package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.*;
import com.ruoyi.kangaroo.controller.admin.*;
import com.ruoyi.kangaroo.config.schema.CityCoordinatorSchema;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;

class KgAdminAuditTest {
    private KgJdbc db;
    private KgExtAdminController admin;
    private ExtEntity city;
    @BeforeEach void setup() {
        db = mock(KgJdbc.class); admin = new KgExtAdminController();
        ExtEntities entities = mock(ExtEntities.class);
        city = new KgUserModule().entities().stream().filter(e -> "city".equals(e.key)).findFirst().get();
        for (String col : Arrays.asList("coordinator_name", "coordinator_avatar", "coordinator_intro")) city.colInfo.put(col,"!S");
        when(entities.require("city")).thenReturn(city);
        ReflectionTestUtils.setField(admin,"db",db); ReflectionTestUtils.setField(admin,"entities",entities);
        when(db.updateRow(anyString(),anyString(),any(),anyMap())).thenReturn(1);
        SysUser user = new SysUser(); user.setUserId(1L); user.setUserName("audit-test");
        LoginUser login = new LoginUser(); login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,Collections.emptyList()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void coordinatorSaveCanBeReadImmediatelyAndCleared() {
        AtomicReference<Map<String,Object>> saved = new AtomicReference<>();
        when(db.updateRow(eq("kg_city"),eq("city_id"),eq(7L),anyMap())).thenAnswer(i -> { saved.set(i.getArgument(3)); return 1; });
        admin.edit("city", p("cityId",7L,"coordinatorName","测试负责人","coordinatorIntro","处理本地岗位咨询"));
        assertEquals("测试负责人",saved.get().get("coordinator_name"));
        assertFalse(saved.get().containsKey("name"),"修改负责人不改城市简称");
        KgCityService service = spy(new KgCityService()); ReflectionTestUtils.setField(service,"db",db);
        doReturn("平潭").when(service).jobCity("平潭岛");
        when(db.one(contains("coordinator_name"),anyMap())).thenAnswer(i -> {
            assertEquals("平潭", ((Map<?,?>)i.getArgument(1)).get("city"));
            assertTrue(((String)i.getArgument(0)).contains("status = '0'"));
            return p("city","平潭","name",saved.get().get("coordinator_name"),"intro",saved.get().get("coordinator_intro"));
        });
        assertEquals("测试负责人",service.coordinator("平潭岛").get("name"));
        admin.edit("city",p("cityId",7L,"coordinatorName","","coordinatorIntro","","coordinatorAvatar",""));
        assertEquals("",service.coordinator("平潭岛").get("name"));
        verify(db,never()).insert(eq("kg_staff_grant"),anyMap());
    }
    @Test void coordinatorRejectsUnsafeAvatarAndOversizedIntro() {
        assertThrows(ServiceException.class,()->admin.edit("city",p("cityId",7L,"coordinatorAvatar","https://example.com/a.png")));
        assertThrows(ServiceException.class,()->admin.edit("city",p("cityId",7L,"coordinatorIntro",String.join("",Collections.nCopies(241,"字")))));
        verify(db,never()).updateRow(anyString(),anyString(),any(),anyMap());
    }
    @Test void unknownAndEmptyCityDoNotLeakOtherProfiles() {
        KgCityService s = spy(new KgCityService()); ReflectionTestUtils.setField(s,"db",db);
        assertTrue(s.coordinator(null).isEmpty()); verifyNoInteractions(db);
        doReturn("未知").when(s).jobCity("未知"); assertTrue(s.coordinator("未知").isEmpty());
    }
    @Test void integerInputRejectsFractionTextAndOverflow() {
        ExtField f=ExtField.of("sort","排序","int").range(0L,9999L);
        for (String value : Arrays.asList("1.1","abc","9223372036854775808")) {
            ServiceException e=assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(admin,"convert",f,value));
            assertEquals(422,e.getCode());
        }
        assertEquals(7L,(Object)ReflectionTestUtils.invokeMethod(admin,"convert",f,"7"));
    }
    @Test void grantRoleDescriptionsUseActualPermissionMatrix() {
        Map<?,?> data=(Map<?,?>)new KgStaffGrantController().roles().get("data");
        List<?> roles=(List<?>)data.get("roles"); assertEquals(7,roles.size());
        for(Object raw:roles){Map<?,?> role=(Map<?,?>)raw; assertEquals(StaffPerms.matrix((String)role.get("value")),role.get("matrix"));}
        assertFalse(StaffPerms.allows("online_staff","finance","pay"));
        assertTrue(StaffPerms.allows("staff","jobs","edit"));
        for (String module : Arrays.asList("rooms", "online", "upsell")) assertFalse(StaffPerms.allows("staff",module,"edit"));
    }
    @Test void schemaRequiresAllProfileFields() {
        when(db.count(anyString(),anyMap())).thenReturn(2L);
        assertThrows(IllegalStateException.class,()->new CityCoordinatorSchema(db,true).check());
        when(db.count(anyString(),anyMap())).thenReturn(3L); new CityCoordinatorSchema(db,true).check();
    }
    @Test void knownLoginFailuresKeepActionableMessage() {
        com.ruoyi.framework.web.exception.GlobalExceptionHandler handler = new com.ruoyi.framework.web.exception.GlobalExceptionHandler();
        assertEquals("验证码已失效", handler.handleBusinessBase(new com.ruoyi.common.exception.base.BaseException("验证码已失效")).get("msg"));
    }
}
