package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.KgGovernanceExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;

class KgGovernanceAllModulesRecheckTest {
    final KgGovernanceExecutionTest fixture = new KgGovernanceExecutionTest();
    Object originalThreshold;

    @BeforeAll static void initialize() {
        KgGovernanceExecutionTest.setup();
    }

    @BeforeEach void prepare() {
        fixture.reset();
        originalThreshold = db.must("select threshold_value from kg_badge_def where code='referrer'", p(), "missing definition").get("thresholdValue");
    }

    @AfterEach void restoreDefinition() {
        db.update("update kg_badge_def set threshold_value=:n where code='referrer'", p("n", originalThreshold));
    }

    void recommendationBaseline(int completedPeople) {
        db.update("update kg_app_user set invited_by=1,invited_time=date_sub(now(),interval 30 day) where user_id in (2,3)", p());
        for (long user = 4; user < 4 + completedPeople; user++) {
            db.update("update kg_app_user set invited_by=1,invited_time=date_sub(now(),interval 30 day) where user_id=:u", p("u", user));
            fixture.app(user);
        }
    }

    long recommendationAwards() {
        return db.count("select count(*) from kg_badge_award where app_user_id=1 and badge_code='referrer' and status='active'", p());
    }

    @Test void recommendationCountsOnlySupportedCompletedServiceApplications() {
        recommendationBaseline(9);
        long application = fixture.app(2L);
        long job = db.count("select job_id from kg_application where app_id=:a", p("a", application));
        for (String type : new String[]{"online", "fulltime"}) {
            db.update("update kg_job set job_type=:t where job_id=:j", p("t", type, "j", job));
            run(() -> badges.sync(1L));
            assertEquals(0, recommendationAwards(), type);
        }
        db.update("update kg_job set job_type='parttime' where job_id=:j", p("j", job));
        run(() -> badges.sync(1L));
        assertEquals(1, recommendationAwards());
        db.update("update kg_application set del_flag='2' where app_id=:a", p("a", application));
        run(() -> badges.sync(1L));
        assertEquals(0, recommendationAwards());
    }

    @Test void trainingCannotReplaceTheSpecifiedFirstExperience() {
        recommendationBaseline(9);
        long activity = db.insert("kg_activity", p("source", "yard", "title", "隔离培训", "store_id", 2L,
                "start_time", Governance.now().minusDays(2), "end_time", Governance.now().minusDays(1),
                "member_training", "1", "status", "ended"));
        long signup = db.insert("kg_activity_signup", p("signup_no", UUID.randomUUID().toString().replace("-", ""),
                "activity_id", activity, "app_user_id", 2L, "status", "confirmed", "checkin_time", Governance.now().minusDays(2), "del_flag", "2"));
        run(() -> badges.sync(1L));
        assertEquals(0, recommendationAwards());
        db.update("update kg_activity_signup set del_flag='0',status='cancelled' where signup_id=:s", p("s", signup));
        run(() -> badges.sync(1L));
        assertEquals(0, recommendationAwards());
        db.update("update kg_activity_signup set status='confirmed' where signup_id=:s", p("s", signup));
        db.update("update kg_activity set end_time=date_add(now(),interval 1 day) where activity_id=:a", p("a", activity));
        run(() -> badges.sync(1L));
        assertEquals(0, recommendationAwards());
        db.update("update kg_activity set end_time=date_sub(now(),interval 1 hour) where activity_id=:a", p("a", activity));
        run(() -> badges.sync(1L));
        assertEquals(0, recommendationAwards(), "培训不在首次换工、兼职或住宿体验口径内");
    }

    @Test void recommendationCountsPeopleOnceAndReconcilesRemovedQualifyingRecords() {
        recommendationBaseline(8);
        fixture.app(2L);
        fixture.app(2L);
        run(() -> badges.sync(1L));
        assertEquals(0, recommendationAwards());
        long secondPerson = fixture.app(3L);
        run(() -> badges.sync(1L));
        long award = db.count("select award_id from kg_badge_award where app_user_id=1 and badge_code='referrer'", p());
        run(() -> badges.display(1L, award, p("visible", true, "worn", true)));
        assertEquals(1, recommendationAwards());
        db.update("update kg_application set volunteer_done_time=null where app_id=:a", p("a", secondPerson));
        run(() -> badges.sync(1L));
        assertEquals(0, recommendationAwards());
        assertEquals("0", db.must("select worn from kg_badge_award where award_id=:a", p("a", award), "missing award").get("worn"));
        assertEquals(0, ledger.totals(1L).balance());
    }

    @Test void fixedCompoundRulesRejectTheLegacySingleThreshold() {
        for (String code : KgBadgeRules.FIXED_CODES) {
            assertThrows(ServiceException.class, () -> run(() -> badges.configure(code, p("threshold", 1), "admin:test")));
            Map<String, Object> definition = badges.definitions().stream().filter(row -> code.equals(row.get("code"))).findFirst().get();
            assertEquals(Boolean.TRUE, definition.get("fixedRule"));
            assertNull(definition.get("thresholdValue"));
            assertEquals(KgBadgeRules.ruleText(code), definition.get("ruleText"));
        }
    }

    @Test void unrelatedAccountAndForeignEvidenceCannotMutateAnExistingCase() {
        long caseId = fixture.create();
        long privateFile = fixture.file(4L, false);
        long before = db.count("select count(*) from kg_mediation_log where case_id=:c", p("c", caseId));
        assertThrows(ServiceException.class, () -> service.detail(4L, caseId, false));
        assertThrows(ServiceException.class, () -> fixture.command(4L, caseId, "evidence", p("fileIds", Collections.singletonList(privateFile))));
        assertThrows(ServiceException.class, () -> fixture.command(1L, caseId, "evidence", p("fileIds", Collections.singletonList(privateFile))));
        Map<String, Object> attachment = db.must("select * from kg_mediation_file where file_id=:f", p("f", privateFile), "missing attachment");
        assertNull(attachment.get("caseId"));
        assertFalse(service.fileAccess(1L, attachment, false));
        assertEquals(before, db.count("select count(*) from kg_mediation_log where case_id=:c", p("c", caseId)));
    }

    @Test void expiredConfirmationKeepsAppealOpenAndRemindersRemainIdempotent() {
        long caseId = fixture.create();
        fixture.ready(caseId);
        fixture.decide(caseId);
        db.update("update kg_mediation_case set confirm_deadline=:t where case_id=:c", p("t", LocalDateTime.now().minusDays(1), "c", caseId));
        run(() -> service.deadlines(caseId));
        run(() -> service.deadlines(caseId));
        assertEquals("confirmation", fixture.state(caseId));
        assertEquals(1, db.count("select count(*) from kg_mediation_event where event_key=:k", p("k", caseId + ":confirmation_due")));
        assertThrows(ServiceException.class, () -> fixture.command(4L, caseId, "confirm", p("accept", false, "note", "无关账号不能代替当事人申诉")));
        fixture.command(1L, caseId, "confirm", p("accept", false, "note", "申请总部进一步复核双方材料和实际执行情况"));
        assertEquals("appealed", fixture.state(caseId));
        assertEquals(0, ledger.totals(1L).balance());
    }

    @Test void cityOpinionAndAssessmentLogsHideGeneralEmailAndPersonalLinks() {
        long caseId = fixture.create();
        long round = fixture.jury(caseId);
        String opinion = "现有订单照片支持双方继续协商，我的邮箱 reviewer@example.test，个人页 https://people.example.test/reviewer。";
        run(() -> service.vote(5L, round, p("choice", "both", "reason", opinion)));
        db.update("update kg_mediation_round set deadline=date_sub(now(),interval 1 minute) where round_id=:r", p("r", round));
        run(() -> service.finishRound(round));
        long vote = db.count("select vote_id from kg_mediation_vote where round_id=:r and app_user_id=5", p("r", round));
        String assessment = "已核实评审依据，联系 reviewer@example.test，主页 www.people.example.test/reviewer。";
        run(() -> service.assess(vote, p("valid", true, "quality", true, "note", assessment), "admin:test"));
        String city = JSON.toJSONString(service.detail(3L, caseId, false));
        assertFalse(city.contains("reviewer@example.test"));
        assertFalse(city.contains("people.example.test"));
        assertTrue(city.contains("现有订单照片支持双方继续协商"));
        assertTrue(city.contains("邮箱已隐藏"));
        String headquarters = JSON.toJSONString(service.detail(99L, caseId, true));
        assertTrue(headquarters.contains(opinion));
        assertTrue(headquarters.contains(assessment));
        assertEquals(15, ledger.totals(5L).balance());
    }
}
