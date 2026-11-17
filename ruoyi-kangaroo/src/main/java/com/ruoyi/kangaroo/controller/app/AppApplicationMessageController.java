package com.ruoyi.kangaroo.controller.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.mapper.KgApplicationMapper;
import com.ruoyi.kangaroo.mapper.KgMessageMapper;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;

/** 报名页的消息联动。预览只读，已读仍走消息中心现有的本人单条已读接口。 */
@RestController
@RequestMapping("/app/application")
@AppAuth
public class AppApplicationMessageController
{
    @Autowired private KgApplicationMapper applications;
    @Autowired private KgMessageMapper messages;

    @GetMapping("/{appId}/messages")
    public AjaxResult messages(@PathVariable Long appId, @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") Integer size)
    {
        Long me = AppUserContext.require().getUserId();
        if (appId == null || appId <= 0 || (before != null && before <= 0))
            throw new ServiceException("报名或消息编号无效", 400);
        KgApplication application = applications.selectKgApplicationByAppId(appId);
        if (application == null || !me.equals(application.getAppUserId()) || !"0".equals(application.getDelFlag()))
            throw new ServiceException("报名记录不存在或无权查看", 404);
        int limit = size == null ? 20 : Math.max(1, Math.min(50, size));
        List<KgMessage> found = messages.selectApplicationNotices(me, appId, before, limit + 1);
        if (found == null) found = new ArrayList<KgMessage>();
        boolean more = found.size() > limit;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (KgMessage message : found.subList(0, Math.min(limit, found.size()))) rows.add(row(message));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("appId", appId);
        out.put("storeName", application.getStoreName());
        out.put("store", row(messages.selectApplicationChat(me, appId)));
        out.put("rows", rows);
        out.put("hasMore", more);
        out.put("nextBefore", rows.isEmpty() ? null : rows.get(rows.size() - 1).get("id"));
        out.put("unread", messages.countApplicationUnread(me, appId));
        return AjaxResult.success(out);
    }

    private static Map<String, Object> row(KgMessage message)
    {
        if (message == null) return null;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", message.getMsgId());
        out.put("category", message.getCategory());
        out.put("title", message.getTitle());
        out.put("content", AppMessageController.listContent(message));
        out.put("bizType", message.getBizType());
        out.put("bizId", message.getBizId());
        out.put("read", "1".equals(message.getReadFlag()));
        out.put("createTime", message.getCreateTime());
        return out;
    }
}
