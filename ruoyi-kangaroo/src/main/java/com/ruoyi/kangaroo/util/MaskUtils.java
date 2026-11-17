package com.ruoyi.kangaroo.util;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.ext.community.ContactMask;

/** 敏感信息脱敏 */
public final class MaskUtils
{
    /** 手机号脱敏：18612348888 -> 186****8888 */
    public static String phone(String phone)
    {
        if (StringUtils.isEmpty(phone) || phone.length() < 7)
        {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 姓名脱敏：张三丰 -> 张*丰，李四 -> 李* */
    public static String name(String name)
    {
        if (StringUtils.isEmpty(name))
        {
            return name;
        }
        if (name.length() == 1)
        {
            return name;
        }
        if (name.length() == 2)
        {
            return name.charAt(0) + "*";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name.charAt(0));
        for (int i = 1; i < name.length() - 1; i++)
        {
            sb.append('*');
        }
        sb.append(name.charAt(name.length() - 1));
        return sb.toString();
    }

    /**
     * 自由文字里的联系方式脱敏（简历经历描述、技能描述这类要给门店看、要转发出去的文字）：
     * 手机号、微信号、QQ 号按私信 / 社区同一套规则（ContactMask：中间加横线 / 空格、全角数字、「微信 xxx」都认得出）
     * 换成「[手机号已隐藏]」之类的占位；剩下 7 位以上的连续数字（座机、证件号）再打码。空值返回空串
     */
    public static String contacts(String text)
    {
        if (StringUtils.isEmpty(text))
        {
            return "";
        }
        return ContactMask.mask(text).replaceAll("\\d{7,}", "***");
    }

    private MaskUtils()
    {
    }
}
