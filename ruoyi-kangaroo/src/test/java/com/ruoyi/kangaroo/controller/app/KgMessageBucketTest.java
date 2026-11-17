package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.util.*;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgMessageService;

/**
 * 消息页改版（2026-10-15）的后端：分组（bucketOf）、分页、按组已读、各组角标。
 * 不连库：解析真实的 KgMessageMapper.xml 看 SQL，接口用 Mock 的 service。
 * 分组在 MySQL 上的实际归属、分页、按组已读不报 1093、互动通知随对象消失，见 mapper/KgInboxMysqlIT（连一次性库）。
 */
class KgMessageBucketTest
{
    static final String NS = "com.ruoyi.kangaroo.mapper.KgMessageMapper.";
    /** bucketOf 原文（去掉多余空白）：用户身份 comment / like / system / main，员工身份 apply / order / system / main */
    static final String BUCKET_OF = "(case when category in ('comment','like') then category "
            + "when mode='staff' and (category in ('apply','interview','contract','staff') or (category='system' and coalesce(biz_type,'') like 'activity%')) then 'apply' "
            + "when mode='staff' and category='order' then 'order' "
            + "when category='system' and not (mode='user' and coalesce(biz_type,'') like 'activity%') then 'system' "
            + "else 'main' end)";
    static final String ALIVE_HEAD = "and (category not in ('comment','like') or (";
    static final String LATEST_CHAT = "newer.biz_type='chat_conv'";

    Configuration config;
    final IKgMessageService svc = mock(IKgMessageService.class);
    final AppMessageController controller = new AppMessageController();

    @BeforeEach
    void setup() throws Exception
    {
        config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("KgMessage", KgMessage.class);
        String resource = "mapper/kangaroo/KgMessageMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(in);
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        ReflectionTestUtils.setField(controller, "kgMessageService", svc);
        login(false);
    }

    @AfterEach
    void clear()
    {
        AppUserContext.remove();
    }

    private void login(boolean staff)
    {
        AppLoginUser me = spy(new AppLoginUser());
        me.setUserId(7L);
        doReturn(staff).when(me).hasStaffAccess();
        AppUserContext.set(me);
    }

    private String sql(String id, Object... kv)
    {
        Map<String, Object> p = new HashMap<String, Object>();
        p.put("recipientId", 7L);
        p.put("mode", "user");
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            p.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return config.getMappedStatement(NS + id).getBoundSql(p).getSql().replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    private static String flat(String s)
    {
        return s.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    // ---------------------------------------------------------------- SQL

    @Test
    void deletionUsesAuthenticatedOwnerAndRevokedStaffCannotDeleteUserMessages()
    {
        when(svc.deleteOwnMessage(8L,7L,"user")).thenReturn(1);
        when(svc.countUnread(7L,"user")).thenReturn(2);
        Map<?,?> data=(Map<?,?>)controller.delete(8L,"user").get("data");
        assertEquals(1,data.get("updated")); assertEquals(2,data.get("unread"));
        assertEquals(0,((Map<?,?>)controller.delete(9L,"staff").get("data")).get("updated"));
        verify(svc,never()).deleteOwnMessage(eq(9L),anyLong(),anyString());
        login(true); controller.delete(10L,"staff"); verify(svc).deleteOwnMessage(10L,7L,"staff");
    }

    @Test
    void expirationIsSharedByListsAndTotalsButNotAdministrativeHistory()
    {
        for(String id:new String[]{"selectAppMessages","selectAppMessagePage","countByCategory","countByBucket","selectApplicationNotices","selectApplicationChat"}) {
            String q=sql(id,"category",null,"bucket",null,"offset",0,"limit",21);
            assertTrue(q.contains("read_time > date_sub(now(), interval 3 day)"),id);
            assertTrue(q.contains("read_flag <> '1' or read_time is null"),id);
        }
        assertFalse(sql("selectKgMessageByMsgId","msgId",1).contains("date_sub"));
    }

    @Test
    void pageQueryFiltersByBucketKeepsAllRulesAndLimits()
    {
        String q = sql("selectAppMessagePage", "bucket", "comment", "category", null, "offset", 20, "limit", 21);
        assertTrue(q.contains("and " + flat(BUCKET_OF) + " = ?"), q);
        assertTrue(q.contains("kg_chat_reception") && q.contains("restricted.role_code='online_staff'"), "员工 ACL（currentChatReception）照旧");
        assertTrue(q.contains(LATEST_CHAT), "会话只显示最新一行");
        assertTrue(q.contains(ALIVE_HEAD), "互动通知随对象消失");
        assertTrue(q.contains("order by create_time desc, msg_id desc limit ?, ?) x"), q);
        assertTrue(q.endsWith("order by x.create_time desc, x.msg_id desc"), q);
        // 连表只给评论 / 赞带出触发人、动态、被回复 / 被赞的评论
        assertTrue(q.contains("left join kg_app_user au on x.category in ('comment','like') and au.user_id=x.actor_id"), q);
        assertTrue(q.contains("concat('用户', au.user_id)"), "没填昵称写「用户{id}」");
        assertTrue(q.contains("substring_index(coalesce(ip.images, ''), ',', 1)"), "首图");
        // 不带 bucket：不加分组条件
        assertFalse(sql("selectAppMessagePage", "bucket", null, "category", null, "offset", 0, "limit", 21).contains("case when category in ('comment','like')"));
    }

    @Test
    void badgesAndListsShareTheSameRules()
    {
        for (String id : new String[] { "selectAppMessages", "countUnread", "countByCategory", "countByBucket" })
        {
            String q = sql(id, "category", null);
            assertTrue(q.contains(ALIVE_HEAD), id + " 带 interactionAlive");
            assertTrue(q.contains(LATEST_CHAT), id + " 带 latestChatRow");
            assertTrue(q.contains("kg_chat_reception"), id + " 带接待 ACL");
        }
        String b = sql("countByBucket");
        assertTrue(b.contains("select " + flat(BUCKET_OF) + " as bucket, read_flag from kg_message"), b);
        assertTrue(b.endsWith("group by t.bucket"), b);
        // 互动通知：动态在线、评论可见、双方没拉黑
        String alive = sql("countUnread");
        assertTrue(alive.contains("ip.post_id=kg_message.biz_id and ip.del_flag='0' and ip.status='online'"), alive);
        assertTrue(alive.contains("ic.comment_id=kg_message.biz_id and ic.status='visible' and ic.del_flag='0'"), alive);
        assertTrue(alive.contains("(ib.user_id=kg_message.recipient_id and ib.blocked_id=kg_message.actor_id) or (ib.user_id=kg_message.actor_id and ib.blocked_id=kg_message.recipient_id)"), alive);
    }

    /** 2026-10-16：触发人账号停用或注销了，评论 / 赞通知不显示、不算未读；没记触发人的老通知照旧；列表、红点、计数同一口径 */
    @Test
    void interactionRowsNeedAnActiveActor()
    {
        String active = "and (actor_id is null or exists(select 1 from kg_app_user iu where iu.user_id=kg_message.actor_id and iu.status='0' and iu.del_flag='0'))";
        for (String id : new String[] { "selectAppMessages", "countUnread", "countByCategory", "countByBucket" })
        {
            assertTrue(sql(id, "category", null).contains(active), id);
        }
        String page = sql("selectAppMessagePage", "bucket", "like", "category", null, "offset", 0, "limit", 21);
        // 在内层筛这一页时就排除（外层 left join au 只是带出昵称头像）
        assertTrue(page.indexOf(active) > 0 && page.indexOf(active) < page.indexOf(") x"), page);
        // 已读不带这条规则：UPDATE kg_message 里不能有引用 kg_message 自己的子查询（MySQL 1093），也不需要
        for (String b : new String[] { null, "like" })
        {
            String upd = sql("markAllRead", "bucket", b, "maxId", 9L);
            assertFalse(upd.contains("iu.user_id=kg_message.actor_id"), upd);
            assertFalse(upd.contains("from kg_message"), upd);
        }
    }

    @Test
    void readAllByBucketUsesOnlyTheRowsOwnColumns()
    {
        String all = sql("markAllRead", "bucket", null);
        assertFalse(all.contains("case when"), all);
        String one = sql("markAllRead", "bucket", "like");
        assertTrue(one.startsWith("update kg_message set read_flag = '1', read_time = sysdate()"), one);
        assertTrue(one.endsWith("and " + flat(BUCKET_OF) + " = ?"), one);
        assertTrue(one.contains("kg_chat_reception"), "已读也只动 ACL 内的行");
        // MySQL 1093：UPDATE 的子查询不能引用 kg_message 自己
        assertFalse(one.contains("from kg_message"), one);
    }

    @Test
    void insertWritesTheActorOnlyWhenThereIsOne()
    {
        KgMessage m = new KgMessage();
        m.setRecipientId(7L);
        m.setTitle("t");
        m.setActorId(9L);
        String with = flat(config.getMappedStatement(NS + "insertKgMessage").getBoundSql(m).getSql());
        assertTrue(with.contains("(recipient_id, title, actor_id, create_time)"), with);
        m.setActorId(null);
        assertFalse(config.getMappedStatement(NS + "insertKgMessage").getBoundSql(m).getSql().contains("actor_id"));
    }

    // ---------------------------------------------------------------- 接口：老参数逐字节不变

    static KgMessage msg(long id, String category, String bizType, String readFlag, String content)
    {
        KgMessage m = new KgMessage();
        m.setMsgId(id);
        m.setRecipientId(7L);
        m.setMode("user");
        m.setCategory(category);
        m.setTitle("标题" + id);
        m.setContent(content);
        m.setBizType(bizType);
        m.setBizId(100L + id);
        m.setRoute("/pages/x?id=" + id);
        m.setReadFlag(readFlag);
        m.setCreateTime(new Date(1760000000000L + id * 1000));
        return m;
    }

    private static String json(Object o) throws Exception
    {
        ObjectMapper om = new ObjectMapper();
        om.getFactory().configure(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(), true);
        return om.writeValueAsString(o);
    }

    /** 改动前用同样的输入跑老代码抓下来的 JSON（2026-10-15） */
    static final String GOLDEN_LIST = "{\"msg\":\"\\u64CD\\u4F5C\\u6210\\u529F\",\"mode\":\"user\",\"code\":200,\"unread\":4,\"rows\":["
            + "{\"mode\":\"user\",\"route\":\"/pages/x?id=3\",\"bizType\":\"chat_conv\",\"read\":false,\"createTime\":1760000003000,\"bizId\":103,\"recipientId\":\"7\",\"id\":3,\"category\":\"chat\",\"title\":\"\\u6807\\u98983\",\"content\":\"\\u5728\\u5417\"},"
            + "{\"mode\":\"user\",\"route\":\"/pages/x?id=2\",\"bizType\":\"comment\",\"read\":true,\"createTime\":1760000002000,\"bizId\":102,\"recipientId\":\"7\",\"id\":2,\"category\":\"comment\",\"title\":\"\\u6807\\u98982\",\"content\":\"\\u5199\\u5F97\\u771F\\u597D\"},"
            + "{\"mode\":\"user\",\"route\":\"/pages/x?id=1\",\"bizType\":null,\"read\":true,\"createTime\":1760000001000,\"bizId\":101,\"recipientId\":\"7\",\"id\":1,\"category\":\"system\",\"title\":\"\\u6807\\u98981\",\"content\":null}]}";
    static final String GOLDEN_SUMMARY = "{\"msg\":\"\\u64CD\\u4F5C\\u6210\\u529F\",\"code\":200,\"unread\":4,\"categories\":[{\"total\":5,\"unread\":2,\"category\":\"system\"}]}";
    static final String GOLDEN_READALL = "{\"msg\":\"\\u64CD\\u4F5C\\u6210\\u529F\",\"code\":200,\"data\":{\"updated\":0}}";

    private void stubLegacy()
    {
        KgMessage comment = msg(2, "comment", "comment", "1", "写得真好");
        // 老接口不带互动字段：就算行里有，也不下发
        comment.setActorId(9L);
        comment.setActorName("晨光");
        when(svc.selectAppMessages(7L, "user", null)).thenReturn(Arrays.asList(
                msg(3, "chat", "chat_conv", "0", "（2 条新消息）在吗"), comment, msg(1, "system", null, "1", null)));
        when(svc.countUnread(7L, "user")).thenReturn(4);
        Map<String, Object> c1 = new HashMap<String, Object>();
        c1.put("category", "system");
        c1.put("total", 5L);
        c1.put("unread", new java.math.BigDecimal(2));
        when(svc.countByCategory(7L, "user")).thenReturn(Collections.singletonList(c1));
    }

    @Test
    void noNewParamsListAndReadAllAreByteForByteTheOldJson() throws Exception
    {
        stubLegacy();
        assertEquals(GOLDEN_LIST, json(controller.list(null, null, null, null, null)));
        assertEquals(GOLDEN_READALL, json(controller.readAll(null, null, null)));
        verify(svc).markAllRead(7L, "user");
        verify(svc, never()).selectAppMessagePage(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void summaryKeepsTheOldFieldsAndAddsBuckets() throws Exception
    {
        stubLegacy();
        Map<String, Object> like = new HashMap<String, Object>();
        like.put("bucket", "like");
        like.put("total", 3L);
        like.put("unread", new java.math.BigDecimal(1));
        Map<String, Object> stray = new HashMap<String, Object>();
        stray.put("bucket", "apply");
        stray.put("total", 9L);
        stray.put("unread", 9L);
        when(svc.countByBucket(7L, "user")).thenReturn(Arrays.asList(like, stray));
        Map<String, Object> r = controller.summary(null);
        String s = json(r);
        // 老字段逐字节不变：拿掉新加的 buckets 后和改动前一样（老版本小程序本来也没调这个接口）
        Object buckets = r.remove("buckets");
        assertNotNull(buckets);
        assertEquals(GOLDEN_SUMMARY, json(r));
        // 用户身份四组都在，查不到的是 0；员工才有的组不出现
        assertTrue(s.contains("\"buckets\":{\"main\":{\"total\":0,\"unread\":0},\"system\":{\"total\":0,\"unread\":0},"
                + "\"comment\":{\"total\":0,\"unread\":0},\"like\":{\"total\":3,\"unread\":1}}"), s);
    }

    @Test
    void staffSummaryHasItsOwnBuckets() throws Exception
    {
        login(true);
        Map<String, Object> apply = new HashMap<String, Object>();
        apply.put("bucket", "apply");
        apply.put("total", 868L);
        apply.put("unread", 860L);
        when(svc.countByBucket(7L, "staff")).thenReturn(Collections.singletonList(apply));
        String s = json(controller.summary("staff"));
        assertTrue(s.contains("\"buckets\":{\"main\":{\"total\":0,\"unread\":0},\"system\":{\"total\":0,\"unread\":0},"
                + "\"apply\":{\"total\":868,\"unread\":860},\"order\":{\"total\":0,\"unread\":0}}"), s);
    }

    // ---------------------------------------------------------------- 接口：分组分页

    private static List<KgMessage> rows(int n, String category)
    {
        List<KgMessage> out = new ArrayList<KgMessage>();
        for (int i = 0; i < n; i++)
        {
            out.add(msg(1000 - i, category, "application", "0", "x"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    @Test
    void pagingAsksOneExtraRowAndReportsHasMore()
    {
        when(svc.selectAppMessagePage(7L, "user", null, "main", 20, 21)).thenReturn(rows(21, "apply"));
        Map<String, Object> r = controller.list("user", null, "main", 2, null);
        assertEquals(Boolean.TRUE, r.get("hasMore"));
        assertEquals(20, ((List<Object>) r.get("rows")).size());
        // 不是评论 / 赞的行不带互动字段
        assertFalse(((List<Map<String, Object>>) r.get("rows")).get(0).containsKey("actorName"));

        when(svc.selectAppMessagePage(7L, "user", null, "main", 0, 21)).thenReturn(rows(5, "apply"));
        r = controller.list("user", null, "main", null, null);
        assertEquals(Boolean.FALSE, r.get("hasMore"));
        assertEquals(5, ((List<Object>) r.get("rows")).size());
    }

    @Test
    void pageSizeIsCappedAndPageIsClamped()
    {
        controller.list(null, null, "system", 0, 500);
        verify(svc).selectAppMessagePage(7L, "user", null, "system", 0, 51);
        controller.list(null, null, null, 3, 10);
        verify(svc).selectAppMessagePage(7L, "user", null, null, 20, 11);
        controller.list(null, null, null, Integer.MAX_VALUE, -5);
        verify(svc).selectAppMessagePage(7L, "user", null, null, 9999, 2);
    }

    @SuppressWarnings("unchecked")
    @Test
    void interactionRowsCarryActorPostAndQuote()
    {
        KgMessage like = msg(5, "like", "comment_like", "0", "怎么去古城");
        like.setActorId(9L);
        like.setActorName("晨光");
        like.setActorAvatar("/profile/a.png");
        like.setPostId(11L);
        like.setCover("/profile/c.png");
        like.setPostTitle("大理三天");
        like.setQuote("怎么去古城？");
        when(svc.selectAppMessagePage(7L, "user", null, "like", 0, 21)).thenReturn(Collections.singletonList(like));
        Map<String, Object> row = ((List<Map<String, Object>>) controller.list(null, null, "like", null, null).get("rows")).get(0);
        assertEquals(9L, row.get("actorId"));
        assertEquals("晨光", row.get("actorName"));
        assertEquals("/profile/a.png", row.get("actorAvatar"));
        assertEquals(11L, row.get("postId"));
        assertEquals("/profile/c.png", row.get("cover"));
        assertEquals("大理三天", row.get("postTitle"));
        assertEquals("怎么去古城？", row.get("quote"));
        assertEquals(Boolean.FALSE, row.get("read"));
    }

    @Test
    void bucketMustBelongToTheResolvedMode()
    {
        // 用户身份没有「报名 / 订单」入口：不是从员工身份降下来的请求传了就是 400
        assertEquals(400, assertThrows(ServiceException.class, () -> controller.list("user", null, "apply", null, null)).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> controller.readAll(null, "order", null)).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> controller.list(null, null, "everything", null, null)).getCode());
        // 完全不认识的分组，就算是从员工身份降下来的也照样 400
        assertEquals(400, assertThrows(ServiceException.class, () -> controller.list("staff", null, "everything", null, null)).getCode());
        assertEquals(400, assertThrows(ServiceException.class, () -> controller.readAll("staff", "everything", null)).getCode());
        login(true);
        assertEquals(400, assertThrows(ServiceException.class, () -> controller.list("staff", null, "comment", null, null)).getCode());
        controller.list("staff", null, "apply", null, null);
        verify(svc).selectAppMessagePage(7L, "staff", null, "apply", 0, 21);
        verify(svc, never()).selectAppMessages(any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void revokedStaffGrantGetsAnEmptyUserPageInsteadOf400()
    {
        // 员工授权刚被撤销（没有员工授权）、小程序还按员工身份要「报名 / 订单」：回空页、mode=user、未读按用户身份算，按组已读什么也不做
        when(svc.countUnread(7L, "user")).thenReturn(4);
        for (String b : new String[] { "apply", "order" })
        {
            Map<String, Object> r = controller.list("staff", null, b, 2, 20);
            assertEquals(200, r.get("code"));
            assertEquals(Collections.emptyList(), r.get("rows"));
            assertEquals(Boolean.FALSE, r.get("hasMore"));
            assertEquals(4, r.get("unread"));
            assertEquals("user", r.get("mode"));
            Map<String, Object> done = controller.readAll("staff", b, null);
            assertEquals(200, done.get("code"));
            assertEquals(Collections.singletonMap("updated", 0), done.get("data"));
            // 新版子页带 maxId：照样什么也不读，unreadIds 回空
            Map<String, Object> upTo = new HashMap<String, Object>();
            upTo.put("updated", 0);
            upTo.put("unreadIds", Collections.emptyList());
            assertEquals(upTo, controller.readAll("staff", b, 99L).get("data"));
        }
        verify(svc, never()).selectAppMessagePage(any(), any(), any(), any(), anyInt(), anyInt());
        verify(svc, never()).selectAppMessages(any(), any(), any());
        verify(svc, never()).markAllRead(any(), any());
        verify(svc, never()).markAllRead(any(), any(), any());
        verify(svc, never()).markAllRead(any(), any(), any(), any());
        verify(svc, never()).selectUnreadIds(any(), any(), any(), any(), anyInt());
        verify(svc, never()).countUnread(7L, "staff");
        // 员工和用户共有的分组照常按用户身份查
        controller.list("staff", null, "system", null, null);
        verify(svc).selectAppMessagePage(7L, "user", null, "system", 0, 21);
        // 真有员工授权时照常查员工的「订单」
        login(true);
        controller.list("staff", null, "order", null, null);
        verify(svc).selectAppMessagePage(7L, "staff", null, "order", 0, 21);
    }

    @Test
    void readAllByBucketOnlyClearsThatBucket()
    {
        when(svc.markAllRead(7L, "user", "comment")).thenReturn(3);
        assertEquals(Collections.singletonMap("updated", 3), controller.readAll(null, "comment", null).get("data"));
        verify(svc).markAllRead(7L, "user", "comment");
        verify(svc, never()).markAllRead(7L, "user");
        verify(svc, never()).selectUnreadIds(any(), any(), any(), any(), anyInt());
    }

    /**
     * 2026-10-16：子页第一页一到手就整组标已读，往下翻的几页取回来已经是已读、小红点丢了。
     * 带 maxId：先读出这一组 maxId 及以前还没读的ID（回给子页记住「进来那一刻没读」），再只读掉 maxId 及以前的。
     */
    @Test
    void readAllUpToMaxIdReturnsTheIdsThatWereUnread()
    {
        when(svc.selectUnreadIds(7L, "user", "like", 50L, 500)).thenReturn(Arrays.asList(50L, 31L, 7L));
        when(svc.markAllRead(7L, "user", "like", 50L)).thenReturn(3);
        Map<String, Object> want = new HashMap<String, Object>();
        want.put("updated", 3);
        want.put("unreadIds", Arrays.asList(50L, 31L, 7L));
        assertEquals(want, controller.readAll(null, "like", 50L).get("data"));
        // 先读ID、再标已读：反过来就读不到了
        org.mockito.InOrder order = inOrder(svc);
        order.verify(svc).selectUnreadIds(7L, "user", "like", 50L, AppMessageController.UNREAD_IDS_MAX);
        order.verify(svc).markAllRead(7L, "user", "like", 50L);
        verify(svc, never()).markAllRead(7L, "user", "like");
        verify(svc, never()).markAllRead(7L, "user");
    }

    @Test
    void readAllMaxIdOnlyLimitsTheRowsItTouches()
    {
        String upTo = sql("markAllRead", "bucket", "like", "maxId", 50L);
        assertTrue(upTo.endsWith("and " + flat(BUCKET_OF) + " = ? and msg_id <= ?"), upTo);
        assertFalse(upTo.contains("from kg_message"), "MySQL 1093");
        // 不带 maxId：和以前一样
        assertFalse(sql("markAllRead", "bucket", "like").contains("msg_id <="));
        String ids = sql("selectUnreadIds", "bucket", "like", "maxId", 50L, "limit", 500);
        assertTrue(ids.startsWith("select msg_id from kg_message where recipient_id = ? and mode = ? and read_flag = '0' and del_flag = '0'"), ids);
        assertTrue(ids.contains("kg_chat_reception"), "和已读同一套 ACL");
        assertTrue(ids.endsWith("and " + flat(BUCKET_OF) + " = ? and msg_id <= ? order by msg_id desc limit ?"), ids);
    }
}
