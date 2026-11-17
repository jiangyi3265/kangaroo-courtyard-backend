package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 给已发岗位补填筛选条件（10-16 跟进）：和发布同一套白名单与收口规则（JobFilterFields）、只改四列和更新时间、重复提交结果一样、
 * 线上任务 422、已关闭 409、别家门店 403；员工端岗位列表每行带上当前值、文案和「还没填」提示，顶上给出没填的已上架岗位数（不连库）。
 * 「还没填」= 排班没填，或包住却没填住宿条件（性别、在店没填 = 不限，按任何条件筛都看得到）
 */
class KgStaffJobConditionsTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgStaffJobService service = new KgStaffJobService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final AtomicReference<Map<String, Object>> updated = new AtomicReference<>();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ctx.storeId = 107L; ctx.role = "manager"; ctx.me = new AppLoginUser(); ctx.me.setUserId(9001L); ctx.me.setNickname("店长");
        when(db.updateRow(eq("kg_job"), eq("job_id"), any(), anyMap())).thenAnswer(inv -> { updated.set(inv.getArgument(3)); return 1; });
        job(107L, "volunteer", "all", "published");
    }

    private void job(Long storeId, String type, String board, String status)
    {
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 15057L, "storeId", storeId, "title", "古城咖啡馆帮手",
                "jobType", type, "boardType", board, "status", status));
    }

    private static Map<String, Object> body(Object gender, Object rest, Object room, Object days)
    {
        return p("genderPref", gender, "restMode", rest, "roomType", room, "minDays", days);
    }

    private int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode() == null ? 500 : e.getCode();
    }

    @Test
    void volunteerSavesAllFourWithLabels()
    {
        Map<String, Object> out = service.conditions(ctx, 15057L, body("F", "5-2", "double", 15));
        Map<String, Object> cols = updated.get();
        assertEquals(Arrays.asList("gender_pref", "rest_mode", "room_type", "min_days", "update_by", "update_time"), new ArrayList<>(cols.keySet()),
                "只改四列和更新人、更新时间，不动状态（不用重新审核）");
        assertEquals("F", cols.get("gender_pref"));
        assertEquals("5-2", cols.get("rest_mode"));
        assertEquals("double", cols.get("room_type"));
        assertEquals(15, cols.get("min_days"));
        assertEquals("staff:9001", cols.get("update_by"));
        assertEquals(15057L, out.get("jobId"));
        assertEquals("女", out.get("genderPrefLabel"));
        assertEquals("做五休二", out.get("restModeLabel"));
        assertEquals("双人间", out.get("roomTypeLabel"));
        assertEquals(Boolean.TRUE, out.get("housing"));
        assertEquals(Boolean.FALSE, out.get("conditionsMissing"));
        verify(db).must(contains("for update"), anyMap(), anyString());
        verify(support).op(same(ctx), eq("job_conditions"), eq("补填筛选条件"), eq("general"), eq("古城咖啡馆帮手 → 女、做五休二、双人间、最短 15 天"), eq(true), isNull());
    }

    @Test
    void chineseLabelsAreAcceptedLikePublish()
    {
        Map<String, Object> out = service.conditions(ctx, 15057L, body("男", "做六休一", "多人间", "30"));
        assertEquals("M", updated.get().get("gender_pref"));
        assertEquals("6-1", updated.get().get("rest_mode"));
        assertEquals("multi", updated.get().get("room_type"));
        assertEquals(30, updated.get().get("min_days"));
        assertEquals("男", out.get("genderPrefLabel"));
    }

    @Test
    void outOfScopeValuesAreCoercedLikePublish()
    {
        // 兼职、不包住：性别偏好、房型都存 ''（招聘不能写性别限制；不包住没有房型），排班和在店照存
        job(107L, "parttime", "none", "published");
        Map<String, Object> out = service.conditions(ctx, 15057L, body("F", "3-2", "single", 7));
        assertEquals("", updated.get().get("gender_pref"));
        assertEquals("", updated.get().get("room_type"));
        assertEquals("3-2", updated.get().get("rest_mode"));
        assertEquals(7, updated.get().get("min_days"));
        assertEquals("", out.get("genderPrefLabel"), "兼职没有性别偏好这一项");
        assertEquals(Boolean.FALSE, out.get("housing"));
        // 全职包住宿：房型留着
        job(107L, "fulltime", "room", "paused");
        service.conditions(ctx, 15057L, body("M", "6-1", "multi", null));
        assertEquals("", updated.get().get("gender_pref"));
        assertEquals("multi", updated.get().get("room_type"));
        assertNull(updated.get().get("min_days"));
        assertTrue(updated.get().containsKey("min_days"), "不限 = 写回 null（清掉旧值）");
    }

    @Test
    void emptyValuesClearEverythingAndRepeatGivesTheSameResult()
    {
        Map<String, Object> a = service.conditions(ctx, 15057L, body("", "不限", "", 0));
        Map<String, Object> first = updated.get();
        assertEquals("", first.get("gender_pref"));
        assertEquals("", first.get("rest_mode"));
        assertNull(first.get("min_days"));
        assertEquals("男女不限", a.get("genderPrefLabel"));
        assertEquals(Boolean.TRUE, a.get("conditionsMissing"), "排班、房型都空着 = 还没填");
        // 只填排班不填房型（换宿包住）：还算缺；填上房型就不缺了，性别和在店空着没关系
        assertEquals(Boolean.TRUE, service.conditions(ctx, 15057L, body("", "5-2", "", null)).get("conditionsMissing"));
        assertEquals(Boolean.FALSE, service.conditions(ctx, 15057L, body("", "5-2", "single", null)).get("conditionsMissing"));
        // 没传的按没填（整体覆盖）
        service.conditions(ctx, 15057L, p());
        assertEquals("", updated.get().get("rest_mode"));
        // 同样的请求提交两次，存的、返回的都一样
        Map<String, Object> x = service.conditions(ctx, 15057L, body("F", "1-1", "single", 20));
        Map<String, Object> xCols = new java.util.LinkedHashMap<>(updated.get());
        Map<String, Object> y = service.conditions(ctx, 15057L, body("F", "1-1", "single", 20));
        assertEquals(x, y);
        xCols.remove("update_time");
        Map<String, Object> yCols = new java.util.LinkedHashMap<>(updated.get());
        yCols.remove("update_time");
        assertEquals(xCols, yCols);
    }

    @Test
    void invalidValuesAreRejectedWith422AndNothingIsSaved()
    {
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, body("X", "", "", null))), "性别");
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, body("", "7-0", "", null))), "排班");
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, body("", "", "suite", null))), "房型");
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, body("", "", "", 366))), "在店天数上限");
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, body("", "", "", -1))));
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, body("", "", "", "两周"))));
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, body(1, "", "", null))), "不是字符串");
        // 兼职填了不认识的性别值也拦（先校验再收口，和发布一样）
        job(107L, "parttime", "none", "published");
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, body("X", "", "", null))));
        assertEquals(422, code(() -> service.conditions(ctx, 15057L, null)), "没有请求体");
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        verify(support, never()).op(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void onlineClosedAndOtherStoresAreRefused()
    {
        job(107L, "online", "none", "published");
        ServiceException online = assertThrows(ServiceException.class, () -> service.conditions(ctx, 15057L, body("", "5-2", "", 7)));
        assertEquals(Integer.valueOf(422), online.getCode());
        assertEquals("线上任务没有这些条件", online.getMessage());
        job(107L, "volunteer", "all", "closed");
        assertEquals(409, code(() -> service.conditions(ctx, 15057L, body("", "5-2", "", 7))), "已关闭，和改工作地点一致");
        job(200L, "volunteer", "all", "published");
        assertEquals(403, code(() -> service.conditions(ctx, 15057L, body("", "5-2", "", 7))), "别家门店的岗位");
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        // 已删除的岗位查不到（del_flag = '0'），和改工作地点同一句
        verify(db, atLeastOnce()).must(contains("del_flag = '0'"), anyMap(), eq("岗位不存在"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void staffListRowsCarryConditionsAndTheMissingHint()
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(p("jobId", 1L, "jobType", "volunteer", "status", "published", "boardType", "all", "genderPref", "", "restMode", "", "roomType", "", "minDays", null,
                "cover", "", "address", ""));
        rows.add(p("jobId", 2L, "jobType", "volunteer", "status", "published", "boardType", "all", "genderPref", "F", "restMode", "5-2", "roomType", "single", "minDays", 30,
                "cover", "", "address", ""));
        rows.add(p("jobId", 3L, "jobType", "parttime", "status", "paused", "boardType", "none", "genderPref", "", "restMode", "", "roomType", "", "minDays", 0,
                "cover", "", "address", ""));
        rows.add(p("jobId", 4L, "jobType", "online", "status", "published", "boardType", "none", "genderPref", "", "restMode", "", "roomType", "", "minDays", null,
                "cover", "", "address", ""));
        rows.add(p("jobId", 5L, "jobType", "fulltime", "status", "closed", "boardType", "none", "genderPref", "", "restMode", "", "roomType", "", "minDays", null,
                "cover", "", "address", ""));
        // 10-16 复查：只看会让岗位被筛漏的两项——排班没填，或包住却没填住宿条件；性别、在店没填 = 不限，不算缺
        rows.add(p("jobId", 6L, "jobType", "volunteer", "status", "published", "boardType", "all", "genderPref", "", "restMode", "5-2", "roomType", "", "minDays", 30,
                "cover", "", "address", ""));
        rows.add(p("jobId", 7L, "jobType", "parttime", "status", "published", "boardType", "none", "genderPref", "", "restMode", "6-1", "roomType", "", "minDays", null,
                "cover", "", "address", ""));
        rows.add(p("jobId", 8L, "jobType", "volunteer", "status", "draft", "boardType", "all", "genderPref", "", "restMode", "1-1", "roomType", "multi", "minDays", null,
                "cover", "", "address", ""));
        rows.add(p("jobId", 9L, "jobType", "fulltime", "status", "published", "boardType", "room", "genderPref", "", "restMode", "", "roomType", "single", "minDays", 30,
                "cover", "", "address", ""));
        when(db.list(anyString(), anyMap())).thenReturn(rows);
        when(db.one(contains("count(*) total"), anyMap())).thenReturn(p("total", 5));
        when(db.one(contains("missing_cnt"), anyMap())).thenReturn(p("jobId", 1L, "title", "古城咖啡馆帮手", "jobType", "volunteer", "boardType", "all",
                "status", "published", "missingCnt", 3L));
        Map<String, Object> out = service.list(ctx, null, null, null);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), anyMap());
        assertTrue(sql.getValue().contains("j.board_type, j.gender_pref, j.rest_mode, j.room_type, j.min_days"), sql.getValue());
        Map<String, Object> r1 = rows.get(0), r2 = rows.get(1), r3 = rows.get(2), r4 = rows.get(3), r5 = rows.get(4);
        assertEquals(Boolean.TRUE, rows.get(5).get("conditionsMissing"), "换宿填了排班和在店，住宿条件空着：按房型筛看不到");
        assertEquals(Boolean.FALSE, rows.get(6).get("conditionsMissing"), "兼职不包住，填了排班就够了（性别、在店没填 = 不限）");
        assertEquals(Boolean.FALSE, rows.get(7).get("conditionsMissing"), "排班、房型都填了，性别、在店不限也不算缺");
        assertEquals(Boolean.TRUE, rows.get(8).get("conditionsMissing"), "全职包住填了房型和在店，排班空着：按排班筛看不到");
        assertEquals(Boolean.TRUE, r1.get("conditionsMissing"));
        assertEquals("男女不限", r1.get("genderPrefLabel"));
        assertEquals(Boolean.TRUE, r1.get("housing"));
        assertEquals(Boolean.FALSE, r2.get("conditionsMissing"));
        assertEquals("女", r2.get("genderPrefLabel"));
        assertEquals("做五休二", r2.get("restModeLabel"));
        assertEquals("单人间", r2.get("roomTypeLabel"));
        assertEquals(30, r2.get("minDays"));
        assertEquals(Boolean.TRUE, r3.get("conditionsMissing"), "排班没填");
        assertNull(r3.get("minDays"));
        assertEquals(Boolean.FALSE, r3.get("housing"));
        assertEquals(Boolean.FALSE, r4.get("conditionsMissing"), "线上任务没有这些条件");
        assertEquals(Boolean.FALSE, r5.get("conditionsMissing"), "已关闭的不再提示");
        assertEquals(3, out.get("conditionsMissingCount"));
        Map<String, Object> first = (Map<String, Object>) out.get("conditionsMissingJob");
        assertEquals(1L, first.get("jobId"));
        assertEquals("volunteer", first.get("jobType"));
        assertEquals(Boolean.TRUE, first.get("housing"));
        // 横幅只数已上架的线下岗位里排班没填、或包住却没填房型的（不受列表上的类型 / 关键词 / 状态筛选影响）
        ArgumentCaptor<String> one = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).one(one.capture(), anyMap());
        String miss = one.getAllValues().stream().filter(s -> s.contains("missing_cnt")).findFirst().orElse("");
        assertTrue(miss.contains("m.status = 'published' and m.job_type <> 'online' and (m.rest_mode = '' or ((m.job_type = 'volunteer' or m.board_type in ('all', 'room')) and m.room_type = ''))"), miss);
        assertTrue(miss.contains("j.status = 'published' and j.job_type <> 'online' and (j.rest_mode = '' or ((j.job_type = 'volunteer' or j.board_type in ('all', 'room')) and j.room_type = ''))"), miss);
        assertFalse(miss.contains("gender_pref") || miss.contains("min_days"), "性别、在店没填 = 不限，不算缺");
        assertTrue(miss.contains("j.store_id = :s") && !miss.contains(":t") && !miss.contains(":kw"), miss);
    }

    @Test
    void noMissingJobsMeansNoBanner()
    {
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        when(db.one(contains("count(*) total"), anyMap())).thenReturn(p("total", 0));
        Map<String, Object> out = service.list(ctx, "volunteer", "咖啡", "recruiting");
        assertEquals(0, out.get("conditionsMissingCount"));
        assertNull(out.get("conditionsMissingJob"));
    }
}
