package com.ruoyi.kangaroo.controller.app;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgMemberService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/app/member")
public class AppMemberController {
    private final KgMemberService service;
    public AppMemberController(KgMemberService service) {this.service=service;}
    @AppAuth(required=false) @GetMapping("/levels")
    public AjaxResult levels(){return AjaxResult.success(service.levels());}
    @AppAuth(required=false) @GetMapping("/benefits")
    public AjaxResult list(@RequestParam(required=false) String keyword,@RequestParam(required=false) String city,@RequestParam(required=false) String category,@RequestParam(defaultValue="1") int pageNum) {
        return AjaxResult.success(service.benefits(AppUserContext.currentUserId(),keyword,city,category,pageNum));
    }
    @AppAuth(required=false) @GetMapping("/benefits/{id}")
    public AjaxResult detail(@PathVariable Long id){return AjaxResult.success(service.benefit(AppUserContext.currentUserId(),id));}
}
