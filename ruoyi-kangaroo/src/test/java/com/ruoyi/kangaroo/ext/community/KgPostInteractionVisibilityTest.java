package com.ruoyi.kangaroo.ext.community;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.service.IKgMessageService;

class KgPostInteractionVisibilityTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgCommunityGuard guard=mock(KgCommunityGuard.class);
    final StaffSupport support=mock(StaffSupport.class);
    final IKgMessageService messages=mock(IKgMessageService.class);
    final KgPostService service=new KgPostService();
    boolean readable=true, commentVisible=true, parentSamePost=true, parentDeleted=false;
    String parentStatus="visible";
    KgPostInteractionVisibilityTest() {
        ReflectionTestUtils.setField(service,"db",db);
        ReflectionTestUtils.setField(service,"guard",guard);
        ReflectionTestUtils.setField(service,"support",support);
        ReflectionTestUtils.setField(service,"messages",messages);
        when(db.one(anyString(),anyMap())).thenAnswer(i->{
            String sql=i.getArgument(0);Map<String,Object> p=i.getArgument(1);
            if(sql.startsWith("select p.* from kg_post"))return readable?KgJdbc.p("postId",11L,"appUserId",3L,"status","online"):null;
            if(sql.startsWith("select comment_id, post_id, app_user_id"))return commentVisible?KgJdbc.p("commentId",22L,"postId",11L,"appUserId",4L):null;
            if(sql.startsWith("select comment_id, parent_id, app_user_id")) {
                assertTrue(sql.contains("post_id = :p"));assertTrue(sql.contains("del_flag = '0'"));
                assertEquals(11L,p.get("p"));assertEquals(22L,p.get("c"));
                if(!parentSamePost||parentDeleted||(sql.contains("status = 'visible'")&&!"visible".equals(parentStatus)))return null;
                return KgJdbc.p("commentId",22L,"parentId",0L,"appUserId",4L);
            }
            if(sql.startsWith("select status, del_flag from kg_app_user"))return KgJdbc.p("status","0","delFlag","0");
            if(sql.startsWith("select user_id"))return KgJdbc.p("userId",7L);
            if(sql.startsWith("select nickname from kg_app_user"))return KgJdbc.p("nickname",p.get("u").equals(7L)?"小七":null);
            throw new AssertionError("Unexpected query contract: "+sql);
        });
        when(db.update(anyString(),anyMap())).thenReturn(1);
        when(db.insert(eq("kg_post_comment"),anyMap())).thenReturn(33L);
        when(guard.check(any(String[].class))).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("online");
    }
    void noWrite(){verify(db,never()).update(anyString(),anyMap());verify(db,never()).insert(anyString(),anyMap());verifyNoInteractions(support);verifyNoInteractions(messages);}
    void rejectedLike(){assertEquals(404,assertThrows(ServiceException.class,()->service.likeComment(22L,7L)).getCode());noWrite();}
    void rejectedReply(){assertEquals(404,assertThrows(ServiceException.class,()->service.comment(11L,7L,22L,"normal reply")).getCode());noWrite();}
    @Test void likeHiddenPostRejectedBeforeToggle(){readable=false;rejectedLike();}
    @Test void likeDeletedPostRejectedBeforeToggle(){readable=false;rejectedLike();}
    @Test void likeAuthorBlockedPostRejectedBeforeToggle(){readable=false;rejectedLike();}
    @Test void likeHiddenCommentRejectedBeforeToggle(){commentVisible=false;rejectedLike();}
    @Test void likeBlockedCommentAuthorRetainsPermissionGuard(){doThrow(new ServiceException("blocked",403)).when(guard).requireNotBlocked(7L,4L);assertEquals(403,assertThrows(ServiceException.class,()->service.likeComment(22L,7L)).getCode());noWrite();}
    @Test void visibleLikeStillIncrementsExactlyOnce(){assertEquals(true,service.likeComment(22L,7L).get("liked"));verify(db).update(contains("likes = likes + 1"),eq(KgJdbc.p("c",22L)));verify(db).one(startsWith("select p.* from kg_post"),eq(KgJdbc.p("id",11L,"me",7L)));
        // 赞通知写给评论人 4，在计数更新之后（2026-10-15）
        org.mockito.InOrder o=inOrder(db,messages);o.verify(db).update(contains("likes = likes + 1"),anyMap());o.verify(messages).upsertLike(eq(4L),eq("comment_like"),eq(22L),eq(7L),any(),eq("/pages/community/index?postId=11&view=comments"));
        verify(messages,never()).retractLike(anyLong(),anyString(),anyLong(),anyLong());}
    @Test void unlikeStillDecrementsOnce(){when(db.update(startsWith("insert ignore into kg_post_comment_like"),anyMap())).thenReturn(0);assertEquals(false,service.likeComment(22L,7L).get("liked"));verify(db).update(contains("likes = greatest(likes - 1, 0)"),anyMap());verify(db,never()).update(contains("likes = likes + 1"),anyMap());
        verify(messages).retractLike(4L,"comment_like",22L,7L);verify(messages,never()).upsertLike(anyLong(),anyString(),anyLong(),anyLong(),any(),any());}
    @Test void pendingParentReplyRejectedWithoutNotice(){parentStatus="pending";rejectedReply();}
    @Test void hiddenParentReplyRejectedWithoutNotice(){parentStatus="hidden";rejectedReply();}
    @Test void crossPostParentRejected(){parentSamePost=false;rejectedReply();}
    @Test void deletedParentRejected(){parentDeleted=true;rejectedReply();}
    @Test void invisiblePostReplyRejected(){readable=false;rejectedReply();}
    @Test void blockedParentAuthorRejected(){doThrow(new ServiceException("blocked",403)).when(guard).requireNotBlocked(7L,4L);assertEquals(403,assertThrows(ServiceException.class,()->service.comment(11L,7L,22L,"normal reply")).getCode());noWrite();}
    @Test void visibleSamePostReplyStillSavesAndNotifiesExactlyOnce(){Map<String,Object> result=service.comment(11L,7L,22L,"normal reply");assertEquals(33L,result.get("commentId"));assertEquals("visible",result.get("status"));verify(db).insert(eq("kg_post_comment"),argThat(row->row.get("post_id").equals(11L)&&row.get("parent_id").equals(22L)&&row.get("reply_user_id").equals(4L)));verify(db).update(contains("comments = comments + 1"),eq(KgJdbc.p("p",11L)));
        // 2026-10-15：被回复的人 4 收到「回复了你的评论」，帖子作者 3 另收到「评论了你的动态」，各一条，都在「评论」分类、带评论人
        String route="/pages/community/index?postId=11&view=comments";
        verify(messages).send(4L,"user","comment","小七 回复了你的评论","normal reply","comment",33L,route,7L);
        verify(messages).send(3L,"user","comment","小七 评论了你的动态","normal reply","comment",33L,route,7L);
        verify(messages,times(2)).send(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong());
        verifyNoInteractions(support);}
}
