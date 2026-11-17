package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/** 2026-11-03（V74）员工任务流转、全体 / 按岗位派发：只查 Java 这一层的判断和拼出来的 SQL，用 Mock 代替 JDBC */
class KgStaffTaskFlowTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final KgStaffTaskService service = new KgStaffTaskService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "files", mock(KgPrivateFiles.class));
        ReflectionTestUtils.setField(service, "cities", cities);
        when(cities.jobCity(anyString())).thenAnswer(i -> {
            String n = i.getArgument(0);
            return n.endsWith("市") ? n.substring(0, n.length() - 1) : n;
        });
        when(db.one(contains("date_format(now()"), anyMap())).thenReturn(p("value", "2026-11-03 10:00:00"));
        when(db.list(contains("from kg_staff_task_submission"), anyMap())).thenReturn(new ArrayList<>());
        when(db.list(contains("from kg_staff_task_event"), anyMap())).thenReturn(new ArrayList<>());
        when(db.one(contains("select nickname from kg_app_user"), anyMap())).thenReturn(p("nickname", "小周"));
    }

    private void task(String status, long version)
    {
        Map<String, Object> t = p("taskId", 7L, "status", status, "version", version, "assigneeId", 100L, "storeId", 3L, "title", "拍门店照片", "requestKey", "stafftask:1:abc");
        when(db.one(startsWith("select t.* from kg_staff_task t"), anyMap())).thenReturn(t);
        when(db.one(startsWith("select t.*,s.store_name"), anyMap())).thenReturn(new java.util.HashMap<>(t));
    }

    private int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode();
    }

    @Test
    void taskRolesAreStaffOnlyNotMerchantsOrRetiredStaff()
    {
        assertEquals(Arrays.asList(StaffRole.MANAGER, StaffRole.KEEPER, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD), KgStaffTaskService.ROLES);
        assertFalse(KgStaffTaskService.ROLES.contains(StaffRole.MERCHANT));
        assertFalse(KgStaffTaskService.ROLES.contains(StaffRole.STAFF));
        assertEquals(422, code(() -> KgStaffTaskService.role("merchant")));
        assertEquals("keeper", KgStaffTaskService.role("keeper"));
    }

    @Test
    void acceptOnlyFromPendingAndRecordsTimeline()
    {
        task("pending", 3);
        service.act(100L, 7L, p("version", 3, "action", "accept"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).update(sql.capture(), anyMap());
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("status='accepted'") && s.contains("version=version+1")), sql.getAllValues().toString());
        verify(db).insert(eq("kg_staff_task_event"), argThat(m -> "accept".equals(m.get("action")) && "staff".equals(m.get("actor_type")) && "小周".equals(m.get("actor_name"))));

        task("submitted", 4);
        assertEquals(409, code(() -> service.act(100L, 7L, p("version", 4, "action", "accept"))));
    }

    @Test
    void staleVersionIsRejected()
    {
        task("accepted", 5);
        assertEquals(409, code(() -> service.act(100L, 7L, p("version", 4, "action", "reply", "content", "请问门头要拍夜景吗"))));
    }

    @Test
    void replyFlagsTheAdminAndWorksWhileWaitingForReview()
    {
        task("submitted", 2);
        service.act(100L, 7L, p("version", 2, "action", "reply", "content", "补充：第三张是后门"));
        verify(db).update(argThat(s -> s.contains("admin_attention='1'") && !s.contains("status=")), anyMap());
        task("approved", 3);
        assertEquals(409, code(() -> service.act(100L, 7L, p("version", 3, "action", "reply", "content", "还在吗"))));
        task("accepted", 3);
        assertEquals(422, code(() -> service.act(100L, 7L, p("version", 3, "action", "reply"))));
    }

    @Test
    void extendRequestNeedsAFutureDateAndReason()
    {
        task("accepted", 1);
        assertEquals(422, code(() -> service.act(100L, 7L, p("version", 1, "action", "extend_request", "dueDate", "2026-11-01", "content", "下雨"))));
        assertEquals(422, code(() -> service.act(100L, 7L, p("version", 1, "action", "extend_request", "dueDate", "2026-11-10"))));
        service.act(100L, 7L, p("version", 1, "action", "extend_request", "dueDate", "2026-11-10", "content", "这周一直下雨"));
        verify(db).update(contains("requested_due_date=:due,admin_attention='1'"), argThat(m -> "2026-11-10".equals(m.get("due"))));
    }

    @Test
    void declineMovesToDeclinedWithReason()
    {
        task("pending", 1);
        assertEquals(422, code(() -> service.act(100L, 7L, p("version", 1, "action", "decline"))));
        service.act(100L, 7L, p("version", 1, "action", "decline", "content", "这周不在店里"));
        verify(db).update(contains("status='declined',admin_attention='1'"), anyMap());
    }

    @Test
    void adminReplyNotifiesAndBumpsNotice()
    {
        task("declined", 2);
        service.handle(7L, p("version", 2, "action", "reply", "content", "那下周可以吗"), "admin", 1L);
        verify(db).update(argThat(s -> s.contains("admin_attention='0'") && s.contains("notice_version=notice_version+1")), anyMap());
        verify(support).notifyStaff(eq(100L), eq("staff"), eq("管理员回复了你的任务"), contains("那下周可以吗"), eq("staff_task"), eq(7L), contains("taskId=7"));
    }

    @Test
    void reopenOnlyFromDeclinedOrClosedAndReassignNotWhileReviewing()
    {
        task("accepted", 1);
        assertEquals(409, code(() -> service.handle(7L, p("version", 1, "action", "reopen"), "admin", 1L)));
        task("closed", 1);
        service.handle(7L, p("version", 1, "action", "reopen"), "admin", 1L);
        verify(db).update(contains("status='pending'"), anyMap());
        task("submitted", 1);
        assertEquals(409, code(() -> service.handle(7L, p("version", 1, "action", "reassign", "grantId", 9), "admin", 1L)));
        task("approved", 1);
        assertEquals(409, code(() -> service.handle(7L, p("version", 1, "action", "close"), "admin", 1L)));
    }

    @Test
    void reassignMovesTheTaskAndTellsBothPeople()
    {
        task("declined", 2);
        when(db.one(contains("where g.grant_id=:id and"), anyMap())).thenReturn(p("grantId", 9L, "storeId", 4L, "appUserId", 200L, "roleCode", "keeper", "nickname", "阿梅", "storeName", "大理小院"));
        when(db.count(contains("where request_key=:k and store_id=:s and assignee_id=:u"), anyMap())).thenReturn(0L);
        when(db.one(contains("select s.store_name from kg_store"), anyMap())).thenReturn(p("storeName", "平潭小院"));
        service.handle(7L, p("version", 2, "action", "reassign", "grantId", 9, "content", "阿梅这周在"), "admin", 1L);
        verify(db).update(contains("set store_id=:s,assignee_id=:u,role_code=:r,status='pending'"), argThat(m -> Long.valueOf(200L).equals(m.get("u")) && "keeper".equals(m.get("r"))));
        verify(support).notifyStaff(eq(100L), eq("staff"), eq("任务已转给其他同事"), anyString(), eq("staff_task"), eq(7L), anyString());
        verify(support).notifyStaff(eq(200L), eq("staff"), contains("转派"), anyString(), eq("staff_task"), eq(7L), contains("taskId=7"));
        verify(db).insert(eq("kg_staff_task_event"), argThat(m -> "reassign".equals(m.get("action")) && String.valueOf(m.get("content")).contains("阿梅")));
    }

    private Map<String, Object> grant(long grantId, long user, long store, String role, String city)
    {
        return p("grantId", grantId, "appUserId", user, "storeId", store, "roleCode", role, "nickname", "员工" + user, "storeName", "店" + store, "city", city);
    }

    @Test
    void broadcastIsOnePersonOneTaskPickingTheFirstRoleAndFiltersCity()
    {
        List<Map<String, Object>> rows = new ArrayList<>(Arrays.asList(
                grant(5, 100, 3, "city_lead", "大理市"), grant(6, 100, 4, "manager", "大理"), grant(7, 101, 5, "online_staff", "丽江市"), grant(8, 102, 3, "keeper", "大理市")));
        when(db.list(contains("from kg_staff_grant g left join kg_store s"), anyMap())).thenReturn(rows);
        List<Map<String, Object>> all = service.audienceRows("all", null, null, null);
        assertEquals(3, all.size());
        assertEquals("manager", all.stream().filter(r -> Long.valueOf(100L).equals(r.get("appUserId"))).findFirst().get().get("roleCode"), "一人多岗取靠前的岗位");
        List<Map<String, Object>> dali = service.audienceRows("all", null, "大理", null);
        assertEquals(2, dali.size(), "城市归一后比：大理市 = 大理");
        assertEquals(422, code(() -> service.audienceRows("role", "merchant", null, null)));
        assertEquals(422, code(() -> service.audienceRows("everyone", null, null, null)));
        service.audienceRows("role", "keeper", null, 3L);
        verify(db).list(contains(" and g.role_code=:r and (g.store_id=:s or g.store_id is null)"), argThat(m -> "keeper".equals(m.get("r")) && Long.valueOf(3L).equals(m.get("s"))));
    }

    /** 2026-11 按城市授权（store_id 为空）的城市负责人：派发对象要算他，任务挂在「负的城市编号」上，限定门店时只算同城的 */
    @Test
    void cityGrantLeadsAreCountedAndScopedByCity()
    {
        List<Map<String, Object>> rows = new ArrayList<>(Arrays.asList(
                grant(11, 300, -7, "city_lead", "丽江"), grant(12, 301, -8, "city_lead", "大理"), grant(13, 302, 5, "manager", "丽江市")));
        when(db.list(contains("from kg_staff_grant g left join kg_store s"), anyMap())).thenReturn(rows);
        List<Map<String, Object>> lijiang = service.audienceRows("all", null, "丽江", null);
        assertEquals(2, lijiang.size(), "丽江按城市授权的城市负责人要算上，大理的不算");
        assertEquals(-7L, lijiang.get(1).get("storeId"), "城市授权的任务挂在负的城市编号上");
        when(db.one(contains("select city from kg_store where store_id=:s"), anyMap())).thenReturn(p("city", "丽江市"));
        List<Map<String, Object>> store5 = service.audienceRows("all", null, null, 5L);
        assertEquals(2, store5.size());
        assertEquals(Arrays.asList(302L, 300L), Arrays.asList(store5.get(0).get("appUserId"), store5.get(1).get("appUserId")), "限定丽江的门店：丽江的城市授权算，大理的不算（岗位顺序：换工主理人在前）");
        verify(db, atLeastOnce()).list(argThat(s -> s.contains("left join kg_city c on c.city_id=g.city_id") && s.contains("(g.store_id is null and c.status='0')")), anyMap());
    }

    @Test
    void broadcastAssignCreatesABatchAndOneTaskEach()
    {
        when(db.list(contains("from kg_staff_grant g left join kg_store s"), anyMap())).thenReturn(new ArrayList<>(Arrays.asList(
                grant(5, 100, 3, "manager", "大理"), grant(7, 101, 5, "online_staff", "丽江"))));
        when(db.claimIdem(anyString(), anyString())).thenReturn(true);
        when(db.insert(eq("kg_staff_task_batch"), anyMap())).thenReturn(50L);
        when(db.insert(eq("kg_staff_task"), anyMap())).thenReturn(71L, 72L);
        Map<String, Object> out = service.assign(p("mode", "all", "title", "月底盘点", "description", "拍库存照片", "requestId", "abcdefghijklmnop1234"), "admin", 1L);
        assertEquals(2, out.get("count"));
        assertEquals(50L, out.get("batchId"));
        verify(db).insert(eq("kg_staff_task_batch"), argThat(m -> "all".equals(m.get("scope"))));
        verify(db, times(2)).insert(eq("kg_staff_task"), argThat(m -> Long.valueOf(50L).equals(m.get("batch_id")) && "pending".equals(m.get("status"))));
        verify(db, times(2)).insert(eq("kg_staff_task_event"), argThat(m -> "assign".equals(m.get("action"))));
        verify(db).update(contains("update kg_staff_task_batch set total=:n"), argThat(m -> Integer.valueOf(2).equals(m.get("n"))));
    }

    @Test
    void pickStillLimitedAndEmptyAudienceRefused()
    {
        assertEquals(422, code(() -> service.assign(p("mode", "pick", "title", "a", "description", "b", "requestId", "abcdefghijklmnop1234", "grantIds", new ArrayList<>()), "admin", 1L)));
        when(db.list(contains("from kg_staff_grant g left join kg_store s"), anyMap())).thenReturn(new ArrayList<>());
        assertEquals(422, code(() -> service.assign(p("mode", "role", "roleCode", "keeper", "title", "a", "description", "b", "requestId", "abcdefghijklmnop1234"), "admin", 1L)));
    }

    @Test
    void staffAccessFollowsAnyTaskRoleGrantNotOnlyManager() throws Exception
    {
        task("pending", 1);
        service.detail(100L, 7L);
        verify(db).one(argThat(s -> s.startsWith("select t.* from kg_staff_task t") && s.contains("g.role_code in ('manager','keeper','online_staff','merchant_service','city_lead')")
                && !s.contains("g.role_code='manager'")), anyMap());
    }
}
