package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BusinessWithdrawalSchema implements KgSchemaContributor {
    public String migration() { return "sql/kangaroo/V83__business_withdrawal.sql"; }

    public Map<String, List<String>> requiredIndexes() {
        return Collections.singletonMap("kg_business_withdrawal", Arrays.asList("PRIMARY",
                "uk_business_withdrawal_request", "uk_business_withdrawal_active",
                "idx_business_withdrawal_bill", "idx_business_withdrawal_retry"));
    }
}
