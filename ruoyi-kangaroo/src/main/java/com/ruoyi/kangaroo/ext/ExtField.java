package com.ruoyi.kangaroo.ext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用实体的一个字段。
 *
 * type：text | textarea | int | money（分，前端按元显示与录入）| date | datetime | select（dict 或 options）
 *       | image（单图 URL）| images（逗号分隔多图）| switch（'0'/'1'）
 * search：none | eq | like | range（仅日期）
 */
public class ExtField
{
    public final String name;
    public final String column;
    public final String label;
    public final String type;
    public String dict;
    /** 固定选项 "value:label,value:label"（没有系统字典时用） */
    public String options;
    public boolean required;
    public int maxLen = 255;
    /** 编辑器展示的默认内容；不会自动写入数据库。 */
    private Object editorDefault;
    public boolean inList = true;
    public boolean editable = true;
    public boolean createOnly;
    /** Retained as an accepted input for older clients, omitted from new editor metadata. */
    public boolean legacyInput;

    public ExtField legacyInput() { this.legacyInput = true; return this; }

    public ExtField createOnly() { this.createOnly = true; return this; }
    public String search = "none";
    public Long min;
    public Long max;
    /** 引用校验：该字段的值必须是 refTable.refColumn 里真实存在的一行（通用新增 / 修改时检查） */
    public String refTable;
    public String refColumn;

    public ExtField(String name, String label, String type)
    {
        this.name = name;
        this.column = KgJdbc.toSnake(name);
        this.label = label;
        this.type = type;
    }

    public static ExtField of(String name, String label, String type)
    {
        return new ExtField(name, label, type);
    }

    public ExtField dict(String d)
    {
        this.dict = d;
        return this;
    }

    public ExtField options(String o)
    {
        this.options = o;
        return this;
    }

    public ExtField required()
    {
        this.required = true;
        return this;
    }

    public ExtField max(int len)
    {
        this.maxLen = len;
        return this;
    }

    public ExtField editorDefault(Object value)
    {
        this.editorDefault = value;
        return this;
    }

    public ExtField range(Long min, Long max)
    {
        this.min = min;
        this.max = max;
        return this;
    }

    public ExtField hideInList()
    {
        this.inList = false;
        return this;
    }

    public ExtField ref(String table, String column)
    {
        this.refTable = table;
        this.refColumn = column;
        return this;
    }

    public ExtField readOnly()
    {
        this.editable = false;
        return this;
    }

    public ExtField search(String s)
    {
        this.search = s;
        return this;
    }

    public Map<String, Object> meta()
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("label", label);
        m.put("type", type);
        m.put("dict", dict);
        m.put("options", options);
        m.put("required", required);
        m.put("maxLen", maxLen);
        // 默认值可以是 Supplier：每次取 meta 时现算（如预订须知的默认正文跟着角色阶段变，不能在启动登记时定死）
        if (editorDefault != null) m.put("editorDefault", editorDefault instanceof java.util.function.Supplier ? ((java.util.function.Supplier<?>) editorDefault).get() : editorDefault);
        m.put("inList", inList);
        m.put("editable", editable);
        m.put("createOnly", createOnly);
        m.put("search", search);
        m.put("min", min);
        m.put("max", max);
        return m;
    }
}
