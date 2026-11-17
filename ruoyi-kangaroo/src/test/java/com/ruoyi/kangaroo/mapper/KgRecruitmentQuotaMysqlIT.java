package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppJobController;
import com.ruoyi.kangaroo.domain.*;
import com.ruoyi.kangaroo.dto.*;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.*;
import com.ruoyi.kangaroo.service.impl.*;

/** 真实 MySQL、真实事务和 Mapper；仅创建并删除随机测试库，不改模板库数据。 */
@EnabledIfEnvironmentVariable(named = "KG_TEST_DB_PASSWORD", matches = ".+")
class KgRecruitmentQuotaMysqlIT
{
    private com.ruoyi.kangaroo.testsupport.UploadedImageFixture photos;
    String database;
    JdbcTemplate root, sql;
    DataSourceTransactionManager tx;
    KgJobMapper jobs;
    KgApplicationServiceImpl applications;
    KgJobServiceImpl admin;
    IKgMessageService messages;

    @BeforeEach void setup() throws Exception
    {
        photos = new com.ruoyi.kangaroo.testsupport.UploadedImageFixture("/profile/upload/2026/10/27/quota-test.jpg");
        String template = Optional.ofNullable(System.getenv("KG_TEST_TEMPLATE_DB")).orElse("dkxy");
        assertTrue(template.matches("[A-Za-z0-9_]+"));
        database = "kg_scratch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        root = new JdbcTemplate(KgInboxMysqlIT.source(""));
        root.execute("create database " + database + " character set utf8mb4 collate utf8mb4_unicode_ci");
        DataSource ds = KgInboxMysqlIT.source(database);
        sql = new JdbcTemplate(ds);
        for (String table : new String[] { "kg_job", "kg_store", "kg_application", "kg_app_user", "kg_idempotency", "kg_insurance_apply", "kg_online_settlement" })
            sql.execute("create table " + table + " like " + template + "." + table);
        // 本地模板库可能早于 V42–V49；仅为一次性测试库补齐 Mapper 所需的既有字段。
        for (String column : new String[] { "work_kind varchar(32)", "latitude decimal(9,6)", "longitude decimal(9,6)",
                "month_redpacket char(1) not null default '1'", "gender_pref varchar(32)", "rest_mode varchar(32)",
                "room_type varchar(32)", "images text" })
        {
            String name = column.substring(0, column.indexOf(' '));
            if (sql.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_job' and column_name=?", Integer.class, name) == 0)
                sql.execute("alter table kg_job add column " + column);
        }
        tx = new DataSourceTransactionManager(ds);
        Configuration cfg = new Configuration(new Environment("quota-it", new SpringManagedTransactionFactory(), ds));
        cfg.getTypeAliasRegistry().registerAlias("KgJob", KgJob.class);
        cfg.getTypeAliasRegistry().registerAlias("KgApplication", KgApplication.class);
        for (String mapper : new String[] { "KgJobMapper", "KgApplicationMapper", "KgIdempotencyMapper" })
        {
            String resource = "mapper/kangaroo/" + mapper + ".xml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource))
            {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        jobs = session.getMapper(KgJobMapper.class);
        KgIdempotencyServiceImpl idem = new KgIdempotencyServiceImpl();
        ReflectionTestUtils.setField(idem, "kgIdempotencyMapper", session.getMapper(KgIdempotencyMapper.class));
        KgApplicationServiceImpl target = new KgApplicationServiceImpl();
        ReflectionTestUtils.setField(target, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(target, "kgApplicationMapper", session.getMapper(KgApplicationMapper.class));
        ReflectionTestUtils.setField(target, "kgJdbcForQuota", new KgJdbc(ds));
        ReflectionTestUtils.setField(target, "idempotencyService", proxy(idem));
        ReflectionTestUtils.setField(target, "kgApplicationLogMapper", mock(KgApplicationLogMapper.class));
        ReflectionTestUtils.setField(target, "kgInterviewSlotMapper", mock(KgInterviewSlotMapper.class));
        ReflectionTestUtils.setField(target, "kgInterviewMapper", mock(KgInterviewMapper.class));
        ReflectionTestUtils.setField(target, "kgStaffGrantService", mock(IKgStaffGrantService.class));
        messages = mock(IKgMessageService.class);
        ReflectionTestUtils.setField(target, "kgMessageService", messages);
        applications = proxy(target);
        KgJobServiceImpl adminTarget = new KgJobServiceImpl();
        KgStoreMapper stores = mock(KgStoreMapper.class);
        when(stores.selectKgStoreByStoreId(102L)).thenReturn(new KgStore());
        ReflectionTestUtils.setField(adminTarget, "kgStoreMapper", stores);
        ReflectionTestUtils.setField(adminTarget, "kgJobMapper", jobs);
        admin = proxy(adminTarget);
        sql.update("insert into kg_store (store_id,store_name,city,status,del_flag) values (102,'测试小院','平潭','0','0')");
        sql.update("insert into kg_job (job_id,store_id,job_type,title,city,salary_type,headcount,hired_count,applied_count,status,del_flag) "
                + "values (7,102,'volunteer','测试换宿','平潭','none',2,0,0,'published','0')");
        // Quota edits use the same validation as publishing: the fixture needs a platform photo.
        sql.update("update kg_job set images=? where job_id=7", "[\"/profile/upload/2026/10/27/quota-test.jpg\"]");
        for (long uid = 1; uid <= 8; uid++)
            sql.update("insert into kg_app_user (user_id,openid,nickname,status,del_flag) values (?,?,'测试报名者','0','0')", uid, "quota-" + uid);
        login();
    }

    @SuppressWarnings("unchecked") <T> T proxy(T target)
    {
        ProxyFactory f = new ProxyFactory(target);
        f.setProxyTargetClass(true);
        f.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T) f.getProxy();
    }

    void login()
    {
        SysUser user = new SysUser(); user.setUserId(1L); user.setUserName("quota-test");
        LoginUser login = new LoginUser(); login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @AfterEach void cleanup()
    {
        SecurityContextHolder.clearContext();
        if (photos != null) photos.close();
        if (root != null && database != null && database.matches("kg_scratch_[a-f0-9]{8}"))
            root.execute("drop database if exists " + database);
    }

    ApplicationSubmitDTO dto(long uid, String key)
    {
        ApplicationSubmitDTO d = new ApplicationSubmitDTO();
        d.setJobId(7L); d.setIdemKey(key); d.setName("测试" + uid); d.setPhone("13900000001");
        d.setAge(23); d.setSkills("接待"); d.setArrival(LocalDate.now().plusDays(2).toString()); d.setDays(30);
        return d;
    }

    KgApplication submit(long uid, String key) { return applications.submit(dto(uid, key), uid, "测试报名者"); }
    KgJob current() { return jobs.selectKgJobByJobId(7L); }
    void remaining(int expected)
    {
        KgJob job = current();
        assertEquals(expected, job.getRemainingCount());
        KgJob query = new KgJob(); query.setJobType("volunteer");
        assertEquals(expected, jobs.selectKgJobList(query).get(0).getRemainingCount());
        Map<?, ?> response = ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap", job, false);
        assertEquals(expected, response.get("remainingCount"));
    }

    @Test void adminTotalAndSuccessfulApplicationsReduceRemainingWithoutDuplicateDeduction() throws Exception
    {
        KgJob edit = current(); edit.setHeadcount(3); admin.updateKgJob(edit);
        remaining(3);
        KgApplication a = submit(1, "first"); remaining(2);
        assertEquals(a.getAppId(), submit(1, "first").getAppId()); remaining(2);
        assertThrows(ServiceException.class, () -> submit(1, "duplicate-new-key")); remaining(2);
        submit(2, "second"); remaining(1);
        submit(3, "third"); remaining(0);
        assertEquals("该岗位名额已满", assertThrows(ServiceException.class, () -> submit(4, "full")).getMessage());
        assertEquals(3, current().getHeadcount());
        assertEquals(0, current().getHiredCount(), "报名不冒充已录用");
        edit = current(); edit.setHeadcount(2);
        final KgJob tooSmall = edit;
        assertTrue(assertThrows(ServiceException.class, () -> admin.updateKgJob(tooSmall)).getMessage().contains("已报名占用人数 3"));
        remaining(0);
        edit = current(); edit.setHeadcount(4); admin.updateKgJob(edit); remaining(1);
        Map<?, ?> json = new ObjectMapper().convertValue(current(), Map.class);
        assertEquals(1, json.get("remainingCount"), "后台 JSON 同样返回剩余人数");
        KgJob forged = new ObjectMapper().readValue("{\"headcount\":4,\"reservedCount\":999,\"remainingCount\":999}", KgJob.class);
        assertNull(forged.getReservedCount(), "前端不能篡改占位统计");
    }

    @Test void withdrawRejectAndTerminateReleaseButHiringAndOnboardingKeepReservation()
    {
        KgApplication first = submit(1, "withdraw"); remaining(1);
        applications.withdraw(first.getAppId(), OperatorDTO.user(1L, "测试")); remaining(2);
        assertThrows(ServiceException.class, () -> applications.withdraw(first.getAppId(), OperatorDTO.user(1L, "测试")));
        remaining(2);
        KgApplication rejected = submit(1, "reject"); remaining(1);
        applications.audit(rejected.getAppId(), false, "日期不合适", OperatorDTO.admin("测试")); remaining(2);
        KgApplication hired = submit(1, "hire"); remaining(1);
        sql.update("update kg_application set status='user_confirmed' where app_id=?", hired.getAppId());
        applications.storeConfirm(hired.getAppId(), OperatorDTO.admin("测试")); remaining(1);
        assertEquals(1, current().getHiredCount());
        applications.terminate(hired.getAppId(), "行程取消", OperatorDTO.admin("测试")); remaining(2);
        assertEquals(0, current().getHiredCount());
        KgApplication arrived = submit(1, "arrive");
        sql.update("update kg_application set status='onboarded' where app_id=?", arrived.getAppId());
        remaining(1);
        jobs.recalcCounters(7L); remaining(1);
        assertEquals(1, current().getHiredCount());
    }

    @Test void failureAfterInsertRollsBackReservationAndApplication()
    {
        doThrow(new ServiceException("测试回滚")).when(messages).send(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        assertThrows(ServiceException.class, () -> submit(1, "rollback"));
        assertEquals(0, sql.queryForObject("select count(*) from kg_application", Integer.class));
        assertEquals(0, current().getAppliedCount()); remaining(2);
        reset(messages);
        submit(1, "retry"); remaining(1);
    }

    @Test void onlyOneOfEightSimultaneousApplicantsCanTakeLastPlace() throws Exception
    {
        sql.update("update kg_job set headcount=1 where job_id=7");
        assertEquals(1, race(false));
        remaining(0);
        assertEquals(1, sql.queryForObject("select count(*) from kg_application", Integer.class));
    }

    @Test void concurrentAdminReductionCannotLetWaitingApplicantsOversubscribe() throws Exception
    {
        submit(1, "existing");
        assertEquals(0, race(true));
        remaining(0);
        assertEquals(1, current().getHeadcount());
    }

    int race(boolean shrink) throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try
        {
            TransactionTemplate gate = new TransactionTemplate(tx);
            gate.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            gate.execute(status -> {
                jobs.lockRecruitment(7L);
                if (shrink) { KgJob edit = current(); edit.setHeadcount(1); admin.updateKgJob(edit); }
                for (long uid = shrink ? 2 : 1; uid <= 8; uid++)
                {
                    final long user = uid;
                    attempts.add(pool.submit(() -> {
                        try { submit(user, "race-" + user); return true; }
                        catch (ServiceException e) { assertEquals("该岗位名额已满", e.getMessage()); return false; }
                    }));
                }
                // 全部请求已完成幂等键事务，正排队等岗位锁；提交此事务后才一起竞争。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (sql.queryForObject("select count(*) from kg_idempotency where idem_key like 'race-%'", Integer.class) < attempts.size())
                {
                    assertTrue(System.nanoTime() < deadline, "等待并发提交进入岗位锁超时");
                    try { Thread.sleep(20); } catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                return null;
            });
            int accepted = 0;
            for (Future<Boolean> f : attempts) if (f.get(20, TimeUnit.SECONDS)) accepted++;
            return accepted;
        }
        finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
    }
}
