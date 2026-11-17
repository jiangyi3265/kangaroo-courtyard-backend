package com.ruoyi.kangaroo.ext.staff;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;

/** 员工端扩展的后台登记（只读留痕）：线上任务扩展、成果提交、线下结算单、人才库邀请 */
@Component
public class KgStaffExtModule implements ExtEntityContributor
{
    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity task = new ExtEntity("onlinetask", "线上任务", "kg_online_task", "job_id", "kangaroo:onlinetask")
                .field(ExtField.of("jobId", "岗位ID", "int").search("eq"))
                .field(ExtField.of("taskType", "类型", "text").search("eq"))
                .field(ExtField.of("deliverables", "交付物", "text"))
                .field(ExtField.of("deadlineTime", "截止时间", "datetime").search("range"))
                .field(ExtField.of("commissionFen", "佣金", "money"))
                .field(ExtField.of("criteria", "验收标准", "text").hideInList())
                .field(ExtField.of("announcement", "任务公告", "textarea").hideInList())
                .readOnly()
                .hardDelete();

        ExtEntity sub = new ExtEntity("onlinesubmit", "成果提交", "kg_online_submission", "submission_id", "kangaroo:onlinesubmit")
                .field(ExtField.of("appId", "接单（报名ID）", "int").search("eq"))
                .field(ExtField.of("round", "轮次", "int"))
                .field(ExtField.of("link", "成果链接", "text"))
                .field(ExtField.of("images", "成果图片", "images").hideInList())
                .field(ExtField.of("note", "补充说明", "text").hideInList())
                .field(ExtField.of("status", "状态", "select").options("submitted:待验收,accepted:已通过,returned:已退回").search("eq"))
                .field(ExtField.of("reviewComment", "评语 / 退回原因", "textarea").hideInList())
                // V41：退回修改附的截图（逗号分隔的平台图片路径，最多 9 张）
                .field(ExtField.of("reviewImages", "退回截图", "images").hideInList())
                .field(ExtField.of("reviewedBy", "验收人", "text"))
                .field(ExtField.of("createTime", "提交时间", "datetime").search("range"))
                .readOnly()
                .hardDelete();

        ExtEntity settle = new ExtEntity("onlinesettle", "线下结算", "kg_online_settlement", "settle_id", "kangaroo:onlinesettle")
                .field(ExtField.of("settleNo", "结算单号", "text").search("eq"))
                .field(ExtField.of("appId", "接单（报名ID）", "int").search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("appUserId", "接单人ID", "int").search("eq"))
                .field(ExtField.of("amountFen", "金额", "money"))
                .field(ExtField.of("status", "状态", "select").options("pending_payout:待门店付款,payout_registered:已登记付款,confirmed:已确认收款,disputed:申诉中").search("eq"))
                .field(ExtField.of("payoutMethod", "付款方式", "text"))
                .field(ExtField.of("payoutRef", "流水号", "text").hideInList())
                .field(ExtField.of("payoutVoucher", "付款凭证", "image").hideInList())
                .field(ExtField.of("disputeReason", "申诉原因", "text").hideInList())
                .field(ExtField.of("createTime", "生成时间", "datetime").search("range"))
                .readOnly()
                .hardDelete();

        ExtEntity invite = new ExtEntity("talentinvite", "人才邀请", "kg_talent_invite", "invite_id", "kangaroo:talentinvite")
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("appUserId", "被邀请人ID", "int").search("eq"))
                .field(ExtField.of("jobId", "岗位ID", "int").search("eq"))
                .field(ExtField.of("invitedBy", "邀请员工ID", "int"))
                .field(ExtField.of("createTime", "邀请时间", "datetime").search("range"))
                .readOnly()
                .hardDelete();

        return Arrays.asList(task, sub, settle, invite);
    }
}
