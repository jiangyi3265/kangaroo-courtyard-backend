package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.stay.KgBusinessBillService.amount;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.WxPayGateway.GatewayException;
import com.ruoyi.kangaroo.ext.pay.WxTransferGateway;
import com.wechat.pay.java.core.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable reservation -> WeChat transfer -> exactly-once receipt. Network calls run outside DB transactions. */
@Service
public class KgBusinessWithdrawalService {
    private static final Logger log = LoggerFactory.getLogger(KgBusinessWithdrawalService.class);
    private static final List<String> TERMINAL = Arrays.asList("SUCCESS", "FAIL", "CANCELLED");
    private static final List<String> STATES = Arrays.asList("ACCEPTED", "PROCESSING", "WAIT_USER_CONFIRM",
            "TRANSFERING", "SUCCESS", "FAIL", "CANCELING", "CANCELLED");
    private final KgJdbc db;
    private final KgBusinessBillService bills;
    private final WxTransferGateway gateway;
    private final TransactionTemplate tx;

    public KgBusinessWithdrawalService(KgJdbc db, KgBusinessBillService bills, WxTransferGateway gateway, PlatformTransactionManager manager) {
        this.db = db;
        this.bills = bills;
        this.gateway = gateway;
        tx = new TransactionTemplate(manager);
    }

    private static void require(boolean valid, String message) { if (!valid) throw new ServiceException(message, 409); }

    public Map<String, Object> detail(long id, long store, long user) {
        Map<String, Object> bill = bills.detail(id, store);
        bill.put("withdrawEnabled", gateway.enabled(store));
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) bill.get("withdrawals");
        for (Map<String, Object> record : records) record.put("owned", Long.valueOf(user).equals(KgJdbc.asLong(record.remove("appUserId"))));
        return bill;
    }

    public Map<String, Object> create(long id, long store, long user, Map<String, Object> body) {
        String key = String.valueOf(body.get("requestKey"));
        require(key.matches("[A-Za-z0-9_-]{16,64}"), "提现请求标识无效，请重新打开账单");
        long requested = amount(body.get("amountFen"));
        require(requested > 0 && requested <= Integer.MAX_VALUE, "请填写有效的提现金额");
        String number = tx.execute(status -> reserve(id, store, user, key, requested, body));
        process(number);
        return owned(number, store, user);
    }

    private String reserve(long id, long store, long user, String key, long requested, Map<String, Object> body) {
        Map<String, Object> lookup = db.must("select * from kg_business_bill where bill_id=:id and store_id=:s",
                p("id", id, "s", store), "账单不存在或无权提现");
        require("stay".equals(lookup.get("kind")), "仅房费结算款可以提现");
        Map<String, Object> source = bills.source(lookup, true);
        Map<String, Object> bill = db.must("select * from kg_business_bill where bill_id=:id for update", p("id", id), "账单不存在");
        Map<String, Object> repeated = db.one("select * from kg_business_withdrawal where app_user_id=:u and request_key=:k for update", p("u", user, "k", key));
        if (repeated != null) {
            require(amount(repeated.get("billId")) == id && amount(repeated.get("amountFen")) == requested, "同一提现请求的账单或金额发生变化，请刷新");
            return String.valueOf(repeated.get("withdrawalNo"));
        }
        gateway.requireEnabled(store);
        require(amount(body.get("version")) == amount(bill.get("version")), "账单已更新，请刷新后提现");
        require(db.one("select withdrawal_no from kg_business_withdrawal where active_bill_id=:id for update", p("id", id)) == null,
                "这笔账单已有提现正在处理，请查看提现记录");
        require("completed".equals(source.get("status")) && Boolean.TRUE.equals(source.get("eligible")), "退房完成且退款处理结束后才能提现");
        long net = amount(source.get("netFen"));
        long base = amount(bill.get("baseFen"));
        long target;
        if (bill.get("targetFen") == null) {
            // 长住优惠等折扣下单时已按比例分摊进账单（KgBusinessBillService.captureStay），走到这里的是退款、会员权益或分摊上线前的老账单
            require(net == base + amount(bill.get("feeFen")), "订单有退款、会员权益或未分摊的优惠，请平台先核对商家应收金额");
            target = base;
            db.update("update kg_business_bill set target_fen=:t,source_net_fen=:n,status='approved' where bill_id=:id", p("t", target, "n", net, "id", id));
        } else {
            require("approved".equals(bill.get("status")) && amount(bill.get("sourceNetFen")) == net, "账单金额已变化或已结清，请刷新并核对");
            target = amount(bill.get("targetFen"));
        }
        long recorded = db.count("select coalesce(sum(amount_fen),0) from kg_business_bill_log where bill_id=:id and action='receipt' for update", p("id", id));
        require(requested <= target - recorded, "提现金额超过本单可提现金额");
        // Recheck the live grant and bind the recipient to this authenticated merchant's WeChat account.
        Map<String, Object> recipient = db.must("select u.openid from kg_app_user u join kg_staff_grant g on g.app_user_id=u.user_id "
                + "where u.user_id=:u and u.status='0' and u.del_flag='0' and g.store_id=:s and g.role_code='merchant' "
                + "and g.status='0' and g.del_flag='0' for update", p("u", user, "s", store), "当前账号的商家收款授权已失效");
        String openid = String.valueOf(recipient.get("openid"));
        require(openid.matches("[A-Za-z0-9_-]{20,64}"), "请使用商家本人的微信登录后提现");
        String name = body.get("realName") == null ? "" : String.valueOf(body.get("realName")).trim();
        require(name.length() <= 64 && (requested < 200000 || !name.isEmpty()), "单笔达到2000元，请填写与微信实名一致的收款人姓名");
        String number = "KW" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        Map<String, Object> request = gateway.request(number, openid, requested, name);
        db.insert("kg_business_withdrawal", p("withdrawal_no", number, "bill_id", id, "store_id", store,
                "app_user_id", user, "request_key", key, "amount_fen", requested, "wx_mchid", gateway.merchantId(),
                "wx_appid", gateway.appId(), "wx_openid", openid, "request_json", GsonUtil.getGson().toJson(request)));
        db.update("update kg_business_bill set version=version+1 where bill_id=:id", p("id", id));
        audit(id, "withdraw", "merchant:" + user, "申请提现到本人微信零钱，提现单 " + number, requested, "");
        return number;
    }

    public Map<String, Object> refresh(String number, long store, long user) {
        owned(number, store, user);
        process(number);
        return owned(number, store, user);
    }

    private Map<String, Object> owned(String number, long store, long user) {
        Map<String, Object> row = db.must("select * from kg_business_withdrawal where withdrawal_no=:n and store_id=:s and app_user_id=:u",
                p("n", number, "s", store, "u", user), "提现记录不存在或无权操作");
        Map<String, Object> view = p("withdrawalNo", row.get("withdrawalNo"), "state", row.get("state"),
                "amountFen", row.get("amountFen"), "errorCode", row.get("errorCode"), "failReason", row.get("failReason"));
        if ("WAIT_USER_CONFIRM".equals(row.get("state")) && !String.valueOf(row.get("packageInfo")).isEmpty())
            view.put("params", p("mchId", row.get("wxMchid"), "appId", row.get("wxAppid"), "package", row.get("packageInfo")));
        return view;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void reconcile() {
        if (!gateway.canQuery()) return;
        for (Map<String, Object> row : db.list("select withdrawal_no from kg_business_withdrawal where active_bill_id is not null "
                + "and next_retry_time<=now() and (lease_until is null or lease_until<now()) order by next_retry_time limit 30", p())) {
            try { process(String.valueOf(row.get("withdrawalNo"))); }
            catch (Exception e) { log.warn("Withdrawal reconciliation deferred number={}", row.get("withdrawalNo")); }
        }
    }

    @SuppressWarnings("unchecked")
    void process(String number) {
        if (!gateway.canQuery()) return;
        // A lease prevents multiple nodes/user refreshes from flooding the channel. The immutable merchant number also deduplicates retries.
        int claimed = db.update("update kg_business_withdrawal set lease_until=date_add(now(),interval 90 second),next_retry_time=date_add(now(),interval 60 second) "
                + "where withdrawal_no=:n and active_bill_id is not null and (lease_until is null or lease_until<now())", p("n", number));
        if (claimed == 0) return;
        try {
            Map<String, Object> row = db.must("select * from kg_business_withdrawal where withdrawal_no=:n", p("n", number), "提现记录不存在");
            require(gateway.merchantId().equals(row.get("wxMchid")) && gateway.appId().equals(row.get("wxAppid")), "提现商户配置发生变化");
            Map<String, Object> remote = gateway.query(number);
            if (remote != null) apply(number, remote, true, false);
            Map<String, Object> current = db.must("select * from kg_business_withdrawal where withdrawal_no=:n", p("n", number), "提现记录不存在");
            String state = String.valueOf(current.get("state"));
            if (TERMINAL.contains(state)) return;
            // Query omits package_info. Resubmit the identical request to recover a lost confirmation package.
            if (remote == null || Arrays.asList("QUEUED", "ACCEPTED", "PROCESSING").contains(state)
                    || ("WAIT_USER_CONFIRM".equals(state) && String.valueOf(current.get("packageInfo")).isEmpty())) {
                Map<String, Object> request = GsonUtil.getGson().fromJson(String.valueOf(row.get("requestJson")), Map.class);
                // Gson's untyped map decodes numbers as Double. APIv3 requires integer fen on the wire.
                request.put("transfer_amount", amount(request.get("transfer_amount")));
                apply(number, gateway.submit(request), false, false);
            }
        } catch (GatewayException e) {
            error(number, e.code);
        } catch (Exception e) {
            error(number, "RECONCILIATION_REQUIRED");
        } finally {
            db.update("update kg_business_withdrawal set lease_until=null where withdrawal_no=:n", p("n", number));
        }
    }

    private void error(String number, String code) {
        // An HTTP/network error leaves the reservation intact until WeChat reports an explicit terminal result.
        db.update("update kg_business_withdrawal set error_code=:c where withdrawal_no=:n and active_bill_id is not null", p("n", number, "c", code));
        log.warn("Withdrawal awaiting reconciliation number={} code={}", number, code);
    }

    public void notifyWechat(String body, String serial, String timestamp, String nonce, String signature) {
        Map<String, Object> remote = gateway.notification(body, serial, timestamp, nonce, signature);
        require(TERMINAL.contains(remote.get("state")), "无效提现通知状态");
        apply(String.valueOf(remote.get("out_bill_no")), remote, true, true);
    }

    void apply(String number, Map<String, Object> remote, boolean verifyIdentity, boolean notification) {
        tx.execute(status -> {
            Map<String, Object> lookup = db.must("select bill_id from kg_business_withdrawal where withdrawal_no=:n", p("n", number), "提现记录不存在");
            long id = amount(lookup.get("billId"));
            Map<String, Object> bill = db.must("select * from kg_business_bill where bill_id=:id for update", p("id", id), "账单不存在");
            Map<String, Object> row = db.must("select * from kg_business_withdrawal where withdrawal_no=:n for update", p("n", number), "提现记录不存在");
            String state = String.valueOf(remote.get("state"));
            String wxNumber = String.valueOf(remote.get("transfer_bill_no"));
            require(number.equals(remote.get("out_bill_no")) && STATES.contains(state) && wxNumber.matches("[A-Za-z0-9]{1,64}"), "微信提现结果不匹配");
            require(String.valueOf(row.get("wxBillNo")).isEmpty() || wxNumber.equals(row.get("wxBillNo")), "微信转账单号不匹配");
            if (verifyIdentity) {
                require(row.get("wxMchid").equals(remote.get("mch_id")) && amount(row.get("amountFen")) == amount(remote.get("transfer_amount")), "微信提现金额或商户不匹配");
                if (!notification) require(row.get("wxAppid").equals(remote.get("appid")), "微信提现应用不匹配");
                if (notification || remote.containsKey("openid")) require(row.get("wxOpenid").equals(remote.get("openid")), "微信提现收款人不匹配");
            }
            if (TERMINAL.contains(row.get("state"))) {
                require(!TERMINAL.contains(state) || state.equals(row.get("state")), "微信提现终态冲突，请人工核查");
                return null;
            }
            String pkg = remote.get("package_info") == null ? String.valueOf(row.get("packageInfo")) : String.valueOf(remote.get("package_info"));
            require(pkg.length() <= 2048, "微信收款参数超长");
            String failure = remote.get("fail_reason") == null ? "" : String.valueOf(remote.get("fail_reason"));
            db.update("update kg_business_withdrawal set state=:state,wx_bill_no=:wx,package_info=:pkg,error_code='',fail_reason=:failure where withdrawal_no=:n",
                    p("state", state, "wx", wxNumber, "pkg", TERMINAL.contains(state) ? "" : pkg,
                            "failure", failure.substring(0, Math.min(256, failure.length())), "n", number));
            if ("SUCCESS".equals(state)) {
                audit(id, "receipt", "wechat", "微信零钱提现到账，提现单 " + number, amount(row.get("amountFen")), wxNumber);
                long recorded = db.count("select coalesce(sum(amount_fen),0) from kg_business_bill_log where bill_id=:id and action='receipt' for update", p("id", id));
                db.update("update kg_business_bill set status=:status,version=version+1 where bill_id=:id",
                        p("id", id, "status", recorded == amount(bill.get("targetFen")) ? "settled" : "approved"));
            } else if (TERMINAL.contains(state)) {
                audit(id, "withdraw_failed", "wechat", "微信提现未完成，已解除本次金额占用，提现单 " + number, 0, wxNumber);
                db.update("update kg_business_bill set version=version+1 where bill_id=:id", p("id", id));
            }
            return null;
        });
    }

    private void audit(long id, String action, String actor, String note, long amount, String proof) {
        db.insert("kg_business_bill_log", p("bill_id", id, "action", action, "actor", actor, "note", note, "amount_fen", amount, "proof", proof));
    }
}
