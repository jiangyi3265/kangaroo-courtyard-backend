package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 手机号的跨实例串行写入与数据库唯一性保障，缺迁移时禁止启动。 */
@Component
public class PhoneBindingSchema implements KgSchemaContributor
{
    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> indexes = new LinkedHashMap<String, List<String>>();
        indexes.put("kg_app_user", Collections.singletonList("uk_app_active_phone"));
        indexes.put("kg_auth_mutex", Collections.singletonList("PRIMARY"));
        return indexes;
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V18__phone_binding_consistency.sql（先检查活跃账号重复手机号；不会自动改动归属）";
    }
}
