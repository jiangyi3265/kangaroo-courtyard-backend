package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component public class StaffTaskSchema implements KgSchemaContributor {
    public String migration(){return "sql/kangaroo/V62__staff_task_dispatch.sql";}
    public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<>();m.put("kg_staff_task",Arrays.asList("idx_staff_task_owner","idx_staff_task_status","uk_staff_task_request"));m.put("kg_staff_task_submission",Arrays.asList("idx_staff_task_submission"));m.put("kg_staff_task_file",Arrays.asList("idx_staff_task_file"));return m;}
}
