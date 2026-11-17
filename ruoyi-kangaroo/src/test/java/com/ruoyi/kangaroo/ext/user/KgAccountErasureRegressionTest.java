package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgArrivalTickets;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgMessageService;

/** Mock service/transaction regression. Real HTTP/SQL erasure is a separate probe. */
class KgAccountErasureRegressionTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgArrivalTickets tickets=mock(KgArrivalTickets.class);
    final AppTokenService tokens=mock(AppTokenService.class);
    final IKgMessageService messages=mock(IKgMessageService.class);
    final KgAccountService service=new KgAccountService();
    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(service,"db",db);
        ReflectionTestUtils.setField(service,"tokens",tokens);
        ReflectionTestUtils.setField(service,"messages",messages);
        // Allows this same test to expose the pre-fix missing dependency/call.
        try {service.getClass().getDeclaredField("arrivalTickets");ReflectionTestUtils.setField(service,"arrivalTickets",tickets);}
        catch (NoSuchFieldException expectedBeforeFix) { }
        AppLoginUser user=new AppLoginUser();user.setUserId(7L);AppUserContext.set(user);
        when(db.one(anyString(),anyMap())).thenReturn(p("status","0","delFlag","0"));
        when(db.must(anyString(),anyMap(),anyString())).thenReturn(p("nickname","测试账号"));
        when(db.list(contains("from kg_app_arrival"),anyMap())).thenReturn(Collections.singletonList(p("appId",12L)));
    }
    @AfterEach void clear(){AppUserContext.remove();}
    @Test void erasureProtectsLegacyFilesBeforeDeletingBindingAndInvalidatesBothForwardTypes() {
        service.deactivate("确认注销","fixture");
        InOrder order=inOrder(db,tickets,tokens);
        order.verify(db).one(contains("kg_app_user where user_id = :u for update"),anyMap());
        order.verify(db).list(contains("kg_application where app_user_id = :u order by app_id for update"),eq(p("u",7L)));
        order.verify(tickets).protectBeforeRemoval(12L);
        order.verify(db).update(argThat(q->q.contains("join kg_application")&&q.contains("m.biz_type = 'application'")&&q.contains("m.route = null")&&q.contains("m.del_flag = '2'")),eq(p("u",7L)));
        order.verify(db).update(contains("m.biz_type = 'resume_forward'"),eq(p("u",7L)));
        order.verify(db).update(startsWith("delete f from kg_resume_forward"),eq(p("u",7L)));
        order.verify(db).update(startsWith("delete from kg_app_arrival"),eq(p("u",7L)));
        order.verify(db).update(argThat(q->q.startsWith("update kg_application set real_name = '已注销用户', phone = '', age = 0, skills = null, note = null")&&!q.contains("status =")&&!q.contains("amount")&&q.contains("where app_user_id = :u")),eq(p("u",7L)));
        order.verify(tokens).kickOut(7L);
    }
    /** 别人收到的评论 / 点赞通知里存着本人昵称与评论原文：注销时按 actor_id 抹掉、清空触发人并下架，不能自查 kg_message（MySQL 1093）。 */
    @Test void erasureScrubsInteractionNoticesThisUserCausedForOthers() {
        service.deactivate("确认注销","fixture");
        InOrder order=inOrder(db,tokens);
        order.verify(db).update(eq("update kg_message set del_flag = '2' where recipient_id = :u"),eq(p("u",7L)));
        order.verify(db).update(argThat(q->q.startsWith("update kg_message set ")&&q.contains("title = '关联账号已注销'")
                &&q.contains("content = '对方的个人资料已清除'")&&q.contains("route = null")&&q.contains("actor_id = null")
                &&q.contains("del_flag = '2'")&&q.endsWith("where actor_id = :u")&&!q.contains("select")&&!q.contains("recipient_id")),eq(p("u",7L)));
        order.verify(tokens).kickOut(7L);
    }
    /** 本人小院评论通过时发给小院主理人的通知：正文引用原文，没有 actor_id，要按评论抹掉；只抹 staff 那条，别人写的评论不受影响。 */
    @Test void erasureScrubsKeeperNoticesOfThisUsersStayComments() {
        service.deactivate("确认注销","fixture");
        InOrder order=inOrder(db,tokens);
        order.verify(db).update(eq("update kg_stay_comment set anonymous = '1', update_time = now() where app_user_id = :u"),eq(p("u",7L)));
        order.verify(db).update(argThat(q->q.startsWith("update kg_message m join kg_stay_comment c on m.biz_type = 'stay_comment' and m.biz_id = c.comment_id ")
                &&q.contains("m.title = '关联账号已注销'")&&q.contains("m.content = '对方的个人资料已清除'")&&q.contains("m.route = null")&&q.contains("m.del_flag = '2'")
                &&q.endsWith("where c.app_user_id = :u and m.mode = 'staff'")),eq(p("u",7L)));
        order.verify(tokens).kickOut(7L);
    }
    /** 采购报销（V66）是财务记录：行留着，只把提交人转匿名；品名、金额、凭证、备注一个字段都不动。撤回的不是财务记录，不留。 */
    @Test void erasureKeepsReimbursementsAndAnonymizesTheSubmitter() {
        service.deactivate("确认注销","fixture");
        InOrder order=inOrder(db,tokens);
        order.verify(db).update(eq("update kg_reimbursement set submitter_name = '已注销用户', anonymous = '1', update_time = now() where app_user_id = :u"),eq(p("u",7L)));
        order.verify(db).update(eq("delete from kg_reimbursement where app_user_id = :u and del_flag <> '0'"),eq(p("u",7L)));
        order.verify(tokens).kickOut(7L);
        verify(db,never()).update(argThat(q->q.contains("kg_reimbursement")&&(q.contains("item_name")||q.contains("amount")||q.contains("receipts")||q.contains("remark")||q.contains("del_flag = '2'"))),anyMap());
    }
    /** 待审核、已通过待打款的挡住注销（人走了不知道钱打给谁）；驳回的、打完款的不挡。 */
    @Test void unfinishedReimbursementsBlockDeactivation() {
        when(db.count(argThat(q->q!=null&&q.contains("from kg_reimbursement")),anyMap())).thenReturn(2L);
        Object blockers=service.deactivationCheck().get("blockers");
        assertTrue(String.valueOf(blockers).contains("2 笔采购报销"),String.valueOf(blockers));
        assertEquals(false,service.deactivationCheck().get("canDeactivate"));
        assertThrows(ServiceException.class,()->service.deactivate("确认注销","fixture"));
        verify(db,atLeastOnce()).count(argThat(q->q!=null&&q.contains("from kg_reimbursement")&&q.contains("status in ('pending','approved')")&&q.contains("del_flag = '0'")&&!q.contains("rejected")&&!q.contains("paid")),anyMap());
        verify(tokens,never()).kickOut(7L);
    }
    /** 个人数据导出要有报销这一张表：撤回的不算。 */
    @Test void personalExportCoversReimbursements() {
        when(db.must(anyString(),anyMap(),anyString())).thenReturn(p("nickname","测试账号"));
        assertTrue(service.exportSheets().containsKey("采购报销"));
        verify(db).list(argThat(q->q!=null&&q.contains("from kg_reimbursement where app_user_id = :u and del_flag = '0'")),eq(p("u",7L)));
    }
    /** 2026-10-15：注销删掉本人点过的赞（按主键删）、重算点赞数，并把别人收到的合并点赞通知改指剩下的人，再抹触发人。 */
    void likeFixture() {
        when(db.list(startsWith("select comment_id, post_id, del_flag from kg_post_comment"),anyMap())).thenReturn(Arrays.asList(
                p("commentId",31L,"postId",8L,"delFlag","0"),p("commentId",30L,"postId",4L,"delFlag","2"),p("commentId",29L,"postId",2L,"delFlag","1")));
        when(db.list(startsWith("select like_id, post_id from kg_post_like"),anyMap())).thenReturn(Arrays.asList(p("likeId",101L,"postId",5L),p("likeId",100L,"postId",3L)));
        when(db.list(startsWith("select id, comment_id from kg_post_comment_like"),anyMap())).thenReturn(Arrays.asList(p("id",201L,"commentId",9L),p("id",200L,"commentId",6L)));
    }
    @Test void erasureDeletesOwnLikesByPrimaryKeyOnly() {
        likeFixture();
        service.deactivate("确认注销","fixture");
        verify(db).list(eq("select like_id, post_id from kg_post_like where app_user_id = :u"),eq(p("u",7L)));
        verify(db).list(eq("select id, comment_id from kg_post_comment_like where app_user_id = :u"),eq(p("u",7L)));
        verify(db,never()).list(argThat(q->q.contains("kg_post_like")&&q.contains("for update")),anyMap());
        verify(db).update(eq("delete from kg_post_like where like_id in (:ids)"),eq(p("ids",Arrays.asList(100L,101L))));
        verify(db).update(eq("delete from kg_post_comment_like where id in (:ids)"),eq(p("ids",Arrays.asList(200L,201L))));
        verify(db,never()).update(argThat(q->q.contains("kg_post_like where app_user_id")||q.contains("kg_post_comment_like where app_user_id")),anyMap());
    }
    /** 2026-10-16：评论表没有 app_user_id 索引（V45 才补），按它直接 update 会锁一大片：先普通 select 读出主键，再按主键从小到大软删。 */
    @Test void erasureSoftDeletesOwnCommentsByPrimaryKeyOnly() {
        likeFixture();
        service.deactivate("确认注销","fixture");
        verify(db).list(eq("select comment_id, post_id, del_flag from kg_post_comment where app_user_id = :u"),eq(p("u",7L)));
        verify(db,never()).list(argThat(q->q.contains("kg_post_comment ")&&q.contains("for update")),anyMap());
        // 已经删掉（del_flag = '2'）的不再碰；排好序按主键改
        verify(db).update(eq("update kg_post_comment set del_flag = '2', update_time = now() where comment_id in (:ids)"),eq(p("ids",Arrays.asList(29L,31L))));
        verify(db,never()).update(argThat(q->q.startsWith("update kg_post_comment ")&&q.contains("where app_user_id")),anyMap());
        verify(db,never()).update(argThat(q->q.contains("kg_post_comment")&&q.contains("app_user_id = :u")),anyMap());
    }
    @Test void erasureWithoutCommentsSkipsCommentUpdate() {
        service.deactivate("确认注销","fixture");
        verify(db,never()).update(startsWith("update kg_post_comment set del_flag"),anyMap());
    }
    @Test void erasureRecountsLikesOfTouchedPostsAndComments() {
        likeFixture();
        service.deactivate("确认注销","fixture");
        verify(db).update(argThat(q->q.startsWith("update kg_post p set p.comments = ")&&q.contains("p.favorites = ")
                &&q.contains("p.likes = (select count(*) from kg_post_like l where l.post_id = p.post_id)")&&q.endsWith("where p.post_id in (:ids)")),
                eq(p("ids",Arrays.asList(3L,5L,8L))));
        verify(db).update(eq("update kg_post_comment c set c.likes = (select count(*) from kg_post_comment_like l where l.comment_id = c.comment_id) where c.comment_id in (:ids)"),
                eq(p("ids",Arrays.asList(6L,9L))));
    }
    @Test void erasureWithoutLikesSkipsLikeDeletesAndCommentRecount() {
        service.deactivate("确认注销","fixture");
        verify(db,never()).update(startsWith("delete from kg_post_like"),anyMap());
        verify(db,never()).update(startsWith("delete from kg_post_comment_like"),anyMap());
        verify(db,never()).update(startsWith("update kg_post_comment c set c.likes"),anyMap());
        verify(messages).relinkLikesOf(7L);
    }
    /** 锁顺序和点赞一致：先计数行（kg_post / kg_post_comment），再 kg_message；改指合并通知要在抹触发人之前。 */
    @Test void erasureRecountsThenRelinksBeforeTouchingMessages() {
        likeFixture();
        service.deactivate("确认注销","fixture");
        InOrder order=inOrder(db,messages,tokens);
        order.verify(db).update(startsWith("delete from kg_post_like where like_id in"),anyMap());
        order.verify(db).update(startsWith("update kg_post p set p.comments"),anyMap());
        order.verify(db).update(startsWith("update kg_post_comment c set c.likes"),anyMap());
        order.verify(messages).relinkLikesOf(7L);
        order.verify(db).update(eq("update kg_message set del_flag = '2' where recipient_id = :u"),eq(p("u",7L)));
        order.verify(db).update(argThat(q->q.startsWith("update kg_message set title = '关联账号已注销'")&&q.endsWith("where actor_id = :u")),eq(p("u",7L)));
        order.verify(tokens).kickOut(7L);
    }
    /** MySQL 1093：update / delete 的子查询不能再查被改的那张表。 */
    @Test void erasureNeverUpdatesATableFromASubqueryOnItself() {
        likeFixture();
        service.deactivate("确认注销","fixture");
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);
        verify(db,atLeastOnce()).update(sql.capture(),anyMap());
        Pattern head=Pattern.compile("^(?:update|delete from) (kg_\\w+)\\b");
        List<String> all=sql.getAllValues();
        for(String q:all){
            Matcher m=head.matcher(q);
            if(!m.find())continue;
            String rest=q.substring(m.end());
            assertFalse(rest.contains("from "+m.group(1)+" ")||rest.contains("join "+m.group(1)+" ")||rest.endsWith("from "+m.group(1)),q);
        }
    }
    @Test void failedPermanentProtectionStopsErasureAndRequestsRollback() {
        doThrow(new ServiceException("fixture protection failed",503)).when(tickets).protectBeforeRemoval(12L);
        PlatformTransactionManager tm=mock(PlatformTransactionManager.class);
        TransactionStatus tx=mock(TransactionStatus.class);when(tm.getTransaction(any())).thenReturn(tx);
        ProxyFactory f=new ProxyFactory(service);f.setProxyTargetClass(true);
        f.addAdvice(new TransactionInterceptor(tm,new AnnotationTransactionAttributeSource()));
        assertEquals(503,assertThrows(ServiceException.class,()->((KgAccountService)f.getProxy()).deactivate("确认注销","fixture")).getCode());
        verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(tokens);
        verify(tm).rollback(tx);verify(tm,never()).commit(any());
    }
    @Test void wrongConfirmationNeverProtectsOrScrubsAnything() {
        assertEquals(422,assertThrows(ServiceException.class,()->service.deactivate("wrong","fixture")).getCode());
        verify(db,never()).insert(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());verifyNoInteractions(tickets,tokens);
    }
}
