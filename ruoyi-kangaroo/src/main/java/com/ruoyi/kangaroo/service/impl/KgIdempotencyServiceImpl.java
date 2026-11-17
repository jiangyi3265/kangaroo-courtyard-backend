package com.ruoyi.kangaroo.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.mapper.KgIdempotencyMapper;
import com.ruoyi.kangaroo.service.IKgIdempotencyService;

@Service
public class KgIdempotencyServiceImpl implements IKgIdempotencyService
{
    @Autowired
    private KgIdempotencyMapper kgIdempotencyMapper;

    /**
     * 用独立事务插入：这样即使外层业务事务后续回滚，"这个键已被用过"的事实也不会一起回滚掉，
     * 反过来，业务失败时由调用方显式 release，语义清晰。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean tryAcquire(String idemKey, String bizType)
    {
        if (StringUtils.isEmpty(idemKey))
        {
            // 没给幂等键时不做幂等保护，由上层的唯一性业务规则兜底
            return true;
        }
        try
        {
            return kgIdempotencyMapper.insertKey(idemKey, bizType) > 0;
        }
        catch (DuplicateKeyException e)
        {
            return false;
        }
    }

    /**
     * 绑定业务主键，**刻意跟随外层业务事务**，不能用 REQUIRES_NEW。
     *
     * 用独立事务绑定会开出一个窗口：biz_id 先独立提交，业务行还没提交。
     * 窗口内到达的同键重试会 findBizId 拿到 appId、再 select 却读不到未提交的行，
     * 于是判定"键已失效"并把键删掉 —— 对一条其实成功了的报名报错，
     * 幂等保护恰好在它唯一要解决的场景（连点、弱网重发）下失效。
     *
     * 跟随外层事务后：biz_id 与报名行同时可见，窗口消失。
     * 外层回滚时 biz_id 一起回滚回 NULL，键仍被 tryAcquire 占着，
     * 由调用方的 release 或下面的孤儿键超时兜底。
     */
    @Override
    public void bindBizId(String idemKey, Long bizId)
    {
        if (StringUtils.isNotEmpty(idemKey) && bizId != null)
        {
            kgIdempotencyMapper.updateBizId(idemKey, bizId);
        }
    }

    @Override
    public Integer ageSeconds(String idemKey)
    {
        return StringUtils.isEmpty(idemKey) ? null : kgIdempotencyMapper.selectAgeSeconds(idemKey);
    }

    @Override
    public Long findBizId(String idemKey)
    {
        return StringUtils.isEmpty(idemKey) ? null : kgIdempotencyMapper.selectBizId(idemKey);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void release(String idemKey)
    {
        if (StringUtils.isNotEmpty(idemKey))
        {
            kgIdempotencyMapper.deleteByKey(idemKey);
        }
    }
}
