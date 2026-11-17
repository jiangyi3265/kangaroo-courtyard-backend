package com.ruoyi.kangaroo.ext.user;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.ruoyi.kangaroo.ext.KgLike;

/**
 * 岗位城市匹配的唯一口径（2026-10-16 审计）：岗位列表、全部筛选 / 订阅推送、首页推荐、找机会助手、城市主页、
 * 「有岗位的城市」下拉的数量全都用它，保证下拉里写几个、点进去就是几个。
 *
 * 规则：选中的城市（已归一成业务城市名）连同字典里只属于它的全称 / 搜索别名（KgCityService.jobCityTerms，
 * 平潭 → 平潭、平潭县、平潭岛…）组成一组写法；岗位城市「等于其中一个，或以其中一个结尾」就算这个城市。
 *   · 以它结尾：「云南大理」算大理、「福建平潭」算平潭、「福建平潭岛」算平潭；
 *   · 之前是包含匹配 like %城市%，「五大连池」会算进大连、「中山区」算进中山、「大同区」算进大同；
 *   · 一个字的写法只认完全相等（「京」不能带出北京、南京）。
 * 岗位城市先去掉首尾空白再比（trim），和下拉统计时的口径一致；写法里的 % _ \ 按字面匹配（KgLike）。
 */
public final class KgJobCity
{
    private KgJobCity()
    {
    }

    /** 选中城市的全部写法；没有城市服务（单测）或字典里没有别名时只有它自己 */
    public static List<String> terms(KgCityService cities, String city)
    {
        String c = city == null ? "" : city.trim();
        if (c.isEmpty())
        {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        out.add(c);
        List<String> more = cities == null ? null : cities.jobCityTerms(c);
        if (more != null)
        {
            for (String t : more)
            {
                String s = t == null ? "" : t.trim();
                if (!s.isEmpty() && !out.contains(s))
                {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 两个字以上按「以它结尾」比，一个字只认相等 */
    static boolean suffix(String term)
    {
        return term.length() >= 2;
    }

    /** Java 里的同一口径（「有岗位的城市」下拉按它数岗位） */
    public static boolean matches(String raw, List<String> terms)
    {
        String r = raw == null ? "" : raw.trim();
        if (r.isEmpty())
        {
            return false;
        }
        for (String t : terms)
        {
            if (suffix(t) ? r.endsWith(t) : r.equals(t))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 命名参数 SQL 的条件片段：(trim(列) like :前缀0 or trim(列) = :前缀1 …)，参数放进 ps。
     * 列名只能是代码里的常量；写法为空时返回恒假条件（不会把条件放开成全部城市）
     */
    public static String sql(String column, String prefix, List<String> terms, Map<String, Object> ps)
    {
        if (terms == null || terms.isEmpty())
        {
            return "(1 = 0)";
        }
        StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < terms.size(); i++)
        {
            String t = terms.get(i);
            String name = prefix + i;
            b.append(i == 0 ? "" : " or ").append("trim(").append(column).append(")");
            if (suffix(t))
            {
                b.append(" like :").append(name);
                ps.put(name, KgLike.endsWith(t));
            }
            else
            {
                b.append(" = :").append(name);
                ps.put(name, t);
            }
        }
        return b.append(")").toString();
    }

    /** 给 MyBatis（KgJobMapper.selectAppJobList）的参数：cityLikes 是转义好的 %写法，cityExacts 是一个字的写法 */
    public static void putMapperParams(Map<String, Object> params, List<String> terms)
    {
        List<String> likes = new ArrayList<String>();
        List<String> exacts = new ArrayList<String>();
        for (String t : terms)
        {
            if (suffix(t))
            {
                likes.add(KgLike.endsWith(t));
            }
            else
            {
                exacts.add(t);
            }
        }
        params.put("cityLikes", likes);
        params.put("cityExacts", exacts);
    }
}
