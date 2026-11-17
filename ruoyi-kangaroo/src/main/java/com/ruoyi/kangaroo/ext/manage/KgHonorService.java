package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.manage.KgStats.num;
import static com.ruoyi.kangaroo.ext.manage.KgStats.pct;
import static com.ruoyi.kangaroo.ext.manage.KgStats.str;
import static com.ruoyi.kangaroo.ext.manage.KgStats.yuanText;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.RolePhase;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 荣誉与关怀：荣誉只由平台评定授予（系统只算「可量化的解锁进度」，达标也不自动授予）；成长里程碑 = 荣誉授予 + 营收首次突破阈值（系统写入）+ 后台补录；
 * 生日礼物每年一次、按条件逐条判定，收货信息只落在领取单上；委屈奖 / 申诉申报由后台有权限的人处理。
 */
@Service
public class KgHonorService
{
    static final long[] REVENUE_STEPS = { 100000L, 500000L, 1000000L, 3000000L, 5000000L };

    static final List<String> APPEAL_TYPES = Arrays.asList("差评申诉", "客人无理投诉", "平台规则争议", "任务验收争议", "其他");

    static final List<String> HELP_PRESETS = Arrays.asList("申请复核这条评价", "需要平台协助沟通", "需要心理疏导与支持");

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgStats stats;

    @Autowired
    private KgManageService manage;

    @Autowired
    private StaffSupport support;

    /** 线上任务交付页发起的申诉（收款 / 验收）处理完要同步结算单、通知门店；单测里没注入时按普通申报处理 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService online;

    private Map<String, Object> person(Long uid)
    {
        Map<String, Object> u = db.one("select nickname, avatar, register_time, birthday from kg_app_user where user_id = :u", p("u", uid));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("nickname", u == null ? "" : u.get("nickname"));
        m.put("avatar", u == null ? null : u.get("avatar"));
        m.put("days", u == null || u.get("registerTime") == null ? null
                : ChronoUnit.DAYS.between(LocalDate.parse(str(u.get("registerTime")).substring(0, 10)), LocalDate.now()) + 1);
        m.put("birthday", u == null ? null : u.get("birthday"));
        return m;
    }

    private List<Long> managedStores(Long uid)
    {
        // 派单补的商家店不算：那种授权不碰钱，他的荣誉进度、月营收里程碑不能算进商家的钱
        List<Long> stores = new ArrayList<Long>(KgManageService.ids(manage.myOwnBusinessStores(uid)));
        if (RolePhase.isNew())
        {
            // 切换后老主理人是城市负责人，荣誉、成长保留资格：算他有授权的门店。这只是荣誉页的范围：只算有授权的门店，不按城市展开
            for (Map<String, Object> g : db.list("select g.store_id from kg_staff_grant g join kg_store s on s.store_id = g.store_id where g.app_user_id = :u and g.role_code = 'city_lead' "
                    + "and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0' order by g.grant_id", p("u", uid)))
            {
                Long id = KgJdbc.asLong(g.get("storeId"));
                if (!stores.contains(id))
                {
                    stores.add(id);
                }
            }
        }
        return stores;
    }

    /** 可量化指标的当前值 */
    private long metric(String key, Long uid, List<Long> stores)
    {
        LocalDate to = LocalDate.now().plusDays(1);
        if ("good_rate_90d".equals(key))
        {
            Long bp = stats.goodRate(stores, to.minusDays(90), to).get("bp");
            return bp == null ? -1 : bp;
        }
        if ("activities_90d".equals(key))
        {
            return stats.activities(stores, to.minusDays(90), to).get("held");
        }
        if ("revenue_month".equals(key))
        {
            return stats.revenue(stores, LocalDate.now().withDayOfMonth(1), to).get("total");
        }
        if ("tasks_done_90d".equals(key))
        {
            return db.count("select count(*) from kg_task_assignee a join kg_task t on t.task_id = a.task_id where t.store_id in (:s) and a.status = 'done' "
                    + "and a.checked_time >= date_sub(now(), interval 90 day)", p("s", stores.isEmpty() ? Collections.singletonList(-1L) : stores));
        }
        return 0;
    }

    private static String metricText(String key, long v)
    {
        if ("good_rate_90d".equals(key))
        {
            return v < 0 ? "近 90 天暂无评价" : "好评率 " + pct(v);
        }
        if ("revenue_month".equals(key))
        {
            return "本月营收 " + yuanText(v);
        }
        if ("activities_90d".equals(key))
        {
            return "已举办 " + v + " 场";
        }
        return "已完成 " + v + " 人次";
    }

    public Map<String, Object> wall()
    {
        AppLoginUser me = AppUserContext.requireStaff();
        Long uid = me.getUserId();
        List<Long> stores = managedStores(uid);
        Map<String, Object> m = person(uid);
        List<Map<String, Object>> awarded = new ArrayList<Map<String, Object>>();
        List<Long> got = new ArrayList<Long>();
        for (Map<String, Object> a : db.list("select a.*, d.name, d.badge, d.basis def_basis from kg_honor_award a join kg_honor_def d on d.honor_id = a.honor_id "
                + "where a.app_user_id = :u and a.del_flag = '0' order by a.awarded_date desc", p("u", uid)))
        {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("name", a.get("name"));
            x.put("badge", a.get("badge"));
            LocalDate d = LocalDate.parse(str(a.get("awardedDate")).substring(0, 10));
            x.put("date", d.getYear() + "年" + String.format("%02d", d.getMonthValue()) + "月" + String.format("%02d", d.getDayOfMonth()) + "日");
            x.put("basis", str(a.get("basis")).isEmpty() ? a.get("defBasis") : a.get("basis"));
            awarded.add(x);
            got.add(KgJdbc.asLong(a.get("honorId")));
        }
        List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> d : db.list("select * from kg_honor_def where status = '0' and rule_metric <> '' order by sort, honor_id", p()))
        {
            if (got.contains(KgJdbc.asLong(d.get("honorId"))) || stores.isEmpty())
            {
                continue;
            }
            String key = str(d.get("ruleMetric"));
            long v = metric(key, uid, stores);
            long th = Math.max(1, num(d.get("ruleThreshold")));
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("name", d.get("name"));
            x.put("badge", d.get("badge"));
            x.put("rule", d.get("ruleText"));
            x.put("progress", metricText(key, v) + (v >= th ? "（已达成，待平台评定）" : ""));
            x.put("pct", Math.max(0, Math.min(100, Math.round(v * 100.0 / th))));
            pending.add(x);
        }
        m.put("awarded", awarded);
        m.put("pending", pending);
        m.put("count", awarded.size());
        return m;
    }

    /** 营收首次突破阈值时写入里程碑（幂等） */
    private void autoMilestones(Long uid, List<Long> stores)
    {
        if (stores.isEmpty())
        {
            return;
        }
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        long month = stats.revenue(stores, first, LocalDate.now().plusDays(1)).get("total");
        for (long step : REVENUE_STEPS)
        {
            if (month >= step)
            {
                String title = "月营收突破 " + yuanText(step);
                if (db.count("select count(*) from kg_growth_milestone where app_user_id = :u and title = :t and del_flag = '0'", p("u", uid, "t", title)) == 0)
                {
                    db.update("insert ignore into kg_growth_milestone (app_user_id, store_id, happened_on, title, source, create_by, create_time) values (:u, :s, :h, :t, 'system', 'system', now())",
                            p("u", uid, "s", stores.get(0), "h", first.getYear() + "." + String.format("%02d", first.getMonthValue()), "t", title));
                }
            }
        }
    }

    public Map<String, Object> growth()
    {
        AppLoginUser me = AppUserContext.requireStaff();
        Long uid = me.getUserId();
        List<Long> stores = managedStores(uid);
        if (stores.isEmpty())
        {
            throw new ServiceException("经营成长仅对主理人或管家开放", 403);
        }
        autoMilestones(uid, stores);
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        LocalDate to = LocalDate.now().plusDays(1);
        LocalDate lastFirst = first.minusMonths(1);
        LocalDate lastTo = lastFirst.plusDays(ChronoUnit.DAYS.between(first, to));
        Map<String, Long> rev = stats.revenue(stores, first, to);
        Map<String, Long> revL = stats.revenue(stores, lastFirst, lastTo);
        Map<String, Long> good = stats.goodRate(stores, first, to);
        Map<String, Long> goodL = stats.goodRate(stores, lastFirst, lastTo);
        Map<String, Long> act = stats.activities(stores, first, to);
        Map<String, Long> actL = stats.activities(stores, lastFirst, lastTo);
        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        metrics.add(KgManageService.cell("总营收", yuanText(rev.get("total")), KgStats.delta(rev.get("total"), revL.get("total")), rev.get("total") >= revL.get("total")));
        metrics.add(KgManageService.cell("订单数", String.valueOf(rev.get("orders")), KgStats.delta(rev.get("orders"), revL.get("orders")), rev.get("orders") >= revL.get("orders")));
        metrics.add(KgManageService.cell("好评率", pct(good.get("bp")), KgStats.deltaBp(good.get("bp"), goodL.get("bp")), good.get("bp") == null || goodL.get("bp") == null
                || good.get("bp") >= goodL.get("bp")));
        metrics.add(KgManageService.cell("活动参与", act.get("people") + "人次", KgStats.delta(act.get("people"), actL.get("people")), act.get("people") >= actL.get("people")));
        List<Map<String, Object>> ms = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select happened_on d, title t from kg_growth_milestone where app_user_id = :u and del_flag = '0' "
                + "union all select date_format(a.awarded_date, '%Y.%m') d, concat('获得「', d.name, '」') t from kg_honor_award a join kg_honor_def d on d.honor_id = a.honor_id "
                + "where a.app_user_id = :u and a.del_flag = '0' order by d desc limit 8", p("u", uid)))
        {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("date", r.get("d"));
            x.put("text", r.get("t"));
            ms.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("range", "（本月 " + first.getMonthValue() + "." + first.getDayOfMonth() + "-" + LocalDate.now().getMonthValue() + "." + LocalDate.now().getDayOfMonth() + "）");
        m.put("metrics", metrics);
        m.put("milestones", ms);
        m.put("goodRate", pct(good.get("bp")));
        m.put("goodStars", good.get("bp") == null ? 0 : Math.round(good.get("bp") / 2000.0 * 2) / 2.0);
        m.put("activityPeople", act.get("people"));
        long actOrders = db.count("select count(*) from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id where a.store_id in (:s) and g.fee_fen > 0 "
                + "and g.paid_time >= :f", p("s", stores, "f", first.toString()));
        m.put("activityOrders", actOrders);
        // 经营建议按经营门店算：只有城市负责人授权的账号没有经营门店，不给这一条（manage.reviews 会拒绝他）
        Map<String, Object> rv = manage.myOwnBusinessStores(uid).isEmpty() ? null : manage.reviews(null, "30");
        @SuppressWarnings("unchecked")
        List<String> advice = rv == null ? Collections.<String>emptyList() : (List<String>) rv.get("advice");
        m.put("advice", rv != null && Boolean.TRUE.equals(rv.get("adviceReal")) && !advice.isEmpty() ? advice.get(0) : null);
        return m;
    }

    // ---------------------------------------------------------------- 生日礼物

    public Map<String, Object> birthday()
    {
        AppLoginUser me = AppUserContext.requireStaff();
        Long uid = me.getUserId();
        Map<String, Object> m = person(uid);
        String bd = str(m.get("birthday"));
        LocalDate now = LocalDate.now();
        boolean isManager = db.count("select count(*) from kg_staff_grant where app_user_id = :u and role_code = 'manager' and status = '0' and del_flag = '0'", p("u", uid)) > 0;
        boolean noWeekly = false;
        Map<String, Object> g = db.one("select min(granted_time) t from kg_staff_grant where app_user_id = :u and role_code = 'manager' and status = '0' and del_flag = '0'", p("u", uid));
        long days = g == null || g.get("t") == null ? 0 : ChronoUnit.DAYS.between(LocalDate.parse(str(g.get("t")).substring(0, 10)), now) + 1;
        if (RolePhase.isNew())
        {
            // 主人定的（生日礼物牵涉花钱）：老主理人（城市负责人）保留资格，按原授权时间算；
            // 新换工主理人从切换当天起算 90 天，不用原授权时间，否则老员工一切换就满足。两种身份都有时取天数多的
            LocalDate since = null;
            if (g != null && g.get("t") != null)
            {
                since = LocalDate.parse(str(g.get("t")).substring(0, 10));
                Map<String, Object> sw = db.one("select switched_time t from kg_role_switch where id = 1", p());
                if (sw != null && sw.get("t") != null && since.isBefore(LocalDate.parse(str(sw.get("t")).substring(0, 10))))
                {
                    since = LocalDate.parse(str(sw.get("t")).substring(0, 10));
                }
            }
            days = since == null ? 0 : ChronoUnit.DAYS.between(since, now) + 1;
            boolean hadManager = since != null;
            // 切换前就是老主理人、城市负责人的行按自己的授权时间；别的（老员工后来被后台改成城市负责人、切换后才开的）从切换当天起算，
            // 不然改一下角色就白拿之前的天数（后台改角色不动 granted_time）
            Map<String, Object> lead = db.one("select min(case when b.old_role in ('manager','city_lead') then g.granted_time else greatest(g.granted_time, s.switched_time) end) t "
                    + "from kg_staff_grant g join kg_role_switch s on s.id = 1 left join kg_role_switch_backup b on b.kind = 'grant' and b.grant_id = g.grant_id "
                    + "where g.app_user_id = :u and g.role_code = 'city_lead' and g.status = '0' and g.del_flag = '0'", p("u", uid));
            if (lead != null && lead.get("t") != null)
            {
                long leadDays = ChronoUnit.DAYS.between(LocalDate.parse(str(lead.get("t")).substring(0, 10)), now) + 1;
                isManager = true;
                days = Math.max(days, leadDays);
                // 城市负责人进不了周报页，这一条他自己做不到：主人定的是老主理人保留资格，不能让它卡住。同时是换工主理人、靠换工主理人那边的天数够资格的，还是要交周报
                noWeekly = !hadManager || leadDays >= 90;
            }
        }
        boolean month = !bd.isEmpty() && Integer.parseInt(bd.substring(0, 2)) == now.getMonthValue();
        long weekly = db.count("select count(*) from kg_weekly_report r join kg_staff_grant g on g.store_id = r.store_id and g.app_user_id = :u and g.status = '0' and g.del_flag = '0' "
                + "where r.status = 'submitted' and r.submitted_time >= :f", p("u", uid, "f", now.withDayOfMonth(1).toString()));
        List<Map<String, Object>> conds = new ArrayList<Map<String, Object>>();
        conds.add(cond(RolePhase.isNew() ? "身份：换工主理人或城市负责人" : "身份：本店主理人", isManager));
        conds.add(cond((RolePhase.isNew() ? "担任满 90 天（已满 " : "担任主理人满 90 天（已满 ") + days + " 天）", days >= 90));
        conds.add(cond(bd.isEmpty() ? "设置生日后，生日当月可领取" : "生日当月可领取（生日 " + Integer.parseInt(bd.substring(0, 2)) + "月" + Integer.parseInt(bd.substring(3)) + "日）", month));
        if (!noWeekly)
        {
            conds.add(cond("本月已提交至少 1 份周报（已提交 " + weekly + " 份）", weekly > 0));
        }
        boolean ok = true;
        for (Map<String, Object> c : conds)
        {
            ok = ok && Boolean.TRUE.equals(c.get("ok"));
        }
        Map<String, Object> claim = db.one("select c.*, o.name from kg_gift_claim c join kg_gift_option o on o.gift_id = c.gift_id where c.app_user_id = :u and c.claim_year = :y",
                p("u", uid, "y", now.getYear()));
        m.put("title", month ? (RolePhase.isNew() ? "生日快乐" : "生日快乐，亲爱的主理人") : "生日关怀计划");
        m.put("birthdayText", bd.isEmpty() ? "设置生日" : "生日：" + Integer.parseInt(bd.substring(0, 2)) + "月" + Integer.parseInt(bd.substring(3)) + "日");
        m.put("conditions", conds);
        m.put("canClaim", ok && claim == null);
        m.put("gifts", db.list("select gift_id, name, subtitle, image, stock from kg_gift_option where status = '0' and stock > 0 order by sort, gift_id", p()));
        if (claim != null)
        {
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("giftName", claim.get("giftName"));
            c.put("status", claim.get("status"));
            c.put("statusText", "shipped".equals(str(claim.get("status"))) ? "已发货" + (claim.get("expressNo") == null ? "" : " · " + claim.get("expressNo")) : "已领取 · 待发货");
            c.put("receiver", claim.get("receiver"));
            c.put("phone", mask(str(claim.get("phone"))));
            c.put("address", claim.get("address"));
            m.put("claim", c);
        }
        return m;
    }

    static String mask(String phone)
    {
        return phone.length() >= 7 ? phone.substring(0, 3) + " **** " + phone.substring(phone.length() - 4) : phone;
    }

    private static Map<String, Object> cond(String text, boolean ok)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("text", text);
        m.put("ok", ok);
        return m;
    }

    public Map<String, Object> setBirthday(String birthday)
    {
        AppLoginUser me = AppUserContext.require();
        String v = birthday == null ? "" : birthday.trim();
        if (!v.isEmpty())
        {
            if (!v.matches("\\d{2}-\\d{2}"))
            {
                throw new ServiceException("生日格式为 MM-dd，例如 08-29", 422);
            }
            try
            {
                LocalDate.parse("2024-" + v);
            }
            catch (Exception e)
            {
                throw new ServiceException("没有这个日期", 422);
            }
        }
        db.update("update kg_app_user set birthday = :b where user_id = :u", p("b", v.isEmpty() ? null : v, "u", me.getUserId()));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message", v.isEmpty() ? "已清除生日" : "已保存");
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> claimGift(Map<String, Object> b)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        Map<String, Object> info = birthday();
        if (!Boolean.TRUE.equals(info.get("canClaim")))
        {
            throw new ServiceException(info.get("claim") != null ? "今年已经领取过了" : "还不满足领取条件", 409);
        }
        Long giftId = KgJdbc.asLong(b.get("giftId"));
        String receiver = str(b.get("receiver")).trim();
        String phone = str(b.get("phone")).trim();
        String address = str(b.get("address")).trim();
        if (receiver.isEmpty() || !phone.matches("1\\d{10}") || address.length() < 5)
        {
            throw new ServiceException("请填写收货人、11 位手机号和详细地址", 422);
        }
        Map<String, Object> gift = db.must("select * from kg_gift_option where gift_id = :g and status = '0'", p("g", giftId), "礼物已下架");
        if (db.update("update kg_gift_option set stock = stock - 1, update_time = now() where gift_id = :g and stock > 0", p("g", giftId)) == 0)
        {
            throw new ServiceException("这份礼物已经领完了，换一个吧", 409);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me.getUserId());
        row.put("gift_id", giftId);
        row.put("gift_name", gift.get("name"));
        row.put("claim_year", LocalDate.now().getYear());
        row.put("receiver", StaffSupport.cut(receiver, 32));
        row.put("phone", phone);
        row.put("address", StaffSupport.cut(address, 200));
        row.put("status", "pending");
        row.put("create_time", new Date());
        try
        {
            db.insert("kg_gift_claim", row);
        }
        catch (org.springframework.dao.DuplicateKeyException e)
        {
            throw new ServiceException("今年已经领取过了", 409);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message", "生日礼物已领取，发货后会通知你");
        return m;
    }

    /** 生日当天 9:00 发送生日祝福（员工身份与用户身份各一条消息中心提醒） */
    @Scheduled(cron = "0 0 9 * * ?")
    public void birthdayGreeting()
    {
        String today = String.format("%02d-%02d", LocalDate.now().getMonthValue(), LocalDate.now().getDayOfMonth());
        for (Map<String, Object> u : db.list("select user_id, nickname from kg_app_user where birthday = :b and status = '0' and del_flag = '0' limit 1000", p("b", today)))
        {
            Long uid = KgJdbc.asLong(u.get("userId"));
            boolean staff = db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0'", p("u", uid)) > 0;
            String text = "袋鼠全球青年祝你生日快乐！今天完成验收的门店任务积分双倍。";
            if (staff)
            {
                support.notifyStaff(uid, "system", "生日快乐", text + (RolePhase.isNew() ? "换工主理人、城市负责人可在「荣誉与关怀」领取生日礼物。" : "主理人可在「荣誉与关怀」领取生日礼物。"), "birthday", uid, "/pages/manage/honor?view=gift");
            }
            support.notifyUser(uid, "system", "生日快乐", text, "birthday", uid, "/pages/profile/index");
        }
    }

    // ---------------------------------------------------------------- 申报

    public Map<String, Object> appealOptions()
    {
        AppLoginUser me = AppUserContext.require();
        Long uid = me.getUserId();
        List<Map<String, Object>> opts = new ArrayList<Map<String, Object>>();
        List<Long> stores = KgManageService.ids(manage.myStores(uid));
        if (!stores.isEmpty())
        {
            for (Map<String, Object> r : db.list("select review_id, rating, content, create_time from kg_review where store_id in (:s) and del_flag = '0' "
                    + "order by review_id desc limit 10", p("s", stores)))
            {
                opts.add(opt("review", r.get("reviewId"), "评价 ★" + r.get("rating") + " " + StaffSupport.cut(str(r.get("content")), 16)));
            }
        }
        for (Map<String, Object> a : db.list("select a.id, t.title from kg_task_assignee a join kg_task t on t.task_id = a.task_id where a.app_user_id = :u "
                + "and a.status = 'done' order by a.id desc limit 5", p("u", uid)))
        {
            opts.add(opt("task_assignee", a.get("id"), "任务 " + StaffSupport.cut(str(a.get("title")), 16)));
        }
        for (Map<String, Object> o : db.list("select order_id, order_no from kg_stay_order where app_user_id = :u and del_flag = '0' order by order_id desc limit 5", p("u", uid)))
        {
            opts.add(opt("stay_order", o.get("orderId"), "住宿订单 " + o.get("orderNo")));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("types", APPEAL_TYPES);
        m.put("helps", HELP_PRESETS);
        m.put("options", opts);
        return m;
    }

    private static Map<String, Object> opt(String type, Object id, String label)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("bizType", type);
        m.put("bizId", id);
        m.put("label", label);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> appeal(Map<String, Object> b)
    {
        AppLoginUser me = AppUserContext.require();
        String type = str(b.get("type"));
        String content = str(b.get("content")).trim();
        String help = str(b.get("help")).trim();
        if (!APPEAL_TYPES.contains(type))
        {
            throw new ServiceException("请选择事件类型", 422);
        }
        if (content.length() < 20 || content.length() > 500)
        {
            throw new ServiceException("事情经过写 20–500 字", 422);
        }
        if (help.isEmpty() || help.length() > 200)
        {
            throw new ServiceException("请选择或填写希望获得的帮助（200 字以内）", 422);
        }
        // 线上任务的收款 / 验收申诉只能在这单的交付页里发起（处理后会改结算单状态），这里不接受，免得关联到别人的接单
        if (str(b.get("bizType")).startsWith("online_"))
        {
            throw new ServiceException("线上任务的收款或验收问题，请在这单的任务交付页里申诉", 422);
        }
        List<String> proofs = new ArrayList<String>();
        if (b.get("proofs") instanceof List)
        {
            for (Object o : (List<?>) b.get("proofs"))
            {
                proofs.add(str(o));
            }
        }
        if (proofs.size() > 6)
        {
            throw new ServiceException("凭证最多 6 张", 422);
        }
        if (db.count("select count(*) from kg_appeal where app_user_id = :u and create_time >= date_sub(now(), interval 1 day)", p("u", me.getUserId())) >= 5)
        {
            throw new ServiceException("今天提交得有点多了，明天再来吧", 429);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me.getUserId());
        row.put("appeal_type", type);
        row.put("biz_type", str(b.get("bizType")).isEmpty() ? null : StaffSupport.cut(str(b.get("bizType")), 32));
        row.put("biz_id", KgJdbc.asLong(b.get("bizId")));
        row.put("biz_label", StaffSupport.cut(str(b.get("bizLabel")), 100));
        row.put("content", content);
        row.put("proofs", String.join(",", proofs));
        row.put("help_wanted", help);
        row.put("status", "pending");
        row.put("create_time", new Date());
        long id = db.insert("kg_appeal", row);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("appealId", id);
        m.put("message", "申报已提交，3 个工作日内反馈到消息中心");
        return m;
    }

    // ---------------------------------------------------------------- 后台

    @Transactional(rollbackFor = Exception.class)
    public String ship(Long claimId, String expressNo, String operator)
    {
        Map<String, Object> c = db.must("select * from kg_gift_claim where claim_id = :id", p("id", claimId), "领取单不存在");
        if (!"pending".equals(str(c.get("status"))))
        {
            throw new ServiceException("已经发货了");
        }
        if (expressNo == null || expressNo.trim().isEmpty())
        {
            throw new ServiceException("请填写快递单号");
        }
        db.update("update kg_gift_claim set status = 'shipped', express_no = :e, update_by = :by, update_time = now() where claim_id = :id",
                p("e", StaffSupport.cut(expressNo.trim(), 64), "by", operator, "id", claimId));
        support.notifyStaff(KgJdbc.asLong(c.get("appUserId")), "system", "生日礼物已发货", c.get("giftName") + " 已发出，快递单号 " + expressNo.trim(), "gift_claim", claimId,
                "/pages/manage/honor?view=gift");
        return "已登记发货";
    }

    @Transactional(rollbackFor = Exception.class)
    public String handleAppeal(Long appealId, boolean resolve, String reply, String operator)
    {
        Map<String, Object> a = db.one("select * from kg_appeal where appeal_id = :id", p("id", appealId));
        // 业务拒绝带码（不存在 404、已处理 409、没填 422），之前不带码，接口是 500
        if (a == null)
        {
            throw new ServiceException("申报不存在", 404);
        }
        if (!"pending".equals(str(a.get("status"))))
        {
            throw new ServiceException("这条申报已经处理过了", 409);
        }
        if (reply == null || reply.trim().isEmpty())
        {
            throw new ServiceException("请填写处理结果", 422);
        }
        db.update("update kg_appeal set status = :s, reply = :r, handled_by = :by, handled_time = now() where appeal_id = :id",
                p("s", resolve ? "resolved" : "rejected", "r", StaffSupport.cut(reply.trim(), 500), "by", operator, "id", appealId));
        Long uid = KgJdbc.asLong(a.get("appUserId"));
        // 线上任务交付页里发起的收款 / 验收申诉：同步结算单、告诉门店，结果链到这单的交付页（之前链到经营者的申报表单，看不到这笔结算）
        if (online != null && online.appealHandled(str(a.get("bizType")), KgJdbc.asLong(a.get("bizId")), uid, resolve, reply.trim()))
        {
            return resolve ? "已处理" : "已标记不予受理";
        }
        String title = resolve ? "申报已处理" : "申报未受理";
        String text = a.get("appealType") + "：" + reply.trim();
        if (db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0'", p("u", uid)) > 0)
        {
            support.notifyStaff(uid, "system", title, text, "appeal", appealId, "/pages/manage/honor?view=appeal");
        }
        support.notifyUser(uid, "system", title, text, "appeal", appealId, "/pages/manage/honor?view=appeal");
        return resolve ? "已处理" : "已标记不予受理";
    }
}
