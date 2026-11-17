package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/** 住客群码到期提醒（2026-10-20）：到期前一天提醒主理人 / 门店管家换码，同一个有效期只提醒一次，过期还没换再提醒一次 */
class KgStayGroupQrReminderTest
{
    static final LocalDate D = LocalDate.of(2026, 10, 20);
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgStayGroupQrReminder reminder = new KgStayGroupQrReminder();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(reminder, "db", db);
        ReflectionTestUtils.setField(reminder, "support", support);
    }

    private static Map<String, Object> stay(long id, String name, long storeId, LocalDate expire)
    {
        return new HashMap<String, Object>(p("stayId", id, "stayName", name, "storeId", storeId, "groupQrExpire", expire.toString()));
    }

    private void stays(Map<String, Object>... rows)
    {
        when(db.list(contains("from kg_stay s join kg_store st"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(rows)));
    }

    private void managers(Long... ids)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Long id : ids)
        {
            out.add(new HashMap<String, Object>(p("appUserId", id)));
        }
        when(db.list(contains("from kg_staff_grant g"), anyMap())).thenReturn(out);
    }

    @Test
    @SuppressWarnings("unchecked")
    void remindsManagersAndKeepersTheDayBeforeExpiry()
    {
        stays(stay(6L, "平潭海边小院", 102L, D.plusDays(1)));
        managers(1012L, 1013L);
        when(db.count(contains("from kg_message"), anyMap())).thenReturn(0L);
        assertEquals(2, reminder.run(D));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(support).notifyStaff(eq(1012L), eq("system"), eq("住客群码明天到期"), text.capture(), eq("stay_group_qr"), eq(6L), eq("/pages/staff/rooms"));
        verify(support).notifyStaff(eq(1013L), eq("system"), eq("住客群码明天到期"), anyString(), eq("stay_group_qr"), eq(6L), eq("/pages/staff/rooms"));
        assertTrue(text.getValue().startsWith("「平潭海边小院」的住客群二维码明天（10月21日）到期"), text.getValue());
        assertTrue(text.getValue().contains("「更多」→「住客群码」"), "告诉员工去哪换");
        assertTrue(text.getValue().endsWith("（当前群码有效期至 2026-10-21）"));
        // 查哪些小院：已上线、门店在用、有群码和有效期；到期日在「过期 3 天内 ~ 明天」之间
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).list(sql.capture(), ps.capture());
        int i = 0;
        while (!sql.getAllValues().get(i).contains("from kg_stay s join kg_store st")) i++;
        String q = sql.getAllValues().get(i);
        assertTrue(q.contains("s.status = 'online'") && q.contains("st.status = '0'") && q.contains("coalesce(s.group_qr, '') <> ''") && q.contains("s.group_qr_expire is not null"));
        assertEquals("2026-10-17", ps.getAllValues().get(i).get("from"));
        assertEquals("2026-10-21", ps.getAllValues().get(i).get("to"));
        // 收提醒的只有主理人、门店管家，且是这家店的有效授权
        int j = 0;
        while (!sql.getAllValues().get(j).contains("from kg_staff_grant g")) j++;
        assertTrue(sql.getAllValues().get(j).contains("g.status = '0'") && sql.getAllValues().get(j).contains("g.del_flag = '0'"));
        assertEquals(Arrays.asList("manager", "keeper"), ps.getAllValues().get(j).get("roles"));
        assertEquals(102L, ps.getAllValues().get(j).get("st"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sameExpiryIsRemindedOnlyOnce()
    {
        stays(stay(6L, "平潭海边小院", 102L, D.plusDays(1)));
        managers(1012L);
        when(db.count(contains("from kg_message"), anyMap())).thenReturn(1L);
        assertEquals(0, reminder.run(D));
        verify(support, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).count(contains("biz_type = :b and biz_id = :s and mode = 'staff' and title in (:t) and content like :m"), ps.capture());
        assertEquals("stay_group_qr", ps.getValue().get("b"));
        assertEquals(6L, ps.getValue().get("s"));
        assertEquals(Arrays.asList("住客群码明天到期", "住客群码今天到期"), ps.getValue().get("t"), "明天 / 今天到期算同一次提醒");
        assertEquals("%有效期至 2026-10-21%", ps.getValue().get("m"));
    }

    @Test
    void missedMorningRunCatchesUpOnTheExpiryDay()
    {
        stays(stay(7L, "大理古城院", 103L, D));
        managers(2001L);
        when(db.count(contains("from kg_message"), anyMap())).thenReturn(0L);
        assertEquals(1, reminder.run(D));
        verify(support).notifyStaff(eq(2001L), eq("system"), eq("住客群码今天到期"),
                startsWith("「大理古城院」的住客群二维码今天（10月20日）到期"), eq("stay_group_qr"), eq(7L), eq("/pages/staff/rooms"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredAndNotReplacedIsRemindedOnceMore()
    {
        stays(stay(8L, "喜洲小院", 104L, D.minusDays(1)));
        managers(3001L);
        when(db.count(contains("from kg_message"), anyMap())).thenReturn(0L);
        assertEquals(1, reminder.run(D));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(support).notifyStaff(eq(3001L), eq("system"), eq("住客群码已过期"), text.capture(), eq("stay_group_qr"), eq(8L), eq("/pages/staff/rooms"));
        assertTrue(text.getValue().startsWith("「喜洲小院」的住客群二维码已在 10月19日 过期，客人现在看不到群码、进不了群。"), text.getValue());
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).count(anyString(), ps.capture());
        assertEquals(Collections.singletonList("住客群码已过期"), ps.getValue().get("t"), "「已过期」和「明天到期」分开算，各提醒一次");
    }

    @Test
    void noManagerOrKeeperMeansNoMessage()
    {
        stays(stay(9L, "没人管的小院", 105L, D.plusDays(1)));
        managers();
        when(db.count(contains("from kg_message"), anyMap())).thenReturn(0L);
        assertEquals(0, reminder.run(D));
        verify(support, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void aFailingRunDoesNotThrowOutOfTheScheduler()
    {
        when(db.list(anyString(), anyMap())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> reminder.daily());
    }
}
