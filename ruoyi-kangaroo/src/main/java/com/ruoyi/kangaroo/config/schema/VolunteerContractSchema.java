package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class VolunteerContractSchema implements KgSchemaContributor {
    public Map<String,List<String>> requiredIndexes() {
        return Collections.singletonMap("kg_volunteer_contract", Arrays.asList("uk_volunteer_contract_revision", "uk_volunteer_contract_no"));
    }
    public String migration() { return "sql/kangaroo/V106__volunteer_contract.sql"; }
}
