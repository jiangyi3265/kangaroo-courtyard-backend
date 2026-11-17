package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.mapper.KgJobMapper;

class KgJobTeamTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgJobMapper jobs = mock(KgJobMapper.class);
    private final KgUserHubService service = new KgUserHubService();
    private final KgJob job = new KgJob();

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "jobMapper", jobs);
        job.setJobId(7L); job.setStoreId(102L); job.setTitle("测试小院");
        job.setJobType("volunteer"); job.setStatus("published");
        job.setHeadcount(2); job.setHiredCount(0); job.setReservedCount(1);
        when(jobs.selectKgJobByJobId(7L)).thenReturn(job);
        when(db.one(startsWith("select store_name from kg_store"), anyMap())).thenReturn(p("storeName", "测试小院"));
    }

    @Test void pausedJobRemainsVisibleForSharing()
    {
        job.setStatus("paused"); job.setJobType("parttime");
        Map<String,Object> out=service.team(null,7L);
        assertEquals("测试小院",((Map<?,?>)out.get("job")).get("storeName"));
        assertEquals("paused",((Map<?,?>)out.get("job")).get("status"));
        assertEquals(2,out.get("remaining"));
        assertEquals(false,out.get("joined"));
    }

    @Test void volunteerRemainingIncludesSuccessfulApplications()
    {
        assertEquals(1,service.team(null,7L).get("remaining"));
        job.setReservedCount(3);
        assertEquals(0,service.team(null,7L).get("remaining"));
    }

    @Test void paidJobsRetainTheCanonicalHiredQuota()
    {
        job.setJobType("fulltime"); job.setHiredCount(1); job.setReservedCount(5);
        assertEquals(1,service.team(null,7L).get("remaining"));
        job.setJobType("online"); job.setHiredCount(2);
        assertEquals(0,service.team(null,7L).get("remaining"));
    }

    @Test void closedAndDraftJobsRemainPrivate()
    {
        for(String status:new String[]{"closed","draft"}) {
            job.setStatus(status);
            assertEquals(Integer.valueOf(404),assertThrows(ServiceException.class,()->service.team(null,7L)).getCode());
        }
        verifyNoInteractions(db);
    }

    @Test void missingJobsAndDisabledStoresRemainPrivate()
    {
        when(jobs.selectKgJobByJobId(8L)).thenReturn(null);
        assertThrows(ServiceException.class,()->service.team(null,8L));
        when(db.one(startsWith("select store_name from kg_store"),anyMap())).thenReturn(null);
        assertThrows(ServiceException.class,()->service.team(null,7L));
        verify(db,never()).list(anyString(),anyMap());
    }

    @Test void viewingPausedJobDoesNotCreateAnApplicationOrJoinTheTeam()
    {
        job.setStatus("paused");
        service.team(null,7L);
        verify(db,never()).update(anyString(),anyMap());
    }
}
