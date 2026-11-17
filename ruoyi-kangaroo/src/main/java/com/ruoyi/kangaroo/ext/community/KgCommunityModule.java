package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgReportHandler;

/**
 * 社区模块的后台登记：动态（审核 / 隐藏 / 恢复 / 置顶）、评论（隐藏 / 恢复）、活动（审核 / 取消 / 投保标记）、
 * 活动报名（只读 + 退款审批）、公开行程（审核）、会话（只看元数据，被举报的可关闭）、敏感词库（增删改）。
 * 另外处理 comment 类型的举报：举报成立 = 隐藏评论。
 */
@Component
public class KgCommunityModule implements ExtEntityContributor, KgReportHandler
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private KgActivityService activities;

    /** 举报成立隐藏评论后通知评论人 */
    @Lazy
    @Autowired
    private KgPostService posts;

    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity post = new ExtEntity("post", "社区动态", "kg_post", "post_id", "kangaroo:post")
                .field(ExtField.of("appUserId", "作者ID", "int").search("eq").readOnly())
                // 2026-10-17：daily 显示名改「旅居攻略」（代码不变，老帖子跟着改名）
                .field(ExtField.of("kind", "类型", "select").options("daily:旅居攻略,buddy:找搭子,diary:经验分享").search("eq").readOnly())
                .field(ExtField.of("city", "城市", "text").search("eq").readOnly())
                .field(ExtField.of("title", "标题", "text").search("like").readOnly())
                .field(ExtField.of("content", "正文", "textarea").hideInList().readOnly())
                .field(ExtField.of("images", "图片", "images").hideInList().readOnly())
                .field(ExtField.of("status", "状态", "select").options("pending:待审核,online:已发布,rejected:已驳回,hidden:已隐藏").search("eq").readOnly())
                .field(ExtField.of("auditReason", "驳回/隐藏原因", "text").hideInList().readOnly())
                .field(ExtField.of("reviewMode", "审核方式", "select").options("manual:人工,auto:机审通过直接发布,mock:本地模拟放行,seed:演示数据").readOnly())
                .field(ExtField.of("likes", "赞", "int").readOnly())
                .field(ExtField.of("comments", "评论", "int").readOnly())
                .field(ExtField.of("reported", "被举报", "int").readOnly())
                .field(ExtField.of("pinned", "置顶", "select").options("1:是,0:否"))
                .field(ExtField.of("createTime", "发布时间", "datetime").search("range").readOnly())
                .noAdd()
                // 删除走「驳回 / 隐藏」，不物理删（菜单也没有 post:remove）
                .noDelete()
                .action("approve", "通过", "put", "/kangaroo/community/post/{postId}/approve", "kangaroo:post:audit",
                        "确认内容合规并发布？", false, Collections.singletonMap("status", Arrays.asList("pending", "hidden", "rejected")))
                .action("reject", "驳回 / 隐藏", "put", "/kangaroo/community/post/{postId}/reject", "kangaroo:post:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending", "online")));

        ExtEntity comment = new ExtEntity("postcomment", "动态评论", "kg_post_comment", "comment_id", "kangaroo:postcomment")
                .field(ExtField.of("postId", "动态ID", "int").search("eq").readOnly())
                .field(ExtField.of("appUserId", "评论人ID", "int").search("eq").readOnly())
                .field(ExtField.of("content", "内容", "text").search("like").readOnly())
                .field(ExtField.of("images", "评论图片", "images").hideInList().readOnly())
                .field(ExtField.of("status", "状态", "select").options("visible:可见,pending:待审核,hidden:已隐藏").search("eq").readOnly())
                .field(ExtField.of("reported", "被举报", "int").readOnly())
                .field(ExtField.of("handleNote", "处理说明", "text").hideInList().readOnly())
                .field(ExtField.of("createTime", "时间", "datetime").search("range").readOnly())
                // 与菜单设计一致：只查询 + 审核动作，不开放通用修改 / 删除（改用户内容会绕过内容审核，删除应走隐藏 / 驳回）
                .readOnly()
                .action("show", "通过 / 恢复", "put", "/kangaroo/community/comment/{commentId}/show", "kangaroo:postcomment:audit",
                        "确认展示这条评论？", false, Collections.singletonMap("status", Arrays.asList("pending", "hidden")))
                .action("hide", "隐藏", "put", "/kangaroo/community/comment/{commentId}/hide", "kangaroo:postcomment:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending", "visible")));

        ExtEntity activity = new ExtEntity("activity", "社区活动", "kg_activity", "activity_id", "kangaroo:activity")
                .field(ExtField.of("productKind", "发布内容", "select").options("activity:社区活动,travel:旅游产品").search("eq").createOnly())
                .field(ExtField.of("cover", "封面", "image").createOnly())
                .field(ExtField.of("title", "标题", "text").max(30).search("like").required().createOnly())
                // 来源不再让运营选：由所属门店类型决定（直营 = 小院活动，其他 = 商家活动），见 KgActivityService.prepareAdminCreate，
                // 与员工端同一条规则；原先可选，非直营门店能建出「小院活动 + 在线支付」
                .field(ExtField.of("source", "来源", "select").options("yard:小院活动,merchant:商家活动,user:用户自发").search("eq").readOnly())
                .field(ExtField.of("storeId", "门店ID", "int").search("eq").required().createOnly())
                .field(ExtField.of("organizerName", "主办方", "text").createOnly())
                .field(ExtField.of("city", "城市", "text").search("eq").createOnly())
                .field(ExtField.of("place", "集合地点", "text").hideInList().required().createOnly())
                .field(ExtField.of("startTime", "开始时间", "datetime").search("range").required().createOnly())
                .field(ExtField.of("capacity", "名额", "int").required().createOnly())
                .field(ExtField.of("signed", "已报名", "int").readOnly())
                .field(ExtField.of("feeFen", "费用", "money").createOnly())
                // 2026-11-03 主人「价格，免费，aa制三种付费方式」：多一种 AA制（现场平摊，平台不收钱，费用填 0；预估人均选填，V71 aa_fen）
                .field(ExtField.of("feeMode", "收费方式", "select").options("free:免费,online:在线支付,offline:到场支付,aa:AA制（现场平摊）").required().createOnly())
                .field(ExtField.of("aaFen", "AA预估人均", "money").hideInList().createOnly())
                .field(ExtField.of("insured", "平台已投保", "select").options("1:是,0:否"))
                .field(ExtField.of("insuranceDesc", "保障说明", "text").max(128).hideInList())
                .field(ExtField.of("intro", "介绍", "textarea").max(1000).hideInList().createOnly())
                .field(ExtField.of("status", "状态", "select").options("draft:草稿,pending:待审核,open:报名中,paused:暂停报名,closed:报名截止,ended:已结束,cancelled:已取消,rejected:已驳回")
                        .search("eq").readOnly())
                .field(ExtField.of("auditReason", "驳回/取消原因", "text").hideInList().readOnly())
                .field(ExtField.of("pinned", "置顶", "select").options("1:是,0:否"))
                .field(ExtField.of("endTime", "结束时间", "datetime").required().createOnly())
                .field(ExtField.of("deadline", "报名截止", "datetime").createOnly())
                // 2026-11-03 主人：活动类型「只留这四个」；老活动的手作课程 / 讲座沙龙在列表里照原样显示
                .field(ExtField.of("category", "活动分类", "select").options("生活体验:生活体验,小型聚会:小型聚会,户外活动:户外活动,其他:其他").createOnly())
                .field(ExtField.of("safetyNote", "参与须知", "textarea").max(200).createOnly().hideInList())
                .validate(activities::prepareAdminCreate)
                // 有报名的活动只能「取消活动」（会退款、通知），不能从后台直接删（菜单也没有 activity:remove）
                .noDelete()
                .action("approve", "通过", "put", "/kangaroo/community/activity/{activityId}/approve", "kangaroo:activity:audit",
                        "确认活动信息真实合规、开放报名？", false, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("reject", "驳回", "put", "/kangaroo/community/activity/{activityId}/reject", "kangaroo:activity:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("cancel", "取消活动", "put", "/kangaroo/community/activity/{activityId}/cancel", "kangaroo:activity:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("open", "paused", "closed")));

        ExtEntity signup = new ExtEntity("actsignup", "活动报名", "kg_activity_signup", "signup_id", "kangaroo:actsignup")
                .field(ExtField.of("signupNo", "报名单号", "text").search("eq"))
                .field(ExtField.of("activityId", "活动ID", "int").search("eq"))
                .field(ExtField.of("appUserId", "报名人ID", "int").search("eq"))
                .field(ExtField.of("name", "昵称", "text"))
                .field(ExtField.of("status", "状态", "select").options("pending_payment:待支付,confirmed:已确认,waitlist:候补,cancelled:已取消,refund_pending:退款待审批,"
                        + "refunding:退款中,refunded:已退款,closed:已关闭").search("eq"))
                .field(ExtField.of("feeFen", "应付", "money"))
                .field(ExtField.of("refundFen", "退款", "money"))
                .field(ExtField.of("cancelReason", "取消原因", "text").hideInList())
                .field(ExtField.of("payNo", "支付单号", "text").hideInList())
                // 审批说明；重新报名前已付未退的旧支付单号也记在这里（「旧支付单 KP…」）
                .field(ExtField.of("refundNote", "退款备注", "text").hideInList())
                .field(ExtField.of("createTime", "报名时间", "datetime").search("range"))
                .readOnly()
                .action("refundApprove", "同意退款", "put", "/kangaroo/community/signup/{signupId}/refund-approve", "kangaroo:actsignup:audit",
                        "同意后按原路退回，确认？", false, Collections.singletonMap("status", Arrays.asList("refund_pending")))
                .action("refundReject", "驳回退款", "put", "/kangaroo/community/signup/{signupId}/refund-reject", "kangaroo:actsignup:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("refund_pending")));

        ExtEntity plan = new ExtEntity("tripplan", "公开行程", "kg_trip_plan", "plan_id", "kangaroo:tripplan")
                .field(ExtField.of("appUserId", "发布人ID", "int").search("eq").readOnly())
                .field(ExtField.of("city", "城市", "text").search("eq").readOnly())
                .field(ExtField.of("startDate", "开始", "date").readOnly())
                .field(ExtField.of("endDate", "结束", "date").readOnly())
                .field(ExtField.of("activities", "主要活动", "text").readOnly())
                .field(ExtField.of("intro", "简介", "text").hideInList().readOnly())
                .field(ExtField.of("status", "状态", "select").options("pending:待审核,online:公开中,rejected:已驳回,closed:已关闭").search("eq").readOnly())
                .field(ExtField.of("auditReason", "驳回原因", "text").hideInList().readOnly())
                // 与菜单设计一致：只查询 + 审核动作，不开放通用修改 / 删除（改用户内容会绕过内容审核，删除应走隐藏 / 驳回）
                .readOnly()
                .action("approve", "通过", "put", "/kangaroo/community/plan/{planId}/approve", "kangaroo:tripplan:audit",
                        "确认公开这条行程？", false, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("reject", "驳回", "put", "/kangaroo/community/plan/{planId}/reject", "kangaroo:tripplan:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")));

        // 会话只登记元数据，不在后台展示私信内容；被举报的会话由「举报处理」判定后关闭
        ExtEntity conv = new ExtEntity("chatconv", "私信群聊", "kg_chat_conv", "conv_id", "kangaroo:chatconv")
                .field(ExtField.of("convType", "类型", "select").options("private:私信,store:联系门店,group:活动群").search("eq"))
                .field(ExtField.of("title", "标题", "text").search("like"))
                .field(ExtField.of("activityId", "活动ID", "int").search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("status", "状态", "select").options("active:正常,closed:已关闭").search("eq"))
                .field(ExtField.of("reported", "被举报", "int"))
                .field(ExtField.of("lastTime", "最后消息时间", "datetime"))
                .field(ExtField.of("createTime", "创建时间", "datetime").search("range"))
                .readOnly();

        ExtEntity words = new ExtEntity("sensword", "敏感词库", "kg_sensitive_word", "word_id", "kangaroo:sensword")
                .field(ExtField.of("word", "词", "text").required().max(32).search("like"))
                .field(ExtField.of("level", "处理方式", "select").options("block:直接拦截,review:转人工审核").required().search("eq"))
                .field(ExtField.of("status", "状态", "select").options("0:启用,1:停用").search("eq"))
                .hardDelete();

        return Arrays.asList(post, comment, activity, signup, plan, conv, words);
    }

    @Override
    public String targetType()
    {
        return "comment";
    }

    @Override
    public String uphold(long targetId, String operator)
    {
        // 锁评论行、只处理没被作者删掉的：作者先删了评论（计数已 -1，status 仍是 visible）再判举报成立，原先会再 -1 一次
        java.util.Map<String, Object> c = db.one("select post_id, app_user_id, content, status from kg_post_comment where comment_id = :c and del_flag = '0' for update", p("c", targetId));
        if (c != null && "visible".equals(KgJdbc.asStr(c.get("status"))))
        {
            int hid = db.update("update kg_post_comment set status = 'hidden', handle_note = :n, update_time = now() where comment_id = :c and status = 'visible' and del_flag = '0'",
                    p("n", operator + "：举报成立", "c", targetId));
            if (hid > 0)
            {
                db.update("update kg_post set comments = greatest(comments - 1, 0) where post_id = :p", p("p", c.get("postId")));
                // 告诉评论人评论被隐藏了（之前悄悄隐藏，什么都不发）
                posts.notifyCommentHidden(KgJdbc.asLong(c.get("appUserId")), KgJdbc.asLong(c.get("postId")), KgJdbc.asStr(c.get("content")), "举报成立，已隐藏");
            }
        }
        return "评论已隐藏";
    }
}
