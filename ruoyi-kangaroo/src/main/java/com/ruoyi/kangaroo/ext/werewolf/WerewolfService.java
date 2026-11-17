package com.ruoyi.kangaroo.ext.werewolf;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.security.AppLoginUser;

@Service
public class WerewolfService
{
    private static final String PREFIX = "kg:werewolf:";
    private static final int ACTIVE_TTL = 86400;
    private static final int HISTORY_TTL = 7 * 86400;
    private final WerewolfEngine engine = new WerewolfEngine();
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();
    @Autowired private StringRedisTemplate redis;

    // Compare the complete original snapshot, so simultaneous seats/votes never overwrite each other.
    // Finished-game archive and every participant's history are committed with the room itself.
    private static final DefaultRedisScript<Long> COMMIT = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end "
            + "redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]) "
            + "if #KEYS>1 then "
            + "redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[4],'NX') "
            + "for i=3,#KEYS do redis.call('ZADD',KEYS[i],ARGV[5],ARGV[6]); "
            + "redis.call('ZREMRANGEBYRANK',KEYS[i],0,-31); redis.call('EXPIRE',KEYS[i],ARGV[4]) end end return 1", Long.class);

    private static final DefaultRedisScript<String> CREATE = new DefaultRedisScript<>(
            "local previous=redis.call('GET',KEYS[1]); if previous then return previous end "
            + "if redis.call('EXISTS',KEYS[2])==1 then return '' end "
            + "redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[3]); "
            + "redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]); return ARGV[2]", String.class);

    // Publish the clip and its message together, only if its speaking turn is still current.
    private static final DefaultRedisScript<Long> AUDIO = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end "
            + "redis.call('SET',KEYS[2],ARGV[3],'EX',ARGV[4],'NX') "
            + "redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[5]); return 1", Long.class);

    public Map<String, Object> create(AppLoginUser user, String requestId)
    {
        validId(requestId);
        for (int attempt = 0; attempt < 10; attempt++)
        {
            String code = String.valueOf(100000 + random.nextInt(900000));
            WerewolfRoom room = engine.create(code, user.getUserId(), user.getNickname(), System.currentTimeMillis());
            String saved = redis.execute(CREATE, Arrays.asList(PREFIX + "create:" + user.getUserId() + ":" + requestId, key(code)),
                    encode(room), code, String.valueOf(ACTIVE_TTL));
            if (saved != null && !saved.isEmpty())
            {
                remember(user.getUserId(), saved);
                return get(saved, user.getUserId());
            }
        }
        throw new ServiceException("创建房间繁忙，请重试", 503);
    }

    public Map<String, Object> get(String code, Long uid)
    {
        for (int attempt = 0; attempt < 12; attempt++)
        {
            String original = load(code);
            WerewolfRoom room = decode(original);
            if ("closed".equals(room.phase)) throw new ServiceException("房间已解散，请重新开房", 404);
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (WerewolfRoom.Player p : room.players)
            {
                if (uid != null && p.userId == uid && now - p.seenAt > 15_000)
                {
                    p.seenAt = now;
                    changed = true;
                }
            }
            changed = engine.advance(room, now) || changed;
            if (!changed || commit(original, room)) return engine.view(room, uid, now);
        }
        throw new ServiceException("房间正在同步，请重试", 409);
    }

    public Map<String, Object> command(String code, AppLoginUser user, String action, Map<String, Object> body)
    {
        for (int attempt = 0; attempt < 12; attempt++)
        {
            String original = load(code);
            WerewolfRoom room = decode(original);
            long now = System.currentTimeMillis();
            if (engine.advance(room, now))
            {
                // Archive a just-finished game before replay/leave can clear its participants or roles.
                commit(original, room);
                continue;
            }
            engine.command(room, user.getUserId(), user.getNickname(), action, body, now);
            if (!commit(original, room)) continue;
            if (!"leave".equals(action)) remember(user.getUserId(), code);
            return engine.view(room, user.getUserId(), now);
        }
        throw new ServiceException("其他玩家正在操作，请重试", 409);
    }

    private boolean commit(String original, WerewolfRoom room)
    {
        room.version++;
        List<String> keys = new ArrayList<>(Collections.singletonList(key(room.code)));
        String archiveId = room.code + ":" + room.gameId;
        if ("finished".equals(room.phase))
        {
            keys.add(PREFIX + "game:" + archiveId);
            for (WerewolfRoom.Player p : room.players) keys.add(PREFIX + "history:" + p.userId);
        }
        Long result = redis.execute(COMMIT, keys, original, encode(room),
                String.valueOf("finished".equals(room.phase) ? HISTORY_TTL : ACTIVE_TTL),
                String.valueOf(HISTORY_TTL), String.valueOf(room.finishedAt), archiveId);
        return Long.valueOf(1).equals(result);
    }

    public Map<String, Object> mine(long uid)
    {
        String code = redis.opsForValue().get(PREFIX + "current:" + uid);
        if (code == null) return WerewolfEngine.map("room", null);
        try
        {
            Map<String, Object> room = get(code, uid);
            return WerewolfEngine.map("room", room.get("me") == null ? null : room);
        }
        catch (ServiceException error)
        {
            if (Integer.valueOf(404).equals(error.getCode())) return WerewolfEngine.map("room", null);
            throw error;
        }
    }

    public List<Map<String, Object>> history(long uid)
    {
        Set<String> ids = redis.opsForZSet().reverseRange(PREFIX + "history:" + uid, 0, 29);
        List<Map<String, Object>> result = new ArrayList<>();
        if (ids == null) return result;
        for (String id : ids)
        {
            String snapshot = redis.opsForValue().get(PREFIX + "game:" + id);
            if (snapshot == null) continue;
            WerewolfRoom room = decode(snapshot);
            if (room.players.stream().anyMatch(p -> p.userId == uid))
                result.add(WerewolfEngine.map("code", room.code, "gameId", room.gameId, "winner", room.winner,
                        "day", room.day, "finishedAt", room.finishedAt, "role", engine.player(room, uid).role));
        }
        return result;
    }

    public Map<String, Object> result(String code, String gameId, long uid)
    {
        validCode(code);
        if (!gameId.matches("[a-f0-9-]{36}")) throw new ServiceException("对局编号无效", 400);
        String value = redis.opsForValue().get(PREFIX + "game:" + code + ":" + gameId);
        if (value == null) throw new ServiceException("对局记录已过期", 404);
        WerewolfRoom room = decode(value);
        engine.player(room, uid);
        return engine.view(room, uid, System.currentTimeMillis());
    }

    public Map<String, Object> audio(String code, AppLoginUser user, Map<String, Object> body, byte[] bytes, String mime)
    {
        String requestId = WerewolfEngine.string(body, "requestId");
        validId(requestId);
        int duration = WerewolfEngine.integer(body, "duration");
        if (duration < 1 || duration > 60 || bytes.length < 16 || bytes.length > 700_000)
            throw new ServiceException("语音需要1至60秒，文件不能超过700KB", 400);
        String audioId = user.getUserId() + "-" + requestId;
        body.put("audio", audioId);
        for (int attempt = 0; attempt < 12; attempt++)
        {
            String original = load(code);
            WerewolfRoom room = decode(original);
            long now = System.currentTimeMillis();
            if (engine.advance(room, now)) { commit(original, room); continue; }
            engine.player(room, user.getUserId());
            // A successful upload whose response was lost can be acknowledged after the turn changes.
            if (room.messages.stream().anyMatch(m -> audioId.equals(m.audio))) return engine.view(room, user.getUserId(), now);
            engine.command(room, user.getUserId(), user.getNickname(), "audio", body, now);
            if (room.messages.stream().noneMatch(m -> audioId.equals(m.audio)))
                throw new ServiceException("操作标识已使用，请重新录音", 409);
            room.version++;
            Long saved = redis.execute(AUDIO, Arrays.asList(key(code), PREFIX + "audio:" + code + ":" + audioId),
                    original, encode(room), mime + ";" + Base64.getEncoder().encodeToString(bytes),
                    String.valueOf(ACTIVE_TTL), String.valueOf("finished".equals(room.phase) ? HISTORY_TTL : ACTIVE_TTL));
            if (Long.valueOf(1).equals(saved))
            {
                remember(user.getUserId(), code);
                return engine.view(room, user.getUserId(), now);
            }
        }
        throw new ServiceException("房间正在同步，请重试发送", 409);
    }

    public String audioData(String code, String id, long uid)
    {
        WerewolfRoom room = decode(load(code));
        engine.player(room, uid);
        if (room.messages.stream().noneMatch(m -> id.equals(m.audio))) throw new ServiceException("语音不存在", 404);
        String value = redis.opsForValue().get(PREFIX + "audio:" + code + ":" + id);
        if (value == null) throw new ServiceException("语音已过期", 404);
        return value;
    }

    private void remember(long uid, String code)
    {
        redis.opsForValue().set(PREFIX + "current:" + uid, code, ACTIVE_TTL, TimeUnit.SECONDS);
    }

    private String load(String code)
    {
        String value = redis.opsForValue().get(key(code));
        if (value == null) throw new ServiceException("房间不存在或已过期，请重新开房", 404);
        return value;
    }

    private String key(String code) { validCode(code); return PREFIX + "room:" + code; }
    private void validCode(String code)
    {
        if (code == null || !code.matches("[1-9][0-9]{5}")) throw new ServiceException("请输入6位房间号", 400);
    }
    private void validId(String id)
    {
        if (id == null || !id.matches("[A-Za-z0-9_-]{16,64}")) throw new ServiceException("操作标识无效，请重试", 400);
    }
    private String encode(WerewolfRoom room)
    {
        try { return json.writeValueAsString(room); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode game room", e); }
    }
    private WerewolfRoom decode(String value)
    {
        try { return json.readValue(value, WerewolfRoom.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read game room", e); }
    }
}
