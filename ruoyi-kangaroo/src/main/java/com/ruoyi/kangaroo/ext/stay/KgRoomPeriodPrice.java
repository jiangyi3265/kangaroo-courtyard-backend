package com.ruoyi.kangaroo.ext.stay;

import java.math.BigDecimal;
import java.util.Map;
import com.ruoyi.common.exception.ServiceException;

/** Optional room totals for exactly 15 or 30 nights, in merchant-base integer fen. */
public final class KgRoomPeriodPrice {
    private KgRoomPeriodPrice() { }

    public static Long checkedTotal(Object value, int nights) {
        if (nights != 15 && nights != 30) throw new IllegalArgumentException("Unsupported stay duration");
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        try {
            long total = new BigDecimal(String.valueOf(value).trim()).longValueExact();
            if (total < KgRoomWeekPrice.MIN_FEN * nights || total > KgRoomWeekPrice.MAX_FEN * nights)
                throw new ArithmeticException();
            return total;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new ServiceException((nights == 15 ? "半个月" : "一个月") + "总价需为" + nights + "–"
                    + (100000L * nights) + "元，并精确到分；不设置可留空", 422);
        }
    }

    public static Long total(Map<String, Object> room, long nights) {
        if (nights != 15 && nights != 30) return null;
        return checkedTotal(room.get(nights == 15 ? "halfMonthTotalFen" : "monthTotalFen"), (int)nights);
    }

    /** Split remainder fen once, so nightly snapshots sum exactly to the configured total. */
    public static long nightly(long total, int nights, int index) {
        return total / nights + (index < total % nights ? 1 : 0);
    }

    public static void validateColumns(Map<String, Object> columns) {
        if (columns.containsKey("half_month_total_fen"))
            columns.put("half_month_total_fen", checkedTotal(columns.get("half_month_total_fen"), 15));
        if (columns.containsKey("month_total_fen"))
            columns.put("month_total_fen", checkedTotal(columns.get("month_total_fen"), 30));
    }
}
