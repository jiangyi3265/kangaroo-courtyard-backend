package com.ruoyi.kangaroo.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.core.KgImageMagic;

/**
 * 岗位照片图集（V49，kg_job.images）：门店在小程序发布 / 换照片、后台岗位管理上传，最多 9 张。
 * 库里存 JSON 数组（["/profile/upload/2026/10/16/xxx.jpg", …]），'' = 没传。
 *
 *  · 只收本平台上传接口给的路径（/profile/upload/…，文件名是随机串）：别处的网址、包内图片、带 .. 的路径一律 422，不悄悄丢掉；
 *  · 入参认 JSON 数组、JSON 数组字符串、逗号分隔的字符串（后台图片上传组件给的就是逗号分隔），重复的只留一张；
 *  · 读的时候宽松：库里有不合格的（只可能是手改库）就跳过，不让详情页报错；
 *  · 封面：第一张照片 → 岗位原来的封面（发布时抄的门店封面、后台手填的）→ 门店封面。
 */
public final class JobImages
{
    private JobImages()
    {
    }

    /** 最多几张 */
    public static final int MAX = 9;

    /** kg_job.images 列宽 */
    public static final int COLUMN_MAX = 2000;

    /** 到店岗位提交、改图及上架共用的必填校验；封面地址不能替代岗位照片。 */
    public static List<String> require(Object raw)
    {
        List<String> images = parse(raw);
        if (images == null || images.isEmpty())
        {
            throw new ServiceException("请至少上传 1 张岗位照片", 422);
        }
        // 图片地址本身不能证明上传成功：通用上传还接收视频/附件，历史文件也可能已被删除。
        // 只在提交、改图、上架等写入边界核验文件；读旧岗位仍保持宽松，避免整个详情不可用。
        requireExisting(images, "岗位照片");
        return images;
    }

    /** 和岗位照片同一套文件核对（文件要在、要真是图片），提示文字按 what 说：宠物照片、住宿照片这些选填图集（V66）共用 */
    public static void requireExisting(List<String> images, String what)
    {
        for (String image : images)
        {
            requireExistingImage(image, what);
        }
    }

    /** 图片上传路径；兼容后台历史 BMP 和小程序 WebP，不接受附件、视频或非规范路径。 */
    public static boolean isUpload(String s)
    {
        return s != null && s.length() < 255 && !s.contains("..") && !s.contains("//") && !s.contains("/./")
                && s.matches("/profile/upload/[A-Za-z0-9_./-]+\\.(?i:jpg|jpeg|png|gif|webp|bmp)");
    }

    private static void requireExistingImage(String image, String what)
    {
        String profile = RuoYiConfig.getProfile();
        if (profile == null || profile.trim().isEmpty())
        {
            throw new ServiceException(what + "不存在或无法读取，请重新上传", 422);
        }
        try
        {
            // 与 FileUploadUtils /profile/upload/... -> ${ruoyi.profile}/upload/... 的约定一致。
            // realPath 同时核对符号链接：不能借上传目录中的链接引用目录外的文件。
            Path uploadRoot = Paths.get(profile, "upload").toRealPath();
            Path target = uploadRoot.resolve(image.substring("/profile/upload/".length())).normalize().toRealPath();
            if (!target.startsWith(uploadRoot) || !Files.isRegularFile(target))
            {
                throw new ServiceException(what + "路径无效，请重新上传", 422);
            }
            byte[] head = new byte[32];
            int n = 0;
            try (InputStream in = Files.newInputStream(target))
            {
                while (n < head.length)
                {
                    int count = in.read(head, n, head.length - n);
                    if (count < 0) break;
                    n += count;
                }
            }
            // 复用小程序上传的文件头识别；后台通用上传历史允许 BMP，因此保留 BMP 文件头兼容。
            boolean bitmap = n >= 26 && head[0] == 'B' && head[1] == 'M';
            if (KgImageMagic.detect(head, n) == null && !bitmap)
            {
                throw new ServiceException(what + "文件不是有效的图片，请重新上传", 422);
            }
        }
        catch (IOException | SecurityException e)
        {
            throw new ServiceException(what + "不存在或无法读取，请重新上传", 422);
        }
    }

    /**
     * 入参 → 校验后的照片列表（保持顺序，去重）。null 返回 null（= 没带这个字段，由调用方决定保持原值还是当没传）。
     * 不合格的路径、超过 9 张、拼起来超过列宽都 422
     */
    public static List<String> parse(Object raw)
    {
        return parse(raw, MAX, COLUMN_MAX, "岗位照片");
    }

    /** 同上，张数上限、列宽、提示文字由调用方给（V66：宠物照片最多 6 张、列宽 1000） */
    public static List<String> parse(Object raw, int max, int columnMax, String what)
    {
        if (raw == null)
        {
            return null;
        }
        List<Object> items = new ArrayList<Object>();
        if (raw instanceof List)
        {
            items.addAll((List<?>) raw);
        }
        else if (raw instanceof String)
        {
            String s = ((String) raw).trim();
            if (s.startsWith("["))
            {
                try
                {
                    JSONArray arr = JSON.parseArray(s);
                    if (arr != null)
                    {
                        items.addAll(arr);
                    }
                }
                catch (RuntimeException e)
                {
                    throw new ServiceException(what + "格式不对，请重新上传", 422);
                }
            }
            else if (!s.isEmpty())
            {
                for (String x : s.split(","))
                {
                    items.add(x);
                }
            }
        }
        else
        {
            throw new ServiceException(what + "格式不对，请重新上传", 422);
        }
        List<String> out = new ArrayList<String>();
        for (Object o : items)
        {
            if (!(o instanceof String))
            {
                throw new ServiceException(what + "格式不对，请重新上传", 422);
            }
            String u = ((String) o).trim();
            if (u.isEmpty())
            {
                continue;
            }
            if (!isUpload(u))
            {
                throw new ServiceException(what + "只能用本平台上传的图片，请重新上传", 422);
            }
            if (!out.contains(u))
            {
                out.add(u);
            }
        }
        if (out.size() > max)
        {
            throw new ServiceException(what + "最多 " + max + " 张", 422);
        }
        if (toJson(out).length() > columnMax)
        {
            throw new ServiceException(what + "太多了，请删掉几张再保存", 422);
        }
        return out;
    }

    /** 存库：JSON 数组；没有照片存 ''（和列默认值一致） */
    public static String toJson(List<String> images)
    {
        return images == null || images.isEmpty() ? "" : JSON.toJSONString(images);
    }

    /** 读库（宽松）：JSON 数组或逗号分隔都认，跳过不合格的，最多 9 张；从不抛错 */
    public static List<String> read(String stored)
    {
        String s = stored == null ? "" : stored.trim();
        if (s.isEmpty())
        {
            return Collections.emptyList();
        }
        List<Object> items = new ArrayList<Object>();
        if (s.startsWith("["))
        {
            try
            {
                JSONArray arr = JSON.parseArray(s);
                if (arr != null)
                {
                    items.addAll(arr);
                }
            }
            catch (RuntimeException e)
            {
                return Collections.emptyList();
            }
        }
        else
        {
            for (String x : s.split(","))
            {
                items.add(x);
            }
        }
        List<String> out = new ArrayList<String>();
        for (Object o : items)
        {
            String u = o instanceof String ? ((String) o).trim() : "";
            if (isUpload(u) && !out.contains(u) && out.size() < MAX)
            {
                out.add(u);
            }
        }
        return out;
    }

    /** 封面：第一张照片，没有照片用岗位原来的封面，再没有用门店封面；都没有返回 '' */
    public static String cover(List<String> images, String jobCover, String storeCover)
    {
        if (images != null && !images.isEmpty())
        {
            return images.get(0);
        }
        if (jobCover != null && !jobCover.trim().isEmpty())
        {
            return jobCover.trim();
        }
        return storeCover == null ? "" : storeCover.trim();
    }
}
