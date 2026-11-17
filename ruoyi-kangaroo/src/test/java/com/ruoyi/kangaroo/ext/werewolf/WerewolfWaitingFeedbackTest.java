package com.ruoyi.kangaroo.ext.werewolf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class WerewolfWaitingFeedbackTest {
 final WerewolfEngine e=new WerewolfEngine();final long now=1000000;
 void cmd(WerewolfRoom r,long u,String a,Object...v){Map<String,Object>b=WerewolfEngine.map(v);b.put("requestId",UUID.randomUUID().toString());b.put("phaseToken",e.phaseToken(r));e.command(r,u,"P"+u,a,b,now);}
 WerewolfRoom full(){WerewolfRoom r=e.create("123456",1,"host",now);for(int i=2;i<=6;i++)cmd(r,i,"join");return r;}
 @Test void hiddenLegacyOccupantReceivesFreeSeatAndRoomCanStart(){
  WerewolfRoom r=full();e.player(r,2).seat=0;e.player(r,2).ready=false;
  assertTrue(e.advance(r,now));assertEquals(6,r.players.stream().map(p->p.seat).distinct().count());assertTrue(r.players.stream().allMatch(p->p.ready&&p.seat>0));cmd(r,1,"start");assertEquals("starting",r.phase);
 }
 @Test void staleOccupantsReleaseCapacityButBriefBackgroundDoesNot(){
  WerewolfRoom r=full();e.player(r,2).seenAt=now-119000;assertFalse(e.advance(r,now));
  e.player(r,2).seenAt=now-121000;assertTrue(e.advance(r,now));cmd(r,7,"join");assertEquals(6,r.players.size());assertTrue(r.players.stream().allMatch(p->p.ready));
 }
 @Test void staleHostTransfersAndActiveGamesRetainPlayers(){
  WerewolfRoom r=full();e.player(r,1).seenAt=now-121000;e.advance(r,now);assertEquals(2,r.hostId);cmd(r,7,"join");cmd(r,2,"start");e.player(r,3).seenAt=now-999999;e.advance(r,now);assertEquals(6,r.players.size());
 }
}
