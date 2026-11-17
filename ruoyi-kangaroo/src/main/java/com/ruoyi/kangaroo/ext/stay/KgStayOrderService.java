package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.D;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.date;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.fen;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.str;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayListener;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgPointRewards;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 旅居订单：下单占房（逐晚 CAS）、支付确认、取消、退款申请与审批、核销码、超时关单与到期流转。
 *
 * 状态：pending_payment → confirmed（支付）/ closed（取消、超时）；confirmed → refund_pending（用户申请）→ confirmed（驳回）
 * / refunding（同意，释放房量，调渠道原路退）→ cancelled（退款成功）；confirmed → checked_in（核销）→ completed；
 * confirmed 过了离店日仍未核销 → no_show。退款一律人工审批，没有任何自动打款。
 */
@Service
public class KgStayOrderService implements KgPayListener
{
    @Autowired(required=false) private com.ruoyi.kangaroo.ext.user.KgMemberWallet memberWallet;
    @Autowired
    private KgStayActivitySelection activitySelections;
    private static final Logger log = LoggerFactory.getLogger(KgStayOrderService.class);
    public static final String BIZ = "stay_order";
    static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final int PAY_MINUTES = 15;

    @Autowired
    private KgJdbc db;

    @Autowired(required = false)
    private KgBusinessBillService businessBills;

    @Autowired
    private KgStayBrowseService browse;

    @Autowired
    private KgPayService pay;

    @Autowired
    private StaffSupport support;

    @Lazy
    @Autowired
    private KgStayOrderService self;

    /** 2026-10-17 消费积分（订单完成发、完成后退款扣回）。required = false：只用 mock 拼起来的单元测试里没有它 */
    @Autowired(required = false)
    private KgPointRewards pointRewards;

    public static String statusLabel(String st)
    {
        switch (st == null ? "" : st)
        {
            case "pending_payment": return "待支付";
            case "confirmed": return "待入住";
            case "checked_in": return "已入住";
            case "completed": return "已完成";
            case "no_show": return "未到店";
            case "closed": return "已关闭";
            case "refund_pending": return "退款审核中";
            case "refunding": return "退款中";
            case "cancelled": return "已取消退款";
            default: return st;
        }
    }

    /**
     * 列表 / 详情显示的状态文字：已入住、已完成、未到店的单后来又退了钱（后台对支付单直接退款、门店主动退款）时订单状态不变，
     * 只有退款金额变了，之前各端仍写「已完成 ¥1596」看不出退过款；现在后面标出「已退款 ¥x」（退满实付写「已全额退款」）
     */
    public static String statusLabel(String st, Object refundFen, Object payFen)
    {
        String label = statusLabel(st);
        long refund = fen(refundFen), paid = fen(payFen);
        if (refund > 0 && Arrays.asList("checked_in", "completed", "no_show").contains(st))
        {
            return label + " · " + (paid > 0 && refund >= paid ? "已全额退款 ¥" : "已退款 ¥") + KgStayBrowseService.yuan(refund);
        }
        return label;
    }

    /** 消息里的房源名：订单快照里的，没有时用联表查出来的 stay_name */
    static String stayName(Map<String, Object> o)
    {
        Object n = json(o.get("staySnapshot")).get("stayName");
        return str(n == null ? o.get("stayName") : n);
    }

    // ---------- 下单 ----------

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Long userId, Map<String, Object> body)
    {
        com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireStayBooking(db);
        // Share the account lock with deactivation and serialize this user's pending-order quota.
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, userId);
        Long roomTypeId = KgJdbc.asLong(body.get("roomTypeId"));
        int guests = (int) Math.max(1, fen(body.get("guests")));
        Long promoId = KgJdbc.asLong(body.get("promoId"));
        String checkIn = str(body.get("checkIn"));
        String checkOut = str(body.get("checkOut"));
        String name = str(body.get("guestName")).trim();
        String phone = str(body.get("guestPhone")).trim();
        if (name.isEmpty() || name.length() > 20)
        {
            throw new ServiceException("请填写入住人姓名（1–20 字）");
        }
        if (!phone.matches("1[3-9]\\d{9}"))
        {
            throw new ServiceException("请填写正确的手机号");
        }
        String idem = str(body.get("idemKey"));
        if (idem.length() > 48)
        {
            throw new ServiceException("idemKey 过长（最多 48 个字符）");
        }
        if (!idem.isEmpty())
        {
            Map<String, Object> dup = db.one("select order_no, status, pay_fen, expire_time, pay_no from kg_stay_order where app_user_id = :u and create_by = :k",
                    p("u", userId, "k", "idem:" + idem));
            if (dup != null)
            {
                return orderResult(dup);
            }
            // 上面的判重挡不住"同一次提交并发到达"（双击、网络重试）：两边都查不到，都会占房下单。
            // 占幂等键把它们串行化：后到的一方等先到的事务结束，再用加锁读取回先到那张单。
            if (!db.claimIdem("stay:" + userId + ":" + idem, "stay_order"))
            {
                Map<String, Object> first = db.one("select order_no, status, pay_fen, expire_time, pay_no from kg_stay_order where app_user_id = :u and create_by = :k for share",
                        p("u", userId, "k", "idem:" + idem));
                if (first != null)
                {
                    return orderResult(first);
                }
                throw new ServiceException("这笔订单正在提交，请稍后在「我的订单」查看", 409);
            }
        }
        // 待支付上限放在幂等判断之后：第三张单的重放要拿回那张单，而不是撞上"已有 3 张待支付"
        if (db.count("select count(*) from kg_stay_order where app_user_id = :u and status = 'pending_payment' and del_flag = '0'", p("u", userId)) >= 3)
        {
            throw new ServiceException("你有 3 张待支付的订单，请先支付或取消");
        }
        KgRoomInventory.lockRoom(db, roomTypeId);
        Map<String, Object> q = browse.quote(roomTypeId, checkIn, checkOut, guests, promoId);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentAgreement = (Map<String, Object>) q.get("agreement");
        StayBookingNotice.validateConsent(currentAgreement, body.get("agreementVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> currentPolicy = (Map<String, Object>) q.get("policy");
        if (currentPolicy != null && (StayCustomPolicy.CUSTOM.equals(currentPolicy.get("code")) || body.containsKey("expectedPolicyKey"))
                && !java.util.Objects.equals(currentPolicy.get("key"), body.get("expectedPolicyKey")))
        {
            throw new ServiceException("取消退款规则已更新，请重新阅读并确认；旧版小程序请更新后再预订", 409);
        }
        if (!Boolean.TRUE.equals(q.get("bookable")))
        {
            throw new ServiceException("所选日期暂不可订：" + q.get("reason"));
        }
        java.util.List<Long> memberIds = com.ruoyi.kangaroo.ext.user.KgMemberWallet.ids(body.get("memberWalletIds"));
        if (memberWallet != null && memberWallet.enabled()) memberWallet.applyStay(userId, roomTypeId, q, memberIds, Boolean.TRUE.equals(body.get("memberFloor")));
        else if (!memberIds.isEmpty() || Boolean.TRUE.equals(body.get("memberFloor"))) throw new ServiceException("会员权益暂未开放",409);
        long payFen = fen(q.get("payFen"));
        Long expected = KgJdbc.asLong(body.get("expectedPayFen"));
        if (expected != null && expected != payFen)
        {
            ServiceException e = new ServiceException("价格有变化，最新应付 ¥" + KgStayBrowseService.yuan(payFen) + "，请确认后重新提交", 409);
            throw e;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> stay = (Map<String, Object>) q.get("stay");
        LocalDate in = date(checkIn);
        LocalDate out = date(checkOut);
        int units = (int) fen(q.get("units"));
        long nights = fen(q.get("nights"));
        // 1) 保证每晚都有日历行；2) 一次占全部晚，受影响行数必须等于晚数
        for (LocalDate d = in; d.isBefore(out); d = d.plusDays(1))
        {
            db.update("insert into kg_stay_calendar (room_type_id, stay_id, stay_date, occupied, update_time) values (:r, :s, :d, 0, now()) "
                    + "on duplicate key update cal_id = cal_id", p("r", roomTypeId, "s", stay.get("stayId"), "d", d.format(D)));
        }
        int n = db.update("update kg_stay_calendar c join kg_stay_room_type t on t.room_type_id = c.room_type_id "
                + "set c.occupied = c.occupied + :u, c.update_time = now() where c.room_type_id = :r and c.stay_date >= :a and c.stay_date < :b "
                + "and c.closed = '0' and c.occupied + :u <= coalesce(c.stock, t.stock)",
                p("u", units, "r", roomTypeId, "a", in.format(D), "b", out.format(D)));
        if (n != nights)
        {
            throw new ServiceException("所选日期刚刚被订满，请换个日期或房型");
        }
        Long appliedPromo = KgJdbc.asLong(q.get("promoId"));
        if (appliedPromo != null && db.update("update kg_stay_promo set sold = sold + 1 where promo_id = :p and sold < quota and status = '0'",
                p("p", appliedPromo)) == 0)
        {
            throw new ServiceException("早鸟特价刚刚售罄，请按日常价预订");
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        String orderNo = StayCodes.orderNo("S");
        row.put("order_no", orderNo);
        row.put("stay_id", stay.get("stayId"));
        row.put("store_id", stay.get("storeId"));
        row.put("room_type_id", roomTypeId);
        row.put("promo_id", appliedPromo);
        row.put("app_user_id", userId);
        row.put("check_in", checkIn);
        row.put("check_out", checkOut);
        row.put("nights", nights);
        row.put("guests", guests);
        row.put("units", units);
        row.put("guest_name", name);
        row.put("guest_phone", phone);
        row.put("arrive_time", StaffSupport.cut(str(body.get("arriveTime")), 16));
        row.put("remark", StaffSupport.cut(str(body.get("remark")).trim(), 200));
        row.put("room_fen", q.get("roomFen"));
        row.put("addon_fen", 0);
        row.put("discount_fen", q.get("discountFen"));
        row.put("discount_label", q.get("discountLabel"));
        row.put("service_fee_fen", q.get("serviceFeeFen"));
        row.put("pay_fen", payFen);
        if (memberWallet != null && memberWallet.enabled()) { row.put("member_discount_fen", q.getOrDefault("memberDiscountFen",0)); row.put("member_mode", q.get("memberMode")); }
        row.put("nightly", JSON.toJSONString(q.get("nightly")));
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) q.get("policy");
        row.put("policy_snapshot", JSON.toJSONString(policy));
        Map<String, Object> snap = new LinkedHashMap<String, Object>(stay);
        row.put("stay_snapshot", JSON.toJSONString(snap));
        // 记实际同意的版本和当时全文；房源后来改须知不能覆盖订单存档。
        row.put("agreement_version", str(body.get("agreementVersion")));
        Map<String, Object> acceptedAgreement = new LinkedHashMap<>(currentAgreement);
        acceptedAgreement.put("version", str(body.get("agreementVersion")));
        row.put("agreement_snapshot", JSON.toJSONString(acceptedAgreement));
        row.put("status", "pending_payment");
        row.put("expire_time", LocalDateTime.now().plusMinutes(PAY_MINUTES).format(DT));
        row.put("create_by", idem.isEmpty() ? "app:" + userId : "idem:" + idem);
        row.put("create_time", new Date());
        long orderId;
        try
        {
            orderId = db.insert("kg_stay_order", row);
            if (businessBills != null) businessBills.captureStay(orderId, q);
        }
        catch (DuplicateKeyException e)
        {
            throw new ServiceException("下单太快了，请重试");
        }
        if (memberWallet != null && memberWallet.enabled()) memberWallet.reserveStay(userId,orderId,memberIds);
        if (payFen == 0 && !memberIds.isEmpty()) {
            onPaid(p("bizId",orderId));
            activitySelections.record(orderId,userId,str(stay.get("city")),checkIn,checkOut,body.get("selectedActivityIds"));
            return orderResult(db.one("select order_no,status,pay_fen,expire_time,pay_no from kg_stay_order where order_id=:id",p("id",orderId)));
        }
        Map<String, Object> po = pay.createForNewBusiness(BIZ, orderId, userId, KgJdbc.asLong(stay.get("storeId")), payFen,
                stay.get("stayName") + " · " + stay.get("typeName") + " " + nights + "晚");
        db.update("update kg_stay_order set pay_no = :n where order_id = :id", p("n", po.get("payNo"), "id", orderId));
        activitySelections.record(orderId, userId, str(stay.get("city")), checkIn, checkOut, body.get("selectedActivityIds"));
        return orderResult(db.one("select order_no, status, pay_fen, expire_time, pay_no from kg_stay_order where order_id = :id", p("id", orderId)));
    }

    private static Map<String, Object> orderResult(Map<String, Object> o)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("orderNo", o.get("orderNo"));
        m.put("status", o.get("status"));
        m.put("payFen", o.get("payFen"));
        m.put("expireTime", o.get("expireTime"));
        m.put("payNo", o.get("payNo"));
        m.put("serverTime", LocalDateTime.now().format(DT));
        return m;
    }

    // ---------- 查询 ----------

    Map<String, Object> own(Long userId, String orderNo)
    {
        Map<String, Object> o = db.one("select * from kg_stay_order where order_no = :n and del_flag = '0'", p("n", orderNo));
        if (o == null || !userId.equals(KgJdbc.asLong(o.get("appUserId"))))
        {
            throw new ServiceException("订单不存在", HttpStatus.FORBIDDEN);
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> json(Object s)
    {
        try
        {
            Object o = JSON.parse(str(s));
            return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
        }
        catch (Exception e)
        {
            return new LinkedHashMap<String, Object>();
        }
    }

    static List<Object> jsonList(Object s)
    {
        try
        {
            Object o = JSON.parse(str(s));
            return o instanceof List ? new ArrayList<Object>((List<?>) o) : new ArrayList<Object>();
        }
        catch (Exception e)
        {
            return new ArrayList<Object>();
        }
    }

    /** 我的订单（profile/orders 聚合用） */
    public Map<String, Object> mine(Long userId, String status, int pageNum, int pageSize)
    {
        StringBuilder where = new StringBuilder(" where app_user_id = :u and del_flag = '0'");
        Map<String, Object> ps = p("u", userId);
        if (status != null && !status.isEmpty())
        {
            where.append(" and status in (:st)");
            ps.put("st", Arrays.asList(status.split(",")));
        }
        Map<String, Object> page = db.page("select order_no, stay_id, check_in, check_out, nights, guests, pay_fen, refund_fen, status, stay_snapshot, create_time, expire_time "
                + "from kg_stay_order" + where + " order by order_id desc", ps, pageNum, pageSize);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        for (Map<String, Object> r : rows)
        {
            Map<String, Object> snap = json(r.remove("staySnapshot"));
            r.put("stayName", snap.get("stayName"));
            r.put("roomTypeName", snap.get("typeName"));
            r.put("cover", snap.get("cover"));
            r.put("statusLabel", statusLabel(str(r.get("status")), r.get("refundFen"), r.get("payFen")));
        }
        return page;
    }

    /** 最近一张进行中的入住单（增值服务入住条用） */
    public Map<String, Object> current(Long userId)
    {
        Map<String, Object> o = db.one("select order_no, store_id, stay_id, check_in, check_out, nights, guest_name, guest_phone, status, stay_snapshot "
                + "from kg_stay_order where app_user_id = :u and status in ('confirmed','checked_in') and check_out >= curdate() and del_flag = '0' "
                + "order by check_in limit 1", p("u", userId));
        if (o != null)
        {
            Map<String, Object> snap = json(o.remove("staySnapshot"));
            o.put("stayName", snap.get("stayName"));
            o.put("cover", snap.get("cover"));
            o.put("typeName", snap.get("typeName"));
        }
        return o;
    }

    public Map<String, Object> detail(Long userId, String orderNo)
    {
        Map<String, Object> o = own(userId, orderNo);
        return view(o, true);
    }

    /** 订单视图：mine = 本人（给完整手机号） */
    Map<String, Object> view(Map<String, Object> o, boolean mine)
    {
        String st = str(o.get("status"));
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        v.put("selectedActivities", activitySelections.list(o.get("orderId")));
        for (String k : Arrays.asList("orderId", "orderNo", "stayId", "storeId", "roomTypeId", "checkIn", "checkOut", "nights", "guests", "units", "arriveTime",
                "memberMode", "memberDiscountFen", "remark", "roomFen", "addonFen", "discountFen", "discountLabel", "serviceFeeFen", "payFen", "status", "payNo", "paidTime",
                "expireTime", "verifyTime", "checkedOutTime", "refundFen", "refundReason", "refundApplyTime", "refundNote", "refundTime", "closeTime", "closeReason", "createTime"))
        {
            v.put(k, o.get(k));
        }
        v.put("statusLabel", statusLabel(st, o.get("refundFen"), o.get("payFen")));
        // 订单页「提交后由 X 审核」「X 审核通过后原路退回」里的 X
        v.put("refundReviewer", refundReviewer());
        v.put("canCancelFree", "confirmed".equals(st)&&fen(o.get("payFen"))==0&&"nights".equals(o.get("memberMode"))&&com.ruoyi.kangaroo.ext.user.KgMembershipPolicy.today().isBefore(date(str(o.get("checkIn")))));
        v.put("guestName", o.get("guestName"));
        v.put("guestPhone", mine ? o.get("guestPhone") : MaskUtils.phone(str(o.get("guestPhone"))));
        Map<String, Object> snap = json(o.get("staySnapshot"));
        v.put("stay", snap);
        v.put("nightly", jsonList(o.get("nightly")));
        Map<String, Object> policy = json(o.get("policySnapshot"));
        v.put("policy", policy);
        v.put("agreementVersion", o.get("agreementVersion"));
        v.put("agreement", json(o.get("agreementSnapshot")));
        // 门店主动退款（旅居订单台）：客人订单页显示「门店已退 ¥xx」和每一笔的原因。
        // storeRefundFen 只算不取消订单的部分退款（取消订单的那一笔就是 refundFen，避免算两遍）；
        // 被微信关闭的（closed，钱没退出）不算进去，单独标出来
        List<Map<String, Object>> storeRefunds = new ArrayList<Map<String, Object>>();
        long storeFen = 0, storePending = 0;
        for (Map<String, Object> r : staffRefunds(o.get("orderId")))
        {
            boolean partial = "partial".equals(str(r.get("kind")));
            boolean processing = "processing".equals(str(r.get("status")));
            boolean closed = "closed".equals(str(r.get("status")));
            if (partial && !closed)
            {
                storeFen += fen(r.get("amountFen"));
                storePending += processing ? fen(r.get("amountFen")) : 0;
            }
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("amountFen", r.get("amountFen"));
            x.put("reason", r.get("reason"));
            x.put("kind", r.get("kind"));
            x.put("processing", processing);
            x.put("closed", closed);
            String t = str(r.get("createTime"));
            x.put("time", t.length() >= 16 ? t.substring(0, 16) : t);
            storeRefunds.add(x);
        }
        v.put("storeRefunds", storeRefunds);
        v.put("storeRefundFen", storeFen);
        v.put("storeRefundPendingFen", storePending);
        LocalDate in = date(str(o.get("checkIn")));
        LocalDate out = date(str(o.get("checkOut")));
        if (in != null && out != null)
        {
            v.put("checkInText", KgStayBrowseService.md(in) + "（" + KgStayBrowseService.weekday(in) + "）");
            v.put("checkOutText", KgStayBrowseService.md(out) + "（" + KgStayBrowseService.weekday(out) + "）");
        }
        boolean paid = Arrays.asList("confirmed", "checked_in", "completed", "no_show", "refund_pending", "refunding", "cancelled").contains(st);
        if (paid)
        {
            Map<String, Object> s = db.one("select s.group_name, s.group_qr, s.group_qr_expire, s.checkin_time, s.checkout_time, s.address, s.latitude, "
                    + "s.longitude, st.contact_phone from kg_stay s left join kg_store st on st.store_id = s.store_id where s.stay_id = :s",
                    p("s", o.get("stayId")));
            if (s != null)
            {
                Map<String, Object> g = new LinkedHashMap<String, Object>();
                String qr = str(s.get("groupQr"));
                LocalDate exp = date(str(s.get("groupQrExpire")));
                boolean open = !qr.isEmpty() && (exp == null || !exp.isBefore(LocalDate.now()));
                g.put("name", str(s.get("groupName")).isEmpty() ? snap.get("stayName") + " 住客群" : s.get("groupName"));
                g.put("qrUrl", open ? qr : null);
                g.put("expire", s.get("groupQrExpire"));
                g.put("open", open);
                if(com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.GROUP_KEY)) v.put("group", g);
                v.put("storePhone", s.get("contactPhone"));
                v.put("checkinTime", s.get("checkinTime"));
                v.put("checkoutTime", s.get("checkoutTime"));
                v.put("address", s.get("address"));
                v.put("latitude", s.get("latitude"));
                v.put("longitude", s.get("longitude"));
            }
        }
        if (("confirmed".equals(st) || "checked_in".equals(st)) && o.get("verifyCode") != null)
        {
            String code = str(o.get("verifyCode"));
            Map<String, Object> vc = new LinkedHashMap<String, Object>();
            vc.put("code", "checked_in".equals(st) ? null : code);
            if (!"checked_in".equals(st))
            {
                vc.put("qrDataUri", StayCodes.qrDataUri(code));
                vc.put("barDataUri", StayCodes.barDataUri(code));
            }
            vc.put("validFrom", o.get("checkIn") + " 00:00");
            vc.put("validTo", o.get("checkOut") + " " + str(v.get("checkoutTime")));
            vc.put("used", "checked_in".equals(st));
            vc.put("usedTime", o.get("verifyTime"));
            v.put("verify", vc);
        }
        v.put("serverTime", LocalDateTime.now().format(DT));
        return v;
    }

    // ---------- 取消（仅待支付） ----------

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, String orderNo)
    {
        Map<String, Object> o = own(userId, orderNo);
        if ("confirmed".equals(str(o.get("status"))) && fen(o.get("payFen"))==0 && "nights".equals(o.get("memberMode"))) {
            if (!LocalDate.now(com.ruoyi.kangaroo.ext.user.KgMembershipPolicy.ZONE).isBefore(date(str(o.get("checkIn"))))) throw new ServiceException("入住当天起请联系门店处理取消",409);
            if (db.update("update kg_stay_order set status='cancelled',close_time=now(),close_reason='免费间夜订单取消',verify_code=null,update_time=now() where order_id=:id and status='confirmed'", p("id", o.get("orderId"))) == 1)
            {
                release(o);
                // 免费间夜同样可以购买增值服务。入住取消后关闭未付款服务，已付款未使用的转人工退款，不能留下无法核销的订单。
                refundLinkedServices(o, "免费间夜入住订单已取消");
            }
            return;
        }
        if (!"pending_payment".equals(str(o.get("status"))))
        {
            throw new ServiceException("只能取消待支付的订单；已支付的订单请申请退款");
        }
        if (!pay.close(str(o.get("payNo"))))
        {
            throw new ServiceException("订单已支付，请刷新后查看");
        }
        closeOrder(o, "用户取消");
    }

    /** 本地关单 + 释放房量与早鸟名额（CAS：只有待支付单会被关）；返回这次是否真的关了 */
    boolean closeOrder(Map<String, Object> o, String reason)
    {
        int n = db.update("update kg_stay_order set status = 'closed', close_time = now(), close_reason = :r, update_time = now() "
                + "where order_id = :id and status = 'pending_payment'", p("id", o.get("orderId"), "r", reason));
        if (n > 0)
        {
            release(o);
        }
        return n > 0;
    }

    void release(Map<String, Object> o)
    {
        if(memberWallet != null)memberWallet.releaseStay(KgJdbc.asLong(o.get("orderId")));
        db.update("update kg_stay_calendar set occupied = greatest(0, occupied - :u), update_time = now() where room_type_id = :r "
                + "and stay_date >= :a and stay_date < :b", p("u", fen(o.get("units")), "r", o.get("roomTypeId"), "a", o.get("checkIn"), "b", o.get("checkOut")));
        if (o.get("promoId") != null)
        {
            db.update("update kg_stay_promo set sold = greatest(0, sold - 1) where promo_id = :p", p("p", o.get("promoId")));
        }
    }

    // ---------- 支付回调（KgPayListener） ----------

    @Override
    public String bizType()
    {
        return BIZ;
    }

    @Override
    public void onPaid(Map<String, Object> payRow)
    {
        Map<String, Object> o = db.one("select * from kg_stay_order where order_id = :id", p("id", payRow.get("bizId")));
        if (o == null)
        {
            throw new ServiceException("订单不存在");
        }
        String code = null;
        for (int i = 0; i < 5 && code == null; i++)
        {
            String c = StayCodes.verifyCode();
            if (db.count("select count(*) from kg_stay_order where verify_code = :c", p("c", c)) == 0
                    && db.count("select count(*) from kg_shop_order where verify_code = :c", p("c", c)) == 0)
            {
                code = c;
            }
        }
        int n = db.update("update kg_stay_order set status = 'confirmed', paid_time = now(), verify_code = :c, update_time = now() "
                + "where order_id = :id and status = 'pending_payment'", p("id", o.get("orderId"), "c", code));
        if (n == 0)
        {
            // 迟到的支付：订单已被关闭，钱进来了 —— 不自动退，转人工：记成退款审核中，由门店/后台同意后原路退回
            db.update("update kg_stay_order set status = 'refund_pending', paid_time = now(), refund_fen = pay_fen, "
                    + "refund_reason = '订单关闭后才到账（迟到支付），待人工全额退款', refund_apply_time = now(), update_time = now() "
                    + "where order_id = :id and status = 'closed'", p("id", o.get("orderId")));
            log.warn("迟到支付 order={} pay={}", o.get("orderNo"), payRow.get("payNo"));
            return;
        }
        Map<String, Object> snap = json(o.get("staySnapshot"));
        String no = str(o.get("orderNo"));
        support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "预订成功",
                snap.get("stayName") + " · " + snap.get("typeName") + "，" + o.get("checkIn") + " 入住，共 " + o.get("nights") + " 晚。到店出示核销码办理入住。",
                "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/stays/order?orderNo=" + no + "&view=trip");
        support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "新的旅居订单",
                MaskUtils.name(str(o.get("guestName"))) + " 预订了 " + snap.get("typeName") + "，" + o.get("checkIn") + " 入住 " + o.get("nights") + " 晚。",
                "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/stay-orders?orderNo=" + no);
    }

    @Override
    public void onRefunded(Map<String, Object> payRow, long amount)
    {
        // 加锁读：退款回调可能与核销、用户取消、退款申请并发，普通读会拿到过期快照，把在店客人的订单覆盖成已取消（2026-09-28 复核发现）
        Map<String, Object> o = db.one("select * from kg_stay_order where order_id = :id for update", p("id", payRow.get("bizId")));
        if (o == null)
        {
            return;
        }
        String st = str(o.get("status"));
        long refundedTotal = Math.min(fen(o.get("payFen")), fen(payRow.get("refundAmount")));
        // 门店主动退款（旅居订单台）：同一支付单同一时间只会有一笔退款在途（微信渠道有未完成的退款时不让再提交），
        // 到账的就是最早那笔同金额、还在等确认的门店退款，记成已到账。
        // 表还没建（新版后端先于 V39 上线）时只记日志：不能让退款回调因此失败、反复重试
        try
        {
            db.update("update kg_stay_staff_refund set status = 'success', success_time = now() where order_id = :id and status = 'processing' "
                    + "and amount_fen = :a order by refund_id limit 1", p("id", o.get("orderId"), "a", amount));
        }
        catch (BadSqlGrammarException e)
        {
            log.warn("门店退款记录表缺失，跳过到账标记 order={}", o.get("orderNo"));
        }
        if ("refunding".equals(st))
        {
            // 走审批流程的退款：refund_fen 在申请时已按政策算好，这里只落终态
            db.update("update kg_stay_order set status = 'cancelled', refund_time = now(), verify_code = null, update_time = now() "
                    + "where order_id = :id and status = 'refunding'", p("id", o.get("orderId")));
        }
        else
        {
            // 后台直接对支付单退款（补偿 / 线下协商）。refund_fen 取支付单的累计退款额（绝对值，不能在订单上累加：
            // 审批中的订单再被后台退款会把 refund_fen 加到超过实付，审批永远失败）；
            // 待入住 / 退款审核中的单被全额退掉时一并取消、清核销码、释放房量与关联服务（只释放一次）
            boolean full = "refunded".equals(str(payRow.get("status")));
            boolean open = "confirmed".equals(st) || "refund_pending".equals(st);
            if (full && open)
            {
                if (db.update("update kg_stay_order set status = 'cancelled', refund_fen = :t, refund_time = now(), verify_code = null, update_time = now() "
                        + "where order_id = :id and status in ('confirmed','refund_pending')", p("id", o.get("orderId"), "t", refundedTotal)) > 0)
                {
                    // A closed order paid late already released its inventory at close time.
                    if (o.get("closeTime") == null) release(o);
                    int linked = refundLinkedServices(o, "入住订单已全额退款");
                    // 平台在后台对支付单直接全额退款、订单因此取消：之前门店完全不知道，订单台上客人还像要来
                    support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "平台已全额退款，订单已取消",
                            "订单 " + o.get("orderNo") + "（" + MaskUtils.name(str(o.get("guestName"))) + "，" + o.get("checkIn") + " 入住）已由平台全额退款 ¥"
                                    + KgStayBrowseService.yuan(refundedTotal) + "，订单已取消" + (o.get("closeTime") == null ? "，房量已还回房态" : "")
                                    + (linked > 0 ? "；关联的 " + linked + " 个增值服务已转入退款审核" : "") + "。",
                            "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/stay-orders?orderNo=" + o.get("orderNo"));
                }
            }
            else if (!"refund_pending".equals(st))
            {
                // 部分退款：审批中的单保留申请时按政策算好的金额，其余状态记录累计退款额
                db.update("update kg_stay_order set refund_fen = :t, refund_time = now(), update_time = now() where order_id = :id",
                        p("id", o.get("orderId"), "t", refundedTotal));
            }
        }
        // 2026-10-17 消费积分：已完成的订单还能退款（门店主动退、后台直接退），到账后按退款后的实付扣回多发的积分。
        // 这张单没发过消费积分（还没完成、早就取消）的什么都不做；事务提交后另开事务做，出错只记日志，不影响退款入账
        if (pointRewards != null)
        {
            pointRewards.stayRefunded(KgJdbc.asLong(o.get("orderId")));
        }
        support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "退款已原路退回",
                "订单 " + o.get("orderNo") + " 退款 ¥" + KgStayBrowseService.yuan(amount) + " 已原路退回，到账时间以支付渠道为准。",
                "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/stays/order?orderNo=" + o.get("orderNo") + "&view=refund");
    }

    /**
     * 微信把退款关闭了（钱没有退出，2026-10-13 新增）：最早那笔同金额、还在等微信确认的门店退款记成 closed（没退成）。
     * 不然它会一直算「等微信确认」：订单台不让再退、客人不能申请退款、客人页一直显示退款处理中。
     * 订单状态不动、不自动重退：部分退款由主理人在订单台重新发起；取消订单的那笔（订单停在退款中）由平台在后台重新退款。
     */
    @Override
    public void onRefundClosed(Map<String, Object> payRow, long amount)
    {
        try
        {
            db.update("update kg_stay_staff_refund set status = 'closed' where order_id = :id and status = 'processing' "
                    + "and amount_fen = :a order by refund_id limit 1", p("id", payRow.get("bizId"), "a", amount));
        }
        catch (BadSqlGrammarException e)
        {
            log.warn("门店退款记录表缺失，跳过关闭标记 pay={}", payRow.get("payNo"));
        }
    }

    // ---------- 退款 ----------

    /** 距入住时刻的小时数（入住日 + 入住时间） */
    static double hoursLeft(Map<String, Object> o, String checkinTime)
    {
        LocalDate in = date(str(o.get("checkIn")));
        LocalTime t;
        try
        {
            t = LocalTime.parse(checkinTime == null || checkinTime.isEmpty() ? "15:00" : checkinTime);
        }
        catch (Exception e)
        {
            t = LocalTime.of(15, 0);
        }
        return Duration.between(LocalDateTime.now(), in.atTime(t)).toMinutes() / 60.0;
    }

    public Map<String, Object> refundPreview(Long userId, String orderNo)
    {
        Map<String, Object> o = own(userId, orderNo);
        return preview(o);
    }

    Map<String, Object> preview(Map<String, Object> o)
    {
        Map<String, Object> policy = json(o.get("policySnapshot"));
        Map<String, Object> snap = json(o.get("staySnapshot"));
        double h = hoursLeft(o, str(snap.get("checkinTime")));
        List<Object> nightly = jsonList(o.get("nightly"));
        long first = nightly.isEmpty() ? 0 : fen(((Map<?, ?>) nightly.get(0)).get("priceFen")) * Math.max(1, fen(o.get("units")));
        Map<String, Object> r = StayCustomPolicy.compute(policy, h, fen(o.get("roomFen")), first, fen(o.get("payFen")));
        String st = str(o.get("status"));
        boolean canApply = "confirmed".equals(st) && Boolean.TRUE.equals(r.get("allowed"));
        String why = !"confirmed".equals(st) ? "当前订单状态（" + statusLabel(st) + "）不支持申请退款" : (canApply ? null : "当前时段不支持退款");
        // 门店已经主动退过一部分（旅居订单台）：之前退款额仍按全额实付算，同意时会超出支付单剩余可退、审批永远失败。
        // 现在按「取消政策能退的总额 − 已经退过的」算（加上之前退的，总共不超过政策能退的），页面上同时列出已退金额；
        // 门店退款还在等微信确认时先不让申请，避免两笔退款同时在途
        long[] before = refundedBefore(o);
        if (before[0] > 0 || before[1] > 0)
        {
            long policyRefund = fen(r.get("refundFen"));
            long already = before[0] + before[1];
            long refund = Math.max(0, Math.min(policyRefund - already, fen(o.get("payFen")) - already));
            r.put("refundFen", refund);
            r.put("refundedFen", already);
            if (canApply && before[1] > 0)
            {
                canApply = false;
                why = "门店的退款还在等微信确认，到账后再申请";
            }
            else if (canApply && refund <= 0)
            {
                canApply = false;
                why = "门店已退款 ¥" + KgStayBrowseService.yuan(already) + "，按取消政策已没有可退金额，如需取消请联系门店";
            }
        }
        r.put("allowed", canApply);
        r.put("reason", why);
        long mins = (long) Math.max(0, h * 60);
        r.put("hoursLeft", Math.max(0, Math.round(h)));
        r.put("hoursLeftText", h <= 0 ? "已过入住时间" : (mins / 1440) + "天" + (mins % 1440 / 60) + "小时" + (mins % 60) + "分");
        r.put("paidFen", o.get("payFen"));
        return r;
    }

    /** 已经退过的钱：[0] 支付单上已确认到账的累计退款，[1] 门店退款里还在等微信确认的 */
    long[] refundedBefore(Map<String, Object> o)
    {
        long done = 0;
        String payNo = str(o.get("payNo"));
        if (!payNo.isEmpty())
        {
            Map<String, Object> pr = db.one("select refund_amount from kg_pay_order where pay_no = :n and del_flag = '0'", p("n", payNo));
            done = pr == null ? 0 : fen(pr.get("refundAmount"));
        }
        long pending = 0;
        for (Map<String, Object> r : staffRefunds(o.get("orderId")))
        {
            if ("processing".equals(str(r.get("status"))))
            {
                pending += fen(r.get("amountFen"));
            }
        }
        return new long[] { done, pending };
    }

    /** 门店主动退款记录（旅居订单台）：最早的在前。表还没建（新版后端先于 V39 上线）时当作没有记录，不影响订单页 */
    List<Map<String, Object>> staffRefunds(Object orderId)
    {
        if (orderId == null)
        {
            return new ArrayList<Map<String, Object>>();
        }
        try
        {
            List<Map<String, Object>> rows = db.list("select refund_id, amount_fen, reason, kind, status, operator_name, create_time, success_time "
                    + "from kg_stay_staff_refund where order_id = :o order by refund_id", p("o", orderId));
            return rows == null ? new ArrayList<Map<String, Object>>() : rows;
        }
        catch (BadSqlGrammarException e)
        {
            log.warn("门店退款记录表缺失 order={}", orderId);
            return new ArrayList<Map<String, Object>>();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyRefund(Long userId, String orderNo, String reason, Long expectedRefundFen)
    {
        Map<String, Object> o = own(userId, orderNo);
        Map<String, Object> pv = preview(o);
        if (!Boolean.TRUE.equals(pv.get("allowed")))
        {
            throw new ServiceException(str(pv.get("reason")));
        }
        long refund = fen(pv.get("refundFen"));
        if (expectedRefundFen != null && expectedRefundFen != refund)
        {
            throw new ServiceException("退款金额已按最新时间重新计算为 ¥" + KgStayBrowseService.yuan(refund) + "，请确认后重新提交", 409);
        }
        String why = reason == null || reason.trim().isEmpty() ? "用户申请退款" : reason.trim();
        int n = db.update("update kg_stay_order set status = 'refund_pending', refund_fen = :f, refund_reason = :r, refund_apply_time = now(), "
                + "update_time = now() where order_id = :id and status = 'confirmed'",
                p("id", o.get("orderId"), "f", refund, "r", StaffSupport.cut(why, 200)));
        if (n == 0)
        {
            throw new ServiceException("订单状态已变化，请刷新后重试");
        }
        if (refundByPlatform())
        {
            // 角色切换后退款由平台在后台审：门店只收知会，点开看订单，不再叫他们去审批（他们点同意也是 403）
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "客人申请了退款（平台审核中）",
                    "订单 " + orderNo + " 申请退款 ¥" + KgStayBrowseService.yuan(refund) + "（" + why + "），由平台审核，门店无需处理；审核结果会再通知你。",
                    "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/stay-orders?orderNo=" + orderNo);
        }
        else
        {
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "新的退款申请",
                    "订单 " + orderNo + " 申请退款 ¥" + KgStayBrowseService.yuan(refund) + "（" + why + "），请尽快审核。",
                    "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/insurance?s=4&tab=refund&orderNo=" + orderNo);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("status", "refund_pending");
        out.put("refundFen", refund);
        return out;
    }

    /**
     * 同意退款（员工或后台）：refund_pending → refunding，释放房量，调渠道原路退；mock 渠道即时到账后转 cancelled。
     * 退款申请与业务审批一起提交，渠道失败会用原退款单号重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public String approveRefund(String orderNo, String operator, Long operatorUserId)
    {
        Map<String, Object> o = db.must("select * from kg_stay_order where order_no = :n and del_flag = '0'", p("n", orderNo), "订单不存在");
        pay.lockPayment(str(o.get("payNo")));
        o = db.must("select * from kg_stay_order where order_no=:n and del_flag='0' for update", p("n", orderNo), "订单不存在");
        if (operatorUserId != null && operatorUserId.equals(KgJdbc.asLong(o.get("appUserId"))))
        {
            throw new ServiceException("不能审批自己下单的退款", HttpStatus.FORBIDDEN);
        }
        long refund = fen(o.get("refundFen"));
        int n = db.update("update kg_stay_order set status = 'refunding', refund_by = :u, update_time = now() where order_id = :id and status = 'refund_pending'",
                p("id", o.get("orderId"), "u", operator));
        if (n == 0)
        {
            throw new ServiceException("该退款已处理过");
        }
        // 迟到支付的单在关单时已经释放过房量，不能再放一次
        if (o.get("closeTime") == null)
        {
            release(o);
        }
        int linked = refundLinkedServices(o, "入住订单已取消");
        String more = linked > 0 ? "；关联的 " + linked + " 个增值服务已转入退款审核" : "";
        if (platform(operator))
        {
            // 平台在后台审批的：告诉门店结果（门店员工自己审批的不用再发）
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "平台已同意退款，订单已取消",
                    "订单 " + orderNo + "（" + MaskUtils.name(str(o.get("guestName"))) + "，" + o.get("checkIn") + " 入住）的退款申请平台已同意"
                            + (refund > 0 ? "，退 ¥" + KgStayBrowseService.yuan(refund) : "，按政策无可退金额") + "，订单已取消"
                            + (o.get("closeTime") == null ? "，房量已还回房态" : "") + more + "。",
                    "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/stay-orders?orderNo=" + orderNo);
        }
        if (refund <= 0)
        {
            db.update("update kg_stay_order set status = 'cancelled', refund_time = now(), verify_code = null where order_id = :id", p("id", o.get("orderId")));
            return "已同意，按政策无可退金额，订单已取消" + more;
        }
        pay.refund(str(o.get("payNo")), refund, str(o.get("refundReason")), operator);
        return "已同意退款 ¥" + KgStayBrowseService.yuan(refund) + "，退款处理中，到账时间以支付渠道为准" + more;
    }

    /** 审批人是不是平台后台：门店员工审批时调用方传 staff:用户ID，后台传的是后台账号名 */
    static boolean platform(String operator)
    {
        return !str(operator).startsWith("staff:");
    }

    /** 旅居、服务订单的退款是不是平台审：角色切换后（新阶段）只由平台在后台审，门店只收知会；切换前门店审 */
    public static boolean refundByPlatform()
    {
        return com.ruoyi.kangaroo.ext.staff.RolePhase.isNew();
    }

    /** 给用户看的「谁审退款」：平台 / 门店 */
    public static String refundReviewer()
    {
        return refundByPlatform() ? "平台" : "门店";
    }

    /** 入住单取消后，挂在它上面还没用的增值服务转成退款审核中（全额，仍需审核同意：切换前门店、切换后平台，不自动打款） */
    int refundLinkedServices(Map<String, Object> o, String reason)
    {
        // 不在持有入住单锁时争抢子支付单锁。支付入口会检查业务单，迟到到账由 onPaid 转人工退款。
        db.update("update kg_shop_order set status='closed', update_time=now() where stay_order_no=:no and status='pending_payment'", p("no", o.get("orderNo")));
        int n = db.update("update kg_shop_order set status = 'refund_pending', refund_fen = pay_fen, refund_reason = :r, refund_apply_time = now(), "
                + "update_time = now() where stay_order_no = :no and status = 'unused'", p("no", o.get("orderNo"), "r", reason));
        if (n > 0 && refundByPlatform())
        {
            // 切换后：平台审，门店只知会，点开看这张入住单
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "增值服务转入退款（平台审核中）",
                    "入住订单 " + o.get("orderNo") + " 已取消，关联的 " + n + " 个增值服务订单已转入退款，由平台审核，门店无需处理。", "stay_order",
                    KgJdbc.asLong(o.get("orderId")), "/pages/staff/stay-orders?orderNo=" + o.get("orderNo"));
        }
        else if (n > 0)
        {
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "增值服务待退款",
                    "入住订单 " + o.get("orderNo") + " 已取消，关联的 " + n + " 个增值服务订单已转入退款审核。", "stay_order",
                    KgJdbc.asLong(o.get("orderId")), "/pages/staff/insurance?s=4&tab=refund");
        }
        return n;
    }

    /**
     * 入住单提前结束（前台提前退房、过了退房时间转未到店）时，挂在它上面已付款还没用的增值服务：和「退款取消」那条路一样
     * 全额转退款审核（仍需审核，不自动打款），并告诉门店和客人。入住单结束后服务核销不了、改不了期，不可退的服务客人连退款都申请不了，
     * 之前只能平台到后台支付单手工退。after 不为空时只管开始时间在它之后的（退房前已经过去的时段可能用过了只是没核销，不自动退）。
     * reason 记在服务单的退款原因里；storeLead / userLead 是给门店、客人消息的开头。返回转入退款审核的单数
     */
    int refundServicesOnStayEnd(Map<String, Object> o, String reason, LocalDateTime after, String storeLead, String userLead)
    {
        String no = str(o.get("orderNo"));
        db.update("update kg_shop_order set status='closed', update_time=now() where stay_order_no=:no and status='pending_payment'", p("no", no));
        String cond = " where stay_order_no = :no and status = 'unused'";
        Map<String, Object> ps = p("no", no);
        if (after != null)
        {
            // 时段写法是 HH:mm-HH:mm（下单时按 LocalTime 解析过，都是两位数），开始时间按字符串比较即可
            cond += " and (use_date > :d or (use_date = :d and trim(substring_index(time_slot, '-', 1)) > :t))";
            ps.put("d", after.toLocalDate().toString());
            ps.put("t", after.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        List<Map<String, Object>> rows = db.list("select order_no from kg_shop_order" + cond + " order by use_date, time_slot for update", ps);
        if (rows == null || rows.isEmpty())
        {
            return 0;
        }
        Map<String, Object> up = new LinkedHashMap<String, Object>(ps);
        up.put("r", reason);
        int n = db.update("update kg_shop_order set status = 'refund_pending', refund_fen = pay_fen, refund_reason = :r, refund_apply_time = now(), "
                + "update_time = now()" + cond, up);
        if (n > 0)
        {
            // 切换后平台审：门店只知会（点开看这张入住单），客人那边写明由平台审核
            boolean platform = refundByPlatform();
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, platform ? "增值服务转入退款（平台审核中）" : "增值服务待退款",
                    storeLead + "，客人还没使用的 " + n + " 个增值服务订单已全额转入退款" + (platform ? "，由平台审核，门店无需处理。" : "审核。"), "stay_order",
                    KgJdbc.asLong(o.get("orderId")), platform ? "/pages/staff/stay-orders?orderNo=" + no : "/pages/staff/insurance?s=4&tab=refund");
            // 打开第一张服务单：服务页按它的状态切到「退款」页签，同一批的都在那里
            support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "增值服务已转退款审核",
                    userLead + "，你还没使用的 " + n + " 项增值服务已全额转入退款审核，" + (platform ? "平台" : "") + "审核通过后原路退回，到账时间以支付渠道为准。",
                    "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/stays/shop?orderNo=" + rows.get(0).get("orderNo"));
        }
        return n;
    }

    @Transactional(rollbackFor = Exception.class)
    public String rejectRefund(String orderNo, String note, String operator)
    {
        String why = note == null ? "" : note.trim();
        if (why.isEmpty())
        {
            throw new ServiceException("请填写驳回原因");
        }
        Map<String, Object> o = db.must("select * from kg_stay_order where order_no = :n and del_flag = '0' for update", p("n", orderNo), "订单不存在");
        if (o.get("closeTime") != null)
        {
            throw new ServiceException("已关闭订单的迟到付款需继续退款处理，不能恢复入住", 409);
        }
        int n = db.update("update kg_stay_order set status = 'confirmed', refund_note = :r, refund_by = :u, refund_fen = 0, update_time = now() "
                + "where order_id = :id and status = 'refund_pending'", p("id", o.get("orderId"), "r", StaffSupport.cut(why, 200), "u", operator));
        if (n == 0)
        {
            throw new ServiceException("该退款已处理过");
        }
        support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "退款申请未通过",
                "订单 " + orderNo + " 的退款申请未通过：" + why, "stay_order", KgJdbc.asLong(o.get("orderId")),
                "/pages/stays/order?orderNo=" + orderNo + "&view=refund");
        if (platform(operator))
        {
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "平台已驳回退款申请",
                    "订单 " + orderNo + "（" + MaskUtils.name(str(o.get("guestName"))) + "，" + o.get("checkIn") + " 入住）的退款申请平台已驳回：" + why
                            + "。订单恢复为待入住，请照常接待。",
                    "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/stay-orders?orderNo=" + orderNo);
        }
        return "已驳回";
    }

    // ---------- 门店主动退款（旅居订单台，主理人） ----------

    /** 门店能主动退款的订单状态。退款审核中（客人已申请）按「用主理人填的金额同意这次申请」处理 */
    static final List<String> STAFF_REFUND_STATUS = Arrays.asList("confirmed", "checked_in", "completed", "no_show", "refund_pending");

    /**
     * 门店主动退款（2026-10-11 新增，主人要求「员工端旅居订单里加退款，可以不按前期设置的退订扣多少」）。
     * 金额由主理人自己填（0.01 元到这张单还没退的余额），必须写原因，钱原路退回客人的微信支付：
     *   - 待入住的单把余额全退：和同意退款一样转「退款中」、释放房量与早鸟名额、关联的增值服务转退款审核，到账后变「已取消」；
     *   - 部分退款，或已入住 / 已完成 / 未到店的单：订单状态不变，只记一笔门店退款（订单详情显示「已退 ¥xx」）；
     *   - 客人已申请退款（退款审核中）：等于按主理人填的金额同意这次申请，订单取消；订单关闭后才到账的钱只能全额退。
     * 先锁支付单再锁订单（和审批退款、支付回调同一顺序，不会互相等死）；同一提交编号重放直接返回上次结果，
     * 同一订单 30 秒内同样的金额再来一次当作连点拒绝；微信还有没确认的退款时不让再退。
     * 权限（只有本店主理人、不能给自己下的单退款）由调用方和这里各把一道。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> staffRefund(Long storeId, String orderNo, long amountFen, String reason, String idemKey, Long operatorUserId, String operatorName)
    {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 100)
        {
            throw new ServiceException("请填写退款原因（1–100 字），客人会看到", 422);
        }
        String idem = idemKey == null ? "" : idemKey.trim();
        if (idem.length() > 64)
        {
            throw new ServiceException("提交编号过长", 422);
        }
        if (amountFen <= 0)
        {
            throw new ServiceException("退款金额至少 0.01 元", 422);
        }
        Map<String, Object> head = db.one("select order_id, store_id, pay_no from kg_stay_order where order_no = :n and del_flag = '0'", p("n", orderNo));
        if (head == null || storeId == null || !storeId.equals(KgJdbc.asLong(head.get("storeId"))))
        {
            throw new ServiceException("订单不存在或不属于本店", HttpStatus.NOT_FOUND);
        }
        String payNo = str(head.get("payNo"));
        if (payNo.isEmpty())
        {
            throw new ServiceException("这张订单没有付款记录，不能退款", 409);
        }
        // 下面一律用加锁读（当前读）：开头那次普通读已经定下了快照，看不到别人刚提交的退款
        Map<String, Object> payRow = db.must("select * from kg_pay_order where pay_no = :n and del_flag = '0' for update", p("n", payNo), "支付单不存在");
        Map<String, Object> o = db.must("select * from kg_stay_order where order_id = :id for update", p("id", head.get("orderId")), "订单不存在");
        Long orderId = KgJdbc.asLong(o.get("orderId"));
        if (!idem.isEmpty())
        {
            Map<String, Object> done = db.one("select * from kg_stay_staff_refund where order_id = :o and idem_key = :k for update", p("o", orderId, "k", idem));
            if (done != null)
            {
                return staffRefundResult(done, str(o.get("status")), true);
            }
        }
        if (operatorUserId != null && operatorUserId.equals(KgJdbc.asLong(o.get("appUserId"))))
        {
            throw new ServiceException("不能给自己下的订单退款，请让本店其他主理人处理", HttpStatus.FORBIDDEN);
        }
        String st = str(o.get("status"));
        if (!STAFF_REFUND_STATUS.contains(st))
        {
            throw new ServiceException("订单" + statusLabel(st) + "，不能退款", 409);
        }
        String payStatus = str(payRow.get("status"));
        if (!"paid".equals(payStatus) && !"part_refunded".equals(payStatus))
        {
            throw new ServiceException("这张订单已经没有可退的钱了", 409);
        }
        pay.requireRefundable(payRow);
        // 只看还没办完的微信退款：被微信关闭的（钱没退出）之前也算在途，这张单就再也退不了了（2026-10-13）
        if (db.count("select count(*) from kg_pay_refund where pay_no = :n and " + KgPayService.ACTIVE_REFUND_SQL + " for update", p("n", payNo)) > 0
                || db.count("select count(*) from kg_stay_staff_refund where order_id = :o and status = 'processing' for update", p("o", orderId)) > 0)
        {
            throw new ServiceException("上一笔退款还在等微信确认，到账后才能再退，请稍后刷新再试", 409);
        }
        if (db.count("select count(*) from kg_stay_staff_refund where order_id = :o and amount_fen = :a and create_time > now() - interval 30 second for update",
                p("o", orderId, "a", amountFen)) > 0)
        {
            throw new ServiceException("30 秒内刚给这张订单退过同样的金额，请刷新订单确认，别重复退款", 409);
        }
        long remaining = fen(payRow.get("amount")) - fen(payRow.get("refundAmount"));
        if (remaining <= 0)
        {
            throw new ServiceException("这张订单已经没有可退的钱了", 409);
        }
        if (amountFen > remaining)
        {
            throw new ServiceException("退款金额超出可退范围：这张订单最多还能退 ¥" + KgStayBrowseService.yuan(remaining), 422);
        }
        boolean late = o.get("closeTime") != null;
        String kind;
        if ("refund_pending".equals(st))
        {
            if (late && amountFen != remaining)
            {
                throw new ServiceException("这笔是订单关闭后才到账的钱，只能全额退回 ¥" + KgStayBrowseService.yuan(remaining), 422);
            }
            kind = "approve";
        }
        else
        {
            kind = "confirmed".equals(st) && amountFen == remaining ? "full" : "partial";
        }
        String by = StaffSupport.cut("staff:" + operatorUserId, 64);
        boolean cancel = !"partial".equals(kind);
        if (cancel)
        {
            // refund_fen 记取消订单这一笔的金额（之前门店的部分退款另有记录，客人页分开显示）
            if (db.update("update kg_stay_order set status = 'refunding', refund_fen = :f, refund_by = :u, update_time = now() where order_id = :id and status = :st",
                    p("id", orderId, "f", amountFen, "u", by, "st", st)) == 0)
            {
                throw new ServiceException("订单状态刚刚变化，请刷新后重试", 409);
            }
            // 迟到支付的单在关单时已经释放过房量，不能再放一次
            if (!late)
            {
                release(o);
            }
            refundLinkedServices(o, "入住订单已由门店退款取消");
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("order_id", orderId);
        row.put("order_no", o.get("orderNo"));
        row.put("store_id", o.get("storeId"));
        row.put("pay_no", payNo);
        row.put("amount_fen", amountFen);
        row.put("reason", why);
        row.put("kind", kind);
        row.put("status", "processing");
        row.put("idem_key", idem.isEmpty() ? null : idem);
        row.put("operator_user_id", operatorUserId);
        row.put("operator_name", StaffSupport.cut(operatorName, 64));
        row.put("create_time", new Date());
        long refundId;
        try
        {
            refundId = db.insert("kg_stay_staff_refund", row);
        }
        catch (DuplicateKeyException e)
        {
            throw new ServiceException("这笔退款正在提交，请刷新订单查看", 409);
        }
        // 联调渠道即时到账：onRefunded 会在这一步里把这一行记成已到账、把退款中的订单转成已取消；
        // 微信渠道这里只登记退款申请，和订单改动一起提交，之后由微信通知 / 定时查单入账
        pay.refund(payNo, amountFen, "门店退款：" + why, by);
        Map<String, Object> saved = db.must("select * from kg_stay_staff_refund where refund_id = :id", p("id", refundId), "退款记录不存在");
        String money = "¥" + KgStayBrowseService.yuan(amountFen);
        // 微信渠道这时只是登记了退款、还没交给微信：不能说「钱会原路退回」（10-13 客人收到这句话钱却没到，微信那边因余额不足没退出去）。
        // 退款成功后 onRefunded 会再发一条「退款已原路退回」；联调渠道这一步已经退成，照旧这么说
        boolean processing = "processing".equals(str(saved.get("status")));
        support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, (processing ? "门店为你发起退款 " : "门店给你退款 ") + money,
                (processing ? "门店为你发起退款 " + money + "：" + why + "。订单 " + o.get("orderNo") + "，正在提交微信支付处理，退款成功后会再通知你。"
                        : "门店给你退款 " + money + "：" + why + "。订单 " + o.get("orderNo") + "，钱会原路退回你的微信支付，到账时间以微信为准。")
                        + (cancel ? "这张订单已取消。" : "订单照常有效。"),
                "stay_order", orderId, "/pages/stays/order?orderNo=" + o.get("orderNo") + "&view=" + (cancel ? "refund" : "trip"));
        Map<String, Object> now = db.one("select status from kg_stay_order where order_id = :id", p("id", orderId));
        return staffRefundResult(saved, now == null ? st : str(now.get("status")), false);
    }

    static Map<String, Object> staffRefundResult(Map<String, Object> row, String orderStatus, boolean replay)
    {
        String kind = str(row.get("kind"));
        boolean processing = "processing".equals(str(row.get("status")));
        String money = "¥" + KgStayBrowseService.yuan(fen(row.get("amountFen")));
        String text = "approve".equals(kind) ? "已按 " + money + " 同意客人的退款申请，订单已取消"
                : ("full".equals(kind) ? (processing ? "已发起全额退款 " : "已全额退款 ") + money + "，订单已取消，房量已还回房态"
                        : (processing ? "已发起退款 " : "已退款 ") + money + "，订单状态不变");
        // 之前写「已提交微信，到账时间以微信为准」：其实这一步只登记了退款，由系统随后提交微信，微信也可能退不出去（如商户号余额不足）。
        // 同一提交编号重放时这一笔可能已被微信关闭（closed，钱没退出），不能再说「钱已原路退回客人」
        boolean closed = "closed".equals(str(row.get("status")));
        if (closed)
        {
            text = ("approve".equals(kind) ? "按 " + money + " 同意的退款" : ("full".equals(kind) ? "全额退款 " : "退款 ") + money)
                    + " 被微信关闭，钱没有退出" + ("partial".equals(kind) ? "，可以重新发起"
                            : ("refunding".equals(orderStatus) ? "，订单停在退款中，请联系平台在后台重新退款" : ""));
        }
        else
        {
            text += processing ? "。退款已登记，系统正在提交微信支付，退款成功后会通知客人" : "，钱已原路退回客人";
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("refundId", row.get("refundId"));
        m.put("kind", kind);
        m.put("amountFen", row.get("amountFen"));
        m.put("processing", processing);
        m.put("orderStatus", orderStatus);
        m.put("replay", replay);
        m.put("message", replay ? "这笔退款刚才已经提交过：" + text : text);
        return m;
    }

    // ---------- 核销码 ----------

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> refreshCode(Long userId, String orderNo)
    {
        Map<String, Object> o = own(userId, orderNo);
        if (!"confirmed".equals(str(o.get("status"))))
        {
            throw new ServiceException("只有待入住的订单可以刷新核销码");
        }
        for (int i = 0; i < 5; i++)
        {
            String c = StayCodes.verifyCode();
            if (db.count("select count(*) from kg_shop_order where verify_code = :c", p("c", c)) > 0)
            {
                continue;
            }
            try
            {
                db.update("update kg_stay_order set verify_code = :c, update_time = now() where order_id = :id and status = 'confirmed'",
                        p("c", c, "id", o.get("orderId")));
                return view(db.one("select * from kg_stay_order where order_id = :id", p("id", o.get("orderId"))), true);
            }
            catch (DuplicateKeyException e)
            {
                // 撞码换一个
            }
        }
        throw new ServiceException("核销码生成失败，请重试");
    }

    // ---------- 定时：超时关单、到期流转 ----------

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void closeExpired()
    {
        List<Map<String, Object>> rows = db.list("select * from kg_stay_order where status = 'pending_payment' and expire_time < now() limit 100", p());
        for (Map<String, Object> o : rows)
        {
            try
            {
                self.closeOneExpired(o);
            }
            catch (RuntimeException e)
            {
                log.warn("关单失败 {}：{}", o.get("orderNo"), e.getMessage());
            }
        }
        try
        {
            self.progress();
        }
        catch (RuntimeException e)
        {
            log.warn("订单到期流转失败：{}", e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void closeOneExpired(Map<String, Object> o)
    {
        // 先关支付单：关不掉说明钱已经到了，按已支付处理（支付回调里会把订单转成待入住）
        if (pay.close(str(o.get("payNo"))) && closeOrder(o, "超时未支付"))
        {
            // 超时关单告诉客人一声：之前没有任何消息，客人回来付款才发现订单没了
            String no = str(o.get("orderNo"));
            support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "订单已超时关闭",
                    "订单 " + no + "（" + stayName(o) + "，" + o.get("checkIn") + " 入住）超过 " + PAY_MINUTES + " 分钟未支付，已自动关闭，房间已释放。如仍想入住请重新预订。",
                    "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/stays/order?orderNo=" + no);
        }
    }

    /** 退房时间的 SQL 表达式：库里不是 HH:mm（空 / 自由文本，历史数据）时按 12:00 算，否则 timestamp() 得 NULL，订单永远不会流转 */
    private static final String CHECKOUT_TIME = "case when s.checkout_time regexp '^([01][0-9]|2[0-3]):[0-5][0-9]$' then s.checkout_time else '12:00' end";

    /** 办理入住后告诉客人（前台办理、扫核销码同一句） */
    public void notifyCheckedIn(Map<String, Object> o)
    {
        String no = str(o.get("orderNo"));
        support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "已办理入住",
                stayName(o) + " 已为你办理入住，" + o.get("checkOut") + " 退房。入住期间可以在订单里预订增值服务，有事随时联系门店。",
                "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/stays/order?orderNo=" + no + "&view=trip");
    }

    /** 住完了（前台退房、提前退房、到点自动完成）告诉客人，并请他写评价（和「我的订单」里「去评价」同一个页面） */
    public void notifyStayDone(Map<String, Object> o, String how)
    {
        support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "提前退房".equals(how) ? "已办理提前退房" : ("自动完成".equals(how) ? "住宿已完成" : "已办理退房"),
                stayName(o) + ("自动完成".equals(how) ? " 的住宿已完成" : " 已为你办理" + how) + "，感谢入住。欢迎写下这次住宿的评价，帮后来的人选小院。",
                "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/profile/review?bizType=stay_order&bizId=" + o.get("orderId"));
    }

    /** confirmed 过了离店退房时间仍未核销 → no_show；checked_in 过了退房时间 2 小时 → completed */
    @Transactional(rollbackFor = Exception.class)
    public void progress()
    {
        // 未到店逐单转：之前一条 update 批量改，挂在上面已付款的增值服务留在「待使用」，核销不了、改不了期、不可退的连退款都申请不了
        List<Map<String, Object>> gone = db.list("select o.*, s.stay_name from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id "
                + "where o.status = 'confirmed' and timestamp(o.check_out, concat(" + CHECKOUT_TIME + ", ':00')) < now() limit 200", p());
        for (Map<String, Object> o : gone)
        {
            if (db.update("update kg_stay_order set status = 'no_show', verify_code = null, update_time = now() where order_id = :id and status = 'confirmed'",
                    p("id", o.get("orderId"))) > 0)
            {
                String no = str(o.get("orderNo"));
                support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "订单已记为未到店",
                        "订单 " + no + "（" + stayName(o) + "，" + o.get("checkIn") + " 入住）过了退房时间仍未办理入住，已记为未到店。如有疑问请联系门店。",
                        "stay_order", KgJdbc.asLong(o.get("orderId")), "/pages/stays/order?orderNo=" + no);
                refundServicesOnStayEnd(o, "客人未到店，入住订单已结束", null, "入住订单 " + no + " 客人未到店、已结束",
                        "入住订单 " + no + " 已记为未到店");
            }
        }
        List<Map<String, Object>> done = db.list("select o.order_id, o.stay_id, o.order_no, o.app_user_id, o.stay_snapshot, s.stay_name from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id "
                + "where o.status = 'checked_in' and timestamp(o.check_out, concat(" + CHECKOUT_TIME + ", ':00')) + interval 2 hour < now() limit 200", p());
        for (Map<String, Object> d : done)
        {
            if (db.update("update kg_stay_order set status = 'completed', update_time = now() where order_id = :id and status = 'checked_in'",
                    p("id", d.get("orderId"))) > 0)
            {
                db.update("update kg_stay set completed_orders = completed_orders + 1 where stay_id = :s", p("s", d.get("stayId")));
                // 2026-10-17 消费积分：自动完成的单和前台退房一样发（这一轮流转提交后另开事务发，出错只记日志）
                if (pointRewards != null)
                {
                    pointRewards.stayCompleted(KgJdbc.asLong(d.get("orderId")));
                }
                // 前台没点退房、到点自动完成的，同样请客人写评价。这时服务日期都已过去（离店日只能约退房前的时段），不自动退
                notifyStayDone(d, "自动完成");
            }
        }
    }
}
