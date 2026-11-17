package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 积分账本：kg_point_entry 的读写，以及运营在「系统管理 → 参数设置」里能改的三个积分数值。
 *
 * 口径（之前只有一个「积分 = 全部流水之和」，等级也按它算；现在积分能花了，分成三个数）：
 *   累计获得 earned  = 所有正数流水之和。成长等级只看它，所以花积分不会降级；
 *   已使用   spent   = 所有负数流水之和的绝对值（目前只有加急投递）；
 *   可用积分 balance = earned − spent（也就是全部流水之和），花积分时只看它够不够。
 *
 * 发积分一律 insert ignore 进唯一键 uk_point_biz (biz_type, biz_id, app_user_id)：同一件事（同一条报名、同一位被邀请人）
 * 不管重试几次、并发几次都只记一条。
 */
@Component
public class KgPointLedger
{
    public static final String KEY_VOLUNTEER_PER_DAY = "kangaroo.points.volunteerPerDay";

    public static final String KEY_INVITE_REWARD = "kangaroo.points.inviteReward";

    public static final String KEY_URGENT_COST = "kangaroo.points.urgentCost";

    /**
     * 2026-10-17 会员信息改版（主人样稿「赚取积分」）新增的两项一次性奖励：完善个人资料、首次入住评价。
     * 没有新迁移：参数表里没有这两项时按下面的默认值发；运营要改分数或暂停（填 0），在「参数设置」里新增同名参数即可
     */
    public static final String KEY_PROFILE_REWARD = "kangaroo.points.profileReward";

    public static final String KEY_STAY_REVIEW_REWARD = "kangaroo.points.stayReviewReward";

    /**
     * 2026-10-17 主人样稿「积分规则」里另外两条（见 KgPointRewards）：消费获得（入住订单完成后按实付减退款，每 1 元得几分）、
     * 攻略获得（旅居攻略审核通过得几分，每人每天最多奖励几篇）。同样没有新迁移，参数表里没有时按默认值；前两项填 0 = 暂停
     */
    public static final String KEY_SPEND_PER_YUAN = "kangaroo.points.spendPerYuan";

    public static final String KEY_GUIDE_REWARD = "kangaroo.points.guideReward";

    public static final String KEY_GUIDE_DAILY_CAP = "kangaroo.points.guideDailyCap";

    static final int DEFAULT_VOLUNTEER_PER_DAY = 10;

    static final int DEFAULT_INVITE_REWARD = 100;

    static final int DEFAULT_URGENT_COST = 50;

    static final int DEFAULT_PROFILE_REWARD = 20;

    static final int DEFAULT_STAY_REVIEW_REWARD = 10;

    static final int DEFAULT_SPEND_PER_YUAN = 1;

    static final int DEFAULT_GUIDE_REWARD = 20;

    static final int DEFAULT_GUIDE_DAILY_CAP = 3;

    /** 门店任务验收：1 小时 = 10 积分（写死在 KgTaskService.check 里，这里只用于展示规则） */
    static final int TASK_PER_HOUR = 10;

    /**
     * 入住订单完成后又退了款，扣回多发的消费积分记在这一类（2026-10-17）。它是负数，但不是「花掉」：
     * 累计获得里减掉（退了钱的消费不该帮人升级），已使用里不算；可用积分照样是全部流水之和
     */
    public static final String REFUND_BACK_TYPE = "stay_spend_back";

    /** 累计获得（成长等级用它算）。KgMemberService 的权益资格也用这条，保证各处等级一致。退款扣回的消费积分从这里减掉（2026-10-17） */
    public static final String EARNED_EXPR = "case when biz_type in ('member_redeem','member_redeem_refund','mall_redeem','mall_refund') then 0 when delta > 0 or biz_type in ('stay_spend_back','m_stay_back','m_review_back','m_quality_back','m_quality_adjust','m_penalty','m_jury_back','badge_penalty') then delta else 0 end";
    public static final String EARNED_SQL = "select coalesce(sum(" + EARNED_EXPR + "), 0) "
            + "from kg_point_entry where app_user_id = :u";

    /** 流水来源 → 给用户看的类别名 */
    static final Map<String, String> KINDS = new LinkedHashMap<String, String>();
    static
    {
        KINDS.put("volunteer", "换工换宿");
        KINDS.put("task_assignee", "门店任务");
        KINDS.put("invite", "邀请朋友");
        KINDS.put("urgent", "加急投递");
        KINDS.put("profile", "完善资料");
        KINDS.put("stay_review", "入住评价");
        KINDS.put("stay_spend", "入住消费");
        KINDS.put(REFUND_BACK_TYPE, "退款扣回");
        KINDS.put("guide", "发布攻略");
        KINDS.put("m_service", "完成换工/兼职"); KINDS.put("m_training", "全职培训");
        KINDS.put("m_stay", "住宿奖励"); KINDS.put("m_stay_back", "住宿奖励扣回");
        KINDS.put("m_ref_service", "推荐服务奖励"); KINDS.put("m_ref_training", "推荐培训奖励");
        KINDS.put("m_review", "真实评价"); KINDS.put("m_quality", "精华评价");
        KINDS.put("m_review_back", "评价奖励扣回"); KINDS.put("m_quality_back", "精华奖励扣回");
        KINDS.put("m_quality_adjust", "精华奖励校正");
        KINDS.put("badge_penalty", "核实违规奖励扣回");
        KINDS.put("m_jury", "有效大众评审"); KINDS.put("m_jury_quality", "优质评审"); KINDS.put("m_jury_back", "无效评审扣回");
        KINDS.put("m_repost", "官方转发"); KINDS.put("m_hot", "每周热门");
        KINDS.put("m_balance_clear", "会员复核积分清零");
        KINDS.put("member_redeem", "兑换免费间夜"); KINDS.put("member_redeem_refund", "撤销兑换");
        KINDS.put("mall_redeem", "兑换积分商品"); KINDS.put("mall_refund", "积分商品退回");
    }

    @Autowired
    private KgJdbc db;

    @Autowired(required = false)
    private ISysConfigService configs;

    /** 一次完整的积分账：累计获得、已使用、可用 */
    public static class Totals
    {
        public final long earned;

        public final long spent;

        public Totals(long earned, long spent)
        {
            this.earned = Math.max(0, earned);
            this.spent = Math.max(0, spent);
        }

        public long balance()
        {
            return earned - spent;
        }
    }

    // ---------------------------------------------------------------- 后台参数

    public int volunteerPerDay()
    {
        return read(KEY_VOLUNTEER_PER_DAY, DEFAULT_VOLUNTEER_PER_DAY, 0, 1000);
    }

    public int inviteReward()
    {
        return read(KEY_INVITE_REWARD, DEFAULT_INVITE_REWARD, 0, 10000);
    }

    public int urgentCost()
    {
        return read(KEY_URGENT_COST, DEFAULT_URGENT_COST, 1, 10000);
    }

    /** 完善个人资料（一次性）：0 = 暂停 */
    public int profileReward()
    {
        return read(KEY_PROFILE_REWARD, DEFAULT_PROFILE_REWARD, 0, 1000);
    }

    /** 首次入住评价（一次性）：0 = 暂停 */
    public int stayReviewReward()
    {
        return read(KEY_STAY_REVIEW_REWARD, DEFAULT_STAY_REVIEW_REWARD, 0, 1000);
    }

    /** 消费获得：每消费 1 元得几分（0 = 暂停；最多 100，多打几个 0 也不会一下子发出天量积分） */
    public int spendPerYuan()
    {
        return read(KEY_SPEND_PER_YUAN, DEFAULT_SPEND_PER_YUAN, 0, 100);
    }

    /** 攻略获得：旅居攻略审核通过得几分（0 = 暂停） */
    public int guideReward()
    {
        return read(KEY_GUIDE_REWARD, DEFAULT_GUIDE_REWARD, 0, 1000);
    }

    /** 攻略获得：每人每天最多奖励几篇（1–100；填 0 或乱填按默认 3 篇，不提供「不限」，免得刷帖刷积分） */
    public int guideDailyCap()
    {
        return read(KEY_GUIDE_DAILY_CAP, DEFAULT_GUIDE_DAILY_CAP, 1, 100);
    }

    /**
     * 读参数：没有这一项、不是整数、超出范围，一律按默认值算，不让一个填错的参数把发积分 / 加急整条路弄成 500，
     * 也不会因为多打了几个 0 一下子发出天量积分。参数服务本身出错（缓存连不上）同样按默认值。
     */
    int read(String key, int dft, int min, int max)
    {
        String v;
        try
        {
            v = configs == null ? null : configs.selectConfigByKey(key);
        }
        catch (RuntimeException e)
        {
            v = null;
        }
        return parse(v, dft, min, max);
    }

    static int parse(String v, int dft, int min, int max)
    {
        String t = v == null ? "" : v.trim();
        if (!t.matches("\\d{1,7}"))
        {
            return dft;
        }
        int n = Integer.parseInt(t);
        return n < min || n > max ? dft : n;
    }

    // ---------------------------------------------------------------- 读

    public Totals totals(Long uid)
    {
        // 2026-10-17：退款扣回的消费积分（REFUND_BACK_TYPE）算在累计获得里减掉，不算「已使用」；可用 = 获得 − 使用 仍等于全部流水之和
        Map<String, Object> r = uid == null ? null : db.one("select coalesce(sum(" + EARNED_EXPR + "),0) earned, "
                + "coalesce(sum((" + EARNED_EXPR + ")-delta),0) spent from kg_point_entry where app_user_id = :u", p("u", uid));
        return new Totals(num(r == null ? null : r.get("earned")), num(r == null ? null : r.get("spent")));
    }

    /** Current read for commands: a prior repeatable-read snapshot must not permit double spending. */
    public Totals lockedTotals(Long uid) {
        Map<String,Object> r=db.one("select coalesce(sum("+EARNED_EXPR+"),0) earned, coalesce(sum(("+EARNED_EXPR+")-delta),0) spent from kg_point_entry where app_user_id=:u for update",p("u",uid));
        return new Totals(num(r.get("earned")),num(r.get("spent")));
    }
    public long recordTotal(Long uid)
    {
        return db.count("select count(*) from kg_point_entry where app_user_id = :u", p("u", uid));
    }

    /** 积分明细（新的在前）：title 是记账时写下的原因，kind 是来源类别，pts 带正负号 */
    public List<Map<String, Object>> records(Long uid, int page, int size)
    {
        int n = Math.max(1, Math.min(50, size));
        int pg = Math.min(Math.max(page, 1), 100000);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select delta, reason, biz_type, create_time from kg_point_entry where app_user_id = :u order by id desc limit :o, :n",
                p("u", uid, "o", (pg - 1) * n, "n", n)))
        {
            long d = num(r.get("delta"));
            String bizType = KgJdbc.asStr(r.get("bizType"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("title", r.get("reason"));
            m.put("kind", KINDS.containsKey(bizType) ? KINDS.get(bizType) : (d >= 0 ? "获得积分" : "使用积分"));
            m.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "yyyy-MM-dd HH:mm"));
            m.put("delta", d);
            m.put("pts", (d >= 0 ? "+" : "") + d);
            rows.add(m);
        }
        return rows;
    }

    // ---------------------------------------------------------------- 写

    /**
     * 发积分。同一来源（bizType + bizId）给同一个人只记一次；返回 true = 这次真的记上了（重复调用返回 false，不报错）。
     * 积分为 0 或负数时什么都不做（运营把某项积分设为 0 = 暂停发放）。
     */
    public boolean award(Long uid, int points, String reason, String bizType, Long bizId)
    {
        if (uid == null || bizId == null || points <= 0)
        {
            return false;
        }
        return db.update("insert ignore into kg_point_entry (app_user_id, delta, reason, biz_type, biz_id, create_time) values (:u, :d, :r, :t, :b, now())",
                p("u", uid, "d", points, "r", StaffSupport.cut(reason, 64), "t", bizType, "b", bizId)) > 0;
    }

    /**
     * 花积分：记一条负数流水。调用方必须已经在同一个事务里锁住这个用户（select … for update）并核对过余额；
     * 同一来源重复花会撞唯一键（DuplicateKeyException），由调用方翻译成「已经用过了」。
     */
    public void spend(Long uid, int points, String reason, String bizType, Long bizId)
    {
        db.update("insert into kg_point_entry (app_user_id, delta, reason, biz_type, biz_id, create_time) values (:u, :d, :r, :t, :b, now())",
                p("u", uid, "d", -Math.abs(points), "r", StaffSupport.cut(reason, 64), "t", bizType, "b", bizId));
    }

    /**
     * 扣回积分（2026-10-17，入住订单完成后退款用）：同一来源只有一条负数流水，记的是「到现在一共扣回多少」。
     * 第一次扣插入一条；之后再退款要多扣时把这条改得更负（只会更负、不会变回去），时间改成这次扣的时间；
     * 同一笔退款回调重复来、算出来的总数一样，什么都不变。返回 false = 参数不对、没写。
     * 调用方必须已经锁住这张订单（和发积分、别的退款回调互斥），并先比过现有的扣回数、只在要多扣时才调
     */
    public boolean clawBack(Long uid, int totalBack, String reason, String bizType, Long bizId)
    {
        if (uid == null || bizId == null || totalBack <= 0)
        {
            return false;
        }
        // on duplicate key update 按顺序赋值：先用旧的 delta 判断要不要改时间，最后才改 delta
        db.update("insert into kg_point_entry (app_user_id, delta, reason, biz_type, biz_id, create_time) values (:u, :d, :r, :t, :b, now()) "
                + "on duplicate key update create_time = if(:d < delta, now(), create_time), delta = least(delta, :d)",
                p("u", uid, "d", -totalBack, "r", StaffSupport.cut(reason, 64), "t", bizType, "b", bizId));
        return true;
    }

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }
}
