package com.ruoyi.kangaroo.controller.admin;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.manage.KgStaffTaskService;

/** 员工任务派发（V62；2026-11-03 V74 主人：要有流转、能一键发全体员工、能按岗位发）。回复 / 改截止 / 转派 / 关闭 / 退回重新处理要 kangaroo:stafftask:handle */
@RestController @RequestMapping("/kangaroo/staff-task")
public class KgStaffTaskAdminController {
    @Autowired private KgStaffTaskService tasks;
    @GetMapping("/list") @PreAuthorize("@ss.hasPermi('kangaroo:stafftask:list')")
    public AjaxResult list(@RequestParam(required=false) String status,@RequestParam(required=false) String keyword,@RequestParam(required=false) Long batchId,
                           @RequestParam(required=false) String roleCode,@RequestParam(required=false) Integer pageNum){return AjaxResult.success(tasks.adminList(status,keyword,batchId,roleCode,pageNum));}
    @GetMapping("/batches") @PreAuthorize("@ss.hasPermi('kangaroo:stafftask:list')")
    public AjaxResult batches(@RequestParam(required=false) String keyword,@RequestParam(required=false) Integer pageNum){return AjaxResult.success(tasks.batches(keyword,pageNum));}
    @GetMapping("/recipients") @PreAuthorize("@ss.hasAnyPermi('kangaroo:stafftask:add,kangaroo:stafftask:handle')")
    public AjaxResult recipients(@RequestParam(required=false) String keyword,@RequestParam(required=false) String roleCode,@RequestParam(required=false) Integer pageNum){return AjaxResult.success(tasks.recipients(keyword,roleCode,pageNum));}
    @GetMapping("/roles") @PreAuthorize("@ss.hasPermi('kangaroo:stafftask:add')")
    public AjaxResult roles(){return AjaxResult.success(tasks.roles());}
    @GetMapping("/audience") @PreAuthorize("@ss.hasPermi('kangaroo:stafftask:add')")
    public AjaxResult audience(@RequestParam String mode,@RequestParam(required=false) String roleCode,@RequestParam(required=false) String city,@RequestParam(required=false) Long storeId){return AjaxResult.success(tasks.audience(mode,roleCode,city,storeId));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:stafftask:query')")
    public AjaxResult detail(@PathVariable Long id){return AjaxResult.success(tasks.detail(null,id));}
    @PostMapping @PreAuthorize("@ss.hasPermi('kangaroo:stafftask:add')") @Log(title="派发员工任务",businessType=BusinessType.INSERT)
    public AjaxResult assign(@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.assign(body,SecurityUtils.getUsername(),SecurityUtils.getUserId()));}
    @PostMapping("/{id}/review") @PreAuthorize("@ss.hasPermi('kangaroo:stafftask:audit')") @Log(title="审核员工任务",businessType=BusinessType.UPDATE)
    public AjaxResult review(@PathVariable Long id,@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.review(id,body,SecurityUtils.getUsername(),SecurityUtils.getUserId()));}
    @PostMapping("/{id}/handle") @PreAuthorize("@ss.hasPermi('kangaroo:stafftask:handle')") @Log(title="处理员工任务",businessType=BusinessType.UPDATE)
    public AjaxResult handle(@PathVariable Long id,@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.handle(id,body,SecurityUtils.getUsername(),SecurityUtils.getUserId()));}
}
