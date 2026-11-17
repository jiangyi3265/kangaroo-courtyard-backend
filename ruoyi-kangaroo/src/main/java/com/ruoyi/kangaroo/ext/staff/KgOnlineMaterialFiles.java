package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 线上任务素材附件（V41 kg_online_material_file）的文件存放：门店发给接单人的压缩包和文档。
 *
 * 照 KgArrivalTickets / KgPrivateFiles 的做法：
 *   · 文件放在若依 profile 目录之外的 kg-private/omat/{接单ID}/ 下（/profile/** 公开可读，这里不行），磁盘上只用 uuid 文件名；
 *   · 只收白名单里的扩展名，再核对文件头（扩展名改过的 exe 之类进不来）；单个不超过 100MB（2026-10-16 起，之前 10MB）；
 *   · 8MB 以内的整个上传（multipart）；更大的分段上传：每段 8MB 原样放在请求体里，按顺序接到 kg-private/omat-part/{上传号}.part，
 *     传完核对总大小和文件头再挪进 omat/ —— 不用改服务器的 nginx client_max_body_size（20m）和 Spring multipart（10MB）设置；
 *   · 原文件名去掉路径、控制字符和 \/:*?"<>|，截到 100 字，只用于显示和下载时命名；
 *   · 读文件先用正则核对键的格式，再做 canonical 路径检查，防止路径穿越；
 *   · 服务器绝不解压（不存在 zip-slip、压缩炸弹的问题）；
 *   · 签名下载链接用 kangaroo.file.sign-secret（与 KgPrivateFiles 同一个密钥，签名内容加了 "omat|" 前缀，两边的签名不能互用），72 小时有效；
 *   · 上传后 24 小时还没发出去的文件，每小时清理一次（删文件、记 del_flag）；发出去的一直保留。
 */
@Component
public class KgOnlineMaterialFiles
{
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KgOnlineMaterialFiles.class);

    /** 单个文件上限：2026-10-16 起 100MB（之前 10MB；主人说 6 张原图打的包就有 30 多 MB）。超过 8MB 的分段上传 */
    public static final long MAX_BYTES = 100L * 1024 * 1024;

    /** 整个文件一次传（multipart）的上限：Spring multipart max-file-size 是 10MB，更大的必须分段上传 */
    public static final long DIRECT_MAX_BYTES = 10L * 1024 * 1024;

    /** 分段上传每段的字节数（最后一段可以不足）：nginx client_max_body_size 20m，8MB 一段留足余量 */
    public static final int PART_BYTES = 8 * 1024 * 1024;

    /** 分段上传多久没有新的一段就作废（删掉已收的部分） */
    public static final long PART_IDLE_MS = 2L * 3600 * 1000;

    /** 每人同时进行中的分段上传最多几个（页面是一个一个传的） */
    public static final int PART_MAX_OPEN = 5;

    public static final String TOO_BIG = "单个文件不能超过 100MB，可以分成几个压缩包，或把网盘链接写在文字里";

    private static final String PART_ID_PATTERN = "[0-9a-f]{32}";

    public static final List<String> ARCHIVES = Arrays.asList("zip", "rar", "7z");

    /** 小程序里能用 openDocument 直接打开的 */
    public static final List<String> DOCUMENTS = Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx");

    /** 签名下载链接的有效期 */
    public static final int LINK_TTL_SECONDS = 72 * 3600;

    /** 上传了却没发出去的文件保留多久 */
    public static final int UNSENT_KEEP_HOURS = 24;

    public static final String TYPE_HINT = "只能发 zip / rar / 7z 压缩包和 PDF、Word、Excel、PPT 文件";

    private static final String KEY_PATTERN = "omat/[1-9][0-9]{0,18}/[0-9a-f]{32}\\.(zip|rar|7z|pdf|doc|docx|xls|xlsx|ppt|pptx)";

    private final byte[] secret;

    @Autowired(required = false)
    private KgJdbc db;

    /** 进行中的分段上传：只放在内存里（线上只有一个后端；重启后作废，页面会提示重新添加这个文件） */
    private final Map<String, Part> parts = new ConcurrentHashMap<String, Part>();

    public KgOnlineMaterialFiles(@Value("${kangaroo.file.sign-secret:}") String configured)
    {
        // 不配时每次启动随机生成：链接只在本次进程内有效（线上用 KANGAROO_FILE_SIGN_SECRET 配置，重启后链接照样能用）；
        // 配了但不到 16 个字符时照用并告警，不再悄悄换成随机密钥（KgSignSecret）
        secret = com.ruoyi.kangaroo.ext.core.KgSignSecret.resolve(configured);
    }

    /** 文件检查通过后的结果：显示用的文件名、扩展名、字节数 */
    public static final class Checked
    {
        public final String name;
        public final String ext;
        public final long size;

        Checked(String name, String ext, long size)
        {
            this.name = name;
            this.ext = ext;
            this.size = size;
        }
    }

    /** 一个进行中的分段上传：属于哪个接单、谁传的、显示名、扩展名、总大小、已收字节数 */
    static final class Part
    {
        final String id;
        final Long appId;
        final Long uid;
        final String name;
        final String ext;
        final long size;
        final File file;
        long received;
        volatile long touched = System.currentTimeMillis();

        Part(String id, Long appId, Long uid, String name, String ext, long size, File file)
        {
            this.id = id;
            this.appId = appId;
            this.uid = uid;
            this.name = name;
            this.ext = ext;
            this.size = size;
            this.file = file;
        }

        int count()
        {
            return (int) ((size + PART_BYTES - 1) / PART_BYTES);
        }
    }

    /** 分段上传传完、核对过的文件：显示信息 + 拼好的临时文件（由 {@link #adopt} 挪进 omat/） */
    public static final class Assembled
    {
        public final Checked checked;
        public final File file;

        Assembled(Checked checked, File file)
        {
            this.checked = checked;
            this.file = file;
        }
    }

    File baseDir()
    {
        File profile = new File(RuoYiConfig.getProfile()).getAbsoluteFile();
        File parent = profile.getParentFile() == null ? profile : profile.getParentFile();
        return new File(parent, "kg-private");
    }

    // ---------------------------------------------------------------- 纯函数（单测直接调）

    /** 扩展名（小写，不含点）；没有返回空串 */
    public static String extOf(String name)
    {
        if (name == null)
        {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }

    public static boolean allowed(String ext)
    {
        return ARCHIVES.contains(ext) || DOCUMENTS.contains(ext);
    }

    /**
     * 显示用的文件名：去掉路径（/ 和 \ 之前的部分）、控制字符、不可见的格式字符（比如把扩展名倒过来显示的 U+202E）和 \/:*?"<>|，
     * 连续空白合成一个，去掉开头的点；超过 100 字时保留扩展名截短。清完是空的就叫「文件.扩展名」。
     */
    public static String cleanName(String raw, String ext)
    {
        String s = raw == null ? "" : raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0)
        {
            s = s.substring(slash + 1);
        }
        // \p{Cntrl} 只管 ASCII 控制字符；\p{Cc} 连 U+0080–U+009F 也算上，\p{Zl}\p{Zp}（U+2028 / U+2029）在页面上会断行
        s = s.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim().replaceAll("^\\.+", "").trim();
        String suffix = "." + ext;
        if (s.isEmpty() || s.equalsIgnoreCase(ext) || s.equalsIgnoreCase(suffix))
        {
            return "文件" + suffix;
        }
        if (!s.toLowerCase(Locale.ROOT).endsWith(suffix))
        {
            s = s + suffix;
        }
        if (s.length() > 100)
        {
            String base = s.toLowerCase(Locale.ROOT).endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
            s = base.substring(0, Math.max(1, 100 - suffix.length())).trim() + suffix;
        }
        return s;
    }

    /** 文件头与扩展名对得上（head 至少读 8 个字节）。zip / docx / xlsx / pptx 都是 zip 容器 */
    public static boolean magicOk(String ext, byte[] head)
    {
        if (head == null || head.length < 8)
        {
            return false;
        }
        int[] h = new int[8];
        for (int i = 0; i < 8; i++)
        {
            h[i] = head[i] & 0xff;
        }
        switch (ext)
        {
            case "zip":
            case "docx":
            case "xlsx":
            case "pptx":
                return h[0] == 0x50 && h[1] == 0x4B && ((h[2] == 0x03 && h[3] == 0x04) || (h[2] == 0x05 && h[3] == 0x06));
            case "rar":
                return h[0] == 0x52 && h[1] == 0x61 && h[2] == 0x72 && h[3] == 0x21 && h[4] == 0x1A && h[5] == 0x07;
            case "7z":
                return h[0] == 0x37 && h[1] == 0x7A && h[2] == 0xBC && h[3] == 0xAF && h[4] == 0x27 && h[5] == 0x1C;
            case "pdf":
                return h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-';
            case "doc":
            case "xls":
            case "ppt":
                return h[0] == 0xD0 && h[1] == 0xCF && h[2] == 0x11 && h[3] == 0xE0 && h[4] == 0xA1 && h[5] == 0xB1 && h[6] == 0x1A && h[7] == 0xE1;
            default:
                return false;
        }
    }

    /** 1536 → "1.5 KB"，10485760 → "10 MB" */
    public static String sizeLabel(long bytes)
    {
        if (bytes < 1024)
        {
            return bytes + " B";
        }
        double v = bytes < 1024L * 1024 ? bytes / 1024.0 : bytes / (1024.0 * 1024);
        String unit = bytes < 1024L * 1024 ? " KB" : " MB";
        String n = v >= 100 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
        return (n.endsWith(".0") ? n.substring(0, n.length() - 2) : n) + unit;
    }

    public static String contentType(String ext)
    {
        switch (ext == null ? "" : ext)
        {
            case "zip":
                return "application/zip";
            case "rar":
                return "application/vnd.rar";
            case "7z":
                return "application/x-7z-compressed";
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default:
                return "application/octet-stream";
        }
    }

    /** 给页面的一条附件：{fileId, name, size, sizeLabel, ext, kind（archive 压缩包 / doc 文档）, openable（小程序里能直接打开）} */
    public static Map<String, Object> view(Map<String, Object> r)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String ext = KgJdbc.asStr(r.get("fileExt")) == null ? "" : KgJdbc.asStr(r.get("fileExt"));
        Long size = KgJdbc.asLong(r.get("fileSize"));
        m.put("fileId", KgJdbc.asLong(r.get("fileId")));
        m.put("name", r.get("fileName"));
        m.put("size", size == null ? 0L : size);
        m.put("sizeLabel", sizeLabel(size == null ? 0L : size));
        m.put("ext", ext);
        m.put("kind", ARCHIVES.contains(ext) ? "archive" : "doc");
        m.put("openable", DOCUMENTS.contains(ext));
        return m;
    }

    // ---------------------------------------------------------------- 存取

    /** 检查上传的文件：大小、扩展名（以显示名为准，没给时用上传文件名）、文件头。通过后返回清洗过的显示名 */
    public Checked check(MultipartFile file, String displayName)
    {
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("没有收到文件，请重新选择", 422);
        }
        if (file.getSize() > MAX_BYTES)
        {
            throw new ServiceException(TOO_BIG, 422);
        }
        if (file.getSize() > DIRECT_MAX_BYTES)
        {
            throw new ServiceException("文件比较大，请更新到最新版本后重新添加（大文件要分段上传）", 422);
        }
        String raw = displayName == null || displayName.trim().isEmpty() ? file.getOriginalFilename() : displayName;
        String ext = allowedExt(raw);
        try (InputStream in = file.getInputStream())
        {
            checkHead(ext, in);
        }
        catch (IOException e)
        {
            throw new ServiceException("文件读取失败，请重新上传", 422);
        }
        return new Checked(cleanName(raw, ext), ext, file.getSize());
    }

    /** 显示名的扩展名，不在白名单里报 422 */
    static String allowedExt(String raw)
    {
        String ext = extOf(raw);
        if (!allowed(ext))
        {
            throw new ServiceException(TYPE_HINT, 422);
        }
        return ext;
    }

    /** 读前 8 个字节核对文件头，对不上报 422 */
    static void checkHead(String ext, InputStream in) throws IOException
    {
        byte[] head = new byte[8];
        int n = 0;
        while (n < head.length)
        {
            int r = in.read(head, n, head.length - n);
            if (r < 0)
            {
                break;
            }
            n += r;
        }
        if (n < head.length || !magicOk(ext, head))
        {
            throw new ServiceException("文件内容和格式对不上（." + ext + "），请确认文件没有损坏、扩展名没有改过", 422);
        }
    }

    // ---------------------------------------------------------------- 分段上传（超过 8MB 的文件）

    File partDir()
    {
        return new File(baseDir(), "omat-part");
    }

    /**
     * 开始一个分段上传：核对文件名的扩展名和申报的大小，建一个空的临时文件。
     * 返回 {uploadId, partBytes 每段字节数, parts 一共几段}。同一个人同时最多 PART_MAX_OPEN 个
     */
    public Map<String, Object> partBegin(Long appId, Long uid, String rawName, long size)
    {
        String ext = allowedExt(rawName);
        if (size <= 0)
        {
            throw new ServiceException("文件是空的，请重新选择", 422);
        }
        if (size > MAX_BYTES)
        {
            throw new ServiceException(TOO_BIG, 422);
        }
        expireParts();
        int open = 0;
        for (Part x : parts.values())
        {
            if (x.uid.equals(uid))
            {
                open++;
            }
        }
        if (open >= PART_MAX_OPEN)
        {
            throw new ServiceException("同时在传的文件太多了，请等前面的传完再添加", 429);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        File f = new File(partDir(), id + ".part");
        try
        {
            Files.createDirectories(f.toPath().getParent());
            Files.write(f.toPath(), new byte[0]);
        }
        catch (IOException e)
        {
            log.warn("分段上传建临时文件失败 {}: {}", id, e.getMessage());
            throw new ServiceException("文件保存失败，请重试", 500);
        }
        Part part = new Part(id, appId, uid, cleanName(rawName, ext), ext, size, f);
        parts.put(id, part);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("uploadId", id);
        m.put("partBytes", PART_BYTES);
        m.put("parts", part.count());
        return m;
    }

    /** 找到自己的这个分段上传；没有（过期、服务重启过、别人的、别的接单的）报 404 */
    Part part(String id, Long appId, Long uid)
    {
        Part p = id == null || !id.matches(PART_ID_PATTERN) ? null : parts.get(id);
        if (p == null || !p.appId.equals(appId) || !p.uid.equals(uid))
        {
            throw new ServiceException("这次上传已失效（太久没动静或服务刚更新过），请重新添加这个文件", 404);
        }
        return p;
    }

    /**
     * 收第 index 段（从 0 起，请求体就是这段的原始字节）。必须按顺序：重传已经收过的段直接算成功（网络重试），跳段报 409。
     * 除最后一段外每段正好 PART_BYTES，最后一段是剩下的字节数；多了少了都不收。返回 {received, size}
     */
    public Map<String, Object> partAppend(String id, Long appId, Long uid, int index, InputStream in)
    {
        Part p = part(id, appId, uid);
        synchronized (p)
        {
            if (parts.get(id) != p)
            {
                throw new ServiceException("这次上传已失效，请重新添加这个文件", 404);
            }
            int total = p.count();
            if (index < 0 || index >= total)
            {
                throw new ServiceException("分段编号不对，请重新添加这个文件", 422);
            }
            int expected = (int) (p.received / PART_BYTES);
            if (index < expected)
            {
                p.touched = System.currentTimeMillis();
                return progress(p);
            }
            if (index > expected)
            {
                throw new ServiceException("上传顺序乱了，请重新添加这个文件", 409);
            }
            int len = (int) Math.min(PART_BYTES, p.size - (long) index * PART_BYTES);
            byte[] buf = new byte[len];
            try
            {
                int n = 0;
                while (n < len)
                {
                    int r = in.read(buf, n, len - n);
                    if (r < 0)
                    {
                        break;
                    }
                    n += r;
                }
                if (n < len || in.read() >= 0)
                {
                    throw new ServiceException("这一段没传完整，请重试", 422);
                }
                try (OutputStream out = new FileOutputStream(p.file, true))
                {
                    out.write(buf);
                }
            }
            catch (IOException e)
            {
                throw new ServiceException("文件接收失败，请重试", 422);
            }
            p.received += len;
            p.touched = System.currentTimeMillis();
            return progress(p);
        }
    }

    private static Map<String, Object> progress(Part p)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("received", p.received);
        m.put("size", p.size);
        return m;
    }

    /** 传完：总字节数要对上、文件头要和扩展名对上（对不上删掉临时文件）。成功后这次上传从内存里拿掉，临时文件交给调用方 */
    public Assembled partFinish(String id, Long appId, Long uid)
    {
        Part p = part(id, appId, uid);
        synchronized (p)
        {
            if (parts.get(id) != p)
            {
                throw new ServiceException("这次上传已失效，请重新添加这个文件", 404);
            }
            if (p.received != p.size || p.file.length() != p.size)
            {
                throw new ServiceException("文件还没传完，请重试", 409);
            }
            parts.remove(id);
            try (InputStream in = new FileInputStream(p.file))
            {
                checkHead(p.ext, in);
            }
            catch (IOException e)
            {
                deleteFile(p.file);
                throw new ServiceException("文件读取失败，请重新上传", 422);
            }
            catch (RuntimeException e)
            {
                deleteFile(p.file);
                throw e;
            }
            return new Assembled(new Checked(p.name, p.ext, p.size), p.file);
        }
    }

    /** 放弃一个分段上传（页面传失败或取消时调，尽力而为） */
    public void partAbort(String id, Long appId, Long uid)
    {
        Part p = id == null ? null : parts.get(id);
        if (p != null && p.appId.equals(appId) && p.uid.equals(uid) && parts.remove(id, p))
        {
            deleteFile(p.file);
        }
    }

    /** 拼好的临时文件挪到 omat/{appId}/{uuid}.{ext}，返回文件键；挪不动就删掉临时文件 */
    public String adopt(Long appId, File assembled, String ext)
    {
        String key = "omat/" + appId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        File target = target(key);
        try
        {
            if (target == null)
            {
                throw new IOException("bad key");
            }
            Files.createDirectories(target.toPath().getParent());
            Files.move(assembled.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            log.warn("素材附件保存失败 {}: {}", key, e.getMessage());
            deleteFile(assembled);
            throw new ServiceException("文件保存失败，请重试", 500);
        }
        return key;
    }

    /** 作废太久没动静的分段上传；再清掉服务重启前留下、超过一天的临时文件 */
    void expireParts()
    {
        long now = System.currentTimeMillis();
        for (Part p : parts.values())
        {
            if (now - p.touched > PART_IDLE_MS && parts.remove(p.id, p))
            {
                deleteFile(p.file);
            }
        }
        File[] left = partDir().listFiles();
        if (left == null)
        {
            return;
        }
        for (File f : left)
        {
            String n = f.getName();
            String id = n.endsWith(".part") ? n.substring(0, n.length() - 5) : "";
            if (f.isFile() && !parts.containsKey(id) && now - f.lastModified() > UNSENT_KEEP_HOURS * 3600000L)
            {
                deleteFile(f);
            }
        }
    }

    private static void deleteFile(File f)
    {
        if (f != null && f.exists() && !f.delete())
        {
            log.warn("临时文件删除失败 {}", f.getName());
        }
    }

    /** 写到 kg-private/omat/{appId}/{uuid}.{ext}，返回文件键 */
    public String write(Long appId, MultipartFile file, String ext)
    {
        String key = "omat/" + appId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
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
            log.warn("素材附件保存失败 {}: {}", key, e.getMessage());
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

    /** 删掉磁盘上的文件（尽力而为） */
    public void delete(String key)
    {
        File f = resolve(key);
        if (f != null && !f.delete())
        {
            log.warn("素材附件删除失败 {}", key);
        }
    }

    // ---------------------------------------------------------------- 签名链接

    /** 签名下载地址（相对后端根）：/kangaroo/file/material?f=&e=&s= */
    public String sign(long fileId, long exp)
    {
        return "/kangaroo/file/material?f=" + fileId + "&e=" + exp + "&s=" + mac("omat|" + fileId + "|" + exp);
    }

    public boolean verify(long fileId, long exp, String sig)
    {
        if (fileId <= 0 || sig == null || exp < System.currentTimeMillis() / 1000)
        {
            return false;
        }
        return MessageDigest.isEqual(mac("omat|" + fileId + "|" + exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
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
     * 流式返回一个附件。row 是 kg_online_material_file 的一行（fileKey / fileName / fileExt）。
     * 总是按「附件」下载（Content-Disposition: attachment）、禁止浏览器猜类型、不缓存，带 Content-Length（小程序靠它显示下载进度）。
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
        String ascii = name.matches("[A-Za-z0-9._ ()\\-]+") ? name : "material." + ext;
        response.setContentType(contentType(ext));
        response.setHeader("Content-Disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                + URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "private, no-store");
        response.setContentLengthLong(f.length());
        Files.copy(f.toPath(), response.getOutputStream());
    }

    // ---------------------------------------------------------------- 清理

    /**
     * 每小时清一次：上传后 UNSENT_KEEP_HOURS 小时还没发出去（material_id 为空）的文件，先按条件把行记成已清理，记成功了再删文件。
     * 发送时对这一行加了锁、写上 material_id，和这里的条件更新互斥，不会删掉刚发出去的文件。多实例同时跑也只生效一次。
     */
    @Scheduled(fixedDelay = 3600000, initialDelay = 600000)
    public void cleanupUnsent()
    {
        try
        {
            expireParts();
        }
        catch (RuntimeException e)
        {
            log.warn("清理分段上传的临时文件失败：{}", e.getMessage());
        }
        if (db == null)
        {
            return;
        }
        try
        {
            Date before = new Date(System.currentTimeMillis() - UNSENT_KEEP_HOURS * 3600000L);
            for (Map<String, Object> r : db.list("select file_id, file_key from kg_online_material_file where material_id is null and del_flag = '0' "
                    + "and create_time < :before order by file_id limit 500", p("before", before)))
            {
                int n = db.update("update kg_online_material_file set del_flag = '2' where file_id = :id and material_id is null and del_flag = '0'",
                        p("id", r.get("fileId")));
                if (n == 1)
                {
                    delete(KgJdbc.asStr(r.get("fileKey")));
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("清理没发出去的素材附件失败：{}", e.getMessage());
        }
    }
}
