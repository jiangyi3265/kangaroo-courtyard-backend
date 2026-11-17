package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.stay.KgStayBrowseService;

/**
 * 首页：近期活动、为你推荐；"找机会"条件匹配（规则打分，不是 AI）：岗位 / 房源 / 活动三类结果 + 五个维度的匹配依据；
 * 一条都匹配不上时给出冲突条件与放宽建议。
 */
@Service
public class KgHomeService
{
    private static final Pattern MONEY = Pattern.compile("(\\d{3,6})\\s*(元|块|rmb)?");
    private static final Pattern DAYS = Pattern.compile("(\\d{1,3})\\s*天");

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgActivityService activities;

    @Autowired
    private KgCityService cities;

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

    static String typeLabel(String t)
    {
        return "volunteer".equals(t) ? "换工换宿" : ("parttime".equals(t) ? "兼职" : ("fulltime".equals(t) ? "全职" : "在线"));
    }

    static String salary(Map<String, Object> j)
    {
        if ("none".equals(str(j.get("salaryType"))) || j.get("salaryMin") == null)
        {
            String extras = com.ruoyi.kangaroo.domain.JobCompensation.summary("", str(j.get("bonusDesc")), str(j.get("commissionDesc")));
            return extras.isEmpty() ? "面议" : extras;
        }
        java.math.BigDecimal a = new java.math.BigDecimal(String.valueOf(j.get("salaryMin")));
        java.math.BigDecimal b = j.get("salaryMax") == null ? a : new java.math.BigDecimal(String.valueOf(j.get("salaryMax")));
        String min = a.stripTrailingZeros().toPlainString();
        return b.compareTo(a) > 0 ? min + "-" + b.stripTrailingZeros().toPlainString() : min;
    }

    /** 给岗位卡片标上「我是否收藏了」（游客一律 false）：首页 / 筛选结果的心形靠它显示，不然已收藏的也是空心 */
    void markFavorites(Long me, List<Map<String, Object>> cards)
    {
        if (cards.isEmpty())
        {
            return;
        }
        java.util.Set<Long> fav = new java.util.HashSet<Long>();
        if (me != null)
        {
            List<Long> ids = new ArrayList<Long>();
            for (Map<String, Object> m : cards)
            {
                ids.add(KgJdbc.asLong(m.get("jobId")));
            }
            for (Map<String, Object> r : db.list("select job_id from kg_job_favorite where app_user_id = :u and job_id in (:ids)", p("u", me, "ids", ids)))
            {
                fav.add(KgJdbc.asLong(r.get("jobId")));
            }
        }
        for (Map<String, Object> m : cards)
        {
            m.put("favorite", fav.contains(KgJdbc.asLong(m.get("jobId"))));
        }
    }

    Map<String, Object> jobCard(Map<String, Object> j)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        KgJobDisplayOrder.decorate(m, j.get("urgentLevel"), j.get("status"), j.get("urgentDeadline"));
        m.put("status", j.get("status"));
        m.put("jobId", j.get("jobId"));
        m.put("title", j.get("title"));
        m.put("cover", j.get("cover"));
        m.put("city", j.get("city"));
        m.put("jobType", j.get("jobType"));
        m.put("typeLabel", typeLabel(str(j.get("jobType"))));
        m.put("price", salary(j));
        m.put("unit", "none".equals(str(j.get("salaryType"))) || j.get("salaryMin") == null ? "" : str(j.get("salaryUnit")));
        m.put("board", "all".equals(str(j.get("boardType"))) ? "包食宿" : ("room".equals(str(j.get("boardType"))) ? "包住宿" : ""));
        m.put("longTerm", num(j.get("minDays")) >= 30);
        m.put("insured", "0".equals(str(j.get("insuranceFlag"))));  // 字典 insurance_flag：0 含 / 1 不含
        m.put("safe", "0".equals(str(j.get("safeCert"))));  // 字典 safe_cert：0 已认证 / 1 未认证
        m.put("monthRedpacket", "0".equals(str(j.get("monthRedpacket"))));  // 做满一个月有红包（V44）：0 有 / 1 没有
        m.put("storeName", j.get("storeName"));
        m.put("startDate", j.get("startDate") == null ? null : str(j.get("startDate")).substring(0, 10));
        m.put("minDays", num(j.get("minDays")));
        m.put("dailyHours", j.get("dailyHours"));
        m.put("headcount", num(j.get("headcount")));
        List<String> tags = new ArrayList<String>();
        if (!str(j.get("bonusDesc")).isEmpty()) tags.add("奖金");
        if (!str(j.get("commissionDesc")).isEmpty()) tags.add("提成");
        for (String tag : KgSkills.tagsOf(str(j.get("title")) + " " + str(j.get("tags")) + " " + str(j.get("content")) + " " + str(j.get("requirement"))))
        {
            if (!tags.contains(tag)) tags.add(tag);
        }
        m.put("tags", tags);
        return m;
    }

    // 门店停用 / 删除后它的岗位不能继续展示（小程序端原有的岗位列表早就按这个过滤；V11 新增的首页 feed、筛选、订阅推送漏了），
    // 否则用户能报名一个没人处理的岗位
    private static final String JOB_SELECT = "select j.*, s.store_name, s.safe_cert from kg_job j left join kg_store s on s.store_id = j.store_id "
            + "where j.del_flag = '0' and j.status = 'published' and s.status = '0' and s.del_flag = '0'";

    public Map<String, Object> feed(Long me, String city)
    {
        // 区县（思明区）按所属城市（厦门）推荐
        String c = city == null || city.trim().isEmpty() || "全部城市".equals(city) ? "" : cities.businessCity(city);
        if(!com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.RECRUITMENT_KEY)) {
            List<Map<String,Object>> stays = com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.STAY_KEY) ? recommendedStays() : new ArrayList<Map<String,Object>>();
            return p("city",c,"activities",homeActivities(c),"jobs",new ArrayList<Map<String,Object>>(),"stays",stays,"stores",stays,"personalized",false);
        }
        List<String> skills = new ArrayList<String>();
        if (me != null)
        {
            Map<String, Object> r = db.one("select skill_text, extra_text from kg_resume where app_user_id = :u", p("u", me));
            if (r != null)
            {
                skills = KgSkills.tagsOf(str(r.get("skillText")) + " " + str(r.get("extraText")));
            }
        }
        // 岗位城市和岗位列表同一口径（KgJobCity：等于或以城市 / 别名结尾）：之前首页按 j.city = :c 精确匹配，
        // 选「平潭」时写成「平潭岛」「福建平潭」的岗位上不了首页，点「更多」进列表却有
        Map<String, Object> jobPs = p("c", c);
        String jobCity = c.isEmpty() ? "" : " and " + KgJobCity.sql("j.city", "jc", KgJobCity.terms(cities, c), jobPs);
        List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> j : db.list(JOB_SELECT + " and j.job_type <> 'online'" + jobCity + " order by " + KgJobDisplayOrder.sql() + " limit 20",
                jobPs))
        {
            Map<String, Object> m = jobCard(j);
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) m.get("tags");
            int overlap = 0;
            for (String s : skills)
            {
                overlap += tags.contains(s) ? 1 : 0;
            }
            m.put("overlap", overlap);
            jobs.add(m);
        }
        // Regular recommendations follow the configured display order; assistant matching keeps its own scores.
        List<Map<String,Object>> configured = db.list(JOB_SELECT + " and exists(select 1 from kg_home_pick h where h.section='job' and h.target_id=j.job_id and " + pickWhere() + ")" + (c.isEmpty()?"":" and (j.job_type='online' or "+KgJobCity.sql("j.city", "jc", KgJobCity.terms(cities, c), jobPs)+")"),jobPs);
        Map<Long,Map<String,Object>> byId=new LinkedHashMap<>();
        for(Map<String,Object> j:configured) byId.put(num(j.get("jobId")),jobCard(j));
        List<Map<String,Object>> ordered=new ArrayList<>();
        for(Map<String,Object> pick:db.list("select target_id,min(sort) pos from kg_home_pick h where h.section='job' and "+pickWhere()+" group by target_id order by pos,target_id",p("c",c))) {
            Map<String,Object> card=byId.get(num(pick.get("targetId"))); if(card!=null) ordered.add(card);
        }
        List<Map<String, Object>> top = mergeCards(ordered,jobs,"jobId",3);
        markFavorites(me, top);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("city", c);
        res.put("activities", homeActivities(c));
        res.put("jobs", com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.RECRUITMENT_KEY) ? top : new ArrayList<Map<String,Object>>());
        // 首页小院推荐面向各地区，不跟随当前位置或兴趣城市收窄。
        List<Map<String, Object>> stays = com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.STAY_KEY) ? recommendedStays() : new ArrayList<Map<String,Object>>();
        res.put("stays", stays);
        // 已上传的旧客户端仍读取 stores，保留字段并使用相同的公开房源封面。
        res.put("stores", stays);
        res.put("personalized", !skills.isEmpty());
        return res;
    }

    /**
     * 助手问「附近有什么兼职 / 附近换工」时按岗位类型取招募中的岗位：展示口径与 feed 相同（已发布、门店在营、不含线上任务，
     * 城市为空或「全部城市」不限城市），排序同 feed 的兜底（加急优先、新发布优先）
     */
    public List<Map<String, Object>> jobsOfTypes(String city, List<String> types, int limit)
    {
        if(!com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.RECRUITMENT_KEY)) return new ArrayList<Map<String,Object>>();
        String c = city == null || city.trim().isEmpty() || "全部城市".equals(city) ? "" : cities.businessCity(city);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        // 城市口径同岗位列表（KgJobCity）
        Map<String, Object> ps = p("t", types, "c", c);
        String jobCity = c.isEmpty() ? "" : " and " + KgJobCity.sql("j.city", "jc", KgJobCity.terms(cities, c), ps);
        for (Map<String, Object> j : db.list(JOB_SELECT + " and j.job_type <> 'online' and j.job_type in (:t)" + jobCity
                + " order by " + KgJobDisplayOrder.sql() + " limit " + Math.max(1, limit), ps))
        {
            out.add(jobCard(j));
        }
        return out;
    }

    /** Published stays with the same visibility rules as the booking list; different cities first. */
    List<Map<String, Object>> recommendedStays()
    {
        List<Map<String, Object>> rows = db.list(
                "select s.stay_id, s.stay_name, s.store_id, st.store_name, s.city, s.cover, s.area_desc intro, st.safe_cert, st.op_type "
                + "from kg_stay s join kg_store st on st.store_id=s.store_id where " + KgStayBrowseService.ONLINE
                + " order by s.sort, s.stay_id limit 60", p());
        Map<String, Map<String, Object>> firstByCity = new LinkedHashMap<>();
        List<Map<String, Object>> remaining = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            Map<String, Object> card = new LinkedHashMap<>();
            for (String key : java.util.Arrays.asList("stayId", "stayName", "storeId", "storeName", "city", "cover", "intro", "opType"))
            {
                card.put(key, row.get(key));
            }
            card.put("safe", "0".equals(str(row.get("safeCert"))));
            String region = str(row.get("city"));
            if (firstByCity.containsKey(region)) remaining.add(card);
            else firstByCity.put(region, card);
        }
        List<Map<String, Object>> cards = new ArrayList<>(firstByCity.values());
        cards.addAll(remaining);
        return cards;
    }

    /**
     * 首页「近期活动」固定露两格：先放后台精选和 14 天内的热门；不足 2 场时补本城市 14 天以后报名中的，
     * 选了城市还不够再补其他城市的（卡片上写着日期和城市，不会让人误会）。按 activityId 去重，最多 3 场（旧版小程序一行放 3 张）。
     */
    List<Map<String, Object>> homeActivities(String c)
    {
        List<Map<String, Object>> list = mergeCards(activities.homePicks(c), activities.hots(c), "activityId", 3);
        if (list.size() < 2)
        {
            list = mergeCards(list, activities.upcoming(c, 3), "activityId", 3);
        }
        if (list.size() < 2 && !c.isEmpty())
        {
            list = mergeCards(list, activities.upcoming("", 3), "activityId", 3);
        }
        return list;
    }

    public static String pickWhere() {
        return "h.del_flag='0' and h.status='0' and h.city=:c and (h.start_time is null or h.start_time<=now()) and (h.end_time is null or h.end_time>now())";
    }
    static List<Map<String,Object>> mergeCards(List<Map<String,Object>> first,List<Map<String,Object>> fallback,String key,int limit) {
        Map<Object,Map<String,Object>> selected=new LinkedHashMap<>();
        for(List<Map<String,Object>> group:java.util.Arrays.asList(first,fallback)) for(Map<String,Object> row:group) if(!selected.containsKey(row.get(key))) selected.put(row.get(key),row);
        List<Map<String,Object>> out=new ArrayList<>(selected.values());return out.size()>limit?new ArrayList<>(out.subList(0,limit)):out;
    }

    // ---------------------------------------------------------------- 找机会：条件匹配

    /**
     * 从「找机会」的文字里认城市：只认两个字及以上的城市名（城市库里有 95 个一个字的区县，「找换工」里的「义」、「和朋友一起」里的「和」
     * 之前会被当成城市，结果一条都没有；2026-10-16 验收发现）；同时出现几个时取最长的，一样长取先出现的。认不出返回空串（全国匹配）
     */
    static String cityInText(String text, List<String> names)
    {
        String best = "";
        int bestAt = Integer.MAX_VALUE;
        if (text == null || text.isEmpty() || names == null)
        {
            return best;
        }
        for (String n : names)
        {
            if (n == null || n.trim().length() < 2)
            {
                continue;
            }
            int at = text.indexOf(n.trim());
            if (at < 0)
            {
                continue;
            }
            String name = n.trim();
            if (name.length() > best.length() || (name.length() == best.length() && at < bestAt))
            {
                best = name;
                bestAt = at;
            }
        }
        return best;
    }

    /**
     * body：text 自由描述、city、from / to（yyyy-MM-dd）、people、hours（每天工时上限）、skills[]、budget（元 / 周）、relax[]（date / budget / city）
     * city 为空或「全部城市」、文字里也没写城市时按全国匹配（返回的 city 是空串）
     */
    public Map<String, Object> match(Long me, Map<String, Object> body)
    {
        com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireRecruitment(db);
        String text = str(body.get("text"));
        String city = str(cities.businessCity(str(body.get("city"))));
        if ("全部城市".equals(city))
        {
            city = "";
        }
        if (city.isEmpty())
        {
            List<String> names = new ArrayList<String>();
            // 只认地级城市和目的地（大理、平潭、腾冲…），不认区县：区县名常和日常说法撞上（「想去大城市」里的「大城」= 河北大城县，
            // 「找换工」里的「义」= 义县），认成城市后一条结果都没有（10-16 复验发现）
            for (Map<String, Object> c : db.list("select name from kg_city where status = '0' and level in ('city', 'destination')", p()))
            {
                names.add(str(c.get("name")));
            }
            city = cityInText(text, names);
            if (!city.isEmpty())
            {
                // 和传进来的城市参数同一口径（别名、全称换成城市字典里的名字）
                city = str(cities.businessCity(city));
            }
        }
        // 没选城市（首页「全部城市」）、文字里也没写城市：全国范围匹配。之前一律当成大理，首页写着全部城市，结果却只有大理的
        boolean nationwide = city.isEmpty();
        LocalDate from = parse(str(body.get("from")), LocalDate.now().plusDays(1));
        LocalDate to = parse(str(body.get("to")), null);
        if (to == null)
        {
            Matcher dm = DAYS.matcher(text);
            long d = dm.find() ? Long.parseLong(dm.group(1)) : (text.contains("一个月") || text.contains("1个月") ? 30 : (text.contains("两周") ? 14 : 7));
            to = from.plusDays(Math.max(1, d) - 1);
        }
        long stayDays = ChronoUnit.DAYS.between(from, to) + 1;
        long budget = num(body.get("budget"));
        if (budget == 0)
        {
            Matcher mm = MONEY.matcher(text);
            budget = mm.find() ? Long.parseLong(mm.group(1)) : 0;
        }
        Integer hours = body.get("hours") == null || str(body.get("hours")).isEmpty() ? null : (int) num(body.get("hours"));
        // 人数（首页选的 1 人 / 2 人 / 3 人 / 4 人及以上）：之前没读，选 4 人和选 1 人推荐的一样，还会推荐只能住 1–2 人的房间
        int people = (int) Math.max(1, Math.min(20, num(body.get("people"))));
        List<String> skills = new ArrayList<String>();
        Object sk = body.get("skills");
        if (sk instanceof List)
        {
            for (Object o : (List<?>) sk)
            {
                skills.add(str(o));
            }
        }
        skills.addAll(KgSkills.tagsOf(text));
        if (skills.isEmpty() && me != null)
        {
            Map<String, Object> r = db.one("select skill_text, extra_text from kg_resume where app_user_id = :u", p("u", me));
            if (r != null)
            {
                skills.addAll(KgSkills.tagsOf(str(r.get("skillText")) + " " + str(r.get("extraText"))));
            }
        }
        List<String> relax = new ArrayList<String>();
        if (body.get("relax") instanceof List)
        {
            for (Object o : (List<?>) body.get("relax"))
            {
                relax.add(str(o));
            }
        }
        LocalDate winFrom = relax.contains("date") ? from.minusDays(7) : from;
        LocalDate winTo = relax.contains("date") ? to.plusDays(7) : to;
        long budgetEff = relax.contains("budget") && budget > 0 ? Math.round(budget * 1.5) : budget;
        // 全国匹配时 cityList 只有一个空串：下面三类查询遇到空串就不加城市条件；也没有「同城市群」可扩
        List<String> cityList = new ArrayList<String>();
        cityList.add(city);
        if (relax.contains("city") && !nationwide)
        {
            for (Object s : (List<?>) cities.overview(city).get("siblings"))
            {
                if (!cityList.contains(str(s)))
                {
                    cityList.add(str(s));
                }
            }
        }
        boolean wantVolunteer = text.contains("换宿") || text.contains("换工") || text.contains("义工");
        boolean wantPart = text.contains("兼职") || text.contains("打工");
        boolean wantFull = text.contains("全职") || text.contains("驻店");

        // 文字里写了想做哪类（换宿 / 兼职 / 全职），类型条件放进 SQL 再取前 N 个：之前先按加急、发布时间取前 200 个再在这里筛类型，
        // 兼职、全职多半排不进前 200，全国匹配「全职」只能出来一部分（2026-10-16 复查发现）
        List<String> wantTypes = new ArrayList<String>();
        if (wantVolunteer) wantTypes.add("volunteer");
        if (wantPart) wantTypes.add("parttime");
        if (wantFull) wantTypes.add("fulltime");

        List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
        int[] dimSum = new int[5];
        for (String c : cityList)
        {
            // 城市口径同岗位列表（KgJobCity：等于或以城市 / 别名结尾，写法里的 % _ 按字面）；之前 like %城市%，「大连」会带出「五大连池」。
            // 全国匹配不加城市条件，按加急、新发布取前 200 个来打分（最后只回 30 个）
            Map<String, Object> jobPs = p();
            String jobType = "";
            if (!wantTypes.isEmpty())
            {
                jobType = " and j.job_type in (:jt)";
                jobPs.put("jt", wantTypes);
            }
            String order = " order by " + KgJobDisplayOrder.sql();
            String jobCity = c.isEmpty() ? order + " limit 200"
                    : " and " + KgJobCity.sql("j.city", "jc", KgJobCity.terms(cities, c), jobPs) + order + " limit 100";
            for (Map<String, Object> j : db.list(JOB_SELECT + " and j.job_type <> 'online'" + jobType + jobCity, jobPs))
            {
                String jt = str(j.get("jobType"));
                if ((wantVolunteer || wantPart || wantFull) && !((wantVolunteer && "volunteer".equals(jt)) || (wantPart && "parttime".equals(jt)) || (wantFull && "fulltime".equals(jt))))
                {
                    continue;
                }
                Map<String, Object> m = jobCard(j);
                LocalDate start = j.get("startDate") == null ? null : LocalDate.parse(str(j.get("startDate")).substring(0, 10));
                long minDays = num(j.get("minDays"));
                int sCity = c.equals(city) ? 100 : 70;
                int sTime = start == null || !start.isAfter(winTo) ? (minDays <= stayDays || minDays == 0 ? 100 : 55) : 30;
                long dh = KgSkills.hoursOf(j.get("dailyHours"));
                int sHours = hours == null || dh == 0 || dh <= hours ? 100 : 60;
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) m.get("tags");
                int overlap = 0;
                for (String s : skills)
                {
                    overlap += tags.contains(s) ? 1 : 0;
                }
                int sSkill = skills.isEmpty() ? 75 : (overlap == 0 ? 50 : Math.min(100, 75 + overlap * 12));
                String board = str(j.get("boardType"));
                int sLife = "all".equals(board) ? 100 : ("room".equals(board) ? 90 : 65);
                int score = (int) Math.round(sCity * 0.25 + sTime * 0.25 + sHours * 0.15 + sSkill * 0.2 + sLife * 0.15);
                if (score < 55)
                {
                    continue;
                }
                List<String> why = new ArrayList<String>();
                if (overlap > 0)
                {
                    why.add("技能匹配（" + String.join("、", intersect(tags, skills)) + "）");
                }
                if (!board.isEmpty() && !"none".equals(board))
                {
                    why.add("all".equals(board) ? "包食宿" : "提供住宿");
                }
                if (sTime == 100)
                {
                    why.add(minDays > 0 ? "你的停留时间满足至少 " + minDays + " 天" : "时间合适");
                }
                else if (minDays > stayDays)
                {
                    why.add("需要至少 " + minDays + " 天，比你计划的长");
                }
                if (dh > 0)
                {
                    why.add("每天约 " + dh + " 小时");
                }
                long head = num(j.get("headcount"));
                if (people > 1 && head > 0 && head < people)
                {
                    why.add("只招 " + head + " 人，不够 " + people + " 人一起");
                }
                m.put("score", score);
                m.put("reason", String.join("，", why));
                m.put("dateText", (start == null ? "随时" : start.getMonthValue() + "." + start.getDayOfMonth() + " 起") + (dh > 0 ? " · 每天 " + dh + " 小时" : ""));
                m.put("dims", new int[] { sCity, sTime, sSkill, sLife, sHours });
                jobs.add(m);
            }
        }
        jobs.sort((a, b) -> Integer.compare((Integer) b.get("score"), (Integer) a.get("score")));
        for (Map<String, Object> j : jobs)
        {
            int[] d = (int[]) j.remove("dims");
            for (int i = 0; i < 5; i++)
            {
                dimSum[i] += d[i];
            }
        }

        // 房源只算住得下这么多人的房型（与预订时的口径一致：床位按人头订，几个人订几个床位；房间要可住人数 ≥ 人数），
        // 价格按这几个人每晚一共要付的算（床位单价 × 人数，房间按间）。一个合适房型都没有的房源不推荐
        List<Map<String, Object>> stays = new ArrayList<Map<String, Object>>();
        int tooSmall = 0;
        for (String c : (com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.STAY_KEY) ? cityList : java.util.Collections.<String>emptyList()))
        {
            // 全国匹配不限城市，按后台排序取前 30 家
            for (Map<String, Object> s : db.list("select s.stay_id, s.stay_name, s.cover, s.area, s.city, (select min(case when t.unit_label = '床位' then (" + com.ruoyi.kangaroo.ext.stay.KgSettlementMath.saleSql("t.base_price_fen", "st.op_type") + ") * :n else (" + com.ruoyi.kangaroo.ext.stay.KgSettlementMath.saleSql("t.base_price_fen", "st.op_type") + ") end) "
                    + "from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0' and (t.unit_label = '床位' or t.capacity >= :n)) price "
                    + "from kg_stay s left join kg_store st on st.store_id=s.store_id where " + (c.isEmpty() ? "" : "s.city = :c and ") + KgStayBrowseService.ONLINE + (c.isEmpty() ? " order by s.sort, s.stay_id limit 30" : ""),
                    c.isEmpty() ? p("n", people) : p("c", c, "n", people)))
            {
                if (s.get("price") == null)
                {
                    tooSmall++;
                    continue;
                }
                BigDecimal nightly = BigDecimal.valueOf(num(s.get("price")), 2);
                BigDecimal week = nightly.multiply(BigDecimal.valueOf(Math.min(7, stayDays)));
                boolean fit = budgetEff == 0 || week.compareTo(BigDecimal.valueOf(budgetEff)) <= 0;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("stayId", s.get("stayId"));
                m.put("title", s.get("stayName"));
                m.put("cover", s.get("cover"));
                m.put("price", nightly);
                m.put("unit", people > 1 ? "元/晚起（" + people + " 人）" : "元/晚起");
                m.put("score", fit ? 88 : 60);
                // 房源的 area 多是空字符串而不是 null：之前只判 null，理由末尾总多一个逗号（「在你的预算内，住得下 4 人，」）
                String area = str(s.get("area")).trim();
                m.put("reason", (fit ? "在你的预算内" : "超出预算") + (people > 1 ? "，住得下 " + people + " 人" : "") + (area.isEmpty() ? "" : "，" + area));
                m.put("dateText", from.getMonthValue() + "." + from.getDayOfMonth() + " – " + to.getMonthValue() + "." + to.getDayOfMonth());
                if (fit || relax.contains("budget"))
                {
                    stays.add(m);
                }
            }
        }
        List<Map<String, Object>> acts = new ArrayList<Map<String, Object>>();
        for (String c : cityList)
        {
            Map<String, Object> actPs = p("f", winFrom.toString(), "t", winTo.plusDays(1).toString());
            if (!c.isEmpty())
            {
                actPs.put("c", c);
            }
            for (Map<String, Object> a : db.list("select activity_id, title, cover, place, start_time, end_time, fee_fen, fee_mode, aa_fen, category from kg_activity a where " + com.ruoyi.kangaroo.ext.community.KgCommunityFeature.USER_ACTIVITY_VISIBLE_SQL + " and " + (c.isEmpty() ? "" : "city = :c and ") + "status = 'open' "
                    + "and del_flag = '0' and start_time >= :f and start_time < :t order by start_time limit " + (c.isEmpty() ? 20 : 10), actPs))
            {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("activityId", a.get("activityId"));
                m.put("title", a.get("title"));
                m.put("cover", a.get("cover"));
                // AA制（2026-11-03）：没有报名费，显示预估人均「80.00 元/人·AA」，没填预估写「AA制」
                boolean aa = "aa".equals(String.valueOf(a.get("feeMode")));
                long priceFen = aa ? num(a.get("aaFen")) : num(a.get("feeFen"));
                m.put("price", BigDecimal.valueOf(priceFen, 2));
                m.put("unit", aa ? (priceFen > 0 ? "元/人·AA" : "AA制") : (priceFen == 0 ? "免费" : "元/人"));
                m.put("score", 80);
                String place = str(a.get("place")).trim();
                m.put("reason", "在你的行程时间内" + (place.isEmpty() ? "" : "，集合：" + place));
                m.put("dateText", KgActivityService.rangeText(a.get("startTime"), a.get("endTime")));
                acts.add(m);
            }
        }

        int n = Math.max(1, jobs.size());
        String[] labels = { "城市", "时间", "技能", "生活偏好", "工时" };
        String[] icons = { "house", "clock-3", "wrench", "heart-handshake", "badge-dollar-sign" };
        String[] descs = {
            nationwide ? "你选择了全部城市，在全国范围内为你推荐。" : "你选择了" + String.join(" / ", cityList) + "，优先推荐当地的机会。",
            "你的时间 " + from.getMonthValue() + "." + from.getDayOfMonth() + " – " + to.getMonthValue() + "." + to.getDayOfMonth() + "（" + stayDays + " 天）。",
            skills.isEmpty() ? "还没有技能信息，完善简历后匹配更准。" : "你的技能：" + String.join(" / ", new java.util.LinkedHashSet<String>(skills)) + "。",
            "优先包食宿 / 提供住宿的机会" + (budget > 0 ? "，预算约 " + budget + " 元" : "") + (people > 1 ? "，" + people + " 人同行，房源只推荐住得下的房型。" : "。"),
            hours == null ? "没有限定每天工时。" : "每天工时 ≤ " + hours + " 小时。"
        };
        List<Map<String, Object>> reasons = new ArrayList<Map<String, Object>>();
        long total = 0;
        for (int i = 0; i < 5; i++)
        {
            int sc = jobs.isEmpty() ? 0 : Math.round(dimSum[i] / (float) n);
            total += sc;
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("label", labels[i]);
            r.put("icon", icons[i]);
            r.put("score", sc);
            r.put("desc", descs[i]);
            reasons.add(r);
        }
        List<String> conflicts = new ArrayList<String>();
        if (jobs.isEmpty() && stays.isEmpty() && acts.isEmpty())
        {
            conflicts.add("日期：" + from.getMonthValue() + "." + from.getDayOfMonth() + "–" + to.getMonthValue() + "." + to.getDayOfMonth() + " 期间机会较少");
            if (budget > 0)
            {
                conflicts.add("预算：≤ " + budget + " 元 可选较少");
            }
            if (tooSmall > 0)
            {
                conflicts.add("人数：住得下 " + people + " 人的房型较少");
            }
            if (!nationwide)
            {
                conflicts.add("城市：只在" + city + "内选择较少");
            }
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("city", city);
        res.put("from", from.toString());
        res.put("to", to.toString());
        res.put("stayDays", stayDays);
        res.put("budget", budget);
        res.put("hours", hours);
        res.put("people", people);
        res.put("skills", new ArrayList<String>(new java.util.LinkedHashSet<String>(skills)));
        res.put("jobs", jobs.size() > 30 ? jobs.subList(0, 30) : jobs);
        res.put("stays", stays);
        res.put("activities", acts);
        res.put("reasons", reasons);
        res.put("overall", jobs.isEmpty() ? 0 : Math.round(total / 5.0));
        res.put("conflicts", conflicts);
        res.put("empty", jobs.isEmpty() && stays.isEmpty() && acts.isEmpty());
        return res;
    }

    private static List<String> intersect(List<String> a, List<String> b)
    {
        List<String> out = new ArrayList<String>();
        for (String x : a)
        {
            if (b.contains(x) && !out.contains(x))
            {
                out.add(x);
            }
        }
        return out;
    }

    private static LocalDate parse(String v, LocalDate dft)
    {
        try
        {
            return v == null || v.isEmpty() ? dft : LocalDate.parse(v.substring(0, 10));
        }
        catch (Exception e)
        {
            return dft;
        }
    }
}
