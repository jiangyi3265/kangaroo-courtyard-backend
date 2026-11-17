package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 业务页未读数与员工身份消息中心同一口径；主理人授予角色的通知 biz_id 记授权ID。
 * 真实 service 方法 + Mock JDBC / 消息服务。
 */
class KgStaffTeamNoticeTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final IKgMessageService messages = mock(IKgMessageService.class);
    private final IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
    private final KgStaffTeamService team = new KgStaffTeamService();
    private final StaffSupport.Ctx c = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(team, "db", db);
        ReflectionTestUtils.setField(team, "messages", messages);
        ReflectionTestUtils.setField(team, "grants", grants);
        ReflectionTestUtils.setField(team, "support", mock(StaffSupport.class));
        ReflectionTestUtils.setField(team, "tokens", mock(AppTokenService.class));
        AppLoginUser me = new AppLoginUser();
        me.setUserId(7073L);
        me.setNickname("店长");
        me.setStaffGrants(Collections.singletonList(new StaffGrantView()));
        c.me = me;
        c.storeId = 2919L;
        c.storeName = "测试门店";
        c.role = "manager";
    }

    @Test
    void hubUnreadUsesTheMessageCenterCount()
    {
        when(messages.countUnread(7073L, "staff")).thenReturn(0);
        Map<String, Object> hub = team.hub(c);
        assertEquals(0, hub.get("unread"));
        verify(messages).countUnread(7073L, "staff");
        verify(db, never()).count(contains("from kg_message"), anyMap());
    }

    @Test
    void newGrantNotificationCarriesGrantId()
    {
        when(db.one(contains("from kg_app_user where user_id"), anyMap())).thenReturn(p("userId", 7089L, "nickname", "小院同学", "status", "0"));
        // Mock 的 one() 默认回空 Map 而不是 null：这里明确「本店还没有这个人的授权」
        when(db.one(contains("from kg_staff_grant"), anyMap())).thenReturn(null);
        when(db.insert(eq("kg_staff_grant"), anyMap())).thenReturn(2576L);
        team.grant(c, "7089", "staff");
        verify(grants).notifyGrantChange(eq(7089L), eq(2919L), eq("你已获得门店员工身份"), anyString(), eq(2576L));
    }

    @Test
    void revokingAKeeperAlsoReleasesTheShopsTheSystemAddedBeforeRefreshingTheSession()
    {
        KgMerchantRequestService requests = mock(KgMerchantRequestService.class);
        AppTokenService tokens = mock(AppTokenService.class);
        ReflectionTestUtils.setField(team, "merchantRequests", requests);
        ReflectionTestUtils.setField(team, "tokens", tokens);
        when(db.must(contains("from kg_staff_grant g"), anyMap(), anyString())).thenReturn(p("grantId", 2500L, "storeId", 2919L, "roleCode", "keeper", "appUserId", 7089L, "nickname", "Luna"));
        when(db.update(contains("update kg_staff_grant"), anyMap())).thenReturn(1);
        team.revoke(c, 2500L);
        org.mockito.InOrder order = inOrder(requests, tokens);
        order.verify(requests).releaseServiceGrants(7089L);
        order.verify(tokens).refreshGrants(eq(7089L), any());
    }

    @Test
    void reactivatedGrantNotificationCarriesExistingGrantId()
    {
        when(db.one(contains("from kg_app_user where user_id"), anyMap())).thenReturn(p("userId", 7089L, "nickname", "", "status", "0"));
        when(db.one(contains("from kg_staff_grant"), anyMap())).thenReturn(p("grantId", 2500L, "status", "1", "delFlag", "0"));
        team.grant(c, "7089", "keeper");
        verify(grants).notifyGrantChange(eq(7089L), eq(2919L), anyString(), anyString(), eq(2500L));
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
    }
}
