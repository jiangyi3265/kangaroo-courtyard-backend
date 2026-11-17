package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 2026-11-03 主人：「必须7天，填写7天的价格页面优化一下不要弹窗」（V70 week_price_fen）、「消防改成选填，加上食品安全许可证」（不连库）
 */
class KgRoomWeekPriceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStayStaffService staff = new KgStayStaffService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(staff, "db", db);
        ReflectionTestUtils.setField(staff, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(staff, "files", mock(com.ruoyi.kangaroo.ext.core.KgPrivateFiles.class));
        ctx.storeId = 102L; ctx.storeName = "测试门店"; ctx.role = "manager";
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(102L); g.setRoleCode("manager"); g.setActive(true);
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setStaffGrants(Collections.singletonList(g));
    }

    @Test
    void parseAcceptsOnlySevenPositivePrices()
    {
        assertArrayEquals(new long[] { 19800, 19800, 19800, 19800, 19800, 26800, 26800 }, KgRoomWeekPrice.parse("19800,19800,19800,19800,19800,26800,26800"));
        assertNull(KgRoomWeekPrice.parse(null));
        assertNull(KgRoomWeekPrice.parse(""));
        assertNull(KgRoomWeekPrice.parse("100,200"), "不是 7 个就当没有，退回基础价");
        assertNull(KgRoomWeekPrice.parse("100,200,300,400,500,600,abc"));
        assertNull(KgRoomWeekPrice.parse("100,200,300,400,500,600,0"));
    }

    @Test
    void nightlyUsesWeekdayPriceThenBasePrice()
    {
        Map<String, Object> rt = row("basePriceFen", 19800L, "weekPriceFen", "19800,19900,20000,20100,20200,26800,27800");
        // 2026-11-02 是周一，2026-11-07 周六，2026-11-08 周日
        assertEquals(19800L, KgRoomWeekPrice.nightly(rt, LocalDate.of(2026, 11, 2)));
        assertEquals(20200L, KgRoomWeekPrice.nightly(rt, LocalDate.of(2026, 11, 6)));
        assertEquals(26800L, KgRoomWeekPrice.nightly(rt, LocalDate.of(2026, 11, 7)));
        assertEquals(27800L, KgRoomWeekPrice.nightly(rt, LocalDate.of(2026, 11, 8)));
        assertEquals(19800L, KgRoomWeekPrice.nightly(row("basePriceFen", 19800L), LocalDate.of(2026, 11, 8)), "老房型没有 7 天价：每天基础价");
        assertEquals(19800L, KgRoomWeekPrice.nightly(row("basePriceFen", 19800L, "weekPriceFen", null), LocalDate.of(2026, 11, 8)));
    }

    @Test
    void normalizeRequiresAllSevenDaysInRange()
    {
        assertEquals("100,200,300,400,500,600,700", KgRoomWeekPrice.normalize(Arrays.asList(100, 200L, "300", 400.0, 500, 600, 700)));
        assertEquals("100,200,300,400,500,600,700", KgRoomWeekPrice.normalize("100, 200,300,400,500,600,700"));
        assertEquals(100L, KgRoomWeekPrice.min("300,200,100,400,500,600,700"));
        assertThrows(ServiceException.class, () -> KgRoomWeekPrice.normalize(Arrays.asList(100, 200, 300)));
        assertThrows(ServiceException.class, () -> KgRoomWeekPrice.normalize(null));
        ServiceException e = assertThrows(ServiceException.class, () -> KgRoomWeekPrice.normalize(Arrays.asList(100, 200, 300, 400, 500, 600, "")));
        assertTrue(e.getMessage().contains("周日"), e.getMessage());
        assertThrows(ServiceException.class, () -> KgRoomWeekPrice.normalize(Arrays.asList(99, 200, 300, 400, 500, 600, 700)), "不到 1 元");
        assertThrows(ServiceException.class, () -> KgRoomWeekPrice.normalize(Arrays.asList(100, 200, 300, 400, 500, 600, 10000001L)), "超过 10 万元");
        assertThrows(ServiceException.class, () -> KgRoomWeekPrice.normalize(Arrays.asList(100, 200, 300, 400, 500, 600, "1.5")), "分只能是整数");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedCols()
    {
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_stay_room_type"), eq("room_type_id"), eq(7L), cols.capture());
        return cols.getValue();
    }

    private void draftRoom()
    {
        when(db.one(contains("from kg_stay_room_type where room_type_id"), anyMap())).thenReturn(row("roomTypeId", 7L, "stayId", 66L, "typeName", "大床房", "basePriceFen", 19800L));
        when(db.one(contains("from kg_stay where stay_id"), anyMap())).thenReturn(row("stayId", 66L, "storeId", 102L, "status", "draft", "stayName", "小院"));
    }

    @Test
    void staffSavesSevenDayPricesAndBaseBecomesTheLowest()
    {
        draftRoom();
        staff.saveRoomType(ctx, null, 7L, row("weekPriceFen", Arrays.asList(19800, 19800, 19800, 19800, 21800, 26800, 26800)));
        Map<String, Object> cols = savedCols();
        assertEquals("19800,19800,19800,19800,21800,26800,26800", cols.get("week_price_fen"));
        assertEquals(19800, ((Number) cols.get("base_price_fen")).intValue(), "基础价 = 7 天最低，列表「¥X 起」照旧读它");
    }

    @Test
    void oldClientsSendingOnlyBasePriceClearTheWeekPrices()
    {
        draftRoom();
        staff.saveRoomType(ctx, null, 7L, row("basePriceFen", 25800));
        Map<String, Object> cols = savedCols();
        assertTrue(cols.containsKey("week_price_fen"));
        assertNull(cols.get("week_price_fen"), "老版本只填一个价 = 每天一个价，不能被旧的 7 天价盖住");
        assertEquals(25800, ((Number) cols.get("base_price_fen")).intValue());
    }

    @Test
    void incompleteWeekIsRejectedBeforeAnyWrite()
    {
        draftRoom();
        assertThrows(ServiceException.class, () -> staff.saveRoomType(ctx, null, 7L, row("weekPriceFen", Arrays.asList(19800, 19800))));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void creatingWithSevenPricesNeedsNoSeparateBasePrice()
    {
        when(db.one(contains("from kg_stay where stay_id"), anyMap())).thenReturn(row("stayId", 66L, "storeId", 102L, "status", "draft", "stayName", "小院"));
        staff.saveRoomType(ctx, 66L, null, row("typeName", "庭院房", "roomKind", "private", "capacity", 2, "stock", 1,
                "weekPriceFen", Arrays.asList(30000, 30000, 30000, 30000, 30000, 36000, 36000)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_stay_room_type"), cols.capture());
        assertEquals("30000,30000,30000,30000,30000,36000,36000", cols.getValue().get("week_price_fen"));
        assertEquals(30000, ((Number) cols.getValue().get("base_price_fen")).intValue());
    }

    @Test
    void fireCertificateIsOptionalAndFoodLicenceIsANewOptionalUpload()
    {
        List<Map<String, Object>> quals = staff.quals(66L);
        assertEquals(Arrays.asList("license", "fire", "food", "special", "idcard"), quals.stream().map(q -> q.get("type")).collect(java.util.stream.Collectors.toList()));
        assertEquals(Boolean.FALSE, quals.get(1).get("required"), "消防安全检查合格证改为选填");
        assertEquals("食品安全许可证", quals.get(2).get("title"));
        assertEquals(Boolean.FALSE, quals.get(2).get("required"));
        assertEquals(Boolean.TRUE, quals.get(0).get("required"));
        assertEquals(Boolean.TRUE, quals.get(4).get("required"));
    }

    @Test
    void weeklyTotalReadsExistingWeekAndFallsBackToSevenBaseNights()
    {
        assertEquals(2800L, KgRoomWeekPrice.total(row("basePriceFen", 800, "weekPriceFen", "100,200,300,400,500,600,700")));
        assertEquals(11900L, KgRoomWeekPrice.total(row("basePriceFen", 1700)));
        assertEquals(11900L, KgRoomWeekPrice.total(row("basePriceFen", 1700, "weekPriceFen", "bad")));
        assertEquals(700000000L, KgRoomWeekPrice.total(row("basePriceFen", 100000000L)), "legacy high prices can still be read");
    }

    @Test
    void weeklyTotalDistributesEveryFenAndEveryStartingWeekdayHasSameSevenNightTotal()
    {
        for (long total : new long[] {700, 701, 10000, 10001, 11900, 70000000})
        {
            String week = KgRoomWeekPrice.splitTotal(total);
            long[] values = KgRoomWeekPrice.parse(week);
            assertEquals(total, Arrays.stream(values).sum());
            assertTrue(Arrays.stream(values).max().getAsLong() - Arrays.stream(values).min().getAsLong() <= 1);
            for (int start = 0; start < 7; start++)
            {
                long sum = 0;
                for (int night = 0; night < 7; night++) sum += KgRoomWeekPrice.nightly(row("weekPriceFen", week), LocalDate.of(2026, 11, 2).plusDays(start + night));
                assertEquals(total, sum, "Every consecutive seven nights must sum exactly");
            }
        }
        assertEquals("1429,1429,1429,1429,1429,1428,1428", KgRoomWeekPrice.splitTotal(10001));
    }

    @Test
    void weeklyTotalRejectsMissingFractionalNegativeAndOverflowAmounts()
    {
        for (Object value : new Object[] {null, "", " ", 0, -1, 699, 70000001, "1000.5", "NaN", "9223372036854775808"})
            assertThrows(ServiceException.class, () -> KgRoomWeekPrice.splitTotal(value), String.valueOf(value));
    }

    @Test
    void unchangedTotalPreservesLegacyWeekdayDistributionAndEmptyWeek()
    {
        Map<String,Object> cols = row("type_name", "renamed");
        KgRoomWeekPrice.applyTotal(cols, 2800, row("basePriceFen", 100, "weekPriceFen", "100,200,300,400,500,600,700"));
        assertEquals(row("type_name", "renamed"), cols);
        KgRoomWeekPrice.applyTotal(cols, 11900, row("basePriceFen", 1700));
        assertEquals(row("type_name", "renamed"), cols, "An unchanged old nightly price stays stored as before");
    }

    @Test
    void staffUpdatesUsingOnlySevenNightTotal()
    {
        draftRoom();
        staff.saveRoomType(ctx, null, 7L, row("weekTotalFen", 10001));
        Map<String,Object> cols = savedCols();
        assertEquals("1429,1429,1429,1429,1429,1428,1428", cols.get("week_price_fen"));
        assertEquals(1428L, ((Number) cols.get("base_price_fen")).longValue());
        assertFalse(cols.containsKey("week_total_fen"));
    }

    @Test
    void staffRejectsMixedNewAndLegacyPricingFieldsBeforeWriting()
    {
        draftRoom();
        for (String key : new String[] {"basePriceFen", "weekPriceFen"})
            assertThrows(ServiceException.class, () -> staff.saveRoomType(ctx, null, 7L, row("weekTotalFen", 10001, key, null)));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void staffEditingDescriptionWithoutPriceDoesNotChangeLegacyWeekPrices()
    {
        draftRoom();
        staff.saveRoomType(ctx, null, 7L, row("typeName", "新房名"));
        Map<String,Object> cols = savedCols();
        assertEquals("新房名", cols.get("type_name"));
        assertFalse(cols.containsKey("base_price_fen"));
        assertFalse(cols.containsKey("week_price_fen"));
    }

    @Test
    void staffUnchangedTotalDoesNotFlattenTheWeek()
    {
        draftRoom();
        when(db.one(contains("from kg_stay_room_type where room_type_id"), anyMap())).thenReturn(row("roomTypeId", 7L, "stayId", 66L,
            "typeName", "大床房", "basePriceFen", 100, "weekPriceFen", "100,200,300,400,500,600,700"));
        staff.saveRoomType(ctx, null, 7L, row("weekTotalFen", 2800, "typeName", "新房名"));
        Map<String,Object> cols = savedCols();
        assertFalse(cols.containsKey("base_price_fen"));
        assertFalse(cols.containsKey("week_price_fen"));
    }

    @Test
    void staffCanCreateWithOnlySevenNightTotal()
    {
        when(db.one(contains("from kg_stay where stay_id"), anyMap())).thenReturn(row("stayId", 66L, "storeId", 102L, "status", "draft", "stayName", "小院"));
        staff.saveRoomType(ctx, 66L, null, row("typeName", "庭院房", "roomKind", "private", "capacity", 2, "stock", 1, "weekTotalFen", 10001));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_stay_room_type"), cols.capture());
        assertEquals("1429,1429,1429,1429,1429,1428,1428", cols.getValue().get("week_price_fen"));
        assertFalse(cols.getValue().containsKey("week_total_fen"));
    }
}
