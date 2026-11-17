package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Service
public class OpsTargets {
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    @Autowired private OpsMetrics metrics;
    Clock clock=Clock.system(ZONE);
    LocalDateTime now(){return LocalDateTime.now(clock);}
    public List<Map<String,Object>> progress(long city,String role,long owner,YearMonth month,LocalDateTime cutoff){
        Map<String,Object> ps=p("city",city,"role",role,"owner",owner,"month",month.toString());
        List<Map<String,Object>> targets=db.list("select * from kg_ops_target where city_id=:city and role_code=:role and month_key=:month and owner_id in (0,:owner) order by owner_id,target_id",ps);
        return calculate(targets,cutoff);
    }
    public List<Map<String,Object>> calculate(List<Map<String,Object>> targets,LocalDateTime cutoff){
        Map<String,Map<String,Object>> cache=new HashMap<>();List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:targets){
            long city=number(row.get("cityId")),owner=number(row.get("ownerId"));String role=text(row.get("roleCode")),code=text(row.get("metricCode"));YearMonth month=month(text(row.get("monthKey")));
            Map<String,Object> m=new LinkedHashMap<>(row);m.putAll(OpsCatalog.get(code,role));m.put("roleCode",role);
            m.put("target",row.get("targetValue"));
            m.put("scopeLabel",owner==0?"地区团队目标":"个人授权门店目标");
            if(city==0){m.putAll(map("status","template","statusLabel","全国模板","completed",null));out.add(m);continue;}
            LocalDateTime until=cutoff.isAfter(monthEnd(month))?monthEnd(month):cutoff.isBefore(monthStart(month))?monthStart(month):cutoff;
            String key=city+":"+owner+":"+role+":"+month;
            Map<String,Object> counts=cache.get(key);
            if(counts==null){
                List<Long> stores;
                try{stores=owner==0?scope.cityStores(city):OpsScope.ids(scope.requireOwned(owner,city,role));}
                catch(ServiceException e){if(e.getCode()!=null&&e.getCode()==403){m.putAll(map("status","unavailable","statusLabel","填报身份已停用","completed",null));out.add(m);continue;}throw e;}
                counts=metrics.counts(stores,monthStart(month),until);cache.put(key,counts);
            }
            Object raw=counts.get(code);BigDecimal completed=raw==null?null:new BigDecimal(raw.toString());
            m.putAll(OpsRules.progress(new BigDecimal(row.get("targetValue").toString()),completed,month,until,(int)number(row.get("normalRatio")),(int)number(row.get("slowRatio"))));out.add(m);
        }
        return out;
    }
    public List<Map<String,Object>> list(String month,Long city,String role,Long owner){
        month(month);String where=" where t.month_key=:month";Map<String,Object> ps=p("month",month);
        if(city!=null){scope.requireAdminCity(city,true);where+=" and t.city_id=:city";ps.put("city",city);}
        if(!text(role).isEmpty()){role(role);where+=" and t.role_code=:role";ps.put("role",role);}
        if(owner!=null){where+=" and t.owner_id=:owner";ps.put("owner",owner);}
        List<Map<String,Object>> rows=db.list("select t.*,c.name city_name from kg_ops_target t left join kg_city c on c.city_id=t.city_id"+where+" order by t.city_id,t.owner_id,t.target_id",ps);
        for(Map<String,Object> row:rows){long uid=number(row.get("ownerId"));Map<String,Object> user=uid==0?null:db.one("select nickname from kg_app_user where user_id=:id",p("id",uid));row.put("ownerName",uid==0?"地区团队":user==null?"已停用人员":user.get("nickname"));}return rows;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(Map<String,Object> body,String actor){
        String month=text(body.get("monthKey")),role=role(text(body.get("roleCode"))),code=text(body.get("metricCode"));YearMonth ym=month(month);
        require(!ym.isBefore(YearMonth.from(now()))&&ym.isBefore(YearMonth.from(now()).plusMonths(25)),"只能设置本月及未来24个月的目标");
        Long city=KgJdbc.asLong(body.get("cityId")),owner=KgJdbc.asLong(body.get("ownerId"));require(city!=null&&city>=0,"请选择地区或全国模板");if(owner==null)owner=0L;
        scope.requireAdminCity(city,true);require(owner>=0&&(city!=0||owner==0),"全国模板不能指定个人");if(owner>0)scope.requireOwned(owner,city,role);
        OpsCatalog.get(code,role);BigDecimal value=decimal(body.get("targetValue"),"目标值");require(value.signum()>0,"目标值必须大于0");
        if("closureRate".equals(code))require(value.compareTo(BigDecimal.valueOf(100))<=0,"闭环率目标不能超过100%");else require(value.stripTrailingZeros().scale()<=0,"数量型目标须为整数");
        Long normalValue=body.containsKey("normalRatio")?KgJdbc.asLong(body.get("normalRatio")):80L,slowValue=body.containsKey("slowRatio")?KgJdbc.asLong(body.get("slowRatio")):50L;
        require(normalValue!=null&&slowValue!=null&&normalValue<=100&&slowValue>0,"进度阈值须为1至100之间的整数");int normal=normalValue.intValue(),slow=slowValue.intValue();
        require(slow>0&&slow<normal&&normal<=100,"进度阈值须满足0＜严重落后阈值＜正常阈值≤100");
        Map<String,Object> cols=p("month_key",month,"city_id",city,"role_code",role,"owner_id",owner,"metric_code",code,"target_value",value,"normal_ratio",normal,"slow_ratio",slow,"update_by",actor,"update_time",now().format(DT));
        Long id=KgJdbc.asLong(body.get("targetId"));
        if(id==null){cols.put("create_by",actor);cols.put("create_time",now().format(DT));try{id=db.insert("kg_ops_target",cols);}catch(org.springframework.dao.DuplicateKeyException e){throw new ServiceException("相同月份、地区、主理人和指标已存在，请编辑原目标",409);}}
        else{
            Map<String,Object> old=db.one("select * from kg_ops_target where target_id=:id for update",p("id",id));require(old!=null,"目标不存在");scope.requireAdminCity(number(old.get("cityId")),true);
            require(month.equals(old.get("monthKey"))&&city==number(old.get("cityId"))&&owner==number(old.get("ownerId"))&&role.equals(old.get("roleCode"))&&code.equals(old.get("metricCode")),"已有目标的月份、地区、人员及指标不可修改");
            if(!Objects.equals(KgJdbc.asLong(body.get("revision")),number(old.get("revision"))))throw new ServiceException("目标已被更新，请刷新后重试",409);
            cols.put("revision",number(old.get("revision"))+1);db.updateRow("kg_ops_target","target_id",id,cols);
        }
        return db.one("select * from kg_ops_target where target_id=:id",p("id",id));
    }
    @Transactional(rollbackFor=Exception.class)
    public int copyTemplate(Map<String,Object> body,String actor){
        String from=text(body.get("sourceMonth")),to=text(body.get("monthKey"));month(from);month(to);scope.requireAdminCity(0,true);
        Object raw=body.get("cityIds");require(raw instanceof List,"请选择复制到的地区");List<?> cities=(List<?>)raw;require(!cities.isEmpty()&&cities.size()<=300,"请选择1至300个地区");
        List<Map<String,Object>> templates=list(from,0L,null,null);require(!templates.isEmpty(),"该月份没有全国模板");int count=0;
        for(Object cityRaw:new LinkedHashSet<>(cities)){
            Long city=KgJdbc.asLong(cityRaw);require(city!=null&&city>0,"模板只能复制到具体地区");scope.requireAdminCity(city,false);
            for(Map<String,Object> template:templates){
                Map<String,Object> ps=p("m",to,"c",city,"r",template.get("roleCode"),"k",template.get("metricCode"));
                if(db.count("select count(*) from kg_ops_target where month_key=:m and city_id=:c and role_code=:r and owner_id=0 and metric_code=:k",ps)>0)continue;
                Map<String,Object> next=new LinkedHashMap<>(template);next.remove("targetId");next.put("cityId",city);next.put("monthKey",to);save(next,actor);count++;
            }
        }
        return count;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> result(Map<String,Object> body,String actor){
        Long store=KgJdbc.asLong(body.get("storeId"));require(store!=null&&store>0,"请选择实际合作门店");String code=text(body.get("metricCode"));require(Arrays.asList("managedContracts","cooperativeYards").contains(code),"请选择合营或代运营签约结果");
        Map<String,Object> row=db.one("select s.store_id,c.city_id from kg_store s join kg_city c on (c.name=s.city or c.full_name=s.city) where s.store_id=:s and s.del_flag='0' and c.status='0'",p("s",store));require(row!=null,"门店不存在");scope.requireAdminCity(number(row.get("cityId")),false);
        LocalDate occurred=date(text(body.get("occurredDate")));require(!occurred.isAfter(now().toLocalDate())&&!occurred.isBefore(now().toLocalDate().minusYears(10)),"请填写真实签约日期，不得晚于今天");String evidence=line(body.get("evidence"),"合同编号或签约凭据说明",1000,true);
        Map<String,Object> old=db.one("select * from kg_ops_result where store_id=:s and metric_code=:c for update",p("s",store,"c",code));
        Map<String,Object> values=p("store_id",store,"metric_code",code,"occurred_at",occurred.atTime(12,0).format(DT),"evidence",evidence,"status","1".equals(text(body.get("status")))?"1":"0","update_by",actor,"update_time",now().format(DT));long id;
        if(old==null){values.put("create_by",actor);values.put("create_time",now().format(DT));id=db.insert("kg_ops_result",values);}else{if(!Objects.equals(KgJdbc.asLong(body.get("revision")),number(old.get("revision"))))throw new ServiceException("结果记录已更新，请刷新后重试",409);values.put("revision",number(old.get("revision"))+1);id=number(old.get("resultId"));db.updateRow("kg_ops_result","result_id",id,values);}
        return db.one("select * from kg_ops_result where result_id=:id",p("id",id));
    }
}
