package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.KgGovernanceExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.ruoyi.common.exception.ServiceException;

/** Real disposable local database. No arrival transition, payment or external delivery. */
class KgArrivalMediationTest {
    private final KgGovernanceExecutionTest fixture = new KgGovernanceExecutionTest();
    @BeforeAll static void initialize() { setup(); }
    @BeforeEach void resetFixture() { fixture.reset(); }

    private long application(String status) {
        long id = fixture.app(1L);
        db.update("update kg_application set status=:s,onboard_time=null,volunteer_done_time=null where app_id=:a",p("s",status,"a",id));
        return id;
    }
    @Test void signedApplicantCanOpenCaseBeforeMerchantConfirmsArrival() {
        long app = application("contract_signed");
        assertEquals(true,orders.mineOne(1L,"application",app).get("eligible"));
        assertEquals(1L,((Number)orders.mine(1L,"user",1).get("total")).longValue());
        long cid = in(() -> service.create(1L,fixture.body(1L,app)));
        assertEquals("pending",service.detail(1L,cid,false).get("status"));
        Map<String,Object> unchanged=db.one("select status,onboard_time from kg_application where app_id=:a",p("a",app));
        assertEquals("contract_signed",unchanged.get("status"));assertNull(unchanged.get("onboardTime"));
        assertEquals(2L,service.detail(1L,cid,false).get("storeId"));
    }
    @Test void unsignedAndUnrelatedAccountsCannotCreateCases() {
        for(String status:Arrays.asList("submitted","review_passed","interview_done","user_confirmed","store_confirmed")) {
            long app=application(status);
            assertThrows(ServiceException.class,() -> orders.mineOne(1L,"application",app));
            assertThrows(ServiceException.class,() -> in(() -> service.create(1L,fixture.body(1L,app))));
        }
        long app=application("contract_signed");
        assertThrows(ServiceException.class,() -> orders.mineOne(4L,"application",app));
        assertThrows(ServiceException.class,() -> in(() -> service.create(4L,fixture.body(4L,app))));
        assertEquals(0,db.count("select count(*) from kg_mediation_case",p()));
    }
    @Test void recruitingMerchantCanCreateButOtherMerchantCannot() {
        long app=application("contract_signed");
        assertEquals(true,orders.mineOne(2L,"application",app).get("eligible"));
        assertThrows(ServiceException.class,() -> orders.mineOne(10L,"application",app));
        assertThrows(ServiceException.class,() -> in(() -> service.create(10L,fixture.body(10L,app))));
        long cid=in(() -> service.create(2L,fixture.body(2L,app)));
        assertEquals("merchant",service.detail(2L,cid,false).get("viewerRole"));
        assertEquals("user",service.detail(1L,cid,false).get("viewerRole"));
    }
    @Test void signedCaseRetriesReuseAndNewDuplicateIsRejected() {
        long app=application("contract_signed");Map<String,Object> request=fixture.body(1L,app);
        long cid=in(() -> service.create(1L,request));
        assertEquals(cid,in(() -> service.create(1L,request)));
        assertThrows(ServiceException.class,() -> in(() -> service.create(1L,fixture.body(1L,app))));
        assertThrows(ServiceException.class,() -> in(() -> service.create(2L,fixture.body(2L,app))));
        assertEquals(1,db.count("select count(*) from kg_mediation_case",p()));
    }
    @Test void otherOrderTypesDoNotGainSignedOrPreArrivalEligibility() {
        for(String type:Arrays.asList("stay","activity"))
            for(String state:Arrays.asList("contract_signed","store_confirmed","confirmed","pending","paid"))
                assertFalse(orders.eligible(p("orderType",type,"status",state)));
        assertTrue(orders.eligible(p("orderType","stay","status","checked_in")));
        assertTrue(orders.eligible(p("orderType","activity","status","confirmed","checkinTime",Governance.now())));
    }
}
