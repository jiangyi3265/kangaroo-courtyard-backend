package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgSkills;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 人才库（员工端）：人才池 = 曾向本店报名的用户（他们报名时已把简历交给本店），不含本店标记为「不感兴趣」的人；
 * 姓名脱敏、年龄只给区间、城市只到城市；看档案会留下访问记录；「申请联系」= 给对方发本店在招岗位的报名邀请，
 * 对方报名前门店看不到联系方式。技能标签由平台技能词表归类，不是 AI。
 */
@Service
public class KgTalentService
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private com.ruoyi.kangaroo.ext.user.KgResumeProfile resumeProfile;

    @Autowired
    private StaffSupport support;

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

    static String maskName(String n)
    {
        if (n == null || n.isEmpty())
        {
            return "用户";
        }
        return n.length() == 1 ? n + "*" : n.charAt(0) + "*" + (n.length() > 2 ? String.valueOf(n.charAt(n.length() - 1)) : "");
    }

    static String ageRange(Object age)
    {
        long a = num(age);
        if (a <= 0)
        {
            return "年龄未填";
        }
        long lo = a / 5 * 5;
        return lo + "–" + (lo + 4) + " 岁";
    }

    /** 人才池的原始数据：每人一行（简历 + 本店最近一次报名 + 平台履约次数） */
    private List<Map<String, Object>> pool(Long storeId)
    {
        return pool(storeId, false);
    }

    private List<Map<String, Object>> pool(Long storeId, boolean hidden)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select u.user_id, u.nickname, u.city user_city, u.gender user_gender, r.name, r.gender, r.age, r.city, r.skill_text, r.extra_text, "
                + "(select max(a2.arrival_date) from kg_application a2 where a2.app_user_id = u.user_id and a2.store_id = :s and a2.del_flag = '0') arrival, "
                + "(select count(*) from kg_application a3 where a3.app_user_id = u.user_id and a3.del_flag = '0' and a3.status = 'onboarded') fulfilled, "
                + "coalesce(m.favorite, '0') favorite, m.store_tags "
                + "from kg_app_user u left join kg_resume r on r.app_user_id = u.user_id left join kg_talent_mark m on m.store_id = :s and m.app_user_id = u.user_id "
                + "where u.del_flag = '0' and u.user_id in (select a.app_user_id from kg_application a where a.store_id = :s and a.del_flag = '0' and a.status <> 'withdrawn') "
                + "and coalesce(m.hidden, '0') = :hidden order by u.user_id desc limit 500", p("s", storeId, "hidden", hidden ? "1" : "0")))
        {
            List<String> tags = KgSkills.tagsOf(str(r.get("skillText")) + " " + str(r.get("extraText")));
            // 门店在报名详情里加的标签（V35）也算这个人的标签，人才库按标签能筛到
            for (String t : splitTags(r.get("storeTags")))
            {
                if (!tags.contains(t))
                {
                    tags.add(t);
                }
            }
            r.put("tags", tags);
            List<String> kinds = new ArrayList<String>();
            for (Map<String, Object> e : db.list("select kind, title from kg_resume_exp where app_user_id = :u and del_flag = '0' order by coalesce(start_date, create_time) desc",
                    p("u", r.get("userId"))))
            {
                kinds.add(str(e.get("kind")));
            }
            r.put("kinds", kinds);
            out.add(r);
        }
        return out;
    }

    public Map<String, Object> tags(StaffSupport.Ctx c)
    {
        List<Map<String, Object>> pool = pool(c.storeId);
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> r : pool)
        {
            for (Object t : (List<?>) r.get("tags"))
            {
                counts.merge(str(t), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Integer> e : counts.entrySet())
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", e.getKey());
            m.put("count", e.getValue());
            m.put("icon", KgSkills.iconOf(e.getKey()));
            list.add(m);
        }
        list.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));
        int max = list.isEmpty() ? 1 : (Integer) list.get(0).get("count");
        for (Map<String, Object> m : list)
        {
            int n = (Integer) m.get("count");
            m.put("size", n * 3 >= max * 2 ? 3 : (n * 3 >= max ? 2 : 1));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("total", pool.size());
        m.put("tags", list);
        m.put("allTags", KgSkills.allTags());
        List<String> cities = new ArrayList<String>();
        Map<String, Integer> cc = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> r : pool)
        {
            String city = str(r.get("city")).isEmpty() ? str(r.get("userCity")) : str(r.get("city"));
            if (!city.isEmpty())
            {
                cc.merge(city, 1, Integer::sum);
            }
        }
        cc.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6).forEach(e -> cities.add(e.getKey()));
        m.put("cities", cities);
        return m;
    }

    @SuppressWarnings("unchecked")
    private boolean match(Map<String, Object> r, Map<String, Object> f)
    {
        List<String> tags = f.get("tags") instanceof List ? (List<String>) f.get("tags") : new ArrayList<String>();
        List<String> have = (List<String>) r.get("tags");
        if (!tags.isEmpty())
        {
            boolean all = "and".equals(str(f.get("logic")));
            boolean ok = all;
            for (String t : tags)
            {
                ok = all ? ok && have.contains(t) : ok || have.contains(t);
            }
            if (!ok)
            {
                return false;
            }
        }
        List<String> cities = f.get("cities") instanceof List ? (List<String>) f.get("cities") : new ArrayList<String>();
        String city = str(r.get("city")).isEmpty() ? str(r.get("userCity")) : str(r.get("city"));
        if (!cities.isEmpty() && !cities.contains(city))
        {
            return false;
        }
        String from = str(f.get("from"));
        String to = str(f.get("to"));
        String arrival = r.get("arrival") == null ? "" : str(r.get("arrival")).substring(0, 10);
        if ((!from.isEmpty() || !to.isEmpty()) && (arrival.isEmpty() || (!from.isEmpty() && arrival.compareTo(from) < 0) || (!to.isEmpty() && arrival.compareTo(to) > 0)))
        {
            return false;
        }
        List<String> kinds = f.get("kinds") instanceof List ? (List<String>) f.get("kinds") : new ArrayList<String>();
        if (!kinds.isEmpty())
        {
            boolean any = false;
            for (String k : kinds)
            {
                any = any || ((List<String>) r.get("kinds")).contains(k);
            }
            if (!any)
            {
                return false;
            }
        }
        long minFulfilled = num(f.get("fulfilled"));
        return num(r.get("fulfilled")) >= minFulfilled;
    }

    private List<Map<String, Object>> storeJobs(Long storeId)
    {
        List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> j : db.list("select job_id, title, tags, content, requirement from kg_job where store_id = :s and status = 'published' and del_flag = '0'", p("s", storeId)))
        {
            j.put("tagList", KgSkills.tagsOf(str(j.get("title")) + " " + str(j.get("tags")) + " " + str(j.get("content")) + " " + str(j.get("requirement"))));
            jobs.add(j);
        }
        return jobs;
    }

    public Map<String, Object> search(StaffSupport.Ctx c, Map<String, Object> f, boolean countOnly)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> jobs = countOnly ? new ArrayList<Map<String, Object>>() : storeJobs(c.storeId);
        int total = 0;
        for (Map<String, Object> r : pool(c.storeId, Boolean.TRUE.equals(f.get("hidden"))))
        {
            if (!match(r, f))
            {
                continue;
            }
            total++;
            if (countOnly)
            {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) r.get("tags");
            int matches = 0;
            for (Map<String, Object> j : jobs)
            {
                for (Object t : (List<?>) j.get("tagList"))
                {
                    if (tags.contains(str(t)))
                    {
                        matches++;
                        break;
                    }
                }
            }
            List<String> roles = new ArrayList<String>();
            for (Map<String, Object> e : db.list("select title from kg_resume_exp where app_user_id = :u and del_flag = '0' order by coalesce(start_date, create_time) desc limit 2",
                    p("u", r.get("userId"))))
            {
                roles.add(maskContacts(str(e.get("title"))));
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("userId", r.get("userId"));
            m.put("name", maskName(str(r.get("name")).isEmpty() ? str(r.get("nickname")) : str(r.get("name"))));
            String city = str(r.get("city")).isEmpty() ? str(r.get("userCity")) : str(r.get("city"));
            m.put("city", city.isEmpty() ? "城市未填" : city);
            m.put("role", roles.isEmpty() ? "暂无经历记录" : String.join(" / ", roles));
            m.put("tags", tags.subList(0, Math.min(3, tags.size())));
            m.put("exp", ((List<?>) r.get("kinds")).size());
            m.put("date", r.get("arrival") == null ? "未定" : str(r.get("arrival")).substring(5, 10));
            m.put("matches", matches);
            m.put("fulfilled", r.get("fulfilled"));
            m.put("favorite", "1".equals(str(r.get("favorite"))));
            rows.add(m);
        }
        String sort = str(f.get("sort"));
        if ("date".equals(sort))
        {
            rows.sort((a, b) -> str(a.get("date")).compareTo(str(b.get("date"))));
        }
        else if ("exp".equals(sort))
        {
            rows.sort((a, b) -> Long.compare(num(b.get("exp")), num(a.get("exp"))));
        }
        else
        {
            rows.sort((a, b) -> Long.compare(num(b.get("matches")), num(a.get("matches"))));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("total", total);
        m.put("rows", rows);
        return m;
    }

    /** 人才档案：只对人才池里的人开放，每次查看留痕 */
    public Map<String, Object> profile(StaffSupport.Ctx c, Long userId)
    {
        Map<String, Object> r = null;
        for (Map<String, Object> x : pool(c.storeId))
        {
            if (userId.equals(KgJdbc.asLong(x.get("userId"))))
            {
                r = x;
                break;
            }
        }
        if (r == null)
        {
            throw new ServiceException("只能查看曾向本店报名的人才档案", 403);
        }
        Map<String, Object> log = new LinkedHashMap<String, Object>();
        log.put("viewer_id", c.uid());
        log.put("target_id", userId);
        log.put("biz_type", "talent_profile");
        log.put("biz_id", c.storeId);
        log.put("field", "resume");
        log.put("create_time", new Date());
        db.insert("kg_pii_access", log);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("userId", userId);
        m.put("name", maskName(str(r.get("name")).isEmpty() ? str(r.get("nickname")) : str(r.get("name"))));
        long fulfilled = num(r.get("fulfilled"));
        m.put("level", fulfilled > 0 ? "◈ 平台履约 " + fulfilled + " 次" : "◈ 暂无平台履约");
        String gender = str(r.get("gender")).isEmpty() ? str(r.get("userGender")) : str(r.get("gender"));
        String city = str(r.get("city")).isEmpty() ? str(r.get("userCity")) : str(r.get("city"));
        m.put("basic", (gender.isEmpty() || "0".equals(gender) ? "性别未填" : ("1".equals(gender) || "男".equals(gender) ? "男" : ("2".equals(gender) || "女".equals(gender) ? "女" : gender)))
                + " · " + ageRange(r.get("age")) + " · " + (city.isEmpty() ? "城市未填" : city));
        m.put("date", r.get("arrival") == null ? "未定" : str(r.get("arrival")).substring(5, 10));
        String raw = (str(r.get("skillText")) + " " + str(r.get("extraText"))).trim();
        // 技能描述、经历里的岗位 / 地点 / 描述是候选人自己写的文字，里面的电话、微信号同样隐去（对方报名前门店看不到联系方式）
        m.put("rawSkills", raw.isEmpty() ? "（对方还没有填写技能描述）" : maskContacts(raw));
        List<String> tagTexts = new ArrayList<String>();
        for (Map<String, Object> t : KgSkills.profile(raw))
        {
            tagTexts.add(t.get("name") + "（命中 " + t.get("hits") + " 次）");
        }
        m.put("tags", tagTexts);
        // 档案下方四个页签（经历 / 评价 / 培训考核 / 档案动态）原先只有「经历」有数据、其余点了没反应，
        // 「评价（0）」是写死的。下面各取一份真实数据：每条都是 { kind, date, title, desc }，页面按页签切换同一条时间线。
        List<Map<String, Object>> exps = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> trainings = new ArrayList<Map<String, Object>>();
        int vol = 0;
        int part = 0;
        int full = 0;
        for (Map<String, Object> e : db.list("select * from kg_resume_exp where app_user_id = :u and del_flag = '0' order by coalesce(start_date, create_time) desc limit 20", p("u", userId)))
        {
            String k = str(e.get("kind"));
            // 未知类型与原先的展示口径一致按「全职」算，筛选「全职」时才对得上数
            String kind = "volunteer".equals(k) || "parttime".equals(k) || "training".equals(k) ? k : "fulltime";
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            String s = e.get("startDate") == null ? "" : str(e.get("startDate")).substring(0, 7).replace('-', '.');
            String en = e.get("endDate") == null ? "至今" : str(e.get("endDate")).substring(0, 7).replace('-', '.');
            x.put("kind", kind);
            x.put("date", s.isEmpty() ? "时间未填" : s + "-" + en);
            x.put("title", maskContacts(str(e.get("title"))) + "（" + ("volunteer".equals(kind) ? "换工" : ("parttime".equals(kind) ? "兼职" : ("training".equals(kind) ? "培训" : "全职"))) + "）"
                    + (str(e.get("place")).isEmpty() ? "" : "｜" + maskContacts(str(e.get("place")))));
            if ("training".equals(kind))
            {
                // 培训经历归到「培训考核」页签：原先它被算进「全职」，全部 / 换工 / 兼职 / 全职四个数加起来对不上
                String passed = str(e.get("passed"));
                String result = ("1".equals(passed) ? "考核通过" : ("0".equals(passed) ? "考核未通过" : "")) + (str(e.get("level")).isEmpty() ? "" : " · 等级 " + e.get("level"));
                x.put("desc", StaffSupport.cut(result.isEmpty() ? (str(e.get("content")).isEmpty() ? "（没有填写培训说明）" : maskContacts(str(e.get("content")))) : result, 60));
                trainings.add(x);
                continue;
            }
            vol += "volunteer".equals(kind) ? 1 : 0;
            part += "parttime".equals(kind) ? 1 : 0;
            full += "fulltime".equals(kind) ? 1 : 0;
            x.put("desc", StaffSupport.cut(str(e.get("content")).isEmpty() ? "（没有填写经历描述）" : maskContacts(str(e.get("content"))), 60));
            exps.add(x);
        }
        m.put("experiences", exps);
        m.put("expCounts", Arrays.asList(exps.size(), vol, part, full));
        // 培训考核：平台登记的有效技能证书在前，自己填写的培训经历在后
        List<Map<String, Object>> certs = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> e : db.list("select name, level, grade, score, issuer, issued_date from kg_cert where app_user_id = :u and status = '0' "
                + "order by issued_date desc limit 20", p("u", userId)))
        {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            String issued = StaffSupport.fmt(StaffSupport.parse(e.get("issuedDate")), "yyyy-MM-dd");
            x.put("kind", "cert");
            x.put("date", issued.isEmpty() ? "日期未填" : issued);
            x.put("title", str(e.get("name")) + (str(e.get("level")).isEmpty() ? "" : "（" + e.get("level") + "）"));
            x.put("desc", "平台认证证书 · " + str(e.get("issuer")) + (str(e.get("grade")).isEmpty() ? "" : " · 评定 " + e.get("grade"))
                    + (e.get("score") == null ? "" : " · " + e.get("score") + " 分"));
            certs.add(x);
        }
        certs.addAll(trainings);
        m.put("trainings", certs);
        m.put("certs", certs.size());
        // 评价：只取本店给 TA 的面试评分与评语（员工面试后打的星）。别的门店的内部评语不外流
        List<Map<String, Object>> reviews = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> e : db.list("select i.staff_rating, i.staff_note, i.start_time, j.title from kg_interview i "
                + "join kg_application a on a.app_id = i.app_id left join kg_job j on j.job_id = a.job_id "
                + "where a.app_user_id = :u and a.store_id = :s and a.del_flag = '0' and i.del_flag = '0' and i.staff_rating is not null "
                + "order by i.start_time desc limit 20", p("u", userId, "s", c.storeId)))
        {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            String day = StaffSupport.fmt(StaffSupport.parse(e.get("startTime")), "yyyy-MM-dd");
            x.put("kind", "review");
            x.put("date", day.isEmpty() ? "时间未填" : day);
            x.put("title", "本店面试评价 " + StaffSupport.toInt(e.get("staffRating"), 0) + " 星（" + (str(e.get("title")).isEmpty() ? "岗位已下线" : str(e.get("title"))) + "）");
            x.put("desc", StaffSupport.cut(str(e.get("staffNote")).isEmpty() ? "（没有填写评语）" : str(e.get("staffNote")), 60));
            reviews.add(x);
        }
        m.put("reviews", reviews);
        m.put("reviewCount", reviews.size());
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) r.get("tags");
        List<Map<String, Object>> recs = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> j : storeJobs(c.storeId))
        {
            List<?> jt = (List<?>) j.get("tagList");
            if (jt.isEmpty())
            {
                continue;
            }
            int hit = 0;
            for (Object t : jt)
            {
                hit += tags.contains(str(t)) ? 1 : 0;
            }
            if (hit == 0)
            {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("jobId", j.get("jobId"));
            x.put("name", j.get("title"));
            x.put("match", Math.round(hit * 100.0 / jt.size()));
            recs.add(x);
        }
        recs.sort((a, b) -> Long.compare(num(b.get("match")), num(a.get("match"))));
        m.put("recommends", recs.subList(0, Math.min(3, recs.size())));
        Map<String, Object> mark = db.one("select favorite from kg_talent_mark where store_id = :s and app_user_id = :u", p("s", c.storeId, "u", userId));
        m.put("favorite", mark != null && "1".equals(str(mark.get("favorite"))));
        List<Map<String, Object>> invites = db.list("select i.invite_id, i.create_time, j.title, (select count(*) from kg_application a where a.app_user_id = i.app_user_id and a.job_id = i.job_id "
                + "and a.del_flag = '0' and a.submit_time >= i.create_time) applied from kg_talent_invite i join kg_job j on j.job_id = i.job_id where i.store_id = :s "
                + "and i.app_user_id = :u order by i.invite_id desc", p("s", c.storeId, "u", userId));
        m.put("inviteText", invites.isEmpty() ? "申请联系" : (num(invites.get(0).get("applied")) > 0 ? "对方已报名「" + invites.get(0).get("title") + "」" : "已邀请 · 等待对方报名"));
        m.put("visibility", "曾报名本店（报名时已向本店提供简历）");
        // 档案动态：TA 与本店之间发生过的事（报名、本店邀请、本店员工查看档案），只看本店范围，按时间倒序
        List<Map<String, Object>> activity = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.submit_time, a.status, j.title from kg_application a left join kg_job j on j.job_id = a.job_id "
                + "where a.app_user_id = :u and a.store_id = :s and a.del_flag = '0' order by a.submit_time desc limit 10", p("u", userId, "s", c.storeId)))
        {
            event(activity, a.get("submitTime"), "报名「" + (str(a.get("title")).isEmpty() ? "岗位已下线" : str(a.get("title"))) + "」",
                    "当前状态：" + ApplicationStatus.label(str(a.get("status"))));
        }
        for (Map<String, Object> i : invites)
        {
            event(activity, i.get("createTime"), "本店邀请报名「" + str(i.get("title")) + "」", num(i.get("applied")) > 0 ? "对方已报名" : "等待对方报名");
        }
        Map<String, Object> views = db.one("select count(*) n, max(create_time) last_time from kg_pii_access where target_id = :u and biz_type = 'talent_profile' and biz_id = :s",
                p("u", userId, "s", c.storeId));
        if (views != null && num(views.get("n")) > 0)
        {
            event(activity, views.get("lastTime"), "本店员工查看档案 " + num(views.get("n")) + " 次", "这里显示最近一次查看的日期；每次查看都会留痕");
        }
        activity.sort((a, b) -> str(b.get("at")).compareTo(str(a.get("at"))));
        for (Map<String, Object> e : activity)
        {
            e.remove("at");
        }
        m.put("activity", activity);
        return m;
    }

    /** 档案动态的一条：at 是完整时间，只用来排序（排完就去掉），页面上只显示日期 */
    private static void event(List<Map<String, Object>> out, Object at, String title, String desc)
    {
        Date t = StaffSupport.parse(at);
        Map<String, Object> x = new LinkedHashMap<String, Object>();
        x.put("kind", "activity");
        x.put("date", t == null ? "时间未知" : StaffSupport.fmt(t, "yyyy-MM-dd"));
        x.put("title", title);
        x.put("desc", desc);
        x.put("at", t == null ? "" : StaffSupport.fmt(t, "yyyy-MM-dd HH:mm:ss"));
        out.add(x);
    }

    static List<String> splitTags(Object v)
    {
        List<String> out = new ArrayList<String>();
        for (String t : str(v).split(","))
        {
            t = t.trim();
            if (!t.isEmpty() && !out.contains(t))
            {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 报名详情 / 脱敏简历里的「经历记录」：报名人简历里的技能描述和经历（最近 10 段）。
     * 之前门店审报名时这一块永远是空标题，候选人在简历里填的经历送不到门店（简历页却写着「简历仅在报名时脱敏展示」）。
     * 每段只给时间、岗位、地点和一句描述；脱敏简历要转发出去，岗位、地点、描述和技能里的联系方式一律隐去：
     * 之前只遮 7 位以上连在一起的数字，「138-1234-5678」「138 1234 5678」、微信号原样送到普通员工和脱敏简历屏（2026-10-14 回归）
     * 门店授权由调用方按报名所属门店先校验
     */
    public Map<String, Object> resumeBrief(Long userId)
    {
        Map<String, Object> r = db.one("select skill_text, extra_text, profile_json from kg_resume where app_user_id = :u", p("u", userId));
        String skill = r == null ? "" : (str(r.get("skillText")) + "\n" + str(r.get("extraText"))).trim();
        List<Map<String, Object>> exps = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> e : db.list("select exp_id, kind, title, place, start_date, end_date, content from kg_resume_exp "
                + "where app_user_id = :u and del_flag = '0' order by coalesce(start_date, create_time) desc limit 10", p("u", userId)))
        {
            String k = str(e.get("kind"));
            String s = e.get("startDate") == null ? "" : str(e.get("startDate")).substring(0, 7).replace('-', '.');
            String en = e.get("endDate") == null ? "至今" : str(e.get("endDate")).substring(0, 7).replace('-', '.');
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("key", e.get("expId"));
            x.put("date", s.isEmpty() ? "时间未填" : s + "-" + en);
            x.put("place", maskContacts(str(e.get("place"))));
            x.put("role", maskContacts(str(e.get("title"))) + "（" + ("volunteer".equals(k) ? "换工" : ("parttime".equals(k) ? "兼职" : ("training".equals(k) ? "培训" : "全职"))) + "）");
            x.put("desc", StaffSupport.cut(maskContacts(str(e.get("content"))), 60));
            exps.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("skillText", StaffSupport.cut(maskContacts(skill), 300));
        if (r != null && r.get("profileJson") != null) {
            Map<String,Object> profile = resumeProfile.read(userId, r.get("profileJson"));
            for (String key : java.util.Arrays.asList("hobbies", "personality", "background"))
                m.put(key, maskContacts(str(profile.get(key))));
            m.put("media", profile.get("media"));
            m.put("lifePhotoUrl", profile.get("lifePhotoUrl"));
        }
        m.put("exps", exps);
        return m;
    }

    /** 候选人自己写的文字里的联系方式隐去：手机号（含加横线 / 空格的写法）、微信号、QQ 号换成占位，其余 7 位以上连续数字打码。规则见 MaskUtils.contacts */
    static String maskContacts(String s)
    {
        return MaskUtils.contacts(s);
    }

    /** 门店给某人加的技能标签（报名详情里的「＋」）：存在人才库标记表 store_tags，门店维度 */
    public List<String> storeTags(Long storeId, Long userId)
    {
        Map<String, Object> r = db.one("select store_tags from kg_talent_mark where store_id = :s and app_user_id = :u", p("s", storeId, "u", userId));
        return splitTags(r == null ? null : r.get("storeTags"));
    }

    /** 加 / 删一个门店标签：每人最多 8 个、每个 1–12 字；返回改后的标签 */
    @Transactional(rollbackFor = Exception.class)
    public List<String> editStoreTag(StaffSupport.Ctx c, Long userId, String tag, boolean add)
    {
        String t = tag == null ? "" : tag.replace(",", "").replace("，", "").trim();
        if (t.isEmpty() || t.length() > 12)
        {
            throw new ServiceException("标签为 1–12 个字", 422);
        }
        List<String> tags = storeTags(c.storeId, userId);
        if (add)
        {
            if (tags.contains(t))
            {
                return tags;
            }
            if (tags.size() >= 8)
            {
                throw new ServiceException("每人最多加 8 个门店标签", 422);
            }
            tags.add(t);
        }
        else if (!tags.remove(t))
        {
            return tags;
        }
        String joined = String.join(",", tags);
        db.update("insert into kg_talent_mark (store_id, app_user_id, store_tags, update_by, update_time) values (:s, :u, :t, :by, now()) "
                + "on duplicate key update store_tags = :t, update_by = :by, update_time = now()",
                p("s", c.storeId, "u", userId, "t", joined.isEmpty() ? null : joined, "by", "staff:" + c.uid()));
        support.op(c, "application_tag", add ? "添加报名人标签" : "删除报名人标签", "general", t, true, null);
        return tags;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> mark(StaffSupport.Ctx c, Long userId, String what, boolean on)
    {
        if (!"hidden".equals(what) && !"favorite".equals(what)) throw new ServiceException("不支持的标记类型", 422);
        if ("hidden".equals(what) && !on)
        {
            if (db.count("select count(*) from kg_talent_mark where store_id=:s and app_user_id=:u and hidden='1'", p("s", c.storeId, "u", userId)) == 0)
                throw new ServiceException("此人不在本店已隐藏名单中", 404);
        }
        else profile(c, userId);
        String col = "hidden".equals(what) ? "hidden" : "favorite";
        db.update("insert into kg_talent_mark (store_id, app_user_id, " + col + ", update_by, update_time) values (:s, :u, :v, :by, now()) "
                + "on duplicate key update " + col + " = :v, update_by = :by, update_time = now()", p("s", c.storeId, "u", userId, "v", on ? "1" : "0", "by", "staff:" + c.uid()));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message", "hidden".equals(what) ? (on ? "已从本店人才库隐藏" : "已恢复到本店人才库") : (on ? "已收藏" : "已取消收藏"));
        return m;
    }

    /** 申请联系：给对方发本店在招岗位的报名邀请（站内消息），对方报名后门店在报名管理里看到联系方式 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> invite(StaffSupport.Ctx c, Long userId, Long jobId)
    {
        profile(c, userId);
        Map<String, Object> j = db.must("select job_id, title from kg_job where job_id = :j and store_id = :s and status = 'published' and del_flag = '0'",
                p("j", jobId, "s", c.storeId), "只能邀请报名本店在招岗位");
        if (db.count("select count(*) from kg_talent_invite where store_id = :s and app_user_id = :u and create_time >= date_sub(now(), interval 7 day)",
                p("s", c.storeId, "u", userId)) >= 3)
        {
            throw new ServiceException("一周内最多邀请同一个人 3 次", 429);
        }
        try
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("store_id", c.storeId);
            row.put("app_user_id", userId);
            row.put("job_id", jobId);
            row.put("status", "sent");
            row.put("invited_by", c.uid());
            row.put("create_time", new Date());
            db.insert("kg_talent_invite", row);
        }
        catch (org.springframework.dao.DuplicateKeyException e)
        {
            throw new ServiceException("已经邀请过这个岗位了", 409);
        }
        support.notifyUser(userId, "system", c.storeName + " 邀请你报名", "门店看了你的简历，邀请你报名「" + j.get("title") + "」。感兴趣就点开报名，不感兴趣忽略即可。",
                "job", jobId, "/pages/jobs/detail?jobId=" + jobId);
        support.op(c, "talent_invite", "邀请人才报名", "general", String.valueOf(j.get("title")), true, null);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message", "邀请已发送，对方报名后会出现在报名管理里");
        return m;
    }

    public List<Map<String, Object>> openJobs(StaffSupport.Ctx c)
    {
        return db.list("select job_id, title from kg_job where store_id = :s and status = 'published' and del_flag = '0' order by job_id desc limit 20", p("s", c.storeId));
    }
}
