package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.user.KgMemberWallet.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;

/** Completion-backed rewards. Reconciliation makes missed callbacks retryable and idempotent. */
@Service
public class KgMembershipRewards {
    @Autowired private KgJdbc db;
    @Autowired private KgPointLedger ledger;
    @Autowired private KgMemberWallet wallet;
    @Autowired private KgCommunityGuard guard;
    @Autowired private KgMemberReviewQueue reviewQueue;
    public boolean enabled(){return wallet.enabled();}
    private String starts(){return str(db.must("select starts_at from kg_member_execution where id=1",p(),"会员规则未配置").get("startsAt"));}
    /** 会员规则下报名能评价的口径（a=kg_application，j=kg_job）：换工 / 兼职要门店确认完成；全职没有「确认完成」这一步，已入职即可；线上任务不评。
     *  待评价列表、订单页「去评价」、岗位详情写评价入口、提交校验都用这一条：列出来的就能评，评不了的不列 */
    public static final String APP_REVIEWABLE_SQL="a.status in ('onboarded','completed') and (j.job_type='fulltime' or (j.job_type in ('volunteer','parttime') and a.volunteer_done_time is not null))";
    /** 会员规则没启用时的老口径：已入职（onboarded / completed）就能评 */
    public static final String APP_REVIEWABLE_LEGACY_SQL="a.status in ('onboarded','completed')";
    /** 会员规则下活动报名能评价的口径（g=kg_activity_signup，a=kg_activity）：只有签到过的会员培训活动 */
    public static final String ACTIVITY_REVIEWABLE_SQL="g.status='confirmed' and g.checkin_time is not null and a.member_training='1' and a.status='ended' and coalesce(a.end_time,date_add(a.start_time,interval 4 hour))<=now()";
    public String applicationReviewableSql(){return enabled()?APP_REVIEWABLE_SQL:APP_REVIEWABLE_LEGACY_SQL;}
    public boolean reviewable(Long uid,String type,Long id){
        if("application".equals(type))return db.count("select count(*) from kg_application a join kg_job j on j.job_id=a.job_id where a.app_id=:id and a.app_user_id=:u and a.del_flag='0' and "+APP_REVIEWABLE_SQL,p("id",id,"u",uid))>0;
        if("stay_order".equals(type))return db.count("select count(*) from kg_stay_order where order_id=:id and app_user_id=:u and status='completed' and del_flag='0'",p("id",id,"u",uid))>0;
        if("activity_signup".equals(type))return db.count("select count(*) from kg_activity_signup g join kg_activity a on a.activity_id=g.activity_id where g.signup_id=:id and g.app_user_id=:u and g.del_flag='0' and "+ACTIVITY_REVIEWABLE_SQL,p("id",id,"u",uid))>0;
        return false;
    }
    public void requireReviewAllowed(Long uid){Map<String,Object> r=db.one("select * from kg_member_restriction where app_user_id=:u",p("u",uid));if(r!=null&&("1".equals(r.get("frozen"))||str(r.get("reviewsBlockedUntil")).compareTo(LocalDateTime.now(KgMembershipPolicy.ZONE).toString().replace('T',' '))>0))throw new ServiceException("当前账号暂不能评价，请查看平台通知",403);}
    @Transactional(rollbackFor=Exception.class)
    public void sync(Long uid){
        if(!enabled())return;
        Map<String,Object> account=db.one("select user_id,status,del_flag,invited_by from kg_app_user where user_id=:u for update",p("u",uid));
        if(account==null||!"0".equals(account.get("status"))||!"0".equals(account.get("delFlag")))return;
        if(db.count("select count(*) from kg_member_restriction where app_user_id=:u and frozen='1'",p("u",uid))>0)return;
        String from=starts();
        for(Map<String,Object> a:db.list("select a.app_id,a.store_id,a.volunteer_days,a.volunteer_done_by,j.job_type from kg_application a join kg_job j on j.job_id=a.job_id where a.app_user_id=:u and a.del_flag='0' and a.status in ('onboarded','completed') and a.volunteer_done_time>=:t and j.job_type in ('volunteer','parttime')",p("u",uid,"t",from))){
            long id=num(a.get("appId"));String type=str(a.get("jobType"));int days=(int)num(a.get("volunteerDays"));
            if(db.count("select count(*) from kg_point_entry where app_user_id=:u and biz_type='volunteer' and biz_id=:id",p("u",uid,"id",id))==0)ledger.award(uid,KgMembershipPolicy.servicePoints(type,days),"完成"+("volunteer".equals(type)?"换工":"兼职")+"服务（"+days+"天）","m_service",id);
            referral(uid,type,days,"m_ref_service",id,a.get("storeId"),str(a.get("volunteerDoneBy")));
        }
        for(Map<String,Object> a:db.list("select g.signup_id,a.store_id from kg_activity_signup g join kg_activity a on a.activity_id=g.activity_id where g.app_user_id=:u and g.del_flag='0' and g.status='confirmed' and g.checkin_time is not null and a.member_training='1' and a.status='ended' and coalesce(a.end_time,date_add(a.start_time,interval 4 hour))>=:t and coalesce(a.end_time,date_add(a.start_time,interval 4 hour))<=now()",p("u",uid,"t",from))){
            long id=num(a.get("signupId"));ledger.award(uid,15,"完成全职培训","m_training",id);referral(uid,"training",0,"m_ref_training",id,a.get("storeId"),"");
        }
        for(Map<String,Object> o:db.list("select order_id,status,nights,check_in,check_out,coalesce(verify_time,check_in) as actual_check_in,coalesce(checked_out_time,check_out) as actual_check_out from kg_stay_order where app_user_id=:u and del_flag='0' and status='completed' and coalesce(checked_out_time,check_out)>=:t",p("u",uid,"t",from))){
            long id=num(o.get("orderId"));if(db.count("select count(*) from kg_point_entry where app_user_id=:u and biz_type='stay_spend' and biz_id=:id",p("u",uid,"id",id))>0)continue;
            int nights=actualNights(o);ledger.award(uid,nights/7*5,"完成住宿每满7晚奖励5分","m_stay",id);
        }
        for(Map<String,Object> o:db.list("select o.order_id,p.delta from kg_stay_order o join kg_point_entry p on p.biz_id=o.order_id and p.biz_type='m_stay' and p.app_user_id=o.app_user_id where o.app_user_id=:u and (o.status in ('cancelled','refunded') or exists(select 1 from kg_pay_order pay where pay.pay_no=o.pay_no and pay.status='refunded'))",p("u",uid)))ledger.clawBack(uid,(int)num(o.get("delta")),"住宿已取消或全额退款，扣回奖励","m_stay_back",num(o.get("orderId")));
        for(Map<String,Object> r:db.list("select * from kg_review where app_user_id=:u and create_time>=:t order by review_id",p("u",uid,"t",from)))reviewReward(uid,r);
        for(Map<String,Object> post:db.list("select post_id from kg_post p where app_user_id=:u and status='online' and del_flag='0' and official_repost_time>=:t and not exists(select 1 from kg_badge_violation v where v.app_user_id=:u and v.violation_type='false_content' and v.target_type='post' and v.target_id=p.post_id)",p("u",uid,"t",from))){
            if(db.one("select violation_id from kg_badge_violation where app_user_id=:u and violation_type='false_content' and target_type='post' and target_id=:id for update",p("u",uid,"id",post.get("postId")))==null)
                ledger.award(uid,5,"社区内容被官方转发","m_repost",num(post.get("postId")));
        }
    }
    static int actualNights(Map<String,Object> o){int booked=(int)num(o.get("nights"));String actual=str(o.get("actualCheckOut"));if(actual.length()<10)return booked;try{return (int)Math.max(0,Math.min(booked,ChronoUnit.DAYS.between(LocalDate.parse(str(o.get("actualCheckIn")==null?o.get("checkIn"):o.get("actualCheckIn")).substring(0,10)),LocalDate.parse(actual.substring(0,10)))));}catch(Exception e){return 0;}}
    private void referral(Long uid,String type,int days,String event,long id,Object store,String confirmedBy){
        int reward=KgMembershipPolicy.referralPoints(type,days);if(reward==0)return;
        Map<String,Object> u=db.one("select invited_by from kg_app_user where user_id=:u",p("u",uid));Long inviter=u==null?null:KgJdbc.asLong(u.get("invitedBy"));
        if(inviter==null||uid.equals(inviter)||("staff:"+inviter).equals(confirmedBy))return;
        if(db.count("select count(*) from kg_member_restriction where app_user_id=:u and frozen='1'",p("u",inviter))>0)return;
        if(db.one("select user_id from kg_app_user where user_id=:u and status='0' and del_flag='0' for update",p("u",inviter))==null)return;
        if(db.one("select violation_id from kg_badge_violation where app_user_id=:u and violation_type='referral_fraud' and target_type='invited' and target_id=:i for update",p("u",inviter,"i",uid))!=null)return;
        ledger.award(inviter,reward,"推荐的朋友完成"+("training".equals(type)?"全职培训":"volunteer".equals(type)?"换工":"兼职"),event,id);
    }
    private void reviewReward(Long uid,Map<String,Object> r){
        Long id=KgJdbc.asLong(r.get("reviewId"));Map<String,Object> check=db.one("select * from kg_member_review_check where review_id=:r for update",p("r",id));
        if(check!=null&&"1".equals(check.get("revoked"))){backReview(uid,id,"1".equals(check.get("revokeBase")));return;}
        if(check!=null&&"1".equals(check.get("archived")))return;
        if(!"0".equals(r.get("status"))||!"0".equals(r.get("delFlag"))||!reviewable(uid,str(r.get("bizType")),KgJdbc.asLong(r.get("bizId"))))return;
        if(db.count("select count(*) from kg_member_restriction where app_user_id=:u and (points_blocked_until>now() or reviews_blocked_until>now() or frozen='1')",p("u",uid))>0)return;
        ledger.award(uid,2,"真实评价审核通过","m_review",id);
        String reason=qualityReason(r,check);boolean quality=reason.isEmpty();
        if(reviewQueue!=null)reviewQueue.detect(r,reason);
        db.update("insert into kg_member_review_check(review_id,quality,reason,checked_time) values(:r,:q,:reason,now()) on duplicate key update quality=:q,reason=:reason,checked_time=now()",p("r",id,"q",quality?"1":"0","reason",quality?"字数、细节、实拍图、原创性及内容审核通过":reason));
        db.update("update kg_review set featured=:q where review_id=:r",p("r",id,"q",quality?"1":"0"));
        if(quality){
            ledger.award(uid,5,"评价被评为精华，额外奖励","m_quality",id);
            reconcileQualityPoints(uid,id,5);
        }
    }
    private String qualityReason(Map<String,Object> r,Map<String,Object> check){
        String text=str(r.get("content")).replaceAll("【[^】]{0,12}】","").trim();
        if(text.replaceAll("\\s","").length()<50)return "文字不足50字";
        if(!text.matches("(?s).*(\\d|天|晚|早上|晚上|房间|厨房|前台|老板|店长|交通|公交|地铁|早餐|打扫|工作|到达).*"))return "缺少具体细节";
        if(str(r.get("images")).trim().isEmpty()||"[]".equals(str(r.get("images"))))return "需要实拍照片";
        if(check==null||!"1".equals(check.get("imageVerified")))return "等待实拍照片核验";
        try{if(!"pass".equals(guard.check(text)))return "内容待审核";}catch(ServiceException e){return "内容不符合社区规范";}
        long daily=db.count("select count(*) from kg_review where app_user_id=:u and date(create_time)=date(:t)",p("u",r.get("appUserId"),"t",r.get("createTime")));if(daily>=5)return "同日评价过多，待人工核验";
        for(Map<String,Object> other:db.list("select content from kg_review where review_id<>:r and del_flag='0' and create_time<=:t order by review_id desc limit 3000",p("r",r.get("reviewId"),"t",r.get("createTime"))))if(similarity(text,str(other.get("content")))>=.7)return "与已有评价重复率达到70%";
        return "";
    }
    static double similarity(String a,String b){Set<String> x=grams(a),y=grams(b);if(x.isEmpty()||y.isEmpty())return 0;Set<String> both=new HashSet<>(x);both.retainAll(y);return both.size()/(double)Math.min(x.size(),y.size());}
    private static Set<String> grams(String text){String s=text.replaceAll("[\\s\\p{Punct}，。！？、【】]","");Set<String> r=new HashSet<>();for(int i=0;i<s.length()-2;i++)r.add(s.substring(i,i+3));return r;}
    public void backReview(Long uid,Long id,boolean base){if(base)reverse(uid,"m_review","m_review_back",id);reverse(uid,"m_quality","m_quality_back",id);reconcileQualityPoints(uid,id,0);db.update("update kg_review set featured='0' where review_id=:r",p("r",id));}
    private void reverse(Long uid,String type,String back,Long id){Map<String,Object> r=db.one("select delta from kg_point_entry where app_user_id=:u and biz_type=:t and biz_id=:id",p("u",uid,"t",type,"id",id));if(r!=null)ledger.clawBack(uid,(int)num(r.get("delta")),"评价核验撤销奖励",back,id);}

    /** The caller holds the account lock. Keep every correction and its audit record. */
    private void reconcileQualityPoints(Long uid,Long reviewId,int expected){
        List<Map<String,Object>> entries=db.list("select e.delta from kg_point_entry e "
                +"left join kg_member_audit a on e.biz_type='m_quality_adjust' and a.audit_id=e.biz_id "
                +"where e.app_user_id=:u and ((e.biz_id=:r and e.biz_type in ('m_quality','m_quality_back')) "
                +"or (e.biz_type='m_quality_adjust' and a.action='review_quality_adjust' and a.target_id=:r)) for update",
                p("u",uid,"r",reviewId));
        long current=0;
        for(Map<String,Object> entry:entries)current+=num(entry.get("delta"));
        int difference=Math.toIntExact(expected-current);
        if(difference==0)return;
        String reason="精华评价奖励校正，由"+current+"分调整为"+expected+"分";
        long auditId=db.insert("kg_member_audit",p("action","review_quality_adjust","operator","system:member_quality",
                "target_id",reviewId,"note",reason));
        if(difference>0)ledger.award(uid,difference,reason,"m_quality_adjust",auditId);
        else ledger.spend(uid,-difference,reason,"m_quality_adjust",auditId);
    }
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public void verifyImages(Long reviewId,boolean real,String note,String operator){
        if(note==null||note.trim().length()<2||note.length()>500)throw new ServiceException("请填写照片核验依据",422);
        Map<String,Object> r=db.must("select * from kg_review where review_id=:r",p("r",reviewId),"评价不存在");
        db.one("select user_id from kg_app_user where user_id=:u for update",p("u",r.get("appUserId")));
        r=db.must("select * from kg_review where review_id=:r for update",p("r",reviewId),"评价不存在");
        db.update("insert into kg_member_review_check(review_id,reason,image_verified,image_note,checked_by,checked_time) values(:r,'等待自动评选',:v,:n,:by,now()) on duplicate key update image_verified=:v,image_note=:n,checked_by=:by,checked_time=now()",p("r",reviewId,"v",real?"1":"0","n",note,"by",operator));
        if(!real)backReview(KgJdbc.asLong(r.get("appUserId")),reviewId,false);
        sync(KgJdbc.asLong(r.get("appUserId")));
    }

    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public boolean applyAutomaticPhotoResult(Long reviewId, String fingerprint, boolean accepted) {
        if(!enabled())return false;
        Map<String,Object> owner=db.one("select app_user_id from kg_review where review_id=:r",p("r",reviewId));
        if(owner==null)return false;
        Long uid=KgJdbc.asLong(owner.get("appUserId"));
        Map<String,Object> account=db.one("select status,del_flag from kg_app_user where user_id=:u for update",p("u",uid));
        if(account==null||!"0".equals(account.get("status"))||!"0".equals(account.get("delFlag")))return false;
        Map<String,Object> review=db.one("select * from kg_review where review_id=:r for update",p("r",reviewId));
        if(review==null||!"0".equals(review.get("status"))||!"0".equals(review.get("delFlag"))
                ||!uid.equals(KgJdbc.asLong(review.get("appUserId")))
                ||!Objects.equals(fingerprint,com.ruoyi.kangaroo.ext.stay.KgReviewPhotoService.fingerprint(review))
                ||str(review.get("createTime")).compareTo(starts())<0
                ||!reviewable(uid,str(review.get("bizType")),KgJdbc.asLong(review.get("bizId"))))return false;
        Map<String,Object> previous=db.one("select * from kg_member_review_check where review_id=:r for update",p("r",reviewId));
        if(previous!=null&&(!str(previous.get("checkedBy")).isEmpty()||"1".equals(previous.get("revoked"))
                ||"1".equals(previous.get("archived"))))return false;
        String note=accepted?"自动图像初审通过，继续核对文字、订单与原创性":"自动图像初审存疑，待人工核验";
        db.update("insert into kg_member_review_check(review_id,reason,image_verified,image_note,checked_by,checked_time) "
                +"values(:r,:note,:v,:note,'auto:photo',now()) on duplicate key update image_verified=:v,image_note=:note,checked_by='auto:photo',checked_time=now()",
                p("r",reviewId,"note",note,"v",accepted?"1":"0"));
        sync(uid);
        return true;
    }
}
