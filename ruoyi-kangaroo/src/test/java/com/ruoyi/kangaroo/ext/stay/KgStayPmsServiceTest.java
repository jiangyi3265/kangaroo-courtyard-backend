package com.ruoyi.kangaroo.ext.stay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 旅居订单台：排房数量 / 房型 / 停用 / 重叠校验，已入住换房从今天起，提前退房还房量，一键排房。 */
class KgStayPmsServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgStayPmsService service = new KgStayPmsService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "stayOrders", mock(KgStayOrderService.class));
        ctx.storeId = 102L; ctx.storeName = "平潭袋鼠小院"; ctx.role = "manager";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setNickname("黑子");
        when(db.list(contains("from kg_stay_assign a join kg_stay_unit u"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        // KgJdbc.one 查不到返回 null；Mockito 对 Map 默认返回空 Map，这里显式说明「默认没有冲突」
        when(db.one(contains("a.unit_id = :u"), anyMap())).thenReturn(null);
    }

    private Map<String, Object> order(String status, LocalDate in, LocalDate out, int units)
    {
        Map<String, Object> o = new HashMap<String, Object>(p("orderId", 9L, "orderNo", "S2610100001", "storeId", 102L, "stayId", 6L, "roomTypeId", 31L,
                "status", status, "checkIn", in.toString(), "checkOut", out.toString(), "nights", 2, "guests", units, "units", units,
                "guestName", "尚可凯", "guestPhone", "13800001234", "unitLabel", "床位", "typeName", "女生多人间", "checkoutTime", "12:00"));
        when(db.one(contains("from kg_stay_order o"), anyMap())).thenReturn(o);
        return o;
    }

    private void units(Map<String, Object>... us)
    {
        when(db.list(contains("from kg_stay_unit where unit_id in"), anyMap())).thenReturn(Arrays.asList(us));
    }

    private static Map<String, Object> unit(long id, String name, long type, String status)
    {
        return p("unitId", id, "unitName", name, "roomTypeId", type, "status", status, "delFlag", "0");
    }

    @Test
    void cannotPickMoreUnitsThanBooked()
    {
        order("confirmed", today.plusDays(1), today.plusDays(3), 1);
        ServiceException e = assertThrows(ServiceException.class, () -> service.assign(ctx, "S2610100001", Arrays.asList(1L, 2L)));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("最多选 1 个"));
    }

    @Test
    void unitMustBelongToTheOrdersRoomTypeAndBeEnabled()
    {
        order("confirmed", today.plusDays(1), today.plusDays(3), 1);
        units(unit(1L, "301", 99L, "0"));
        assertTrue(assertThrows(ServiceException.class, () -> service.assign(ctx, "S2610100001", Collections.singletonList(1L))).getMessage().contains("不属于"));
        units(unit(1L, "201-1号床", 31L, "1"));
        assertTrue(assertThrows(ServiceException.class, () -> service.assign(ctx, "S2610100001", Collections.singletonList(1L))).getMessage().contains("已停用"));
    }

    @Test
    void overlappingAssignmentIsRefusedWithWhoHoldsIt()
    {
        order("confirmed", today.plusDays(1), today.plusDays(3), 1);
        units(unit(1L, "201-1号床", 31L, "0"));
        when(db.one(contains("a.unit_id = :u"), anyMap())).thenReturn(p("startDate", today.plusDays(2).toString(), "endDate", today.plusDays(4).toString(),
                "orderNo", "S2610109999", "guestName", "王小明"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.assign(ctx, "S2610100001", Collections.singletonList(1L)));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().contains("201-1号床") && e.getMessage().contains("王*明") && e.getMessage().contains("9999"), e.getMessage());
        verify(db, never()).insert(eq("kg_stay_assign"), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void assignReplacesPreviousArrangementForTheWholeStay()
    {
        order("confirmed", today.plusDays(1), today.plusDays(3), 2);
        units(unit(1L, "201-1号床", 31L, "0"), unit(2L, "201-2号床", 31L, "0"));
        service.assign(ctx, "S2610100001", Arrays.asList(1L, 2L));
        ArgumentCaptor<Map<String, Object>> rows = ArgumentCaptor.forClass(Map.class);
        verify(db, times(2)).insert(eq("kg_stay_assign"), rows.capture());
        for (Map<String, Object> r : rows.getAllValues())
        {
            assertEquals(today.plusDays(1).toString(), r.get("start_date"));
            assertEquals(today.plusDays(3).toString(), r.get("end_date"));
            assertEquals("active", r.get("status"));
        }
        verify(db).update(contains("set status = 'released'"), argThat(m -> today.plusDays(1).toString().equals(((Map<String, Object>) m).get("f"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void moveDuringStayStartsTonightAndKeepsNightsAlreadySlept()
    {
        order("checked_in", today.minusDays(1), today.plusDays(2), 1);
        units(unit(2L, "201-2号床", 31L, "0"));
        service.assign(ctx, "S2610100001", Collections.singletonList(2L));
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_stay_assign"), row.capture());
        assertEquals(today.toString(), row.getValue().get("start_date"));
        verify(db).update(contains("set end_date = :f"), argThat(m -> today.toString().equals(((Map<String, Object>) m).get("f"))));
    }

    @Test
    void cancelledOrdersCannotBeArranged()
    {
        order("cancelled", today.plusDays(1), today.plusDays(3), 1);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.assign(ctx, "S2610100001", Collections.singletonList(1L))).getCode());
    }

    @Test
    void missingCountsUnitsCoveringTonightOrArrival()
    {
        Map<String, Object> o = order("confirmed", today.plusDays(1), today.plusDays(3), 2);
        List<Map<String, Object>> as = new ArrayList<Map<String, Object>>();
        assertEquals(2, service.missing(o, as));
        as.add(p("startDate", today.plusDays(1).toString(), "endDate", today.plusDays(3).toString(), "unitId", 1L));
        assertEquals(1, service.missing(o, as));
        as.add(p("startDate", today.plusDays(1).toString(), "endDate", today.plusDays(3).toString(), "unitId", 2L));
        assertEquals(0, service.missing(o, as));
    }

    @Test
    void checkInOnlyFromArrivalDay()
    {
        order("confirmed", today.plusDays(1), today.plusDays(3), 1);
        assertTrue(assertThrows(ServiceException.class, () -> service.checkIn(ctx, "S2610100001")).getMessage().contains("不在入住有效期内"));
        order("confirmed", today, today.plusDays(2), 1);
        when(db.update(contains("set status = 'checked_in'"), anyMap())).thenReturn(1);
        service.checkIn(ctx, "S2610100001");
        verify(db).update(contains("set status = 'checked_in'"), argThat(m -> String.valueOf(m.get("b")).contains("前台办理")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void earlyCheckOutGivesRemainingNightsBack()
    {
        order("checked_in", today.minusDays(1), today.plusDays(2), 2);
        when(db.update(contains("set status = 'completed'"), anyMap())).thenReturn(1);
        Map<String, Object> r = service.checkOut(ctx, "S2610100001");
        verify(db).update(contains("update kg_stay_calendar set occupied = greatest(0, occupied - :u)"), argThat(m -> {
            Map<String, Object> x = (Map<String, Object>) m;
            return Long.valueOf(2).equals(Long.valueOf(String.valueOf(x.get("u")))) && today.toString().equals(x.get("a")) && today.plusDays(2).toString().equals(x.get("b"));
        }));
        verify(db).update(contains("completed_orders = completed_orders + 1"), anyMap());
        assertTrue(String.valueOf(r.get("message")).contains("重新预订"));
    }

    @Test
    void checkOutOnDepartureDayKeepsInventory()
    {
        order("checked_in", today.minusDays(2), today, 1);
        when(db.update(contains("set status = 'completed'"), anyMap())).thenReturn(1);
        service.checkOut(ctx, "S2610100001");
        verify(db, never()).update(contains("update kg_stay_calendar"), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void autoAssignFillsFreeUnitsAndReportsShortage()
    {
        Map<String, Object> a = new HashMap<String, Object>(p("orderId", 1L, "orderNo", "S1", "storeId", 102L, "stayId", 6L, "roomTypeId", 31L, "status", "confirmed",
                "checkIn", today.plusDays(1).toString(), "checkOut", today.plusDays(2).toString(), "units", 2, "guestName", "甲乙丙", "typeName", "女生多人间"));
        Map<String, Object> b = new HashMap<String, Object>(p("orderId", 2L, "orderNo", "S2", "storeId", 102L, "stayId", 6L, "roomTypeId", 31L, "status", "confirmed",
                "checkIn", today.plusDays(1).toString(), "checkOut", today.plusDays(2).toString(), "units", 2, "guestName", "丁戊己", "typeName", "女生多人间"));
        when(db.list(contains("from kg_stay_order o left join kg_stay_room_type t"), anyMap())).thenReturn(Arrays.asList(a, b));
        when(db.list(contains("where room_type_id = :r and del_flag = '0' and status = '0'"), anyMap())).thenReturn(Arrays.asList(
                p("unitId", 11L, "unitName", "1号床"), p("unitId", 12L, "unitName", "2号床"), p("unitId", 13L, "unitName", "3号床")));
        // 第一单排进 1、2 号床后，第二单只剩 3 号床：1、2 号床查冲突时返回第一单
        when(db.one(contains("a.unit_id = :u"), anyMap())).thenAnswer(inv -> {
            Map<String, Object> m = (Map<String, Object>) inv.getArgument(1);
            Long unit = Long.valueOf(String.valueOf(m.get("u")));
            boolean taken = Long.valueOf(2L).equals(Long.valueOf(String.valueOf(m.get("o")))) && (unit == 11L || unit == 12L);
            return taken ? p("startDate", today.plusDays(1).toString(), "endDate", today.plusDays(2).toString(), "orderNo", "S1", "guestName", "甲乙丙") : null;
        });
        Map<String, Object> r = service.autoAssign(ctx, 6L, 30);
        assertEquals(1, r.get("assignedOrders"));
        assertEquals(2, r.get("assignedUnits"));
        List<String> failed = (List<String>) r.get("failed");
        assertEquals(1, failed.size());
        assertTrue(failed.get(0).contains("不够"), failed.get(0));
        verify(db, times(2)).insert(eq("kg_stay_assign"), anyMap());
    }

    @Test
    void addUnitsRejectsDuplicatesAndTrimsNames()
    {
        when(db.must(contains("from kg_stay_room_type t join kg_stay s"), anyMap(), anyString())).thenReturn(p("roomTypeId", 31L, "stayId", 6L, "typeName", "女生多人间"));
        when(db.list(contains("select unit_name from kg_stay_unit"), anyMap())).thenReturn(Collections.singletonList(p("unitName", "201-1号床")));
        when(db.must(contains("from kg_stay where stay_id"), anyMap(), anyString())).thenReturn(p("stayId", 6L));
        ServiceException dup = assertThrows(ServiceException.class, () -> service.addUnits(ctx, 31L, Arrays.asList(" 201-1号床 ")));
        assertEquals(Integer.valueOf(409), dup.getCode());
        service.addUnits(ctx, 31L, Arrays.asList("201-2号床", "201-2号床", " ", "201-3号床"));
        verify(db, times(2)).insert(eq("kg_stay_unit"), anyMap());
    }

    /** 2026-11-09 主人拍板：合作商家能设置自己门店的房间床位，但不因此拿到「房源·审核」（Vlog 审核等仍不给） */
    @Test
    void merchantCanSetUpUnitsWithoutRoomsAudit()
    {
        String phase = com.ruoyi.kangaroo.ext.staff.RolePhase.code();
        try
        {
            for (String p : Arrays.asList("legacy", "new"))
            {
                com.ruoyi.kangaroo.ext.staff.RolePhase.set(p);
                StaffSupport.Ctx c = new StaffSupport.Ctx();
                c.role = "merchant";
                assertTrue(KgStayPmsService.canSetupUnits(c), p);
                assertFalse(com.ruoyi.kangaroo.ext.staff.StaffPerms.allows("merchant", "rooms", "audit"), p);
                c.role = "keeper";
                assertTrue(KgStayPmsService.canSetupUnits(c), p);
                c.role = "online_staff";
                assertFalse(KgStayPmsService.canSetupUnits(c), p);
                c.role = "merchant_service";
                assertFalse(KgStayPmsService.canSetupUnits(c), p);
            }
        }
        finally
        {
            com.ruoyi.kangaroo.ext.staff.RolePhase.set(phase);
        }
        // 商家添加房间：不走「房源·审核」的拦截；房型仍按本店 storeId 查（别家门店的房型查不到）
        ctx.role = "merchant";
        when(db.must(contains("from kg_stay_room_type t join kg_stay s"), anyMap(), anyString())).thenReturn(p("roomTypeId", 31L, "stayId", 6L, "typeName", "女生多人间"));
        when(db.list(contains("select unit_name from kg_stay_unit"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.must(contains("from kg_stay where stay_id"), anyMap(), anyString())).thenReturn(p("stayId", 6L));
        service.addUnits(ctx, 31L, Arrays.asList("101"));
        verify(support, never()).need(any(), eq("rooms"), eq("audit"), anyString(), anyString());
        verify(db).must(contains("s.store_id = :st"), argThat(m -> Long.valueOf(102L).equals(m.get("st"))), anyString());
        verify(db).insert(eq("kg_stay_unit"), anyMap());
        // 线上任务专员：照旧按「房源·审核」把关
        ctx.role = "online_staff";
        service.removeUnit(ctx, 1L);
        verify(support).need(any(), eq("rooms"), eq("audit"), eq("stay_unit_edit"), eq("删除房间床位"));
    }

    @Test
    void overviewTellsMerchantItCanSetUpUnits()
    {
        ctx.role = "merchant";
        when(db.list(contains("from kg_stay s where s.store_id"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        assertEquals(Boolean.TRUE, service.overview(ctx, null).get("canSetup"));
    }
}
