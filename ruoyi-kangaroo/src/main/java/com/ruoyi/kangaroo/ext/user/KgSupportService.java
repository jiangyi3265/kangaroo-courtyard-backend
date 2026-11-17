package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.stay.KgStayOrderService;
import com.ruoyi.kangaroo.util.InsuranceState;

/**
 * 智能客服（关键词意图 + 查询本人真实数据作答，不是大模型）与人工客服工单。
 * 回答里的数据都来自业务表：报名进度、住宿订单退款试算、保单登记、城市活动与岗位；答不上的引导转人工。
 */
@Service
public class KgSupportService
{
    static final Map<String, String> APP_LABEL = new LinkedHashMap<String, String>();
    static
    {
        APP_LABEL.put("submitted", "报名审核中");
        APP_LABEL.put("review_passed", "审核通过，待约面试");
        APP_LABEL.put("review_rejected", "未通过审核");
        APP_LABEL.put("withdrawn", "已撤回");
        APP_LABEL.put("interview_booked", "已预约面试");
        APP_LABEL.put("interview_done", "面试完成，待双方确认");
        APP_LABEL.put("user_confirmed", "你已确认，待门店确认录用");
        APP_LABEL.put("store_confirmed", "门店已录用，待签责任书");
        APP_LABEL.put("contract_signed", "已签责任书，待到店");
        APP_LABEL.put("onboarded", "已入职");
        APP_LABEL.put("completed", "已完成");
        APP_LABEL.put("terminated", "已终止");
    }

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgStayOrderService stayOrders;

    @Autowired
    private KgActivityService activities;

    @Autowired
    private KgHomeService home;

    @Autowired
    private StaffSupport support;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
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

    static boolean any(String q, String... words)
    {
        for (String w : words)
        {
            if (q.contains(w))
            {
                return true;
            }
        }
        return false;
    }

    static String intentOf(String q)
    {
        // 客服页「猜你想问」里的「查看客服回复」：看自己工单的回复，排最前（问题里带「客服」两字，后面几类都不该接）
        if (any(q, "客服回复", "工单", "客服回了"))
        {
            return "ticket";
        }
        if (any(q, "退款", "退订", "取消", "扣多少", "扣钱"))
        {
            return "refund";
        }
        // 「怎么提交成果」「成果审核要多久」：线上任务交付，排在报名进度（含「审核」）和岗位（含「工作」）前面
        if (any(q, "成果", "线上任务", "交付", "验收"))
        {
            return "online";
        }
        if (any(q, "进度", "审核", "面试", "录用", "报名了"))
        {
            return "progress";
        }
        if (any(q, "保险", "保障", "投保", "保单"))
        {
            return "insurance";
        }
        if (any(q, "联系", "电话", "商家", "门店", "主理人", "路线", "怎么走", "地址"))
        {
            return "contact";
        }
        if (any(q, "提前入住", "入住时间", "几点入住", "退房"))
        {
            return "checkin";
        }
        if (any(q, "活动", "周末", "这周", "市集", "手作课"))
        {
            return "activity";
        }
        if (any(q, "工作", "兼职", "换工", "义工", "换宿", "岗位", "全职", "找活"))
        {
            return "job";
        }
        if (any(q, "积分", "优惠券", "会员"))
        {
            return "points";
        }
        if (any(q, "攻略", "体验官"))
        {
            return "guide";
        }
        return "unknown";
    }

    /** 问题里点名的岗位类型（kg_job.job_type）；没点名返回空，按首页推荐作答 */
    static List<String> jobTypesOf(String q)
    {
        List<String> types = new ArrayList<String>();
        // 「打工换宿」说的是换工换宿，不算兼职（只按「打工」匹配时会把兼职也答进来）
        if (any(q.replace("打工换宿", "换宿"), "兼职", "打工"))
        {
            types.add("parttime");
        }
        if (any(q, "换工", "义工", "换宿"))
        {
            types.add("volunteer");
        }
        if (any(q, "全职", "驻店"))
        {
            types.add("fulltime");
        }
        return types;
    }

    public List<Map<String, Object>> faqs()
    {
        return db.list("select faq_id, question, category, answer from kg_support_faq where status = '0' and del_flag = '0' order by sort, faq_id", p());
    }

    /** 问句里的口头字（两字一组），不拿来配常见问题：「怎么」「什么」几乎每条都有 */
    static final List<String> FAQ_STOP = Arrays.asList("怎么", "什么", "如何", "可以", "在哪", "哪里", "怎样", "请问", "我的", "我们", "你们",
            "一下", "需要", "时候", "多久", "是不", "不是", "么办", "么时", "为什", "么样", "有没", "没有");

    /**
     * 按关键词在常见问题里找最像的一条：问题去掉空格标点后两字一组，组出现在常见问题的题目里记 2 分、只在答案里记 1 分，
     * 得分最高且不少于 4 分的算匹配（至少两组字对得上题目），免得一个口头字就配上不相干的问题。常见问题只有几十条，全取出来比就行。
     */
    Map<String, Object> faqMatch(String question)
    {
        String norm = (question == null ? "" : question).replaceAll("[\\s\\p{P}\\p{S}]+", "");
        if (norm.length() < 2)
        {
            return null;
        }
        Map<String, Object> best = null;
        int bestScore = 0;
        for (Map<String, Object> f : faqs())
        {
            String title = str(f.get("question"));
            String answer = str(f.get("answer"));
            int score = 0;
            for (int i = 0; i + 2 <= norm.length(); i++)
            {
                String pair = norm.substring(i, i + 2);
                if (FAQ_STOP.contains(pair))
                {
                    continue;
                }
                score += title.contains(pair) ? 2 : (answer.contains(pair) ? 1 : 0);
            }
            if (score > bestScore)
            {
                best = f;
                bestScore = score;
            }
        }
        return bestScore >= 4 ? best : null;
    }

    /** 进行中的报名（未结束的都算），和注销前要处理完的口径一致 */
    static final List<String> APP_ACTIVE = Arrays.asList("submitted", "review_passed", "interview_booked", "interview_done", "user_confirmed", "store_confirmed",
            "contract_signed", "onboarded");

    /**
     * 按用户正在办的事预测的问题，排在客服页快捷问题的最前面：报名在进行 → 报名进度；有待入住 / 在住的订单 → 入住和退款；
     * 客服 30 天内回复过 → 查看客服回复；有进行中的线上任务 → 怎么提交成果。没登录就没有预测的。
     */
    List<String> predictedQuestions(Long me)
    {
        List<String> out = new ArrayList<String>();
        if (me == null)
        {
            return out;
        }
        if (db.count("select count(*) from kg_application where app_user_id = :u and del_flag = '0' and status in (:st)", p("u", me, "st", APP_ACTIVE)) > 0)
        {
            out.add("报名进度");
        }
        if (db.count("select count(*) from kg_stay_order where app_user_id = :u and del_flag = '0' and status in ('confirmed','checked_in') and check_out >= curdate()", p("u", me)) > 0)
        {
            out.add("入住和退款");
        }
        if (db.count("select count(*) from kg_support_ticket where app_user_id = :u and status = 'replied' and replied_time >= date_sub(now(), interval 30 day)", p("u", me)) > 0)
        {
            out.add("查看客服回复");
        }
        if (db.count("select count(*) from kg_application a join kg_job j on j.job_id = a.job_id where a.app_user_id = :u and a.del_flag = '0' "
                + "and j.job_type = 'online' and a.status = 'review_passed'", p("u", me)) > 0)
        {
            out.add("怎么提交成果");
        }
        return out;
    }

    /** 客服页的快捷问题：先放按用户状态预测的，再放这个入口的高频问题（去重） */
    public List<String> quickQuestions(Long me, String context)
    {
        List<String> out = predictedQuestions(me);
        for (String q : quickQuestions(context))
        {
            if (!out.contains(q))
            {
                out.add(q);
            }
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ask(Long me, String context, String question, String city, String orderNo)
    {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || q.length() > 200)
        {
            throw new ServiceException("问题为 1–200 字", 422);
        }
        // Authenticated history writes share the erasure lock; anonymous help stays available.
        if (me != null) com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        String intent = intentOf(q);
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> links = new ArrayList<Map<String, Object>>();
        String text;
        String source = "袋鼠全球青年业务数据";
        switch (intent)
        {
            case "progress":
                if (me == null)
                {
                    text = "登录后我可以帮你查报名进度。";
                    break;
                }
                List<Map<String, Object>> apps = db.list("select a.app_id, a.status, a.submit_time, j.title, j.cover, j.city from kg_application a join kg_job j on j.job_id = a.job_id "
                        + "where a.app_user_id = :u and a.del_flag = '0' order by a.app_id desc limit 3", p("u", me));
                if (apps.isEmpty())
                {
                    text = "你还没有报名记录，可以先去看看招募中的机会。";
                    links.add(link("去找机会", "/pages/jobs/list"));
                    break;
                }
                long active = db.count("select count(*) from kg_application where app_user_id = :u and del_flag = '0' "
                        + "and status not in ('withdrawn','review_rejected','completed','terminated')", p("u", me));
                // 「进行中」只数没结束的；下面列的是最近 3 条（可能含已完成的），所以分开说
                text = active > 0 ? "你有 " + active + " 个进行中的报名。最近的报名记录如下：" : "你现在没有进行中的报名。最近的报名记录如下：";
                for (Map<String, Object> r : apps)
                {
                    Map<String, Object> c = new LinkedHashMap<String, Object>();
                    c.put("type", "application");
                    c.put("title", r.get("title") + " · " + r.get("city"));
                    c.put("status", APP_LABEL.getOrDefault(str(r.get("status")), str(r.get("status"))));
                    c.put("meta", "报名时间：" + StaffSupport.fmt(StaffSupport.parse(r.get("submitTime")), "yyyy-MM-dd HH:mm"));
                    c.put("image", r.get("cover"));
                    c.put("url", "/pages/jobs/progress?appId=" + r.get("appId"));
                    cards.add(c);
                }
                source = "袋鼠全球青年报名系统";
                break;
            case "refund":
            {
                // 没从订单页进来（客服页「猜你想问」的「入住和退款」）：有待入住 / 在住的订单就按最近那一张试算
                String nearest = "";
                if (me != null && (orderNo == null || orderNo.isEmpty()))
                {
                    Map<String, Object> o = db.one("select order_no, check_in from kg_stay_order where app_user_id = :u and del_flag = '0' "
                            + "and status in ('confirmed','checked_in') and check_out >= curdate() order by check_in, order_id limit 1", p("u", me));
                    if (o != null && !str(o.get("orderNo")).isEmpty())
                    {
                        orderNo = str(o.get("orderNo"));
                        String in = str(o.get("checkIn"));
                        nearest = "你 " + (in.length() >= 10 ? in.substring(5, 10) : in) + " 入住的那张订单：";
                    }
                }
                if (me != null && orderNo != null && !orderNo.isEmpty())
                {
                    Map<String, Object> pv = stayOrders.refundPreview(me, orderNo);
                    long refund = num(pv.get("refundFen"));
                    text = nearest + (Boolean.TRUE.equals(pv.get("allowed"))
                            ? "按这张订单的退订政策现在取消：距入住还有 " + pv.get("hoursLeftText") + "，预计退款 ¥" + yuan(refund) + "（实付 ¥" + yuan(num(pv.get("paidFen"))) + "），"
                                    + "提交后由" + com.ruoyi.kangaroo.ext.stay.KgStayOrderService.refundReviewer() + "审核，通过后原路退回，一般 1–5 个工作日到账。"
                            : "这张订单现在不能申请退款：" + pv.get("reason") + "。");
                    Map<String, Object> c = new LinkedHashMap<String, Object>();
                    c.put("type", "refund");
                    c.put("refundFen", refund);
                    c.put("allowed", pv.get("allowed"));
                    c.put("hoursLeftText", pv.get("hoursLeftText"));
                    cards.add(c);
                    if (!nearest.isEmpty())
                    {
                        text += "\n入住、退房时间以房源页的「入住须知」为准，想提前入住请先在订单页联系门店确认。";
                        links.add(link("查看订单", "/pages/stays/order?orderNo=" + orderNo));
                    }
                    links.add(link("去订单页申请", "/pages/stays/order?orderNo=" + orderNo + "&view=refund"));
                    source = "该房源的退订政策（下单时快照）";
                    break;
                }
                text = "不同业务的退款规则不一样：\n· 住宿：按房源的退订政策（订单页可试算实际退款），提交后" + com.ruoyi.kangaroo.ext.stay.KgStayOrderService.refundReviewer() + "审核，原路退回；\n"
                        + "· 活动：在线支付的，开始前 24 小时以上取消可全额退款，人工审核；24 小时内不退；\n· 加购服务：未使用可申请退款。";
                links.add(link("我的订单", "/pages/profile/orders"));
                source = "袋鼠全球青年各业务退款规则";
                break;
            }
            case "ticket":
            {
                if (me == null)
                {
                    text = "登录后可以在这里查看客服给你的回复。";
                    break;
                }
                List<Map<String, Object>> ts = myTickets(me);
                if (ts.isEmpty())
                {
                    text = "你还没有向人工客服提过问题。需要人工帮忙时点右上角「转人工」。";
                    break;
                }
                Map<String, Object> replied = null;
                for (Map<String, Object> t : ts)
                {
                    if (replied == null && !str(t.get("reply")).isEmpty())
                    {
                        replied = t;
                    }
                }
                text = replied == null ? "你的问题客服还在处理，工作日 9:00–18:00 回复，回复会发到消息中心。"
                        : "客服最近一次回复（" + StaffSupport.cut(str(replied.get("question")), 20) + "）：\n" + str(replied.get("reply"));
                for (Map<String, Object> t : ts.subList(0, Math.min(3, ts.size())))
                {
                    Map<String, Object> c = new LinkedHashMap<String, Object>();
                    c.put("type", "ticket");
                    c.put("title", StaffSupport.cut(str(t.get("question")), 30));
                    c.put("status", t.get("statusLabel"));
                    c.put("meta", "提交时间 " + str(t.get("time")));
                    c.put("url", "/pages/home/assistant?ticket=" + t.get("ticketId"));
                    cards.add(c);
                }
                source = "我的人工客服记录";
                break;
            }
            case "online":
            {
                text = "线上任务的成果在任务交付页提交：发布方审核通过你的接单申请后，打开这条任务的交付页，在截止时间前提交成果（链接、图片和说明，可以多次提交）；"
                        + "发布方验收通过后生成结算单，在交付页确认结算。";
                if (me != null)
                {
                    for (Map<String, Object> r : db.list("select a.app_id, j.title from kg_application a join kg_job j on j.job_id = a.job_id where a.app_user_id = :u "
                            + "and a.del_flag = '0' and j.job_type = 'online' and a.status = 'review_passed' order by a.app_id desc limit 2", p("u", me)))
                    {
                        links.add(link("交付：" + StaffSupport.cut(str(r.get("title")), 10), "/pages/jobs/deliver?appId=" + r.get("appId")));
                    }
                }
                if (links.isEmpty())
                {
                    links.add(link("看看线上任务", "/pages/jobs/list?type=online"));
                }
                source = "袋鼠全球青年线上任务规则";
                break;
            }
            case "insurance":
                text = "岗位（换工 / 兼职 / 全职）在门店确认录用后，由门店在员工端为你提交投保申请，平台登记保单号后你能在「签约到店」里看到保单信息。"
                        + "活动只有活动页写明「平台已投保」的才有保险，其余请自行购买意外险。";
                if (me != null)
                {
                    for (Map<String, Object> r : db.list("select i.status, i.policy_no, i.start_date, i.end_date, j.title from kg_insurance_apply i join kg_application a on a.app_id = i.app_id "
                            + "join kg_job j on j.job_id = a.job_id where i.app_user_id = :u and i.del_flag = '0' order by i.apply_id desc limit 2", p("u", me)))
                    {
                        InsuranceState.decorate(r);
                        Map<String, Object> c = new LinkedHashMap<String, Object>();
                        c.put("type", "insurance");
                        c.put("title", r.get("title"));
                        c.put("status", r.get("statusLabel"));
                        c.put("statusLabel", r.get("statusLabel"));
                        c.put("coverageStatus", r.get("coverageStatus"));
                        c.put("policyIssued", r.get("policyIssued"));
                        String policyNo = str(r.get("policyNo")).trim();
                        c.put("meta", policyNo.isEmpty()
                                ? ("effective".equals(str(r.get("status"))) ? "保单号缺失，待核实" : "保单号待登记")
                                : "保单号 " + policyNo);
                        cards.add(c);
                    }
                }
                source = "袋鼠全球青年保单登记";
                break;
            case "contact":
                text = "联系门店：在「报名进度」页点「联系主理人」发消息，已约面试的在面试页点底部「可联系主理人或平台客服」那一行（门店员工会在消息里回复）；住宿订单的门店电话在订单页，入住当天可拨打。到店路线在「签约到店」的到店信息里打开地图。";
                links.add(link("我的报名", "/pages/jobs/progress"));
                links.add(link("我的订单", "/pages/profile/orders"));
                break;
            case "checkin":
                text = "入住、退房时间以房源页的「入住须知」为准；需要提前入住请在订单页联系门店确认，门店同意前不要提前出发。";
                if (orderNo != null && !orderNo.isEmpty())
                {
                    links.add(link("查看订单", "/pages/stays/order?orderNo=" + orderNo));
                }
                break;
            case "activity":
            {
                List<Map<String, Object>> hs = activities.hots(city == null || city.isEmpty() ? "大理" : city);
                text = hs.isEmpty() ? "近两周" + (city == null || "全部城市".equals(city) ? "" : city) + "还没有报名中的活动，可以打开「有新活动提醒我」。" : "为你找到 " + hs.size() + " 场近期可报名的活动：";
                for (Map<String, Object> h : hs.subList(0, Math.min(3, hs.size())))
                {
                    Map<String, Object> c = new LinkedHashMap<String, Object>();
                    c.put("type", "activity");
                    c.put("title", h.get("title"));
                    c.put("meta", h.get("timeText") + " · " + h.get("place"));
                    c.put("status", h.get("priceText"));
                    c.put("image", h.get("img"));
                    c.put("url", "/pages/community/activity?id=" + h.get("activityId") + "&channel=home");
                    cards.add(c);
                }
                links.add(link("全部活动", "/pages/community/activity"));
                break;
            }
            case "job":
            {
                // 问的是兼职 / 换工换宿 / 全职的，只答这一类：之前一律用首页推荐的 3 条，问「附近有什么兼职？」第一条是换工换宿
                List<String> types = jobTypesOf(q);
                List<Map<String, Object>> js;
                if (types.isEmpty())
                {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> feedJobs = (List<Map<String, Object>>) home.feed(me, city).get("jobs");
                    js = feedJobs;
                }
                else
                {
                    js = home.jobsOfTypes(city, types, 3);
                }
                String kind = types.size() == 1 ? KgHomeService.typeLabel(types.get(0)) : "";
                text = js.isEmpty() ? "当前城市暂时没有招募中的" + (kind.isEmpty() ? "岗位" : kind) + "，可以订阅新机会。" : "这些" + kind + "机会正在招募：";
                for (Map<String, Object> j : js)
                {
                    Map<String, Object> c = new LinkedHashMap<String, Object>();
                    c.put("type", "job");
                    c.put("title", j.get("title"));
                    c.put("meta", j.get("typeLabel") + " · " + j.get("city"));
                    c.put("status", j.get("price") + str(j.get("unit")));
                    c.put("image", j.get("cover"));
                    c.put("url", "/pages/jobs/detail?jobId=" + j.get("jobId"));
                    cards.add(c);
                }
                links.add(link("按条件筛选", "/pages/jobs/filter"));
                break;
            }
            case "points":
                // 之前回答「积分暂未开放」；现在积分能得（换工换宿 / 门店任务 / 邀请朋友）也能花（加急投递），优惠券仍未开放
                text = "完成换工换宿、门店任务，或邀请朋友完成第一次换工 / 线上任务，都能得积分；积分可以用来加急投递报名。可用积分和明细在「我的 → 会员信息」查看。优惠券暂未开放。";
                links.add(link("查看我的积分", "/pages/profile/index?s=3"));
                links.add(link("邀请朋友", "/pages/profile/invite"));
                break;
            case "guide":
                text = "旅行体验官（攻略作者）可以在「我的订单 → 体验官申请」提交，3 个工作日内审核。";
                links.add(link("去申请", "/pages/profile/orders?s=4"));
                break;
            default:
            {
                // 认不出意图时先到后台维护的常见问题里按关键词找最像的一条，找不到再引导转人工
                Map<String, Object> f = faqMatch(q);
                if (f != null)
                {
                    intent = "faq";
                    text = str(f.get("answer"));
                    source = "常见问题：" + str(f.get("question"));
                    a.put("faqId", f.get("faqId"));
                    break;
                }
                text = "这个问题我暂时回答不了，可以转人工客服，工作日 9:00–18:00 会在消息里回复你。";
            }
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me);
        row.put("context", StaffSupport.cut(context, 16));
        row.put("question", StaffSupport.cut(q, 255));
        row.put("intent", intent);
        row.put("answer", StaffSupport.cut(text, 1000));
        row.put("create_time", new Date());
        long logId = db.insert("kg_support_log", row);
        // 主人（2026-11-03）：「历史聊天记录，永久保存，不能删除」。登录了就把这一问一答原样记进聊天记录（V72），下次打开、换手机都还在；
        // 游客没有账号可挂，照旧只记上面那行匿名统计。和上面一样在账号锁之后写，注销中的账号写不进来
        if (me != null)
        {
            appendChat(me, context, "user", q, intent, null, null, null);
            appendChat(me, context, "bot", text, intent, source, cards, links);
        }
        a.put("logId", logId);
        a.put("intent", intent);
        a.put("question", q);
        a.put("text", text);
        a.put("cards", cards);
        a.put("links", links);
        a.put("source", source);
        a.put("canHuman", true);
        return a;
    }

    private static Map<String, Object> link(String label, String url)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("url", url);
        return m;
    }

    // ---------------------------------------------------------------- 聊天记录（V72 kg_support_chat）

    /**
     * 主人（2026-11-03）：「历史聊天记录，永久保存，不能删除」。登录用户和 AI袋鼠的每一句（问的、答的，带信息来源、卡片和跳转按钮）一行，只追加：
     * 没有给用户的删除 / 清空接口，后台也只读；只有注销账号时随账号一起清掉（KgAccountService，和 kg_support_log 同一口径）。
     * 文字最长 2000 字；卡片和按钮只留展示要用的几个字段，存成 JSON，超过 4000 字先丢卡片、再不行就不存。
     */
    static final String CHAT_TABLE = "kg_support_chat";
    static final int CHAT_TEXT_MAX = 2000;
    static final int CHAT_EXTRA_MAX = 4000;
    static final int CHAT_PAGE = 30;
    static final List<String> CHAT_CARD_KEYS = Arrays.asList("type", "title", "status", "meta", "image", "url", "refundFen", "allowed", "hoursLeftText");

    void appendChat(Long me, String context, String role, String text, String intent, String source,
            List<Map<String, Object>> cards, List<Map<String, Object>> links)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me);
        row.put("role", role);
        row.put("content", StaffSupport.cut(text == null ? "" : text, CHAT_TEXT_MAX));
        row.put("source", StaffSupport.cut(source, 64));
        row.put("intent", StaffSupport.cut(intent, 32));
        row.put("context", StaffSupport.cut(context, 16));
        row.put("extra", chatExtra(cards, links));
        row.put("create_time", new Date());
        db.insert(CHAT_TABLE, row);
    }

    static String chatExtra(List<Map<String, Object>> cards, List<Map<String, Object>> links)
    {
        List<Map<String, Object>> cs = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> c : cards == null ? new ArrayList<Map<String, Object>>() : cards)
        {
            Map<String, Object> keep = new LinkedHashMap<String, Object>();
            for (String k : CHAT_CARD_KEYS)
            {
                if (c.get(k) != null)
                {
                    keep.put(k, c.get(k));
                }
            }
            cs.add(keep);
        }
        List<Map<String, Object>> ls = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> l : links == null ? new ArrayList<Map<String, Object>>() : links)
        {
            ls.add(link(str(l.get("label")), str(l.get("url"))));
        }
        if (cs.isEmpty() && ls.isEmpty())
        {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("cards", cs);
        m.put("links", ls);
        String json = com.alibaba.fastjson2.JSON.toJSONString(m);
        if (json.length() > CHAT_EXTRA_MAX && !cs.isEmpty())
        {
            m.put("cards", new ArrayList<Object>());
            json = com.alibaba.fastjson2.JSON.toJSONString(m);
        }
        return json.length() > CHAT_EXTRA_MAX ? null : json;
    }

    /**
     * 本人的聊天记录，按时间正序给一页（默认 30 条）；before = 已经拿到的最早一条的 id，往前翻。
     * 每条：id、from（me / bot，和页面气泡同一口径）、text、source、cards、links、time。
     */
    public Map<String, Object> history(Long me, Long before, Integer size)
    {
        int n = size == null ? CHAT_PAGE : Math.max(1, Math.min(50, size));
        List<Map<String, Object>> rows = db.list("select chat_id, role, content, source, extra, create_time from " + CHAT_TABLE
                + " where app_user_id = :u" + (before == null ? "" : " and chat_id < :b") + " order by chat_id desc limit " + (n + 1),
                p("u", me, "b", before));
        boolean more = rows.size() > n;
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (int i = Math.min(rows.size(), n) - 1; i >= 0; i--)
        {
            Map<String, Object> r = rows.get(i);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", r.get("chatId"));
            m.put("from", "user".equals(str(r.get("role"))) ? "me" : "bot");
            m.put("text", str(r.get("content")));
            m.put("source", str(r.get("source")));
            Object cards = new ArrayList<Object>();
            Object links = new ArrayList<Object>();
            String extra = str(r.get("extra"));
            if (!extra.isEmpty())
            {
                try
                {
                    com.alibaba.fastjson2.JSONObject o = com.alibaba.fastjson2.JSON.parseObject(extra);
                    if (o != null && o.get("cards") != null) cards = o.get("cards");
                    if (o != null && o.get("links") != null) links = o.get("links");
                }
                catch (RuntimeException ignore)
                {
                    // 存坏了的卡片不挡文字：照常显示这一句
                }
            }
            m.put("cards", cards);
            m.put("links", links);
            m.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "yyyy-MM-dd HH:mm"));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("list", list);
        out.put("more", more);
        return out;
    }

    /** 点了「猜你想问」里的一条：答案是后台维护的常见问题原文，问和答都记进聊天记录（服务端按 faqId 取原文，不收前端传的文字） */
    @Transactional(rollbackFor = Exception.class)
    public String faqRead(Long me, String context, Long faqId)
    {
        Map<String, Object> f = faqId == null ? null
                : db.one("select faq_id, question, answer from kg_support_faq where faq_id = :f and status = '0' and del_flag = '0'", p("f", faqId));
        if (f == null || f.isEmpty())
        {
            throw new ServiceException("这条常见问题已下线", 404);
        }
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        appendChat(me, context, "user", str(f.get("question")), "faq", null, null, null);
        appendChat(me, context, "bot", str(f.get("answer")), "faq", "常见问题", null, null);
        return "ok";
    }

    public String feedback(Long me, Long logId, boolean helpful)
    {
        db.update("update kg_support_log set helpful = :h where log_id = :l and (app_user_id = :u or app_user_id is null)",
                p("h", helpful ? "1" : "0", "l", logId, "u", me));
        return "感谢反馈";
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createTicket(Long me, Map<String, Object> b)
    {
        return createTicket(me, b, false);
    }

    /** 员工身份下提交的工单，摘要开头带这个标记（不改表结构，身份记在摘要里），客服回复按它发回员工身份 */
    static final String STAFF_MARK = "【员工身份】";

    /**
     * staff=true：在员工身份下提交的（员工身份消息页的客服区 / 从那里进的助手），客服回复发回员工身份的消息中心。
     * 之前一律发到用户身份，有员工授权的账号默认在员工身份，看不到客服回了什么，红点也不提示。
     * 来源（context）仍记提交的页面（城市页 / 首页 / 我的 / 订单），身份另在摘要开头记 STAFF_MARK：之前来源整个改成 staff，客服分不清是从哪一页来的
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createTicket(Long me, Map<String, Object> b, boolean staff)
    {
        String q = str(b.get("question")).trim();
        if (q.isEmpty() || q.length() > 500)
        {
            // 之前统一提示「问题为 1–500 字」，看不懂为什么被拒。追问的长度（含自动加的「追问（工单 N）：」）由助手页提交前先查；
            // 走到这里的是消息页 / 旧版助手页直接提交的长问题，提示里不提追问，免得没追问过的人也看糊涂
            throw new ServiceException(q.isEmpty() ? "请先写下你的问题" : "问题最多 500 字（含补充内容），请删减一些", 422);
        }
        // Serialize quota and personal-data writes with account erasure.
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, me);
        if (db.count("select count(*) from kg_support_ticket where app_user_id = :u and status = 'open'", p("u", me)) >= 5)
        {
            throw new ServiceException("你已有 5 个待处理的工单，请等待客服回复", 409);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me);
        row.put("context", StaffSupport.cut(str(b.get("context")), 16));
        row.put("question", q);
        // 标记只由服务端按真实身份加：用户自己写进摘要的一律去掉，追问时带过来的原摘要也不会叠两层
        String sum = str(b.get("summary")).replace(STAFF_MARK, "");
        row.put("summary", StaffSupport.cut(staff ? STAFF_MARK + sum : sum, 500));
        row.put("related", StaffSupport.cut(str(b.get("related")), 500));
        row.put("status", "open");
        row.put("create_time", new Date());
        long id = db.insert("kg_support_ticket", row);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ticketId", id);
        m.put("message", "已提交人工客服，工作日 9:00–18:00 处理，回复会发到消息中心");
        return m;
    }

    /**
     * 客服联系方式。热线号码放在后台「参数设置」的 kangaroo.support.phone 里，
     * 没配就是没开通 —— 页面如实说明并引导走工单，不摆一个打不通的号码。
     */
    public Map<String, Object> contact()
    {
        Map<String, Object> r = db.one("select config_value from sys_config where config_key = 'kangaroo.support.phone'", p());
        String phone = r == null ? "" : str(r.get("configValue")).trim();
        Map<String, Object> h = db.one("select config_value from sys_config where config_key = 'kangaroo.support.hours'", p());
        String hours = h == null || str(h.get("configValue")).trim().isEmpty() ? "工作日 9:00–18:00" : str(h.get("configValue")).trim();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("phone", phone);
        m.put("available", Boolean.valueOf(!phone.isEmpty()));
        m.put("hours", hours);
        m.put("note", phone.isEmpty() ? "客服热线尚未开通，可以提交工单或在消息中心直接联系门店主理人。" : "服务时间 " + hours);
        return m;
    }

    public List<Map<String, Object>> myTickets(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select * from kg_support_ticket where app_user_id = :u order by ticket_id desc limit 20", p("u", me)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ticketId", r.get("ticketId"));
            m.put("question", r.get("question"));
            // 身份标记是给客服看的，用户自己的工单页不显示
            m.put("summary", r.get("summary") == null ? null : str(r.get("summary")).replace(STAFF_MARK, ""));
            m.put("status", r.get("status"));
            m.put("statusLabel", "open".equals(str(r.get("status"))) ? "处理中" : ("replied".equals(str(r.get("status"))) ? "已回复" : "已关闭"));
            m.put("reply", r.get("reply"));
            m.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "MM-dd HH:mm"));
            m.put("satisfaction", r.get("satisfaction"));
            out.add(m);
        }
        return out;
    }

    public String rate(Long me, Long ticketId, int score)
    {
        if (score < 1 || score > 5)
        {
            throw new ServiceException("评分为 1–5", 422);
        }
        if (db.update("update kg_support_ticket set satisfaction = :s, update_time = now() where ticket_id = :t and app_user_id = :u", p("s", score, "t", ticketId, "u", me)) == 0)
        {
            throw new ServiceException("工单不存在", 404);
        }
        return "感谢评价";
    }

    /** 后台回复：写回复、置为已回复、给用户发站内信 */
    @Transactional(rollbackFor = Exception.class)
    public String reply(Long ticketId, String reply, String operator)
    {
        String t = reply == null ? "" : reply.trim();
        if (t.isEmpty())
        {
            throw new ServiceException("请填写回复内容");
        }
        // Resolve only identity first, then use the same account -> ticket order as erasure.
        Map<String, Object> identity = db.one("select app_user_id from kg_support_ticket where ticket_id = :t", p("t", ticketId));
        if (identity == null) throw new ServiceException("工单不存在", 404);
        Long owner = KgJdbc.asLong(identity.get("appUserId"));
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, owner);
        Map<String, Object> r = db.must("select app_user_id, question, context, summary from kg_support_ticket where ticket_id = :t and app_user_id = :u for update",
                p("t", ticketId, "u", owner), "工单不存在或归属已变化");
        db.update("update kg_support_ticket set reply = :r, status = 'replied', replied_by = :by, replied_time = now(), update_time = now() where ticket_id = :t",
                p("r", StaffSupport.cut(t, 1000), "by", operator, "t", ticketId));
        Long uid = KgJdbc.asLong(r.get("appUserId"));
        String route = "/pages/home/assistant?ticket=" + ticketId;
        // 员工身份下提交的工单回到员工身份的消息中心；这期间员工授权已被撤销（或门店停用）的，员工身份进不去了，仍发到用户身份。
        // 「还有员工身份」与登录下发的授权同一口径（KgStaffGrantMapper.selectActiveGrantsWithStore）。
        // 员工身份消息中心对线上任务专员账号也放行 support_ticket（KgMessageMapper currentChatReception），发过去一定看得到；
        // 员工身份看摘要开头的标记，context = 'staff' 是这次改之前提交的旧工单
        // 按城市授权（本城还没门店也能进员工身份）同样算还有员工身份
        if (("staff".equals(str(r.get("context"))) || str(r.get("summary")).startsWith(STAFF_MARK))
                && db.count(com.ruoyi.kangaroo.ext.staff.CityGrantScope.COUNT_ACTIVE_IDENTITY, p("u", uid)) > 0)
        {
            support.notifyStaff(uid, "system", "客服回复了你的问题", StaffSupport.cut(t, 80), "support_ticket", ticketId, route);
        }
        else
        {
            support.notifyUser(uid, "system", "客服回复了你的问题", StaffSupport.cut(t, 80), "support_ticket", ticketId, route);
        }
        return "已回复并通知用户";
    }

    public List<String> quickQuestions(String context)
    {
        if ("order".equals(context))
        {
            return Arrays.asList("取消要扣多少？", "到店路线怎么走？", "能否提前入住？");
        }
        if ("profile".equals(context))
        {
            return Arrays.asList("报名进度", "退款规则", "保险查询", "积分明细", "攻略申请");
        }
        return Arrays.asList("这周有什么活动？", "附近有什么兼职？", "附近换工");
    }
}
