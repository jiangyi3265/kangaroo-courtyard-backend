package com.ruoyi.kangaroo.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class KangarooSecurityErrorConfigTest {
    @Test void rejectedInputReturns400BeforeMvcWithoutInstallingAnotherFirewall() throws Exception {
        WebSecurity web=new WebSecurity(new ObjectPostProcessor<Object>() {
            public <O> O postProcess(O object) { return object; }
        });
        Object firewall=ReflectionTestUtils.getField(web,"httpFirewall");
        Object ignored=ReflectionTestUtils.getField(web,"ignoredRequests");
        new KangarooSecurityErrorConfig().kangarooRequestRejection().customize(web);
        assertSame(firewall,ReflectionTestUtils.getField(web,"httpFirewall"));
        assertSame(ignored,ReflectionTestUtils.getField(web,"ignoredRequests"));
        assertTrue(((java.util.List<?>)ignored).isEmpty());
        RequestRejectedHandler handler=(RequestRejectedHandler)ReflectionTestUtils.getField(web,"requestRejectedHandler");
        assertNotNull(handler);
        MockHttpServletResponse response=new MockHttpServletResponse();
        handler.handle(new MockHttpServletRequest(),response,new RequestRejectedException("blocked input"));
        assertEquals(400,response.getStatus());assertTrue(response.getContentAsByteArray().length==0);
    }
}
