package com.ruoyi.kangaroo.ext.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 共享能力模块：支付单（只读 + 退款）、评价（隐藏/精选/回复）、举报（处理）的后台登记，
 * 以及举报成立时隐藏评价这一种处置。
 */
@Component
public class KgCoreModule implements ExtEntityContributor, KgReportHandler
{
    private final KgJdbc db;
    private final KgReviewStats stats;

    public KgCoreModule(KgJdbc db, KgReviewStats stats)
    {
        this.db = db;
        this.stats = stats;
    }

    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity pay = new ExtEntity("payorder", "支付单", "kg_pay_order", "pay_id", "kangaroo:payorder")
                .field(ExtField.of("payNo", "支付单号", "text").search("like"))
                .field(ExtField.of("bizType", "业务", "select").dict("kg_biz_type").search("eq"))
                .field(ExtField.of("bizId", "业务单ID", "int"))
                .field(ExtField.of("appUserId", "付款账号ID", "int").search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("amount", "金额", "money"))
                .field(ExtField.of("status", "状态", "select").dict("kg_pay_status").search("eq"))
                .field(ExtField.of("channel", "渠道", "select").options("mock:联调通道,wechat:微信支付"))
                .field(ExtField.of("paidTime", "到账时间", "datetime"))
                .field(ExtField.of("refundAmount", "已退金额", "money"))
                .field(ExtField.of("refundReason", "退款原因", "text").hideInList())
                .field(ExtField.of("createTime", "创建时间", "datetime").search("range"))
                .readOnly()
                .action("refundProgress", "退款进度", "get", "/kangaroo/core/payorder/{payNo}/refund-progress", "kangaroo:payorder:query", null, false, null)
                .action("refund", "全额退款", "put", "/kangaroo/core/payorder/{payNo}/refund", "kangaroo:payorder:refund",
                        "确认把这笔支付的剩余金额全部退回给付款人？", true,
                        Collections.singletonMap("status", Arrays.asList("paid", "part_refunded")));

        ExtEntity review = new ExtEntity("review", "评价", "kg_review", "review_id", "kangaroo:review")
                .field(ExtField.of("targetType", "对象", "select").dict("kg_review_target").search("eq").readOnly())
                .field(ExtField.of("targetId", "对象ID", "int").readOnly())
                .field(ExtField.of("storeId", "门店ID", "int").search("eq").readOnly())
                .field(ExtField.of("appUserId", "评价人ID", "int").readOnly())
                .field(ExtField.of("rating", "评分", "int").search("eq").readOnly())
                .field(ExtField.of("content", "内容", "textarea").search("like").readOnly())
                // 2026-10-17 R3：列表里就放图片缩略图和「▶ 视频」（住宿评价可以附照片和 1 段视频，审核时不用一条条点开）
                .field(ExtField.of("images", "图片", "images").readOnly())
                .field(ExtField.of("videos", "视频", "media").readOnly())
                .field(ExtField.of("reply", "商家回复", "textarea").max(500).hideInList())
                .field(ExtField.of("status", "显示", "select").dict("sys_show_hide").search("eq"))
                .field(ExtField.of("featured", "精选", "switch"))
                .field(ExtField.of("createTime", "评价时间", "datetime").search("range").readOnly())
                .afterChange(stats::refreshByReview)
                .noAdd();

        ExtEntity report = new ExtEntity("report", "举报", "kg_report", "report_id", "kangaroo:report")
                .field(ExtField.of("targetType", "对象", "select")
                        .options("post:动态,comment:评论,user:用户,vlog:Vlog,vlog_comment:Vlog评论,chat:会话,review:评价,staffop:员工操作,stay_comment:小院评论").search("eq"))
                .field(ExtField.of("targetId", "对象ID", "int"))
                .field(ExtField.of("reporterId", "举报人ID", "int"))
                .field(ExtField.of("reason", "类型", "text").search("like"))
                .field(ExtField.of("detail", "说明", "textarea").hideInList())
                .field(ExtField.of("status", "状态", "select").dict("kg_report_status").search("eq"))
                .field(ExtField.of("handleNote", "处理说明", "text").hideInList())
                .field(ExtField.of("handledBy", "处理人", "text"))
                .field(ExtField.of("createTime", "举报时间", "datetime").search("range"))
                .readOnly()
                .action("context", "查看原文", "get", "/kangaroo/core/report/{reportId}/context", "kangaroo:report:handle", null, false, null)
                .action("uphold", "举报成立", "put", "/kangaroo/core/report/{reportId}/uphold", "kangaroo:report:handle",
                        "确认举报成立？被举报内容会被隐藏。", true, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("reject", "不成立", "put", "/kangaroo/core/report/{reportId}/reject", "kangaroo:report:handle",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")));
        return Arrays.asList(pay, review, report);
    }

    @Override
    public String targetType()
    {
        return "review";
    }

    @Override
    public String uphold(long targetId, String operator)
    {
        int n = db.update("update kg_review set status = '1', update_by = :u, update_time = now() where review_id = :id",
                KgJdbc.p("id", targetId, "u", operator));
        if (n > 0)
        {
            stats.refreshByReview(targetId);
        }
        return n > 0 ? "评价已隐藏" : "评价不存在";
    }
}
