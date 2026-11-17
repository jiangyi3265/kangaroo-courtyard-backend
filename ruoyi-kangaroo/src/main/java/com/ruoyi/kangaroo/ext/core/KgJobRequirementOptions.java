package com.ruoyi.kangaroo.ext.core;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DictUtils;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 发布岗位时「年龄」「性格」能选的选项（V67，2026-11-03 主人：「加一个性格，横向排列」「年龄选择年龄段，在后台管理配置」）。
 *
 * 选项存在字典里（sys_dict_data：kg_job_age 年龄段 / kg_job_personality 性格），运营在后台「袋鼠小院 → 岗位要求选项」里加、改、排序、停用，
 * 不用去「系统管理 → 字典管理」。小程序发布岗位、换工主理人完善商家岗位、后台岗位表单都读这一份。
 * 选中的文字照旧写进岗位「要求」的行首（「年龄：18-30岁」「性格：活泼开朗、善于沟通」），不加新列：
 * 老岗位手写的年龄照常显示，选项改名、停用也不影响已经发出去的岗位。
 */
@Component
public class KgJobRequirementOptions implements ExtEntityContributor
{
    public static final String DICT_AGE = "kg_job_age";
    public static final String DICT_PERSONALITY = "kg_job_personality";

    /** 性格最多选几个（小程序、后台表单都按它） */
    public static final int PERSONALITY_MAX = 3;

    /** 读字典失败时的兜底（和 V67 的种子一样）：选项读不到不能让发布页没得选 */
    static final List<String> DEFAULT_AGES = Collections.unmodifiableList(Arrays.asList("不限", "18-25岁", "18-30岁", "18-35岁", "18-40岁", "18岁以上"));
    static final List<String> DEFAULT_PERSONALITIES = Collections.unmodifiableList(Arrays.asList("活泼开朗", "细心耐心", "善于沟通", "吃苦耐劳", "有责任心", "安静内向也欢迎"));

    /** 只认这两个字典：通用增删改的表是 sys_dict_data，改、删之前都要核对这一行是不是它们的 */
    static final String TYPES_SQL = "dict_type in ('" + DICT_AGE + "', '" + DICT_PERSONALITY + "')";

    /**
     * 选项文字：1–12 个字，不能有换行、冒号、顿号、逗号 —— 选中的会拼成「性格：活泼开朗、善于沟通」写进要求，
     * 带了这些符号详情页就拆错了。
     */
    private static final Pattern BAD = Pattern.compile("[\\s：:、，,；;]");
    static final int LABEL_MAX = 12;

    @Autowired
    private KgJdbc db;

    private static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 给小程序和后台表单：{ ages: [...], personalities: [...], personalityMax: 3 }，只列启用的、按排序 */
    public Map<String, Object> options()
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ages", labels(DICT_AGE, DEFAULT_AGES));
        m.put("personalities", labels(DICT_PERSONALITY, DEFAULT_PERSONALITIES));
        m.put("personalityMax", PERSONALITY_MAX);
        return m;
    }

    /** 启用中的选项文字。全停用了就是空（运营的意思）；读库出错才退回默认那组 */
    List<String> labels(String type, List<String> fallback)
    {
        List<Map<String, Object>> rows;
        try
        {
            rows = db.list("select dict_label from sys_dict_data where dict_type = :t and status = '0' order by dict_sort, dict_code", p("t", type));
        }
        catch (RuntimeException e)
        {
            return new ArrayList<String>(fallback);
        }
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> r : rows)
        {
            String l = str(r.get("dictLabel"));
            if (!l.isEmpty() && !out.contains(l))
            {
                out.add(l);
            }
        }
        return out;
    }

    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity e = new ExtEntity("jobopt", "岗位要求选项", "sys_dict_data", "dict_code", "kangaroo:jobopt")
                .field(ExtField.of("dictType", "哪一项", "select").options(DICT_AGE + ":年龄段," + DICT_PERSONALITY + ":性格").required().createOnly().search("eq"))
                .field(ExtField.of("dictLabel", "选项文字", "text").required().max(LABEL_MAX).search("like"))
                .field(ExtField.of("dictSort", "排序（小的在前）", "int").range(0L, 9999L))
                .field(ExtField.of("status", "状态", "select").options("0:启用,1:停用").search("eq"))
                .field(ExtField.of("remark", "备注", "text").max(100).hideInList())
                .where(TYPES_SQL)
                .order("dict_type, dict_sort, dict_code")
                .hardDelete()
                .validate(this::check)
                .beforeDelete(this::mustBeOurs)
                .afterChange(id -> clearDictCache());
        return Collections.singletonList(e);
    }

    /** 保存前：只改这两个字典里的行；文字合规、同一项里不重名；字典值跟着文字走（岗位里存的就是文字） */
    void check(Map<String, Object> cols, Boolean creating)
    {
        String type;
        Long id = null;
        if (Boolean.TRUE.equals(creating))
        {
            type = str(cols.get("dict_type"));
            if (!DICT_AGE.equals(type) && !DICT_PERSONALITY.equals(type))
            {
                throw new ServiceException("只能选「年龄段」或「性格」", 422);
            }
        }
        else
        {
            id = KgJdbc.asLong(cols.get("_pk"));
            Map<String, Object> row = id == null ? null : db.one("select dict_type from sys_dict_data where dict_code = :id and " + TYPES_SQL, p("id", id));
            if (row == null)
            {
                throw new ServiceException("选项不存在或已删除", 404);
            }
            type = str(row.get("dictType"));
        }
        if (cols.containsKey("dict_label"))
        {
            String label = str(cols.get("dict_label"));
            if (label.isEmpty())
            {
                throw new ServiceException("选项文字不能为空", 422);
            }
            if (label.length() > LABEL_MAX || BAD.matcher(label).find())
            {
                throw new ServiceException("选项文字 1–" + LABEL_MAX + " 个字，不能有空格、冒号、顿号、逗号", 422);
            }
            if (db.count("select count(*) from sys_dict_data where dict_type = :t and dict_label = :l"
                    + (id == null ? "" : " and dict_code <> :id"), p("t", type, "l", label, "id", id)) > 0)
            {
                throw new ServiceException("「" + label + "」已经有了", 422);
            }
            cols.put("dict_label", label);
            cols.put("dict_value", label);
        }
        if (Boolean.TRUE.equals(creating))
        {
            cols.put("is_default", "N");
            cols.put("list_class", "default");
            // 新增时没选状态 / 排序：通用新增会把可空列写成 NULL（sys_dict_data.status 可空），
            // 小程序和岗位表单只读 status='0' 的选项，存成 NULL 就永远看不到。默认启用、排序 0
            if (str(cols.get("status")).isEmpty())
            {
                cols.put("status", "0");
            }
            if (cols.get("dict_sort") == null)
            {
                cols.put("dict_sort", 0L);
            }
        }
        else if (cols.containsKey("status") && str(cols.get("status")).isEmpty())
        {
            // 修改时把状态清空：保持原状态，不写成既不是启用也不是停用的 NULL
            cols.remove("status");
        }
    }

    /** 删除前：不是这两个字典的行一律拒绝（通用接口按主键删，不带字典类型） */
    void mustBeOurs(Long id)
    {
        if (db.count("select count(*) from sys_dict_data where dict_code = :id and " + TYPES_SQL, p("id", id)) == 0)
        {
            throw new ServiceException("选项不存在或已删除", 404);
        }
    }

    /** 改完清掉若依的字典缓存：后台岗位表单用 /system/dict/data/type 读这两个字典，不清会一直是老选项 */
    private static void clearDictCache()
    {
        try
        {
            DictUtils.removeDictCache(DICT_AGE);
            DictUtils.removeDictCache(DICT_PERSONALITY);
        }
        catch (RuntimeException e)
        {
            // 缓存清不掉不影响保存：小程序直接读库，后台表单最多晚一会儿看到新选项
        }
    }
}
