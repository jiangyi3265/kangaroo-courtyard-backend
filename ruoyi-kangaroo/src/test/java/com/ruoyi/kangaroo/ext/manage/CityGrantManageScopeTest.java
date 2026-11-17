package com.ruoyi.kangaroo.ext.manage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.CityGrantScope;
import com.ruoyi.kangaroo.ext.staff.RolePhase;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 按城市授权的换工主理人、小院主理人：经营门店（日报、周报、看板）和门店任务按所选角色认本城门店 */
class CityGrantManageScopeTest {
    private final KgJdbc db = mock(KgJdbc.class);
    private final CityGrantScope cityGrants = mock(CityGrantScope.class);
    @BeforeEach void phase() { RolePhase.set(RolePhase.NEW); }
    @AfterEach void clear() { RolePhase.set(RolePhase.LEGACY); }
    private static StaffGrantView view(long store, String role) { StaffGrantView v = new StaffGrantView(); v.setStoreId(store); v.setRoleCode(role); return v; }

    @Test void myStoresAddsCityGrantStoresAsSelectedRoleOnlyForManagerAndKeeper() {
        KgManageService manage = new KgManageService();
        ReflectionTestUtils.setField(manage,"db",db); ReflectionTestUtils.setField(manage,"cityGrants",cityGrants);
        when(db.list(contains("from kg_staff_grant g join kg_store s"),anyMap())).thenReturn(new ArrayList<Map<String,Object>>(Arrays.asList(
                KgJdbc.p("storeId",1L,"roleCode","manager","storeName","一店","cover",null,"city","大连","address","a"))));
        Map<Long,StaffGrantView> covered = new LinkedHashMap<Long,StaffGrantView>();
        covered.put(5L,view(5L,"keeper")); covered.put(6L,view(6L,"merchant_service"));
        when(cityGrants.stores(7L)).thenReturn(covered);
        when(db.list(contains("from kg_store where store_id in (:ids)"),anyMap())).thenReturn(Arrays.asList(
                KgJdbc.p("storeId",5L,"storeName","五店","cover","c.jpg","city","大连","address","b")));
        List<Map<String,Object>> stores = manage.myStores(7L);
        assertEquals(Arrays.asList(1L,5L),KgManageService.ids(stores));
        assertEquals("keeper",stores.get(1).get("roleCode"));
        assertEquals(Arrays.asList("storeId","roleCode","storeName","cover","city","address"),new ArrayList<String>(stores.get(1).keySet()));
        verify(db).list(contains("from kg_store where store_id in (:ids)"),eq(KgJdbc.p("ids",Arrays.asList(5L))));
        assertEquals(Arrays.asList(1L,5L),KgManageService.ids(manage.myBusinessStores(7L)));
        // 没有城市授权时不多查
        when(cityGrants.stores(7L)).thenReturn(new LinkedHashMap<Long,StaffGrantView>());
        assertEquals(Arrays.asList(1L),KgManageService.ids(manage.myStores(7L)));
    }

    @Test void storeTaskRoleFallsBackToCityGrantButNeverToSpecialists() {
        KgTaskService tasks = new KgTaskService();
        ReflectionTestUtils.setField(tasks,"db",db); ReflectionTestUtils.setField(tasks,"cityGrants",cityGrants);
        when(db.one(contains("from kg_staff_grant g join kg_store s"),anyMap())).thenReturn(null);
        when(cityGrants.roleAt(7L,5L)).thenReturn("keeper");
        assertEquals("keeper",tasks.roleAt(7L,5L)); assertTrue(tasks.canPublish(7L,5L));
        when(cityGrants.roleAt(7L,5L)).thenReturn("online_staff");
        assertNull(tasks.roleAt(7L,5L));
        when(db.one(contains("from kg_staff_grant g join kg_store s"),anyMap())).thenReturn(KgJdbc.p("roleCode","manager"));
        assertEquals("manager",tasks.roleAt(7L,5L),"门店授权优先");
    }
}
