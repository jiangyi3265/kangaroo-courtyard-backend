package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.user.KgMembershipExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgReviewPhotoService;

/** Runs only with the existing isolated MySQL membership harness. */
class KgAutomaticPhotoResultTest {
    final KgMembershipExecutionTest fixture = new KgMembershipExecutionTest();
    KgMembershipRewards callback;
    KgMembershipOperations operations;

    @BeforeEach void prepare() {
        KgMembershipExecutionTest.setup();
        fixture.reset();
        callback = proxy(rewards, KgMembershipRewards.class);
        field(ops, "rewards", callback);
        operations = proxy(ops, KgMembershipOperations.class);
    }

    <T> T proxy(T target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(tx.getTransactionManager(), new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    Map<String, Object> review(long id) {
        return db.must("select * from kg_review where review_id=:r", p("r", id), "test review missing");
    }

    Map<String, Object> check(long id) {
        return db.one("select * from kg_member_review_check where review_id=:r", p("r", id));
    }

    String fingerprint(long id) {
        return KgReviewPhotoService.fingerprint(review(id));
    }

    long createReview() {
        long id = fixture.review(fixture.stay());
        db.update("update kg_review set images='/profile/upload/test-photo.png' where review_id=:r", p("r", id));
        return id;
    }

    void manual(long id, boolean real) {
        operations.review(id, p("action", "verify", "real", real, "note", "已逐张查看原图并核实订单情况"), "admin:test");
    }

    @Test void approvedPhotoAwardsOnlyOnceAndDoesNotDependOnPositiveRating() {
        long id = createReview();
        String fingerprint = fingerprint(id);
        assertEquals(2L, KgJdbc.asLong(review(id).get("rating")));
        assertTrue(callback.applyAutomaticPhotoResult(id, fingerprint, true));
        assertEquals("1", check(id).get("imageVerified"));
        assertEquals("auto:photo", check(id).get("checkedBy"));
        assertEquals("1", review(id).get("featured"));
        assertEquals(17, ledger.totals(1L).balance());
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true));
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, false));
        assertEquals(17, ledger.totals(1L).balance());
        assertEquals(1, db.count("select count(*) from kg_point_entry where biz_type='m_quality' and biz_id=:r", p("r", id)));
    }

    @Test void callbackRechecksCompletedOrderOwnershipAndExistence() {
        long id = createReview();
        long order = KgJdbc.asLong(review(id).get("bizId"));
        String fingerprint = fingerprint(id);
        for (String status : Arrays.asList("pending", "cancelled", "refunded")) {
            db.update("update kg_stay_order set status=:s where order_id=:o", p("s", status, "o", order));
            assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true), status);
        }
        db.update("update kg_stay_order set status='completed',app_user_id=2 where order_id=:o", p("o", order));
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true));
        db.update("delete from kg_stay_order where order_id=:o", p("o", order));
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true));
        assertFalse(callback.applyAutomaticPhotoResult(Long.MAX_VALUE, fingerprint, true));
        assertNull(check(id));
        assertEquals(0, ledger.totals(1L).balance());
    }

    @Test void staleContentImagesOrderOrOwnerCannotReuseAnEarlierResult() {
        long id = createReview();
        Map<String, Object> original = review(id);
        String fingerprint = fingerprint(id);
        for (String column : Arrays.asList("content", "images", "biz_type", "biz_id", "app_user_id")) {
            Object changed = column.endsWith("_id") ? 2L : "changed";
            db.update("update kg_review set " + column + "=:v where review_id=:r", p("v", changed, "r", id));
            assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true), column);
            db.update("update kg_review set content=:content,images=:images,biz_type=:bizType,biz_id=:bizId,app_user_id=:appUserId where review_id=:reviewId", original);
        }
        assertFalse(callback.applyAutomaticPhotoResult(id, null, true));
        assertNull(check(id));
        assertEquals(0, ledger.totals(1L).balance());
    }

    @Test void manualVerdictAlwaysTakesPriorityOverSubsequentAutomaticResult() {
        long id = createReview();
        manual(id, false);
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        assertEquals("0", check(id).get("imageVerified"));
        assertEquals("admin:test", check(id).get("checkedBy"));
        assertEquals(12, ledger.totals(1L).balance());
        manual(id, true);
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint(id), false));
        assertEquals("1", check(id).get("imageVerified"));
        assertEquals(17, ledger.totals(1L).balance());
    }

    @Test void manualRejectionAfterAutomaticApprovalReversesQualityPointsExactlyOnce() {
        long id = createReview();
        assertTrue(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        manual(id, false);
        manual(id, false);
        assertEquals("0", review(id).get("featured"));
        assertEquals("0", check(id).get("imageVerified"));
        assertEquals("admin:test", check(id).get("checkedBy"));
        assertEquals(12, ledger.totals(1L).balance());
        assertEquals(1, db.count("select count(*) from kg_point_entry where biz_type='m_quality_back' and biz_id=:r", p("r", id)));
    }

    @Test void repeatedManualCorrectionsKeepQualityGrowthAndAvailablePointsConsistent() {
        long id = createReview();
        assertTrue(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        manual(id, false);
        for (int cycle = 0; cycle < 2; cycle++) {
            manual(id, true);
            manual(id, true);
            callback.sync(1L);
            callback.sync(1L);
            assertEquals("1", review(id).get("featured"));
            assertEquals(17, ledger.totals(1L).earned);
            assertEquals(17, ledger.totals(1L).balance());
            manual(id, false);
            manual(id, false);
            callback.sync(1L);
            callback.sync(1L);
            assertEquals("0", review(id).get("featured"));
            assertEquals(12, ledger.totals(1L).earned);
            assertEquals(12, ledger.totals(1L).balance());
            assertEquals(0, ledger.totals(1L).spent);
        }
        assertEquals(1, db.count("select count(*) from kg_point_entry where biz_type='m_review' and biz_id=:r", p("r", id)));
        assertEquals(2, db.count("select delta from kg_point_entry where biz_type='m_review' and biz_id=:r", p("r", id)));
        assertEquals(5, db.count("select delta from kg_point_entry where biz_type='m_quality' and biz_id=:r", p("r", id)));
        assertEquals(-5, db.count("select delta from kg_point_entry where biz_type='m_quality_back' and biz_id=:r", p("r", id)));
        assertEquals(4, db.count("select count(*) from kg_point_entry where biz_type='m_quality_adjust' and app_user_id=1", p()));
        assertEquals(4, db.count("select count(*) from kg_point_entry e join kg_member_audit a on a.audit_id=e.biz_id "
                + "where e.biz_type='m_quality_adjust' and a.action='review_quality_adjust' and a.target_id=:r", p("r", id)));
        assertEquals("精华奖励校正", ledger.records(1L, 1, 1).get(0).get("kind"));
    }

    @Test void routineSyncRepairsPreviouslyReconfirmedPhotoWithoutRepeatingBaseReward() {
        long id = createReview();
        assertTrue(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        manual(id, false);
        // State produced by the earlier code when a rejected photo was confirmed again.
        db.update("update kg_member_review_check set image_verified='1',quality='1',image_note='人工再次确认实拍' where review_id=:r", p("r", id));
        db.update("update kg_review set featured='1' where review_id=:r", p("r", id));
        callback.sync(1L);
        callback.sync(1L);
        assertEquals(17, ledger.totals(1L).earned);
        assertEquals(17, ledger.totals(1L).balance());
        assertEquals(1, db.count("select count(*) from kg_point_entry where biz_type='m_quality_adjust' and app_user_id=1", p()));
        assertEquals(1, db.count("select count(*) from kg_point_entry where biz_type='m_review' and biz_id=:r", p("r", id)));
    }

    @Test void uncertainPhotoWaitsForManualApprovalAndNeverAutomaticallyRetriesOverIt() {
        long id = createReview();
        assertTrue(callback.applyAutomaticPhotoResult(id, fingerprint(id), false));
        assertEquals("0", check(id).get("imageVerified"));
        assertEquals("0", review(id).get("featured"));
        assertEquals(12, ledger.totals(1L).balance());
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        manual(id, true);
        assertEquals(17, ledger.totals(1L).balance());
        assertEquals("1", review(id).get("featured"));
    }

    @Test void disabledRulesHiddenDeletedHistoricalOrDisabledOwnerRejectCallbacks() {
        long id = createReview();
        String fingerprint = fingerprint(id);
        db.update("update kg_member_execution set enabled='0' where id=1", p());
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true));
        db.update("update kg_member_execution set enabled='1' where id=1", p());
        for (String column : Arrays.asList("status", "del_flag")) {
            db.update("update kg_review set " + column + "='1' where review_id=:r", p("r", id));
            assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true), column);
            db.update("update kg_review set " + column + "='0' where review_id=:r", p("r", id));
            db.update("update kg_app_user set " + column + "='1' where user_id=1", p());
            assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true), column);
            db.update("update kg_app_user set " + column + "='0' where user_id=1", p());
        }
        db.update("update kg_review set create_time='2025-12-31 23:59:59' where review_id=:r", p("r", id));
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint, true));
        assertNull(check(id));
        assertEquals(0, ledger.totals(1L).balance());
    }

    @Test void archivedOrRevokedReviewsCannotBeReopenedByAutomaticResult() {
        long id = createReview();
        db.insert("kg_member_review_check", p("review_id", id, "reason", "人工存档", "archived", "1"));
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        db.update("update kg_member_review_check set archived='0',revoked='1' where review_id=:r", p("r", id));
        assertFalse(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        assertEquals("0", check(id).get("imageVerified"));
        assertEquals(0, ledger.totals(1L).balance());
    }

    @Test void imageApprovalStillRequiresEnoughSpecificOriginalTextForQualityPoints() {
        long id = createReview();
        db.update("update kg_review set content='很好' where review_id=:r", p("r", id));
        assertTrue(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        assertEquals("1", check(id).get("imageVerified"));
        assertEquals("0", review(id).get("featured"));
        assertEquals(12, ledger.totals(1L).balance());
    }

    @Test void concurrentManualReviewThroughNormalEntrySeesAndReversesJustCommittedAutomaticReward() throws Exception {
        long id = createReview();
        String fingerprint = fingerprint(id);
        CountDownLatch automaticAwarded = new CountDownLatch(1);
        CountDownLatch manualReadOwner = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        KgPointLedger interceptedLedger = spy(ledger);
        field(rewards, "ledger", interceptedLedger);
        doAnswer(invocation -> {
            Object awarded = invocation.callRealMethod();
            automaticAwarded.countDown();
            assertTrue(allowCommit.await(10, TimeUnit.SECONDS));
            return awarded;
        }).when(interceptedLedger).award(eq(1L), eq(5), anyString(), eq("m_quality"), eq(id));
        KgJdbc manualDb = spy(db);
        field(ops, "db", manualDb);
        doAnswer(invocation -> {
            Object owner = invocation.callRealMethod();
            manualReadOwner.countDown();
            return owner;
        }).when(manualDb).must(eq("select app_user_id from kg_review where review_id=:r"), anyMap(), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> automatic = pool.submit(() -> callback.applyAutomaticPhotoResult(id, fingerprint, true));
            assertTrue(automaticAwarded.await(10, TimeUnit.SECONDS));
            Future<?> human = pool.submit(() -> manual(id, false));
            assertTrue(manualReadOwner.await(10, TimeUnit.SECONDS));
            allowCommit.countDown();
            assertTrue(automatic.get(10, TimeUnit.SECONDS));
            human.get(10, TimeUnit.SECONDS);
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("admin:test", check(id).get("checkedBy"));
        assertEquals("0", check(id).get("imageVerified"));
        assertEquals("0", review(id).get("featured"));
        assertEquals(12, ledger.totals(1L).balance());
        assertEquals(1, db.count("select count(*) from kg_point_entry where biz_type='m_quality_back' and biz_id=:r", p("r", id)));
    }

    @Test void concurrentRoutineReconciliationCannotRestoreQualityFromBeforeManualRejection() throws Exception {
        long id = createReview();
        assertTrue(callback.applyAutomaticPhotoResult(id, fingerprint(id), true));
        CountDownLatch manualAudited = new CountDownLatch(1);
        CountDownLatch reconciliationSnapshot = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        KgJdbc manualDb = spy(db);
        field(ops, "db", manualDb);
        doAnswer(invocation -> {
            Object audit = invocation.callRealMethod();
            manualAudited.countDown();
            assertTrue(allowCommit.await(10, TimeUnit.SECONDS));
            return audit;
        }).when(manualDb).insert(eq("kg_member_audit"), anyMap());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> human = pool.submit(() -> manual(id, false));
            assertTrue(manualAudited.await(10, TimeUnit.SECONDS));
            KgJdbc enabledDb = spy(db);
            field(wallet, "db", enabledDb);
            doAnswer(invocation -> {
                Object enabled = invocation.callRealMethod();
                reconciliationSnapshot.countDown();
                return enabled;
            }).when(enabledDb).count(eq("select count(*) from kg_member_execution where id=1 and enabled='1'"), anyMap());
            TransactionTemplate repeatableRead = new TransactionTemplate(tx.getTransactionManager());
            repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            Future<?> reconcile = pool.submit(() -> repeatableRead.execute(status -> {
                callback.sync(1L);
                return null;
            }));
            assertTrue(reconciliationSnapshot.await(10, TimeUnit.SECONDS));
            allowCommit.countDown();
            human.get(10, TimeUnit.SECONDS);
            reconcile.get(10, TimeUnit.SECONDS);
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("admin:test", check(id).get("checkedBy"));
        assertEquals("0", check(id).get("imageVerified"));
        assertEquals("0", check(id).get("quality"));
        assertEquals("0", review(id).get("featured"));
        assertEquals(12, ledger.totals(1L).balance());
    }
}
