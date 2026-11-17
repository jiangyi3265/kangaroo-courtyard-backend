package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.math.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** SQL sources and interval bounds are explicit; no counts inferred from prose or star ratings. */
@Component
public class OpsMetrics {
    @Autowired private KgJdbc db;
    private Map<String,Object> params(List<Long> stores,LocalDateTime from,LocalDateTime to){return p("stores",stores.isEmpty()?Collections.singletonList(-1L):stores,"from",from.format(DT),"to",to.format(DT));}
    private long n(String query,Map<String,Object> ps){return db.count(query,ps);}
    static BigDecimal percent(long done,long total){return total==0?null:BigDecimal.valueOf(done).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),2,RoundingMode.HALF_UP);}
    public Map<String,Object> counts(List<Long> stores,LocalDateTime from,LocalDateTime to){
        Map<String,Object> ps=params(stores,from,to),out=new LinkedHashMap<>();
        out.put("newStores",n("select count(*) from kg_store where store_id in (:stores) and del_flag='0' and op_type<>'direct' and create_time>=:from and create_time<:to",ps));
        for(String type:Arrays.asList("volunteer","parttime","fulltime","online")){
            ps.put("type",type);
            out.put(type+"Jobs",n("select count(*) from kg_job where store_id in (:stores) and job_type=:type and del_flag='0' and status in ('published','paused','closed') and publish_time>=:from and publish_time<:to",ps));
            out.put(type+"Applications",n("select count(*) from kg_application a join kg_job j on j.job_id=a.job_id where a.store_id in (:stores) and a.del_flag='0' and j.job_type=:type and coalesce(a.submit_time,a.create_time)>=:from and coalesce(a.submit_time,a.create_time)<:to",ps));
            if(!"online".equals(type))out.put(type+"Arrivals",n("select count(*) from kg_application a join kg_job j on j.job_id=a.job_id where a.store_id in (:stores) and a.del_flag='0' and j.job_type=:type and a.onboard_time>=:from and a.onboard_time<:to",ps));
        }
        out.put("activities",n("select count(*) from kg_activity where store_id in (:stores) and del_flag='0' and status in ('open','paused','closed','ended') and first_published_time>=:from and first_published_time<:to",ps));
        for(String metric:Arrays.asList("cooperativeYards","managedContracts")){ps.put("metric",metric);out.put(metric,n("select count(*) from kg_ops_result where store_id in (:stores) and metric_code=:metric and status='0' and occurred_at>=:from and occurred_at<:to",ps));}
        out.put("onlinePaidPeople",n("select count(distinct app_user_id) from kg_online_settlement where store_id in (:stores) and confirmed_time>=:from and confirmed_time<:to",ps));
        out.put("onlinePaidCount",n("select count(*) from kg_online_settlement where store_id in (:stores) and confirmed_time>=:from and confirmed_time<:to",ps));
        out.put("onlineLinks",n("select count(*) from kg_online_submission s join kg_application a on a.app_id=s.app_id where a.store_id in (:stores) and a.del_flag='0' and trim(s.link)<>'' and s.create_time>=:from and s.create_time<:to",ps));
        long cases=n("select count(*) from kg_mediation_case where store_id in (:stores) and create_time>=:from and create_time<:to",ps);
        long closed=n("select count(*) from kg_mediation_case where store_id in (:stores) and create_time>=:from and create_time<:to and closed_at is not null and closed_at<:to",ps);
        out.putAll(map("cases",cases,"closedCases",closed,"openCases",cases-closed,"closureRate",percent(closed,cases)));
        return out;
    }
    private List<Map<String,Object>> workforce(List<Long> stores,LocalDateTime from,LocalDateTime to,Map<String,Object> counts){
        Map<String,Object> ps=params(stores,from,to);List<Map<String,Object>> lines=new ArrayList<>();
        String end="coalesce(a.volunteer_done_time, timestamp(date_add(a.arrival_date,interval a.stay_days day)))";
        for(String type:Arrays.asList("volunteer","parttime","fulltime")){
            ps.put("type",type);List<Map<String,Object>> fields=new ArrayList<>();
            String job=" from kg_job j where j.store_id in (:stores) and j.job_type=:type and j.del_flag='0' ";
            String apps=" from kg_application a join kg_job j on j.job_id=a.job_id where a.store_id in (:stores) and a.del_flag='0' and j.job_type=:type ";
            String interviews=" from kg_interview i join kg_application a on a.app_id=i.app_id join kg_job j on j.job_id=a.job_id where a.store_id in (:stores) and a.del_flag='0' and i.del_flag='0' and j.job_type=:type ";
            add(fields,"新增合作商家",n("select count(distinct s.store_id) from kg_store s join kg_job j on j.store_id=s.store_id where s.store_id in (:stores) and s.del_flag='0' and s.op_type<>'direct' and s.create_time>=:from and s.create_time<:to and j.job_type=:type and j.publish_time<:to and j.del_flag='0'",ps),"家","按商家首次建档及已发布岗位类型归类，跨业务类型不可直接相加");
            add(fields,"新增岗位",counts.get(type+"Jobs"),"个","按首次发布时间");
            add(fields,"当前在架岗位",n("select count(*)"+job+"and j.status='published' and j.publish_time<:to",ps),"个","生成快照时仍在招聘的岗位");
            add(fields,"今日报名",counts.get(type+"Applications"),"人次","首次提交的有效报名");
            add(fields,"今日安排面试",n("select count(*)"+interviews+"and i.create_time>=:from and i.create_time<:to and i.status<>'canceled'",ps),"场","按预约建立时间；取消预约不计");
            for(String result:Arrays.asList("pass","reject")){ps.put("result",result);add(fields,"pass".equals(result)?"今日面试通过":"今日面试未通过",n("select count(*)"+interviews+"and i.staff_result=:result and i.staff_result_time>=:from and i.staff_result_time<:to",ps),"人","按员工明确登记的面试结果，不以星级或报名审核代替");}
            add(fields,"面试结果待补录",n("select count(*)"+interviews+"and i.finish_time>=:from and i.finish_time<:to and (i.staff_result is null or i.staff_result='pending')",ps),"人","历史未记录的结果不会自动猜测为通过或失败");
            add(fields,"今日到岗",counts.get(type+"Arrivals"),"人次","按实际办理到岗时间");
            add(fields,"当前在岗",n("select count(*)"+apps+"and a.onboard_time<:to and ("+end+" is null or "+end+">=:to)",ps),"人","实际到岗后，尚未登记结束且约定期未满");
            add(fields,"今日离岗／结束",n("select count(*)"+apps+"and a.onboard_time<:to and "+end+">=:from and "+end+"<:to",ps),"人","实际结束或约定期满；实际结束优先");
            add(fields,"今日异常／纠纷",n("select count(*) from kg_mediation_case c join kg_application a on c.order_type='application' and c.order_id=a.app_id join kg_job j on j.job_id=a.job_id where c.store_id in (:stores) and j.job_type=:type and c.create_time>=:from and c.create_time<:to",ps),"起","系统调解工单");
            if("parttime".equals(type)){
                add(fields,"线上任务发布",counts.get("onlineJobs"),"个","线上任务首次发布时间");add(fields,"线上任务报名",counts.get("onlineApplications"),"人次","线上任务报名提交时间");
                add(fields,"线上成果链接提交",counts.get("onlineLinks"),"次","有链接的交付提交次数，重新提交按新一轮统计");add(fields,"线上验收后已确认收款",counts.get("onlinePaidCount"),"单","按接单人确认收款时间；不是待付款或仅登记付款");
            }
            lines.add(map("type",type,"title","volunteer".equals(type)?"义工业务":"parttime".equals(type)?"兼职与线上任务":"全职业务","fields",fields));
        }
        return lines;
    }
    private void add(List<Map<String,Object>> fields,String label,Object value,String unit,String source){fields.add(map("label",label,"value",value,"unit",unit,"source",source));}
    private long refunds(String table,Map<String,Object> ps,boolean verified){
        String valid="o.store_id in (:stores) and o.del_flag='0'"+(verified?" and o.verify_time is not null":"");
        long recorded=n("select coalesce(sum(r.amount),0) from kg_pay_refund r where r.status='success' and r.success_time>=:from and r.success_time<:to and exists(select 1 from "+table+" o where o.pay_no=r.pay_no and "+valid+")",ps);
        // Old orders without any refund ledger retain the legacy fallback, never both sources.
        long legacy=n("select coalesce(sum(o.refund_fen),0) from "+table+" o where "+valid+" and o.refund_time>=:from and o.refund_time<:to and not exists(select 1 from kg_pay_refund r where r.pay_no=o.pay_no and r.status='success')",ps);return recorded+legacy;
    }
    private List<Map<String,Object>> business(List<Long> stores,LocalDate d,LocalDateTime from,LocalDateTime to){
        Map<String,Object> ps=params(stores,from,to);ps.put("day",d.toString());ps.put("tomorrow",d.plusDays(1).toString());
        List<Map<String,Object>> fields=new ArrayList<>();
        String occupied=" from kg_stay_order where store_id in (:stores) and del_flag='0' and verify_time<:to and (checked_out_time is null or checked_out_time>=:to) and check_out>:day and check_in<=:day ";
        long guests=n("select coalesce(sum(guests),0)"+occupied,ps),units=n("select coalesce(sum(units),0)"+occupied,ps);
        long stock=n("select coalesce(sum(case when c.closed='1' then 0 else coalesce(c.stock,r.stock) end),0) from kg_stay_room_type r join kg_stay s on s.stay_id=r.stay_id left join kg_stay_calendar c on c.room_type_id=r.room_type_id and c.stay_date=:day where s.store_id in (:stores) and s.del_flag='0' and r.del_flag='0' and r.status='0'",ps);
        add(fields,"今日旅居住客数",guests,"人","已办理入住且截至统计时点未退房的平台订单；未登记的线下住客不自动推算");
        add(fields,"今日平台订单住客数",guests,"人","已核销入住订单的登记人数");
        add(fields,"今日总入住率",percent(units,stock),"%","已入住间／床位数除以当日可售间／床位库存；无库存时不适用");
        long stay=n("select coalesce(sum(pay_fen),0) from kg_stay_order where store_id in (:stores) and del_flag='0' and paid_time>=:from and paid_time<:to",ps)-refunds("kg_stay_order",ps,false);
        long shop=n("select coalesce(sum(pay_fen),0) from kg_shop_order where store_id in (:stores) and del_flag='0' and verify_time>=:from and verify_time<:to",ps)-refunds("kg_shop_order",ps,true);
        long spend=n("select coalesce(sum(round(amount*100)),0) from kg_reimbursement where store_id in (:stores) and del_flag='0' and status='paid' and paid_time>=:from and paid_time<:to",ps);
        add(fields,"今日平台营收",BigDecimal.valueOf(stay+shop,2),"元","旅居订单实收减当期退款，加二消核销收入减当期退款");
        add(fields,"今日平台支出",BigDecimal.valueOf(spend,2),"元","已登记打款的报销记录，未登记的采购和平台代付不推算");
        add(fields,"今日差评",n("select count(*) from kg_review where store_id in (:stores) and del_flag='0' and status='0' and rating<=2 and create_time>=:from and create_time<:to",ps),"条","平台可见的1至2星评价");
        add(fields,"明日已预订人数",n("select coalesce(sum(guests),0) from kg_stay_order where store_id in (:stores) and del_flag='0' and paid_time<:to and check_in<=:tomorrow and check_out>:tomorrow and status in ('confirmed','checked_in','refund_pending')",ps),"人","明日在住日期内已支付的有效预订");
        return fields;
    }
    public Map<String,Object> daily(List<Long> stores,String role,LocalDate date,LocalDateTime cutoff){
        Map<String,Object> counts=counts(stores,start(date),cutoff),ps=params(stores,start(date),cutoff);
        LocalDate monday=date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));ps.put("week",start(monday).format(DT));
        List<Map<String,Object>> activities=db.list("select activity_id,title,start_time,signed,status,first_published_time from kg_activity where store_id in (:stores) and del_flag='0' and status in ('open','paused','closed','ended') and first_published_time>=:week and first_published_time<:to order by first_published_time,activity_id",ps);
        List<Map<String,Object>> issues=db.list("select case_id,case_no,category,status,create_time,closed_at from kg_mediation_case where store_id in (:stores) and create_time>=:from and create_time<:to order by create_time,case_id",ps);
        return map("counts",counts,"business","keeper".equals(role)?business(stores,date,start(date),cutoff):Collections.emptyList(),"workforce","manager".equals(role)?workforce(stores,start(date),cutoff,counts):Collections.emptyList(),"activities",activities,"weekActivityCount",activities.size(),"weekActivityMissing",Math.max(0,3-activities.size()),"issues",issues,"dataSourceNote","统计窗口为前一天18:00至当天18:00（不含结束时点）；自动数据以系统已记录的业务为准，未记录的数据不会推算。");
    }
}
