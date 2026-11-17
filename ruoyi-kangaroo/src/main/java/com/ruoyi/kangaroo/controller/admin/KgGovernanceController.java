package com.ruoyi.kangaroo.controller.admin;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.governance.*;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

@RestController @RequestMapping("/kangaroo/governance")
public class KgGovernanceController extends BaseController {
    @Autowired private KgBadgeService badges;
    @Autowired private KgBadgeModeration badgeModeration;
    @Autowired private KgMediationService mediation;
    @Autowired private KgMediationFiles files;
    @Autowired private KgJdbc db;
    @Autowired private KgMerchantSanctions merchantSanctions;
    @Autowired private KgMerchantCaseEffects merchantEffects;
    @Autowired private KgMerchantSettlement settlements;
    @Autowired private KgGovernanceDelivery delivery;
    @GetMapping("/merchant-reviews") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')") public AjaxResult merchantReviews(@RequestParam Long storeId){return success(merchantEffects.reviews(storeId));}
    @GetMapping("/settlements") @PreAuthorize("@ss.hasPermi('kangaroo:merchantfinance:query')") public AjaxResult settlements(@RequestParam(defaultValue="1")int pageNum){return success(settlements.list(pageNum));}
    @GetMapping("/settlement-stores") @PreAuthorize("@ss.hasPermi('kangaroo:merchantfinance:query')") public AjaxResult settlementStores(){return success(merchantSanctions.stores());}
    @GetMapping("/settlements/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:merchantfinance:query')") public AjaxResult settlement(@PathVariable Long id){return success(settlements.detail(id));}
    @PostMapping("/settlements") @PreAuthorize("@ss.hasPermi('kangaroo:merchantfinance:edit')") public AjaxResult saveSettlement(@RequestBody Map<String,Object>b){return success(settlements.save(b,actor()));}
    @PostMapping("/settlements/{id}/{action}") @PreAuthorize("@ss.hasPermi('kangaroo:merchantfinance:edit')") public AjaxResult settlementCommand(@PathVariable Long id,@PathVariable String action,@RequestBody Map<String,Object>b){settlements.command(id,action,b,actor());return success();}
    @GetMapping("/deliveries") @PreAuthorize("@ss.hasPermi('kangaroo:delivery:query')") public AjaxResult deliveries(@RequestParam(defaultValue="sms")String channel,@RequestParam(defaultValue="1")int pageNum){return success(delivery.overview(channel,pageNum));}
    @GetMapping("/merchant-cases") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')") public AjaxResult merchantCases(@RequestParam(defaultValue="1")int pageNum){return success(merchantSanctions.list(0L,pageNum,true));}
    @GetMapping("/merchant-stores") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')") public AjaxResult merchantStores(){return success(merchantSanctions.stores());}
    @GetMapping("/merchant-cases/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')") public AjaxResult merchantCase(@PathVariable Long id){return success(merchantSanctions.detail(0L,id,true));}
    @PostMapping("/merchant-cases") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:edit')") public AjaxResult merchantNotice(@RequestBody Map<String,Object>b){return success(merchantSanctions.create(b,actor()));}
    @PostMapping("/merchant-cases/{id}/decision") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:edit')") public AjaxResult merchantDecision(@PathVariable Long id,@RequestBody Map<String,Object>b){merchantSanctions.decide(id,b,actor());return success();}
    private String actor(){return "admin:"+getUserId();}
    @GetMapping @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')")
    public AjaxResult list(@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="1")int pageNum){return success(p("cases",mediation.list(0L,"",status,pageNum,true),"stats",mediation.stats()));}
    @GetMapping("/cases/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')") public AjaxResult detail(@PathVariable Long id){return success(mediation.detail(getUserId(),id,true));}
    @PostMapping("/cases/{id}/{action}") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:edit')") public AjaxResult command(@PathVariable Long id,@PathVariable String action,@RequestBody Map<String,Object>b){mediation.command(getUserId(),id,action,b,true,actor());return success();}
    @GetMapping("/files/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')") public AjaxResult file(@PathVariable Long id){return success(files.link(getUserId(),id,true));}
    @GetMapping("/badges") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:query')") public AjaxResult badges(@RequestParam(defaultValue="1")int pageNum){return success(badges.admin(pageNum));}
    @PostMapping("/badges") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')") public AjaxResult grant(@RequestBody Map<String,Object>b){return success(badges.grant(b,actor()));}
    @PostMapping("/badges/{id}/revoke") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')") public AjaxResult revoke(@PathVariable Long id,@RequestBody Map<String,Object>b){badges.revoke(id,Governance.text(b,"note",5,500),actor());return success();}
    @PostMapping("/badges/{id}/city") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')") public AjaxResult badgeCity(@PathVariable Long id,@RequestBody Map<String,Object>b){badges.updateBuilderCity(id,b,actor());return success();}
    @PostMapping("/badge-config/{code}") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')") public AjaxResult config(@PathVariable String code,@RequestBody Map<String,Object>b){badges.configure(code,b,actor());return success();}
    @GetMapping("/badge-moderation")
    @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:query')")
    public AjaxResult badgeModeration(@RequestParam(required=false) Long userId,
            @RequestParam(defaultValue="1") int pageNum) {
        return success(badgeModeration.overview(userId, pageNum));
    }
    @PostMapping("/badge-content")
    @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult assessBadgeContent(@RequestBody Map<String, Object> body) {
        return success(badgeModeration.assessContent(body, actor()));
    }
    @PostMapping("/badge-violations")
    @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')")
    public AjaxResult recordBadgeViolation(@RequestBody Map<String, Object> body) {
        return success(badgeModeration.recordViolation(body, actor()));
    }
    @PostMapping("/feedback/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:memberlevel:edit')") public AjaxResult feedback(@PathVariable Long id,@RequestBody Map<String,Object>b){badges.assessFeedback(id,b,actor());return success();}
    @GetMapping("/jurors")
    @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')")
    public AjaxResult jurors(@RequestParam(defaultValue="1") int pageNum) {
        Map<String, Object> result = db.page("select j.*,u.nickname from kg_mediation_juror j join kg_app_user u on u.user_id=j.app_user_id order by j.app_user_id", p(), pageNum, 30);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        for (Map<String, Object> row : rows) {
            Map<String, Object> qualification = mediation.juror(KgJdbc.asLong(row.get("appUserId")));
            row.put("creditScore", qualification.get("creditScore"));
            row.put("coreEligible", qualification.get("coreEligible"));
            row.put("coreMember", qualification.get("coreMember"));
        }
        return success(result);
    }
    @PostMapping("/jurors/{id}/sanction") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:edit')") public AjaxResult sanction(@PathVariable Long id,@RequestBody Map<String,Object>b){mediation.sanctionJuror(id,b,actor());return success();}
    @GetMapping("/jurors/{id}/sanctions")
    @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')")
    public AjaxResult jurorSanctions(@PathVariable Long id) {
        return success(mediation.jurorSanctions(id));
    }
    @PostMapping("/jurors/{id}/restore")
    @PreAuthorize("@ss.hasPermi('kangaroo:mediation:edit')")
    public AjaxResult restoreJuror(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        mediation.restoreJuror(id, body, actor());
        return success();
    }
    @PostMapping("/votes/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:edit')") public AjaxResult assess(@PathVariable Long id,@RequestBody Map<String,Object>b){mediation.assess(id,b,actor());return success();}
    @GetMapping("/export/{id}") @PreAuthorize("@ss.hasPermi('kangaroo:mediation:query')") public AjaxResult export(@PathVariable Long id){return success(mediation.detail(getUserId(),id,true));}
}
