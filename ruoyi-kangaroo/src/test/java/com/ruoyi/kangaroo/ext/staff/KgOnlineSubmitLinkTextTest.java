package com.ruoyi.kangaroo.ext.staff;

import java.util.Collections;
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
 * 提交成果的「成果链接」原样收下（2026-10-16 主人定）：小红书、抖音复制出来的「链接 + 口令」整段文字不再被
 * 「成果链接需以 http(s):// 开头」拒掉；只去首尾空白，500 字内，和补充说明一起过内容检查（不连库）。
 */
class KgOnlineSubmitLinkTextTest
{
    private static final String XHS = "https://xhslink.cn/o/AYmULXdGz76 复制口令，打开【小红书】即可畅读全文";

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

    @Test void shareTextWithoutLeadingUrlIsAcceptedAsIs()
    {
        Map<String, Object> row = inserted(p("link", "  " + XHS + "\n", "note", "已发布"));
        assertEquals(XHS, row.get("link"), "原样保存，只去首尾空白");
        verify(guard).check(XHS, "已发布");
    }

    @Test void anyTextIsAccepted()
    {
        String text = "【抖音】看看我的作品 7.43 复制打开抖音 Kjc:/ abc";
        assertEquals(text, inserted(p("link", text)).get("link"));
    }

    @Test void fiveHundredCharactersFit()
    {
        StringBuilder s = new StringBuilder(XHS);
        while (s.length() < KgOnlineTaskService.LINK_MAX) s.append('字');
        assertEquals(500, KgOnlineTaskService.LINK_MAX);
        assertEquals(s.toString(), inserted(p("link", s.toString())).get("link"));
    }

    @Test void longerThanFiveHundredIsRejectedBeforeWriting()
    {
        StringBuilder s = new StringBuilder();
        while (s.length() <= KgOnlineTaskService.LINK_MAX) s.append('字');
        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(7L, p("link", s.toString())));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("链接或口令 500 字内"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void blockedWordsInTheLinkTextAreRejected()
    {
        when(guard.check(anyString(), anyString())).thenThrow(new ServiceException("内容包含不允许发布的信息，请修改后再发", 422));
        assertThrows(ServiceException.class, () -> service.submit(7L, p("link", XHS, "note", "")));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void stillNeedsALinkOrAnImage()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(7L, p("link", "   ", "note", "说明")));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertEquals("请提交成果链接或图片", e.getMessage());
    }
}
