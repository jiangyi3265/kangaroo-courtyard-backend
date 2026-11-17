package com.ruoyi.kangaroo.ext.stay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ruoyi.common.exception.ServiceException;

/**
 * 房型「一周 7 天的每晚价」（V70，2026-11-03 主人：「必须7天，填写7天的价格页面优化一下不要弹窗」）。
 * 存在 kg_stay_room_type.week_price_fen：周一到周日 7 个整数（分），逗号分隔；空 = 每天都按 base_price_fen（老房型照旧）。
 * 填了 7 天价时 base_price_fen 跟着存成 7 天里最低的那个：列表「¥X 起」、价格筛选、早鸟划线价这些只读 base 的地方不用改。
 * 某一晚的价：房态日历单独设了当晚价用当晚价，否则按这一天是星期几取 7 天价，再没有就用基础价（{@link #nightly}）。
 */
public final class KgRoomWeekPrice
{
    /** 每晚价上下限（分）：和员工端房型基础价同一口径，1 元 – 10 万元 */
    static final long MIN_FEN = 100L;
    static final long MAX_FEN = 10000000L;

    private KgRoomWeekPrice() { }

    /** Editor total in integer fen. Persistence still uses seven weekday prices. */
    public static long total(Map<String, Object> roomType)
    {
        long[] week = parse(roomType.get("weekPriceFen"));
        if (week == null) return Math.multiplyExact(KgStayBrowseService.fen(roomType.get("basePriceFen")), 7L);
        long total = 0;
        for (long night : week) total = Math.addExact(total, night);
        return total;
    }

    public static long checkedTotal(Object raw)
    {
        try
        {
            long total = new java.math.BigDecimal(raw == null ? "" : String.valueOf(raw).trim()).longValueExact();
            if (total < MIN_FEN * 7 || total > MAX_FEN * 7) throw new ArithmeticException();
            return total;
        }
        catch (NumberFormatException | ArithmeticException e)
        {
            throw new ServiceException("7天总价需为7–700000元，并精确到分", 422);
        }
    }

    /** Distribute remainder fen once; any seven consecutive nights sum to the entered total. */
    public static String splitTotal(Object raw)
    {
        long total = checkedTotal(raw), each = total / 7, remainder = total % 7;
        List<Long> week = new ArrayList<Long>();
        for (int i = 0; i < 7; i++) week.add(each + (i < remainder ? 1 : 0));
        return normalize(week);
    }

    /** Changes unrelated to price must not flatten an existing weekday schedule. */
    public static void applyTotal(Map<String, Object> columns, Object raw, Map<String, Object> previous)
    {
        long requested = checkedTotal(raw);
        if (previous != null && total(previous) == requested) return;
        String week = splitTotal(requested);
        columns.put("week_price_fen", week);
        columns.put("base_price_fen", min(week));
    }

    /** 解析存的值；不是正好 7 个正整数（含空）就当没有，退回基础价 */
    public static long[] parse(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty())
        {
            return null;
        }
        String[] parts = s.split(",");
        if (parts.length != 7)
        {
            return null;
        }
        long[] out = new long[7];
        try
        {
            for (int i = 0; i < 7; i++)
            {
                out[i] = Long.parseLong(parts[i].trim());
                if (out[i] < MIN_FEN)
                {
                    return null;
                }
            }
        }
        catch (NumberFormatException e)
        {
            return null;
        }
        return out;
    }

    /** 某天的默认每晚价（不含房态日历单独设的当晚价）：有 7 天价按星期几取，否则基础价 */
    public static long nightly(Map<String, Object> roomType, LocalDate day)
    {
        long[] week = parse(roomType.get("weekPriceFen"));
        if (week != null && day != null)
        {
            return week[day.getDayOfWeek().getValue() - 1];
        }
        return KgStayBrowseService.fen(roomType.get("basePriceFen"));
    }

    /**
     * 员工端 / 后台传来的 7 天价 → 存库的字符串。接受数组 [周一..周日] 或逗号分隔的字符串；
     * 必须 7 个都填、每个 1–100000 元（整数分），否则报错（不悄悄补默认值）。
     */
    public static String normalize(Object raw)
    {
        List<Object> items = new ArrayList<Object>();
        if (raw instanceof List)
        {
            items.addAll((List<?>) raw);
        }
        else if (raw != null && !String.valueOf(raw).trim().isEmpty())
        {
            for (String p : String.valueOf(raw).split(","))
            {
                items.add(p);
            }
        }
        if (items.size() != 7)
        {
            throw new ServiceException("请把周一到周日 7 天的价格都填上");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++)
        {
            Object v = items.get(i);
            long fen;
            try
            {
                String t = v == null ? "" : String.valueOf(v).trim();
                if (t.endsWith(".0"))
                {
                    t = t.substring(0, t.length() - 2);
                }
                fen = Long.parseLong(t);
            }
            catch (NumberFormatException e)
            {
                throw new ServiceException(DAYS[i] + "的价格没填或不是整数（分）");
            }
            if (fen < MIN_FEN || fen > MAX_FEN)
            {
                throw new ServiceException(DAYS[i] + "的价格需在 1–100000 元之间");
            }
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(fen);
        }
        return sb.toString();
    }

    /** Sum all seven weekday prices; never multiply the cheapest night by seven. */
    public static long saleWeek(Map<String,Object> roomType) {
        long total=0;
        LocalDate monday=LocalDate.of(2026,11,9);
        for(int i=0;i<7;i++) total=Math.addExact(total,KgSettlementMath.sale(nightly(roomType,monday.plusDays(i)),roomType.get("opType")));
        return total;
    }

    /** 7 天里最低的那个（存进 base_price_fen） */
    public static long min(String normalized)
    {
        long[] week = parse(normalized);
        long m = Long.MAX_VALUE;
        for (long v : week)
        {
            m = Math.min(m, v);
        }
        return m;
    }

    static final String[] DAYS = { "周一", "周二", "周三", "周四", "周五", "周六", "周日" };
}
