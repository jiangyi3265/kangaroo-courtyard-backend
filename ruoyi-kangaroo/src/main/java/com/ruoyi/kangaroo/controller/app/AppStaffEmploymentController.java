package com.ruoyi.kangaroo.controller.app;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.staff.KgEmploymentService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;

@RestController
@RequestMapping("/app/staff")
@AppAuth(staff=true,merchant=true,merchantService=true)
public class AppStaffEmploymentController {
    @Autowired private KgEmploymentService employment;

    @GetMapping("/employments")
    public AjaxResult list(@RequestParam Long storeId,@RequestParam(defaultValue="1") int pageNum) {
        return AjaxResult.success(employment.list(AppUserContext.requireStaff(),storeId,pageNum));
    }

    @GetMapping("/applications/{appId}/employment")
    public AjaxResult detail(@PathVariable Long appId) {
        return AjaxResult.success(employment.detail(AppUserContext.requireStaff(),appId));
    }

    @PostMapping("/applications/{appId}/employment")
    public AjaxResult save(@PathVariable Long appId,@RequestBody Map<String,Object> body) {
        return AjaxResult.success(employment.save(AppUserContext.requireStaff(),appId,body));
    }
}
