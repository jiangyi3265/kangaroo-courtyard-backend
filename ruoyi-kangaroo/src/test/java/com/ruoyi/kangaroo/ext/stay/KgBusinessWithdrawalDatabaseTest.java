package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.WxTransferGateway;

@EnabledIfEnvironmentVariable(named = "KG_BILL_TEST_URL", matches = ".*kg_bill_test_[a-z0-9_]+.*")
class KgBusinessWithdrawalDatabaseTest {
    private KgJdbc db;
    private KgBusinessBillService bills;
    private KgBusinessWithdrawalService service;
    private WxTransferGateway gateway;
    private TransactionTemplate tx;
    private long billId, userId;
    private String payNo, openid;

    @BeforeEach void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("KG_BILL_TEST_URL"), System.getenv("KG_BILL_TEST_USER"), System.getenv("KG_BILL_TEST_PASSWORD"));
        db = new KgJdbc(source);
        bills = new KgBusinessBillService();
        ReflectionTestUtils.setField(bills, "db", db);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        gateway = mock(WxTransferGateway.class);
        when(gateway.canQuery()).thenReturn(true);
        when(gateway.enabled(1L)).thenReturn(true);
        when(gateway.merchantId()).thenReturn("1234567890");
        when(gateway.appId()).thenReturn("wx0123456789abcdef");
        when(gateway.request(anyString(), anyString(), anyLong(), anyString())).thenAnswer(call -> p("out_bill_no", call.getArgument(0), "transfer_amount", call.getArgument(2)));
        when(gateway.query(anyString())).thenReturn(null);
        when(gateway.submit(anyMap())).thenAnswer(call -> {
            Map<?, ?> request = call.getArgument(0);
            assertTrue(request.get("transfer_amount") instanceof Long);
            return p("out_bill_no", request.get("out_bill_no"), "transfer_bill_no", "WX1234567890", "state", "WAIT_USER_CONFIRM", "package_info", "confirmation-package");
        });
        service = new KgBusinessWithdrawalService(db, bills, gateway, manager);
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        openid = "owithdraw" + unique;
        userId = db.insert("kg_app_user", p("openid", openid));
        db.update("insert ignore into kg_store(store_id,store_name,city) values(1,'Settlement test','Test')", p());
        db.insert("kg_staff_grant", p("app_user_id", userId, "store_id", 1, "role_code", "merchant"));
        payNo = "wp" + unique;
        long order = db.insert("kg_stay_order", p("order_no", "wo" + unique, "stay_id", 1, "store_id", 1, "room_type_id", 1,
                "app_user_id", userId, "check_in", "2026-11-01", "check_out", "2026-11-02", "nights", 1,
                "guest_name", "Test", "guest_phone", "13000000000", "pay_fen", 21000, "status", "completed", "pay_no", payNo));
        db.insert("kg_pay_order", p("pay_no", payNo, "biz_type", "stay_order", "biz_id", order, "app_user_id", userId,
                "amount", 21000, "description", "withdrawal fixture", "channel", "wechat", "status", "paid"));
        tx.execute(status -> { bills.captureStay(order, p("stay", p("storeId", 1, "stayName", "Test", "typeName", "Room"), "merchantBaseFen", 20000, "platformMarkupFen", 1000)); return null; });
        billId = db.count("select bill_id from kg_business_bill where source_key=:key", p("key", "stay:" + order));
    }

    private Map<String, Object> request(long amount) {
        return p("requestKey", UUID.randomUUID().toString(), "amountFen", amount, "version", bills.detail(billId, 1L).get("version"));
    }
    private String create(long amount) { return String.valueOf(service.create(billId, 1, userId, request(amount)).get("withdrawalNo")); }
    private Map<String, Object> result(String number, String state, long amount) {
        return p("out_bill_no", number, "transfer_bill_no", "WX1234567890", "state", state, "mch_id", "1234567890",
                "appid", "wx0123456789abcdef", "openid", openid, "transfer_amount", amount);
    }

    @Test void autoApprovalAndExactlyOncePayoutWithSignedIdentityChecks() {
        assertEquals(20000L, bills.detail(billId, 1L).get("withdrawableFen"));
        Map<String, Object> input = request(20000);
        Map<String, Object> transfer = service.create(billId, 1, userId, input);
        String number = String.valueOf(transfer.get("withdrawalNo"));
        assertEquals("WAIT_USER_CONFIRM", transfer.get("state"));
        assertNotNull(transfer.get("params"));
        assertEquals(number, service.create(billId, 1, userId, input).get("withdrawalNo"));
        assertEquals(1, db.count("select count(*) from kg_business_withdrawal where bill_id=:id", p("id", billId)));
        assertEquals(0L, bills.detail(billId, 1L).get("withdrawableFen"));
        assertThrows(ServiceException.class, () -> create(20000));
        assertThrows(ServiceException.class, () -> tx.execute(status -> { bills.command(billId, "receipt", p("version", 1, "note", "manual", "proof", "x", "amountFen", 20000), "admin:test"); return null; }));
        Map<String, Object> mismatch = result(number, "SUCCESS", 20000);
        mismatch.put("openid", "other-person");
        assertThrows(ServiceException.class, () -> service.apply(number, mismatch, true, true));
        assertThrows(ServiceException.class, () -> service.apply(number, result(number, "SUCCESS", 21000), true, true));
        service.apply(number, result(number, "SUCCESS", 20000), true, true);
        service.apply(number, result(number, "SUCCESS", 20000), true, true);
        service.apply(number, result(number, "WAIT_USER_CONFIRM", 20000), true, false);
        Map<String, Object> bill = bills.detail(billId, 1L);
        assertEquals("settled", bill.get("status"));
        assertEquals(20000L, bill.get("recordedFen"));
        assertEquals(1000L, bill.get("platformNetFen"));
        assertEquals(1, db.count("select count(*) from kg_business_bill_log where bill_id=:id and action='receipt'", p("id", billId)));
    }

    @Test void concurrentRequestsReserveOnlyOnce() throws Exception {
        Map<String, Object> input = request(20000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> submit = () -> String.valueOf(service.create(billId, 1, userId, input).get("withdrawalNo"));
            Future<String> first = pool.submit(submit), second = pool.submit(submit);
            assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1, db.count("select count(*) from kg_business_withdrawal where bill_id=:id", p("id", billId)));
    }

    @Test void reservationUsesCurrentReceiptsAfterWaitingForAnotherTransaction() throws Exception {
        tx.execute(status -> { bills.command(billId, "approve", p("version", 0, "targetFen", 20000, "note", "confirmed"), "admin:test"); return null; });
        CountDownLatch initialRead = new CountDownLatch(1);
        KgJdbc observed = spy(db);
        doAnswer(call -> { Object row = call.callRealMethod(); initialRead.countDown(); return row; })
                .when(observed).must(contains("and store_id=:s"), anyMap(), anyString());
        KgBusinessWithdrawalService concurrent = new KgBusinessWithdrawalService(observed, bills, gateway, tx.getTransactionManager());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<Future<Map<String, Object>>> attempt = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            tx.execute(status -> {
                db.must("select pay_id from kg_pay_order where pay_no=:n for update", p("n", payNo), "payment");
                attempt.set(pool.submit(() -> concurrent.create(billId, 1, userId,
                        p("requestKey", UUID.randomUUID().toString(), "amountFen", 20000, "version", 2))));
                try { assertTrue(initialRead.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                bills.command(billId, "receipt", p("version", 1, "amountFen", 10000, "note", "partial manual payment", "proof", "partial-1"), "admin:test");
                return null;
            });
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> attempt.get().get(10, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof ServiceException);
            assertEquals(0, db.count("select count(*) from kg_business_withdrawal where bill_id=:id", p("id", billId)));
            assertEquals(10000L, bills.detail(billId, 1L).get("withdrawableFen"));
        } finally { pool.shutdownNow(); }
    }

    @Test void failureReleasesBalanceAndPartialSuccessLeavesRemainder() {
        String number = create(10000);
        service.apply(number, result(number, "FAIL", 10000), true, true);
        service.apply(number, result(number, "FAIL", 10000), true, true);
        assertEquals(20000L, bills.detail(billId, 1L).get("withdrawableFen"));
        String retry = create(10000);
        assertNotEquals(number, retry);
        service.apply(retry, result(retry, "SUCCESS", 10000), true, true);
        assertEquals(10000L, bills.detail(billId, 1L).get("withdrawableFen"));
        String remaining = create(10000);
        service.apply(remaining, result(remaining, "CANCELLED", 10000), true, true);
        assertEquals(10000L, bills.detail(billId, 1L).get("withdrawableFen"));
    }

    @Test void uncertainNetworkResultKeepsReservationAndRecoversUsingSameNumber() {
        doThrow(new RuntimeException("timeout")).when(gateway).submit(anyMap());
        String number = create(20000);
        assertEquals(20000L, bills.detail(billId, 1L).get("withdrawingFen"));
        assertEquals(0L, bills.detail(billId, 1L).get("recordedFen"));
        when(gateway.query(number)).thenReturn(result(number, "SUCCESS", 20000));
        service.refresh(number, 1, userId);
        assertEquals(20000L, bills.detail(billId, 1L).get("recordedFen"));
        verify(gateway, times(1)).submit(anyMap());
    }

    @Test void checkoutRefundPermissionAndSalaryGuards() {
        db.update("update kg_stay_order set status='checked_in' where pay_no=:n", p("n", payNo));
        assertThrows(ServiceException.class, () -> create(20000));
        db.update("update kg_stay_order set status='completed' where pay_no=:n", p("n", payNo));
        assertThrows(ServiceException.class, () -> service.create(billId, 2, userId, request(20000)));
        assertThrows(ServiceException.class, () -> service.create(billId, 1, userId + 100000, request(20000)));
        assertThrows(ServiceException.class, () -> create(20001));
        db.update("update kg_pay_order set refund_amount=1000,status='part_refunded' where pay_no=:n", p("n", payNo));
        assertThrows(ServiceException.class, () -> create(19000));
        db.update("update kg_pay_order set refund_amount=0,status='paid' where pay_no=:n", p("n", payNo));
        db.update("update kg_business_bill set kind='fulltime' where bill_id=:id", p("id", billId));
        assertThrows(ServiceException.class, () -> create(1000));
    }

    @Test void refundDuringTransferIsReconciledAfterArrivalAndOtherUsersCannotClaim() {
        String number = create(20000);
        assertThrows(ServiceException.class, () -> service.refresh(number, 1, userId + 1));
        assertThrows(ServiceException.class, () -> service.refresh(number, 2, userId));
        db.update("update kg_pay_order set refund_amount=10500,status='part_refunded' where pay_no=:n", p("n", payNo));
        service.apply(number, result(number, "SUCCESS", 20000), true, true);
        assertEquals(true, bills.detail(billId, 1L).get("needsReview"));
        assertEquals(0L, bills.detail(billId, 1L).get("withdrawableFen"));
        tx.execute(status -> { bills.command(billId, "approve", p("version", 2, "targetFen", 10000, "note", "refund split"), "admin:test"); return null; });
        assertEquals(-10000L, bills.detail(billId, 1L).get("remainingFen"));
    }

    @Test void largeAmountRequiresWechatRealName() {
        db.update("update kg_business_bill set base_fen=200000,fee_fen=10000 where bill_id=:id", p("id", billId));
        db.update("update kg_pay_order set amount=210000 where pay_no=:n", p("n", payNo));
        assertThrows(ServiceException.class, () -> create(200000));
        assertEquals(0, db.count("select count(*) from kg_business_withdrawal where bill_id=:id", p("id", billId)));
        Map<String, Object> input = request(200000);
        input.put("realName", "测试姓名");
        assertEquals("WAIT_USER_CONFIRM", service.create(billId, 1, userId, input).get("state"));
    }
}
