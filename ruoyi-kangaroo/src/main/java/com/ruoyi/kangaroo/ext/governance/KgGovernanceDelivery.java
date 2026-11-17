package com.ruoyi.kangaroo.ext.governance;

import java.util.*;
import com.alibaba.fastjson2.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.config.KangarooProperties;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;

@Service public class KgGovernanceDelivery {
    @Autowired private KgJdbc db;
    @Autowired private TencentGovernanceSms sms;
    @Autowired private KangarooProperties properties;
    @Autowired @Lazy private KgGovernanceDelivery self;
    public static boolean eligible(String biz){return Arrays.asList("mediation","mediation_jury","member_case","member_case_result","merchant_case").contains(biz);}
    @Transactional(rollbackFor=Exception.class) public void enqueue(Long uid,Long msg,String biz){
        if(!eligible(biz)||uid==null||msg==null)return;
        db.update("insert ignore into kg_governance_sms(msg_id,app_user_id,biz_key,status,detail) values(:m,:u,:b,:st,:d)",p("m",msg,"u",uid,"b",biz,"st",sms.ready()?"queued":"blocked","d",sms.ready()?null:"短信通道未配置；站内消息已保存，配置后不会追发旧通知"));
    }
    public Map<String,Object> consent(Long uid){return p("enabled",db.count("select count(*) from kg_notify_pref where app_user_id=:u and pref_key='sms_governance' and enabled='1'",p("u",uid))>0,"available",sms.ready());}
    @Transactional public void consent(Long uid,boolean on){
        require(!on||db.count("select count(*) from kg_app_user where user_id=:u and del_flag='0' and phone_verified_time is not null and phone regexp '^1[3-9][0-9]{9}$'",p("u",uid))==1,"请先绑定并验证手机号码");
        db.update("insert into kg_notify_pref(app_user_id,pref_key,enabled,update_time) values(:u,'sms_governance',:e,now()) on duplicate key update enabled=:e,update_time=now()",p("u",uid,"e",on?"1":"0"));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW) public Map<String,Object> claim(long id){
        Map<String,Object> r=db.one("select * from kg_governance_sms where sms_id=:id for update",p("id",id));if(r==null||!"queued".equals(r.get("status")))return null;
        if(!sms.ready()){finish(id,"blocked","短信通道未配置",null,null);return null;}
        Map<String,Object> u=db.one("select phone,phone_verified_time from kg_app_user where user_id=:u and del_flag='0'",p("u",r.get("appUserId")));
        if(u==null||u.get("phoneVerifiedTime")==null||!str(u.get("phone")).matches("1[3-9][0-9]{9}")||!Boolean.TRUE.equals(consent(id(r.get("appUserId"))).get("enabled"))){finish(id,"skipped","未验证手机号或未同意接收短信提醒",null,null);return null;}
        String phone="+86"+u.get("phone");r.put("phone",phone);
        db.update("update kg_governance_sms set status='sending',phone=:p,template_id=:t,sdk_app_id=:a,sent_at=now(),update_time=now() where sms_id=:id",p("p",phone,"t",sms.template(),"a",sms.appId(),"id",id));return r;
    }
    public void dispatch(long id){Map<String,Object> r=self.claim(id);if(r==null)return;
        try{JSONObject response=sms.send(str(r.get("phone")),"流程处理通知","KG"+r.get("msgId"));JSONObject error=response.getJSONObject("Error");
            if(error!=null){self.finish(id,"failed",safeCode(error.getString("Code")),null,null);return;}
            JSONArray list=response.getJSONArray("SendStatusSet");if(list==null||list.size()!=1){self.finish(id,"unknown","缺少唯一发送结果，未自动重发",null,null);return;}
            JSONObject item=list.getJSONObject(0);String code=item.getString("Code"),serial=item.getString("SerialNo");
            if("Ok".equals(code)&&serial!=null&&!serial.isEmpty())self.finish(id,"accepted","服务商已受理，等待运营商送达回执",serial,null);
            else self.finish(id,"Ok".equals(code)?"unknown":"failed",safeCode(code),null,null);
        }catch(Exception e){self.finish(id,"unknown","调用中断，发送结果待核对，未自动重发",null,null);}
    }
    static String safeCode(String s){return s!=null&&s.matches("[A-Za-z0-9_.]{1,120}")?s:"服务商响应待核对";}
    @Transactional(propagation=Propagation.REQUIRES_NEW) public void finish(long id,String status,String detail,String serial,Date received){
        db.update("update kg_governance_sms set status=:s,detail=:d,serial_no=coalesce(:n,serial_no),received_at=coalesce(:r,received_at),update_time=now() where sms_id=:id and status in ('queued','sending','accepted')",p("s",status,"d",detail,"n",serial,"r",received,"id",id));
    }
    public void reconcile(Map<String,Object> row){
        if(!sms.ready())return;
        try{long start=time(row.get("sentAt")).atZone(ZONE).toEpochSecond();JSONObject r=sms.receipt(str(row.get("phone")),str(row.get("sdkAppId")),start);JSONArray a=r.getJSONArray("PullSmsSendStatusSet");if(a==null)return;
            for(int i=0;i<a.size();i++){JSONObject receipt=a.getJSONObject(i);if(!Objects.equals(str(row.get("serialNo")),receipt.getString("SerialNo"))||!Objects.equals(str(row.get("phone")),receipt.getString("PhoneNumber")))continue;
                String st=receipt.getString("ReportStatus");if(!Arrays.asList("SUCCESS","FAIL").contains(st))continue;
                Long t=receipt.getLong("UserReceiveTime");self.finish(id(row.get("smsId")),"SUCCESS".equals(st)?"delivered":"failed","SUCCESS".equals(st)?"运营商已确认送达":safeCode(receipt.getString("Description")),null,t!=null&&t>0?new Date(t*1000):null);break;}
        }catch(Exception e){/* Keep accepted: no receipt is not proof of failure. */}
    }
    @Scheduled(fixedDelay=30000,initialDelay=30000) public void tick(){
        db.update("update kg_governance_sms set status='unknown',detail='发送任务中断，结果待核对；未自动重发',update_time=now() where status='sending' and update_time<date_sub(now(),interval 5 minute)",p());
        for(Map<String,Object> r:db.list("select sms_id from kg_governance_sms where status='queued' order by sms_id limit 20",p()))self.dispatch(id(r.get("smsId")));
        for(Map<String,Object> r:db.list("select * from kg_governance_sms where status='accepted' and sent_at>=date_sub(now(),interval 7 day) order by update_time limit 20",p())){self.reconcile(r);db.update("update kg_governance_sms set update_time=now() where sms_id=:id",p("id",r.get("smsId")));}
    }
    public Map<String,Object> overview(String channel,int page){
        int n=Math.max(1,Math.min(10000,page));Map<String,Object> list;
        if("sms".equals(channel))list=db.page("select sms_id,msg_id,app_user_id,biz_key,status,detail,serial_no,sent_at,received_at,create_time from kg_governance_sms order by sms_id desc",p(),n,20);
        else list=db.page("select outbox_id,msg_id,app_user_id,biz_key,channel,status,detail,create_time,update_time from kg_subscribe_outbox order by outbox_id desc",p(),n,20);
        return p("records",list,"sms",sms.readiness(),"wechatMode",properties.getSubscribeMode(),"templates",db.list("select biz_key,template_id,status from kg_subscribe_template order by biz_key",p()));
    }
}
