package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.user.VolunteerAgreement.s;
import java.time.LocalDate;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.*;
import com.ruoyi.kangaroo.dto.OperatorDTO;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.service.IKgApplicationService;
import com.ruoyi.kangaroo.util.AccountState;

/** 每个修订是一份不可改写的用户确认快照；门店只能确认或退回，不能替用户改字后签署。 */
@Service
public class KgVolunteerContractService {
    @Autowired private KgJdbc db;
    @Autowired private StaffSupport support;
    @Autowired private IKgApplicationService applications;
    @Autowired private KgDepartureNotice departureNotice;
    @Value("${kangaroo.file.sign-secret:${token.secret}}") private String secret;
    private static final String APP = "select a.*, j.job_type,j.title,j.daily_hours,j.shift_desc,j.rest_mode,j.shift_time,j.board_desc,j.board_type,j.room_type,j.content, "
        + "s.store_name,s.address store_address,s.contact_phone store_phone from kg_application a join kg_job j on j.job_id=a.job_id "
        + "join kg_store s on s.store_id=a.store_id where a.app_id=:a and a.del_flag='0'";
    private Map<String,Object> app(Long id) { return db.must(APP,p("a",id),"报名不存在"); }
    private Map<String,Object> own(Long uid,Long id) {
        Map<String,Object> a=app(id);
        if(!uid.equals(KgJdbc.asLong(a.get("appUserId"))))throw new ServiceException("无权查看其他用户的协议",403);
        if(!"volunteer".equals(a.get("jobType")))throw new ServiceException("此协议仅适用于换工报名",409);
        return a;
    }
    private Map<String,Object> latest(Long id) {return db.one("select * from kg_volunteer_contract where app_id=:a order by revision desc limit 1",p("a",id));}
    private void lock(Long id) { db.must("select app_id from kg_application where app_id=:a for update",p("a",id),"报名不存在"); }
    private void writable(Map<String,Object> a) {
        if(!"store_confirmed".equals(a.get("status")))throw new ServiceException("需门店确认录用且本次报名仍在签约阶段",409);
        if(db.count("select count(*) from kg_contract_sign where app_id=:a",p("a",a.get("appId")))>0)throw new ServiceException("本次报名已签署协议，无需重复签署",409);
    }
    private Map<String,Object> publicRecord(Map<String,Object> r) {
        if(r==null)return null;
        Map<String,Object> out=new LinkedHashMap<>();
        for(String k:Arrays.asList("contractId","revision","agreementNo","version","status","contentHash","contentMasked","signerName","signedTime","merchantSigner","merchantSignedTime","rejectReason"))out.put(k,r.get(k));
        return out;
    }
    public Map<String,Object> view(Long uid,Long appId) {
        Map<String,Object> a=own(uid,appId);
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("title",VolunteerAgreement.TITLE);m.put("version",VolunteerAgreement.VERSION);m.put("body",VolunteerAgreement.BODY);
        m.put("status",a.get("status"));m.put("record",publicRecord(latest(appId)));
        boolean hired=Arrays.asList("store_confirmed","contract_signed","onboarded","completed").contains(s(a.get("status")));
        m.put("defaults",p("name",a.get("realName"),"phone",a.get("phone"),"storeName",a.get("storeName"),"storeAddress",hired?a.get("storeAddress"):"","storePhone",hired?a.get("storePhone"):""));
        m.put("history",db.list("select contract_id,agreement_no,status,signed_time,merchant_signed_time from kg_volunteer_contract where app_id=:a order by revision desc",p("a",appId)));
        return m;
    }
    public Map<String,Object> record(Long uid,Long appId,Long id) {
        own(uid,appId);
        return publicRecord(db.must("select * from kg_volunteer_contract where contract_id=:i and app_id=:a",p("i",id,"a",appId),"确认记录不存在"));
    }
    private Map<String,Object> prepared(Map<String,Object> a,Map<String,Object> b) {
        writable(a);
        Map<String,Object> previous=latest(KgJdbc.asLong(a.get("appId")));
        if(previous!=null && !"rejected".equals(previous.get("status")))throw new ServiceException("协议已提交，请查看门店核对进度",409);
        LocalDate today=LocalDate.now(VolunteerAgreement.ZONE);
        Map<String,Object> f=VolunteerAgreement.fields(b,a,today);
        // 与已经完成的实名信息核对；这里只做格式检查，不把填证件当作官方实名认证。
        Map<String,Object> verified=db.one("select id_hash,real_name,real_name_verified_time from kg_app_user where user_id=:u",p("u",a.get("appUserId")));
        if(verified!=null && verified.get("realNameVerifiedTime")!=null && ((!s(verified.get("idHash")).isEmpty() && !KgTravelIdService.hmac(s(f.get("idNo")),secret).equals(s(verified.get("idHash")))) || (!s(verified.get("realName")).isEmpty() && !KgUserHubService.sameName(s(f.get("name")),s(verified.get("realName"))))))throw new ServiceException("请使用账号实名认证的本人姓名和证件",422);
        int revision=previous==null?1:((Number)previous.get("revision")).intValue()+1;
        String no="KG-W-"+a.get("appId")+"-"+revision;
        Map<String,Object> job=VolunteerAgreement.snapshot(a);
        String full=VolunteerAgreement.render(f,job,no,today.toString(),false);
        return p("fields",f,"job",job,"revision",revision,"agreementNo",no,"day",today.toString(),"full",full,"masked",VolunteerAgreement.render(f,job,no,today.toString(),true),"hash",KgUserHubService.sha256(full));
    }
    public Map<String,Object> preview(Long uid,Long appId,Map<String,Object> b) {
        AccountState.requireActive(db,uid);
        Map<String,Object> x=prepared(own(uid,appId),b);
        return p("agreementNo",x.get("agreementNo"),"version",VolunteerAgreement.VERSION,"hash",x.get("hash"),"text",x.get("masked"));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> submit(Long uid,Long appId,Map<String,Object> b,String ip) {
        AccountState.requireActive(db,uid);lock(appId);
        Map<String,Object> a=own(uid,appId),old=latest(appId);
        if(old!=null && "pending".equals(old.get("status")) && s(old.get("contentHash")).equals(s(b.get("hash"))))return view(uid,appId);
        if(!Boolean.TRUE.equals(b.get("agree")))throw new ServiceException("请阅读完整预览并勾选同意协议",422);
        Map<String,Object> x=prepared(a,b);
        if(!s(x.get("hash")).equals(s(b.get("hash"))))throw new ServiceException("协议内容已更新，请重新预览并确认",409);
        departureNotice.requireAccepted(uid,appId);
        @SuppressWarnings("unchecked") Map<String,Object> f=(Map<String,Object>)x.get("fields");
        String id=s(f.get("idNo"));
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("app_id",appId);row.put("revision",x.get("revision"));row.put("app_user_id",uid);row.put("store_id",a.get("storeId"));
        row.put("agreement_no",x.get("agreementNo"));row.put("version",VolunteerAgreement.VERSION);row.put("status","pending");row.put("content_hash",x.get("hash"));
        row.put("data_enc",KgTravelIdService.encrypt(JSON.toJSONString(p("fields",f,"job",x.get("job"))),secret));
        row.put("content_enc",KgTravelIdService.encrypt(s(x.get("full")),secret));row.put("content_masked",x.get("masked"));
        row.put("signer_name",f.get("name"));row.put("id_last4",id.substring(14));row.put("signed_time",new Date());row.put("sign_ip",StaffSupport.cut(ip,64));
        db.insert("kg_volunteer_contract",row);
        support.notifyStore(KgJdbc.asLong(a.get("storeId")),MessageCategory.CONTRACT,"换工三方协议待核对","报名人已填写并确认，请核对店家信息及视频沟通差异后确认或退回。","application",appId,"/pages/staff/applications?appId="+appId);
        return view(uid,appId);
    }
    public StaffSupport.Ctx staffContext(Long appId) {
        Map<String,Object> a=app(appId);
        StaffSupport.Ctx c=support.ctx(KgJdbc.asLong(a.get("storeId")));
        StaffPerms.require(c.role,"jobs",StaffPerms.EDIT);
        if(c.uid().equals(KgJdbc.asLong(a.get("appUserId"))))throw new ServiceException("不能同时作为本人报名的甲乙双方确认",403);
        return c;
    }
    private Map<String,Object> staffApp(StaffSupport.Ctx c,Long id) {
        AccountState.requireActive(db,c.uid());
        Map<String,Object> a=app(id);
        StaffPerms.require(c.role,"jobs",StaffPerms.EDIT);
        if(!Objects.equals(c.storeId,KgJdbc.asLong(a.get("storeId"))) || c.uid().equals(KgJdbc.asLong(a.get("appUserId"))))throw new ServiceException("无权操作此报名协议",403);
        if(!"volunteer".equals(a.get("jobType")))throw new ServiceException("此协议仅适用于换工报名",409);
        return a;
    }
    public Map<String,Object> staffView(StaffSupport.Ctx c,Long appId) {
        Map<String,Object> a=staffApp(c,appId);
        return p("status",a.get("status"),"title",VolunteerAgreement.TITLE,"record",publicRecord(latest(appId)));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> decide(StaffSupport.Ctx c,Long appId,Map<String,Object> b,String ip) {
        lock(appId);Map<String,Object> a=staffApp(c,appId),r=latest(appId);
        if(r==null || !Objects.equals(KgJdbc.asLong(r.get("contractId")),KgJdbc.asLong(b.get("contractId"))) || !s(r.get("contentHash")).equals(s(b.get("hash"))))throw new ServiceException("协议版本已变化，请刷新后核对",409);
        String action=s(b.get("action"));
        if("confirmed".equals(r.get("status")) && "confirm".equals(action))return staffView(c,appId);
        writable(a);
        if(!"pending".equals(r.get("status")))throw new ServiceException("该版本已处理，请刷新",409);
        Date now=new Date();
        if("reject".equals(action)) {
            String reason=VolunteerAgreement.required(b,"reason","退回原因",500);
            db.update("update kg_volunteer_contract set status='rejected',merchant_user_id=:u,merchant_role=:role,merchant_signed_time=:t,merchant_ip=:ip,reject_reason=:reason where contract_id=:i and status='pending'",
                p("u",c.uid(),"role",c.role,"t",now,"ip",StaffSupport.cut(ip,64),"reason",reason,"i",r.get("contractId")));
            support.notifyUser(KgJdbc.asLong(a.get("appUserId")),MessageCategory.CONTRACT,"换工协议需修改","店家退回了协议，请查看原因后修改并重新确认。","application",appId,"/pages/jobs/contract?appId="+appId+"&view=terms");
        } else if("confirm".equals(action)) {
            if(!Boolean.TRUE.equals(b.get("agree")))throw new ServiceException("请确认已获店家授权，核对全部信息并同意协议",422);
            String signer=VolunteerAgreement.required(b,"name","乙方授权签署人姓名",32);
            // 对比预览时的岗位和行程，不能用后来改过的条件代替用户已确认的条件。
            String raw=KgTravelIdService.decrypt(s(r.get("dataEnc")),secret);
            if(raw==null)throw new ServiceException("协议数据暂时无法读取，请联系平台处理",409);
            Map<String,Object> saved=JSON.parseObject(raw);
            if(!VolunteerAgreement.snapshot(a).equals(saved.get("job")))throw new ServiceException("岗位或行程已变更，请退回用户重新核对并确认",409);
            db.update("update kg_volunteer_contract set status='confirmed',merchant_user_id=:u,merchant_role=:role,merchant_signer=:n,merchant_signed_time=:t,merchant_ip=:ip where contract_id=:i and status='pending'",
                p("u",c.uid(),"role",c.role,"n",signer,"t",now,"ip",StaffSupport.cut(ip,64),"i",r.get("contractId")));
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("app_id",appId);row.put("app_user_id",a.get("appUserId"));row.put("version",r.get("version"));row.put("content_hash",r.get("contentHash"));
            row.put("signer_name",r.get("signerName"));row.put("id_last4",r.get("idLast4"));row.put("signed_time",r.get("signedTime"));row.put("sign_ip",r.get("signIp"));
            row.put("agreement_no",r.get("agreementNo"));row.put("content_snapshot",r.get("contentMasked"));row.put("merchant_signer",signer);row.put("merchant_signed_time",now);
            db.insert("kg_contract_sign",row);
            applications.signContract(appId,OperatorDTO.user(KgJdbc.asLong(a.get("appUserId")),s(r.get("signerName"))));
        } else throw new ServiceException("处理方式不正确",422);
        support.op(c,"volunteer_contract_"+action,"换工三方协议确认","general","报名 "+appId+"，协议编号 "+r.get("agreementNo"),true,null);
        return staffView(c,appId);
    }
}
