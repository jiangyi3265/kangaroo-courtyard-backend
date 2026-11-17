package com.ruoyi.kangaroo.ext.staff;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 员工端各模块共用：确定"当前门店 + 角色"、按权限矩阵把关、记员工操作日志、给门店员工发站内信。
 *
 * 门店永远以登录态里的授权快照为准：请求里传的 storeId 只是"想看哪家"，必须落在授权范围内。
 */
@Component
public class StaffSupport
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private IKgMessageService messages;

    @Autowired
    private IKgStaffGrantService grants;

    @Autowired(required = false)
    private CityLeadScope cityLeads;

    /** 按城市授权的员工（不含城市负责人、合作商家）：门店通知名单按所选角色算上他们；单测没注入时不算 */
    @Autowired(required = false)
    private CityGrantScope cityGrants;

    /** 一次请求里的员工身份：谁、在哪家门店、什么角色 */
    public static class Ctx
    {
        public AppLoginUser me;
        public Long storeId;
        public String storeName;
        public String role;
        /** 城市负责人顶某个二级角色时（KgMerchantRequestService.pick）：role 记他顶的角色，业务按它走；操作日志记这里的真实角色 */
        public String actingFrom;

        /** 这次是城市负责人顶上（不是这个角色本人）：不用补授权，也不看「这家店另有角色」 */
        public boolean cityLeadActing()
        {
            return StaffRole.CITY_LEAD.equals(actingFrom);
        }

        public Long uid()
        {
            return me.getUserId();
        }

        public String name()
        {
            String n = me.getNickname();
            return n == null || n.trim().isEmpty() ? "员工" + me.getUserId() : n;
        }
    }

    /** storeId 为空时取第一家授权门店；传了就必须有授权，否则 403 */
    public Ctx ctx(Long storeId)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        List<StaffGrantView> list = me.getStaffGrants();
        StaffGrantView hit = null;
        for (StaffGrantView g : list)
        {
            if (g.getStoreId() != null && RolePhase.usable(g.getRoleCode()) && (storeId == null || storeId.equals(g.getStoreId())))
            {
                hit = g;
                break;
            }
        }
        if (hit == null)
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        Ctx c = new Ctx();
        c.me = me;
        c.storeId = hit.getStoreId();
        c.storeName = hit.getStoreName();
        c.role = StaffRole.exists(hit.getRoleCode()) ? hit.getRoleCode() : StaffRole.STAFF;
        return c;
    }

    /** 对某家门店的操作：先确认有授权，再按那家门店上的角色取上下文 */
    public Ctx ctxForStore(Long storeId)
    {
        if (storeId == null)
        {
            throw new ServiceException("缺少门店");
        }
        return ctx(storeId);
    }

    /** 按权限矩阵把关；拒绝时记一条失败的操作日志（控制器里调用，保证不随业务事务回滚） */
    public void need(Ctx c, String item, String action, String opAction, String opLabel)
    {
        if (!StaffPerms.allows(c.role, item, action))
        {
            op(c, opAction, opLabel, categoryOf(action), null, false, "无" + StaffPerms.ACTIONS.get(action) + "权限");
            StaffPerms.require(c.role, item, action);
        }
    }

    /**
     * 只有本店主理人可以做的事（退款审批、取消收费活动：都是资金动作，按约定 P1★ / 验收 A07 不下放给管家）。
     * 拒绝时同样记一条失败的操作日志。
     * 角色切换后这个判断一律拒绝：资金动作只归城市负责人（KgCityFinanceService，有自己的接口），换工主理人不能做。
     */
    public void needManager(Ctx c, String opAction, String opLabel)
    {
        if (RolePhase.isNew())
        {
            op(c, opAction, opLabel, "fund", null, false, "资金操作归城市负责人");
            if (StaffRole.CITY_LEAD.equals(c.role))
            {
                // 走到这里的都是旅居、服务订单的退款：二级角色都没有，继续只走平台后台。活动退款城市负责人走 /activities 和 /city-finance，不经过这里
                throw new ServiceException("旅居和服务订单的退款由平台在后台处理", HttpStatus.FORBIDDEN);
            }
            throw new ServiceException("退款和取消收费活动已交给城市负责人，请联系城市负责人或平台处理（当前角色：" + StaffRole.label(c.role) + "）", HttpStatus.FORBIDDEN);
        }
        if (!StaffRole.MANAGER.equals(c.role))
        {
            op(c, opAction, opLabel, "fund", null, false, "仅限主理人");
            throw new ServiceException("这项操作只有本店主理人可以做（当前角色：" + StaffRole.label(c.role) + "）", HttpStatus.FORBIDDEN);
        }
        if (autoManager(c))
        {
            op(c, opAction, opLabel, "fund", null, false, "派单授权不含资金操作");
            throw new ServiceException("这家店是派单给你的，退款和取消收费活动请联系平台处理", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 能不能动这家店活动的钱（活动退款审批、取消已有报名或收费的活动）。切换前：换工主理人或活动·支付（老员工有）；切换后：只有城市负责人。
     * 派单补的授权另由 autoManager 挡。
     */
    public static boolean canFund(Ctx c)
    {
        if (RolePhase.isNew())
        {
            return StaffRole.CITY_LEAD.equals(c.role);
        }
        return StaffRole.MANAGER.equals(c.role) || StaffPerms.allows(c.role, "activities", StaffPerms.PAY);
    }

    /**
     * 能管这家店的城市负责人（活动退款待审批、退款异常发给他们）：切换后按城市算（CityLeadScope），切换前只有在这家店有授权的。
     * 账号或门店停用的不算：名单非空就不走「请联系平台后台」的兜底，发给停用账号等于没人知道，用户的钱一直挂着。
     */
    public List<Long> cityLeadsOfStore(Long storeId)
    {
        if (RolePhase.isNew() && cityLeads != null)
        {
            return cityLeads.leadsOf(storeId);
        }
        List<Long> ids = new java.util.ArrayList<Long>();
        for (Map<String, Object> g : db.list("select g.app_user_id from kg_staff_grant g join kg_store s on s.store_id = g.store_id join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.store_id = :s and g.role_code = 'city_lead' and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0' and u.status = '0' and u.del_flag = '0' order by g.grant_id",
                KgJdbc.p("s", storeId)))
        {
            ids.add(KgJdbc.asLong(g.get("appUserId")));
        }
        return ids;
    }

    /**
     * 这个人在当前门店的换工主理人授权是不是商家需求自动补的（granted_by = KgMerchantRequestService.GRANTED_BY）。
     * 这种授权只用来完善、上架、审核报名、面试、跟进到岗；管人（授予、撤销）、线上接待设置、动钱都不给。每次读库，不看登录态快照。
     */
    public boolean autoManager(Ctx c)
    {
        if (!StaffRole.MANAGER.equals(c.role))
        {
            return false;
        }
        Map<String, Object> g = db.one("select granted_by from kg_staff_grant where app_user_id = :u and store_id = :s and status = '0' and del_flag = '0' limit 1",
                KgJdbc.p("u", c.uid(), "s", c.storeId));
        return g != null && KgMerchantRequestService.GRANTED_BY.equals(str(g.get("grantedBy")));
    }

    private static String categoryOf(String action)
    {
        if (StaffPerms.EXPORT.equals(action))
        {
            return "export";
        }
        if (StaffPerms.PAY.equals(action))
        {
            return "fund";
        }
        return "general";
    }

    /** 员工操作日志。category：general 一般 / export 数据导出 / fund 资金 / grant 授权 */
    public void op(Ctx c, String action, String label, String category, String detail, boolean ok, String failReason)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", c.storeId);
        row.put("app_user_id", c.uid());
        row.put("operator_name", cut(c.name(), 64));
        row.put("role_code", c.actingFrom != null ? c.actingFrom : c.role);
        row.put("action", action);
        row.put("action_label", label);
        row.put("category", category == null ? "general" : category);
        row.put("detail", cut(detail, 255));
        row.put("device", device());
        row.put("ok", ok ? "1" : "0");
        row.put("fail_reason", cut(failReason, 128));
        row.put("create_time", new Date());
        db.insert("kg_staff_op_log", row);
    }

    /** 从 User-Agent 粗分设备，只用于日志展示，不参与任何判断 */
    public static String device()
    {
        HttpServletRequest req = null;
        try
        {
            req = ServletUtils.getRequest();
        }
        catch (Exception ignore)
        {
            // 非请求线程（定时任务）没有 UA
        }
        String ua = req == null ? null : req.getHeader("User-Agent");
        if (ua == null || ua.isEmpty())
        {
            return "系统";
        }
        String base;
        Matcher android = Pattern.compile("Android ([0-9]+)").matcher(ua);
        if (ua.contains("iPhone"))
        {
            base = "iPhone";
        }
        else if (ua.contains("iPad"))
        {
            base = "iPad";
        }
        else if (android.find())
        {
            base = "Android " + android.group(1);
        }
        else if (ua.contains("Windows"))
        {
            base = "Windows";
        }
        else if (ua.contains("Mac OS X"))
        {
            base = "Mac";
        }
        else
        {
            base = "其他设备";
        }
        if (ua.contains("miniProgram") || ua.contains("MiniProgram"))
        {
            return base + " · 小程序";
        }
        if (ua.contains("MicroMessenger"))
        {
            return base + " · 微信";
        }
        return base;
    }

    /** 线上任务相关的门店消息：这些才发给这家店的商家服务人（KgMessageMapper 放行的也是这几类） */
    static boolean onlineNotice(String bizType, String route)
    {
        return "online_submission".equals(bizType) || "online_settlement".equals(bizType) || (route != null && route.startsWith("/pages/staff/online-jobs"));
    }

    /**
     * 给门店在职员工发站内信（员工身份的消息中心）。这家店的商家服务人只收线上任务相关的，报名、订单、活动这些他无权查看，姓名和内容不能露在消息里。
     * 线上任务相关的（onlineNotice）不全店群发，只发给点开能办这件事的人（onlineRecipients）：之前换工主理人收到点开全是 403，
     * 自营店能登记付款的城市负责人反而收不到「待付款」。online_settlement 的 bizId 是接单（报名）ID。
     */
    public int notifyStore(Long storeId, String category, String title, String content, String bizType, Long bizId,
            String route)
    {
        if (stayOrderNotice(bizType, route))
        {
            List<Long> ids = stayRecipients(storeId);
            for (Long uid : ids) messages.send(uid, "staff", category, title, content, bizType, bizId, route);
            return ids.size();
        }
        if (onlineNotice(bizType, route))
        {
            boolean payout = "online_settlement".equals(bizType);
            return notifyOnline(storeId, payout, payout ? bizId : null, category, title, content, bizType, bizId, route);
        }
        List<Long> ids = grants.listActiveStaffUserIds(storeId);
        if (ids == null)
        {
            return 0;
        }
        List<Long> service = grants.listMerchantServiceUserIds(storeId);
        if (service != null && !service.isEmpty())
        {
            ids = new java.util.ArrayList<Long>(ids);
            ids.removeAll(service);
        }
        // 这家店没有换工主理人时，城市负责人顶上：门店通知也发给他（平常不发，他不做日常）
        if (cityLeads != null)
        {
            for (Long lead : cityLeads.standIns(storeId))
            {
                if (!ids.contains(lead))
                {
                    ids = new java.util.ArrayList<Long>(ids);
                    ids.add(lead);
                }
            }
        }
        for (Long uid : ids)
        {
            messages.send(uid, "staff", category, title, content, bizType, bizId, route);
        }
        return ids.size();
    }

    /** 住宿与旅居增值服务订单；保险报名、活动订单不能按 category=order 一起屏蔽。 */
    public static boolean stayOrderNotice(String bizType, String route)
    {
        return "stay_order".equals(bizType) || "service_order".equals(bizType)
                || "/pages/staff/stay-orders".equals(route)
                || (route != null && route.startsWith("/pages/staff/stay-orders?"));
    }

    /** 与旅居接口 rooms·view 同源：门店及城市授权均现算，兼任按有权限的授权收一条。 */
    public List<Long> stayRecipients(Long storeId)
    {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<Long>();
        if (storeId == null) return new java.util.ArrayList<Long>();
        for (Map<String, Object> grant : withCityHolders(storeId, db.list(
                "select g.app_user_id, g.role_code, g.also_service from kg_staff_grant g "
                + "join kg_store s on s.store_id=g.store_id join kg_app_user u on u.user_id=g.app_user_id "
                + "where g.store_id=:s and g.status='0' and g.del_flag='0' and s.status='0' and s.del_flag='0' "
                + "and u.status='0' and u.del_flag='0' order by g.grant_id", KgJdbc.p("s", storeId))))
        {
            String role = str(grant.get("roleCode"));
            Long uid = KgJdbc.asLong(grant.get("appUserId"));
            if (uid != null && RolePhase.usable(role) && StaffPerms.allows(role, "rooms", StaffPerms.VIEW)) ids.add(uid);
        }
        if (RolePhase.usable(StaffRole.CITY_LEAD) && StaffPerms.allows(StaffRole.CITY_LEAD, "rooms", StaffPerms.VIEW))
            ids.addAll(cityLeadsOfStore(storeId));
        return new java.util.ArrayList<Long>(ids);
    }

    /** 线上任务的门店消息：只发给 onlineRecipients 算出来的人。payout 见 onlineRecipients；appId 只有付款类要用 */
    public int notifyOnline(Long storeId, boolean payout, Long appId, String category, String title, String content, String bizType, Long bizId,
            String route)
    {
        List<Long> ids = onlineRecipients(storeId, payout, appId);
        for (Long uid : ids)
        {
            messages.send(uid, "staff", category, title, content, bizType, bizId, route);
        }
        return ids.size();
    }

    /**
     * 线上任务的门店消息该发给谁：只发给点开能办这件事的人，按权限矩阵（StaffPerms）算，不写死角色名单。
     *   付款类（payout = true：待付款、确认收款、收款申诉）：能登记线下付款的（StaffPerms.canRegisterOnlinePayout：合作商家、商家服务人、有「财务·支付」的）；
     *     兼任商家服务人的小院主理人只在商家的任务上算（同 KgOnlineTaskService.payoutRole，appId 是这笔接单）。
     *   管理类（新成果、审核结果、到期关闭、继续开启、请平台介入）：有「线上任务·编辑」的（发布、验收）。
     * 先看这家店在职的授权，管这家店的城市负责人在店里另有授权的按城市负责人算（同 CityLeadScope.expand）；
     * 店里一个能办的人都没有时，管这家店的城市负责人顶上（他有全部权限）。自营店的待付款就是这样：小院主理人、换工主理人都不能登记付款。
     */
    public List<Long> onlineRecipients(Long storeId, boolean payout, Long appId)
    {
        List<Long> out = new java.util.ArrayList<Long>();
        if (storeId == null)
        {
            return out;
        }
        List<Long> leads = cityLeadsOfStore(storeId);
        Boolean merchantTask = null;
        // 本城按城市授权的人（这家店没有门店授权或撤销记录）也按所选角色算，排在门店授权后面
        for (Map<String, Object> g : withCityHolders(storeId, db.list("select g.app_user_id, g.role_code, g.also_service from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "join kg_app_user u on u.user_id = g.app_user_id where g.store_id = :s and g.status = '0' and g.del_flag = '0' and g.role_code <> 'city_lead' "
                + "and s.status = '0' and s.del_flag = '0' and u.status = '0' and u.del_flag = '0' order by g.grant_id", KgJdbc.p("s", storeId))))
        {
            Long uid = KgJdbc.asLong(g.get("appUserId"));
            if (uid == null || out.contains(uid))
            {
                continue;
            }
            String role = leads.contains(uid) ? StaffRole.CITY_LEAD : str(g.get("roleCode"));
            boolean can = canHandleOnline(role, payout);
            if (!can && payout && StaffRole.KEEPER.equals(role) && "1".equals(str(g.get("alsoService"))))
            {
                if (merchantTask == null)
                {
                    merchantTask = merchantTaskOfApp(storeId, appId);
                }
                can = merchantTask;
            }
            if (can)
            {
                out.add(uid);
            }
        }
        if (out.isEmpty() && canHandleOnline(StaffRole.CITY_LEAD, payout))
        {
            for (Long lead : leads)
            {
                if (lead != null && !out.contains(lead))
                {
                    out.add(lead);
                }
            }
        }
        return out;
    }

    /** 门店名单行（app_user_id, role_code, also_service）后面接上本城按城市授权的人（CityGrantScope.holders，同样的列） */
    private List<Map<String, Object>> withCityHolders(Long storeId, List<Map<String, Object>> rows)
    {
        List<Map<String, Object>> extra = cityGrants == null ? null : cityGrants.holders(storeId);
        if (extra == null || extra.isEmpty())
        {
            return rows;
        }
        List<Map<String, Object>> out = new java.util.ArrayList<Map<String, Object>>(rows);
        out.addAll(extra);
        return out;
    }

    private static boolean canHandleOnline(String role, boolean payout)
    {
        if (!RolePhase.usable(role))
        {
            return false;
        }
        return payout ? StaffPerms.canRegisterOnlinePayout(role) : StaffPerms.allows(role, "online", StaffPerms.EDIT);
    }

    /** 这笔接单的任务是不是这家店的商家需求转来的、门店不是直营（兼任的小院主理人在这种任务上按商家服务人登记付款） */
    private boolean merchantTaskOfApp(Long storeId, Long appId)
    {
        return appId != null && db.count("select count(*) from kg_application a join kg_merchant_request r on r.job_id = a.job_id join kg_store s on s.store_id = r.store_id "
                + "where a.app_id = :a and r.store_id = :s and r.del_flag = '0' and coalesce(s.op_type, '') <> 'direct'", KgJdbc.p("a", appId, "s", storeId)) > 0;
    }

    /**
     * 只发给这家店在职的小院主理人（keeper），不是 notifyStore 那样的全店群发。exceptUserId 不发（小院主理人自己写的评论，已经按本人通知过）。
     * 用在内容已经过平台审核、只该让小院主理人知道的通知上（小院评论，2026-10-31）。
     */
    public int notifyKeepers(Long storeId, Long exceptUserId, String category, String title, String content, String bizType, Long bizId,
            String route)
    {
        int n = 0;
        java.util.Set<Long> sent = new java.util.HashSet<Long>();
        List<Map<String, Object>> keepers = db.list("select distinct g.app_user_id from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.store_id = :s and g.role_code = :r and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0'",
                KgJdbc.p("s", storeId, "r", StaffRole.KEEPER));
        // 本城按城市授权的小院主理人也是这家店的小院主理人
        if (cityGrants != null)
        {
            keepers = new java.util.ArrayList<Map<String, Object>>(keepers);
            for (Long uid : cityGrants.holderIds(storeId, java.util.Collections.singletonList(StaffRole.KEEPER)))
            {
                keepers.add(KgJdbc.p("appUserId", uid));
            }
        }
        for (Map<String, Object> r : keepers)
        {
            Long uid = KgJdbc.asLong(r.get("appUserId"));
            if (uid != null && !uid.equals(exceptUserId) && sent.add(uid))
            {
                messages.send(uid, "staff", category, title, content, bizType, bizId, route);
                n++;
            }
        }
        return n;
    }

    public void notifyStaff(Long staffUserId, String category, String title, String content, String bizType, Long bizId,
            String route)
    {
        messages.send(staffUserId, "staff", category, title, content, bizType, bizId, route);
    }

    /** 同 notifyStaff，但把消息ID返回给调用方（简历转发要靠它统计已读；用户关了该类通知时为 null） */
    public Long notifyStaffWithId(Long staffUserId, String category, String title, String content, String bizType, Long bizId,
            String route)
    {
        return messages.send(staffUserId, "staff", category, title, content, bizType, bizId, route);
    }

    public void notifyUser(Long userId, String category, String title, String content, String bizType, Long bizId,
            String route)
    {
        messages.send(userId, "user", category, title, content, bizType, bizId, route);
    }

    /** 返回实际写入的站内消息 ID；关闭该通知时为 null，不代表微信已送达。 */
    public Long notifyUserWithId(Long userId, String category, String title, String content, String bizType, Long bizId,
            String route)
    {
        return messages.send(userId, "user", category, title, content, bizType, bizId, route);
    }

    // ---------- 小工具 ----------

    public static String cut(String s, int max)
    {
        if (s == null)
        {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static String str(Object o)
    {
        return o == null ? "" : String.valueOf(o);
    }

    public static int toInt(Object o, int dft)
    {
        if (o == null)
        {
            return dft;
        }
        if (o instanceof Number)
        {
            return ((Number) o).intValue();
        }
        try
        {
            return Integer.parseInt(String.valueOf(o).trim());
        }
        catch (NumberFormatException e)
        {
            return dft;
        }
    }

    /** 'yyyy-MM-dd HH:mm:ss' → Date；解析不了返回 null */
    public static Date parse(Object v)
    {
        if (v == null)
        {
            return null;
        }
        if (v instanceof Date)
        {
            return (Date) v;
        }
        String s = String.valueOf(v).trim();
        String[] fmts = { "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd" };
        for (String f : fmts)
        {
            if (s.length() == f.length())
            {
                try
                {
                    SimpleDateFormat sdf = new SimpleDateFormat(f);
                    sdf.setLenient(false);
                    return sdf.parse(s);
                }
                catch (Exception ignore)
                {
                    // 试下一种
                }
            }
        }
        return null;
    }

    public static String fmt(Date d, String pattern)
    {
        return d == null ? "" : new SimpleDateFormat(pattern).format(d);
    }

    private static final String[] WEEK = { "周日", "周一", "周二", "周三", "周四", "周五", "周六" };

    public static String week(Date d)
    {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        return WEEK[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** 8月29日 周五 */
    public static String dayLabel(Date d)
    {
        if (d == null)
        {
            return "";
        }
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 " + week(d);
    }

    public static Date startOfDay(Date d)
    {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    public static Date addDays(Date d, int n)
    {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.add(Calendar.DAY_OF_MONTH, n);
        return c.getTime();
    }

    /** 账号性别（kg_app_user：1 男 / 2 女 / 0 未知）或简历性别（kg_resume：男 / 女 / 其他）都认 */
    public static String genderLabel(Object g)
    {
        String s = str(g).trim();
        return "1".equals(s) || "男".equals(s) ? "男" : ("2".equals(s) || "女".equals(s) ? "女" : ("其他".equals(s) ? "其他" : "性别未填"));
    }

    /** 报名人性别：先看简历 kg_resume.gender，简历没填再退回账号资料 kg_app_user.gender（同人才库 KgTalentService） */
    public static String applicantGender(Object resumeGender, Object accountGender)
    {
        return str(resumeGender).trim().isEmpty() ? genderLabel(accountGender) : genderLabel(resumeGender);
    }

    public static String jobTypeLabel(Object t)
    {
        String s = str(t);
        if ("volunteer".equals(s))
        {
            return "换工";
        }
        if ("parttime".equals(s))
        {
            return "兼职";
        }
        if ("fulltime".equals(s))
        {
            return "全职";
        }
        if ("online".equals(s))
        {
            return "在线任务";
        }
        return s;
    }
}
