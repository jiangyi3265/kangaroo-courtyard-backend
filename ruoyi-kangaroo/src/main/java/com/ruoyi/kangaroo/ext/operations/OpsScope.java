package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.CityGrantScope;
import com.ruoyi.kangaroo.ext.staff.CityLeadScope;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.security.admin.AdminCityContext;

/** Each write rereads active grants; neither a request city nor an old token grants access. */
@Component
public class OpsScope {
    @Autowired private KgJdbc db;
    @Autowired private CityGrantScope cityGrants;
    @Autowired private CityLeadScope leads;

    public List<Map<String,Object>> owned(long uid){
        Map<String,Object> user=db.one("select nickname from kg_app_user where user_id=:u and status='0' and del_flag='0'",p("u",uid));
        if(user==null)return Collections.emptyList();
        Map<Long,String> roles=new LinkedHashMap<>();
        for(Map<String,Object> row:db.list("select g.store_id,g.role_code from kg_staff_grant g join kg_store s on s.store_id=g.store_id where g.app_user_id=:u and g.status='0' and g.del_flag='0' and g.role_code in ('keeper','manager') and s.status='0' and s.del_flag='0' order by g.grant_id",p("u",uid)))roles.put(number(row.get("storeId")),text(row.get("roleCode")));
        for(Map.Entry<Long,StaffGrantView> e:cityGrants.stores(uid).entrySet())if(Arrays.asList("keeper","manager").contains(e.getValue().getRoleCode()))roles.putIfAbsent(e.getKey(),e.getValue().getRoleCode());
        if(roles.isEmpty())return Collections.emptyList();
        Map<String,Map<String,Object>> groups=new LinkedHashMap<>();
        for(Map<String,Object> row:db.list("select s.store_id,s.store_name,c.city_id,c.name city_name from kg_store s join kg_city c on (s.city=c.name or s.city=c.full_name) and c.status='0' where s.store_id in (:ids) and s.status='0' and s.del_flag='0' order by c.city_id,s.store_id",p("ids",roles.keySet()))){
            long id=number(row.get("storeId")),city=number(row.get("cityId"));String role=roles.get(id),key=city+":"+role;
            Map<String,Object> group=groups.computeIfAbsent(key,k->map("cityId",city,"cityName",row.get("cityName"),"roleCode",role,"ownerId",uid,"ownerName",user.get("nickname"),"storeIds",new ArrayList<Long>(),"storeNames",new ArrayList<String>()));
            List<Long> ids=ids(group);if(!ids.contains(id)){ids.add(id);((List<String>)group.get("storeNames")).add(text(row.get("storeName")));}
        }
        return new ArrayList<>(groups.values());
    }
    @SuppressWarnings("unchecked") public static List<Long> ids(Map<String,Object> row){
        Object value=row.get("storeIds");
        return value instanceof String?JSON.parseArray((String)value,Long.class):(List<Long>)value;
    }
    public Map<String,Object> requireOwned(long uid,long city,String role){
        role(role);for(Map<String,Object> s:owned(uid))if(number(s.get("cityId"))==city&&role.equals(s.get("roleCode")))return s;
        throw new ServiceException("当前身份没有该地区日报的填报权限",403);
    }
    public boolean canObserve(long uid,Map<String,Object> row){
        List<Long> stores=ids(row);return !stores.isEmpty()&&leads.stores(uid).keySet().containsAll(stores);
    }
    public void requireRead(long uid,Map<String,Object> row){
        if(number(row.get("ownerId"))==uid){
            Map<String,Object> scope=requireOwned(uid,number(row.get("cityId")),text(row.get("roleCode")));
            if(ids(scope).containsAll(ids(row)))return;
        }
        if(canObserve(uid,row))return;
        throw new ServiceException("没有这份报表的查看权限或门店授权已变更",403);
    }
    public List<Map<String,Object>> observerCities(long uid){
        Set<Long> stores=leads.stores(uid).keySet();if(stores.isEmpty())return Collections.emptyList();
        return db.list("select distinct c.city_id,c.name from kg_city c join kg_store s on (s.city=c.name or s.city=c.full_name) where s.store_id in (:s) and c.status='0' order by c.city_id",p("s",stores));
    }
    public List<Long> cityStores(long city){
        List<Long> ids=new ArrayList<>();for(Map<String,Object> r:db.list("select distinct s.store_id from kg_store s join kg_city c on (s.city=c.name or s.city=c.full_name) where c.city_id=:c and c.status='0' and s.status='0' and s.del_flag='0' order by s.store_id",p("c",city)))ids.add(number(r.get("storeId")));return ids;
    }
    public Map<String,Object> city(long city){Map<String,Object> row=db.one("select city_id,name from kg_city where city_id=:c and status='0'",p("c",city));if(row==null)throw new ServiceException("地区不存在或已停用",422);return row;}
    public void requireAdminCity(long city,boolean template){
        if(city==0&&template){if(AdminCityContext.limited())throw new ServiceException("全国模板需全部城市权限",403);return;}
        city(city);if(AdminCityContext.limited()&&!AdminCityContext.cities().contains(city))throw new ServiceException("未授权该城市",403);
    }
    public List<Long> principals(){
        List<Long> out=new ArrayList<>();for(Map<String,Object> r:db.list("select distinct g.app_user_id from kg_staff_grant g join kg_app_user u on u.user_id=g.app_user_id where g.role_code in ('manager','keeper') and g.status='0' and g.del_flag='0' and u.status='0' and u.del_flag='0' order by g.app_user_id",p()))out.add(number(r.get("appUserId")));return out;
    }
    public Set<Long> supervisors(List<Long> stores){Set<Long> out=new LinkedHashSet<>();for(Long s:stores)out.addAll(leads.leadsOf(s));return out;}
    public List<Long> observedStores(long uid,long city){List<Long> out=cityStores(city);out.retainAll(leads.stores(uid).keySet());return out;}
}
