package com.ruoyi.kangaroo.controller.app;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgJobReviewService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;

@RestController
@RequestMapping("/app/stores/{storeId}/job-reviews")
public class AppJobReviewController
{
    @Autowired private KgJobReviewService reviews;
    @Autowired private AppRateLimiter limiter;

    @AppAuth(required = false)
    @GetMapping("/summary")
    public AjaxResult summary(@PathVariable Long storeId, @RequestParam(required = false) String filter)
    {
        limiter.checkByIp("job-reviews", 300, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success(reviews.summary(storeId, filter));
    }
    @AppAuth(required = false)
    @GetMapping
    public AjaxResult list(@PathVariable Long storeId, @RequestParam(required = false) String filter, @RequestParam(defaultValue = "all") String tab,
            @RequestParam(defaultValue = "0") int days, @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String mention, @RequestParam(defaultValue = "1") int pageNum)
    {
        limiter.checkByIp("job-reviews", 300, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success(reviews.list(storeId, filter, tab, days, sort, mention, pageNum));
    }
}
