package com.ruoyi.kangaroo.controller.app;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.community.KgPostService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 社区动态。信息流、找搭子、帖子、评论、规则文本、用户主页游客可看；点赞 / 收藏 / 关注 / 拉黑 / 举报 / 评论 / 发布、
 * 我关注的人、我收藏的帖子、「谁可以私信我」需要登录。信息流 sort = new 最新 / hot 热门 / follow 已关注。
 */
@RestController
@RequestMapping("/app/community")
public class AppCommunityController
{
    @Autowired
    private KgPostService posts;
    @Autowired(required=false) private javax.servlet.http.HttpServletRequest request;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private AppRateLimiter limiter;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    /** jobType = volunteer / parttime / fulltime：只在 kind = diary（经验分享）时生效，按关联经历的岗位类型筛（换宿旅行 / 线下兼职 / 全职） */
    @AppAuth(required = false)
    @GetMapping("/feed")
    public AjaxResult feed(@RequestParam(required = false) String kind, @RequestParam(required = false) String city,
            @RequestParam(required = false) String sort, @RequestParam(defaultValue = "1") int page, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String jobType)
    {
        return AjaxResult.success(posts.feed(AppUserContext.currentUserId(), kind, city, sort, page, keyword, jobType));
    }

    /** gender = female / male：只看这个性别能参加的找搭子（不限 + 限定为该性别）；keyword：社区搜索框在「找搭子」页签下的关键词 */
    @AppAuth(required = false)
    @GetMapping("/buddies")
    public AjaxResult buddies(@RequestParam(required = false) String city, @RequestParam(required = false) String date,
            @RequestParam(required = false) String days, @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean verified, @RequestParam(required = false) String gender,
            @RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(posts.buddies(AppUserContext.currentUserId(), city, date, days, category, verified, gender, keyword,request==null?null:request.getParameter("activityPhase")));
    }

    @AppAuth(required = false)
    @GetMapping("/posts/{id}")
    public AjaxResult post(@PathVariable Long id)
    {
        return AjaxResult.success(posts.detail(id, AppUserContext.currentUserId()));
    }

    @AppAuth(required = false)
    @GetMapping("/posts/{id}/comments")
    public AjaxResult comments(@PathVariable Long id, @RequestParam(required = false) String sort)
    {
        return AjaxResult.success(posts.comments(id, AppUserContext.currentUserId(), sort));
    }

    @AppAuth
    @PostMapping("/posts/{id}/comments")
    public AjaxResult comment(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("community:comment", String.valueOf(me), 30, 600, "评论太频繁了，稍后再试");
        return AjaxResult.success(posts.comment(id, me, KgJdbc.asLong(body.get("parentId")), s(body, "content"), body.get("images")));
    }

    @AppAuth
    @DeleteMapping("/comments/{id}")
    public AjaxResult deleteComment(@PathVariable Long id)
    {
        return AjaxResult.success(posts.deleteComment(id, AppUserContext.getUserId()));
    }

    /** 点赞会改写对方「收到的赞」那一行（2026-10-15）：和评论一样按账号限频，防止连点刷通知 */
    @AppAuth
    @PostMapping("/comments/{id}/like")
    public AjaxResult likeComment(@PathVariable Long id)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("community:like", String.valueOf(me), 60, 60, "操作太频繁了，稍后再试");
        return AjaxResult.success(posts.likeComment(id, me));
    }

    @AppAuth
    @PostMapping("/posts/{id}/like")
    public AjaxResult like(@PathVariable Long id)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("community:like", String.valueOf(me), 60, 60, "操作太频繁了，稍后再试");
        return AjaxResult.success(posts.like(id, me));
    }

    @AppAuth
    @PostMapping("/posts/{id}/favorite")
    public AjaxResult favorite(@PathVariable Long id)
    {
        return AjaxResult.success(posts.favorite(id, AppUserContext.getUserId()));
    }

    @AppAuth
    @PostMapping("/users/{uid}/follow")
    public AjaxResult follow(@PathVariable Long uid)
    {
        return AjaxResult.success(posts.follow(uid, AppUserContext.getUserId()));
    }

    /** 用户的公开主页（游客也能看）：资料、关注关系、能否私信、TA 最近的公开动态（page 翻页，每页 20 条） */
    @AppAuth(required = false)
    @GetMapping("/users/{uid}")
    public AjaxResult user(@PathVariable Long uid, @RequestParam(required = false) Integer page)
    {
        return AjaxResult.success(posts.userProfile(uid, AppUserContext.currentUserId(), page));
    }

    /** 我关注的人 */
    @AppAuth
    @GetMapping("/following")
    public AjaxResult following()
    {
        return AjaxResult.success(posts.followingUsers(AppUserContext.getUserId()));
    }

    /** 我收藏的帖子 */
    @AppAuth
    @GetMapping("/favorites")
    public AjaxResult favorites(@RequestParam(required = false) Integer page)
    {
        return AjaxResult.success(posts.favorites(AppUserContext.getUserId(), page));
    }

    /** 「谁可以私信我」：value = all / following / mutual，options 是三个选项和中文名 */
    @AppAuth
    @GetMapping("/settings/dm")
    public AjaxResult dmSetting()
    {
        String v = guard.dmPrivacy(AppUserContext.getUserId());
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("value", v);
        m.put("label", KgCommunityGuard.DM_PRIVACY.get(v));
        m.put("options", KgCommunityGuard.DM_PRIVACY);
        return AjaxResult.success(m);
    }

    @AppAuth
    @PutMapping("/settings/dm")
    public AjaxResult saveDmSetting(@RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        String v = s(body, "value");
        guard.setDmPrivacy(me, v);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("value", v);
        m.put("label", KgCommunityGuard.DM_PRIVACY.get(v));
        m.put("message", "已保存：" + KgCommunityGuard.DM_PRIVACY.get(v) + "可以私信你");
        return AjaxResult.success(m);
    }

    @AppAuth
    @PostMapping("/users/{uid}/block")
    public AjaxResult block(@PathVariable Long uid)
    {
        return AjaxResult.success(posts.block(uid, AppUserContext.getUserId()));
    }

    @AppAuth
    @PostMapping("/reports")
    public AjaxResult report(@RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("community:report", String.valueOf(me), 20, 3600, "举报太频繁了，稍后再试");
        return AjaxResult.success(posts.report(me, s(body, "targetType"), KgJdbc.asLong(body.get("targetId")), s(body, "reason"), s(body, "detail")));
    }

    @AppAuth
    @PostMapping("/posts")
    public AjaxResult publish(@RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("community:publish", String.valueOf(me), 10, 600, "发布太频繁了，稍后再试");
        return AjaxResult.success(posts.publish(me, body));
    }

    @AppAuth
    @GetMapping("/posts/{id}/edit")
    public AjaxResult editView(@PathVariable Long id)
    {
        return AjaxResult.success(posts.editView(id, AppUserContext.getUserId()));
    }

    @AppAuth
    @PutMapping("/posts/{id}")
    public AjaxResult edit(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("community:edit", String.valueOf(me), 20, 600, "修改太频繁了，稍后再试");
        return AjaxResult.success(posts.edit(id, me, body));
    }

    @AppAuth
    @GetMapping("/posts/mine")
    public AjaxResult mine(@RequestParam(required = false) Integer page)
    {
        return AjaxResult.success(posts.mine(AppUserContext.getUserId(), page));
    }

    @AppAuth
    @DeleteMapping("/posts/{id}")
    public AjaxResult remove(@PathVariable Long id)
    {
        return AjaxResult.success(posts.remove(id, AppUserContext.getUserId()));
    }

    @AppAuth
    @PostMapping("/posts/{id}/remove")
    public AjaxResult removeWithActivity(@PathVariable Long id,@RequestBody Map<String,Object> body) {
        return AjaxResult.success(posts.removeWithActivity(id,AppUserContext.getUserId(),Boolean.TRUE.equals(body.get("cancelActivity")),s(body,"reason")));
    }

    @AppAuth
    @GetMapping("/refs")
    public AjaxResult refs()
    {
        return AjaxResult.success(posts.refs(AppUserContext.getUserId()));
    }

    /**
     * 发布页「关联帖子」选择页（2026-10-17）：搜能关联的 type = place 小院和门店 / job 在招岗位 / activity 还没结束的活动，
     * keyword 按名称、城市找，city 是发布页选的城市（排在前面，不过滤），每页 20 条。都是公开内容，游客也能搜（发布时要登录）
     */
    @AppAuth(required = false)
    @GetMapping("/linkables")
    public AjaxResult linkables(@RequestParam(required = false) String type, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city, @RequestParam(defaultValue = "1") int page)
    {
        return AjaxResult.success(posts.linkables(type, keyword, city, page, AppUserContext.currentUserId()));
    }

    @AppAuth
    @GetMapping("/experiences")
    public AjaxResult experiences()
    {
        Long me = AppUserContext.getUserId();
        // data 还是能选的经历（老版本小程序照旧能用）；2026-10-16 内测反馈 2.0 起在 data 旁边多给 unavailable：
        // 暂时还不能用的报名（报名了还没到岗、线上任务），每条带 reason，经历校验页灰着显示
        AjaxResult r = AjaxResult.success(posts.experiences(me));
        r.put("unavailable", posts.experiencesUnavailable(me));
        return r;
    }

    @AppAuth(required = false)
    @GetMapping("/rules/{doc}")
    public AjaxResult rules(@PathVariable String doc)
    {
        return AjaxResult.success(KgCommunityGuard.doc(doc));
    }

    @AppAuth
    @PostMapping("/consent")
    public AjaxResult consent(@RequestBody Map<String, Object> body)
    {
        guard.consent(AppUserContext.getUserId(), s(body, "doc"));
        return AjaxResult.success("已同意");
    }

    /** 当前账号在社区里的准入状态：是否同意了两份规则、手机号是否经微信核验、审核方式 */
    @AppAuth(required = false)
    @GetMapping("/status")
    public AjaxResult status()
    {
        Long me = AppUserContext.currentUserId();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("login", me != null);
        m.put("communityConsented", guard.consented(me, "community"));
        m.put("activityConsented", guard.consented(me, "activity"));
        m.put("phoneVerified", me != null && guard.phoneVerified(me));
        m.put("reviewMode", guard.configuredMode());
        return AjaxResult.success(m);
    }
}
