package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgJobReviewDimensions;
import com.ruoyi.kangaroo.ext.core.KgReviewStats;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

class KgJobReviewServiceTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KgJobReviewService service = new KgJobReviewService();
    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(startsWith("select store_id"), anyMap())).thenReturn(p("storeId", 102L, "storeName", "小院"));
    }
    @Test void newAndLegacyDimensionsRetainTheirMeanings()
    {
        assertArrayEquals(new int[]{5,4,3,2}, KgJobReviewDimensions.scores("[5,4,3,2]"));
        assertArrayEquals(new int[]{5,3,2,1}, KgJobReviewDimensions.scores("[1,2,3,4,5]"));
        assertArrayEquals(new int[4], KgJobReviewDimensions.scores("[5,4,3]"));
        List<Map<String,Object>> dims = KgJobReviewDimensions.aggregate(Arrays.asList(p("dims", "[5,4,3,2]"), p("dims", "[1,2,3,4,5]"), p("dims", "[0,6,x,0]")));
        assertEquals(Arrays.asList("位置","氛围","工作","吃住"), Arrays.asList(dims.get(0).get("label"),dims.get(1).get("label"),dims.get(2).get("label"),dims.get(3).get("label")));
        assertEquals(5.0,dims.get(0).get("avg")); assertEquals(3.5,dims.get(1).get("avg"));
        assertEquals(2.5,dims.get(2).get("avg")); assertEquals(1.5,dims.get(3).get("avg"));
        assertEquals(2,dims.get(0).get("count"));
    }
    @Test void summaryUsesOnlyVisibleReviewsOfTheSelectedStoreAndJobType()
    {
        when(db.one(startsWith("select count(*) count"), anyMap())).thenReturn(p("count",4L,"avg",3.8,"good",3L,"bad",1L,"media",2L));
        when(db.list(startsWith("select v.dims"), anyMap())).thenReturn(Arrays.asList(p("dims","[4,3,2,1]"),p("dims","[1,2,3,4,5]")));
        when(db.one(startsWith("select sum(v.content"), anyMap())).thenReturn(p("m0",2L,"m2",1L));
        Map<String,Object> out=service.summary(102L,"换工");
        assertEquals(4L,out.get("count")); assertEquals(75L,out.get("recommendRate")); assertEquals(true,out.get("legacyDimensions"));
        assertEquals(2,((List<?>)out.get("mentions")).size());
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String,Object>> ps=ArgumentCaptor.forClass(Map.class);
        verify(db,atLeastOnce()).one(sql.capture(),ps.capture());
        for(int i=0;i<sql.getAllValues().size();i++) if(sql.getAllValues().get(i).contains("kg_review")) {
            assertTrue(sql.getAllValues().get(i).contains("coalesce(j.recruit_store_id,j.store_id)=:s"));
            assertTrue(sql.getAllValues().get(i).contains("v.status='0' and v.del_flag='0'"));
            assertTrue(sql.getAllValues().get(i).contains("v.biz_type='application'"));
            assertTrue(sql.getAllValues().get(i).contains("j.job_type=:type"));
            assertEquals(102L,ps.getAllValues().get(i).get("s")); assertEquals("volunteer",ps.getAllValues().get(i).get("type"));
        }
    }
    @Test void emptySummaryDoesNotInventScoresRecommendationsOrTags()
    {
        Map<String,Object> out=service.summary(102L,"兼职");
        assertEquals(0L,out.get("count")); assertNull(out.get("avg")); assertNull(out.get("recommendRate"));
        assertTrue(((List<?>)out.get("mentions")).isEmpty());
        for(Object dim:(List<?>)out.get("dims")) assertNull(((Map<?,?>)dim).get("avg"));
    }

    @Test void legacyReviewFilterKeepsItsVolunteerScope()
    {
        service.summary(102L, "义工");
        verify(db).one(startsWith("select count(*) count"), argThat(p -> "volunteer".equals(p.get("type"))));
    }
    @Test void listFiltersAndPagingPreserveAnonymousPrivacyAndMedia()
    {
        when(db.count(anyString(),anyMap())).thenReturn(21L);
        when(db.list(startsWith("select v.review_id"),anyMap())).thenReturn(Collections.singletonList(p("reviewId",1L,"anonymous","1","nickname","私密昵称","avatar","/private.png",
                "rating",2,"dims","[2,2,2,2]","jobType","volunteer","content","工作较累","images","/a.jpg,/b.jpg","videos","/v.mp4","videoCovers","/c.jpg","createTime","2026-10-23 10:00:00")));
        Map<String,Object> out=service.list(102L,"换工","media",30,"low","工作较累",2);
        Map<?,?> row=(Map<?,?>)((List<?>)out.get("rows")).get(0);
        assertEquals("匿名伙伴",row.get("name")); assertEquals("",row.get("avatar")); assertEquals("2026.10",row.get("date"));
        assertEquals(Arrays.asList("/a.jpg","/b.jpg"),row.get("images")); assertEquals("/v.mp4",((Map<?,?>)row.get("video")).get("url"));
        assertEquals(true,out.get("hasMore"));
        verify(db).list(argThat(s->s.contains("v.videos")&&s.contains("v.create_time>=date_sub")&&s.contains("v.content like :mention")&&s.contains("v.rating asc, v.review_id desc limit :offset,10")),
                argThat(p->Integer.valueOf(10).equals(p.get("offset"))&&"%工作较累%".equals(p.get("mention"))));
        service.list(102L,"全职","bad",0,"latest","",1);
        verify(db).count(contains("and v.rating<=2"),argThat(p->"fulltime".equals(p.get("type"))));
    }
    @Test void invalidFiltersAndInactiveStoresCannotLeakReviews()
    {
        assertThrows(ServiceException.class,()->service.list(102L,"换工","all",-1,"latest","",1));
        assertThrows(ServiceException.class,()->service.list(102L,"换工","all",0,"latest","SQL%",1));
        assertThrows(ServiceException.class,()->service.list(102L,"换工","all",0,"latest","",0));
        assertThrows(ServiceException.class,()->service.summary(102L,"住宿"));
        when(db.one(startsWith("select store_id"),anyMap())).thenReturn(null);
        assertThrows(ServiceException.class,()->service.summary(102L,"换工"));
        verify(db,never()).list(anyString(),anyMap());
    }
    @Test void applicationSubmissionRequiresAllFourDimensionsAndAllowsLegacyFive()
    {
        KgUserHubService hub=new KgUserHubService();
        ReflectionTestUtils.setField(hub,"db",db); ReflectionTestUtils.setField(hub,"guard",mock(KgCommunityGuard.class));
        ReflectionTestUtils.setField(hub,"support",mock(StaffSupport.class)); ReflectionTestUtils.setField(hub,"reviewStats",mock(KgReviewStats.class));
        when(db.one(contains("from kg_application a"),anyMap())).thenReturn(p("storeId",102L,"targetRef",7L));
        when(db.insert(eq("kg_review"),anyMap())).thenReturn(1L);
        Map<String,Object> body=p("bizType","application","bizId",55L,"dims",Arrays.asList(5,4,3,2),"images",Arrays.asList("/profile/upload/2026/10/24/photo.jpg"));
        hub.writeReview(9L,body);
        verify(db).insert(eq("kg_review"),argThat(r->"[5, 4, 3, 2]".equals(r.get("dims"))&&Integer.valueOf(4).equals(r.get("rating"))&&r.containsKey("images")));
        body.put("dims",Arrays.asList(5,4,3)); assertThrows(ServiceException.class,()->hub.writeReview(9L,body));
        body.put("dims",Arrays.asList(5,4,3,2,1)); hub.writeReview(9L,body);
        body.put("dims",Arrays.asList(5,4,3,0)); assertThrows(ServiceException.class,()->hub.writeReview(9L,body));
        verify(db,times(2)).insert(eq("kg_review"),anyMap());
    }
}
