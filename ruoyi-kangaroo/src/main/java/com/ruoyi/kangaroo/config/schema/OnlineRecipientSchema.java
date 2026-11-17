package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class OnlineRecipientSchema implements KgSchemaContributor {
    @Override public Map<String,List<String>> requiredIndexes() { return Collections.singletonMap("kg_online_recipient",Collections.singletonList("PRIMARY")); }
    @Override public String migration() { return "sql/kangaroo/V115__online_recipient.sql"; }
}
