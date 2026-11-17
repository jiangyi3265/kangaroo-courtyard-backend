package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgCommentImagesTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgCommunityGuard guard=mock(KgCommunityGuard.class);
    final KgPostService service=new KgPostService();
    final String image="/profile/upload/comment.jpg";
    @BeforeEach void setup(){
        ReflectionTestUtils.setField(service,"db",db);
        ReflectionTestUtils.setField(service,"guard",guard);
        when(db.one(startsWith("select status, del_flag from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
        when(db.one(startsWith("select p.* from kg_post p"),anyMap())).thenReturn(p("postId",11L,"appUserId",3L,"status","online","city","大理"));
        when(guard.check(any())).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("online");
    }
    @Test void imageOnlyIsSavedPendingEvenWhenTextCanBeAutoApproved(){
        assertEquals("pending",service.comment(11L,7L,null,"",Arrays.asList(image)).get("status"));
        verify(db).insert(eq("kg_post_comment"),argThat(m->image.equals(m.get("images")) && "".equals(m.get("content"))));
        verify(guard,never()).autoApprove(any(),any(),anyInt(),any(),any());
        verify(db,never()).update(startsWith("update kg_post set comments"),anyMap());
    }
    @Test void invalidAndTooManyImagesNeverWrite(){
        for(Object bad:Arrays.asList("wrong",Arrays.asList("https://example.com/a.jpg"),Arrays.asList("/profile/a.jpg,https://evil.test/a.jpg"),Arrays.asList(image,image,image,image)))
            assertThrows(ServiceException.class,()->service.comment(11L,7L,null,"看图",bad));
        verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void emptyCommentStillRejected(){
        assertThrows(ServiceException.class,()->service.comment(11L,7L,null," ",Collections.emptyList()));
        verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void imageReplyKeepsParentAndRecipient(){
        when(db.one(startsWith("select comment_id, parent_id, app_user_id"),anyMap())).thenReturn(p("commentId",21L,"parentId",20L,"appUserId",9L));
        service.comment(11L,7L,21L,"回复图片",Arrays.asList(image));
        verify(db).insert(eq("kg_post_comment"),argThat(m->Long.valueOf(20L).equals(m.get("parent_id")) && Long.valueOf(9L).equals(m.get("reply_user_id"))));
        verify(guard).requireNotBlocked(7L,9L);
    }
    @Test void readUsesLatestAccountIdentityAndIncludesImages(){
        when(db.one(startsWith("select user_id, nickname, avatar"),anyMap())).thenReturn(p("nickname","新的微信昵称","avatar","/profile/new-avatar.jpg"));
        Map<?,?> view=ReflectionTestUtils.invokeMethod(service,"commentView",p("commentId",21L,"appUserId",7L,"content","","images",image,"status","pending"),7L,3L,"大理");
        assertEquals(Arrays.asList(image),view.get("images"));assertEquals("新的微信昵称",view.get("name"));assertEquals("/profile/new-avatar.jpg",view.get("avatar"));assertEquals(true,view.get("pending"));
        Map<?,?> legacy=ReflectionTestUtils.invokeMethod(service,"commentView",p("commentId",20L,"appUserId",7L,"content","旧评论"),7L,3L,"大理");
        assertEquals(Collections.emptyList(),legacy.get("images"));
    }
}
