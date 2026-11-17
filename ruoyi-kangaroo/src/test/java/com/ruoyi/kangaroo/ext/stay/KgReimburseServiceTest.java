package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.RolePhase;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 采购报销（V66，2026-10-31 主人拍板）：谁能交、表单边界、流转和通知、不能审自己（不连库；真库真接口、并发在 e2e/test_reimburse.py） */
class KgReimburseServiceTest
{
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1f, 0x15, (byte) 0xc4 };
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    private static final long ME = 7L;
    private static final long STORE = 102L;

    @TempDir
    Path folder;

    private String originalProfile;
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgReimburseService svc = new KgReimburseService();

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static void rejects(int code, String word, Executable run)
    {
        ServiceException e = assertThrows(ServiceException.class, run);
        assertEquals(Integer.valueOf(code), e.getCode(), e.getMessage());
        assertTrue(e.getMessage().contains(word), e.getMessage());
    }

    private static StaffGrantView grant(long store, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setStoreName("店" + store);
        g.setRoleCode(role);
        return g;
    }

    private void login(StaffGrantView... grants)
    {
        AppLoginUser me = new AppLoginUser();
        me.setUserId(ME);
        me.setNickname("阿杨");
        me.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grants)));
        AppUserContext.set(me);
        doAnswer(i -> {
            Long want = i.getArgument(0);
            for (StaffGrantView g : grants)
            {
                if (want.equals(g.getStoreId()))
                {
                    StaffSupport.Ctx c = new StaffSupport.Ctx();
                    c.me = me;
                    c.storeId = g.getStoreId();
                    c.storeName = g.getStoreName();
                    c.role = g.getRoleCode();
                    return c;
                }
            }
            throw new ServiceException("没有该门店的操作权限", 403);
        }).when(support).ctxForStore(any());
    }

    private Map<String, Object> saved(Object... kv)
    {
        Map<String, Object> r = row("reimburseId", 9L, "storeId", STORE, "storeName", "店102", "appUserId", ME, "submitterName", "阿杨", "itemName", "床单", "quantity", 2, "amount", new BigDecimal("128.50"),
                "purchaseDate", "2026-10-30", "receipts", "[]", "remark", "", "status", "pending", "revision", 1, "submitTime", "2026-10-31 10:00:00");
        r.putAll(row(kv));
        return r;
    }

    @BeforeEach
    void setup()
    {
        originalProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(folder.toString());
        ReflectionTestUtils.setField(svc, "db", db);
        ReflectionTestUtils.setField(svc, "support", support);
        when(db.one(anyString(), anyMap())).thenReturn(null);
        when(db.one(contains("from kg_app_user where user_id=:u for update"), anyMap())).thenReturn(row("status", "0", "delFlag", "0"));
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(row("opType", "direct"));
        when(db.insert(eq("kg_reimbursement"), anyMap())).thenReturn(9L);
        when(db.one(contains("from kg_reimbursement where reimburse_id = :id and del_flag = '0'"), anyMap())).thenReturn(saved());
        when(db.update(anyString(), anyMap())).thenReturn(1);
        login(grant(STORE, StaffRole.KEEPER));
    }

    @AfterEach
    void back()
    {
        AppUserContext.remove();
        RolePhase.set(RolePhase.LEGACY);
        new RuoYiConfig().setProfile(originalProfile);
    }

    private String image(String name) throws IOException
    {
        Path f = folder.resolve("upload/2026/10/31").resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, PNG);
        return "/profile/upload/2026/10/31/" + name;
    }

    private Map<String, Object> form() throws IOException
    {
        return row("itemName", " 床单 ", "quantity", 2, "amount", "128.5", "purchaseDate", LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(), "receipts", Collections.singletonList(image("a.png")), "remark", " 二楼 ");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inserted()
    {
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_reimbursement"), c.capture());
        return c.getValue();
    }

    // ---------------------------------------------------------------- 提交

    @Test
    void directKeeperSubmitsAndTheRowIsPending() throws IOException
    {
        Map<String, Object> out = svc.submit(STORE, form());
        Map<String, Object> r = inserted();
        assertEquals("pending", r.get("status"));
        assertEquals("床单", r.get("item_name"));
        assertEquals(new BigDecimal("128.50"), r.get("amount"));
        assertEquals("二楼", r.get("remark"));
        assertEquals(STORE, r.get("store_id"));
        assertEquals(ME, r.get("app_user_id"));
        assertEquals("店102", r.get("store_name"));
        assertTrue(String.valueOf(r.get("receipts")).startsWith("[\"/profile/upload/"));
        assertEquals("128.50", out.get("amount"));
        assertEquals("待审核", out.get("statusText"));
        assertEquals(Boolean.TRUE, out.get("canWithdraw"));
        assertEquals(Boolean.FALSE, out.get("canEdit"));
        assertFalse(out.containsKey("auditBy"), "后台账号名不给小院主理人");
        verify(support).op(any(), eq("reimburse_submit"), anyString(), eq("fund"), contains("¥128.50"), eq(true), isNull());
    }

    /** 账号锁必须是事务里第一条：后面的每小时条数、重复提交才读得到别人刚提交的（可重复读下普通 select 会先定快照） */
    @Test
    void theAccountLockComesFirst() throws IOException
    {
        svc.submit(STORE, form());
        org.mockito.InOrder order = inOrder(db);
        order.verify(db).one(contains("from kg_app_user where user_id=:u for update"), anyMap());
        order.verify(db).one(contains("from kg_store"), anyMap());
        order.verify(db).count(contains("and receipts = :r"), anyMap());
        order.verify(db).count(contains("submit_time > :since"), anyMap());
        order.verify(db).insert(eq("kg_reimbursement"), anyMap());
    }

    @Test
    void onlyAKeeperOfThatStore() throws IOException
    {
        Map<String, Object> f = form();
        for (String role : new String[] { StaffRole.MANAGER, StaffRole.STAFF, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT, StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD })
        {
            login(grant(STORE, role));
            rejects(403, "小院主理人", () -> svc.submit(STORE, f));
            rejects(403, "小院主理人", () -> svc.list(STORE, null, 1));
        }
        login(grant(STORE + 1, StaffRole.KEEPER));
        rejects(403, "门店", () -> svc.submit(STORE, f));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void keeperMeansTheSameInBothPhases() throws IOException
    {
        Map<String, Object> f = form();
        svc.submit(STORE, f);
        RolePhase.set(RolePhase.NEW);
        when(db.count(contains("and receipts = :r"), anyMap())).thenReturn(0L);
        f.put("itemName", "新阶段");
        svc.submit(STORE, f);
        verify(db, times(2)).insert(eq("kg_reimbursement"), anyMap());
    }

    @Test
    void noStoreGivenPicksTheKeeperGrantNotTheFirstGrant() throws IOException
    {
        login(grant(STORE + 1, StaffRole.MANAGER), grant(STORE, StaffRole.KEEPER));
        svc.submit(null, form());
        assertEquals(STORE, inserted().get("store_id"));
    }

    @Test
    void severalKeeperStoresMustSayWhich() throws IOException
    {
        Map<String, Object> f = form();
        login(grant(STORE, StaffRole.KEEPER), grant(STORE + 1, StaffRole.KEEPER));
        rejects(422, "门店", () -> svc.submit(null, f));
        svc.submit(STORE + 1, f);
        assertEquals(STORE + 1, inserted().get("store_id"));
    }

    @Test
    void onlyDirectStores() throws IOException
    {
        Map<String, Object> f = form();
        for (Object op : new Object[] { "managed", "franchise", "", null })
        {
            when(db.one(contains("from kg_store"), anyMap())).thenReturn(row("opType", op));
            rejects(403, "直营", () -> svc.submit(STORE, f));
        }
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(null);
        rejects(403, "直营", () -> svc.submit(STORE, f));
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void directIsTheColumnNotAGuess()
    {
        svc.isDirect(STORE);
        verify(db).one(eq("select op_type from kg_store where store_id = :s and status = '0' and del_flag = '0'"), anyMap());
    }

    @Test
    void bannedAccountStopsBeforeAnything() throws IOException
    {
        Map<String, Object> f = form();
        when(db.one(contains("from kg_app_user where user_id=:u for update"), anyMap())).thenReturn(row("status", "1", "delFlag", "0"));
        rejects(409, "不可用", () -> svc.submit(STORE, f));
        rejects(409, "不可用", () -> svc.resubmit(9L, f));
        rejects(409, "不可用", () -> svc.withdraw(9L));
        verify(db, never()).insert(anyString(), anyMap());
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void hourlyLimitCountsWithdrawnRowsToo() throws IOException
    {
        Map<String, Object> f = form();
        when(db.count(contains("submit_time > :since"), anyMap())).thenReturn(20L);
        rejects(429, "20", () -> svc.submit(STORE, f));
        verify(db, never()).insert(anyString(), anyMap());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).count(sql.capture(), anyMap());
        String hourly = sql.getAllValues().stream().filter(s -> s.contains("submit_time > :since")).findFirst().get();
        assertFalse(hourly.contains("del_flag"), "撤回的也算，不能撤了重发来绕");
        when(db.count(contains("submit_time > :since"), anyMap())).thenReturn(19L);
        svc.submit(STORE, f);
        verify(db).insert(eq("kg_reimbursement"), anyMap());
    }

    @Test
    void theSameContentAgainIsADoubleTap() throws IOException
    {
        Map<String, Object> f = form();
        when(db.count(contains("and receipts = :r"), anyMap())).thenReturn(1L);
        rejects(409, "提交过", () -> svc.submit(STORE, f));
        verify(db, never()).insert(anyString(), anyMap());
    }

    // ---------------------------------------------------------------- 表单边界

    @Test
    void amountBoundaries()
    {
        for (Object bad : new Object[] { null, "", " ", "0", 0, "0.00", 0.0, "-1", -1, "0.001", "1.234", 1.234, "100000.01", 100000.01, "100001", 100001, "1e3", "abc", "１２", "12,5", ".5", "5.", "+5", "1.230", true, Arrays.asList(1), row("a", 1), "99999999999999999999999" })
        {
            assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> KgReimburseService.amount(bad), String.valueOf(bad)).getCode());
        }
        assertEquals("0.01", KgReimburseService.amount("0.01").toPlainString());
        assertEquals("100000.00", KgReimburseService.amount("100000").toPlainString());
        assertEquals("100000.00", KgReimburseService.amount("100000.00").toPlainString());
        assertEquals("100000.00", KgReimburseService.amount(100000).toPlainString());
        assertEquals("12.50", KgReimburseService.amount(12.5).toPlainString());
        assertEquals("15.00", KgReimburseService.amount("015").toPlainString());
        assertEquals("8.80", KgReimburseService.amount(" 8.8 ").toPlainString());
    }

    @Test
    void quantityBoundaries()
    {
        for (Object bad : new Object[] { null, "", 0, "0", -1, 1.5, "1.5", "2.0", 2.0, "a", true, Arrays.asList(1), 1000000, "1000000", 12345678901L, "99999999999" })
        {
            assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> KgReimburseService.quantity(bad), String.valueOf(bad)).getCode());
        }
        assertEquals(1, KgReimburseService.quantity(1));
        assertEquals(3, KgReimburseService.quantity("3"));
        assertEquals(999999, KgReimburseService.quantity(999999));
    }

    @Test
    void itemNameIsOneToSixtyVisibleCharacters()
    {
        String sixty = String.join("", Collections.nCopies(60, "字"));
        for (Object bad : new Object[] { null, "", "   ", "　​️", sixty + "字", Arrays.asList("x"), row("a", 1), true })
        {
            rejects(422, "品名", () -> KgReimburseService.itemName(bad));
        }
        assertEquals(sixty, KgReimburseService.itemName(sixty));
        String emoji = String.join("", Collections.nCopies(60, new String(Character.toChars(0x1F6CF))));
        assertEquals(emoji, KgReimburseService.itemName(emoji), "按字算，一个表情一个字，和 varchar(60) 同一个算法");
        assertEquals("床单", KgReimburseService.itemName(" 床单 "));
    }

    @Test
    void purchaseDateBoundaries()
    {
        rejects(422, "不能晚于今天", () -> KgReimburseService.purchaseDate("2026-11-01", TODAY));
        for (Object bad : new Object[] { null, "", "2026-02-30", "2026/10/01", "20261001", 20261001, "abc", "1999-12-31", "0001-01-01", "+12026-01-01", Arrays.asList("2026-10-01") })
        {
            assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> KgReimburseService.purchaseDate(bad, TODAY), String.valueOf(bad)).getCode());
        }
        assertEquals(TODAY, KgReimburseService.purchaseDate("2026-10-31", TODAY));
        assertEquals(LocalDate.of(2000, 1, 1), KgReimburseService.purchaseDate("2000-01-01", TODAY));
    }

    @Test
    void receiptsAreOneToNinePlatformImagesThatExist() throws IOException
    {
        String a = image("a.png");
        rejects(422, "至少上传 1 张", () -> KgReimburseService.receipts(null));
        rejects(422, "至少上传 1 张", () -> KgReimburseService.receipts(new ArrayList<String>()));
        rejects(422, "至少上传 1 张", () -> KgReimburseService.receipts(""));
        List<String> ten = new ArrayList<String>();
        for (int i = 0; i < 10; i++)
        {
            ten.add(image(i + ".png"));
        }
        rejects(422, "最多 9 张", () -> KgReimburseService.receipts(ten));
        assertEquals(9, KgReimburseService.receipts(ten.subList(0, 9)).size());
        rejects(422, "本平台", () -> KgReimburseService.receipts(Arrays.asList("https://evil.example/a.png")));
        rejects(422, "本平台", () -> KgReimburseService.receipts(Arrays.asList("/profile/upload/../a.png")));
        rejects(422, "本平台", () -> KgReimburseService.receipts(Arrays.asList("/profile/upload/videos/2026/10/31/a.mp4")));
        rejects(422, "不存在", () -> KgReimburseService.receipts(Arrays.asList("/profile/upload/2026/10/31/missing.png")));
        Path notImage = folder.resolve("upload/2026/10/31/fake.png");
        Files.write(notImage, "not an image at all, just some text".getBytes(StandardCharsets.UTF_8));
        rejects(422, "不是有效的图片", () -> KgReimburseService.receipts(Arrays.asList("/profile/upload/2026/10/31/fake.png")));
        rejects(422, "格式不对", () -> KgReimburseService.receipts(123));
        assertEquals(Collections.singletonList(a), KgReimburseService.receipts(Arrays.asList(a, a)), "重复的只留一张");
        assertEquals(Collections.singletonList(a), KgReimburseService.receipts(a), "后台图片组件给的逗号分隔也认");
    }

    @Test
    void remarkIsOptional()
    {
        assertEquals("", KgReimburseService.remark(null));
        assertEquals("二楼", KgReimburseService.remark(" 二楼 "));
        assertEquals(200, KgReimburseService.remark(String.join("", Collections.nCopies(200, "字"))).length());
        rejects(422, "备注", () -> KgReimburseService.remark(String.join("", Collections.nCopies(201, "字"))));
        rejects(422, "备注", () -> KgReimburseService.remark(row("a", 1)));
    }

    // ---------------------------------------------------------------- 再交、撤回

    private void stored(Object... kv)
    {
        when(db.one(contains("from kg_reimbursement where reimburse_id = :id and del_flag = '0'"), anyMap())).thenReturn(saved(kv));
    }

    @Test
    void resubmitOnlyFromRejectedAndOnlyBySubmitter() throws IOException
    {
        Map<String, Object> f = form();
        stored("status", "pending");
        rejects(409, "被驳回", () -> svc.resubmit(9L, f));
        stored("status", "approved");
        rejects(409, "被驳回", () -> svc.resubmit(9L, f));
        stored("status", "rejected", "appUserId", 99L);
        rejects(403, "提交人", () -> svc.resubmit(9L, f));
        stored("status", "rejected");
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(row("opType", "managed"));
        rejects(403, "直营", () -> svc.resubmit(9L, f));
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(row("opType", "direct"));
        verify(db, never()).update(anyString(), anyMap());

        svc.resubmit(9L, f);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).update(sql.capture(), anyMap());
        assertTrue(sql.getValue().contains("status = 'pending', revision = revision + 1"), sql.getValue());
        assertTrue(sql.getValue().contains("reject_reason = null, audit_by = null, audit_time = null"), "审核信息清掉");
        assertTrue(sql.getValue().contains("where reimburse_id = :id and app_user_id = :u and status = 'rejected' and del_flag = '0'"), "带原状态，防并发");
    }

    @Test
    void lostTheRaceIsAConflict() throws IOException
    {
        Map<String, Object> f = form();
        stored("status", "rejected");
        when(db.update(anyString(), anyMap())).thenReturn(0);
        rejects(409, "刷新", () -> svc.resubmit(9L, f));
        stored("status", "pending");
        rejects(409, "刷新", () -> svc.withdraw(9L));
        rejects(409, "刷新", () -> svc.approve(9L, null, 1L, "admin"));
        rejects(409, "刷新", () -> svc.reject(9L, "不行", null, 1L, "admin"));
        stored("status", "approved");
        rejects(409, "刷新", () -> svc.markPaid(9L, 1L, "admin"));
        verify(support, never()).notifyStaff(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void withdrawOnlyPendingOnlySubmitterAndItIsASoftDelete()
    {
        for (String st : new String[] { "approved", "rejected", "paid" })
        {
            stored("status", st);
            rejects(409, "待审核", () -> svc.withdraw(9L));
        }
        stored("status", "pending", "appUserId", 99L);
        rejects(403, "提交人", () -> svc.withdraw(9L));
        stored("status", "pending");
        svc.withdraw(9L);
        verify(db).update(contains("set del_flag = '2'"), anyMap());
        verify(support).op(any(), eq("reimburse_withdraw"), anyString(), eq("fund"), anyString(), eq(true), isNull());
    }

    @Test
    void withdrawStillWorksAfterTheStoreStopsBeingDirect()
    {
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(row("opType", "managed"));
        stored("status", "pending");
        svc.withdraw(9L);
        verify(db).update(contains("set del_flag = '2'"), anyMap());
    }

    @Test
    void goneRowsAre404()
    {
        when(db.one(contains("from kg_reimbursement where reimburse_id = :id and del_flag = '0'"), anyMap())).thenReturn(null);
        rejects(404, "不存在", () -> svc.detail(9L));
        rejects(404, "不存在", () -> svc.withdraw(9L));
        rejects(404, "不存在", () -> svc.adminDetail(9L));
        rejects(404, "不存在", () -> svc.approve(9L, null, 1L, "admin"));
        rejects(404, "不存在", () -> svc.reject(9L, "不行", null, 1L, "admin"));
        rejects(404, "不存在", () -> svc.markPaid(9L, 1L, "admin"));
    }

    // ---------------------------------------------------------------- 小程序看

    @Test
    void anotherKeeperOfTheStoreSeesButCannotChange()
    {
        Map<String, Object> mine = KgReimburseService.view(saved("status", "rejected", "rejectReason", "小票看不清"), ME, true);
        assertEquals(Boolean.TRUE, mine.get("isMine"));
        assertEquals(Boolean.TRUE, mine.get("canEdit"));
        assertEquals("小票看不清", mine.get("rejectReason"));
        Map<String, Object> other = KgReimburseService.view(saved("status", "rejected", "rejectReason", "小票看不清"), 99L, true);
        assertEquals(Boolean.FALSE, other.get("isMine"));
        assertEquals(Boolean.FALSE, other.get("canEdit"));
        assertEquals(Boolean.FALSE, other.get("canWithdraw"));
        assertEquals(Boolean.FALSE, KgReimburseService.view(saved("status", "rejected"), ME, false).get("canEdit"), "门店不再直营：不能再交");
        assertEquals("", KgReimburseService.view(saved("status", "pending", "rejectReason", "旧的"), ME, true).get("rejectReason"), "只有已驳回才显示理由");
    }

    @Test
    void accessNeverFailsForStaff()
    {
        Map<String, Object> a = svc.access(STORE);
        assertEquals(Boolean.TRUE, a.get("canSubmit"));
        assertEquals(Boolean.TRUE, a.get("visible"));

        login(grant(STORE, StaffRole.MANAGER));
        a = svc.access(STORE);
        assertEquals(Boolean.FALSE, a.get("canSubmit"));
        assertEquals(Boolean.FALSE, a.get("visible"));

        login(grant(STORE, StaffRole.KEEPER));
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(row("opType", "managed"));
        when(db.count(contains("from kg_reimbursement where store_id"), anyMap())).thenReturn(0L);
        assertEquals(Boolean.FALSE, svc.access(STORE).get("visible"), "代运营店没有老记录：不放入口");
        when(db.count(contains("from kg_reimbursement where store_id"), anyMap())).thenReturn(2L);
        a = svc.access(STORE);
        assertEquals(Boolean.TRUE, a.get("visible"), "改成代运营之前交过的：还能看");
        assertEquals(Boolean.FALSE, a.get("canSubmit"));
        assertEquals(Boolean.FALSE, svc.access(STORE + 1).get("visible"), "别家门店不报错");
    }

    @Test
    void listStatusMustBeKnown()
    {
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(row("rows", new ArrayList<Map<String, Object>>(), "total", 0L, "hasMore", false, "pageNum", 1));
        rejects(422, "状态", () -> svc.list(STORE, "xx", 1));
        svc.list(STORE, "approved", 1);
        verify(db).page(contains("and status = :st"), anyMap(), eq(1), eq(KgReimburseService.PAGE_SIZE));
    }

    // ---------------------------------------------------------------- 后台审核

    private void noticeOf(String title, String word)
    {
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(support).notifyStaff(eq(ME), eq(MessageCategory.SYSTEM), eq(title), content.capture(), eq("reimburse"), eq(9L), eq("/pages/staff/reimburse?id=9&storeId=102"));
        assertTrue(content.getValue().contains("床单") && content.getValue().contains("¥128.50") && content.getValue().contains(word), content.getValue());
    }

    @Test
    void approveMovesPendingAndTellsTheSubmitter()
    {
        assertEquals("已通过", svc.approve(9L, null, 1L, "admin"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(sql.capture(), ps.capture());
        assertTrue(sql.getValue().contains("audit_by = :op, audit_time = now()"), sql.getValue());
        assertTrue(sql.getValue().endsWith("where reimburse_id = :id and status = :from and del_flag = '0'"), "带原状态，防并发");
        assertEquals("pending", ps.getValue().get("from"));
        assertEquals("approved", ps.getValue().get("to"));
        assertEquals("admin", ps.getValue().get("op"));
        noticeOf("采购报销已通过", "等平台打款");
    }

    @Test
    void auditReadsTheRowWithALock()
    {
        svc.approve(9L, null, 1L, "admin");
        verify(db).one(contains("for update"), anyMap());
    }

    @Test
    void rejectNeedsAReason()
    {
        for (String bad : new String[] { null, "", "  ", "　​" })
        {
            rejects(422, "理由", () -> svc.reject(9L, bad, null, 1L, "admin"));
        }
        rejects(422, "255", () -> svc.reject(9L, String.join("", Collections.nCopies(256, "字")), null, 1L, "admin"));
        verify(db, never()).update(anyString(), anyMap());
        assertEquals("已驳回", svc.reject(9L, " 小票看不清 ", null, 1L, "admin"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).update(contains("reject_reason = :why"), ps.capture());
        assertEquals("小票看不清", ps.getValue().get("why"));
        noticeOf("采购报销被驳回", "小票看不清");
    }

    @Test
    void onlyPendingCanBeAudited()
    {
        for (String st : new String[] { "approved", "rejected", "paid" })
        {
            stored("status", st);
            rejects(409, "待审核", () -> svc.approve(9L, null, 1L, "admin"));
            rejects(409, "待审核", () -> svc.reject(9L, "不行", null, 1L, "admin"));
        }
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void onlyApprovedCanBePaid()
    {
        for (String st : new String[] { "pending", "rejected", "paid" })
        {
            stored("status", st);
            rejects(409, "已通过", () -> svc.markPaid(9L, 1L, "admin"));
        }
        verify(db, never()).update(anyString(), anyMap());
        stored("status", "approved");
        assertEquals("已标记打款", svc.markPaid(9L, 1L, "admin"));
        verify(db).update(contains("paid_by = :op, paid_time = now()"), anyMap());
        noticeOf("采购报销已打款", "留意收款");
    }

    @Test
    void staleRevisionCannotApproveChangedContent()
    {
        stored("revision", 2);
        rejects(409, "刷新", () -> svc.approve(9L, 1, 1L, "admin"));
        rejects(409, "刷新", () -> svc.reject(9L, "不行", "1", 1L, "admin"));
        rejects(422, "revision", () -> svc.approve(9L, "x", 1L, "admin"));
        verify(db, never()).update(anyString(), anyMap());
        assertEquals("已通过", svc.approve(9L, 2, 1L, "admin"));
    }

    @Test
    void cannotAuditYourOwnWhenThePhoneMatches()
    {
        when(db.one(contains("from sys_user"), anyMap())).thenReturn(row("phonenumber", "13900000001"));
        when(db.one(contains("select phone from kg_app_user"), anyMap())).thenReturn(row("phone", "13900000001"));
        rejects(403, "自己", () -> svc.approve(9L, null, 1L, "admin"));
        rejects(403, "自己", () -> svc.reject(9L, "不行", null, 1L, "admin"));
        stored("status", "approved");
        rejects(403, "自己", () -> svc.markPaid(9L, 1L, "admin"));
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void differentOrEmptyPhonesDoNotBlock()
    {
        when(db.one(contains("from sys_user"), anyMap())).thenReturn(row("phonenumber", "13900000001"));
        when(db.one(contains("select phone from kg_app_user"), anyMap())).thenReturn(row("phone", "13900000002"));
        assertEquals("已通过", svc.approve(9L, null, 1L, "admin"));
        when(db.one(contains("from sys_user"), anyMap())).thenReturn(row("phonenumber", ""));
        when(db.one(contains("select phone from kg_app_user"), anyMap())).thenReturn(row("phone", ""));
        assertEquals("已通过", svc.approve(9L, null, 1L, "admin"), "两边都空不算同一个人");
        when(db.one(contains("from sys_user"), anyMap())).thenReturn(row("phonenumber", "13900000001"));
        when(db.one(contains("select phone from kg_app_user"), anyMap())).thenReturn(row("phone", null));
        assertEquals("已通过", svc.approve(9L, null, 1L, "admin"), "注销后手机号是空的");
    }

    // ---------------------------------------------------------------- 后台列表

    @Test
    void filtersAreParametersAndValidated()
    {
        Map<String, Object> ps = new HashMap<String, Object>();
        Map<String, String> q = new HashMap<String, String>();
        q.put("storeId", "102");
        q.put("storeName", "100%_!");
        q.put("status", "paid");
        q.put("appUserId", "7");
        q.put("submitter", "阿");
        q.put("purchaseBegin", "2026-10-01");
        q.put("purchaseEnd", "2026-10-31");
        q.put("submitBegin", "2026-10-02");
        q.put("submitEnd", "2026-10-30");
        String where = KgReimburseService.adminWhere(q, ps);
        assertTrue(where.startsWith(" where del_flag = '0'"), "撤回的不进表格");
        assertEquals("%100!%!_!!%", ps.get("storeName"), "% 和 _ 不当通配符");
        assertEquals("2026-10-02 00:00:00", ps.get("submitBegin"));
        assertEquals("2026-10-30 23:59:59", ps.get("submitEnd"));
        assertFalse(where.contains("100") || where.contains("paid") || where.contains("阿"), "值都走参数，不进 SQL");
        for (String[] bad : new String[][] { { "status", "xx" }, { "purchaseBegin", "2026-02-30" }, { "purchaseEnd", "abc" }, { "submitBegin", "x" }, { "submitEnd", "20261031" }, { "storeId", "abc" }, { "appUserId", "1 or 1=1" } })
        {
            rejects(422, "", () -> KgReimburseService.adminWhere(Collections.singletonMap(bad[0], bad[1]), new HashMap<String, Object>()));
        }
    }

    @Test
    void listIsPendingFirstAndCarriesTotals()
    {
        when(db.page(anyString(), anyMap(), any(), any())).thenReturn(row("rows", new ArrayList<Map<String, Object>>(Arrays.asList(saved())), "total", 1L, "hasMore", false, "pageNum", 1, "pageSize", 10));
        when(db.list(contains("group by status"), anyMap())).thenReturn(Arrays.asList(row("status", "pending", "n", 2L, "total", new BigDecimal("10.10")), row("status", "rejected", "n", 1L, "total", new BigDecimal("0.20")), row("status", "paid", "n", 1L, "total", new BigDecimal("100000.00"))));
        Map<String, Object> out = svc.adminList(Collections.<String, String>emptyMap());
        verify(db).page(contains("order by field(status, 'pending', 'approved', 'rejected', 'paid'), reimburse_id desc"), anyMap(), eq(1), eq(10));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        assertEquals(4L, summary.get("count"));
        assertEquals("100010.30", summary.get("sumAmount"), "不用浮点算");
        assertEquals("100010.10", summary.get("validAmount"), "去掉驳回");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> by = (Map<String, Map<String, Object>>) summary.get("byStatus");
        assertEquals("0.00", by.get("approved").get("amount"), "没有的状态也有 0");
        assertEquals(2L, by.get("pending").get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals("128.50", rows.get(0).get("amount"));
        assertEquals(Boolean.FALSE, rows.get(0).get("anonymous"));
        assertEquals(1L, out.get("total"));
    }

    @Test
    void moneyIsAlwaysTwoDecimals()
    {
        assertEquals("128.50", KgReimburseService.money(new BigDecimal("128.5")));
        assertEquals("0.00", KgReimburseService.money(null));
        assertEquals("100000.00", KgReimburseService.money(100000L));
    }

    // ---------------------------------------------------------------- 登记

    @Test
    void messageListLetsTheNoticeThroughForAccountsWithOnlineStaffGrants() throws Exception
    {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/resources/mapper/kangaroo/KgMessageMapper.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("or biz_type='reimburse'"), "账号同时带专员授权时也要看得到，不然红点清不掉");
        assertEquals(KgReimburseService.TYPE, "reimburse");
    }

    @Test
    void routeFitsTheMessageColumn()
    {
        assertTrue(KgReimburseService.route(Long.MAX_VALUE, Long.MAX_VALUE).length() <= 128);
        assertEquals("/pages/staff/reimburse?id=9&storeId=102", KgReimburseService.route(9L, 102L));
    }

    @Test
    void submitDoesNotNotifyAnyone() throws IOException
    {
        svc.submit(STORE, form());
        verify(support, never()).notifyStaff(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        verify(support, never()).notifyStore(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }
}
