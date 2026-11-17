package com.ruoyi.kangaroo.ext.governance;

import java.util.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.security.AppTokenService;

/** Headquarters decisions only. Never debits a proprietor's commission wallet. */
@Service public class KgMerchantSanctions {
    @Autowired private KgJdbc db;
    @Autowired private IKgMessageService messages;
    @Autowired private AppTokenService tokens;
    @Autowired private KgMerchantSettlement settlements;
    @Autowired private KgMerchantCaseEffects effects;
    @Autowired @org.springframework.context.annotation.Lazy private IKgStaffGrantService grants;
    private static final String PARTY="select distinct app_user_id from kg_staff_grant where store_id=:s and status='0' and del_flag='0' and role_code in ('owner','manager','merchant')";
    private void authorized(Long uid,Long store){if(db.count("select count(*) from ("+PARTY+") g where app_user_id=:u",p("s",store,"u",uid))==0)denied();}
    private Map<String,Object> row(Long id){return must(db,"select * from kg_merchant_case where case_id=:id for update",p("id",id),"通知不存在",404);}
    private void log(Long id,String actor,String action,String note){db.insert("kg_merchant_case_log",p("case_id",id,"actor",actor,"action",action,"note",note));}
    private void notifyParty(Long store,Long id,String title,String body){for(Map<String,Object> g:db.list(PARTY,p("s",store)))messages.send(KgJdbc.asLong(g.get("appUserId")),"user","system",title,body,"merchant_case",id,"/pages/governance/merchant-cases?id="+id);}
    /** Refresh every affected employee's sessions; AppTokenService applies this after commit. */
    private void refreshSessions(Long store){
        for(Map<String,Object> g:db.list("select distinct app_user_id from kg_staff_grant where store_id=:s and status='0' and del_flag='0'",p("s",store))){
            Long uid=KgJdbc.asLong(g.get("appUserId"));tokens.refreshGrants(uid,grants.loadActiveGrantViews(uid));
        }
    }
    // 小程序端不给内部字段（谁建的、谁复核的、防重编号、恢复门店用的标记和原状态）；后台照旧
    private static final List<String> INTERNAL=Arrays.asList("createdBy","reviewedBy","requestKey","restoreMarker","previousStatus");
    private static Map<String,Object> forApp(Map<String,Object> c){for(String k:INTERNAL)c.remove(k);return c;}
    @SuppressWarnings("unchecked")
    public Map<String,Object> list(Long uid,int page,boolean admin){Map<String,Object> out=db.page("select c.*,s.store_name from kg_merchant_case c join kg_store s on s.store_id=c.store_id"+(admin?"":" where exists(select 1 from kg_staff_grant g where g.store_id=c.store_id and g.app_user_id=:u and g.status='0' and g.del_flag='0' and g.role_code in ('owner','manager','merchant'))")+" order by c.case_id desc",p("u",uid),Math.max(1,Math.min(10000,page)),20);
        if(!admin&&out.get("rows") instanceof List)for(Map<String,Object> c:(List<Map<String,Object>>)out.get("rows"))forApp(c);return out;}
    public Map<String,Object> detail(Long uid,Long id,boolean admin){Map<String,Object> c=must(db,"select c.*,s.store_name from kg_merchant_case c join kg_store s on s.store_id=c.store_id where c.case_id=:id",p("id",id),"通知不存在",404);if(!admin){authorized(uid,KgJdbc.asLong(c.get("storeId")));forApp(c);}if(admin)c.put("relatedReviews",effects.linked(id)); c.put("settlement",db.one("select fee_month,gross_fen,deduction_fen,net_fen,status,paid_at from kg_merchant_settlement where store_id=:s and fee_month=:m",p("s",c.get("storeId"),"m",c.get("feeMonth"))));c.put("logs",db.list("select action,note,create_time from kg_merchant_case_log where case_id=:id order by log_id",p("id",id)));return c;}
    public List<Map<String,Object>> stores(){return db.list("select store_id,store_name,city,status from kg_store where del_flag='0' order by store_id desc",p());}
    @Transactional(rollbackFor=Exception.class) public Long create(Map<String,Object>b,String actor){
        Long store=id(b.get("storeId"));String request=text(b,"requestKey",8,64),violation=text(b,"violation",1,24),facts=text(b,"facts",10,2000),basis=text(b,"basis",10,2000);
        require(Arrays.asList("positive_fraud","competitor_attack","collusion").contains(violation),"请选择违规类型");
        must(db,"select store_id from kg_store where store_id=:s and del_flag='0' for update",p("s",store),"门店不存在",404);
        Map<String,Object> prior=db.one("select case_id,store_id from kg_merchant_case where request_key=:r",p("r",request));if(prior!=null){require(store.equals(id(prior.get("storeId"))),"请求编号已使用");return id(prior.get("caseId"));}
        require(!db.list(PARTY,p("s",store)).isEmpty(),"请先为该店配置可接收通知和申诉的主理人或合作商家账号");
        require(db.count("select count(*) from kg_merchant_case where store_id=:s and status in ('notified','appealed','suspended','blacklisted')",p("s",store))==0,"该店已有未结束的违规处理，请先处理原记录");
        Long cid=db.insert("kg_merchant_case",p("store_id",store,"request_key",request,"violation",violation,"facts",facts,"basis",basis,"appeal_deadline",now().plusDays(7),"created_by",actor));
        effects.attach(cid,store,violation,b,actor);
        log(cid,actor,"notice",facts+"\n依据："+basis);notifyParty(store,cid,"商家合作复核通知","请查看事实及依据，可在7天内提出申诉。申诉期内尚未执行合作限制。");return cid;
    }
    @Transactional(rollbackFor=Exception.class) public void appeal(Long uid,Long cid,Map<String,Object>b){Map<String,Object> c=row(cid);authorized(uid,id(c.get("storeId")));require("notified".equals(c.get("status"))&&time(c.get("appealDeadline")).isAfter(now()),"申诉期已结束或已提交申诉");String n=text(b,"note",10,2000);db.update("update kg_merchant_case set status='appealed',appeal=:n where case_id=:id",p("id",cid,"n",n));log(cid,"app:"+uid,"appeal",n);}
    @Transactional(rollbackFor=Exception.class) public void decide(Long cid,Map<String,Object>b,String actor){
        // Consistent store -> case lock order with create/expiry and the generic store-edit guard.
        Map<String,Object> lookup=must(db,"select store_id from kg_merchant_case where case_id=:id",p("id",cid),"通知不存在",404);Long store=id(lookup.get("storeId"));
        Map<String,Object> s=must(db,"select status,del_flag from kg_store where store_id=:s for update",p("s",store),"门店不存在",404);Map<String,Object> c=row(cid);
        require(Arrays.asList("notified","appealed").contains(c.get("status")),"已处理，不能重复执行");String n=text(b,"note",10,2000);boolean execute=Boolean.TRUE.equals(b.get("execute"));
        if(execute)require(!time(c.get("appealDeadline")).isAfter(now()),"七天申诉期未结束，不能执行");
        effects.apply(cid,execute,b,actor);
        if(!execute){db.update("update kg_merchant_case set status='cancelled',response=:n,reviewed_by=:a where case_id=:id",p("n",n,"a",actor,"id",cid));log(cid,actor,"cancel",n);notifyParty(store,cid,"商家复核已撤销",n);return;}
        require(!time(c.get("appealDeadline")).isAfter(now()),"七天申诉期未结束，不能执行");require("0".equals(s.get("delFlag")),"门店已删除，请先核查门店记录");
        boolean blacklist="competitor_attack".equals(c.get("violation"));String marker="sanction:"+cid;
        db.update("update kg_merchant_case set status=:st,response=:n,reviewed_by=:a,executed_at=:now,ends_at=:ends,previous_status=:ps,restore_marker=:m,fee_month=:month,fee_state=:fee where case_id=:id",p("st",blacklist?"blacklisted":"suspended","n",n,"a",actor,"now",now(),"ends",blacklist?null:now().plusDays(30),"ps",s.get("status"),"m",marker,"month",now().toLocalDate().toString().substring(0,7),"fee",blacklist?"none":"awaiting_settlement_basis","id",cid));
        if(!blacklist)settlements.applyPenalty(store,now().toLocalDate().toString().substring(0,7),cid,actor);
        db.update("update kg_store set status='1',update_by=:m,update_time=now() where store_id=:s",p("m",marker,"s",store));log(cid,actor,blacklist?"blacklist":"suspend",n);notifyParty(store,cid,"商家合作复核结果",(blacklist?"本店已解除合作并列入合作黑名单。":"本店合作暂停30天，当月服务费分成扣除50%已关联财务结算单，金额以实际分成对账依据为准。")+"复核说明："+n);
        refreshSessions(store);
    }
    /** Used by the store-edit guard, transaction locked so admin edits cannot race a sanction. */
    public void assertCanEnable(Long store){must(db,"select store_id from kg_store where store_id=:s for update",p("s",store),"门店不存在",404);require(db.count("select count(*) from kg_merchant_case where store_id=:s and status in ('suspended','blacklisted')",p("s",store))==0,"该店处于合作处罚中，不能在门店编辑中直接恢复");}
    @Transactional(rollbackFor=Exception.class) public void expire(Long cid){
        Map<String,Object> lookup=db.must("select store_id from kg_merchant_case where case_id=:id",p("id",cid),"通知不存在");Long store=id(lookup.get("storeId"));Map<String,Object> s=db.must("select status,del_flag,update_by from kg_store where store_id=:s for update",p("s",store),"门店不存在");Map<String,Object> c=row(cid);
        if(!"suspended".equals(c.get("status"))||time(c.get("endsAt")).isAfter(now()))return;
        boolean restore="0".equals(c.get("previousStatus"))&&"0".equals(s.get("delFlag"))&&"1".equals(s.get("status"))&&Objects.equals(c.get("restoreMarker"),s.get("updateBy"));
        if(restore){db.update("update kg_store set status='0',update_by='sanction-expiry',update_time=now() where store_id=:s",p("s",store));refreshSessions(store);}
        db.update("update kg_merchant_case set status=:st where case_id=:id",p("st",restore?"expired":"expired_review","id",cid));String n=restore?"30天合作限制已到期，恢复处罚前的正常门店状态。":"合作限制已到期，门店原已停用或后来被修改，保留停用状态，请管理员核实后手动恢复。";log(cid,"system","expire",n);notifyParty(store,cid,"合作限制期限已结束",n);
    }
}
