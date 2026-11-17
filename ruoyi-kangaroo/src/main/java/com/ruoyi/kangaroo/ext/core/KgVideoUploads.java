package com.ruoyi.kangaroo.ext.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;

/**
 * 公开视频的分段上传（2026-10-17 旅居改版 R3：「3分钟看小院」、房型短视频、生活碎片、住宿评价视频）。
 *
 * 照 KgOnlineMaterialFiles 的分段上传做（那边线上已经跑通，nginx / Spring 的上传设置都不用改）：
 *   · 先登记文件名和大小（partBegin），再按顺序一段一段传（每段 8MB 原样放在请求体里，application/octet-stream），最后 partFinish 核对；
 *     nginx client_max_body_size 20m、Spring multipart 10MB 都不碍事；不管视频大小一律分段（5MB 的视频就是 1 段），只有一条上传路径；
 *   · 只收 mp4 / mov，单个不超过 200MB；拼好后核对文件头：ISO 媒体文件第 4–8 个字节是「ftyp」（mp4 / mov 都是这种容器），
 *     改了扩展名的网页、压缩包、图片进不来；
 *   · 时长服务端量不了（没有 ffprobe，不加本机依赖）：页面选视频时用 chooseMedia / chooseVideo 的 maxDuration 限 60 秒，
 *     登记时带上页面量到的时长，超过 61 秒的直接拒收（老实的客户端拦得住；改包绕过的只能靠 200MB 上限兜底）；
 *   · 视频是公开内容：和公开图片放在同一处 —— 若依 profile 目录 upload/videos/yyyy/MM/dd/{32位uuid}.mp4，经 /profile/** 访问
 *     （若依 ResourcesConfig 的静态资源处理，支持 Range 请求，video 拖动进度条不用整段重下；线上 nginx 的 location ^~ /profile/ 原样转给后端，不用改配置）；
 *     磁盘上只用 uuid 文件名，路径里不带原文件名；
 *   · 进行中的分段只放在内存里（线上只有一个后端；重启后作废，页面提示重新上传），临时文件在 profile 之外的 kg-private/video-part/，
 *     2 小时没新的一段就作废，服务重启前留下、超过一天的临时文件每小时清一次。
 * 只负责收文件、给出平台路径；视频挂到小院 / 房型 / 生活碎片 / 评价上时，各自再用 {@link #isPlatformVideo} 和 {@link #exists} 核对。
 */
@Component
public class KgVideoUploads
{
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KgVideoUploads.class);

    /** 单个视频上限 200MB（1 分钟手机视频压缩后一般 10–30MB） */
    public static final long MAX_BYTES = 200L * 1024 * 1024;

    /** 页面限的时长（秒）；登记时带的时长超过 MAX_SECONDS + 1 秒（四舍五入的余量）就拒收 */
    public static final int MAX_SECONDS = 60;

    /** 小院介绍视频（详情页「3分钟看小院」，登记时 kind=intro）：2026-10-17 卡片写的是 3 分钟，单独放宽到 3 分钟、200MB；其他视频仍是 1 分钟 / 200MB */
    public static final int INTRO_MAX_SECONDS = 180;

    public static final long INTRO_MAX_BYTES = 200L * 1024 * 1024;

    public static final String INTRO_TOO_BIG = "小院视频不能超过 200MB，可以剪短一点或用手机压缩后再传";

    /**
     * 门店采访视频：多短都能传（2026-10-31 主人拍板去掉 60 秒下限），时长要是有效正数；不设业务时长上限；分段上传文件大小仍为 300MB。
     */
    /** 旧发布包二进制兼容常量，采访校验不再使用该上限。 */
    @Deprecated
    public static final int INTERVIEW_MAX_SECONDS = 300;
    /** 旧发布包二进制兼容常量，采访校验不再使用该下限。 */
    @Deprecated
    public static final int INTERVIEW_MIN_SECONDS = 60;

    public static final long INTERVIEW_MAX_BYTES = 300L * 1024 * 1024;

    public static final String INTERVIEW_TOO_BIG = "采访视频不能超过 300MB，可以剪短一点或压缩后再传";

    /** 每段字节数（最后一段可以不足）：nginx client_max_body_size 20m，8MB 一段留足余量（和素材附件同一个数） */
    public static final int PART_BYTES = 8 * 1024 * 1024;

    /** 分段上传多久没有新的一段就作废 */
    public static final long PART_IDLE_MS = 2L * 3600 * 1000;

    /** 每人同时进行中的视频上传最多几个（页面一次传一个） */
    public static final int PART_MAX_OPEN = 3;

    public static final String TOO_BIG = "视频不能超过 200MB，可以剪短一点或用手机压缩后再传";

    public static final String TYPE_HINT = "只能上传 mp4 / mov 视频";

    private static final String PART_ID_PATTERN = "[0-9a-f]{32}";

    /** 平台视频路径：/profile/upload/videos/yyyy/MM/dd/{32位小写十六进制}.mp4|mov */
    private static final String URL_PATTERN = "/profile/upload/videos/[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}\\.(mp4|mov)";

    /** 进行中的分段上传 */
    private final Map<String, Part> parts = new ConcurrentHashMap<String, Part>();

    static final class Part
    {
        final String id;
        final Long uid;
        final String ext;
        final long size;
        final File file;
        long received;
        volatile long touched = System.currentTimeMillis();

        Part(String id, Long uid, String ext, long size, File file)
        {
            this.id = id;
            this.uid = uid;
            this.ext = ext;
            this.size = size;
            this.file = file;
        }

        int count()
        {
            return (int) ((size + PART_BYTES - 1) / PART_BYTES);
        }
    }

    // ---------------------------------------------------------------- 纯函数（单测直接调）

    /** 扩展名（小写，不含点）；没有返回空串。小程序的临时文件名形如 wxfile://tmp_xxx.mp4、http://tmp/xxx.MOV */
    public static String extOf(String name)
    {
        if (name == null)
        {
            return "";
        }
        String n = name.trim();
        int q = n.indexOf('?');
        if (q >= 0)
        {
            n = n.substring(0, q);
        }
        int dot = n.lastIndexOf('.');
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        return dot < 0 || dot < slash || dot == n.length() - 1 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean allowedExt(String ext)
    {
        return "mp4".equals(ext) || "mov".equals(ext);
    }

    /**
     * 文件头是不是 ISO 媒体文件（mp4 / mov / m4v 都是）：第一个盒子是 ftyp —— 前 4 个字节是盒子长度（8–4096），第 5–8 个字节是 'ftyp'，
     * 第 9–12 个字节是品牌（isom / mp41 / mp42 / avc1 / qt…，必须是可打印字符）。head 至少 12 个字节
     */
    public static boolean magicOk(byte[] head)
    {
        if (head == null || head.length < 12)
        {
            return false;
        }
        long box = ((head[0] & 0xffL) << 24) | ((head[1] & 0xffL) << 16) | ((head[2] & 0xffL) << 8) | (head[3] & 0xffL);
        if (box < 8 || box > 4096)
        {
            return false;
        }
        if (head[4] != 'f' || head[5] != 't' || head[6] != 'y' || head[7] != 'p')
        {
            return false;
        }
        for (int i = 8; i < 12; i++)
        {
            int c = head[i] & 0xff;
            if (c < 0x20 || c > 0x7e)
            {
                return false;
            }
        }
        return true;
    }

    /** 是不是本平台视频上传接口给的路径（只认格式，不看文件在不在） */
    public static boolean isPlatformVideo(String url)
    {
        return url != null && url.matches(URL_PATTERN);
    }

    /** 1 分钟以内的说法：登记时带的时长（秒，可以带小数）；没带或不是数字返回 null，超过 MAX_SECONDS + 1 报 422 */
    static Double checkDuration(Object raw)
    {
        return checkDuration(raw, MAX_SECONDS);
    }

    /** 同上，按给定的上限（小院介绍视频 INTRO_MAX_SECONDS） */
    static Double checkDuration(Object raw, int maxSeconds)
    {
        return checkDuration(raw, maxSeconds, "小院视频");
    }

    /** 同上；超过 2 分钟的上限按「{label}不能超过 N 分钟」提示（小院视频 / 采访视频） */
    static Double checkDuration(Object raw, int maxSeconds, String label)
    {
        if (raw == null || String.valueOf(raw).trim().isEmpty())
        {
            return null;
        }
        double d;
        try
        {
            d = Double.parseDouble(String.valueOf(raw).trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
        if (Double.isNaN(d) || d < 0)
        {
            return null;
        }
        if (d > maxSeconds + 1)
        {
            throw new ServiceException(maxSeconds >= 120 ? label + "不能超过 " + (maxSeconds / 60) + " 分钟，请剪短后再传"
                    : "视频不能超过 " + maxSeconds + " 秒，请剪短后再传", 422);
        }
        return d;
    }

    /** 采访独立校验，不改变小院介绍和评价短视频的时长规则。 */
    static double checkInterviewDuration(Object raw)
    {
        final double seconds;
        try
        {
            seconds = Double.parseDouble(String.valueOf(raw).trim());
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("请提供有效的采访视频时长（秒数）", 422);
        }
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > Integer.MAX_VALUE)
        {
            throw new ServiceException("请提供有效的采访视频时长（秒数）", 422);
        }
        return seconds;
    }

    // ---------------------------------------------------------------- 目录

    File profileDir()
    {
        return new File(RuoYiConfig.getProfile()).getAbsoluteFile();
    }

    File partDir()
    {
        File profile = profileDir();
        File parent = profile.getParentFile() == null ? profile : profile.getParentFile();
        return new File(new File(parent, "kg-private"), "video-part");
    }

    /** 平台视频路径 → 磁盘文件（格式不对或越出 profile 目录返回 null；不检查文件在不在） */
    File target(String url)
    {
        if (!isPlatformVideo(url))
        {
            return null;
        }
        try
        {
            File base = profileDir().getCanonicalFile();
            File f = new File(base, url.substring("/profile/".length())).getCanonicalFile();
            return f.toPath().startsWith(base.toPath()) ? f : null;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /** 平台视频路径对应的文件确实在（挂到小院 / 评价上之前核对，免得存进一个打不开的地址） */
    public boolean exists(String url)
    {
        File f = target(url);
        return f != null && f.isFile();
    }

    /** 文件字节数；不存在返回 0（小院评论提交时再核对一次 200MB，KgStayCommentService） */
    public long sizeOf(String url)
    {
        File f = target(url);
        return f != null && f.isFile() ? f.length() : 0L;
    }

    // ---------------------------------------------------------------- 分段上传

    /**
     * 开始上传：核对扩展名（按原文件名，小程序给的是临时名但扩展名是对的）、申报的大小、页面量到的时长，建一个空的临时文件。
     * 返回 {uploadId, partBytes, parts}。同一个人同时最多 PART_MAX_OPEN 个
     */
    public Map<String, Object> partBegin(Long uid, String rawName, Object size, Object duration)
    {
        return partBegin(uid, rawName, size, duration, false);
    }

    /** intro = 小院介绍视频（3 分钟、200MB）；其余 1 分钟、200MB */
    public Map<String, Object> partBegin(Long uid, String rawName, Object size, Object duration, boolean intro)
    {
        return intro ? begin(uid, rawName, size, duration, INTRO_MAX_BYTES, INTRO_TOO_BIG, INTRO_MAX_SECONDS, "小院视频")
                : begin(uid, rawName, size, duration, MAX_BYTES, TOO_BIG, MAX_SECONDS, "小院视频");
    }

    /**
     * 门店采访视频（后台上传，时长有效即可，300MB）。owner 用 {@link #adminOwner} 从后台账号换算成负数，
     * 和小程序用户（正数 app_user_id）的进行中上传分开，互相看不到、也不占对方的「同时最多 3 个」
     */
    public Map<String, Object> partBeginInterview(Long owner, String rawName, Object size, Object duration)
    {
        return begin(owner, rawName, size, duration, INTERVIEW_MAX_BYTES, INTERVIEW_TOO_BIG, 0, "采访视频");
    }

    /** 后台账号（sys_user.user_id，正数）在分段上传里的身份：取负数，永远不会和小程序用户撞上 */
    public static Long adminOwner(Long sysUserId)
    {
        if (sysUserId == null || sysUserId.longValue() == 0L)
        {
            throw new ServiceException("请先登录后台", 401);
        }
        return Long.valueOf(-Math.abs(sysUserId.longValue()));
    }

    private Map<String, Object> begin(Long uid, String rawName, Object size, Object duration, long maxBytes, String tooBig, int maxSeconds, String label)
    {
        String ext = extOf(rawName);
        if (!allowedExt(ext))
        {
            throw new ServiceException(TYPE_HINT, 422);
        }
        long bytes;
        try
        {
            bytes = size == null ? 0 : new java.math.BigDecimal(String.valueOf(size).trim()).longValueExact();
        }
        catch (ArithmeticException | NumberFormatException e)
        {
            bytes = 0;
        }
        if (bytes <= 0)
        {
            throw new ServiceException("视频是空的，请重新选择", 422);
        }
        if (bytes > maxBytes)
        {
            throw new ServiceException(tooBig, 422);
        }
        if (maxSeconds == 0) checkInterviewDuration(duration);
        else checkDuration(duration, maxSeconds, label);
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
            throw new ServiceException("同时在传的视频太多了，请等前面的传完再添加", 429);
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
            log.warn("视频分段上传建临时文件失败 {}: {}", id, e.getMessage());
            throw new ServiceException("视频保存失败，请重试", 500);
        }
        Part part = new Part(id, uid, ext, bytes, f);
        parts.put(id, part);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("uploadId", id);
        m.put("partBytes", PART_BYTES);
        m.put("parts", part.count());
        return m;
    }

    /** 找到自己的这个上传；没有（过期、服务重启过、别人的）报 404 */
    Part part(String id, Long uid)
    {
        Part p = id == null || !id.matches(PART_ID_PATTERN) ? null : parts.get(id);
        if (p == null || !p.uid.equals(uid))
        {
            throw new ServiceException("这次上传已失效（太久没动静或服务刚更新过），请重新上传这个视频", 404);
        }
        return p;
    }

    /**
     * 收第 index 段（从 0 起，请求体就是这段的原始字节）。必须按顺序：重传已经收过的段直接算成功（网络重试），跳段报 409。
     * 除最后一段外每段正好 PART_BYTES，最后一段是剩下的字节数；多了少了都不收。返回 {received, size}
     */
    public Map<String, Object> partAppend(String id, Long uid, int index, InputStream in)
    {
        Part p = part(id, uid);
        synchronized (p)
        {
            if (parts.get(id) != p)
            {
                throw new ServiceException("这次上传已失效，请重新上传这个视频", 404);
            }
            int total = p.count();
            if (index < 0 || index >= total)
            {
                throw new ServiceException("分段编号不对，请重新上传这个视频", 422);
            }
            int expected = (int) (p.received / PART_BYTES);
            if (index < expected)
            {
                p.touched = System.currentTimeMillis();
                return progress(p);
            }
            if (index > expected)
            {
                throw new ServiceException("上传顺序乱了，请重新上传这个视频", 409);
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
                // 第一段到了就先看文件头：不是视频的不用等 200MB 传完才拒收
                if (index == 0 && !magicOk(buf))
                {
                    abandon(p);
                    throw new ServiceException("这个文件不是能播放的 mp4 / mov 视频，请换一个视频", 422);
                }
                try (OutputStream out = new FileOutputStream(p.file, true))
                {
                    out.write(buf);
                }
            }
            catch (IOException e)
            {
                throw new ServiceException("视频接收失败，请重试", 422);
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

    /**
     * 传完：总字节数要对上、文件头要是视频（对不上删掉临时文件），挪到 profile/upload/videos/yyyy/MM/dd/{uuid}.{ext}。
     * 返回 {url: /profile/upload/videos/…, size}
     */
    public Map<String, Object> partFinish(String id, Long uid)
    {
        Part p = part(id, uid);
        synchronized (p)
        {
            if (parts.get(id) != p)
            {
                throw new ServiceException("这次上传已失效，请重新上传这个视频", 404);
            }
            if (p.received != p.size || p.file.length() != p.size)
            {
                throw new ServiceException("视频还没传完，请重试", 409);
            }
            parts.remove(id);
            byte[] head = new byte[12];
            try (InputStream in = new FileInputStream(p.file))
            {
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
                if (n < head.length || !magicOk(head))
                {
                    deleteFile(p.file);
                    throw new ServiceException("这个文件不是能播放的 mp4 / mov 视频，请换一个视频", 422);
                }
            }
            catch (IOException e)
            {
                deleteFile(p.file);
                throw new ServiceException("视频读取失败，请重新上传", 422);
            }
            LocalDate d = LocalDate.now();
            String url = String.format(Locale.ROOT, "/profile/upload/videos/%04d/%02d/%02d/%s.%s", d.getYear(), d.getMonthValue(), d.getDayOfMonth(),
                    UUID.randomUUID().toString().replace("-", ""), p.ext);
            File target = target(url);
            try
            {
                if (target == null)
                {
                    throw new IOException("bad path");
                }
                Files.createDirectories(target.toPath().getParent());
                Files.move(p.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException e)
            {
                log.warn("视频保存失败 {}: {}", url, e.getMessage());
                deleteFile(p.file);
                throw new ServiceException("视频保存失败，请重试", 500);
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("url", url);
            m.put("size", p.size);
            return m;
        }
    }

    /** 放弃一个上传（页面传失败或取消时调，尽力而为；不是自己的不动） */
    public void partAbort(String id, Long uid)
    {
        Part p = id == null ? null : parts.get(id);
        if (p != null && p.uid.equals(uid) && parts.remove(id, p))
        {
            deleteFile(p.file);
        }
    }

    private void abandon(Part p)
    {
        if (parts.remove(p.id, p))
        {
            deleteFile(p.file);
        }
    }

    /** 进行中的上传数（单测用） */
    int openCount()
    {
        return parts.size();
    }

    /** 作废太久没动静的上传；再清掉服务重启前留下、超过一天的临时文件 */
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
            if (f.isFile() && !parts.containsKey(id) && now - f.lastModified() > 24L * 3600000L)
            {
                deleteFile(f);
            }
        }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 900000)
    public void cleanup()
    {
        try
        {
            expireParts();
        }
        catch (RuntimeException e)
        {
            log.warn("清理视频分段上传的临时文件失败：{}", e.getMessage());
        }
    }

    private static void deleteFile(File f)
    {
        if (f != null && f.exists() && !f.delete())
        {
            log.warn("临时文件删除失败 {}", f.getName());
        }
    }
}
