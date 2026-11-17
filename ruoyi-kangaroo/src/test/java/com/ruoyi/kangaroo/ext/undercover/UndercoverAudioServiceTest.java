package com.ruoyi.kangaroo.ext.undercover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppUndercoverController;
import com.ruoyi.kangaroo.security.AppLoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

class UndercoverAudioServiceTest {
    final UndercoverService service = new UndercoverService();
    final UndercoverEngine engine = new UndercoverEngine();
    final ObjectMapper json = new ObjectMapper();
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String,String> values = mock(ValueOperations.class);
    final Map<String,String> stored = new HashMap<>();
    UndercoverRoom room;
    AppLoginUser user;
    int commits;
    boolean conflict;
    final String roomKey = "kg:undercover:room:123456";
    final String id = "recorded-request-0001";
    final String audioKey = "kg:undercover:audio:123456:1-" + id;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        user = new AppLoginUser(); user.setUserId(1L); user.setNickname("测试玩家");
        room = engine.create("123456", 1L, "测试玩家", System.currentTimeMillis());
        engine.command(room,2L,"同伴","join",UndercoverEngine.map("requestId","join-request-0002"),System.currentTimeMillis());
        room.phase="discussion";room.speaker=1;room.deadline=System.currentTimeMillis()+60000;
        save();
        ReflectionTestUtils.setField(service,"redis",redis);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> stored.get(i.getArgument(0)));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            if (conflict) return 0L;
            List<String> keys = i.getArgument(1);
            assertEquals(roomKey,keys.get(0)); assertEquals(audioKey,keys.get(1));
            assertEquals(stored.get(roomKey),i.getArgument(2));
            stored.put(keys.get(0),i.getArgument(3));stored.putIfAbsent(keys.get(1),i.getArgument(4));commits++;
            return 1L;
        });
    }
    void save() throws Exception { stored.put(roomKey,json.writeValueAsString(room)); }
    Map<String,Object> body() { return UndercoverEngine.map("requestId",id,"phaseToken",engine.phaseToken(room),"duration",2); }
    Map<String,Object> send(Map<String,Object> body) { return service.audio("123456",user,body,new byte[32],"audio/wav"); }

    @Test @SuppressWarnings("unchecked") void publishesOnceAndRetryAfterTurnChangeKeepsOriginalBytes() throws Exception {
        send(body());String first=stored.get(audioKey);
        room=json.readValue(stored.get(roomKey),UndercoverRoom.class);room.phase="vote";room.deadline=System.currentTimeMillis()+60000;save();
        Map<String,Object> r=service.audio("123456",user,body(),new byte[33],"audio/mpeg");
        assertEquals(1,((List<?>)r.get("messages")).size());assertEquals(1,commits);assertEquals(first,stored.get(audioKey));
        assertEquals(first,service.audioData("123456","1-"+id,2L));
    }
    @Test void stalePhaseAndWrongSpeakerNeverStoreAnAudioBlob() throws Exception {
        Map<String,Object> stale=body();stale.put("phaseToken","old-turn");assertThrows(ServiceException.class,()->send(stale));
        room.phase="discussion";room.speaker=2;room.deadline=System.currentTimeMillis()+60000;save();
        assertThrows(ServiceException.class,()->send(body()));assertFalse(stored.containsKey(audioKey));assertEquals(0,commits);
    }
    @Test void onlyMembersCanSendOrReadAndTheClipMustHaveAPublishedMessage() {
        user.setUserId(99L);assertThrows(ServiceException.class,()->send(body()));
        user.setUserId(1L);send(body());assertThrows(ServiceException.class,()->service.audioData("123456","1-"+id,99L));
        assertThrows(ServiceException.class,()->service.audioData("123456","missing",2L));
    }
    @Test void durationLimitsAndSnapshotConflictsDoNotPublishPartialMessages() {
        for(int duration:new int[]{0,61}) {Map<String,Object>b=body();b.put("duration",duration);assertThrows(ServiceException.class,()->send(b));}
        conflict=true;assertThrows(ServiceException.class,()->send(body()));assertFalse(stored.containsKey(audioKey));assertEquals(0,commits);
    }
}
