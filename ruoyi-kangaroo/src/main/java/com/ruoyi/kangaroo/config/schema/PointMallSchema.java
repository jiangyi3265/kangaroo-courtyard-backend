package com.ruoyi.kangaroo.config.schema;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PointMallSchema implements KgSchemaContributor {
    public String migration() { return "sql/kangaroo/V84__point_mall.sql"; }
    public Map<String, List<String>> requiredIndexes() {
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        indexes.put("kg_point_goods", Arrays.asList("PRIMARY", "idx_point_goods_status"));
        indexes.put("kg_point_goods_order", Arrays.asList("PRIMARY", "uk_point_goods_request", "idx_point_goods_order_user"));
        return indexes;
    }
}
