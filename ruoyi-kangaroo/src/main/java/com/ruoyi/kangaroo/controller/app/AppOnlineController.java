package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgChatReception;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialFiles;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialService;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/** 线上任务（接单人侧）：查看本人接单、下载门店发来的素材附件、提交成果、确认收款或申诉、门店迟迟不验收时请平台介入 */
@RestController
@RequestMapping("/app/online")
public class AppOnlineController
{
    @Autowired
    private KgOnlineTaskService online;

    @Autowired private com.ruoyi.kangaroo.ext.staff.KgOnlineRecipient recipients;

    @AppAuth
    @PostMapping("/assignments/{appId}/recipient")
    public AjaxResult recipient(@PathVariable Long appId, @RequestBody Map<String,Object> body) {
        limiter.check("online:recipient", String.valueOf(AppUserContext.getUserId()), 30, 3600, "操作太频繁，请稍后再试");
        return AjaxResult.success(recipients.save(appId,body));
    }

    @AppAuth
    @PostMapping("/assignments/{appId}/recipient-qr")
    public AjaxResult recipientQr(@PathVariable Long appId, @RequestParam String realName, @RequestParam org.springframework.web.multipart.MultipartFile file) {
        limiter.check("online:recipient", String.valueOf(AppUserContext.getUserId()), 30, 3600, "操作太频繁，请稍后再试");
        return AjaxResult.success(recipients.saveQr(appId,realName,file));
    }

    @Autowired
    private KgOnlineMaterialService materials;

    @Autowired
    private KgOnlineMaterialFiles files;

    @Autowired
    private AppRateLimiter limiter;

    @Autowired
    private KgChatReception reception;

    @AppAuth
    @GetMapping("/assignments/{appId}")
    public AjaxResult mine(@PathVariable Long appId)
    {
        Map<String, Object> m = online.mine(appId);
        // 本店在职线上任务专员发布的任务：交付页「联系主理人」改叫「联系专员」（和进度页同一口径，2026-10-16 主人拍板）
        m.put("contactSpecialist", reception.specialistOf(KgJdbc.asLong(m.get("storeId")), appId) != null);
        return AjaxResult.success(m);
    }

    @AppAuth
    @PostMapping("/assignments/{appId}/submit")
    public AjaxResult submit(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        limiter.check("online:submit", String.valueOf(AppUserContext.getUserId()), 20, 3600, "提交太频繁了，稍后再试");
        return AjaxResult.success(online.submit(appId, body));
    }

    @AppAuth
    @PostMapping("/assignments/{appId}/confirm")
    public AjaxResult confirm(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(online.confirm(appId, Boolean.TRUE.equals(body.get("received")), KgJdbc.asStr(body.get("reason"))));
    }

    /** 成果提交满几天门店还没验收：请平台介入（body: reason） */
    @AppAuth
    @PostMapping("/assignments/{appId}/escalate")
    public AjaxResult escalate(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        limiter.check("online:escalate", String.valueOf(AppUserContext.getUserId()), 5, 3600, "操作太频繁了，稍后再试");
        return AjaxResult.success(online.escalate(appId, KgJdbc.asStr(body == null ? null : body.get("reason"))));
    }

    /** 下载门店随素材发来的附件（V41）：只能下自己接单里已经发出的；接单撤回或结束后仍可下载 */
    @AppAuth
    @GetMapping("/assignments/{appId}/material-files/{fileId}")
    public void materialFile(@PathVariable Long appId, @PathVariable Long fileId, HttpServletResponse response) throws Exception
    {
        files.serve(materials.workerFile(appId, fileId), response);
    }

    /** 附件的下载链接（3 天内有效，用手机或电脑的浏览器打开就能下载）：{url, expireAt, hours} */
    @AppAuth
    @GetMapping("/assignments/{appId}/material-files/{fileId}/link")
    public AjaxResult materialFileLink(@PathVariable Long appId, @PathVariable Long fileId)
    {
        limiter.check("online:material-link", String.valueOf(AppUserContext.getUserId()), 60, 3600, "复制链接太频繁了，稍后再试");
        return AjaxResult.success(materials.link(materials.workerFile(appId, fileId)));
    }
}
