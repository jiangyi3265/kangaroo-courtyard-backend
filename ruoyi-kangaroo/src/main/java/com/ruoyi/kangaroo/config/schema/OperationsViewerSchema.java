package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component public class OperationsViewerSchema implements KgSchemaContributor {
    public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<>();m.put("kg_ops_viewer",Arrays.asList("PRIMARY"));m.put("kg_ops_viewer_change",Arrays.asList("uk_ops_viewer_change"));return m;}
    public String migration(){return "sql/kangaroo/V109__operations_viewers.sql";}
}
