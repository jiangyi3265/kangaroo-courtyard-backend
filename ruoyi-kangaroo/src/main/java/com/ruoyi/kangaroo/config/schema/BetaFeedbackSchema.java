package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/** V39（内测反馈：门店退款记录、私信权限、伙伴说）：缺表 / 缺列时拒绝启动，免得上线后私信和门店退款才报 SQL 错误。 */
@Component
public class BetaFeedbackSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;

    public BetaFeedbackSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("kg_stay_staff_refund", Arrays.asList("PRIMARY", "uk_ssr_idem", "idx_ssr_order", "idx_ssr_store"));
        return m;
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V39__beta_feedback.sql";
    }

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        long n = db.count("select count(*) from information_schema.columns where table_schema = database() and ("
                + "(table_name = 'kg_app_user' and column_name = 'dm_privacy') or (table_name = 'kg_post' and column_name = 'tone'))", KgJdbc.p());
        if (n != 2)
        {
            throw new IllegalStateException("V39 columns missing; run " + migration());
        }
    }
}
