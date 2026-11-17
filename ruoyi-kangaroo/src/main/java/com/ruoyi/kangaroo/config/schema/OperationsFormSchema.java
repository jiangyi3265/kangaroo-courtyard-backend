package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component public class OperationsFormSchema implements KgSchemaContributor {
    public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<>();m.put("kg_ops_form",Arrays.asList("PRIMARY"));m.put("kg_ops_form_change",Arrays.asList("uk_ops_form_change"));m.put("kg_ops_daily_change",Arrays.asList("uk_ops_daily_change","uk_ops_daily_change_revision"));return m;}
    public String migration(){return "sql/kangaroo/V108__operations_forms.sql";}
}
