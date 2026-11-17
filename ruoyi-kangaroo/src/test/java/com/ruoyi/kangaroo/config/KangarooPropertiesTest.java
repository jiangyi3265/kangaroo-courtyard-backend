package com.ruoyi.kangaroo.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 本地联调模式（kangaroo.wx.mock-enabled）的那道守卫。
 *
 * 为什么要专门测它：这道守卫替换掉的上一道是"active profile 等于 prod/production 就抛"，
 * 而本项目的 profile 永远是 druid（若依靠它加载数据源），那道防线一次也不会触发 ——
 * 它在文档里被当成有效防线写了很久，却从来没有测试碰过。
 *
 * 新判据是"开了 mock 就必须连着本机数据库"。这里逐个断言它的判定，
 * 确保这一次不是又换了一道"看起来有、实际没有"的防线。
 *
 * 说明：本机 MySQL 只允许 localhost 连接，做不出"可连通但非本机"的启动场景，
 * 所以"@PostConstruct 真的会让应用启动失败"这一半没有做活体验证，
 * 只测判定本身；这一点在验收报告里如实标注。
 */
class KangarooPropertiesTest
{
    @Test
    @DisplayName("本机地址一律放行")
    void localHostsPass()
    {
        assertTrue(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://localhost:3306/dkxy?useUnicode=true"));
        assertTrue(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://127.0.0.1:3306/dkxy"));
        assertTrue(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://LOCALHOST:3306/dkxy"));
        assertTrue(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://[::1]:3306/dkxy"));
        // 没写端口
        assertTrue(KangarooProperties.isLocalJdbcUrl("jdbc:mysql://localhost/dkxy"));
    }

    @Test
    @DisplayName("非本机地址一律拦下 —— 这是这道守卫存在的唯一理由")
    void remoteHostsBlocked()
    {
        assertFalse(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://db.example.com:3306/dkxy"));
        assertFalse(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://10.0.0.5:3306/dkxy"));
        assertFalse(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://192.168.0.144:3306/dkxy"));
        assertFalse(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://rds-prod.aliyuncs.com:3306/dkxy?useSSL=true"));
    }

    @Test
    @DisplayName("容易骗过粗糙实现的几种写法")
    void trickyFormsBlocked()
    {
        // 主机名里包含 localhost，但并不是本机
        assertFalse(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://localhost.evil.com:3306/dkxy"));
        assertFalse(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://notlocalhost:3306/dkxy"));
        // 连接串里带凭证，主机名在最后一个 @ 之后
        assertFalse(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://user:pass@db.example.com:3306/dkxy"));
        assertTrue(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://user:pass@localhost:3306/dkxy"));
        // 多主机（failover）写法，第二个不是本机就不能放行
        assertFalse(KangarooProperties.isLocalJdbcUrl(
                "jdbc:mysql://db1.example.com,db2.example.com:3306/dkxy"));
    }

    @Test
    @DisplayName("判不出来就当成不是本机（失败关闭）")
    void unknownIsTreatedAsRemote()
    {
        assertFalse(KangarooProperties.isLocalJdbcUrl(null));
        assertFalse(KangarooProperties.isLocalJdbcUrl(""));
        assertFalse(KangarooProperties.isLocalJdbcUrl("   "));
        // 没有 // 的畸形串
        assertFalse(KangarooProperties.isLocalJdbcUrl("jdbc:mysql:localhost:3306/dkxy"));
        // 只有协议头
        assertFalse(KangarooProperties.isLocalJdbcUrl("jdbc:mysql://"));
    }
}
