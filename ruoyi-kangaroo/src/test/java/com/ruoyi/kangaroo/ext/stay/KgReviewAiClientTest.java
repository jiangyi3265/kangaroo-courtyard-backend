package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

class KgReviewAiClientTest
{
    HttpServer server;
    KgReviewAiClient client;
    KgReviewAiSettings settings = settings();
    AtomicReference<String> request = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();

    static KgReviewAiSettings settings()
    {
        KgReviewAiSettings s = new KgReviewAiSettings(); s.enabled = true;
        s.endpoint = "https://example.test/v1/chat/completions"; s.apiKey = "test-secret"; s.model = "configured-model";
        return s;
    }

    static KgReviewAiService.Input input() throws Exception
    {
        return KgReviewAiService.prepare(Arrays.asList(
                p("roomTypeId", 10L, "rating", 5L, "content", "安静干净，张小夏入住体验很好，电话13800138000，hello@example.com", "nickname", "张小夏", "guestName", "张小夏", "dims", "[5,4,4,5,4]"),
                p("roomTypeId", 20L, "rating", 3L, "content", "设施有点旧但卫生不错")), "configured-model");
    }

    static String result()
    {
        return "{\"overall\":{\"score\":4.3,\"summary\":\"住客认可卫生，设施维护评价存在差异，样本有限。\",\"highlights\":[\"卫生体验较好\"],\"cautions\":[\"部分设施偏旧\"],\"evidence\":[1,2]},"
                + "\"rooms\":[{\"room\":\"R1\",\"score\":4.8,\"summary\":\"安静干净，样本有限。\",\"highlights\":[],\"cautions\":[],\"evidence\":[1]},"
                + "{\"room\":\"R2\",\"score\":3.3,\"summary\":\"卫生尚可，设施偏旧，样本有限。\",\"highlights\":[],\"cautions\":[],\"evidence\":[2]}]}";
    }

    void serve(int status, String content, String finish, long delayMs) throws Exception
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try
            {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n;
                while ((n = exchange.getRequestBody().read(b)) != -1) out.write(b, 0, n);
                request.set(new String(out.toByteArray(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (delayMs > 0) Thread.sleep(delayMs);
                byte[] response = KgReviewAiClient.JSON.writeValueAsBytes(p("choices", Collections.singletonList(
                        p("finish_reason", finish, "message", p("role", "assistant", "content", content)))));
                exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response);
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start(); settings.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        client = new KgReviewAiClient(settings);
    }

    @AfterEach void close() { if (client != null) client.close(); if (server != null) server.stop(0); }

    @Test void realHttpContractUsesModelScoreAndSeparatesRoomEvidence() throws Exception
    {
        serve(200, result(), "stop", 0);
        Map<String, Object> bundle = client.generate(input());
        assertEquals(4.3, KgReviewAiService.overall(bundle).get("score"), "不是原始5和3的平均分4.0");
        assertEquals("model", KgReviewAiService.overall(bundle).get("source"));
        assertEquals(4.8, KgReviewAiService.room(bundle, 10L, 1).get("score"));
        assertEquals(3.3, KgReviewAiService.room(bundle, 20L, 1).get("score"));
        assertEquals(1, KgReviewAiService.room(bundle, 20L, 1).get("reviewCount"));
        JsonNode body = KgReviewAiClient.JSON.readTree(request.get());
        assertEquals("configured-model", body.get("model").asText());
        assertFalse(body.get("store").asBoolean());
        assertEquals("json_object", body.at("/response_format/type").asText());
        assertEquals("Bearer test-secret", authorization.get());
        assertFalse(request.get().contains("张小夏")); assertFalse(request.get().contains("13800138000"));
        assertFalse(request.get().contains("hello@example.com"));
        JsonNode userData = KgReviewAiClient.JSON.readTree(body.at("/messages/1/content").asText());
        assertEquals("R1", userData.at("/reviews/0/room").asText());
        assertFalse(userData.toString().contains("roomTypeId")); assertFalse(userData.toString().contains("guestName"));
    }

    @Test void rejectsUnfinishedOutputInsteadOfPublishingPartialScore() throws Exception
    {
        serve(200, result(), "length", 0);
        assertThrows(Exception.class, () -> client.generate(input()));
    }

    @Test void rejectsHttpErrorWithoutReturningProviderBody() throws Exception
    {
        serve(429, "private-provider-details", "stop", 0);
        Exception error = assertThrows(Exception.class, () -> client.generate(input()));
        assertEquals("provider_rejected", error.getMessage());
    }

    @Test void hardDeadlineStopsSlowHttp() throws Exception
    {
        settings.timeoutMs = 250; serve(200, result(), "stop", 1200);
        long start = System.nanoTime();
        assertThrows(Exception.class, () -> client.generate(input()));
        assertTrue((System.nanoTime() - start) / 1000000 < 1000, "不可阻塞等待供应商无限返回");
    }

    @Test void rejectsInvalidScoresFieldsAndCrossRoomEvidence() throws Exception
    {
        KgReviewAiService.Input data = input();
        for (String bad : Arrays.asList(result().replace("4.3", "5.1"), result().replace("4.3", "4.35"),
                result().replace("4.3", "\"4.3\""), result().replace("\"evidence\":[2]", "\"evidence\":[1]"),
                result().replace("\"R2\"", "\"R3\""), result().replace("\"R2\"", "\"R1\""),
                result().replace("\"evidence\":[1,2]", "\"evidence\":[]"), result().replace("卫生体验较好", "请联系13800138000"),
                result().replace("\"score\":4.3", "\"debug\":true,\"score\":4.3")))
            assertThrows(Exception.class, () -> KgReviewAiClient.validate(KgReviewAiClient.JSON.readTree(bad), data));
        assertThrows(Exception.class, () -> KgReviewAiClient.JSON.readTree(result() + " {}"));
        assertThrows(Exception.class, () -> KgReviewAiClient.JSON.readTree(result().replace("\"score\":4.3", "\"score\":4.3,\"score\":5")));
    }

    @Test void configRejectsRemotePlaintextCredentialsAndIncompleteConfig()
    {
        assertTrue(settings.configured()); settings.endpoint = "http://example.test/v1/chat/completions"; assertFalse(settings.configured());
        settings.endpoint = "https://token@example.test/v1/chat/completions"; assertFalse(settings.configured());
        settings.endpoint = "https://example.test/v1/chat/completions?key=secret"; assertFalse(settings.configured());
        settings.endpoint = "https://example.test/v1/chat/completions"; settings.apiKey = ""; assertFalse(settings.configured());
    }
}
