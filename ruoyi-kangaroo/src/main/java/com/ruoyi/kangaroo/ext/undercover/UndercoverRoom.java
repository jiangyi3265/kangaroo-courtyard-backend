package com.ruoyi.kangaroo.ext.undercover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Redis-only state. UndercoverEngine.view explicitly projects all client responses. */
public class UndercoverRoom
{
    public String code;
    public long version;
    public long hostId;
    public long createdAt;
    public long startedAt;
    public long finishedAt;
    public String gameId = "";
    public String phase = "waiting";
    public String winner = "";
    public String category = "travel";
    public int speechSeconds = 30;
    public int offlineSeconds = 0;
    public int voteSeconds = 45;
    public int round;
    public int turn;
    public int speaker;
    public long deadline;
    public boolean runoff;
    public int pairIndex = -1;
    public long wordPairId;
    public String closeReason = "";
    public String closedBy = "";
    public long closedAt;
    public String civilianWord = "";
    public String undercoverWord = "";
    public List<Player> players = new ArrayList<>();
    public List<Integer> speechQueue = new ArrayList<>();
    public List<Integer> candidates = new ArrayList<>();
    public Map<Long, Integer> votes = new LinkedHashMap<>();
    public List<Message> messages = new ArrayList<>();
    public List<String> events = new ArrayList<>();
    public List<String> receipts = new ArrayList<>();

    public static class Player
    {
        public long userId;
        public String name;
        public int seat;
        public boolean ready;
        public boolean alive = true;
        public long seenAt;
        public String role = "";
        public String word = "";
    }

    public static class Message
    {
        public long userId;
        public String audio = "";
        public int duration;
        public String id;
        public int seat;
        public String name;
        public String text;
        public int round;
        public boolean runoff;
        public long at;
    }
}
