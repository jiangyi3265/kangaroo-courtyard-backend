package com.ruoyi.kangaroo.ext.undercover;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.undercover.UndercoverRoom.Message;
import com.ruoyi.kangaroo.ext.undercover.UndercoverRoom.Player;

/** Server-authoritative rules. Even the host only receives their own word before settlement. */
public class UndercoverEngine
{
    private final SecureRandom random = new SecureRandom();
    private final Supplier<List<WordPair>> wordSource;
    private static final String[][] WORDS = {
        {"travel", "民宿", "酒店"}, {"travel", "露营", "野餐"}, {"travel", "高铁", "飞机"},
        {"travel", "沙滩", "沙漠"}, {"travel", "日出", "日落"}, {"travel", "背包", "行李箱"},
        {"travel", "登山", "徒步"}, {"travel", "温泉", "泳池"}, {"travel", "地图", "导航"},
        {"travel", "帐篷", "房车"}, {"travel", "古镇", "古城"}, {"travel", "缆车", "摩天轮"},
        {"travel", "瀑布", "喷泉"}, {"travel", "护照", "签证"}, {"travel", "导游", "领队"},
        {"daily", "豆浆", "牛奶"}, {"daily", "饺子", "馄饨"}, {"daily", "咖啡", "奶茶"},
        {"daily", "火锅", "麻辣烫"}, {"daily", "西瓜", "哈密瓜"}, {"daily", "包子", "馒头"},
        {"daily", "蛋糕", "面包"}, {"daily", "耳机", "音箱"}, {"daily", "雨伞", "雨衣"},
        {"daily", "围巾", "披肩"}, {"daily", "冰箱", "空调"}, {"daily", "钢琴", "电子琴"},
        {"daily", "电影", "电视剧"}, {"daily", "篮球", "足球"}, {"daily", "书店", "图书馆"}
    };

    public static class WordPair
    {
        public final long id;
        public final String category;
        public final String first;
        public final String second;

        public WordPair(long id, String category, String first, String second)
        {
            this.id = id;
            this.category = category;
            this.first = first;
            this.second = second;
        }
    }

    public UndercoverEngine()
    {
        this(() -> {
            List<WordPair> pairs = new ArrayList<>();
            for (int i = 0; i < WORDS.length; i++) pairs.add(new WordPair(i + 1, WORDS[i][0], WORDS[i][1], WORDS[i][2]));
            return pairs;
        });
    }

    public UndercoverEngine(Supplier<List<WordPair>> wordSource) { this.wordSource = wordSource; }

    public UndercoverRoom create(String code, long uid, String name, long now)
    {
        UndercoverRoom room = new UndercoverRoom();
        room.code = code;
        room.hostId = uid;
        room.createdAt = now;
        room.gameId = UUID.randomUUID().toString();
        join(room, uid, name, now);
        return room;
    }

    public Player player(UndercoverRoom room, long uid)
    {
        return room.players.stream().filter(p -> p.userId == uid).findFirst()
                .orElseThrow(() -> new ServiceException("请先加入房间", 403));
    }

    private Player seat(UndercoverRoom room, int number)
    {
        return room.players.stream().filter(p -> p.seat == number).findFirst()
                .orElseThrow(() -> new ServiceException("玩家座位不存在", 409));
    }

    private void require(boolean condition, String message)
    {
        if (!condition) throw new ServiceException(message, 409);
    }

    private void waiting(UndercoverRoom room)
    {
        require("waiting".equals(room.phase), "请等待本局结束后操作");
    }

    private void host(UndercoverRoom room, long uid)
    {
        require(room.hostId == uid, "只有房主可以进行这项操作");
    }

    public String phaseToken(UndercoverRoom room)
    {
        return room.gameId + ":" + room.turn + ":" + room.phase;
    }

    private void resetReady(UndercoverRoom room)
    {
        for (Player p : room.players) p.ready = true;
    }

    private void join(UndercoverRoom room, long uid, String name, long now)
    {
        if (room.players.stream().anyMatch(p -> p.userId == uid)) return;
        waiting(room);
        require(room.players.size() < 10, "房间已满，最多10人");
        Player p = new Player();
        p.userId = uid;
        String nickname = name == null || name.trim().isEmpty() ? "袋鼠朋友" : name.trim();
        p.name = nickname.substring(0, Math.min(12, nickname.length()));
        p.seenAt = now;
        List<Integer> occupied = room.players.stream().map(v -> v.seat).collect(Collectors.toList());
        for (int number = 1; number <= 10; number++)
        {
            if (!occupied.contains(number)) { p.seat = number; break; }
        }
        room.players.add(p);
        resetReady(room);
    }

    public void command(UndercoverRoom room, long uid, String name, String action, Map<String, Object> data, long now)
    {
        require(!"closed".equals(room.phase), "房间已解散，请重新开房");
        String id = string(data, "requestId");
        require(id.matches("[A-Za-z0-9_-]{16,64}"), "操作标识无效，请重试");
        String receipt = uid + ":" + id;
        if (room.receipts.contains(receipt)) return;
        if ("join".equals(action)) join(room, uid, name, now);
        else
        {
            Player p = player(room, uid);
            require(phaseToken(room).equals(string(data, "phaseToken")), "阶段已变化，请刷新后重新操作");
            execute(room, p, action, data, now);
            p.seenAt = now;
        }
        room.receipts.add(receipt);
        if (room.receipts.size() > 512) room.receipts.remove(0);
    }

    private void execute(UndercoverRoom room, Player p, String action, Map<String, Object> data, long now)
    {
        switch (action)
        {
            case "ready":
                waiting(room);
                require(data.get("ready") instanceof Boolean, "准备状态无效");
                p.ready = true; // Legacy clients cannot cancel automatic readiness.
                break;
            case "settings":
                waiting(room);
                host(room, p.userId);
                int seconds = integer(data, "speechSeconds");
                String category = string(data, "category");
                require(Arrays.asList(15, 20, 30, 45, 60, 90).contains(seconds), "请选择有效的描述时长");
                int offline = data.containsKey("offlineSeconds") ? integer(data, "offlineSeconds") : room.offlineSeconds;
                require(Arrays.asList(0, 60, 120, 180).contains(offline), "请选择有效的离线处理时长");
                room.offlineSeconds = offline;
                require(Arrays.asList("travel", "daily", "mixed").contains(category), "请选择有效词库");
                room.speechSeconds = seconds;
                room.category = category;
                resetReady(room);
                break;
            case "leave":
                require("waiting".equals(room.phase) || "finished".equals(room.phase), "对局中可暂时返回大厅，座位会为你保留");
                room.players.remove(p);
                if (room.players.isEmpty()) room.phase = "closed";
                else
                {
                    if (room.hostId == p.userId) room.hostId = room.players.get(0).userId;
                    resetReady(room);
                }
                break;
            case "kick":
                waiting(room);
                host(room, p.userId);
                Player other = seat(room, integer(data, "seat"));
                require(other.userId != p.userId && now - other.seenAt > 60_000, "仅能移出离线超过一分钟的玩家");
                room.players.remove(other);
                resetReady(room);
                break;
            case "start":
                waiting(room);
                host(room, p.userId);
                require(room.players.size() >= 4 && room.players.stream().allMatch(v -> v.ready), "需要4至10人，且全员准备");
                start(room, now);
                break;
            case "audio":
                require(canSpeak(room, p), "请等待自己的描述回合");
                int duration = integer(data, "duration");
                require(duration >= 1 && duration <= 60, "语音需要1至60秒");
                String clip = string(data, "audio");
                require(clip.equals(p.userId + "-" + string(data, "requestId")), "语音标识无效");
                require(room.messages.size() < 500, "本局发言记录已满");
                Message voice = new Message();
                voice.id = clip; voice.audio = clip; voice.userId = p.userId;
                voice.name = p.name; voice.seat = p.seat; voice.duration = duration;
                voice.round = room.round; voice.runoff = room.runoff; voice.at = now;
                room.messages.add(voice);
                break;
            case "describe":
                require(canSpeak(room, p), "请等待自己的描述回合");
                String text = string(data, "text").trim();
                require(!text.isEmpty() && text.length() <= 100, "描述需要1至100个字");
                String normalized = text.replaceAll("[\\s\\p{P}\\p{Z}]", "");
                require(!normalized.contains(p.word), "请描述词语的特点，避免直接说出词语");
                Message message = new Message();
                message.id = p.userId + "-" + string(data, "requestId");
                message.userId = p.userId;
                message.name = p.name;
                message.seat = p.seat;
                message.text = text;
                message.round = room.round;
                message.runoff = room.runoff;
                message.at = now;
                room.messages.add(message);
                nextSpeaker(room, now);
                break;
            case "endSpeech":
                require(canSpeak(room, p), "请等待自己的描述回合");
                room.events.add(p.seat + "号完成口头描述");
                nextSpeaker(room, now);
                break;
            case "vote":
                require("vote".equals(room.phase) && p.alive, "当前无法投票");
                require(!room.votes.containsKey(p.userId), "你已提交投票，请等待揭晓");
                int target = integer(data, "target");
                require(target == 0 || (target != p.seat && seat(room, target).alive
                        && (!room.runoff || room.candidates.contains(target))), "请选择有效的其他玩家");
                room.votes.put(p.userId, target);
                if (room.players.stream().filter(v -> v.alive).allMatch(v -> room.votes.containsKey(v.userId))) resolveVote(room, now);
                break;
            case "replay":
                host(room, p.userId);
                require("finished".equals(room.phase), "请等待本局结束");
                room.gameId = UUID.randomUUID().toString();
                room.winner = "";
                room.civilianWord = "";
                room.undercoverWord = "";
                room.round = 0;
                room.speaker = 0;
                room.startedAt = 0;
                room.finishedAt = 0;
                room.runoff = false;
                room.messages.clear();
                room.events.clear();
                room.candidates.clear();
                room.speechQueue.clear();
                room.votes.clear();
                for (Player person : room.players)
                {
                    person.role = "";
                    person.word = "";
                    person.alive = true;
                }
                resetReady(room);
                phase(room, "waiting", now, 0);
                break;
            default:
                throw new ServiceException("不支持这项房间操作", 400);
        }
    }

    private void start(UndercoverRoom room, long now)
    {
        List<WordPair> pairs = wordSource.get().stream()
                .filter(pair -> "mixed".equals(room.category) || pair.category.equals(room.category)).collect(Collectors.toList());
        require(!pairs.isEmpty(), "这个词库暂无启用词条，请房主更换词库或联系管理员");
        long previousId = room.wordPairId > 0 ? room.wordPairId : room.pairIndex + 1;
        if (pairs.size() > 1) pairs.removeIf(pair -> pair.id == previousId);
        WordPair pair = pairs.get(random.nextInt(pairs.size()));
        room.wordPairId = pair.id;
        room.pairIndex = -1;
        boolean reverse = random.nextBoolean();
        room.civilianWord = reverse ? pair.second : pair.first;
        room.undercoverWord = reverse ? pair.first : pair.second;
        List<Player> shuffled = new ArrayList<>(room.players);
        Collections.shuffle(shuffled, random);
        int count = room.players.size() >= 7 ? 2 : 1;
        for (int i = 0; i < shuffled.size(); i++)
        {
            Player p = shuffled.get(i);
            p.role = i < count ? "undercover" : "civilian";
            p.word = i < count ? room.undercoverWord : room.civilianWord;
            p.alive = true;
        }
        room.gameId = UUID.randomUUID().toString();
        room.startedAt = now;
        room.round = 1;
        room.events.add("词语已发放，请查看自己的词语");
        phase(room, "reveal", now, 10);
    }

    private void phase(UndercoverRoom room, String phase, long now, int seconds)
    {
        room.phase = phase;
        room.turn++;
        room.deadline = seconds == 0 ? 0 : now + seconds * 1000L;
    }

    private void beginRound(UndercoverRoom room, long now)
    {
        room.runoff = false;
        room.candidates.clear();
        room.votes.clear();
        room.speechQueue = room.players.stream().filter(p -> p.alive).map(p -> p.seat).collect(Collectors.toList());
        Collections.rotate(room.speechQueue, -random.nextInt(room.speechQueue.size()));
        room.events.add("第" + room.round + "轮，依次描述自己的词语");
        nextSpeaker(room, now);
    }

    private void nextSpeaker(UndercoverRoom room, long now)
    {
        if (room.speechQueue.isEmpty())
        {
            room.speaker = 0;
            room.votes.clear();
            phase(room, "vote", now, room.voteSeconds);
        }
        else
        {
            room.speaker = room.speechQueue.remove(0);
            phase(room, "discussion", now, room.speechSeconds);
        }
    }

    private void resolveVote(UndercoverRoom room, long now)
    {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        List<String> ballots = new ArrayList<>();
        for (Player p : room.players)
        {
            if (!p.alive) continue;
            int target = room.votes.getOrDefault(p.userId, 0);
            if (target > 0) counts.merge(target, 1, Integer::sum);
            ballots.add(p.seat + "号→" + (target == 0 ? "弃票" : target + "号"));
        }
        room.events.add("第" + room.round + "轮" + (room.runoff ? "加赛" : "") + "投票 " + String.join("，", ballots));
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> top = counts.keySet().stream().filter(n -> counts.get(n) == max).sorted().collect(Collectors.toList());
        if (top.size() > 1 && !room.runoff)
        {
            room.runoff = true;
            room.votes.clear(); // The runoff is a new ballot, never inherit submitted flags.
            room.candidates = top;
            room.speechQueue = new ArrayList<>(top);
            room.events.add("最高票平票，平票玩家各补充描述一次，再进行投票");
            nextSpeaker(room, now);
            return;
        }
        if (top.size() == 1)
        {
            seat(room, top.get(0)).alive = false;
            room.events.add(top.get(0) + "号出局，身份在本局结束时揭晓");
        }
        else room.events.add("本轮无人出局，继续下一轮");
        long spies = room.players.stream().filter(p -> p.alive && "undercover".equals(p.role)).count();
        long civilians = room.players.stream().filter(p -> p.alive && "civilian".equals(p.role)).count();
        if (spies == 0) finish(room, "civilian", now);
        else if (spies >= civilians) finish(room, "undercover", now);
        else if (room.round >= 12) finish(room, "draw", now);
        else { room.round++; beginRound(room, now); }
    }

    private void finish(UndercoverRoom room, String winner, long now)
    {
        room.winner = winner;
        room.finishedAt = now;
        room.speaker = 0;
        room.events.add("draw".equals(winner) ? "达到12轮上限，本局平局" : ("civilian".equals(winner) ? "平民阵营获胜" : "卧底阵营获胜"));
        phase(room, "finished", now, 0);
    }

    private boolean expireOffline(UndercoverRoom room, long now)
    {
        if (room.offlineSeconds <= 0 || "finished".equals(room.phase) || "closed".equals(room.phase)) return false;
        List<Player> offline = room.players.stream().filter(p -> now - p.seenAt > room.offlineSeconds * 1000L
                && ("waiting".equals(room.phase) || p.alive)).collect(Collectors.toList());
        if (offline.isEmpty()) return false;
        if ("waiting".equals(room.phase))
        {
            room.players.removeAll(offline);
            if (room.players.isEmpty()) { phase(room, "closed", now, 0); return true; }
            if (room.players.stream().noneMatch(p -> p.userId == room.hostId)) room.hostId = room.players.get(0).userId;
            resetReady(room);
            return true;
        }
        for (Player p : offline)
        {
            p.alive = false;
            room.speechQueue.remove(Integer.valueOf(p.seat));
            room.candidates.remove(Integer.valueOf(p.seat));
            room.votes.remove(p.userId);
            room.votes.replaceAll((uid, target) -> target == p.seat ? 0 : target);
            room.events.add(p.seat + "号离线超时，自动出局");
        }
        long spies = room.players.stream().filter(p -> p.alive && "undercover".equals(p.role)).count();
        long civilians = room.players.stream().filter(p -> p.alive && "civilian".equals(p.role)).count();
        if (spies + civilians == 0) finish(room, "draw", now);
        else if (spies == 0) finish(room, "civilian", now);
        else if (spies >= civilians) finish(room, "undercover", now);
        else if ("discussion".equals(room.phase) && offline.stream().anyMatch(p -> p.seat == room.speaker)) nextSpeaker(room, now);
        else if ("vote".equals(room.phase) && room.players.stream().filter(p -> p.alive).allMatch(p -> room.votes.containsKey(p.userId))) resolveVote(room, now);
        return true;
    }

    public boolean advance(UndercoverRoom room, long now)
    {
        boolean changed = expireOffline(room, now);
        while (room.deadline > 0 && room.deadline <= now)
        {
            changed = true;
            long boundary = room.deadline;
            switch (room.phase)
            {
                case "reveal": beginRound(room, boundary); break;
                case "discussion":
                    room.events.add(room.speaker + "号描述时间结束");
                    nextSpeaker(room, boundary);
                    break;
                case "vote": resolveVote(room, boundary); break;
                default: room.deadline = 0;
            }
        }
        return changed;
    }

    private boolean canSpeak(UndercoverRoom room, Player p)
    {
        return "discussion".equals(room.phase) && p.alive && p.seat == room.speaker;
    }

    public Map<String, Object> view(UndercoverRoom room, Long uid, long now)
    {
        Player me = room.players.stream().filter(p -> uid != null && p.userId == uid).findFirst().orElse(null);
        boolean reveal = "finished".equals(room.phase) && me != null;
        Map<String, Object> result = map("code", room.code, "version", room.version, "hostId", room.hostId,
                "gameId", room.gameId, "phase", room.phase, "phaseToken", phaseToken(room), "round", room.round,
                "speaker", room.speaker, "deadline", room.deadline, "serverTime", now,
                "category", room.category, "speechSeconds", room.speechSeconds, "voteSeconds", room.voteSeconds, "offlineSeconds", room.offlineSeconds,
                "runoff", room.runoff, "candidates", room.candidates, "winner", room.winner,
                "startedAt", room.startedAt, "finishedAt", room.finishedAt);
        List<Map<String, Object>> people = new ArrayList<>();
        for (Player p : room.players)
        {
            Map<String, Object> person = map("userId", p.userId, "name", p.name, "seat", p.seat,
                    "ready", p.ready, "alive", p.alive, "online", now - p.seenAt <= 45_000,
                    "canKick", now - p.seenAt > 60_000);
            if (reveal) { person.put("role", p.role); person.put("word", p.word); }
            people.add(person);
        }
        people.sort(Comparator.comparingInt(p -> (Integer) p.get("seat")));
        result.put("players", people);
        if (me != null)
        {
            result.put("me", map("userId", me.userId, "seat", me.seat, "ready", me.ready, "alive", me.alive,
                    "word", me.word, "submitted", "vote".equals(room.phase) && room.votes.containsKey(me.userId),
                    "canVote", "vote".equals(room.phase) && me.alive && !room.votes.containsKey(me.userId) && room.deadline > now, "canSpeak", canSpeak(room, me)));
            result.put("votesSubmitted", room.votes.size());
            result.put("messages", room.messages);
            result.put("events", room.events);
        }
        if (reveal)
        {
            result.put("civilianWord", room.civilianWord);
            result.put("undercoverWord", room.undercoverWord);
        }
        return result;
    }

    public static Map<String, Object> map(Object... values)
    {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
        return map;
    }

    public static String string(Map<String, Object> data, String key)
    {
        Object value = data == null ? null : data.get(key);
        return value instanceof String ? (String) value : "";
    }

    public static int integer(Map<String, Object> data, String key)
    {
        Object value = data == null ? null : data.get(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue())
            throw new ServiceException("参数格式不正确", 400);
        return ((Number) value).intValue();
    }
}
