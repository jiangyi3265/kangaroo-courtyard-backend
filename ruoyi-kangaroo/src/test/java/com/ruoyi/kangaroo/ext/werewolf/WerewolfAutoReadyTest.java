package com.ruoyi.kangaroo.ext.werewolf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;
class WerewolfAutoReadyTest {
    WerewolfEngine e=new WerewolfEngine();long now=1000000;
    void cmd(WerewolfRoom r,long uid,String action,Object...args){Map<String,Object>b=WerewolfEngine.map(args);b.put("requestId",UUID.randomUUID().toString());b.put("phaseToken",e.phaseToken(r));e.command(r,uid,"QA",action,b,now);}
    @Test void everyCapacityAutoSeatsDistinctPlayersAndStartsWithoutReadyCommands(){
        for(int count=6;count<=12;count++){
            WerewolfRoom r=e.create("123456",1,"QA",now);cmd(r,1,"settings","playerCount",count);
            for(int uid=2;uid<=count;uid++)cmd(r,uid,"join");
            assertEquals(count,r.players.stream().map(p->p.seat).distinct().count());assertTrue(r.players.stream().allMatch(p->p.ready&&p.seat>0));
            cmd(r,1,"start");assertEquals("starting",r.phase);
        }
    }
    @Test void settingChangesAndLegacyPausePreserveAutomaticReadiness(){
        WerewolfRoom r=e.create("123456",1,"QA",now);cmd(r,2,"join");cmd(r,3,"join");cmd(r,2,"ready","ready",false);
        cmd(r,1,"settings","playerCount",9,"nightSeconds",15);assertTrue(e.player(r,2).ready);assertTrue(e.player(r,3).ready);
        cmd(r,2,"seat","seat",8);assertTrue(e.player(r,2).ready);
        cmd(r,1,"settings","playerCount",6);assertEquals(8,e.player(r,2).seat);
        cmd(r,2,"seat","seat",2);cmd(r,1,"settings","playerCount",6,"nightSeconds",20);assertEquals(20,r.nightSeconds);
    }
    @Test void duplicateJoinDoesNotMovePlayerOrCancelReadiness(){
        WerewolfRoom r=e.create("123456",1,"QA",now);cmd(r,2,"join");cmd(r,2,"ready","ready",false);cmd(r,2,"join");
        assertEquals(2,r.players.size());assertEquals(2,e.player(r,2).seat);assertTrue(e.player(r,2).ready);
    }
    @Test void oneNightHasOrderedStagesAndExactlyOneDawn(){
        for(int count:new int[]{6,12}){
            WerewolfRoom r=e.create("123456",1,"QA",now);cmd(r,1,"settings","playerCount",count,"nightSeconds",15);
            for(int uid=2;uid<=count;uid++)cmd(r,uid,"join");cmd(r,1,"start");e.advance(r,r.deadline);
            String[] phases=count==12?new String[]{"guard","wolves","seer","witch"}:new String[]{"wolves","seer","witch"};
            for(String phase:phases){assertEquals(phase,r.phase);assertEquals(1,r.day);long deadline=r.deadline;assertFalse(e.advance(r,deadline-1));assertTrue(e.advance(r,deadline));assertFalse(e.advance(r,deadline));}
            assertEquals("discussion",r.phase);assertEquals(1,r.day);assertEquals(1,r.events.stream().filter(v->v.contains("天黑请闭眼")).count());assertEquals(1,r.events.stream().filter(v->v.contains("平安夜")).count());
        }
    }
}
