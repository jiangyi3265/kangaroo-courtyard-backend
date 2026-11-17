package com.ruoyi.kangaroo.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;

/**
 * 招募表单新字段（V66，2026-10-31 主人反馈）：餐补、宠物、住宿照片和视频。门店在小程序发布、换工主理人完善、已发岗位补资料、后台岗位管理共用这一份规则。
 *
 *  · 餐补：不包吃才有（食宿 room 只包住 / none 不包食宿）。金额 + 单位（day 元/天、month 元/月），选填；
 *  · 宠物：只给换工。pet_status 空 = 没填、none 没有、yes 有；有才有照片，最多 6 张，选填，不挡提交；
 *  · 住宿照片和视频：包住才有（JobFilterFields.housing，换工一律算包住）。最多 9 张照片、1 段视频（小院视频规则，3 分钟、100MB），都选填；
 *  · 线上任务一个都没有。
 *
 * 老版本小程序（2026.10.31.1、.2）提交岗位时不带这些键，所以写入只认「带了的键」：{@link #columns} 没带的键不进结果，库里原值不动；带 '' 或 [] 才清空，
 * 带 null 等于没带。也因此老版本把「只包住」改成「包吃包住」时餐补还留在库里，读的时候 {@link #view} 再按岗位类型和食宿收口（用户端看不到），不能指望库里一定干净。
 * 媒体路径校验和岗位照片一致（JobImages：只收本平台上传的路径，写入时核对文件确实在、确实是图片）；这几项和岗位照片一样不走平台审核，合作商家上架后不能自己改。
 */
public final class JobExtras
{
    private JobExtras()
    {
    }

    public static final String UNIT_DAY = "day";
    public static final String UNIT_MONTH = "month";
    public static final String PET_NONE = "none";
    public static final String PET_YES = "yes";

    /** 宠物照片最多几张、kg_job.pet_images 列宽 */
    public static final int PET_MAX = 6;
    public static final int PET_COLUMN_MAX = 1000;

    /** 住宿照片最多几张、kg_job.lodging_images 列宽 */
    public static final int LODGING_MAX = 9;
    public static final int LODGING_COLUMN_MAX = 2000;

    /** 请求体里的键（和 kg_job 列名一一对应） */
    public static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
            "mealAllowance", "mealAllowanceUnit", "petStatus", "petImages", "lodgingImages", "lodgingVideo", "lodgingVideoCover"));

    private static String text(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 带了这个键：没带、带 null 都算没带（老版本不带；只有显式的 '' 或 [] 才清空） */
    private static boolean has(Map<String, Object> in, String key)
    {
        return in.get(key) != null;
    }

    /** 这种食宿下有没有餐补：只要不包吃就有（只包住、不包食宿），包吃包住、只包吃没有。老数据没写食宿的按不包食宿（列默认值 none） */
    public static boolean mealShown(String boardType)
    {
        String b = text(boardType);
        return b.isEmpty() || "room".equals(b) || "none".equals(b);
    }

    /**
     * 餐补金额入参 → 存库的写法（去掉多余的 0，15.50 存 15.5）：'' = 清空；否则 1–5 位整数、最多两位小数、大于 0，字符串和数字都收，别的 422
     */
    public static String amount(Object raw)
    {
        String s = text(raw);
        if (s.isEmpty())
        {
            return "";
        }
        if (!s.matches("[0-9]{1,5}(\\.[0-9]{1,2})?"))
        {
            throw new ServiceException("餐补金额要是数字，最多两位小数，不超过 99999", 422);
        }
        BigDecimal v = new BigDecimal(s);
        if (v.signum() <= 0)
        {
            throw new ServiceException("餐补金额要大于 0", 422);
        }
        return v.stripTrailingZeros().toPlainString();
    }

    /** 餐补单位入参：''、day、month，别的 422 */
    public static String unit(Object raw)
    {
        String s = text(raw);
        if (s.isEmpty() || UNIT_DAY.equals(s) || UNIT_MONTH.equals(s))
        {
            return s;
        }
        throw new ServiceException("餐补单位只能是元/天或元/月", 422);
    }

    /** 15、day → 「15 元/天」；金额或单位不全时是 '' */
    public static String mealText(Object amount, Object unit)
    {
        String a = text(amount);
        String u = text(unit);
        if (a.isEmpty() || !(UNIT_DAY.equals(u) || UNIT_MONTH.equals(u)))
        {
            return "";
        }
        return a + (UNIT_DAY.equals(u) ? " 元/天" : " 元/月");
    }

    /** 宠物入参：''（没填）、none、yes，别的 422 */
    public static String petStatus(Object raw)
    {
        String s = text(raw);
        if (s.isEmpty() || PET_NONE.equals(s) || PET_YES.equals(s))
        {
            return s;
        }
        throw new ServiceException("宠物选项只能是没有或有", 422);
    }

    /** 住宿视频入参：'' 清空；否则必须是平台视频上传接口给的路径，文件确实在 */
    public static String video(Object raw, Predicate<String> exists)
    {
        String s = text(raw);
        if (s.isEmpty())
        {
            return "";
        }
        if (!(raw instanceof String) || !KgVideoUploads.isPlatformVideo(s) || (exists != null && !exists.test(s)))
        {
            throw new ServiceException("住宿视频地址不对，请重新上传视频", 422);
        }
        return s;
    }

    /** 住宿视频封面入参：'' 清空；否则和岗位照片同一套校验 */
    public static String cover(Object raw)
    {
        String s = text(raw);
        if (s.isEmpty())
        {
            return "";
        }
        if (!(raw instanceof String) || !JobImages.isUpload(s))
        {
            throw new ServiceException("住宿视频封面只能用本平台上传的图片，请重新上传", 422);
        }
        JobImages.requireExisting(Collections.singletonList(s), "住宿视频封面");
        return s;
    }

    private static List<String> images(Object raw, int max, int columnMax, String what, boolean checkFiles)
    {
        List<String> list = JobImages.parse(raw, max, columnMax, what);
        if (list == null)
        {
            return new ArrayList<String>();
        }
        if (checkFiles)
        {
            JobImages.requireExisting(list, what);
        }
        return list;
    }

    /**
     * 入参 → kg_job 的列：只有带了的键才有对应的列（见类注释）。先校验，不合格 422，再按岗位类型、食宿收口（宠物只给换工、包吃清餐补、不包住清住宿媒体，线上任务都没有），
     * 收口掉的值存 ''。改成没有宠物时照片一起清；换视频没给封面时封面清掉（旧封面是上一个视频的画面）。videoExists 为 null 时不查文件（只有手工 new 的单测）
     */
    public static Map<String, Object> columns(Map<String, Object> in, String jobType, String boardType, Predicate<String> videoExists)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (in == null)
        {
            return out;
        }
        boolean online = "online".equals(jobType);
        // 餐补
        boolean mealOn = !online && mealShown(boardType);
        boolean hasAmount = has(in, "mealAllowance");
        boolean hasUnit = has(in, "mealAllowanceUnit");
        if (hasAmount || hasUnit)
        {
            String amount = hasAmount ? amount(in.get("mealAllowance")) : null;
            String unit = hasUnit ? unit(in.get("mealAllowanceUnit")) : "";
            if (!mealOn || (hasAmount && amount.isEmpty()))
            {
                out.put("meal_allowance", "");
                out.put("meal_allowance_unit", "");
            }
            else if (hasAmount)
            {
                if (unit.isEmpty())
                {
                    throw new ServiceException("请选择餐补的单位（元/天或元/月）", 422);
                }
                out.put("meal_allowance", amount);
                out.put("meal_allowance_unit", unit);
            }
            else
            {
                out.put("meal_allowance_unit", unit);
            }
        }
        // 宠物
        boolean volunteer = "volunteer".equals(jobType);
        boolean hasPet = has(in, "petStatus");
        String pet = "";
        if (hasPet)
        {
            String checked = petStatus(in.get("petStatus"));
            pet = volunteer ? checked : "";
            out.put("pet_status", pet);
        }
        if (has(in, "petImages"))
        {
            boolean keep = volunteer && (!hasPet || PET_YES.equals(pet));
            List<String> list = images(in.get("petImages"), PET_MAX, PET_COLUMN_MAX, "宠物照片", keep);
            out.put("pet_images", keep ? JobImages.toJson(list) : "");
        }
        else if (hasPet && !PET_YES.equals(pet))
        {
            out.put("pet_images", "");
        }
        // 住宿照片和视频
        boolean housing = JobFilterFields.housing(jobType, boardType);
        if (has(in, "lodgingImages"))
        {
            List<String> list = images(in.get("lodgingImages"), LODGING_MAX, LODGING_COLUMN_MAX, "住宿照片", housing);
            out.put("lodging_images", housing ? JobImages.toJson(list) : "");
        }
        boolean hasCover = has(in, "lodgingVideoCover");
        if (has(in, "lodgingVideo"))
        {
            String v = video(in.get("lodgingVideo"), housing ? videoExists : null);
            String c = !v.isEmpty() && hasCover && housing ? cover(in.get("lodgingVideoCover")) : "";
            out.put("lodging_video", housing ? v : "");
            out.put("lodging_video_cover", housing ? c : "");
        }
        else if (hasCover)
        {
            String c = housing ? cover(in.get("lodgingVideoCover")) : "";
            out.put("lodging_video_cover", c);
        }
        return out;
    }

    /**
     * 用户端岗位详情（读的时候收口）：mealAllowance、mealAllowanceUnit、mealAllowanceText（如「15 元/天」）、petStatus、petImages、lodgingImages、lodgingVideo、lodgingVideoCover。
     * 不包吃才有餐补、换工才有宠物（有才有照片）、包住才有住宿媒体，线上任务都是空的。库里不合格的路径跳过（只可能是手改库），从不抛错
     */
    public static Map<String, Object> view(String jobType, String boardType, Object mealAllowance, Object mealUnit, Object petStatus, Object petImages,
            Object lodgingImages, Object lodgingVideo, Object lodgingVideoCover)
    {
        boolean online = "online".equals(jobType);
        boolean mealOn = !online && mealShown(boardType);
        String text = mealOn ? mealText(mealAllowance, mealUnit) : "";
        boolean volunteer = "volunteer".equals(jobType);
        String pet = volunteer ? text(petStatus) : "";
        boolean housing = JobFilterFields.housing(jobType, boardType);
        String video = housing ? text(lodgingVideo) : "";
        if (!KgVideoUploads.isPlatformVideo(video))
        {
            video = "";
        }
        String cover = video.isEmpty() ? "" : text(lodgingVideoCover);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mealAllowance", text.isEmpty() ? "" : text(mealAllowance));
        m.put("mealAllowanceUnit", text.isEmpty() ? "" : text(mealUnit));
        m.put("mealAllowanceText", text);
        m.put("petStatus", PET_NONE.equals(pet) || PET_YES.equals(pet) ? pet : "");
        m.put("petImages", PET_YES.equals(pet) ? JobImages.read(text(petImages)) : Collections.<String>emptyList());
        m.put("lodgingImages", housing ? JobImages.read(text(lodgingImages)) : Collections.<String>emptyList());
        m.put("lodgingVideo", video);
        m.put("lodgingVideoCover", JobImages.isUpload(cover) ? cover : "");
        return m;
    }

    /**
     * 员工端回填（发布表单的完善、已发岗位补资料）：库里的原值，不按食宿收口（图片列解析成数组，没填是 '' / []），
     * 老版本留下的值在这里看得到，保存时才按当时的食宿收口。housing、mealShown、petShown 告诉页面这个岗位现在该显示哪几块
     */
    public static Map<String, Object> stored(String jobType, String boardType, Object mealAllowance, Object mealUnit, Object petStatus, Object petImages,
            Object lodgingImages, Object lodgingVideo, Object lodgingVideoCover)
    {
        boolean online = "online".equals(jobType);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("jobType", jobType);
        m.put("boardType", text(boardType));
        m.put("housing", JobFilterFields.housing(jobType, boardType));
        m.put("mealShown", !online && mealShown(boardType));
        m.put("petShown", "volunteer".equals(jobType));
        m.put("mealAllowance", text(mealAllowance));
        m.put("mealAllowanceUnit", text(mealUnit));
        m.put("petStatus", text(petStatus));
        m.put("petImages", JobImages.read(text(petImages)));
        m.put("lodgingImages", JobImages.read(text(lodgingImages)));
        m.put("lodgingVideo", text(lodgingVideo));
        m.put("lodgingVideoCover", text(lodgingVideoCover));
        return m;
    }
}
