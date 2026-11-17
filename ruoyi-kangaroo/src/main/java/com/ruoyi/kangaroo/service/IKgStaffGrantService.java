package com.ruoyi.kangaroo.service;

import java.util.List;
import com.ruoyi.kangaroo.domain.KgStaffGrant;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 员工授权 Service
 *
 * kg_staff_grant 是小程序端"员工身份"的唯一可信来源，
 * hasStaffAccess 与员工侧接口全部由这里推导，禁止前端自行判定。
 */
public interface IKgStaffGrantService
{
    /**
     * 取某账号当前生效的授权快照，用于登录下发与授权变更后刷新登录态。
     * 只返回授权本身生效、且所属门店正常的记录。
     */
    List<StaffGrantView> loadActiveGrantViews(Long appUserId);

    List<KgStaffGrant> selectKgStaffGrantList(KgStaffGrant kgStaffGrant);

    KgStaffGrant selectKgStaffGrantByGrantId(Long grantId);

    /** 存在性校验：不存在或已删除时抛业务异常 */
    KgStaffGrant requireGrant(Long grantId);

    /** 授予员工身份；账号在该门店已有历史授权时改为重新激活 */
    int grant(KgStaffGrant kgStaffGrant);

    /** 只调整角色，换人换店必须走撤销 + 重新授予 */
    int updateRole(KgStaffGrant kgStaffGrant);

    /** 撤销授权，并立刻作废该账号已下发的员工身份 */
    int revoke(Long[] grantIds);

    boolean hasActiveGrant(Long appUserId, Long storeId);

    /**
     * 列出某门店当前生效的全部员工账号ID。
     * 业务流程（如新报名到达）需要给门店所有在岗员工推送待办消息。
     */
    List<Long> listActiveStaffUserIds(Long storeId);

    /** 某门店当前生效的商家服务人账号ID：报名、订单、活动这些线下业务的消息发送时要跳过他们 */
    List<Long> listMerchantServiceUserIds(Long storeId);

    /**
     * 员工授权变更（授予 / 撤销）通知本人：一定发一条到用户身份，在其他门店还有员工身份的再发一条到员工身份。
     * 后台授权和门店主理人授权共用这一条路径。
     *
     * @param storeId 这次变更的门店，用来判断"其他门店"的员工身份
     */
    void notifyGrantChange(Long appUserId, Long storeId, String title, String content, Long bizId);
}
