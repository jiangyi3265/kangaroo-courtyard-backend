package com.ruoyi.kangaroo.controller.admin;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.util.*;
import java.time.*;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.operations.*;

@RestController @RequestMapping("/kangaroo/operations")
public class KgOperationsController {
    @Autowired private OpsDaily daily;
    @Autowired private OpsMonthly monthly;
    @Autowired private OpsTargets targets;
    @Autowired private OpsQueries queries;
    @Autowired private OpsNotices notices;
    @Autowired private OpsExport export;
    @Autowired private OpsBatchExport batch;
    @Autowired private OpsReminders reminders;
    @Autowired private OpsForms forms;
    @Autowired private OpsDailyChanges changes;
    @Autowired private OpsViewerGrants viewers;
    @GetMapping("/viewers") @PreAuthorize("@ss.hasPermi('kangaroo:operations:viewer')") public AjaxResult viewers(){return AjaxResult.success(viewers.list());}
    @GetMapping("/viewers/search") @PreAuthorize("@ss.hasPermi('kangaroo:operations:viewer')") public AjaxResult viewerSearch(@RequestParam String query){return AjaxResult.success(viewers.search(query));}
    @GetMapping("/viewers/{uid}/changes") @PreAuthorize("@ss.hasPermi('kangaroo:operations:viewer')") public AjaxResult viewerHistory(@PathVariable long uid){return AjaxResult.success(viewers.history(uid));}
    @PutMapping("/viewers/{uid}") @PreAuthorize("@ss.hasPermi('kangaroo:operations:viewer')") @Log(title="手机报表查阅授权",businessType=BusinessType.UPDATE) public AjaxResult viewerSave(@PathVariable long uid,@RequestBody Map<String,Object> body){return AjaxResult.success(viewers.save(uid,body,SecurityUtils.getUserId(),SecurityUtils.getUsername()));}
    @GetMapping("/forms/{role}") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult form(@PathVariable String role){return AjaxResult.success(forms.current(role));}
    @GetMapping("/forms/{role}/changes") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult formChanges(@PathVariable String role){return AjaxResult.success(forms.history(role));}
    @PutMapping("/forms/{role}") @PreAuthorize("@ss.hasPermi('kangaroo:operations:form')") @Log(title="配置日报填写项目",businessType=BusinessType.UPDATE) public AjaxResult form(@PathVariable String role,@RequestBody Map<String,Object> body){return AjaxResult.success(forms.save(role,body,SecurityUtils.getUserId(),SecurityUtils.getUsername()));}
    @PutMapping("/daily/{id}/correction") @PreAuthorize("@ss.hasPermi('kangaroo:operations:correct')") @Log(title="修改员工日报",businessType=BusinessType.UPDATE) public AjaxResult correct(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(changes.save(id,body,SecurityUtils.getUserId(),SecurityUtils.getUsername()));}
    @GetMapping("/daily/{id}/changes") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult changes(@PathVariable long id){return AjaxResult.success(changes.history(id));}
    @GetMapping("/reminders/preview") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')")
    public AjaxResult preview(@RequestParam long cityId,@RequestParam String kind,@RequestParam String date,@RequestParam(defaultValue="") String roleCode,@RequestParam(required=false) Long ownerId){return AjaxResult.success(reminders.preview(cityId,kind,date,roleCode,ownerId));}
    @PostMapping("/reminders") @PreAuthorize("@ss.hasPermi('kangaroo:operations:remind')") @Log(title="发送日报周报催报",businessType=BusinessType.OTHER)
    public AjaxResult remind(@RequestBody Map<String,Object> body){return AjaxResult.success(reminders.send(body,SecurityUtils.getUserId(),SecurityUtils.getUsername()));}
    @GetMapping("/reminders") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')")
    public AjaxResult reminders(@RequestParam long cityId){return AjaxResult.success(reminders.history(cityId));}
    @GetMapping("/reminders/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')")
    public AjaxResult reminder(@PathVariable long id){return AjaxResult.success(reminders.detail(id));}
    @GetMapping("/options") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult options(){return AjaxResult.success(queries.options());}
    @GetMapping("/owners") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult owners(@RequestParam long cityId,@RequestParam String roleCode){return AjaxResult.success(queries.owners(cityId,roleCode));}
    @GetMapping("/stores") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult stores(@RequestParam long cityId){return AjaxResult.success(queries.stores(cityId));}
    @GetMapping("/daily") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult list(@RequestParam(required=false) String dateFrom,@RequestParam(required=false) String dateTo,@RequestParam(required=false) Long cityId,@RequestParam(required=false) String roleCode,@RequestParam(required=false) Long ownerId,@RequestParam(required=false) String status,@RequestParam(required=false) Integer pageNum){return AjaxResult.success(daily.list(dateFrom,dateTo,cityId,roleCode,ownerId,status,pageNum));}
    @GetMapping("/daily/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult daily(@PathVariable long id){return AjaxResult.success(daily.adminGet(id));}
    @GetMapping("/targets") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult targets(@RequestParam String month,@RequestParam(required=false) Long cityId,@RequestParam(required=false) String roleCode,@RequestParam(required=false) Long ownerId){return AjaxResult.success(targets.calculate(targets.list(month,cityId,roleCode,ownerId),LocalDateTime.now(ZONE)));}
    @PostMapping("/targets") @PreAuthorize("@ss.hasPermi('kangaroo:operations:edit')") @Log(title="月度任务目标",businessType=BusinessType.UPDATE) public AjaxResult save(@RequestBody Map<String,Object> body){return AjaxResult.success(targets.save(body,SecurityUtils.getUsername()));}
    @PostMapping("/targets/copy") @PreAuthorize("@ss.hasPermi('kangaroo:operations:edit')") @Log(title="复制月度任务模板",businessType=BusinessType.INSERT) public AjaxResult copy(@RequestBody Map<String,Object> body){return AjaxResult.success(targets.copyTemplate(body,SecurityUtils.getUsername()));}
    @GetMapping("/monthly") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult monthly(@RequestParam String month,@RequestParam(required=false) Long cityId,@RequestParam(required=false) String roleCode,@RequestParam(required=false) Long ownerId){return AjaxResult.success(monthly.list(month,cityId,roleCode,ownerId));}
    @GetMapping("/monthly/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult monthly(@PathVariable long id){return AjaxResult.success(monthly.adminGet(id));}
    @PostMapping("/monthly") @PreAuthorize("@ss.hasPermi('kangaroo:operations:edit')") @Log(title="生成月度工作总结",businessType=BusinessType.INSERT) public AjaxResult generate(@RequestBody Map<String,Object> body){Long city=KgJdbc.asLong(body.get("cityId")),owner=KgJdbc.asLong(body.get("ownerId"));require(city!=null&&city>0,"请选择具体地区");return AjaxResult.success(monthly.generate(text(body.get("monthKey")),city,text(body.get("roleCode")),owner==null?0:owner));}
    @PostMapping("/monthly/{id}/send") @PreAuthorize("@ss.hasPermi('kangaroo:operations:edit')") @Log(title="发送月度工作总结",businessType=BusinessType.OTHER) public AjaxResult send(@PathVariable long id){return AjaxResult.success(map("sent",notices.sendMonthly(id)));}
    @GetMapping("/issues") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult issues(@RequestParam(required=false) Long cityId,@RequestParam(required=false) String status,@RequestParam(required=false) Integer pageNum,@RequestParam(required=false) String dateFrom,@RequestParam(required=false) String dateTo){return AjaxResult.success(queries.issues(cityId,status,pageNum,dateFrom,dateTo));}
    @GetMapping("/export-batch/{kind}") @PreAuthorize("@ss.hasPermi('kangaroo:operations:export')") @Log(title="批量导出工作报表",businessType=BusinessType.EXPORT)
    public void batch(@PathVariable String kind,@RequestParam(required=false) String month,@RequestParam(required=false) String dateFrom,@RequestParam(required=false) String dateTo,@RequestParam(required=false) Long cityId,@RequestParam(required=false) String roleCode,@RequestParam(required=false) Long ownerId,@RequestParam(required=false) String status,@RequestParam(defaultValue="xlsx") String format,HttpServletResponse response)throws IOException{export.respond(response,format,batch.rows(kind,month,dateFrom,dateTo,cityId,roleCode,ownerId,status),"工作报表汇总-"+kind);}
    @GetMapping("/results") @PreAuthorize("@ss.hasPermi('kangaroo:operations:query')") public AjaxResult results(@RequestParam long cityId){return AjaxResult.success(queries.results(cityId));}
    @PostMapping("/results") @PreAuthorize("@ss.hasPermi('kangaroo:operations:edit')") @Log(title="登记合作签约结果",businessType=BusinessType.UPDATE) public AjaxResult result(@RequestBody Map<String,Object> body){return AjaxResult.success(targets.result(body,SecurityUtils.getUsername()));}
    @GetMapping("/export/{kind}/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:operations:export')") @Log(title="导出工作报表",businessType=BusinessType.EXPORT) public void export(@PathVariable String kind,@PathVariable long id,@RequestParam(defaultValue="xlsx") String format,HttpServletResponse response)throws IOException{require(Arrays.asList("daily","monthly").contains(kind),"报表类型无效");export.respond(response,format,export.rows("daily".equals(kind)?daily.adminGet(id):monthly.adminGet(id)),"工作报表-"+id);}
    @GetMapping("/export/targets") @PreAuthorize("@ss.hasPermi('kangaroo:operations:export')") public void boardExport(@RequestParam String month,@RequestParam(required=false) Long cityId,@RequestParam(required=false) String roleCode,@RequestParam(required=false) Long ownerId,@RequestParam(defaultValue="xlsx") String format,HttpServletResponse response)throws IOException{
        List<List<String>> rows=new ArrayList<>();rows.add(Arrays.asList("城市","人员","指标","目标 / 完成","差额","状态"));for(Map<String,Object> r:targets.calculate(targets.list(month,cityId,roleCode,ownerId),LocalDateTime.now(ZONE)))rows.add(Arrays.asList(text(r.get("cityName")),text(r.get("ownerName")),text(r.get("label")),r.get("target")+" / "+r.get("completed"),text(r.get("missing")),text(r.get("statusLabel"))));export.respond(response,format,rows,"月度进度-"+month);
    }
}
