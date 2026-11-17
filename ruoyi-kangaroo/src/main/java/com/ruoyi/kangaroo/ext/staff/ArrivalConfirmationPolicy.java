package com.ruoyi.kangaroo.ext.staff;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 到店页、员工提示及写入校验共用同一预计日期，以中国日历日为准。 */
public final class ArrivalConfirmationPolicy
{
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private ArrivalConfirmationPolicy() { }

    public static String date(Object value)
    {
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate().toString();
        if (value instanceof Date) return ((Date) value).toInstant().atZone(CHINA).toLocalDate().toString();
        String text = String.valueOf(value).trim();
        return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text).toString();
    }

    /** 用户填过的预计到店日期优先；未填时回退到报名约定日期。 */
    public static String expectedDate(Object reported, Object agreed)
    {
        String value = date(reported);
        return value == null ? date(agreed) : value;
    }

    public static String waitReason(Object expected)
    {
        return waitReason(expected, LocalDate.now(CHINA));
    }

    public static String waitReason(Object expected, LocalDate today)
    {
        String value = date(expected);
        if (value == null || !today.isBefore(LocalDate.parse(value))) return null;
        return "预计到店日期是 " + value + "，当天起才能确认到店；如提前到店，请先更新到店信息，再由门店核实确认";
    }

    /** 写入方先锁报名，与用户更新预计到店日期串行；锁后使用当前读，避免旧快照绕过日期校验。 */
    public static String waitReason(KgJdbc db, Long appId, boolean lock)
    {
        if (lock) db.one("select app_id from kg_application where app_id=:a for update", KgJdbc.p("a", appId));
        Map<String, Object> row = db.one("select date_format(coalesce(r.arrive_date, a.arrival_date), '%Y-%m-%d') expected_date "
                + "from kg_application a left join kg_app_arrival r on r.app_id=a.app_id and r.app_user_id=a.app_user_id "
                + "where a.app_id=:a" + (lock ? " for update" : ""), KgJdbc.p("a", appId));
        return waitReason(row == null ? null : row.get("expectedDate"));
    }
}
