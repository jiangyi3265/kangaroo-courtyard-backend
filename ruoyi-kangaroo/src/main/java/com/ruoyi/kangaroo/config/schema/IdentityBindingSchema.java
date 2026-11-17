package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IdentityBindingSchema implements KgSchemaContributor
{
    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_app_user", Collections.singletonList("uk_app_active_identity"));
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V19__identity_binding_consistency.sql（先核实重复证件摘要归属）";
    }
}
