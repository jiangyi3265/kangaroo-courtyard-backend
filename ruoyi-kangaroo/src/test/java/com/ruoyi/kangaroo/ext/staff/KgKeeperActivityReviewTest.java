package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppLoginUser;

/** 2026-11-03 主人「小院主理人，审核活动」：本店活动、不能审自己的、要人工把关的内容不能放行 */
class KgKeeperActivityReviewTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgActivityService activities = mock(KgActivityService.class);
    private final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    private final KgKeeperActivityReview review = new KgKeeperActivityReview();

    @BeforeEach
    void setup()
    {
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(review, "db", db);
        ReflectionTestUtils.setField(review, "support", support);
        ReflectionTestUtils.setField(review, "activities", activities);
        ReflectionTestUtils.setField(review, "guard", guard);
        when(guard.check(any(), any(), any(), any())).thenReturn("pass");
    }

    @AfterEach
    void clear()
    {
        RolePhase.set(RolePhase.LEGACY);
    }

    private static StaffSupport.Ctx ctx(String role, long uid, long store)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        AppLoginUser me = new AppLoginUser();
        me.setUserId(uid);
        me.setNickname("阿梅");
        c.me = me;
        c.role = role;
        c.storeId = store;
        c.storeName = "大理小院";
        return c;
    }

    private void activity(long store, Long creator, String updateBy, String status)
    {
        Map<String, Object> a = p("activityId", 9L, "storeId", store, "appUserId", creator, "updateBy", updateBy, "title", "古城夜跑", "intro", "一起跑", "place", "南门", "status", status);
        when(db.one(contains("from kg_activity where activity_id = :id"), anyMap())).thenReturn(a);
    }

    private int code(Runnable r)
    {
        return assertThrows(ServiceException.class, r::run).getCode();
    }

    @Test
    void onlyKeeperAndCityLeadMayReview()
    {
        assertTrue(KgKeeperActivityReview.canReview(StaffRole.KEEPER));
        assertTrue(KgKeeperActivityReview.canReview(StaffRole.CITY_LEAD));
        for (String r : new String[] { StaffRole.MANAGER, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT, StaffRole.MERCHANT_SERVICE, StaffRole.STAFF })
        {
            assertFalse(KgKeeperActivityReview.canReview(r), r);
        }
        when(support.ctx(3L)).thenReturn(ctx(StaffRole.MANAGER, 1L, 3L));
        assertEquals(403, code(() -> review.ctx(3L)));
        verify(support).op(any(), eq("keeper_activity_audit"), anyString(), eq("general"), isNull(), eq(false), eq("角色不符"));
    }

    @Test
    void passOwnStoreActivityGoesThroughTheNormalAudit()
    {
        activity(3L, 50L, "staff:50", "pending");
        Map<String, Object> out = review.audit(ctx(StaffRole.KEEPER, 1L, 3L), 9L, true, null);
        assertEquals("open", out.get("status"));
        verify(activities).audit(9L, true, null, "staff:1");
    }

    @Test
    void otherStoreSelfAndHandledAreRefused()
    {
        activity(4L, 50L, null, "pending");
        assertEquals(403, code(() -> review.audit(ctx(StaffRole.KEEPER, 1L, 3L), 9L, true, null)));
        activity(3L, 1L, null, "pending");
        assertEquals(403, code(() -> review.audit(ctx(StaffRole.KEEPER, 1L, 3L), 9L, true, null)));
        activity(3L, 50L, "staff:1", "pending");
        assertEquals(403, code(() -> review.audit(ctx(StaffRole.KEEPER, 1L, 3L), 9L, false, "改一下")));
        activity(3L, 50L, null, "open");
        assertEquals(409, code(() -> review.audit(ctx(StaffRole.KEEPER, 1L, 3L), 9L, true, null)));
        verify(activities, never()).audit(anyLong(), anyBoolean(), any(), any());
    }

    @Test
    void flaggedContentCanOnlyBeRejectedAndRejectNeedsReason()
    {
        activity(3L, 50L, null, "pending");
        when(guard.check(any(), any(), any(), any())).thenReturn("review");
        assertEquals(409, code(() -> review.audit(ctx(StaffRole.KEEPER, 1L, 3L), 9L, true, null)));
        assertEquals(422, code(() -> review.audit(ctx(StaffRole.KEEPER, 1L, 3L), 9L, false, " ")));
        review.audit(ctx(StaffRole.KEEPER, 1L, 3L), 9L, false, "留了微信号，请删掉");
        verify(activities).audit(9L, false, "留了微信号，请删掉", "staff:1");
    }

    @Test
    void listIsOwnStoreAndSkipsOwnActivities()
    {
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(p("rows", new java.util.ArrayList<>(), "total", 0L));
        review.pending(ctx(StaffRole.KEEPER, 1L, 3L), 1, 20);
        verify(db).page(argThat(s -> s.contains("a.store_id = :s") && s.contains("a.status = 'pending'") && s.contains("a.app_user_id <> :me") && s.contains("a.update_by <> :meBy")),
                argThat(m -> Long.valueOf(3L).equals(m.get("s")) && "staff:1".equals(m.get("meBy"))), eq(1), eq(20));
    }
}
