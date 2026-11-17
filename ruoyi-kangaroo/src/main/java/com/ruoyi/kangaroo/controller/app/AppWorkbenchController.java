package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgWorkbenchService;
import com.ruoyi.kangaroo.security.AppAuth;

/** 门店工作台：待办、任务详情、转派与备注、今日经营数据、简历转发 */
@RestController
@RequestMapping("/app/staff/workbench")
public class AppWorkbenchController
{
    @Autowired
    private KgWorkbenchService workbench;

    /** 同 AppMeController.msg：文本结果统一放进 data.message */
    private static Map<String, Object> msg(String text)
    {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("message", text);
        return m;
    }

    @AppAuth(staff = true)
    @GetMapping("/home")
    public AjaxResult home(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(workbench.home(storeId));
    }

    @AppAuth(staff = true)
    @GetMapping("/todos")
    public AjaxResult todos(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String tab,
            @RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(workbench.todos(storeId, tab, keyword));
    }

    @AppAuth(staff = true)
    @GetMapping("/todo")
    public AjaxResult detail(@RequestParam(required = false) Long storeId, @RequestParam String key)
    {
        return AjaxResult.success(workbench.detail(storeId, key));
    }

    @AppAuth(staff = true)
    @GetMapping("/metrics")
    public AjaxResult metrics(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(workbench.metrics(storeId));
    }

    @AppAuth(staff = true)
    @PostMapping("/assign")
    public AjaxResult assign(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(msg(workbench.assign(KgJdbc.asLong(body.get("storeId")), String.valueOf(body.get("key")), KgJdbc.asLong(body.get("ownerId")))));
    }

    @AppAuth(staff = true)
    @PostMapping("/note")
    public AjaxResult note(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(msg(workbench.note(KgJdbc.asLong(body.get("storeId")), String.valueOf(body.get("key")), KgJdbc.asStr(body.get("note")))));
    }

    /** 生成待办清单并发到自己的消息中心 */
    @AppAuth(staff = true)
    @PostMapping("/digest")
    public AjaxResult digest(@RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(workbench.digest(body == null ? null : KgJdbc.asLong(body.get("storeId"))));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.ruoyi.kangaroo.ext.staff.KgResumeShareService shares;

    @AppAuth(staff = true)
    @GetMapping("/resume-poster")
    public AjaxResult resumePoster(@RequestParam Long storeId, @RequestParam Long appId) {
        return AjaxResult.success(shares.poster(storeId,appId));
    }

    @AppAuth(staff = true)
    @GetMapping("/forward-stores")
    public AjaxResult forwardStores(@RequestParam Long storeId, @RequestParam(required=false) String q) {
        return AjaxResult.success(shares.stores(storeId,q));
    }
    @AppAuth(staff = true)
    @GetMapping("/shared-resume/{id}")
    public AjaxResult sharedResume(@PathVariable Long id) { return AjaxResult.success(shares.view(id)); }

    // ---------------------------------------------------------------- 简历转发

    @AppAuth(staff = true)
    @GetMapping("/forwards")
    public AjaxResult forwards(@RequestParam(required = false) Long storeId, @RequestParam Long appId)
    {
        return AjaxResult.success(workbench.forwards(workbench.storeOf(storeId), appId));
    }

    @AppAuth(staff = true)
    @PostMapping("/forward")
    public AjaxResult forward(@RequestBody Map<String, Object> body)
    {
        if ("cross_store".equals(KgJdbc.asStr(body.get("target")))) {
            return AjaxResult.success(shares.send(KgJdbc.asLong(body.get("storeId")),KgJdbc.asLong(body.get("appId")),KgJdbc.asLong(body.get("targetStoreId")),
                KgJdbc.asStr(body.get("suggestion")),!"0".equals(KgJdbc.asStr(body.get("track")))));
        }
        return AjaxResult.success(workbench.forward(KgJdbc.asLong(body.get("storeId")), KgJdbc.asLong(body.get("appId")),
                KgJdbc.asStr(body.get("target")), KgJdbc.asStr(body.get("suggestion")), !"0".equals(KgJdbc.asStr(body.get("track")))));
    }
}
