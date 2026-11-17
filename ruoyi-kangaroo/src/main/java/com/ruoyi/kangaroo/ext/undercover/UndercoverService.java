package com.ruoyi.kangaroo.ext.undercover;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.security.AppLoginUser;

@Service
public class UndercoverService
{
    private static final String PREFIX = "kg:undercover:";
    private static final int ACTIVE_TTL = 86400;
    private static final int HISTORY_TTL = 7 * 86400;
    @Autowired private UndercoverWordService words;
    private final UndercoverEngine engine = new UndercoverEngine(() -> words.activePairs());
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

    // One person keeps one unfinished room at a time, and current:{uid} always names it (that is what "mine" reads).
    // Creating or joining claims that slot atomically: the remembered code and that room's snapshot must be exactly
    // what Java checked, otherwise '!' / 0 asks Java to re-check. So two quick taps can never seat one person twice.
    private static final DefaultRedisScript<String> CREATE = new DefaultRedisScript<>(
            "local previous=redis.call('GET',KEYS[1]); if previous then return previous end "
            + "if (redis.call('GET',KEYS[3]) or '')~=ARGV[4] then return '!' end "
            + "if ARGV[4]~='' and (redis.call('GET',KEYS[4]) or '')~=ARGV[5] then return '!' end "
            + "if redis.call('EXISTS',KEYS[2])==1 then return '' end "
            + "redis.call('SET',KEYS[2],ARGV[1],'EX',ARGV[3]); "
            + "redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]); "
            + "redis.call('SET',KEYS[3],ARGV[2],'EX',ARGV[3]); return ARGV[2]", String.class);

    private static final DefaultRedisScript<Long> JOIN = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end "
            + "if (redis.call('GET',KEYS[2]) or '')~=ARGV[4] then return 0 end "
            + "if ARGV[4]~='' and (redis.call('GET',KEYS[3]) or '')~=ARGV[5] then return 0 end "
            + "redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[3]) "
            + "redis.call('SET',KEYS[2],ARGV[6],'EX',ARGV[3]) return 1", Long.class);

    // Publish the clip and its message together, only if its speaking turn is still current.
    private static final DefaultRedisScript<Long> AUDIO = new DefaultRedisScript<>(
            "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end "
            + "redis.call('SET',KEYS[2],ARGV[3],'EX',ARGV[4],'NX') "
            + "redis.call('SET',KEYS[1],ARGV[2],'EX',ARGV[5]); return 1", Long.class);

    public Map<String, Object> create(AppLoginUser user, String requestId)
    {
        validId(requestId);
        long uid = user.getUserId();
        String once = PREFIX + "create:" + uid + ":" + requestId;
        for (int attempt = 0; attempt < 10; attempt++)
        {
            // Read the slot before the receipt: a retry whose first try just landed is answered with that room, not 409.
            String pointer = redis.opsForValue().get(current(uid));
            String snapshot = pointer == null ? null : redis.opsForValue().get(PREFIX + "room:" + pointer);
            String previous = redis.opsForValue().get(once);
            if (previous != null) return get(previous, uid);
            if (release(uid, user.getNickname(), pointer, snapshot, null)) continue;
            alone(uid, pointer, snapshot, null, "开新房间");
            String code = String.valueOf(100000 + random.nextInt(900000));
            UndercoverRoom room = engine.create(code, uid, user.getNickname(), System.currentTimeMillis());
            String saved = redis.execute(CREATE, Arrays.asList(once, key(code), current(uid),
                    pointer == null ? current(uid) : PREFIX + "room:" + pointer),
                    encode(room), code, String.valueOf(ACTIVE_TTL), pointer == null ? "" : pointer, snapshot == null ? "" : snapshot);
            if (saved != null && !saved.isEmpty() && !"!".equals(saved)) return get(saved, uid);
        }
        throw new ServiceException("创建房间繁忙，请重试", 503);
    }

    /** Refuses while the remembered room still exists, is not dissolved and still seats this person (waiting or mid-game). */
    private void alone(long uid, String pointer, String snapshot, String target, String next)
    {
        if (pointer == null || snapshot == null || pointer.equals(target)) return;
        UndercoverRoom room = decode(snapshot);
        if ("closed".equals(room.phase) || room.players.stream().noneMatch(p -> p.userId == uid)) return;
        if ("waiting".equals(room.phase) || "finished".equals(room.phase))
            throw new ServiceException("你已经在房间 " + pointer + " 里了，先回到那个房间退出，才能" + next, 409);
        throw new ServiceException("你在房间 " + pointer + " 的这一局还没打完，打完才能" + next, 409);
    }

    /**
     * Brings the remembered room up to date first (an unattended game may already be over), and a game that is over
     * no longer holds its players: moving on leaves it exactly like 「退出房间」 (host passes on). true = re-read.
     */
    private boolean release(long uid, String name, String pointer, String snapshot, String target)
    {
        if (pointer == null || snapshot == null || pointer.equals(target)) return false;
        UndercoverRoom room = decode(snapshot);
        if ("closed".equals(room.phase) || room.players.stream().noneMatch(p -> p.userId == uid)) return false;
        long now = System.currentTimeMillis();
        if (!engine.advance(room, now))
        {
            if (!"finished".equals(room.phase)) return false;
            engine.command(room, uid, name, "leave", UndercoverEngine.map("requestId", "auto_" + UUID.randomUUID().toString().replace("-", ""),
                    "phaseToken", engine.phaseToken(room)), now);
        }
        commit(snapshot, room);
        return true;
    }

    public Map<String, Object> get(String code, Long uid)
    {
        for (int attempt = 0; attempt < 12; attempt++)
        {
            String original = load(code);
            UndercoverRoom room = decode(original);
            if ("closed".equals(room.phase)) throw new ServiceException("房间已解散，请重新开房", 404);
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (UndercoverRoom.Player p : room.players)
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
            UndercoverRoom room = decode(original);
            long now = System.currentTimeMillis();
            if (engine.advance(room, now))
            {
                // Archive a just-finished game before replay/leave can clear its participants or roles.
                commit(original, room);
                continue;
            }
            long uid = user.getUserId();
            if ("join".equals(action) && room.players.stream().noneMatch(p -> p.userId == uid))
            {
                // A newcomer must not still be seated in another unfinished room; the seat and the slot are taken together.
                String pointer = redis.opsForValue().get(current(uid));
                String snapshot = pointer == null ? null : pointer.equals(code) ? original : redis.opsForValue().get(PREFIX + "room:" + pointer);
                if (release(uid, user.getNickname(), pointer, snapshot, code)) continue;
                alone(uid, pointer, snapshot, code, "加入别的房间");
                engine.command(room, uid, user.getNickname(), action, body, now);
                if (!claim(original, room, uid, pointer, snapshot)) continue;
                return engine.view(room, uid, now);
            }
            engine.command(room, uid, user.getNickname(), action, body, now);
            if (!commit(original, room)) continue;
            if (!"leave".equals(action)) keep(uid, code);
            return engine.view(room, uid, now);
        }
        throw new ServiceException("其他玩家正在操作，请重试", 409);
    }

    private boolean claim(String original, UndercoverRoom room, long uid, String pointer, String snapshot)
    {
        room.version++;
        Long result = redis.execute(JOIN, Arrays.asList(key(room.code), current(uid),
                pointer == null ? current(uid) : PREFIX + "room:" + pointer),
                original, encode(room), String.valueOf(ACTIVE_TTL), pointer == null ? "" : pointer,
                snapshot == null ? "" : snapshot, room.code);
        return Long.valueOf(1).equals(result);
    }

    private boolean commit(String original, UndercoverRoom room)
    {
        room.version++;
        List<String> keys = new ArrayList<>(Collections.singletonList(key(room.code)));
        String archiveId = room.code + ":" + room.gameId;
        if ("finished".equals(room.phase))
        {
            keys.add(PREFIX + "game:" + archiveId);
            for (UndercoverRoom.Player p : room.players) keys.add(PREFIX + "history:" + p.userId);
        }
        Long result = redis.execute(COMMIT, keys, original, encode(room),
                String.valueOf("finished".equals(room.phase) ? HISTORY_TTL : ACTIVE_TTL),
                String.valueOf(HISTORY_TTL), String.valueOf(room.finishedAt), archiveId);
        return Long.valueOf(1).equals(result);
    }

    public Map<String, Object> mine(long uid)
    {
        // create/join keep exactly one unfinished room per person in this slot, so it is "my room", not merely the latest one.
        String code = redis.opsForValue().get(current(uid));
        if (code == null) return UndercoverEngine.map("room", null);
        try
        {
            Map<String, Object> room = get(code, uid);
            return UndercoverEngine.map("room", room.get("me") == null ? null : room);
        }
        catch (ServiceException error)
        {
            if (Integer.valueOf(404).equals(error.getCode())) return UndercoverEngine.map("room", null);
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
            UndercoverRoom room = decode(snapshot);
            if (room.players.stream().anyMatch(p -> p.userId == uid))
                result.add(UndercoverEngine.map("code", room.code, "gameId", room.gameId, "winner", room.winner,
                        "round", room.round, "finishedAt", room.finishedAt, "role", engine.player(room, uid).role));
        }
        return result;
    }

    public Map<String, Object> result(String code, String gameId, long uid)
    {
        validCode(code);
        if (!gameId.matches("[a-f0-9-]{36}")) throw new ServiceException("对局编号无效", 400);
        String value = redis.opsForValue().get(PREFIX + "game:" + code + ":" + gameId);
        if (value == null) throw new ServiceException("对局记录已过期", 404);
        UndercoverRoom room = decode(value);
        engine.player(room, uid);
        return engine.view(room, uid, System.currentTimeMillis());
    }

    public Map<String, Object> adminList(boolean history, String code, String phase, int page, int size)
    {
        if (!code.isEmpty()) validCode(code);
        if (!Arrays.asList("", "waiting", "reveal", "discussion", "vote", "finished", "closed").contains(phase))
            throw new ServiceException("房间状态无效", 400);
        String pattern = PREFIX + (history ? "game:" : "room:") + (code.isEmpty() ? "*" : code + (history ? ":*" : ""));
        Set<String> keys = redis.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> found = new LinkedHashSet<>();
            try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions().match(pattern).count(200).build()))
            {
                while (cursor.hasNext()) found.add(redis.getStringSerializer().deserialize(cursor.next()));
            }
            return found;
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (keys != null) for (String key : keys)
        {
            String value = redis.opsForValue().get(key);
            if (value == null) continue;
            UndercoverRoom room = decode(value);
            if (!history && room.deadline > 0 && room.deadline <= now)
            {
                try { get(room.code, null); }
                catch (ServiceException e) { if (Integer.valueOf(404).equals(e.getCode())) continue; throw e; }
                value = redis.opsForValue().get(key);
                if (value == null) continue;
                room = decode(value);
            }
            if (!phase.isEmpty() && !phase.equals(room.phase)) continue;
            String host = "";
            for (UndercoverRoom.Player person : room.players) if (person.userId == room.hostId) host = person.name;
            rows.add(UndercoverEngine.map("code", room.code, "gameId", room.gameId, "phase", room.phase,
                    "hostId", room.hostId, "hostName", host, "playerCount", room.players.size(), "round", room.round,
                    "category", room.category, "winner", room.winner, "createdAt", room.createdAt,
                    "startedAt", room.startedAt, "finishedAt", room.finishedAt, "closedAt", room.closedAt));
        }
        String sortKey = history ? "finishedAt" : "createdAt";
        rows.sort(Comparator.<Map<String, Object>>comparingLong(row -> ((Number) row.get(sortKey)).longValue()).reversed()
                .thenComparing(row -> row.get("code").toString()).thenComparing(row -> row.get("gameId").toString()));
        int pageSize = Math.max(1, Math.min(50, size));
        int pageNum = Math.max(1, Math.min(100000, page));
        int start = Math.min(rows.size(), (pageNum - 1) * pageSize);
        return UndercoverEngine.map("rows", rows.subList(start, Math.min(rows.size(), start + pageSize)),
                "total", rows.size(), "pageNum", pageNum, "pageSize", pageSize);
    }

    public Map<String, Object> adminDetail(String code, String gameId)
    {
        validCode(code);
        String value;
        if (gameId == null || gameId.isEmpty())
        {
            UndercoverRoom current = decode(load(code));
            if (!"closed".equals(current.phase)) get(code, null);
            value = load(code);
        }
        else
        {
            if (!gameId.matches("[a-f0-9-]{36}")) throw new ServiceException("对局编号无效", 400);
            value = redis.opsForValue().get(PREFIX + "game:" + code + ":" + gameId);
            if (value == null) throw new ServiceException("对局记录已过期", 404);
        }
        UndercoverRoom room = decode(value);
        Long viewer = "finished".equals(room.phase) && !room.players.isEmpty() ? room.players.get(0).userId : null;
        Map<String, Object> result = engine.view(room, viewer, System.currentTimeMillis());
        result.remove("me");
        result.put("messages", room.messages);
        result.put("events", room.events);
        result.put("createdAt", room.createdAt);
        result.put("closeReason", room.closeReason);
        result.put("closedBy", room.closedBy);
        result.put("closedAt", room.closedAt);
        return result;
    }

    public void adminClose(String code, Map<String, Object> body, String actor)
    {
        String reason = UndercoverEngine.string(body, "reason").trim();
        if (reason.isEmpty() || reason.length() > 200) throw new ServiceException("请填写1至200字的解散原因", 400);
        String gameId = UndercoverEngine.string(body, "gameId");
        for (int attempt = 0; attempt < 12; attempt++)
        {
            String original = load(code);
            UndercoverRoom room = decode(original);
            if (!room.gameId.equals(gameId)) throw new ServiceException("房间已开启新一局，请刷新后确认", 409);
            if ("closed".equals(room.phase)) return;
            long now = System.currentTimeMillis();
            if (engine.advance(room, now)) { commit(original, room); continue; }
            room.phase = "closed";
            room.turn++;
            room.deadline = 0;
            room.speaker = 0;
            room.closeReason = reason;
            room.closedBy = actor;
            room.closedAt = now;
            room.events.add("管理员解散房间，原因：" + reason);
            if (commit(original, room)) return;
        }
        throw new ServiceException("房间正在同步，请刷新后重试", 409);
    }

    private String current(long uid) { return PREFIX + "current:" + uid; }

    /** Acting in a room keeps the slot alive, but never points it away from another room that create/join claimed. */
    private void keep(long uid, String code)
    {
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(current(uid), code, ACTIVE_TTL, TimeUnit.SECONDS))) return;
        if (code.equals(redis.opsForValue().get(current(uid)))) redis.expire(current(uid), ACTIVE_TTL, TimeUnit.SECONDS);
    }

    public Map<String, Object> audio(String code, AppLoginUser user, Map<String, Object> body, byte[] bytes, String mime)
    {
        String requestId = UndercoverEngine.string(body, "requestId");
        validId(requestId);
        int duration = UndercoverEngine.integer(body, "duration");
        if (duration < 1 || duration > 60 || bytes.length < 16 || bytes.length > 700_000)
            throw new ServiceException("语音需要1至60秒，文件不能超过700KB", 400);
        String audioId = user.getUserId() + "-" + requestId;
        body.put("audio", audioId);
        for (int attempt = 0; attempt < 12; attempt++)
        {
            String original = load(code);
            UndercoverRoom room = decode(original);
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
                return engine.view(room, user.getUserId(), now);
            }
        }
        throw new ServiceException("房间正在同步，请重试发送", 409);
    }

    public String audioData(String code, String id, long uid)
    {
        UndercoverRoom room = decode(load(code));
        engine.player(room, uid);
        if (room.messages.stream().noneMatch(m -> id.equals(m.audio))) throw new ServiceException("语音不存在", 404);
        String value = redis.opsForValue().get(PREFIX + "audio:" + code + ":" + id);
        if (value == null) throw new ServiceException("语音已过期", 404);
        return value;
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
    private String encode(UndercoverRoom room)
    {
        try { return json.writeValueAsString(room); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode game room", e); }
    }
    private UndercoverRoom decode(String value)
    {
        try { return json.readValue(value, UndercoverRoom.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read game room", e); }
    }
}
