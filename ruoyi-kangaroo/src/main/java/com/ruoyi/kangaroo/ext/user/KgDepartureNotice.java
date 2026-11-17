package com.ruoyi.kangaroo.ext.user;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
@Service
public class KgDepartureNotice {
 public static final String TITLE="袋鼠青年以工换宿旅行 · 行前安全须知";
 private final KgJdbc db;
 public KgDepartureNotice(KgJdbc db){this.db=db;}
 private Map<String,Object> own(Long user,Long app,boolean lock){
  Map<String,Object> a=db.one("select a.app_id,a.app_user_id,a.store_id,a.status,j.job_type,s.store_name from kg_application a join kg_job j on j.job_id=a.job_id left join kg_store s on s.store_id=a.store_id where a.app_id=:a and a.app_user_id=:u and a.del_flag='0'"+(lock?" for update":""),p("a",app,"u",user));
  if(a==null)throw new ServiceException("报名不存在",404);return a;
 }
 private Map<String,Object> document(boolean lock){
  Map<String,Object> r=db.one("select notice_content from sys_notice where notice_title=:t and status='0' order by notice_id desc limit 1"+(lock?" for update":""),p("t",TITLE));
  Object raw=r==null?null:r.get("noticeContent");String h=raw instanceof byte[]?new String((byte[])raw,StandardCharsets.UTF_8):raw==null?"":raw.toString();
  if(h.replaceAll("<[^>]*>","").trim().length()<200)throw new ServiceException("行前安全须知暂未发布，请稍后重试",503);
  return p("title",TITLE,"html",h,"version",DigestUtils.sha256Hex(h));
 }
 public Map<String,Object> read(Long user,Long app){
  Map<String,Object> a=own(user,app,false);if(!"volunteer".equals(a.get("jobType")))throw new ServiceException("该报名无需换工行前须知",404);
  Map<String,Object>d=document(false);d.put("storeName",a.get("storeName"));
  d.put("accepted",db.count("select count(*) from kg_departure_consent where app_id=:a and app_user_id=:u and doc_version=:v",p("a",app,"u",user,"v",d.get("version")))>0);
  d.put("records",db.list("select id,confirmed_at from kg_departure_consent where app_id=:a and app_user_id=:u order by id desc limit 20",p("a",app,"u",user)));return d;
 }
 public Map<String,Object> record(Long user,Long app,Long id){
  own(user,app,false);Map<String,Object>r=db.one("select content_html,confirmed_at,store_name,operator_name from kg_departure_consent where id=:id and app_id=:a and app_user_id=:u",p("id",id,"a",app,"u",user));
  if(r==null)throw new ServiceException("确认记录不存在",404);return r;
 }
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> accept(Long user,Long app,Map<String,Object>b){
  com.ruoyi.kangaroo.util.AccountState.requireActive(db,user);
  Map<String,Object>a=own(user,app,true);if(!"volunteer".equals(a.get("jobType")))throw new ServiceException("该报名无需换工行前须知",404);
  if(Arrays.asList("withdrawn","terminated","review_rejected").contains(a.get("status")))throw new ServiceException("本次报名已结束，可查看历史记录",409);
  if(!Boolean.TRUE.equals(b.get("agreed")))throw new ServiceException("请阅读并勾选同意行前安全须知",422);
  Map<String,Object>d=document(true);if(!d.get("version").equals(b.get("version")))throw new ServiceException("行前安全须知已更新，请重新阅读",409);
  Map<String,Object>ps=p("a",app,"u",user,"v",d.get("version"),"h",d.get("html"),"s",a.get("storeId"),"name",a.get("storeName"));
  if(db.count("select count(*) from kg_departure_consent where app_id=:a and app_user_id=:u and doc_version=:v",ps)==0)
   db.update("insert into kg_departure_consent(app_id,app_user_id,store_id,doc_version,content_html,store_name,operator_name,confirmed_at) select :a,:u,:s,:v,:h,:name,nickname,now() from kg_app_user where user_id=:u",ps);
  return read(user,app);
 }
 public void requireAccepted(Long user,Long app){
  Map<String,Object>a=own(user,app,true);if(!"volunteer".equals(a.get("jobType")))return;
  Map<String,Object>d=document(true);
  if(db.count("select count(*) from kg_departure_consent where app_id=:a and app_user_id=:u and doc_version=:v",p("a",app,"u",user,"v",d.get("version")))==0)throw new ServiceException("请先阅读并确认本次报名的行前安全须知",428);
 }
}
