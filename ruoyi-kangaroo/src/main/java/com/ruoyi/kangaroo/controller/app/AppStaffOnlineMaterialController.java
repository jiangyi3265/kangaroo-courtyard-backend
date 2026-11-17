package com.ruoyi.kangaroo.controller.app;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialFiles;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialService;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 员工端线上任务「发素材」：门店接单后，把文字说明、最多 20 张图片和最多 5 个文件（压缩包 / 文档，V41）发给这位接单人（可以多次补发）。
 * 权限与验收同一档（线上任务·编辑），线上任务专员也能发；只是信息展示，不改成果提交和结算的状态。
 * 文件先单独上传（material-files），发送时把文件 ID 带在 files 里；没发出去的 24 小时后自动清理。
 */
@RestController
@RequestMapping("/app/staff/online")
public class AppStaffOnlineMaterialController
{
    @Autowired
    private KgOnlineMaterialService materials;

    @Autowired
    private KgOnlineMaterialFiles files;

    @Autowired
    private KgOnlineTaskService online;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgJdbc db;

    @Autowired
    private AppRateLimiter limiter;

    /** 接单记录所属门店的员工上下文（与 AppStaffExtController.appCtx 同一口径：只认线上任务的报名） */
    private StaffSupport.Ctx appCtx(Long appId)
    {
        Map<String, Object> r = db.one("select j.store_id from kg_application a join kg_job j on j.job_id = a.job_id where a.app_id = :id and a.del_flag = '0' and j.job_type = 'online'",
                KgJdbc.p("id", appId));
        if (r == null)
        {
            throw new ServiceException("接单记录不存在", 404);
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    /**
     * 发素材：body = {content: 文字说明（1 万字内）, images: [平台图片地址，最多 20 张], files: [先上传的附件 ID，最多 5 个]}；
     * 返回最新的接单记录（含已发素材和附件）
     */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/assignments/{appId}/materials")
    public AjaxResult send(@PathVariable Long appId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.EDIT, "online_material", "发送任务素材");
        limiter.check("staff:online-material", String.valueOf(c.uid()), 60, 3600, "发送太频繁了，稍后再试");
        Map<String, Object> r = materials.send(c, appId, body == null ? new LinkedHashMap<String, Object>() : body);
        return AjaxResult.success(String.valueOf(r.get("message")), online.assignment(c, appId));
    }

    /**
     * 上传一个素材附件（multipart：file + name 原文件名；小程序上传时的文件名是临时名，原名只能放在 name 里）。
     * 只收 zip / rar / 7z / PDF / Word / Excel / PPT，文件头要和扩展名对得上；这里整个传的只到 10MB（页面 8MB 以上走下面的分段上传，单个最多 100MB）。
     * 返回 {fileId, name, size, sizeLabel, ext, kind, openable}
     */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/assignments/{appId}/material-files")
    public AjaxResult upload(@PathVariable Long appId, @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "name", required = false) String name)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.EDIT, "online_material", "上传素材附件");
        limiter.check("staff:online-material-file", String.valueOf(c.uid()), 30, 3600, "上传文件太频繁了，请一小时后再试");
        return AjaxResult.success(materials.upload(c, appId, file, name));
    }

    /**
     * 大文件分段上传（2026-10-16，超过 8MB 的；单个最多 100MB）第一步：body = {name: 原文件名, size: 字节数}。
     * 和整个上传共用「上传文件」的频率限制（一个文件算一次）。返回 {uploadId, partBytes 每段字节数, parts 一共几段}
     */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/assignments/{appId}/material-files/parts")
    public AjaxResult partBegin(@PathVariable Long appId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.EDIT, "online_material", "上传素材附件");
        limiter.check("staff:online-material-file", String.valueOf(c.uid()), 30, 3600, "上传文件太频繁了，请一小时后再试");
        Map<String, Object> b = body == null ? new LinkedHashMap<String, Object>() : body;
        return AjaxResult.success(materials.partBegin(c, appId, b.get("name") == null ? null : String.valueOf(b.get("name")), b.get("size")));
    }

    /** 传第 index 段（从 0 起）：请求体就是这一段的原始字节（application/octet-stream，不超过 8MB）。返回 {received, size} */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/assignments/{appId}/material-files/parts/{uploadId}/{index:[0-9]{1,4}}")
    public AjaxResult partAppend(@PathVariable Long appId, @PathVariable String uploadId, @PathVariable int index, HttpServletRequest request) throws Exception
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.EDIT, "online_material", "上传素材附件");
        limiter.check("staff:online-material-part", String.valueOf(c.uid()), 600, 3600, "上传文件太频繁了，请一小时后再试");
        return AjaxResult.success(files.partAppend(uploadId, appId, c.uid(), index, request.getInputStream()));
    }

    /** 分段都传完了：核对后存成附件，返回和整个上传一样的 {fileId, name, size, sizeLabel, ext, kind, openable} */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @PostMapping("/assignments/{appId}/material-files/parts/{uploadId}/finish")
    public AjaxResult partFinish(@PathVariable Long appId, @PathVariable String uploadId)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.EDIT, "online_material", "上传素材附件");
        return AjaxResult.success(materials.partFinish(c, appId, uploadId));
    }

    /** 放弃一个分段上传（传失败、取消时页面调；没有也算成功） */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @DeleteMapping("/assignments/{appId}/material-files/parts/{uploadId}")
    public AjaxResult partAbort(@PathVariable Long appId, @PathVariable String uploadId)
    {
        StaffSupport.Ctx c = appCtx(appId);
        files.partAbort(uploadId, appId, c.uid());
        return AjaxResult.success();
    }

    /** 移除一个还没发出的附件（发出的不能移除）；记录先标记清理，提交后再删磁盘文件 */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @DeleteMapping("/assignments/{appId}/material-files/{fileId}")
    public AjaxResult remove(@PathVariable Long appId, @PathVariable Long fileId)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.EDIT, "online_material", "移除素材附件");
        files.delete(materials.removePending(c, appId, fileId));
        return AjaxResult.success();
    }

    /** 门店下载附件（本店「线上任务·查看」；还没发出的也能下载核对） */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @GetMapping("/assignments/{appId}/material-files/{fileId}")
    public void download(@PathVariable Long appId, @PathVariable Long fileId, HttpServletResponse response) throws Exception
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.VIEW, "online_view", "查看线上任务");
        files.serve(materials.storeFile(c, appId, fileId), response);
    }

    /** 已发出附件的下载链接（3 天内有效，拿到链接的人都能下载）：{url, expireAt, hours} */
    @AppAuth(staff = true, onlineStaff = true, merchant = true, merchantService = true)
    @GetMapping("/assignments/{appId}/material-files/{fileId}/link")
    public AjaxResult link(@PathVariable Long appId, @PathVariable Long fileId)
    {
        StaffSupport.Ctx c = appCtx(appId);
        support.need(c, "online", StaffPerms.VIEW, "online_view", "查看线上任务");
        limiter.check("online:material-link", String.valueOf(c.uid()), 60, 3600, "复制链接太频繁了，稍后再试");
        return AjaxResult.success(materials.link(materials.storeFile(c, appId, fileId)));
    }
}
