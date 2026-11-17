package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.enums.JobStatus;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgJobReviewDimensions;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.service.IKgApplicationService;

/**
 * 用户端扩展的其余能力：订单聚合、评价（写 / 我的 / 门店评价汇总）、提醒设置、体验官申请、同行队伍、
 * 新机会订阅（含推送任务）、岗位多条件筛选、责任书签署存证与到店信息。
 * 报名类订单永远不产生应付金额（招聘环节不收押金 / 定金）。
 */
@Service
public class KgUserHubService
{
    private static final Logger log = LoggerFactory.getLogger(KgUserHubService.class);
    /** 责任书版本：条款文字一改就要升版本（版本号也算进摘要原文），否则两种文字会共用一个版本号；旧版文字见 TERMS 上方注释 */
    static final String CONTRACT_VERSION = "2026-11";

    @Autowired(required=false) private KgMembershipRewards memberRewards;
    @Autowired
    private KgJdbc db;
    @Autowired private KgDepartureNotice departureNotice;
    @Autowired private KgVolunteerContractService volunteerContracts;

    @Autowired
    private KgJobMapper jobMapper;

    @Autowired
    private com.ruoyi.kangaroo.ext.core.KgArrivalTickets arrivalTickets;

    @Autowired(required = false)
    private KgSubscribeService subscribe;

    @Autowired
    private KgHomeService home;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private com.ruoyi.kangaroo.ext.core.KgReviewStats reviewStats;

    @Autowired
    private StaffSupport support;

    @Autowired
    private IKgApplicationService applications;

    @Autowired
    private KgCityService cityService;
    @Autowired
    private KgRegionGroupService regionGroups;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgVolunteerKinds volunteerKinds;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    /** 岗位金额列按 salary_type 换成「元/天」的 SQL 表达式（小时 × 8、周 ÷ 5、月 ÷ 21.75），用于期望薪资筛选 */
    static String dailyPay(String col)
    {
        return "(case j.salary_type when 'daily' then " + col + " when 'hourly' then " + col + " * 8 when 'weekly' then " + col + " / 5 "
                + "when 'monthly' then " + col + " / 21.75 end)";
    }

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    static String yuan(long fen)
    {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.2f", fen / 100.0);
    }

    static String md(Object d)
    {
        String s = str(d);
        return s.length() >= 10 ? Integer.parseInt(s.substring(5, 7)) + "." + Integer.parseInt(s.substring(8, 10)) : "";
    }

    // ---------------------------------------------------------------- 订单聚合

    /** 进度落在面试页（pages/jobs/interview）的报名状态；与小程序 progress.vue / interview.vue 的分流表同一口径 */
    static final List<String> INTERVIEW_PAGE_STATUSES = Arrays.asList("interview_booked", "interview_done", "user_confirmed",
            "store_confirmed", "contract_signed", "onboarded");

    private static Map<String, Object> btn(String label, String type, String url)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("type", type);
        m.put("url", url);
        return m;
    }

    /** 订单卡片的地点行：城市、商圈 / 门店 / 集合地点里空的不拼（之前商圈是空串，卡片写成「大理 ·」） */
    static String locText(Object... parts)
    {
        StringBuilder b = new StringBuilder();
        for (Object part : parts)
        {
            String t = str(part).trim();
            if (!t.isEmpty())
            {
                b.append(b.length() == 0 ? "" : " · ").append(t);
            }
        }
        return b.toString();
    }

    private static Map<String, Object> order(String type, String icon, String hex, String status, String tone, String title, String loc, String date,
            String price, String unit, String image, String sortKey)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("icon", icon);
        m.put("iconHex", hex);
        m.put("status", status);
        m.put("tone", tone);
        m.put("title", title);
        m.put("loc", loc);
        m.put("date", date);
        m.put("price", price);
        m.put("unit", unit);
        m.put("image", image);
        m.put("sortKey", sortKey);
        m.put("btns", new ArrayList<Map<String, Object>>());
        return m;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> orders(Long me, String tab)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.app_id, a.status, a.arrival_date, a.stay_days, a.submit_time, j.job_type, j.title, j.city, j.cover, j.salary_min, "
                + "j.salary_max, j.salary_unit, j.store_id, (select count(*) from kg_review v where v.biz_type = 'application' and v.biz_id = a.app_id and v.app_user_id = :u) reviewed, "
                // 能不能评价：和待评价、提交校验同一口径（会员规则下换工 / 兼职要门店确认完成，全职入职即可）
                + "(case when " + appReviewableSql() + " then 1 else 0 end) review_ok, "
                + "(select count(*) from kg_online_submission x where x.app_id = a.app_id) submission_count, "
                + "(select s.status from kg_online_settlement s where s.app_id = a.app_id) settlement_status, "
                + "(select s.amount_fen from kg_online_settlement s where s.app_id = a.app_id) settlement_fen "
                + "from kg_application a join kg_job j on j.job_id = a.job_id where a.app_user_id = :u and a.del_flag = '0' "
                + "and (a.status <> 'withdrawn' or (j.job_type = 'online' and (exists (select 1 from kg_online_submission x where x.app_id = a.app_id) "
                + "or exists (select 1 from kg_online_settlement s where s.app_id = a.app_id)))) order by a.app_id desc limit 50",
                p("u", me)))
        {
            String st = str(a.get("status"));
            String jt = str(a.get("jobType"));
            boolean online = "online".equals(jt);
            String settlement = str(a.get("settlementStatus"));
            boolean retainedWork = online && (num(a.get("submissionCount")) > 0 || !settlement.isEmpty());
            // 报名状态机里没有 completed，走完流程的终点是 onboarded；原先只认 completed，岗位报名永远不会显示「已完成」
            boolean done = online ? "confirmed".equals(settlement) : "onboarded".equals(st) || "completed".equals(st);
            // 撤回不抹掉已发生的成果或债务；有未结清结算时仍归入进行中，金额使用结算快照。
            boolean closed = Arrays.asList("withdrawn", "review_rejected", "terminated").contains(st) && settlement.isEmpty();
            String status = done ? "已完成" : (closed ? "已结束" : "进行中");
            String type = "volunteer".equals(jt) ? "换工" : ("parttime".equals(jt) ? "兼职" : ("fulltime".equals(jt) ? "全职" : "在线"));
            long days = Math.max(1, num(a.get("stayDays")));
            String date = a.get("arrivalDate") == null ? "到岗日期待定" : md(a.get("arrivalDate")) + "—" + md(LocalDate.parse(str(a.get("arrivalDate")).substring(0, 10)).plusDays(days - 1).toString())
                    + "（" + days + "天）";
            String onlineDate = settlement.isEmpty() ? (closed ? "报名已结束 · 成果留存" : "无需到店 · 按任务要求交付")
                    : ("pending_payout".equals(settlement) ? "已验收 · 待门店付款" : ("payout_registered".equals(settlement) ? "已登记付款 · 待确认收款" : ("confirmed".equals(settlement) ? "结算完成" : "收款申诉中")));
            Map<String, Object> o = order(type, "volunteer".equals(jt) ? "house" : ("fulltime".equals(jt) ? "store" : "circle-dot"), "volunteer".equals(jt) ? "#C99043" : "#40576B",
                    status, done ? "green" : (closed ? "gray" : "indigo"), str(a.get("title")), online ? "全国远程" : str(a.get("city")), online ? onlineDate : date,
                    online && !settlement.isEmpty() ? yuan(num(a.get("settlementFen"))) : (a.get("salaryMin") == null ? "面议" : new java.math.BigDecimal(String.valueOf(a.get("salaryMin"))).stripTrailingZeros().toPlainString()),
                    online && !settlement.isEmpty() ? "元" : str(a.get("salaryUnit")), str(a.get("cover")), str(a.get("submitTime")));
            o.put("signed", !"online".equals(jt) && "contract_signed".equals(st) ? "已签责任书" : null);
            List<Map<String, Object>> b = (List<Map<String, Object>>) o.get("btns");
            // 已约面试及之后（面试、双向确认、签约）的进度在面试页：那里才有面试后反馈、意向确认与责任书入口。
            // 原先一律进审核进度页，已约面试的报名还停在"请选择视频面试时间"，再约一次只会撞唯一键或状态校验。
            // 线上任务没有面试，仍进审核进度页（那一页会给出交付入口）
            boolean interviewStage = !"online".equals(jt) && INTERVIEW_PAGE_STATUSES.contains(st);
            b.add(btn("查看进度", "outline", (interviewStage ? "/pages/jobs/interview?appId=" : "/pages/jobs/progress?appId=") + a.get("appId")));
            if (!online && "store_confirmed".equals(st))
            {
                b.add(btn("签署责任书", "primary", "/pages/jobs/contract?appId=" + a.get("appId") + "&view=terms"));
            }
            if (!online && Arrays.asList("contract_signed", "onboarded", "completed").contains(st))
            {
                b.add(btn("到店确认", "primary", "/pages/jobs/contract?appId=" + a.get("appId") + "&s=5"));
            }
            if (!online && num(a.get("reviewOk")) == 1 && num(a.get("reviewed")) == 0)
            {
                b.add(btn("去评价", "primary", "/pages/profile/review?bizType=application&bizId=" + a.get("appId")));
            }
            if (online && (retainedWork || Arrays.asList("review_passed", "interview_booked", "interview_done", "store_confirmed", "user_confirmed", "contract_signed", "onboarded")
                    .contains(st)))
            {
                // 线上任务：接单后在交付页提交成果、确认线下结算
                b.add(btn(closed || !settlement.isEmpty() ? "查看成果 / 结算" : "提交成果 / 结算", "primary", "/pages/jobs/deliver?appId=" + a.get("appId")));
            }
            out.add(o);
        }
        for (Map<String, Object> s : db.list("select o.order_no, o.status, o.check_in, o.check_out, o.nights, o.pay_fen, o.refund_fen, o.create_time, s.stay_name, s.city, s.area, s.cover, "
                + "(select count(*) from kg_review v where v.biz_type = 'stay_order' and v.biz_id = o.order_id and v.app_user_id = :u) reviewed, o.order_id, "
                + "(o.check_out >= date_sub(curdate(), interval 30 day)) review_open "
                + "from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id where o.app_user_id = :u and o.del_flag = '0' and o.status <> 'closed' order by o.order_id desc limit 50",
                p("u", me)))
        {
            String st = str(s.get("status"));
            // 未到店（过了离店日没入住）单独写「未到店」、灰色，仍归在「已完成」页签：之前和已完成写在一起，绿色「已完成」，和订单详情的「未到店」对不上
            String status = "pending_payment".equals(st) ? "待付款" : (Arrays.asList("confirmed", "checked_in").contains(st) ? "进行中"
                    : ("completed".equals(st) ? "已完成" : ("no_show".equals(st) ? "未到店" : "退款")));
            if ("cancelled".equals(st) && num(s.get("refundFen")) == 0)
            {
                status = "已取消";
            }
            Map<String, Object> o = order("住宿", "bed", "#4F7A66", status, "待付款".equals(status) ? "ochre" : ("进行中".equals(status) ? "indigo" : ("已完成".equals(status) ? "green" : "gray")),
                    str(s.get("stayName")), locText(s.get("city"), s.get("area")),
                    md(s.get("checkIn")) + "—" + md(s.get("checkOut")) + "（" + num(s.get("nights")) + "晚）", yuan(num(s.get("payFen"))), "元", str(s.get("cover")), str(s.get("createTime")));
            List<Map<String, Object>> b = (List<Map<String, Object>>) o.get("btns");
            if ("pending_payment".equals(st) && com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.STAY_KEY))
            {
                b.add(btn("去支付", "primary", "/pages/stays/pay?orderNo=" + s.get("orderNo")));
            }
            b.add(btn("订单详情", "outline", "/pages/stays/order?orderNo=" + s.get("orderNo")));
            // 已支付待入住：直接打开到店出示的核销码（之前列表上只有「订单详情」，顾客找不到核销码）
            if ("confirmed".equals(st))
            {
                b.add(btn("核销码", "primary", "/pages/stays/order?orderNo=" + s.get("orderNo") + "&view=verify"));
            }
            if ("completed".equals(st) && num(s.get("payFen")) > 0)
            {
                b.add(btn("申请退款 / 进度", "outline", "/pages/stays/order?orderNo=" + s.get("orderNo") + "&view=refund"));
            }
            // 评价期 30 天，和「待评价」(reviewsTodo) 同一条件：之前超期的订单仍显示「去评价」，点进去才被拦下
            if ("completed".equals(st) && num(s.get("reviewed")) == 0 && num(s.get("reviewOpen")) == 1)
            {
                b.add(btn("去评价", "primary", "/pages/profile/review?bizType=stay_order&bizId=" + s.get("orderId")));
            }
            out.add(o);
        }
        for (Map<String, Object> s : db.list("select o.order_no, o.status, o.use_date, o.time_slot, o.pay_fen, o.qty, o.create_time, o.service_snapshot, st.store_name, st.city "
                + "from kg_shop_order o left join kg_store st on st.store_id = o.store_id where o.app_user_id = :u and o.del_flag = '0' and o.status <> 'closed' "
                + "order by o.order_id desc limit 50", p("u", me)))
        {
            String st = str(s.get("status"));
            Map<String, Object> snap = com.ruoyi.kangaroo.ext.stay.KgStayOrderService.json(s.get("serviceSnapshot"));
            String status = "pending_payment".equals(st) ? "待付款" : ("unused".equals(st) ? "进行中" : ("used".equals(st) ? "已完成" : "退款"));
            Map<String, Object> o = order("二消", "shopping-bag", "#6B635C", status, "待付款".equals(status) ? "ochre" : ("进行中".equals(status) ? "indigo" : ("已完成".equals(status) ? "green" : "gray")),
                    snap.get("serviceName") + " × " + s.get("qty"), locText(s.get("city"), s.get("storeName")), md(s.get("useDate")) + " " + s.get("timeSlot"),
                    yuan(num(s.get("payFen"))), "元", str(snap.get("cover")), str(s.get("createTime")));
            ((List<Map<String, Object>>) o.get("btns")).add(btn("查看订单", "outline", "/pages/stays/shop?orderNo=" + s.get("orderNo")));
            out.add(o);
        }
        for (Map<String, Object> g : db.list("select g.signup_id, g.status, g.fee_fen, g.create_time, a.activity_id, a.title, a.city, a.place, a.cover, a.start_time, a.fee_mode, "
                + "coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) <= now() ended, (select count(*) from kg_review v where v.biz_type = 'activity_signup' "
                + "and v.biz_id = g.signup_id and v.app_user_id = :u) reviewed, (a.start_time >= date_sub(now(), interval 30 day)) review_open, "
                // 会员规则开着时只有签到过的会员培训活动能评价（和待评价、提交校验同一口径）
                + (memberOn() ? "(case when " + KgMembershipRewards.ACTIVITY_REVIEWABLE_SQL + " then 1 else 0 end)" : "1") + " review_ok "
                + "from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id "
                + "where g.app_user_id = :u and g.status <> 'closed' and " + com.ruoyi.kangaroo.ext.community.KgActivityService.VISIBLE_SIGNUP_SQL
                + " order by g.signup_id desc limit 50", p("u", me)))
        {
            String st = str(g.get("status"));
            boolean ended = num(g.get("ended")) == 1;
            String status = "pending_payment".equals(st) ? "待付款" : (Arrays.asList("confirmed", "waitlist").contains(st) ? (ended && "confirmed".equals(st) ? "已完成" : "进行中")
                    : ("cancelled".equals(st) ? "已取消" : "退款"));
            Map<String, Object> o = order("活动", "tent", "#6B635C", status, "待付款".equals(status) ? "ochre" : ("进行中".equals(status) ? "indigo" : ("已完成".equals(status) ? "green" : "gray")),
                    str(g.get("title")), locText(g.get("city"), g.get("place")), StaffSupport.fmt(StaffSupport.parse(g.get("startTime")), "M.d HH:mm"),
                    // 没在线付钱的报名：AA制写「AA制」（2026-11-03），其余写「免费」（到场支付的商家 / 用户活动报名记录里也是 0）
                    num(g.get("feeFen")) == 0 ? ("aa".equals(str(g.get("feeMode"))) ? "AA制" : "免费") : yuan(num(g.get("feeFen"))), num(g.get("feeFen")) == 0 ? "" : "元", str(g.get("cover")), str(g.get("createTime")));
            List<Map<String, Object>> b = (List<Map<String, Object>>) o.get("btns");
            b.add(btn("pending_payment".equals(st) ? "去支付" : "查看报名", "pending_payment".equals(st) ? "primary" : "outline", "/pages/community/activity?signupId=" + g.get("signupId")));
            o.put("signupId", g.get("signupId"));
            if (Arrays.asList("cancelled", "refunded").contains(st)) {
                Map<String, Object> hide = btn("移除记录", "outline", null);
                hide.put("action", "hideActivitySignup");
                b.add(hide);
            }
            if ("已完成".equals(status) && num(g.get("reviewed")) == 0 && num(g.get("reviewOpen")) == 1 && num(g.get("reviewOk")) == 1)
            {
                b.add(btn("去评价", "primary", "/pages/profile/review?bizType=activity_signup&bizId=" + g.get("signupId")));
            }
            out.add(o);
        }
        out.sort((x, y) -> str(y.get("sortKey")).compareTo(str(x.get("sortKey"))));
        if (tab != null && !tab.isEmpty() && !"全部".equals(tab))
        {
            List<Map<String, Object>> f = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> o : out)
            {
                if (tab.equals(o.get("status")) || ("已完成".equals(tab) && Arrays.asList("已结束", "未到店").contains(o.get("status")))
                        || ("退款".equals(tab) && "已取消".equals(o.get("status"))))
                {
                    f.add(o);
                }
            }
            return f;
        }
        return out;
    }

    // ---------------------------------------------------------------- 评价

    /** 会员规则开着没有 */
    private boolean memberOn()
    {
        return memberRewards != null && memberRewards.enabled();
    }

    /** 报名能评价的口径（a=kg_application，j=kg_job）：和提交校验同一条（KgMembershipRewards），列出来的就能评 */
    private String appReviewableSql()
    {
        return memberOn() ? KgMembershipRewards.APP_REVIEWABLE_SQL : KgMembershipRewards.APP_REVIEWABLE_LEGACY_SQL;
    }

    /** 待评价：已完成 30 天内、还没评价的报名 / 住宿 / 活动 */
    public List<Map<String, Object>> reviewsTodo(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.app_id, a.store_id, a.update_time, j.title, j.job_type, j.cover, s.store_name from kg_application a join kg_job j on j.job_id = a.job_id "
                + "left join kg_store s on s.store_id = a.store_id where a.app_user_id = :u and " + appReviewableSql() + " and a.del_flag = '0' "
                + "and not exists (select 1 from kg_review v where v.biz_type = 'application' and v.biz_id = a.app_id and v.app_user_id = :u) order by a.app_id desc limit 20", p("u", me)))
        {
            out.add(todo("application", num(a.get("appId")), str(a.get("storeName")), "volunteer".equals(str(a.get("jobType"))) ? "换工" : "岗位", str(a.get("title")),
                    str(a.get("cover")), a.get("updateTime")));
        }
        for (Map<String, Object> o : db.list("select o.order_id, o.check_out, o.checked_out_time, s.stay_name, s.cover, st.store_name from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id "
                + "left join kg_store st on st.store_id = o.store_id where o.app_user_id = :u and o.status = 'completed' and o.check_out >= date_sub(curdate(), interval 30 day) "
                + "and not exists (select 1 from kg_review v where v.biz_type = 'stay_order' and v.biz_id = o.order_id and v.app_user_id = :u) order by o.order_id desc limit 20", p("u", me)))
        {
            Map<String, Object> t = todo("stay_order", num(o.get("orderId")), str(o.get("storeName")), "住宿", str(o.get("stayName")), str(o.get("cover")), o.get("checkOut"));
            // 提前退房的按实际退房日写「完成于」（截止仍按原离店日算，和提交校验一致）
            java.util.Date left = StaffSupport.parse(o.get("checkedOutTime"));
            if (left != null)
            {
                t.put("time", "完成于 " + StaffSupport.fmt(left, "MM-dd"));
            }
            out.add(t);
        }
        // 会员规则开着时提交校验只收签到过的会员培训活动，这里同一口径，不列评不了的
        for (Map<String, Object> g : db.list("select g.signup_id, a.title, a.cover, a.organizer_name, a.start_time from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id "
                + "where g.app_user_id = :u and g.status = 'confirmed' and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) <= now() "
                + (memberOn() ? "and " + KgMembershipRewards.ACTIVITY_REVIEWABLE_SQL + " " : "")
                + "and a.start_time >= date_sub(now(), interval 30 day) and not exists (select 1 from kg_review v where v.biz_type = 'activity_signup' and v.biz_id = g.signup_id "
                + "and v.app_user_id = :u) order by g.signup_id desc limit 20", p("u", me)))
        {
            out.add(todo("activity_signup", num(g.get("signupId")), str(g.get("organizerName")), "活动", str(g.get("title")), str(g.get("cover")), g.get("startTime")));
        }
        return out;
    }

    private static Map<String, Object> todo(String bizType, long bizId, String name, String sub, String title, String image, Object at)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("bizType", bizType);
        m.put("bizId", bizId);
        m.put("name", name);
        m.put("sub", sub);
        m.put("title", title);
        m.put("image", image);
        java.util.Date d = StaffSupport.parse(at);
        m.put("time", d == null ? "" : "完成于 " + StaffSupport.fmt(d, "MM-dd"));
        m.put("deadline", d == null ? "" : "评价截止 " + StaffSupport.fmt(new java.util.Date(d.getTime() + 30L * 86400000L), "MM-dd"));
        return m;
    }

    public List<Map<String, Object>> reviewsMine(Long me, boolean featuredOnly)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        // upheld_reports：举报已成立的条数。status='1' 既可能是「含联系方式、待平台复核」，也可能是平台判定举报成立后隐藏的，只能靠它区分
        for (Map<String, Object> r : db.list("select v.*, s.store_name, s.cover, (select count(*) from kg_report rp where rp.target_type = 'review' and rp.target_id = v.review_id "
                + "and rp.status = 'upheld' and rp.del_flag = '0') upheld_reports from kg_review v left join kg_store s on s.store_id = v.store_id where v.app_user_id = :u "
                + "and v.del_flag = '0'" + (featuredOnly ? " and v.featured = '1'" : "") + " order by v.review_id desc limit 50", p("u", me)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("reviewId", r.get("reviewId"));
            m.put("name", r.get("storeName"));
            m.put("sub", "application".equals(str(r.get("bizType"))) ? "岗位" : ("stay_order".equals(str(r.get("bizType"))) ? "住宿" : "活动"));
            m.put("title", StaffSupport.cut(str(r.get("content")), 30));
            // 带图评价先用自己的第一张图，没有再用门店封面（门店没封面时之前一律是空）
            String firstImage = str(r.get("images")).split(",")[0].trim();
            m.put("image", firstImage.isEmpty() ? r.get("cover") : firstImage);
            m.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "yyyy-MM-dd"));
            m.put("deadline", "1".equals(str(r.get("status"))) ? (r.get("upheldReports") instanceof Number && ((Number) r.get("upheldReports")).longValue() > 0 ?"已被平台隐藏" : "已隐藏（待平台复核）")
                    : (r.get("reply") == null ? "★ " + r.get("rating") : "商家已回复"));
            m.put("rating", r.get("rating"));
            out.add(m);
        }
        return out;
    }

    /** 评价最多附几张图、拼起来不能超过 kg_review.images 列宽 */
    static final int REVIEW_IMAGES_MAX = 9;
    static final int REVIEW_IMAGES_LEN = 1000;

    /**
     * 评价附图（2026-10-17 改版 R2，住宿评价页先用）：body.images = [平台上传接口给的 /profile/upload/… 路径]，最多 9 张，
     * 也认逗号分隔的字符串；空的跳过、重复的只留一张。别处的网址、带 .. 的路径一律 422，不悄悄丢掉。
     * 没带这个字段（旧版小程序）或一张都没有返回 null，images 列照旧空着。
     */
    static String reviewImages(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        List<Object> items = new ArrayList<Object>();
        if (raw instanceof List)
        {
            items.addAll((List<?>) raw);
        }
        else if (raw instanceof String)
        {
            items.addAll(Arrays.asList(((String) raw).split(",")));
        }
        else
        {
            throw new ServiceException("评价图片格式不对，请重新上传", 422);
        }
        List<String> out = new ArrayList<String>();
        for (Object o : items)
        {
            String u = str(o).trim();
            if (u.isEmpty())
            {
                continue;
            }
            // 2026-10-17 R3：视频也在 /profile/upload/ 下（upload/videos/），不能混进图片里（页面会画成一张打不开的图）
            if (!com.ruoyi.kangaroo.domain.JobImages.isUpload(u) || com.ruoyi.kangaroo.ext.core.KgVideoUploads.isPlatformVideo(u))
            {
                throw new ServiceException("评价图片只能用本平台上传的图片", 422);
            }
            if (!out.contains(u))
            {
                out.add(u);
            }
        }
        if (out.size() > REVIEW_IMAGES_MAX)
        {
            throw new ServiceException("评价最多附 " + REVIEW_IMAGES_MAX + " 张图片", 422);
        }
        String joined = String.join(",", out);
        if (joined.length() > REVIEW_IMAGES_LEN)
        {
            throw new ServiceException("图片太多了，请删掉几张再提交", 422);
        }
        return out.isEmpty() ? null : joined;
    }

    /** 评价最多附几段视频（2026-10-17 R3，住宿评价页先用）；拼起来不能超过 kg_review.videos 列宽 */
    static final int REVIEW_VIDEOS_MAX = 1;
    static final int REVIEW_VIDEOS_LEN = 600;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgVideoUploads videoUploads;

    /**
     * 评价视频（2026-10-17 改版 R3）：body.videos = [平台视频上传接口给的 /profile/upload/videos/… 路径，或 {url, cover}]，最多 1 段；
     * cover 是视频封面（平台图片路径，选填；小程序选视频时给的缩略图先传成图片）。别处的网址、图片路径、不存在的文件一律 422。
     * 返回 [videos 列, video_covers 列]；没带或一段都没有返回 null，两列照旧空着（旧版小程序不带这个字段）
     */
    String[] reviewVideos(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        List<Object> items = new ArrayList<Object>();
        if (raw instanceof List)
        {
            items.addAll((List<?>) raw);
        }
        else if (raw instanceof String)
        {
            items.addAll(Arrays.asList(((String) raw).split(",")));
        }
        else
        {
            throw new ServiceException("评价视频格式不对，请重新上传", 422);
        }
        List<String> urls = new ArrayList<String>();
        List<String> covers = new ArrayList<String>();
        for (Object o : items)
        {
            String u;
            String cover = "";
            if (o instanceof Map)
            {
                u = str(((Map<?, ?>) o).get("url")).trim();
                cover = str(((Map<?, ?>) o).get("cover")).trim();
            }
            else
            {
                u = str(o).trim();
            }
            if (u.isEmpty())
            {
                continue;
            }
            if (!com.ruoyi.kangaroo.ext.core.KgVideoUploads.isPlatformVideo(u) || (videoUploads != null && !videoUploads.exists(u)))
            {
                throw new ServiceException("评价视频只能用本平台上传的视频，请重新上传", 422);
            }
            if (!cover.isEmpty() && (!com.ruoyi.kangaroo.domain.JobImages.isUpload(cover) || com.ruoyi.kangaroo.ext.core.KgVideoUploads.isPlatformVideo(cover)))
            {
                throw new ServiceException("视频封面只能用本平台上传的图片", 422);
            }
            if (!urls.contains(u))
            {
                urls.add(u);
                covers.add(cover);
            }
        }
        if (urls.size() > REVIEW_VIDEOS_MAX)
        {
            throw new ServiceException("评价最多附 " + REVIEW_VIDEOS_MAX + " 段视频", 422);
        }
        if (urls.isEmpty())
        {
            return null;
        }
        String v = String.join(",", urls);
        String c = String.join(",", covers);
        if (v.length() > REVIEW_VIDEOS_LEN || c.length() > REVIEW_VIDEOS_LEN)
        {
            throw new ServiceException("视频太多了，请删掉再提交", 422);
        }
        return new String[] { v, c };
    }

    /** 写评价：岗位 4 个分项（兼容旧版 5 项），住宿 / 活动 5 个分项（1–5 星）+ 文字 + 可选附图（最多 9 张）+ 可选视频（最多 1 段，2026-10-17 R3）；评分取分项平均；含联系方式等的先隐藏待复核 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> writeReview(Long me, Map<String, Object> b)
    {
        String bizType = str(b.get("bizType"));
        if(memberRewards != null && memberRewards.enabled()) {
            memberRewards.requireReviewAllowed(me);
            if(!memberRewards.reviewable(me,bizType,KgJdbc.asLong(b.get("bizId")))) throw new ServiceException("完成实际服务或入住后才可评价",409);
        }
        Long bizId = KgJdbc.asLong(b.get("bizId"));
        Map<String, Object> target;
        if ("application".equals(bizType))
        {
            target = db.one("select a.store_id, a.job_id target_ref from kg_application a where a.app_id = :id and a.app_user_id = :u and a.status in ('onboarded','completed')",
                    p("id", bizId, "u", me));
        }
        else if ("stay_order".equals(bizType))
        {
            target = db.one("select o.store_id, o.stay_id target_ref from kg_stay_order o where o.order_id = :id and o.app_user_id = :u and o.status = 'completed'", p("id", bizId, "u", me));
        }
        else if ("activity_signup".equals(bizType))
        {
            target = db.one("select a.store_id, a.activity_id target_ref from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id where g.signup_id = :id "
                    + "and g.app_user_id = :u and g.status = 'confirmed' and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) <= now()", p("id", bizId, "u", me));
        }
        else
        {
            throw new ServiceException("评价对象不合法", 422);
        }
        if (target == null)
        {
            throw new ServiceException("只能评价自己已完成的岗位 / 住宿 / 活动", 403);
        }
        List<Integer> dims = new ArrayList<Integer>();
        if (b.get("dims") instanceof List)
        {
            for (Object o : (List<?>) b.get("dims"))
            {
                int v = (int) num(o);
                if (v < 1 || v > 5)
                {
                    throw new ServiceException("每一项都需要打 1–5 星", 422);
                }
                dims.add(v);
            }
        }
        if (dims.isEmpty() || dims.size() > 5 || ("application".equals(bizType) && dims.size() != 4 && dims.size() != 5))
        {
            throw new ServiceException("请完成评分", 422);
        }
        String content = str(b.get("content")).trim();
        if (content.length() > 1000)
        {
            throw new ServiceException("评价内容不超过 1000 字", 422);
        }
        String images = reviewImages(b.get("images"));
        String[] videos = reviewVideos(b.get("videos"));
        String level = guard.check(content);
        double avg = dims.stream().mapToInt(Integer::intValue).average().orElse(5);
        String targetType = "application".equals(bizType) ? "store" : ("stay_order".equals(bizType) ? "room" : "activity");
        Long targetId = "store".equals(targetType) ? KgJdbc.asLong(target.get("storeId")) : KgJdbc.asLong(target.get("targetRef"));
        if (targetId == null)
        {
            throw new ServiceException("评价对象不存在", 404);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("target_type", targetType);
        row.put("target_id", targetId);
        row.put("store_id", target.get("storeId"));
        row.put("app_user_id", me);
        row.put("biz_type", bizType);
        row.put("biz_id", bizId);
        row.put("rating", (int) Math.round(avg));
        row.put("content", content);
        row.put("dims", dims.toString());
        if (images != null)
        {
            row.put("images", images);
        }
        if (videos != null)
        {
            row.put("videos", videos[0]);
            row.put("video_covers", videos[1]);
        }
        row.put("anonymous", "false".equals(str(b.get("anonymous"))) ? "0" : "1");
        row.put("status", "review".equals(level) ? "1" : "0");
        row.put("create_by", "app:" + me);
        row.put("create_time", new Date());
        long reviewId;
        try
        {
            reviewId = db.insert("kg_review", row);
        }
        catch (org.springframework.dao.DuplicateKeyException e)
        {
            throw new ServiceException("你已经评价过了", 409);
        }
        if ("0".equals(row.get("status")))
        {
            if ("room".equals(targetType))
            {
                reviewStats.refreshStay(targetId);
            }
            reviewStats.refreshStore(KgJdbc.asLong(target.get("storeId")));
        }
        if (target.get("storeId") != null)
        {
            support.notifyStore(KgJdbc.asLong(target.get("storeId")), "system", "收到新的评价", "★ " + Math.round(avg) + "  " + StaffSupport.cut(content, 40), "review", bizId,
                    "/pages/staff/reviews?reviewId=" + reviewId);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if(memberRewards != null && memberRewards.enabled())memberRewards.sync(me);
        m.put("message", "review".equals(level) ? "评价已提交，包含联系方式等内容，平台复核后展示" : "评价已发布，感谢你的真实分享");
        return m;
    }

    /** 5 星到 1 星各占几成（percent 和 width 同一个值，给评分分布条用）；agg 里是 s5…s1 各几条，n 是总条数 */
    private static List<Map<String, Object>> starRows(Map<String, Object> agg, long n)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int star = 5; star >= 1; star--)
        {
            long c = agg == null ? 0 : num(agg.get("s" + star));
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("star", star);
            r.put("percent", n == 0 ? "0%" : Math.round(c * 100.0 / n) + "%");
            r.put("width", n == 0 ? "0%" : Math.round(c * 100.0 / n) + "%");
            rows.add(r);
        }
        return rows;
    }

    /** 门店评价汇总：平均分、分布、按类型 / 有图筛选、最新 / 最高排序；匿名评价不露昵称 */
    public Map<String, Object> storeReviews(Long storeId, String filter, String sort)
    {
        return storeReviews(storeId, filter, sort, 0, 1, null);
    }

    public Map<String, Object> storeReviews(Long storeId, String filter, String sort, int days, int pageNum, Long stayId)
    {
        if (!(days == 0 || days == 30 || days == 90 || days == 365) || pageNum < 1 || pageNum > 10000)
            throw new ServiceException("评价筛选参数不合法", 422);
        Map<String, Object> store = db.one("select store_id, store_name, city, cover, safe_cert, intro from kg_store where store_id = :s and del_flag = '0'", p("s", storeId));
        if (store == null)
        {
            throw new ServiceException("门店不存在", 404);
        }
        // 「换工」「兼职」「全职」要按报名对应岗位的类型再分一次，否则几个页签是同一批评价（2026-09-26 回归发现；
        // 2026-10-19 加「全职」：岗位详情「用户反馈」只拿同类岗位的评价，全职岗位也要有对应的一批）
        String byJobType = ("换工".equals(filter) || "义工".equals(filter)) ? "volunteer" : ("兼职".equals(filter) ? "parttime" : ("全职".equals(filter) ? "fulltime" : null));
        String cond = byJobType != null ? " and v.biz_type = 'application' and exists (select 1 from kg_application ja join kg_job jj on jj.job_id = ja.job_id "
                + "where ja.app_id = v.biz_id and jj.job_type = '" + byJobType + "')"
                : "application".equals(filter) ? " and v.biz_type = 'application'"
                :("住宿".equals(filter) ? " and v.biz_type = 'stay_order'" : ("有图".equals(filter) ? " and v.images is not null and v.images <> ''" : ""));
        if (days > 0) cond += " and v.create_time >= date_sub(now(), interval :days day)";
        if (stayId != null) cond += " and v.target_type='room' and v.target_id=:stay";
        Map<String, Object> reviewParams = p("s", storeId, "days", days, "stay", stayId, "offset", (pageNum - 1) * 50);
        long filteredTotal = db.count("select count(*) from kg_review v where v.store_id=:s and v.status='0' and v.del_flag='0'" + cond, reviewParams);
        // 当前筛选这一批评价自己的平均分和星级分布（2026-10-19）：岗位详情「用户反馈」按同类岗位筛，点「查看全部」进来带同一个筛选，
        // 两边的分数、条数、分布都按这一批算才对得上。不带任何筛选时就是全店那一批（和下面的 avg / rows 相同）
        Map<String, Object> fagg = db.one("select round(avg(v.rating), 1) avg, sum(v.rating = 5) s5, sum(v.rating = 4) s4, sum(v.rating = 3) s3, sum(v.rating = 2) s2, sum(v.rating = 1) s1 "
                + "from kg_review v where v.store_id = :s and v.status = '0' and v.del_flag = '0'" + cond, reviewParams);
        Object filteredAvg = fagg == null ? null : fagg.get("avg");
        Map<String, Object> agg = db.one("select count(*) n, round(avg(rating), 1) avg, sum(rating = 5) s5, sum(rating = 4) s4, sum(rating = 3) s3, sum(rating = 2) s2, sum(rating = 1) s1 "
                + "from kg_review where store_id = :s and status = '0' and del_flag = '0'", p("s", storeId));
        long n = num(agg.get("n"));
        List<Map<String, Object>> rows = starRows(agg, n);
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> v : db.list("select v.*, u.nickname, u.avatar, j.title job_title, j.job_type, a.stay_days from kg_review v left join kg_app_user u on u.user_id = v.app_user_id "
                + "left join kg_application a on v.biz_type = 'application' and a.app_id = v.biz_id left join kg_job j on j.job_id = a.job_id "
                + "where v.store_id = :s and v.status = '0' and v.del_flag = '0'" + cond + " order by " + ("high".equals(sort) ? "v.rating desc, v.review_id desc" : "v.featured desc, v.review_id desc")
                + " limit :offset, 50", reviewParams))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            boolean anon = "1".equals(str(v.get("anonymous")));
            m.put("name", anon ? "匿名用户" : (str(v.get("nickname")).isEmpty() ? "用户" : v.get("nickname")));
            m.put("avatar", anon ? "" : v.get("avatar"));
            m.put("tag", "application".equals(str(v.get("bizType"))) ? ("volunteer".equals(str(v.get("jobType"))) ? "换工" : "岗位") + (v.get("jobTitle") == null ? "" : "｜" + v.get("jobTitle"))
                    : ("stay_order".equals(str(v.get("bizType"))) ? "住宿" : "活动"));
            m.put("rating", v.get("rating"));
            m.put("date", StaffSupport.fmt(StaffSupport.parse(v.get("createTime")), "yyyy.MM.dd"));
            m.put("content", v.get("content"));
            m.put("images", KgJdbc.asStr(v.get("images")) == null ? new ArrayList<String>() : Arrays.asList(str(v.get("images")).split(",")));
            m.put("reply", v.get("reply"));
            m.put("replyDate", v.get("replyTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(v.get("replyTime")), "yyyy.MM.dd"));
            m.put("reviewId", v.get("reviewId"));
            m.put("stayDays", v.get("stayDays"));
            m.put("featured", "1".equals(str(v.get("featured"))));
            list.add(m);
        }
        // 旧五项按语义映射，新岗位评价使用位置、氛围、工作、吃住。
        List<Map<String, Object>> jobScores = db.list("select dims, rating from kg_review where store_id = :s and biz_type = 'application' and status = '0' and del_flag = '0'", p("s", storeId));
        int apps = jobScores.size();
        int good = (int) jobScores.stream().filter(r -> num(r.get("rating")) >= 4).count();
        List<Map<String, Object>> dims = KgJobReviewDimensions.aggregate(jobScores);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("store", store);
        m.put("count", n);
        m.put("avg", agg.get("avg"));
        m.put("rows", rows);
        m.put("list", list);
        m.put("filteredTotal", filteredTotal);
        m.put("hasMore", (long) pageNum * 50 < filteredTotal);
        m.put("filteredAvg", filteredAvg);
        m.put("filteredRows", starRows(fagg, filteredTotal));
        m.put("filteredSummary", cond.isEmpty() ? (n == 0 ? "这家店还没有评价。" : "共 " + n + " 条评价，平均 " + agg.get("avg") + " 分（按真实评价统计，不做 AI 摘要）。")
                : (filteredTotal == 0 ? "没有符合当前筛选的评价。" : "当前筛选共 " + filteredTotal + " 条评价，平均 " + filteredAvg + " 分（按真实评价统计，不做 AI 摘要）。"));
        m.put("jobs", db.count("select count(*) from kg_job where store_id = :s and status = 'published' and del_flag = '0'", p("s", storeId)));
        m.put("summary", n == 0 ? "这家店还没有评价。" : "共 " + n + " 条评价，平均 " + agg.get("avg") + " 分（按真实评价统计，不做 AI 摘要）。");
        m.put("jobDims", dims);
        m.put("jobReviewCount", apps);
        m.put("jobGoodRate", apps == 0 ? null : Math.round(good * 100.0 / apps) + "%");
        return m;
    }

    // ---------------------------------------------------------------- 提醒设置 / 体验官

    static final String[][] PREFS = {
        { "governance_case", "权益与合作复核", "复核通知、申诉和处理结果", "shield-check", "#A87542" },
        { "mediation", "纠纷调解", "接单、裁决与申诉结果更新", "scale", "#A87542" },
        { "mediation_jury", "大众评审", "邀请你独立评议已脱敏的材料", "shield-check", "#4F7A66" },
        { "job_new", "新机会提醒", "订阅的城市和岗位有新机会时通知你", "bell", "#C99043" },
        { "apply_progress", "报名进度", "审核、面试、录用等节点变化时通知你", "clipboard-check", "#4F7A66" },
        { "activity", "活动提醒", "报名的活动集合提醒、变更通知", "calendar-days", "#40576B" },
        { "order", "订单与退款", "支付、核销、退款进度", "wallet-cards", "#6B635C" },
        { "review", "评价邀请", "完成后邀请你写评价", "message-square", "#9A6A22" },
        { "community", "社区互动", "评论、赞、私信提醒", "users", "#4F7A66" } };

    public Map<String, Object> prefs(Long me)
    {
        Map<String, Map<String, Object>> saved = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : db.list("select pref_key, enabled, freq from kg_notify_pref where app_user_id = :u", p("u", me)))
        {
            saved.put(str(r.get("prefKey")), r);
        }
        List<Map<String, Object>> on = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> off = new ArrayList<Map<String, Object>>();
        for (String[] d : PREFS)
        {
            Map<String, Object> s = saved.get(d[0]);
            boolean enabled = s == null || "1".equals(str(s.get("enabled")));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("key", d[0]);
            m.put("title", d[1]);
            m.put("desc", d[2]);
            m.put("icon", d[3]);
            m.put("color", d[4]);
            m.put("on", enabled);
            // 只有新机会提醒有定时推送，才区分即时 / 每日汇总；其余类别都是事件发生时写入消息中心
            boolean freqEditable = "job_new".equals(d[0]);
            m.put("freqEditable", freqEditable);
            m.put("freq", freqEditable ? subFreq(me) : "即时");
            (enabled ? on : off).add(m);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("active", on);
        m.put("paused", off);
        m.put("note", "提醒写入小程序消息中心；暂停后这类消息不再发给你（审核结果、客服回复等系统通知不受影响）。微信服务通知尚未接入。");
        return m;
    }

    /** 新机会提醒的频率就是岗位订阅的频率（订阅推送按订阅上的 freq 跑），这里汇总展示 */
    private String subFreq(Long me)
    {
        List<Map<String, Object>> rows = db.list("select freq, count(*) n from kg_job_subscribe where app_user_id = :u and status = '0' group by freq", p("u", me));
        if (rows.isEmpty())
        {
            return "未订阅";
        }
        if (rows.size() > 1)
        {
            return "按订阅设置";
        }
        return "daily".equals(str(rows.get(0).get("freq"))) ? "每日汇总" : "即时";
    }

    public Map<String, Object> setPref(Long me, String key, Boolean enabled, String freq)
    {
        boolean known = false;
        for (String[] d : PREFS)
        {
            known = known || d[0].equals(key);
        }
        if (!known)
        {
            throw new ServiceException("提醒类型不合法", 422);
        }
        if (freq != null && !"job_new".equals(key))
        {
            throw new ServiceException("这类提醒在事件发生时送达，不支持每日汇总", 422);
        }
        if (freq != null && db.count("select count(*) from kg_job_subscribe where app_user_id = :u", p("u", me)) == 0)
        {
            throw new ServiceException("还没有岗位订阅，先在筛选页订阅城市或岗位", 422);
        }
        db.update("insert into kg_notify_pref (app_user_id, pref_key, enabled, freq, update_time) values (:u, :k, :e, :f, now()) "
                + "on duplicate key update enabled = coalesce(:e2, enabled), freq = coalesce(:f2, freq), update_time = now()",
                p("u", me, "k", key, "e", enabled == null || enabled ? "1" : "0", "f", "daily".equals(freq) ? "daily" : "instant",
                        "e2", enabled == null ? null : (enabled ? "1" : "0"), "f2", freq == null ? null : ("daily".equals(freq) ? "daily" : "instant")));
        if (freq != null)
        {
            db.update("update kg_job_subscribe set freq = :f where app_user_id = :u", p("f", "daily".equals(freq) ? "daily" : "instant", "u", me));
        }
        return prefs(me);
    }

    // ---------------------------------------------------------------- 通知偏好（提醒页设置屏）

    /** 免打扰配置存在 kg_notify_pref 的 quiet 这一行：enabled = 开关，freq = "22:00-08:00" */
    static final String QUIET_KEY = "quiet";

    static final String QUIET_DEFAULT = "22:00-08:00";

    /** 员工身份的开关统一加 staff: 前缀，和用户身份的开关分开存 */
    static final String STAFF_PREFIX = "staff:";

    public boolean inQuietHours(Long me)
    {
        Map<String, Object> r = db.one("select enabled, freq from kg_notify_pref where app_user_id = :u and pref_key = :k", p("u", me, "k", QUIET_KEY));
        if (r == null || !"1".equals(str(r.get("enabled"))))
        {
            return false;
        }
        String[] w = quietWindow(str(r.get("freq")));
        int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        int from = minutes(w[0]);
        int to = minutes(w[1]);
        return from <= to ? now >= from && now < to : now >= from || now < to;
    }

    static String[] quietWindow(String v)
    {
        String s = v == null || !v.matches("\\d{2}:\\d{2}-\\d{2}:\\d{2}") ? QUIET_DEFAULT : v;
        return new String[] { s.substring(0, 5), s.substring(6) };
    }

    static int minutes(String hhmm)
    {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
    }

    /**
     * 通知偏好设置屏的数据。
     *
     * 三列的真实情况各不相同，返回体里如实标出来，页面据此决定哪列可点：
     *   小程序消息 —— 真开关，关掉后这类消息不再写进消息中心；
     *   员工通知   —— 真开关，只对有员工授权的账号有意义，没有授权时整列不可用；
     *   微信订阅   —— 微信订阅消息尚未接入，这一列现在只是占位，不可修改。
     */
    public Map<String, Object> notifySettings(Long me)
    {
        Map<String, Map<String, Object>> saved = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : db.list("select pref_key, enabled, freq from kg_notify_pref where app_user_id = :u", p("u", me)))
        {
            saved.put(str(r.get("prefKey")), r);
        }
        boolean staff = db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0'", p("u", me)) > 0;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String[] d : PREFS)
        {
            Map<String, Object> a = saved.get(d[0]);
            Map<String, Object> b2 = saved.get(STAFF_PREFIX + d[0]);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("key", d[0]);
            m.put("label", d[1]);
            m.put("desc", d[2]);
            m.put("icon", d[3]);
            m.put("color", d[4]);
            m.put("wechat", Boolean.FALSE);
            m.put("app", Boolean.valueOf(a == null || "1".equals(str(a.get("enabled")))));
            m.put("staff", Boolean.valueOf(staff && (b2 == null || "1".equals(str(b2.get("enabled"))))));
            rows.add(m);
        }
        Map<String, Object> quiet = saved.get(QUIET_KEY);
        String[] w = quietWindow(quiet == null ? null : str(quiet.get("freq")));
        Map<String, Object> q = new LinkedHashMap<String, Object>();
        q.put("on", Boolean.valueOf(quiet != null && "1".equals(str(quiet.get("enabled")))));
        q.put("start", w[0]);
        q.put("end", w[1]);

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rows", rows);
        m.put("freq", "daily".equals(subFreqRaw(me)) ? "daily" : "instant");
        m.put("freqAvailable", Boolean.valueOf(db.count("select count(*) from kg_job_subscribe where app_user_id = :u", p("u", me)) > 0));
        m.put("quiet", q);
        m.put("staffAvailable", Boolean.valueOf(staff));
        boolean wechat = subscribe != null && subscribe.enabled() && !subscribe.templates().isEmpty();
        m.put("wechatAvailable", Boolean.valueOf(wechat));
        m.put("note", (wechat
                ? "微信订阅是一次性授权：点一下「微信订阅」那一列授权后，我们只能给你发一条，发完需要再次授权。"
                : "微信订阅消息尚未开通，该列不可修改。")
                + "免打扰时段只影响「新机会提醒」这类主动推送，审核结果、退款进度等与你正在办的事直接相关的通知照常送达。");
        return m;
    }

    private String subFreqRaw(Long me)
    {
        Map<String, Object> r = db.one("select freq from kg_job_subscribe where app_user_id = :u and status = '0' order by sub_id limit 1", p("u", me));
        return r == null ? "instant" : str(r.get("freq"));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveNotifySettings(Long me, Map<String, Object> b)
    {
        Object rows = b == null ? null : b.get("rows");
        // 没有员工授权的账号不写 staff: 这半边：否则今天保存一次，明天被授予员工身份时
        // 会发现所有员工通知都是关的，而他从没关过
        boolean staff = db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0'", p("u", me)) > 0;
        if (rows instanceof List)
        {
            for (Object o : (List<?>) rows)
            {
                if (!(o instanceof Map))
                {
                    continue;
                }
                Map<?, ?> r = (Map<?, ?>) o;
                String key = str(r.get("key"));
                boolean known = false;
                for (String[] d : PREFS)
                {
                    known = known || d[0].equals(key);
                }
                if (!known)
                {
                    continue;
                }
                savePref(me, key, !Boolean.FALSE.equals(r.get("app")));
                if (staff)
                {
                    savePref(me, STAFF_PREFIX + key, !Boolean.FALSE.equals(r.get("staff")));
                }
            }
        }
        String freq = str(b == null ? null : b.get("freq"));
        if (!freq.isEmpty())
        {
            if (db.count("select count(*) from kg_job_subscribe where app_user_id = :u", p("u", me)) == 0)
            {
                throw new ServiceException("还没有岗位订阅，先在筛选页订阅城市或岗位后再设置频率", 422);
            }
            db.update("update kg_job_subscribe set freq = :f where app_user_id = :u", p("f", "daily".equals(freq) ? "daily" : "instant", "u", me));
            // 这里原先还有一句 savePref(me, "job_new", true)：只要有岗位订阅，每次保存设置都会把用户刚关掉的「新机会提醒」重新打开，
            // 用户根本暂停不了。改频率只改频率，开关以 rows 里用户的选择为准
            db.update("update kg_notify_pref set freq = :f, update_time = now() where app_user_id = :u and pref_key = 'job_new'",
                    p("f", "daily".equals(freq) ? "daily" : "instant", "u", me));
        }
        Object quiet = b == null ? null : b.get("quiet");
        if (quiet instanceof Map)
        {
            Map<?, ?> q = (Map<?, ?>) quiet;
            String start = str(q.get("start"));
            String end = str(q.get("end"));
            if (!start.matches("\\d{2}:\\d{2}") || !end.matches("\\d{2}:\\d{2}") || minutes(start) > 1439 || minutes(end) > 1439)
            {
                throw new ServiceException("免打扰时段格式不对", 422);
            }
            if (start.equals(end))
            {
                throw new ServiceException("免打扰的开始和结束时间不能相同", 422);
            }
            db.update("insert into kg_notify_pref (app_user_id, pref_key, enabled, freq, update_time) values (:u, :k, :e, :f, now()) "
                    + "on duplicate key update enabled = :e2, freq = :f2, update_time = now()",
                    p("u", me, "k", QUIET_KEY, "e", Boolean.TRUE.equals(q.get("on")) ? "1" : "0", "f", start + "-" + end,
                            "e2", Boolean.TRUE.equals(q.get("on")) ? "1" : "0", "f2", start + "-" + end));
        }
        Map<String, Object> out = notifySettings(me);
        out.put("message", "设置已保存");
        return out;
    }

    private void savePref(Long me, String key, boolean on)
    {
        db.update("insert into kg_notify_pref (app_user_id, pref_key, enabled, update_time) values (:u, :k, :e, now()) "
                + "on duplicate key update enabled = :e2, update_time = now()", p("u", me, "k", key, "e", on ? "1" : "0", "e2", on ? "1" : "0"));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> guideApply(Long me, Map<String, Object> b)
    {
        List<String> cities = new ArrayList<String>();
        if (b.get("cities") instanceof List)
        {
            for (Object o : (List<?>) b.get("cities"))
            {
                cities.add(StaffSupport.cut(str(o), 16));
            }
        }
        if (cities.isEmpty() || cities.size() > 5)
        {
            throw new ServiceException("请选择 1–5 个城市", 422);
        }
        String plan = str(b.get("plan")).trim();
        if (plan.length() < 10 || plan.length() > 200)
        {
            throw new ServiceException("创作计划写 10–200 字", 422);
        }
        String works = str(b.get("works")).trim();
        guard.check(plan, works);
        if (db.count("select count(*) from kg_guide_apply where app_user_id = :u and status = 'pending'", p("u", me)) > 0)
        {
            throw new ServiceException("你有一份申请正在审核中", 409);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me);
        row.put("cities", String.join(",", cities));
        row.put("plan", plan);
        row.put("works", StaffSupport.cut(works, 500));
        row.put("status", "pending");
        row.put("create_time", new Date());
        db.insert("kg_guide_apply", row);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message", "已提交，3 个工作日内审核，结果会发到消息中心");
        return m;
    }

    public Map<String, Object> guideStatus(Long me)
    {
        Map<String, Object> r = db.one("select status, audit_note, cities, create_time from kg_guide_apply where app_user_id = :u order by apply_id desc limit 1", p("u", me));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("latest", r);
        List<String> cities = new ArrayList<String>();
        for (Map<String, Object> c : db.list("select distinct a.city from kg_activity a where a.del_flag = '0' and a.status in ('open','closed','ended') "
                + "union select distinct s.city from kg_stay s where s.del_flag = '0' and s.status = 'online'", p()))
        {
            cities.add(str(c.get("city")));
        }
        m.put("cities", cities);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public String auditGuide(Long applyId, boolean pass, String note, String operator)
    {
        Map<String, Object> r = db.must("select app_user_id, status from kg_guide_apply where apply_id = :a", p("a", applyId), "申请不存在");
        if (!"pending".equals(str(r.get("status"))))
        {
            throw new ServiceException("只有待审核的申请可以处理");
        }
        if (!pass && (note == null || note.trim().isEmpty()))
        {
            throw new ServiceException("请填写不通过的原因");
        }
        db.update("update kg_guide_apply set status = :s, audit_note = :n, audited_by = :by, audited_time = now() where apply_id = :a",
                p("s", pass ? "approved" : "rejected", "n", StaffSupport.cut(note, 255), "by", operator, "a", applyId));
        support.notifyUser(KgJdbc.asLong(r.get("appUserId")), "system", pass ? "旅行体验官申请已通过" : "旅行体验官申请未通过", pass ? "欢迎加入，攻略发布入口会在社区开放。" : note,
                "guide_apply", applyId, "/pages/profile/orders?s=4");
        return pass ? "已通过" : "已驳回";
    }

    // ---------------------------------------------------------------- 到店路线

    /**
     * 到店路线：房源 / 门店的地址、坐标、照片与电话。不传参数时取最近要去的：进行中的住宿订单优先，其次是已录用的报名。
     * 路线规划交给微信内置地图（openLocation），这里不编造公交线路；门店电话只给在这家店有有效订单或录用的用户。
     */
    public Map<String, Object> route(Long me, Long storeId, Long stayId)
    {
        String visit = null;
        if (stayId == null && storeId == null)
        {
            Map<String, Object> o = db.one("select o.stay_id, o.check_in, o.check_out from kg_stay_order o where o.app_user_id = :u and o.del_flag = '0' "
                    + "and o.status in ('confirmed','checked_in') and o.check_out >= curdate() order by o.check_in limit 1", p("u", me));
            if (o != null)
            {
                stayId = KgJdbc.asLong(o.get("stayId"));
                visit = "入住 " + md(o.get("checkIn")) + "—" + md(o.get("checkOut"));
            }
            else
            {
                Map<String, Object> a = db.one("select a.store_id, a.arrival_date, j.title from kg_application a join kg_job j on j.job_id = a.job_id where a.app_user_id = :u "
                        + "and a.del_flag = '0' and a.status in ('store_confirmed','contract_signed','onboarded') order by a.app_id desc limit 1", p("u", me));
                if (a == null)
                {
                    throw new ServiceException("暂时没有需要到店的订单或录用", 404);
                }
                storeId = KgJdbc.asLong(a.get("storeId"));
                visit = str(a.get("title")) + (a.get("arrivalDate") == null ? "" : " · " + md(a.get("arrivalDate")) + " 到岗");
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        List<String> photos = new ArrayList<String>();
        if (stayId != null)
        {
            Map<String, Object> s = db.must("select s.stay_id, s.store_id, s.stay_name, s.city, s.area, s.address, s.latitude, s.longitude, s.cover, s.images, s.checkin_time, "
                    + "st.latitude store_lat, st.longitude store_lng, (select min((" + com.ruoyi.kangaroo.ext.stay.KgSettlementMath.saleSql("r.base_price_fen", "st.op_type") + ")) from kg_stay_room_type r where r.stay_id = s.stay_id and r.status = '0' "
                    + "and r.del_flag = '0') min_fen from kg_stay s left join kg_store st on st.store_id = s.store_id where s.stay_id = :id and s.del_flag = '0' and s.status = 'online'",
                    p("id", stayId), "房源不存在或已下架");
            storeId = KgJdbc.asLong(s.get("storeId"));
            m.put("kind", "stay");
            m.put("title", s.get("stayName"));
            m.put("cover", s.get("cover"));
            m.put("price", s.get("minFen") == null ? null : yuan(num(s.get("minFen"))));
            m.put("unit", s.get("minFen") == null ? null : "元/晚起");
            m.put("address", str(s.get("city")) + (str(s.get("area")).isEmpty() ? "" : " · " + s.get("area")) + (str(s.get("address")).isEmpty() ? "" : " · " + s.get("address")));
            m.put("latitude", s.get("latitude") != null ? s.get("latitude") : s.get("storeLat"));
            m.put("longitude", s.get("longitude") != null ? s.get("longitude") : s.get("storeLng"));
            m.put("hint", s.get("checkinTime") == null ? null : "入住时间 " + s.get("checkinTime") + " 后");
            for (String u : str(s.get("images")).split(","))
            {
                if (!u.trim().isEmpty() && photos.size() < 3)
                {
                    photos.add(u.trim());
                }
            }
        }
        else
        {
            Map<String, Object> st = db.must("select store_id, store_name, city, address, latitude, longitude, cover from kg_store where store_id = :s and del_flag = '0'",
                    p("s", storeId), "门店不存在");
            m.put("kind", "store");
            m.put("title", st.get("storeName"));
            m.put("cover", st.get("cover"));
            m.put("price", null);
            m.put("unit", null);
            m.put("address", str(st.get("city")) + (str(st.get("address")).isEmpty() ? "" : " · " + st.get("address")));
            m.put("latitude", st.get("latitude"));
            m.put("longitude", st.get("longitude"));
            m.put("hint", null);
            // 多数门店 / 房源的 cover 是空串，不能当照片返回（前端会画出一块空白图），只收非空且不重复的地址
            for (Map<String, Object> c : db.list("select cover from kg_stay where store_id = :s and del_flag = '0' and status = 'online' and cover is not null and cover <> '' limit 3", p("s", storeId)))
            {
                String u = str(c.get("cover")).trim();
                if (!u.isEmpty() && !photos.contains(u))
                {
                    photos.add(u);
                }
            }
            String sc = str(st.get("cover")).trim();
            if (photos.isEmpty() && !sc.isEmpty())
            {
                photos.add(sc);
            }
        }
        boolean related = storeId != null && (db.count("select count(*) from kg_stay_order where app_user_id = :u and store_id = :s and del_flag = '0' "
                + "and status in ('confirmed','checked_in','completed')", p("u", me, "s", storeId)) > 0
                || db.count("select count(*) from kg_application where app_user_id = :u and store_id = :s and del_flag = '0' "
                        + "and status in ('store_confirmed','contract_signed','onboarded','completed')", p("u", me, "s", storeId)) > 0);
        String phone = related ? KgJdbc.asStr(db.one("select contact_phone from kg_store where store_id = :s", p("s", storeId)).get("contactPhone")) : null;
        Map<String, Object> app = storeId == null ? null : db.one("select app_id from kg_application where app_user_id = :u and store_id = :s and del_flag = '0' "
                + "and status <> 'withdrawn' order by app_id desc limit 1", p("u", me, "s", storeId));
        m.put("storeId", storeId);
        m.put("stayId", stayId);
        m.put("appId", app == null ? null : app.get("appId"));
        m.put("visit", visit);
        m.put("photos", photos);
        m.put("phone", phone);
        m.put("phoneHint", related ? (phone == null ? "门店还没有登记电话，可以在消息里联系门店" : null) : "下单或录用后可查看门店电话，也可以在消息里联系门店");
        return m;
    }

    // ---------------------------------------------------------------- 同行队伍

    public Map<String, Object> team(Long me, Long jobId)
    {
        com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requirePrivateChat(db);
        // 与详情页共用可见性和名额口径：暂停招聘仍可分享，换宿报名成功即占位。
        KgJob job = jobMapper.selectKgJobByJobId(jobId);
        if (job == null || !JobStatus.visibleToApp(job.getStatus()))
            throw new ServiceException("岗位不存在或已下架", 404);
        Map<String, Object> store = db.one("select store_name from kg_store where store_id=:s and status='0' and del_flag='0'", p("s", job.getStoreId()));
        if (store == null) throw new ServiceException("岗位不存在或已下架", 404);
        Map<String, Object> j = p("jobId", job.getJobId(), "title", job.getTitle(), "city", job.getCity(),
                "cover", job.getCover(), "jobType", job.getJobType(), "headcount", job.getHeadcount(),
                "hiredCount", job.getHiredCount(), "storeId", job.getStoreId(), "boardType", job.getBoardType(),
                "tags", job.getTags(), "storeName", store.get("storeName"), "status", job.getStatus());
        long applied = db.count("select count(*) from kg_application where job_id = :j and del_flag = '0' and status not in ('withdrawn','review_rejected','terminated')", p("j", jobId));
        List<Map<String, Object>> members = new ArrayList<Map<String, Object>>();
        long longStay = 0;
        List<String> longAvatars = new ArrayList<String>();
        for (Map<String, Object> t : db.list("select t.app_user_id, u.nickname, u.avatar, r.city, a.stay_days from kg_team_member t join kg_app_user u on u.user_id = t.app_user_id "
                + "left join kg_resume r on r.app_user_id = t.app_user_id left join kg_application a on a.app_id = t.app_id where t.job_id = :j and t.visible = '1' and u.del_flag='0' and u.status='0' and a.del_flag='0' and a.status not in ('withdrawn','review_rejected','terminated') "
                + "and not exists (select 1 from kg_user_block b where (b.user_id = :me and b.blocked_id = t.app_user_id) or (b.user_id = t.app_user_id and b.blocked_id = :me)) "
                + "order by t.id", p("j", jobId, "me", me)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("userId", t.get("appUserId"));
            m.put("name", str(t.get("nickname")).isEmpty() ? "用户" + t.get("appUserId") : t.get("nickname"));
            m.put("avatar", t.get("avatar"));
            long days = num(t.get("stayDays"));
            m.put("meta", (str(t.get("city")).isEmpty() ? "" : t.get("city") + "｜") + (days > 0 ? days + "天" : "天数待定"));
            m.put("isMe", me != null && me.equals(KgJdbc.asLong(t.get("appUserId"))));
            members.add(m);
            if (days >= 5)
            {
                longStay++;
                if (!str(t.get("avatar")).isEmpty() && longAvatars.size() < 5)
                {
                    longAvatars.add(str(t.get("avatar")));
                }
            }
        }
        Map<String, Object> mine = me == null ? null : db.one("select app_id, status from kg_application where job_id = :j and app_user_id = :u and del_flag = '0' "
                + "and status not in ('withdrawn','review_rejected','terminated') order by app_id desc limit 1", p("j", jobId, "u", me));
        long head = Math.max(1, num(j.get("headcount")));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("job", j);
        m.put("applied", applied);
        m.put("headcount", head);
        m.put("hired", num(j.get("hiredCount")));
        m.put("remaining", job.getRemainingCount());
        m.put("members", members);
        m.put("longStay", longStay);
        m.put("longAvatars", longAvatars);
        m.put("myAppId", mine == null ? null : mine.get("appId"));
        m.put("joined", me != null && mine != null && db.count("select count(*) from kg_team_member where job_id = :j and app_user_id = :u and app_id=:a and visible = '1'", p("j", jobId, "u", me, "a", mine.get("appId"))) > 0);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> joinTeam(Long me, Long jobId, boolean visible)
    {
        if (visible) com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requirePrivateChat(db);
        Map<String, Object> a = db.one("select app_id from kg_application where job_id = :j and app_user_id = :u and del_flag = '0' and status not in ('withdrawn','review_rejected','terminated') "
                + "order by app_id desc limit 1", p("j", jobId, "u", me));
        if (a == null && visible)
        {
            throw new ServiceException("报名这个岗位后才能加入同行队伍", 403);
        }
        db.update("insert into kg_team_member (job_id, app_user_id, app_id, visible, create_time) values (:j, :u, :a, :v, now()) "
                + "on duplicate key update visible = :v, app_id = coalesce(:a, app_id)", p("j", jobId, "u", me, "a", a == null ? null : a.get("appId"), "v", visible ? "1" : "0"));
        return !visible && !com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db, com.ruoyi.kangaroo.ext.community.KgCommunityFeature.PRIVATE_KEY) ? p("joined",false) : team(me, jobId);
    }

    // ---------------------------------------------------------------- 新机会订阅 + 推送

    public List<Map<String, Object>> subscriptions(Long me)
    {
        return db.list("select s.*, st.store_name from kg_job_subscribe s left join kg_store st on st.store_id = s.store_id where s.app_user_id = :u order by s.sub_id desc", p("u", me));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveSubscription(Long me, Long subId, Map<String, Object> b)
    {
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        List<String> cities = listOf(b.get("cities"), 10, 16);
        List<String> types = listOf(b.get("jobTypes"), 10, 16);
        if (jobTypeCodes(null, types).equals(java.util.Collections.singletonList("online"))) cities.clear();
        Long storeId = KgJdbc.asLong(b.get("storeId"));
        if (cities.isEmpty() && types.isEmpty() && storeId == null && str(b.get("keyword")).trim().isEmpty())
        {
            throw new ServiceException("至少选择一个城市或岗位类型", 422);
        }
        cols.put("cities", String.join(",", cities));
        cols.put("job_types", String.join(",", types));
        cols.put("keyword", StaffSupport.cut(str(b.get("keyword")).trim(), 64));
        cols.put("store_id", storeId);
        String from = str(b.get("fromDate"));
        cols.put("from_date", KgJdbc.isDate(from) ? from : null);
        long mm = num(b.get("minMatch"));
        cols.put("min_match", mm < 50 || mm > 99 ? 60 : mm);
        cols.put("freq", "daily".equals(str(b.get("freq"))) ? "daily" : "instant");
        cols.put("status", "0");
        cols.put("update_time", new Date());
        // 只带门店的一条就是关注门店（岗位页「这家店缺人了提醒我」）：和按城市 / 类型的订阅分开计数，之前两者合计 10 条
        boolean follow = storeId != null && cities.isEmpty() && types.isEmpty() && str(cols.get("keyword")).isEmpty();
        // 先锁住这个账号再查重 / 计数：连点两下或网络重试时，两个请求不会都以为「还没有」而各插一条，也不会一起越过条数上限
        lockUser(me);
        Map<String, Object> old = null;
        if (subId != null)
        {
            old = db.one("select sub_id, store_id, cities, job_types, keyword from kg_job_subscribe where sub_id = :s and app_user_id = :u", p("s", subId, "u", me));
            if (old == null)
            {
                throw new ServiceException("订阅不存在", 404);
            }
            // 之前修改时不看这一条原来是什么：把订阅改成只带门店（= 关注）、或把关注改回订阅，每改一次就腾出一个名额，
            // 反复改能加出成千上万条（新机会推送每轮都要扫全部订阅）。现在关注和订阅不能互相改，名额只在新增时算
            if (KgStoreFollowService.isFollowRow(old) != follow)
            {
                throw new ServiceException(follow ? "这一条是新机会订阅，不能改成只关注门店；想关注门店，请在岗位详情的门店卡片上点「关注」"
                        : "这一条是关注的门店，不能改成按城市或类型订阅；想按城市或类型订阅，请另外新增一条", 422);
            }
        }
        // 之前门店编号不校验，随便填一个也能存；现在门店要真实存在、没被删除。修改时门店没换就不再查，门店后来被删了也还能改提醒频率
        if (storeId != null && (old == null || !storeId.equals(KgJdbc.asLong(old.get("storeId"))))
                && db.one("select store_id from kg_store where store_id = :s and del_flag = '0'", p("s", storeId)) == null)
        {
            throw new ServiceException("门店不存在", 404);
        }
        // 一个账号只保留一条生效的新机会订阅；账号锁使并发创建也落到同一条。
        // 门店关注独立维护，不参与此规则。历史订阅保留记录，在成功保存时暂停其余条目。
        if (!follow && subId == null)
        {
            Map<String, Object> current = db.one("select sub_id from kg_job_subscribe where app_user_id = :u and not ("
                    + KgStoreFollowService.followRow("") + ") order by (status = '0') desc, sub_id desc limit 1", p("u", me));
            if (current != null) subId = KgJdbc.asLong(current.get("subId"));
        }
        if (subId == null)
        {
            // 同样条件的订阅已经有了（重复点「订阅这个城市」「有空位提醒我」）：更新那一条，不再插第二条（2026-09-26 回归发现）
            Map<String, Object> same = db.one("select sub_id from kg_job_subscribe where app_user_id = :u and cities = :c and job_types = :t "
                    + "and coalesce(keyword, '') = :k and coalesce(store_id, 0) = :s limit 1",
                    p("u", me, "c", cols.get("cities"), "t", cols.get("job_types"), "k", cols.get("keyword"), "s", storeId == null ? 0L : storeId));
            if (same != null)
            {
                subId = KgJdbc.asLong(same.get("subId"));
            }
        }
        if (subId == null)
        {
            String followRow = KgStoreFollowService.followRow("");
            if (follow ? db.count("select count(*) from kg_job_subscribe where app_user_id = :u and " + followRow, p("u", me)) >= KgStoreFollowService.FOLLOW_MAX
                    : db.count("select count(*) from kg_job_subscribe where app_user_id = :u and not (" + followRow + ")", p("u", me)) >= 10)
            {
                throw new ServiceException(follow ? "最多关注 " + KgStoreFollowService.FOLLOW_MAX + " 家门店，请先在「我的关注」里取消几家" : "订阅最多 10 条", 409);
            }
            cols.put("app_user_id", me);
            cols.put("create_time", new Date());
            cols.put("last_notified_time", new Date());
            subId = db.insert("kg_job_subscribe", cols);
        }
        else
        {
            db.updateRow("kg_job_subscribe", "sub_id", subId, cols);
        }
        if (!follow)
        {
            db.update("update kg_job_subscribe set status = '1', update_time = now() where app_user_id = :u and sub_id <> :s and not ("
                    + KgStoreFollowService.followRow("") + ") and status = '0'", p("u", me, "s", subId));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("subId", subId);
        m.put("message", "已订阅，" + ("daily".equals(cols.get("freq")) ? "每天 9:00 汇总" : "有新机会时") + "通过消息中心通知你");
        return m;
    }

    /**
     * 关注 / 取消关注一家门店：本质是一条只带 store_id 的新机会订阅，
     * 这家门店有新岗位上架时按订阅规则通知，不会给门店发任何消息，也不会暴露关注者。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> followStore(Long me, Long storeId, boolean on)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (!on)
        {
            // 之前取消关注也要先查到门店：门店删除后，「我的关注」里那一条怎么点取消都提示「门店不存在」。现在取消不看门店是否还在
            db.update("delete from kg_job_subscribe where app_user_id = :u and store_id = :s and " + KgStoreFollowService.followRow(""), p("u", me, "s", storeId));
            m.put("following", Boolean.FALSE);
            m.put("message", "已取消关注");
            return m;
        }
        // 之前「查有没有关注 → 没有就插入」不加锁：连点两下或网络重试时两个请求都查到「还没有」，各插一条，「我的关注」里同一家店出现两次。
        // 现在先锁住这个账号（必须是本事务的第一条查询，见 lockUser），同一账号的关注请求排队，后到的能看到前一个刚插入的那一条
        lockUser(me);
        Map<String, Object> store;
        try
        {
            store = db.must("select store_id, store_name from kg_store where store_id = :s and del_flag = '0'", p("s", storeId), "门店不存在");
        }
        catch (ServiceException e)
        {
            // db.must 查不到时不带码（接口 500，监控当成服务器报错）；门店不存在是业务拒绝，改报 404
            throw new ServiceException(e.getMessage(), 404);
        }
        Map<String, Object> exist = db.one("select sub_id from kg_job_subscribe where app_user_id = :u and store_id = :s and "
                + KgStoreFollowService.followRow("") + " limit 1", p("u", me, "s", storeId));
        if (exist == null)
        {
            // 之前关注和新机会订阅合计最多 10 条，关注几家店就订阅不了城市；现在关注门店单独计数（最多 50 家），订阅仍是 10 条
            if (db.count("select count(*) from kg_job_subscribe where app_user_id = :u and " + KgStoreFollowService.followRow(""), p("u", me)) >= KgStoreFollowService.FOLLOW_MAX)
            {
                throw new ServiceException("最多关注 " + KgStoreFollowService.FOLLOW_MAX + " 家门店，请先在「我的关注」里取消几家", 409);
            }
            Map<String, Object> cols = new LinkedHashMap<String, Object>();
            cols.put("app_user_id", me);
            cols.put("cities", "");
            cols.put("job_types", "");
            cols.put("keyword", "");
            cols.put("store_id", storeId);
            cols.put("min_match", 60);
            cols.put("freq", "instant");
            cols.put("status", "0");
            cols.put("create_time", new Date());
            cols.put("update_time", new Date());
            cols.put("last_notified_time", new Date());
            db.insert("kg_job_subscribe", cols);
        }
        m.put("following", Boolean.TRUE);
        m.put("message", "已关注「" + str(store.get("storeName")) + "」，有新岗位时通知你");
        return m;
    }

    /**
     * 锁住这个账号（kg_app_user 的这一行），同一账号的关注 / 订阅写入排队进行，查重和计数不会被并发的请求绕过。
     * 必须在事务里任何普通查询之前调用：可重复读下，事务里第一次普通查询才定下「看到的数据快照」，
     * 先锁再查，才能看到前一个请求刚提交的那一条。
     */
    private void lockUser(Long me)
    {
        db.one("select user_id from kg_app_user where user_id = :u for update", p("u", me));
    }

    /** 是否已关注这家门店。之前凡是带这家门店的订阅都算，和「取消关注」删的不是同一种行；现在只认关注行（与 KgStoreFollowService 同一口径） */
    public boolean following(Long me, Long storeId)
    {
        return me != null && storeId != null && db.count("select count(*) from kg_job_subscribe where app_user_id = :u and store_id = :s and status = '0' and "
                + KgStoreFollowService.followRow(""), p("u", me, "s", storeId)) > 0;
    }

    public String removeSubscription(Long me, Long subId)
    {
        if (db.update("delete from kg_job_subscribe where sub_id = :s and app_user_id = :u", p("s", subId, "u", me)) == 0)
        {
            throw new ServiceException("订阅不存在", 404);
        }
        return "已取消订阅";
    }

    private static List<String> listOf(Object raw, int max, int len)
    {
        List<String> out = new ArrayList<String>();
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                String s = str(o).trim();
                if (!s.isEmpty() && out.size() < max)
                {
                    out.add(StaffSupport.cut(s, len));
                }
            }
        }
        return out;
    }

    @Scheduled(fixedDelay = 600000, initialDelay = 90000)
    public void pushInstant()
    {
        push("instant");
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void pushDaily()
    {
        push("daily");
    }

    /** 新机会推送每批取的订阅条数 */
    static final int PUSH_BATCH = 500;

    private void push(String freq)
    {
        if(!com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.RECRUITMENT_KEY)) return;
        // 之前一轮只取前 500 条（不排序、不翻页）：订阅和关注门店多了以后，排在后面的永远推不到。现在按 sub_id 分批取完
        long after = 0L;
        while (true)
        {
            List<Map<String, Object>> batch = db.list("select * from kg_job_subscribe where status = '0' and freq = :f and sub_id > :after order by sub_id limit " + PUSH_BATCH,
                    p("f", freq, "after", after));
            for (Map<String, Object> s : batch)
            {
                pushOne(s);
            }
            Long last = batch.isEmpty() ? null : KgJdbc.asLong(batch.get(batch.size() - 1).get("subId"));
            if (batch.size() < PUSH_BATCH || last == null || last <= after)
            {
                return;
            }
            after = last;
        }
    }

    private void pushOne(Map<String, Object> s)
    {
        try
        {
            Map<String, Object> pref = db.one("select enabled from kg_notify_pref where app_user_id = :u and pref_key = 'job_new'", p("u", s.get("appUserId")));
            if (pref != null && !"1".equals(str(pref.get("enabled"))))
            {
                return;
            }
            StringBuilder where = new StringBuilder(" where j.del_flag = '0' and j.status = 'published' and exists (select 1 from kg_store st where st.store_id = j.store_id and st.status = '0' and st.del_flag = '0')"
                    + " and coalesce(j.publish_time, j.create_time) > :since");
            Map<String, Object> ps = p("since", s.get("lastNotifiedTime") == null ? s.get("createTime") : s.get("lastNotifiedTime"));
            if (s.get("storeId") != null)
            {
                where.append(" and j.store_id = :st");
                ps.put("st", s.get("storeId"));
            }
            // 城市与岗位类型的匹配口径必须和筛选页（search）完全一致：订阅就是"把这次筛选存下来，有新岗位时再跑一遍"。
            // 原先这里自己拼了一套：岗位大类代码（fulltime 等）拿去 like 标题永远推不到；
            // "店员/助手"去掉斜杠变成 like '%店员助手%'，和筛选页按斜杠拆开匹配的结果也不一样
            List<String> subTypes = splitList(s.get("jobTypes"));
            List<String> codes = jobTypeCodes(null, subTypes);
            appendCities(where, ps, businessCities(splitList(s.get("cities"))), codes.isEmpty() || codes.contains("online"));
            appendJobTypes(where, ps, codes, keywordTypes(subTypes), false);
            if (!str(s.get("keyword")).isEmpty())
            {
                where.append(" and (j.title like :kw or j.content like :kw)");
                // 订阅的关键词按字面找：% _ \ 转义（KgLike），之前存一个「%」就推所有新岗位
                ps.put("kw", com.ruoyi.kangaroo.ext.KgLike.contains(str(s.get("keyword"))));
            }
            List<Map<String, Object>> jobs = db.list("select j.job_id, j.title, j.city from kg_job j" + where + " order by j.job_id desc limit 20", ps);
            if (!jobs.isEmpty() && inQuietHours(KgJdbc.asLong(s.get("appUserId"))))
            {
                // 免打扰时段内先不推；last_notified_time 不前移，下一轮（10 分钟后）出了时段照样会推到，不会漏
                return;
            }
            db.update("update kg_job_subscribe set last_notified_time = now() where sub_id = :s", p("s", s.get("subId")));
            if (jobs.isEmpty())
            {
                return;
            }
            if (KgStoreFollowService.isFollowRow(s))
            {
                // 关注门店：之前和普通订阅一样推「有 N 个新机会符合你的订阅」，多条时跳到不带门店条件的筛选页（等于全部岗位）；
                // 现在写明「你关注的「门店名」发布了新岗位：岗位标题」，1 条进岗位详情，多条进这家店的在招岗位
                Map<String, Object> store = db.one("select store_name from kg_store where store_id = :s", p("s", s.get("storeId")));
                String[] n = KgStoreFollowService.notice(KgJdbc.asLong(s.get("storeId")), store == null ? null : str(store.get("storeName")), jobs);
                support.notifyUser(KgJdbc.asLong(s.get("appUserId")), "system", n[0], n[1], "job_subscribe", KgJdbc.asLong(s.get("subId")), n[2]);
                return;
            }
            String first = str(jobs.get(0).get("title"));
            support.notifyUser(KgJdbc.asLong(s.get("appUserId")), "system", jobs.size() == 1 ? "有 1 个新机会符合你的订阅" : "有 " + jobs.size() + " 个新机会符合你的订阅",
                    first + (jobs.size() > 1 ? " 等" : "") + "（" + jobs.get(0).get("city") + "）", "job_subscribe", KgJdbc.asLong(s.get("subId")),
                    jobs.size() == 1 ? "/pages/jobs/detail?jobId=" + jobs.get(0).get("jobId") : "/pages/jobs/filter?source=sub&sub=" + s.get("subId"));
        }
        catch (Exception e)
        {
            log.warn("新机会推送失败 sub={}：{}", s.get("subId"), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 岗位多条件筛选

    /** kg_job.job_type 的四个取值（岗位大类）。筛选与订阅里的大类按这一列精确匹配，不能拿英文代码去 like 标题 */
    static final List<String> JOB_TYPE_CODES = Arrays.asList("volunteer", "parttime", "fulltime", "online");

    /** 逗号分隔的订阅字段 → 去空白后的非空列表 */
    static List<String> splitList(Object raw)
    {
        List<String> out = new ArrayList<String>();
        for (String x : str(raw).split(","))
        {
            if (!x.trim().isEmpty())
            {
                out.add(x.trim());
            }
        }
        return out;
    }

    /**
     * 岗位大类代码：显式的 jobType（字符串、逗号串或数组）加上混在「岗位类型」关键词里的代码。
     * 后一种是为了兼容：列表页以前把 volunteer / fulltime 当关键词塞进 types，按那种方式存下来的订阅
     * job_types 里就是这些代码 —— 挑出来走 j.job_type，老订阅不用迁移数据就能命中。
     */
    static List<String> jobTypeCodes(Object explicit, List<String> types)
    {
        List<Object> raw = new ArrayList<Object>();
        if (explicit instanceof List)
        {
            raw.addAll((List<?>) explicit);
        }
        else if (explicit != null)
        {
            raw.addAll(splitList(explicit));
        }
        if (types != null)
        {
            raw.addAll(types);
        }
        List<String> out = new ArrayList<String>();
        for (Object o : raw)
        {
            String t = str(o).trim();
            if (JOB_TYPE_CODES.contains(t) && !out.contains(t))
            {
                out.add(t);
            }
        }
        return out;
    }

    /** 「岗位类型」里真正的关键词（手作老师、店员/助手…），即去掉岗位大类代码之后剩下的 */
    static List<String> keywordTypes(List<String> types)
    {
        List<String> out = new ArrayList<String>();
        for (String t : types)
        {
            if (!JOB_TYPE_CODES.contains(t.trim()))
            {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 岗位大类 + 关键词条件。没选大类时 offlineByDefault 决定要不要排除线上任务（筛选页默认只看到店岗位，订阅推送不排除）；
     * 关键词按斜杠拆开（"店员/助手" → 店员、助手），任一部分命中标题或标签即可。
     * 只看线上任务时，关键词就是「任务类型」，按任务类型精确匹配（见下）
     */
    static void appendJobTypes(StringBuilder where, Map<String, Object> ps, List<String> codes, List<String> keywords, boolean offlineByDefault)
    {
        if (!codes.isEmpty())
        {
            where.append(" and j.job_type in (:jt)");
            ps.put("jt", new ArrayList<String>(codes));
        }
        else if (offlineByDefault)
        {
            where.append(" and j.job_type <> 'online'");
        }
        // 只看线上任务时：「任务类型」按发布时选的类型（kg_online_task.task_type）精确匹配，和线上任务列表的「任务类型」胶囊同一口径
        // （KgJobMapper.selectAppJobList 的 params.taskType）；选「其他」时，没有任务类型的老线上岗位也算进来。
        // 之前按关键词模糊匹配标题 / 标签，标题里写着「视频剪辑」的其他类型任务也会算进「视频剪辑」，和列表页对不上
        if (codes.size() == 1 && "online".equals(codes.get(0)))
        {
            List<String> tt = new ArrayList<String>();
            for (String t : keywords)
            {
                String v = t.trim();
                if (!v.isEmpty() && !tt.contains(v))
                {
                    tt.add(v);
                }
            }
            if (!tt.isEmpty())
            {
                where.append(" and (exists (select 1 from kg_online_task ot where ot.job_id = j.job_id and ot.task_type in (:tt))");
                if (tt.contains("其他"))
                {
                    where.append(" or not exists (select 1 from kg_online_task ot where ot.job_id = j.job_id)");
                }
                where.append(")");
                ps.put("tt", tt);
            }
            return;
        }
        StringBuilder tc = new StringBuilder();
        int i = 0;
        for (String t : keywords)
        {
            for (String part : t.split("/"))
            {
                if (part.trim().isEmpty())
                {
                    continue;
                }
                tc.append(tc.length() == 0 ? "" : " or ").append("j.title like :t").append(i).append(" or j.tags like :t").append(i);
                tc.append(" or exists (select 1 from kg_online_task ot where ot.job_id = j.job_id and ot.task_type like :t").append(i).append(")");
                ps.put("t" + i++, com.ruoyi.kangaroo.ext.KgLike.contains(part.trim()));
            }
        }
        if (tc.length() > 0)
        {
            where.append(" and (").append(tc).append(")");
        }
    }

    /**
     * 换工类型条件（筛选页「类型」选了日常换工 / 摄影换工…）：只看换工岗位，按 kg_job.work_kind 精确匹配；
     * 选了「其他」时，还没选类型（work_kind 为空串）的换工岗位也算进来。
     * 之前没选类型的老岗位按类型名称 / 说明里的词模糊匹配标题和标签：「手工咖啡馆」会被当成手作教学，「其他」却总是空的。
     */
    static void appendWorkKinds(StringBuilder where, Map<String, Object> ps, List<String> kinds)
    {
        if (kinds == null || kinds.isEmpty())
        {
            return;
        }
        where.append(" and j.job_type = 'volunteer' and (j.work_kind in (:wk)");
        ps.put("wk", new ArrayList<String>(kinds));
        if (com.ruoyi.kangaroo.ext.core.KgVolunteerKinds.includesUntyped(kinds))
        {
            where.append(" or j.work_kind = ''");
        }
        where.append(")");
    }

    /** 用户选中的城市或区县 → 业务城市名（思明区 → 厦门，不认识的原样），去重 */
    List<String> businessCities(List<String> raw)
    {
        List<String> out = new ArrayList<String>();
        for (String c : raw)
        {
            String n = cityService.businessCity(c);
            if (n != null && !n.isEmpty() && !out.contains(n))
            {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * 城市条件（传入的必须是已归一的业务城市名）：岗位城市是"云南大理""平潭岛"这种写法，
     * 按「等于或以城市 / 别名结尾」匹配（KgJobCity，和岗位列表、「有岗位的城市」下拉同一口径）。
     * 之前按包含 like %城市%，「大连」会带出「五大连池」、「中山」带出「中山区」。
     * 第 i 个城市的写法参数名是 c{i}_0、c{i}_1…
     * includeOnline 时所有线上任务均不受地区限制，包括城市仍写着门店所在地的历史任务。
     * 混合订阅中到店岗位仍按所选城市匹配，不把跨城的到店岗位一起放开。
     */
    void appendCities(StringBuilder where, Map<String, Object> ps, List<String> cities, boolean includeOnline)
    {
        if (cities.isEmpty())
        {
            return;
        }
        StringBuilder cc = new StringBuilder();
        for (int i = 0; i < cities.size(); i++)
        {
            cc.append(i == 0 ? "" : " or ").append(KgJobCity.sql("j.city", "c" + i + "_", KgJobCity.terms(cityService, cities.get(i)), ps));
        }
        if (includeOnline)
        {
            cc.append(" or j.job_type = 'online'");
        }
        where.append(" and (").append(cc).append(")");
    }

    /**
     * cities[]、fromDate（可到岗）、minStay（至少能待多少天：0 / 7 / 14 / 30 / 90）、jobType（岗位大类 volunteer / parttime / fulltime / online，
     * 不传时只看到店岗位）、types[]（岗位关键词）、hours（每天工时上限：0 不限 / 4 / 6 / 8 / 99）、
     * board（none 不限 / all 包食宿 / room 包住宿）、salaryMin / salaryMax（元/天）、safe、insured、
     * monthBonus（只看「做满一个月有红包」的岗位，V44 kg_job.month_redpacket = '0'）。
     * 筛选页 2026-10-16 起不再提供期望薪资，salaryMin / salaryMax 留给老版本小程序
     */
    public Map<String, Object> search(Long me, Map<String, Object> b)
    {
        // 城市先归到业务城市再拼 like：选择器里选中"思明区"时，岗位上写的是"厦门"，原样去 like 一条都出不来
        List<String> cities = businessCities(listOf(b.get("cities"), 10, 16));
        List<String> types = listOf(b.get("types"), 10, 16);
        List<String> jobTypes = jobTypeCodes(b.get("jobType"), types);
        String from = str(b.get("fromDate"));
        long minStay = num(b.get("minStay"));
        long hours = num(b.get("hours"));
        String board = str(b.get("board"));
        long smin = num(b.get("salaryMin"));
        long smax = num(b.get("salaryMax"));
        boolean safe = Boolean.parseBoolean(str(b.get("safe")));
        boolean insured = Boolean.parseBoolean(str(b.get("insured")));
        boolean monthBonus = Boolean.parseBoolean(str(b.get("monthBonus"))) || "1".equals(str(b.get("monthBonus")));
        StringBuilder where = new StringBuilder();
        Map<String, Object> ps = p();
        appendCities(where, ps, cities, jobTypes.contains("online"));
        // 岗位大类走 j.job_type：列表页的筛选胶囊原先把 fulltime 这类代码当关键词去 like 标题 / 标签，永远 0 条
        appendJobTypes(where, ps, jobTypes, keywordTypes(types), true);
        // 过了截止时间的线上任务不再接单，列表页（KgJobMapper.selectAppJobList）早就不列了；这里之前照样算进「查看 N 个机会」，
        // 同一个任务类型列表页和全部筛选页数量对不上，点进去也报不了名
        where.append(" and not exists (select 1 from kg_online_task ot where ot.job_id = j.job_id and ot.deadline_time is not null and ot.deadline_time < now())");
        // 换工类型（workKinds = 字典 kg_volunteer_kind 的值）：按类型精确匹配，选「其他」时连还没选类型的换工岗位一起列出。
        // 之前截成 32 位就拿去查、老岗位还按词模糊匹配；现在格式不对（太长、大写、中文）直接提示「换工类型不合法」
        List<String> workKinds = listOf(b.get("workKinds"), 10, 64);
        for (String k : workKinds)
        {
            if (!com.ruoyi.kangaroo.ext.core.KgVolunteerKinds.validValue(k))
            {
                throw new ServiceException("换工类型不合法", 422);
            }
        }
        appendWorkKinds(where, ps, workKinds);
        if (KgJdbc.isDate(from))
        {
            where.append(" and (j.start_date is null or j.start_date <= date_add(:from, interval 14 day))");
            ps.put("from", from);
        }
        if (minStay > 0)
        {
            where.append(" and coalesce(j.min_days, 0) <= :ms");
            ps.put("ms", minStay);
        }
        // 食宿：none / 空 = 不限；2026-10-17 起兼职、全职页签多了「包吃」「不包食宿」，和岗位列表同一口径（JobTabFilters.boardSql）
        board = com.ruoyi.kangaroo.domain.JobTabFilters.board(board);
        where.append(com.ruoyi.kangaroo.domain.JobTabFilters.boardSql(board));
        // 每个页签自己的条件（2026-10-17）：兼职「日薪」档 payDay、全职「月薪」档 payMonthMin / payMonthMax、全职「类型」categories，
        // 口径和岗位列表（KgJobMapper.selectAppJobList）一样，都在 JobTabFilters
        Long payDay = com.ruoyi.kangaroo.domain.JobTabFilters.pay(b.get("payDay"));
        Long payMonthMin = com.ruoyi.kangaroo.domain.JobTabFilters.pay(b.get("payMonthMin"));
        Long payMonthMax = com.ruoyi.kangaroo.domain.JobTabFilters.pay(b.get("payMonthMax"));
        List<String> categories = com.ruoyi.kangaroo.domain.JobTabFilters.categories(b.get("categories"));
        com.ruoyi.kangaroo.domain.JobTabFilters.appendSql(where, ps, payDay, payMonthMin, payMonthMax, categories);
        // 期望薪资按「元/天」填。原先只认日薪岗位，选了任何薪资档时薪 / 周薪 / 月薪岗位全部消失；
        // 按国家工资折算口径换成日薪再比（劳社部发〔2008〕3 号：日工资 = 小时工资 × 8 = 月工资 ÷ 21.75；周薪按 5 个工作日）。
        // 计件、只有奖金 / 提成没有底薪的岗位没有日薪口径，选了薪资档时不出现
        if (smin > 0)
        {
            where.append(" and j.salary_type in ('hourly','daily','weekly','monthly') and " + dailyPay("coalesce(j.salary_max, j.salary_min)") + " >= :smin");
            ps.put("smin", smin);
        }
        if (smax > 0)
        {
            where.append(" and j.salary_type in ('hourly','daily','weekly','monthly') and " + dailyPay("j.salary_min") + " <= :smax");
            ps.put("smax", smax);
        }
        if (safe)
        {
            where.append(" and s.safe_cert = '0'");  // 0 已认证
        }
        if (insured)
        {
            where.append(" and j.insurance_flag = '0'");  // 0 含保险
        }
        if (monthBonus)
        {
            where.append(" and j.month_redpacket = '0'");  // 0 做满一个月有红包
        }
        List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
        List<String> skills = new ArrayList<String>();
        if (me != null)
        {
            Map<String, Object> r = db.one("select skill_text, extra_text from kg_resume where app_user_id = :u", p("u", me));
            if (r != null)
            {
                skills = KgSkills.tagsOf(str(r.get("skillText")) + " " + str(r.get("extraText")));
            }
        }
        String base = " from kg_job j left join kg_store s on s.store_id = j.store_id where j.del_flag = '0' "
                + "and j.status = 'published' and s.status = '0' and s.del_flag = '0'" + where;
        List<Map<String, Object>> rows = db.list("select j.*, s.store_name, s.safe_cert" + base
                + " order by " + KgJobDisplayOrder.sql() + " limit " + SEARCH_LIMIT, ps);
        for (Map<String, Object> j : rows)
        {
            long dh = KgSkills.hoursOf(j.get("dailyHours"));
            if (hours > 0 && hours < 99 && dh > hours)
            {
                continue;
            }
            Map<String, Object> m = home.jobCard(j);
            String kind = str(j.get("workKind"));
            m.put("workKind", kind);
            m.put("workKindLabel", volunteerKinds == null || kind.isEmpty() ? "" : volunteerKinds.label(kind));
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) m.get("tags");
            int overlap = 0;
            for (String sk : skills)
            {
                overlap += tags.contains(sk) ? 1 : 0;
            }
            m.put("match", skills.isEmpty() ? null : Math.min(98, 70 + overlap * 10));
            m.put("place", j.get("city") + (j.get("storeName") == null ? "" : " · " + j.get("storeName")));
            m.put("date", (j.get("startDate") == null ? "随时到岗" : md(j.get("startDate")) + " 起") + (num(j.get("minDays")) > 0 ? " · 至少 " + num(j.get("minDays")) + " 天" : "")
                    + (j.get("dailyHours") == null ? "" : " · " + j.get("dailyHours")));
            jobs.add(m);
        }
        home.markFavorites(me, jobs);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("jobs", jobs);
        res.put("total", searchTotal(base, ps, rows.size(), jobs.size(), hours));
        if (jobs.isEmpty())
        {
            List<String> conflicts = new ArrayList<String>();
            List<Map<String, Object>> relax = new ArrayList<Map<String, Object>>();
            if (KgJdbc.isDate(from))
            {
                conflicts.add("可到岗日期：" + from + " 及以后");
                relax.add(relaxItem("放宽可到岗日期至今天起", "fromDate", ""));
            }
            if (minStay > 0)
            {
                conflicts.add("停留时长：" + minStay + " 天以上");
                relax.add(relaxItem("放宽停留时长为不限", "minStay", 0));
            }
            if (hours > 0 && hours < 99)
            {
                conflicts.add("每天工时：" + hours + " 小时以内");
                relax.add(relaxItem("放宽每天工时为不限", "hours", 0));
            }
            if (smin > 0 || smax > 0)
            {
                conflicts.add("期望薪资区间");
                relax.add(relaxItem("薪资改为不限", "salary", 0));
            }
            if (board != null)
            {
                conflicts.add("食宿要求");
                relax.add(relaxItem("食宿改为不限", "board", "none"));
            }
            if (payDay != null || payMonthMin != null || payMonthMax != null)
            {
                conflicts.add(payDay != null ? "日薪：" + payDay + " 元/天以上" : "月薪档位");
                relax.add(relaxItem("薪资档改为不限", "payBand", 0));
            }
            if (!categories.isEmpty())
            {
                conflicts.add("岗位类型");
                relax.add(relaxItem("类型改为不限", "categories", new ArrayList<String>()));
            }
            if (monthBonus)
            {
                conflicts.add("只看满月红包岗位");
                relax.add(relaxItem("不限是否有满月红包", "monthBonus", false));
            }
            res.put("conflicts", conflicts);
            res.put("relax", relax);
        }
        return res;
    }

    /** 筛选结果最多列多少条（按急招、发布时间排序取前面的）；「查看 N 个机会」的 N 另外数，不受这个上限影响 */
    static final int SEARCH_LIMIT = 100;

    /**
     * 符合条件的岗位总数（2026-10-16）。之前 total 就是列出来的条数，列表 limit 100，
     * 大理两百多个岗位时筛选页按钮永远停在「查看 100 个机会」。
     * 没到上限说明已经全列出来了，直接用列出来的条数，不多查一次；到了上限再用同样的条件数一遍：
     * 每天工时是自由文本（「6-8 小时」），只能取出来按 KgSkills.hoursOf 逐条比，和列表同一口径；没选工时直接 count(*)。
     * 老版本小程序也读 total，不用改前端
     */
    long searchTotal(String base, Map<String, Object> ps, int fetched, int listed, long hours)
    {
        if (fetched < SEARCH_LIMIT)
        {
            return listed;
        }
        if (hours > 0 && hours < 99)
        {
            long n = 0;
            for (Map<String, Object> r : db.list("select j.daily_hours" + base, ps))
            {
                n += KgSkills.hoursOf(r.get("dailyHours")) > hours ? 0 : 1;
            }
            return n;
        }
        return db.count("select count(*)" + base, ps);
    }

    private static Map<String, Object> relaxItem(String label, String key, Object value)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("key", key);
        m.put("value", value);
        return m;
    }

    // ---------------------------------------------------------------- 责任书 / 到店信息

    // 2026-10 只改了「保险说明」一条：平台不再劝报名人自行购买意外险，改为如实说明「没有保单号就还没有保障」。
    // 已签的 2026-09 记录（kg_contract_sign.content_hash）按下面这条旧文字算出，其余五条与现在逐字相同；
    // 复核旧签名时，把版本号换回 2026-09、保险说明换回这一行，用 termsText 的拼法重算 SHA-256 即可对上：
    //   2026-09 保险说明：门店确认录用后可为你申请投保，是否已投保以本页「保单信息」为准；未显示保单号前，建议自行购买意外险。
    static final String[][] TERMS = {
        { "calendar-check", "工作范围", "工作内容、服务标准与工作时间以岗位详情和门店当面说明为准；每天工作时长不超过岗位写明的时长。" },
        { "house", "食宿安排", "岗位写明提供食宿的，由门店免费提供；不提供的，费用由本人承担。门店不得以任何名义收取押金、定金或培训费。" },
        { "shield-check", "安全与健康", "遵守门店安全规定；身体不适及时告知；门店应提供必要的安全说明与劳动保护。" },
        { "clock-3", "到岗与离店", "按约定日期到店；因故不能按时到岗或需提前离店的，至少提前 48 小时在平台告知门店。" },
        { "badge-check", "保险说明", "门店确认录用后可为你申请投保，是否已投保以本页「保单信息」为准；未显示保单号前，不代表已有保障。" },
        { "scale", "争议处理", "发生争议时先与门店协商，可通过平台客服介入；本确认书是平台内的确认留痕，不替代双方依法签订的劳动或劳务合同。" } };

    private Map<String, Object> ownApp(Long me, Long appId)
    {
        // 岗位坐标（V42 起 kg_job 才有）和门店坐标、门店地址分开取：导航点按 navPoint 的规则挑，不再一律用门店的
        Map<String, Object> a = db.one("select a.*, j.title, j.city, j.address, j.job_type, j.cover, j.latitude job_lat, j.longitude job_lng, "
                + "s.store_name, s.contact_phone, s.address store_address, s.latitude store_lat, s.longitude store_lng "
                + "from kg_application a join kg_job j on j.job_id = a.job_id left join kg_store s on s.store_id = a.store_id "
                + "where a.app_id = :a and a.app_user_id = :u and a.del_flag = '0'", p("a", appId, "u", me));
        if (a == null)
        {
            throw new ServiceException("报名记录不存在", 404);
        }
        return a;
    }

    static String termsText()
    {
        StringBuilder sb = new StringBuilder("袋鼠全球青年岗位责任确认书（" + CONTRACT_VERSION + "）\n");
        for (String[] t : TERMS)
        {
            sb.append(t[1]).append("：").append(t[2]).append("\n");
        }
        return sb.toString();
    }

    static String sha256(String s)
    {
        try
        {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : h)
            {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> regionGroup(Long me, Long appId)
    {
        Map<String, Object> a = ownApp(me, appId);
        if ("online".equals(str(a.get("jobType"))) || !Arrays.asList("contract_signed", "onboarded", "completed").contains(str(a.get("status"))))
            throw new ServiceException("线下岗位签署责任书后可查看地区群", 403);
        return regionGroups.forCity(str(a.get("city")));
    }

    public Map<String, Object> contract(Long me, Long appId)
    {
        Map<String, Object> a = ownApp(me, appId);
        String st = str(a.get("status"));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("appId", appId);
        m.put("status", st);
        m.put("jobTitle", a.get("title"));
        m.put("storeName", a.get("storeName"));
        m.put("city", a.get("city"));
        // 当地活动按岗位目的地匹配，兼容“福建平潭岛”等岗位城市写法。
        m.put("activityCity", cityService == null ? a.get("city") : cityService.jobCity(str(a.get("city"))));
        m.put("canSign", "store_confirmed".equals(st));
        m.put("onboardTime", a.get("onboardTime"));
        List<Map<String, Object>> terms = new ArrayList<Map<String, Object>>();
        for (String[] t : TERMS)
        {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("icon", t[0]);
            x.put("title", t[1]);
            x.put("desc", t[2]);
            terms.add(x);
        }
        m.put("terms", terms);
        m.put("version", CONTRACT_VERSION);
        m.put("hash", sha256(termsText()));
        Map<String, Object> sign = db.one("select signer_name, id_last4, signed_time, content_hash, version, agreement_no, content_snapshot, merchant_signer, merchant_signed_time from kg_contract_sign where app_id = :a", p("a", appId));
        m.put("sign", sign);
        // 仅新换工协议走双方确认。已签旧版继续展示旧版，不能把旧签名套在新正文上。
        if ("volunteer".equals(a.get("jobType")) && (sign == null || VolunteerAgreement.VERSION.equals(sign.get("version")))) {
            Map<String,Object> vc = volunteerContracts.view(me,appId);
            m.put("tripartite", vc);
            m.put("terms", Arrays.asList(p("icon","shield-check","title",VolunteerAgreement.TITLE,"desc",VolunteerAgreement.BODY)));
            m.put("version", VolunteerAgreement.VERSION);
        } else if (sign != null) {
            m.put("version",sign.get("version")); m.put("hash",sign.get("contentHash"));
            if (sign.get("contentSnapshot") != null) m.put("terms",Arrays.asList(p("icon","shield-check","title","签署时的协议原文","desc",sign.get("contentSnapshot"))));
            else if ("2026-09".equals(sign.get("version"))) {
                terms.get(4).put("desc","门店确认录用后可为你申请投保，是否已投保以本页「保单信息」为准；未显示保单号前，建议自行购买意外险。");
            }
        }
        m.put("signerDefault", a.get("realName"));
        Map<String, Object> arr = db.one("select * from kg_app_arrival where app_id = :a", p("a", appId));
        if (arr == null)
        {
            arr = new LinkedHashMap<String, Object>();
        }
        arr.put("arriveDate", com.ruoyi.kangaroo.ext.staff.ArrivalConfirmationPolicy.expectedDate(arr.get("arriveDate"), a.get("arrivalDate")));
        m.put("arrival", arr);
        // 提前续保后最近一条是续保申请：返回今天正在保障的那张，续保挂在 renewal 里（之前只取最近一条，被保险人以为自己没保障）
        m.put("insurance", com.ruoyi.kangaroo.ext.staff.KgInsuranceService.userCoverage(db, appId));
        boolean hired = Arrays.asList("store_confirmed", "contract_signed", "onboarded", "completed").contains(st);
        m.put("storePhone", hired ? a.get("contactPhone") : null);
        Object[] nav = hired ? navPoint(a) : new Object[3];
        m.put("address", nav[0]);
        m.put("latitude", nav[1]);
        m.put("longitude", nav[2]);
        return m;
    }

    /**
     * 责任书页「点此打开地图」用的地址和坐标，返回 {地址, 纬度, 经度}。
     * 之前地址取岗位的、坐标却一律取门店的：门店替别家（合作商户）发的岗位，导航会把人带到门店。现在：
     * 岗位自己选过点（kg_job.latitude / longitude 成对）就用岗位的；岗位没填地址、或地址就是门店地址时，才借用门店坐标
     * （没填地址时地址也借门店的）；其余情况不给坐标，页面只显示地址文字，不导航到错的地方。
     */
    static Object[] navPoint(Map<String, Object> a)
    {
        String jobAddr = str(a.get("address")).trim();
        String storeAddr = str(a.get("storeAddress")).trim();
        Object address = jobAddr.isEmpty() ? (storeAddr.isEmpty() ? null : storeAddr) : a.get("address");
        if (a.get("jobLat") != null && a.get("jobLng") != null)
        {
            return new Object[] { address, a.get("jobLat"), a.get("jobLng") };
        }
        if (jobAddr.isEmpty() || jobAddr.equals(storeAddr))
        {
            return new Object[] { address, a.get("storeLat"), a.get("storeLng") };
        }
        return new Object[] { address, null, null };
    }

    /** 姓名比较：去掉空白和间隔号再比（少数民族姓名常带「·」，手输时空格也不统一） */
    static boolean sameName(String x, String y)
    {
        String r = "[" + "\\s" + "·•.．]";
        return !x.isEmpty() && x.replaceAll(r, "").equals(y.replaceAll(r, ""));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sign(Long me, String nickname, Long appId, Map<String, Object> b, String ip)
    {
        db.must("select app_id from kg_application where app_id=:a for update",p("a",appId),"报名不存在");
        Map<String, Object> a = ownApp(me, appId);
        if ("volunteer".equals(a.get("jobType"))) {
            volunteerContracts.submit(me,appId,b,ip);
            return contract(me,appId);
        }
        String st = str(a.get("status"));
        if (!"store_confirmed".equals(st))
        {
            // 已经签过的再点签署：之前也说「门店确认录用后才能签署」，像是还没录用；按真实情况说
            if (Arrays.asList("contract_signed", "onboarded", "completed").contains(st)
                    || db.count("select count(*) from kg_contract_sign where app_id = :a", p("a", appId)) > 0)
            {
                throw new ServiceException("责任书已签署，无需重复签署", 409);
            }
            if (Arrays.asList("withdrawn", "terminated", "review_rejected").contains(st))
            {
                throw new ServiceException("这次报名不在进行中，不能再签署责任书", 409);
            }
            throw new ServiceException("门店确认录用后才能签署责任书", 409);
        }
        String name = str(b.get("name")).trim();
        if (name.length() < 2 || name.length() > 16)
        {
            throw new ServiceException("请填写 2–16 字的签署姓名", 422);
        }
        String last4 = str(b.get("idLast4")).trim();
        if (!last4.isEmpty() && !last4.matches("[0-9Xx]{4}"))
        {
            throw new ServiceException("证件后四位格式不对", 422);
        }
        // 签署人必须是报名人本人：原先姓名随便填，可以用任意名字以报名人的身份签下责任确认。
        // 姓名与报名时填写的一致（忽略空格 / 间隔号）；账号已完成实名的，填了证件后四位就必须与实名信息一致
        if (!sameName(name, str(a.get("realName"))))
        {
            throw new ServiceException("签署姓名需与报名时填写的姓名一致", 422);
        }
        Map<String, Object> verified = db.one("select id_last4, real_name_verified_time from kg_app_user where user_id = :u", p("u", me));
        String verifiedLast4 = verified != null && verified.get("realNameVerifiedTime") != null ? str(verified.get("idLast4")).trim() : "";
        if (!verifiedLast4.isEmpty())
        {
            // 签署页上证件后四位是选填：已实名的人留空就按实名信息记；填了才比对。之前留空直接被拒「需与实名认证信息一致」
            if (last4.isEmpty())
            {
                last4 = verifiedLast4;
            }
            else if (!last4.equalsIgnoreCase(verifiedLast4))
            {
                throw new ServiceException("证件后四位需与实名认证信息一致", 422);
            }
        }
        if (!Boolean.parseBoolean(str(b.get("agree"))))
        {
            throw new ServiceException("请勾选已阅读并理解全部条款", 422);
        }
        String hash = sha256(termsText());
        if (!hash.equals(str(b.get("hash"))))
        {
            throw new ServiceException("责任书内容已更新，请重新阅读后签署", 409);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_id", appId);
        row.put("app_user_id", me);
        departureNotice.requireAccepted(me,appId);
        row.put("version", CONTRACT_VERSION);
        row.put("content_hash", hash);
        row.put("signer_name", name);
        row.put("id_last4", last4.isEmpty() ? null : last4.toUpperCase());
        row.put("sign_ip", StaffSupport.cut(ip, 64));
        row.put("signed_time", new Date());
        db.insert("kg_contract_sign", row);
        applications.signContract(appId, OperatorDTO.user(me, nickname));
        return contract(me, appId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveArrival(Long me, Long appId, Map<String, Object> b)
    {
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        db.one("select app_id from kg_application where app_id=:a for update", p("a", appId));
        Map<String, Object> a = ownApp(me, appId);
        if ("online".equals(str(a.get("jobType")))) throw new ServiceException("线上任务无需填写到店信息", 409);
        departureNotice.requireAccepted(me,appId);
        // 页面要求先签责任书再填到店信息：之前「商家已确认」（还没签）也能存、还会通知门店。签约之后的状态（已签约 / 已入职）才收
        String st = str(a.get("status"));
        if (!Arrays.asList("contract_signed", "onboarded", "completed").contains(st))
        {
            throw new ServiceException("store_confirmed".equals(st) ? "请先签署责任书，再填写到店信息" : "签署责任书后才能填写到店信息", 409);
        }
        String d = str(b.get("arriveDate"));
        if (!d.isEmpty() && !KgJdbc.isDate(d))
        {
            throw new ServiceException("到达日期格式应为 yyyy-MM-dd", 422);
        }
        String t = str(b.get("arriveTime"));
        if (!t.isEmpty() && !t.matches("([01]\\d|2[0-3]):[0-5]\\d"))
        {
            throw new ServiceException("到达时间格式应为 HH:mm", 422);
        }
        String tr = str(b.get("transport"));
        if (!tr.isEmpty() && !Arrays.asList("火车", "飞机", "大巴", "自驾", "其他").contains(tr))
        {
            throw new ServiceException("交通方式不合法", 422);
        }
        Object rawTickets = b.get("tickets");
        if (rawTickets != null && !(rawTickets instanceof List)) throw new ServiceException("票据应为图片列表", 422);
        List<?> rawList = rawTickets == null ? java.util.Collections.emptyList() : (List<?>)rawTickets;
        if (rawList.size() > 6) throw new ServiceException("最多上传6张票据", 422);
        List<String> tickets = new ArrayList<String>();
        for (Object ref : rawList) {
            if (!(ref instanceof String) || ((String)ref).length() > 200 || ((String)ref).isEmpty() || tickets.contains(ref))
                throw new ServiceException("票据图片地址不合法或重复", 422);
            tickets.add((String)ref);
        }
        arrivalTickets.validateForSave(me, appId, tickets);
        // 「接站需求」已下线（门店并不提供接站）：旧版小程序还会带 needPickup，这里不再读取，也不再写 need_pickup ——
        // 新行按建表默认值 '0'，老行原来存的值保持不动；只切换旧版开关、别的没改时也不算一次修改
        Map<String, Object> values = p("a", appId, "u", me, "d", d.isEmpty() ? null : d, "t", t.isEmpty() ? null : t, "tr", tr.isEmpty() ? null : tr,
                "no", StaffSupport.cut(str(b.get("transportNo")).trim(), 32),
                "tk", String.join(",", tickets), "n", StaffSupport.cut(str(b.get("note")).trim(), 200));
        Map<String, Object> previous = db.one("select app_user_id u, date_format(arrive_date, '%Y-%m-%d') d, arrive_time t, transport tr, "
                + "transport_no no, tickets tk, note n from kg_app_arrival where app_id = :a for update", p("a", appId));
        if (previous != null && !me.equals(KgJdbc.asLong(previous.get("u"))))
            throw new ServiceException("到店信息归属不一致，请联系平台核实", 409);
        // 到店确认是历史事实；确认后只允许补票据、备注等，不能把预计日期挪到未来或清空。
        String confirmedDate = com.ruoyi.kangaroo.ext.staff.ArrivalConfirmationPolicy.expectedDate(previous == null ? null : previous.get("d"), a.get("arrivalDate"));
        if (Arrays.asList("onboarded", "completed").contains(st)
                && !java.util.Objects.equals(com.ruoyi.kangaroo.ext.staff.ArrivalConfirmationPolicy.date(d), confirmedDate))
            throw new ServiceException("门店已确认到店，不能再修改预计到店日期；如需更正，请联系平台核实", 409);
        // 到达日期不能早于今天（之前能填 3 天前的日期并通知门店）；已经存过的日期原样带回不算（到店后改备注、补票据照样能存）
        if (!d.isEmpty() && d.compareTo(KgMembershipPolicy.today().toString()) < 0 && (previous == null || !d.equals(str(previous.get("d"))))
                && !(Arrays.asList("onboarded", "completed").contains(st) && d.equals(confirmedDate)))
            throw new ServiceException("到达日期不能早于今天，请重新选择", 422);
        // The application lock serializes both first saves and revisions. Compare
        // the exact canonical values we persist, after all permission/ticket checks.
        if (sameArrival(previous, values)) return contract(me, appId);
        db.update("insert into kg_app_arrival (app_id, app_user_id, arrive_date, arrive_time, transport, transport_no, tickets, note, update_time) "
                + "values (:a, :u, :d, :t, :tr, :no, :tk, :n, now()) on duplicate key update arrive_date = :d, arrive_time = :t, transport = :tr, transport_no = :no, "
                + "tickets = :tk, note = :n, update_time = now()",
                values);
        support.notifyStore(KgJdbc.asLong(a.get("storeId")), "apply", "报名人更新了到店信息",
                a.get("realName") + "：" + (d.isEmpty() ? "日期待定" : d) + " " + t + " " + tr,
                "application", appId, "/pages/staff/applications?appId=" + appId);
        return contract(me, appId);
    }

    static boolean sameArrival(Map<String, Object> previous, Map<String, Object> values)
    {
        if (previous == null) return false;
        for (String key : Arrays.asList("u", "d", "t", "tr", "no", "tk", "n"))
            if (!str(previous.get(key)).equals(str(values.get(key)))) return false;
        return true;
    }
}
