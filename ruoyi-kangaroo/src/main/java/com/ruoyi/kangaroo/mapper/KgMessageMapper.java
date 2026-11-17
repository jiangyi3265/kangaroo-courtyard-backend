package com.ruoyi.kangaroo.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.kangaroo.domain.KgMessage;

/**
 * 站内消息 Mapper
 *
 * kg_message 没有 create_by/update_by/update_time 列：消息由业务流程写入，
 * 不存在“后台操作人”，因此这里不提供通用的 update / 物理删除方法。
 */
public interface KgMessageMapper
{
    /** 业务流程发消息；回填 msgId 供调用方记日志或拼路由 */
    int insertKgMessage(KgMessage kgMessage);

    KgMessage selectKgMessageByMsgId(Long msgId);

    KgMessage selectVisibleMessage(@Param("msgId") Long msgId,@Param("recipientId") Long recipientId,@Param("mode") String mode);

    /** 本人这次报名的系统通知；before 为消息游标，避免新通知插入时翻页重复。 */
    List<KgMessage> selectApplicationNotices(@Param("recipientId") Long recipientId, @Param("appId") Long appId,
            @Param("before") Long before, @Param("limit") int limit);

    int countApplicationUnread(@Param("recipientId") Long recipientId, @Param("appId") Long appId);

    /** 只读门店报名会话的收件箱摘要，不创建会话、不标记已读。 */
    KgMessage selectApplicationChat(@Param("recipientId") Long recipientId, @Param("appId") Long appId);

    /** 后台列表：left join kg_app_user 取接收人昵称，便于按人排查消息 */
    List<KgMessage> selectKgMessageList(KgMessage kgMessage);

    /** 小程序消息列表：category 为空表示“全部”页签，不参与过滤 */
    List<KgMessage> selectAppMessages(@Param("recipientId") Long recipientId, @Param("mode") String mode,
            @Param("category") String category);

    /**
     * 消息页一组一页（2026-10-15）：bucket 取 main / system / comment / like（员工身份 main / system / apply / order），
     * 为空不按分组过滤；按最近时间倒序，从 offset 起取 limit 条。评论 / 赞两类另带触发人昵称、头像、动态首图与标题、被回复 / 被赞的评论片段。
     */
    List<KgMessage> selectAppMessagePage(@Param("recipientId") Long recipientId, @Param("mode") String mode,
            @Param("category") String category, @Param("bucket") String bucket,
            @Param("offset") int offset, @Param("limit") int limit);

    /** 消息中心红点数 */
    int countUnread(@Param("recipientId") Long recipientId, @Param("mode") String mode);

    /**
     * 标记单条已读。
     * recipient_id 条件写在 where 里而不是先查后比，既保证原子性，
     * 又让“别人的消息ID”只返回 0，无法据此探测消息是否存在。
     */
    int markRead(@Param("msgId") Long msgId, @Param("recipientId") Long recipientId);

    /** 删除前锁住本人的消息，复用当前员工接待权限。 */
    KgMessage selectOwnMessageForDelete(@Param("msgId") Long msgId, @Param("recipientId") Long recipientId,
            @Param("mode") String mode);

    /** 只软删除收件箱提醒；会话同时移除该身份历史旧摘要，避免删除后旧行重新出现。 */
    int deleteOwnMessage(@Param("msgId") Long msgId, @Param("recipientId") Long recipientId,
            @Param("mode") String mode, @Param("chatId") Long chatId);

    /** 一键已读；bucket 为空 = 当前身份全部分组，否则只读掉这一组；maxId 不为空时只读掉这个消息ID及以前的 */
    int markAllRead(@Param("recipientId") Long recipientId, @Param("mode") String mode, @Param("bucket") String bucket,
            @Param("maxId") Long maxId);

    /** 这一组（bucket 为空 = 全部）里还没读的消息ID，maxId 不为空时只要这个ID及以前的；新的在前，最多 limit 个 */
    List<Long> selectUnreadIds(@Param("recipientId") Long recipientId, @Param("mode") String mode, @Param("bucket") String bucket,
            @Param("maxId") Long maxId, @Param("limit") int limit);

    /** 各分类的总数与未读数，供消息中心页签角标一次取齐 */
    List<Map<String, Object>> countByCategory(@Param("recipientId") Long recipientId, @Param("mode") String mode);

    /** 消息页各组的总数与未读数：每行 {bucket, total, unread} */
    List<Map<String, Object>> countByBucket(@Param("recipientId") Long recipientId, @Param("mode") String mode);
    List<Map<String,Object>> selectStaffApplicationThreads(@Param("recipientId") Long recipientId,@Param("offset") int offset,@Param("limit") int limit);
    List<KgMessage> selectStaffApplicationThreadMessages(@Param("recipientId") Long recipientId,@Param("personId") Long personId,@Param("offset") int offset,@Param("limit") int limit);
}
