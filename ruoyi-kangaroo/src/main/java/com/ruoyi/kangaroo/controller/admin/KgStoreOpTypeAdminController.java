package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.kangaroo.ext.core.KgStoreOpTypes;

/**
 * 门店来源类型（管理后台「门店管理」；2026-11-03 主人：来源类型加「合作」，见 KgStoreOpTypes）。用门店已有的编辑权限，不加新菜单：
 *  PUT /kangaroo/store/{storeId}/op-type  body {opType: direct | franchise | managed | partner} → {storeId, storeName, opType, changed}
 * 和 KgStoreController 的 PUT /kangaroo/store（通用编辑）分开：线上旧版门店服务不认 partner，这个接口只改 op_type 一列。
 */
@RestController
@RequestMapping("/kangaroo/store")
public class KgStoreOpTypeAdminController extends BaseController
{
    @Autowired
    private KgStoreOpTypes opTypes;

    @PreAuthorize("@ss.hasPermi('kangaroo:store:edit')")
    @Log(title = "门店来源类型", businessType = BusinessType.UPDATE)
    @PutMapping("/{storeId:[0-9]+}/op-type")
    public AjaxResult set(@PathVariable Long storeId, @RequestBody(required = false) Map<String, Object> body)
    {
        return success(opTypes.set(storeId, body == null ? null : body.get("opType"), getUsername()));
    }
}
