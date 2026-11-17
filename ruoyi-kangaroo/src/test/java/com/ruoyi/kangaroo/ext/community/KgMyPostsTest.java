package com.ruoyi.kangaroo.ext.community;

import com.ruoyi.kangaroo.ext.KgJdbc;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgMyPostsTest
{
    @Test
    void historyIncludesModerationStatesAndPagesBeyondFiftyWithOwnerScope()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgPostService service = new KgPostService();
        ReflectionTestUtils.setField(service, "db", db);
        // 帖子列表那一句（一页的点赞、评论数另各查一次，见 KgPostCommentCountTest）
        when(db.list(startsWith("select p.* from kg_post p"), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Map<String, Object> params = invocation.getArgument(1);
            assertTrue(sql.contains("p.app_user_id = :u"));
            assertTrue(sql.contains("p.del_flag = '0'"));
            assertFalse(sql.contains("p.status ="));
            assertEquals(9001L, params.get("u"));
            assertEquals(20, params.get("size"));
            assertEquals(60, params.get("off"));
            return Arrays.asList(p("postId", 1L, "appUserId", 9001L, "status", "pending", "content", "待审核正文"),
                    p("postId", 2L, "appUserId", 9001L, "status", "rejected", "auditReason", "请补充活动地点"));
        });
        List<Map<String, Object>> rows = service.mine(9001L, 4);
        assertEquals("待审核正文", rows.get(0).get("content"));
        assertEquals("pending", rows.get(0).get("status"));
        assertEquals("请补充活动地点", rows.get(1).get("auditReason"));
    }

    @Test
    void legacyClientAndUntrustedPageBoundsStayCompatible()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgPostService service = new KgPostService();
        ReflectionTestUtils.setField(service, "db", db);
        service.mine(9001L, null);
        verify(db).list(anyString(), eq(p("u", 9001L, "size", 50, "off", 0)));
        service.mine(9001L, Integer.MIN_VALUE);
        verify(db).list(anyString(), eq(p("u", 9001L, "size", 20, "off", 0)));
        service.mine(9001L, Integer.MAX_VALUE);
        verify(db).list(anyString(), eq(p("u", 9001L, "size", 20, "off", 1999980)));
    }
}
