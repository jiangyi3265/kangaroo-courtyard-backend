package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.fen;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.str;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgPointRewards;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 员工端「旅居订单台」：订单分组（待排房 / 今日到店 / 在住 / 今日离店 / 近期 / 已结束）、房态图、排房（房间 / 床位）、
 * 前台办理入住与退房、内部备注，以及房间床位设置。思路同民宿 PMS。
 *
 * 库存仍以房型的「间数 / 床位数」（kg_stay_calendar.occupied）为准，这里不改变能卖多少；
 * 排房只回答「哪位客人住哪一间 / 哪一个床位」。一张订单 units 间（或床位）就要排 units 个房间 / 床位，
 * 同一房间 / 床位同一晚只能排给一张订单（kg_stay_assign 的 [start_date, end_date) 不重叠，排房时锁房间行再查）。
 *
 * 权限：看订单和房态要「房源·查看」；排房、办理入住 / 退房、备注要「房源·编辑」；房间床位设置要「房源·审核」（小院主理人 / 城市负责人），
 * 或者是本店的合作商家（2026-11-09 主人拍板，见 canSetupUnits）。
 */
@Service
public class KgStayPmsService
{
    /** 占着房的订单状态（排房冲突按这些算；已完成的只剩历史日期，不会挡住以后） */
    static final List<String> OCCUPY = Arrays.asList("confirmed", "checked_in", "refund_pending", "refunding", "completed");
    /** 还需要排房的订单状态 */
    static final List<String> ARRANGE = Arrays.asList("confirmed", "checked_in", "refund_pending");
    static final List<String> TABS = Arrays.asList("unassigned", "arrivals", "inhouse", "departures", "upcoming", "done");
    static final Map<String, String> TAB_LABEL = new LinkedHashMap<String, String>();
    static
    {
        TAB_LABEL.put("unassigned", "待排房");
        TAB_LABEL.put("arrivals", "今日到店");
        TAB_LABEL.put("inhouse", "在住");
        TAB_LABEL.put("departures", "今日离店");
        TAB_LABEL.put("upcoming", "近期订单");
        TAB_LABEL.put("done", "已结束");
    }
    private static final String[] WEEK = { "周一", "周二", "周三", "周四", "周五", "周六", "周日" };

    @Autowired(required=false) private com.ruoyi.kangaroo.ext.user.KgMemberWallet memberWallet;
    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgStayOrderService stayOrders;

    /** 2026-10-17 退房完成发消费积分。required = false：只用 mock 拼起来的单元测试里没有它 */
    @Autowired(required = false)
    private KgPointRewards pointRewards;

    // ------------------------------------------------------------ 工具

    static String day(Object v)
    {
        String s = str(v);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    static LocalDate date(Object v)
    {
        String s = day(v);
        return KgJdbc.isDate(s) ? LocalDate.parse(s) : null;
    }

    private static String md(Object v)
    {
        String s = day(v);
        return s.length() == 10 ? Integer.parseInt(s.substring(5, 7)) + "/" + Integer.parseInt(s.substring(8, 10)) : s;
    }

    LocalDate today()
    {
        return LocalDate.now();
    }

    private boolean canSeeContact(StaffSupport.Ctx c)
    {
        return StaffPerms.allows(c.role, "rooms", StaffPerms.EDIT);
    }

    /**
     * 能不能设置房间 / 床位（添加、改名、停用、删除）。原来只认「房源·审核」；2026-11-09 主人拍板合作商家也能管自己门店的房间床位
     * （没有小院主理人的店，订单一直挂在「待排房」）。只放开这一块，不给商家整个「房源·审核」：那一格还管 Vlog 审核、评论隐藏等。
     * 门店范围由 typeOf / unitOf 按 storeId 收，商家只碰得到自己店的房型。
     */
    static boolean canSetupUnits(StaffSupport.Ctx c)
    {
        return StaffPerms.allows(c.role, "rooms", StaffPerms.AUDIT)
                || (StaffRole.MERCHANT.equals(c.role) && StaffPerms.allows(c.role, "rooms", StaffPerms.EDIT));
    }

    /** 没有房间床位设置权限：照旧按「房源·审核」拒绝并记失败日志 */
    private void needSetup(StaffSupport.Ctx c, String opLabel)
    {
        if (!canSetupUnits(c))
        {
            support.need(c, "rooms", StaffPerms.AUDIT, "stay_unit_edit", opLabel);
        }
    }

    private String guestName(StaffSupport.Ctx c, Object name)
    {
        return canSeeContact(c) ? str(name) : MaskUtils.name(str(name));
    }

    /** 退房时刻：房源的退房时间（HH:mm，非法按 12:00） */
    private static LocalDateTime checkoutAt(Object checkOut, Object checkoutTime)
    {
        String t = str(checkoutTime);
        if (!t.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
        {
            t = "12:00";
        }
        LocalDate d = date(checkOut);
        return d == null ? null : d.atTime(LocalTime.parse(t));
    }

    /** 订单 + 房源退房时间 + 房型名称；不是这家门店的订单一律当作不存在 */
    Map<String, Object> order(StaffSupport.Ctx c, String orderNo)
    {
        Map<String, Object> o = db.one("select o.*, s.stay_name, s.checkout_time, t.type_name, t.unit_label, t.room_kind from kg_stay_order o "
                + "join kg_stay s on s.stay_id = o.stay_id left join kg_stay_room_type t on t.room_type_id = o.room_type_id "
                + "where o.order_no = :n and o.store_id = :st and o.del_flag = '0'", p("n", orderNo, "st", c.storeId));
        if (o == null)
        {
            throw new ServiceException("订单不存在或不属于本店");
        }
        return o;
    }

    /** 某张订单当前有效的排房（房间名按排序） */
    List<Map<String, Object>> assigns(Long orderId)
    {
        return db.list("select a.assign_id, a.unit_id, a.start_date, a.end_date, u.unit_name from kg_stay_assign a join kg_stay_unit u on u.unit_id = a.unit_id "
                + "where a.order_id = :o and a.status = 'active' order by u.sort, u.unit_id, a.start_date", p("o", orderId));
    }

    /** 订单还差几个房间 / 床位没排：以今天以后（含今天）的晚上为准，已入住的订单看今晚 */
    int missing(Map<String, Object> o, List<Map<String, Object>> as)
    {
        LocalDate from = maxDate(date(o.get("checkIn")), today());
        LocalDate to = date(o.get("checkOut"));
        if (to == null || !from.isBefore(to))
        {
            return 0;
        }
        int covered = 0;
        for (Map<String, Object> a : as)
        {
            LocalDate s = date(a.get("startDate")), e = date(a.get("endDate"));
            if (s != null && e != null && !s.isAfter(from) && e.isAfter(from))
            {
                covered++;
            }
        }
        return Math.max(0, (int) fen(o.get("units")) - covered);
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b)
    {
        if (a == null) return b;
        return a.isAfter(b) ? a : b;
    }

    // ------------------------------------------------------------ 订单台

    /** 门店的房源、各分组数量 */
    public Map<String, Object> overview(StaffSupport.Ctx c, Long stayId)
    {
        support.need(c, "rooms", StaffPerms.VIEW, "stay_pms_view", "查看旅居订单");
        // 订单台默认看第一处房源：有进行中订单的排最前，其次已上线的（顺序同房源管理）。
        // 之前按 stay_id 排，最早建的是草稿 / 已下架房源时默认停在它上面，在卖房源的新订单要手动切换才看得到
        List<Map<String, Object>> stays = db.list("select s.stay_id, s.stay_name, s.status from kg_stay s where s.store_id = :st and s.del_flag = '0' "
                + "order by exists (select 1 from kg_stay_order o where o.stay_id = s.stay_id and o.del_flag = '0' "
                + "and o.status in ('confirmed','checked_in','refund_pending','refunding') and o.check_out >= :today) desc, "
                + "field(s.status, 'online', 'pending', 'rejected', 'draft', 'offline'), s.sort, s.stay_id",
                p("st", c.storeId, "today", today().toString()));
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        for (String tab : TABS)
        {
            counts.put(tab, rows(c, tab, stayId, null).size());
        }
        List<Map<String, Object>> tabs = new ArrayList<Map<String, Object>>();
        for (String tab : TABS)
        {
            tabs.add(p("key", tab, "label", TAB_LABEL.get(tab), "count", counts.get(tab)));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("storeName", c.storeName);
        m.put("stays", stays);
        m.put("tabs", tabs);
        m.put("counts", counts);
        m.put("today", today().toString());
        m.put("canEdit", StaffPerms.allows(c.role, "rooms", StaffPerms.EDIT));
        m.put("canSetup", canSetupUnits(c));
        return m;
    }

    public List<Map<String, Object>> orders(StaffSupport.Ctx c, String tab, Long stayId, String keyword)
    {
        support.need(c, "rooms", StaffPerms.VIEW, "stay_pms_view", "查看旅居订单");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> o : rows(c, TABS.contains(tab) ? tab : "unassigned", stayId, keyword))
        {
            out.add(card(c, o, assigns(KgJdbc.asLong(o.get("orderId")))));
        }
        return out;
    }

    /**
     * 分组查询；待排房需要逐单算还差几个房间 / 床位。
     * 有关键词时不分组、不限房源，按本店全部订单搜（新的在前）：之前搜索也受分组限制，已离店、未到店的单按订单号都搜不到，
     * 门店主动退款（这两种状态也能退）找不到入口
     */
    List<Map<String, Object>> rows(StaffSupport.Ctx c, String tab, Long stayId, String keyword)
    {
        String today = today().toString();
        Map<String, Object> ps = p("st", c.storeId, "today", today);
        StringBuilder where = new StringBuilder(" where o.store_id = :st and o.del_flag = '0'");
        boolean search = keyword != null && !keyword.trim().isEmpty();
        if (stayId != null && !search)
        {
            where.append(" and o.stay_id = :stay");
            ps.put("stay", stayId);
        }
        if (search)
        {
            where.append(" and (o.order_no like :kw or o.guest_name like :kw or o.guest_phone like :kw)");
            ps.put("kw", "%" + keyword.trim() + "%");
            return db.list("select o.*, s.stay_name, s.checkout_time, t.type_name, t.unit_label, t.room_kind from kg_stay_order o "
                    + "join kg_stay s on s.stay_id = o.stay_id left join kg_stay_room_type t on t.room_type_id = o.room_type_id"
                    + where + " order by o.check_in desc, o.order_id desc limit 200", ps);
        }
        String order = " order by o.check_in, o.order_id";
        switch (tab)
        {
            case "arrivals":
                where.append(" and o.check_in = :today and o.status in ('confirmed','checked_in','refund_pending')");
                break;
            case "inhouse":
                where.append(" and o.status = 'checked_in'");
                break;
            case "departures":
                where.append(" and o.status = 'checked_in' and o.check_out <= :today");
                order = " order by o.check_out, o.order_id";
                break;
            case "upcoming":
                where.append(" and o.status in ('confirmed','checked_in','refund_pending','refunding') and o.check_out >= :today "
                        + "and o.check_in <= date_add(:today, interval 60 day)");
                break;
            case "done":
                // 已离店 / 未到店（近 60 天，新离店的在前）：主理人还能给这些单主动退款，之前订单台的分组里都看不到
                where.append(" and o.status in ('completed','no_show') and o.check_out >= date_sub(:today, interval 60 day)");
                order = " order by o.check_out desc, o.order_id desc";
                break;
            default:
                where.append(" and o.status in ('confirmed','checked_in','refund_pending') and o.check_out > :today");
        }
        List<Map<String, Object>> list = db.list("select o.*, s.stay_name, s.checkout_time, t.type_name, t.unit_label, t.room_kind from kg_stay_order o "
                + "join kg_stay s on s.stay_id = o.stay_id left join kg_stay_room_type t on t.room_type_id = o.room_type_id"
                + where + order + " limit 200", ps);
        if (!"unassigned".equals(tab))
        {
            return list;
        }
        List<Map<String, Object>> need = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> o : list)
        {
            if (missing(o, assigns(KgJdbc.asLong(o.get("orderId")))) > 0)
            {
                need.add(o);
            }
        }
        return need;
    }

    Map<String, Object> card(StaffSupport.Ctx c, Map<String, Object> o, List<Map<String, Object>> as)
    {
        String st = str(o.get("status"));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("orderNo", o.get("orderNo"));
        m.put("stayId", o.get("stayId"));
        m.put("stayName", o.get("stayName"));
        m.put("roomTypeId", o.get("roomTypeId"));
        m.put("typeName", str(o.get("typeName")).isEmpty() ? "房型已删除" : o.get("typeName"));
        String unitLabel = str(o.get("unitLabel")).isEmpty() ? "间" : str(o.get("unitLabel"));
        m.put("unitLabel", unitLabel);
        m.put("checkIn", day(o.get("checkIn")));
        m.put("checkOut", day(o.get("checkOut")));
        m.put("dateText", md(o.get("checkIn")) + " → " + md(o.get("checkOut")) + " · " + fen(o.get("nights")) + "晚");
        m.put("guests", o.get("guests"));
        m.put("units", o.get("units"));
        m.put("unitsText", fen(o.get("units")) + ("床位".equals(unitLabel) ? "个床位" : "间") + " · " + fen(o.get("guests")) + "人");
        m.put("guestName", guestName(c, o.get("guestName")));
        m.put("guestPhone", MaskUtils.phone(str(o.get("guestPhone"))));
        m.put("arriveTime", o.get("arriveTime"));
        m.put("remark", o.get("remark"));
        m.put("staffNote", o.get("staffNote"));
        m.put("status", st);
        // 已完成 / 未到店的单后来退了款（后台直接退、门店主动退）时标出「已退款 ¥x」，订单状态本身不变
        m.put("statusLabel", KgStayOrderService.statusLabel(st, o.get("refundFen"), o.get("payFen")));
        m.put("payFen", o.get("payFen"));
        List<String> names = new ArrayList<String>();
        for (Map<String, Object> a : as)
        {
            names.add(str(a.get("unitName")));
        }
        m.put("assigned", new ArrayList<String>(new LinkedHashSet<String>(names)));
        int miss = ARRANGE.contains(st) ? missing(o, as) : 0;
        m.put("missing", miss);
        boolean edit = StaffPerms.allows(c.role, "rooms", StaffPerms.EDIT);
        LocalDateTime out = checkoutAt(o.get("checkOut"), o.get("checkoutTime"));
        LocalDate in = date(o.get("checkIn"));
        boolean inWindow = in != null && out != null && !today().isBefore(in) && LocalDateTime.now().isBefore(out);
        m.put("canAssign", edit && ARRANGE.contains(st) && out != null && LocalDateTime.now().isBefore(out));
        m.put("canCheckIn", edit && "confirmed".equals(st) && inWindow);
        m.put("checkInHint", "confirmed".equals(st) && !inWindow ? (in != null && today().isBefore(in) ? md(o.get("checkIn")) + " 起可办理入住" : "已过退房时间") : null);
        m.put("canCheckOut", edit && "checked_in".equals(st));
        m.put("early", "checked_in".equals(st) && date(o.get("checkOut")) != null && today().isBefore(date(o.get("checkOut"))));
        return m;
    }

    public Map<String, Object> detail(StaffSupport.Ctx c, String orderNo)
    {
        support.need(c, "rooms", StaffPerms.VIEW, "stay_pms_view", "查看旅居订单");
        Map<String, Object> o = order(c, orderNo);
        List<Map<String, Object>> as = assigns(KgJdbc.asLong(o.get("orderId")));
        Map<String, Object> m = card(c, o, as);
        // 订单详情底部「纠纷调解」组件按 orderId 问服务端能不能调解（stay-orders.vue :order-id="d.orderId"），之前没给，按钮永远不出现
        m.put("orderId", o.get("orderId"));
        m.put("guestPhone", canSeeContact(c) ? str(o.get("guestPhone")) : MaskUtils.phone(str(o.get("guestPhone"))));
        m.put("phoneVisible", canSeeContact(c));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : as)
        {
            rows.add(p("unitId", a.get("unitId"), "unitName", a.get("unitName"), "from", day(a.get("startDate")), "to", day(a.get("endDate")),
                    "text", a.get("unitName") + "（" + md(a.get("startDate")) + " → " + md(a.get("endDate")) + "）"));
        }
        m.put("assignments", rows);
        List<Map<String, Object>> timeline = new ArrayList<Map<String, Object>>();
        addStep(timeline, o.get("createTime"), "下单");
        addStep(timeline, o.get("paidTime"), "支付成功");
        addStep(timeline, o.get("verifyTime"), "办理入住" + (str(o.get("verifiedBy")).isEmpty() ? "" : "（" + o.get("verifiedBy") + "）"));
        addStep(timeline, o.get("checkedOutTime"), "办理退房" + (str(o.get("checkedOutBy")).isEmpty() ? "" : "（" + o.get("checkedOutBy") + "）"));
        addStep(timeline, o.get("refundApplyTime"), "申请退款：" + str(o.get("refundReason")));
        // 门店主动退款每一笔一条；之前只有一条「退款完成」（取最后一次退款时间），部分退款几次都看不出来
        List<Map<String, Object>> staffRefunds = stayOrders.staffRefunds(o.get("orderId"));
        long pendingFen = 0;
        String waiting = null;
        for (Map<String, Object> r : staffRefunds)
        {
            boolean processing = "processing".equals(str(r.get("status")));
            pendingFen += processing ? fen(r.get("amountFen")) : 0;
            if (processing && waiting == null)
            {
                waiting = refundWaitText(o.get("payNo"));
            }
            String kind = str(r.get("kind"));
            addStep(timeline, r.get("createTime"), ("approve".equals(kind) ? "门店按 " : "门店退款 ") + "¥" + KgStayBrowseService.yuan(fen(r.get("amountFen")))
                    + ("approve".equals(kind) ? " 同意退款" : "") + ("full".equals(kind) ? "（全额，订单取消）" : "")
                    + (str(r.get("operatorName")).isEmpty() ? "" : "（" + r.get("operatorName") + "）") + "：" + r.get("reason")
                    + (processing ? waiting : ("closed".equals(str(r.get("status"))) ? "　微信关闭了这笔退款，钱没有退出" : "")));
        }
        String st = str(o.get("status"));
        addStep(timeline, o.get("refundTime"), "cancelled".equals(st) ? "退款到账，订单已取消" : "退款到账");
        timeline.sort((x, y) -> str(x.get("time")).compareTo(str(y.get("time"))));
        m.put("timeline", timeline);
        m.put("refundFen", o.get("refundFen"));
        // 已退金额以支付单为准（客人申请 / 驳回会改写订单上的 refund_fen，不能拿来累计）
        Map<String, Object> payRow = str(o.get("payNo")).isEmpty() ? null
                : db.one("select amount, refund_amount, status from kg_pay_order where pay_no = :n and del_flag = '0'", p("n", o.get("payNo")));
        long paid = payRow == null ? 0 : fen(payRow.get("amount"));
        long refunded = payRow == null ? 0 : fen(payRow.get("refundAmount"));
        String payStatus = payRow == null ? "" : str(payRow.get("status"));
        m.put("refundedFen", refunded);
        m.put("refundPendingFen", pendingFen);
        long left = Math.max(0, paid - refunded);
        // 角色切换后退款只走平台后台，这里不再显示退款按钮（接口那头 needManager 同样拒绝）
        boolean manager = !com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() && StaffRole.MANAGER.equals(c.role) && !support.autoManager(c);
        boolean mine = KgJdbc.asLong(o.get("appUserId")) != null && KgJdbc.asLong(o.get("appUserId")).equals(c.uid());
        boolean open = KgStayOrderService.STAFF_REFUND_STATUS.contains(st) && ("paid".equals(payStatus) || "part_refunded".equals(payStatus)) && left > 0;
        m.put("canRefund", manager && open && !mine && pendingFen == 0);
        m.put("refundMaxFen", left);
        m.put("refundPaidFen", paid);
        m.put("refundHint", !manager ? null : (!open ? null : (mine ? "这是你自己下的订单，请让本店其他主理人退款"
                : (pendingFen > 0 ? "上一笔退款还在等微信确认，到账后才能再退" : refundHint(o, st, left)))));
        m.put("canNote", StaffPerms.allows(c.role, "rooms", StaffPerms.EDIT));
        return m;
    }

    /**
     * 门店退款还在等微信时，时间线上这一条后面的说明（2026-10-13）：微信不受理（如商户号余额不足）时把原因翻成白话、
     * 告诉主理人系统会每分钟自动重试；退款异常要到商户平台处理。之前一律写「等微信确认到账」，退不出去也看不出来。
     * 同一张支付单同一时间只会有一笔在途的微信退款，取最新那笔
     */
    String refundWaitText(Object payNo)
    {
        Map<String, Object> wx = str(payNo).isEmpty() ? null : db.one("select status, last_error from kg_pay_refund where pay_no = :n and "
                + KgPayService.ACTIVE_REFUND_SQL + " order by refund_seq desc limit 1", p("n", payNo));
        if (wx != null && "abnormal".equals(str(wx.get("status"))))
        {
            return "　微信退款异常（常见原因是客人银行卡已注销），需在微信支付商户平台的退款管理里处理";
        }
        String error = wx == null ? "" : str(wx.get("lastError"));
        // 对账出错时记下的是程序异常类名（不是微信错误码），不能写成「微信提示」
        return "　等微信确认到账" + (error.isEmpty() ? "" : (error.matches("[A-Z0-9_]+") ? "；微信提示：" : "；") + KgPayService.refundErrorText(error) + "，系统每分钟自动重试");
    }

    /** 退款卡片上的说明：告诉主理人退了之后订单会怎样 */
    private static String refundHint(Map<String, Object> o, String st, long left)
    {
        String all = "¥" + KgStayBrowseService.yuan(left);
        if ("refund_pending".equals(st))
        {
            return "客人已申请退款（按取消政策 ¥" + KgStayBrowseService.yuan(fen(o.get("refundFen"))) + "）。在这里退款＝按你填的金额同意这次申请，订单会取消"
                    + (o.get("closeTime") != null ? "；这笔是订单关闭后才到账的钱，只能全额退 " + all : "");
        }
        if ("confirmed".equals(st))
        {
            return "全额退 " + all + " 会取消订单、空出房间；只退一部分订单照常入住";
        }
        return "退款后订单状态不变，最多还能退 " + all;
    }

    /**
     * 门店主动退款（只有本店主理人，金额自己填，不按取消政策）。控制器先用 needManager 把关并记失败日志，这里再把一道：
     * 单元测试与以后别的入口都不能绕过。详见 KgStayOrderService.staffRefund。
     */
    public Map<String, Object> refund(StaffSupport.Ctx c, String orderNo, Object amountFen, String reason, String idemKey)
    {
        if (!StaffRole.MANAGER.equals(c.role))
        {
            throw new ServiceException("这项操作只有本店主理人可以做（当前角色：" + StaffRole.label(c.role) + "）", HttpStatus.FORBIDDEN);
        }
        order(c, orderNo);
        long amount = parseFen(amountFen);
        Map<String, Object> r = stayOrders.staffRefund(c.storeId, orderNo, amount, reason, idemKey, c.uid(), c.name());
        support.op(c, "stay_staff_refund", "门店退款", "fund", orderNo + " ¥" + KgStayBrowseService.yuan(amount) + "：" + StaffSupport.cut(str(reason).trim(), 100), true, null);
        Map<String, Object> m = detail(c, orderNo);
        m.put("message", r.get("message"));
        m.put("refund", r);
        return m;
    }

    /** 金额（分）：只收正整数；小数、负数、字母一律当作填错 */
    static long parseFen(Object v)
    {
        String s = v == null ? "" : String.valueOf(v).trim();
        if (!s.matches("[0-9]{1,10}"))
        {
            throw new ServiceException("请填写正确的退款金额", 422);
        }
        return Long.parseLong(s);
    }

    private static void addStep(List<Map<String, Object>> list, Object time, String label)
    {
        String t = str(time);
        if (!t.isEmpty())
        {
            list.add(p("time", t.length() >= 16 ? t.substring(0, 16) : t, "label", label));
        }
    }

    // ------------------------------------------------------------ 排房

    /** 订单所属房型的全部房间 / 床位，标出哪些在这张订单的日子里是空的 */
    public Map<String, Object> unitOptions(StaffSupport.Ctx c, String orderNo)
    {
        support.need(c, "rooms", StaffPerms.VIEW, "stay_pms_view", "查看旅居订单");
        Map<String, Object> o = order(c, orderNo);
        Long orderId = KgJdbc.asLong(o.get("orderId"));
        LocalDate[] range = arrangeRange(o);
        Set<Long> mine = new HashSet<Long>();
        for (Map<String, Object> a : assigns(orderId))
        {
            mine.add(KgJdbc.asLong(a.get("unitId")));
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> u : db.list("select unit_id, unit_name, status from kg_stay_unit where room_type_id = :r and del_flag = '0' order by sort, unit_id",
                p("r", o.get("roomTypeId"))))
        {
            Long id = KgJdbc.asLong(u.get("unitId"));
            Map<String, Object> busy = range == null ? null : conflict(id, orderId, range[0], range[1]);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("unitId", id);
            m.put("unitName", u.get("unitName"));
            m.put("disabled", "1".equals(str(u.get("status"))));
            m.put("mine", mine.contains(id));
            m.put("free", busy == null && !"1".equals(str(u.get("status"))));
            m.put("busyText", busy == null ? null : MaskUtils.name(str(busy.get("guestName"))) + " " + md(busy.get("startDate")) + "→" + md(busy.get("endDate")));
            out.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("orderNo", orderNo);
        m.put("typeName", o.get("typeName"));
        m.put("unitLabel", str(o.get("unitLabel")).isEmpty() ? "间" : o.get("unitLabel"));
        m.put("need", o.get("units"));
        m.put("from", range == null ? null : range[0].toString());
        m.put("to", range == null ? null : range[1].toString());
        m.put("units", out);
        m.put("hint", out.isEmpty() ? "这个房型还没有设置房间 / 床位，请先在「房间床位设置」里添加" : null);
        return m;
    }

    /** 这张订单要排的晚上：没入住从入住日开始；已入住的换房从今天开始（之前住过的晚上保留原房间） */
    LocalDate[] arrangeRange(Map<String, Object> o)
    {
        LocalDate in = date(o.get("checkIn")), out = date(o.get("checkOut"));
        if (in == null || out == null)
        {
            return null;
        }
        LocalDate from = "checked_in".equals(str(o.get("status"))) ? maxDate(in, today()) : in;
        return from.isBefore(out) ? new LocalDate[] { from, out } : null;
    }

    /** 某房间 / 床位在 [from, to) 是否已排给别的订单；有就返回那条 */
    Map<String, Object> conflict(Long unitId, Long orderId, LocalDate from, LocalDate to)
    {
        return db.one("select a.start_date, a.end_date, o.order_no, o.guest_name from kg_stay_assign a join kg_stay_order o on o.order_id = a.order_id "
                + "where a.unit_id = :u and a.status = 'active' and a.order_id <> :o and o.status in (:occ) and a.start_date < :to and a.end_date > :from "
                + "order by a.start_date limit 1", p("u", unitId, "o", orderId, "occ", OCCUPY, "from", from.toString(), "to", to.toString()));
    }

    /** 排房 / 换房：把订单排到选中的房间 / 床位（数量不超过订单的间数 / 床位数），替换原来的排房 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> assign(StaffSupport.Ctx c, String orderNo, List<Long> unitIds)
    {
        support.need(c, "rooms", StaffPerms.EDIT, "stay_assign", "排房");
        Map<String, Object> o = order(c, orderNo);
        String st = str(o.get("status"));
        if (!ARRANGE.contains(st))
        {
            throw new ServiceException("订单" + KgStayOrderService.statusLabel(st) + "，不能排房", 409);
        }
        LocalDate[] range = arrangeRange(o);
        if (range == null)
        {
            throw new ServiceException("这张订单已经没有需要排房的晚上了", 409);
        }
        List<Long> ids = new ArrayList<Long>(new LinkedHashSet<Long>(unitIds == null ? new ArrayList<Long>() : unitIds));
        long need = fen(o.get("units"));
        if (ids.isEmpty())
        {
            throw new ServiceException("请选择房间 / 床位", 422);
        }
        if (ids.size() > need)
        {
            throw new ServiceException("这张订单只订了 " + need + (isBed(o) ? " 个床位" : " 间") + "，最多选 " + need + " 个", 422);
        }
        Long orderId = KgJdbc.asLong(o.get("orderId"));
        // 锁住要排的房间行，同一房间的并发排房排队进行，再查是否和别的订单重叠
        List<Map<String, Object>> units = db.list("select unit_id, unit_name, room_type_id, status, del_flag from kg_stay_unit where unit_id in (:ids) for update",
                p("ids", ids));
        if (units.size() != ids.size())
        {
            throw new ServiceException("房间 / 床位不存在，请刷新后重试", 404);
        }
        for (Map<String, Object> u : units)
        {
            if (!"0".equals(str(u.get("delFlag"))) || !str(u.get("roomTypeId")).equals(str(o.get("roomTypeId"))))
            {
                throw new ServiceException("「" + u.get("unitName") + "」不属于这张订单的房型", 422);
            }
            if ("1".equals(str(u.get("status"))))
            {
                throw new ServiceException("「" + u.get("unitName") + "」已停用，不能排房", 409);
            }
            Map<String, Object> busy = conflict(KgJdbc.asLong(u.get("unitId")), orderId, range[0], range[1]);
            if (busy != null)
            {
                throw new ServiceException("「" + u.get("unitName") + "」在 " + md(busy.get("startDate")) + "–" + md(busy.get("endDate")) + " 已排给 "
                        + MaskUtils.name(str(busy.get("guestName"))) + "（订单尾号 " + tail(busy.get("orderNo")) + "）", 409);
            }
        }
        releaseFrom(orderId, range[0]);
        for (Long id : ids)
        {
            insertAssign(c, o, id, range[0], range[1]);
        }
        support.op(c, "stay_assign", "排房", "general", orderNo + " → " + names(units), true, null);
        return detail(c, orderNo);
    }

    private static boolean isBed(Map<String, Object> o)
    {
        return "床位".equals(str(o.get("unitLabel")));
    }

    private static String tail(Object no)
    {
        String s = str(no);
        return s.length() > 4 ? s.substring(s.length() - 4) : s;
    }

    private static String names(List<Map<String, Object>> units)
    {
        List<String> n = new ArrayList<String>();
        for (Map<String, Object> u : units)
        {
            n.add(str(u.get("unitName")));
        }
        return String.join("、", n);
    }

    private void insertAssign(StaffSupport.Ctx c, Map<String, Object> o, Long unitId, LocalDate from, LocalDate to)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("order_id", o.get("orderId"));
        row.put("order_no", o.get("orderNo"));
        row.put("store_id", o.get("storeId"));
        row.put("stay_id", o.get("stayId"));
        row.put("room_type_id", o.get("roomTypeId"));
        row.put("unit_id", unitId);
        row.put("start_date", from.toString());
        row.put("end_date", to.toString());
        row.put("status", "active");
        row.put("create_by", StaffSupport.cut("staff:" + c.uid(), 64));
        row.put("create_time", new Date());
        db.insert("kg_stay_assign", row);
    }

    /** 撤掉从 from 起的排房：from 之前已经住过的晚上保留（end_date 截到 from），完全在 from 之后的作废 */
    void releaseFrom(Long orderId, LocalDate from)
    {
        db.update("update kg_stay_assign set status = 'released', update_time = now() where order_id = :o and status = 'active' and start_date >= :f",
                p("o", orderId, "f", from.toString()));
        db.update("update kg_stay_assign set end_date = :f, update_time = now() where order_id = :o and status = 'active' and start_date < :f and end_date > :f",
                p("o", orderId, "f", from.toString()));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> unassign(StaffSupport.Ctx c, String orderNo)
    {
        support.need(c, "rooms", StaffPerms.EDIT, "stay_assign", "取消排房");
        Map<String, Object> o = order(c, orderNo);
        LocalDate[] range = arrangeRange(o);
        if (range != null)
        {
            releaseFrom(KgJdbc.asLong(o.get("orderId")), range[0]);
        }
        support.op(c, "stay_assign", "取消排房", "general", orderNo, true, null);
        return detail(c, orderNo);
    }

    /**
     * 一键排房：把房源里还没排满的订单（入住日在 [from, from+days) 内或正在住）按入住先后，
     * 依次排到这一晚段都空着的房间 / 床位上（按房间排序取前几个）。排不下的如实列出原因。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> autoAssign(StaffSupport.Ctx c, Long stayId, int days)
    {
        support.need(c, "rooms", StaffPerms.EDIT, "stay_assign", "一键排房");
        int span = Math.max(1, Math.min(days, 90));
        LocalDate from = today();
        db.list("select unit_id from kg_stay_unit where stay_id = :s for update", p("s", stayId));
        List<Map<String, Object>> orders = db.list("select o.*, t.type_name, t.unit_label from kg_stay_order o left join kg_stay_room_type t on t.room_type_id = o.room_type_id "
                + "where o.stay_id = :s and o.store_id = :st and o.del_flag = '0' and o.status in (:arr) and o.check_out > :f and o.check_in < :e "
                + "order by o.check_in, o.order_id", p("s", stayId, "st", c.storeId, "arr", ARRANGE, "f", from.toString(), "e", from.plusDays(span).toString()));
        int done = 0, beds = 0;
        List<String> failed = new ArrayList<String>();
        Map<Long, List<Map<String, Object>>> unitsByType = new HashMap<Long, List<Map<String, Object>>>();
        for (Map<String, Object> o : orders)
        {
            Long orderId = KgJdbc.asLong(o.get("orderId"));
            List<Map<String, Object>> current = assigns(orderId);
            int miss = missing(o, current);
            if (miss <= 0)
            {
                continue;
            }
            LocalDate[] range = arrangeRange(o);
            if (range == null)
            {
                continue;
            }
            Long typeId = KgJdbc.asLong(o.get("roomTypeId"));
            List<Map<String, Object>> units = unitsByType.get(typeId);
            if (units == null)
            {
                units = db.list("select unit_id, unit_name from kg_stay_unit where room_type_id = :r and del_flag = '0' and status = '0' order by sort, unit_id",
                        p("r", typeId));
                unitsByType.put(typeId, units);
            }
            Set<Long> taken = new HashSet<Long>();
            for (Map<String, Object> a : current)
            {
                taken.add(KgJdbc.asLong(a.get("unitId")));
            }
            List<Long> picked = new ArrayList<Long>();
            for (Map<String, Object> u : units)
            {
                Long id = KgJdbc.asLong(u.get("unitId"));
                if (picked.size() >= miss)
                {
                    break;
                }
                if (!taken.contains(id) && conflict(id, orderId, range[0], range[1]) == null)
                {
                    picked.add(id);
                }
            }
            if (picked.size() < miss)
            {
                String who = str(o.get("guestName")).isEmpty() ? "订单尾号 " + tail(o.get("orderNo")) : MaskUtils.name(str(o.get("guestName")));
                failed.add(who + "（" + o.get("typeName") + " " + md(o.get("checkIn")) + "→" + md(o.get("checkOut")) + "）："
                        + (units.isEmpty() ? "房型还没有设置房间 / 床位" : "空着的房间 / 床位不够"));
                continue;
            }
            for (Long id : picked)
            {
                insertAssign(c, o, id, range[0], range[1]);
            }
            done++;
            beds += picked.size();
        }
        support.op(c, "stay_assign", "一键排房", "general", "排好 " + done + " 单，" + beds + " 个房间/床位", true, null);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("assignedOrders", done);
        m.put("assignedUnits", beds);
        m.put("failed", failed);
        m.put("message", done == 0 && failed.isEmpty() ? "没有需要排房的订单" : "已排好 " + done + " 单" + (failed.isEmpty() ? "" : "，" + failed.size() + " 单没排上"));
        return m;
    }

    // ------------------------------------------------------------ 入住 / 退房 / 备注

    /** 前台办理入住（客人当面核对身份；和扫核销码效果一样）：只能在入住日到退房时间之间办 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkIn(StaffSupport.Ctx c, String orderNo)
    {
        support.need(c, "rooms", StaffPerms.EDIT, "stay_checkin", "办理入住");
        Map<String, Object> o = order(c, orderNo);
        if (!"confirmed".equals(str(o.get("status"))))
        {
            throw new ServiceException("订单" + KgStayOrderService.statusLabel(str(o.get("status"))) + "，不能办理入住", 409);
        }
        LocalDate in = date(o.get("checkIn"));
        LocalDateTime out = checkoutAt(o.get("checkOut"), o.get("checkoutTime"));
        if (in == null || out == null || today().isBefore(in) || !LocalDateTime.now().isBefore(out))
        {
            throw new ServiceException("不在入住有效期内（" + day(o.get("checkIn")) + " 至 " + day(o.get("checkOut")) + " 退房前）", 409);
        }
        String by = StaffSupport.cut(c.name() + "（" + c.uid() + "）· 前台办理", 64);
        if(memberWallet != null)memberWallet.consumeStay(KgJdbc.asLong(o.get("orderId")));
        if (db.update("update kg_stay_order set status = 'checked_in', verify_time = now(), verified_by = :b, update_time = now() "
                + "where order_id = :id and status = 'confirmed'", p("id", o.get("orderId"), "b", by)) == 0)
        {
            throw new ServiceException("订单状态刚刚变化，请刷新后重试", 409);
        }
        stayOrders.notifyCheckedIn(o);
        support.op(c, "stay_checkin", "办理入住", "general", orderNo + " " + MaskUtils.name(str(o.get("guestName"))), true, null);
        return detail(c, orderNo);
    }

    /**
     * 前台办理退房：已入住 → 已完成。提前退房时，从今天起没住的晚上把房量还回库存（可以重新卖），
     * 排房也截到今天。房费不涉及退款（需要退钱的走退款流程）；已付款没用、开始时间在退房之后的增值服务
     * 转退款审核（入住单完成后服务核销不了），返回的提示里告诉门店有几项。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkOut(StaffSupport.Ctx c, String orderNo)
    {
        support.need(c, "rooms", StaffPerms.EDIT, "stay_checkout", "办理退房");
        Map<String, Object> o = order(c, orderNo);
        if (!"checked_in".equals(str(o.get("status"))))
        {
            throw new ServiceException("订单" + KgStayOrderService.statusLabel(str(o.get("status"))) + "，不能办理退房", 409);
        }
        String by = StaffSupport.cut(c.name() + "（" + c.uid() + "）", 64);
        if (db.update("update kg_stay_order set status = 'completed', checked_out_time = now(), checked_out_by = :b, update_time = now() "
                + "where order_id = :id and status = 'checked_in'", p("id", o.get("orderId"), "b", by)) == 0)
        {
            throw new ServiceException("订单状态刚刚变化，请刷新后重试", 409);
        }
        db.update("update kg_stay set completed_orders = completed_orders + 1 where stay_id = :s", p("s", o.get("stayId")));
        // 2026-10-17 消费积分：按实付减已退款，每 1 元得几分（退房提交后另开事务发，出错只记日志，不影响退房）
        if (pointRewards != null)
        {
            pointRewards.stayCompleted(KgJdbc.asLong(o.get("orderId")));
        }
        LocalDate t = today(), end = date(o.get("checkOut"));
        boolean early = end != null && t.isBefore(end);
        if (early)
        {
            db.update("update kg_stay_calendar set occupied = greatest(0, occupied - :u), update_time = now() where room_type_id = :r "
                    + "and stay_date >= :a and stay_date < :b", p("u", fen(o.get("units")), "r", o.get("roomTypeId"), "a", t.toString(), "b", end.toString()));
            releaseFrom(KgJdbc.asLong(o.get("orderId")), t);
        }
        String how = early ? "提前退房" : "退房";
        int services = stayOrders.refundServicesOnStayEnd(o, "客人已" + how + "，入住订单已结束", LocalDateTime.now(),
                "入住订单 " + orderNo + " 已办理" + how, "入住订单 " + orderNo + " 已" + how);
        stayOrders.notifyStayDone(o, how);
        support.op(c, "stay_checkout", early ? "提前退房" : "办理退房", "general", orderNo + " " + MaskUtils.name(str(o.get("guestName"))), true, null);
        Map<String, Object> m = detail(c, orderNo);
        m.put("message", (early ? "已办理退房，剩下没住的晚上已还回房态，可以重新预订" : "已办理退房")
                + (services > 0 ? "；有 " + services + " 项增值服务已转退款审核" : ""));
        m.put("servicesToRefund", services);
        return m;
    }

    public Map<String, Object> note(StaffSupport.Ctx c, String orderNo, String note)
    {
        support.need(c, "rooms", StaffPerms.EDIT, "stay_note", "订单备注");
        Map<String, Object> o = order(c, orderNo);
        String v = note == null ? "" : note.trim();
        if (v.length() > 255)
        {
            throw new ServiceException("备注最多 255 字", 422);
        }
        db.update("update kg_stay_order set staff_note = :n, update_time = now() where order_id = :id", p("id", o.get("orderId"), "n", v.isEmpty() ? null : v));
        return detail(c, orderNo);
    }

    // ------------------------------------------------------------ 房态图

    /** 房态图：一屏 days 天（默认 7），每个房间 / 床位一行，每晚一格；另给出每个房型每晚的库存、已订和待排数 */
    public Map<String, Object> board(StaffSupport.Ctx c, Long stayId, String start, int days)
    {
        support.need(c, "rooms", StaffPerms.VIEW, "stay_pms_view", "查看房态");
        Map<String, Object> stay = db.must("select stay_id, stay_name from kg_stay where stay_id = :s and store_id = :st and del_flag = '0'",
                p("s", stayId, "st", c.storeId), "房源不存在或不属于本店");
        int span = Math.max(1, Math.min(days <= 0 ? 7 : days, 31));
        LocalDate from = KgJdbc.isDate(start) ? LocalDate.parse(start) : today();
        LocalDate to = from.plusDays(span);
        List<LocalDate> dates = new ArrayList<LocalDate>();
        List<Map<String, Object>> cols = new ArrayList<Map<String, Object>>();
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1))
        {
            dates.add(d);
            cols.add(p("date", d.toString(), "label", d.getMonthValue() + "/" + d.getDayOfMonth(), "week", WEEK[d.getDayOfWeek().getValue() - 1],
                    "today", d.equals(today())));
        }
        Map<String, Object> ps = p("s", stayId, "f", from.toString(), "t", to.toString());
        List<Map<String, Object>> types = db.list("select room_type_id, type_name, unit_label, stock, status from kg_stay_room_type where stay_id = :s and del_flag = '0' "
                + "order by sort, room_type_id", ps);
        Map<String, Map<String, Object>> cal = new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : db.list("select room_type_id, stay_date, stock, occupied, closed from kg_stay_calendar where stay_id = :s and stay_date >= :f and stay_date < :t", ps))
        {
            cal.put(str(r.get("roomTypeId")) + "|" + day(r.get("stayDate")), r);
        }
        List<Map<String, Object>> asg = db.list("select a.assign_id, a.unit_id, a.start_date, a.end_date, o.order_no, o.guest_name, o.status from kg_stay_assign a "
                + "join kg_stay_order o on o.order_id = a.order_id where a.stay_id = :s and a.status = 'active' and o.status in (:occ) and a.start_date < :t and a.end_date > :f",
                p("s", stayId, "f", from.toString(), "t", to.toString(), "occ", OCCUPY));
        Map<String, Map<String, Object>> cell = new HashMap<String, Map<String, Object>>();
        Map<String, Integer> assignedByTypeDay = new HashMap<String, Integer>();
        Map<Long, Long> unitType = new HashMap<Long, Long>();
        List<Map<String, Object>> allUnits = db.list("select unit_id, room_type_id, unit_name, status from kg_stay_unit where stay_id = :s and del_flag = '0' order by sort, unit_id", ps);
        for (Map<String, Object> u : allUnits)
        {
            unitType.put(KgJdbc.asLong(u.get("unitId")), KgJdbc.asLong(u.get("roomTypeId")));
        }
        for (Map<String, Object> a : asg)
        {
            LocalDate s = date(a.get("startDate")), e = date(a.get("endDate"));
            Long unitId = KgJdbc.asLong(a.get("unitId"));
            for (LocalDate d : dates)
            {
                if (s != null && e != null && !d.isBefore(s) && d.isBefore(e))
                {
                    cell.put(unitId + "|" + d, a);
                    String k = unitType.get(unitId) + "|" + d;
                    assignedByTypeDay.put(k, assignedByTypeDay.getOrDefault(k, 0) + 1);
                }
            }
        }
        // 每个房型每晚还有多少（间 / 床位）没排：按需要排房的订单逐晚累计
        Map<String, Integer> wantByTypeDay = new HashMap<String, Integer>();
        for (Map<String, Object> o : db.list("select order_id, room_type_id, units, check_in, check_out from kg_stay_order where stay_id = :s and del_flag = '0' "
                + "and status in (:arr) and check_in < :t and check_out > :f", p("s", stayId, "f", from.toString(), "t", to.toString(), "arr", ARRANGE)))
        {
            LocalDate s = date(o.get("checkIn")), e = date(o.get("checkOut"));
            for (LocalDate d : dates)
            {
                if (s != null && e != null && !d.isBefore(s) && d.isBefore(e))
                {
                    String k = str(o.get("roomTypeId")) + "|" + d;
                    wantByTypeDay.put(k, wantByTypeDay.getOrDefault(k, 0) + (int) fen(o.get("units")));
                }
            }
        }
        List<Map<String, Object>> outTypes = new ArrayList<Map<String, Object>>();
        int waitingTotal = 0;
        for (Map<String, Object> t : types)
        {
            Long typeId = KgJdbc.asLong(t.get("roomTypeId"));
            List<Map<String, Object>> perDate = new ArrayList<Map<String, Object>>();
            for (LocalDate d : dates)
            {
                Map<String, Object> r = cal.get(typeId + "|" + d);
                long stock = r != null && r.get("stock") != null ? fen(r.get("stock")) : fen(t.get("stock"));
                boolean closed = r != null && "1".equals(str(r.get("closed")));
                long sold = r == null ? 0 : fen(r.get("occupied"));
                int want = wantByTypeDay.getOrDefault(typeId + "|" + d, 0);
                int assigned = assignedByTypeDay.getOrDefault(typeId + "|" + d, 0);
                int waiting = Math.max(0, want - assigned);
                if (!d.isBefore(today()))
                {
                    waitingTotal += waiting;
                }
                perDate.add(p("date", d.toString(), "stock", stock, "sold", sold, "left", Math.max(0, stock - sold), "closed", closed, "waiting", waiting));
            }
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> u : allUnits)
            {
                if (!typeId.equals(KgJdbc.asLong(u.get("roomTypeId"))))
                {
                    continue;
                }
                Long unitId = KgJdbc.asLong(u.get("unitId"));
                boolean disabled = "1".equals(str(u.get("status")));
                List<Map<String, Object>> cells = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < dates.size(); i++)
                {
                    LocalDate d = dates.get(i);
                    Map<String, Object> a = cell.get(unitId + "|" + d);
                    Map<String, Object> x = new LinkedHashMap<String, Object>();
                    x.put("date", d.toString());
                    if (a != null)
                    {
                        String st = str(a.get("status"));
                        x.put("state", "checked_in".equals(st) ? "in" : ("completed".equals(st) ? "done" : (st.startsWith("refund") ? "refund" : "booked")));
                        x.put("orderNo", a.get("orderNo"));
                        x.put("guest", MaskUtils.name(str(a.get("guestName"))));
                        // 同一张订单连续的格子只在第一格写名字
                        Map<String, Object> prev = i == 0 ? null : cell.get(unitId + "|" + dates.get(i - 1));
                        x.put("head", prev == null || !str(prev.get("assignId")).equals(str(a.get("assignId"))));
                    }
                    else
                    {
                        Map<String, Object> r = cal.get(typeId + "|" + d);
                        x.put("state", disabled ? "disabled" : (r != null && "1".equals(str(r.get("closed"))) ? "closed" : "free"));
                    }
                    cells.add(x);
                }
                rows.add(p("unitId", unitId, "unitName", u.get("unitName"), "disabled", disabled, "cells", cells));
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("roomTypeId", typeId);
            m.put("typeName", t.get("typeName"));
            m.put("unitLabel", t.get("unitLabel"));
            m.put("offline", "1".equals(str(t.get("status"))));
            m.put("perDate", perDate);
            m.put("units", rows);
            m.put("unitHint", rows.isEmpty() ? "还没有设置" + ("床位".equals(str(t.get("unitLabel"))) ? "床位" : "房间") + "，排不了房" : null);
            outTypes.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("stayId", stay.get("stayId"));
        m.put("stayName", stay.get("stayName"));
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("dates", cols);
        m.put("types", outTypes);
        m.put("waiting", waitingTotal);
        return m;
    }

    // ------------------------------------------------------------ 房间 / 床位设置

    public List<Map<String, Object>> units(StaffSupport.Ctx c, Long stayId)
    {
        support.need(c, "rooms", StaffPerms.VIEW, "stay_pms_view", "查看房间床位");
        db.must("select stay_id from kg_stay where stay_id = :s and store_id = :st and del_flag = '0'", p("s", stayId, "st", c.storeId), "房源不存在或不属于本店");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> t : db.list("select room_type_id, type_name, unit_label, stock, room_kind from kg_stay_room_type where stay_id = :s and del_flag = '0' "
                + "order by sort, room_type_id", p("s", stayId)))
        {
            List<Map<String, Object>> us = db.list("select unit_id, unit_name, status, sort, remark from kg_stay_unit where room_type_id = :r and del_flag = '0' order by sort, unit_id",
                    p("r", t.get("roomTypeId")));
            long active = 0;
            for (Map<String, Object> u : us)
            {
                if (!"1".equals(str(u.get("status"))))
                {
                    active++;
                }
            }
            long stock = fen(t.get("stock"));
            String label = "床位".equals(str(t.get("unitLabel"))) ? "床位" : "房间";
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("roomTypeId", t.get("roomTypeId"));
            m.put("typeName", t.get("typeName"));
            m.put("unitLabel", label);
            m.put("stock", stock);
            m.put("units", us);
            m.put("hint", active < stock ? "每晚可卖 " + stock + " 个" + label + "，已设置可用的 " + active + " 个，还差 " + (stock - active) + " 个时会有订单排不上" : null);
            out.add(m);
        }
        return out;
    }

    /** 批量添加房间 / 床位（名称 1–32 字，同一房型内不重复，每个房型最多 200 个） */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> addUnits(StaffSupport.Ctx c, Long roomTypeId, List<String> names)
    {
        needSetup(c, "设置房间床位");
        Map<String, Object> t = typeOf(c, roomTypeId);
        List<String> clean = new ArrayList<String>();
        for (String n : names == null ? new ArrayList<String>() : names)
        {
            String v = n == null ? "" : n.trim();
            if (v.isEmpty())
            {
                continue;
            }
            if (v.length() > 32)
            {
                throw new ServiceException("名称最多 32 个字：" + v, 422);
            }
            if (!clean.contains(v))
            {
                clean.add(v);
            }
        }
        if (clean.isEmpty())
        {
            throw new ServiceException("请填写房间号 / 床位号", 422);
        }
        db.list("select room_type_id from kg_stay_room_type where room_type_id = :r for update", p("r", roomTypeId));
        List<Map<String, Object>> exist = db.list("select unit_name from kg_stay_unit where room_type_id = :r and del_flag = '0'", p("r", roomTypeId));
        Set<String> have = new HashSet<String>();
        for (Map<String, Object> e : exist)
        {
            have.add(str(e.get("unitName")));
        }
        if (exist.size() + clean.size() > 200)
        {
            throw new ServiceException("每个房型最多 200 个房间 / 床位", 422);
        }
        long sort = db.count("select coalesce(max(sort), 0) from kg_stay_unit where room_type_id = :r and del_flag = '0'", p("r", roomTypeId));
        for (String n : clean)
        {
            if (have.contains(n))
            {
                throw new ServiceException("「" + n + "」已经存在", 409);
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("store_id", c.storeId);
            row.put("stay_id", t.get("stayId"));
            row.put("room_type_id", roomTypeId);
            row.put("unit_name", n);
            row.put("sort", ++sort);
            row.put("status", "0");
            row.put("create_by", StaffSupport.cut("staff:" + c.uid(), 64));
            row.put("create_time", new Date());
            row.put("del_flag", "0");
            db.insert("kg_stay_unit", row);
        }
        support.op(c, "stay_unit_edit", "添加房间床位", "general", t.get("typeName") + "：" + String.join("、", clean), true, null);
        return units(c, KgJdbc.asLong(t.get("stayId")));
    }

    private Map<String, Object> typeOf(StaffSupport.Ctx c, Long roomTypeId)
    {
        return db.must("select t.room_type_id, t.stay_id, t.type_name from kg_stay_room_type t join kg_stay s on s.stay_id = t.stay_id "
                + "where t.room_type_id = :r and s.store_id = :st and t.del_flag = '0'", p("r", roomTypeId, "st", c.storeId), "房型不存在或不属于本店");
    }

    private Map<String, Object> unitOf(StaffSupport.Ctx c, Long unitId)
    {
        return db.must("select * from kg_stay_unit where unit_id = :u and store_id = :st and del_flag = '0'", p("u", unitId, "st", c.storeId), "房间 / 床位不存在");
    }

    /** 改名、停用 / 启用 */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> updateUnit(StaffSupport.Ctx c, Long unitId, String name, String status)
    {
        needSetup(c, "设置房间床位");
        Map<String, Object> u = unitOf(c, unitId);
        if (name != null)
        {
            String v = name.trim();
            if (v.isEmpty() || v.length() > 32)
            {
                throw new ServiceException("名称为 1–32 个字", 422);
            }
            if (db.count("select count(*) from kg_stay_unit where room_type_id = :r and del_flag = '0' and unit_name = :n and unit_id <> :u",
                    p("r", u.get("roomTypeId"), "n", v, "u", unitId)) > 0)
            {
                throw new ServiceException("「" + v + "」已经存在", 409);
            }
            db.update("update kg_stay_unit set unit_name = :n, update_by = :b, update_time = now() where unit_id = :u",
                    p("n", v, "u", unitId, "b", StaffSupport.cut("staff:" + c.uid(), 64)));
        }
        if (status != null)
        {
            if (!"0".equals(status) && !"1".equals(status))
            {
                throw new ServiceException("状态只能是可用或停用", 422);
            }
            if ("1".equals(status) && futureAssigned(unitId))
            {
                throw new ServiceException("「" + u.get("unitName") + "」今天以后还排着客人，先给这些订单换房再停用", 409);
            }
            db.update("update kg_stay_unit set status = :st, update_by = :b, update_time = now() where unit_id = :u",
                    p("st", status, "u", unitId, "b", StaffSupport.cut("staff:" + c.uid(), 64)));
        }
        support.op(c, "stay_unit_edit", "修改房间床位", "general", str(u.get("unitName")), true, null);
        return units(c, KgJdbc.asLong(u.get("stayId")));
    }

    boolean futureAssigned(Long unitId)
    {
        return db.count("select count(*) from kg_stay_assign a join kg_stay_order o on o.order_id = a.order_id where a.unit_id = :u and a.status = 'active' "
                + "and o.status in (:occ) and a.end_date > :t", p("u", unitId, "occ", OCCUPY, "t", today().toString())) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> removeUnit(StaffSupport.Ctx c, Long unitId)
    {
        needSetup(c, "删除房间床位");
        Map<String, Object> u = unitOf(c, unitId);
        if (futureAssigned(unitId))
        {
            throw new ServiceException("「" + u.get("unitName") + "」今天以后还排着客人，先给这些订单换房再删除", 409);
        }
        db.update("update kg_stay_unit set del_flag = '1', update_by = :b, update_time = now() where unit_id = :u",
                p("u", unitId, "b", StaffSupport.cut("staff:" + c.uid(), 64)));
        support.op(c, "stay_unit_edit", "删除房间床位", "general", str(u.get("unitName")), true, null);
        return units(c, KgJdbc.asLong(u.get("stayId")));
    }
}
