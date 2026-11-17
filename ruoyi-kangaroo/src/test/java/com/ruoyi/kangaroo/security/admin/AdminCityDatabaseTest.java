package com.ruoyi.kangaroo.security.admin;

import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

class AdminCityDatabaseTest {
    static KgJdbc db; static AdminCitySqlGuard guard; static DataSource ds;
    @BeforeAll static void init() {
        String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);guard=new AdminCitySqlGuard(ds);guard.init();ReflectionTestUtils.setField(db,"adminCityGuard",guard);
    }
    @BeforeEach void seed() {
        AdminCityContext.clear();
        for(String t:new String[]{"kg_stay_room_type","kg_stay","kg_application","kg_job","kg_store","kg_city"})db.update("delete from "+t,p());
        db.insert("kg_city",p("city_id",1,"name","平潭","full_name","平潭县"));
        db.insert("kg_city",p("city_id",2,"name","丽江","full_name","丽江市"));
        db.insert("kg_store",p("store_id",101,"store_name","平潭自营","city","平潭","op_type","direct"));
        db.insert("kg_store",p("store_id",102,"store_name","丽江合作","city","丽江","op_type","partner"));
        db.insert("kg_stay",p("stay_id",201,"store_id",101,"stay_name","平潭房源","city","平潭"));
        db.insert("kg_stay",p("stay_id",202,"store_id",102,"stay_name","丽江房源","city","丽江"));
        db.insert("kg_stay_room_type",p("room_type_id",301,"stay_id",201,"type_name","平潭房型","base_price_fen",1700));
        db.insert("kg_stay_room_type",p("room_type_id",302,"stay_id",202,"type_name","丽江房型","base_price_fen",1700));
        AdminCityContext.set(Arrays.asList(1L));
    }
    @AfterEach void clear(){AdminCityContext.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void listCountDetailAndExportCannotReadOtherCity(){
        assertEquals(1,db.count("select count(*) from kg_store",p()));
        assertEquals(101L,db.list("select * from kg_store order by store_id",p()).get(0).get("storeId"));
        assertNull(db.one("select * from kg_store where store_id=:id",p("id",102)));
        assertEquals(1L,db.page("select s.* from kg_stay s order by stay_id",p(),1,20).get("total"));
        assertEquals(1,db.list("select s.* from kg_stay s order by stay_id limit 10001",p()).size());
    }
    @Test void joinsSubqueriesUnionAndQualifiedColumnsStayScoped(){
        assertEquals(1,db.list("select s.stay_id,st.store_name,(select count(*) from kg_stay_room_type rt where rt.stay_id=s.stay_id) n from kg_stay s left join kg_store st on st.store_id=s.store_id",p()).size());
        assertEquals(2,db.list("select store_id id from kg_store union all select stay_id id from kg_stay",p()).size());
        assertEquals(1,db.list("select kg_store.store_id from kg_store where store_id in (:ids)",p("ids",Arrays.asList(101,102))).size());
    }
    @Test void updatesDeletesAndRoomParentEnforceScope(){
        assertEquals(0,db.updateRow("kg_store","store_id",102,p("store_name","不得修改")));
        assertEquals(1,db.updateRow("kg_store","store_id",101,p("store_name","允许修改")));
        assertEquals(0,db.update("delete from kg_stay_room_type where room_type_id=:id",p("id",302)));
        assertEquals(1,db.count("select count(*) from kg_stay_room_type",p()));
    }
    @Test void cannotMoveDataOrCreateInForeignCity(){
        assertThrows(ServiceException.class,()->db.updateRow("kg_store","store_id",101,p("city","丽江")));
        assertThrows(ServiceException.class,()->db.updateRow("kg_stay","stay_id",201,p("store_id",102)));
        assertThrows(ServiceException.class,()->db.insert("kg_stay_room_type",p("stay_id",202,"type_name","禁止","base_price_fen",1700)));
        long id=db.insert("kg_stay_room_type",p("stay_id",201,"type_name","允许","base_price_fen",1700));assertTrue(id>0);
    }
    @Test void emptyScopeDeniesAllBusinessRowsAndUnknownGlobalTablesFailClosed(){
        AdminCityContext.set(Collections.emptyList());assertEquals(0,db.count("select count(*) from kg_store",p()));
        assertThrows(ServiceException.class,()->db.list("select * from kg_wallet",p()));
        assertThrows(ServiceException.class,()->db.update("update kg_city set name='恶意' where city_id=2",p()));
    }
    @Test void multipleCitiesAndAllScopePreserveExistingAdminBehavior(){
        AdminCityContext.set(Arrays.asList(1L,2L));assertEquals(2,db.count("select count(*) from kg_store",p()));
        AdminCityContext.clear();assertEquals(2,db.count("select count(*) from kg_store",p()));
    }
    @Test void namedParameterInsertAndUpdateCannotChangeOwner(){
        assertThrows(ServiceException.class,()->db.update("insert into kg_store(store_name,city) values(:name,:city)",p("name","禁","city","丽江市")));
        assertEquals(1,db.update("insert into kg_store(store_name,city) values(:name,:city)",p("name","允许全称","city","平潭县")));
        assertThrows(ServiceException.class,()->db.update("update kg_store set city=concat('丽','江') where store_id=101",p()));
    }
    @Test void scopedForUpdatePreservesRowLock() {
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        tx.execute(status->{
            assertNotNull(db.one("select status from kg_stay where stay_id=201 for update",p()));
            java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<Boolean> blocked=worker.submit(()->{
                    try(java.sql.Connection c=ds.getConnection();java.sql.Statement st=c.createStatement()) {
                        st.execute("set innodb_lock_wait_timeout=1");
                        try {st.executeUpdate("update kg_stay set stay_name='must wait' where stay_id=201");return false;}
                        catch(java.sql.SQLException e){return e.getErrorCode()==1205;}
                    }
                });
                assertTrue(blocked.get(5,java.util.concurrent.TimeUnit.SECONDS));
            } catch(Exception e){throw new RuntimeException(e);} finally {worker.shutdownNow();}
            return null;
        });
    }
    public interface StoreMapper {
        @Select("select store_id from kg_store order by store_id") List<Long> list();
        @Update("update kg_store set city=#{city} where store_id=#{id}") int move(@Param("id")long id,@Param("city")String city);
        @Select("select count(*) from kg_store where store_id=#{id}") long count(long id);
    }
    @Test void mybatisAppliesBeforeCacheAndPreventsOwnerChange(){
        Configuration cfg=new Configuration(new Environment("isolated",new JdbcTransactionFactory(),ds));
        cfg.addMapper(StoreMapper.class);
        SqlSessionFactory factory=new SqlSessionFactoryBuilder().build(cfg);
        AdminCityMybatisRegistration registration=new AdminCityMybatisRegistration(Arrays.asList(factory),new AdminCityMybatisInterceptor(guard));
        registration.afterSingletonsInstantiated();registration.afterSingletonsInstantiated();assertEquals(1,cfg.getInterceptors().size());
        try(SqlSession session=factory.openSession(true)) {
            StoreMapper m=session.getMapper(StoreMapper.class);
            assertEquals(Arrays.asList(101L),m.list());assertEquals(0,m.count(102));
            assertThrows(Exception.class,()->m.move(101,"丽江"));
            AdminCityContext.set(Arrays.asList(2L));assertEquals(Arrays.asList(102L),m.list());
            AdminCityContext.clear();assertEquals(Arrays.asList(101L,102L),m.list());
            AdminCityContext.set(Arrays.asList(1L));assertEquals(Arrays.asList(101L),m.list());
        }
    }
    void login(long id) {
        com.ruoyi.common.core.domain.model.LoginUser login=new com.ruoyi.common.core.domain.model.LoginUser();
        login.setUserId(id);com.ruoyi.common.core.domain.entity.SysUser user=new com.ruoyi.common.core.domain.entity.SysUser();user.setUserId(id);user.setUserName("isolated_admin_"+id);login.setUser(user);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(login,null,Collections.emptyList()));
    }
    boolean request(AdminCityInterceptor i,String path) {
        return i.preHandle(new org.springframework.mock.web.MockHttpServletRequest("GET",path),new org.springframework.mock.web.MockHttpServletResponse(),new Object());
    }
    @Test void authorizationSaveAllMultiCityRevokeAndRequestBoundary() {
        AdminCityContext.clear();db.update("delete from kg_admin_city_grant",p());db.update("delete from kg_admin_city_scope",p());
        db.update("delete from sys_user where user_id=9901",p());db.insert("sys_user",p("user_id",9901,"user_name","scope_fixture","nick_name","隔离账号"));
        AdminCityScopeService scopes=new AdminCityScopeService(ds);AdminCityInterceptor interceptor=new AdminCityInterceptor(scopes);
        login(9901);assertFalse(scopes.all(9901));assertThrows(ServiceException.class,()->request(interceptor,"/kangaroo/store/list"));assertFalse(AdminCityContext.limited());
        assertThrows(ServiceException.class,()->scopes.save(9901,true,Collections.emptyList()));
        login(1);scopes.save(9901,false,Arrays.asList(1L,2L));assertEquals(Arrays.asList(1L,2L),scopes.cities(9901));
        login(9901);assertTrue(request(interceptor,"/kangaroo/store/list"));assertEquals(Arrays.asList(1L,2L),AdminCityContext.cities());
        interceptor.afterCompletion(null,null,null,null);assertFalse(AdminCityContext.limited());
        assertThrows(ServiceException.class,()->request(interceptor,"/system/user/list"));assertThrows(ServiceException.class,()->request(interceptor,"/common/download/resource"));
        assertTrue(request(interceptor,"/system/user/profile"));assertFalse(AdminCityContext.limited());
        login(1);scopes.save(9901,true,Collections.emptyList());login(9901);assertTrue(request(interceptor,"/kangaroo/store/list"));assertFalse(AdminCityContext.limited());
        login(1);scopes.save(9901,false,Collections.emptyList());login(9901);assertThrows(ServiceException.class,()->request(interceptor,"/kangaroo/store/list"));
        login(1);assertTrue(request(interceptor,"/kangaroo/store/list"));assertFalse(AdminCityContext.limited());
        assertThrows(ServiceException.class,()->scopes.save(9901,false,Arrays.asList(999999L)));
    }
}
