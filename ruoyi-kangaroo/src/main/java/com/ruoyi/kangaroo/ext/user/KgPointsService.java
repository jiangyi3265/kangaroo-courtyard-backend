package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 积分怎么得、怎么用。
 *
 * 之前积分只有门店任务验收一个来源、也没有任何地方能花；现在：
 *   1. 换工换宿完成得积分：每条换工报名完成时发一次「天数 × 每天积分」（天数优先用门店填的实际天数，没填按报名天数，最多 180 天）。
 *      报名状态机走到「已入职」就是终点，系统里原本没有「换工结束」这一步，所以完成有两条路：
 *        · 门店在员工端报名详情点「确认换工完成」并填实际天数（提前离开、提前确认都走这里）；
 *        · 计划结束日过了 3 天、并且标记入职也满 3 天，门店还没确认的，定时任务按报名天数自动完成。
 *      两条路都在同一个事务里记完成时间、发积分、通知用户，并给邀请人发首次奖励（见 KgInviteService）。
 *      计划在积分上线日（V37 执行那天）之前就已结束的换工，不管哪条路完成都只记完成、不发积分（见 complete）。
 *   2. 加急投递花积分：等门店审核的报名花 kangaroo.points.urgentCost 积分标成「加急」，员工端列表排在最前面并标红色「加急」。
 *   3. 成长等级按「累计获得」算，花掉的积分不会让等级掉下去；能花的是「可用积分」。
 */
@Service
public class KgPointsService
{
    private static final Logger log = LoggerFactory.getLogger(KgPointsService.class);

    /** 一条换工报名最多按 180 天计积分 */
    static final int MAX_VOLUNTEER_DAYS = 180;

    /** 计划结束日之后再等几天才自动完成：留给门店填实际天数（提前离开的、多住了几天的） */
    static final int AUTO_FINISH_GRACE_DAYS = 3;

    /**
     * 标记入职之后至少再等几天才自动完成。之前只看计划结束日：门店很晚才补标入职（计划早就结束了）的，
     * 下一轮定时任务（半小时一次）就按报名天数自动完成了，门店来不及填实际天数；现在入职标记满 3 天才自动完成。
     */
    static final int ONBOARD_GRACE_DAYS = 3;

    /** 积分功能上线的迁移版本（kg_schema_version.version），上线日按它的执行日期算 */
    static final int LAUNCH_VERSION = 37;

    /** 能加急的报名状态：已提交、等门店审核（员工端「待审核」页签） */
    static final String URGENT_STATUS = "submitted";

    @Autowired(required=false) private KgMembershipRewards memberRewards;
    @Autowired
    private KgJdbc db;

    @Autowired
    private KgPointLedger ledger;

    @Autowired
    private KgInviteService invites;

    @Autowired
    private KgMemberService membership;

    @Autowired
    private StaffSupport support;

    @Autowired
    private IKgMessageService messages;

    /** 定时任务里调本类的事务方法必须经过代理，否则 @Transactional 不生效 */
    @Lazy
    @Autowired
    private KgPointsService self;

    /** 积分上线日缓存：查到一次就不再查（上线日不会变） */
    private volatile LocalDate launchDate;

    // ---------------------------------------------------------------- 积分总览

    /**
     * 「我的」→ 会员与积分：可用、累计获得、已使用、等级（按累计获得）、明细、规则。
     * points 字段之前是全部流水之和，现在明确表示「可用积分」（数值不变：全部流水之和 = 获得 − 使用）。
     */
    public Map<String, Object> summary(Long uid, int page)
    {
        // 2026-10-17：先补发「完善个人资料」「首次入住评价」两项一次性奖励（满足条件、还没发过才发），这次读到的积分里就带上
        boolean execution = memberRewards != null && memberRewards.enabled();
        if (execution) memberRewards.sync(uid);
        Map<String, Boolean> done = execution ? new java.util.HashMap<>() : oneTimeRewards(uid);
        KgPointLedger.Totals t = ledger.totals(uid);
        long total = ledger.recordTotal(uid);
        int perDay = ledger.volunteerPerDay();
        int reward = ledger.inviteReward();
        int cost = ledger.urgentCost();
        int profileReward = ledger.profileReward();
        int reviewReward = ledger.stayReviewReward();
        // 2026-10-17 消费获得、攻略获得（发放见 KgPointRewards）：分数和每日篇数都来自参数设置
        int spendPerYuan = ledger.spendPerYuan();
        int guideReward = ledger.guideReward();
        int guideCap = ledger.guideDailyCap();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("points", t.balance());
        m.put("balance", t.balance());
        m.put("earned", t.earned);
        m.put("spent", t.spent);
        m.put("hasPoints", total > 0);
        m.putAll(membership.summaryFor(uid,t.earned));
        m.put("records", ledger.records(uid, page, 20));
        m.put("recordTotal", total);
        Map<String, Object> rules = new LinkedHashMap<String, Object>();
        rules.put("volunteerPerDay", perDay);
        rules.put("inviteReward", reward);
        rules.put("urgentCost", cost);
        rules.put("taskPerHour", KgPointLedger.TASK_PER_HOUR);
        rules.put("profileReward", profileReward);
        rules.put("stayReviewReward", reviewReward);
        rules.put("spendPerYuan", spendPerYuan);
        rules.put("guideReward", guideReward);
        rules.put("guideDailyCap", guideCap);
        m.put("rules", rules);
        m.put("note", note(perDay, reward, cost, profileReward, reviewReward, spendPerYuan, guideReward));
        m.put("ways", ways(perDay, reward, cost));
        m.put("tasks", tasks(perDay, reward, profileReward, reviewReward, guideReward, guideCap, done));
        m.put("ruleRows", ruleRows(perDay, reward, cost, profileReward, reviewReward, spendPerYuan, guideReward));
        m.put("levelPerks", membership.perks());
        if (execution) {
            m.put("executionEnabled", true);
            m.put("note", "积分长期有效，兑换不降低成长等级；奖励按已完成的真实服务发放。");
            m.put("ruleRows", KgMembershipPolicy.rules());
            m.put("tasks", java.util.Collections.emptyList());
            m.put("ways", java.util.Collections.emptyList());
        }
        return m;
    }

    static String note(int perDay, int reward, int cost)
    {
        return note(perDay, reward, cost, 0, 0);
    }

    static String note(int perDay, int reward, int cost, int profileReward, int reviewReward)
    {
        return note(perDay, reward, cost, profileReward, reviewReward, 0, 0);
    }

    static String note(int perDay, int reward, int cost, int profileReward, int reviewReward, int spendPerYuan, int guideReward)
    {
        return "怎么得：" + (perDay > 0 ? "换工换宿完成后，每天得 " + perDay + " 积分" : "换工换宿积分暂时停发")
                + "；门店任务验收 1 小时 " + KgPointLedger.TASK_PER_HOUR + " 积分（生日当天双倍）；"
                + (reward > 0 ? "邀请的朋友完成第一次换工或线上任务，你得 " + reward + " 积分。" : "邀请奖励暂时停发。")
                + (spendPerYuan > 0 ? "入住订单完成后，每消费 1 元得 " + spendPerYuan + " 积分（按实付减退款算）。" : "")
                + (guideReward > 0 ? "发布旅居攻略审核通过得 " + guideReward + " 积分。" : "")
                + (profileReward > 0 ? "完善个人资料得 " + profileReward + " 积分（一次）。" : "")
                + (reviewReward > 0 ? "第一次发布入住评价得 " + reviewReward + " 积分（一次）。" : "")
                + "\n怎么用：等门店审核的报名可以花 " + cost + " 积分加急投递，门店那边会排在前面并标「加急」。"
                + "\n可用积分 = 累计获得 − 已用掉的；成长等级按累计获得算，用掉积分不会降级。";
    }

    // ---------------------------------------------------------------- 一次性奖励（2026-10-17）

    /** 完善个人资料要填齐的 6 项（和「编辑资料」屏一致；手机号、实名不算） */
    static final String PROFILE_FIELDS = "nickname, avatar, gender, birthday, city, intro";

    /**
     * 「完善个人资料」「首次入住评价」两项一次性奖励：每人各一次，记账用 biz_id = 用户自己（uk_point_biz 保证并发、重试都只记一条）。
     * 不在保存资料 / 提交评价的那一刻发，而是每次读积分总览时补：这样后台审核通过的评价、上线前就填齐的资料也能领到，
     * 不用另找入口改代码。返回两项是否已经发过（profile / stay_review），前端「赚取积分」按它显示「已完成」。
     * 查不到、出错只记日志，不影响积分页
     */
    Map<String, Boolean> oneTimeRewards(Long uid)
    {
        Map<String, Boolean> done = new LinkedHashMap<String, Boolean>();
        done.put("profile", false);
        done.put("stay_review", false);
        if (uid == null)
        {
            return done;
        }
        try
        {
            for (Map<String, Object> r : db.list("select biz_type from kg_point_entry where app_user_id = :u and biz_id = :u and biz_type in ('profile', 'stay_review')", p("u", uid)))
            {
                done.put(KgJdbc.asStr(r.get("bizType")), true);
            }
            int profileReward = ledger.profileReward();
            if (!done.get("profile") && profileReward > 0
                    && profileComplete(db.one("select " + PROFILE_FIELDS + " from kg_app_user where user_id = :u and del_flag = '0'", p("u", uid))))
            {
                ledger.award(uid, profileReward, "完善个人资料", "profile", uid);
                done.put("profile", true);
            }
            int reviewReward = ledger.stayReviewReward();
            // 只算已经公开展示的入住评价（status=0）：含联系方式等转人工复核的，审核通过后下次打开积分页再发
            if (!done.get("stay_review") && reviewReward > 0
                    && db.count("select count(*) from kg_review where app_user_id = :u and biz_type = 'stay_order' and status = '0' and del_flag = '0'", p("u", uid)) > 0)
            {
                ledger.award(uid, reviewReward, "首次入住评价", "stay_review", uid);
                done.put("stay_review", true);
            }
        }
        catch (RuntimeException e)
        {
            log.warn("一次性积分奖励检查失败 uid={}：{}", uid, e.getMessage());
        }
        return done;
    }

    /** 头像、昵称、性别（男 / 女）、生日、常驻城市、自我介绍都填了 */
    static boolean profileComplete(Map<String, Object> u)
    {
        if (u == null)
        {
            return false;
        }
        for (String k : new String[] { "nickname", "avatar", "birthday", "city", "intro" })
        {
            String v = KgJdbc.asStr(u.get(k));
            if (v == null || v.trim().isEmpty())
            {
                return false;
            }
        }
        String g = KgJdbc.asStr(u.get("gender"));
        return "1".equals(g) || "2".equals(g);
    }

    /**
     * 会员信息屏「赚取积分」：只列真的会发积分的事（分数取参数设置里的值，填 0 暂停的不列；已完成的一次性任务照样列，显示「已完成」）。
     * action 是前端「去完成」的去处：profile 编辑资料、review 我的评价、guide 发布页（预选旅居攻略）、invite 邀请有礼、volunteer 换工岗位列表
     */
    static List<Map<String, Object>> tasks(int perDay, int reward, int profileReward, int reviewReward, Map<String, Boolean> done)
    {
        return tasks(perDay, reward, profileReward, reviewReward, 0, KgPointLedger.DEFAULT_GUIDE_DAILY_CAP, done);
    }

    static List<Map<String, Object>> tasks(int perDay, int reward, int profileReward, int reviewReward, int guideReward, int guideCap, Map<String, Boolean> done)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        boolean profileDone = Boolean.TRUE.equals(done.get("profile")), reviewDone = Boolean.TRUE.equals(done.get("stay_review"));
        if (profileReward > 0 || profileDone)
        {
            out.add(task("profile", "完善个人资料", profileReward > 0 ? profileReward + "积分" : "", "填齐头像、生日等 6 项资料", profileDone, "profile"));
        }
        if (reviewReward > 0 || reviewDone)
        {
            out.add(task("stay_review", "首次入住评价", reviewReward > 0 ? reviewReward + "积分" : "", "住完给小院写一条评价", reviewDone, "review"));
        }
        // 2026-10-17 主人样稿「发布攻略 20积分」：可以反复做（每篇审核通过都发，每天最多 guideCap 篇），所以不显示「已完成」
        if (guideReward > 0)
        {
            out.add(task("guide", "发布攻略", guideReward + "积分", "旅居攻略审核通过后到账，每天最多 " + guideCap + " 篇", false, "guide"));
        }
        if (reward > 0)
        {
            out.add(task("invite", "邀请好友", reward + "积分/人", "朋友首次完成换工或线上任务后到账", false, "invite"));
        }
        if (perDay > 0)
        {
            out.add(task("volunteer", "换工换宿", perDay + "积分/天", "换工完成后按天数发放", false, "volunteer"));
        }
        return out;
    }

    private static Map<String, Object> task(String key, String title, String points, String sub, boolean done, String action)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("title", title);
        m.put("points", points);
        m.put("sub", sub);
        m.put("done", done);
        m.put("action", action);
        return m;
    }

    /** 会员信息屏底部「积分规则」：一条一行（前端编 01、02…），分数全部来自参数设置；停发的写「暂时停发」 */
    static List<Map<String, Object>> ruleRows(int perDay, int reward, int cost, int profileReward, int reviewReward)
    {
        return ruleRows(perDay, reward, cost, profileReward, reviewReward, 0, 0);
    }

    /**
     * 2026-10-17 加上主人样稿的两条：「消费获得：每消费1元累计1积分」「攻略获得：发布攻略审核通过获得20积分」（字照样稿，数字来自参数设置；
     * 前端显示成「标题：说明」），排在邀请获得后面、一次性奖励前面；填 0 暂停的不列（和资料获得、评价获得一样）
     */
    static List<Map<String, Object>> ruleRows(int perDay, int reward, int cost, int profileReward, int reviewReward, int spendPerYuan, int guideReward)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        out.add(rule("换工换宿", perDay > 0 ? "换工换宿完成后，每天获得 " + perDay + " 积分" : "换工换宿积分暂时停发"));
        out.add(rule("门店任务", "门店任务验收后，每小时获得 " + KgPointLedger.TASK_PER_HOUR + " 积分，生日当天双倍"));
        out.add(rule("邀请获得", reward > 0 ? "邀请的朋友完成第一次换工或线上任务，获得 " + reward + " 积分" : "邀请奖励暂时停发"));
        if (spendPerYuan > 0)
        {
            out.add(rule("消费获得", "每消费1元累计" + spendPerYuan + "积分"));
        }
        if (guideReward > 0)
        {
            out.add(rule("攻略获得", "发布攻略审核通过获得" + guideReward + "积分"));
        }
        if (profileReward > 0)
        {
            out.add(rule("资料获得", "头像、昵称、性别、生日、常驻城市、自我介绍都填好，获得 " + profileReward + " 积分（一次）"));
        }
        if (reviewReward > 0)
        {
            out.add(rule("评价获得", "第一次发布入住评价并公开展示后，获得 " + reviewReward + " 积分（一次）"));
        }
        out.add(rule("积分使用", "等门店审核的报名可以花 " + cost + " 积分加急投递；成长等级按累计获得算，用掉积分不降级"));
        return out;
    }

    private static Map<String, Object> rule(String title, String text)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("title", title);
        m.put("text", text);
        return m;
    }

    static List<Map<String, Object>> ways(int perDay, int reward, int cost)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        out.add(way("heart-handshake", "换工换宿", perDay > 0 ? perDay + "分/天" : "暂停"));
        out.add(way("clipboard-check", "门店任务", KgPointLedger.TASK_PER_HOUR + "分/小时"));
        out.add(way("user-plus", "邀请朋友", reward > 0 ? reward + "分/人" : "暂停"));
        out.add(way("zap", "加急投递", "用" + cost + "分/次"));
        return out;
    }

    private static Map<String, Object> way(String icon, String title, String sub)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("icon", icon);
        m.put("title", title);
        m.put("sub", sub);
        return m;
    }

    // ---------------------------------------------------------------- 加急投递

    /** 报名进度页用：这条报名能不能加急、要花多少、我还有多少 */
    public Map<String, Object> urgentInfo(Long uid, Long appId)
    {
        Map<String, Object> a = appId == null ? null
                : db.one("select app_id, app_user_id, status, urgent_flag, urgent_time, del_flag from kg_application where app_id = :a", p("a", appId));
        requireOwn(a, uid);
        int cost = ledger.urgentCost();
        long balance = ledger.totals(uid).balance();
        boolean urgent = "1".equals(KgJdbc.asStr(a.get("urgentFlag")));
        boolean waiting = URGENT_STATUS.equals(KgJdbc.asStr(a.get("status")));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("appId", appId);
        m.put("urgent", urgent);
        m.put("urgentTime", a.get("urgentTime"));
        m.put("waiting", waiting);
        m.put("canUrgent", waiting && !urgent);
        m.put("cost", cost);
        m.put("balance", balance);
        m.put("shortBy", Math.max(0, cost - balance));
        m.put("label", "加急投递（" + cost + " 积分）");
        m.put("tip", urgent ? "已加急，门店那边会排在前面并标「加急」"
                : (waiting ? "花 " + cost + " 积分加急，门店那边会排在前面并标「加急」" : "门店已经处理了这条报名，不需要加急"));
        return m;
    }

    /**
     * 花积分把自己的一条报名标成加急。先锁住自己的账号行（同一个人并发点两次只有一次能扣），再锁报名行（和门店审核互斥），
     * 余额够才记一条负数流水并打上加急标记；每条报名只能加急一次。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> urgent(Long uid, Long appId)
    {
        Map<String, Object> me = db.one("select user_id, status, del_flag from kg_app_user where user_id = :u for update", p("u", uid));
        if (me == null || !"0".equals(KgJdbc.asStr(me.get("status"))) || !"0".equals(KgJdbc.asStr(me.get("delFlag"))))
        {
            throw new ServiceException("账号不可用", 403);
        }
        Map<String, Object> a = appId == null ? null
                : db.one("select app_id, app_user_id, store_id, job_id, real_name, status, urgent_flag, del_flag from kg_application where app_id = :a for update", p("a", appId));
        requireOwn(a, uid);
        if ("1".equals(KgJdbc.asStr(a.get("urgentFlag"))))
        {
            throw new ServiceException("这条报名已经加急过了", 409);
        }
        if (!URGENT_STATUS.equals(KgJdbc.asStr(a.get("status"))))
        {
            throw new ServiceException("门店已经处理了这条报名，不能再加急", 409);
        }
        int cost = ledger.urgentCost();
        long balance = ledger.totals(uid).balance();
        if (balance < cost)
        {
            throw new ServiceException("积分不够，还差 " + (cost - balance) + " 分", 422);
        }
        Map<String, Object> job = db.one("select title from kg_job where job_id = :j", p("j", a.get("jobId")));
        String title = job == null || KgJdbc.asStr(job.get("title")) == null ? "岗位报名" : KgJdbc.asStr(job.get("title"));
        try
        {
            ledger.spend(uid, cost, "加急投递：" + title, "urgent", appId);
        }
        catch (DuplicateKeyException e)
        {
            throw new ServiceException("这条报名已经加急过了", 409);
        }
        if (db.update("update kg_application set urgent_flag = '1', urgent_time = now() where app_id = :a and urgent_flag = '0' and status = :s",
                p("a", appId, "s", URGENT_STATUS)) == 0)
        {
            throw new ServiceException("报名状态已变化，请刷新后再试", 409);
        }
        support.notifyStore(KgJdbc.asLong(a.get("storeId")), MessageCategory.APPLY, "有一份加急报名",
                MaskUtils.name(KgJdbc.asStr(a.get("realName"))) + " 加急了「" + title + "」的报名，已排在报名列表最前面，请优先审核。",
                "application", appId, "/pages/staff/applications?appId=" + appId);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("urgent", true);
        m.put("cost", cost);
        m.put("balance", balance - cost);
        m.put("message", "已加急，用了 " + cost + " 积分");
        return m;
    }

    private static void requireOwn(Map<String, Object> a, Long uid)
    {
        // 不区分「不存在」和「不是你的」，免得被人拿报名ID试探
        if (a == null || uid == null || !uid.equals(KgJdbc.asLong(a.get("appUserId"))) || !"0".equals(KgJdbc.asStr(a.get("delFlag"))))
        {
            throw new ServiceException("报名记录不存在", 404);
        }
    }

    // ---------------------------------------------------------------- 员工端展示

    /** 员工端报名列表：给还在等审核的加急报名标 urgent=true（一页一条查询，不逐行回查） */
    public void markUrgent(List<Map<String, Object>> rows)
    {
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> r : rows)
        {
            Long id = KgJdbc.asLong(r.get("appId"));
            if (id != null)
            {
                ids.add(id);
            }
        }
        if (ids.isEmpty())
        {
            return;
        }
        List<Long> urgent = new ArrayList<Long>();
        for (Map<String, Object> r : db.list("select app_id from kg_application where app_id in (:ids) and urgent_flag = '1' and status = :s",
                p("ids", ids, "s", URGENT_STATUS)))
        {
            urgent.add(KgJdbc.asLong(r.get("appId")));
        }
        for (Map<String, Object> r : rows)
        {
            r.put("urgent", urgent.contains(KgJdbc.asLong(r.get("appId"))));
        }
    }

    /**
     * 员工端报名详情补充：是否加急、换工完成情况，以及能不能点「确认换工完成」。
     * canManage 由控制器按本店角色算好传进来（主理人 / 管家，且不是本人的报名）。
     */
    public Map<String, Object> staffExtras(Long appId, String jobType, boolean canManage)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Map<String, Object> a = db.one("select status, arrival_date, stay_days, urgent_flag, urgent_time, volunteer_done_time, volunteer_days, volunteer_done_by "
                + "from kg_application where app_id = :a", p("a", appId));
        if (a == null)
        {
            return m;
        }
        String status = KgJdbc.asStr(a.get("status"));
        boolean volunteer = "volunteer".equals(jobType) || (memberRewards != null && memberRewards.enabled() && "parttime".equals(jobType));
        boolean done = a.get("volunteerDoneTime") != null;
        m.put("urgent", "1".equals(KgJdbc.asStr(a.get("urgentFlag"))) && URGENT_STATUS.equals(status));
        m.put("urgentTime", a.get("urgentTime"));
        m.put("volunteerDone", volunteer && done);
        m.put("volunteerDays", done ? a.get("volunteerDays") : null);
        m.put("volunteerDoneText", volunteer && done ? doneText(KgJdbc.asStr(a.get("volunteerDoneBy")), KgPointLedger.num(a.get("volunteerDays"))) : "");
        int max = volunteer ? maxDays(KgJdbc.asStr(a.get("arrivalDate")), (int) KgPointLedger.num(a.get("stayDays")), LocalDate.now()) : 0;
        boolean canFinish = canManage && volunteer && !done && "onboarded".equals(status) && max > 0;
        // 之前每天积分一律给后台配的数、提示语由页面自己拼（「系统按每天 N 积分发给 TA」）；现在计划在积分上线前就结束的换工
        // 确认完成也不发积分：每天积分给 0（确认弹窗就不会写「发多少积分」），提示语也由这里按情况给
        boolean beforeLaunch = canFinish && endedBeforeLaunch(KgJdbc.asStr(a.get("arrivalDate")), KgPointLedger.num(a.get("stayDays")), launchDate());
        // 会员规则开着时 complete() 按会员口径发分（换工满 30 天 20 分、兼职 1 天 3 分 / 连续 3 天及以上 15 分），不按每天积分：
        // 这里每天积分给 0（确认弹窗就不写「给 TA 发 N 积分」），提示语照会员口径说
        boolean member = !beforeLaunch && memberRewards != null && memberRewards.enabled();
        int perDay = beforeLaunch || member ? 0 : ledger.volunteerPerDay();
        m.put("canFinishVolunteer", canFinish);
        m.put("volunteerMaxDays", max);
        m.put("volunteerPerDay", perDay);
        m.put("volunteerFinishTip", canFinish ? (member ? memberFinishTip() : finishTip(beforeLaunch, perDay)) : "");
        return m;
    }

    static String doneText(String by, long days)
    {
        if ("migration".equals(by))
        {
            return "这段换工在积分功能上线前已结束，不计积分";
        }
        String who = "system".equals(by) ? "计划结束后系统自动确认完成" : "门店已确认换工完成";
        // 计分天数为 0：计划在积分上线前就结束、上线后才确认完成的（见 complete）
        return days <= 0 ? who + "。这段换工在积分功能上线前已结束，不计积分" : who + "，按 " + days + " 天计积分";
    }

    /** 员工端报名详情里「确认换工完成」的提示：上线前已结束的不计积分、运营暂停了换工积分、正常发积分三种说法 */
    static String memberFinishTip()
    {
        return "换工结束（或提前离开）后，点「确认换工完成」填实际天数，积分按会员规则发：换工满 30 天得 20 分（不满 30 天不计分），"
                + "单日兼职 3 分、连续 3 天及以上兼职 15 分；计划结束满 " + AUTO_FINISH_GRACE_DAYS + " 天、并且标记入职也满 " + ONBOARD_GRACE_DAYS
                + " 天还没确认的，系统按报名天数自动确认。";
    }

    static String finishTip(boolean beforeLaunch, int perDay)
    {
        String auto = "计划结束满 " + AUTO_FINISH_GRACE_DAYS + " 天、并且标记入职也满 " + ONBOARD_GRACE_DAYS + " 天还没确认的，系统按报名天数自动确认";
        if (beforeLaunch)
        {
            return "这段换工在积分功能上线前就已结束，确认完成后不计积分。请点「确认换工完成」填实际天数；" + auto + "。";
        }
        if (perDay <= 0)
        {
            return "换工积分暂时停发，确认完成后不发积分。换工结束（或提前离开）后，点「确认换工完成」填实际天数；" + auto + "。";
        }
        return "换工结束（或提前离开）后，点「确认换工完成」填实际天数，系统按每天 " + perDay + " 积分发给 TA；" + auto + "并发积分。";
    }

    /**
     * 门店最多能填几天：不超过报名天数、不超过到今天为止已经过去的天数（含到岗当天）、不超过 180 天。
     * 还没到岗（到岗日在今天之后）返回 0，表示现在还不能确认完成。
     */
    static int maxDays(String arrivalDate, int stayDays, LocalDate today)
    {
        if (arrivalDate == null || arrivalDate.length() < 10)
        {
            return 0;
        }
        LocalDate from = LocalDate.parse(arrivalDate.substring(0, 10));
        if (from.isAfter(today))
        {
            return 0;
        }
        long elapsed = ChronoUnit.DAYS.between(from, today) + 1;
        return (int) Math.min(Math.min(Math.max(1, stayDays), elapsed), MAX_VOLUNTEER_DAYS);
    }

    // ---------------------------------------------------------------- 换工完成

    /**
     * 门店确认换工完成。权限（本店主理人 / 管家）由控制器先校验；这里锁住报名行再核对一遍状态，
     * 天数不填就按「能填的最大天数」算。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> finishVolunteer(StaffSupport.Ctx c, Long appId, Integer days)
    {
        Map<String, Object> a = lockApplication(appId);
        if (a == null || !"0".equals(KgJdbc.asStr(a.get("delFlag"))) || c == null || c.storeId == null || !c.storeId.equals(KgJdbc.asLong(a.get("storeId"))))
        {
            throw new ServiceException("报名记录不存在", 404);
        }
        if (c.uid().equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            throw new ServiceException("不能确认自己的换工经历，请由本店其他换工主理人或小院主理人处理", 403);
        }
        if (!"volunteer".equals(KgJdbc.asStr(a.get("jobType"))) && !(memberRewards != null && memberRewards.enabled() && "parttime".equals(KgJdbc.asStr(a.get("jobType")))))
        {
            throw new ServiceException("仅换工、兼职服务可确认完成", 409);
        }
        if (!"onboarded".equals(KgJdbc.asStr(a.get("status"))))
        {
            throw new ServiceException("报名还没有标记入职，不能确认换工完成", 409);
        }
        if (a.get("volunteerDoneTime") != null)
        {
            throw new ServiceException("这段换工已经确认完成了", 409);
        }
        int max = maxDays(KgJdbc.asStr(a.get("arrivalDate")), (int) KgPointLedger.num(a.get("stayDays")), LocalDate.now());
        if (max <= 0)
        {
            throw new ServiceException("换工还没到岗，不能确认完成", 409);
        }
        int d = days == null ? max : days.intValue();
        if (d < 1 || d > max)
        {
            throw new ServiceException("实际天数请填 1–" + max + " 天", 422);
        }
        boolean beforeLaunch = endedBeforeLaunch(a);
        int points = complete(a, d, "staff:" + c.uid(), c.uid());
        support.op(c, "volunteer_done", "确认换工完成", "general", KgJdbc.asStr(a.get("title")) + " · " + d + " 天" + (beforeLaunch ? "（上线前已结束，不计积分）" : ""), true, null);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("days", d);
        m.put("points", points);
        m.put("message", points > 0 ? "已确认完成，给 TA 发了 " + points + " 积分"
                : (beforeLaunch ? "已确认完成。这段换工在积分功能上线前已结束，不计积分" : "已确认完成"));
        return m;
    }

    /**
     * 每半小时找一次「计划结束日已过 3 天、标记入职也满 3 天、门店还没确认」的换工，按报名天数自动完成；
     * 间隔可用 kangaroo.points.volunteer-scan-ms 调。入职时间 onboard_time 是门店点「标记入职」时写的，没有的不自动完成（门店可以手动确认）
     */
    @Scheduled(fixedDelayString = "${kangaroo.points.volunteer-scan-ms:1800000}", initialDelayString = "${kangaroo.points.volunteer-initial-ms:180000}")
    public void scanVolunteerCompletions()
    {
        List<Map<String, Object>> due;
        try
        {
            due = db.list("select a.app_id from kg_application a join kg_job j on j.job_id = a.job_id where a.status = 'onboarded' and a.del_flag = '0' "
                    + "and a.volunteer_done_time is null and j.job_type = 'volunteer' "
                    + "and date_add(a.arrival_date, interval a.stay_days + " + (AUTO_FINISH_GRACE_DAYS - 1) + " day) <= curdate() "
                    + "and a.onboard_time is not null and a.onboard_time <= date_sub(now(), interval " + ONBOARD_GRACE_DAYS + " day) order by a.app_id limit 100", p());
        }
        catch (RuntimeException e)
        {
            // 典型情况：V37 还没执行、列不存在。只记日志，不影响别的定时任务
            log.warn("换工自动完成扫描失败：{}", e.getMessage());
            return;
        }
        for (Map<String, Object> r : due)
        {
            try
            {
                self.autoFinishVolunteer(KgJdbc.asLong(r.get("appId")));
            }
            catch (RuntimeException e)
            {
                log.warn("换工自动完成失败 app={}：{}", r.get("appId"), e.getMessage());
            }
        }
    }

    /** 自动完成一条（锁住后再核对一遍：门店可能刚刚手动确认过） */
    @Transactional(rollbackFor = Exception.class)
    public boolean autoFinishVolunteer(Long appId)
    {
        Map<String, Object> a = lockApplication(appId);
        if (a == null || !"0".equals(KgJdbc.asStr(a.get("delFlag"))) || !"volunteer".equals(KgJdbc.asStr(a.get("jobType")))
                || !"onboarded".equals(KgJdbc.asStr(a.get("status"))) || a.get("volunteerDoneTime") != null)
        {
            return false;
        }
        String arrival = KgJdbc.asStr(a.get("arrivalDate"));
        long stay = Math.max(1, KgPointLedger.num(a.get("stayDays")));
        if (arrival == null || arrival.length() < 10
                || LocalDate.now().isBefore(LocalDate.parse(arrival.substring(0, 10)).plusDays(stay - 1 + AUTO_FINISH_GRACE_DAYS)))
        {
            return false;
        }
        // 之前计划结束日一过就自动完成；现在标记入职也要满 3 天（很晚才补标入职的，留时间给门店填实际天数），没有入职时间的不自动完成
        Date onboard = StaffSupport.parse(a.get("onboardTime"));
        if (onboard == null || System.currentTimeMillis() < onboard.getTime() + ONBOARD_GRACE_DAYS * 86400000L)
        {
            return false;
        }
        complete(a, (int) Math.min(stay, MAX_VOLUNTEER_DAYS), "system", null);
        return true;
    }

    /** 锁住报名行，顺带带出岗位类型和名称（岗位行不加锁） */
    private Map<String, Object> lockApplication(Long appId)
    {
        Map<String, Object> a = appId == null ? null
                : db.one("select app_id, app_user_id, store_id, job_id, status, del_flag, arrival_date, stay_days, onboard_time, volunteer_done_time "
                        + "from kg_application where app_id = :a for update", p("a", appId));
        if (a == null)
        {
            return null;
        }
        Map<String, Object> job = db.one("select job_type, title from kg_job where job_id = :j", p("j", a.get("jobId")));
        Map<String, Object> out = new LinkedHashMap<String, Object>(a);
        out.put("jobType", job == null ? null : job.get("jobType"));
        out.put("title", job == null || job.get("title") == null ? "换工岗位" : job.get("title"));
        return out;
    }

    /**
     * 记完成 + 发积分 + 通知 + 邀请首次奖励，必须在调用方的事务里。返回这次发出的积分（0 = 运营暂停了换工积分，或上线前已结束）。
     * 完成标记用条件更新（volunteer_done_time is null），同一条报名并发完成也只会成功一次。
     * confirmedBy = 点「确认换工完成」的员工账号（系统自动完成传 null），用来判断邀请奖励能不能发（见 KgInviteService.rewardInviter）。
     *
     * 之前只有 V37 迁移把「上线前已结束、当时已入职」的换工记成不计积分；上线时还停在已签约等状态、上线后才补标入职的，
     * 一完成照样按天数补发。现在不管门店确认还是系统自动完成，计划在上线日之前就结束的一律只记完成：计分天数记 0
     * （和迁移标记的一样，员工端显示「上线前已结束，不计积分」），不发积分、不发通知、不发邀请奖励。
     */
    int complete(Map<String, Object> a, int days, String by, Long confirmedBy)
    {
        Long appId = KgJdbc.asLong(a.get("appId"));
        Long uid = KgJdbc.asLong(a.get("appUserId"));
        int d = Math.max(1, Math.min(MAX_VOLUNTEER_DAYS, days));
        boolean beforeLaunch = endedBeforeLaunch(a);
        if (db.update("update kg_application set volunteer_done_time = now(), volunteer_days = :d, volunteer_done_by = :by "
                + "where app_id = :a and volunteer_done_time is null and status = 'onboarded'", p("d", beforeLaunch ? 0 : d, "by", StaffSupport.cut(by, 64), "a", appId)) == 0)
        {
            throw new ServiceException("这段换工已经确认完成了", 409);
        }
        if (beforeLaunch)
        {
            return 0;
        }
        if (memberRewards != null && memberRewards.enabled()) {
            memberRewards.sync(uid);
            int earned = KgMembershipPolicy.servicePoints(KgJdbc.asStr(a.get("jobType")), d);
            messages.send(uid, "user", MessageCategory.SYSTEM, "服务已完成", "已确认完成" + d + "天服务" + (earned > 0 ? "，获得" + earned + "积分。" : "。") + "可以分享真实体验了。", "application", appId, reviewRoute(appId));
            return earned;
        }
        int perDay = ledger.volunteerPerDay();
        int points = d * perDay;
        boolean awarded = ledger.award(uid, points, "换工换宿完成：" + a.get("title") + "（" + d + " 天）", "volunteer", appId);
        // 2026-10-16 内测反馈 2.0：完成消息顺带请 TA 评价这家店，点开直接进写评价页（之前进积分页，评价入口只在「我的」里，没人找得到）。
        // 门店手动确认（finishVolunteer）和系统自动完成都走这里；运营暂停了换工积分（每天 0 分）也照样发，只是不写积分。
        // 积分流水已经存在（awarded=false 且 points>0）说明这条通知发过了，不再重发。
        // 已经评价过这段经历的（入职后就能评）不再请 TA 评价，点开还是进积分页
        if (awarded || points <= 0)
        {
            boolean reviewed = db.count("select count(*) from kg_review where biz_type = 'application' and biz_id = :a and app_user_id = :u",
                    p("a", appId, "u", uid)) > 0;
            messages.send(uid, "user", MessageCategory.SYSTEM, "换工换宿已完成", completionText(awarded ? points : 0, reviewed),
                    "application", appId, reviewed ? "/pages/profile/index?s=3" : reviewRoute(appId));
        }
        invites.rewardInviter(uid, "volunteer", KgJdbc.asLong(a.get("storeId")), confirmedBy);
        return awarded ? points : 0;
    }

    /** 换工完成消息正文：「换工换宿已完成，获得 N 积分。说说这家店怎么样？」；没发积分时不提积分，已评价过的不再问 */
    static String completionText(int points, boolean reviewed)
    {
        return "换工换宿已完成" + (points > 0 ? "，获得 " + points + " 积分。" : "。") + (reviewed ? "" : "说说这家店怎么样？");
    }

    /** 写评价页（和「我的订单」里「去评价」按钮同一个地址） */
    static String reviewRoute(Long appId)
    {
        return "/pages/profile/review?bizType=application&bizId=" + appId;
    }

    // ---------------------------------------------------------------- 积分上线日

    /**
     * 积分功能上线日 = 迁移账本 kg_schema_version 里 V37 的执行日期。查到就缓存；账本里没有 V37（本地库、测试库）
     * 或查询出错时返回 null，表示不限制，下次再查（主键查询，很便宜）。
     */
    LocalDate launchDate()
    {
        LocalDate d = launchDate;
        if (d != null)
        {
            return d;
        }
        try
        {
            Map<String, Object> r = db.one("select date(applied_time) d from kg_schema_version where version = :v", p("v", LAUNCH_VERSION));
            String s = r == null ? null : KgJdbc.asStr(r.get("d"));
            if (s != null && s.length() >= 10)
            {
                d = LocalDate.parse(s.substring(0, 10));
                launchDate = d;
            }
        }
        catch (RuntimeException e)
        {
            log.warn("读取积分上线日失败，暂按不限制处理：{}", e.getMessage());
        }
        return d;
    }

    private boolean endedBeforeLaunch(Map<String, Object> a)
    {
        return endedBeforeLaunch(KgJdbc.asStr(a.get("arrivalDate")), KgPointLedger.num(a.get("stayDays")), launchDate());
    }

    /**
     * 这段换工是不是计划在上线日之前就已结束（最后一天在上线日之前）：到岗日 + 报名天数 <= 上线日，和 V37 迁移标记老数据同一口径。
     * 没有上线日、到岗日不全时按「不是」算。
     */
    static boolean endedBeforeLaunch(String arrivalDate, long stayDays, LocalDate launch)
    {
        if (launch == null || arrivalDate == null || arrivalDate.length() < 10)
        {
            return false;
        }
        return !LocalDate.parse(arrivalDate.substring(0, 10)).plusDays(Math.max(1, stayDays)).isAfter(launch);
    }
}
