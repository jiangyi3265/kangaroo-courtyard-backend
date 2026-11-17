package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 商家服务人审核（Luna 备忘录）：本城商家活动的列表、通过 / 驳回、提交时的提醒、线上任务退回。
 * 查的都是 Java 这一层的判断和拼好的 SQL，用 Mock 代替 JDBC。
 */
class KgMerchantServiceAuditTest
{
    private static final long DALI_STORE = 10L;
    private static final long MERCHANT_STORE = 30L;
    private static final long MERCHANT = 1000L;
    private static final long SERVICE = 1002L;
    private static final long OTHER_SERVICE = 1003L;
    private static final long LUNA = 1005L;
    private static final long LIJIANG_SERVICE = 2002L;

    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final KgActivityService activities = mock(KgActivityService.class);
    private final KgMerchantRequestService service = new KgMerchantRequestService();

    @BeforeEach
    void setup()
    {
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "activities", activities);
        when(cities.jobCity(anyString())).thenAnswer(i -> {
            String n = i.getArgument(0);
            return n.endsWith("市") ? n.substring(0, n.length() - 1) : n;
        });
        when(db.one(contains("select city from kg_store"), anyMap())).thenAnswer(i -> {
            Map<String, Object> ps = i.getArgument(1);
            return p("city", Long.valueOf(40L).equals(ps.get("s")) ? "" : "大理市");
        });
        when(db.one(contains("from kg_staff_grant where app_user_id"), anyMap())).thenReturn(null);
        when(db.update(contains("update kg_merchant_request set status"), anyMap())).thenReturn(1);
    }

    @AfterEach
    void clear()
    {
        RolePhase.set(RolePhase.LEGACY);
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.clearSynchronization();
        }
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

    private static StaffSupport.Ctx asService()
    {
        return ctx(DALI_STORE, SERVICE, StaffRole.MERCHANT_SERVICE);
    }

    private int code(org.junit.jupiter.api.function.Executable e)
    {
        return assertThrows(ServiceException.class, e).getCode();
    }

    private void onGrantRow(long uid, Map<String, Object> row)
    {
        when(db.one(contains("from kg_staff_grant where app_user_id"), argThat(m -> m != null && Long.valueOf(uid).equals(m.get("u"))))).thenReturn(row);
    }

    // ------------------------------------------------------------------ 活动列表

    private static Map<String, Object> pending(long id, String city)
    {
        return new HashMap<String, Object>(p("activityId", id, "title", "手作课" + id, "cover", "/img/a.jpg", "city", city, "place", "喜洲古镇", "feeFen", 5000L, "feeMode", "offline",
                "capacity", 10L, "intro", "介绍", "startTime", "2026-11-05 10:00:00", "storeId", MERCHANT_STORE, "storeName", "喜洲小院", "organizerName", "喜洲小院",
                "creator", "阿杰", "createTime", "2026-10-30 09:00:00", "status", "pending"));
    }

    @SafeVarargs
    private final void onPending(Map<String, Object>... rows)
    {
        when(db.list(contains("from kg_activity a"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(rows)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page)
    {
        return (List<Map<String, Object>>) page.get("rows");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listShowsThisCityAfterNormalisingWithEverythingNeededToReview()
    {
        onPending(pending(1, "大理市"), pending(2, "大理"), pending(3, "丽江"));
        Map<String, Object> out = service.serviceActivities(asService(), "pending", 1, 10);
        assertEquals(2L, out.get("total"));
        assertEquals("大理", out.get("city"));
        assertEquals(false, out.get("hasMore"));
        Map<String, Object> first = rows(out).get(0);
        assertEquals(1L, first.get("activityId"));
        for (String k : Arrays.asList("title", "cover", "startTime", "place", "feeFen", "priceText", "capacity", "intro", "storeName", "creator", "submitTime", "status", "statusLabel"))
        {
            assertNotNull(first.get(k), k);
        }
        assertEquals("2026-10-30 09:00:00", first.get("submitTime"), "没改过的算创建时间");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), ps.capture());
        assertTrue(sql.getValue().contains("a.status = 'pending' and a.source = 'merchant'"), sql.getValue());
        assertTrue(sql.getValue().contains("a.app_user_id <> :me"), "自己发起的不出现");
        assertTrue(sql.getValue().contains("bg.store_id = a.store_id"), "这家店不让他碰的不出现（同商家需求）");
        assertTrue(sql.getValue().contains("bg.also_service = '1'"), "在这家店兼任的不算另有角色");
        assertEquals(SERVICE, ps.getValue().get("me"));
    }

    @Test
    void resubmittedActivityShowsLastSaveAsSubmitTime()
    {
        Map<String, Object> a = pending(1, "大理");
        a.put("updateTime", "2026-10-30 12:00:00");
        onPending(a);
        assertEquals("2026-10-30 12:00:00", rows(service.serviceActivities(asService(), null, null, null)).get(0).get("submitTime"));
    }

    @Test
    void listPagesInJava()
    {
        onPending(pending(1, "大理"), pending(2, "大理"), pending(3, "大理"));
        Map<String, Object> one = service.serviceActivities(asService(), null, 1, 2);
        assertEquals(2, rows(one).size());
        assertEquals(true, one.get("hasMore"));
        assertEquals(3L, one.get("total"));
        Map<String, Object> two = service.serviceActivities(asService(), null, 2, 2);
        assertEquals(1, rows(two).size());
        assertEquals(3L, rows(two).get(0).get("activityId"));
        assertEquals(false, two.get("hasMore"));
        assertTrue(rows(service.serviceActivities(asService(), null, 9, 2)).isEmpty());
    }

    @Test
    void badStatusAndCitylessStoreAreRefused()
    {
        assertEquals(422, code(() -> service.serviceActivities(asService(), "open", 1, 10)));
        assertEquals(422, code(() -> service.serviceActivities(ctx(40L, SERVICE, StaffRole.MERCHANT_SERVICE), null, 1, 10)));
    }

    @Test
    void badgeUsesTheSameRulesForServiceAndDoubleRoleOnly()
    {
        onPending(pending(1, "大理市"), pending(2, "丽江"));
        assertEquals(1L, service.hubCounts(asService()).get("serviceAuditPending"));

        StaffSupport.Ctx dual = ctx(DALI_STORE, LUNA, StaffRole.KEEPER);
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(DALI_STORE);
        g.setRoleCode(StaffRole.KEEPER);
        g.setAlsoService(true);
        dual.me.setStaffGrants(Collections.singletonList(g));
        assertEquals(1L, service.hubCounts(dual).get("serviceAuditPending"));

        StaffSupport.Ctx plain = ctx(DALI_STORE, LUNA, StaffRole.KEEPER);
        plain.me.setStaffGrants(Collections.singletonList(new StaffGrantView()));
        assertFalse(service.hubCounts(plain).containsKey("serviceAuditPending"));
        assertFalse(service.hubCounts(ctx(DALI_STORE, 1001L, StaffRole.MANAGER)).containsKey("serviceAuditPending"));
        assertEquals(0L, service.hubCounts(ctx(40L, SERVICE, StaffRole.MERCHANT_SERVICE)).get("serviceAuditPending"), "门店没城市不能数出别的城市的");
    }

    // ------------------------------------------------------------------ 通过 / 驳回

    private Map<String, Object> onActivity(String source, Object store, Object creator, String city, String status)
    {
        Map<String, Object> a = new HashMap<String, Object>();
        a.put("activityId", 7L);
        a.put("source", source);
        a.put("storeId", store);
        a.put("appUserId", creator);
        a.put("city", city);
        a.put("title", "手作课");
        a.put("status", status);
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(a);
        return a;
    }

    @Test
    void passGoesThroughTheSharedAuditAndIsLogged()
    {
        onActivity("merchant", MERCHANT_STORE, MERCHANT, "大理市", "pending");
        Map<String, Object> out = service.serviceActivityAudit(asService(), 7L, true, "  ");
        assertEquals("open", out.get("status"));
        verify(activities).audit(7L, true, null, "staff:" + SERVICE);
        verify(support).op(any(), eq("service_activity_audit"), eq("通过商家活动"), anyString(), eq("手作课"), eq(true), isNull());
    }

    @Test
    void rejectNeedsAReasonAndSendsItTrimmed()
    {
        onActivity("merchant", MERCHANT_STORE, MERCHANT, "大理", "pending");
        assertEquals(422, code(() -> service.serviceActivityAudit(asService(), 7L, false, "   ")));
        assertEquals(422, code(() -> service.serviceActivityAudit(asService(), 7L, false, null)));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 256; i++) longText.append('字');
        assertEquals(422, code(() -> service.serviceActivityAudit(asService(), 7L, false, longText.toString())), "audit_reason 只有 255 字");
        assertEquals(422, code(() -> service.serviceActivityAudit(asService(), 7L, null, "x")), "没说通过还是驳回");
        verify(activities, never()).audit(any(), anyBoolean(), any(), any());

        Map<String, Object> out = service.serviceActivityAudit(asService(), 7L, false, " 时间不对 ");
        assertEquals("rejected", out.get("status"));
        verify(activities).audit(7L, false, "时间不对", "staff:" + SERVICE);
    }

    @Test
    void cannotAuditOutsideCitySourceOwnOrBlocked()
    {
        onActivity("merchant", MERCHANT_STORE, MERCHANT, "丽江", "pending");
        assertEquals(403, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)), "别的城市");
        onActivity("yard", MERCHANT_STORE, MERCHANT, "大理", "pending");
        assertEquals(403, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)), "小院活动还是后台审");
        onActivity("user", null, MERCHANT, "大理", "pending");
        assertEquals(403, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)), "用户自发");
        onActivity("merchant", MERCHANT_STORE, SERVICE, "大理", "pending");
        assertEquals(403, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)), "自己发起的");
        onActivity("merchant", MERCHANT_STORE, MERCHANT, "大理", "pending");
        onGrantRow(SERVICE, p("status", "1", "delFlag", "0", "roleCode", StaffRole.MERCHANT_SERVICE, "alsoService", "0"));
        assertEquals(403, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)), "这家店的授权被撤销过");
        onGrantRow(SERVICE, p("status", "0", "delFlag", "0", "roleCode", StaffRole.KEEPER, "alsoService", "0"));
        assertEquals(403, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)), "这家店上是普通小院主理人");
        verify(activities, never()).audit(any(), anyBoolean(), any(), any());

        onGrantRow(SERVICE, p("status", "0", "delFlag", "0", "roleCode", StaffRole.KEEPER, "alsoService", "1"));
        service.serviceActivityAudit(asService(), 7L, true, null);
        verify(activities).audit(7L, true, null, "staff:" + SERVICE);
    }

    @Test
    void missingAndAlreadyHandledAreClear()
    {
        when(db.one(contains("from kg_activity where activity_id"), anyMap())).thenReturn(null);
        assertEquals(404, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)));
        onActivity("merchant", MERCHANT_STORE, MERCHANT, "大理", "open");
        assertEquals(409, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)), "后台先点过了");
        verify(activities, never()).audit(any(), anyBoolean(), any(), any());
    }

    @Test
    void auditFailureIsNotLoggedAsDone()
    {
        onActivity("merchant", MERCHANT_STORE, MERCHANT, "大理", "pending");
        when(activities.audit(any(), anyBoolean(), any(), any())).thenThrow(new ServiceException("这个活动的状态刚刚变化了，请刷新后再看", 409));
        assertEquals(409, code(() -> service.serviceActivityAudit(asService(), 7L, true, null)));
        verify(support, never()).op(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());
    }

    // ------------------------------------------------------------------ 提交时的提醒

    private void onServicePeople()
    {
        when(db.list(contains("g.role_code = :r"), anyMap())).thenReturn(Arrays.asList(
                p("appUserId", SERVICE, "storeId", DALI_STORE, "city", "大理市", "nickname", "甲"),
                p("appUserId", LUNA, "storeId", 11L, "city", "大理", "nickname", "Luna"),
                p("appUserId", MERCHANT, "storeId", 12L, "city", "大理", "nickname", "提交人"),
                p("appUserId", OTHER_SERVICE, "storeId", 13L, "city", "大理", "nickname", "乙"),
                p("appUserId", LIJIANG_SERVICE, "storeId", 20L, "city", "丽江", "nickname", "丙")));
    }

    @Test
    void reminderGoesToServicePeopleInTheCityWithAVisibleMessageType()
    {
        onServicePeople();
        onGrantRow(OTHER_SERVICE, p("status", "1", "delFlag", "0", "roleCode", StaffRole.MERCHANT_SERVICE, "alsoService", "0"));
        service.activityPending(7L, MERCHANT_STORE, "喜洲小院", "手作课", "大理市", MERCHANT);
        // merchant_request：只有商家服务人角色的账号，消息中心只放行这一类（KgMessageMapper）
        verify(support).notifyStaff(eq(SERVICE), anyString(), eq("有商家活动待审核"), contains("手作课"), eq("merchant_request"), eq(7L), eq("/pages/staff/service-activities?storeId=" + DALI_STORE + "&id=7"));
        verify(support).notifyStaff(eq(LUNA), anyString(), anyString(), anyString(), eq("merchant_request"), eq(7L), eq("/pages/staff/service-activities?storeId=11&id=7"));
        verify(support, never()).notifyStaff(eq(MERCHANT), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(support, never()).notifyStaff(eq(OTHER_SERVICE), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(support, never()).notifyStaff(eq(LIJIANG_SERVICE), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void reminderFailureNeverReachesTheSubmitter()
    {
        onServicePeople();
        doThrow(new RuntimeException("消息表写不进去")).when(support).notifyStaff(eq(SERVICE), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        assertDoesNotThrow(() -> service.activityPending(7L, MERCHANT_STORE, "喜洲小院", "手作课", "大理", MERCHANT));
        verify(support).notifyStaff(eq(LUNA), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());

        when(db.list(contains("g.role_code = :r"), anyMap())).thenThrow(new RuntimeException("查不了人"));
        assertDoesNotThrow(() -> service.activityPending(7L, MERCHANT_STORE, "喜洲小院", "手作课", "大理", MERCHANT));
    }

    @Test
    void insideATransactionItWaitsForCommitAndUsesItsOwnTransaction()
    {
        onServicePeople();
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        ReflectionTestUtils.setField(service, "transactions", tx);
        TransactionSynchronizationManager.initSynchronization();
        service.activityPending(7L, MERCHANT_STORE, "喜洲小院", "手作课", "大理", MERCHANT);
        verify(support, never()).notifyStaff(any(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(db, never()).list(contains("g.role_code = :r"), anyMap());

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations())
        {
            s.afterCommit();
        }
        verify(support).notifyStaff(eq(SERVICE), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        ArgumentCaptor<TransactionDefinition> def = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(tx, atLeastOnce()).getTransaction(def.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, def.getValue().getPropagationBehavior(), "已提交的连接上写了不算数");

        // afterCommit 里抛错，调用方（已提交的保存）会收到异常：这里不能漏出去
        doThrow(new RuntimeException("x")).when(tx).getTransaction(any());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations())
        {
            assertDoesNotThrow(s::afterCommit);
        }
    }

    // ------------------------------------------------------------------ 退回线上任务

    private Map<String, Object> onRequest(String type, String status, Object jobId, Object serviceUser, Object merchant)
    {
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("requestId", 500L);
        r.put("requestType", type);
        r.put("status", status);
        r.put("city", "大理");
        r.put("storeId", MERCHANT_STORE);
        r.put("storeName", "喜洲小院");
        r.put("merchantUserId", merchant);
        r.put("title", "写一篇推文");
        r.put("jobId", jobId);
        r.put("serviceUserId", serviceUser);
        when(db.one(contains("from kg_merchant_request where request_id"), anyMap())).thenReturn(r);
        return r;
    }

    private void onJob(String status)
    {
        when(db.one(contains("for update"), anyMap())).thenReturn(status == null ? null : p("status", status));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardedOnlineTaskCanBeReturnedWhileStillADraft()
    {
        onRequest("online_task", "forwarded", 88L, null, MERCHANT);
        onJob("draft");
        Map<String, Object> out = service.serviceReturn(asService(), 500L, " 佣金写错了 ");
        assertEquals(KgMerchantRequestService.RETURNED, out.get("requestStatus"));

        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(contains("update kg_merchant_request set status"), ps.capture());
        assertEquals("forwarded", ps.getValue().get("from"));
        assertEquals("returned", ps.getValue().get("to"));
        assertEquals("佣金写错了", ps.getValue().get("x_return_reason"));
        assertTrue(ps.getValue().containsKey("x_service_user_id"), "接手人清掉");
        assertNull(ps.getValue().get("x_service_user_id"));
        verify(support).notifyStaff(eq(MERCHANT), anyString(), eq("需求被退回"), contains("佣金写错了"), eq("merchant_request"), eq(500L), anyString());
        verify(support).op(any(), eq("service_return"), anyString(), anyString(), contains("佣金写错了"), eq(true), isNull());
    }

    @Test
    void onlyTheHolderReturnsAnAcceptedOne()
    {
        onRequest("online_task", "accepted", 88L, OTHER_SERVICE, MERCHANT);
        onJob("draft");
        assertEquals(403, code(() -> service.serviceReturn(asService(), 500L, "理由")));
        onRequest("online_task", "accepted", 88L, SERVICE, MERCHANT);
        assertEquals(KgMerchantRequestService.RETURNED, service.serviceReturn(asService(), 500L, "理由").get("requestStatus"));
        verify(db).update(contains("update kg_merchant_request set status"), argThat(m -> "accepted".equals(m.get("from"))));
    }

    @Test
    void cannotReturnOncePublishedDoneOrWithoutAMerchantOrReason()
    {
        onRequest("online_task", "accepted", 88L, SERVICE, MERCHANT);
        onJob("published");
        assertEquals(409, code(() -> service.serviceReturn(asService(), 500L, "理由")), "上架后不能退");
        onJob(null);
        assertEquals(409, code(() -> service.serviceReturn(asService(), 500L, "理由")), "岗位已删");
        onJob("draft");
        assertEquals(422, code(() -> service.serviceReturn(asService(), 500L, "  ")));
        assertEquals(422, code(() -> service.serviceReturn(asService(), 500L, null)));
        onRequest("online_task", "done", 88L, SERVICE, MERCHANT);
        assertEquals(409, code(() -> service.serviceReturn(asService(), 500L, "理由")));
        onRequest("online_task", "forwarded", 88L, null, null);
        assertEquals(409, code(() -> service.serviceReturn(asService(), 500L, "理由")), "代提交的没有商家账号能改");
        onRequest("online_task", "pending_verify", 88L, null, MERCHANT);
        assertEquals(403, code(() -> service.serviceReturn(asService(), 500L, "理由")), "还没转过来");
        onRequest("online_task", "forwarded", 88L, null, SERVICE);
        assertEquals(403, code(() -> service.serviceReturn(asService(), 500L, "理由")), "自己提交的");
        verify(db, never()).update(contains("update kg_merchant_request set status"), anyMap());
    }

    @Test
    void requestsWithoutAJobDoNotLookAtOne()
    {
        onRequest("managed", "forwarded", null, null, MERCHANT);
        assertEquals(KgMerchantRequestService.RETURNED, service.serviceReturn(asService(), 500L, "理由").get("requestStatus"));
        verify(db, never()).one(contains("from kg_job"), anyMap());
    }

    @Test
    void detailTellsThePageWhetherItCanBeReturned()
    {
        when(db.one(contains("from kg_job j left join kg_online_task"), anyMap())).thenReturn(p("jobStatus", "draft"));
        onRequest("online_task", "forwarded", 88L, null, MERCHANT);
        assertEquals(true, service.serviceDetail(asService(), 500L).get("canReturn"));
        onRequest("online_task", "accepted", 88L, SERVICE, MERCHANT);
        assertEquals(true, service.serviceDetail(asService(), 500L).get("canReturn"));
        onRequest("online_task", "accepted", 88L, OTHER_SERVICE, MERCHANT);
        assertEquals(false, service.serviceDetail(asService(), 500L).get("canReturn"), "别人接手的");
        onRequest("online_task", "forwarded", 88L, null, null);
        assertEquals(false, service.serviceDetail(asService(), 500L).get("canReturn"), "代提交的");
        onRequest("managed", "accepted", null, SERVICE, MERCHANT);
        assertEquals(true, service.serviceDetail(asService(), 500L).get("canReturn"));
        when(db.one(contains("from kg_job j left join kg_online_task"), anyMap())).thenReturn(p("jobStatus", "published"));
        onRequest("online_task", "accepted", 88L, SERVICE, MERCHANT);
        assertEquals(false, service.serviceDetail(asService(), 500L).get("canReturn"), "已上架");
    }
}
