package com.ruoyi.kangaroo.ext.community;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgPublicProfileTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgCommunityGuard guard=mock(KgCommunityGuard.class);
    final KgActivityService activities=mock(KgActivityService.class);
    KgPostService profile() {
        KgPostService s=new KgPostService();
        ReflectionTestUtils.setField(s,"db",db);ReflectionTestUtils.setField(s,"guard",guard);ReflectionTestUtils.setField(s,"activities",activities);
        when(db.one(startsWith("select user_id, nickname"),anyMap())).thenReturn(p("nickname","阿川","status","0","delFlag","0","intro","喜欢徒步","city","平潭","phone","not-public","birthday","01-01","openid","not-public"));
        when(db.count(startsWith("select coalesce(sum(coalesce(likes"),anyMap())).thenReturn(123L);
        when(activities.profileActivities(9L,1)).thenReturn(p("rows",Arrays.asList(p("activityId",88L)),"total",1L,"hasMore",false));
        return s;
    }
    @Test void profileOnlyPublishesWhitelistedDetailsAndPublicTotals() {
        Map<String,Object> r=profile().userProfile(9L,null,1);
        assertEquals("喜欢徒步",r.get("intro"));assertEquals("平潭",r.get("city"));assertEquals(123L,r.get("appreciationCount"));
        assertFalse(r.containsKey("phone"));assertFalse(r.containsKey("birthday"));assertFalse(r.containsKey("openid"));
        assertEquals(1L,r.get("activityCount"));assertEquals(1,((List<?>)r.get("activities")).size());
        verify(db).count(argThat(q -> q.startsWith("select coalesce(sum") && q.contains("status = 'online' and del_flag = '0'")),eq(p("u",9L)));
    }
    @Test void blockedProfileNeverLoadsActivities() {
        KgPostService s=profile();
        when(db.count(eq("select count(*) from kg_user_block where user_id = :m and blocked_id = :u"),anyMap())).thenReturn(1L);
        Map<String,Object> r=s.userProfile(9L,7L,1);
        assertEquals(Collections.emptyList(),r.get("activities"));assertEquals(false,r.get("activitiesHasMore"));
        verify(activities,never()).profileActivities(anyLong(),anyInt());
    }
    @Test void blockedByOwnerStopsBeforeActivityOrPostQueries() {
        KgPostService s=profile();
        when(db.count(eq("select count(*) from kg_user_block where user_id = :u and blocked_id = :m"),anyMap())).thenReturn(1L);
        assertEquals(404,assertThrows(ServiceException.class,()->s.userProfile(9L,7L,1)).getCode());
        verify(activities,never()).profileActivities(anyLong(),anyInt());verify(db,never()).list(anyString(),anyMap());
    }
    @Test void organizerHistoryIsBoundedAndOmitsUnapprovedActivityStates() {
        KgActivityService s=new KgActivityService();ReflectionTestUtils.setField(s,"db",db);
        List<Map<String,Object>> all=new ArrayList<>();
        for(int i=1;i<=21;i++)all.add(p("activityId",(long)i,"source","user","status","open","title","活动","capacity",6,"signed",0));
        when(db.list(startsWith("select a.* from kg_activity a where a.app_user_id"),anyMap())).thenReturn(all);
        when(db.count(startsWith("select count(*) from kg_activity a"),anyMap())).thenReturn(45L);
        Map<String,Object> r=s.profileActivities(9L,2);assertEquals(20,((List<?>)r.get("rows")).size());assertEquals(true,r.get("hasMore"));assertEquals(45L,r.get("total"));
        verify(db).list(argThat(q -> q.startsWith("select a.* from kg_activity a where a.app_user_id = :u") && q.contains("a.source = 'user'") && q.contains("a.del_flag = '0'") && q.contains("a.status in ('open','paused','closed','ended')") && !q.contains("'pending'") && !q.contains("'cancelled'")),argThat(ps -> ps.get("u").equals(9L) && ps.get("off").equals(20) && ps.get("size").equals(21)));
    }
    @Test void organizerTargetsUseUserOrStoreIdentifiers() {
        KgActivityService s=new KgActivityService();ReflectionTestUtils.setField(s,"db",db);
        Map<String,Object> a=p("activityId",88L,"source","user","appUserId",9L,"status","open","capacity",6,"signed",0);
        when(db.one(startsWith("select * from kg_activity"),anyMap())).thenReturn(a);
        when(db.one(startsWith("select avatar from kg_app_user"),anyMap())).thenReturn(p("avatar","/profile/a.jpg"));
        Map<?,?> host=(Map<?,?>)s.detail(null,88L,"community",null).get("host");assertEquals(9L,host.get("userId"));assertFalse(host.containsKey("storeId"));
        a.put("storeId",12L);a.put("source","merchant");when(db.one(startsWith("select store_name, cover logo"),anyMap())).thenReturn(p("logo","/profile/store.jpg"));
        host=(Map<?,?>)s.detail(null,88L,"community",null).get("host");assertEquals(12L,host.get("storeId"));assertFalse(host.containsKey("userId"));
    }
}
