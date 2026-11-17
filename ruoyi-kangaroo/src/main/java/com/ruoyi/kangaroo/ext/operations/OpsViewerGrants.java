package com.ruoyi.kangaroo.ext.operations;
import java.util.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.admin.AdminCityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Separate read-only reporting grants. Never creates any staff/store/business permission. */
@Service public class OpsViewerGrants {
    @Autowired private KgJdbc db;
    public boolean allowed(long uid){return db.one("select v.app_user_id from kg_ops_viewer v join kg_app_user u on u.user_id=v.app_user_id where v.app_user_id=:u and v.enabled=1 and u.status='0' and u.del_flag='0'",p("u",uid))!=null;}
    public void requireViewer(long uid){if(!allowed(uid))throw new ServiceException("未开通团队报表查阅权限，请联系平台管理员",403);}
    private void admin(){if(AdminCityContext.limited())throw new ServiceException("团队报表查阅授权须由全部城市管理员处理",403);}
    public List<Map<String,Object>> list(){admin();return db.list("select v.app_user_id user_id,u.nickname,v.enabled,v.revision,v.reason,v.update_by,v.update_time from kg_ops_viewer v join kg_app_user u on u.user_id=v.app_user_id where u.del_flag='0' order by v.enabled desc,v.update_time desc",p());}
    public List<Map<String,Object>> search(String query){admin();String q=line(query,"账号关键词",40,true);require(q.length()>=2||q.matches("[0-9]+"),"请输入至少2个字符的昵称或用户ID");return db.list("select user_id,nickname,case when phone is null or phone='' then '' else concat(left(phone,3),'****',right(phone,4)) end phone_hint from kg_app_user where status='0' and del_flag='0' and (nickname like :q or cast(user_id as char)=:exact or phone=:exact) order by user_id desc limit 20",p("q","%"+q.replace("%","\\%").replace("_","\\_")+"%","exact",q));}
    @Transactional(rollbackFor=Exception.class) public Map<String,Object> save(long uid,Map<String,Object> body,long operator,String name){
        admin();require(body.get("enabled") instanceof Boolean,"请选择开通或撤销");String reason=line(body.get("reason"),"授权说明",300,true);
        Map<String,Object> user=db.must("select user_id,status from kg_app_user where user_id=:u and del_flag='0' for update",p("u",uid),"账号不存在");require(!Boolean.TRUE.equals(body.get("enabled"))||"0".equals(user.get("status")),"账号已停用，不能开通");
        Map<String,Object> old=db.one("select * from kg_ops_viewer where app_user_id=:u for update",p("u",uid));long revision=old==null?0:number(old.get("revision"));
        if(!Objects.equals(KgJdbc.asLong(body.get("revision")),revision))throw new ServiceException("授权状态已改变，请刷新后重试",409);
        int enabled=Boolean.TRUE.equals(body.get("enabled"))?1:0;require(old!=null||enabled==1,"账号尚未开通，无需撤销");require(old==null||number(old.get("enabled"))!=enabled,"授权状态没有变化");
        Map<String,Object> next=p("enabled",enabled,"revision",revision+1,"reason",reason,"update_by",name,"update_time",java.time.LocalDateTime.now(ZONE).format(DT));
        if(old==null){next.put("app_user_id",uid);db.insert("kg_ops_viewer",next);}else db.updateRow("kg_ops_viewer","app_user_id",uid,next);
        db.insert("kg_ops_viewer_change",p("app_user_id",uid,"revision",revision+1,"enabled",enabled,"operator_id",operator,"operator_name",name,"reason",reason,"before_json",JSON.toJSONString(old),"create_time",java.time.LocalDateTime.now(ZONE).format(DT)));
        return p("userId",uid,"enabled",enabled,"revision",revision+1);
    }
    public List<Map<String,Object>> history(long uid){admin();return db.list("select revision,enabled,operator_name,reason,create_time from kg_ops_viewer_change where app_user_id=:u order by revision desc limit 100",p("u",uid));}
}
