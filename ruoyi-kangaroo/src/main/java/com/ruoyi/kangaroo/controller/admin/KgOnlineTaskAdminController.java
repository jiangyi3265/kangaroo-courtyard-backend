package com.ruoyi.kangaroo.controller.admin;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskReopen;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService;

@RestController
@RequestMapping("/kangaroo/online-task")
public class KgOnlineTaskAdminController {
    private final KgOnlineTaskService tasks;
    public KgOnlineTaskAdminController(KgOnlineTaskService tasks) { this.tasks=tasks; }
    @PreAuthorize("@ss.hasPermi('kangaroo:job:add')")
    @Log(title="线上任务",businessType=BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody Map<String,Object> body) { return AjaxResult.success(tasks.createByAdmin(body)); }
    // 2026-11-03 主人：截止时间是预计完成时间，到期自动关闭后可以「继续开启」（岗位管理里已关闭的线上任务）。只认到期关闭的，规则见 KgOnlineTaskReopen
    @org.springframework.beans.factory.annotation.Autowired private KgOnlineTaskReopen reopen;
    @PreAuthorize("@ss.hasPermi('kangaroo:job:publish')")
    @Log(title="线上任务继续开启",businessType=BusinessType.UPDATE)
    @PostMapping("/{jobId}/reopen")
    public AjaxResult reopen(@PathVariable Long jobId,@RequestBody(required=false) Map<String,Object> body) {
        Map<String,Object> out=reopen.reopenByAdmin(jobId,body==null?null:body.get("deadline"),com.ruoyi.common.utils.SecurityUtils.getUsername());
        return AjaxResult.success(String.valueOf(out.get("message")),out);
    }
}
