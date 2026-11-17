package com.ruoyi.kangaroo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgApplicationLog;

/**
 * 报名状态流转日志 Mapper
 *
 * 该表是履约链路的审计流水：只追加、不修改、不删除，所以既没有 del_flag，
 * 也不提供 update / delete 方法。有争议时以这张表的记录为准。
 */
public interface KgApplicationLogMapper
{
    int insertKgApplicationLog(KgApplicationLog kgApplicationLog);

    /** 按发生先后取某张报名单的完整流转轨迹，供详情页时间轴展示 */
    List<KgApplicationLog> selectByAppId(@Param("appId") Long appId);
}
