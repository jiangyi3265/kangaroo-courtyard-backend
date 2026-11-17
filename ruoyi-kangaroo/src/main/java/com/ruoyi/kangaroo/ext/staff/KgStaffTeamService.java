package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.staff.StaffSupport.str;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 员工工作台"业务"页：身份与当前门店、门店切换、角色与成员、权限矩阵、员工操作日志与异常上报。
 *
 * 主理人可以在本店授予/撤销"员工""小院主理人"两种角色；主理人本身只能由平台后台授予，
 * 避免一个门店账号自己给自己扩权。授权变更后立刻刷新对方的登录态，和后台授权同一条路径。
 */
@Service
public class KgStaffTeamService
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private IKgStaffGrantService grants;

    @Autowired
    private AppTokenService tokens;

    @Autowired
    private IKgMessageService messages;

    /** 单测没注入时不查库 */
    @Autowired(required = false)
    private RolePhaseGuard phaseGuard;

    /** 工作台待办数（换工主理人待核实、商家服务人待接手）；单测没注入时不给这几个数 */
    @Autowired(required = false)
    private KgMerchantRequestService merchantRequests;

    static String opTypeLabel(String t)
    {
        if ("managed".equals(t))
        {
            return "代运营";
        }
        if ("franchise".equals(t))
        {
            return "加盟";
        }
        // 2026-11-03 主人：来源类型加「合作」（V70）
        if ("partner".equals(t))
        {
            return "合作";
        }
        return "直营";
    }

    static String opTypeTone(String t)
    {
        return "direct".equals(t) || t == null || t.isEmpty() ? "green" : "ochre";
    }

    public Map<String, Object> hub(StaffSupport.Ctx c)
    {
        Map<String, Object> user = db.one("select nickname, avatar from kg_app_user where user_id = :u", p("u", c.uid()));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("name", c.name());
        out.put("avatar", user == null ? null : user.get("avatar"));
        out.put("role", c.role);
        out.put("roleName", StaffRole.label(c.role));
        out.put("storeId", c.storeId);
        out.put("storeName", c.storeName);
        out.put("storeCount", c.me.getStaffGrants().size());
        // 和员工身份消息中心同一个口径（带 KgMessageMapper 的可见范围）：之前直接数 kg_message，线上任务专员账号看不到的消息也算进来，
        // 业务页红点挂着、消息中心却是「暂无消息」，全部已读也清不掉
        out.put("unread", messages.countUnread(c.uid(), "staff"));
        out.put("isManager", StaffRole.MANAGER.equals(c.role) || (RolePhase.isNew() && StaffRole.CITY_LEAD.equals(c.role)));
        // 这家店的小院主理人是否兼任商家服务人（kg_staff_grant.also_service）：工作台多出商家需求、线上任务概况
        out.put("alsoService", KgMerchantRequestService.alsoService(c));
        // 业务页「周报管理」入口的文字：换工主理人进去是换工招募情况周报
        out.put("weeklyEntryLabel", StaffRole.MANAGER.equals(c.role) ? "招募周报" : "周报管理");
        if (merchantRequests != null)
        {
            out.putAll(merchantRequests.hubCounts(c));
        }
        return out;
    }

    /** 门店切换页：当前门店 + 可管理门店 + 选中门店的信息 */
    public Map<String, Object> stores(StaffSupport.Ctx c, Long viewStoreId)
    {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        Map<String, Object> view = null;
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            if (g.getStoreId() == null) continue;
            Map<String, Object> s = db.one("select store_id, store_name, city, address, cover, contact_phone, op_type from kg_store where store_id = :s",
                    p("s", g.getStoreId()));
            if (s == null)
            {
                continue;
            }
            String op = str(s.get("opType"));
            s.put("type", opTypeLabel(op));
            s.put("tone", opTypeTone(op));
            s.put("role", g.getRoleCode());
            s.put("roleName", StaffRole.label(g.getRoleCode()));
            s.put("alsoService", Boolean.TRUE.equals(g.getAlsoService()));
            s.put("current", g.getStoreId().equals(c.storeId));
            list.add(s);
            if (g.getStoreId().equals(viewStoreId == null ? c.storeId : viewStoreId))
            {
                view = s;
            }
        }
        if (view == null && !list.isEmpty())
        {
            view = list.get(0);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("stores", list);
        out.put("current", view);
        if (view != null)
        {
            // 「店长」：切换前是老主理人；切换后是这家店的小院主理人，没有就显示未设置
            List<Map<String, Object>> managers = db.list("select u.nickname from kg_staff_grant g left join kg_app_user u on u.user_id = g.app_user_id "
                    + "where g.store_id = :s and g.role_code = :r and g.status = '0' and g.del_flag = '0'", p("s", view.get("storeId"), "r", RolePhase.isNew() ? StaffRole.KEEPER : StaffRole.MANAGER));
            StringBuilder names = new StringBuilder();
            for (Map<String, Object> m : managers)
            {
                names.append(names.length() == 0 ? "" : "、").append(str(m.get("nickname")));
            }
            List<String[]> info = new ArrayList<String[]>();
            info.add(new String[] { "运营类型", str(view.get("type")) });
            info.add(new String[] { "店长", names.length() == 0 ? "未设置" : names.toString() });
            info.add(new String[] { "门店地址", (str(view.get("city")) + " " + str(view.get("address"))).trim() });
            info.add(new String[] { "联系电话", str(view.get("contactPhone")).isEmpty() ? "未填写" : str(view.get("contactPhone")) });
            out.put("info", info);
        }
        return out;
    }

    /** 记一次门店切换（切换本身在小程序端保存当前门店，服务端每个请求仍按授权校验） */
    public void switched(StaffSupport.Ctx c)
    {
        support.op(c, "store_switch", "门店切换", "general", c.storeName, true, null);
    }

    public Map<String, Object> roles(StaffSupport.Ctx c, String role)
    {
        if (StaffRole.ONLINE_STAFF.equals(c.role) || StaffRole.MERCHANT.equals(c.role) || StaffRole.MERCHANT_SERVICE.equals(c.role) || (!RolePhase.isNew() && StaffRole.CITY_LEAD.equals(c.role)))
        {
            Map<String, Object> own = new LinkedHashMap<String, Object>();
            own.put("roles", java.util.Collections.emptyList());
            own.put("selected", c.role);
            own.put("selectedName", StaffRole.label(c.role));
            own.put("heads", StaffPerms.actionLabels());
            own.put("permissions", StaffPerms.matrix(c.role));
            own.put("canManage", false);
            own.put("grantOptions", java.util.Collections.emptyList());
            return own;
        }
        List<Map<String, Object>> members = db.list("select g.grant_id, g.app_user_id, g.role_code, g.granted_time, u.nickname, u.avatar "
                + "from kg_staff_grant g left join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.store_id = :s and g.status = '0' and g.del_flag = '0' order by g.grant_id", p("s", c.storeId));
        List<Map<String, Object>> temps = db.list("select a.app_id, a.real_name, a.onboard_time from kg_application a join kg_job j on j.job_id = a.job_id "
                + "where a.store_id = :s and a.status = 'onboarded' and j.job_type = 'volunteer' and a.del_flag = '0' order by a.onboard_time desc limit 50",
                p("s", c.storeId));
        String[][] defs = { { StaffRole.MERCHANT, "合作商家", "house", "#9A6B3F" }, { StaffRole.ONLINE_STAFF, "线上任务专员", "globe", "#2D7C59" }, { StaffRole.MERCHANT_SERVICE, "商家服务人", "globe", "#9A6B3F" }, { StaffRole.CITY_LEAD, "城市负责人", "crown", "#2F679A" }, { StaffRole.STAFF, "员工", "user-round", "#2F679A" }, { StaffRole.KEEPER, "小院主理人", "user-round", "#2D7C59" },
                { StaffRole.MANAGER, "换工主理人", "crown", "#B72F22" }, { "temp", "临时换工", "user-round", "#D28A20" } };
        List<Map<String, Object>> roles = new ArrayList<Map<String, Object>>();
        for (String[] d : defs)
        {
            // 切换后：员工这一组没有了，皇冠只留给城市负责人
            if (RolePhase.isNew() && StaffRole.STAFF.equals(d[0]))
            {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("code", d[0]);
            r.put("name", d[1]);
            r.put("icon", RolePhase.isNew() && StaffRole.MANAGER.equals(d[0]) ? "user-round" : d[2]);
            r.put("bg", d[3]);
            List<Map<String, Object>> ms = new ArrayList<Map<String, Object>>();
            if ("temp".equals(d[0]))
            {
                for (Map<String, Object> t : temps)
                {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", MaskUtils.name(str(t.get("realName"))));
                    m.put("since", t.get("onboardTime"));
                    ms.add(m);
                }
            }
            else
            {
                for (Map<String, Object> g : members)
                {
                    if (d[0].equals(str(g.get("roleCode"))))
                    {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("grantId", g.get("grantId"));
                        m.put("userId", g.get("appUserId"));
                        m.put("name", str(g.get("nickname")).isEmpty() ? "用户" + g.get("appUserId") : g.get("nickname"));
                        m.put("since", g.get("grantedTime"));
                        m.put("self", c.uid().equals(KgJdbc.asLong(g.get("appUserId"))));
                        ms.add(m);
                    }
                }
            }
            r.put("count", ms.size());
            r.put("members", ms);
            roles.add(r);
        }
        String sel = StaffRole.exists(role) && !(RolePhase.isNew() && StaffRole.STAFF.equals(role)) ? role : c.role;
        Map<String, Object> mine = null;
        for (Map<String, Object> g : members)
        {
            if (c.uid().equals(KgJdbc.asLong(g.get("appUserId"))))
            {
                mine = g;
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("roles", roles);
        out.put("selected", sel);
        out.put("selectedName", StaffRole.label(sel));
        out.put("heads", StaffPerms.actionLabels());
        out.put("permissions", StaffPerms.matrix(sel));
        Date since = mine == null ? null : StaffSupport.parse(mine.get("grantedTime"));
        out.put("validity", (since == null ? "" : StaffSupport.fmt(since, "yyyy-MM-dd") + " 起") + "长期有效（撤销前）");
        boolean canManage = !RolePhase.isNew() && StaffRole.MANAGER.equals(c.role) && !support.autoManager(c);
        out.put("canManage", canManage);
        // 「添加成员」弹窗的角色选项；空数组时小程序整个入口隐藏（切换后谁都不能在小程序加人，只能平台后台开）
        List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
        if (canManage)
        {
            for (String code : java.util.Arrays.asList(StaffRole.STAFF, StaffRole.KEEPER, StaffRole.ONLINE_STAFF))
            {
                Map<String, Object> o = new LinkedHashMap<String, Object>();
                o.put("code", code);
                o.put("name", StaffRole.label(code));
                options.add(o);
            }
        }
        out.put("grantOptions", options);
        return out;
    }

    /** 主理人在本店授予角色：target 为账号ID或手机号 */
    @Transactional(rollbackFor = Exception.class)
    public String grant(StaffSupport.Ctx c, String target, String role)
    {
        if (phaseGuard != null)
        {
            phaseGuard.beforeGrantWrite();
        }
        requireManager(c);
        if (!StaffRole.STAFF.equals(role) && !StaffRole.KEEPER.equals(role) && !StaffRole.ONLINE_STAFF.equals(role))
        {
            throw new ServiceException("换工主理人只能授予「员工」「小院主理人」或「线上任务专员」；换工主理人需由平台授予");
        }
        String t = target == null ? "" : target.trim();
        Map<String, Object> user;
        if (t.matches("1\\d{10}"))
        {
            // 只认已验证手机号的账号：未验证的号码谁都能用 bind-phone 占上，按号码授权会把门店角色给到占号的人
            user = db.one("select user_id, nickname, status from kg_app_user where phone = :t and phone_verified_time is not null and del_flag = '0' limit 1", p("t", t));
            if (user == null)
            {
                throw new ServiceException("没有找到用这个手机号完成验证的账号；请让对方先在小程序里验证手机号，或改填对方的账号ID");
            }
        }
        else if (t.matches("\\d{1,18}"))
        {
            user = db.one("select user_id, nickname, status from kg_app_user where user_id = :t and del_flag = '0'", p("t", Long.valueOf(t)));
        }
        else
        {
            throw new ServiceException("请填写对方的账号ID或手机号");
        }
        if (user == null)
        {
            throw new ServiceException("没有找到这个账号，请对方先登录一次小程序");
        }
        Long uid = KgJdbc.asLong(user.get("userId"));
        if (uid.equals(c.uid()))
        {
            throw new ServiceException("不能给自己授权");
        }
        if (!"0".equals(str(user.get("status"))))
        {
            throw new ServiceException("该账号已停用");
        }
        Map<String, Object> exists = db.one("select grant_id, status, del_flag from kg_staff_grant where app_user_id = :u and store_id = :s limit 1",
                p("u", uid, "s", c.storeId));
        String by = "staff:" + c.uid();
        Long grantId;
        if (exists != null)
        {
            if ("0".equals(str(exists.get("status"))) && !"2".equals(str(exists.get("delFlag"))))
            {
                throw new ServiceException("该账号在本店已有授权", 409);
            }
            // 兼任商家服务人只有平台能开：换工主理人复活旧授权时清掉，撤销前开过的不能跟着回来
            db.update("update kg_staff_grant set status = '0', del_flag = '0', role_code = :r, also_service = '0', granted_by = :b, granted_time = now(), "
                    + "revoked_by = null, revoked_time = null, update_by = :b, update_time = now() where grant_id = :g",
                    p("r", role, "b", by, "g", exists.get("grantId")));
            grantId = KgJdbc.asLong(exists.get("grantId"));
        }
        else
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("app_user_id", uid);
            row.put("store_id", c.storeId);
            row.put("role_code", role);
            row.put("status", "0");
            row.put("granted_by", by);
            row.put("granted_time", new Date());
            row.put("del_flag", "0");
            row.put("create_by", by);
            row.put("create_time", new Date());
            try
            {
                grantId = db.insert("kg_staff_grant", row);
            }
            catch (org.springframework.dao.DuplicateKeyException e)
            {
                // 同时点了几次「授予」：先到的那次已经建好授权，其余如实告知而不是 500
                throw new ServiceException("该成员刚刚已被授予角色，请刷新查看", 409);
            }
        }
        tokens.refreshGrants(uid, grants.loadActiveGrantViews(uid));
        String name = str(user.get("nickname")).isEmpty() ? "用户" + uid : str(user.get("nickname"));
        // 之前只发到员工身份，新员工这时还在用户身份下，消息中心和红点都看不到；改走和后台授权同一条通知路径。
        // biz_id 记授权ID（之前误记成对方的账号ID），与后台授予 / 撤销、主理人撤销一致
        grants.notifyGrantChange(uid, c.storeId, "你已获得门店员工身份", c.storeName + " 的主理人 " + c.name() + " 授予你「" + StaffRole.label(role)
                + "」角色，切换到员工身份即可使用工作台。", grantId);
        support.op(c, "role_grant", "角色授予", "grant", name + " → " + StaffRole.label(role), true, null);
        return "已授予 " + name + "「" + StaffRole.label(role) + "」";
    }

    @Transactional(rollbackFor = Exception.class)
    public String revoke(StaffSupport.Ctx c, Long grantId)
    {
        requireManager(c);
        Map<String, Object> g = db.must("select g.*, u.nickname from kg_staff_grant g left join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.grant_id = :g and g.del_flag = '0'", p("g", grantId), "授权不存在");
        if (!c.storeId.equals(KgJdbc.asLong(g.get("storeId"))))
        {
            throw new ServiceException("只能撤销本店的授权", HttpStatus.FORBIDDEN);
        }
        if (StaffRole.MANAGER.equals(str(g.get("roleCode"))))
        {
            throw new ServiceException("主理人授权需由平台撤销");
        }
        // 合作商家、商家服务人、城市负责人和主理人一样只有平台能开，也只有平台能撤（之前只挡了主理人，换工主理人能撤掉商家自己的授权）
        if (StaffRole.platformOnly(str(g.get("roleCode"))))
        {
            throw new ServiceException("「" + StaffRole.label(str(g.get("roleCode"))) + "」的授权需由平台撤销", HttpStatus.FORBIDDEN);
        }
        Long uid = KgJdbc.asLong(g.get("appUserId"));
        if (db.update("update kg_staff_grant set status = '1', revoked_by = :b, revoked_time = now(), update_by = :b, update_time = now() "
                + "where grant_id = :g and status = '0'", p("g", grantId, "b", "staff:" + c.uid())) == 0)
        {
            throw new ServiceException("授权已撤销，请勿重复操作");
        }
        // 被撤的可能是兼任的小院主理人：系统在商家店补的商家服务人授权一起收回
        if (merchantRequests != null)
        {
            merchantRequests.releaseServiceGrants(uid);
        }
        tokens.refreshGrants(uid, grants.loadActiveGrantViews(uid));
        String name = str(g.get("nickname")).isEmpty() ? "用户" + uid : str(g.get("nickname"));
        grants.notifyGrantChange(uid, c.storeId, "门店员工身份已撤销", "你在「" + c.storeName + "」的「" + StaffRole.label(str(g.get("roleCode")))
                + "」身份已被本店主理人撤销，这家门店的工作台已不能使用。", KgJdbc.asLong(g.get("grantId")));
        support.op(c, "role_revoke", "撤销授权", "grant", name + "（" + StaffRole.label(str(g.get("roleCode"))) + "）", true, null);
        return "已撤销 " + name + " 的授权";
    }

    /** 控制器在进入事务之前调用：拒绝时写的失败日志不能随 grant / revoke 的事务一起回滚（2026-09-26 回归发现越权授予没留痕） */
    public void requireManager(StaffSupport.Ctx c)
    {
        // 切换后小程序里谁都不能加人、撤人，只有平台后台能开（管人下一轮才做）；老版本的「添加员工」、页面状态过期的请求都到这里
        if (RolePhase.isNew())
        {
            support.op(c, "role_grant", "角色授予", "grant", null, false, "成员由平台管理");
            throw new ServiceException("成员和授权现在由平台在后台开通、撤销，请联系平台处理", HttpStatus.FORBIDDEN);
        }
        if (!StaffRole.MANAGER.equals(c.role))
        {
            support.op(c, "role_grant", "角色授予", "grant", null, false, "超出权限范围");
            throw new ServiceException("只有本店主理人可以管理成员", HttpStatus.FORBIDDEN);
        }
        // 商家需求自动补的授权：只管岗位和报名，不能往商家店里加人、撤人（加的人商家看不到也撤不掉）
        if (support.autoManager(c))
        {
            support.op(c, "role_grant", "角色授予", "grant", null, false, "派单授权不能管理成员");
            throw new ServiceException("这家店是派单给你的，成员和授权要由平台管理", HttpStatus.FORBIDDEN);
        }
    }

    // ---------- 操作日志 ----------

    public Map<String, Object> opLogs(StaffSupport.Ctx c, String category, int days, String result)
    {
        int d = days <= 0 || days > 90 ? 7 : days;
        StringBuilder where = new StringBuilder(" where store_id = :s and create_time >= date_sub(now(), interval :d day)");
        Map<String, Object> ps = p("s", c.storeId, "d", d);
        // 只有主理人看全店；管家与普通员工只看自己的操作（设计 m1：监控员工操作的日志，全店视图只给主理人）。
        // 切换后 manager 是原来的员工，不再看全店（里面有资金操作和别人的记录）：谁都只看自己的，全店日志在平台后台
        boolean all = !RolePhase.isNew() && StaffRole.MANAGER.equals(c.role);
        if (!all)
        {
            where.append(" and app_user_id = :u");
            ps.put("u", c.uid());
        }
        Map<String, Object> sum = db.one("select count(*) total, sum(ok = '0') failed, sum(category = 'export') exports, sum(category = 'fund') funds "
                + "from kg_staff_op_log" + where, ps);
        if (category != null && category.matches("general|export|fund|grant"))
        {
            where.append(" and category = :c");
            ps.put("c", category);
        }
        if ("failed".equals(result))
        {
            where.append(" and ok = '0'");
        }
        List<Map<String, Object>> rows = db.list("select log_id, operator_name, role_code, action_label, detail, device, ok, fail_reason, category, create_time "
                + "from kg_staff_op_log" + where + " order by log_id desc limit 100", ps);
        for (Map<String, Object> r : rows)
        {
            boolean ok = "1".equals(str(r.get("ok")));
            String cat = str(r.get("category"));
            r.put("action", r.get("actionLabel"));
            r.put("person", str(r.get("operatorName")) + "（" + StaffRole.label(str(r.get("roleCode"))) + "）");
            r.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "MM-dd HH:mm"));
            r.put("ok", ok);
            r.put("reason", r.get("failReason"));
            r.put("color", !ok ? "#C02F1D" : ("export".equals(cat) ? "#2F679A" : ("fund".equals(cat) ? "#C02F1D" : ("grant".equals(cat) ? "#D9860B" : "#2D7C59"))));
            r.put("icon", !ok ? "triangle-alert" : ("export".equals(cat) ? "file-text" : ("fund".equals(cat) ? "wallet-cards" : ("grant".equals(cat) ? "badge-check" : "qr-code"))));
        }
        List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
        summaries.add(summary("全部操作", sum.get("total"), false));
        summaries.add(summary("异常操作", sum.get("failed"), true));
        summaries.add(summary("数据导出", sum.get("exports"), false));
        summaries.add(summary("资金操作", sum.get("funds"), false));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("summaries", summaries);
        out.put("rows", rows);
        out.put("days", d);
        out.put("scope", all ? "本店全部员工" : "仅本人");
        return out;
    }

    private static Map<String, Object> summary(String label, Object v, boolean red)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", String.valueOf(StaffSupport.toInt(v, 0)));
        m.put("red", red);
        return m;
    }

    /** 上报异常操作：落到后台"举报处理"，对象类型 staffop */
    public String report(StaffSupport.Ctx c, Long logId, String reason)
    {
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 2 || why.length() > 200)
        {
            throw new ServiceException("请描述异常情况（2–200 字）");
        }
        if (logId == null)
        {
            throw new ServiceException("请选择要上报的那条操作");
        }
        Map<String, Object> l = db.must("select store_id from kg_staff_op_log where log_id = :id", p("id", logId), "日志不存在");
        if (!c.me.hasStoreAccess(KgJdbc.asLong(l.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        if (db.count("select count(*) from kg_report where target_type = 'staffop' and target_id = :t and reporter_id = :u",
                p("t", logId, "u", c.uid())) > 0)
        {
            throw new ServiceException("这条操作你已经上报过了");
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("target_type", "staffop");
        row.put("target_id", logId);
        row.put("reporter_id", c.uid());
        row.put("reason", "员工异常操作");
        row.put("detail", StaffSupport.cut("门店：" + c.storeName + "；" + why, 500));
        row.put("status", "pending");
        row.put("create_by", "staff:" + c.uid());
        row.put("create_time", new Date());
        db.insert("kg_report", row);
        support.op(c, "op_report", "上报异常", "general", why, true, null);
        return "已上报平台，处理结果会通过消息通知你";
    }
}
