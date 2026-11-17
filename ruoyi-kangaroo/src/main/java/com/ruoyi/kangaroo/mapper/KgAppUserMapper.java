package com.ruoyi.kangaroo.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgAppUser;

/**
 * 小程序账号 Mapper
 *
 * 账号只由微信登录链路创建，后台没有新增/删除入口，因此这里不提供 deleteXxx 方法。
 */
public interface KgAppUserMapper
{
    KgAppUser selectKgAppUserByUserId(Long userId);

    /** 微信登录首先按 openid 认人，openid 上有唯一索引，命中即复用老账号 */
    KgAppUser selectByOpenid(@Param("openid") String openid);

    /** 列表不查 openid/unionid，避免敏感标识随分页结果批量流出 */
    List<KgAppUser> selectKgAppUserList(KgAppUser kgAppUser);

    int insertKgAppUser(KgAppUser kgAppUser);

    int updateKgAppUser(KgAppUser kgAppUser);

    /**
     * 仅用于把"查不到"细分成"从未注册"和"已注销"，给出更准确的提示。
     * 返回的是计数不是行数据，所以不会把逻辑删除的数据带到业务里。
     */
    int countDeletedByUserId(@Param("userId") Long userId);

    /**
     * 这个手机号当前属于哪个账号；没人用返回 null。
     *
     * 用于绑定手机号时拦住"把陌生人的号码绑到自己账号上"。
     * 只看未删除的账号：已注销账号占着的号码应当可以被重新使用。
     */
    Long selectUserIdByPhone(@Param("phone") String phone);
}
