package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.fen;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.str;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.core.KgReportHandler;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 房源 Vlog：门店把视频号 / 抖音 / 小红书上的视频引用到房源下，经门店（主理人、管家）审核后在房源详情展示。
 *
 * 小程序内不打开外链、不嵌外部播放器：视频号在同主体时直接打开，其余复制链接到对应 App。
 * 数据只统计小程序内能真实观测到的行为（打开、点赞、评论、进房源详情、下单）；外部平台的播放量、完播率需要平台接口，
 * 没接之前页面写"—"，不编数字。
 */
@Service
public class KgStayVlogService implements KgReportHandler
{
    static final List<String> PLATFORMS = Arrays.asList("channels", "douyin", "xhs");

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgCommunityGuard guard;

    public static String platformLabel(String p)
    {
        return "douyin".equals(p) ? "抖音" : ("xhs".equals(p) ? "小红书" : "视频号");
    }

    public static String roleLabel(String r)
    {
        return "guest".equals(r) ? "住客" : ("staff".equals(r) ? "员工" : "主理人");
    }

    static String ago(Object t)
    {
        Date d = StaffSupport.parse(t);
        if (d == null)
        {
            return "";
        }
        long mins = Duration.between(d.toInstant(), new Date().toInstant()).toMinutes();
        if (mins < 60)
        {
            return Math.max(1, mins) + "分钟前";
        }
        if (mins < 1440)
        {
            return mins / 60 + "小时前";
        }
        if (mins < 1440 * 7)
        {
            return mins / 1440 + "天前";
        }
        return StaffSupport.fmt(d, "M月d日");
    }

    static String duration(Object sec)
    {
        int s = (int) fen(sec);
        return s <= 0 ? "" : String.format("%02d:%02d", s / 60, s % 60);
    }

    private Map<String, Object> brief(Map<String, Object> v)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("vlogId", v.get("vlogId"));
        m.put("stayId", v.get("stayId"));
        m.put("title", v.get("title"));
        m.put("cover", v.get("cover"));
        m.put("description", v.get("description"));
        m.put("platform", v.get("platform"));
        m.put("platformLabel", platformLabel(str(v.get("platform"))));
        m.put("finderUserName", v.get("finderUserName"));
        m.put("feedId", v.get("feedId"));
        m.put("link", v.get("link"));
        m.put("authorName", v.get("authorName"));
        m.put("authorAvatar", v.get("authorAvatar"));
        m.put("authorRole", v.get("authorRole"));
        m.put("roleLabel", roleLabel(str(v.get("authorRole"))));
        m.put("timeText", ago(v.get("publishedTime") != null ? v.get("publishedTime") : v.get("createTime")));
        m.put("duration", duration(v.get("durationSec")));
        m.put("likes", fen(v.get("likes")));
        m.put("views", fen(v.get("views")));
        m.put("comments", db.count("select count(*) from kg_stay_vlog_comment where vlog_id = :v and status = 'visible'", p("v", v.get("vlogId"))));
        m.put("status", v.get("status"));
        return m;
    }

    void event(Long vlogId, Long storeId, String event)
    {
        db.update("insert into kg_stay_vlog_event (vlog_id, store_id, event, day, cnt) values (:v, :s, :e, curdate(), 1) "
                + "on duplicate key update cnt = cnt + 1", p("v", vlogId, "s", storeId, "e", event));
    }

    private Map<String, Object> online(Long vlogId)
    {
        Map<String, Object> v = db.one("select * from kg_stay_vlog where vlog_id = :v and status = 'online' and del_flag = '0'", p("v", vlogId));
        if (v == null)
        {
            throw new ServiceException("视频不存在或已下架");
        }
        return v;
    }

    // ---------- 用户侧 ----------

    public List<Map<String, Object>> forStay(Long stayId)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> v : db.list("select * from kg_stay_vlog where stay_id = :s and status = 'online' and del_flag = '0' "
                + "order by sort, coalesce(published_time, create_time) desc limit 20", p("s", stayId)))
        {
            out.add(brief(v));
        }
        return out;
    }

    public Map<String, Object> open(Long vlogId, Long userId)
    {
        Map<String, Object> v = online(vlogId);
        db.update("update kg_stay_vlog set views = views + 1 where vlog_id = :v", p("v", vlogId));
        event(vlogId, KgJdbc.asLong(v.get("storeId")), "open");
        Map<String, Object> m = brief(v);
        m.put("liked", userId != null && db.count("select count(*) from kg_favorite where target_type = 'vlog' and target_id = :v and app_user_id = :u",
                p("v", vlogId, "u", userId)) > 0);
        return m;
    }

    /** 从 Vlog 进房源详情 / 下单时记一次（归因只看同一次会话里带过来的 vlogId） */
    public void track(Long vlogId, String event)
    {
        if (vlogId == null || !Arrays.asList("detail", "order").contains(event))
        {
            return;
        }
        Map<String, Object> v = db.one("select store_id from kg_stay_vlog where vlog_id = :v", p("v", vlogId));
        if (v != null)
        {
            event(vlogId, KgJdbc.asLong(v.get("storeId")), event);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> like(Long vlogId, Long userId)
    {
        Map<String, Object> v = online(vlogId);
        int n = db.update("insert ignore into kg_favorite (target_type, target_id, app_user_id, create_time) values ('vlog', :v, :u, now())",
                p("v", vlogId, "u", userId));
        if (n > 0)
        {
            db.update("update kg_stay_vlog set likes = likes + 1 where vlog_id = :v", p("v", vlogId));
            event(vlogId, KgJdbc.asLong(v.get("storeId")), "like");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("liked", true);
        out.put("likes", fen(db.one("select likes from kg_stay_vlog where vlog_id = :v", p("v", vlogId)).get("likes")));
        return out;
    }

    public List<Map<String, Object>> comments(Long vlogId)
    {
        List<Map<String, Object>> rows = db.list("select c.comment_id, c.content, c.reply, c.create_time, u.nickname, u.avatar from kg_stay_vlog_comment c "
                + "left join kg_app_user u on u.user_id = c.app_user_id where c.vlog_id = :v and c.status = 'visible' order by c.comment_id desc limit 50",
                p("v", vlogId));
        for (Map<String, Object> r : rows)
        {
            r.put("timeText", ago(r.get("createTime")));
        }
        return rows;
    }

    public Map<String, Object> comment(Long vlogId, Long userId, String content)
    {
        Map<String, Object> v = online(vlogId);
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() || text.length() > 200)
        {
            throw new ServiceException("评论为 1–200 字");
        }
        // 之前不做任何检查直接公开（社区帖子、评论、活动、行程都要过敏感词和联系方式检查）。
        // 命中拦截词 check 里直接 422；留联系方式或命中需人工审核的词（私下转账、先付定金等）也不公开，说清原因让用户改一下再发
        if (!"pass".equals(guard.check(text)))
        {
            throw new ServiceException(guard.hasContact(text) ? "评论里不要留手机号、微信号等联系方式，有问题可以直接问门店"
                    : "评论里有需要人工审核的内容（如私下转账、先付定金），请修改后再发", 422);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("vlog_id", vlogId);
        row.put("store_id", v.get("storeId"));
        row.put("app_user_id", userId);
        row.put("content", text);
        row.put("status", "visible");
        row.put("create_time", new Date());
        long id = db.insert("kg_stay_vlog_comment", row);
        // 之前门店收不到新评论提醒，住客的提问（怎么去、能不能做饭）只有员工自己翻「评论」才看得到
        support.notifyStore(KgJdbc.asLong(v.get("storeId")), MessageCategory.SYSTEM, "Vlog 有新评论",
                "「" + StaffSupport.cut(str(v.get("title")), 20) + "」：" + StaffSupport.cut(text, 60), "vlog", vlogId, "/pages/staff/vlog?s=4");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("commentId", id);
        return out;
    }

    // ---------- 员工侧 ----------

    private Map<String, Object> own(StaffSupport.Ctx c, Long vlogId)
    {
        Map<String, Object> v = db.one("select * from kg_stay_vlog where vlog_id = :v and del_flag = '0'", p("v", vlogId));
        if (v == null || !c.me.hasStoreAccess(KgJdbc.asLong(v.get("storeId"))))
        {
            throw new ServiceException("视频不存在或没有权限", HttpStatus.FORBIDDEN);
        }
        return v;
    }

    static String statusLabel(String st)
    {
        switch (st == null ? "" : st)
        {
            case "pending": return "待审核";
            case "online": return "已通过";
            case "rejected": return "已驳回";
            case "offline": return "已下架";
            default: return st;
        }
    }

    static String statusTone(String st)
    {
        return "online".equals(st) ? "green" : ("rejected".equals(st) || "offline".equals(st) ? "red" : "ochre");
    }

    public Map<String, Object> library(StaffSupport.Ctx c, Long stayId, String platform, String status, String sort)
    {
        StringBuilder where = new StringBuilder(" where v.store_id = :st and v.del_flag = '0'");
        Map<String, Object> ps = p("st", c.storeId);
        if (stayId != null)
        {
            where.append(" and v.stay_id = :stay");
            ps.put("stay", stayId);
        }
        if (platform != null && (PLATFORMS.contains(platform) || "guest".equals(platform)))
        {
            where.append("guest".equals(platform) ? " and v.author_role = 'guest'" : " and v.platform = :pf");
            ps.put("pf", platform);
        }
        if (status != null && Arrays.asList("pending", "online", "rejected", "offline").contains(status))
        {
            where.append(" and v.status = :stt");
            ps.put("stt", status);
        }
        String order = "old".equals(sort) ? "v.vlog_id asc" : ("likes".equals(sort) ? "v.likes desc, v.vlog_id desc" : "v.vlog_id desc");
        List<Map<String, Object>> rows = db.list("select v.*, s.stay_name from kg_stay_vlog v left join kg_stay s on s.stay_id = v.stay_id" + where
                + " order by " + order + " limit 100", ps);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> v : rows)
        {
            Map<String, Object> m = brief(v);
            m.put("stayName", v.get("stayName"));
            m.put("statusLabel", statusLabel(str(v.get("status"))));
            m.put("tone", statusTone(str(v.get("status"))));
            m.put("source", "guest".equals(str(v.get("authorRole"))) ? "住客投稿 · " + v.get("authorName")
                    : platformLabel(str(v.get("platform"))) + " · @" + v.get("authorName"));
            out.add(m);
        }
        // 周更建议：本周（周一起）每家上线房源少于 2 条的
        List<Map<String, Object>> lagging = db.list("select s.stay_id, s.stay_name, (select count(*) from kg_stay_vlog v where v.stay_id = s.stay_id "
                + "and v.del_flag = '0' and v.create_time >= date_sub(curdate(), interval weekday(curdate()) day)) cnt from kg_stay s "
                + "where s.store_id = :st and s.status = 'online' and s.del_flag = '0' having cnt < 2", p("st", c.storeId));
        List<Map<String, Object>> stays = db.list("select stay_id, stay_name from kg_stay where store_id = :st and del_flag = '0' order by sort, stay_id",
                p("st", c.storeId));
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", out);
        res.put("total", out.size());
        res.put("lagging", lagging);
        res.put("stays", stays);
        return res;
    }

    public Map<String, Object> detail(StaffSupport.Ctx c, Long vlogId)
    {
        Map<String, Object> v = own(c, vlogId);
        Map<String, Object> m = brief(v);
        Map<String, Object> s = db.one("select stay_name from kg_stay where stay_id = :s", p("s", v.get("stayId")));
        m.put("stayName", s == null ? "" : s.get("stayName"));
        for (String k : Arrays.asList("orientation", "quality", "scenes", "highlights", "authorizer", "authorizedDate", "orderNo", "auditReason", "publishedTime", "createTime"))
        {
            m.put(k, v.get(k));
        }
        m.put("authorized", "1".equals(str(v.get("authorized"))));
        m.put("statusLabel", statusLabel(str(v.get("status"))));
        m.put("source", platformLabel(str(v.get("platform"))) + " · @" + v.get("authorName"));
        return m;
    }

    /** 同步 / 提交一条视频引用：落待审核 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(StaffSupport.Ctx c, Map<String, Object> b)
    {
        Long stayId = KgJdbc.asLong(b.get("stayId"));
        Map<String, Object> s = db.one("select stay_id, store_id from kg_stay where stay_id = :s and del_flag = '0'", p("s", stayId));
        if (s == null || !c.me.hasStoreAccess(KgJdbc.asLong(s.get("storeId"))))
        {
            throw new ServiceException("房源不存在或没有权限", HttpStatus.FORBIDDEN);
        }
        String platform = str(b.get("platform"));
        if (!PLATFORMS.contains(platform))
        {
            throw new ServiceException("请选择来源平台");
        }
        String title = str(b.get("title")).trim();
        if (title.isEmpty() || title.length() > 64)
        {
            throw new ServiceException("标题为 1–64 字");
        }
        String link = str(b.get("link")).trim();
        String feed = str(b.get("feedId")).trim();
        if ("channels".equals(platform) ? feed.isEmpty() && link.isEmpty() : !link.matches("https?://\\S{4,480}"))
        {
            throw new ServiceException("channels".equals(platform) ? "请填写视频号视频 ID 或链接" : "请填写以 http(s):// 开头的视频链接");
        }
        String role = Arrays.asList("host", "guest", "staff").contains(str(b.get("authorRole"))) ? str(b.get("authorRole")) : "host";
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("stay_id", stayId);
        row.put("store_id", s.get("storeId"));
        row.put("title", title);
        row.put("cover", StaffSupport.cut(str(b.get("cover")), 255));
        row.put("description", StaffSupport.cut(str(b.get("description")).trim(), 255));
        row.put("platform", platform);
        row.put("link", link.isEmpty() ? null : link);
        row.put("finder_user_name", StaffSupport.cut(str(b.get("finderUserName")).trim(), 64));
        row.put("feed_id", feed.isEmpty() ? null : StaffSupport.cut(feed, 128));
        row.put("author_name", StaffSupport.cut(str(b.get("authorName")).trim().isEmpty() ? c.name() : str(b.get("authorName")).trim(), 32));
        row.put("author_role", role);
        row.put("duration_sec", KgJdbc.asLong(b.get("durationSec")));
        row.put("orientation", StaffSupport.cut(str(b.get("orientation")), 8));
        row.put("authorized", Boolean.parseBoolean(str(b.get("authorized"))) ? "1" : "0");
        row.put("authorizer", StaffSupport.cut(str(b.get("authorizer")), 64));
        row.put("authorized_date", Boolean.parseBoolean(str(b.get("authorized"))) ? LocalDate.now().toString() : null);
        row.put("order_no", StaffSupport.cut(str(b.get("orderNo")).trim(), 32));
        row.put("status", "pending");
        row.put("create_by", "app:" + c.uid());
        row.put("create_time", new Date());
        long id = db.insert("kg_stay_vlog", row);
        support.op(c, "vlog_create", "同步 Vlog", "general", title, true, null);
        return detail(c, id);
    }

    /**
     * 上线前的住客检查：住客投稿必须已取得出镜住客授权，并关联本店的旅居订单。
     * 门店审核通过与平台后台「通过」共用（之前后台通过不查，「已取得授权=否」的住客视频也能直接上线）
     */
    public void requireGuestReady(Map<String, Object> v)
    {
        if ("guest".equals(str(v.get("authorRole"))))
        {
            if (!"1".equals(str(v.get("authorized"))))
            {
                throw new ServiceException("住客投稿需先取得出镜住客授权");
            }
            String no = str(v.get("orderNo"));
            if (no.isEmpty() || db.count("select count(*) from kg_stay_order where order_no = :n and store_id = :s", p("n", no, "s", v.get("storeId"))) == 0)
            {
                throw new ServiceException("住客投稿需关联本店的旅居订单");
            }
        }
    }

    /** 审核通过：住客出镜 / 住客投稿必须已取得授权，住客投稿还要关联本店订单 */
    @Transactional(rollbackFor = Exception.class)
    public String approve(StaffSupport.Ctx c, Long vlogId)
    {
        Map<String, Object> v = own(c, vlogId);
        requireGuestReady(v);
        if (db.update("update kg_stay_vlog set status = 'online', audit_reason = null, update_by = :u, update_time = now() where vlog_id = :v and status = 'pending'",
                p("v", vlogId, "u", "staff:" + c.uid())) == 0)
        {
            throw new ServiceException("只有待审核的视频可以通过");
        }
        support.op(c, "vlog_approve", "Vlog 审核通过", "general", str(v.get("title")), true, null);
        return "已通过，房源详情页可见";
    }

    @Transactional(rollbackFor = Exception.class)
    public String reject(StaffSupport.Ctx c, Long vlogId, String reason)
    {
        Map<String, Object> v = own(c, vlogId);
        String why = reason == null ? "" : reason.trim();
        if (db.update("update kg_stay_vlog set status = 'rejected', audit_reason = :r, update_by = :u, update_time = now() where vlog_id = :v and status = 'pending'",
                p("v", vlogId, "r", why.isEmpty() ? "未说明原因" : StaffSupport.cut(why, 255), "u", "staff:" + c.uid())) == 0)
        {
            throw new ServiceException("只有待审核的视频可以驳回");
        }
        support.op(c, "vlog_reject", "Vlog 驳回", "general", v.get("title") + (why.isEmpty() ? "" : "：" + why), true, null);
        return "已驳回";
    }

    @Transactional(rollbackFor = Exception.class)
    public String offline(StaffSupport.Ctx c, Long vlogId, String reason)
    {
        Map<String, Object> v = own(c, vlogId);
        if (db.update("update kg_stay_vlog set status = 'offline', audit_reason = :r, update_by = :u, update_time = now() where vlog_id = :v and status = 'online'",
                p("v", vlogId, "r", StaffSupport.cut(reason == null ? "" : reason.trim(), 255), "u", "staff:" + c.uid())) == 0)
        {
            throw new ServiceException("只有已上线的视频可以下架");
        }
        support.op(c, "vlog_offline", "Vlog 下架", "general", str(v.get("title")), true, null);
        return "已下架";
    }

    /** 数据：近 days 天与前一个同长周期对比（只统计小程序内行为） */
    public Map<String, Object> data(StaffSupport.Ctx c, int days)
    {
        int d = days == 30 ? 30 : 7;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(d - 1);
        LocalDate prevStart = start.minusDays(d);
        Map<String, long[]> cur = sums(c.storeId, start, end);
        Map<String, long[]> prev = sums(c.storeId, prevStart, start.minusDays(1));
        long comments = db.count("select count(*) from kg_stay_vlog_comment where store_id = :s and create_time >= :a", p("s", c.storeId, "a", start.toString()));
        long commentsPrev = db.count("select count(*) from kg_stay_vlog_comment where store_id = :s and create_time >= :a and create_time < :b",
                p("s", c.storeId, "a", prevStart.toString(), "b", start.toString()));
        long opens = total(cur, "open");
        long orders = total(cur, "order");
        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        metrics.add(metric("小程序内打开", String.valueOf(opens), rise(opens, total(prev, "open")), "circle-play", "indigo"));
        metrics.add(metric("完播率", "—", "需接平台数据", "circle-play", "red"));
        metrics.add(metric("平均时长", "—", "需接平台数据", "circle-dot", "indigo"));
        metrics.add(metric("点赞", String.valueOf(total(cur, "like")), rise(total(cur, "like"), total(prev, "like")), "heart", "ink"));
        metrics.add(metric("评论", String.valueOf(comments), rise(comments, commentsPrev), "message-circle", "ink"));
        metrics.add(metric("房源点击", String.valueOf(total(cur, "detail")), rise(total(cur, "detail"), total(prev, "detail")), "house", "ink"));
        metrics.add(metric("预订转化", String.valueOf(orders), rise(orders, total(prev, "order")), "badge-check", "ink"));
        String rate = opens == 0 ? "—" : String.format("%.2f%%", orders * 100.0 / opens);
        metrics.add(metric("转化率", rate, "", "heart", "ink"));
        List<String> dates = new ArrayList<String>();
        List<Long> openSeries = new ArrayList<Long>();
        List<Long> orderSeries = new ArrayList<Long>();
        int step = d == 30 ? 5 : 1;
        for (LocalDate x = start; !x.isAfter(end); x = x.plusDays(step))
        {
            long o = 0;
            long od = 0;
            for (int i = 0; i < step && !x.plusDays(i).isAfter(end); i++)
            {
                String k = x.plusDays(i).toString();
                o += cur.containsKey(k) ? cur.get(k)[0] : 0;
                od += cur.containsKey(k) ? cur.get(k)[3] : 0;
            }
            dates.add(x.format(DateTimeFormatter.ofPattern("MM/dd")));
            openSeries.add(o);
            orderSeries.add(od);
        }
        List<Map<String, Object>> sources = db.list("select v.platform, v.author_role, coalesce(sum(e.cnt), 0) opens from kg_stay_vlog v "
                + "left join kg_stay_vlog_event e on e.vlog_id = v.vlog_id and e.event = 'open' and e.day >= :a "
                + "where v.store_id = :s and v.del_flag = '0' group by v.platform, v.author_role", p("s", c.storeId, "a", start.toString()));
        Map<String, Long> bySource = new LinkedHashMap<String, Long>();
        bySource.put("抖音", 0L);
        bySource.put("小红书", 0L);
        bySource.put("视频号", 0L);
        bySource.put("住客投稿", 0L);
        for (Map<String, Object> r : sources)
        {
            String label = "guest".equals(str(r.get("authorRole"))) ? "住客投稿" : platformLabel(str(r.get("platform")));
            bySource.put(label, bySource.get(label) + fen(r.get("opens")));
        }
        List<Map<String, Object>> src = new ArrayList<Map<String, Object>>();
        String[] colors = { "#35639A", "#BC4226", "#548267", "#C6963B" };
        int i = 0;
        for (Map.Entry<String, Long> e : bySource.entrySet())
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("label", e.getKey());
            m.put("opens", e.getValue());
            m.put("value", e.getValue() + (opens == 0 ? "" : String.format("（%.1f%%）", e.getValue() * 100.0 / opens)));
            m.put("color", colors[i++ % colors.length]);
            src.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("days", d);
        out.put("range", start.format(DateTimeFormatter.ofPattern("MM/dd")) + "-" + end.format(DateTimeFormatter.ofPattern("MM/dd")));
        out.put("metrics", metrics);
        out.put("dates", dates);
        out.put("opens", openSeries);
        out.put("orders", orderSeries);
        out.put("totalOpens", opens);
        out.put("sources", src);
        return out;
    }

    /** day → [open, like, detail, order] */
    private Map<String, long[]> sums(Long storeId, LocalDate a, LocalDate b)
    {
        Map<String, long[]> m = new LinkedHashMap<String, long[]>();
        for (Map<String, Object> r : db.list("select day, event, sum(cnt) cnt from kg_stay_vlog_event where store_id = :s and day between :a and :b "
                + "group by day, event", p("s", storeId, "a", a.toString(), "b", b.toString())))
        {
            String k = str(r.get("day"));
            long[] v = m.get(k);
            if (v == null)
            {
                v = new long[4];
                m.put(k, v);
            }
            int idx = Arrays.asList("open", "like", "detail", "order").indexOf(str(r.get("event")));
            if (idx >= 0)
            {
                v[idx] += fen(r.get("cnt"));
            }
        }
        return m;
    }

    private static long total(Map<String, long[]> m, String event)
    {
        int idx = Arrays.asList("open", "like", "detail", "order").indexOf(event);
        long t = 0;
        for (long[] v : m.values())
        {
            t += v[idx];
        }
        return t;
    }

    private static String rise(long cur, long prev)
    {
        if (prev == 0)
        {
            return cur == 0 ? "持平" : "新增 " + cur;
        }
        long pct = Math.round((cur - prev) * 100.0 / prev);
        return (pct >= 0 ? "↑ " : "↓ ") + Math.abs(pct) + "%";
    }

    private static Map<String, Object> metric(String label, String value, String rise, String icon, String color)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", value);
        m.put("rise", rise);
        m.put("icon", icon);
        m.put("color", color);
        return m;
    }

    /** 评论管理：tab = all / pending（待回复）/ reported（被举报）/ hidden */
    public Map<String, Object> staffComments(StaffSupport.Ctx c, String tab)
    {
        StringBuilder where = new StringBuilder(" where c.store_id = :s");
        if ("pending".equals(tab))
        {
            where.append(" and c.reply is null and c.status = 'visible'");
        }
        else if ("reported".equals(tab))
        {
            where.append(" and c.reported > 0 and c.status = 'visible'");
        }
        else if ("hidden".equals(tab))
        {
            where.append(" and c.status = 'hidden'");
        }
        List<Map<String, Object>> rows = db.list("select c.*, v.title video, u.nickname, u.avatar from kg_stay_vlog_comment c "
                + "left join kg_stay_vlog v on v.vlog_id = c.vlog_id left join kg_app_user u on u.user_id = c.app_user_id" + where
                + " order by c.comment_id desc limit 100", p("s", c.storeId));
        for (Map<String, Object> r : rows)
        {
            String st = str(r.get("status"));
            boolean reported = fen(r.get("reported")) > 0 && "visible".equals(st);
            r.put("name", str(r.get("nickname")).isEmpty() ? "用户" + r.get("appUserId") : r.get("nickname"));
            r.put("statusLabel", "hidden".equals(st) ? "已隐藏" : (reported ? "举报" : (r.get("reply") == null ? "待回复" : "已回复")));
            r.put("tone", "hidden".equals(st) ? "green" : (reported ? "red" : "ochre"));
            r.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "MM-dd HH:mm"));
            r.put("copy", r.get("content"));
            r.put("reportedFlag", reported);
            r.put("handled", r.get("handleNote") != null);
        }
        Map<String, Object> counts = db.one("select count(*) total, sum(reply is null and status = 'visible') pending, "
                + "sum(reported > 0 and status = 'visible') reported, sum(status = 'hidden') hidden from kg_stay_vlog_comment where store_id = :s",
                p("s", c.storeId));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        Map<String, Object> cnt = new LinkedHashMap<String, Object>();
        cnt.put("all", fen(counts.get("total")));
        cnt.put("pending", fen(counts.get("pending")));
        cnt.put("reported", fen(counts.get("reported")));
        cnt.put("hidden", fen(counts.get("hidden")));
        out.put("counts", cnt);
        return out;
    }

    private Map<String, Object> ownComment(StaffSupport.Ctx c, Long commentId)
    {
        Map<String, Object> r = db.one("select c.*, v.title, s.stay_name, st.contact_phone from kg_stay_vlog_comment c left join kg_stay_vlog v on v.vlog_id = c.vlog_id "
                + "left join kg_stay s on s.stay_id = v.stay_id left join kg_store st on st.store_id = c.store_id where c.comment_id = :id", p("id", commentId));
        if (r == null || !c.me.hasStoreAccess(KgJdbc.asLong(r.get("storeId"))))
        {
            throw new ServiceException("评论不存在或没有权限", HttpStatus.FORBIDDEN);
        }
        return r;
    }

    public String reply(StaffSupport.Ctx c, Long commentId, String reply)
    {
        Map<String, Object> r = ownComment(c, commentId);
        String text = reply == null ? "" : reply.trim();
        if (text.isEmpty() || text.length() > 200)
        {
            throw new ServiceException("回复为 1–200 字");
        }
        db.update("update kg_stay_vlog_comment set reply = :t, reply_by = :u, reply_time = now() where comment_id = :id",
                p("id", commentId, "t", text, "u", c.name()));
        support.notifyUser(KgJdbc.asLong(r.get("appUserId")), MessageCategory.SYSTEM, "门店回复了你的评论",
                "「" + StaffSupport.cut(str(r.get("content")), 30) + "」：" + text, "vlog", KgJdbc.asLong(r.get("vlogId")),
                "/pages/stays/detail?s=3&stayId=" + db.one("select stay_id from kg_stay_vlog where vlog_id = :v", p("v", r.get("vlogId"))).get("stayId"));
        support.op(c, "vlog_reply", "回复 Vlog 评论", "general", text, true, null);
        return "已回复";
    }

    /** 回复模板（按评论里的关键词挑一句开头，门店可改；不是 AI 生成） */
    public String draft(StaffSupport.Ctx c, Long commentId)
    {
        Map<String, Object> r = ownComment(c, commentId);
        String q = str(r.get("content"));
        String stay = str(r.get("stayName"));
        String head;
        if (q.contains("做饭") || q.contains("厨房"))
        {
            head = "您好，" + stay + "的公共区域能否做饭以房源详情页标注为准";
        }
        else if (q.contains("地铁") || q.contains("交通") || q.contains("怎么去") || q.contains("位置"))
        {
            head = "您好，到店路线可以在房源详情页点「地图 · 到店路线」查看";
        }
        else if (q.contains("价格") || q.contains("贵") || q.contains("便宜"))
        {
            head = "感谢反馈！房价以预订页日历为准，提前预订可关注早鸟价与长住优惠";
        }
        else
        {
            head = "感谢关注" + stay + "！";
        }
        String phone = str(r.get("contactPhone"));
        return head + (phone.isEmpty() ? "，有其他问题欢迎预订时备注。" : "，其他问题欢迎来电 " + phone + " 咨询。");
    }

    @Transactional(rollbackFor = Exception.class)
    public String setHidden(StaffSupport.Ctx c, Long commentId, boolean hidden, String note)
    {
        ownComment(c, commentId);
        requireNotPlatformHidden(commentId);
        db.update("update kg_stay_vlog_comment set status = :st, handle_note = :n, handled_by = :u, handled_time = now() where comment_id = :id",
                p("id", commentId, "st", hidden ? "hidden" : "visible", "n", StaffSupport.cut(note == null || note.isEmpty() ? (hidden ? "隐藏" : "恢复显示") : note, 128),
                        "u", c.name()));
        support.op(c, hidden ? "vlog_comment_hide" : "vlog_comment_show", hidden ? "隐藏评论" : "恢复评论", "general", String.valueOf(commentId), true, null);
        return hidden ? "已隐藏" : "已恢复显示";
    }

    /** 举报不成立：清零举报数并留处理记录 */
    @Transactional(rollbackFor = Exception.class)
    public String dismissReport(StaffSupport.Ctx c, Long commentId)
    {
        ownComment(c, commentId);
        requireNotPlatformHidden(commentId);
        db.update("update kg_stay_vlog_comment set reported = 0, handle_note = '举报不成立', handled_by = :u, handled_time = now() where comment_id = :id",
                p("id", commentId, "u", c.name()));
        return "已处理：举报不成立";
    }

    private void requireNotPlatformHidden(Long commentId)
    {
        // 与平台处置统一先锁评论，随后使用当前读检查最终处置，不能用事务旧快照恢复违规内容。
        db.one("select comment_id from kg_stay_vlog_comment where comment_id=:id for update", p("id", commentId));
        if (db.one("select report_id from kg_report where target_type='vlog_comment' and target_id=:id "
                + "and status='upheld' limit 1 for share", p("id", commentId)) != null)
        {
            throw new ServiceException("此评论由平台处理隐藏，请联系平台复核，门店不能撤销平台处置", 403);
        }
    }

    /** 用户举报评论 */
    public void report(Long commentId, Long userId)
    {
        if (db.update("insert ignore into kg_report (target_type, target_id, reporter_id, reason, status, create_by, create_time) "
                + "values ('vlog_comment', :c, :u, '不当评论', 'pending', :by, now())", p("c", commentId, "u", userId, "by", "app:" + userId)) > 0)
        {
            db.update("update kg_stay_vlog_comment set reported = reported + 1 where comment_id = :c", p("c", commentId));
        }
    }

    /** 违规处理日志：最近的隐藏 / 举报处理记录 */
    public List<Map<String, Object>> handleLogs(StaffSupport.Ctx c)
    {
        List<Map<String, Object>> rows = db.list("select comment_id, content, handle_note, handled_by, handled_time from kg_stay_vlog_comment "
                + "where store_id = :s and handle_note is not null order by handled_time desc limit 30", p("s", c.storeId));
        return rows;
    }

    // ---------- 举报处置（后台"举报处理"里判定成立时调用） ----------

    @Override
    public String targetType()
    {
        return "vlog_comment";
    }

    @Override
    public String uphold(long targetId, String operator)
    {
        int n = db.update("update kg_stay_vlog_comment set status = 'hidden', handle_note = '举报成立，平台隐藏', handled_by = :u, handled_time = now() "
                + "where comment_id = :id", p("id", targetId, "u", operator));
        return n > 0 ? "评论已隐藏" : "评论不存在";
    }
}
