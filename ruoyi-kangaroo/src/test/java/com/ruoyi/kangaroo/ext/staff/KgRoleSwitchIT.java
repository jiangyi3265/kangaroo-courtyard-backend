package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * 角色切换探针：真实 MySQL、真实事务和行锁。用 e2e/test_role_switch_database.py 跑——它克隆验收库到一次性的 acceptance_*_guardtest，跑 V64，再跑这里，跑完删库。
 * 比内容，不只比行数。没有 KG_ROLE_SWITCH_TEST_DB 环境变量时跳过（普通 mvn test 不需要数据库，这个类也不在默认 *Test 匹配里）。
 */
class KgRoleSwitchIT
{
    private static final String COLS = "grant_id, app_user_id, store_id, role_code, status, also_service, granted_by, granted_time, revoked_by, revoked_time, del_flag, create_by, create_time, update_by, update_time, remark";

    /** 能在指定语句前停住的 KgJdbc：造「切换进行中」的窗口 */
    static class PausingJdbc extends KgJdbc
    {
        volatile String pauseOn;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);

        PausingJdbc(DataSource ds)
        {
            super(ds);
        }

        @Override
        public int update(String sql, Map<String, ?> params)
        {
            if (pauseOn != null && sql.contains(pauseOn))
            {
                reached.countDown();
                try
                {
                    assertTrue(resume.await(30, TimeUnit.SECONDS));
                }
                catch (InterruptedException e)
                {
                    throw new IllegalStateException(e);
                }
            }
            return super.update(sql, params);
        }
    }

    /** 提交那一刻看一眼内存阶段，也能让提交失败 */
    static class ProbingTxManager extends DataSourceTransactionManager
    {
        volatile Boolean phaseAtCommit;
        volatile boolean failCommit;

        ProbingTxManager(DataSource ds)
        {
            super(ds);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status)
        {
            phaseAtCommit = RolePhase.isNew();
            if (failCommit)
            {
                throw new TransactionSystemException("模拟提交失败");
            }
            super.doCommit(status);
        }
    }

    private DataSource ds;
    private PausingJdbc db;
    private ProbingTxManager tx;
    private KgRoleSwitchService service;
    private final IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final ISysDictTypeService dictTypes = mock(ISysDictTypeService.class);
    private final ISysConfigService configs = mock(ISysConfigService.class);

    @BeforeEach
    void setup()
    {
        String schema = System.getenv("KG_ROLE_SWITCH_TEST_DB");
        Assumptions.assumeTrue(schema != null, "只在 e2e/test_role_switch_database.py 里跑");
        assertTrue(schema.matches("acceptance_roleswitch_[a-f0-9]{12}_guardtest"), schema);
        ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai", "root", System.getenv("MYSQL_PWD"));
        db = new PausingJdbc(ds);
        tx = new ProbingTxManager(ds);
        service = wired(db, tx);
        when(cities.dictionaryCity(anyString())).thenAnswer(i -> Arrays.asList("大理", "丽江").contains(i.getArgument(0)) ? i.getArgument(0) : null);
        when(cities.jobCity(anyString())).thenAnswer(i -> i.getArgument(0));
        RolePhase.set(RolePhase.LEGACY);
        reset();
    }

    @AfterEach
    void tearDown()
    {
        RolePhase.set(RolePhase.LEGACY);
    }

    private KgRoleSwitchService wired(KgJdbc jdbc, DataSourceTransactionManager manager)
    {
        KgRoleSwitchService s = new KgRoleSwitchService();
        ReflectionTestUtils.setField(s, "db", jdbc);
        ReflectionTestUtils.setField(s, "transactions", manager);
        ReflectionTestUtils.setField(s, "grants", grants);
        ReflectionTestUtils.setField(s, "cities", cities);
        ReflectionTestUtils.setField(s, "dictTypes", dictTypes);
        ReflectionTestUtils.setField(s, "configs", configs);
        return s;
    }

    private void exec(String sql)
    {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement())
        {
            st.execute(sql);
        }
        catch (SQLException e)
        {
            throw new IllegalStateException(sql, e);
        }
    }

    /** 每个场景都从同一份内容开始：一店一种角色，再加已撤销、已删除、混合身份 */
    private void reset()
    {
        exec("drop trigger if exists kg_rs_tamper");
        db.update("delete from kg_role_switch_backup", p());
        db.update("update kg_role_switch set phase = 'legacy', fingerprint = null, switched_by = null, switched_time = null, rolled_back_by = null, rolled_back_time = null, rollback_note = null, routing_opened_by = null, routing_opened_time = null where id = 1", p());
        db.update("update sys_config set config_value = 'off' where config_key = :k", p("k", KgMerchantRequestService.ROUTING_KEY));
        db.update("delete from kg_staff_client_seen", p());
        db.update("delete from kg_staff_grant", p());
        db.update("delete from kg_merchant_request", p());
        db.update("delete from kg_activity_signup where signup_no like 'RS%'", p());
        db.update("delete from kg_activity where title like 'RS探针%'", p());
        db.update("delete from kg_store where store_id >= 9100 and store_id < 9200", p());
        db.update("delete from kg_app_user where user_id >= 9000 and user_id < 9100", p());
        db.update("delete from sys_dict_data where dict_type = 'kg_staff_role'", p());
        for (Object[] d : new Object[][] { { 1, "主理人", "manager", "Y" }, { 2, "门店管家", "keeper", "N" }, { 3, "员工", "staff", "Y" }, { 5, "合作商家", "merchant", "N" }, { 6, "商家服务人", "merchant_service", "N" }, { 7, "城市负责人", "city_lead", "N" } })
        {
            db.update("insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) values (:s, :l, :v, 'kg_staff_role', '', 'info', :d, '0', 'admin', now(), '')",
                    p("s", d[0], "l", d[1], "v", d[2], "d", d[3]));
        }
        for (long s : new long[] { 9101, 9102, 9103, 9104, 9105 })
        {
            db.insert("kg_store", p("store_id", s, "store_name", "探针店" + s, "city", s == 9105 ? "不限" : (s == 9104 ? "丽江" : "大理"), "status", "0", "del_flag", "0"));
        }
        for (long u = 9001; u <= 9012; u++)
        {
            db.insert("kg_app_user", p("user_id", u, "openid", "rs-" + u, "nickname", "探针" + u, "phone", "1380000" + u, "status", "0", "del_flag", "0"));
        }
        grant(9001, 9101, "manager", "0", "0", "0", "admin");       // 老主理人
        grant(9002, 9101, "staff", "0", "0", "0", "admin");         // 老员工
        grant(9003, 9101, "keeper", "0", "0", "1", "admin");        // 小院主理人（兼任开着）
        grant(9004, 9101, "online_staff", "0", "0", "0", "admin");
        grant(9005, 9101, "merchant", "0", "0", "0", "admin");
        grant(9006, 9101, "merchant_service", "0", "0", "0", "admin");
        grant(9007, 9102, "city_lead", "0", "0", "0", "admin");     // 已有城市负责人
        grant(9008, 9102, "manager", "1", "0", "0", "admin");       // 已撤销的老主理人
        grant(9009, 9102, "staff", "0", "2", "0", "admin");         // 已删除的老员工
        grant(9010, 9103, "manager", "0", "0", "0", "admin");       // 混合身份：这家店老主理人
        grant(9010, 9104, "staff", "0", "0", "0", "admin");         // 混合身份：那家店老员工
        seenRelease();
    }

    private long grant(long uid, long store, String role, String status, String del, String also, String by)
    {
        return db.insert("kg_staff_grant", new LinkedHashMap<String, Object>(p("app_user_id", uid, "store_id", store, "role_code", role, "status", status, "del_flag", del, "also_service", also,
                "granted_by", by, "granted_time", "2026-01-02 03:04:05", "create_by", by, "create_time", "2026-01-02 03:04:05", "update_by", "seed", "update_time", "2026-02-03 04:05:06", "remark", "r" + uid)));
    }

    private void seenRelease()
    {
        db.update("insert into kg_staff_client_seen (app_user_id, channel, version, first_seen_time, last_seen_time) values (9002, 'release', :v, now(), now())", p("v", KgRoleSwitchService.MIN_CLIENT_VERSION));
    }

    private Map<Long, Map<String, Object>> rows()
    {
        Map<Long, Map<String, Object>> m = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> r : db.list("select " + COLS + " from kg_staff_grant order by grant_id", p()))
        {
            m.put(KgJdbc.asLong(r.get("grantId")), r);
        }
        return m;
    }

    private long idOf(long uid, long store)
    {
        return KgJdbc.asLong(db.one("select grant_id from kg_staff_grant where app_user_id = :u and store_id = :s", p("u", uid, "s", store)).get("grantId"));
    }

    private String fp()
    {
        return String.valueOf(service.preview().get("fingerprint"));
    }

    private String phaseInDb()
    {
        return String.valueOf(db.one("select phase from kg_role_switch where id = 1", p()).get("phase"));
    }

    private Map<String, Map<String, Object>> dict()
    {
        Map<String, Map<String, Object>> m = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : db.list("select dict_value, dict_label, dict_sort, status, is_default from sys_dict_data where dict_type = 'kg_staff_role' order by dict_value", p()))
        {
            m.put(String.valueOf(r.get("dictValue")), r);
        }
        return m;
    }

    private void assertUnchanged(Map<Long, Map<String, Object>> before, Map<String, Map<String, Object>> dictBefore)
    {
        assertEquals(before, rows(), "授权一行都不能变");
        assertEquals(dictBefore, dict(), "字典不能变");
        assertEquals(RolePhase.LEGACY, phaseInDb());
        assertFalse(RolePhase.isNew(), "内存阶段不能变");
        assertEquals(0L, db.count("select count(*) from kg_role_switch_backup", p()), "备份不能留");
    }

    private static int code(Runnable r)
    {
        ServiceException e = assertThrows(ServiceException.class, r::run);
        return e.getCode();
    }

    // ------------------------------------------------------------------ 切换

    @Test
    void switchMapsEveryRowByContentAndTouchesNothingElse()
    {
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Object> out = service.switchOver(fp(), "切换", "boss");
        assertEquals(RolePhase.NEW, out.get("phase"));
        assertEquals(2L + 1L, out.get("toCityLead"), "两个有效的老主理人 + 一个已撤销的");
        assertEquals(2L + 1L, out.get("toManager"), "两个有效的老员工 + 一个已删除的");
        assertFalse(out.containsKey("before"), "名单不出现在返回和操作日志里");
        Map<Long, Map<String, Object>> after = rows();
        assertEquals(before.keySet(), after.keySet());
        for (Long id : before.keySet())
        {
            Map<String, Object> b = before.get(id);
            Map<String, Object> a = after.get(id);
            String old = String.valueOf(b.get("roleCode"));
            String want = "manager".equals(old) ? "city_lead" : ("staff".equals(old) ? "manager" : old);
            assertEquals(want, a.get("roleCode"), "grant " + id);
            for (String col : Arrays.asList("appUserId", "storeId", "status", "alsoService", "grantedBy", "grantedTime", "revokedBy", "revokedTime", "delFlag", "createBy", "createTime", "remark"))
            {
                assertEquals(b.get(col), a.get(col), col + " of grant " + id);
            }
            if (old.equals(want))
            {
                assertEquals(b.get("updateBy"), a.get("updateBy"), "没改的行审计列不动");
                assertEquals(b.get("updateTime"), a.get("updateTime"));
            }
            else
            {
                assertEquals(KgRoleSwitchService.BY_SWITCH, a.get("updateBy"));
            }
        }
        assertEquals(0L, db.count("select count(*) from kg_staff_grant where role_code = 'staff'", p()));
        assertEquals("city_lead", db.one("select role_code from kg_staff_grant where app_user_id = 9010 and store_id = 9103", p()).get("roleCode"), "混合身份各店各自的新角色");
        assertEquals("manager", db.one("select role_code from kg_staff_grant where app_user_id = 9010 and store_id = 9104", p()).get("roleCode"));
        assertEquals(RolePhase.NEW, phaseInDb());
        assertTrue(RolePhase.isNew());
        assertEquals(Boolean.TRUE, tx.phaseAtCommit, "提交那一刻内存已经是 new：时间差里只会收紧");
        assertEquals(11L, db.count("select count(*) from kg_role_switch_backup where kind = 'grant'", p()), "每一行都有备份");
        Map<String, Object> backup = db.one("select * from kg_role_switch_backup where grant_id = :g", p("g", idOf(9001, 9101)));
        assertEquals("manager", backup.get("oldRole"));
        assertEquals("seed", backup.get("oldUpdateBy"));
        assertEquals("2026-02-03 04:05:06", backup.get("oldUpdateTime"));

        Map<String, Map<String, Object>> d = dict();
        assertEquals("换工主理人", d.get("manager").get("dictLabel"));
        assertEquals("城市负责人", d.get("city_lead").get("dictLabel"));
        assertEquals("小院主理人", d.get("keeper").get("dictLabel"));
        assertEquals("线上任务专员", d.get("online_staff").get("dictLabel"), "没有的字典补上");
        assertEquals("1", d.get("staff").get("status"));
        assertEquals("N", d.get("staff").get("isDefault"));
        assertEquals("1", String.valueOf(d.get("city_lead").get("dictSort")));
        verify(dictTypes).resetDictCache();
        verify(grants, atLeastOnce()).notifyGrantChange(eq(9001L), isNull(), anyString(), contains("城市负责人"), anyLong());
        verify(grants).notifyGrantChange(eq(9002L), isNull(), anyString(), contains("换工主理人"), anyLong());
        verify(grants).notifyGrantChange(eq(9007L), isNull(), anyString(), contains("活动退款审批"), anyLong());
        verify(grants, never()).notifyGrantChange(eq(9008L), any(), anyString(), anyString(), anyLong());
        verify(grants, never()).notifyGrantChange(eq(9003L), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void doubleClickAndConcurrentClicksSwitchOnce() throws Exception
    {
        String fp = fp();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Integer>> results = new ArrayList<Future<Integer>>();
        for (int i = 0; i < 3; i++)
        {
            results.add(pool.submit(() -> {
                try
                {
                    service.switchOver(fp, "切换", "boss");
                    return 200;
                }
                catch (ServiceException e)
                {
                    return e.getCode();
                }
            }));
        }
        int ok = 0;
        for (Future<Integer> f : results)
        {
            ok += f.get(60, TimeUnit.SECONDS) == 200 ? 1 : 0;
        }
        pool.shutdown();
        assertEquals(1, ok, "三个并发只成功一个");
        assertEquals(409, code(() -> service.switchOver(fp, "切换", "boss")), "再点一次");
        assertEquals(11L, db.count("select count(*) from kg_role_switch_backup where kind = 'grant'", p()), "备份没有写两遍");
        assertEquals("manager", db.one("select role_code from kg_staff_grant where app_user_id = 9002 and store_id = 9101", p()).get("roleCode"), "没有被映射两次");
    }

    @Test
    void wrongConfirmOrMissingFingerprintDoesNothing()
    {
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        assertEquals(422, code(() -> service.switchOver(fp(), "确定", "boss")));
        assertEquals(422, code(() -> service.switchOver("", "切换", "boss")));
        assertUnchanged(before, d);
    }

    @Test
    void staleFingerprintStops()
    {
        String fp = fp();
        db.update("update kg_staff_grant set role_code = 'keeper' where app_user_id = 9002", p());
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        ServiceException e = assertThrows(ServiceException.class, () -> service.switchOver(fp, "切换", "boss"));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("名单"), e.getMessage());
        assertUnchanged(before, d);
    }

    @Test
    void switchNotOffStops()
    {
        db.update("update sys_config set config_value = 'on' where config_key = :k", p("k", KgMerchantRequestService.ROUTING_KEY));
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        ServiceException e = assertThrows(ServiceException.class, () -> service.switchOver(fp(), "切换", "boss"));
        assertTrue(e.getMessage().contains("off"), e.getMessage());
        assertFalse((Boolean) service.preview().get("canSwitch"));
        assertUnchanged(before, d);
    }

    @Test
    void autoManagerGrantStopsEvenIfRevoked()
    {
        grant(9011, 9101, "manager", "1", "0", "0", KgMerchantRequestService.GRANTED_BY);
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        ServiceException e = assertThrows(ServiceException.class, () -> service.switchOver(fp(), "切换", "boss"));
        assertTrue(e.getMessage().contains("自动补"), e.getMessage());
        assertUnchanged(before, d);
    }

    @Test
    void withoutANewReleaseClientItStops()
    {
        db.update("delete from kg_staff_client_seen", p());
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        assertEquals(409, code(() -> service.switchOver(fp(), "切换", "boss")), "谁都没见过");
        db.update("insert into kg_staff_client_seen (app_user_id, channel, version, first_seen_time, last_seen_time) values (9002, 'develop', :v, now(), now()), (9001, 'release', '2026.10.30.1', now(), now()), (9003, 'none', '', now(), now())",
                p("v", KgRoleSwitchService.MIN_CLIENT_VERSION));
        assertEquals(409, code(() -> service.switchOver(fp(), "切换", "boss")), "开发版、老正式版、不带头的都不算");
        assertUnchanged(before, d);
        db.update("update kg_staff_client_seen set version = '2026.10.31.1' where app_user_id = 9001", p());
        assertEquals(RolePhase.NEW, service.switchOver(fp(), "切换", "boss").get("phase"), "比门槛新的正式版算");
    }

    /** 主人只有体验版（11-01 决定）：只见过够新的体验版也能切，切换页按「已用新版」显示；太老的体验版不算 */
    @Test
    void aNewEnoughTrialClientAloneLetsItSwitch()
    {
        db.update("delete from kg_staff_client_seen", p());
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        db.update("insert into kg_staff_client_seen (app_user_id, channel, version, first_seen_time, last_seen_time) values (9002, 'trial', '2026.10.30.1', now(), now())", p());
        assertEquals(409, code(() -> service.switchOver(fp(), "切换", "boss")), "太老的体验版不算");
        assertUnchanged(before, d);
        db.update("update kg_staff_client_seen set version = :v where app_user_id = 9002", p("v", KgRoleSwitchService.MIN_CLIENT_VERSION));
        Map<String, Object> m = service.preview();
        assertTrue((Boolean) m.get("canSwitch"), String.valueOf(m.get("blockers")));
        Map<String, Object> toManager = ((List<Map<String, Object>>) ((List<Map<String, Object>>) m.get("groups")).get(1).get("people")).get(0);
        Map<String, Object> client = (Map<String, Object>) toManager.get("client");
        assertEquals("new", client.get("status"), "够新的体验版显示已用新版");
        assertEquals("已用新版", client.get("statusText"));
        assertEquals("trial", client.get("channel"));
        assertEquals(RolePhase.NEW, service.switchOver(fp(), "切换", "boss").get("phase"), "只有体验版也能切");
    }

    @Test
    void verificationFailureRollsEverythingBack()
    {
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        // 真实触发器偷改一列：逐行核对必须抓到，整个事务回滚，连备份和字典一起
        exec("create trigger kg_rs_tamper before update on kg_staff_grant for each row begin if new.app_user_id = 9002 then set new.remark = 'tampered'; end if; end");
        try
        {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.switchOver(fp(), "切换", "boss"));
            assertTrue(e.getMessage().contains("核对失败"), e.getMessage());
        }
        finally
        {
            exec("drop trigger if exists kg_rs_tamper");
        }
        assertUnchanged(before, d);
        verify(grants, never()).notifyGrantChange(anyLong(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    void verificationFailureOnAMappedRoleRollsBack()
    {
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        exec("create trigger kg_rs_tamper before update on kg_staff_grant for each row begin if new.app_user_id = 9001 then set new.role_code = 'merchant'; end if; end");
        try
        {
            assertThrows(IllegalStateException.class, () -> service.switchOver(fp(), "切换", "boss"));
        }
        finally
        {
            exec("drop trigger if exists kg_rs_tamper");
        }
        assertUnchanged(before, d);
    }

    @Test
    void commitFailureThatRollsBackRestoresTheMemoryPhase()
    {
        Map<Long, Map<String, Object>> before = rows();
        Map<String, Map<String, Object>> d = dict();
        tx.failCommit = true;
        tx.setRollbackOnCommitFailure(true);
        assertThrows(TransactionSystemException.class, () -> service.switchOver(fp(), "切换", "boss"));
        assertEquals(Boolean.TRUE, tx.phaseAtCommit, "提交时内存已经收紧");
        assertUnchanged(before, d);
    }

    @Test
    void commitFailureWithUnknownOutcomeFollowsTheDatabase()
    {
        // 提交报错、结果不明：内存阶段不能猜，和库里的真值对齐（库里已是新数据时留在 legacy 就是老员工带资金权）
        tx.failCommit = true;
        assertThrows(TransactionSystemException.class, () -> service.switchOver(fp(), "切换", "boss"));
        assertEquals(RolePhase.code(), phaseInDb());
    }

    @Test
    void writerIsBlockedWhileSwitchingAndWriterFirstMakesTheSwitchStop() throws Exception
    {
        // 方向一：切换已经锁住授权行，后台改角色、新增授权都进不来
        db.pauseOn = "set phase = 'new'";
        String fp = fp();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Map<String, Object>> running = pool.submit(() -> service.switchOver(fp, "切换", "boss"));
        assertTrue(db.reached.await(30, TimeUnit.SECONDS));
        assertFalse(RolePhase.isNew(), "这时候内存阶段还没动");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement())
        {
            st.execute("set session innodb_lock_wait_timeout = 1");
            assertThrows(SQLException.class, () -> st.executeUpdate("update kg_staff_grant set role_code = 'keeper' where app_user_id = 9002"), "改角色要等切换提交");
            assertThrows(SQLException.class, () -> st.executeUpdate("insert into kg_staff_grant (app_user_id, store_id, role_code, status, del_flag, also_service) values (9012, 9101, 'staff', '0', '0', '0')"), "新增也要等");
        }
        db.resume.countDown();
        assertEquals(RolePhase.NEW, running.get(30, TimeUnit.SECONDS).get("phase"));
        pool.shutdown();
        assertEquals(0L, db.count("select count(*) from kg_staff_grant where role_code = 'staff'", p()));
    }

    @Test
    void writerFirstMakesTheSwitchWaitThenStopOnTheFingerprint() throws Exception
    {
        // 方向二：后台先开了事务改一行，切换等它提交，读到新内容，指纹对不上就停
        String fp = fp();
        Map<String, Map<String, Object>> d = dict();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement())
        {
            c.setAutoCommit(false);
            st.executeUpdate("update kg_staff_grant set role_code = 'keeper' where app_user_id = 9002");
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<Integer> running = pool.submit(() -> {
                try
                {
                    service.switchOver(fp, "切换", "boss");
                    return 200;
                }
                catch (ServiceException e)
                {
                    return e.getCode();
                }
            });
            Thread.sleep(1500);
            assertFalse(running.isDone(), "切换在等授权行的锁");
            c.commit();
            assertEquals(409, running.get(30, TimeUnit.SECONDS));
            pool.shutdown();
        }
        assertEquals("keeper", db.one("select role_code from kg_staff_grant where app_user_id = 9002", p()).get("roleCode"));
        assertUnchanged(rows(), d);
    }

    @Test
    void noticeFailureDoesNotRollBack()
    {
        doThrow(new RuntimeException("消息服务挂了")).when(grants).notifyGrantChange(anyLong(), any(), anyString(), anyString(), anyLong());
        doThrow(new RuntimeException("redis down")).when(dictTypes).resetDictCache();
        assertEquals(0, service.switchOver(fp(), "切换", "boss").get("noticeSent"));
        assertEquals(RolePhase.NEW, phaseInDb());
        assertTrue(RolePhase.isNew());
    }

    // ------------------------------------------------------------------ 退回

    @Test
    void rollbackRestoresUntouchedRowsLeavesEditedOnesAndConvertsNewOnes()
    {
        Map<Long, Map<String, Object>> original = rows();
        Map<String, Map<String, Object>> dictOriginal = dict();
        service.switchOver(fp(), "切换", "boss");

        long edited = idOf(9001, 9101);      // 切换后后台改成小院主理人
        long revoked = idOf(9002, 9101);     // 切换后后台撤销
        long keeperUp = idOf(9003, 9101);    // 小院主理人切换后被改成换工主理人
        long saved = idOf(9010, 9103);       // 老主理人（现城市负责人）：后台只保存过一次，角色没变
        long savedStaff = idOf(9010, 9104);  // 老员工（现换工主理人）：后台只保存过一次，角色没变
        long reborn = idOf(9008, 9102);      // 切换前已撤销的老主理人：切换后后台复活成换工主理人
        db.update("update kg_staff_grant set role_code = 'keeper', update_by = 'admin', update_time = now() where grant_id = :g", p("g", edited));
        db.update("update kg_staff_grant set status = '1', revoked_by = 'admin', revoked_time = now(), update_by = 'admin', update_time = now() where grant_id = :g", p("g", revoked));
        db.update("update kg_staff_grant set role_code = 'manager', also_service = '0', update_by = 'admin', update_time = now() where grant_id = :g", p("g", keeperUp));
        db.update("update kg_staff_grant set remark = '只改了备注', update_by = 'admin', update_time = '2026-03-04 05:06:07' where grant_id = :g", p("g", saved));
        db.update("update kg_staff_grant set update_by = 'admin', update_time = '2026-03-04 05:06:07' where grant_id = :g", p("g", savedStaff));
        db.update("update kg_staff_grant set status = '0', role_code = 'manager', granted_by = 'admin', granted_time = date_add(now(), interval 2 second), revoked_by = null, revoked_time = null, update_by = 'admin', update_time = now() where grant_id = :g", p("g", reborn));
        long addedManager = grant(9011, 9103, "manager", "0", "0", "0", "admin");
        long addedLead = grant(9012, 9103, "city_lead", "0", "0", "0", "admin");
        db.update("update sys_dict_data set dict_label = '运营改过' where dict_type = 'kg_staff_role' and dict_value = 'keeper'", p());

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) service.preview().get("rollbackPlan");
        Map<String, Object> out = service.rollback("退回", "boss");
        assertEquals(plan.get("skipped"), out.get("skipped"), "退回前列给主人看的，和真退回一样");
        assertEquals(plan.get("converted"), out.get("converted"));
        assertEquals(plan.get("restoreCount"), ((Long) out.get("restored")).intValue());
        assertEquals(RolePhase.LEGACY, out.get("phase"));
        assertEquals(RolePhase.LEGACY, phaseInDb());
        assertFalse(RolePhase.isNew());
        assertEquals(0L, db.count("select count(*) from kg_role_switch_backup", p()), "退回后备份清空，V64 回滚和下个包的闸门才认");
        assertNotNull(db.one("select rollback_note from kg_role_switch where id = 1", p()).get("rollbackNote"));

        Map<Long, Map<String, Object>> now = rows();
        // 没被改过的行：逐列回到切换前，连 update_by、update_time
        for (Long id : original.keySet())
        {
            if (id != edited && id != revoked && id != keeperUp && id != saved && id != savedStaff && id != reborn)
            {
                assertEquals(original.get(id), now.get(id), "grant " + id);
            }
        }
        assertEquals("keeper", now.get(edited).get("roleCode"), "改成别的角色的不动");
        assertEquals("manager", now.get(keeperUp).get("roleCode"), "改成别的角色的不动");
        // 角色还等于切换写的：按值还原。审计列、状态、备注是别人的，不盖
        assertEquals("staff", now.get(revoked).get("roleCode"), "切换后撤销过，角色还是切换写的，要还原：以后复活不能是老主理人");
        assertEquals("1", now.get(revoked).get("status"));
        assertEquals("admin", now.get(revoked).get("updateBy"));
        assertEquals("manager", now.get(saved).get("roleCode"), "只是保存过：老主理人要回来，不能留在只读的城市负责人");
        assertEquals("只改了备注", now.get(saved).get("remark"));
        assertEquals("admin", now.get(saved).get("updateBy"));
        assertEquals("2026-03-04 05:06:07", now.get(saved).get("updateTime"));
        assertEquals("staff", now.get(savedStaff).get("roleCode"), "只是保存过：老员工不能变成带资金权的老主理人");
        assertEquals("admin", now.get(savedStaff).get("updateBy"));
        assertEquals("staff", now.get(reborn).get("roleCode"), "切换后复活的换工主理人和新开的一样倒回老员工，不能留成老主理人");
        assertEquals(KgRoleSwitchService.BY_ROLLBACK, now.get(reborn).get("updateBy"));
        assertEquals("staff", now.get(addedManager).get("roleCode"), "切换后新开的换工主理人倒成老员工");
        assertEquals("manager", now.get(addedLead).get("roleCode"), "切换后新开的城市负责人倒成老主理人");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) out.get("skipped");
        assertEquals(2, skipped.size(), "只有被改成别的角色的两行：" + skipped);
        boolean warned = false;
        for (Map<String, Object> s : skipped)
        {
            warned = warned || (KgJdbc.asLong(s.get("grantId")) == keeperUp && String.valueOf(s.get("attention")).contains("资金权"));
        }
        assertTrue(warned, "切换后改成 manager 的，退回后是带资金权的老主理人，要提醒");

        // 倒回的行也要列出来：倒成老主理人的标红
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> converted = (List<Map<String, Object>>) out.get("converted");
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> c : converted)
        {
            byId.put(KgJdbc.asLong(c.get("grantId")), c);
        }
        assertEquals(new java.util.HashSet<Long>(Arrays.asList(reborn, addedManager, addedLead)), byId.keySet());
        assertEquals("manager", byId.get(addedLead).get("afterRole"));
        assertTrue(String.valueOf(byId.get(addedLead).get("attention")).contains("资金权"), "新开的城市负责人倒成老主理人，要标红");
        assertEquals("", byId.get(addedManager).get("attention"));
        assertEquals("staff", byId.get(reborn).get("afterRole"));

        // 名单留得住：刷新页面后摘要里还有编号
        String note = String.valueOf(db.one("select rollback_note from kg_role_switch where id = 1", p()).get("rollbackNote"));
        assertTrue(note.contains("倒回的授权编号：") && note.contains(String.valueOf(addedLead)), note);
        assertTrue(note.contains("没动的授权编号：") && note.contains(String.valueOf(keeperUp)), note);

        Map<String, Map<String, Object>> d = dict();
        assertEquals("运营改过", d.get("keeper").get("dictLabel"), "后改过的字典不动");
        assertEquals(dictOriginal.get("manager"), d.get("manager"));
        assertEquals(dictOriginal.get("staff"), d.get("staff"));
        assertNull(d.get("online_staff"), "切换时补的字典退回时删掉");
    }

    @Test
    void rollbackThenSwitchAgainWorks()
    {
        Map<Long, Map<String, Object>> original = rows();
        Map<String, Map<String, Object>> d = dict();
        service.switchOver(fp(), "切换", "boss");
        service.rollback("退回", "boss");
        assertEquals(original, rows(), "退回后逐列等于切换前");
        assertEquals(d, dict());
        assertEquals(RolePhase.LEGACY, phaseInDb());
        Map<String, Object> again = service.switchOver(fp(), "切换", "boss2");
        assertEquals(RolePhase.NEW, again.get("phase"));
        assertEquals("boss2", db.one("select switched_by from kg_role_switch where id = 1", p()).get("switchedBy"));
        assertEquals(11L, db.count("select count(*) from kg_role_switch_backup where kind = 'grant'", p()));
    }

    @Test
    void rollbackRefusedOnlegacyAfterRoutingOpenedSwitchOnOrAutoGrants()
    {
        assertEquals(409, code(() -> service.rollback("退回", "boss")), "没切过不能退回");
        service.switchOver(fp(), "切换", "boss");
        assertEquals(422, code(() -> service.rollback("确定", "boss")));

        db.update("update sys_config set config_value = 'on' where config_key = :k", p("k", KgMerchantRequestService.ROUTING_KEY));
        assertEquals(409, code(() -> service.rollback("退回", "boss")), "开关手工改成 on 也算开了派单");
        assertFalse((Boolean) service.preview().get("canRollback"));
        db.update("update sys_config set config_value = 'off' where config_key = :k", p("k", KgMerchantRequestService.ROUTING_KEY));

        long auto = grant(9011, 9103, "manager", "0", "0", "0", KgMerchantRequestService.GRANTED_BY);
        assertEquals(409, code(() -> service.rollback("退回", "boss")), "有自动授权");
        db.update("delete from kg_staff_grant where grant_id = :g", p("g", auto));

        db.update("insert into kg_merchant_request (request_type, store_name, city, status, create_time) values ('volunteer', '探针', '大理', 'dispatched', now())", p());
        assertEquals(409, code(() -> service.rollback("退回", "boss")), "有进行中的需求");
        db.update("delete from kg_merchant_request", p());

        assertTrue((Boolean) service.preview().get("canRollback"));
        service.openRouting("打开派单", "boss");
        assertEquals("on", db.one("select config_value from sys_config where config_key = :k", p("k", KgMerchantRequestService.ROUTING_KEY)).get("configValue"));
        assertNotNull(db.one("select routing_opened_time from kg_role_switch where id = 1", p()).get("routingOpenedTime"));
        verify(configs).resetConfigCache();
        db.update("update sys_config set config_value = 'off' where config_key = :k", p("k", KgMerchantRequestService.ROUTING_KEY));
        ServiceException e = assertThrows(ServiceException.class, () -> service.rollback("退回", "boss"));
        assertTrue(e.getMessage().contains("不提供一键退回"), "开派单后开关改回 off 也不提供一键退回：" + e.getMessage());
        assertTrue(RolePhase.isNew());
        assertEquals(RolePhase.NEW, phaseInDb());
    }

    @Test
    void openRoutingRefusedBeforeTheSwitch()
    {
        assertEquals(409, code(() -> service.openRouting("打开派单", "boss")));
        assertEquals(422, code(() -> service.openRouting("打开", "boss")));
        assertEquals("off", db.one("select config_value from sys_config where config_key = :k", p("k", KgMerchantRequestService.ROUTING_KEY)).get("configValue"));
    }

    // ------------------------------------------------------------------ 切换页

    @Test
    @SuppressWarnings("unchecked")
    void previewShowsThreeGroupsMaskedPhonesVersionsAndWarnings()
    {
        // 9104 只有老员工（9010 混合身份）；9103 只有老主理人；9105 城市字典外；近期有旅居账没有小院主理人的店
        grant(9011, 9105, "staff", "0", "0", "0", "admin");
        db.insert("kg_daily_entry", p("store_id", 9103L, "biz_date", java.time.LocalDate.now().toString(), "direction", "income", "category", "订房收入", "amount_fen", 100L, "ledger", "stay", "status", "0"));
        db.update("insert into kg_staff_client_seen (app_user_id, channel, version, first_seen_time, last_seen_time) values (9001, 'none', '', now(), now())", p());
        long act = db.insert("kg_activity", p("source", "store", "title", "RS探针活动", "start_time", "2026-12-01 10:00:00", "store_id", 9101L, "fee_fen", 100L, "status", "open", "del_flag", "0"));
        db.insert("kg_activity_signup", p("signup_no", "RS1", "activity_id", act, "app_user_id", 9011L, "name", "探针报名", "status", "refund_pending", "fee_fen", 100L, "refund_fen", 100L, "del_flag", "0"));

        Map<String, Object> m = service.preview();
        assertEquals(RolePhase.LEGACY, m.get("phase"));
        assertEquals(64, String.valueOf(m.get("fingerprint")).length());
        assertTrue((Boolean) m.get("canSwitch"), String.valueOf(m.get("blockers")));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) m.get("groups");
        assertEquals(Arrays.asList("toCityLead", "toManager", "cityLeadGainsFinance"), Arrays.asList(groups.get(0).get("code"), groups.get(1).get("code"), groups.get(2).get("code")));
        assertEquals(2, groups.get(0).get("count"), "只算有效的");
        assertEquals(3, groups.get(1).get("count"));
        assertEquals(1, groups.get(2).get("count"));
        Map<String, Object> first = ((List<Map<String, Object>>) groups.get(0).get("people")).get(0);
        assertEquals(9001L, KgJdbc.asLong(first.get("appUserId")));
        assertEquals("探针9001", first.get("nickname"));
        assertFalse(String.valueOf(first.get("phone")).contains("00009001"), "手机号打码");
        assertEquals("探针店9101", first.get("storeName"));
        assertEquals("大理", first.get("city"));
        assertEquals("old", ((Map<String, Object>) first.get("client")).get("status"), "不带头的是老版本");
        Map<String, Object> second = ((List<Map<String, Object>>) groups.get(1).get("people")).get(0);
        assertEquals("new", ((Map<String, Object>) second.get("client")).get("status"));
        Map<String, Object> lead = ((List<Map<String, Object>>) groups.get(2).get("people")).get(0);
        assertEquals("never", ((Map<String, Object>) lead.get("client")).get("status"));
        assertEquals(2L, m.get("inactiveRows"));

        Map<String, Map<String, Object>> w = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> x : (List<Map<String, Object>>) m.get("warnings"))
        {
            w.put(String.valueOf(x.get("code")), x);
        }
        assertEquals(1, w.get("onlyOldManager").get("count"), "9103 只有老主理人");
        assertEquals(2, w.get("noCityLead").get("count"), "9104、9105 只有老员工");
        assertEquals(1, w.get("stayLedgerNoKeeper").get("count"));
        assertEquals(1, w.get("cityOutOfDictionary").get("count"));
        assertEquals(9105L, KgJdbc.asLong(((List<Map<String, Object>>) w.get("cityOutOfDictionary").get("items")).get(0).get("storeId")));
        assertEquals(1L, w.get("pendingRefunds").get("count"));
        assertEquals(1, w.get("mixedAccounts").get("count"));

        // 切换后这一页还看得懂：分组按备份里的原角色
        service.switchOver(String.valueOf(m.get("fingerprint")), "切换", "boss");
        Map<String, Object> after = service.preview();
        assertEquals(RolePhase.NEW, after.get("phase"));
        assertEquals(2, ((List<Map<String, Object>>) after.get("groups")).get(0).get("count"));
        assertTrue((Boolean) after.get("canRollback"));
        assertTrue((Boolean) after.get("canOpenRouting"));
        assertFalse((Boolean) after.get("canSwitch"));
    }

    @Test
    void realAcceptanceRowsRoundTripExactly()
    {
        db.update("delete from kg_staff_grant", p());
        db.update("insert into kg_staff_grant select * from kg_staff_grant_probe_orig", p());
        Map<Long, Map<String, Object>> original = rows();
        assertTrue(original.size() > 50, "用的是验收库的真实行");
        service.switchOver(fp(), "切换", "boss");
        assertEquals(0L, db.count("select count(*) from kg_staff_grant where role_code = 'staff'", p()));
        service.rollback("退回", "boss");
        assertEquals(original, rows());
    }

    @Test
    @SuppressWarnings("unchecked")
    void noCityLeadWarningCountsOnlyAccountsThatCanActuallyAct()
    {
        Map<String, Object> m = service.preview();
        assertEquals(2 - 1, warning(m, "noCityLead").get("count"), "只有 9104 没有城市负责人");
        // 9102 唯一的城市负责人账号停用了：授权行还在，但没人能审
        db.update("update kg_app_user set status = '1' where user_id = 9007", p());
        Map<String, Object> w = warning(service.preview(), "noCityLead");
        assertEquals(2, w.get("count"));
        assertEquals(new java.util.HashSet<Long>(Arrays.asList(9102L, 9104L)), new java.util.HashSet<Long>(storeIds(w)));
        // 切换后在 9104 补开一位，提醒就消失
        db.update("update kg_app_user set status = '0' where user_id = 9007", p());
        service.switchOver(fp(), "切换", "boss");
        assertEquals(1, warning(service.preview(), "noCityLead").get("count"));
        grant(9011, 9104, "city_lead", "0", "0", "0", "admin");
        assertEquals(0, warning(service.preview(), "noCityLead").get("count"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> warning(Map<String, Object> preview, String code)
    {
        for (Map<String, Object> x : (List<Map<String, Object>>) preview.get("warnings"))
        {
            if (code.equals(x.get("code")))
            {
                return x;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> storeIds(Map<String, Object> warning)
    {
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> i : (List<Map<String, Object>>) warning.get("items"))
        {
            ids.add(KgJdbc.asLong(i.get("storeId")));
        }
        return ids;
    }

    @Test
    void leftoverStaffRowsAreShownAfterTheSwitch()
    {
        service.switchOver(fp(), "切换", "boss");
        assertNull(warning(service.preview(), "leftoverStaff"));
        grant(9011, 9103, "staff", "0", "0", "0", "admin");
        grant(9012, 9104, "weird_role", "0", "0", "0", "admin");
        Map<String, Object> m = service.preview();
        assertEquals(2, warning(m, "leftoverStaff").get("count"));
        assertEquals(1L, ((Map<?, ?>) m.get("counts")).get("unknownRole"));
    }

    @Test
    void bogusVersionRowAndRefundWithoutStoreDoNotBreakThePage()
    {
        db.update("insert into kg_staff_client_seen (app_user_id, channel, version, first_seen_time, last_seen_time) values (9001, 'release', '99999999999999999999', now(), now())", p());
        long act = db.insert("kg_activity", p("source", "user", "title", "RS探针没有门店", "start_time", "2026-12-01 10:00:00", "fee_fen", 100L, "status", "open", "del_flag", "0"));
        db.insert("kg_activity_signup", p("signup_no", "RS2", "activity_id", act, "app_user_id", 9011L, "name", "探针", "status", "refund_pending", "fee_fen", 100L, "refund_fen", 100L, "del_flag", "0"));
        Map<String, Object> m = service.preview();
        assertTrue((Boolean) m.get("canSwitch"), "还有一个正常的正式版：" + m.get("blockers"));
        assertEquals(1L, warning(m, "pendingRefunds").get("count"));
        // 超长版本号的这一行算旧，也没有因为它切不了
        assertEquals(RolePhase.NEW, service.switchOver(String.valueOf(m.get("fingerprint")), "切换", "boss").get("phase"));
        assertTrue((Boolean) service.preview().get("canRollback"), "切换后这一页也打得开");

        // 超长的请求头进不了库
        com.ruoyi.kangaroo.security.AppLoginUser u = new com.ruoyi.kangaroo.security.AppLoginUser();
        u.setUserId(9002L);
        com.ruoyi.kangaroo.security.StaffGrantView g = new com.ruoyi.kangaroo.security.StaffGrantView();
        g.setStoreId(9101L);
        u.setStaffGrants(new ArrayList<com.ruoyi.kangaroo.security.StaffGrantView>(Arrays.asList(g)));
        KgStaffClientSeen seen = new KgStaffClientSeen();
        ReflectionTestUtils.setField(seen, "db", db);
        seen.record(u, "release/99999999999999999999");
        assertEquals(KgRoleSwitchService.MIN_CLIENT_VERSION, db.one("select version from kg_staff_client_seen where app_user_id = 9002 and channel = 'release'", p()).get("version"), "正式版那一行没被覆盖");
        assertNotNull(db.one("select version from kg_staff_client_seen where app_user_id = 9002 and channel = 'none'", p()), "当没带头记");
    }

    @Test
    void noOneIsToldAboutARefundThroughADisabledAccountOrStore()
    {
        StaffSupport support = new StaffSupport();
        ReflectionTestUtils.setField(support, "db", db);
        assertEquals(Arrays.asList(9007L), support.cityLeadsOfStore(9102L));
        assertTrue(support.cityLeadsOfStore(9101L).isEmpty(), "9101 没有城市负责人（切换前老主理人还不算）");
        db.update("update kg_app_user set status = '1' where user_id = 9007", p());
        assertTrue(support.cityLeadsOfStore(9102L).isEmpty(), "账号停用");
        db.update("update kg_app_user set status = '0' where user_id = 9007", p());
        db.update("update kg_store set status = '1' where store_id = 9102", p());
        assertTrue(support.cityLeadsOfStore(9102L).isEmpty(), "门店停用");
    }

    private static com.ruoyi.kangaroo.security.AppLoginUser login(long uid, long store, String role)
    {
        com.ruoyi.kangaroo.security.AppLoginUser u = new com.ruoyi.kangaroo.security.AppLoginUser();
        u.setUserId(uid);
        u.setNickname("探针" + uid);
        com.ruoyi.kangaroo.security.StaffGrantView g = new com.ruoyi.kangaroo.security.StaffGrantView();
        g.setStoreId(store);
        g.setStoreName("探针店" + store);
        g.setRoleCode(role);
        g.setActive(true);
        u.setStaffGrants(new ArrayList<com.ruoyi.kangaroo.security.StaffGrantView>(Arrays.asList(g)));
        com.ruoyi.kangaroo.security.AppUserContext.set(u);
        return u;
    }

    @Test
    void refundBadgeOnlyCountsWhatHeCanActuallyApprove()
    {
        KgMerchantRequestService requests = new KgMerchantRequestService();
        ReflectionTestUtils.setField(requests, "db", db);
        ReflectionTestUtils.setField(requests, "cities", cities);
        long act = db.insert("kg_activity", p("source", "store", "title", "RS探针角标", "start_time", "2026-12-01 10:00:00", "store_id", 9102L, "fee_fen", 100L, "status", "open", "del_flag", "0"));
        db.insert("kg_activity_signup", p("signup_no", "RS3", "activity_id", act, "app_user_id", 9007L, "name", "他自己", "status", "refund_pending", "fee_fen", 100L, "refund_fen", 100L, "del_flag", "0"));
        db.insert("kg_activity_signup", p("signup_no", "RS4", "activity_id", act, "app_user_id", 9011L, "name", "别人", "status", "refund_pending", "fee_fen", 100L, "refund_fen", 100L, "del_flag", "0"));
        service.switchOver(fp(), "切换", "boss");
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = login(9007, 9102, "city_lead");
        c.storeId = 9102L;
        c.role = "city_lead";
        try
        {
            assertEquals(1L, requests.hubCounts(c).get("cityRefundPending"), "自己的报名他审不了，算进来永远消不掉");
            CityLeadScope scope = new CityLeadScope();
            ReflectionTestUtils.setField(scope, "db", db);
            ReflectionTestUtils.setField(scope, "cities", cities);
            assertTrue(scope.covers(9007L, 9102L));
            db.update("update kg_store set status = '1' where store_id = 9102", p());
            assertFalse(scope.covers(9007L, 9102L), "门店停用后他的范围里没有这家店");
            // 登录态每个请求重读，授权查询和范围都过滤停用门店：下一个请求这家店就不在他的授权里了
            c.me.setStaffGrants(new ArrayList<com.ruoyi.kangaroo.security.StaffGrantView>());
            assertEquals(0L, requests.hubCounts(c).get("cityRefundPending"), "和列表同一口径：门店停用不算");
        }
        finally
        {
            com.ruoyi.kangaroo.security.AppUserContext.remove();
        }
    }

    @Test
    void dashboardStoresLeaveOutRoutedGrantsAndRetiredStaff()
    {
        com.ruoyi.kangaroo.ext.manage.KgManageService manage = new com.ruoyi.kangaroo.ext.manage.KgManageService();
        ReflectionTestUtils.setField(manage, "db", db);
        grant(9011, 9101, "manager", "0", "0", "0", "admin");
        grant(9011, 9102, "manager", "0", "0", "0", KgMerchantRequestService.GRANTED_BY);
        grant(9011, 9103, "staff", "0", "0", "0", "admin");
        assertEquals(3, manage.myBusinessStores(9011L).size(), "切换前行为不变");
        assertEquals(3, manage.myStores(9011L).size());
        RolePhase.set(RolePhase.NEW);
        assertEquals(1, manage.myBusinessStores(9011L).size());
        assertEquals(9101L, KgJdbc.asLong(manage.myBusinessStores(9011L).get(0).get("storeId")));
        assertEquals(2, manage.myStores(9011L).size(), "日报、周报、招募账还要用派单补的门店；残留的 staff 不算");
    }

    private Map<String, Object> birthdayOf(long uid, String role, long store)
    {
        com.ruoyi.kangaroo.ext.manage.KgHonorService honor = new com.ruoyi.kangaroo.ext.manage.KgHonorService();
        ReflectionTestUtils.setField(honor, "db", db);
        login(uid, store, role);
        try
        {
            return honor.birthday();
        }
        finally
        {
            com.ruoyi.kangaroo.security.AppUserContext.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static String conditions(Map<String, Object> birthday)
    {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> c : (List<Map<String, Object>>) birthday.get("conditions"))
        {
            b.append(c.get("text")).append('=').append(c.get("ok")).append(';');
        }
        return b.toString();
    }

    @Test
    void changingAnOldStaffRoleDoesNotGiveHimTheOldDays()
    {
        service.switchOver(fp(), "切换", "boss");
        // 老主理人：城市负责人，按原授权时间（2026-01-02）。没有周报这一条
        String oldManager = conditions(birthdayOf(9001, "city_lead", 9101));
        assertFalse(oldManager.contains("周报"), oldManager);
        assertTrue(oldManager.contains("担任满 90 天") && oldManager.matches(".*担任满 90 天（已满 \\d+ 天）=true.*"), oldManager);
        // 老员工：换工主理人，从切换当天算
        // 切换时间是库里的 now()，天数是 JVM 的今天：探针进程的时区和库可能差一天，所以看「只有 0～2 天、不够 90」，不看正好 1 天
        String justSwitched = ".*已满 [0-2] 天）=false.*";
        String oldStaff = conditions(birthdayOf(9002, "manager", 9101));
        assertTrue(oldStaff.matches(justSwitched), oldStaff);
        // 后台把老员工改成城市负责人：改角色不动 granted_time，但不能因此白拿原来的天数
        db.update("update kg_staff_grant set role_code = 'city_lead', update_by = 'admin' where grant_id = :g", p("g", idOf(9002, 9101)));
        String moved = conditions(birthdayOf(9002, "city_lead", 9101));
        assertTrue(moved.matches(justSwitched), moved);
        // 切换前就是城市负责人的：按自己的授权时间
        assertTrue(conditions(birthdayOf(9007, "city_lead", 9102)).matches(".*已满 \\d{3} 天）=true.*"));
    }

    @Test
    void grantWriterWaitingOnTheSwitchIsStoppedByThePhaseGuard() throws Exception
    {
        RolePhaseGuard guard = new RolePhaseGuard();
        ReflectionTestUtils.setField(guard, "db", db);
        org.springframework.transaction.support.TransactionTemplate writer = new org.springframework.transaction.support.TransactionTemplate(tx);
        // 切换进行中：状态行已锁，内存还是 legacy。写授权的请求这时进来
        db.pauseOn = "set phase = 'new'";
        String fp = fp();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Map<String, Object>> running = pool.submit(() -> service.switchOver(fp, "切换", "boss"));
        assertTrue(db.reached.await(30, TimeUnit.SECONDS));
        Future<Integer> waiting = pool.submit(() -> {
            try
            {
                writer.execute(status -> {
                    guard.beforeGrantWrite();
                    db.update("insert into kg_staff_grant (app_user_id, store_id, role_code, status, del_flag, also_service) values (9012, 9101, 'staff', '0', '0', '0')", p());
                    return null;
                });
                return 200;
            }
            catch (ServiceException e)
            {
                return e.getCode();
            }
        });
        Thread.sleep(800);
        assertFalse(waiting.isDone(), "在等切换提交");
        db.pauseOn = null;
        db.resume.countDown();
        assertEquals(RolePhase.NEW, running.get(30, TimeUnit.SECONDS).get("phase"));
        assertEquals(409, waiting.get(30, TimeUnit.SECONDS), "等到锁时阶段已经变了，不能写出切换后的 staff 行");
        assertEquals(0L, db.count("select count(*) from kg_staff_grant where role_code = 'staff'", p()));

        // 退回同样：manager 不能在退回提交之后写成带资金权的老主理人
        PausingJdbc pausing = new PausingJdbc(ds);
        pausing.pauseOn = "set phase = 'legacy'";
        KgRoleSwitchService rolling = wired(pausing, tx);
        Future<Map<String, Object>> rollback = pool.submit(() -> rolling.rollback("退回", "boss"));
        assertTrue(pausing.reached.await(30, TimeUnit.SECONDS));
        Future<Integer> waiting2 = pool.submit(() -> {
            try
            {
                writer.execute(status -> {
                    guard.beforeGrantWrite();
                    db.update("insert into kg_staff_grant (app_user_id, store_id, role_code, status, del_flag, also_service) values (9012, 9102, 'manager', '0', '0', '0')", p());
                    return null;
                });
                return 200;
            }
            catch (ServiceException e)
            {
                return e.getCode();
            }
        });
        Thread.sleep(800);
        assertFalse(waiting2.isDone(), "在等退回提交");
        pausing.resume.countDown();
        assertEquals(RolePhase.LEGACY, rollback.get(30, TimeUnit.SECONDS).get("phase"));
        assertEquals(409, waiting2.get(30, TimeUnit.SECONDS));
        assertEquals(0L, db.count("select count(*) from kg_staff_grant where app_user_id = 9012", p()));

        // 没有在切换时：照常写；拿着共享锁时切换要等
        writer.execute(status -> {
            guard.beforeGrantWrite();
            return null;
        });
        pool.shutdown();
    }

    // ------------------------------------------------------------------ 自动授权收回（真实 SQL）

    @Test
    void autoManagerGrantsAreReleasedAndCanBeRevived()
    {
        KgMerchantRequestService requests = new KgMerchantRequestService();
        ReflectionTestUtils.setField(requests, "db", db);
        ReflectionTestUtils.setField(requests, "cities", cities);
        ReflectionTestUtils.setField(requests, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(requests, "grants", grants);
        ReflectionTestUtils.setField(requests, "tokens", mock(AppTokenService.class));
        ReflectionTestUtils.setField(requests, "jobs", mock(IKgJobService.class));
        long own = grant(9011, 9101, "manager", "0", "0", "0", "admin");
        long auto = grant(9011, 9102, "manager", "0", "0", "0", KgMerchantRequestService.GRANTED_BY);
        long elsewhere = grant(9011, 9104, "manager", "0", "0", "0", KgMerchantRequestService.GRANTED_BY);

        assertEquals(0, requests.releaseManagerGrants(9011L), "legacy 阶段一行都不动");

        RolePhase.set(RolePhase.NEW);
        assertEquals(1, requests.releaseManagerGrants(9011L), "丽江已经没有平台开的授权，收回丽江这行；大理还有，不动");
        assertEquals("1", rows().get(elsewhere).get("status"));
        assertEquals(KgMerchantRequestService.REVOKED_NOT_MANAGER, rows().get(elsewhere).get("revokedBy"));
        assertEquals("0", rows().get(auto).get("status"));

        db.update("update kg_staff_grant set role_code = 'keeper' where grant_id = :g", p("g", own));
        assertEquals(1, requests.releaseManagerGrants(9011L), "大理也没有了");
        assertEquals("1", rows().get(auto).get("status"));
        assertEquals(0, requests.releaseManagerGrants(9011L), "重复调没有副作用");

        // 他以后重新成为丽江的换工主理人：系统收回的能复活；平台手工撤的不能
        Boolean revived = ReflectionTestUtils.invokeMethod(requests, "ensureGrant", 9011L, 9104L, "manager");
        assertEquals(Boolean.TRUE, revived);
        assertEquals("0", rows().get(elsewhere).get("status"));
        db.update("update kg_staff_grant set revoked_by = 'admin' where grant_id = :g", p("g", auto));
        Boolean blocked = ReflectionTestUtils.invokeMethod(requests, "ensureGrant", 9011L, 9102L, "manager");
        assertEquals(Boolean.FALSE, blocked, "平台手工撤销的不复活");
    }
}
