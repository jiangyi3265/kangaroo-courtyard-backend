package com.ruoyi.kangaroo.controller.app;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.ext.party.PartyService;
@RestController @RequestMapping("/app/party") public class AppPartyController {
 @Autowired private PartyService games;@Autowired private AppRateLimiter limiter;
 @AppAuth @PostMapping("/rooms") public AjaxResult create(@RequestBody Map<String,Object> b){limiter.check("party:create",String.valueOf(AppUserContext.getUserId()),20,3600,"开房较多，请稍后再试");return AjaxResult.success(games.create(AppUserContext.require(),String.valueOf(b.get("mode")),String.valueOf(b.get("requestId"))));}
 @AppAuth @GetMapping("/mine") public AjaxResult mine(){return AjaxResult.success(games.mine(AppUserContext.getUserId()));}
 @AppAuth(required=false) @GetMapping("/rooms/{code}") public AjaxResult get(@PathVariable String code){limiter.checkByIp("party:read",1200,60,"读取过于频繁");return AjaxResult.success(games.get(code,AppUserContext.currentUserId()));}
 @AppAuth @PostMapping("/rooms/{code}/{action}") public AjaxResult action(@PathVariable String code,@PathVariable String action,@RequestBody Map<String,Object> b){limiter.check("party:action",String.valueOf(AppUserContext.getUserId()),120,60,"操作过于频繁");return AjaxResult.success(games.command(code,AppUserContext.require(),action,b));}
}
