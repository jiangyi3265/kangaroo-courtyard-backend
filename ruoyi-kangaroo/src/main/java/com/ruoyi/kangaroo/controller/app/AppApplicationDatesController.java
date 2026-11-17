package com.ruoyi.kangaroo.controller.app;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.staff.KgApplicationDatesService;
import com.ruoyi.kangaroo.security.*;

@RestController
@RequestMapping("/app")
@AppAuth
public class AppApplicationDatesController {
    @Autowired private KgApplicationDatesService dates;
    @GetMapping("/application/{appId}/dates")
    public AjaxResult userDates(@PathVariable Long appId) { return AjaxResult.success(dates.detail(AppUserContext.require(),appId,false)); }
    @PostMapping("/application/{appId}/dates/confirm")
    public AjaxResult confirm(@PathVariable Long appId,@RequestBody Map<String,Object> body) { return AjaxResult.success(dates.confirm(AppUserContext.require(),appId,body)); }
    @AppAuth(staff=true,merchant=true,cityLeadAfterSwitch=true)
    @GetMapping("/staff/applications/{appId}/dates")
    public AjaxResult staffDates(@PathVariable Long appId) { return AjaxResult.success(dates.detail(AppUserContext.requireStaff(),appId,true)); }
    @AppAuth(staff=true,merchant=true,cityLeadAfterSwitch=true)
    @PostMapping("/staff/applications/{appId}/dates")
    public AjaxResult propose(@PathVariable Long appId,@RequestBody Map<String,Object> body) { return AjaxResult.success(dates.propose(AppUserContext.requireStaff(),appId,body)); }
}
