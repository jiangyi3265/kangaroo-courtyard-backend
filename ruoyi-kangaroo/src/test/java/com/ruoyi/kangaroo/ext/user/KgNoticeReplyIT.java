package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/** 真实 MySQL 隔离数据库：不修改业务库、不实际发通知。 */
@EnabledIfEnvironmentVariable(named="KG_TEST_DB_PASSWORD", matches=".+")
class KgNoticeReplyIT
{
    JdbcTemplate root, sql;
    String database;
    KgNoticeReplyService service;
    StaffSupport support;
    DataSource source(String db) {
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+db+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", System.getenv("KG_TEST_DB_PASSWORD"));
    }
    @BeforeEach void setup() throws Exception {
        database="kg_notice_test_"+UUID.randomUUID().toString().replace("-", "");
        root=new JdbcTemplate(source(""));
        root.execute("create database "+database+" character set utf8mb4 collate utf8mb4_unicode_ci");
        DataSource ds=source(database); sql=new JdbcTemplate(ds);
        String ddl=new String(Files.readAllBytes(Paths.get("../../.dev/migrations/V75_body.sql")), java.nio.charset.StandardCharsets.UTF_8);
        ddl=ddl.replaceAll("(?m)^--.*$", "");
        for(String command:ddl.split(";")) if(command.trim().startsWith("create table")) sql.execute(command.trim());
        sql.execute("create table kg_app_user(user_id bigint primary key, nickname varchar(40), status char(1), del_flag char(1))");
        sql.execute("insert into kg_app_user values(1,'测试用户一','0','0'),(2,'测试用户二','0','0')");
        sql.execute("create table kg_app_notice(notice_id bigint primary key,title varchar(64),status char(1),del_flag char(1),start_time datetime,end_time datetime)");
        sql.execute("insert into kg_app_notice values(10,'行程通知','0','0',null,null)");
        sql.execute("create table kg_store(store_id bigint primary key,status char(1),del_flag char(1))");
        sql.execute("create table kg_staff_grant(app_user_id bigint,store_id bigint,status char(1),del_flag char(1),city_id bigint,role_code varchar(32))");
        sql.execute("create table kg_city(city_id bigint primary key,name varchar(32),status char(1))");
        sql.execute("create table kg_message(recipient_id bigint,biz_type varchar(40),biz_id bigint,read_flag char(1),read_time datetime)");
        support=mock(StaffSupport.class);
        KgNoticeReplyService target=new KgNoticeReplyService();
        ReflectionTestUtils.setField(target,"db",new KgJdbc(ds));ReflectionTestUtils.setField(target,"support",support);
        ProxyFactory proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(KgNoticeReplyService)proxy.getProxy();
    }
    @AfterEach void cleanup() {
        if(root!=null && database!=null && database.matches("kg_notice_test_[a-f0-9]{32}")) root.execute("drop database "+database);
    }
    Map<String,Object> body(String key,String text) { return p("requestId",key,"content",text); }
    long count(String table) { return sql.queryForObject("select count(*) from "+table,Long.class); }
    long tid(long user) { return sql.queryForObject("select thread_id from kg_notice_thread where app_user_id=?",Long.class,user); }
    String pending(long user) { return sql.queryForObject("select pending from kg_notice_thread where app_user_id=?",String.class,user); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> history(long user) { return (List<Map<String,Object>>)service.mine(user,10L,null).get("rows"); }

    @Test void privateConversationPersistsAcrossRoundsAndModes() {
        service.send(1L,10L,body("user_one_01","我能改日期吗"),false);
        assertTrue(history(2).isEmpty());
        assertEquals(1L,service.list("1",null,1,10).get("total"));
        service.reply(tid(1),99L,body("admin_one_01","可以，请告诉我日期"));
        assertEquals("0",pending(1));
        verify(support).notifyUser(eq(1L),eq("system"),anyString(),anyString(),eq("notice_reply"),anyLong(),eq("/pages/notice/index?id=10"));
        service.send(1L,10L,body("user_one_02","改成周六"),true);
        service.send(2L,10L,body("user_two_01","另一个人的问题"),false);
        assertEquals("1",pending(1)); assertEquals(2,count("kg_notice_thread"));
        assertEquals(3,history(1).size());assertEquals(1,history(2).size());
        assertFalse(history(1).get(0).containsKey("senderId"));
        assertFalse(history(1).get(0).containsKey("requestId"));
    }
    @Test void concurrentRetryWritesOnceAndNotifiesOnce() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Object>> calls=new ArrayList<>();
            for(int i=0;i<8;i++) calls.add(()->service.send(1L,10L,body("same_user_01","同一次提交"),false));
            for(Future<Object> f:pool.invokeAll(calls)) f.get();
            assertEquals(1,count("kg_notice_thread"));assertEquals(1,count("kg_notice_reply"));
            calls.clear();long id=tid(1);
            for(int i=0;i<8;i++) calls.add(()->service.reply(id,99L,body("same_admin_01","收到")));
            for(Future<Object> f:pool.invokeAll(calls)) f.get();
            assertEquals(2,count("kg_notice_reply"));
            verify(support,times(1)).notifyUser(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        } finally { pool.shutdownNow(); }
    }
    @Test void archiveDoesNotLoseExistingConversationAndCannotStartNewOne() {
        service.send(1L,10L,body("archive_001","咨询"),false);
        sql.update("update kg_app_notice set status='1'");
        assertEquals(1,history(1).size());
        assertThrows(ServiceException.class,()->service.send(2L,10L,body("archive_002","新咨询"),false));
        service.reply(tid(1),99L,body("archive_003","已下线但继续答复"));
        service.send(1L,10L,body("archive_004","谢谢"),false);
        assertEquals(3,history(1).size());
    }
    @Test void staffReplyFallsBackAfterGrantIsRevoked() {
        sql.update("insert into kg_store values(1,'0','0')");sql.update("insert into kg_staff_grant(app_user_id,store_id,status,del_flag,role_code) values(1,1,'0','0','manager')");
        service.send(1L,10L,body("staff_001","员工咨询"),true);
        service.reply(tid(1),99L,body("staff_002","员工端接收"));
        verify(support).notifyStaff(eq(1L),anyString(),anyString(),anyString(),eq("notice_reply"),anyLong(),anyString());
        sql.update("update kg_staff_grant set status='1'");
        service.reply(tid(1),99L,body("staff_003","用户端接收"));
        verify(support).notifyUser(eq(1L),anyString(),anyString(),anyString(),eq("notice_reply"),anyLong(),anyString());
    }
    // 按城市授权、本城还没有门店：登录能进员工身份，回复也发到员工身份；城市停用后退回用户身份
    @Test void cityGrantWithoutStoreStillCountsAsStaffIdentity() {
        sql.update("insert into kg_city values(20,'大连','0')");
        sql.update("insert into kg_staff_grant(app_user_id,store_id,status,del_flag,city_id,role_code) values(1,null,'0','0',20,'city_lead')");
        service.send(1L,10L,body("city_0001","城市负责人咨询"),true);
        service.reply(tid(1),99L,body("city_0002","员工端接收"));
        verify(support).notifyStaff(eq(1L),anyString(),anyString(),anyString(),eq("notice_reply"),anyLong(),anyString());
        sql.update("update kg_city set status='1'");
        service.reply(tid(1),99L,body("city_0003","用户端接收"));
        verify(support).notifyUser(eq(1L),anyString(),anyString(),anyString(),eq("notice_reply"),anyLong(),anyString());
    }
    @Test void readBoundDoesNotConsumeNewRepliesOrAnotherUsersMessages() {
        service.send(1L,10L,body("read_0001","咨询"),false);
        long first=((Number)service.reply(tid(1),99L,body("read_0002","第一条")).get("replyId")).longValue();
        long next=((Number)service.reply(tid(1),99L,body("read_0003","第二条")).get("replyId")).longValue();
        sql.update("insert into kg_message values(1,'notice_reply',?,'0',null),(1,'notice_reply',?,'0',null),(2,'notice_reply',?,'0',null)",first,next,first);
        service.read(2L,10L,Long.MAX_VALUE);
        assertEquals(0,sql.queryForObject("select count(*) from kg_message where read_flag='1'",Integer.class));
        service.read(1L,10L,first);
        assertEquals(1,sql.queryForObject("select count(*) from kg_message where read_flag='1'",Integer.class));
    }
    @Test void paginationAndRollbackKeepFullHistory() {
        service.send(1L,10L,body("pages_0001","咨询"),false);
        long id=tid(1);
        for(int i=0;i<55;i++)service.reply(id,99L,body("pages_reply_"+i,"答复"+i));
        Map<String,Object> latest=service.mine(1L,10L,null);
        assertEquals(true,latest.get("hasMore"));assertEquals(50,history(1).size());
        Long before=((Number)history(1).get(0).get("replyId")).longValue();
        assertEquals(false,service.mine(1L,10L,before).get("hasMore"));
        doThrow(new IllegalStateException("notification failure")).when(support).notifyUser(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        assertThrows(IllegalStateException.class,()->service.reply(id,99L,body("rollback_01","不能半成功")));
        assertEquals(56,count("kg_notice_reply"));
        assertThrows(ServiceException.class,()->service.send(1L,10L,body("pages_0001","同一个标识换内容"),false));
        assertThrows(ServiceException.class,()->service.send(1L,10L,body("invalid_01","  "),false));
    }
}
