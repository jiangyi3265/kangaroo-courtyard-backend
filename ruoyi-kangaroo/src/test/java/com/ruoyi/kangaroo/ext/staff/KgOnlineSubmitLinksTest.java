package com.ruoyi.kangaroo.ext.staff;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-10-17 主人反馈：有人一次发了不止一篇帖子，「提交成果」分三栏、最多 3 个链接或口令，每栏选填、每个 500 字内。
 * 几个链接用换行连起来存在 kg_online_submission.link（V50 放宽到 1600）；每个链接里的换行先并成空格，换行只当分隔符。
 * 老版本小程序只传 link 一段文字，照旧按一个链接收（不连库）。
 */
class KgOnlineSubmitLinksTest
{
    private static final String XHS = "https://xhslink.cn/o/AYmULXdGz76 复制口令，打开【小红书】即可畅读全文";
    private static final String DOUYIN = "7.43 复制打开抖音，看看【小院的作品】海边日落 https://v.douyin.com/iRNBho6u/ abc:/";
    private static final String BLOG = "https://example.com/post/3";

    private final KgJdbc db = mock(KgJdbc.class);
    private final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    private final KgOnlineTaskService service = new KgOnlineTaskService();
    private final Map<String, Object> user = p("status", "0", "delFlag", "0");
    private final Map<String, Object> app = p("appUserId", 2L, "jobId", 8L, "storeId", 3L, "status", "review_passed", "delFlag", "0");

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(service, "guard", guard);
        // 本人的一单进行中的线上任务：没有截止时间、没交过、没有结算单 → 可以提交
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(p("appUserId", 2L, "jobId", 8L, "title", "探店笔记",
                "jobStore", 3L, "status", "review_passed", "workerStatus", "0", "workerDelFlag", "0", "commissionFen", 5000L));
        when(db.list(anyString(), anyMap())).thenReturn(Collections.emptyList());
        when(db.one(anyString(), anyMap())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            if (sql.contains("kg_app_user") && sql.contains("for update")) return user;
            if (sql.contains("kg_application where")) return app;
            return null;
        });
        AppLoginUser owner = new AppLoginUser(); owner.setUserId(2L); AppUserContext.set(owner);
    }

    @AfterEach void clear() { AppUserContext.remove(); }

    /** 提交到写库那一步就停下，拿到要写进 kg_online_submission 的那一行 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> inserted(Map<String, Object> body)
    {
        when(db.insert(eq("kg_online_submission"), anyMap())).thenThrow(new IllegalStateException("stop"));
        assertThrows(IllegalStateException.class, () -> service.submit(7L, body));
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_online_submission"), row.capture());
        return row.getValue();
    }

    private ServiceException rejected(Map<String, Object> body)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(7L, body));
        assertEquals(Integer.valueOf(422), e.getCode());
        verify(db, never()).insert(anyString(), anyMap());
        return e;
    }

    private static String chars(int n)
    {
        StringBuilder s = new StringBuilder();
        while (s.length() < n) s.append('字');
        return s.toString();
    }

    @Test void threeLinksAreStoredJoinedByNewlinesAndEachIsContentChecked()
    {
        Map<String, Object> row = inserted(p("links", Arrays.asList(XHS, DOUYIN, BLOG), "note", "发了三篇"));
        assertEquals(XHS + "\n" + DOUYIN + "\n" + BLOG, row.get("link"));
        verify(guard).check(XHS, DOUYIN, BLOG, "发了三篇");
    }

    @Test void emptyBoxesAreDroppedAndTheRestKeepTheirOrder()
    {
        assertEquals(XHS + "\n" + BLOG, inserted(p("links", Arrays.asList("  " + XHS + " ", "", "   ", BLOG))).get("link"));
    }

    @Test void lineBreaksInsideOneLinkAreCollapsedSoNewlineOnlySeparates()
    {
        String pasted = "  【小院的作品】海边日落\n\nhttps://v.douyin.com/iRNBho6u/ \t 复制打开抖音\r\n";
        Map<String, Object> row = inserted(p("links", Arrays.asList(pasted, BLOG)));
        assertEquals("【小院的作品】海边日落 https://v.douyin.com/iRNBho6u/ 复制打开抖音\n" + BLOG, row.get("link"));
        assertEquals(2, KgOnlineTaskService.splitLinks(row.get("link")).size());
    }

    @Test void eachLinkMayHaveFiveHundredCharacters()
    {
        String full = chars(KgOnlineTaskService.LINK_MAX);
        Map<String, Object> row = inserted(p("links", Arrays.asList(full, full, full)));
        assertEquals(3 * 500 + 2, String.valueOf(row.get("link")).length(), "3 × 500 + 2 个换行，V50 的 1600 放得下");
        assertTrue(String.valueOf(row.get("link")).length() <= 1600);
    }

    @Test void fourLinksAreRejected()
    {
        ServiceException e = rejected(p("links", Arrays.asList(XHS, DOUYIN, BLOG, "https://example.com/4")));
        assertEquals("最多提交 3 个链接或口令", e.getMessage());
        assertEquals(3, KgOnlineTaskService.LINKS_MAX);
    }

    @Test void tooLongLinkIsRejectedNamingWhichOne()
    {
        ServiceException e = rejected(p("links", Arrays.asList(XHS, chars(KgOnlineTaskService.LINK_MAX + 1))));
        assertEquals("第 2 个链接或口令超过 500 字", e.getMessage());
    }

    @Test void blockedWordsInAnyLinkAreRejected()
    {
        // 第 2 个链接有问题：两个链接 + 说明一起交给内容检查，被拦下就不落库
        when(guard.check(eq(XHS), eq("违禁 口令"), eq(""))).thenThrow(new ServiceException("内容包含不允许发布的信息，请修改后再发", 422));
        assertThrows(ServiceException.class, () -> service.submit(7L, p("links", Arrays.asList(XHS, "违禁 口令"), "note", "")));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void stillNeedsALinkOrAnImage()
    {
        assertEquals("请提交成果链接或图片", rejected(p("links", Arrays.asList("", "  ", "\n"), "note", "说明")).getMessage());
    }

    @Test void oldMiniProgramSendingOnlyLinkStillWorks()
    {
        Map<String, Object> row = inserted(p("link", "  " + XHS + "\n", "note", "已发布"));
        assertEquals(XHS, row.get("link"));
        verify(guard).check(XHS, "已发布");
    }

    @Test void oldMiniProgramKeepsTheOldLengthRuleAndMessage()
    {
        ServiceException e = rejected(p("link", "口令" + chars(KgOnlineTaskService.LINK_MAX)));
        assertTrue(e.getMessage().contains("链接或口令 500 字内"), e.getMessage());
    }

    @Test void linksListWinsOverTheJoinedLegacyField()
    {
        // 新版小程序两样都传（link 是连好的，给老后端兜底）：以 links 为准
        Map<String, Object> row = inserted(p("link", XHS + "\n" + BLOG, "links", Arrays.asList(XHS, BLOG)));
        assertEquals(XHS + "\n" + BLOG, row.get("link"));
    }

    @Test void splitLinksDropsEmptiesAndKeepsOldSingleLinks()
    {
        assertEquals(Arrays.asList(XHS, BLOG), KgOnlineTaskService.splitLinks(XHS + "\n\n" + BLOG + "\n"));
        assertEquals(Collections.singletonList(XHS), KgOnlineTaskService.splitLinks(XHS));
        assertTrue(KgOnlineTaskService.splitLinks("").isEmpty());
        assertTrue(KgOnlineTaskService.splitLinks(null).isEmpty());
    }

    @Test @SuppressWarnings("unchecked")
    void viewReturnsTheLinksOneByOneNextToTheJoinedText()
    {
        when(db.list(contains("from kg_online_submission where app_id"), anyMap())).thenReturn(Arrays.asList(
                p("round", 2, "link", XHS + "\n" + DOUYIN + "\n" + BLOG, "images", "", "note", "", "status", "submitted", "createTime", new java.util.Date()),
                p("round", 1, "link", BLOG, "images", "", "note", "", "status", "returned", "createTime", new java.util.Date())));
        Map<String, Object> m = service.mine(7L);
        Map<String, Object> latest = (Map<String, Object>) m.get("latest");
        assertEquals(Arrays.asList(XHS, DOUYIN, BLOG), latest.get("links"));
        assertEquals(XHS + "\n" + DOUYIN + "\n" + BLOG, latest.get("link"), "老版本小程序照旧读 link");
        List<Map<String, Object>> subs = (List<Map<String, Object>>) m.get("submissions");
        assertEquals(Collections.singletonList(BLOG), subs.get(1).get("links"), "老数据就是一个链接");
    }
}
