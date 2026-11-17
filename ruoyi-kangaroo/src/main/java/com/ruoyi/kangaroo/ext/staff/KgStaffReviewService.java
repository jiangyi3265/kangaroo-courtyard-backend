package com.ruoyi.kangaroo.ext.staff;

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
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.stay.KgStayOrderService;

/**
 * 评价运营（员工端）：本店评价的列表 / 详情 / 回复 / 精选 / 内部备注 / 提请平台处理。
 * 倾向按分数派生（≥4 积极、3 中性、≤2 消极），不是 AI；回复草稿来自回复模板库，发布前由员工确认；昵称一律脱敏。
 */
@Service
public class KgStaffReviewService
{
    /**
     * 精选理由清单。没有「表达积极正面」：门店挑「正面」的评价精选，等于挑好评；
     * 设计（PLAN B10 / P11）也明确任何精选都不发积分 —— 门店自己挑评价、再给作者奖励，就是以利益换好评（电子商务法 第 39 条）。
     */
    static final List<String> REASONS = Arrays.asList("内容真实有价值", "具有代表性", "帮助其他用户决策", "图文优质美观");

    static final String[] TONES = { "温和", "真诚", "专业", "活泼" };

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgCommunityGuard guard;

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

    static String mask(String nick)
    {
        if (nick == null || nick.isEmpty())
        {
            return "用户";
        }
        if (nick.length() == 1)
        {
            return nick + "*";
        }
        if (nick.length() == 2)
        {
            return nick.charAt(0) + "*";
        }
        return nick.charAt(0) + "*" + nick.charAt(nick.length() - 1);
    }

    private static String displayName(Map<String, Object> r)
    {
        return "1".equals(str(r.get("anonymous"))) ? "匿名用户" : mask(str(r.get("nickname")));
    }

    private static String source(Map<String, Object> r)
    {
        String b = str(r.get("bizType"));
        if ("application".equals(b))
        {
            return ("online".equals(str(r.get("jobType"))) ? "线上任务" : "岗位") + " · " + str(r.get("jobTitle"));
        }
        if ("stay_order".equals(b))
        {
            return "住宿 · " + str(r.get("stayName"));
        }
        return "活动 · " + str(r.get("activityTitle"));
    }

    private static final String SELECT = "select v.*, u.nickname, u.avatar, u.city user_city, j.title job_title, j.job_type, s.stay_name, a.title activity_title "
            + "from kg_review v left join kg_app_user u on u.user_id = v.app_user_id "
            + "left join kg_application ap on v.biz_type = 'application' and ap.app_id = v.biz_id left join kg_job j on j.job_id = ap.job_id "
            + "left join kg_stay_order so on v.biz_type = 'stay_order' and so.order_id = v.biz_id left join kg_stay s on s.stay_id = so.stay_id "
            + "left join kg_activity_signup g on v.biz_type = 'activity_signup' and g.signup_id = v.biz_id left join kg_activity a on a.activity_id = g.activity_id ";

    /**
     * 评价是被平台判定举报成立后隐藏的（kg_review.status 同为 '1'，和「含联系方式、待平台复核」分不开，只能看有没有已成立的举报）。
     * 之前一律显示「待平台复核」，平台处理完了门店看到的还是在等
     */
    private boolean hiddenByPlatform(Map<String, Object> r)
    {
        return "1".equals(str(r.get("status"))) && db.count("select count(*) from kg_report where target_type = 'review' and target_id = :id and status = 'upheld' "
                + "and del_flag = '0'", p("id", r.get("reviewId"))) > 0;
    }

    public Map<String, Object> list(StaffSupport.Ctx c, String tab, String src, String stars, String days, String filter)
    {
        StringBuilder w = new StringBuilder(" where v.store_id = :s and v.del_flag = '0'");
        Map<String, Object> ps = p("s", c.storeId);
        if ("pending".equals(tab))
        {
            w.append(" and (v.reply is null or v.reply = '')");
        }
        else if ("low".equals(tab))
        {
            w.append(" and v.rating <= 2");
        }
        else if ("featured".equals(tab))
        {
            w.append(" and v.featured = '1'");
        }
        if ("job".equals(src))
        {
            w.append(" and v.biz_type = 'application' and j.job_type <> 'online'");
        }
        else if ("online".equals(src))
        {
            w.append(" and v.biz_type = 'application' and j.job_type = 'online'");
        }
        else if ("stay".equals(src))
        {
            w.append(" and v.biz_type = 'stay_order'");
        }
        else if ("activity".equals(src))
        {
            w.append(" and v.biz_type = 'activity_signup'");
        }
        if (stars != null && stars.matches("[1-5]"))
        {
            w.append("2".equals(stars) ? " and v.rating <= 2" : " and v.rating = :st");
            ps.put("st", Integer.parseInt(stars));
        }
        if (days != null && days.matches("\\d{1,3}"))
        {
            w.append(" and v.create_time >= date_sub(now(), interval " + Integer.parseInt(days) + " day)");
        }
        if ("images".equals(filter))
        {
            // 2026-10-17 R3：「有图」也算带视频的评价
            w.append(" and ((v.images is not null and v.images <> '') or (v.videos is not null and v.videos <> ''))");
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list(SELECT + w + " order by v.review_id desc limit 100", ps))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("reviewId", r.get("reviewId"));
            m.put("source", source(r));
            String b = str(r.get("bizType"));
            boolean online = "application".equals(b) && "online".equals(str(r.get("jobType")));
            m.put("sourceTone", "application".equals(b) ? "green" : ("stay_order".equals(b) ? "blue" : "red"));
            m.put("sourceIcon", "application".equals(b) ? "briefcase-business" : ("stay_order".equals(b) ? "bed-double" : "calendar-days"));
            m.put("online", online);
            m.put("date", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "MM-dd"));
            m.put("avatar", "1".equals(str(r.get("anonymous"))) ? null : r.get("avatar"));
            m.put("name", displayName(r));
            long rating = num(r.get("rating"));
            m.put("stars", rating);
            m.put("score", rating + ".0");
            m.put("content", "1".equals(str(r.get("status")))
                    ? (hiddenByPlatform(r) ? "（平台已判定违规并隐藏，不再公开）" : "（含联系方式等内容，待平台复核，暂不公开）") + str(r.get("content")) : r.get("content"));
            m.put("images", str(r.get("images")).isEmpty() ? null : Arrays.asList(str(r.get("images")).split(",")));
            // 评价视频（2026-10-17 R3）：[{url, cover}]，员工端列表放一个带播放标记的缩略图
            List<Map<String, Object>> vids = com.ruoyi.kangaroo.ext.stay.KgStayReviewService.videos(r.get("videos"), r.get("videoCovers"));
            m.put("videos", vids.isEmpty() ? null : vids);
            m.put("low", rating <= 2);
            m.put("sentiment", rating >= 4 ? "积极" : (rating == 3 ? "中性" : "消极"));
            m.put("tags", str(r.get("tags")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(r.get("tags")).split(",")));
            boolean replied = !str(r.get("reply")).isEmpty();
            m.put("reply", replied ? 1 : 0);
            m.put("state", "1".equals(str(r.get("featured"))) ? "已精选" : (replied ? "已回复" : "待回复"));
            m.put("stateTone", "1".equals(str(r.get("featured"))) ? "green-text" : (replied ? "blue-text" : "amber-text"));
            rows.add(m);
        }
        Map<String, Object> counts = db.one("select count(*) total, coalesce(sum(reply is null or reply = ''), 0) pending, coalesce(sum(rating <= 2), 0) low, "
                + "coalesce(sum(featured = '1'), 0) featured from kg_review where store_id = :s and del_flag = '0'", p("s", c.storeId));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("counts", counts);
        m.put("rows", rows);
        m.put("canFeature", StaffPerms.allows(c.role, "reviews", StaffPerms.AUDIT));
        return m;
    }

    Map<String, Object> own(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = db.one(SELECT + " where v.review_id = :id and v.del_flag = '0'", p("id", id));
        if (r == null || !c.storeId.equals(KgJdbc.asLong(r.get("storeId"))))
        {
            throw new ServiceException("评价不存在或不属于当前门店", 404);
        }
        return r;
    }

    public Map<String, Object> detail(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = own(c, id);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        long rating = num(r.get("rating"));
        m.put("reviewId", id);
        m.put("name", displayName(r));
        m.put("avatar", "1".equals(str(r.get("anonymous"))) ? null : r.get("avatar"));
        m.put("stars", rating);
        m.put("score", rating + ".0");
        m.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "yyyy-MM-dd HH:mm"));
        m.put("content", r.get("content"));
        boolean byPlatform = hiddenByPlatform(r);
        m.put("hiddenForReview", "1".equals(str(r.get("status"))) && !byPlatform);
        m.put("hiddenByPlatform", byPlatform);
        m.put("images", str(r.get("images")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(r.get("images")).split(",")));
        m.put("videos", com.ruoyi.kangaroo.ext.stay.KgStayReviewService.videos(r.get("videos"), r.get("videoCovers")));
        m.put("reply", r.get("reply"));
        m.put("replyTime", r.get("replyTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(r.get("replyTime")), "yyyy-MM-dd HH:mm"));
        m.put("featured", "1".equals(str(r.get("featured"))));
        m.put("featureReasons", str(r.get("featureReasons")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(r.get("featureReasons")).split(",")));
        m.put("featureNote", r.get("featureNote"));
        m.put("featuredBy", r.get("featuredBy"));
        m.put("featuredTime", r.get("featuredTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(r.get("featuredTime")), "yyyy-MM-dd HH:mm"));
        m.put("internalNote", r.get("internalNote"));
        m.put("source", source(r));
        // 关联订单与真实履约核验（只列系统里真实存在的记录）
        String b = str(r.get("bizType"));
        Long bizId = KgJdbc.asLong(r.get("bizId"));
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        List<String> evidence = new ArrayList<String>();
        boolean verified = false;
        if ("application".equals(b))
        {
            Map<String, Object> a = db.one("select a.app_id, a.status, a.arrival_date, a.stay_days, j.title, j.city, j.address from kg_application a join kg_job j on j.job_id = a.job_id "
                    + "where a.app_id = :id", p("id", bizId));
            if (a != null)
            {
                f.put("title", a.get("title"));
                // 其余状态用报名状态机自己的中文名（原先直接把 interview_done 这类英文编码显示给员工）
                f.put("status", "terminated".equals(str(a.get("status"))) ? "已结束" : ApplicationStatus.label(str(a.get("status"))));
                f.put("no", "报名号 " + a.get("appId"));
                f.put("period", a.get("arrivalDate") == null ? "—" : str(a.get("arrivalDate")).substring(0, 10) + " 起 " + num(a.get("stayDays")) + " 天");
                f.put("placeLabel", "岗位地点");
                f.put("place", str(a.get("city")) + " · " + str(a.get("address")));
                f.put("link", "/pages/staff/applications?appId=" + a.get("appId"));
                if (db.count("select count(*) from kg_application_log where app_id = :id", p("id", bizId)) > 0)
                {
                    evidence.add("报名流转记录");
                }
                if (db.count("select count(*) from kg_application_log where app_id = :id and to_status = 'onboarded'", p("id", bizId)) > 0)
                {
                    evidence.add("入职记录");
                    verified = true;
                }
                if (db.count("select count(*) from kg_online_settlement where app_id = :id", p("id", bizId)) > 0)
                {
                    evidence.add("结算记录");
                    verified = true;
                }
            }
        }
        else if ("stay_order".equals(b))
        {
            Map<String, Object> o = db.one("select o.order_no, o.status, o.check_in, o.check_out, o.nights, o.verify_time, s.stay_name, s.city, s.area from kg_stay_order o "
                    + "join kg_stay s on s.stay_id = o.stay_id where o.order_id = :id", p("id", bizId));
            if (o != null)
            {
                f.put("title", o.get("stayName"));
                f.put("status", "completed".equals(str(o.get("status"))) ? "已离店" : KgStayOrderService.statusLabel(str(o.get("status"))));
                String no = str(o.get("orderNo"));
                f.put("no", "订单号 " + (no.length() > 8 ? no.substring(0, 4) + "****" + no.substring(no.length() - 4) : no));
                f.put("period", str(o.get("checkIn")).substring(0, 10) + " ～ " + str(o.get("checkOut")).substring(0, 10) + "（" + num(o.get("nights")) + "晚）");
                f.put("placeLabel", "房源");
                f.put("place", str(o.get("city")) + " · " + str(o.get("area")));
                f.put("link", "/pages/staff/insurance?s=3&orderNo=" + no);
                if (o.get("verifyTime") != null)
                {
                    evidence.add("订单核销记录");
                    verified = true;
                }
            }
        }
        else if ("activity_signup".equals(b))
        {
            Map<String, Object> g = db.one("select g.signup_no, g.checkin_time, a.activity_id, a.title, a.start_time, a.place, a.city from kg_activity_signup g "
                    + "join kg_activity a on a.activity_id = g.activity_id where g.signup_id = :id", p("id", bizId));
            if (g != null)
            {
                f.put("title", g.get("title"));
                f.put("status", g.get("checkinTime") != null ? "已签到" : "已报名");
                f.put("no", "报名记录 " + g.get("signupNo"));
                f.put("period", StaffSupport.fmt(StaffSupport.parse(g.get("startTime")), "yyyy-MM-dd HH:mm"));
                f.put("placeLabel", "活动地点");
                f.put("place", str(g.get("city")) + " · " + str(g.get("place")));
                f.put("link", "/pages/staff/activities?id=" + g.get("activityId"));
                if (g.get("checkinTime") != null)
                {
                    evidence.add("活动签到记录");
                    verified = true;
                }
            }
        }
        f.put("verified", verified);
        f.put("verifiedTime", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "yyyy-MM-dd HH:mm"));
        f.put("evidence", evidence);
        f.put("method", verified ? "系统自动（" + String.join("、", evidence) + "）" : "系统未找到履约记录");
        m.put("fulfillment", f);
        Long uid = KgJdbc.asLong(r.get("appUserId"));
        Map<String, Object> pub = new LinkedHashMap<String, Object>();
        Map<String, Object> u = db.one("select city, register_time from kg_app_user where user_id = :u", p("u", uid));
        pub.put("city", u == null || str(u.get("city")).isEmpty() ? "城市未填写" : u.get("city"));
        pub.put("registered", u == null || u.get("registerTime") == null ? "—" : str(u.get("registerTime")).substring(0, 10));
        pub.put("reviews", db.count("select count(*) from kg_review where app_user_id = :u and del_flag = '0' and status = '0'", p("u", uid)));
        pub.put("likes", db.count("select coalesce(sum(likes), 0) from kg_post where app_user_id = :u and del_flag = '0'", p("u", uid)));
        m.put("publicInfo", pub);
        long reports = db.count("select count(*) from kg_report where target_type = 'review' and target_id = :id and del_flag = '0'", p("id", id));
        long open = db.count("select count(*) from kg_report where target_type = 'review' and target_id = :id and del_flag = '0' and status = 'pending'", p("id", id));
        m.put("reports", reports);
        m.put("risk", byPlatform ? "已被平台隐藏" : ("1".equals(str(r.get("status"))) ? "高风险" : (open > 0 ? "中风险" : "低风险")));
        // 本店提请平台处理的进度：之前提请后详情里看不出平台处理没有、结果是什么
        Map<String, Object> mine = db.one("select status, handle_note from kg_report where target_type = 'review' and target_id = :id and create_by like 'staff:%' "
                + "and del_flag = '0' order by report_id desc limit 1", p("id", id));
        m.put("reportResult", mine == null ? null : reportResultText(str(mine.get("status")), str(mine.get("handleNote"))));
        m.put("canFeature", StaffPerms.allows(c.role, "reviews", StaffPerms.AUDIT));
        m.put("reasons", REASONS);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public String note(StaffSupport.Ctx c, Long id, String note)
    {
        own(c, id);
        String v = note == null ? "" : note.trim();
        if (v.length() > 200)
        {
            throw new ServiceException("备注不超过 200 字", 422);
        }
        db.update("update kg_review set internal_note = :n, update_by = :by, update_time = now() where review_id = :id",
                p("n", v.isEmpty() ? null : v, "by", "staff:" + c.uid(), "id", id));
        return "已保存";
    }

    /** 回复草稿：回复模板库 v1（非 AI），按语气与评分挑句子，员工确认后才发布 */
    public Map<String, Object> draft(StaffSupport.Ctx c, Long id, String tone, int seed)
    {
        Map<String, Object> r = own(c, id);
        long rating = num(r.get("rating"));
        String name = "1".equals(str(r.get("anonymous"))) ? "这位朋友" : displayName(r);
        String t = Arrays.asList(TONES).contains(tone) ? tone : "温和";
        String[] open;
        String[] body;
        String[] close;
        if (rating >= 4)
        {
            open = new String[] { "感谢" + name + "的认可与推荐！", "谢谢" + name + "的好评～", "非常感谢" + name + "认真写下的评价！" };
            body = new String[] { "很高兴这次体验让你满意，你提到的细节我们都会继续保持。", "你的肯定是团队最大的动力，我们会把这份用心延续下去。", "看到你喜欢这里，我们也很开心，欢迎把感受分享给更多朋友。" };
            close = new String[] { "期待下次再见！", "欢迎常回来看看～", "祝你旅途愉快，下次见！" };
        }
        else if (rating == 3)
        {
            open = new String[] { "谢谢" + name + "的反馈。", "感谢" + name + "花时间写下真实感受。", "收到" + name + "的评价，谢谢。" };
            body = new String[] { "你提到的不足我们已经记下，会逐项改进。", "我们会认真对照你的建议调整细节，让体验更好。", "有些地方确实还能做得更好，我们会尽快优化。" };
            close = new String[] { "期待下次给你更好的体验。", "欢迎再来检验我们的改进。", "有任何问题也可以在消息里联系我们。" };
        }
        else
        {
            open = new String[] { "非常抱歉给" + name + "带来了不好的体验。", "对不起，这次没有让" + name + "满意。", "很抱歉，" + name + "反馈的问题是我们的疏忽。" };
            body = new String[] { "我们已经和团队复盘了你提到的问题，并着手整改。", "你的意见我们都记下了，会在最短时间内改进。", "我们会认真核实并改进，也想听听你更多的建议。" };
            close = new String[] { "如方便，可以通过消息联系我们，我们会继续跟进。", "希望有机会再为你提供更好的服务。", "再次向你致歉，也感谢你的坦诚。" };
        }
        int k = Math.floorMod(seed, 3);
        String extra = "活泼".equals(t) ? " ✨" : ("专业".equals(t) ? "（门店运营团队）" : ("真诚".equals(t) ? "我们是认真的。" : ""));
        String text = open[k] + "\n\n" + body[(k + 1) % 3] + "\n\n" + close[(k + 2) % 3] + extra;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("text", text);
        m.put("tone", t);
        List<String> basis = new ArrayList<String>();
        basis.add("• 评价关键词：" + (str(r.get("tags")).isEmpty() ? "（用户未选择标签）" : str(r.get("tags")).replace(",", " / ")));
        basis.add("• 评分与倾向：" + rating + " 星 · " + (rating >= 4 ? "积极" : (rating == 3 ? "中性" : "消极")));
        basis.add("• 所选语气：" + t);
        basis.add("• 生成方式：回复模板库 v1（非 AI），请核对后发布");
        m.put("basis", basis);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reply(StaffSupport.Ctx c, Long id, String content)
    {
        Map<String, Object> r = own(c, id);
        String v = content == null ? "" : content.trim();
        if (v.isEmpty() || v.length() > 500)
        {
            throw new ServiceException("回复内容 1–500 字", 422);
        }
        if ("review".equals(guard.check(v)))
        {
            throw new ServiceException("回复里不要留电话、微信等联系方式，请在消息里沟通", 422);
        }
        db.update("update kg_review set reply = :r, reply_by = :by, reply_time = now(), update_by = :by, update_time = now() where review_id = :id",
                p("r", v, "by", StaffSupport.cut(c.name(), 64), "id", id));
        support.notifyUser(KgJdbc.asLong(r.get("appUserId")), "system", "门店回复了你的评价", c.storeName + "：" + StaffSupport.cut(v, 60), "review", id, "/pages/profile/orders?s=2&tab=%E5%B7%B2%E5%8F%91%E5%B8%83");  // tab=已发布：被回复的评价在「已发布」页签
        support.op(c, "review_reply", "回复评价", "general", StaffSupport.cut(v, 60), true, null);
        return detail(c, id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> feature(StaffSupport.Ctx c, Long id, boolean featured, List<String> reasons, String note)
    {
        own(c, id);
        List<String> rs = new ArrayList<String>();
        for (String x : reasons == null ? new ArrayList<String>() : reasons)
        {
            if (REASONS.contains(x) && !rs.contains(x))
            {
                rs.add(x);
            }
        }
        if (featured && rs.isEmpty())
        {
            throw new ServiceException("请至少选择一条精选理由", 422);
        }
        String n = note == null ? "" : note.trim();
        if (n.length() > 200)
        {
            throw new ServiceException("备注不超过 200 字", 422);
        }
        db.update("update kg_review set featured = :f, feature_reasons = :r, feature_note = :n, featured_by = :by, featured_time = now(), update_by = :by, update_time = now() "
                + "where review_id = :id", p("f", featured ? "1" : "0", "r", featured ? String.join(",", rs) : null, "n", n.isEmpty() ? null : n,
                        "by", StaffSupport.cut(c.name() + "（" + com.ruoyi.kangaroo.enums.StaffRole.label(c.role) + "）", 64), "id", id));
        support.op(c, featured ? "review_feature" : "review_unfeature", featured ? "标记精选" : "取消精选", "general", String.join("、", rs), true, null);
        return detail(c, id);
    }

    @Transactional(rollbackFor = Exception.class)
    public String report(StaffSupport.Ctx c, Long id, String reason)
    {
        own(c, id);
        String v = reason == null ? "" : reason.trim();
        if (v.isEmpty())
        {
            throw new ServiceException("请说明需要平台处理的原因", 422);
        }
        // uk_report_once (target_type, target_id, reporter_id) 不区分状态：平台处理完之后再次提请同一条评价，
        // 原先只查 pending，漏过去就撞唯一键变成 500。这里查所有状态，并给出各自能看懂的提示
        Map<String, Object> prev = db.one("select status from kg_report where target_type = 'review' and target_id = :id and reporter_id = :u for share", p("id", id, "u", c.uid()));
        if (prev != null)
        {
            throw new ServiceException("pending".equals(str(prev.get("status"))) ? "你已经提请过了，平台处理后会通知你"
                    : "这条评价的提请平台已经处理过，不能重复提请；如有异议请联系平台运营", 409);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("target_type", "review");
        row.put("target_id", id);
        row.put("reporter_id", c.uid());
        row.put("reason", "门店提请处理评价");
        row.put("detail", StaffSupport.cut("门店：" + c.storeName + "；" + v, 500));
        row.put("status", "pending");
        row.put("create_by", "staff:" + c.uid());
        row.put("create_time", new Date());
        try
        {
            db.insert("kg_report", row);
        }
        catch (org.springframework.dao.DuplicateKeyException e)
        {
            throw new ServiceException("你已经提请过了，平台处理后会通知你", 409);
        }
        support.op(c, "review_report", "提请平台处理评价", "general", v, true, null);
        return "已提交平台处理，结果会通过消息通知你";
    }

    /** 提请结果的一句话（详情页与通知共用）；处理说明里后台自动追加的「评价已隐藏」不再重复 */
    private static String reportResultText(String status, String note)
    {
        String n = note == null ? "" : note.replaceAll("[；;]?\\s*评价已隐藏$", "").trim();
        String head = "upheld".equals(status) ? "平台判定违规，评价已隐藏，不再公开展示"
                : ("rejected".equals(status) ? "平台核实后认为不违规，不做隐藏处理" : "已提请平台处理，等待平台结果");
        return n.isEmpty() || "pending".equals(status) ? head : head + "。处理说明：" + n;
    }

    /**
     * 平台处理完门店的「提请平台处理」后，通知当时提请的员工（员工身份的消息中心，点开回到这条评价）。
     * 提请时说「结果会通过消息通知你」，之前平台点「举报成立 / 不成立」后什么都不发。
     * 由后台举报处理在改完 kg_report 状态之后调用；不是门店提请的评价举报直接忽略
     */
    public void notifyReportClosed(Long reportId)
    {
        Map<String, Object> r = db.one("select target_type, target_id, reporter_id, status, handle_note, create_by from kg_report where report_id = :id",
                p("id", reportId));
        if (r == null || !"review".equals(str(r.get("targetType"))) || !str(r.get("createBy")).startsWith("staff:"))
        {
            return;
        }
        String status = str(r.get("status"));
        if (!"upheld".equals(status) && !"rejected".equals(status))
        {
            return;
        }
        Long reviewId = KgJdbc.asLong(r.get("targetId"));
        support.notifyStaff(KgJdbc.asLong(r.get("reporterId")), "system", "upheld".equals(status) ? "提请处理的评价已被平台隐藏" : "提请处理的评价平台判定不违规",
                reportResultText(status, str(r.get("handleNote"))), "review", reviewId, "/pages/staff/reviews?reviewId=" + reviewId);
    }
}
