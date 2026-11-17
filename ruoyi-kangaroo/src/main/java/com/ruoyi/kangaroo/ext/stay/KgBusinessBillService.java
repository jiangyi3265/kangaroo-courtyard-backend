package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.util.MaskUtils;

/** Room receipts and recruitment fees share an auditable reconciliation ledger. */
@Service
public class KgBusinessBillService {
    @Autowired private KgJdbc db;
    /** 账单变动通知合作商家。required = false：只用 mock 拼起来的单元测试里没有它 */
    @Autowired(required = false) private StaffSupport support;

    /** 从没付过款就关掉的入住单（用户取消、超时关单；迟到到账的会转退款审核，不是 closed）：它的账单不进列表、不算待结算 */
    static final String UNPAID_CLOSED = " and not (b.kind='stay' and exists (select 1 from kg_stay_order uo where uo.order_id=b.source_id "
            + "and uo.status='closed' and uo.paid_time is null))";

    /** 这家店在职的合作商家（账单只有商家身份能看，不能用 notifyStore 群发给全店员工） */
    static List<Long> merchantUserIds(KgJdbc db, Object storeId) {
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> r : db.list("select distinct g.app_user_id from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.store_id = :s and g.role_code = :r and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0'",
                p("s", storeId, "r", StaffRole.MERCHANT))) {
            Long id = KgJdbc.asLong(r.get("appUserId"));
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    public static long amount(Object value) {
        try {
            long number = new BigDecimal(String.valueOf(value)).longValueExact();
            require(number >= 0 && number <= 100000000000L, "金额须为非负整数分，且不超过十亿元");
            return number;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ServiceException("金额无效，请精确到分");
        }
    }

    private static long number(Object value) { return value == null ? 0 : amount(value); }
    private static void require(boolean ok, String message) { if (!ok) throw new ServiceException(message, 409); }
    private static String text(Map<String, Object> body, String key, int limit) {
        String value = String.valueOf(body.getOrDefault(key, "")).trim();
        String label = "basis".equals(key) ? "工资确认依据" : "note".equals(key) ? "核对或收付款说明" : "付款凭证";
        require(!value.isEmpty() && !"null".equals(value) && value.length() <= limit, "请填写" + label + "，最多" + limit + "字");
        return value;
    }

    /**
     * Called inside the order creation transaction, so an unsuccessful booking leaves no bill.
     * 2026-11-09 主人拍板：长住优惠等让客人实付低于「结算价 + 服务费」的折扣，建账单时就按比例分摊（见 shareDiscount），
     * 账单上的结算价、服务费就是分摊后的金额，没退款时商家可直接提现、不用平台先核对；分摊前的金额记在 basis。
     * 只影响新下的单，已建的账单不重算。
     */
    public void captureStay(long orderId, Map<String, Object> quote) {
        Map<?, ?> stay = (Map<?, ?>) quote.get("stay");
        Map<String, Object> row = p("store_id", stay.get("storeId"), "kind", "stay", "source_id", orderId,
                "source_key", "stay:" + orderId, "title", stay.get("stayName") + " · " + stay.get("typeName"),
                "base_fen", quote.get("merchantBaseFen"), "rate_bp", 500, "fee_fen", quote.get("platformMarkupFen"),
                "bearer", "guest", "settle_stage", "completed");
        boolean directFree = KgSettlementMath.DIRECT_NO_FEE_POLICY.equals(quote.get("settlementPolicy"));
        if (directFree || KgSettlementMath.MERCHANT_FEE_POLICY.equals(quote.get("settlementPolicy"))) {
            long paid = amount(quote.get("payFen"));
            long extras = Math.addExact(number(quote.get("addonFen")), number(quote.get("serviceFeeFen")));
            require(extras == 0, "房费结算请与加购服务分开记账");
            long merchant = directFree ? paid : KgSettlementMath.merchantStayNet(paid);
            row.put("base_fen", merchant);
            row.put("fee_fen", paid - merchant);
            row.put("bearer", "merchant");
            row.put("rate_bp", directFree ? 0 : 500);
            row.put("basis", directFree ? "自营房源按后台标价销售，不加价、不收平台服务费；优惠后实付房费 ¥" + yuan(paid)
                    + " 全额计入门店应收，退款后按剩余实收重新核对。"
                    : "房费按后台标价销售；按优惠后实付房费 ¥" + yuan(paid)
                    + " 内扣5%平台服务费 ¥" + yuan(paid - merchant) + "，商家应收 ¥" + yuan(merchant)
                    + "；金额四舍五入到分，退款后按剩余实收重新核对。");
            db.insert("kg_business_bill", row);
            return;
        }
        // Quotes without the new marker retain their original agreement and historical snapshots.
        long[] shared = shareDiscount(quote);
        if (shared != null) {
            long base = number(quote.get("merchantBaseFen")), fee = number(quote.get("platformMarkupFen")), paid = number(quote.get("payFen"));
            row.put("base_fen", shared[0]);
            row.put("fee_fen", shared[1]);
            Object label = quote.get("discountLabel");
            row.put("basis", (label == null ? "折扣" : label) + " ¥" + yuan(number(quote.get("discountFen"))) + " 已按比例分摊：原结算价 ¥" + yuan(base)
                    + " + 平台服务费 ¥" + yuan(fee) + "，客人实付 ¥" + yuan(paid) + "，分摊后结算价 ¥" + yuan(shared[0]) + " + 服务费 ¥" + yuan(shared[1]));
        }
        db.insert("kg_business_bill", row);
    }

    /**
     * 要按比例分摊时返回 {结算价', 服务费'}，否则 null。只分摊让实付低于「结算价 + 服务费」的折扣（长住优惠）：
     * 早鸟特价按结算价定价、实付本来就等于结算价 + 5%，不会走到这里；会员底价、免费间夜（memberMode）没有约定谁承担，照旧由平台核对。
     */
    static long[] shareDiscount(Map<String, Object> quote) {
        if (quote.get("payFen") == null || number(quote.get("discountFen")) <= 0) return null;
        Object mode = quote.get("memberMode");
        if (mode != null && !String.valueOf(mode).trim().isEmpty()) return null;
        // 实付只算房费：有加购或服务费时不按这条规则分
        if (number(quote.get("addonFen")) != 0 || number(quote.get("serviceFeeFen")) != 0) return null;
        long base = number(quote.get("merchantBaseFen")), fee = number(quote.get("platformMarkupFen")), paid = number(quote.get("payFen"));
        if (paid >= base + fee) return null;
        long sharedBase = KgSettlementMath.shareBase(base, fee, paid);
        return new long[] { sharedBase, paid - sharedBase };
    }

    public Map<String, Object> list(Long store, String kind, int page) {
        String where = " where 1=1" + UNPAID_CLOSED;
        if (store != null) where += " and b.store_id=:store";
        if (Arrays.asList("stay", "parttime", "fulltime").contains(kind)) where += " and b.kind=:kind";
        Map<String, Object> result = db.page("select b.*,s.store_name from kg_business_bill b join kg_store s on s.store_id=b.store_id"
                + where + " order by b.bill_id desc", p("store", store, "kind", kind), page, 20);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        for (Map<String, Object> row : rows) decorate(row);
        return result;
    }

    public Map<String, Object> detail(Long id, Long store) {
        Map<String, Object> bill = db.must("select b.*,s.store_name from kg_business_bill b join kg_store s on s.store_id=b.store_id where b.bill_id=:id"
                + (store == null ? "" : " and b.store_id=:store"), p("id", id, "store", store), "账单不存在或无权查看");
        decorate(bill);
        bill.put("logs", db.list("select action,actor,note,amount_fen,proof,create_time from kg_business_bill_log where bill_id=:id order by log_id", p("id", id)));
        bill.put("withdrawals", db.list("select withdrawal_no,app_user_id,amount_fen,state,wx_bill_no,error_code,fail_reason,create_time,update_time "
                + "from kg_business_withdrawal where bill_id=:id order by create_time desc", p("id", id)));
        return bill;
    }

    /** Net cash is read from the payment ledger, including every successful partial refund. */
    Map<String, Object> source(Map<String, Object> bill, boolean lock) {
        if (!"stay".equals(bill.get("kind"))) return p("netFen", bill.get("feeFen"), "eligible", true, "status", "recorded");
        String orderSql = "select order_id,order_no,status,pay_no,pay_fen,check_in,check_out,guest_name,discount_fen,discount_label,member_mode from kg_stay_order where order_id=:id";
        Map<String, Object> order = db.must(orderSql, p("id", bill.get("sourceId")), "关联订单不存在");
        Map<String, Object> payment = db.one("select pay_id,amount,refund_amount,status from kg_pay_order where pay_no=:n"
                + (lock ? " for update" : ""), p("n", order.get("payNo")));
        if (lock) order = db.must(orderSql + " for update", p("id", bill.get("sourceId")), "关联订单不存在");
        boolean paid = payment != null && Arrays.asList("paid", "part_refunded", "refunded").contains(payment.get("status"));
        long net = paid ? number(payment.get("amount")) - number(payment.get("refundAmount")) : 0;
        String activeRefund = " from kg_pay_refund where pay_no=:n and status in ('queued','processing','abnormal')";
        boolean refundPending = lock
                ? db.one("select refund_no" + activeRefund + " limit 1 for update", p("n", order.get("payNo"))) != null
                : db.count("select count(*)" + activeRefund, p("n", order.get("payNo"))) > 0;
        boolean eligible = paid && "completed".equals(order.get("status")) && !refundPending;
        // Cancellation after payout must support return-of-funds reconciliation.
        if (paid && "cancelled".equals(order.get("status")) && !refundPending) eligible = true;
        // 未到店（no_show）是终态、没有转 completed 的路径：退款处理完后也要能由平台核对结清（商家自助提现仍只限 completed，见 decorate）
        if (paid && "no_show".equals(order.get("status")) && !refundPending) eligible = true;
        Map<String, Object> out = p("netFen", net, "eligible", eligible, "status", order.get("status"), "orderNo", order.get("orderNo"), "refundPending", refundPending);
        // 账单上认得出是哪张单：入住、离店日期和打码的客人称呼（商家在订单台本来就看得到，手机号不给）
        out.put("checkIn", day(order.get("checkIn")));
        out.put("checkOut", day(order.get("checkOut")));
        out.put("guestName", MaskUtils.name(order.get("guestName") == null ? null : String.valueOf(order.get("guestName"))));
        // 订单上的优惠：账单页说明是否已按比例分摊（会员权益、老账单没分摊的要平台核对）
        out.put("payFen", number(order.get("payFen")));
        out.put("discountFen", number(order.get("discountFen")));
        out.put("discountLabel", order.get("discountLabel"));
        out.put("memberMode", order.get("memberMode") == null ? "" : String.valueOf(order.get("memberMode")).trim());
        return out;
    }

    private static String day(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    private static boolean merchantStayFee(Map<String, Object> bill) {
        return "stay".equals(bill.get("kind")) && "merchant".equals(bill.get("bearer")) && number(bill.get("rateBp")) == 500;
    }

    /** Use the saved fee rate, never the store's current type, so old orders retain their contract. */
    private static boolean directStayFree(Map<String, Object> bill) {
        return "stay".equals(bill.get("kind")) && "merchant".equals(bill.get("bearer"))
                && bill.get("rateBp") != null && number(bill.get("rateBp")) == 0;
    }

    private void decorate(Map<String, Object> bill) {
        Map<String, Object> source = source(bill, false);
        long recorded = db.count("select coalesce(sum(amount_fen),0) from kg_business_bill_log where bill_id=:id and action='receipt'", p("id", bill.get("billId")));
        long net = number(source.get("netFen"));
        bill.put("netFen", net);
        bill.put("sourceStatus", source.get("status"));
        bill.put("orderNo", source.get("orderNo"));
        bill.put("checkIn", source.get("checkIn"));
        bill.put("checkOut", source.get("checkOut"));
        bill.put("guestName", source.get("guestName"));
        bill.put("eligible", source.get("eligible"));
        bill.put("recordedFen", recorded);
        bill.put("workerNetFen", number(bill.get("baseFen")) - number(bill.get("feeFen")));
        boolean changed = bill.get("sourceNetFen") != null && number(bill.get("sourceNetFen")) != net;
        bill.put("needsReview", changed);
        bill.put("remainingFen", bill.get("targetFen") == null ? null : number(bill.get("targetFen")) - recorded);
        bill.put("platformNetFen", "stay".equals(bill.get("kind")) && bill.get("targetFen") != null ? net - number(bill.get("targetFen")) : bill.get("feeFen"));
        long held = db.count("select coalesce(sum(amount_fen),0) from kg_business_withdrawal where active_bill_id=:id", p("id", bill.get("billId")));
        bill.put("withdrawingFen", held);
        long target = bill.get("targetFen") == null ? number(bill.get("baseFen")) : number(bill.get("targetFen"));
        boolean normal = net == number(bill.get("baseFen")) + number(bill.get("feeFen"));
        boolean confirmed = bill.get("targetFen") == null ? normal : "approved".equals(bill.get("status")) && !changed;
        boolean available = "stay".equals(bill.get("kind")) && "completed".equals(source.get("status"))
                && Boolean.TRUE.equals(source.get("eligible")) && confirmed && held == 0;
        bill.put("withdrawableFen", available ? Math.max(0, target - recorded) : 0L);
        boolean merchantFee = merchantStayFee(bill);
        boolean directFree = directStayFree(bill);
        bill.put("merchantFeePolicy", merchantFee);
        bill.put("directFeeFree", directFree);
        if (merchantFee || directFree) {
            long expected = directFree ? net : KgSettlementMath.merchantStayNet(net);
            bill.put("suggestTargetFen", expected);
            bill.put("currentServiceFeeFen", net - expected);
        }
        long discount = number(source.get("discountFen"));
        if ("stay".equals(bill.get("kind")) && discount > 0) {
            long base = number(bill.get("baseFen")), fee = number(bill.get("feeFen")), paid = number(source.get("payFen"));
            boolean member = !String.valueOf(source.get("memberMode")).isEmpty();
            // 新账单下单时已分摊：结算价 + 服务费 = 实付
            boolean shared = merchantFee || directFree || (!member && base + fee == paid);
            bill.put("discountFen", discount);
            bill.put("discountLabel", source.get("discountLabel"));
            bill.put("discountShared", shared);
            bill.put("discountMember", member);
            // 分摊规则上线前的老账单（结算价 + 服务费还是原价）：后台核对时按同一公式给个建议金额，不改账单
            if (!shared && !member && paid < base + fee) bill.put("suggestTargetFen", KgSettlementMath.shareBase(base, fee, paid));
        }
    }

    public List<Map<String, Object>> candidates(Long store) {
        return db.list("select a.app_id,a.store_id,a.real_name,a.onboard_time,j.title,j.job_type,j.salary_type,j.salary_min,s.store_name "
                + "from kg_application a join kg_job j on j.job_id=a.job_id join kg_store s on s.store_id=a.store_id "
                + "where a.del_flag='0' and a.status='onboarded' and j.job_type in ('parttime','fulltime')"
                + (store == null ? "" : " and a.store_id=:s") + " order by a.app_id desc limit 500", p("s", store));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long recordWork(Map<String, Object> body, Long store, String actor) {
        long appId = amount(body.get("appId"));
        Map<String, Object> app = db.must("select a.*,j.job_type,j.title from kg_application a join kg_job j on j.job_id=a.job_id "
                + "where a.app_id=:id and a.del_flag='0'" + (store == null ? "" : " and a.store_id=:s") + " for update",
                p("id", appId, "s", store), "录用记录不存在或无权操作");
        require("onboarded".equals(app.get("status")), "请先完成入职确认");
        String kind = String.valueOf(app.get("jobType"));
        require(Arrays.asList("parttime", "fulltime").contains(kind), "仅兼职和全职计收招聘服务费");
        String workDate = null;
        String key = "fulltime:" + appId;
        if ("parttime".equals(kind)) {
            try {
                LocalDate day = LocalDate.parse(String.valueOf(body.get("workDate")));
                LocalDate onboard = LocalDate.parse(String.valueOf(app.get("onboardTime")).substring(0, 10));
                require(!day.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))) && !day.isBefore(onboard), "工作日期须在入职日至今天之间");
                workDate = day.toString();
                key = "parttime:" + appId + ":" + workDate;
            } catch (java.time.DateTimeException | IndexOutOfBoundsException e) { throw new ServiceException("请填写有效的工作日期"); }
        }
        long base = amount(body.get("baseFen"));
        require(base > 0 && base <= 10000000, "工资或底薪须大于0且不超过10万元");
        String basis = text(body, "basis", 1000);
        Map<String, Object> existing = db.one("select bill_id,base_fen,basis from kg_business_bill where source_key=:k for update", p("k", key));
        if (existing != null) {
            require(number(existing.get("baseFen")) == base && basis.equals(existing.get("basis")), "这笔费用已登记，请打开原账单核对，避免重复收费");
            return KgJdbc.asLong(existing.get("billId"));
        }
        String title = app.get("realName") + " · " + app.get("title");
        long id = db.insert("kg_business_bill", p("store_id", app.get("storeId"), "kind", kind, "source_id", appId,
                "source_key", key, "title", title.substring(0, Math.min(160, title.length())), "work_date", workDate,
                "base_fen", base, "rate_bp", 1000, "fee_fen", KgSettlementMath.fee(base, 1000),
                "bearer", "worker", "settle_stage", "confirmed_work", "basis", basis));
        log(id, "work", actor, basis, 0, "");
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public void command(Long id, String action, Map<String, Object> body, String actor) {
        // Serialize with payment callbacks before acquiring the bill lock.
        Map<String, Object> lookup = db.must("select * from kg_business_bill where bill_id=:id", p("id", id), "账单不存在");
        Map<String, Object> source = source(lookup, true);
        Map<String, Object> bill = db.must("select * from kg_business_bill where bill_id=:id for update", p("id", id), "账单不存在");
        require(amount(body.get("version")) == number(bill.get("version")), "账单已更新，请刷新后再操作");
        require(db.one("select withdrawal_no from kg_business_withdrawal where active_bill_id=:id for update", p("id", id)) == null,
                "这笔账单正在微信提现，请等转账结果确定后再核对或登记收付款");
        String note = text(body, "note", 1000);
        long net = number(source.get("netFen"));
        if ("approve".equals(action)) {
            require(Boolean.TRUE.equals(source.get("eligible")), "退房完成且退款处理结束后才能结算房费");
            long base = number(bill.get("baseFen"));
            long fee = number(bill.get("feeFen"));
            boolean stay = "stay".equals(bill.get("kind"));
            if (!stay && body.containsKey("baseFen")) {
                base = amount(body.get("baseFen"));
                require(base > 0 && base <= 10000000, "工资或底薪须大于0且不超过10万元");
                fee = KgSettlementMath.fee(base, 1000);
                net = fee;
                note += "，原工资 " + bill.get("baseFen") + " 分，确认工资 " + base + " 分";
            }
            long target = stay ? amount(body.get("targetFen")) : fee;
            require(target <= net, "商家结算金额不能超过实际剩余收款");
            if (directStayFree(bill)) {
                require(target == net, "自营房源免平台服务费，门店应收应为实际剩余房费，请刷新后核对");
                base = net;
                fee = 0;
                note += "，自营免平台服务费，剩余实收全额结算";
            } else if (merchantStayFee(bill)) {
                long expected = KgSettlementMath.merchantStayNet(net);
                require(target == expected, "商家应收应为实际剩余房费扣除5%服务费，请刷新后核对");
                base = expected;
                fee = net - expected;
                note += "，按剩余实收内扣5%服务费 " + fee + " 分";
            }
            if (stay && net == base + fee) require(target == base, "没有退款的订单按账单上的商家结算价结算（长住优惠等折扣已按比例分摊），平台保留服务费");
            long recorded = db.count("select coalesce(sum(amount_fen),0) from kg_business_bill_log where bill_id=:id and action='receipt' for update", p("id", id));
            db.update("update kg_business_bill set base_fen=:base,fee_fen=:fee,target_fen=:target,source_net_fen=:net,status=:status,version=version+1 where bill_id=:id",
                    p("base", base, "fee", fee, "target", target, "net", net, "status", target == recorded ? "settled" : "approved", "id", id));
            log(id, action, actor, note + "，核对金额 " + target + " 分，实际净收款 " + net + " 分", 0, "");
            long left = target - recorded;
            String what = stay ? "应付你 ¥" + yuan(target) : "服务费 ¥" + yuan(target);
            String next = left == 0 ? "，已结清。" : (stay ? (left > 0 ? "，平台待付 ¥" + yuan(left) + "。" : "，订单退款后多付了，需要你退回 ¥" + yuan(-left) + "，请按平台说明转回。")
                    : (left > 0 ? "，请把 ¥" + yuan(left) + " 服务费交给平台。" : "，平台将退还你 ¥" + yuan(-left) + "。"));
            notifyMerchants(bill, source, stay && left < 0 ? "账单需要退回款项" : "平台已核对账单", "平台已核对：" + what + next);
            return;
        }
        require("receipt".equals(action), "不支持的账单操作");
        require("approved".equals(bill.get("status")), "请先复核账单");
        require(Boolean.TRUE.equals(source.get("eligible")) && number(bill.get("sourceNetFen")) == net, "订单或退款金额有变化，请重新复核后登记");
        long recorded = db.count("select coalesce(sum(amount_fen),0) from kg_business_bill_log where bill_id=:id and action='receipt' for update", p("id", id));
        long remaining = number(bill.get("targetFen")) - recorded;
        require(remaining != 0, "账单已结清，请勿重复登记");
        long actual = amount(body.get("amountFen"));
        require(actual > 0 && actual <= Math.abs(remaining), "登记金额不能超过本次待收付金额");
        String proof = text(body, "proof", 500);
        long signed = remaining < 0 ? -actual : actual;
        log(id, action, actor, note, signed, proof);
        db.update("update kg_business_bill set status=:status,version=version+1 where bill_id=:id",
                p("status", actual == Math.abs(remaining) ? "settled" : "approved", "id", id));
        boolean stay = "stay".equals(bill.get("kind"));
        String done = actual == Math.abs(remaining) ? "，这笔账单已结清。" : "，还差 ¥" + yuan(Math.abs(remaining) - actual) + "。";
        notifyMerchants(bill, source, signed > 0 ? (stay ? "平台已付款" : "平台已收到服务费") : (stay ? "平台已收到你的退回款" : "平台已退还服务费"),
                (signed > 0 ? (stay ? "平台已付款 ¥" : "平台已登记收到服务费 ¥") : (stay ? "平台已登记收到你退回的 ¥" : "平台已退还服务费 ¥")) + yuan(actual)
                        + "（凭证 " + proof + "）" + done);
    }

    /** 后台核对、登记收付款后告诉这家店的合作商家（账单页只有商家身份能看） */
    private void notifyMerchants(Map<String, Object> bill, Map<String, Object> source, String title, String text) {
        if (support == null) return;
        Object orderNo = source.get("orderNo");
        String head = bill.get("title") + (orderNo == null ? "" : "（订单 " + orderNo + "）") + "：";
        Long billId = KgJdbc.asLong(bill.get("billId"));
        for (Long uid : merchantUserIds(db, bill.get("storeId"))) {
            support.notifyStaff(uid, MessageCategory.ORDER, title, head + text, "business_bill", billId, "/pages/staff/settlements");
        }
    }

    private static String yuan(long fen) {
        return KgStayBrowseService.yuan(fen);
    }

    private void log(long id, String action, String actor, String note, long amount, String proof) {
        db.insert("kg_business_bill_log", p("bill_id", id, "action", action, "actor", actor, "note", note,
                "amount_fen", amount, "proof", proof));
    }
}
