package com.ruoyi.kangaroo.config.schema;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ApplicationDatesSchema implements KgSchemaContributor {
    @Override public Map<String,List<String>> requiredIndexes() { return Collections.singletonMap("kg_application_date_plan",Collections.singletonList("PRIMARY")); }
    @Override public String migration() { return "sql/kangaroo/V93__application_dates.sql"; }
}
