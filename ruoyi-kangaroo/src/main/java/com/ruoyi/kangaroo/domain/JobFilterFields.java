package com.ruoyi.kangaroo.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ruoyi.common.exception.ServiceException;

/**
 * 岗位筛选条件（V48，2026-10-16 第二轮改版）：性别偏好 gender_pref、休息排班 rest_mode、住宿房型 room_type 的取值、文案和适用范围。
 * 后台岗位管理、门店在小程序发布岗位、小程序岗位列表的筛选和出参共用这一份口径。
 *
 *  · 性别偏好只对换工换宿：'' 男女不限 / F 女 / M 男。兼职、全职、线上任务一律存 ''（招聘不能写性别限制）；
 *  · 休息排班只对线下岗位：1-1 做一休一 … 6-1 做六休一。线上任务一律存 ''；
 *  · 住宿房型只在岗位包住时有意义（换工换宿，或食宿是包吃包住 / 包住宿）：single 单人间 … multi 多人间。其余一律存 ''。
 *
 * 三项都是门店选填，'' = 没填。用户筛某一项时，没填的岗位不算符合；只有性别例外：没填 = 男女不限，筛「女」也列出来。
 * 入参收代码，也认页面上的中文（女 / 做五休二 / 单人间），别的值一律拒绝（422），不悄悄忽略。
 */
public final class JobFilterFields
{
    private JobFilterFields()
    {
    }

    /** 性别偏好：代码 → 文案（'' = 男女不限，不在表里） */
    public static final Map<String, String> GENDERS;

    /** 休息排班：代码 → 文案，按发布页、筛选面板的显示顺序 */
    public static final Map<String, String> REST_MODES;

    /** 住宿房型：代码 → 文案 */
    public static final Map<String, String> ROOM_TYPES;

    static
    {
        Map<String, String> g = new LinkedHashMap<String, String>();
        g.put("F", "女");
        g.put("M", "男");
        GENDERS = Collections.unmodifiableMap(g);
        Map<String, String> r = new LinkedHashMap<String, String>();
        r.put("1-1", "做一休一");
        r.put("4-3", "做四休三");
        r.put("2-1", "做二休一");
        r.put("5-2", "做五休二");
        r.put("3-2", "做三休二");
        r.put("3-1", "做三休一");
        r.put("6-1", "做六休一");
        REST_MODES = Collections.unmodifiableMap(r);
        Map<String, String> t = new LinkedHashMap<String, String>();
        t.put("single", "单人间");
        t.put("double", "双人间");
        t.put("triple", "三人间");
        t.put("multi", "多人间");
        ROOM_TYPES = Collections.unmodifiableMap(t);
    }

    /** 最短在店天数上限（门店发布页的选项是 7 / 15 / 20 / 30 天） */
    public static final int MIN_DAYS_MAX = 365;

    public static String restModeLabel(String code)
    {
        String l = code == null ? null : REST_MODES.get(code);
        return l == null ? "" : l;
    }

    public static String roomTypeLabel(String code)
    {
        String l = code == null ? null : ROOM_TYPES.get(code);
        return l == null ? "" : l;
    }

    /** 岗位包不包住：换工换宿本来就换住宿；兼职、全职看食宿（包吃包住 / 包住宿）；线上任务不到店 */
    public static boolean housing(String jobType, String boardType)
    {
        if ("volunteer".equals(jobType))
        {
            return true;
        }
        if ("online".equals(jobType))
        {
            return false;
        }
        return "all".equals(boardType) || "room".equals(boardType);
    }

    /** 性别偏好入参 → 代码：null = 没传；'' / 不限 / 男女不限 = ''；F / M / 女 / 男；别的拒绝 */
    public static String gender(Object raw)
    {
        return code(GENDERS, raw, "性别偏好", "不限", "男女不限");
    }

    /** 休息排班入参 → 代码：null = 没传；'' / 不限 = ''；1-1 / 做一休一 …；别的拒绝 */
    public static String restMode(Object raw)
    {
        return code(REST_MODES, raw, "休息排班", "不限");
    }

    /** 住宿房型入参 → 代码：null = 没传；'' / 不限 = ''；single / 单人间 …；别的拒绝 */
    public static String roomType(Object raw)
    {
        return code(ROOM_TYPES, raw, "住宿条件", "不限");
    }

    /** 按岗位类型收口：性别偏好只留给换工换宿 */
    public static String genderFor(String jobType, String code)
    {
        return "volunteer".equals(jobType) && code != null ? code : "";
    }

    /** 按岗位类型收口：线上任务没有休息排班 */
    public static String restModeFor(String jobType, String code)
    {
        return "online".equals(jobType) || code == null ? "" : code;
    }

    /** 按岗位类型和食宿收口：不包住的岗位没有房型 */
    public static String roomTypeFor(String jobType, String boardType, String code)
    {
        return housing(jobType, boardType) && code != null ? code : "";
    }

    /**
     * 门店发布时的最短在店天数：没传 / 空 / 0 = 不限（null）；1–365 的整数；别的拒绝。
     * 页面可能传数字，也可能传数字字符串（picker 的值）
     */
    public static Integer minDays(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        long v;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short)
        {
            v = ((Number) raw).longValue();
        }
        else if (raw instanceof String)
        {
            String s = ((String) raw).trim();
            if (s.isEmpty())
            {
                return null;
            }
            if (!s.matches("[0-9]{1,4}"))
            {
                throw new ServiceException("最短在店天数为 1–" + MIN_DAYS_MAX + " 天", 422);
            }
            v = Long.parseLong(s);
        }
        else if (raw instanceof Number && ((Number) raw).doubleValue() == Math.floor(((Number) raw).doubleValue()))
        {
            // JSON 里的 15.0 之类
            v = ((Number) raw).longValue();
        }
        else
        {
            throw new ServiceException("最短在店天数为 1–" + MIN_DAYS_MAX + " 天", 422);
        }
        if (v == 0)
        {
            return null;
        }
        if (v < 1 || v > MIN_DAYS_MAX)
        {
            throw new ServiceException("最短在店天数为 1–" + MIN_DAYS_MAX + " 天", 422);
        }
        return Integer.valueOf((int) v);
    }

    /**
     * 列表筛选的多选参数（restModes=5-2,6-1 / roomTypes=single,double）：逗号分隔，空的跳过、重复的去掉，
     * 每个都要在白名单里（代码或中文都认），有一个不认识就拒绝。全空返回空列表（= 不筛）
     */
    public static List<String> codes(Map<String, String> dict, String csv, String what)
    {
        List<String> out = new ArrayList<String>();
        if (csv == null)
        {
            return out;
        }
        for (String part : csv.split("[,，]"))
        {
            String c = code(dict, part, what);
            if (c != null && !c.isEmpty() && !out.contains(c))
            {
                out.add(c);
            }
        }
        return out;
    }

    private static String code(Map<String, String> dict, Object raw, String what, String... blanks)
    {
        if (raw == null)
        {
            return null;
        }
        if (!(raw instanceof String))
        {
            throw new ServiceException(what + "选项不合法", 422);
        }
        String v = ((String) raw).trim();
        if (v.isEmpty())
        {
            return "";
        }
        for (String b : blanks)
        {
            if (b.equals(v))
            {
                return "";
            }
        }
        if (dict.containsKey(v))
        {
            return v;
        }
        for (Map.Entry<String, String> e : dict.entrySet())
        {
            if (e.getValue().equals(v))
            {
                return e.getKey();
            }
        }
        throw new ServiceException(what + "选项不合法", 422);
    }
}
