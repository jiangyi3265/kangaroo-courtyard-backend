package com.ruoyi.kangaroo.ext.user;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KgMembershipTest {
    private List<Map<String,Object>> levels() {
        return Arrays.asList(p("levelId",1L,"level",0,"levelName","幼崽鼠","minPoints",0L),p("levelId",2L,"level",1,"levelName","小袋鼠","minPoints",100L),p("levelId",3L,"level",2,"levelName","袋鼠","minPoints",300L));
    }
    @Test void pointsBoundariesAndConfiguredNames() {
        KgJdbc db=mock(KgJdbc.class);when(db.list(anyString(),anyMap())).thenReturn(levels());
        KgMemberService svc=new KgMemberService(db);
        assertEquals(0,svc.summary(99).get("level"));assertEquals(1,svc.summary(100).get("level"));
        assertEquals("袋鼠",svc.summary(300).get("levelName"));assertEquals(100L,svc.summary(5000).get("pointsPercent"));
        assertEquals(0L,svc.summary(-10).get("pointsPercent"));
    }
    @Test void documentThresholdsAndNamesWorkAtEveryBoundary() {
        KgJdbc db=mock(KgJdbc.class);
        long[] mins={0,15,40,90,200,400};List<Map<String,Object>> configured=new ArrayList<>();
        for(int i=0;i<mins.length;i++) configured.add(p("levelId",(long)i+1,"level",i,"levelName","已确认名称"+i,"minPoints",mins[i]));
        when(db.list(anyString(),anyMap())).thenReturn(configured);KgMemberService svc=new KgMemberService(db);
        for(int i=0;i<mins.length;i++) {
            assertEquals(i,svc.summary(mins[i]).get("level"));
            assertEquals("已确认名称"+i,svc.summary(mins[i]).get("levelName"));
            if(i>0) assertEquals(i-1,svc.summary(mins[i]-1).get("level"));
        }
        assertEquals(5,svc.summary(9999).get("level"));
        // Subsequent administrator changes continue to be authoritative.
        configured.get(2).put("minPoints",50L);
        assertEquals(1,svc.summary(40).get("level"));assertEquals(2,svc.summary(50).get("level"));
    }
    @Test void thresholdEditsCannotInvalidateOrderOrFirstLevel() {
        KgJdbc db=mock(KgJdbc.class);when(db.list(anyString(),anyMap())).thenReturn(levels());
        KgMemberService svc=new KgMemberService(db);
        assertThrows(ServiceException.class,()->svc.validateLevel(p("_pk",1L,"min_points",1L),false));
        assertThrows(ServiceException.class,()->svc.validateLevel(p("_pk",2L,"min_points",300L),false));
        assertThrows(ServiceException.class,()->svc.validateLevel(p("_pk",3L,"min_points",99L),false));
        assertDoesNotThrow(()->svc.validateLevel(p("_pk",2L,"min_points",150L),false));
    }
    @Test void eligibilityComesFromServerPointsNotClient() {
        KgJdbc db=mock(KgJdbc.class);when(db.list(anyString(),anyMap())).thenReturn(levels());
        when(db.must(anyString(),anyMap(),anyString())).thenAnswer(i->p("minLevel",1L));
        when(db.count(anyString(),eq(p("u",42L)))).thenReturn(99L,100L);
        KgMemberService svc=new KgMemberService(db);
        assertEquals(false,svc.benefit(null,7L).get("eligible"));
        assertEquals(false,svc.benefit(42L,7L).get("eligible"));
        assertEquals(true,svc.benefit(42L,7L).get("eligible"));
    }
    @Test void validWindowAllowsOpenEndsButNotInversion() {
        assertDoesNotThrow(()->KgHomeMemberModule.checkWindow(p("end_time","2026-10-10 00:00:00")));
        assertDoesNotThrow(()->KgHomeMemberModule.checkWindow(p("start_time","2026-10-09 00:00:00","end_time","2026-10-10 00:00:00")));
        assertThrows(ServiceException.class,()->KgHomeMemberModule.checkWindow(p("start_time","2026-10-10 00:00:00","end_time","2026-10-10 00:00:00")));
    }
    @Test void benefitOverviewShowsConfiguredLevelsEvenWithoutShopsOrExecution() {
        KgJdbc db=mock(KgJdbc.class);
        List<Map<String,Object>> configured=levels();
        configured.get(1).put("description","后台配置的权益一\n有效期与使用条件");
        when(db.list(contains("from kg_member_level"),anyMap())).thenReturn(configured);
        when(db.list(contains("from kg_member_benefit"),anyMap())).thenReturn(Collections.emptyList());
        when(db.page(anyString(),anyMap(),anyInt(),anyInt())).thenReturn(p("rows",Collections.emptyList(),"total",0));
        Map<String,Object> result=new KgMemberService(db).benefits(null,"不存在的店","平潭","food",1);
        assertNull(result.get("member"));
        assertEquals(false,result.get("executionEnabled"));
        List<Map<String,Object>> perks=(List<Map<String,Object>>)result.get("levelPerks");
        assertEquals(3,perks.size());
        assertEquals(Arrays.asList("后台配置的权益一","有效期与使用条件"),perks.get(1).get("items"));
        assertEquals(0L,perks.get(1).get("shopCount"));
        verify(db,never()).update(anyString(),anyMap());
    }
    @Test void benefitOverviewReturnsExecutionFlagAndCurrentLevelWithoutIssuingVouchers() {
        KgJdbc db=mock(KgJdbc.class);
        when(db.list(contains("from kg_member_level"),anyMap())).thenReturn(levels());
        when(db.list(contains("from kg_member_benefit"),anyMap())).thenReturn(Collections.emptyList());
        when(db.page(anyString(),anyMap(),anyInt(),anyInt())).thenReturn(p("rows",Collections.emptyList(),"total",0));
        when(db.count(contains("kg_member_execution"),anyMap())).thenReturn(1L);
        when(db.count(eq(KgPointLedger.EARNED_SQL),eq(p("u",42L)))).thenReturn(100L);
        Map<String,Object> result=new KgMemberService(db).benefits(42L,null,null,null,1);
        assertEquals(true,result.get("executionEnabled"));
        assertEquals(1,((Map<String,Object>)result.get("member")).get("level"));
        assertEquals(3,((List<?>)result.get("levelPerks")).size());
        verify(db,never()).update(anyString(),anyMap());
    }
    @Test void editorialOrderWinsWithoutDuplicatingFallback() {
        List<Map<String,Object>> rows=KgHomeService.mergeCards(Arrays.asList(p("jobId",3L),p("jobId",1L)),Arrays.asList(p("jobId",1L),p("jobId",2L),p("jobId",4L)),"jobId",3);
        assertEquals(Arrays.asList(3L,1L,2L),Arrays.asList(rows.get(0).get("jobId"),rows.get(1).get("jobId"),rows.get(2).get("jobId")));
    }
}
