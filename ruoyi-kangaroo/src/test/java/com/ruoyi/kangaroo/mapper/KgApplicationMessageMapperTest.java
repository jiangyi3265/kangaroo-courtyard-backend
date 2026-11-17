package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import com.ruoyi.kangaroo.domain.KgMessage;

class KgApplicationMessageMapperTest {
    Configuration config;
    @BeforeEach void setup() throws Exception {
        config = new Configuration(); config.getTypeAliasRegistry().registerAlias("KgMessage", KgMessage.class);
        String path = "mapper/kangaroo/KgMessageMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(in, config, path, config.getSqlFragments()).parse();
        }
    }
    BoundSql bound(String name, Long before) {
        Map<String, Object> p = new HashMap<>(); p.put("recipientId", 7L); p.put("appId", 81L); p.put("before", before); p.put("limit", 21);
        return config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgMessageMapper." + name).getBoundSql(p);
    }
    String sql(String name, Long before) { return bound(name, before).getSql().replaceAll("\\s+", " ").trim(); }
    @Test void noticesAndUnreadShareOwnerApplicationAndUserModeScope() {
        for (String name : new String[] {"selectApplicationNotices", "countApplicationUnread"}) {
            String q = sql(name, null);
            assertTrue(q.contains("recipient_id = ? and mode = 'user' and del_flag = '0'"), q);
            assertTrue(q.contains("biz_type = 'application' and biz_id = ?"), q);
            assertTrue(q.contains("own_app.app_id = ?") && q.contains("own_app.app_user_id = ?") && q.contains("own_app.del_flag = '0'"), q);
        }
        assertTrue(sql("countApplicationUnread", null).contains("read_flag = '0'"));
    }
    @Test void stableCursorUsesBoundParametersAndNewestIdFirst() {
        String first = sql("selectApplicationNotices", null), older = sql("selectApplicationNotices", 103L);
        assertFalse(first.contains("msg_id <")); assertTrue(older.contains("msg_id < ?"));
        assertTrue(older.endsWith("order by msg_id desc limit ?"));
        assertTrue(bound("selectApplicationNotices", 103L).getParameterMappings().stream().anyMatch(p -> "before".equals(p.getProperty())));
    }
    @Test void merchantPreviewKeepsCurrentChatAclAndCanonicalOfflineStorePair() {
        String q = sql("selectApplicationChat", null);
        assertTrue(q.contains("recipient_id = ? and mode = 'user' and del_flag = '0' and biz_type = 'chat_conv'"), q);
        for (String fragment : new String[] {"kg_chat_reception", "cm.left_time is null", "newer.msg_id>kg_message.msg_id", "scoped_chat.store_id=own_app.store_id", "scoped_chat.conv_id=kg_message.biz_id", "own_app.app_user_id=?", "scoped_chat.source='progress'", "scoped_chat.pair_key=concat('offline:',own_app.store_id,':u',?)"})
            assertTrue(q.replaceAll("\\s+", "").contains(fragment.replaceAll("\\s+", "")), fragment);
        assertFalse(q.contains("scoped_chat.source_id="), "同一门店会话跨报名复用，不能误按最近关联报名排除它");
        assertTrue(q.endsWith("order by create_time desc, msg_id desc limit 1"));
    }
}
