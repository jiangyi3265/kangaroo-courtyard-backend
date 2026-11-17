package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.manage.KgHonorService;
import com.ruoyi.kangaroo.ext.manage.KgTaskService;
import com.ruoyi.kangaroo.ext.manage.KgWalletService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/** 钱包（只看本人）、积分、荣誉与关怀、申报 */
@RestController
@RequestMapping("/app")
public class AppWalletController
{
    @Autowired
    private KgWalletService wallet;

    @Autowired
    private KgHonorService honor;

    @Autowired
    private KgTaskService tasks;

    @Autowired
    private AppRateLimiter limiter;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    @AppAuth
    @GetMapping("/wallet")
    public AjaxResult overview()
    {
        return AjaxResult.success(wallet.overview());
    }

    @AppAuth
    @GetMapping("/wallet/entries")
    public AjaxResult entries(@RequestParam(required = false) String type, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(wallet.entries(type, from, to, keyword));
    }

    @AppAuth
    @GetMapping("/wallet/rules")
    public AjaxResult rules()
    {
        return AjaxResult.success(wallet.rules());
    }

    @AppAuth
    @GetMapping("/wallet/withdraws")
    public AjaxResult withdraws()
    {
        return AjaxResult.success(wallet.withdraws());
    }

    @AppAuth
    @PostMapping("/wallet/withdraw")
    public AjaxResult withdraw(@RequestBody Map<String, Object> body)
    {
        limiter.check("wallet:withdraw", String.valueOf(AppUserContext.getUserId()), 10, 86400, "今天提交的结算申请太多了，明天再试");
        return AjaxResult.success(wallet.withdraw(body));
    }

    @AppAuth
    @GetMapping("/points")
    public AjaxResult points()
    {
        return AjaxResult.success(java.util.Collections.singletonMap("points", tasks.points(AppUserContext.getUserId())));
    }

    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @GetMapping("/honor")
    public AjaxResult wall()
    {
        return AjaxResult.success(honor.wall());
    }

    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @GetMapping("/honor/growth")
    public AjaxResult growth()
    {
        return AjaxResult.success(honor.growth());
    }

    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @GetMapping("/honor/birthday")
    public AjaxResult birthday()
    {
        return AjaxResult.success(honor.birthday());
    }

    @AppAuth
    @PutMapping("/honor/birthday")
    public AjaxResult setBirthday(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(honor.setBirthday(s(body, "birthday")));
    }

    @AppAuth(staff = true, cityLeadAfterSwitch = true)
    @PostMapping("/honor/birthday/claim")
    public AjaxResult claim(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(honor.claimGift(body));
    }

    @AppAuth
    @GetMapping("/appeal/options")
    public AjaxResult appealOptions()
    {
        return AjaxResult.success(honor.appealOptions());
    }

    @AppAuth
    @PostMapping("/appeal")
    public AjaxResult appeal(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(honor.appeal(body));
    }
}
