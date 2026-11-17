package com.ruoyi.kangaroo.controller.app;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.manage.KgManageService;
import com.ruoyi.kangaroo.ext.manage.KgTaskService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/** 经营：周报 / 日报 / 数据看板（主理人、管家）与门店任务（员工 + 在店换工） */
@RestController
@RequestMapping("/app/manage")
public class AppManageController
{
    @Autowired
    private KgManageService manage;

    @Autowired
    private KgTaskService tasks;

    @Autowired
    private AppRateLimiter limiter;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    // ---------------------------------------------------------------- 周报

    @AppAuth(staff = true)
    @GetMapping("/weekly")
    public AjaxResult weekly(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String weekStart,
            @RequestParam(required = false) String mode)
    {
        return AjaxResult.success(manage.weeklyList(storeId, weekStart, mode));
    }

    @AppAuth(staff = true)
    @PostMapping("/weekly/open")
    public AjaxResult weeklyOpen(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(manage.weeklyOpen(KgJdbc.asLong(body.get("storeId")), s(body, "weekStart")));
    }

    @AppAuth(staff = true)
    @GetMapping("/weekly/{reportId}")
    public AjaxResult weeklyDetail(@PathVariable Long reportId)
    {
        return AjaxResult.success(manage.weeklyDetail(reportId));
    }

    @AppAuth(staff = true)
    @GetMapping("/weekly/summary")
    public AjaxResult weeklySummary(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String weekStart)
    {
        return AjaxResult.success(manage.weeklySummary(storeId, weekStart));
    }

    @AppAuth(staff = true)
    @PutMapping("/weekly/{reportId}/draft")
    public AjaxResult weeklySave(@PathVariable Long reportId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(manage.weeklySave(reportId, body));
    }

    @AppAuth(staff = true)
    @PostMapping("/weekly/{reportId}/submit")
    public AjaxResult weeklySubmit(@PathVariable Long reportId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(manage.weeklySubmit(reportId, body));
    }

    // ---------------------------------------------------------------- 日报

    @AppAuth(staff = true)
    @GetMapping("/daily")
    public AjaxResult daily(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String date)
    {
        return AjaxResult.success(manage.daily(storeId, date));
    }

    @AppAuth(staff = true)
    @PostMapping("/daily/entries")
    public AjaxResult dailyAdd(@RequestBody Map<String, Object> body)
    {
        limiter.check("manage:daily", String.valueOf(AppUserContext.getUserId()), 120, 3600, "录入太频繁了，稍后再试");
        return AjaxResult.success(manage.dailyAdd(body));
    }

    @AppAuth(staff = true)
    @PostMapping("/daily/entries/{entryId}/void")
    public AjaxResult dailyVoid(@PathVariable Long entryId)
    {
        return AjaxResult.success(manage.dailyVoid(entryId));
    }

    @AppAuth(staff = true)
    @PostMapping("/daily/report/send")
    public AjaxResult dailySend(@RequestBody Map<String, Object> body)
    {
        limiter.check("manage:daily-send", String.valueOf(AppUserContext.getUserId()), 20, 3600, "发送太频繁了，稍后再试");
        return AjaxResult.success(manage.dailySend(KgJdbc.asLong(body.get("storeId")), s(body, "date")));
    }

    /** 导出当天报表（Excel）：小程序用 downloadFile + openDocument 打开 */
    @AppAuth(staff = true)
    @GetMapping("/daily/report.xlsx")
    public void dailyExport(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String date, HttpServletResponse resp) throws Exception
    {
        Map<String, Object> d = manage.daily(storeId, date);
        byte[] bytes = dailyWorkbook(d);
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(d.get("title") + "-" + d.get("date") + ".xlsx", "UTF-8"));
        resp.getOutputStream().write(bytes);
    }

    /** 旅居账和招募账共用：标题、数据来源、汇总项的名字都跟着服务端的 daily 结果走 */
    static byte[] dailyWorkbook(Map<String, Object> d) throws Exception
    {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            String title = String.valueOf(d.get("title"));
            Sheet sh = wb.createSheet(title);
            int r = 0;
            row(sh, r++, title);
            row(sh, r++, "门店名称", String.valueOf(d.get("storeName")));
            row(sh, r++, "报表日期", String.valueOf(d.get("dateText")));
            row(sh, r++, "数据来源", d.get("sourceText") == null ? "系统自动 + 手动录入" : String.valueOf(d.get("sourceText")));
            r++;
            if ("recruit".equals(d.get("ledger")))
            {
                row(sh, r++, "科目", "金额（元）");
                for (Object o : (List<?>) d.get("items"))
                {
                    Map<?, ?> x = (Map<?, ?>) o;
                    row(sh, r++, String.valueOf(x.get("name")), String.valueOf(x.get("amount")));
                }
                r++;
            }
            row(sh, r++, "项目", "金额（元）", "来源", "备注");
            for (Object o : (List<?>) d.get("rows"))
            {
                Map<?, ?> x = (Map<?, ?>) o;
                row(sh, r++, String.valueOf(x.get("name")), String.valueOf(x.get("amount")), Boolean.TRUE.equals(x.get("system")) ? "系统" : "手动",
                        x.get("note") == null ? "" : String.valueOf(x.get("note")));
            }
            r++;
            long[] t = (long[]) d.get("totals");
            row(sh, r++, "recruit".equals(d.get("ledger")) ? "总收入" : "总营收", com.ruoyi.kangaroo.ext.manage.KgStats.yuanText(t[0]));
            row(sh, r++, "总支出", com.ruoyi.kangaroo.ext.manage.KgStats.yuanText(t[1]));
            row(sh, r++, "收支结余", com.ruoyi.kangaroo.ext.manage.KgStats.yuanText(t[2]));
            r++;
            row(sh, r++, "备注：本报表由袋鼠全球青年系统生成，仅供内部经营参考（收支结余不是会计净利润）。");
            sh.setColumnWidth(0, 20 * 256);
            sh.setColumnWidth(1, 16 * 256);
            sh.setColumnWidth(3, 30 * 256);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void row(Sheet sh, int i, String... cells)
    {
        Row row = sh.createRow(i);
        for (int c = 0; c < cells.length; c++)
        {
            row.createCell(c).setCellValue(cells[c]);
        }
    }

    // ---------------------------------------------------------------- 看板

    @AppAuth(staff = true)
    @GetMapping("/dashboard/overview")
    public AjaxResult overview(@RequestParam(required = false) String period)
    {
        return AjaxResult.success(manage.overview(period));
    }

    @AppAuth(staff = true)
    @GetMapping("/dashboard/trend")
    public AjaxResult trend(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String city,
            @RequestParam(required = false) String period, @RequestParam(required = false) String end)
    {
        return AjaxResult.success(manage.trend(storeId, city, period, end));
    }

    @AppAuth(staff = true)
    @GetMapping("/dashboard/rank")
    public AjaxResult rank(@RequestParam(required = false) String metric, @RequestParam(required = false) String period)
    {
        return AjaxResult.success(manage.rank(metric, period));
    }

    @AppAuth(staff = true)
    @GetMapping("/dashboard/reviews")
    public AjaxResult reviews(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String period)
    {
        return AjaxResult.success(manage.reviews(storeId, period));
    }

    @AppAuth(staff = true)
    @SuppressWarnings("unchecked")
    @PostMapping("/dashboard/improvement-plan")
    public AjaxResult plan(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(tasks.planFromAdvice(KgJdbc.asLong(body.get("storeId")), (List<String>) body.get("advice")));
    }

    // ---------------------------------------------------------------- 任务

    @AppAuth
    @GetMapping("/tasks")
    public AjaxResult tasks(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String weekStart,
            @RequestParam(required = false) String tab)
    {
        return AjaxResult.success(tasks.list(storeId, weekStart, tab));
    }

    @AppAuth
    @GetMapping("/tasks/{taskId}")
    public AjaxResult task(@PathVariable Long taskId)
    {
        return AjaxResult.success(tasks.detail(taskId));
    }

    @AppAuth(staff = true)
    @GetMapping("/tasks/eligible-staff")
    public AjaxResult eligible(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(tasks.eligibleStaff(storeId));
    }

    @AppAuth(staff = true)
    @PostMapping("/tasks")
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        limiter.check("manage:task", String.valueOf(AppUserContext.getUserId()), 60, 3600, "发布太频繁了，稍后再试");
        return AjaxResult.success(tasks.save(KgJdbc.asLong(body.get("taskId")), body, Boolean.TRUE.equals(body.get("publish"))));
    }

    @AppAuth
    @PostMapping("/tasks/{taskId}/claim")
    public AjaxResult claim(@PathVariable Long taskId)
    {
        return AjaxResult.success(tasks.claim(taskId));
    }

    @AppAuth
    @PostMapping("/tasks/{taskId}/unclaim")
    public AjaxResult unclaim(@PathVariable Long taskId)
    {
        return AjaxResult.success(tasks.unclaim(taskId));
    }

    /** 被指定的员工说明无法参加（可附原因，会通知发布人） */
    @AppAuth
    @PostMapping("/tasks/{taskId}/decline")
    public AjaxResult decline(@PathVariable Long taskId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(tasks.decline(taskId, s(body, "reason")));
    }

    @AppAuth(staff = true)
    @PostMapping("/tasks/{taskId}/check")
    public AjaxResult check(@PathVariable Long taskId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(tasks.check(taskId, body));
    }

    @AppAuth(staff = true)
    @PostMapping("/tasks/{taskId}/cancel")
    public AjaxResult cancel(@PathVariable Long taskId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(tasks.cancel(taskId, s(body, "reason")));
    }
}
