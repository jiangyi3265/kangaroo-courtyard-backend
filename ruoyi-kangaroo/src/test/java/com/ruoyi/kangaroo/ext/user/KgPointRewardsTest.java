package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 2026-10-17 主人样稿的两条积分规则（KgPointRewards）：
 *   消费获得——入住订单完成按「实付 − 已退款」每 1 元得 N 分、每单一次、完成后退款扣回多发的（只越扣越多、不超过当初发的）；
 *   攻略获得——旅居攻略已发布得 N 分、每篇一次、每人每天最多 N 篇；参数填 0 暂停；都在业务事务提交之后才发，出错只记日志。
 */
class KgPointRewardsTest
{
    private final KgJdbc db = mock(KgJdbc.class);

    private final ISysConfigService configs = mock(ISysConfigService.class);

    private final KgPointRewards rewards = new KgPointRewards();

    private static final String ORDER_SQL = "from kg_stay_order where order_id = :id and del_flag = '0' for update";

    private static final String PAY_SQL = "from kg_pay_order where pay_no = :n";

    private static final String AWARD_SQL = "insert ignore into kg_point_entry";

    private static final String BACK_SQL = "on duplicate key update";

    private static final String POST_SQL = "from kg_post where post_id = :p";

    @BeforeEach
    void setup()
    {
        KgPointLedger ledger = new KgPointLedger();
        ReflectionTestUtils.setField(ledger, "db", db);
        ReflectionTestUtils.setField(ledger, "configs", configs);
        ReflectionTestUtils.setField(rewards, "db", db);
        ReflectionTestUtils.setField(rewards, "ledger", ledger);
        when(db.update(startsWith(AWARD_SQL), anyMap())).thenReturn(1);
        when(db.one(contains("from kg_app_user where user_id = :u for update"), anyMap())).thenReturn(p("userId", 7L));
        when(db.one(contains("from kg_badge_violation"), anyMap())).thenReturn(null);
    }

    @AfterEach
    void clearSync()
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void order(String status)
    {
        when(db.one(contains(ORDER_SQL), anyMap())).thenReturn(p("orderId", 9L, "orderNo", "S2610170001", "appUserId", 7L, "status", status, "payNo", "P9"));
    }

    private void pay(long amount, long refunded, String status)
    {
        when(db.one(contains(PAY_SQL), anyMap())).thenReturn(p("amount", amount, "refundAmount", refunded, "status", status));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object x)
    {
        return (Map<String, Object>) x;
    }

    // ---------------------------------------------------------------- 消费获得：金额

    @Test
    void spendPointsAreWholeYuanTimesRate()
    {
        assertEquals(123, KgPointRewards.stayPoints(12345, 1));
        assertEquals(246, KgPointRewards.stayPoints(12345, 2));
        assertEquals(0, KgPointRewards.stayPoints(99, 1), "不足 1 元不算");
        assertEquals(1, KgPointRewards.stayPoints(100, 1));
        assertEquals(0, KgPointRewards.stayPoints(0, 1));
        assertEquals(0, KgPointRewards.stayPoints(-500, 1));
        assertEquals(0, KgPointRewards.stayPoints(50000, 0), "每元 0 分 = 暂停");
        assertEquals(KgPointRewards.MAX_STAY_POINTS, KgPointRewards.stayPoints(Long.MAX_VALUE / 200, 100));
    }

    @Test
    void completedOrderEarnsPaidMinusRefundedOncePerOrder()
    {
        order("completed");
        pay(50000, 12050, "part_refunded");
        rewards.stayCompleted(9L);
        verify(db).update(startsWith(AWARD_SQL), argThat(x -> Integer.valueOf(379).equals(m(x).get("d")) && "stay_spend".equals(m(x).get("t"))
                && Long.valueOf(9).equals(m(x).get("b")) && Long.valueOf(7).equals(m(x).get("u")) && "入住消费 订单号S2610170001".equals(m(x).get("r"))));
        // 同一张单再来一次完成事件（自动完成和前台退房撞上、重试）：唯一键挡住，insert ignore 不记，返回 0
        when(db.update(startsWith(AWARD_SQL), anyMap())).thenReturn(0);
        assertEquals(0, rewards.grantStay(9L));
    }

    @Test
    void spendRateComesFromSettingsAndZeroPauses()
    {
        order("completed");
        pay(30000, 0, "paid");
        when(configs.selectConfigByKey(KgPointLedger.KEY_SPEND_PER_YUAN)).thenReturn("3");
        assertEquals(900, rewards.grantStay(9L));
        when(configs.selectConfigByKey(KgPointLedger.KEY_SPEND_PER_YUAN)).thenReturn("0");
        clearInvocations(db);
        assertEquals(0, rewards.grantStay(9L));
        verifyNoInteractions(db);
        // 乱填（超过 100、不是数字）按默认 1
        when(configs.selectConfigByKey(KgPointLedger.KEY_SPEND_PER_YUAN)).thenReturn("1000");
        assertEquals(300, rewards.grantStay(9L));
    }

    @Test
    void notCompletedUnpaidOrFullyRefundedOrdersEarnNothing()
    {
        order("checked_in");
        pay(30000, 0, "paid");
        assertEquals(0, rewards.grantStay(9L));
        order("completed");
        pay(30000, 30000, "refunded");
        assertEquals(0, rewards.grantStay(9L));
        pay(30000, 0, "closed");
        assertEquals(0, rewards.grantStay(9L));
        when(db.one(contains(ORDER_SQL), anyMap())).thenReturn(p("orderId", 9L, "orderNo", "S1", "appUserId", 7L, "status", "completed", "payNo", ""));
        assertEquals(0, rewards.grantStay(9L));
        when(db.one(contains(ORDER_SQL), anyMap())).thenReturn(null);
        assertEquals(0, rewards.grantStay(9L));
        verify(db, never()).update(startsWith(AWARD_SQL), anyMap());
    }

    // ---------------------------------------------------------------- 消费获得：完成后退款扣回

    private void granted(Long delta, Long back)
    {
        when(db.one(contains("from kg_point_entry where biz_type = :t"), argThat(x -> x != null && "stay_spend".equals(m(x).get("t")))))
                .thenReturn(delta == null ? null : p("delta", delta));
        when(db.one(contains("from kg_point_entry where biz_type = :t"), argThat(x -> x != null && "stay_spend_back".equals(m(x).get("t")))))
                .thenReturn(back == null ? null : p("delta", back));
    }

    @Test
    void refundAfterCompletionClawsBackTheDifference()
    {
        order("completed");
        granted(500L, null);
        pay(50000, 20000, "part_refunded");
        rewards.stayRefunded(9L);
        verify(db).update(contains(BACK_SQL), argThat(x -> Integer.valueOf(-200).equals(m(x).get("d")) && "stay_spend_back".equals(m(x).get("t"))
                && Long.valueOf(9).equals(m(x).get("b")) && "入住订单退款扣回 订单号S2610170001".equals(m(x).get("r"))));
    }

    @Test
    void repeatedRefundCallbackDoesNotClawBackTwiceAndLaterRefundsOnlyAddTheRest()
    {
        order("completed");
        pay(50000, 20000, "part_refunded");
        granted(500L, -200L);
        assertEquals(200, rewards.reverseStay(9L));
        verify(db, never()).update(contains(BACK_SQL), anyMap());
        // 再退 300 元（退光了）：一共扣回 500，不会超过当初发的
        pay(50000, 50000, "refunded");
        assertEquals(500, rewards.reverseStay(9L));
        verify(db).update(contains(BACK_SQL), argThat(x -> Integer.valueOf(-500).equals(m(x).get("d"))));
    }

    @Test
    void refundWithoutGrantedPointsDoesNothing()
    {
        order("checked_in");
        granted(null, null);
        pay(50000, 20000, "part_refunded");
        assertEquals(0, rewards.reverseStay(9L));
        verify(db, never()).update(contains(BACK_SQL), anyMap());
    }

    @Test
    void refundWhileSpendIsPausedStillUsesOnePointPerYuan()
    {
        when(configs.selectConfigByKey(KgPointLedger.KEY_SPEND_PER_YUAN)).thenReturn("0");
        order("completed");
        granted(500L, null);
        pay(50000, 10000, "part_refunded");
        assertEquals(100, rewards.reverseStay(9L));
    }

    @Test
    void refundBackIsNotCountedAsSpentAndLowersEarned()
    {
        assertTrue(KgPointLedger.EARNED_SQL.contains("delta > 0 or biz_type in ('stay_spend_back'"), KgPointLedger.EARNED_SQL);
        when(db.one(contains(" earned, "), anyMap())).thenReturn(p("earned", 300L, "spent", 50L));
        KgPointLedger ledger = (KgPointLedger) ReflectionTestUtils.getField(rewards, "ledger");
        ledger.totals(7L);
        verify(db).one(argThat(sql -> sql != null && sql.contains("(" + KgPointLedger.EARNED_EXPR + ")-delta") && sql.contains(KgPointLedger.EARNED_EXPR)), anyMap());
        assertEquals("入住消费", KgPointLedger.KINDS.get("stay_spend"));
        assertEquals("退款扣回", KgPointLedger.KINDS.get("stay_spend_back"));
        assertEquals("发布攻略", KgPointLedger.KINDS.get("guide"));
    }

    // ---------------------------------------------------------------- 攻略获得

    private void post(String kind, String status, String delFlag)
    {
        when(db.one(contains(POST_SQL), anyMap())).thenReturn(p("postId", 11L, "appUserId", 7L, "kind", kind, "title", " 平潭三天怎么玩 ", "status", status, "delFlag", delFlag));
    }

    @Test
    void confirmedFalsePostCannotReceiveGuideRewardsAfterBeingRestored()
    {
        post("daily", "online", "0");
        when(db.one(contains("from kg_badge_violation"), eq(p("u", 7L, "p", 11L)))).thenReturn(p("violationId", 91L));
        assertEquals(0, rewards.grantGuide(11L));
        verify(db, never()).update(startsWith(AWARD_SQL), anyMap());
    }

    @Test
    void approvedGuideEarnsTwentyWithAReadableTitle()
    {
        post("daily", "online", "0");
        rewards.postOnline(11L);
        verify(db).update(startsWith(AWARD_SQL), argThat(x -> Integer.valueOf(20).equals(m(x).get("d")) && "guide".equals(m(x).get("t"))
                && Long.valueOf(11).equals(m(x).get("b")) && "攻略审核通过：平潭三天怎么玩".equals(m(x).get("r"))));
    }

    @Test
    void onlyOnlineGuidePostsEarn()
    {
        post("buddy", "online", "0");
        assertEquals(0, rewards.grantGuide(11L));
        post("diary", "online", "0");
        assertEquals(0, rewards.grantGuide(11L));
        post("daily", "pending", "0");
        assertEquals(0, rewards.grantGuide(11L));
        post("daily", "online", "2");
        assertEquals(0, rewards.grantGuide(11L));
        verify(db, never()).update(startsWith(AWARD_SQL), anyMap());
    }

    @Test
    void guideIsPaidOncePerPost()
    {
        post("daily", "online", "0");
        when(db.count(contains("biz_id = :p"), anyMap())).thenReturn(1L);
        assertEquals(0, rewards.grantGuide(11L), "隐藏后又恢复、重复审核通过：这篇已经发过");
        verify(db, never()).update(startsWith(AWARD_SQL), anyMap());
    }

    @Test
    void dailyCapStopsFarmingAndComesFromSettings()
    {
        post("daily", "online", "0");
        when(db.count(contains("create_time >= curdate()"), anyMap())).thenReturn(3L);
        assertEquals(0, rewards.grantGuide(11L), "默认每天最多 3 篇");
        when(configs.selectConfigByKey(KgPointLedger.KEY_GUIDE_DAILY_CAP)).thenReturn("5");
        assertEquals(20, rewards.grantGuide(11L));
        when(db.count(contains("create_time >= curdate()"), anyMap())).thenReturn(5L);
        assertEquals(0, rewards.grantGuide(11L));
        // 填 0 不是「不限」：按默认 3 篇
        when(configs.selectConfigByKey(KgPointLedger.KEY_GUIDE_DAILY_CAP)).thenReturn("0");
        when(db.count(contains("create_time >= curdate()"), anyMap())).thenReturn(3L);
        assertEquals(0, rewards.grantGuide(11L));
        // 先锁作者账号行再数今天发了几篇
        verify(db, atLeastOnce()).one(contains("from kg_app_user where user_id = :u for update"), anyMap());
    }

    @Test
    void guideRewardFromSettingsAndZeroPauses()
    {
        post("daily", "online", "0");
        when(configs.selectConfigByKey(KgPointLedger.KEY_GUIDE_REWARD)).thenReturn("35");
        assertEquals(35, rewards.grantGuide(11L));
        when(configs.selectConfigByKey(KgPointLedger.KEY_GUIDE_REWARD)).thenReturn("0");
        clearInvocations(db);
        assertEquals(0, rewards.grantGuide(11L));
        verifyNoInteractions(db);
    }

    // ---------------------------------------------------------------- 事务提交后才发、出错不往外抛

    @Test
    void insideATransactionNothingHappensUntilCommit()
    {
        post("daily", "online", "0");
        TransactionSynchronizationManager.initSynchronization();
        rewards.postOnline(11L);
        verify(db, never()).one(anyString(), anyMap());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations())
        {
            s.afterCommit();
        }
        verify(db).update(startsWith(AWARD_SQL), anyMap());
    }

    @Test
    void rolledBackBusinessTransactionPaysNothing()
    {
        order("completed");
        pay(50000, 0, "paid");
        TransactionSynchronizationManager.initSynchronization();
        rewards.stayCompleted(9L);
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations())
        {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void failuresAreLoggedNotThrown()
    {
        when(db.one(contains(ORDER_SQL), anyMap())).thenThrow(new RuntimeException("lock wait timeout"));
        when(db.one(contains(POST_SQL), anyMap())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> rewards.stayCompleted(9L));
        assertDoesNotThrow(() -> rewards.stayRefunded(9L));
        assertDoesNotThrow(() -> rewards.postOnline(11L));
        assertDoesNotThrow(() -> rewards.stayCompleted(null));
    }
}
