package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.time.LocalDateTime;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Role-wide form definitions; each daily report retains the definition used at creation. */
@Service
public class OpsForms {
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    public static Map<String,Object> defaults(String role){
        role(role);List<Map<String,Object>> fields=new ArrayList<>();
        fields.add(field("tomorrowPlan","manager".equals(role)?"明日规划":"明日工作重点",true,500));
        if("manager".equals(role)){
            fields.add(field("agreedShops","约定店铺及事项",true,1000));fields.add(field("issues","今日问题",true,1000));
        }else{
            fields.add(0,field("facilities","设施故障",true,1000));fields.add(1,field("shortages","物料缺货",true,1000));fields.add(2,field("yesterdayIssues","昨日问题／特殊情况",true,1000));
            fields.add(field("activityPlan","未来两天活动计划",true,1000));fields.add(field("activityNote","活动备注",false,1000));
        }
        return map("roleCode",role,"revision",0L,"fields",fields);
    }
    private static Map<String,Object> field(String key,String label,boolean required,int max){return map("key",key,"label",label,"type","textarea","required",required,"enabled",true,"maxLength",max,"placeholder","");}
    public Map<String,Object> current(String role){
        role(role);Map<String,Object> row=db.one("select * from kg_ops_form where role_code=:r",p("r",role));
        require(row!=null,"日报表单尚未初始化，请联系平台");Map<String,Object> form=text(row.get("formJson")).isEmpty()?defaults(role):JSON.parseObject(text(row.get("formJson")));
        form.put("revision",number(row.get("revision")));return form;
    }
    public static Map<String,Object> snapshot(Map<String,Object> row){return text(row.get("formJson")).isEmpty()?defaults(text(row.get("roleCode"))):JSON.parseObject(text(row.get("formJson")));}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> fields(Map<String,Object> form){return (List<Map<String,Object>>)form.get("fields");}
    public static String validateValue(Object raw,Map<String,Object> field,boolean submit){
        String label=text(field.get("label"));boolean required=submit&&Boolean.TRUE.equals(field.get("enabled"))&&Boolean.TRUE.equals(field.get("required"));
        String value=line(raw,label,(int)number(field.get("maxLength")),required);
        if("number".equals(field.get("type"))&&!value.isEmpty())value=decimal(value,label).stripTrailingZeros().toPlainString();return value;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(String role,Map<String,Object> body,long operator,String name){
        role(role);scope.requireAdminCity(0,true);
        Map<String,Object> row=db.must("select * from kg_ops_form where role_code=:r for update",p("r",role),"表单不存在");
        if(!Objects.equals(KgJdbc.asLong(body.get("revision")),number(row.get("revision"))))throw new ServiceException("表单已被修改，请刷新后再编辑",409);
        String reason=line(body.get("reason"),"表单调整说明",300,true);Object raw=body.get("fields");require(raw instanceof List,"请配置填写项目");
        require(((List<?>)raw).size()<=30,"每种身份最多配置30项");Set<String> keys=new HashSet<>(),labels=new HashSet<>(),builtins=new LinkedHashSet<>();
        for(Map<String,Object> f:fields(defaults(role)))builtins.add(text(f.get("key")));
        List<Map<String,Object>> checked=new ArrayList<>();int enabled=0;
        for(Object value:(List<?>)raw){require(value instanceof Map,"填写项目格式无效");Map<?,?> f=(Map<?,?>)value;String key=text(f.get("key")),type=text(f.get("type")),label=line(f.get("label"),"项目名称",40,true);
            require(builtins.contains(key)||key.matches("custom_[a-z0-9]{8,32}"),"项目标识无效");require(keys.add(key),"项目标识不能重复");require(labels.add(label),"项目名称不能重复");
            require(Arrays.asList("text","textarea","number").contains(type),"不支持的填写类型");require(!builtins.contains(key)||!"number".equals(type),"原有文字项目不能改为数字");
            require(f.get("enabled") instanceof Boolean&&f.get("required") instanceof Boolean,"请设置显示和必填选项");long max=number(f.get("maxLength"));require(max>=1&&max<=2000,"字数上限应为1至2000");
            if(Boolean.TRUE.equals(f.get("enabled")))enabled++;
            checked.add(map("key",key,"label",label,"type",type,"enabled",f.get("enabled"),"required",f.get("required"),"maxLength",max,"placeholder",line(f.get("placeholder"),"填写提示",150,false)));
        }
        require(keys.containsAll(builtins),"原有项目请关闭显示，不能删除其标识");require(enabled>0,"至少保留一个显示的填写项目");
        long rev=number(row.get("revision"))+1;Map<String,Object> form=map("roleCode",role,"revision",rev,"fields",checked);String json=JSON.toJSONString(form);
        Map<String,Object> before=current(role);
        db.insert("kg_ops_form_change",p("role_code",role,"revision",rev,"operator_id",operator,"operator_name",name,"reason",reason,"before_json",JSON.toJSONString(before),"after_json",json,"create_time",LocalDateTime.now(ZONE).format(DT)));
        db.update("update kg_ops_form set form_json=:json,revision=:rev,update_by=:name,update_time=now() where role_code=:role",p("json",json,"rev",rev,"name",name,"role",role));return form;
    }
    public List<Map<String,Object>> history(String role){role(role);return db.list("select change_id,role_code,revision,operator_name,reason,before_json,after_json,create_time from kg_ops_form_change where role_code=:r order by change_id desc limit 100",p("r",role));}
}
