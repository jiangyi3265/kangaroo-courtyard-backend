package com.ruoyi.kangaroo.ext.werewolf;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.zip.InflaterInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import com.ruoyi.common.exception.ServiceException;

class WerewolfRtcServiceTest
{
    private final WerewolfEngine engine = new WerewolfEngine();

    private WerewolfRoom room()
    {
        WerewolfRoom room = engine.create("123456", 1, "小袋鼠", 1000);
        room.players.get(0).role = "wolf";
        return room;
    }

    @Test void voicePolicyFollowsRoleAndTurn()
    {
        WerewolfRoom room = room();
        assertPolicy(room, true, true);
        room.phase = "starting";
        assertPolicy(room, false, false);
        room.phase = "wolves";
        assertPolicy(room, true, true);
        room.players.get(0).alive = false;
        assertPolicy(room, false, false);
        room.players.get(0).alive = true;
        room.players.get(0).role = "seer";
        assertPolicy(room, false, false);
        room.phase = "seer";
        assertPolicy(room, false, false);
        room.phase = "witch";
        assertPolicy(room, false, false);
        room.phase = "discussion";
        room.speaker = 1;
        assertPolicy(room, true, true);
        room.speaker = 2;
        assertPolicy(room, true, false);
        room.players.get(0).alive = false;
        room.speaker = 1;
        assertPolicy(room, true, false);
        room.phase = "vote";
        assertPolicy(room, true, false);
        room.phase = "finished";
        assertPolicy(room, true, true);
    }

    private void assertPolicy(WerewolfRoom room, boolean listen, boolean publish)
    {
        Map<String, Object> result = WerewolfRtcService.policy(engine.view(room, 1L, 1000));
        assertEquals(listen, result.get("canListen"));
        assertEquals(publish, result.get("canPublish"));
    }

    @Test void channelChangesAcrossTurnsMembershipAndRoomReuse()
    {
        WerewolfRoom room = room();
        String previous = WerewolfRtcService.channel(engine.view(room, 1L, 1000));
        assertTrue(previous.matches("ww_[a-f0-9]{48}"));
        room.phase = "discussion";
        room.speaker = 1;
        String speaking = WerewolfRtcService.channel(engine.view(room, 1L, 1000));
        assertNotEquals(previous, speaking);
        room.speaker = 2;
        assertNotEquals(speaking, WerewolfRtcService.channel(engine.view(room, 1L, 1000)));
        room.phase = "waiting";
        room.speaker = 0;
        assertEquals(previous, WerewolfRtcService.channel(engine.view(room, 1L, 1000)));
        room.createdAt++;
        assertNotEquals(previous, WerewolfRtcService.channel(engine.view(room, 1L, 1000)));
        room.createdAt--;
        WerewolfRoom.Player guest = new WerewolfRoom.Player();
        guest.userId = 2;
        room.players.add(guest);
        assertNotEquals(previous, WerewolfRtcService.channel(engine.view(room, 1L, 1000)));
    }

    @Test void missingConfigurationIsExplicitAndVisitorCannotGetCredentials()
    {
        WerewolfRtcService rtc = new WerewolfRtcService();
        WerewolfService games = mock(WerewolfService.class);
        ReflectionTestUtils.setField(rtc, "games", games);
        WerewolfRoom room = room();
        when(games.get(room.code, 1L)).thenReturn(engine.view(room, 1L, 1000));
        Map<String, Object> result = rtc.session(room.code, 1, WerewolfEngine.map("phaseToken", engine.phaseToken(room)));
        assertEquals(false, result.get("available"));
        assertFalse(result.containsKey("userSig"));
        assertThrows(ServiceException.class, () -> rtc.session(room.code, 1, WerewolfEngine.map("phaseToken", "stale")));
        when(games.get(room.code, 2L)).thenReturn(engine.view(room, 2L, 1000));
        assertThrows(ServiceException.class, () -> rtc.session(room.code, 2, WerewolfEngine.map("phaseToken", engine.phaseToken(room))));
    }

    @Test void configuredTicketsRestrictAudioPublishingAndRoomScope() throws Exception
    {
        WerewolfRtcService rtc = new WerewolfRtcService();
        WerewolfService games = mock(WerewolfService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(rtc, "games", games);
        ReflectionTestUtils.setField(rtc, "redis", redis);
        ReflectionTestUtils.setField(rtc, "enabled", true);
        ReflectionTestUtils.setField(rtc, "privateMapEnabled", true);
        ReflectionTestUtils.setField(rtc, "sdkAppId", 1400000000L);
        ReflectionTestUtils.setField(rtc, "secret", "unit-test-only-signing-key");
        WerewolfRoom room = room();
        when(games.get(room.code, 1L)).thenAnswer(call -> engine.view(room, 1L, 1000));
        Map<String, Object> publisher = rtc.session(room.code, 1, WerewolfEngine.map("phaseToken", engine.phaseToken(room)));
        assertEquals(true, publisher.get("available"));
        assertTicket(publisher, 15);
        room.phase = "discussion";
        room.speaker = 2;
        Map<String, Object> listener = rtc.session(room.code, 1, WerewolfEngine.map("phaseToken", engine.phaseToken(room)));
        assertTicket(listener, 11);
        assertNotEquals(publisher.get("strRoomID"), listener.get("strRoomID"));
        room.phase = "seer";
        Map<String, Object> silent = rtc.session(room.code, 1, WerewolfEngine.map("phaseToken", engine.phaseToken(room)));
        assertFalse(silent.containsKey("userSig"));
        assertFalse(silent.containsKey("peers"));
    }

    private void assertTicket(Map<String, Object> ticket, int permission) throws Exception
    {
        String encoded = ((String) ticket.get("privateMapKey")).replace('*', '+').replace('-', '/').replace('_', '=');
        Map<?, ?> signature = new ObjectMapper().readValue(
                new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded))), Map.class);
        assertEquals("ww_1", signature.get("TLS.identifier"));
        assertEquals(120, signature.get("TLS.expire"));
        ByteBuffer scope = ByteBuffer.wrap(Base64.getDecoder().decode((String) signature.get("TLS.userbuf")));
        assertEquals(1, scope.get());
        int userLength = Short.toUnsignedInt(scope.getShort());
        byte[] user = new byte[userLength];
        scope.get(user);
        assertEquals("ww_1", new String(user, StandardCharsets.UTF_8));
        assertEquals(1400000000, scope.getInt());
        assertEquals(0, scope.getInt());
        assertTrue(Integer.toUnsignedLong(scope.getInt()) > System.currentTimeMillis() / 1000);
        assertEquals(permission, scope.getInt());
        assertEquals(0, scope.getInt());
        byte[] channel = new byte[Short.toUnsignedInt(scope.getShort())];
        scope.get(channel);
        assertEquals(ticket.get("strRoomID"), new String(channel, StandardCharsets.UTF_8));
        assertFalse(scope.hasRemaining());
    }

    @Test void configurationRequiresAdvancedPermissionControl()
    {
        WerewolfRtcService rtc = new WerewolfRtcService();
        WerewolfService games = mock(WerewolfService.class);
        ReflectionTestUtils.setField(rtc, "games", games);
        ReflectionTestUtils.setField(rtc, "enabled", true);
        ReflectionTestUtils.setField(rtc, "sdkAppId", 1400000000L);
        ReflectionTestUtils.setField(rtc, "secret", "unit-test-only-signing-key");
        WerewolfRoom room = room();
        when(games.get(room.code, 1L)).thenReturn(engine.view(room, 1L, 1000));
        Map<String, Object> result = rtc.session(room.code, 1, WerewolfEngine.map("phaseToken", engine.phaseToken(room)));
        assertEquals(false, result.get("available"));
        assertFalse(result.containsKey("privateMapKey"));
    }
}
