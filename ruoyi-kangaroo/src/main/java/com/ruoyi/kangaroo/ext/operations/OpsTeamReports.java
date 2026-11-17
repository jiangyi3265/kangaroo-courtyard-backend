package com.ruoyi.kangaroo.ext.operations;
import java.util.*;
import java.time.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.manage.KgManageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** An explicitly granted cross-city reader; all operations are GET-only and submitted snapshots only. */
@Service public class OpsTeamReports {
    @Autowired private KgJdbc db;
    @Autowired private OpsViewerGrants grants;
    @Autowired private OpsDaily daily;
    @Autowired private OpsMonthly monthly;
    @Autowired private OpsExport export;
    @Autowired private KgManageService weekly;
    public Map<String,Object> access(long uid){boolean allowed=grants.allowed(uid);return p("allowed",allowed,"month",YearMonth.now(ZONE).toString(),"cities",allowed?db.list("select city_id,name from kg_city where status='0' order by name",p()):Collections.emptyList());}
    private void kind(String k){require(Arrays.asList("daily","weekly","monthly").contains(k),"请选择日报、周报或月度总结");}
    public Map<String,Object> list(long uid,String kind,String requested,Long city,String keyword,Integer page){
        grants.requireViewer(uid);kind(kind);YearMonth m=month(requested);String q=line(keyword,"姓名关键词",40,false);Map<String,Object> ps=p("f",m.atDay(1).toString(),"t",m.plusMonths(1).atDay(1).toString(),"m",m.toString());String sql;
        if("weekly".equals(kind)){
            sql="select r.report_id id,r.week_start period,r.submitted_name owner_name,s.city city_name,s.store_name,case when r.report_type='recruit' then '换工招募周报' else '经营周报' end title,r.submitted_time submitted_at from kg_weekly_report r join kg_store s on s.store_id=r.store_id where r.status='submitted' and r.week_start>=:f and r.week_start<:t";
            if(city!=null){sql+=" and exists(select 1 from kg_city c where c.city_id=:city and c.status='0' and (c.name=s.city or c.full_name=s.city))";ps.put("city",city);}
            if(!q.isEmpty()){sql+=" and r.submitted_name like :q";ps.put("q","%"+q.replace("%","\\%").replace("_","\\_")+"%");}sql+=" order by r.week_start desc,r.report_id desc";
        }else{
            boolean d="daily".equals(kind);String table=d?"kg_ops_daily":"kg_ops_monthly",id=d?"report_id":"summary_id",period=d?"biz_date":"month_key";
            sql="select r."+id+" id,r."+period+" period,r.owner_name,r.city_name,r.role_code,"+(d?"r.submitted_at":"r.generated_at submitted_at")+" from "+table+" r join kg_city c on c.city_id=r.city_id and c.status='0' where "+(d?"r.status='submitted' and r.biz_date>=:f and r.biz_date<:t":"r.month_key=:m");
            if(city!=null){sql+=" and r.city_id=:city";ps.put("city",city);}if(!q.isEmpty()){sql+=" and r.owner_name like :q";ps.put("q","%"+q.replace("%","\\%").replace("_","\\_")+"%");}sql+=" order by r."+period+" desc,r."+id+" desc";
        }
        require(page==null||page>=1&&page<=10000,"页码无效");return db.page(sql,ps,page,20);
    }
    public Map<String,Object> detail(long uid,String kind,long id){
        grants.requireViewer(uid);kind(kind);require(id>0,"报表编号无效");List<List<String>> rows;Map<String,Object> result;
        if("weekly".equals(kind)){
            Map<String,Object> r=db.one("select report_id,status,snapshot from kg_weekly_report where report_id=:id",p("id",id));if(r==null||!"submitted".equals(r.get("status")))throw new ServiceException("报表尚未提交或已被退回",404);
            Map<String,Object> context=weekly.weeklyReviewContext(id);result=p("title",context.get("title"),"text",context.get("text"),"images",context.get("images"));rows=new ArrayList<>();
            if(!text(r.get("snapshot")).isEmpty()){
                Map<String,Object> snap=JSON.parseObject(text(r.get("snapshot")));
                for(String key:Arrays.asList("works","bizCols","orderCols")){
                    Object fields=snap.get(key);
                    if(fields instanceof List)for(Object v:(List<?>)fields){Map<?,?> f=(Map<?,?>)v;rows.add(Arrays.asList(text(f.get("label")),text(f.get("value"))));}
                }
            }
        }else{Map<String,Object> r="daily".equals(kind)?daily.adminGet(id):monthly.adminGet(id);if("daily".equals(kind)&&!"submitted".equals(r.get("status")))throw new ServiceException("日报尚未提交",404);rows=export.rows(r);result=p("title","daily".equals(kind)?"每日工作报告":"月度总结");}
        List<Map<String,Object>> fields=new ArrayList<>();for(List<String> row:rows)fields.add(p("label",row.isEmpty()?"":row.get(0),"value",row.size()>1?String.join(" · ",row.subList(1,row.size())):""));result.put("fields",fields);return result;
    }
}
