package com.ruoyi.kangaroo.controller.admin;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgReportHandler;
import com.ruoyi.kangaroo.ext.pay.KgPayService;

/**
 * 共享能力的后台动作：支付单退款、举报处理。
 */
@RestController
@RequestMapping("/kangaroo/core")
public class KgCoreAdminController extends BaseController
{
    @Autowired
    private KgPayService payService;

    @Autowired
    private KgJdbc db;

    @Autowired(required = false)
    private List<KgReportHandler> reportHandlers;

    /** 门店提请处理的评价举报处理完后，按员工身份告诉提请人结果 */
    @Autowired
    private com.ruoyi.kangaroo.ext.staff.KgStaffReviewService staffReviews;

    /** 仅举报处置权限可读原文；不会把整个用户档案或整段私聊无边界暴露给列表读取者。 */
    @PreAuthorize("@ss.hasPermi('kangaroo:report:handle')")
    @Log(title = "查看举报原文", businessType = BusinessType.OTHER)
    @GetMapping("/report/{reportId}/context")
    public AjaxResult reportContext(@PathVariable Long reportId)
    {
        Map<String, Object> report = db.must("select target_type,target_id,create_time from kg_report where report_id=:id and del_flag='0'", KgJdbc.p("id", reportId), "举报不存在");
        String type = String.valueOf(report.get("targetType"));
        String query;
        switch (type)
        {
            case "post": query="select title,content,images,status from kg_post where post_id=:id"; break;
            case "comment": query="select content,status from kg_post_comment where comment_id=:id"; break;
            case "vlog": query="select title,description content,link,status from kg_stay_vlog where vlog_id=:id"; break;
            case "vlog_comment": query="select content,reply,status from kg_stay_vlog_comment where comment_id=:id"; break;
            case "review": query="select content,rating,images,reply,status from kg_review where review_id=:id"; break;
            case "stay_comment": query="select content,images,video_url link,status from kg_stay_comment where comment_id=:id"; break;
            case "staffop": query="select detail content,create_time from kg_staff_op_log where log_id=:id"; break;
            case "user": query="select nickname title,status from kg_app_user where user_id=:id"; break;
            case "chat": query=null; break;
            default: throw new ServiceException("该举报类型未接入原文查询", 422);
        }
        Map<String, Object> params=KgJdbc.p("id", report.get("targetId"), "at", report.get("createTime"));
        Map<String, Object> context=new java.util.LinkedHashMap<String, Object>();
        context.put("title", "举报对象原文（当前保留内容）");
        if ("chat".equals(type))
        {
            List<Map<String,Object>> rows=db.list("select sender_id,content,create_time,status from kg_chat_msg where conv_id=:id and create_time<=:at order by msg_id desc limit 20", params);
            java.util.Collections.reverse(rows);
            StringBuilder text=new StringBuilder("举报提交时最近20条会话记录：\n");
            for (Map<String,Object> row:rows) text.append(row.get("createTime")).append(" 用户#").append(row.get("senderId")).append(" [").append(row.get("status")).append("] ").append(row.get("content")).append('\n');
            context.put("text",text.toString());
        }
        else
        {
            Map<String,Object> row=db.one(query,params);
            StringBuilder text=new StringBuilder();
            if(row==null) text.append("原始对象已删除或不存在，不能据此认定违规内容。");
            else
            {
                for(String[] field:new String[][]{{"title","标题"},{"content","原文"},{"reply","回复"},{"rating","评分"},{"link","来源链接"},{"createTime","时间"}})
                    if(row.get(field[0])!=null) text.append(field[1]).append("：").append(row.get(field[0])).append('\n');
                context.put("images",row.get("images"));
            }
            context.put("text",text.toString());
        }
        return AjaxResult.success(KgJdbc.p("reviewContext", context));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:payorder:query')")
    @GetMapping("/payorder/{payNo}/refund-progress")
    public AjaxResult refundProgress(@PathVariable String payNo) {
        return AjaxResult.success(payService.refundProgress(payNo));
    }

    /** 把支付单剩余可退金额全部退回（部分退款走各业务自己的退款审批） */
    @PreAuthorize("@ss.hasPermi('kangaroo:payorder:refund')")
    @Log(title = "支付单退款", businessType = BusinessType.UPDATE)
    @PutMapping("/payorder/{payNo}/refund")
    public AjaxResult refund(@PathVariable String payNo, @RequestBody(required = false) Map<String, Object> body)
    {
        String reason = body == null ? null : KgJdbc.asStr(body.get("reason"));
        if (reason == null || reason.trim().isEmpty())
        {
            throw new ServiceException("退款必须填写原因");
        }
        Map<String, Object> pay = payService.byNo(payNo);
        long left = ((Number) pay.get("amount")).longValue() - ((Number) pay.get("refundAmount")).longValue();
        Map<String, Object> result = payService.refund(payNo, left, reason.trim(), SecurityUtils.getUsername());
        // 微信渠道这一步只是登记了退款申请：由定时任务向微信发起，微信确认后支付单才变成「已退款」。
        // 原先回的是默认的「操作成功」，页面据此提示「全额退款成功」，运营会以为钱已经退回去了
        if ("processing".equals(result.get("refundStatus")))
        {
            return AjaxResult.success("已提交微信退款申请，微信确认后这笔支付会变成「已退款」；可以点「退款进度」查看", result);
        }
        return AjaxResult.success("全额退款成功", result);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:report:handle')")
    @Log(title = "举报处理", businessType = BusinessType.UPDATE)
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/report/{reportId}/uphold")
    public AjaxResult uphold(@PathVariable Long reportId, @RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> r = pending(reportId);
        String note = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
        String action = "该类型暂无自动处置，请人工处理";
        if (reportHandlers != null)
        {
            for (KgReportHandler h : reportHandlers)
            {
                if (h.targetType().equals(r.get("targetType")))
                {
                    action = h.uphold(((Number) r.get("targetId")).longValue(), SecurityUtils.getUsername());
                }
            }
        }
        close(reportId, "upheld", (note.trim().isEmpty() ? "" : note.trim() + "；") + action);
        return AjaxResult.success(action);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:report:handle')")
    @Log(title = "举报处理", businessType = BusinessType.UPDATE)
    @PutMapping("/report/{reportId}/reject")
    public AjaxResult reject(@PathVariable Long reportId, @RequestBody(required = false) Map<String, Object> body)
    {
        pending(reportId);
        String note = body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
        close(reportId, "rejected", note.trim());
        return AjaxResult.success();
    }

    private Map<String, Object> pending(Long reportId)
    {
        Map<String, Object> r = db.must("select * from kg_report where report_id = :id and del_flag = '0'",
                KgJdbc.p("id", reportId), "举报不存在");
        if (!"pending".equals(r.get("status")))
        {
            throw new ServiceException("这条举报已经处理过了");
        }
        return r;
    }

    private void close(Long reportId, String status, String note)
    {
        int n = db.update("update kg_report set status = :s, handle_note = :n, handled_by = :u, handled_time = now(), "
                + "update_time = now() where report_id = :id and status = 'pending'",
                KgJdbc.p("id", reportId, "s", status, "n", note.length() > 250 ? note.substring(0, 250) : note,
                        "u", SecurityUtils.getUsername()));
        if (n == 0)
        {
            throw new ServiceException("这条举报刚刚被别人处理了，请刷新");
        }
        // 门店在小程序里提请处理的差评（target_type='review'、create_by 以 staff: 开头），处理结果发给提请的员工；
        // 其他举报方法内部直接跳过。原先门店提请后再也收不到结果，只能自己去评价列表里看
        staffReviews.notifyReportClosed(reportId);
    }
}
