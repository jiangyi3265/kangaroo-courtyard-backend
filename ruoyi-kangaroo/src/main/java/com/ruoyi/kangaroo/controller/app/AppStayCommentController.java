package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayCommentService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 小院评论（2026-10-31，V65）：和「住过的家人评价」分开，先审后显示。
 *  GET    /app/stay/{stayId}/comments?page=1      游客可看：{rows 已通过的, mine 本人还没公开的（第一页）, total, hasMore, pageNum}
 *  GET    /app/stay/{stayId}/comments/me          写评论前的提示：{phoneVerified, consented, identity, identityText, pending, pendingMax}
 *  POST   /app/stay/{stayId}/comments             {content, images?, videos?: [{url, cover}], portraitConfirmed?}
 *  DELETE /app/stay/comments/{commentId}          删除自己的
 *  POST   /app/stay/comments/{commentId}/report   {reason?, detail?}
 */
@RestController
@RequestMapping("/app/stay")
public class AppStayCommentController
{
    @Autowired
    private KgStayCommentService comments;

    @Autowired
    private AppRateLimiter limiter;

    @AppAuth(required = false)
    @GetMapping("/{stayId}/comments")
    public AjaxResult list(@PathVariable Long stayId, @RequestParam(defaultValue = "1") int page)
    {
        limiter.checkByIp("stay-comments", 300, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success(comments.list(stayId, page, AppUserContext.currentUserId()));
    }

    @AppAuth
    @GetMapping("/{stayId}/comments/me")
    public AjaxResult me(@PathVariable Long stayId)
    {
        return AjaxResult.success(comments.me(stayId, AppUserContext.getUserId()));
    }

    @AppAuth
    @PostMapping("/{stayId}/comments")
    public AjaxResult submit(@PathVariable Long stayId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success("已提交，审核通过后展示", comments.submit(stayId, AppUserContext.getUserId(), body));
    }

    @AppAuth
    @DeleteMapping("/comments/{commentId}")
    public AjaxResult remove(@PathVariable Long commentId)
    {
        comments.remove(commentId, AppUserContext.getUserId());
        return AjaxResult.success("已删除");
    }

    @AppAuth
    @PostMapping("/comments/{commentId}/report")
    public AjaxResult report(@PathVariable Long commentId, @RequestBody(required = false) Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("stay-comment-report", String.valueOf(me), 20, 3600, "举报太频繁了，请稍后再试");
        String reason = body == null ? null : KgJdbc.asStr(body.get("reason"));
        String detail = body == null ? null : KgJdbc.asStr(body.get("detail"));
        return AjaxResult.success(comments.report(commentId, me, reason, detail));
    }
}
