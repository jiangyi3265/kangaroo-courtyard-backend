package com.ruoyi.kangaroo.controller;
import java.io.File;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.kangaroo.ext.user.KgResumeMedia;
import com.ruoyi.kangaroo.security.AppRateLimiter;
@RestController
public class KgResumeFileController {
    private final KgResumeMedia media;
    private final AppRateLimiter limiter;
    public KgResumeFileController(KgResumeMedia media,AppRateLimiter limiter){this.media=media;this.limiter=limiter;}
    @Anonymous @GetMapping("/kangaroo/file/resume")
    public ResponseEntity<FileSystemResource> file(@RequestParam("k") String key,@RequestParam("e") long expiry,@RequestParam("s") String sig){
        limiter.checkByIp("resume-download",600,3600,"访问太频繁，请稍后再试");
        File f=media.verify(key,expiry,sig)?media.resolve(key):null;
        if(f==null)return ResponseEntity.status(403).build();
        String type=key.endsWith("mp4")?"video/mp4":(key.endsWith("png")?"image/png":"image/jpeg");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("X-Content-Type-Options","nosniff")
            .header("Cache-Control","private, no-store").body(new FileSystemResource(f));
    }
}
