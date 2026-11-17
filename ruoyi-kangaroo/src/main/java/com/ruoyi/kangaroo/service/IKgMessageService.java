package com.ruoyi.kangaroo.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.kangaroo.domain.KgMessage;

/**
 * 站内消息 Service
 *
 * 小程序消息中心的唯一数据源。前端按 recipientId + mode 过滤，
 * 所以 mode 必须由服务端写准：面向 C 端用户写 'user'，面向门店员工写 'staff'。
 */
public interface IKgMessageService
{
    /**
     * 业务流程发消息，返回新消息ID。
     *
     * @param recipientId 接收人（kg_app_user.user_id）；为空直接返回 null 不落库，
     *                    发消息是主业务的附属动作，不能因为收件人缺失把主流程打断
     * @param mode        身份，'user' 或 'staff'；为空按 'user' 处理
     * @param category    分类，取 MessageCategory 常量；为空按 system 处理
     * @param bizType     关联业务类型，用于按业务反查消息
     * @param bizId       关联业务ID
     * @param route       点击消息跳转的小程序路由
     */
    Long send(Long recipientId, String mode, String category, String title, String content,
            String bizType, Long bizId, String route);

    /**
     * 同上，另记触发人（2026-10-15 消息页改版）：评论通知的 actorId = 评论人，消息页「收到的评论」据此显示对方昵称和头像，
     * 双方拉黑后这条通知也据此不再显示。系统或业务流程发的消息用上面那个方法（触发人为空）。
     */
    Long send(Long recipientId, String mode, String category, String title, String content,
            String bizType, Long bizId, String route, Long actorId);

    /**
     * 有人点赞了收件人的动态（bizType = post_like，bizId = 动态ID）或评论（comment_like，评论ID）。
     * 同一个收件人、同一个对象只占一行：上次读过以后又有 N 个人点赞时标题写「{昵称} 等 N 人赞了你的动态」，
     * 只有一个人时写「{昵称} 赞了你的动态」；这一行重新变成未读、时间更新成现在，read_time 不动（它就是这一轮计数的起点）。
     * 自己赞自己不发；收件人在「我的提醒」里暂停了社区互动不发；点赞从不发微信订阅消息。
     * 调用方要在点赞计数更新（锁住动态 / 评论那一行）之后再调用，同一对象的点赞才会排队，不会插出两行。
     */
    void upsertLike(Long recipientId, String bizType, Long bizId, Long actorId, String content, String route);

    /**
     * 取消点赞：这一行还没读时才改（读过的不动），按剩下的人重写标题和最近点赞的人；一个都不剩就把这一行删掉（del_flag = 2）。
     */
    void retractLike(Long recipientId, String bizType, Long bizId, Long actorId);

    /**
     * 账号注销时：触发人是 userId 的未读点赞合并行，按剩下的（仍在用的）点赞人重写——
     * 剩 0 人删掉这一行（del_flag = 2），否则标题和触发人改成剩下的人里最近点赞的那个，时间不动；读过的行不动。
     * 调用方要先删掉这个人的点赞并重算计数，再调用本方法，最后才按 actor_id 抹掉剩下的通知。
     */
    void relinkLikesOf(Long userId);

    /**
     * 拉黑时（2026-10-16）：收件人 recipientId 的未读点赞合并行里，触发人（最近点赞的人）是 actorId 的，按剩下的点赞人重写——
     * 点赞人的口径本来就不算有拉黑关系的人，所以要在写入拉黑记录之后调用：改指剩下的人里最近点赞的那个，一个不剩才删掉；
     * 之前这一行整行跟着被拉黑的人一起不显示，别人点的赞也看不到了。读过的行不动。
     */
    void relinkLikesBetween(Long recipientId, Long actorId);

    /** 小程序消息列表；category 为空表示“全部”页签 */
    List<KgMessage> selectAppMessages(Long recipientId, String mode, String category);

    /**
     * 消息页一组一页（2026-10-15）：bucket 见 KgMessageMapper.xml 的 bucketOf，为空不按分组过滤；
     * 从 offset 起取 limit 条，按最近时间倒序。
     */
    List<KgMessage> selectAppMessagePage(Long recipientId, String mode, String category, String bucket, int offset, int limit);

    /** 消息中心未读红点数 */
    int countUnread(Long recipientId, String mode);

    /** 标记单条已读；只有接收人本人能标记，msgId 不属于本人时返回 0 */
    int markRead(Long msgId, Long recipientId);

    /** 本人当前身份下的消息软删除，不删除关联业务或聊天记录。 */
    int deleteOwnMessage(Long msgId, Long recipientId, String mode);

    /** 一键已读当前身份下的全部消息 */
    int markAllRead(Long recipientId, String mode);

    /** 一键已读当前身份下的一组消息（消息页点开「评论」「赞」「系统通知」时）；bucket 为空 = 全部 */
    int markAllRead(Long recipientId, String mode, String bucket);

    /** 同上，maxId 不为空时只读掉这个消息ID及以前的（消息子页带第一页最大的ID） */
    int markAllRead(Long recipientId, String mode, String bucket, Long maxId);

    /** 标已读之前：这一组里还没读的消息ID（maxId 不为空时只要这个ID及以前的），新的在前，最多 limit 个 */
    List<Long> selectUnreadIds(Long recipientId, String mode, String bucket, Long maxId, int limit);

    /** 各分类的总数与未读数，供页签角标 */
    List<Map<String, Object>> countByCategory(Long recipientId, String mode);

    /** 消息页各组的总数与未读数：每行 {bucket, total, unread} */
    List<Map<String, Object>> countByBucket(Long recipientId, String mode);

    /** 后台列表（只读） */
    List<KgMessage> selectKgMessageList(KgMessage kgMessage);

    /** 后台详情（只读） */
    KgMessage selectKgMessageByMsgId(Long msgId);
}
