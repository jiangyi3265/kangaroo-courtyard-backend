package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 2026-10-17 主人样稿「积分规则」里还没做的两条：
 *
 *   1. 消费获得：入住订单（kg_stay_order）走到「已完成」时发一次，积分 = 实付 − 已退款（按元取整，不足 1 元不算）× 每元积分
 *      （kangaroo.points.spendPerYuan，默认 1，0 = 暂停）。完成有两条路：前台办理退房（KgStayPmsService.checkOut）、
 *      离店后 2 小时定时自动完成（KgStayOrderService.progress），两边都调 stayCompleted。每张订单只发一次（biz_type = stay_spend、biz_id = 订单）。
 *      已完成的订单还能退款（门店在旅居订单台主动退款，订单状态不变；后台直接对支付单退款），所以每笔退款到账（KgStayOrderService.onRefunded）
 *      都调 stayRefunded：按退款后的实付重算这张单应得多少，比当初发的少就扣回差额。扣回只有一条流水（biz_type = stay_spend_back），
 *      记「一共扣回多少」，多次退款时只会越扣越多、不会超过当初发的，同一笔退款回调重复来也不会多扣。
 *      活动报名（kg_activity_signup）不发：报名付款后一直停在「已报名」，活动结束只是活动整体改成 ended，没有逐条报名的完成点，结束后还能退款。
 *   2. 攻略获得：旅居攻略（帖子类型代码 daily）变成已发布时发一次（kangaroo.points.guideReward，默认 20，0 = 暂停）。
 *      已发布有两条路：发帖时机审直接通过（KgPostService.publish）、后台审核通过（KgPostService.audit）。每篇只发一次（biz_type = guide、biz_id = 帖子）；
 *      防刷：每人每天最多奖励 kangaroo.points.guideDailyCap 篇（默认 3），超出的那篇不发、之后也不补。
 *      帖子之后被删除、被隐藏不扣回（主人没要求，扣回还要处理「隐藏后又恢复」，先不做）。
 *
 * 都不在业务事务里发：业务事务提交之后另开一个新事务发（没有外层事务时直接开新事务），发积分出任何错只记日志，
 * 不会让退房、自动完成、退款回调、发帖、审核失败或回滚（和 KgReviewStats 一样的做法）。新事务（读已提交）里先锁住订单行 / 用户行再算，
 * 和退款回调（同样锁订单行，支付单退款额也在同一个事务里改）互斥：发积分时读到的已退款金额一定是最新的，扣回时也一定看得到已经发出的积分。
 * 万一事务提交后进程正好挂了、积分没发出去，不补发（只记日志）；同一来源有唯一键，任何情况下都不会重复发。
 */
@Component
public class KgPointRewards
{
    private static final Logger log = LoggerFactory.getLogger(KgPointRewards.class);

    /** 消费积分的流水类型；退款扣回的是 KgPointLedger.REFUND_BACK_TYPE */
    static final String STAY_TYPE = "stay_spend";

    static final String GUIDE_TYPE = "guide";

    /** 旅居攻略在 kg_post.kind 里的代码（原「旅居日常」，只改了显示名） */
    static final String GUIDE_KIND = "daily";

    /** 一张订单最多发这么多消费积分（delta 是 int；正常订单远到不了） */
    static final int MAX_STAY_POINTS = 10000000;

    @Autowired(required=false) private KgMembershipRewards memberRewards;
    @Autowired
    private KgJdbc db;

    @Autowired
    private KgPointLedger ledger;

    @Autowired(required = false)
    private PlatformTransactionManager txManager;

    // ---------------------------------------------------------------- 入口（业务代码调这三个）

    /** 入住订单刚变成已完成（在完成它的事务里调；事务提交后才发） */
    public void stayCompleted(Long orderId)
    {
        if (orderId != null)
        {
            later("入住消费积分 order=" + orderId, () -> grantStay(orderId));
        }
    }

    /** 入住订单的一笔退款到账（在退款回调的事务里调；提交后按最新已退金额扣回多发的消费积分） */
    public void stayRefunded(Long orderId)
    {
        if (orderId != null)
        {
            later("入住退款扣回积分 order=" + orderId, () -> reverseStay(orderId));
        }
    }

    /** 帖子刚变成已发布（发帖机审直接通过、后台审核通过）；不是旅居攻略的在发放时跳过 */
    public void postOnline(Long postId)
    {
        if (memberRewards != null && memberRewards.enabled()) return;
        if (postId != null)
        {
            later("攻略积分 post=" + postId, () -> grantGuide(postId));
        }
    }

    // ---------------------------------------------------------------- 消费获得

    /** 发这张订单的消费积分，返回这次发出的分数（0 = 不该发、已经发过、或暂停） */
    int grantStay(Long orderId)
    {
        if (memberRewards != null && memberRewards.enabled()) {
            Map<String,Object> row=db.one("select app_user_id from kg_stay_order where order_id=:id",p("id",orderId));
            if(row!=null)memberRewards.sync(KgJdbc.asLong(row.get("appUserId")));
            return 0;
        }
        int rate = ledger.spendPerYuan();
        if (rate <= 0)
        {
            return 0;
        }
        Map<String, Object> o = lockOrder(orderId);
        if (o == null || !"completed".equals(KgJdbc.asStr(o.get("status"))))
        {
            return 0;
        }
        int points = stayPoints(netPaidFen(o), rate);
        Long uid = KgJdbc.asLong(o.get("appUserId"));
        return points > 0 && ledger.award(uid, points, "入住消费 订单号" + KgJdbc.asStr(o.get("orderNo")), STAY_TYPE, orderId) ? points : 0;
    }

    /** 退款到账后扣回多发的消费积分，返回扣回后的累计扣回数（0 = 这张单没发过消费积分，或不需要扣） */
    int reverseStay(Long orderId)
    {
        if(memberRewards!=null && memberRewards.enabled()) { Map<String,Object> r=db.one("select app_user_id from kg_stay_order where order_id=:o",p("o",orderId));if(r!=null)memberRewards.sync(KgJdbc.asLong(r.get("appUserId"))); }
        Map<String, Object> o = lockOrder(orderId);
        if (o == null)
        {
            return 0;
        }
        Long uid = KgJdbc.asLong(o.get("appUserId"));
        Map<String, Object> grant = db.one("select delta from kg_point_entry where biz_type = :t and biz_id = :o and app_user_id = :u",
                p("t", STAY_TYPE, "o", orderId, "u", uid));
        long granted = grant == null ? 0 : KgPointLedger.num(grant.get("delta"));
        if (granted <= 0)
        {
            return 0;
        }
        Map<String, Object> back = db.one("select delta from kg_point_entry where biz_type = :t and biz_id = :o and app_user_id = :u",
                p("t", KgPointLedger.REFUND_BACK_TYPE, "o", orderId, "u", uid));
        long already = back == null ? 0 : -KgPointLedger.num(back.get("delta"));
        // 按现在的每元积分算应留多少；运营暂停了消费积分（0）时按默认每元 1 分算，免得暂停期间退款的人白留积分
        int rate = ledger.spendPerYuan();
        long keep = Math.min(granted, stayPoints(netPaidFen(o), rate > 0 ? rate : KgPointLedger.DEFAULT_SPEND_PER_YUAN));
        long total = granted - keep;
        if (total <= already)
        {
            return (int) already;
        }
        ledger.clawBack(uid, (int) total, "入住订单退款扣回 订单号" + KgJdbc.asStr(o.get("orderNo")), KgPointLedger.REFUND_BACK_TYPE, orderId);
        return (int) total;
    }

    /** 实付 − 已退（分）：以支付单为准（amount = 实际收款，refund_amount = 已到账的累计退款）；没有支付单的单算 0 */
    long netPaidFen(Map<String, Object> o)
    {
        String payNo = KgJdbc.asStr(o.get("payNo"));
        if (payNo == null || payNo.isEmpty())
        {
            return 0;
        }
        Map<String, Object> pay = db.one("select amount, refund_amount, status from kg_pay_order where pay_no = :n and del_flag = '0'", p("n", payNo));
        String st = pay == null ? null : KgJdbc.asStr(pay.get("status"));
        if (!"paid".equals(st) && !"part_refunded".equals(st) && !"refunded".equals(st))
        {
            return 0;
        }
        return Math.max(0, KgPointLedger.num(pay.get("amount")) - KgPointLedger.num(pay.get("refundAmount")));
    }

    /** 每消费 1 元得 rate 分：按元取整（99 分不算 1 元），退光了是 0 */
    static int stayPoints(long netFen, int rate)
    {
        if (netFen <= 0 || rate <= 0)
        {
            return 0;
        }
        return (int) Math.min((netFen / 100) * rate, MAX_STAY_POINTS);
    }

    private Map<String, Object> lockOrder(Long orderId)
    {
        return db.one("select order_id, order_no, app_user_id, status, pay_no from kg_stay_order where order_id = :id and del_flag = '0' for update",
                p("id", orderId));
    }

    // ---------------------------------------------------------------- 攻略获得

    /** 发这篇攻略的积分，返回这次发出的分数（0 = 不是已发布的旅居攻略、已经发过、今天已满、或暂停） */
    int grantGuide(Long postId)
    {
        int reward = ledger.guideReward();
        if (reward <= 0)
        {
            return 0;
        }
        Map<String, Object> post = db.one("select post_id, app_user_id, kind, title, status, del_flag from kg_post where post_id = :p", p("p", postId));
        if (post == null || !GUIDE_KIND.equals(KgJdbc.asStr(post.get("kind"))) || !"online".equals(KgJdbc.asStr(post.get("status")))
                || !"0".equals(KgJdbc.asStr(post.get("delFlag"))))
        {
            return 0;
        }
        Long uid = KgJdbc.asLong(post.get("appUserId"));
        // 锁住作者的账号行：同一个人两篇攻略同时审核通过，也只能一篇一篇数「今天发了几篇」，不会一起越过每日上限
        if (uid == null || db.one("select user_id from kg_app_user where user_id = :u for update", p("u", uid)) == null)
        {
            return 0;
        }
        if (db.one("select violation_id from kg_badge_violation where app_user_id=:u and violation_type='false_content' "
                + "and target_type='post' and target_id=:p for update", p("u", uid, "p", postId)) != null)
        {
            return 0;
        }
        if (db.count("select count(*) from kg_point_entry where biz_type = :t and biz_id = :p and app_user_id = :u", p("t", GUIDE_TYPE, "p", postId, "u", uid)) > 0)
        {
            return 0;
        }
        int cap = ledger.guideDailyCap();
        if (db.count("select count(*) from kg_point_entry where app_user_id = :u and biz_type = :t and create_time >= curdate()", p("u", uid, "t", GUIDE_TYPE)) >= cap)
        {
            log.info("攻略积分今天已满 {} 篇，不再发 uid={} post={}", cap, uid, postId);
            return 0;
        }
        String title = KgJdbc.asStr(post.get("title"));
        String reason = "攻略审核通过" + (title == null || title.trim().isEmpty() ? "" : "：" + title.trim());
        return ledger.award(uid, reward, reason, GUIDE_TYPE, postId) ? reward : 0;
    }

    // ---------------------------------------------------------------- 事务提交后另开事务

    private void later(String what, Runnable r)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    inNewTx(what, r);
                }
            });
        }
        else
        {
            inNewTx(what, r);
        }
    }

    private void inNewTx(String what, Runnable r)
    {
        try
        {
            if (txManager == null)
            {
                r.run();
                return;
            }
            TransactionTemplate tt = new TransactionTemplate(txManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            // 读已提交：等到订单行锁之后再读支付单，读到的一定是刚提交的退款（可重复读下快照可能在等锁之前就定了，读到旧的已退金额）
            tt.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tt.execute(status -> {
                r.run();
                return null;
            });
        }
        catch (RuntimeException e)
        {
            // 业务已经提交，积分没发成只记日志（同一来源只会发一次，不会因为这里出错而重复发）
            log.warn("{} 失败：{}", what, e.getMessage());
        }
    }
}
