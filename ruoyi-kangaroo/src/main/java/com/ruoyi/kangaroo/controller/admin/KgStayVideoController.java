package com.ruoyi.kangaroo.controller.admin;

import java.util.Collections;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;

/** 后台直接上传；文件归当前管理员所有，业务保存仍校验相应编辑权限。 */
@RestController
@RequestMapping("/kangaroo/stay-media/video")
public class KgStayVideoController extends BaseController
{
    static final String PERMISSIONS = "kangaroo:stay:add,kangaroo:stay:edit,kangaroo:roomtype:add,kangaroo:roomtype:edit,kangaroo:staydiary:add,kangaroo:staydiary:edit,kangaroo:job:add,kangaroo:job:edit";
    @Autowired private KgVideoUploads videos;
    private Long owner() { return KgVideoUploads.adminOwner(SecurityUtils.getUserId()); }

    @PreAuthorize("@ss.hasAnyPermi('" + PERMISSIONS + "')")
    @PostMapping("/parts")
    public AjaxResult begin(@RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> b = body == null ? Collections.emptyMap() : body;
        String kind = String.valueOf(b.get("kind"));
        // lodging（V66）：岗位的住宿视频，只有岗位权限的账号也能传；时长、大小和小院视频同一条规则（3 分钟、100MB）
        String entity = "intro".equals(kind) ? "stay" : "room".equals(kind) ? "roomtype" : "diary".equals(kind) ? "staydiary" : "lodging".equals(kind) ? "job" : "";
        if (entity.isEmpty()) throw new ServiceException("请选择小院、房型、生活碎片或住宿视频", 422);
        if (!SecurityUtils.hasPermi("kangaroo:" + entity + ":add") && !SecurityUtils.hasPermi("kangaroo:" + entity + ":edit"))
            throw new ServiceException("没有该内容的编辑权限", 403);
        return success(videos.partBegin(owner(), b.get("name") == null ? null : String.valueOf(b.get("name")), b.get("size"), b.get("duration"), "intro".equals(kind) || "lodging".equals(kind)));
    }
    @PreAuthorize("@ss.hasAnyPermi('" + PERMISSIONS + "')")
    @PostMapping("/parts/{uploadId}/{index:[0-9]{1,4}}")
    public AjaxResult append(@PathVariable String uploadId, @PathVariable int index, HttpServletRequest request) throws Exception
    { return success(videos.partAppend(uploadId, owner(), index, request.getInputStream())); }
    @PreAuthorize("@ss.hasAnyPermi('" + PERMISSIONS + "')")
    @PostMapping("/parts/{uploadId}/finish")
    public AjaxResult finish(@PathVariable String uploadId)
    { return success(videos.partFinish(uploadId, owner())); }
    @PreAuthorize("@ss.hasAnyPermi('" + PERMISSIONS + "')")
    @DeleteMapping("/parts/{uploadId}")
    public AjaxResult abort(@PathVariable String uploadId)
    { videos.partAbort(uploadId, owner()); return success(); }
}
