package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppLoginUser;

/**
 * 线上任务「发素材」：谁能发、什么时候能发、发了什么，以及交付步骤条怎么由现有状态推出来（KgJdbc 打桩，不连库）。
 * 素材只是信息展示，不碰成果提交 / 结算的状态机。
 */
class KgOnlineMaterialServiceTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    private final KgOnlineMaterialFiles files = mock(KgOnlineMaterialFiles.class);
    private final KgOnlineMaterialService service = new KgOnlineMaterialService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    private Map<String, Object> app;
    private Map<String, Object> worker;
    private Map<String, Object> settlement;
    /** kg_online_material_file 的行（按 file_id）：V41 附件 */
    private final Map<Long, Map<String, Object>> fileRows = new HashMap<Long, Map<String, Object>>();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "files", files);
        ctx.storeId = 3L; ctx.storeName = "平潭袋鼠小院"; ctx.role = "online_staff";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setNickname("小林");
        app = row("appId", 7L, "appUserId", 2L, "jobId", 8L, "status", "review_passed", "delFlag", "0", "jobStore", 3L, "title", "探店笔记", "jobType", "online");
        worker = row("status", "0", "delFlag", "0");
        settlement = null;
        when(db.one(anyString(), anyMap())).thenAnswer(i -> {
            String sql = i.getArgument(0);
            Map<String, Object> params = i.getArgument(1);
            if (sql.contains("from kg_online_material_file")) return fileRows.get(KgJdbc.asLong(params.get("f")));
            if (sql.contains("from kg_application a join kg_job j")) return app;
            if (sql.contains("from kg_app_user")) return worker;
            if (sql.contains("from kg_online_settlement")) return settlement;
            return null;
        });
        when(db.insert(eq("kg_online_material"), anyMap())).thenReturn(55L);
        when(db.update(contains("update kg_online_material_file set material_id"), anyMap())).thenReturn(1);
    }

    /** 一个已上传、还没发出的附件（属于接单 7、门店 3） */
    private Map<String, Object> pendingFile(long fileId)
    {
        Map<String, Object> f = row("fileId", fileId, "appId", 7L, "storeId", 3L, "materialId", null, "delFlag", "0", "fileKey", "omat/7/" + fileId + ".zip",
                "fileName", "原图" + fileId + ".zip", "fileExt", "zip", "fileSize", 1024L);
        fileRows.put(fileId, f);
        return f;
    }

    private static Map<String, Object> withFiles(String content, Object... ids)
    {
        return row("content", content, "images", new ArrayList<String>(), "files", Arrays.asList(ids));
    }

    private static Map<String, Object> row(Object... kv)
    {
        return new HashMap<String, Object>(p(kv));
    }

    private static Map<String, Object> body(String content, String... images)
    {
        return row("content", content, "images", Arrays.asList(images));
    }

    private int rejected(Map<String, Object> body)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> service.send(ctx, 7L, body));
        verify(db, never()).insert(anyString(), anyMap());
        verifyNoInteractions(support);
        return e.getCode();
    }

    // ---------------------------------------------------------------- 内容校验

    @Test
    void needsTextOrImages()
    {
        assertEquals(422, rejected(body("  ")));
    }

    private static String[] images(int n)
    {
        String[] out = new String[n];
        // 和真实上传返回的地址一样长：/profile/upload/yyyy/MM/dd/<32 位 hex>.jpeg
        for (int i = 0; i < n; i++) out[i] = String.format("/profile/upload/2026/10/15/%032d.jpeg", i);
        return out;
    }

    @Test
    void onlyPlatformImagesUpToTwenty()
    {
        assertEquals(422, rejected(body("看图", "https://evil.example.com/a.png")));
        assertEquals(422, rejected(body("", images(KgOnlineMaterialService.MAX_IMAGES + 1))));
        // 一次多选 20 张：20 个真实长度的地址拼起来仍在 images 列的 2000 字符以内
        assertEquals(20, KgOnlineMaterialService.MAX_IMAGES);
        assertEquals(55L, service.send(ctx, 7L, body("", images(20))).get("materialId"));
        ArgumentCaptor<Map> rowCap = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_online_material"), rowCap.capture());
        String saved = String.valueOf(rowCap.getValue().get("images"));
        assertEquals(20, saved.split(",").length);
        assertTrue(saved.length() <= KgOnlineMaterialService.MAX_IMAGES_LEN, String.valueOf(saved.length()));
    }

    @Test
    void textIsCappedAtTenThousandChars()
    {
        assertEquals(10000, KgOnlineMaterialService.MAX_CONTENT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KgOnlineMaterialService.MAX_CONTENT + 1; i++) sb.append('字');
        ServiceException e = assertThrows(ServiceException.class, () -> service.send(ctx, 7L, body(sb.toString())));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertTrue(e.getMessage().contains("附件"), e.getMessage());
        verify(db, never()).insert(anyString(), anyMap());
        // 正好 1 万字可以发（不在输入框里截断，也不在服务端截断）
        sb.setLength(KgOnlineMaterialService.MAX_CONTENT);
        assertEquals(55L, service.send(ctx, 7L, body(sb.toString())).get("materialId"));
    }

    @Test
    void cumulativeTextPerAssignmentIsCapped()
    {
        // 交付页一次下发整个接单：累计文字超过 10 万字就不再收，提示放进文档当附件
        when(db.count(contains("sum(char_length(content))"), anyMap())).thenReturn((long) KgOnlineMaterialService.MAX_CONTENT_TOTAL - 3);
        assertEquals(409, rejected(body("四个字呀")));
        // 只发图片不受文字累计的限制
        assertEquals(55L, service.send(ctx, 7L, body("", "/profile/upload/a.jpg")).get("materialId"));
    }

    // ---------------------------------------------------------------- 谁能发、什么时候能发

    @Test
    void otherStoresAssignmentIsNotFound()
    {
        app.put("jobStore", 99L);
        assertEquals(404, rejected(body("素材")));
    }

    @Test
    void offlineJobApplicationsAreNotOnlineAssignments()
    {
        app.put("jobType", "volunteer");
        assertEquals(404, rejected(body("素材")));
    }

    @Test
    void cannotSendToOwnAssignment()
    {
        app.put("appUserId", 1L);
        assertEquals(403, rejected(body("素材")));
    }

    @Test
    void onlyAfterTheStoreAcceptedTheApplicant()
    {
        app.put("status", "submitted");
        ServiceException e = assertThrows(ServiceException.class, () -> service.send(ctx, 7L, body("素材")));
        assertEquals(Integer.valueOf(409), e.getCode());
        assertTrue(e.getMessage().contains("接单"), e.getMessage());
        for (String st : new String[] { "withdrawn", "terminated", "review_rejected" })
        {
            app.put("status", st);
            assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.send(ctx, 7L, body("素材"))).getCode(), st);
        }
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void deactivatedWorkerGetsNoMaterials()
    {
        worker.put("delFlag", "2");
        assertEquals(409, rejected(body("素材")));
    }

    @Test
    void notAfterSettlementIsConfirmedButStillWhilePaymentIsPending()
    {
        settlement = row("status", "confirmed");
        assertEquals(409, rejected(body("素材")));
        settlement = row("status", "pending_payout");
        assertEquals(55L, service.send(ctx, 7L, body("补一张图", "/profile/upload/a.jpg")).get("materialId"));
    }

    @Test
    void perAssignmentCapStopsAccidentalFlooding()
    {
        when(db.count(contains("from kg_online_material"), anyMap())).thenReturn((long) KgOnlineMaterialService.MAX_PER_ASSIGNMENT);
        assertEquals(409, rejected(body("素材")));
    }

    @Test
    void blockedWordsAreRefusedByTheGuard()
    {
        when(guard.check(anyString())).thenThrow(new ServiceException("内容包含不允许发布的信息，请修改后再发", 422));
        assertEquals(422, rejected(body("违禁内容")));
    }

    // ---------------------------------------------------------------- 发出去之后

    @Test
    @SuppressWarnings("unchecked")
    void sendStoresTextAndImagesNotifiesWorkerAndLogs()
    {
        Map<String, Object> out = service.send(ctx, 7L, body("  第一批素材：店内全景、招牌饮品  ", "/profile/upload/a.jpg", "/profile/upload/b.jpg", "/profile/upload/a.jpg"));
        assertEquals(55L, out.get("materialId"));
        ArgumentCaptor<Map> rowCap = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_online_material"), rowCap.capture());
        Map<String, Object> saved = rowCap.getValue();
        assertEquals(7L, saved.get("app_id"));
        assertEquals(8L, saved.get("job_id"));
        assertEquals(3L, saved.get("store_id"));
        assertEquals("第一批素材：店内全景、招牌饮品", saved.get("content"));
        assertEquals("/profile/upload/a.jpg,/profile/upload/b.jpg", saved.get("images"), "重复的图片只存一次");
        assertEquals(1L, saved.get("sent_by_id"));
        assertEquals("小林", saved.get("sent_by"));
        assertNotNull(saved.get("sent_time"));
        verify(support).notifyUser(eq(2L), eq("apply"), eq("门店给你发了任务素材"), contains("「探店笔记」"), eq("application"), eq(7L), eq("/pages/jobs/deliver?appId=7"));
        verify(support).op(same(ctx), eq("online_material"), eq("发送任务素材"), eq("general"), contains("2 张图"), eq(true), isNull());
        // 只是信息展示：不改报名、成果、结算
        verify(db, never()).update(anyString(), anyMap());
    }

    @Test
    void imagesOnlyMessageSaysHowManyPictures()
    {
        service.send(ctx, 7L, body("", "/profile/upload/a.jpg"));
        verify(support).notifyUser(eq(2L), eq("apply"), eq("门店给你发了任务素材"), contains("1 张素材图片"), eq("application"), eq(7L), anyString());
    }

    // ---------------------------------------------------------------- 附件（V41）

    @Test
    void filesAreAttachedToThisMaterialUnderRowLocks()
    {
        pendingFile(31L);
        pendingFile(32L);
        Map<String, Object> out = service.send(ctx, 7L, withFiles("", 31L, "32", 31L));
        assertEquals(55L, out.get("materialId"));
        verify(db).one(contains("from kg_online_material_file where file_id = :f for update"), eq(p("f", 31L)));
        verify(db).one(contains("from kg_online_material_file where file_id = :f for update"), eq(p("f", 32L)));
        verify(db).update(contains("set material_id = :m where file_id = :f and material_id is null and del_flag = '0'"), eq(p("m", 55L, "f", 31L)));
        verify(db).update(contains("set material_id = :m where file_id = :f and material_id is null and del_flag = '0'"), eq(p("m", 55L, "f", 32L)));
        // 只有文件时，通知说清楚有几个文件、去交付页下载；操作日志带上文件数
        verify(support).notifyUser(eq(2L), eq("apply"), eq("门店给你发了任务素材"), argThat(s -> s.contains("门店发来 2 个文件（压缩包/文档）") && s.contains("请到任务交付页查看和下载")),
                eq("application"), eq(7L), eq("/pages/jobs/deliver?appId=7"));
        verify(support).op(same(ctx), eq("online_material"), eq("发送任务素材"), eq("general"), contains("+ 2 个文件"), eq(true), isNull());
    }

    @Test
    void textWithPicturesAndFilesShowsAShortPreview()
    {
        assertEquals("拍店内全景。点开查看，按要求完成后提交成果", KgOnlineMaterialService.notice("拍店内全景", 0, 0));
        // 文件种类不套两层括号（之前是「（附 5 个文件（压缩包/文档））」）
        assertEquals("拍店内全景（附 3 张素材图片、1 个文件：压缩包/文档）。请到任务交付页查看和下载，按要求完成后提交成果",
                KgOnlineMaterialService.notice("拍店内全景", 3, 1));
        assertEquals("拍店内全景（附 5 个文件：压缩包/文档）。请到任务交付页查看和下载，按要求完成后提交成果",
                KgOnlineMaterialService.notice("拍店内全景", 0, 5));
        assertEquals("拍店内全景（附 3 张素材图片）。点开查看，按要求完成后提交成果", KgOnlineMaterialService.notice("拍店内全景", 3, 0));
        assertEquals("门店发来 1 个文件（压缩包/文档）。请到任务交付页查看和下载，按要求完成后提交成果", KgOnlineMaterialService.notice("", 0, 1));
        assertEquals("门店发来 20 张素材图片。点开查看，按要求完成后提交成果", KgOnlineMaterialService.notice("", 20, 0));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 80; i++) longText.append('长');
        String n = KgOnlineMaterialService.notice(longText.toString(), 0, 0);
        assertTrue(n.startsWith(longText.substring(0, 60) + "…。"), n);
        assertEquals("门店发来 2 张素材图片和 1 个文件（压缩包/文档）。请到任务交付页查看和下载，按要求完成后提交成果", KgOnlineMaterialService.notice("", 2, 1));
    }

    @Test
    void filesMustBelongToThisAssignmentAndStoreAndStillBeUnsent()
    {
        Map<String, Object> other = pendingFile(41L);
        other.put("appId", 99L);  // 别的接单
        assertEquals(404, rejected(withFiles("", 41L)));
        Map<String, Object> otherStore = pendingFile(42L);
        otherStore.put("storeId", 9L);
        assertEquals(404, rejected(withFiles("", 42L)));
        Map<String, Object> cleaned = pendingFile(43L);
        cleaned.put("delFlag", "2");  // 24 小时没发出被清理了
        assertEquals(404, rejected(withFiles("", 43L)));
        assertEquals(404, rejected(withFiles("", 44L)));  // 不存在
        Map<String, Object> sent = pendingFile(45L);
        sent.put("materialId", 12L);  // 已经随别的素材发出去了
        assertEquals(409, rejected(withFiles("", 45L)));
    }

    @Test
    void atMostFiveFilesPerSendAndIdsMustBeNumbers()
    {
        assertEquals(422, rejected(withFiles("", 1L, 2L, 3L, 4L, 5L, 6L)));
        assertEquals(422, rejected(withFiles("", "abc")));
        assertEquals(422, rejected(withFiles("", -3)));
        assertEquals(422, rejected(row("content", "", "files", "31")));
        assertEquals(java.util.Collections.singletonList(31L), KgOnlineMaterialService.fileIds(Arrays.asList(31, "31", 31L)));
    }

    @Test
    void concurrentSendThatAlreadyClaimedTheFileRollsBack()
    {
        pendingFile(51L);
        when(db.update(contains("update kg_online_material_file set material_id"), anyMap())).thenReturn(0);
        ServiceException e = assertThrows(ServiceException.class, () -> service.send(ctx, 7L, withFiles("说明", 51L)));
        assertEquals(Integer.valueOf(409), e.getCode());
        verifyNoInteractions(support);
    }

    private org.springframework.mock.web.MockMultipartFile zip()
    {
        return new org.springframework.mock.web.MockMultipartFile("file", "tmp.zip", null, new byte[] { 0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0 });
    }

    private void filesAccept()
    {
        when(files.check(any(), any())).thenReturn(new KgOnlineMaterialFiles.Checked("原图.zip", "zip", 8L));
        when(files.write(eq(7L), any(), eq("zip"))).thenReturn("omat/7/" + "a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4" + ".zip");
        when(db.insert(eq("kg_online_material_file"), anyMap())).thenReturn(88L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void uploadChecksTheSameRulesThenStoresAPendingFile()
    {
        filesAccept();
        Map<String, Object> out = service.upload(ctx, 7L, zip(), "原图.zip");
        assertEquals(88L, out.get("fileId"));
        assertEquals("原图.zip", out.get("name"));
        assertEquals("archive", out.get("kind"));
        ArgumentCaptor<Map> rowCap = ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_online_material_file"), rowCap.capture());
        Map<String, Object> saved = rowCap.getValue();
        assertEquals(7L, saved.get("app_id"));
        assertEquals(3L, saved.get("store_id"));
        assertEquals(1L, saved.get("uploaded_by"));
        assertEquals("zip", saved.get("file_ext"));
        assertEquals(8L, saved.get("file_size"));
        assertFalse(saved.containsKey("material_id"), "上传时还没发出");
        // 和发送同一套前置检查：自己的接单、结算完成后都不能上传
        app.put("appUserId", 1L);
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class, () -> service.upload(ctx, 7L, zip(), "原图.zip")).getCode());
        app.put("appUserId", 2L);
        settlement = row("status", "confirmed");
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.upload(ctx, 7L, zip(), "原图.zip")).getCode());
        verify(files, times(1)).write(any(), any(), any());
    }

    @Test
    void uploadQuotasStopPilesOfUnsentOrHugeFiles()
    {
        filesAccept();
        when(db.count(contains("material_id is null and del_flag = '0'"), anyMap())).thenReturn((long) KgOnlineMaterialService.MAX_PENDING_FILES);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.upload(ctx, 7L, zip(), "原图.zip")).getCode());
        when(db.count(contains("material_id is null and del_flag = '0'"), anyMap())).thenReturn(0L);
        when(db.count(contains("from kg_online_material_file where app_id = :id and del_flag = '0'"), anyMap())).thenReturn(KgOnlineMaterialService.MAX_BYTES_PER_ASSIGNMENT - 4);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.upload(ctx, 7L, zip(), "原图.zip")).getCode());
        when(db.count(contains("from kg_online_material_file where app_id = :id and del_flag = '0'"), anyMap())).thenReturn(0L);
        when(db.count(contains("where store_id = :s and create_time >= curdate()"), anyMap())).thenReturn(KgOnlineMaterialService.MAX_BYTES_PER_STORE_DAY);
        assertEquals(Integer.valueOf(429), assertThrows(ServiceException.class, () -> service.upload(ctx, 7L, zip(), "原图.zip")).getCode());
        verify(files, never()).write(any(), any(), any());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void failedRowInsertRemovesTheWrittenFile()
    {
        filesAccept();
        when(db.insert(eq("kg_online_material_file"), anyMap())).thenThrow(new RuntimeException("duplicate key"));
        assertThrows(RuntimeException.class, () -> service.upload(ctx, 7L, zip(), "原图.zip"));
        verify(files).delete("omat/7/a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4.zip");
    }

    @Test
    void uploadLocksTheAssignmentBeforeCountingQuotas()
    {
        // 两个上传同时进来时，先锁住这条接单再数配额，不会都看到「还差 1 个」一起放行
        filesAccept();
        service.upload(ctx, 7L, zip(), "原图.zip");
        org.mockito.InOrder order = inOrder(db);
        order.verify(db).one(eq("select app_id from kg_application where app_id = :id for update"), anyMap());
        order.verify(db).count(contains("material_id is null and del_flag = '0'"), anyMap());
    }

    @Test
    void rolledBackTransactionRemovesTheWrittenFile()
    {
        // 插行成功、但外层事务后来回滚：afterCompletion 删掉已写到磁盘的文件；提交了就不删
        filesAccept();
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try
        {
            service.upload(ctx, 7L, zip(), "原图.zip");
            java.util.List<org.springframework.transaction.support.TransactionSynchronization> syncs =
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, syncs.size());
            syncs.get(0).afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
            verify(files, never()).delete(anyString());
            syncs.get(0).afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(files).delete("omat/7/a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4.zip");
        }
        finally
        {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void onlyUnsentFilesOfThisStoreCanBeRemoved()
    {
        pendingFile(61L);
        assertEquals("omat/7/61.zip", service.removePending(ctx, 7L, 61L));
        verify(db).update(contains("set del_flag = '2' where file_id = :f and material_id is null"), eq(p("f", 61L)));
        Map<String, Object> sent = pendingFile(62L);
        sent.put("materialId", 5L);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.removePending(ctx, 7L, 62L)).getCode());
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.removePending(ctx, 8L, 61L)).getCode());
        pendingFile(63L).put("storeId", 9L);
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.removePending(ctx, 7L, 63L)).getCode());
    }

    @Test
    void workerDownloadsOnlyOwnSentFiles()
    {
        AppLoginUser me = new AppLoginUser();
        me.setUserId(2L);
        com.ruoyi.kangaroo.security.AppUserContext.set(me);
        try
        {
            Map<String, Object> f = pendingFile(71L);
            f.put("materialId", 55L);
            when(db.one(contains("select a.app_user_id from kg_application a join kg_job j"), anyMap())).thenReturn(row("appUserId", 2L));
            when(db.one(contains("material_id is not null and del_flag = '0'"), anyMap())).thenAnswer(i -> {
                Map<String, Object> ps = i.getArgument(1);
                Map<String, Object> r = fileRows.get(KgJdbc.asLong(ps.get("f")));
                return r != null && Long.valueOf(7L).equals(ps.get("a")) && r.get("materialId") != null ? r : null;
            });
            assertSame(f, service.workerFile(7L, 71L));
            pendingFile(72L);  // 还没发出的：接单人看不到
            assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.workerFile(7L, 72L)).getCode());
            // 换一个接单人：404
            when(db.one(contains("select a.app_user_id from kg_application a join kg_job j"), anyMap())).thenReturn(row("appUserId", 5L));
            assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> service.workerFile(7L, 71L)).getCode());
        }
        finally
        {
            com.ruoyi.kangaroo.security.AppUserContext.remove();
        }
    }

    @Test
    void linksAreOnlyForSentFilesAndLastThreeDays()
    {
        KgOnlineMaterialFiles real = new KgOnlineMaterialFiles("0123456789abcdef-test-secret");
        ReflectionTestUtils.setField(service, "files", real);
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.link(pendingFile(81L))).getCode());
        Map<String, Object> sent = pendingFile(82L);
        sent.put("materialId", 55L);
        Map<String, Object> link = service.link(sent);
        assertEquals(72, link.get("hours"));
        String url = String.valueOf(link.get("url"));
        assertTrue(url.startsWith("/kangaroo/file/material?f=82&e="), url);
        long exp = Long.parseLong(url.replaceAll(".*&e=(\\d+)&s=.*", "$1"));
        long left = exp - System.currentTimeMillis() / 1000;
        assertTrue(left > 71 * 3600 && left <= 72 * 3600, String.valueOf(left));
        String sig = url.substring(url.indexOf("&s=") + 3);
        when(db.one(contains("where file_id = :f and material_id is not null and del_flag = '0'"), eq(p("f", 82L)))).thenReturn(sent);
        assertSame(sent, service.signedFile(82L, exp, sig));
        assertNull(service.signedFile(82L, exp, sig.substring(0, sig.length() - 1) + (sig.endsWith("0") ? "1" : "0")));
        // 文件被删（行查不到）链接就失效
        when(db.one(contains("where file_id = :f and material_id is not null and del_flag = '0'"), eq(p("f", 82L)))).thenReturn(null);
        assertNull(service.signedFile(82L, exp, sig));
    }

    @Test
    void staffViewShowsSenderUserViewShowsStore()
    {
        List<Map<String, Object>> raw = Collections.singletonList(row("materialId", 1L, "content", "说明", "images", "/profile/a.jpg, /profile/b.jpg",
                "sentBy", "小林", "sentTime", "2026-10-10 09:30:00"));
        Map<String, Object> staff = KgOnlineMaterialService.rows(raw, true, "平潭袋鼠小院").get(0);
        Map<String, Object> user = KgOnlineMaterialService.rows(raw, false, "平潭袋鼠小院").get(0);
        assertEquals("小林", staff.get("sentBy"));
        assertEquals("平潭袋鼠小院", user.get("sentBy"));
        assertEquals(Arrays.asList("/profile/a.jpg", "/profile/b.jpg"), user.get("images"));
        assertEquals("2026-10-10 09:30", user.get("time"));
    }

    // ---------------------------------------------------------------- 步骤条

    private static List<Map<String, Object>> materials()
    {
        return Collections.singletonList(row("materialId", 1L, "sentTime", "2026-10-03 10:00:00"));
    }

    private static List<Map<String, Object>> subs(String... statusNewestFirst)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < statusNewestFirst.length; i++)
        {
            out.add(row("status", statusNewestFirst[i], "time", "2026-10-0" + (9 - i) + " 10:00"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> step(Map<String, Object> flow, int n)
    {
        return ((List<Map<String, Object>>) flow.get("steps")).get(n - 1);
    }

    @Test
    void flowFollowsExistingStatesPlusMaterialTime()
    {
        Map<String, Object> f = KgOnlineMaterialService.flow("submitted", "2026-10-01 09:00:00", null, null, null, null);
        assertEquals(1, f.get("current"));
        assertEquals("10-01", step(f, 1).get("time"));
        assertTrue(String.valueOf(f.get("tip")).contains("审核"));

        f = KgOnlineMaterialService.flow("review_passed", "2026-10-01 09:00:00", "10-02 08:00", null, null, null);
        assertEquals(2, f.get("current"));
        assertEquals("10-02", step(f, 2).get("time"));
        assertEquals("素材已发", step(f, 3).get("label"));
        assertEquals("todo", step(f, 3).get("state"));
        assertTrue(String.valueOf(f.get("tip")).contains("门店还没发素材，可以先私信门店沟通"), String.valueOf(f.get("tip")));

        f = KgOnlineMaterialService.flow("review_passed", "2026-10-01 09:00:00", "10-02 08:00", materials(), null, null);
        assertEquals(3, f.get("current"));
        assertEquals("current", step(f, 3).get("state"));
        assertEquals("10-03", step(f, 3).get("time"));

        f = KgOnlineMaterialService.flow("review_passed", "2026-10-01 09:00:00", "10-02 08:00", materials(), subs("returned", "returned"), null);
        assertEquals(4, f.get("current"));
        assertEquals("待修改", step(f, 4).get("label"));
        assertEquals("10-08", step(f, 4).get("time"), "已交付的时间是第一次提交");
        assertEquals("done", step(f, 3).get("state"));
    }

    @Test
    void deliveringWithoutMaterialsMarksTheMaterialStepSkippedNotDone()
    {
        Map<String, Object> f = KgOnlineMaterialService.flow("review_passed", null, "10-02 08:00", null, subs("submitted"), null);
        assertEquals(4, f.get("current"));
        assertEquals("未发素材", step(f, 3).get("label"));
        assertEquals("skipped", step(f, 3).get("state"));
        assertEquals("已交付", step(f, 4).get("label"));
        assertTrue(String.valueOf(f.get("tip")).contains("等门店验收"));
    }

    @Test
    void settlementStagesMapToTheLastStep()
    {
        Map<String, Object> st = row("status", "pending_payout", "createTime", "2026-10-09 12:00:00");
        Map<String, Object> f = KgOnlineMaterialService.flow("review_passed", null, null, materials(), subs("accepted"), st);
        assertEquals(5, f.get("current"));
        assertEquals("10-09", step(f, 5).get("time"));
        assertEquals("待打款", step(f, 6).get("label"));

        st = row("status", "payout_registered", "createTime", "2026-10-09 12:00:00", "registeredTime", "2026-10-10 08:00:00");
        f = KgOnlineMaterialService.flow("review_passed", null, null, materials(), subs("accepted"), st);
        assertEquals(6, f.get("current"));
        assertEquals("已登记打款", step(f, 6).get("label"));
        assertEquals("10-10", step(f, 6).get("time"));
        assertTrue(String.valueOf(f.get("tip")).contains("确认收款"));

        st = row("status", "confirmed", "confirmedTime", "2026-10-11 08:00:00");
        f = KgOnlineMaterialService.flow("review_passed", null, null, materials(), subs("accepted"), st);
        assertEquals(7, f.get("current"), "结算完成：六步全部打勾");
        assertEquals("已到账", step(f, 6).get("label"));
        assertEquals("done", step(f, 6).get("state"));

        st = row("status", "disputed");
        f = KgOnlineMaterialService.flow("terminated", null, null, materials(), subs("accepted"), st);
        assertEquals(6, f.get("current"), "报名结束但已有结算单：照常走结算");
        assertEquals("申诉中", step(f, 6).get("label"));
        assertEquals(false, f.get("ended"));
    }

    @Test
    void endedApplicationsStopTheBar()
    {
        Map<String, Object> f = KgOnlineMaterialService.flow("withdrawn", null, "10-02 08:00", materials(), null, null);
        assertEquals(0, f.get("current"));
        assertEquals(true, f.get("ended"));
        assertEquals("done", step(f, 3).get("state"));
        assertEquals("off", step(f, 4).get("state"));

        f = KgOnlineMaterialService.flow("review_rejected", "2026-10-01 09:00:00", null, null, null, null);
        assertEquals(2, f.get("current"));
        assertEquals("未通过", step(f, 2).get("label"));
        assertEquals("off", step(f, 3).get("state"));
    }

    @Test
    void acceptedThenWithdrawnOrTerminatedKeepsTheAcceptedStepWithItsDate()
    {
        // 之前只看现在的状态：接过单、后来撤回 / 被终止（还没发素材、没交付）的，「已接单」显示成没走到、也没有日期。
        // view() 传进来的接单时间取自审核通过的记录（10-02 08:00），和报名时间（10-01 09:00）不同 = 接过单
        for (String st : new String[] { "withdrawn", "terminated" })
        {
            Map<String, Object> f = KgOnlineMaterialService.flow(st, "2026-10-01 09:00:00", "10-02 08:00", null, null, null);
            assertEquals(0, f.get("current"), st);
            assertEquals(true, f.get("ended"), st);
            assertEquals("done", step(f, 1).get("state"), st);
            assertEquals("已接单", step(f, 2).get("label"), st);
            assertEquals("done", step(f, 2).get("state"), st);
            assertEquals("10-02", step(f, 2).get("time"), st);
            assertEquals("off", step(f, 3).get("state"), st);
            assertEquals("off", step(f, 4).get("state"), st);
        }
    }

    @Test
    void withdrawnBeforeAcceptanceStillShowsNotAccepted()
    {
        // 没有审核通过的记录时 view() 把接单时间退回成报名时间（同一分钟）：仍按没接单显示
        Map<String, Object> f = KgOnlineMaterialService.flow("withdrawn", "2026-10-01 09:00:00", "10-01 09:00", null, null, null);
        assertEquals("off", step(f, 2).get("state"));
        assertEquals("", step(f, 2).get("time"));
        f = KgOnlineMaterialService.flow("withdrawn", "2026-10-01 09:00:00", null, null, null, null);
        assertEquals("off", step(f, 2).get("state"));
        // 「已终止」只能从审核通过之后走到：即使是没有日志的老数据，也算接过单（日期用退回的报名时间）
        f = KgOnlineMaterialService.flow("terminated", "2026-10-01 09:00:00", "10-01 09:00", null, null, null);
        assertEquals("done", step(f, 2).get("state"));
        assertEquals("10-01", step(f, 2).get("time"));
        // 审核没通过的照旧显示「未通过」（「审核未通过」只能从刚报名时走到，时间对不上也不算接过单）
        for (String taken : new String[] { "10-01 09:00", "10-02 08:00" })
        {
            f = KgOnlineMaterialService.flow("review_rejected", "2026-10-01 09:00:00", taken, null, null, null);
            assertEquals("未通过", step(f, 2).get("label"), taken);
            assertEquals("current", step(f, 2).get("state"), taken);
        }
    }

    @Test
    void acceptanceRecordIsOnlyConsultedOnceTheApplicationHasEnded()
    {
        // 还在等审核的报名不会因为时间对不上就被当成已接单
        Map<String, Object> f = KgOnlineMaterialService.flow("submitted", "2026-10-01 09:00:00", "10-02 08:00", null, null, null);
        assertEquals(1, f.get("current"));
        assertEquals("todo", step(f, 2).get("state"));
        assertEquals("10-02 08:00", KgOnlineMaterialService.minute("2026-10-02 08:00:59"));
        assertEquals("10-02 08:00", KgOnlineMaterialService.minute("10-02 08:00"));
        assertEquals("", KgOnlineMaterialService.minute(null));
    }

    // ---------------------------------------------------------------- 员工端接单详情：能不能发素材

    private KgOnlineTaskService taskService(String appStatus, Map<String, Object> settle)
    {
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(row("jobId", 8L, "jobStore", 3L, "title", "探店笔记", "appUserId", 2L,
                "status", appStatus, "workerStatus", "0", "workerDelFlag", "0", "commissionFen", 5000L, "storeName", "平潭袋鼠小院"));
        when(db.one(anyString(), anyMap())).thenAnswer(i -> String.valueOf((Object) i.getArgument(0)).contains("from kg_online_settlement") ? settle : null);
        when(db.list(anyString(), anyMap())).thenAnswer(i -> {
            String sql = String.valueOf((Object) i.getArgument(0));
            if (sql.contains("from kg_online_material_file"))
                return Collections.singletonList(row("fileId", 9L, "materialId", 1L, "fileName", "原图.zip", "fileExt", "zip", "fileSize", 2048L));
            return sql.contains("from kg_online_material")
                    ? Collections.singletonList(row("materialId", 1L, "content", "素材", "images", "", "sentBy", "小林", "sentTime", "2026-10-03 10:00:00"))
                    : new ArrayList<Map<String, Object>>();
        });
        KgOnlineTaskService s = new KgOnlineTaskService();
        ReflectionTestUtils.setField(s, "db", db);
        return s;
    }

    @Test
    void assignmentTellsStaffWhetherTheyCanSendMaterials()
    {
        ctx.role = "online_staff";
        Map<String, Object> a = taskService("review_passed", null).assignment(ctx, 7L);
        assertEquals(true, a.get("canSendMaterial"));
        assertEquals(1, ((List<?>) a.get("materials")).size());
        assertEquals("小林", ((Map<?, ?>) ((List<?>) a.get("materials")).get(0)).get("sentBy"));
        // V41：随这次素材发出的附件挂在 files 上
        List<?> attached = (List<?>) ((Map<?, ?>) ((List<?>) a.get("materials")).get(0)).get("files");
        assertEquals(1, attached.size());
        assertEquals("原图.zip", ((Map<?, ?>) attached.get(0)).get("name"));
        assertEquals("2 KB", ((Map<?, ?>) attached.get(0)).get("sizeLabel"));
        assertEquals(false, ((Map<?, ?>) attached.get(0)).get("openable"));
        assertEquals(3, ((Map<?, ?>) a.get("flow")).get("current"));

        ctx.role = "staff";  // 普通员工没有「线上任务·编辑」
        assertEquals(false, taskService("review_passed", null).assignment(ctx, 7L).get("canSendMaterial"));

        ctx.role = "manager";
        assertEquals(false, taskService("review_passed", null).assignment(ctx, 7L).get("canSendMaterial"));
        ctx.role = "online_staff"; // 仅有线上编辑权限的角色继续校验结算/撤回/本人限制。
        assertEquals(false, taskService("review_passed", row("status", "confirmed", "amountFen", 5000L)).assignment(ctx, 7L).get("canSendMaterial"));
        assertEquals(true, taskService("review_passed", row("status", "pending_payout", "amountFen", 5000L)).assignment(ctx, 7L).get("canSendMaterial"));
        assertEquals(false, taskService("withdrawn", null).assignment(ctx, 7L).get("canSendMaterial"));
        ctx.me.setUserId(2L);  // 自己接的单
        assertEquals(false, taskService("review_passed", null).assignment(ctx, 7L).get("canSendMaterial"));
    }

    /** 接单人自己的交付页（view），报名已撤回；passTime 是报名日志里第一次「审核通过」的时间，null = 没有这条记录 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withdrawnFlowFromView(String passTime)
    {
        when(db.must(anyString(), anyMap(), anyString())).thenReturn(row("jobId", 8L, "jobStore", 3L, "title", "探店笔记", "appUserId", 2L,
                "status", "withdrawn", "submitTime", "2026-10-01 09:00:00", "workerStatus", "0", "workerDelFlag", "0", "commissionFen", 5000L, "storeName", "平潭袋鼠小院"));
        when(db.one(anyString(), anyMap())).thenAnswer(i -> String.valueOf((Object) i.getArgument(0)).contains("from kg_application_log") ? row("t", passTime) : null);
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<Map<String, Object>>());
        KgOnlineTaskService s = new KgOnlineTaskService();
        ReflectionTestUtils.setField(s, "db", db);
        Map<String, Object> v = ReflectionTestUtils.invokeMethod(s, "view", 7L, false);
        return (Map<String, Object>) v.get("flow");
    }

    @Test
    void deliverPageKeepsAcceptedStepAfterWithdrawal()
    {
        Map<String, Object> f = withdrawnFlowFromView("2026-10-02 08:00:00");
        assertEquals("done", step(f, 2).get("state"));
        assertEquals("10-02", step(f, 2).get("time"));
        // 从没被接单就撤回：仍显示没接单
        f = withdrawnFlowFromView(null);
        assertEquals("off", step(f, 2).get("state"));
        assertEquals("", step(f, 2).get("time"));
    }

    @Test
    void workerViewShowsStoreNameAsSender()
    {
        Map<String, Object> v = ReflectionTestUtils.invokeMethod(taskService("review_passed", null), "view", 7L, false);
        assertEquals("平潭袋鼠小院", ((Map<?, ?>) ((List<?>) v.get("materials")).get(0)).get("sentBy"));
        assertEquals(true, v.get("canSubmit"), "没等素材也照样能提交成果");
    }
}
