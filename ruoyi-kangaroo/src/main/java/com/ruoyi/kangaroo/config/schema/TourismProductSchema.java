package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class TourismProductSchema implements KgSchemaContributor {
    public String migration() { return "sql/kangaroo/V85__tourism_product.sql"; }
    public Map<String, List<String>> requiredIndexes() { return Collections.singletonMap("kg_activity", Arrays.asList("idx_activity_product_kind")); }
}
