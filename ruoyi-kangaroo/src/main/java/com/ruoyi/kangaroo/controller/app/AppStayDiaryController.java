package com.ruoyi.kangaroo.controller.app;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.stay.KgStayMediaService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 小院「近期生活碎片」页（2026-10-17 旅居改版 R3，主人效果图）：游客可看。
 *  GET /app/stay/{stayId}/diary?page=1  → {header: {bg, title, subtitle, lines, stayName}, rows: [{diaryId, type, url, cover, caption, date, shotDate}], total, hasMore, pageNum}
 * 只给显示中（status = '0'、没删）的，房源要在架（与详情页同一口径）。
 */
@RestController
@RequestMapping("/app/stay")
public class AppStayDiaryController
{
    @Autowired
    private KgStayMediaService media;

    @Autowired
    private AppRateLimiter limiter;

    @AppAuth(required = false)
    @GetMapping("/{stayId}/diary")
    public AjaxResult diary(@PathVariable Long stayId, @RequestParam(defaultValue = "1") int page)
    {
        limiter.checkByIp("stay-diary", 300, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success(media.diary(stayId, page));
    }
}
