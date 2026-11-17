package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.domain.KgMessage;

/**
 * 消息中心「私信」= 会话列表（2026-10-15）：10-15 验收版曾在那一行正文开头写「（N 条新消息）」，
 * 数不准、读掉后还挂着，已经不再写；验收库里留下的旧行，列表里不管已读未读都不显示这个前缀。
 */
class AppMessageListContentTest
{
    private static KgMessage msg(String bizType, String readFlag, String content)
    {
        KgMessage m = new KgMessage();
        m.setBizType(bizType);
        m.setReadFlag(readFlag);
        m.setContent(content);
        return m;
    }

    @Test
    void unreadChatRowAlsoDropsTheOldCount()
    {
        // 「全部已读」只在本地把行标成已读、不重新拉列表：未读时就不显示，读掉后才不会还挂着旧前缀
        assertEquals("在吗", AppMessageController.listContent(msg("chat_conv", "0", "（3 条新消息）在吗")));
    }

    @Test
    void readChatRowDropsTheCountButKeepsTheText()
    {
        assertEquals("在吗", AppMessageController.listContent(msg("chat_conv", "1", "（3 条新消息）在吗")));
        assertEquals("阿川（接待）：作品收到了", AppMessageController.listContent(msg("chat_conv", "1", "（12 条新消息）阿川（接待）：作品收到了")));
    }

    @Test
    void otherTextIsNeverTouched()
    {
        // 只认开头那一段固定格式：用户自己写的括号、中间出现的同样文字、别的业务消息都原样显示
        assertEquals("我：（3 条新消息）", AppMessageController.listContent(msg("chat_conv", "1", "我：（3 条新消息）")));
        assertEquals("（备注）明天到", AppMessageController.listContent(msg("chat_conv", "1", "（备注）明天到")));
        assertEquals("（3 条新消息）系统通知", AppMessageController.listContent(msg("application", "1", "（3 条新消息）系统通知")));
        assertNull(AppMessageController.listContent(msg("chat_conv", "1", null)));
    }

    @Test
    void multiLineChatPreviewBecomesOneLine()
    {
        // 2026-10-16 主人截图：接待发的多行消息在消息列表里换行显示，字压到上下两行
        assertEquals("果酱（接待）：1️⃣标题（从下面任选一个）： 平潭以及福州性价比很高的海鲜 平潭新鲜鲜活的一家海鲜餐厅",
                AppMessageController.listContent(msg("chat_conv", "0", "果酱（接待）：1️⃣标题（从下面任选一个）：\n平潭以及福州性价比很高的海鲜\r\n\n平潭新鲜鲜活的一家海鲜餐厅\n")));
        assertEquals("在吗 明天到", AppMessageController.listContent(msg("chat_conv", "1", "（3 条新消息）在吗\n\t明天到")));
        assertEquals("在吗 明天到", AppMessageController.listContent(msg("chat_conv", "1", "在吗　　明天到")));
        // 别的业务消息（系统通知等）可能有意分行，原样下发
        assertEquals("第一行\n第二行", AppMessageController.listContent(msg("application", "1", "第一行\n第二行")));
    }
}
