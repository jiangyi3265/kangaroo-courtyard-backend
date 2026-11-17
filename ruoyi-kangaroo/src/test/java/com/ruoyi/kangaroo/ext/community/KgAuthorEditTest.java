package com.ruoyi.kangaroo.ext.community;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgAuthorEditTest {
    KgJdbc db; KgPostService posts; KgActivityService activities; KgCommunityGuard guard;
    Map<String,Object> post, activity;
    @BeforeEach void setup() {
        db=mock(KgJdbc.class); guard=mock(KgCommunityGuard.class);
        posts=new KgPostService(); activities=new KgActivityService();
        for(Object service:Arrays.asList(posts,activities)) {
            ReflectionTestUtils.setField(service,"db",db); ReflectionTestUtils.setField(service,"guard",guard);
        }
        when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
        when(guard.check(any())).thenReturn("pass"); when(guard.initialStatus(any())).thenReturn("pending"); when(guard.mode()).thenReturn("manual");
        post=p("postId",3L,"appUserId",7L,"kind","buddy","title","原动态","content","原正文","images","/profile/a.jpg","status","online","activityId",6L,"likes",9L);
        activity=p("activityId",6L,"appUserId",7L,"source","user","status","paused","signed",5L,"title","原活动","intro","介绍","safetyNote","注意","cover","/profile/a.jpg");
        when(db.one(contains("from kg_post where post_id"),anyMap())).thenAnswer(i->post);
        when(db.one(contains("from kg_activity where activity_id"),anyMap())).thenAnswer(i->activity);
    }
    Map<String,Object> postBody() { return p("title","新标题","content","新正文","images",Arrays.asList("/profile/a.jpg"),"editToken",KgPostService.editToken(post)); }
    Map<String,Object> activityBody() { return p("contentOnly",true,"editToken",KgPostService.editToken(activity),"title","新活动","intro","新介绍","safetyNote","注意","cover","/profile/a.jpg"); }
    @SuppressWarnings("unchecked") Map<String,Object> written(String table) {
        ArgumentCaptor<Map<String,Object>> c=ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq(table),anyString(),any(),c.capture());return c.getValue();
    }
    @Test void postEditRetainsRelationsAndInteractionsAndResubmitsReview() {
        Map<String,Object> result=posts.edit(3L,7L,postBody()), cols=written("kg_post");
        assertEquals("pending",result.get("status"));assertEquals("新标题",cols.get("title"));
        for(String key:Arrays.asList("activity_id","likes","comments","ref_id","app_user_id","kind","create_time"))assertFalse(cols.containsKey(key),key);
        verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void cannotReadOrModifyAnotherAuthorsPost() {
        assertThrows(ServiceException.class,()->posts.editView(3L,8L));assertThrows(ServiceException.class,()->posts.edit(3L,8L,postBody()));
        verify(db,never()).updateRow(anyString(),anyString(),any(),anyMap());
    }
    @Test void stalePostCannotOverwriteNewContent() {
        Map<String,Object> b=postBody();post.put("content","其他设备已修改");
        assertEquals(409,postsError(b).getCode());
    }
    ServiceException postsError(Map<String,Object>b){return assertThrows(ServiceException.class,()->posts.edit(3L,7L,b));}
    @Test void hiddenPostCannotBeReopenedByEditing() {post.put("status","hidden");assertEquals(409,postsError(postBody()).getCode());}
    @Test void invalidNewImageRejected() {Map<String,Object>b=postBody();b.put("images",Arrays.asList("https://untrusted.example/photo.jpg"));assertEquals(422,postsError(b).getCode());}
    @Test void legacyImageCanBeRetained() {post.put("images","https://legacy.example/photo.jpg");Map<String,Object>b=postBody();b.put("images",Arrays.asList(post.get("images")));posts.edit(3L,7L,b);assertEquals(post.get("images"),written("kg_post").get("images"));}
    @Test void signedActivityContentCanChangeWithoutReopeningOrLosingSignups() {
        activities.editByUser(7L,6L,activityBody()); Map<String,Object> cols=written("kg_activity");
        assertEquals("paused",cols.get("status"));assertEquals("新活动",cols.get("title"));
        for(String key:Arrays.asList("signed","capacity","start_time","end_time","place","fee_fen","deadline","post_id"))assertFalse(cols.containsKey(key),key);
    }
    @Test void signedActivityRejectsProtectedFieldInjection() {Map<String,Object>b=activityBody();b.put("feeFen",1);assertThrows(ServiceException.class,()->activities.editByUser(7L,6L,b));verify(db,never()).updateRow(anyString(),anyString(),any(),anyMap());}
    @Test void signedActivityReviewRiskLeavesOriginalUntouched() {when(guard.check(any())).thenReturn("review");assertThrows(ServiceException.class,()->activities.editByUser(7L,6L,activityBody()));verify(db,never()).updateRow(anyString(),anyString(),any(),anyMap());}
    @Test void terminalActivityCannotBeReopened() {for(String status:Arrays.asList("ended","cancelled")){activity.put("status",status);assertThrows(ServiceException.class,()->activities.editByUser(7L,6L,activityBody()));}}
    @Test void activityAuthorAndSourceChecked() {assertThrows(ServiceException.class,()->activities.editByUser(8L,6L,activityBody()));activity.put("source","yard");assertThrows(ServiceException.class,()->activities.editByUser(7L,6L,activityBody()));}
    @Test void staleActivityRejected() {Map<String,Object>b=activityBody();activity.put("title","其他设备已修改");assertThrows(ServiceException.class,()->activities.editByUser(7L,6L,b));}
}
