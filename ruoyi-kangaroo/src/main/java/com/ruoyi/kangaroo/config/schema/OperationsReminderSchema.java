package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class OperationsReminderSchema implements KgSchemaContributor {
    public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<>();for(String n:Arrays.asList("batch","item"))m.put("kg_ops_reminder_"+n,Arrays.asList("uk_ops_reminder_"+n));return m;}
    public String migration(){return "sql/kangaroo/V107__operations_reminders.sql";}
}
