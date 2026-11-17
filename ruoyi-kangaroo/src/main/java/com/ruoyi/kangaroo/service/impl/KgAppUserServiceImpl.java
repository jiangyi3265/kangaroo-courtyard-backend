package com.ruoyi.kangaroo.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.mapper.KgAppUserMapper;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.service.IKgAppUserService;

@Service
public class KgAppUserServiceImpl implements IKgAppUserService
{
    /** 账号正常 */
    private static final String STATUS_NORMAL = "0";

    /** 账号停用 */
    private static final String STATUS_DISABLED = "1";

    @Autowired
    private KgAppUserMapper kgAppUserMapper;

    @Autowired
    private AppTokenService appTokenService;

    @Override
    public KgAppUser selectKgAppUserByUserId(Long userId)
    {
        return kgAppUserMapper.selectKgAppUserByUserId(userId);
    }

    @Override
    public KgAppUser requireUser(Long userId)
    {
        KgAppUser user = mustExist(userId);
        if (STATUS_DISABLED.equals(user.getStatus()))
        {
            // 停用的账号还能被其它模块当作报名人/接单人用的话，等于停用没生效
            throw new ServiceException("小程序账号已停用");
        }
        return user;
    }

    @Override
    public List<KgAppUser> selectKgAppUserList(KgAppUser kgAppUser)
    {
        return kgAppUserMapper.selectKgAppUserList(kgAppUser);
    }

    @Override
    public int changeStatus(Long userId, String status)
    {
        if (!STATUS_NORMAL.equals(status) && !STATUS_DISABLED.equals(status))
        {
            throw new ServiceException("账号状态只能是 0（启用）或 1（停用）");
        }
        // 这里不能用 requireUser：已停用的账号必须还能被重新启用
        KgAppUser exists = mustExist(userId);

        KgAppUser update = new KgAppUser();
        update.setUserId(exists.getUserId());
        update.setStatus(status);
        update.setUpdateBy(SecurityUtils.getUsername());
        int rows = kgAppUserMapper.updateKgAppUser(update);
        if (rows > 0 && STATUS_DISABLED.equals(status))
        {
            // 只改库不踢线的话，已经登录的小程序会话在 token 自然过期前仍能继续报名下单，
            // 停用要立刻生效就必须清掉这个账号的全部会话
            appTokenService.kickOut(exists.getUserId());
        }
        return rows;
    }

    /**
     * 取一个仍然存在（未逻辑删除）的账号。
     * 常规查询一律带 del_flag = '0'，查不到时再补一次计数来区分"从未注册"和"已注销"。
     */
    private KgAppUser mustExist(Long userId)
    {
        if (userId == null)
        {
            throw new ServiceException("小程序账号ID不能为空");
        }
        KgAppUser user = kgAppUserMapper.selectKgAppUserByUserId(userId);
        if (user == null)
        {
            if (kgAppUserMapper.countDeletedByUserId(userId) > 0)
            {
                throw new ServiceException("小程序账号已注销");
            }
            throw new ServiceException("小程序账号不存在");
        }
        return user;
    }
}
