package com.ruoyi.kangaroo.ext.core;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;

/** 营业执照上传时烧入水印，公开目录和私有目录均不落盘未处理的原件。 */
public final class KgLicenseWatermark
{
    public static final String TEXT = "仅用于袋鼠认证";
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 24000000L;
    private static final String PUBLIC_PREFIX = "/profile/upload/licenses/";

    private KgLicenseWatermark() {}

    public static final class Prepared
    {
        public final byte[] bytes;
        public final String extension;
        public final String name;

        private Prepared(byte[] bytes, String extension, String originalName)
        {
            this.bytes = bytes;
            this.extension = extension;
            int dot = originalName.lastIndexOf('.');
            this.name = (dot > 0 ? originalName.substring(0, dot) : "营业执照") + "." + extension;
        }
    }

    public static Prepared prepare(MultipartFile file, boolean allowPdf)
    {
        if (file == null || file.isEmpty()) throw invalid("请选择营业执照文件");

        String name = file.getOriginalFilename() == null ? "license" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean pdf = lower.endsWith(".pdf");
        if (pdf && !allowPdf) throw invalid("营业执照展示只支持 JPG / PNG 图片");
        if (!pdf && !lower.matches(".*\\.(jpg|jpeg|png)$")) throw invalid("营业执照只支持 JPG / PNG" + (allowPdf ? " / PDF" : ""));
        try
        {
            long limit = pdf ? MAX_BYTES : 20L * 1024 * 1024;
            if (file.getSize() > limit) throw invalid(pdf ? "PDF不能超过10MB" : "图片不能超过20MB");
            byte[] source = file.getBytes();
            if (source.length > limit) throw invalid("文件超过容量限制");
            byte[] result;
            if (pdf) result = pdf(source);
            else
            {
                KgImageMagic.require(file, "jpg", "png");
                BufferedImage marked = stamp(readImage(source));
                try { result = jpeg(marked); }
                finally { marked.flush(); }
            }
            if (result.length > limit) throw invalid("加水印后文件过大，请降低图片分辨率后重新上传");
            return new Prepared(result, pdf ? "pdf" : "jpg", name);
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw invalid("营业执照处理失败，请上传清晰、未加密的 JPG / PNG" + (allowPdf ? " / PDF" : "") + " 文件"); }
    }

    /** 与后台 ImageUpload 的 fileName 回包约定配合；随机文件名不暴露证照信息。 */
    public static String savePublic(MultipartFile file)
    {
        if (file != null && file.getSize() > 20L * 1024 * 1024) throw invalid("营业执照图片不能超过 20MB");
        Prepared marked = prepare(file, false);
        String key = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "/" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
        Path target = Paths.get(RuoYiConfig.getUploadPath(), "licenses", key);
        try
        {
            Files.createDirectories(target.getParent());
            Files.write(target, marked.bytes);
            return PUBLIC_PREFIX + key;
        }
        catch (Exception e)
        {
            try { Files.deleteIfExists(target); } catch (Exception ignored) { }
            throw invalid("营业执照保存失败，请重试");
        }
    }

    /** 新绑定的执照必须来自专用上传入口，不能把通用图片 URL 作为无水印执照保存。 */
    public static void requirePublic(String url)
    {
        if (url == null || !url.matches("^/profile/upload/licenses/\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.jpg$"))
            throw invalid("请通过营业执照上传入口重新上传，系统会自动添加认证水印");
        Path root = Paths.get(RuoYiConfig.getUploadPath(), "licenses").toAbsolutePath().normalize();
        Path target = root.resolve(url.substring(PUBLIC_PREFIX.length())).normalize();
        try
        {
            if (!target.startsWith(root) || !Files.isRegularFile(target) || !target.toRealPath().startsWith(root.toRealPath()))
                throw invalid("营业执照文件不存在，请重新上传");
        }
        catch (java.io.IOException e) { throw invalid("营业执照文件不存在，请重新上传"); }
    }

    private static BufferedImage readImage(byte[] bytes) throws Exception
    {
        try (ImageInputStream input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)))
        {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("营业执照图片损坏，请重新选择");
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(input, true, true);
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                return (bytes[0] & 255) == 255 ? com.ruoyi.kangaroo.ext.community.KgChatImage.orientJpeg(image, bytes) : image;
            }
            finally { reader.dispose(); }
        }
    }

    private static void checkDimensions(int width, int height)
    {
        if (width < 1 || height < 1 || width > 16000 || height > 16000 || (long) width * height > MAX_PIXELS)
            throw invalid("营业执照图片分辨率过大，请缩小后重新上传");
    }

    private static final class FontHolder
    {
        static final Font FONT = load();
        private static Font load()
        {
            try (InputStream input = KgLicenseWatermark.class.getResourceAsStream("/fonts/kg-license-watermark.ttf"))
            {
                Font font = Font.createFont(Font.TRUETYPE_FONT, input);
                if (font.canDisplayUpTo(TEXT) != -1) throw new IllegalStateException("Missing watermark glyphs");
                return font;
            }
            catch (Exception e) { throw new IllegalStateException("Cannot load license watermark font", e); }
        }
    }

    static BufferedImage stamp(BufferedImage source)
    {
        int width = source.getWidth(), height = source.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        try
        {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float size = Math.max(10f, Math.max(Math.min(width, height) / 17f, Math.max(width, height) / 60f));
            g.setFont(FontHolder.FONT.deriveFont(size));
            g.setColor(new Color(150, 45, 38));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .28f));
            g.rotate(Math.toRadians(-28), width / 2.0, height / 2.0);
            int diagonal = (int) Math.ceil(Math.hypot(width, height));
            int stepX = Math.max(1, Math.round(g.getFontMetrics().stringWidth(TEXT) + size * 2));
            int stepY = Math.max(1, Math.round(size * 3.7f));
            for (int y = -diagonal; y <= diagonal * 2; y += stepY)
                for (int x = -diagonal; x <= diagonal * 2; x += stepX) g.drawString(TEXT, x, y);
        }
        finally { g.dispose(); source.flush(); }
        return result;
    }

    private static byte[] jpeg(BufferedImage image) throws Exception
    {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageOutputStream output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes))
        {
            writer.setOutput(output);
            ImageWriteParam options = writer.getDefaultWriteParam();
            options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            options.setCompressionQuality(.94f);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), options);
            output.flush();
            return bytes.toByteArray();
        }
        finally { writer.dispose(); }
    }

    /** PDF 每页先栅格化再加水印，输出中不保留可被提取的未加水印图片或文字层。 */
    private static byte[] pdf(byte[] bytes) throws Exception
    {
        try (PDDocument original = Loader.loadPDF(bytes); PDDocument result = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            if (original.isEncrypted()) throw invalid("营业执照 PDF 不能加密，请重新导出后上传");
            if (original.getNumberOfPages() < 1 || original.getNumberOfPages() > 10) throw invalid("营业执照 PDF 请控制在 1 至 10 页内");
            PDFRenderer renderer = new PDFRenderer(original);
            renderer.setSubsamplingAllowed(true);
            long totalPixels = 0;
            for (int i = 0; i < original.getNumberOfPages(); i++)
            {
                PDRectangle box = original.getPage(i).getCropBox();
                float width = box.getWidth(), height = box.getHeight();
                if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) throw invalid("营业执照 PDF 页面尺寸不正确");
                int w = (int) Math.ceil(width * 150 / 72), h = (int) Math.ceil(height * 150 / 72);
                checkDimensions(w, h);
                totalPixels += (long) w * h;
                if (totalPixels > 60000000L) throw invalid("营业执照 PDF 内容过多，请减少页数后上传");
                BufferedImage image = stamp(renderer.renderImageWithDPI(i, 150, ImageType.RGB));
                try
                {
                    PDPage page = new PDPage(new PDRectangle(image.getWidth() * 72f / 150, image.getHeight() * 72f / 150));
                    result.addPage(page);
                    try (PDPageContentStream content = new PDPageContentStream(result, page))
                    {
                        content.drawImage(JPEGFactory.createFromImage(result, image, .94f), 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                    }
                }
                finally { image.flush(); }
            }
            result.save(output);
            return output.toByteArray();
        }
    }

    private static ServiceException invalid(String message) { return new ServiceException(message, 422); }
}
