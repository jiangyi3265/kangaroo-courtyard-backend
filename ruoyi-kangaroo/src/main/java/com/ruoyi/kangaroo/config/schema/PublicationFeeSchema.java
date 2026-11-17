package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class PublicationFeeSchema implements KgSchemaContributor {
    public String migration() { return "sql/kangaroo/V86__publication_fee.sql"; }
    public Map<String, List<String>> requiredIndexes() {
        Map<String,List<String>> indexes = new LinkedHashMap<>();
        indexes.put("kg_publication_fee", Arrays.asList("PRIMARY", "uk_publication_fee_job", "uk_publication_fee_request"));
        indexes.put("kg_publication_fee_config", Arrays.asList("PRIMARY"));
        indexes.put("kg_publication_fee_log", Arrays.asList("PRIMARY"));
        return indexes;
    }
}
