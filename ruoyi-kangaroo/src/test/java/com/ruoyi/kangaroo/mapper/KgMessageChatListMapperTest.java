package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.kangaroo.domain.KgMessage;

/**
 * 解析真实的 KgMessageMapper.xml（不连库）：消息中心「私信」= 会话列表（2026-10-15）。
 * 同一会话历史上多出来的旧行只在 SELECT 里隐藏，列表按最近时间排；两条 UPDATE 不能带同表子查询（MySQL 1093），
 * 接待权限规则 currentChatReception 原样保留。SQL 在 MySQL 上的实际效果在验收库上核对过（见发布说明）。
 */
class KgMessageChatListMapperTest
{
    static final String NS = "com.ruoyi.kangaroo.mapper.KgMessageMapper.";
    static final String HIDE_OLDER = "not exists( select 1 from kg_message newer where newer.biz_type='chat_conv' and newer.biz_id=kg_message.biz_id "
            + "and newer.recipient_id=kg_message.recipient_id and newer.mode=kg_message.mode and newer.del_flag='0' and newer.msg_id>kg_message.msg_id)";
    Configuration config;

    @BeforeEach
    void setup() throws Exception
    {
        config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("KgMessage", KgMessage.class);
        String resource = "mapper/kangaroo/KgMessageMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(in);
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
    }

    private String sql(String id, String category)
    {
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("recipientId", 7L);
        p.put("mode", "staff");
        p.put("category", category);
        p.put("msgId", 1L);
        return config.getMappedStatement(NS + id).getBoundSql(p).getSql().replaceAll("\\s+", " ");
    }

    @Test
    void listShowsOneRowPerConversationNewestFirst()
    {
        for (String category : new String[] { null, "chat" })
        {
            String q = sql("selectAppMessages", category);
            assertTrue(q.contains("(biz_type is null or biz_type <> 'chat_conv' or " + HIDE_OLDER + ")"), q);
            // 有新消息的会话那一行 create_time 会更新，要浮到最上面；同一秒内按插入顺序
            assertTrue(q.endsWith("order by create_time desc, msg_id desc"), q);
            assertFalse(q.contains("order by msg_id desc"), q);
        }
        assertTrue(sql("selectAppMessages", "chat").contains("and category = ?"));
    }

    @Test
    void badgesCountTheSameRowsTheListShows()
    {
        for (String id : new String[] { "countUnread", "countByCategory" })
        {
            String q = sql(id, null);
            assertTrue(q.contains(HIDE_OLDER), id + ": " + q);
            assertTrue(q.contains("c.conv_type='store'") && q.contains("kg_chat_reception"), id + " keeps the reception ACL");
        }
    }

    @Test
    void updatesKeepTheReceptionRuleButNeverReferenceKgMessageAgain()
    {
        for (String id : new String[] { "markRead", "markAllRead" })
        {
            String q = sql(id, null);
            assertTrue(q.startsWith("update kg_message set read_flag = '1'"), q);
            assertTrue(q.contains("kg_chat_reception"), id + " keeps the reception ACL");
            // MySQL 1093：UPDATE 的子查询不能再引用 kg_message
            assertFalse(q.contains("from kg_message"), q);
        }
    }
}
