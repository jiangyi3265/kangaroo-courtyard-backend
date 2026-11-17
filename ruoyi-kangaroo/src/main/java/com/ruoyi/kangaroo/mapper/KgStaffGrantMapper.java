package com.ruoyi.kangaroo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgStaffGrant;

/**
 * 员工授权 Mapper
 */
public interface KgStaffGrantMapper
{
    KgStaffGrant selectKgStaffGrantByGrantId(Long grantId);

    List<KgStaffGrant> selectKgStaffGrantList(KgStaffGrant kgStaffGrant);

    int insertKgStaffGrant(KgStaffGrant kgStaffGrant);

    int updateKgStaffGrant(KgStaffGrant kgStaffGrant);

    /** 生效中的授权（联门店取名称）。门店停用或删除时员工身份一并失效，故这里把门店状态也算进条件 */
    List<KgStaffGrant> selectActiveGrantsWithStore(@Param("appUserId") Long appUserId);

    /**
     * 按账号 + 门店取授权，故意不过滤 status 与 del_flag。
     * uk_grant_user_store 唯一索引覆盖已撤销、已逻辑删除的行，插入前必须能看见它们，否则会撞唯一键。
     */
    KgStaffGrant selectByUserAndStore(@Param("appUserId") Long appUserId, @Param("storeId") Long storeId);

    KgStaffGrant selectByUserAndCity(@Param("appUserId") Long appUserId, @Param("cityId") Long cityId);

    int countActive(@Param("appUserId") Long appUserId, @Param("storeId") Long storeId);

    /** 某门店当前生效的员工账号ID列表 */
    List<Long> selectActiveStaffUserIds(@Param("storeId") Long storeId);

    /** 某门店当前生效的商家服务人账号ID（门店的线下业务消息不发给他们） */
    List<Long> selectActiveMerchantServiceUserIds(@Param("storeId") Long storeId);

    /** 就地复活旧授权（含已撤销、已逻辑删除的行），并清掉上一次的撤销痕迹 */
    int reactivateGrant(@Param("grantId") Long grantId, @Param("roleCode") String roleCode,
            @Param("grantedBy") String grantedBy);

    /** 撤销：status 置 1 并留痕撤销人与撤销时间 */
    int revokeByGrantIds(@Param("grantIds") Long[] grantIds, @Param("revokedBy") String revokedBy);
}
