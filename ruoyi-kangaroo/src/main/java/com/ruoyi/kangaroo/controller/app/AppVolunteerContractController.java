package com.ruoyi.kangaroo.controller.app;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.ext.user.KgVolunteerContractService;

/** 不记录请求正文日志：预览/确认正文中含有完整证件和联系方式。 */
@RestController
@RequestMapping("/app")
public class AppVolunteerContractController {
    @Autowired private KgVolunteerContractService contracts;
    @AppAuth @GetMapping("/application/{id}/volunteer-contract")
    public AjaxResult view(@PathVariable Long id) {return AjaxResult.success(contracts.view(AppUserContext.require().getUserId(),id));}
    @AppAuth @GetMapping("/application/{id}/volunteer-contract/records/{recordId}")
    public AjaxResult record(@PathVariable Long id,@PathVariable Long recordId) {return AjaxResult.success(contracts.record(AppUserContext.require().getUserId(),id,recordId));}
    @AppAuth @PostMapping("/application/{id}/volunteer-contract/preview")
    public AjaxResult preview(@PathVariable Long id,@RequestBody Map<String,Object> b) {return AjaxResult.success(contracts.preview(AppUserContext.require().getUserId(),id,b));}
    @AppAuth @PostMapping("/application/{id}/volunteer-contract/sign")
    public AjaxResult sign(@PathVariable Long id,@RequestBody Map<String,Object> b,HttpServletRequest request) {return AjaxResult.success("已提交，等待店家核对",contracts.submit(AppUserContext.require().getUserId(),id,b,IpUtils.getIpAddr(request)));}
    @AppAuth(staff=true,merchant=true) @GetMapping("/staff/applications/{id}/volunteer-contract")
    public AjaxResult staffView(@PathVariable Long id) {return AjaxResult.success(contracts.staffView(contracts.staffContext(id),id));}
    @AppAuth(staff=true,merchant=true) @PostMapping("/staff/applications/{id}/volunteer-contract/decision")
    public AjaxResult decision(@PathVariable Long id,@RequestBody Map<String,Object> b,HttpServletRequest request) {return AjaxResult.success(contracts.decide(contracts.staffContext(id),id,b,IpUtils.getIpAddr(request)));}
}
