package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.KgGovernanceExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Original document rules for optional opinions and anonymous city-lead summaries. */
class KgMediationOpinionRulesTest {
    private final KgGovernanceExecutionTest fixture = new KgGovernanceExecutionTest();

    @BeforeAll static void initialize() { setup(); }
    @BeforeEach void resetFixture() { fixture.reset(); }

    private void finish(long round) {
        db.update("update kg_mediation_round set deadline=date_sub(now(),interval 1 minute) where round_id=:r",p("r",round));
        run(() -> service.finishRound(round));
    }

    private long voteId(long round,long user) {
        return db.count("select vote_id from kg_mediation_vote where round_id=:r and app_user_id=:u",p("r",round,"u",user));
    }

    private JSONObject result(long caseId,long viewer,boolean hq) {
        Map<?,?> round = (Map<?,?>) service.detail(viewer,caseId,hq).get("jury");
        return JSON.parseObject(String.valueOf(round.get("resultJson")));
    }

    @Test void missingBlankAndShortOpinionsRemainValidVotes() {
        long caseId=fixture.create(),round=fixture.jury(caseId);
        run(() -> service.vote(5L,round,p("choice","both")));
        run(() -> service.vote(6L,round,p("choice","user","reason","  ")));
        run(() -> service.vote(7L,round,p("choice","merchant","reason","认可")));
        for(long user=5;user<=7;user++)assertEquals(5,ledger.totals(user).balance());
        assertThrows(ServiceException.class,() -> run(() -> service.vote(5L,round,p("choice","user"))));
        finish(round);
        JSONObject summary=result(caseId,3L,false);
        assertEquals(1,summary.getIntValue("both"));
        assertEquals(1,summary.getIntValue("user"));
        assertEquals(1,summary.getIntValue("merchant"));
        assertEquals(1,summary.getJSONArray("reasons").size());
    }

    @Test void reasonLimitAndChoiceValidationStillApply() {
        long caseId=fixture.create(),round=fixture.jury(caseId);
        assertThrows(ServiceException.class,() -> run(() -> service.vote(5L,round,p("choice","both","reason",repeat("字",1001)))));
        assertThrows(ServiceException.class,() -> run(() -> service.vote(5L,round,p("choice","unknown"))));
        assertEquals(0,ledger.totals(5L).balance());
        assertNull(db.one("select choice from kg_mediation_vote where vote_id=:v",p("v",voteId(round,5))).get("choice"));
    }

    @Test void emptyOpinionCannotReceiveQualityBonusButShortOpinionCanBeAssessedByHeadquarters() {
        long caseId=fixture.create(),round=fixture.jury(caseId);
        run(() -> service.vote(5L,round,p("choice","both")));
        run(() -> service.vote(6L,round,p("choice","user","reason","照片显示床板破损")));
        finish(round);
        long vote=voteId(round,5);
        assertThrows(ServiceException.class,() -> run(() -> service.assess(vote,p("valid",true,"quality",true,"note","没有理由，不能发放优质评审奖励"),"admin:test")));
        assertEquals(0,db.count("select assessed from kg_mediation_vote where vote_id=:v",p("v",vote)));
        assertEquals(5,ledger.totals(5L).balance());
        run(() -> service.assess(voteId(round,6),p("valid",true,"quality",true,"note","总部核实照片确有破损，该理由有助于确定事实"),"admin:test"));
        assertEquals(15,ledger.totals(6L).balance());
        run(() -> service.assess(voteId(round,5),p("valid",true,"quality",false,"note","用户已认真投票，未附加理由，确认有效"),"admin:test"));
        assertEquals(5,ledger.totals(5L).balance());
    }

    @Test void substantiveOpinionCanStillReceiveTenPointQualityBonus() {
        long caseId=fixture.create(),round=fixture.jury(caseId);
        run(() -> service.vote(5L,round,p("choice","user","reason","照片与订单约定相互印证，门店应当改善住宿安排")));
        finish(round);
        run(() -> service.assess(voteId(round,5),p("valid",true,"quality",true,"note","意见引用材料明确，对确认事实有实际参考价值"),"admin:test"));
        assertEquals(15,ledger.totals(5L).balance());
    }

    @Test void legacySummaryShowsOpinionsToCityWithoutJurorIdentities() {
        long caseId=fixture.create(),round=fixture.jury(caseId);
        db.update("update kg_app_user set nickname='评审者甲',real_name='评审姓名甲',phone='13912345678' where user_id=5",p());
        String opinion="我是评审者甲，评审姓名甲，手机号13912345678，参考测试院3的经验，现有照片支持双方均应改善沟通。";
        run(() -> service.vote(5L,round,p("choice","both","reason",opinion)));
        finish(round);
        String assessment="总部核实评审姓名甲的意见引用照片准确，联系电话13912345678，仅供内部核验";
        run(() -> service.assess(voteId(round,5),p("valid",true,"quality",true,"note",assessment),"admin:test"));
        // Older rounds may contain counts without the reasons array.
        db.update("update kg_mediation_round set result_json=:j where round_id=:r",p("r",round,"j","{\"both\":1}"));
        Map<String,Object> city=service.detail(3L,caseId,false);
        String visible=JSON.toJSONString(city);
        assertFalse(visible.contains("评审者甲"));
        assertFalse(visible.contains("评审姓名甲"));
        assertFalse(visible.contains("13912345678"));
        assertFalse(visible.contains("测试院3"));
        JSONObject summary=result(caseId,3L,false);
        assertEquals(1,summary.getIntValue("both"));
        JSONObject reason=summary.getJSONArray("reasons").getJSONObject(0);
        assertEquals(2,reason.size());
        assertEquals("both",reason.getString("choice"));
        assertTrue(reason.getString("reason").contains("现有照片支持双方均应改善沟通"));
        assertTrue(JSON.toJSONString(service.detail(99L,caseId,true)).contains("评审者甲"));
        assertTrue(JSON.toJSONString(service.detail(99L,caseId,true)).contains(assessment));
        assertEquals(assessment,db.must("select note from kg_mediation_log where case_id=:c and action='jury_assessment'",p("c",caseId),"assessment log missing").get("note"));
        assertFalse(result(caseId,1L,false).containsKey("reasons"));
    }
}
