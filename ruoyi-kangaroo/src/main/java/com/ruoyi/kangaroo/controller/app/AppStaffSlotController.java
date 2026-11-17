package com.ruoyi.kangaroo.controller.app;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.ext.staff.KgStaffSlotService;
import com.ruoyi.kangaroo.ext.KgJdbc;

@RestController
@RequestMapping("/app/staff/interview-slots")
@AppAuth(staff=true, merchant=true)
public class AppStaffSlotController {
    private final KgStaffSlotService slots;
    public AppStaffSlotController(KgStaffSlotService slots) { this.slots=slots; }
    @GetMapping public AjaxResult list(@RequestParam Long storeId) { return AjaxResult.success(slots.list(storeId)); }
    @PostMapping public AjaxResult add(@RequestBody Map<String,Object> body) { return AjaxResult.success(slots.add(KgJdbc.asLong(body.get("storeId")),body)); }
    @PostMapping("/{id}/close") public AjaxResult close(@PathVariable Long id,@RequestBody Map<String,Object> body) { slots.close(KgJdbc.asLong(body.get("storeId")),id); return AjaxResult.success("时段已关闭"); }
}
