package com.ruoyi.kangaroo.ext.core;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.exception.ServiceException;

/**
 * 图片文件头核对（2026-10-16 审计）：上传只看扩展名时，把一个 HTML / SVG / 脚本改名成 .jpg 就能存进公开的 /profile/ 下，
 * 再配合浏览器的内容嗅探当网页打开。这里读前 12 个字节，按文件头认 jpg / png / gif / webp，认不出来就拒收（422）。
 */
public final class KgImageMagic
{
    private KgImageMagic()
    {
    }

    /** 按文件头认图片类型：jpg / png / gif / webp，认不出来返回 null */
    public static String detect(byte[] h, int n)
    {
        if (h == null)
        {
            return null;
        }
        if (n >= 3 && (h[0] & 0xff) == 0xFF && (h[1] & 0xff) == 0xD8 && (h[2] & 0xff) == 0xFF)
        {
            return "jpg";
        }
        if (n >= 8 && (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A)
        {
            return "png";
        }
        if (n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8' && (h[4] == '7' || h[4] == '9') && h[5] == 'a')
        {
            return "gif";
        }
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P')
        {
            return "webp";
        }
        return null;
    }

    /** 读文件头认类型；读不到返回 null */
    public static String detect(MultipartFile file)
    {
        byte[] head = new byte[12];
        int n = 0;
        try (InputStream in = file.getInputStream())
        {
            while (n < head.length)
            {
                int r = in.read(head, n, head.length - n);
                if (r < 0)
                {
                    break;
                }
                n += r;
            }
        }
        catch (IOException e)
        {
            return null;
        }
        return detect(head, n);
    }

    /**
     * 文件头必须是 allowed 里的一种图片（jpeg 按 jpg 算），否则 422。返回认出来的类型。
     * 扩展名和文件头不一致但都是允许的图片（png 存成了 .jpg）照样收：浏览器、小程序都按内容解码，不影响显示，
     * 拒收反倒会挡住手机相册里改过后缀的正常照片
     */
    public static String require(MultipartFile file, String... allowed)
    {
        String type = detect(file);
        if (type != null)
        {
            for (String a : allowed)
            {
                if (type.equals("jpeg".equals(a) ? "jpg" : a))
                {
                    return type;
                }
            }
        }
        throw new ServiceException("文件内容不是有效的图片，请重新选择", 422);
    }
}
