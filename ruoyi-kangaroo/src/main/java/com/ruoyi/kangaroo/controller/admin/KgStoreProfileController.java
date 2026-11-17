package com.ruoyi.kangaroo.controller.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.core.KgStoreProfiles;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.ext.core.KgLicenseWatermark;

/**
 * 门店资料（管理后台「门店」→ 门店资料；2026-10-18 岗位详情改版，见 KgStoreProfiles）。用门店已有的权限，不加新菜单：
 *  GET    /kangaroo/store/profile/{storeId}                       查看（kangaroo:store:query）
 *  PUT    /kangaroo/store/profile/{storeId}                       保存（kangaroo:store:edit；改成 / 取消实地探访还要 kangaroo:store:cert）
 *  POST   /kangaroo/store/profile/video/parts                     采访视频开始分段上传 body {name, size, duration} → {uploadId, partBytes, parts}
 *  POST   /kangaroo/store/profile/video/parts/{uploadId}/{index}  请求体是这一段的原始字节（application/octet-stream，≤ 8MB）→ {received, size}
 *  POST   /kangaroo/store/profile/video/parts/{uploadId}/finish   → {url: /profile/upload/videos/…, size}
 *  DELETE /kangaroo/store/profile/video/parts/{uploadId}          放弃（没有也算成功）
 * 视频上传都要 kangaroo:store:edit；后台账号在分段上传里用负数身份（KgVideoUploads.adminOwner），和小程序用户分开。
 */
@RestController
@RequestMapping("/kangaroo/store/profile")
public class KgStoreProfileController extends BaseController
{
    static final String CERT_PERM = "kangaroo:store:cert";

    @Autowired
    private KgStoreProfiles profiles;

    @Autowired
    private KgVideoUploads videos;

    private static Long owner()
    {
        return KgVideoUploads.adminOwner(SecurityUtils.getUserId());
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:edit')")
    @PostMapping("/license/upload")
    public AjaxResult uploadLicense(@org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file)
    {
        String path = KgLicenseWatermark.savePublic(file);
        AjaxResult result = AjaxResult.success();
        result.put("fileName", path);
        result.put("url", path);
        result.put("originalFilename", file.getOriginalFilename());
        return result;
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:query')")
    @GetMapping("/{storeId:[0-9]+}")
    public AjaxResult get(@PathVariable Long storeId)
    {
        return success(profiles.adminProfile(storeId));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:edit')")
    @Log(title = "门店资料", businessType = BusinessType.UPDATE)
    @PutMapping("/{storeId:[0-9]+}")
    public AjaxResult save(@PathVariable Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        // 认证权限在这里查好传进去：只有真要改成 / 取消实地探访时才用得上
        return success(profiles.save(storeId, body, SecurityUtils.hasPermi(CERT_PERM)));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:edit')")
    @PostMapping("/video/parts")
    public AjaxResult begin(@RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> b = body == null ? new LinkedHashMap<String, Object>() : body;
        return success(videos.partBeginInterview(owner(), b.get("name") == null ? null : String.valueOf(b.get("name")), b.get("size"), b.get("duration")));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:edit')")
    @PostMapping("/video/parts/{uploadId}/{index:[0-9]{1,4}}")
    public AjaxResult append(@PathVariable String uploadId, @PathVariable int index, HttpServletRequest request) throws Exception
    {
        return success(videos.partAppend(uploadId, owner(), index, request.getInputStream()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:edit')")
    @Log(title = "门店采访视频", businessType = BusinessType.INSERT)
    @PostMapping("/video/parts/{uploadId}/finish")
    public AjaxResult finish(@PathVariable String uploadId)
    {
        return success(videos.partFinish(uploadId, owner()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:edit')")
    @DeleteMapping("/video/parts/{uploadId}")
    public AjaxResult abort(@PathVariable String uploadId)
    {
        videos.partAbort(uploadId, owner());
        return success();
    }
}
