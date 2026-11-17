package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class OpsJobs {
    private static final Logger log=LoggerFactory.getLogger(OpsJobs.class);
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    @Autowired private OpsDaily daily;
    @Autowired private OpsMonthly monthly;
    @Autowired private OpsNotices notices;
    @Value("${kangaroo.operations.schedule-enabled:true}") private boolean enabled;
    Clock clock=Clock.system(ZONE);
    @Scheduled(cron="12 0,5,30 * * * *",zone="Asia/Shanghai")
    public void scheduled(){if(enabled)tick(LocalDateTime.now(clock));}
    public void tick(LocalDateTime now){
        LocalDate day=now.toLocalDate();
        if(!now.toLocalTime().isBefore(LocalTime.of(17,30)))for(Long uid:scope.principals())for(Map<String,Object> own:scope.owned(uid)){
            try{long city=number(own.get("cityId"));String role=text(own.get("roleCode"));
                Map<String,Object> existing=db.one("select report_id,final_data,status from kg_ops_daily where owner_id=:u and city_id=:c and role_code=:r and biz_date=:d",p("u",uid,"c",city,"r",role,"d",day.toString()));
                Map<String,Object> row=existing;
                if(existing==null||(!now.toLocalTime().isBefore(LocalTime.of(18,0))&&number(existing.get("finalData"))==0))row=daily.open(uid,city,role,day.toString());
                long id=number(row.get("reportId"));String route="/pages/staff/operations?reportId="+id;
                notices.send(city,uid,"draft:"+id,"日报已预填",day+" 日报已准备好。18:00完成自动统计后，请核对并提交。","ops_daily",id,route);
                if(!now.toLocalTime().isBefore(LocalTime.of(18,30))&&"draft".equals(row.get("status")))notices.send(city,uid,"due:"+id,"今日日报待提交","请核对完整数据，补充明日计划及线下记录后提交。","ops_daily",id,route);
                if(number(row.get("finalData"))==1){Map<String,Object> detail=daily.get(uid,id);for(Object value:(List<?>)detail.get("progress")){Map<?,?> target=(Map<?,?>)value;if("severe".equals(target.get("status")))notices.send(city,uid,"slow:"+id+":"+target.get("targetId"),"月度任务进度落后",target.get("label")+"进度严重落后，请在日报中填写补救计划。","ops_daily",id,route);}}
            }catch(Exception e){log.warn("Operations daily schedule failed for user {}: {}",uid,e.getMessage());}
        }
        // Daily retry recovers a missed month-end run after an outage; receipts make delivery idempotent.
        if(now.getHour()==0&&now.getMinute()>=5)generateMonth(YearMonth.from(day).minusMonths(1));
        if(now.getHour()==18&&now.getMinute()>=30)overdue(now);
    }
    private void generateMonth(YearMonth month){
        Set<String> groups=new LinkedHashSet<>();
        for(Map<String,Object> r:db.list("select distinct city_id,role_code,owner_id from kg_ops_daily where biz_date>=:f and biz_date<:t union select city_id,role_code,owner_id from kg_ops_target where month_key=:m and city_id>0",p("f",month.atDay(1).toString(),"t",month.plusMonths(1).atDay(1).toString(),"m",month.toString()))){groups.add(r.get("cityId")+":"+r.get("roleCode")+":"+r.get("ownerId"));groups.add(r.get("cityId")+":"+r.get("roleCode")+":0");}
        for(String group:groups){String[] a=group.split(":");try{Map<String,Object> row=monthly.generate(month.toString(),Long.parseLong(a[0]),a[1],Long.parseLong(a[2]));notices.sendMonthly(number(row.get("summaryId")));}catch(Exception e){log.warn("Operations monthly schedule failed for {}: {}",group,e.getMessage());}}
    }
    private void overdue(LocalDateTime now){
        Set<Long> supervisors=new LinkedHashSet<>();
        for(Long uid:scope.principals())for(Map<String,Object> own:scope.owned(uid)){List<Long> stores=OpsScope.ids(own);overdueNotice(now,uid,number(own.get("cityId")),text(own.get("roleCode")),stores);supervisors.addAll(scope.supervisors(stores));}
        for(Long uid:supervisors)for(Map<String,Object> city:scope.observerCities(uid)){long id=number(city.get("cityId"));overdueNotice(now,uid,id,"city_lead",scope.observedStores(uid,id));}
    }
    private void overdueNotice(LocalDateTime now,long uid,long city,String role,List<Long> stores){
        if(stores.isEmpty())return;long count=db.count("select count(*) from kg_mediation_case where store_id in (:s) and closed_at is null and create_time<:t",p("s",stores,"t",now.minusDays(7).format(DT)));if(count==0)return;
        try{String event="overdue:"+now.toLocalDate()+":"+city+":"+role;notices.send(city,uid,event,"异常处理逾期提醒","授权门店有 "+count+" 起异常超过7天未闭环，请在工作台跟进。","ops_scope",city,"/pages/staff/operations?cityId="+city+"&role="+role);}catch(Exception e){log.warn("Operations overdue notice failed for user {}",uid,e);}
    }
}
