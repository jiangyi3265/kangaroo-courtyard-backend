package com.ruoyi.kangaroo.ext.werewolf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.tencentyun.TLSSigAPIv2;

/** Issues room-scoped, audio-only tickets. TRTC advanced permission control must be enabled. */
@Service
public class WerewolfRtcService
{
    @Value("${KANGAROO_WEREWOLF_RTC_ENABLED:false}") private boolean enabled;
    @Value("${KANGAROO_WEREWOLF_RTC_SDK_APP_ID:0}") private long sdkAppId;
    @Value("${KANGAROO_WEREWOLF_RTC_SECRET:}") private String secret;
    @Value("${KANGAROO_WEREWOLF_RTC_PRIVATE_MAP_ENABLED:false}") private boolean privateMapEnabled;
    @Autowired private WerewolfService games;
    @Autowired private StringRedisTemplate redis;

    @SuppressWarnings("unchecked")
    public Map<String, Object> session(String code, long uid, Map<String, Object> body)
    {
        Map<String, Object> room = games.get(code, uid);
        Map<String, Object> me = (Map<String, Object>) room.get("me");
        if (me == null) throw new ServiceException("请先加入房间", 403);
        if (!Objects.equals(room.get("phaseToken"), body.get("phaseToken")))
            throw new ServiceException("语音阶段已变化，请重新连接", 409);
        Map<String, Object> result = policy(room);
        if ("face_to_face".equals(room.get("playMode"))) {
            result.put("available", false);
            result.put("message", "面对面模式直接当面交流，无需开启麦克风");
            return result;
        }
        if (!enabled || sdkAppId <= 0 || secret == null || secret.trim().isEmpty() || !privateMapEnabled)
        {
            result.put("available", false);
            result.put("message", "当前小程序尚未开通实时语音，可发送文字；面对面游玩可直接交流");
            return result;
        }
        result.put("available", true);
        if (!Boolean.TRUE.equals(result.get("canListen"))) return result;
        String channel = channel(room);
        String userId = "ww_" + uid;
        boolean canPublish = Boolean.TRUE.equals(result.get("canPublish"));
        TLSSigAPIv2 signatures = new TLSSigAPIv2(sdkAppId, secret);
        result.put("sdkAppID", sdkAppId);
        result.put("userID", userId);
        result.put("strRoomID", channel);
        result.put("userSig", signatures.genUserSig(userId, 120));
        result.put("privateMapKey", signatures.genPrivateMapKeyWithStringRoomID(userId, 120, channel, canPublish ? 15 : 11));
        result.put("expiresAt", System.currentTimeMillis() + 120_000);

        String presenceKey = "kg:werewolf:rtc:" + channel + ":";
        // Presence reports are display hints. Publishing permissions come only from the signed policy above.
        if (Boolean.TRUE.equals(body.get("connected")))
            redis.opsForValue().set(presenceKey + uid,
                    canPublish && !Boolean.TRUE.equals(body.get("muted")) ? "mic" : "listening", 25, TimeUnit.SECONDS);
        else redis.delete(presenceKey + uid);
        List<Map<String, Object>> peers = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) room.get("players"))
        {
            String state = redis.opsForValue().get(presenceKey + p.get("userId"));
            if (state != null) peers.add(WerewolfEngine.map("userId", p.get("userId"), "state", state));
        }
        result.put("peers", peers);
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> policy(Map<String, Object> room)
    {
        Map<String, Object> me = (Map<String, Object>) room.get("me");
        if (me == null) throw new ServiceException("请先加入房间", 403);
        String phase = String.valueOf(room.get("phase"));
        if ("face_to_face".equals(room.get("playMode"))) return WerewolfEngine.map("phaseToken", room.get("phaseToken"),
                "canListen", false, "canPublish", false, "channelLabel", "面对面交流");
        boolean free = Arrays.asList("waiting", "finished").contains(phase);
        boolean day = Arrays.asList("discussion", "vote", "hunter").contains(phase);
        boolean wolf = "wolves".equals(phase) && Boolean.TRUE.equals(me.get("alive")) && "wolf".equals(me.get("role"));
        boolean publish = free || wolf || (Arrays.asList("discussion", "hunter").contains(phase) && Boolean.TRUE.equals(me.get("canSpeak")));
        return WerewolfEngine.map("phaseToken", room.get("phaseToken"), "canListen", free || day || wolf,
                "canPublish", publish, "channelLabel", wolf ? "狼人私密语音" : free ? "房间自由交流" : day ? "轮流发言" : "夜间静音");
    }

    @SuppressWarnings("unchecked")
    static String channel(Map<String, Object> room)
    {
        List<Map<String, Object>> players = (List<Map<String, Object>>) room.get("players");
        String members = players.stream().map(p -> String.valueOf(p.get("userId"))).sorted().collect(Collectors.joining(","));
        // Every phase/speaker and membership change gets a different room. Old publish tickets cannot reach the new audience.
        String scope = room.get("code") + ":" + room.get("createdAt") + ":" + room.get("phaseToken") + ":" + members;
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("ww_");
            for (int i = 0; i < 24; i++) name.append(String.format("%02x", digest[i] & 255));
            return name.toString();
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
