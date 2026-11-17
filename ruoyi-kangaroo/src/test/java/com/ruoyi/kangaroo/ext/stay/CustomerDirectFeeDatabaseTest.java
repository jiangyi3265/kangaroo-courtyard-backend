package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.WxTransferGateway;
import com.ruoyi.common.exception.ServiceException;

/** Isolated MySQL ledger checks; the mocked transfer gateway never pays real money. */
class CustomerDirectFeeDatabaseTest {
    static KgJdbc db;
    static TransactionTemplate tx;
    static DataSourceTransactionManager manager;
    KgBusinessBillService bills;
    KgBusinessWithdrawalService withdrawals;
    WxTransferGateway gateway;
    long billId;

    @BeforeAll static void init() {
        String name=System.getenv("KG_MEMBER_TEST_DB");
        assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+
            "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",
            System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
    }

    @BeforeEach void setup() {
        for (String table : new String[]{"kg_business_withdrawal","kg_business_bill_log","kg_business_bill",
            "kg_pay_refund","kg_pay_order","kg_stay_order","kg_staff_grant","kg_store","kg_app_user"})
            db.update("delete from "+table,p());
        db.insert("kg_app_user",p("user_id",1L,"openid","isolated_direct_fee_openid","nickname","隔离自营测试"));
        db.insert("kg_store",p("store_id",1L,"store_name","隔离自营门店","city","平潭","op_type","direct"));
        db.insert("kg_staff_grant",p("app_user_id",1L,"store_id",1L,"role_code","merchant","status","0","del_flag","0"));
        long order=db.insert("kg_stay_order",p("order_no","QA_DIRECT_FREE","stay_id",1L,"store_id",1L,
            "room_type_id",1L,"app_user_id",2L,"check_in","2026-11-01","check_out","2026-11-08","nights",7,
            "guest_name","隔离测试","guest_phone","13800000000","pay_fen",10710,
            "discount_fen",1190,"discount_label","长住9折","status","completed","pay_no","QA_DIRECT_PAY"));
        db.insert("kg_pay_order",p("pay_no","QA_DIRECT_PAY","biz_type","stay_order","biz_id",order,
            "app_user_id",2L,"store_id",1L,"amount",10710,"description","隔离自营结算","channel","mock","status","paid"));
        bills=new KgBusinessBillService();ReflectionTestUtils.setField(bills,"db",db);
        tx.execute(s->{bills.captureStay(order,p("stay",p("storeId",1L,"stayName","隔离门店","typeName","单人房"),
            "settlementPolicy",KgSettlementMath.DIRECT_NO_FEE_POLICY,"payFen",10710L,"merchantBaseFen",11900L,
            "platformMarkupFen",0L,"discountFen",1190L,"addonFen",0L,"serviceFeeFen",0L));return null;});
        billId=db.count("select bill_id from kg_business_bill where source_id=:o",p("o",order));
        gateway=mock(WxTransferGateway.class);
        when(gateway.merchantId()).thenReturn("isolated");when(gateway.appId()).thenReturn("isolated");
        when(gateway.request(anyString(),anyString(),anyLong(),anyString())).thenAnswer(i->p("transfer_amount",i.getArgument(2)));
        withdrawals=new KgBusinessWithdrawalService(db,bills,gateway,manager);
    }

    Map<String,Object> detail(){return bills.detail(billId,1L);}
    long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    void approve(long target){approve(target,num(detail(),"version"));}
    void approve(long target,long version){tx.execute(s->{bills.command(billId,"approve",p("version",version,"targetFen",target,"note","隔离测试核对"),"test");return null;});}
    void refund(long amount){db.update("update kg_pay_order set refund_amount=:r,status=:status where pay_no='QA_DIRECT_PAY'",p("r",amount,"status",amount==10710?"refunded":"part_refunded"));}
    String reserve(long amount,long store) {
        long version=num(detail(),"version");
        return tx.execute(s->ReflectionTestUtils.<String>invokeMethod(withdrawals,"reserve",billId,store,1L,
            "test_direct_no_fee_request",amount,p("version",version)));
    }

    @Test void discountedDirectBillPaysAllReceiptsWithoutPlatformFee() {
        Map<String,Object> row=detail();
        assertEquals("merchant",row.get("bearer"));assertEquals(0,num(row,"rateBp"));
        assertEquals(true,row.get("directFeeFree"));assertEquals(false,row.get("merchantFeePolicy"));
        assertEquals(10710,num(row,"baseFen"));assertEquals(0,num(row,"feeFen"));
        assertEquals(10710,num(row,"withdrawableFen"));assertEquals(10710,num(row,"suggestTargetFen"));
        assertEquals(0,num(row,"currentServiceFeeFen"));assertEquals(true,row.get("discountShared"));
        assertThrows(ServiceException.class,()->reserve(10711,1));
        assertThrows(ServiceException.class,()->reserve(10710,2));
        String number=reserve(10710,1);
        Map<String,Object> record=db.one("select * from kg_business_withdrawal where withdrawal_no=:n",p("n",number));
        assertEquals(10710,num(record,"amountFen"));assertEquals(0,num(detail(),"withdrawableFen"));
        verify(gateway,never()).submit(anyMap());
    }

    @Test void partialRefundRequiresReviewAndStillLeavesAllRemainingReceipts() {
        refund(5355);
        assertEquals(0,num(detail(),"withdrawableFen"));assertEquals(5355,num(detail(),"suggestTargetFen"));
        assertEquals(0,num(detail(),"currentServiceFeeFen"));
        assertThrows(ServiceException.class,()->reserve(5355,1));
        assertThrows(ServiceException.class,()->approve(5087));
        long oldVersion=num(detail(),"version");approve(5355,oldVersion);
        Map<String,Object> row=detail();assertEquals(5355,num(row,"baseFen"));assertEquals(0,num(row,"feeFen"));
        assertEquals(5355,num(row,"withdrawableFen"));assertEquals(5355,num(row,"sourceNetFen"));
        assertEquals(0,num(row,"platformNetFen"));
        assertThrows(ServiceException.class,()->approve(5355,oldVersion));
        refund(6000);assertEquals(true,detail().get("needsReview"));assertEquals(0,num(detail(),"withdrawableFen"));
    }

    @Test void fullRefundLeavesNoSettlementBalance() {
        refund(10710);approve(0);
        Map<String,Object> row=detail();assertEquals(0,num(row,"baseFen"));assertEquals(0,num(row,"feeFen"));
        assertEquals(0,num(row,"targetFen"));assertEquals("settled",row.get("status"));
        assertEquals(0,num(row,"withdrawableFen"));assertEquals(0,num(row,"platformNetFen"));
    }

    @Test void refundAfterPayoutPreservesReceiptAndRequiresExactReturn() {
        approve(10710);
        tx.execute(s->{bills.command(billId,"receipt",p("version",num(detail(),"version"),"amountFen",10710,
            "note","测试收款记录","proof","isolated-no-transfer"),"test");return null;});
        refund(5355);assertEquals(true,detail().get("needsReview"));approve(5355);
        Map<String,Object> row=detail();assertEquals(10710,num(row,"recordedFen"));
        assertEquals(-5355,num(row,"remainingFen"));assertEquals(0,num(row,"platformNetFen"));
        assertEquals(0,num(row,"withdrawableFen"));
    }

    @Test void pendingRefundOrUncompletedStayCannotSettle() {
        db.update("update kg_stay_order set status='checked_in' where order_no='QA_DIRECT_FREE'",p());
        assertThrows(ServiceException.class,()->approve(10710));assertEquals(0,num(detail(),"withdrawableFen"));
        db.update("update kg_stay_order set status='completed' where order_no='QA_DIRECT_FREE'",p());
        db.insert("kg_pay_refund",p("refund_no","QA_DIRECT_REFUND","pay_no","QA_DIRECT_PAY","amount",5355,
            "reason","测试","operator","test","status","processing"));
        assertThrows(ServiceException.class,()->approve(10710));assertThrows(ServiceException.class,()->reserve(10710,1));
        assertEquals(0,num(detail(),"withdrawableFen"));
    }

    @Test void changedStoreTypeDoesNotRepriceSavedFreeLedger() {
        db.update("update kg_store set op_type='partner' where store_id=1",p());
        refund(5355);approve(5355);
        Map<String,Object> row=detail();assertEquals(true,row.get("directFeeFree"));
        assertEquals(0,num(row,"rateBp"));assertEquals(5355,num(row,"targetFen"));assertEquals(0,num(row,"feeFen"));
    }

    @Test void historicalFivePercentLedgerIsNotRewrittenForCurrentDirectStore() {
        db.update("update kg_business_bill set rate_bp=500,base_fen=10174,fee_fen=536 where bill_id=:b",p("b",billId));
        assertEquals(false,detail().get("directFeeFree"));assertEquals(true,detail().get("merchantFeePolicy"));
        assertEquals(10174,num(detail(),"withdrawableFen"));
        refund(5355);assertThrows(ServiceException.class,()->approve(5355));approve(5087);
        Map<String,Object> row=detail();assertEquals(5087,num(row,"targetFen"));assertEquals(268,num(row,"feeFen"));
        assertEquals(500,num(row,"rateBp"));
    }
}
