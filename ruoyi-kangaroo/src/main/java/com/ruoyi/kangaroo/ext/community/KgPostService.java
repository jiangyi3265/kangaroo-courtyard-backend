package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.util.AccountState;
import com.ruoyi.kangaroo.ext.core.KgReportHandler;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.stay.KgStayBrowseService;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.ext.user.KgPointRewards;
import com.ruoyi.kangaroo.security.WxMsgSecCheck;
import com.ruoyi.kangaroo.service.IKgMessageService;

/**
 * 社区动态：信息流、找搭子、帖子详情、评论、点赞 / 收藏 / 关注 / 拉黑 / 举报、发布（含经历校验与找搭子同步活动）。
 * 作者身份只展示可推导的事实：「手机已验证」= 经微信手机号快速验证；「行程已验证」= 在该城市有已确认的住宿 / 岗位 / 活动。
 * 本期没有实名认证能力，任何地方都不出现"实名"。
 */
@Service
public class KgPostService implements KgReportHandler
{
    static final List<String> KINDS = Arrays.asList("daily", "buddy", "diary");
    static final Map<String, String> KIND_LABEL = new LinkedHashMap<String, String>();
    static final Map<String, String> CATEGORY_LABEL = new LinkedHashMap<String, String>();
    /** 找搭子「同行性别」：'' 不限 / female 仅限女生 / male 仅限男生（kg_post.gender_limit） */
    static final Map<String, String> GENDER_LIMIT_LABEL = new LinkedHashMap<String, String>();
    /** 旅居日常的「伙伴说」（kg_post.tone，2026-10-11 内测建议）：'' 不选 / recommend 推荐 / avoid 避雷，卡片和详情上显示成小标签 */
    static final Map<String, String> TONE_LABEL = new LinkedHashMap<String, String>();
    /**
     * 经验分享（diary）按关联经历的岗位类型分的小类（2026-10-16 社区改版，主人原话：换宿旅行 / 线下兼职 / 全职）。
     * 不加列：diary 帖子一定关联一条本人已完成的报名（ref_type='application'），类型顺着 ref_id → kg_application → kg_job.job_type 查。
     * 顺序就是社区「经验分享」下面那行小字的顺序。线上任务不算旅居经验，不在这里。
     */
    static final Map<String, String> DIARY_JOB_LABEL = new LinkedHashMap<String, String>();
    static
    {
        TONE_LABEL.put("recommend", "推荐");
        TONE_LABEL.put("avoid", "避雷");
        KIND_LABEL.put("buddy", "找搭子");
        // 之前叫「换工日记」；2026-10-16 改名「经验分享」（只改名字，代码还是 diary，老帖子不用迁移），兼职、全职的经历也能发
        KIND_LABEL.put("diary", "经验分享");
        // 2026-10-17 主人拍板：「旅居日常」改名「旅居攻略」。只改显示的名字，代码还是 daily，老帖子自动变成旅居攻略
        KIND_LABEL.put("daily", "旅居攻略");
        DIARY_JOB_LABEL.put("volunteer", "换宿旅行");
        DIARY_JOB_LABEL.put("parttime", "线下兼职");
        DIARY_JOB_LABEL.put("fulltime", "全职");
        // 之前是 徒步登山 / 摄影 / 咖啡美食 / 骑行；现在按运营要的六类：新增拼饭（meal）、跑步（run），
        // food 的代码不变、名字改成「美食分享」，老帖子不用迁移。顺序就是找搭子页分类条的顺序
        CATEGORY_LABEL.put("hike", "徒步登山");
        CATEGORY_LABEL.put("photo", "摄影");
        CATEGORY_LABEL.put("meal", "拼饭");
        CATEGORY_LABEL.put("food", "美食分享");
        CATEGORY_LABEL.put("ride", "骑行");
        CATEGORY_LABEL.put("run", "跑步");
        CATEGORY_LABEL.put("other", "其他");
        GENDER_LIMIT_LABEL.put("", "不限");
        GENDER_LIMIT_LABEL.put("female", "仅限女生");
        GENDER_LIMIT_LABEL.put("male", "仅限男生");
    }

    @Autowired(required=false) private com.ruoyi.kangaroo.ext.user.KgMemberWallet memberWallet;
    @Autowired
    private KgJdbc db;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgActivityService activities;

    @Autowired
    private KgCityService cities;

    /** 2026-10-17 旅居攻略审核通过发积分。required = false：只用 mock 拼起来的单元测试里没有它 */
    @Autowired(required = false)
    private KgPointRewards pointRewards;

    /** 评论 / 点赞通知（2026-10-15 消息页改版）：带触发人写进「收到的评论」「收到的赞」 */
    @Autowired
    private IKgMessageService messages;

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

    static String md(Object t)
    {
        Date d = StaffSupport.parse(t);
        return d == null ? "" : StaffSupport.fmt(d, "M-dd");
    }

    static List<String> split(Object csv)
    {
        List<String> out = new ArrayList<String>();
        for (String s : str(csv).split(","))
        {
            if (!s.trim().isEmpty())
            {
                out.add(s.trim());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 作者身份标签

    public boolean tripVerified(Long uid, String city)
    {
        if (uid == null || city == null || city.isEmpty())
        {
            return false;
        }
        return db.count("select (exists(select 1 from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id where o.app_user_id = :u and s.city = :c "
                + "and o.status in ('confirmed','checked_in','completed')) "
                + "or exists(select 1 from kg_application a join kg_job j on j.job_id = a.job_id where a.app_user_id = :u and j.city = :c "
                + "and a.status in ('store_confirmed','contract_signed','onboarded','completed')) "
                + "or exists(select 1 from kg_activity_signup g join kg_activity t on t.activity_id = g.activity_id where g.app_user_id = :u and t.city = :c "
                + "and g.status = 'confirmed'))", p("u", uid, "c", city)) > 0;
    }

    @Autowired(required=false) private com.ruoyi.kangaroo.ext.user.KgMemberService memberIdentity;

    private Map<String, Object> author(Long uid, String city, Long me)
    {
        Map<String, Object> u = db.one("select user_id, nickname, avatar, phone_verified_time from kg_app_user where user_id = :u", p("u", uid));
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("userId", uid);
        a.put("name", u == null || str(u.get("nickname")).isEmpty() ? "用户" + uid : u.get("nickname"));
        a.put("avatar", u == null ? "" : str(u.get("avatar")));
        if(memberIdentity!=null)a.put("member",memberIdentity.identity(uid));
        a.put("phoneVerified", u != null && u.get("phoneVerifiedTime") != null);
        a.put("tripVerified", tripVerified(uid, city));
        a.put("followed", me != null && db.count("select count(*) from kg_user_follow where follower_id = :m and followee_id = :u", p("m", me, "u", uid)) > 0);
        a.put("isMe", me != null && me.equals(uid));
        return a;
    }

    /**
     * 客户端没传（或传了不认识的）分类时，按标题 / 正文 / 标签里的关键词推断；都对不上才是「其他」。
     * 发布页的分类默认「自动识别」（不传），不推断的话「找搭子」的分类筛选对用户自己发的帖子永远为空（全部落在 other）。
     * 按规则顺序取第一个命中的：拼饭排在美食分享前（「约饭探店」是拼饭）；跑步排在骑行前（「环湖夜跑」是跑步，「环湖」只是骑行的弱关键词）。
     * 之前「吃饭」算咖啡美食；现在约人一起吃饭归拼饭，美食分享只留咖啡 / 美食 / 探店 / 餐厅 / 饮品 / 烘焙。
     */
    static String inferCategory(String text)
    {
        String t = text == null ? "" : text;
        String[][] rules = { { "hike", "徒步,爬山,登山,露营,山野" }, { "photo", "摄影,拍照,拍摄,约拍,相机" },
                { "meal", "拼饭,搭饭,约饭,一起吃饭,饭搭子" }, { "food", "咖啡,美食,探店,餐厅,饮品,烘焙" },
                { "run", "跑步,夜跑,晨跑,慢跑,马拉松" }, { "ride", "骑行,单车,自行车,公路车,环湖,骑车" } };
        for (String[] r : rules)
        {
            for (String k : r[1].split(","))
            {
                if (t.contains(k))
                {
                    return r[0];
                }
            }
        }
        return "other";
    }

    /** 评论人（a 是表别名）和我之间任一方向有拉黑记录就看不到 */
    private static String commentNotBlocked(String a)
    {
        return "not exists (select 1 from kg_user_block b where (b.user_id = :me and b.blocked_id = " + a + ".app_user_id) "
                + "or (b.user_id = " + a + ".app_user_id and b.blocked_id = :me))";
    }

    /** 评论区里我看得到的一条（a 是表别名）：没删，可见或者是我自己待审核的（和 comments() 一样），评论人和我没有拉黑关系 */
    private static String commentSeen(String a)
    {
        return "(" + a + ".status = 'visible' or (" + a + ".status = 'pending' and " + a + ".app_user_id = :me)) and " + a + ".del_flag = '0' and "
                + commentNotBlocked(a);
    }

    /** 我看得到的评论 c：本身看得到，回复还要挂在看得到的一级评论下（被隐藏的评论下的回复评论区里不显示） */
    static final String COMMENT_VISIBLE_TO_ME = commentSeen("c") + " and (c.parent_id = 0 or exists (select 1 from kg_post_comment pc "
            + "where pc.comment_id = c.parent_id and " + commentSeen("pc") + "))";

    /**
     * 这个人看到的评论数（2026-10-15）：kg_post.comments 存的是全部可见评论，有拉黑关系时评论区里看不到对方的评论（以及挂在被隐藏评论下的回复），
     * 角标却还是全部，点进去数对不上。2026-10-16 起和评论区一样，我自己待审核的评论也算（评论区里我看得到）。
     * 没登录时直接用存的数，不多查；存的是 0 也要查：可能有我自己待审核的。单条动态（详情、评论区）用；列表一页一次查齐见 {@link #briefs}。
     */
    long visibleCommentCount(Long postId, Long me, long stored)
    {
        if (me == null || postId == null)
        {
            return stored;
        }
        return db.count("select count(*) from kg_post_comment c where c.post_id = :p and " + COMMENT_VISIBLE_TO_ME, p("p", postId, "me", me));
    }

    /** 我和别人之间有没有拉黑记录（任一方向）：列表每次请求查一次，没有就整页用存的评论数（再加上我自己待审核的） */
    private boolean hasBlocks(Long me)
    {
        return me != null && db.count("select count(*) from (select 1 from kg_user_block where user_id = :me or blocked_id = :me limit 1) x", p("me", me)) > 0;
    }

    /**
     * 一页帖子卡片（信息流、找搭子、个人主页、收藏、我的发布）：我点过赞没有、我看到的评论数，一页各一次查齐（2026-10-16）。
     * 之前每张卡片各查一次点赞表、有拉黑关系时再各数一次评论，一页 20 张就是 20～40 次查询。
     * 评论数：没登录用存的数；我和谁都没有拉黑记录时，存的数加上我自己待审核的（分组数一次）；有拉黑记录时按评论区口径整页分组数一次。
     */
    private List<Map<String, Object>> briefs(List<Map<String, Object>> rows, Long me)
    {
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> r : rows)
        {
            Long id = KgJdbc.asLong(r.get("postId"));
            if (id != null && !ids.contains(id))
            {
                ids.add(id);
            }
        }
        java.util.Set<Long> liked = new java.util.HashSet<Long>();
        Map<Long, Long> counted = new java.util.HashMap<Long, Long>();
        boolean viewerBlocks = false;
        if (me != null && !ids.isEmpty())
        {
            for (Map<String, Object> l : db.list("select post_id from kg_post_like where app_user_id = :me and post_id in (:ids)", p("me", me, "ids", ids)))
            {
                Long id = KgJdbc.asLong(l.get("postId"));
                if (id != null)
                {
                    liked.add(id);
                }
            }
            viewerBlocks = hasBlocks(me);
            // 有拉黑记录：按评论区口径数全部；没有：只数我自己待审核的（回复要挂在看得到的一级评论下，和评论区一样），加到存的数上
            String where = viewerBlocks ? COMMENT_VISIBLE_TO_ME
                    : "c.app_user_id = :me and c.status = 'pending' and c.del_flag = '0' and (c.parent_id = 0 or exists (select 1 from kg_post_comment pc "
                            + "where pc.comment_id = c.parent_id and (pc.status = 'visible' or (pc.status = 'pending' and pc.app_user_id = :me)) and pc.del_flag = '0'))";
            for (Map<String, Object> c : db.list("select c.post_id, count(*) n from kg_post_comment c where c.post_id in (:ids) and " + where
                    + " group by c.post_id", p("me", me, "ids", ids)))
            {
                Long id = KgJdbc.asLong(c.get("postId"));
                if (id != null)
                {
                    counted.put(id, num(c.get("n")));
                }
            }
        }
        Map<Long, String> diaryJobs = diaryJobTypes(rows);
        // 关联的小院 / 门店 / 岗位 / 活动（2026-10-17）：一页按类型各查一次，不逐条查
        Map<Long, Map<String, Object>> linked = links(rows);
        Map<Long, Map<String, Object>> activityStates = activityStates(rows, me);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : rows)
        {
            Long id = KgJdbc.asLong(r.get("postId"));
            Map<String, Object> m = brief(r, me, liked.contains(id), diaryJobs);
            m.put("link", linked.get(id));
            m.put("activity", activityStates.get(KgJdbc.asLong(r.get("activityId"))));
            if (me != null)
            {
                long n = counted.containsKey(id) ? counted.get(id).longValue() : 0L;
                m.put("comments", viewerBlocks ? n : num(r.get("comments")) + n);
            }
            out.add(m);
        }
        return out;
    }

    /** 单条动态（详情）：我点过赞没有单独查一次 */
    private Map<String, Object> brief(Map<String, Object> r, Long me)
    {
        Long id = KgJdbc.asLong(r.get("postId"));
        Map<String, Object> m = brief(r, me, me != null && db.count("select count(*) from kg_post_like where post_id = :p and app_user_id = :u", p("p", id, "u", me)) > 0,
                diaryJobTypes(java.util.Collections.singletonList(r)));
        m.put("link", links(java.util.Collections.singletonList(r)).get(id));
        m.put("activity", activityStates(java.util.Collections.singletonList(r), me).get(KgJdbc.asLong(r.get("activityId"))));
        return m;
    }

    /** 活动与动态分别审核：一起返回真实状态，避免把动态待审核误读为活动未通过。 */
    private Map<Long, Map<String, Object>> activityStates(List<Map<String, Object>> rows, Long me)
    {
        java.util.Set<Long> ids = new java.util.HashSet<Long>();
        for (Map<String, Object> r : rows) {
            Long id = KgJdbc.asLong(r.get("activityId"));
            if (id != null) ids.add(id);
        }
        Map<Long, Map<String, Object>> out = new java.util.HashMap<Long, Map<String, Object>>();
        if (ids.isEmpty()) return out;
        for (Map<String, Object> a : db.list("select activity_id, app_user_id, source, status, start_time, end_time, deadline, signed, capacity "
                + "from kg_activity where activity_id in (:ids) and del_flag = '0'", p("ids", ids))) {
            boolean mine = me != null && me.equals(KgJdbc.asLong(a.get("appUserId"))) && "user".equals(str(a.get("source")));
            if (!mine && Arrays.asList("pending", "rejected").contains(str(a.get("status")))) continue;
            String phase = KgActivityService.phase(a);
            out.put(KgJdbc.asLong(a.get("activityId")), p("activityId", a.get("activityId"), "status", a.get("status"),
                    "label", KgActivityService.phaseLabel(phase), "isMine", mine,
                    "canCancel", mine && Arrays.asList("pending", "open", "paused", "closed").contains(str(a.get("status")))));
        }
        return out;
    }

    /**
     * 经验分享帖关联经历的岗位类型（报名编号 → job_type），一页一次查齐（不逐条查）。
     * 只看 kind = diary 且关联的是岗位报名的帖子；一条都没有时不查库。
     */
    private Map<Long, String> diaryJobTypes(List<Map<String, Object>> rows)
    {
        List<Long> appIds = new ArrayList<Long>();
        for (Map<String, Object> r : rows)
        {
            Long ref = KgJdbc.asLong(r.get("refId"));
            if ("diary".equals(str(r.get("kind"))) && "application".equals(str(r.get("refType"))) && ref != null && !appIds.contains(ref))
            {
                appIds.add(ref);
            }
        }
        Map<Long, String> out = new java.util.HashMap<Long, String>();
        if (appIds.isEmpty())
        {
            return out;
        }
        for (Map<String, Object> a : db.list("select a.app_id, j.job_type from kg_application a join kg_job j on j.job_id = a.job_id where a.app_id in (:ids)",
                p("ids", appIds)))
        {
            Long appId = KgJdbc.asLong(a.get("appId"));
            if (appId != null)
            {
                out.put(appId, str(a.get("jobType")));
            }
        }
        return out;
    }

    /**
     * 卡片标题前面的小话题（「话题｜标题」，2026-10-16 社区改版）：
     * 经验分享 = 关联经历的岗位类型（换宿旅行 / 线下兼职 / 全职）；找搭子 = 分类名；旅居日常 = 第一个标签（没有标签就空着，不硬塞「旅居日常」）。
     * 认不出来的一律给空串，前端只显示标题。
     */
    static String topicLabel(Map<String, Object> r, Map<Long, String> diaryJobs)
    {
        String kind = str(r.get("kind"));
        if ("diary".equals(kind))
        {
            String jt = "application".equals(str(r.get("refType"))) && diaryJobs != null ? diaryJobs.get(KgJdbc.asLong(r.get("refId"))) : null;
            return jt != null && DIARY_JOB_LABEL.containsKey(jt) ? DIARY_JOB_LABEL.get(jt) : "";
        }
        if ("buddy".equals(kind))
        {
            // 2026-10-17：分类是「其他」（自动识别没命中时落在这里）不当话题，卡片上之前成了「其他｜酒吧」，主人嫌难看；只显示标题。
            // 找搭子列表上的分类小标签（categoryLabel）照旧是「其他」
            String cat = "other".equals(str(r.get("category"))) ? null : CATEGORY_LABEL.get(str(r.get("category")));
            return cat == null ? "" : cat;
        }
        if ("daily".equals(kind))
        {
            for (String t : split(r.get("tags")))
            {
                String tag = t.replaceAll("^[#＃]+", "").trim();
                if (!tag.isEmpty())
                {
                    return tag.length() > 10 ? tag.substring(0, 10) : tag;
                }
            }
        }
        return "";
    }

    private Map<String, Object> brief(Map<String, Object> r, Long me, boolean liked, Map<Long, String> diaryJobs)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Long id = KgJdbc.asLong(r.get("postId"));
        m.put("postId", id);
        m.put("kind", r.get("kind"));
        m.put("kindLabel", KIND_LABEL.get(str(r.get("kind"))));
        m.put("topicLabel", topicLabel(r, diaryJobs));
        m.put("city", r.get("city"));
        m.put("title", r.get("title"));
        m.put("content", r.get("content"));
        m.put("images", split(r.get("images")));
        m.put("tags", split(r.get("tags")));
        m.put("dateText", md(r.get("createTime")));
        m.put("likes", num(r.get("likes")));
        m.put("comments", num(r.get("comments")));
        m.put("favorites", num(r.get("favorites")));
        m.put("status", r.get("status"));
        // 找搭子的同行性别（信息流 / 找搭子列表 / 详情 / 我的发布都带上）；其他类型恒为 '' 不限
        String gl = str(r.get("genderLimit"));
        m.put("genderLimit", GENDER_LIMIT_LABEL.containsKey(gl) ? gl : "");
        m.put("genderLimitLabel", GENDER_LIMIT_LABEL.containsKey(gl) ? GENDER_LIMIT_LABEL.get(gl) : "不限");
        m.put("pinned", "1".equals(str(r.get("pinned"))));
        String tone = str(r.get("tone"));
        m.put("tone", TONE_LABEL.containsKey(tone) ? tone : "");
        m.put("toneLabel", TONE_LABEL.containsKey(tone) ? TONE_LABEL.get(tone) : "");
        m.put("liked", liked);
        m.put("author", author(KgJdbc.asLong(r.get("appUserId")), str(r.get("city")), me));
        return m;
    }

    private String activeActivityClause() { return " and not exists (select 1 from kg_activity hidden_activity where (hidden_activity.activity_id = p.activity_id or (p.ref_type = 'activity' and hidden_activity.activity_id = p.ref_id)) and hidden_activity.status = 'cancelled')"; }

    private String visibleClause(Long me)
    {
        // 自己的待审核内容自己看得到；被我拉黑 / 拉黑我的人的内容不出现
        return "(p.status = 'online'" + (me == null ? ")" : " or (p.status = 'pending' and p.app_user_id = :me))")
                + (me == null ? "" : " and not exists (select 1 from kg_user_block b where (b.user_id = :me and b.blocked_id = p.app_user_id) "
                        + "or (b.user_id = p.app_user_id and b.blocked_id = :me))");
    }

    // ---------------------------------------------------------------- 信息流 / 找搭子

    public Map<String, Object> feed(Long me, String kind, String city, String sort, int page, String keyword)
    {
        return feed(me, kind, city, sort, page, keyword, null);
    }

    /**
     * jobType（2026-10-16 社区改版）：只在 kind = diary（经验分享）时生效，按关联经历的岗位类型筛
     * volunteer 换宿旅行 / parttime 线下兼职 / fulltime 全职；别的类型、不认识的值一律不筛。
     */
    public Map<String, Object> feed(Long me, String kind, String city, String sort, int page, String keyword, String jobType)
    {
        // 选中的是区县（思明区）时按所属城市（厦门）查：帖子、房源、活动存的都是城市名
        city = cities.businessCity(city);
        // 页码夹到 10 万：匿名接口传 page=107374184 时 (page-1)*20 会 int 溢出成负数，SQL 语法错 → 500
        Map<String, Object> ps = p("me", me, "off", (Math.min(Math.max(page, 1), 100000) - 1) * 20);
        StringBuilder where = new StringBuilder(" where p.del_flag = '0' and " + visibleClause(me));
        where.append(activeActivityClause());
        if (keyword != null && !keyword.trim().isEmpty())
        {
            // 搜「%」「_」之前会匹配所有动态：通配符按字面找（KgLike 转义，参数绑定）
            where.append(" and (p.title like :kw or p.content like :kw or p.tags like :kw)");
            ps.put("kw", com.ruoyi.kangaroo.ext.KgLike.contains(keyword.trim()));
        }
        if (KINDS.contains(kind))
        {
            where.append(" and p.kind = :k");
            ps.put("k", kind);
        }
        if ("diary".equals(kind) && jobType != null && DIARY_JOB_LABEL.containsKey(jobType))
        {
            where.append(" and p.ref_type = 'application' and exists (select 1 from kg_application ja join kg_job jj on jj.job_id = ja.job_id "
                    + "where ja.app_id = p.ref_id and jj.job_type = :jt)");
            ps.put("jt", jobType);
        }
        // 「已关注」（2026-10-11 内测建议）：只看我关注的人发的动态，不分城市（关注的人在哪个城市发的都看得到）；没登录没有关注的人，直接返回空
        boolean following = "follow".equals(sort);
        if (following && me == null)
        {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("rows", new ArrayList<Object>());
            empty.put("hots", page <= 1 ? activities.hots(city) : new ArrayList<Object>());
            empty.put("hasMore", false);
            return empty;
        }
        if (following)
        {
            where.append(" and exists (select 1 from kg_user_follow f where f.follower_id = :me and f.followee_id = p.app_user_id)");
        }
        else if (city != null && !city.isEmpty() && !"全部城市".equals(city))
        {
            where.append(" and p.city = :c");
            ps.put("c", city);
        }
        String order = following ? "p.post_id desc" : "hot".equals(sort) ? "p.pinned desc, (p.likes * 2 + p.comments * 3 + p.favorites * 2) desc, p.post_id desc" : "p.pinned desc, p.post_id desc";
        if(memberWallet != null && memberWallet.enabled()){
            String priority="";
            if("recommend".equals(sort)||sort==null||sort.isEmpty())priority="exists(select 1 from kg_member_level ml where ml.level_id=6 and ml.min_points<=(select coalesce(sum("+com.ruoyi.kangaroo.ext.user.KgPointLedger.EARNED_EXPR+"),0) from kg_point_entry where app_user_id=p.app_user_id) and not exists(select 1 from kg_member_restriction mr where mr.app_user_id=p.app_user_id and (mr.frozen='1' or mr.level_penalty>0))) desc, ";
            order="exists(select 1 from kg_member_pin mp where mp.post_id=p.post_id and mp.ends_at>now()) desc, "+priority+order;
        }
        List<Map<String, Object>> rows = db.list("select p.* from kg_post p" + where + " order by " + order + " limit :off, 20", ps);
        List<Map<String, Object>> out = briefs(rows, me);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", out);
        res.put("hots", page <= 1 ? activities.hots(city) : new ArrayList<Object>());
        res.put("hasMore", rows.size() == 20);
        return res;
    }

    static String activityPhaseSql(String phase) {
        String end="coalesce(ap.end_time,date_add(ap.start_time,interval 4 hour))";
        if("ended".equals(phase))return "(ap.status='ended' or (ap.status in ('open','paused','closed') and "+end+"<=now()))";
        if("ongoing".equals(phase))return "ap.status in ('open','paused','closed') and ap.start_time<=now() and "+end+">now()";
        if("open".equals(phase))return "ap.status='open' and ap.start_time>now() and (ap.deadline is null or ap.deadline>now()) and ap.signed<ap.capacity";
        return "";
    }

    /**
     * gender = female / male 表示「这个性别能参加的」：不限性别的帖子 + 限定为该性别的帖子；不传或传别的值不过滤。
     */
    public Map<String, Object> buddies(Long me, String city, String date, String days, String category, boolean verifiedOnly, String gender)
    {
        return buddies(me, city, date, days, category, verifiedOnly, gender, null);
    }

    /** keyword（2026-10-16）：社区搜索框在「找搭子」页签下也能搜，按标题 / 正文 / 标签找，和信息流同一口径 */
    public Map<String, Object> buddies(Long me, String city, String date, String days, String category, boolean verifiedOnly, String gender, String keyword)
    {
        return buddies(me,city,date,days,category,verifiedOnly,gender,keyword,null);
    }

    public Map<String,Object> buddies(Long me,String city,String date,String days,String category,boolean verifiedOnly,String gender,String keyword,String activityPhase)
    {
        city = cities.businessCity(city);
        Map<String, Object> ps = p("me", me);
        StringBuilder where = new StringBuilder(" where p.del_flag = '0' and p.kind = 'buddy' and " + visibleClause(me)
                + activeActivityClause());
        String phaseSql=activityPhaseSql(activityPhase);
        if(phaseSql.isEmpty())where.append(" and (p.event_date is null or p.event_date >= curdate())");
        else where.append(" and exists(select 1 from kg_activity ap where ap.activity_id=p.activity_id and ap.del_flag='0' and "+phaseSql+")");
        if (keyword != null && !keyword.trim().isEmpty())
        {
            where.append(" and (p.title like :kw or p.content like :kw or p.tags like :kw)");
            ps.put("kw", com.ruoyi.kangaroo.ext.KgLike.contains(keyword.trim()));
        }
        if (city != null && !city.isEmpty() && !"全部城市".equals(city))
        {
            where.append(" and p.city = :c");
            ps.put("c", city);
        }
        if (date != null && KgJdbc.isDate(date))
        {
            where.append(" and p.event_date = :d");
            ps.put("d", date);
        }
        if (days != null && !days.isEmpty())
        {
            where.append(" and p.event_days = :days");
            ps.put("days", days);
        }
        if (CATEGORY_LABEL.containsKey(category))
        {
            where.append(" and p.category = :cat");
            ps.put("cat", category);
        }
        if ("female".equals(gender) || "male".equals(gender))
        {
            where.append(" and p.gender_limit in ('', :g)");
            ps.put("g", gender);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> rows = db.list("select p.*, a.signed act_signed, a.place act_place, a.start_time act_start from kg_post p "
                + "left join kg_activity a on a.activity_id = p.activity_id" + where + " order by p.event_date is null, p.event_date, p.post_id desc limit 50", ps);
        List<Map<String, Object>> cards = briefs(rows, me);
        for (int i = 0; i < rows.size(); i++)
        {
            Map<String, Object> r = rows.get(i);
            Map<String, Object> m = cards.get(i);
            Map<String, Object> au = (Map<String, Object>) m.get("author");
            if (verifiedOnly && !Boolean.TRUE.equals(au.get("tripVerified")) && !Boolean.TRUE.equals(au.get("phoneVerified")))
            {
                continue;
            }
            Date start = StaffSupport.parse(r.get("actStart"));
            m.put("timeText", start != null ? StaffSupport.fmt(start, "M月d日 HH:mm") : (r.get("eventDate") == null ? "日期待定" : StaffSupport.fmt(StaffSupport.parse(r.get("eventDate")), "M月d日")));
            m.put("place", r.get("actPlace") != null ? r.get("actPlace") : r.get("city"));
            m.put("days", str(r.get("eventDays")).isEmpty() ? "待定" : r.get("eventDays"));
            m.put("count", num(r.get("actSigned")));
            m.put("activityId", r.get("activityId"));
            m.put("avatars", r.get("activityId") == null ? new ArrayList<String>() : activities.avatars(KgJdbc.asLong(r.get("activityId")), 4));
            m.put("categoryLabel", CATEGORY_LABEL.get(str(r.get("category"))));
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", out);
        res.put("categories", CATEGORY_LABEL);
        return res;
    }

    // ---------------------------------------------------------------- 详情 / 互动

    private Map<String, Object> visiblePost(Long id, Long me)
    {
        Map<String, Object> r = db.one("select p.* from kg_post p where p.post_id = :id and p.del_flag = '0' and " + visibleClause(me), p("id", id, "me", me));
        if (r == null)
        {
            throw new ServiceException("动态不存在或已被隐藏", HttpStatus.NOT_FOUND.value());
        }
        return r;
    }

    public Map<String, Object> detail(Long id, Long me)
    {
        Map<String, Object> r = visiblePost(id, me);
        db.update("update kg_post set views = views + 1 where post_id = :id", p("id", id));
        Map<String, Object> m = brief(r, me);
        // 角标和评论区对得上：有拉黑关系时只数我看得到的评论
        m.put("comments", visibleCommentCount(id, me, num(r.get("comments"))));
        m.put("favorited",me != null && db.count("select count(*) from kg_favorite where target_type = 'post' and target_id = :id and app_user_id = :u",
                p("id", id, "u", me)) > 0);
        m.put("createTimeText", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "M月d日"));
        m.put("auditReason", "rejected".equals(str(r.get("status"))) ? r.get("auditReason") : null);
        m.put("related", related(str(r.get("city"))));
        m.put("activityId", r.get("activityId"));
        return m;
    }

    /** 关联推荐：同城的一个在招岗位、一个在线房源、一个报名中的活动（都是真实数据，没有就少一张） */
    private List<Map<String, Object>> related(String city)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Map<String, Object> job = db.one("select j.job_id, j.title, j.city, j.cover, j.salary_min, j.salary_max, j.salary_unit, j.job_type from kg_job j where j.city = :c and j.status = 'published' "
                + "and j.del_flag = '0' and exists (select 1 from kg_store st where st.store_id = j.store_id and st.status = '0' and st.del_flag = '0') order by j.job_id desc limit 1", p("c", city));
        if (job != null)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("kind", "招聘");
            m.put("tone", "blue");
            m.put("title", job.get("title"));
            m.put("meta", city + (job.get("salaryMin") == null ? "" : " · " + job.get("salaryMin") + "-" + job.get("salaryMax") + str(job.get("salaryUnit"))));
            m.put("status", "在招");
            m.put("statusTone", "green");
            m.put("img", job.get("cover"));
            m.put("to", "/pages/jobs/detail?jobId=" + job.get("jobId"));
            out.add(m);
        }
        Map<String, Object> stay = db.one("select s.stay_id, s.stay_name, s.cover, s.area, (select min(" + com.ruoyi.kangaroo.ext.stay.KgSettlementMath.saleSql("t.base_price_fen", "st.op_type") + ") from kg_stay_room_type t where t.stay_id = s.stay_id "
                + "and t.status = '0' and t.del_flag = '0') price from kg_stay s left join kg_store st on st.store_id=s.store_id where s.city = :c and " + KgStayBrowseService.ONLINE + " order by s.sort, s.stay_id limit 1", p("c", city));
        if (stay != null)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("kind", "房源");
            m.put("tone", "green");
            m.put("title", stay.get("stayName"));
            m.put("price", stay.get("price") == null ? null : BigDecimal.valueOf(num(stay.get("price")), 2).stripTrailingZeros().toPlainString());
            m.put("meta", stay.get("price") == null ? str(stay.get("area")) : " 元/晚起");
            m.put("status", "可预订");
            m.put("statusTone", "green");
            m.put("img", stay.get("cover"));
            m.put("to", "/pages/stays/detail?stayId=" + stay.get("stayId"));
            out.add(m);
        }
        Map<String, Object> act = db.one("select activity_id, title, cover, start_time from kg_activity where city = :c and status = 'open' and del_flag = '0' "
                + "and start_time > now() order by pinned desc, start_time limit 1", p("c", city));
        if (act != null)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("kind", "活动");
            m.put("tone", "blue");
            m.put("title", act.get("title"));
            m.put("meta", StaffSupport.fmt(StaffSupport.parse(act.get("startTime")), "M/d HH:mm") + " 集合");
            m.put("status", "报名中");
            m.put("statusTone", "ochre");
            m.put("img", act.get("cover"));
            m.put("to", "/pages/community/activity?id=" + act.get("activityId"));
            out.add(m);
        }
        return out;
    }

    /**
     * 同一个人的「切换」类操作（点赞 / 收藏）先串行：锁他自己的账号行。
     * 连点会同时进来两个请求，各自看到「没点过」/「点过」，再去插入 / 删除同一行，在唯一索引上互相等待 → 死锁，返回 500。
     * 锁的是这个人自己的行，不同用户之间不互相阻塞。
     */
    private void lockToggler(Long me)
    {
        AccountState.requireActive(db, me);
    }

    /**
     * 2026-10-17 主人反馈「发帖审核中时点赞和收藏还有评论都不能用」：只有已发布（online）的帖子能点赞 / 收藏 / 评论 / 给评论点赞。
     * 审核中的帖子只有作者自己看得到，之前作者自己能点赞、收藏，计数和通知都会留下来；驳回 / 隐藏的同理（它们本来就打不开，这里兜底）。
     * 返回 null = 可以互动；否则是 409 的提示语。取消（删掉已有的赞 / 收藏）照旧放行，只减不加、不发通知。
     * 线上老版本小程序按钮没灰掉，点了只会弹出这句提示。
     */
    static String interactBlockedMessage(Object status)
    {
        String s = str(status);
        if ("online".equals(s))
        {
            return null;
        }
        if ("pending".equals(s))
        {
            return "帖子还在审核中，通过后才能点赞、收藏和评论";
        }
        if ("rejected".equals(s))
        {
            return "帖子未通过审核，不能点赞、收藏和评论";
        }
        return "帖子已下架，不能点赞、收藏和评论";
    }

    /**
     * 点赞 / 取消点赞。读已提交（2026-10-15）：点赞通知「同一条动态只占一行」靠上面 kg_post 计数那一行的行锁排队，
     * 排在后面的请求拿到锁以后要读得到前一个刚提交的通知行；可重复读下普通 select 读的是事务开头的快照，会再插出一行。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> like(Long id, Long me)
    {
        Map<String, Object> r = visiblePost(id, me);
        Long authorId = KgJdbc.asLong(r.get("appUserId"));
        guard.requireNotBlocked(me, authorId);
        lockToggler(me);
        boolean on;
        // 2026-10-17：没发布的帖子不插赞，只能取消已有的；一个都没有就 409
        String blocked = interactBlockedMessage(r.get("status"));
        String route = "/pages/community/index?postId=" + id + "&view=comments";
        // 先插后删：先 delete 再 insert 时，两个不同的人同时操作会在唯一索引的间隙上互相等待 → 死锁（500）。
        // 插入成功 = 原来没点过 → 点赞；没插进去 = 已经点过 → 取消。计数只在行真的变化时才动（insert ignore 没插进去也 +1 会让计数漂）
        if (blocked == null && db.update("insert ignore into kg_post_like (post_id, app_user_id, create_time) values (:p, :u, now())", p("p", id, "u", me)) > 0)
        {
            db.update("update kg_post set likes = likes + 1 where post_id = :p", p("p", id));
            // 通知必须在计数更新（锁住这条动态）之后写：同一条动态的点赞在这里排队，合并成一行
            String label = str(r.get("title")).isEmpty() ? StaffSupport.cut(str(r.get("content")), 30) : str(r.get("title"));
            messages.upsertLike(authorId, "post_like", id, me, label, route);
            on = true;
        }
        else
        {
            if (db.update("delete from kg_post_like where post_id = :p and app_user_id = :u", p("p", id, "u", me)) > 0)
            {
                db.update("update kg_post set likes = greatest(likes - 1, 0) where post_id = :p", p("p", id));
                messages.retractLike(authorId, "post_like", id, me);
            }
            else if (blocked != null)
            {
                throw new ServiceException(blocked, 409);
            }
            on = false;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("liked", on);
        m.put("likes", num(db.one("select likes from kg_post where post_id = :p", p("p", id)).get("likes")));
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> favorite(Long id, Long me)
    {
        Map<String, Object> r = visiblePost(id, me);
        lockToggler(me);
        boolean on;
        // 2026-10-17：和点赞一样，没发布的帖子只能取消收藏，不能新收藏
        String blocked = interactBlockedMessage(r.get("status"));
        if (blocked == null && db.update("insert ignore into kg_favorite (target_type, target_id, app_user_id, create_time) values ('post', :p, :u, now())", p("p", id, "u", me)) > 0)
        {
            db.update("update kg_post set favorites = favorites + 1 where post_id = :p", p("p", id));
            on = true;
        }
        else
        {
            if (db.update("delete from kg_favorite where target_type = 'post' and target_id = :p and app_user_id = :u", p("p", id, "u", me)) > 0)
            {
                db.update("update kg_post set favorites = greatest(favorites - 1, 0) where post_id = :p", p("p", id));
            }
            else if (blocked != null)
            {
                throw new ServiceException(blocked, 409);
            }
            on = false;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("favorited", on);
        return m;
    }

    public Map<String, Object> follow(Long target, Long me)
    {
        if (target == null || target.equals(me))
        {
            throw new ServiceException("不能关注自己");
        }
        guard.requireNotBlocked(me, target);
        boolean on;
        if (db.update("delete from kg_user_follow where follower_id = :m and followee_id = :t", p("m", me, "t", target)) > 0)
        {
            on = false;
        }
        else
        {
            db.update("insert ignore into kg_user_follow (follower_id, followee_id, create_time) values (:m, :t, now())", p("m", me, "t", target));
            on = true;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("followed", on);
        return m;
    }

    // ---------------------------------------------------------------- 个人主页 / 我关注的人 / 我收藏的帖子（2026-10-11 内测建议）

    /**
     * 用户的公开主页：头像、昵称、「手机已验证」、动态 / 关注 / 粉丝数、和我的关注关系、能不能私信 TA，以及 TA 最近的公开动态（每页 20 条）。
     * 之前点帖子作者的头像、名字没有任何反应，看不了 TA 发过什么，也没法从这里私信。
     * 账号不存在 / 已注销 / 已停用 → 404；TA 拉黑了我 → 同样 404（不透露被拉黑）；我拉黑了 TA → 照常返回资料（blocked=true），不列动态，方便取消屏蔽。
     */
    public Map<String, Object> userProfile(Long uid, Long me, Integer page)
    {
        Map<String, Object> u = uid == null ? null : db.one("select user_id, nickname, avatar, phone_verified_time, status, del_flag, intro, city from kg_app_user where user_id = :u", p("u", uid));
        if (u == null || !"0".equals(str(u.get("status"))) || !"0".equals(str(u.get("delFlag"))))
        {
            throw new ServiceException("用户不存在或已注销", 404);
        }
        boolean isMe = me != null && me.equals(uid);
        if (!isMe && me != null && db.count("select count(*) from kg_user_block where user_id = :u and blocked_id = :m", p("u", uid, "m", me)) > 0)
        {
            throw new ServiceException("暂时看不到对方的主页", 404);
        }
        boolean blocked = !isMe && me != null && db.count("select count(*) from kg_user_block where user_id = :m and blocked_id = :u", p("m", me, "u", uid)) > 0;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("userId", uid);
        m.put("name", str(u.get("nickname")).isEmpty() ? "用户" + uid : u.get("nickname"));
        m.put("avatar", str(u.get("avatar")));
        m.put("intro", str(u.get("intro")));
        m.put("city", str(u.get("city")));
        if(memberIdentity!=null)m.put("member",memberIdentity.identity(uid));
        m.put("phoneVerified", u.get("phoneVerifiedTime") != null);
        m.put("isMe", isMe);
        m.put("blocked", blocked);
        boolean followed = !isMe && guard.follows(me, uid);
        boolean followsMe = !isMe && guard.follows(uid, me);
        m.put("followed", followed);
        m.put("followsMe", followsMe);
        m.put("mutual", followed && followsMe);
        m.put("postCount", db.count("select count(*) from kg_post where app_user_id = :u and status = 'online' and del_flag = '0'", p("u", uid)));
        m.put("followingCount", db.count("select count(*) from kg_user_follow where follower_id = :u", p("u", uid)));
        m.put("followerCount", db.count("select count(*) from kg_user_follow where followee_id = :u", p("u", uid)));
        m.put("appreciationCount", db.count("select coalesce(sum(coalesce(likes,0) + coalesce(favorites,0)),0) from kg_post where app_user_id = :u and status = 'online' and del_flag = '0'", p("u", uid)));
        // 能不能私信：自己不行；有拉黑关系不行；TA 的「谁可以私信我」不允许我的，给出原因（发起时服务端还会再判一次）
        String dmReason = isMe ? "这是你自己" : (me == null ? null : (blocked ? "你已屏蔽对方，取消屏蔽后才能私信" : guard.dmRefusal(me, uid)));
        m.put("canMessage", !isMe && dmReason == null);
        m.put("dmReason", dmReason);
        int size = 20;
        int offset = (Math.min(Math.max(page == null ? 1 : page, 1), 100000) - 1) * size;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (!blocked)
        {
            rows.addAll(briefs(db.list("select p.* from kg_post p where p.app_user_id = :u and p.del_flag = '0' and " + visibleClause(me) + activeActivityClause()
                    + " order by p.post_id desc limit :size offset :off", p("u", uid, "me", me, "size", size, "off", offset)), me));
        }
        m.put("posts", rows);
        m.put("hasMore", rows.size() == size);
        Map<String, Object> organized = !blocked && activities != null ? activities.profileActivities(uid, page) : null;
        m.put("activities", organized == null ? Collections.emptyList() : organized.get("rows"));
        m.put("activityCount", organized == null ? 0 : organized.get("total"));
        m.put("activitiesHasMore", organized != null && Boolean.TRUE.equals(organized.get("hasMore")));
        return m;
    }

    /** 我关注的人（「我的 → 我的关注 → 用户」）：按关注时间倒序，最多 200 个；已注销 / 停用的账号不列 */
    public List<Map<String, Object>> followingUsers(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select f.followee_id, f.create_time follow_time, u.nickname, u.avatar, u.phone_verified_time, "
                + "exists(select 1 from kg_user_follow b where b.follower_id = f.followee_id and b.followee_id = :me) follows_me "
                + "from kg_user_follow f join kg_app_user u on u.user_id = f.followee_id and u.status = '0' and u.del_flag = '0' "
                + "where f.follower_id = :me order by f.id desc limit 200", p("me", me)))
        {
            Long uid = KgJdbc.asLong(r.get("followeeId"));
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("userId", uid);
            v.put("name", str(r.get("nickname")).isEmpty() ? "用户" + uid : r.get("nickname"));
            v.put("avatar", str(r.get("avatar")));
            v.put("phoneVerified", r.get("phoneVerifiedTime") != null);
            v.put("mutual", num(r.get("followsMe")) > 0);
            Date t = StaffSupport.parse(r.get("followTime"));
            v.put("followTime", t == null ? "" : StaffSupport.fmt(t, "yyyy-MM-dd"));
            out.add(v);
        }
        return out;
    }

    /**
     * 我收藏的帖子（「我的 → 我的收藏 → 收藏的帖子」）：按收藏时间倒序，每页 20 条。
     * 之前帖子详情能点收藏（kg_favorite target_type = 'post'），但哪里都看不到收藏过的帖子；已删除 / 被隐藏 / 有拉黑关系的不列。
     */
    public Map<String, Object> favorites(Long me, Integer page)
    {
        int size = 20;
        int offset = (Math.min(Math.max(page == null ? 1 : page, 1), 100000) - 1) * size;
        List<Map<String, Object>> rows = briefs(db.list("select p.* from kg_favorite f join kg_post p on p.post_id = f.target_id "
                + "where f.target_type = 'post' and f.app_user_id = :me and p.del_flag = '0' and " + visibleClause(me)
                + " order by f.fav_id desc limit :size offset :off", p("me", me, "size", size, "off", offset)), me);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", rows);
        res.put("hasMore", rows.size() == size);
        return res;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> block(Long target, Long me)
    {
        if (target == null || target.equals(me))
        {
            throw new ServiceException("不能拉黑自己");
        }
        boolean on;
        if (db.update("delete from kg_user_block where user_id = :m and blocked_id = :t", p("m", me, "t", target)) > 0)
        {
            on = false;
        }
        else
        {
            db.update("insert ignore into kg_user_block (user_id, blocked_id, create_time) values (:m, :t, now())", p("m", me, "t", target));
            // 拉黑同时解除双方关注
            db.update("delete from kg_user_follow where (follower_id = :m and followee_id = :t) or (follower_id = :t and followee_id = :m)", p("m", me, "t", target));
            // 「晨光 等 3 人赞了你的动态」最近点赞的正好是被拉黑的人时，整行会跟着不显示（互动通知双方有拉黑就隐藏），
            // 另外两个人的赞也看不到了：改指剩下的人（一个不剩才删）。双方各自收到的、由对方触发的都改；必须在写入拉黑记录之后
            if (messages != null)
            {
                messages.relinkLikesBetween(me, target);
                messages.relinkLikesBetween(target, me);
            }
            on = true;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("blocked", on);
        return m;
    }

    /** 举报：post 动态 / comment 评论 / user 用户 / chat 会话。同一人对同一对象只记一次。 */
    public String report(Long me, String type, Long targetId, String reason, String detail)
    {
        if (!Arrays.asList("post", "comment", "user", "chat").contains(type) || targetId == null)
        {
            throw new ServiceException("举报对象不合法", 422);
        }
        // 举报对象必须真实存在，否则后台会堆一批点不开的孤儿举报（2026-09-26 回归发现）
        String exists = "post".equals(type) ? "select count(*) from kg_post where post_id = :id and del_flag = '0'"
                : ("comment".equals(type) ? "select count(*) from kg_post_comment where comment_id = :id"
                : ("user".equals(type) ? "select count(*) from kg_app_user where user_id = :id" : "select count(*) from kg_chat_conv where conv_id = :id"));
        if (db.count(exists, p("id", targetId)) == 0)
        {
            throw new ServiceException("举报的内容不存在或已删除", 404);
        }
        String rs = StaffSupport.cut(reason == null || reason.trim().isEmpty() ? "其他" : reason.trim(), 64);
        int n = db.update("insert ignore into kg_report (target_type, target_id, reporter_id, reason, detail, status, create_by, create_time) "
                + "values (:t, :id, :u, :r, :d, 'pending', :by, now())",
                p("t", type, "id", targetId, "u", me, "r", rs, "d", StaffSupport.cut(detail, 500), "by", "app:" + me));
        if (n > 0)
        {
            if ("post".equals(type))
            {
                db.update("update kg_post set reported = reported + 1 where post_id = :id", p("id", targetId));
            }
            else if ("comment".equals(type))
            {
                db.update("update kg_post_comment set reported = reported + 1 where comment_id = :id", p("id", targetId));
            }
            else if ("chat".equals(type))
            {
                db.update("update kg_chat_conv set reported = reported + 1 where conv_id = :id", p("id", targetId));
            }
        }
        return n > 0 ? "举报已提交，平台会在 24 小时内处理" : "你已经举报过了，平台正在处理";
    }

    // ---------------------------------------------------------------- 评论

    public Map<String, Object> comments(Long postId, Long me, String sort)
    {
        Map<String, Object> post = visiblePost(postId, me);
        Long authorId = KgJdbc.asLong(post.get("appUserId"));
        String vis = "(c.status = 'visible'" + (me == null ? ")" : " or (c.status = 'pending' and c.app_user_id = :me))")
                + (me == null ? "" : " and not exists (select 1 from kg_user_block b where (b.user_id = :me and b.blocked_id = c.app_user_id) "
                        + "or (b.user_id = c.app_user_id and b.blocked_id = :me))");
        String order = "new".equals(sort) ? "c.comment_id desc" : "c.likes desc, c.comment_id desc";
        List<Map<String, Object>> tops = db.list("select c.* from kg_post_comment c where c.post_id = :p and c.parent_id = 0 and c.del_flag = '0' and " + vis
                + " order by " + order + " limit 100", p("p", postId, "me", me));
        String city = str(post.get("city"));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> c : tops)
        {
            Map<String, Object> m = commentView(c, me, authorId, city);
            List<Map<String, Object>> replies = db.list("select c.* from kg_post_comment c where c.parent_id = :pid and c.del_flag = '0' and " + vis
                    + " order by c.comment_id limit 50", p("pid", c.get("commentId"), "me", me));
            List<Map<String, Object>> rv = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> r : replies)
            {
                rv.add(commentView(r, me, authorId, city));
            }
            m.put("replies", rv);
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", out);
        res.put("total", visibleCommentCount(postId, me, num(post.get("comments"))));
        res.put("authorId", authorId);
        return res;
    }

    private Map<String, Object> commentView(Map<String, Object> c, Long me, Long authorId, String city)
    {
        Long uid = KgJdbc.asLong(c.get("appUserId"));
        Map<String, Object> au = author(uid, city, me);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("commentId", c.get("commentId"));
        m.put("userId", uid);
        m.put("name", au.get("name"));
        m.put("avatar", au.get("avatar"));
        m.put("member",au.get("member"));
        m.put("isAuthor", uid.equals(authorId));
        m.put("phoneVerified", au.get("phoneVerified"));
        m.put("tripVerified", au.get("tripVerified"));
        m.put("content", c.get("content"));
        m.put("images", split(c.get("images")));
        m.put("likes", num(c.get("likes")));
        m.put("liked", me != null && db.count("select count(*) from kg_post_comment_like where comment_id = :c and app_user_id = :u",
                p("c", c.get("commentId"), "u", me)) > 0);
        m.put("pending", "pending".equals(str(c.get("status"))));
        m.put("dateText", md(c.get("createTime")));
        m.put("isMe", me != null && me.equals(uid));
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> comment(Long postId, Long me, Long parentId, String content)
    {
        return comment(postId, me, parentId, content, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> comment(Long postId, Long me, Long parentId, String content, Object imageValue)
    {
        AccountState.requireActive(db, me);
        List<String> images = new ArrayList<String>();
        if (imageValue != null) {
            if (!(imageValue instanceof List) || ((List<?>) imageValue).size() > 3)
                throw new ServiceException("评论最多添加3张图片", 422);
            for (Object value : (List<?>) imageValue) {
                if (!(value instanceof String) || !KgJdbc.isPlatformImage((String) value))
                    throw new ServiceException("请重新上传评论图片", 422);
                if (!images.contains(value)) images.add((String) value);
            }
        }
        Map<String, Object> post = visiblePost(postId, me);
        // 2026-10-17：提示语和点赞 / 收藏统一（之前是「动态还在审核中，暂不能评论」）
        String blocked = interactBlockedMessage(post.get("status"));
        if (blocked != null)
        {
            throw new ServiceException(blocked, 409);
        }
        String text = content == null ? "" : content.trim();
        if ((text.isEmpty() && images.isEmpty()) || text.length() > 500)
        {
            throw new ServiceException("请填写评论或添加图片，文字不超过500字", 422);
        }
        guard.requireConsent(me, "community");
        Long authorId = KgJdbc.asLong(post.get("appUserId"));
        guard.requireNotBlocked(me, authorId);
        Long replyUser = null;
        long parent = 0;
        if (parentId != null && parentId > 0)
        {
            Map<String, Object> pc = db.one("select comment_id, parent_id, app_user_id from kg_post_comment where comment_id = :c and post_id = :p and status = 'visible' and del_flag = '0'",
                    p("c", parentId, "p", postId));
            if (pc == null)
            {
                throw new ServiceException("回复的评论不存在", 404);
            }
            replyUser = KgJdbc.asLong(pc.get("appUserId"));
            guard.requireNotBlocked(me, replyUser);
            parent = num(pc.get("parentId")) > 0 ? num(pc.get("parentId")) : num(pc.get("commentId"));
        }
        // 评论不能留联系方式：命中 review 也走人工；联调模拟模式下 pass 直接可见
        String level = guard.check(text);
        String status = images.isEmpty() && "online".equals(guard.initialStatus(level)) ? "visible" : "pending";
        // 2026-10-16 内测反馈 2.0：review-mode=auto 时，本地检查通过、微信内容安全接口也判通过的评论直接展示；接口出错 / 超时照旧待审
        if (images.isEmpty() && "pending".equals(status) && guard.autoApprove(level, me, WxMsgSecCheck.SCENE_COMMENT, "", text))
        {
            status = "visible";
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("post_id", postId);
        row.put("app_user_id", me);
        row.put("parent_id", parent);
        row.put("reply_user_id", replyUser);
        row.put("images", String.join(",", images));
        row.put("content", text);
        row.put("status", status);
        row.put("create_time", new Date());
        long id = db.insert("kg_post_comment", row);
        if ("visible".equals(status))
        {
            db.update("update kg_post set comments = comments + 1 where post_id = :p", p("p", postId));
            notifyComment(postId, authorId, replyUser, me, id, text);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("commentId", id);
        m.put("status", status);
        m.put("message", "visible".equals(status) ? "评论已发布" : "评论已提交，审核通过后展示");
        return m;
    }

    /**
     * 评论点赞 / 取消。读已提交，理由同 {@link #like}：同一条评论的点赞靠 kg_post_comment 计数那一行的行锁排队，通知合并成一行。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> likeComment(Long commentId, Long me)
    {
        Map<String, Object> c = db.one("select comment_id, post_id, app_user_id, content from kg_post_comment where comment_id = :c and status = 'visible' and del_flag = '0'",
                p("c", commentId));
        if (c == null)
        {
            throw new ServiceException("评论不存在", 404);
        }
        // Comment IDs do not grant access to a hidden/deleted or blocked parent post.
        Map<String, Object> post = visiblePost(KgJdbc.asLong(c.get("postId")), me);
        guard.requireNotBlocked(me, KgJdbc.asLong(c.get("appUserId")));
        lockToggler(me);
        boolean on;
        Long commenter = KgJdbc.asLong(c.get("appUserId"));
        // 2026-10-17：帖子没发布（审核中 / 驳回 / 隐藏）时评论区也不能新点赞，只能取消已有的
        String blocked = interactBlockedMessage(post.get("status"));
        if (blocked == null && db.update("insert ignore into kg_post_comment_like (comment_id, app_user_id, create_time) values (:c, :u, now())", p("c", commentId, "u", me)) > 0)
        {
            db.update("update kg_post_comment set likes = likes + 1 where comment_id = :c", p("c", commentId));
            // 和动态点赞一样：计数更新（锁住这条评论）之后再写通知
            messages.upsertLike(commenter, "comment_like", commentId, me, StaffSupport.cut(str(c.get("content")), 30),
                    "/pages/community/index?postId=" + c.get("postId") + "&view=comments");
            on = true;
        }
        else
        {
            if (db.update("delete from kg_post_comment_like where comment_id = :c and app_user_id = :u", p("c", commentId, "u", me)) > 0)
            {
                db.update("update kg_post_comment set likes = greatest(likes - 1, 0) where comment_id = :c", p("c", commentId));
                messages.retractLike(commenter, "comment_like", commentId, me);
            }
            else if (blocked != null)
            {
                throw new ServiceException(blocked, 409);
            }
            on = false;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("liked", on);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public String deleteComment(Long commentId, Long me)
    {
        // 锁评论行：与后台「隐藏/恢复评论」串行，否则两边各自按读到的 status 加减评论数，计数会漂
        Map<String, Object> c = db.one("select comment_id, post_id, status from kg_post_comment where comment_id = :c and app_user_id = :u and del_flag = '0' for update",
                p("c", commentId, "u", me));
        if (c == null)
        {
            throw new ServiceException("只能删除自己的评论", 403);
        }
        int changed = db.update("update kg_post_comment set del_flag = '2', update_time = now() where comment_id = :c and del_flag = '0'", p("c", commentId));
        if (changed == 1 && "visible".equals(str(c.get("status"))))
        {
            db.update("update kg_post set comments = greatest(comments - 1, 0) where post_id = :p", p("p", c.get("postId")));
        }
        return "已删除";
    }

    // ---------------------------------------------------------------- 关联小院 / 门店 / 岗位 / 活动（2026-10-17 发布页改版）

    /**
     * 帖子能「关联」的公开对象（主人拍板）：stay 小院 / store 门店 / job 岗位 / activity 活动；不关联别人的帖子。
     * r1 不加列：存在原来的 ref_type（varchar(16)）/ ref_id 里。找搭子、旅居攻略的 ref 之前只能放自己的报名 / 住宿订单、哪里都不显示，
     * 新版发布页把这一项换成「关联帖子」；老版本小程序传 application / stay_order 照旧按 refs() 校验。
     * 经验分享的 ref 已经是经历校验那条报名（application），放不下第二个关联：自动关联到那条报名的岗位，岗位下架了退到门店（见 links）。
     * 2026-10-17 r2（V52，主人要求经验分享也能自己选）：关联单独存在 link_type / link_id（见 storedLink）。找搭子、旅居攻略照旧另写一份 ref_*（老版本程序还读它）；
     * 经验分享的 ref 照旧是那条报名，自己选了关联才写 link_*，没选照旧自动关联经历的岗位。
     * 顺序就是关联选择页里的类型小标签顺序。
     */
    static final Map<String, String> LINK_LABEL = new LinkedHashMap<String, String>();
    /** 岗位卡片上的小标签：换宿旅行 / 线下兼职 / 全职 / 线上任务（和岗位列表的四个页签同名） */
    static final Map<String, String> JOB_TYPE_LABEL = new LinkedHashMap<String, String>();
    static
    {
        LINK_LABEL.put("stay", "小院");
        LINK_LABEL.put("store", "门店");
        LINK_LABEL.put("job", "岗位");
        LINK_LABEL.put("activity", "活动");
        LINK_LABEL.put("post", "帖子");
        JOB_TYPE_LABEL.put("volunteer", "换宿旅行");
        JOB_TYPE_LABEL.put("parttime", "线下兼职");
        JOB_TYPE_LABEL.put("fulltime", "全职");
        JOB_TYPE_LABEL.put("online", "线上任务");
    }

    /** 门店：正常营业、没删（门店页 profile/follows?storeId= 游客也能看） */
    static final String STORE_SHOWN = "st.status = '0' and st.del_flag = '0'";
    /** 能选来关联的门店：另外还得有在招的岗位（门店页就是「门店在招岗位」，一个都没有点进去是空的） */
    static final String STORE_PICK = STORE_SHOWN + " and exists (select 1 from kg_job sj where sj.store_id = st.store_id and sj.status = 'published' and sj.del_flag = '0')";
    /** 岗位：小程序看得到的（招聘中 / 暂停，门店正常）；暂停的照常显示关联、点进去能看，只是选的时候只给招聘中的 */
    static final String JOB_SHOWN = "j.del_flag = '0' and j.status in ('published','paused') and st.status = '0' and st.del_flag = '0'";
    static final String JOB_PICK = "j.del_flag = '0' and j.status = 'published' and st.status = '0' and st.del_flag = '0'";
    /** 活动：详情页看得到的（结束了也能看）；选的时候只给还没结束的（同活动列表 LISTABLE 的口径） */
    static final String ACT_SHOWN = "a.del_flag = '0' and a.status in ('open','paused','closed','ended')";
    static final String ACT_PICK = "a.del_flag = '0' and a.status in ('open','paused','closed') and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) > now()";

    static final String LINK_STAY_SQL = "select s.stay_id id, s.stay_name title, s.cover, s.city, s.area sub from kg_stay s where ";
    static final String LINK_STORE_SQL = "select st.store_id id, st.store_name title, st.cover, st.city, '' sub from kg_store st where ";
    static final String LINK_JOB_SQL = "select j.job_id id, j.title, j.cover, j.city, j.job_type, st.store_name sub from kg_job j join kg_store st on st.store_id = j.store_id where ";
    static final String LINK_ACT_SQL = "select a.activity_id id, a.title, a.cover, a.city, a.start_time from kg_activity a where ";

    static final String POST_SHOWN = "lp.status = 'online' and lp.del_flag = '0'";
    static final String LINK_POST_SQL = "select lp.post_id id, lp.title, substring_index(coalesce(lp.images,''), ',', 1) cover, lp.city, lp.kind post_kind from kg_post lp where ";

    /** 按类型给出查询的开头、条件和编号列：pick = true 是「现在能选」（搜索、发布校验），false 是「还看得到」（信息流 / 详情的「关联：XXX」）；不认识的类型给 null */
    private static String[] linkQuery(String type, boolean pick)
    {
        if ("stay".equals(type))
        {
            return new String[] { LINK_STAY_SQL, KgStayBrowseService.ONLINE, "s.stay_id" };
        }
        if ("store".equals(type))
        {
            return new String[] { LINK_STORE_SQL, pick ? STORE_PICK : STORE_SHOWN, "st.store_id" };
        }
        if ("job".equals(type))
        {
            return new String[] { LINK_JOB_SQL, pick ? JOB_PICK : JOB_SHOWN, "j.job_id" };
        }
        if ("activity".equals(type))
        {
            return new String[] { LINK_ACT_SQL, pick ? ACT_PICK : ACT_SHOWN, "a.activity_id" };
        }
        if ("post".equals(type) && !pick)
        {
            return new String[] { LINK_POST_SQL, POST_SHOWN, "lp.post_id" };
        }
        return null;
    }

    /** 关联对象的小卡片：类型、编号、标题、封面、城市、小标签（岗位是岗位类型，其余是「小院 / 门店 / 活动」）、副标题。页面按 type + id 拼跳转地址 */
    static Map<String, Object> linkView(String type, Map<String, Object> r)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("id", KgJdbc.asLong(r.get("id")));
        m.put("title", str(r.get("title")));
        m.put("cover", str(r.get("cover")));
        m.put("city", str(r.get("city")));
        String jt = str(r.get("jobType"));
        m.put("label", "job".equals(type) && JOB_TYPE_LABEL.containsKey(jt) ? JOB_TYPE_LABEL.get(jt) : LINK_LABEL.get(type));
        String sub = str(r.get("sub"));
        if ("activity".equals(type))
        {
            Date start = StaffSupport.parse(r.get("startTime"));
            sub = start == null ? "" : StaffSupport.fmt(start, "M月d日 HH:mm");
        }
        if ("post".equals(type))
        {
            String kind = str(r.get("postKind"));
            sub = "buddy".equals(kind) ? "找搭子" : "diary".equals(kind) ? "经验分享" : "旅居日常";
        }
        m.put("sub", sub);
        return m;
    }

    Map<String, Object> pickableLink(String type, Long id, Long me)
    {
        if (!"post".equals(type)) return pickableLink(type, id);
        if (id == null || me == null) return null;
        Map<String, Object> row = db.one(LINK_POST_SQL + "lp.post_id = :id and lp.app_user_id = :u and " + POST_SHOWN, p("id", id, "u", me));
        return row == null || KgJdbc.asLong(row.get("id")) == null ? null : linkView(type, row);
    }

    /** 发布时校验：关联的对象存在、而且现在能选（小院在线、门店有在招岗位、岗位招聘中、活动还没结束）；不行给 null */
    Map<String, Object> pickableLink(String type, Long id)
    {
        String[] q = linkQuery(type, true);
        if (q == null || id == null)
        {
            return null;
        }
        Map<String, Object> r = db.one(q[0] + q[2] + " = :id and " + q[1], p("id", id));
        return r == null || KgJdbc.asLong(r.get("id")) == null ? null : linkView(type, r);
    }

    /**
     * 帖子存下的关联对象 { 类型, 编号 }（2026-10-17 r2）：先看 link_type / link_id（V52 起新代码发帖写它，V52 也把老帖子回填了）；
     * 没有再看 ref_type / ref_id —— V52 执行后、新代码上线前（或回退到老版本程序时）老程序发的帖子只写了 ref_*。
     * 经验分享的 ref 是经历那条报名，不算关联对象（没选关联时自动关联那条报名的岗位，见 links）。都没有给 null。
     */
    static Object[] storedLink(Map<String, Object> r)
    {
        String lt = str(r.get("linkType"));
        Long lid = KgJdbc.asLong(r.get("linkId"));
        if (lid != null && LINK_LABEL.containsKey(lt))
        {
            return new Object[] { lt, lid };
        }
        String rt = str(r.get("refType"));
        Long rid = KgJdbc.asLong(r.get("refId"));
        if (!"diary".equals(str(r.get("kind"))) && rid != null && LINK_LABEL.containsKey(rt))
        {
            return new Object[] { rt, rid };
        }
        return null;
    }

    /**
     * 一页帖子的关联对象（帖子编号 → 小卡片），按类型各查一次。
     * 找搭子 / 旅居攻略：存下的关联（storedLink）；经验分享：自己选了关联就用它（2026-10-17 r2），
     * 没选（或选的后来下线了）照旧是经历那条报名的岗位（下架了退到门店）。
     * 关联的对象后来下线了就不显示（不给点进去打不开的入口）；老帖子关联的是自己的报名 / 住宿订单（不公开），也不显示。
     */
    Map<Long, Map<String, Object>> links(List<Map<String, Object>> rows)
    {
        Map<Long, Map<String, Object>> out = new java.util.HashMap<Long, Map<String, Object>>();
        Map<String, List<Long>> want = new LinkedHashMap<String, List<Long>>();
        Map<Long, Object[]> direct = new LinkedHashMap<Long, Object[]>();
        Map<Long, Long> diaryApp = new LinkedHashMap<Long, Long>();
        for (Map<String, Object> r : rows)
        {
            Long pid = KgJdbc.asLong(r.get("postId"));
            if (pid == null)
            {
                continue;
            }
            Object[] own = storedLink(r);
            if (own != null)
            {
                direct.put(pid, own);
                addId(want, (String) own[0], (Long) own[1]);
            }
            Long rid = KgJdbc.asLong(r.get("refId"));
            if ("diary".equals(str(r.get("kind"))) && "application".equals(str(r.get("refType"))) && rid != null)
            {
                diaryApp.put(pid, rid);
            }
        }
        Map<Long, Long[]> appTargets = new java.util.HashMap<Long, Long[]>();
        if (!diaryApp.isEmpty())
        {
            for (Map<String, Object> a : db.list("select a.app_id, a.job_id, a.store_id from kg_application a where a.app_id in (:ids)",
                    p("ids", new ArrayList<Long>(new java.util.LinkedHashSet<Long>(diaryApp.values())))))
            {
                Long appId = KgJdbc.asLong(a.get("appId"));
                Long jobId = KgJdbc.asLong(a.get("jobId"));
                Long storeId = KgJdbc.asLong(a.get("storeId"));
                if (appId == null)
                {
                    continue;
                }
                appTargets.put(appId, new Long[] { jobId, storeId });
                addId(want, "job", jobId);
                addId(want, "store", storeId);
            }
        }
        Map<String, Map<Long, Map<String, Object>>> found = new java.util.HashMap<String, Map<Long, Map<String, Object>>>();
        for (Map.Entry<String, List<Long>> e : want.entrySet())
        {
            String[] q = linkQuery(e.getKey(), false);
            Map<Long, Map<String, Object>> byId = new java.util.HashMap<Long, Map<String, Object>>();
            for (Map<String, Object> r : db.list(q[0] + q[2] + " in (:ids) and " + q[1], p("ids", e.getValue())))
            {
                Long id = KgJdbc.asLong(r.get("id"));
                if (id != null)
                {
                    byId.put(id, linkView(e.getKey(), r));
                }
            }
            found.put(e.getKey(), byId);
        }
        for (Map.Entry<Long, Object[]> e : direct.entrySet())
        {
            Map<Long, Map<String, Object>> byId = found.get((String) e.getValue()[0]);
            Map<String, Object> v = byId == null ? null : byId.get((Long) e.getValue()[1]);
            if (v != null)
            {
                out.put(e.getKey(), v);
            }
        }
        for (Map.Entry<Long, Long> e : diaryApp.entrySet())
        {
            Long[] t = appTargets.get(e.getValue());
            // 经验分享自己选的关联还看得到就用它；没选、或选的下线了，才自动关联经历的岗位 / 门店
            if (t == null || out.containsKey(e.getKey()))
            {
                continue;
            }
            Map<String, Object> v = t[0] == null || found.get("job") == null ? null : found.get("job").get(t[0]);
            if (v == null && t[1] != null && found.get("store") != null)
            {
                v = found.get("store").get(t[1]);
            }
            if (v != null)
            {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private static void addId(Map<String, List<Long>> want, String type, Long id)
    {
        if (id == null)
        {
            return;
        }
        List<Long> ids = want.get(type);
        if (ids == null)
        {
            ids = new ArrayList<Long>();
            want.put(type, ids);
        }
        if (!ids.contains(id))
        {
            ids.add(id);
        }
    }

    /**
     * 关联选择页的搜索（2026-10-17）：type = place 小院和门店 / job 在招岗位 / activity 还没结束的活动；
     * keyword 按名称 / 城市找（通配符按字面）；city 给了就把这个城市的排在前面（不过滤，别的城市也能关联）。
     * 每页 20 条，只回卡片要的几个字段（同 linkView）。
     */
    public Map<String, Object> linkables(String type, String keyword, String city, int page)
    {
        return linkables(type, keyword, city, page, null);
    }

    public Map<String, Object> linkables(String type, String keyword, String city, int page, Long me)
    {
        if ("post".equals(type) && me == null) throw new ServiceException("请登录后选择自己的帖子", 401);
        int size = 20;
        Map<String, Object> ps = p("off", (Math.min(Math.max(page, 1), 100000) - 1) * size, "size", size + 1);
        String kw = keyword == null ? "" : StaffSupport.cut(keyword.trim(), 30);
        if (!kw.isEmpty())
        {
            ps.put("kw", com.ruoyi.kangaroo.ext.KgLike.contains(kw));
        }
        String c = city == null ? "" : cities.businessCity(city.trim());
        boolean byCity = c != null && !c.isEmpty() && !"全部城市".equals(c);
        if (byCity)
        {
            ps.put("c", c);
        }
        String near = byCity ? "(x.city = :c) desc, " : "";
        String sql;
        if ("place".equals(type))
        {
            sql = "select * from (select 'stay' kind, s.stay_id id, s.stay_name title, s.cover, s.city, s.area sub from kg_stay s where "
                    + KgStayBrowseService.ONLINE + (kw.isEmpty() ? "" : " and (s.stay_name like :kw or s.city like :kw or s.area like :kw)")
                    + " union all select 'store' kind, st.store_id id, st.store_name title, st.cover, st.city, '' sub from kg_store st where "
                    + STORE_PICK + (kw.isEmpty() ? "" : " and (st.store_name like :kw or st.city like :kw)")
                    + ") x order by " + near + "(x.kind = 'stay') desc, x.id desc limit :off, :size";
        }
        else if ("job".equals(type))
        {
            sql = "select * from (" + LINK_JOB_SQL + JOB_PICK + (kw.isEmpty() ? "" : " and (j.title like :kw or j.city like :kw or st.store_name like :kw)")
                    + ") x order by " + near + "x.id desc limit :off, :size";
        }
        else if ("activity".equals(type))
        {
            sql = "select * from (" + LINK_ACT_SQL + ACT_PICK + (kw.isEmpty() ? "" : " and (a.title like :kw or a.city like :kw)")
                    + ") x order by " + near + "x.start_time, x.id limit :off, :size";
        }
        else if ("post".equals(type))
        {
            ps.put("u", me);
            sql = "select * from (" + LINK_POST_SQL + POST_SHOWN + " and lp.app_user_id = :u"
                    + (kw.isEmpty() ? "" : " and (lp.title like :kw or lp.city like :kw)")
                    + ") x order by x.id desc limit :off, :size";
        }
        else
        {
            throw new ServiceException("请选择小院 / 门店、岗位、活动或自己的帖子", 422);
        }
        List<Map<String, Object>> rows = db.list(sql, ps);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rows.size() && i < size; i++)
        {
            Map<String, Object> r = rows.get(i);
            out.add(linkView("place".equals(type) ? ("store".equals(str(r.get("kind"))) ? "store" : "stay") : type, r));
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", out);
        res.put("hasMore", rows.size() > size);
        return res;
    }

    // ---------------------------------------------------------------- 发布

    /** 可关联的对象：我的岗位报名（未撤回）与住宿订单（已确认及以后） */
    public List<Map<String, Object>> refs(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.app_id, j.title, j.city from kg_application a join kg_job j on j.job_id = a.job_id where a.app_user_id = :u "
                + "and a.del_flag = '0' and a.status not in ('withdrawn','review_rejected') order by a.app_id desc limit 20", p("u", me)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("refType", "application");
            m.put("refId", a.get("appId"));
            m.put("label", "岗位 · " + a.get("title") + "（" + a.get("city") + "）");
            out.add(m);
        }
        for (Map<String, Object> o : db.list("select o.order_id, o.check_in, s.stay_name from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id where o.app_user_id = :u "
                + "and o.status in ('confirmed','checked_in','completed') order by o.order_id desc limit 20", p("u", me)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("refType", "stay_order");
            m.put("refId", o.get("orderId"));
            m.put("label", "住宿 · " + o.get("stayName") + "（" + str(o.get("checkIn")) + " 入住）");
            out.add(m);
        }
        return out;
    }

    /**
     * 能用来发「经验分享」的经历（发 diary 必须关联一条）。之前只认换工换宿；2026-10-16 起换宿、兼职、全职都算（线上任务不算旅居经验），
     * 每条带 jobType / jobTypeLabel（换宿旅行 / 线下兼职 / 全职），发布页的经历卡片上显示成小标签。
     * 2026-10-16 内测反馈 2.0（主人拍板）：已到岗、还没做完的也算，和简历「平台记录」同一口径（KgResumeService.syncPlatformExps：
     * 状态 onboarded / completed，不看结束日期）。发布时的校验也用这个列表，两边一致。
     */
    public List<Map<String, Object>> experiences(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.app_id, a.arrival_date, a.stay_days, j.title, j.city, j.cover, j.shift_desc, j.tags, j.job_type from kg_application a "
                + "join kg_job j on j.job_id = a.job_id where a.app_user_id = :u and a.del_flag = '0' and j.job_type in ('volunteer','parttime','fulltime') "
                + "and a.status in ('onboarded','completed') order by a.app_id desc limit 20", p("u", me)))
        {
            Map<String, Object> m = experienceCard(a);
            LocalDate to = endDate(a);
            m.put("ongoing", to != null && to.isAfter(LocalDate.now()));
            out.add(m);
        }
        return out;
    }

    /** 报名进行中、还不能用来发经验分享时给用户看的原因（按报名状态） */
    static final Map<String, String> NOT_YET_REASON = new LinkedHashMap<String, String>();
    static
    {
        NOT_YET_REASON.put("submitted", "报名还在等门店审核，到岗后才能分享");
        NOT_YET_REASON.put("review_passed", "还在面试阶段，到岗后才能分享");
        NOT_YET_REASON.put("interview_booked", "还在面试阶段，到岗后才能分享");
        NOT_YET_REASON.put("interview_done", "还在面试阶段，到岗后才能分享");
        NOT_YET_REASON.put("user_confirmed", "还在等门店确认录用，到岗后才能分享");
        NOT_YET_REASON.put("store_confirmed", "门店已录用，签好责任书、到岗后才能分享");
        NOT_YET_REASON.put("contract_signed", "还没确认到岗，门店标记你到岗后就能分享");
    }

    /**
     * 暂时还不能用的报名（2026-10-16 内测反馈 2.0）：经历校验页灰着显示、不能选，每条带一句大白话原因 reason。
     * 包括换宿 / 兼职 / 全职里报名了还没到岗的，和线上任务（不算旅居经验）。撤回、没通过、已终止的不列。
     * 字段和 experiences 一样（appId / title / date / img / jobType / jobTypeLabel），另加 status / statusLabel / reason。
     */
    public List<Map<String, Object>> experiencesUnavailable(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.app_id, a.status, a.arrival_date, a.stay_days, j.title, j.city, j.cover, j.shift_desc, j.tags, j.job_type from kg_application a "
                + "join kg_job j on j.job_id = a.job_id where a.app_user_id = :u and a.del_flag = '0' "
                + "and a.status not in ('withdrawn','review_rejected','terminated') "
                + "and (j.job_type = 'online' or a.status not in ('onboarded','completed')) order by a.app_id desc limit 20", p("u", me)))
        {
            String st = str(a.get("status"));
            boolean online = "online".equals(str(a.get("jobType")));
            String reason = online ? "线上任务不算旅居经验，不能用来发经验分享" : NOT_YET_REASON.get(st);
            if (reason == null)
            {
                continue;
            }
            Map<String, Object> m = experienceCard(a);
            if (online)
            {
                m.put("title", a.get("title"));
                m.put("date", "线上任务");
            }
            m.put("status", st);
            m.put("statusLabel", com.ruoyi.kangaroo.enums.ApplicationStatus.label(st));
            m.put("reason", reason);
            out.add(m);
        }
        return out;
    }

    private static LocalDate endDate(Map<String, Object> a)
    {
        LocalDate from = a.get("arrivalDate") == null ? null : LocalDate.parse(str(a.get("arrivalDate")).substring(0, 10));
        return from == null ? null : from.plusDays(Math.max(1, num(a.get("stayDays"))) - 1);
    }

    private static Map<String, Object> experienceCard(Map<String, Object> a)
    {
        LocalDate from = a.get("arrivalDate") == null ? null : LocalDate.parse(str(a.get("arrivalDate")).substring(0, 10));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("appId", a.get("appId"));
        m.put("title", a.get("title") + "（" + a.get("city") + "）");
        m.put("service", str(a.get("shiftDesc")).isEmpty() ? str(a.get("tags")).replace(",", " · ") : a.get("shiftDesc"));
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy.MM.dd");
        m.put("date", from == null ? "到岗日期待定" : from.format(f) + "–" + endDate(a).format(f));
        m.put("img", a.get("cover"));
        String jt = str(a.get("jobType"));
        m.put("jobType", jt);
        m.put("jobTypeLabel", DIARY_JOB_LABEL.containsKey(jt) ? DIARY_JOB_LABEL.get(jt) : "");
        return m;
    }

    /** 机审直接发布（review-mode=auto）只放开不带图的旅居日常、经验分享；找搭子、带图的动态照旧人工审核 */
    static boolean autoReviewable(String kind, List<String> images)
    {
        return ("daily".equals(kind) || "diary".equals(kind)) && (images == null || images.isEmpty());
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(Long me, Map<String, Object> b)
    {
        AccountState.requireActive(db, me);
        String kind = str(b.get("kind"));
        if (!KINDS.contains(kind))
        {
            throw new ServiceException("请选择发布类型", 422);
        }
        guard.requireConsent(me, "community");
        if ("buddy".equals(kind))
        {
            // 之前发找搭子只要同意《社区公约》；现在还要读过并同意《找搭子安全须知》（自愿结伴、自负安全、意外险、报警电话），
            // 没同意时返回 423 CONSENT:buddy，小程序 gated() 弹出全文、记下同意后自动重发
            guard.requireConsent(me, "buddy");
            guard.requirePhone(me);
        }
        if (db.count("select count(*) from kg_post where app_user_id = :u and create_time > date_sub(now(), interval 1 day)", p("u", me)) >= 20)
        {
            throw new ServiceException("今天发布得太多了，明天再来吧", 429);
        }
        String title = str(b.get("title")).trim();
        String content = str(b.get("content")).trim();
        if (title.isEmpty() || title.length() > 60)
        {
            throw new ServiceException("标题为 1–60 字", 422);
        }
        if (content.length() > 500)
        {
            throw new ServiceException("正文不能超过 500 字", 422);
        }
        List<String> images = new ArrayList<String>();
        Object imgs = b.get("images");
        if (imgs instanceof List)
        {
            for (Object o : (List<?>) imgs)
            {
                String s = str(o).trim();
                if (KgJdbc.isPlatformImage(s))
                {
                    images.add(s);
                }
            }
        }
        if (images.size() > 9)
        {
            throw new ServiceException("最多 9 张图片", 422);
        }
        String city = str(b.get("city")).trim();
        if (city.isEmpty() || city.length() > 32)
        {
            throw new ServiceException("请选择城市", 422);
        }
        String refType = str(b.get("refType"));
        Long refId = KgJdbc.asLong(b.get("refId"));
        // 2026-10-17 r2：关联的 小院 / 门店 / 岗位 / 活动 单独传 linkType / linkId（经验分享也能选了）。
        // 新版小程序找搭子、旅居攻略另外照旧带 refType / refId（老后端照样认）；老版本小程序（r1.1）只带 refType / refId，这里认成关联
        String linkType = str(b.get("linkType")).trim();
        Long linkId = KgJdbc.asLong(b.get("linkId"));
        if (linkId == null && !"diary".equals(kind) && refId != null && LINK_LABEL.containsKey(refType))
        {
            linkType = refType;
            linkId = refId;
        }
        if (linkId != null && !LINK_LABEL.containsKey(linkType))
        {
            throw new ServiceException("关联只能选小院 / 门店、岗位、活动或自己的帖子", 422);
        }
        if ("diary".equals(kind))
        {
            // 经历校验：必须是本人已到岗（含还没做完的）或已完成的换宿 / 兼职 / 全职报名（experiences 的口径）
            boolean ok = refId != null && "application".equals(refType);
            if (ok)
            {
                ok = false;
                for (Map<String, Object> e : experiences(me))
                {
                    ok = ok || refId.equals(KgJdbc.asLong(e.get("appId")));
                }
            }
            if (!ok)
            {
                throw new ServiceException("发布经验分享需要先选择一段已完成的换宿、兼职或全职经历（已到岗、还在做的也可以）", 422);
            }
        }
        else if (refId != null && !LINK_LABEL.containsKey(refType))
        {
            boolean ok = false;
            for (Map<String, Object> r : refs(me))
            {
                ok = ok || (refType.equals(r.get("refType")) && refId.equals(KgJdbc.asLong(r.get("refId"))));
            }
            if (!ok)
            {
                throw new ServiceException("只能关联你自己的报名或订单", 403);
            }
        }
        if (linkId != null)
        {
            // 2026-10-17 关联小院 / 门店 / 岗位 / 活动：对象要存在、而且现在能选（下线的、结束的不让关联）；r2 起经验分享自己选的也一样校验
            if (pickableLink(linkType, linkId, me) == null)
            {
                throw new ServiceException("关联的" + LINK_LABEL.get(linkType) + "不存在或已下线，换一个试试", 422);
            }
            // 找搭子 / 旅居攻略：ref_* 照旧写一份同样的关联（老版本程序只读 ref_*）；ref 是老客户端传的自己的报名 / 订单时不动它。经验分享的 ref 是那条报名，不动
            if (!"diary".equals(kind) && (refId == null || LINK_LABEL.containsKey(refType)))
            {
                refType = linkType;
                refId = linkId;
            }
        }
        String level = guard.check(title, content, str(b.get("tags")));
        String status = guard.initialStatus(level);
        String reviewMode = guard.mode();
        // 2026-10-16 内测反馈 2.0：review-mode=auto 时，不带图的旅居日常 / 经验分享过了本地检查、微信内容安全接口也判通过就直接发布。
        // 带图的动态、找搭子照旧人工审核；接口出错 / 超时也照旧待审
        if ("pending".equals(status) && autoReviewable(kind, images)
                && guard.autoApprove(level, me, WxMsgSecCheck.SCENE_FORUM, title, (content + " " + str(b.get("tags")).replace(",", " ")).trim()))
        {
            status = "online";
            reviewMode = "auto";
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me);
        row.put("kind", kind);
        row.put("city", city);
        row.put("title", title);
        row.put("content", content);
        row.put("images", String.join(",", images));
        row.put("tags", StaffSupport.cut(str(b.get("tags")), 128));
        if ("buddy".equals(kind))
        {
            String cat = str(b.get("category"));
            row.put("category", CATEGORY_LABEL.containsKey(cat) ? cat : inferCategory(title + " " + content + " " + str(b.get("tags"))));
            // 同行性别：不传 = 不限；只认 '' / female / male，别的值直接拒绝（不悄悄当成不限，免得发帖人以为限定了）
            String genderLimit = str(b.get("genderLimit")).trim();
            if (!GENDER_LIMIT_LABEL.containsKey(genderLimit))
            {
                throw new ServiceException("同行性别只能选不限、仅限女生或仅限男生", 422);
            }
            row.put("gender_limit", genderLimit);
            String d = str(b.get("eventDate"));
            if (!d.isEmpty() && !KgJdbc.isDate(d))
            {
                throw new ServiceException("日期不存在或格式不对，应为 yyyy-MM-dd", 422);
            }
            if (KgJdbc.isDate(d))
            {
                if (LocalDate.parse(d).isBefore(LocalDate.now()))
                {
                    throw new ServiceException("日期不能早于今天", 422);
                }
                row.put("event_date", d);
            }
            row.put("event_days", StaffSupport.cut(str(b.get("eventDays")), 8));
        }
        if ("daily".equals(kind))
        {
            // 伙伴说：不传 = 不选；只认 '' / recommend / avoid，别的值直接拒绝。只有旅居日常有，别的类型传了也不存
            String tone = str(b.get("tone")).trim();
            if (!tone.isEmpty() && !TONE_LABEL.containsKey(tone))
            {
                throw new ServiceException("「伙伴说」只能选推荐或避雷", 422);
            }
            if (!tone.isEmpty())
            {
                row.put("tone", tone);
            }
        }
        if (refId != null)
        {
            row.put("ref_type", refType);
            row.put("ref_id", refId);
        }
        if (linkId != null)
        {
            row.put("link_type", linkType);
            row.put("link_id", linkId);
        }
        row.put("status", status);
        row.put("review_mode", reviewMode);
        row.put("create_by", "app:" + me);
        row.put("create_time", new Date());
        long id = db.insert("kg_post", row);
        Long activityId = null;
        Object sync = b.get("syncActivity");
        if ("buddy".equals(kind) && sync instanceof Map)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = new LinkedHashMap<String, Object>((Map<String, Object>) sync);
            a.put("title", title);
            a.put("intro", content);
            a.put("city", city);
            a.put("cover", images.isEmpty() ? "" : images.get(0));
            a.put("category", "户外活动");
            activityId = activities.createByUser(me, a, id);
            db.update("update kg_post set activity_id = :a where post_id = :p", p("a", activityId, "p", id));
        }
        // 2026-10-17 攻略获得：旅居攻略发帖时机审直接通过（已发布）就发积分；进人工审核的等后台通过时再发（见 audit）。
        // 发帖提交后另开事务发，出错只记日志，不影响发帖
        if ("online".equals(status) && "daily".equals(kind) && pointRewards != null)
        {
            pointRewards.postOnline(id);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("postId", id);
        m.put("status", status);
        m.put("activityId", activityId);
        m.put("message", "online".equals(status) ? "发布成功" : "已提交审核，通过后会展示在社区并通知你");
        // 发布成功页的「已关联到：XXX」（2026-10-17）：和信息流同一口径（经验分享没选关联时是经历那条报名的岗位）；没关联是 null
        m.put("link", refId == null && linkId == null ? null : links(java.util.Collections.singletonList(
                p("postId", id, "kind", kind, "refType", refType, "refId", refId, "linkType", linkId == null ? "" : linkType, "linkId", linkId))).get(id));
        return m;
    }

    /**
     * 用户发起活动时勾选「同步到社区」：给这场活动发一条关联的找搭子动态（和找搭子帖同步建活动方向相反）。
     * 之前确认页的这个勾选不起任何作用。帖子照常走社区规则同意、每日上限和内容审核；
     * 活动还在审核中时帖子也先进审核，免得帖子先上线、点进去活动却看不到。帖子没发出去不影响已发起的活动。
     */
    public Map<String, Object> publishForActivity(Long me, Long activityId)
    {
        AccountState.requireActive(db, me);
        Map<String, Object> a = db.must("select activity_id, title, intro, city, cover, start_time, status from kg_activity "
                + "where activity_id = :a and app_user_id = :u and source = 'user' and del_flag = '0'", p("a", activityId, "u", me), "活动不存在");
        guard.requireConsent(me, "community");
        if (db.count("select count(*) from kg_post where activity_id = :a and del_flag = '0'", p("a", activityId)) > 0)
        {
            throw new ServiceException("这场活动已经同步到社区了", 409);
        }
        if (db.count("select count(*) from kg_post where app_user_id = :u and create_time > date_sub(now(), interval 1 day)", p("u", me)) >= 20)
        {
            throw new ServiceException("今天发布得太多了，明天再来吧", 429);
        }
        String title = StaffSupport.cut(str(a.get("title")).trim(), 60);
        String content = StaffSupport.cut(str(a.get("intro")).trim(), 500);
        String cover = str(a.get("cover")).trim();
        String level = guard.check(title, content);
        String status = "pending".equals(str(a.get("status"))) ? "pending" : guard.initialStatus(level);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me);
        row.put("kind", "buddy");
        row.put("city", StaffSupport.cut(str(a.get("city")), 32));
        row.put("title", title);
        row.put("content", content);
        row.put("images", KgJdbc.isPlatformImage(cover) ? cover : "");
        row.put("tags", "");
        row.put("category", inferCategory(title + " " + content));
        String day = str(a.get("startTime"));
        day = day.length() >= 10 ? day.substring(0, 10) : "";
        if (KgJdbc.isDate(day) && !LocalDate.parse(day).isBefore(LocalDate.now()))
        {
            row.put("event_date", day);
        }
        row.put("activity_id", activityId);
        row.put("status", status);
        row.put("review_mode", guard.mode());
        row.put("create_by", "app:" + me);
        row.put("create_time", new Date());
        long id = db.insert("kg_post", row);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("postId", id);
        m.put("status", status);
        m.put("message", "online".equals(status) ? "已同步到社区" : "已同步到社区，审核通过后展示");
        return m;
    }

    public List<Map<String, Object>> mine(Long me, Integer page)
    {
        // 老客户端不传页码时保留最近 50 条；新客户端连续加载，历史内容不会被 50 条截断。
        int size = page == null ? 50 : 20;
        int offset = page == null ? 0 : (Math.min(Math.max(page, 1), 100000) - 1) * size;
        List<Map<String, Object>> rows = db.list("select p.* from kg_post p where p.app_user_id = :u and p.del_flag = '0' order by p.post_id desc limit :size offset :off", p("u", me, "size", size, "off", offset));
        List<Map<String, Object>> out = briefs(rows, me);
        for (int i = 0; i < rows.size(); i++)
        {
            out.get(i).put("auditReason", rows.get(i).get("auditReason"));
        }
        return out;
    }

    private Map<String, Object> editablePost(Long id, Long me, boolean lock)
    {
        Map<String, Object> row = db.one("select * from kg_post where post_id=:p and del_flag='0'" + (lock ? " for update" : ""), p("p", id));
        if (row == null || me == null || !me.equals(KgJdbc.asLong(row.get("appUserId"))))
            throw new ServiceException("只能编辑自己的动态", 403);
        if ("hidden".equals(str(row.get("status"))))
            throw new ServiceException("动态已被平台隐藏，请先联系平台处理", 409);
        return row;
    }

    static String editToken(Map<String, Object> row)
    {
        StringBuilder value = new StringBuilder();
        // Include arrangements themselves: two edits within one DB timestamp second must still conflict.
        for (String key : Arrays.asList("postId", "activityId", "title", "content", "images", "cover", "intro", "safetyNote", "status", "auditReason", "updateTime",
                "startTime", "endTime", "deadline", "place", "city", "meetLat", "meetLng", "capacity", "feeFen", "feeMode", "aaFen", "signed", "waitlist"))
        {
            String s = str(row.get(key));
            value.append(s.length()).append(':').append(s);
        }
        return org.springframework.util.DigestUtils.md5DigestAsHex(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public Map<String, Object> editView(Long id, Long me)
    {
        Map<String, Object> row = editablePost(id, me, false);
        return p("postId", id, "title", row.get("title"), "content", row.get("content"),
                "images", split(row.get("images")), "editToken", editToken(row), "activityId", row.get("activityId"));
    }

    /** Update only author content; retain links, experience evidence, interactions and activity signups. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> edit(Long id, Long me, Map<String, Object> body)
    {
        AccountState.requireActive(db, me);
        Map<String, Object> row = editablePost(id, me, true);
        if (!editToken(row).equals(str(body.get("editToken"))))
            throw new ServiceException("内容或审核状态已变化，请重新打开编辑后再保存", 409);
        String title = str(body.get("title")).trim(), content = str(body.get("content")).trim();
        if (title.isEmpty() || title.length() > 60 || content.length() > 500)
            throw new ServiceException("标题为 1–60 字，正文不超过 500 字", 422);
        if (!(body.get("images") instanceof List) || ((List<?>) body.get("images")).size() > 9)
            throw new ServiceException("图片最多 9 张", 422);
        List<String> images = new ArrayList<String>();
        for (Object image : (List<?>) body.get("images"))
        {
            String url = str(image).trim();
            // Existing legacy photos may be retained, newly added photos must use the upload service.
            if (!KgJdbc.isPlatformImage(url) && !split(row.get("images")).contains(url))
                throw new ServiceException("请重新上传无效的图片", 422);
            images.add(url);
        }
        guard.requireConsent(me, "community");
        String kind = str(row.get("kind"));
        if ("buddy".equals(kind)) { guard.requireConsent(me, "buddy"); guard.requirePhone(me); }
        String level = guard.check(title, content, str(row.get("tags")));
        String status = guard.initialStatus(level), mode = guard.mode();
        if ("pending".equals(status) && autoReviewable(kind, images)
                && guard.autoApprove(level, me, WxMsgSecCheck.SCENE_FORUM, title, (content + " " + str(row.get("tags")).replace(",", " ")).trim()))
        { status = "online"; mode = "auto"; }
        db.updateRow("kg_post", "post_id", id, p("title", title, "content", content, "images", String.join(",", images),
                "status", status, "review_mode", mode, "audit_reason", null, "update_by", "app:" + me, "update_time", new Date()));
        return p("postId", id, "status", status, "message", "online".equals(status) ? "修改已保存" : "修改已提交审核，通过后展示");
    }

    @Transactional(rollbackFor = Exception.class)
    public String remove(Long id, Long me)
    {
        if (db.update("update kg_post set del_flag = '2', update_by = :by, update_time = now() where post_id = :p and app_user_id = :u and del_flag = '0'",
                p("p", id, "u", me, "by", "app:" + me)) == 0)
        {
            throw new ServiceException("只能删除自己的动态", 403);
        }
        return "已删除";
    }

    @Transactional(rollbackFor=Exception.class)
    public String removeWithActivity(Long id,Long me,boolean cancelActivity,String reason) {
        if(!cancelActivity)return remove(id,me);
        AccountState.requireActive(db,me);
        Map<String,Object> post=db.must("select app_user_id,activity_id from kg_post where post_id=:p and del_flag='0'",p("p",id),"动态不存在");
        if(!me.equals(KgJdbc.asLong(post.get("appUserId"))))throw new ServiceException("只能删除自己的动态",403);
        Long activity=KgJdbc.asLong(post.get("activityId"));
        if(activity!=null) {
            Map<String,Object> a=db.must("select status,app_user_id,source from kg_activity where activity_id=:a and del_flag='0'",p("a",activity),"活动不存在");
            if(!me.equals(KgJdbc.asLong(a.get("appUserId")))||!"user".equals(a.get("source")))throw new ServiceException("只能取消本人发起的活动",403);
            if(!"cancelled".equals(a.get("status")))activities.actAsUser(me,activity,"cancel",null,null,reason);
        }
        remove(id,me);return "动态已删除，关联活动已取消";
    }

    // ---------------------------------------------------------------- 后台审核

    @Transactional(rollbackFor = Exception.class)
    public String audit(Long id, boolean pass, String reason, String operator)
    {
        Map<String, Object> r = db.must("select post_id, app_user_id, title, status, activity_id from kg_post where post_id = :p and del_flag = '0'", p("p", id), "动态不存在");
        String from = str(r.get("status"));
        String to = pass ? "online" : ("online".equals(from) ? "hidden" : "rejected");
        if (to.equals(from))
        {
            // 已发布的再点通过 / 已驳回的再驳回：不改状态，也不能再给作者发一遍通知（2026-09-26 回归发现）
            throw new ServiceException(pass ? "这条动态已经是发布状态" : "这条动态已经处理过了", 409);
        }
        if (!pass && (reason == null || reason.trim().isEmpty()))
        {
            throw new ServiceException("请填写原因");
        }
        if (db.update("update kg_post set status = :to, audit_reason = :r, update_by = :by, update_time = now() where post_id = :p and status = :from",
                p("to", to, "r", pass ? null : StaffSupport.cut(reason, 255), "by", operator, "p", id, "from", from)) == 0)
        {
            throw new ServiceException("状态已变化，请刷新");
        }
        Long uid = KgJdbc.asLong(r.get("appUserId"));
        // 2026-10-17 攻略获得：后台审核通过（含隐藏后又恢复）就发；不是旅居攻略的、这篇已经发过的，发放时跳过。
        // 审核提交后另开事务发，出错只记日志，不影响审核
        if (pass && pointRewards != null)
        {
            pointRewards.postOnline(id);
        }
        support.notifyUser(uid, "system", pass ? "你的动态已通过审核" : ("hidden".equals(to) ? "你的动态已被隐藏" : "你的动态未通过审核"),
                pass ? str(r.get("title")) : str(r.get("title")) + "：" + reason, "post", id, pass ? "/pages/community/index?postId=" + id : "/pages/community/mine");
        auditSyncedActivities(id, uid, pass, reason, operator);
        return pass ? "已通过" : ("hidden".equals(to) ? "已隐藏" : "已驳回");
    }

    /**
     * 找搭子勾了「同步活动」时建的那场活动（kg_activity.post_id = 本帖）还在待审核：跟着帖子一起通过 / 一起驳回（同样的原因）。
     * 之前只改帖子，活动一直待审核，别人点帖子上的活动卡片是 404。走活动自己的审核（建群、通知发起人、推送同城新活动都照做）；
     * 开始时间已过的活动活动审核不让通过，按它的规矩驳回。先锁活动行，活动审核里的状态判断不会和别人撞上
     */
    private void auditSyncedActivities(Long postId, Long author, boolean pass, String reason, String operator)
    {
        if (author == null)
        {
            return;
        }
        for (Map<String, Object> a : db.list("select activity_id, start_time from kg_activity where post_id = :p and app_user_id = :u and source = 'user' "
                + "and status = 'pending' and del_flag = '0' order by activity_id for update", p("p", postId, "u", author)))
        {
            Long activityId = KgJdbc.asLong(a.get("activityId"));
            Date start = StaffSupport.parse(a.get("startTime"));
            if (pass && start != null && !new Date().before(start))
            {
                activities.audit(activityId, false, "活动开始时间已过，不能再通过审核", operator);
            }
            else
            {
                activities.audit(activityId, pass, pass ? null : reason, operator);
            }
        }
    }

    /** 待审时被直接隐藏的评论，处理说明末尾带这一句：它从来没展示过，之后「通过 / 恢复」要按第一次审核通过提醒 */
    static final String NEVER_SHOWN = "（待审时隐藏，未展示过）";

    @Transactional(rollbackFor = Exception.class)
    public String auditComment(Long id, boolean pass, String note, String operator)
    {
        Map<String, Object> c;
        try
        {
            c = db.must("select comment_id, post_id, app_user_id, reply_user_id, content, status, handle_note from kg_post_comment where comment_id = :c and del_flag = '0' for update",
                    p("c", id), "评论不存在");
        }
        catch (ServiceException e)
        {
            // db.must 查不到时不带码（接口 500）；评论已被删是业务拒绝，改报 404
            throw new ServiceException(e.getMessage(), 404);
        }
        String from = str(c.get("status"));
        String to = pass ? "visible" : "hidden";
        if (from.equals(to))
        {
            return pass ? "已是可见状态" : "已是隐藏状态";
        }
        // 之前只有「待审 → 可见」会发提醒：待审评论先被隐藏、再「通过 / 恢复」时是第一次展示，作者和评论人却都收不到任何通知。
        // 没有单独的列记「展示过没有」，隐藏待审评论时在处理说明末尾记一笔（后台详情里也看得到），恢复时据此补发
        boolean neverShown = "pending".equals(from) || ("hidden".equals(from) && str(c.get("handleNote")).endsWith(NEVER_SHOWN));
        String handle = (operator == null ? "" : operator + "：") + (note == null ? "" : note);
        handle = "hidden".equals(to) && neverShown ? StaffSupport.cut(handle, 255 - NEVER_SHOWN.length()) + NEVER_SHOWN : StaffSupport.cut(handle, 255);
        int changed = db.update("update kg_post_comment set status = :to, handle_note = :n, update_time = now() where comment_id = :c and status = :from and del_flag = '0'",
                p("to", to, "n", handle, "c", id, "from", from));
        if (changed == 0)
        {
            throw new ServiceException("评论状态已变化，请刷新", 409);
        }
        if ("visible".equals(to))
        {
            db.update("update kg_post set comments = comments + 1 where post_id = :p", p("p", c.get("postId")));
            if (neverShown)
            {
                notifyCommentApproved(c);
            }
        }
        else if ("visible".equals(from))
        {
            db.update("update kg_post set comments = greatest(comments - 1, 0) where post_id = :p", p("p", c.get("postId")));
            notifyCommentHidden(KgJdbc.asLong(c.get("appUserId")), KgJdbc.asLong(c.get("postId")), str(c.get("content")), note);
        }
        else if ("pending".equals(from))
        {
            notifyCommentRejected(c, note);
        }
        return pass ? "已恢复展示" : "已隐藏";
    }

    /**
     * 已展示的评论被后台隐藏、或举报成立被隐藏：和「你的动态已被隐藏」一样告诉评论人、带上原因（之前什么都不发，评论就悄悄没了）。
     * 举报处理（KgCommunityModule.uphold）也调这里
     */
    public void notifyCommentHidden(Long commenter, Long postId, String content, String note)
    {
        if (commenter == null)
        {
            return;
        }
        String reason = note == null ? "" : note.trim();
        support.notifyUser(commenter, "system", "你的评论已被隐藏", StaffSupport.cut(content == null ? "" : content, 40) + (reason.isEmpty() ? "" : "：" + reason),
                "post", postId, "/pages/community/index?postId=" + postId);
    }

    /** 待审评论被直接隐藏：和动态驳回一样告诉评论人没通过、带上原因（之前什么都不发）；以后被「通过 / 恢复」时再按审核通过提醒 */
    private void notifyCommentRejected(Map<String, Object> c, String note)
    {
        Long commenter = KgJdbc.asLong(c.get("appUserId"));
        if (commenter == null)
        {
            return;
        }
        String reason = note == null ? "" : note.trim();
        Long postId = KgJdbc.asLong(c.get("postId"));
        support.notifyUser(commenter, "system", "你的评论未通过审核", StaffSupport.cut(str(c.get("content")), 40) + (reason.isEmpty() ? "" : "：" + reason),
                "post", postId, "/pages/community/index?postId=" + postId);
    }

    /**
     * 待审评论审核通过：补发 comment() 当时因「先审后发」没发的提醒（生产环境评论一律先审，之前作者永远收不到），
     * 给被回复的人、帖子作者发「回复了你的评论 / 评论了你的动态」，再告诉评论人评论已展示；评论人自己就是对方时不发
     */
    private void notifyCommentApproved(Map<String, Object> c)
    {
        Long postId = KgJdbc.asLong(c.get("postId"));
        Long commenter = KgJdbc.asLong(c.get("appUserId"));
        Long replyUser = KgJdbc.asLong(c.get("replyUserId"));
        Map<String, Object> post = db.one("select app_user_id from kg_post where post_id = :p and del_flag = '0'", p("p", postId));
        String text = StaffSupport.cut(str(c.get("content")), 60);
        String route = "/pages/community/index?postId=" + postId + "&view=comments";
        if (commenter != null)
        {
            notifyComment(postId, post == null ? null : KgJdbc.asLong(post.get("appUserId")), replyUser, commenter, KgJdbc.asLong(c.get("commentId")), text);
            support.notifyUser(commenter, "system", "你的评论已通过审核", text, "post", postId, route);
        }
    }

    /**
     * 评论通知（2026-10-15 消息页改版）：一条评论一行，进「收到的评论」（category = comment，biz = comment / 评论ID，触发人 = 评论人）。
     * 回复别人的评论时通知被回复的人「回复了你的评论」；帖子作者既不是评论人、也不是被回复的人时，再通知作者「评论了你的动态」
     * （之前回复时只通知被回复的人，作者收不到）。自己评论 / 回复自己一律不发。微信订阅消息照旧按「社区互动」开关推送。
     */
    private void notifyComment(Long postId, Long authorId, Long replyUser, Long actor, Long commentId, String text)
    {
        String body = StaffSupport.cut(text == null || text.trim().isEmpty() ? "[图片]" : text, 60);
        String route = "/pages/community/index?postId=" + postId + "&view=comments";
        String name = null;
        if (replyUser != null && !replyUser.equals(actor))
        {
            name = nick(actor);
            messages.send(replyUser, "user", MessageCategory.COMMENT, name + " 回复了你的评论", body, "comment", commentId, route, actor);
        }
        if (authorId != null && !authorId.equals(actor) && !authorId.equals(replyUser))
        {
            messages.send(authorId, "user", MessageCategory.COMMENT, (name == null ? nick(actor) : name) + " 评论了你的动态", body, "comment", commentId, route, actor);
        }
    }

    /** 通知标题里的昵称：没填写「用户{id}」，和作者卡片一样 */
    private String nick(Long uid)
    {
        Map<String, Object> u = db.one("select nickname from kg_app_user where user_id = :u", p("u", uid));
        return u == null || str(u.get("nickname")).isEmpty() ? "用户" + uid : str(u.get("nickname"));
    }

    // ---------------------------------------------------------------- 举报处置（后台判定成立时，按对象类型隐藏）

    @Override
    public String targetType()
    {
        return "post";
    }

    @Override
    public String uphold(long targetId, String operator)
    {
        Map<String, Object> r = db.one("select app_user_id, title from kg_post where post_id = :p and status = 'online' and del_flag = '0'", p("p", targetId));
        if (db.update("update kg_post set status = 'hidden', audit_reason = '举报成立，已隐藏', update_by = :by, update_time = now() where post_id = :p and status = 'online'",
                p("p", targetId, "by", operator)) > 0 && r != null)
        {
            // 和后台隐藏同一条通知（之前举报成立隐藏时作者什么都收不到）
            support.notifyUser(KgJdbc.asLong(r.get("appUserId")), "system", "你的动态已被隐藏", str(r.get("title")) + "：举报成立，已隐藏", "post", targetId,
                    "/pages/community/mine");
        }
        return "动态已隐藏";
    }
}
