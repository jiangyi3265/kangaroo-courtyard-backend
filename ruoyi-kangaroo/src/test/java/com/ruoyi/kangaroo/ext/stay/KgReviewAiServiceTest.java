package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgReviewAiServiceTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String, String> values = mock(ValueOperations.class);
    final KgReviewAiSettings settings = KgReviewAiClientTest.settings();
    final KgReviewAiClient client = mock(KgReviewAiClient.class);
    final KgReviewAiService service = new KgReviewAiService(db, redis, settings, client);
    final List<Map<String, Object>> rows = Collections.singletonList(p("roomTypeId", 11L, "rating", 4L, "content", "环境安静，设施较旧"));

    KgReviewAiServiceTest()
    {
        when(redis.opsForValue()).thenReturn(values);
        when(db.list(anyString(), anyMap())).thenReturn(rows);
    }
    @AfterEach void close() { service.close(); }

    String cached(String status, String fingerprint) throws Exception
    {
        Map<String, Object> out = KgReviewAiService.bundle(status, 1); out.put("fingerprint", fingerprint);
        return KgReviewAiClient.JSON.writeValueAsString(out);
    }

    @Test void disabledDoesNotReadReviewsOrSpendTokens() throws Exception
    {
        settings.enabled = false;
        assertEquals("disabled", KgReviewAiService.overall(service.snapshot(1L)).get("status"));
        assertFalse(service.refresh(1L)); verifyNoInteractions(db, redis, client);
    }

    @Test void publicRequestsNeverTriggerModelEvenWhenCacheMissing() throws Exception
    {
        for (int i = 0; i < 20; i++) assertEquals("pending", KgReviewAiService.overall(service.snapshot(1L)).get("status"));
        verifyNoInteractions(client); verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
    }

    @Test void emptyReviewsHaveNoArtificialScore() throws Exception
    {
        when(db.list(anyString(), anyMap())).thenReturn(Collections.emptyList());
        Map<String, Object> ai = KgReviewAiService.overall(service.snapshot(1L));
        assertEquals("no_reviews", ai.get("status")); assertNull(ai.get("score"));
        assertFalse(service.refresh(1L)); verifyNoInteractions(client, redis);
    }

    @Test void changedOrHiddenReviewInvalidatesCachedResultOnRead() throws Exception
    {
        String fingerprint = service.input(1L).fingerprint;
        when(values.get(anyString())).thenReturn(cached("ready", fingerprint));
        assertEquals("ready", KgReviewAiService.overall(service.snapshot(1L)).get("status"));
        when(db.list(anyString(), anyMap())).thenReturn(Collections.singletonList(p("roomTypeId", 11L, "rating", 1L, "content", "设施很差")));
        assertEquals("pending", KgReviewAiService.overall(service.snapshot(1L)).get("status"));
        verifyNoInteractions(client);
    }

    @Test void sameFingerprintSkipsBudgetAndHttpWhileFailureIsCached() throws Exception
    {
        String value = cached("unavailable", service.input(1L).fingerprint);
        when(values.get(anyString())).thenReturn(value);
        assertFalse(service.refresh(1L)); verifyNoInteractions(client);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
    }

    @Test void redisOutageFailsClosed() throws Exception
    {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        assertEquals("unavailable", KgReviewAiService.overall(service.snapshot(1L)).get("status"));
        assertThrows(Exception.class, () -> service.refresh(1L)); verifyNoInteractions(client);
    }

    @Test void exhaustedDistributedBudgetPreventsProviderCall() throws Exception
    {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(0L);
        assertFalse(service.refresh(1L)); verifyNoInteractions(client);
    }

    @Test void successfulGenerationCachesFingerprintAndProviderFailureIsUnavailable() throws Exception
    {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
        when(client.generate(any())).thenReturn(KgReviewAiService.bundle("ready", 1));
        assertTrue(service.refresh(1L));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("kg:review-ai:v1:result:1"), payload.capture(), eq(604800L), eq(TimeUnit.SECONDS));
        assertEquals(service.input(1L).fingerprint, KgReviewAiClient.JSON.readTree(payload.getValue()).get("fingerprint").asText());
        when(client.generate(any())).thenThrow(new IllegalStateException("secret provider output"));
        assertTrue(service.refresh(2L));
        verify(values).set(eq("kg:review-ai:v1:result:2"), payload.capture(), eq(3600L), eq(TimeUnit.SECONDS));
        assertEquals("unavailable", KgReviewAiClient.JSON.readTree(payload.getValue()).at("/overall/status").asText());
        assertFalse(payload.getValue().contains("secret provider output"));
    }

    @Test void hiddenDuringGenerationMustNotPublishStaleSummary() throws Exception
    {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
        when(client.generate(any())).thenAnswer(invocation -> {
            when(db.list(anyString(), anyMap())).thenReturn(Collections.emptyList());
            return KgReviewAiService.bundle("ready", 1);
        });
        assertTrue(service.refresh(1L));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test void queryRequiresRealCompletedOrderAndDoesNotSelectSensitiveIdentifiers() throws Exception
    {
        service.snapshot(1L); ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), eq(p("s", 1L)));
        String q = sql.getValue();
        assertTrue(q.contains("o.app_user_id=v.app_user_id")); assertTrue(q.contains("o.status='completed'"));
        assertTrue(q.contains("v.status='0' and v.del_flag='0'")); assertTrue(q.contains("limit 120"));
        assertFalse(q.contains("guest_phone")); assertFalse(q.contains("order_no"));
    }

    @Test void promptInputIsBoundedAndFingerprintsExcludeIrrelevantPersonalMetadata() throws Exception
    {
        List<Map<String, Object>> lots = new ArrayList<>();
        for (int i = 0; i < 150; i++) lots.add(p("roomTypeId", 11L, "rating", 4L, "content", String.join("", Collections.nCopies(600, "好")), "orderNo", "secret-order"));
        KgReviewAiService.Input first = KgReviewAiService.prepare(lots, "m");
        assertEquals(120, first.reviews.size()); assertEquals(400, first.reviews.get(0).get("text").toString().length());
        lots.get(0).put("orderNo", "different-order");
        assertEquals(first.fingerprint, KgReviewAiService.prepare(lots, "m").fingerprint);
        assertNotEquals(first.fingerprint, KgReviewAiService.prepare(lots, "different-model").fingerprint);
        assertEquals("no_reviews", KgReviewAiService.room(KgReviewAiService.bundle("ready", 1), 2L, 0).get("status"));
        assertEquals("not_in_sample", KgReviewAiService.room(KgReviewAiService.bundle("ready", 1), 2L, 1).get("status"));
    }
}
