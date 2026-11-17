package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * Public requests only read a fingerprint-checked cache. Only the background worker can spend tokens.
 * One bounded request per stay/hour, at most 5/hour and 30/day by default across all server instances.
 * Redis failure fails closed; no retry loops or arithmetic scores presented as AI output.
 */
@Service
public class KgReviewAiService
{
    private static final Logger LOG = LoggerFactory.getLogger(KgReviewAiService.class);
    private static final String PREFIX = "kg:review-ai:v1:";
    static final int MAX_REVIEWS = 120;
    private static final int MAX_ROOMS = 16;
    private static final DefaultRedisScript<Long> BUDGET = new DefaultRedisScript<>(
            "if redis.call('exists',KEYS[3]) == 1 then return 0 end "
            + "if tonumber(redis.call('get',KEYS[1]) or '0') >= tonumber(ARGV[1]) or "
            + "tonumber(redis.call('get',KEYS[2]) or '0') >= tonumber(ARGV[2]) then return 0 end "
            + "redis.call('incr',KEYS[1]); redis.call('expire',KEYS[1],172800); "
            + "redis.call('incr',KEYS[2]); redis.call('expire',KEYS[2],7200); "
            + "redis.call('set',KEYS[3],'1','EX',3600); return 1", Long.class);
    private final KgJdbc db;
    private final StringRedisTemplate redis;
    private final KgReviewAiSettings settings;
    private final KgReviewAiClient client;
    private final AtomicBoolean working = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "review-ai-background"); t.setDaemon(true); return t;
    });
    private long cursor;

    public KgReviewAiService(KgJdbc db, StringRedisTemplate redis, KgReviewAiSettings settings, KgReviewAiClient client)
    {
        this.db = db; this.redis = redis; this.settings = settings; this.client = client;
    }

    static class Input
    {
        final List<Map<String, Object>> reviews = new ArrayList<>();
        final Map<String, Long> rooms = new LinkedHashMap<>();
        String fingerprint;
    }

    static Input prepare(List<Map<String, Object>> rows, String model) throws Exception
    {
        Input input = new Input();
        Map<Long, String> roomTokens = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
        {
            Long roomId = KgJdbc.asLong(row.get("roomTypeId"));
            Long rating = KgJdbc.asLong(row.get("rating"));
            if (roomId == null || rating == null || rating < 1 || rating > 5) continue;
            String token = roomTokens.get(roomId);
            if (token == null)
            {
                if (roomTokens.size() >= MAX_ROOMS) continue;
                token = "R" + (roomTokens.size() + 1);
                roomTokens.put(roomId, token); input.rooms.put(token, roomId);
            }
            String text = KgReviewAiClient.redact(row.get("content"), KgJdbc.asStr(row.get("nickname")), KgJdbc.asStr(row.get("guestName")));
            // Never send names, IDs, order numbers, media paths or precise stay dates as separate fields.
            Map<String, Object> sample = p("index", input.reviews.size() + 1, "room", token, "rating", rating,
                    "text", text.substring(0, Math.min(400, text.length())));
            int[] dims = KgStayReviewService.dims(row.get("dims"));
            if (dims != null) sample.put("dimensions", p("卫生", dims[0], "设施", dims[1], "服务", dims[2], "安静", dims[3], "位置", dims[4]));
            input.reviews.add(sample);
            if (input.reviews.size() >= MAX_REVIEWS) break;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(KgReviewAiClient.JSON.writeValueAsString(
                p("promptVersion", 1, "model", model, "rooms", input.rooms, "reviews", input.reviews)).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b & 255));
        input.fingerprint = hex.toString();
        return input;
    }

    Input input(Long stayId) throws Exception
    {
        // The matched order must belong to the reviewer and be a completed stay. Seed/unbound reviews are excluded.
        return prepare(db.list("select v.rating, v.content, v.dims, o.room_type_id, o.guest_name, u.nickname from kg_review v "
                + "join kg_stay_order o on v.biz_type='stay_order' and o.order_id=v.biz_id and o.stay_id=v.target_id "
                + "and o.app_user_id=v.app_user_id and o.status='completed' and o.del_flag='0' "
                + "left join kg_app_user u on u.user_id=v.app_user_id "
                + "where v.target_type='room' and v.target_id=:s and v.status='0' and v.del_flag='0' "
                + "order by v.review_id desc limit " + MAX_REVIEWS, p("s", stayId)), settings.model);
    }

    /** Returns promptly; it never enqueues work and never calls a model. */
    public Map<String, Object> snapshot(Long stayId)
    {
        if (!settings.configured()) return bundle("disabled", 0);
        try
        {
            Input input = input(stayId);
            if (input.reviews.isEmpty()) return bundle("no_reviews", 0);
            Map<String, Object> cached = cached(stayId);
            if (cached != null && input.fingerprint.equals(cached.get("fingerprint")))
            {
                cached.remove("fingerprint");
                return cached;
            }
            return bundle("pending", input.reviews.size());
        }
        catch (Exception e) { return bundle("unavailable", 0); }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> cached(Long stayId) throws Exception
    {
        String raw = redis.opsForValue().get(PREFIX + "result:" + stayId);
        return raw == null ? null : KgReviewAiClient.JSON.readValue(raw, Map.class);
    }

    static Map<String, Object> state(String status, int count)
    {
        return p("status", status, "score", null, "source", null, "summary", "", "highlights", Collections.emptyList(),
                "cautions", Collections.emptyList(), "reviewCount", count, "generatedAt", null,
                "method", "AI基于真实入住评价总结", "notice", "AI参考分，不替代住客评分");
    }

    public static Map<String, Object> bundle(String status, int count)
    {
        return p("overall", state(status, count), "rooms", Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> overall(Map<String, Object> bundle)
    {
        return bundle == null ? state("disabled", 0) : (Map<String, Object>) bundle.get("overall");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> room(Map<String, Object> bundle, Object roomId, long totalReviews)
    {
        Map<String, Object> all = overall(bundle);
        if (bundle != null)
        {
            Map<String, Object> rooms = (Map<String, Object>) bundle.get("rooms");
            if (rooms != null && rooms.containsKey(String.valueOf(roomId))) return (Map<String, Object>) rooms.get(String.valueOf(roomId));
        }
        return state(totalReviews == 0 ? "no_reviews" : ("ready".equals(all.get("status")) ? "not_in_sample" : String.valueOf(all.get("status"))), 0);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 120000)
    public void tick()
    {
        if (!settings.configured() || !working.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try { scan(); }
            catch (Exception e) { LOG.warn("评价AI后台暂不可用（不影响真实评价统计）"); }
            finally { working.set(false); }
        });
    }

    void scan() throws Exception
    {
        List<Map<String, Object>> stays = db.list("select s.stay_id from kg_stay s where s.stay_id>:cursor and "
                + KgStayBrowseService.ONLINE + " and exists (select 1 from kg_review v where v.target_type='room' and v.target_id=s.stay_id "
                + "and v.status='0' and v.del_flag='0') order by s.stay_id limit 4", p("cursor", cursor));
        if (stays.isEmpty()) { cursor = 0; return; }
        for (Map<String, Object> stay : stays)
        {
            Long id = KgJdbc.asLong(stay.get("stayId"));
            cursor = id;
            if (refresh(id)) break; // at most one outbound request per tick
        }
    }

    /** Package-private for deterministic integration tests; not exposed as a public HTTP endpoint. */
    boolean refresh(Long stayId) throws Exception
    {
        if (!settings.configured()) return false;
        Input input = input(stayId);
        if (input.reviews.isEmpty()) return false;
        Map<String, Object> cached = cached(stayId);
        if (cached != null && input.fingerprint.equals(cached.get("fingerprint"))) return false;
        Instant now = Instant.now();
        String day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now);
        String hour = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC).format(now);
        Long reserved = redis.execute(BUDGET, Arrays.asList(PREFIX + "budget:d:" + day, PREFIX + "budget:h:" + hour, PREFIX + "slot:" + stayId),
                String.valueOf(settings.dailyCap()), String.valueOf(settings.hourlyCap()));
        if (!Long.valueOf(1).equals(reserved)) return false;
        Map<String, Object> result;
        boolean success;
        try
        {
            result = client.generate(input);
            // A review may have been hidden/deleted while HTTP was running. Never publish that stale answer.
            if (!input.fingerprint.equals(input(stayId).fingerprint)) return true;
            success = true;
        }
        catch (Exception e)
        {
            result = bundle("unavailable", input.reviews.size()); success = false;
            // Provider response bodies may contain credentials/PII. Never put them or exception text in logs.
            LOG.warn("评价AI生成失败，保留真实评价统计；将在后台限额内重试");
        }
        result.put("fingerprint", input.fingerprint);
        redis.opsForValue().set(PREFIX + "result:" + stayId, KgReviewAiClient.JSON.writeValueAsString(result), success ? 7 * 86400 : 3600, TimeUnit.SECONDS);
        return true;
    }

    @PreDestroy public void close() { worker.shutdownNow(); }
}
