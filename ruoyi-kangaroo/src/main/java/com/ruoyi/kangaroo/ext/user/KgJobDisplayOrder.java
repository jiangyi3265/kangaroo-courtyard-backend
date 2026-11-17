package com.ruoyi.kangaroo.ext.user;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;

/** Public recruitment ordering. Only currently published, unexpired urgency has priority. */
public final class KgJobDisplayOrder
{
    private KgJobDisplayOrder() { }

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] LABELS = { "", "一般招募", "较急招募", "紧急招募", "特急招募" };

    /** Keep the public badge and its deadline consistent across mapper and JDBC card endpoints. */
    public static void decorate(Map<String, Object> card, Object rawLevel, Object status, Object rawDeadline)
    {
        int level = 0;
        try { level = Math.max(0, Math.min(4, Integer.parseInt(String.valueOf(rawLevel)))); }
        catch (NumberFormatException ignored) { }
        Instant deadline = deadline(rawDeadline);
        boolean validDeadline = rawDeadline == null || deadline != null;
        if (!"published".equals(status) || !validDeadline || (deadline != null && !deadline.isAfter(Instant.now()))) level = 0;
        card.put("urgentLevel", level);
        card.put("urgentLabel", LABELS[level]);
        card.put("urgentDeadline", level > 0 && deadline != null ? DEADLINE.format(deadline.atZone(CHINA)) : null);
    }

    private static Instant deadline(Object value)
    {
        if (value instanceof Date) return Instant.ofEpochMilli(((Date)value).getTime());
        if (value instanceof LocalDateTime) return ((LocalDateTime)value).atZone(CHINA).toInstant();
        if (value == null) return null;
        try {
            String text = String.valueOf(value).replace('T', ' ');
            if (text.length() > 19) text = text.substring(0, 19);
            return LocalDateTime.parse(text, DEADLINE).atZone(CHINA).toInstant();
        } catch (java.time.format.DateTimeParseException ignored) { return null; }
    }

    // All callers use the fixed alias j. Never interpolate a request-supplied SQL identifier here.
    public static String effectiveUrgency()
    {
        return "case when j.status = 'published' and (j.urgent_deadline is null or j.urgent_deadline > now()) "
                + "then coalesce(j.urgent_level, 0) else 0 end";
    }

    public static String sql()
    {
        return effectiveUrgency() + " desc, j.sort_no asc, coalesce(j.publish_time, j.create_time) desc, j.job_id desc";
    }
}
