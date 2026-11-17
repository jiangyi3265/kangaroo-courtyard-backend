package com.ruoyi.kangaroo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgJobFavorite;

/**
 * 岗位收藏 Mapper
 *
 * kg_job_favorite 没有 del_flag 列：收藏是用户随手开关的轻量关系，
 * 取消收藏直接物理删除，保留删除痕迹没有业务价值。
 */
public interface KgJobFavoriteMapper
{
    /** 依赖 uk_fav_user_job 唯一键去重，重复收藏不报错（返回 0 表示已收藏过） */
    int insertIgnore(KgJobFavorite fav);

    int deleteByUserAndJob(@Param("appUserId") Long appUserId, @Param("jobId") Long jobId);

    /** 详情页回显收藏状态用 */
    int countByUserAndJob(@Param("appUserId") Long appUserId, @Param("jobId") Long jobId);

    /** 只返回岗位ID，岗位详情由 KgJobMapper 按需再查，避免收藏表跟着岗位表字段变动 */
    List<Long> selectJobIdsByUser(@Param("appUserId") Long appUserId);
}
