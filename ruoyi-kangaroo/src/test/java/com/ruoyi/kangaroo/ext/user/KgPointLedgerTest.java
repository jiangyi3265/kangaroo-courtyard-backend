package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.system.service.ISysConfigService;

/** 积分账本：后台参数填错按默认值、三个数的口径、发积分只记一次。 */
class KgPointLedgerTest
{
    private final KgJdbc db = mock(KgJdbc.class);

    private final ISysConfigService configs = mock(ISysConfigService.class);

    private final KgPointLedger ledger = new KgPointLedger();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(ledger, "db", db);
        ReflectionTestUtils.setField(ledger, "configs", configs);
    }

    @Test
    void wrongOrMissingConfigFallsBackToDefaults()
    {
        assertEquals(10, ledger.volunteerPerDay(), "没配参数按默认 10");
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn(" 20 ");
        assertEquals(20, ledger.volunteerPerDay());
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn("0");
        assertEquals(0, ledger.volunteerPerDay(), "填 0 = 暂停换工积分，是合法值");
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn("十");
        assertEquals(10, ledger.volunteerPerDay());
        when(configs.selectConfigByKey(KgPointLedger.KEY_VOLUNTEER_PER_DAY)).thenReturn("100000");
        assertEquals(10, ledger.volunteerPerDay(), "多打几个 0 超出上限，按默认值，不发天量积分");
        when(configs.selectConfigByKey(KgPointLedger.KEY_URGENT_COST)).thenReturn("0");
        assertEquals(50, ledger.urgentCost(), "加急至少花 1 分，填 0 按默认 50");
        when(configs.selectConfigByKey(KgPointLedger.KEY_INVITE_REWARD)).thenReturn("-5");
        assertEquals(100, ledger.inviteReward());
        when(configs.selectConfigByKey(KgPointLedger.KEY_INVITE_REWARD)).thenThrow(new RuntimeException("redis down"));
        assertEquals(100, ledger.inviteReward(), "参数服务出错也按默认值，不变成 500");
    }

    @Test
    void totalsSplitEarnedSpentAndBalance()
    {
        when(db.one(contains(" earned, "), eq(p("u", 7L)))).thenReturn(p("earned", 350L, "spent", 300L));
        KgPointLedger.Totals t = ledger.totals(7L);
        assertEquals(350L, t.earned);
        assertEquals(300L, t.spent);
        assertEquals(50L, t.balance());
    }

    @Test
    @SuppressWarnings("unchecked")
    void awardIsInsertIgnoreOnTheBizKeyAndSkipsZero()
    {
        assertFalse(ledger.award(7L, 0, "暂停", "volunteer", 9L));
        verify(db, never()).update(anyString(), anyMap());
        when(db.update(startsWith("insert ignore into kg_point_entry"), anyMap())).thenReturn(1, 0);
        assertTrue(ledger.award(7L, 30, "换工换宿完成", "volunteer", 9L));
        assertFalse(ledger.award(7L, 30, "换工换宿完成", "volunteer", 9L), "同一来源第二次不再记");
        verify(db, times(2)).update(startsWith("insert ignore into kg_point_entry"),
                argThat(m -> Integer.valueOf(30).equals(((Map<String, Object>) m).get("d")) && "volunteer".equals(((Map<String, Object>) m).get("t"))
                        && Long.valueOf(9).equals(((Map<String, Object>) m).get("b"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void spendWritesANegativeEntry()
    {
        ledger.spend(7L, 50, "加急投递：前台", "urgent", 9L);
        verify(db).update(startsWith("insert into kg_point_entry"), argThat(m -> Integer.valueOf(-50).equals(((Map<String, Object>) m).get("d"))
                && "urgent".equals(((Map<String, Object>) m).get("t"))));
    }
}
