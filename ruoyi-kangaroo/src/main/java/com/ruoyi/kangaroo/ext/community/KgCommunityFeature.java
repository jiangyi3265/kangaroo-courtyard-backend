package com.ruoyi.kangaroo.ext.community;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Persistent product switches for every user/client; absent or malformed means closed. */
@Service
public class KgCommunityFeature {
    public static final String KEY = "kangaroo.community.enabled";
    public static final String MESSAGE = "社区内容功能暂未开放";
    public static final String USER_ACTIVITY_VISIBLE_SQL = " (a.source <> 'user' or exists (select 1 from sys_config cf where cf.config_key='" + KEY + "' and binary cf.config_value='true'))";
    private final KgJdbc db;
    public KgCommunityFeature(KgJdbc db) { this.db = db; }
    public static final String RECRUITMENT_KEY = "kangaroo.recruitment.enabled";
    public static void requireRecruitment(KgJdbc db) { if(!isEnabled(db,RECRUITMENT_KEY)) throw new ServiceException("求职招聘服务暂未开放",423); }
    public void requireRecruitment() { requireRecruitment(db); }
    public boolean recruitmentEnabled() { return isEnabled(db,RECRUITMENT_KEY); }
    public static final String STAY_KEY = "kangaroo.stay_booking.enabled";
    public static void requireStayBooking(KgJdbc db) { if(!isEnabled(db,STAY_KEY)) throw new ServiceException("旅居住宿预订暂未开放",423); }
    public void requireStayBooking() { requireStayBooking(db); }
    public boolean stayBookingEnabled() { return isEnabled(db,STAY_KEY); }
    public static final String WISH_KEY = "kangaroo.wish.enabled";
    public static final String PRIVATE_KEY = "kangaroo.private_chat.enabled";
    public static final String GROUP_KEY = "kangaroo.group_chat.enabled";
    public static boolean isEnabled(KgJdbc db, String key) {
        Map<String,Object> row=db.one("select config_value from sys_config where config_key=:k",p("k",key));
        return row!=null && "true".equals(row.get("configValue"));
    }
    public static void requireWish(KgJdbc db) { if(!isEnabled(db,WISH_KEY)) throw new ServiceException("心愿互助暂未开放",423); }
    public static void requireGroupChat(KgJdbc db) { if(!isEnabled(db,GROUP_KEY)) throw new ServiceException("群聊与入群功能暂未开放",423); }
    public static void requirePrivateChat(KgJdbc db) { if(!isEnabled(db,PRIVATE_KEY)) throw new ServiceException("用户私信与交友互动暂未开放",423); }
    public void requirePrivateChat() { requirePrivateChat(db); }
    public boolean privateChatEnabled() { return isEnabled(db,PRIVATE_KEY); }
    public boolean wishEnabled() { return isEnabled(db,WISH_KEY); }
    public boolean groupChatEnabled() { return isEnabled(db,GROUP_KEY); }
    private static String key(String feature) {
        if("recruitment".equals(feature)) return RECRUITMENT_KEY;
        if("stay-booking".equals(feature)) return STAY_KEY;
        if("wish".equals(feature)) return WISH_KEY;
        if("group-chat".equals(feature)) return GROUP_KEY;
        if("private-chat".equals(feature)) return PRIVATE_KEY;
        throw new ServiceException("功能不存在",404);
    }
    public Map<String,Object> state(String feature) {
        Map<String,Object> row=db.one("select config_value,update_by,update_time from sys_config where config_key=:k",p("k",key(feature)));
        return p("enabled",row!=null && "true".equals(row.get("configValue")),"updatedBy",row==null?"":row.get("updateBy"),"updatedAt",row==null?null:row.get("updateTime"));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> saveFeature(String feature,Boolean enabled,Boolean expectedEnabled,Boolean categoryConfirmed,String operator) {
        String k=key(feature);
        if(enabled==null || expectedEnabled==null) throw new ServiceException("请明确选择开关状态",422);
        if(enabled && !Boolean.TRUE.equals(categoryConfirmed)) throw new ServiceException("请先确认所需类目及相关资质已办理通过",422);
        // Lock a stable existing row as well, serializing concurrent first creation across administrators.
        db.one("select config_id from sys_config order by config_id limit 1 for update",p());
        Map<String,Object> old=db.one("select config_id,config_value from sys_config where config_key=:k for update",p("k",k));
        boolean current=old!=null && "true".equals(old.get("configValue"));
        if(current!=expectedEnabled) throw new ServiceException("开关状态已被修改，请刷新后重试",409);
        if(old==null) db.insert("sys_config",p("config_name","recruitment".equals(feature)?"求职招聘功能开关":"stay-booking".equals(feature)?"旅居住宿预订开关":"wish".equals(feature)?"心愿互助功能开关":"private-chat".equals(feature)?"用户私信与交友互动开关":"群聊与入群功能开关","config_key",k,"config_value",enabled.toString(),"config_type","N","create_by",operator,"create_time",LocalDateTime.now(),"update_by",operator,"update_time",LocalDateTime.now(),"remark","默认关闭，相关类目与资质办妥后再开启；已有业务数据保留"));
        else db.update("update sys_config set config_value=:v,update_by=:by,update_time=now() where config_id=:id",p("v",enabled.toString(),"by",operator,"id",old.get("configId")));
        return state(feature);
    }
    public boolean enabled() {
        Map<String,Object> row = db.one("select config_value from sys_config where config_key=:k", p("k", KEY));
        return row != null && "true".equals(row.get("configValue"));
    }
    public void requireEnabled() { if (!enabled()) throw new ServiceException(MESSAGE, 423); }
    public Map<String,Object> state() {
        Map<String,Object> row = db.one("select config_value,update_by,update_time from sys_config where config_key=:k", p("k", KEY));
        return p("communityEnabled", row != null && "true".equals(row.get("configValue")),
                "updatedBy", row == null ? "" : row.get("updateBy"), "updatedAt", row == null ? null : row.get("updateTime"));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(Boolean enabled, Boolean expectedEnabled, Boolean categoryConfirmed, String operator) {
        if (enabled == null || expectedEnabled == null) throw new ServiceException("请明确选择开关状态", 422);
        if (enabled && !Boolean.TRUE.equals(categoryConfirmed)) throw new ServiceException("请先确认社交—笔记类目及相关资质已办理通过", 422);
        Map<String,Object> old = db.one("select config_id,config_value from sys_config where config_key=:k for update", p("k", KEY));
        boolean current = old != null && "true".equals(old.get("configValue"));
        if (current != expectedEnabled) throw new ServiceException("开关状态已被修改，请刷新后重试", 409);
        if (old == null) db.insert("sys_config", p("config_name", "社区内容功能总开关", "config_key", KEY,
                "config_value", enabled.toString(), "config_type", "N", "create_by", operator,
                "create_time", LocalDateTime.now(), "update_by", operator, "update_time", LocalDateTime.now(),
                "remark", "关闭时停止社区公开展示与发布；仅在相应类目和资质办妥后开启"));
        else db.update("update sys_config set config_value=:v,update_by=:by,update_time=now() where config_id=:id",
                p("v", enabled.toString(), "by", operator, "id", old.get("configId")));
        return state();
    }
}
