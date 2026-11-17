package com.ruoyi.kangaroo.ext.werewolf;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
class WerewolfLiveChoicesTest {
 final WerewolfEngine engine=new WerewolfEngine(); long now=1000000;
 void cmd(WerewolfRoom r,long uid,String action,Object...args){Map<String,Object>b=WerewolfEngine.map(args);b.put("requestId",UUID.randomUUID().toString());b.put("phaseToken",engine.phaseToken(r));engine.command(r,uid,"QA",action,b,now);}
 WerewolfRoom room(int count){WerewolfRoom r=engine.create("123456",1,"host",now);for(int i=2;i<=count;i++)cmd(r,i,"join");return r;}
 @Test void sixToTwelveStartByActualAttendanceWithoutSettings(){
  for(int count=6;count<=12;count++){WerewolfRoom r=room(count);cmd(r,1,"start");assertEquals(count,r.playerCount);assertEquals(count,r.players.size());assertEquals("starting",r.phase);}
  WerewolfRoom r=room(5);assertThrows(ServiceException.class,()->cmd(r,1,"start"));
  WerewolfRoom full=room(12);assertThrows(ServiceException.class,()->cmd(full,13,"join"));
 }
 @Test void highSeatsCompactBeforeRoleAndTurnAssignment(){WerewolfRoom r=room(6);cmd(r,6,"seat","seat",12);cmd(r,1,"start");assertEquals(6,r.playerCount);assertEquals(6,engine.player(r,6).seat);}
 @Test void liveDraftAndConfirmedChoicesAreVisibleOnlyToLivingWolves(){
  WerewolfRoom r=room(6);cmd(r,1,"start");now=r.deadline;engine.advance(r,now);
  for(int i=0;i<6;i++)r.players.get(i).role=i<2?"wolf":"villager";
  cmd(r,1,"aim","target",3);assertTrue(r.actions.isEmpty());
  Map<?,?> me=(Map<?,?>)engine.view(r,2L,now).get("me");Map<?,?> choice=(Map<?,?>)((List<?>)me.get("wolfTargets")).get(0);
  assertEquals(3,choice.get("target"));assertEquals(false,choice.get("submitted"));
  assertFalse(((Map<?,?>)engine.view(r,3L,now).get("me")).containsKey("wolfTargets"));assertFalse(engine.view(r,null,now).containsKey("me"));
  assertThrows(ServiceException.class,()->cmd(r,3,"aim","target",4));assertThrows(ServiceException.class,()->cmd(r,1,"aim","target",2));
  cmd(r,1,"act","target",4);me=(Map<?,?>)engine.view(r,2L,now).get("me");choice=(Map<?,?>)((List<?>)me.get("wolfTargets")).get(0);assertEquals(4,choice.get("target"));assertEquals(true,choice.get("submitted"));
  assertThrows(ServiceException.class,()->cmd(r,1,"aim","target",3));
  engine.player(r,2).alive=false;assertFalse(((Map<?,?>)engine.view(r,2L,now).get("me")).containsKey("wolfTargets"));
  now=r.deadline;engine.advance(r,now);assertTrue(r.wolfAims.isEmpty());assertFalse(((Map<?,?>)engine.view(r,1L,now).get("me")).containsKey("wolfTargets"));
 }
}
