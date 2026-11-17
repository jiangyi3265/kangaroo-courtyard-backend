package com.ruoyi.kangaroo.controller.admin;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

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
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.stay.KgShopService;
import com.ruoyi.kangaroo.ext.stay.KgStayOrderService;

/**
 * 旅居后台动作：房源审核（上线 / 驳回 / 下架 / 恢复）、旅居订单与服务订单的退款审批。
 */
@RestController
@RequestMapping("/kangaroo/stayops")
public class KgStayAdminController extends BaseController
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private com.ruoyi.kangaroo.ext.stay.KgStayReadiness readiness;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgStayOrderService stayOrders;

    @Autowired
    private KgShopService shop;

    @Autowired
    private com.ruoyi.kangaroo.ext.stay.KgStayCommentService stayComments;

    @PreAuthorize("@ss.hasAnyPermi('kangaroo:roomtype:list,kangaroo:roomtype:add,kangaroo:roomtype:edit,kangaroo:staydiary:add,kangaroo:staydiary:edit')")
    @GetMapping("/options")
    public AjaxResult stayOptions(@RequestParam(defaultValue="") String keyword) {
        String q="%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return AjaxResult.success(db.list("select stay_id id,stay_name title,city,status from kg_stay where del_flag='0' and stay_name like :q escape '!' order by stay_id desc limit 100",p("q",q)));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stay:edit')")
    @Log(title="房源提交审核",businessType=BusinessType.UPDATE)
    @Transactional(rollbackFor=Exception.class)
    @PutMapping("/stay/{stayId}/submit")
    public AjaxResult submit(@PathVariable Long stayId) {
        Map<String,Object> row=db.must("select status from kg_stay where stay_id=:id and del_flag='0' for update",p("id",stayId),"房源不存在");
        if(!java.util.Arrays.asList("draft","rejected").contains(row.get("status"))) throw new ServiceException("当前房源状态不能提交审核，请刷新重试",409);
        readiness.requireSubmissionReady(stayId);
        db.update("update kg_stay set status='pending',submit_time=now(),audit_reason=null,update_by=:user,update_time=now() where stay_id=:id",p("id",stayId,"user",SecurityUtils.getUsername()));
        return AjaxResult.success("已提交审核，通过后才会在用户端展示");
    }

    @Autowired
    private com.ruoyi.kangaroo.ext.core.KgPrivateFiles files;

    /** 资质文件查看：签发 5 分钟有效的链接，后台页面在新窗口打开 */
    @PreAuthorize("@ss.hasPermi('kangaroo:stayqual:view')")
    @Log(title = "查看房源资质", businessType = BusinessType.OTHER)
    @GetMapping("/qual/{qualId}/file-url")
    public AjaxResult qualFile(@PathVariable Long qualId)
    {
        Map<String, Object> q = db.must("select file_key from kg_stay_qualification where qual_id = :id", p("id", qualId), "资质不存在");
        Map<String, Object> r = new java.util.HashMap<String, Object>();
        r.put("openUrl", files.sign(KgJdbc.asStr(q.get("fileKey")), 300));
        return AjaxResult.success("已生成 5 分钟有效的查看链接", r);
    }

    private static String reason(Map<String, Object> body)
    {
        String r = body == null ? null : KgJdbc.asStr(body.get("reason"));
        return r == null ? "" : r.trim();
    }

    private String move(Long stayId, String from, String to, String reason, String okMsg, String title)
    {
        Map<String, Object> s = db.must("select * from kg_stay where stay_id = :id and del_flag = '0'", p("id", stayId), "房源不存在");
        if (db.update("update kg_stay set status = :to, audit_reason = :r, audit_time = now(), update_by = :u, update_time = now() where stay_id = :id and status = :from",
                p("id", stayId, "to", to, "from", from, "r", reason.isEmpty() ? null : StaffSupport.cut(reason, 255), "u", SecurityUtils.getUsername())) == 0)
        {
            throw new ServiceException("房源状态已变化，请刷新后重试");
        }
        support.notifyStore(KgJdbc.asLong(s.get("storeId")), MessageCategory.SYSTEM, title,
                "房源「" + s.get("stayName") + "」" + okMsg + (reason.isEmpty() ? "" : "：" + reason), "stay", stayId, "/pages/staff/rooms?stayId=" + stayId);
        return okMsg;
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stay:audit')")
    @Log(title = "房源审核上线", businessType = BusinessType.UPDATE)
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/stay/{stayId}/approve")
    public AjaxResult approve(@PathVariable Long stayId)
    {
        readiness.requireReady(stayId);
        return AjaxResult.success(move(stayId, "pending", "online", "", "审核通过，已上线", "房源审核通过"));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stay:audit')")
    @Log(title = "房源驳回", businessType = BusinessType.UPDATE)
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/stay/{stayId}/reject")
    public AjaxResult reject(@PathVariable Long stayId, @RequestBody(required = false) Map<String, Object> body)
    {
        String r = reason(body);
        if (r.isEmpty())
        {
            throw new ServiceException("请填写驳回原因");
        }
        return AjaxResult.success(move(stayId, "pending", "rejected", r, "审核未通过", "房源审核未通过"));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stay:audit')")
    @Log(title = "房源下架", businessType = BusinessType.UPDATE)
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/stay/{stayId}/offline")
    public AjaxResult offline(@PathVariable Long stayId, @RequestBody(required = false) Map<String, Object> body)
    {
        String r = reason(body);
        if (r.isEmpty())
        {
            throw new ServiceException("请填写下架原因");
        }
        return AjaxResult.success(move(stayId, "online", "offline", r, "已被平台下架", "房源已下架"));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stay:audit')")
    @Log(title = "房源恢复上线", businessType = BusinessType.UPDATE)
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/stay/{stayId}/online")
    public AjaxResult online(@PathVariable Long stayId)
    {
        readiness.requireReady(stayId);
        return AjaxResult.success(move(stayId, "offline", "online", "", "已恢复上线", "房源已恢复上线"));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stay:query')")
    @GetMapping("/stay/{stayId}/visibility")
    public AjaxResult visibility(@PathVariable Long stayId) {
        Map<String,Object> stay = db.must("select stay_name,city,store_id,status from kg_stay where stay_id = :id and del_flag = '0'", p("id",stayId), "房源不存在");
        java.util.List<String> issues = readiness.issues(stayId);
        if (!"online".equals(stay.get("status"))) {
            issues.addAll(readiness.qualificationIssues(stayId));
            issues.add("房源当前为「"+com.ruoyi.kangaroo.ext.stay.KgStayStaffService.stayStatusLabel(String.valueOf(stay.get("status")))+"」，需审核上线后才会在用户端展示。");
        }
        Map<String,Object> context = new java.util.LinkedHashMap<>();
        context.put("title", "小程序展示检查");
        String heading="房源："+stay.get("stayName")+"（#"+stayId+"）\n展示城市："+stay.get("city")+"\n关联门店编号："+stay.get("storeId")+"\n房型的所属房源请选择「"+stay.get("stayName")+"」，不要填写门店编号。\n\n";
        context.put("text", heading+(issues.isEmpty() ? "已满足展示条件。指定日期不可订时，请检查房态价格、库存和筛选条件。" : String.join("\n",issues)));
        context.put("roomTypes",db.list("select room_type_id,type_name,status,stock,base_price_fen from kg_stay_room_type where stay_id=:id and del_flag='0' order by sort,room_type_id",p("id",stayId)));
        context.put("stayId",stayId);
        Map<String,Object> out = new java.util.LinkedHashMap<>();
        out.put("reviewContext",context);
        return AjaxResult.success(out);
    }

    @Autowired
    private com.ruoyi.kangaroo.ext.stay.KgStayVlogService vlogs;

    private String moveVlog(Long vlogId, String from, String to, String reason, String msg)
    {
        Map<String, Object> v = db.must("select * from kg_stay_vlog where vlog_id = :id and del_flag = '0'", p("id", vlogId), "视频不存在");
        if (db.update("update kg_stay_vlog set status = :to, audit_reason = :r, update_by = :u, update_time = now() where vlog_id = :id and status = :from",
                p("id", vlogId, "to", to, "from", from, "r", reason.isEmpty() ? null : StaffSupport.cut(reason, 255), "u", SecurityUtils.getUsername())) == 0)
        {
            throw new ServiceException("视频状态已变化，请刷新后重试");
        }
        support.notifyStore(KgJdbc.asLong(v.get("storeId")), MessageCategory.SYSTEM, "Vlog " + msg,
                "「" + v.get("title") + "」" + msg + (reason.isEmpty() ? "" : "：" + reason), "vlog", vlogId, "/pages/staff/vlog?s=2&vlogId=" + vlogId);
        return msg;
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stayvlog:audit')")
    @Log(title = "Vlog 审核通过", businessType = BusinessType.UPDATE)
    @PutMapping("/vlog/{vlogId}/approve")
    public AjaxResult vlogApprove(@PathVariable Long vlogId)
    {
        // 和门店端审核通过同一道检查：住客投稿要先取得出镜授权、关联本店订单（之前后台通过不查）
        vlogs.requireGuestReady(db.must("select * from kg_stay_vlog where vlog_id = :id and del_flag = '0'", p("id", vlogId), "视频不存在"));
        return AjaxResult.success(moveVlog(vlogId, "pending", "online", "", "已通过平台审核"));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stayvlog:audit')")
    @Log(title = "Vlog 驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/vlog/{vlogId}/reject")
    public AjaxResult vlogReject(@PathVariable Long vlogId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(moveVlog(vlogId, "pending", "rejected", reason(body), "未通过平台审核"));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stayvlog:audit')")
    @Log(title = "Vlog 下架", businessType = BusinessType.UPDATE)
    @PutMapping("/vlog/{vlogId}/offline")
    public AjaxResult vlogOffline(@PathVariable Long vlogId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(moveVlog(vlogId, "online", "offline", reason(body), "已被平台下架"));
    }

    // ---------------------------------------------------------------- 小院评论（2026-10-31，V65）：由平台审

    @PreAuthorize("@ss.hasPermi('kangaroo:staycomment:audit')")
    @Log(title = "小院评论通过", businessType = BusinessType.UPDATE)
    @PutMapping("/staycomment/{commentId}/approve")
    public AjaxResult stayCommentApprove(@PathVariable Long commentId)
    {
        return AjaxResult.success(stayComments.approve(commentId, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:staycomment:audit')")
    @Log(title = "小院评论驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/staycomment/{commentId}/reject")
    public AjaxResult stayCommentReject(@PathVariable Long commentId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(stayComments.reject(commentId, reason(body), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:staycomment:audit')")
    @Log(title = "小院评论下架", businessType = BusinessType.UPDATE)
    @PutMapping("/staycomment/{commentId}/offline")
    public AjaxResult stayCommentOffline(@PathVariable Long commentId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(stayComments.offline(commentId, reason(body), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stayorder:refund')")
    @Log(title = "旅居订单同意退款", businessType = BusinessType.UPDATE)
    @PutMapping("/stayorder/{orderNo}/refund-approve")
    public AjaxResult stayRefundApprove(@PathVariable String orderNo)
    {
        return AjaxResult.success(stayOrders.approveRefund(orderNo, SecurityUtils.getUsername(), null));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:stayorder:refund')")
    @Log(title = "旅居订单驳回退款", businessType = BusinessType.UPDATE)
    @PutMapping("/stayorder/{orderNo}/refund-reject")
    public AjaxResult stayRefundReject(@PathVariable String orderNo, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(stayOrders.rejectRefund(orderNo, reason(body), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:shoporder:refund')")
    @Log(title = "服务订单同意退款", businessType = BusinessType.UPDATE)
    @PutMapping("/shoporder/{orderNo}/refund-approve")
    public AjaxResult shopRefundApprove(@PathVariable String orderNo)
    {
        return AjaxResult.success(shop.approveRefund(orderNo, SecurityUtils.getUsername(), null));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:shoporder:refund')")
    @Log(title = "服务订单驳回退款", businessType = BusinessType.UPDATE)
    @PutMapping("/shoporder/{orderNo}/refund-reject")
    public AjaxResult shopRefundReject(@PathVariable String orderNo, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(shop.rejectRefund(orderNo, reason(body), SecurityUtils.getUsername()));
    }
}
