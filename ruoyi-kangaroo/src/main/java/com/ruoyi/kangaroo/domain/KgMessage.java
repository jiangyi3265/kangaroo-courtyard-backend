package com.ruoyi.kangaroo.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 站内消息 对象 kg_message
 *
 * 由 scratchpad/gen_domain.py 按 sql/kangaroo/V1__kangaroo_core.sql 的列定义生成，
 * 字段与列一一对应；公共列 create_by/create_time/update_by/update_time/remark 来自 BaseEntity。
 */
public class KgMessage extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 消息ID */
    @Excel(name = "消息ID")
    private Long msgId;

    /** 接收人账号ID */
    @Excel(name = "接收人")
    private Long recipientId;

    /** 身份（user/staff） */
    @Excel(name = "身份", readConverterExp = "user=用户端,staff=员工端")
    private String mode;

    /** 分类 */
    @Excel(name = "分类", readConverterExp = "system=系统,apply=报名,interview=面试,contract=签约,order=订单,chat=会话,comment=评论,like=点赞")
    private String category;

    /** 标题 */
    @Excel(name = "标题")
    private String title;

    /** 正文 */
    @Excel(name = "正文")
    private String content;

    /** 关联业务类型 */
    private String bizType;

    /** 关联业务ID */
    private Long bizId;

    /** 跳转路由 */
    private String route;

    /** 触发人（评论人 / 最近点赞的人，kg_app_user.user_id）；系统或业务流程发的消息为空（V43） */
    private Long actorId;

    /** 已读标志（0未读 1已读） */
    @Excel(name = "已读", readConverterExp = "0=未读,1=已读")
    private String readFlag;

    /** 阅读时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date readTime;

    /** 删除标志 */
    private String delFlag;

    /** 接收人昵称（关联查询字段，非本表列） */
    @Excel(name = "接收人昵称")
    private String nickname;

    /*
     * 以下是消息页「收到的评论 / 收到的赞」分页列表的关联查询字段（非本表列），只有 comment / like 两类有值：
     * 触发人昵称（没填昵称写「用户{id}」）和头像、动态 ID、首图、标题，以及被回复 / 被赞的那条评论的片段
     */
    private String actorName;

    private String actorAvatar;

    private Long postId;

    private String cover;

    private String postTitle;

    private String quote;

    public void setMsgId(Long msgId)
    {
        this.msgId = msgId;
    }

    public Long getMsgId()
    {
        return msgId;
    }

    public void setRecipientId(Long recipientId)
    {
        this.recipientId = recipientId;
    }

    public Long getRecipientId()
    {
        return recipientId;
    }

    public void setMode(String mode)
    {
        this.mode = mode;
    }

    public String getMode()
    {
        return mode;
    }

    public void setCategory(String category)
    {
        this.category = category;
    }

    public String getCategory()
    {
        return category;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getTitle()
    {
        return title;
    }

    public void setContent(String content)
    {
        this.content = content;
    }

    public String getContent()
    {
        return content;
    }

    public void setBizType(String bizType)
    {
        this.bizType = bizType;
    }

    public String getBizType()
    {
        return bizType;
    }

    public void setBizId(Long bizId)
    {
        this.bizId = bizId;
    }

    public Long getBizId()
    {
        return bizId;
    }

    public void setRoute(String route)
    {
        this.route = route;
    }

    public String getRoute()
    {
        return route;
    }

    public void setActorId(Long actorId)
    {
        this.actorId = actorId;
    }

    public Long getActorId()
    {
        return actorId;
    }

    public void setReadFlag(String readFlag)
    {
        this.readFlag = readFlag;
    }

    public String getReadFlag()
    {
        return readFlag;
    }

    public void setReadTime(Date readTime)
    {
        this.readTime = readTime;
    }

    public Date getReadTime()
    {
        return readTime;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setNickname(String nickname)
    {
        this.nickname = nickname;
    }

    public String getNickname()
    {
        return nickname;
    }

    public void setActorName(String actorName)
    {
        this.actorName = actorName;
    }

    public String getActorName()
    {
        return actorName;
    }

    public void setActorAvatar(String actorAvatar)
    {
        this.actorAvatar = actorAvatar;
    }

    public String getActorAvatar()
    {
        return actorAvatar;
    }

    public void setPostId(Long postId)
    {
        this.postId = postId;
    }

    public Long getPostId()
    {
        return postId;
    }

    public void setCover(String cover)
    {
        this.cover = cover;
    }

    public String getCover()
    {
        return cover;
    }

    public void setPostTitle(String postTitle)
    {
        this.postTitle = postTitle;
    }

    public String getPostTitle()
    {
        return postTitle;
    }

    public void setQuote(String quote)
    {
        this.quote = quote;
    }

    public String getQuote()
    {
        return quote;
    }

}
