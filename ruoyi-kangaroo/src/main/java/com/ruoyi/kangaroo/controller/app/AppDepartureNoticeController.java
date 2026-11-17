package com.ruoyi.kangaroo.controller.app;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgDepartureNotice;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;
@RestController @RequestMapping("/app/application/{appId}/departure-notice") @AppAuth
public class AppDepartureNoticeController {
 private final KgDepartureNotice service;
 public AppDepartureNoticeController(KgDepartureNotice service){this.service=service;}
 @GetMapping public AjaxResult read(@PathVariable Long appId,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return AjaxResult.success(service.read(AppUserContext.getUserId(),appId));}
 @PostMapping("/accept") public AjaxResult accept(@PathVariable Long appId,@RequestBody Map<String,Object> body){return AjaxResult.success(service.accept(AppUserContext.getUserId(),appId,body));}
 @GetMapping("/records/{id}") public AjaxResult record(@PathVariable Long appId,@PathVariable Long id,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return AjaxResult.success(service.record(AppUserContext.getUserId(),appId,id));}
}
