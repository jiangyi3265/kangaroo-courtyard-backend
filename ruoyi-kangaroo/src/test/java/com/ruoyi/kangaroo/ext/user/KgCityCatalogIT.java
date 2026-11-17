package com.ruoyi.kangaroo.ext.user;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.kangaroo.ext.KgJdbc;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

class KgCityCatalogIT
{
    @Test @SuppressWarnings("unchecked")
    void nationwideCatalogAndLocationRespectActualDatabaseState() throws Exception
    {
        String database=System.getenv("KG_CITY_TEST_DB");
        assertNotNull(database,"Run through e2e/test_city_catalog_database.py");
        assertTrue(database.matches("kg_city_test_[a-f0-9]{12}"));
        KgJdbc db=new KgJdbc(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+database+"?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai","root",System.getenv("MYSQL_PWD")));
        KgCityService service=new KgCityService(); ReflectionTestUtils.setField(service,"db",db);
        Map<String,Object> directory=service.cities();
        List<Map<String,Object>> all=(List<Map<String,Object>>)directory.get("all");
        assertTrue(all.size()>3100);
        assertTrue(all.stream().anyMatch(c->"平潭".equals(c.get("name"))));
        assertFalse(all.stream().anyMatch(c->"厦门".equals(c.get("name")))); // operator disabled before migration
        assertTrue(all.stream().anyMatch(c->"海岛营地".equals(c.get("name"))));
        assertEquals("平潭",service.locate(25.498,119.790).get("name"));
        Map<String,Object> gcj=service.locate(25.503672,119.791197,30.0,"gcj02");
        assertEquals("平潭县 · 福建省",gcj.get("label"));
        assertEquals("administrative_boundary",gcj.get("source"));
        assertEquals(false,service.locate(null,null).get("found"));
        assertEquals(false,service.locate(Double.NaN,119.0).get("found"));
        assertEquals(false,service.locate(91.0,0.0).get("found"));
        assertEquals(false,service.locate(25.5,119.8,10000.0,"gcj02").get("found"));
        assertEquals(false,service.locate(25.5,119.8,20.0,"unknown").get("found"));
        assertFalse(service.locate(0.0,0.0).containsKey("name"));
        // 区县归到业务城市：定位或选中"古城区"时按"丽江"查数据；目的地和原有城市保持自身名字
        assertEquals("丽江",service.businessCity("古城区"));
        assertEquals("杭州",service.businessCity("西湖区·杭州"));
        assertEquals("厦门",service.businessCity("思明区")); // 字典里停用了厦门，业务数据仍记在厦门名下
        assertEquals("平潭",service.businessCity(" 平潭 "));
        assertEquals("海岛营地",service.businessCity("海岛营地"));
        // 后台自由填写的别名 / 全称归到字典名：门店写成"平潭岛"时，用户选"平潭"要能查到它
        assertEquals("平潭",service.businessCity("平潭岛"));
        assertEquals("平潭",service.businessCity("平潭综合实验区"));
        assertEquals("丽江",service.businessCity("丽江市"));
        assertTrue(all.stream().anyMatch(c->"古城区".equals(c.get("name"))&&"丽江".equals(c.get("cityName"))));
        assertTrue(all.stream().anyMatch(c->"平潭".equals(c.get("name"))&&"平潭".equals(c.get("cityName"))));
        Map<String,Object> lijiang=service.locate(26.872229,100.234412,30.0,"gcj02");
        assertEquals("丽江",lijiang.get("name"));
        assertEquals("古城区",lijiang.get("district"));
        assertEquals("古城区 · 丽江",lijiang.get("label"));
        assertNull(gcj.get("district"));
        db.update("update kg_city set status='1' where adcode=:code",p("code","350128"));
        assertEquals(false,service.locate(25.498,119.790).get("found"));
        db.update("update kg_city set status='0' where adcode=:code",p("code","350128"));
        // Capture actual service output for browser integration, not a hand-written 3,000-row fixture.
        Files.write(Paths.get(System.getenv("KG_CITY_TEST_OUTPUT")),new ObjectMapper().writeValueAsBytes(directory));
    }
}
