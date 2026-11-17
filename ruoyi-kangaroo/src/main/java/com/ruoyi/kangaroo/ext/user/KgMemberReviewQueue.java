package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Initial human review starts at detection/reporting, before the seven-day appeal period. */
@Service
public class KgMemberReviewQueue {
    private static final String KEY = "kangaroo.member.review.workdays.";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 国办发明电〔2025〕7号, also used by the existing 2026 holiday calendar.
    // https://www.beijing.gov.cn/cs/gncs/zcwj/202603/t20260327_4568275.html
    private static final String WORKDAYS_2026 = "2026-01-04,2026-02-14,2026-02-28,2026-05-09,2026-09-20,2026-10-10";
    @Autowired private KgJdbc db;

    public Map<String, Object> overview(int page) {
        Map<String, Object> result = db.page("select r.report_id,r.target_type,r.target_id,r.reason,r.detail,r.create_time,r.create_by,"
                + "case when r.target_type='review' then v.app_user_id else r.target_id end app_user_id "
                + "from kg_report r left join kg_review v on r.target_type='review' and v.review_id=r.target_id "
                + "where r.del_flag='0' and r.status='pending' and r.target_type in ('review','user') "
                + "order by r.create_time,r.report_id", p(), page, 20);
        Map<Integer, Set<LocalDate>> holidays = new HashMap<>();
        Map<Integer, Set<LocalDate>> working = new HashMap<>();
        for (Map<String, Object> year : calendars()) {
            int value = ((Number) year.get("year")).intValue();
            if (Boolean.TRUE.equals(year.get("confirmed"))) {
                working.put(value, dates(String.valueOf(year.get("dates")), value));
                holidays.put(value, new HashSet<>());
            }
        }
        for (Map<String, Object> row : db.list("select holiday_date from kg_member_holiday", p())) {
            LocalDate date = LocalDate.parse(String.valueOf(row.get("holidayDate")));
            if (holidays.containsKey(date.getYear())) holidays.get(date.getYear()).add(date);
        }
        LocalDateTime now = LocalDateTime.now(KgMembershipPolicy.ZONE);
        for (Object value : (List<?>) result.get("rows")) {
            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) value;
            LocalDateTime received;
            try {
                received = LocalDateTime.parse(String.valueOf(row.get("createTime")), DATE_TIME);
            } catch (java.time.format.DateTimeParseException error) {
                row.put("reviewDeadline", null);
                row.put("deadlineState", "receipt_pending");
                row.put("source", String.valueOf(row.get("createBy")).startsWith("system:") ? "系统检测" : "用户或门店举报");
                continue;
            }
            LocalDateTime deadline = deadline(received, holidays, working);
            row.put("reviewDeadline", deadline == null ? null : deadline.format(DATE_TIME));
            row.put("deadlineState", deadline == null ? "calendar_pending" : now.isAfter(deadline) ? "overdue"
                    : now.toLocalDate().equals(deadline.toLocalDate()) ? "due_today" : "pending");
            row.put("source", String.valueOf(row.get("createBy")).startsWith("system:") ? "系统检测" : "用户或门店举报");
        }
        return result;
    }

    /** Preserve receipt time while advancing three confirmed Chinese business dates. */
    static LocalDateTime deadline(LocalDateTime received, Map<Integer, Set<LocalDate>> holidays,
                                  Map<Integer, Set<LocalDate>> working) {
        if (!holidays.containsKey(received.getYear()) || !working.containsKey(received.getYear())) return null;
        LocalDate day = received.toLocalDate();
        int count = 0;
        while (count < 3) {
            day = day.plusDays(1);
            if (!holidays.containsKey(day.getYear()) || !working.containsKey(day.getYear())) return null;
            boolean work = working.get(day.getYear()).contains(day)
                    || day.getDayOfWeek().getValue() <= 5 && !holidays.get(day.getYear()).contains(day);
            if (work) count++;
        }
        return LocalDateTime.of(day, received.toLocalTime());
    }

    public List<Map<String, Object>> calendars() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> year : db.list("select calendar_year,confirmed from kg_member_calendar_year order by calendar_year", p())) {
            int value = ((Number) year.get("calendarYear")).intValue();
            Map<String, Object> config = db.one("select config_value from sys_config where config_key=:k limit 1", p("k", KEY + value));
            String dates = config == null ? value == 2026 ? WORKDAYS_2026 : "" : String.valueOf(config.get("configValue"));
            boolean configured = config != null || value == 2026;
            try { dates(dates, value); } catch (ServiceException error) { configured = false; }
            rows.add(p("year", value, "dates", dates.replace(',', '\n'), "confirmed", configured && "1".equals(year.get("confirmed"))));
        }
        return rows;
    }

    /** Called in the existing calendar transaction after its year row has been locked. */
    public void saveWorkdays(int year, Map<String, Object> body, String operator, Set<LocalDate> holidays) {
        if (!body.containsKey("workdates")) return; // Existing calendar clients retain their established contract.
        if (!Boolean.TRUE.equals(body.get("workdaysConfirmed"))) throw new ServiceException("请核对该年份调休上班日期", 422);
        Set<LocalDate> values = dates(String.valueOf(body.getOrDefault("workdates", "")), year);
        if (!Collections.disjoint(values, holidays)) throw new ServiceException("同一天不能同时设为放假和调休上班", 422);
        String joined = String.join(",", values.stream().map(LocalDate::toString).toArray(String[]::new));
        if (joined.length() > 500) throw new ServiceException("调休日期数量过多，请核对全年日历", 422);
        String key = KEY + year;
        Map<String, Object> old = db.one("select config_id from sys_config where config_key=:k for update", p("k", key));
        if (old == null) db.insert("sys_config", p("config_name", year + "年会员复核调休工作日", "config_key", key,
                "config_value", joined, "config_type", "N", "create_by", operator, "create_time", LocalDateTime.now(KgMembershipPolicy.ZONE)));
        else db.update("update sys_config set config_value=:v,update_by=:by,update_time=now() where config_key=:k", p("v", joined, "by", operator, "k", key));
    }

    static Set<LocalDate> dates(String text, int year) {
        Set<LocalDate> values = new TreeSet<>();
        try {
            for (String part : text.trim().split("[\\s,，]+")) {
                if (part.isEmpty()) continue;
                LocalDate day = LocalDate.parse(part);
                if (day.getYear() != year) throw new IllegalArgumentException();
                values.add(day);
            }
        } catch (RuntimeException error) {
            throw new ServiceException("调休日期应为所选年份的 YYYY-MM-DD", 422);
        }
        return values;
    }

    /** Existing report uniqueness preserves the first detection time on reconciliation retries. */
    public void detect(Map<String, Object> review, String reason) {
        if (!reason.startsWith("同日评价过多") && !reason.startsWith("与已有评价重复率")) return;
        db.update("insert ignore into kg_report(target_type,target_id,reporter_id,reason,detail,status,create_by,create_time) "
                + "values('review',:id,0,'会员评价待人工复核',:reason,'pending','system:membership',now())",
                p("id", review.get("reviewId"), "reason", reason));
    }
}
