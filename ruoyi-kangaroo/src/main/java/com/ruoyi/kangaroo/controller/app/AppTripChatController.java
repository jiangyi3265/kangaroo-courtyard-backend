package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgChatService;
import com.ruoyi.kangaroo.ext.community.KgTripService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 我的行程、公开行程找搭子、私信与群聊。全部需要登录。
 */
@RestController
@RequestMapping("/app")
public class AppTripChatController
{
    @ModelAttribute
    public void selectChatMode(javax.servlet.http.HttpServletRequest request,
            @RequestHeader(value="X-Chat-Mode", required=false) String mode) {
        AppUserContext.chatMode(null);
        if (!request.getServletPath().startsWith("/app/chat/") || mode == null) return;
        if (!"staff".equals(mode) && !"user".equals(mode)) throw new ServiceException("聊天身份不正确", 422);
        AppUserContext.chatMode(mode);
    }

    @Autowired
    private KgTripService trips;

    @Autowired
    private KgChatService chats;

    @Autowired private com.ruoyi.kangaroo.ext.community.KgChatReception reception;

    @Autowired
    private com.ruoyi.kangaroo.ext.community.KgChatFiles files;

    @Autowired
    private KgJdbc db;

    @Autowired
    private AppRateLimiter limiter;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    // ---------------------------------------------------------------- 行程

    @AppAuth
    @GetMapping("/trips")
    public AjaxResult trips(@RequestParam(required = false) String tab)
    {
        return AjaxResult.success(trips.trips(AppUserContext.getUserId(), tab));
    }

    @AppAuth
    @GetMapping("/trips/detail")
    public AjaxResult tripDetail(@RequestParam String refType, @RequestParam Long refId)
    {
        return AjaxResult.success(trips.detail(AppUserContext.getUserId(), refType, refId));
    }

    @AppAuth
    @PostMapping("/trip-plans")
    public AjaxResult publishPlan(@RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("trip:publish", String.valueOf(me), 10, 3600, "发布太频繁了，稍后再试");
        return AjaxResult.success(trips.publishPlan(me, body));
    }

    @AppAuth
    @GetMapping("/trip-plans/mine")
    public AjaxResult myPlans()
    {
        return AjaxResult.success(trips.myPlans(AppUserContext.getUserId()));
    }

    @AppAuth
    @PostMapping("/trip-plans/{planId}/close")
    public AjaxResult closePlan(@PathVariable Long planId)
    {
        return AjaxResult.success(trips.closePlan(AppUserContext.getUserId(), planId));
    }

    @AppAuth
    @GetMapping("/trip-plans/matches")
    public AjaxResult matches(@RequestParam(required = false) Long planId)
    {
        return AjaxResult.success(trips.matches(AppUserContext.getUserId(), planId));
    }

    @AppAuth
    @GetMapping("/trip-plans/{planId}/person")
    public AjaxResult person(@PathVariable Long planId)
    {
        return AjaxResult.success(trips.person(AppUserContext.getUserId(), planId));
    }

    // ---------------------------------------------------------------- 会话

    /**
     * 打开会话：type = private（userId / postId / planId 三选一定位对方）/ store（appId）/ activity（activityId）。
     */
    @AppAuth
    @PostMapping("/chat/open")
    public AjaxResult open(@RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        String type = s(body, "type");
        if ("store".equals(type))
        {
            return AjaxResult.success(chats.openStore(me, KgJdbc.asLong(body.get("appId"))));
        }
        if ("activity".equals(type))
        {
            return AjaxResult.success(chats.openActivity(me, KgJdbc.asLong(body.get("activityId"))));
        }
        Long other = KgJdbc.asLong(body.get("userId"));
        String source = s(body, "source");
        Long sourceId = null;
        if (body.get("postId") != null)
        {
            sourceId = KgJdbc.asLong(body.get("postId"));
            Map<String, Object> p = db.one("select app_user_id, kind from kg_post where post_id = :p and status = 'online' and del_flag = '0'", KgJdbc.p("p", sourceId));
            if (p == null)
            {
                throw new ServiceException("动态不存在", 404);
            }
            other = KgJdbc.asLong(p.get("appUserId"));
            source = "buddy".equals(KgJdbc.asStr(p.get("kind"))) ? "buddy" : "post";
        }
        else if (body.get("planId") != null)
        {
            sourceId = KgJdbc.asLong(body.get("planId"));
            Map<String, Object> p = db.one("select app_user_id from kg_trip_plan where plan_id = :p and status = 'online' and del_flag = '0'", KgJdbc.p("p", sourceId));
            if (p == null)
            {
                throw new ServiceException("对方的公开行程已关闭", 404);
            }
            other = KgJdbc.asLong(p.get("appUserId"));
            source = "match";
        }
        limiter.check("chat:open", String.valueOf(me), 30, 3600, "发起会话太频繁了，稍后再试");
        return AjaxResult.success(chats.openPrivate(me, other, source, sourceId));
    }

    /** 我最近的私信会话（帖子「分享到私信」时选发给谁） */
    @AppAuth
    @GetMapping("/chat/recent")
    public AjaxResult recent()
    {
        return AjaxResult.success(chats.recentPrivate(AppUserContext.getUserId()));
    }

    @AppAuth
    @GetMapping("/chat/{convId}")
    public AjaxResult conversation(@PathVariable Long convId)
    {
        return AjaxResult.success(chats.conversation(AppUserContext.getUserId(), convId));
    }

    @AppAuth
    @GetMapping("/chat/{convId}/messages")
    public AjaxResult messages(@PathVariable Long convId, @RequestParam(required = false) Long after)
    {
        return AjaxResult.success(chats.messages(AppUserContext.getUserId(), convId, after));
    }

    @AppAuth
    @PostMapping("/chat/{convId}/messages")
    public AjaxResult send(@PathVariable Long convId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(chats.send(AppUserContext.getUserId(), convId, s(body, "content"), body.get("clientMessageId")));
    }

    @AppAuth
    @GetMapping("/staff/chat-reception")
    public AjaxResult reception(@RequestParam Long storeId)
    { return AjaxResult.success(reception.settings(AppUserContext.getUserId(),storeId)); }

    @AppAuth
    @PutMapping("/staff/chat-reception")
    public AjaxResult receptionSave(@RequestParam Long storeId,@RequestBody Map<String,Object> body)
    { return AjaxResult.success(reception.save(AppUserContext.getUserId(),storeId,body.get("userIds"))); }

    @AppAuth
    @PostMapping("/chat/{convId}/images")
    public AjaxResult sendImage(@PathVariable Long convId,@RequestParam String clientMessageId,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file,
            @RequestHeader(value="X-App-Upload-Version",required=false) String protocol)
    {
        if(!"2".equals(protocol))throw new ServiceException("请更新客户端后发送图片",426);
        return AjaxResult.success(chats.sendImage(AppUserContext.getUserId(),convId,clientMessageId,file));
    }

    @AppAuth
    @GetMapping("/chat/{convId}/messages/{msgId}/image")
    public org.springframework.http.ResponseEntity<byte[]> image(@PathVariable Long convId,@PathVariable Long msgId)
    {
        Map<String,Object> image=chats.image(AppUserContext.getUserId(),convId,msgId);
        byte[] data=(byte[])image.get("imageBytes");
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","private, no-store, max-age=0")
                .header("Pragma","no-cache").header("X-Content-Type-Options","nosniff")
                .header("Content-Disposition","inline; filename=chat-image"+("image/png".equals(image.get("mime"))?".png":".jpg"))
                .contentType(org.springframework.http.MediaType.parseMediaType((String)image.get("mime"))).contentLength(data.length).body(data);
    }

    /**
     * 发文件（V46，2026-10-16）：只有和门店的沟通（门店 ↔ 报名人 / 接单人）能发；multipart：file + name（原文件名，小程序上传时的文件名是临时名）
     * + clientMessageId（重发同一条不会发两次）。只收 zip / rar / 7z / PDF / Word / Excel / PPT，单个 10MB 以内，文件头要和扩展名对得上。
     */
    @AppAuth
    @PostMapping("/chat/{convId}/files")
    public AjaxResult sendFile(@PathVariable Long convId, @RequestParam(value = "clientMessageId", required = false) String clientMessageId,
            @RequestParam(value = "file", required = false) org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "name", required = false) String name)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("chat:file", String.valueOf(me), 30, 3600, "发文件太频繁了，请一小时后再试");
        return AjaxResult.success(chats.sendFile(me, convId, clientMessageId, file, name));
    }

    /** 下载会话里的文件（带登录令牌）：每次按当前身份重新核对；总是按附件下载、nosniff、不缓存 */
    @AppAuth
    @GetMapping("/chat/{convId}/messages/{msgId}/file")
    public void file(@PathVariable Long convId, @PathVariable Long msgId, javax.servlet.http.HttpServletResponse response) throws java.io.IOException
    {
        files.serve(chats.file(AppUserContext.getUserId(), convId, msgId), response);
    }

    /** 复制下载链接：{url, expireAt, hours}，3 天内有效、认人（复制的人不在会话里了就失效） */
    @AppAuth
    @GetMapping("/chat/{convId}/messages/{msgId}/file/link")
    public AjaxResult fileLink(@PathVariable Long convId, @PathVariable Long msgId)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("chat:file-link", String.valueOf(me), 60, 3600, "复制链接太频繁了，稍后再试");
        return AjaxResult.success(chats.fileLink(me, convId, msgId));
    }

    @AppAuth
    @GetMapping("/chat/{convId}/detail")
    public AjaxResult groupDetail(@PathVariable Long convId)
    {
        return AjaxResult.success(chats.groupDetail(AppUserContext.getUserId(), convId));
    }

    @AppAuth
    @PutMapping("/chat/{convId}/flags")
    public AjaxResult flags(@PathVariable Long convId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(chats.setFlag(AppUserContext.getUserId(), convId, s(body, "flag"), Boolean.parseBoolean(s(body, "on"))));
    }

    @AppAuth
    @PutMapping("/chat/{convId}/announcement")
    public AjaxResult announce(@PathVariable Long convId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(chats.announce(AppUserContext.getUserId(), convId, s(body, "text")));
    }

    @AppAuth
    @PostMapping("/chat/{convId}/leave")
    public AjaxResult leave(@PathVariable Long convId)
    {
        return AjaxResult.success(chats.leave(AppUserContext.getUserId(), convId));
    }

    /** 私信交换联系方式：agree=true 同意 / false 撤回自己的请求；双方都同意后手机号、微信号才显示 */
    @AppAuth
    @PostMapping("/chat/{convId}/contact")
    public AjaxResult contact(@PathVariable Long convId, @RequestBody Map<String, Object> body)
    {
        boolean agree = Boolean.parseBoolean(s(body, "agree"));
        Map<String, Object> state = chats.exchangeContact(AppUserContext.getUserId(), convId, agree);
        String msg = Boolean.TRUE.equals(state.get("open")) ? "已交换联系方式" : (agree ? "已发出交换请求，等对方同意" : "已撤回交换请求");
        return AjaxResult.success(msg, state);
    }
}
