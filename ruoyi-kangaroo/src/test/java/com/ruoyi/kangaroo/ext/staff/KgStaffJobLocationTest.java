package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 门店发布线下岗位时的工作地点（V42）：地图选点存坐标、城市跟着点走；坐标不对 422；不选照旧用门店地址（门店有坐标一并带上）；
 * 手填别处地址不存坐标；线上任务仍不能从这里发。以及「修改工作地点」：只改本店线下岗位、手填清坐标、记操作日志（不连库）。
 */
class KgStaffJobLocationTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final KgStaffJobService service = new KgStaffJobService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> updated = new AtomicReference<>();
    private Map<String, Object> store;

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "cities", cities);
        ctx.storeId = 102L; ctx.role = "manager"; ctx.me = new AppLoginUser(); ctx.me.setUserId(9001L); ctx.me.setNickname("店长");
        store = p("storeName", "平潭袋鼠小院", "city", "平潭", "address", "福建省平潭县东湖山庄98号", "cover", "/c.jpg",
                "latitude", new BigDecimal("25.503000"), "longitude", new BigDecimal("119.791000"), "intro", "");
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenAnswer(inv -> store);
        when(db.insert(eq("kg_job"), anyMap())).thenAnswer(inv -> { saved.set(inv.getArgument(1)); return 99L; });
        when(db.updateRow(eq("kg_job"), eq("job_id"), any(), anyMap())).thenAnswer(inv -> { updated.set(inv.getArgument(3)); return 1; });
        // 离线行政区定位：南舍庄园那个点落在「福州」；别的点算不出来
        when(cities.locate(anyDouble(), anyDouble(), any(), anyString())).thenReturn(p("found", false));
        when(cities.locate(eq(25.6), eq(119.3), any(), eq("gcj02"))).thenReturn(p("found", true, "name", "福州"));
    }

    private static Map<String, Object> input(Object... extra)
    {
        Map<String, Object> m = p("images", java.util.Collections.singletonList(PHOTO), "jobType", "volunteer", "startDate", "2026-10-20", "pay", "board", "headcount", 2,
                "title", "南舍庄园直播换工", "intro", "介绍", "work", "直播", "hope", "认真负责");
        for (int i = 0; i + 1 < extra.length; i += 2) m.put((String) extra[i], extra[i + 1]);
        return m;
    }

    private int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode() == null ? 500 : e.getCode();
    }

    // ---------------------------------------------------------------- 发布

    @Test
    void pickedPointIsSavedWithItsCity()
    {
        service.create(ctx, input("address", "福州市闽侯县南舍庄园", "latitude", 25.6, "longitude", "119.3"));
        Map<String, Object> row = saved.get();
        assertEquals("福州市闽侯县南舍庄园", row.get("address"));
        assertEquals("福州", row.get("city"), "城市跟着选的点走");
        assertEquals(new BigDecimal("25.600000"), row.get("latitude"));
        assertEquals(new BigDecimal("119.300000"), row.get("longitude"));
        verify(cities).locate(25.6, 119.3, 0d, "gcj02");
    }

    @Test
    void pointOutsideKnownCitiesFallsBackToStoreCity()
    {
        service.create(ctx, input("address", "海上某处", "latitude", "24.123456789", "longitude", "120.5"));
        Map<String, Object> row = saved.get();
        assertEquals("平潭", row.get("city"));
        assertEquals(new BigDecimal("24.123457"), row.get("latitude"), "保留 6 位小数");
        // 没有城市服务（手工 new 的服务）也一样用门店城市，不报错
        ReflectionTestUtils.setField(service, "cities", null);
        service.create(ctx, input("address", "福州市闽侯县南舍庄园", "latitude", 25.6, "longitude", 119.3));
        assertEquals("平潭", saved.get().get("city"));
    }

    @Test
    void badCoordinatesAreRejectedWith422()
    {
        assertEquals(422, code(() -> service.create(ctx, input("address", "南舍庄园", "latitude", 25.6))), "只给一个");
        assertEquals(422, code(() -> service.create(ctx, input("address", "南舍庄园", "latitude", 25.6, "longitude", null))), "一个为空");
        assertEquals(422, code(() -> service.create(ctx, input("address", "南舍庄园", "latitude", 91, "longitude", 119.3))), "越界");
        assertEquals(422, code(() -> service.create(ctx, input("address", "南舍庄园", "latitude", 25.6, "longitude", 181))), "越界");
        assertEquals(422, code(() -> service.create(ctx, input("address", "南舍庄园", "latitude", 0, "longitude", 0))), "0,0 不是有效位置");
        assertEquals(422, code(() -> service.create(ctx, input("address", "南舍庄园", "latitude", "abc", "longitude", 119.3))), "不是数字");
        assertEquals(422, code(() -> service.create(ctx, input("latitude", 25.6, "longitude", 119.3))), "选了点必须有地址");
        StringBuilder longAddress = new StringBuilder();
        for (int i = 0; i < 256; i++) longAddress.append('址');
        assertEquals(422, code(() -> service.create(ctx, input("address", longAddress.toString(), "latitude", 25.6, "longitude", 119.3))));
        assertEquals(422, code(() -> service.create(ctx, input("address", longAddress.toString()))), "手填也不超过 255 字");
        verify(db, never()).insert(anyString(), anyMap());
        // 生成描述就按同一规则拦，不用等最后提交
        assertEquals(422, code(() -> service.describe(ctx, input("address", "南舍庄园", "latitude", 0, "longitude", 0), 0)));
    }

    @Test
    void withoutAPickTheStoreAddressAndPointAreUsed()
    {
        service.create(ctx, input());
        Map<String, Object> row = saved.get();
        assertEquals("福建省平潭县东湖山庄98号", row.get("address"));
        assertEquals("平潭", row.get("city"));
        assertEquals(new BigDecimal("25.503000"), row.get("latitude"), "门店登记过坐标的一并带上");
        assertEquals(new BigDecimal("119.791000"), row.get("longitude"));
        // 新版小程序没选点时带的是空地址和空坐标，和老版本一样
        service.create(ctx, input("address", "", "latitude", null, "longitude", null));
        assertEquals("福建省平潭县东湖山庄98号", saved.get().get("address"));
        assertEquals(new BigDecimal("25.503000"), saved.get().get("latitude"));
        verifyNoInteractions(cities);
    }

    @Test
    void storeWithoutCoordinatesLeavesTheJobWithoutAPoint()
    {
        store.put("latitude", null);
        store.put("longitude", null);
        service.create(ctx, input());
        assertFalse(saved.get().containsKey("latitude"));
        assertFalse(saved.get().containsKey("longitude"));
        // 门店坐标是坏数据（0,0）也当没有，不拦发布
        store.put("latitude", BigDecimal.ZERO);
        store.put("longitude", BigDecimal.ZERO);
        service.create(ctx, input());
        assertFalse(saved.get().containsKey("latitude"));
    }

    @Test
    void typedAddressWithoutAPointKeepsTheTextAndNoCoordinates()
    {
        service.create(ctx, input("address", "  平潭县君山片区李喵喵民宿  ", "latitude", null, "longitude", null));
        Map<String, Object> row = saved.get();
        assertEquals("平潭县君山片区李喵喵民宿", row.get("address"));
        assertEquals("平潭", row.get("city"), "手填算不出城市，用门店城市");
        assertFalse(row.containsKey("latitude"), "门店的点不能挂到别处的地址上");
    }

    @Test
    void onlineTasksStillCannotBePublishedHere()
    {
        assertEquals(422, code(() -> service.create(ctx, p("images", java.util.Collections.singletonList(PHOTO), "jobType", "online", "startDate", "2026-10-20", "headcount", 1, "title", "剪辑",
                "address", "福州", "latitude", 25.6, "longitude", 119.3))));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void generatedTextUsesThePickedPlace()
    {
        Map<String, Object> d = service.describe(ctx, input("address", "闽侯县南舍庄园", "latitude", 25.6, "longitude", 119.3), 0);
        assertTrue(d.get("title").toString().startsWith("福州"), d.get("title").toString());
        assertTrue(d.get("intro").toString().startsWith("工作地点在福州闽侯县南舍庄园，由平潭袋鼠小院发布招募。"), d.get("intro").toString());
        assertFalse(d.get("intro").toString().contains("我们是位于"), "不能说成本店位于南舍庄园");
        assertTrue(d.get("basis").toString().contains("工作地点：福州 · 闽侯县南舍庄园（地图选点）"), d.get("basis").toString());
        // 选点给的地址已经带了城市：不再在前面重复补一遍
        Map<String, Object> full = service.describe(ctx, input("address", "福建省福州市闽侯县南舍庄园", "latitude", 25.6, "longitude", 119.3), 0);
        assertTrue(full.get("intro").toString().startsWith("工作地点在福建省福州市闽侯县南舍庄园，由平潭袋鼠小院发布招募。"), full.get("intro").toString());
        // 门店有简介时，简介后面补一句工作地点；手填的地址算不出城市，不能把门店城市拼在前面（拼出「平潭闽侯县…」这种错地名）
        store.put("intro", "海边的小院");
        Map<String, Object> withIntro = service.describe(ctx, input("address", "闽侯县南舍庄园"), 0);
        assertTrue(withIntro.get("intro").toString().startsWith("平潭袋鼠小院：海边的小院。工作地点在闽侯县南舍庄园。"), withIntro.get("intro").toString());
        assertTrue(withIntro.get("basis").toString().contains("（手填）"));
        // 没选：和之前一模一样
        store.put("intro", "");
        Map<String, Object> plain = service.describe(ctx, input(), 0);
        assertTrue(plain.get("intro").toString().startsWith("我们是位于平潭福建省平潭县东湖山庄98号的平潭袋鼠小院。"), plain.get("intro").toString());
        assertTrue(plain.get("basis").toString().contains("工作地点：门店地址（福建省平潭县东湖山庄98号）"));
    }

    @Test
    void jobListCarriesTheStoreAddressForTheDefaultHint()
    {
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.one(contains("count(*) total"), anyMap())).thenReturn(p("total", 0));
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(p("safeCert", "0", "address", "福建省平潭县东湖山庄98号",
                "latitude", new BigDecimal("25.503000"), "longitude", new BigDecimal("119.791000")));
        Map<String, Object> out = service.list(ctx, null, null, null);
        assertEquals("福建省平潭县东湖山庄98号", out.get("storeAddress"));
        assertEquals(new BigDecimal("25.503000"), out.get("storeLatitude"));
        assertEquals(new BigDecimal("119.791000"), out.get("storeLongitude"));
        verify(db).list(contains("j.latitude, j.longitude"), anyMap());
    }

    // ---------------------------------------------------------------- 修改工作地点

    private void job(Long storeId, String type)
    {
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 10005L, "storeId", storeId, "title", "南舍庄园招直播换工",
                "jobType", type, "city", "平潭"));
    }

    @Test
    void pickingANewPointUpdatesAddressCoordinatesAndCity()
    {
        job(102L, "volunteer");
        Map<String, Object> out = service.location(ctx, 10005L, p("address", "福州市闽侯县南舍庄园", "latitude", 25.6, "longitude", 119.3));
        Map<String, Object> cols = updated.get();
        assertEquals("福州市闽侯县南舍庄园", cols.get("address"));
        assertEquals(new BigDecimal("25.600000"), cols.get("latitude"));
        assertEquals(new BigDecimal("119.300000"), cols.get("longitude"));
        assertEquals("福州", cols.get("city"));
        assertEquals("staff:9001", cols.get("update_by"));
        assertFalse(cols.containsKey("status"), "不重新审核，状态不动");
        assertEquals("福州", out.get("city"));
        verify(db).must(contains("for update"), anyMap(), anyString());
        verify(support).op(same(ctx), eq("job_location"), eq("修改工作地点"), eq("general"), eq("南舍庄园招直播换工 → 福州市闽侯县南舍庄园"), eq(true), isNull());
    }

    @Test
    void typingAnAddressClearsTheOldPointAndKeepsTheCity()
    {
        job(102L, "parttime");
        service.location(ctx, 10005L, p("address", "平潭县君山片区李喵喵民宿", "latitude", null, "longitude", null));
        Map<String, Object> cols = updated.get();
        assertEquals("平潭县君山片区李喵喵民宿", cols.get("address"));
        assertTrue(cols.containsKey("latitude") && cols.get("latitude") == null, "旧坐标要清掉");
        assertTrue(cols.containsKey("longitude") && cols.get("longitude") == null);
        assertEquals("平潭", cols.get("city"));
        // 改回门店地址：带上门店的点和城市
        service.location(ctx, 10005L, p("address", "福建省平潭县东湖山庄98号"));
        assertEquals(new BigDecimal("25.503000"), updated.get().get("latitude"));
        assertEquals("平潭", updated.get().get("city"));
    }

    @Test
    void locationRefusesOtherStoresOnlineTasksAndBadInput()
    {
        job(200L, "volunteer");
        assertEquals(403, code(() -> service.location(ctx, 10005L, p("address", "南舍庄园", "latitude", 25.6, "longitude", 119.3))), "别家门店的岗位");
        job(102L, "online");
        assertEquals(409, code(() -> service.location(ctx, 10005L, p("address", "南舍庄园", "latitude", 25.6, "longitude", 119.3))), "线上任务没有工作地点");
        job(102L, "volunteer");
        assertEquals(422, code(() -> service.location(ctx, 10005L, p("address", "  "))), "地址必填");
        assertEquals(422, code(() -> service.location(ctx, 10005L, null)));
        assertEquals(422, code(() -> service.location(ctx, 10005L, p("address", "南舍庄园", "latitude", 25.6))));
        assertEquals(422, code(() -> service.location(ctx, 10005L, p("address", "南舍庄园", "latitude", 0, "longitude", 0))));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        verify(support, never()).op(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void closedJobLocationCannotBeChanged()
    {
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 10005L, "storeId", 102L, "title", "南舍庄园招直播换工",
                "jobType", "volunteer", "city", "平潭", "status", "closed"));
        assertEquals(409, code(() -> service.location(ctx, 10005L, p("address", "南舍庄园", "latitude", 25.6, "longitude", 119.3))), "已关闭的岗位");
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        // 暂停 / 草稿还能改
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 10005L, "storeId", 102L, "title", "南舍庄园招直播换工",
                "jobType", "volunteer", "city", "平潭", "status", "paused"));
        service.location(ctx, 10005L, p("address", "福建省平潭县东湖山庄98号"));
        assertEquals("福建省平潭县东湖山庄98号", updated.get().get("address"));
    }

    @Test
    void recruitingMetricUsesTheSameRuleAsTheRecruitingFilter()
    {
        // 「招聘中」数字 = 点「招聘中」筛出来的条数：已上架且不缺人；缺人的单独算在「缺人中」
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.one(anyString(), anyMap())).thenReturn(p("total", 5L, "published", 2L, "shortCnt", 1L, "paused", 1L));
        service.list(ctx, null, null, "recruiting");
        org.mockito.ArgumentCaptor<String> sqls = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).one(sqls.capture(), anyMap());
        String metric = sqls.getAllValues().stream().filter(s -> s.contains("count(*) total")).findFirst().orElse("");
        assertTrue(metric.contains("sum(status = 'published' and not (urgent_level > 0 or (start_date <= date_add(curdate(), interval 7 day) and hired_count < headcount))) published"), metric);
        org.mockito.ArgumentCaptor<String> list = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(db).list(list.capture(), anyMap());
        assertTrue(list.getValue().contains("j.status = 'published' and not (j.urgent_level > 0 or (j.start_date <= date_add(curdate(), interval 7 day) and j.hired_count < j.headcount))"), list.getValue());
    }
}
