package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgJobReviewService;

/** Disposable local MySQL fixture: no changes to application databases. */
@EnabledIfEnvironmentVariable(named="KG_TEST_DB_PASSWORD", matches=".+")
class KgJobReviewMysqlIT
{
    String database;
    JdbcTemplate root, sql;
    KgJobReviewService service;
    @BeforeEach void setup() throws Exception
    {
        database="kg_scratch_"+UUID.randomUUID().toString().replace("-", "").substring(0,8);
        root=new JdbcTemplate(KgInboxMysqlIT.source(""));
        root.execute("create database "+database+" character set utf8mb4 collate utf8mb4_unicode_ci");
        DataSource ds=KgInboxMysqlIT.source(database); sql=new JdbcTemplate(ds);
        sql.execute("create table kg_store (store_id bigint primary key,store_name varchar(64),status char(1) default '0',del_flag char(1) default '0')");
        sql.execute("create table kg_job (job_id bigint primary key,job_type varchar(32),store_id bigint default 1,recruit_store_id bigint null)");
        sql.execute("create table kg_application (app_id bigint primary key,job_id bigint,stay_days int)");
        sql.execute("create table kg_app_user (user_id bigint primary key,nickname varchar(64),avatar varchar(255))");
        sql.execute("create table kg_review (review_id bigint primary key,store_id bigint,biz_type varchar(32) default 'application',biz_id bigint,app_user_id bigint,rating int,dims varchar(128),content text,images text,videos text,video_covers text,anonymous char(1) default '0',reply text,create_time datetime default current_timestamp,status char(1) default '0',del_flag char(1) default '0')");
        sql.update("insert into kg_store (store_id,store_name) values (1,'本店'),(2,'其他门店'),(3,'无评价门店')");
        sql.update("insert into kg_job (job_id,job_type) values (7,'volunteer'),(8,'parttime')");
        sql.update("insert into kg_application values (1,7,30),(2,8,30)");
        sql.update("insert into kg_app_user values (9,'公开昵称','/avatar.jpg')");
        for(int i=1;i<=12;i++) sql.update("insert into kg_review (review_id,store_id,biz_id,app_user_id,rating,dims,content) values (?,1,1,9,?,'[5,4,3,2]',?)",i,i==3?2:5,i==1?"交通方便，氛围很好":"真实体验");
        sql.update("update kg_review set anonymous='1',dims='[1,2,3,4,5]',images='/a.jpg,/b.jpg' where review_id=1");
        sql.update("update kg_review set videos='/v.mp4',video_covers='/cover.jpg' where review_id=2");
        sql.update("update kg_review set create_time=date_sub(now(),interval 90 day) where review_id=3");
        sql.update("insert into kg_review (review_id,store_id,biz_id,rating,dims,status) values (13,1,1,1,'[1,1,1,1]','1')");
        sql.update("insert into kg_review (review_id,store_id,biz_id,rating,dims,del_flag) values (14,1,1,1,'[1,1,1,1]','2')");
        sql.update("insert into kg_review (review_id,store_id,biz_id,rating,dims) values (15,2,1,1,'[1,1,1,1]'),(17,1,2,1,'[1,1,1,1]')");
        sql.update("insert into kg_review (review_id,store_id,biz_id,rating,dims,biz_type) values (16,1,1,1,'[1,1,1,1,1]','stay_order')");
        service=new KgJobReviewService(); ReflectionTestUtils.setField(service,"db",new KgJdbc(ds));
    }
    @AfterEach void cleanup()
    { if(root!=null&&database!=null&&database.matches("kg_scratch_[a-f0-9]{8}"))root.execute("drop database if exists "+database); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> page) { return (List<Map<String,Object>>)page.get("rows"); }

    @Test void realSqlAggregatesAndFiltersOnlyPublishedReviewsForThisStoreAndType()
    {
        Map<String,Object> summary=service.summary(1L,"换工");
        assertEquals(12L,summary.get("count")); assertEquals("4.8",summary.get("avg").toString()); assertEquals(92L,summary.get("recommendRate"));
        assertEquals(2L,((Map<?,?>)summary.get("tabs")).get("media")); assertEquals(1L,((Map<?,?>)summary.get("tabs")).get("bad"));
        assertEquals(2,((List<?>)summary.get("mentions")).size());
        Map<String,Object> page1=service.list(1L,"换工","all",0,"latest","",1),page2=service.list(1L,"换工","all",0,"latest","",2);
        assertEquals(10,rows(page1).size()); assertEquals(2,rows(page2).size()); assertEquals(true,page1.get("hasMore")); assertEquals(false,page2.get("hasMore"));
        assertEquals("匿名伙伴",rows(page2).get(1).get("name")); assertEquals("",rows(page2).get(1).get("avatar"));
        assertEquals(2,rows(service.list(1L,"换工","media",0,"latest","",1)).size());
        assertEquals(1,rows(service.list(1L,"换工","all",0,"latest","交通方便",1)).size());
        assertEquals(3L,rows(service.list(1L,"换工","all",0,"low","",1)).get(0).get("reviewId"));
        assertEquals(0,rows(service.list(1L,"换工","bad",30,"latest","",1)).size());
        assertEquals(1,rows(service.list(1L,"换工","bad",0,"latest","",1)).size());
        assertEquals(1L,service.summary(1L,"兼职").get("count"));
        Map<String,Object> empty=service.summary(3L,"换工"); assertEquals(0L,empty.get("count")); assertNull(empty.get("avg")); assertNull(empty.get("recommendRate"));
    }

    @Test void agencyReviewsAreShownAtTheActualRecruitingVenue()
    {
        sql.update("update kg_job set recruit_store_id=2 where job_id=7");
        assertEquals(12L,service.summary(2L,"换工").get("count"));
        assertEquals(0L,service.summary(1L,"换工").get("count"));
        assertEquals(1L,service.summary(1L,"兼职").get("count"));
    }
}
