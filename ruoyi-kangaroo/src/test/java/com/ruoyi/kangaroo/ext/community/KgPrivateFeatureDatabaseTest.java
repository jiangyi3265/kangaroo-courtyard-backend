package com.ruoyi.kangaroo.ext.community;

import java.lang.reflect.Method;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.controller.app.*;
import com.ruoyi.kangaroo.security.CommunityFeatureInterceptor;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Executes against a fresh, isolated MySQL schema, never the production database. */
class KgPrivateFeatureDatabaseTest {
    KgJdbc db;KgCommunityFeature feature;DataSource source;
    @BeforeEach void setup() {
        String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(source);feature=new KgCommunityFeature(db);
        db.update("insert ignore into kg_app_user(user_id,openid,nickname,status,del_flag) values(99409,'private-switch-fixture','隔离用户','0','0')",p());
        db.update("delete from sys_config where config_key in (:c,:w,:g,:p)",p("c",KgCommunityFeature.KEY,"w",KgCommunityFeature.WISH_KEY,"g",KgCommunityFeature.GROUP_KEY,"p",KgCommunityFeature.PRIVATE_KEY));
        db.update("delete from kg_message where recipient_id=99409",p());
    }
    void enabled(boolean on) { feature.saveFeature("private-chat",on,!on,on,"isolated-fixture"); }
    KgChatService chats() { KgChatService c=new KgChatService();ReflectionTestUtils.setField(c,"db",db);return c; }
    long conversation(String type,Long storeId) {
        long id=db.insert("kg_chat_conv",p("conv_type",type,"title","隔离验收会话","source","profile","pair_key",UUID.randomUUID().toString(),"store_id",storeId,"status","active"));
        db.insert("kg_chat_member",p("conv_id",id,"app_user_id",99409L,"role","member"));return id;
    }
    @Test void independentPersistentStrictDefaultAndQualification() {
        assertFalse(feature.privateChatEnabled());assertEquals(false,feature.state("private-chat").get("enabled"));
        assertEquals(422,assertThrows(ServiceException.class,()->feature.saveFeature("private-chat",true,false,false,"fixture")).getCode());
        enabled(true);assertTrue(new KgCommunityFeature(db).privateChatEnabled());assertFalse(feature.enabled());assertFalse(feature.wishEnabled());assertFalse(feature.groupChatEnabled());
        assertEquals(409,assertThrows(ServiceException.class,()->feature.saveFeature("private-chat",false,false,false,"stale")).getCode());
        for(String value:Arrays.asList("TRUE","1"," true ","")){db.update("update sys_config set config_value=:v where config_key=:k",p("v",value,"k",KgCommunityFeature.PRIVATE_KEY));assertFalse(feature.privateChatEnabled());}
        assertEquals(false,((Map<?,?>)new AppFeatureController(feature).features(new MockHttpServletResponse()).get("data")).get("privateChatEnabled"));
    }
    @Test void newPrivateAndRecentFailBeforeIdentityLookupOrWrite() {
        KgChatService c=chats();long count=db.count("select count(*) from kg_chat_conv",p());
        assertEquals(423,assertThrows(ServiceException.class,()->c.openPrivate(99409L,99410L,"progress",1L)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->c.recentPrivate(99409L)).getCode());
        assertEquals(count,db.count("select count(*) from kg_chat_conv",p()));
    }
    @Test void legacyPrivateAllReadsWritesAndContactsAreClosed() {
        long id=conversation("private",null);KgChatService c=chats();
        assertEquals(423,assertThrows(ServiceException.class,()->c.conversation(99409L,id)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->c.messages(99409L,id,0L)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->c.send(99409L,id,"不可发送")).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->c.exchangeContact(99409L,id,true)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->c.image(99409L,id,1L)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->c.file(99409L,id,1L)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->c.setFlag(99409L,id,"muted",true)).getCode());
        assertEquals(0,db.count("select count(*) from kg_chat_msg where conv_id=:c",p("c",id)));
        assertEquals(1,db.count("select count(*) from kg_chat_conv where conv_id=:c",p("c",id)));
    }
    @Test void businessScopeNeverReliesOnClientProvidedSource() {
        KgChatService c=chats();long id=conversation("private",123L);
        db.update("update kg_chat_conv set source='progress' where conv_id=:c",p("c",id));
        assertEquals(423,assertThrows(ServiceException.class,()->c.conversation(99409L,id)).getCode());
        assertEquals(404,assertThrows(ServiceException.class,()->c.openStore(99409L,999999999L)).getCode());
        Map<String,Object> fact=KgChatService.inboxFact(99409L,"user",p("convType","store","title","业务门店","source","progress","pairKey","offline:1:u99409"),Collections.emptyList());
        assertEquals("store",fact.get("conversationType"));
    }
    @Test void companionBrowseAndJoinClosedWithdrawalStillWorks() {
        com.ruoyi.kangaroo.ext.user.KgUserHubService hub=new com.ruoyi.kangaroo.ext.user.KgUserHubService();ReflectionTestUtils.setField(hub,"db",db);
        assertEquals(423,assertThrows(ServiceException.class,()->hub.team(99409L,1L)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->hub.joinTeam(99409L,1L,true)).getCode());
        assertEquals(false,hub.joinTeam(99409L,1L,false).get("joined"));
    }
    @Test void privateSocialHandlersClosedWhileBusinessHandlersPass() throws Exception {
        CommunityFeatureInterceptor gate=new CommunityFeatureInterceptor(feature);
        for(Class<?> type:Arrays.asList(AppCommunityController.class,AppTripChatController.class))for(Method m:type.getDeclaredMethods()){
            if(!Arrays.asList("follow","following","publishPlan","matches","person","open","chat","messages").contains(m.getName()))continue;
            HandlerMethod handler=new HandlerMethod(org.mockito.Mockito.mock(type),m);
            if(Arrays.asList("follow","following","publishPlan","matches","person").contains(m.getName()))assertEquals(423,assertThrows(ServiceException.class,()->gate.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler)).getCode());
            else assertTrue(gate.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler));
        }
    }
    void notice(String category,String biz,Long conv) {db.insert("kg_message",p("recipient_id",99409L,"mode","user","category",category,"biz_type",biz,"biz_id",conv,"title","隔离通知","content","仅验收","read_flag","0","del_flag","0"));}
    @Test void actualMapperFiltersBeforePaginationAndCountsRestoresHistory() throws Exception {
        db.update("insert ignore into kg_app_user(user_id,openid,nickname,status,del_flag) values(99409,'private-switch-fixture','隔离用户','0','0')",p());
        long store=db.insert("kg_store",p("store_name","隔离业务门店","city","平潭","status","0","del_flag","0"));
        long business=conversation("store",store),privateId=conversation("private",null);
        db.update("update kg_chat_conv set source='progress',pair_key=:pair where conv_id=:c",p("pair","offline:"+store+":u99409","c",business));
        notice("chat","chat_conv",business);notice("order","application",1L);notice("chat",null,null);notice("chat","chat_conv",privateId);
        long disguised=conversation("private",null);notice("system","chat_conv",disguised);
        org.mybatis.spring.SqlSessionFactoryBean factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(source);factory.setTypeAliasesPackage("com.ruoyi.kangaroo.domain");factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/kangaroo/KgMessageMapper.xml"));
        try(org.apache.ibatis.session.SqlSession session=factory.getObject().openSession()) {
            com.ruoyi.kangaroo.mapper.KgMessageMapper m=session.getMapper(com.ruoyi.kangaroo.mapper.KgMessageMapper.class);
            assertEquals(2,m.countUnread(99409L,"user"));assertEquals(2,m.selectAppMessages(99409L,"user",null).size());
            assertEquals(Long.valueOf(business),m.selectAppMessagePage(99409L,"user","chat","main",0,1).get(0).getBizId());
            assertEquals(2,m.countByCategory(99409L,"user").size());
            enabled(true);session.clearCache();assertEquals(5,m.countUnread(99409L,"user"));
            enabled(false);session.clearCache();assertEquals(2,m.countUnread(99409L,"user"));
            assertEquals(5,db.count("select count(*) from kg_message where recipient_id=99409",p()));
            db.update("update kg_chat_member set left_time=now() where conv_id=:c",p("c",business));session.clearCache();assertEquals(1,m.countUnread(99409L,"user"));
        }
    }
}
