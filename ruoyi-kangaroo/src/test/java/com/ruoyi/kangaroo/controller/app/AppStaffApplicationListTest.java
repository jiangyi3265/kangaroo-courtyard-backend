package com.ruoyi.kangaroo.controller.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.parser.OrderByParser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgPointsService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 员工端报名列表：岗位类型页签与排序都真的落到查询上；排序只认白名单。 */
class AppStaffApplicationListTest
{
    private final IKgApplicationService apps = mock(IKgApplicationService.class);
    private final AppStaffController controller = new AppStaffController();
    private final AtomicReference<KgApplication> query = new AtomicReference<KgApplication>();
    private final AtomicReference<String> orderBy = new AtomicReference<String>();
    private final KgPointsService points = mock(KgPointsService.class);
    private List<KgApplication> found = new ArrayList<KgApplication>();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(controller, "kgApplicationService", apps);
        // 列表按门店角色决定是否只看线上任务：之前这里的 mock 返回 null，取 .role 直接空指针，三条用例一直是红的
        StaffSupport support = mock(StaffSupport.class);
        StaffSupport.Ctx ctx = new StaffSupport.Ctx();
        ctx.storeId = 102L; ctx.role = "staff";
        when(support.ctxForStore(any())).thenReturn(ctx);
        ReflectionTestUtils.setField(controller, "staffSupport", support);
        ReflectionTestUtils.setField(controller, "points", points);
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(102L); g.setRoleCode("staff"); g.setActive(true);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(1L); me.setStaffGrants(Collections.singletonList(g));
        AppUserContext.set(me);
        // 真实环境里 PageHelper 拦截器在执行 SQL 时读取线程上的分页参数；这里在服务被调用的那一刻把它取出来
        when(apps.selectKgApplicationList(any())).thenAnswer(inv -> {
            query.set(inv.getArgument(0));
            Page<?> page = PageHelper.getLocalPage();
            orderBy.set(page == null ? null : page.getOrderBy());
            return found;
        });
    }

    @AfterEach
    void cleanup()
    {
        PageHelper.clearPage();
        AppUserContext.remove();
    }

    @Test
    void jobTypeTabFiltersTheQuery()
    {
        controller.applications(102L, null, null, null, "volunteer", null, 1, 20);
        assertEquals("volunteer", query.get().getJobType());
        assertEquals(102L, query.get().getStoreId());
        // 之前默认排序不设 orderBy、沿用 SQL 的最新在前；现在加急的待审核报名排最前，其后仍是最新在前
        assertEquals("(a.urgent_flag = '1' and a.status = 'submitted') desc, a.app_id desc", orderBy.get());
        verifyNoInteractions(points);
    }

    @Test
    void waitSortOrdersByLongestWaiting()
    {
        controller.applications(102L, null, null, null, null, "wait", 1, 20);
        assertEquals("(a.urgent_flag = '1' and a.status = 'submitted') desc, a.submit_time asc, a.app_id asc", orderBy.get());
    }

    @Test
    void urgentRowsAreMarkedThroughOneLookupPerPage()
    {
        KgApplication a = new KgApplication();
        a.setAppId(9L); a.setStoreId(102L); a.setStatus("submitted");
        found = Collections.singletonList(a);
        controller.applications(102L, null, null, null, null, null, 1, 20);
        verify(points).markUrgent(argThat(rows -> rows.size() == 1 && Long.valueOf(9).equals(rows.get(0).get("appId"))));
    }

    @Test
    void urgentFirstOrderReplacesTheListSqlOrder()
    {
        String sql = "select a.app_id, a.status from kg_application a left join kg_job j on j.job_id = a.job_id "
                + "WHERE a.del_flag = '0' and a.store_id = ? order by a.app_id desc";
        String out = OrderByParser.converToOrderBySql(sql, AppStaffController.SORT_DEFAULT_ORDER);
        assertTrue(out.endsWith("order by " + AppStaffController.SORT_DEFAULT_ORDER), out);
        assertEquals(1, out.toLowerCase().split("order by", -1).length - 1, out);
    }

    @Test
    void unknownSortIsRejectedBeforeQuerying()
    {
        ServiceException e = assertThrows(ServiceException.class,
                () -> controller.applications(102L, null, null, null, null, "a.real_name; drop table x", 1, 20));
        assertEquals(Integer.valueOf(422), e.getCode());
        verify(apps, never()).selectKgApplicationList(any());
    }

    @Test
    void orderByReplacesTheListSqlOrderInsteadOfAppending()
    {
        // 与 KgApplicationMapper.selectKgApplicationList 生成的 SQL 同形：PageHelper 解析失败时会退化成直接拼接，
        // 结果是两个 order by、MySQL 报错；这里确认能被正常解析并替换
        String sql = "select a.app_id, a.app_no, a.submit_time, case when j.del_flag = '0' then j.title else null end as job_title, "
                + "j.job_type, s.store_name as store_name, u.nickname as nickname from kg_application a "
                + "left join kg_job j on j.job_id = a.job_id left join kg_store s on s.store_id = a.store_id and s.del_flag = '0' "
                + "left join kg_app_user u on u.user_id = a.app_user_id and u.del_flag = '0' "
                + "WHERE a.del_flag = '0' and a.store_id = ? and j.job_type = ? and a.status = ? "
                + "and a.submit_time >= ? and a.submit_time < ? order by a.app_id desc";
        String out = OrderByParser.converToOrderBySql(sql, AppStaffController.SORT_WAIT_ORDER);
        assertTrue(out.endsWith("order by a.submit_time asc, a.app_id asc"), out);
        assertFalse(out.toLowerCase().contains("a.app_id desc"), out);
    }
    @Test
    void taskFilterKeepsTheAuthorizedStoreScope()
    {
        controller.applications(102L, 10026L, "submitted", null, "online", null, 1, 20);
        assertEquals(10026L, query.get().getJobId());
        assertEquals(102L, query.get().getStoreId());
        assertEquals("submitted", query.get().getStatus());
    }

    @Test
    void taskIdCannotAuthorizeAnotherStore()
    {
        ServiceException e = assertThrows(ServiceException.class,
                () -> controller.applications(999L, 10026L, "submitted", null, "online", null, 1, 20));
        assertEquals(Integer.valueOf(403), e.getCode());
        verify(apps, never()).selectKgApplicationList(any());
    }

    @Test
    void invalidTaskIdIsRejected()
    {
        assertThrows(ServiceException.class,
                () -> controller.applications(102L, -1L, null, null, "online", null, 1, 20));
        verify(apps, never()).selectKgApplicationList(any());
    }

}
