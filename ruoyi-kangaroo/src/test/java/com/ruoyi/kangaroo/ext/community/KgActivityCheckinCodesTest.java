package com.ruoyi.kangaroo.ext.community;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppActivityCheckinCodeController;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.WxMiniAppClient;

/** 2026-11-03 主人：签到二维码生成失败 → 微信码按版本生成 + 普通二维码 / 6 位活动签到码兜底 */
class KgActivityCheckinCodesTest
{
    private static final String TOKEN = "0123456789abcdef";

    private KgActivityCheckinCodes service(KgJdbc db, WxMiniAppClient wx)
    {
        KgActivityCheckinCodes s = new KgActivityCheckinCodes();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "wxMiniApp", wx);
        return s;
    }

    @Test
    void pinIsStableSixDigitsAndRejectsBadTokens()
    {
        String a = KgActivityCheckinCodes.pin(TOKEN);
        assertTrue(a.matches("\\d{6}"));
        assertEquals(a, KgActivityCheckinCodes.pin(TOKEN));
        assertTrue(KgActivityCheckinCodes.pin("0123456789ab").matches("\\d{6}"));
        for (String bad : new String[] { null, "", "xyz", "0123456789ABCDEF", "0123456789abcde" }) assertNull(KgActivityCheckinCodes.pin(bad));
        Set<String> seen = new HashSet<String>();
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) seen.add(KgActivityCheckinCodes.pin(String.format("%016x", r.nextLong())));
        assertTrue(seen.size() > 1990, "不同活动的码基本不重复");
    }

    @Test
    void plainQrDecodesToTheSameSceneTheMiniProgramParses() throws Exception
    {
        String text = KgActivityCheckinCodes.plainText(Long.MAX_VALUE, TOKEN);
        assertTrue(text.endsWith(" c_1y2p0ij32e8e7_" + TOKEN));
        assertNull(KgActivityCheckinCodes.plainText(0L, TOKEN));
        assertNull(KgActivityCheckinCodes.plainText(1L, "bad"));
        String uri = KgActivityCheckinCodes.qrDataUri(text);
        assertTrue(uri.startsWith("data:image/png;base64,"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1))));
        int[] px = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
        String decoded = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(img.getWidth(), img.getHeight(), px))), hints).getText();
        assertEquals(text, decoded, "中文前缀要按 UTF-8 编进去，扫出来不能是问号");
    }

    @Test
    void organizerCodePassesClientEnvAndAlwaysCarriesFallback()
    {
        KgJdbc db = mock(KgJdbc.class);
        WxMiniAppClient wx = mock(WxMiniAppClient.class);
        when(wx.activityCode(12L, TOKEN, "trial")).thenReturn(KgJdbc.p("state", "error", "image", "", "message", "x"));
        Map<String, Object> out = service(db, wx).organizerCode(KgJdbc.p("activityId", 12L, "checkinToken", TOKEN), "trial");
        verify(wx).activityCode(12L, TOKEN, "trial");
        assertEquals(KgActivityCheckinCodes.pin(TOKEN), out.get("pin"));
        assertTrue(out.get("plainQr").toString().startsWith("data:image/png;base64,"));
        assertTrue(out.get("plainText").toString().endsWith("c_c_" + TOKEN));
        verify(db, never()).update(anyString(), anyMap());
    }

    private Map<String, Object> act(String status, long startOffsetMs)
    {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long now = System.currentTimeMillis();
        return KgJdbc.p("activityId", 12L, "status", status, "checkinToken", TOKEN,
                "startTime", f.format(new Date(now + startOffsetMs)), "endTime", f.format(new Date(now + startOffsetMs + 3 * 3600_000L)));
    }

    @Test
    void pinCheckinFollowsSameRulesAsScan()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgActivityCheckinCodes s = service(db, mock(WxMiniAppClient.class));
        String pin = KgActivityCheckinCodes.pin(TOKEN);
        assertEquals(422, assertThrows(ServiceException.class, () -> s.selfCheckinByPin(7L, 12L, "12ab")).getCode());
        when(db.one(anyString(), anyMap())).thenReturn(act("open", 600_000L));
        String wrong = pin.equals("000000") ? "000001" : "000000";
        assertEquals(422, assertThrows(ServiceException.class, () -> s.selfCheckinByPin(7L, 12L, wrong)).getCode());
        verify(db, never()).update(anyString(), anyMap());
        when(db.update(anyString(), anyMap())).thenReturn(1);
        assertEquals("签到成功", s.selfCheckinByPin(7L, 12L, " " + pin + " "));
        when(db.update(anyString(), anyMap())).thenReturn(0);
        when(db.count(anyString(), anyMap())).thenReturn(1L);
        assertEquals("你已签到", s.selfCheckinByPin(7L, 12L, pin));
        when(db.one(anyString(), anyMap())).thenReturn(act("open", 5 * 3600_000L));
        assertTrue(s.selfCheckinByPin(7L, 12L, pin).startsWith("签到尚未开放，将于 "));
        when(db.one(anyString(), anyMap())).thenReturn(act("cancelled", 0L));
        assertTrue(s.selfCheckinByPin(7L, 12L, pin).contains("已取消"));
    }

    @Test void pinCheckinNamesTheExactOpeningTimeAndLocksCurrentActivity() {
        KgJdbc db=mock(KgJdbc.class);KgActivityCheckinCodes s=service(db,mock(WxMiniAppClient.class));
        Map<String,Object>a=act("open",5*3600_000L);when(db.one(anyString(),anyMap())).thenReturn(a);
        assertEquals(KgActivityService.checkinWindowTip(a),s.selfCheckinByPin(7L,12L,KgActivityCheckinCodes.pin(TOKEN)));
        verify(db).one(contains("for update"),anyMap());verify(db,never()).update(anyString(),anyMap());
    }
    @Test void pinCheckinRejectsEndedActivityInsideOriginalWindow() {
        KgJdbc db=mock(KgJdbc.class);when(db.one(anyString(),anyMap())).thenReturn(act("ended",-600000L));
        when(db.update(anyString(),anyMap())).thenReturn(1);
        assertTrue(service(db,mock(WxMiniAppClient.class)).selfCheckinByPin(7L,12L,KgActivityCheckinCodes.pin(TOKEN)).contains("已结束"));
        verify(db,never()).update(anyString(),anyMap());
    }

    @Test
    void controllerAuthorizesBeforeCodeAndRateLimitsPins()
    {
        AppActivityCheckinCodeController c = new AppActivityCheckinCodeController();
        KgJdbc db = mock(KgJdbc.class);
        StaffSupport support = mock(StaffSupport.class);
        KgActivityService activities = mock(KgActivityService.class);
        KgActivityCheckinCodes codes = mock(KgActivityCheckinCodes.class);
        AppRateLimiter limiter = mock(AppRateLimiter.class);
        ReflectionTestUtils.setField(c, "db", db);
        ReflectionTestUtils.setField(c, "support", support);
        ReflectionTestUtils.setField(c, "activities", activities);
        ReflectionTestUtils.setField(c, "codes", codes);
        ReflectionTestUtils.setField(c, "limiter", limiter);
        when(db.one(anyString(), anyMap())).thenReturn(KgJdbc.p("storeId", 99L));
        when(support.ctx(99L)).thenThrow(new ServiceException("无权", 403));
        assertEquals(403, assertThrows(ServiceException.class, () -> c.staffCode(12L, "trial")).getCode());
        verifyNoInteractions(codes);
        when(db.one(anyString(), anyMap())).thenReturn(null);
        assertEquals(404, assertThrows(ServiceException.class, () -> c.staffCode(12L, "trial")).getCode());
        com.ruoyi.kangaroo.security.AppLoginUser me = new com.ruoyi.kangaroo.security.AppLoginUser();
        me.setUserId(7L);
        com.ruoyi.kangaroo.security.AppUserContext.set(me);
        try
        {
            doThrow(new ServiceException("太多", 429)).when(limiter).check(eq("activity:checkin-pin"), eq("7:12"), anyInt(), anyInt(), anyString());
            assertThrows(ServiceException.class, () -> c.checkinPin(12L, KgJdbc.p("code", "123456")));
            verifyNoInteractions(codes);
        }
        finally
        {
            com.ruoyi.kangaroo.security.AppUserContext.remove();
        }
    }
}
