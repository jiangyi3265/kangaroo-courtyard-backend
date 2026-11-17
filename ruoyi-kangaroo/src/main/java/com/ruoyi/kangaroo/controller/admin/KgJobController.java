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
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.service.IKgJobService;

/**
 * 岗位管理（管理后台）
 */
@RestController
@RequestMapping("/kangaroo/job")
public class KgJobController extends BaseController
{
    @Autowired
    private IKgJobService kgJobService;

    @PreAuthorize("@ss.hasPermi('kangaroo:job:list')")
    @GetMapping("/list")
    public TableDataInfo list(KgJob kgJob)
    {
        startPage();
        List<KgJob> list = kgJobService.selectKgJobList(kgJob);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:job:export')")
    @Log(title = "岗位管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, KgJob kgJob)
    {
        List<KgJob> list = kgJobService.selectKgJobList(kgJob);
        ExcelUtil<KgJob> util = new ExcelUtil<KgJob>(KgJob.class);
        util.exportExcel(response, list, "岗位数据");
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:job:query')")
    @GetMapping(value = "/{jobId}")
    public AjaxResult getInfo(@PathVariable("jobId") Long jobId)
    {
        return success(kgJobService.requireJob(jobId));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:job:add')")
    @Log(title = "岗位管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody KgJob kgJob)
    {
        kgJobService.insertKgJob(kgJob);
        return success(kgJob.getJobId());
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:job:edit')")
    @Log(title = "岗位管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody KgJob kgJob)
    {
        // 改了所属门店时，页面要告诉运营有几条报名一起转到了新门店（params 只用来带回这个数，不认请求里传来的）
        kgJob.getParams().remove("movedApps");
        AjaxResult result = toAjax(kgJobService.updateKgJob(kgJob));
        Object moved = kgJob.getParams().get("movedApps");
        return moved == null ? result : result.put("movedApps", moved);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:job:remove')")
    @Log(title = "岗位管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{jobIds}")
    public AjaxResult remove(@PathVariable Long[] jobIds)
    {
        return toAjax(kgJobService.deleteKgJobByJobIds(jobIds));
    }

    /**
     * 上下架（发布 / 暂停 / 恢复 / 关闭）。
     * 与编辑接口分开并单独授权：能改岗位文案的人不一定有权把岗位推到小程序首页。
     * 审核不通过时原因放在 remark 里传上来（后端会把它写进给门店的通知和岗位备注）；
     * 返回的 endedApps 是随岗位关闭一起结束的报名条数，页面据此提示运营。
     */
    @PreAuthorize("@ss.hasPermi('kangaroo:job:publish')")
    @Log(title = "岗位上下架", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody KgJob kgJob)
    {
        int ended = kgJobService.changeStatus(kgJob.getJobId(), kgJob.getStatus(), kgJob.getRemark());
        return success().put("endedApps", ended);
    }

    /**
     * 重算岗位的录用/报名计数。
     *
     * 存在的原因：hired_count 是冗余统计列，任何漏掉回退的写入路径都会让它和实际报名数据脱节，
     * 而一旦偏高，岗位就会被"名额已满"锁死、既不能录用也不能报名。
     * 这里提供一条不用连数据库就能纠偏的运维路径。不传 jobId 表示全量重算。
     */
    @PreAuthorize("@ss.hasPermi('kangaroo:job:recalc')")
    @Log(title = "重算岗位名额", businessType = BusinessType.UPDATE)
    @PutMapping("/recalc")
    public AjaxResult recalc(@RequestParam(required = false) Long jobId)
    {
        return success(kgJobService.recalcCounters(jobId));
    }
}
