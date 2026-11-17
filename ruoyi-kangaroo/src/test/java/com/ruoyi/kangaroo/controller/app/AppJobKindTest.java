package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.pagehelper.PageHelper;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.core.KgVolunteerKinds;
import com.ruoyi.kangaroo.mapper.KgJobFavoriteMapper;
import com.ruoyi.kangaroo.service.IKgJobService;

/** 「换工换宿」列表的换工类型：分类条按类型精确筛（「其他」也包括没选类型的岗位）、列表行带类型名、类型清单接口（不连库） */
class AppJobKindTest
{
    private final AppJobController controller = new AppJobController();
    private final IKgJobService jobs = mock(IKgJobService.class);
    private final KgVolunteerKinds kinds = mock(KgVolunteerKinds.class);

    @BeforeEach
    void setUp()
    {
        ReflectionTestUtils.setField(controller, "kgJobService", jobs);
        ReflectionTestUtils.setField(controller, "kgJobFavoriteMapper", mock(KgJobFavoriteMapper.class));
        ReflectionTestUtils.setField(controller, "volunteerKinds", kinds);
        when(kinds.label("photo")).thenReturn("摄影换工");
    }

    @AfterEach
    void tearDown()
    {
        PageHelper.clearPage();
    }

    private KgJob job(String type, String kind)
    {
        KgJob j = new KgJob();
        j.setJobId(11L); j.setJobType(type); j.setTitle("海边摄影换工"); j.setWorkKind(kind); j.setStatus("published");
        return j;
    }

    private Map<String, Object> listParams(String kind)
    {
        when(jobs.selectAppJobList(any())).thenReturn(new ArrayList<KgJob>());
        controller.list("volunteer", null, null, kind, 1, 20);
        ArgumentCaptor<KgJob> q = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs, atLeastOnce()).selectAppJobList(q.capture());
        return q.getValue().getParams();
    }

    @Test
    void kindFilterMatchesTheKindExactlyWithoutGuessingFromTitles()
    {
        when(jobs.selectAppJobList(any())).thenReturn(new ArrayList<KgJob>(Collections.singletonList(job("volunteer", "photo"))));
        AjaxResult res = controller.list("volunteer", null, null, " photo ", 1, 20);
        ArgumentCaptor<KgJob> q = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).selectAppJobList(q.capture());
        Map<String, Object> params = q.getValue().getParams();
        assertEquals("photo", params.get("workKind"));
        assertEquals(Boolean.FALSE, params.get("workKindUntyped"), "摄影换工只看选了摄影的岗位，没选类型的老岗位不再按标题里的词猜");
        assertFalse(params.containsKey("workKindWords"), "不再按词模糊匹配");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.get("rows");
        assertEquals("photo", rows.get(0).get("workKind"));
        assertEquals("摄影换工", rows.get(0).get("workKindLabel"));
    }

    @Test
    void otherAlsoListsVolunteerJobsWithoutAKind()
    {
        // 线上唯一的换工岗位没选类型：之前「其他」里看不到它，现在算在「其他」里
        Map<String, Object> params = listParams("other");
        assertEquals("other", params.get("workKind"));
        assertEquals(Boolean.TRUE, params.get("workKindUntyped"));
    }

    @Test
    void noKindMeansNoKindParams()
    {
        Map<String, Object> params = listParams(null);
        assertFalse(params.containsKey("workKind"));
        assertFalse(params.containsKey("workKindUntyped"));
    }

    @Test
    void absurdKindIsRejected()
    {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 40; i++) s.append('a');
        for (String bad : new String[] { s.toString(), "Photo", "摄影", "a'b" })
        {
            ServiceException e = assertThrows(ServiceException.class, () -> controller.list("volunteer", null, null, bad, 1, 20), bad);
            assertEquals(Integer.valueOf(422), e.getCode(), bad);
            assertEquals("换工类型不合法", e.getMessage(), bad);
        }
        verify(jobs, never()).selectAppJobList(any());
    }

    @Test
    void rowsWithoutKindShowEmptyLabel()
    {
        Map<String, Object> m = ReflectionTestUtils.invokeMethod(controller, "jobToMap", job("parttime", ""), false);
        assertEquals("", m.get("workKind"));
        assertEquals("", m.get("workKindLabel"));
        // 没注入类型服务（例如手工 new 出来的控制器）也不能报错
        Map<String, Object> bare = ReflectionTestUtils.invokeMethod(new AppJobController(), "jobToMap", job("volunteer", "photo"), false);
        assertEquals("photo", bare.get("workKind"));
        assertEquals("", bare.get("workKindLabel"));
    }

    @Test
    void kindsEndpointListsEnabledKinds()
    {
        List<Map<String, Object>> enabled = Collections.singletonList(p("value", "daily", "label", "日常换工", "desc", "前台、接待"));
        when(kinds.enabled()).thenReturn(enabled);
        assertEquals(enabled, controller.kinds().get("data"));
    }
}
