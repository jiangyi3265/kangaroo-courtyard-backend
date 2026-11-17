package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgMemberReviewDeadlineTest {
    private Map<Integer, Set<LocalDate>> year(int year, String dates) {
        return new HashMap<>(Collections.singletonMap(year, KgMemberReviewQueue.dates(dates, year)));
    }

    @Test void threeBusinessDaysStartAfterReceiptAndPreserveTime() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 6, 16, 45);
        assertEquals(LocalDateTime.of(2026, 11, 11, 16, 45),
                KgMemberReviewQueue.deadline(start, year(2026, ""), year(2026, "")));
    }

    @Test void officialMakeupSundayCountsAndHolidayDoesNot() {
        LocalDateTime start = LocalDateTime.of(2025, 12, 31, 10, 30);
        Map<Integer, Set<LocalDate>> holidays = year(2025, "");
        holidays.putAll(year(2026, "2026-01-01,2026-01-02,2026-01-03"));
        Map<Integer, Set<LocalDate>> working = year(2025, "");
        working.putAll(year(2026, "2026-01-04"));
        assertEquals(LocalDateTime.of(2026, 1, 6, 10, 30), KgMemberReviewQueue.deadline(start, holidays, working));
    }

    @Test void missingReceiptYearOrCrossYearCalendarNeverGuessesDeadline() {
        assertNull(KgMemberReviewQueue.deadline(LocalDateTime.of(2025, 12, 25, 9, 0), year(2026, ""), year(2026, "")));
        assertNull(KgMemberReviewQueue.deadline(LocalDateTime.of(2026, 12, 31, 9, 0), year(2026, ""), year(2026, "")));
    }

    @Test void datesRejectWrongYearAndInvalidDaysAndDeduplicate() {
        assertThrows(ServiceException.class, () -> KgMemberReviewQueue.dates("2027-01-02", 2026));
        assertThrows(ServiceException.class, () -> KgMemberReviewQueue.dates("2026-02-30", 2026));
        assertEquals(1, KgMemberReviewQueue.dates("2026-01-04\n2026-01-04", 2026).size());
        assertTrue(KgMemberReviewQueue.dates("", 2027).isEmpty());
    }

    @Test void suspiciousDetectionUsesReportUniqueKeyWithoutResettingReceiptTime() {
        KgJdbc db = mock(KgJdbc.class);
        KgMemberReviewQueue queue = new KgMemberReviewQueue();
        ReflectionTestUtils.setField(queue, "db", db);
        queue.detect(p("reviewId", 27), "同日评价过多，待人工核验");
        queue.detect(p("reviewId", 27), "同日评价过多，待人工核验");
        verify(db, times(2)).update(startsWith("insert ignore into kg_report"), eq(p("id", 27, "reason", "同日评价过多，待人工核验")));
        queue.detect(p("reviewId", 28), "文字不足50字");
        queue.detect(p("reviewId", 29), "等待实拍照片核验");
        queue.detect(p("reviewId", 30), "");
        verifyNoMoreInteractions(db);
    }

    @Test void workdayConfigurationNeedsConfirmationAndCannotOverlapHoliday() {
        KgJdbc db = mock(KgJdbc.class);
        KgMemberReviewQueue queue = new KgMemberReviewQueue();
        ReflectionTestUtils.setField(queue, "db", db);
        assertThrows(ServiceException.class, () -> queue.saveWorkdays(2026, p("workdates", "2026-01-04"), "admin:1", Collections.emptySet()));
        assertThrows(ServiceException.class, () -> queue.saveWorkdays(2026, p("workdates", "2026-01-04", "workdaysConfirmed", true), "admin:1", new HashSet<>(Arrays.asList(LocalDate.of(2026,1,4)))));
        queue.saveWorkdays(2026, p(), "admin:1", Collections.emptySet());
        verifyNoInteractions(db);
    }

    @Test void confirmedEmptyMakeupCalendarIsPersistedAndDoesNotMeanUnconfigured() {
        KgJdbc db = mock(KgJdbc.class);
        KgMemberReviewQueue queue = new KgMemberReviewQueue();
        ReflectionTestUtils.setField(queue, "db", db);
        when(db.one(startsWith("select config_id"), anyMap())).thenReturn(null);
        queue.saveWorkdays(2027, p("workdates", "", "workdaysConfirmed", true), "admin:1", Collections.emptySet());
        verify(db).insert(eq("sys_config"), argThat(row -> "kangaroo.member.review.workdays.2027".equals(row.get("config_key")) && "".equals(row.get("config_value"))));
        when(db.list(contains("from kg_member_calendar_year"), anyMap())).thenReturn(Arrays.asList(p("calendarYear",2027,"confirmed","1")));
        when(db.one(startsWith("select config_value"), anyMap())).thenReturn(p("configValue", ""));
        assertEquals(true, queue.calendars().get(0).get("confirmed"));
    }

    @Test void queueContainsPendingReportsAndKeepsHistoricalReceiptTime() {
        KgJdbc db = mock(KgJdbc.class);
        KgMemberReviewQueue queue = new KgMemberReviewQueue();
        ReflectionTestUtils.setField(queue, "db", db);
        Map<String,Object> old = p("reportId",3,"createTime","2026-01-08 11:23:00","createBy","app:1");
        when(db.page(contains("from kg_report"), anyMap(), eq(1), eq(20))).thenReturn(p("rows",Arrays.asList(old),"total",1));
        when(db.list(contains("from kg_member_calendar_year"), anyMap())).thenReturn(Arrays.asList(p("calendarYear",2026,"confirmed","1")));
        when(db.one(startsWith("select config_value"), anyMap())).thenReturn(null);
        queue.overview(1);
        assertEquals("2026-01-13 11:23:00",old.get("reviewDeadline"));
        verify(db).page(contains("r.status='pending' and r.target_type in ('review','user')"), anyMap(), eq(1), eq(20));
        verify(db,never()).update(anyString(),anyMap());
    }

    @Test void historicalMissingReceiptAndUnconfirmedCalendarRemainVisible() {
        KgJdbc db = mock(KgJdbc.class);
        KgMemberReviewQueue queue = new KgMemberReviewQueue();
        ReflectionTestUtils.setField(queue, "db", db);
        Map<String,Object> missing = p("reportId",4,"createTime",null,"createBy","app:1");
        Map<String,Object> old = p("reportId",5,"createTime","2025-12-01 08:00:00","createBy","system:membership");
        when(db.page(contains("from kg_report"), anyMap(), eq(1), eq(20))).thenReturn(p("rows",Arrays.asList(missing,old),"total",2));
        queue.overview(1);
        assertEquals("receipt_pending",missing.get("deadlineState"));
        assertEquals("calendar_pending",old.get("deadlineState"));
        assertEquals("系统检测",old.get("source"));
        assertNull(missing.get("reviewDeadline"));
        assertNull(old.get("reviewDeadline"));
    }

    @Test void corruptedWorkingCalendarIsMarkedUnconfirmed() {
        KgJdbc db = mock(KgJdbc.class);
        KgMemberReviewQueue queue = new KgMemberReviewQueue();
        ReflectionTestUtils.setField(queue, "db", db);
        when(db.list(contains("from kg_member_calendar_year"), anyMap())).thenReturn(Arrays.asList(p("calendarYear",2026,"confirmed","1")));
        when(db.one(startsWith("select config_value"), anyMap())).thenReturn(p("configValue","2027-01-03"));
        assertEquals(false,queue.calendars().get(0).get("confirmed"));
    }

    @Test void isolatedMysqlQueueParsesJdbcTimeAndDeduplicatesDetection() {
        String database = System.getenv("KG_MEMBER_TEST_DB");
        Assumptions.assumeTrue(database != null && database.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + database
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sessionVariables=time_zone=%27%2B08:00%27",
                System.getenv("KG_MEMBER_TEST_USER"), System.getenv("KG_TEST_DB_PASSWORD"));
        KgJdbc db = new KgJdbc(source);
        KgMemberReviewQueue queue = new KgMemberReviewQueue();
        ReflectionTestUtils.setField(queue, "db", db);
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
            status.setRollbackOnly();
            db.update("delete from kg_report", p());
            db.update("delete from sys_config where config_key='kangaroo.member.review.workdays.2026'", p());
            db.update("insert into kg_member_calendar_year(calendar_year,confirmed) values(2026,'1') on duplicate key update confirmed='1'", p());
            long user = db.insert("kg_app_user", p("openid", "review-deadline-" + UUID.randomUUID(), "nickname", "复核待办测试"));
            long review = db.insert("kg_review", p("target_type", "room", "target_id", 1, "app_user_id", user,
                    "rating", 3, "content", "隔离测试评价", "create_time", "2026-01-08 11:23:00"));
            Map<String,Object> input = p("reviewId", review);
            queue.detect(input, "同日评价过多，待人工核验");
            db.update("update kg_report set create_time='2026-01-08 11:23:00' where target_type='review' and target_id=:id and reporter_id=0", p("id", review));
            queue.detect(input, "同日评价过多，待人工核验");
            Map<String,Object> response = queue.overview(1);
            assertEquals(1L, response.get("total"));
            Map<?,?> item = (Map<?,?>) ((List<?>) response.get("rows")).get(0);
            assertEquals("2026-01-08 11:23:00", item.get("createTime"));
            assertEquals("2026-01-13 11:23:00", item.get("reviewDeadline"));
            assertEquals("overdue", item.get("deadlineState"));
            assertEquals(user, ((Number) item.get("appUserId")).longValue());
            db.update("update kg_report set status='rejected' where target_type='review' and target_id=:id and reporter_id=0", p("id", review));
            queue.detect(input, "同日评价过多，待人工核验");
            assertEquals(0L, queue.overview(1).get("total"));
            assertEquals(1, db.count("select count(*) from kg_report where target_type='review' and target_id=:id and reporter_id=0", p("id", review)));
            return null;
        });
    }
}
