package com.ruoyi.kangaroo.ext.werewolf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-only snapshot. Clients receive the explicit projection from WerewolfEngine.view. */
public class WerewolfRoom
{
    public String code;
    public long version;
    public long hostId;
    public long createdAt;
    public long startedAt;
    public long finishedAt;
    public String gameId = "";
    public String phase = "waiting";
    public int day;
    public int speaker;
    public long deadline;
    public int speechSeconds = 90;
    public int nightSeconds = 30;
    public int voteSeconds = 45;
    public int playerCount = 6;
    public String playMode = "online";
    public boolean allowExtension = true;
    public boolean speechExtended;
    public String winner = "";
    public int victim;
    public int poisoned;
    public boolean saved;
    public int guarded;
    public int pendingHunter;
    public String hunterResume = "";
    public List<Player> players = new ArrayList<>();
    public Map<Long, Integer> actions = new LinkedHashMap<>();
    public Map<Long, Integer> wolfAims = new LinkedHashMap<>();
    public List<Message> messages = new ArrayList<>();
    public List<String> events = new ArrayList<>();
    public List<String> nightEvents = new ArrayList<>();
    public List<String> receipts = new ArrayList<>();

    public static class Player
    {
        public long userId;
        public String name;
        public int seat;
        public int avatar;
        public boolean ready;
        public boolean alive = true;
        public long seenAt;
        public String role = "";
        public boolean antidote = true;
        public boolean poison = true;
        public boolean gun = true;
        public int lastGuard;
        public long skipAt;
        public String skipPhase = "";
        public Map<Integer, String> inspections = new LinkedHashMap<>();
    }

    public static class Message
    {
        public String id;
        public long userId;
        public String name;
        public int seat;
        public String text = "";
        public String audio = "";
        public int duration;
        public long at;
    }
}
