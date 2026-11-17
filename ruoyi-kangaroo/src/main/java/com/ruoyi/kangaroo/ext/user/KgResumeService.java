package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 简历：基本信息、技能描述（原文保留，标签由词典规则归类）、经历（平台记录的岗位经历自动生成 + 自己补充）、证书、找机会推荐。
 * 简历只在报名时给到对应门店，手机号在门店侧脱敏展示。
 */
@Service
public class KgResumeService
{
    static final List<String> KINDS = Arrays.asList("volunteer", "parttime", "fulltime", "training");
    static final Map<String, String> KIND_LABEL = new LinkedHashMap<String, String>();
    static
    {
        KIND_LABEL.put("volunteer", "换工经历");
        KIND_LABEL.put("parttime", "兼职经历");
        KIND_LABEL.put("fulltime", "全职/培训经历");
        KIND_LABEL.put("training", "全职/培训经历");
    }

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgHomeService home;

    @Autowired
    private KgResumeProfile profile;

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

    private Map<String, Object> row(Long me)
    {
        Map<String, Object> r = db.one("select * from kg_resume where app_user_id = :u", p("u", me));
        if (r == null)
        {
            Map<String, Object> u = db.one("select nickname, city, phone from kg_app_user where user_id = :u", p("u", me));
            r = new LinkedHashMap<String, Object>();
            r.put("name", u == null ? "" : str(u.get("nickname")));
            r.put("city", u == null ? "" : str(u.get("city")));
            r.put("phone", u == null ? "" : str(u.get("phone")));
            r.put("isNew", true);
        }
        return r;
    }

    /** 把已入职 / 已完成的岗位报名同步成"平台记录"的经历（幂等） */
    void syncPlatformExps(Long me)
    {
        for (Map<String, Object> a : db.list("select a.app_id, a.arrival_date, a.stay_days, j.job_type, j.title, j.city, s.store_name from kg_application a "
                + "join kg_job j on j.job_id = a.job_id left join kg_store s on s.store_id = a.store_id where a.app_user_id = :u and a.del_flag = '0' "
                + "and a.status in ('onboarded','completed') and j.job_type in ('volunteer','parttime','fulltime')", p("u", me)))
        {
            LocalDate f = a.get("arrivalDate") == null ? null : LocalDate.parse(str(a.get("arrivalDate")).substring(0, 10));
            long days = Math.max(1, num(a.get("stayDays")));
            db.update("insert ignore into kg_resume_exp (app_user_id, kind, title, place, start_date, end_date, app_id, source, create_time) "
                    + "values (:u, :k, :t, :pl, :f, :e, :a, 'platform', now())",
                    p("u", me, "k", a.get("jobType"), "t", StaffSupport.cut(str(a.get("title")), 64),
                            "pl", StaffSupport.cut(str(a.get("storeName")) + " · " + str(a.get("city")), 64),
                            "f", f == null ? null : f.toString(), "e", f == null ? null : f.plusDays(days - 1).toString(), "a", a.get("appId")));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> get(Long me)
    {
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        syncPlatformExps(me);
        Map<String, Object> r = row(me);
        List<Map<String, Object>> exps = exps(me);
        String skillAll = str(r.get("skillText")) + "\n" + str(r.get("extraText"));
        int score = 0;
        for (String k : Arrays.asList("name", "gender", "age", "phone", "city", "intro"))
        {
            score += str(r.get(k)).isEmpty() ? 0 : 10;
        }
        // 2026-10-12 主人确认：作品链接是可选项、不计分（之前占 10 分，没有作品的换工最高只能到 90%）；技能描述满 20 字 25 分、有经历 15 分，合计 100。
        // 小程序简历页（pages/profile/resume.vue）按同一套规则在前端计算显示，两边要一起改
        score += str(r.get("skillText")).length() >= 20 ? 25 : (str(r.get("skillText")).isEmpty() ? 0 : 10);
        score += exps.isEmpty() ? 0 : 15;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        r.putAll(profile.read(me, r.get("profileJson")));
        r.remove("profileJson");
        m.put("resume", r);
        m.put("completeness", score);
        m.put("exps", exps);
        m.put("stats", stats(exps));
        m.put("tags", KgSkills.profile(skillAll));
        m.put("skillLength", skillAll.trim().length());
        m.put("certs", certs(me));
        m.put("links", split(str(r.get("links"))));
        m.put("updated", r.get("updateTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(r.get("updateTime")), "yyyy-MM-dd"));
        return m;
    }

    private static List<String> split(String s)
    {
        List<String> out = new ArrayList<String>();
        for (String x : s.split("\n"))
        {
            if (!x.trim().isEmpty())
            {
                out.add(x.trim());
            }
        }
        return out;
    }

    public List<Map<String, Object>> exps(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> e : db.list("select e.*, (select round(avg(v.rating), 1) from kg_review v where v.biz_type = 'application' and v.biz_id = e.app_id "
                + "and v.target_type = 'user' and v.status = '0') store_rating, (select v.content from kg_review v where v.biz_type = 'application' and v.biz_id = e.app_id "
                + "and v.target_type = 'user' and v.status = '0' order by v.review_id desc limit 1) store_comment from kg_resume_exp e where e.app_user_id = :u and e.del_flag = '0' "
                + "order by e.start_date desc, e.exp_id desc", p("u", me)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("expId", e.get("expId"));
            m.put("kind", e.get("kind"));
            m.put("title", e.get("title"));
            m.put("place", e.get("place"));
            m.put("content", e.get("content"));
            m.put("level", e.get("level"));
            m.put("passed", "1".equals(str(e.get("passed"))));
            m.put("source", e.get("source"));
            LocalDate f = e.get("startDate") == null ? null : LocalDate.parse(str(e.get("startDate")).substring(0, 10));
            LocalDate t = e.get("endDate") == null ? null : LocalDate.parse(str(e.get("endDate")).substring(0, 10));
            m.put("startDate", f == null ? null : f.toString());
            m.put("endDate", t == null ? null : t.toString());
            m.put("range", (f == null ? "—" : f.toString().replace('-', '.')) + " – " + (t == null ? "至今" : t.toString().replace('-', '.')));
            long days = f == null ? 0 : ChronoUnit.DAYS.between(f, t == null ? LocalDate.now() : t) + 1;
            m.put("days", days);
            m.put("total", days >= 60 ? "共 " + Math.round(days / 30.0) + " 个月" : "共 " + days + " 天");
            m.put("stars", e.get("storeRating") == null ? null : Double.parseDouble(str(e.get("storeRating"))));
            m.put("comment", e.get("storeComment"));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> stats(List<Map<String, Object>> exps)
    {
        long[] cnt = new long[3];
        long[] days = new long[3];
        boolean passed = false;
        for (Map<String, Object> e : exps)
        {
            String k = str(e.get("kind"));
            int i = "volunteer".equals(k) ? 0 : ("parttime".equals(k) ? 1 : 2);
            cnt[i]++;
            days[i] += num(e.get("days"));
            passed = passed || Boolean.TRUE.equals(e.get("passed"));
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String[] titles = { "换工经历", "兼职经历", "全职/培训经历" };
        for (int i = 0; i < 3; i++)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("title", titles[i]);
            m.put("num", cnt[i]);
            m.put("sub", cnt[i] == 0 ? "暂无" : (i == 2 && passed ? "已通过考核" : (days[i] >= 60 ? "累计 " + Math.round(days[i] / 30.0) + " 个月" : "累计 " + days[i] + " 天")));
            out.add(m);
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveBasic(Long me, Map<String, Object> b)
    {
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        String name = str(b.get("name")).trim();
        if (name.isEmpty() || name.length() > 32)
        {
            throw new ServiceException("请填写昵称（32 字以内）", 422);
        }
        cols.put("name", name);
        String gender = str(b.get("gender"));
        if (!gender.isEmpty() && !Arrays.asList("女", "男", "其他").contains(gender))
        {
            throw new ServiceException("性别不合法", 422);
        }
        cols.put("gender", gender.isEmpty() ? null : gender);
        String age = str(b.get("age")).trim();
        if (!age.isEmpty())
        {
            long a = num(age);
            if (a < 16 || a > 80)
            {
                throw new ServiceException("年龄应在 16–80 之间", 422);
            }
            cols.put("age", a);
        }
        else if (b.containsKey("age"))
        {
            // 传了空年龄就是清掉（之前直接跳过，清不掉）；没传这个字段的老版本照旧不动
            cols.put("age", null);
        }
        String phone = str(b.get("phone")).replaceAll("\\s", "");
        if (!phone.isEmpty() && !phone.matches("1[3-9]\\d{9}"))
        {
            throw new ServiceException("请填写 11 位手机号", 422);
        }
        cols.put("phone", phone.isEmpty() ? null : phone);
        cols.put("city", StaffSupport.cut(str(b.get("city")).trim(), 32));
        String intro = str(b.get("intro")).trim();
        if (intro.length() > 200)
        {
            throw new ServiceException("自我介绍不超过 200 字", 422);
        }
        cols.put("intro", intro);
        cols.put("profile_json", profile.merge(me, row(me).get("profileJson"), b));
        if (b.containsKey("skill")) {
            String skill = str(b.get("skill")).trim();
            if (skill.length() > 1000) throw new ServiceException("技能描述不超过 1000 字", 422);
            cols.put("skill_text", skill);
        }
        upsert(me, cols);
        return get(me);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveSkills(Long me, Map<String, Object> b)
    {
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        String skill = str(b.get("skill")).trim();
        String extra = str(b.get("extra")).trim();
        if (skill.length() > 1000 || extra.length() > 1000)
        {
            throw new ServiceException("每段不超过 1000 字", 422);
        }
        List<String> links = new ArrayList<String>();
        if (b.get("links") instanceof List)
        {
            for (Object o : (List<?>) b.get("links"))
            {
                String l = str(o).trim();
                if (!l.matches("https?://\\S{4,300}"))
                {
                    throw new ServiceException("作品链接需以 http(s):// 开头", 422);
                }
                links.add(l);
            }
        }
        if (links.size() > 10)
        {
            throw new ServiceException("作品链接最多 10 个", 422);
        }
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        cols.put("skill_text", skill);
        cols.put("extra_text", extra);
        cols.put("links", String.join("\n", links));
        upsert(me, cols);
        return get(me);
    }

    private void upsert(Long me, Map<String, Object> cols)
    {
        Map<String, Object> r = db.one("select resume_id from kg_resume where app_user_id = :u", p("u", me));
        cols.put("update_time", new Date());
        if (r == null)
        {
            cols.put("app_user_id", me);
            if (!cols.containsKey("name"))
            {
                Map<String, Object> u = db.one("select nickname from kg_app_user where user_id = :u", p("u", me));
                cols.put("name", u == null ? "" : StaffSupport.cut(str(u.get("nickname")), 32));
            }
            cols.put("create_time", new Date());
            db.insert("kg_resume", cols);
        }
        else
        {
            db.updateRow("kg_resume", "resume_id", r.get("resumeId"), cols);
        }
    }

    /** 从最近一次报名表复用：称呼、年龄、电话、技能 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reuseLast(Long me)
    {
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        Map<String, Object> a = db.one("select real_name, age, phone, skills from kg_application where app_user_id = :u and del_flag = '0' order by app_id desc limit 1",
                p("u", me));
        if (a == null)
        {
            throw new ServiceException("还没有报名记录可复用", 404);
        }
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        if (!str(a.get("realName")).isEmpty())
        {
            cols.put("name", StaffSupport.cut(str(a.get("realName")), 32));
        }
        if (a.get("age") != null)
        {
            cols.put("age", a.get("age"));
        }
        if (str(a.get("phone")).matches("1[3-9]\\d{9}"))
        {
            cols.put("phone", a.get("phone"));
        }
        Map<String, Object> cur = row(me);
        if (!str(a.get("skills")).isEmpty() && str(cur.get("skillText")).isEmpty())
        {
            cols.put("skill_text", StaffSupport.cut(str(a.get("skills")), 1000));
        }
        upsert(me, cols);
        return get(me);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveExp(Long me, Long expId, Map<String, Object> b)
    {
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        String kind = str(b.get("kind"));
        if (!KINDS.contains(kind))
        {
            throw new ServiceException("经历类型不合法", 422);
        }
        String title = str(b.get("title")).trim();
        if (title.isEmpty() || title.length() > 64)
        {
            throw new ServiceException("请填写岗位 / 课程名称", 422);
        }
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        cols.put("kind", kind);
        cols.put("title", title);
        cols.put("place", StaffSupport.cut(str(b.get("place")).trim(), 64));
        for (String k : Arrays.asList("startDate", "endDate"))
        {
            String v = str(b.get(k));
            if (!v.isEmpty() && !KgJdbc.isDate(v))
            {
                throw new ServiceException("日期格式应为 yyyy-MM-dd", 422);
            }
            cols.put(KgJdbc.toSnake(k), v.isEmpty() ? null : v);
        }
        if (cols.get("start_date") != null && cols.get("end_date") != null && str(cols.get("end_date")).compareTo(str(cols.get("start_date"))) < 0)
        {
            // yyyy-MM-dd 字符串按字典序比较即按日期比较
            throw new ServiceException("结束日期不能早于开始日期", 422);
        }
        cols.put("content", StaffSupport.cut(str(b.get("content")).trim(), 500));
        cols.put("level", StaffSupport.cut(str(b.get("level")).trim(), 16));
        cols.put("update_time", new Date());
        if (expId == null)
        {
            if (db.count("select count(*) from kg_resume_exp where app_user_id = :u and del_flag = '0'", p("u", me)) >= 30)
            {
                throw new ServiceException("经历最多 30 段", 409);
            }
            cols.put("app_user_id", me);
            cols.put("source", "manual");
            cols.put("create_time", new Date());
            db.insert("kg_resume_exp", cols);
        }
        else
        {
            if (db.count("select count(*) from kg_resume_exp where exp_id = :e and app_user_id = :u and source = 'manual' and del_flag = '0'",
                    p("e", expId, "u", me)) == 0)
            {
                throw new ServiceException("只能修改自己填写的经历（平台记录的经历不可修改）", 403);
            }
            db.updateRow("kg_resume_exp", "exp_id", expId, cols);
        }
        return get(me);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> removeExp(Long me, Long expId)
    {
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        if (db.update("update kg_resume_exp set del_flag = '2', update_time = now() where exp_id = :e and app_user_id = :u and source = 'manual'", p("e", expId, "u", me)) == 0)
        {
            throw new ServiceException("只能删除自己填写的经历", 403);
        }
        return get(me);
    }

    public List<Map<String, Object>> certs(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> c : db.list("select * from kg_cert where app_user_id = :u and status = '0' order by issued_date desc", p("u", me)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("certId", c.get("certId"));
            m.put("name", c.get("name"));
            m.put("category", c.get("category"));
            m.put("level", c.get("level"));
            m.put("grade", c.get("grade"));
            m.put("score", c.get("score"));
            m.put("certNo", c.get("certNo"));
            m.put("issuer", c.get("issuer"));
            m.put("issuedDate", str(c.get("issuedDate")).substring(0, Math.min(10, str(c.get("issuedDate")).length())));
            out.add(m);
        }
        return out;
    }

    /** 找机会：按简历技能标签给岗位打分（重合越多越靠前），并给出匹配原因 */
    public Map<String, Object> opportunities(Long me, String city)
    {
        Map<String, Object> r = row(me);
        List<String> skills = KgSkills.tagsOf(str(r.get("skillText")) + " " + str(r.get("extraText")));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("city", city == null || city.isEmpty() ? str(r.get("city")) : city);
        body.put("skills", skills);
        body.put("from", LocalDate.now().plusDays(1).toString());
        body.put("to", LocalDate.now().plusDays(60).toString());
        Map<String, Object> res = home.match(me, body);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("skills", skills);
        m.put("jobs", res.get("jobs"));
        m.put("city", res.get("city"));
        return m;
    }
}
