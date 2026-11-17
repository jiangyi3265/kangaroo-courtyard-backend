package com.ruoyi.kangaroo.enums;

/**
 * 站内消息分类，与小程序消息中心的分类页签、后台字典 kg_msg_category 对应。
 * GROUP（活动群聊）在 V2 字典里；STAFF（员工待办）由 V17 补进字典——它们此前只以字面量写在业务代码里，
 * 词表里没有，前端对照词表的静态检查也就认不得它们。
 * COMMENT（收到的评论）、LIKE（收到的赞）由 V43 加入（2026-10-15 消息页改版）：之前评论通知记在 system 里，点赞没有通知。
 */
public final class MessageCategory
{
    public static final String SYSTEM = "system";
    public static final String APPLY = "apply";
    public static final String INTERVIEW = "interview";
    public static final String CONTRACT = "contract";
    public static final String ORDER = "order";
    public static final String CHAT = "chat";
    public static final String GROUP = "group";
    public static final String STAFF = "staff";
    public static final String COMMENT = "comment";
    public static final String LIKE = "like";

    private MessageCategory()
    {
    }
}
