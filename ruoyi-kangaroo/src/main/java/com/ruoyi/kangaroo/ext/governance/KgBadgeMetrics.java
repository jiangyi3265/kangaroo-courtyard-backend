package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Business evidence for fixed badges. Rewards remain owned by their existing point ledger. */
@Service
public class KgBadgeMetrics {
    @Autowired private KgJdbc db;
    @Autowired private KgBadgeModeration moderation;

    /* A legacy completion without an actual timestamp counts for lifetime totals only. */
    private static final String FIRST_EXPERIENCE =
            "select experience.app_user_id,min(experience.completed_at) first_completed_at,"
            + "max(experience.completed_at is null) undated from ("
            + "select a.app_user_id,case when a.volunteer_done_by='migration' then null else a.volunteer_done_time end completed_at "
            + "from kg_application a join kg_job j on j.job_id=a.job_id "
            + "where j.job_type in ('volunteer','parttime') and a.del_flag='0' "
            + "and a.status in ('onboarded','completed') and a.volunteer_done_time is not null "
            + "union all select app_user_id,checked_out_time from kg_stay_order "
            + "where status='completed' and del_flag='0') experience group by experience.app_user_id";

    public Map<String, Object> integrity(Long uid) {
        return moderation.metrics(uid);
    }

    public Map<String, Object> referral(Long uid, LocalDate quarter, LocalDateTime cutoff) {
        Map<String, Object> result = new HashMap<>(integrity(uid));
        Map<String, Object> params = p("u", uid, "start", quarter.atStartOfDay(), "until", cutoff);
        Map<String, Object> totals = db.one("select count(*) registered_people,"
                + "coalesce(sum(f.first_completed_at<:until or f.undated=1),0) experienced_people,"
                + "coalesce(sum(f.undated=0 and f.first_completed_at>=:start and f.first_completed_at<:until),0) quarter_experienced_people,"
                + "coalesce(sum(f.undated=1),0) undated_experiences "
                + "from kg_app_user invited left join (" + FIRST_EXPERIENCE + ") f on f.app_user_id=invited.user_id "
                + "where invited.invited_by=:u and invited.status='0' and invited.del_flag='0' "
                + "and coalesce(invited.invited_time,invited.register_time,invited.create_time)<:until", params);
        result.putAll(totals);
        long completed = num(result.get("quarterExperiencedPeople"));
        long rank = 0;
        if (completed > 0) {
            params.put("completed", completed);
            // Competition ranking gives all tied inviters the same place, including ties at tenth.
            rank = 1 + db.count("select count(*) from (select invited.invited_by,count(*) total "
                    + "from kg_app_user invited join kg_app_user inviter on inviter.user_id=invited.invited_by "
                    + "join (" + FIRST_EXPERIENCE + ") f on f.app_user_id=invited.user_id "
                    + "where invited.status='0' and invited.del_flag='0' and inviter.status='0' and inviter.del_flag='0' "
                    + "and coalesce(invited.invited_time,invited.register_time,invited.create_time)<:until "
                    + "and f.undated=0 and f.first_completed_at>=:start and f.first_completed_at<:until "
                    + "and not exists(select 1 from kg_badge_integrity i where i.app_user_id=inviter.user_id and i.referral_fraud='1') "
                    + "group by invited.invited_by having count(*)>:completed) ranked", params);
        }
        result.put("quarterRank", rank);
        result.put("activeReferredWorkers", db.count("select count(distinct invited.user_id) from kg_app_user invited "
                + "join kg_application a on a.app_user_id=invited.user_id join kg_job j on j.job_id=a.job_id "
                + "where invited.invited_by=:u and invited.status='0' and invited.del_flag='0' and a.del_flag='0' "
                + "and j.job_type in ('volunteer','parttime','fulltime') and a.status in ('onboarded','completed') "
                + "and a.onboard_time is not null and a.onboard_time<:until "
                + "and a.onboard_time>=coalesce(invited.invited_time,invited.register_time,invited.create_time) "
                + "and (a.offboard_time is null or a.offboard_time>=:until) "
                + "and (a.volunteer_done_time>=:until or (a.volunteer_done_time is null and a.status='onboarded'))", params));
        result.put("quarterStart", quarter.toString());
        result.put("evaluatedUntil", cutoff.toString());
        result.put("downgradedFromGold", db.count("select count(*) from kg_badge_log "
                + "where app_user_id=:u and action='gold_downgrade'",p("u",uid)) > 0);
        return result;
    }

    public Map<String, Object> jury(Long uid) {
        Map<String, Object> result = new HashMap<>(integrity(uid));
        Map<String, Object> params = p("u", uid);
        result.putAll(db.one("select coalesce(sum(v.choice is not null and v.valid='1'),0) participations,"
                + "coalesce(sum(v.choice is not null and v.valid='1' and v.quality='1'),0) quality_votes,"
                + "coalesce(sum(v.choice is not null and v.valid='1' and v.city_adopted='1'),0) adopted_votes,"
                + "coalesce(sum(v.choice is null),0) absence_count "
                + "from kg_mediation_vote v join kg_mediation_round r on r.round_id=v.round_id "
                + "where v.app_user_id=:u and r.status='closed'", params));
        Map<String, Object> juror = db.one("select disqualified,violations,suspended_until "
                + "from kg_mediation_juror where app_user_id=:u", params);
        result.put("disqualified", juror != null && ("1".equals(juror.get("disqualified")) || num(juror.get("violations")) > 0));
        result.put("suspended", juror != null && juror.get("suspendedUntil") != null
                && time(juror.get("suspendedUntil")).isAfter(now()));
        return result;
    }

    public Map<String, Object> travel(Long uid) {
        Map<String, Object> result = new HashMap<>(integrity(uid));
        List<LocalDate[]> intervals = new ArrayList<>();
        long experiences = 0;
        long missingDays = 0;
        for (Map<String, Object> application : db.list("select a.onboard_time,a.arrival_date,a.volunteer_days,a.volunteer_done_time,a.offboard_time,j.job_type "
                + "from kg_application a join kg_job j on j.job_id=a.job_id where a.app_user_id=:u and a.del_flag='0' "
                + "and ((j.job_type in ('volunteer','parttime') and a.status in ('onboarded','completed') and a.volunteer_done_time is not null) "
                + "or (j.job_type='fulltime' and ((a.status='completed' and a.offboard_time is null) "
                + "or (a.status in ('onboarded','completed') and a.onboard_time is not null "
                + "and a.offboard_time>=a.onboard_time and a.offboard_time<=now()))))", p("u", uid))) {
            experiences++;
            LocalDate start = date(application.get("onboardTime"));
            if ("fulltime".equals(application.get("jobType"))) {
                LocalDate departed = date(application.get("offboardTime"));
                if (start != null && departed != null && !departed.isBefore(start))
                    intervals.add(new LocalDate[]{start,departed.plusDays(1)});
                else missingDays++;
                continue;
            }
            if (start == null) start = date(application.get("arrivalDate"));
            LocalDate completed = date(application.get("volunteerDoneTime"));
            long verifiedDays = num(application.get("volunteerDays"));
            if (start == null || completed == null || verifiedDays <= 0 || completed.isBefore(start)) {
                missingDays++;
                continue;
            }
            LocalDate end = start.plusDays(Math.min(verifiedDays, ChronoUnit.DAYS.between(start, completed) + 1));
            intervals.add(new LocalDate[]{start, end});
        }
        for (Map<String, Object> stay : db.list("select nights,coalesce(verify_time,check_in) actual_start,"
                + "coalesce(checked_out_time,check_out) actual_end from kg_stay_order "
                + "where app_user_id=:u and status='completed' and del_flag='0'", p("u", uid))) {
            experiences++;
            LocalDate start = date(stay.get("actualStart"));
            LocalDate end = date(stay.get("actualEnd"));
            long booked = num(stay.get("nights"));
            if (start == null || end == null || !end.isAfter(start) || booked <= 0) {
                missingDays++;
                continue;
            }
            intervals.add(new LocalDate[]{start, start.plusDays(Math.min(booked, ChronoUnit.DAYS.between(start, end)))});
        }
        result.put("completedExperiences", experiences);
        result.put("travelDays", unionDays(intervals));
        result.put("experiencesWithoutVerifiedDays", missingDays);
        return result;
    }

    private static LocalDate date(Object value) {
        String text = str(value);
        if (text.length() < 10) return null;
        try { return LocalDate.parse(text.substring(0, 10)); }
        catch (java.time.format.DateTimeParseException exception) { return null; }
    }

    static long unionDays(List<LocalDate[]> intervals) {
        intervals.sort(Comparator.comparing(interval -> interval[0]));
        long days = 0;
        LocalDate end = null;
        for (LocalDate[] interval : intervals) {
            LocalDate start = end != null && end.isAfter(interval[0]) ? end : interval[0];
            if (interval[1].isAfter(start)) days += ChronoUnit.DAYS.between(start, interval[1]);
            if (end == null || interval[1].isAfter(end)) end = interval[1];
        }
        return days;
    }
}
