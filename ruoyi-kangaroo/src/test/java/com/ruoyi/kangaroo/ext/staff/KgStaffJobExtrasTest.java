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

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/**
 * 招募表单新字段（V66）在员工端的读写：老版本小程序提交发布、完善草稿都不带新键，新列一个都不能出现在要写的列里（红线）；新版显式传空才清空；
 * 已发岗位补资料（GET / PUT extras）的把关同换岗位照片：线上 422、已关闭 409、别家门店 403、合作商家上架后 403。
 */
class KgStaffJobExtrasTest
{
    private static final String PHOTO = "/profile/upload/2026/10/31/p.jpg";
    private static final String A = "/profile/upload/2026/10/31/a1.jpg";
    private static final String VIDEO = "/profile/upload/videos/2026/10/31/0123456789abcdef0123456789abcdef.mp4";
    private static final List<String> NEW_COLUMNS = Arrays.asList("meal_allowance", "meal_allowance_unit", "pet_status", "pet_images", "lodging_images", "lodging_video", "lodging_video_cover");

    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgVideoUploads videos = mock(KgVideoUploads.class);
    private final KgStaffJobService service = new KgStaffJobService();
    private UploadedImageFixture files;

    @BeforeEach
    void setup()
    {
        files = new UploadedImageFixture(PHOTO, A);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "videos", videos);
        when(videos.exists(VIDEO)).thenReturn(true);
        when(db.must(contains("from kg_store"), anyMap(), anyString())).thenReturn(p("storeName", "平潭袋鼠小院", "city", "平潭", "address", "东湖山庄98号", "cover", "", "latitude", null, "longitude", null));
        when(db.insert(eq("kg_job"), anyMap())).thenReturn(88L);
        when(db.updateRow(eq("kg_job"), eq("job_id"), any(), anyMap())).thenReturn(1);
    }

    @AfterEach
    void cleanup()
    {
        files.close();
    }

    private static StaffSupport.Ctx ctx(long store, String role)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = new AppLoginUser();
        c.me.setUserId(9001L);
        c.me.setNickname("店长");
        c.storeId = store;
        c.storeName = "店" + store;
        c.role = role;
        return c;
    }

    /** 2026.10.31.2 发布换工时带的那些键（抄自线上小程序的 jobPayload） */
    private static Map<String, Object> oldClientForm(String type)
    {
        Map<String, Object> in = new HashMap<String, Object>();
        in.put("jobType", type);
        in.put("title", "海边青旅前台");
        in.put("work", "前台接待");
        in.put("hope", "开朗");
        in.put("startDate", "2026-12-01");
        in.put("headcount", 2);
        in.put("boardEnabled", Boolean.TRUE);
        in.put("salaryEnabled", Boolean.FALSE);
        in.put("boardType", "volunteer".equals(type) ? "room" : "room");
        in.put("dailyHours", "每天 10 小时");
        in.put("images", Collections.singletonList(PHOTO));
        return in;
    }

    private Map<String, Object> insertedRow()
    {
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_job"), c.capture());
        return c.getValue();
    }

    private Map<String, Object> updatedCols(Long id)
    {
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_job"), eq("job_id"), eq(id), c.capture());
        return c.getValue();
    }

    private static int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode() == null ? 500 : e.getCode();
    }

    // ---------------- 发布 ----------------

    @Test
    void oldClientPublishWritesNoneOfTheNewColumns()
    {
        service.create(ctx(1, StaffRole.MANAGER), oldClientForm("volunteer"));
        Map<String, Object> row = insertedRow();
        for (String col : NEW_COLUMNS)
        {
            assertFalse(row.containsKey(col), col);
        }
        assertEquals("每天 10 小时", row.get("daily_hours"), "老版本还在提交 10 小时，服务端不拒（R1 只改前端选项）");
    }

    @Test
    void newClientPublishValidatesAndStores()
    {
        Map<String, Object> in = oldClientForm("volunteer");
        in.put("mealAllowance", "30");
        in.put("mealAllowanceUnit", "month");
        in.put("petStatus", "yes");
        in.put("petImages", Arrays.asList(A));
        in.put("lodgingImages", Arrays.asList(A));
        in.put("lodgingVideo", VIDEO);
        in.put("lodgingVideoCover", A);
        service.create(ctx(1, StaffRole.MANAGER), in);
        Map<String, Object> row = insertedRow();
        assertEquals("30", row.get("meal_allowance"));
        assertEquals("month", row.get("meal_allowance_unit"));
        assertEquals("yes", row.get("pet_status"));
        assertEquals("[\"" + A + "\"]", row.get("pet_images"));
        assertEquals("[\"" + A + "\"]", row.get("lodging_images"));
        assertEquals(VIDEO, row.get("lodging_video"));
        assertEquals(A, row.get("lodging_video_cover"));
    }

    @Test
    void publishFollowsTheBoardTheFormChose()
    {
        Map<String, Object> in = oldClientForm("parttime");
        in.put("boardType", "all");
        in.put("mealAllowance", "30");
        in.put("mealAllowanceUnit", "day");
        in.put("petStatus", "yes");
        in.put("lodgingImages", Arrays.asList(A));
        service.create(ctx(1, StaffRole.MANAGER), in);
        Map<String, Object> row = insertedRow();
        assertEquals("", row.get("meal_allowance"), "包吃包住没有餐补");
        assertEquals("", row.get("pet_status"), "宠物只给换工");
        assertEquals("[\"" + A + "\"]", row.get("lodging_images"), "包吃包住也包住");
    }

    @Test
    void badValuesAreRefusedBeforeAnythingIsWritten()
    {
        Map<String, Object> in = oldClientForm("volunteer");
        in.put("mealAllowance", "很多");
        in.put("mealAllowanceUnit", "day");
        assertEquals(422, code(() -> service.create(ctx(1, StaffRole.MANAGER), in)));
        verify(db, never()).insert(eq("kg_job"), anyMap());
        in.remove("mealAllowance");
        in.put("lodgingVideo", "https://evil.example/v.mp4");
        assertEquals(422, code(() -> service.create(ctx(1, StaffRole.MANAGER), in)));
        verify(db, never()).insert(eq("kg_job"), anyMap());
    }

    // ---------------- 换工主理人完善派给他的草稿（红线：老版本不带键不能清空） ----------------

    private void draft(String type)
    {
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(p("jobId", 77L, "storeId", 1L, "jobType", type, "status", "draft"));
    }

    @Test
    void oldClientEditingADraftKeepsEveryNewColumn()
    {
        draft("volunteer");
        Map<String, Object> in = oldClientForm("volunteer");
        // 把食宿改成包吃包住、还不带新键：库里的餐补也不能被清
        in.put("boardType", "all");
        service.updateMerchantDraft(ctx(1, StaffRole.MANAGER), 77L, in);
        Map<String, Object> cols = updatedCols(77L);
        for (String col : NEW_COLUMNS)
        {
            assertFalse(cols.containsKey(col), col + " 不能出现在 update 的列里");
        }
        assertEquals("all", cols.get("board_type"));
    }

    @Test
    void newClientEditingADraftClearsOnlyWhatItSendsEmpty()
    {
        draft("volunteer");
        Map<String, Object> in = oldClientForm("volunteer");
        in.put("petStatus", "");
        in.put("petImages", new ArrayList<String>());
        in.put("mealAllowance", "");
        in.put("mealAllowanceUnit", "day");
        service.updateMerchantDraft(ctx(1, StaffRole.MANAGER), 77L, in);
        Map<String, Object> cols = updatedCols(77L);
        assertEquals("", cols.get("pet_status"));
        assertEquals("", cols.get("pet_images"));
        assertEquals("", cols.get("meal_allowance"));
        assertEquals("", cols.get("meal_allowance_unit"));
        assertFalse(cols.containsKey("lodging_images"), "没带的键不动");
        assertFalse(cols.containsKey("lodging_video"));
        assertFalse(cols.containsKey("lodging_video_cover"));
    }

    @Test
    void draftEditUsesTheJobsOwnTypeNotTheOneInTheBody()
    {
        draft("parttime");
        Map<String, Object> in = oldClientForm("volunteer");
        in.put("petStatus", "yes");
        service.updateMerchantDraft(ctx(1, StaffRole.MANAGER), 77L, in);
        assertEquals("", updatedCols(77L).get("pet_status"), "岗位类型不能改，兼职没有宠物");
    }

    // ---------------- 已发岗位补资料 ----------------

    private Map<String, Object> jobRow(long store, String type, String board, String status)
    {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("jobId", 15L);
        m.put("storeId", store);
        m.put("title", "古城咖啡馆帮手");
        m.put("jobType", type);
        m.put("boardType", board);
        m.put("status", status);
        m.put("mealAllowance", "15");
        m.put("mealAllowanceUnit", "day");
        m.put("petStatus", "yes");
        m.put("petImages", "[\"" + A + "\"]");
        m.put("lodgingImages", "");
        m.put("lodgingVideo", null);
        m.put("lodgingVideoCover", null);
        return m;
    }

    private void job(long store, String type, String board, String status)
    {
        when(db.must(contains("from kg_job"), anyMap(), anyString())).thenReturn(jobRow(store, type, board, status));
    }

    @Test
    void extrasGivesStoredValuesAndWhatToShow()
    {
        job(1, "volunteer", "room", "published");
        Map<String, Object> out = service.extras(ctx(1, StaffRole.MANAGER), 15L);
        assertEquals(15L, out.get("jobId"));
        assertEquals("volunteer", out.get("jobType"));
        assertEquals("room", out.get("boardType"));
        assertEquals(Boolean.TRUE, out.get("housing"));
        assertEquals(Boolean.TRUE, out.get("mealShown"));
        assertEquals(Boolean.TRUE, out.get("petShown"));
        assertEquals("15", out.get("mealAllowance"));
        assertEquals(Arrays.asList(A), out.get("petImages"));
        assertEquals(Collections.emptyList(), out.get("lodgingImages"));
        assertEquals("", out.get("lodgingVideo"));
    }

    @Test
    void extrasIsRefusedOnOnlineAndOtherStores()
    {
        job(1, "online", "none", "published");
        assertEquals(422, code(() -> service.extras(ctx(1, StaffRole.MANAGER), 15L)));
        assertEquals(422, code(() -> service.saveExtras(ctx(1, StaffRole.MANAGER), 15L, p("petStatus", "none"))));
        job(2, "volunteer", "room", "published");
        assertEquals(403, code(() -> service.extras(ctx(1, StaffRole.MANAGER), 15L)));
        assertEquals(403, code(() -> service.saveExtras(ctx(1, StaffRole.MANAGER), 15L, p("petStatus", "none"))));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void closedJobsAndPublishedMerchantJobsCannotChange()
    {
        job(1, "volunteer", "room", "closed");
        assertEquals(409, code(() -> service.saveExtras(ctx(1, StaffRole.MANAGER), 15L, p("petStatus", "none"))));
        job(1, "volunteer", "room", "published");
        ServiceException e = assertThrows(ServiceException.class, () -> service.saveExtras(ctx(1, StaffRole.MERCHANT), 15L, p("petStatus", "none")));
        assertEquals(403, e.getCode());
        assertEquals("岗位上架后不能自己改，请联系本城换工主理人", e.getMessage());
        job(1, "volunteer", "room", "paused");
        assertEquals(403, code(() -> service.saveExtras(ctx(1, StaffRole.MERCHANT), 15L, p("petStatus", "none"))));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        job(1, "volunteer", "room", "draft");
        service.saveExtras(ctx(1, StaffRole.MERCHANT), 15L, p("petStatus", "none"));
        assertEquals("none", updatedCols(15L).get("pet_status"), "草稿商家自己能改");
    }

    @Test
    void saveChangesOnlyTheKeysItWasGivenAndLogsIt()
    {
        job(1, "volunteer", "room", "published");
        Map<String, Object> out = service.saveExtras(ctx(1, StaffRole.MANAGER), 15L, p("lodgingVideo", VIDEO));
        Map<String, Object> cols = updatedCols(15L);
        assertEquals(VIDEO, cols.get("lodging_video"));
        assertEquals("", cols.get("lodging_video_cover"));
        assertFalse(cols.containsKey("meal_allowance"));
        assertFalse(cols.containsKey("pet_status"));
        assertFalse(cols.containsKey("status"), "不用重新审核");
        assertEquals("staff:9001", cols.get("update_by"));
        assertEquals(VIDEO, out.get("lodgingVideo"));
        assertEquals("15", out.get("mealAllowance"), "没带的键回的是原值");
        assertEquals(Arrays.asList(A), out.get("petImages"));
        verify(support).op(any(), eq("job_extras"), anyString(), eq("general"), anyString(), eq(true), isNull());
    }

    @Test
    void saveWithNoKeysWritesNothing()
    {
        job(1, "volunteer", "room", "published");
        Map<String, Object> out = service.saveExtras(ctx(1, StaffRole.MANAGER), 15L, p("title", "别的键不认"));
        assertEquals("15", out.get("mealAllowance"));
        out = service.saveExtras(ctx(1, StaffRole.MANAGER), 15L, null);
        assertEquals("15", out.get("mealAllowance"));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
        verify(support, never()).op(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void saveCollapsesByTheBoardTheJobHasNow()
    {
        job(1, "parttime", "all", "published");
        service.saveExtras(ctx(1, StaffRole.MANAGER), 15L, p("mealAllowance", "30", "mealAllowanceUnit", "day", "petStatus", "yes"));
        Map<String, Object> cols = updatedCols(15L);
        assertEquals("", cols.get("meal_allowance"));
        assertEquals("", cols.get("meal_allowance_unit"));
        assertEquals("", cols.get("pet_status"));
    }

    @Test
    void missingVideoFileIsRefused()
    {
        job(1, "volunteer", "room", "published");
        String other = VIDEO.replace("0123", "aaaa");
        when(videos.exists(other)).thenReturn(false);
        assertEquals(422, code(() -> service.saveExtras(ctx(1, StaffRole.MANAGER), 15L, p("lodgingVideo", other))));
        verify(db, never()).updateRow(anyString(), anyString(), any(), anyMap());
    }
}
