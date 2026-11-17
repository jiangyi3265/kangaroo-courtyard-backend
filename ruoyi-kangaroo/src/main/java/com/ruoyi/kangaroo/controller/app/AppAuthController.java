package com.ruoyi.kangaroo.controller.app;

import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.utils.BuildFingerprint;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.dto.AppAccountVO;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.WxMiniAppClient;
import com.ruoyi.kangaroo.service.IKgAppAuthService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 小程序端登录接口。
 *
 * 路径前缀 /app 下的接口一律由 AppAuthInterceptor 用 X-App-Token 鉴权，
 * 与管理后台的 Authorization 令牌互不相通。
 */
@RestController
@RequestMapping("/app/auth")
public class AppAuthController
{
    @Autowired
    private IKgAppAuthService appAuthService;

    @Autowired
    private IKgStaffGrantService kgStaffGrantService;

    @Autowired
    private WxMiniAppClient wxMiniAppClient;

    @Autowired
    private AppRateLimiter rateLimiter;

    @Autowired private com.ruoyi.kangaroo.ext.user.KgLegalDocuments legalDocuments;

    /**
     * 微信登录。
     *
     * 返回体中的 account 直接交给小程序 acceptAuthenticatedAccount(account)，
     * 其中 staffGrants 由服务端根据 kg_staff_grant 判定，前端不得自行推断员工身份。
     */
    @AppAuth(required = false)
    @PostMapping("/wx-login")
    public AjaxResult wxLogin(@RequestBody Map<String, String> body, HttpServletRequest request)
    {
        legalDocuments.requirePublished();
        // 这个接口是匿名的，而且会**同步阻塞**去外调微信服务器换 openid。
        // Tomcat 配了 800 线程，数据库连接池只有 20 —— 持续打它就能把线程占满，
        // 连带管理后台一起不可用。匿名接口唯一能用的限流主体只有 IP。
        // 120 次 / 分钟 / IP。这个数不是拍脑袋：
        // 真正要防的是"每次调用都同步阻塞等微信服务器"把 Tomcat 的 800 个线程耗光。
        // 按 5 秒超时算，2 次/秒持续打过来最多占住约 10 个线程，够不成威胁。
        // 反过来不能定太小：国内大量用户走运营商 NAT，一个出口 IP 后面可能几百人，
        // 30 次/分钟会把正常用户挡在门外 —— 限流挡住真实用户比不限流更糟。
        rateLimiter.checkByIp("wx-login", 120, 60,
                "登录过于频繁，请稍后再试");

        String code = body == null ? null : body.get("code");
        String nickname = body == null ? null : body.get("nickname");
        String avatar = body == null ? null : body.get("avatar");

        KgAppUser user = appAuthService.loginByWxCode(code, nickname, avatar, request);
        String token = appAuthService.issueToken(user, request);
        AppAccountVO account = appAuthService.buildAccount(user,
                kgStaffGrantService.loadActiveGrantViews(user.getUserId()));

        AjaxResult ajax = AjaxResult.success();
        ajax.put("token", token);
        ajax.put("account", account);
        if (wxMiniAppClient.isMock())
        {
            // 让调用方与测试能明确区分"真实微信身份"与"本地联调身份"
            ajax.put("mockLogin", Boolean.TRUE);
        }
        return ajax;
    }

    /** 读取当前账号（小程序冷启动用本地 token 恢复登录态时调用） */
    @AppAuth
    @GetMapping("/account")
    public AjaxResult account()
    {
        return AjaxResult.success(appAuthService.currentAccount());
    }

    /** 登出 */
    @AppAuth
    @PostMapping("/logout")
    public AjaxResult logout()
    {
        appAuthService.logout();
        return AjaxResult.success();
    }

    /**
     * 旧版「直接填号码绑定」（不验证）已停用：小程序早就不调它了，留着谁都能把别人的号码占到自己账号上，
     * 后台按手机号找人授权时会把门店角色给到占号的人。现在只能走微信手机号验证（/phone-verify）或短信验证（/app/me/phone/bind）。
     */
    @AppAuth
    @PostMapping("/bind-phone")
    public AjaxResult bindPhone(@RequestBody(required = false) Map<String, String> body)
    {
        return AjaxResult.error(410, "请在「我的」里用微信授权验证手机号");
    }

    /**
     * 微信手机号快速验证：小程序 open-type="getPhoneNumber" 回调里的 code 换手机号并绑定，同时记下"已经微信核验"。
     * 社区发布找搭子、发起活动、公开行程要求核验过的手机号；手机号本身不会展示给其他用户。
     */
    @AppAuth
    @PostMapping("/phone-verify")
    public AjaxResult phoneVerify(@RequestBody Map<String, String> body)
    {
        legalDocuments.requirePublished();
        Long me = AppUserContext.getUserId();
        rateLimiter.check("phone-verify:user", String.valueOf(me), 5, 600, "验证太频繁了，请 10 分钟后再试");
        String phone = wxMiniAppClient.getPhoneNumber(body == null ? null : body.get("code"));
        appAuthService.bindVerifiedPhone(me, phone);
        return AjaxResult.success(appAuthService.currentAccount());
    }

    /** 探活：不需要登录，用于小程序端检查后端可达性与微信登录是否已配置 */
    @AppAuth(required = false)
    @GetMapping("/ping")
    public AjaxResult ping()
    {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("ok", Boolean.TRUE);
        data.put("wxMock", Boolean.valueOf(wxMiniAppClient.isMock()));
        // 正在运行的这份代码的指纹。验收脚本拿它和刚构建出来的 jar 对一次，
        // 避免"构建了新代码、测的却是还开着的旧进程"。
        data.put("build", BuildFingerprint.current());
        // 角色切换的阶段：发布后、切换后只靠这一项确认线上走的是哪套规则
        data.put("rolePhase", com.ruoyi.kangaroo.ext.staff.RolePhase.code());
        return AjaxResult.success(data);
    }
}
