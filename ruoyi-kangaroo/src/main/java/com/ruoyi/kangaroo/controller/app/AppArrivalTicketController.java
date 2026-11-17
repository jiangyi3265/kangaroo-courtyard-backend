package com.ruoyi.kangaroo.controller.app;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.core.KgArrivalTickets;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

@RestController
@RequestMapping("/app/application/{appId}/arrival/ticket")
public class AppArrivalTicketController
{
    @Autowired private KgArrivalTickets files;
    @Autowired private AppRateLimiter limiter;

    @AppAuth
    @PostMapping
    public AjaxResult upload(@PathVariable Long appId, @RequestParam("file") MultipartFile file) throws Exception
    {
        Long uid = AppUserContext.getUserId();
        limiter.check("arrival-ticket-min", String.valueOf(uid), 20, 60, "上传太频繁了，请稍后再试");
        limiter.check("arrival-ticket-day", String.valueOf(uid), 200, 86400, "今天上传的票据已达上限");
        return AjaxResult.success(Collections.singletonMap("url", files.save(uid, appId, file)));
    }

    @AppAuth
    @GetMapping
    public void download(@PathVariable Long appId, @RequestParam("file") String ref, HttpServletResponse response) throws Exception
    {
        File f = files.read(AppUserContext.getUserId(), appId, ref);
        String ext = f.getName().substring(f.getName().lastIndexOf('.') + 1);
        response.setContentType("image/" + (("jpg".equals(ext) || "jpeg".equals(ext)) ? "jpeg" : ext));
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLengthLong(f.length());
        Files.copy(f.toPath(), response.getOutputStream());
    }
}
