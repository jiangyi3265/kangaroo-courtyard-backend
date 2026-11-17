package com.ruoyi.kangaroo.controller.admin;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.kangaroo.security.admin.AdminCityScopeService;
@RestController
@RequestMapping("/kangaroo/admin-city-scope")
public class KgAdminCityScopeController {
    private final AdminCityScopeService scope;
    public KgAdminCityScopeController(AdminCityScopeService scope) {this.scope=scope;}
    @GetMapping("/{userId}") public AjaxResult get(@PathVariable long userId) {return AjaxResult.success(scope.get(userId));}
    public static class Grant { public boolean allCities; public List<Long> cityIds; }
    @PutMapping("/{userId}") @Log(title="后台业务城市授权",businessType=BusinessType.GRANT)
    public AjaxResult save(@PathVariable long userId,@RequestBody Grant grant) {scope.save(userId,grant.allCities,grant.cityIds);return AjaxResult.success();}
}
