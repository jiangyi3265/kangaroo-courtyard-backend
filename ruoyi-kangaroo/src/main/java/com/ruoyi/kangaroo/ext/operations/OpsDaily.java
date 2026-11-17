package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Service
public class OpsDaily {
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    @Autowired private OpsMetrics metrics;
    @Autowired private OpsTargets targets;
    @Autowired private OpsForms forms;
    Clock clock=Clock.system(ZONE);
    LocalDateTime now(){return LocalDateTime.now(clock);}

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> open(long uid,long city,String role,String requested){
        LocalDateTime now=now();LocalDate day=text(requested).isEmpty()?now.toLocalDate():date(requested);
        require(!day.isAfter(now.toLocalDate()),"不能填报未来日期");
        Map<String,Object> own=scope.requireOwned(uid,city,role);
        Map<String,Object> row=db.one("select * from kg_ops_daily where owner_id=:u and city_id=:c and role_code=:r and biz_date=:d for update",p("u",uid,"c",city,"r",role,"d",day.toString()));
        // A new historical report cannot truthfully reconstruct mutable stock/status at its old cutoff.
        require(row!=null||day.equals(now.toLocalDate()),"该历史日期没有预填快照，不能补造自动数据");
        if(row!=null)scope.requireRead(uid,row);
        if(row!=null&&("submitted".equals(row.get("status"))||number(row.get("finalData"))==1))return present(row,uid);
        LocalDateTime cutoff=now.isBefore(end(day))?now:end(day);
        Map<String,Object> auto=metrics.daily(OpsScope.ids(own),role,day,cutoff);
        auto.put("snapshotGeneratedAt",now.format(DT));
        auto.put("lateSnapshot",now.isAfter(end(day).plusMinutes(5)));
        List<Map<String,Object>> progress=targets.progress(city,role,uid,YearMonth.from(day),cutoff);
        Map<String,Object> form=row==null?forms.current(role):OpsForms.snapshot(row);
        String manual=row==null?"{}":text(row.get("manualJson"));
        Map<String,Object> cols=p("owner_id",uid,"city_id",city,"role_code",role,"biz_date",day.toString(),"store_ids",JSON.toJSONString(OpsScope.ids(own)),"owner_name",own.get("ownerName"),"city_name",own.get("cityName"),"window_start",start(day).format(DT),"window_end",end(day).format(DT),"data_cutoff",cutoff.format(DT),"final_data",now.isBefore(end(day))?0:1,"auto_json",JSON.toJSONString(auto),"manual_json",manual,"progress_json",JSON.toJSONString(progress),"summary_text",summary(auto,JSON.parseObject(manual),progress,form),"update_time",now.format(DT));
        cols.put("form_json",JSON.toJSONString(form));
        long id;
        if(row==null){cols.put("create_time",now.format(DT));try{id=db.insert("kg_ops_daily",cols);}catch(org.springframework.dao.DuplicateKeyException e){throw new ServiceException("日报正在生成，请稍后重新打开",409);}}
        else{id=number(row.get("reportId"));cols.put("revision",number(row.get("revision"))+1);db.updateRow("kg_ops_daily","report_id",id,cols);}
        return present(db.one("select * from kg_ops_daily where report_id=:id",p("id",id)),uid);
    }
    Map<String,Object> load(long id,boolean lock){Map<String,Object> row=db.one("select * from kg_ops_daily where report_id=:id"+(lock?" for update":""),p("id",id));if(row==null)throw new ServiceException("日报不存在或无权查看",404);return row;}
    public Map<String,Object> get(long uid,long id){Map<String,Object> row=load(id,false);scope.requireRead(uid,row);return present(row,uid);}
    public Map<String,Object> adminGet(long id){Map<String,Object> row=load(id,false);scope.requireAdminCity(number(row.get("cityId")),false);return present(row,null);}

    @SuppressWarnings("unchecked")
    private Map<String,Object> present(Map<String,Object> raw,Long uid){
        Map<String,Object> row=new LinkedHashMap<>(raw);row.put("auto",JSON.parseObject(text(row.remove("autoJson"))));row.put("manual",JSON.parseObject(text(row.remove("manualJson"))));row.put("progress",JSON.parseArray(text(row.remove("progressJson"))));row.put("storeIds",OpsScope.ids(raw));
        row.put("form",OpsForms.snapshot(raw));
        row.put("editable",uid!=null&&number(row.get("ownerId"))==uid&&"draft".equals(row.get("status")));
        row.put("canSubmit",Boolean.TRUE.equals(row.get("editable"))&&number(row.get("finalData"))==1&&!now().isBefore(end(date(text(row.get("bizDate"))))));
        Map<String,Object> auto=(Map<String,Object>)row.get("auto"),manual=(Map<String,Object>)row.get("manual");
        if("keeper".equals(row.get("roleCode"))){
            long revenue=0,expense=0;for(Object item:(List<?>)auto.get("business")){Map<?,?> field=(Map<?,?>)item;Object value=field.get("value");if(value==null)continue;if("今日平台营收".equals(field.get("label")))revenue=new BigDecimal(value.toString()).movePointRight(2).longValueExact();if("今日平台支出".equals(field.get("label")))expense=new BigDecimal(value.toString()).movePointRight(2).longValueExact();}
            row.put("totals",map("platformIncomeFen",revenue,"platformExpenseFen",expense,"offlineIncomeFen",number(manual.get("offlineIncomeFen")),"offlineExpenseFen",number(manual.get("offlineExpenseFen")),"totalIncomeFen",revenue+number(manual.get("offlineIncomeFen")),"totalExpenseFen",expense+number(manual.get("offlineExpenseFen"))));
        }
        return row;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(long uid,long id,Map<String,Object> body,boolean submit){
        Map<String,Object> row=load(id,true);if(number(row.get("ownerId"))!=uid)throw new ServiceException("只能填报本人日报",403);scope.requireRead(uid,row);
        if(!"draft".equals(row.get("status")))throw new ServiceException("日报已提交，原始记录不可覆盖",409);
        if(!Objects.equals(KgJdbc.asLong(body.get("revision")),number(row.get("revision"))))throw new ServiceException("日报已刷新，请重新核对自动数据后保存",409);
        if(submit)require(number(row.get("finalData"))==1&&!now().isBefore(end(date(text(row.get("bizDate"))))),"18:00刷新完整数据后才能提交，请先刷新日报");
        Object raw=body.get("manual");require(raw instanceof Map,"请填写日报内容");
        List<Map<String,Object>> progress=(List)JSON.parseArray(text(row.get("progressJson")),Map.class);
        Map<String,Object> manual=validate((Map<String,Object>)raw,text(row.get("roleCode")),progress,submit,OpsForms.snapshot(row));
        validateExpenseDates(manual,row,submit);
        Map<String,Object> auto=JSON.parseObject(text(row.get("autoJson")));
        Map<String,Object> cols=p("manual_json",JSON.toJSONString(manual),"summary_text",summary(auto,manual,progress,OpsForms.snapshot(row)),"revision",number(row.get("revision"))+1,"update_time",now().format(DT));
        if(submit){cols.put("status","submitted");cols.put("submitted_at",now().format(DT));}
        db.updateRow("kg_ops_daily","report_id",id,cols);return present(load(id,false),uid);
    }
    static void validateExpenseDates(Map<String,Object> manual,Map<String,Object> row,boolean submit){
        if(submit&&manual.get("expenses") instanceof List){LocalDate day=date(text(row.get("bizDate")));for(Object value:(List<?>)manual.get("expenses")){LocalDate occurred=date(text(((Map<?,?>)value).get("date")));require(!occurred.isBefore(day.minusDays(1))&&!occurred.isAfter(day),"支出日期应在本期统计窗口的两天内；历史支出请在原业务账本补录");}}
    }
    static Map<String,Object> validate(Map<String,Object> raw,String role,List<Map<String,Object>> progress,boolean submit,Map<String,Object> form){
        Map<String,Object> out=new LinkedHashMap<>();
        for(Map<String,Object> f:OpsForms.fields(form))out.put(text(f.get("key")),OpsForms.validateValue(raw.get(text(f.get("key"))),f,submit));
        if("keeper".equals(role)){
            String income=text(raw.get("offlineIncome"));require(!submit||!income.isEmpty(),"请填写线下收入，没有请填0");out.put("offlineIncome",income);out.put("offlineIncomeFen",income.isEmpty()?0L:fen(income,"线下收入"));
            Object entries=raw.get("expenses");require(entries==null||entries instanceof List,"支出明细格式不正确");List<Map<String,Object>> checked=new ArrayList<>();long total=0;
            if(entries!=null){require(((List<?>)entries).size()<=100,"每份日报最多100笔支出");for(Object entry:(List<?>)entries){require(entry instanceof Map,"支出明细格式不正确");Map<?,?> e=(Map<?,?>)entry;String amount=text(e.get("amount"));long value=amount.isEmpty()?0:fen(amount,"支出金额");require(!submit||value>0,"支出金额须大于0；没有支出请删除空行");String occurred=text(e.get("date"));if(!occurred.isEmpty())date(occurred);require(!submit||!occurred.isEmpty(),"请填写支出日期");checked.add(map("date",occurred,"item",line(e.get("item"),"支出项目",100,submit),"handler",line(e.get("handler"),"经手人",64,submit),"amount",amount,"amountFen",value));total=Math.addExact(total,value);}}
            out.put("expenses",checked);out.put("offlineExpenseFen",total);
        }
        Object plans=raw.get("remedies");require(plans==null||plans instanceof Map,"补救计划格式不正确");Map<String,Object> remedies=new LinkedHashMap<>();
        for(Map<String,Object> target:progress){String key=String.valueOf(target.get("targetId"));Object value=plans instanceof Map?((Map<?,?>)plans).get(key):null;String plan=line(value,"落后任务补救计划",1000,submit&&"severe".equals(target.get("status")));if(!plan.isEmpty())remedies.put(key,plan);}
        out.put("remedies",remedies);return out;
    }
    static String summary(Map<String,Object> auto,Map<String,Object> manual,List<Map<String,Object>> progress,Map<String,Object> form){
        Map<?,?> count=(Map<?,?>)auto.get("counts");StringBuilder text=new StringBuilder();
        for(Map<String,Object> target:progress){Object today=count.get(target.get("metricCode"));text.append(target.get("label")).append("：今日 ").append(today==null?"不适用":today).append("，本月 ").append(target.get("completed")==null?"—":target.get("completed")).append('/').append(target.get("target")).append(target.get("unit")).append("。\n");}
        if(progress.isEmpty())text.append("本月尚未设置任务目标。\n");
        for(Object value:(List<?>)auto.get("business")){Map<?,?> field=(Map<?,?>)value;text.append(field.get("label")).append(' ').append(field.get("value")==null?"不适用":field.get("value")).append(field.get("unit")).append("；");}
        for(Object value:(List<?>)auto.get("workforce")){Map<?,?> line=(Map<?,?>)value;text.append('\n').append(line.get("title")).append("：");for(Object item:(List<?>)line.get("fields")){Map<?,?> field=(Map<?,?>)item;text.append(field.get("label")).append(' ').append(field.get("value")==null?"不适用":field.get("value")).append(field.get("unit")).append("；");}}
        text.append('\n');
        if(!((List<?>)auto.get("business")).isEmpty()){long missing=number(auto.get("weekActivityMissing"));text.append("本周发布活动 ").append(auto.get("weekActivityCount")).append(" 场，").append(missing>0?"距达标还差 "+missing+" 场":"已达标").append("。\n");}
        text.append("本期新增异常 ").append(count.get("cases")).append(" 起，其中已闭环 ").append(count.get("closedCases")).append(" 起。");
        for(Map<String,Object> f:OpsForms.fields(form))if(Boolean.TRUE.equals(f.get("enabled"))){String key=text(f.get("key"));if("tomorrowPlan".equals(key)||key.startsWith("custom_"))text.append('\n').append(f.get("label")).append("：").append(text(manual.get(key)).isEmpty()?"待填写":manual.get(key));}return text.toString();
    }
    public Map<String,Object> list(String dateFrom,String dateTo,Long city,String role,Long owner,String status,Integer page){
        LocalDate from=date(text(dateFrom).isEmpty()?now().toLocalDate().minusDays(30).toString():dateFrom),to=date(text(dateTo).isEmpty()?now().toLocalDate().toString():dateTo);require(!to.isBefore(from)&&!to.isAfter(from.plusDays(366)),"查询日期范围最多366天");
        String where=" where d.biz_date>=:from and d.biz_date<=:to";Map<String,Object> ps=p("from",from.toString(),"to",to.toString());
        if(city!=null){scope.requireAdminCity(city,false);where+=" and d.city_id=:city";ps.put("city",city);}if(!text(role).isEmpty()){role(role);where+=" and d.role_code=:role";ps.put("role",role);}if(owner!=null){where+=" and d.owner_id=:owner";ps.put("owner",owner);}if(!text(status).isEmpty()){require(Arrays.asList("draft","submitted").contains(status),"报表状态无效");where+=" and d.status=:status";ps.put("status",status);}
        return db.page("select d.report_id,d.owner_id,d.owner_name,d.city_id,d.city_name,d.role_code,d.biz_date,d.status,d.final_data,d.submitted_at,d.update_time from kg_ops_daily d"+where+" order by d.biz_date desc,d.city_id,d.report_id",ps,page,30);
    }
    public List<Map<String,Object>> mine(long uid,String month){
        YearMonth m=month(text(month).isEmpty()?YearMonth.from(now()).toString():month);List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:db.list("select report_id,owner_id,city_id,city_name,role_code,biz_date,status,submitted_at,store_ids from kg_ops_daily where owner_id=:u and biz_date>=:f and biz_date<:t order by biz_date desc,report_id",p("u",uid,"f",m.atDay(1).toString(),"t",m.plusMonths(1).atDay(1).toString()))){try{scope.requireRead(uid,row);out.add(row);}catch(ServiceException e){if(e.getCode()==null||e.getCode()!=403)throw e;}}
        return out;
    }
}
