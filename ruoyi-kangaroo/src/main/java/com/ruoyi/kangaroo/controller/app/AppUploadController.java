package com.ruoyi.kangaroo.controller.app;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 小程序图片上传（发帖、评价、Vlog 封面、周报附图、结算凭证等共用）。
 * 必须登录；只收图片扩展名，并核对文件头确实是 jpg / png / gif / webp（KgImageMagic）；单张不超过 20MB；存到若依的上传目录（/profile/upload/...），
 * 返回相对路径，前端按接口域名拼成完整地址。
 */
@RestController
@RequestMapping("/app/upload")
public class AppUploadController
{
    private static final String[] IMAGE_EXT = { "jpg", "jpeg", "png", "gif", "webp" };
    private static final long MAX = 20L * 1024 * 1024;

    @org.springframework.beans.factory.annotation.Autowired
    private AppRateLimiter limiter;

    @AppAuth
    @PostMapping("/image")
    public AjaxResult image(@RequestParam("file") MultipartFile file,
            @RequestHeader(value="X-App-Upload-Version", required=false) String version) throws Exception
    {
        // Retire old clients which sent private arrival tickets through this public-media endpoint.
        // The upgraded client uses a dedicated private endpoint for tickets. No file is persisted here until this check passes.
        if (!"2".equals(version)) throw new ServiceException("请更新小程序后再上传图片，旧版票据上传入口已停用", 426);
        // 上传的文件存在公开可读的 /profile/ 下：没有限流时任意登录用户一个循环就能把磁盘写满。
        // 每人每分钟 40 张、每天 200 张（正常发帖 / 评价 / 周报附图一天远用不到）。10-15 从 20 调到 40：发素材一次可选 20 张，
        // 再补传失败的几张、或接着加退回截图，同一分钟内就会被 20 张的上限挡住
        String uid = String.valueOf(AppUserContext.getUserId());
        limiter.check("upload-min", uid, 40, 60, "上传太频繁了，请稍后再试");
        limiter.check("upload-day", uid, 200, 86400, "今天上传的图片已达上限，请明天再试");
        if (file == null || file.isEmpty())
        {
            throw new ServiceException("没有收到图片");
        }
        if (file.getSize() > MAX)
        {
            throw new ServiceException("图片不能超过 20MB");
        }
        String ct = file.getContentType();
        if (ct != null && !ct.startsWith("image/") && !"application/octet-stream".equals(ct))
        {
            throw new ServiceException("只能上传图片");
        }
        // 只看扩展名时，HTML / SVG 改名成 .jpg 也能存进公开的 /profile/ 下；存盘前先核对文件头，不是图片回 422
        com.ruoyi.kangaroo.ext.core.KgImageMagic.require(file, IMAGE_EXT);
        // uuid 命名：原文件名（中文 / 空格 / 括号）会原样进路径，之后被「只收平台图片路径」的白名单挡掉，发帖静默丢图、封面头像报 422
        String path = FileUploadUtils.upload(RuoYiConfig.getUploadPath(), file, IMAGE_EXT, true);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("url", path);
        return AjaxResult.success(m);
    }
}
