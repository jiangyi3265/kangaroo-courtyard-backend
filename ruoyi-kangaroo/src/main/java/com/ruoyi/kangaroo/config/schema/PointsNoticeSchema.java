package com.ruoyi.kangaroo.config.schema;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * V37（换工分类型、小程序公告、关注门店 / 我的收入 / 发素材、积分与邀请、私信交换联系方式）：
 * 缺表 / 缺列 / 缺索引时拒绝启动，免得上线后岗位列表、交付页、积分和私信才报 SQL 错误。
 */
@Component
public class PointsNoticeSchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;

    public PointsNoticeSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    {
        this.db = db;
        this.enabled = enabled;
    }

    @Override
    public Map<String, List<String>> requiredIndexes()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("kg_app_notice", Arrays.asList("PRIMARY", "uk_notice_key", "idx_notice_list"));
        m.put("kg_app_notice_seen", Arrays.asList("PRIMARY"));
        m.put("kg_online_material", Arrays.asList("PRIMARY", "idx_material_app", "idx_material_store"));
        m.put("kg_job", Arrays.asList("idx_job_kind"));
        m.put("kg_online_settlement", Arrays.asList("idx_online_settle_user"));
        m.put("kg_job_subscribe", Arrays.asList("idx_jsub_store"));
        m.put("kg_application", Arrays.asList("idx_app_volunteer_done"));
        m.put("kg_app_user", Arrays.asList("uk_app_user_invite_code", "idx_app_user_invited_by"));
        return m;
    }

    @Override
    public String migration()
    {
        return "sql/kangaroo/V37__points_notice_follow.sql";
    }

    @PostConstruct
    public void check()
    {
        if (!enabled)
        {
            return;
        }
        long n = db.count("select count(*) from information_schema.columns where table_schema = database() and ("
                + "(table_name = 'kg_job' and column_name = 'work_kind') "
                + "or (table_name = 'kg_application' and column_name in ('urgent_flag','urgent_time','volunteer_done_time','volunteer_days','volunteer_done_by')) "
                + "or (table_name = 'kg_app_user' and column_name in ('invite_code','invited_by','invited_time')) "
                + "or (table_name = 'kg_chat_member' and column_name = 'contact_ok_time'))", KgJdbc.p());
        if (n != 10)
        {
            throw new IllegalStateException("V37 columns missing; run " + migration());
        }
    }
}
