package com.ruoyi.kangaroo.ext.community;

import java.lang.reflect.Method;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.controller.app.*;
import com.ruoyi.kangaroo.controller.admin.*;
import com.ruoyi.kangaroo.security.CommunityFeatureInterceptor;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

class KgRecruitmentFeatureTest {
    KgJdbc db; KgCommunityFeature feature; AnnotationConfigApplicationContext context;
    @Configuration @EnableGlobalMethodSecurity(prePostEnabled=true) @EnableTransactionManagement
    static class Config {
        @Bean(name="ss") Permission permission(){return new Permission();}
        @Bean PlatformTransactionManager transactionManager(DataSource source){return new DataSourceTransactionManager(source);}
    }
    public static class Permission { public boolean hasPermi(String key){return false;} }
    @BeforeEach void setup(){
        String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        DataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(source);db.update("delete from sys_config",p());
        db.insert("sys_config",p("config_name","lock fixture","config_key","fixture","config_value","false","config_type","N"));
        context=new AnnotationConfigApplicationContext();context.register(Config.class);context.registerBean(DataSource.class,()->source);context.registerBean(KgJdbc.class,()->db);context.registerBean(KgCommunityFeature.class);context.registerBean(KgSocialFeatureAdminController.class);context.refresh();feature=context.getBean(KgCommunityFeature.class);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("fixture","",Collections.emptyList()));
    }
    @AfterEach void close(){org.springframework.security.core.context.SecurityContextHolder.clearContext();if(context!=null)context.close();}
    @Test void strictPersistentSwitchWithConcurrencyAndQualificationCheck(){
        assertFalse(feature.recruitmentEnabled());assertEquals(false,feature.state("recruitment").get("enabled"));
        assertEquals(422,assertThrows(ServiceException.class,()->feature.saveFeature("recruitment",true,false,false,"test")).getCode());
        feature.saveFeature("recruitment",true,false,true,"test");assertTrue(new KgCommunityFeature(db).recruitmentEnabled());assertFalse(feature.stayBookingEnabled());assertFalse(feature.enabled());
        assertEquals(409,assertThrows(ServiceException.class,()->feature.saveFeature("recruitment",false,false,false,"stale")).getCode());
        feature.saveFeature("recruitment",false,true,false,"test");
        for(String value:Arrays.asList("TRUE","1"," true ","")){db.update("update sys_config set config_value=:v where config_key=:k",p("v",value,"k",KgCommunityFeature.RECRUITMENT_KEY));assertFalse(feature.recruitmentEnabled());}
    }
    @Test void publicFlagsAndAdminAuthorization(){
        MockHttpServletResponse res=new MockHttpServletResponse();Map<?,?> flags=(Map<?,?>)new AppFeatureController(feature).features(res).get("data");assertEquals(6,flags.size());assertEquals(false,flags.get("recruitmentEnabled"));assertEquals("no-store",res.getHeader("Cache-Control"));
        KgSocialFeatureAdminController admin=context.getBean(KgSocialFeatureAdminController.class);
        assertThrows(AccessDeniedException.class,()->admin.state("recruitment",res));
        KgFeatureAdminController.Change change=new KgFeatureAdminController.Change();change.enabled=true;change.expectedEnabled=false;change.categoryConfirmed=true;
        assertThrows(AccessDeniedException.class,()->admin.save("recruitment",change));
    }
    void handler(Class<?> type,String name,boolean closed){
        Object bean=mock(type);CommunityFeatureInterceptor gate=new CommunityFeatureInterceptor(feature);int found=0;
        for(Method m:type.getDeclaredMethods())if(m.getName().equals(name)&&java.lang.reflect.Modifier.isPublic(m.getModifiers())){
            found++;MockHttpServletResponse response=new MockHttpServletResponse();HandlerMethod h=new HandlerMethod(bean,m);MockHttpServletRequest req=new MockHttpServletRequest();req.setRequestURI("/alias/encoded/path");
            if(closed){assertEquals(423,assertThrows(ServiceException.class,()->gate.preHandle(req,response,h),type+"."+name).getCode());assertEquals("no-store",response.getHeader("Cache-Control"));}
            else assertTrue(gate.preHandle(req,response,h));
        }
        assertTrue(found>0,type+"."+name);
    }
    @Test void allJobTypesPublicHandlersAndNewWritesAreClosedRegardlessOfUrl(){
        for(String name:Arrays.asList("list","detail","cities","kinds","diary","favorites","favorite"))handler(AppJobController.class,name,true);
        handler(AppApplicationController.class,"submit",true);
        for(String name:Arrays.asList("match","search","team","joinTeam","resume","opportunities","addSub","editSub"))handler(AppUserExtController.class,name,true);
        for(String name:Arrays.asList("createJob","batchJobs","shareJob","adjust","urgent"))handler(AppStaffOpsController.class,name,true);
        for(String name:Arrays.asList("onlineCreate","talentSearch","talent","invite"))handler(AppStaffExtController.class,name,true);
        for(String name:Arrays.asList("merchantSubmit","merchantResubmit","dispatchedPublish","servicePublish"))handler(AppStaffMerchantRequestController.class,name,true);
        for(String name:Arrays.asList("forward","forwardStores","resumePoster","sharedResume"))handler(AppWorkbenchController.class,name,true);
        handler(AppFollowIncomeController.class,"storeJobs",true);
        feature.saveFeature("recruitment",true,false,true,"test");handler(AppJobController.class,"list",false);handler(AppApplicationController.class,"submit",false);
    }
    @Test void historicalFulfillmentRefundInsuranceAndAuthenticationHandlersRemain(){
        for(Class<?> type:Arrays.asList(AppOnlineController.class,AppPayController.class,AppStayAfterRefundController.class))
            for(Method m:type.getDeclaredMethods())if(java.lang.reflect.Modifier.isPublic(m.getModifiers()))handler(type,m.getName(),false);
        for(String n:Arrays.asList("withdraw","detail","mine"))handler(AppApplicationController.class,n,false);
        for(String n:Arrays.asList("insuranceApply","insuranceRegisterPolicy"))handler(AppStaffOpsController.class,n,false);
        handler(AppStaffExtController.class,"onlinePayout",false);handler(AppUserExtController.class,"removeExp",false);
    }
    @Test void newApplicationsAndMatchingStopBeforeAnyWriteAndJobPushStops(){
        com.ruoyi.kangaroo.service.impl.KgApplicationServiceImpl service=new com.ruoyi.kangaroo.service.impl.KgApplicationServiceImpl();ReflectionTestUtils.setField(service,"kgJdbcForQuota",db);
        assertEquals(423,assertThrows(ServiceException.class,()->service.submit(null,91L,"fixture")).getCode());
        com.ruoyi.kangaroo.ext.user.KgHomeService home=new com.ruoyi.kangaroo.ext.user.KgHomeService();ReflectionTestUtils.setField(home,"db",db);
        assertEquals(423,assertThrows(ServiceException.class,()->home.match(91L,p())).getCode());
        assertTrue(home.jobsOfTypes("",Arrays.asList("parttime","fulltime","volunteer","online"),10).isEmpty());
        ReflectionTestUtils.setField(home,"activities",mock(KgActivityService.class));
        Map<String,Object> feed=home.feed(null,"");assertEquals(Collections.emptyList(),feed.get("jobs"));
        com.ruoyi.kangaroo.ext.user.KgUserHubService hub=new com.ruoyi.kangaroo.ext.user.KgUserHubService();ReflectionTestUtils.setField(hub,"db",db);hub.pushInstant();hub.pushDaily();
        // Only sys_config exists in the fixture DB: an unexpected business write/query fails this test.
    }
}
