package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 关注门店（「我的关注」）。
 *
 * 一条关注就是 kg_job_subscribe 里只带 store_id、城市 / 岗位类型 / 关键词都为空的一行
 *（写入与取消见 KgUserHubService.followStore；岗位页「这家店缺人了提醒我」写的也是这种行）。
 * 这家门店上架新岗位时，由 KgUserHubService 的新机会推送按 store_id 匹配并发站内信，文案见 {@link #notice}。
 * 只作用在用户自己的账号上：不通知门店，也不对外展示谁关注了谁。
 */
@Service
public class KgStoreFollowService
{
    /** 关注门店最多 50 家；按城市 / 类型 / 关键词的新机会订阅另算（最多 10 条），两者互不占名额 */
    public static final int FOLLOW_MAX = 50;

    /** 「我的关注」页 / 通知深链：某家门店的在招岗位 */
    public static final String STORE_JOBS_ROUTE = "/pages/profile/follows?storeId=";

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgHomeService home;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    /**
     * 「是一条关注」的 SQL 条件。alias 为表别名前缀（如 "s."），不用别名时传 ""。
     * 与 followStore 判断「已关注」、saveSubscription 判重是同一口径：只看 store_id，城市 / 类型 / 关键词全空。
     */
    public static String followRow(String alias)
    {
        String a = alias == null ? "" : alias;
        return a + "store_id is not null and coalesce(" + a + "cities, '') = '' and coalesce(" + a + "job_types, '') = '' and coalesce(" + a + "keyword, '') = ''";
    }

    /** 一行订阅是不是关注门店（推送时据此换成「你关注的门店上新了」的文案） */
    public static boolean isFollowRow(Map<String, Object> sub)
    {
        return sub != null && sub.get("storeId") != null && str(sub.get("cities")).trim().isEmpty()
                && str(sub.get("jobTypes")).trim().isEmpty() && str(sub.get("keyword")).trim().isEmpty();
    }

    /**
     * 关注门店上新的站内信：[标题, 正文, 落地页]。
     * 只有 1 个新岗位时直接进岗位详情；多个时进这家店的在招岗位（之前跳的是不带门店条件的筛选页，等于全部岗位）。
     */
    public static String[] notice(Long storeId, String storeName, List<Map<String, Object>> jobs)
    {
        String name = storeName == null || storeName.trim().isEmpty() ? "门店" : storeName.trim();
        String first = jobs.isEmpty() ? "" : str(jobs.get(0).get("title"));
        String content = jobs.size() <= 1 ? "你关注的「" + name + "」发布了新岗位：" + first
                : "你关注的「" + name + "」发布了 " + jobs.size() + " 个新岗位：" + first + " 等";
        String route = jobs.size() == 1 ? "/pages/jobs/detail?jobId=" + jobs.get(0).get("jobId") : STORE_JOBS_ROUTE + storeId;
        return new String[] { "你关注的门店有新岗位", content, route };
    }

    static boolean active(Map<String, Object> store)
    {
        return store != null && "0".equals(str(store.get("status"))) && "0".equals(str(store.get("delFlag")));
    }

    /** 我关注的门店：门店名、城市、在招岗位数（其中线上任务数）、关注时间；最近关注的在前 */
    public Map<String, Object> follows(Long me)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select s.sub_id, s.store_id, s.freq, s.create_time, st.store_name, st.city, st.cover, st.status, st.del_flag, "
                + "(select count(*) from kg_job j where j.store_id = s.store_id and j.del_flag = '0' and j.status = 'published') open_jobs, "
                + "(select count(*) from kg_job j where j.store_id = s.store_id and j.del_flag = '0' and j.status = 'published' and j.job_type = 'online') online_jobs "
                + "from kg_job_subscribe s left join kg_store st on st.store_id = s.store_id where s.app_user_id = :u and " + followRow("s.")
                + " order by s.sub_id desc limit " + FOLLOW_MAX, p("u", me)))
        {
            boolean open = active(r);
            long jobs = open ? num(r.get("openJobs")) : 0L;
            long online = open ? num(r.get("onlineJobs")) : 0L;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("storeId", r.get("storeId"));
            m.put("storeName", str(r.get("storeName")).isEmpty() ? "门店已下线" : r.get("storeName"));
            m.put("city", r.get("city"));
            m.put("cover", r.get("cover"));
            m.put("active", open);
            m.put("openJobs", jobs);
            m.put("onlineJobs", online);
            m.put("jobsText", !open ? "门店暂停营业，暂时看不到岗位" : jobs == 0 ? "暂时没有在招岗位" : "在招 " + jobs + " 个岗位" + (online > 0 ? "（含线上任务 " + online + " 个）" : ""));
            m.put("followTime", str(r.get("createTime")).length() >= 10 ? str(r.get("createTime")).substring(0, 10) : "");
            m.put("freqText", "daily".equals(str(r.get("freq"))) ? "每天 9:00 汇总通知" : "有新岗位马上通知");
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("total", rows.size());
        out.put("max", FOLLOW_MAX);
        out.put("note", "关注的门店发布新岗位时，会在消息中心通知你；不想收到可以在「我的 → 设置 → 通知管理」里关掉新机会提醒。");
        return out;
    }

    /**
     * 某家门店的在招岗位（「我的关注」点进一家店、关注上新的通知深链）。游客也能看；登录时带上是否已关注。
     * 门店停用 / 删除后不再列出它的岗位（与岗位列表同一口径）；这时只有已登录、而且正关注着这家店的人
     * 还能看到门店名（方便取消关注），其他人一律提示「门店不存在」。
     */
    public Map<String, Object> storeJobs(Long me, Long storeId)
    {
        Map<String, Object> st = storeId == null ? null : db.one("select store_id, store_name, city, cover, intro, safe_cert, rating, review_count, status, del_flag "
                + "from kg_store where store_id = :s", p("s", storeId));
        if (st == null)
        {
            throw new ServiceException("门店不存在", 404);
        }
        boolean open = active(st);
        boolean following = following(me, storeId);
        // 之前门店停用 / 删除后谁来问都照样返回门店名（游客也行），挨个试门店编号就能拿到下线门店的名字
        if (!open && !following)
        {
            throw new ServiceException("门店不存在", 404);
        }
        Map<String, Object> store = new LinkedHashMap<String, Object>();
        store.put("storeId", st.get("storeId"));
        store.put("storeName", st.get("storeName"));
        store.put("city", open ? st.get("city") : null);
        store.put("cover", open ? st.get("cover") : null);
        store.put("intro", open ? st.get("intro") : null);
        store.put("safe", open && "0".equals(str(st.get("safeCert"))));  // 字典 safe_cert：0 已认证
        store.put("rating", open ? st.get("rating") : null);
        store.put("reviewCount", open ? num(st.get("reviewCount")) : 0L);
        store.put("active", open);
        List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
        if (open)
        {
            for (Map<String, Object> j : db.list("select j.*, s.store_name, s.safe_cert from kg_job j join kg_store s on s.store_id = j.store_id "
                    + "where j.store_id = :s and j.del_flag = '0' and j.status = 'published' and s.status = '0' and s.del_flag = '0' "
                    + "order by " + KgJobDisplayOrder.sql() + " limit 50", p("s", storeId)))
            {
                jobs.add(home.jobCard(j));
            }
            home.markFavorites(me, jobs);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("store", store);
        out.put("following", following);
        out.put("jobs", jobs);
        out.put("total", jobs.size());
        return out;
    }

    /** 是否已关注这家门店（只认关注行；按城市 / 类型订阅里顺带写了门店的不算，否则取消关注后按钮仍显示已关注） */
    public boolean following(Long me, Long storeId)
    {
        return me != null && storeId != null
                && db.count("select count(*) from kg_job_subscribe where app_user_id = :u and store_id = :s and status = '0' and " + followRow(""), p("u", me, "s", storeId)) > 0;
    }
}
