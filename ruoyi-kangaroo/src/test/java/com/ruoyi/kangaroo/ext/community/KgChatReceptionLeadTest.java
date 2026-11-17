package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.ext.staff.RolePhase;

/** 报名结束页的安全负责人（2026-10-31 主人反馈）：一定是这段线下会话席位里的人，报名人自己不算，没人时退到人工客服。 */
class KgChatReceptionLeadTest
{
    private static Map<String, Object> seat(long uid, String role, String grantedBy, String nickname)
    {
        return p("appUserId", uid, "roleCode", role, "grantedBy", grantedBy, "nickname", nickname, "avatar", "/a" + uid + ".jpg");
    }

    private static Long uidOf(Map<String, Object> row)
    {
        return row == null ? null : KgJdbc.asLong(row.get("appUserId"));
    }

    private static final String AUTO = KgMerchantRequestService.GRANTED_BY;

    @Test
    void orderIsVerifierThenManagerThenKeeperThenTheRest()
    {
        Map<String, Object> merchant = seat(1, "merchant", "platform", "商家");
        Map<String, Object> keeper = seat(2, "keeper", "platform", "管家");
        Map<String, Object> auto = seat(3, "manager", AUTO, "派单补的主理人");
        Map<String, Object> own = seat(4, "manager", "platform", "本店主理人");
        Map<String, Object> staff = seat(5, "staff", "platform", "老员工");
        List<Map<String, Object>> all = Arrays.asList(merchant, keeper, auto, own, staff);
        assertEquals(3L, uidOf(KgChatReception.pickLead(all, 3L)), "上架这个岗位的人优先");
        assertEquals(2L, uidOf(KgChatReception.pickLead(all, 2L)), "上架的人是小院主理人时也是他");
        assertEquals(4L, uidOf(KgChatReception.pickLead(all, null)), "自己的授权优先于派单补的");
        assertEquals(4L, uidOf(KgChatReception.pickLead(all, 999L)), "上架的人已经不在席位里（离职、撤销）");
        assertEquals(3L, uidOf(KgChatReception.pickLead(Arrays.asList(merchant, keeper, auto, staff), null)), "只有派单补的也算换工主理人");
        assertEquals(2L, uidOf(KgChatReception.pickLead(Arrays.asList(merchant, keeper, staff), null)));
        assertEquals(1L, uidOf(KgChatReception.pickLead(Arrays.asList(merchant, staff), null)));
        assertEquals(5L, uidOf(KgChatReception.pickLead(Arrays.asList(staff, merchant), null)), "按授权顺序");
        assertNull(KgChatReception.pickLead(new ArrayList<Map<String, Object>>(), null));
    }

    @Test
    void viewSaysTheRoleAndNeverAPhone()
    {
        Map<String, Object> m = KgChatReception.leadView(seat(4, "manager", "platform", "小林"));
        assertEquals("manager", m.get("kind"));
        assertEquals("换工主理人", m.get("roleLabel"));
        assertEquals("小林", m.get("name"));
        assertEquals("/a4.jpg", m.get("avatar"));
        assertEquals(4, m.size(), "只有这四样，账号 ID 也不给");
        m = KgChatReception.leadView(seat(2, "keeper", "platform", ""));
        assertEquals("keeper", m.get("kind"));
        assertEquals("小院主理人", m.get("roleLabel"));
        assertEquals("", m.get("name"));
        m = KgChatReception.leadView(seat(1, "merchant", "platform", "南舍庄园"));
        assertEquals("store", m.get("kind"));
        assertEquals("门店接待", m.get("roleLabel"));
        m = KgChatReception.leadView(seat(5, "staff", "platform", "老员工"));
        assertEquals("store", m.get("kind"));
        m = KgChatReception.leadView(seat(6, "manager", "platform", "小林13812345678"));
        assertFalse(String.valueOf(m.get("name")).contains("13812345678"), "昵称里写了手机号也不能露");
    }

    @Test
    void nobodyMeansCustomerService()
    {
        Map<String, Object> m = KgChatReception.leadView(null);
        assertEquals("service", m.get("kind"));
        assertEquals("人工客服", m.get("roleLabel"));
        assertEquals("袋鼠客服", m.get("name"));
        assertEquals("", m.get("avatar"));
    }

    private static KgChatReception reception(KgJdbc db)
    {
        KgChatReception r = new KgChatReception();
        ReflectionTestUtils.setField(r, "db", db);
        return r;
    }

    @Test
    void candidatesUseExactlyTheOfflineSeatCondition()
    {
        // 选出来的人一定进得了这段会话：和 staff(store, false) 同一个 from + where
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        KgChatReception r = reception(db);
        r.staff(102L, false);
        r.offlineLead(102L, 8L, 12L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, times(2)).list(sql.capture(), anyMap());
        String seats = sql.getAllValues().get(0);
        String lead = sql.getAllValues().get(1);
        assertEquals(seats.substring(seats.indexOf(" from kg_staff_grant")), lead.substring(lead.indexOf(" from kg_staff_grant")));
        assertTrue(lead.contains("g.role_code <> 'online_staff'"));
        assertTrue(lead.contains("not in ('merchant_service','city_lead')"), "城市负责人、商家服务人不在席位里");
    }

    @Test
    void theApplicantIsNeverTheirOwnSafetyContact()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(contains("kg_staff_grant"), anyMap())).thenReturn(Arrays.asList(seat(8, "manager", "platform", "报名人自己"), seat(9, "keeper", "platform", "管家")));
        Map<String, Object> m = reception(db).offlineLead(102L, 8L, 12L);
        assertEquals("keeper", m.get("kind"));
        assertEquals("管家", m.get("name"));

        when(db.list(contains("kg_staff_grant"), anyMap())).thenReturn(Collections.singletonList(seat(8, "manager", "platform", "报名人自己")));
        assertEquals("service", reception(db).offlineLead(102L, 8L, 12L).get("kind"), "这家店只有他自己");
    }

    @Test
    void theOneWhoPublishedTheJobIsPreferred()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(contains("kg_staff_grant"), anyMap())).thenReturn(Arrays.asList(seat(1, "manager", "platform", "先开的"), seat(2, "manager", AUTO, "上架的人")));
        when(db.one(contains("kg_merchant_request"), anyMap())).thenReturn(p("verifierUserId", 2L));
        assertEquals("上架的人", reception(db).offlineLead(102L, 8L, 12L).get("name"));
        when(db.one(contains("kg_merchant_request"), anyMap())).thenReturn(null);
        assertEquals("先开的", reception(db).offlineLead(102L, 8L, 12L).get("name"));
    }

    @Test
    void bothPhasesReadTheCurrentRole()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(contains("kg_staff_grant"), anyMap())).thenReturn(Arrays.asList(seat(1, "staff", "platform", "老员工"), seat(2, "manager", "platform", "主理人")));
        String before = RolePhase.code();
        try
        {
            RolePhase.set(RolePhase.LEGACY);
            assertEquals("manager", reception(db).offlineLead(102L, 8L, null).get("kind"), "legacy：老主理人");
            RolePhase.set(RolePhase.NEW);
            assertEquals("manager", reception(db).offlineLead(102L, 8L, null).get("kind"), "new：换工主理人");
            when(db.list(contains("kg_staff_grant"), anyMap())).thenReturn(Collections.singletonList(seat(1, "staff", "platform", "老员工")));
            assertEquals("service", reception(db).offlineLead(102L, 8L, null).get("kind"), "new：残留的老 staff 不算有授权，退到人工客服");
            RolePhase.set(RolePhase.LEGACY);
            assertEquals("store", reception(db).offlineLead(102L, 8L, null).get("kind"), "legacy：老员工是门店接待");
        }
        finally
        {
            RolePhase.set(before);
        }
    }

    @Test
    void noStoreNoQuery()
    {
        KgJdbc db = mock(KgJdbc.class);
        assertEquals("service", reception(db).offlineLead(null, 8L, 12L).get("kind"));
        verifyNoInteractions(db);
    }
}
