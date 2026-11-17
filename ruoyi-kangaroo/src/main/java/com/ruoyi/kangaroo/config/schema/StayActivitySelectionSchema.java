package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class StayActivitySelectionSchema implements KgSchemaContributor
{
    @Override public Map<String,List<String>> requiredIndexes() { return Collections.singletonMap("kg_stay_activity_selection",Collections.singletonList("PRIMARY")); }
    @Override public String migration() { return "sql/kangaroo/V21__stay_activity_selection.sql"; }
}
