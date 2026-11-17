package com.ruoyi.kangaroo.ext.staff;

import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.*;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.*;
import com.ruoyi.kangaroo.ext.community.*;
import com.ruoyi.kangaroo.ext.user.*;
import com.ruoyi.kangaroo.ext.stay.*;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.controller.admin.KgExtAdminController;
import com.ruoyi.kangaroo.domain.KgInterviewSlot;
import com.ruoyi.kangaroo.mapper.KgInterviewSlotMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Run with e2e/test_operations_database.py; all tables and rows are disposable. */
class KgOperationsIT {
    @SuppressWarnings("unchecked")
    static <T> T tx(T target,DriverManagerDataSource ds) {
        ProxyFactory factory=new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
    @Test
    @SuppressWarnings("unchecked")
    void roundTrip() throws Exception {
        String schema=System.getenv("KG_OPS_TEST_DB");assertNotNull(schema);assertTrue(schema.matches("kg_ops_test_[a-f0-9]{12}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai","root",System.getenv("MYSQL_PWD"));
        KgJdbc db=new KgJdbc(ds);
        for(long uid=9001L;uid<=9003L;uid++) db.insert("kg_app_user",p("user_id",uid,"openid","synthetic-ops-"+uid,"nickname","合成账号"+uid,"status","0","del_flag","0"));
        db.insert("kg_store",p("store_id",102L,"store_name","平潭测试门店","city","平潭","address","测试路","status","0"));
        db.insert("kg_store",p("store_id",103L,"store_name","大理测试门店","city","大理","address","测试路","status","0"));
        StaffSupport support=mock(StaffSupport.class);
        StaffSupport.Ctx c=new StaffSupport.Ctx();c.storeId=102L;c.storeName="平潭测试门店";c.role="manager";c.me=new AppLoginUser();c.me.setUserId(9001L);c.me.setNickname("测试主理人");
        when(support.ctx(102L)).thenReturn(c);
        when(support.ctx(999L)).thenThrow(new ServiceException("没有该门店的操作权限",403));
        when(support.ctx(103L)).thenThrow(new ServiceException("没有该门店的操作权限",403));
        SysUser user=new SysUser();user.setUserId(1L);user.setUserName("ops-test");LoginUser login=new LoginUser();login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,Collections.emptyList()));
        try {
            KgActivityService activities=new KgActivityService();ReflectionTestUtils.setField(activities,"db",db);
            KgCommunityModule community=new KgCommunityModule();ReflectionTestUtils.setField(community,"db",db);ReflectionTestUtils.setField(community,"activities",activities);
            KgUserModule users=new KgUserModule();
            ExtEntities entities=new ExtEntities();ReflectionTestUtils.setField(entities,"db",db);ReflectionTestUtils.setField(entities,"contributors",Arrays.asList(community,users,new KgStayModule(db)));entities.init();
            KgExtAdminController admin=new KgExtAdminController();ReflectionTestUtils.setField(admin,"db",db);ReflectionTestUtils.setField(admin,"entities",entities);admin=tx(admin,ds);
            for(String entity:Arrays.asList("activity","faq","stay")) Files.write(Paths.get(System.getenv("KG_OPS_META_DIR"),entity+".json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(entities.require(entity).meta()));
            long faq=((Number)admin.add("faq",p("question","自定义面试问题","category","interview","answer","先与门店确认","status","0","sort",0)).get("data")).longValue();
            KgSupportService help=new KgSupportService();ReflectionTestUtils.setField(help,"db",db);
            assertTrue(help.faqs().stream().anyMatch(f->"自定义面试问题".equals(f.get("question"))));
            admin.edit("faq",p("faqId",faq,"answer","更新后的解答","status","1"));
            assertFalse(help.faqs().stream().anyMatch(f->Long.valueOf(faq).equals(KgJdbc.asLong(f.get("faqId")))));
            String date=LocalDateTime.now().plusDays(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Map<String,Object> activity=p("title","测试活动","source","yard","storeId",102L,"place","门店前台","startTime",date+" 14:00:00","endTime",date+" 16:00:00","capacity",10,"feeMode","free","feeFen",0);
            long act=((Number)admin.add("activity",activity).get("data")).longValue();
            Map<String,Object> stored=db.one("select * from kg_activity where activity_id=:id",p("id",act));assertEquals("pending",stored.get("status"));assertEquals("平潭",stored.get("city"));assertEquals(16,stored.get("checkinToken").toString().length());
            admin.edit("activity",p("activityId",act,"title","不允许静默改活动","capacity",1,"pinned","1"));
            assertEquals("测试活动",db.one("select title from kg_activity where activity_id=:id",p("id",act)).get("title"));
            final KgExtAdminController adminApi=admin;
            activity.put("storeId",999L);assertThrows(ServiceException.class,()->adminApi.add("activity",activity));

            KgStayReadiness ready=new KgStayReadiness(db);
            long stay=db.insert("kg_stay",p("store_id",999L,"stay_name","测试房源","city","大理","address","测试路","cover","/profile/qa.jpg","status","offline"));
            assertEquals(2,ready.issues(stay).size());assertThrows(ServiceException.class,()->ready.requireReady(stay));
            admin.edit("stay",p("stayId",stay,"storeId",103L));
            db.insert("kg_stay_room_type",p("stay_id",stay,"type_name","测试房型","status","0","stock",2,"base_price_fen",10000));
            assertTrue(ready.issues(stay).isEmpty());
            assertThrows(ServiceException.class,()->adminApi.edit("stay",p("stayId",stay,"storeId",999L)));
            // 原门店停用且已有订单：门店不变时其余字段照常能存；要换门店仍按 409 拦住；展示检查不再让人"去换门店"
            long roomType=KgJdbc.asLong(db.one("select room_type_id from kg_stay_room_type where stay_id=:id",p("id",stay)).get("roomTypeId"));
            db.insert("kg_stay_order",p("order_no","QA-STAY-1","app_user_id",9002L,"stay_id",stay,"store_id",103L,"room_type_id",roomType,"check_in",date,"check_out",date,"nights",1,"pay_fen",10000,"guest_name","张测试","guest_phone","13800138000"));
            db.update("update kg_store set status='1' where store_id=103",null);
            admin.edit("stay",p("stayId",stay,"storeId",103L,"address","测试路 2 号"));
            assertEquals("测试路 2 号",db.one("select address from kg_stay where stay_id=:id",p("id",stay)).get("address"));
            ServiceException moved=assertThrows(ServiceException.class,()->adminApi.edit("stay",p("stayId",stay,"storeId",102L)));assertEquals(Integer.valueOf(409),moved.getCode());
            assertTrue(ready.issues(stay).get(0).contains("已有订单"));
            db.update("update kg_store set status='0' where store_id=103",null);

            KgStaffSlotService slots=tx(new KgStaffSlotService(db,support),ds);
            long slot=slots.add(102L,p("startTime",date+" 10:00:00","endTime",date+" 10:30:00","meetingInfo","测试会议 123456"));
            assertEquals(1,slots.list(102L).size());
            assertThrows(ServiceException.class,()->slots.add(102L,p("startTime",date+" 10:15:00","endTime",date+" 10:45:00")));
            assertThrows(ServiceException.class,()->slots.add(999L,p("startTime",date+" 11:00:00","endTime",date+" 11:30:00")));
            assertThrows(ServiceException.class,()->slots.close(103L,slot));
            Configuration config=new Configuration(new Environment("ops-test",new JdbcTransactionFactory(),ds));config.getTypeAliasRegistry().registerAlias("KgInterviewSlot",KgInterviewSlot.class);
            try(InputStream xml=getClass().getClassLoader().getResourceAsStream("mapper/kangaroo/KgInterviewSlotMapper.xml")){new XMLMapperBuilder(xml,config,"mapper/kangaroo/KgInterviewSlotMapper.xml",config.getSqlFragments()).parse();}
            try(SqlSession session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                KgInterviewSlot row=session.getMapper(KgInterviewSlotMapper.class).selectKgInterviewSlotBySlotId(slot);
                assertEquals("测试会议 123456",row.getMeetingInfo());
            }
            db.update("update kg_interview_slot set status='booked',booked_app_id=201 where slot_id=:id",p("id",slot));assertThrows(ServiceException.class,()->slots.close(102L,slot));
            db.update("update kg_interview_slot set status='open',booked_app_id=null where slot_id=:id",p("id",slot));slots.close(102L,slot);

            KgOnlineTaskService tasks=new KgOnlineTaskService();ReflectionTestUtils.setField(tasks,"db",db);ReflectionTestUtils.setField(tasks,"support",support);ReflectionTestUtils.setField(tasks,"guard",mock(KgCommunityGuard.class));tasks=tx(tasks,ds);
            Map<String,Object> task=p("storeId",102L,"title","测试剪辑任务","taskType","视频剪辑","deliverables","一个视频","criteria","按要求完成","deadline",date+" 18:00","commissionFen",1250,"quota",2);
            long job=((Number)tasks.createByAdmin(task).get("jobId")).longValue();
            Map<String,Object> row=db.one("select * from kg_job where job_id=:id",p("id",job));assertEquals("draft",row.get("status"));assertEquals("admin",row.get("source"));
            // 交付物允许 100 字，但 kg_job.deliver_desc 只有 32 字宽、显示为「交付日期」：长交付物要能存下，交付日期写截止时间
            String longDeliver=String.join("",java.util.Collections.nCopies(10,"剪辑成片与封面图"));assertEquals(80,longDeliver.length());
            Map<String,Object> longTask=new java.util.HashMap<String,Object>(task);longTask.put("deliverables",longDeliver);
            long longJob=((Number)tasks.createByAdmin(longTask).get("jobId")).longValue();
            assertEquals(LocalDateTime.parse(date+" 18:00",DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))+" 前",db.one("select deliver_desc from kg_job where job_id=:id",p("id",longJob)).get("deliverDesc"));
            assertEquals(longDeliver,db.one("select deliverables from kg_online_task where job_id=:id",p("id",longJob)).get("deliverables"));
            // 岗位管理改价：佣金写回 kg_online_task（验收结算读它），交付日期按截止时间重新生成；超出 1–5000 元在写库前拒绝
            final KgOnlineTaskService taskApi=tasks;
            assertEquals(LocalDateTime.parse(date+" 18:00",DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))+" 前",taskApi.syncFromJobEdit(job,2000L,"ops-test"));
            assertEquals(2000L,((Number)db.one("select commission_fen from kg_online_task where job_id=:id",p("id",job)).get("commissionFen")).longValue());
            assertThrows(ServiceException.class,()->taskApi.syncFromJobEdit(job,50L,"ops-test"));
            assertEquals(2000L,((Number)db.one("select commission_fen from kg_online_task where job_id=:id",p("id",job)).get("commissionFen")).longValue());
            // 改回 12.50 元：精确换成 1250 分（原先 asLong 会截成 12 元），也让下面原有的断言照旧成立
            taskApi.syncFromJobEdit(job,KgOnlineTaskService.commissionFen(new java.math.BigDecimal("12.50")),"ops-test");
            assertEquals(1250L,((Number)db.one("select commission_fen from kg_online_task where job_id=:id",p("id",job)).get("commissionFen")).longValue());
            db.insert("kg_application",p("app_id",201L,"app_no","QA201","job_id",job,"store_id",102L,"app_user_id",9002L,"real_name","张测试","phone","13800138000","age",25,"skills","摄影","arrival_date",date,"stay_days",30,"status","submitted"));
            db.insert("kg_staff_grant",p("app_user_id",9003L,"store_id",103L,"role_code","manager","status","0"));
            KgResumeShareService share=tx(new KgResumeShareService(db,support,mock(KgWorkbenchService.class)),ds);
            assertEquals(1,share.stores(102L,"大理").size());
            when(support.notifyStaffWithId(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString())).thenReturn(1001L);
            assertEquals(1,share.send(102L,201L,103L,"请协助看看",true).get("count"));
            long forward=((Number)db.one("select max(forward_id) id from kg_resume_forward",null).get("id")).longValue();
            assertThrows(ServiceException.class,()->share.view(forward));
            StaffSupport.Ctx recipient=new StaffSupport.Ctx();recipient.storeId=103L;recipient.me=c.me;recipient.role="manager";doReturn(recipient).when(support).ctx(103L);
            Map<String,Object> masked=share.view(forward);assertFalse(masked.containsKey("phone"));assertFalse(masked.containsKey("appId"));assertNotEquals("张测试",masked.get("name"));
            when(support.notifyStaffWithId(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString())).thenReturn(null);
            long n=db.count("select count(*) from kg_resume_forward",null);assertThrows(ServiceException.class,()->share.send(102L,201L,103L,"",true));assertEquals(n,db.count("select count(*) from kg_resume_forward",null));
            db.update("update kg_application set status='withdrawn' where app_id=201",null);assertThrows(ServiceException.class,()->share.view(forward));
            // 经营模块与登录同一口径：门店停用后，主理人在这家店的日报 / 周报 / 看板范围里消失
            com.ruoyi.kangaroo.ext.manage.KgManageService manage=new com.ruoyi.kangaroo.ext.manage.KgManageService();ReflectionTestUtils.setField(manage,"db",db);
            assertEquals(1,manage.myStores(9003L).size());
            db.update("update kg_store set status='1' where store_id=103",null);
            assertEquals(0,manage.myStores(9003L).size());
            db.update("update kg_store set status='0' where store_id=103",null);
        } finally {SecurityContextHolder.clearContext();}
    }
}
