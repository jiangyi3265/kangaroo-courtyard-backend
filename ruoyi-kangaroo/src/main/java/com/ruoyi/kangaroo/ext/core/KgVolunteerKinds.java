package com.ruoyi.kangaroo.ext.core;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 换工类型（日常换工 / 剪辑换工 / 直播换工 / 摄影换工 / 手作教学 / 其他…）。
 *
 * 类型不写死在代码里：运营在后台「系统管理 → 字典管理」编辑字典 kg_volunteer_kind（名称、排序、启用 / 停用，备注里写一句说明），
 * 小程序「换工换宿」列表的分类条、筛选页、员工端发布岗位、后台岗位表单都读这一份。
 * 岗位上存的是字典值（kg_job.work_kind），只对换工岗位有意义，其他岗位一律存空串。
 *
 * 筛选口径（分类条、筛选页）：选了哪个类型，就只看 work_kind 正好是它的换工岗位；
 * 选「其他」时，还没选类型（work_kind 为空）的换工岗位也算在「其他」里（见 includesUntyped）。
 * 之前还没选类型的老岗位按类型名称和说明里的词去模糊匹配标题和标签：「手工咖啡馆」会跑到「手作教学」下面，
 * 「其他」却只列明确选了「其他」的岗位（线上唯一的换工岗位没选类型，「其他」一直是空的）。现在不再按词猜。
 */
@Component
public class KgVolunteerKinds
{
    public static final String DICT_TYPE = "kg_volunteer_kind";

    /** 「其他」：兜底类型。筛选「其他」时，还没选类型的换工岗位也列在这里 */
    public static final String OTHER = "other";

    /**
     * 类型值的格式：小写字母、数字、下划线、横线，1–32 位（kg_job.work_kind 是 varchar(32)）。
     * 之前不查格式：运营在字典里填了超过 32 位的值，发布岗位时数据库报「数据太长」，页面上只看到系统错误
     */
    private static final Pattern VALUE = Pattern.compile("^[a-z0-9_-]{1,32}$");

    /** 类型名称缓存时长：列表每一行都要显示类型名，不能每行查一次库；运营改了字典，最多半分钟后生效 */
    private static final long CACHE_MS = 30000L;

    @Autowired
    private KgJdbc db;

    private volatile Map<String, String> labelCache;
    private volatile long labelCacheAt;

    private static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 旧字典仍可读取；展示文案统一使用新名称，字典值保持兼容。 */
    private static String labelText(Object v)
    {
        return str(v).replace("义工", "换工");
    }

    /** 是不是合法的类型值：小写字母、数字、下划线、横线，1–32 位 */
    public static boolean validValue(String value)
    {
        return value != null && VALUE.matcher(value).matches();
    }

    /** 按这些类型筛选时，要不要把还没选类型（work_kind 为空）的换工岗位也算进来：选了「其他」才算 */
    public static boolean includesUntyped(Collection<String> kinds)
    {
        return kinds != null && kinds.contains(OTHER);
    }

    /** 启用中的类型，按字典排序：[{value, label, desc}]，desc 是字典备注里的一句说明（如「前台、接待、打扫、陪伴客人」） */
    public List<Map<String, Object>> enabled()
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select dict_value, dict_label, remark from sys_dict_data where dict_type = :t and status = '0' "
                + "order by dict_sort, dict_code", p("t", DICT_TYPE)))
        {
            String value = str(r.get("dictValue"));
            // 之前只跳过空值；现在格式不对的字典值（太长、带中文或大写字母）也不列出来 —— 选了也存不进岗位
            if (!validValue(value))
            {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("value", value);
            m.put("label", labelText(r.get("dictLabel")));
            m.put("desc", labelText(r.get("remark")));
            out.add(m);
        }
        return out;
    }

    /** 类型名称。停用的类型也照样给名字（老岗位还挂着它）；没选类型或不认识的值返回空串 */
    public String label(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return "";
        }
        String l = labels().get(value.trim());
        return l == null ? "" : l;
    }

    private Map<String, String> labels()
    {
        Map<String, String> cached = labelCache;
        long now = System.currentTimeMillis();
        if (cached != null && now - labelCacheAt < CACHE_MS)
        {
            return cached;
        }
        Map<String, String> m = new HashMap<String, String>();
        try
        {
            for (Map<String, Object> r : db.list("select dict_value, dict_label from sys_dict_data where dict_type = :t", p("t", DICT_TYPE)))
            {
                m.put(str(r.get("dictValue")), labelText(r.get("dictLabel")));
            }
        }
        catch (RuntimeException e)
        {
            // 字典读不到不能拖垮岗位列表，只是这一次不显示类型名
            return cached == null ? Collections.<String, String>emptyMap() : cached;
        }
        labelCache = m;
        labelCacheAt = now;
        return m;
    }

    /**
     * 保存岗位前把换工类型归一：
     *  · 不是换工岗位：一律存空串（后台把岗位从换工改成兼职时，表单里残留的类型不能跟过去）；
     *  · 没选：空串（不选类型也能发布，筛选时这类岗位算在「其他」里）；
     *  · 选了：格式要对（见 validValue，否则 422「换工类型不合法」），而且必须是启用中的字典值。
     *    修改岗位时库里原来的值原样放行 —— 类型被运营停用后，老岗位改别的字段不能被它卡住。
     *
     * @param keep 修改时库里现在的值；新增传 null
     */
    public String normalize(String jobType, String workKind, String keep)
    {
        String v = workKind == null ? "" : workKind.trim();
        if (!"volunteer".equals(jobType) || v.isEmpty())
        {
            return "";
        }
        if (v.equals(keep))
        {
            return v;
        }
        // 之前直接拿去和字典比：字典里有超过 32 位的值时会一路存到数据库，报「数据太长」变成系统错误；现在先看格式
        if (!validValue(v))
        {
            throw new ServiceException("换工类型不合法", 422);
        }
        for (Map<String, Object> k : enabled())
        {
            if (v.equals(k.get("value")))
            {
                return v;
            }
        }
        throw new ServiceException("换工类型不存在或已停用，请重新选择", 422);
    }
}
