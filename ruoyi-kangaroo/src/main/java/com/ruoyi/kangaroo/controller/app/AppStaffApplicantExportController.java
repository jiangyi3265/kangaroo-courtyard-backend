package com.ruoyi.kangaroo.controller.app;

import java.net.URLEncoder;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.staff.KgApplicantExportService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 到店名单导出（2026-11-03 主人：一键选时间、一键导出姓名、身份证号、手机号、去哪家、开始 / 结束时间）。
 * 只认「岗位管理·导出」的门店（换工主理人、城市负责人），范围和口径见 KgApplicantExportService。
 * 查询参数只有日期、门店编号、范围，不带任何个人信息；登录凭证走 X-App-Token 请求头（小程序 downloadFile 带 header）。
 */
@RestController
@RequestMapping("/app/staff/applicants")
@AppAuth(staff = true, cityLead = true)
public class AppStaffApplicantExportController
{
    @Autowired
    private KgApplicantExportService exports;

    /** 选好日期后先看一眼：多少人、几人没交证件、前 30 行（证件号、手机号脱敏） */
    @GetMapping("/export/preview")
    public AjaxResult preview(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String scope)
    {
        AppLoginUser me = AppUserContext.requireStaff();
        return AjaxResult.success(exports.preview(exports.query(me, storeId, from, to, scope)));
    }

    /** 导出 Excel（含完整证件号、手机号），每次导出按门店记一条员工操作日志 */
    @GetMapping("/export.xlsx")
    public void export(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String scope, HttpServletResponse resp) throws Exception
    {
        AppLoginUser me = AppUserContext.requireStaff();
        KgApplicantExportService.Query q = exports.query(me, storeId, from, to, scope);
        byte[] bytes = exports.export(me, q);
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode("到店名单-" + q.from + "至" + q.to + ".xlsx", "UTF-8"));
        resp.getOutputStream().write(bytes);
    }
}
