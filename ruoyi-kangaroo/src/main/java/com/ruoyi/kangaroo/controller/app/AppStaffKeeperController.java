package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgKeeperActivityReview;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskReopen;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 2026-11-03 主人：「小院主理人，审核活动，线上任务，几天，是预期，过期了线上专员有个，增加一个继续开启的功能，线上任务的权限」。
 *   /app/staff/keeper/activities                本店待审核的活动（小院主理人；城市负责人顶上）；
 *   /app/staff/keeper/activities/{id}/audit     通过（pass = true）/ 驳回（pass = false，reason 必填）；
 *   /app/staff/online/tasks/{jobId}/reopen      到期关闭的线上任务继续开启（body.deadline = 新的预计完成时间 yyyy-MM-dd HH:mm）。
 * 角色在控制器（事务外）查，拒绝时写的失败日志不随业务回滚；门店、状态、自己不能审自己在服务里查。
 */
@RestController
@RequestMapping("/app/staff")
public class AppStaffKeeperController
{
    @Autowired
    private KgKeeperActivityReview review;

    @Autowired
    private KgOnlineTaskReopen reopen;

    @Autowired
    private AppRateLimiter limiter;

    private static Boolean bool(Map<String, Object> b, String k)
    {
        String v = b == null ? null : KgJdbc.asStr(b.get(k));
        if ("true".equalsIgnoreCase(v) || "1".equals(v))
        {
            return Boolean.TRUE;
        }
        return "false".equalsIgnoreCase(v) || "0".equals(v) ? Boolean.FALSE : null;
    }

    @AppAuth(staff = true)
    @GetMapping("/keeper/activities")
    public AjaxResult keeperActivities(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize)
    {
        return AjaxResult.success(review.pending(review.ctx(storeId), pageNum, pageSize));
    }

    @AppAuth(staff = true)
    @PostMapping("/keeper/activities/{activityId:[0-9]+}/audit")
    public AjaxResult keeperActivityAudit(@PathVariable Long activityId, @RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = review.ctx(storeId);
        Map<String, Object> out = review.audit(c, activityId, bool(body, "pass"), body == null ? null : KgJdbc.asStr(body.get("reason")));
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }

    @AppAuth(staff = true, onlineStaff = true, merchantService = true)
    @PostMapping("/online/tasks/{jobId:[0-9]+}/reopen")
    public AjaxResult onlineReopen(@PathVariable Long jobId, @RequestParam(required = false) Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = reopen.ctx(storeId);
        limiter.check("staff:online-reopen", String.valueOf(c.uid()), 20, 3600, "操作太频繁了，稍后再试");
        Map<String, Object> out = reopen.reopenByStaff(c, jobId, body == null ? null : body.get("deadline"));
        return AjaxResult.success(String.valueOf(out.get("message")), out);
    }
}
