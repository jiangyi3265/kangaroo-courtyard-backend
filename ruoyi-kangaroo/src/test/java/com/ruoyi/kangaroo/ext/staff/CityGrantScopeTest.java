package com.ruoyi.kangaroo.ext.staff;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.StaffGrantView;

class CityGrantScopeTest {
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final CityGrantScope scope = new CityGrantScope();
    @BeforeEach void setup() {
        RolePhase.set(RolePhase.NEW);
        ReflectionTestUtils.setField(scope,"db",db); ReflectionTestUtils.setField(scope,"cities",cities);
        when(cities.jobCity(anyString())).thenAnswer(i -> ((String)i.getArgument(0)).replace("市", ""));
        when(db.list(contains("from kg_staff_grant"),anyMap())).thenReturn(Collections.emptyList());
        when(db.list(contains("from kg_store"),anyMap())).thenReturn(Arrays.asList(store(1L,"大连"),store(2L,"大连市"),store(3L,"平潭"),store(4L,"全国")));
    }
    @AfterEach void clear() { RolePhase.set(RolePhase.LEGACY); }
    private static Map<String,Object> store(long id,String city) { return KgJdbc.p("storeId",id,"storeName","门店"+id,"city",city); }
    static StaffGrantView city(String role) {
        StaffGrantView g = new StaffGrantView(); g.setRoleCode(role); g.setActive(true); g.setCityId(20L); g.setCity("大连"); g.setAlsoService("keeper".equals(role)); return g;
    }
    @Test void internalRolesExpandOnlyWithinCityAndKeepOriginalPermissions() {
        for(String role: Arrays.asList("manager","keeper","online_staff","merchant_service")) {
            List<StaffGrantView> rows = new ArrayList<>(Arrays.asList(city(role)));
            scope.expand(7L,rows);
            assertEquals(3,rows.size()); assertNull(rows.get(0).getStoreId());
            for(StaffGrantView g:rows.subList(1,rows.size())) {
                assertTrue(g.getStoreId()==1L || g.getStoreId()==2L); assertEquals(role,g.getRoleCode()); assertEquals(false,g.getFullAccess());
                assertEquals("keeper".equals(role),g.getAlsoService());
            }
        }
    }
    @Test void merchantDoesNotAcquireOtherMerchantsStores() {
        List<StaffGrantView> rows = new ArrayList<>(Arrays.asList(city("merchant")));
        scope.expand(7L,rows); assertEquals(1,rows.size()); verifyNoInteractions(db);
    }
    @Test void explicitRoleAndRevokedOrDeletedStoreGrantsOverrideCityScope() {
        when(db.list(contains("from kg_staff_grant"),anyMap())).thenReturn(Arrays.asList(KgJdbc.p("storeId",1L),KgJdbc.p("storeId",2L)));
        StaffGrantView explicit = city("merchant"); explicit.setStoreId(1L);
        List<StaffGrantView> rows = new ArrayList<>(Arrays.asList(city("manager"),explicit));
        scope.expand(7L,rows); assertEquals(2,rows.size()); assertEquals("merchant",rows.get(1).getRoleCode());
        verify(db).list(eq("select store_id from kg_staff_grant where app_user_id=:u and store_id is not null"),anyMap());
    }
    @Test void revokedCityGrantAndRetiredRolesNeverExpand() {
        StaffGrantView revoked = city("manager"); revoked.setActive(false);
        List<StaffGrantView> rows = new ArrayList<>(Arrays.asList(revoked,city("staff"),city("unknown")));
        scope.expand(7L,rows); assertEquals(3,rows.size()); verifyNoInteractions(db);
    }
    @Test void emptyAndNationalScopesNeverExpand() {
        for(String name:Arrays.asList("", "全国", "不限", "全部城市")) {
            StaffGrantView grant = city("manager"); grant.setCity(name);
            List<StaffGrantView> rows = new ArrayList<>(Arrays.asList(grant)); scope.expand(7L,rows); assertEquals(1,rows.size());
        }
    }
    // 后端逐个接口回库判权（myStores、在岗登记、到岗日期、门店任务）：和登录态同一套规则，已有门店授权或撤销记录的店以门店授权为准
    @Test void storesAndRoleAtFollowLoginRulesAndExplicitStoreRecordsWin() {
        when(db.list(contains("g.app_user_id = :u order by g.grant_id"),anyMap())).thenReturn(Arrays.asList(
                KgJdbc.p("appUserId",7L,"cityId",20L,"city","大连","roleCode","keeper","alsoService","1")));
        when(db.list(eq("select store_id from kg_staff_grant where app_user_id=:u and store_id is not null"),anyMap())).thenReturn(Arrays.asList(KgJdbc.p("storeId",2L)));
        Map<Long,StaffGrantView> stores = scope.stores(7L);
        assertEquals(Collections.singleton(1L), stores.keySet());
        assertEquals("keeper",stores.get(1L).getRoleCode()); assertEquals(true,stores.get(1L).getAlsoService()); assertEquals(false,stores.get(1L).getFullAccess());
        assertEquals("keeper",scope.roleAt(7L,1L));
        assertNull(scope.roleAt(7L,2L),"这家店有门店授权或撤销记录：城市授权不覆盖");
        assertNull(scope.roleAt(7L,3L),"别的城市不算"); assertNull(scope.roleAt(7L,null)); assertNull(scope.roleAt(null,1L));
        verify(db,never()).update(anyString(),anyMap());
    }
    @Test void storesSkipCityLeadAndMerchantAndNeverQueryStoresWithoutCityGrants() {
        assertTrue(scope.stores(7L).isEmpty());
        verify(db,never()).list(contains("from kg_store"),anyMap());
        verify(db).list(argThat(sql -> sql.contains("g.store_id is null") && sql.contains("c.status = '0'") && sql.contains("u.status = '0'")
                && sql.contains("g.role_code in ('manager','keeper','online_staff','merchant_service','staff')")),anyMap());
    }
    // 门店通知名单（listActiveStaffUserIds 等）：本城按城市授权的人也算这家店的员工
    @Test void holdersAreSameCityGrantsWithoutOwnStoreRecord() {
        when(db.one(contains("from kg_store"),anyMap())).thenReturn(KgJdbc.p("city","大连市"));
        when(db.list(contains("not exists (select 1 from kg_staff_grant x"),anyMap())).thenReturn(Arrays.asList(
                KgJdbc.p("appUserId",7L,"roleCode","manager","city","大连","alsoService","0"),
                KgJdbc.p("appUserId",8L,"roleCode","merchant_service","city","大连","alsoService","0"),
                KgJdbc.p("appUserId",9L,"roleCode","keeper","city","平潭","alsoService","0"),
                KgJdbc.p("appUserId",10L,"roleCode","staff","city","大连","alsoService","0"),
                KgJdbc.p("appUserId",7L,"roleCode","keeper","city","大连","alsoService","0")));
        List<Long> ids = new ArrayList<>();
        for (Map<String,Object> row : scope.holders(2L)) ids.add(KgJdbc.asLong(row.get("appUserId")));
        assertEquals(Arrays.asList(7L,8L),ids,"别的城市、切换后退役的员工不算，同一人只算一次");
        assertEquals(Arrays.asList(8L),scope.holderIds(2L,Arrays.asList("merchant_service")));
        assertEquals(Arrays.asList(7L,8L),scope.holderIds(2L,null));
        verify(db,atLeastOnce()).list(argThat(sql -> sql.contains("x.store_id = :s") && sql.contains("g.store_id is null")),eq(KgJdbc.p("s",2L)));
        when(db.one(contains("from kg_store"),anyMap())).thenReturn(null);
        assertTrue(scope.holders(2L).isEmpty(),"门店停用或删除");
        when(db.one(contains("from kg_store"),anyMap())).thenReturn(KgJdbc.p("city","全国"));
        assertTrue(scope.holders(2L).isEmpty(),"门店没设具体城市");
        assertTrue(scope.holders(null).isEmpty());
    }
    @Test void newlyOpenedSameCityStoresAppearOnReloadWithoutWritingGrants() {
        when(db.list(contains("from kg_store"),anyMap())).thenReturn(Collections.emptyList());
        List<StaffGrantView> before = new ArrayList<>(Arrays.asList(city("keeper"))); scope.expand(7L,before); assertEquals(1,before.size());
        when(db.list(contains("from kg_store"),anyMap())).thenReturn(Arrays.asList(store(8L,"大连"),store(9L,"平潭")));
        List<StaffGrantView> after = new ArrayList<>(Arrays.asList(city("keeper"))); scope.expand(7L,after); assertEquals(2,after.size()); assertEquals(8L,after.get(1).getStoreId());
        verify(db,never()).update(anyString(),anyMap());
    }
}
