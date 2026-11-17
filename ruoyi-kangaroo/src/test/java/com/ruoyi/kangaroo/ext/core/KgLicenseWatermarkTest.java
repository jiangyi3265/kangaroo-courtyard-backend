package com.ruoyi.kangaroo.ext.core;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.admin.KgStoreProfileController;

class KgLicenseWatermarkTest
{
    @TempDir Path temp;
    String previousProfile;
    @BeforeEach void setup() { previousProfile = RuoYiConfig.getProfile(); new RuoYiConfig().setProfile(temp.resolve("profile").toString()); }
    @AfterEach void cleanup() { new RuoYiConfig().setProfile(previousProfile); }

    private static MockMultipartFile file(String name, byte[] bytes) { return new MockMultipartFile("file", name, "application/octet-stream", bytes); }

    private static byte[] image(String format) throws Exception
    {
        BufferedImage image = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 1200, 900);
        g.setColor(Color.DARK_GRAY); g.setFont(new Font("SansSerif", Font.BOLD, 38));
        g.drawString("DISPLAY TEST - NO REAL BUSINESS DATA", 60, 95);
        g.setFont(new Font("SansSerif", Font.PLAIN, 26));
        for (int i = 0; i < 8; i++) g.drawString("Field " + (i + 1) + ": Sample document for watermark verification", 70, 180 + 75 * i);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, format, out); return out.toByteArray();
    }

    private static void checkMarks(BufferedImage image)
    {
        int[] quadrants = new int[4];
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
        {
            Color p = new Color(image.getRGB(x, y));
            if (p.getRed() - p.getGreen() > 12 && p.getRed() - p.getBlue() > 12)
                quadrants[(x >= image.getWidth() / 2 ? 1 : 0) + (y >= image.getHeight() / 2 ? 2 : 0)]++;
        }
        for (int count : quadrants) assertTrue(count > 500, "Repeated watermark must be baked into each quadrant");
    }

    private static void artifact(String name, byte[] bytes) throws Exception
    {
        String dir = System.getProperty("kg.watermark.auditDir");
        if (dir != null) { Path output = Paths.get(dir); Files.createDirectories(output); Files.write(output.resolve(name), bytes); }
    }

    @Test void jpgAndPngAreReencodedWithRepeatedVisibleWatermarks() throws Exception
    {
        for (String format : new String[] { "jpg", "png" })
        {
            byte[] original = image(format);
            KgLicenseWatermark.Prepared result = KgLicenseWatermark.prepare(file("证照." + format, original), false);
            assertEquals("jpg", result.extension);
            assertEquals("证照.jpg", result.name);
            BufferedImage marked = ImageIO.read(new ByteArrayInputStream(result.bytes));
            assertEquals(1200, marked.getWidth()); assertEquals(900, marked.getHeight());
            checkMarks(marked);
            assertFalse(java.util.Arrays.equals(original, result.bytes));
            artifact("watermark-" + format + ".jpg", result.bytes);
        }
    }

    private static byte[] pdf(int pages, boolean encrypted) throws Exception
    {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream())
        {
            for (int i = 0; i < pages; i++)
            {
                PDPage page = new PDPage(PDRectangle.LETTER); if (i % 2 == 1) page.setRotation(90); doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page))
                {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 20);
                    content.newLineAtOffset(50, 650); content.showText("ORIGINAL DOCUMENT PAGE " + (i + 1)); content.endText();
                }
            }
            if (encrypted) doc.protect(new StandardProtectionPolicy("owner-secret", "reader-secret", new AccessPermission()));
            doc.save(bytes); return bytes.toByteArray();
        }
    }

    @Test void everyPdfPageIsFlattenedWithWatermarkAndRotationPreserved() throws Exception
    {
        KgLicenseWatermark.Prepared marked = KgLicenseWatermark.prepare(file("license.pdf", pdf(2, false)), true);
        assertEquals("pdf", marked.extension);
        try (PDDocument output = Loader.loadPDF(marked.bytes))
        {
            assertEquals(2, output.getNumberOfPages());
            assertEquals("", new PDFTextStripper().getText(output).trim(), "No original removable text layer remains");
            assertTrue(output.getPage(0).getMediaBox().getHeight() > output.getPage(0).getMediaBox().getWidth());
            assertTrue(output.getPage(1).getMediaBox().getWidth() > output.getPage(1).getMediaBox().getHeight());
            PDFRenderer renderer = new PDFRenderer(output);
            for (int i = 0; i < 2; i++)
            {
                BufferedImage image = renderer.renderImageWithDPI(i, 100); checkMarks(image);
                ByteArrayOutputStream shot = new ByteArrayOutputStream(); ImageIO.write(image, "png", shot);
                artifact("pdf-page-" + (i + 1) + ".png", shot.toByteArray());
            }
        }
        artifact("watermarked.pdf", marked.bytes);
    }

    @Test void invalidOrUnboundedInputsAreRejectedBeforeAnyFileIsSaved() throws Exception
    {
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.savePublic(file("bad.jpg", "<svg/>".getBytes())));
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.prepare(file("big.png", new byte[10 * 1024 * 1024 + 1]), true));
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.prepare(file("x.gif", image("png")), true));
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.prepare(file("x.pdf", pdf(1, false)), false));
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.prepare(file("x.pdf", pdf(11, false)), true));
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.prepare(file("x.pdf", pdf(1, true)), true));
        assertFalse(Files.exists(temp.resolve("profile/upload/licenses")));
    }

    @Test void publicEndpointReturnsOnlyMarkedFileAndPrivateStorageUsesTransformedSize() throws Exception
    {
        byte[] source = image("png");
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new KgStoreProfileController()).build();
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/kangaroo/store/profile/license/upload").file(file("营业执照.png", source)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        assertEquals(200, body.path("code").asInt());
        String url = body.path("fileName").asText();
        assertEquals(url, body.path("url").asText());
        KgLicenseWatermark.requirePublic(url);
        Path publicFile = Paths.get(RuoYiConfig.getProfile(), url.substring("/profile/".length()));
        checkMarks(ImageIO.read(publicFile.toFile()));
        try (java.util.stream.Stream<Path> paths = Files.walk(temp.resolve("profile"))) { assertEquals(1, paths.filter(Files::isRegularFile).count()); }

        KgPrivateFiles privateFiles = new KgPrivateFiles("test-license-file-sign-secret");
        Map<String, Object> stored = privateFiles.saveLicense(file("营业执照.png", source));
        java.io.File storedFile = privateFiles.resolve((String) stored.get("key"));
        assertNotNull(storedFile); checkMarks(ImageIO.read(storedFile));
        assertEquals(storedFile.length(), stored.get("size"));
        assertEquals("营业执照.jpg", stored.get("name"));
        assertFalse(storedFile.toPath().startsWith(temp.resolve("profile")));
        Map<String, Object> storedPdf = privateFiles.saveLicense(file("license.pdf", pdf(1, false)));
        assertTrue(((String) storedPdf.get("key")).endsWith(".pdf"));
        try (PDDocument markedPdf = Loader.loadPDF(privateFiles.resolve((String) storedPdf.get("key"))))
        {
            assertEquals("", new PDFTextStripper().getText(markedPdf).trim());
            checkMarks(new PDFRenderer(markedPdf).renderImageWithDPI(0, 100));
        }
        Map<String, Object> untouched = privateFiles.save(file("other.png", source), "qual");
        assertArrayEquals(source, Files.readAllBytes(privateFiles.resolve((String) untouched.get("key")).toPath()), "Other qualification types are unchanged");
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.requirePublic("/profile/upload/unmarked.jpg"));
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.requirePublic(url.replace("/licenses/", "/licenses/../")));
        Files.delete(publicFile);
        assertThrows(ServiceException.class, () -> KgLicenseWatermark.requirePublic(url));
    }
}
