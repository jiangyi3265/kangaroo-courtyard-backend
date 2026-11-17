package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.WxMiniAppClient;

/**
 * 2026-11-03 主人：「出示二维码失效，生成失败」。活动签到码单独一个服务（不改 KgActivityService）：
 * <ul>
 *   <li>微信小程序码按前端版本（正式 / 体验 / 开发）生成，见 {@link WxMiniAppClient#activityCode(Long, String, String)}；</li>
 *   <li>微信那边还是生成不出来时，给一张普通二维码（服务端 zxing 画，内容带活动签到参数，参与者在小程序报名凭证页「现场签到 → 扫码」扫）
 *       和一个 6 位「活动签到码」（参与者自己输入），组织者不会卡住。</li>
 * </ul>
 * 6 位码由活动的 checkin_token 算出来，不存库、不加迁移；换 token 就换码。调用方先做好组织者 / 门店授权。
 */
@Service
public class KgActivityCheckinCodes
{
    /** 普通二维码里的固定前缀：用微信「扫一扫」扫到也能看懂这是什么、该去哪扫 */
    public static final String PLAIN_PREFIX = "袋鼠全球青年活动签到：请在袋鼠全球青年小程序「报名凭证 → 现场签到」里扫码 ";

    @Autowired
    private KgJdbc db;

    @Autowired
    private WxMiniAppClient wxMiniApp;

    /** 组织者「出示签到二维码」：微信小程序码 + 兜底普通二维码 + 6 位活动签到码 */
    public Map<String, Object> organizerCode(Map<String, Object> activity, String clientEnv)
    {
        Long id = KgJdbc.asLong(activity.get("activityId"));
        String token = ensureToken(activity);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("code", wxMiniApp.activityCode(id, token, clientEnv));
        out.put("pin", pin(token));
        String text = plainText(id, token);
        out.put("plainText", text);
        String img = text == null ? null : qrDataUri(text);
        out.put("plainQr", img == null ? "" : img);
        return out;
    }

    /** 参与者输入 6 位活动签到码：时间窗口和已确认报名的规则同扫码签到（KgActivityService.selfCheckin） */
    @Transactional(rollbackFor = Exception.class)
    public String selfCheckinByPin(Long me, Long activityId, String input)
    {
        String code = input == null ? "" : input.trim();
        if (!code.matches("\\d{6}")) throw new ServiceException("活动签到码是 6 位数字", 422);
        Map<String, Object> a = db.one("select activity_id, status, start_time, end_time, checkin_token from kg_activity where activity_id = :a and del_flag = '0' for update",
                p("a", activityId));
        if (a == null) throw new ServiceException("活动不存在", 404);
        String token = KgJdbc.asStr(a.get("checkinToken"));
        if (token == null || token.isEmpty() || !code.equals(pin(token)))
            throw new ServiceException("签到码不对，请核对领队现场给的 6 位数字（不是你自己报名凭证上的签到码）", 422);
        if (!KgActivityService.inCheckinWindow(a)) return KgActivityService.checkinWindowTip(a);
        int n = db.update("update kg_activity_signup set checkin_time = now(), update_time = now() where activity_id = :a and app_user_id = :u "
                + "and status = 'confirmed' and checkin_time is null", p("a", activityId, "u", me));
        if (n > 0) return "签到成功";
        return db.count("select count(*) from kg_activity_signup where activity_id = :a and app_user_id = :u and checkin_time is not null",
                p("a", activityId, "u", me)) > 0 ? "你已签到" : "你没有已确认的报名，无法签到";
    }

    /** 由 checkin_token（12 或 16 位十六进制）算出 6 位数字；格式不对返回 null */
    public static String pin(String token)
    {
        if (token == null || !token.matches("(?:[0-9a-f]{12}|[0-9a-f]{16})")) return null;
        long v = Long.parseLong(token.substring(0, 12), 16) ^ (token.length() > 12 ? Long.parseLong(token.substring(12), 16) << 20 : 0L);
        return String.format("%06d", Math.floorMod(v * 2654435761L, 1_000_000L));
    }

    /** 普通二维码内容：前缀 + 场景串 c_<id36>_<token>（和小程序码的 scene 同一格式，参与者端同一套解析） */
    public static String plainText(Long id, String token)
    {
        if (id == null || id <= 0 || pin(token) == null) return null;
        return PLAIN_PREFIX + "c_" + Long.toString(id, 36) + "_" + token;
    }

    /** 普通二维码 PNG（data URI）。和 StayCodes 一样用 zxing core，但内容有中文，要声明 UTF-8；画不出来返回 null，页面上仍有 6 位码 */
    static String qrDataUri(String text)
    {
        try
        {
            Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            BitMatrix m = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480, hints);
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < m.getWidth(); x++)
                for (int y = 0; y < m.getHeight(); y++)
                    img.setRGB(x, y, m.get(x, y) ? 0x241B17 : 0xFFFFFF);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** 同 KgActivityService.ensureToken：老活动没有 token 时补一个（只在为空时写，并发下以库里为准） */
    private String ensureToken(Map<String, Object> a)
    {
        String t = KgJdbc.asStr(a.get("checkinToken"));
        if (t == null || t.isEmpty())
        {
            t = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            db.update("update kg_activity set checkin_token = :t where activity_id = :a and (checkin_token is null or checkin_token = '')", p("t", t, "a", a.get("activityId")));
            Map<String, Object> row = db.one("select checkin_token from kg_activity where activity_id = :a", p("a", a.get("activityId")));
            t = row == null ? t : KgJdbc.asStr(row.get("checkinToken"));
        }
        return t;
    }
}
