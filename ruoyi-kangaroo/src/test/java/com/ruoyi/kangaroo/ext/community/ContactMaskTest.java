package com.ruoyi.kangaroo.ext.community;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 联系方式识别 / 遮挡（2026-10-11 复核后重写）：常见绕过写法要认得出，价格、普通英文词不能误遮。 */
class ContactMaskTest {
    static final String P = ContactMask.PHONE_MASK, W = ContactMask.WECHAT_MASK, Q = ContactMask.QQ_MASK;

    @Test void phoneWritingTricksAreCaught() {
        assertEquals("电话 " + P, ContactMask.mask("电话 １３８１２３４５６７８"));
        assertEquals(P, ContactMask.mask("+8613812345678"));
        assertEquals(P, ContactMask.mask("+86 138 1234 5678"));
        assertEquals(P, ContactMask.mask("008613812345678"));
        assertEquals(P, ContactMask.mask("1 3812345678"));
        assertEquals(P, ContactMask.mask("138.1234.5678"));
        assertEquals(P, ContactMask.mask("138  1234  5678"));
        assertEquals(P, ContactMask.mask("138　1234　5678"));
        assertEquals("号码" + P + "，晚上打", ContactMask.mask("号码138​1234​5678，晚上打"));
        assertEquals("微信" + P, ContactMask.mask("微信13812345678"));
        assertEquals("先打 " + P + " 再打 " + P, ContactMask.mask("先打 13812345678 再打 15900001111"));
    }

    @Test void wechatAndQqTricksAreCaught() {
        assertEquals("我的" + W, ContactMask.mask("我的微信号是：abc12345"));
        assertEquals("我的" + W, ContactMask.mask("我的微信号是 hello2026"));
        assertEquals(W + "，备注袋鼠", ContactMask.mask("加V：abc12345，备注袋鼠"));
        assertEquals(W, ContactMask.mask("wx：abcdefg"));
        assertEquals(W, ContactMask.mask("ＶＸ abcdef1"));
        assertEquals(Q, ContactMask.mask("QQ号是：12345678"));
        assertEquals(Q, ContactMask.mask("qq 12345678"));
        assertEquals(Q, ContactMask.mask("12345678@qq.com"));
        assertEquals(Q, ContactMask.mask("12345678＠QQ.com"));
        assertEquals("进" + Q, ContactMask.mask("进QQ群 123456789"));
    }

    @Test void ordinaryContentIsNotMasked() {
        for (String plain : new String[] { "价格 1500 1800 200", "可以用微信 alipay", "我在微信 Moments 发了", "房费 1380 元",
                "2026-10-11 13:45 到", "订单 S2610100001", "电话找前台", "11月13号到", "明天 9 点见", "123813812345678",
                "身份证后四位 1234", "微信转账可以吗", "2026101012345", "" }) {
            assertEquals(plain, ContactMask.mask(plain), plain);
            assertFalse(ContactMask.contains(plain), plain);
        }
        assertNull(ContactMask.mask(null));
        assertFalse(ContactMask.contains(null));
    }

    /** 10-14 回归：简历经历里这些写法之前能绕过遮挡 */
    @Test void fullWidthDashSlashParenthesesAndWechatIdAreCaught() {
        assertEquals("全角横线" + P, ContactMask.mask("全角横线１３８－１２３４－５６７８"));
        assertEquals(P, ContactMask.mask("138－1234－5678"));
        assertEquals("括号(" + P, ContactMask.mask("括号(138)1234-5678"));
        assertEquals("（" + P, ContactMask.mask("（138）1234-5678"));
        assertEquals(P, ContactMask.mask("138/1234/5678"));
        assertEquals(W, ContactMask.mask("wechat id: kg2026x"));
        assertEquals(W, ContactMask.mask("微信 ID：kg_test01"));
        for (String plain : new String[] { "2026/10/14 到", "价格（1500）元", "第 1/2 页", "(10)人以内", "微信 ID 卡" }) {
            assertEquals(plain, ContactMask.mask(plain), plain);
        }
    }

    /** 10-14 回归：号码前后紧挨着别的数字时，之前整串连起来超过 11 位就放过了 */
    @Test void phonesNextToOtherNumbersAreCaught() {
        assertEquals(P + " " + P, ContactMask.mask("+86 138.1234.5678 １３８１２３４５６７８"));
        assertEquals(P + " 2026年", ContactMask.mask("138-1234-5678 2026年"));
        assertEquals("电话 " + P + " 9点后联系", ContactMask.mask("电话 138 1234 5678 9点后联系"));
        assertEquals(P + " 3天", ContactMask.mask("１３８１２３４５６７８ 3天"));
        assertEquals("10/14 " + P, ContactMask.mask("10/14 138-1234-5678"));
        assertEquals(P + " / " + P, ContactMask.mask("138 1234 5678 / 139 8765 4321"));
        assertEquals("(" + P + " 2人", ContactMask.mask("(138)1234-5678 2人"));
        assertEquals("价格1500 " + P, ContactMask.mask("价格1500 13812345678"));
        for (String plain : new String[] { "价格 1500 1800 200 300", "订单 S2610100001 共 2 晚", "2026 10 14 13 45" }) {
            assertEquals(plain, ContactMask.mask(plain), plain);
        }
    }

    @Test void containsAgreesWithMask() {
        assertTrue(ContactMask.contains("昵称QQ123456789"));
        assertTrue(ContactMask.contains("找我 １３８ １２３４ ５６７８"));
        assertFalse(ContactMask.contains("袋鼠小院·大理"));
    }
}
