package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialFiles;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 私信放宽（V46，2026-10-16）：文字 2000 字；门店会话里发文件（一条消息一个，和消息同一事务落盘、登记），
 * 消息列表里是 type = file + 文件信息、旧版本看到「[文件] 名称，请升级小程序查看」；下载、复制链接每次按当前身份重新核对。
 * 真实 service + Mock JDBC + 临时目录里的真实文件。
 */
class KgChatFileMessageTest
{
    static final byte[] PDF = "%PDF-1.7\n%报名材料".getBytes(StandardCharsets.UTF_8);
    static final byte[] ZIP = { 0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00 };

    @TempDir
    Path folder;
    String original;
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgChatReception reception = mock(KgChatReception.class);
    final KgChatFiles files = new KgChatFiles("0123456789abcdef-chat-secret");
    final KgChatService service = new KgChatService();
    final String key = "m0123456789abcdef";
    final Map<String, Object> store = p("convId", 12L, "convType", "store", "status", "active", "title", "海边小院", "storeId", 22L,
            "pairKey", "o22-u7", "source", "online_task", "sourceId", 300L);

    @BeforeEach void setup()
    {
        when(db.one(startsWith("select config_value from sys_config"), anyMap())).thenReturn(p("configValue", "true"));
        original = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
        ReflectionTestUtils.setField(files, "materialFiles", new KgOnlineMaterialFiles("0123456789abcdef-chat-secret"));
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "grants", mock(IKgStaffGrantService.class));
        ReflectionTestUtils.setField(service, "reception", reception);
        ReflectionTestUtils.setField(service, "files", files);
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.one(startsWith("select user_id, nickname"), anyMap())).thenReturn(p("userId", 7L, "nickname", "小林"));
        when(db.one(startsWith("select * from kg_chat_member"), anyMap())).thenReturn(p("role", "member", "muted", "0"));
        when(db.one(startsWith("select j.title from kg_application"), anyMap())).thenReturn(p("title", "民宿文案"));
        conv(store);
        when(reception.isOnline(anyMap())).thenReturn(true);
        when(reception.staff(eq(22L), anyBoolean())).thenReturn(Arrays.asList(8L));
        when(db.claimIdem(anyString(), anyString())).thenReturn(true);
        when(db.insert(eq("kg_chat_msg"), anyMap())).thenReturn(73L);
        when(db.update(startsWith("insert into kg_chat_file"), anyMap())).thenReturn(1);
        when(db.update(startsWith("update kg_idempotency set biz_id"), anyMap())).thenReturn(1);
        when(db.list(startsWith("select app_user_id, role from kg_chat_member"), anyMap())).thenReturn(Arrays.asList(p("appUserId", 8L, "role", "store")));
    }

    @AfterEach void restore()
    {
        new RuoYiConfig().setProfile(original);
    }

    void conv(Map<String, Object> c)
    {
        when(db.one(startsWith("select conv_type,store_id from kg_chat_conv"), anyMap())).thenReturn(c);
        when(db.one(startsWith("select conv_id,conv_type,pair_key,source,store_id from kg_chat_conv"), anyMap())).thenReturn(c);
        when(db.one(startsWith("select c.conv_type, c.store_id"), anyMap())).thenReturn(c);
        when(db.one(startsWith("select conv_type from kg_chat_conv"), anyMap())).thenReturn(c);
        when(db.must(startsWith("select * from kg_chat_conv"), anyMap(), anyString())).thenReturn(c);
    }

    MockMultipartFile pdf()
    {
        return new MockMultipartFile("file", "wxfile_tmp_1.pdf", "application/octet-stream", PDF);
    }

    long storedFiles() throws Exception
    {
        Path dir = folder.resolve("kg-private");
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir))
        {
            return s.filter(Files::isRegularFile).count();
        }
    }

    // ---------------------------------------------------------------- 文字 2000 字

    @Test void textUpTo2000CharactersIsAcceptedAnd2001Rejected()
    {
        assertEquals(2000, KgChatService.TEXT_MAX);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 2000; i++) s.append('字');
        assertEquals(73L, service.send(7L, 12L, s.toString()).get("msgId"));
        verify(db).insert(eq("kg_chat_msg"), argThat(m -> String.valueOf(m.get("content")).length() == 2000));
        ServiceException e = assertThrows(ServiceException.class, () -> service.send(7L, 12L, s + "多"));
        assertEquals(422, e.getCode());
        assertEquals("消息为 1–2000 字", e.getMessage());
        // 活动群同一个上限（群公告仍是 500 字）
        conv(p("convId", 12L, "convType", "group", "status", "active", "title", "扎染 活动群"));
        assertEquals(73L, service.send(7L, 12L, s.toString()).get("msgId"));
    }

    // ---------------------------------------------------------------- 发文件

    @Test void storeConversationFileIsStoredAndRegisteredWithTheMessage() throws Exception
    {
        assertEquals(73L, service.sendFile(7L, 12L, key, pdf(), "报名材料.pdf").get("msgId"));
        org.mockito.InOrder o = inOrder(db);
        o.verify(db).insert(eq("kg_chat_msg"), argThat(m -> "[文件] 报名材料.pdf".equals(m.get("content")) && "user".equals(m.get("sender_role"))));
        o.verify(db).update(startsWith("insert into kg_chat_file"), argThat(m -> Long.valueOf(73).equals(m.get("m")) && Long.valueOf(12).equals(m.get("c"))
                && Long.valueOf(7).equals(m.get("u")) && "报名材料.pdf".equals(m.get("n")) && "pdf".equals(m.get("e"))
                && Long.valueOf(PDF.length).equals(m.get("s")) && String.valueOf(m.get("h")).matches("[0-9a-f]{64}")
                && String.valueOf(m.get("k")).matches("chat/12/[0-9a-f]{32}\\.pdf")));
        o.verify(db).update(startsWith("update kg_idempotency set biz_id"), anyMap());
        assertEquals(1, storedFiles());
        // 会话列表、站内信：「[文件] 名称」
        verify(db).update(startsWith("update kg_chat_conv set last_msg"), argThat(m -> "[文件] 报名材料.pdf".equals(m.get("m"))));
        verify(db).update(startsWith("insert into kg_message"), argThat(m -> Long.valueOf(8).equals(m.get("u")) && "[文件] 报名材料.pdf".equals(m.get("b"))));
    }

    @Test void privateChatsAndGroupsCannotSendFilesAndNothingIsWritten() throws Exception
    {
        for (String type : Arrays.asList("private", "group"))
        {
            conv(p("convId", 12L, "convType", type, "status", "active", "title", "x", "pairKey", "7-9"));
            ServiceException e = assertThrows(ServiceException.class, () -> service.sendFile(7L, 12L, key, pdf(), "报名材料.pdf"));
            assertEquals(422, e.getCode());
            assertEquals("只有和门店的沟通里可以发文件", e.getMessage());
        }
        verify(db, never()).insert(anyString(), anyMap());
        assertEquals(0, storedFiles());
    }

    @Test void missingKeyWrongTypeOrClosedConversationNeverStoreTheFile() throws Exception
    {
        assertEquals(422, assertThrows(ServiceException.class, () -> service.sendFile(7L, 12L, null, pdf(), "报名材料.pdf")).getCode());
        assertEquals(422, assertThrows(ServiceException.class,
                () -> service.sendFile(7L, 12L, key, new MockMultipartFile("file", "a.exe", null, new byte[] { 0x4D, 0x5A, 0, 0, 0, 0, 0, 0 }), "a.exe")).getCode());
        conv(p("convId", 12L, "convType", "store", "status", "closed", "title", "海边小院", "storeId", 22L, "pairKey", "o22-u7", "source", "online_task"));
        assertEquals(409, assertThrows(ServiceException.class, () -> service.sendFile(7L, 12L, key, pdf(), "报名材料.pdf")).getCode());
        verify(db, never()).insert(anyString(), anyMap());
        assertEquals(0, storedFiles());
    }

    void duplicate(String fileSha, String content)
    {
        when(db.claimIdem(anyString(), anyString())).thenReturn(false);
        when(db.one(startsWith("select biz_type, biz_id"), anyMap())).thenReturn(p("bizType", "chat_message", "bizId", 73L));
        when(db.one(startsWith("select msg_id, conv_id"), anyMap())).thenReturn(p("msgId", 73L, "convId", 12L, "senderId", 7L, "content", content, "fileSha", fileSha));
    }

    @Test void retryWithTheSameFileReturnsTheOriginalMessageAndDifferentFileConflicts() throws Exception
    {
        String sha = files.check(pdf(), "报名材料.pdf").sha;
        duplicate(sha, "[文件] 报名材料.pdf");
        assertEquals(73L, service.sendFile(7L, 12L, key, pdf(), "报名材料.pdf").get("msgId"));
        verify(db, never()).insert(anyString(), anyMap());
        verify(db).one(argThat(q -> q.startsWith("select msg_id, conv_id") && q.contains("(select f.input_sha from kg_chat_file f where f.msg_id=kg_chat_msg.msg_id) file_sha")), anyMap());
        duplicate("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "[文件] 报名材料.pdf");
        assertEquals(409, assertThrows(ServiceException.class, () -> service.sendFile(7L, 12L, key, pdf(), "报名材料.pdf")).getCode());
        // 同一个标识先发过文件，再拿来发同样文字的消息也不行
        duplicate(sha, "[文件] 报名材料.pdf");
        assertEquals(409, assertThrows(ServiceException.class, () -> service.send(7L, 12L, "[文件] 报名材料.pdf", key)).getCode());
        assertEquals(0, storedFiles());
    }

    @Test void dailyQuotaPerSenderIsEnforcedBeforeWritingToDisk() throws Exception
    {
        when(db.count(startsWith("select coalesce(sum(file_size), 0) from kg_chat_file"), anyMap())).thenReturn(KgChatFiles.MAX_BYTES_PER_SENDER_DAY - 3);
        ServiceException e = assertThrows(ServiceException.class, () -> service.sendFile(7L, 12L, key, pdf(), "报名材料.pdf"));
        assertEquals(429, e.getCode());
        verify(db, never()).insert(anyString(), anyMap());
        assertEquals(0, storedFiles());
    }

    @Test void registrationFailureDeletesTheFileAndRollsBack() throws Exception
    {
        when(db.update(startsWith("insert into kg_chat_file"), anyMap())).thenReturn(0);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        TransactionStatus tx = mock(TransactionStatus.class);
        when(tm.getTransaction(any())).thenReturn(tx);
        ProxyFactory pf = new ProxyFactory(service);
        pf.setProxyTargetClass(true);
        pf.addAdvice(new TransactionInterceptor(tm, new AnnotationTransactionAttributeSource()));
        assertEquals(503, assertThrows(ServiceException.class, () -> ((KgChatService) pf.getProxy()).sendFile(7L, 12L, key, pdf(), "报名材料.pdf")).getCode());
        verify(tm).rollback(tx);
        verify(tm, never()).commit(any());
        assertEquals(0, storedFiles());
        verify(db, never()).update(startsWith("update kg_idempotency set biz_id"), anyMap());
    }

    // ---------------------------------------------------------------- 消息列表

    @Test void fileMessagesCarryFileInfoAndOldClientsSeeAnUpgradeHint()
    {
        when(db.must(startsWith("select conv_id, conv_type from kg_chat_conv"), anyMap(), anyString())).thenReturn(p("convId", 12L, "convType", "store"));
        when(db.list(startsWith("select * from (select m.*"), anyMap())).thenReturn(Arrays.asList(
                p("msgId", 73L, "senderId", 8L, "senderRole", "store", "nickname", "阿川", "content", "[文件] 合同.docx", "status", "visible",
                        "fileName", "合同.docx", "fileExt", "docx", "fileSize", 2048L, "createTime", new Date()),
                // 发送人账号已停用：查询里关联不到文件信息，只剩文字
                p("msgId", 74L, "senderId", 9L, "senderRole", "user", "nickname", null, "content", "[文件] 作品集.zip", "status", "visible", "createTime", new Date())));
        when(db.must(startsWith("select last_read_id"), anyMap(), anyString())).thenReturn(p("lastReadId", 74L));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.messages(7L, 12L, 0L).get("rows");
        assertEquals("file", rows.get(0).get("type"));
        assertEquals("[文件] 合同.docx，请升级小程序查看", rows.get(0).get("content"));
        assertEquals(p("name", "合同.docx", "size", 2048L, "sizeLabel", "2 KB", "ext", "docx", "kind", "doc", "openable", true), rows.get(0).get("file"));
        assertEquals("text", rows.get(1).get("type"));
        assertEquals("[文件] 作品集.zip", rows.get(1).get("content"));
        assertNull(rows.get(1).get("file"));
        verify(db).list(argThat(q -> q.contains("left join kg_chat_file f on f.msg_id=m.msg_id and u.status='0' and u.del_flag='0'")
                && q.contains("f.file_name, f.file_ext, f.file_size")), anyMap());
    }

    // ---------------------------------------------------------------- 下载 / 复制链接

    @Test void downloadRechecksMembershipVisibilitySenderAndBlocking()
    {
        when(db.one(startsWith("select m.sender_id, f.file_key"), anyMap())).thenReturn(null);
        assertEquals(404, assertThrows(ServiceException.class, () -> service.file(7L, 12L, 73L)).getCode());
        verify(db).one(argThat(q -> q.startsWith("select m.sender_id, f.file_key") && q.contains("u.status = '0'") && q.contains("u.del_flag = '0'")
                && q.contains("m.status = 'visible'") && q.contains("m.conv_id = :c")), eq(p("m", 73L, "c", 12L)));
        when(db.one(startsWith("select m.sender_id, f.file_key"), anyMap())).thenReturn(p("senderId", 8L, "fileKey", "chat/12/x.pdf", "fileName", "a.pdf", "fileExt", "pdf"));
        assertEquals("chat/12/x.pdf", service.file(7L, 12L, 73L).get("fileKey"));
        doThrow(new ServiceException("blocked", 403)).when(guard).requireNotBlocked(7L, 8L);
        assertEquals(403, assertThrows(ServiceException.class, () -> service.file(7L, 12L, 73L)).getCode());
        // 不在会话里的人
        when(db.one(startsWith("select * from kg_chat_member"), anyMap())).thenReturn(null);
        assertEquals(403, assertThrows(ServiceException.class, () -> service.file(11L, 12L, 73L)).getCode());
    }

    @Test void copiedLinkIsSignedForTheCopierAndRecheckedForThatPerson()
    {
        when(db.one(startsWith("select m.sender_id, f.file_key"), anyMap())).thenReturn(p("senderId", 8L, "fileKey", "chat/12/x.pdf", "fileName", "a.pdf", "fileExt", "pdf"));
        Map<String, Object> link = service.fileLink(7L, 12L, 73L);
        String url = String.valueOf(link.get("url"));
        assertTrue(url.startsWith("/kangaroo/file/chat?m=73&u=7&e="), url);
        assertEquals(72, link.get("hours"));
        long exp = Long.parseLong(url.substring(url.indexOf("&e=") + 3, url.indexOf("&s=")));
        String sig = url.substring(url.indexOf("&s=") + 3);
        when(db.one(startsWith("select conv_id from kg_chat_msg"), anyMap())).thenReturn(p("convId", 12L));
        assertEquals("chat/12/x.pdf", service.signedFile(73L, 7L, exp, sig).get("fileKey"));
        assertNull(service.signedFile(73L, 8L, exp, sig), "换成别人不认");
        assertNull(service.signedFile(74L, 7L, exp, sig));
        // 复制链接的人后来被拉黑 / 不在会话里：链接随之失效
        doThrow(new ServiceException("blocked", 403)).when(guard).requireNotBlocked(7L, 8L);
        assertThrows(ServiceException.class, () -> service.signedFile(73L, 7L, exp, sig));
    }
}
