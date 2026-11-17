package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.manage.KgHonorService;
import com.ruoyi.kangaroo.ext.manage.KgManageService;
import com.ruoyi.kangaroo.ext.manage.KgWalletService;

/** 经营模块后台动作：周报退回、结算申请审核 / 驳回 / 登记打款 / 通过后退回、生日礼物发货、申报处理 */
@RestController
@RequestMapping("/kangaroo/manage")
public class KgManageAdminController extends BaseController
{
    @Autowired
    private KgManageService manage;

    @Autowired
    private KgWalletService wallet;

    @Autowired
    private KgHonorService honor;

    private static String reason(Map<String, Object> body)
    {
        String r = body == null ? null : KgJdbc.asStr(body.get("reason"));
        return r == null ? "" : r.trim();
    }

    private static String op()
    {
        return "admin:" + SecurityUtils.getUsername();
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:weeklyreport:audit')")
    @Log(title = "周报退回", businessType = BusinessType.UPDATE)
    @PutMapping("/weekly/{id}/return")
    public AjaxResult weeklyReturn(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(manage.weeklyReturn(id, reason(body), op()));
    }

    /** 周报的费用支出与待协同事项（连同补充说明、风险、凭证），列表「费用与协同」弹窗显示 */
    @PreAuthorize("@ss.hasPermi('kangaroo:weeklyreport:query')")
    @GetMapping("/weekly/{id}/review-context")
    public AjaxResult weeklyContext(@PathVariable Long id)
    {
        return AjaxResult.success(KgJdbc.p("reviewContext", manage.weeklyReviewContext(id)));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:withdraw:audit')")
    @Log(title = "结算申请通过", businessType = BusinessType.UPDATE)
    @PutMapping("/withdraw/{id}/approve")
    public AjaxResult approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(wallet.audit(id, "approve", reason(body), op()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:withdraw:audit')")
    @Log(title = "结算申请驳回", businessType = BusinessType.UPDATE)
    @PutMapping("/withdraw/{id}/reject")
    public AjaxResult reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(wallet.audit(id, "reject", reason(body), op()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:withdraw:audit')")
    @Log(title = "结算线下打款登记", businessType = BusinessType.UPDATE)
    @PutMapping("/withdraw/{id}/paid")
    public AjaxResult paid(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        // 申请人余额为负时默认 409；后台确认框确认后带 confirmShortfall=true 再提交
        boolean confirm = body != null && Boolean.parseBoolean(String.valueOf(body.get("confirmShortfall")));
        return AjaxResult.success(wallet.audit(id, "paid", reason(body), op(), confirm));
    }

    /** 已通过、还没打款的申请退回（必填原因，金额回到申请人余额并通知本人） */
    @PreAuthorize("@ss.hasPermi('kangaroo:withdraw:audit')")
    @Log(title = "结算申请退回", businessType = BusinessType.UPDATE)
    @PutMapping("/withdraw/{id}/revoke")
    public AjaxResult revoke(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(wallet.audit(id, "revoke", reason(body), op()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:withdraw:audit')")
    @Log(title = "分佣入账", businessType = BusinessType.UPDATE)
    @PostMapping("/commission/accrue")
    public AjaxResult accrue()
    {
        // 与每 10 分钟的定时入账或另一次点击撞在一起时，数据库会把其中一个事务判为死锁牺牲者并整体回滚；
        // 入账是幂等的（唯一键），所以直接重跑即可，最多 3 次（2026-09-26 回归发现并发点击返回 500）
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                return AjaxResult.success("新增流水 " + wallet.accrue() + " 条");
            }
            catch (org.springframework.dao.PessimisticLockingFailureException e)
            {
                if (attempt >= 3)
                {
                    throw new com.ruoyi.common.exception.ServiceException("另一次入账计算正在进行，请稍后再点「重新计算」", 409);
                }
            }
        }
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:giftclaim:audit')")
    @Log(title = "生日礼物发货", businessType = BusinessType.UPDATE)
    @PutMapping("/giftclaim/{id}/ship")
    public AjaxResult ship(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(honor.ship(id, reason(body), op()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:appeal:audit')")
    @Log(title = "申报处理", businessType = BusinessType.UPDATE)
    @PutMapping("/appeal/{id}/resolve")
    public AjaxResult resolve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(honor.handleAppeal(id, true, reason(body), op()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:appeal:audit')")
    @Log(title = "申报不予受理", businessType = BusinessType.UPDATE)
    @PutMapping("/appeal/{id}/reject")
    public AjaxResult rejectAppeal(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(honor.handleAppeal(id, false, reason(body), op()));
    }
}
