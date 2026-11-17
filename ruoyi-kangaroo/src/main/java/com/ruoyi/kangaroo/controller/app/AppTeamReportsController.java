package com.ruoyi.kangaroo.controller.app;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.operations.OpsTeamReports;
import com.ruoyi.kangaroo.security.*;
@RestController @RequestMapping("/app/team-reports") @AppAuth
public class AppTeamReportsController {
    @Autowired private OpsTeamReports reports;
    @GetMapping("/access") public AjaxResult access(){return AjaxResult.success(reports.access(AppUserContext.getUserId()));}
    @GetMapping public AjaxResult list(@RequestParam String kind,@RequestParam String month,@RequestParam(required=false) Long cityId,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) Integer pageNum){return AjaxResult.success(reports.list(AppUserContext.getUserId(),kind,month,cityId,keyword,pageNum));}
    @GetMapping("/{kind}/{id}") public AjaxResult detail(@PathVariable String kind,@PathVariable long id){return AjaxResult.success(reports.detail(AppUserContext.getUserId(),kind,id));}
}
