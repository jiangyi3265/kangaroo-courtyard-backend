package com.ruoyi.kangaroo.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgLike;

/**
 * 岗位列表每个页签自己的筛选条件（2026-10-17 主人反馈：四个页签不再共用一套条件）。
 * 小程序岗位列表（GET /app/job/list，KgJobMapper.selectAppJobList）和全部筛选页（POST /app/job/search，KgUserHubService.search）共用这一份口径：
 *
 *  · 兼职旅行「日薪」三档：80 / 100 / 120 元/天以上 → payDay = 80 / 100 / 120；
 *  · 全职旅行「月薪」三档：3000以下 / 3000–5000 / 5000以上 → payMonthMax = 3000 / payMonthMin = 3000 + payMonthMax = 5000 / payMonthMin = 5000
 *    （主人写的是「3000—4500、5000以上」，中间空出 4500–5000，按连续的三档做：区间左闭右开，正好 5000 的算「5000以上」）；
 *  · 全职旅行「类型」：民宿 / 酒店 / 咖啡餐饮 / 酒吧 / 其他（categories，可多选，命中其一）；
 *  · 食宿（兼职、全职）：包吃包住 all / 包住 room（含包吃包住）/ 包吃 meal（含包吃包住）/ 不包食宿 self（board_type 是 none 或没填）。
 *
 * 薪资按岗位写的「起薪」（salary_min，区间岗位取下限）比：「80元/天以上」= 最低也能拿到 80 元/天，100-150 的岗位选「120以上」时不出现。
 * 单位按 salary_type 换算，和老的 salaryMin / salaryMax 同一国家口径（劳社部发〔2008〕3 号：日 = 时 × 8 = 月 ÷ 21.75，周按 5 天）：
 * 日薪 = 时薪 × 8 = 周薪 ÷ 5 = 月薪 ÷ 21.75；月薪 = 日薪 × 21.75 = 时薪 × 174 = 周薪 × 4.35。
 * 计件（piece）、面议 / 只有奖金提成（none）、没写金额的岗位没有这个口径，选了任何薪资档都不出现。
 *
 * 类型：kg_store / kg_job 都没有「业态」字段（库里只有门店名、岗位标题），按门店名 + 岗位标题里的词归类（下面 CATEGORY_WORDS），
 * 一个岗位可以同时属于几类（「民宿咖啡馆」既算民宿也算咖啡餐饮）；「其他」= 哪一类的词都没出现。
 * 2026-10-18 起商家端发布全职岗位时把「门店类型」存成岗位的第一个标签（kg_job.tags），归类时岗位标签也一起看。
 * 词表是代码常量，like 写法都经 KgLike 转义、走参数绑定。
 *
 * 老的 salaryMin / salaryMax（按日薪区间，区间重叠就算）、board=none（不限）照旧认，线上的老版本小程序不受影响
 */
public final class JobTabFilters
{
    private JobTabFilters()
    {
    }

    /** 薪资门槛上限（元），防止乱传天文数字 */
    public static final long PAY_MAX = 1000000L;

    /** 兼职「日薪」三档（元/天以上），和小程序 job-filters.js 的 PAY_DAY_OPTS 一致 */
    public static final List<Integer> PART_DAY_BANDS = Collections.unmodifiableList(Arrays.asList(80, 100, 120));

    /** 全职「月薪」三档的分界（元/月）：3000以下 / 3000–5000 / 5000以上 */
    public static final List<Integer> FULL_MONTH_EDGES = Collections.unmodifiableList(Arrays.asList(3000, 4500, 5000));
    /** 新版固定中档为 3000–4500（含4500）；旧版3000以下、5000以下仍保持不含上限。 */
    public static boolean monthUpperInclusive(Long max)
    {
        return Long.valueOf(4500L).equals(max);
    }

    /** 食宿条件：代码 → 文案（none / 空 = 不限，不在表里） */
    public static final Map<String, String> BOARDS;

    /** 全职「类型」：代码 → 文案，按筛选面板的顺序 */
    public static final Map<String, String> CATEGORIES;

    /** 每一类认哪些词（门店名或岗位标题里出现任一个就算）；other 没有词，= 哪一类都不是 */
    public static final Map<String, List<String>> CATEGORY_WORDS;

    static
    {
        Map<String, String> b = new LinkedHashMap<String, String>();
        b.put("all", "包吃包住");
        b.put("room", "包住");
        b.put("meal", "包吃");
        b.put("self", "不包食宿");
        BOARDS = Collections.unmodifiableMap(b);
        Map<String, String> c = new LinkedHashMap<String, String>();
        c.put("homestay", "民宿");
        c.put("hotel", "酒店");
        c.put("food", "咖啡餐饮");
        c.put("bar", "酒吧");
        c.put("other", "其他");
        CATEGORIES = Collections.unmodifiableMap(c);
        Map<String, List<String>> w = new LinkedHashMap<String, List<String>>();
        // 不收单个「宿」字：岗位标题里常写「提供食宿」「包住宿」，会把咖啡馆、手作店都算成民宿
        w.put("homestay", Collections.unmodifiableList(Arrays.asList("民宿", "客栈", "青旅", "青年旅舍", "旅舍", "民居")));
        w.put("hotel", Collections.unmodifiableList(Arrays.asList("酒店", "宾馆", "旅馆", "度假村")));
        w.put("food", Collections.unmodifiableList(Arrays.asList("咖啡", "餐厅", "餐饮", "餐馆", "饭店", "茶饮", "奶茶", "茶馆", "茶室",
                "烘焙", "面包", "甜品", "料理", "厨房", "后厨", "小吃", "火锅", "食堂", "早餐", "面馆", "西餐", "中餐", "饮品")));
        w.put("bar", Collections.unmodifiableList(Arrays.asList("酒吧", "清吧", "酒馆", "精酿", "livehouse")));
        CATEGORY_WORDS = Collections.unmodifiableMap(w);
    }

    /** 薪资门槛入参：null / ≤ 0 = 不筛；太大的收到 PAY_MAX */
    public static Long pay(Long raw)
    {
        if (raw == null || raw <= 0)
        {
            return null;
        }
        return Long.valueOf(Math.min(raw, PAY_MAX));
    }

    /** 薪资门槛入参（全部筛选页的 JSON 里可能是数字或字符串）：认不出 / ≤ 0 = 不筛 */
    public static Long pay(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        if (raw instanceof Number)
        {
            return pay(Long.valueOf(((Number) raw).longValue()));
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty())
        {
            return null;
        }
        if (!s.matches("\\d{1,9}"))
        {
            throw new ServiceException("薪资条件不合法", 422);
        }
        return pay(Long.valueOf(s));
    }

    /** 月薪区间：下限不能大于等于上限（左闭右开，相等就一个都筛不出来，直接提示） */
    public static void checkMonthRange(Long min, Long max)
    {
        if (min != null && max != null && min >= max)
        {
            throw new ServiceException("薪资区间不合法", 422);
        }
    }

    /** 食宿入参 → 代码：null / '' / none / 不限 = null（不筛）；all / room / meal / self 或页面上的中文；别的拒绝（422） */
    public static String board(String raw)
    {
        String b = raw == null ? "" : raw.trim();
        if (b.isEmpty() || "none".equals(b) || "不限".equals(b))
        {
            return null;
        }
        if (BOARDS.containsKey(b))
        {
            return b;
        }
        for (Map.Entry<String, String> e : BOARDS.entrySet())
        {
            if (e.getValue().equals(b))
            {
                return e.getKey();
            }
        }
        throw new ServiceException("食宿条件不合法", 422);
    }

    /** 类型入参（逗号分隔的代码或中文，如 homestay,酒店）→ 去重后的代码；空 = 不筛；不认识的拒绝（422） */
    public static List<String> categories(Object raw)
    {
        List<String> parts = new ArrayList<String>();
        if (raw instanceof java.util.Collection)
        {
            for (Object o : (java.util.Collection<?>) raw)
            {
                parts.addAll(Arrays.asList(String.valueOf(o).split("[,，]")));
            }
        }
        else if (raw != null)
        {
            parts.addAll(Arrays.asList(String.valueOf(raw).split("[,，]")));
        }
        List<String> out = new ArrayList<String>();
        for (String p : parts)
        {
            String v = p.trim();
            if (v.isEmpty())
            {
                continue;
            }
            String code = CATEGORIES.containsKey(v) ? v : null;
            for (Map.Entry<String, String> e : CATEGORIES.entrySet())
            {
                if (code == null && e.getValue().equals(v))
                {
                    code = e.getKey();
                }
            }
            if (code == null)
            {
                throw new ServiceException("岗位类型不合法", 422);
            }
            if (!out.contains(code))
            {
                out.add(code);
            }
        }
        return out;
    }

    /** 一个岗位属于哪几类（门店名 + 岗位标题，和 SQL 同一份词表、同样不分大小写）；一类都不是 = [other] */
    public static Set<String> categoriesOf(String storeName, String title)
    {
        return categoriesOf(storeName, title, null);
    }

    /** 同上，再加上岗位标签（逗号分隔；2026-10-18 起全职岗位的第一个标签是商家选的门店类型） */
    public static Set<String> categoriesOf(String storeName, String title, String tags)
    {
        String text = ((storeName == null ? "" : storeName) + "\n" + (title == null ? "" : title) + "\n" + (tags == null ? "" : tags)).toLowerCase();
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, List<String>> e : CATEGORY_WORDS.entrySet())
        {
            for (String w : e.getValue())
            {
                if (text.contains(w.toLowerCase()))
                {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        if (out.isEmpty())
        {
            out.add("other");
        }
        return out;
    }

    /** 全部类型的词（「其他」= 一个都不出现） */
    public static List<String> allCategoryWords()
    {
        List<String> all = new ArrayList<String>();
        for (List<String> ws : CATEGORY_WORDS.values())
        {
            all.addAll(ws);
        }
        return all;
    }

    /** 起薪换成「元/天」的 SQL 表达式（j = kg_job），不是时 / 日 / 周 / 月薪的为 null（比较时不算符合） */
    public static final String DAILY_MIN_SQL = "(case j.salary_type when 'daily' then j.salary_min when 'hourly' then j.salary_min * 8 "
            + "when 'weekly' then j.salary_min / 5 when 'monthly' then j.salary_min / 21.75 end)";

    /** 起薪换成「元/月」的 SQL 表达式：日 × 21.75、时 × 174（8 × 21.75）、周 × 4.35（21.75 ÷ 5） */
    public static final String MONTHLY_MIN_SQL = "(case j.salary_type when 'monthly' then j.salary_min when 'daily' then j.salary_min * 21.75 "
            + "when 'hourly' then j.salary_min * 174 when 'weekly' then j.salary_min * 4.35 end)";

    /** 和 DAILY_MIN_SQL 同一口径的 Java 版（单测、说明用）：没有日薪口径返回 null */
    public static BigDecimal dailyMin(String salaryType, BigDecimal salaryMin)
    {
        if (salaryMin == null || salaryType == null)
        {
            return null;
        }
        switch (salaryType)
        {
            case "daily":
                return salaryMin;
            case "hourly":
                return salaryMin.multiply(BigDecimal.valueOf(8));
            case "weekly":
                return salaryMin.divide(BigDecimal.valueOf(5), 4, RoundingMode.HALF_UP);
            case "monthly":
                return salaryMin.divide(new BigDecimal("21.75"), 4, RoundingMode.HALF_UP);
            default:
                return null;
        }
    }

    /** 和 MONTHLY_MIN_SQL 同一口径的 Java 版：没有月薪口径返回 null */
    public static BigDecimal monthlyMin(String salaryType, BigDecimal salaryMin)
    {
        if (salaryMin == null || salaryType == null)
        {
            return null;
        }
        switch (salaryType)
        {
            case "monthly":
                return salaryMin;
            case "daily":
                return salaryMin.multiply(new BigDecimal("21.75"));
            case "hourly":
                return salaryMin.multiply(BigDecimal.valueOf(174));
            case "weekly":
                return salaryMin.multiply(new BigDecimal("4.35"));
            default:
                return null;
        }
    }

    /** Java 版的薪资判断（单测用，和 SQL 同一口径）：payDay / payMonthMin / payMonthMax 都是 null 时总是 true */
    public static boolean payMatches(String salaryType, BigDecimal salaryMin, Long payDay, Long payMonthMin, Long payMonthMax)
    {
        if (payDay != null)
        {
            BigDecimal d = dailyMin(salaryType, salaryMin);
            if (d == null || d.compareTo(BigDecimal.valueOf(payDay)) < 0)
            {
                return false;
            }
        }
        if (payMonthMin != null || payMonthMax != null)
        {
            BigDecimal m = monthlyMin(salaryType, salaryMin);
            if (m == null)
            {
                return false;
            }
            if (payMonthMin != null && m.compareTo(BigDecimal.valueOf(payMonthMin)) < 0)
            {
                return false;
            }
            if (payMonthMax != null && (monthUpperInclusive(payMonthMax)
                    ? m.compareTo(BigDecimal.valueOf(payMonthMax)) > 0
                    : m.compareTo(BigDecimal.valueOf(payMonthMax)) >= 0))
            {
                return false;
            }
        }
        return true;
    }

    /** Java 版的食宿判断（单测用，和 SQL 同一口径）：all 只认包吃包住；room / meal 含包吃包住；self = none 或没填 */
    public static boolean boardMatches(String boardType, String board)
    {
        String t = boardType == null ? "" : boardType;
        if (board == null)
        {
            return true;
        }
        switch (board)
        {
            case "all":
                return "all".equals(t);
            case "room":
                return "all".equals(t) || "room".equals(t);
            case "meal":
                return "all".equals(t) || "meal".equals(t);
            case "self":
                return t.isEmpty() || "none".equals(t);
            default:
                return false;
        }
    }

    /**
     * 列表接口（MyBatis，KgJobMapper.selectAppJobList）的参数：
     * params.payDay / payMonthMin / payMonthMax；类型 params.categoryTerms = [{other: bool, likes: [...]}]，「其他」用 params.categoryAllLikes
     */
    public static void putParams(Map<String, Object> params, Long payDay, Long payMonthMin, Long payMonthMax, List<String> categories)
    {
        checkMonthRange(payMonthMin, payMonthMax);
        if (payDay != null)
        {
            params.put("payDay", payDay);
        }
        if (payMonthMin != null)
        {
            params.put("payMonthMin", payMonthMin);
        }
        if (payMonthMax != null)
        {
            params.put("payMonthMax", payMonthMax);
            params.put("payMonthMaxInclusive", monthUpperInclusive(payMonthMax));
        }
        if (categories == null || categories.isEmpty())
        {
            return;
        }
        List<Map<String, Object>> terms = new ArrayList<Map<String, Object>>();
        for (String c : categories)
        {
            Map<String, Object> t = new LinkedHashMap<String, Object>();
            boolean other = "other".equals(c);
            t.put("other", Boolean.valueOf(other));
            t.put("likes", other ? new ArrayList<String>() : likes(CATEGORY_WORDS.get(c)));
            terms.add(t);
        }
        params.put("categoryTerms", terms);
        params.put("categoryAllLikes", likes(allCategoryWords()));
    }

    /**
     * 全部筛选页（KgUserHubService.search，具名参数 SQL，j = kg_job、s = kg_store）的同一组条件。
     * 参数名 pd / pmn / pmx / cw0、cw1…（全部类型的词各一个，「其他」也用它们）
     */
    public static void appendSql(StringBuilder where, Map<String, Object> ps, Long payDay, Long payMonthMin, Long payMonthMax, List<String> categories)
    {
        checkMonthRange(payMonthMin, payMonthMax);
        if (payDay != null)
        {
            where.append(" and j.salary_type in ('hourly','daily','weekly','monthly') and j.salary_min is not null and ").append(DAILY_MIN_SQL).append(" >= :pd");
            ps.put("pd", payDay);
        }
        if (payMonthMin != null || payMonthMax != null)
        {
            where.append(" and j.salary_type in ('hourly','daily','weekly','monthly') and j.salary_min is not null");
            if (payMonthMin != null)
            {
                where.append(" and ").append(MONTHLY_MIN_SQL).append(" >= :pmn");
                ps.put("pmn", payMonthMin);
            }
            if (payMonthMax != null)
            {
                where.append(" and ").append(MONTHLY_MIN_SQL).append(monthUpperInclusive(payMonthMax) ? " <= :pmx" : " < :pmx");
                ps.put("pmx", payMonthMax);
            }
        }
        if (categories == null || categories.isEmpty())
        {
            return;
        }
        // 每个词一个参数：cw0、cw1…，各类按自己的词拼 or，「其他」= not (全部词)
        List<String> all = allCategoryWords();
        Map<String, String> names = new LinkedHashMap<String, String>();
        for (int i = 0; i < all.size(); i++)
        {
            names.put(all.get(i), "cw" + i);
            ps.put("cw" + i, KgLike.contains(all.get(i)));
        }
        StringBuilder or = new StringBuilder();
        for (String c : categories)
        {
            List<String> ws = "other".equals(c) ? all : CATEGORY_WORDS.get(c);
            StringBuilder any = new StringBuilder();
            for (String w : ws)
            {
                String n = names.get(w);
                any.append(any.length() == 0 ? "" : " or ").append("coalesce(s.store_name, '') like :").append(n).append(" or j.title like :").append(n)
                        .append(" or coalesce(j.tags, '') like :").append(n);
            }
            or.append(or.length() == 0 ? "" : " or ").append("other".equals(c) ? "not (" : "(").append(any).append(")");
        }
        where.append(" and (").append(or).append(")");
    }

    /** 全部筛选页的食宿条件（具名参数 SQL），和列表接口 KgJobMapper 同一口径 */
    public static String boardSql(String board)
    {
        if (board == null)
        {
            return "";
        }
        switch (board)
        {
            case "all":
                return " and j.board_type = 'all'";
            case "room":
                return " and j.board_type in ('all','room')";
            case "meal":
                return " and j.board_type in ('all','meal')";
            case "self":
                return " and coalesce(j.board_type, 'none') in ('none','')";
            default:
                return "";
        }
    }

    private static List<String> likes(List<String> words)
    {
        List<String> out = new ArrayList<String>();
        for (String w : words)
        {
            out.add(KgLike.contains(w));
        }
        return out;
    }
}
