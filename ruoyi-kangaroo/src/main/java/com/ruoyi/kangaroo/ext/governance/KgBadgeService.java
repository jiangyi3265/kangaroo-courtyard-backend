package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;
import java.util.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.*;
import com.ruoyi.common.exception.ServiceException;

@Service public class KgBadgeService {
    @Autowired private KgJdbc db;
    @Autowired private KgMembershipRewards rewards;
    @Autowired private KgMemberService members;
    @Autowired private KgCityService cities;
    @Autowired private KgBadgeMetrics metrics;

    public List<Map<String,Object>> definitions() {
        List<Map<String,Object>> definitions = db.list("select * from kg_badge_def order by sort_order,code", p());
        for (Map<String,Object> definition : definitions) {
            String code = str(definition.get("code"));
            boolean fixed = KgBadgeRules.FIXED_CODES.contains(code);
            definition.put("fixedRule", fixed);
            if (fixed) {
                definition.put("ruleText", KgBadgeRules.ruleText(code));
                definition.put("thresholdValue", null);
            }
        }
        return definitions;
    }
    private long metric(Long uid,String metric){
        Map<String,Object> q=p("u",uid);
        if("quality".equals(metric))return db.count("select count(*) from kg_member_review_check c join kg_review r on r.review_id=c.review_id where r.app_user_id=:u and r.status='0' and r.del_flag='0' and c.quality='1' and c.revoked='0'",q);
        if("jury".equals(metric)||"jury_quality".equals(metric))return db.count("select count(*) from kg_mediation_vote where app_user_id=:u and valid='1' and not exists(select 1 from kg_mediation_juror j where j.app_user_id=:u and j.disqualified='1')"+("jury_quality".equals(metric)?" and quality='1'":""),q);
        if("repost".equals(metric))return db.count("select count(*) from kg_post where app_user_id=:u and official_repost_time is not null and status='online' and del_flag='0'",q);
        if("hot".equals(metric))return db.count("select count(*) from kg_point_entry where app_user_id=:u and biz_type='m_hot' and create_time>=date_sub(now(),interval 7 day)",q);
        if("recorder".equals(metric)){
            List<Map<String,Object>> rows=db.list("select date_format(at_time,'%Y-%m') month_key,count(*) n from (select r.create_time at_time from kg_review r join kg_point_entry e on e.app_user_id=r.app_user_id and e.biz_type='m_review' and e.biz_id=r.review_id left join kg_member_review_check c on c.review_id=r.review_id where r.app_user_id=:u and r.status='0' and r.del_flag='0' and coalesce(c.revoked,'0')='0' union all select create_time from kg_post where app_user_id=:u and status='online' and del_flag='0' union all select create_time from kg_badge_feedback where app_user_id=:u and status='valid') x where at_time is not null group by date_format(at_time,'%Y-%m') order by month_key",q);
            int streak=0;YearMonth prev=null;for(Map<String,Object> r:rows){YearMonth month=YearMonth.parse(str(r.get("monthKey")));streak=num(r.get("n"))>=5?(prev!=null&&prev.plusMonths(1).equals(month)?streak+1:1):0;if(streak>=3)return 3;prev=month;}return 0;
        }
        return 0;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void sync(Long uid) {
        syncAndProgress(uid);
    }

    private Map<String,Object> syncAndProgress(Long uid) {
        rewards.sync(uid);
        must(db, "select user_id from kg_app_user where user_id=:u for update", p("u",uid), "账号不存在", 404);
        LocalDateTime evaluatedAt = now();
        LocalDate quarter = KgBadgeRules.quarterStart(evaluatedAt.toLocalDate());
        Map<String,Object> referral = metrics.referral(uid, quarter, evaluatedAt);
        boolean accountAvailable = KgBadgeRules.flag(referral,"accountAvailable");
        Map<String,Object> jury = metrics.jury(uid);
        Map<String,Object> travel = metrics.travel(uid);
        Map<String,Object> progress = new LinkedHashMap<>();
        List<Map<String,Object>> definitions = definitions();
        for (Map<String,Object> definition : definitions) {
            String code = str(definition.get("code"));
            String metric = str(definition.get("metric"));
            if (KgBadgeRules.FIXED_CODES.contains(code)) {
                Map<String,Object> evidence = "juror_gold".equals(code) ? jury : "traveler".equals(code) ? travel : referral;
                boolean eligible = "1".equals(definition.get("enabled")) && KgBadgeRules.eligible(code, evidence);
                List<String> missing = new ArrayList<>();
                if (evidence.get("creditScore") == null && !"juror_gold".equals(code)) missing.add("信用记录待核验");
                if (num(evidence.get("undatedExperiences")) > 0) missing.add("部分历史体验缺少实际完成日期，仅计入累计人数");
                if (num(evidence.get("experiencesWithoutVerifiedDays")) > 0) missing.add("部分历史体验缺少已核验天数，仅计入体验次数");
                Map<String,Object> status = p("eligible", eligible, "criteria", evidence, "missingData", missing);
                if ("referrer_gold".equals(code)) reconcileGold(uid, definition, referral, evaluatedAt, status);
                else automatic(uid, definition, eligible, "对应有效业务记录或资格已不符合规则");
                progress.put(code, status);
                continue;
            }
            if ("manual".equals(metric) || definition.get("thresholdValue") == null) continue;
            boolean earned = accountAvailable && "1".equals(definition.get("enabled")) && metric(uid,metric) >= num(definition.get("thresholdValue"));
            if (earned || !"recorder".equals(metric) || !accountAvailable) automatic(uid,definition,earned,"对应有效业务记录或账号资格不再符合规则");
            if ("hot".equals(metric) && earned) db.update("update kg_badge_award set expires_at=(select date_add(max(create_time),interval 7 day) from kg_point_entry where app_user_id=:u and biz_type='m_hot') where app_user_id=:u and badge_code=:c",p("u",uid,"c",code));
        }
        // A gold downgrade during this pass can establish the base badge's retention basis.
        for (Map<String,Object> definition : definitions) {
            if ("referrer".equals(definition.get("code")) && progress.get("referrer") instanceof Map) {
                @SuppressWarnings("unchecked") Map<String,Object> basic = (Map<String,Object>) progress.get("referrer");
                basic.put("eligible","1".equals(definition.get("enabled")) && KgBadgeRules.referrer(referral));
            }
        }
        return progress;
    }

    private void automatic(Long uid, Map<String,Object> definition, boolean earned, String reason) {
        String code = str(definition.get("code"));
        Map<String,Object> params = p("u",uid,"c",code,"n",definition.get("ruleText"),
                "v",KgBadgeRules.FIXED_CODES.contains(code)?KgBadgeRules.VERSION:0);
        Map<String,Object> old = db.one("select award_id,status from kg_badge_award where app_user_id=:u and badge_code=:c and source_key='automatic'",params);
        boolean active = old != null && "active".equals(old.get("status"));
        if (earned) {
            db.update("insert into kg_badge_award(app_user_id,badge_code,source_key,source_note,awarded_by,rule_version) "
                    + "values(:u,:c,'automatic',:n,'system',:v) on duplicate key update "
                    + "awarded_at=if(status='active',awarded_at,now()),source_note=values(source_note),"
                    + "rule_version=if(:v>0,:v,rule_version),status='active'",params);
            if (!active) log(uid,null,"system","automatic_grant",code+"："+definition.get("ruleText"));
        } else if (active) {
            db.update("update kg_badge_award set status='inactive',worn='0' where award_id=:a",p("a",old.get("awardId")));
            log(uid,id(old.get("awardId")),"system","automatic_revoke",code+"："+reason);
        }
    }

    private void reconcileGold(Long uid, Map<String,Object> definition, Map<String,Object> current,
            LocalDateTime evaluatedAt, Map<String,Object> status) {
        boolean enabled = "1".equals(definition.get("enabled"));
        boolean eligible = enabled && KgBadgeRules.goldReferrer(current);
        Map<String,Object> old = db.one("select award_id,status,awarded_at,rule_version from kg_badge_award "
                + "where app_user_id=:u and badge_code='referrer_gold' and source_key='automatic'",p("u",uid));
        boolean active = old != null && "active".equals(old.get("status"));
        int failedQuarters = 0;
        if (active && num(old.get("ruleVersion")) < KgBadgeRules.VERSION) {
            if (eligible) {
                db.update("update kg_badge_award set awarded_at=:t,rule_version=:v where award_id=:a",
                        p("t",evaluatedAt,"v",KgBadgeRules.VERSION,"a",old.get("awardId")));
                old.put("awardedAt",evaluatedAt.toString());
                log(uid,id(old.get("awardId")),"system","rule_upgrade","金牌推荐官已按完整新规则复核，从本季度起评估");
            } else {
                automatic(uid,definition,false,"旧门槛授予记录未达到完整新规则");
                active = false;
            }
        }
        if (active && enabled && KgBadgeRules.referralIntegrity(current,true)) {
            LocalDate quarter = KgBadgeRules.quarterStart(time(old.get("awardedAt")).toLocalDate()).plusMonths(3);
            LocalDate currentQuarter = KgBadgeRules.quarterStart(evaluatedAt.toLocalDate());
            while (quarter.isBefore(currentQuarter)) {
                Map<String,Object> params = p("u",uid,"q",quarter);
                Map<String,Object> review = db.one("select qualified from kg_badge_quarter_review "
                        + "where app_user_id=:u and badge_code='referrer_gold' and quarter_start=:q",params);
                if (review == null) {
                    Map<String,Object> evidence = metrics.referral(uid,quarter,quarter.plusMonths(3).atStartOfDay());
                    boolean qualified = KgBadgeRules.goldReferrer(evidence);
                    db.insert("kg_badge_quarter_review",p("app_user_id",uid,"badge_code","referrer_gold", "quarter_start",quarter,
                            "qualified",qualified?"1":"0","metrics_json",com.alibaba.fastjson2.JSON.toJSONString(evidence)));
                    review = p("qualified",qualified?"1":"0");
                }
                failedQuarters = "1".equals(review.get("qualified")) ? 0 : failedQuarters + 1;
                quarter = quarter.plusMonths(3);
            }
            if (failedQuarters >= 2) {
                automatic(uid,definition,false,"连续两个已结束自然季度未达标，降为推荐官");
                log(uid,id(old.get("awardId")),"system","gold_downgrade","连续两个已结束自然季度未达标，保留推荐官取得记录");
                current.put("downgradedFromGold",true);
                Map<String,Object> basic = db.must("select * from kg_badge_def where code='referrer'",p(),"推荐官定义不存在");
                basic.put("ruleText",KgBadgeRules.ruleText("referrer"));
                automatic(uid,basic,"1".equals(basic.get("enabled")) && KgBadgeRules.referrer(current),"信用或刷推荐记录不符合要求");
                active = false;
            }
        } else if (active) {
            automatic(uid,definition,false,"信用、违规记录或徽章启用状态已不符合规则");
            active = false;
        }
        if (eligible) {
            automatic(uid,definition,true,"");
            active = true;
        }
        status.put("awardActive",active);
        status.put("retained",active && !eligible);
        status.put("failedQuarters",failedQuarters);
    }

    /** The same formula controls the badge and complex-case core-juror priority. */
    public boolean goldJurorEligible(Long uid) {
        return KgBadgeRules.goldJuror(metrics.jury(uid));
    }

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> wall(Long uid) {
        Map<String,Object> progress = syncAndProgress(uid);
        return p("member",members.summaryFor(uid,db.count(KgPointLedger.EARNED_SQL,p("u",uid))),
                "definitions",definitions(),"progress",progress,"awards",db.list("select a.*,d.name,d.category,d.rule_text,d.tone "
                + "from kg_badge_award a join kg_badge_def d on d.code=a.badge_code where a.app_user_id=:u "
                + "order by a.awarded_at desc,a.award_id desc",p("u",uid)));
    }
    @Transactional(rollbackFor=Exception.class) public void display(Long uid,Long aid,Map<String,Object>b){
        must(db,"select user_id from kg_app_user where user_id=:u for update",p("u",uid),"账号不存在",404);
        Map<String,Object>a=must(db,"select * from kg_badge_award where award_id=:a and app_user_id=:u and status='active' and (expires_at is null or expires_at>now()) for update",p("a",aid,"u",uid),"徽章不可用",409);
        boolean worn=Boolean.TRUE.equals(b.get("worn")),visible=Boolean.TRUE.equals(b.get("visible"));if(worn)visible=true;
        if(worn)db.update("update kg_badge_award set worn='0' where app_user_id=:u",p("u",uid));
        db.update("update kg_badge_award set worn=:w,visible=:v where award_id=:a",p("a",aid,"w",worn?"1":"0","v",visible?"1":"0"));
        log(uid,aid,"user:"+uid,"display",visible?(worn?"佩戴":"展示"):"隐藏");
    }
    @Transactional(rollbackFor=Exception.class) public void configure(String code,Map<String,Object>b,String actor){
        must(db,"select code from kg_badge_def where code=:c",p("c",code),"徽章不存在",404);
        throw new ServiceException("此徽章遵循固定复合条件，不能修改为单一数量门槛",422);
    }
    @Transactional(rollbackFor=Exception.class) public Long grant(Map<String,Object>b,String actor){
        Long uid=id(b.get("userId"));String code=text(b,"code",1,40),note=text(b,"note",5,500),source=text(b,"sourceKey",8,100),city=str(b.get("city"));
        must(db,"select user_id from kg_app_user where user_id=:u and status='0' and del_flag='0' for update",p("u",uid),"会员不存在",404);
        Map<String,Object>d=must(db,"select * from kg_badge_def where code=:c and enabled='1' for update",p("c",code),"徽章不存在",404);
        require("manual".equals(d.get("metric")),"自动徽章由真实业务记录授予");
        Map<String,Object> prior=db.one("select award_id from kg_badge_award where app_user_id=:u and badge_code=:c and source_key=:s",p("u",uid,"c",code,"s",source));if(prior!=null)return id(prior.get("awardId"));
        if("city_youth".equals(code)){require(!city.isEmpty()&&city.length()<=64,"请填写任命城市");require(db.count("select count(*) from kg_badge_award where badge_code=:c and city=:city and status='active'",p("c",code,"city",city))==0,"该城市已有青年主理人，请先核对原任命");}
        if("city_builder".equals(code))city=builderCity(city);
        long aid=db.insert("kg_badge_award",p("app_user_id",uid,"badge_code",code,"source_key",source,"source_note",note,"city",city,"awarded_by",actor,"visible",Arrays.asList("gentle","comfort").contains(code)?"0":"1"));log(uid,aid,actor,"grant",note);return aid;
    }
    private String builderCity(String raw) {
        require(!raw.isEmpty() && raw.length() <= 64 && !"全部城市".equals(raw), "请填写城市共建人所属城市");
        String city = cities.businessCity(raw);
        require(db.count("select count(*) from kg_city where name=:c and status='0'", p("c", city)) > 0,
                "请选择城市字典中的有效城市或目的地");
        return city;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateBuilderCity(Long aid, Map<String, Object> body, String actor) {
        String city = builderCity(str(body.get("city")));
        String note = text(body, "note", 5, 360);
        Map<String, Object> award = must(db, "select * from kg_badge_award where award_id=:a for update",
                p("a", aid), "徽章不存在", 404);
        require("city_builder".equals(award.get("badgeCode")) && "active".equals(award.get("status")),
                "仅可更新有效城市共建人徽章的城市");
        if (city.equals(str(award.get("city")))) return;
        db.update("update kg_badge_award set city=:c where award_id=:a", p("c", city, "a", aid));
        log(id(award.get("appUserId")), aid, actor, "city_update", str(award.get("city")) + " → " + city + "；" + note);
    }
    @Transactional(rollbackFor=Exception.class) public void revoke(Long aid,String note,String actor){require(note.length()>=5&&note.length()<=500,"请填写撤销依据");Map<String,Object>a=must(db,"select * from kg_badge_award where award_id=:a for update",p("a",aid),"徽章不存在",404);require(!"automatic".equals(a.get("sourceKey")),"自动徽章请先核验对应业务记录");db.update("update kg_badge_award set status='revoked',worn='0' where award_id=:a",p("a",aid));log(id(a.get("appUserId")),aid,actor,"revoke",note);}
    @Transactional(rollbackFor=Exception.class) public void feedback(Long uid,Map<String,Object>body){String content=text(body,"content",20,2000),key=text(body,"requestKey",8,64);db.update("insert ignore into kg_badge_feedback(app_user_id,request_key,content) values(:u,:k,:c)",p("u",uid,"k",key,"c",content));}
    public List<Map<String,Object>> feedbacks(Long uid){return db.list("select feedback_id,content,status,review_note,create_time from kg_badge_feedback where app_user_id=:u order by feedback_id desc limit 30",p("u",uid));}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED) public void assessFeedback(Long id,Map<String,Object>body,String actor){String note=text(body,"note",5,500);Map<String,Object>f=must(db,"select * from kg_badge_feedback where feedback_id=:id for update",p("id",id),"反馈不存在",404);require("pending".equals(f.get("status")),"反馈已核验");db.update("update kg_badge_feedback set status=:s,review_note=:n,reviewed_by=:a where feedback_id=:id",p("id",id,"s",Boolean.TRUE.equals(body.get("valid"))?"valid":"invalid","n",note,"a",actor));log(id(f.get("appUserId")),null,actor,"feedback_review",note);sync(id(f.get("appUserId")));}
    private void log(Long uid,Long aid,String actor,String action,String note){db.insert("kg_badge_log",p("app_user_id",uid,"award_id",aid,"actor",actor,"action",action,"note",note));}
    public Map<String,Object> admin(int page){return p("feedback",db.list("select * from kg_badge_feedback order by feedback_id desc limit 100",p()),"definitions",definitions(),"awards",db.page("select a.*,d.name,u.nickname from kg_badge_award a join kg_badge_def d on d.code=a.badge_code left join kg_app_user u on u.user_id=a.app_user_id order by award_id desc",p(),page,30),"log",db.list("select * from kg_badge_log order by log_id desc limit 100",p()));}
}
