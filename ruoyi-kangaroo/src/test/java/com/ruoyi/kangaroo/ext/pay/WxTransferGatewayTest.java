package com.ruoyi.kangaroo.ext.pay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.util.GsonUtil;

class WxTransferGatewayTest {
    private static final String KEY = "01234567890123456789012345678901";
    private static final String SERIAL = "PUB_KEY_ID_TEST";
    private WxPayGateway pay;
    private KeyPair platform;

    @BeforeEach void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        platform = generator.generateKeyPair();
        KeyPair merchant = generator.generateKeyPair();
        RSAPublicKeyConfig config = new RSAPublicKeyConfig.Builder().merchantId("1234567890")
                .merchantSerialNumber("0123456789ABCDEF").privateKey(merchant.getPrivate())
                .publicKey(platform.getPublic()).publicKeyId(SERIAL).apiV3Key(KEY).build();
        KangarooProperties properties = mock(KangarooProperties.class);
        when(properties.getPayMode()).thenReturn("off");
        pay = new WxPayGateway(properties, new MockEnvironment());
        ReflectionTestUtils.setField(pay, "config", config);
        ReflectionTestUtils.setField(pay, "client", mock(HttpClient.class));
        ReflectionTestUtils.setField(pay, "parser", new NotificationParser(config));
    }

    private String body(String event) throws Exception {
        String plain = GsonUtil.getGson().toJson(p("out_bill_no", "KW012345678901234567890123456789", "state", "SUCCESS", "transfer_amount", 20000));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"), new GCMParameterSpec(128, "123456789012".getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD("mch_payment".getBytes(StandardCharsets.UTF_8));
        return GsonUtil.getGson().toJson(p("event_type", event, "resource", p("algorithm", "AEAD_AES_256_GCM", "original_type", "mch_payment",
                "associated_data", "mch_payment", "nonce", "123456789012", "ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8))))));
    }

    private String sign(String body, String time) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(platform.getPrivate());
        signer.update((time + "\nnonce\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    @Test void transferNotificationRequiresValidSignatureTimestampAndEvent() throws Exception {
        String body = body("MCHTRANSFER.BILL.FINISHED");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(body, timestamp);
        assertEquals("SUCCESS", pay.transferNotification(body, SERIAL, timestamp, "nonce", signature).get("state"));
        assertThrows(ServiceException.class, () -> pay.transferNotification(body + " ", SERIAL, timestamp, "nonce", signature));
        assertThrows(ServiceException.class, () -> pay.transferNotification(body, SERIAL, "1", "nonce", sign(body, "1")));
        String wrongEvent = body("TRANSACTION.SUCCESS");
        assertThrows(ServiceException.class, () -> pay.transferNotification(wrongEvent, SERIAL, timestamp, "nonce", sign(wrongEvent, timestamp)));
        assertThrows(ServiceException.class, () -> pay.notification(body, SERIAL, timestamp, "nonce", signature, false));
    }

    @Test void recipientNameIsEncryptedUsingWechatPublicKey() throws Exception {
        String encrypted = pay.encryptTransferName("测试姓名");
        assertFalse(encrypted.contains("测试姓名"));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, platform.getPrivate());
        assertEquals("测试姓名", new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8));
    }

    @Test void transferIsDisabledUntilApprovedSceneIsConfigured() {
        WxPayGateway channel = mock(WxPayGateway.class);
        when(channel.enabled()).thenReturn(true);
        when(channel.storeIds()).thenReturn(Collections.singleton(1L));
        WxTransferGateway disabled = new WxTransferGateway(channel, new MockEnvironment());
        assertFalse(disabled.enabled(1L));
        assertThrows(ServiceException.class, () -> disabled.requireEnabled(1L));
        assertThrows(IllegalStateException.class, () -> new WxTransferGateway(channel,
                new MockEnvironment().withProperty("kangaroo.pay.wechat.transfer.enabled", "true")));
        MockEnvironment env = new MockEnvironment().withProperty("kangaroo.pay.wechat.transfer.enabled", "true")
                .withProperty("kangaroo.pay.wechat.transfer.scene-id", "1005")
                .withProperty("kangaroo.pay.wechat.transfer.scene-report-infos", "[{\"info_type\":\"测试场景\",\"info_content\":\"测试报备信息\"}]");
        assertTrue(new WxTransferGateway(channel, env).enabled(1L));
        assertFalse(new WxTransferGateway(channel, env).enabled(2L));
    }
}
