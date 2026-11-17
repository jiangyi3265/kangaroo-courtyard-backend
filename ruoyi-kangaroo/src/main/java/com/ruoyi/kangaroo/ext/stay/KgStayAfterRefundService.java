package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.enums.MessageCategory;

/** Completed stays keep their occupancy state. Applying never invokes the payment channel. */
@Service
public class KgStayAfterRefundService {
    private final KgJdbc db;
    private final KgPayService pay;
    private final StaffSupport support;
    public KgStayAfterRefundService(KgJdbc db,KgPayService pay,StaffSupport support){this.db=db;this.pay=pay;this.support=support;}
    static String str(Object v){return v==null?"":String.valueOf(v);}
    static long amount(Object v){Long n=KgJdbc.asLong(v);return n==null?0:n;}
    static final String JOIN="select a.*,r.status channel_status from kg_stay_after_refund a left join kg_stay_staff_refund r on r.refund_id=a.staff_refund_id ";

    private Map<String,Object> own(Long uid,String no){
        Map<String,Object> o=db.one("select * from kg_stay_order where order_no=:n and app_user_id=:u and del_flag='0'",p("n",no,"u",uid));
        if(o==null || o.isEmpty())throw new ServiceException("订单不存在",404);
        return o;
    }
    private Map<String,Object> payment(Map<String,Object> o,boolean lock){
        if(str(o.get("payNo")).isEmpty())return Collections.emptyMap();
        Map<String,Object> r=db.one("select * from kg_pay_order where pay_no=:n and del_flag='0'"+(lock?" for update":""),p("n",o.get("payNo")));
        return r==null?Collections.emptyMap():r;
    }
    private boolean processing(Map<String,Object> o){
        return db.count("select count(*) from kg_pay_refund where pay_no=:n and "+KgPayService.ACTIVE_REFUND_SQL+" for update",p("n",o.get("payNo")))>0
            || db.count("select count(*) from kg_stay_staff_refund where order_id=:o and status='processing' for update",p("o",o.get("orderId")))>0;
    }
    private String blocked(Map<String,Object> o,Map<String,Object> payment){
        if(!Arrays.asList("checked_in","completed").contains(str(o.get("status"))))return "入住后可申请提前退房或退房后退款";
        if(payment.isEmpty() || !Arrays.asList("paid","part_refunded").contains(str(payment.get("status"))) || remaining(payment)<=0)return "当前没有可申请退款的实付余额";
        if(processing(o))return "已有退款正在处理，请等待渠道结果后再申请";
        if(db.count("select count(*) from kg_stay_after_refund where order_id=:o and status='pending' for update",p("o",o.get("orderId")))>0)return "已有退款申请待平台审核";
        return "";
    }
    static long remaining(Map<String,Object> payment){return Math.max(0,amount(payment.get("amount"))-amount(payment.get("refundAmount")));}
    static Map<String,Object> publicRow(Map<String,Object> row){
        Map<String,Object> out=new LinkedHashMap<>();
        for(String k:Arrays.asList("requestId","amountFen","reason","status","reviewNote","reviewedTime","createTime","staffRefundId"))out.put(k,row.get(k));
        String state=str(row.get("status"));
        if("approved".equals(state))state="success".equals(row.get("channelStatus"))?"success":("closed".equals(row.get("channelStatus"))?"closed":"processing");
        out.put("state",state);
        out.put("statusLabel","pending".equals(state)?"待平台审核":"rejected".equals(state)?"审核未通过":"success".equals(state)?"已退款":"closed".equals(state)?"退款未完成":"退款处理中");
        return out;
    }
    public Map<String,Object> overview(Long uid,String no){
        Map<String,Object> o=own(uid,no),payment=payment(o,false);
        String reason=blocked(o,payment);
        List<Map<String,Object>> records=new ArrayList<>();
        for(Map<String,Object> row:db.list(JOIN+"where a.order_id=:o order by a.request_id desc",p("o",o.get("orderId"))))records.add(publicRow(row));
        Map<String,Object> result = p("allowed",reason.isEmpty(),"reason",reason,"paidFen",amount(payment.get("amount")),"refundedFen",amount(payment.get("refundAmount")),
            "maxRefundFen",reason.isEmpty()?remaining(payment):0,"records",records);
        result.putAll(StayUnusedNights.estimate(o,amount(payment.get("amount")),amount(payment.get("refundAmount")),java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
        result.put("awaitingCheckout","checked_in".equals(o.get("status")));
        return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> apply(Long uid,String no,long fen,String reason,String key){
        reason=str(reason).trim();key=str(key).trim();
        if(fen<1)throw new ServiceException("申请金额至少 0.01 元",422);
        if(reason.isEmpty()||reason.length()>100)throw new ServiceException("请填写退款原因（1–100字）",422);
        if(!key.matches("[A-Za-z0-9_-]{8,64}"))throw new ServiceException("提交编号无效，请刷新后重试",422);
        Map<String,Object> head=own(uid,no);
        // Consistent with payment callbacks and staff refunds: payment -> stay -> request.
        Map<String,Object> payment=payment(head,true);
        Map<String,Object> o=db.must("select * from kg_stay_order where order_id=:o and app_user_id=:u and del_flag='0' for update",p("o",head.get("orderId"),"u",uid),"订单不存在");
        Map<String,Object> prior=db.one(JOIN+"where a.order_id=:o and a.request_key=:k for update",p("o",o.get("orderId"),"k",key));
        if(prior!=null&&!prior.isEmpty()){
            if(amount(prior.get("amountFen"))!=fen||!reason.equals(str(prior.get("reason"))))throw new ServiceException("同一提交编号的金额或原因已变化，请重新提交",409);
            return publicRow(prior);
        }
        String why=blocked(o,payment);if(!why.isEmpty())throw new ServiceException(why,409);
        pay.requireRefundable(payment);
        if(fen>remaining(payment))throw new ServiceException("申请金额不能超过当前可退余额 ¥"+KgStayBrowseService.yuan(remaining(payment)),422);
        long id=db.insert("kg_stay_after_refund",p("order_id",o.get("orderId"),"order_no",no,"app_user_id",uid,"store_id",o.get("storeId"),"pay_no",o.get("payNo"),
            "amount_fen",fen,"reason",reason,"status","pending","request_key",key,"create_time",new Date()));
        support.notifyStore(KgJdbc.asLong(o.get("storeId")),MessageCategory.ORDER,"客人申请退房退款",
            "订单 "+no+" 已提交申请，请核实实际退房情况并办理退房；退款由平台审核。","stay_order",KgJdbc.asLong(o.get("orderId")),"/pages/staff/stay-orders?orderNo="+no);
        return publicRow(db.must(JOIN+"where a.request_id=:id",p("id",id),"退款申请不存在"));
    }
    private Long staffOwner(StaffSupport.Ctx c,String no) {
        support.need(c,"rooms",com.ruoyi.kangaroo.ext.staff.StaffPerms.EDIT,"stay_refund_apply","申请退房退款");
        Map<String,Object> o=db.must("select app_user_id from kg_stay_order where order_no=:n and store_id=:s and del_flag='0'",p("n",no,"s",c.storeId),"订单不存在或不属于当前门店");
        return KgJdbc.asLong(o.get("appUserId"));
    }
    public Map<String,Object> staffOverview(StaffSupport.Ctx c,String no){return overview(staffOwner(c,no),no);}
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> staffApply(StaffSupport.Ctx c,String no,long fen,String reason,String key){
        Long owner=staffOwner(c,no);
        Map<String,Object> result=apply(owner,no,fen,reason,key);
        support.op(c,"stay_refund_apply","代客申请退款","general",no,true,null);
        return result;
    }
    public Map<String,Object> list(String status,String no,int page,int size){
        if(!str(status).isEmpty()&&!Arrays.asList("pending","approved","rejected").contains(status))throw new ServiceException("审核状态无效",422);
        String where="where 1=1";Map<String,Object> args=p();
        if(!str(status).isEmpty()){where+=" and a.status=:s";args.put("s",status);}
        if(!str(no).trim().isEmpty()){where+=" and a.order_no=:n";args.put("n",no.trim());}
        String reviewJoin="select a.*,r.status channel_status,s.store_name,o.check_in,o.check_out,o.checked_out_time,"
            +"p.amount paid_fen,p.refund_amount refunded_fen,greatest(0,p.amount-p.refund_amount) remaining_fen "
            +"from kg_stay_after_refund a left join kg_stay_staff_refund r on r.refund_id=a.staff_refund_id "
            +"join kg_stay_order o on o.order_id=a.order_id join kg_pay_order p on p.pay_no=a.pay_no "
            +"left join kg_store s on s.store_id=a.store_id ";
        return db.page(reviewJoin+where+" order by a.request_id desc",args,Math.max(1,page),Math.max(1,Math.min(100,size)));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> review(Long id,boolean approve,String note,String operator){
        Map<String,Object> head=db.must("select * from kg_stay_after_refund where request_id=:id",p("id",id),"退款申请不存在");
        Map<String,Object> payment=payment(head,true);
        Map<String,Object> o=db.must("select * from kg_stay_order where order_id=:o and del_flag='0' for update",p("o",head.get("orderId")),"订单不存在");
        Map<String,Object> request=db.must("select * from kg_stay_after_refund where request_id=:id for update",p("id",id),"退款申请不存在");
        if(!"pending".equals(request.get("status")))throw new ServiceException("这笔申请已经审核，请刷新查看结果",409);
        note=str(note).trim();if(note.length()>200||(!approve&&note.isEmpty()))throw new ServiceException("请填写驳回原因（1–200字）",422);
        Long refundId=null;
        if(approve){
            if(!"completed".equals(o.get("status")))throw new ServiceException("请先由门店核实并办理退房，再审核退款",409);
            if(!Arrays.asList("paid","part_refunded").contains(str(payment.get("status"))))throw new ServiceException("支付单当前不可退款",409);
            pay.requireRefundable(payment);
            if(processing(o))throw new ServiceException("已有退款处理中，待渠道结果确认后再审核",409);
            long fen=amount(request.get("amountFen"));
            if(fen<=0||fen>remaining(payment))throw new ServiceException("实付可退余额已变化，请核对已退款记录后处理申请",409);
            // Reuse the existing durable refund ledger and callback reconciliation; occupancy remains completed.
            refundId=db.insert("kg_stay_staff_refund",p("order_id",o.get("orderId"),"order_no",o.get("orderNo"),"store_id",o.get("storeId"),"pay_no",o.get("payNo"),
                "amount_fen",fen,"reason",request.get("reason"),"kind","partial","status","processing","idem_key","after-refund-"+id,
                "operator_user_id",null,"operator_name",StaffSupport.cut("平台："+operator,64),"create_time",new Date()));
            pay.refund(str(o.get("payNo")),fen,str(request.get("reason")),StaffSupport.cut(operator,64));
        }
        int n=db.update("update kg_stay_after_refund set status=:s,staff_refund_id=:r,review_note=:n,reviewed_by=:b,reviewed_time=now() where request_id=:id and status='pending'",
            p("s",approve?"approved":"rejected","r",refundId,"n",note,"b",StaffSupport.cut(operator,64),"id",id));
        if(n!=1)throw new ServiceException("申请已被其他人处理，请刷新",409);
        support.notifyUser(KgJdbc.asLong(request.get("appUserId")),MessageCategory.ORDER,approve?"退款申请已通过":"退款申请未通过",
            "订单 "+o.get("orderNo")+(approve?" 的退款申请已通过，到账以支付渠道结果为准。":" 的退款申请未通过："+note),
            "stay_order",KgJdbc.asLong(o.get("orderId")),"/pages/stays/order?orderNo="+o.get("orderNo")+"&view=refund");
        return publicRow(db.must(JOIN+"where a.request_id=:id",p("id",id),"退款申请不存在"));
    }
}
