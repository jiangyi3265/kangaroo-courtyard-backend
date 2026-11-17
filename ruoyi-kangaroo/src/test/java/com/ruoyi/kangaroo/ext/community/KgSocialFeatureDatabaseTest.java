package com.ruoyi.kangaroo.ext.community;

import java.lang.reflect.Method;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.controller.admin.KgFeatureAdminController;
import com.ruoyi.kangaroo.controller.app.*;
import com.ruoyi.kangaroo.security.CommunityFeatureInterceptor;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

class KgSocialFeatureDatabaseTest {
    AnnotationConfigApplicationContext context;
    KgJdbc db;KgCommunityFeature feature;
    @Configuration @EnableGlobalMethodSecurity(prePostEnabled=true) @EnableTransactionManagement
    static class Config {
        @Bean(name="ss") Permission permission() { return new Permission(); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }
    public static class Permission {
        boolean allowed;
        public boolean hasPermi(String value) { return allowed && "system:config:edit".equals(value); }
    }
    @BeforeEach void setup() {
        String name=System.getenv("KG_MEMBER_TEST_DB");
        assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(source);db.update("delete from sys_config where config_key=:k",p("k",KgCommunityFeature.KEY));
        db.update("delete from sys_config where config_key=:k",p("k",KgCommunityFeature.PRIVATE_KEY));
        db.update("delete from sys_config where config_key in (:w,:g)",p("w",KgCommunityFeature.WISH_KEY,"g",KgCommunityFeature.GROUP_KEY));
        context=new AnnotationConfigApplicationContext();context.register(Config.class);
        context.registerBean(DataSource.class,()->source);context.registerBean(KgJdbc.class,()->db);
        context.registerBean(KgCommunityFeature.class);context.registerBean(KgFeatureAdminController.class);context.registerBean(com.ruoyi.kangaroo.controller.admin.KgSocialFeatureAdminController.class);context.refresh();
        feature=context.getBean(KgCommunityFeature.class);
        SysUser user=new SysUser();user.setUserId(1L);user.setUserName("fixture-admin");
        LoginUser principal=new LoginUser();principal.setUser(user);principal.setUserId(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,java.util.Collections.emptyList()));
    }
    @AfterEach void close() { SecurityContextHolder.clearContext();if(context!=null)context.close(); }

    @Test void defaultsMalformedAndIndependentPersistence() {
        assertFalse(feature.wishEnabled());assertFalse(feature.groupChatEnabled());
        feature.saveFeature("wish",true,false,true,"fixture");assertTrue(feature.wishEnabled());assertFalse(feature.groupChatEnabled());assertFalse(feature.enabled());
        feature.saveFeature("group-chat",true,false,true,"fixture");assertTrue(new KgCommunityFeature(db).groupChatEnabled());
        feature.saveFeature("wish",false,true,false,"fixture-close");assertFalse(feature.wishEnabled());assertTrue(feature.groupChatEnabled());
        for(String bad:Arrays.asList("TRUE","1"," true ","")) { db.update("update sys_config set config_value=:v where config_key=:k",p("v",bad,"k",KgCommunityFeature.GROUP_KEY));assertFalse(feature.groupChatEnabled()); }
    }
    @Test void staleUnconfirmedAndUnknownWritesRejected() {
        assertThrows(ServiceException.class,()->feature.saveFeature("wish",true,false,false,"fixture"));
        assertThrows(ServiceException.class,()->feature.saveFeature("unknown",true,false,true,"fixture"));
        feature.saveFeature("wish",true,false,true,"fixture");
        assertEquals(409,assertThrows(ServiceException.class,()->feature.saveFeature("wish",false,false,false,"stale")).getCode());assertTrue(feature.wishEnabled());
    }
    @Test void realAdminAuthorizationAndAnonymousFeatureResponse() {
        com.ruoyi.kangaroo.controller.admin.KgSocialFeatureAdminController admin=context.getBean(com.ruoyi.kangaroo.controller.admin.KgSocialFeatureAdminController.class);
        KgFeatureAdminController.Change change=new KgFeatureAdminController.Change();change.enabled=true;change.expectedEnabled=false;change.categoryConfirmed=true;
        assertThrows(AccessDeniedException.class,()->admin.state("wish",new MockHttpServletResponse()));
        assertThrows(AccessDeniedException.class,()->admin.save("group-chat",change));
        assertThrows(AccessDeniedException.class,()->admin.state("private-chat",new MockHttpServletResponse()));
        assertThrows(AccessDeniedException.class,()->admin.save("private-chat",change));
        context.getBean(Permission.class).allowed=true;assertEquals(200,admin.save("group-chat",change).get("code"));
        MockHttpServletResponse response=new MockHttpServletResponse();java.util.Map<?,?> data=(java.util.Map<?,?>)new AppFeatureController(feature).features(response).get("data");
        assertEquals(false,data.get("wishEnabled"));assertEquals(true,data.get("groupChatEnabled"));assertEquals("no-store",response.getHeader("Cache-Control"));
    }
    com.ruoyi.kangaroo.ext.wish.WishService wishes() {
        com.ruoyi.kangaroo.ext.wish.WishService service=new com.ruoyi.kangaroo.ext.wish.WishService();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"db",db);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"notices",org.mockito.Mockito.mock(com.ruoyi.kangaroo.service.IKgMessageService.class));return service;
    }
    long wish(String state) {
        return db.insert("kg_wish",p("publisher_id",99301L,"helper_id",99302L,"request_key",java.util.UUID.randomUUID().toString(),"title","隔离验收心愿","description","测试已有订单，不向生产写入","category","postcard","origin_city","平潭","target_city","厦门","delivery","express","deadline",java.time.LocalDate.now().plusDays(7),"mode","free","cost_fen",0L,"markup_fen",0L,"amount_fen",0L,"fee_fen",0L,"net_fen",0L,"status",state));
    }
    @Test void closedPublicWishReadsAndCreatesFailBeforeMutations() {
        com.ruoyi.kangaroo.ext.wish.WishService service=wishes();long id=wish("open");
        assertEquals(423,assertThrows(ServiceException.class,()->service.list(null,"","","","","","","","","latest",1)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->service.detail(id,null)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->service.publish(99301L,p())).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->service.accept(id,99303L)).getCode());
        assertEquals("open",service.adminDetail(id).get("status"));
        feature.saveFeature("wish",true,false,true,"fixture");assertEquals("open",service.detail(id,null).get("status"));
    }
    @Test void privateWishHistoryCancellationAndCommunicationRemainAvailable() {
        com.ruoyi.kangaroo.ext.wish.WishService service=wishes();long id=wish("open");
        assertEquals(true,service.detail(id,99301L).get("isOwner"));
        assertNotNull(service.list(99301L,"publisher","","","","","","","","latest",1));
        assertEquals("cancelled",service.action(id,99301L,"cancel",p()).get("status"));
        long live=wish("accepted");
        service.message(live,99301L,p("content","已有订单沟通仍可正常处理","requestKey",java.util.UUID.randomUUID().toString()));
        assertEquals(1,db.count("select count(*) from kg_wish_message where wish_id=:id",p("id",live)));
        assertThrows(ServiceException.class,()->service.messages(live,99303L,0));
        assertEquals("disputed",service.action(live,99302L,"dispute",p("note","已有订单申请平台核实处理")).get("status"));
    }
    @Test void groupCreationIsSkippedWithoutBreakingActivityWorkflows() {
        KgChatService chats=new KgChatService();org.springframework.test.util.ReflectionTestUtils.setField(chats,"db",db);
        long id=db.insert("kg_activity",p("source","yard","title","隔离验收活动","city","平潭","start_time",java.time.LocalDateTime.now().plusDays(2),"status","open"));
        assertEquals(0L,chats.ensureActivityGroup(id));chats.joinActivityGroup(id,99301L);assertNull(db.one("select conv_id from kg_activity where activity_id=:id",p("id",id)).get("convId"));
        assertEquals(423,assertThrows(ServiceException.class,()->chats.openActivity(99301L,id)).getCode());
        feature.saveFeature("group-chat",true,false,true,"fixture");long conv=chats.ensureActivityGroup(id);assertTrue(conv>0);
        feature.saveFeature("group-chat",false,true,false,"fixture");assertEquals(423,assertThrows(ServiceException.class,()->chats.conversation(99301L,conv)).getCode());
        assertEquals(1,db.count("select count(*) from kg_chat_conv where conv_id=:c",p("c",conv)),"Closing preserves historical data");
    }
    @Test void groupClosedPreventsQrBeforeLookup() {
        com.ruoyi.kangaroo.ext.user.KgRegionGroupService groups=new com.ruoyi.kangaroo.ext.user.KgRegionGroupService();
        org.springframework.test.util.ReflectionTestUtils.setField(groups,"db",db);
        assertEquals(423,assertThrows(ServiceException.class,()->groups.forCity("平潭")).getCode());
    }
    @Test void groupInboxRowsAndUnreadCountsAreFilteredByActualMapper() throws Exception {
        feature.saveFeature("private-chat",true,false,true,"fixture");
        org.mybatis.spring.SqlSessionFactoryBean factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(context.getBean(DataSource.class));
        factory.setTypeAliasesPackage("com.ruoyi.kangaroo.domain");factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/kangaroo/KgMessageMapper.xml"));
        try(org.apache.ibatis.session.SqlSession session=factory.getObject().openSession()) {
            com.ruoyi.kangaroo.mapper.KgMessageMapper mapper=session.getMapper(com.ruoyi.kangaroo.mapper.KgMessageMapper.class);
            db.update("delete from kg_message where recipient_id=99309",p());
            db.insert("kg_message",p("recipient_id",99309L,"mode","user","category","group","title","群通知","content","群内消息","read_flag","0","del_flag","0"));
            db.insert("kg_message",p("recipient_id",99309L,"mode","user","category","chat","title","私信","content","正常私信","read_flag","0","del_flag","0"));
            assertEquals(1,mapper.countUnread(99309L,"user"));assertEquals(1,mapper.selectAppMessages(99309L,"user",null).size());
            feature.saveFeature("group-chat",true,false,true,"fixture");session.clearCache();assertEquals(2,mapper.countUnread(99309L,"user"));
            feature.saveFeature("group-chat",false,true,false,"fixture");session.clearCache();assertEquals(1,mapper.countUnread(99309L,"user"));
        }
    }
}
