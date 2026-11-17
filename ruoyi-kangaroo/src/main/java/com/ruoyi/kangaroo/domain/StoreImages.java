package com.ruoyi.kangaroo.domain;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;

/** 门店图片保持提交顺序；服务端统一封面，兼容仅提交 cover 的旧后台。 */
public final class StoreImages
{
    private StoreImages() { }
    public static final int MAX = 9;
    public static final int COLUMN_MAX = 3000;

    public static void apply(KgStore incoming, KgStore current)
    {
        List<String> images;
        if (incoming.getImages() != null)
        {
            images = parse(incoming.getImages());
        }
        else
        {
            if (incoming.getCover() == null) return; // 部分更新不能清空图片。
            String cover = incoming.getCover().trim();
            if (current != null && Objects.equals(cover, current.getCover())) return;
            images = new ArrayList<>();
            if (!cover.isEmpty())
            {
                images.add(cover);
                // 旧客户端换封面时只替换首图，保留其它图片及顺序。
                if (current != null && current.getImages() != null && !current.getImages().isEmpty())
                {
                    List<String> previous = parse(current.getImages());
                    for (int i = 1; i < previous.size(); i++)
                        if (!images.contains(previous.get(i))) images.add(previous.get(i));
                }
            }
        }
        for (String path : images) validatePath(path);
        String json = JSON.toJSONString(images);
        if (images.size() > MAX || json.length() > COLUMN_MAX)
            throw new ServiceException("门店图片最多上传 9 张，图片地址过长时请重新上传", 422);
        incoming.setImages(json);
        incoming.setCover(images.isEmpty() ? "" : images.get(0));
    }

    public static List<String> parse(String raw)
    {
        List<?> values;
        try { values = raw.trim().isEmpty() ? new ArrayList<>() : JSON.parseArray(raw); }
        catch (RuntimeException e) { throw new ServiceException("门店图片格式不正确，请重新选择图片", 422); }
        if (values == null) throw new ServiceException("门店图片格式不正确，请重新选择图片", 422);
        List<String> result = new ArrayList<>();
        for (Object value : values)
        {
            if (!(value instanceof String)) throw new ServiceException("门店图片格式不正确，请重新选择图片", 422);
            String path = ((String) value).trim();
            if (!path.isEmpty() && !result.contains(path)) result.add(path);
        }
        if (result.size() > MAX) throw new ServiceException("门店图片最多上传 9 张", 422);
        return result;
    }

    private static void validatePath(String path)
    {
        if (path.length() > 255) throw new ServiceException("门店图片地址过长，请重新上传", 422);
        if (JobImages.isUpload(path)) return;
        // 原门店支持图片网址及小程序内置图；保留安全的旧地址，不能因编辑其它资料而丢封面。
        if (path.startsWith("/static/") && !path.contains("..") && !path.contains("\\")) return;
        try
        {
            URI uri = new URI(path);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null) return;
        }
        catch (Exception ignored) { }
        throw new ServiceException("门店图片地址无效，请删除该图片后重新上传", 422);
    }
}
