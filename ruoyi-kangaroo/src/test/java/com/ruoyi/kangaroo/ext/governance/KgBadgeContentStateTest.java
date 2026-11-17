package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

class KgBadgeContentStateTest {
    private Map<String, Object> content() {
        return p("contentType", "post", "contentId", 23L, "title", "旅居记录", "content", "核验过的真实内容",
                "images", "/a.jpg", "status", "online", "delFlag", "0", "sourceQuality", "0", "sourceRevoked", "0");
    }

    @Test void manualQualityAndFeaturedRemainSeparate() {
        Map<String, Object> row = content();
        row.put("manualQuality", "1");
        row.put("manualFeatured", "0");
        row.put("contentDigest", KgBadgeModeration.contentDigest(row));
        KgBadgeModeration.contentState(row);
        assertEquals("1", row.get("quality"));
        assertEquals("0", row.get("featured"));
    }

    @Test void changingTextOrPhotosRequiresNewAssessment() {
        Map<String, Object> row = content();
        row.put("manualQuality", "1");
        row.put("manualFeatured", "1");
        row.put("contentDigest", KgBadgeModeration.contentDigest(row));
        row.put("images", "/b.jpg");
        KgBadgeModeration.contentState(row);
        assertEquals("0", row.get("quality"));
        assertEquals("0", row.get("featured"));
        assertEquals(true, row.get("needsRecheck"));
    }

    @Test void verifiedMemberEssenceIsInheritedUntilRevoked() {
        Map<String, Object> row = content();
        row.put("contentType", "review");
        row.put("status", "0");
        row.put("sourceQuality", "1");
        KgBadgeModeration.contentState(row);
        assertEquals("1", row.get("quality"));
        assertEquals("1", row.get("featured"));
        row.put("sourceRevoked", "1");
        KgBadgeModeration.contentState(row);
        assertEquals("0", row.get("quality"));
    }

    @Test void hiddenDeletedAndConfirmedFalseContentCannotCount() {
        for (String reason : new String[] { "hidden", "deleted", "false" }) {
            Map<String, Object> row = content();
            row.put("manualQuality", "1");
            row.put("manualFeatured", "1");
            row.put("contentDigest", KgBadgeModeration.contentDigest(row));
            if ("hidden".equals(reason)) row.put("status", "hidden");
            if ("deleted".equals(reason)) row.put("delFlag", "2");
            if ("false".equals(reason)) row.put("falseContent", true);
            KgBadgeModeration.contentState(row);
            assertEquals("0", row.get("quality"), reason);
            assertEquals(false, row.get("canAssess"), reason);
        }
    }

    @Test void fractionalOverflowAndNonpositiveIdsNeverReachDatabaseCommands() {
        KgBadgeModeration moderation = new KgBadgeModeration();
        for (Object invalid : new Object[] { 1.5d, new java.math.BigDecimal("1.1"), "9223372036854775808", "1e3", 0, -1, null }) {
            Map<String, Object> content = p("contentType", "post", "contentId", invalid, "quality", true, "featured", false,
                    "note", "已核实内容真实性以及完整内容来源");
            assertThrows(ServiceException.class, () -> moderation.assessContent(content, "admin:test"));
            Map<String, Object> violation = p("userId", 1, "targetId", invalid, "targetType", "invited", "violationType", "referral_fraud",
                    "deduction", 10, "note", "已核实具体邀请关系的真实注册和服务记录");
            assertThrows(ServiceException.class, () -> moderation.recordViolation(violation, "admin:test"));
        }
    }
}
