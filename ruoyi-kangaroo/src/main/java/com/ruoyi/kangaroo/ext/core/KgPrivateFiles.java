package com.ruoyi.kangaroo.ext.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;

/**
 * 私有文件（资质证照、身份证等）：存在若依 profile 目录之外（profile 下的一切都经 /profile/** 公开可读），
 * 只能用带签名、有有效期的链接访问：员工端按门店授权签发，后台按审核权限签发。
 *
 * 签名密钥 kangaroo.file.sign-secret；不配时每次启动随机生成（链接只在本次进程内有效；多实例部署必须配置同一个值），
 * 配了但不到 16 个字符时照用并告警（KgSignSecret）。
 */
@Component
public class KgPrivateFiles
{
    private static final List<String> EXTS = Arrays.asList("jpg", "jpeg", "png", "pdf");
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final byte[] secret;

    public KgPrivateFiles(@Value("${kangaroo.file.sign-secret:}") String configured)
    {
        secret = KgSignSecret.resolve(configured);
    }

    File baseDir()
    {
        File profile = new File(RuoYiConfig.getProfile()).getAbsoluteFile();
        File parent = profile.getParentFile() == null ? profile : profile.getParentFile();
        return new File(parent, "kg-private");
    }

    /** 保存上传文件，返回 {key, name, size}；只收 jpg/png/pdf，10MB 以内 */
    public Map<String, Object> save(MultipartFile file, String category)
    {
        return save(file, category, false);
    }

    /** 营业执照的图片与 PDF 先烧入水印，再落盘到同一个私有存储。 */
    public Map<String, Object> saveLicense(MultipartFile file)
    {
        return save(file, "qual", true);
    }

    private Map<String, Object> save(MultipartFile file, String category, boolean license)
    {
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("请选择文件");
        }
        String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!EXTS.contains(ext))
        {
            throw new ServiceException("只支持 JPG / PNG / PDF");
        }
        if (file.getSize() > ("pdf".equals(ext) ? MAX_BYTES : 20L * 1024 * 1024))
            throw new ServiceException("pdf".equals(ext) ? "PDF不能超过10MB" : "图片不能超过20MB");
        if (!category.matches("[a-z]{2,16}"))
        {
            throw new ServiceException("文件类别不合法");
        }
        // 图片核对文件头（KgImageMagic）：只看扩展名时，别的文件改名成 .jpg 也能存进来，签名链接下载时再按图片类型下发
        if (!"pdf".equals(ext))
        {
            KgImageMagic.require(file, "jpg", "png");
        }
        KgLicenseWatermark.Prepared marked = license ? KgLicenseWatermark.prepare(file, true) : null;
        if (marked != null) { ext = marked.extension; name = marked.name; }
        String key = category + "/" + new SimpleDateFormat("yyyyMMdd").format(new Date()) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + ("jpeg".equals(ext) ? "jpg" : ext);
        File target = new File(baseDir(), key);
        if (!target.getParentFile().exists() && !target.getParentFile().mkdirs())
        {
            throw new ServiceException("文件保存失败");
        }
        try
        {
            if (marked != null) java.nio.file.Files.write(target.toPath(), marked.bytes);
            else file.transferTo(target);
        }
        catch (Exception e)
        {
            try { java.nio.file.Files.deleteIfExists(target.toPath()); } catch (Exception ignored) { }
            throw new ServiceException("文件保存失败");
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("name", name.length() > 120 ? name.substring(name.length() - 120) : name);
        m.put("size", marked == null ? file.getSize() : (long) marked.bytes.length);
        return m;
    }

    /** 文件键只允许本类生成的格式，防止路径穿越 */
    public File resolve(String key)
    {
        if (key == null || !key.matches("[a-z]{2,16}/\\d{8}/[0-9a-f]{32}\\.(jpg|png|pdf)"))
        {
            return null;
        }
        File f = new File(baseDir(), key);
        return f.isFile() ? f : null;
    }

    /** 带签名的访问路径（相对后端根），ttl 秒后失效 */
    public String sign(String key, int ttlSeconds)
    {
        long exp = System.currentTimeMillis() / 1000 + Math.max(30, ttlSeconds);
        return "/kangaroo/file/private?k=" + key + "&e=" + exp + "&s=" + mac(key + "|" + exp);
    }

    public boolean verify(String key, long exp, String sig)
    {
        if (key == null || sig == null || exp < System.currentTimeMillis() / 1000)
        {
            return false;
        }
        return java.security.MessageDigest.isEqual(mac(key + "|" + exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    private String mac(String data)
    {
        try
        {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = m.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++)
            {
                sb.append(String.format("%02x", out[i]));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }
}
