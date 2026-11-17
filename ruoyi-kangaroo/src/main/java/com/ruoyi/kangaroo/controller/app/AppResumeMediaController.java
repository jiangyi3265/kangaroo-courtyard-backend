package com.ruoyi.kangaroo.controller.app;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.*;
import com.ruoyi.kangaroo.security.*;
@RestController
@RequestMapping("/app/resume")
@AppAuth
public class AppResumeMediaController {
    private final KgResumeMedia media;
    private final AppRateLimiter limiter;
    public AppResumeMediaController(KgResumeMedia media,AppRateLimiter limiter){this.media=media;this.limiter=limiter;}
    @PostMapping("/media")
    public AjaxResult media(@RequestParam("file") MultipartFile file,@RequestParam String kind) throws IOException {
        limiter.checkByIp("resume-media",40,3600,"上传太频繁，请稍后再试");
        return AjaxResult.success(media.save(AppUserContext.getUserId(),file,kind));
    }
    @PostMapping("/import")
    public AjaxResult parse(@RequestParam("file") MultipartFile file) throws IOException {
        limiter.checkByIp("resume-import",20,3600,"识别太频繁，请稍后再试");
        return AjaxResult.success(KgResumeImport.read(file));
    }
}
