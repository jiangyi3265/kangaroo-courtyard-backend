package com.ruoyi.kangaroo.controller.app;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.manage.KgStaffTaskService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 员工端「管理员任务」。2026-11-03（V74）起发给全体员工、按岗位发：线上任务专员、商家服务人也会收到，
 * 接口要明确放行这两种授权（AppAuthInterceptor 默认会去掉）；合作商家不是员工，不放行。谁能看哪条任务按 kg_staff_grant 现查（KgStaffTaskService.OWN）。
 * 本城还没有门店的城市授权（城市负责人等）也能派到任务（任务挂在「城市（全城）」上），所以放行城市授权（cityGrant）。
 */
@RestController @RequestMapping("/app/staff/platform-tasks") @AppAuth(staff=true, onlineStaff=true, merchantService=true, cityGrant=true)
public class AppStaffTaskController {
    @Autowired private KgStaffTaskService tasks;
    @Autowired private AppRateLimiter limiter;
    @GetMapping public AjaxResult list(@RequestParam(required=false) String status,@RequestParam(required=false) Integer pageNum){return AjaxResult.success(tasks.staffList(AppUserContext.getUserId(),status,pageNum));}
    @GetMapping("/reminder") public AjaxResult reminder(){return AjaxResult.success(tasks.reminder(AppUserContext.getUserId()));}
    @PostMapping("/acknowledge") public AjaxResult acknowledge(@RequestBody List<Map<String,Object>> items){tasks.acknowledge(AppUserContext.getUserId(),items);return AjaxResult.success();}
    @GetMapping("/{id}") public AjaxResult detail(@PathVariable Long id){return AjaxResult.success(tasks.detail(AppUserContext.getUserId(),id));}
    @PostMapping("/{id}/submit") public AjaxResult submit(@PathVariable Long id,@RequestBody Map<String,Object> body){return AjaxResult.success(tasks.submit(AppUserContext.getUserId(),id,body));}
    /** 接收 / 回复、提问 / 申请延期 / 说明无法完成（body.action），见 KgStaffTaskService.act */
    @PostMapping("/{id}/act") public AjaxResult act(@PathVariable Long id,@RequestBody Map<String,Object> body){
        limiter.check("stafftask-act",String.valueOf(AppUserContext.getUserId()),60,60,"操作太频繁，请稍后再试");
        return AjaxResult.success(tasks.act(AppUserContext.getUserId(),id,body));
    }
    @PostMapping("/{id}/images") public AjaxResult image(@PathVariable Long id,@RequestParam("file") MultipartFile file){
        limiter.check("stafftask-image",String.valueOf(AppUserContext.getUserId()),30,60,"上传太频繁，请稍后再试");
        return AjaxResult.success(tasks.upload(AppUserContext.getUserId(),id,file));
    }
}
