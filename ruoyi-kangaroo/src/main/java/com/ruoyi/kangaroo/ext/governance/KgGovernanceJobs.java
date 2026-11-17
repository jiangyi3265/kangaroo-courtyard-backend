package com.ruoyi.kangaroo.ext.governance;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
@Component public class KgGovernanceJobs {
    private static final Logger LOG=LoggerFactory.getLogger(KgGovernanceJobs.class);
    @Autowired private KgJdbc db;
    @Autowired private KgMediationService mediation;
    @Autowired private KgBadgeService badges;
    @Autowired private KgMerchantSanctions sanctions;
    @Scheduled(fixedDelay=60000,initialDelay=100000) public void merchantDeadlines(){
        for(Map<String,Object> c:db.list("select case_id from kg_merchant_case where status='suspended' and ends_at<=now() order by case_id limit 100",p()))try{sanctions.expire(KgJdbc.asLong(c.get("caseId")));}catch(Exception e){LOG.error("Merchant sanction expiry failed id={}",c.get("caseId"),e);}
    }
    private long badgeCursor=0;
    @Scheduled(fixedDelay=60000,initialDelay=120000) public void reconcileBadges(){
        java.util.List<Map<String,Object>> users=db.list("select user_id from kg_app_user where user_id>:u and status='0' and del_flag='0' order by user_id limit 50",p("u",badgeCursor));
        if(users.isEmpty()){badgeCursor=0;return;}
        for(Map<String,Object> u:users){badgeCursor=KgJdbc.asLong(u.get("userId"));try{badges.sync(badgeCursor);}catch(Exception e){LOG.error("Badge reconcile failed uid={}",badgeCursor,e);}}
    }
    @Scheduled(fixedDelay=60000,initialDelay=90000) public void deadlines(){
        for(Map<String,Object> r:db.list("select round_id from kg_mediation_round where status='open' and deadline<=now() limit 100",p()))try{mediation.finishRound(KgJdbc.asLong(r.get("roundId")));}catch(Exception e){LOG.error("Jury close failed id={}",r.get("roundId"),e);}
        for(Map<String,Object> r:db.list("select case_id from kg_mediation_case where (status='pending' and accept_deadline<=now() and not exists(select 1 from kg_mediation_event e where e.event_key=concat(kg_mediation_case.case_id,':overdue'))) or (status='confirmation' and confirm_deadline<=now() and not exists(select 1 from kg_mediation_event e where e.event_key=concat(kg_mediation_case.case_id,':confirmation_due'))) order by case_id limit 500",p()))try{mediation.deadlines(KgJdbc.asLong(r.get("caseId")));}catch(Exception e){LOG.error("Mediation deadline failed id={}",r.get("caseId"),e);}
    }
}
