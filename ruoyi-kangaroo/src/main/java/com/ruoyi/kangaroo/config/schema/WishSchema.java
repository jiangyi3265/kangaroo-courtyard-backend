package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class WishSchema implements KgSchemaContributor {
    public String migration() { return "sql/kangaroo/V114__wish_order_communication.sql"; }
    public Map<String,List<String>> requiredIndexes() {
        Map<String,List<String>> m = new LinkedHashMap<>();
        m.put("kg_wish",Arrays.asList("PRIMARY","uk_wish_request","idx_wish_eligibility_review"));
        m.put("kg_wish_credential",Arrays.asList("PRIMARY","idx_wish_credential_valid"));
        m.put("kg_wish_message",Arrays.asList("PRIMARY","uk_wish_message"));
        m.put("kg_wish_message_image",Arrays.asList("PRIMARY","idx_wish_image_owner"));
        m.put("kg_wish_audit",Arrays.asList("PRIMARY"));
        m.put("kg_wish_payout",Arrays.asList("PRIMARY","uk_wish_payout","uk_wish_receipt"));
        return m;
    }
}
