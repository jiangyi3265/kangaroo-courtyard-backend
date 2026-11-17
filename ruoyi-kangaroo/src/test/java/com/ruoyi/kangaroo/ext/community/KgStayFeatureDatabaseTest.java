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

class KgStayFeatureDatabaseTest {
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
        db=new KgJdbc(source);db.update("delete from sys_config where config_key=:k",p("k",KgCommunityFeature.STAY_KEY));db.update("delete from sys_config where config_key=:k",p("k",KgCommunityFeature.KEY));
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


    @Test void defaultClosedStrictBooleanPersistenceAndIndependentSwitches() {
        assertFalse(feature.stayBookingEnabled());
        assertEquals(false,feature.state("stay-booking").get("enabled"));
        assertEquals(422,assertThrows(ServiceException.class,()->feature.saveFeature("stay-booking",true,false,false,"fixture")).getCode());
        feature.saveFeature("stay-booking",true,false,true,"fixture");
        assertTrue(new KgCommunityFeature(db).stayBookingEnabled());assertFalse(feature.wishEnabled());assertFalse(feature.enabled());
        assertEquals(409,assertThrows(ServiceException.class,()->feature.saveFeature("stay-booking",false,false,false,"stale")).getCode());
        feature.saveFeature("stay-booking",false,true,false,"fixture-close");assertFalse(feature.stayBookingEnabled());
        for(String bad:Arrays.asList("TRUE","1"," true ","")){db.update("update sys_config set config_value=:v where config_key=:k",p("v",bad,"k",KgCommunityFeature.STAY_KEY));assertFalse(feature.stayBookingEnabled());}
    }
    @Test void permissionAndPublicResponse() {
        com.ruoyi.kangaroo.controller.admin.KgSocialFeatureAdminController admin=context.getBean(com.ruoyi.kangaroo.controller.admin.KgSocialFeatureAdminController.class);
        KgFeatureAdminController.Change change=new KgFeatureAdminController.Change();change.enabled=true;change.expectedEnabled=false;change.categoryConfirmed=true;
        assertThrows(AccessDeniedException.class,()->admin.state("stay-booking",new MockHttpServletResponse()));
        assertThrows(AccessDeniedException.class,()->admin.save("stay-booking",change));
        context.getBean(Permission.class).allowed=true;assertEquals(200,admin.save("stay-booking",change).get("code"));
        MockHttpServletResponse response=new MockHttpServletResponse();java.util.Map<?,?> data=(java.util.Map<?,?>)new AppFeatureController(feature).features(response).get("data");
        assertEquals(true,data.get("stayBookingEnabled"));assertEquals("no-store",response.getHeader("Cache-Control"));
    }
    @Test void resolvedPublicHandlersCloseAndReopen() throws Exception {
        CommunityFeatureInterceptor gate=new CommunityFeatureInterceptor(feature);AppStayController controller=new AppStayController();
        java.util.Set<String> historical=new java.util.HashSet<>(Arrays.asList("mine","current","order","cancel","enrollActivity","refundPreview","refund","refreshCode","reportComment"));
        for(Method m:AppStayController.class.getDeclaredMethods()) {
            if(!java.lang.reflect.Modifier.isPublic(m.getModifiers()))continue;
            HandlerMethod handler=new HandlerMethod(controller,m);MockHttpServletResponse res=new MockHttpServletResponse();
            if(historical.contains(m.getName())) assertTrue(gate.preHandle(new MockHttpServletRequest(),res,handler),m.getName());
            else {assertEquals(423,assertThrows(ServiceException.class,()->gate.preHandle(new MockHttpServletRequest(),res,handler),m.getName()).getCode());assertEquals("no-store",res.getHeader("Cache-Control"));}
        }
        feature.saveFeature("stay-booking",true,false,true,"fixture");
        for(Method m:AppStayController.class.getDeclaredMethods())if(java.lang.reflect.Modifier.isPublic(m.getModifiers()))assertTrue(gate.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),new HandlerMethod(controller,m)));
        for(Class<?> type:Arrays.asList(AppStayAfterRefundController.class,AppPayController.class)) {
            Object bean=org.mockito.Mockito.mock(type);
            for(Method m:type.getDeclaredMethods()) if(java.lang.reflect.Modifier.isPublic(m.getModifiers()))assertTrue(gate.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),new HandlerMethod(bean,m)));
        }
    }
    @Test void roomMediaAndReviewsAreClosedToo() {
        CommunityFeatureInterceptor gate=new CommunityFeatureInterceptor(feature);
        for(Class<?> type:Arrays.asList(AppStayReviewController.class,AppStayDiaryController.class,AppStayCommentController.class)) {
            Object bean=org.mockito.Mockito.mock(type);
            for(Method m:type.getDeclaredMethods())if(java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                HandlerMethod handler=new HandlerMethod(bean,m);
                if(Arrays.asList("remove","report").contains(m.getName()))assertTrue(gate.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler));
                else assertEquals(423,assertThrows(ServiceException.class,()->gate.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler)).getCode());
            }
        }
    }
    @Test void directOrderQuoteAndPaymentCreationStopBeforeWriting() {
        com.ruoyi.kangaroo.ext.stay.KgStayOrderService orders=new com.ruoyi.kangaroo.ext.stay.KgStayOrderService();
        com.ruoyi.kangaroo.ext.stay.KgStayBrowseService browse=new com.ruoyi.kangaroo.ext.stay.KgStayBrowseService();
        com.ruoyi.kangaroo.ext.pay.KgPayService pay=new com.ruoyi.kangaroo.ext.pay.KgPayService();
        for(Object svc:Arrays.asList(orders,browse,pay))org.springframework.test.util.ReflectionTestUtils.setField(svc,"db",db);
        long before=db.count("select count(*) from kg_stay_order",p()),payments=db.count("select count(*) from kg_pay_order",p());
        assertEquals(423,assertThrows(ServiceException.class,()->orders.create(99301L,p())).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->browse.quote(1L,"2026-12-01","2026-12-08",1,null)).getCode());
        assertEquals(423,assertThrows(ServiceException.class,()->pay.createForNewBusiness("stay_order",1L,99301L,1L,100,"隔离测试")).getCode());
        assertEquals(before,db.count("select count(*) from kg_stay_order",p()));assertEquals(payments,db.count("select count(*) from kg_pay_order",p()));
    }
    @Test void pendingPaymentCannotStartButOtherPaymentAndOwnershipRulesRemain() {
        com.ruoyi.kangaroo.ext.pay.KgPayService pay=new com.ruoyi.kangaroo.ext.pay.KgPayService();
        com.ruoyi.kangaroo.config.KangarooProperties props=new com.ruoyi.kangaroo.config.KangarooProperties();props.setPayMode("mock");
        org.springframework.test.util.ReflectionTestUtils.setField(pay,"db",db);org.springframework.test.util.ReflectionTestUtils.setField(pay,"properties",props);
        String no="Pstaygate"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,14);
        db.insert("kg_pay_order",p("pay_no",no,"biz_type","stay_order","biz_id",1L,"app_user_id",99301L,"store_id",1L,"amount",100L,"description","隔离测试","channel","mock","status","pending","del_flag","0"));
        assertEquals(423,assertThrows(ServiceException.class,()->pay.prepay(no,99301L)).getCode());
        assertEquals(404,assertThrows(ServiceException.class,()->pay.prepay(no,99302L)).getCode());
        assertEquals("pending",pay.queryOwned(no,99301L).get("status"));
        feature.saveFeature("stay-booking",true,false,true,"fixture");assertEquals("mock",pay.prepay(no,99301L).get("channel"));
        feature.saveFeature("stay-booking",false,true,false,"fixture");db.update("update kg_pay_order set biz_type='activity_signup' where pay_no=:n",p("n",no));assertEquals("mock",pay.prepay(no,99301L).get("channel"));
    }
}
