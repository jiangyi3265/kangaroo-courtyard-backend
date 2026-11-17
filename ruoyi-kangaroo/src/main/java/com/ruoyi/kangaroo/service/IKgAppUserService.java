package com.ruoyi.kangaroo.service;

import java.util.List;
import com.ruoyi.kangaroo.domain.KgAppUser;

/**
 * 小程序账号 Service
 *
 * 账号的生命周期归微信登录链路管理：后台只做查看、停用/启用和导出，
 * 不开放新增（凭空建号会产生没有 openid 的孤儿数据）和删除（会让报名记录失去归属）。
 */
public interface IKgAppUserService
{
    KgAppUser selectKgAppUserByUserId(Long userId);

    /** 存在性 + 可用性校验：不存在、已注销、已停用分别给出明确提示 */
    KgAppUser requireUser(Long userId);

    List<KgAppUser> selectKgAppUserList(KgAppUser kgAppUser);

    /** 变更账号状态（0启用 1停用），停用会把该账号的小程序会话全部踢下线 */
    int changeStatus(Long userId, String status);
}
