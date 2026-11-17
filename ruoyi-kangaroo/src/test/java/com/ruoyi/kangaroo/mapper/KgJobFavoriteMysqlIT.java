package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.InputStream;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.controller.app.AppJobController;
import com.ruoyi.kangaroo.domain.*;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.service.IKgJobService;

/** Real favorite controller and Mapper persistence, using a disposable database. */
@EnabledIfEnvironmentVariable(named="KG_TEST_DB_PASSWORD", matches=".+")
class KgJobFavoriteMysqlIT
{
    String database; JdbcTemplate root, sql; SqlSession session; SqlSessionFactory factory;
    AppJobController controller; KgJobFavoriteMapper mapper;
    @BeforeEach void setup() throws Exception
    {
        database="kg_scratch_"+UUID.randomUUID().toString().replace("-", "").substring(0,8);
        root=new JdbcTemplate(KgInboxMysqlIT.source(""));
        root.execute("create database "+database+" character set utf8mb4 collate utf8mb4_unicode_ci");
        DataSource ds=KgInboxMysqlIT.source(database);sql=new JdbcTemplate(ds);
        sql.execute("create table kg_job (job_id bigint primary key,store_id bigint,status varchar(32),del_flag char(1) default '0')");
        sql.execute("create table kg_store (store_id bigint primary key,status char(1) default '0',del_flag char(1) default '0')");
        sql.execute("create table kg_job_favorite (fav_id bigint primary key auto_increment,app_user_id bigint,job_id bigint,create_time datetime,unique key uk_fav_user_job(app_user_id,job_id))");
        sql.update("insert into kg_store(store_id) values(1)");sql.update("insert into kg_job(job_id,store_id,status) values(7,1,'published')");
        Configuration config=new Configuration(new Environment("favorite-it",new JdbcTransactionFactory(),ds));
        config.getTypeAliasRegistry().registerAlias("KgJobFavorite",KgJobFavorite.class);
        String resource="mapper/kangaroo/KgJobFavoriteMapper.xml";
        try(InputStream in=getClass().getClassLoader().getResourceAsStream(resource)){new XMLMapperBuilder(in,config,resource,config.getSqlFragments()).parse();}
        factory=new SqlSessionFactoryBuilder().build(config); session=factory.openSession(true);mapper=session.getMapper(KgJobFavoriteMapper.class);
        KgJob job=new KgJob();job.setJobId(7L);job.setStoreId(1L);job.setJobType("volunteer");job.setTitle("收藏接口测试");job.setStatus("published");
        IKgJobService jobs=mock(IKgJobService.class);when(jobs.requireJob(7L)).thenReturn(job);when(jobs.selectKgJobByJobId(7L)).thenReturn(job);
        controller=new AppJobController();ReflectionTestUtils.setField(controller,"kgJobService",jobs);ReflectionTestUtils.setField(controller,"kgJobFavoriteMapper",mapper);
        // 收藏列表按门店营业状态标「已结束招聘」：门店状态从库里现读
        com.ruoyi.kangaroo.service.IKgStoreService stores=mock(com.ruoyi.kangaroo.service.IKgStoreService.class);
        when(stores.selectKgStoreByStoreId(anyLong())).thenAnswer(inv->{Long id=inv.getArgument(0);KgStore s=new KgStore();s.setStoreId(id);
            s.setStatus(sql.queryForObject("select status from kg_store where store_id=?",String.class,id));return s;});
        ReflectionTestUtils.setField(controller,"kgStoreService",stores);
        login(101L);
    }
    void login(Long id){AppLoginUser user=new AppLoginUser();user.setUserId(id);AppUserContext.set(user);}
    @AfterEach void cleanup()
    {
        AppUserContext.remove();if(session!=null)session.close();
        if(root!=null&&database!=null&&database.matches("kg_scratch_[a-f0-9]{8}"))root.execute("drop database if exists "+database);
    }
    @Test void favoriteAndUnfavoritePersistAndMyFavoritesIsAccountScoped()
    {
        controller.favorite(7L,true);controller.favorite(7L,true);
        assertEquals(1,mapper.countByUserAndJob(101L,7L));
        try(SqlSession another=factory.openSession(true)){assertEquals(Collections.singletonList(7L),another.getMapper(KgJobFavoriteMapper.class).selectJobIdsByUser(101L));}
        List<?> saved=(List<?>)controller.favorites().get("data");assertEquals(1,saved.size());assertEquals(true,((Map<?,?>)saved.get(0)).get("favorite"));
        login(202L);assertTrue(((List<?>)controller.favorites().get("data")).isEmpty());controller.favorite(7L,true);
        login(101L);controller.favorite(7L,false);controller.favorite(7L,false);
        assertTrue(((List<?>)controller.favorites().get("data")).isEmpty());assertEquals(1,mapper.countByUserAndJob(202L,7L));
        // 2026-11-09：门店停用后收藏不再消失，留着写「已结束招聘」，方便取消收藏
        login(202L);sql.update("update kg_store set status='1' where store_id=1");session.clearCache();
        List<?> ended=(List<?>)controller.favorites().get("data");assertEquals(1,ended.size());
        assertEquals(true,((Map<?,?>)ended.get(0)).get("ended"));assertEquals("已结束招聘",((Map<?,?>)ended.get(0)).get("statusText"));
        controller.favorite(7L,false);assertEquals(0,mapper.countByUserAndJob(202L,7L));
    }
}
