package com.ruoyi.kangaroo.ext.community;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 商家活动进入待审核才提醒本城商家服务人；对商家不说「平台审核」，别的角色文案不变（不连库） */
class KgActivityServiceAuditNoticeTest
{
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String start = LocalDateTime.now().plusDays(3).withSecond(0).withNano(0).format(F);
    private final String end = LocalDateTime.now().plusDays(3).plusHours(2).withSecond(0).withNano(0).format(F);

    private final KgActivityService activities = new KgActivityService();
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    private final KgMerchantRequestService requests = mock(KgMerchantRequestService.class);

    @BeforeEach
    void setUp()
    {
        when(guard.initialStatus(any())).thenReturn("pending");
        when(guard.check(any())).thenReturn("pass");
        ReflectionTestUtils.setField(activities, "db", db);
        ReflectionTestUtils.setField(activities, "guard", guard);
        ReflectionTestUtils.setField(activities, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(activities, "requests", requests);
        Map<String, Object> store = new HashMap<String, Object>();
        store.put("storeName", "喜洲小院");
        store.put("city", "大理市");
        store.put("opType", "managed");
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(store);
        when(db.insert(eq("kg_activity"), anyMap())).thenReturn(5L);
    }

    private StaffSupport.Ctx ctx(String role)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.storeId = 30L;
        c.role = role;
        c.me = new AppLoginUser();
        c.me.setUserId(1000L);
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(30L);
        c.me.setStaffGrants(Collections.singletonList(g));
        return c;
    }

    private void stored(String source, String status, String city)
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", 5L);
        a.put("storeId", 30L);
        a.put("source", source);
        a.put("feeMode", "free");
        a.put("feeFen", 0L);
        a.put("status", status);
        a.put("signed", 0L);
        a.put("capacity", 10L);
        a.put("startTime", start);
        a.put("endTime", end);
        a.put("place", "门店前台");
        a.put("title", "手作课");
        a.put("city", city);
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
    }

    private Map<String, Object> body()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("title", "手作课");
        b.put("place", "门店前台");
        b.put("capacity", 10);
        b.put("startTime", start.substring(0, 16));
        b.put("endTime", end.substring(0, 16));
        b.put("feeYuan", "0");
        return b;
    }

    @Test
    void merchantCreatingAnActivityTellsServicePeopleAndSeesPlainWording()
    {
        stored("merchant", "pending", "大理市");
        Map<String, Object> out = activities.staffSave(ctx(StaffRole.MERCHANT), null, body());
        verify(requests).activityPending(5L, 30L, "喜洲小院", "手作课", "大理市", 1000L);
        assertEquals("已提交，待审核", out.get("message"));
    }

    @Test
    void otherRolesKeepTheirWording()
    {
        stored("merchant", "pending", "大理市");
        for (String role : new String[] { StaffRole.MANAGER, StaffRole.KEEPER, StaffRole.STAFF })
        {
            assertEquals("已提交平台审核", activities.staffSave(ctx(role), null, body()).get("message"), role);
        }
    }

    @Test
    void editingBackIntoPendingTellsThemWithTheStoredCity()
    {
        for (String from : new String[] { "rejected", "open" })
        {
            reset(requests);
            stored("merchant", from, "大理");
            activities.staffSave(ctx(StaffRole.MERCHANT), 5L, body());
            verify(requests).activityPending(5L, 30L, "喜洲小院", "手作课", "大理", 1000L);
        }
    }

    @Test
    void editingWhileAlreadyPendingDoesNotRepeat()
    {
        stored("merchant", "pending", "大理");
        activities.staffSave(ctx(StaffRole.MERCHANT), 5L, body());
        verify(requests, never()).activityPending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void yardActivitiesStayWithThePlatform()
    {
        stored("yard", "rejected", "大理");
        activities.staffSave(ctx(StaffRole.KEEPER), 5L, body());
        verify(requests, never()).activityPending(any(), any(), any(), any(), any(), any());
    }

    @Test
    void openInMockModeTellsNobody()
    {
        when(guard.initialStatus(any())).thenReturn("online");
        stored("merchant", "rejected", "大理");
        ReflectionTestUtils.setField(activities, "chats", mock(KgChatService.class));
        ReflectionTestUtils.setField(activities, "cities", mock(com.ruoyi.kangaroo.ext.user.KgCityService.class));
        try
        {
            activities.staffSave(ctx(StaffRole.MERCHANT), 5L, body());
        }
        catch (RuntimeException ignore)
        {
            // onOpened 要建群、发订阅提醒，这里不关心，只看有没有提醒商家服务人
        }
        verify(requests, never()).activityPending(any(), any(), any(), any(), any(), any());
    }
}
