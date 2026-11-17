package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;

/** 工作台「报名待审核」：用户花积分加急的报名单独排在最上面一组、带 urgent 标记、小标签显示红色「加急」，其余照旧按截止时间分组 */
class KgWorkbenchUrgentTodoTest
{
    private final KgJdbc db = mock(KgJdbc.class);

    private final StaffSupport support = mock(StaffSupport.class);

    private final KgWorkbenchService service = new KgWorkbenchService();

    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ctx.storeId = 102L;
        ctx.storeName = "平潭测试门店";
        ctx.role = "manager";
        ctx.me = new AppLoginUser();
        ctx.me.setUserId(1L);
        when(support.ctx(102L)).thenReturn(ctx);
        when(support.ctxForStore(102L)).thenReturn(ctx);
    }

    private static String hoursAgo(long hours)
    {
        return StaffSupport.fmt(new Date(System.currentTimeMillis() - hours * 3600000L), "yyyy-MM-dd HH:mm:ss");
    }

    private static Map<String, Object> app(long id, String name, long submittedHoursAgo, String urgentFlag)
    {
        return p("appId", id, "appNo", "KA00000" + id, "realName", name, "submitTime", hoursAgo(submittedHoursAgo), "urgentFlag", urgentFlag, "title", "前台换工");
    }

    @Test
    @SuppressWarnings("unchecked")
    void urgentApplicationsComeFirstInTheirOwnGroupAndCarryTheFlag()
    {
        // 50 小时前提交的普通报名已经超过 48 小时平台时限；1 小时前提交的加急报名时限还早，之前会排在它后面
        when(db.list(contains("a.status = 'submitted'"), anyMap())).thenReturn(Arrays.asList(app(1L, "老王", 50, "0"), app(2L, "小李", 1, "1")));
        Map<String, Object> out = service.todos(102L, null, null);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) out.get("groups");
        assertEquals("加急报名", groups.get(0).get("title"));
        assertEquals(true, groups.get(0).get("urgent"));
        List<Map<String, Object>> urgent = (List<Map<String, Object>>) groups.get(0).get("items");
        assertEquals(1, urgent.size());
        assertEquals(2L, urgent.get(0).get("bizId"));
        assertEquals(true, urgent.get(0).get("urgent"));
        assertEquals("加急", urgent.get(0).get("chip"), "工作台页右侧的红色小标签显示「加急」");
        assertEquals("red", urgent.get(0).get("chipTone"));
        assertEquals("已逾期", groups.get(1).get("title"));
        assertEquals(false, groups.get(1).get("urgent"));
        Map<String, Object> plain = ((List<Map<String, Object>>) groups.get(1).get("items")).get(0);
        assertEquals(1L, plain.get("bizId"));
        assertEquals(false, plain.get("urgent"));
        assertEquals("待审核", plain.get("chip"));
        assertEquals(1, out.get("overdue"), "逾期条数照旧按截止时间算");
        verify(db).list(contains("a.urgent_flag, j.title from kg_application a"), eq(p("s", 102L, "onlineAllowed", 0, "onlineOnly", 0)));
        verify(db).list(contains("order by a.urgent_flag desc, a.submit_time"), eq(p("s", 102L, "onlineAllowed", 0, "onlineOnly", 0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutUrgentApplicationsTheGroupsStayAsBefore()
    {
        when(db.list(contains("a.status = 'submitted'"), anyMap())).thenReturn(Arrays.asList(app(1L, "老王", 50, "0"), app(3L, "小张", 2, "0")));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) service.todos(102L, null, null).get("groups");
        assertEquals(2, groups.size());
        assertEquals("已逾期", groups.get(0).get("title"));
        assertEquals("本周到期", groups.get(1).get("title"));
        for (Map<String, Object> g : groups)
        {
            assertEquals(false, g.get("urgent"));
        }
    }
}
