package com.ruoyi.kangaroo.ext.werewolf;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppWerewolfController;

class WerewolfExpansionTest {
    final WerewolfEngine e = new WerewolfEngine();
    long now = 100_000;
    void cmd(WerewolfRoom r, long uid, String action, Object... fields) {
        Map<String,Object> b=WerewolfEngine.map(fields);
        b.put("requestId",UUID.randomUUID().toString());b.put("phaseToken",e.phaseToken(r));
        e.command(r,uid,"玩家"+uid,action,b,now);
    }
    WerewolfRoom room(int count) {
        WerewolfRoom r=e.create("123456",1,"房主",now);
        cmd(r,1,"settings","playerCount",count);
        for(int i=2;i<=count;i++){cmd(r,i,"join");cmd(r,i,"seat","seat",i);cmd(r,i,"ready","ready",true);}
        return r;
    }
    WerewolfRoom game(int count) {
        WerewolfRoom r=room(count);cmd(r,1,"start");next(r);
        List<String> roles=WerewolfEngine.rolesFor(count);
        for(int i=0;i<count;i++)r.players.get(i).role=roles.get(i);
        return r;
    }
    void next(WerewolfRoom r){now=r.deadline;e.advance(r,now);}
    WerewolfRoom.Player role(WerewolfRoom r,String role){return r.players.stream().filter(p->role.equals(p.role)).findFirst().get();}
    void nightChoice(WerewolfRoom r,String who,int target){cmd(r,role(r,who).userId,"act","target",target);}
    void voteHunter(WerewolfRoom r) {
        r.phase="vote";r.deadline=now+30_000;r.actions.clear();
        int target=role(r,"hunter").seat;
        for(WerewolfRoom.Player p:r.players) if(p.alive) r.actions.put(p.userId,p.seat==target?0:target);
        next(r);
    }
    @Test void everySizeStartsWithConfiguredPlayersAndPrivateRoles() {
        for(int count=6;count<=12;count++){
            WerewolfRoom r=room(count);cmd(r,1,"start");
            assertEquals(count,r.players.size());
            assertEquals(count,((List<?>)e.view(r,1L,now).get("players")).size());
            assertEquals(count,((Number)e.view(r,1L,now).get("playerCount")).intValue());
            for(Object p:(List<?>)e.view(r,1L,now).get("players"))assertFalse(((Map<?,?>)p).containsKey("role"));
            assertEquals("",((Map<?,?>)e.view(r,1L,now).get("me")).get("role"));
            assertFalse(e.view(r,null,now).containsKey("me"));
            next(r);assertEquals(count>=10?"guard":"wolves",r.phase);
            assertEquals(count==12?4:count>=9?3:2,r.players.stream().filter(p->"wolf".equals(p.role)).count());
            assertEquals(count>=8?1:0,r.players.stream().filter(p->"hunter".equals(p.role)).count());
        }
    }
    @Test void legacyCountSettingsDoNotLimitAttendanceAndOnlyHostChangesMode() {
        WerewolfRoom r=room(8);
        assertThrows(ServiceException.class,()->cmd(r,2,"settings","playMode","face_to_face"));
        cmd(r,1,"settings","playerCount",6,"playMode","face_to_face");
        assertTrue(r.players.stream().allMatch(p->p.ready));
        assertThrows(ServiceException.class,()->cmd(r,1,"settings","playMode","invalid"));
        assertThrows(ServiceException.class,()->cmd(r,2,"seat","seat",13));
        cmd(r,1,"start");assertEquals(8,r.playerCount);
    }
    @Test void guardCanProtectSelfButNotRepeatAndSkippingResetsPrevious() {
        WerewolfRoom r=game(10);WerewolfRoom.Player g=role(r,"guard");
        nightChoice(r,"guard",g.seat);assertEquals(g.seat,r.guarded);next(r);assertEquals(g.seat,g.lastGuard);
        r.phase="guard";r.actions.clear();r.guarded=0;r.day++;
        assertThrows(ServiceException.class,()->nightChoice(r,"guard",g.seat));
        nightChoice(r,"guard",0);next(r);assertEquals(0,g.lastGuard);
    }
    @Test void nightProtectionAndPoisonResolveBeforeDay() {
        for(int mode=0;mode<4;mode++){
            WerewolfRoom r=game(10);int victim=role(r,"villager").seat;
            r.phase="witch";r.actions.clear();r.victim=victim;
            r.guarded=(mode==0||mode==2||mode==3)?victim:0;r.saved=mode==1||mode==2;r.poisoned=mode==3?victim:0;
            next(r);assertEquals(mode==0||mode==1,e.player(r,victim).alive,"protection mode "+mode);
        }
    }
    @Test void hunterGetsShotAfterWolfAttackButNotPoison() {
        for(boolean poison:new boolean[]{false,true}){
            WerewolfRoom r=game(10);WerewolfRoom.Player h=role(r,"hunter");
            r.phase="witch";r.victim=h.seat;r.poisoned=poison?h.seat:0;
            next(r);assertFalse(h.alive);
            assertEquals(!poison,"hunter".equals(r.phase));
            assertEquals(!poison,((Map<?,?>)e.view(r,h.userId,now).get("me")).get("canShoot"));
        }
    }
    @Test void votedHunterShootsBeforeParityAndCannotShootAgain() {
        WerewolfRoom r=game(8);WerewolfRoom.Player h=role(r,"hunter"),wolf=role(r,"wolf");
        // Two wolves, hunter and two villagers: hunter's elimination reaches parity until the shot.
        for(WerewolfRoom.Player p:r.players)p.alive="wolf".equals(p.role)||"hunter".equals(p.role)||"villager".equals(p.role);
        role(r,"villager").alive=false;
        voteHunter(r);assertEquals("hunter",r.phase);assertEquals("",r.winner);
        assertThrows(ServiceException.class,()->cmd(r,wolf.userId,"act","target",0));
        cmd(r,h.userId,"act","target",wolf.seat);
        assertFalse(wolf.alive);assertFalse(h.gun);assertEquals(2,r.day);assertEquals("wolves",r.phase);
        assertThrows(ServiceException.class,()->cmd(r,h.userId,"act","target",0));
    }
    @Test void hunterTimeoutAndCancellableSkipResumeCorrectly() {
        WerewolfRoom r=game(8);voteHunter(r);WerewolfRoom.Player h=role(r,"hunter");
        cmd(r,h.userId,"prepareSkip");assertEquals(now+3000,h.skipAt);cmd(r,h.userId,"cancelSkip");assertEquals(0,h.skipAt);
        cmd(r,h.userId,"prepareSkip");now+=3000;e.advance(r,now);assertFalse(h.gun);assertNotEquals("hunter",r.phase);
        WerewolfRoom other=game(8);voteHunter(other);next(other);assertFalse(role(other,"hunter").gun);assertNotEquals("hunter",other.phase);
    }
    @Test void replayResetsSkillsAndRetainsModeAndCapacity() {
        WerewolfRoom r=game(12);r.playMode="face_to_face";r.phase="finished";r.pendingHunter=8;r.hunterResume="day";
        r.players.forEach(p->{p.gun=false;p.lastGuard=3;});cmd(r,1,"replay");
        assertEquals(12,r.playerCount);assertEquals("face_to_face",r.playMode);assertEquals(0,r.pendingHunter);
        for(WerewolfRoom.Player p:r.players){assertEquals("",p.role);assertTrue(p.gun);assertEquals(0,p.lastGuard);}
    }
    @Test void staleActionsAndDuplicateRequestsCannotChangeNewPhase() {
        WerewolfRoom r=game(10);WerewolfRoom.Player g=role(r,"guard");
        Map<String,Object>b=WerewolfEngine.map("requestId",UUID.randomUUID().toString(),"phaseToken",e.phaseToken(r),"target",g.seat);
        e.command(r,g.userId,"守卫","act",b,now);next(r);
        e.command(r,g.userId,"守卫","act",b,now);assertEquals("wolves",r.phase);assertTrue(r.actions.isEmpty());
        b.put("requestId",UUID.randomUUID().toString());assertThrows(ServiceException.class,()->e.command(r,g.userId,"守卫","act",b,now));
    }
    @Test void oldRoomSnapshotsKeepSixPlayerDefaults() throws Exception {
        WerewolfRoom r=new ObjectMapper().readValue("{\"code\":\"123456\",\"players\":[{\"userId\":1,\"seat\":1}]}",WerewolfRoom.class);
        assertEquals(6,r.playerCount);assertEquals("online",r.playMode);assertTrue(r.players.get(0).gun);
        assertEquals(12,e.view(r,1L,now).get("playerCount"));
    }
    @Test void expandedRoomsRequireCompatibleClientButSixPlayerRoomsRemainReadable() {
        AppWerewolfController c=new AppWerewolfController();HttpServletRequest req=mock(HttpServletRequest.class);
        ReflectionTestUtils.setField(c,"request",req);
        ReflectionTestUtils.invokeMethod(c,"requireCompatible",WerewolfEngine.map("playerCount",6,"playMode","online"));
        assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(c,"requireCompatible",WerewolfEngine.map("playerCount",12)));
        when(req.getParameter("rulesVersion")).thenReturn("2");
        ReflectionTestUtils.invokeMethod(c,"requireCompatible",WerewolfEngine.map("playerCount",12,"playMode","face_to_face"));
    }
    @Test void offlineVoiceNeverRequestsRtcAndHunterHasOnlyOwnTurn() {
        WerewolfRoom r=game(8);voteHunter(r);WerewolfRoom.Player h=role(r,"hunter");
        assertEquals(true,WerewolfRtcService.policy(e.view(r,h.userId,now)).get("canPublish"));
        assertEquals(false,WerewolfRtcService.policy(e.view(r,1L,now)).get("canPublish"));
        r.playMode="face_to_face";
        assertEquals(false,WerewolfRtcService.policy(e.view(r,h.userId,now)).get("canListen"));
        assertEquals(false,WerewolfRtcService.policy(e.view(r,h.userId,now)).get("canPublish"));
    }
    @Test void abandonedGamesOfEverySizeFinishWithoutStalling() {
        for(int count=6;count<=12;count++){
            WerewolfRoom r=game(count);assertTrue(e.advance(r,now+7*24*3600_000L));
            assertEquals("finished",r.phase);assertEquals("draw",r.winner);
        }
    }
}
