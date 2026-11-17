package com.ruoyi.kangaroo.ext.community;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialFiles;

/**
 * 私信里的文件（V46 kg_chat_file，2026-10-16 主人真机反馈）：只有门店和报名人 / 接单人的会话能发，一条消息一个文件。
 *
 * 照线上任务素材附件（KgOnlineMaterialFiles）的做法：
 *   · 只收 zip / rar / 7z、PDF、Word、Excel、PPT，单个不超过 10MB，文件头要和扩展名对得上（格式检查、文件名清洗直接用素材附件那一套）；
 *   · 文件放在若依 profile 目录之外的 kg-private/chat/{会话ID}/ 下（/profile/** 公开可读，这里不行），磁盘上只用 uuid 文件名；
 *   · 读文件先用正则核对键的格式，再做 canonical 路径检查，防止路径穿越；服务器绝不解压；
 *   · 下载一律按「附件」（Content-Disposition: attachment）、nosniff、不缓存；
 *   · 签名下载链接用 kangaroo.file.sign-secret（签名内容加 "chatf|" 前缀，和素材附件、证照的签名不能互用），3 天内有效；
 *     链接里带着复制链接的人：下载时按这个人重新核对还在不在会话里（接待资格撤销、拉黑、账号停用后链接随之失效）。
 * 文件跟着消息一直保留（没有「上传了没发出去」的情况：上传和发消息是同一个请求、同一个事务，事务没成功就删掉磁盘上的文件）。
 */
@Component
public class KgChatFiles
{
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KgChatFiles.class);

    /** 单个文件上限 10MB（Spring multipart 的 max-file-size；素材附件 2026-10-16 起分段上传到 100MB，聊天文件仍整个传，不跟着变） */
    public static final long MAX_BYTES = KgOnlineMaterialFiles.DIRECT_MAX_BYTES;

    /** 签名下载链接的有效期（与素材附件一样 3 天） */
    public static final int LINK_TTL_SECONDS = KgOnlineMaterialFiles.LINK_TTL_SECONDS;

    /** 每人每天最多发多少字节的文件（防止把服务器磁盘塞满；正常沟通远用不到） */
    public static final long MAX_BYTES_PER_SENDER_DAY = 200L * 1024 * 1024;

    private static final String KEY_PATTERN = "chat/[1-9][0-9]{0,18}/[0-9a-f]{32}\\.(zip|rar|7z|pdf|doc|docx|xls|xlsx|ppt|pptx)";

    private final byte[] secret;

    @Autowired
    private KgOnlineMaterialFiles materialFiles;

    public KgChatFiles(@Value("${kangaroo.file.sign-secret:}") String configured)
    {
        // 与素材附件同一个密钥配置：不配时每次启动随机生成，链接只在本次进程内有效
        secret = com.ruoyi.kangaroo.ext.core.KgSignSecret.resolve(configured);
    }

    /** 检查通过的上传：显示用的文件名、扩展名、字节数、内容 sha256（同一消息标识重发时核对是不是同一个文件） */
    public static final class Upload
    {
        public final String name;
        public final String ext;
        public final long size;
        public final String sha;
        public final MultipartFile file;

        Upload(String name, String ext, long size, String sha, MultipartFile file)
        {
            this.name = name;
            this.ext = ext;
            this.size = size;
            this.sha = sha;
            this.file = file;
        }
    }

    // ---------------------------------------------------------------- 纯函数（单测直接调）

    /** 会话列表、站内信、旧版本小程序里这条消息的文字：「[文件] 名称」 */
    public static String preview(String name)
    {
        return "[文件] " + (name == null ? "" : name);
    }

    /** 旧版本小程序不认识文件消息，只会把 content 当文字显示：告诉对方去升级（新版本按 type = file 显示文件卡片，不看这段文字） */
    public static String fallback(String name)
    {
        return preview(name) + "，请升级小程序查看";
    }

    /** 给页面的文件信息：{name, size, sizeLabel, ext, kind（archive 压缩包 / doc 文档）, openable（小程序里能直接打开）} */
    public static Map<String, Object> view(Object name, Object ext, Object size)
    {
        String e = KgJdbc.asStr(ext) == null ? "" : KgJdbc.asStr(ext);
        Long n = KgJdbc.asLong(size);
        long bytes = n == null ? 0L : n;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", KgJdbc.asStr(name));
        m.put("size", bytes);
        m.put("sizeLabel", KgOnlineMaterialFiles.sizeLabel(bytes));
        m.put("ext", e);
        m.put("kind", KgOnlineMaterialFiles.ARCHIVES.contains(e) ? "archive" : "doc");
        m.put("openable", KgOnlineMaterialFiles.DOCUMENTS.contains(e));
        return m;
    }

    // ---------------------------------------------------------------- 检查 / 存取

    /** 大小、扩展名（以显示名为准，没给时用上传文件名）、文件头都对才算通过；顺带算出内容的 sha256 */
    public Upload check(MultipartFile file, String displayName)
    {
        if (file != null && file.getSize() > MAX_BYTES)
        {
            throw new ServiceException("单个文件不能超过 10MB，可以分成几个压缩包，或把网盘链接写在文字里", 422);
        }
        KgOnlineMaterialFiles.Checked ok = materialFiles.check(file, displayName);
        return new Upload(ok.name, ok.ext, ok.size, sha256(file), file);
    }

    static String sha256(MultipartFile file)
    {
        try (InputStream in = file.getInputStream())
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
            {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest())
            {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            throw new ServiceException("文件读取失败，请重新选择", 422);
        }
    }

    File baseDir()
    {
        File profile = new File(RuoYiConfig.getProfile()).getAbsoluteFile();
        File parent = profile.getParentFile() == null ? profile : profile.getParentFile();
        return new File(parent, "kg-private");
    }

    /** 写到 kg-private/chat/{convId}/{uuid}.{ext}，返回文件键 */
    public String write(long convId, MultipartFile file, String ext)
    {
        String key = "chat/" + convId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        File target = target(key);
        if (target == null)
        {
            throw new ServiceException("文件保存失败", 500);
        }
        try
        {
            Files.createDirectories(target.toPath().getParent());
            file.transferTo(target);
        }
        catch (Exception e)
        {
            log.warn("私信文件保存失败 {}: {}", key, e.getMessage());
            throw new ServiceException("文件保存失败，请重试", 500);
        }
        return key;
    }

    /** 文件键 → 磁盘文件（键格式不对或越出 kg-private 返回 null；不检查文件在不在） */
    File target(String key)
    {
        if (key == null || !key.matches(KEY_PATTERN))
        {
            return null;
        }
        try
        {
            File base = baseDir().getCanonicalFile();
            File f = new File(base, key).getCanonicalFile();
            return f.toPath().startsWith(base.toPath()) ? f : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** 已存在的文件；键不合法、越界或文件不在都返回 null */
    public File resolve(String key)
    {
        File f = target(key);
        return f != null && f.isFile() ? f : null;
    }

    /** 删掉磁盘上的文件（尽力而为：发送失败回滚时用） */
    public void delete(String key)
    {
        File f = resolve(key);
        if (f != null && !f.delete())
        {
            log.warn("私信文件删除失败 {}", key);
        }
    }

    // ---------------------------------------------------------------- 签名链接（认人）

    /** 签名下载地址（相对后端根）：/kangaroo/file/chat?m=消息&u=复制链接的人&e=到期&s=签名 */
    public String sign(long msgId, long uid, long exp)
    {
        return "/kangaroo/file/chat?m=" + msgId + "&u=" + uid + "&e=" + exp + "&s=" + mac("chatf|" + msgId + "|" + uid + "|" + exp);
    }

    public boolean verify(long msgId, long uid, long exp, String sig)
    {
        if (msgId <= 0 || uid <= 0 || sig == null || exp < System.currentTimeMillis() / 1000)
        {
            return false;
        }
        return MessageDigest.isEqual(mac("chatf|" + msgId + "|" + uid + "|" + exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
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

    // ---------------------------------------------------------------- 下载

    /**
     * 流式返回一个文件。row 带 fileKey / fileName / fileExt（kg_chat_file 的一行）。
     * 总是按「附件」下载、禁止浏览器猜类型、不缓存，带 Content-Length（小程序靠它显示下载进度）。
     */
    public void serve(Map<String, Object> row, HttpServletResponse response) throws IOException
    {
        File f = resolve(KgJdbc.asStr(row.get("fileKey")));
        if (f == null)
        {
            throw new ServiceException("文件不存在或已被清理", 404);
        }
        String ext = KgJdbc.asStr(row.get("fileExt"));
        String name = KgJdbc.asStr(row.get("fileName"));
        if (name == null || name.isEmpty())
        {
            name = "文件." + ext;
        }
        String ascii = name.matches("[A-Za-z0-9._ ()\\-]+") ? name : "chat-file." + ext;
        response.setContentType(KgOnlineMaterialFiles.contentType(ext));
        response.setHeader("Content-Disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                + URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "private, no-store");
        response.setContentLengthLong(f.length());
        Files.copy(f.toPath(), response.getOutputStream());
    }
}
