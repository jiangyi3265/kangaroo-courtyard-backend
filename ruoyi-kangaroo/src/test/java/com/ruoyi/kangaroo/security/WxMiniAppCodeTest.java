package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.kangaroo.config.KangarooProperties;

/** Mock transport only: these images are not official WeChat service evidence. */
class WxMiniAppCodeTest {
    static class Client extends WxMiniAppClient {
        int tokens, calls; final List<String> payloads = new ArrayList<>();
        final List<byte[]> replies = new ArrayList<>(); Exception failure;
        @Override protected String fetchAccessTokenJson() { tokens++; return "{\"access_token\":\"secret-token-"+tokens+"\",\"expires_in\":7200}"; }
        @Override protected byte[] requestCodeImage(String token, String payload) throws Exception {
            calls++; payloads.add(payload); assertTrue(token.startsWith("secret-token-"));
            if (failure != null) throw failure;
            return replies.get(Math.min(calls-1,replies.size()-1));
        }
    }
    private Client client(boolean mock, byte[]... replies) {
        Client c = new Client(); KangarooProperties p = new KangarooProperties();
        p.setWxAppId("test-app");p.setWxAppSecret("secret-app-value");p.setWxMockEnabled(mock);
        ReflectionTestUtils.setField(c,"properties",p);c.replies.addAll(Arrays.asList(replies));return c;
    }
    private byte[] image(String format, int size) throws Exception {
        BufferedImage image = new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,out));return out.toByteArray();
    }
    private byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test void scenePreservesLongIdAndBothExistingTokenLengths() {
        assertEquals("a_1y2p0ij32e8e7",WxMiniAppClient.activityScene(Long.MAX_VALUE,null));
        assertEquals(32,WxMiniAppClient.activityScene(Long.MAX_VALUE,"0123456789abcdef").length());
        assertEquals("c_1_0123456789ab",WxMiniAppClient.activityScene(1L,"0123456789ab"));
    }
    @Test void invalidIdsAndTokensNeverReachTransport() {
        Client c=client(false);
        for(Long id:Arrays.asList(null,0L,-1L)) assertEquals("error",c.activityCode(id,null).get("state"));
        for(String token:Arrays.asList("","x","0123456789ab%","0123456789ABCDEf","../../anything")) assertEquals("error",c.activityCode(1L,token).get("state"));
        assertEquals(0,c.calls);assertEquals(0,c.tokens);
    }
    @Test void mockIsExplicitAndCannotBeMistakenForScanImage() {
        Client c=client(true);Map<String,Object> r=c.activityCode(1L,null);
        assertEquals("mock",r.get("state"));assertEquals("",r.get("image"));assertTrue(r.get("message").toString().contains("未调用微信"));assertEquals(0,c.tokens);
    }
    @Test void missingConfigurationDoesNotProduceFallbackImage() {
        Client c=client(false);KangarooProperties p=new KangarooProperties();ReflectionTestUtils.setField(c,"properties",p);
        assertEquals("error",c.activityCode(1L,null).get("state"));assertEquals(0,c.tokens);
    }
    @Test void officialRequestIsFixedPageExplicitEnvironmentAndBinaryPng() throws Exception {
        Client c=client(false,image("png",430));Map<String,Object> r=c.activityCode(15L,null);
        assertEquals("ready",r.get("state"));assertTrue(r.get("image").toString().startsWith("data:image/png;base64,"));
        JSONObject sent=JSON.parseObject(c.payloads.get(0));assertEquals("pages/community/activity",sent.getString("page"));assertEquals("a_f",sent.getString("scene"));
        assertEquals("release",sent.getString("env_version"));assertEquals(Boolean.FALSE,sent.getBoolean("check_path"));assertEquals(430,sent.getIntValue("width"));
        assertFalse(JSON.toJSONString(r).contains("secret-"));assertFalse(c.payloads.get(0).contains("access_token"));
    }
    @Test void jpegIsDecodedAndNormalizedToPng() throws Exception {
        assertTrue(client(false,image("jpeg",430)).activityCode(1L,null).get("image").toString().startsWith("data:image/png;base64,iVBOR"));
    }
    @Test void failureJsonIncludingZeroAndMissingErrorNeverClaimsSuccess() {
        for(String body:Arrays.asList("{\"errcode\":40097,\"errmsg\":\"secret-token\"}","{\"errcode\":0}","{}","{\"errcode\":\"0\"}")) {
            Map<String,Object> r=client(false,json(body)).activityCode(1L,null);assertEquals("error",r.get("state"));assertEquals("",r.get("image"));assertFalse(JSON.toJSONString(r).contains("secret-token"));
        }
    }
    @Test void invalidCredentialRefreshesOnceAndOnlyOnce() throws Exception {
        Client c=client(false,json("{\"errcode\":40001}"),image("png",430));assertEquals("ready",c.activityCode(1L,null).get("state"));assertEquals(2,c.calls);assertEquals(2,c.tokens);
        c=client(false,json("{\"errcode\":42001}"));assertEquals("error",c.activityCode(1L,null).get("state"));assertEquals(2,c.calls);
    }
    @Test void emptyMalformedNonImageAndOversizedBodiesFailClosed() {
        for(byte[] body:Arrays.asList(new byte[0],json("<html>gateway</html>"),json("{bad"),new byte[2*1024*1024+1])) {
            Map<String,Object> r=client(false,body).activityCode(1L,null);assertEquals("error",r.get("state"));assertEquals("",r.get("image"));
        }
    }
    @Test void oversizedOrUnsupportedImageDimensionsFailClosed() throws Exception {
        assertEquals("error",client(false,image("png",1290)).activityCode(1L,null).get("state"));
        assertEquals("error",client(false,image("gif",430)).activityCode(1L,null).get("state"));
    }
    @Test void transportTimeoutDoesNotLeakCredentialsOrRetryUnknownOutcome() {
        Client c=client(false);c.failure=new java.net.SocketTimeoutException("https://api.weixin.qq.com/?access_token=secret-token-1");
        Map<String,Object> r=c.activityCode(1L,null);assertEquals("error",r.get("state"));assertEquals(1,c.calls);assertFalse(JSON.toJSONString(r).contains("secret-token"));
    }
    @Test void cacheIsBoundToSceneEnvironmentAndNotExposedToCallerMutation() throws Exception {
        Client c=client(false,image("png",430));Map<String,Object> r=c.activityCode(1L,null);r.put("image","bad");
        assertTrue(c.activityCode(1L,null).get("image").toString().startsWith("data:image/png"));assertEquals(1,c.calls);
        KangarooProperties p=(KangarooProperties)ReflectionTestUtils.getField(c,"properties");p.setWxCodeEnv("trial");
        assertEquals("trial",c.activityCode(1L,null).get("envVersion"));assertEquals(2,c.calls);
        c.activityCode(2L,null);assertEquals(3,c.calls);
    }
    @Test void failedResponseIsNotCachedAsSuccess() throws Exception {
        Client c=client(false,json("{}"),image("png",430));assertEquals("error",c.activityCode(1L,null).get("state"));assertEquals("ready",c.activityCode(1L,null).get("state"));assertEquals(2,c.calls);
    }
    @Test void invalidEnvironmentCannotReachOfficialTransport() {
        Client c=client(false);((KangarooProperties)ReflectionTestUtils.getField(c,"properties")).setWxCodeEnv("other");assertEquals("error",c.activityCode(1L,null).get("state"));assertEquals(0,c.tokens);
    }
    // 2026-11-03 主人：签到二维码生成失败。check_path=true 时没有正式版就 41030；现在版本跟着前端走，check_path=false
    @Test void clientEnvironmentIsWhitelistedAndPathCheckIsOff() throws Exception {
        Client c=client(false,image("png",430));Map<String,Object> r=c.activityCode(1L,"0123456789abcdef","trial");
        assertEquals("ready",r.get("state"));assertEquals("trial",r.get("envVersion"));JSONObject sent=JSON.parseObject(c.payloads.get(0));
        assertEquals("trial",sent.getString("env_version"));assertEquals(Boolean.FALSE,sent.getBoolean("check_path"));assertEquals("c_1_0123456789abcdef",sent.getString("scene"));
        c.activityCode(1L,"0123456789abcdef","develop");assertEquals("develop",JSON.parseObject(c.payloads.get(1)).getString("env_version"));
        for(String bad:Arrays.asList("../x","RELEASE","","prod",null)){Map<String,Object> x=client(false,image("png",430)).activityCode(1L,null,bad);assertEquals("release",x.get("envVersion"));}
    }
    @Test void pageMissingErrorExplainsWithoutDuplicateRetryText() {
        Map<String,Object> r=client(false,json("{\"errcode\":41030,\"errmsg\":\"invalid page\"}")).activityCode(1L,null,"release");
        assertEquals("error",r.get("state"));assertEquals(41030,r.get("errcode"));assertTrue(r.get("message").toString().contains("正式版"));
        for(Integer e:Arrays.asList(null,41030,45009,40001,40097,85096,12345)) assertFalse(WxMiniAppClient.codeErrorMessage(e).contains("重试"));
    }
}
