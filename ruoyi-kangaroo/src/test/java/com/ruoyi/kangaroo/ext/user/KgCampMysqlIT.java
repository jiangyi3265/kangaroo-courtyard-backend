package com.ruoyi.kangaroo.ext.user;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgCampMysqlIT {
    @Test @SuppressWarnings("unchecked") void actualSignupQueryAndQrReplacementUseLatestAccountData() {
        String schema=System.getenv("KG_CAMP_TEST_DB");
        assertNotNull(schema,"Run e2e/test_camp_database.py");
        assertTrue(schema.matches("kg_camp_test_[a-f0-9]{12}"));
        KgJdbc db=new KgJdbc(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root",System.getenv("MYSQL_PWD")));
        KgCampService service=new KgCampService(); KgCityService cities=mock(KgCityService.class);
        when(cities.jobCity(anyString())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(service,"db",db); ReflectionTestUtils.setField(service,"cities",cities);
        Map<String,Object> result=service.groups(7L,"成都","杭州",1);
        assertEquals("大理",((Map<?,?>)result.get("destination")).get("city"));
        assertEquals("signup",result.get("destinationSource"));
        assertEquals("杭州",((Map<?,?>)service.groups(8L,"成都","杭州",1).get("destination")).get("city"));
        assertEquals("杭州",((Map<?,?>)service.groups(7L,"成都","杭州",System.currentTimeMillis()).get("destination")).get("city"));
        assertEquals("/profile/upload/local.png",((Map<?,?>)result.get("current")).get("qrUrl"));
        db.update("update kg_city set camp_local_qr='/profile/upload/new.png' where name='成都'",p());
        assertEquals("/profile/upload/new.png",((Map<?,?>)service.groups(null,"成都","",0).get("current")).get("qrUrl"));
        db.update("update kg_city set status='1' where name='成都'",p());
        assertEquals("",((Map<?,?>)service.groups(null,"成都","",0).get("current")).get("qrUrl"));
    }
}
