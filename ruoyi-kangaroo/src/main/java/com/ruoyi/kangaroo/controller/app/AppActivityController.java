package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.community.KgPostService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 社区活动（用户端）：列表 / 推荐 / 详情游客可看；报名、收藏、订阅、我的活动、发起与管理需要登录。
 */
@RestController
@RequestMapping("/app/activities")
public class AppActivityController
{
    @Autowired
    private KgActivityService activities;

    @Autowired
    private AppRateLimiter limiter;

    @Autowired
    private KgPostService posts;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    @AppAuth(required = false)
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String source, @RequestParam(required = false) String city,
            @RequestParam(required = false) String category)
    {
        return AjaxResult.success(activities.list(AppUserContext.currentUserId(), source, city, category));
    }

    @AppAuth(required = false)
    @GetMapping("/hots")
    public AjaxResult hots(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(activities.hots(city));
    }

    @AppAuth(required = false)
    @GetMapping("/travel")
    public AjaxResult travel(@RequestParam(required = false) String city, @RequestParam(defaultValue = "1") int pageNum) {
        return AjaxResult.success(activities.travel(city, pageNum));
    }

    @AppAuth(required = false)
    @GetMapping("/recommend")
    public AjaxResult recommend(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(activities.recommend(AppUserContext.currentUserId(), city));
    }

    /** 房源详情「小院活动」：这家门店主办的活动，进行中的 + 往期的（2026-11-03，游客可看，不用关注门店） */
    @AppAuth(required = false)
    @GetMapping("/store/{storeId}")
    public AjaxResult forStore(@PathVariable Long storeId)
    {
        return AjaxResult.success(activities.forStore(storeId));
    }

    @AppAuth(required = false)
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id, @RequestParam(required = false) String channel, @RequestParam(required = false) String checkin)
    {
        return AjaxResult.success(activities.detail(AppUserContext.currentUserId(), id, channel, checkin));
    }

    @AppAuth
    @PostMapping("/{id}/signup")
    public AjaxResult signup(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("activity:signup", String.valueOf(me), 20, 600, "操作太频繁了，稍后再试");
        return AjaxResult.success(activities.signup(me, id, s(body, "channel"), body==null?null:com.ruoyi.kangaroo.ext.KgJdbc.asLong(body.get("memberWalletId"))));
    }

    @AppAuth
    @PostMapping("/{id}/favorite")
    public AjaxResult favorite(@PathVariable Long id)
    {
        return AjaxResult.success(activities.favorite(AppUserContext.getUserId(), id));
    }

    /** 我收藏的活动（「我的活动 → 收藏」页签、「我的收藏」入口）：带报名截止时间，已结束的排在后面 */
    @AppAuth
    @GetMapping("/favorites")
    public AjaxResult favorites()
    {
        return AjaxResult.success(activities.favorites(AppUserContext.getUserId()));
    }

    @AppAuth
    @PostMapping("/subscribe")
    public AjaxResult subscribe(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(activities.subscribe(AppUserContext.getUserId(), s(body, "city"), Boolean.parseBoolean(s(body, "on"))));
    }

    @AppAuth
    @GetMapping("/signups/{signupId}")
    public AjaxResult ticket(@PathVariable Long signupId)
    {
        return AjaxResult.success(activities.ticket(AppUserContext.getUserId(), signupId));
    }

    @AppAuth
    @PostMapping("/signups/{signupId}/cancel")
    public AjaxResult cancel(@PathVariable Long signupId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(activities.cancel(AppUserContext.getUserId(), signupId, s(body, "reason")));
    }

    @AppAuth
    @DeleteMapping("/signups/{signupId}/record")
    public AjaxResult hideSignup(@PathVariable Long signupId)
    {
        return AjaxResult.success(activities.hideSignup(AppUserContext.getUserId(), signupId));
    }

    @AppAuth
    @PutMapping("/signups/{signupId}/remind")
    public AjaxResult remind(@PathVariable Long signupId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(activities.setRemind(AppUserContext.getUserId(), signupId, s(body, "remind")));
    }

    @AppAuth
    @GetMapping("/mine")
    public AjaxResult mine(@RequestParam(defaultValue = "joined") String tab)
    {
        return AjaxResult.success(activities.mine(AppUserContext.getUserId(), tab));
    }

    @AppAuth
    @PostMapping
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("activity:create", String.valueOf(me), 5, 3600, "发起太频繁了，稍后再试");
        Long id = activities.createByUser(me, body, null);
        return AjaxResult.success(activities.manageAsUser(me, id));
    }

    /** 发起活动时勾选「同步到社区」：活动建好后再调本接口，发一条关联这场活动的找搭子动态 */
    @AppAuth
    @PostMapping("/{id}/sync-community")
    public AjaxResult syncCommunity(@PathVariable Long id)
    {
        Map<String, Object> r = posts.publishForActivity(AppUserContext.getUserId(), id);
        return AjaxResult.success(String.valueOf(r.get("message")), r);
    }

    @AppAuth
    @PutMapping("/{id}")
    public AjaxResult edit(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(activities.editByUser(AppUserContext.getUserId(), id, body));
    }

    @AppAuth
    @GetMapping("/{id}/manage")
    public AjaxResult manage(@PathVariable Long id)
    {
        return AjaxResult.success(activities.manageAsUser(AppUserContext.getUserId(), id));
    }

    @AppAuth
    @PostMapping("/{id}/manage/{action}")
    public AjaxResult act(@PathVariable Long id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        return AjaxResult.success(activities.actAsUser(me, id, action, s(body, "text"), s(body, "code"), s(body, "reason")));
    }

    @AppAuth
    @GetMapping("/{id}/manage/members/{signupId}/phone")
    public AjaxResult phone(@PathVariable Long id, @PathVariable Long signupId)
    {
        Long me = AppUserContext.getUserId();
        return AjaxResult.success(activities.revealPhone(me, activities.ownUserActivity(me, id), signupId));
    }
}
