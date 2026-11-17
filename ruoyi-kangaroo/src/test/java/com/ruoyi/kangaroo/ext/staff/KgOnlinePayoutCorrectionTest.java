package com.ruoyi.kangaroo.ext.staff;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 单元层校验金额、字段和审计完整性；真实并发、事务和双端重新读取由隔离 HTTP 回归证明。 */
class KgOnlinePayoutCorrectionTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgOnlineTaskService service = spy(new KgOnlineTaskService());
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private final Map<String, Object> before = p("settleId", 9L, "amountFen", 12345L, "status", "disputed",
            "payoutMethod", "银行转账", "payoutRef", "original-ref", "payoutVoucher", "/profile/original.png", "disputeReason", "未收到款");

    @BeforeEach
    void setup()
    {
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setNickname("核实主理人");
        ctx.storeId = 3L; ctx.role = "manager";
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        doReturn(p("title", "本地任务", "commission", "¥999.99")).when(service).assignment(ctx, 7L);
        when(db.one(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("kg_online_settlement") ? before : p("appUserId", 2L);
        });
        when(db.update(anyString(), anyMap())).thenReturn(1);
        when(db.insert(anyString(), anyMap())).thenReturn(19L);
    }

    private StaffSupport.Ctx keeper(boolean also)
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = new AppLoginUser();
        c.me.setUserId(1L);
        c.me.setNickname("小院主理人");
        com.ruoyi.kangaroo.security.StaffGrantView g = new com.ruoyi.kangaroo.security.StaffGrantView();
        g.setStoreId(3L);
        g.setRoleCode(StaffRole.KEEPER);
        g.setAlsoService(also);
        c.me.setStaffGrants(java.util.Collections.singletonList(g));
        c.storeId = 3L;
        c.role = StaffRole.KEEPER;
        return c;
    }

    @Test
    void aDoubleRoleOnlyCountsAsServiceOnAMerchantsTask()
    {
        StaffSupport.Ctx also = keeper(true);
        when(db.count(contains("from kg_merchant_request r"), anyMap())).thenReturn(0L);
        assertEquals(StaffRole.KEEPER, service.payoutRole(also, 88L), "直营店的任务、门店自己发的任务");
        assertFalse(StaffPerms.canRegisterOnlinePayout(service.payoutRole(also, 88L)));
        verify(db, atLeastOnce()).count(contains("coalesce(s.op_type, '') <> 'direct'"), argThat(m -> Long.valueOf(88L).equals(m.get("j")) && Long.valueOf(3L).equals(m.get("s"))));
        when(db.count(contains("from kg_merchant_request r"), anyMap())).thenReturn(1L);
        assertEquals(StaffRole.MERCHANT_SERVICE, service.payoutRole(also, 88L), "商家需求转来的任务");
        assertTrue(StaffPerms.canRegisterOnlinePayout(service.payoutRole(also, 88L)));
        assertEquals(StaffRole.KEEPER, service.payoutRole(also, null));
    }

    @Test
    void otherRolesAreNotLookedUp()
    {
        assertEquals(StaffRole.KEEPER, service.payoutRole(keeper(false), 88L));
        for (String role : java.util.Arrays.asList(StaffRole.STAFF, StaffRole.ONLINE_STAFF, StaffRole.MANAGER, StaffRole.MERCHANT, StaffRole.MERCHANT_SERVICE))
        {
            ctx.role = role;
            assertEquals(role, service.payoutRole(ctx, 88L), role);
        }
        verify(db, never()).count(anyString(), anyMap());
    }

    @Test
    void aDoubleRoleCannotRegisterOrCorrectOnTheirOwnStoresTask()
    {
        StaffSupport.Ctx also = keeper(true);
        doReturn(p("title", "本店任务")).when(service).assignment(also, 7L);
        when(db.one(contains("from kg_application where app_id"), anyMap())).thenReturn(p("jobId", 88L, "appUserId", 2L));
        when(db.count(contains("from kg_merchant_request r"), anyMap())).thenReturn(0L);
        ServiceException error = assertThrows(ServiceException.class, () -> service.payout(also, 7L, body()));
        assertEquals(Integer.valueOf(403), error.getCode());
        verify(db, never()).update(anyString(), anyMap());

        // 同一个人在商家的任务上：结算单是争议单时能更正
        when(db.count(contains("from kg_merchant_request r"), anyMap())).thenReturn(1L);
        service.payout(also, 7L, body());
        verify(db).insert(eq("kg_online_payout_correction"), anyMap());
    }

    private Map<String, Object> body()
    {
        return p("method", "微信转账", "ref", "new-ref", "voucher", "/profile/new.png", "correctionNote", "已核实并补充凭证");
    }

    private void rejected(int code, Map<String, Object> body)
    {
        ServiceException error = assertThrows(ServiceException.class, () -> service.payout(ctx, 7L, body));
        assertEquals(Integer.valueOf(code), error.getCode());
        verify(db, never()).update(anyString(), anyMap());
        verify(db, never()).insert(anyString(), anyMap());
        verifyNoInteractions(support);
    }

    @Test
    void correctionKeepsOriginalAmountAndFullAuditAndUsesItInNotice()
    {
        before.put("payoutVoucher", "/profile/" + String.join("", java.util.Collections.nCopies(220, "o")) + ".png");
        service.payout(ctx, 7L, body());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_online_payout_correction"), audit.capture());
        Map<String, Object> row = audit.getValue();
        assertEquals(12345L, row.get("amount_fen"));
        assertEquals(1L, row.get("operator_id"));
        assertEquals(before.get("payoutVoucher"), row.get("old_voucher"));
        assertEquals("original-ref", row.get("old_ref"));
        assertEquals("/profile/new.png", row.get("new_voucher"));
        assertEquals("未收到款", row.get("dispute_reason"));
        assertEquals("已核实并补充凭证", row.get("correction_note"));
        verify(db).update(argThat(sql -> !sql.contains("amount_fen") && sql.contains("status = :oldStatus")),
                argThat(params -> "disputed".equals(params.get("oldStatus"))));
        verify(support).notifyUser(eq(2L), eq("apply"), eq("门店已更正付款凭证"),
                argThat(text -> text.contains("¥123.45") && !text.contains("999.99") && text.contains("不会发起转账")),
                eq("application"), eq(7L), eq("/pages/jobs/deliver?appId=7"));
    }

    @Test void clientAmountIsRejected() { Map<String,Object> b = body(); b.put("amountFen", 1L); rejected(422, b); }
    @Test void clientStatusIsRejected() { Map<String,Object> b = body(); b.put("status", "confirmed"); rejected(422, b); }
    @Test void missingVerificationNoteIsRejected() { Map<String,Object> b = body(); b.put("correctionNote", " "); rejected(422, b); }
    @Test void externalVoucherIsRejected() { Map<String,Object> b = body(); b.put("voucher", "https://example.com/tracker.png"); rejected(422, b); }
    @Test void keeperCannotCorrect() { ctx.role = "keeper"; rejected(403, body()); }
    @Test void onlineStaffCannotCorrect() { ctx.role = "online_staff"; rejected(403, body()); }
    @Test void cityLeadCannotRegisterOrCorrect() { ctx.role = "city_lead"; rejected(403, body()); }
    @Test void merchantCanCorrectAsThePayer() { ctx.role = "merchant"; assertDoesNotThrow(() -> service.payout(ctx, 7L, body())); verify(db).insert(eq("kg_online_payout_correction"), anyMap()); }
    @Test void merchantServiceCanCorrect() { ctx.role = "merchant_service"; assertDoesNotThrow(() -> service.payout(ctx, 7L, body())); verify(db).insert(eq("kg_online_payout_correction"), anyMap()); }
    @Test void confirmedCannotBeReopened() { before.put("status", "confirmed"); rejected(409, body()); }
    @Test void awaitingConfirmationCannotBeOverwritten() { before.put("status", "payout_registered"); rejected(409, body()); }

    @Test
    void pendingPayoutStillRegistersWithoutCorrectionAuditOrNote()
    {
        before.put("status", "pending_payout");
        Map<String, Object> b = body(); b.remove("correctionNote");
        service.payout(ctx, 7L, b);
        verify(db, never()).insert(anyString(), anyMap());
        verify(support).notifyUser(eq(2L), eq("apply"), eq("门店已登记线下付款"), anyString(), eq("application"), eq(7L), anyString());
    }
}
