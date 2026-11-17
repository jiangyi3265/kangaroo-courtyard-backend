package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgAppNoticeService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 小程序公告：消息页的「公告」列表、公告详情（编号或固定标识，如 online-guide）、进小程序时的弹窗、「我知道了」。
 * 浏览类接口游客也能用（游客只看得到面向所有人的公告）；记「看过」要登录，游客的记录存在手机本地。
 */
@RestController
@RequestMapping("/app/notices")
public class AppNoticeController
{
    @Autowired
    private KgAppNoticeService notices;

    @Autowired
    private com.ruoyi.kangaroo.ext.user.KgNoticeReplyService replies;

    @AppAuth
    @GetMapping("/{noticeId}/replies")
    public AjaxResult replies(@PathVariable Long noticeId, @RequestParam(required = false) Long before)
    { return AjaxResult.success(replies.mine(AppUserContext.getUserId(), noticeId, before)); }

    @AppAuth
    @PostMapping("/{noticeId}/replies")
    public AjaxResult reply(@PathVariable Long noticeId, @RequestBody Map<String, Object> body)
    {
        boolean staff = "staff".equals(body.get("mode")) && AppUserContext.require().hasStaffAccess();
        return AjaxResult.success(replies.send(AppUserContext.getUserId(), noticeId, body, staff));
    }

    @AppAuth
    @PostMapping("/{noticeId}/replies/read")
    public AjaxResult readReplies(@PathVariable Long noticeId, @RequestBody Map<String, Object> body)
    {
        replies.read(AppUserContext.getUserId(), noticeId, com.ruoyi.kangaroo.ext.KgJdbc.asLong(body.get("through")));
        return AjaxResult.success();
    }

    @AppAuth(required = false)
    @GetMapping
    public AjaxResult list()
    {
        return AjaxResult.success(notices.list(AppUserContext.currentUserId()));
    }

    /** 弹窗：没有要弹的返回 data = null。seen = 手机本地记下的「编号:版本」，逗号分隔 */
    @AppAuth(required = false)
    @GetMapping("/popup")
    public AjaxResult popup(@RequestParam(required = false) String seen)
    {
        return AjaxResult.success(notices.popup(AppUserContext.currentUserId(), seen));
    }

    @AppAuth(required = false)
    @GetMapping("/{idOrKey}")
    public AjaxResult detail(@PathVariable String idOrKey)
    {
        return AjaxResult.success(notices.detail(AppUserContext.currentUserId(), idOrKey));
    }

    /** 我知道了：body {version}，记下看过的版本 */
    @AppAuth
    @PostMapping("/{noticeId}/seen")
    public AjaxResult seen(@PathVariable Long noticeId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(notices.seen(AppUserContext.getUserId(), noticeId, body == null ? null : body.get("version")));
    }
}
