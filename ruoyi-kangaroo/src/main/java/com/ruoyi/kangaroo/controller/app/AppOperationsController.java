package com.ruoyi.kangaroo.controller.app;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.util.*;
import java.time.*;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.operations.*;
import com.ruoyi.kangaroo.security.*;

@RestController @RequestMapping("/app/operations")
public class AppOperationsController {
    @Autowired private OpsScope scope;
    @Autowired private OpsDaily daily;
    @Autowired private OpsMonthly monthly;
    @Autowired private OpsTargets targets;
    @Autowired private OpsQueries queries;
    @Autowired private OpsExport export;
    @Autowired private OpsDailyChanges changes;
    private long me(){return AppUserContext.getUserId();}
    @AppAuth(staff=true) @GetMapping("/access") public AjaxResult access(){return AjaxResult.success(map("scopes",scope.owned(me()),"observerCities",scope.observerCities(me()),"today",LocalDate.now(ZONE).toString(),"month",YearMonth.now(ZONE).toString(),"serverTime",LocalDateTime.now(ZONE).format(DT)));}
    @AppAuth(staff=true) @PostMapping("/daily/open") public AjaxResult open(@RequestBody Map<String,Object> b){Long city=KgJdbc.asLong(b.get("cityId"));require(city!=null,"请选择地区");return AjaxResult.success(daily.open(me(),city,text(b.get("roleCode")),text(b.get("date"))));}
    @AppAuth(staff=true) @GetMapping("/daily/{id}") public AjaxResult get(@PathVariable long id){return AjaxResult.success(daily.get(me(),id));}
    @AppAuth(staff=true) @GetMapping("/daily/{id}/changes") public AjaxResult changes(@PathVariable long id){return AjaxResult.success(changes.forUser(me(),id));}
    @AppAuth(staff=true) @PutMapping("/daily/{id}/draft") public AjaxResult save(@PathVariable long id,@RequestBody Map<String,Object> b){return AjaxResult.success(daily.save(me(),id,b,false));}
    @AppAuth(staff=true) @PostMapping("/daily/{id}/submit") public AjaxResult submit(@PathVariable long id,@RequestBody Map<String,Object> b){return AjaxResult.success(daily.save(me(),id,b,true));}
    @AppAuth(staff=true) @GetMapping("/history") public AjaxResult history(@RequestParam String month){return AjaxResult.success(map("mine",daily.mine(me(),month),"observed",queries.observed(me(),month),"monthly",monthly.mine(me(),month)));}
    @AppAuth(staff=true) @GetMapping("/progress") public AjaxResult progress(@RequestParam long cityId,@RequestParam String roleCode,@RequestParam String month){scope.requireOwned(me(),cityId,roleCode);return AjaxResult.success(targets.progress(cityId,roleCode,me(),month(month),LocalDateTime.now(ZONE)));}
    @AppAuth(staff=true) @GetMapping("/monthly/{id}") public AjaxResult monthly(@PathVariable long id){return AjaxResult.success(monthly.get(me(),id));}
    @AppAuth(staff=true) @GetMapping("/export/{kind}/{id}") public void export(@PathVariable String kind,@PathVariable long id,@RequestParam(defaultValue="pdf") String format,HttpServletResponse response)throws IOException{require(Arrays.asList("daily","monthly").contains(kind),"报表类型无效");Map<String,Object> row="daily".equals(kind)?daily.get(me(),id):monthly.get(me(),id);export.respond(response,format,export.rows(row),"工作报表-"+id);}
}
