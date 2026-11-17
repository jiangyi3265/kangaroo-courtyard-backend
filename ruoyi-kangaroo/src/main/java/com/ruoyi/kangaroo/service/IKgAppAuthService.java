package com.ruoyi.kangaroo.service;

import java.util.List;
import javax.servlet.http.HttpServletRequest;

import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.dto.AppAccountVO;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 小程序端登录服务
 */
public interface IKgAppAuthService
{
    /** 用微信 code 登录；账号不存在则创建 */
    KgAppUser loginByWxCode(String code, String nickname, String avatar, HttpServletRequest request);

    /** 为账号签发小程序令牌，并把当前生效的员工授权写入登录态 */
    String issueToken(KgAppUser user, HttpServletRequest request);

    /** 组装下发给小程序的账号对象（字段名对齐 experience.js 的既有用法） */
    AppAccountVO buildAccount(KgAppUser user, List<StaffGrantView> grants);

    /** 读取当前登录账号，并重新校验账号状态与员工授权 */
    AppAccountVO currentAccount();

    /** 登出，清除 Redis 中的登录态 */
    void logout();

    /** 绑定手机号 */
    int bindPhone(Long appUserId, String phone);

    /** 微信或短信已在服务端核验的手机号；接管占号与核验标记在同一事务内保存。 */
    int bindVerifiedPhone(Long appUserId, String phone);
}
