package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 线上任务招募概况（2026-10-30，Luna 备忘录）：本城线上任务招募得怎么样，只读、只有数字，不出现任何报名人的信息。
 * 给商家服务人和兼任的小院主理人看，身份在控制器里由 KgMerchantRequestService.serviceCtx 把关。
 *
 * 口径：
 *   城市 —— 按招募门店的 kg_store.city 归一后比。线上任务自己的 kg_job.city 固定是「不限」，不能用（KgOnlineTaskService.create）；
 *   在做 —— 报名状态在 KgOnlineTaskService.WORKING 里的人数。kg_job.hired_count 对线上任务永远是 0，不能用；
 *   招募缺口 —— 招募中的任务 名额 − 在做（不小于 0），和报名时的名额检查同一个算法；
 *   待审核 / 待验收 —— 已关闭的任务上也可能还挂着，一样算进来（门店还要处理）。
 */
@Service
public class KgOnlineDailyService
{
    private static final String PUBLISHED = "published";

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgMerchantRequestService requests;

    private static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    private static String statusLabel(String status)
    {
        return PUBLISHED.equals(status) ? "招募中" : ("paused".equals(status) ? "已暂停" : ("closed".equals(status) ? "已关闭" : status));
    }

    /** c 是 serviceCtx 返回的：城市取这家店的，门店没设城市时 townOf 直接 422 */
    public Map<String, Object> overview(StaffSupport.Ctx c)
    {
        String town = requests.townOf(c);
        long recruiting = 0;
        long shortfall = 0;
        long toReview = 0;
        long toAccept = 0;
        List<Map<String, Object>> tasks = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> j : jobs())
        {
            if (!town.equals(requests.normalCity(j.get("city"))))
            {
                continue;
            }
            String status = StaffSupport.str(j.get("status"));
            boolean open = PUBLISHED.equals(status);
            long working = num(j.get("working"));
            long gap = open ? Math.max(0L, num(j.get("headcount")) - working) : 0L;
            long review = num(j.get("toReview"));
            long accept = num(j.get("toAccept"));
            recruiting += open ? 1 : 0;
            shortfall += gap;
            toReview += review;
            toAccept += accept;
            // 已结束又没有待办的老任务不用看
            if (open || review > 0 || accept > 0)
            {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("jobId", j.get("jobId"));
                m.put("title", j.get("title"));
                m.put("storeId", j.get("storeId"));
                m.put("storeName", j.get("storeName"));
                m.put("status", status);
                m.put("statusLabel", statusLabel(status));
                m.put("headcount", num(j.get("headcount")));
                m.put("working", working);
                m.put("shortfall", gap);
                m.put("toReview", review);
                m.put("toAccept", accept);
                tasks.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("city", town);
        out.put("storeId", c.storeId);
        out.put("date", LocalDate.now().toString());
        out.put("recruiting", recruiting);
        out.put("shortfall", shortfall);
        out.put("toReview", toReview);
        out.put("toAccept", toAccept);
        out.put("tasks", tasks);
        out.put("note", "招募缺口为招募中任务的名额减去正在做的人数；待审核报名、待验收成果包含已关闭但还没处理完的任务。");
        return out;
    }

    /** 全部已提交过（不是草稿）的线上任务；城市在 Java 里归一后筛（kg_store.city 是自由文本，同 KgVolunteerDailyService.jobs） */
    private List<Map<String, Object>> jobs()
    {
        String working = "'" + String.join("','", KgOnlineTaskService.WORKING) + "'";
        return db.list("select j.job_id, j.store_id, j.title, j.status, j.headcount, s.city, s.store_name, "
                + "(select count(*) from kg_application a where a.job_id = j.job_id and a.del_flag = '0' and a.status in (" + working + ")) working, "
                + "(select count(*) from kg_application a where a.job_id = j.job_id and a.del_flag = '0' and a.status = 'submitted') to_review, "
                + "(select count(*) from kg_online_submission x join kg_application a on a.app_id = x.app_id where a.job_id = j.job_id and a.del_flag = '0' and x.status = 'submitted') to_accept "
                + "from kg_job j join kg_store s on s.store_id = coalesce(j.recruit_store_id, j.store_id) join kg_store p on p.store_id = j.store_id "
                + "where j.del_flag = '0' and j.job_type = 'online' and j.status <> 'draft' and s.del_flag = '0' and s.status = '0' and p.del_flag = '0' and p.status = '0' "
                + "order by s.store_name, j.job_id", p());
    }
}
