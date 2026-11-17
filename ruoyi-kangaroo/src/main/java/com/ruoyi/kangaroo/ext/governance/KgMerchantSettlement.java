package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Merchant contractual service-fee shares. Never uses employee commission balances. */
@Service public class KgMerchantSettlement {
    @Autowired private KgJdbc db;
    private void log(Long id,String action,String actor,String note){db.insert("kg_merchant_settlement_log",p("settlement_id",id,"action",action,"actor",actor,"note",note));}
    public Map<String,Object> list(int page){return db.page("select f.*,s.store_name from kg_merchant_settlement f join kg_store s on s.store_id=f.store_id order by f.fee_month desc,f.settlement_id desc",p(),Math.max(1,Math.min(10000,page)),20);}
    public Map<String,Object> detail(Long id){Map<String,Object> r=must(db,"select f.*,s.store_name from kg_merchant_settlement f join kg_store s on s.store_id=f.store_id where settlement_id=:id",p("id",id),"结算单不存在",404);r.put("logs",db.list("select action,actor,note,create_time from kg_merchant_settlement_log where settlement_id=:id order by log_id",p("id",id)));return r;}
    /** Called under the same store lock and transaction as the headquarters decision. */
    public void applyPenalty(Long store,String month,Long caseId,String actor){
        db.update("insert ignore into kg_merchant_settlement(store_id,fee_month,penalty_case_id,penalty_bp) values(:s,:m,:c,5000)",p("s",store,"m",month,"c",caseId));
        Map<String,Object> row=db.must("select * from kg_merchant_settlement where store_id=:s and fee_month=:m for update",p("s",store,"m",month),"结算单不存在");
        require(!"paid".equals(row.get("status")),"该月分成已结清，请先由财务复核，不能覆盖已付款账单");
        if(row.get("penaltyCaseId")==null){
            db.update("update kg_merchant_settlement set penalty_case_id=:c,penalty_bp=5000,status='draft',reviewed_by=null,version=version+1,update_time=now() where settlement_id=:id",p("c",caseId,"id",row.get("settlementId")));
            row.put("penaltyBp",5000);
        }
        recalculate(row);
        log(id(row.get("settlementId")),"penalty",actor,"关联商家复核 #"+caseId+"，当月服务费分成扣除50%；同一月份不重复叠加。");
        db.update("update kg_merchant_case set fee_state='settlement_linked' where case_id=:c",p("c",caseId));
    }
    private void recalculate(Map<String,Object> r){
        if(r.get("grossFen")==null)return;
        long gross=KgJdbc.asLong(r.get("grossFen"));long deduction=KgJdbc.asLong(r.get("penaltyBp"))==5000?(gross+1)/2:0;
        db.update("update kg_merchant_settlement set deduction_fen=:d,net_fen=:n,update_time=now() where settlement_id=:id",p("d",deduction,"n",gross-deduction,"id",r.get("settlementId")));
    }
    static long money(Object value){try{long v=new BigDecimal(String.valueOf(value)).longValueExact();require(v>=0&&v<=100000000000L,"分成金额须为0至十亿元，精确到分");return v;}catch(RuntimeException e){throw new com.ruoyi.common.exception.ServiceException("分成金额无效，请精确到分",422);}}
    @Transactional(rollbackFor=Exception.class) public Long save(Map<String,Object>b,String actor){
        Long store=id(b.get("storeId"));String month=text(b,"month",7,7),basis=text(b,"basis",10,2000);long gross=money(b.get("grossFen"));
        try{YearMonth.parse(month);}catch(Exception e){require(false,"月份格式应为YYYY-MM");}
        require(month.compareTo(YearMonth.from(now()).toString())<=0,"不能录入未来月份");
        must(db,"select store_id from kg_store where store_id=:s and del_flag='0' for update",p("s",store),"门店不存在",404);
        db.update("insert ignore into kg_merchant_settlement(store_id,fee_month) values(:s,:m)",p("s",store,"m",month));
        Map<String,Object> r=db.must("select * from kg_merchant_settlement where store_id=:s and fee_month=:m for update",p("s",store,"m",month),"结算单不存在");
        require("draft".equals(r.get("status")),"已审核或已付款，不能修改原始账单");
        require(b.get("version")!=null&&KgJdbc.asLong(b.get("version")).equals(KgJdbc.asLong(r.get("version"))),"账单已更新，请刷新后核对金额");
        db.update("update kg_merchant_settlement set gross_fen=:g,basis=:b,prepared_by=:a,version=version+1 where settlement_id=:id",p("g",gross,"b",basis,"a",actor,"id",r.get("settlementId")));
        r.put("grossFen",gross);recalculate(r);log(id(r.get("settlementId")),"basis",actor,"应分成 "+gross+" 分；依据："+basis);return id(r.get("settlementId"));
    }
    @Transactional(rollbackFor=Exception.class) public void command(Long sid,String action,Map<String,Object>b,String actor){
        Map<String,Object> lookup=must(db,"select store_id from kg_merchant_settlement where settlement_id=:id",p("id",sid),"结算单不存在",404);
        must(db,"select store_id from kg_store where store_id=:s for update",p("s",lookup.get("storeId")),"门店不存在",404);
        Map<String,Object> r=must(db,"select * from kg_merchant_settlement where settlement_id=:id for update",p("id",sid),"结算单不存在",404);
        require(b.get("version")!=null&&KgJdbc.asLong(b.get("version")).equals(KgJdbc.asLong(r.get("version"))),"账单已更新，请刷新后再处理");
        String note=text(b,"note",5,1000);
        if("return".equals(action)){
            require("approved".equals(r.get("status")),"仅待付款账单可退回");
            db.update("update kg_merchant_settlement set status='draft',reviewed_by=null,version=version+1 where settlement_id=:id",p("id",sid));log(sid,"return",actor,note);return;
        }
        require(r.get("grossFen")!=null,"财务尚未录入实际分成金额和依据");
        require(YearMonth.parse(str(r.get("feeMonth"))).isBefore(YearMonth.from(now())),"当月尚未结束，请次月核对完整账期后结算");
        require(db.count("select count(*) from kg_merchant_case where store_id=:s and status in ('notified','appealed')",p("s",r.get("storeId")))==0,"商家有待复核事项，请复核完成后结算");
        if("approve".equals(action)){
            require("draft".equals(r.get("status")),"仅草稿可审核");
            require(!actor.equals(r.get("preparedBy")),"录入人与复核人须不同，请另一位财务管理员复核");
            db.update("update kg_merchant_settlement set status='approved',reviewed_by=:a,version=version+1 where settlement_id=:id",p("id",sid,"a",actor));
        }else if("paid".equals(action)){
            require("approved".equals(r.get("status")),"仅已审核账单可登记付款");
            String proof=text(b,"proof",5,500);require(money(b.get("paidFen"))==KgJdbc.asLong(r.get("netFen")),"实付金额必须与扣除处罚后的应付金额一致");
            db.update("update kg_merchant_settlement set status='paid',paid_by=:a,paid_proof=:p,paid_at=now(),version=version+1 where settlement_id=:id",p("id",sid,"a",actor,"p",proof));
            db.update("update kg_merchant_case set fee_state='settled' where store_id=:s and fee_month=:m and fee_state='settlement_linked'",p("s",r.get("storeId"),"m",r.get("feeMonth")));
            note+="；付款凭证："+proof;
        }else require(false,"不支持的结算操作");
        log(sid,action,actor,note);
    }
}
