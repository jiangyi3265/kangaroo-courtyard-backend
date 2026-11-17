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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.user.KgAccountService;
import com.ruoyi.kangaroo.ext.user.KgSmsService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgAppAuthService;

/**
 * 「我的」页里与账号本身相关的接口：资料、授权、设备、实名、积分、数据导出、注销。
 */
@RestController
@RequestMapping("/app/me")
public class AppMeController
{
    @Autowired
    private KgAccountService account;

    @Autowired
    private KgSmsService sms;

    @Autowired
    private IKgAppAuthService appAuthService;

    /** AjaxResult.success(String) 只会写进 msg、data 为空，前端拿不到内容；统一包成 {message} 走 data */
    private static Map<String, Object> msg(String text)
    {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("message", text);
        return m;
    }

    // ---------------------------------------------------------------- 资料

    @AppAuth
    @GetMapping("/profile")
    public AjaxResult profile()
    {
        return AjaxResult.success(account.profile());
    }

    @AppAuth
    @PostMapping("/profile")
    public AjaxResult saveProfile(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(account.saveProfile(body));
    }

    @AppAuth
    @GetMapping("/points")
    public AjaxResult points(@RequestParam(required = false, defaultValue = "1") Integer page)
    {
        return AjaxResult.success(account.points(page == null ? 1 : page.intValue()));
    }

    // ---------------------------------------------------------------- 黑名单

    @AppAuth
    @GetMapping("/blocks")
    public AjaxResult blocks()
    {
        return AjaxResult.success(account.blocks());
    }

    @AppAuth
    @PostMapping("/blocks/{uid}/remove")
    public AjaxResult unblock(@PathVariable Long uid)
    {
        return AjaxResult.success(msg(account.unblock(uid)));
    }

    // ---------------------------------------------------------------- 授权与设备

    @AppAuth
    @GetMapping("/authorizations")
    public AjaxResult authorizations()
    {
        return AjaxResult.success(account.authorizations());
    }

    /** 登录页勾选了「我已阅读并同意《用户协议》《隐私政策》」、登录成功后记一笔同意 */
    @AppAuth
    @PostMapping("/consent/legal")
    public AjaxResult agreeLegal(@RequestBody(required = false) Map<String, String> versions)
    {
        return AjaxResult.success(msg(account.agreeLegal(versions)));
    }

    @AppAuth
    @PostMapping("/consent/withdraw")
    public AjaxResult withdrawConsent(@RequestBody Map<String, String> body)
    {
        return AjaxResult.success(msg(account.withdrawConsent(body == null ? null : body.get("doc"))));
    }

    @AppAuth
    @GetMapping("/sessions")
    public AjaxResult sessions()
    {
        return AjaxResult.success(account.sessions());
    }

    @AppAuth
    @PostMapping("/sessions/revoke-others")
    public AjaxResult revokeOthers()
    {
        return AjaxResult.success(msg(account.revokeOtherSessions()));
    }

    @AppAuth
    @PostMapping("/sessions/revoke")
    public AjaxResult revokeOne(@RequestBody Map<String, String> body)
    {
        return AjaxResult.success(msg(account.revokeSession(body == null ? null : body.get("id"))));
    }

    // ---------------------------------------------------------------- 手机号与实名

    @AppAuth
    @PostMapping("/sms/send")
    public AjaxResult smsSend(@RequestBody Map<String, String> body)
    {
        String scene = body == null || body.get("scene") == null ? "bind" : body.get("scene");
        // 不传号码时发到账号已绑定的手机号（结算验证码就走这条路），页面不需要也拿不到完整号码
        String phone = body == null ? null : body.get("phone");
        if (phone == null || phone.trim().isEmpty())
        {
            phone = account.boundPhone(AppUserContext.getUserId());
        }
        return AjaxResult.success(sms.send(phone, scene, AppUserContext.getUserId()));
    }

    /** 短信验证码绑定/换绑手机号（微信一键授权走 /app/auth/phone-verify） */
    @AppAuth
    @PostMapping("/phone/bind")
    public AjaxResult bindPhone(@RequestBody Map<String, String> body)
    {
        String phone = body == null ? null : body.get("phone");
        String scene = body == null || body.get("scene") == null ? "bind" : body.get("scene");
        sms.verify(phone, scene, body == null ? null : body.get("code"));
        appAuthService.bindVerifiedPhone(AppUserContext.getUserId(), phone);
        return AjaxResult.success(appAuthService.currentAccount());
    }

    @AppAuth
    @PostMapping("/real-name")
    public AjaxResult realName(@RequestBody Map<String, String> body)
    {
        return AjaxResult.success(account.realNameVerify(body == null ? null : body.get("name"), body == null ? null : body.get("idNo")));
    }

    // ---------------------------------------------------------------- 注销

    @AppAuth
    @GetMapping("/deactivation")
    public AjaxResult deactivationCheck()
    {
        return AjaxResult.success(account.deactivationCheck());
    }

    @AppAuth
    @PostMapping("/deactivate")
    public AjaxResult deactivate(@RequestBody Map<String, String> body)
    {
        return AjaxResult.success(account.deactivate(body == null ? null : body.get("confirm"), body == null ? null : body.get("reason")));
    }

    // ---------------------------------------------------------------- 数据导出

    /**
     * 个人数据副本：一张表一类数据，含账号、简历、订单、评价、积分、钱包流水、授权记录。
     * 手机号在导出件里仍按脱敏展示 —— 导出文件会离开设备，没必要把完整号码写进去。
     */
    @AppAuth
    @GetMapping("/export.xlsx")
    public void export(HttpServletResponse resp) throws Exception
    {
        Long uid = AppUserContext.getUserId();
        Map<String, List<List<String>>> sheets = account.exportSheets();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            for (Map.Entry<String, List<List<String>>> e : sheets.entrySet())
            {
                Sheet sh = wb.createSheet(e.getKey());
                int r = 0;
                for (List<String> line : e.getValue())
                {
                    Row row = sh.createRow(r++);
                    for (int c = 0; c < line.size(); c++)
                    {
                        row.createCell(c).setCellValue(line.get(c));
                    }
                }
                for (int c = 0; c < 8; c++)
                {
                    sh.setColumnWidth(c, 18 * 256);
                }
            }
            wb.write(out);
            account.logExport(uid);
            resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode("袋鼠全球青年-我的数据-" + uid + ".xlsx", "UTF-8"));
            resp.getOutputStream().write(out.toByteArray());
        }
    }
}
