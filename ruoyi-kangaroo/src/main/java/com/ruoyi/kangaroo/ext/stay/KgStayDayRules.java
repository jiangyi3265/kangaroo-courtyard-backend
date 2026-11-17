package com.ruoyi.kangaroo.ext.stay;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.ruoyi.common.exception.ServiceException;

/**
 * 「某一天的入住情况」共用口径（纯函数，不连库）：员工端每日概况（KgStaffDailyService）和后台数据看板（KgDashboardService）都用它，
 * 两处数字才对得上。2026-10-18 从 KgStaffDailyService 里抽出来，员工端的行为一点没改。
 *
 * 口径（d = 所选日期）：
 *   占着房的订单只算 confirmed 待入住 / checked_in 已入住 / completed 已完成（退款中的已经还了房量，不算）
 *   · 今日入住 = 入住日是 d 的订单；其中「已到店」= 已办理入住（已入住 / 已完成）
 *   · 在住     = d 这一晚有人住的订单（入住日 ≤ d < 实际离店日；含当天待到店的；提前退房的从退房那天起不算）
 *   · 今日离店 = 已入住 / 已完成、离店日（提前退房按实际退房日）是 d 的订单；其中「已退房」= 已完成；
 *                看今天时，过了离店日还没办退房的已入住订单也算进今日离店（和订单台「今日离店」一致）
 *   · 入住率   = d 这一晚在住订单的间（床位）数之和 ÷ d 这天的房态库存，超过 100% 按 100% 封顶；没有库存时 null（页面写「—」）
 *   · 库存     = 上架房型（status = '0'）当天在房态日历里单独设了库存用当天的，否则用房型默认库存
 */
public final class KgStayDayRules
{
    /** 占着房的订单状态（入住 / 在住 / 离店都只看这几种） */
    public static final List<String> STAY_STATUS = Collections.unmodifiableList(Arrays.asList("confirmed", "checked_in", "completed"));
    /** 往前最多看一年、往后最多看 180 天（订房最多提前 180 天） */
    public static final int PAST_DAYS = 366;
    public static final int AHEAD_DAYS = 180;

    private KgStayDayRules()
    {
    }

    /** 一张订单在 d 这一天算进哪些数字 */
    public static final class Mark
    {
        /** 支付时间在 d 当天 */
        public boolean newOrder;
        /** 此刻在「退款审核中」 */
        public boolean refundPending;
        public boolean arrival;
        public boolean arrived;
        public boolean inHouse;
        public boolean departure;
        public boolean departed;
        /** 在住时占的间（床位）数、住客数（至少 1）；不在住为 0 */
        public long units;
        public long guests;
    }

    /** date = yyyy-MM-dd（空 / today 为今天）；超出「近一年到未来 180 天」或格式不对时报 422 */
    public static LocalDate pickDay(String date, LocalDate today)
    {
        String v = date == null ? "" : date.trim();
        if (v.isEmpty() || "today".equals(v))
        {
            return today;
        }
        LocalDate d;
        try
        {
            d = LocalDate.parse(v);
        }
        catch (DateTimeParseException e)
        {
            throw new ServiceException("日期格式应为 YYYY-MM-DD", 422);
        }
        if (d.isBefore(today.minusDays(PAST_DAYS)) || d.isAfter(today.plusDays(AHEAD_DAYS)))
        {
            throw new ServiceException("只能查看近一年到未来 " + AHEAD_DAYS + " 天的概况", 422);
        }
        return d;
    }

    public static LocalDate asDate(Object v)
    {
        if (v == null)
        {
            return null;
        }
        if (v instanceof java.sql.Date)
        {
            return ((java.sql.Date) v).toLocalDate();
        }
        if (v instanceof java.sql.Timestamp)
        {
            return ((java.sql.Timestamp) v).toLocalDateTime().toLocalDate();
        }
        if (v instanceof java.util.Date)
        {
            return new java.sql.Timestamp(((java.util.Date) v).getTime()).toLocalDateTime().toLocalDate();
        }
        if (v instanceof LocalDate)
        {
            return (LocalDate) v;
        }
        if (v instanceof LocalDateTime)
        {
            return ((LocalDateTime) v).toLocalDate();
        }
        String s = String.valueOf(v).trim();
        if (s.length() < 10)
        {
            return null;
        }
        try
        {
            return LocalDate.parse(s.substring(0, 10));
        }
        catch (DateTimeParseException e)
        {
            return null;
        }
    }

    public static long num(Object v)
    {
        if (v instanceof Number)
        {
            return ((Number) v).longValue();
        }
        try
        {
            return v == null ? 0 : Long.parseLong(String.valueOf(v).trim());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    private static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v);
    }

    /** 入住率（基点，万分之几）；当天没有库存时 null（页面写「—」）。超过 100% 时按 100% 封顶 */
    public static Integer occupancyBp(long units, long stock)
    {
        if (stock <= 0)
        {
            return null;
        }
        return (int) Math.min(10000, Math.round(units * 10000.0 / stock));
    }

    /** 实际离店日：提前退房（已完成、退房时间早于离店日）按退房那天，否则按订单的离店日 */
    public static LocalDate effectiveOut(String status, LocalDate checkOut, LocalDate checkedOut)
    {
        if ("completed".equals(status) && checkedOut != null && checkOut != null && checkedOut.isBefore(checkOut))
        {
            return checkedOut;
        }
        return checkOut;
    }

    /**
     * 一张订单（status / checkIn / checkOut / units / guests / paidTime / checkedOutTime）在 d 这一天的标记。
     * isToday = 看的是不是今天（只影响「过了离店日还没退房」算不算今日离店）。
     */
    public static Mark mark(Map<String, Object> o, LocalDate d, boolean isToday)
    {
        Mark m = new Mark();
        String st = str(o.get("status"));
        LocalDate in = asDate(o.get("checkIn"));
        LocalDate out = asDate(o.get("checkOut"));
        LocalDate paid = asDate(o.get("paidTime"));
        m.newOrder = paid != null && paid.equals(d);
        m.refundPending = "refund_pending".equals(st);
        if (!STAY_STATUS.contains(st) || in == null || out == null)
        {
            return m;
        }
        boolean arrivedSt = "checked_in".equals(st) || "completed".equals(st);
        LocalDate eff = effectiveOut(st, out, asDate(o.get("checkedOutTime")));
        if (in.equals(d))
        {
            m.arrival = true;
            m.arrived = arrivedSt;
        }
        if (!in.isAfter(d) && eff.isAfter(d))
        {
            m.inHouse = true;
            m.units = Math.max(1, num(o.get("units")));
            m.guests = Math.max(1, num(o.get("guests")));
        }
        m.departure = arrivedSt && (eff.equals(d) || (isToday && "checked_in".equals(st) && out.isBefore(d)));
        m.departed = m.departure && "completed".equals(st);
        return m;
    }
}
