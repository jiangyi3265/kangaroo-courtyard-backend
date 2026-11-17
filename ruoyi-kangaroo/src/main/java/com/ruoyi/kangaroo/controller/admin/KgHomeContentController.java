package com.ruoyi.kangaroo.controller.admin;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgMemberService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/kangaroo/homecontent")
public class KgHomeContentController {
    private final KgJdbc db;
    private final KgMemberService members;
    public KgHomeContentController(KgJdbc db, KgMemberService members){this.db=db;this.members=members;}
    @PreAuthorize("@ss.hasAnyPermi('kangaroo:memberbenefit:list,kangaroo:memberbenefit:add,kangaroo:memberbenefit:edit,kangaroo:memberlevel:list')")
    @GetMapping("/member-levels")
    public AjaxResult levels() { return AjaxResult.success(members.levels()); }
    @PreAuthorize("@ss.hasAnyPermi('kangaroo:homepick:list,kangaroo:homepick:add,kangaroo:homepick:edit')")
    @GetMapping("/options")
    public AjaxResult options(@RequestParam String section,@RequestParam(defaultValue="") String keyword) {
        String q="%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        // 「全职培训横幅」（training，2026-10-18）选的也是活动
        String sql=("activity".equals(section)||"training".equals(section))?"select activity_id id,title,city,status from kg_activity where del_flag='0' and status='open' and start_time>now() and title like :q escape '!' order by start_time limit 100":"select j.job_id id,j.title,j.city,j.status from kg_job j join kg_store s on s.store_id=j.store_id where j.del_flag='0' and j.status='published' and s.del_flag='0' and s.status='0' and j.title like :q escape '!' order by j.job_id desc limit 100";
        return AjaxResult.success(db.list(sql,KgJdbc.p("q",q)));
    }
}
