package com.ruoyi.kangaroo.controller.app;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.ext.wish.*;

@RestController @RequestMapping("/app/wishes") @AppAuth
public class AppWishController {
    @Autowired private WishService service;
    @Autowired private com.ruoyi.kangaroo.ext.KgJdbc db;
    @GetMapping @AppAuth(required=false)
    public AjaxResult list(@RequestParam(defaultValue="") String role,@RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="") String city,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String mode,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String min,@RequestParam(defaultValue="") String max,
        @RequestParam(defaultValue="latest") String sort,@RequestParam(defaultValue="1") int pageNum){
        return AjaxResult.success(service.list(AppUserContext.currentUserId(),role,status,city,category,mode,keyword,min,max,sort,pageNum));
    }
    @GetMapping("/{id}") @AppAuth(required=false)
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(id,AppUserContext.currentUserId()));}
    @PostMapping("/quote") @AppAuth(required=false)
    public AjaxResult quote(@RequestBody Map<String,Object>b){com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireWish(db);return AjaxResult.success(WishPricing.quote(b.get("cost"),"free".equals(b.get("mode"))));}
    @PostMapping public AjaxResult publish(@RequestBody Map<String,Object>b){return AjaxResult.success(service.publish(AppUserContext.getUserId(),b));}
    @PostMapping("/{id}/accept") public AjaxResult accept(@PathVariable long id){return AjaxResult.success(service.accept(id,AppUserContext.getUserId()));}
    @PostMapping("/{id}/actions/{action}") public AjaxResult action(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object>b){return AjaxResult.success(service.action(id,AppUserContext.getUserId(),action,b));}
    @GetMapping("/{id}/messages") public AjaxResult messages(@PathVariable long id,@RequestParam(defaultValue="0") long before){return AjaxResult.success(service.messages(id,AppUserContext.getUserId(),before));}
    @PostMapping("/{id}/messages") public AjaxResult message(@PathVariable long id,@RequestBody Map<String,Object>b){service.message(id,AppUserContext.getUserId(),b);return AjaxResult.success();}
    @PostMapping("/{id}/message-images") public AjaxResult messageImage(@PathVariable long id,@RequestParam MultipartFile file){return AjaxResult.success(service.messageImage(id,AppUserContext.getUserId(),file));}
    @GetMapping("/credentials") public AjaxResult credentials(){return AjaxResult.success(service.credentials(AppUserContext.getUserId(),false));}
    @PostMapping("/credentials") public AjaxResult credential(@RequestParam String type,@RequestParam(defaultValue="") String city,
        @RequestParam(defaultValue="") String validFrom,@RequestParam(defaultValue="") String validUntil,@RequestParam(defaultValue="") String permitType,
        @RequestParam MultipartFile file){service.credential(AppUserContext.getUserId(),type,city,validFrom,validUntil,permitType,file);return AjaxResult.success();}
}
