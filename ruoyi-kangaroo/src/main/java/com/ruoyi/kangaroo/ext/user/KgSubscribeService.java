package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 微信订阅消息。
 *
 * 微信的一次性订阅规则：用户每点一次「允许」，这个模板只能再发一条。所以这里把授权次数当额度记在
 * kg_subscribe_grant 上，发一条扣一次，扣光了就不发 —— 超发会被微信直接拒（errcode 43101），
 * 更糟的是会被用户标记为骚扰。
 *
 * 三种通道（kangaroo.subscribe.mode）：
 *   none 不发（默认，也是没申请模板时的状态）；
 *   mock 本地联调：只写发送留痕，不外发，额度照常扣，便于验证整条链路；
 *   wx   真调微信接口。
 *
 * 无论哪种模式，站内信都照常写 —— 订阅消息是"额外提醒"，不是消息本身。
 */
@Service
public class KgSubscribeService
{

    /** 一个模板最多攒多少次授权：微信允许勾「总是保持以上选择」批量授权，但攒太多等于变相长期订阅 */
    static final int MAX_QUOTA = 5;

    /** 模板字段的最大长度（微信 thing 类型上限 20 个字） */
    static final int THING_MAX = 20;

    @Autowired
    private KgJdbc db;

    @Autowired
    private KangarooProperties properties;


    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    public String mode()
    {
        return properties.getSubscribeMode();
    }

    public boolean enabled()
    {
        return !"none".equals(mode());
    }

    /** 启用中的模板；小程序端据此调 requestSubscribeMessage */
    public List<Map<String, Object>> templates()
    {
        if (!enabled())
        {
            return new ArrayList<Map<String, Object>>();
        }
        return db.list("select biz_key, title, template_id, page from kg_subscribe_template where status = '0' order by tpl_id", p());
    }

    /**
     * 小程序端的订阅状态：每个模板还剩几次可发。
     * quota = 0 表示没有授权（或已经用完），页面上那一列就显示未勾选。
     */
    public Map<String, Object> status(Long userId)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", Boolean.valueOf(enabled()));
        out.put("mode", mode());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, Long> quota = new LinkedHashMap<String, Long>();
        for (Map<String, Object> r : db.list("select template_id, quota from kg_subscribe_grant where app_user_id = :u", p("u", userId)))
        {
            quota.put(str(r.get("templateId")), KgJdbc.asLong(r.get("quota")));
        }
        boolean staff = db.count("select count(*) from kg_staff_grant where app_user_id=:u and status='0' and del_flag='0'",p("u",userId))>0;
        for (Map<String, Object> t : templates())
        {
            if(KgSubscribeScenes.staffOnly(str(t.get("bizKey")))&&!staff)continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            String tid = str(t.get("templateId"));
            Long q = quota.get(tid);
            m.put("bizKey", t.get("bizKey"));
            m.put("title", t.get("title"));
            m.put("templateId", tid);
            m.put("quota", q == null ? Long.valueOf(0) : q);
            m.put("on", Boolean.valueOf(q != null && q.longValue() > 0));
            rows.add(m);
        }
        out.put("rows", rows);
        out.put("note", enabled()
                ? "微信订阅消息是一次性授权：每授权一次，我们只能给你发一条；发完需要再次授权。"
                : "微信订阅消息尚未开通。");
        return out;
    }

    /** Preload before the user's tap. The receipt is single-use, account-bound and short-lived. */
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> prepare(Long uid) {
        Map<String,Object> out=status(uid);
        if(!enabled())return out;
        if(db.count("select count(*) from kg_subscribe_consent where app_user_id=:u and create_time>date_sub(now(),interval 1 minute)",p("u",uid))>=30)
            throw new ServiceException("订阅操作频繁，请稍后重试",429);
        Set<String> ids=new LinkedHashSet<>();
        for(Object v:(List<?>)out.get("rows"))ids.add(str(((Map<?,?>)v).get("templateId")));
        if(ids.isEmpty())return out;
        String ticket=UUID.randomUUID().toString().replace("-","");
        db.update("insert into kg_subscribe_consent(ticket,app_user_id,template_ids,expires_time,create_time) values(:t,:u,:i,date_add(now(),interval 10 minute),now())",p("t",ticket,"u",uid,"i",JSON.toJSONString(ids)));
        out.put("ticket",ticket);out.put("expiresIn",600);return out;
    }

    /**
     * 记录一次授权结果。小程序端把 wx.requestSubscribeMessage 的回调原样传上来：
     * {"模板ID": "accept" | "reject" | "ban"}。只有 accept 才加额度。
     *
     * @return 本次新增授权的模板数
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> accept(Long userId, Map<String, Object> results)
    {
        int n = 0;
        String ticket=results==null?"":str(results.get("ticket"));
        Map<?,?> answers=results!=null&&results.get("results") instanceof Map?(Map<?,?>)results.get("results"):null;
        if(answers==null||!ticket.matches("[a-f0-9]{32}"))throw new ServiceException("请重新打开订阅提醒后授权",422);
        Map<String,Object> receipt=db.one("select template_ids,used_time from kg_subscribe_consent where ticket=:t and app_user_id=:u and expires_time>now() for update",p("t",ticket,"u",userId));
        if(receipt==null)throw new ServiceException("订阅授权已过期，请重新点击",422);
        if(receipt.get("usedTime")!=null)return status(userId);
        List<String> permitted=JSON.parseArray(str(receipt.get("templateIds")),String.class);
        long count=answers.keySet().stream().filter(k->permitted.contains(str(k))).count();
        if(count>3)throw new ServiceException("每次最多授权三类模板",422);
        db.update("update kg_subscribe_consent set used_time=now() where ticket=:t",p("t",ticket));
        if (enabled())
        {
            for (Map.Entry<?, ?> e : answers.entrySet())
            {
                String tid=str(e.getKey());
                if(!permitted.contains(tid))continue;
                if("ban".equals(str(e.getValue()))) {
                    db.update("update kg_subscribe_grant set quota=0,grant_version=grant_version+1,update_time=now() where app_user_id=:u and template_id=:t",p("u",userId,"t",tid));continue;
                }
                if (!"accept".equals(str(e.getValue())))
                {
                    continue;
                }
                if (db.count("select count(*) from kg_subscribe_template where template_id = :t and status = '0'", p("t", tid)) == 0)
                {
                    continue;
                }
                db.update("insert into kg_subscribe_grant (app_user_id, template_id, quota, accepted_time, update_time) values (:u, :t, 1, now(), now()) "
                        + "on duplicate key update quota = least(:max, quota + 1), grant_version=grant_version+1, accepted_time = now(), update_time = now()",
                        p("u", userId, "t", tid, "max", Integer.valueOf(MAX_QUOTA)));
                n++;
            }
        }
        Map<String, Object> out = status(userId);
        out.put("accepted", Integer.valueOf(n));
        out.put("message", n > 0 ? "已开启微信订阅提醒" : "没有新的授权");
        return out;
    }

    /** 用户在设置里关掉某一类：把额度清零，之后不再发，直到他重新授权 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> revoke(Long userId, String bizKey)
    {
        db.update("update kg_subscribe_grant g join kg_subscribe_template t on t.template_id = g.template_id "
                + "set g.quota = 0, g.grant_version = g.grant_version+1, g.update_time = now() where g.app_user_id = :u and t.biz_key = :k", p("u", userId, "k", bizKey));
        Map<String, Object> out = status(userId);
        out.put("message", "已关闭这类微信提醒");
        return out;
    }

    @Autowired
    private KgSubscribeOutbox outbox;

    /** 与业务消息同事务入队，提交后才允许外发。 */
    public void push(Long userId, String bizKey, String title, String content, String route, Long msgId)
    {
        outbox.enqueue(userId, bizKey, title, content, route, msgId);
    }

    /**
     * 按后台配置的字段映射拼模板数据。
     * fields 形如 thing1=title,thing2=content,time3=now —— 左边是微信模板的字段名，
     * 右边只支持 title / content / now 三个来源，避免后台能写出越界的表达式。
     */
    static Map<String, Object> buildData(String fields, String title, String content)
    {
        return buildData(fields,p("title",title,"content",content,"scene","业务办理","now",eventTime(),"platform","袋鼠全球青年"));
    }

    static String eventTime(){return java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));}
    static String cutCodePoints(String value,int max){String s=value==null?"":value.replaceAll("[\\r\\n\\t]+"," ").trim();return s.codePointCount(0,s.length())>max?s.substring(0,s.offsetByCodePoints(0,max-1))+"…":s;}
    static Map<String,Object> buildData(String fields,Map<String,Object> values) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (fields == null || fields.trim().isEmpty())
        {
            throw new IllegalArgumentException("模板字段未配置");
        }
        for (String pair : fields.split(","))
        {
            String[] kv = pair.split("=",2);
            if (kv.length != 2)
            {
                throw new IllegalArgumentException("模板字段映射格式错误");
            }
            String key = kv[0].trim();
            String from = kv[1].trim();
            if(!key.matches("(thing|time|date|phrase|short_thing|name|character_string|amount|number|phone_number)[1-9][0-9]*")||data.containsKey(key))throw new IllegalArgumentException("模板字段名不合法或重复");
            String value=str(values.get(from));
            if(value.trim().isEmpty())throw new IllegalArgumentException("模板字段缺少数据");
            if(key.startsWith("thing"))value=cutCodePoints(value,20);
            else if(key.startsWith("short_thing")||key.startsWith("phrase"))value=cutCodePoints(value,5);
            else if(key.startsWith("name"))value=cutCodePoints(value,10);
            else if(key.startsWith("time")||key.startsWith("date")) { if(!value.matches("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}(:\\d{2})?)?"))throw new IllegalArgumentException("日期字段格式不正确"); }
            else if(key.startsWith("amount")&&!value.matches("[0-9]+(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("金额字段格式不正确");
            else if(key.startsWith("number")&&!value.matches("[0-9]{1,32}"))throw new IllegalArgumentException("数字字段格式不正确");
            else if(key.startsWith("phone_number")&&!value.matches("[+0-9 -]{3,20}"))throw new IllegalArgumentException("电话字段格式不正确");
            else if(key.startsWith("character_string")&&!value.matches("[A-Za-z0-9_\\-]{1,32}"))throw new IllegalArgumentException("编号字段格式不正确");
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            // thing 类型最多 20 个字，超了微信会整条拒收
            v.put("value",value);
            data.put(key, v);
        }
        return data;
    }

}
