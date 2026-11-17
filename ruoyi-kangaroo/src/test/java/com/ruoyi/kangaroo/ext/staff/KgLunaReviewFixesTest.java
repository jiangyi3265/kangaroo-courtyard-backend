package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 复查发现的几处（2026-10-30）：
 * 关掉兼任后系统补的授权要收回、以后还能复活；改同事活动的人不能自己审；城市负责人的消息也要遮手机号；开关关着时不发活动提醒。
 */
class KgLunaReviewFixesTest
{
    private static final long LUNA = 1005L;
    private static final long MERCHANT = 1000L;
    private static final long LEAD = 1003L;
    private static final long MANAGER = 1001L;
    private static final long HOME_STORE = 102L;
    private static final long MERCHANT_STORE = 201L;
    private static final String OFF = KgMerchantRequestService.REVOKED_NOT_SERVICE;
    private static final String AUTO = KgMerchantRequestService.GRANTED_BY;

    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
    private final AppTokenService tokens = mock(AppTokenService.class);
    private final KgActivityService activities = mock(KgActivityService.class);
    private final ISysConfigService configs = mock(ISysConfigService.class);
    private final KgMerchantRequestService service = new KgMerchantRequestService();

    @AfterEach
    void backToLegacy()
    {
        RolePhase.set(RolePhase.LEGACY);
    }

    @BeforeEach
    void setup()
    {
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "grants", grants);
        ReflectionTestUtils.setField(service, "tokens", tokens);
        ReflectionTestUtils.setField(service, "activities", activities);
        ReflectionTestUtils.setField(service, "configs", configs);
        when(cities.jobCity(anyString())).thenAnswer(i -> {
            String n = i.getArgument(0);
            return n.endsWith("市") ? n.substring(0, n.length() - 1) : n;
        });
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(p("city", "大理市"));
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(null);
        when(db.update(anyString(), anyMap())).thenReturn(1);
    }

    private static StaffSupport.Ctx ctx(long store, long uid, String role)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        AppLoginUser me = new AppLoginUser();
        me.setUserId(uid);
        me.setNickname("用户" + uid);
        c.me = me;
        c.storeId = store;
        c.storeName = "门店" + store;
        c.role = role;
        return c;
    }

    private int code(org.junit.jupiter.api.function.Executable e)
    {
        return assertThrows(ServiceException.class, e).getCode();
    }

    // ------------------------------------------------------------------ 收回系统补的授权

    private void onHeldRows(Map<String, Object>... rows)
    {
        when(db.list(contains("g.also_service = '1'"), anyMap())).thenReturn(Arrays.asList(rows));
    }

    private static Map<String, Object> held(long grantId, String role, String by, String city)
    {
        return p("grantId", grantId, "roleCode", role, "grantedBy", by, "city", city, "storeStatus", "0", "storeDel", "0");
    }

    private void verifyReleased(long grantId)
    {
        verify(db).update(contains("update kg_staff_grant set status = '1'"), argThat(m -> Long.valueOf(grantId).equals(m.get("g")) && OFF.equals(m.get("off")) && AUTO.equals(m.get("auto"))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void afterTheSwitchIsOffSystemRowsGoBack()
    {
        // 兼任关了：查出来只剩系统补的那一行
        onHeldRows(held(70L, StaffRole.MERCHANT_SERVICE, AUTO, "大理"));
        assertEquals(1, service.releaseServiceGrants(LUNA));
        verifyReleased(70L);
        verify(db).list(anyString(), argThat(m -> Long.valueOf(LUNA).equals(m.get("u"))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void stillServiceInThatCityKeepsTheRows()
    {
        // 另一家小院还开着兼任（同城），或者平台手工开过（同城）：这个城市他还是商家服务人
        onHeldRows(held(70L, StaffRole.MERCHANT_SERVICE, AUTO, "大理"), held(60L, StaffRole.KEEPER, "admin", "大理市"));
        assertEquals(0, service.releaseServiceGrants(LUNA));
        onHeldRows(held(70L, StaffRole.MERCHANT_SERVICE, AUTO, "大理"), held(61L, StaffRole.MERCHANT_SERVICE, "admin", "大理"));
        assertEquals(0, service.releaseServiceGrants(LUNA));
        verify(db, never()).update(contains("update kg_staff_grant"), anyMap());
    }

    @SuppressWarnings("unchecked")
    @Test
    void cityByCityOnlyTheOrphanedOnesGo()
    {
        onHeldRows(held(70L, StaffRole.MERCHANT_SERVICE, AUTO, "大理"), held(71L, StaffRole.MERCHANT_SERVICE, AUTO, "丽江"), held(60L, StaffRole.KEEPER, "admin", "丽江"));
        assertEquals(1, service.releaseServiceGrants(LUNA));
        verifyReleased(70L);
        verify(db, never()).update(contains("update kg_staff_grant"), argThat(m -> Long.valueOf(71L).equals(m.get("g"))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aDisabledStoreIsNotASourceAndHandGrantsAreNeverTouched()
    {
        Map<String, Object> stopped = new HashMap<String, Object>(held(60L, StaffRole.KEEPER, "admin", "大理"));
        stopped.put("storeStatus", "1");
        onHeldRows(held(70L, StaffRole.MERCHANT_SERVICE, AUTO, "大理"), stopped);
        assertEquals(1, service.releaseServiceGrants(LUNA));
        verifyReleased(70L);

        KgJdbc other = mock(KgJdbc.class);
        ReflectionTestUtils.setField(service, "db", other);
        when(other.list(anyString(), anyMap())).thenReturn(Collections.singletonList(held(61L, StaffRole.MERCHANT_SERVICE, "admin", "大理")));
        assertEquals(0, service.releaseServiceGrants(LUNA));
        verify(other, never()).update(anyString(), anyMap());
        assertEquals(0, service.releaseServiceGrants(null));
    }

    private void onRequest(String status)
    {
        when(db.one(contains("from kg_merchant_request where request_id"), anyMap())).thenReturn(p("requestId", 500L, "requestType", "online_task", "storeId", MERCHANT_STORE,
                "merchantUserId", MERCHANT, "city", "大理", "title", "探店", "status", status));
    }

    private Map<String, Object> grantRow(String status, String role, String revokedBy)
    {
        return p("grantId", 70L, "status", status, "delFlag", "0", "roleCode", role, "alsoService", "0", "revokedBy", revokedBy);
    }

    @Test
    void turningItBackOnRevivesTheRowInsteadOfA403()
    {
        onRequest("forwarded");
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap()))
                .thenReturn(grantRow("1", StaffRole.MERCHANT_SERVICE, OFF), grantRow("1", StaffRole.MERCHANT_SERVICE, OFF), grantRow("0", StaffRole.MERCHANT_SERVICE, null));
        service.serviceAccept(ctx(HOME_STORE, LUNA, StaffRole.MERCHANT_SERVICE), 500L);
        verify(db).update(contains("update kg_staff_grant set status = '0', revoked_by = null"), argThat(m -> Long.valueOf(70L).equals(m.get("g")) && OFF.equals(m.get("off"))));
        verify(tokens).refreshGrants(eq(LUNA), any());
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
    }

    @Test
    void aPlatformRevocationIsStillABlock()
    {
        onRequest("forwarded");
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(grantRow("1", StaffRole.MERCHANT_SERVICE, "admin"));
        assertEquals(403, code(() -> service.serviceAccept(ctx(HOME_STORE, LUNA, StaffRole.MERCHANT_SERVICE), 500L)));
        verify(db, never()).update(contains("update kg_staff_grant"), anyMap());
        // 系统收回的行换成了别的角色（平台改过）也不认标记
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(grantRow("1", StaffRole.STAFF, OFF));
        assertEquals(403, code(() -> service.serviceAccept(ctx(HOME_STORE, LUNA, StaffRole.MERCHANT_SERVICE), 500L)));
    }

    @Test
    void theListsDoNotHideShopsHeOnlyLostByTheSwitch()
    {
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(new HashMap<String, Object>(p("rows", Collections.emptyList(), "total", 0L)));
        service.serviceList(ctx(HOME_STORE, LUNA, StaffRole.MERCHANT_SERVICE), null, null, 1, 10);
        verify(db).page(contains("bg.revoked_by = '" + OFF + "'"), anyMap(), any(), any());
    }

    // ------------------------------------------------------------------ 活动审核

    private void onActivity(String updateBy)
    {
        Map<String, Object> a = new HashMap<String, Object>(p("activityId", 7L, "source", "merchant", "storeId", MERCHANT_STORE, "appUserId", MERCHANT, "city", "大理", "title", "手作课", "status", "pending"));
        a.put("updateBy", updateBy);
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
    }

    @Test
    void whoLastSavedItCannotAuditIt()
    {
        StaffSupport.Ctx luna = ctx(MERCHANT_STORE, LUNA, StaffRole.MERCHANT_SERVICE);
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(p("status", "0", "delFlag", "0", "roleCode", StaffRole.KEEPER, "alsoService", "1"));
        onActivity("staff:" + LUNA);
        assertEquals(403, code(() -> service.serviceActivityAudit(luna, 7L, true, null)), "改了同事的活动，再自己审");
        assertEquals(403, code(() -> service.serviceActivityAudit(luna, 7L, false, "不合适")), "驳回也一样");
        verify(activities, never()).audit(any(), anyBoolean(), any(), any());

        onActivity("staff:" + MERCHANT);
        service.serviceActivityAudit(luna, 7L, true, null);
        verify(activities).audit(7L, true, null, "staff:" + LUNA);
        onActivity(null);
        service.serviceActivityAudit(luna, 7L, true, null);
        verify(activities, times(2)).audit(7L, true, null, "staff:" + LUNA);
    }

    @Test
    void theListAndBadgeUseTheSameRule()
    {
        when(db.list(contains("from kg_activity a"), anyMap())).thenReturn(Collections.emptyList());
        service.serviceActivities(ctx(HOME_STORE, LUNA, StaffRole.MERCHANT_SERVICE), null, 1, 10);
        verify(db).list(contains("(a.update_by is null or a.update_by <> :meBy)"), argThat(m -> ("staff:" + LUNA).equals(m.get("meBy"))));
    }

    @Test
    void noReminderBeforeTheSwitchIsOn()
    {
        when(db.list(contains("g.role_code = :r"), anyMap())).thenReturn(Collections.singletonList(p("appUserId", LUNA, "storeId", HOME_STORE, "city", "大理", "nickname", "Luna")));
        when(configs.selectConfigByKey(KgMerchantRequestService.ROUTING_KEY)).thenReturn("off");
        service.activityPending(7L, MERCHANT_STORE, "喜洲小院", "手作课", "大理", MERCHANT);
        verify(support, never()).notifyStaff(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());

        when(configs.selectConfigByKey(KgMerchantRequestService.ROUTING_KEY)).thenReturn("on");
        service.activityPending(7L, MERCHANT_STORE, "喜洲小院", "手作课", "大理", MERCHANT);
        verify(support).notifyStaff(eq(LUNA), anyString(), eq("有商家活动待审核"), anyString(), anyString(), eq(7L), anyString());
    }

    // ------------------------------------------------------------------ 城市负责人的消息

    @Test
    @SuppressWarnings("unchecked")
    void cityLeadsNeverSeeAPhoneNumberInTheMessage()
    {
        String phone = "13812345678";
        when(configs.selectConfigByKey(KgMerchantRequestService.ROUTING_KEY)).thenReturn("on");
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(p("storeId", MERCHANT_STORE, "storeName", "喜洲小院", "city", "大理"));
        when(db.one(contains("from kg_merchant_apply"), anyMap())).thenReturn(p("contactName", "阿杰", "phone", "13912345678"));
        when(db.insert(eq("kg_merchant_request"), anyMap())).thenReturn(500L);
        when(db.list(contains("g.role_code = :r"), anyMap())).thenAnswer(i -> {
            Map<String, Object> ps = i.getArgument(1);
            if (StaffRole.CITY_LEAD.equals(ps.get("r")))
            {
                return Collections.singletonList(p("appUserId", LEAD, "storeId", HOME_STORE, "city", "大理", "nickname", "负责人"));
            }
            return StaffRole.MANAGER.equals(ps.get("r"))
                    ? Collections.singletonList(p("appUserId", MANAGER, "storeId", HOME_STORE, "city", "大理", "nickname", "换工主理人"))
                    : Collections.emptyList();
        });
        Map<String, Object> form = new HashMap<String, Object>(p("requestType", "managed", "title", "代运营 联系我" + phone, "content", "需要代运营", "contactName", "阿杰", "contactPhone", "13912345678"));

        service.merchantSubmit(ctx(MERCHANT_STORE, MERCHANT, StaffRole.MERCHANT), form);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(support).notifyStaff(eq(LEAD), anyString(), eq("本城有新的商家需求"), text.capture(), eq("merchant_request"), eq(500L), anyString());
        assertFalse(text.getValue().contains(phone), text.getValue());
        assertTrue(text.getValue().contains("喜洲小院"), text.getValue());
        // 换工主理人要和商家联系，看得到原文（详情也是完整的）
        verify(support).notifyStaff(eq(MANAGER), anyString(), eq("有商家需求待核实"), contains(phone), anyString(), anyLong(), anyString());
    }
}
