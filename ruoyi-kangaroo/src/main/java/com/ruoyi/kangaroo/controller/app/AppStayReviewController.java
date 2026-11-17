package com.ruoyi.kangaroo.controller.app;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.stay.KgStayReviewService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 小院「住过的家人评价」页（2026-10-17 改版 R2）：游客可看。和 AppStayController 同一个前缀，单独一个类，
 * 免得和同时在改详情 / 列表接口的那一批改动挤在一个文件里。
 *  GET /app/stay/{stayId}/reviews/summary  综合分、推荐率、分项、大家提到、页签条数、房型 / 入住月份筛选项
 *  GET /app/stay/{stayId}/reviews          ?tab=all|media|bad&roomTypeId=&month=yyyy-MM&mention=&pageNum=&pageSize=
 */
@RestController
@RequestMapping("/app/stay")
public class AppStayReviewController
{
    @Autowired
    private KgStayReviewService reviews;

    @Autowired
    private AppRateLimiter limiter;

    @AppAuth(required = false)
    @GetMapping("/{stayId}/reviews/summary")
    public AjaxResult summary(@PathVariable Long stayId)
    {
        limiter.checkByIp("stay-reviews", 300, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success(reviews.summary(stayId));
    }

    @AppAuth(required = false)
    @GetMapping("/{stayId}/reviews")
    public AjaxResult list(@PathVariable Long stayId, @RequestParam(required = false) String tab, @RequestParam(required = false) Long roomTypeId,
            @RequestParam(required = false) String month, @RequestParam(required = false) String mention,
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "10") int pageSize)
    {
        limiter.checkByIp("stay-reviews", 300, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success(reviews.list(stayId, tab, roomTypeId, month, mention, pageNum, pageSize));
    }
}
