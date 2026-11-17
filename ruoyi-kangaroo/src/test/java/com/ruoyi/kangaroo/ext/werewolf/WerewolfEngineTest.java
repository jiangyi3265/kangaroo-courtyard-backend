package com.ruoyi.kangaroo.ext.werewolf;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import com.ruoyi.common.exception.ServiceException;

class WerewolfEngineTest
{
    private final WerewolfEngine engine = new WerewolfEngine();
    private final long now = 100_000;

    private void command(WerewolfRoom room, long uid, String action, Object... fields)
    {
        Map<String, Object> body = WerewolfEngine.map(fields);
        body.put("requestId", UUID.randomUUID().toString());
        body.put("phaseToken", engine.phaseToken(room));
        engine.command(room, uid, "玩家" + uid, action, body, now);
    }

    private WerewolfRoom fullRoom()
    {
        WerewolfRoom room = engine.create("482691", 1, "小鹿", now);
        for (long uid = 2; uid <= 6; uid++)
        {
            command(room, uid, "join");
            command(room, uid, "seat", "seat", (int) uid);
            command(room, uid, "ready", "ready", true);
        }
        return room;
    }

    private WerewolfRoom game()
    {
        WerewolfRoom room = fullRoom();
        command(room, 1, "start");
        engine.advance(room, room.deadline);
        String[] roles = { "seer", "wolf", "witch", "villager", "villager", "wolf" };
        for (int i = 0; i < 6; i++) room.players.get(i).role = roles[i];
        return room;
    }

    @Test void avatarSelectionSupportsLongUserIds()
    {
        long[] ids = {1L, 6L, Integer.MAX_VALUE + 1L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long uid : ids)
        {
            WerewolfRoom room = engine.create("482691", uid, "袋鼠朋友", now);
            assertEquals((int) Math.floorMod(uid, 6L), engine.player(room, uid).avatar);
            assertTrue(engine.player(room, uid).avatar >= 0 && engine.player(room, uid).avatar < 6);
        }
    }

    @Test void avatarBytecodeDoesNotCallTheJava9MathOverload() throws Exception
    {
        // source/target=8 alone still allows a newer build JDK's library methods.
        try (java.io.InputStream bytecode = WerewolfEngine.class.getResourceAsStream("WerewolfEngine.class"))
        {
            new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM7)
            {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions)
                {
                    return new MethodVisitor(Opcodes.ASM7)
                    {
                        @Override public void visitMethodInsn(int opcode, String owner, String method,
                                String methodDescriptor, boolean isInterface)
                        {
                            if ("java/lang/Math".equals(owner) && "floorMod".equals(method))
                                assertNotEquals("(JI)I", methodDescriptor, "Java 8 requires the long/long floorMod overload");
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
    }

    @Test void seatsAreExclusiveAndHostTransfers()
    {
        WerewolfRoom room = fullRoom();
        assertThrows(ServiceException.class, () -> command(room, 3, "seat", "seat", 2));
        assertEquals(3, engine.player(room, 3).seat);
        command(room, 1, "leave");
        assertEquals(2, room.hostId);
        command(room, 2, "seat", "seat", 1);
        assertTrue(engine.player(room, 2).ready);
    }

    @Test void joiningAndStartingRequireCapacitySeatsAndReadiness()
    {
        WerewolfRoom room = fullRoom();
        command(room, 7, "join");
        assertEquals(7, room.players.size());
        assertThrows(ServiceException.class, () -> command(room, 2, "start"));
        command(room, 2, "ready", "ready", false);
        assertTrue(engine.player(room, 2).ready);
        command(room, 2, "ready", "ready", true);
        command(room, 1, "start");
        assertEquals(2, room.players.stream().filter(p -> "wolf".equals(p.role)).count());
        assertEquals(1, room.players.stream().filter(p -> "witch".equals(p.role)).count());
        assertThrows(ServiceException.class, () -> command(room, 1, "seat", "seat", 0));
    }

    @Test void hostCanRemoveOfflineUnseatedPlayersByIdentity()
    {
        WerewolfRoom room = engine.create("482691", 1, "小鹿", now);
        long guest = Integer.MAX_VALUE + 12L;
        command(room, guest, "join");
        assertThrows(ServiceException.class, () -> command(room, 1, "kick", "userId", guest));
        engine.player(room, guest).seenAt = now - 60_001;
        assertThrows(ServiceException.class, () -> command(room, guest, "kick", "userId", 1));
        assertThrows(ServiceException.class, () -> command(room, 1, "kick", "userId", "invalid"));
        assertThrows(ServiceException.class, () -> command(room, 1, "kick", "userId", 1.5));
        engine.player(room, guest).seenAt = now - 60_001;
        command(room, 1, "kick", "userId", guest);
        assertEquals(1, room.players.size());
        assertEquals(1, room.hostId);
    }

    @Test void removingSeatedPlayersPreservesTheExistingSeatCommand()
    {
        WerewolfRoom room = fullRoom();
        engine.player(room, 2).seenAt = now - 60_001;
        command(room, 1, "kick", "seat", 2);
        assertEquals(5, room.players.size());
        assertThrows(ServiceException.class, () -> engine.player(room, 2));
    }

    @Test void requestReceiptsMakeMutationRetriesIdempotent()
    {
        WerewolfRoom room = fullRoom();
        Map<String, Object> body = WerewolfEngine.map("requestId", UUID.randomUUID().toString());
        engine.command(room, 1, "小鹿", "start", body, now);
        String gameId = room.gameId;
        engine.command(room, 1, "小鹿", "start", body, now);
        assertEquals(gameId, room.gameId);
        assertEquals("starting", room.phase);
    }

    @Test void snapshotsDiscloseOnlyViewerRoleAndPermittedPrivateKnowledge()
    {
        WerewolfRoom room = game();
        Map<String, Object> visitor = engine.view(room, null, now);
        assertFalse(visitor.containsKey("me"));
        assertFalse(visitor.containsKey("messages"));
        List<Map<String, Object>> people = (List<Map<String, Object>>) visitor.get("players");
        assertTrue(people.stream().noneMatch(p -> p.containsKey("role") || p.containsKey("inspections")));
        Map<String, Object> own = (Map<String, Object>) engine.view(room, 1L, now).get("me");
        assertEquals("seer", own.get("role"));
        assertFalse(own.containsKey("wolves"));
        assertFalse(own.containsKey("victim"));
        Map<String, Object> wolf = (Map<String, Object>) engine.view(room, 2L, now).get("me");
        assertEquals(Arrays.asList(2, 6), wolf.get("wolves"));
    }

    @Test void fullNightInspectionSaveSpeechVoteAndWin()
    {
        WerewolfRoom room = game();
        command(room, 2, "act", "target", 4);
        command(room, 6, "act", "target", 4);
        assertFalse(engine.advance(room, room.deadline - 1));
        engine.advance(room, room.deadline);
        assertEquals("seer", room.phase);
        assertThrows(ServiceException.class, () -> command(room, 4, "act", "target", 2));
        command(room, 1, "act", "target", 2);
        assertEquals("狼人阵营", engine.player(room, 1).inspections.get(2));
        engine.advance(room, room.deadline);
        Map<String, Object> witch = (Map<String, Object>) engine.view(room, 3L, now).get("me");
        assertEquals(4, witch.get("victim"));
        command(room, 3, "act", "target", 0, "potion", "save");
        engine.advance(room, room.deadline);
        assertEquals("discussion", room.phase);
        assertTrue(engine.player(room, 4).alive);
        for (long uid = 1; uid <= 6; uid++) command(room, uid, "endSpeech");
        assertEquals("vote", room.phase);
        for (long uid : new long[] {1, 3, 4, 5, 6}) command(room, uid, "act", "target", 2);
        command(room, 2, "act", "target", 1);
        assertFalse(engine.player(room, 2).alive);
        assertEquals("wolves", room.phase);
        engine.advance(room, room.deadline);
        engine.advance(room, room.deadline);
        command(room, 3, "act", "target", 6, "potion", "poison");
        engine.advance(room, room.deadline);
        assertEquals("good", room.winner);
        assertEquals("finished", room.phase);
        assertEquals(0, room.deadline);
    }

    @Test void tiedWolfVotesDoNotEliminateAndSelfTargetsAreRejected()
    {
        WerewolfRoom room = game();
        assertThrows(ServiceException.class, () -> command(room, 2, "act", "target", 6));
        command(room, 2, "act", "target", 4);
        command(room, 6, "act", "target", 5);
        engine.advance(room, room.deadline);
        assertEquals(0, room.victim);
        assertThrows(ServiceException.class, () -> command(room, 1, "act", "target", 1));
        assertEquals(0, engine.player(room, 1).inspections.size());
    }

    @Test void duplicateActionAndStaleRoundAreRejected()
    {
        WerewolfRoom room = game();
        String previous = engine.phaseToken(room);
        command(room, 2, "act", "target", 4);
        assertThrows(ServiceException.class, () -> command(room, 2, "act", "target", 5));
        engine.advance(room, room.deadline);
        Map<String, Object> stale = WerewolfEngine.map("requestId", UUID.randomUUID().toString(), "phaseToken", previous, "target", 2);
        assertThrows(ServiceException.class, () -> engine.command(room, 1, "小鹿", "act", stale, now));
    }

    @Test void deadPlayersCannotActAndSpeechBelongsToCurrentSeat()
    {
        WerewolfRoom room = game();
        engine.advance(room, room.deadline);
        engine.advance(room, room.deadline);
        engine.advance(room, room.deadline);
        assertTrue(engine.canSpeak(room, engine.player(room, 1)));
        assertFalse(engine.canSpeak(room, engine.player(room, 2)));
        assertThrows(ServiceException.class, () -> command(room, 2, "message", "text", "越过发言顺序"));
        command(room, 1, "message", "text", "我是预言家");
        assertEquals(1, room.messages.size());
        room.phase = "vote";
        engine.player(room, 4).alive = false;
        assertThrows(ServiceException.class, () -> command(room, 4, "act", "target", 2));
        assertThrows(ServiceException.class, () -> command(room, 1, "act", "target", 4));
    }

    @Test void timersCatchUpAndDoNotRequireHostOnline()
    {
        WerewolfRoom room = game();
        engine.advance(room, now + 86400_000);
        assertEquals("finished", room.phase);
        assertEquals("draw", room.winner);
        assertEquals(12, room.day);
        assertEquals(0, room.deadline);
    }

    @Test void witchCannotUseBothPotionsAndCannotSelfSaveAfterFirstNight()
    {
        WerewolfRoom room = game();
        room.phase = "witch";
        room.victim = 3;
        room.day = 2;
        assertThrows(ServiceException.class, () -> command(room, 3, "act", "target", 0, "potion", "save"));
        assertTrue(engine.player(room, 3).antidote);
        room.day = 1;
        command(room, 3, "act", "target", 0, "potion", "save");
        assertThrows(ServiceException.class, () -> command(room, 3, "act", "target", 2, "potion", "poison"));
        assertTrue(engine.player(room, 3).poison);
    }

    @Test void replayClearsSecretStateAndResetsReadiness()
    {
        WerewolfRoom room = game();
        engine.advance(room, now + 86400_000);
        command(room, 1, "replay");
        assertEquals("waiting", room.phase);
        assertTrue(room.players.stream().allMatch(p -> p.role.isEmpty() && p.inspections.isEmpty()));
        assertEquals(6, room.players.stream().filter(p -> p.ready).count());
    }

    @Test void startCountdownHidesRolesAndHasAnExactBoundary()
    {
        WerewolfRoom room = fullRoom();
        command(room, 1, "start");
        assertEquals("starting", room.phase);
        assertEquals(now + 5000, room.deadline);
        for (long uid = 1; uid <= 6; uid++)
        {
            Map<?, ?> own = (Map<?, ?>) engine.view(room, uid, now).get("me");
            assertEquals("", own.get("role"));
            assertFalse(own.containsKey("wolves"));
            assertFalse(engine.canSpeak(room, engine.player(room, uid)));
        }
        assertFalse(engine.advance(room, now + 4999));
        assertTrue(engine.advance(room, now + 5000));
        assertEquals("wolves", room.phase);
        assertEquals(now + 35_000, room.deadline);
    }

    @Test void settingsValidateAtomicallyAndPreserveReady()
    {
        WerewolfRoom room = fullRoom();
        assertEquals(90, room.speechSeconds);
        assertThrows(ServiceException.class, () -> command(room, 2, "settings", "speechSeconds", 120));
        assertThrows(ServiceException.class, () -> command(room, 1, "settings", "speechSeconds", 120, "nightSeconds", 25));
        assertEquals(90, room.speechSeconds);
        assertEquals(6, room.players.stream().filter(p -> p.ready).count());
        command(room, 1, "settings", "speechSeconds", 120, "nightSeconds", 60, "voteSeconds", 30, "allowExtension", false);
        assertEquals(120, room.speechSeconds);
        assertEquals(60, room.nightSeconds);
        assertEquals(30, room.voteSeconds);
        assertFalse(room.allowExtension);
        assertEquals(6, room.players.stream().filter(p -> p.ready).count());
        command(room, 1, "settings", "speechSeconds", 45);
        assertEquals(60, room.nightSeconds);
    }

    @Test void nightSkipCanBeCancelledAndKeepsTheStageDeadline()
    {
        WerewolfRoom room = game();
        long deadline = room.deadline;
        command(room, 2, "prepareSkip");
        assertEquals(now + 3000, engine.player(room, 2).skipAt);
        assertFalse(engine.advance(room, now + 2999));
        command(room, 2, "cancelSkip");
        assertFalse(engine.advance(room, now + 3000));
        assertFalse(room.actions.containsKey(2L));
        command(room, 2, "prepareSkip");
        assertTrue(engine.advance(room, now + 3000));
        assertEquals(0, room.actions.get(2L));
        assertEquals("wolves", room.phase);
        assertEquals(deadline, room.deadline);
        assertThrows(ServiceException.class, () -> command(room, 2, "prepareSkip"));
        assertThrows(ServiceException.class, () -> command(room, 2, "cancelSkip"));
        assertThrows(ServiceException.class, () -> command(room, 1, "prepareSkip"));
    }

    @Test void speechSkipAdvancesOnceAtItsDeadlineAndRejectsLateCancellation()
    {
        WerewolfRoom room = game();
        room.phase = "discussion";
        room.speaker = 1;
        room.deadline = now + 90_000;
        assertThrows(ServiceException.class, () -> command(room, 2, "prepareSkip"));
        Map<String, Object> body = WerewolfEngine.map("requestId", UUID.randomUUID().toString(), "phaseToken", engine.phaseToken(room));
        engine.command(room, 1, "小鹿", "prepareSkip", body, now);
        engine.command(room, 1, "小鹿", "prepareSkip", body, now + 1000);
        assertEquals(now + 3000, engine.player(room, 1).skipAt);
        engine.advance(room, now + 3000);
        assertEquals(2, room.speaker);
        assertEquals(now + 93_000, room.deadline);
        assertFalse(engine.advance(room, now + 3001));
        assertThrows(ServiceException.class, () -> command(room, 1, "cancelSkip"));
        assertThrows(ServiceException.class, () -> engine.command(room, 1, "小鹿", "cancelSkip",
                WerewolfEngine.map("requestId", UUID.randomUUID().toString(), "phaseToken", body.get("phaseToken")), now + 3001));
    }

    @Test void speechExtensionIsOncePerTurnAndRetriable()
    {
        WerewolfRoom room = game();
        room.phase = "discussion";
        room.speaker = 1;
        room.deadline = now + 90_000;
        Map<String, Object> body = WerewolfEngine.map("requestId", UUID.randomUUID().toString(), "phaseToken", engine.phaseToken(room));
        engine.command(room, 1, "小鹿", "extendSpeech", body, now);
        engine.command(room, 1, "小鹿", "extendSpeech", body, now);
        assertEquals(now + 120_000, room.deadline);
        assertThrows(ServiceException.class, () -> command(room, 1, "extendSpeech"));
        assertThrows(ServiceException.class, () -> command(room, 2, "extendSpeech"));
        engine.advance(room, room.deadline);
        assertFalse(room.speechExtended);
        room.allowExtension = false;
        assertThrows(ServiceException.class, () -> command(room, 2, "extendSpeech"));
    }

    @Test void allLivingVotesFinishEarlyAndPendingSkipsClearOnPhaseChange()
    {
        WerewolfRoom room = game();
        command(room, 2, "prepareSkip");
        engine.advance(room, room.deadline);
        assertEquals(0, engine.player(room, 2).skipAt);
        room.phase = "vote";
        room.actions.clear();
        engine.player(room, 4).alive = false;
        for (long uid : new long[] {1, 2, 3, 5}) command(room, uid, "act", "target", 0);
        assertEquals("vote", room.phase);
        command(room, 6, "act", "target", 0);
        assertEquals("wolves", room.phase);
        assertEquals(2, room.day);
    }

    @Test void oldSnapshotsHaveUsableTimingDefaults() throws Exception
    {
        WerewolfRoom room = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"code\":\"482691\",\"speechSeconds\":45,\"players\":[{\"userId\":1}]}", WerewolfRoom.class);
        assertEquals(45, room.speechSeconds);
        assertEquals(30, room.nightSeconds);
        assertEquals(45, room.voteSeconds);
        assertTrue(room.allowExtension);
        assertEquals(0, room.players.get(0).skipAt);
    }

    @Test void wolfChoicesArePrivateAndAllNightActionsAppearOnlyAtFinish() {
        WerewolfRoom r=game();
        assertEquals("wolves",r.phase);
        command(r,2,"act","target",4);
        Map<String,Object> teammate=(Map<String,Object>)engine.view(r,6L,now).get("me");
        List<Map<String,Object>> targets=(List<Map<String,Object>>)teammate.get("wolfTargets");
        assertEquals(4,targets.stream().filter(x->Integer.valueOf(2).equals(x.get("seat"))).findFirst().get().get("target"));
        assertFalse(((Map)engine.view(r,1L,now).get("me")).containsKey("wolfTargets"));
        assertNull(engine.view(r,99L,now).get("me"));
        command(r,6,"act","target",4);
        engine.advance(r,r.deadline);
        command(r,1,"act","target",2);
        engine.advance(r,r.deadline);
        command(r,3,"act","target",0,"potion","save");
        engine.advance(r,r.deadline);
        String privateLog=r.nightEvents.toString();
        assertTrue(privateLog.contains("2号狼人：刀人选择4号"));
        assertTrue(privateLog.contains("6号狼人：刀人选择4号"));
        assertTrue(privateLog.contains("查验2号（狼人阵营）"));
        assertTrue(privateLog.contains("解药救起4号"));
        assertFalse(engine.view(r,4L,now).get("events").toString().contains("查验"));
        r.phase="finished";
        assertTrue(engine.view(r,4L,now).get("events").toString().contains("查验2号"));
        assertNull(engine.view(r,99L,now).get("events"));
        command(r,1,"replay");
        assertTrue(r.nightEvents.isEmpty());
    }
    @Test void nightTimeoutRecordsNoActionAndNeverDuplicatesOnRefresh() {
        WerewolfRoom r=game();
        engine.advance(r,r.deadline);
        assertTrue(r.nightEvents.toString().contains("超时未操作"));
        int count=r.nightEvents.size();
        engine.advance(r,r.deadline-1);
        assertEquals(count,r.nightEvents.size());
    }
}
