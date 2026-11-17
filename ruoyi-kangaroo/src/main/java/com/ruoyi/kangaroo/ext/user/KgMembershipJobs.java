package com.ruoyi.kangaroo.ext.user;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Bounded recovery: source events and unique ledger keys make every replay safe. */
@Component
public class KgMembershipJobs {
    private static final Logger LOG=LoggerFactory.getLogger(KgMembershipJobs.class);
    @Autowired private KgJdbc db;
    @Autowired private KgMembershipRewards rewards;
    @Autowired private KgMembershipOperations operations;
    @Autowired private KgMemberWallet wallet;
    private long cursor=0;
    @Scheduled(fixedDelay=120000,initialDelay=120000)
    public synchronized void reconcile(){
        try{
            // Stopping new issuance must not strand already-issued activity entitlements.
            wallet.reconcileActivities();
            if(!rewards.enabled())return;
            List<Map<String,Object>> users=db.list("select user_id from kg_app_user where user_id>:id and status='0' and del_flag='0' and user_id not in(select app_user_id from kg_member_restriction where frozen='1') order by user_id limit 200",p("id",cursor));
            if(users.isEmpty())cursor=0;
            for(Map<String,Object> user:users){Long uid=KgJdbc.asLong(user.get("userId"));try{rewards.sync(uid);wallet.claim(uid);}catch(Exception ex){LOG.warn("Member reconciliation failed uid={}",uid,ex);}cursor=uid;}
            operations.weeklyHot();
        }catch(Exception ex){LOG.warn("Member reconciliation unavailable",ex);}
    }
}
