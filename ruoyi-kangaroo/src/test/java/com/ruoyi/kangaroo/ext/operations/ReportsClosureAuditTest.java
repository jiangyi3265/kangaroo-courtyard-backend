package com.ruoyi.kangaroo.ext.operations;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.util.*;
import java.time.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.security.admin.AdminCityContext;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.ext.manage.KgStats;

/** Extends the real-database reminder flow suite with limited-admin monthly/weekly actions. */
class ReportsClosureAuditTest extends OperationsReminderDatabaseTest {
    private long weeklyReport(long uid,long store){
        long id=weekly.openWeeklyReminder(uid,store,"keeper",scope.cityStores(store==197004?OTHER:CITY),"2026-11-09");
        db.update("update kg_weekly_report set status='submitted',submitted_by=:u,submitted_name='测试填报人',submitted_time=now(),note='本周交接完成',snapshot='{}' where report_id=:id",p("u",uid,"id",id));return id;
    }
    @Test void cityAdminCanReadWeeklyExpensesAndCannotReadOtherCity(){
        long id=weeklyReport(KEEPER,STORE),other=weeklyReport(STRANGER,197004);
        db.insert("kg_weekly_item",p("report_id",id,"item_type","fee","name","测试采购","amount_fen",350,"remark","收据已核对","sort",1));
        AdminCityContext.set(Arrays.asList(CITY));Map<String,Object> context=weekly.weeklyReviewContext(id);assertTrue(text(context.get("text")).contains("测试采购"));assertThrows(ServiceException.class,()->weekly.weeklyReviewContext(other));
    }
    @Test void cityAdminReturnSendsNotificationAndReopensReminder(){
        wire(weekly,"support",staff);Map<String,Object> batch=send(request("weekly",KEEPER));long id=number(item(batch).get("reportId"));
        db.update("update kg_weekly_report set status='submitted',submitted_by=:u,submitted_time=now() where report_id=:id",p("u",KEEPER,"id",id));
        AdminCityContext.set(Arrays.asList(CITY));assertEquals("已退回",tx.execute(s->weekly.weeklyReturn(id,"补充费用凭证","测试管理员")));assertEquals(Arrays.asList(CITY),AdminCityContext.cities());assertEquals(1L,number(reminders.detail(number(batch.get("batchId"))).get("pendingCount")));assertEquals("returned",item(reminders.detail(number(batch.get("batchId")))).get("reportStatus"));
        assertThrows(ServiceException.class,()->tx.execute(s->weekly.weeklyReturn(id,"重复退回","测试管理员")));
        AdminCityContext.clear();assertEquals(1,db.count("select count(*) from kg_message where biz_type='weekly_report' and title='周报被退回' and recipient_id=:u",p("u",KEEPER)));
        KgStats stats=new KgStats();wire(stats,"db",db);wire(weekly,"stats",stats);
        AppLoginUser me=new AppLoginUser();me.setUserId(KEEPER);me.setNickname("测试主理人");me.setStaffGrants(Collections.singletonList(new StaffGrantView()));AppUserContext.set(me);
        try{
            Map<String,Object> returned=weekly.weeklyDetail(id);assertEquals("returned",returned.get("status"));assertEquals("补充费用凭证",returned.get("returnReason"));
            Map<String,Object> saved=tx.execute(s->weekly.weeklySave(id,p("version",returned.get("version"),"note","已补充凭证","risk","无","fees",Arrays.asList(p("name","测试采购","amountFen",350)),"coops",Collections.emptyList())));
            assertThrows(ServiceException.class,()->tx.execute(s->weekly.weeklySubmit(id,p("version",returned.get("version"),"confirmed",true))));
            Map<String,Object> submitted=tx.execute(s->weekly.weeklySubmit(id,p("version",saved.get("version"),"confirmed",true)));assertEquals("submitted",submitted.get("status"));
            assertThrows(ServiceException.class,()->tx.execute(s->weekly.weeklySave(id,p("version",submitted.get("version")))));
        }finally{AppUserContext.remove();}
        AdminCityContext.set(Arrays.asList(CITY));assertEquals(0L,number(reminders.detail(number(batch.get("batchId"))).get("pendingCount")));assertTrue(text(weekly.weeklyReviewContext(id).get("text")).contains("测试采购"));
    }
    @Test void cityAdminMonthlyDeliveryPreservesScopeAndIdempotency(){
        OpsMonthly monthly=new OpsMonthly();wire(monthly,"db",db,"scope",scope,"metrics",ReflectionTestUtils.getField(daily,"metrics"),"targets",ReflectionTestUtils.getField(daily,"targets"));monthly.clock=Clock.fixed(LocalDateTime.of(2026,12,1,0,6).atZone(ZONE).toInstant(),ZONE);
        OpsNotices notices=new OpsNotices();wire(notices,"db",db,"scope",scope,"monthly",monthly,"staff",staff,"tx",tx);
        long id=number(tx.execute(s->monthly.generate("2026-11",CITY,"keeper",KEEPER)).get("summaryId"));
        AdminCityContext.set(Arrays.asList(CITY));assertEquals(1,notices.sendMonthly(id));assertEquals(0,notices.sendMonthly(id));assertEquals(Arrays.asList(CITY),AdminCityContext.cities());AdminCityContext.set(Arrays.asList(OTHER));assertThrows(ServiceException.class,()->notices.sendMonthly(id));
        AdminCityContext.clear();assertEquals(1,db.count("select count(*) from kg_message where biz_type='ops_monthly' and biz_id=:id",p("id",id)));
    }
}
