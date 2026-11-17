package com.ruoyi.kangaroo.ext.staff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppStaffOpsController;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 调度日志「全部门店」的导出与导出权：逐家按账号在那家门店上的角色判，
 * 不能像之前那样用 ctx(null) 取到的第一家门店代表全部（只导出第一家、也只按第一家的角色判）。
 * 账号：A 店管家（第一家授权）、B 店主理人、C 店管家；此组仅 B 店有导出权。
 */
class KgDispatchExportTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgDispatchService service = new KgDispatchService();
    // me 必须先于两个 ctx 初始化（字段按声明顺序初始化）
    private final AppLoginUser me = new AppLoginUser();
    private final StaffSupport.Ctx ctxA = ctx(1L, "A店", "keeper");
    private final StaffSupport.Ctx ctxB = ctx(2L, "B店", "manager");

    private StaffSupport.Ctx ctx(Long storeId, String name, String role)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = me;
        c.storeId = storeId;
        c.storeName = name;
        c.role = role;
        return c;
    }

    private static StaffGrantView grant(Long storeId, String name, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(storeId);
        g.setStoreName(name);
        g.setRoleCode(role);
        return g;
    }

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        // 打开日志时顺手同步走的是代理自调，测试里换成什么都不做的替身
        ReflectionTestUtils.setField(service, "self", mock(KgDispatchService.class));
        me.setUserId(9L);
        me.setNickname("调度员");
        me.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grant(1L, "A店", "keeper"), grant(2L, "B店", "manager"),
                grant(3L, "C店", "keeper"))));
        when(support.ctx(2L)).thenReturn(ctxB);
        when(db.list(contains("from kg_dispatch_log"), anyMap())).thenAnswer(inv -> {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            rows.add(p("logId", 11L, "storeId", 2L, "opType", "adjust", "operatorName", "主理人", "operatorId", 5L, "storeName", "B店",
                    "title", "前台", "jobType", "volunteer", "beforeValue", "名额：2", "afterValue", "名额：3", "delta", 1,
                    "createTime", "2026-10-02 10:00:00"));
            return rows;
        });
    }

    @SuppressWarnings("unchecked")
    private static boolean scopeIs(Map<String, ?> params, Long... ids)
    {
        return params != null && Arrays.asList(ids).equals(params.get("ids"));
    }

    @Test
    void allStoresExportCoversOnlyStoresWhereTheRoleAllowsExport()
    {
        Map<String, Object> out = service.exportCsv(ctxA, null, "2026-10-01", "2026-10-03", null);
        assertEquals(1, out.get("count"));
        assertEquals(Arrays.asList("A店", "C店"), out.get("skippedStores"));
        assertTrue(String.valueOf(out.get("csv")).contains("B店 · 前台"));
        // 只查 B 店（主理人）的日志，不是第一家授权的 A 店
        verify(db).list(contains("from kg_dispatch_log"), argThat(m -> scopeIs(m, 2L)));
        // 导出操作日志记在被导出的 B 店上
        verify(support).op(same(ctxB), eq("dispatch_export"), eq("数据导出"), eq("export"), eq("调度日志 1 条"), eq(true), isNull());
        verify(support, never()).op(same(ctxA), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void selectedStoreExportUsesOnlyThatStore()
    {
        Map<String, Object> out = service.exportCsv(ctxB, 2L, null, null, null);
        assertEquals(1, out.get("count"));
        assertEquals(Collections.emptyList(), out.get("skippedStores"));
        verify(db).list(contains("from kg_dispatch_log"), argThat(m -> scopeIs(m, 2L)));
    }

    @Test
    void ordinaryStaffCanExportTheAuthorizedStoreUnderCurrentPermissions()
    {
        ctxA.role = "staff";
        me.getStaffGrants().get(0).setRoleCode("staff");
        assertEquals(true, service.logs(ctxA, 1L, null, null, null).get("canExport"));
        service.exportCsv(ctxA, 1L, null, null, null);
        verify(db, times(2)).list(contains("from kg_dispatch_log"), argThat(m -> scopeIs(m, 1L)));
    }

    @Test
    void selectedStoreWithoutExportRightIsRejected()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.exportCsv(ctxA, 1L, null, null, null));
        assertEquals(Integer.valueOf(403), e.getCode());
        verify(db, never()).list(contains("from kg_dispatch_log"), anyMap());
    }

    @Test
    void allStoresWithoutAnyExportRightIsRejected()
    {
        me.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grant(1L, "A店", "keeper"), grant(3L, "C店", "keeper"))));
        ServiceException e = assertThrows(ServiceException.class, () -> service.exportCsv(ctxA, null, null, null, null));
        assertEquals(Integer.valueOf(403), e.getCode());
        verify(db, never()).list(contains("from kg_dispatch_log"), anyMap());
    }

    @Test
    void logListCanExportFollowsTheSelectedStore()
    {
        // 「全部门店」：至少一家（B 店）能导出
        assertEquals(true, service.logs(ctxA, null, null, null, null).get("canExport"));
        // 选了 A 店（管家）：不能导出，哪怕账号在别的门店是主理人
        assertEquals(false, service.logs(ctxA, 1L, null, null, null).get("canExport"));
        // 选了 B 店（主理人）：能导出，哪怕第一家授权门店的角色不能导出
        assertEquals(true, service.logs(ctxA, 2L, null, null, null).get("canExport"));
        verify(db).list(contains("from kg_dispatch_log"), argThat(m -> scopeIs(m, 1L, 2L, 3L)));
    }

    @Test
    void boardStoreCardsAndTopStoresCarryStoreId()
    {
        when(db.list(contains("from kg_job j join kg_store s"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(
                p("jobId", 1L, "storeId", 2L, "storeName", "B店", "city", "平潭", "jobType", "volunteer", "headcount", 3, "hiredCount", 1,
                        "urgentLevel", 0, "beginDate", "2026-10-01"))));
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(p("city", "平潭"));
        Map<String, Object> out = service.board(ctxA, "store", null, "2026-10-03", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) out.get("cards");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) out.get("topStores");
        assertEquals(2L, cards.get(0).get("storeId"));
        assertEquals(2L, top.get(0).get("storeId"));
    }

    // ---------------------------------------------------------------- 控制器：「全部门店」不再拿第一家门店的角色判导出权

    private AppStaffOpsController controller()
    {
        AppStaffOpsController c = new AppStaffOpsController();
        KgDispatchService dispatch = mock(KgDispatchService.class);
        ReflectionTestUtils.setField(c, "support", support);
        ReflectionTestUtils.setField(c, "dispatch", dispatch);
        when(support.ctx(null)).thenReturn(ctxA);
        when(dispatch.canExport(ctxA, null)).thenReturn(true);
        when(dispatch.exportCsv(any(), any(), any(), any(), any())).thenReturn(p("count", 0));
        return c;
    }

    @Test
    void controllerAllStoresSkipsFirstStoreRoleCheckWhenAnyStoreCanExport()
    {
        AppStaffOpsController c = controller();
        c.exportLogs(null, null, null, null);
        verify(support, never()).need(any(), anyString(), anyString(), anyString(), anyString());
        KgDispatchService dispatch = (KgDispatchService) ReflectionTestUtils.getField(c, "dispatch");
        verify(dispatch).exportCsv(same(ctxA), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void controllerSelectedStoreChecksThatStoresRole()
    {
        AppStaffOpsController c = controller();
        c.exportLogs(2L, null, null, null);
        verify(support).need(same(ctxB), eq("dispatch"), eq(StaffPerms.EXPORT), eq("dispatch_export"), eq("数据导出"));
        KgDispatchService dispatch = (KgDispatchService) ReflectionTestUtils.getField(c, "dispatch");
        verify(dispatch).exportCsv(same(ctxB), eq(2L), isNull(), isNull(), isNull());
    }

    @Test
    void controllerAllStoresWithoutAnyExportRightIsDeniedAndLogged()
    {
        AppStaffOpsController c = controller();
        KgDispatchService dispatch = (KgDispatchService) ReflectionTestUtils.getField(c, "dispatch");
        when(dispatch.canExport(ctxA, null)).thenReturn(false);
        doThrow(new ServiceException("当前角色没有权限", 403)).when(support).need(same(ctxA), eq("dispatch"), eq(StaffPerms.EXPORT), anyString(), anyString());
        assertThrows(ServiceException.class, () -> c.exportLogs(null, null, null, null));
        verify(dispatch, never()).exportCsv(any(), any(), any(), any(), any());
    }
}
