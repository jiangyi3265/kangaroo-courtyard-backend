package com.ruoyi.kangaroo.controller.app;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgLegalDocuments;
import com.ruoyi.kangaroo.security.AppAuth;

/**
 * 协议与隐私全文：由运营在后台「系统管理 › 通知公告」发布标题为《隐私政策》《用户协议》的公告（状态正常），这里取最新一条原样下发。
 * 代码里不内置法律文本；没有发布时如实返回未配置。
 */
@RestController
@RequestMapping("/app/legal")
public class AppLegalController
{
    @Autowired
    private KgLegalDocuments documents;

    @AppAuth(required = false)
    @GetMapping("/{key}")
    public AjaxResult legal(@PathVariable String key, HttpServletResponse response)
    {
        response.setHeader("Cache-Control", "no-store");
        return AjaxResult.success(documents.read(key));
    }
}
