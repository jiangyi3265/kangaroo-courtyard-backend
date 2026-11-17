package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.util.*;

/** Whitelisted metrics; the browser cannot submit SQL or invent a completion number. */
public final class OpsCatalog {
    private OpsCatalog(){}
    public static List<Map<String,Object>> metrics(){
        List<Map<String,Object>> out=new ArrayList<>();
        out.add(metric("newStores","新增合作商家","家","manager","按非直营门店首次建档时间统计，同一门店只计一次"));
        for(String type:Arrays.asList("volunteer","parttime","fulltime")){
            String name="volunteer".equals(type)?"义工":"parttime".equals(type)?"兼职":"全职";
            out.add(metric(type+"Jobs","新增"+name+"岗位","个","manager","首次发布时间在统计区间内，已取消和草稿不计"));
            out.add(metric(type+"Applications",name+"报名","人次","manager","按有效报名首次提交时间统计"));
            out.add(metric(type+"Arrivals",name+"到岗","人次","manager","按实际办理到岗时间统计"));
        }
        out.add(metric("activities","发布活动","场","both","按首次发布时间统计，取消、草稿和驳回活动不计；历史活动按已有创建时间回填"));
        out.add(metric("cooperativeYards","新增合营院子","家","keeper","后台根据实际合作凭证登记，日报不会把普通门店自动当成已签约"));
        out.add(metric("managedContracts","代运营签约","家","manager","后台根据实际签约凭证登记，同一门店只计一次"));
        out.add(metric("onlinePaidPeople","线上任务已确认收款","人","manager","按接单人确认收款时间统计去重人数，登记付款不等于已到账"));
        out.add(metric("closureRate","异常闭环率","%","both","本期新建调解工单中截至统计时点已闭环的比例；无工单时不适用"));
        return out;
    }
    private static Map<String,Object> metric(String code,String label,String unit,String role,String source){return map("code",code,"label",label,"unit",unit,"roleCode",role,"source",source);}
    public static Map<String,Object> get(String code,String role){for(Map<String,Object> m:metrics())if(code.equals(m.get("code"))&&(role.equals(m.get("roleCode"))||"both".equals(m.get("roleCode"))))return m;throw new com.ruoyi.common.exception.ServiceException("该身份不支持此任务指标",422);}
}
