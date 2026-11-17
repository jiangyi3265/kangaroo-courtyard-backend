package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgSkills;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 门店发布岗位的筛选条件（V48，2026-10-16 第二轮改版）：性别偏好（只对换工换宿）、休息排班（线下岗位）、住宿条件（包住时）、最短在店天数；
 * 白名单外的值拒绝、超出适用范围的存 ''；关键词选项多了 做饭 / 拍摄 / 开车 / 玩游戏（不连库）
 */
class KgStaffJobFilterFieldsTest extends com.ruoyi.kangaroo.testsupport.WithJobPhoto
{
    @Test
    void splitGenderPlanIsSavedAlongsideTotalAndSupportsSingleGender()
    {
        Map<String,Object> in = input("volunteer", "board");
        in.put("genderCountsEnabled", true); in.put("genderMaleCount", 1); in.put("genderFemaleCount", 1);
        Map<String,Object> row = create(in);
        assertEquals(1,row.get("gender_male_count")); assertEquals(1,row.get("gender_female_count"));
        assertEquals(2,row.get("headcount")); assertEquals("",row.get("gender_pref"));
        in.put("genderMaleCount",0); in.put("genderFemaleCount",2);
        assertEquals("F",create(in).get("gender_pref"));
    }

    @Test
    void invalidSplitIsRejectedBeforeWriting()
    {
        Map<String,Object> in=input("volunteer","board");
        in.put("genderCountsEnabled",true); in.put("genderMaleCount",1); in.put("genderFemaleCount",2);
        assertThrows(ServiceException.class,()->service.create(ctx,in));
        in.put("genderFemaleCount",0.5);
        assertThrows(ServiceException.class,()->service.create(ctx,in));
        verify(db,never()).insert(eq("kg_job"),anyMap());
    }

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

    private static Map<String, Object> input(String type, String pay)
    {
        return p("images", java.util.Collections.singletonList(PHOTO), "jobType", type, "startDate", "2026-10-20", "hours", "每天 6 小时", "pay", pay, "headcount", 2,
                "title", "小院伙伴", "intro", "介绍", "work", "接待", "hope", "认真负责");
    }

    private Map<String, Object> create(Map<String, Object> in)
    {
        service.create(ctx, in);
        return saved.get();
    }

    @Test
    void volunteerKeepsAllFourFields()
    {
        Map<String, Object> in = input("volunteer", "board");
        in.put("genderPref", "F"); in.put("restMode", "5-2"); in.put("roomType", "double"); in.put("minDays", 15);
        Map<String, Object> row = create(in);
        assertEquals("F", row.get("gender_pref"));
        assertEquals("5-2", row.get("rest_mode"));
        assertEquals("double", row.get("room_type"));
        assertEquals(15, row.get("min_days"));
    }

    @Test
    void volunteerRoomCountsEvenWithoutTickingBoard()
    {
        // 换工换宿本来就换住宿：没勾「提供食宿」也能填房型
        Map<String, Object> in = input("volunteer", "none");
        in.put("roomType", "single");
        assertEquals("single", create(in).get("room_type"));
    }

    @Test
    void outOfScopeValuesAreStoredAsEmpty()
    {
        // 兼职：不能写性别；没包住就没有房型；排班照存
        Map<String, Object> in = input("parttime", "salary");
        in.put("salary", "150"); in.put("salaryType", "daily");
        in.put("genderPref", "M"); in.put("restMode", "6-1"); in.put("roomType", "single"); in.put("minDays", "20");
        Map<String, Object> row = create(in);
        assertEquals("", row.get("gender_pref"));
        assertEquals("6-1", row.get("rest_mode"));
        assertEquals("", row.get("room_type"));
        assertEquals(20, row.get("min_days"));
        // 全职勾了提供食宿：房型照存，性别仍不存
        Map<String, Object> ft = input("fulltime", "board");
        ft.put("genderPref", "F"); ft.put("roomType", "multi");
        Map<String, Object> r2 = create(ft);
        assertEquals("", r2.get("gender_pref"));
        assertEquals("multi", r2.get("room_type"));
    }

    @Test
    void oldClientsAndBlankChoicesSaveEmptyAndNoMinDays()
    {
        Map<String, Object> row = create(input("volunteer", "board"));
        assertEquals("", row.get("gender_pref"));
        assertEquals("", row.get("rest_mode"));
        assertEquals("", row.get("room_type"));
        assertFalse(row.containsKey("min_days"), "不选 = 不限，不写 min_days");
        for (Object none : Arrays.asList(null, "", 0, "0"))
        {
            Map<String, Object> in = input("volunteer", "board");
            in.put("genderPref", ""); in.put("restMode", "不限"); in.put("roomType", ""); in.put("minDays", none);
            Map<String, Object> r = create(in);
            assertEquals("", r.get("gender_pref"));
            assertEquals("", r.get("rest_mode"));
            assertFalse(r.containsKey("min_days"), String.valueOf(none));
        }
    }

    @Test
    void chineseLabelsAreAcceptedToo()
    {
        Map<String, Object> in = input("volunteer", "board");
        in.put("genderPref", "女"); in.put("restMode", "做三休一"); in.put("roomType", "三人间"); in.put("minDays", 30.0d);
        Map<String, Object> row = create(in);
        assertEquals("F", row.get("gender_pref"));
        assertEquals("3-1", row.get("rest_mode"));
        assertEquals("triple", row.get("room_type"));
        assertEquals(30, row.get("min_days"));
    }

    @Test
    void invalidValuesAreRejectedBeforeDatabaseAccess()
    {
        Object[][] bad = {
            { "genderPref", "X" }, { "genderPref", 1 }, { "genderPref", Arrays.asList("F") },
            { "restMode", "7-0" }, { "restMode", "5-2' or '1" }, { "restMode", true },
            { "roomType", "suite" }, { "roomType", 2 },
            { "minDays", 366 }, { "minDays", -1 }, { "minDays", "abc" }, { "minDays", "15天" }, { "minDays", 15.5d }, { "minDays", true }
        };
        for (Object[] b : bad)
        {
            Map<String, Object> in = input("volunteer", "board");
            in.put((String) b[0], b[1]);
            ServiceException e = assertThrows(ServiceException.class, () -> service.create(ctx, in), b[0] + "=" + b[1]);
            assertEquals(Integer.valueOf(422), e.getCode(), b[0] + "=" + b[1]);
        }
        verifyNoInteractions(db);
    }

    @Test
    @SuppressWarnings("unchecked")
    void describeListsTheChoicesAndWritesRestAndStay()
    {
        Map<String, Object> in = input("volunteer", "board");
        in.put("genderPref", "F"); in.put("restMode", "5-2"); in.put("roomType", "single"); in.put("minDays", 15);
        Map<String, Object> d = service.describe(ctx, in, 0);
        String basis = d.get("basis").toString();
        assertTrue(basis.contains("性别偏好：女"), basis);
        assertTrue(basis.contains("休息排班：做五休二"), basis);
        assertTrue(basis.contains("住宿条件：单人间"), basis);
        assertTrue(basis.contains("最短在店：15 天"), basis);
        assertTrue(String.valueOf(d.get("work")).contains("休息安排：做五休二"), String.valueOf(d.get("work")));
        assertTrue(String.valueOf(d.get("hope")).contains("能在店至少 15 天"), String.valueOf(d.get("hope")));
        // 兼职不包住：没有性别、房型两行；没选排班写「未选」
        String pt = ((List<Object>) service.describe(ctx, input("parttime", "none"), 0).get("basis")).toString();
        assertFalse(pt.contains("性别偏好") || pt.contains("住宿条件"), pt);
        assertTrue(pt.contains("休息排班：未选") && pt.contains("最短在店：不限"), pt);
        // 换工没选性别 = 男女不限
        assertTrue(service.describe(ctx, input("volunteer", "board"), 0).get("basis").toString().contains("性别偏好：男女不限"));
    }

    @Test
    void newKeywordOptionsHaveDutiesAndSkillDictionaryEntries()
    {
        List<String> opts = KgStaffJobService.keywordOptions();
        assertTrue(opts.containsAll(Arrays.asList("咖啡", "摄影", "做饭", "拍摄", "开车", "玩游戏")), opts.toString());
        assertEquals(opts.size(), new java.util.HashSet<String>(opts).size());
        Map<String, Object> in = input("volunteer", "board");
        in.put("keywords", Arrays.asList("做饭", "拍摄", "开车", "玩游戏"));
        String work = String.valueOf(service.describe(ctx, in, 0).get("work"));
        for (String duty : new String[] { "协助准备员工餐和客人早餐", "拍摄店内日常与活动的照片、短视频", "持驾照协助接送客人、外出采购", "陪客人玩桌游、剧本杀等游戏" })
        {
            assertTrue(work.contains(duty), work);
        }
        // 每个新关键词在技能词典里都能展开（列表筛选面板的「技能需求」按它筛）
        for (String k : new String[] { "做饭", "拍摄", "开车", "玩游戏" })
        {
            assertTrue(KgSkills.wordsOf(k).size() > 1, k);
        }
        // 关键词照存进 tags（筛选靠它）
        Map<String, Object> row = create(in);
        assertEquals("做饭,拍摄,开车,玩游戏", row.get("tags"));
    }
}
