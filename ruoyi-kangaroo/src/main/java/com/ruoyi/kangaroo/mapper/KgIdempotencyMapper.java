package com.ruoyi.kangaroo.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 幂等键 Mapper
 */
public interface KgIdempotencyMapper
{
    /** 插入幂等键；键冲突时由唯一索引抛异常，调用方捕获后视为重复请求 */
    int insertKey(@Param("idemKey") String idemKey, @Param("bizType") String bizType);

    Long selectBizId(@Param("idemKey") String idemKey);

    int countByKey(@Param("idemKey") String idemKey);

    /** 键已存在多少秒；键不存在时返回 null */
    Integer selectAgeSeconds(@Param("idemKey") String idemKey);

    int updateBizId(@Param("idemKey") String idemKey, @Param("bizId") Long bizId);

    int deleteByKey(@Param("idemKey") String idemKey);
}
