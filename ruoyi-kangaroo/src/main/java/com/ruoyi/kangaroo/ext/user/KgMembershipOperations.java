package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.user.KgMemberWallet.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Explicitly audited operations, as opposed to editing a member's displayed points. */
@Service
public class KgMembershipOperations {
    @Autowired private KgJdbc db;
    @Autowired private com.ruoyi.kangaroo.service.IKgMessageService messages;
    @Autowired private KgMemberWallet wallet;
    @Autowired private KgMembershipRewards rewards;
    @Autowired private KgPointLedger ledger;
    @Autowired private KgMemberReviewQueue reviewQueue;
    @Autowired(required=false) private com.ruoyi.kangaroo.ext.stay.KgReviewPhotoService photoReview;
    private static LocalDate week(){return KgMembershipPolicy.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));}
    private static String note(Map<String,Object> b){String n=str(b.get("note")).trim();if(n.length()<2||n.length()>500)throw new ServiceException("请填写2至500字处理依据",422);return n;}
    private void audit(String action,String operator,Long id,String note){db.insert("kg_member_audit",p("action",action,"operator",operator,"target_id",id,"note",note));}

    public Map<String,Object> overview(String tab,int page){
        switch(tab){
            case "requests":return db.page("select r.*,w.benefit_code,w.amount_fen from kg_member_request r left join kg_member_wallet w on w.wallet_id=r.wallet_id order by (r.status='pending') desc,r.request_id desc",p(),page,20);
            case "reviews":return reviews(page);
            case "rooms":return rooms(page);
            case "activities":return db.page("select activity_id,title,status,member_training,member_free from kg_activity where del_flag='0' order by activity_id desc",p(),page,20);
            case "posts":return db.page("select post_id,app_user_id,title,content,status,official_repost_time from kg_post where del_flag='0' order by post_id desc",p(),page,20);
            case "cases":return db.page("select * from kg_member_case order by case_id desc",p(),page,20);
            case "audit":return db.page("select * from kg_member_audit order by audit_id desc",p(),page,30);
            case "calendar":return p("rows",db.list("select * from kg_member_holiday order by holiday_date desc",p()),"years",db.list("select * from kg_member_calendar_year order by calendar_year desc",p()),"workdayCalendars",reviewQueue==null?Collections.emptyList():reviewQueue.calendars());
            default:throw new ServiceException("未知会员管理栏目",422);
        }
    }
    private Map<String,Object> reviews(int page){
        Map<String,Object> result=db.page("select r.review_id,r.app_user_id,r.content,r.images,r.status,c.quality,c.reason,c.image_verified,c.image_note,c.checked_by,c.revoked,c.archived from kg_review r left join kg_member_review_check c on c.review_id=r.review_id where r.del_flag='0' order by r.review_id desc",p(),page,20);
        result.put("photoReview",photoReview==null?p("ready",false,"message","照片自动初审暂未配置，可继续人工核验"):photoReview.readiness());
        return result;
    }
    private Map<String,Object> rooms(int page){
        Map<String,Object> result=db.page("select r.room_type_id,r.type_name,s.stay_name,r.member_room_code,r.member_price_fen,r.room_kind,r.unit_label,r.capacity from kg_stay_room_type r join kg_stay s on s.stay_id=r.stay_id where r.del_flag='0' order by r.room_type_id desc",p(),page,20);
        for(Object value:(List<?>)result.get("rows")){
            @SuppressWarnings("unchecked") Map<String,Object> room=(Map<String,Object>)value;
            room.put("effectiveRoomCode",KgMembershipPolicy.roomCode(room));
        }
        return result;
    }
    @Transactional(rollbackFor=Exception.class)
    public void configure(String type,Long id,Map<String,Object>b,String operator){
        String n=note(b);
        if("rooms".equals(type)){
            String code=str(b.get("roomCode"));if(!Arrays.asList("","auto","disabled","night4","night2","night1").contains(code))throw new ServiceException("房型对应关系无效",422);
            long price;
            try{price=new java.math.BigDecimal(String.valueOf(b.get("priceFen"))).longValueExact();}catch(RuntimeException e){throw new ServiceException("会员底价请填写精确到分的非负金额",422);}
            if(price<0||price>100000000)throw new ServiceException("会员底价无效",422);
            if(db.update("update kg_stay_room_type set member_room_code=:c,member_price_fen=:p where room_type_id=:id and del_flag='0'",p("c",code.isEmpty()?"auto":code,"p",price==0?null:price,"id",id))!=1)throw new ServiceException("房型不存在",404);
        }else if("activities".equals(type)){
            if(db.update("update kg_activity set member_training=:t,member_free=:f where activity_id=:id and del_flag='0'",p("t",Boolean.TRUE.equals(b.get("training"))?"1":"0","f",Boolean.TRUE.equals(b.get("free"))?"1":"0","id",id))!=1)throw new ServiceException("活动不存在",404);
        }else throw new ServiceException("配置类型无效",422);
        audit("configure_"+type,operator,id,n);
    }
    @Transactional(rollbackFor=Exception.class)
    public void calendar(Map<String,Object>b,String operator){
        int y=(int)num(b.get("year"));if(y<KgMembershipPolicy.year()||y>KgMembershipPolicy.year()+1)throw new ServiceException("仅可维护今年和明年的节假日",422);
        if(!Boolean.TRUE.equals(b.get("confirmed")))throw new ServiceException("请核对完整法定节假日后确认",422);
        String text=str(b.get("dates")).trim();String n=note(b);Set<LocalDate> dates=new TreeSet<>();
        if(text.isEmpty())throw new ServiceException("请填写全年法定节假日日期，每行一个",422);
        try{for(String line:text.split("[\\s,，]+")){LocalDate d=LocalDate.parse(line);if(d.getYear()!=y)throw new IllegalArgumentException();dates.add(d);}}catch(Exception e){throw new ServiceException("日期应为所选年份的 YYYY-MM-DD",422);}
        db.update("insert ignore into kg_member_calendar_year(calendar_year,confirmed) values(:y,'0')",p("y",y));
        db.one("select calendar_year from kg_member_calendar_year where calendar_year=:y for update",p("y",y));
        db.update("delete from kg_member_holiday where year(holiday_date)=:y",p("y",y));
        for(LocalDate d:dates)db.insert("kg_member_holiday",p("holiday_date",d,"title","法定节假日","create_by",operator));
        if(reviewQueue!=null)reviewQueue.saveWorkdays(y,b,operator,dates);
        db.update("update kg_member_calendar_year set confirmed='1',update_by=:by,update_time=now() where calendar_year=:y",p("by",operator,"y",y));audit("calendar",operator,(long)y,n);
    }
    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void review(Long id,Map<String,Object>b,String operator){
        String action=str(b.get("action")),n=note(b);
        Map<String,Object> r=db.must("select app_user_id from kg_review where review_id=:r",p("r",id),"评价不存在");
        db.one("select user_id from kg_app_user where user_id=:u for update",p("u",r.get("appUserId")));
        if("verify".equals(action))rewards.verifyImages(id,Boolean.TRUE.equals(b.get("real")),n,operator);
        else if("archive".equals(action))db.update("insert into kg_member_review_check(review_id,reason,archived) values(:r,:n,'1') on duplicate key update archived='1',reason=:n",p("r",id,"n",n));
        else if("revoke".equals(action)){
            // Fraud penalties have an appeal period; immediate removal does not silently deduct points.
            throw new ServiceException("涉及虚假评价请新建违规通知，申诉期后再执行扣回",422);
        }else throw new ServiceException("操作无效",422);
        audit("review_"+action,operator,id,n);
    }
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public void repost(Long id,Map<String,Object>b,String operator){
        String n=note(b);
        Map<String,Object> owner=db.must("select app_user_id from kg_post where post_id=:id and del_flag='0'",p("id",id),"帖子不存在");
        db.must("select user_id from kg_app_user where user_id=:u for update",p("u",owner.get("appUserId")),"会员不存在");
        Map<String,Object> r=db.must("select app_user_id,status from kg_post where post_id=:id and del_flag='0' for update",p("id",id),"帖子不存在");
        if(!"online".equals(r.get("status")))throw new ServiceException("仅可登记已发布帖子的真实官方转发",422);
        if(db.one("select violation_id from kg_badge_violation where app_user_id=:u and violation_type='false_content' and target_type='post' and target_id=:id for update",p("u",r.get("appUserId"),"id",id))!=null)throw new ServiceException("已核实为虚假内容，不能登记官方转发",409);
        db.update("update kg_post set official_repost_time=coalesce(official_repost_time,now()),official_repost_by=:by where post_id=:id",p("by",operator,"id",id));
        rewards.sync(KgJdbc.asLong(r.get("appUserId")));audit("official_repost",operator,id,n);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> pins(Long uid){wallet.active(uid);return p("quota",KgMembershipPolicy.pinQuota(wallet.level(uid)),"used",db.count("select count(*) from kg_member_pin where app_user_id=:u and week_start=:w",p("u",uid,"w",week())),"posts",db.list("select post_id,title,content from kg_post where app_user_id=:u and status='online' and del_flag='0' order by post_id desc limit 100",p("u",uid)),"active",db.list("select post_id,ends_at from kg_member_pin where app_user_id=:u and ends_at>now()",p("u",uid)));}
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> pin(Long uid,Map<String,Object>b){
        wallet.active(uid);String k=key(b.get("requestId"));Long post=KgJdbc.asLong(b.get("postId"));
        if(db.count("select count(*) from kg_member_pin where app_user_id=:u and request_key=:k",p("u",uid,"k",k))>0)return pins(uid);
        if(db.count("select count(*) from kg_post where post_id=:p and app_user_id=:u and status='online' and del_flag='0'",p("p",post,"u",uid))==0)throw new ServiceException("请选择自己的已发布帖子",422);
        if(db.count("select count(*) from kg_member_pin where app_user_id=:u and ends_at>now()",p("u",uid))>0)throw new ServiceException("已有帖子置顶中，到期后可再次使用",409);
        if(db.count("select count(*) from kg_member_pin where app_user_id=:u and week_start=:w",p("u",uid,"w",week()))>=KgMembershipPolicy.pinQuota(wallet.level(uid)))throw new ServiceException("本周置顶次数已用完或等级尚未达到",409);
        db.update("insert into kg_member_pin(app_user_id,post_id,week_start,starts_at,ends_at,request_key) values(:u,:p,:w,now(),date_add(now(),interval 2 hour),:k)",p("u",uid,"p",post,"w",week(),"k",k));return pins(uid);
    }
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public void weeklyHot(){
        LocalDate start=week().minusWeeks(1),end=start.plusWeeks(1);
        if(db.count("select count(*) from kg_member_execution where id=1 and starts_at<:e",p("e",end))==0)return;
        // Snapshot once after the week closes; new likes cannot move the already rewarded ranking.
        db.one("select id from kg_member_execution where id=1 for update",p());
        if(db.count("select count(*) from kg_member_hot where week_start=:w",p("w",start))>0)return;
        List<Map<String,Object>> rows=db.list("select post_id,app_user_id,likes,comments,favorites,timestampdiff(second,:a,create_time) age from kg_post p where status='online' and del_flag='0' and create_time>=:a and create_time<:b and create_time>=(select starts_at from kg_member_execution where id=1) and app_user_id not in(select app_user_id from kg_member_restriction where frozen='1') and not exists(select 1 from kg_badge_violation v where v.app_user_id=p.app_user_id and v.violation_type='false_content' and v.target_type='post' and v.target_id=p.post_id)",p("a",start,"b",end));
        double ml=1,mc=1,mf=1;for(Map<String,Object>r:rows){ml=Math.max(ml,num(r.get("likes")));mc=Math.max(mc,num(r.get("comments")));mf=Math.max(mf,num(r.get("favorites")));}
        for(Map<String,Object>r:rows)r.put("score",.4*num(r.get("likes"))/ml+.3*num(r.get("comments"))/mc+.2*num(r.get("favorites"))/mf+.1*Math.max(0,num(r.get("age")))/604800.0);
        rows.sort((a,b)->{int c=Double.compare((Double)b.get("score"),(Double)a.get("score"));return c==0?Long.compare(num(b.get("postId")),num(a.get("postId"))):c;});
        int awarded=0;
        for(Map<String,Object> r:rows){
            if(awarded==3)break;
            Long uid=KgJdbc.asLong(r.get("appUserId"));
            db.must("select user_id from kg_app_user where user_id=:u for update",p("u",uid),"会员不存在");
            if(db.one("select violation_id from kg_badge_violation where app_user_id=:u and violation_type='false_content' and target_type='post' and target_id=:id for update",p("u",uid,"id",r.get("postId")))!=null)continue;
            if(db.count("select count(*) from kg_post where post_id=:id and status='online' and del_flag='0'",p("id",r.get("postId")))==0)continue;
            long id=db.insert("kg_member_hot",p("week_start",start,"post_id",r.get("postId"),"score",r.get("score")));
            ledger.award(uid,5,"本周热门帖子前三名","m_hot",id);
            awarded++;
        }
    }
    @Transactional(rollbackFor=Exception.class)
    public Long notifyCase(Map<String,Object>b,String operator){
        Long uid=KgJdbc.asLong(b.get("userId")),review=KgJdbc.asLong(b.get("reviewId"));int severity=(int)num(b.get("severity"));String facts=note(b);
        if(severity<1||severity>4||uid==null||(severity==1&&review==null))throw new ServiceException("请选择会员与1至4级处理",422);
        db.must("select user_id from kg_app_user where user_id=:u",p("u",uid),"会员不存在");
        if(review!=null&&db.count("select count(*) from kg_review where review_id=:r and app_user_id=:u",p("r",review,"u",uid))==0)throw new ServiceException("评价不属于该会员",422);
        long id=db.insert("kg_member_case",p("app_user_id",uid,"severity",severity,"review_id",review,"facts",facts,"created_by",operator,"appeal_deadline",LocalDateTime.now(KgMembershipPolicy.ZONE).plusDays(7)));
        messages.send(uid,"user","system","会员规则复核通知",facts+"。你可在7天内提交申诉，申诉期结束后再执行复核结果。","member_case",id,"/pages/profile/member-wallet");
        audit("case_notice",operator,id,facts);return id;
    }
    public List<Map<String,Object>> cases(Long uid){return db.list("select case_id,severity,facts,status,appeal,response,appeal_deadline,executed_at from kg_member_case where app_user_id=:u order by case_id desc limit 50",p("u",uid));}
    // 查不到（不是本人的通知、通知不存在）按 404 报：db.must 不带码，接口会是 500
    @Transactional(rollbackFor=Exception.class)
    public void appeal(Long uid,Long id,Map<String,Object>b){String n=note(b);Map<String,Object> c=db.one("select * from kg_member_case where case_id=:id and app_user_id=:u for update",p("id",id,"u",uid));if(c==null)throw new ServiceException("通知不存在",404);if(!"notified".equals(c.get("status"))||str(c.get("appealDeadline")).compareTo(LocalDateTime.now(KgMembershipPolicy.ZONE).toString().replace('T',' '))<0)throw new ServiceException("当前不可提交申诉，请联系平台",409);db.update("update kg_member_case set appeal=:a,status='appealed' where case_id=:id",p("a",n,"id",id));}
    @Transactional(rollbackFor=Exception.class)
    public void decideCase(Long id,Map<String,Object>b,String operator){
        String n=note(b);Map<String,Object> c=db.one("select * from kg_member_case where case_id=:id for update",p("id",id));if(c==null)throw new ServiceException("通知不存在",404);
        if(Arrays.asList("cancelled","executed").contains(c.get("status")))throw new ServiceException("已处理，不能重复执行",409);
        boolean execute=Boolean.TRUE.equals(b.get("execute"));
        if(execute&&str(c.get("appealDeadline")).compareTo(LocalDateTime.now(KgMembershipPolicy.ZONE).toString().replace('T',' '))>0)throw new ServiceException("七天申诉期未结束，不能执行",409);
        Long uid=KgJdbc.asLong(c.get("appUserId"));int s=(int)num(c.get("severity"));
        if(execute){
            db.one("select user_id from kg_app_user where user_id=:u for update",p("u",uid));
            List<Map<String,Object>> rs=s==1?db.list("select review_id from kg_review where review_id=:r and app_user_id=:u",p("r",c.get("reviewId"),"u",uid)):db.list("select review_id from kg_review where app_user_id=:u",p("u",uid));
            for(Map<String,Object> r:rs){Long rid=KgJdbc.asLong(r.get("reviewId"));db.update("insert into kg_member_review_check(review_id,reason,revoked,revoke_base) values(:r,:n,'1',:base) on duplicate key update revoked='1',revoke_base=greatest(revoke_base,:base),quality='0',reason=:n",p("r",rid,"n",n,"base",s==1||s>=3?"1":"0"));rewards.backReview(uid,rid,s==1||s>=3);}
            db.update("insert ignore into kg_member_restriction(app_user_id) values(:u)",p("u",uid));
            if(s>=2)db.update("update kg_member_restriction set points_blocked_until=date_add(now(),interval 30 day) where app_user_id=:u",p("u",uid));
            if(s>=3)db.update("update kg_member_restriction set reviews_blocked_until=date_add(now(),interval 90 day),level_penalty=level_penalty+1 where app_user_id=:u",p("u",uid));
            if(s>=3){
                // Clear spendable points, retaining historical growth so the explicit one-level penalty is not a reset to level one.
                long balance=db.count("select coalesce(sum(delta),0) from kg_point_entry where app_user_id=:u",p("u",uid));
                if(balance>0)db.insert("kg_point_entry",p("app_user_id",uid,"delta",-balance,"reason","会员复核：清空可用积分","biz_type","m_balance_clear","biz_id",id));
            }
            if(s>=4){
                db.update("update kg_member_restriction set frozen='1',reviews_blocked_until='9999-12-31 00:00:00' where app_user_id=:u",p("u",uid));
                db.update("update kg_app_user set status='1' where user_id=:u",p("u",uid));
            }
        }
        db.update("update kg_member_case set status=:s,response=:n,reviewed_by=:by,reviewed_time=now(),executed_at=:t where case_id=:id",p("s",execute?"executed":"cancelled","n",n,"by",operator,"t",execute?LocalDateTime.now(KgMembershipPolicy.ZONE):null,"id",id));audit("case_decision",operator,id,n);messages.send(uid,"user","system","会员复核结果",n,"member_case_result",id,"/pages/profile/member-wallet");
    }
    @Transactional(rollbackFor=Exception.class)
    public void fulfill(Long id,Map<String,Object>b,String operator){wallet.fulfill(id,Boolean.TRUE.equals(b.get("approve")),note(b),str(b.get("proof")),operator);audit("fulfill",operator,id,note(b));}
}
