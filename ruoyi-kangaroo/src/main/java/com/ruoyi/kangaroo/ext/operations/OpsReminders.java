package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.manage.KgManageService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.admin.AdminCityContext;

/** Preview is read-only. Sending rechecks ownership and submission in one transaction.
 * Delivery receipts are immutable; read/submission state is joined live when a batch is opened. */
@Service
public class OpsReminders {
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    @Autowired private OpsDaily daily;
    @Autowired private KgManageService weekly;
    @Autowired private StaffSupport staff;
    @Autowired private DataSource dataSource;
    Clock clock=Clock.system(ZONE);
    private LocalDate today(){return LocalDate.now(clock);}
    private String time(){return LocalDateTime.now(clock).format(DT);}
    private String kind(String value){require(Arrays.asList("daily","weekly").contains(value),"请选择日报或周报");return value;}
    private LocalDate period(String kind,String raw){LocalDate d=date(raw);if("weekly".equals(kind))d=d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));require(!d.isAfter(today()),"不能催报未来日期");return d;}
    private String deliveryKey(String kind,String day,String key){return kind+":"+day+":"+key+":"+today();}

    public Map<String,Object> preview(long city,String kind,String raw,String role,Long owner){
        scope.requireAdminCity(city,false);kind=kind(kind);LocalDate day=period(kind,raw);
        require(role.isEmpty()||Arrays.asList("keeper","manager").contains(role),"身份无效");
        List<Map<String,Object>> rows=new ArrayList<>();List<Long> stores=scope.cityStores(city);
        for(long uid:scope.principals()) {
            if(owner!=null&&owner>0&&owner!=uid)continue;
            List<Map<String,Object>> own=scope.owned(uid);
            if("daily".equals(kind)) {
                for(Map<String,Object> s:own)if(number(s.get("cityId"))==city&&(role.isEmpty()||role.equals(s.get("roleCode")))) {
                    Map<String,Object> row=target(uid,text(s.get("ownerName")),text(s.get("roleCode")),city,0,String.join("、",(List<String>)s.get("storeNames")));
                    Map<String,Object> report=db.one("select * from kg_ops_daily where city_id=:c and owner_id=:u and role_code=:r and biz_date=:d",p("c",city,"u",uid,"r",s.get("roleCode"),"d",day.toString()));
                    decorate(row,report,kind,day);
                    if(report!=null&&!OpsScope.ids(s).containsAll(OpsScope.ids(report)))block(row,"门店授权已变更，需核对原报表范围");
                    if(report==null&&!day.equals(today()))block(row,"该历史日期无预填快照，不能补造日报");
                    rows.add(row);
                }
            }else {
                Map<String,Object> local=null;for(Map<String,Object> s:own)if(number(s.get("cityId"))==city){local=s;break;}
                if(local==null)continue;
                for(Map<String,Object> s:weekly.weeklyReminderTargets(uid,stores)) {
                    String r=text(s.get("roleCode"));if(!role.isEmpty()&&!role.equals(r))continue;
                    Map<String,Object> row=target(uid,text(local.get("ownerName")),r,city,number(s.get("storeId")),text(s.get("targetName")));
                    Map<String,Object> report=db.one("select report_id,status,submitted_time submitted_at from kg_weekly_report where store_id=:s and week_start=:d and report_type=:t",p("s",s.get("storeId"),"d",day.toString(),"t",s.get("reportType")));
                    decorate(row,report,kind,day);rows.add(row);
                }
            }
        }
        long ready=rows.stream().filter(r->Boolean.TRUE.equals(r.get("eligible"))).count();
        return map("rows",rows,"eligibleCount",ready,"total",rows.size(),"cityId",city,"kind",kind,"periodStart",day.toString(),"today",today().toString());
    }
    private Map<String,Object> target(long uid,String name,String role,long city,long store,String nameOfTarget){return map("targetKey",uid+":"+city+":"+role+":"+store,"recipientId",uid,"recipientName",name,"roleCode",role,"targetStoreId",store,"targetName",nameOfTarget);}
    private void block(Map<String,Object> row,String reason){row.put("eligible",false);row.put("reason",reason);}
    private void decorate(Map<String,Object> row,Map<String,Object> report,String kind,LocalDate day){
        row.put("reportId",report==null?null:report.get("reportId"));row.put("reportStatus",report==null?"missing":report.get("status"));row.put("submittedAt",report==null?null:report.get("submittedAt"));row.put("eligible",true);row.put("reason","");
        if(report!=null&&"submitted".equals(report.get("status")))block(row,"已提交，无需催报");
        Map<String,Object> sent=db.one("select sent_at,message_id from kg_ops_reminder_item where delivery_key=:k",p("k",deliveryKey(kind,day.toString(),text(row.get("targetKey")))));
        row.put("lastSentAt",sent==null?null:sent.get("sentAt"));
        if(sent!=null&&Boolean.TRUE.equals(row.get("eligible")))block(row,"今天已催报，明天可再次提醒");
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> send(Map<String,Object> body,long operator,String operatorName){
        long city=number(body.get("cityId"));scope.requireAdminCity(city,false);
        String kind=kind(text(body.get("kind"))),day=period(kind,text(body.get("date"))).toString(),request=text(body.get("requestId"));
        require(request.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"请求编号无效，请重新预览");
        require(body.get("targetKeys") instanceof List,"请选择催报对象");
        TreeSet<String> keys=new TreeSet<>();for(Object k:(List<?>)body.get("targetKeys"))keys.add(text(k));
        require(!keys.isEmpty()&&keys.size()<=200,"每批请选择1至200项待办");
        String hash=hash(city+"|"+kind+"|"+day+"|"+String.join(",",keys));
        Map<String,Object> existing=db.one("select * from kg_ops_reminder_batch where request_id=:r",p("r",request));
        if(existing!=null)return replay(existing,operator,hash);
        long id;
        try{id=db.insert("kg_ops_reminder_batch",p("city_id",city,"report_kind",kind,"period_start",day,"request_id",request,"request_hash",hash,"operator_id",operator,"operator_name",StaffSupport.cut(operatorName,64),"created_at",time()));}
        catch(DuplicateKeyException e){existing=db.one("select * from kg_ops_reminder_batch where request_id=:r for update",p("r",request));require(existing!=null,"请求正在处理，请稍后重试");return replay(existing,operator,hash);}
        Map<String,Map<String,Object>> candidates=new HashMap<>();
        for(Map<String,Object> r:(List<Map<String,Object>>)preview(city,kind,day,"",null).get("rows"))candidates.put(text(r.get("targetKey")),r);
        require(candidates.keySet().containsAll(keys),"接收人的权限或门店已变化，请重新预览后发送");
        int sent=0,skipped=0;
        for(String key:keys){
            Map<String,Object> row=candidates.get(key);Long report=KgJdbc.asLong(row.get("reportId"));String reason=text(row.get("reason"));
            long uid=number(row.get("recipientId"));String role=text(row.get("roleCode"));
            if(Boolean.TRUE.equals(row.get("eligible"))){
                // Recheck/lock the original report so a just-submitted task does not receive a reminder.
                if("daily".equals(kind)){
                    Map<String,Object> d=daily.open(uid,city,role,day);report=number(d.get("reportId"));
                    if("submitted".equals(d.get("status")))reason="已提交，无需催报";
                }else{
                    report=weekly.openWeeklyReminder(uid,number(row.get("targetStoreId")),role,scope.cityStores(city),day);
                    Map<String,Object> d=db.one("select status from kg_weekly_report where report_id=:id for update",p("id",report));
                    if("submitted".equals(d.get("status")))reason="已提交，无需催报";
                }
            }
            Map<String,Object> cols=p("batch_id",id,"city_id",city,"recipient_id",uid,"recipient_name",StaffSupport.cut(text(row.get("recipientName")),64),"role_code",role,"target_store_id",row.get("targetStoreId"),"target_name",StaffSupport.cut(text(row.get("targetName")),255),"target_key",key,"report_kind",kind,"period_start",day,"report_id",report,"delivery_status","skipped","skip_reason",reason);
            if(reason.isEmpty())cols.put("delivery_key",deliveryKey(kind,day,key));
            long item;
            try{item=db.insert("kg_ops_reminder_item",cols);}catch(DuplicateKeyException e){cols.remove("delivery_key");reason="今天已催报，已跳过重复发送";cols.put("skip_reason",reason);item=db.insert("kg_ops_reminder_item",cols);}
            if(reason.isEmpty()){
                String route="daily".equals(kind)?"/pages/staff/operations?reportId="+report:"/pages/manage/weekly?reportId="+report+("manager".equals(role)?"&as=recruit":"");
                Long message=deliver(city,uid,kind,report,day,text(row.get("targetName")),route);
                if(message!=null){db.updateRow("kg_ops_reminder_item","item_id",item,p("delivery_status","sent","message_id",message,"sent_at",time()));sent++;continue;}
                db.updateRow("kg_ops_reminder_item","item_id",item,p("delivery_key",null,"skip_reason","未生成通知，请核对接收设置"));
            }
            skipped++;
        }
        db.updateRow("kg_ops_reminder_batch","batch_id",id,p("sent_count",sent,"skipped_count",skipped));return detail(id);
    }
    private Map<String,Object> replay(Map<String,Object> existing,long operator,String hash){
        if(number(existing.get("operatorId"))!=operator||!hash.equals(existing.get("requestHash")))throw new ServiceException("请求编号已用于其他发送，请重新预览",409);
        return detail(number(existing.get("batchId")));
    }
    private Long deliver(long city,long uid,String kind,long report,String day,String target,String route){
        scope.requireAdminCity(city,false);
        // This fixed system-message write is authorized by the scoped, locked report above.
        // Generic message tables have no city column; never suspend the guard around report queries.
        List<Long> saved=AdminCityContext.cities();
        try{AdminCityContext.clear();return staff.notifyStaffWithId(uid,"system",("daily".equals(kind)?"日报":"周报")+"待提交",day+" · "+target+"。请打开核对并提交；已提交无需重复填写。","daily".equals(kind)?"ops_daily":"weekly_report",report,route);}
        finally{if(saved==null)AdminCityContext.clear();else AdminCityContext.set(saved);}
    }
    public List<Map<String,Object>> history(long city){scope.requireAdminCity(city,false);return db.list("select batch_id,city_id,report_kind,period_start,operator_name,sent_count,skipped_count,created_at from kg_ops_reminder_batch where city_id=:c order by batch_id desc limit 50",p("c",city));}
    public Map<String,Object> detail(long id){
        Map<String,Object> batch=db.must("select batch_id,city_id,report_kind,period_start,operator_name,sent_count,skipped_count,created_at from kg_ops_reminder_batch where batch_id=:id",p("id",id),"催报记录不存在或无权查看");
        scope.requireAdminCity(number(batch.get("cityId")),false);
        List<Map<String,Object>> rows=db.list("select * from kg_ops_reminder_item where batch_id=:id order by item_id",p("id",id));
        int read=0,submitted=0;
        for(Map<String,Object> row:rows){
            Map<String,Object> report=null;Long reportId=KgJdbc.asLong(row.get("reportId"));
            if(reportId!=null){String sql="daily".equals(row.get("reportKind"))?"select status,submitted_at from kg_ops_daily where report_id=:id":"select status,submitted_time submitted_at from kg_weekly_report where report_id=:id";report=db.one(sql,p("id",reportId));}
            row.put("reportStatus",report==null?"missing":report.get("status"));row.put("submittedAt",report==null?null:report.get("submittedAt"));
            boolean done=report!=null&&"submitted".equals(report.get("status"));if(done)submitted++;
            // Metadata only, for a receipt already authorized above; never expose other messages or content.
            List<Map<String,Object>> messages=row.get("messageId")==null?Collections.emptyList():new JdbcTemplate(dataSource).queryForList("select read_flag,read_time,del_flag from kg_message where msg_id=? and recipient_id=? and mode='staff'",row.get("messageId"),row.get("recipientId"));
            Map<String,Object> message=messages.isEmpty()?null:messages.get(0);boolean seen=message!=null&&"1".equals(text(message.get("read_flag")));
            row.put("read",seen);row.put("readAt",message==null?null:message.get("read_time"));if(seen)read++;
            row.put("trackingStatus",done?"已提交":!"sent".equals(row.get("deliveryStatus"))?"已跳过":message==null?"消息已移除":seen?"已读待提交":"未读待提交");row.remove("deliveryKey");
        }
        batch.put("rows",rows);batch.put("readCount",read);batch.put("submittedCount",submitted);batch.put("pendingCount",rows.stream().filter(r->"sent".equals(r.get("deliveryStatus"))&&!"submitted".equals(r.get("reportStatus"))).count());return batch;
    }
    private static String hash(String value){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format("%02x",x&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
