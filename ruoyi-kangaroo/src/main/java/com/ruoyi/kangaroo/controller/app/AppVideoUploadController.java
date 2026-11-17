package com.ruoyi.kangaroo.controller.app;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 小程序视频上传（2026-10-17 旅居改版 R3：员工端小院视频 / 房型视频 / 生活碎片，住客住宿评价视频）。必须登录。
 * 和图片上传（/app/upload/image）放在同一个前缀下，但视频一律分段传（KgVideoUploads）：
 *  POST   /app/upload/video/parts                       body {name 原文件名, size 字节数, duration 页面量到的秒数} → {uploadId, partBytes, parts}
 *  POST   /app/upload/video/parts/{uploadId}/{index}    请求体是这一段的原始字节（application/octet-stream，≤ 8MB）→ {received, size}
 *  POST   /app/upload/video/parts/{uploadId}/finish     → {url: /profile/upload/videos/…, size}
 *  DELETE /app/upload/video/parts/{uploadId}            放弃（没有也算成功）
 * 视频存在公开可读的 /profile/ 下：每人每小时最多开始 10 个、每天 20 个（一个视频算一次），免得一个循环把磁盘写满；
 * 分段本身另有每小时 600 段的上限（200MB 的视频是 7 段）。
 */
@RestController
@RequestMapping("/app/upload/video")
public class AppVideoUploadController
{
    @Autowired
    private KgVideoUploads videos;

    @Autowired
    private AppRateLimiter limiter;

    private static Long uid()
    {
        return AppUserContext.getUserId();
    }

    @AppAuth
    @PostMapping("/parts")
    public AjaxResult begin(@RequestBody(required = false) Map<String, Object> body)
    {
        String u = String.valueOf(uid());
        limiter.check("upload-video-hour", u, 10, 3600, "上传视频太频繁了，请稍后再试");
        limiter.check("upload-video-day", u, 20, 86400, "今天上传的视频已达上限，请明天再试");
        Map<String, Object> b = body == null ? new LinkedHashMap<String, Object>() : body;
        // kind=intro：小院介绍视频（「3分钟看小院」），放宽到 3 分钟、200MB（2026-10-17）
        return AjaxResult.success(videos.partBegin(uid(), b.get("name") == null ? null : String.valueOf(b.get("name")), b.get("size"), b.get("duration"),
                "intro".equals(b.get("kind"))));
    }

    @AppAuth
    @PostMapping("/parts/{uploadId}/{index:[0-9]{1,4}}")
    public AjaxResult append(@PathVariable String uploadId, @PathVariable int index, HttpServletRequest request) throws Exception
    {
        limiter.check("upload-video-part", String.valueOf(uid()), 600, 3600, "上传视频太频繁了，请稍后再试");
        return AjaxResult.success(videos.partAppend(uploadId, uid(), index, request.getInputStream()));
    }

    @AppAuth
    @PostMapping("/parts/{uploadId}/finish")
    public AjaxResult finish(@PathVariable String uploadId)
    {
        return AjaxResult.success(videos.partFinish(uploadId, uid()));
    }

    @AppAuth
    @DeleteMapping("/parts/{uploadId}")
    public AjaxResult abort(@PathVariable String uploadId)
    {
        videos.partAbort(uploadId, uid());
        return AjaxResult.success();
    }
}
