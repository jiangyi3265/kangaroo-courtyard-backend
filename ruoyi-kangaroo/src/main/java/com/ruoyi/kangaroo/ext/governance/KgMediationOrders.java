package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;

/** All order IDs, parties and cities come from authoritative order records. */
@Service public class KgMediationOrders {
    @Autowired private KgJdbc db;
    public void lock(String type,Long oid){
        String table="application".equals(type)?"kg_application":"stay".equals(type)?"kg_stay_order":"activity".equals(type)?"kg_activity_signup":null;
        require(table!=null,"不支持此订单类型");String key="application".equals(type)?"app_id":"stay".equals(type)?"order_id":"signup_id";
        must(db,"select "+key+" from "+table+" where "+key+"=:id for update",p("id",oid),"订单不存在",404);
    }
    public boolean merchant(Long uid,Long store){return db.count("select count(*) from kg_staff_grant g join kg_app_user u on u.user_id=g.app_user_id where g.app_user_id=:u and g.store_id=:s and g.role_code='merchant' and g.status='0' and g.del_flag='0' and u.status='0' and u.del_flag='0'",p("u",uid,"s",store))>0;}
    public List<Long> merchants(Long store){List<Long> out=new ArrayList<>();for(Map<String,Object> r:db.list("select distinct g.app_user_id from kg_staff_grant g join kg_app_user u on u.user_id=g.app_user_id where g.store_id=:s and g.role_code='merchant' and g.status='0' and g.del_flag='0' and u.status='0' and u.del_flag='0'",p("s",store)))out.add(id(r.get("appUserId")));return out;}
    /** 调解已出结果后再发起的提示（2026-11-09 主人拍板，双方都一样） */
    public static final String SETTLED="这笔订单的调解已结案，不能再次发起";
    /**
     * 这笔订单的调解是否已经出了结果：工单已关闭，且是「裁决后双方都接受」或「总部终局复核」关的（close 只有这两个入口）。
     * 撤回的、没出结果就关闭的（没有裁决 / 复核结果）不算，仍可再发起
     */
    public boolean settled(String type,Long oid){
        return db.count("select count(*) from kg_mediation_case where order_type=:t and order_id=:o and status='closed' "
                +"and (hq_result is not null or (decision is not null and user_confirm='accepted' and merchant_confirm='accepted'))",p("t",type,"o",oid))>0;
    }
    /** 已结案的不再抛错：返回 eligible=false 和原因，发起页照原因显示；其他不可调解的照旧报错 */
    public Map<String,Object> mineOne(Long uid,String type,Long oid){
        Map<String,Object> o=order(type,oid);
        if(!uid.equals(id(o.get("userId")))&&!merchant(uid,id(o.get("storeId"))))denied();
        boolean done=settled(type,oid),eligible=!done&&eligible(o);
        Map<String,Object> out=p("orderId",oid,"orderType",type,"orderNo",o.get("orderNo"),"storeName",o.get("storeName"),"eligible",eligible);
        existingCase(out,uid,type,oid);
        if(done)out.put("reason",SETTLED);
        else if(!eligible)out.put("reason","订单当前不可申请调解");
        // 历史工单继续可查，不受新申请时限影响；新申请资格仍独立判定。
        if(!eligible&&!out.containsKey("existingCaseId")&&!done)require(false,"订单当前不可申请调解");
        return out;
    }
    private void existingCase(Map<String,Object> out,Long uid,String type,Long oid){
        // 工单保留发起时的门店。岗位换店后，新门店不能读到旧门店工单的编号和状态。
        Map<String,Object> c=db.one("select c.case_id,c.status from kg_mediation_case c where c.order_type=:t and c.order_id=:o "
                +"and (c.user_id=:u or exists(select 1 from kg_staff_grant g join kg_app_user u on u.user_id=g.app_user_id "
                +"where g.app_user_id=:u and g.store_id=c.store_id and g.role_code='merchant' and g.status='0' and g.del_flag='0' and u.status='0' and u.del_flag='0')) "
                +"order by (c.status<>'closed') desc,c.case_id desc limit 1",p("t",type,"o",oid,"u",uid));
        if(c!=null&&KgJdbc.asLong(c.get("caseId"))!=null){out.put("existingCaseId",c.get("caseId"));out.put("existingCaseStatus",c.get("status"));}
    }
    public Map<String,Object> order(String type,Long oid){
        String sql;
        // 签约后被终止（contract_signed→terminated）的报名不会有 volunteer_done_time（只在入职时写），按终止那条状态日志的时间起算 15 天；
        // 签约前就终止的取不到这条日志，completed_at 为空，照旧不能申请
        if("application".equals(type))sql="select a.app_id order_id,a.app_no order_no,a.app_user_id user_id,coalesce(j.recruit_store_id,a.store_id) store_id,a.status,coalesce(a.volunteer_done_time,case when a.status='terminated' then (select max(l.create_time) from kg_application_log l where l.app_id=a.app_id and l.from_status='contract_signed' and l.to_status='terminated') end) completed_at,j.job_type title from kg_application a join kg_job j on j.job_id=a.job_id where a.app_id=:id and a.del_flag='0' and j.job_type in ('volunteer','parttime','fulltime')";
        else if("stay".equals(type))sql="select order_id,order_no,app_user_id user_id,store_id,status,checked_out_time completed_at,'旅居' title from kg_stay_order where order_id=:id and del_flag='0'";
        else if("activity".equals(type))sql="select g.signup_id order_id,g.signup_no order_no,g.app_user_id user_id,a.store_id,g.status,a.end_time completed_at,a.title,g.checkin_time,a.status activity_status from kg_activity_signup g join kg_activity a on a.activity_id=g.activity_id where g.signup_id=:id and g.del_flag='0' and a.store_id is not null";
        else throw new ServiceException("不支持此订单类型",422);
        Map<String,Object> r=must(db,sql,p("id",oid),"订单不存在",404);
        Map<String,Object> store=must(db,"select store_name,city from kg_store where store_id=:s",p("s",r.get("storeId")),"订单门店不存在",404);r.putAll(store);r.put("orderType",type);return r;
    }
    public boolean eligible(Map<String,Object> o){
        String state=str(o.get("status")),type=str(o.get("orderType"));
        // A signed applicant may need help precisely because arrival is disputed;
        // eligibility must not depend on the merchant confirming arrival first.
        if("application".equals(type)&&Arrays.asList("contract_signed","onboarded","completed").contains(state))return true;
        if("stay".equals(type)&&Arrays.asList("checked_in","completed").contains(state))return true;
        if("activity".equals(type)&&"confirmed".equals(state)&&o.get("checkinTime")!=null)return true;
        if(Arrays.asList("terminated","closed","cancelled","refunded").contains(state)&&o.get("completedAt")!=null&&(!"activity".equals(type)||o.get("checkinTime")!=null)){try{java.time.LocalDateTime end=time(str(o.get("completedAt")).length()==10?o.get("completedAt")+" 00:00:00":o.get("completedAt"));return !end.isAfter(now())&&!end.plusDays(15).isBefore(now());}catch(ServiceException ignored){return false;}}
        return false;
    }
    public Map<String,Object> mine(Long uid,String role,int page){
        boolean shop="merchant".equals(role);
        String scope=shop?"o.store_id in(select store_id from kg_staff_grant where app_user_id=:u and role_code='merchant' and status='0' and del_flag='0')":"o.user_id=:u";
        String union="select 'application' order_type,a.app_id order_id,a.app_no order_no,a.app_user_id user_id,coalesce(j.recruit_store_id,a.store_id) store_id,a.status,j.title from kg_application a join kg_job j on j.job_id=a.job_id where a.del_flag='0' and j.job_type in ('volunteer','parttime','fulltime') and a.status in ('contract_signed','onboarded','completed','terminated') union all select 'stay',order_id,order_no,app_user_id,store_id,status,'旅居订单' from kg_stay_order where del_flag='0' and status in ('checked_in','completed','cancelled','refunded','closed') union all select 'activity',g.signup_id,g.signup_no,g.app_user_id,a.store_id,g.status,a.title from kg_activity_signup g join kg_activity a on a.activity_id=g.activity_id where g.del_flag='0' and a.store_id is not null and (g.checkin_time is not null or g.status in ('closed','cancelled'))";
        Map<String,Object> result=db.page("select o.*,s.store_name from ("+union+") o join kg_store s on s.store_id=o.store_id where "+scope+" order by o.order_id desc",p("u",uid),page,20);
        for(Map<String,Object> r:(List<Map<String,Object>>)result.get("rows")){
            boolean done=settled(str(r.get("orderType")),id(r.get("orderId")));
            r.put("eligible",!done&&eligible(order(str(r.get("orderType")),id(r.get("orderId")))));
            if(done)r.put("reason",SETTLED);
            existingCase(r,uid,str(r.get("orderType")),id(r.get("orderId")));
        }
        return result;
    }
}
