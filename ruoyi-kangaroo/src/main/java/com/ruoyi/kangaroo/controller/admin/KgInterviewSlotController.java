package com.ruoyi.kangaroo.controller.admin;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.kangaroo.domain.KgInterviewSlot;
import com.ruoyi.kangaroo.service.IKgInterviewSlotService;

/**
 * 面试时段管理（管理后台）
 */
@RestController
@RequestMapping("/kangaroo/slot")
public class KgInterviewSlotController extends BaseController
{
    @Autowired
    private IKgInterviewSlotService kgInterviewSlotService;

    @PreAuthorize("@ss.hasPermi('kangaroo:slot:list')")
    @GetMapping("/list")
    public TableDataInfo list(KgInterviewSlot kgInterviewSlot)
    {
        startPage();
        List<KgInterviewSlot> list = kgInterviewSlotService.selectKgInterviewSlotList(kgInterviewSlot);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:slot:query')")
    @GetMapping(value = "/{slotId}")
    public AjaxResult getInfo(@PathVariable("slotId") Long slotId)
    {
        return success(kgInterviewSlotService.requireSlot(slotId));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:slot:add')")
    @Log(title = "面试时段", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody KgInterviewSlot kgInterviewSlot)
    {
        kgInterviewSlotService.insertKgInterviewSlot(kgInterviewSlot);
        return success(kgInterviewSlot.getSlotId());
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:slot:edit')")
    @Log(title = "面试时段", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody KgInterviewSlot kgInterviewSlot)
    {
        return toAjax(kgInterviewSlotService.updateKgInterviewSlot(kgInterviewSlot));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:slot:remove')")
    @Log(title = "面试时段", businessType = BusinessType.DELETE)
    @DeleteMapping("/{slotIds}")
    public AjaxResult remove(@PathVariable Long[] slotIds)
    {
        return toAjax(kgInterviewSlotService.deleteKgInterviewSlotBySlotIds(slotIds));
    }
}
