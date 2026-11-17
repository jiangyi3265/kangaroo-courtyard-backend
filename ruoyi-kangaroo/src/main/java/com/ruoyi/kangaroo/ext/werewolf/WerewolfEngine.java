package com.ruoyi.kangaroo.ext.werewolf;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.werewolf.WerewolfRoom.Message;
import com.ruoyi.kangaroo.ext.werewolf.WerewolfRoom.Player;

/** Six to twelve player rules with viewer-specific disclosure. No client clock or role is trusted. */
public class WerewolfEngine
{
    private final SecureRandom random = new SecureRandom();
    private static final List<String> NIGHT = Arrays.asList("guard", "wolves", "seer", "witch");

    public static List<String> rolesFor(int count)
    {
        if (count < 6 || count > 12) throw new ServiceException("房间人数需要6至12人", 400);
        List<String> roles = new ArrayList<>();
        for (int i = 0; i < (count == 12 ? 4 : count >= 9 ? 3 : 2); i++) roles.add("wolf");
        roles.add("seer"); roles.add("witch");
        if (count >= 8) roles.add("hunter");
        if (count >= 10) roles.add("guard");
        while (roles.size() < count) roles.add("villager");
        return roles;
    }

    public WerewolfRoom create(String code, long uid, String name, long now)
    {
        WerewolfRoom room = new WerewolfRoom();
        room.code = code;
        room.hostId = uid;
        room.createdAt = now;
        join(room, uid, name, now);
        player(room, uid).seat = 1;
        player(room, uid).ready = true;
        return room;
    }

    public Player player(WerewolfRoom room, long uid)
    {
        return room.players.stream().filter(p -> p.userId == uid).findFirst()
                .orElseThrow(() -> new ServiceException("请先加入房间", 403));
    }

    private Player seat(WerewolfRoom room, int number)
    {
        return room.players.stream().filter(p -> p.seat == number && number > 0).findFirst()
                .orElseThrow(() -> new ServiceException("这个座位还没有玩家", 409));
    }

    private void require(boolean condition, String message)
    {
        if (!condition) throw new ServiceException(message, 409);
    }

    private void waiting(WerewolfRoom room)
    {
        require("waiting".equals(room.phase), "对局已经开始，当前操作不可用");
    }

    private void host(WerewolfRoom room, long uid)
    {
        require(room.hostId == uid, "只有房主可以进行这项操作");
    }

    public String phaseToken(WerewolfRoom room)
    {
        return room.gameId + ":" + room.day + ":" + room.phase + ":" + room.speaker;
    }

    private void join(WerewolfRoom room, long uid, String name, long now)
    {
        if (room.players.stream().anyMatch(p -> p.userId == uid)) return;
        waiting(room);
        require(room.players.size() < 12, "房间已满，请联系房主或加入其他房间");
        Player p = new Player();
        p.userId = uid;
        p.name = name == null || name.trim().isEmpty() ? "袋鼠朋友" : name.trim().substring(0, Math.min(12, name.trim().length()));
        p.seenAt = now;
        p.avatar = (int) Math.floorMod(uid, 6L);
        for (int number = 1; number <= 12; number++) {
            final int candidate = number;
            if (room.players.stream().noneMatch(other -> other.seat == candidate)) { p.seat = number; break; }
        }
        p.ready = p.seat > 0;
        room.players.add(p);
    }

    public void command(WerewolfRoom room, long uid, String name, String action, Map<String, Object> data, long now)
    {
        String id = string(data, "requestId");
        require(id.matches("[A-Za-z0-9_-]{16,64}"), "操作标识无效，请重试");
        String receipt = uid + ":" + id;
        if (room.receipts.contains(receipt)) return;
        if ("join".equals(action)) join(room, uid, name, now);
        else execute(room, player(room, uid), action, data, now);
        room.receipts.add(receipt);
        if (room.receipts.size() > 256) room.receipts.remove(0);
    }

    private void execute(WerewolfRoom room, Player p, String action, Map<String, Object> data, long now)
    {
        p.seenAt = now;
        switch (action)
        {
            case "seat":
                waiting(room);
                int number = integer(data, "seat");
                require(number >= 0 && number <= 12, "座位编号无效");
                require(number == 0 || room.players.stream().noneMatch(other -> other.seat == number && other.userId != p.userId), "这个座位刚被坐下，请选择其他空位");
                p.seat = number;
                p.ready = number > 0;
                break;
            case "ready":
                waiting(room);
                require(p.seat > 0, "请先点击空椅子入座");
                p.ready = p.seat > 0; // Entering a seat is always ready.
                break;
            case "settings":
                waiting(room);
                host(room, p.userId);
                int seconds = data.containsKey("speechSeconds") ? integer(data, "speechSeconds") : room.speechSeconds;
                int nightSeconds = data.containsKey("nightSeconds") ? integer(data, "nightSeconds") : room.nightSeconds;
                int voteSeconds = data.containsKey("voteSeconds") ? integer(data, "voteSeconds") : room.voteSeconds;
                String playMode = data.containsKey("playMode") ? string(data, "playMode") : room.playMode;
                require(Arrays.asList("online", "face_to_face").contains(playMode), "请选择线上或面对面模式");
                require(Arrays.asList(30, 45, 60, 90, 120).contains(seconds), "请选择支持的发言时长");
                require(Arrays.asList(15, 20, 30, 45, 60).contains(nightSeconds), "请选择支持的夜间时长");
                require(Arrays.asList(30, 45, 60).contains(voteSeconds), "请选择支持的投票时长");
                require(!data.containsKey("allowExtension") || data.get("allowExtension") instanceof Boolean, "加时设置无效");
                room.speechSeconds = seconds;
                room.nightSeconds = nightSeconds;
                room.voteSeconds = voteSeconds;
                room.playMode = playMode;
                if (data.containsKey("allowExtension")) room.allowExtension = Boolean.TRUE.equals(data.get("allowExtension"));
                // Settings do not undo readiness or a player's explicit pause.
                break;
            case "leave":
                require("waiting".equals(room.phase) || "finished".equals(room.phase), "对局中可暂时离开页面，座位会为你保留");
                room.players.remove(p);
                if (room.players.isEmpty()) room.phase = "closed";
                if (room.hostId == p.userId && !room.players.isEmpty())
                {
                    room.hostId = room.players.get(0).userId;
                    room.players.get(0).ready = room.players.get(0).seat > 0;
                }
                break;
            case "kick":
                waiting(room);
                host(room, p.userId);
                Player other;
                if (data.containsKey("userId"))
                {
                    Object targetId = data.get("userId");
                    require(targetId instanceof Integer || targetId instanceof Long, "玩家编号无效");
                    other = player(room, ((Number) targetId).longValue());
                }
                else other = seat(room, integer(data, "seat"));
                require(other.userId != p.userId && now - other.seenAt > 60_000, "仅能移出离线超过一分钟的玩家");
                room.players.remove(other);
                break;
            case "start":
                waiting(room);
                host(room, p.userId);
                require(room.players.size() >= 6 && room.players.size() <= 12 && room.players.stream().allMatch(v -> v.seat > 0 && v.ready), "需要6至12位玩家全部入座并准备");
                start(room, now);
                break;
            case "aim":
                require(Objects.equals(string(data, "phaseToken"), phaseToken(room)), "阶段已变化，请重新选择");
                require("wolves".equals(room.phase) && p.alive && "wolf".equals(p.role), "当前无法同步狼队选择");
                require(!room.actions.containsKey(p.userId), "本阶段已经提交，请等待结果");
                int aim = integer(data, "target");
                Player aimed = aim == 0 ? null : seat(room, aim);
                require(aimed == null || (aimed.alive && !"wolf".equals(aimed.role)), "请选择仍在场上的好人阵营玩家");
                room.wolfAims.put(p.userId, aim);
                break;
            case "act":
                require(Objects.equals(string(data, "phaseToken"), phaseToken(room)), "阶段已变化，请查看当前对局后再操作");
                act(room, p, data);
                if (("vote".equals(room.phase) && room.players.stream().filter(v -> v.alive).allMatch(v -> room.actions.containsKey(v.userId))) || "hunter".equals(room.phase))
                {
                    room.deadline = now;
                    advance(room, now);
                }
                break;
            case "prepareSkip":
                require(Objects.equals(string(data, "phaseToken"), phaseToken(room)), "阶段已变化，请重新操作");
                require((p.alive && (("discussion".equals(room.phase) && p.seat == room.speaker)
                        || (NIGHT.contains(room.phase) && roleCanAct(room, p) && !room.actions.containsKey(p.userId))))
                        || ("hunter".equals(room.phase) && room.pendingHunter == p.seat && !room.actions.containsKey(p.userId)), "当前无法跳过");
                require(p.skipAt == 0, "正在等待跳过，可以点击取消");
                require(room.deadline > now + 3000, "本阶段即将结束，请等待倒计时");
                p.skipAt = now + 3000;
                p.skipPhase = phaseToken(room);
                break;
            case "cancelSkip":
                require(Objects.equals(string(data, "phaseToken"), phaseToken(room)), "阶段已变化，请重新操作");
                require(p.skipAt > now, "跳过已生效");
                p.skipAt = 0;
                p.skipPhase = "";
                break;
            case "extendSpeech":
                require(Objects.equals(string(data, "phaseToken"), phaseToken(room)), "当前发言已经结束");
                require("discussion".equals(room.phase) && p.alive && p.seat == room.speaker, "请等待自己的发言回合");
                require(room.allowExtension && !room.speechExtended && p.skipAt == 0, "本次发言无法继续加时");
                room.deadline += 30_000;
                room.speechExtended = true;
                break;
            case "endSpeech":
                require(Objects.equals(string(data, "phaseToken"), phaseToken(room)), "当前发言已经结束");
                require("discussion".equals(room.phase) && p.alive && p.seat == room.speaker, "请等待自己的发言回合");
                nextSpeaker(room, now);
                break;
            case "message":
                require(Objects.equals(string(data, "phaseToken"), phaseToken(room)), "阶段已变化，请重新发送");
                require(canSpeak(room, p), "请在自己的白天发言回合发送消息");
                String text = string(data, "text").trim();
                require(!text.isEmpty() && text.length() <= 300, "消息需要1至300个字");
                addMessage(room, p, string(data, "requestId"), text, "", 0, now);
                break;
            case "audio":
                require(Objects.equals(string(data, "phaseToken"), phaseToken(room)), "阶段已变化，请重新录音");
                require(canSpeak(room, p), "请在自己的白天发言回合发送语音");
                addMessage(room, p, string(data, "requestId"), "", string(data, "audio"), integer(data, "duration"), now);
                break;
            case "replay":
                host(room, p.userId);
                require("finished".equals(room.phase), "请等待本局结束");
                room.phase = "waiting";
                room.gameId = UUID.randomUUID().toString();
                room.winner = "";
                room.day = 0;
                room.deadline = 0;
                room.speaker = 0;
                room.startedAt = 0;
                room.finishedAt = 0;
                room.pendingHunter = 0;
                room.hunterResume = "";
                room.guarded = 0;
                room.messages.clear();
                room.events.clear();
                room.nightEvents.clear();
                room.actions.clear();
        room.wolfAims.clear();
                for (Player person : room.players)
                {
                    person.role = "";
                    person.alive = true;
                    person.ready = person.seat > 0;
                    person.inspections.clear();
                    person.skipAt = 0;
                    person.skipPhase = "";
                    person.gun = true;
                    person.lastGuard = 0;
                }
                break;
            default:
                throw new ServiceException("不支持这项房间操作", 400);
        }
    }

    private void start(WerewolfRoom room, long now)
    {
        room.playerCount = room.players.size();
        room.players.sort(Comparator.comparingInt(p -> p.seat));
        for (int i = 0; i < room.players.size(); i++) room.players.get(i).seat = i + 1;
        List<String> roles = rolesFor(room.playerCount);
        Collections.shuffle(roles, random);
        for (int i = 0; i < room.players.size(); i++)
        {
            Player p = room.players.get(i);
            p.role = roles.get(i);
            p.alive = true;
            p.antidote = true;
            p.poison = true;
            p.gun = true;
            p.lastGuard = 0;
            p.inspections.clear();
        }
        room.gameId = UUID.randomUUID().toString();
        room.startedAt = now;
        room.finishedAt = 0;
        room.day = 1;
        room.pendingHunter = 0;
        room.hunterResume = "";
        room.events.clear();
                room.nightEvents.clear();
        room.messages.clear();
        phase(room, "starting", now, 5);
    }

    private void beginNight(WerewolfRoom room, long now)
    {
        room.victim = 0;
        room.poisoned = 0;
        room.saved = false;
        room.guarded = 0;
        room.speaker = 0;
        phase(room, room.playerCount >= 10 ? "guard" : "wolves", now, room.nightSeconds);
        room.events.add("第" + room.day + "夜，天黑请闭眼");
    }

    private void phase(WerewolfRoom room, String phase, long now, int seconds)
    {
        room.phase = phase;
        room.deadline = now + seconds * 1000L;
        room.actions.clear();
        room.wolfAims.clear();
        room.speechExtended = false;
        for (Player p : room.players)
        {
            p.skipAt = 0;
            p.skipPhase = "";
        }
    }

    private boolean roleCanAct(WerewolfRoom room, Player p)
    {
        return ("wolves".equals(room.phase) && "wolf".equals(p.role))
                || ("guard".equals(room.phase) && "guard".equals(p.role))
                || ("seer".equals(room.phase) && "seer".equals(p.role))
                || ("witch".equals(room.phase) && "witch".equals(p.role));
    }

    private void act(WerewolfRoom room, Player p, Map<String, Object> data)
    {
        boolean shooting = "hunter".equals(room.phase) && p.seat == room.pendingHunter && "hunter".equals(p.role) && p.gun;
        require(p.alive || shooting, "你已出局，可以继续观看对局");
        require(!room.actions.containsKey(p.userId), "本阶段已经提交，请等待结果");
        int target = integer(data, "target");
        Player chosen = target == 0 ? null : seat(room, target);
        require(chosen == null || chosen.alive, "请选择仍在场上的玩家");
        switch (room.phase)
        {
            case "guard":
                require("guard".equals(p.role), "请等待你的行动回合");
                require(target == 0 || target != p.lastGuard, "不能连续两晚守护同一名玩家");
                room.guarded = target;
                break;
            case "hunter":
                require(shooting, "请等待猎人完成行动");
                require(chosen == null || chosen.userId != p.userId, "请选择其他在场玩家");
                break;
            case "wolves":
                require("wolf".equals(p.role), "请等待你的行动回合");
                require(chosen == null || !"wolf".equals(chosen.role), "请选择好人阵营玩家");
                break;
            case "seer":
                require("seer".equals(p.role), "请等待你的行动回合");
                require(chosen == null || chosen.userId != p.userId, "请选择其他玩家查验");
                if (chosen != null) p.inspections.put(target, "wolf".equals(chosen.role) ? "狼人阵营" : "好人阵营");
                break;
            case "witch":
                require("witch".equals(p.role), "请等待你的行动回合");
                String potion = string(data, "potion");
                require(Arrays.asList("save", "poison", "skip").contains(potion), "请选择使用药水或跳过");
                if ("save".equals(potion))
                {
                    require(p.antidote && room.victim > 0, "今晚无法使用解药");
                    require(room.victim != p.seat || room.day == 1, "仅第一夜可以自救");
                    p.antidote = false;
                    room.saved = true;
                }
                else if ("poison".equals(potion))
                {
                    require(p.poison && chosen != null && chosen.userId != p.userId, "请选择其他在场玩家使用毒药");
                    p.poison = false;
                    room.poisoned = target;
                }
                break;
            case "vote":
                require(chosen == null || chosen.userId != p.userId, "请选择其他玩家或弃票");
                break;
            default:
                throw new ServiceException("当前阶段无需选择玩家", 409);
        }
        room.actions.put(p.userId, target);
        p.skipAt = 0;
        p.skipPhase = "";
    }

    /** Fixed night durations prevent action timing from disclosing whether a role is alive. */
    public boolean advance(WerewolfRoom room, long now)
    {
        boolean changed = false;
        if ("waiting".equals(room.phase))
        {
            changed = room.players.removeIf(p -> now - p.seenAt > 120_000);
            if (room.players.isEmpty()) { room.phase = "closed"; return true; }
            if (room.players.stream().noneMatch(p -> p.userId == room.hostId)) room.hostId = room.players.get(0).userId;
            // Migrate old waiting rooms whose unseated occupants silently consumed capacity.
            for (Player p : room.players)
            {
                if (p.seat <= 0)
                    for (int number = 1; number <= 12; number++)
                    {
                        final int free = number;
                        if (room.players.stream().noneMatch(v -> v.seat == free)) { p.seat = number; changed = true; break; }
                    }
                if (!p.ready && p.seat > 0) { p.ready = true; changed = true; }
            }
        }
        for (int i = 0; room.deadline > 0 && i < 512; i++)
        {
            Player skipping = room.players.stream()
                    .filter(p -> p.skipAt > 0 && p.skipAt < room.deadline && p.skipAt <= now && phaseToken(room).equals(p.skipPhase))
                    .min(Comparator.comparingLong(p -> p.skipAt)).orElse(null);
            if (skipping != null)
            {
                long skipTime = skipping.skipAt;
                skipping.skipAt = 0;
                skipping.skipPhase = "";
                if ("discussion".equals(room.phase)) nextSpeaker(room, skipTime);
                else {
                    act(room, skipping, map("target", 0, "potion", "skip"));
                    if ("hunter".equals(room.phase)) room.deadline = skipTime;
                }
                changed = true;
                continue;
            }
            if (now < room.deadline) break;
            changed = true;
            long boundary = room.deadline;
            if (NIGHT.contains(room.phase)) recordNight(room);
            switch (room.phase)
            {
                case "starting":
                    beginNight(room, boundary);
                    break;
                case "guard":
                    for (Player guard : room.players) if (guard.alive && "guard".equals(guard.role)) guard.lastGuard = room.guarded;
                    phase(room, "wolves", boundary, room.nightSeconds);
                    break;
                case "wolves":
                    room.victim = winningVote(room.actions);
                    phase(room, "seer", boundary, room.nightSeconds);
                    break;
                case "seer":
                    phase(room, "witch", boundary, room.nightSeconds);
                    break;
                case "witch":
                    List<Integer> eliminated = new ArrayList<>();
                    // Both antidote and guard on the wolf target cancel each other; poison ignores protection.
                    if (room.victim > 0 && (room.saved == (room.guarded == room.victim))) eliminated.add(room.victim);
                    if (room.poisoned > 0 && !eliminated.contains(room.poisoned)) eliminated.add(room.poisoned);
                    for (int number : eliminated) seat(room, number).alive = false;
                    room.events.add("第" + room.day + "天，" + (eliminated.isEmpty() ? "昨夜平安夜" : eliminated.stream().map(n -> n + "号").collect(Collectors.joining("、")) + "出局"));
                    if (!offerHunter(room, eliminated, "day", boundary)) continueAfterDeath(room, "day", boundary);
                    break;
                case "discussion":
                    nextSpeaker(room, boundary);
                    break;
                case "vote":
                    int voted = winningVote(room.actions);
                    String votes = room.actions.entrySet().stream()
                            .map(entry -> seatLabel(room, entry.getKey()) + "→" + (entry.getValue() == 0 ? "弃票" : entry.getValue() + "号"))
                            .collect(Collectors.joining("，"));
                    room.events.add("第" + room.day + "天投票 " + votes);
                    if (voted > 0) seat(room, voted).alive = false;
                    room.events.add(voted > 0 ? voted + "号玩家被放逐" : "平票或全员弃票，无人被放逐");
                    if (!offerHunter(room, voted > 0 ? Arrays.asList(voted) : Collections.emptyList(), "night", boundary)) continueAfterDeath(room, "night", boundary);
                    break;
                case "hunter":
                    Player hunter = seat(room, room.pendingHunter);
                    int shot = room.actions.getOrDefault(hunter.userId, 0);
                    hunter.gun = false;
                    if (shot > 0) seat(room, shot).alive = false;
                    room.events.add(hunter.seat + "号猎人" + (shot > 0 ? "开枪，" + shot + "号出局" : "未开枪"));
                    String resume = room.hunterResume;
                    room.pendingHunter = 0;
                    room.hunterResume = "";
                    continueAfterDeath(room, resume, boundary);
                    break;
                default:
                    room.deadline = 0;
            }
        }
        return changed;
    }

    private void recordNight(WerewolfRoom room)
    {
        String role = "wolves".equals(room.phase) ? "wolf" : room.phase;
        for (Player p : room.players) {
            if (!p.alive || !role.equals(p.role)) continue;
            Integer target = room.actions.get(p.userId);
            String action;
            if (target == null) action = "超时未操作";
            else if ("witch".equals(role)) action = room.saved ? "解药救起" + room.victim + "号" : room.poisoned > 0 ? "毒药选择" + room.poisoned + "号" : "未使用药水";
            else if (target == 0) action = "跳过";
            else if ("seer".equals(role)) action = "查验" + target + "号（" + p.inspections.get(target) + "）";
            else action = ("wolf".equals(role) ? "刀人选择" : "守护") + target + "号";
            String name = "wolf".equals(role) ? "狼人" : "seer".equals(role) ? "预言家" : "witch".equals(role) ? "女巫" : "守卫";
            room.nightEvents.add("第" + room.day + "夜 · " + p.seat + "号" + name + "：" + action);
        }
        if ("wolves".equals(room.phase)) {
            int victim = winningVote(room.actions);
            room.nightEvents.add("第" + room.day + "夜 · 狼队结果：" + (victim > 0 ? victim + "号" : "平票或无人选择，空刀"));
        }
    }

    private String seatLabel(WerewolfRoom room, long uid) { return player(room, uid).seat + "号"; }

    private boolean offerHunter(WerewolfRoom room, List<Integer> eliminated, String resume, long now)
    {
        for (int number : eliminated) {
            Player hunter = seat(room, number);
            if ("hunter".equals(hunter.role) && hunter.gun && !("day".equals(resume) && room.poisoned == number)) {
                room.pendingHunter = number;
                room.hunterResume = resume;
                room.events.add(number + "号猎人可以开枪");
                phase(room, "hunter", now, 30);
                return true;
            }
        }
        return false;
    }

    private void continueAfterDeath(WerewolfRoom room, String resume, long now)
    {
        if (finishIfWon(room, now)) return;
        if ("day".equals(resume)) { room.speaker = 0; nextSpeaker(room, now); }
        else if (room.day >= 12) finish(room, "draw", now);
        else { room.day++; beginNight(room, now); }
    }

    private void nextSpeaker(WerewolfRoom room, long now)
    {
        int next = room.players.stream().filter(p -> p.alive && p.seat > room.speaker)
                .mapToInt(p -> p.seat).min().orElse(0);
        room.speaker = next;
        if (next == 0) phase(room, "vote", now, room.voteSeconds);
        else phase(room, "discussion", now, room.speechSeconds);
    }

    private int winningVote(Map<Long, Integer> actions)
    {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Integer target : actions.values()) if (target > 0) counts.merge(target, 1, Integer::sum);
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> top = counts.keySet().stream().filter(n -> counts.get(n) == max).collect(Collectors.toList());
        return top.size() == 1 ? top.get(0) : 0;
    }

    private boolean finishIfWon(WerewolfRoom room, long now)
    {
        long wolves = room.players.stream().filter(p -> p.alive && "wolf".equals(p.role)).count();
        long good = room.players.stream().filter(p -> p.alive && !"wolf".equals(p.role)).count();
        if (wolves == 0) finish(room, "good", now);
        else if (wolves >= good) finish(room, "wolf", now);
        return "finished".equals(room.phase);
    }

    private void finish(WerewolfRoom room, String winner, long now)
    {
        room.winner = winner;
        room.finishedAt = now;
        room.phase = "finished";
        room.deadline = 0;
        for (Player p : room.players) { p.skipAt = 0; p.skipPhase = ""; }
        room.events.add("draw".equals(winner) ? "达到12天上限，本局平局" : ("good".equals(winner) ? "好人阵营获胜" : "狼人阵营获胜"));
    }

    public boolean canSpeak(WerewolfRoom room, Player p)
    {
        return "waiting".equals(room.phase) || "finished".equals(room.phase)
                || ("hunter".equals(room.phase) && room.pendingHunter == p.seat)
                || ("discussion".equals(room.phase) && p.alive && p.seat == room.speaker);
    }

    private void addMessage(WerewolfRoom room, Player p, String id, String text, String audio, int duration, long now)
    {
        Message message = new Message();
        message.id = p.userId + "-" + id;
        message.userId = p.userId;
        message.name = p.name;
        message.seat = p.seat;
        message.text = text;
        message.audio = audio;
        message.duration = duration;
        message.at = now;
        room.messages.add(message);
        if (room.messages.size() > 100) room.messages.remove(0);
    }

    public Map<String, Object> view(WerewolfRoom room, Long uid, long now)
    {
        Player me = room.players.stream().filter(p -> uid != null && p.userId == uid).findFirst().orElse(null);
        Map<String, Object> result = map("code", room.code, "version", room.version, "hostId", room.hostId,
                "createdAt", room.createdAt,
                "playerCount", "waiting".equals(room.phase) ? 12 : room.playerCount, "playMode", room.playMode,
                "roleCounts", rolesFor("waiting".equals(room.phase) ? Math.max(6, room.players.size()) : room.playerCount).stream().collect(Collectors.groupingBy(r -> r, LinkedHashMap::new, Collectors.counting())),
                "pendingHunter", "hunter".equals(room.phase) ? room.pendingHunter : 0,
                "phase", room.phase, "day", room.day, "speaker", room.speaker, "deadline", room.deadline,
                "serverTime", now, "speechSeconds", room.speechSeconds, "phaseToken", phaseToken(room),
                "nightSeconds", room.nightSeconds, "voteSeconds", room.voteSeconds, "allowExtension", room.allowExtension,
                "speechExtended", room.speechExtended,
                "gameId", room.gameId, "winner", room.winner, "startedAt", room.startedAt, "finishedAt", room.finishedAt);
        List<Map<String, Object>> people = new ArrayList<>();
        for (Player p : room.players)
        {
            Map<String, Object> person = map("userId", p.userId, "name", p.name, "seat", p.seat, "avatar", p.avatar,
                    "ready", p.ready, "alive", p.alive, "online", now - p.seenAt <= 45_000,
                    "canKick", now - p.seenAt > 60_000);
            if ("finished".equals(room.phase) && me != null) person.put("role", p.role);
            people.add(person);
        }
        people.sort(Comparator.comparingInt(p -> (Integer) p.get("seat")));
        result.put("players", people);
        if (me != null)
        {
            Map<String, Object> own = map("userId", me.userId, "seat", me.seat, "ready", me.ready, "alive", me.alive,
                    "role", "starting".equals(room.phase) ? "" : me.role, "submitted", room.actions.containsKey(me.userId),
                    "skipAt", me.skipAt, "canSpeak", canSpeak(room, me));
            if ("vote".equals(room.phase)) result.put("votesSubmitted", room.actions.size());
            if (!"starting".equals(room.phase) && "seer".equals(me.role)) own.put("inspections", me.inspections);
            if (!"starting".equals(room.phase) && "guard".equals(me.role)) own.put("lastGuard", me.lastGuard);
            own.put("canShoot", "hunter".equals(room.phase) && room.pendingHunter == me.seat && me.gun);
            if (!"starting".equals(room.phase) && "wolf".equals(me.role)) own.put("wolves", room.players.stream().filter(p -> "wolf".equals(p.role)).map(p -> p.seat).collect(Collectors.toList()));
            if ("wolves".equals(room.phase) && me.alive && "wolf".equals(me.role)) {
                own.put("wolfTargets", room.players.stream().filter(p -> p.alive && "wolf".equals(p.role))
                    .map(p -> map("seat", p.seat, "submitted", room.actions.containsKey(p.userId), "target", room.actions.getOrDefault(p.userId, room.wolfAims.getOrDefault(p.userId, 0)))).collect(Collectors.toList()));
            }
            if (!"starting".equals(room.phase) && "witch".equals(me.role))
            {
                own.put("antidote", me.antidote);
                own.put("poison", me.poison);
                if (me.alive && "witch".equals(room.phase) && me.antidote) own.put("victim", room.victim);
            }
            result.put("me", own);
            result.put("messages", room.messages);
            result.put("events", room.events);
            if ("finished".equals(room.phase)) {
                List<String> replay = new ArrayList<>(room.events);
                if (!room.nightEvents.isEmpty()) { replay.add("夜间行动复盘"); replay.addAll(room.nightEvents); }
                result.put("events", replay);
            }
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
