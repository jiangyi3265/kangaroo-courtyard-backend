package com.ruoyi.kangaroo.ext.staff;

import com.ruoyi.kangaroo.enums.StaffRole;

/**
 * 角色切换的阶段：legacy 旧规则 / new 新规则。服务器包同时带两套规则，全项目只从 {@link #isNew()} 判断走哪套。
 *
 * legacy：manager 是老主理人（带资金权），staff 是老员工，所有人的权限和切换前逐格相同。
 * new：manager 是换工主理人（不碰钱、不管人），city_lead 是城市负责人（活动退款、取消收费活动），staff 退役：残留的 staff 行和认不出的角色不算有授权（usable）。
 *
 * 真值在 kg_role_switch.phase：后端启动时读（RoleSwitchSchema，读不到拒绝启动）；后台点切换时由 KgRoleSwitchService 在事务提交前改内存、
 * 失败恢复，所以时间差里只会收紧权限，不会放大。只有一个后端进程，不需要定时重读。
 */
public final class RolePhase
{
    public static final String LEGACY = "legacy";
    public static final String NEW = "new";

    private static volatile boolean newPhase = false;

    private RolePhase()
    {
    }

    public static boolean isNew()
    {
        return newPhase;
    }

    public static String code()
    {
        return newPhase ? NEW : LEGACY;
    }

    /** 这个角色编码现在算不算有效授权：切换后 staff 退役，认不出的角色也不再按员工算（只走矩阵挡不住不看矩阵的接口）。切换前一律算，行为不变 */
    public static boolean usable(String role)
    {
        return !newPhase || (StaffRole.exists(role) && !StaffRole.STAFF.equals(role));
    }

    /** 只有 RoleSwitchSchema、KgRoleSwitchService（和测试）调用；认不出的值拒绝，不能猜 */
    public static void set(String phase)
    {
        if (!LEGACY.equals(phase) && !NEW.equals(phase))
        {
            throw new IllegalStateException("unknown role phase: " + phase);
        }
        newPhase = NEW.equals(phase);
    }
}
