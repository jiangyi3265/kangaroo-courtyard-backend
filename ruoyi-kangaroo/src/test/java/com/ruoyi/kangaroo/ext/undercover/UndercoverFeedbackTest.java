package com.ruoyi.kangaroo.ext.undercover;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class UndercoverFeedbackTest {
 final UndercoverEngine e=new UndercoverEngine(); final long now=1000000;
 void cmd(UndercoverRoom r,long u,String a,Object...v){Map<String,Object>b=UndercoverEngine.map(v);b.put("requestId",UUID.randomUUID().toString());b.put("phaseToken",e.phaseToken(r));e.command(r,u,"P"+u,a,b,now);}
 UndercoverRoom room(int n){UndercoverRoom r=e.create("123456",1,"host",now);for(int i=2;i<=n;i++)cmd(r,i,"join");return r;}
 @Test void joinSettingsKickAndLegacyUnreadyNeverUnreadyOtherPlayers(){
  UndercoverRoom r=room(5);assertEquals(30,r.speechSeconds);assertTrue(r.players.stream().allMatch(p->p.ready));
  cmd(r,1,"settings","speechSeconds",15,"category","daily","offlineSeconds",60);cmd(r,2,"ready","ready",false);
  e.player(r,5).seenAt=now-61000;cmd(r,1,"kick","seat",5);assertTrue(r.players.stream().allMatch(p->p.ready));cmd(r,1,"start");assertEquals("reveal",r.phase);
 }
 @Test void newRunoffBallotDoesNotInheritSubmittedFlags(){
  UndercoverRoom r=room(4);cmd(r,1,"start");e.advance(r,r.deadline);while(r.phase.equals("discussion"))cmd(r,r.players.stream().filter(v->v.seat==r.speaker).findFirst().get().userId,"endSpeech");
  for(int i=1;i<=4;i++)cmd(r,i,"vote","target",i%2==0?1:2);
  assertTrue(r.runoff);assertTrue(r.votes.isEmpty());for(int i=1;i<=4;i++)assertEquals(false,((Map<?,?>)e.view(r,(long)i,now).get("me")).get("submitted"));
  cmd(r,1,"endSpeech");cmd(r,2,"endSpeech");assertEquals("vote",r.phase);assertEquals(true,((Map<?,?>)e.view(r,3L,now).get("me")).get("canVote"));cmd(r,3,"vote","target",2);assertEquals(Integer.valueOf(2),r.votes.get(3L));
 }
 @Test void offlineTimeoutCanBeDisabledAndWaitingHostTransfers(){
  UndercoverRoom r=room(5);e.player(r,1).seenAt=now-61000;assertFalse(e.advance(r,now));r.offlineSeconds=60;assertTrue(e.advance(r,now));assertEquals(2,r.hostId);assertEquals(4,r.players.size());assertTrue(r.players.stream().allMatch(p->p.ready));
 }
 @Test void offlineSpeakerIsEliminatedOnceAndSkippedWithoutLeakingRole(){
  UndercoverRoom r=room(7);cmd(r,1,"start");e.advance(r,r.deadline);r.offlineSeconds=60;
  UndercoverRoom.Player p=r.players.stream().filter(v->v.seat==r.speaker).findFirst().get();p.seenAt=now-61000;int old=p.seat;
  assertTrue(e.advance(r,now));assertFalse(p.alive);assertNotEquals(old,r.speaker);assertFalse(r.speechQueue.contains(old));assertEquals(1,r.events.stream().filter(x->x.contains("离线超时")).count());
  assertFalse(e.advance(r,now));assertFalse(((List<Map<String,Object>>)e.view(r,2L,now).get("players")).get(0).containsKey("role"));
 }
 @Test void allDisconnectedFinishesDrawAndInvalidTimeoutCannotBeSaved(){
  UndercoverRoom r=room(4);assertThrows(RuntimeException.class,()->cmd(r,1,"settings","speechSeconds",15,"category","daily","offlineSeconds",1));
  cmd(r,1,"settings","speechSeconds",20,"category","daily","offlineSeconds",60);cmd(r,1,"start");for(UndercoverRoom.Player p:r.players)p.seenAt=now-61000;e.advance(r,now);assertEquals("finished",r.phase);assertEquals("draw",r.winner);
 }
}
