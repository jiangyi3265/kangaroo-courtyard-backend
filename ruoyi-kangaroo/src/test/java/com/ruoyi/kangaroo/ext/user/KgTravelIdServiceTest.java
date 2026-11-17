package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 2026-11-03 出行证件（V69）：证件号只存密文、校验口径同实名、已实名账号只能填实名那张 */
class KgTravelIdServiceTest
{
    static final String SECRET = "unit-test-secret";
    /** 校验位正确的测试号（GB 11643） */
    static final String ID = "11010519491231002X";

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static KgTravelIdService service(KgJdbc db)
    {
        KgTravelIdService s = new KgTravelIdService();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "signSecret", SECRET);
        return s;
    }

    @Test
    void cipherRoundTripsAndNeverContainsThePlainNumber()
    {
        assertTrue(KgAccountService.idChecksumOk(ID), "测试号本身要过校验位");
        String c1 = KgTravelIdService.encrypt(ID, SECRET);
        String c2 = KgTravelIdService.encrypt(ID, SECRET);
        assertTrue(c1.startsWith("v1:"));
        assertNotEquals(c1, c2, "每次随机 IV，同一个号密文不同");
        assertFalse(c1.contains(ID) && c1.contains("1101"), "密文里看不出证件号");
        assertEquals(ID, KgTravelIdService.decrypt(c1, SECRET));
        assertEquals(ID, KgTravelIdService.decrypt(c2, SECRET));
    }

    @Test
    void wrongSecretOrTamperedCipherDecryptsToNullInsteadOfThrowing()
    {
        String c = KgTravelIdService.encrypt(ID, SECRET);
        assertNull(KgTravelIdService.decrypt(c, "other-secret"));
        // 改密文中间一个字符（GCM 认证不过）
        int i = 30;
        String tampered = c.substring(0, i) + (c.charAt(i) == 'A' ? 'B' : 'A') + c.substring(i + 1);
        assertNull(KgTravelIdService.decrypt(tampered, SECRET));
        assertNull(KgTravelIdService.decrypt("plain:" + ID, SECRET));
        assertNull(KgTravelIdService.decrypt("v1:", SECRET));
        assertNull(KgTravelIdService.decrypt(null, SECRET));
    }

    @Test
    void masksKeepOnlyHeadAndTail()
    {
        assertEquals("110***********002X", KgTravelIdService.maskId(ID, null));
        assertEquals("**************002X", KgTravelIdService.maskId(null, "002X"));
        assertEquals("", KgTravelIdService.maskId(null, null));
        assertTrue(KgTravelIdService.validName("张三"));
        assertTrue(KgTravelIdService.validName("阿依古丽·买买提"));
        assertFalse(KgTravelIdService.validName("Zhang San"));
        assertFalse(KgTravelIdService.validName("张"));
    }

    @Test
    void savesCipherLast4AndHashButNeverThePlainNumber()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(contains("from kg_app_user where user_id=:u for update"), anyMap())).thenReturn(row("status", "0", "delFlag", "0"));
        when(db.one(contains("select id_hash, real_name_verified_time from kg_app_user"), anyMap())).thenReturn(row("idHash", null, "realNameVerifiedTime", null));
        // 没有别的账号用这张证件实名（mock 默认给空 Map，这里明确给 null = 查无此行）
        when(db.one(contains("where id_hash = :h and user_id <> :u"), anyMap())).thenReturn(null);
        KgTravelIdService s = service(db);
        s.save(7L, " 张三 ", "110105 19491231002x");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> p = ArgumentCaptor.forClass(Map.class);
        verify(db).update(contains("insert into kg_user_travel_id"), p.capture());
        Map<String, Object> v = p.getValue();
        assertEquals(7L, v.get("u"));
        assertEquals("张三", v.get("n"));
        assertEquals("002X", v.get("l"), "末位 x 统一大写");
        assertEquals(KgTravelIdService.hmac(ID, SECRET), v.get("h"));
        assertFalse(String.valueOf(v.get("e")).contains(ID));
        assertEquals(ID, KgTravelIdService.decrypt(String.valueOf(v.get("e")), SECRET));
        for (Object o : v.values())
        {
            assertNotEquals(ID, o, "明文证件号不进任何一列");
        }
    }

    @Test
    void rejectsBadNameAndBadChecksum()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(contains("for update"), anyMap())).thenReturn(row("status", "0", "delFlag", "0"));
        KgTravelIdService s = service(db);
        ServiceException e = assertThrows(ServiceException.class, () -> s.save(7L, "Tom", ID));
        assertEquals(422, e.getCode());
        e = assertThrows(ServiceException.class, () -> s.save(7L, "张三", "110105194912310021"));
        assertEquals(422, e.getCode());
        verify(db, never()).update(contains("kg_user_travel_id"), anyMap());
    }

    @Test
    void verifiedAccountCanOnlySubmitItsOwnVerifiedId()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(contains("for update"), anyMap())).thenReturn(row("status", "0", "delFlag", "0"));
        when(db.one(contains("select id_hash, real_name_verified_time from kg_app_user"), anyMap()))
                .thenReturn(row("idHash", "someone-else", "realNameVerifiedTime", new java.util.Date()));
        ServiceException e = assertThrows(ServiceException.class, () -> service(db).save(7L, "张三", ID));
        assertEquals(409, e.getCode());
        verify(db, never()).update(contains("kg_user_travel_id"), anyMap());
    }

    @Test
    void idAlreadyVerifiedOnAnotherAccountIsRefused()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(contains("for update"), anyMap())).thenReturn(row("status", "0", "delFlag", "0"));
        when(db.one(contains("select id_hash, real_name_verified_time from kg_app_user"), anyMap())).thenReturn(row());
        when(db.one(contains("where id_hash = :h and user_id <> :u"), anyMap())).thenReturn(row("userId", 9L));
        ServiceException e = assertThrows(ServiceException.class, () -> service(db).save(7L, "张三", ID));
        assertEquals(409, e.getCode());
    }

    @Test
    void mineShowsOnlyTheMaskedNumberAndAsksToResubmitUnreadableCipher()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(contains("from kg_user_travel_id"), anyMap())).thenReturn(row("idName", "张三", "idNoEnc", KgTravelIdService.encrypt(ID, SECRET), "idLast4", "002X"));
        when(db.one(contains("select real_name, real_name_verified_time"), anyMap())).thenReturn(row());
        Map<String, Object> m = service(db).mine(7L);
        assertEquals(Boolean.TRUE, m.get("submitted"));
        assertEquals("**************002X", m.get("idMasked"));
        assertFalse(m.toString().contains(ID));

        KgJdbc broken = mock(KgJdbc.class);
        when(broken.one(contains("from kg_user_travel_id"), anyMap())).thenReturn(row("idName", "张三", "idNoEnc", KgTravelIdService.encrypt(ID, "old-secret"), "idLast4", "002X"));
        when(broken.one(contains("from kg_application"), anyMap())).thenReturn(row("realName", "张三"));
        Map<String, Object> r = service(broken).mine(7L);
        assertEquals(Boolean.FALSE, r.get("submitted"));
        assertEquals(Boolean.TRUE, r.get("needResubmit"));
        assertEquals("张三", r.get("defaultName"), "没实名时用最近一次报名的姓名预填");
    }

    @Test
    void deactivationDeletesTheRow() throws Exception
    {
        String src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(System.getProperty("basedir", "."),
                "src/main/java/com/ruoyi/kangaroo/ext/user/KgAccountService.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("\"delete from kg_user_travel_id where user_id = :u\""));
    }
}
