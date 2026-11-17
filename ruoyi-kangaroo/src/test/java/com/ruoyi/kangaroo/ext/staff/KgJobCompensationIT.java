package com.ruoyi.kangaroo.ext.staff;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.common.config.RuoYiConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.controller.app.AppJobController;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Invoked by test_job_compensation_database.py with a newly created isolated database. */
class KgJobCompensationIT
{
    @TempDir Path profile;
    @Test
    @SuppressWarnings("unchecked")
    void staffCreateAdminEditAndAppDisplayRoundTripThroughRealMysql() throws Exception
    {
        String database = System.getenv("KG_COMPENSATION_TEST_DB");
        assertNotNull(database, "Run through e2e/test_job_compensation_database.py");
        assertTrue(database.matches("kg_comp_test_[a-f0-9]{12}"));
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                "root", System.getenv("MYSQL_PWD"));
        KgJdbc db = new KgJdbc(ds);
        Configuration config = new Configuration(new Environment("salary-test", new JdbcTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAlias("KgJob", KgJob.class);
        String resource = "mapper/kangaroo/KgJobMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource))
        {
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        }
        KgStaffJobService staff = new KgStaffJobService();
        ReflectionTestUtils.setField(staff, "db", db);
        ReflectionTestUtils.setField(staff, "support", mock(StaffSupport.class));
        StaffSupport.Ctx ctx = new StaffSupport.Ctx(); ctx.storeId = 102L; ctx.role = "manager";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(9001L);
        String previousProfile = RuoYiConfig.getProfile();
        Files.createDirectories(profile.resolve("upload"));
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", profile.resolve("upload/compensation.png").toFile()));
        new RuoYiConfig().setProfile(profile.toString());
        String images = "[\"/profile/upload/compensation.png\"]";
        String arrival = LocalDate.now().plusDays(3).toString();
        try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true))
        {
            KgJobMapper mapper = session.getMapper(KgJobMapper.class);
            com.ruoyi.kangaroo.service.impl.KgJobServiceImpl adminService = new com.ruoyi.kangaroo.service.impl.KgJobServiceImpl();
            com.ruoyi.kangaroo.mapper.KgStoreMapper stores = mock(com.ruoyi.kangaroo.mapper.KgStoreMapper.class);
            when(stores.selectKgStoreByStoreId(102L)).thenReturn(new com.ruoyi.kangaroo.domain.KgStore());
            ReflectionTestUtils.setField(adminService, "kgStoreMapper", stores);
            for (String pay : new String[] { "board", "none" })
            {
                for (boolean bonus : new boolean[] { true, false })
                {
                    for (boolean commission : new boolean[] { true, false })
                    {
                        Map<String, Object> input = p("jobType", "volunteer", "startDate", arrival, "images", images, "pay", pay,
                                "headcount", 2, "title", "换工独立奖金测试", "bonusEnabled", bonus, "bonusDesc", "服务满30天奖500元",
                                "commissionEnabled", commission, "commissionDesc", "销售额5%");
                        Long id = ((Number) staff.create(ctx, input).get("jobId")).longValue();
                        KgJob row = mapper.selectKgJobByJobId(id);
                        assertEquals("none", row.getSalaryType()); assertNull(row.getSalaryMin()); assertNull(row.getSalaryUnit());
                        assertEquals(bonus ? "服务满30天奖500元" : null, row.getBonusDesc());
                        assertEquals(commission ? "销售额5%" : null, row.getCommissionDesc());
                        assertEquals("board".equals(pay) ? "all" : "none", row.getBoardType());
                        Map<String, Object> view = ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap", row, false);
                        assertEquals(bonus ? (commission ? "奖金 + 提成" : "奖金") : (commission ? "提成" : ""), view.get("compensationText"));
                        // 后台重新编辑无底薪岗位，规则必须保留，取消勾选则写入 NULL。
                        ReflectionTestUtils.invokeMethod(adminService, "validate", row);
                        assertEquals(1, mapper.updateKgJobByAdmin(row)); session.clearCache();
                        row = mapper.selectKgJobByJobId(id);
                        assertEquals(bonus ? "服务满30天奖500元" : null, row.getBonusDesc());
                        assertEquals(commission ? "销售额5%" : null, row.getCommissionDesc());
                        row.setBonusDesc(null); row.setCommissionDesc(null);
                        ReflectionTestUtils.invokeMethod(adminService, "validate", row);
                        assertEquals(1, mapper.updateKgJobByAdmin(row)); session.clearCache();
                        row = mapper.selectKgJobByJobId(id);
                        assertNull(row.getBonusDesc()); assertNull(row.getCommissionDesc());
                    }
                }
            }
            for (String type : new String[] { "hourly", "daily", "weekly", "monthly" })
            {
                Map<String, Object> input = p("jobType", "fulltime", "startDate", arrival, "images", images, "hours", "弹性时间",
                        "pay", "salary", "salaryType", type, "salary", "25.50", "headcount", 2, "title", "组合薪资测试",
                        "bonusEnabled", true, "bonusDesc", "达标奖500元", "commissionEnabled", true, "commissionDesc", "个人销售额5%");
                Long id = ((Number) staff.create(ctx, input).get("jobId")).longValue();
                KgJob row = mapper.selectKgJobByJobId(id);
                assertEquals("达标奖500元", row.getBonusDesc());
                assertEquals("个人销售额5%", row.getCommissionDesc());
                assertEquals(new BigDecimal("25.50"), row.getSalaryMin());
                assertEquals(type, row.getSalaryType());

                Map<String, Object> view = ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap", row, false);
                assertTrue(view.get("compensationText").toString().endsWith(" + 奖金 + 提成"));
                assertEquals("个人销售额5%", view.get("commissionDesc"));

                // 后台取消奖金，修改提成：全量 SQL 必须写 NULL，不能保留旧值。
                row.setBonusDesc(null); row.setCommissionDesc("个人销售额8%，月结");
                assertEquals(1, mapper.updateKgJobByAdmin(row));
                session.clearCache(); row = mapper.selectKgJobByJobId(id);
                assertNull(row.getBonusDesc()); assertEquals("个人销售额8%，月结", row.getCommissionDesc());
                view = ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap", row, false);
                assertFalse(view.get("compensationText").toString().contains("奖金"));
                assertTrue(view.get("compensationText").toString().endsWith(" + 提成"));

                row.setCommissionDesc(null); assertEquals(1, mapper.updateKgJobByAdmin(row));
                session.clearCache(); assertNull(mapper.selectKgJobByJobId(id).getCommissionDesc());
            }
            KgJob admin = new KgJob(); admin.setStoreId(102L); admin.setJobType("fulltime");
            admin.setTitle("后台新建"); admin.setCity("平潭"); admin.setSalaryType("monthly"); admin.setSalaryUnit("元/月");
            admin.setSalaryMin(new BigDecimal("5000")); admin.setSalaryMax(new BigDecimal("5000")); admin.setHeadcount(2);
            admin.setBonusDesc("满勤奖200元"); admin.setCommissionDesc("销售额5%");
            assertEquals(1, mapper.insertKgJob(admin));
            session.clearCache();
            assertEquals("满勤奖200元", mapper.selectKgJobByJobId(admin.getJobId()).getBonusDesc());
            assertEquals("销售额5%", mapper.selectKgJobByJobId(admin.getJobId()).getCommissionDesc());
        }
        finally { new RuoYiConfig().setProfile(previousProfile); }
    }
}
