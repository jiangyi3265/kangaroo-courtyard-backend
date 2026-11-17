package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
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

@EnabledIfEnvironmentVariable(named = "KG_BILL_TEST_URL", matches = ".*kg_bill_test_[a-z0-9_]+.*")
class KgBusinessBillDatabaseTest {
    private KgJdbc db;
    private KgBusinessBillService service;
    private TransactionTemplate tx;

    @BeforeEach void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("KG_BILL_TEST_URL"),
                System.getenv("KG_BILL_TEST_USER"), System.getenv("KG_BILL_TEST_PASSWORD"));
        db = new KgJdbc(source);
        service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service, "db", db);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        db.update("insert ignore into kg_store(store_id,store_name,city) values(1,'Settlement test','Test'),(2,'Other merchant','Test')", p());
    }

    @Test void realPaymentLedgerCheckoutRefundAndReturn() {
        long order = db.insert("kg_stay_order", p("order_no", "bill-room", "stay_id", 1, "store_id", 1, "room_type_id", 1,
                "app_user_id", 1, "check_in", "2026-11-01", "check_out", "2026-11-02", "nights", 1,
                "guest_name", "Test", "guest_phone", "13000000000", "pay_fen", 21000, "status", "checked_in", "pay_no", "bill-pay"));
        db.insert("kg_pay_order", p("pay_no", "bill-pay", "biz_type", "stay_order", "biz_id", order,
                "app_user_id", 1, "amount", 21000, "description", "test", "channel", "mock", "status", "paid"));
        tx.execute(status -> { service.captureStay(order, p("stay", p("storeId", 1, "stayName", "Test", "typeName", "Room"), "merchantBaseFen", 20000, "platformMarkupFen", 1000)); return null; });
        long id = db.count("select bill_id from kg_business_bill where source_key=:k", p("k", "stay:" + order));
        assertThrows(ServiceException.class, () -> service.detail(id, 2L));
        assertThrows(ServiceException.class, () -> tx.execute(status -> { service.command(id, "approve", p("version", 0, "targetFen", 20000, "note", "review"), "admin:test"); return null; }));
        db.update("update kg_stay_order set status='completed' where order_id=:id", p("id", order));
        command(id, "approve", p("version", 0, "targetFen", 20000, "note", "checkout confirmed"));
        command(id, "receipt", p("version", 1, "amountFen", 20000, "note", "paid", "proof", "transfer-1"));
        assertEquals("settled", service.detail(id, 1L).get("status"));
        assertThrows(ServiceException.class, () -> command(id, "receipt", p("version", 1, "amountFen", 20000, "note", "repeat", "proof", "transfer-1")));
        db.update("update kg_pay_order set refund_amount=10500,status='part_refunded' where pay_no='bill-pay'", p());
        assertEquals(true, service.detail(id, 1L).get("needsReview"));
        command(id, "approve", p("version", 2, "targetFen", 10000, "note", "refund shared equally"));
        assertEquals(-10000L, service.detail(id, 1L).get("remainingFen"));
        command(id, "receipt", p("version", 3, "amountFen", 10000, "note", "merchant returned funds", "proof", "return-1"));
        Map<String, Object> bill = service.detail(id, 1L);
        assertEquals(10000L, bill.get("recordedFen"));
        assertEquals(500L, bill.get("platformNetFen"));
        assertEquals("settled", bill.get("status"));
    }

    @Test void concurrentFulltimeIsOnceAndParttimeIsPerDate() throws Exception {
        long job = db.insert("kg_job", p("store_id", 1, "job_type", "fulltime", "title", "Test job", "city", "Test"));
        long app = db.insert("kg_application", p("app_no", "bill-job", "job_id", job, "store_id", 1, "app_user_id", 1,
                "real_name", "Test worker", "phone", "13000000000", "status", "onboarded", "onboard_time", "2026-11-01 00:00:00"));
        Map<String, Object> input = p("appId", app, "baseFen", 300000, "basis", "first month base agreed");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> task = () -> tx.execute(status -> service.recordWork(input, 1L, "merchant:1"));
            Future<Long> one = pool.submit(task), two = pool.submit(task);
            long id = one.get(15, TimeUnit.SECONDS);
            assertEquals(id, two.get(15, TimeUnit.SECONDS));
            assertEquals(270000L, service.detail(id, 1L).get("workerNetFen"));
            command(id, "approve", p("version", 0, "note", "confirmed"));
            command(id, "receipt", p("version", 1, "amountFen", 30000, "note", "withheld fee received", "proof", "fee-1"));
            assertEquals("settled", service.detail(id, 1L).get("status"));
        } finally { pool.shutdownNow(); }
        long partJob = db.insert("kg_job", p("store_id", 1, "job_type", "parttime", "title", "Daily job", "city", "Test"));
        long partApp = db.insert("kg_application", p("app_no", "bill-daily", "job_id", partJob, "store_id", 1, "app_user_id", 1,
                "real_name", "Test worker", "phone", "13000000000", "status", "onboarded", "onboard_time", "2026-11-01 00:00:00"));
        long daily = tx.execute(status -> service.recordWork(p("appId", partApp, "workDate", "2026-11-02", "baseFen", 20000, "basis", "confirmed day"), 1L, "merchant:1"));
        assertEquals(18000L, service.detail(daily, 1L).get("workerNetFen"));
        assertEquals(2, service.candidates(1L).size());
        assertTrue(service.candidates(2L).isEmpty());
    }

    private void command(long id, String action, Map<String, Object> body) {
        tx.execute(status -> { service.command(id, action, body, "admin:test"); return null; });
    }
}
