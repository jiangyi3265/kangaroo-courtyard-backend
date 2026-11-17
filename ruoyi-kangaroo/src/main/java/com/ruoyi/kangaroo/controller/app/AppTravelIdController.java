package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgTravelIdService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 本人的出行证件（V69，2026-11-03 主人：「订票以后让他提交这个身份证号」）。
 * 证件号只走请求体（PUT），读回来的只有脱敏的；完整证件号只在门店导出名单时出现（AppStaffApplicantExportController）。
 */
@RestController
@RequestMapping("/app/me/travel-id")
public class AppTravelIdController
{
    @Autowired
    private KgTravelIdService travelIds;

    @AppAuth
    @GetMapping
    public AjaxResult mine()
    {
        return AjaxResult.success(travelIds.mine(AppUserContext.getUserId()));
    }

    @AppAuth
    @PutMapping
    public AjaxResult save(@RequestBody(required = false) Map<String, String> body)
    {
        return AjaxResult.success("证件信息已保存，门店订票、买保险时会用到", travelIds.save(AppUserContext.getUserId(),
                body == null ? null : body.get("name"), body == null ? null : body.get("idNo")));
    }
}
