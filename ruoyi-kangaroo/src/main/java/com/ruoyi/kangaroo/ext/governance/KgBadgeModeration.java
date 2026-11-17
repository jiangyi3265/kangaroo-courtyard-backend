package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgMembershipRewards;
import com.ruoyi.kangaroo.ext.user.KgPointLedger;

/** Verified content and structured integrity evidence shared by badge rules and juror eligibility. */
@Service
public class KgBadgeModeration {
    @Autowired private KgJdbc db;
    @Autowired private KgPointLedger ledger;
    @Autowired private KgMembershipRewards rewards;
    @Autowired @Lazy private KgBadgeService badges;

    public int creditScore(Long uid) {
        return (int) db.count("select least(100,greatest(0,coalesce((select credit_score from kg_badge_integrity where app_user_id=:u),100)),"
                + "greatest(0,coalesce((select credit_score from kg_mediation_juror where app_user_id=:u),100)))", p("u", uid));
    }

    /** Callers that also lock jurors or content must first lock the account in the same order. */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int adjustCredit(Long uid, int signedDelta, String actor) {
        lockAccount(uid);
        Map<String, Object> juror = db.one("select credit_score from kg_mediation_juror where app_user_id=:u for update", p("u", uid));
        Map<String, Object> integrity = db.one("select credit_score from kg_badge_integrity where app_user_id=:u for update", p("u", uid));
        int current = Math.min(score(integrity), score(juror));
        int next = (int) Math.max(0L, Math.min(100L, (long) current + signedDelta));
        db.update("insert into kg_badge_integrity(app_user_id,credit_score,update_by,update_time) values(:u,:s,:a,now()) "
                + "on duplicate key update credit_score=:s,update_by=:a,update_time=now()", p("u", uid, "s", next, "a", actor));
        db.update("update kg_mediation_juror set credit_score=:s where app_user_id=:u", p("u", uid, "s", next));
        return next - current;
    }

    private static int score(Map<String, Object> row) {
        return row == null ? 100 : (int) Math.max(0, Math.min(100, num(row.get("creditScore"))));
    }

    private void lockAccount(Long uid) {
        must(db, "select user_id from kg_app_user where user_id=:u for update", p("u", uid), "会员不存在", 404);
    }

    public Map<String, Object> metrics(Long uid) {
        Map<String, Object> q = p("u", uid);
        Map<String, Object> integrity = db.one("select referral_fraud,false_content from kg_badge_integrity where app_user_id=:u", q);
        boolean referralFraud = integrity != null && "1".equals(integrity.get("referralFraud"));
        boolean falseContent = integrity != null && "1".equals(integrity.get("falseContent"));
        referralFraud |= db.count("select count(*) from kg_badge_violation where app_user_id=:u and violation_type='referral_fraud'", q) > 0;
        falseContent |= db.count("select count(*) from kg_badge_violation where app_user_id=:u and violation_type='false_content'", q) > 0;
        boolean memberViolation = db.count("select count(*) from kg_member_case where app_user_id=:u and status='executed'", q) > 0;
        boolean historicalIntegrityNeedsReview = db.count("select count(*) from kg_member_case c where c.app_user_id=:u and c.status='executed' "
                + "and (c.review_id is null or not exists(select 1 from kg_badge_violation v where v.app_user_id=:u "
                + "and v.violation_type='false_content' and v.target_type='review' and v.target_id=c.review_id))", q) > 0
                || db.count("select count(*) from kg_member_review_check c join kg_review r on r.review_id=c.review_id "
                + "where r.app_user_id=:u and c.revoked='1' and not exists(select 1 from kg_badge_violation v where v.app_user_id=:u "
                + "and v.violation_type='false_content' and v.target_type='review' and v.target_id=r.review_id)", q) > 0;
        boolean restricted = db.count("select count(*) from kg_member_restriction where app_user_id=:u "
                + "and (frozen='1' or level_penalty>0 or points_blocked_until>now() or reviews_blocked_until>now())", q) > 0;
        boolean jurorViolation = db.count("select count(*) from kg_mediation_juror where app_user_id=:u "
                + "and (disqualified='1' or violations>0 or suspended_until>now())", q) > 0;
        boolean available = db.count("select count(*) from kg_app_user where user_id=:u and status='0' and del_flag='0'", q) > 0
                && db.count("select count(*) from kg_member_restriction where app_user_id=:u and frozen='1'", q) == 0;
        Set<String> quality = new HashSet<String>();
        Set<String> featured = new HashSet<String>();
        for (Map<String, Object> content : db.list(contentQuery() + " where x.app_user_id=:u", q)) {
            contentState(content);
            String key = str(content.get("contentType")) + ":" + content.get("contentId");
            if ("1".equals(content.get("quality"))) quality.add(key);
            if ("1".equals(content.get("featured"))) featured.add(key);
        }
        long official = db.count("select count(*) from kg_post p where p.app_user_id=:u and p.status='online' and p.del_flag='0' "
                + "and (p.official_repost_time is not null or exists(select 1 from kg_member_hot h where h.post_id=p.post_id)) "
                + "and not exists(select 1 from kg_badge_violation v where v.app_user_id=:u and v.violation_type='false_content' "
                + "and v.target_type='post' and v.target_id=p.post_id)", q);
        return p("creditScore", creditScore(uid), "referralFraud", referralFraud, "falseContent", falseContent,
                "hasViolation", referralFraud || falseContent || memberViolation || restricted || jurorViolation, "accountAvailable", available,
                "historicalIntegrityNeedsReview", historicalIntegrityNeedsReview,
                "qualityContents", quality.size(), "featuredContents", featured.size(), "officialFeaturedContents", official);
    }

    public Map<String, Object> overview(Long userId, int page) {
        if (userId != null) must(db, "select user_id from kg_app_user where user_id=:u", p("u", userId), "会员不存在", 404);
        Map<String, Object> q = p("u", userId);
        String scope = userId == null ? "" : " where x.app_user_id=:u";
        Map<String, Object> contents = db.page(contentQuery() + scope + " order by x.create_time desc,x.content_type,x.content_id desc", q, page, 20);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) contents.get("rows");
        for (Map<String, Object> row : rows) contentState(row);
        return p("member", userId == null ? null : metrics(userId), "userId", userId, "contents", contents,
                "violations", db.list("select * from kg_badge_violation" + (userId == null ? "" : " where app_user_id=:u")
                        + " order by violation_id desc limit 100", q));
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> assessContent(Map<String, Object> body, String actor) {
        String type = contentType(body.get("contentType"));
        Long contentId = positiveId(body.get("contentId"));
        String note = text(body, "note", 10, 500);
        require(body.get("quality") instanceof Boolean && body.get("featured") instanceof Boolean, "请选择优质与精华核定结果");
        boolean quality = Boolean.TRUE.equals(body.get("quality"));
        boolean featured = Boolean.TRUE.equals(body.get("featured"));
        require(!featured || quality, "精华内容须同时达到优质要求");
        Map<String, Object> content = source(type, contentId, false);
        Long uid = id(content.get("appUserId"));
        lockAccount(uid);
        content = source(type, contentId, true);
        require(uid.equals(id(content.get("appUserId"))), "内容归属已变化，请刷新");
        require(live(content, type), "内容须通过审核并保持公开，才能核定质量");
        require(!isFalseContent(uid, type, contentId), "这条内容已有核实的虚假记录");
        if ("review".equals(type)) require(db.count("select count(*) from kg_member_review_check where review_id=:id and revoked='1'", p("id", contentId)) == 0,
                "该评价的奖励资格已撤销，请先完成会员复核");
        content.put("contentType", type);
        content.put("contentId", contentId);
        require(contentDigest(content).equals(str(body.get("contentDigest"))), "内容已变化，请刷新并重新核定");
        db.update("insert into kg_badge_content_check(content_type,content_id,app_user_id,quality,featured,content_digest,note,checked_by,checked_time) "
                + "values(:t,:id,:u,:q,:f,:digest,:n,:a,now()) on duplicate key update app_user_id=:u,quality=:q,featured=:f,"
                + "content_digest=:digest,note=:n,checked_by=:a,checked_time=now()",
                p("t", type, "id", contentId, "u", uid, "q", quality ? "1" : "0", "f", featured ? "1" : "0",
                        "digest", contentDigest(content), "n", note, "a", actor));
        audit(uid, actor, "content_quality", type + "#" + contentId + " 优质=" + quality + " 精华=" + featured + " " + note, null);
        badges.sync(uid);
        return metrics(uid);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long recordViolation(Map<String, Object> body, String actor) {
        Long uid = positiveId(body.get("userId"));
        Long target = positiveId(body.get("targetId"));
        String type = text(body, "violationType", 1, 30);
        String targetType = text(body, "targetType", 1, 16);
        String note = text(body, "note", 10, 500);
        require(str(body.get("deduction")).matches("[0-9]{1,3}"), "信用扣分应为0至100的整数");
        int deduction = Integer.parseInt(str(body.get("deduction")));
        require(deduction >= 0 && deduction <= 100, "信用扣分应为0至100的整数");
        require(("referral_fraud".equals(type) && "invited".equals(targetType))
                || ("false_content".equals(type) && Arrays.asList("review", "post").contains(targetType)), "请选择违规类型与对应记录");
        lockAccount(uid);
        Map<String, Object> q = p("u", uid, "t", type, "targetType", targetType, "target", target);
        Map<String, Object> previous = db.one("select violation_id from kg_badge_violation where app_user_id=:u and violation_type=:t "
                + "and target_type=:targetType and target_id=:target", q);
        if (previous != null) return id(previous.get("violationId"));
        if ("invited".equals(targetType)) {
            require(db.count("select count(*) from kg_app_user where user_id=:id and invited_by=:u", p("id", target, "u", uid)) > 0,
                    "该账号不在这位会员的邀请记录中");
        } else {
            Map<String, Object> content = source(targetType, target, false);
            require(uid.equals(id(content.get("appUserId"))), "内容不属于这位会员");
        }
        int actualCreditDelta = adjustCredit(uid, -deduction, actor);
        long violationId = db.insert("kg_badge_violation", p("app_user_id", uid, "violation_type", type, "target_type", targetType,
                "target_id", target, "credit_deduction", -actualCreditDelta, "note", note, "created_by", actor));
        String flag = "referral_fraud".equals(type) ? "referral_fraud" : "false_content";
        db.update("update kg_badge_integrity set " + flag + "='1',update_by=:a,update_time=now() where app_user_id=:u", p("u", uid, "a", actor));
        if ("review".equals(targetType)) {
            source(targetType, target, true);
            db.update("insert into kg_member_review_check(review_id,reason,revoked,revoke_base) values(:id,:n,'1','1') "
                    + "on duplicate key update revoked='1',revoke_base='1',quality='0',reason=:n", p("id", target, "n", note));
            rewards.backReview(uid, target, true);
        } else {
            long remaining = relatedReward(uid, targetType, target);
            ledger.clawBack(uid, Math.toIntExact(remaining), "核实" + ("invited".equals(targetType) ? "虚假邀请" : "虚假内容") + "，扣回关联奖励",
                    "badge_penalty", violationId);
            if ("post".equals(targetType)) {
                db.update("update kg_post set status='hidden',audit_reason=:n,update_by=:a,update_time=now() where post_id=:id and del_flag='0'",
                        p("n", note.substring(0, Math.min(255, note.length())), "a", actor, "id", target));
            }
        }
        audit(uid, actor, "integrity_violation", type + " " + targetType + "#" + target + " " + note, actualCreditDelta);
        badges.sync(uid);
        return violationId;
    }

    private long relatedReward(Long uid, String targetType, Long target) {
        String condition;
        if ("invited".equals(targetType)) {
            condition = "(e.biz_type='invite' and e.biz_id=:id) or (e.biz_type='m_ref_service' and exists(select 1 from kg_application a "
                    + "where a.app_id=e.biz_id and a.app_user_id=:id)) or (e.biz_type='m_ref_training' and exists(select 1 from kg_activity_signup g "
                    + "where g.signup_id=e.biz_id and g.app_user_id=:id))";
        } else {
            condition = "(e.biz_type in ('guide','m_repost') and e.biz_id=:id) or (e.biz_type='m_hot' and exists(select 1 from kg_member_hot h "
                    + "where h.hot_id=e.biz_id and h.post_id=:id))";
        }
        return Math.max(0, db.count("select coalesce(sum(e.delta),0) from kg_point_entry e where e.app_user_id=:u and (" + condition + ")",
                p("u", uid, "id", target)));
    }

    private boolean isFalseContent(Long uid, String type, Long contentId) {
        return db.count("select count(*) from kg_badge_violation where app_user_id=:u and violation_type='false_content' "
                + "and target_type=:t and target_id=:id", p("u", uid, "t", type, "id", contentId)) > 0;
    }

    private Map<String, Object> source(String type, Long contentId, boolean lock) {
        String table = "review".equals(type) ? "kg_review" : "kg_post";
        String idColumn = "review".equals(type) ? "review_id" : "post_id";
        return must(db, "select * from " + table + " where " + idColumn + "=:id" + (lock ? " for update" : ""), p("id", contentId), "内容不存在", 404);
    }

    private static boolean live(Map<String, Object> content, String type) {
        return "0".equals(content.get("delFlag")) && ("review".equals(type) ? "0" : "online").equals(content.get("status"));
    }

    private static String contentType(Object value) {
        String type = str(value);
        require(Arrays.asList("review", "post").contains(type), "请选择评价或社区帖子");
        return type;
    }

    private static Long positiveId(Object value) {
        Long result = id(value);
        require(result != null && result > 0, "请填写有效记录ID");
        return result;
    }

    private String contentQuery() {
        return "select x.*,m.quality manual_quality,m.featured manual_featured,m.content_digest,m.note,m.checked_by,m.checked_time,"
                + "exists(select 1 from kg_badge_violation v where v.app_user_id=x.app_user_id and v.violation_type='false_content' "
                + "and v.target_type=x.content_type and v.target_id=x.content_id) false_content from ("
                + "select 'review' content_type,r.review_id content_id,r.app_user_id,'' title,r.content,r.images,r.status,r.del_flag,r.create_time,"
                + "case when c.quality='1' and c.image_verified='1' and coalesce(c.checked_by,'')<>'' and c.checked_by not like 'system:%' then '1' else '0' end source_quality,"
                + "coalesce(c.revoked,'0') source_revoked from kg_review r "
                + "left join kg_member_review_check c on c.review_id=r.review_id union all "
                + "select 'post',p.post_id,p.app_user_id,p.title,p.content,p.images,p.status,p.del_flag,p.create_time,'0','0' from kg_post p"
                + ") x left join kg_badge_content_check m on m.content_type=x.content_type and m.content_id=x.content_id";
    }

    static void contentState(Map<String, Object> content) {
        String type = str(content.get("contentType"));
        boolean falseContent = Boolean.TRUE.equals(content.get("falseContent")) || "1".equals(str(content.get("falseContent")));
        boolean allowed = live(content, type) && !"1".equals(content.get("sourceRevoked")) && !falseContent;
        boolean manual = content.get("manualQuality") != null;
        boolean matches = contentDigest(content).equals(content.get("contentDigest"));
        boolean quality = allowed && (manual ? matches && "1".equals(content.get("manualQuality")) : "1".equals(content.get("sourceQuality")));
        boolean featured = quality && (manual ? "1".equals(content.get("manualFeatured")) : "1".equals(content.get("sourceQuality")));
        content.put("quality", quality ? "1" : "0");
        content.put("featured", featured ? "1" : "0");
        content.put("canAssess", allowed);
        content.put("needsRecheck", manual && !matches);
        content.put("currentDigest", contentDigest(content));
        content.put("sourceLabel", manual ? (matches ? "人工核定" : "内容已修改，待重新核定") : ("1".equals(content.get("sourceQuality")) ? "会员精华核验" : "待核定"));
    }

    static String contentDigest(Map<String, Object> content) {
        StringBuilder payload = new StringBuilder();
        for (String field : Arrays.asList("contentType", "contentId", "title", "content", "images")) {
            String value = str(content.get(field));
            payload.append(value.length()).append(':').append(value);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            for (byte value : hash) encoded.append(String.format("%02x", value & 0xff));
            return encoded.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private void audit(Long uid, String actor, String action, String note, Integer creditDelta) {
        db.insert("kg_badge_log", p("app_user_id", uid, "actor", actor, "action", action,
                "note", note.substring(0, Math.min(500, note.length())), "credit_delta", creditDelta));
    }
}
