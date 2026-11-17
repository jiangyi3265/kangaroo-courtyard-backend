package com.ruoyi.kangaroo.controller.admin;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.service.IKgMessageService;

/**
 * 站内消息（管理后台，只读）
 *
 * 消息由报名、面试、签约等业务流程产生，后台手工新增或改动会让消息内容与业务状态对不上，
 * 因此只开放 list / query，V2__menu_and_dict.sql 里也只给了 list 与 query 两个权限标识。
 */
@RestController
@RequestMapping("/kangaroo/message")
public class KgMessageController extends BaseController
{
    @Autowired
    private IKgMessageService kgMessageService;

    @PreAuthorize("@ss.hasPermi('kangaroo:message:list')")
    @GetMapping("/list")
    public TableDataInfo list(KgMessage kgMessage)
    {
        startPage();
        List<KgMessage> list = kgMessageService.selectKgMessageList(kgMessage);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:message:query')")
    @GetMapping(value = "/{msgId}")
    public AjaxResult getInfo(@PathVariable("msgId") Long msgId)
    {
        return success(kgMessageService.selectKgMessageByMsgId(msgId));
    }
}
