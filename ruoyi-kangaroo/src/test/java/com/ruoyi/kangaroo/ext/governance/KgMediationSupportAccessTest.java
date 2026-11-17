package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.CityLeadScope;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

class KgMediationSupportAccessTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgMediationOrders orders=mock(KgMediationOrders.class);
    final CityLeadScope city=mock(CityLeadScope.class);
    final IKgStaffGrantService grants=mock(IKgStaffGrantService.class);
    final KgMediationService service=new KgMediationService();
    KgMediationSupportAccessTest(){
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"orders",orders);
        ReflectionTestUtils.setField(service,"cityScope",city);ReflectionTestUtils.setField(service,"grants",grants);
        when(grants.loadActiveGrantViews(9L)).thenReturn(Arrays.asList(grant(2L,"manager",true)));
        when(db.one(contains("from kg_mediation_case"),anyMap())).thenAnswer(x->record());
    }
    StaffGrantView grant(Long store,String role,boolean active){StaffGrantView g=new StaffGrantView();g.setStoreId(store);g.setRoleCode(role);g.setActive(active);return g;}
    Map<String,Object> record(){return p("caseId",5L,"orderType","application","storeId",2L,"userId",1L,"initiatorId",1L,"status","pending","phone","private phone","verification","private review","appeal","private appeal","requestKey","private key","narrative","private narrative");}
    @Test void managerCanReadAuthorizedApplicationOnly(){assertEquals("support",service.role(9L,record(),false));}
    @Test void unrelatedStoreAndOtherOrderTypesAreDenied(){
        Map<String,Object> c=record();c.put("storeId",3L);assertThrows(ServiceException.class,()->service.role(9L,c,false));
        c.put("storeId",2L);for(String type:Arrays.asList("stay","activity")){c.put("orderType",type);assertThrows(ServiceException.class,()->service.role(9L,c,false));}
    }
    @Test void removedInactiveAndNonManagerGrantsDoNotProvideAccess(){
        for(List<StaffGrantView> g:Arrays.asList(Collections.<StaffGrantView>emptyList(),Arrays.asList(grant(2L,"manager",false)),Arrays.asList(grant(2L,"keeper",true)),Arrays.asList(grant(null,"manager",true)))){
            when(grants.loadActiveGrantViews(9L)).thenReturn(g);assertThrows(ServiceException.class,()->service.role(9L,record(),false));
        }
    }
    @Test void ownCaseAndExistingCityAuthorityKeepTheirOriginalRole(){
        assertEquals("user",service.role(1L,record(),false));
        when(city.covers(9L,2L)).thenReturn(true);assertEquals("city",service.role(9L,record(),false));
    }
    @Test void supportListUsesOnlyAuthorizedApplicationStores(){
        service.list(9L,"support","",1,false);
        verify(db).page(contains("c.order_type='application' and c.store_id in(:stores) and c.user_id<>:u"),argThat(m->m.get("stores").equals(new LinkedHashSet<>(Arrays.asList(2L)))),eq(1),eq(30));
    }
    @Test void noManagerGrantMeansEmptyFollowUpList(){
        when(grants.loadActiveGrantViews(9L)).thenReturn(Collections.emptyList());
        assertEquals(0,service.list(9L,"support","",1,false).get("total"));verify(db,never()).page(anyString(),anyMap(),anyInt(),anyInt());
    }
    @Test void supportDetailKeepsDecisionsReadOnlyAndHidesPrivateMaterial(){
        Map<String,Object> d=service.detail(9L,5L,false);
        assertEquals("support",d.get("viewerRole"));assertEquals(false,d.get("canHandle"));assertEquals(false,d.get("canContribute"));assertEquals(true,d.get("canAssist"));
        for(String key:Arrays.asList("phone","verification","appeal","requestKey","userId","handlerId","initiatorId"))assertFalse(d.containsKey(key),key);
        assertNotEquals("private narrative",d.get("narrative"));
        verify(db).list(contains("and (owner_actor=:a or redacted='1')"),anyMap());
        verify(db).list(contains("and (audience='parties' or (audience='private' and actor=:a))"),anyMap());
    }
    @Test void supportCannotWriteOrMakeDecisionsEvenWithForgedCommands(){
        for(String action:Arrays.asList("evidence","statement","accept","verify","decision","confirm","withdraw","execution","transfer","adoption")){
            ServiceException e=assertThrows(ServiceException.class,()->service.command(9L,5L,action,p(),false,null));assertEquals(403,e.getCode());
        }
        verify(db,never()).update(anyString(),anyMap());verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void supportCannotReadRawPartyEvidence(){
        assertFalse(service.fileAccess(9L,p("caseId",5L,"ownerActor","app:1","redacted","0"),false));
    }
    @Test void grantRevocationIsReadFreshWithoutReLogin(){
        assertEquals("support",service.role(9L,record(),false));
        when(grants.loadActiveGrantViews(9L)).thenReturn(Collections.emptyList());
        assertThrows(ServiceException.class,()->service.role(9L,record(),false));
    }
}
