package com.ruoyi.kangaroo.service;

import java.util.List;
import com.ruoyi.kangaroo.domain.KgStore;

/**
 * 门店 Service
 */
public interface IKgStoreService
{
    KgStore selectKgStoreByStoreId(Long storeId);

    /** 存在性校验：不存在或已删除时抛业务异常 */
    KgStore requireStore(Long storeId);

    List<KgStore> selectKgStoreList(KgStore kgStore);

    int insertKgStore(KgStore kgStore);

    int updateKgStore(KgStore kgStore);

    int deleteKgStoreByStoreIds(Long[] storeIds);

    /** 安心商家认证 / 撤销认证 */
    int certify(Long storeId, boolean certified);
}
