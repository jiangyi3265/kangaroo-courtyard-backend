package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.staff.StaffSupport.str;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.util.MaskUtils;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDictTypeService;

/**
 * 角色切换（后台「角色切换」页）：看名单 → 切换 → 退回 → 打开派单。
 *
 * 编码不动：manager 变 city_lead（老主理人 → 城市负责人），staff 变 manager（老员工 → 换工主理人），staff 退役。
 *
 * 切换在一个事务里：锁状态行和 kg_staff_grant → 核对指纹 → 硬拦截 → 写备份 → 一条 CASE 同时改（含已撤销、已删除的行，granted_time、also_service 不动）
 * → 字典写实文字和排序 → 逐行核对 → 改阶段。提交前内存阶段先置 new，失败恢复，所以时间差里只会收紧权限（老员工此时矩阵里没有 staff 行，老主理人此时没有资金权）。
 *
 * 退回按值判断：当前角色还等于切换写的角色就还原（只是后台保存过一次、撤销过，角色没变，一样还原；update_by、update_time 只有还是切换写的才还原）；
 * 切换后被改成别的角色的行不动，列给主人看；切换后新开的、切换前已撤销切换后又重新授予的，按映射倒回去，也列出来。退回提交之后内存阶段才回 legacy，同样只会收紧。
 * 打开派单之后不提供一键退回：已有自动授权和进行中的需求。
 *
 * 不用 @Transactional：要手动控制提交前后的内存阶段，而且提交之后才刷字典缓存、发站内信（发信失败不回滚）。
 */
@Service
public class KgRoleSwitchService
{
    private static final Logger log = LoggerFactory.getLogger(KgRoleSwitchService.class);

    public static final String CONFIRM_SWITCH = "切换";
    public static final String CONFIRM_ROLLBACK = "退回";
    public static final String CONFIRM_ROUTING = "打开派单";
    /** 这个包的小程序版本：切换前至少要见过一个正式版或体验版用户（主人只有体验版，11-01 决定体验版也算），切换页也按它判断「已用新版」 */
    public static final String MIN_CLIENT_VERSION = "2026.10.30.2";
    /** 切换改过的授权行 update_by 记这个；退回时靠它判断这两列还是不是切换写的（是不是要还原角色只看值） */
    static final String BY_SWITCH = "system:role_switch";
    static final String BY_ROLLBACK = "system:role_rollback";

    private static final String GRANT_COLUMNS = "grant_id, app_user_id, store_id, role_code, status, also_service, granted_by, granted_time, revoked_by, revoked_time, "
            + "del_flag, create_by, create_time, update_by, update_time, remark";
    private static final String GRANTS_SQL = "select " + GRANT_COLUMNS + " from kg_staff_grant order by grant_id";
    /** 逐行核对时这些列必须和切换前一样 */
    private static final List<String> UNTOUCHED = Arrays.asList("appUserId", "storeId", "status", "alsoService", "grantedBy", "grantedTime", "revokedBy", "revokedTime",
            "delFlag", "createBy", "createTime", "remark");

    /** 字典：值、文字、排序、状态。主人定的 6 个可选角色在前；员工停用。线上字典的原文字没实测，一律按这张表写实，原值进备份 */
    private static final Object[][] DICT = { { "city_lead", "城市负责人", 1, "0" }, { "keeper", "小院主理人", 2, "0" }, { "manager", "换工主理人", 3, "0" },
            { "online_staff", "线上任务专员", 4, "0" }, { "merchant_service", "商家服务人", 5, "0" }, { "merchant", "合作商家", 6, "0" }, { "staff", "员工", 7, "1" } };
    private static final String DICT_TYPE = "kg_staff_role";
    /** 开着说明派单已进行：这些状态的需求还在换工主理人、商家服务人手上 */
    private static final List<String> ACTIVE_REQUESTS = Arrays.asList(KgMerchantRequestService.DISPATCHED, KgMerchantRequestService.PENDING_VERIFY, KgMerchantRequestService.FORWARDED,
            KgMerchantRequestService.ACCEPTED);

    @Autowired
    private KgJdbc db;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private IKgStaffGrantService grants;

    @Autowired
    private KgCityService cities;

    @Autowired
    private ISysDictTypeService dictTypes;

    @Autowired
    private ISysConfigService configs;

    // ------------------------------------------------------------------ 映射

    static String mapped(String role)
    {
        if (StaffRole.MANAGER.equals(role))
        {
            return StaffRole.CITY_LEAD;
        }
        return StaffRole.STAFF.equals(role) ? StaffRole.MANAGER : role;
    }

    /** 切换后新开的授权退回时怎么倒：换工主理人 → 老员工，城市负责人 → 老主理人 */
    static String unmapped(String role)
    {
        if (StaffRole.MANAGER.equals(role))
        {
            return StaffRole.STAFF;
        }
        return StaffRole.CITY_LEAD.equals(role) ? StaffRole.MANAGER : role;
    }

    /** 名单指纹：谁在哪家店、什么角色、什么状态。比 update_time 没有意义，比这些才是主人看到的东西 */
    static String fingerprint(List<Map<String, Object>> rows)
    {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> r : rows)
        {
            b.append(str(r.get("grantId"))).append('|').append(str(r.get("appUserId"))).append('|').append(str(r.get("storeId"))).append('|').append(str(r.get("roleCode"))).append('|')
                    .append(str(r.get("status"))).append('|').append(str(r.get("delFlag"))).append('|').append(str(r.get("alsoService"))).append('|').append(str(r.get("grantedBy"))).append('\n');
        }
        try
        {
            StringBuilder hex = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)))
            {
                hex.append(String.format("%02x", x));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /** 版本比较：2026.10.30.2 这种按段比数字；有段不是数字就算旧（只影响显示和门槛，不参与授权） */
    static boolean atLeast(String version, String min)
    {
        if (version == null || !version.matches("\\d+(\\.\\d+)*"))
        {
            return false;
        }
        String[] a = version.split("\\.");
        String[] b = min.split("\\.");
        try
        {
            for (int i = 0; i < Math.max(a.length, b.length); i++)
            {
                long x = i < a.length ? Long.parseLong(a[i]) : 0;
                long y = i < b.length ? Long.parseLong(b[i]) : 0;
                if (x != y)
                {
                    return x > y;
                }
            }
        }
        catch (NumberFormatException e)
        {
            // 超出 long 的数字只可能是伪造的请求头：算旧。这个判断在切换页和切换里都走，抛错会让整页打不开
            return false;
        }
        return true;
    }

    private static boolean active(Map<String, Object> g)
    {
        return "0".equals(str(g.get("status"))) && !"2".equals(str(g.get("delFlag")));
    }

    private static String pair(Object uid, Object store)
    {
        return str(uid) + "|" + str(store);
    }

    private TransactionTemplate tx()
    {
        TransactionTemplate t = new TransactionTemplate(transactions);
        t.setTimeout(120);
        return t;
    }

    // ------------------------------------------------------------------ 公共判断（预览和事务里同一份）

    private String routingValue()
    {
        Map<String, Object> c = db.one("select config_value from sys_config where config_key = :k limit 1", p("k", KgMerchantRequestService.ROUTING_KEY));
        return c == null ? "" : str(c.get("configValue")).trim();
    }

    /** 切换的硬拦截：不是 off、有派单补的换工主理人授权、还没见过任何正式版或体验版的新版小程序。grantRows 是已经读到（事务里是已经锁住）的全部授权 */
    private List<String> switchBlockers(List<Map<String, Object>> grantRows)
    {
        List<String> out = new ArrayList<String>();
        String routing = routingValue();
        if (!"off".equalsIgnoreCase(routing))
        {
            out.add("派单开关现在不是 off（当前：" + (routing.isEmpty() ? "没有这一项" : routing) + "）。切换前必须是 off，到「系统管理 → 参数设置」改回去");
        }
        long auto = 0;
        for (Map<String, Object> g : grantRows)
        {
            if (StaffRole.MANAGER.equals(str(g.get("roleCode"))) && KgMerchantRequestService.GRANTED_BY.equals(str(g.get("grantedBy"))))
            {
                auto++;
            }
        }
        if (auto > 0)
        {
            out.add("有 " + auto + " 行派单自动补的换工主理人授权：切换会把它们当老主理人变成城市负责人。先联系 Claude 处理");
        }
        boolean seen = false;
        for (Map<String, Object> c : db.list("select version from kg_staff_client_seen where channel in ('release', 'trial')", p()))
        {
            seen = seen || atLeast(str(c.get("version")), MIN_CLIENT_VERSION);
        }
        if (!seen)
        {
            out.add("还没有哪位有门店授权的员工打开过正式版或体验版 " + MIN_CLIENT_VERSION + "（或更新的版本）：普通用户和开发版不算。请一位有门店授权的员工打开最新的体验版，再点「重新读取」");
        }
        return out;
    }

    /** 退回的拦截；返回 null 表示可以退回 */
    private String rollbackBlocked(Map<String, Object> state, List<Map<String, Object>> grantRows)
    {
        if (!RolePhase.NEW.equals(str(state.get("phase"))))
        {
            return "现在是切换前的阶段，不需要退回";
        }
        String routed = "已打开过派单，不提供一键退回：已有自动授权和进行中的需求。请先把派单开关改回 off 并联系 Claude 处理";
        if (state.get("routingOpenedTime") != null || !"off".equalsIgnoreCase(routingValue()))
        {
            return routed;
        }
        for (Map<String, Object> g : grantRows)
        {
            if (KgMerchantRequestService.GRANTED_BY.equals(str(g.get("grantedBy"))) && StaffRole.MANAGER.equals(str(g.get("roleCode"))))
            {
                return routed;
            }
        }
        if (db.count("select count(*) from kg_merchant_request where del_flag = '0' and status in (:s)", p("s", ACTIVE_REQUESTS)) > 0)
        {
            return routed;
        }
        return null;
    }

    // ------------------------------------------------------------------ 切换页

    public Map<String, Object> preview()
    {
        Map<String, Object> state = db.must("select * from kg_role_switch where id = 1", p(), "没有角色切换状态行，V64 迁移没有执行");
        List<Map<String, Object>> rows = db.list(GRANTS_SQL, p());
        boolean switched = RolePhase.NEW.equals(str(state.get("phase")));
        // 切换之后这一页还要看得懂：名单按备份里的原角色分组
        Map<Long, String> oldRole = new TreeMap<Long, String>();
        for (Map<String, Object> b : db.list("select grant_id, old_role from kg_role_switch_backup where kind = 'grant'", p()))
        {
            oldRole.put(KgJdbc.asLong(b.get("grantId")), str(b.get("oldRole")));
        }
        Map<Long, Map<String, Object>> users = indexBy("select user_id, nickname, phone, status, del_flag from kg_app_user", "userId");
        Map<Long, Map<String, Object>> stores = indexBy("select store_id, store_name, city, status, del_flag from kg_store", "storeId");
        Map<Long, Map<String, Map<String, Object>>> seen = clientRows();

        List<Map<String, Object>> toLead = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> toManager = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> leadFinance = new ArrayList<Map<String, Object>>();
        // 这几个集合按「切换前的角色」算，切换前后看到的警告一致
        Set<Long> withOldManager = new LinkedHashSet<Long>();
        Set<Long> withOldStaff = new LinkedHashSet<Long>();
        Set<Long> withLead = new LinkedHashSet<Long>();
        Set<Long> withKeeper = new LinkedHashSet<Long>();
        // 退款审批真的有人接的门店：账号和门店都正常（和 StaffSupport.cityLeadsOfStore 同一口径）。这个按当前的授权算：切换后补开一位城市负责人，提醒就该消失
        Set<Long> withWorkingLead = new LinkedHashSet<Long>();
        // 切换后还留着的 staff 行、认不出的角色：这些账号什么都做不了，要主人改成别的角色
        Set<Long> leftoverStores = new LinkedHashSet<Long>();
        Set<String> oldManagerAt = new LinkedHashSet<String>();
        Set<Long> oldManagerUsers = new LinkedHashSet<Long>();
        Set<Long> oldStaffUsers = new LinkedHashSet<Long>();
        Map<String, Integer> newManagersByCity = new TreeMap<String, Integer>();
        long inactiveRows = 0;
        long unknown = 0;
        long alsoService = 0;
        long merchantServiceRows = 0;
        for (Map<String, Object> g : rows)
        {
            Long grantId = KgJdbc.asLong(g.get("grantId"));
            String role = switched ? oldRole.getOrDefault(grantId, "") : str(g.get("roleCode"));
            String current = str(g.get("roleCode"));
            if (!StaffRole.exists(current))
            {
                unknown++;
            }
            if (!active(g))
            {
                if (StaffRole.MANAGER.equals(role) || StaffRole.STAFF.equals(role))
                {
                    inactiveRows++;
                }
                continue;
            }
            if ("1".equals(str(g.get("alsoService"))))
            {
                alsoService++;
            }
            Long uid = KgJdbc.asLong(g.get("appUserId"));
            Long store = KgJdbc.asLong(g.get("storeId"));
            if (normal(users.get(uid)) && normal(stores.get(store)) && (StaffRole.CITY_LEAD.equals(current) || (!switched && StaffRole.MANAGER.equals(current))))
            {
                withWorkingLead.add(store);
            }
            if (switched && !RolePhase.usable(current))
            {
                leftoverStores.add(store);
            }
            Map<String, Object> person = person(g, users.get(uid), stores.get(store), seen.get(uid));
            if (StaffRole.MANAGER.equals(role))
            {
                toLead.add(person);
                withOldManager.add(store);
                oldManagerAt.add(pair(uid, store));
                oldManagerUsers.add(uid);
            }
            else if (StaffRole.STAFF.equals(role))
            {
                toManager.add(person);
                withOldStaff.add(store);
                oldStaffUsers.add(uid);
                newManagersByCity.merge(cityName(stores.get(store)), 1, Integer::sum);
            }
            else if (StaffRole.CITY_LEAD.equals(role))
            {
                leadFinance.add(person);
                withLead.add(store);
            }
            else if (StaffRole.KEEPER.equals(role))
            {
                withKeeper.add(store);
            }
            else if (StaffRole.MERCHANT_SERVICE.equals(role))
            {
                merchantServiceRows++;
            }
        }

        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        groups.add(group("toCityLead", "变城市负责人（原换工主理人）", toLead, Arrays.asList("本城全部门店，小院主理人、换工主理人、线上任务专员、商家服务人能做的都能做（平时不做日常，下级不在时顶上）", "本城门店数、今日报名、在招岗位、在住人数", "活动退款审批、取消收费活动"),
                Arrays.asList("小程序里加人、撤人（只有平台能做）", "派单不发给他，也不算进换工主理人人数", "门店有换工主理人时，报名、面试的通知不发给他；门店没有换工主理人时才发")));
        groups.add(group("toManager", "变换工主理人（原员工）", toManager, Arrays.asList("全城商家需求的派单和核实（打开派单之后）", "招募账和招募周报", "保险登记保单号", "换工主理人专区：钱包、荣誉与关怀",
                "数据看板不是新增的：老员工今天就能看本店营业额，切换后照旧；派单补的商家店不算在内"),
                Arrays.asList("活动退款、取消收费活动", "旅居账和经营周报")));
        groups.add(group("cityLeadGainsFinance", "已有城市负责人（多出全部权限）", leadFinance, Arrays.asList("本城全部门店，小院主理人、换工主理人、线上任务专员、商家服务人能做的都能做（平时不做日常，下级不在时顶上）", "活动退款审批、取消收费活动", "本城门店数、今日报名、在招岗位、在住人数"), new ArrayList<String>()));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("phase", str(state.get("phase")));
        out.put("switchedBy", state.get("switchedBy"));
        out.put("switchedTime", state.get("switchedTime"));
        out.put("rolledBackBy", state.get("rolledBackBy"));
        out.put("rolledBackTime", state.get("rolledBackTime"));
        out.put("rollbackNote", state.get("rollbackNote"));
        out.put("routing", routingValue());
        out.put("routingOpenedTime", state.get("routingOpenedTime"));
        out.put("minClientVersion", MIN_CLIENT_VERSION);
        out.put("fingerprint", fingerprint(rows));
        out.put("groups", groups);
        out.put("inactiveRows", inactiveRows);
        out.put("counts", KgJdbc.p("merchantService", merchantServiceRows, "alsoService", alsoService, "unknownRole", unknown));
        out.put("clientSummary", clientSummary(toLead, toManager, leadFinance));
        out.put("routingAudience", newManagersByCity);
        out.put("warnings", warnings(rows, stores, withOldManager, withOldStaff, withLead, withKeeper, withWorkingLead, leftoverStores, oldManagerAt, oldManagerUsers, oldStaffUsers));
        List<String> blockers = switched ? new ArrayList<String>() : switchBlockers(rows);
        out.put("blockers", blockers);
        String blocked = rollbackBlocked(state, rows);
        out.put("canSwitch", !switched && blockers.isEmpty());
        out.put("canRollback", blocked == null);
        out.put("rollbackBlockedReason", blocked);
        // 退回之前就列给主人看：哪些行不会被还原、哪些切换后新开的会倒回去。和真退回同一份计算
        out.put("rollbackPlan", blocked == null ? rollbackPlan(rows, backupRows(), state.get("switchedTime")).view() : null);
        out.put("canOpenRouting", switched && state.get("routingOpenedTime") == null && "off".equalsIgnoreCase(routingValue()));
        return out;
    }

    private Map<Long, Map<String, Object>> indexBy(String sql, String key)
    {
        Map<Long, Map<String, Object>> m = new TreeMap<Long, Map<String, Object>>();
        for (Map<String, Object> r : db.list(sql, p()))
        {
            m.put(KgJdbc.asLong(r.get(key)), r);
        }
        return m;
    }

    /** 账号 → 渠道 → {version, lastSeenTime} */
    private Map<Long, Map<String, Map<String, Object>>> clientRows()
    {
        Map<Long, Map<String, Map<String, Object>>> m = new TreeMap<Long, Map<String, Map<String, Object>>>();
        for (Map<String, Object> r : db.list("select app_user_id, channel, version, last_seen_time from kg_staff_client_seen", p()))
        {
            m.computeIfAbsent(KgJdbc.asLong(r.get("appUserId")), k -> new LinkedHashMap<String, Map<String, Object>>()).put(str(r.get("channel")), r);
        }
        return m;
    }

    /** 已用新版 new / 还在老版 old / 没打开过 never。老版本不带请求头，记成 channel = none */
    private static Map<String, Object> client(Map<String, Map<String, Object>> seen)
    {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        if (seen == null || seen.isEmpty())
        {
            c.put("status", "never");
            c.put("statusText", "没打开过");
            return c;
        }
        Map<String, Object> release = seen.get("release");
        Map<String, Object> trial = seen.get("trial");
        boolean releaseFresh = release != null && atLeast(str(release.get("version")), MIN_CLIENT_VERSION);
        boolean trialFresh = trial != null && atLeast(str(trial.get("version")), MIN_CLIENT_VERSION);
        boolean fresh = releaseFresh || trialFresh;
        Map<String, Object> shown = releaseFresh ? release : trialFresh ? trial : release != null ? release : seen.values().iterator().next();
        c.put("status", fresh ? "new" : "old");
        c.put("statusText", fresh ? "已用新版" : "还在老版");
        c.put("channel", str(shown.get("channel")));
        c.put("version", str(shown.get("version")));
        c.put("lastSeenTime", shown.get("lastSeenTime"));
        return c;
    }

    private static String cityName(Map<String, Object> store)
    {
        return store == null ? "" : str(store.get("city"));
    }

    /** 账号或门店正常：没停用、没删除 */
    private static boolean normal(Map<String, Object> row)
    {
        return row != null && "0".equals(str(row.get("status"))) && "0".equals(str(row.get("delFlag")));
    }

    private static Map<String, Object> person(Map<String, Object> g, Map<String, Object> user, Map<String, Object> store, Map<String, Map<String, Object>> seen)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("grantId", g.get("grantId"));
        m.put("appUserId", g.get("appUserId"));
        m.put("nickname", user == null ? "" : str(user.get("nickname")));
        m.put("phone", user == null ? "" : MaskUtils.phone(str(user.get("phone"))));
        m.put("storeId", g.get("storeId"));
        m.put("storeName", store == null ? "" : str(store.get("storeName")));
        m.put("city", cityName(store));
        m.put("storeActive", store != null && "0".equals(str(store.get("status"))) && "0".equals(str(store.get("delFlag"))));
        m.put("grantedTime", g.get("grantedTime"));
        m.put("client", client(seen));
        return m;
    }

    private static Map<String, Object> group(String code, String title, List<Map<String, Object>> people, List<String> gains, List<String> loses)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("title", title);
        m.put("count", people.size());
        m.put("gains", gains);
        m.put("loses", loses);
        m.put("people", people);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> clientSummary(List<Map<String, Object>>... lists)
    {
        Map<Object, String> byUser = new LinkedHashMap<Object, String>();
        for (List<Map<String, Object>> l : lists)
        {
            for (Map<String, Object> m : l)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> c = (Map<String, Object>) m.get("client");
                byUser.put(m.get("appUserId"), str(c.get("status")));
            }
        }
        long fresh = 0;
        long old = 0;
        long never = 0;
        for (String s : byUser.values())
        {
            if ("new".equals(s))
            {
                fresh++;
            }
            else if ("old".equals(s))
            {
                old++;
            }
            else
            {
                never++;
            }
        }
        return KgJdbc.p("newCount", fresh, "oldCount", old, "neverCount", never, "people", byUser.size());
    }

    private Map<String, Object> warning(String code, String level, String title, Object count, List<Map<String, Object>> items)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("level", level);
        m.put("title", title);
        m.put("count", count);
        m.put("items", items);
        return m;
    }

    private static List<Map<String, Object>> storeItems(Collection<Long> ids, Map<Long, Map<String, Object>> stores)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Long id : ids)
        {
            // 活动的门店可以为空（kg_activity.store_id）：这一条不能让整页打不开
            Map<String, Object> s = id == null ? null : stores.get(id);
            out.add(KgJdbc.p("storeId", id == null ? "none" : id, "storeName", id == null ? "没有门店的活动" : (s == null ? "" : str(s.get("storeName"))), "city", cityName(s)));
        }
        return out;
    }

    /** 切换页的提醒：只提醒，不拦（主人定的）。门店的归属按切换前的角色算 */
    private List<Map<String, Object>> warnings(List<Map<String, Object>> rows, Map<Long, Map<String, Object>> stores, Set<Long> withOldManager, Set<Long> withOldStaff,
            Set<Long> withLead, Set<Long> withKeeper, Set<Long> withWorkingLead, Set<Long> leftoverStores, Set<String> oldManagerAt, Set<Long> oldManagerUsers, Set<Long> oldStaffUsers)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();

        Set<Long> onlyManager = new LinkedHashSet<Long>(withOldManager);
        onlyManager.removeAll(withOldStaff);
        out.add(warning("onlyOldManager", "red", "只有老主理人、没有老员工的门店：切换后这家店没有换工主理人，报名、面试、活动、保险在小程序里没人接（可以先到后台给这家店加一个「员工」）", onlyManager.size(),
                storeItems(onlyManager, stores)));

        // 只看正常营业的门店；授权还在但账号已停用的不算有人：退款消息发给停用账号等于没人知道
        Set<Long> noLead = new LinkedHashSet<Long>();
        for (Set<Long> s : Arrays.asList(withOldStaff, withOldManager, withLead))
        {
            for (Long id : s)
            {
                if (normal(stores.get(id)) && !withWorkingLead.contains(id))
                {
                    noLead.add(id);
                }
            }
        }
        out.add(warning("noCityLead", "red", "没有可用城市负责人的门店（没有老主理人、城市负责人，或他们的账号已停用）：切换后活动退款只能走平台后台", noLead.size(), storeItems(noLead, stores)));

        if (!leftoverStores.isEmpty())
        {
            out.add(warning("leftoverStaff", "red", "切换后还留着「员工」或认不出角色的授权：这些账号在这家店什么都做不了，请到「员工授权」改成别的角色", leftoverStores.size(), storeItems(leftoverStores, stores)));
        }

        Set<Long> stayLedger = new LinkedHashSet<Long>();
        for (Map<String, Object> r : db.list("select distinct store_id from kg_daily_entry where ledger = 'stay' and status = '0' and biz_date >= date_sub(curdate(), interval 90 day) "
                + "union select distinct store_id from kg_weekly_report where report_type = 'ops' and week_start >= date_sub(curdate(), interval 90 day)", p()))
        {
            stayLedger.add(KgJdbc.asLong(r.get("storeId")));
        }
        stayLedger.removeAll(withKeeper);
        out.add(warning("stayLedgerNoKeeper", "red", "近 90 天有旅居账或经营周报、但没有小院主理人的门店：切换后这家店的旅居账没人写（旧明细不迁移）", stayLedger.size(), storeItems(stayLedger, stores)));

        Set<Long> offDict = new LinkedHashSet<Long>();
        Set<Long> involved = new LinkedHashSet<Long>(withOldManager);
        involved.addAll(withOldStaff);
        involved.addAll(withLead);
        for (Long id : involved)
        {
            Map<String, Object> s = stores.get(id);
            if (s == null || cities.dictionaryCity(str(s.get("city"))) == null)
            {
                offDict.add(id);
            }
        }
        out.add(warning("cityOutOfDictionary", "red", "城市不在字典里（或是全国、不限）的门店：城市负责人看不到本城数据，派单也找不到人，请先到后台改门店城市", offDict.size(), storeItems(offDict, stores)));

        List<Map<String, Object>> refundItems = new ArrayList<Map<String, Object>>();
        long refundTotal = 0;
        for (Map<String, Object> r : db.list("select a.store_id, count(*) n from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id "
                + "where g.status = 'refund_pending' and a.del_flag = '0' group by a.store_id", p()))
        {
            Long id = KgJdbc.asLong(r.get("storeId"));
            long n = StaffSupport.toInt(r.get("n"), 0);
            refundTotal += n;
            Map<String, Object> item = storeItems(Arrays.asList(id), stores).get(0);
            item.put("count", n);
            refundItems.add(item);
        }
        out.add(warning("pendingRefunds", "info", "待审批的活动退款：切换后由这家店的城市负责人审批，老版本上审不了，选退款少的时段切", refundTotal, refundItems));

        long tasks = 0;
        for (Map<String, Object> t : db.list("select store_id, assignee_id from kg_staff_task where status in ('pending','rejected','submitted')", p()))
        {
            tasks += oldManagerAt.contains(pair(t.get("assigneeId"), t.get("storeId"))) ? 1 : 0;
        }
        out.add(warning("unfinishedStaffTasks", "info", "老主理人没做完的后台任务：不自动迁移，切换后请在后台重新派", tasks, new ArrayList<Map<String, Object>>()));

        long storeTasks = 0;
        for (Map<String, Object> t : db.list("select t.store_id, t.creator_id, count(*) n from kg_task_assignee a join kg_task t on t.task_id = a.task_id "
                + "where a.status in ('accepted','done') and a.checked_time is null group by t.store_id, t.creator_id", p()))
        {
            storeTasks += oldManagerAt.contains(pair(t.get("creatorId"), t.get("storeId"))) ? StaffSupport.toInt(t.get("n"), 0) : 0;
        }
        out.add(warning("uncheckedStoreTasks", "info", "老主理人发的门店任务还没验收：切换后他进不了任务页", storeTasks, new ArrayList<Map<String, Object>>()));

        long todos = 0;
        for (Map<String, Object> t : db.list("select store_id, owner_id from kg_todo_meta where owner_id is not null", p()))
        {
            todos += oldManagerAt.contains(pair(t.get("ownerId"), t.get("storeId"))) ? 1 : 0;
        }
        out.add(warning("assignedTodos", "info", "指派给老主理人的待办：不自动迁移", todos, new ArrayList<Map<String, Object>>()));

        Set<Long> reception = new LinkedHashSet<Long>();
        for (Map<String, Object> r : db.list("select store_id, first_id, second_id from kg_chat_reception", p()))
        {
            if (oldManagerAt.contains(pair(r.get("firstId"), r.get("storeId"))) || oldManagerAt.contains(pair(r.get("secondId"), r.get("storeId"))))
            {
                reception.add(KgJdbc.asLong(r.get("storeId")));
            }
        }
        out.add(warning("chatReception", "info", "线上接待选的是老主理人的门店：切换后要重新选接待账号", reception.size(), storeItems(reception, stores)));

        Set<Long> mixed = new LinkedHashSet<Long>(oldManagerUsers);
        mixed.retainAll(oldStaffUsers);
        out.add(warning("mixedAccounts", "info", "同一账号这家店是老主理人、那家店是老员工：切完就是各店各自的新角色", mixed.size(), new ArrayList<Map<String, Object>>()));

        long recruitDaily = db.count("select count(*) from kg_daily_entry where ledger = 'recruit'", p());
        long recruitWeekly = db.count("select count(*) from kg_weekly_report where report_type = 'recruit'", p());
        out.add(warning("recruitLedger", "info", "已有招募账明细、招募周报：挂在老主理人的门店上，切换后老主理人看不到，也不会迁移", recruitDaily + recruitWeekly, new ArrayList<Map<String, Object>>()));

        out.add(warning("activeRequests", "info", "还在处理中的商家需求（开关关着时预期没有）", db.count("select count(*) from kg_merchant_request where del_flag = '0' and status not in ('unassigned','closed','done','published','returned')", p()),
                new ArrayList<Map<String, Object>>()));
        return out;
    }

    // ------------------------------------------------------------------ 切换

    public Map<String, Object> switchOver(String fingerprint, String confirm, String operator)
    {
        if (!CONFIRM_SWITCH.equals(confirm == null ? "" : confirm.trim()))
        {
            throw new ServiceException("请输入「" + CONFIRM_SWITCH + "」确认", 422);
        }
        if (fingerprint == null || fingerprint.isEmpty())
        {
            throw new ServiceException("缺少名单指纹，请刷新页面后重看名单", 422);
        }
        Map<String, Object> result;
        try
        {
            result = tx().execute(status -> doSwitch(fingerprint, operator));
        }
        catch (PessimisticLockingFailureException e)
        {
            throw new ServiceException("有人正在修改员工授权，这次没有切换。请稍后刷新再试", HttpStatus.CONFLICT);
        }
        catch (TransactionException e)
        {
            resyncAfterCommitFailure();
            throw e;
        }
        afterGrantChange();
        int sent = notifySwitched(result);
        result.remove("before");
        result.put("noticeSent", sent);
        return result;
    }

    private Map<String, Object> doSwitch(String fingerprint, String operator)
    {
        Map<String, Object> state = db.must("select * from kg_role_switch where id = 1 for update", p(), "没有角色切换状态行，V64 迁移没有执行");
        if (!RolePhase.LEGACY.equals(str(state.get("phase"))) || RolePhase.isNew())
        {
            throw new ServiceException("已经切换过了，请刷新页面", HttpStatus.CONFLICT);
        }
        // 第一条普通读之前就把授权行锁住：指纹算的就是最新提交的内容，算完到 UPDATE 之间后台、老小程序都改不了（新增也进不来）
        List<Map<String, Object>> before = db.list(GRANTS_SQL + " for update", p());
        if (!fingerprint.equals(fingerprint(before)))
        {
            throw new ServiceException("名单在你打开页面之后变过了，这次没有切换。请刷新页面重新看名单", HttpStatus.CONFLICT);
        }
        List<String> blockers = switchBlockers(before);
        if (!blockers.isEmpty())
        {
            throw new ServiceException("这次没有切换：" + String.join("；", blockers), HttpStatus.CONFLICT);
        }
        if (db.count("select count(*) from kg_role_switch_backup", p()) > 0)
        {
            throw new ServiceException("备份表不是空的（上次切换没退回干净），这次没有切换。请联系 Claude 处理", HttpStatus.CONFLICT);
        }

        for (Map<String, Object> g : before)
        {
            String old = str(g.get("roleCode"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("kind", "grant");
            row.put("grant_id", g.get("grantId"));
            row.put("old_role", old);
            row.put("new_role", mapped(old));
            row.put("old_update_by", g.get("updateBy"));
            row.put("old_update_time", g.get("updateTime"));
            row.put("create_time", new java.util.Date());
            db.insert("kg_role_switch_backup", row);
        }

        // 一条语句同时改，两个方向都在一个 CASE 里，不会有中间状态把 staff 变的 manager 再变一次
        db.update("update kg_staff_grant set role_code = case role_code when 'manager' then 'city_lead' when 'staff' then 'manager' end, update_by = :by, update_time = now() "
                + "where role_code in ('manager', 'staff')", p("by", BY_SWITCH));

        writeDictionary();
        verifySwitch(before);

        db.update("update kg_role_switch set phase = 'new', fingerprint = :f, switched_by = :by, switched_time = now(), update_time = now() where id = 1", p("f", fingerprint, "by", operator));

        // 提交前先收紧内存阶段，没提交成功就恢复。必须先注册再改
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCompletion(int status)
            {
                // 提交结果不明（STATUS_UNKNOWN）时先留在 new，只会收紧；出了事务再按库里的真值对齐（resyncAfterCommitFailure）
                if (status == STATUS_ROLLED_BACK)
                {
                    RolePhase.set(RolePhase.LEGACY);
                }
            }
        });
        RolePhase.set(RolePhase.NEW);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        long leads = 0;
        long managers = 0;
        for (Map<String, Object> g : before)
        {
            leads += StaffRole.MANAGER.equals(str(g.get("roleCode"))) ? 1 : 0;
            managers += StaffRole.STAFF.equals(str(g.get("roleCode"))) ? 1 : 0;
        }
        out.put("phase", RolePhase.NEW);
        out.put("toCityLead", leads);
        out.put("toManager", managers);
        out.put("totalRows", before.size());
        out.put("before", before);
        return out;
    }

    /** 提交报错时结果不明：按库里的真值对齐内存阶段；读不到就留在 new（只会收紧，不会放大） */
    private void resyncAfterCommitFailure()
    {
        try
        {
            Map<String, Object> row = db.one("select phase from kg_role_switch where id = 1", p());
            if (row != null)
            {
                RolePhase.set(str(row.get("phase")));
            }
        }
        catch (RuntimeException e)
        {
            log.error("角色切换提交失败后读不到阶段，内存阶段留在 {}：{}", RolePhase.code(), e.getMessage());
        }
    }

    /** 写实文字和排序，staff 停用；没有的补上（online_staff 在有的库里没进字典）。原值进备份 */
    private void writeDictionary()
    {
        for (Object[] d : DICT)
        {
            String value = (String) d[0];
            Map<String, Object> cur = db.one("select dict_code, dict_label, dict_sort, status, is_default from sys_dict_data where dict_type = :t and dict_value = :v order by dict_code limit 1 for update",
                    p("t", DICT_TYPE, "v", value));
            if (cur == null && "1".equals(d[3]))
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("kind", "dict");
            row.put("dict_value", value);
            row.put("dict_existed", cur == null ? "0" : "1");
            row.put("old_dict_label", cur == null ? null : cur.get("dictLabel"));
            row.put("old_dict_sort", cur == null ? null : cur.get("dictSort"));
            row.put("old_dict_status", cur == null ? null : cur.get("status"));
            row.put("old_dict_default", cur == null ? null : cur.get("isDefault"));
            row.put("new_dict_label", d[1]);
            row.put("new_dict_sort", d[2]);
            row.put("new_dict_status", d[3]);
            row.put("create_time", new java.util.Date());
            db.insert("kg_role_switch_backup", row);
            if (cur == null)
            {
                db.update("insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) "
                        + "values (:s, :l, :v, :t, '', 'info', 'N', :st, :by, now(), '')", p("s", d[2], "l", d[1], "v", value, "t", DICT_TYPE, "st", d[3], "by", BY_SWITCH));
            }
            else
            {
                db.update("update sys_dict_data set dict_label = :l, dict_sort = :s, status = :st, is_default = 'N', update_by = :by, update_time = now() where dict_code = :c",
                        p("l", d[1], "s", d[2], "st", d[3], "by", BY_SWITCH, "c", cur.get("dictCode")));
            }
        }
    }

    /** 逐行等于「名单按映射算的结果」，其他行、其他列都不变，staff 为 0，人数对得上。不对就抛错，整个事务回滚 */
    private void verifySwitch(List<Map<String, Object>> before)
    {
        List<Map<String, Object>> after = db.list(GRANTS_SQL + " for update", p());
        if (after.size() != before.size())
        {
            throw new IllegalStateException("切换核对失败：授权行数变了（" + before.size() + " → " + after.size() + "）");
        }
        long oldManager = 0;
        long oldStaff = 0;
        long oldLead = 0;
        long newManager = 0;
        long newLead = 0;
        for (int i = 0; i < before.size(); i++)
        {
            Map<String, Object> b = before.get(i);
            Map<String, Object> a = after.get(i);
            String id = str(b.get("grantId"));
            if (!id.equals(str(a.get("grantId"))))
            {
                throw new IllegalStateException("切换核对失败：授权行不对应，grant_id " + id);
            }
            String old = str(b.get("roleCode"));
            if (!mapped(old).equals(str(a.get("roleCode"))))
            {
                throw new IllegalStateException("切换核对失败：grant_id " + id + " 角色 " + old + " → " + a.get("roleCode"));
            }
            for (String col : UNTOUCHED)
            {
                if (!Objects.equals(str(b.get(col)), str(a.get(col))))
                {
                    throw new IllegalStateException("切换核对失败：grant_id " + id + " 的 " + col + " 变了");
                }
            }
            boolean changed = !old.equals(mapped(old));
            if (changed ? !BY_SWITCH.equals(str(a.get("updateBy"))) : !Objects.equals(str(b.get("updateBy")), str(a.get("updateBy"))) || !Objects.equals(str(b.get("updateTime")), str(a.get("updateTime"))))
            {
                throw new IllegalStateException("切换核对失败：grant_id " + id + " 的审计列不对");
            }
            oldManager += StaffRole.MANAGER.equals(old) ? 1 : 0;
            oldStaff += StaffRole.STAFF.equals(old) ? 1 : 0;
            oldLead += StaffRole.CITY_LEAD.equals(old) ? 1 : 0;
            newManager += StaffRole.MANAGER.equals(str(a.get("roleCode"))) ? 1 : 0;
            newLead += StaffRole.CITY_LEAD.equals(str(a.get("roleCode"))) ? 1 : 0;
            if (StaffRole.STAFF.equals(str(a.get("roleCode"))))
            {
                throw new IllegalStateException("切换核对失败：grant_id " + id + " 还是 staff");
            }
        }
        if (newManager != oldStaff || newLead != oldManager + oldLead)
        {
            throw new IllegalStateException("切换核对失败：人数对不上（换工主理人 " + newManager + "/" + oldStaff + "，城市负责人 " + newLead + "/" + (oldManager + oldLead) + "）");
        }
        if (db.count("select count(*) from sys_dict_data where dict_type = :t and dict_value = 'staff' and status <> '1'", p("t", DICT_TYPE)) > 0)
        {
            throw new IllegalStateException("切换核对失败：字典里员工没有停用");
        }
    }

    /** 提交之后：刷字典缓存、失败不影响已经提交的切换 */
    private void afterGrantChange()
    {
        try
        {
            dictTypes.resetDictCache();
        }
        catch (RuntimeException e)
        {
            log.warn("角色切换后刷新字典缓存失败：{}", e.getMessage());
        }
    }

    /** 按人发一条站内信（biz_type = grant，城市负责人也能看到）；发信失败不回滚，只记日志 */
    private int notifySwitched(Map<String, Object> result)
    {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> before = (List<Map<String, Object>>) result.get("before");
        Map<Long, Set<String>> leadRoles = new LinkedHashMap<Long, Set<String>>();
        Map<Long, Long> firstGrant = new LinkedHashMap<Long, Long>();
        for (Map<String, Object> g : before)
        {
            String old = str(g.get("roleCode"));
            if (active(g) && (StaffRole.MANAGER.equals(old) || StaffRole.STAFF.equals(old) || StaffRole.CITY_LEAD.equals(old)))
            {
                Long uid = KgJdbc.asLong(g.get("appUserId"));
                leadRoles.computeIfAbsent(uid, k -> new LinkedHashSet<String>()).add(old);
                firstGrant.putIfAbsent(uid, KgJdbc.asLong(g.get("grantId")));
            }
        }
        int sent = 0;
        for (Map.Entry<Long, Set<String>> e : leadRoles.entrySet())
        {
            List<String> parts = new ArrayList<String>();
            if (e.getValue().contains(StaffRole.MANAGER))
            {
                parts.add("你原来的换工主理人授权已调整为「城市负责人」：看本城需求和数字，审批有授权门店的活动退款，不再做门店日常。");
            }
            if (e.getValue().contains(StaffRole.STAFF))
            {
                parts.add("你原来的员工授权已调整为「换工主理人」：负责招募和门店日常，不再处理退款和收费活动取消。");
            }
            if (e.getValue().contains(StaffRole.CITY_LEAD))
            {
                parts.add("你的城市负责人身份多了有授权门店的活动退款审批和取消收费活动。");
            }
            try
            {
                grants.notifyGrantChange(e.getKey(), null, "你的门店角色已调整", String.join("", parts) + "请彻底关闭小程序重新打开，更新到最新版后使用。", firstGrant.get(e.getKey()));
                sent++;
            }
            catch (RuntimeException ex)
            {
                log.warn("角色切换站内信发送失败 user={} : {}", e.getKey(), ex.getMessage());
            }
        }
        return sent;
    }

    // ------------------------------------------------------------------ 退回

    public Map<String, Object> rollback(String confirm, String operator)
    {
        if (!CONFIRM_ROLLBACK.equals(confirm == null ? "" : confirm.trim()))
        {
            throw new ServiceException("请输入「" + CONFIRM_ROLLBACK + "」确认", 422);
        }
        Map<String, Object> result;
        try
        {
            result = tx().execute(status -> doRollback(operator));
        }
        catch (PessimisticLockingFailureException e)
        {
            throw new ServiceException("有人正在修改员工授权，这次没有退回。请稍后刷新再试", HttpStatus.CONFLICT);
        }
        catch (TransactionException e)
        {
            resyncAfterCommitFailure();
            throw e;
        }
        afterGrantChange();
        return result;
    }

    private Map<String, Object> doRollback(String operator)
    {
        Map<String, Object> state = db.must("select * from kg_role_switch where id = 1 for update", p(), "没有角色切换状态行，V64 迁移没有执行");
        List<Map<String, Object>> rows = db.list(GRANTS_SQL + " for update", p());
        String blocked = rollbackBlocked(state, rows);
        if (blocked != null)
        {
            throw new ServiceException(blocked, HttpStatus.CONFLICT);
        }
        RollbackPlan plan = rollbackPlan(rows, backupRows(), state.get("switchedTime"));
        for (Map<String, Object> r : plan.restore)
        {
            if (BY_SWITCH.equals(str(r.get("updateBy"))))
            {
                db.update("update kg_staff_grant set role_code = :r, update_by = :by, update_time = :t where grant_id = :g", p("r", r.get("oldRole"), "by", r.get("oldUpdateBy"), "t", r.get("oldUpdateTime"), "g", r.get("grantId")));
            }
            else
            {
                // 切换之后别人保存过：角色还原，审计列是别人的，不盖
                db.update("update kg_staff_grant set role_code = :r where grant_id = :g", p("r", r.get("oldRole"), "g", r.get("grantId")));
            }
        }
        for (Map<String, Object> r : plan.converted)
        {
            db.update("update kg_staff_grant set role_code = :r, update_by = :by, update_time = now() where grant_id = :g", p("r", r.get("afterRole"), "by", BY_ROLLBACK, "g", r.get("grantId")));
        }
        long restored = plan.restore.size();
        long added = plan.converted.size();
        List<Map<String, Object>> skipped = plan.skipped;
        List<Map<String, Object>> dictSkipped = restoreDictionary();

        String note = rollbackNote(restored, plan.converted, skipped, dictSkipped.size());
        db.update("delete from kg_role_switch_backup", p());
        db.update("update kg_role_switch set phase = 'legacy', fingerprint = null, switched_by = null, switched_time = null, rolled_back_by = :by, rolled_back_time = now(), rollback_note = :n, update_time = now() where id = 1",
                p("by", operator, "n", note));

        // 退回提交之后内存阶段才回 legacy：提交前数据已是旧的、规则还是新的，只会收紧
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCommit()
            {
                RolePhase.set(RolePhase.LEGACY);
            }
        });

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("phase", RolePhase.LEGACY);
        out.put("restored", restored);
        out.put("addedConverted", added);
        out.put("converted", plan.converted);
        out.put("skipped", skipped);
        out.put("dictSkipped", dictSkipped);
        out.put("message", note);
        return out;
    }

    /** 退回要做的事：preview 列给主人看，doRollback 照着做，所以两边一定一样 */
    static final class RollbackPlan
    {
        final List<Map<String, Object>> restore = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> converted = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> skipped = new ArrayList<Map<String, Object>>();

        Map<String, Object> view()
        {
            return KgJdbc.p("restoreCount", restore.size(), "converted", converted, "skipped", skipped);
        }
    }

    private Map<Long, Map<String, Object>> backupRows()
    {
        Map<Long, Map<String, Object>> backup = new TreeMap<Long, Map<String, Object>>();
        for (Map<String, Object> b : db.list("select * from kg_role_switch_backup where kind = 'grant'", p()))
        {
            backup.put(KgJdbc.asLong(b.get("grantId")), b);
        }
        return backup;
    }

    /** 这一行是切换之后才授予的（切换前已撤销、切换后在后台重新授予的行 granted_time 会重写） */
    private static boolean grantedAfterSwitch(Map<String, Object> g, Object switchedTime)
    {
        java.util.Date granted = StaffSupport.parse(g.get("grantedTime"));
        java.util.Date switched = StaffSupport.parse(switchedTime);
        return granted != null && switched != null && granted.after(switched);
    }

    /**
     * 退回规则（紧急脚本 emergency_role_rollback.py 同一规则，rehearse_emergency_rollback.py 在真库上逐行比）：
     *   切换后才授予的（不在备份里，或重新授予过）：换工主理人倒成老员工，城市负责人倒成老主理人；别的角色不用动。
     *   其余的按值判断：当前角色等于切换写的角色就还原；被改成别的角色的不动，列出来。
     */
    static RollbackPlan rollbackPlan(List<Map<String, Object>> rows, Map<Long, Map<String, Object>> backup, Object switchedTime)
    {
        RollbackPlan plan = new RollbackPlan();
        for (Map<String, Object> g : rows)
        {
            Long id = KgJdbc.asLong(g.get("grantId"));
            String role = str(g.get("roleCode"));
            Map<String, Object> b = backup.get(id);
            if (b == null || grantedAfterSwitch(g, switchedTime))
            {
                if (!role.equals(unmapped(role)))
                {
                    plan.converted.add(convertedRow(g, unmapped(role)));
                }
                continue;
            }
            String oldRole = str(b.get("oldRole"));
            String newRole = str(b.get("newRole"));
            if (!role.equals(newRole))
            {
                plan.skipped.add(skippedRow(g, oldRole));
            }
            else if (!oldRole.equals(newRole))
            {
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("grantId", id);
                r.put("oldRole", oldRole);
                r.put("updateBy", g.get("updateBy"));
                r.put("oldUpdateBy", b.get("oldUpdateBy"));
                r.put("oldUpdateTime", b.get("oldUpdateTime"));
                plan.restore.add(r);
            }
        }
        return plan;
    }

    private static List<String> grantIds(List<Map<String, Object>> rows)
    {
        List<String> ids = new ArrayList<String>();
        for (Map<String, Object> r : rows)
        {
            ids.add(str(r.get("grantId")));
        }
        return ids;
    }

    /** 上次退回的摘要，只有数字和授权编号：刷新页面后主人还查得到是哪几行。紧急脚本写同样的文字 */
    static String rollbackNote(long restored, List<Map<String, Object>> converted, List<Map<String, Object>> skipped, int dictSkipped)
    {
        String note = "还原 " + restored + " 行，切换后新开的倒回 " + converted.size() + " 行，后改过没动 " + skipped.size() + " 行，字典没动 " + dictSkipped + " 条";
        if (!converted.isEmpty())
        {
            note += "；倒回的授权编号：" + String.join(",", grantIds(converted));
        }
        if (!skipped.isEmpty())
        {
            note += "；没动的授权编号：" + String.join(",", grantIds(skipped));
        }
        return StaffSupport.cut(note, 2000);
    }

    private static Map<String, Object> convertedRow(Map<String, Object> g, String afterRole)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("grantId", g.get("grantId"));
        m.put("appUserId", g.get("appUserId"));
        m.put("storeId", g.get("storeId"));
        m.put("currentRole", g.get("roleCode"));
        m.put("afterRole", afterRole);
        m.put("status", g.get("status"));
        m.put("updateBy", g.get("updateBy"));
        m.put("updateTime", g.get("updateTime"));
        m.put("attention", StaffRole.MANAGER.equals(afterRole) && active(g) ? "退回后这一行是老主理人（有资金权，能加人、撤人），请确认" : "");
        return m;
    }

    private static Map<String, Object> skippedRow(Map<String, Object> g, String oldRole)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("grantId", g.get("grantId"));
        m.put("appUserId", g.get("appUserId"));
        m.put("storeId", g.get("storeId"));
        m.put("currentRole", g.get("roleCode"));
        m.put("beforeSwitchRole", oldRole);
        m.put("status", g.get("status"));
        m.put("updateBy", g.get("updateBy"));
        m.put("updateTime", g.get("updateTime"));
        // 退回后 manager 又是带资金权的老主理人：切换后改成 manager 的人要主人看一眼
        m.put("attention", StaffRole.MANAGER.equals(str(g.get("roleCode"))) && active(g) ? "退回后这一行是老主理人（有资金权），请确认" : "");
        return m;
    }

    /** 还原字典：切换后被运营改过的不动，列出来 */
    private List<Map<String, Object>> restoreDictionary()
    {
        List<Map<String, Object>> skipped = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> b : db.list("select * from kg_role_switch_backup where kind = 'dict'", p()))
        {
            String value = str(b.get("dictValue"));
            Map<String, Object> cur = db.one("select dict_code, dict_label, dict_sort, status from sys_dict_data where dict_type = :t and dict_value = :v order by dict_code limit 1 for update",
                    p("t", DICT_TYPE, "v", value));
            if (cur == null)
            {
                continue;
            }
            if (!str(cur.get("dictLabel")).equals(str(b.get("newDictLabel"))) || !str(cur.get("dictSort")).equals(str(b.get("newDictSort"))) || !str(cur.get("status")).equals(str(b.get("newDictStatus"))))
            {
                skipped.add(KgJdbc.p("dictValue", value, "currentLabel", cur.get("dictLabel")));
                continue;
            }
            if ("0".equals(str(b.get("dictExisted"))))
            {
                db.update("delete from sys_dict_data where dict_code = :c", p("c", cur.get("dictCode")));
            }
            else
            {
                db.update("update sys_dict_data set dict_label = :l, dict_sort = :s, status = :st, is_default = :d, update_by = :by, update_time = now() where dict_code = :c",
                        p("l", b.get("oldDictLabel"), "s", b.get("oldDictSort"), "st", b.get("oldDictStatus"), "d", b.get("oldDictDefault"), "by", BY_ROLLBACK, "c", cur.get("dictCode")));
            }
        }
        return skipped;
    }

    // ------------------------------------------------------------------ 打开派单

    public Map<String, Object> openRouting(String confirm, String operator)
    {
        if (!CONFIRM_ROUTING.equals(confirm == null ? "" : confirm.trim()))
        {
            throw new ServiceException("请输入「" + CONFIRM_ROUTING + "」确认", 422);
        }
        Map<String, Object> result = tx().execute(status -> {
            Map<String, Object> state = db.must("select * from kg_role_switch where id = 1 for update", p(), "没有角色切换状态行，V64 迁移没有执行");
            if (!RolePhase.NEW.equals(str(state.get("phase"))))
            {
                throw new ServiceException("还没切换角色，不能打开派单", HttpStatus.CONFLICT);
            }
            int n = db.update("update sys_config set config_value = 'on', update_by = :by, update_time = now() where config_key = :k", p("by", operator, "k", KgMerchantRequestService.ROUTING_KEY));
            if (n != 1)
            {
                throw new ServiceException("参数设置里没有派单开关 " + KgMerchantRequestService.ROUTING_KEY, HttpStatus.CONFLICT);
            }
            db.update("update kg_role_switch set routing_opened_by = :by, routing_opened_time = now(), update_time = now() where id = 1", p("by", operator));
            return KgJdbc.p("routing", "on");
        });
        try
        {
            // 开关走参数缓存，提交后马上刷，不然 staffRouting 还读旧值
            configs.resetConfigCache();
        }
        catch (RuntimeException e)
        {
            log.warn("打开派单后刷新参数缓存失败：{}", e.getMessage());
        }
        return result;
    }
}
