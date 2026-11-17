package com.ruoyi.kangaroo.ext.staff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;

/**
 * 员工端角色权限矩阵：员工工作台"角色与权限"页展示的就是这张表，员工端各写接口也按它校验。
 *
 * 取值 yes 有权限 / part 部分（只能处理本人经手的或只读汇总）/ no 无权限。
 * 改这里就是改线上权限，页面展示与服务端校验同源，不会出现"页面说能做、接口却拒绝"或反过来。
 *
 * 两张表（角色切换，见 RolePhase）：LEGACY 是切换前的表，一格都不能改（金标测试逐格比）；NEW 是切换后的表：
 * 换工主理人去掉财务和保险·支付，没有 staff 这一行，城市负责人是全部二级角色的并集加财务。收尾包删掉 LEGACY。
 */
public final class StaffPerms
{
    public static final String VIEW = "view";
    public static final String EDIT = "edit";
    public static final String AUDIT = "audit";
    public static final String PAY = "pay";
    public static final String EXPORT = "export";

    /** 权限项：编码 → 页面上的中文名（顺序即展示顺序） */
    public static final Map<String, String> ITEMS = new LinkedHashMap<String, String>();
    /** 动作：编码 → 中文名（顺序即表头顺序） */
    public static final Map<String, String> ACTIONS = new LinkedHashMap<String, String>();

    private static final Map<String, Map<String, String[]>> LEGACY = new HashMap<String, Map<String, String[]>>();
    private static final Map<String, Map<String, String[]>> NEW = new HashMap<String, Map<String, String[]>>();

    static
    {
        ITEMS.put("rooms", "房源管理");
        ITEMS.put("jobs", "岗位管理");
        ITEMS.put("online", "线上任务（编辑=发布、验收）");
        ITEMS.put("intake", "线上任务审核");
        ITEMS.put("dispatch", "调度管理");
        ITEMS.put("activities", "活动管理");
        ITEMS.put("reviews", "评价管理");
        ITEMS.put("insurance", "保险管理");
        ITEMS.put("finance", "财务管理");
        ITEMS.put("dashboard", "数据看板");

        ACTIONS.put(VIEW, "查看");
        ACTIONS.put(EDIT, "编辑");
        ACTIONS.put(AUDIT, "审核");
        ACTIONS.put(PAY, "支付");
        ACTIONS.put(EXPORT, "导出");

        //                                    查看    编辑    审核    支付    导出
        Map<String, String[]> manager = new HashMap<String, String[]>();
        manager.put("rooms", row("no", "no", "no", "no", "no"));
        manager.put("jobs", row("yes", "yes", "yes", "no", "yes"));
        manager.put("dispatch", row("yes", "yes", "yes", "no", "yes"));
        manager.put("activities", row("yes", "yes", "yes", "no", "yes"));
        manager.put("reviews", row("yes", "yes", "yes", "no", "yes"));
        manager.put("insurance", row("yes", "yes", "yes", "yes", "yes"));
        manager.put("finance", row("yes", "yes", "yes", "yes", "yes"));
        manager.put("dashboard", row("yes", "no", "no", "no", "yes"));

        Map<String, String[]> keeper = new HashMap<String, String[]>();
        keeper.put("rooms", row("yes", "yes", "yes", "no", "no"));
        keeper.put("jobs", row("yes", "yes", "yes", "no", "no"));
        keeper.put("dispatch", row("yes", "yes", "yes", "no", "no"));
        keeper.put("activities", row("yes", "yes", "yes", "no", "no"));
        keeper.put("reviews", row("yes", "yes", "yes", "no", "no"));
        keeper.put("insurance", row("yes", "yes", "no", "no", "no"));
        keeper.put("finance", row("part", "no", "no", "no", "no"));
        keeper.put("dashboard", row("yes", "no", "no", "no", "no"));

        Map<String, String[]> staff = new HashMap<String, String[]>();
        staff.put("rooms", row("no", "no", "no", "no", "no"));
        staff.put("jobs", row("yes", "yes", "yes", "yes", "yes"));
        staff.put("dispatch", row("yes", "yes", "yes", "yes", "yes"));
        staff.put("activities", row("yes", "yes", "yes", "yes", "yes"));
        staff.put("reviews", row("yes", "yes", "yes", "yes", "yes"));
        staff.put("insurance", row("yes", "yes", "yes", "yes", "yes"));
        staff.put("finance", row("no", "no", "no", "no", "no"));
        staff.put("dashboard", row("yes", "yes", "yes", "yes", "yes"));

        // 换工主理人不碰线上任务本身（10-29 的决定，online 这一行不动）；只核实商家提交的需求并转给商家服务人（intake）
        manager.put("online", row("no", "no", "no", "no", "no"));
        manager.put("intake", row("yes", "yes", "yes", "no", "no"));
        LEGACY.put(StaffRole.MANAGER, manager);
        keeper.put("online", keeper.get("jobs"));
        LEGACY.put(StaffRole.KEEPER, keeper);
        staff.put("online", row("no", "no", "no", "no", "no"));
        LEGACY.put(StaffRole.STAFF, staff);
        // 线上任务专员只有这一行：编辑 = 发布本店线上任务（平台审核后上架）+ 验收、退回成果；登记付款仍只有主理人（finance·支付）
        Map<String, String[]> online = new HashMap<String, String[]>();
        online.put("online", row("yes", "yes", "yes", "no", "no"));
        LEGACY.put(StaffRole.ONLINE_STAFF, online);
        Map<String, String[]> merchant = new HashMap<String, String[]>();
        for (String item : Arrays.asList("rooms", "jobs", "activities", "reviews"))
            merchant.put(item, row("yes", "yes", "no", "no", "no"));
        merchant.put("online", row("yes", "yes", "yes", "no", "no"));
        merchant.put("insurance", row("yes", "yes", "no", "no", "no"));
        LEGACY.put(StaffRole.MERCHANT, merchant);
        // 商家服务人：管商家店的线上任务（用现有线上任务页），看、接手转来的需求；付款登记另见 canRegisterOnlinePayout
        Map<String, String[]> service = new HashMap<String, String[]>();
        service.put("online", row("yes", "yes", "yes", "no", "no"));
        service.put("intake", row("yes", "yes", "no", "no", "no"));
        LEGACY.put(StaffRole.MERCHANT_SERVICE, service);
        // 城市负责人：只读
        Map<String, String[]> cityLead = new HashMap<String, String[]>();
        cityLead.put("intake", row("yes", "no", "no", "no", "no"));
        LEGACY.put(StaffRole.CITY_LEAD, cityLead);

        // 切换后：换工主理人不碰钱（老员工的调度、保险投保、岗位活动日常他本来就有）；保险·支付代码里没有接口，只是这一格换人
        Map<String, String[]> newManager = new HashMap<String, String[]>(manager);
        newManager.put("insurance", row("yes", "yes", "yes", "no", "yes"));
        newManager.put("finance", row("no", "no", "no", "no", "no"));
        NEW.put(StaffRole.MANAGER, newManager);
        NEW.put(StaffRole.KEEPER, keeper);
        NEW.put(StaffRole.ONLINE_STAFF, online);
        NEW.put(StaffRole.MERCHANT, merchant);
        NEW.put(StaffRole.MERCHANT_SERVICE, service);
        // 城市负责人有全部二级角色（小院主理人、换工主理人、线上任务专员、商家服务人）的权限，再加财务（2026-11-01 主人拍板）：每格是这几行的并集，范围见 CityLeadScope。
        // 活动·支付：退款审批和取消收费活动。管人不在表里，小程序里谁都不能授权。
        Map<String, String[]> newCityLead = new HashMap<String, String[]>();
        newCityLead.put("rooms", row("yes", "yes", "yes", "no", "no"));
        newCityLead.put("jobs", row("yes", "yes", "yes", "no", "yes"));
        newCityLead.put("online", row("yes", "yes", "yes", "no", "no"));
        newCityLead.put("intake", row("yes", "yes", "yes", "no", "no"));
        newCityLead.put("dispatch", row("yes", "yes", "yes", "no", "yes"));
        newCityLead.put("activities", row("yes", "yes", "yes", "yes", "yes"));
        newCityLead.put("reviews", row("yes", "yes", "yes", "no", "yes"));
        newCityLead.put("insurance", row("yes", "yes", "yes", "yes", "yes"));
        newCityLead.put("finance", row("yes", "yes", "yes", "yes", "yes"));
        newCityLead.put("dashboard", row("yes", "no", "no", "no", "yes"));
        NEW.put(StaffRole.CITY_LEAD, newCityLead);
    }

    private StaffPerms()
    {
    }

    private static String[] row(String... v)
    {
        return v;
    }

    /** 取值：yes / part / no；未知角色一律 no */
    public static String value(String role, String item, String action)
    {
        Map<String, String[]> m = (RolePhase.isNew() ? NEW : LEGACY).get(role);
        if (m == null || !m.containsKey(item))
        {
            return "no";
        }
        int idx = new ArrayList<String>(ACTIONS.keySet()).indexOf(action);
        return idx < 0 ? "no" : m.get(item)[idx];
    }

    /** yes 与 part 都算有权限：part 的细分边界由各接口自己收（例如只能改本人经手的） */
    public static boolean allows(String role, String item, String action)
    {
        return !"no".equals(value(role, item, action));
    }

    /**
     * 线上任务「登记线下付款」：付钱方是这家店的合作商家，商家服务人替他跑；原来 finance·支付 的角色不变。
     * 不把 finance 整行开给这两个角色：那会连带开放别的财务接口。
     */
    public static boolean canRegisterOnlinePayout(String role)
    {
        return StaffRole.MERCHANT.equals(role) || StaffRole.MERCHANT_SERVICE.equals(role) || allows(role, "finance", PAY);
    }

    /** 没权限直接 403，文案带上具体缺的是哪一项 */
    public static void require(String role, String item, String action)
    {
        if (!allows(role, item, action))
        {
            throw new ServiceException("当前角色（" + StaffRole.label(role) + "）没有「" + ITEMS.get(item)
                    + "·" + ACTIONS.get(action) + "」权限", HttpStatus.FORBIDDEN);
        }
    }

    /** 页面用的矩阵：[{code, name, values:[yes|part|no × 5]}] */
    public static List<Map<String, Object>> matrix(String role)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, String> e : ITEMS.entrySet())
        {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("code", e.getKey());
            r.put("name", e.getValue());
            List<String> values = new ArrayList<String>();
            for (String a : ACTIONS.keySet())
            {
                values.add(value(role, e.getKey(), a));
            }
            r.put("values", values);
            rows.add(r);
        }
        return rows;
    }

    public static List<String> actionLabels()
    {
        return new ArrayList<String>(ACTIONS.values());
    }

    public static List<String> roles()
    {
        if (RolePhase.isNew())
        {
            return Arrays.asList(StaffRole.CITY_LEAD, StaffRole.KEEPER, StaffRole.MANAGER, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT_SERVICE, StaffRole.MERCHANT);
        }
        return Arrays.asList(StaffRole.MERCHANT, StaffRole.ONLINE_STAFF, StaffRole.MERCHANT_SERVICE, StaffRole.CITY_LEAD, StaffRole.STAFF, StaffRole.KEEPER, StaffRole.MANAGER);
    }
}
