package com.ruoyi.kangaroo.ext.undercover;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.undercover.UndercoverRoom.Player;

class UndercoverEngineTest
{
    private final UndercoverEngine engine = new UndercoverEngine();
    private final long now = 100_000;

    private void command(UndercoverRoom room, long uid, String action, Object... fields)
    {
        Map<String, Object> body = UndercoverEngine.map(fields);
        body.put("requestId", UUID.randomUUID().toString());
        body.put("phaseToken", engine.phaseToken(room));
        engine.command(room, uid, "朋友" + uid, action, body, now);
    }

    private UndercoverRoom waiting(int count)
    {
        UndercoverRoom room = engine.create("482691", 1, "房主", now);
        for (long uid = 2; uid <= count; uid++) command(room, uid, "join");
        for (long uid = 2; uid <= count; uid++) command(room, uid, "ready", "ready", true);
        return room;
    }

    private UndercoverRoom game(int count)
    {
        UndercoverRoom room = waiting(count);
        command(room, 1, "start");
        engine.advance(room, room.deadline);
        return room;
    }

    private void toVote(UndercoverRoom room)
    {
        while ("discussion".equals(room.phase))
        {
            int speaker = room.speaker;
            long uid = room.players.stream().filter(p -> p.seat == speaker).findFirst().get().userId;
            command(room, uid, "endSpeech");
        }
        assertEquals("vote", room.phase);
    }

    private void eliminate(UndercoverRoom room, Player target)
    {
        toVote(room);
        List<Player> voters = room.players.stream().filter(p -> p.alive).collect(Collectors.toList());
        for (Player voter : voters) command(room, voter.userId, "vote", "target", voter == target ? 0 : target.seat);
    }

    @Test void supportsEveryPlayerCountAndEnforcesCapacity()
    {
        for (int count = 4; count <= 10; count++)
        {
            UndercoverRoom room = game(count);
            assertEquals(count >= 7 ? 2 : 1, room.players.stream().filter(p -> p.role.equals("undercover")).count());
            assertEquals(count, room.players.stream().map(p -> p.seat).distinct().count());
            assertTrue(room.players.stream().allMatch(p -> !p.word.isEmpty()));
        }
        UndercoverRoom shortRoom = waiting(3);
        assertThrows(ServiceException.class, () -> command(shortRoom, 1, "start"));
        UndercoverRoom full = waiting(10);
        assertThrows(ServiceException.class, () -> command(full, 11, "join"));
    }

    @Test void hidesRolesAndOtherWordsFromHostPlayersAndOutsiders() throws Exception
    {
        UndercoverRoom room = game(7);
        ObjectMapper json = new ObjectMapper();
        for (Player p : room.players)
        {
            Map<String, Object> view = engine.view(room, p.userId, now);
            String text = json.writeValueAsString(view);
            String other = p.role.equals("civilian") ? room.undercoverWord : room.civilianWord;
            assertFalse(text.contains(other));
            assertFalse(text.contains("\"role\""));
            assertFalse(text.contains("pairIndex"));
            assertFalse(text.contains("receipts"));
            assertEquals(p.word, ((Map<?, ?>) view.get("me")).get("word"));
        }
        String guest = json.writeValueAsString(engine.view(room, null, now));
        assertFalse(guest.contains(room.civilianWord));
        assertFalse(guest.contains(room.undercoverWord));
        assertFalse(engine.view(room, 99L, now).containsKey("messages"));
    }

    @Test void settingsAndMembershipPreserveAutomaticReadinessAndHostTransfers()
    {
        UndercoverRoom room = waiting(4);
        command(room, 1, "settings", "speechSeconds", 30, "category", "daily");
        assertEquals(4, room.players.stream().filter(p -> p.ready).count());
        assertThrows(ServiceException.class, () -> command(room, 2, "settings", "speechSeconds", 30, "category", "daily"));
        command(room, 1, "leave");
        assertEquals(2, room.hostId);
        assertTrue(engine.player(room, 2).ready);
        command(room, 5, "join");
        assertEquals(1, engine.player(room, 5).seat);
    }

    @Test void descriptionsAreTurnBoundAndAdvanceExactlyOnce()
    {
        UndercoverRoom room = game(4);
        Player speaker = room.players.stream().filter(p -> p.seat == room.speaker).findFirst().get();
        Player other = room.players.stream().filter(p -> p != speaker).findFirst().get();
        assertThrows(ServiceException.class, () -> command(room, other.userId, "describe", "text", "我想说一句"));
        assertThrows(ServiceException.class, () -> command(room, speaker.userId, "describe", "text", speaker.word));
        Map<String, Object> data = UndercoverEngine.map("requestId", UUID.randomUUID().toString(),
                "phaseToken", engine.phaseToken(room), "text", "旅行时我想到的一种事物");
        engine.command(room, speaker.userId, speaker.name, "describe", data, now);
        String next = engine.phaseToken(room);
        engine.command(room, speaker.userId, speaker.name, "describe", data, now);
        assertEquals(1, room.messages.size());
        assertEquals(next, engine.phaseToken(room));
        data.put("requestId", UUID.randomUUID().toString());
        assertThrows(ServiceException.class, () -> engine.command(room, speaker.userId, speaker.name, "describe", data, now));
    }

    @Test void votesArePrivateAndCannotBeChangedOrCastForSelf() throws Exception
    {
        UndercoverRoom room = game(4);
        toVote(room);
        assertThrows(ServiceException.class, () -> command(room, 1, "vote", "target", 1));
        command(room, 1, "vote", "target", 2);
        Map<String, Object> view = engine.view(room, 2L, now);
        assertEquals(1, view.get("votesSubmitted"));
        assertFalse(view.containsKey("votes"));
        assertEquals(1, room.votes.size());
        assertThrows(ServiceException.class, () -> command(room, 1, "vote", "target", 3));
        assertThrows(ServiceException.class, () -> command(room, 2, "vote", "target", 99));
        assertThrows(ServiceException.class, () -> command(room, 2, "vote", "target", 1.5));
        assertThrows(ServiceException.class, () -> command(room, 2, "join" + "invalid"));
    }

    @Test void tiedVoteRunsOneRestrictedRunoffThenMovesOn()
    {
        UndercoverRoom room = game(4);
        toVote(room);
        command(room, 1, "vote", "target", 2);
        command(room, 2, "vote", "target", 1);
        command(room, 3, "vote", "target", 1);
        command(room, 4, "vote", "target", 2);
        assertTrue(room.runoff);
        assertEquals(java.util.Arrays.asList(1, 2), room.candidates);
        assertEquals(1, room.speaker);
        command(room, 1, "endSpeech");
        assertEquals(2, room.speaker);
        command(room, 2, "endSpeech");
        assertEquals("vote", room.phase);
        assertThrows(ServiceException.class, () -> command(room, 1, "vote", "target", 3));
        command(room, 1, "vote", "target", 2);
        command(room, 2, "vote", "target", 1);
        command(room, 3, "vote", "target", 1);
        command(room, 4, "vote", "target", 2);
        assertEquals(2, room.round);
        assertFalse(room.runoff);
        assertEquals(4, room.players.stream().filter(p -> p.alive).count());
    }

    @Test void civiliansWinWhenAllSpiesAreEliminatedAndReplayErasesSecrets()
    {
        UndercoverRoom room = game(7);
        List<Player> spies = room.players.stream().filter(p -> p.role.equals("undercover")).collect(Collectors.toList());
        eliminate(room, spies.get(0));
        assertEquals("discussion", room.phase);
        assertFalse(engine.view(room, spies.get(0).userId, now).containsKey("civilianWord"));
        toVote(room);
        assertThrows(ServiceException.class, () -> command(room, spies.get(0).userId, "vote", "target", 0));
        eliminate(room, spies.get(1));
        assertEquals("civilian", room.winner);
        assertTrue(engine.view(room, 1L, now).containsKey("civilianWord"));
        assertFalse(engine.view(room, 99L, now).containsKey("civilianWord"));
        long oldPair = room.wordPairId;
        String oldGame = room.gameId;
        command(room, 1, "replay");
        assertEquals("waiting", room.phase);
        assertNotEquals(oldGame, room.gameId);
        assertTrue(room.players.stream().allMatch(p -> p.word.isEmpty() && p.role.isEmpty() && p.alive));
        assertTrue(room.events.isEmpty());
        for (long uid = 2; uid <= 7; uid++) command(room, uid, "ready", "ready", true);
        command(room, 1, "start");
        assertNotEquals(oldPair, room.wordPairId);
    }

    @Test void undercoverWinsAtParity()
    {
        UndercoverRoom room = game(4);
        List<Player> civilians = room.players.stream().filter(p -> p.role.equals("civilian")).collect(Collectors.toList());
        eliminate(room, civilians.get(0));
        eliminate(room, civilians.get(1));
        assertEquals("finished", room.phase);
        assertEquals("undercover", room.winner);
    }

    @Test void abandonedGamesCatchUpToBoundedDraw()
    {
        UndercoverRoom room = game(10);
        assertTrue(engine.advance(room, now + 86_400_000));
        assertEquals("finished", room.phase);
        assertEquals("draw", room.winner);
        assertEquals(12, room.round);
        assertEquals(0, room.deadline);
        assertFalse(engine.advance(room, now + 86_400_000));
    }

    @Test void kickChecksHostOfflineAgeAndMembership()
    {
        UndercoverRoom room = waiting(4);
        assertThrows(ServiceException.class, () -> command(room, 1, "kick", "seat", 2));
        engine.player(room, 2).seenAt = now - 61_000;
        assertThrows(ServiceException.class, () -> command(room, 3, "kick", "seat", 2));
        command(room, 1, "kick", "seat", 2);
        assertThrows(ServiceException.class, () -> command(room, 2, "ready", "ready", true));
        assertThrows(ServiceException.class, () -> command(room, 1, "kick", "seat", 1));
    }

    @Test void managedWordsAreReadAtStartAndExistingGameKeepsItsWords()
    {
        List<UndercoverEngine.WordPair> words = new java.util.ArrayList<>();
        words.add(new UndercoverEngine.WordPair(90, "travel", "山林", "树林"));
        UndercoverEngine managed = new UndercoverEngine(() -> new java.util.ArrayList<>(words));
        UndercoverRoom room = waiting(4);
        managed.command(room, 1, "房主", "start", UndercoverEngine.map("requestId", UUID.randomUUID().toString(),
                "phaseToken", managed.phaseToken(room)), now);
        assertEquals(90, room.wordPairId);
        String originalWord = room.players.get(0).word;
        words.clear();
        managed.advance(room, room.deadline);
        assertEquals(originalWord, room.players.get(0).word);
        UndercoverRoom another = waiting(4);
        assertThrows(ServiceException.class, () -> managed.command(another, 1, "房主", "start",
                UndercoverEngine.map("requestId", UUID.randomUUID().toString(), "phaseToken", managed.phaseToken(another)), now));
        assertEquals("waiting", another.phase);
        assertTrue(another.players.stream().allMatch(p -> p.word.isEmpty()));
    }

    @Test void legacySnapshotsAvoidPreviousSeedPairAndClosedRoomsRejectWrites() throws Exception
    {
        UndercoverRoom room = waiting(4);
        room.pairIndex = 0;
        UndercoverEngine managed = new UndercoverEngine(() -> java.util.Arrays.asList(
                new UndercoverEngine.WordPair(1, "travel", "民宿", "酒店"),
                new UndercoverEngine.WordPair(2, "travel", "露营", "野餐")));
        managed.command(room, 1, "房主", "start", UndercoverEngine.map("requestId", UUID.randomUUID().toString(),
                "phaseToken", managed.phaseToken(room)), now);
        assertEquals(2, room.wordPairId);
        room.phase = "closed";
        assertThrows(ServiceException.class, () -> command(room, 1, "join"));
        assertThrows(ServiceException.class, () -> command(room, 1, "ready", "ready", true));
    }

    @Test void recordedSpeechIsTurnScopedIdempotentAndDoesNotEndTurn() {
        UndercoverRoom r=game(4);
        long uid=r.players.stream().filter(p->p.seat==r.speaker).findFirst().get().userId;
        long other=r.players.stream().filter(p->p.userId!=uid).findFirst().get().userId;
        String id=UUID.randomUUID().toString();
        Map<String,Object> body=UndercoverEngine.map("requestId",id,"phaseToken",engine.phaseToken(r),"audio",uid+"-"+id,"duration",12);
        int speaker=r.speaker;
        engine.command(r,uid,"测试","audio",body,now);
        engine.command(r,uid,"测试","audio",body,now);
        assertEquals(1,r.messages.size());assertEquals(speaker,r.speaker);
        assertEquals(12,r.messages.get(0).duration);assertEquals(uid+"-"+id,r.messages.get(0).audio);
        assertThrows(ServiceException.class,()->command(r,other,"audio","audio",other+"-wrong","duration",5));
        assertThrows(ServiceException.class,()->command(r,uid,"audio","audio","wrong","duration",5));
        assertThrows(ServiceException.class,()->command(r,uid,"audio","audio","wrong","duration",61));
        command(r,uid,"endSpeech");
        assertNotEquals(speaker,r.speaker);
        assertThrows(ServiceException.class,()->command(r,uid,"audio","audio","wrong","duration",5));
    }
}
