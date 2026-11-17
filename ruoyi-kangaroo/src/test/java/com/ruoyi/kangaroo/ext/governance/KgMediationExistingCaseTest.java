package com.ruoyi.kangaroo.ext.governance;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;

class KgMediationExistingCaseTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgMediationOrders orders=spy(new KgMediationOrders());
    KgMediationExistingCaseTest(){ReflectionTestUtils.setField(orders,"db",db);doReturn(p("orderId",30L,"orderType","application","orderNo","A30","userId",1L,"storeId",2L,"storeName","测试店","status","contract_signed")).when(orders).order("application",30L);}
    void existing(String state){when(db.one(contains("from kg_mediation_case"),anyMap())).thenReturn(p("caseId",5L,"status",state));}
    @Test void ownerCanOpenExistingCaseFromOrder(){existing("pending");Map<String,Object> o=orders.mineOne(1L,"application",30L);assertEquals(5L,o.get("existingCaseId"));assertEquals("pending",o.get("existingCaseStatus"));assertEquals(true,o.get("eligible"));}
    @Test void expiredApplicationStillReturnsItsExistingProgress(){existing("closed");doReturn(false).when(orders).eligible(anyMap());Map<String,Object> o=orders.mineOne(1L,"application",30L);assertEquals(5L,o.get("existingCaseId"));assertEquals(false,o.get("eligible"));}
    @Test void expiredWithoutCaseDoesNotBecomeEligible(){doReturn(false).when(orders).eligible(anyMap());assertThrows(ServiceException.class,()->orders.mineOne(1L,"application",30L));}
    @Test void unrelatedAccountCannotProbeExistingCaseIds(){existing("pending");assertThrows(ServiceException.class,()->orders.mineOne(9L,"application",30L));verify(db,never()).one(contains("from kg_mediation_case"),anyMap());}
    @Test void authorizedMerchantCanFindTheSameProgress(){existing("accepted");doReturn(true).when(orders).merchant(2L,2L);assertEquals(5L,orders.mineOne(2L,"application",30L).get("existingCaseId"));}
    @Test void closedDecisionKeepsNewCaseDisabled(){existing("closed");doReturn(true).when(orders).settled("application",30L);Map<String,Object> o=orders.mineOne(1L,"application",30L);assertEquals(false,o.get("eligible"));assertEquals(KgMediationOrders.SETTLED,o.get("reason"));assertEquals(5L,o.get("existingCaseId"));}
    @Test void withdrawnClosedCaseDoesNotDisableAnOtherwiseEligibleOrder(){existing("closed");assertEquals(true,orders.mineOne(1L,"application",30L).get("eligible"));}
    @Test void queriesPrioritizeOngoingCaseBeforeMostRecentClosedCase(){existing("pending");orders.mineOne(1L,"application",30L);verify(db).one(contains("order by (c.status<>'closed') desc,c.case_id desc limit 1"),eq(p("t","application","o",30L,"u",1L)));}
    @Test void existingCaseLookupRequiresItsOwnParticipantScope(){
        doReturn(true).when(orders).merchant(2L,2L);
        assertFalse(orders.mineOne(2L,"application",30L).containsKey("existingCaseId"));
        verify(db).one(argThat(sql->sql.contains("c.user_id=:u or exists")&&sql.contains("g.store_id=c.store_id")&&sql.contains("g.role_code='merchant'")&&sql.contains("u.status='0'")&&sql.contains("g.del_flag='0'")),eq(p("t","application","o",30L,"u",2L)));
    }
    @Test void merchantOrderListUsesTheSameCaseParticipantScope(){
        when(db.page(anyString(),anyMap(),eq(1),eq(20))).thenReturn(p("rows",Arrays.asList(p("orderType","application","orderId",30L))));
        orders.mine(2L,"merchant",1);
        verify(db).one(contains("g.store_id=c.store_id"),eq(p("t","application","o",30L,"u",2L)));
    }
}
