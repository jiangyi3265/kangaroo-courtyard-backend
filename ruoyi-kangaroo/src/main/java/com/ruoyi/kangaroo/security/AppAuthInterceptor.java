package com.ruoyi.kangaroo.security;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 小程序端接口鉴权拦截器，作用于 /app/**。
 *
 * 默认拒绝：只要处理器方法或其所在类没有显式标注 @AppAuth(required = false)，
 * 就必须携带有效的小程序令牌。即使某个接口忘记加注解，也会走"必须登录"分支，
 * 不存在因漏标注而默认放行的情况。
 */
@Component
public class AppAuthInterceptor implements HandlerInterceptor
{
    @Autowired
    private AppTokenService appTokenService;

    @Autowired
    private AppIdentityLoader identities;

    /** 切换页看「谁用什么版本」；只记录，不参与下面任何判断，失败也不影响请求 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.KgStaffClientSeen clientSeen;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
    {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()))
        {
            return true;
        }
        if (!(handler instanceof HandlerMethod))
        {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        AppAuth appAuth = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), AppAuth.class);
        if (appAuth == null)
        {
            appAuth = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), AppAuth.class);
        }

        boolean required = appAuth == null || appAuth.required();
        boolean staffRequired = appAuth != null && appAuth.staff();

        // 先把线程上可能残留的身份清掉。
        // 正常情况下 afterCompletion 会清，但下面这些校验是**抛异常**而不是 return false，
        // 而 Spring 的 HandlerExecutionChain 只在 preHandle 正常返回 true 之后才记
        // interceptorIndex —— preHandle 抛出时 triggerAfterCompletion 从 -1 开始循环，
        // afterCompletion 一次也不会被调用。于是身份留在了这个 Tomcat 线程上，
        // 下一个落到同一线程的匿名请求（/app/job/** 都是 required=false）会读到上一个人。
        AppUserContext.remove();

        AppLoginUser loginUser = appTokenService.getLoginUser(request);
        try
        {
            if (loginUser != null)
            {
                appTokenService.verifyToken(loginUser);
                identities.reload(loginUser);
                if (clientSeen != null)
                {
                    clientSeen.record(loginUser, request.getHeader(com.ruoyi.kangaroo.ext.staff.KgStaffClientSeen.HEADER));
                }
            }
            if (loginUser == null && required)
            {
                throw new ServiceException("未登录或登录已失效", HttpStatus.UNAUTHORIZED);
            }
            // 城市预授权是可登录的筹备身份，不是“所有门店”。只有真实门店授权才能进入业务接口。
            // /stores 仅用于展示自己的授权范围，须保留城市身份用于切换和刷新。
            // 标了 cityGrant 的接口（管理员任务）也保留：本城还没有门店的城市负责人能被派任务，要能处理掉。
            if (loginUser != null && (staffRequired || request.getServletPath().startsWith("/app/staff/")
                    || request.getServletPath().startsWith("/app/manage/"))
                    && !"/app/staff/stores".equals(request.getServletPath())
                    && (appAuth == null || !appAuth.cityGrant())
                    && loginUser.getStaffGrants().stream().anyMatch(g -> g.getStoreId() == null && g.getCityId() != null))
            {
                AppLoginUser scoped = new AppLoginUser();
                org.springframework.beans.BeanUtils.copyProperties(loginUser, scoped);
                java.util.List<StaffGrantView> grants = new java.util.ArrayList<StaffGrantView>();
                for (StaffGrantView g : loginUser.getStaffGrants())
                    if (g.getStoreId() != null) grants.add(g);
                scoped.setStaffGrants(grants);
                boolean merchantOnly = loginUser.getStaffGrants().stream().allMatch(g -> com.ruoyi.kangaroo.enums.StaffRole.MERCHANT.equals(g.getRoleCode()));
                loginUser = scoped;
                if (!loginUser.hasStaffAccess())
                    throw new ServiceException(merchantOnly ? "门店还没绑定，请联系平台关联你的门店后再使用这项功能"
                            : "本城还没有开通门店，门店开通后才能使用这项功能（管理员任务可以照常处理）", HttpStatus.FORBIDDEN);
            }
            // 普通员工接口默认不接受专员授权；复制请求身份，不能污染缓存中的完整授权。
            // 混合角色账号仍可访问自己在其他门店的常规权限，不能借用它跨店扩权。
            if (loginUser != null && loginUser.getStaffGrants().stream().anyMatch(g -> com.ruoyi.kangaroo.enums.StaffRole.ONLINE_STAFF.equals(g.getRoleCode())) && (staffRequired || request.getServletPath().startsWith("/app/staff/")
                    || request.getServletPath().startsWith("/app/manage/")) && (appAuth == null || !appAuth.onlineStaff()))
            {
                AppLoginUser scoped = new AppLoginUser();
                org.springframework.beans.BeanUtils.copyProperties(loginUser, scoped);
                java.util.List<StaffGrantView> grants = new java.util.ArrayList<StaffGrantView>();
                for (StaffGrantView g : loginUser.getStaffGrants())
                    if (!com.ruoyi.kangaroo.enums.StaffRole.ONLINE_STAFF.equals(g.getRoleCode())) grants.add(g);
                scoped.setStaffGrants(grants);
                loginUser = scoped;
                if (!loginUser.hasStaffAccess()) throw new ServiceException("线上任务专员仅可处理线上任务", HttpStatus.FORBIDDEN);
            }
            if (loginUser != null && (staffRequired || request.getServletPath().startsWith("/app/staff/")
                    || request.getServletPath().startsWith("/app/manage/")) && (appAuth == null || !appAuth.merchant()))
            {
                AppLoginUser scoped = MerchantScope.withoutMerchantGrants(loginUser);
                if (scoped != loginUser && !scoped.hasStaffAccess())
                    throw new ServiceException("合作商家仅可处理授权门店的招募与入住业务", HttpStatus.FORBIDDEN);
                loginUser = scoped;
            }
            // 商家服务人、城市负责人同样默认拒绝：只有明确标了 merchantService / cityLead 的接口才留下这两种授权。
            // 没标注的接口（appAuth 为空也算）一律去掉，有别的角色的账号只能用别的角色那几家门店。
            if (loginUser != null && (staffRequired || request.getServletPath().startsWith("/app/staff/")
                    || request.getServletPath().startsWith("/app/manage/")))
            {
                AppLoginUser scoped = loginUser;
                if (appAuth == null || !appAuth.merchantService())
                    scoped = MerchantScope.withoutRole(scoped, com.ruoyi.kangaroo.enums.StaffRole.MERCHANT_SERVICE);
                // 切换后城市负责人有全部二级角色的权限（2026-11-01）：接口上不再去掉他的授权，能不能做按权限矩阵和各接口自己的角色判断（商家专用的接口只认合作商家）
                boolean cityLeadOpen = com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() || (appAuth != null && appAuth.cityLead());
                if (!cityLeadOpen)
                    scoped = MerchantScope.withoutRole(scoped, com.ruoyi.kangaroo.enums.StaffRole.CITY_LEAD);
                if (scoped != loginUser && !scoped.hasStaffAccess())
                    throw new ServiceException("当前角色不能使用这项功能", HttpStatus.FORBIDDEN);
                loginUser = scoped;
            }
            // 旅居和线上任务按功能权限过滤授权；混合角色不能借另一家门店的权限访问。
            if (loginUser != null && (request.getServletPath().startsWith("/app/staff/stay/") || request.getServletPath().startsWith("/app/staff/online/")))
            {
                AppLoginUser scoped = new AppLoginUser();
                org.springframework.beans.BeanUtils.copyProperties(loginUser, scoped);
                java.util.List<StaffGrantView> grants = new java.util.ArrayList<StaffGrantView>();
                for (StaffGrantView g : loginUser.getStaffGrants())
                    if (com.ruoyi.kangaroo.ext.staff.StaffPerms.allows(g.getRoleCode(), request.getServletPath().startsWith("/app/staff/stay/") ? "rooms" : "online", "view")) grants.add(g);
                scoped.setStaffGrants(grants);
                loginUser = scoped;
                if (!loginUser.hasStaffAccess()) throw new ServiceException(request.getServletPath().startsWith("/app/staff/stay/") ? "当前角色没有旅居订单、房源和二消管理权限" : "当前角色没有线上任务管理权限", HttpStatus.FORBIDDEN);
            }
            if (staffRequired)
            {
                if (loginUser == null || !loginUser.hasStaffAccess())
                {
                    throw new ServiceException("当前账号没有员工授权", HttpStatus.FORBIDDEN);
                }
            }
        }
        catch (RuntimeException e)
        {
            AppUserContext.remove();
            throw e;
        }

        // 全部校验通过之后才写入线程上下文：写在前面的话，
        // 上面任何一个抛出点都会把身份留在线程上带不走。
        if (loginUser != null)
        {
            AppUserContext.set(loginUser);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
    {
        AppUserContext.remove();
    }
}
