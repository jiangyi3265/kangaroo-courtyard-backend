package com.ruoyi.kangaroo.ext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;

/**
 * 全部通用实体的登记处。各模块实现 ExtEntityContributor 把自己的实体交进来。
 * Bean 名 extEntities：控制器上的 @PreAuthorize 用它拼权限标识。
 */
@Component("extEntities")
public class ExtEntities
{
    private final Map<String, ExtEntity> byKey = new LinkedHashMap<String, ExtEntity>();

    @Autowired(required = false)
    private List<ExtEntityContributor> contributors;

    @Autowired
    private KgJdbc db;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExtEntities.class);

    @javax.annotation.PostConstruct
    public void init()
    {
        if (contributors == null)
        {
            return;
        }
        for (ExtEntityContributor c : contributors)
        {
            for (ExtEntity e : c.entities())
            {
                if (byKey.put(e.key, e) != null)
                {
                    throw new IllegalStateException("通用实体键重复：" + e.key);
                }
            }
        }
        resolveColumns();
    }

    /**
     * 按真实表结构校正登记（2026-09-28 复核发现）：
     *  · 实体默认软删除，会在列表 / 详情 SQL 上拼 del_flag = '0'；表没有这一列时每次请求都是 500
     *    （客服工单、体验官申请、技能证书、新机会订阅、责任书签署、到店信息、私信群聊 7 页曾因此整页打不开）。
     *    这里发现表没有 del_flag 就自动按硬删除处理，以后新增实体不会再犯同一个错；
     *  · 列表 / 详情只查登记过的列（主键、登记字段、动作 URL 与显示条件用到的列、创建 / 更新时间），
     *    不再 select *，避免把核销码、私信摘要之类没登记的列带给只有「查看」权限的人。
     */
    private void resolveColumns()
    {
        Map<String, java.util.Set<String>> cols = new LinkedHashMap<String, java.util.Set<String>>();
        Map<String, String> colKinds = new java.util.HashMap<String, String>();
        try
        {
            for (Map<String, Object> r : db.list("select table_name as tn, column_name as cn, is_nullable as nl, data_type as dt from information_schema.columns where table_schema = database()", null))
            {
                String t = String.valueOf(r.get("tn")).toLowerCase();
                java.util.Set<String> set = cols.get(t);
                if (set == null)
                {
                    set = new java.util.LinkedHashSet<String>();
                    cols.put(t, set);
                }
                set.add(String.valueOf(r.get("cn")).toLowerCase());
                String dt = String.valueOf(r.get("dt")).toLowerCase();
                String kind = dt.contains("char") || dt.contains("text") || dt.equals("enum") || dt.equals("json") ? "S"
                        : (dt.equals("date") || dt.equals("datetime") || dt.equals("timestamp") || dt.equals("time") ? "D" : "N");
                colKinds.put(t + "." + String.valueOf(r.get("cn")).toLowerCase(), ("NO".equals(String.valueOf(r.get("nl"))) ? "!" : "") + kind);
            }
        }
        catch (Exception ex)
        {
            log.warn("读取表结构失败，通用实体沿用登记时的软删除设置与 select *：{}", ex.getMessage());
            return;
        }
        for (ExtEntity e : byKey.values())
        {
            java.util.Set<String> have = cols.get(e.table.toLowerCase());
            if (have == null)
            {
                log.error("通用实体 {} 登记的表 {} 不存在", e.key, e.table);
                continue;
            }
            if (e.softDelete && !have.contains("del_flag"))
            {
                log.warn("通用实体 {}（表 {}）没有 del_flag 列，按硬删除处理", e.key, e.table);
                e.softDelete = false;
            }
            java.util.Set<String> want = new java.util.LinkedHashSet<String>();
            want.add(e.pk.toLowerCase());
            for (ExtField f : e.fields)
            {
                want.add(f.column.toLowerCase());
            }
            want.add("create_time");
            want.add("update_time");
            if (e.softDelete)
            {
                want.add("del_flag");
            }
            for (Map<String, Object> a : e.actions)
            {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([a-zA-Z]+)\\}").matcher(String.valueOf(a.get("url")));
                while (m.find())
                {
                    want.add(KgJdbc.toSnake(m.group(1)).toLowerCase());
                }
                Object showIf = a.get("showIf");
                if (showIf instanceof Map)
                {
                    for (Object k : ((Map<?, ?>) showIf).keySet())
                    {
                        want.add(KgJdbc.toSnake(String.valueOf(k)).toLowerCase());
                    }
                }
            }
            for (String c : have)
            {
                String k = colKinds.get(e.table.toLowerCase() + "." + c);
                if (k != null)
                {
                    e.colInfo.put(c, k);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String c : want)
            {
                if (have.contains(c))
                {
                    sb.append(sb.length() == 0 ? "" : ", ").append('`').append(c).append('`');
                }
            }
            if (sb.length() > 0)
            {
                e.selectCols = sb.toString();
            }
        }
    }

    public ExtEntity require(String key)
    {
        ExtEntity e = byKey.get(key);
        if (e == null)
        {
            throw new ServiceException("未知的数据类型：" + key);
        }
        return e;
    }

    /** 给 @PreAuthorize 用：未登记的实体返回一个谁都没有的权限标识，等于拒绝 */
    public String perm(String key, String action)
    {
        ExtEntity e = byKey.get(key);
        return e == null ? "kangaroo:__unknown__:" + action : e.perm + ":" + action;
    }

    public Collection<ExtEntity> all()
    {
        return byKey.values();
    }
}
