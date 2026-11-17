package com.ruoyi.kangaroo.enums;

/** 门店员工角色 */
public final class StaffRole
{
    /** 换工主理人：招募、核实、门店日常（切换前是带资金权的老主理人，见 RolePhase） */
    public static final String MANAGER = "manager";
    /** 小院主理人 */
    public static final String KEEPER = "keeper";
    /** 老员工：切换后退役，新权限矩阵里没有这一行，等于什么都做不了（授权数据切换时改成换工主理人） */
    public static final String STAFF = "staff";
    /** 仅处理授权门店的线上任务 */
    public static final String ONLINE_STAFF = "online_staff";

    /** 平台授予的合作商家，仅经营授权门店。 */
    public static final String MERCHANT = "merchant";

    /** 商家服务人：代运营、抖音卖房、线上任务派发，按授权门店所在城市接单，仅平台可授权。 */
    public static final String MERCHANT_SERVICE = "merchant_service";

    /** 城市负责人：收通知、只读看本城需求和各店概况；切换后多活动退款审批和取消收费活动（只限有授权的门店）。仅平台可授权。 */
    public static final String CITY_LEAD = "city_lead";

    public static boolean exists(String code)
    {
        return MANAGER.equals(code) || KEEPER.equals(code) || STAFF.equals(code) || ONLINE_STAFF.equals(code) || MERCHANT.equals(code)
                || MERCHANT_SERVICE.equals(code) || CITY_LEAD.equals(code);
    }

    /** 这种角色在门店上只碰线上任务：报名列表强制线上、线下岗位的报名和通知一律不给看 */
    public static boolean onlineOnly(String code)
    {
        return ONLINE_STAFF.equals(code) || MERCHANT_SERVICE.equals(code);
    }

    /** 只有平台后台能授予、能撤销的角色；门店里的换工主理人动不了 */
    public static boolean platformOnly(String code)
    {
        return MANAGER.equals(code) || MERCHANT.equals(code) || MERCHANT_SERVICE.equals(code) || CITY_LEAD.equals(code);
    }

    public static String label(String code)
    {
        if (MERCHANT.equals(code)) { return "合作商家"; }
        if (MERCHANT_SERVICE.equals(code)) { return "商家服务人"; }
        if (CITY_LEAD.equals(code)) { return "城市负责人"; }
        if (MANAGER.equals(code)) { return "换工主理人"; }
        if (KEEPER.equals(code)) { return "小院主理人"; }
        if (ONLINE_STAFF.equals(code)) { return "线上任务专员"; }
        if (STAFF.equals(code)) { return "员工"; }
        return "员工";
    }

    private StaffRole()
    {
    }
}
