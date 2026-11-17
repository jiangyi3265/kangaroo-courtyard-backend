package com.ruoyi.kangaroo.controller.admin;

import java.util.List;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.kangaroo.domain.KgAppUser;
import com.ruoyi.kangaroo.service.IKgAppUserService;

/**
 * 小程序账号管理（管理后台）
 *
 * 只提供查询、停用/启用、导出三类能力：
 * 账号由微信登录创建，后台新增会造出没有 openid 的孤儿数据；
 * 删除会让已产生的报名、合同等记录失去归属，所以统一用"停用"代替删除。
 */
@RestController
@RequestMapping("/kangaroo/appuser")
public class KgAppUserController extends BaseController
{
    @Autowired
    private IKgAppUserService kgAppUserService;

    @PreAuthorize("@ss.hasPermi('kangaroo:appuser:list')")
    @GetMapping("/list")
    public TableDataInfo list(KgAppUser kgAppUser)
    {
        startPage();
        List<KgAppUser> list = kgAppUserService.selectKgAppUserList(kgAppUser);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:appuser:export')")
    @Log(title = "小程序账号", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, KgAppUser kgAppUser)
    {
        List<KgAppUser> list = kgAppUserService.selectKgAppUserList(kgAppUser);
        ExcelUtil<KgAppUser> util = new ExcelUtil<KgAppUser>(KgAppUser.class);
        util.exportExcel(response, list, "小程序账号数据");
    }

    /**
     * 账号详情。
     * 详情是单条、定向查询，可以带出 openid/unionid 供客服核对身份；列表则不下发。
     * 这里刻意不用 requireUser：后台必须能查看已停用账号的资料才能判断要不要恢复。
     */
    @PreAuthorize("@ss.hasPermi('kangaroo:appuser:query')")
    @GetMapping(value = "/{userId}")
    public AjaxResult getInfo(@PathVariable("userId") Long userId)
    {
        return success(kgAppUserService.selectKgAppUserByUserId(userId));
    }

    /**
     * 停用 / 启用账号。
     * 停用会同步把该账号的小程序会话踢下线，否则已登录的端在 token 过期前仍能继续操作。
     */
    @PreAuthorize("@ss.hasPermi('kangaroo:appuser:status')")
    @Log(title = "小程序账号", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody KgAppUser kgAppUser)
    {
        return toAjax(kgAppUserService.changeStatus(kgAppUser.getUserId(), kgAppUser.getStatus()));
    }
}
