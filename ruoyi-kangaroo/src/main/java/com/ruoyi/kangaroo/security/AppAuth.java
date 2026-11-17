package com.ruoyi.kangaroo.security;

import java.lang.annotation.*;

/**
 * 小程序端接口鉴权注解。
 *
 * 标注在 Controller 类或方法上：
 * - 默认要求已登录的小程序账号（C 端用户）。
 * - staff = true 时额外要求该账号持有至少一条生效中的员工授权（kg_staff_grant.status = 0）。
 * - required = false 时允许匿名访问（如登录接口），但若携带了有效 token 仍会解析出账号。
 *
 * 注意：小程序账号（kg_app_user）与管理后台账号（sys_user）是两套完全独立的凭证体系，
 * 小程序 token 不能访问管理后台接口，管理后台 token 也不能访问 /app/** 接口。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AppAuth
{
    /** 是否必须登录 */
    boolean required() default true;

    /** 是否必须具备员工授权 */
    boolean staff() default false;

    /** 明确开放线上任务专员；开放的方法仍须按实际门店、任务类型和动作判权。 */
    /** 明确开放合作商家；业务服务仍按记录所属门店及动作校验。 */
    boolean merchant() default false;

    boolean onlineStaff() default false;

    /** 明确开放商家服务人（默认拒绝）：开放的方法仍须按城市、记录所属门店和动作判权。 */
    boolean merchantService() default false;

    /** 明确开放城市负责人（默认拒绝）：只开放只读接口和门店切换。 */
    boolean cityLead() default false;

    /** 角色切换之后才开放给城市负责人（RolePhase.isNew）：切换前他和今天一样进不来，legacy 阶段权限不变。 */
    boolean cityLeadAfterSwitch() default false;

    /**
     * 明确开放还没落到门店的城市授权（store_id 为空，例如本城还没有门店的城市负责人）：默认拒绝。
     * 只给不按门店办事、自己按 kg_staff_grant 现查范围的接口用（管理员任务）；其他角色过滤照旧。
     */
    boolean cityGrant() default false;
}
