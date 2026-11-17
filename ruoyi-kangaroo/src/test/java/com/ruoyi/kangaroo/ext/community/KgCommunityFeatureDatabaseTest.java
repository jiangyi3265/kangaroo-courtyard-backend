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

class KgCommunityFeatureDatabaseTest {
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
        context=new AnnotationConfigApplicationContext();context.register(Config.class);
        context.registerBean(DataSource.class,()->source);context.registerBean(KgJdbc.class,()->db);
        context.registerBean(KgCommunityFeature.class);context.registerBean(KgFeatureAdminController.class);context.refresh();
        feature=context.getBean(KgCommunityFeature.class);
        SysUser user=new SysUser();user.setUserId(1L);user.setUserName("fixture-admin");
        LoginUser principal=new LoginUser();principal.setUser(user);principal.setUserId(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,java.util.Collections.emptyList()));
    }
    @AfterEach void close() { SecurityContextHolder.clearContext();if(context!=null)context.close(); }
    @Test void missingAndMalformedSettingsStayClosed() {
        assertFalse(feature.enabled());assertEquals(false,feature.state().get("communityEnabled"));
        feature.save(false,false,false,"fixture");
        for(String bad:Arrays.asList("TRUE","1"," true ","garbage","")) {
            db.update("update sys_config set config_value=:v where config_key=:k",p("v",bad,"k",KgCommunityFeature.KEY));
            assertFalse(feature.enabled());assertThrows(ServiceException.class,feature::requireEnabled);
        }
    }
    @Test void togglePersistsAcrossInstancesAndCanBeClosedAgain() {
        feature.save(true,false,true,"fixture");
        KgCommunityFeature reopened=new KgCommunityFeature(db);assertTrue(reopened.enabled());
        assertEquals("fixture",reopened.state().get("updatedBy"));
        feature.save(false,true,false,"fixture-close");assertFalse(reopened.enabled());
        assertEquals(1,db.count("select count(*) from sys_config where config_key=:k",p("k",KgCommunityFeature.KEY)));
    }
    @Test void invalidOrStaleChangesDoNotChangeStoredState() {
        assertThrows(ServiceException.class,()->feature.save(true,false,false,"fixture"));assertFalse(feature.enabled());
        assertThrows(ServiceException.class,()->feature.save(null,false,true,"fixture"));
        feature.save(true,false,true,"fixture");
        assertThrows(ServiceException.class,()->feature.save(false,false,false,"stale"));assertTrue(feature.enabled());
    }
    @Test void administratorPermissionIsEnforcedByActualMethodSecurity() {
        KgFeatureAdminController admin=context.getBean(KgFeatureAdminController.class);
        KgFeatureAdminController.Change change=new KgFeatureAdminController.Change();change.enabled=true;change.expectedEnabled=false;change.categoryConfirmed=true;
        assertThrows(AccessDeniedException.class,()->admin.state(new MockHttpServletResponse()));
        assertThrows(AccessDeniedException.class,()->admin.save(change));assertFalse(feature.enabled());
        context.getBean(Permission.class).allowed=true;
        assertEquals(200,admin.save(change).get("code"));assertTrue(feature.enabled());
        context.getBean(Permission.class).allowed=false;
        change.enabled=false;change.expectedEnabled=true;
        assertThrows(AccessDeniedException.class,()->admin.save(change));assertTrue(feature.enabled());
    }
    HandlerMethod handler(Object bean,String name) {
        Method method=Arrays.stream(bean.getClass().getMethods()).filter(m->m.getName().equals(name)).findFirst().get();
        return new HandlerMethod(bean,method);
    }
    @Test void resolvedHandlersRejectLegacyReadsAndWritesWhileClosed() {
        CommunityFeatureInterceptor guard=new CommunityFeatureInterceptor(feature);
        Object[][] blocked={{new AppCommunityController(),"feed"},{new AppCommunityController(),"post"},{new AppCommunityController(),"comments"},
            {new AppCommunityController(),"publish"},{new AppCommunityController(),"user"},{new AppCommunityController(),"favorites"},
            {new AppActivityController(),"create"},{new AppActivityController(),"syncCommunity"},{new AppActivityController(),"edit"},
            {new AppTripChatController(),"publishPlan"},{new AppTripChatController(),"matches"},{new AppTripChatController(),"person"}};
        for(Object[] item:blocked) {
            MockHttpServletRequest request=new MockHttpServletRequest("GET","/app/encoded-alias");
            assertThrows(ServiceException.class,()->guard.preHandle(request,new MockHttpServletResponse(),handler(item[0],(String)item[1])));
        }
        feature.save(true,false,true,"fixture");
        feature.saveFeature("private-chat",true,feature.privateChatEnabled(),true,"fixture");
        for(Object[] item:blocked)assertTrue(guard.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler(item[0],(String)item[1])));
    }
    @Test void existingOrdersPrivateChatsAndPrivacySettingsRemainAccessible() {
        CommunityFeatureInterceptor guard=new CommunityFeatureInterceptor(feature);
        Object[][] allowed={{new AppCommunityController(),"status"},{new AppCommunityController(),"dmSetting"},
            {new AppActivityController(),"mine"},{new AppActivityController(),"manage"},
            {new AppTripChatController(),"trips"},{new AppTripChatController(),"tripDetail"},{new AppTripChatController(),"conversation"}};
        for(Object[] item:allowed)assertTrue(guard.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler(item[0],(String)item[1])));
    }
    @Test void publicActivitySqlOnlyIncludesUserActivitiesWhenEnabled() {
        String query="select count(*) from (select 'yard' source union all select 'merchant' union all select 'user') a where"+KgCommunityFeature.USER_ACTIVITY_VISIBLE_SQL;
        assertEquals(2,db.count(query,p()));feature.save(true,false,true,"fixture");assertEquals(3,db.count(query,p()));
        feature.save(false,true,false,"fixture");assertEquals(2,db.count(query,p()));
    }
    @Test void stayRecommendationsPastActivitiesAndFavoritesRespectStoredSwitch() {
        KgActivityService activities=new KgActivityService();
        org.springframework.test.util.ReflectionTestUtils.setField(activities,"db",db);
        org.springframework.test.util.ReflectionTestUtils.setField(activities,"communityFeature",feature);
        for(String source:Arrays.asList("yard","merchant","user")) {
            for(boolean past:Arrays.asList(false,true)) {
                long id=db.insert("kg_activity",p("source",source,"store_id",981L,"app_user_id",982L,"title","fixture-"+source,
                    "city","验收城市","start_time",java.time.LocalDateTime.now().plusDays(past?-3:3),"status",past?"ended":"open"));
                db.insert("kg_favorite",p("target_type","activity","target_id",id,"app_user_id",983L));
            }
        }
        for(boolean enabled:Arrays.asList(false,true,false)) {
            feature.save(enabled,feature.enabled(),enabled,"fixture");
            int sources=enabled?3:2;
            assertEquals(sources,activities.forStay("验收城市",java.time.LocalDate.now().toString(),java.time.LocalDate.now().plusDays(7).toString()).size());
            java.util.Map<String,Object> store=activities.forStore(981L);
            assertEquals(sources,((java.util.List<?>)store.get("current")).size());
            assertEquals(sources,((java.util.List<?>)store.get("past")).size());
            assertEquals(sources*2,activities.favorites(983L).size());
        }
        assertEquals(6,db.count("select count(*) from kg_activity where store_id=981",p()),"关闭不删除原内容");
    }
}
