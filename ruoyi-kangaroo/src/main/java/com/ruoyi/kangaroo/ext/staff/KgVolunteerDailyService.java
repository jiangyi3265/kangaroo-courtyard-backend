package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 城市换工协作看板。城市只从当前有效授权门店推导；不扩大其他业务的编辑权限。 */
@Service
public class KgVolunteerDailyService {
    private static final Logger LOG = LoggerFactory.getLogger(KgVolunteerDailyService.class);
    static final String ACTIVE = "'store_confirmed','contract_signed','onboarded'";
    static final String ROUTE = "/pages/staff/volunteers";
    @Autowired private KgJdbc db;
    @Autowired private StaffSupport support;
    @Autowired private KgCityService cities;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    /** 按城市授权的换工主理人、小院主理人；单测没注入时不算 */
    @Autowired(required=false) private CityGrantScope cityGrants;

    static String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    static long num(Object v) { return v instanceof Number ? ((Number)v).longValue() : 0; }
    static LocalDate date(Object v) {
        String s = str(v);
        try { return s.length() < 10 ? null : LocalDate.parse(s.substring(0,10)); }
        catch (RuntimeException e) { return null; }
    }
    static LocalDate end(Object start, Object days) {
        LocalDate d = date(start); long n = num(days);
        return d == null || n < 1 || n > 3650 ? null : d.plusDays(n-1);
    }
    LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    /** 切换前城市负责人只从 /app/staff/city/overview 进来，名单上不能操作；切换后他有岗位权限，manageable 里算他的门店 */
    static boolean eligible(String role) { return Arrays.asList("manager","keeper","staff","city_lead").contains(role); }
    String city(Object raw) { return cities.jobCity(str(raw)); }

    public Map<String,Object> overview(Long storeId) {
        StaffSupport.Ctx ctx = support.ctx(storeId);
        if (!eligible(ctx.role)) throw new ServiceException("当前身份不能查看城市换工概况",403);
        Map<String,Object> store = db.one("select city from kg_store where store_id=:id and del_flag='0' and status='0'",p("id",ctx.storeId));
        if (store == null) throw new ServiceException("当前授权门店已停用",403);
        String town = city(store.get("city"));
        if (town.isEmpty() || Arrays.asList("全国","不限").contains(town)) throw new ServiceException("请先在后台为当前门店设置所在城市",422);
        Set<Long> manageable = new HashSet<Long>();
        for (StaffGrantView g : ctx.me.getStaffGrants()) if (Boolean.TRUE.equals(g.getActive()) && StaffPerms.allows(g.getRoleCode(),"jobs",StaffPerms.VIEW)) manageable.add(g.getStoreId());
        LocalDate day = today();
        List<Map<String,Object>> shops = new ArrayList<Map<String,Object>>();
        Map<Long,Map<String,Object>> grouped = new LinkedHashMap<Long,Map<String,Object>>();
        List<Map<String,Object>> jobs = jobs(town);
        List<Long> ids = new ArrayList<Long>();
        Map<Long,Map<String,Object>> byJob = new HashMap<Long,Map<String,Object>>();
        for (Map<String,Object> j : jobs) {
            Long id = KgJdbc.asLong(j.get("jobId")); ids.add(id); byJob.put(id,j);
            Long shopId = KgJdbc.asLong(j.get("shopId"));
            Map<String,Object> shop = grouped.get(shopId);
            if (shop == null) {
                shop = new LinkedHashMap<String,Object>(); shop.put("storeId",shopId); shop.put("name",j.get("shopName"));
                shop.put("shortfall",0L); shop.put("jobs",new ArrayList<Map<String,Object>>());
                shop.put("arrivals",new ArrayList<Map<String,Object>>()); shop.put("departures",new ArrayList<Map<String,Object>>());
                grouped.put(shopId,shop);
            }
            long gap = "published".equals(j.get("status")) ? Math.max(0,num(j.get("headcount"))-num(j.get("occupied"))) : 0;
            if (gap > 0) { shop.put("shortfall",num(shop.get("shortfall"))+gap); list(shop,"jobs").add(p("jobId",id,"title",j.get("title"),"shortfall",gap)); }
        }
        long arriving=0,leaving=0,reminding=0,missing=0;
        if (!ids.isEmpty()) for (Map<String,Object> a : db.list("select a.app_id,a.job_id,a.store_id,a.real_name,a.status,a.arrival_date,a.stay_days,coalesce(r.arrive_date,a.arrival_date) expected_arrival "
                + "from kg_application a left join kg_app_arrival r on r.app_id=a.app_id where a.del_flag='0' and a.job_id in (:ids) and a.status in ("+ACTIVE+") order by a.arrival_date,a.app_id",p("ids",ids))) {
            Map<String,Object> j = byJob.get(KgJdbc.asLong(a.get("jobId")));
            Map<String,Object> shop = grouped.get(KgJdbc.asLong(j.get("shopId")));
            LocalDate finish = end(a.get("arrivalDate"),a.get("stayDays"));
            boolean manage = manageable.contains(KgJdbc.asLong(a.get("storeId")));
            Map<String,Object> person = p("appId",a.get("appId"),"publisherStoreId",a.get("storeId"),"name",str(a.get("realName")).isEmpty()?"换工":a.get("realName"),
                    "jobTitle",j.get("title"),"arrivalDate",a.get("expectedArrival"),"endDate",finish==null?null:finish.toString(),"status",a.get("status"),"canManage",manage);
            if (day.equals(date(a.get("expectedArrival")))) { list(shop,"arrivals").add(person); arriving++; }
            if (finish==null) missing++;
            else if (!finish.isBefore(day) && !finish.isAfter(day.plusDays(7))) {
                long left=ChronoUnit.DAYS.between(day,finish); person.put("daysLeft",left); person.put("needsContact",left<=5);
                list(shop,"departures").add(person); leaving++; if(left<=5) reminding++;
            }
        }
        long gap=0;
        for(Map<String,Object> shop:grouped.values()) {
            list(shop,"departures").sort(Comparator.comparing(a->str(a.get("endDate"))));
            if(num(shop.get("shortfall"))>0 || !list(shop,"arrivals").isEmpty() || !list(shop,"departures").isEmpty()) shops.add(shop);
            gap+=num(shop.get("shortfall"));
        }
        return p("city",town,"storeId",ctx.storeId,"date",day.toString(),"scope","本城市全部合作门店", "shops",shops,
                "shortfall",gap,"arriving",arriving,"leaving",leaving,"reminding",reminding,"missingTerm",missing,
                "note","缺口为招聘中的剩余名额；到达以最新到达登记为准。离开按报名到岗日＋合约天数（含首日）计算，包含今天至未来7天。提前5天、3天发送员工端消息，请及时沟通续约或正常离开。");
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Map<String,Object> row,String key) { return (List<Map<String,Object>>)row.get(key); }

    /** 先读岗位公开维度确定城市，再限岗位ID读取人员；招聘方资料不串到发布方。 */
    List<Map<String,Object>> jobs(String town) {
        List<Map<String,Object>> out = new ArrayList<Map<String,Object>>();
        for(Map<String,Object> j:db.list("select j.job_id,j.store_id,j.title,j.status,j.headcount,coalesce(nullif(j.city,''),s.city) city, "
                + "coalesce(j.recruit_store_id,j.store_id) shop_id,s.store_name shop_name,s.cover shop_cover, "
                + "(select count(*) from kg_application a where a.job_id=j.job_id and a.del_flag='0' and a.status in ("+ACTIVE+")) occupied "
                + "from kg_job j join kg_store s on s.store_id=coalesce(j.recruit_store_id,j.store_id) join kg_store p on p.store_id=j.store_id "
                + "where j.del_flag='0' and j.job_type='volunteer' and j.status<>'draft' and s.del_flag='0' and s.status='0' and p.del_flag='0' and p.status='0' order by s.store_name,j.job_id",p())) {
            if(town.equals(city(j.get("city")))) out.add(j);
        }
        return out;
    }

    /**
     * 招募周报：本城每家换工店家这一周的人数（mon 是周一）。招募按 store_confirm_time、到岗按 onboard_time、报名按 create_time，录用后又终止的也算录用过；
     * 缺口是现在的剩余名额；一周内离开是合约在这一周结束、现在还在岗的。没有金额（金额按查看人的权限另算）。
     */
    public List<Map<String,Object>> weekShops(String town, LocalDate mon) {
        Map<Long,Map<String,Object>> grouped = new LinkedHashMap<Long,Map<String,Object>>();
        Map<Long,Map<String,Object>> byJob = new HashMap<Long,Map<String,Object>>();
        for(Map<String,Object> j:jobs(town)) {
            Long shopId = KgJdbc.asLong(j.get("shopId"));
            Map<String,Object> shop = grouped.get(shopId);
            if(shop==null) {
                shop = new LinkedHashMap<String,Object>();
                shop.put("storeId",shopId); shop.put("name",j.get("shopName")); shop.put("cover",j.get("shopCover"));
                for(String f:WEEK_COUNTS) shop.put(f,0L);
                shop.put("shortfall",0L); shop.put("leaving",0L);
                grouped.put(shopId,shop);
            }
            byJob.put(KgJdbc.asLong(j.get("jobId")),shop);
            if("published".equals(j.get("status"))) add(shop,"shortfall",Math.max(0,num(j.get("headcount"))-num(j.get("occupied"))));
        }
        if(byJob.isEmpty()) return new ArrayList<Map<String,Object>>();
        Map<String,Object> params = p("ids",new ArrayList<Long>(byJob.keySet()),"lf",mon.minusDays(7).toString(),"f",mon.toString(),"t",mon.plusDays(7).toString());
        for(Map<String,Object> a:db.list("select a.job_id, coalesce(sum(a.store_confirm_time>=:f and a.store_confirm_time<:t),0) recruited, "
                + "coalesce(sum(a.onboard_time>=:f and a.onboard_time<:t),0) onboarded, coalesce(sum(a.store_confirm_time>=:lf and a.store_confirm_time<:f),0) recruited_last, "
                + "coalesce(sum(a.onboard_time>=:lf and a.onboard_time<:f),0) onboarded_last, coalesce(sum(a.create_time>=:f and a.create_time<:t),0) applied, "
                + "coalesce(sum(a.create_time>=:lf and a.create_time<:f),0) applied_last from kg_application a where a.del_flag='0' and a.job_id in (:ids) group by a.job_id",params)) {
            Map<String,Object> shop = byJob.get(KgJdbc.asLong(a.get("jobId")));
            for(String f:WEEK_COUNTS) add(shop,f,num(a.get(f)));
        }
        for(Map<String,Object> a:db.list("select a.job_id, count(*) n from kg_application a where a.del_flag='0' and a.job_id in (:ids) and a.status in ("+ACTIVE+") "
                + "and a.stay_days between 1 and 3650 and date_add(a.arrival_date,interval a.stay_days-1 day)>=:f and date_add(a.arrival_date,interval a.stay_days-1 day)<:t group by a.job_id",params)) {
            add(byJob.get(KgJdbc.asLong(a.get("jobId"))),"leaving",num(a.get("n")));
        }
        return new ArrayList<Map<String,Object>>(grouped.values());
    }
    private static final List<String> WEEK_COUNTS = Arrays.asList("recruited","onboarded","recruitedLast","onboardedLast","applied","appliedLast");
    private static void add(Map<String,Object> row,String key,long v) { row.put(key,num(row.get(key))+v); }

    /** 每小时补查；单次提醒与幂等键在同一事务，重启/多实例不会重复。 */
    @Scheduled(initialDelayString="${kangaroo.volunteer.remind-initial-ms:60000}",fixedDelayString="${kangaroo.volunteer.remind-delay-ms:3600000}")
    public void remind() {
        try {
            LocalDate day=today();
            List<Map<String,Object>> apps=db.list("select a.app_id from kg_application a join kg_job j on j.job_id=a.job_id where a.del_flag='0' and j.del_flag='0' and j.job_type='volunteer' "
                    + "and a.status in ("+ACTIVE+") and a.stay_days between 1 and 3650 and date_add(a.arrival_date,interval a.stay_days-1 day) between :d and :e",p("d",day.toString(),"e",day.plusDays(5).toString()));
            TransactionTemplate tx=new TransactionTemplate(transactions);
            for(Map<String,Object> a:apps) try { tx.execute(s->{remindOne(KgJdbc.asLong(a.get("appId")),day); return null;}); }
            catch(RuntimeException e) { LOG.warn("换工到期提醒失败 appId={}",a.get("appId"),e); }
        } catch(RuntimeException e) { LOG.warn("读取换工到期提醒失败",e); }
    }
    void remindOne(Long id,LocalDate day) {
        Map<String,Object> a=db.one("select a.*,coalesce(nullif(j.city,''),s.city) city,s.store_name shop_name from kg_application a join kg_job j on j.job_id=a.job_id "
                + "join kg_store s on s.store_id=coalesce(j.recruit_store_id,j.store_id) join kg_store p on p.store_id=j.store_id "
                + "where a.app_id=:id and a.del_flag='0' and a.status in ("+ACTIVE+") and j.del_flag='0' and j.job_type='volunteer' and s.del_flag='0' and s.status='0' and p.del_flag='0' and p.status='0' for update",p("id",id));
        if(a==null)return;
        LocalDate finish=end(a.get("arrivalDate"),a.get("stayDays"));
        if(finish==null)return;
        long left=ChronoUnit.DAYS.between(day,finish);
        if(left<0 || left>5)return;
        // 漏跑时补最近一档，不在恢复服务时连发两条。
        int stage=left>3?5:3;
        String town=city(a.get("city")); Set<Long> sent=new HashSet<Long>();
        List<Map<String,Object>> people=db.list("select g.app_user_id,g.store_id,s.city from kg_staff_grant g join kg_store s on s.store_id=g.store_id join kg_app_user u on u.user_id=g.app_user_id "
                + "where g.status='0' and g.del_flag='0' and g.role_code in ('manager','keeper','staff') and s.status='0' and s.del_flag='0' and u.status='0' and u.del_flag='0' order by g.store_id",p());
        // 本城按城市授权的换工主理人、小院主理人也提醒（点开到这份报名的门店）
        Long own=KgJdbc.asLong(a.get("storeId"));
        if(cityGrants!=null && own!=null) {
            people=new ArrayList<Map<String,Object>>(people);
            for(Long uid:cityGrants.holderIds(own,Arrays.asList("manager","keeper","staff"))) people.add(p("appUserId",uid,"storeId",own,"city",a.get("city")));
        }
        for(Map<String,Object> g:people) {
            Long uid=KgJdbc.asLong(g.get("appUserId"));
            if(!town.equals(city(g.get("city"))) || !sent.add(uid))continue;
            String key="volunteer-expiry:"+id+":"+finish+":"+stage+":"+uid;
            if(!db.claimIdem(key,"volunteer_expiry"))continue;
            String content=str(a.get("realName"))+"在「"+str(a.get("shopName"))+"」的合约于 "+finish+" 结束（"+(left==0?"今天":"还有"+left+"天")+"）。请提前沟通续约或正常离开。";
            support.notifyStaff(uid,MessageCategory.SYSTEM,"换工合约即将到期",content,"volunteer_expiry",id,ROUTE+"?storeId="+g.get("storeId"));
        }
    }
}
