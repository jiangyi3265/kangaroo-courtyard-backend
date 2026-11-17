package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.kangaroo.security.WxMiniAppClient.SubscribeDelivery.State.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import com.ruoyi.kangaroo.config.KangarooProperties;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

/** In-memory HTTP transport, no sockets, credentials or real WeChat requests. */
class WxSubscribeTransportTest
{
    private static int status, posts, tokenCalls;
    private static String body;
    private WxMiniAppClient client;
    @BeforeAll static void transport() {
        URL.setURLStreamHandlerFactory(protocol -> {
            if(!protocol.equals("https") && !protocol.equals("http")) return null;
            return new URLStreamHandler() {
                protected URLConnection openConnection(URL url) {
                    return new HttpURLConnection(url) {
                        public void connect() {} public void disconnect() {} public boolean usingProxy(){return false;}
                        public OutputStream getOutputStream(){posts++;return new ByteArrayOutputStream();}
                        public int getResponseCode(){if(url.getPath().endsWith("/token"))tokenCalls++;return status;}
                        public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));}
                        public InputStream getErrorStream(){return getInputStream();}
                    };
                }
            };
        });
    }
    @BeforeEach void setup() {
        client=new WxMiniAppClient(); KangarooProperties props=mock(KangarooProperties.class);
        when(props.isWxConfigured()).thenReturn(true); when(props.getWxAppId()).thenReturn("synthetic-app"); when(props.getWxAppSecret()).thenReturn("synthetic-secret");
        ReflectionTestUtils.setField(client,"properties",props); ReflectionTestUtils.setField(client,"accessToken","synthetic-token");
        ReflectionTestUtils.setField(client,"accessTokenExpireAt",Long.MAX_VALUE);
        status=200; body="{\"errcode\":0}";posts=0;tokenCalls=0;
    }
    private WxMiniAppClient.SubscribeDelivery send() {return client.deliverSubscribeMessage("synthetic-user","synthetic-template","pages/home/index",Collections.emptyMap());}
    @Test void explicitHttpAndBusinessSuccessRequired() {
        assertEquals(SENT,send().state);
        for(int code:new int[]{500,503}) {status=code; assertEquals(UNKNOWN,send().state);}
    }
    @Test void malformedOrMissingBusinessResultNeverCountsAsSent() {
        for(String value:new String[]{"{}","","null","{\"errcode\":\"0\"}","{\"errcode\":0.5}"}) {body=value;assertEquals(UNKNOWN,send().state,value);}
        body="{\"errcode\":43101}"; assertEquals(REJECTED,send().state);
    }
    @Test void tokenFailureIsKnownNotAttempted() {
        ReflectionTestUtils.setField(client,"accessToken",null); body="{\"errcode\":40001}";
        assertEquals(NOT_ATTEMPTED,send().state);assertEquals(1,tokenCalls);assertEquals(0,posts);
    }
}
