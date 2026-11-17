package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** 「成为商家」入驻（V56 + V63 自动开通）：每一项都校验、提交即开通、不重复建店、被撤销过的不自动开通、进度说法、后台只能改跟进两项（不连库） */
class KgMerchantApplyServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgMerchantApplyService service = new KgMerchantApplyService(db);
    private final IKgStaffGrantService grants = mock(IKgStaffGrantService.class);
    private final AppTokenService tokens = mock(AppTokenService.class);
    private final KgCityService cities = mock(KgCityService.class);

    @BeforeEach
    void wire()
    {
        ReflectionTestUtils.setField(service, "grants", grants);
        ReflectionTestUtils.setField(service, "tokens", tokens);
        ReflectionTestUtils.setField(service, "cities", cities);
        when(cities.jobCity(anyString())).thenAnswer(i -> i.<String>getArgument(0).replace("市", ""));
        when(cities.dictionaryCity(anyString())).thenAnswer(i -> i.<String>getArgument(0).replace("市", ""));
        // 账号验证过的手机号：和 form() 里填的是同一个
        when(db.one(contains("from kg_app_user where user_id = :u for update"), anyMap())).thenReturn(p("userId", 1015L, "phone", "13912345678", "phoneVerifiedTime", "2026-10-01 10:00:00", "status", "0", "delFlag", "0"));
        when(db.insert(eq("kg_store"), anyMap())).thenReturn(300L);
        when(db.insert(eq("kg_staff_grant"), anyMap())).thenReturn(400L);
        // KgJdbc.one 查不到返回 null（Mockito 默认回空 Map，这里显式给 null）
        when(db.one(contains("from kg_staff_grant g"), anyMap())).thenReturn(null);
        when(db.one(contains("status in ('new', 'contacted')"), anyMap())).thenReturn(null);
    }

    private static Map<String, Object> form()
    {
        Map<String, Object> b = new HashMap<String, Object>();
        b.put("contactName", "  阿杰 ");
        b.put("phone", "139 1234-5678");
        b.put("city", "大理");
        b.put("storeType", "homestay");
        b.put("storeName", "喜洲\n小院");
        b.put("intro", "  院子里有三只猫\r\n离古城 10 分钟  ");
        b.put("wantTypes", Arrays.asList("volunteer", "parttime", "volunteer"));
        return b;
    }

    private int code(Map<String, Object> b)
    {
        return assertThrows(ServiceException.class, () -> service.apply(1015L, b)).getCode();
    }

    @SuppressWarnings("unchecked")
    @Test
    void validFormWaitsForReviewWithCleanedValuesAndChineseWantLabels()
    {
        Map<String, Object> out = service.apply(1015L, form());
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_merchant_apply"), row.capture());
        Map<String, Object> r = row.getValue();
        assertEquals(1015L, r.get("app_user_id"));
        assertEquals("阿杰", r.get("contact_name"));
        assertEquals("13912345678", r.get("phone"), "空格和横杠去掉");
        assertEquals("喜洲 小院", r.get("store_name"), "店名里的换行并成空格");
        assertEquals("院子里有三只猫\n离古城 10 分钟", r.get("intro"), "介绍保留换行");
        assertEquals("换工、兼职", r.get("want_types"), "去重、存中文");
        assertEquals("new", r.get("status"));
        assertNull(r.get("store_id"));
        assertNull(r.get("grant_id"));
        assertEquals("app:1015", r.get("create_by"));
        assertNotNull(r.get("create_time"));
        assertEquals(Boolean.FALSE, out.get("approved"));
        assertNull(out.get("storeId"));
        assertNull(out.get("roleCode"));
        assertFalse(String.valueOf(out.get("message")).contains("工作日"));
    }

    @Test
    void submissionNeverCreatesStoreGrantOrRefreshesAuthority() {
        Map<String,Object> out=service.apply(1015L,form());
        assertEquals(Boolean.FALSE,out.get("approved"));
        verify(db,never()).insert(eq("kg_store"),anyMap());
        verify(db,never()).insert(eq("kg_staff_grant"),anyMap());
        verifyNoInteractions(grants,tokens);
        verify(db).one(contains("for update"),anyMap());
    }

    @Test
    void rejectedApplicantIsNotOpenedAutomatically()
    {
        when(db.count(contains("status = 'rejected'"), anyMap())).thenReturn(1L);
        Map<String, Object> out = service.apply(1015L, form());
        assertEquals(Boolean.FALSE, out.get("approved"), "平台拒绝过，再填一次表不能绕过去");
        verify(db, never()).insert(eq("kg_store"), anyMap());
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        verify(db).insert(eq("kg_merchant_apply"), argThat(r -> "new".equals(r.get("status"))));
    }

    @Test
    void rejectedApplicantWithAnOpenApplicationIsToldToWait()
    {
        when(db.count(contains("status = 'rejected'"), anyMap())).thenReturn(1L);
        when(db.one(contains("status in ('new', 'contacted')"), anyMap())).thenReturn(p("applyId", 9L));
        assertEquals(409, code(form()));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void openingNeedsTheAccountsVerifiedPhone()
    {
        when(db.one(contains("from kg_app_user where user_id = :u for update"), anyMap())).thenReturn(p("userId", 1015L, "phone", "13912345678", "status", "0", "delFlag", "0"));
        assertEquals(422, code(form()), "号码没验证过");
        when(db.one(contains("from kg_app_user where user_id = :u for update"), anyMap())).thenReturn(p("userId", 1015L, "phone", "13800000000", "phoneVerifiedTime", "2026-10-01 10:00:00", "status", "0", "delFlag", "0"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.apply(1015L, form()));
        assertEquals(422, e.getCode(), "填的是别人的号");
        assertTrue(e.getMessage().contains("138****0000"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void cityMustBeInTheDictionaryAndPassTheContentCheck()
    {
        when(cities.dictionaryCity(anyString())).thenReturn(null);
        Map<String, Object> b = form();
        b.put("city", "加微信abc");
        assertEquals(422, code(b));
        verify(db, never()).insert(anyString(), anyMap());

        KgCommunityGuard guard = mock(KgCommunityGuard.class);
        service.setGuard(guard);
        when(cities.dictionaryCity(anyString())).thenReturn("大理");
        service.apply(1015L, form());
        verify(guard).check(anyString(), anyString(), anyString(), eq("大理"));
    }

    // ------------------------------------------------------------------ 自助退出

    @Test
    void accountDisabledAfterAuthenticationCannotAutoOpenAStore()
    {
        assertInactiveApplicant(p("userId", 1015L, "phone", "13912345678", "phoneVerifiedTime", "2026-10-01 10:00:00", "status", "1", "delFlag", "0"));
    }

    @Test
    void accountDeletedAfterAuthenticationCannotAutoOpenAStore()
    {
        assertInactiveApplicant(p("userId", 1015L, "phone", "13912345678", "phoneVerifiedTime", "2026-10-01 10:00:00", "status", "0", "delFlag", "2"));
    }

    @Test
    void missingAccountCannotAutoOpenAStore()
    {
        assertInactiveApplicant(null);
    }

    private void assertInactiveApplicant(Map<String, Object> account)
    {
        when(db.one(contains("from kg_app_user where user_id = :u for update"), anyMap())).thenReturn(account);
        ServiceException e = assertThrows(ServiceException.class, () -> service.apply(1015L, form()));
        assertEquals(409, e.getCode());
        assertEquals("账号已注销或不可用", e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
        verify(db, never()).update(anyString(), anyMap());
        verify(db, never()).one(contains("from kg_staff_grant g"), anyMap());
        verifyNoInteractions(tokens, grants);
    }

    private void merchantOf(long grantId, long storeId)
    {
        when(db.list(contains("g.role_code = 'merchant'"), anyMap())).thenReturn(Arrays.<Map<String, Object>>asList(new HashMap<String, Object>(p("grantId", grantId, "storeId", storeId, "storeName", "喜洲小院"))));
    }

    @Test
    void exitIsRefusedWhileAnythingIsStillOpenAndSaysWhat()
    {
        merchantOf(400L, 300L);
        when(db.count(contains("from kg_job where store_id"), anyMap())).thenReturn(2L);
        when(db.count(contains("from kg_merchant_request where store_id"), anyMap())).thenReturn(1L);
        ServiceException e = assertThrows(ServiceException.class, () -> service.exit(1015L));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("2 个上架或暂停中的岗位"), e.getMessage());
        assertTrue(e.getMessage().contains("1 条需求"), e.getMessage());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void exitRevokesTheGrantAndStopsASelfOpenedStore()
    {
        merchantOf(400L, 300L);
        when(db.update(contains("update kg_merchant_apply"), anyMap())).thenReturn(1);
        when(db.list(contains("select grant_id, app_user_id from kg_staff_grant"), anyMap())).thenReturn(Arrays.<Map<String, Object>>asList(new HashMap<String, Object>(p("grantId", 401L, "appUserId", 2002L))));
        List<StaffGrantView> views = Arrays.asList(new StaffGrantView());
        when(grants.loadActiveGrantViews(1015L)).thenReturn(views);

        Map<String, Object> out = service.exit(1015L);

        assertEquals(Boolean.TRUE, out.get("exited"));
        assertSame(views, out.get("staffGrants"));
        verify(db).update(contains("update kg_staff_grant set status = '1'"), argThat(m -> Long.valueOf(400L).equals(m.get("g")) && "app:1015".equals(m.get("by"))));
        verify(db).update(contains("update kg_store set status = '1'"), argThat(m -> Long.valueOf(300L).equals(m.get("s"))));
        verify(db).update(contains("update kg_staff_grant set status = '1'"), argThat(m -> Long.valueOf(401L).equals(m.get("g"))));
        verify(db).update(contains("update kg_merchant_request set status = 'closed'"), anyMap());
        verify(db).update(contains("update kg_job set status = 'closed'"), argThat(m -> "staff:1015".equals(m.get("mine"))));
        verify(tokens).refreshGrants(1015L, views);
        verify(tokens).refreshGrants(eq(2002L), any());
    }

    @Test
    void merchantCannotExitWhileStayRefundIsStillInFlight()
    {
        merchantOf(400L, 300L);
        when(db.count(argThat(sql -> sql.contains("from kg_stay_order") && sql.contains("'refunding'")), anyMap())).thenReturn(1L);
        ServiceException e = assertThrows(ServiceException.class, () -> service.exit(1015L));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("1 个订单没完成"), e.getMessage());
        verify(db, never()).update(anyString(), anyMap());
        verifyNoInteractions(tokens);
    }

    @Test
    void merchantCannotExitWhileAddonRefundIsStillInFlight()
    {
        merchantOf(400L, 300L);
        when(db.count(argThat(sql -> sql.contains("from kg_shop_order") && sql.contains("'refunding'")), anyMap())).thenReturn(1L);
        ServiceException e = assertThrows(ServiceException.class, () -> service.exit(1015L));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("1 个订单没完成"), e.getMessage());
        verify(db, never()).update(anyString(), anyMap());
        verifyNoInteractions(tokens);
    }

    @Test
    void exitKeepsAPlatformOpenedStoreRunning()
    {
        merchantOf(400L, 300L);
        when(db.update(contains("update kg_merchant_apply"), anyMap())).thenReturn(0);
        service.exit(1015L);
        verify(db).update(contains("update kg_staff_grant set status = '1'"), argThat(m -> Long.valueOf(400L).equals(m.get("g"))));
        verify(db, never()).update(contains("update kg_store"), anyMap());
    }

    @Test
    void exitKeepsAStoreThatStillHasPlatformStaff()
    {
        merchantOf(400L, 300L);
        when(db.update(contains("update kg_merchant_apply"), anyMap())).thenReturn(1);
        when(db.count(contains("coalesce(granted_by, '') <> :auto"), anyMap())).thenReturn(1L);
        service.exit(1015L);
        verify(db, never()).update(contains("update kg_store"), anyMap());
    }

    @Test
    void nonMerchantCannotExit()
    {
        assertEquals(409, assertThrows(ServiceException.class, () -> service.exit(1015L)).getCode());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void alreadyMerchantDoesNotGetASecondStore()
    {
        when(db.one(contains("from kg_staff_grant g"), anyMap())).thenReturn(p("grantId", 401L, "storeId", 301L, "storeName", "老店", "storeStatus", "0", "storeDel", "0"));
        Map<String, Object> out = service.apply(1015L, form());
        assertEquals(Boolean.TRUE, out.get("approved"));
        assertEquals(301L, out.get("storeId"));
        verify(db, never()).insert(anyString(), anyMap());
        verify(tokens).refreshGrants(eq(1015L), any());
    }

    @Test
    void otherStaffRolesAreLeftAloneAndAwaitReview()
    {
        // 查的是 role_code = 'merchant'：别的角色的授权既不算已入驻，也不会被改动
        assertEquals(Boolean.FALSE, service.apply(1015L, form()).get("approved"));
        verify(db, never()).update(anyString(), anyMap());
        verify(db, never()).updateRow(eq("kg_staff_grant"), anyString(), any(), anyMap());
    }

    @Test
    void pendingApplicationCannotBeResubmittedOrSelfApproved() {
        when(db.one(contains("status in ('new', 'contacted')"), anyMap())).thenReturn(p("applyId", 9L));
        assertEquals(Integer.valueOf(409),assertThrows(ServiceException.class,()->service.apply(1015L,form())).getCode());
        verify(db,never()).insert(anyString(),anyMap());
        verify(db,never()).updateRow(anyString(),anyString(),any(),anyMap());
    }

    @Test
    void revokedMerchantIsNotAutoApprovedAgain()
    {
        when(db.count(contains("(status <> '0' or del_flag <> '0')"), anyMap())).thenReturn(1L);
        Map<String, Object> out = service.apply(1015L, form());
        assertEquals(Boolean.FALSE, out.get("approved"), "平台撤销过的，再填一次表不能绕过去");
        verify(db, never()).insert(eq("kg_store"), anyMap());
        verify(db, never()).insert(eq("kg_staff_grant"), anyMap());
        verify(db).insert(eq("kg_merchant_apply"), argThat(r -> "new".equals(r.get("status"))));
        assertEquals("申请已提交，平台审核通过后开通商家身份", out.get("message"));
    }

    @Test
    void merchantOfADisabledStoreIsNotAutoApprovedAgain()
    {
        when(db.one(contains("from kg_staff_grant g"), anyMap())).thenReturn(p("grantId", 401L, "storeId", 301L, "storeName", "老店", "storeStatus", "1", "storeDel", "0"));
        assertEquals(Boolean.FALSE, service.apply(1015L, form()).get("approved"));
        verify(db, never()).insert(eq("kg_store"), anyMap());
    }

    @Test
    void nameCollisionIsDeferredUntilPlatformApproval() {
        when(db.insert(eq("kg_store"),anyMap())).thenThrow(new org.springframework.dao.DuplicateKeyException("uk_store_name_city"));
        assertEquals(Boolean.FALSE,service.apply(1015L,form()).get("approved"));
        verify(db,never()).insert(eq("kg_store"),anyMap());
    }

    @Test
    void everyRequiredItemIsChecked()
    {
        Map<String, Object> b = form(); b.put("contactName", " "); assertEquals(422, code(b));
        b = form(); b.put("phone", "12345678901"); assertEquals(422, code(b));
        b = form(); b.put("phone", null); assertEquals(422, code(b));
        b = form(); b.put("city", ""); assertEquals(422, code(b));
        b = form(); b.put("storeType", "spa"); assertEquals(422, code(b));
        b = form(); b.put("storeName", null); assertEquals(422, code(b));
        b = form(); b.put("storeName", new String(new char[65]).replace('\0', '店')); assertEquals(422, code(b));
        b = form(); b.put("intro", new String(new char[501]).replace('\0', '字')); assertEquals(422, code(b));
        b = form(); b.put("wantTypes", Arrays.asList("volunteer", "boss")); assertEquals(422, code(b));
        assertEquals(422, assertThrows(ServiceException.class, () -> service.apply(1015L, null)).getCode());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void wantTypesAreOptionalNow()
    {
        Map<String, Object> b = form();
        b.remove("wantTypes");
        assertEquals(Boolean.FALSE, service.apply(1015L, b).get("approved"));
        verify(db).insert(eq("kg_merchant_apply"), argThat(r -> "".equals(r.get("want_types"))));

        Map<String, Object> empty = form();
        empty.put("wantTypes", Arrays.asList());
        assertEquals(Boolean.FALSE, service.apply(1016L, empty).get("approved"));
    }

    @Test
    void wantTypesAlsoAcceptACommaString()
    {
        Map<String, Object> b = form();
        b.put("wantTypes", "fulltime,online");
        service.apply(1015L, b);
        verify(db).insert(eq("kg_merchant_apply"), argThat(r -> "全职、线上任务".equals(r.get("want_types"))));
    }

    @Test
    void aRevokedMerchantWithAnOpenApplicationCannotPileUpMore()
    {
        when(db.count(contains("(status <> '0' or del_flag <> '0')"), anyMap())).thenReturn(1L);
        when(db.one(contains("status in ('new', 'contacted')"), anyMap())).thenReturn(p("applyId", 9L));
        assertEquals(409, code(form()));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void blockedWordsAreRefusedButContactInfoIsFine()
    {
        KgCommunityGuard guard = mock(KgCommunityGuard.class);
        service.setGuard(guard);
        when(guard.check(any(), any(), any(), any())).thenReturn("review");
        service.apply(1015L, form());
        verify(db).insert(eq("kg_merchant_apply"), anyMap());

        reset(db);
        when(guard.check(any(), any(), any(), any())).thenThrow(new ServiceException("内容包含不允许发布的信息，请修改后再发", 422));
        assertEquals(422, code(form()));
        verify(db, never()).insert(anyString(), anyMap());
        verify(db, never()).one(contains("for update"), anyMap());
    }

    @Test
    void notSignedInIsRefused()
    {
        assertEquals(401, assertThrows(ServiceException.class, () -> service.apply(null, form())).getCode());
    }

    @SuppressWarnings("unchecked")
    @Test
    void latestTellsTheUserWhereTheirApplicationIs()
    {
        // KgJdbc.one 查不到返回 null（Mockito 默认会回一个空 Map，这里显式给 null）
        when(db.one(contains("from kg_merchant_apply where app_user_id = :u"), anyMap())).thenReturn(null);
        Map<String, Object> none = service.latest(1015L);
        assertNull(none.get("latest"));
        assertEquals(5, ((List<?>) none.get("storeTypes")).size());
        assertEquals(4, ((List<?>) none.get("wantTypes")).size());

        when(db.one(contains("from kg_merchant_apply where app_user_id = :u"), anyMap())).thenReturn(
                new HashMap<String, Object>(p("applyId", 7L, "storeName", "喜洲小院", "city", "大理", "storeType", "homestay",
                        "wantTypes", "换工、兼职", "status", "contacted", "createTime", "2026-10-18 20:00:00")));
        Map<String, Object> x = (Map<String, Object>) service.latest(1015L).get("latest");
        assertEquals("喜洲小院", x.get("storeName"), "KgJdbc 的列名是驼峰");
        assertEquals(7L, x.get("applyId"));
        assertEquals("民宿", x.get("storeTypeLabel"));
        assertEquals(Arrays.asList("换工", "兼职"), x.get("wantLabels"));
        assertEquals(Boolean.TRUE, x.get("open"));
        assertEquals(Boolean.FALSE, x.get("canResubmit"), "待审核不允许重复提交");
        assertTrue(String.valueOf(x.get("statusText")).contains("已和你联系"));
        assertFalse(x.containsKey("followNote"), "后台跟进备注不给用户看");
    }

    @Test
    void adminCanOnlyChangeTheFollowUpFields()
    {
        ExtEntity e = new KgMerchantModule().entities().get(0);
        assertEquals("merchantapply", e.key);
        assertEquals("kangaroo:merchantapply", e.perm);
        assertFalse(e.allowAdd, "申请只从小程序来");
        assertFalse(e.allowDelete, "跟进记录要留着");
        assertTrue(e.allowEdit);
        for (ExtField f : e.fields)
        {
            boolean followUp = "status".equals(f.name) || "followNote".equals(f.name);
            assertEquals(followUp, f.editable, f.name);
        }
        assertEquals(KgMerchantModule.STATUS_OPTIONS, e.field("status").options);
        assertTrue(e.field("status").options.startsWith("new:待审核"));
    }

    // ------------------------------------------------------------------ 后台改跟进状态（adminFollow）

    private final IKgMessageService messages = mock(IKgMessageService.class);

    private Map<String, Object> pending(String status, String note)
    {
        ReflectionTestUtils.setField(service, "messages", messages);
        Map<String, Object> old = new HashMap<String, Object>(p("applyId", 7L, "appUserId", 1015L, "status", status, "storeName", "喜洲小院",
                "city", "大理市", "phone", "13912345678", "intro", "有三只猫", "followNote", note));
        when(db.one(contains("from kg_merchant_apply where apply_id = :id"), anyMap())).thenReturn(old);
        return old;
    }

    private static Map<String, Object> save(String status, String note)
    {
        Map<String, Object> cols = new HashMap<String, Object>(p("_pk", 7L, "status", status));
        if (note != null) cols.put("follow_note", note);
        return cols;
    }

    @Test
    void contactedTellsTheApplicant()
    {
        pending("new", "");
        service.adminFollow(7L, save("contacted", null), "admin");
        verify(messages).send(eq(1015L), eq("user"), eq("system"), eq("商家入驻申请有新进展"), contains("喜洲小院"), eq("merchant_apply"), eq(7L), eq("/pages/profile/merchant"));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void rejectedSendsOnlyTheNewlyWrittenReason()
    {
        pending("contacted", "10-20 电话没人接");
        service.adminFollow(7L, save("rejected", "10-20 电话没人接\n所在城市暂不开放"), "admin");
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(messages).send(eq(1015L), eq("user"), eq("system"), eq("商家入驻申请未通过"), content.capture(), eq("merchant_apply"), eq(7L), anyString());
        assertTrue(content.getValue().contains("原因：所在城市暂不开放"), content.getValue());
        assertFalse(content.getValue().contains("电话没人接"), "以前的备注不发给用户");
    }

    @Test
    void unchangedStatusDoesNothing()
    {
        pending("contacted", "");
        service.adminFollow(7L, save("contacted", "又打了一次"), "admin");
        verifyNoInteractions(messages);
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void settledOpensStoreAndGrantAndFillsTheRow()
    {
        pending("contacted", "");
        List<StaffGrantView> views = Arrays.asList(new StaffGrantView());
        when(grants.loadActiveGrantViews(1015L)).thenReturn(views);
        Map<String, Object> cols = save("settled", null);
        service.adminFollow(7L, cols, "admin");
        verify(db).insert(eq("kg_store"), argThat(s -> "大理".equals(s.get("city")) && "喜洲小院".equals(s.get("store_name")) && "admin:admin".equals(s.get("create_by"))));
        verify(db).insert(eq("kg_staff_grant"), argThat(g -> Long.valueOf(1015L).equals(g.get("app_user_id")) && Long.valueOf(300L).equals(g.get("store_id"))));
        assertEquals(300L, cols.get("store_id"));
        assertEquals(400L, cols.get("grant_id"));
        verify(tokens).refreshGrants(1015L, views);
        verify(grants).notifyGrantChange(eq(1015L), eq(300L), anyString(), anyString(), eq(400L));
    }

    @Test
    void settledReusesAnExistingMerchantGrant()
    {
        pending("contacted", "");
        when(db.one(contains("s.status = '0' and s.del_flag = '0'"), anyMap())).thenReturn(p("grantId", 401L, "storeId", 301L, "storeName", "老店"));
        Map<String, Object> cols = save("settled", null);
        service.adminFollow(7L, cols, "admin");
        verify(db, never()).insert(anyString(), anyMap());
        assertEquals(301L, cols.get("store_id"));
        assertEquals(401L, cols.get("grant_id"));
        verify(messages).send(eq(1015L), eq("user"), eq("system"), eq("商家入驻申请已通过"), contains("老店"), eq("merchant_apply"), eq(7L), anyString());
    }

    @Test
    void settledIsRefusedForAPreviouslyRevokedMerchant()
    {
        pending("contacted", "");
        when(db.one(contains("order by g.grant_id desc limit 1"), anyMap())).thenReturn(p("grantId", 399L, "storeName", "旧店"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.adminFollow(7L, save("settled", null), "admin"));
        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("员工授权"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void settledIsRefusedWhenTheCityIsNotInTheDictionary()
    {
        pending("new", "");
        when(cities.dictionaryCity(anyString())).thenReturn(null);
        assertEquals(422, assertThrows(ServiceException.class, () -> service.adminFollow(7L, save("settled", null), "admin")).getCode());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void activeMerchantCannotBeMovedOffSettled()
    {
        pending("settled", "");
        when(db.one(contains("s.status = '0' and s.del_flag = '0'"), anyMap())).thenReturn(p("grantId", 401L, "storeId", 301L, "storeName", "老店"));
        assertEquals(409, assertThrows(ServiceException.class, () -> service.adminFollow(7L, save("rejected", "不合作了"), "admin")).getCode());
        verifyNoInteractions(messages);
    }

    @SuppressWarnings("unchecked")
    @Test
    void settledRowWithoutMerchantIdentityIsNotShownAsWelcome()
    {
        when(db.one(contains("from kg_merchant_apply where app_user_id = :u"), anyMap())).thenReturn(
                new HashMap<String, Object>(p("applyId", 7L, "storeName", "喜洲小院", "city", "大理", "storeType", "homestay", "status", "settled", "createTime", "2026-10-18 20:00:00")));
        Map<String, Object> x = (Map<String, Object>) service.latest(1015L).get("latest");
        assertEquals(Boolean.FALSE, x.get("merchantActive"));
        assertEquals(Boolean.TRUE, x.get("canResubmit"));
        assertTrue(String.valueOf(x.get("statusText")).contains("还没开通或已结束"), String.valueOf(x.get("statusText")));
    }

    @Test
    void validationFailuresDoNotUseUpTheDailyQuota()
    {
        Runnable quota = mock(Runnable.class);
        Map<String, Object> b = form();
        b.put("storeType", "spa");
        assertThrows(ServiceException.class, () -> service.apply(1015L, b, quota));
        verify(quota, never()).run();
        service.apply(1015L, form(), quota);
        verify(quota).run();
    }
}
