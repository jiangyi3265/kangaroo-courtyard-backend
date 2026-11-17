package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class BadgeRulesSchema implements KgSchemaContributor {
    private final KgJdbc db;
    private final boolean enabled;

    public BadgeRulesSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) {
        this.db = db;
        this.enabled = enabled;
    }

    public String migration() {
        return "sql/kangaroo/V88__badge_rules.sql";
    }

    public Map<String, List<String>> requiredIndexes() {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        indexes.put("kg_badge_integrity", Arrays.asList("PRIMARY"));
        indexes.put("kg_badge_content_check", Arrays.asList("PRIMARY", "idx_badge_content_user"));
        indexes.put("kg_badge_violation", Arrays.asList("PRIMARY", "uk_badge_violation"));
        indexes.put("kg_badge_quarter_review", Arrays.asList("PRIMARY"));
        indexes.put("kg_badge_log", Arrays.asList("uk_badge_log_related_action", "uk_badge_log_vote_action"));
        return indexes;
    }

    @PostConstruct
    public void check() {
        if (enabled && db.count("select count(*) from kg_schema_version where version=88 and name='badge_rules'", KgJdbc.p()) != 1) {
            throw new IllegalStateException("Run " + migration() + " before deploying badge rules");
        }
    }
}
