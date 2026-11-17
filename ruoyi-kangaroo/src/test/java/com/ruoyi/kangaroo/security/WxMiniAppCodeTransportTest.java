package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** Exercises real transport code with an in-memory connection, never calls WeChat. */
class WxMiniAppCodeTransportTest {
    static class Connection extends HttpURLConnection {
        byte[] response="{}".getBytes(StandardCharsets.UTF_8); int status=200; long declared=-1;
        boolean disconnected, inputClosed, outputClosed; int inputCalls;
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        Connection() throws Exception { super(new URL("https://api.weixin.qq.com/")); }
        public void disconnect(){disconnected=true;} public boolean usingProxy(){return false;} public void connect(){}
        public int getResponseCode(){return status;} public long getContentLengthLong(){return declared;}
        public OutputStream getOutputStream(){return new FilterOutputStream(sent){public void close() throws IOException {outputClosed=true;super.close();}};}
        public InputStream getInputStream(){inputCalls++;return new ByteArrayInputStream(response){public void close(){inputClosed=true;}};}
    }
    static class Client extends WxMiniAppClient {
        final Connection connection; URL requested;
        Client(Connection c){connection=c;}
        @Override protected HttpURLConnection openCodeConnection(URL u){requested=u;return connection;}
    }
    @Test void fixedOfficialPostDoesNotFollowRedirectAndClosesBothStreams() throws Exception {
        Connection c=new Connection();Client client=new Client(c);
        assertArrayEquals(c.response,client.requestCodeImage("secret +&?", "{\"scene\":\"a_1\"}"));
        assertEquals("https",client.requested.getProtocol());assertEquals("api.weixin.qq.com",client.requested.getHost());
        assertEquals("/wxa/getwxacodeunlimit",client.requested.getPath());assertEquals("access_token=secret+%2B%26%3F",client.requested.getQuery());
        assertEquals("POST",c.getRequestMethod());assertFalse(c.getInstanceFollowRedirects());assertEquals(3000,c.getConnectTimeout());assertEquals(5000,c.getReadTimeout());
        assertTrue(c.outputClosed&&c.inputClosed&&c.disconnected);assertFalse(c.sent.toString("UTF-8").contains("secret"));
    }
    @Test void redirectAndHttpFailureDoNotConsumeBody() throws Exception {
        for(int status:new int[]{302,400,500}) {Connection c=new Connection();c.status=status;
            assertThrows(IOException.class,()->new Client(c).requestCodeImage("secret","{}"));assertEquals(0,c.inputCalls);assertTrue(c.disconnected);}
    }
    @Test void advertisedOversizeIsRejectedBeforeReadAndChunkedOversizeIsClosed() throws Exception {
        Connection c=new Connection();c.declared=2*1024*1024+1;
        assertThrows(IOException.class,()->new Client(c).requestCodeImage("secret","{}"));assertEquals(0,c.inputCalls);assertTrue(c.disconnected);
        Connection chunked=new Connection();chunked.response=new byte[2*1024*1024+1];
        assertThrows(IOException.class,()->new Client(chunked).requestCodeImage("secret","{}"));assertTrue(chunked.inputClosed&&chunked.disconnected);
    }
    @Test void exceptionAndJsonErrorLogsNeverContainCredentialsResponseOrScene() {
        Logger logger=(Logger)LoggerFactory.getLogger(WxMiniAppClient.class);ListAppender<ILoggingEvent> app=new ListAppender<>();app.start();logger.addAppender(app);
        try {
            WxMiniAppCodeTest.Client c=new WxMiniAppCodeTest.Client();com.ruoyi.kangaroo.config.KangarooProperties p=new com.ruoyi.kangaroo.config.KangarooProperties();p.setWxAppId("private-app");p.setWxAppSecret("private-secret");
            org.springframework.test.util.ReflectionTestUtils.setField(c,"properties",p);
            c.failure=new SocketTimeoutException("access_token=secret-token-1 private-secret c_1_0123456789ab");c.activityCode(1L,"0123456789ab");
            c.failure=null;c.replies.add("{\"errcode\":40097,\"errmsg\":\"private-secret secret-token-1\"}".getBytes(StandardCharsets.UTF_8));c.activityCode(2L,null);
            assertFalse(app.list.isEmpty());for(ILoggingEvent event:app.list){String m=event.getFormattedMessage();assertFalse(m.contains("private-"));assertFalse(m.contains("secret-token"));assertFalse(m.contains("0123456789ab"));assertNull(event.getThrowableProxy());}
        } finally {logger.detachAppender(app);app.stop();}
    }
}
