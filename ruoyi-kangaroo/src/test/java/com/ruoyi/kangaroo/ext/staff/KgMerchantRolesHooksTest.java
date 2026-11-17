package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** 接到老代码上的几处：门店成员撤销、岗位发布、线上任务发布、付款登记、hub、后台实体。 */
class KgMerchantRolesHooksTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private static StaffSupport.Ctx ctx(long store, long uid, String role)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        AppLoginUser me = new AppLoginUser();
        me.setUserId(uid);
        me.setNickname("用户" + uid);
        me.setStaffGrants(Collections.singletonList(new StaffGrantView()));
        c.me = me;
        c.storeId = store;
        c.storeName = "门店" + store;
        c.role = role;
        return c;
    }

    // ------------------------------------------------------------------ 撤销授权

    private KgStaffTeamService team(KgJdbc db)
    {
        KgStaffTeamService t = new KgStaffTeamService();
        ReflectionTestUtils.setField(t, "db", db);
        ReflectionTestUtils.setField(t, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(t, "grants", mock(IKgStaffGrantService.class));
        ReflectionTestUtils.setField(t, "tokens", mock(AppTokenService.class));
        ReflectionTestUtils.setField(t, "messages", mock(IKgMessageService.class));
        return t;
    }

    @Test
    void managerCannotRevokePlatformOnlyGrants()
    {
        for (String role : Arrays.asList(StaffRole.MERCHANT, StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD, StaffRole.MANAGER))
        {
            KgJdbc db = mock(KgJdbc.class);
            when(db.must(contains("from kg_staff_grant"), anyMap(), anyString())).thenReturn(p("storeId", 1L, "roleCode", role, "appUserId", 9L, "grantId", 5L));
            assertThrows(ServiceException.class, () -> team(db).revoke(ctx(1, 7, StaffRole.MANAGER), 5L), role);
            verify(db, never()).update(anyString(), anyMap());
        }
    }

    @Test
    void managerStillRevokesOrdinaryRoles()
    {
        for (String role : Arrays.asList(StaffRole.STAFF, StaffRole.KEEPER, StaffRole.ONLINE_STAFF))
        {
            KgJdbc db = mock(KgJdbc.class);
            when(db.must(contains("from kg_staff_grant"), anyMap(), anyString())).thenReturn(p("storeId", 1L, "roleCode", role, "appUserId", 9L, "grantId", 5L));
            when(db.update(contains("update kg_staff_grant"), anyMap())).thenReturn(1);
            assertDoesNotThrow(() -> team(db).revoke(ctx(1, 7, StaffRole.MANAGER), 5L), role);
        }
    }

    @Test
    void managerCannotGrantTheNewRoles()
    {
        for (String role : Arrays.asList(StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD, StaffRole.MERCHANT, StaffRole.MANAGER))
        {
            KgJdbc db = mock(KgJdbc.class);
            assertThrows(ServiceException.class, () -> team(db).grant(ctx(1, 7, StaffRole.MANAGER), "99", role), role);
            verify(db, never()).insert(anyString(), anyMap());
        }
    }

    @Test
    void newRolesSeeOnlyTheirOwnMatrixNotTheTeam()
    {
        for (String role : Arrays.asList(StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD))
        {
            KgJdbc db = mock(KgJdbc.class);
            Map<String, Object> out = team(db).roles(ctx(1, 7, role), null);
            assertEquals(Collections.emptyList(), out.get("roles"));
            assertEquals(Boolean.FALSE, out.get("canManage"));
            assertEquals(role, out.get("selected"));
            verify(db, never()).list(contains("kg_staff_grant"), anyMap());
        }
    }

    @Test
    void managerSeesNewRolesInTheTeamList()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) team(db).roles(ctx(1, 7, StaffRole.MANAGER), null).get("roles");
        assertTrue(roles.stream().anyMatch(r -> StaffRole.MERCHANT_SERVICE.equals(r.get("code")) && "商家服务人".equals(r.get("name"))));
        assertTrue(roles.stream().anyMatch(r -> StaffRole.CITY_LEAD.equals(r.get("code")) && "城市负责人".equals(r.get("name"))));
    }

    @Test
    void hubCarriesPendingCounts()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgStaffTeamService t = team(db);
        KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
        when(requests.hubCounts(any())).thenReturn(new HashMap<String, Object>(p("intakePending", 4L)));
        ReflectionTestUtils.setField(t, "merchantRequests", requests);
        assertEquals(4L, t.hub(ctx(1, 7, StaffRole.MANAGER)).get("intakePending"));
    }

    // ------------------------------------------------------------------ 岗位发布

    private KgStaffJobService jobService(KgJdbc db, KgMerchantRequestService requests)
    {
        KgStaffJobService s = new KgStaffJobService();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(s, "merchantRequests", requests);
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(p("storeName", "喜洲小院", "city", "大理", "address", "喜洲", "cover", ""));
        return s;
    }

    private static Map<String, Object> jobForm()
    {
        Map<String, Object> in = new HashMap<String, Object>();
        in.put("jobType", "volunteer");
        in.put("title", "招换工");
        in.put("work", "前台接待");
        in.put("hope", "开朗");
        in.put("startDate", "2026-12-01");
        in.put("headcount", 2);
        in.put("images", Collections.singletonList(PHOTO));
        return in;
    }

    @Test
    void merchantJobIsDispatchedInTheSameCall()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
        when(db.insert(eq("kg_job"), anyMap())).thenReturn(77L);
        when(requests.dispatchJob(any(), eq(77L), eq("volunteer"), eq("招换工"), anyString()))
                .thenReturn(new HashMap<String, Object>(p("requestId", 500L, "requestStatus", "dispatched", "statusLabel", "换工主理人完善中", "message", "已提交，本城换工主理人会尽快完善并上架")));
        Map<String, Object> out = jobService(db, requests).create(ctx(1, 7, StaffRole.MERCHANT), jobForm());
        assertEquals(77L, out.get("jobId"));
        assertEquals("换工主理人完善中", out.get("statusLabel"));
        assertEquals(500L, out.get("requestId"));
        assertNotNull(out.get("message"));
    }

    @Test
    void otherRolesKeepTheOldDraftFlow()
    {
        for (String role : Arrays.asList(StaffRole.MANAGER, StaffRole.KEEPER, StaffRole.STAFF))
        {
            KgJdbc db = mock(KgJdbc.class);
            KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
            when(db.insert(eq("kg_job"), anyMap())).thenReturn(77L);
            Map<String, Object> out = jobService(db, requests).create(ctx(1, 7, role), jobForm());
            assertEquals("待平台审核", out.get("statusLabel"), role);
            assertFalse(out.containsKey("message"));
            verifyNoInteractions(requests);
        }
    }

    @Test
    void newColumnsAreStillWrittenOnCreate()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.insert(eq("kg_job"), anyMap())).thenReturn(77L);
        jobService(db, null).create(ctx(1, 7, StaffRole.MANAGER), jobForm());
        verify(db).insert(eq("kg_job"), argThat(r -> Long.valueOf(1L).equals(r.get("store_id")) && "draft".equals(r.get("status")) && "staff".equals(r.get("source"))
                && "staff:7".equals(r.get("create_by")) && r.get("create_time") != null && "用户7".equals(r.get("publisher_name"))));
    }

    @Test
    void draftEditKeepsTypeStoreAndClearsWhatWasEmptied()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 77L, "storeId", 1L, "jobType", "parttime", "status", "draft"));
        Map<String, Object> in = jobForm();
        in.put("jobType", "fulltime");
        in.put("title", "改过的标题");
        Map<String, Object> out = jobService(db, null).updateMerchantDraft(ctx(1, 7, StaffRole.MANAGER), 77L, in);
        assertEquals("改过的标题", out.get("title"));
        verify(db).updateRow(eq("kg_job"), eq("job_id"), eq(77L), argThat(c -> "parttime".equals(c.get("job_type")) && "改过的标题".equals(c.get("title"))
                && c.containsKey("min_days") && c.get("min_days") == null && c.containsKey("latitude")
                && !c.containsKey("store_id") && !c.containsKey("status") && !c.containsKey("source") && !c.containsKey("create_by") && "staff:7".equals(c.get("update_by"))));
    }

    @Test
    void draftEditIsRefusedOnOtherStoresAndNonDrafts()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 77L, "storeId", 2L, "jobType", "parttime", "status", "draft"));
        assertEquals(403, assertThrows(ServiceException.class, () -> jobService(db, null).updateMerchantDraft(ctx(1, 7, StaffRole.MANAGER), 77L, jobForm())).getCode());
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 77L, "storeId", 1L, "jobType", "parttime", "status", "published"));
        assertEquals(409, assertThrows(ServiceException.class, () -> jobService(db, null).updateMerchantDraft(ctx(1, 7, StaffRole.MANAGER), 77L, jobForm())).getCode());
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void jobListShowsRequestProgressOnDraftsAndCanPublishOnlyForTheManager()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgStaffJobService s = jobService(db, null);
        when(db.list(contains("from kg_job j"), anyMap())).thenAnswer(i -> Arrays.asList(
                new HashMap<String, Object>(p("jobId", 77L, "status", "draft", "source", "staff", "requestId", 500L, "requestStatus", "dispatched", "address", "", "jobType", "volunteer")),
                new HashMap<String, Object>(p("jobId", 78L, "status", "draft", "source", "staff", "address", "", "jobType", "volunteer"))));
        when(db.one(anyString(), anyMap())).thenReturn(new HashMap<String, Object>());
        List<?> rows = (List<?>) s.list(ctx(1, 7, StaffRole.MANAGER), null, null, null).get("rows");
        Map<?, ?> merchantDraft = (Map<?, ?>) rows.get(0);
        Map<?, ?> oldDraft = (Map<?, ?>) rows.get(1);
        assertEquals("换工主理人完善中", merchantDraft.get("statusLabel"));
        assertEquals(Boolean.TRUE, merchantDraft.get("canPublish"));
        assertEquals("待平台审核", oldDraft.get("statusLabel"), "没有需求行的老草稿走老流程");
        assertEquals(Boolean.FALSE, oldDraft.get("canPublish"), "普通草稿仍只能后台上架");

        rows = (List<?>) s.list(ctx(1, 8, StaffRole.MERCHANT), null, null, null).get("rows");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) rows.get(0)).get("canPublish"), "商家自己不能上架");
    }

    // ------------------------------------------------------------------ 线上任务

    private KgOnlineTaskService onlineService(KgJdbc db, KgMerchantRequestService requests)
    {
        KgOnlineTaskService s = new KgOnlineTaskService();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(s, "guard", mock(KgCommunityGuard.class));
        ReflectionTestUtils.setField(s, "merchantRequests", requests);
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(p("cover", ""));
        return s;
    }

    private static Map<String, Object> taskForm()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("title", "写一篇推文");
        b.put("taskType", "内容写作");
        b.put("deliverables", "一篇文章");
        b.put("criteria", "不少于 800 字");
        b.put("commissionFen", 10000);
        b.put("quota", 1);
        b.put("deadline", java.time.LocalDateTime.now().plusDays(5).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return b;
    }

    @Test
    void merchantOnlineTaskGoesToIntakeSpecialistDoesNot()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
        when(db.insert(eq("kg_job"), anyMap())).thenReturn(88L);
        when(requests.onlineTaskCreated(any(), eq(88L), eq("写一篇推文"), eq("一篇文章")))
                .thenReturn(new HashMap<String, Object>(p("requestId", 500L, "requestStatus", "pending_verify", "message", "已提交，本城换工主理人会和你联系核实")));
        Map<String, Object> out = onlineService(db, requests).create(ctx(1, 7, StaffRole.MERCHANT), taskForm());
        assertEquals(88L, out.get("jobId"));
        assertEquals("pending_verify", out.get("requestStatus"));
        assertEquals("已提交，本城换工主理人会和你联系核实", out.get("message"));

        KgJdbc db2 = mock(KgJdbc.class);
        KgMerchantRequestService requests2 = mock(KgMerchantRequestService.class);
        when(db2.insert(eq("kg_job"), anyMap())).thenReturn(89L);
        when(db2.list(anyString(), anyMap())).thenReturn(Collections.<Map<String, Object>>emptyList());
        assertEquals("已提交平台审核，审核通过后对用户可见", onlineService(db2, requests2).create(ctx(1, 8, StaffRole.ONLINE_STAFF), taskForm()).get("message"));
        onlineService(db2, requests2).create(ctx(1, 8, StaffRole.KEEPER), taskForm());
        verifyNoInteractions(requests2);
    }

    @Test
    void taskExtensionRowIsStillWrittenAfterTheSplit()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.insert(eq("kg_job"), anyMap())).thenReturn(88L);
        onlineService(db, null).create(ctx(1, 8, StaffRole.KEEPER), taskForm());
        verify(db).insert(eq("kg_online_task"), argThat(e -> Long.valueOf(88L).equals(e.get("job_id")) && "内容写作".equals(e.get("task_type")) && Long.valueOf(10000L).equals(e.get("commission_fen"))));
    }

    @Test
    void returnedTaskIsUpdatedInPlaceOnlyWhileDraft()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgOnlineTaskService s = onlineService(db, null);
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 88L, "storeId", 1L, "status", "draft"));
        Map<String, Object> b = taskForm();
        b.put("title", "改过的标题");
        s.updateDraftTask(ctx(1, 7, StaffRole.MERCHANT), 88L, b);
        verify(db).updateRow(eq("kg_job"), eq("job_id"), eq(88L), argThat(c -> "改过的标题".equals(c.get("title")) && !c.containsKey("store_id") && !c.containsKey("status") && !c.containsKey("source")));
        verify(db).updateRow(eq("kg_online_task"), eq("job_id"), eq(88L), argThat(c -> "内容写作".equals(c.get("task_type")) && !c.containsKey("job_id") && !c.containsKey("create_by")));

        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 88L, "storeId", 2L, "status", "draft"));
        assertEquals(403, assertThrows(ServiceException.class, () -> s.updateDraftTask(ctx(1, 7, StaffRole.MERCHANT), 88L, taskForm())).getCode());
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 88L, "storeId", 1L, "status", "published"));
        assertEquals(409, assertThrows(ServiceException.class, () -> s.updateDraftTask(ctx(1, 7, StaffRole.MERCHANT), 88L, taskForm())).getCode());
    }

    // ------------------------------------------------------------------ 后台实体

    @Test
    void adminEntityOnlyEditsContentAndHasTheFlowActions()
    {
        ExtEntity e = new KgMerchantRequestModule().entities().get(0);
        assertEquals("merchantrequest", e.key);
        assertEquals("kg_merchant_request", e.table);
        assertEquals("kangaroo:merchantrequest", e.perm);
        assertFalse(e.allowAdd);
        assertFalse(e.allowDelete);
        assertTrue(e.allowEdit);
        List<String> editable = Arrays.asList("title", "contactName", "contactPhone", "content", "images", "verifyNote");
        for (ExtField f : e.fields)
        {
            assertEquals(editable.contains(f.name), f.editable, f.name);
        }
        assertEquals(3, e.actions.size());
        assertEquals("/kangaroo/merchant-request/{requestId}/redispatch", e.actions.get(0).get("url"));
        assertEquals("kangaroo:merchantrequest:edit", e.actions.get(0).get("perm"));
        for (String s : Arrays.asList("unassigned", "dispatched", "pending_verify", "forwarded", "accepted", "returned", "published", "done", "closed"))
        {
            assertTrue(e.field("status").options.contains(s + ":"), s);
        }
    }
}
