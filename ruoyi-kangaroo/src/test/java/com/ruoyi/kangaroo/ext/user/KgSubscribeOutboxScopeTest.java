package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URLDecoder;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.ServiceCityMessageScope;
import com.ruoyi.kangaroo.security.StaffGrantView;

class KgSubscribeOutboxScopeTest
{
    KgJdbc db=mock(KgJdbc.class);
    KgSubscribeVisibility visibility=mock(KgSubscribeVisibility.class);
    KgSubscribeOutbox outbox=new KgSubscribeOutbox();
    @BeforeEach void setup()
    {
        KangarooProperties properties=mock(KangarooProperties.class);
        when(properties.getSubscribeMode()).thenReturn("wechat");
        ReflectionTestUtils.setField(outbox,"db",db);
        ReflectionTestUtils.setField(outbox,"properties",properties);
        ReflectionTestUtils.setField(outbox,"visibility",visibility);
        when(db.count(anyString(),anyMap())).thenReturn(1L);
        when(db.update(anyString(),anyMap())).thenReturn(1);
        when(db.one(anyString(),anyMap())).thenAnswer(call->{
            String sql=call.getArgument(0);
            if(sql.contains("from kg_subscribe_outbox"))return p("outboxId",1L,"status","queued","channel","wechat","msgId",77L,"appUserId",1L,"bizKey","business","eventKey","message:77","payload","{\"title\":\"合成通知\"}");
            if(sql.contains("from kg_subscribe_template"))return p("templateId","synthetic-template","fields","thing1=title");
            if(sql.contains("from kg_app_user"))return p("openid","synthetic-recipient");
            if(sql.contains("from kg_message"))return p("readFlag","0","delFlag","0","mode","staff","bizType","merchant_request","bizId",3L);
            if(sql.contains("from kg_subscribe_grant"))return p("quota",1L,"grantVersion",2L);
            throw new AssertionError(sql);
        });
    }

    @Test void claimedCityNotificationKeepsScopeIntactAfterUrlParameterDecoding() throws Exception
    {
        StaffGrantView grant=new StaffGrantView();grant.setActive(true);grant.setRoleCode("merchant_service");grant.setCity("平潭 A+B&店#%?");
        String scope=ServiceCityMessageScope.key(grant);
        when(visibility.authorizedScope(1L,77L,"staff")).thenReturn(scope);
        Map<String,Object> work=outbox.claim(1L);
        String page=(String)work.get("page");
        assertTrue(page.startsWith("pages/profile/subscription-message?id=77&mode=staff&scope=service-city%3A"));
        assertEquals(3,page.substring(page.indexOf('?')+1).split("&").length);
        assertFalse(page.contains("#"));
        assertEquals(scope,URLDecoder.decode(page.substring(page.indexOf("&scope=")+7),"UTF-8"));
        verify(db).update(contains("quota=quota-1"),anyMap());
    }

    @Test void oldStoreAndUserScopesAlsoRoundTripWithoutChangingIdentity() throws Exception
    {
        for(String scope:new String[]{"user","store:12:merchant","city:8:city_lead"}) {
            when(visibility.authorizedScope(1L,77L,"staff")).thenReturn(scope);
            String page=(String)outbox.claim(1L).get("page");
            assertEquals(scope,URLDecoder.decode(page.substring(page.indexOf("&scope=")+7),"UTF-8"));
        }
    }

    @Test void revokedIdentityCannotProduceADeepLinkOrConsumeQuota()
    {
        when(visibility.authorizedScope(1L,77L,"staff")).thenReturn(null);
        assertNull(outbox.claim(1L));
        verify(db,never()).update(contains("quota=quota-1"),anyMap());
    }
}
