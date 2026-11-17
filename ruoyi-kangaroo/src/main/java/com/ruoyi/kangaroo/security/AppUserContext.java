package com.ruoyi.kangaroo.security;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 当前请求的小程序登录账号上下文。由 AppAuthInterceptor 写入并在请求结束时清理。
 *
 * 业务代码一律通过 getUserId() 获取用户身份，禁止信任请求参数中传入的 userId。
 */
public class AppUserContext
{
    private static final ThreadLocal<AppLoginUser> HOLDER = new ThreadLocal<AppLoginUser>();

    private static final ThreadLocal<java.util.List<Long>> MESSAGE_STORES = new ThreadLocal<>();
    private static final ThreadLocal<String> MESSAGE_ROLE = new ThreadLocal<>();
    public static java.util.List<Long> messageStores(){return MESSAGE_STORES.get();}
    public static String messageRole(){return MESSAGE_ROLE.get();}
    public static void messageScope(java.util.List<Long> stores,String role){MESSAGE_STORES.set(stores);MESSAGE_ROLE.set(role);}

    /** Mediation notices use the freshly loaded grants and the currently selected identity. */
    public static java.util.List<Long> messageMediationStores(String role)
    {
        java.util.Set<Long> ids=new java.util.LinkedHashSet<Long>();AppLoginUser user=get();String selected=messageRole();
        if(user==null||(selected!=null&&!role.equals(selected)))return new java.util.ArrayList<Long>();
        for(StaffGrantView grant:user.getStaffGrants()){
            if(!Boolean.TRUE.equals(grant.getActive()))continue;
            if(selected==null&&!role.equals(grant.getRoleCode()))return new java.util.ArrayList<Long>();
            if(role.equals(grant.getRoleCode())&&grant.getStoreId()!=null&&(messageStores()==null||messageStores().contains(grant.getStoreId())))ids.add(grant.getStoreId());
        }
        return new java.util.ArrayList<Long>(ids);
    }

    /** 消息中的旅居业务按当前身份和本次请求刷新后的授权取交集，不能借账号的另一身份读订单。 */
    public static java.util.List<Long> messageStayStores()
    {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<Long>();
        AppLoginUser user = get();
        String selected = messageRole();
        if (user == null || "user".equals(selected)) return new java.util.ArrayList<Long>();
        for (StaffGrantView grant : user.getStaffGrants())
        {
            if (!Boolean.TRUE.equals(grant.getActive())) continue;
            boolean allowed = com.ruoyi.kangaroo.ext.staff.RolePhase.usable(grant.getRoleCode())
                    && com.ruoyi.kangaroo.ext.staff.StaffPerms.allows(grant.getRoleCode(), "rooms", "view");
            // 老客户端没有选中身份：混合账号按最小权限，不将另一角色的旅居通知泄露给换工身份。
            if (selected == null && !allowed) return new java.util.ArrayList<Long>();
            if (allowed && (selected == null || selected.equals(grant.getRoleCode())) && grant.getStoreId() != null
                    && (messageStores() == null || messageStores().contains(grant.getStoreId()))) ids.add(grant.getStoreId());
        }
        return new java.util.ArrayList<Long>(ids);
    }

    /** 旧退款异常副本没有记录业务类型，只允许城市负责人本人在该身份查阅。 */
    public static boolean messageCityLead()
    {
        AppLoginUser user = get();
        if (user == null || !com.ruoyi.kangaroo.ext.staff.RolePhase.isNew()
                || (messageRole() != null && !"city_lead".equals(messageRole()))) return false;
        boolean found = false;
        for (StaffGrantView grant : user.getStaffGrants())
        {
            if (!Boolean.TRUE.equals(grant.getActive())) continue;
            if (messageRole() == null && !"city_lead".equals(grant.getRoleCode())) return false;
            if ("city_lead".equals(grant.getRoleCode()) && (messageStores() == null
                    || messageStores().contains(grant.getStoreId()))) found = true;
        }
        return found;
    }
    public static void set(AppLoginUser user)
    {
        HOLDER.set(user);
    }

    public static AppLoginUser get()
    {
        return HOLDER.get();
    }

    private static final ThreadLocal<String> CHAT_MODE = new ThreadLocal<>();
    public static void chatMode(String mode) { CHAT_MODE.set(mode); }
    public static String chatMode() { return CHAT_MODE.get(); }

    public static void remove()
    {
        HOLDER.remove();
        MESSAGE_STORES.remove(); MESSAGE_ROLE.remove(); CHAT_MODE.remove();
    }

    /** 获取当前登录账号，未登录直接抛 401 */
    public static AppLoginUser require()
    {
        AppLoginUser user = HOLDER.get();
        if (user == null)
        {
            throw new ServiceException("未登录或登录已失效", HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    /** 当前登录账号 ID；未登录返回 null（给 @AppAuth(required = false) 的游客可浏览接口用，不能在那里调 getUserId()） */
    public static Long currentUserId()
    {
        AppLoginUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    /** 获取当前登录账号 ID，未登录直接抛 401 */
    public static Long getUserId()
    {
        return require().getUserId();
    }

    /** 要求具备员工授权，否则抛 403 */
    public static AppLoginUser requireStaff()
    {
        AppLoginUser user = require();
        if (!user.hasStaffAccess())
        {
            throw new ServiceException("当前账号没有员工授权", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    /** 要求对指定门店具备员工授权，否则抛 403 */
    public static AppLoginUser requireStore(Long storeId)
    {
        AppLoginUser user = requireStaff();
        if (!user.hasStoreAccess(storeId))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
