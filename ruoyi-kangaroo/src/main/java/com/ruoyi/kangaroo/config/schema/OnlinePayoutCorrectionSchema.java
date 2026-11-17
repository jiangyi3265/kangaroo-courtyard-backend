package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 缺少凭证审计表时拒绝启动，避免上线后结算页才报缺表或更正记录无法保存。 */
@Component
public class OnlinePayoutCorrectionSchema implements KgSchemaContributor
{
    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        return Collections.singletonMap("kg_online_payout_correction", Arrays.asList("PRIMARY", "idx_online_correction_app"));
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V32__online_payout_corrections.sql";
    }
}
