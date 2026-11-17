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
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.service.IKgStoreService;

/**
 * 门店管理（管理后台）
 */
@RestController
@RequestMapping("/kangaroo/store")
public class KgStoreController extends BaseController
{
    @Autowired
    private IKgStoreService kgStoreService;

    @PreAuthorize("@ss.hasPermi('kangaroo:store:list')")
    @GetMapping("/list")
    public TableDataInfo list(KgStore kgStore)
    {
        startPage();
        List<KgStore> list = kgStoreService.selectKgStoreList(kgStore);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:export')")
    @Log(title = "门店管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, KgStore kgStore)
    {
        List<KgStore> list = kgStoreService.selectKgStoreList(kgStore);
        ExcelUtil<KgStore> util = new ExcelUtil<KgStore>(KgStore.class);
        util.exportExcel(response, list, "门店数据");
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:query')")
    @GetMapping(value = "/{storeId}")
    public AjaxResult getInfo(@PathVariable("storeId") Long storeId)
    {
        return success(kgStoreService.requireStore(storeId));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:add')")
    @Log(title = "门店管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody KgStore kgStore)
    {
        kgStoreService.insertKgStore(kgStore);
        return success(kgStore.getStoreId());
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:edit')")
    @Log(title = "门店管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody KgStore kgStore)
    {
        return toAjax(kgStoreService.updateKgStore(kgStore));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:store:remove')")
    @Log(title = "门店管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{storeIds}")
    public AjaxResult remove(@PathVariable Long[] storeIds)
    {
        return toAjax(kgStoreService.deleteKgStoreByStoreIds(storeIds));
    }

    /**
     * 安心商家认证 / 撤销认证。
     * 单独成一个受权限保护的接口，避免通过通用编辑接口随意改认证标记。
     */
    @PreAuthorize("@ss.hasPermi('kangaroo:store:cert')")
    @Log(title = "门店安心认证", businessType = BusinessType.UPDATE)
    @PutMapping("/cert/{storeId}")
    public AjaxResult cert(@PathVariable Long storeId, @RequestParam boolean certified)
    {
        return toAjax(kgStoreService.certify(storeId, certified));
    }
}
