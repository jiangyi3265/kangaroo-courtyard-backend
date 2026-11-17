package com.ruoyi.kangaroo.config.schema;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
@Component
public class SubscribeOutboxSchema implements KgSchemaContributor
{
    @Override public Map<String,List<String>> requiredIndexes() {
        Map<String,List<String>> result=new java.util.LinkedHashMap<>();
        result.put("kg_subscribe_outbox",java.util.Arrays.asList("uk_subscribe_event","idx_outbox_retry","idx_subscribe_message"));
        result.put("kg_subscribe_consent",java.util.Arrays.asList("idx_sub_consent_user"));return result;
    }
    @Override public String migration() { return "sql/kangaroo/V112__subscribe_scenes.sql"; }
}
