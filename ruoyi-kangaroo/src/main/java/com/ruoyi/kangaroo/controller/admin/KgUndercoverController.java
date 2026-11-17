package com.ruoyi.kangaroo.controller.admin;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.undercover.UndercoverService;
import com.ruoyi.kangaroo.ext.undercover.UndercoverWordService;

@RestController
@RequestMapping("/kangaroo/undercover")
public class KgUndercoverController
{
    @Autowired private UndercoverService games;
    @Autowired private UndercoverWordService words;

    @PreAuthorize("@ss.hasPermi('kangaroo:undercover:list')")
    @GetMapping("/words")
    public AjaxResult words(@RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="") String category,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize)
    {
        return AjaxResult.success(words.list(keyword, category, status, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:undercover:edit')")
    @Log(title="谁是卧底词库", businessType=BusinessType.INSERT)
    @PostMapping("/words")
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(words.save(null, body, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:undercover:edit')")
    @Log(title="谁是卧底词库", businessType=BusinessType.UPDATE)
    @PutMapping("/words/{id}")
    public AjaxResult update(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(words.save(id, body, SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:undercover:list')")
    @GetMapping("/rooms")
    public AjaxResult rooms(@RequestParam(defaultValue="false") boolean history, @RequestParam(defaultValue="") String code,
            @RequestParam(defaultValue="") String phase, @RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize)
    {
        return AjaxResult.success(games.adminList(history, code, phase, pageNum, pageSize));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:undercover:list')")
    @GetMapping("/rooms/{code}")
    public AjaxResult detail(@PathVariable String code, @RequestParam(required=false) String gameId)
    {
        return AjaxResult.success(games.adminDetail(code, gameId));
    }

    @PreAuthorize("@ss.hasPermi('kangaroo:undercover:close')")
    @Log(title="解散谁是卧底房间", businessType=BusinessType.UPDATE)
    @PostMapping("/rooms/{code}/close")
    public AjaxResult close(@PathVariable String code, @RequestBody Map<String, Object> body)
    {
        games.adminClose(code, body, SecurityUtils.getUsername());
        return AjaxResult.success();
    }
}
