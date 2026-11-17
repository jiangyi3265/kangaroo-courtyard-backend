package com.ruoyi.kangaroo.domain;

import com.ruoyi.common.exception.ServiceException;
import java.util.StringJoiner;

/** 奖金和提成为单独的待遇规则，不加入固定底薪金额。 */
public final class JobCompensation
{
    public static String rule(String value, String label)
    {
        if (value == null || value.trim().isEmpty()) return null;
        String text = value.trim();
        if (text.length() > 120) throw new ServiceException(label + "规则不能超过 120 字");
        return text;
    }

    public static String selectedRule(Object selected, Object value, String label)
    {
        // 未携带开关的旧请求，按说明内容识别；显式取消选择必须清空。
        if (Boolean.FALSE.equals(selected)) return null;
        String text = rule(value == null ? null : String.valueOf(value), label);
        if (Boolean.TRUE.equals(selected) && text == null) throw new ServiceException("请填写" + label + "规则");
        return text;
    }

    public static String summary(String base, String bonus, String commission)
    {
        StringJoiner parts = new StringJoiner(" + ");
        if (base != null && !base.isEmpty()) parts.add(base);
        if (bonus != null && !bonus.isEmpty()) parts.add("奖金");
        if (commission != null && !commission.isEmpty()) parts.add("提成");
        return parts.toString();
    }

    public static String details(String base, String bonus, String commission)
    {
        StringJoiner parts = new StringJoiner("；");
        if (base != null && !base.isEmpty()) parts.add(base);
        if (bonus != null && !bonus.isEmpty()) parts.add("奖金：" + bonus);
        if (commission != null && !commission.isEmpty()) parts.add("提成：" + commission);
        return parts.toString();
    }

    private JobCompensation() { }
}
