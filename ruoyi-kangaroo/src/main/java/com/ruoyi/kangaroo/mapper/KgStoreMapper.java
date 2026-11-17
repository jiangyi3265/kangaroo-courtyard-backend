package com.ruoyi.kangaroo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgStore;

/**
 * 门店 Mapper
 */
public interface KgStoreMapper
{
    KgStore selectKgStoreByStoreId(Long storeId);

    List<KgStore> selectKgStoreList(KgStore kgStore);

    int insertKgStore(KgStore kgStore);

    int updateKgStore(KgStore kgStore);

    /** 逻辑删除 */
    int deleteKgStoreByStoreIds(@Param("storeIds") Long[] storeIds, @Param("updateBy") String updateBy);

    /** 撤销认证时显式清空认证时间与操作人 */
    int clearCert(@Param("storeId") Long storeId);

    /** 门店名在同城内是否重复（排除自身） */
    int countByNameAndCity(@Param("storeName") String storeName, @Param("city") String city,
            @Param("excludeId") Long excludeId);
}
