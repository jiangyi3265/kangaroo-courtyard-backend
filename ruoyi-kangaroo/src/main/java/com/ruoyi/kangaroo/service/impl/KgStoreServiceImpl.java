package com.ruoyi.kangaroo.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.domain.StoreImages;
import com.ruoyi.kangaroo.mapper.KgApplicationMapper;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.service.IKgStoreService;
import com.ruoyi.kangaroo.util.KgValidate;

@Service
public class KgStoreServiceImpl implements IKgStoreService
{
    @Autowired
    private KgStoreMapper kgStoreMapper;

    @Autowired
    private KgJobMapper kgJobMapper;

    @Autowired
    private KgApplicationMapper kgApplicationMapper;

    @Autowired
    private KgJdbc db;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.user.KgCityService cities;

    /**
     * 门店城市是后台自由填写的文本，写成别名（平潭岛、厦门市）或区县后，用户按字典里的城市名（平潭、厦门）
     * 选城市就看不到这家店的房源和活动。保存时统一成字典名，与查询时的 KgCityService.businessCity 同一口径。
     */
    private void normalizeCity(KgStore store)
    {
        if (cities != null && StringUtils.isNotEmpty(store.getCity()))
        {
            store.setCity(cities.businessCity(store.getCity()));
        }
    }

    @Autowired
    private AppTokenService appTokenService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private IKgStaffGrantService grantService;

    /** 这些门店上仍生效授权的员工账号（不看门店状态：停用 / 删除之后也要能查到，才能刷新他们的会话） */
    private List<Long> grantedUsers(Long[] storeIds)
    {
        List<Long> out = new java.util.ArrayList<Long>();
        for (Long sid : storeIds)
        {
            for (java.util.Map<String, Object> r : db.list("select distinct app_user_id from kg_staff_grant where store_id = :s and status = '0' and del_flag = '0'",
                    KgJdbc.p("s", sid)))
            {
                Long uid = KgJdbc.asLong(r.get("appUserId"));
                if (uid != null && !out.contains(uid))
                {
                    out.add(uid);
                }
            }
        }
        return out;
    }

    /** 门店停用 / 恢复 / 删除后立刻刷新该店员工的小程序会话：员工端的授权都缓存在会话里，不刷新的话已停用的门店权限会随续期一直有效 */
    private void refreshStaffSessions(List<Long> users)
    {
        for (Long uid : users)
        {
            appTokenService.refreshGrants(uid, grantService.loadActiveGrantViews(uid));
        }
    }

    @Override
    public KgStore selectKgStoreByStoreId(Long storeId)
    {
        return kgStoreMapper.selectKgStoreByStoreId(storeId);
    }

    @Override
    public KgStore requireStore(Long storeId)
    {
        if (storeId == null)
        {
            throw new ServiceException("门店ID不能为空");
        }
        KgStore store = kgStoreMapper.selectKgStoreByStoreId(storeId);
        if (store == null)
        {
            throw new ServiceException("门店不存在或已删除");
        }
        return store;
    }

    @Override
    public List<KgStore> selectKgStoreList(KgStore kgStore)
    {
        return kgStoreMapper.selectKgStoreList(kgStore);
    }

    @Override
    public int insertKgStore(KgStore kgStore)
    {
        StoreImages.apply(kgStore, null);
        normalizeCity(kgStore);
        validate(kgStore, null);
        kgStore.setCreateBy(SecurityUtils.getUsername());
        // 评分与评价数由评价聚合产生，不接受外部写入
        kgStore.setRating(null);
        kgStore.setReviewCount(null);
        if (StringUtils.isEmpty(kgStore.getStatus()))
        {
            kgStore.setStatus("0");
        }
        // 认证状态只能通过认证接口变更
        kgStore.setSafeCert("1");
        kgStore.setCertTime(null);
        kgStore.setCertBy(null);
        try
        {
            return kgStoreMapper.insertKgStore(kgStore);
        }
        catch (DuplicateKeyException e)
        {
            // uk_store_name_city 兜住并发：上面的 countByNameAndCity 只负责给出友好提示
            throw new ServiceException("同城已存在同名门店：" + kgStore.getStoreName());
        }
    }

    @Override
    public int updateKgStore(KgStore kgStore)
    {
        KgStore current = requireStore(kgStore.getStoreId());
        StoreImages.apply(kgStore, current);
        normalizeCity(kgStore);
        validateUpdate(kgStore, current);
        kgStore.setUpdateBy(SecurityUtils.getUsername());
        // 认证状态、评分、评价数不允许通过通用编辑接口修改
        kgStore.setSafeCert(null);
        kgStore.setCertTime(null);
        kgStore.setCertBy(null);
        kgStore.setRating(null);
        kgStore.setReviewCount(null);
        boolean statusChanged = kgStore.getStatus() != null && !kgStore.getStatus().equals(current.getStatus());
        try
        {
            int n = kgStoreMapper.updateKgStore(kgStore);
            if (statusChanged)
            {
                refreshStaffSessions(grantedUsers(new Long[] { kgStore.getStoreId() }));
            }
            return n;
        }
        catch (DuplicateKeyException e)
        {
            throw new ServiceException("同城已存在同名门店，无法改成该名称");
        }
    }

    @Override
    public int deleteKgStoreByStoreIds(Long[] storeIds)
    {
        if (storeIds == null || storeIds.length == 0)
        {
            return 0;
        }
        for (Long storeId : storeIds)
        {
            requireStore(storeId);
            if (kgJobMapper.countByStoreId(storeId) > 0)
            {
                throw new ServiceException("门店下仍有岗位，不能删除");
            }
            if (kgApplicationMapper.countByStoreId(storeId) > 0)
            {
                throw new ServiceException("门店下仍有报名记录，不能删除");
            }
            // 旅居 / 服务 / 活动的在途订单：删除后员工随即失去授权，这些订单就没有人能处理了（2026-09-28 复核发现）
            long open = db.count("select (select count(*) from kg_stay_order where store_id = :s and del_flag = '0' and status in ('pending_payment','confirmed','checked_in','refund_pending','refunding'))"
                    + " + (select count(*) from kg_shop_order where store_id = :s and del_flag = '0' and status in ('pending_payment','unused','refund_pending'))"
                    + " + (select count(*) from kg_activity where store_id = :s and del_flag = '0' and status in ('open','paused','closed'))", KgJdbc.p("s", storeId));
            if (open > 0)
            {
                throw new ServiceException("门店下仍有未完成的旅居订单、服务订单或进行中的活动，不能删除；请先处理完或改为停用");
            }
        }
        List<Long> users = grantedUsers(storeIds);
        int n = kgStoreMapper.deleteKgStoreByStoreIds(storeIds, SecurityUtils.getUsername());
        refreshStaffSessions(users);
        return n;
    }

    @Override
    public int certify(Long storeId, boolean certified)
    {
        KgStore store = requireStore(storeId);
        KgStore update = new KgStore();
        update.setStoreId(store.getStoreId());
        update.setSafeCert(certified ? "0" : "1");
        update.setCertTime(certified ? new Date() : null);
        update.setCertBy(certified ? SecurityUtils.getUsername() : null);
        update.setUpdateBy(SecurityUtils.getUsername());
        if (!certified)
        {
            // cert_time/cert_by 的 <if> 为 null 时不会置空，这里显式清理
            kgStoreMapper.clearCert(storeId);
        }
        int n = kgStoreMapper.updateKgStore(update);
        // 2026-10-18 岗位详情改版（V54）：「实地探访」就是安心商家认证，采访方式跟着改 —— 认证 = 实地探访；
        // 撤销认证时原来是实地探访的改成未采访（线上采访不受影响，本来就不是认证的店）。后台「门店资料」改采访方式也走这里
        db.update("update kg_store set interview_mode = case when :c = 1 then 'onsite' when interview_mode = 'onsite' then '' else interview_mode end "
                + "where store_id = :id", KgJdbc.p("c", certified ? 1 : 0, "id", store.getStoreId()));
        return n;
    }

    /**
     * 更新校验。
     *
     * 与新增不同，更新走的是 MyBatis 的动态 SET —— 没传的字段不会被改。
     * 所以这里必须区分"没传"和"传了空值"：
     *   没传   -> 该字段保持原值，用原值参与唯一性校验，不能因为没传就报"名称不能为空"
     *   传了空 -> 是把字段清空的意图，对必填字段应当拒绝
     * 早期实现直接复用新增的校验，导致"只改状态"这类局部更新被必填校验拦下来。
     */
    private void validateUpdate(KgStore store, KgStore current)
    {
        if (store.getStoreName() != null && store.getStoreName().trim().length() == 0)
        {
            throw new ServiceException("门店名称不能为空");
        }
        if (store.getCity() != null && store.getCity().trim().length() == 0)
        {
            throw new ServiceException("城市不能为空");
        }
        if (StringUtils.isNotEmpty(store.getContactPhone())
                && !store.getContactPhone().matches("^1\\d{10}$"))
        {
            throw new ServiceException("联系电话须为 11 位手机号");
        }
        // 长度校验放在唯一性查库之前：超长的名称拿去 count 没有意义，先拦下来还能省一次 SQL
        validateLength(store);
        String name = store.getStoreName() != null ? store.getStoreName() : current.getStoreName();
        String city = store.getCity() != null ? store.getCity() : current.getCity();
        if (kgStoreMapper.countByNameAndCity(name, city, current.getStoreId()) > 0)
        {
            throw new ServiceException("同城已存在同名门店：" + name);
        }
    }

    private void validate(KgStore store, Long excludeId)
    {
        if (StringUtils.isEmpty(store.getStoreName()))
        {
            throw new ServiceException("门店名称不能为空");
        }
        if (StringUtils.isEmpty(store.getCity()))
        {
            throw new ServiceException("城市不能为空");
        }
        if (StringUtils.isNotEmpty(store.getContactPhone())
                && !store.getContactPhone().matches("^1\\d{10}$"))
        {
            throw new ServiceException("联系电话须为 11 位手机号");
        }
        validateLength(store);
        if (kgStoreMapper.countByNameAndCity(store.getStoreName(), store.getCity(), excludeId) > 0)
        {
            throw new ServiceException("同城已存在同名门店：" + store.getStoreName());
        }
    }

    /**
     * 文本长度校验（新增与更新共用）。
     *
     * 为什么能共用、而必填校验不能：长度只关心"传上来的值有多长"，
     * 没传（null）就跳过，正好是局部更新想要的语义；必填校验则必须区分
     * "没传"和"传了空串"，所以那部分仍留在各自的方法里。
     *
     * 上限取自 sql/kangaroo/V1__kangaroo_core.sql 的 kg_store 列定义，
     * 与建表语句一一对应；将来改列宽时这里要跟着改。
     * contact_phone 不在列表里：上面的 11 位手机号正则比 varchar(20) 更严。
     * cover 由有序图片首图生成，仍兼容旧后台提交的图片地址。
     */
    private void validateLength(KgStore store)
    {
        KgValidate.maxLength(store.getStoreName(), 64, "门店名称");
        KgValidate.maxLength(store.getCity(), 32, "城市");
        KgValidate.maxLength(store.getAddress(), 255, "详细地址");
        KgValidate.maxLength(store.getCover(), 255, "封面图地址");
        KgValidate.maxLength(store.getIntro(), 1000, "门店简介");
        KgValidate.maxLength(store.getRemark(), 500, "备注");
        validateOpAndGeo(store);
    }

    /**
     * 运营类型与经纬度：op_type 决定收费活动是否由平台在线代收（只有直营才代收），经纬度决定「附近」排序，
     * 原先后台表单里根本没有这两项，所有门店都被当作直营。没传（null）就跳过，局部更新语义同长度校验。
     */
    private void validateOpAndGeo(KgStore store)
    {
        String op = store.getOpType();
        // 2026-11-03 主人：「来源类型，分成直营，加盟，代运营，合作」：加 partner 合作（V70 字典 kg_store_op_type）
        if (StringUtils.isNotEmpty(op) && !"direct".equals(op) && !"managed".equals(op) && !"franchise".equals(op) && !"partner".equals(op))
        {
            throw new ServiceException("运营类型只能是 直营 / 加盟 / 代运营 / 合作");
        }
        if (store.getLatitude() != null
                && (store.getLatitude().compareTo(new java.math.BigDecimal("-90")) < 0 || store.getLatitude().compareTo(new java.math.BigDecimal("90")) > 0))
        {
            throw new ServiceException("纬度应在 -90 到 90 之间");
        }
        if (store.getLongitude() != null
                && (store.getLongitude().compareTo(new java.math.BigDecimal("-180")) < 0 || store.getLongitude().compareTo(new java.math.BigDecimal("180")) > 0))
        {
            throw new ServiceException("经度应在 -180 到 180 之间");
        }
        if ((store.getLatitude() == null) != (store.getLongitude() == null))
        {
            throw new ServiceException("经纬度要么都填，要么都不填");
        }
    }
}
