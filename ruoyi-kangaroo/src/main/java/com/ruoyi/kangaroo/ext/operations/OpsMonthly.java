package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.time.*;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Sealed month-end evidence; later changes to targets or manual drafts cannot rewrite it. */
@Service
public class OpsMonthly {
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    @Autowired private OpsMetrics metrics;
    @Autowired private OpsTargets targets;
    Clock clock=Clock.system(ZONE);

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> generate(String month,long city,String role,long owner){
        YearMonth m=month(month);role(role);LocalDateTime now=LocalDateTime.now(clock);
        require(!now.isBefore(m.plusMonths(1).atDay(1).atTime(0,5)),"月度总结在次月1日00:05后生成，为最后一天日报预留提交时间");scope.requireAdminCity(city,false);
        Map<String,Object> row=db.one("select * from kg_ops_monthly where month_key=:m and city_id=:c and role_code=:r and owner_id=:u for update",p("m",month,"c",city,"r",role,"u",owner));
        if(row!=null)return present(row);
        List<Long> stores;String name;
        if(owner==0){stores=scope.cityStores(city);name="地区团队";}else{Map<String,Object> own=scope.requireOwned(owner,city,role);stores=OpsScope.ids(own);name=text(own.get("ownerName"));}
        Map<String,Object> counts=metrics.counts(stores,monthStart(m),monthEnd(m));
        List<Map<String,Object>> progress=targets.progress(city,role,owner,m,monthEnd(m));
        Map<String,Object> ps=p("m",month,"c",city,"r",role,"u",owner,"f",m.atDay(1).toString(),"t",m.plusMonths(1).atDay(1).toString());
        String ownerWhere=owner==0?"":" and owner_id=:u";
        List<Map<String,Object>> reports=db.list("select report_id,owner_name,biz_date,status,manual_json from kg_ops_daily where city_id=:c and role_code=:r and biz_date>=:f and biz_date<:t"+ownerWhere+" order by biz_date,report_id",ps);
        List<Map<String,Object>> actions=new ArrayList<>();int submitted=0;
        for(Map<String,Object> report:reports){if(!"submitted".equals(report.get("status")))continue;submitted++;Map<String,Object> manual=JSON.parseObject(text(report.remove("manualJson")));report.put("plan",manual.get("tomorrowPlan"));report.put("issues","keeper".equals(role)?manual.get("yesterdayIssues"):manual.get("issues"));report.put("remedies",manual.get("remedies"));actions.add(report);}
        List<String> strengths=new ArrayList<>(),gaps=new ArrayList<>(),suggestions=new ArrayList<>();
        for(Map<String,Object> target:progress){Object remaining=target.get("missing");if("normal".equals(target.get("status"))){strengths.add(target.get("label")+"：完成 "+target.get("completed")+" / "+target.get("target"));}else{gaps.add(target.get("label")+"："+target.get("statusLabel")+"，差额 "+(remaining==null?"待核实":remaining));suggestions.add("围绕“"+target.get("label")+"”拆分下月每周任务，并复查日报中的补救计划。");}}
        if(number(counts.get("openCases"))>0)suggestions.add("优先跟进尚未闭环的异常，明确责任人与截止日期。");
        if(progress.isEmpty())suggestions.add("本月未配置目标，请先设置下月的城市或个人任务。");
        Map<String,Object> snapshot=map("counts",counts,"progress",progress,"actions",actions,"strengths",strengths,"gaps",gaps,"suggestions",suggestions,"submittedReports",submitted,"draftReports",reports.size()-submitted,"windowStart",monthStart(m).format(DT),"windowEnd",monthEnd(m).format(DT),"lateSnapshot",now.isAfter(m.plusMonths(1).atDay(1).atTime(1,0)),"note","按源业务记录统计；人员日报可能覆盖同一门店，团队数据不累加个人日报。未提交内容不进入月度行动记录；已封存的总结不覆盖。");
        long id=db.insert("kg_ops_monthly",p("month_key",month,"city_id",city,"role_code",role,"owner_id",owner,"owner_name",name,"city_name",scope.city(city).get("name"),"store_ids",JSON.toJSONString(stores),"summary_json",JSON.toJSONString(snapshot),"generated_at",now.format(DT)));
        return present(load(id));
    }
    Map<String,Object> load(long id){Map<String,Object> row=db.one("select * from kg_ops_monthly where summary_id=:id",p("id",id));if(row==null)throw new ServiceException("月度总结不存在或无权查看",404);return row;}
    private Map<String,Object> present(Map<String,Object> raw){
        Map<String,Object> row=new LinkedHashMap<>(raw),summary=JSON.parseObject(text(row.remove("summaryJson")));Map<?,?> counts=(Map<?,?>)summary.get("counts");List<Map<String,Object>> fields=new ArrayList<>();
        for(Map<String,Object> metric:OpsCatalog.metrics())if((row.get("roleCode").equals(metric.get("roleCode"))||"both".equals(metric.get("roleCode")))&&counts.containsKey(metric.get("code")))fields.add(map("label",metric.get("label"),"value",counts.get(metric.get("code")),"unit",metric.get("unit")));
        fields.add(map("label","本期新增异常","value",counts.get("cases"),"unit","起"));fields.add(map("label","已闭环异常","value",counts.get("closedCases"),"unit","起"));fields.add(map("label","未闭环异常","value",counts.get("openCases"),"unit","起"));summary.put("keyMetrics",fields);row.put("summary",summary);row.put("storeIds",OpsScope.ids(raw));return row;
    }
    public Map<String,Object> adminGet(long id){Map<String,Object> row=load(id);scope.requireAdminCity(number(row.get("cityId")),false);return present(row);}
    public Map<String,Object> get(long uid,long id){Map<String,Object> row=load(id);scope.requireRead(uid,row);return present(row);}
    public List<Map<String,Object>> list(String month,Long city,String role,Long owner){
        month(month);String where=" where month_key=:m";Map<String,Object> ps=p("m",month);
        if(city!=null){scope.requireAdminCity(city,false);where+=" and city_id=:c";ps.put("c",city);}if(!text(role).isEmpty()){role(role);where+=" and role_code=:r";ps.put("r",role);}if(owner!=null){where+=" and owner_id=:u";ps.put("u",owner);}
        return db.list("select summary_id,month_key,city_id,city_name,role_code,owner_id,owner_name,store_ids,generated_at from kg_ops_monthly"+where+" order by city_id,role_code,owner_id",ps);
    }
    public List<Map<String,Object>> mine(long uid,String month){List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> row:list(month,null,null,null)){try{scope.requireRead(uid,row);out.add(row);}catch(ServiceException e){if(!Integer.valueOf(403).equals(e.getCode()))throw e;}}return out;}
}
