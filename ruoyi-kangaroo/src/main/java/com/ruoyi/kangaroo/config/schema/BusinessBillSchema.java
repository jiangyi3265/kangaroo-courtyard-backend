package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BusinessBillSchema implements KgSchemaContributor {
    public String migration() { return "sql/kangaroo/V82__business_settlement.sql"; }

    public Map<String, List<String>> requiredIndexes() {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        indexes.put("kg_business_bill", Arrays.asList("PRIMARY", "uk_business_bill_source", "idx_business_bill_store"));
        indexes.put("kg_business_bill_log", Arrays.asList("PRIMARY", "idx_business_bill_log"));
        return indexes;
    }
}
