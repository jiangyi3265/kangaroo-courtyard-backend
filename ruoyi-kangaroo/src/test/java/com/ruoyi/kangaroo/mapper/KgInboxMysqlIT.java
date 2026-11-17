package com.ruoyi.kangaroo.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.community.KgPostService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.service.impl.KgMessageServiceImpl;

/**
 * 消息页改版（2026-10-15）在真实 MySQL 上的效果：分组归属、分页、按组已读不报 1093、互动通知随对象消失、点赞合并与并发只插一行。
 * 只在设置了 KG_TEST_DB_PASSWORD 时跑（和其它 *IT 一样不进默认的 mvn test）：
 * 新建一次性库 kg_scratch_xxxxxxxx，表结构从 KG_TEST_TEMPLATE_DB（默认 dkxy）照抄（create table like，只读模板），跑完删库。
 * 模板库还没执行 V43 时这里补上 kg_message.actor_id。
 */
@EnabledIfEnvironmentVariable(named = "KG_TEST_DB_PASSWORD", matches = ".+")
class KgInboxMysqlIT
{
    static final String[] TABLES = { "kg_message", "kg_app_user", "kg_post", "kg_post_comment", "kg_post_like", "kg_post_comment_like",
            "kg_user_block", "kg_notify_pref", "kg_staff_grant", "kg_chat_conv", "kg_chat_member", "kg_chat_reception", "kg_role_switch",
            "kg_application", "kg_job", "kg_store", "kg_activity", "kg_user_follow" };
    static final String ROUTE = "/pages/community/index?postId=11&view=comments";

    String database;
    JdbcTemplate root, sql;
    KgMessageMapper mapper;
    KgMessageServiceImpl messages;
    KgPostService posts;

    static DataSource source(String db)
    {
        String password = System.getenv("KG_TEST_DB_PASSWORD");
        String key = System.getenv("KG_MYSQL_RSA_PUBLIC_KEY_FILE");
        String security;
        try
        {
            security = key == null || key.isEmpty() ? "&allowPublicKeyRetrieval=true"
                    : "&allowPublicKeyRetrieval=false&serverRSAPublicKeyFile=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name());
        }
        catch (java.io.UnsupportedEncodingException e)
        {
            throw new IllegalStateException(e);
        }
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + db + "?serverTimezone=Asia/Shanghai&useSSL=false"
                + "&sessionVariables=time_zone%3D'%2B08%3A00'" + security, "root", password);
    }

    @BeforeEach
    void setup() throws Exception
    {
        String template = Optional.ofNullable(System.getenv("KG_TEST_TEMPLATE_DB")).filter(s -> !s.isEmpty()).orElse("dkxy");
        assertTrue(template.matches("[A-Za-z0-9_]+"), template);
        database = "kg_scratch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        root = new JdbcTemplate(source(""));
        root.execute("create database " + database + " character set utf8mb4 collate utf8mb4_unicode_ci");
        DataSource ds = source(database);
        sql = new JdbcTemplate(ds);
        for (String t : TABLES)
        {
            sql.execute("create table " + t + " like " + template + "." + t);
        }
        if (sql.queryForObject("select count(*) from information_schema.columns where table_schema = database() and table_name = 'kg_message' and column_name = 'actor_id'", Integer.class) == 0)
        {
            sql.execute("alter table kg_message add column actor_id bigint null after biz_id");
        }
        DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        Configuration cfg = new Configuration(new Environment("it", new SpringManagedTransactionFactory(), ds));
        cfg.getTypeAliasRegistry().registerAlias("KgMessage", KgMessage.class);
        String resource = "mapper/kangaroo/KgMessageMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource))
        {
            new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        mapper = new SqlSessionTemplate(factory).getMapper(KgMessageMapper.class);
        KgJdbc db = new KgJdbc(ds);
        messages = new KgMessageServiceImpl();
        ReflectionTestUtils.setField(messages, "kgMessageMapper", mapper);
        ReflectionTestUtils.setField(messages, "jdbc", db);
        KgPostService target = new KgPostService();
        ReflectionTestUtils.setField(target, "db", db);
        ReflectionTestUtils.setField(target, "guard", mock(KgCommunityGuard.class));
        ReflectionTestUtils.setField(target, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(target, "messages", messages);
        ProxyFactory f = new ProxyFactory(target);
        f.setProxyTargetClass(true);
        f.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        posts = (KgPostService) f.getProxy();
        fixtures();
    }

    @AfterEach
    void cleanup()
    {
        if (root != null && database != null && database.matches("kg_scratch_[a-f0-9]{8}"))
        {
            root.execute("drop database if exists " + database);
        }
    }

    /** 用户 1 是作者；2–7、9 有昵称（u2…），8 没填昵称；动态 11（两张图）；1 的一级评论 21，2 回复 1 的评论 22 */
    void fixtures()
    {
        for (long u = 1; u <= 12; u++)
        {
            sql.update("insert into kg_app_user (user_id, openid, nickname, status, del_flag) values (?, ?, ?, '0', '0')", u, "it-" + u, u == 8 ? "" : "u" + u);
        }
        sql.update("insert into kg_post (post_id, app_user_id, kind, title, content, images, status, likes, comments, del_flag, create_time) "
                + "values (11, 1, 'daily', '大理三天', '正文', '/profile/a.png,/profile/b.png', 'online', 0, 2, '0', now())");
        sql.update("insert into kg_post_comment (comment_id, post_id, app_user_id, parent_id, reply_user_id, content, status, create_time, del_flag) "
                + "values (21, 11, 1, 0, null, '欢迎来大理', 'visible', now(), '0'), (22, 11, 2, 21, 1, '怎么去古城？', 'visible', now(), '0')");
    }

    long msg(long to, String mode, String category, String bizType, Long bizId, Long actor, String readFlag)
    {
        sql.update("insert into kg_message (recipient_id, mode, category, title, content, biz_type, biz_id, actor_id, route, read_flag, del_flag, create_time) "
                + "values (?, ?, ?, ?, '正文', ?, ?, ?, '/r', ?, '0', now())", to, mode, category, category + "/" + bizType, bizType, bizId, actor, readFlag);
        // DriverManagerDataSource 每条语句一个新连接，last_insert_id() 拿不到；这些用例都是单线程写
        return sql.queryForObject("select max(msg_id) from kg_message", Long.class);
    }

    Map<String, long[]> buckets(long to, String mode)
    {
        Map<String, long[]> out = new TreeMap<String, long[]>();
        for (Map<String, Object> r : mapper.countByBucket(to, mode))
        {
            out.put(String.valueOf(r.get("bucket")), new long[] { ((Number) r.get("total")).longValue(), ((Number) r.get("unread")).longValue() });
        }
        return out;
    }

    List<Long> ids(long to, String mode, String bucket)
    {
        List<Long> out = new ArrayList<Long>();
        for (KgMessage m : mapper.selectAppMessagePage(to, mode, null, bucket, 0, 50))
        {
            out.add(m.getMsgId());
        }
        return out;
    }

    // ---------------------------------------------------------------- 分组

    @Test
    void bucketOfForUserAndStaffModes()
    {
        Map<String, List<Long>> want = new HashMap<String, List<Long>>();
        String[][] user = { { "comment", "post" }, { "like", "post_like" }, { "system", null }, { "system", "job_subscribe" }, { "system", "post" },
                { "system", "activity" }, { "apply", "application" }, { "interview", "application" }, { "contract", "insurance" },
                { "order", "stay_order" }, { "chat", "x" }, { "group", "x" } };
        String[] userBucket = { "comment", "like", "system", "system", "system", "main", "main", "main", "main", "main", "main", "main" };
        for (int i = 0; i < user.length; i++)
        {
            want.computeIfAbsent("user:" + userBucket[i], k -> new ArrayList<Long>()).add(msg(9, "user", user[i][0], user[i][1], 11L, null, "0"));
        }
        String[][] staff = { { "apply", "application" }, { "interview", "application" }, { "contract", "application" }, { "staff", "resume_forward" },
                { "system", "activity" }, { "order", "activity" }, { "order", "stay_order" }, { "system", "job" }, { "system", null }, { "chat", "x" } };
        String[] staffBucket = { "apply", "apply", "apply", "apply", "apply", "order", "order", "system", "system", "main" };
        for (int i = 0; i < staff.length; i++)
        {
            want.computeIfAbsent("staff:" + staffBucket[i], k -> new ArrayList<Long>()).add(msg(9, "staff", staff[i][0], staff[i][1], 11L, null, "0"));
        }
        for (Map.Entry<String, List<Long>> e : want.entrySet())
        {
            String[] k = e.getKey().split(":");
            List<Long> got = ids(9, k[0], k[1]);
            Collections.sort(got);
            assertEquals(e.getValue(), got, e.getKey());
            assertEquals(e.getValue().size(), buckets(9, k[0]).get(k[1])[0], e.getKey());
        }
        assertEquals(new TreeSet<String>(Arrays.asList("comment", "like", "main", "system")), buckets(9, "user").keySet());
        assertEquals(new TreeSet<String>(Arrays.asList("apply", "main", "order", "system")), buckets(9, "staff").keySet());
        // 不分组时和老列表一样全给
        assertEquals(user.length, mapper.selectAppMessagePage(9L, "user", null, null, 0, 50).size());
        assertEquals(user.length, mapper.countUnread(9L, "user"));
    }

    // ---------------------------------------------------------------- 分页

    @Test
    void pagingWalksNewestFirstWithoutGapsOrRepeats()
    {
        for (int i = 0; i < 45; i++)
        {
            msg(9, "user", "apply", "application", (long) i, null, "0");
        }
        sql.update("update kg_message set create_time = date_add('2026-10-01 00:00:00', interval msg_id second)");
        List<Long> seen = new ArrayList<Long>();
        for (int page = 0; page < 3; page++)
        {
            List<KgMessage> rows = mapper.selectAppMessagePage(9L, "user", null, "main", page * 20, 21);
            assertEquals(page < 2 ? 21 : 5, rows.size(), "第 " + (page + 1) + " 页多取一条来判断还有没有下一页");
            for (KgMessage m : rows.subList(0, Math.min(20, rows.size())))
            {
                seen.add(m.getMsgId());
            }
        }
        List<Long> sorted = new ArrayList<Long>(seen);
        sorted.sort(Comparator.reverseOrder());
        assertEquals(45, new HashSet<Long>(seen).size());
        assertEquals(sorted, seen, "最近的在前");
    }

    // ---------------------------------------------------------------- 按组已读

    @Test
    void readAllPerBucketRunsOnMysqlAndOnlyClearsThatBucket()
    {
        msg(9, "user", "system", "job", 1L, null, "0");
        msg(9, "user", "system", "job", 2L, null, "0");
        msg(9, "user", "apply", "application", 3L, null, "0");
        msg(9, "user", "like", "post_like", 11L, 2L, "0");
        msg(9, "staff", "apply", "application", 4L, null, "0");
        msg(9, "staff", "system", "activity", 5L, null, "0");
        msg(9, "staff", "order", "stay_order", 6L, null, "0");
        assertEquals(2, mapper.markAllRead(9L, "user", "system", null));
        assertArrayEquals(new long[] { 2, 0 }, buckets(9, "user").get("system"));
        assertArrayEquals(new long[] { 1, 1 }, buckets(9, "user").get("main"));
        assertArrayEquals(new long[] { 1, 1 }, buckets(9, "user").get("like"));
        assertEquals(2, mapper.markAllRead(9L, "staff", "apply", null));
        assertArrayEquals(new long[] { 1, 1 }, buckets(9, "staff").get("order"));
        // 不带分组 = 老的「全部已读」
        assertEquals(2, mapper.markAllRead(9L, "user", null, null));
        assertEquals(0, mapper.countUnread(9L, "user"));
        assertEquals(1, mapper.countUnread(9L, "staff"));
    }

    // ---------------------------------------------------------------- 互动通知随对象消失

    @Test
    void deletingHidingOrBlockingHidesInteractionRowsAndLowersUnread()
    {
        long reply = msg(1, "user", "comment", "comment", 22L, 2L, "0");
        long like = msg(1, "user", "like", "post_like", 11L, 3L, "0");
        long legacy = msg(1, "user", "comment", "post", 11L, null, "0");
        long cl = msg(1, "user", "like", "comment_like", 21L, 2L, "0");
        assertEquals(4, mapper.countUnread(1L, "user"));

        List<KgMessage> rows = mapper.selectAppMessagePage(1L, "user", null, "comment", 0, 21);
        KgMessage r = rows.stream().filter(m -> m.getMsgId() == reply).findFirst().get();
        assertEquals("u2", r.getActorName());
        assertEquals("/profile/a.png", r.getCover());
        assertEquals("大理三天", r.getPostTitle());
        assertEquals(Long.valueOf(11L), r.getPostId());
        assertEquals("欢迎来大理", r.getQuote(), "回复：引用被回复的那条");
        KgMessage old = rows.stream().filter(m -> m.getMsgId() == legacy).findFirst().get();
        assertNull(old.getActorName(), "老评论通知没有触发人");
        assertEquals("/profile/a.png", old.getCover());
        assertEquals("欢迎来大理", mapper.selectAppMessagePage(1L, "user", null, "like", 0, 21).stream()
                .filter(m -> m.getMsgId() == cl).findFirst().get().getQuote(), "赞评论：引用被赞的那条");

        // 评论被删：那条评论通知消失
        sql.update("update kg_post_comment set del_flag = '2' where comment_id = 22");
        assertFalse(ids(1, "user", "comment").contains(reply));
        assertEquals(3, mapper.countUnread(1L, "user"));
        sql.update("update kg_post_comment set del_flag = '0' where comment_id = 22");
        // 评论被隐藏
        sql.update("update kg_post_comment set status = 'hidden' where comment_id = 21");
        assertFalse(ids(1, "user", "like").contains(cl));
        sql.update("update kg_post_comment set status = 'visible' where comment_id = 21");
        // 动态被隐藏 / 删除：四条都不显示
        sql.update("update kg_post set status = 'hidden' where post_id = 11");
        assertEquals(0, mapper.countUnread(1L, "user"));
        assertTrue(ids(1, "user", "comment").isEmpty() && ids(1, "user", "like").isEmpty());
        sql.update("update kg_post set status = 'online', del_flag = '2' where post_id = 11");
        assertEquals(0, mapper.countUnread(1L, "user"));
        sql.update("update kg_post set del_flag = '0' where post_id = 11");
        assertEquals(4, mapper.countUnread(1L, "user"));
        // 拉黑：任一方向
        sql.update("insert into kg_user_block (user_id, blocked_id, create_time) values (1, 2, now())");
        assertEquals(2, mapper.countUnread(1L, "user"), "1 拉黑 2：2 触发的两条都不显示");
        assertEquals(Arrays.asList(legacy), ids(1, "user", "comment"));
        sql.update("delete from kg_user_block");
        sql.update("insert into kg_user_block (user_id, blocked_id, create_time) values (3, 1, now())");
        assertFalse(ids(1, "user", "like").contains(like), "3 拉黑 1 也不显示");
        sql.update("delete from kg_user_block");
        assertEquals(4, mapper.countUnread(1L, "user"));
        // 分组计数与列表同一口径
        assertArrayEquals(new long[] { 2, 2 }, buckets(1, "user").get("comment"));
        // 老列表（不带分组）也按同一规则
        sql.update("update kg_post set status = 'hidden' where post_id = 11");
        assertTrue(mapper.selectAppMessages(1L, "user", null).isEmpty());
        assertTrue(mapper.countByCategory(1L, "user").isEmpty());
    }

    // ---------------------------------------------------------------- 点赞合并

    KgMessage likeRow()
    {
        List<KgMessage> rows = mapper.selectAppMessagePage(1L, "user", null, "like", 0, 21);
        return rows.isEmpty() ? null : rows.get(0);
    }

    int likeRows()
    {
        return sql.queryForObject("select count(*) from kg_message where recipient_id = 1 and biz_type = 'post_like' and biz_id = 11", Integer.class);
    }

    @Test
    void likesMergeIntoOneRowAndRetractOnlyWhileUnread() throws Exception
    {
        posts.like(11L, 2L);
        assertEquals("u2 赞了你的动态", likeRow().getTitle());
        posts.like(11L, 3L);
        posts.like(11L, 8L);
        KgMessage row = likeRow();
        assertEquals("用户8 等 3 人赞了你的动态", row.getTitle());
        assertEquals("用户8", row.getActorName());
        assertEquals(Long.valueOf(8L), row.getActorId());
        assertEquals("0", row.getReadFlag());
        assertEquals(1, mapper.countUnread(1L, "user"), "合并的一行只算 1 条未读");
        long id = row.getMsgId();
        // 未读时取消：重算人数和最近点赞的人，时间不动
        Date before = row.getCreateTime();
        Thread.sleep(1100);
        posts.like(11L, 8L);
        row = likeRow();
        assertEquals("u3 等 2 人赞了你的动态", row.getTitle());
        assertEquals(before, row.getCreateTime());
        // 读过以后：取消不改；新的赞从读的时间起算
        assertEquals(1, mapper.markAllRead(1L, "user", "like", null));
        Thread.sleep(1100);
        posts.like(11L, 2L);
        assertEquals("u3 等 2 人赞了你的动态", likeRow().getTitle());
        assertEquals("1", likeRow().getReadFlag());
        posts.like(11L, 4L);
        row = likeRow();
        assertEquals("u4 赞了你的动态", row.getTitle());
        assertEquals("0", row.getReadFlag());
        assertNotNull(sql.queryForObject("select read_time from kg_message where msg_id = ?", Date.class, id), "read_time 保留");
        // 这一轮唯一的人取消 → 整行不显示；再有人赞 → 同一行恢复
        posts.like(11L, 4L);
        assertNull(likeRow());
        assertEquals(0, mapper.countUnread(1L, "user"));
        posts.like(11L, 5L);
        row = likeRow();
        assertEquals("u5 赞了你的动态", row.getTitle());
        assertEquals(id, row.getMsgId().longValue());
        // 作者自己点赞不发
        posts.like(11L, 1L);
        assertEquals("u5 赞了你的动态", likeRow().getTitle());
        assertEquals(1, likeRows());
        // 赞评论单独一行
        posts.likeComment(22L, 3L);
        assertEquals("u3 赞了你的评论", sql.queryForObject("select title from kg_message where recipient_id = 2 and biz_type = 'comment_like' and biz_id = 22", String.class));
        // 暂停了社区互动：不写
        sql.update("insert into kg_notify_pref (app_user_id, pref_key, enabled, freq, update_time) values (2, 'community', '0', 'instant', now())");
        posts.likeComment(22L, 4L);
        assertEquals("u3 赞了你的评论", sql.queryForObject("select title from kg_message where recipient_id = 2 and biz_type = 'comment_like' and biz_id = 22", String.class));
    }

    @Test
    void concurrentLikesOnOnePostProduceOneRow() throws Exception
    {
        int n = 8;
        long[] users = { 2, 3, 4, 5, 6, 7, 9, 10 };
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier start = new CyclicBarrier(n);
        try
        {
            List<Future<?>> work = new ArrayList<Future<?>>();
            for (long u : users)
            {
                work.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return posts.like(11L, u);
                }));
            }
            for (Future<?> w : work)
            {
                w.get(30, TimeUnit.SECONDS);
            }
        }
        finally
        {
            pool.shutdownNow();
        }
        assertEquals(1, likeRows());
        assertEquals(n, sql.queryForObject("select likes from kg_post where post_id = 11", Integer.class).intValue());
        assertTrue(likeRow().getTitle().endsWith(" 等 " + n + " 人赞了你的动态"), likeRow().getTitle());
        assertEquals(1, mapper.countUnread(1L, "user"));
    }

    // ---------------------------------------------------------------- 2026-10-16 复核修复

    /** 子页带 maxId 标已读：只读掉 maxId 及以前的，先读出的未读ID正好是被读掉的那些（新的在前） */
    @Test
    void readAllUpToMaxIdOnlyClearsOlderRowsAndListsThem()
    {
        long a = msg(9, "user", "system", "job", 1L, null, "1");
        long b = msg(9, "user", "system", "job", 2L, null, "0");
        long c = msg(9, "user", "system", "job", 3L, null, "0");
        long d = msg(9, "user", "system", "job", 4L, null, "0");
        long other = msg(9, "user", "like", "post_like", 11L, 2L, "0");
        assertTrue(a < b && b < c && c < d && d < other);
        assertEquals(Arrays.asList(c, b), mapper.selectUnreadIds(9L, "user", "system", c, 500));
        assertEquals(Arrays.asList(c), mapper.selectUnreadIds(9L, "user", "system", c, 1));
        assertEquals(2, mapper.markAllRead(9L, "user", "system", c));
        // 进来之后新来的 d、别的组都还没读
        assertEquals(Arrays.asList(d), mapper.selectUnreadIds(9L, "user", "system", null, 500));
        assertArrayEquals(new long[] { 4, 1 }, buckets(9, "user").get("system"));
        assertEquals(Arrays.asList(other, d), mapper.selectUnreadIds(9L, "user", null, null, 500), "不分组：全部没读的，新的在前");
    }

    /** 「u3 等 2 人赞了你的动态」：拉黑 u3 后改指 u2，整行照样显示；再拉黑 u2，一个不剩才不显示 */
    @Test
    void blockingTheLatestLikerRepointsTheMergedRow()
    {
        posts.like(11L, 2L);
        posts.like(11L, 3L);
        assertEquals("u3 等 2 人赞了你的动态", likeRow().getTitle());
        posts.block(3L, 1L);
        KgMessage row = likeRow();
        assertNotNull(row, "别人点的赞还在，不能整行跟着被拉黑的人一起不显示");
        assertEquals("u2 赞了你的动态", row.getTitle());
        assertEquals(Long.valueOf(2L), row.getActorId());
        assertEquals(1, mapper.countUnread(1L, "user"));
        posts.block(2L, 1L);
        assertNull(likeRow());
        assertEquals(0, mapper.countUnread(1L, "user"));
        assertEquals(1, likeRows(), "只是改指 / 隐藏，不多插行");
    }

    /** 被拉黑的人收到的、由拉黑人触发的点赞合并行也改指（双方任一方向拉黑都不显示对方） */
    @Test
    void blockerLikesOnTheBlockedUsersPostAreRepointedToo()
    {
        sql.update("insert into kg_post (post_id, app_user_id, kind, title, content, images, status, likes, comments, del_flag, create_time) "
                + "values (12, 5, 'daily', 't', 'c', '', 'online', 0, 0, '0', now())");
        posts.like(12L, 2L);
        posts.like(12L, 1L);
        List<KgMessage> rows = mapper.selectAppMessagePage(5L, "user", null, "like", 0, 21);
        assertEquals("u1 等 2 人赞了你的动态", rows.get(0).getTitle());
        posts.block(5L, 1L);
        rows = mapper.selectAppMessagePage(5L, "user", null, "like", 0, 21);
        assertEquals(1, rows.size());
        assertEquals("u2 赞了你的动态", rows.get(0).getTitle());
    }

    /** 触发人停用 / 注销：评论、赞通知不显示、不算未读；恢复后又出现；按组已读照样能跑（不报 1093） */
    @Test
    void disabledOrDeletedActorsHideTheirInteractionRows()
    {
        posts.like(11L, 2L);
        long comment = msg(1, "user", "comment", "comment", 22L, 2L, "0");
        long legacy = msg(1, "user", "comment", "post", 11L, null, "0");
        assertEquals(3, mapper.countUnread(1L, "user"));
        sql.update("update kg_app_user set status = '1' where user_id = 2");
        assertNull(likeRow());
        assertEquals(Arrays.asList(legacy), ids(1, "user", "comment"));
        assertEquals(1, mapper.countUnread(1L, "user"), "没记触发人的老通知照旧");
        sql.update("update kg_app_user set status = '0', del_flag = '2' where user_id = 2");
        assertEquals(1, mapper.countUnread(1L, "user"));
        sql.update("update kg_app_user set del_flag = '0' where user_id = 2");
        assertEquals(3, mapper.countUnread(1L, "user"));
        assertTrue(ids(1, "user", "comment").contains(comment));
        sql.update("update kg_app_user set status = '1' where user_id = 2");
        assertEquals(2, mapper.markAllRead(1L, "user", "comment", null));
        assertEquals(1, mapper.markAllRead(1L, "user", null, null));
    }
}
