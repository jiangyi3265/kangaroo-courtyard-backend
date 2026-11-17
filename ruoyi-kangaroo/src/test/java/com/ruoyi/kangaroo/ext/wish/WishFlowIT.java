package com.ruoyi.kangaroo.ext.wish;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.mock.web.MockMultipartFile;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.*;
import com.ruoyi.kangaroo.service.IKgMessageService;

/** Real MySQL transactions in a disposable, separately named database; no network payments. */
class WishFlowIT {
    private String database; private JdbcTemplate root,sql; private WishService service;private KgPayService pay;
    DataSource source(String db){String password=System.getenv("KG_TEST_DB_PASSWORD");assertNotNull(password,"Set KG_TEST_DB_PASSWORD for isolated MySQL tests");return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+db+"?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false","root",password);}
    <T>T proxy(T target,DataSourceTransactionManager manager){ProxyFactory f=new ProxyFactory(target);f.setProxyTargetClass(true);f.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)f.getProxy();}
    @BeforeEach void setup()throws Exception{
        database="kg_wish_it_"+UUID.randomUUID().toString().replace("-","").substring(0,8);root=new JdbcTemplate(source("dkxy"));root.execute("create database "+database+" character set utf8mb4 collate utf8mb4_unicode_ci");
        DataSource ds=source(database);sql=new JdbcTemplate(ds);
        sql.execute("create table sys_config(config_key varchar(100) primary key,config_value varchar(100))");
        sql.execute("insert into sys_config values('kangaroo.wish.enabled','true')");
        String migration=Files.readString(Path.of("../../.dev/migrations/V87_wish_body.sql"));
        for(String statement:migration.substring(0,migration.indexOf("insert into sys_menu")).split(";"))if(!statement.isBlank())sql.execute(statement);
        String eligibility = Files.readString(Path.of("../../.dev/migrations/V96_wish_eligibility_body.sql"));
        java.util.regex.Matcher additions = java.util.regex.Pattern.compile("call kg_add_(col|idx)_if_absent\\('([^']+)', '[^']+', '(.*)'\\);").matcher(eligibility);
        while(additions.find())sql.execute("alter table "+additions.group(2)+" add "+("col".equals(additions.group(1))?"column ":"")+additions.group(3).replace("''","'"));
        String communication=Files.readString(Path.of("../../acceptance/wish-order-20261116/V114_body.sql"));
        java.util.regex.Matcher columns=java.util.regex.Pattern.compile("call kg_add_col_if_absent\\('([^']+)', '[^']+', '(.*)'\\);").matcher(communication);
        while(columns.find())sql.execute("alter table "+columns.group(1)+" add column "+columns.group(2).replace("''","'"));
        sql.execute(communication.substring(communication.indexOf("create table"),communication.indexOf("call kg_assert_same_collation")).trim().replaceAll(";$",""));
        sql.execute("create table kg_app_user(user_id bigint primary key,nickname varchar(80),avatar varchar(255))");
        sql.execute("insert into kg_app_user values(1,'发布者',''),(2,'服务者',''),(3,'另一位服务者','')");
        sql.execute("create table kg_idempotency like dkxy.kg_idempotency");sql.execute("create table kg_pay_order like dkxy.kg_pay_order");
        // Match production's already-normalized collation; the legacy V87 fixture predates that normalization.
        sql.execute("alter table kg_wish convert to character set utf8mb4 collate utf8mb4_unicode_ci");
        sql.execute("alter table kg_pay_order convert to character set utf8mb4 collate utf8mb4_unicode_ci");
        KgJdbc db=new KgJdbc(ds);DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);
        WishService target=new WishService();ReflectionTestUtils.setField(target,"db",db);ReflectionTestUtils.setField(target,"notices",mock(IKgMessageService.class));service=proxy(target,manager);
        KgPayService payment=new KgPayService();ReflectionTestUtils.setField(payment,"db",db);ReflectionTestUtils.setField(payment,"acceptNew",true);
        KangarooProperties config=mock(KangarooProperties.class);when(config.getPayMode()).thenReturn("mock");ReflectionTestUtils.setField(payment,"properties",config);ReflectionTestUtils.setField(payment,"listeners",List.of(service));pay=proxy(payment,manager);ReflectionTestUtils.setField(target,"payments",pay);
    }
    @AfterEach void cleanup(){if(root!=null&&database!=null&&database.matches("kg_wish_it_[a-f0-9]{8}"))root.execute("drop database "+database);}
    void approve(long id){Map<String,Object> w=service.adminDetail(id);service.adminAction(id,"approve",p("note","内容与图片已人工核实","contentChecked",true,"complianceTypes",w.get("detectedTypes")),"reviewer");}
    Map<String,Object> body(boolean free){return p("requestKey",UUID.randomUUID().toString(),"title","帮我寄一张明信片","description","请从当地选一张风景明信片并填写祝福寄出。","mode",free?"free":"paid","cost","100","category","postcard","originCity","辽宁大连","targetCity","广西阳朔","delivery","express","deadline",LocalDate.now().plusDays(7).toString(),"images",List.of("/static/media/wish-hero.jpg"),"agreed",true);}
    long publish(boolean free){Map<String,Object>w=service.publish(1,body(free));if(!free)pay.mockConfirm((String)w.get("payNo"),1);long id=((Number)w.get("wishId")).longValue();approve(id);return id;}
    @Test void paidLifecycleConservesMoneyAndPayoutIsNotAutomatic(){long id=publish(false);Map<String,Object> accepted=service.accept(id,2);assertEquals(10100L,accepted.get("amountFen"));assertThrows(ServiceException.class,()->service.action(id,1,"deliver",p("note","已完成交付","images",List.of("/profile/proof.jpg"))));assertThrows(ServiceException.class,()->service.action(id,1,"complete",p()));service.action(id,2,"deliver",p("note","已寄出，请查看物流凭证","images",List.of("/profile/proof.jpg")));Map<String,Object> done=service.action(id,1,"complete",p());assertEquals("completed",done.get("status"));assertEquals("pending",done.get("payoutStatus"));assertEquals(9595L,done.get("netFen"));service.action(id,1,"complete",p());assertEquals(1,sql.queryForObject("select count(*) from kg_wish_audit where action='complete'",Integer.class));assertThrows(ServiceException.class,()->service.adminAction(id,"payout",p("note","核实打款","receipt","TEST-RECEIPT-1","amountFen",9595L),"finance"));sql.update("insert into kg_wish_credential(user_id,type,file_key,status) values(2,'payout','private-fixture','approved')");service.adminAction(id,"payout",p("note","核实打款","receipt","TEST-RECEIPT-1","amountFen",9595L),"finance");assertEquals("paid",service.detail(id,2L).get("payoutStatus"));assertThrows(ServiceException.class,()->service.adminAction(id,"payout",p("note","重复打款","receipt","TEST-RECEIPT-2","amountFen",9595L),"finance"));assertEquals(1,sql.queryForObject("select count(*) from kg_wish_payout",Integer.class));}
    @Test void freeNeverCreatesPaymentOrFee(){long id=publish(true);service.accept(id,2);service.action(id,2,"deliver",p("note","免费心愿已经完成","images",List.of("/profile/proof.jpg")));Map<String,Object> result=service.action(id,1,"complete",p());for(String k:List.of("costFen","markupFen","amountFen","feeFen","netFen"))assertEquals(0L,result.get(k));assertEquals("none",result.get("payoutStatus"));assertEquals(0,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));}
    @Test void duplicatePublishRetriesReuseSinglePayment()throws Exception{Map<String,Object> b=body(false);ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier gate=new CyclicBarrier(2);try{Callable<Object>task=()->{gate.await();return service.publish(1,b).get("wishId");};Future<Object> a=pool.submit(task);Future<Object> c=pool.submit(task);assertEquals(a.get(20,TimeUnit.SECONDS),c.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}assertEquals(1,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));assertEquals(1,sql.queryForObject("select count(*) from kg_wish",Integer.class));}
    @Test void simultaneousAcceptHasExactlyOneWinner()throws Exception{long id=publish(true);ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier gate=new CyclicBarrier(2);List<Future<Boolean>>results=new ArrayList<>();try{for(long user:List.of(2L,3L))results.add(pool.submit(()->{gate.await();try{service.accept(id,user);return true;}catch(ServiceException e){return false;}}));int won=0;for(Future<Boolean> f:results)if(f.get(15,TimeUnit.SECONDS))won++;assertEquals(1,won);}finally{pool.shutdownNow();}}
    @Test void privacyAndRoleChecks(){long id=publish(true);assertThrows(ServiceException.class,()->service.accept(id,1));Map<String,Object> publicView=service.detail(id,null);for(String key:List.of("payNo","proof","requestKey","deliveryNote","dispute","payoutReceipt"))assertFalse(publicView.containsKey(key));service.accept(id,2);assertThrows(ServiceException.class,()->service.detail(id,3L));assertThrows(ServiceException.class,()->service.messages(id,3,0));assertThrows(ServiceException.class,()->service.message(id,3,p("requestKey",UUID.randomUUID().toString(),"content","未经授权")));Map<String,Object> b=p("requestKey",UUID.randomUUID().toString(),"content","你好，沟通寄送细节");service.message(id,2,b);service.message(id,2,b);assertEquals(1,service.messages(id,1,0).size());}
    @Test void foodAndPermitAreServerGated(){for(String cat:List.of("food","licensed")){Map<String,Object> b=body(true);b.put("category",cat);long id=((Number)service.publish(1,b).get("wishId")).longValue();approve(id);assertThrows(ServiceException.class,()->service.accept(id,2));sql.update("insert into kg_wish_credential(user_id,type,city,file_key,status,valid_from,valid_until,permit_scope) values(2,?,?, 'private-fixture','approved',date_sub(curdate(),interval 1 day),date_add(curdate(),interval 1 day),6)",cat.equals("food")?"health":"permit",cat.equals("food")?"":"其他城市");if(cat.equals("licensed")){assertThrows(ServiceException.class,()->service.accept(id,2));sql.update("update kg_wish_credential set city='广西阳朔' where type='permit'");}assertEquals("accepted",service.accept(id,2).get("status"));}}
    @Test void refundsAndDisputeFreeze(){long id=publish(false);service.action(id,1,"cancel",p());assertEquals("cancelled",service.detail(id,1L).get("status"));assertEquals(10100L,sql.queryForObject("select refund_amount from kg_pay_order",Long.class));long dispute=publish(false);service.accept(dispute,2);service.action(dispute,1,"dispute",p("note","交付内容与约定不同"));assertThrows(ServiceException.class,()->service.action(dispute,2,"deliver",p("note","试图绕过争议状态","images",List.of("/profile/proof.jpg"))));assertThrows(ServiceException.class,()->service.action(dispute,1,"complete",p()));service.adminAction(dispute,"refund",p("note","核实后全额退款"),"reviewer");assertEquals("cancelled",service.detail(dispute,1L).get("status"));}
    @Test void rejectsRestrictedCategoryAndTamperedPrice(){Map<String,Object> b=body(false);b.put("category","medicine");assertThrows(ServiceException.class,()->service.publish(1,b));b.put("category","postcard");b.put("amountFen",1);Map<String,Object> w=service.publish(1,b);assertEquals(10100L,w.get("amountFen"));assertEquals(505L,w.get("feeFen"));}
    // 有偿心愿 30 分钟没付款：定时任务关单、关支付单；过期后不能再拉起支付或联调确认
    @Test void unpaidWishExpiresAndCannotBePaidLate(){
        Map<String,Object> w=service.publish(1,body(false));long id=((Number)w.get("wishId")).longValue();String no=(String)w.get("payNo");
        ReflectionTestUtils.setField((Object)AopTestUtils.getTargetObject(service),"self",service);
        service.closeExpired();
        assertEquals("pending_payment",service.detail(id,1L).get("status"),"30 分钟内不关");
        sql.update("update kg_wish set expire_time=date_sub(now(),interval 1 minute) where wish_id=?",id);
        assertThrows(ServiceException.class,()->pay.prepay(no,1));
        assertThrows(ServiceException.class,()->pay.mockConfirm(no,1));
        service.closeExpired();
        assertEquals("cancelled",service.detail(id,1L).get("status"));
        assertEquals("closed",pay.byNo(no).get("status"));
        assertEquals(1,sql.queryForObject("select count(*) from kg_wish_audit where action='expire'",Integer.class));
        assertFalse(service.expireOne(id),"已关的不再处理");
    }
    @Test void genericRefundCannotBypassWishSettlement(){
        long id=publish(false);String no=(String)service.detail(id,1L).get("payNo");
        assertThrows(ServiceException.class,()->pay.refund(no,10100,"绕过心愿状态","admin"));
        assertEquals("paid",pay.byNo(no).get("status"));
        service.accept(id,2);service.action(id,2,"deliver",p("note","交付已完成请确认","images",List.of("/profile/proof.jpg")));service.action(id,1,"complete",p());
        assertThrows(ServiceException.class,()->pay.refund(no,10100,"试图退款已结算订单","admin"));
        assertEquals("pending",service.detail(id,1L).get("payoutStatus"));assertEquals("paid",pay.byNo(no).get("status"));
    }

    @Test void concurrentCompletionRetriesBothReturnCommittedStatus() throws Exception {
        long id = publish(false);
        service.accept(id, 2);
        service.action(id, 2, "deliver", p("note", "交付照片已上传，请核实", "images", List.of("/profile/proof.jpg")));
        WishService target = AopTestUtils.getTargetObject(service);
        KgJdbc original = (KgJdbc) ReflectionTestUtils.getField(target, "db");
        KgJdbc observed = spy(original);
        CyclicBarrier reads = new CyclicBarrier(2);
        AtomicInteger firstReads = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (firstReads.incrementAndGet() <= 2) reads.await(10, TimeUnit.SECONDS);
            return result;
        }).when(observed).must(eq("select * from kg_wish where wish_id=:id"), anyMap(), anyString());
        ReflectionTestUtils.setField(target, "db", observed);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Map<String,Object>> complete = () -> service.action(id, 1, "complete", p());
            Future<Map<String,Object>> first = pool.submit(complete);
            Future<Map<String,Object>> retry = pool.submit(complete);
            for (Future<Map<String,Object>> result : List.of(first, retry)) {
                Map<String,Object> response = result.get(20, TimeUnit.SECONDS);
                assertEquals("completed", response.get("status"));
                assertEquals("pending", response.get("payoutStatus"));
            }
        } finally {
            ReflectionTestUtils.setField(target, "db", original);
            pool.shutdownNow();
        }
        assertEquals(1, sql.queryForObject("select count(*) from kg_wish_audit where action='complete'", Integer.class));
    }

    @Test void refundFailureCanBeRetriedWithoutChangingAmountOrSettling() {
        long id = publish(false);
        String no = (String) service.detail(id, 1L).get("payNo");
        sql.update("update kg_wish set status='refunding' where wish_id=?", id);
        service.onRefundClosed(pay.byNo(no), 10100);
        assertEquals("refund_failed", service.detail(id, 1L).get("status"));
        assertThrows(ServiceException.class, () -> service.action(id, 2, "cancel", p()));
        assertThrows(ServiceException.class, () -> service.adminAction(id, "payout", p("note", "不可提前结算", "receipt", "NO-PAYOUT", "amountFen", 9595), "finance"));
        Map<String,Object> result = service.action(id, 1, "cancel", p());
        assertEquals("cancelled", result.get("status"));
        assertEquals("none", result.get("payoutStatus"));
        assertEquals(10100L, pay.byNo(no).get("refundAmount"));
        assertEquals(0, sql.queryForObject("select count(*) from kg_wish_payout", Integer.class));
    }

    @Test void payoutRejectsWrongAmountsRefundedPaymentsAndReusedReceipts() {
        sql.update("insert into kg_wish_credential(user_id,type,file_key,status) values(2,'payout','private-fixture','approved')");
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            long id = publish(false);
            service.accept(id, 2);
            service.action(id, 2, "deliver", p("note", "已经完成约定交付内容", "images", List.of("/profile/proof.jpg")));
            service.action(id, 1, "complete", p());
            ids.add(id);
        }
        long first = ids.get(0), second = ids.get(1);
        Map<String,Object> payout = p("note", "隔离测试实际打款登记", "receipt", "RECHECK-UNIQUE-RECEIPT", "amountFen", 9595L);
        assertThrows(ServiceException.class, () -> service.adminAction(first, "payout", p("note", "金额不一致", "receipt", "RECHECK-WRONG-AMOUNT", "amountFen", 10000), "finance"));
        String no = (String) service.detail(first, 1L).get("payNo");
        sql.update("update kg_pay_order set refund_amount=1 where pay_no=?", no);
        assertThrows(ServiceException.class, () -> service.adminAction(first, "payout", payout, "finance"));
        sql.update("update kg_pay_order set refund_amount=0 where pay_no=?", no);
        service.adminAction(first, "payout", payout, "finance");
        ServiceException reused = assertThrows(ServiceException.class, () -> service.adminAction(second, "payout", payout, "finance"));
        assertEquals(409, reused.getCode());
        assertEquals("pending", service.detail(second, 2L).get("payoutStatus"));
        assertEquals(1, sql.queryForObject("select count(*) from kg_wish_payout", Integer.class));
        assertEquals(1, sql.queryForObject("select count(*) from kg_wish_audit where action='payout'", Integer.class));
    }

    @Test void credentialRevocationBlocksFutureRestrictedAcceptances() {
        Map<String,Object> request = body(true);
        request.put("category", "food");
        long id = ((Number) service.publish(1, request).get("wishId")).longValue();
        approve(id);
        sql.update("insert into kg_wish_credential(user_id,type,file_key,status,valid_from,valid_until) values(2,'health','private-fixture','approved',date_sub(curdate(),interval 1 day),date_add(curdate(),interval 1 day))");
        long credential = sql.queryForObject("select credential_id from kg_wish_credential where user_id=2", Long.class);
        service.reviewCredential(credential, false, "人工核实证照已失效，撤销审核", "reviewer");
        assertThrows(ServiceException.class, () -> service.accept(id, 2));
        assertEquals("open", service.detail(id, 1L).get("status"));
    }

    long restrictedWish(String text, boolean free) {
        Map<String,Object> request=body(free);
        request.put("category","errand");request.put("title",text);request.put("description","请按标题的实际商品准备并交付，包装保持完整。");
        Map<String,Object> wish=service.publish(1,request);
        if(!free)pay.mockConfirm((String)wish.get("payNo"),1);
        long id=((Number)wish.get("wishId")).longValue();approve(id);return id;
    }
    LocalDate chinaToday(){return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));}
    void grant(String type,String city,int scope,LocalDate start,LocalDate end) {
        sql.update("insert into kg_wish_credential(user_id,type,city,file_key,status,valid_from,valid_until,permit_scope) values(2,?,?,'test-proof','approved',?,?,?)",type,city,start.toString(),end.toString(),scope);
    }
    @Test void crossCategoryAndFreeDoNotBypassFoodCredentials() {
        for(boolean free:new boolean[]{true,false}){
            long id=restrictedWish("帮买螺\u200b蛳　粉",free);
            assertEquals(List.of("food"),service.detail(id,3L).get("requiredTypes"));
            assertThrows(ServiceException.class,()->service.accept(id,2));
            grant("health","",0,chinaToday().minusDays(2),chinaToday().minusDays(1));
            assertThrows(ServiceException.class,()->service.accept(id,2));
            sql.update("delete from kg_wish_credential");
            grant("food","",0,chinaToday().plusDays(1),chinaToday().plusDays(3));
            assertThrows(ServiceException.class,()->service.accept(id,2));
            sql.update("delete from kg_wish_credential");
            grant("food","",0,chinaToday().minusDays(1),chinaToday().plusDays(1));
            assertEquals("accepted",service.accept(id,2).get("status"));
            sql.update("delete from kg_wish_credential");
        }
    }
    @Test void tobaccoAndAlcoholRequireMatchingCityAndSeparateScope() {
        long alcohol=restrictedWish("代买一箱啤，酒",true);
        grant("permit","广西阳朔",WishEligibility.TOBACCO,chinaToday().minusDays(1),chinaToday().plusDays(1));
        assertThrows(ServiceException.class,()->service.accept(alcohol,2));
        grant("permit","广西桂林",WishEligibility.ALCOHOL,chinaToday().minusDays(1),chinaToday().plusDays(1));
        assertThrows(ServiceException.class,()->service.accept(alcohol,2));
        grant("permit","广西　阳朔",WishEligibility.ALCOHOL,chinaToday().minusDays(1),chinaToday().plusDays(1));
        assertEquals("accepted",service.accept(alcohol,2).get("status"));
        long tobacco=restrictedWish("购买香\u2060烟寄送",true);
        sql.update("delete from kg_wish_credential where permit_scope=2");
        assertThrows(ServiceException.class,()->service.accept(tobacco,2));
    }
    @Test void commonFoodNamesAcrossOrdinaryCategoriesStillRequireCredentials(){
        for(String item:List.of("鸡蛋","大米","牛肉","猪肉","食盐","面条")){
            long id=restrictedWish("帮我采购一些"+item,true);
            assertThrows(ServiceException.class,()->service.accept(id,2),item);
        }
    }
    @Test void reviewCannotEraseDetectedRiskAndNeedsExplicitImageCheck() {
        Map<String,Object> request=body(true);request.put("description","请帮我购买一箱啤酒并寄到大连。");
        long id=((Number)service.publish(1,request).get("wishId")).longValue();
        assertThrows(ServiceException.class,()->service.adminAction(id,"approve",p("note","口头同意通过"),"reviewer"));
        assertThrows(ServiceException.class,()->service.adminAction(id,"approve",p("note","误选普通类别","contentChecked",true,"complianceTypes",List.of("ordinary")),"reviewer"));
        assertEquals("reviewing",service.detail(id,1L).get("status"));approve(id);
        service.adminAction(id,"classify",p("note",String.join("",Collections.nCopies(500,"核")),"contentChecked",true,"complianceTypes",List.of("alcohol")),"reviewer");
        String audit=sql.queryForObject("select note from kg_wish_audit where action='classify' order by audit_id desc limit 1",String.class);
        assertTrue(audit.contains("alcohol"));assertTrue(audit.contains("内容摘要"));assertEquals(500,audit.length());
        assertThrows(ServiceException.class,()->service.adminAction(id,"classify",p("note","尝试降低限制","contentChecked",true,"complianceTypes",List.of("ordinary")),"reviewer"));
        assertThrows(ServiceException.class,()->service.accept(id,2));
    }
    // 关键词误判（「咖啡色」被当成食品）：后台勾「关键词误判」才能改判；所选类目带的范围改判不掉；之后普通复核不会把关键词结果叠回来
    @Test void keywordMisjudgmentCanBeCorrectedOnlyExplicitly() {
        Map<String,Object> request=body(true);request.put("description","请帮我寄一个咖啡色的手工钱包，到付即可。");
        long id=((Number)service.publish(1,request).get("wishId")).longValue();
        assertThrows(ServiceException.class,()->service.adminAction(id,"approve",p("note","咖啡色是颜色","contentChecked",true,"complianceTypes",List.of("ordinary")),"reviewer"));
        service.adminAction(id,"approve",p("note","咖啡色是颜色，不是食品","contentChecked",true,"keywordMisjudged",true,"complianceTypes",List.of("ordinary")),"reviewer");
        assertTrue(sql.queryForObject("select note from kg_wish_audit where action='approve' order by audit_id desc limit 1",String.class).startsWith("[关键词误判改判]"));
        assertEquals(List.of("ordinary"),service.adminDetail(id).get("detectedTypes"));
        assertEquals("accepted",service.accept(id,2).get("status"));
        service.adminAction(id,"classify",p("note","再次核对仍是普通手工品","contentChecked",true,"complianceTypes",List.of("ordinary")),"reviewer");
        assertEquals(List.of("ordinary"),service.adminDetail(id).get("detectedTypes"));
        Map<String,Object> food=body(true);food.put("category","food");
        long f=((Number)service.publish(1,food).get("wishId")).longValue();
        assertThrows(ServiceException.class,()->service.adminAction(f,"approve",p("note","误判","contentChecked",true,"keywordMisjudged",true,"complianceTypes",List.of("ordinary")),"reviewer"));
    }
    @Test void foodAdviceNeedsExplicitHumanClassificationThenOrdinaryUserCanHelp() {
        Map<String,Object> request=body(true);
        request.put("category","food");request.put("delivery","digital");
        request.put("title","想了解云南特色美食");request.put("description","请介绍云南有哪些好吃的米线和小吃，只需要线上文字回答。");
        long id=((Number)service.publish(1,request).get("wishId")).longValue();
        approve(id);
        assertThrows(ServiceException.class,()->service.accept(id,2));
        service.adminAction(id,"classify",p("note","核对正文图片，只有美食信息问答，无实物交付","contentChecked",true,"informationOnly",true,"complianceTypes",List.of("ordinary")),"reviewer");
        assertEquals(true,service.adminDetail(id).get("informationOnly"));
        assertEquals(List.of("ordinary"),service.detail(id,2L).get("requiredTypes"));
        assertEquals("accepted",service.accept(id,2).get("status"));
        assertThrows(ServiceException.class,()->service.action(id,2,"deliver",p("note","顺便代买米线并寄送过去","images",List.of("/profile/proof.jpg"))));
        service.action(id,2,"deliver",p("note","已介绍云南米线和小吃的特色与攻略","images",List.of("/profile/proof.jpg")));
        assertEquals("completed",service.action(id,1,"complete",p()).get("status"));
    }
    @Test void adviceFlagCannotWaivePhysicalFoodOrSurviveDeliveryChange() {
        Map<String,Object> request=body(true);request.put("category","food");request.put("delivery","express");
        long id=((Number)service.publish(1,request).get("wishId")).longValue();
        Map<String,Object> classification=p("note","核对咨询","contentChecked",true,"informationOnly",true,"complianceTypes",List.of("ordinary"));
        assertThrows(ServiceException.class,()->service.adminAction(id,"approve",classification,"reviewer"));
        sql.update("update kg_wish set delivery='digital' where wish_id=?",id);
        service.adminAction(id,"approve",classification,"reviewer");
        sql.update("update kg_wish set delivery='meet' where wish_id=?",id);
        assertEquals(false,service.detail(id,2L).get("informationOnly"));
        assertThrows(ServiceException.class,()->service.accept(id,2));
    }
    @Test void directPublishingRejectsObfuscatedProhibitedGoodsWithoutCreatingOrders() {
        for(String text:List.of("代买布 洛 芬", "购买烟，花", "代办身\u200b份证", "安排高空\u2060作业", "购买活体动物", "提供法 律 咨 询")){
            Map<String,Object> request=body(false);request.put("title",text);
            assertThrows(ServiceException.class,()->service.publish(1,request),text);
        }
        assertEquals(0,sql.queryForObject("select count(*) from kg_wish",Integer.class));
        assertEquals(0,sql.queryForObject("select count(*) from kg_pay_order",Integer.class));
    }
    @Test void safeAnimalHotelGameAndDesignContentStillWorks() {
        for(String text:List.of("购买猫砂送到酒店", "拍动物园照片做游戏素材", "设计食品包装设计图", "买小猫玩具和明信片", "制作游戏弹药图标", "制作玩具子弹模型", "购买苹果手机壳")){
            long id=restrictedWish(text,true);assertEquals("accepted",service.accept(id,2).get("status"));
        }
        long ambiguous=restrictedWish("啤酒瓶空瓶工艺制作",true);
        assertThrows(ServiceException.class,()->service.accept(ambiguous,2));
    }
    @Test void legacyDataRecheckedAndRefundsStayAvailable() {
        long id=publish(false);sql.update("update kg_wish set title='帮忙购买螺蛳粉',reviewed_requirements=null,review_content_hash=null where wish_id=?",id);
        assertThrows(ServiceException.class,()->service.accept(id,2));
        assertEquals("cancelled",service.action(id,1,"cancel",p()).get("status"));
        long safe=publish(true);sql.update("update kg_wish set images='',reviewed_requirements=null,review_content_hash=null where wish_id=?",safe);
        assertEquals("accepted",service.accept(safe,2).get("status"));
        long photo=publish(true);sql.update("update kg_wish set reviewed_requirements=null,review_content_hash=null where wish_id=?",photo);
        assertThrows(ServiceException.class,()->service.accept(photo,2));
        service.adminAction(photo,"classify",p("note","核对原始图片为明信片","contentChecked",true,"complianceTypes",List.of("ordinary")),"reviewer");
        assertEquals("accepted",service.accept(photo,2).get("status"));
    }
    @Test void changedOriginalImageAndDeliveryContentRequireReview() {
        long id=publish(true);service.accept(id,2);
        assertThrows(ServiceException.class,()->service.message(id,1,p("requestKey",UUID.randomUUID().toString(),"content","改成寄一箱啤酒")));
        assertThrows(ServiceException.class,()->service.message(id,2,p("requestKey",UUID.randomUUID().toString(),"content","改成寄一箱啤酒")));
        assertEquals(0,sql.queryForObject("select count(*) from kg_wish_message",Integer.class));
        service.message(id,1,p("requestKey",UUID.randomUUID().toString(),"content","请不要寄香烟，对方寄来药品，我要求退款"));
        assertEquals(1,sql.queryForObject("select count(*) from kg_wish_message",Integer.class));
        assertThrows(ServiceException.class,()->service.action(id,2,"deliver",p("note","已寄出两包螺蛳粉","images",List.of("/profile/proof.jpg"))));
        sql.update("update kg_wish set images='/profile/changed.jpg' where wish_id=?",id);
        assertThrows(ServiceException.class,()->service.action(id,2,"deliver",p("note","按原来约定交付","images",List.of("/profile/proof.jpg"))));
        assertEquals("disputed",service.action(id,1,"dispute",p("note","实际内容变更请平台处理")).get("status"));
        service.adminAction(id,"refund",p("note","核实关闭本次心愿"),"reviewer");
        assertEquals("cancelled",service.detail(id,1L).get("status"));
    }
    @Test void credentialsAreRecheckedAtDeliveryButLaterExpiryDoesNotFreezeSettlement() {
        long id=restrictedWish("寄送当地特色糕点",false);
        grant("health","",0,chinaToday().minusDays(2),chinaToday().plusDays(1));service.accept(id,2);
        sql.update("update kg_wish_credential set valid_until=date_sub(curdate(),interval 1 day)");
        assertThrows(ServiceException.class,()->service.action(id,2,"deliver",p("note","糕点已经按约寄送","images",List.of("/profile/proof.jpg"))));
        sql.update("update kg_wish_credential set valid_until=date_add(curdate(),interval 1 day)");
        service.action(id,2,"deliver",p("note","糕点已经按约寄送","images",List.of("/profile/proof.jpg")));
        sql.update("update kg_wish_credential set valid_until=date_sub(curdate(),interval 1 day)");
        assertEquals("completed",service.action(id,1,"complete",p()).get("status"));
        assertEquals(505L,service.detail(id,1L).get("feeFen"));
    }
    @Test void legacySubmittedOrderHasAuditedHistoricalQualificationRoute() {
        long id=publish(true);service.accept(id,2);service.action(id,2,"deliver",p("note","已经寄送原定明信片","images",List.of("/profile/proof.jpg")));
        sql.update("update kg_wish set fulfillment_requirements=null,fulfillment_qualification=null where wish_id=?",id);
        assertThrows(ServiceException.class,()->service.action(id,1,"complete",p()));
        assertThrows(ServiceException.class,()->service.adminAction(id,"verifyFulfillment",p("note","未核验证据","serviceDate",LocalDate.now().toString()),"reviewer"));
        service.adminAction(id,"verifyFulfillment",p("note","核对交付图片与原始明信片约定","serviceDate",LocalDate.now().toString(),"fulfillmentChecked",true),"reviewer");
        assertEquals("completed",service.action(id,1,"complete",p()).get("status"));
        assertEquals(1,sql.queryForObject("select count(*) from kg_wish_audit where action='verifyFulfillment'",Integer.class));
    }
    @Test void credentialApprovalRequiresVerifiedDatesAndScope() {
        sql.update("insert into kg_wish_credential(user_id,type,city,file_key) values(2,'permit','广西阳朔','private-fixture')");
        long id=sql.queryForObject("select credential_id from kg_wish_credential",Long.class);
        assertThrows(ServiceException.class,()->service.reviewCredential(id,true,"审核同意通过","reviewer"));
        Map<String,Object> review=p("note","核实许可原件与烟草范围","documentChecked",true,"city","广西阳朔","validFrom",LocalDate.now().minusDays(2).toString(),"validUntil",LocalDate.now().plusDays(2).toString(),"permitType","tobacco");
        service.reviewCredential(id,true,review,"reviewer");
        assertEquals(2,sql.queryForObject("select permit_scope from kg_wish_credential",Integer.class));
        long alcohol=restrictedWish("购买两瓶当地白酒",true);assertThrows(ServiceException.class,()->service.accept(alcohol,2));
        review.put("validUntil",LocalDate.now().minusDays(3).toString());
        assertThrows(ServiceException.class,()->service.reviewCredential(id,true,review,"reviewer"));
    }

    @Test void cancelledAndDisputedOrdersCannotBeDeliveredOrPrematurelyPaidOut() {
        long id = publish(false);
        service.accept(id, 2);
        assertThrows(ServiceException.class, () -> service.action(id, 1, "cancel", p()));
        assertThrows(ServiceException.class, () -> service.action(id, 2, "deliver", p("note", "缺少交付照片", "images", List.of())));
        assertThrows(ServiceException.class, () -> service.action(id, 3, "dispute", p("note", "无权申请平台介入")));
        service.action(id, 1, "dispute", p("note", "需要平台核实交付情况"));
        assertThrows(ServiceException.class, () -> service.action(id, 1, "complete", p()));
        assertThrows(ServiceException.class, () -> service.adminAction(id, "payout", p("note", "争议中禁止打款", "receipt", "RECHECK-DISPUTE", "amountFen", 9595), "finance"));
        service.adminAction(id, "refund", p("note", "核实后全额退款关闭"), "reviewer");
        assertThrows(ServiceException.class, () -> service.action(id, 2, "deliver", p("note", "退款后不能交付", "images", List.of("/profile/proof.jpg"))));
        assertEquals("cancelled", service.detail(id, 1L).get("status"));
    }

    @Test void bothParticipantsFindTheSameOrderAndNoSecondHelperCanAccept(){
        long id=publish(true);service.accept(id,2);
        for(String role:List.of("publisher","helper")){
            long user=role.equals("publisher")?1:2;
            Map<String,Object> page=service.list(user,role,"accepted","","","","","","","latest",1);
            List<Map<String,Object>> rows=(List<Map<String,Object>>)page.get("rows");
            assertEquals(1,rows.size());assertEquals(id,((Number)rows.get(0).get("wishId")).longValue());
            assertEquals(true,rows.get(0).get(role.equals("publisher")?"isOwner":"isHelper"));
        }
        assertThrows(ServiceException.class,()->service.accept(id,3));
        assertEquals(id,((Number)service.detail(id,1L).get("wishId")).longValue());
        assertEquals(id,((Number)service.detail(id,2L).get("wishId")).longValue());
    }
    @Test void courierIsSavedSeparatelyWithoutCompletingTheOrder(){
        long id=publish(true);
        assertFalse(service.detail(id,null).containsKey("trackingNo"));
        assertFalse(service.detail(id,null).containsKey("courier"));
        service.accept(id,2);Map<String,Object> b=p("courier","顺丰速运","trackingNo","SF 1234567890");
        assertThrows(ServiceException.class,()->service.action(id,1,"tracking",b));
        assertThrows(ServiceException.class,()->service.action(id,3,"tracking",b));
        service.action(id,2,"tracking",b);
        assertEquals("accepted",service.detail(id,1L).get("status"));
        assertEquals("SF1234567890",service.detail(id,1L).get("trackingNo"));
        assertEquals("顺丰速运",service.detail(id,2L).get("courier"));
        assertThrows(ServiceException.class,()->service.action(id,2,"tracking",p("courier","顺丰","trackingNo","12")));
        sql.update("update kg_wish set delivery='digital' where wish_id=?",id);
        assertThrows(ServiceException.class,()->service.action(id,2,"tracking",b));
    }
    KgPrivateFiles privateFiles(){
        KgPrivateFiles f=mock(KgPrivateFiles.class);
        when(f.save(any(),eq("wishchat"))).thenAnswer(inv->p("key","wishchat/20261116/"+UUID.randomUUID().toString().replace("-","")+".png"));
        when(f.sign(anyString(),eq(600))).thenAnswer(inv->"/kangaroo/file/private?k="+inv.getArgument(0)+"&s=test-signature");
        ReflectionTestUtils.setField((Object)AopTestUtils.getTargetObject(service),"files",f);return f;
    }
    MockMultipartFile photo(){return new MockMultipartFile("file","test.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10,1});}
    @Test void privateChatPhotosAreOwnedByTheUploaderAndOrderAndReadableByBoth(){
        KgPrivateFiles f=privateFiles();long id=publish(true);
        assertThrows(ServiceException.class,()->service.messageImage(id,1,photo()));
        service.accept(id,2);
        assertThrows(ServiceException.class,()->service.messageImage(id,3,photo()));verify(f,never()).save(any(),anyString());
        long image=((Number)service.messageImage(id,2,photo()).get("imageId")).longValue();
        Map<String,Object>b=p("requestKey",UUID.randomUUID().toString(),"content","","imageIds",List.of(image));
        assertThrows(ServiceException.class,()->service.message(id,1,b));
        long other=publish(true);service.accept(other,2);assertThrows(ServiceException.class,()->service.message(other,2,b));
        service.message(id,2,b);service.message(id,2,b);
        for(long viewer:List.of(1L,2L)){
            List<Map<String,Object>> rows=service.messages(id,viewer,0);assertEquals(1,rows.size());
            List<String> images=(List<String>)rows.get(0).get("images");assertEquals(1,images.size());
            assertTrue(images.get(0).startsWith("/kangaroo/file/private?"));assertFalse(rows.get(0).containsKey("imageIds"));
        }
        assertThrows(ServiceException.class,()->service.messages(id,3,0));
        List<Map<String,Object>> admin=(List<Map<String,Object>>)service.adminDetail(id).get("messages");
        assertEquals(1,((List<?>)admin.get(0).get("images")).size());
        b.put("content","不同消息");assertThrows(ServiceException.class,()->service.message(id,2,b));
        assertEquals(1,sql.queryForObject("select count(*) from kg_wish_message",Integer.class));
    }
    @Test void rejectsEmptyInvalidAndTooManyChatPhotos(){
        privateFiles();long id=publish(true);service.accept(id,2);
        assertThrows(ServiceException.class,()->service.messageImage(id,2,new MockMultipartFile("file","bad.png","image/png","not an image".getBytes())));
        assertThrows(ServiceException.class,()->service.messageImage(id,2,new MockMultipartFile("file","bad.pdf","application/pdf","%PDF".getBytes())));
        long image=((Number)service.messageImage(id,2,photo()).get("imageId")).longValue();
        for(Object ids:List.of(List.of(),List.of(image,image),List.of(image,image,image,image),List.of(-1),List.of("/profile/public.png"),"bad")){
            assertThrows(ServiceException.class,()->service.message(id,2,p("requestKey",UUID.randomUUID().toString(),"content","","imageIds",ids)));
        }
        sql.update("update kg_wish set status='cancelled' where wish_id=?",id);
        assertThrows(ServiceException.class,()->service.messageImage(id,2,photo()));
    }
}
