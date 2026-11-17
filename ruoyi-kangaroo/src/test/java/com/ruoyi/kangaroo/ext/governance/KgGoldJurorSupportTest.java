package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.KgGovernanceExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import com.ruoyi.common.exception.ServiceException;

class KgGoldJurorSupportTest {
    private final KgGovernanceExecutionTest fixture=new KgGovernanceExecutionTest();

    @BeforeAll static void initialize(){setup();}
    @BeforeEach void reset(){fixture.reset();}
    @AfterEach void clearNewTables(){
        field(service,"badges",badges);
        for(String table:Arrays.asList("kg_badge_quarter_review","kg_badge_content_check","kg_badge_violation","kg_badge_integrity"))db.update("delete from "+table,p());
    }
    private long voteId(long round,long uid){return db.count("select vote_id from kg_mediation_vote where round_id=:r and app_user_id=:u",p("r",round,"u",uid));}
    private void close(long round){db.update("update kg_mediation_round set deadline=date_sub(now(),interval 1 minute) where round_id=:r",p("r",round));run(()->service.finishRound(round));}
    private Map<String,Object> decision(){return p("decision","门店应当按照订单安排约定房型","basis","核实订单与照片能够确认双方约定","measures","协商调整住房并登记执行结果","creditImpact","无影响","executeDeadline",Governance.now().plusDays(2),"juryAdopted",true,"juryReason","根据匿名意见和真实证据逐条核对");}
    private String adopted(long vote){Object value=db.must("select city_adopted from kg_mediation_vote where vote_id=:v",p("v",vote),"vote missing").get("cityAdopted");return value==null?null:String.valueOf(value);}

    @Test void legacyCollectiveAcceptanceLeavesEveryPersonalOpinionUnknown(){
        long cid=fixture.create(),round=fixture.jury(cid);
        run(()->service.vote(5L,round,p("choice","both")));
        close(round);fixture.decide(cid);
        assertNull(adopted(voteId(round,5)));
        assertEquals("1",db.must("select jury_adopted from kg_mediation_case where case_id=:c",p("c",cid),"case missing").get("juryAdopted"));
    }

    @Test void decisionRecordsIndividualAcceptanceAndExcludesAbsentVotes(){
        long cid=fixture.create(),round=fixture.jury(cid);
        run(()->service.vote(5L,round,p("choice","both")));
        run(()->service.vote(6L,round,p("choice","user","reason","核实照片支持约定")));
        close(round);
        Map<String,Object> body=decision();body.put("adoptedVoteIds",Collections.singletonList(voteId(round,5)));
        fixture.command(3L,cid,"decision",body);
        assertEquals("1",adopted(voteId(round,5)));assertEquals("0",adopted(voteId(round,6)));assertNull(adopted(voteId(round,7)));
        assertEquals("app:3",db.must("select adopted_by from kg_mediation_vote where vote_id=:v",p("v",voteId(round,5)),"vote missing").get("adoptedBy"));
        assertEquals(5,ledger.totals(5L).balance());
    }

    @Test void adoptionCorrectionsAreOwnedAuditedAndIdempotent(){
        long cid=fixture.create(),round=fixture.jury(cid);
        run(()->service.vote(5L,round,p("choice","both")));close(round);fixture.decide(cid);
        Map<String,Object> body=p("adoptedVoteIds",Collections.singletonList(voteId(round,5)),"note","核实后采纳该条独立判断");
        assertThrows(ServiceException.class,()->fixture.command(1L,cid,"adoption",body));
        assertThrows(ServiceException.class,()->fixture.command(4L,cid,"adoption",body));
        fixture.command(3L,cid,"adoption",body);fixture.command(3L,cid,"adoption",body);
        assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and action='jury_adoption'",p("c",cid)));
        run(()->service.command(99L,cid,"adoption",p("adoptedVoteIds",Collections.emptyList(),"note","总部核查原采纳登记有误"),true,"admin:99"));
        assertEquals("0",adopted(voteId(round,5)));
        assertEquals(2,db.count("select count(*) from kg_mediation_log where case_id=:c and action='jury_adoption'",p("c",cid)));
    }

    @Test void adoptionRejectsAbsentForeignAndMalformedVoteLists(){
        long cid=fixture.create(),round=fixture.jury(cid);
        run(()->service.vote(5L,round,p("choice","both")));close(round);fixture.decide(cid);
        for(Object invalid:Arrays.asList(Collections.singletonList(voteId(round,7)),Collections.singletonList(999999L),"all"))
            assertThrows(ServiceException.class,()->fixture.command(3L,cid,"adoption",p("adoptedVoteIds",invalid,"note","核实意见采纳情况与对应证据")));
        assertNull(adopted(voteId(round,5)));
    }

    private List<Long> submittedHistory(int count){
        db.insert("kg_mediation_juror",p("app_user_id",5L,"kind","user","willing","1"));
        List<Long> votes=new ArrayList<>();
        for(int i=0;i<count;i++){
            long cid=fixture.create();
            long round=db.insert("kg_mediation_round",p("case_id",cid,"anonymous_narrative","隔离测试核验材料","anonymous_claims","协商处理","status","closed","deadline",Governance.now().minusDays(count-i)));
            long vote=db.insert("kg_mediation_vote",p("round_id",round,"app_user_id",5L,"juror_kind","user","choice","both","reason","","voted_at",Governance.now().minusDays(count-i),"valid","1"));
            run(()->ledger.award(5L,5,"有效大众评审","m_jury",vote));votes.add(vote);
        }
        return votes;
    }

    @Test void emptyReasonsAndLegacyInvalidAssessmentsDoNotBecomePerfunctory(){
        List<Long> votes=submittedHistory(3);
        for(long vote:votes)run(()->service.assess(vote,p("valid",false,"note","核实该条存在其他无效原因，未认定敷衍"),"admin:test"));
        assertNull(db.must("select suspended_until from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("suspendedUntil"));
        assertEquals(3,db.count("select count(*) from kg_mediation_vote where app_user_id=5 and assessment_kind='invalid'",p()));
    }

    @Test void threeExplicitPerfunctoryAssessmentsSuspendAndImmediatelyRemoveGold(){
        List<Long> votes=submittedHistory(3);
        db.insert("kg_badge_award",p("app_user_id",5L,"badge_code","juror_gold","source_key","automatic","source_note","历史有效授予","awarded_by","system","worn","1"));
        for(long vote:votes)run(()->service.assess(vote,p("valid",false,"assessmentKind","perfunctory","note","总部核实该票未阅读材料且重复随意投票"),"admin:test"));
        assertNotNull(db.must("select suspended_until from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("suspendedUntil"));
        assertEquals(1,db.count("select count(*) from kg_badge_log where app_user_id=5 and action='juror_perfunctory_suspend'",p()));
        assertEquals(0,db.count("select count(*) from kg_badge_award where app_user_id=5 and (status='active' or worn='1')",p()));
        assertEquals(0,ledger.totals(5L).balance());
    }

    @Test void noShowBreaksPerfunctorySequenceAndCannotBeQualityAssessed(){
        List<Long> votes=submittedHistory(3);
        db.update("update kg_mediation_vote set choice=null,valid='0' where vote_id=:v",p("v",votes.get(1)));
        for(int index:Arrays.asList(0,2)){long vote=votes.get(index);run(()->service.assess(vote,p("valid",false,"assessmentKind","perfunctory","note","总部依据具体材料核实本次存在敷衍"),"admin:test"));}
        assertThrows(ServiceException.class,()->run(()->service.assess(votes.get(1),p("valid",false,"assessmentKind","perfunctory","note","未参加评审不能作为已核实敷衍"),"admin:test")));
        assertNull(db.must("select suspended_until from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("suspendedUntil"));
    }

    @Test void lateAssessmentOfHistoricalThreeStillSuspendsAfterLaterValidVoteAndDoesNotExtendOnRetry(){
        List<Long> votes=submittedHistory(4);
        run(()->service.assess(votes.get(3),p("valid",true,"note","已核实后续这一轮认真阅读材料并正常投票"),"admin:test"));
        for(long vote:votes.subList(0,3))run(()->service.assess(vote,p("valid",false,"assessmentKind","perfunctory","note","事后补充核实本次未阅读材料而敷衍投票"),"admin:test"));
        assertNotNull(db.must("select suspended_until from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("suspendedUntil"));
        assertEquals(1,db.count("select count(*) from kg_badge_log where app_user_id=5 and action='juror_perfunctory_suspend' and related_vote_id=:v",p("v",votes.get(2))));
        db.update("update kg_mediation_juror set suspended_until=date_add(now(),interval 20 day) where app_user_id=5",p());
        Object previous=db.must("select suspended_until from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("suspendedUntil");
        run(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"updatePerfunctory",5L,"admin:test"));
        assertEquals(previous,db.must("select suspended_until from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("suspendedUntil"));
        assertEquals(1,db.count("select count(*) from kg_badge_log where app_user_id=5 and action='juror_perfunctory_suspend'",p()));
    }

    @Test void outOfOrderAssessmentCannotReuseAlreadySanctionedVotes(){
        List<Long> votes=submittedHistory(4);
        for(long vote:votes.subList(1,4))run(()->service.assess(vote,p("valid",false,"assessmentKind","perfunctory","note","总部补核实本次未阅读材料而敷衍投票"),"admin:test"));
        db.update("update kg_mediation_juror set suspended_until=date_add(now(),interval 20 day) where app_user_id=5",p());
        Object previous=db.must("select suspended_until from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("suspendedUntil");
        run(()->service.assess(votes.get(0),p("valid",false,"assessmentKind","perfunctory","note","后续补核实更早这一票同样属于敷衍投票"),"admin:test"));
        assertEquals(previous,db.must("select suspended_until from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("suspendedUntil"));
        assertEquals(1,db.count("select count(*) from kg_badge_log where app_user_id=5 and action='juror_perfunctory_suspend'",p()));
    }

    @Test void sanctionRestoreReversesOnlyItsActualCreditAndRetainsOtherSanctions(){
        submittedHistory(1);
        run(()->service.sanctionJuror(5L,p("kind","leakage","deduction",15,"note","总部核验确认存在向外转发调解资料"),"admin:test"));
        run(()->service.sanctionJuror(5L,p("kind","bias","deduction",10,"note","总部独立核验确认存在利益偏袒情况"),"admin:test"));
        long leakage=db.count("select log_id from kg_badge_log where action='juror_leakage' and app_user_id=5",p());
        run(()->service.restoreJuror(5L,p("logId",leakage,"note","总部复查确认泄露记录归属错误，撤销此项"),"admin:test"));
        run(()->service.restoreJuror(5L,p("logId",leakage,"note","重复请求不应再次返还已经恢复的信用分"),"admin:test"));
        assertEquals(90,db.count("select credit_score from kg_mediation_juror where app_user_id=5",p()));
        assertEquals(90,db.count("select credit_score from kg_badge_integrity where app_user_id=5",p()));
        assertEquals("1",db.must("select disqualified from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("disqualified"));
        assertEquals(1,db.count("select count(*) from kg_badge_log where action='juror_restore' and related_log_id=:l",p("l",leakage)));
    }

    @Test void globalCreditCannotBeBypassedByNewJurorOptIn(){
        db.insert("kg_badge_integrity",p("app_user_id",5L,"credit_score",80));
        for(int i=0;i<3;i++)fixture.app(5L);
        run(()->service.optIn(5L,p("kind","user","willing",true)));
        assertEquals(80,((Number)service.juror(5L).get("creditScore")).intValue());
        assertEquals(false,service.juror(5L).get("eligible"));
        assertEquals(false,service.juror(5L).get("coreMember"));
    }

    @Test void restoringCappedCreditUsesActualDeltaAndCannotUseAnotherJurorsRecord(){
        submittedHistory(1);
        db.update("update kg_mediation_juror set credit_score=5 where app_user_id=5",p());
        db.insert("kg_mediation_juror",p("app_user_id",6L,"kind","user"));
        run(()->service.sanctionJuror(5L,p("kind","bias","deduction",10,"note","已经核实存在偏袒，需要记录真实信用扣分"),"admin:test"));
        long original=db.count("select log_id from kg_badge_log where action='juror_bias' and app_user_id=5",p());
        assertEquals(-5,db.count("select credit_delta from kg_badge_log where log_id=:l",p("l",original)));
        assertThrows(ServiceException.class,()->run(()->service.restoreJuror(6L,p("logId",original,"note","不能将另一人的核验记录用于恢复本账号"),"admin:test")));
        run(()->service.restoreJuror(5L,p("logId",original,"note","复查确认该次处理有误，应恢复实际扣除分数"),"admin:test"));
        assertEquals(5,db.count("select credit_score from kg_mediation_juror where app_user_id=5",p()));
    }

    @Test void historicalSanctionWithoutCreditDeltaRemainsExplicitlyUnknown(){
        submittedHistory(1);
        db.update("update kg_mediation_juror set credit_score=80,disqualified='1',violations=1 where app_user_id=5",p());
        long original=db.insert("kg_badge_log",p("app_user_id",5L,"actor","admin:legacy","action","juror_disqualified","note","历史资格处理记录未保存信用变动金额"));
        run(()->service.restoreJuror(5L,p("logId",original,"note","总部复查撤销原资格处理，历史信用待单独核对"),"admin:test"));
        assertEquals(80,db.count("select credit_score from kg_mediation_juror where app_user_id=5",p()));
        assertNull(db.must("select credit_delta from kg_badge_log where related_log_id=:l",p("l",original),"restore missing").get("creditDelta"));
        assertEquals("0",db.must("select disqualified from kg_mediation_juror where app_user_id=5",p(),"juror missing").get("disqualified"));
    }

    @Test void corePriorityKeepsQuotaAndUsesCurrentEligibility(){
        long cid=fixture.create();fixture.ready(cid);fixture.pool();
        for(long uid=11;uid<=15;uid++){for(int i=0;i<3;i++)fixture.app(uid);final long user=uid;run(()->service.optIn(user,p("kind","user","willing",true)));}
        KgBadgeService eligibility=mock(KgBadgeService.class);when(eligibility.goldJurorEligible(14L)).thenReturn(true);when(eligibility.goldJurorEligible(15L)).thenReturn(true);field(service,"badges",eligibility);
        fixture.command(3L,cid,"jury",p("corePriority",true,"anonymousNarrative",repeat("双方对约定的住宿安排存在分歧，已经核验资料并去除身份信息。",3),"anonymousClaims","核实并协商处理","redactionConfirmed",true,"fileIds",Collections.singletonList(fixture.file(3L,true))));
        long round=db.count("select round_id from kg_mediation_round where case_id=:c",p("c",cid));
        assertEquals(2,db.count("select count(*) from kg_mediation_vote where round_id=:r and app_user_id in (14,15)",p("r",round)));
        assertTrue(db.count("select count(*) from kg_mediation_vote where round_id=:r and juror_kind='user'",p("r",round))>=3);
        assertTrue(db.count("select count(*) from kg_mediation_vote where round_id=:r and juror_kind='merchant'",p("r",round))>=1);
        assertEquals("1",db.must("select core_priority from kg_mediation_round where round_id=:r",p("r",round),"round missing").get("corePriority"));
    }
}
