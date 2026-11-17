package com.ruoyi.kangaroo.ext.stay;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Credentials belong to the server environment, never to mini-program builds. */
@Component
public class KgReviewAiSettings
{
    @Value("${kangaroo.review-ai.enabled:${KG_REVIEW_AI_ENABLED:false}}")
    boolean enabled;
    @Value("${kangaroo.review-ai.endpoint:${KG_REVIEW_AI_ENDPOINT:}}")
    String endpoint = "";
    @Value("${kangaroo.review-ai.api-key:${KG_REVIEW_AI_API_KEY:}}")
    String apiKey = "";
    @Value("${kangaroo.review-ai.model:${KG_REVIEW_AI_MODEL:}}")
    String model = "";
    @Value("${kangaroo.review-ai.daily-limit:${KG_REVIEW_AI_DAILY_LIMIT:30}}")
    int dailyLimit = 30;
    @Value("${kangaroo.review-ai.hourly-limit:${KG_REVIEW_AI_HOURLY_LIMIT:5}}")
    int hourlyLimit = 5;
    @Value("${kangaroo.review-ai.timeout-ms:15000}")
    int timeoutMs = 15000;

    boolean configured()
    {
        if (!enabled || apiKey.trim().isEmpty() || model.trim().isEmpty()) return false;
        try
        {
            URI u = URI.create(endpoint);
            // HTTP is only useful for a local mock during tests. Remote credentials require TLS.
            boolean local = "127.0.0.1".equals(u.getHost()) || "localhost".equals(u.getHost());
            return ("https".equals(u.getScheme()) || (local && "http".equals(u.getScheme())))
                    && u.getHost() != null && u.getRawUserInfo() == null && u.getQuery() == null && u.getFragment() == null
                    && u.getPath().endsWith("/chat/completions") && !apiKey.contains("\n") && !apiKey.contains("\r");
        }
        catch (IllegalArgumentException e) { return false; }
    }

    int dailyCap() { return Math.min(100, Math.max(0, dailyLimit)); }
    int hourlyCap() { return Math.min(10, Math.max(0, hourlyLimit)); }
    int timeout() { return Math.min(30000, Math.max(250, timeoutMs)); }
}
