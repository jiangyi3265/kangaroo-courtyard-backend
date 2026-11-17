package com.ruoyi.kangaroo.ext.staff;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.core.KgReportHandler;

/**
 * 员工运营模块的后台登记：保险方案（增删改）、投保申请（登记保单号/驳回）、调度日志、分享追踪、员工操作日志（只读），
 * 以及"员工异常操作"举报的处置（只记录结论，约谈、撤权等由运营线下处理）。
 */
@Component
public class KgStaffOpsModule implements ExtEntityContributor, KgReportHandler
{
    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity plan = new ExtEntity("insplan", "保险方案", "kg_insurance_plan", "plan_id", "kangaroo:insplan")
                .field(ExtField.of("planName", "方案名称", "text").required().max(64).search("like"))
                .field(ExtField.of("planDesc", "保障内容", "textarea").max(255))
                .field(ExtField.of("insurer", "承保公司", "text").max(64))
                .field(ExtField.of("price", "保费", "money").required().range(0L, 10000000L))
                .field(ExtField.of("days", "保障天数", "int").required().range(1L, 3650L))
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L))
                .field(ExtField.of("status", "状态", "select").options("0:上架,1:下架").required().search("eq"))
                .field(ExtField.of("createTime", "创建时间", "datetime").readOnly())
                .order("sort, plan_id");

        ExtEntity apply = new ExtEntity("insapply", "投保申请", "kg_insurance_apply", "apply_id", "kangaroo:insapply")
                .field(ExtField.of("applyId", "申请号", "int").readOnly())
                .field(ExtField.of("insuredName", "被保险人", "text").search("like"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("appId", "报名ID", "int").search("eq"))
                .field(ExtField.of("planId", "方案ID", "int"))
                .field(ExtField.of("startDate", "起保日期", "date"))
                .field(ExtField.of("endDate", "止保日期", "date"))
                .field(ExtField.of("price", "参考保费", "money"))
                .field(ExtField.of("status", "状态", "select").options("pending:待登记保单,effective:已出单,rejected:已驳回").search("eq"))
                .field(ExtField.of("policyNo", "保单号", "text").search("like"))
                .field(ExtField.of("handleNote", "处理说明", "text").hideInList())
                .field(ExtField.of("handledBy", "处理人", "text"))
                .field(ExtField.of("createTime", "申请时间", "datetime").search("range"))
                .readOnly()
                .action("effect", "登记保单", "put", "/kangaroo/staffops/insapply/{applyId}/effect", "kangaroo:insapply:handle",
                        "请核对保险公司出具的真实保单后登记保单号；登记出单不等于保障已生效，保障须按保单起止日期核对。本操作不付款，并通知被保险人与门店。", true,
                        Collections.singletonMap("status", Arrays.asList("pending")))
                .promptLabel("请填写保险公司出具的保单号")
                .action("reject", "驳回", "put", "/kangaroo/staffops/insapply/{applyId}/reject", "kangaroo:insapply:handle",
                        "驳回原因会通知门店和被保险人本人，门店可以重新提交投保申请。", true, Collections.singletonMap("status", Arrays.asList("pending")));

        ExtEntity dispatch = new ExtEntity("dispatch", "调度日志", "kg_dispatch_log", "log_id", "kangaroo:dispatch")
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("jobId", "岗位ID", "int").search("eq"))
                .field(ExtField.of("opType", "类型", "select").options("adjust:数值调整,urgent:紧急标记,sync:系统同步,undo:撤销调整").search("eq"))
                .field(ExtField.of("operatorName", "操作人", "text").search("like"))
                .field(ExtField.of("beforeValue", "调整前", "text"))
                .field(ExtField.of("afterValue", "调整后", "text"))
                .field(ExtField.of("reason", "原因", "text"))
                .field(ExtField.of("validFrom", "有效期起", "date").hideInList())
                .field(ExtField.of("validTo", "有效期止", "date").hideInList())
                .field(ExtField.of("createTime", "时间", "datetime").search("range"))
                .readOnly().hardDelete();

        ExtEntity share = new ExtEntity("jobshare", "分享追踪", "kg_job_share", "share_id", "kangaroo:jobshare")
                .field(ExtField.of("shareCode", "分享码", "text").search("like"))
                .field(ExtField.of("jobId", "岗位ID", "int").search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("staffUserId", "分享员工ID", "int").search("eq"))
                .field(ExtField.of("channel", "渠道", "select").options("friend:微信好友,group:微信群,moments:朋友圈,link:复制链接,poster:海报").search("eq"))
                .field(ExtField.of("shares", "分享次数", "int"))
                .field(ExtField.of("visits", "访问人次", "int"))
                .field(ExtField.of("createTime", "创建时间", "datetime").search("range"))
                .readOnly().hardDelete();

        ExtEntity ops = new ExtEntity("staffop", "员工操作日志", "kg_staff_op_log", "log_id", "kangaroo:staffop")
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("operatorName", "操作人", "text").search("like"))
                .field(ExtField.of("roleCode", "角色", "select").options("manager:换工主理人,keeper:小院主理人,staff:员工,online_staff:线上任务专员,merchant:合作商家,merchant_service:商家服务人,city_lead:城市负责人"))
                .field(ExtField.of("actionLabel", "操作", "text").search("like"))
                .field(ExtField.of("category", "类别", "select").options("general:一般,export:数据导出,fund:资金,grant:授权").search("eq"))
                .field(ExtField.of("detail", "内容", "text"))
                .field(ExtField.of("device", "设备", "text"))
                .field(ExtField.of("ok", "结果", "select").options("1:成功,0:失败").search("eq"))
                .field(ExtField.of("failReason", "失败原因", "text"))
                .field(ExtField.of("createTime", "时间", "datetime").search("range"))
                .readOnly().hardDelete();
        return Arrays.asList(plan, apply, dispatch, share, ops);
    }

    @Override
    public String targetType()
    {
        return "staffop";
    }

    @Override
    public String uphold(long targetId, String operator)
    {
        return "已确认为异常操作，请运营线下跟进（约谈 / 在后台调整授权）";
    }
}
