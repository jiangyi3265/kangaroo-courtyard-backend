package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.manage.KgStats.num;
import static com.ruoyi.kangaroo.ext.manage.KgStats.str;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
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
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 门店任务：主理人 / 管家发布（指定本店员工，或开放给近 90 天在本店入职的换工认领），执行人接受 / 认领，发布人验收记工时与评价，
 * 按工时折算积分（生日当天双倍），换工的任务写入经历档案。「任务身份」只是称呼，不授予任何系统权限。
 */
@Service
public class KgTaskService
{
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityLeadScope cityLeads;

    /** 按城市授权的员工：本城门店按所选角色算（已有门店授权或撤销记录的店以门店授权为准）；单测没注入时不算 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityGrantScope cityGrants;

    /** 门店任务不认的角色（和下面 SQL 的 not in 同一份） */
    private static final List<String> NOT_STORE_TASK = Arrays.asList("online_staff", "merchant_service", "city_lead");

    static final Map<String, String> PRIORITY = new LinkedHashMap<String, String>();
    static
    {
        PRIORITY.put("high", "高优先级");
        PRIORITY.put("medium", "中优先级");
        PRIORITY.put("low", "低优先级");
    }

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    static String dateCn(Object d)
    {
        LocalDate x = LocalDate.parse(str(d).substring(0, 10));
        String[] w = { "一", "二", "三", "四", "五", "六", "日" };
        return x.getMonthValue() + "月" + x.getDayOfMonth() + "日（周" + w[x.getDayOfWeek().getValue() - 1] + "）";
    }

    /**
     * 当前账号在这家店的员工角色；不是本店员工返回 null（回库读，不只信令牌）。
     * 与登录态授权同一口径：授权有效且门店启用、未删除。门店停用后这里必须返回 null，
     * 否则按任务里的 store_id 判权的详情 / 验收 / 取消仍对停用门店放行。
     */
    String roleAt(Long uid, Long storeId)
    {
        Map<String, Object> g = db.one("select g.role_code from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.app_user_id = :u and g.store_id = :s and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0' and g.role_code not in ('online_staff','merchant_service','city_lead') limit 1",
                p("u", uid, "s", storeId));
        if (g != null)
        {
            return str(g.get("roleCode"));
        }
        // 按城市授权：这家店没有门店授权或撤销记录时，本城门店按所选角色算
        String city = cityGrants == null || uid == null || storeId == null ? null : cityGrants.roleAt(uid, storeId);
        return city == null || NOT_STORE_TASK.contains(city) ? null : city;
    }

    boolean canPublish(Long uid, Long storeId)
    {
        String r = roleAt(uid, storeId);
        return "manager".equals(r) || "keeper".equals(r) || "staff".equals(r) || cityLeadAt(uid, storeId);
    }

    /** 切换后城市负责人能管的门店：能发布、验收、看任务；不进执行人名单（roleAt 不认他，平时不做日常） */
    private boolean cityLeadAt(Long uid, Long storeId)
    {
        return cityLeads != null && cityLeads.covers(uid, storeId);
    }

    /** 近 90 天在这家店入职过的换工（非员工账号也可以认领换工任务） */
    boolean volunteerAt(Long uid, Long storeId)
    {
        return db.count("select count(*) from kg_application a where a.app_user_id = :u and a.store_id = :s and a.del_flag = '0' and a.status = 'onboarded' "
                + "and a.update_time >= date_sub(now(), interval 90 day)", p("u", uid, "s", storeId)) > 0;
    }

    /** 列表的门店：员工取当前门店（?storeId 或第一家授权）；换工取入职过的门店 */
    private List<Long> scopeStores(AppLoginUser me, Long storeId)
    {
        List<Long> out = new ArrayList<Long>();
        for (StaffGrantView g : me.getStaffGrants() == null ? new ArrayList<StaffGrantView>() : me.getStaffGrants())
        {
            if ((roleAt(me.getUserId(), g.getStoreId()) != null || cityLeadAt(me.getUserId(), g.getStoreId())) && (storeId == null || storeId.equals(g.getStoreId())))
            {
                out.add(g.getStoreId());
                break;
            }
        }
        if (out.isEmpty())
        {
            for (Map<String, Object> r : db.list("select distinct store_id from kg_application where app_user_id = :u and del_flag = '0' and status = 'onboarded' "
                    + "and update_time >= date_sub(now(), interval 90 day)", p("u", me.getUserId())))
            {
                Long sid = KgJdbc.asLong(r.get("storeId"));
                if (storeId == null || storeId.equals(sid))
                {
                    out.add(sid);
                }
            }
        }
        return out;
    }

    public Map<String, Object> list(Long storeId, String weekStart, String tab)
    {
        AppLoginUser me = AppUserContext.require();
        List<Long> stores = scopeStores(me, storeId);
        boolean publisher = !stores.isEmpty() && canPublish(me.getUserId(), stores.get(0));
        boolean staff = !stores.isEmpty() && (roleAt(me.getUserId(), stores.get(0)) != null || cityLeadAt(me.getUserId(), stores.get(0)));
        // 不指定周时：员工默认看下周（排班用）；在店换工从「我的 → 门店任务」进来默认看本周，近几天能认领的任务不藏在下一周之后
        LocalDate dft = staff ? LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)) : LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate mon = weekStart == null || weekStart.isEmpty() ? dft : LocalDate.parse(weekStart).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("weekStart", mon.toString());
        m.put("weekText", mon.getYear() + "年" + mon.getMonthValue() + "月" + mon.getDayOfMonth() + "日 - " + mon.plusDays(6).getMonthValue() + "月" + mon.plusDays(6).getDayOfMonth() + "日");
        m.put("storeId", stores.isEmpty() ? null : stores.get(0));
        m.put("canPublish", publisher);
        m.put("isStaff", staff);
        List<Map<String, Object>> staffRows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> volRows = new ArrayList<Map<String, Object>>();
        long[] counts = new long[3];
        if (!stores.isEmpty())
        {
            for (Map<String, Object> t : db.list("select t.*, s.store_name, (select count(*) from kg_task_assignee a where a.task_id = t.task_id and a.status = 'pending') pending, "
                    + "(select count(*) from kg_task_assignee a where a.task_id = t.task_id and a.status = 'accepted') doing, "
                    + "(select count(*) from kg_task_assignee a where a.task_id = t.task_id and a.status = 'done') done, "
                    + "(select count(*) from kg_task_assignee a where a.task_id = t.task_id and a.status in ('accepted','done')) taken, "
                    + "(select a.status from kg_task_assignee a where a.task_id = t.task_id and a.app_user_id = :me limit 1) my_status "
                    + "from kg_task t join kg_store s on s.store_id = t.store_id where t.store_id in (:s) and t.task_date >= :f and t.task_date < :t "
                    + "and (t.status = 'published' or (t.status = 'draft' and :pub = 1)) order by t.task_date, t.start_time, t.task_id",
                    p("s", stores, "f", mon.toString(), "t", mon.plusDays(7).toString(), "pub", publisher ? 1 : 0, "me", me.getUserId())))
            {
                boolean vol = "volunteer".equals(str(t.get("assignMode")));
                if (vol && !staff && !publisher && "draft".equals(str(t.get("status"))))
                {
                    continue;
                }
                if (!vol && !staff)
                {
                    // 换工只看开放给换工的任务
                    continue;
                }
                String bucket;
                long pending = num(t.get("pending"));
                long doing = num(t.get("doing"));
                long done = num(t.get("done"));
                long taken = num(t.get("taken"));
                boolean draft = "draft".equals(str(t.get("status")));
                boolean ended = LocalDateTime.now().isAfter(time(LocalDate.parse(str(t.get("taskDate")).substring(0, 10)), str(t.get("endTime")), 18));
                String my = str(t.get("myStatus"));
                // 换工任务：还有名额、任务也没结束，就一直留在「待认领」（有人认领了，别人也还能认领）；
                // 满员或已结束后，有人还在做是「进行中」，都验收完了是「已完成」。
                // 之前只要有一个人认领就离开「待认领」，名额没招满的任务验收完也到不了「已完成」
                boolean volOpen = vol && taken < num(t.get("quota")) && !ended;
                if (draft)
                {
                    bucket = "open";
                }
                // 按看的人分：自己已接受 / 认领、还没验收的在「进行中」，自己已验收的在「已完成」。
                // 之前只按任务算，名额没满时自己认领的任务还挂在「待认领」，「进行中」里找不到
                else if ("accepted".equals(my))
                {
                    bucket = "doing";
                }
                // 发布人（管家被指派、自己已验收）而别人还在做的，仍按任务放「进行中」：他还要在那里验收其他执行人
                else if ("done".equals(my) && !(publisher && doing > 0))
                {
                    bucket = "done";
                }
                // 已过结束时间的不再算「待认领」（认领 / 接受都会被拒）：之前已结束、没人认领的换工任务一直挂在「待认领」
                else if (!ended && (pending > 0 || volOpen))
                {
                    bucket = "open";
                }
                else if (doing > 0)
                {
                    bucket = "doing";
                }
                else if (done > 0)
                {
                    bucket = "done";
                }
                else
                {
                    // 没人做过：没结束的留在「待认领」，已结束的放进「已完成」并在标题前标「已结束」
                    bucket = ended ? "done" : "open";
                }
                counts["open".equals(bucket) ? 0 : ("doing".equals(bucket) ? 1 : 2)]++;
                if (!bucket.equals(tab == null || tab.isEmpty() ? "open" : tab))
                {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("taskId", t.get("taskId"));
                r.put("title", draft ? "【草稿】" + t.get("title") : (ended && taken == 0 ? "【已结束】" + t.get("title") : t.get("title")));
                r.put("date", dateCn(t.get("taskDate")));
                r.put("place", str(t.get("place")).isEmpty() ? t.get("storeName") : t.get("place"));
                r.put("priority", PRIORITY.getOrDefault(str(t.get("priority")), "中优先级"));
                r.put("tone", str(t.get("priority")).isEmpty() ? "medium" : t.get("priority"));
                Map<String, Object> first = db.one("select a.nickname, u.avatar from kg_task_assignee a left join kg_app_user u on u.user_id = a.app_user_id "
                        + "where a.task_id = :id and a.status <> 'cancelled' order by a.id limit 1", p("id", t.get("taskId")));
                r.put("avatar", first == null ? null : first.get("avatar"));
                r.put("owner", vol ? "" : (first == null ? "" : str(first.get("nickname")) + (str(t.get("roleName")).isEmpty() ? "" : " · " + t.get("roleName"))));
                r.put("claimed", taken + "/" + num(t.get("quota")));
                // 看的人自己在这条任务里的状态，列表行上标出来（之前只有「1/3 人已认领」，看不出其中有没有自己）
                r.put("mine", draft ? "" : "accepted".equals(my) ? (vol ? "我已认领" : "我已接受") : ("done".equals(my) ? "我已完成" : ("pending".equals(my) && !ended ? "待我接受" : "")));
                (vol ? volRows : staffRows).add(r);
            }
        }
        List<Map<String, Object>> tabs = new ArrayList<Map<String, Object>>();
        String[] labels = { "待认领", "进行中", "已完成" };
        String[] keys = { "open", "doing", "done" };
        for (int i = 0; i < 3; i++)
        {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("key", keys[i]);
            x.put("label", labels[i]);
            x.put("count", counts[i]);
            tabs.add(x);
        }
        m.put("tabs", tabs);
        m.put("staffTasks", staffRows);
        m.put("volunteerTasks", volRows);
        return m;
    }

    public Map<String, Object> detail(Long taskId)
    {
        AppLoginUser me = AppUserContext.require();
        Map<String, Object> t = db.must("select t.*, s.store_name from kg_task t join kg_store s on s.store_id = t.store_id where t.task_id = :id", p("id", taskId), "任务不存在");
        Long sid = KgJdbc.asLong(t.get("storeId"));
        boolean publisher = canPublish(me.getUserId(), sid);
        boolean staff = roleAt(me.getUserId(), sid) != null || cityLeadAt(me.getUserId(), sid);
        boolean vol = "volunteer".equals(str(t.get("assignMode")));
        Map<String, Object> mine = db.one("select * from kg_task_assignee where task_id = :id and app_user_id = :u", p("id", taskId, "u", me.getUserId()));
        if (!publisher && !staff && !(vol && volunteerAt(me.getUserId(), sid)) && mine == null)
        {
            throw new ServiceException("这个任务只对本店员工和在店换工开放", 403);
        }
        if ("draft".equals(str(t.get("status"))) && !publisher)
        {
            throw new ServiceException("任务还没发布", 404);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("taskId", taskId);
        m.put("storeId", sid);
        m.put("title", t.get("title"));
        m.put("description", t.get("description"));
        m.put("status", t.get("status"));
        m.put("modeLabel", vol ? "开放给换工" : "指定员工");
        m.put("assignMode", t.get("assignMode"));
        for (String field : Arrays.asList("taskDate", "startTime", "endTime", "estHours", "priority"))
        {
            m.put(field, t.get(field));
        }
        m.put("editPlace", t.get("place"));
        m.put("editRoleName", t.get("roleName"));
        m.put("editNote", t.get("note"));
        m.put("timeText", dateCn(t.get("taskDate")) + " " + t.get("startTime") + " - " + t.get("endTime") + " · " + trim(t.get("estHours")) + "小时");
        m.put("place", str(t.get("place")).isEmpty() ? t.get("storeName") : t.get("place"));
        m.put("roleName", str(t.get("roleName")).isEmpty() ? "任务协助" : t.get("roleName"));
        List<String> scope = new ArrayList<String>();
        for (String x : str(t.get("scopeItems")).split("\n"))
        {
            if (!x.trim().isEmpty())
            {
                scope.add(x.trim());
            }
        }
        m.put("scope", scope);
        m.put("skills", str(t.get("skills")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(t.get("skills")).split(",")));
        m.put("acceptRule", t.get("acceptRule"));
        m.put("note", str(t.get("note")).isEmpty() ? "请遵守门店现场安排，用心服务每一位来宾。" : t.get("note"));
        LocalDate d = LocalDate.parse(str(t.get("taskDate")).substring(0, 10));
        LocalDateTime start = time(d, str(t.get("startTime")), 9);
        LocalDateTime end = time(d, str(t.get("endTime")), 18);
        m.put("validText", d.getYear() + "年" + d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + fmt(start.minusMinutes(30)) + " - " + fmt(end.plusMinutes(60))
                + "（仅限本次任务，不含系统权限）");
        m.put("quota", t.get("quota"));
        m.put("taken", db.count("select count(*) from kg_task_assignee where task_id = :id and status in ('accepted','done')", p("id", taskId)));
        m.put("canPublish", publisher);
        m.put("mine", mine == null ? null : assignee(mine));
        boolean startedSoon = LocalDateTime.now().isAfter(start.minusHours(2));
        m.put("canClaim", "published".equals(str(t.get("status"))) && !LocalDateTime.now().isAfter(end)
                && ((mine != null && "pending".equals(str(mine.get("status"))))
                        || (vol && (mine == null || "cancelled".equals(str(mine.get("status")))) && (staff || volunteerAt(me.getUserId(), sid)))));
        m.put("canUnclaim", mine != null && "accepted".equals(str(mine.get("status"))) && !startedSoon);
        // 被指定、还没接受的员工可以说明「无法参加」（之前只能接受或不理，发布人得不到任何消息）
        m.put("canDecline", "published".equals(str(t.get("status"))) && mine != null && "pending".equals(str(mine.get("status"))));
        if (publisher)
        {
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> a : db.list("select * from kg_task_assignee where task_id = :id order by id", p("id", taskId)))
            {
                list.add(assignee(a));
            }
            m.put("assignees", list);
        }
        return m;
    }

    static String trim(Object v)
    {
        BigDecimal b = v == null ? BigDecimal.ZERO : new BigDecimal(str(v));
        return b.stripTrailingZeros().toPlainString();
    }

    static LocalDateTime time(LocalDate d, String hm, int dft)
    {
        try
        {
            String[] x = hm.split(":");
            return d.atTime(Integer.parseInt(x[0]), Integer.parseInt(x[1]));
        }
        catch (Exception e)
        {
            return d.atTime(dft, 0);
        }
    }

    static String fmt(LocalDateTime t)
    {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private Map<String, Object> assignee(Map<String, Object> a)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("userId", a.get("appUserId"));
        m.put("nickname", a.get("nickname"));
        m.put("source", a.get("source"));
        m.put("status", a.get("status"));
        m.put("statusText", "pending".equals(str(a.get("status"))) ? "待接受" : ("accepted".equals(str(a.get("status"))) ? "进行中"
                : ("done".equals(str(a.get("status"))) ? "已验收" : "已取消")));
        m.put("actualHours", a.get("actualHours") == null ? null : trim(a.get("actualHours")));
        m.put("rating", a.get("rating"));
        m.put("comment", a.get("comment"));
        m.put("points", a.get("points"));
        m.put("checkedName", a.get("checkedName"));
        Map<String, Object> checker = a.get("checkedBy") == null ? null : db.one("select avatar from kg_app_user where user_id = :u", p("u", a.get("checkedBy")));
        m.put("checkedAvatar", checker == null ? null : checker.get("avatar"));
        m.put("checkedTime", a.get("checkedTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(a.get("checkedTime")), "yyyy-MM-dd HH:mm"));
        m.put("proofs", str(a.get("proofs")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(a.get("proofs")).split(",")));
        m.put("expWritten", "1".equals(str(a.get("expWritten"))));
        return m;
    }

    public List<Map<String, Object>> eligibleStaff(Long storeId)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        StaffSupport.Ctx c = support.ctx(storeId);
        if (!canPublish(me.getUserId(), c.storeId))
        {
            throw new ServiceException("仅主理人或管家可以发布任务", 403);
        }
        // 线上任务专员不在名单里：发布时按 roleAt 校验执行人，线上专员会被拒（「只能指定本店在职员工」），名单要与之同一口径
        List<Map<String, Object>> rows = db.list("select g.app_user_id user_id, u.nickname, u.avatar, g.role_code from kg_staff_grant g join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.store_id = :s and g.status = '0' and g.del_flag = '0' and g.role_code not in ('online_staff','merchant_service','city_lead') order by g.grant_id", p("s", c.storeId));
        // 本城按城市授权的员工也是本店员工（roleAt 同样认他们）
        List<Map<String, Object>> holders = cityGrants == null ? null : cityGrants.holders(c.storeId);
        if (holders == null || holders.isEmpty())
        {
            return rows;
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(rows);
        for (Map<String, Object> h : holders)
        {
            String role = str(h.get("roleCode"));
            Map<String, Object> u = NOT_STORE_TASK.contains(role) ? null : db.one("select user_id, nickname, avatar from kg_app_user where user_id = :u", p("u", h.get("appUserId")));
            if (u != null)
            {
                Map<String, Object> row = new LinkedHashMap<String, Object>(u);
                row.put("roleCode", role);
                out.add(row);
            }
        }
        return out;
    }

    /** 新建 / 修改草稿，或发布（publish=true） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long taskId, Map<String, Object> b, boolean publish)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        StaffSupport.Ctx c = support.ctx(KgJdbc.asLong(b.get("storeId")));
        if (!canPublish(me.getUserId(), c.storeId))
        {
            throw new ServiceException("仅主理人或管家可以发布任务", 403);
        }
        if (taskId != null)
        {
            Map<String, Object> old = db.must("select store_id, status from kg_task where task_id = :id", p("id", taskId), "任务不存在");
            if (!c.storeId.equals(KgJdbc.asLong(old.get("storeId"))) || !"draft".equals(str(old.get("status"))))
            {
                throw new ServiceException("只有本店的草稿可以修改", 409);
            }
        }
        String title = str(b.get("title")).trim();
        if (title.isEmpty() || title.length() > 64)
        {
            throw new ServiceException("请填写任务标题（64 字以内）", 422);
        }
        LocalDate d;
        try
        {
            d = LocalDate.parse(str(b.get("taskDate")));
        }
        catch (Exception e)
        {
            throw new ServiceException("请选择任务日期", 422);
        }
        if (d.isBefore(LocalDate.now()))
        {
            throw new ServiceException("任务日期不能早于今天", 422);
        }
        String st = str(b.get("startTime"));
        String et = str(b.get("endTime"));
        if (!st.matches("\\d{2}:\\d{2}") || !et.matches("\\d{2}:\\d{2}") || st.compareTo(et) >= 0)
        {
            throw new ServiceException("请选择时段（开始早于结束）", 422);
        }
        // 发布时任务时段不能已经过去：之前能发布一条今天、结束时间已过的换工任务，在店换工都收到「门店发布了换工任务」却没法认领。草稿照常能存
        if (publish && LocalDateTime.now().isAfter(time(d, et, 18)))
        {
            throw new ServiceException("这个时段已经结束了，请改一下任务日期或时段再发布", 422);
        }
        double hours = hoursOf(b.get("estHours"));
        if (hours < 0.5 || hours > 12)
        {
            throw new ServiceException("预估工时 0.5–12 小时", 422);
        }
        String mode = "volunteer".equals(str(b.get("assignMode"))) ? "volunteer" : "staff";
        int quota = (int) Math.max(1, num(b.get("quota")));
        List<Long> assignees = new ArrayList<Long>();
        if (b.get("assignees") instanceof List)
        {
            for (Object o : (List<?>) b.get("assignees"))
            {
                Long u = KgJdbc.asLong(o);
                if (u != null && !assignees.contains(u))
                {
                    assignees.add(u);
                }
            }
        }
        if ("volunteer".equals(mode) && (quota < 1 || quota > 50))
        {
            throw new ServiceException("换工名额 1–50 人", 422);
        }
        if ("staff".equals(mode))
        {
            if (publish && assignees.isEmpty())
            {
                throw new ServiceException("请至少指定一位员工", 422);
            }
            if (assignees.size() > 5)
            {
                throw new ServiceException("最多指定 5 位员工", 422);
            }
            for (Long u : assignees)
            {
                if (roleAt(u, c.storeId) == null)
                {
                    throw new ServiceException("只能指定本店在职员工", 422);
                }
            }
            quota = Math.max(1, assignees.size());
        }
        List<String> scope = new ArrayList<String>();
        if (b.get("scope") instanceof List)
        {
            for (Object o : (List<?>) b.get("scope"))
            {
                if (!str(o).trim().isEmpty())
                {
                    scope.add(StaffSupport.cut(str(o).trim(), 60));
                }
            }
        }
        List<String> skills = new ArrayList<String>();
        if (b.get("skills") instanceof List)
        {
            for (Object o : (List<?>) b.get("skills"))
            {
                if (!str(o).trim().isEmpty())
                {
                    skills.add(StaffSupport.cut(str(o).trim(), 12));
                }
            }
        }
        if (scope.size() > 6 || skills.size() > 5)
        {
            throw new ServiceException("职责范围最多 6 条，技能最多 5 个", 422);
        }
        String pr = PRIORITY.containsKey(str(b.get("priority"))) ? str(b.get("priority")) : "medium";
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", c.storeId);
        row.put("title", title);
        row.put("description", StaffSupport.cut(str(b.get("description")).trim(), 300));
        row.put("role_name", StaffSupport.cut(str(b.get("roleName")).trim(), 32));
        row.put("scope_items", String.join("\n", scope));
        row.put("place", StaffSupport.cut(str(b.get("place")).trim(), 64));
        row.put("priority", pr);
        row.put("task_date", d.toString());
        row.put("start_time", st);
        row.put("end_time", et);
        row.put("est_hours", hours);
        row.put("skills", String.join(",", skills));
        row.put("assign_mode", mode);
        row.put("quota", quota);
        row.put("accept_rule", StaffSupport.cut(str(b.get("acceptRule")).trim(), 300));
        row.put("note", StaffSupport.cut(str(b.get("note")).trim(), 300));
        row.put("status", publish ? "published" : "draft");
        row.put("update_time", new Date());
        if (taskId == null)
        {
            row.put("creator_id", me.getUserId());
            row.put("creator_name", StaffSupport.cut(c.name(), 64));
            row.put("create_time", new Date());
            taskId = db.insert("kg_task", row);
        }
        else
        {
            db.updateRow("kg_task", "task_id", taskId, row);
        }
        db.update("delete from kg_task_assignee where task_id = :id and status = 'pending'", p("id", taskId));
        if ("staff".equals(mode))
        {
            for (Long u : assignees)
            {
                Map<String, Object> nick = db.one("select nickname from kg_app_user where user_id = :u", p("u", u));
                db.update("insert ignore into kg_task_assignee (task_id, app_user_id, nickname, source, status, create_time, update_time) values (:t, :u, :n, 'assigned', 'pending', now(), now())",
                        p("t", taskId, "u", u, "n", nick == null ? null : nick.get("nickname")));
            }
        }
        if (publish)
        {
            String text = dateCn(d.toString()) + " " + st + "-" + et + " · " + title;
            if ("staff".equals(mode))
            {
                for (Long u : assignees)
                {
                    support.notifyStaff(u, "system", "你有一个新任务", text, "task", taskId, "/pages/manage/tasks?taskId=" + taskId);
                }
            }
            else
            {
                for (Map<String, Object> v : db.list("select distinct app_user_id from kg_application where store_id = :s and del_flag = '0' and status = 'onboarded' "
                        + "and update_time >= date_sub(now(), interval 90 day)", p("s", c.storeId)))
                {
                    support.notifyUser(KgJdbc.asLong(v.get("appUserId")), "system", "门店发布了换工任务", text + "（名额 " + quota + " 人）", "task", taskId,
                            "/pages/manage/tasks?taskId=" + taskId);
                }
            }
            support.op(c, "task_publish", "发布任务", "general", title, true, null);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("taskId", taskId);
        m.put("message", publish ? "任务已发布" : "草稿已保存");
        return m;
    }

    /** 认领（换工）或接受（被指定的员工） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> claim(Long taskId)
    {
        AppLoginUser me = AppUserContext.require();
        Map<String, Object> t = db.must("select * from kg_task where task_id = :id for update", p("id", taskId), "任务不存在");
        if (!"published".equals(str(t.get("status"))))
        {
            throw new ServiceException("任务已取消或未发布", 409);
        }
        LocalDate d = LocalDate.parse(str(t.get("taskDate")).substring(0, 10));
        if (LocalDateTime.now().isAfter(time(d, str(t.get("endTime")), 18)))
        {
            throw new ServiceException("任务已结束", 409);
        }
        Long sid = KgJdbc.asLong(t.get("storeId"));
        Map<String, Object> mine = db.one("select * from kg_task_assignee where task_id = :id and app_user_id = :u", p("id", taskId, "u", me.getUserId()));
        String nick = me.getNickname() == null || me.getNickname().isEmpty() ? "用户" + me.getUserId() : me.getNickname();
        if (mine != null && "pending".equals(str(mine.get("status"))))
        {
            db.update("update kg_task_assignee set status = 'accepted', update_time = now() where id = :id", p("id", mine.get("id")));
        }
        else
        {
            if (!"volunteer".equals(str(t.get("assignMode"))))
            {
                throw new ServiceException("这是指定员工的任务，不能认领", 403);
            }
            if (mine != null && !"cancelled".equals(str(mine.get("status"))))
            {
                throw new ServiceException("你已经认领了这个任务", 409);
            }
            if (roleAt(me.getUserId(), sid) == null && !volunteerAt(me.getUserId(), sid))
            {
                throw new ServiceException("只有近 90 天在这家店入职的换工或本店员工可以认领", 403);
            }
            long taken = db.count("select count(*) from kg_task_assignee where task_id = :id and status in ('accepted','done')", p("id", taskId));
            if (taken >= num(t.get("quota")))
            {
                throw new ServiceException("名额已满", 409);
            }
            if (mine == null)
            {
                db.update("insert into kg_task_assignee (task_id, app_user_id, nickname, source, status, create_time, update_time) values (:t, :u, :n, 'claimed', 'accepted', now(), now())",
                        p("t", taskId, "u", me.getUserId(), "n", StaffSupport.cut(nick, 64)));
            }
            else
            {
                db.update("update kg_task_assignee set status = 'accepted', source = 'claimed', update_time = now() where id = :id", p("id", mine.get("id")));
            }
        }
        if (t.get("creatorId") != null)
        {
            support.notifyStaff(KgJdbc.asLong(t.get("creatorId")), "system", "任务有人认领", nick + " 认领 / 接受了「" + t.get("title") + "」", "task", taskId,
                    "/pages/manage/tasks?taskId=" + taskId);
        }
        return detail(taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> unclaim(Long taskId)
    {
        AppLoginUser me = AppUserContext.require();
        Map<String, Object> t = db.must("select * from kg_task where task_id = :id", p("id", taskId), "任务不存在");
        Map<String, Object> mine = db.must("select * from kg_task_assignee where task_id = :id and app_user_id = :u", p("id", taskId, "u", me.getUserId()), "你没有认领这个任务");
        if (!"accepted".equals(str(mine.get("status"))))
        {
            throw new ServiceException("当前状态不能取消", 409);
        }
        LocalDate d = LocalDate.parse(str(t.get("taskDate")).substring(0, 10));
        if (LocalDateTime.now().isAfter(time(d, str(t.get("startTime")), 9).minusHours(2)))
        {
            throw new ServiceException("开始前 2 小时内不能取消，请直接联系门店", 409);
        }
        db.update("update kg_task_assignee set status = case when source = 'assigned' then 'pending' else 'cancelled' end, update_time = now() where id = :id",
                p("id", mine.get("id")));
        if (t.get("creatorId") != null)
        {
            support.notifyStaff(KgJdbc.asLong(t.get("creatorId")), "system", "任务认领已取消", "「" + t.get("title") + "」有人取消了认领", "task", taskId,
                    "/pages/manage/tasks?taskId=" + taskId);
        }
        return detail(taskId);
    }

    /** 被指定的员工「无法参加」：只在还没接受时可用，这条执行记录作废，并把原因告诉发布人 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> decline(Long taskId, String reason)
    {
        AppLoginUser me = AppUserContext.require();
        Map<String, Object> t = db.must("select * from kg_task where task_id = :id", p("id", taskId), "任务不存在");
        Map<String, Object> mine = db.must("select * from kg_task_assignee where task_id = :id and app_user_id = :u for update", p("id", taskId, "u", me.getUserId()),
                "你不是这个任务的执行人");
        if (!"published".equals(str(t.get("status"))))
        {
            throw new ServiceException("任务已取消或未发布", 409);
        }
        if (!"pending".equals(str(mine.get("status"))))
        {
            throw new ServiceException("只有还没接受的任务可以选择无法参加；已接受的请点「取消认领」", 409);
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() > 100)
        {
            throw new ServiceException("原因请写在 100 字以内", 422);
        }
        db.update("update kg_task_assignee set status = 'cancelled', update_time = now() where id = :id", p("id", mine.get("id")));
        if (t.get("creatorId") != null)
        {
            String nick = me.getNickname() == null || me.getNickname().isEmpty() ? "用户" + me.getUserId() : me.getNickname();
            support.notifyStaff(KgJdbc.asLong(t.get("creatorId")), "system", "任务无法参加", nick + " 无法参加「" + t.get("title") + "」" + (why.isEmpty() ? "" : "：" + why),
                    "task", taskId, "/pages/manage/tasks?taskId=" + taskId);
        }
        return detail(taskId);
    }

    /** 验收：记实际工时、评分、评语，折算积分（1 小时 = 10 积分，生日当天双倍）；换工的任务写入经历档案 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> check(Long taskId, Map<String, Object> b)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        Map<String, Object> t = db.must("select t.*, s.store_name from kg_task t join kg_store s on s.store_id = t.store_id where t.task_id = :id", p("id", taskId), "任务不存在");
        Long sid = KgJdbc.asLong(t.get("storeId"));
        if (!canPublish(me.getUserId(), sid))
        {
            throw new ServiceException("仅本店主理人或管家可以验收", 403);
        }
        Long uid = KgJdbc.asLong(b.get("userId"));
        if (uid != null && uid.equals(me.getUserId()))
        {
            // 自己接的任务不能自己验收：一次最多给自己记 240 积分，验收人与执行人是同一个人就没有制衡
            throw new ServiceException("不能验收自己执行的任务，请让本店另一位主理人 / 管家验收", 403);
        }
        Map<String, Object> a = db.must("select * from kg_task_assignee where task_id = :id and app_user_id = :u for update", p("id", taskId, "u", uid), "执行人不存在");
        if (!"accepted".equals(str(a.get("status"))))
        {
            throw new ServiceException("只有进行中的执行人可以验收", 409);
        }
        LocalDate d = LocalDate.parse(str(t.get("taskDate")).substring(0, 10));
        if (LocalDateTime.now().isBefore(time(d, str(t.get("startTime")), 9)))
        {
            throw new ServiceException("任务还没开始，不能验收", 409);
        }
        double hours = hoursOf(b.get("hours"));
        int rating = (int) num(b.get("rating"));
        String comment = str(b.get("comment")).trim();
        if (hours < 0.5 || hours > 24 || rating < 1 || rating > 5 || comment.length() > 200)
        {
            throw new ServiceException("实际工时 0.5–24 小时、评分 1–5 星、评语 200 字以内", 422);
        }
        // 积分按工时发（每小时 10 分）：实际工时不能远超任务本身的时间窗（多 1 小时的机动），否则验收人随手填 24 小时就能发出 240 分
        LocalDateTime winStart = time(d, str(t.get("startTime")), 9);
        LocalDateTime winEnd = time(d, str(t.get("endTime")), 18);
        double window = java.time.Duration.between(winStart, winEnd).toMinutes() / 60.0;
        if (window > 0 && hours > window + 1)
        {
            throw new ServiceException("实际工时不能超过任务时间窗（" + (Math.round(window * 10) / 10.0) + " 小时）加 1 小时", 422);
        }
        List<String> proofs = new ArrayList<String>();
        if (b.get("proofs") instanceof List)
        {
            for (Object o : (List<?>) b.get("proofs"))
            {
                if (!str(o).trim().isEmpty())
                {
                    proofs.add(str(o).trim());
                }
            }
        }
        // 验收页现在真的会传凭证了（最多 6 张）：列宽 1000（V12 kg_task_assignee.proofs），超出直接说清楚，不让数据库截断报 500
        if (proofs.size() > 6 || String.join(",", proofs).length() > 1000)
        {
            throw new ServiceException("验收凭证最多 6 张", 422);
        }
        Map<String, Object> bd = db.one("select birthday from kg_app_user where user_id = :u", p("u", uid));
        String today = String.format("%02d-%02d", LocalDate.now().getMonthValue(), LocalDate.now().getDayOfMonth());
        boolean birthday = bd != null && today.equals(str(bd.get("birthday")));
        int points = (int) Math.round(hours * 10) * (birthday ? 2 : 1);
        String checker = me.getNickname() == null || me.getNickname().isEmpty() ? "主理人" : me.getNickname();
        db.update("update kg_task_assignee set status = 'done', actual_hours = :h, rating = :r, comment = :c, points = :p, checked_by = :by, checked_name = :bn, "
                + "checked_time = now(), proofs = :pf, update_time = now() where id = :id",
                p("h", hours, "r", rating, "c", comment, "p", points, "by", me.getUserId(), "bn", StaffSupport.cut(checker, 64), "pf", String.join(",", proofs), "id", a.get("id")));
        db.update("insert ignore into kg_point_entry (app_user_id, delta, reason, biz_type, biz_id, create_time) values (:u, :d, :r, 'task_assignee', :b, now())",
                p("u", uid, "d", points, "r", StaffSupport.cut("任务验收：" + t.get("title") + (birthday ? "（生日双倍）" : ""), 64), "b", a.get("id")));
        boolean wrote = false;
        if (roleAt(uid, sid) == null)
        {
            db.update("insert into kg_resume_exp (app_user_id, kind, title, place, start_date, end_date, content, source, create_time) "
                    + "values (:u, 'volunteer', :t, :pl, :d, :d, :c, 'platform', now())",
                    p("u", uid, "t", StaffSupport.cut(str(t.get("title")), 64), "pl", StaffSupport.cut(str(t.get("storeName")), 64), "d", d.toString(),
                            "c", StaffSupport.cut("门店任务 · 实际 " + trim(hours) + " 小时 · " + rating + " 星" + (comment.isEmpty() ? "" : " · " + comment), 200)));
            db.update("update kg_task_assignee set exp_written = '1' where id = :id", p("id", a.get("id")));
            wrote = true;
        }
        String text = "「" + t.get("title") + "」已验收：" + trim(hours) + " 小时，" + rating + " 星，折算 " + points + " 积分" + (wrote ? "，已写入你的经历档案" : "");
        if (roleAt(uid, sid) != null)
        {
            support.notifyStaff(uid, "system", "任务已验收", text, "task", taskId, "/pages/manage/tasks?taskId=" + taskId);
        }
        else
        {
            support.notifyUser(uid, "system", "任务已验收", text, "task", taskId, "/pages/manage/tasks?taskId=" + taskId);
        }
        return detail(taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public String cancel(Long taskId, String reason)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        Map<String, Object> t = db.must("select * from kg_task where task_id = :id", p("id", taskId), "任务不存在");
        if (!canPublish(me.getUserId(), KgJdbc.asLong(t.get("storeId"))))
        {
            throw new ServiceException("仅本店主理人或管家可以取消任务", 403);
        }
        if ("cancelled".equals(str(t.get("status"))))
        {
            throw new ServiceException("任务已取消");
        }
        if (db.count("select count(*) from kg_task_assignee where task_id = :id and status = 'done'", p("id", taskId)) > 0)
        {
            throw new ServiceException("已有验收记录的任务不能取消", 409);
        }
        // 草稿也走这里删除（列表里不再显示）：草稿没发布过，预先选好的执行人从没收到过任务，不发「任务已取消」
        boolean draft = "draft".equals(str(t.get("status")));
        db.update("update kg_task set status = 'cancelled', update_time = now() where task_id = :id", p("id", taskId));
        for (Map<String, Object> a : draft ? new ArrayList<Map<String, Object>>()
                : db.list("select app_user_id from kg_task_assignee where task_id = :id and status in ('pending','accepted')", p("id", taskId)))
        {
            Long uid = KgJdbc.asLong(a.get("appUserId"));
            String text = "「" + t.get("title") + "」已取消" + (reason == null || reason.trim().isEmpty() ? "" : "：" + reason.trim());
            if (roleAt(uid, KgJdbc.asLong(t.get("storeId"))) != null)
            {
                support.notifyStaff(uid, "system", "任务已取消", text, "task", taskId, "/pages/manage/tasks");
            }
            else
            {
                support.notifyUser(uid, "system", "任务已取消", text, "task", taskId, "/pages/manage/tasks");
            }
        }
        db.update("update kg_task_assignee set status = 'cancelled', update_time = now() where task_id = :id and status in ('pending','accepted')", p("id", taskId));
        return draft ? "草稿已删除" : "任务已取消";
    }

    /** 看板「生成改进计划」：每条建议生成一条员工任务草稿（下周一，待主理人补充时间后发布） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> planFromAdvice(Long storeId, List<String> advice)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        StaffSupport.Ctx c = support.ctx(storeId);
        if (!canPublish(me.getUserId(), c.storeId))
        {
            throw new ServiceException("仅主理人或管家可以生成改进计划", 403);
        }
        LocalDate mon = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        int n = 0;
        int skipped = 0;
        for (String a : advice == null ? new ArrayList<String>() : advice)
        {
            if (a == null || a.trim().isEmpty() || n >= 5)
            {
                continue;
            }
            String title = StaffSupport.cut(a.trim(), 64);
            // 这家店下周已经有同名的草稿 / 任务（之前点过「生成改进计划」）：不再重复生成，之前每点一次就多一批一样的草稿
            if (db.count("select count(*) from kg_task where store_id = :s and title = :t and task_date >= :f and task_date < :to and status in ('draft','published')",
                    p("s", c.storeId, "t", title, "f", mon.toString(), "to", mon.plusDays(7).toString())) > 0)
            {
                skipped++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("store_id", c.storeId);
            row.put("title", title);
            row.put("description", "来自数据看板的评价改进建议，请补充具体做法、时间与执行人后发布。");
            row.put("priority", "medium");
            row.put("task_date", mon.toString());
            row.put("start_time", "10:00");
            row.put("end_time", "12:00");
            row.put("est_hours", 2);
            row.put("assign_mode", "staff");
            row.put("quota", 1);
            row.put("status", "draft");
            row.put("creator_id", me.getUserId());
            row.put("creator_name", StaffSupport.cut(c.name(), 64));
            row.put("create_time", new Date());
            row.put("update_time", new Date());
            db.insert("kg_task", row);
            n++;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("created", n);
        m.put("weekStart", mon.toString());
        m.put("skipped", skipped);
        m.put("message", n > 0 ? "已生成 " + n + " 条任务草稿" + (skipped > 0 ? "（另有 " + skipped + " 条下周已经有了）" : "")
                : (skipped > 0 ? "这些建议下周已经生成过任务草稿，不再重复生成" : "没有需要生成的建议"));
        return m;
    }

    public long points(Long uid)
    {
        return db.count("select coalesce(sum(delta), 0) from kg_point_entry where app_user_id = :u", p("u", uid));
    }

    /** 工时：空 = 0（随后按范围校验报错）；不是数字给 422，不抛 NumberFormatException 变成 500（2026-09-26 回归发现） */
    private static double hoursOf(Object v)
    {
        String t = v == null ? "" : String.valueOf(v).trim();
        if (t.isEmpty())
        {
            return 0;
        }
        try
        {
            return Double.parseDouble(t);
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("工时请填数字（小时）", 422);
        }
    }
}
