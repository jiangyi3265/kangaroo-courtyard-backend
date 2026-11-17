package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.admin.AdminCityContext;

@Service
public class OpsQueries {
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    public Map<String,Object> options(){
        String filter=AdminCityContext.limited()?" and c.city_id in (:cities)":"";
        List<Map<String,Object>> cities=db.list("select c.city_id,c.name from kg_city c where c.status='0'"+filter+" order by c.name",p("cities",AdminCityContext.limited()&&!AdminCityContext.cities().isEmpty()?AdminCityContext.cities():Arrays.asList(-1L)));
        return map("cities",cities,"metrics",OpsCatalog.metrics(),"templatesAllowed",!AdminCityContext.limited());
    }
    public List<Map<String,Object>> owners(long city,String role){scope.requireAdminCity(city,false);role(role);List<Map<String,Object>> out=new ArrayList<>();for(Long uid:scope.principals()){try{Map<String,Object> own=scope.requireOwned(uid,city,role);out.add(map("ownerId",uid,"ownerName",own.get("ownerName"),"storeNames",own.get("storeNames")));}catch(ServiceException e){if(!Integer.valueOf(403).equals(e.getCode()))throw e;}}return out;}
    public List<Map<String,Object>> stores(long city){scope.requireAdminCity(city,false);List<Long> ids=scope.cityStores(city);return db.list("select store_id,store_name from kg_store where store_id in (:ids) order by store_id",p("ids",ids.isEmpty()?Arrays.asList(-1L):ids));}
    public List<Map<String,Object>> results(long city){scope.requireAdminCity(city,false);List<Long> ids=scope.cityStores(city);return db.list("select r.*,s.store_name from kg_ops_result r join kg_store s on s.store_id=r.store_id where r.store_id in (:ids) order by r.occurred_at desc",p("ids",ids.isEmpty()?Arrays.asList(-1L):ids));}
    public List<Map<String,Object>> observed(long uid,String month){
        YearMonth m=month(month);List<Map<String,Object>> out=new ArrayList<>();List<Map<String,Object>> cities=scope.observerCities(uid);if(cities.isEmpty())return out;
        List<Long> ids=new ArrayList<>();for(Map<String,Object> c:cities)ids.add(number(c.get("cityId")));
        for(Map<String,Object> row:db.list("select report_id,owner_id,owner_name,city_id,city_name,role_code,biz_date,status,submitted_at,store_ids from kg_ops_daily where city_id in (:c) and biz_date>=:f and biz_date<:t order by biz_date desc,report_id",p("c",ids,"f",m.atDay(1).toString(),"t",m.plusMonths(1).atDay(1).toString())))if(scope.canObserve(uid,row))out.add(row);return out;
    }
    public Map<String,Object> issues(Long city,String status,Integer page){
        return issues(city,status,page,null,null);
    }
    public Map<String,Object> issues(Long city,String status,Integer page,String from,String to){
        String where=" where 1=1";Map<String,Object> ps=p();if(city!=null){scope.requireAdminCity(city,false);List<Long> ids=scope.cityStores(city);where+=" and a.store_id in (:s)";ps.put("s",ids.isEmpty()?Arrays.asList(-1L):ids);}
        if(!text(from).isEmpty()||!text(to).isEmpty()){LocalDate first=date(from),last=date(to);require(!last.isBefore(first)&&!last.isAfter(first.plusDays(366)),"查询日期范围最多366天");where+=" and a.create_time>=:first and a.create_time<:last";ps.put("first",first.atStartOfDay().format(DT));ps.put("last",last.plusDays(1).atStartOfDay().format(DT));}
        ps.put("cutoff",LocalDateTime.now(ZONE).minusDays(7).format(DT));
        Map<String,Object> summary=db.one("select count(*) total,coalesce(sum(a.closed_at is not null),0) closed,coalesce(sum(a.closed_at is null and a.create_time<:cutoff),0) overdue from kg_mediation_case a join kg_store s on s.store_id=a.store_id"+where,ps);
        summary.put("open",number(summary.get("total"))-number(summary.get("closed")));summary.put("closureRate",OpsMetrics.percent(number(summary.get("closed")),number(summary.get("total"))));
        if("open".equals(status))where+=" and a.closed_at is null";else if("overdue".equals(status))where+=" and a.closed_at is null and a.create_time<:cutoff";else if("closed".equals(status))where+=" and a.closed_at is not null";else require(text(status).isEmpty(),"异常状态无效");
        Map<String,Object> result=db.page("select a.case_id,a.case_no,a.store_id,s.store_name,s.city,a.status,a.create_time,a.closed_at,a.category from kg_mediation_case a join kg_store s on s.store_id=a.store_id"+where+" order by a.create_time desc,a.case_id",ps,page,30);result.put("summary",summary);return result;
    }
}
