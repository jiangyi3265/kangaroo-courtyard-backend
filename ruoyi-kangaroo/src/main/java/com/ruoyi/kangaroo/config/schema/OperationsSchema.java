package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class OperationsSchema implements KgSchemaContributor {
    public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<>();for(String name:Arrays.asList("daily","target","monthly","notice","result"))m.put("kg_ops_"+name,Arrays.asList("uk_ops_"+name));return m;}
    public String migration(){return "sql/kangaroo/V105__operations_reports.sql";}
}
