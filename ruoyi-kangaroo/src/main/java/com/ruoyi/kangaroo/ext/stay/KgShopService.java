package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.D;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.date;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.fen;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.split;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.str;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 增值服务（入住期间预订的到店服务）：按类目白名单开放（字典 kg_shop_category 停用 = 暂未开放），
 * 必须挂在本人进行中的入住单上；一次结算一项服务。支付、退款审批、核销与旅居订单同一套规则。
 */
@Service
public class KgShopService implements KgPayListener
{
    private static final Logger log = LoggerFactory.getLogger(KgShopService.class);
    public static final String BIZ = "service_order";

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgStayOrderService stayOrders;

    @Autowired
    private KgPayService pay;

    @Autowired
    private StaffSupport support;

    @Lazy
    @Autowired
    private KgShopService self;

    public static String statusLabel(String st)
    {
        switch (st == null ? "" : st)
        {
            case "pending_payment": return "待支付";
            case "unused": return "待使用";
            case "used": return "已核销";
            case "refund_pending": return "退款审核中";
            case "refunding": return "退款处理中";
            case "refunded": return "已退款";
            case "closed": return "已关闭";
            default: return st;
        }
    }

    public List<Map<String, Object>> categories()
    {
        List<Map<String, Object>> rows = db.list("select dict_value code, dict_label label, status from sys_dict_data where dict_type = 'kg_shop_category' "
                + "order by dict_sort", p());
        for (Map<String, Object> r : rows)
        {
            r.put("open", "0".equals(str(r.remove("status"))));
        }
        return rows;
    }

    private boolean categoryOpen(String code)
    {
        return db.count("select count(*) from sys_dict_data where dict_type = 'kg_shop_category' and dict_value = :c and status = '0'", p("c", code)) > 0;
    }

    /** 本人进行中的入住单（指定单号时校验归属与状态） */
    Map<String, Object> stayOrder(Long userId, String stayOrderNo)
    {
        if (userId == null)
        {
            return null;
        }
        if (stayOrderNo == null || stayOrderNo.isEmpty())
        {
            Map<String, Object> cur = stayOrders.current(userId);
            return cur;
        }
        Map<String, Object> o = db.one("select order_no, store_id, stay_id, check_in, check_out, nights, guest_name, guest_phone, status, stay_snapshot "
                + "from kg_stay_order where order_no = :n and app_user_id = :u and del_flag = '0'", p("n", stayOrderNo, "u", userId));
        if (o == null)
        {
            throw new ServiceException("入住订单不存在", HttpStatus.FORBIDDEN);
        }
        Map<String, Object> snap = KgStayOrderService.json(o.remove("staySnapshot"));
        o.put("stayName", snap.get("stayName"));
        o.put("cover", snap.get("cover"));
        o.put("typeName", snap.get("typeName"));
        return o;
    }

    public Map<String, Object> services(Long userId, String stayOrderNo, Long storeId, String category)
    {
        Map<String, Object> stay = stayOrder(userId, stayOrderNo);
        Long store = stay != null ? KgJdbc.asLong(stay.get("storeId")) : storeId;
        List<Map<String, Object>> cats = categories();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (store != null)
        {
            StringBuilder where = new StringBuilder(" where s.store_id = :st and s.status = '0' and s.del_flag = '0' "
                    + "and exists (select 1 from sys_dict_data d where d.dict_type = 'kg_shop_category' and d.dict_value = s.category and d.status = '0')");
            Map<String, Object> ps = p("st", store);
            if (category != null && !category.isEmpty() && !"recommend".equals(category))
            {
                where.append(" and s.category = :c");
                ps.put("c", category);
            }
            rows = db.list("select s.service_id, s.service_name, s.cover, s.price_fen, s.unit_label, s.category, s.refundable, s.advance_hours, "
                    + "s.duration_min, s.sold_total from kg_shop_service s" + where + " order by s.sort, s.service_id", ps);
            for (Map<String, Object> r : rows)
            {
                r.put("refundable", "1".equals(str(r.get("refundable"))));
                r.put("availableDesc", "需提前 " + r.get("advanceHours") + " 小时预约" + (r.get("durationMin") == null ? "" : " · " + r.get("durationMin") + " 分钟"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("categories", cats);
        out.put("rows", rows);
        out.put("stay", stay);
        return out;
    }

    public Map<String, Object> service(Long serviceId)
    {
        return service(serviceId, null);
    }

    public Map<String, Object> service(Long serviceId, Long userId)
    {
        Map<String, Object> s = db.one("select s.*, st.store_name, st.cover store_cover, st.rating store_rating, st.contact_phone from kg_shop_service s "
                + "left join kg_store st on st.store_id = s.store_id where s.service_id = :id and s.status = '0' and s.del_flag = '0'", p("id", serviceId));
        if (s == null || !categoryOpen(str(s.get("category"))))
        {
            throw new ServiceException("服务不存在或暂未开放");
        }
        s.put("badges", split(s.get("badges")));
        s.put("timeSlots", split(s.get("timeSlots")));
        s.put("refundable", "1".equals(str(s.get("refundable"))));
        // 下单页退款规则「X 审核通过后原路退回」里的 X
        s.put("refundReviewer", KgStayOrderService.refundReviewer());
        Map<String, Object> merchant = new LinkedHashMap<String, Object>();
        merchant.put("storeName", s.get("storeName"));
        merchant.put("cover", s.get("storeCover"));
        merchant.put("rating", s.get("storeRating"));
        merchant.put("servedCount", db.count("select count(*) from kg_shop_order where store_id = :st and status = 'used'", p("st", s.get("storeId"))));
        s.put("merchant", merchant);
        s.remove("contactPhone");
        s.put("reviewCount", 0);
        s.put("favorite", Boolean.valueOf(userId != null
                && db.count("select count(*) from kg_favorite where target_type = 'service' and target_id = :id and app_user_id = :u",
                        p("id", serviceId, "u", userId)) > 0));
        s.put("serverTime", LocalDateTime.now().format(KgStayOrderService.DT));
        return s;
    }

    /**
     * 收藏 / 取消收藏一个服务。收藏只是个人书签，不占名额、不通知门店；
     * 服务下架后收藏仍在，列表里会标出来。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> favorite(Long userId, Long serviceId, boolean on)
    {
        db.must("select service_id from kg_shop_service where service_id = :id and del_flag = '0'", p("id", serviceId), "服务不存在");
        if (on)
        {
            db.update("insert ignore into kg_favorite (target_type, target_id, app_user_id, create_time) values ('service', :id, :u, now())",
                    p("id", serviceId, "u", userId));
        }
        else
        {
            db.update("delete from kg_favorite where target_type = 'service' and target_id = :id and app_user_id = :u", p("id", serviceId, "u", userId));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("favorite", Boolean.valueOf(on));
        m.put("message", on ? "已收藏，可在「我的 · 收藏」里找到" : "已取消收藏");
        return m;
    }

    /** 我收藏的服务 */
    public List<Map<String, Object>> favorites(Long userId)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select s.service_id, s.service_name name, s.cover, s.price_fen, s.category, s.unit_label, s.status, s.del_flag, st.store_name "
                + "from kg_favorite f join kg_shop_service s on s.service_id = f.target_id left join kg_store st on st.store_id = s.store_id "
                + "where f.target_type = 'service' and f.app_user_id = :u order by f.fav_id desc", p("u", userId)))
        {
            boolean off = !"0".equals(str(r.get("status"))) || "2".equals(str(r.get("delFlag")));
            r.put("offline", Boolean.valueOf(off));
            r.put("statusText", off ? "已下架" : "可预订");
            r.remove("delFlag");
            rows.add(r);
        }
        return rows;
    }

    /**
     * 可约日期：整段入住期内、满足提前小时数的日期；离店当天只放退房前开始的时段；每个时段剩余名额。
     * 之前最多只给最近 6 天，长住客人后面几天的服务约不了；现在给整段入住期（小程序超过 6 天用日历点选），各时段已约份数一次查出
     */
    public Map<String, Object> slots(Long userId, Long serviceId, String stayOrderNo)
    {
        Map<String, Object> s = service(serviceId);
        Map<String, Object> stay = requireStay(userId, stayOrderNo, s);
        List<Map<String, Object>> days = new ArrayList<Map<String, Object>>();
        LocalDate in = date(str(stay.get("checkIn")));
        LocalDate out = date(str(stay.get("checkOut")));
        LocalTime checkout = checkoutTime(stay);
        Map<String, Long> booked = new HashMap<String, Long>();
        for (Map<String, Object> r : db.list("select use_date, time_slot, coalesce(sum(qty), 0) n from kg_shop_order where service_id = :s "
                + "and use_date >= :a and use_date <= :b and status in ('pending_payment','unused','used','refund_pending','refunding') group by use_date, time_slot",
                p("s", serviceId, "a", in.format(D), "b", out.format(D))))
        {
            String ud = str(r.get("useDate"));
            booked.put((ud.length() >= 10 ? ud.substring(0, 10) : ud) + " " + str(r.get("timeSlot")), fen(r.get("n")));
        }
        for (LocalDate d = in; !d.isAfter(out); d = d.plusDays(1))
        {
            List<Map<String, Object>> ss = new ArrayList<Map<String, Object>>();
            for (Object slot : (List<?>) s.get("timeSlots"))
            {
                String t = str(slot);
                if (!startsLater(d, t, (int) fen(s.get("advanceHours"))) || !beforeCheckout(d, t, out, checkout))
                {
                    continue;
                }
                Long used = booked.get(d.format(D) + " " + t);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("slot", t);
                m.put("left", Math.max(0, fen(s.get("slotCapacity")) - (used == null ? 0 : used)));
                ss.add(m);
            }
            if (!ss.isEmpty())
            {
                Map<String, Object> day = new LinkedHashMap<String, Object>();
                day.put("date", d.format(D));
                day.put("label", KgStayBrowseService.md(d) + "（" + KgStayBrowseService.weekday(d) + "）");
                day.put("slots", ss);
                days.add(day);
            }
        }
        Map<String, Object> outMap = new LinkedHashMap<String, Object>();
        outMap.put("days", days);
        outMap.put("stay", stay);
        return outMap;
    }

    private static boolean startsLater(LocalDate d, String slot, int advanceHours)
    {
        try
        {
            LocalTime start = LocalTime.parse(slot.split("-")[0].trim());
            return d.atTime(start).isAfter(LocalDateTime.now().plusHours(advanceHours));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * 离店当天只能约退房时间前开始的时段：过了退房时间入住单会被系统结束（没入住的记未到店，已入住的退房 2 小时后记已完成），
     * 之后的服务到点核销不了（「关联入住订单已失效」），客人付了钱只能再申请退款
     */
    static boolean beforeCheckout(LocalDate d, String slot, LocalDate checkOut, LocalTime checkout)
    {
        if (checkOut == null || !d.equals(checkOut))
        {
            return true;
        }
        try
        {
            return LocalTime.parse(slot.split("-")[0].trim()).isBefore(checkout);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** 入住房源的退房时间（库里不是 HH:mm 时按 12:00，和入住单自动结束同一口径） */
    private LocalTime checkoutTime(Map<String, Object> stay)
    {
        Map<String, Object> st = db.one("select checkout_time from kg_stay where stay_id = :s", p("s", stay.get("stayId")));
        String t = st == null ? "" : str(st.get("checkoutTime")).trim();
        return LocalTime.parse(t.matches("([01][0-9]|2[0-3]):[0-5][0-9]") ? t : "12:00");
    }

    private Map<String, Object> requireStay(Long userId, String stayOrderNo, Map<String, Object> s)
    {
        Map<String, Object> stay = stayOrder(userId, stayOrderNo);
        if (stay == null)
        {
            throw new ServiceException("增值服务需在入住期间预订，你当前没有进行中的入住订单");
        }
        if (!Arrays.asList("confirmed", "checked_in").contains(str(stay.get("status"))))
        {
            throw new ServiceException("入住订单当前状态不能预订增值服务",409);
        }
        if (!KgJdbc.asLong(stay.get("storeId")).equals(KgJdbc.asLong(s.get("storeId"))))
        {
            throw new ServiceException("只能预订入住门店的服务");
        }
        return stay;
    }

    public Map<String, Object> quote(Long userId, Map<String, Object> body)
    {
        Long serviceId = KgJdbc.asLong(body.get("serviceId"));
        Map<String, Object> s = service(serviceId);
        Map<String, Object> stay = requireStay(userId, str(body.get("stayOrderNo")), s);
        int qty = (int) Math.max(1, fen(body.get("qty")));
        if (qty > 10)
        {
            throw new ServiceException("单次最多 10 份");
        }
        long unit = fen(s.get("priceFen"));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("service", s);
        out.put("stay", stay);
        out.put("unitFen", unit);
        out.put("qty", qty);
        out.put("amountFen", unit * qty);
        out.put("serviceFeeFen", 0);
        out.put("discountFen", 0);
        out.put("payFen", unit * qty);
        out.put("refundRule", Boolean.TRUE.equals(s.get("refundable")) ? "未核销可申请退款，" + KgStayOrderService.refundReviewer() + "审核通过后原路退回，到账时间以支付渠道为准" : "该服务不支持退款");
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Long userId, Map<String, Object> body)
    {
        // 幂等判断放在最前面：同一次提交的重放（超时重试）要拿回第一次那张单，
        // 不能先撞上"价格变了""已有 3 张待支付"这些校验而报错
        String idem = str(body.get("idemKey"));
        if (idem.length() > 48)
        {
            throw new ServiceException("idemKey 过长（最多 48 个字符）");
        }
        if (!idem.isEmpty())
        {
            Map<String, Object> dup = db.one("select order_no, status, pay_fen, expire_time, pay_no from kg_shop_order where app_user_id = :u and create_by = :k",
                    p("u", userId, "k", "idem:" + idem));
            if (dup != null)
            {
                return result(dup);
            }
            // 同一次提交并发到达时，上面的判重两边都查不到；占幂等键串行化，后到的一方取回先到那张单
            if (!db.claimIdem("shop:" + userId + ":" + idem, "shop_order"))
            {
                Map<String, Object> first = db.one("select order_no, status, pay_fen, expire_time, pay_no from kg_shop_order where app_user_id = :u and create_by = :k for share",
                        p("u", userId, "k", "idem:" + idem));
                if (first != null)
                {
                    return result(first);
                }
                throw new ServiceException("这笔订单正在提交，请稍后在「我的订单」查看", 409);
            }
        }
        Map<String, Object> q = quote(userId, body);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) q.get("service");
        @SuppressWarnings("unchecked")
        Map<String, Object> stay = (Map<String, Object>) q.get("stay");
        requireActiveStayLocked(str(stay.get("orderNo")));
        Long serviceId = KgJdbc.asLong(s.get("serviceId"));
        LocalDate useDate = date(str(body.get("useDate")));
        String slot = str(body.get("timeSlot"));
        LocalDate in = date(str(stay.get("checkIn")));
        LocalDate out = date(str(stay.get("checkOut")));
        if (useDate == null || useDate.isBefore(in) || useDate.isAfter(out))
        {
            throw new ServiceException("使用日期需在入住期间");
        }
        // 时段不存在和来不及预约分开说：之前一律写「需提前 0 小时」，客人看不懂
        if (!((List<?>) s.get("timeSlots")).contains(slot))
        {
            throw new ServiceException("该时段不存在或已下架");
        }
        if (!startsLater(useDate, slot, (int) fen(s.get("advanceHours"))))
        {
            throw new ServiceException("该时段不可预约（需提前 " + s.get("advanceHours") + " 小时）");
        }
        LocalTime checkout = checkoutTime(stay);
        if (!beforeCheckout(useDate, slot, out, checkout))
        {
            throw new ServiceException("离店当天只能预约 " + checkout + " 退房前开始的时段");
        }
        long payFen = fen(q.get("payFen"));
        Long expected = KgJdbc.asLong(body.get("expectedPayFen"));
        if (expected != null && expected != payFen)
        {
            throw new ServiceException("价格有变化，最新应付 ¥" + KgStayBrowseService.yuan(payFen) + "，请确认后重新提交", 409);
        }
        if (db.count("select count(*) from kg_shop_order where app_user_id = :u and status = 'pending_payment'", p("u", userId)) >= 3)
        {
            throw new ServiceException("你有 3 张待支付的服务订单，请先支付或取消");
        }
        // 锁服务行后按时段校验名额，避免并发超卖
        db.one("select service_id from kg_shop_service where service_id = :id for update", p("id", serviceId));
        int qty = (int) fen(q.get("qty"));
        // 必须是加锁读：本事务的快照在前面的普通查询时就定下了，普通 select 看不到刚刚提交的别人的订单，
        // 两个人抢最后一个名额会都判"够"（2026-09-24 复核发现）
        long used = db.count("select coalesce(sum(qty), 0) from kg_shop_order where service_id = :s and use_date = :d and time_slot = :t "
                + "and status in ('pending_payment','unused','used','refund_pending','refunding') for share", p("s", serviceId, "d", useDate.format(D), "t", slot));
        if (used + qty > fen(s.get("slotCapacity")))
        {
            throw new ServiceException("该时段名额不足（剩余 " + Math.max(0, fen(s.get("slotCapacity")) - used) + "）");
        }
        String userName = str(body.get("userName")).trim();
        if (userName.isEmpty())
        {
            userName = str(stay.get("guestName"));
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        String orderNo = StayCodes.orderNo("P");
        row.put("order_no", orderNo);
        row.put("service_id", serviceId);
        row.put("store_id", s.get("storeId"));
        row.put("stay_order_no", stay.get("orderNo"));
        row.put("app_user_id", userId);
        row.put("qty", qty);
        row.put("unit_fen", q.get("unitFen"));
        row.put("pay_fen", payFen);
        row.put("user_name", StaffSupport.cut(userName, 32));
        row.put("user_phone", stay.get("guestPhone"));
        row.put("use_date", useDate.format(D));
        row.put("time_slot", slot);
        row.put("remark", StaffSupport.cut(str(body.get("remark")).trim(), 100));
        row.put("status", "pending_payment");
        row.put("expire_time", LocalDateTime.now().plusMinutes(KgStayOrderService.PAY_MINUTES).format(KgStayOrderService.DT));
        Map<String, Object> snap = new LinkedHashMap<String, Object>();
        snap.put("serviceName", s.get("serviceName"));
        snap.put("cover", s.get("cover"));
        snap.put("specLabel", s.get("specLabel"));
        snap.put("unitLabel", s.get("unitLabel"));
        snap.put("storeName", s.get("storeName"));
        snap.put("refundable", s.get("refundable"));
        snap.put("location", s.get("location"));
        snap.put("rescheduleLimit", s.get("rescheduleLimit"));
        row.put("service_snapshot", JSON.toJSONString(snap));
        row.put("create_by", idem.isEmpty() ? "app:" + userId : "idem:" + idem);
        row.put("create_time", new Date());
        long orderId = db.insert("kg_shop_order", row);
        Map<String, Object> po = pay.createForNewBusiness(BIZ, orderId, userId, KgJdbc.asLong(s.get("storeId")), payFen, s.get("serviceName") + " × " + qty);
        db.update("update kg_shop_order set pay_no = :n where order_id = :id", p("n", po.get("payNo"), "id", orderId));
        return result(db.one("select order_no, status, pay_fen, expire_time, pay_no from kg_shop_order where order_id = :id", p("id", orderId)));
    }

    private static Map<String, Object> result(Map<String, Object> o)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("orderNo", o.get("orderNo"));
        m.put("status", o.get("status"));
        m.put("payFen", o.get("payFen"));
        m.put("expireTime", o.get("expireTime"));
        m.put("payNo", o.get("payNo"));
        m.put("serverTime", LocalDateTime.now().format(KgStayOrderService.DT));
        return m;
    }

    Map<String, Object> own(Long userId, String orderNo)
    {
        Map<String, Object> o = db.one("select * from kg_shop_order where order_no = :n and del_flag = '0'", p("n", orderNo));
        if (o == null || !userId.equals(KgJdbc.asLong(o.get("appUserId"))))
        {
            throw new ServiceException("订单不存在", HttpStatus.FORBIDDEN);
        }
        return o;
    }

    Map<String, Object> view(Map<String, Object> o, boolean mine)
    {
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        for (String k : Arrays.asList("orderNo", "serviceId", "storeId", "stayOrderNo", "qty", "unitFen", "payFen", "useDate", "timeSlot", "remark",
                "status", "payNo", "paidTime", "expireTime", "verifyTime", "rescheduleCount", "refundFen", "refundReason", "refundNote", "refundTime", "createTime"))
        {
            v.put(k, o.get(k));
        }
        Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
        v.put("service", snap);
        v.put("serviceName", snap.get("serviceName"));
        v.put("cover", snap.get("cover"));
        v.put("storeName", snap.get("storeName"));
        v.put("statusLabel", statusLabel(str(o.get("status"))));
        // 服务页「提交后由 X 审核」「X 审核中」里的 X
        v.put("refundReviewer", KgStayOrderService.refundReviewer());
        v.put("userName", o.get("userName"));
        v.put("maskedPhone", MaskUtils.phone(str(o.get("userPhone"))));
        if ("unused".equals(str(o.get("status"))) && o.get("verifyCode") != null && mine)
        {
            v.put("code", o.get("verifyCode"));
            v.put("qrDataUri", StayCodes.qrDataUri(str(o.get("verifyCode"))));
        }
        v.put("serverTime", LocalDateTime.now().format(KgStayOrderService.DT));
        return v;
    }

    public Map<String, Object> mine(Long userId, String tab, int pageNum, int pageSize)
    {
        String st = "used".equals(tab) ? "'used'" : ("refunded".equals(tab) ? "'refunded','refund_pending','refunding'" : ("pending".equals(tab) ? "'pending_payment'" : "'unused'"));
        Map<String, Object> page = db.page("select * from kg_shop_order where app_user_id = :u and del_flag = '0' and status in (" + st + ") order by order_id desc",
                p("u", userId), pageNum, pageSize);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : rows)
        {
            out.add(view(r, true));
        }
        page.put("rows", out);
        page.put("counts", counts(userId));
        return page;
    }

    private Map<String, Object> counts(Long userId)
    {
        Map<String, Object> c = db.one("select sum(status = 'unused') unused, sum(status = 'used') used, sum(status in ('refunded','refund_pending','refunding')) refunded, "
                + "sum(status = 'pending_payment') pending from kg_shop_order where app_user_id = :u and del_flag = '0'", p("u", userId));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("unused", fen(c.get("unused")));
        m.put("used", fen(c.get("used")));
        m.put("refunded", fen(c.get("refunded")));
        m.put("pending", fen(c.get("pending")));
        return m;
    }

    public Map<String, Object> detail(Long userId, String orderNo)
    {
        return view(own(userId, orderNo), true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, String orderNo)
    {
        Map<String, Object> o = own(userId, orderNo);
        if (!"pending_payment".equals(str(o.get("status"))))
        {
            throw new ServiceException("只能取消待支付的订单");
        }
        if (!pay.close(str(o.get("payNo"))))
        {
            throw new ServiceException("订单已支付，请刷新后查看");
        }
        db.update("update kg_shop_order set status = 'closed', update_time = now() where order_id = :id and status = 'pending_payment'", p("id", o.get("orderId")));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyRefund(Long userId, String orderNo, String reason)
    {
        Map<String, Object> o = own(userId, orderNo);
        Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
        if (!"unused".equals(str(o.get("status"))))
        {
            throw new ServiceException("只有待使用的服务可以申请退款");
        }
        if (!Boolean.TRUE.equals(snap.get("refundable")))
        {
            throw new ServiceException("该服务不支持退款");
        }
        String why = reason == null || reason.trim().isEmpty() ? "用户申请退款" : reason.trim();
        int n = db.update("update kg_shop_order set status = 'refund_pending', refund_fen = pay_fen, refund_reason = :r, refund_apply_time = now(), "
                + "update_time = now() where order_id = :id and status = 'unused'", p("id", o.get("orderId"), "r", StaffSupport.cut(why, 200)));
        if (n == 0)
        {
            throw new ServiceException("订单状态已变化，请刷新后重试");
        }
        if (KgStayOrderService.refundByPlatform())
        {
            // 角色切换后平台审：门店只知会，点开看这张服务单（二消订单里筛出这一单），不再叫他们去审批
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "客人申请了服务退款（平台审核中）",
                    "服务订单 " + orderNo + "（" + snap.get("serviceName") + "）申请退款，由平台审核，门店无需处理；审核结果会再通知你。", "service_order",
                    KgJdbc.asLong(o.get("orderId")), "/pages/staff/insurance?s=3&orderNo=" + orderNo);
        }
        else
        {
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "新的服务退款申请",
                    "服务订单 " + orderNo + "（" + snap.get("serviceName") + "）申请退款，请尽快审核。", "service_order", KgJdbc.asLong(o.get("orderId")),
                    "/pages/staff/insurance?s=4&tab=refund&orderNo=" + orderNo);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("status", "refund_pending");
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reschedule(Long userId, String orderNo, String useDate, String timeSlot)
    {
        Map<String, Object> o = own(userId, orderNo);
        if (!"unused".equals(str(o.get("status"))))
        {
            throw new ServiceException("只有待使用的服务可以改期");
        }
        Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
        int limit = (int) fen(snap.get("rescheduleLimit"));
        if (fen(o.get("rescheduleCount")) >= limit)
        {
            throw new ServiceException("已达到可改期次数（" + limit + " 次）");
        }
        Map<String, Object> s = service(KgJdbc.asLong(o.get("serviceId")));
        Map<String, Object> stay = requireStay(userId, str(o.get("stayOrderNo")), s);
        requireActiveStayLocked(str(o.get("stayOrderNo")));
        LocalDate d = date(useDate);
        if (d == null || d.isBefore(date(str(stay.get("checkIn")))) || d.isAfter(date(str(stay.get("checkOut")))))
        {
            throw new ServiceException("使用日期需在入住期间");
        }
        if (!((List<?>) s.get("timeSlots")).contains(timeSlot))
        {
            throw new ServiceException("该时段不存在或已下架");
        }
        if (!startsLater(d, timeSlot, (int) fen(s.get("advanceHours"))))
        {
            throw new ServiceException("该时段不可预约（需提前 " + s.get("advanceHours") + " 小时）");
        }
        LocalTime checkout = checkoutTime(stay);
        if (!beforeCheckout(d, timeSlot, date(str(stay.get("checkOut"))), checkout))
        {
            throw new ServiceException("离店当天只能改到 " + checkout + " 退房前开始的时段");
        }
        db.one("select service_id from kg_shop_service where service_id = :id for update", p("id", o.get("serviceId")));
        long used = db.count("select coalesce(sum(qty), 0) from kg_shop_order where service_id = :s and use_date = :d and time_slot = :t "
                + "and status in ('pending_payment','unused','used','refund_pending','refunding') and order_id <> :me for share",  // 加锁读，理由同下单
                p("s", o.get("serviceId"), "d", d.format(D), "t", timeSlot, "me", o.get("orderId")));
        if (used + fen(o.get("qty")) > fen(s.get("slotCapacity")))
        {
            throw new ServiceException("该时段名额不足");
        }
        int n = db.update("update kg_shop_order set use_date = :d, time_slot = :t, reschedule_count = reschedule_count + 1, update_time = now() "
                + "where order_id = :id and status = 'unused' and reschedule_count = :c",
                p("d", d.format(D), "t", timeSlot, "id", o.get("orderId"), "c", fen(o.get("rescheduleCount"))));
        if (n == 0)
        {
            throw new ServiceException("订单状态已变化，请刷新后重试");
        }
        return view(db.one("select * from kg_shop_order where order_id = :id", p("id", o.get("orderId"))), true);
    }

    // ---------- 审批（员工 / 后台） ----------

    @Transactional(rollbackFor = Exception.class)
    public String approveRefund(String orderNo, String operator, Long operatorUserId)
    {
        Map<String, Object> o = db.must("select * from kg_shop_order where order_no = :n and del_flag = '0'", p("n", orderNo), "订单不存在");
        pay.lockPayment(str(o.get("payNo")));
        o = db.must("select * from kg_shop_order where order_no=:n and del_flag='0' for update", p("n", orderNo), "订单不存在");
        if (operatorUserId != null && operatorUserId.equals(KgJdbc.asLong(o.get("appUserId"))))
        {
            throw new ServiceException("不能审批自己下单的退款", HttpStatus.FORBIDDEN);
        }
        int n = db.update("update kg_shop_order set status = 'refunding', refund_by = :u, verify_code = null, update_time = now() "
                + "where order_id = :id and status = 'refund_pending'", p("id", o.get("orderId"), "u", operator));
        if (n == 0)
        {
            throw new ServiceException("该退款已处理过");
        }
        if (KgStayOrderService.platform(operator))
        {
            // 平台在后台审批的：告诉门店结果（门店员工自己审批的不用再发）
            Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "平台已同意服务退款",
                    "服务订单 " + orderNo + "（" + snap.get("serviceName") + "，" + MaskUtils.name(str(o.get("userName"))) + "）的退款平台已同意，退 ¥"
                            + KgStayBrowseService.yuan(fen(o.get("refundFen"))) + "，这单不用再接待。",
                    "service_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/insurance?s=3&orderNo=" + orderNo);
        }
        pay.refund(str(o.get("payNo")), fen(o.get("refundFen")), str(o.get("refundReason")), operator);
        return "已同意退款 ¥" + KgStayBrowseService.yuan(fen(o.get("refundFen"))) + "，退款处理中，到账时间以支付渠道为准";
    }

    @Transactional(rollbackFor = Exception.class)
    public String rejectRefund(String orderNo, String note, String operator)
    {
        String why = note == null ? "" : note.trim();
        if (why.isEmpty())
        {
            throw new ServiceException("请填写驳回原因");
        }
        Map<String, Object> o = db.must("select * from kg_shop_order where order_no = :n and del_flag = '0'", p("n", orderNo), "订单不存在");
        requireActiveStayLocked(str(o.get("stayOrderNo")));
        o = db.must("select * from kg_shop_order where order_no=:n for update", p("n", orderNo), "订单不存在");
        if (str(o.get("verifyCode")).isEmpty()) throw new ServiceException("该订单无有效核销凭证，只能完成补偿退款", 409);
        int n = db.update("update kg_shop_order set status = 'unused', refund_fen = 0, refund_note = :r, refund_by = :u, update_time = now() "
                + "where order_id = :id and status = 'refund_pending'", p("id", o.get("orderId"), "r", StaffSupport.cut(why, 200), "u", operator));
        if (n == 0)
        {
            throw new ServiceException("该退款已处理过");
        }
        support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "服务退款申请未通过",
                "服务订单 " + orderNo + " 的退款申请未通过：" + why, "service_order", KgJdbc.asLong(o.get("orderId")), "/pages/stays/shop?orderNo=" + orderNo);
        if (KgStayOrderService.platform(operator))
        {
            Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
            support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "平台已驳回服务退款",
                    "服务订单 " + orderNo + "（" + snap.get("serviceName") + "，" + o.get("useDate") + " " + o.get("timeSlot") + "）的退款申请平台已驳回：" + why
                            + "。服务照常有效，请按预约接待。",
                    "service_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/insurance?s=3&orderNo=" + orderNo);
        }
        return "已驳回";
    }

    // ---------- 支付回调 ----------

    @Override
    public String bizType()
    {
        return BIZ;
    }

    @Override
    public void onPaid(Map<String, Object> payRow)
    {
        Map<String, Object> o = db.one("select * from kg_shop_order where order_id = :id", p("id", payRow.get("bizId")));
        if (o == null)
        {
            throw new ServiceException("订单不存在");
        }
        // 当前读并先锁父单，再锁子单，与入住退款的锁顺序相同。
        Map<String, Object> parent = db.one("select status, del_flag from kg_stay_order where order_no=:n for share", p("n", o.get("stayOrderNo")));
        boolean activeStay = parent != null && "0".equals(str(parent.get("delFlag")))
                && Arrays.asList("confirmed", "checked_in").contains(str(parent.get("status")));
        if (!activeStay)
        {
            db.update("update kg_shop_order set status='refund_pending', paid_time=now(), verify_code=null, refund_fen=pay_fen, "
                    + "refund_reason='关联入住订单失效后到账，待人工全额退款', refund_apply_time=now(), update_time=now() "
                    + "where order_id=:id and status in ('pending_payment','closed')", p("id", o.get("orderId")));
            return;
        }
        // 建单/改期同样按父住宿→服务资源→服务订单顺序，避免销量更新与容量查询反向争锁。
        db.one("select service_id from kg_shop_service where service_id=:id for update", p("id", o.get("serviceId")));
        String code = null;
        for (int i = 0; i < 5 && code == null; i++)
        {
            String c = StayCodes.verifyCode();
            if (db.count("select count(*) from kg_shop_order where verify_code = :c", p("c", c)) == 0
                    && db.count("select count(*) from kg_stay_order where verify_code = :c", p("c", c)) == 0)
            {
                code = c;
            }
        }
        int n = db.update("update kg_shop_order set status = 'unused', paid_time = now(), verify_code = :c, update_time = now() "
                + "where order_id = :id and status = 'pending_payment'", p("id", o.get("orderId"), "c", code));
        if (n == 0)
        {
            db.update("update kg_shop_order set status = 'refund_pending', paid_time = now(), refund_fen = pay_fen, "
                    + "refund_reason = '订单关闭后才到账（迟到支付），待人工全额退款', refund_apply_time = now(), update_time = now() "
                    + "where order_id = :id and status = 'closed'", p("id", o.get("orderId")));
            log.warn("服务订单迟到支付 order={}", o.get("orderNo"));
            return;
        }
        db.update("update kg_shop_service set sold_total = sold_total + :q where service_id = :s", p("q", o.get("qty"), "s", o.get("serviceId")));
        Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
        support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "服务预订成功",
                snap.get("serviceName") + "，" + o.get("useDate") + " " + o.get("timeSlot") + "，到店出示核销码使用。", "service_order",
                KgJdbc.asLong(o.get("orderId")), "/pages/stays/shop?orderNo=" + o.get("orderNo"));
        support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "新的服务订单",
                MaskUtils.name(str(o.get("userName"))) + " 预订了 " + snap.get("serviceName") + " × " + o.get("qty") + "（" + o.get("useDate") + " " + o.get("timeSlot") + "）。",
                "service_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/insurance?s=3&orderNo=" + o.get("orderNo"));
    }

    @Override
    public void onRefunded(Map<String, Object> payRow, long amount)
    {
        // 加锁读并回写订单：此前这里只发通知，服务订单在后台全额退款后仍是 unused——核销码有效、名额仍被占、还能再申请退款，
        // 佣金与营收统计也看不到这笔退款（2026-09-28 复核发现）
        Map<String, Object> o = db.one("select * from kg_shop_order where order_id = :id for update", p("id", payRow.get("bizId")));
        if (o != null)
        {
            long refundedTotal = Math.min(fen(o.get("payFen")), fen(payRow.get("refundAmount")));
            String st = str(o.get("status"));
            if ("refunded".equals(str(payRow.get("status"))) && ("unused".equals(st) || "refund_pending".equals(st) || "refunding".equals(st)))
            {
                // refunded 不在名额统计的状态集合里，所以名额随之释放
                int n = db.update("update kg_shop_order set status = 'refunded', refund_fen = :t, refund_time = now(), verify_code = null, update_time = now() "
                        + "where order_id = :id and status in ('unused','refund_pending','refunding')", p("id", o.get("orderId"), "t", refundedTotal));
                // 不是走审批（refunding）退的，就是平台在后台对支付单直接全额退款：这单作废了，告诉门店别再按预约准备
                if (n > 0 && !"refunding".equals(st))
                {
                    Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
                    support.notifyStore(KgJdbc.asLong(o.get("storeId")), MessageCategory.ORDER, "平台已全额退款，服务订单已取消",
                            "服务订单 " + o.get("orderNo") + "（" + snap.get("serviceName") + "，" + o.get("useDate") + " " + o.get("timeSlot") + "）已由平台全额退款 ¥"
                                    + KgStayBrowseService.yuan(refundedTotal) + "，这单不用再接待。",
                            "service_order", KgJdbc.asLong(o.get("orderId")), "/pages/staff/insurance?s=3&orderNo=" + o.get("orderNo"));
                }
            }
            else if (!"refund_pending".equals(st))
            {
                // 已核销（used）的补偿退款只记录退款额，让佣金冲回与营收统计能看到
                db.update("update kg_shop_order set refund_fen = :t, refund_time = now(), update_time = now() where order_id = :id",
                        p("id", o.get("orderId"), "t", refundedTotal));
            }
            support.notifyUser(KgJdbc.asLong(o.get("appUserId")), MessageCategory.ORDER, "服务退款已原路退回",
                    "服务订单 " + o.get("orderNo") + " 退款 ¥" + KgStayBrowseService.yuan(amount) + " 已原路退回。", "service_order",
                    KgJdbc.asLong(o.get("orderId")), "/pages/stays/shop?orderNo=" + o.get("orderNo"));
        }
    }

    // ---------- 定时：超时关单 ----------

    @Scheduled(fixedDelay = 60000, initialDelay = 40000)
    public void closeExpired()
    {
        // closed 业务单本身也是持久化补偿待办，服务重启后仍会继续关闭遗留支付单。
        for (Map<String, Object> o : db.list("select o.* from kg_shop_order o join kg_pay_order p on p.pay_no=o.pay_no "
                + "where p.status='pending' and (o.status='closed' or (o.status='pending_payment' and o.expire_time<now())) order by o.order_id limit 100", p()))
        {
            try
            {
                self.closeOne(o);
            }
            catch (RuntimeException e)
            {
                log.warn("服务订单关单失败 {}：{}", o.get("orderNo"), e.getMessage());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void closeOne(Map<String, Object> o)
    {
        if (pay.close(str(o.get("payNo"))))
        {
            db.update("update kg_shop_order set status = 'closed', update_time = now() where order_id = :id and status = 'pending_payment'", p("id", o.get("orderId")));
        }
    }

    private void requireActiveStayLocked(String no)
    {
        Map<String, Object> s = db.one("select status,del_flag from kg_stay_order where order_no=:n for share", p("n", no));
        if (s == null || !"0".equals(str(s.get("delFlag"))) || !Arrays.asList("confirmed", "checked_in").contains(str(s.get("status"))))
            throw new ServiceException("关联入住订单已失效，服务不能继续履约，请完成退款", 409);
    }
}
