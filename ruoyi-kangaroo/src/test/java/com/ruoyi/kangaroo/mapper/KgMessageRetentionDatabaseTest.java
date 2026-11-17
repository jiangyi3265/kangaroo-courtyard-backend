package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgChatService;
import com.ruoyi.kangaroo.service.impl.KgMessageServiceImpl;

/** 真实 MySQL，只允许本任务独立端口上的固定隔离库。 */
class KgMessageRetentionDatabaseTest {
    static JdbcTemplate sql;
    static KgMessageMapper mapper;
    static KgMessageServiceImpl service;
    static DriverManagerDataSource ds;
    @BeforeAll static void init() throws Exception {
        String url=System.getenv("KG_MESSAGE_TEST_URL");
        Assumptions.assumeTrue("jdbc:mysql://127.0.0.1:33319/kg_message_retention_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai".equals(url));
        new JdbcTemplate(new DriverManagerDataSource(url.replace("/kg_message_retention_test?","/?"),"root","")).execute("create database if not exists kg_message_retention_test character set utf8mb4 collate utf8mb4_unicode_ci");
        ds=new DriverManagerDataSource(url,"root",""); sql=new JdbcTemplate(ds);
        String[] ddl={
          "kg_message(msg_id bigint primary key auto_increment,recipient_id bigint,mode varchar(10),category varchar(30),title varchar(128),content varchar(500),biz_type varchar(40),biz_id bigint,actor_id bigint,route varchar(128),read_flag char(1) default '0',read_time datetime,del_flag char(1) default '0',create_time datetime)",
          "kg_staff_grant(app_user_id bigint,store_id bigint,role_code varchar(32),status char(1),del_flag char(1),granted_by varchar(60))",
          "kg_role_switch(id int,phase varchar(20))", "kg_job(job_id bigint,store_id bigint,job_type varchar(20),del_flag char(1))",
          "kg_application(app_id bigint,app_user_id bigint,job_id bigint,store_id bigint,del_flag char(1))",
          "kg_store(store_id bigint,status char(1),del_flag char(1))",
          "kg_chat_conv(conv_id bigint,conv_type varchar(20),store_id bigint,activity_id bigint,pair_key varchar(100),source varchar(30))",
          "kg_chat_member(conv_id bigint,app_user_id bigint,role varchar(20),left_time datetime)",
          "kg_chat_reception(store_id bigint,first_id bigint,second_id bigint)", "kg_activity(activity_id bigint,store_id bigint)",
          "kg_app_user(user_id bigint,status char(1),del_flag char(1),nickname varchar(50),avatar varchar(200))",
          "kg_post(post_id bigint,status varchar(20),del_flag char(1),images varchar(300),title varchar(100),content text)",
          "kg_post_comment(comment_id bigint,post_id bigint,status varchar(20),del_flag char(1),reply_user_id bigint,parent_id bigint,app_user_id bigint,content text)",
          "kg_user_block(user_id bigint,blocked_id bigint)"
        };
        for(String table:ddl) sql.execute("create table if not exists "+table);
        Configuration cfg=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));
        cfg.getTypeAliasRegistry().registerAlias("KgMessage",KgMessage.class);
        String resource="mapper/kangaroo/KgMessageMapper.xml";
        try(InputStream in=KgMessageRetentionDatabaseTest.class.getClassLoader().getResourceAsStream(resource)) { new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse(); }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg)).getMapper(KgMessageMapper.class);
        KgMessageServiceImpl target=new KgMessageServiceImpl(); ReflectionTestUtils.setField(target,"kgMessageMapper",mapper);
        ProxyFactory proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(KgMessageServiceImpl)proxy.getProxy();
    }
    @BeforeEach void clean() {
        for(String table:Arrays.asList("kg_message","kg_staff_grant","kg_chat_conv","kg_chat_member","kg_application")) sql.execute("delete from "+table);
    }
    void add(long id,long user,String mode,String read,String age) {
        sql.update("insert into kg_message(msg_id,recipient_id,mode,category,title,read_flag,read_time,create_time) values(?,?,?,'interview','fixture',?,"+age+",date_sub(now(),interval 20 day))",id,user,mode,read);
    }
    List<Long> ids() { return mapper.selectAppMessagePage(7L,"user",null,"main",0,50).stream().map(KgMessage::getMsgId).collect(Collectors.toList()); }
    @Test void hidesAt72HoursButRetainsUnreadRecentAndUnknownReadTime() {
        add(1,7,"user","1","date_sub(now(),interval 3 day)");
        add(2,7,"user","1","date_sub(now(),interval 71 hour)");
        add(3,7,"user","0","date_sub(now(),interval 10 day)");
        add(4,7,"user","1","null"); add(5,8,"user","0","null"); add(6,7,"staff","0","null");
        assertEquals(Arrays.asList(4L,3L,2L),ids());
        assertEquals(3,mapper.selectAppMessages(7L,"user",null).size()); assertEquals(1,mapper.countUnread(7L,"user"));
        assertEquals(3L,((Number)mapper.countByCategory(7L,"user").get(0).get("total")).longValue());
        assertEquals(3L,((Number)mapper.countByBucket(7L,"user").get(0).get("total")).longValue());
        assertEquals(6,sql.queryForObject("select count(*) from kg_message",Integer.class));
    }
    @Test void repeatedReadDoesNotRestartClockAndLegacyReadGetsFirstKnownTime() {
        add(1,7,"user","1","date_sub(now(),interval 71 hour)");
        Date before=sql.queryForObject("select read_time from kg_message where msg_id=1",Date.class);
        assertEquals(0,mapper.markRead(1L,7L)); assertEquals(before,sql.queryForObject("select read_time from kg_message where msg_id=1",Date.class));
        add(2,7,"user","1","null"); assertEquals(1,mapper.markRead(2L,7L));
        assertNotNull(sql.queryForObject("select read_time from kg_message where msg_id=2",Date.class));
        add(3,7,"user","0","null"); assertEquals(1,mapper.markAllRead(7L,"user",null,null));
        assertEquals(before,sql.queryForObject("select read_time from kg_message where msg_id=1",Date.class));
    }
    @Test void deletionChecksOwnerModeAndCurrentStaffAclAndIsIdempotent() {
        add(1,7,"user","0","null"); add(2,8,"user","0","null"); add(3,7,"staff","0","null");
        assertEquals(0,service.deleteOwnMessage(2L,7L,"user")); assertEquals(0,service.deleteOwnMessage(3L,7L,"user"));
        sql.execute("insert into kg_staff_grant values(7,1,'online_staff','0','0',null)");
        assertEquals(0,service.deleteOwnMessage(3L,7L,"staff"));
        assertEquals(1,service.deleteOwnMessage(1L,7L,"user")); assertEquals(0,service.deleteOwnMessage(1L,7L,"user"));
        assertEquals(0,mapper.countUnread(7L,"user")); assertEquals(3,sql.queryForObject("select count(*) from kg_message",Integer.class));
    }
    @Test void deleteChatHidesOldSummariesButNewChatReappearsWithoutTouchingOtherUser() {
        sql.execute("insert into kg_chat_conv(conv_id,conv_type) values(91,'direct')");
        sql.execute("insert into kg_chat_member(conv_id,app_user_id,role) values(91,7,'member'),(91,8,'member')");
        add(1,7,"user","1","now()"); add(2,7,"user","0","null"); add(3,8,"user","0","null");
        sql.execute("update kg_message set category='chat',biz_type='chat_conv',biz_id=91");
        assertEquals(Arrays.asList(2L),ids()); assertEquals(2,service.deleteOwnMessage(2L,7L,"user")); assertTrue(ids().isEmpty());
        assertEquals("0",sql.queryForObject("select del_flag from kg_message where msg_id=3",String.class));
        KgChatService chats=new KgChatService(); ReflectionTestUtils.setField(chats,"db",new KgJdbc(ds));
        Map<String,Object> conv=new HashMap<>();conv.put("convId",91L);conv.put("convType","direct");
        ReflectionTestUtils.invokeMethod(chats,"upsertConvNotice",7L,conv,"member","new chat","hello",true);
        assertEquals(1,ids().size()); assertTrue(ids().get(0)>3); assertEquals(1,mapper.countUnread(7L,"user"));
    }
    @Test void expiredLatestChatDoesNotRevealHistoricalSummary() {
        sql.execute("insert into kg_chat_conv(conv_id,conv_type) values(91,'direct')");
        sql.execute("insert into kg_chat_member(conv_id,app_user_id,role) values(91,7,'member')");
        add(1,7,"user","1","now()"); add(2,7,"user","1","date_sub(now(),interval 4 day)");
        sql.execute("update kg_message set category='chat',biz_type='chat_conv',biz_id=91");
        assertTrue(ids().isEmpty());
        sql.execute("update kg_message set read_flag='0',read_time=null,create_time=now() where msg_id=2");
        assertEquals(Arrays.asList(2L),ids());
    }
}
