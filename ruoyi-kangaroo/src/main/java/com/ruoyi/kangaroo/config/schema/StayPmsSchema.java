package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** 旅居订单台（排房）与找搭子性别要求（V36）：缺表 / 缺列时拒绝启动，免得上线后订单台和社区才报 SQL 错误。 */
@Component
public class StayPmsSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;

    public StayPmsSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("kg_stay_unit", Arrays.asList("PRIMARY", "idx_unit_type"));
        m.put("kg_stay_assign", Arrays.asList("PRIMARY", "idx_assign_unit", "idx_assign_order"));
        return m;
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V36__stay_pms_buddy_gender.sql";
    }

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        long n = db.count("select count(*) from information_schema.columns where table_schema = database() and ((table_name = 'kg_stay_order' "
                + "and column_name in ('staff_note','checked_out_time','checked_out_by')) or (table_name = 'kg_post' and column_name = 'gender_limit'))", KgJdbc.p());
        if (n != 4)
        {
            throw new IllegalStateException("V36 columns missing; run " + migration());
        }
    }
}
