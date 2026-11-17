package com.ruoyi.kangaroo.ext;

/**
 * like 条件里用户输入的转义（2026-10-16 审计）。
 * 之前关键词原样拼进 '%' + kw + '%'：搜「%」「_」会匹配所有岗位，搜「100%」也不是按字面找。
 * 这里把 \ → \\、% → \%、_ → \_，交给 MySQL 默认的转义符反斜杠（like 不写 escape 子句时就是 \），
 * 参数一律走绑定，不拼进 SQL 字面量。
 */
public final class KgLike
{
    private KgLike()
    {
    }

    /** 转义 like 通配符，null 原样返回 */
    public static String escape(String s)
    {
        if (s == null)
        {
            return null;
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++)
        {
            char ch = s.charAt(i);
            if (ch == '\\' || ch == '%' || ch == '_')
            {
                b.append('\\');
            }
            b.append(ch);
        }
        return b.toString();
    }

    /** 包含匹配：%转义后的值% */
    public static String contains(String s)
    {
        return "%" + escape(s == null ? "" : s) + "%";
    }

    /** 以它结尾：%转义后的值 */
    public static String endsWith(String s)
    {
        return "%" + escape(s == null ? "" : s);
    }
}
