package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppJobController;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.user.KgSkills;

/** 岗位列表页的筛选胶囊（薪资 / 技能 / 包住 / 时间 / 培训）：参数校验 + 解析真实 KgJobMapper.xml 看拼出的条件（不连库） */
class KgJobListChipFilterTest
{
    Configuration config;

    @BeforeEach
    void setup() throws Exception
    {
        config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("KgJob", KgJob.class);
        String resource = "mapper/kangaroo/KgJobMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(in);
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
    }

    private BoundSql bound(Map<String, Object> params)
    {
        KgJob j = new KgJob();
        j.setJobType("parttime");
        j.setParams(params);
        return config.getMappedStatement("com.ruoyi.kangaroo.mapper.KgJobMapper.selectAppJobList").getBoundSql(j);
    }

    private static String sql(BoundSql b)
    {
        return b.getSql().replaceAll("\\s+", " ");
    }

    private static Map<String, Object> chips(Long smin, Long smax, String board, String skill, Integer minStay, String train) throws Exception
    {
        Method m = AppJobController.class.getDeclaredMethod("putChipFilters", Map.class, Long.class, Long.class, String.class, String.class, Integer.class, String.class);
        m.setAccessible(true);
        Map<String, Object> params = new HashMap<String, Object>();
        try
        {
            m.invoke(null, params, smin, smax, board, skill, minStay, train);
        }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            if (e.getCause() instanceof ServiceException)
            {
                throw (ServiceException) e.getCause();
            }
            throw e;
        }
        return params;
    }

    @Test
    void noChipsLeavesTheListAlone() throws Exception
    {
        String q = sql(bound(chips(null, null, null, null, null, null)));
        assertFalse(q.contains("salary_type in"), q);
        assertFalse(q.contains("board_type in"), q);
        assertFalse(q.contains("like"), q);
        assertFalse(q.contains("min_days, 0) <="), q);
        assertFalse(q.contains("train_desc is not null"), q);
        // 「不限」类的空值 / none 也不加条件
        assertEquals(q, sql(bound(chips(0L, 0L, "none", " ", 0, "0"))));
    }

    @Test
    void salaryIsComparedPerDayWithTheSameConversionAsTheFilterPage() throws Exception
    {
        String q = sql(bound(chips(80L, 150L, null, null, null, null)));
        assertTrue(q.contains("j.salary_type in ('hourly','daily','weekly','monthly')"), q);
        assertTrue(q.contains("* 8"), q);
        assertTrue(q.contains("/ 21.75"), q);
        assertTrue(q.contains(">= ?") && q.contains("<= ?"), "薪资走参数绑定：" + q);
    }

    @Test
    void boardRoomAlsoCountsFullBoard() throws Exception
    {
        assertTrue(sql(bound(chips(null, null, "room", null, null, null))).contains("j.board_type in ('all', 'room')"));
        assertTrue(sql(bound(chips(null, null, "meal", null, null, null))).contains("j.board_type in ('all', 'meal')"));
        assertTrue(sql(bound(chips(null, null, "all", null, null, null))).contains("j.board_type = 'all'"));
        assertThrows(ServiceException.class, () -> chips(null, null, "x' or 1=1", null, null, null));
    }

    @Test
    void skillExpandsDictionaryWordsAndBindsThem() throws Exception
    {
        Map<String, Object> p = chips(null, null, null, "摄影", null, null);
        @SuppressWarnings("unchecked")
        List<String> words = (List<String>) p.get("skillWords");
        assertTrue(words.containsAll(Arrays.asList("摄影", "拍照", "相机")), String.valueOf(words));
        BoundSql b = bound(p);
        String q = sql(b);
        assertTrue(q.contains("j.title like concat('%', ?, '%') or j.tags like concat('%', ?, '%')"), q);
        assertTrue(q.contains("kg_online_task"), "线上任务类型也算：" + q);
        assertFalse(q.contains("拍照"), "词要走参数绑定，不能拼进 SQL");
        // 词典外的（线上任务类型）按原样，斜杠拆开
        assertEquals(Arrays.asList("店员", "助手"), KgSkills.wordsOf("店员/助手"));
        assertEquals(Arrays.asList("内容写作"), KgSkills.wordsOf("内容写作"));
        assertTrue(KgSkills.wordsOf("  ").isEmpty());
        assertThrows(ServiceException.class, () -> chips(null, null, null, "%' or '1", null, null));
    }

    @Test
    void stayAndTrainChips() throws Exception
    {
        String q = sql(bound(chips(null, null, null, null, 14, "1")));
        assertTrue(q.contains("coalesce(j.min_days, 0) <= ?"), q);
        assertTrue(q.contains("j.train_desc is not null and j.train_desc <> ''"), q);
    }
}
