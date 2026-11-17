package com.ruoyi.kangaroo.controller.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.manage.KgDashboardService;

/**
 * 管理后台「数据看板」（2026-10-18；菜单 袋鼠小院 → 数据看板，V55 加的；首页也用同一份数据）。只读，口径见 {@link KgDashboardService}：
 *  GET /kangaroo/dashboard/overview?date=yyyy-MM-dd&city=&district=&host=&stayId=   总数据 + 当日数据 + 城市 → 区县 → 小院
 *  GET /kangaroo/dashboard/stay/{stayId}?date=                                       一个小院当天各房型的库存 / 在住 / 空房和订单、各房间的房态
 * 都要 kangaroo:dashboard:view；date 不传为今天（北京时间），只能看近一年到未来 180 天。
 */
@RestController
@RequestMapping("/kangaroo/dashboard")
public class KgDashboardController extends BaseController
{
    @Autowired
    private KgDashboardService dashboard;

    @PreAuthorize("@ss.hasPermi('kangaroo:dashboard:view')")
    @GetMapping("/overview")
    public AjaxResult overview(@RequestParam(required = false) String date, @RequestParam(required = false) String city,
            @RequestParam(required = false) String district, @RequestParam(required = false) String host,
            @RequestParam(required = false) Long stayId)
    {
        return success(dashboard.overview(date, city, district, host, stayId));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:dashboard:view')")
    @GetMapping("/stay/{stayId:[0-9]+}")
    public AjaxResult stay(@PathVariable Long stayId, @RequestParam(required = false) String date)
    {
        return success(dashboard.stay(stayId, date));
    }
}
