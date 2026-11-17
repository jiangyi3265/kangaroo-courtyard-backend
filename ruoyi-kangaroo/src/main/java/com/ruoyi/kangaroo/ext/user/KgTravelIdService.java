package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.util.AccountState;

/**
 * 出行证件（V69，2026-11-03 主人：「订票以后让他提交这个身份证号」）。
 *
 * 录用后报名人填一次证件姓名和身份证号（一人一行，以后的报名都用这一份），换工主理人 / 城市负责人导出到店名单时读它（订票、买保险）。
 * 实名核验那套（KgAccountService.realNameVerify）只存摘要和后四位、还原不出证件号，线上也没开通，所以另存一份：
 *   · 校验口径和实名一样：中文姓名、18 位身份证校验位（KgAccountService.idChecksumOk）；
 *   · 账号已实名的，证件号必须就是实名那张（摘要比对，算法同 KgAccountService.hashId）；别的账号已用这张证件实名的，拒绝；
 *   · 只做格式检查，不算「已实名」，不改 kg_app_user 的任何列；
 *   · 证件号只存 AES-GCM 密文（密钥由 kangaroo.file.sign-secret 派生），后四位另存给页面脱敏展示；注销时整行删除（KgAccountService.deactivate）。
 * 改了 sign-secret 之后旧密文解不开：导出里这一格显示「需重新提交」，页面也提示本人重填，不会报错。
 */
@Service
public class KgTravelIdService
{
    /** 密文前缀：以后换算法时按前缀区分 */
    static final String CIPHER_PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private KgJdbc db;

    @Value("${kangaroo.file.sign-secret:${token.secret}}")
    private String signSecret;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------------- 本人

    /** 本人的出行证件：submitted 有没有交、证件号只给脱敏的；没交时给一个默认姓名（实名姓名 / 最近一次报名填的姓名）方便预填 */
    public Map<String, Object> mine(Long uid)
    {
        Map<String, Object> row = db.one("select id_name, id_no_enc, id_last4, create_time, update_time from kg_user_travel_id where user_id = :u", p("u", uid));
        Map<String, Object> u = db.one("select real_name, real_name_verified_time from kg_app_user where user_id = :u", p("u", uid));
        boolean verified = u != null && u.get("realNameVerifiedTime") != null;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        // 密文解不开（改过 sign-secret）按没交处理，让本人重填
        boolean readable = row != null && decrypt(str(row.get("idNoEnc")), signSecret) != null;
        m.put("submitted", readable);
        m.put("name", readable ? row.get("idName") : null);
        m.put("idMasked", readable ? maskId(null, str(row.get("idLast4"))) : null);
        m.put("updateTime", readable ? (row.get("updateTime") != null ? row.get("updateTime") : row.get("createTime")) : null);
        m.put("needResubmit", row != null && !readable);
        m.put("verified", verified);
        String dft = verified ? str(u.get("realName")) : "";
        if (dft.isEmpty())
        {
            Map<String, Object> a = db.one("select real_name from kg_application where app_user_id = :u and del_flag = '0' and real_name is not null and real_name <> '' "
                    + "order by app_id desc limit 1", p("u", uid));
            dft = a == null ? "" : str(a.get("realName"));
        }
        m.put("defaultName", dft.isEmpty() ? null : dft);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long uid, String name, String idNo)
    {
        AccountState.requireActive(db, uid);
        String n = name == null ? "" : name.trim();
        String id = idNo == null ? "" : idNo.trim().replace(" ", "").toUpperCase();
        if (!validName(n))
        {
            throw new ServiceException("请填写身份证上的中文姓名", 422);
        }
        if (!KgAccountService.idChecksumOk(id))
        {
            throw new ServiceException("身份证号格式或校验位不正确，请核对后再填", 422);
        }
        String hash = hmac(id, signSecret);
        Map<String, Object> u = db.one("select id_hash, real_name_verified_time from kg_app_user where user_id = :u", p("u", uid));
        if (u != null && u.get("realNameVerifiedTime") != null && !str(u.get("idHash")).isEmpty() && !hash.equals(str(u.get("idHash"))))
        {
            throw new ServiceException("账号已完成实名，这里要填实名认证的那张身份证；如需更正请联系客服", 409);
        }
        if (db.one("select user_id from kg_app_user where id_hash = :h and user_id <> :u and del_flag = '0' and real_name_verified_time is not null limit 1",
                p("h", hash, "u", uid)) != null)
        {
            throw new ServiceException("这张身份证已在其他账号完成实名，请用本人证件或联系客服", 409);
        }
        db.update("insert into kg_user_travel_id (user_id, id_name, id_no_enc, id_last4, id_hash, create_time, update_time) "
                + "values (:u, :n, :e, :l, :h, :t, null) on duplicate key update id_name = :n, id_no_enc = :e, id_last4 = :l, id_hash = :h, update_time = :t",
                p("u", uid, "n", n, "e", encrypt(id, signSecret), "l", id.substring(14), "h", hash, "t", new Date()));
        return mine(uid);
    }

    /** 证件姓名：2–16 个汉字，少数民族姓名里的间隔号也算（口径同实名核验） */
    static boolean validName(String n)
    {
        return n != null && n.matches("[\\u4e00-\\u9fa5·]{2,16}");
    }

    // ---------------------------------------------------------------- 导出用

    /** 导出时解出完整证件号；解不开返回 null（调用方写「需重新提交」） */
    public String reveal(String cipher)
    {
        return decrypt(cipher, signSecret);
    }

    /** 页面上的脱敏：前 3 位 + 11 个 * + 后 4 位；只有后四位时前面一律用 * */
    public static String maskId(String full, String last4)
    {
        if (full != null && full.length() == 18)
        {
            return full.substring(0, 3) + "***********" + full.substring(14);
        }
        String tail = last4 == null ? "" : last4;
        return tail.isEmpty() ? "" : "**************" + tail;
    }

    // ---------------------------------------------------------------- 加解密

    /** 与 KgAccountService.hashId 同一算法（HMAC-SHA256，密钥 sign-secret），结果能直接和 kg_app_user.id_hash 比 */
    static String hmac(String idNo, String secret)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(idNo.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : out)
            {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            throw new ServiceException("保存失败，请稍后再试");
        }
    }

    /** AES-128 密钥 = SHA-256("kg-travel-id|" + secret) 的前 16 字节（128 位：Java 8 不装无限强度策略也能用） */
    private static SecretKeySpec key(String secret) throws Exception
    {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(("kg-travel-id|" + secret).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(Arrays.copyOf(d, 16), "AES");
    }

    static String encrypt(String plain, String secret)
    {
        try
        {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(secret), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return CIPHER_PREFIX + Base64.getEncoder().encodeToString(all);
        }
        catch (Exception e)
        {
            throw new ServiceException("保存失败，请稍后再试");
        }
    }

    /** 解不开（不是本算法、密钥变了、被改过）一律返回 null，不抛异常 */
    static String decrypt(String cipher, String secret)
    {
        if (cipher == null || !cipher.startsWith(CIPHER_PREFIX) || secret == null)
        {
            return null;
        }
        try
        {
            byte[] all = Base64.getDecoder().decode(cipher.substring(CIPHER_PREFIX.length()));
            if (all.length <= IV_BYTES)
            {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(secret), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
