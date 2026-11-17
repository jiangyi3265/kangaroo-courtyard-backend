package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgInviteService;
import com.ruoyi.kangaroo.ext.user.KgMemberWallet;
import com.ruoyi.kangaroo.ext.user.KgMemberService;
import com.ruoyi.kangaroo.ext.user.KgMembershipOperations;
import com.ruoyi.kangaroo.ext.user.KgMembershipRewards;
import com.ruoyi.kangaroo.ext.user.KgPointLedger;
import com.ruoyi.kangaroo.ext.user.KgPointRewards;
import com.ruoyi.kangaroo.service.IKgMessageService;

/** Uses only the explicit disposable member-test database and synthetic accounts. */
class KgBadgeModerationExecutionTest {
    private static final long OWNER = 910001L, INVITED = 910002L, LEGAL = 910003L, OTHER = 910004L;
    private static final String ACTOR = "admin:badge-moderation-test";
    private static KgJdbc db;
    private static TransactionTemplate tx;
    private static KgBadgeModeration service;
    private static KgPointLedger ledger;
    private static KgMembershipRewards rewards;
    private static KgMembershipOperations operations;
    private static KgBadgeService badges;
    private static KgMemberService members;
    private static KgBadgeMetrics badgeMetrics;
    private Map<String, Object> originalExecution;

    @BeforeAll static void setup() {
        String name = System.getenv("KG_MEMBER_TEST_DB");
        assumeTrue(name != null && name.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sessionVariables=time_zone=%27%2B08:00%27",
                System.getenv("KG_MEMBER_TEST_USER"), System.getenv("KG_TEST_DB_PASSWORD"));
        db = new KgJdbc(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        ledger = new KgPointLedger(); field(ledger, "db", db);
        rewards = new KgMembershipRewards(); field(rewards, "db", db); field(rewards, "ledger", ledger);
        KgMemberWallet wallet = mock(KgMemberWallet.class); when(wallet.enabled()).thenReturn(true); field(rewards, "wallet", wallet);
        service = new KgBadgeModeration(); field(service, "db", db); field(service, "ledger", ledger); field(service, "rewards", rewards);
        badgeMetrics = new KgBadgeMetrics(); field(badgeMetrics, "db", db); field(badgeMetrics, "moderation", service);
        members = new KgMemberService(db);
        badges = new KgBadgeService(); field(badges, "db", db); field(badges, "metrics", badgeMetrics); field(badges, "members", members);
        field(badges, "rewards", mock(KgMembershipRewards.class)); field(service, "badges", badges);
        operations = new KgMembershipOperations(); field(operations, "db", db); field(operations, "ledger", ledger); field(operations, "rewards", rewards);
    }

    @BeforeEach void reset() {
        cleanup();
        originalExecution = db.one("select enabled,starts_at from kg_member_execution where id=1", p());
        db.update("update kg_member_execution set enabled='1',starts_at='2020-01-01' where id=1", p());
        for (long uid = OWNER; uid <= OTHER; uid++) db.insert("kg_app_user", p("user_id", uid, "openid", "badge-moderation-" + uid,
                "nickname", "隔离核定测试", "status", "0", "del_flag", "0", "create_time", LocalDateTime.now(Governance.ZONE).minusDays(40),
                "invited_time", LocalDateTime.now(Governance.ZONE).minusDays(35), "invited_by", uid == INVITED || uid == LEGAL ? OWNER : null));
    }

    @AfterEach void clear() {
        cleanup();
        if (originalExecution != null) db.update("update kg_member_execution set enabled=:e,starts_at=:s where id=1",
                p("e", originalExecution.get("enabled"), "s", originalExecution.get("startsAt")));
    }

    private void cleanup() {
        db.update("delete from kg_member_hot where post_id in(select post_id from kg_post where app_user_id between :a and :b)", scope());
        db.update("delete from kg_member_audit where operator=:actor or (action='review_quality_adjust' and target_id in(select review_id from kg_review where app_user_id between :a and :b))",
                p("actor", ACTOR, "a", OWNER, "b", OTHER));
        db.update("delete from kg_member_review_check where review_id in(select review_id from kg_review where app_user_id between :a and :b)", scope());
        for (String table : Arrays.asList("kg_badge_content_check", "kg_badge_violation", "kg_badge_integrity", "kg_badge_quarter_review", "kg_badge_log",
                "kg_badge_award", "kg_member_case", "kg_member_restriction", "kg_mediation_juror", "kg_point_entry", "kg_review", "kg_post", "kg_application", "kg_activity_signup"))
            db.update("delete from " + table + " where app_user_id between :a and :b", scope());
        db.update("delete from kg_job where store_id=:s", p("s", OWNER));
        db.update("delete from kg_app_user where user_id between :a and :b", scope());
    }

    @Test void sharedCreditInheritsOldDeductionsAndSerializesConcurrentChanges() throws Exception {
        db.insert("kg_mediation_juror", p("app_user_id", OWNER, "kind", "user", "credit_score", 88));
        assertEquals(88, service.creditScore(OWNER));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> in(() -> service.adjustCredit(OWNER, -5, ACTOR)));
            Future<Integer> b = pool.submit(() -> in(() -> service.adjustCredit(OWNER, -7, ACTOR)));
            assertEquals(-12, a.get() + b.get());
        } finally { pool.shutdownNow(); }
        assertEquals(76, service.creditScore(OWNER));
        assertEquals(76, db.count("select credit_score from kg_mediation_juror where app_user_id=:u", p("u", OWNER)));
        assertEquals(24, in(() -> service.adjustCredit(OWNER, 50, ACTOR)).intValue());
        assertEquals(100, service.creditScore(OWNER));
    }

    @Test void normalContentEntryUsesDigestAndSeparatesQualityFromFeatured() {
        long post = post(OWNER, "online");
        Map<String, Object> body = assessment("post", post, true, false);
        in(() -> service.assessContent(body, ACTOR));
        assertEquals(1, service.metrics(OWNER).get("qualityContents"));
        assertEquals(0, service.metrics(OWNER).get("featuredContents"));
        db.update("update kg_post set content='内容已经换了一篇' where post_id=:id", p("id", post));
        assertEquals(0, service.metrics(OWNER).get("qualityContents"));
        assertThrows(ServiceException.class, () -> in(() -> service.assessContent(body, ACTOR)));
        in(() -> service.assessContent(assessment("post", post, true, true), ACTOR));
        assertEquals(1, service.metrics(OWNER).get("featuredContents"));
        db.update("update kg_post set status='hidden' where post_id=:id", p("id", post));
        assertEquals(0, service.metrics(OWNER).get("qualityContents"));
    }

    @Test void verifiedReviewAndOfficialHotEvidenceAreDeduplicatedByContent() {
        long review = review(OWNER);
        db.insert("kg_member_review_check", p("review_id", review, "quality", "1", "image_verified", "1", "checked_by", ACTOR, "reason", "会员精华核验通过"));
        long post = post(OWNER, "online");
        db.update("update kg_post set official_repost_time=now() where post_id=:id", p("id", post));
        db.insert("kg_member_hot", p("post_id", post, "week_start", "2026-10-05", "score", 1));
        db.insert("kg_member_hot", p("post_id", post, "week_start", "2026-10-12", "score", 1));
        in(() -> service.assessContent(assessment("post", post, true, false), ACTOR));
        assertEquals(2, service.metrics(OWNER).get("qualityContents"));
        assertEquals(1, service.metrics(OWNER).get("featuredContents"));
        assertEquals(1L, service.metrics(OWNER).get("officialFeaturedContents"));
        db.update("update kg_member_review_check set revoked='1' where review_id=:id", p("id", review));
        assertEquals(1, service.metrics(OWNER).get("qualityContents"));
    }

    @Test void fakeReferralClawsOnlyLinkedRewardsOnceAndPreservesOtherInvitees() throws Exception {
        long fraudulentApp = application(INVITED), legalApp = application(LEGAL);
        ledger.award(OWNER, 100, "旧邀请奖励", "invite", INVITED);
        ledger.award(OWNER, 5, "推荐服务", "m_ref_service", fraudulentApp);
        ledger.award(OWNER, 5, "合法推荐服务", "m_ref_service", legalApp);
        ledger.award(OWNER, 20, "合法资料奖励", "profile", OWNER);
        Map<String, Object> body = violation(OWNER, "referral_fraud", "invited", INVITED, 10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> a = pool.submit(() -> in(() -> service.recordViolation(body, ACTOR)));
            Future<Long> b = pool.submit(() -> in(() -> service.recordViolation(body, ACTOR)));
            assertEquals(a.get(), b.get());
        } finally { pool.shutdownNow(); }
        assertEquals(90, service.creditScore(OWNER));
        assertEquals(25, ledger.totals(OWNER).earned);
        assertEquals(25, ledger.totals(OWNER).balance());
        long newFraudulentApp = application(INVITED), newLegalApp = application(LEGAL);
        in(() -> { rewards.sync(INVITED); rewards.sync(LEGAL); return null; });
        assertEquals(0, db.count("select count(*) from kg_point_entry where app_user_id=:u and biz_type='m_ref_service' and biz_id=:id", p("u", OWNER, "id", newFraudulentApp)));
        assertEquals(1, db.count("select count(*) from kg_point_entry where app_user_id=:u and biz_type='m_ref_service' and biz_id=:id", p("u", OWNER, "id", newLegalApp)));
    }

    @Test void legacyInvitePathRejectsOnlyTheConfirmedFraudulentPair() {
        in(() -> service.recordViolation(violation(OWNER, "referral_fraud", "invited", INVITED, 5), ACTOR));
        KgInviteService invites = new KgInviteService(); field(invites, "db", db); field(invites, "ledger", ledger);
        field(invites, "messages", mock(IKgMessageService.class));
        assertFalse(in(() -> invites.rewardInviter(INVITED, "volunteer", 999L, OTHER)));
        assertTrue(in(() -> invites.rewardInviter(LEGAL, "volunteer", 999L, OTHER)));
        assertEquals(100, ledger.totals(OWNER).balance());
    }

    @Test void unrelatedTargetIsRejectedBeforeCreditOrPointsChange() {
        long post = post(OTHER, "online");
        assertThrows(ServiceException.class, () -> in(() -> service.recordViolation(violation(OWNER, "false_content", "post", post, 50), ACTOR)));
        assertThrows(ServiceException.class, () -> in(() -> service.recordViolation(violation(OWNER, "referral_fraud", "invited", OTHER, 50), ACTOR)));
        assertEquals(100, service.creditScore(OWNER));
        assertEquals(0, db.count("select count(*) from kg_badge_violation where app_user_id=:u", p("u", OWNER)));
    }

    @Test void automatedQualityAndOrdinaryApprovalAwaitManualContentAssessment() {
        long automated = review(OWNER);
        db.insert("kg_member_review_check", p("review_id", automated, "quality", "1", "image_verified", "1", "checked_by", "system:review-photo", "reason", "自动初审通过"));
        post(OWNER, "online");
        assertEquals(0, service.metrics(OWNER).get("qualityContents"));
        assertEquals(0, service.metrics(OWNER).get("featuredContents"));
        in(() -> service.assessContent(assessment("review", automated, true, false), ACTOR));
        assertEquals(1, service.metrics(OWNER).get("qualityContents"));
        assertEquals(0, service.metrics(OWNER).get("featuredContents"));
    }

    @Test void legacyUnclassifiedCaseDoesNotInventFalseContentFinding() {
        long review = review(OWNER);
        db.insert("kg_member_review_check", p("review_id", review, "revoked", "1", "reason", "历史处理待补充具体类型"));
        db.insert("kg_member_case", p("app_user_id", OWNER, "severity", 1, "review_id", review, "facts", "已完成历史复核", "created_by", ACTOR,
                "status", "executed", "appeal_deadline", LocalDateTime.now(Governance.ZONE).minusDays(1)));
        Map<String, Object> metrics = service.metrics(OWNER);
        assertEquals(false, metrics.get("falseContent"));
        assertEquals(true, metrics.get("hasViolation"));
        assertEquals(true, metrics.get("historicalIntegrityNeedsReview"));
        in(() -> service.recordViolation(violation(OWNER, "false_content", "review", review, 0), ACTOR));
        assertEquals(false, service.metrics(OWNER).get("historicalIntegrityNeedsReview"));
    }

    @Test void previouslyReversedReviewRewardsAreNotDeductedTwice() {
        long review = review(OWNER);
        ledger.award(OWNER, 5, "真实评价", "m_review", review);
        ledger.award(OWNER, 5, "精华评价", "m_quality", review);
        ledger.award(OWNER, 20, "合法资料", "profile", OWNER);
        in(() -> { rewards.backReview(OWNER, review, true); return null; });
        assertEquals(20, ledger.totals(OWNER).balance());
        in(() -> service.recordViolation(violation(OWNER, "false_content", "review", review, 10), ACTOR));
        assertEquals(20, ledger.totals(OWNER).earned);
        assertEquals(20, ledger.totals(OWNER).balance());
        assertEquals("1", db.one("select revoked from kg_member_review_check where review_id=:id", p("id", review)).get("revoked"));
    }

    @Test void confirmedFakeReferralImmediatelyRemovesTheWornBadgeAndDuplicateIsInert() {
        for (long uid = 910010L; uid < 910020L; uid++) {
            db.insert("kg_app_user", p("user_id", uid, "openid", "badge-moderation-" + uid, "nickname", "已核实邀请体验",
                    "status", "0", "del_flag", "0", "create_time", LocalDateTime.now(Governance.ZONE).minusDays(40),
                    "invited_by", OWNER, "invited_time", LocalDateTime.now(Governance.ZONE).minusDays(35)));
            application(uid);
        }
        Map<String, Object> referral = badgeMetrics.referral(OWNER, KgBadgeRules.quarterStart(Governance.now().toLocalDate()), Governance.now());
        assertEquals(10L, Governance.num(referral.get("experiencedPeople")), referral.toString());
        in(() -> { badges.sync(OWNER); return null; });
        long award = db.count("select award_id from kg_badge_award where app_user_id=:u and badge_code='referrer' and status='active'", p("u", OWNER));
        assertTrue(award > 0);
        in(() -> { badges.display(OWNER, award, p("visible", true, "worn", true)); return null; });
        assertNotNull(members.identity(OWNER).get("achievement"));
        Map<String, Object> body = violation(OWNER, "referral_fraud", "invited", 910010L, 15);
        long violationId = in(() -> service.recordViolation(body, ACTOR));
        assertNull(members.identity(OWNER).get("achievement"));
        long logs = db.count("select count(*) from kg_badge_log where app_user_id=:u", p("u", OWNER));
        assertEquals(violationId, in(() -> service.recordViolation(body, ACTOR)).longValue());
        assertEquals(logs, db.count("select count(*) from kg_badge_log where app_user_id=:u", p("u", OWNER)));
        assertEquals(85, service.creditScore(OWNER));
    }

    @Test void travelerContentDecisionAndFalseContentImmediatelyRefreshPublicIdentity() {
        for (int i = 0; i < 6; i++) application(OWNER);
        long firstPost = 0;
        for (int i = 0; i < 10; i++) {
            long post = post(OWNER, "online");
            if (i == 0) firstPost = post;
            final Map<String, Object> assessment = assessment("post", post, true, i < 3);
            in(() -> service.assessContent(assessment, ACTOR));
        }
        long award = db.count("select award_id from kg_badge_award where app_user_id=:u and badge_code='traveler' and status='active'", p("u", OWNER));
        assertTrue(award > 0);
        in(() -> { badges.display(OWNER, award, p("visible", true, "worn", true)); return null; });
        assertNotNull(members.identity(OWNER).get("achievement"));
        Map<String, Object> decision = assessment("post", firstPost, false, false);
        in(() -> service.assessContent(decision, ACTOR));
        assertNull(members.identity(OWNER).get("achievement"));
        Map<String, Object> restored = assessment("post", firstPost, true, true);
        in(() -> service.assessContent(restored, ACTOR));
        in(() -> { badges.display(OWNER, award, p("visible", true, "worn", true)); return null; });
        Map<String, Object> violation = violation(OWNER, "false_content", "post", firstPost, 10);
        in(() -> service.recordViolation(violation, ACTOR));
        assertNull(members.identity(OWNER).get("achievement"));
    }

    @Test void falsePostClawsItsGuideRepostAndHotRewardsWithoutTouchingOtherPosts() {
        long badPost = post(OWNER, "online"), legalPost = post(OWNER, "online");
        long hot = db.insert("kg_member_hot", p("post_id", badPost, "week_start", "2026-10-05", "score", 1));
        ledger.award(OWNER, 20, "攻略奖励", "guide", badPost);
        ledger.award(OWNER, 5, "转发奖励", "m_repost", badPost);
        ledger.award(OWNER, 5, "热门奖励", "m_hot", hot);
        ledger.award(OWNER, 20, "合法攻略奖励", "guide", legalPost);
        in(() -> service.recordViolation(violation(OWNER, "false_content", "post", badPost, 10), ACTOR));
        assertEquals(20, ledger.totals(OWNER).balance());
        assertEquals("hidden", db.one("select status from kg_post where post_id=:id", p("id", badPost)).get("status"));
        db.update("update kg_post set status='online' where post_id=:id", p("id", badPost));
        assertThrows(ServiceException.class, () -> in(() -> { operations.repost(badPost, p("note", "平台准备登记一次官方转发"), ACTOR); return null; }));
        in(() -> { operations.repost(legalPost, p("note", "已核实这篇真实内容由官方转发"), ACTOR); return null; });
        assertEquals(25, ledger.totals(OWNER).balance());
    }

    @Test void RestoredFalsePostsCannotReceiveNewGuideOrWeeklyHotRewards() {
        long badPost = post(OWNER, "online"), legalPost = post(LEGAL, "online");
        in(() -> service.recordViolation(violation(OWNER, "false_content", "post", badPost, 0), ACTOR));
        db.update("update kg_post set status='online' where post_id=:id", p("id", badPost));
        KgPointRewards guides = new KgPointRewards(); field(guides, "db", db); field(guides, "ledger", ledger);
        guides.postOnline(badPost); guides.postOnline(legalPost);
        assertEquals(0, ledger.totals(OWNER).balance());
        assertEquals(20, ledger.totals(LEGAL).balance());
        LocalDate week = LocalDate.now(Governance.ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        db.update("update kg_post set create_time=:d where post_id in (:bad,:good)", p("d", week.plusDays(1), "bad", badPost, "good", legalPost));
        // Existing test suites may have previous-week snapshots. Scope this check to a clean snapshot in this disposable database.
        db.update("delete from kg_member_hot where week_start=:w", p("w", week));
        in(() -> { operations.weeklyHot(); return null; });
        assertEquals(0, db.count("select count(*) from kg_member_hot where post_id=:id", p("id", badPost)));
        assertEquals(1, db.count("select count(*) from kg_member_hot where post_id=:id", p("id", legalPost)));
    }

    private long application(long uid) {
        long job = db.insert("kg_job", p("store_id", OWNER, "job_type", "volunteer", "title", "隔离测试换工", "city", "测试城市"));
        return db.insert("kg_application", p("app_no", UUID.randomUUID().toString().replace("-", ""), "job_id", job, "store_id", OWNER,
                "app_user_id", uid, "real_name", "测试", "phone", "TEST_ONLY", "status", "onboarded", "del_flag", "0", "volunteer_days", 30,
                "onboard_time", LocalDateTime.now(Governance.ZONE).minusDays(30), "arrival_date", LocalDate.now(Governance.ZONE).minusDays(30),
                "volunteer_done_time", LocalDateTime.now(Governance.ZONE).minusDays(1), "volunteer_done_by", "staff:" + OTHER));
    }
    private long review(long uid) { return db.insert("kg_review", p("app_user_id", uid, "target_type", "stay", "target_id", 1L, "rating", 5, "content", "经过核验的真实评价内容", "status", "0", "create_time", LocalDateTime.now(Governance.ZONE))); }
    private long post(long uid, String status) { return db.insert("kg_post", p("app_user_id", uid, "kind", "daily", "title", "隔离核定测试帖子", "content", "已核实的旅居细节与真实照片", "status", status, "city", "测试城市", "create_time", LocalDateTime.now(Governance.ZONE))); }
    @SuppressWarnings("unchecked") private Map<String, Object> assessment(String type, long contentId, boolean quality, boolean featured) {
        Map<String, Object> contents = (Map<String, Object>) service.overview(OWNER, 1).get("contents");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) contents.get("rows");
        Map<String, Object> row = rows.stream().filter(item -> type.equals(item.get("contentType")) && contentId == KgJdbc.asLong(item.get("contentId"))).findFirst().get();
        return p("contentType", type, "contentId", contentId, "contentDigest", row.get("currentDigest"), "quality", quality, "featured", featured, "note", "已逐项核对内容真实来源和具体体验细节");
    }
    private Map<String, Object> violation(long uid, String type, String targetType, long target, int deduction) { return p("userId", uid, "violationType", type, "targetType", targetType, "targetId", target, "deduction", deduction, "note", "已核实关联记录存在虚假事实，并留存具体证据"); }
    private static Map<String, Object> scope() { return p("a", OWNER, "b", 910050L); }
    private static void field(Object object, String name, Object value) { ReflectionTestUtils.setField(object, name, value); }
    private static <T> T in(Supplier<T> work) { return tx.execute(status -> work.get()); }
}
