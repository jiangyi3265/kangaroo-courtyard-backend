package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 商家端发布表单（2026-10-18，E1）：用户端岗位详情显示、之前发布页存不进去的几项（食宿细分、食宿说明、每日工时、上班时段、
 * 结算方式、全职的试用期 / 培训 / 门店类型）落到 kg_job 现有的列上；老客户端一个都不带时和以前存的一样；乱传的代码 422（不连库）
 */
class KgStaffJobPublishFormTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStaffJobService service = new KgStaffJobService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final AtomicReference<Map<String, Object>> saved = new AtomicReference<>();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ctx.storeId = 102L; ctx.me = new AppLoginUser(); ctx.me.setUserId(9001L);
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("storeName", "小院", "city", "平潭", "address", "海边"));
        when(db.insert(eq("kg_job"), anyMap())).thenAnswer(inv -> { saved.set(inv.getArgument(1)); return 99L; });
    }

    private Map<String, Object> input(String type)
    {
        return p("images", java.util.Collections.singletonList(PHOTO), "jobType", type, "startDate", "2026-10-25", "pay", "board", "headcount", 2,
                "title", "小院伙伴", "intro", "", "work", "接待客人", "hope", "认真负责", "keywords", Arrays.asList("咖啡", "接待"));
    }

    @Test void merchantMustConfirmAgreementBeforeAnyJobIsWritten() {
        KgMerchantAgreements agreements = mock(KgMerchantAgreements.class);
        ReflectionTestUtils.setField(service, "merchantAgreements", agreements);
        ctx.role = "merchant";
        doThrow(new ServiceException("请确认商家协议", 428)).when(agreements).requireAccepted(ctx, "volunteer");
        assertEquals(Integer.valueOf(428), assertThrows(ServiceException.class, () -> service.create(ctx, input("volunteer"))).getCode());
        verify(db, never()).insert(eq("kg_job"), anyMap());
        doNothing().when(agreements).requireAccepted(ctx, "volunteer");
        service.create(ctx, input("volunteer"));
        verify(db).insert(eq("kg_job"), anyMap());
    }

    @Test
    void oldClientSavesWhatItSavedBefore()
    {
        Map<String, Object> in = input("volunteer");
        in.put("hours", "每周 2 天，每天 4 小时");
        service.create(ctx, in);
        Map<String, Object> row = saved.get();
        assertEquals("all", row.get("board_type"));
        assertEquals("提供食宿", row.get("board_desc"));
        assertEquals("每周 2 天，每天 4 小时", row.get("shift_desc"));
        for (String col : Arrays.asList("daily_hours", "shift_time", "settle_desc", "probation", "train_desc"))
        {
            assertNull(row.get(col), col);
        }
        assertEquals("咖啡,接待", row.get("tags"));
        // 描述照旧写「提供食宿」
        assertTrue(service.describe(ctx, input("volunteer"), 0).get("title").toString().contains("提供食宿"));
    }

    @Test
    void parttimeSavesBoardWorkTimeAndSettle()
    {
        Map<String, Object> in = input("parttime");
        in.putAll(p("boardType", "room", "boardDesc", "  独立单间，吃饭自理  ", "roomType", "single",
                "dailyHours", "每天 6 小时", "shiftTime", "9:00-15:00", "settleDesc", "日结",
                // 兼职不存试用期、培训、门店类型
                "probation", "1个月", "trainDesc", "带薪培训", "category", "bar"));
        service.create(ctx, in);
        Map<String, Object> row = saved.get();
        assertEquals("room", row.get("board_type"));
        assertEquals("独立单间，吃饭自理", row.get("board_desc"));
        assertEquals("single", row.get("room_type"), "包住的岗位留住宿条件");
        assertEquals("每天 6 小时", row.get("daily_hours"));
        assertEquals("9:00-15:00", row.get("shift_time"));
        assertEquals("日结", row.get("settle_desc"));
        assertNull(row.get("probation"));
        assertNull(row.get("train_desc"));
        assertEquals("咖啡,接待", row.get("tags"));
        Map<String, Object> out = service.describe(ctx, in, 0);
        assertTrue(out.get("work").toString().contains("工作时间：每天 6 小时（9:00-15:00）"), out.get("work").toString());
        assertTrue(out.get("basis").toString().contains("食宿安排：包住宿（独立单间，吃饭自理）"));
        assertTrue(out.get("basis").toString().contains("结算方式：日结"));
    }

    @Test
    void mealOrNoBoardDropsRoomTypeAndText()
    {
        Map<String, Object> in = input("fulltime");
        in.putAll(p("boardType", "meal", "roomType", "double"));
        service.create(ctx, in);
        assertEquals("meal", saved.get().get("board_type"));
        assertEquals("包三餐", saved.get().get("board_desc"));
        assertEquals("", saved.get().get("room_type"), "只包吃不包住，没有房型");
        in.putAll(p("boardType", "none", "boardDesc", "随便写写"));
        service.create(ctx, in);
        assertEquals("none", saved.get().get("board_type"));
        assertNull(saved.get().get("board_desc"));
    }

    @Test
    void fulltimeSavesProbationTrainingAndCategoryAsFirstTag()
    {
        Map<String, Object> in = input("fulltime");
        in.putAll(p("boardType", "all", "probation", "1个月", "trainDesc", "入职带薪培训 3 天", "category", "homestay"));
        service.create(ctx, in);
        Map<String, Object> row = saved.get();
        assertEquals("1个月", row.get("probation"));
        assertEquals("入职带薪培训 3 天", row.get("train_desc"));
        assertEquals("民宿,咖啡,接待", row.get("tags"));
        assertTrue(service.describe(ctx, in, 0).get("basis").toString().contains("门店类型：民宿"));
        // 认中文；「其他」不进标签；和关键词重复的不重复写
        in.put("category", "咖啡餐饮");
        in.put("keywords", Arrays.asList("咖啡餐饮", "咖啡"));
        service.create(ctx, in);
        assertEquals("咖啡餐饮,咖啡", saved.get().get("tags"));
        in.put("category", "other");
        service.create(ctx, in);
        assertEquals("咖啡餐饮,咖啡", saved.get().get("tags"));
    }

    @Test
    void longTextIsCutToColumnWidth()
    {
        Map<String, Object> in = input("parttime");
        String longText = String.join("", java.util.Collections.nCopies(80, "长"));
        in.putAll(p("boardType", "all", "boardDesc", longText, "dailyHours", longText, "shiftTime", longText, "settleDesc", longText));
        service.create(ctx, in);
        Map<String, Object> row = saved.get();
        assertEquals(60, row.get("board_desc").toString().length());
        assertEquals(32, row.get("daily_hours").toString().length());
        assertEquals(32, row.get("shift_time").toString().length());
        assertEquals(64, row.get("settle_desc").toString().length());
    }

    @Test
    void unknownCodesAreRejectedBeforeDatabaseAccess()
    {
        Object[][] bad = {
            { "parttime", "boardType", "free" },
            { "volunteer", "boardType", "meal" },
            { "volunteer", "boardType", "none" },
            { "fulltime", "category", "spa" }
        };
        for (Object[] b : bad)
        {
            Map<String, Object> in = input((String) b[0]);
            in.put((String) b[1], b[2]);
            assertEquals(422, assertThrows(ServiceException.class, () -> service.create(ctx, in)).getCode(), Arrays.toString(b));
        }
        verifyNoInteractions(db);
    }
}
