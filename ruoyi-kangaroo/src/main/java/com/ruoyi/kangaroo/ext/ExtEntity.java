package com.ruoyi.kangaroo.ext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台通用增删改查的实体登记。
 *
 * 一个实体 = 一张表 + 一组字段定义。后台 /kangaroo/ext/{key} 的列表、详情、新增、修改、删除，
 * 与前端 ExtCrud.vue 的表格、搜索、表单，都由这里的定义驱动。
 * 表名、列名只来自这里的常量；前端传来的字段名必须在 fields 里登记过才会被使用。
 */
public class ExtEntity
{
    /** 路由键，例如 room */
    public final String key;
    /** 页面标题，例如 房源 */
    public final String title;
    public final String table;
    public final String pk;
    /** 权限前缀，例如 kangaroo:room → kangaroo:room:list / query / add / edit / remove */
    public final String perm;
    public final List<ExtField> fields = new ArrayList<ExtField>();
    /** 自定义行动作（审核、退款、打款登记…），前端按 showIf 决定是否显示 */
    public final List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
    public String orderBy;
    public boolean softDelete = true;
    public boolean allowAdd = true;
    public boolean allowEdit = true;
    public boolean allowDelete = true;
    /** 列的类型与是否 NOT NULL（启动时由 ExtEntities 读表结构）：column -> "!S" / "N" / "D" 等；前缀 ! = NOT NULL，S 文本 N 数字 D 日期时间 */
    public final java.util.Map<String, String> colInfo = new java.util.HashMap<String, String>();
    /** 保存前的实体级校验（列名 -> 已转换的值；creating = 新增）。校验不过抛 ServiceException */
    public java.util.function.BiConsumer<java.util.Map<String, Object>, Boolean> validator;

    public ExtEntity validate(java.util.function.BiConsumer<java.util.Map<String, Object>, Boolean> v)
    {
        this.validator = v;
        return this;
    }

    /** 新增 / 修改 / 删除成功后的回调（参数 = 主键），给需要回写聚合值的实体用 */
    public java.util.function.Consumer<Long> afterChange;
    /** Derived display fields; must not modify database state. */
    public java.util.function.Consumer<Map<String, Object>> afterRead;

    public ExtEntity afterRead(java.util.function.Consumer<Map<String, Object>> c)
    {
        this.afterRead = c;
        return this;
    }

    public ExtEntity afterChange(java.util.function.Consumer<Long> c)
    {
        this.afterChange = c;
        return this;
    }

    /** 删除前的检查（参数 = 主键）：有业务流水挂着时抛 ServiceException 拒绝 */
    public java.util.function.Consumer<Long> beforeDelete;

    public ExtEntity beforeDelete(java.util.function.Consumer<Long> c)
    {
        this.beforeDelete = c;
        return this;
    }

    /** list / detail 查询的列清单（启动时由 ExtEntities 按登记字段算出）；"*" = 尚未解析，退回全表列 */
    public String selectCols = "*";
    /** 额外的固定查询条件（SQL 片段，常量） */
    public String baseWhere;

    public ExtEntity(String key, String title, String table, String pk, String perm)
    {
        this.key = key;
        this.title = title;
        this.table = table;
        this.pk = pk;
        this.perm = perm;
        this.orderBy = pk + " desc";
    }

    public ExtEntity field(ExtField f)
    {
        fields.add(f);
        return this;
    }

    public ExtEntity order(String orderBy)
    {
        this.orderBy = orderBy;
        return this;
    }

    public ExtEntity readOnly()
    {
        this.allowAdd = false;
        this.allowEdit = false;
        this.allowDelete = false;
        return this;
    }

    public ExtEntity noAdd()
    {
        this.allowAdd = false;
        return this;
    }

    public ExtEntity noDelete()
    {
        this.allowDelete = false;
        return this;
    }

    public ExtEntity hardDelete()
    {
        this.softDelete = false;
        return this;
    }

    public ExtEntity where(String sql)
    {
        this.baseWhere = sql;
        return this;
    }

    /**
     * 自定义动作。url 是后台接口（相对 /dev-api），method 为 put/post；
     * prompt=true 时前端先弹框要原因（字段名 reason）；showIf 形如 {"status": ["pending"]}。
     */
    public ExtEntity action(String key, String label, String method, String url, String perm,
                            String confirm, boolean prompt, Map<String, List<String>> showIf)
    {
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("key", key);
        a.put("label", label);
        a.put("method", method);
        a.put("url", url);
        a.put("perm", perm);
        a.put("confirm", confirm);
        a.put("prompt", prompt);
        a.put("showIf", showIf);
        actions.add(a);
        return this;
    }

    /** 给上一个动作的输入框换一句提示（默认"请填写原因/说明"），输入值仍以 reason 字段提交 */
    public ExtEntity promptLabel(String label)
    {
        if (!actions.isEmpty())
        {
            actions.get(actions.size() - 1).put("promptLabel", label);
        }
        return this;
    }

    /** 给指定名字的动作设输入框提示（promptLabel 只作用于最后一个动作，同一实体有多个需要填写的动作时用这个） */
    public ExtEntity promptLabel(String actionName, String label)
    {
        for (Map<String, Object> a : actions)
        {
            if (actionName.equals(a.get("key")))
            {
                a.put("promptLabel", label);
            }
        }
        return this;
    }

    public ExtField field(String name)
    {
        for (ExtField f : fields)
        {
            if (f.name.equals(name))
            {
                return f;
            }
        }
        return null;
    }

    public Map<String, Object> meta()
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("title", title);
        m.put("pk", KgJdbc.toCamel(pk));
        m.put("perm", perm);
        m.put("allowAdd", allowAdd);
        m.put("allowEdit", allowEdit);
        m.put("allowDelete", allowDelete);
        List<Map<String, Object>> fs = new ArrayList<Map<String, Object>>();
        for (ExtField f : fields)
        {
            if (!f.legacyInput) fs.add(f.meta());
        }
        m.put("fields", fs);
        m.put("actions", actions);
        return m;
    }
}
