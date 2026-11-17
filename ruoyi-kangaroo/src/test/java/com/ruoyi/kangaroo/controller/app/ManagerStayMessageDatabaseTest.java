package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.kangaroo.domain.*;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.*;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.mapper.*;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.service.*;
import com.ruoyi.kangaroo.service.impl.KgMessageServiceImpl;

/** Real MySQL mapper and HTTP identity refresh; only the token store and outgoing transport are doubled. */
class ManagerStayMessageDatabaseTest {
    static KgJdbc db; static KgMessageMapper mapper; static AppIdentityLoader identities;
    static CityLeadScope cityLeads; static CityGrantScope cityGrants;
    static KgMessageServiceImpl service;
    AppLoginUser session; AppMessageController controller; MockMvc mvc;

    @BeforeAll static void init() throws Exception {
        String name=System.getenv("KG_MESSAGE_SCOPE_TEST_DB");
        assertNotNull(name); assertTrue(name.matches("kg_message_scope_[a-f0-9]{32}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+
            "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MESSAGE_SCOPE_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);
        Configuration cfg=new Configuration(new Environment("isolated",new SpringManagedTransactionFactory(),ds));
        cfg.getTypeAliasRegistry().registerAlias("KgMessage",KgMessage.class);
        cfg.getTypeAliasRegistry().registerAlias("KgAppUser",KgAppUser.class);
        cfg.getTypeAliasRegistry().registerAlias("KgStaffGrant",KgStaffGrant.class);
        for(String n:Arrays.asList("KgMessage","KgAppUser","KgStaffGrant")) {
            String r="mapper/kangaroo/"+n+"Mapper.xml";
            try(InputStream in=ManagerStayMessageDatabaseTest.class.getClassLoader().getResourceAsStream(r)) {
                new XMLMapperBuilder(in,cfg,r,cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sql=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        mapper=sql.getMapper(KgMessageMapper.class);
        KgCityService cities=new KgCityService(); ReflectionTestUtils.setField(cities,"db",db);
        cityGrants=new CityGrantScope(); ReflectionTestUtils.setField(cityGrants,"db",db); ReflectionTestUtils.setField(cityGrants,"cities",cities);
        cityLeads=new CityLeadScope(); ReflectionTestUtils.setField(cityLeads,"db",db); ReflectionTestUtils.setField(cityLeads,"cities",cities);
        ReflectionTestUtils.setField(cityLeads,"cityGrants",cityGrants);
        identities=new AppIdentityLoader(sql.getMapper(KgAppUserMapper.class),sql.getMapper(KgStaffGrantMapper.class));
        ReflectionTestUtils.setField(identities,"cityGrants",cityGrants); ReflectionTestUtils.setField(identities,"cityLeads",cityLeads);
        service=new KgMessageServiceImpl(); ReflectionTestUtils.setField(service,"kgMessageMapper",mapper);
    }

    @BeforeEach void setup() {
        RolePhase.set(RolePhase.NEW); AppUserContext.remove();
        for(String t:Arrays.asList("kg_message","kg_merchant_request","kg_stay_order","kg_shop_order","kg_staff_grant","kg_store","kg_app_user","kg_activity","kg_role_switch")) db.update("delete from "+t,p());
        db.insert("kg_role_switch",p("id",1,"phase","new","create_time",new Date(),"update_time",new Date()));
        for(long u=1;u<=12;u++) db.insert("kg_app_user",p("user_id",u,"openid","synthetic-message-"+u,"nickname","隔离测试"+u));
        for(long s=1;s<=3;s++) {
            db.insert("kg_store",p("store_id",s,"store_name","隔离门店"+s,"city",s==3?"丽江":"平潭"));
            db.insert("kg_stay_order",p("order_id",s,"order_no","S_FIXTURE_"+s,"stay_id",s,"store_id",s,"room_type_id",s,
                "app_user_id",10L,"check_in","2026-12-01","check_out","2026-12-08","nights",7,"guest_name","测试","guest_phone","13800000000","pay_fen",7000));
            db.insert("kg_shop_order",p("order_id",s,"order_no","P_FIXTURE_"+s,"service_id",s,"store_id",s,"stay_order_no","S_FIXTURE_"+s,
                "app_user_id",10L,"unit_fen",100,"pay_fen",100,"user_name","测试","user_phone","13800000000","use_date","2026-12-02","time_slot","10:00"));
        }
        db.insert("kg_activity",p("activity_id",1L,"store_id",1L,"source","store","title","活动测试","start_time","2026-12-01 10:00:00"));
        session=new AppLoginUser(); session.setUserId(1L);
        AppTokenService tokens=mock(AppTokenService.class); when(tokens.getLoginUser(any())).thenReturn(session);
        AppAuthInterceptor auth=new AppAuthInterceptor(); ReflectionTestUtils.setField(auth,"appTokenService",tokens); ReflectionTestUtils.setField(auth,"identities",identities);
        controller=new AppMessageController(); ReflectionTestUtils.setField(controller,"messageCities",cityLeads);
        ReflectionTestUtils.setField(controller,"kgMessageService",service); ReflectionTestUtils.setField(controller,"messageMapper",mapper);
        mvc=MockMvcBuilders.standaloneSetup(controller).addInterceptors(auth).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void cleanup() {AppUserContext.remove();RolePhase.set(RolePhase.LEGACY);}
    void grant(long uid,long store,String role) {db.insert("kg_staff_grant",p("app_user_id",uid,"store_id",store,"role_code",role));}
    void select(String key) {identities.reload(session);AppUserContext.set(session);controller.selectMessageScope(key);}
    void notice(long id,String mode,String category,String type,long biz,String route) {
        db.insert("kg_message",p("msg_id",id,"recipient_id",1L,"mode",mode,"category",category,"title","历史测试消息","content","只含合成数据",
            "biz_type",type,"biz_id",biz,"route",route,"read_flag","0","create_time","2026-01-01 12:00:00"));
    }
    List<Long> ids(String mode) {return mapper.selectAppMessagePage(1L,mode,null,null,0,50).stream().map(KgMessage::getMsgId).collect(Collectors.toList());}
    void fixtures() {
        notice(1,"staff","order","stay_order",1,"/pages/staff/stay-orders?orderNo=S_FIXTURE_1");
        notice(2,"staff","order","stay_order",2,"/pages/staff/stay-orders?orderNo=S_FIXTURE_2");
        notice(3,"staff","order","service_order",1,"/pages/staff/insurance?s=4");
        notice(4,"staff","order","activity",1,"/pages/staff/activities?id=1");
        notice(5,"staff","contract","insurance",1,"/pages/staff/insurance?s=2");
        notice(6,"staff","order","refund_trouble",1,null);
        notice(7,"user","order","stay_order",1,"/pages/stays/order?orderNo=S_FIXTURE_1");
    }

    @Test void subscriptionWorkerRebuildsCurrentRecipientScopes() {
        grant(1,1,"manager");grant(1,2,"keeper");fixtures();AppUserContext.remove();
        com.ruoyi.kangaroo.ext.user.KgSubscribeVisibility visibility=new com.ruoyi.kangaroo.ext.user.KgSubscribeVisibility(identities,mapper);
        assertNull(visibility.authorizedScope(1L,1L,"staff"));
        assertEquals("store:2:keeper",visibility.authorizedScope(1L,2L,"staff"));
        assertEquals("user",visibility.authorizedScope(1L,7L,"user"));assertNull(AppUserContext.get());
        db.update("update kg_staff_grant set status='1' where app_user_id=1 and store_id=2",p());
        assertNull(visibility.authorizedScope(1L,2L,"staff"));
    }

    @Test void subscriptionLandingCannotReadAnotherRoleOrRecipient() throws Exception {
        grant(1,1,"manager");grant(1,2,"keeper");fixtures();
        mvc.perform(get("/app/message/subscription/2").param("mode","staff").header("X-Message-Scope","store:1:manager"))
            .andExpect(jsonPath("$.code").value(404));
        mvc.perform(get("/app/message/subscription/2").param("mode","staff").header("X-Message-Scope","store:2:keeper"))
            .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.id").value(2));
        session.setUserId(2L);
        mvc.perform(get("/app/message/subscription/7").param("mode","user").header("X-Message-Scope","user"))
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test void managerHistoricalListCountsAndMutationsHideOnlyLodging() throws Exception {
        grant(1,1,"manager");fixtures();select("store:1:manager");
        assertEquals(Arrays.asList(5L,4L),ids("staff"));assertEquals(2,mapper.selectAppMessages(1L,"staff",null).size());
        assertEquals(2,mapper.countUnread(1L,"staff"));
        assertEquals(2L,mapper.countByCategory(1L,"staff").stream().mapToLong(x->((Number)x.get("unread")).longValue()).sum());
        assertEquals(2L,mapper.countByBucket(1L,"staff").stream().mapToLong(x->((Number)x.get("unread")).longValue()).sum());
        assertEquals(Arrays.asList(4L),mapper.selectUnreadIds(1L,"staff","order",99L,50));
        assertEquals(0,mapper.markRead(1L,1L));assertNull(mapper.selectOwnMessageForDelete(1L,1L,"staff"));
        assertEquals(0,mapper.deleteOwnMessage(1L,1L,"staff",null));assertEquals(1,mapper.markAllRead(1L,"staff","order",99L));
        assertEquals("0",db.one("select read_flag from kg_message where msg_id=1",p()).get("readFlag"));
        assertEquals(1,mapper.countUnread(1L,"user"));assertEquals(Arrays.asList(7L),ids("user"));
        mvc.perform(get("/app/message/list").param("mode","staff").param("bucket","order").header("X-Message-Scope","store:1:manager"))
            .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.rows.length()").value(1)).andExpect(jsonPath("$.rows[0].id").value(4));
        assertNull(AppUserContext.get());
    }

    @Test void keeperSelectedScopeAndPersonalModeStayIndependent() throws Exception {
        grant(1,1,"keeper");grant(1,2,"keeper");fixtures();select("store:1:keeper");
        assertEquals(Arrays.asList(5L,4L,3L,1L),ids("staff"));
        assertEquals(0,mapper.markRead(2L,1L));assertEquals(0,mapper.deleteOwnMessage(2L,1L,"staff",null));
        assertEquals(3,mapper.markAllRead(1L,"staff","order",99L));
        select("store:2:keeper");assertEquals(Arrays.asList(5L,2L),ids("staff"));assertEquals(2,mapper.countUnread(1L,"staff"));
        select("user");assertEquals(Arrays.asList(7L),ids("user"));assertFalse(ids("staff").contains(1L));
        mvc.perform(get("/app/message/unread").param("mode","user").header("X-Message-Scope","user"))
            .andExpect(jsonPath("$.data.unread").value(1));
    }

    @Test void mixedRolesNeedExplicitLodgingIdentityAndCannotBorrowAnotherStore() throws Exception {
        grant(1,1,"manager");grant(1,2,"keeper");fixtures();
        for(String key:Arrays.asList(null,"","user","store:1:manager")) {
            select(key);assertFalse(ids("staff").contains(1L));assertFalse(ids("staff").contains(2L));
        }
        select("store:2:keeper");assertTrue(ids("staff").contains(2L));assertFalse(ids("staff").contains(1L));
        mvc.perform(get("/app/message/summary").param("mode","staff").header("X-Message-Scope","store:1:keeper"))
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test void sameStoreCombinedSnapshotStillUsesSelectedRole() {
        grant(1,1,"keeper");fixtures();select("store:1:keeper");
        StaffGrantView extra=new StaffGrantView();extra.setStoreId(1L);extra.setRoleCode("manager");extra.setActive(true);
        session.getStaffGrants().add(extra);controller.selectMessageScope("store:1:manager");assertFalse(ids("staff").contains(1L));
        controller.selectMessageScope("store:1:keeper");assertTrue(ids("staff").contains(1L));
    }

    @Test void legacyKeeperRemainsScopedButManagerCannotUseMissingHeader() {
        grant(1,1,"keeper");fixtures();select(null);assertTrue(ids("staff").contains(1L));assertFalse(ids("staff").contains(2L));
        db.update("update kg_staff_grant set role_code='manager' where app_user_id=1",p());select(null);assertFalse(ids("staff").contains(1L));
    }

    @Test void staleTokenRoleIsReloadedAndRevocationImmediatelyStopsHttpReadsAndWrites() throws Exception {
        grant(1,1,"keeper");fixtures();select("store:1:keeper");assertTrue(ids("staff").contains(1L));
        db.update("update kg_staff_grant set role_code='manager' where app_user_id=1",p());
        mvc.perform(get("/app/message/list").param("mode","staff").header("X-Message-Scope","store:1:keeper"))
            .andExpect(jsonPath("$.code").value(403));assertNull(AppUserContext.get());
        mvc.perform(post("/app/message/1/read").header("X-Message-Scope","store:1:manager"))
            .andExpect(jsonPath("$.data.updated").value(0));
        mvc.perform(post("/app/message/1/delete").param("mode","staff").header("X-Message-Scope","store:1:manager"))
            .andExpect(jsonPath("$.data.updated").value(0));
        mvc.perform(post("/app/message/read-all").param("mode","staff").param("bucket","order").param("maxId","99").header("X-Message-Scope","store:1:manager"))
            .andExpect(jsonPath("$.data.updated").value(1)).andExpect(jsonPath("$.data.unreadIds.length()").value(1));
        assertEquals("0",db.one("select read_flag from kg_message where msg_id=1",p()).get("readFlag"));
    }

    @Test void legacyRouteOnlyMessagesResolveAnExactOrderNumberAndStore() {
        grant(1,1,"keeper");fixtures();
        notice(8,"staff","order","legacy",999,"/pages/staff/stay-orders?orderNo=S_FIXTURE_1&storeId=1");
        notice(9,"staff","order","legacy",999,"/pages/staff/stay-orders?orderNo=S_FIXTURE_2&storeId=1");
        notice(10,"staff","order","legacy",999,"/pages/staff/stay-orders?orderNo=S_FIXTURE_10");
        select("store:1:keeper");assertTrue(ids("staff").contains(8L));assertFalse(ids("staff").contains(9L));assertFalse(ids("staff").contains(10L));
    }

    @Test void keeperWithOnlineRoleCanStillReadAuthorizedLodgingMessages() {
        grant(1,1,"keeper");grant(1,2,"online_staff");fixtures();select("store:1:keeper");
        assertTrue(ids("staff").contains(1L));assertTrue(ids("staff").contains(3L));
        select("store:2:online_staff");assertFalse(ids("staff").contains(1L));assertFalse(ids("staff").contains(2L));
    }

    @Test void merchantCanReadAndRemoveOnlyAuthorizedStoreOrders() {
        grant(1,1,"merchant");fixtures();select("store:1:merchant");
        assertTrue(ids("staff").contains(1L));assertTrue(ids("staff").contains(3L));assertFalse(ids("staff").contains(2L));
        assertEquals(0,mapper.deleteOwnMessage(2L,1L,"staff",null));assertEquals(1,mapper.deleteOwnMessage(1L,1L,"staff",null));
        assertEquals(1,mapper.countUnread(1L,"user"));
    }

    @Test void cityLeadHasScopedOrdersAndHistoricalFinancialCopies() {
        grant(1,1,"city_lead");fixtures();select("store:2:city_lead");assertTrue(ids("staff").contains(2L));assertTrue(ids("staff").contains(6L));assertFalse(ids("staff").contains(1L));
        notice(8,"staff","order","stay_order",3,"/pages/staff/stay-orders?orderNo=S_FIXTURE_3");
        assertFalse(ids("staff").contains(8L));
        db.update("update kg_staff_grant set role_code='manager' where app_user_id=1",p());select("store:1:manager");assertFalse(ids("staff").contains(6L));
    }

    @Test void outgoingLodgingUsesRealStoreRoleMatrixAndDeduplicatesCityLeads() {
        grant(1,1,"manager");grant(2,1,"keeper");grant(3,1,"merchant");grant(4,1,"online_staff");
        grant(5,1,"merchant_service");grant(6,1,"city_lead");grant(7,3,"keeper");grant(8,1,"keeper");grant(9,1,"keeper");
        db.update("update kg_app_user set status='1' where user_id=8",p());db.update("update kg_staff_grant set status='1' where app_user_id=9",p());
        StaffSupport support=new StaffSupport();IKgMessageService transport=mock(IKgMessageService.class);
        ReflectionTestUtils.setField(support,"db",db);ReflectionTestUtils.setField(support,"messages",transport);
        ReflectionTestUtils.setField(support,"cityLeads",cityLeads);ReflectionTestUtils.setField(support,"cityGrants",cityGrants);
        assertEquals(Arrays.asList(2L,3L,6L),support.stayRecipients(1L));
        assertEquals(3,support.notifyStore(1L,"order","退款","合成退款","stay_order",1L,"/pages/staff/stay-orders?orderNo=S_FIXTURE_1"));
        for(long uid:new long[]{2,3,6}) verify(transport,times(1)).send(eq(uid),eq("staff"),eq("order"),eq("退款"),anyString(),eq("stay_order"),eq(1L),anyString());
        verify(transport,never()).send(eq(1L),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        assertTrue(StaffSupport.stayOrderNotice("service_order",null));assertFalse(StaffSupport.stayOrderNotice("insurance","/pages/staff/insurance?s=2"));
        assertFalse(StaffSupport.stayOrderNotice("activity_signup","/pages/staff/activities?id=1"));
    }

    void serviceCityFixtures() {
        db.insert("kg_store",p("store_id",4L,"store_name","本人商家门店","city","平潭"));
        grant(1,1,"merchant_service");grant(1,2,"merchant_service");grant(1,3,"merchant_service");grant(1,4,"merchant");
        for(long id=1;id<=4;id++) {
            db.insert("kg_merchant_request",p("request_id",id,"request_type","managed","store_id",id,
                "merchant_user_id",id==4?1L:10L,"city",id==3?"丽江":"平潭","status","forwarded","create_time",new Date()));
            notice(100+id,"staff","apply","merchant_request",id,"/pages/staff/service?id="+id+"&storeId="+id);
        }
    }

    @Test void serviceCityInboxAggregatesAuthorizedStoresAndSeparatesMerchantAndOtherCity() throws Exception {
        serviceCityFixtures();select("service-city:%E5%B9%B3%E6%BD%AD");
        assertEquals(Arrays.asList(102L,101L),ids("staff"));assertEquals(2,mapper.countUnread(1L,"staff"));
        assertEquals(0,mapper.markRead(103L,1L));assertEquals(0,mapper.deleteOwnMessage(104L,1L,"staff",null));
        mvc.perform(get("/app/message/list").param("mode","staff").header("X-Message-Scope","service-city:%E5%B9%B3%E6%BD%AD"))
            .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.rows.length()").value(2));
        select("store:4:merchant");assertEquals(Collections.singletonList(104L),ids("staff"));
        select("store:1:merchant_service");assertEquals(Collections.singletonList(101L),ids("staff"));
        select("service-city:%E4%B8%BD%E6%B1%9F");assertEquals(Collections.singletonList(103L),ids("staff"));
    }

    @Test void serviceCityScopeUsesFreshGrantsForReadsBulkWritesAndSubscriptionDelivery() throws Exception {
        serviceCityFixtures();select("service-city:%E5%B9%B3%E6%BD%AD");
        com.ruoyi.kangaroo.ext.user.KgSubscribeVisibility visibility=new com.ruoyi.kangaroo.ext.user.KgSubscribeVisibility(identities,mapper);
        assertEquals("service-city:%E5%B9%B3%E6%BD%AD",visibility.authorizedScope(1L,102L,"staff"));
        db.update("update kg_staff_grant set status='1' where app_user_id=1 and store_id=2",p());
        mvc.perform(get("/app/message/list").param("mode","staff").header("X-Message-Scope","service-city:%E5%B9%B3%E6%BD%AD"))
            .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].id").value(101));
        mvc.perform(post("/app/message/102/read").header("X-Message-Scope","service-city:%E5%B9%B3%E6%BD%AD"))
            .andExpect(jsonPath("$.data.updated").value(0));
        mvc.perform(post("/app/message/102/delete").param("mode","staff").header("X-Message-Scope","service-city:%E5%B9%B3%E6%BD%AD"))
            .andExpect(jsonPath("$.data.updated").value(0));
        mvc.perform(post("/app/message/read-all").param("mode","staff").param("maxId","999").header("X-Message-Scope","service-city:%E5%B9%B3%E6%BD%AD"))
            .andExpect(jsonPath("$.data.updated").value(1));
        assertNull(visibility.authorizedScope(1L,102L,"staff"));
        db.update("update kg_staff_grant set status='1' where app_user_id=1 and store_id=1",p());
        mvc.perform(get("/app/message/unread").param("mode","staff").header("X-Message-Scope","service-city:%E5%B9%B3%E6%BD%AD"))
            .andExpect(jsonPath("$.code").value(403));assertNull(AppUserContext.get());
    }

    @Test void cityMessageHeaderCannotConvertMerchantOrCityLeadIntoServiceRole() throws Exception {
        grant(1,1,"merchant");grant(1,3,"city_lead");
        for(String scope:Arrays.asList("service-city:%E5%B9%B3%E6%BD%AD","service-city:%E4%B8%BD%E6%B1%9F","service-city:%ZZ")) {
            mvc.perform(get("/app/message/summary").param("mode","staff").header("X-Message-Scope",scope))
                .andExpect(jsonPath("$.code").value(403));assertNull(AppUserContext.get());
        }
    }
}
