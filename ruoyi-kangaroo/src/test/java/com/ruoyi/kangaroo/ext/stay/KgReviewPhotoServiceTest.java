package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgMembershipRewards;

class KgReviewPhotoServiceTest {
    @TempDir Path profile;
    final KgJdbc db = mock(KgJdbc.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final KgReviewAiSettings settings = KgReviewAiClientTest.settings();
    final KgReviewAiClient client = mock(KgReviewAiClient.class);
    final KgMembershipRewards rewards = mock(KgMembershipRewards.class);
    final KgReviewPhotoService service = new KgReviewPhotoService(db, redis, settings, client, rewards);
    String previousProfile;

    @BeforeEach void configureImages() throws Exception {
        previousProfile = RuoYiConfig.getProfile();
        new RuoYiConfig().setProfile(profile.toString());
        Files.createDirectories(profile.resolve("upload"));
    }

    @AfterEach void restoreProfile() {
        new RuoYiConfig().setProfile(previousProfile);
    }

    void enable() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
    }

    Map<String, Object> row(String images) {
        return p("reviewId", 31L, "appUserId", 7L, "bizType", "stay_order", "bizId", 18L,
                "content", "入住三天，房间的床比较硬，电话13812345678，联系test@example.com，详见https://example.com/test", "images", images);
    }

    String photo(String filename, int width, int height) throws Exception {
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", profile.resolve("upload").resolve(filename).toFile()));
        return "/profile/upload/" + filename;
    }

    ObjectNode image(int index) {
        return KgReviewAiClient.JSON.valueToTree(p("index", index, "realPhoto", true, "relevant", true,
                "clear", true, "safe", true, "certain", true));
    }

    JsonNode result(ObjectNode... images) {
        return KgReviewAiClient.JSON.valueToTree(p("images", Arrays.asList(images)));
    }

    @Test void completeSafePhotoPassesAndEveryUnsafeOrUncertainImagePreventsApproval() {
        assertTrue(KgReviewPhotoService.accepted(result(image(1)), 1));
        for (String field : Arrays.asList("realPhoto", "relevant", "clear", "safe", "certain")) {
            ObjectNode failed = image(1);
            failed.put(field, false);
            assertFalse(KgReviewPhotoService.accepted(result(failed), 1), field);
        }
        for (String field : Arrays.asList("safe", "certain")) {
            ObjectNode failed = image(2);
            failed.put(field, false);
            assertFalse(KgReviewPhotoService.accepted(result(image(1), failed), 2), field);
        }
    }

    @Test void responseShapeTypesAndIndicesMustMatchExactly() {
        for (String malformed : Arrays.asList("null", "[]", "{}", "{\"images\":null}",
                "{\"images\":[]}", "{\"images\":[],\"score\":1}")) {
            assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.accepted(KgReviewAiClient.JSON.readTree(malformed), 1));
        }
        ObjectNode missing = image(1);
        missing.remove("safe");
        ObjectNode extra = image(1);
        extra.put("identity", "irrelevant");
        ObjectNode numericBoolean = image(1);
        numericBoolean.put("safe", 1);
        ObjectNode stringBoolean = image(1);
        stringBoolean.put("certain", "true");
        ObjectNode fractional = image(1);
        fractional.put("index", 1.0);
        ObjectNode textIndex = image(1);
        textIndex.put("index", "1");
        for (ObjectNode invalid : Arrays.asList(missing, extra, numericBoolean, stringBoolean, fractional, textIndex, image(0), image(2))) {
            assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.accepted(result(invalid), 1));
        }
        assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.accepted(result(image(1), image(1)), 2));
        assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.accepted(result(), 0));
        assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.accepted(result(image(1)), 10));
    }

    @Test void defaultOffOrMissingProviderOrBudgetDoesNotReadOrSendPhotos() throws Exception {
        assertEquals(false, service.readiness().get("enabled"));
        service.tick();
        service.process(row("/profile/upload/test.png"));
        ReflectionTestUtils.setField(service, "enabled", true);
        settings.apiKey = "";
        service.tick();
        service.process(row("/profile/upload/test.png"));
        settings.apiKey = "test-secret";
        settings.dailyLimit = 0;
        service.tick();
        settings.dailyLimit = 30;
        settings.hourlyLimit = 0;
        service.process(row("/profile/upload/test.png"));
        assertEquals(false, service.readiness().get("ready"));
        verifyNoInteractions(db, redis, client, rewards);
    }

    @Test void schedulerRequiresActualCompletedOrderBeforeSpendingBudget() {
        enable();
        Map<String, Object> review = row("/profile/upload/test.png");
        when(db.list(anyString(), anyMap())).thenReturn(Collections.singletonList(review));
        when(rewards.reviewable(7L, "stay_order", 18L)).thenReturn(false);
        service.tick();
        verify(rewards).reviewable(7L, "stay_order", 18L);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
        verifyNoInteractions(client);
    }

    @Test void distributedBudgetOrDuplicateClaimPreventsProviderCall() throws Exception {
        enable();
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(0L);
        service.process(row("/profile/upload/test.png"));
        verifyNoInteractions(client, rewards);
    }

    @Test void sendsResizedPlatformBytesAndRedactedTextWithExactContentFingerprint() throws Exception {
        enable();
        Map<String, Object> review = row(photo("actual.png", 2048, 1024));
        when(client.request(anyMap())).thenReturn(result(image(1)));
        service.process(review);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(client).request(request.capture());
        JsonNode payload = KgReviewAiClient.JSON.valueToTree(request.getValue());
        assertEquals("configured-model", payload.get("model").asText());
        assertFalse(payload.get("store").booleanValue());
        assertFalse(payload.get("stream").booleanValue());
        String text = payload.at("/messages/1/content/0/text").asText();
        assertFalse(text.contains("13812345678"));
        assertFalse(text.contains("test@example.com"));
        assertFalse(text.contains("https://example.com/test"));
        String data = payload.at("/messages/1/content/1/image_url/url").asText();
        assertTrue(data.startsWith("data:image/jpeg;base64,"));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1))));
        assertEquals(1024, image.getWidth());
        assertEquals(512, image.getHeight());
        verify(rewards).applyAutomaticPhotoResult(31L, KgReviewPhotoService.fingerprint(review), true);
    }

    @Test void invalidFileAndMalformedAssessmentEnterManualReview() throws Exception {
        enable();
        Map<String, Object> remote = row("https://example.test/photo.jpg");
        service.process(remote);
        verify(rewards).applyAutomaticPhotoResult(31L, KgReviewPhotoService.fingerprint(remote), false);
        verifyNoInteractions(client);
        Map<String, Object> review = row(photo("valid.png", 4, 4));
        when(client.request(anyMap())).thenReturn(KgReviewAiClient.JSON.readTree("{\"images\":[]}"));
        service.process(review);
        verify(rewards).applyAutomaticPhotoResult(31L, KgReviewPhotoService.fingerprint(review), false);
    }

    @Test void moreThanNineHistoricalPhotosEnterManualReviewBeforeAnyProviderRequest() throws Exception {
        enable();
        java.util.List<String> photos = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) photos.add(photo("historical-" + i + ".png", 2, 2));
        Map<String, Object> review = row(KgReviewAiClient.JSON.writeValueAsString(photos));
        service.process(review);
        verifyNoInteractions(client);
        verify(rewards).applyAutomaticPhotoResult(31L, KgReviewPhotoService.fingerprint(review), false);
    }

    @Test void providerUnavailableRemainsRetryableWithoutWritingAnApproval() throws Exception {
        enable();
        Map<String, Object> review = row(photo("valid.png", 4, 4));
        when(client.request(anyMap())).thenThrow(new IllegalStateException("provider_rejected"));
        assertThrows(IllegalStateException.class, () -> service.process(review));
        verifyNoInteractions(rewards);
    }

    @Test void imagePathsCannotEscapeUploadsOrReadRemoteResources() {
        for (String path : Arrays.asList("https://example.test/a.jpg", "/profile/upload/../secret.jpg", "/profile/upload/a.jpg?x=1", "/profile/secret.jpg", "C:/secret.jpg")) {
            assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.imageData(path));
        }
        assertThrows(ServiceException.class, () -> KgReviewPhotoService.imageData("/profile/upload/missing.png"));
    }

    @Test void rejectsOversizedCompressedFilesAndPixelDimensionsBeforeDecoding() throws Exception {
        String oversized = photo("large.png", 1, 1);
        try (RandomAccessFile file = new RandomAccessFile(profile.resolve("upload/large.png").toFile(), "rw")) {
            file.setLength(20L * 1024 * 1024 + 1);
        }
        assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.imageData(oversized));
        String dimensions = photo("dimensions.png", 1, 1);
        Path file = profile.resolve("upload/dimensions.png");
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).putInt(16, 9000).putInt(20, 9000);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        Files.write(file, bytes);
        assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.imageData(dimensions));
    }

    @Test void animatedImagesRequireManualReviewInsteadOfInspectingOnlyFirstFrame() throws Exception {
        Path file = profile.resolve("upload/animated.gif");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < 2; i++) writer.writeToSequence(new IIOImage(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), null, null), null);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        assertThrows(IllegalArgumentException.class, () -> KgReviewPhotoService.imageData("/profile/upload/animated.gif"));
    }

    @Test void fingerprintBindsOwnerOrderTextAndImages() {
        Map<String, Object> review = row("/profile/upload/first.png");
        String original = KgReviewPhotoService.fingerprint(review);
        assertEquals(original, KgReviewPhotoService.fingerprint(review));
        for (String field : Arrays.asList("appUserId", "bizType", "bizId", "content", "images")) {
            Map<String, Object> changed = new java.util.LinkedHashMap<>(review);
            changed.put(field, "different");
            assertNotEquals(original, KgReviewPhotoService.fingerprint(changed), field);
        }
    }
}
