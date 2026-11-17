package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgBusinessBillTest {
    @Test void ratesAndRounding() {
        assertEquals(20000, KgSettlementMath.sale(20000));
        assertEquals(30000, KgSettlementMath.fee(300000, 1000));
        assertEquals(2000, KgSettlementMath.fee(20000, 1000));
        assertEquals(110, KgSettlementMath.sale(110));
        assertThrows(IllegalArgumentException.class, () -> KgSettlementMath.sale(-1));
        assertThrows(ServiceException.class, () -> KgBusinessBillService.amount("100.5"));
        assertThrows(ServiceException.class, () -> KgBusinessBillService.amount("-1"));
    }

    @Test void nightlyPricesUseCalendarOverridesAndMultiplyRoundedUnits() {
        KgJdbc db = mock(KgJdbc.class);
        KgStayBrowseService browse = new KgStayBrowseService();
        ReflectionTestUtils.setField(browse, "db", db);
        LocalDate start = LocalDate.now().plusDays(1);
        when(db.list(contains("kg_stay_calendar"), anyMap())).thenReturn(Collections.singletonList(p("stayDate", start.toString(), "priceFen", 110L)));
        Map<String, Object> room = p("roomTypeId", 1L, "basePriceFen", 20000L, "stock", 5, "unitLabel", "床位", "capacity", 1);
        Map<String, Object> result = browse.availability(room, start, start.plusDays(2), 2, null);
        assertEquals(40220L, result.get("merchantBaseFen"));
        assertEquals(40220L, result.get("roomFen"));
        assertEquals(110L, result.get("minNightFen"));
    }

    @Test void fulltimeDuplicateReturnsSameBillAndRejectsDifferentWage() {
        KgJdbc db = mock(KgJdbc.class);
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service, "db", db);
        when(db.must(contains("kg_application"), anyMap(), anyString())).thenReturn(p("status", "onboarded", "jobType", "fulltime", "storeId", 1L));
        when(db.one(contains("source_key"), anyMap())).thenReturn(p("billId", 9L, "baseFen", 300000L, "basis", "确认首月底薪"));
        assertEquals(9L, service.recordWork(p("appId", 2L, "baseFen", 300000L, "basis", "确认首月底薪"), 1L, "merchant:1"));
        assertThrows(ServiceException.class, () -> service.recordWork(p("appId", 2L, "baseFen", 400000L, "basis", "确认首月底薪"), 1L, "merchant:1"));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void parttimeRejectsFutureAndBeforeOnboarding() {
        KgJdbc db = mock(KgJdbc.class);
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service, "db", db);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        when(db.must(contains("kg_application"), anyMap(), anyString())).thenReturn(p("status", "onboarded", "jobType", "parttime", "onboardTime", today.toString()));
        for (LocalDate day : new LocalDate[] { today.minusDays(1), today.plusDays(1) }) {
            assertThrows(ServiceException.class, () -> service.recordWork(p("appId", 2L, "workDate", day.toString(), "baseFen", 20000, "basis", "当日工资"), 1L, "merchant:1"));
        }
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void checkoutIsRequiredAndUnchangedNormalOrderKeepsFivePercent() {
        KgJdbc db = mock(KgJdbc.class);
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(contains("kg_pay_refund"), anyMap())).thenReturn(null);
        when(db.one(contains("kg_business_withdrawal"), anyMap())).thenReturn(null);
        Map<String, Object> bill = p("billId", 1L, "kind", "stay", "sourceId", 9L, "version", 0, "baseFen", 20000L, "feeFen", 1000L);
        Map<String, Object> order = p("orderId", 9L, "status", "checked_in", "payNo", "test");
        when(db.must(contains("kg_business_bill"), anyMap(), anyString())).thenReturn(bill);
        when(db.must(contains("kg_stay_order"), anyMap(), anyString())).thenReturn(order);
        when(db.one(contains("kg_pay_order"), anyMap())).thenReturn(p("status", "paid", "amount", 21000L, "refundAmount", 0L));
        assertThrows(ServiceException.class, () -> service.command(1L, "approve", p("version", 0, "targetFen", 20000L, "note", "核对"), "admin:test"));
        order.put("status", "completed");
        assertThrows(ServiceException.class, () -> service.command(1L, "approve", p("version", 0, "targetFen", 21000L, "note", "核对"), "admin:test"));
        service.command(1L, "approve", p("version", 0, "targetFen", 20000L, "note", "核对"), "admin:test");
        verify(db).update(contains("target_fen=:target"), argThat(params -> Long.valueOf(20000L).equals(params.get("target"))));
    }

    @Test void refundChangeBlocksPayoutAndVersionBlocksReplay() {
        KgJdbc db = mock(KgJdbc.class);
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(contains("kg_pay_refund"), anyMap())).thenReturn(null);
        when(db.one(contains("kg_business_withdrawal"), anyMap())).thenReturn(null);
        Map<String, Object> bill = p("billId", 1L, "kind", "stay", "sourceId", 9L, "version", 1, "status", "approved", "targetFen", 20000L, "sourceNetFen", 21000L);
        when(db.must(contains("kg_business_bill"), anyMap(), anyString())).thenReturn(bill);
        when(db.must(contains("kg_stay_order"), anyMap(), anyString())).thenReturn(p("status", "completed", "payNo", "test"));
        when(db.one(contains("kg_pay_order"), anyMap())).thenReturn(p("status", "part_refunded", "amount", 21000L, "refundAmount", 1000L));
        assertThrows(ServiceException.class, () -> service.command(1L, "receipt", p("version", 1, "amountFen", 20000L, "note", "付款", "proof", "test"), "admin:test"));
        assertThrows(ServiceException.class, () -> service.command(1L, "approve", p("version", 0, "targetFen", 19000L, "note", "重放"), "admin:test"));
        verify(db, never()).insert(anyString(), anyMap());
    }

    /** 2026-11-09 主人拍板：结算价' = round(结算价 × 实付 ÷ (结算价 + 服务费))，服务费' = 实付 − 结算价' */
    @Test void discountShareFormulaRoundsHalfUp() {
        assertEquals(136800L, KgSettlementMath.shareBase(152000, 7600, 143640));
        assertEquals(18101L, KgSettlementMath.shareBase(20111, 1006, 19006));
        assertEquals(20000L, KgSettlementMath.shareBase(20000, 1000, 21000));
        assertEquals(0L, KgSettlementMath.shareBase(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> KgSettlementMath.shareBase(-1, 0, 0));
    }

    @Test @SuppressWarnings("unchecked") void longStayDiscountIsSharedWhenTheBillIsCreated() {
        KgJdbc db = mock(KgJdbc.class);
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service, "db", db);
        Map<String, Object> stay = p("storeId", 1L, "stayName", "平潭小院", "typeName", "大床房");
        // S2611100439586417 的情形：结算价 1520 + 服务费 76，满 7 晚 9 折，实付 1436.40
        service.captureStay(9L, p("stay", stay, "merchantBaseFen", 152000L, "platformMarkupFen", 7600L, "discountFen", 15960L,
                "discountLabel", "长住优惠（满7晚9折）", "payFen", 143640L, "addonFen", 0, "serviceFeeFen", 0L));
        // 早鸟：按结算价定价，实付 = 结算价 + 5%，不再打折
        service.captureStay(10L, p("stay", stay, "merchantBaseFen", 20000L, "platformMarkupFen", 1000L, "discountFen", 0L, "payFen", 21000L, "promoId", 3L));
        // 会员底价：没有约定谁承担，照旧不分摊、由平台核对
        service.captureStay(11L, p("stay", stay, "merchantBaseFen", 20000L, "platformMarkupFen", 1000L, "discountFen", 5000L, "memberMode", "floor", "payFen", 16000L));
        ArgumentCaptor<Map<String, Object>> rows = ArgumentCaptor.forClass(Map.class);
        verify(db, times(3)).insert(eq("kg_business_bill"), rows.capture());
        Map<String, Object> longStay = rows.getAllValues().get(0);
        assertEquals(136800L, longStay.get("base_fen"));
        assertEquals(6840L, longStay.get("fee_fen"));
        assertTrue(String.valueOf(longStay.get("basis")).contains("原结算价 ¥1520 + 平台服务费 ¥76"), String.valueOf(longStay.get("basis")));
        assertEquals(20000L, rows.getAllValues().get(1).get("base_fen"));
        assertEquals(1000L, rows.getAllValues().get(1).get("fee_fen"));
        assertNull(rows.getAllValues().get(1).get("basis"));
        assertEquals(20000L, rows.getAllValues().get(2).get("base_fen"));
        assertNull(rows.getAllValues().get(2).get("basis"));
    }

    private static KgJdbc paidStay(long baseFen, long feeFen, long paidFen, long refundFen) {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(contains("kg_pay_refund"), anyMap())).thenReturn(null);
        when(db.one(contains("kg_business_withdrawal"), anyMap())).thenReturn(null);
        when(db.must(contains("kg_business_bill"), anyMap(), anyString())).thenAnswer(inv -> p("billId", 1L, "kind", "stay", "sourceId", 9L, "version", 0,
                "status", "draft", "baseFen", baseFen, "feeFen", feeFen));
        when(db.must(contains("kg_stay_order"), anyMap(), anyString())).thenAnswer(inv -> p("orderId", 9L, "orderNo", "S2611100439586417", "status", "completed",
                "payNo", "test", "payFen", paidFen, "discountFen", 15960L, "discountLabel", "长住优惠（满7晚9折）"));
        when(db.one(contains("kg_pay_order"), anyMap())).thenReturn(p("status", refundFen > 0 ? "part_refunded" : "paid", "amount", paidFen, "refundAmount", refundFen));
        return db;
    }

    @Test void sharedDiscountBillNeedsNoPlatformReviewButRefundStillDoes() {
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service, "db", paidStay(136800L, 6840L, 143640L, 0L));
        Map<String, Object> bill = service.detail(1L, 1L);
        assertEquals(136800L, bill.get("withdrawableFen"));
        assertEquals(Boolean.TRUE, bill.get("discountShared"));
        assertEquals(6840L, bill.get("platformNetFen"));
        // 有退款：照旧要平台核对
        ReflectionTestUtils.setField(service, "db", paidStay(136800L, 6840L, 143640L, 20520L));
        assertEquals(0L, service.detail(1L, 1L).get("withdrawableFen"));
        // 分摊规则上线前的老账单：不改数据、仍要核对，后台给按比例算的建议金额
        ReflectionTestUtils.setField(service, "db", paidStay(152000L, 7600L, 143640L, 0L));
        Map<String, Object> legacy = service.detail(1L, 1L);
        assertEquals(0L, legacy.get("withdrawableFen"));
        assertEquals(Boolean.FALSE, legacy.get("discountShared"));
        assertEquals(136800L, legacy.get("suggestTargetFen"));
    }

    @Test void approvingSharedBillKeepsTheSharedSettlementPrice() {
        KgJdbc db = paidStay(136800L, 6840L, 143640L, 0L);
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service, "db", db);
        assertThrows(ServiceException.class, () -> service.command(1L, "approve", p("version", 0, "targetFen", 143640L, "note", "核对"), "admin:test"));
        service.command(1L, "approve", p("version", 0, "targetFen", 136800L, "note", "核对"), "admin:test");
        verify(db).update(contains("target_fen=:target"), argThat(params -> Long.valueOf(136800L).equals(params.get("target"))));
    }
}
