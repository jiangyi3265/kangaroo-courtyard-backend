package com.ruoyi.kangaroo.ext.staff;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.mapper.KgStaffGrantMapper;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.impl.KgStaffGrantServiceImpl;

/**
 * 按城市授权（store_id 为空、只有城市；不含城市负责人、合作商家）在本城门店按所选角色行使权限：
 * 门店通知名单、小院主理人通知、线上付款通知、在岗登记的回库判权都要认。范围由 CityGrantScope 算（这里 mock），
 * 「已有门店授权或撤销记录优先」在 CityGrantScope 里保证（CityGrantScopeTest）。
 */
class CityGrantStoreAccessTest {
    private final KgJdbc db = mock(KgJdbc.class);
    private final CityGrantScope cityGrants = mock(CityGrantScope.class);
    @BeforeEach void phase() { RolePhase.set(RolePhase.NEW); }
    @AfterEach void clear() { RolePhase.set(RolePhase.LEGACY); }
    private static Map<String,Object> holder(long uid, String role) { return KgJdbc.p("appUserId",uid,"roleCode",role,"alsoService","0"); }

    @Test void storeStaffListsIncludeSameCityGrantHolders() {
        KgStaffGrantServiceImpl service = new KgStaffGrantServiceImpl();
        KgStaffGrantMapper mapper = mock(KgStaffGrantMapper.class);
        ReflectionTestUtils.setField(service,"kgStaffGrantMapper",mapper);
        ReflectionTestUtils.setField(service,"cityGrants",cityGrants);
        when(mapper.selectActiveStaffUserIds(5L)).thenReturn(Arrays.asList(1L));
        when(mapper.selectActiveMerchantServiceUserIds(5L)).thenReturn(new ArrayList<Long>());
        when(cityGrants.holderIds(5L,null)).thenReturn(Arrays.asList(7L,8L,1L));
        when(cityGrants.holderIds(5L,Collections.singletonList("merchant_service"))).thenReturn(Arrays.asList(8L));
        assertEquals(Arrays.asList(1L,7L,8L),service.listActiveStaffUserIds(5L),"本城按城市授权的员工也收这家店的通知，不重复");
        assertEquals(Arrays.asList(8L),service.listMerchantServiceUserIds(5L),"按城市授权的商家服务人照样从线下业务消息里去掉");
        assertTrue(service.listActiveStaffUserIds(null).isEmpty());
        ReflectionTestUtils.setField(service,"cityGrants",null);
        assertEquals(Arrays.asList(1L),service.listActiveStaffUserIds(5L));
    }

    @Test void keeperNoticesAndOnlinePayoutReachCityGrantHolders() {
        StaffSupport support = new StaffSupport();
        IKgMessageService messages = mock(IKgMessageService.class);
        ReflectionTestUtils.setField(support,"db",db); ReflectionTestUtils.setField(support,"messages",messages);
        ReflectionTestUtils.setField(support,"cityGrants",cityGrants);
        when(db.list(contains("g.role_code = :r"),anyMap())).thenReturn(Arrays.asList(KgJdbc.p("appUserId",1L)));
        when(cityGrants.holderIds(5L,Collections.singletonList("keeper"))).thenReturn(Arrays.asList(7L,1L));
        assertEquals(2,support.notifyKeepers(5L,null,"system","小院有新评论","内容","stay_comment",9L,"/r"));
        verify(messages).send(eq(7L),eq("staff"),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        verify(messages,times(1)).send(eq(1L),eq("staff"),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        // 付款类线上消息：本城按城市授权的商家服务人能登记付款，要收到
        when(cityGrants.holders(5L)).thenReturn(Arrays.asList(holder(8L,"merchant_service")));
        assertEquals(Arrays.asList(8L),support.onlineRecipients(5L,true,null));
    }

    @Test void onDutyRegistrationAcceptsCityGrantRoleOnlyWhereScopeCovers() {
        KgEmploymentService employment = new KgEmploymentService();
        ReflectionTestUtils.setField(employment,"db",db); ReflectionTestUtils.setField(employment,"cityGrants",cityGrants);
        when(db.count(contains("from kg_app_user u join kg_store s"),anyMap())).thenReturn(1L);
        when(db.list(contains("from kg_staff_grant where app_user_id=:u"),anyMap())).thenReturn(new ArrayList<Map<String,Object>>());
        when(cityGrants.roleAt(7L,5L)).thenReturn("manager");
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(employment,"allowedNow",7L,5L,StaffPerms.VIEW));
        // 不同城、或这家店有门店授权 / 撤销记录（以门店授权为准）：roleAt 为空
        when(cityGrants.roleAt(7L,5L)).thenReturn(null);
        assertEquals(Boolean.FALSE,ReflectionTestUtils.invokeMethod(employment,"allowedNow",7L,5L,StaffPerms.VIEW));
    }
}
