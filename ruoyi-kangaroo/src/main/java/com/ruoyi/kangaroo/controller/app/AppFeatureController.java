package com.ruoyi.kangaroo.controller.app;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityFeature;
import com.ruoyi.kangaroo.security.AppAuth;

@RestController
@RequestMapping("/app/features")
public class AppFeatureController {
    private final KgCommunityFeature community;
    public AppFeatureController(KgCommunityFeature community) { this.community=community; }
    @AppAuth(required=false)
    @GetMapping
    public AjaxResult features(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return AjaxResult.success(KgJdbc.p("recruitmentEnabled", community.recruitmentEnabled(), "stayBookingEnabled", community.stayBookingEnabled(), "communityEnabled", community.enabled(), "wishEnabled", community.wishEnabled(), "groupChatEnabled", community.groupChatEnabled(), "privateChatEnabled", community.privateChatEnabled()));
    }
}
