package com.ruoyi.kangaroo.ext.stay;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.EnumMap;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * 核销码与订单号。
 *
 * 核销码是 10 位数字：前台可以扫二维码 / 条形码，也可以手输。图片在服务端用 zxing 生成后以 base64 内联下发
 * （image 组件请求图片时带不了 X-App-Token，不能给一个要鉴权的图片地址）。
 */
public final class StayCodes
{
    private static final SecureRandom RND = new SecureRandom();

    private StayCodes()
    {
    }

    /** 订单号：前缀 + yyMMddHHmmss + 4 位随机 */
    public static String orderNo(String prefix)
    {
        return prefix + new SimpleDateFormat("yyMMddHHmmss").format(new Date()) + String.format("%04d", RND.nextInt(10000));
    }

    /** 10 位数字核销码，首位不为 0 */
    public static String verifyCode()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(1 + RND.nextInt(9));
        for (int i = 0; i < 9; i++)
        {
            sb.append(RND.nextInt(10));
        }
        return sb.toString();
    }

    public static String qrDataUri(String text)
    {
        return image(text, BarcodeFormat.QR_CODE, 240, 240);
    }

    public static String barDataUri(String text)
    {
        return image(text, BarcodeFormat.CODE_128, 480, 120);
    }

    private static String image(String text, BarcodeFormat format, int w, int h)
    {
        try
        {
            Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            if (format == BarcodeFormat.QR_CODE)
            {
                hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            }
            BitMatrix m = new MultiFormatWriter().encode(text, format, w, h, hints);
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < m.getWidth(); x++)
            {
                for (int y = 0; y < m.getHeight(); y++)
                {
                    img.setRGB(x, y, m.get(x, y) ? 0x241B17 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        }
        catch (Exception e)
        {
            // 生成失败时页面仍然显示数字码，前台可以手输
            return null;
        }
    }
}
