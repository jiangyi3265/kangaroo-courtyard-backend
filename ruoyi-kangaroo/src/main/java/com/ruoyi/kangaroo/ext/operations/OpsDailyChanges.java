package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit administrator corrections, preserving the original manual values and automatic snapshot. */
@Service
public class OpsDailyChanges {
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    @Autowired private OpsDaily daily;
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(long id,Map<String,Object> body,long operator,String name){
        Map<String,Object> row=daily.load(id,true);scope.requireAdminCity(number(row.get("cityId")),false);
        String request=text(body.get("requestId"));require(request.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),"修改请求编号无效");
        String payload=JSON.toJSONString(body);Map<String,Object> prior=db.one("select operator_id,request_json from kg_ops_daily_change where report_id=:id and request_id=:req for update",p("id",id,"req",request));
        if(prior!=null){if(number(prior.get("operatorId"))!=operator||!Objects.equals(prior.get("requestJson"),payload))throw new ServiceException("同一请求不能修改为其他内容，请重新编辑",409);return daily.adminGet(id);}
        if(!Objects.equals(KgJdbc.asLong(body.get("revision")),number(row.get("revision"))))throw new ServiceException("日报已更新，请重新打开后修改",409);
        String reason=line(body.get("reason"),"修改原因",300,true);require(body.get("manual") instanceof Map,"请填写修改内容");
        List<Map<String,Object>> progress=(List)JSON.parseArray(text(row.get("progressJson")),Map.class);boolean submitted="submitted".equals(row.get("status"));
        Map<String,Object> form=OpsForms.snapshot(row),manual=OpsDaily.validate((Map<String,Object>)body.get("manual"),text(row.get("roleCode")),progress,submitted,form);
        OpsDaily.validateExpenseDates(manual,row,submitted);Map<String,Object> auto=JSON.parseObject(text(row.get("autoJson")));String summary=OpsDaily.summary(auto,manual,progress,form);
        String before=text(row.get("manualJson")),after=JSON.toJSONString(manual);require(!Objects.equals(JSON.parseObject(before),manual),"内容没有变化，无需保存");
        long revision=number(row.get("revision"))+1;
        db.insert("kg_ops_daily_change",p("report_id",id,"city_id",row.get("cityId"),"request_id",request,"request_json",payload,"operator_id",operator,"operator_name",name,"reason",reason,"from_revision",row.get("revision"),"to_revision",revision,"before_json",before,"after_json",after,"form_json",JSON.toJSONString(form),"create_time",daily.now().format(DT)));
        db.updateRow("kg_ops_daily","report_id",id,p("manual_json",after,"summary_text",summary,"revision",revision,"update_time",daily.now().format(DT),"corrected_at",daily.now().format(DT),"corrected_by",name,"correction_reason",reason));
        return daily.adminGet(id);
    }
    public List<Map<String,Object>> history(long id){daily.adminGet(id);return rows(id);}
    public List<Map<String,Object>> forUser(long uid,long id){daily.get(uid,id);return rows(id);}
    private List<Map<String,Object>> rows(long id){return db.list("select change_id,operator_name,reason,from_revision,to_revision,before_json,after_json,form_json,create_time from kg_ops_daily_change where report_id=:id order by change_id desc limit 100",p("id",id));}
}
