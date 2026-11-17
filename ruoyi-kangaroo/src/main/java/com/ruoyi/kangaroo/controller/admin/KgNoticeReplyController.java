package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgNoticeReplyService;

/** 公告管理页的「用户回复」。沿用公告查询 / 修改权限，普通员工授权不等于后台公告权限。 */
@RestController
@RequestMapping("/kangaroo/notice-replies")
public class KgNoticeReplyController extends BaseController
{
    @Autowired private KgNoticeReplyService replies;

    @PreAuthorize("@ss.hasPermi('kangaroo:appnotice:query')")
    @GetMapping
    public AjaxResult list(@RequestParam(required=false) String pending, @RequestParam(required=false) String keyword,
            @RequestParam(required=false) Integer pageNum, @RequestParam(required=false) Integer pageSize)
    { return success(replies.list(pending, keyword, pageNum, pageSize)); }

    @PreAuthorize("@ss.hasPermi('kangaroo:appnotice:query')")
    @GetMapping("/{threadId}")
    public AjaxResult detail(@PathVariable Long threadId, @RequestParam(required=false) Long before)
    { return success(replies.detail(threadId, before)); }

    @PreAuthorize("@ss.hasPermi('kangaroo:appnotice:edit')")
    @PostMapping("/{threadId}")
    public AjaxResult reply(@PathVariable Long threadId, @RequestBody Map<String,Object> body)
    { return success(replies.reply(threadId, getUserId(), body)); }
}
