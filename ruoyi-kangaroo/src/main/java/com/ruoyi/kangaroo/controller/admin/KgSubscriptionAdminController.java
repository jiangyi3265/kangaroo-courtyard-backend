package com.ruoyi.kangaroo.controller.admin;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.user.KgSubscribeScenes;

@RestController
@RequestMapping("/kangaroo/subscription")
public class KgSubscriptionAdminController {
 @Autowired private KgJdbc db;
 @Autowired private KangarooProperties properties;
 @PreAuthorize("@ss.hasPermi('kangaroo:substpl:list')")
 @GetMapping("/readiness")
 public AjaxResult readiness(){
  Map<String,Object> result=new LinkedHashMap<>();result.put("mode",properties.getSubscribeMode());result.put("version",properties.getSubscribeMiniProgramState());
  List<Map<String,Object>> rows=new ArrayList<>();
  for(Map.Entry<String,String> scene:KgSubscribeScenes.LABELS.entrySet()){
   Map<String,Object> row=new LinkedHashMap<>();row.put("key",scene.getKey());row.put("title",scene.getValue());
   row.put("configured",db.count("select count(*) from kg_subscribe_template where biz_key=:k and status='0' and template_id<>'' and fields<>''",KgJdbc.p("k",scene.getKey()))>0);rows.add(row);
  }
  result.put("scenes",rows);return AjaxResult.success(result);
 }
}
