package com.ruoyi.kangaroo.controller.app;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.staff.*;
import com.ruoyi.kangaroo.security.AppAuth;

@RestController
@RequestMapping("/app/staff/publication-fees")
@AppAuth(staff=true,merchant=true)
public class AppPublicationFeeController {
    @Autowired private KgPublicationFeeService fees;
    @Autowired private StaffSupport support;
    @GetMapping
    public AjaxResult list(@RequestParam(required=false) Long storeId,@RequestParam(defaultValue="1") int pageNum) {
        StaffSupport.Ctx c=support.ctx(storeId);
        if (!"merchant".equals(c.role)) throw new ServiceException("请使用商家身份查看发布费用",403);
        return AjaxResult.success(fees.list(c.storeId,pageNum));
    }
}
