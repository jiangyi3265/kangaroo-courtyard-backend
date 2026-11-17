package com.ruoyi.kangaroo.service.impl;

import java.util.Date;
import java.util.List;
import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.dto.AppAccountVO;
import com.ruoyi.kangaroo.mapper.KgAppUserMapper;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.security.WxMiniAppClient;
import com.ruoyi.kangaroo.security.WxSession;
import com.ruoyi.kangaroo.service.IKgAppAuthService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 小程序端登录服务。
 *
 * 关键安全点：
 * 1. 员工身份只从 kg_staff_grant 读取，绝不接受客户端传入的角色、门店或 staffGrants。
 * 2. openid 由微信服务器返回，不接受客户端直接传 openid 登录。
 * 3. 登录态存 Redis（前缀 kangaroo:app_login_tokens:），与管理后台的 login_tokens: 完全隔离。
 */
@Service
public class KgAppAuthServiceImpl implements IKgAppAuthService
{
    @Autowired
    private WxMiniAppClient wxMiniAppClient;

    @Autowired
    private KgAppUserMapper kgAppUserMapper;

    @Autowired
    private IKgStaffGrantService kgStaffGrantService;

    @Autowired
    private AppTokenService appTokenService;

    @Autowired
    private KgPhoneBindingService phoneBinding;

    /** 只用来读手机号是否已验证（KgAppUser 实体没有映射 phone_verified_time） */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.KgJdbc jdbc;

    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.community.KgCommunityGuard contentGuard;

    /**
     * 登录请求里带来的昵称：与资料页同一条规则（1–16 字、内容合规）。不合规就当作没传（回退默认值）：
     * 原先原样入库，既绕过资料页的规则直接展示给其他人，超过列宽（64）还会让登录本身 500。
     */
    private String usableNickname(String raw)
    {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || v.length() > 16)
        {
            return null;
        }
        if (contentGuard != null)
        {
            try
            {
                if ("review".equals(contentGuard.check(v)))
                {
                    return null;
                }
            }
            catch (ServiceException e)
            {
                // 命中 block 级敏感词时 check 会直接抛：登录不能因为昵称失败，当作没传
                return null;
            }
        }
        return v;
    }

    /** 头像：平台上传的图片，或微信自己的头像域名；其余当作没传 */
    private static String usableAvatar(String raw)
    {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || v.length() > 255)
        {
            return null;
        }
        if (com.ruoyi.kangaroo.ext.KgJdbc.isPlatformImage(v))
        {
            return v;
        }
        return v.matches("https://(thirdwx|wx)\\.qlogo\\.cn/[A-Za-z0-9_./?=&%-]+") ? v : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KgAppUser loginByWxCode(String code, String nickname, String avatar, HttpServletRequest request)
    {
        WxSession session = wxMiniAppClient.code2session(code);
        nickname = usableNickname(nickname);
        avatar = usableAvatar(avatar);

        KgAppUser user = kgAppUserMapper.selectByOpenid(session.getOpenid());
        Date now = new Date();
        String ip = request == null ? "" : IpUtils.getIpAddr(request);

        if (user == null)
        {
            user = new KgAppUser();
            user.setOpenid(session.getOpenid());
            user.setUnionid(session.getUnionid());
            // 昵称/头像来自小程序端用户主动授权，允许为空；不接受空字符串覆盖成 null
            user.setNickname(StringUtils.isNotEmpty(nickname) ? nickname : "小院同学");
            user.setAvatar(StringUtils.isNotEmpty(avatar) ? avatar : "");
            user.setStatus("0");
            user.setRegisterTime(now);
            user.setLastLoginTime(now);
            user.setLastLoginIp(ip);
            user.setCreateBy("wx-login");
            kgAppUserMapper.insertKgAppUser(user);
        }
        else
        {
            if ("1".equals(user.getStatus()))
            {
                throw new ServiceException("账号已被停用，请联系客服", Integer.valueOf(HttpStatus.FORBIDDEN));
            }
            KgAppUser update = new KgAppUser();
            update.setUserId(user.getUserId());
            update.setLastLoginTime(now);
            update.setLastLoginIp(ip);
            if (StringUtils.isNotEmpty(nickname))
            {
                update.setNickname(nickname);
                user.setNickname(nickname);
            }
            if (StringUtils.isNotEmpty(avatar))
            {
                update.setAvatar(avatar);
                user.setAvatar(avatar);
            }
            if (StringUtils.isEmpty(user.getUnionid()) && StringUtils.isNotEmpty(session.getUnionid()))
            {
                update.setUnionid(session.getUnionid());
                user.setUnionid(session.getUnionid());
            }
            update.setUpdateBy("wx-login");
            kgAppUserMapper.updateKgAppUser(update);
        }
        return user;
    }

    @Override
    public String issueToken(KgAppUser user, HttpServletRequest request)
    {
        AppLoginUser loginUser = new AppLoginUser();
        loginUser.setUserId(user.getUserId());
        loginUser.setOpenid(user.getOpenid());
        loginUser.setUnionid(user.getUnionid());
        loginUser.setNickname(user.getNickname());
        loginUser.setAvatar(user.getAvatar());
        loginUser.setPhone(user.getPhone());
        // 员工授权只从数据库读，不接受任何客户端输入
        loginUser.setStaffGrants(kgStaffGrantService.loadActiveGrantViews(user.getUserId()));
        return appTokenService.createToken(loginUser, request);
    }

    @Override
    public AppAccountVO buildAccount(KgAppUser user, List<StaffGrantView> grants)
    {
        AppAccountVO vo = new AppAccountVO();
        vo.setId(String.valueOf(user.getUserId()));
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setCity(user.getCity());
        vo.setPhone(MaskUtils.phone(user.getPhone()));
        vo.setPhoneBound(Boolean.valueOf(StringUtils.isNotEmpty(user.getPhone())));
        if (jdbc != null && user.getUserId() != null)
        {
            vo.setPhoneVerified(Boolean.valueOf(jdbc.count("select count(*) from kg_app_user where user_id = :u and phone is not null and phone_verified_time is not null",
                    com.ruoyi.kangaroo.ext.KgJdbc.p("u", user.getUserId())) > 0));
        }
        vo.setStaffGrants(grants);
        return vo;
    }

    @Override
    public AppAccountVO currentAccount()
    {
        AppLoginUser loginUser = com.ruoyi.kangaroo.security.AppUserContext.require();
        KgAppUser user = kgAppUserMapper.selectKgAppUserByUserId(loginUser.getUserId());
        if (user == null || "2".equals(user.getDelFlag()))
        {
            throw new ServiceException("账号不存在", Integer.valueOf(HttpStatus.UNAUTHORIZED));
        }
        if ("1".equals(user.getStatus()))
        {
            throw new ServiceException("账号已被停用，请联系客服", Integer.valueOf(HttpStatus.FORBIDDEN));
        }
        // 每次拉取账号都重新读授权，保证后台撤销授权后小程序侧能及时失去员工身份
        List<StaffGrantView> grants = kgStaffGrantService.loadActiveGrantViews(user.getUserId());
        loginUser.setStaffGrants(grants);
        loginUser.setPhone(user.getPhone());
        appTokenService.refreshToken(loginUser);
        return buildAccount(user, grants);
    }

    @Override
    public void logout()
    {
        AppLoginUser loginUser = com.ruoyi.kangaroo.security.AppUserContext.get();
        if (loginUser != null)
        {
            appTokenService.deleteToken(loginUser.getToken());
        }
    }

    @Override
    public int bindPhone(Long appUserId, String phone)
    {
        return phoneBinding.bind(appUserId, phone, false);
    }

    @Override
    public int bindVerifiedPhone(Long appUserId, String phone)
    {
        return phoneBinding.bind(appUserId, phone, true);
    }
}
