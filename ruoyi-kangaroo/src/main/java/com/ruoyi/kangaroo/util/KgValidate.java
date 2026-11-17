package com.ruoyi.kangaroo.util;

import com.ruoyi.common.exception.ServiceException;

/**
 * 袋鼠业务的轻量入参校验工具。
 *
 * 为什么需要：后台表单里的文本框即使加了 maxlength，也只是"好心提示"——
 * 接口可以被直接调用（curl / 粘贴超长文本绕过前端），超长值会一路撞到
 * MySQL 的 varchar 列上，MyBatis 抛出的 DataIntegrityViolationException
 * 带着完整 SQL 与服务器 jar 的绝对路径，最后原样弹到运营眼前：
 * 既泄露了部署信息，运营也完全不知道该改哪个字段、改到多少字。
 * 所以长度必须在 Service 层先拦一道，报出"照着改就能过"的中文提示。
 *
 * 只放通用的小方法，业务规则仍留在各自的 ServiceImpl 里。
 */
public class KgValidate
{
    private KgValidate()
    {
    }

    /**
     * 校验文本长度不超过数据库列宽。
     *
     * 为什么用 codePointCount 而不是 length()：MySQL 的 varchar(n) 按字符计数，
     * 一个 emoji（U+1F600 这类补充平面字符）在 MySQL 眼里是 1 个字符，
     * 而 Java 的 String.length() 按 UTF-16 码元算是 2。
     * 用 length() 会把刚好合法的输入误判为超长，用 codePointCount 才和列宽对齐。
     *
     * null 与空串直接放行：必填与否是各自业务的判断，这里只管长度。
     *
     * @param value 待校验文本
     * @param max   数据库列允许的最大字符数
     * @param label 运营看得懂的字段名，如"门店简介"
     */
    public static void maxLength(String value, int max, String label)
    {
        if (value == null || value.isEmpty())
        {
            return;
        }
        int length = value.codePointCount(0, value.length());
        if (length > max)
        {
            // 把上限和当前长度都报出来，运营不用二分猜要删多少字
            throw new ServiceException(label + "最多 " + max + " 字，当前 " + length + " 字");
        }
    }
}
