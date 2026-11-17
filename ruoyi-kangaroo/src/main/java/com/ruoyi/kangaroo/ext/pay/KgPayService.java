package com.ruoyi.kangaroo.ext.pay;

import java.security.SecureRandom;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 支付单与退款核心（旅居订单、付费活动报名、增值服务共用）。
 *
 * 渠道由 kangaroo.pay.mode 决定：
 *   off    缺省。一切需要支付的下单都返回"在线支付暂未开通"，不会产生任何待付款单；
 *   mock   本地联调：客户端调 mock-confirm 即视为到账。只允许在微信登录联调模式下开启
 *          （KangarooProperties 启动时校验，那个模式本身只允许连本机库），生产不可能误开；
 *   wechat 真实微信支付：服务端商户签名、查单/通知确认到账，持久化异步退款。
 * 金额一律以分为单位的整数、由服务端按业务单计算，前端传来的金额不采信。
 * 确认到账与退款都用条件更新做状态 CAS：重复回调、并发确认只会成功一次。
 */
@Service
public class KgPayService
{
    public static final String MODE_OFF = "off";
    public static final String MODE_MOCK = "mock";
    public static final String MODE_WECHAT = "wechat";
    /**
     * 还没办完的微信退款：排队等提交 / 微信处理中 / 微信退款异常（要在商户平台处理）。只有这些挡下一笔退款；
     * closed（微信关闭，钱没有退出）和 success 都算办完。之前写的是 status<>'success'，一笔被关闭的退款会让这张支付单再也退不了（2026-10-13）
     */
    public static final String ACTIVE_REFUND_SQL = "status in ('queued','processing','abnormal')";
    /** 要有人处理才会好的微信退款错误码（每分钟重试也不会自己好）；网络超时、限流、微信繁忙这类会自己好，不在里面 */
    static final java.util.Set<String> PERMANENT_REFUND_ERRORS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "NOT_ENOUGH", "NO_AUTH", "SIGN_ERROR", "PARAM_ERROR", "INVALID_REQUEST", "USER_ACCOUNT_ABNORMAL", "MCH_NOT_EXISTS", "RESOURCE_NOT_EXISTS"));

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(KgPayService.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    @org.springframework.beans.factory.annotation.Value("${kangaroo.pay.accept-new:true}") private boolean acceptNew = true;
    @Autowired private WxPayGateway wechat;
    @Autowired private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }


    @Autowired
    private KgJdbc db;

    @Autowired
    private KangarooProperties properties;

    @Autowired(required = false)
    private List<KgPayListener> listeners;

    /** 微信退款退不出去 / 被关闭 / 异常时通知门店员工 */
    @Autowired
    private StaffSupport support;

    public String mode()
    {
        return properties.getPayMode();
    }

    public Map<String, Object> config()
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mode", mode());
        m.put("enabled", acceptNew && (MODE_MOCK.equals(mode()) || (MODE_WECHAT.equals(mode()) && wechat.enabled())));
        if (MODE_WECHAT.equals(mode())) m.put("storeIds", wechat.storeIds());
        m.put("mock", MODE_MOCK.equals(mode()));
        return m;
    }

    private void requireEnabled()
    {
        if (!acceptNew) throw new ServiceException("在线支付正在维护，请稍后重试", 503);
        if (!MODE_MOCK.equals(mode()) && !(MODE_WECHAT.equals(mode()) && wechat.enabled()))
        {
            throw new ServiceException("在线支付暂未开通，请联系商家线下沟通");
        }
    }

    /**
     * 为一张业务单建支付单；同一业务单已有待支付的单就复用，已支付则拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(String bizType, long bizId, long userId, Long storeId, long amount, String desc)
    {
        return create(bizType, bizId, userId, storeId, amount, desc, null);
    }

    /**
     * since 非空时只认这个时间点之后建的支付单：业务行会被复用（活动报名取消后再报名用的是同一个 signup_id），
     * 上一轮留下的「已支付」单不能挡住这一轮建新单（2026-09-28 复核发现：重新报名 500、候补转正失败还会把其他人的取消 / 退款一起回滚）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(String bizType, long bizId, long userId, Long storeId, long amount, String desc, Date since)
    {
        return createPayment(bizType,bizId,userId,storeId,amount,desc,since,true);
    }

    /** 仅限调用事务刚插入、尚无历史支付的新业务主键；业务主键/幂等键负责唯一性。
     * 不对不存在的支付范围加间隙锁，避免两个独立新订单彼此阻塞插入。
     */
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> createForNewBusiness(String bizType,long bizId,long userId,Long storeId,long amount,String desc)
    {
        return createPayment(bizType,bizId,userId,storeId,amount,desc,null,false);
    }

    /** 报名主键会复用，以已锁定报名当前周期的 pay_no 判重，不查历史支付空范围。 */
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> createForSignupCycle(long signupId,long userId,Long storeId,long amount,String desc)
    {
        requireEnabled();
        Map<String,Object> signup=db.must("select app_user_id,status,pay_no,fee_fen from kg_activity_signup where signup_id=:id and del_flag='0' for update",
                KgJdbc.p("id",signupId),"活动报名不存在");
        if(!Long.valueOf(userId).equals(KgJdbc.asLong(signup.get("appUserId"))) || !"pending_payment".equals(signup.get("status"))
                || amount<=0 || amount!=KgJdbc.asLong(signup.get("feeFen")))
            throw new ServiceException("活动报名付款状态或金额已变化",409);
        String current=KgJdbc.asStr(signup.get("payNo"));
        if(current!=null && !current.isEmpty())
        {
            Map<String,Object> existing;
            try {
                // The caller holds signup. Never wait for a callback holding payment and needing signup.
                existing=db.must("select * from kg_pay_order where pay_no=:n and del_flag='0' for update nowait",KgJdbc.p("n",current),"关联支付单不存在");
            } catch (org.springframework.dao.CannotAcquireLockException busy) {
                throw new ServiceException("支付正在处理，请刷新后重试",409);
            }
            if(!"activity_signup".equals(existing.get("bizType")) || !Long.valueOf(signupId).equals(KgJdbc.asLong(existing.get("bizId")))
                    || !Long.valueOf(userId).equals(KgJdbc.asLong(existing.get("appUserId"))) || !"pending".equals(existing.get("status"))
                    || amount!=KgJdbc.asLong(existing.get("amount"))) throw new ServiceException("该报名支付状态已变化",409);
            return existing;
        }
        Map<String,Object> created=createPayment("activity_signup",signupId,userId,storeId,amount,desc,null,false);
        db.update("update kg_activity_signup set pay_no=:n,update_time=now() where signup_id=:id",KgJdbc.p("n",created.get("payNo"),"id",signupId));
        return created;
    }

    private Map<String,Object> createPayment(String bizType,long bizId,long userId,Long storeId,long amount,String desc,Date since,boolean findExisting)
    {
        if("stay_order".equals(bizType)) com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireStayBooking(db);
        requireEnabled();
        if (amount <= 0)
        {
            throw new ServiceException("支付金额必须大于 0");
        }
        if (MODE_WECHAT.equals(mode())) {
            // Wishes are platform orders collected by the configured platform merchant.
            if (!"wish_order".equals(bizType) && !"insurance_apply".equals(bizType)) wechat.requireStore(storeId);
            if (amount > Integer.MAX_VALUE) throw new ServiceException("支付金额超出渠道范围");
            if (!java.util.Arrays.asList("stay_order", "service_order", "activity_signup", "wish_order", "insurance_apply").contains(bizType)) throw new ServiceException("该业务暂未开通收款");
            if ("activity_signup".equals(bizType) && db.count("select count(*) from kg_activity_signup s join kg_activity a on a.activity_id=s.activity_id where s.signup_id=:id and a.store_id=:store and a.source in ('yard','merchant')", KgJdbc.p("id", bizId, "store", storeId)) != 1)
                throw new ServiceException("仅支持已开通门店的活动收款", 409);
        }
        // 加锁读：并发提交的同一业务单的待支付单，普通读看不到（快照），会建出第二张可支付的单
        Map<String, Object> exist = findExisting ? db.one("select * from kg_pay_order where biz_type = :t and biz_id = :b and del_flag = '0' "
                + "and status in ('pending','paid','part_refunded')" + (since == null ? "" : " and create_time >= :since")
                + " order by pay_id desc limit 1 for update", since == null ? KgJdbc.p("t", bizType, "b", bizId) : KgJdbc.p("t", bizType, "b", bizId, "since", since)) : null;
        if (exist != null)
        {
            if (!"pending".equals(exist.get("status")))
            {
                throw new ServiceException("该订单已支付，请勿重复支付");
            }
            if (((Number) exist.get("amount")).longValue() == amount)
            {
                return exist;
            }
            // 金额变了（例如改了加购项）：旧的待支付单作废，按新金额重建
            if (!close(String.valueOf(exist.get("payNo")))) throw new ServiceException("原订单已支付，请刷新后查看", 409);
        }
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        String payNo = "KP" + new SimpleDateFormat("yyMMddHHmmss").format(new Date()) + String.format("%06d", RANDOM.nextInt(1000000));
        cols.put("pay_no", payNo);
        cols.put("biz_type", bizType);
        cols.put("biz_id", bizId);
        cols.put("app_user_id", userId);
        cols.put("store_id", storeId);
        cols.put("amount", amount);
        cols.put("description", desc.length() > 120 ? desc.substring(0, 120) : desc);
        cols.put("channel", mode());
        cols.put("status", "pending");
        cols.put("create_by", "app:" + userId);
        cols.put("create_time", new Date());
        db.insert("kg_pay_order", cols);
        if (MODE_WECHAT.equals(mode())) {
            Map<String,Object> user = db.must("select openid from kg_app_user where user_id=:u and status='0' and del_flag='0'", KgJdbc.p("u", userId), "微信账号不可用");
            String openid = KgJdbc.asStr(user.get("openid"));
            if (openid == null || !openid.matches("[A-Za-z0-9_-]{20,64}")) throw new ServiceException("请使用微信小程序登录后支付");
            db.insert("kg_wx_payment", KgJdbc.p("pay_no", payNo, "wx_mchid", wechat.merchantId(), "wx_appid", wechat.appId(), "wx_openid", openid));
        }
        return byNo(payNo);
    }

    public Map<String, Object> byNo(String payNo)
    {
        return mustPay("select * from kg_pay_order where pay_no = :n and del_flag = '0'", payNo);
    }

    /** 查支付单。db.must 查不到时不带码（接口 500，监控当成服务器报错）；支付单不存在是业务拒绝，改报 404 */
    private Map<String, Object> mustPay(String sql, String payNo)
    {
        try { return db.must(sql, KgJdbc.p("n", payNo), "支付单不存在"); }
        catch (ServiceException e) { throw new ServiceException(e.getMessage(), 404); }
    }

    /** 业务审批也必须先锁支付单，再改业务状态；调用者持有事务。 */
    public void lockPayment(String payNo)
    {
        mustPay("select pay_id from kg_pay_order where pay_no=:n and del_flag='0' for update", payNo);
    }

    private Map<String, Object> ownedPending(String payNo, long userId)
    {
        Map<String, Object> pay = byNo(payNo);
        if (((Number) pay.get("appUserId")).longValue() != userId)
        {
            throw new ServiceException("支付单不存在", 404);
        }
        if (!"pending".equals(pay.get("status")))
        {
            throw new ServiceException("paid".equals(pay.get("status")) ? "该订单已支付" : "支付单已失效，请重新下单", 409);
        }
        listener(String.valueOf(pay.get("bizType"))).beforePay(pay,userId);
        if ("service_order".equals(pay.get("bizType")) && db.count("select count(*) from kg_shop_order o join kg_stay_order s on s.order_no=o.stay_order_no "
                + "where o.order_id=:id and o.status='pending_payment' and s.status in ('confirmed','checked_in') and s.del_flag='0'",
                KgJdbc.p("id", pay.get("bizId"))) != 1)
        {
            throw new ServiceException("关联入住订单已失效，不能继续支付服务订单", 409);
        }
        // 有偿心愿 30 分钟支付时限：过了就不让拉起支付 / 联调确认（微信渠道另有 payableExpiry，联调渠道之前不查）
        if ("wish_order".equals(pay.get("bizType")) && db.count("select count(*) from kg_wish where wish_id=:id and pay_no=:n "
                + "and status='pending_payment' and expire_time>now()", KgJdbc.p("id", pay.get("bizId"), "n", payNo)) != 1)
        {
            throw new ServiceException("心愿已超过 30 分钟支付时限或状态已变化，请刷新查看；仍需帮忙请重新发布", 409);
        }
        return pay;
    }

    /** 拉起支付所需参数。mock 渠道返回 {channel:'mock'}，客户端随后调 mock-confirm。 */
    public Map<String, Object> prepay(String payNo, long userId)
    {
        requireEnabled();
        Map<String, Object> pay = ownedPending(payNo, userId);
        if("stay_order".equals(pay.get("bizType"))) com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireStayBooking(db);
        if (MODE_WECHAT.equals(mode())) {
            if (!MODE_WECHAT.equals(pay.get("channel"))) throw new ServiceException("支付渠道已更新，请重新下单", 409);
            // Commit the attempt before the HTTP call: timeout/restart must not forget a possibly payable remote order.
            tx().execute(status -> {
                lockPayment(payNo); Map<String,Object> current = ownedPending(payNo, userId);
                if("stay_order".equals(current.get("bizType"))) com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireStayBooking(db);
                Map<String,Object> wx = wxPay(current);
                if (!"wish_order".equals(current.get("bizType")) && !"insurance_apply".equals(current.get("bizType"))) wechat.requireStore(KgJdbc.asLong(current.get("storeId")));
                String expiry = payableExpiry(current);
                if (wx.get("wxExpireTime") != null) expiry = String.valueOf(wx.get("wxExpireTime"));
                if (expires(expiry).isBefore(Instant.now().plusSeconds(65))) throw new ServiceException("支付时限即将结束，请重新下单", 409);
                db.update("update kg_wx_payment set wx_requested=1,wx_expire_time=:e where pay_no=:n", KgJdbc.p("e", expiry, "n", payNo));
                return null;
            });
            return tx().execute(status -> {
                lockPayment(payNo); Map<String,Object> current = ownedPending(payNo, userId);
                if("stay_order".equals(current.get("bizType"))) com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireStayBooking(db);
                payableExpiry(current);
                Map<String,Object> wx = wxPay(current);
                Instant expiry = expires(String.valueOf(wx.get("wxExpireTime")));
                if (expiry.isBefore(Instant.now().plusSeconds(65))) throw new ServiceException("支付时限即将结束，请重新下单", 409);
                return wechat.prepay(wx, wxTime(expiry));
            });
        }
        if (!MODE_MOCK.equals(pay.get("channel"))) throw new ServiceException("支付渠道不匹配", 409);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("channel", MODE_MOCK);
        m.put("payNo", payNo);
        m.put("amount", pay.get("amount"));
        return m;
    }

    /**
     * 关闭待支付单（业务单超时 / 用户取消）。返回 true = 已关闭（或本来就关了），false = 已经付款，调用方应按已支付处理。
     * 真实渠道接入后要先向渠道关单再本地关单；当前 mock 渠道没有远端单，直接本地 CAS。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean close(String payNo)
    {
        if (payNo == null) return true;
        Map<String,Object> pay = db.one("select * from kg_pay_order where pay_no=:n for update", KgJdbc.p("n", payNo));
        if (pay == null || "closed".equals(pay.get("status"))) return true;
        if (!"pending".equals(pay.get("status"))) return false;
        if (MODE_WECHAT.equals(pay.get("channel"))) {
            Map<String,Object> wx = wxPay(pay);
            if (number(wx.get("wxRequested")) != 0) {
                Map<String,Object> remote = wechat.query(wx);
                if (remote == null) {
                    // A timed-out prepay can still arrive at WeChat. Keep inventory until its fixed expiry has passed.
                    if (wx.get("wxExpireTime") == null || Instant.now().isBefore(expires(String.valueOf(wx.get("wxExpireTime"))).plusSeconds(300)))
                        throw new ServiceException("微信支付状态确认中，请稍后重试", 409);
                } else {
                    validatePayment(wx, remote, false);
                    String state = String.valueOf(remote.get("trade_state"));
                    if ("SUCCESS".equals(state) || "REFUND".equals(state)) { applyWechatPayment(wx, remote); return false; }
                    if ("NOTPAY".equals(state)) {
                        try { wechat.close(wx); }
                        catch (WxPayGateway.GatewayException e) {
                            if (!"ORDERPAID".equals(e.code)) throw e;
                            applyWechatPayment(wx, wechat.query(wx)); return false;
                        }
                    } else if (!java.util.Arrays.asList("CLOSED", "REVOKED", "PAYERROR").contains(state)) {
                        throw new ServiceException("微信支付正在处理，请稍后重试", 409);
                    }
                }
            }
        }
        db.update("update kg_pay_order set status='closed',update_time=now() where pay_no=:n and status='pending'", KgJdbc.p("n", payNo));
        return true;
    }

    /** 联调渠道的"确认到账"。只在 mock 模式下可用。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> mockConfirm(String payNo, long userId)
    {
        if (!MODE_MOCK.equals(mode()))
        {
            throw new ServiceException("当前不是联调支付模式");
        }
        Map<String, Object> pay = ownedPending(payNo, userId);
        if("stay_order".equals(pay.get("bizType"))) com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireStayBooking(db);
        if (!MODE_MOCK.equals(pay.get("channel"))) throw new ServiceException("不能模拟确认真实支付");
        int n = db.update("update kg_pay_order set status = 'paid', paid_time = now(), channel_txn = :txn, update_time = now() "
                + "where pay_id = :id and status = 'pending'",
                KgJdbc.p("id", pay.get("payId"), "txn", "MOCK-" + payNo));
        if (n == 0)
        {
            throw new ServiceException("支付状态刚刚发生变化，请刷新后查看", 409);
        }
        Map<String, Object> paid = byNo(payNo);
        listener(String.valueOf(paid.get("bizType"))).onPaid(paid);
        return paid;
    }

    /**
     * 退款（全额或部分）。累计退款不超过实付；真实渠道先持久化，成功通知/查单后入账。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> refund(String payNo, long amount, String reason, String operator)
    {
        Map<String, Object> pay = db.must("select * from kg_pay_order where pay_no = :n and del_flag = '0' for update", KgJdbc.p("n", payNo), "支付单不存在");
        String st = String.valueOf(pay.get("status"));
        if (!"paid".equals(st) && !"part_refunded".equals(st))
        {
            throw new ServiceException("只有已支付的订单可以退款");
        }
        long paid = ((Number) pay.get("amount")).longValue();
        long refunded = ((Number) pay.get("refundAmount")).longValue();
        if (amount <= 0 || refunded + amount > paid)
        {
            throw new ServiceException("退款金额超出可退范围（最多 " + (paid - refunded) + " 分）");
        }
        if (MODE_WECHAT.equals(String.valueOf(pay.get("channel"))))
        {
            wechat.requireMerchant(wxPay(pay));
            Map<String,Object> active = db.one("select * from kg_pay_refund where pay_no=:n and " + ACTIVE_REFUND_SQL + " order by refund_seq desc limit 1 for update", KgJdbc.p("n", payNo));
            if (active != null) throw new ServiceException("该支付单有尚未完成的退款，请勿重复提交", 409);
            listener(String.valueOf(pay.get("bizType"))).beforeRefund(pay, amount);
            String refundNo="KR" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
            db.insert("kg_pay_refund", KgJdbc.p("refund_no", refundNo,
                    "pay_no", payNo, "amount", amount, "reason", cut(reason, 80), "operator", cut(operator, 64)));
            listener(String.valueOf(pay.get("bizType"))).onRefundRequested(pay,refundNo,amount);
            Map<String,Object> queued = new LinkedHashMap<>(pay);
            queued.put("refundStatus", "processing");
            return queued;
        }
        listener(String.valueOf(pay.get("bizType"))).beforeRefund(pay, amount);
        long total = refunded + amount;
        Map<String, Object> ps = new HashMap<String, Object>();
        ps.put("id", pay.get("payId"));
        ps.put("old", refunded);
        ps.put("amt", total);
        ps.put("st", total == paid ? "refunded" : "part_refunded");
        ps.put("r", reason == null ? "" : (reason.length() > 250 ? reason.substring(0, 250) : reason));
        ps.put("u", operator);
        int n = db.update("update kg_pay_order set refund_amount = :amt, status = :st, refund_time = now(), refund_reason = :r, "
                + "update_by = :u, update_time = now() where pay_id = :id and refund_amount = :old", ps);
        if (n == 0)
        {
            throw new ServiceException("退款状态刚刚发生变化，请刷新后重试");
        }
        Map<String, Object> after = byNo(payNo);
        listener(String.valueOf(after.get("bizType"))).onRefunded(after, amount);
        return after;
    }

    public String refundProgress(String payNo) {
        Map<String,Object> pay = byNo(payNo);
        Map<String,Object> row = db.one("select * from kg_pay_refund where pay_no=:n order by refund_seq desc limit 1", KgJdbc.p("n", payNo));
        if (row == null) return "暂无微信退款申请";
        String state = String.valueOf(row.get("status"));
        // closed 之前也说「需在商户平台处理」：其实钱没有退出，可以直接重新退款；渠道提示之前只有 NOT_ENOUGH 这类英文代码，后面加上白话。
        // 后台只有「全额退款」：被关闭的是部分退款（如客人按政策只退一部分）时提醒这个按钮会退得更多
        long closedFen = "closed".equals(state) ? number(row.get("amount")) : 0, leftFen = number(pay.get("amount")) - number(pay.get("refundAmount"));
        String label = "success".equals(state) ? "微信已确认退款成功" : "closed".equals(state) ? "微信已关闭这笔 ¥" + yuan(closedFen) + " 的退款，钱没有退出，可以重新退款"
                + (leftFen > closedFen ? "（「全额退款」会退回剩下的全部 ¥" + yuan(leftFen) + "，比被关闭的这笔多，请先和门店确认该退多少）" : "")
                : "abnormal".equals(state) ? "退款异常（常见原因是客人银行卡已注销），需在微信支付商户平台的退款管理里处理异常退款"
                : "queued".equals(state) ? "退款已登记，系统正在提交微信" : "退款处理中";
        String error = KgJdbc.asStr(row.get("lastError"));
        return label + "；退款单号：" + row.get("refundNo") + (error == null || error.isEmpty() ? "" : "；渠道提示：" + refundErrorWithCode(error));
    }

    /** Read-only channel recheck of the original insurance refund; never creates a replacement request. */
    @Transactional(rollbackFor=Exception.class)
    public String recheckInsuranceRefund(String payNo,String refundNo) {
        lockPayment(payNo);Map<String,Object> payment=byNo(payNo);
        if(!"insurance_apply".equals(payment.get("bizType")) || !MODE_WECHAT.equals(payment.get("channel")))throw new ServiceException("仅可核对原保险微信退款",409);
        Map<String,Object> row=db.must("select * from kg_pay_refund where pay_no=:p and refund_no=:n for update",KgJdbc.p("p",payNo,"n",refundNo),"退款单不存在");
        if("success".equals(row.get("status")))return "success";
        Map<String,Object> remote=wechat.queryRefund(refundNo);
        if(remote==null)throw new ServiceException("微信暂未返回明确退款结果，请稍后核对",409);
        applyWechatRefund(wxPay(payment),row,remote);
        return String.valueOf(remote.containsKey("refund_status")?remote.get("refund_status"):remote.get("status")).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 微信退款错误码翻成一句白话（门店消息、订单台时间线、后台「退款进度」共用）。认不出的微信错误码原样返回；
     * 不像错误码的（对账出错时记下的程序异常类名）说成「系统处理出错」。
     */
    public static String refundErrorText(String code)
    {
        if (code == null) return "";
        switch (code)
        {
            case "NOT_ENOUGH": return "商户号可用余额不足";
            case "NO_AUTH": return "商户号没有退款权限";
            case "SIGN_ERROR": return "商户签名校验失败（证书或密钥配置有误）";
            case "PARAM_ERROR": return "退款参数有误";
            case "INVALID_REQUEST": return "微信不受理这笔退款（如超过退款期限、订单已退完）";
            case "USER_ACCOUNT_ABNORMAL": return "客人的微信账户异常（如已注销）";
            case "MCH_NOT_EXISTS": return "商户号不存在";
            case "RESOURCE_NOT_EXISTS": return "微信查不到这笔支付";
            case "FREQUENCY_LIMITED": return "请求太频繁，被微信限流";
            case "SYSTEM_ERROR": return "微信系统繁忙";
            case "CHANNEL_UNAVAILABLE": return "暂时连不上微信支付";
            case "CHANNEL_ERROR": return "微信返回了认不出的错误";
            default: return code.matches("[A-Z0-9_]+") ? code : "系统处理出错（" + code + "）";
        }
    }

    /** 白话后面带上原始代码，方便对照微信文档；认不出的只出现一次 */
    static String refundErrorWithCode(String code)
    {
        String text = refundErrorText(code);
        return text.contains(code) ? text : text + "（" + code + "）";
    }

    /**
     * 门店主动退款前确认这笔钱能原路退回（2026-10-11 新增，旅居订单台用）：
     * 微信支付的单要求当前是微信渠道、而且这家门店在已开通收款的名单里；联调（mock）单只在联调模式下能退（本地验收用）。
     * 之前只有平台后台退款，渠道不对时报的是「支付商户配置与订单不匹配」这类技术话，主理人看不懂。
     */
    public void requireRefundable(Map<String, Object> payRow)
    {
        String channel = String.valueOf(payRow.get("channel"));
        if (MODE_WECHAT.equals(channel))
        {
            Long storeId = KgJdbc.asLong(payRow.get("storeId"));
            if (!MODE_WECHAT.equals(mode()) || !wechat.enabled() || storeId == null || !wechat.storeIds().contains(storeId))
            {
                throw new ServiceException("这家门店还没有开通微信收款，不能在这里退款，请联系平台在后台处理", 409);
            }
            return;
        }
        if (MODE_MOCK.equals(channel) && MODE_MOCK.equals(mode()))
        {
            return;
        }
        throw new ServiceException("这笔订单不是通过微信支付付的款，不能在这里原路退款，请联系平台处理", 409);
    }

    private Map<String,Object> wxPay(Map<String,Object> pay) {
        Map<String,Object> result = new LinkedHashMap<>(pay);
        result.putAll(db.must("select * from kg_wx_payment where pay_no=:n for update", KgJdbc.p("n", pay.get("payNo")), "支付商户记录缺失"));
        wechat.requireMerchant(result);
        return result;
    }
    private String payableExpiry(Map<String,Object> pay) {
        String type = String.valueOf(pay.get("bizType"));
        String sql;
        if ("insurance_apply".equals(type)) {
            Map<String,Object> insurance=db.must("select app_id from kg_insurance_apply where apply_id=:id",KgJdbc.p("id",pay.get("bizId")),"投保申请不存在");
            db.must("select app_id from kg_application where app_id=:id for update",KgJdbc.p("id",insurance.get("appId")),"报名不存在");
        }
        if ("stay_order".equals(type)) sql = "select expire_time from kg_stay_order where order_id=:id and pay_no=:n and status='pending_payment' and del_flag='0' for update";
        else if ("service_order".equals(type)) sql = "select expire_time from kg_shop_order where order_id=:id and pay_no=:n and status='pending_payment' and del_flag='0' for update";
        else if ("activity_signup".equals(type)) sql = "select expire_time from kg_activity_signup where signup_id=:id and pay_no=:n and status='pending_payment' and del_flag='0' for update";
        else if ("wish_order".equals(type)) sql = "select expire_time from kg_wish where wish_id=:id and pay_no=:n and status='pending_payment' for update";
        else if ("insurance_apply".equals(type)) sql = "select f.expire_time from kg_insurance_payment f join kg_insurance_apply i on i.apply_id=f.apply_id join kg_application a on a.app_id=i.app_id where f.apply_id=:id and f.pay_no=:n and f.status='pending' and i.status='pending_payment' and i.store_id=f.store_id and a.status in ('store_confirmed','contract_signed','onboarded') and a.del_flag='0' for update";
        else throw new ServiceException("不支持的支付业务");
        Map<String,Object> business;
        // 业务单已取消 / 已支付等：状态变了，按 409 报（db.must 不带码是 500）
        try { business = db.must(sql, KgJdbc.p("id", pay.get("bizId"), "n", pay.get("payNo")), "订单已失效，请刷新后查看"); }
        catch (ServiceException e) { throw new ServiceException(e.getMessage(), 409); }
        String expiry = String.valueOf(business.get("expireTime"));
        if (expires(expiry).isBefore(Instant.now().plusSeconds(65))) throw new ServiceException("订单支付时限即将结束，请重新下单", 409);
        return expiry;
    }
    // 微信要求 time_expire 形如 2026-10-11T14:14:00+08:00。之前用 OffsetDateTime.toString()：截止时间正好是整分（秒为 00）时
    // 会省掉秒写成 2026-10-11T14:14+08:00，微信判参数错误，页面只提示「微信支付暂时无法处理」（约 60 单碰上 1 单）；现在固定带秒
    private static final DateTimeFormatter WX_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    static String wxTime(Instant instant) { return WX_TIME.format(instant.atOffset(ZoneOffset.ofHours(8))); }
    private static Instant expires(String time) { return LocalDateTime.parse(time, DT).atZone(CHINA).toInstant(); }
    private static String cut(String value, int size) { return value == null ? "" : value.substring(0, Math.min(size, value.length())); }
    static long number(Object value) {
        try { return new BigDecimal(String.valueOf(value)).longValueExact(); }
        catch (Exception e) { throw new ServiceException("支付金额数据无效", 409); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if (!(value instanceof Map)) throw new ServiceException("支付渠道返回数据不完整", 409);
        return (Map<String,Object>) value;
    }
    private void validatePayment(Map<String,Object> pay, Map<String,Object> remote, boolean success) {
        if (remote == null || !pay.get("payNo").equals(remote.get("out_trade_no")) || !pay.get("wxMchid").equals(remote.get("mchid"))
                || !pay.get("wxAppid").equals(remote.get("appid"))) throw new ServiceException("支付通知与订单不匹配", 409);
        Map<String,Object> amount = object(remote.get("amount"));
        if (number(pay.get("amount")) != number(amount.get("total")) || !"CNY".equals(amount.get("currency")))
            throw new ServiceException("支付通知金额或币种不匹配", 409);
        if (success && (!pay.get("wxOpenid").equals(object(remote.get("payer")).get("openid"))
                || !"JSAPI".equals(remote.get("trade_type")) || !String.valueOf(remote.get("transaction_id")).matches("[0-9]{20,64}")))
            throw new ServiceException("支付身份或交易号不匹配", 409);
    }
    private void applyWechatPayment(Map<String,Object> pay, Map<String,Object> remote) {
        validatePayment(pay, remote, true);
        if (!java.util.Arrays.asList("SUCCESS", "REFUND").contains(remote.get("trade_state"))) throw new ServiceException("渠道尚未确认付款", 409);
        String transaction = String.valueOf(remote.get("transaction_id"));
        if (pay.get("channelTxn") != null && !transaction.equals(pay.get("channelTxn"))) throw new ServiceException("支付交易号冲突", 409);
        db.update("update kg_wx_payment set transaction_id=:t where pay_no=:n and (transaction_id is null or transaction_id=:t)", KgJdbc.p("t", transaction, "n", pay.get("payNo")));
        String paidAt;
        try { paidAt = OffsetDateTime.parse(String.valueOf(remote.get("success_time"))).atZoneSameInstant(CHINA).format(DT); }
        catch (Exception e) { throw new ServiceException("支付成功时间无效", 409); }
        if (db.update("update kg_pay_order set status='paid',paid_time=:at,channel_txn=:t,update_time=now() where pay_no=:n and status in ('pending','closed')",
                KgJdbc.p("t", transaction, "at", paidAt, "n", pay.get("payNo"))) > 0) {
            Map<String,Object> paid = byNo(String.valueOf(pay.get("payNo")));
            listener(String.valueOf(paid.get("bizType"))).onPaid(paid);
        }
    }
    public void notifyWechat(String body, String serial, String timestamp, String nonce, String signature, boolean refund) {
        Map<String,Object> remote = wechat.notification(body, serial, timestamp, nonce, signature, refund);
        tx().execute(status -> {
            String payNo = String.valueOf(remote.get("out_trade_no"));
            lockPayment(payNo);
            Map<String,Object> pay = byNo(payNo);
            if (!MODE_WECHAT.equals(pay.get("channel"))) throw new ServiceException("支付渠道不匹配", 409);
            Map<String,Object> wx = wxPay(pay);
            if (refund) {
                if (!wx.get("wxMchid").equals(remote.get("mchid"))) throw new ServiceException("退款商户不匹配", 409);
                Map<String,Object> row = db.must("select * from kg_pay_refund where refund_no=:n and pay_no=:p for update",
                        KgJdbc.p("n", remote.get("out_refund_no"), "p", payNo), "退款单不存在");
                applyWechatRefund(wx, row, remote);
            } else applyWechatPayment(wx, remote);
            return null;
        });
    }
    @Transactional(rollbackFor = Exception.class)
    public Map<String,Object> queryOwned(String payNo, long userId) {
        Map<String,Object> pay = byNo(payNo);
        if (number(pay.get("appUserId")) != userId) throw new ServiceException("支付单不存在", 404);
        if (MODE_WECHAT.equals(pay.get("channel")) && "pending".equals(pay.get("status"))) {
            lockPayment(payNo); pay = mustPay("select * from kg_pay_order where pay_no=:n for update", payNo);
            if ("pending".equals(pay.get("status"))) syncWechat(pay);
            pay = mustPay("select * from kg_pay_order where pay_no=:n for update", payNo);
        }
        Map<String,Object> out = new LinkedHashMap<>();
        for (String key : java.util.Arrays.asList("payNo", "bizType", "bizId", "amount", "channel", "status", "paidTime", "refundAmount", "refundTime")) out.put(key, pay.get(key));
        Map<String,Object> refund = db.one("select status from kg_pay_refund where pay_no=:n order by refund_seq desc limit 1", KgJdbc.p("n", payNo));
        if (refund != null) out.put("refundStatus", refund.get("status"));
        return out;
    }
    private void syncWechat(Map<String,Object> pay) {
        Map<String,Object> wx = wxPay(pay);
        if (number(wx.get("wxRequested")) == 0) return;
        if (wx.get("wxLastQueryTime") != null && expires(String.valueOf(wx.get("wxLastQueryTime"))).isAfter(Instant.now().minusSeconds(3))) return;
        db.update("update kg_wx_payment set wx_last_query_time=now() where pay_no=:n", KgJdbc.p("n", pay.get("payNo")));
        try {
            Map<String,Object> remote = wechat.query(wx);
            if (remote != null && java.util.Arrays.asList("SUCCESS", "REFUND").contains(remote.get("trade_state"))) applyWechatPayment(wx, remote);
        } catch (WxPayGateway.GatewayException e) { log.warn("Wechat payment query deferred pay={} code={}", pay.get("payNo"), e.code); }
    }
    private void applyWechatRefund(Map<String,Object> pay, Map<String,Object> row, Map<String,Object> remote) {
        Map<String,Object> amount = object(remote.get("amount"));
        String state = String.valueOf(remote.containsKey("refund_status") ? remote.get("refund_status") : remote.get("status"));
        String channelId = String.valueOf(remote.get("refund_id"));
        if (!row.get("refundNo").equals(remote.get("out_refund_no")) || !pay.get("payNo").equals(remote.get("out_trade_no"))
                || !java.util.Objects.equals(pay.get("channelTxn"), remote.get("transaction_id"))
                || number(pay.get("amount")) != number(amount.get("total")) || number(row.get("amount")) != number(amount.get("refund"))
                || (amount.get("currency") != null && !"CNY".equals(amount.get("currency")))
                || !channelId.matches("[0-9]{20,64}") || (row.get("channelRefundId") != null && !channelId.equals(row.get("channelRefundId")))
                || !java.util.Arrays.asList("SUCCESS", "PROCESSING", "ABNORMAL", "CLOSED").contains(state)) throw new ServiceException("退款通知与退款单不匹配", 409);
        if ("success".equals(row.get("status"))) return;
        // 本地已关闭的退款也是终态：它已经不挡新退款，迟到 / 重发的旧通知（处理中、异常）不能把它改回在途——
        // 否则同一张支付单会有两笔在途退款，再被查到「关闭」时还会把新发起的同金额门店退款误记成没退成。微信真退成了（SUCCESS）照常入账
        if ("closed".equals(row.get("status")) && !"SUCCESS".equals(state)) return;
        if (!"SUCCESS".equals(state)) {
            String next = state.toLowerCase(java.util.Locale.ROOT);
            db.update("update kg_pay_refund set status=:s,channel_refund_id=:c,update_time=now(),next_attempt_time=date_add(now(),interval 60 second),last_error=null where refund_no=:n",
                    KgJdbc.p("s", next, "c", channelId, "n", row.get("refundNo")));
            // 第一次变成关闭 / 异常时告诉门店（abnormal 每分钟还会查到一次，同样的状态不重复发）。之前只改状态，谁都不知道钱没退出去（2026-10-13）
            if (("closed".equals(next) || "abnormal".equals(next)) && !next.equals(row.get("status"))) {
                log.error("Wechat refund {} refund={} pay={}", state, row.get("refundNo"), pay.get("payNo"));
                // 关闭 = 钱没有退出：业务方把自己记着「等微信确认」的那笔改成没退成，不再挡下一笔；不自动重退
                if ("closed".equals(next)) {
                    Map<String,Object> callback=new LinkedHashMap<>(pay);callback.put("refundNo",row.get("refundNo"));
                    listener(String.valueOf(pay.get("bizType"))).onRefundClosed(callback, number(row.get("amount")));
                }
                notifyRefundTrouble(pay, row, next, null);
            }
            return;
        }
        long total = Math.addExact(number(pay.get("refundAmount")), number(row.get("amount")));
        if (total > number(pay.get("amount"))) throw new ServiceException("累计退款超出实付金额", 409);
        String successAt;
        try { successAt = OffsetDateTime.parse(String.valueOf(remote.get("success_time"))).atZoneSameInstant(CHINA).format(DT); }
        catch (Exception e) { throw new ServiceException("退款成功时间无效", 409); }
        db.update("update kg_pay_refund set status='success',channel_refund_id=:c,success_time=:at,update_time=now(),last_error=null where refund_no=:n",
                KgJdbc.p("c", channelId, "at", successAt, "n", row.get("refundNo")));
        db.update("update kg_pay_order set refund_amount=:a,status=:s,refund_time=:at,refund_reason=:r,update_by=:u,update_time=now() where pay_no=:n",
                KgJdbc.p("a", total, "s", total == number(pay.get("amount")) ? "refunded" : "part_refunded", "at", successAt, "r", row.get("reason"), "u", row.get("operator"), "n", pay.get("payNo")));
        Map<String,Object> after = byNo(String.valueOf(pay.get("payNo")));
        Map<String,Object> callback=new LinkedHashMap<>(after);callback.put("refundNo",row.get("refundNo"));
        listener(String.valueOf(after.get("bizType"))).onRefunded(callback, number(row.get("amount")));
    }
    /** Durable requests are committed with business approval; retries always use the original refund number and amount. */
    @Scheduled(fixedDelay = 30000, initialDelay = 45000)
    public void reconcileWechat() {
        if (!wechat.enabled()) return;
        for (Map<String,Object> candidate : db.list("select refund_no,pay_no from kg_pay_refund where status in ('queued','processing','abnormal') and next_attempt_time<=now() order by next_attempt_time limit 10", KgJdbc.p())) {
            try {
                tx().execute(status -> {
                    String payNo = String.valueOf(candidate.get("payNo")); lockPayment(payNo);
                    Map<String,Object> pay = wxPay(byNo(payNo));
                    Map<String,Object> refund = db.must("select * from kg_pay_refund where refund_no=:n for update", KgJdbc.p("n", candidate.get("refundNo")), "退款单不存在");
                    if ("success".equals(refund.get("status")) || "closed".equals(refund.get("status"))) return null;
                    db.update("update kg_pay_refund set attempts=attempts+1,next_attempt_time=date_add(now(),interval 60 second) where refund_no=:n", KgJdbc.p("n", refund.get("refundNo")));
                    try {
                        Map<String,Object> remote = wechat.queryRefund(String.valueOf(refund.get("refundNo")));
                        if (remote == null) {
                            try {
                                remote = wechat.refund(pay, refund);
                            } catch (WxPayGateway.GatewayException e) {
                                // 老资金流商户默认从「未结算资金」退：交易款已经结算走时微信回 NOT_ENOUGH 并提示用可用余额退。
                                // 按提示改从可用余额再提交一次（同一个退款单号，微信按单号去重，不会退两次）；可用余额也不够时照常记下错误、下一分钟再试
                                if (!"NOT_ENOUGH".equals(e.code) || !WxPayGateway.unsettledFundsHint(e.detail)) throw e;
                                log.warn("Wechat refund retry from available balance refund={} detail={}", refund.get("refundNo"), e.detail);
                                remote = wechat.refund(pay, refund, true);
                            }
                        }
                        applyWechatRefund(pay, refund, remote);
                    } catch (WxPayGateway.GatewayException e) {
                        String previous = KgJdbc.asStr(refund.get("lastError"));
                        boolean permanent = PERMANENT_REFUND_ERRORS.contains(e.code);
                        // 临时错误不覆盖已经记下的「要有人处理」的错误：余额不足期间偶尔一次网络超时，会让下一分钟的余额不足又算成新错误、再通知一遍门店
                        if (permanent || previous == null || !PERMANENT_REFUND_ERRORS.contains(previous))
                            db.update("update kg_pay_refund set last_error=:e,update_time=now() where refund_no=:n", KgJdbc.p("e", e.code, "n", refund.get("refundNo")));
                        // 要有人处理才会好的错误（余额不足、没有退款权限、签名错…）：每出现一种新的错误告诉门店一次、记 ERROR（WARN 不进日志文件）；
                        // 同一个错误每分钟重试不重复发。网络超时这类临时错误照旧只记 WARN，下一分钟再试
                        if (permanent && !e.code.equals(previous)) {
                            log.error("Wechat refund rejected refund={} pay={} code={} detail={}", refund.get("refundNo"), payNo, e.code, e.detail);
                            notifyRefundTrouble(pay, refund, "rejected", e.code);
                        } else {
                            log.warn("Wechat refund deferred refund={} code={} detail={}", refund.get("refundNo"), e.code, e.detail);
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                // 整个事务已回滚，attempts、last_error 都没留下：记 ERROR，再另开一个小事务留下痕迹。
                // 业务拒绝（ServiceException）只记消息；程序异常（空指针、数据库出错…）带上堆栈，不然只剩一个类名查不出原因
                if (e instanceof ServiceException)
                    log.error("Wechat refund reconciliation failed refund={} type={} message={}", candidate.get("refundNo"), e.getClass().getSimpleName(), e.getMessage());
                else
                    log.error("Wechat refund reconciliation failed refund={} type={}", candidate.get("refundNo"), e.getClass().getSimpleName(), e);
                recordRefundFailure(candidate.get("refundNo"), e.getClass().getSimpleName());
            }
        }
        for (Map<String,Object> candidate : db.list("select p.pay_no from kg_pay_order p join kg_wx_payment w on w.pay_no=p.pay_no where p.status='pending' and w.wx_requested=1 and (w.wx_last_query_time is null or w.wx_last_query_time<date_sub(now(),interval 30 second)) order by coalesce(w.wx_last_query_time,p.create_time) limit 10", KgJdbc.p())) {
            try { tx().execute(status -> { String n = String.valueOf(candidate.get("payNo")); lockPayment(n); Map<String,Object> pay = byNo(n); if ("pending".equals(pay.get("status"))) syncWechat(pay); return null; }); }
            catch (Exception e) { log.warn("Wechat payment reconciliation deferred pay={} type={}", candidate.get("payNo"), e.getClass().getSimpleName()); }
        }
    }

    /**
     * 对账事务整个回滚后另开一个小事务：attempts+1、last_error 记异常类名，照常一分钟后再试（之前这种失败什么痕迹都不留）。
     * 失败的事务已经回滚、锁都放了，这里不会和它互相等。已经记着「要有人处理」的微信错误码（如 NOT_ENOUGH）时不覆盖：
     * 否则下一分钟同样的余额不足又算成新错误，再通知一遍门店
     */
    private void recordRefundFailure(Object refundNo, String error) {
        try {
            tx().execute(status -> db.update("update kg_pay_refund set attempts=attempts+1,"
                    + "last_error=case when last_error in ('" + String.join("','", new java.util.TreeSet<String>(PERMANENT_REFUND_ERRORS)) + "') then last_error else :e end,"
                    + "next_attempt_time=date_add(now(),interval 60 second),update_time=now() "
                    + "where refund_no=:n and " + ACTIVE_REFUND_SQL, KgJdbc.p("e", cut(error, 64), "n", refundNo)));
        } catch (Exception e) { log.error("Wechat refund failure not recorded refund={} type={}", refundNo, e.getClass().getSimpleName()); }
    }

    /**
     * 微信退款出了要有人处理的问题，告诉这笔支付所属门店的员工（员工身份的消息中心，和新订单、退款申请同一个入口）。
     * kind：rejected 微信拒绝受理（code 是错误码，系统每分钟自动重试）/ closed 微信关闭了退款 / abnormal 退款异常。
     * 2026-10-13 之前这三种情况都只记一行 WARN 日志（不进日志文件），门店、平台、客人都不知道钱没退出去。
     */
    private void notifyRefundTrouble(Map<String,Object> pay, Map<String,Object> refund, String kind, String code) {
        String[] target = refundTarget(pay);
        String head = target[0] + " 退给客人的 ¥" + yuan(number(refund.get("amount")));
        String title, text;
        if ("closed".equals(kind)) {
            title = "退款被微信关闭";
            text = head + " 被微信关闭，钱没有退出，" + target[2] + "。";
        } else if ("abnormal".equals(kind)) {
            title = "退款异常";
            text = head + " 退款异常（常见原因是客人银行卡已注销），请在微信支付商户平台的退款管理里处理异常退款。";
        } else {
            // 还在排队（queued）= 微信没有受理；已经交给微信的（处理中 / 异常）是查询退款结果时出错，不能说「没能提交」
            boolean submitted = !"queued".equals(String.valueOf(refund.get("status")));
            title = submitted ? "查不到微信退款结果" : "退款没能提交到微信";
            text = head + (submitted ? " 已提交微信，但查询退款结果时出错：" : " 没能提交到微信：") + refundErrorWithCode(code) + "。系统每分钟自动重试，"
                    + ("NOT_ENOUGH".equals(code) ? "请联系平台给微信支付商户号补足可用余额，补足后会自动退成功"
                            : java.util.Arrays.asList("NO_AUTH", "SIGN_ERROR", "MCH_NOT_EXISTS").contains(code) ? "请联系平台检查微信支付商户号配置，处理好后会自动恢复"
                            // 参数错、超过退款期限、客人账户注销这类，重试一般不会自己好，不能说「处理好后会自动退成功」
                            : "这类问题重试一般不会自己好，请联系平台处理")
                    + "；不要在微信支付商户平台手动再退，否则可能重复退款。";
        }
        support.notifyStore(KgJdbc.asLong(pay.get("storeId")), MessageCategory.ORDER, title, text,
                String.valueOf(pay.get("bizType")), KgJdbc.asLong(pay.get("bizId")), target[1]);
        // 角色切换后城市负责人管钱：这家店有授权的城市负责人也收一条（他进不了订单台，不给落地页，内容都在正文里）
        if (com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() && !StaffSupport.stayOrderNotice(String.valueOf(pay.get("bizType")), target[1])) {
            for (Long lead : support.cityLeadsOfStore(KgJdbc.asLong(pay.get("storeId")))) {
                support.notifyStaff(lead, MessageCategory.ORDER, title, text, "refund_trouble", KgJdbc.asLong(pay.get("bizId")), null);
            }
        }
    }

    /** 这笔支付对应的业务单：[0] 消息里怎么称呼 [1] 门店消息点开去的页面 [2] 钱没退出时怎么重新退 */
    private String[] refundTarget(Map<String,Object> pay) {
        String type = String.valueOf(pay.get("bizType"));
        if ("stay_order".equals(type)) {
            Map<String,Object> o = db.one("select order_no,status from kg_stay_order where order_id=:id", KgJdbc.p("id", pay.get("bizId")));
            // 全额退 / 同意退款的那笔会把订单转成「退款中」，订单台不能再退，只能由平台在后台对这笔支付重新退款
            if (o != null) return new String[] { "订单 " + o.get("orderNo"), "/pages/staff/stay-orders?orderNo=" + o.get("orderNo") + "&storeId=" + pay.get("storeId"),
                    "refunding".equals(o.get("status")) ? "订单停在「退款中」，请联系平台在后台重新退款" : "请在订单台重新发起" };
        } else if ("service_order".equals(type)) {
            Map<String,Object> o = db.one("select order_no from kg_shop_order where order_id=:id", KgJdbc.p("id", pay.get("bizId")));
            if (o != null) return new String[] { "服务订单 " + o.get("orderNo"), "/pages/staff/insurance?s=4&tab=refund&orderNo=" + o.get("orderNo"), "请联系平台在后台重新退款" };
        } else if ("activity_signup".equals(type)) {
            Map<String,Object> a = db.one("select a.activity_id,a.title from kg_activity_signup s join kg_activity a on a.activity_id=s.activity_id where s.signup_id=:id",
                    KgJdbc.p("id", pay.get("bizId")));
            if (a != null) return new String[] { "活动「" + a.get("title") + "」的报名费", "/pages/staff/activities?s=4&id=" + a.get("activityId"), "请联系平台在后台重新退款" };
        }
        return new String[] { "支付单 " + pay.get("payNo"), null, "请联系平台在后台重新退款" };
    }
    private static String yuan(long fen) { return BigDecimal.valueOf(fen, 2).stripTrailingZeros().toPlainString(); }

    private KgPayListener listener(String bizType)
    {
        if (listeners != null)
        {
            for (KgPayListener l : listeners)
            {
                if (l.bizType().equals(bizType))
                {
                    return l;
                }
            }
        }
        throw new ServiceException("未知的支付业务类型：" + bizType);
    }
}
