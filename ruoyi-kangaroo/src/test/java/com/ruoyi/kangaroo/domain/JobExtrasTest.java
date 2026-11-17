package com.ruoyi.kangaroo.domain;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/** 招募表单新字段（V66）的规则：老版本不带键不动原值、显式传空才清空、按岗位类型和食宿收口、读的时候再收口一次。 */
class JobExtrasTest
{
    private static final String A = "/profile/upload/2026/10/31/a1.jpg";
    private static final String B = "/profile/upload/2026/10/31/b2.png";
    private static final String VIDEO = "/profile/upload/videos/2026/10/31/0123456789abcdef0123456789abcdef.mp4";
    private UploadedImageFixture files;

    @BeforeEach
    void setup()
    {
        files = new UploadedImageFixture(A, B);
    }

    @AfterEach
    void cleanup()
    {
        files.close();
    }

    private static Map<String, Object> columns(Map<String, Object> in, String type, String board)
    {
        return JobExtras.columns(in, type, board, url -> true);
    }

    private static int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode() == null ? 500 : e.getCode();
    }

    private static Map<String, Object> nullable(Object... kv)
    {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------------- 老版本 ----------------

    @Test
    void oldClientWithoutTheKeysProducesNoColumnsAtAll()
    {
        assertTrue(columns(p("title", "老版本的表单", "boardType", "all"), "volunteer", "all").isEmpty());
        assertTrue(columns(null, "volunteer", "all").isEmpty());
        // 带 null 等于没带：只有 '' 或 [] 才清空，免得一个 null 把照片清掉
        Map<String, Object> nulls = new HashMap<String, Object>();
        for (String k : JobExtras.KEYS)
        {
            nulls.put(k, null);
        }
        assertTrue(columns(nulls, "volunteer", "room").isEmpty());
    }

    @Test
    void explicitlyEmptyClearsEveryColumn()
    {
        Map<String, Object> cols = columns(p("mealAllowance", "", "mealAllowanceUnit", "", "petStatus", "", "petImages", new ArrayList<String>(), "lodgingImages", new ArrayList<String>(),
                "lodgingVideo", "", "lodgingVideoCover", ""), "volunteer", "room");
        assertEquals(7, cols.size(), cols.toString());
        for (Object v : cols.values())
        {
            assertEquals("", v);
        }
    }

    // ---------------- 餐补 ----------------

    @Test
    void mealAmountKeepsItsMeaningAndDropsTrailingZeros()
    {
        assertEquals("30", JobExtras.amount("30"));
        assertEquals("30", JobExtras.amount(30));
        assertEquals("15.5", JobExtras.amount(15.5));
        assertEquals("15.5", JobExtras.amount("15.50"));
        assertEquals("15", JobExtras.amount("015"));
        assertEquals("99999.99", JobExtras.amount("99999.99"));
        assertEquals("", JobExtras.amount(" "));
        for (Object bad : new Object[] { "0", "0.00", "-1", "1.234", "100000", "abc", "1e3", "30元", "30 元/天", ".5", Boolean.TRUE })
        {
            assertEquals(422, code(() -> JobExtras.amount(bad)), String.valueOf(bad));
        }
    }

    @Test
    void mealUnitIsOneOfTwo()
    {
        assertEquals("day", JobExtras.unit("day"));
        assertEquals("month", JobExtras.unit("month"));
        assertEquals("", JobExtras.unit(""));
        assertEquals(422, code(() -> JobExtras.unit("week")));
        assertEquals("15 元/天", JobExtras.mealText("15", "day"));
        assertEquals("600 元/月", JobExtras.mealText("600", "month"));
        assertEquals("", JobExtras.mealText("", "day"));
        assertEquals("", JobExtras.mealText("15", ""));
    }

    @Test
    void mealOnlyWhenTheJobDoesNotFeedYou()
    {
        // 主人拍板：只要不包吃就出现（只包住、不包食宿）；包吃包住、只包吃不出现
        assertTrue(JobExtras.mealShown("room"));
        assertTrue(JobExtras.mealShown("none"));
        assertTrue(JobExtras.mealShown(null), "老数据没写食宿按不包食宿");
        assertFalse(JobExtras.mealShown("all"));
        assertFalse(JobExtras.mealShown("meal"));
        Map<String, Object> in = p("mealAllowance", "30", "mealAllowanceUnit", "day");
        for (String board : Arrays.asList("room", "none"))
        {
            Map<String, Object> cols = columns(in, "parttime", board);
            assertEquals("30", cols.get("meal_allowance"), board);
            assertEquals("day", cols.get("meal_allowance_unit"), board);
        }
        for (String board : Arrays.asList("all", "meal"))
        {
            Map<String, Object> cols = columns(in, "parttime", board);
            assertEquals("", cols.get("meal_allowance"), board);
            assertEquals("", cols.get("meal_allowance_unit"), board);
        }
        assertEquals("", columns(in, "online", "none").get("meal_allowance"), "线上任务没有餐补");
    }

    @Test
    void mealAmountNeedsAUnitButClearingDoesNot()
    {
        assertEquals(422, code(() -> columns(p("mealAllowance", "30"), "parttime", "room")));
        assertEquals(422, code(() -> columns(p("mealAllowance", "30", "mealAllowanceUnit", ""), "parttime", "room")));
        assertEquals(422, code(() -> columns(p("mealAllowance", "abc", "mealAllowanceUnit", "day"), "parttime", "all")), "包吃时也先校验");
        Map<String, Object> cleared = columns(p("mealAllowance", "", "mealAllowanceUnit", "day"), "parttime", "room");
        assertEquals("", cleared.get("meal_allowance"));
        assertEquals("", cleared.get("meal_allowance_unit"), "新表单默认带 day，金额空时不留单位");
        Map<String, Object> unitOnly = columns(p("mealAllowanceUnit", "month"), "parttime", "room");
        assertFalse(unitOnly.containsKey("meal_allowance"), "只改单位不动金额");
        assertEquals("month", unitOnly.get("meal_allowance_unit"));
    }

    // ---------------- 宠物 ----------------

    @Test
    void petIsForVolunteersOnly()
    {
        Map<String, Object> in = p("petStatus", "yes", "petImages", Arrays.asList(A, B));
        Map<String, Object> cols = columns(in, "volunteer", "all");
        assertEquals("yes", cols.get("pet_status"));
        assertEquals("[\"" + A + "\",\"" + B + "\"]", cols.get("pet_images"));
        for (String type : Arrays.asList("parttime", "fulltime", "online"))
        {
            cols = columns(in, type, "all");
            assertEquals("", cols.get("pet_status"), type);
            assertEquals("", cols.get("pet_images"), type);
        }
        assertEquals(422, code(() -> columns(p("petStatus", "maybe"), "volunteer", "all")));
        assertEquals(422, code(() -> columns(p("petStatus", "maybe"), "parttime", "all")), "别的类型也先校验");
    }

    @Test
    void noPetMeansNoPetPhotos()
    {
        Map<String, Object> none = columns(p("petStatus", "none", "petImages", Arrays.asList(A)), "volunteer", "all");
        assertEquals("none", none.get("pet_status"));
        assertEquals("", none.get("pet_images"));
        // 改成没有 / 没填，没带照片的键也一起清：不然以后改回「有」，旧照片又冒出来
        assertEquals("", columns(p("petStatus", "none"), "volunteer", "all").get("pet_images"));
        assertEquals("", columns(p("petStatus", ""), "volunteer", "all").get("pet_images"));
        assertFalse(columns(p("petStatus", "yes"), "volunteer", "all").containsKey("pet_images"), "改成有、没带照片的键：原来的照片留着");
        assertEquals("[\"" + A + "\"]", columns(p("petImages", Arrays.asList(A)), "volunteer", "all").get("pet_images"), "只改照片不带状态：状态不动，读的时候收口");
    }

    @Test
    void petPhotosFollowTheJobPhotoRules()
    {
        List<String> seven = new ArrayList<String>();
        for (int i = 0; i < 7; i++)
        {
            seven.add("/profile/upload/2026/10/31/p" + i + ".jpg");
        }
        ServiceException tooMany = assertThrows(ServiceException.class, () -> columns(p("petImages", seven), "volunteer", "all"));
        assertEquals("宠物照片最多 6 张", tooMany.getMessage());
        assertEquals(422, code(() -> columns(p("petImages", Arrays.asList("https://evil.example/a.jpg")), "volunteer", "all")));
        assertEquals(422, code(() -> columns(p("petImages", Arrays.asList("/profile/upload/../../a.jpg")), "volunteer", "all")));
        ServiceException gone = assertThrows(ServiceException.class, () -> columns(p("petImages", Arrays.asList("/profile/upload/2026/10/31/not-there.jpg")), "volunteer", "all"));
        assertEquals("宠物照片不存在或无法读取，请重新上传", gone.getMessage());
        // 后台图片上传组件给逗号分隔，也认 JSON 数组字符串（同 JobImages）
        assertEquals("[\"" + A + "\",\"" + B + "\"]", columns(p("petImages", A + "," + B), "volunteer", "all").get("pet_images"));
        assertEquals("[\"" + A + "\"]", columns(p("petImages", "[\"" + A + "\"]"), "volunteer", "all").get("pet_images"));
    }

    // ---------------- 住宿照片和视频 ----------------

    @Test
    void lodgingMediaOnlyWhenTheJobHousesYou()
    {
        Map<String, Object> in = p("lodgingImages", Arrays.asList(A), "lodgingVideo", VIDEO, "lodgingVideoCover", B);
        for (String[] kept : new String[][] { { "volunteer", "all" }, { "volunteer", "none" }, { "parttime", "room" }, { "fulltime", "all" } })
        {
            Map<String, Object> cols = columns(in, kept[0], kept[1]);
            assertEquals("[\"" + A + "\"]", cols.get("lodging_images"), kept[0] + kept[1]);
            assertEquals(VIDEO, cols.get("lodging_video"));
            assertEquals(B, cols.get("lodging_video_cover"));
        }
        for (String[] gone : new String[][] { { "parttime", "none" }, { "parttime", "meal" }, { "fulltime", "none" }, { "online", "none" } })
        {
            Map<String, Object> cols = columns(in, gone[0], gone[1]);
            assertEquals("", cols.get("lodging_images"), gone[0] + gone[1]);
            assertEquals("", cols.get("lodging_video"));
            assertEquals("", cols.get("lodging_video_cover"));
        }
    }

    @Test
    void lodgingVideoMustBeAPlatformFileThatExists()
    {
        assertEquals(422, code(() -> columns(p("lodgingVideo", "https://evil.example/v.mp4"), "volunteer", "all")));
        assertEquals(422, code(() -> columns(p("lodgingVideo", A), "volunteer", "all")), "图片不能当视频");
        assertEquals(422, code(() -> JobExtras.columns(p("lodgingVideo", VIDEO), "volunteer", "all", url -> false)), "文件不在");
        assertEquals(422, code(() -> columns(p("lodgingVideo", Arrays.asList(VIDEO)), "volunteer", "all")));
        assertEquals(422, code(() -> columns(p("lodgingVideoCover", VIDEO), "volunteer", "all")), "视频不能当封面");
        assertEquals(422, code(() -> columns(p("lodgingVideo", VIDEO, "lodgingVideoCover", "https://evil.example/c.jpg"), "volunteer", "all")));
    }

    @Test
    void changingTheVideoDropsTheOldCover()
    {
        Map<String, Object> cols = columns(p("lodgingVideo", VIDEO), "volunteer", "all");
        assertEquals(VIDEO, cols.get("lodging_video"));
        assertEquals("", cols.get("lodging_video_cover"), "旧封面是上一个视频的画面");
        cols = columns(p("lodgingVideo", "", "lodgingVideoCover", A), "volunteer", "all");
        assertEquals("", cols.get("lodging_video"));
        assertEquals("", cols.get("lodging_video_cover"), "删了视频封面一起清");
        assertEquals(A, columns(p("lodgingVideoCover", A), "volunteer", "all").get("lodging_video_cover"), "只换封面");
    }

    @Test
    void lodgingPhotosCapAtNine()
    {
        List<String> ten = new ArrayList<String>();
        for (int i = 0; i < 10; i++)
        {
            ten.add("/profile/upload/2026/10/31/l" + i + ".jpg");
        }
        ServiceException e = assertThrows(ServiceException.class, () -> columns(p("lodgingImages", ten), "volunteer", "all"));
        assertEquals("住宿照片最多 9 张", e.getMessage());
    }

    // ---------------- 读 ----------------

    private static Map<String, Object> view(String type, String board)
    {
        return JobExtras.view(type, board, "15", "day", "yes", "[\"" + A + "\"]", "[\"" + B + "\"]", VIDEO, A);
    }

    @Test
    void detailFollowsTheJobTypeAndBoard()
    {
        Map<String, Object> v = view("volunteer", "room");
        assertEquals("15", v.get("mealAllowance"));
        assertEquals("day", v.get("mealAllowanceUnit"));
        assertEquals("15 元/天", v.get("mealAllowanceText"));
        assertEquals("yes", v.get("petStatus"));
        assertEquals(Arrays.asList(A), v.get("petImages"));
        assertEquals(Arrays.asList(B), v.get("lodgingImages"));
        assertEquals(VIDEO, v.get("lodgingVideo"));
        assertEquals(A, v.get("lodgingVideoCover"));

        // 老版本把「只包住」改成「包吃包住」时不带新字段：库里的餐补留着，详情不显示
        v = view("volunteer", "all");
        assertEquals("", v.get("mealAllowance"));
        assertEquals("", v.get("mealAllowanceUnit"));
        assertEquals("", v.get("mealAllowanceText"));
        assertEquals(Arrays.asList(B), v.get("lodgingImages"), "包吃包住也包住");

        v = view("parttime", "none");
        assertEquals("15 元/天", v.get("mealAllowanceText"));
        assertEquals("", v.get("petStatus"), "宠物只给换工");
        assertEquals(Collections.emptyList(), v.get("petImages"));
        assertEquals(Collections.emptyList(), v.get("lodgingImages"), "不包住");
        assertEquals("", v.get("lodgingVideo"));
        assertEquals("", v.get("lodgingVideoCover"));

        v = view("online", "none");
        assertEquals("", v.get("mealAllowanceText"));
        assertEquals("", v.get("petStatus"));
        assertEquals(Collections.emptyList(), v.get("lodgingImages"));
    }

    @Test
    void detailNeverThrowsOnOddStoredValues()
    {
        // 老岗位都是 null；手改库的值跳过
        Map<String, Object> v = JobExtras.view("volunteer", "all", null, null, null, null, null, null, null);
        assertEquals("", v.get("petStatus"));
        assertEquals(Collections.emptyList(), v.get("petImages"));
        assertEquals(Collections.emptyList(), v.get("lodgingImages"));
        assertEquals("", v.get("lodgingVideo"));
        v = JobExtras.view("volunteer", "room", "15", "week", "whatever", "not json", "[1,2", "/etc/passwd", "/etc/passwd");
        assertEquals("", v.get("mealAllowanceText"));
        assertEquals("", v.get("mealAllowance"), "单位不对就不显示金额");
        assertEquals("", v.get("petStatus"));
        assertEquals("", v.get("lodgingVideo"));
        assertEquals("", v.get("lodgingVideoCover"));
        v = JobExtras.view("volunteer", "all", null, null, "none", "[\"" + A + "\"]", null, null, null);
        assertEquals("none", v.get("petStatus"));
        assertEquals(Collections.emptyList(), v.get("petImages"), "没有宠物不出照片");
    }

    @Test
    void staffSeesTheStoredValuesEvenWhenHiddenFromUsers()
    {
        Map<String, Object> s = JobExtras.stored("parttime", "all", "15", "day", "", null, "[\"" + A + "\"]", VIDEO, "");
        assertEquals("15", s.get("mealAllowance"), "老版本留下的值，员工端打开时看得到，保存时才按当时的食宿收口");
        assertEquals(Boolean.TRUE, s.get("housing"));
        assertEquals(Boolean.FALSE, s.get("mealShown"));
        assertEquals(Boolean.FALSE, s.get("petShown"));
        assertEquals(Arrays.asList(A), s.get("lodgingImages"));
        assertEquals(Collections.emptyList(), s.get("petImages"));
        s = JobExtras.stored("volunteer", "room", null, null, null, null, null, null, null);
        assertEquals("", s.get("mealAllowance"), "老岗位是 null，给页面空串");
        assertEquals(Boolean.TRUE, s.get("mealShown"));
        assertEquals(Boolean.TRUE, s.get("petShown"));
    }

    @Test
    void jobImagesKeepTheirOwnWording()
    {
        // 共用校验不能改了岗位照片原来的提示（老测试、小程序都认这些字）
        assertEquals("岗位照片最多 9 张", assertThrows(ServiceException.class, () -> JobImages.parse(Arrays.asList("/profile/upload/1.jpg", "/profile/upload/2.jpg", "/profile/upload/3.jpg",
                "/profile/upload/4.jpg", "/profile/upload/5.jpg", "/profile/upload/6.jpg", "/profile/upload/7.jpg", "/profile/upload/8.jpg", "/profile/upload/9.jpg", "/profile/upload/10.jpg"))).getMessage());
        assertEquals("岗位照片只能用本平台上传的图片，请重新上传", assertThrows(ServiceException.class, () -> JobImages.parse("https://a/b.jpg")).getMessage());
        assertEquals("岗位照片不存在或无法读取，请重新上传", assertThrows(ServiceException.class, () -> JobImages.require(Arrays.asList("/profile/upload/2026/10/31/not-there.jpg"))).getMessage());
        assertNull(JobImages.parse(null));
    }
}
