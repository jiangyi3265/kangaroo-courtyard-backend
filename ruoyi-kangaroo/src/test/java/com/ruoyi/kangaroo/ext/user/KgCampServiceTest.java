package com.ruoyi.kangaroo.ext.user;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgCampServiceTest {
    KgJdbc db; KgCampService service;
    @BeforeEach void setup() {
        db=mock(KgJdbc.class); service=new KgCampService(); KgCityService cities=mock(KgCityService.class);
        ReflectionTestUtils.setField(service,"db",db); ReflectionTestUtils.setField(service,"cities",cities);
        when(db.one(contains("sys_config"),anyMap())).thenReturn(p("configValue","true"));
        when(cities.jobCity(anyString())).thenAnswer(call -> call.getArgument(0));
        when(db.one(contains("camp_local_qr"),anyMap())).thenReturn(p("campLocalQr","/profile/upload/local.png","campDestinationQr","/profile/upload/destination.png"));
    }
    @SuppressWarnings("unchecked") Map<String,Object> group(Map<String,Object> result,String key) { return (Map<String,Object>)result.get(key); }
    @Test void guestGetsDistinctCodesEvenInSameCityAndNoPrivateQuery() {
        Map<String,Object> out=service.groups(null,"成都","成都",0);
        assertEquals("成都同城群",group(out,"current").get("name"));
        assertEquals("成都目的地群",group(out,"destination").get("name"));
        assertNotEquals(group(out,"current").get("qrUrl"),group(out,"destination").get("qrUrl"));
        verify(db,never()).one(contains("kg_application"),anyMap());
    }
    @Test void newestBrowseOrSignupDeterminesDestinationAndQueryIsScoped() {
        when(db.one(contains("kg_application"),eq(p("u",7L)))).thenReturn(p("city","大理","visitedAt",2000L));
        assertEquals("大理",group(service.groups(7L,"成都","杭州",1000),"destination").get("city"));
        assertEquals("signup",service.groups(7L,"成都","杭州",1000).get("destinationSource"));
        assertEquals("杭州",group(service.groups(7L,"成都","杭州",3000),"destination").get("city"));
        assertEquals("大理",group(service.groups(7L,"成都","",0),"destination").get("city"));
    }
    @Test void missingCityAndUnconfiguredCodesStayUnavailable() {
        assertEquals("unavailable",group(service.groups(null,"","",0),"current").get("state"));
        when(db.one(contains("camp_local_qr"),anyMap())).thenReturn(null);
        assertEquals("",group(service.groups(null,"成都","杭州",0),"current").get("qrUrl"));
    }
    @Test void unsafeOrMultipleImagesAreNotExposed() {
        for(String raw:new String[]{"javascript:alert(1)","https://other.example/qr.png","/static/fake.png","/profile/upload/a.png,/profile/upload/b.png"})
            assertEquals("",KgCampService.safeImage(raw));
    }
    @Test void groupFeatureSwitchRemainsEffective() {
        when(db.one(contains("sys_config"),anyMap())).thenReturn(p("configValue","false"));
        assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> service.groups(null,"成都","杭州",0));
        verify(db,never()).one(contains("camp_local_qr"),anyMap());
    }
    @Test void publicControllerSupportsGuestsWithoutRequiringLogin() {
        com.ruoyi.kangaroo.controller.app.AppUserExtController controller = new com.ruoyi.kangaroo.controller.app.AppUserExtController();
        ReflectionTestUtils.setField(controller,"camp",service);
        assertDoesNotThrow(() -> controller.campGroups("成都","杭州",0));
        verify(db,never()).one(contains("kg_application"),anyMap());
    }
}
