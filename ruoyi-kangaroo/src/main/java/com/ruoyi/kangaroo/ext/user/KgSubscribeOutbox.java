package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.user.KgSubscribeService.str;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.WxMiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 提交前只入队；领取和完成各自提交，网络调用不占业务数据库事务。 */
@Service
public class KgSubscribeOutbox
{
    private static final Logger log = LoggerFactory.getLogger(KgSubscribeOutbox.class);
    @Autowired private KgJdbc db;
    @Autowired private KangarooProperties properties;
    @Autowired private WxMiniAppClient wx;
    @Autowired @Lazy private KgSubscribeOutbox self;
    @Autowired(required=false) private KgSubscribeVisibility visibility;

    @Transactional(rollbackFor = Exception.class)
    public void enqueue(Long userId, String bizKey, String title, String content, String route, Long msgId)
    {
        // Some business events create both personal and staff inbox copies for one account.
        // Keep both inbox entries, but consume just one WeChat authorization for the pair.
        if (msgId != null && db.count("select count(*) from kg_subscribe_outbox o join kg_message earlier on earlier.msg_id=o.msg_id "
                + "join kg_message current_msg on current_msg.msg_id=:id where o.app_user_id=:u and o.biz_key=:k "
                + "and earlier.mode<>current_msg.mode and earlier.biz_type=current_msg.biz_type and earlier.biz_id<=>current_msg.biz_id "
                + "and earlier.title=current_msg.title and earlier.content=current_msg.content "
                + "and o.create_time>=date_sub(now(),interval 10 second) and o.status in ('queued','sending','sent','unknown')",
                p("id",msgId,"u",userId,"k",bizKey))>0) return;
        enqueueEvent(userId,bizKey,title,content,route,msgId,"message:"+msgId);
    }

    @Transactional(rollbackFor=Exception.class)
    public void enqueueEvent(Long userId,String bizKey,String title,String content,String route,Long msgId,String eventKey) {
        if (userId == null || bizKey == null || msgId == null || "none".equals(properties.getSubscribeMode())) return;
        if (db.count("select count(*) from kg_subscribe_outbox where event_key=:key", p("key", eventKey)) > 0) return;
        // A single conversation reminder waits for the reader; further unread messages don't create a burst.
        if(eventKey.startsWith("chat:")&&db.count("select count(*) from kg_subscribe_outbox where msg_id=:id and (status in ('queued','sending') or (status in ('sent','unknown') and create_time>date_sub(now(),interval 10 minute)))",p("id",msgId))>0)return;
        Map<String, Object> row = p("msg_id", msgId, "app_user_id", userId, "biz_key", bizKey, "channel", properties.getSubscribeMode(),
                "event_key",eventKey,"payload", JSON.toJSONString(p("title", title, "content", content, "route", route,"scene",KgSubscribeScenes.label(bizKey),"now",KgSubscribeService.eventTime(),"platform","袋鼠全球青年")), "status", "queued", "create_time", new Date(), "update_time", new Date());
        db.insert("kg_subscribe_outbox", row);
        // The scheduled worker can only see committed rows; WeChat latency never holds up the business action.
    }

    private void dispatchSafely(long id)
    {
        try { self.dispatch(id); }
        catch (RuntimeException e) { log.warn("订阅消息任务未完成 id={}，由持久化状态继续处理", id, e); }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatch(long id)
    {
        Map<String, Object> work = self.claim(id);
        if (work == null) return;
        if ("mock".equals(work.get("channel")))
        {
            self.finish(id, "sent", "本地模拟通道：未真正外发", false);
            return;
        }
        WxMiniAppClient.SubscribeDelivery result;
        try
        {
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) work.get("data");
            result = wx.deliverSubscribeMessage(str(work.get("openid")), str(work.get("templateId")), str(work.get("page")), data);
        }
        catch (RuntimeException e)
        {
            // 不能确定微信是否已收到，不能退回额度并自动重发。
            self.finish(id, "unknown", "调用中断，发送结果待核对，未自动重发", false);
            return;
        }
        if (result == null) { self.finish(id,"unknown","缺少投递结果",false); return; }
        switch(result.state)
        {
            case SENT: self.finish(id,"sent","",false); break;
            case REJECTED: self.finish(id,"failed",result.detail,!"微信返回 errcode=43101".equals(result.detail)); if("微信返回 errcode=43101".equals(result.detail))self.clearRejectedGrant(id); break;
            case NOT_ATTEMPTED: self.retryUnattempted(id,result.detail); break;
            default: self.finish(id,"unknown",result.detail,false);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Map<String, Object> claim(long id)
    {
        Map<String, Object> row = db.one("select * from kg_subscribe_outbox where outbox_id=:id for update", p("id", id));
        if (row == null || !"queued".equals(row.get("status"))) return null;
        if (db.count("select count(*) from kg_subscribe_outbox where outbox_id=:id and (next_attempt_time is null or next_attempt_time<=now())",p("id",id))==0) return null;
        if (!str(row.get("channel")).equals(properties.getSubscribeMode()) || "none".equals(properties.getSubscribeMode()))
        {
            done(row, "skipped", "发送通道已变更，不转用其他通道外发", false); return null;
        }
        Map<String, Object> tpl = db.one("select * from kg_subscribe_template where biz_key=:k and status='0'", p("k", row.get("bizKey")));
        if (tpl == null) { done(row, "skipped", "订阅模板未启用", false); return null; }
        row.put("templateId", tpl.get("templateId"));
        Map<String, Object> user = db.one("select openid from kg_app_user where user_id=:u and del_flag='0' and status='0'", p("u", row.get("appUserId")));
        if (user == null || str(user.get("openid")).isEmpty() || str(user.get("openid")).startsWith("deleted:"))
        { done(row, "skipped", "没有可用的接收账号", false); return null; }
        Map<String, Object> payload = JSON.parseObject(str(row.get("payload")), Map.class);
        Map<String,Object> message=db.one("select read_flag,del_flag,mode,biz_type,biz_id from kg_message where msg_id=:id and recipient_id=:u",p("id",row.get("msgId"),"u",row.get("appUserId")));
        if(message==null||!"0".equals(str(message.get("delFlag")))){done(row,"skipped","原消息已不可用",false);return null;}
        String selectedScope=visibility==null?"user":visibility.authorizedScope(KgJdbc.asLong(row.get("appUserId")),KgJdbc.asLong(row.get("msgId")),str(message.get("mode")));
        if(selectedScope==null){done(row,"skipped","消息已不在接收人的授权范围内",false);return null;}
        if("staff".equals(message.get("mode"))&&db.count("select count(*) from kg_staff_grant where app_user_id=:u and status='0' and del_flag='0'",p("u",row.get("appUserId")))==0){done(row,"skipped","员工授权已撤销",false);return null;}
        if(str(row.get("eventKey")).startsWith("chat:")&&!"0".equals(str(message.get("readFlag")))){done(row,"skipped","会话已读，无需微信提醒",false);return null;}
        if(str(row.get("eventKey")).startsWith("chat:")&&db.count("select count(*) from kg_chat_member where conv_id=:c and app_user_id=:u and (left_time is not null or muted='1')",p("c",message.get("bizId"),"u",row.get("appUserId")))>0){done(row,"skipped","会话已退出或免打扰",false);return null;}
        if(!payload.containsKey("now"))payload.put("now",KgSubscribeService.eventTime());
        if(!payload.containsKey("scene"))payload.put("scene",KgSubscribeScenes.label(str(row.get("bizKey"))));
        Map<String,Object> data;
        try{data=KgSubscribeService.buildData(str(tpl.get("fields")),payload);}catch(IllegalArgumentException e){done(row,"failed",e.getMessage(),false);return null;}
        Map<String,Object> grant=db.one("select quota,grant_version from kg_subscribe_grant where app_user_id=:u and template_id=:t for update",
                p("u",row.get("appUserId"),"t",row.get("templateId")));
        if(grant==null || KgJdbc.asLong(grant.get("quota"))<=0)
        { done(row,"skipped","用户未授权或授权次数已用完",false); return null; }
        if (db.update("update kg_subscribe_grant set quota=quota-1,update_time=now() where app_user_id=:u and template_id=:t and quota>0",
                p("u", row.get("appUserId"), "t", row.get("templateId"))) == 0)
        { done(row, "skipped", "用户未授权或授权次数已用完", false); return null; }
        db.update("update kg_subscribe_outbox set status='sending',template_id=:t,grant_version=:v,attempts=attempts+1,update_time=now() where outbox_id=:id", p("id", id, "t", row.get("templateId"),"v",grant.get("grantVersion")));
        String encodedScope;
        try { encodedScope=URLEncoder.encode(selectedScope,"UTF-8").replace("+","%20"); }
        catch(UnsupportedEncodingException e) { throw new IllegalStateException(e); }
        String page = "pages/profile/subscription-message?id="+row.get("msgId")+"&mode="+("staff".equals(message.get("mode"))?"staff":"user")+"&scope="+encodedScope;
        Map<String, Object> work = new LinkedHashMap<String, Object>(row);
        work.put("openid", user.get("openid")); work.put("page", page);
        work.put("data",data);
        return work;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void clearRejectedGrant(long id){
        Map<String,Object> row=db.one("select app_user_id,template_id,grant_version from kg_subscribe_outbox where outbox_id=:id",p("id",id));
        if(row!=null)db.update("update kg_subscribe_grant set quota=0,grant_version=grant_version+1,update_time=now() where app_user_id=:u and template_id=:t and grant_version=:v",p("u",row.get("appUserId"),"t",row.get("templateId"),"v",row.get("grantVersion")));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void finish(long id, String status, String detail, boolean returnQuota)
    {
        Map<String, Object> row = db.one("select * from kg_subscribe_outbox where outbox_id=:id for update", p("id", id));
        if (row != null && "sending".equals(row.get("status"))) done(row, status, detail, returnQuota);
    }

    private void done(Map<String, Object> row, String status, String detail, boolean returnQuota)
    {
        if (returnQuota) restoreQuota(row);
        db.update("update kg_subscribe_outbox set status=:s,template_id=:t,detail=:d,update_time=now() where outbox_id=:id",
                p("id", row.get("outboxId"), "s", status, "t", row.get("templateId"), "d", StaffSupport.cut(detail, 255)));
        db.insert("kg_subscribe_log", p("app_user_id", row.get("appUserId"), "biz_key", row.get("bizKey"), "template_id", str(row.get("templateId")),
                "msg_id", row.get("msgId"), "status", status, "detail", StaffSupport.cut(detail, 255), "create_time", new Date()));
    }

    private void restoreQuota(Map<String,Object> row)
    {
        db.update("update kg_subscribe_grant set quota=least(5,quota+1),update_time=now() where app_user_id=:u and template_id=:t and grant_version=:v",
                p("u",row.get("appUserId"),"t",row.get("templateId"),"v",row.get("grantVersion")));
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
    public void retryUnattempted(long id,String detail)
    {
        Map<String,Object> row=db.one("select * from kg_subscribe_outbox where outbox_id=:id for update",p("id",id));
        if(row==null || !"sending".equals(row.get("status"))) return;
        if(KgJdbc.asLong(row.get("attempts"))>=3) { done(row,"failed",detail+"；凭证重试已用完，请核对配置",true); return; }
        restoreQuota(row);
        db.update("update kg_subscribe_outbox set status='queued',next_attempt_time=date_add(now(),interval 60 second),detail=:d,update_time=now() where outbox_id=:id",
                p("id",id,"d",StaffSupport.cut(detail,255)));
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void drain()
    {
        db.update("delete from kg_subscribe_consent where expires_time<date_sub(now(),interval 1 day)",p());
        for (Map<String, Object> row : db.list("select outbox_id from kg_subscribe_outbox where status='queued' and (next_attempt_time is null or next_attempt_time<=now()) order by outbox_id limit 100", p()))
            dispatchSafely(KgJdbc.asLong(row.get("outboxId")));
        for (Map<String, Object> row : db.list("select outbox_id from kg_subscribe_outbox where status='sending' and update_time<date_sub(now(),interval 5 minute) limit 100", p()))
            self.finish(KgJdbc.asLong(row.get("outboxId")), "unknown", "发送过程超时或中断，请核对微信结果；不会自动重复外发", false);
    }
}
