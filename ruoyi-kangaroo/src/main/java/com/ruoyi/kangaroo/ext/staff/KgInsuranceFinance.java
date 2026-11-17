package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.*;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.security.admin.AdminCityContext;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.util.InsuranceState;

/** Insurance principal only. Lock order: payment -> application -> insurance receipt -> account.
 * Channel callbacks and approvals share this transaction boundary. No historical payment is inferred. */
@Service
public class KgInsuranceFinance implements KgPayListener {
 @Autowired private KgJdbc db;
 @Autowired private KgInsuranceService insurance;
 @Autowired private StaffSupport support;
 @Autowired @Lazy private KgPayService pay;
 @Autowired private PlatformTransactionManager transactionManager;
 @Autowired(required=false) private com.ruoyi.kangaroo.service.IKgMessageService messages;
 @Autowired(required=false) private CityGrantScope cityGrants;
 private static final String SELECT="select i.*,pl.plan_name,s.store_name,f.payer_user_id,f.pay_no,f.amount amount_fen,f.method payment_method,f.status payment_status,f.refund_amount refunded_fen,f.refund_required,f.paid_at from kg_insurance_apply i left join kg_insurance_plan pl on pl.plan_id=i.plan_id left join kg_store s on s.store_id=i.store_id left join kg_insurance_payment f on f.apply_id=i.apply_id ";
 private static final String OPEN="('pending','approved','processing')";
 private static String str(Object v){return v==null?"":String.valueOf(v);}
 private static long number(Object v){try{return new BigDecimal(str(v)).longValueExact();}catch(Exception e){throw new ServiceException("金额或编号无效",400);}}
 private static long zero(Object v){return v==null?0:number(v);}
 private static ServiceException conflict(String s){return new ServiceException(s,409);}
 private static String text(Map<String,Object>b,String key,int max){String v=str(b.get(key)).trim();if(v.isEmpty()||v.length()>max)throw new ServiceException("请正确填写"+key,400);return v;}
 private static String key(Map<String,Object>b){String k=text(b,"requestKey",80);if(!k.matches("[A-Za-z0-9_-]{8,80}"))throw new ServiceException("提交编号无效，请重新打开页面",400);return k;}
 private static String hash(String input){try{byte[]h=MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:h)s.append(String.format("%02x",v));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 private static boolean eligible(Object s){return Arrays.asList("store_confirmed","contract_signed","onboarded").contains(str(s));}
 private long uid(){return AppUserContext.getUserId();}
 private List<Long> stores(String role){
  if(AppUserContext.messageRole()!=null)return AppUserContext.messageMediationStores(role);
  org.springframework.web.context.request.RequestAttributes attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
  javax.servlet.http.HttpServletRequest request=attributes instanceof org.springframework.web.context.request.ServletRequestAttributes?((org.springframework.web.context.request.ServletRequestAttributes)attributes).getRequest():null;
  String scope=request==null?null:request.getHeader("X-Message-Scope");
  if(scope!=null)for(StaffGrantView g:AppUserContext.require().getStaffGrants()){
   if(Boolean.TRUE.equals(g.getActive())&&role.equals(g.getRoleCode())&&g.getStoreId()!=null&&scope.equals("store:"+g.getStoreId()+":"+role))return Arrays.asList(g.getStoreId());
  }
  return Collections.emptyList();
 }
 private boolean role(long store,String role){return stores(role).contains(store);}
 private StaffSupport.Ctx actor(long store,String required){
  if(!role(store,required))throw new ServiceException("请切换到该门店的"+("merchant".equals(required)?"合作商家":"换工主理人")+"身份办理",403);
  StaffSupport.Ctx c=new StaffSupport.Ctx();c.me=AppUserContext.require();c.storeId=store;c.role=required;c.storeName=str(db.must("select store_name from kg_store where store_id=:s and status='0' and del_flag='0'",p("s",store),"门店已停用").get("storeName"));return c;
 }
 private List<Long> viewStores(){Set<Long>s=new LinkedHashSet<>();for(String r:Arrays.asList("merchant","manager","city_lead"))s.addAll(stores(r));return new ArrayList<>(s);}
 private Map<String,Object> raw(long id){return db.must(SELECT+"where i.apply_id=:id and i.del_flag='0'",p("id",id),"投保申请不存在");}
 private Map<String,Object> own(long id){Map<String,Object>r=raw(id);if(zero(r.get("payerUserId"))!=uid())throw new ServiceException("只能操作本人实际付款的投保记录",403);return r;}
 private Map<String,Object> receipt(long id){return db.must("select * from kg_insurance_payment where apply_id=:id",p("id",id),"这条历史申请没有小程序付款记录");}
 private Map<String,Object> lock(long id){
  Map<String,Object> f=receipt(id);if(!str(f.get("payNo")).isEmpty())pay.lockPayment(str(f.get("payNo")));
  Map<String,Object>i=db.must("select app_id from kg_insurance_apply where apply_id=:id and del_flag='0'",p("id",id),"投保申请不存在");
  db.must("select app_id from kg_application where app_id=:id for update",p("id",i.get("appId")),"报名不存在");
  return db.must("select * from kg_insurance_payment where apply_id=:id for update",p("id",id),"付款记录不存在");
 }
 private Map<String,Object> currentApply(long id){return db.must("select * from kg_insurance_apply where apply_id=:id for update",p("id",id),"申请不存在");}
 private boolean applicationActive(long id){Map<String,Object>r=db.one("select a.status from kg_insurance_apply i join kg_application a on a.app_id=i.app_id where i.apply_id=:id and a.del_flag='0' for share",p("id",id));return r!=null&&eligible(r.get("status"));}
 private Map<String,Object> refundDto(Map<String,Object> r){if(r==null)return null;if("approved".equals(r.get("status")))r.put("status","processing");r.put("amountFen",r.get("amount"));r.put("approvedFen",r.get("approvedAmount"));return r;}
 private Map<String,Object> decorate(Map<String,Object> r,boolean admin){
  long id=number(r.get("applyId")), store=number(r.get("storeId"));boolean owner=!admin&&zero(r.get("payerUserId"))==uid();String ps=str(r.get("paymentStatus")),status=str(r.get("status"));
  List<Map<String,Object>> refunds=db.list("select * from kg_insurance_refund where apply_id=:id order by refund_id desc",p("id",id));refunds.forEach(this::refundDto);
  boolean active=refunds.stream().anyMatch(x->Arrays.asList("pending","approved","processing").contains(str(x.get("status"))));
  boolean unresolved=refunds.stream().anyMatch(x->"failed".equals(x.get("status"))&&!str(x.get("channelRefundNo")).isEmpty());
  long remaining=Math.max(0,zero(r.get("amountFen"))-zero(r.get("refundedFen")));r.put("referencePriceFen",r.get("price"));r.put("paymentStatus",ps.isEmpty()?"legacy":ps);r.put("isPayer",owner);r.put("refundableFen",remaining);r.put("refunds",refunds);r.put("refund",refunds.isEmpty()?null:refunds.get(0));
  r.put("canPay",owner&&"pending".equals(ps)&&"pending_payment".equals(status)&&role(store,"merchant")&&applicationActive(id));
  r.put("canReject",admin&&!active&&Arrays.asList("pending_payment","pending").contains(status));r.put("canCancel",owner&&"pending".equals(ps));r.put("canRefund",owner&&"paid".equals(ps)&&remaining>0&&!active&&!unresolved);
  r.put("canRegister",(admin||role(store,"manager"))&&"pending".equals(status)&&!active&&(ps.isEmpty()||"paid".equals(ps))&&zero(r.get("refundRequired"))==0&&applicationActive(id));
  r.put("refundRequired",zero(r.get("refundRequired"))==1);InsuranceState.decorate(r);return r;
 }
 public Map<String,Object> detail(long id,boolean admin){Map<String,Object>r=raw(id);if(!admin&&zero(r.get("payerUserId"))!=uid()&&!viewStores().contains(number(r.get("storeId"))))throw new ServiceException("无权查看该投保申请",403);return decorate(r,admin);}
 public Map<String,Object> context(Long storeId){
  List<Long> ids=viewStores();long store=storeId==null?(ids.isEmpty()?0:ids.get(0)):storeId;String role=AppUserContext.messageRole();
  Map<String,Object>out=p("role",role,"storeId",store,"canApply",role(store,"merchant"),"canRegister",role(store,"manager"),"account",account(),"plans",insurance.plans(),"candidates",Collections.emptyList());
  if(ids.contains(store)){StaffSupport.Ctx c=actorForView(store);out.put("candidates",insurance.toBuy(c).get("rows"));}else if(store!=0&&db.count("select count(*) from kg_insurance_payment where payer_user_id=:u and store_id=:s",p("u",uid(),"s",store))==0)throw new ServiceException("没有该门店权限",403);return out;
 }
 private StaffSupport.Ctx actorForView(long s){for(String r:Arrays.asList("merchant","manager","city_lead"))if(role(s,r)){StaffSupport.Ctx c=new StaffSupport.Ctx();c.me=AppUserContext.require();c.storeId=s;c.role=r;return c;}throw new ServiceException("没有门店权限",403);}
 public Map<String,Object> list(Long storeId){List<Long>ids=viewStores();Map<String,Object>ps=p("u",uid(),"ids",ids.isEmpty()?Arrays.asList(-1L):ids);String w="where i.del_flag='0' and (f.payer_user_id=:u or i.store_id in (:ids))";if(storeId!=null){w+=" and i.store_id=:s";ps.put("s",storeId);}List<Map<String,Object>>rows=db.list(SELECT+w+" order by i.apply_id desc limit 300",ps);for(Map<String,Object>r:rows)decorate(r,false);return p("rows",rows);}
 public Map<String,Object> account(){long user=uid();Map<String,Object>a=db.one("select balance balance_fen from kg_insurance_account where user_id=:u",p("u",user));return p("balanceFen",a==null?0:a.get("balanceFen"),"hasPayments",db.count("select count(*) from kg_insurance_payment where payer_user_id=:u",p("u",user))>0,"entries",db.list("select entry_id,amount amount_fen,balance_after balance_after_fen,type,apply_id,refund_id,created_at from kg_insurance_entry where user_id=:u order by entry_id desc limit 100",p("u",user)));}
 public Map<String,Object> create(Map<String,Object>b){
  return newRequestTransaction(()->createRequest(b));
 }
 /** Stable parent row locks serialize each application. READ COMMITTED prevents empty insurance/refund
  * ranges of unrelated applications taking mutually blocking RR gap locks; all validation stays in this transaction. */
 private <T>T newRequestTransaction(Supplier<T> work){
  TransactionTemplate tx=new TransactionTemplate(transactionManager);
  tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  return tx.execute(status->work.get());
 }
 private Map<String,Object> createRequest(Map<String,Object>b){
  long user=uid(),app=number(b.get("appId")),plan=number(b.get("planId"));String k=key(b),start=text(b,"startDate",10),method=text(b,"method",16);if(!Arrays.asList("wechat","balance").contains(method))throw conflict("请选择微信支付或保险余额");
  String digest=hash(app+"|"+plan+"|"+start+"|"+method);
  // Lock an existing idempotency row, not a missing receipt key (RR gap locks deadlock independent purchases).
  db.update("insert ignore into kg_insurance_request(payer_user_id,request_kind,request_key,request_hash) values(:u,'payment',:k,:h)",p("u",user,"k",k,"h",digest));
  Map<String,Object> request=db.must("select * from kg_insurance_request where payer_user_id=:u and request_kind='payment' and request_key=:k for update",p("u",user,"k",k),"投保请求不存在");
  if(!digest.equals(request.get("requestHash")))throw conflict("同一提交编号不能修改申请内容");
  if(request.get("applyId")!=null)return detail(number(request.get("applyId")),false);
  Map<String,Object>a=db.must("select a.app_id,a.status,coalesce(j.recruit_store_id,a.store_id) store_id from kg_application a join kg_job j on j.job_id=a.job_id where a.app_id=:a and a.del_flag='0' for update",p("a",app),"报名不存在");
  StaffSupport.Ctx c=actor(number(a.get("storeId")),"merchant");
  Map<String,Object>created=insurance.createForPayment(c,app,plan,start);long id=number(created.get("applyId"));Map<String,Object>i=db.must("select price from kg_insurance_apply where apply_id=:id",p("id",id),"申请不存在");long amount=number(i.get("price"));if(amount<=0)throw conflict("该保险方案未配置有效保费，请联系平台");
  db.update("insert into kg_insurance_payment(apply_id,payer_user_id,store_id,request_key,request_hash,amount,method,expire_time) values(:id,:u,:s,:k,:h,:a,:m,date_add(now(),interval 30 minute))",p("id",id,"u",user,"s",c.storeId,"k",k,"h",digest,"a",amount,"m",method));
  if("balance".equals(method)){balance(user,-amount,"pay:"+id,id,null,"insurance_payment");db.update("update kg_insurance_payment set status='paid',paid_at=now() where apply_id=:id",p("id",id));db.update("update kg_insurance_apply set status='pending' where apply_id=:id",p("id",id));paidNotice(id);}
  else{Map<String,Object>p=pay.createForNewBusiness(bizType(),id,user,c.storeId,amount,"用工保险保费");db.update("update kg_insurance_payment set pay_no=:n where apply_id=:id",p("n",p.get("payNo"),"id",id));}
  db.update("update kg_insurance_request set apply_id=:id where payer_user_id=:u and request_kind='payment' and request_key=:k and apply_id is null",p("id",id,"u",user,"k",k));
  return detail(id,false);
 }
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> resume(long id){own(id);Map<String,Object>f=lock(id);actor(number(f.get("storeId")),"merchant");if(!"pending".equals(f.get("status"))||!applicationActive(id)||!"pending_payment".equals(currentApply(id).get("status")))return detail(id,false);if(db.count("select count(*) from kg_insurance_payment where apply_id=:id and expire_time>date_add(now(),interval 65 second)",p("id",id))!=1)throw conflict("付款时限已结束，请取消后重新申请");return detail(id,false);}
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> cancel(long id){own(id);Map<String,Object>f=lock(id);if("closed".equals(f.get("status")))return detail(id,false);if(!"pending".equals(f.get("status")))throw conflict("已付款，请申请退款");if(!str(f.get("payNo")).isEmpty()&&!pay.close(str(f.get("payNo")))){
  db.update("update kg_insurance_payment set refund_required=1 where apply_id=:id and status='paid'",p("id",id));
  db.update("update kg_insurance_apply set status='closed',handle_note='取消时核实保费已到账，请选择退款去向',update_time=now() where apply_id=:id",p("id",id));
  notifyPayer(id,"取消时已核实缴费，请申请退款","保费已实际到账，请选择原路退款或存入保险余额，提交平台审核。");return detail(id,false);
 }db.update("update kg_insurance_payment set status='closed' where apply_id=:id and status='pending'",p("id",id));db.update("update kg_insurance_apply set status='closed',update_time=now() where apply_id=:id and status='pending_payment'",p("id",id));return detail(id,false);}
 public Map<String,Object> requestRefund(long id,Map<String,Object>b){
  return newRequestTransaction(()->createRefundRequest(id,b));
 }
 private Map<String,Object> createRefundRequest(long id,Map<String,Object>b){
  own(id);String k=key(b),reason=text(b,"reason",500),destination=text(b,"destination",16);long amount=number(b.get("amountFen"));String digest=hash(id+"|"+amount+"|"+reason+"|"+destination);
  db.update("insert ignore into kg_insurance_request(payer_user_id,request_kind,request_key,request_hash) values(:u,'refund',:k,:h)",p("u",uid(),"k",k,"h",digest));
  Map<String,Object> request=db.must("select * from kg_insurance_request where payer_user_id=:u and request_kind='refund' and request_key=:k for update",p("u",uid(),"k",k),"退款请求不存在");
  if(!digest.equals(request.get("requestHash")))throw conflict("重复提交的退款内容不一致");if(request.get("refundId")!=null)return detail(id,false);
  Map<String,Object>f=lock(id);
  if(!Arrays.asList("original","balance").contains(destination))throw conflict("请选择原路退款或保险余额");if("balance".equals(f.get("method"))&&!"balance".equals(destination))throw conflict("保险余额支付的款项只能退回保险余额");
  if(!"paid".equals(f.get("status"))||amount<=0||amount>number(f.get("amount"))-number(f.get("refundAmount")))throw conflict("申请金额超过可退保费");
  if(db.count("select count(*) from kg_insurance_refund where apply_id=:id and status in "+OPEN+" for share",p("id",id))>0)throw conflict("已有退款申请处理中");
  // A previously closed channel refund can still produce a late SUCCESS. Do not open another refund until its channel outcome is certain.
  if(db.count("select count(*) from kg_insurance_refund where apply_id=:id and status='failed' and channel_refund_no is not null",p("id",id))>0)throw conflict("上一笔原路退款需平台核对渠道结果，暂不能重复退款");
  String prior=str(currentApply(id).get("status"));long refund=db.insert("kg_insurance_refund",p("apply_id",id,"store_id",f.get("storeId"),"payer_user_id",uid(),"request_key",k,"request_hash",digest,"amount",amount,"destination",destination,"prior_status",prior,"reason",reason));
  db.update("update kg_insurance_request set refund_id=:id where payer_user_id=:u and request_kind='refund' and request_key=:k and refund_id is null",p("id",refund,"u",uid(),"k",k));
  db.update("update kg_insurance_apply set status='refunding',update_time=now() where apply_id=:id",p("id",id));return detail(id,false);
 }
 /** All balance effects have an immutable unique business key and atomic receipt/ledger/account updates. */
 private void balance(long user,long delta,String key,long apply,Long refund,String type){
  List<Long> scope=AdminCityContext.cities();try{AdminCityContext.clear();
   db.update("insert ignore into kg_insurance_account(user_id,balance) values(:u,0)",p("u",user));Map<String,Object>a=db.must("select balance from kg_insurance_account where user_id=:u for update",p("u",user),"保险账户不存在");
   Map<String,Object>old=db.one("select user_id,amount from kg_insurance_entry where business_key=:k",p("k",key));if(old!=null){if(number(old.get("userId"))!=user||number(old.get("amount"))!=delta)throw conflict("保险账本记录冲突");return;}
   long after=Math.addExact(number(a.get("balance")),delta);if(after<0)throw conflict("保险余额不足，请选择微信全额支付");
   db.insert("kg_insurance_entry",p("user_id",user,"business_key",key,"apply_id",apply,"refund_id",refund,"type",type,"amount",delta,"balance_after",after));db.update("update kg_insurance_account set balance=:b,updated_at=now() where user_id=:u",p("b",after,"u",user));
  }finally{if(scope==null)AdminCityContext.clear();else AdminCityContext.set(scope);}
 }
 @Override public String bizType(){return "insurance_apply";}
 @Override public void beforePay(Map<String,Object>payment,long userId){
  Map<String,Object>f=receipt(number(payment.get("bizId")));
  if(number(f.get("payerUserId"))!=userId||!Objects.equals(f.get("payNo"),payment.get("payNo")))throw conflict("保险付款账号不匹配");
  actor(number(f.get("storeId")),"merchant");
 }
 @Override public void onPaid(Map<String,Object>payment){
  long id=number(payment.get("bizId"));Map<String,Object>f=lock(id);
  if(!Objects.equals(f.get("payNo"),payment.get("payNo"))||number(f.get("payerUserId"))!=number(payment.get("appUserId"))||number(f.get("amount"))!=number(payment.get("amount")))throw conflict("保险付款记录不匹配");
  if("paid".equals(f.get("status")))return;
  boolean valid=applicationActive(id)&&"pending_payment".equals(currentApply(id).get("status"))&&"pending".equals(f.get("status"));
  db.update("update kg_insurance_payment set status='paid',paid_at=now(),refund_required=:r where apply_id=:id",p("id",id,"r",valid?0:1));
  db.update("update kg_insurance_apply set status=:s,update_time=now() where apply_id=:id",p("id",id,"s",valid?"pending":"closed"));
  if(valid)paidNotice(id);else notifyPayer(id,"保险付款已到账，请申请退款","报名或申请已结束，保费已记录。请选择原路退款或退到保险余额，提交平台审核。");
 }
 private void paidNotice(long id){
  Map<String,Object>r=raw(id);notifyPayer(id,"保险缴费成功","保费已到账，等待换工主理人核对保险公司真实保单并登记；付款不代表已有保障。");
  if(messages==null)return;
  Set<Long> managers=new LinkedHashSet<>();for(Map<String,Object>g:db.list("select g.app_user_id from kg_staff_grant g join kg_app_user u on u.user_id=g.app_user_id where g.store_id=:s and g.role_code='manager' and g.status='0' and g.del_flag='0' and u.status='0' and u.del_flag='0'",p("s",r.get("storeId"))))managers.add(number(g.get("appUserId")));
  if(cityGrants!=null)managers.addAll(cityGrants.holderIds(number(r.get("storeId")),Arrays.asList("manager")));
  for(Long manager:managers)messages.send(manager,"staff",MessageCategory.CONTRACT,"保险待登记保单",str(r.get("insuredName"))+" 的保险办理已进入出单登记，请核对保险公司真实保单号及保障起止日期。","insurance_policy",id,"/pages/staff/insurance-account?storeId="+r.get("storeId"));
 }
 private void notifyPayer(long id,String title,String text){Map<String,Object>r=receipt(id);String route="/pages/staff/insurance-account?applyId="+id;long user=number(r.get("payerUserId"));support.notifyUser(user,MessageCategory.CONTRACT,title,text,"insurance_finance",id,route);if(messages!=null)messages.send(user,"staff",MessageCategory.CONTRACT,title,text,"insurance_finance",id,route);}
 @Override public void beforeRefund(Map<String,Object>payment,long amount){
  long id=number(payment.get("bizId"));Map<String,Object>f=lock(id);Map<String,Object>r=db.one("select * from kg_insurance_refund where apply_id=:id and status='approved' and destination='original' for update",p("id",id));
  if(r==null||number(r.get("approvedAmount"))!=amount||!"wechat".equals(f.get("method"))||amount>number(f.get("amount"))-number(f.get("refundAmount")))throw conflict("保险退款必须按付款人选择的去向，在投保管理中审核");
  db.update("update kg_insurance_refund set status='processing' where refund_id=:r",p("r",r.get("refundId")));
 }
 @Override public void onRefundRequested(Map<String,Object>payment,String refundNo,long amount){
  if(db.update("update kg_insurance_refund set channel_refund_no=:n where apply_id=:id and status='processing' and destination='original' and approved_amount=:a and channel_refund_no is null",p("n",refundNo,"id",payment.get("bizId"),"a",amount))!=1)throw conflict("保险退款关联失败");
 }
 @Override public void onRefunded(Map<String,Object>payment,long amount){
  long id=number(payment.get("bizId"));Map<String,Object>f=lock(id);String no=str(payment.get("refundNo"));Map<String,Object>r;
  if(!no.isEmpty())r=db.one("select * from kg_insurance_refund where apply_id=:id and channel_refund_no=:n for update",p("id",id,"n",no));
  else r=db.one("select * from kg_insurance_refund where apply_id=:id and status='processing' and destination='original' and channel_refund_no is null for update",p("id",id));
  if(r==null||number(r.get("approvedAmount"))!=amount)throw conflict("保险退款凭证不匹配");if("success".equals(r.get("status")))return;completeRefund(f,r,false);
 }
 @Override public void onRefundClosed(Map<String,Object>payment,long amount){String no=str(payment.get("refundNo"));if(no.isEmpty())return;db.update("update kg_insurance_refund set status='failed',handle_note=concat(coalesce(handle_note,''),'；微信退款关闭，需核对渠道结果'),handled_at=now() where apply_id=:id and channel_refund_no=:n and status='processing'",p("id",payment.get("bizId"),"n",no));}
 private void completeRefund(Map<String,Object>f,Map<String,Object>r,boolean toBalance){
  long id=number(f.get("applyId")),rid=number(r.get("refundId")),amount=number(r.get("approvedAmount")),total=Math.addExact(number(f.get("refundAmount")),amount);
  if(total>number(f.get("amount")))throw conflict("累计退款超出实付保费");
  if(toBalance){
   balance(number(f.get("payerUserId")),amount,"refund:"+rid,id,rid,"insurance_refund");
   if(!str(f.get("payNo")).isEmpty()){
    Map<String,Object>payment=db.must("select amount,refund_amount from kg_pay_order where pay_no=:n for update",p("n",f.get("payNo")),"关联支付记录不存在");
    long aggregate=Math.addExact(number(payment.get("refundAmount")),amount);if(aggregate>number(payment.get("amount")))throw conflict("支付累计退款超出实付");
    db.update("update kg_pay_order set refund_amount=:a,status=:s,refund_time=now(),refund_reason='保险退款存入本人保险余额',update_by=:u,update_time=now() where pay_no=:n",p("a",aggregate,"s",aggregate==number(payment.get("amount"))?"refunded":"part_refunded","u",r.get("handledBy"),"n",f.get("payNo")));
   }
  }
  db.update("update kg_insurance_payment set refund_amount=:a,refund_required=0 where apply_id=:id",p("a",total,"id",id));db.update("update kg_insurance_refund set status='success',completed_at=now() where refund_id=:r",p("r",rid));
  db.update("update kg_insurance_apply set status='refunded',update_time=now() where apply_id=:id",p("id",id));
  notifyPayer(id,toBalance?"保险退款已存入保险余额":"保险退款已原路退回",toBalance?"已退保费可在下次办理保险时抵扣，明细可在保险账户查看。":"退款已由微信确认成功，请查看原付款账户。");
 }
 public Map<String,Object> adminList(int page,int size,String status,String keyword,Long store){size=Math.max(1,Math.min(100,size));page=Math.max(1,page);String w=" where i.del_flag='0'";Map<String,Object>b=p();if(status!=null&&!status.isEmpty()){w+=" and i.status=:s";b.put("s",status);}if(keyword!=null&&!keyword.isEmpty()){w+=" and (i.insured_name like :k or i.policy_no like :k)";b.put("k","%"+keyword+"%");}if(store!=null){w+=" and i.store_id=:store";b.put("store",store);}long total=db.count("select count(*) from kg_insurance_apply i"+w,b);List<Map<String,Object>>rows=db.list(SELECT+w+" order by i.apply_id desc limit "+size+" offset "+((page-1)*size),b);rows.forEach(r->decorate(r,true));return p("rows",rows,"total",total);}
 public Map<String,Object> adminRefunds(int page,int size,String status,String keyword){size=Math.max(1,Math.min(100,size));page=Math.max(1,page);String w=" where 1=1";Map<String,Object>b=p();if(status!=null&&!status.isEmpty()){w+=" and r.status=:s";b.put("s",status);}if(keyword!=null&&!keyword.isEmpty()){w+=" and i.insured_name like :k";b.put("k","%"+keyword+"%");}String from=" from kg_insurance_refund r join kg_insurance_apply i on i.apply_id=r.apply_id left join kg_store s on s.store_id=r.store_id ";long total=db.count("select count(*)"+from+w,b);List<Map<String,Object>>rows=db.list("select r.*,i.insured_name,s.store_name"+from+w+" order by r.refund_id desc limit "+size+" offset "+((page-1)*size),b);rows.forEach(this::refundDto);return p("rows",rows,"total",total);}
 /** Verify the case in the admin's city scope first; internal payment/account effects must not be filtered into incomplete totals. */
 private <T>T adminBusiness(long id,Supplier<T> work){raw(id);List<Long>s=AdminCityContext.cities();try{AdminCityContext.clear();return work.get();}finally{if(s==null)AdminCityContext.clear();else AdminCityContext.set(s);}}
 public Map<String,Object> audit(long rid,Map<String,Object>b,String admin){
  // Approval only records a durable channel request (or an atomic balance entry), never calls the refund gateway.
  // Roll back the whole attempt before retrying RR range-lock conflicts in the shared payment queue.
  TransactionTemplate tx=new TransactionTemplate(transactionManager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  for(int attempt=0;;attempt++)try{return tx.execute(status->auditRequest(rid,b,admin));}
  catch(org.springframework.dao.DeadlockLoserDataAccessException e){if(attempt>=2)throw conflict("另一笔保险申请正在处理，请稍后重试");}
 }
 private Map<String,Object> auditRequest(long rid,Map<String,Object>b,String admin){
  Map<String,Object>ref=db.must("select apply_id from kg_insurance_refund where refund_id=:r",p("r",rid),"退款申请不存在");long id=number(ref.get("applyId"));
  return adminBusiness(id,()->{
   Map<String,Object>f=lock(id);Map<String,Object>r=db.must("select * from kg_insurance_refund where refund_id=:r for update",p("r",rid),"退款申请不存在");boolean approve=Boolean.TRUE.equals(b.get("approve"));String note=text(b,"reason",500);if(!"pending".equals(r.get("status")))throw conflict("退款已处理，请刷新");
   if(!approve){db.update("update kg_insurance_refund set status='rejected',handle_note=:n,handled_by=:u,handled_at=now() where refund_id=:r",p("n",note,"u",admin,"r",rid));db.update("update kg_insurance_apply set status=:s,update_time=now() where apply_id=:id",p("s",number(f.get("refundAmount"))>0?"refunded":(applicationActive(id)?r.get("priorStatus"):"closed"),"id",id));notifyPayer(id,"保险退款申请未通过",note);return detail(id,true);}
   long amount=number(b.get("amountFen"));if(amount<=0||amount>number(r.get("amount"))||amount>number(f.get("amount"))-number(f.get("refundAmount")))throw conflict("审核退款金额超过申请金额或可退保费");
   db.update("update kg_insurance_refund set status='approved',approved_amount=:a,handle_note=:n,handled_by=:u,handled_at=now() where refund_id=:r",p("a",amount,"n",note,"u",admin,"r",rid));r.put("approvedAmount",amount);r.put("handledBy",admin);
   if("balance".equals(r.get("destination")))completeRefund(f,r,true);else pay.refund(str(f.get("payNo")),amount,note,admin);
   return detail(id,true);
  });
 }
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> recheck(long rid,String admin){
  Map<String,Object>ref=db.must("select apply_id from kg_insurance_refund where refund_id=:r",p("r",rid),"退款申请不存在");long id=number(ref.get("applyId"));
  return adminBusiness(id,()->{Map<String,Object>f=lock(id);Map<String,Object>r=db.must("select * from kg_insurance_refund where refund_id=:r for update",p("r",rid),"退款申请不存在");
   if(!Arrays.asList("failed","processing").contains(str(r.get("status")))||str(r.get("channelRefundNo")).isEmpty())throw conflict("该退款无需渠道关闭核对");
   String outcome=pay.recheckInsuranceRefund(str(f.get("payNo")),str(r.get("channelRefundNo")));
   if("closed".equals(outcome)){db.update("update kg_insurance_refund set status='closed',handle_note=concat(coalesce(handle_note,''),'；已向微信核实关闭，未退款，可重新申请'),handled_by=:u,handled_at=now() where refund_id=:r and status='failed'",p("u",admin,"r",rid));db.update("update kg_insurance_apply set status=:s,update_time=now() where apply_id=:id and status='refunding'",p("s",number(f.get("refundAmount"))>0?"refunded":(applicationActive(id)?r.get("priorStatus"):"closed"),"id",id));notifyPayer(id,"保险原路退款已关闭，可重新申请","平台已向微信核实该笔未退款。请在保险账户重新选择退款去向并提交。");}
   return detail(id,true);
  });
 }
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> register(long id,Map<String,Object>b,boolean admin,String operator){
  Map<String,Object>i=raw(id);if(!admin)actor(number(i.get("storeId")),"manager");
  if(admin)return adminBusiness(id,()->{insurance.effect(id,text(b,"policyNo",64),text(b,"startDate",10),text(b,"endDate",10),operator);return detail(id,true);});
  insurance.effectByManager(actor(number(i.get("storeId")),"manager"),id,text(b,"policyNo",64),text(b,"startDate",10),text(b,"endDate",10));return detail(id,false);
 }
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> reject(long id,String reason,String admin){return adminBusiness(id,()->{
  Map<String,Object>f=db.one("select * from kg_insurance_payment where apply_id=:id",p("id",id));if(f==null){insurance.reject(id,reason,admin);return detail(id,true);}f=lock(id);if(reason==null||reason.trim().isEmpty()||reason.length()>500)throw conflict("请填写处理原因");
  if(db.count("select count(*) from kg_insurance_refund where apply_id=:id and status in "+OPEN,p("id",id))>0)throw conflict("请先处理退款申请");if(!Arrays.asList("pending_payment","pending","closed").contains(str(currentApply(id).get("status"))))throw conflict("已出单保单请通过退款审核处理");
  if("pending".equals(f.get("status"))){if(!str(f.get("payNo")).isEmpty()&&!pay.close(str(f.get("payNo"))))f=receipt(id);else db.update("update kg_insurance_payment set status='closed' where apply_id=:id and status='pending'",p("id",id));}
  if("paid".equals(f.get("status"))){db.update("update kg_insurance_payment set refund_required=1 where apply_id=:id",p("id",id));notifyPayer(id,"投保需退款，请选择退款去向",reason);}
  db.update("update kg_insurance_apply set status='closed',handle_note=:n,handled_by=:u,handled_time=now(),update_time=now() where apply_id=:id",p("n",reason.substring(0,Math.min(255,reason.length())),"u",admin,"id",id));return detail(id,true);
 });}
 /** Expired unpaid receipts do not block renewal forever; late paid receipts are retained for explicit refund choice. */
 @Scheduled(fixedDelay=60000,initialDelay=90000)
 public void expire(){for(Map<String,Object>x:db.list("select apply_id from kg_insurance_payment where status='pending' and expire_time<now() order by expire_time limit 20",p()))try{new TransactionTemplate(transactionManager).execute(s->{long id=number(x.get("applyId"));Map<String,Object>f=lock(id);if(!"pending".equals(f.get("status")))return null;if(!str(f.get("payNo")).isEmpty()&&!pay.close(str(f.get("payNo"))))return null;db.update("update kg_insurance_payment set status='closed' where apply_id=:id and status='pending'",p("id",id));db.update("update kg_insurance_apply set status='closed',update_time=now() where apply_id=:id and status='pending_payment'",p("id",id));return null;});}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Insurance payment expiry deferred apply={}",x.get("applyId"));}}
}
