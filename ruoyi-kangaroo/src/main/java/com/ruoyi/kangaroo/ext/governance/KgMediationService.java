package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;
import java.util.*;
import java.time.*;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.CityLeadScope;
import com.ruoyi.kangaroo.ext.user.KgPointLedger;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.common.exception.ServiceException;

/** Transactional, order-bound state machine. No command edits or deletes submitted evidence. */
@Service public class KgMediationService {
    public static final List<String> CATEGORIES=Arrays.asList("欠薪","食宿不符","工作超时","人身冲突","店家失联","食宿安全","工作内容不符","评价真实性争议","其他");
    private static final List<String> JURY=Arrays.asList("食宿不符","工作内容不符","工作超时","评价真实性争议");
    private static final List<String> ASSISTANCE_STATES=Arrays.asList("pending","accepted","verifying","ready","jury","confirmation","appealed");
    private static final List<String> CHOICES=Arrays.asList("user","merchant","both","insufficient");
    @Autowired private KgJdbc db;
    @Autowired private KgMediationOrders orders;
    @Autowired private IKgStaffGrantService grants;
    @Autowired private CityLeadScope cityScope;
    @Autowired private KgPointLedger ledger;
    @Autowired private IKgMessageService messages;
    @Autowired private KgBadgeService badges;
    @Autowired private KgBadgeModeration moderation;

    public String role(Long uid,Map<String,Object> c,boolean hq){
        if(hq)return "hq";
        if(uid.equals(id(c.get("userId"))))return "user";
        if(orders.merchant(uid,id(c.get("storeId"))))return "merchant";
        if(cityScope.covers(uid,id(c.get("storeId")))&&!relatedToStore(uid,id(c.get("storeId"))))return "city";
        if("application".equals(c.get("orderType"))&&supportStores(uid).contains(id(c.get("storeId"))))return "support";
        denied();return "";
    }
    /** 换工主理人仅跟进当前有效授权门店的报名纠纷，不因此取得当事人或裁决权限。 */
    private Set<Long> supportStores(Long uid){
        Set<Long> stores=new LinkedHashSet<>();
        if(uid==null||grants==null)return stores;
        for(StaffGrantView g:grants.loadActiveGrantViews(uid))
            if(Boolean.TRUE.equals(g.getActive())&&"manager".equals(g.getRoleCode())&&g.getStoreId()!=null)stores.add(g.getStoreId());
        return stores;
    }
    private boolean relatedToStore(Long uid,Long store){return db.count("select count(*) from kg_staff_grant where app_user_id=:u and store_id=:s and role_code<>'city_lead' and status='0' and del_flag='0'",p("u",uid,"s",store))>0;}
    private Map<String,Object> get(Long cid,boolean lock){return must(db,"select * from kg_mediation_case where case_id=:c"+(lock?" for update":""),p("c",cid),"调解工单不存在",404);}
    public Map<String,Object> context(Long uid){Map<String,Object> me=db.one("select phone from kg_app_user where user_id=:u",p("u",uid));return p("categories",CATEGORIES,"canCity",!cityScope.stores(uid).isEmpty(),"canSupport",!supportStores(uid).isEmpty(),"canMerchant",!merchantStores(uid).isEmpty(),"merchantStores",merchantStores(uid),"juror",juror(uid),"phone",me==null?"":me.get("phone"),"formRules",p("narrativeMin",50,"narrativeMax",10000,"claimsMin",2,"claimsMax",2000,"amountMaxFen",100000000));}
    private List<Map<String,Object>> merchantStores(Long uid){return db.list("select s.store_id,s.store_name from kg_store s join kg_staff_grant g on g.store_id=s.store_id where g.app_user_id=:u and g.role_code='merchant' and g.status='0' and g.del_flag='0' and s.status='0' and s.del_flag='0'",p("u",uid));}
    public Map<String,Object> list(Long uid,String mode,String state,int page,boolean hq){
        Map<String,Object> ps=p("u",uid);String scope="(c.user_id=:u or c.store_id in(select store_id from kg_staff_grant where app_user_id=:u and role_code='merchant' and status='0' and del_flag='0'))";
        if(hq)scope="1=1";
        else if("city".equals(mode)){List<Long> stores=new ArrayList<>();for(Long s:cityScope.stores(uid).keySet())if(!relatedToStore(uid,s))stores.add(s);if(stores.isEmpty())return p("rows",Collections.emptyList(),"total",0);ps.put("stores",stores);scope="c.store_id in(:stores) and c.user_id<>:u";}
        else if("support".equals(mode)){Set<Long> stores=supportStores(uid);if(stores.isEmpty())return p("rows",Collections.emptyList(),"total",0);ps.put("stores",stores);scope="c.order_type='application' and c.store_id in(:stores) and c.user_id<>:u";}
        if(!str(state).isEmpty()){scope+=" and c.status=:state";ps.put("state",state);}
        return db.page("select c.case_id,c.case_no,c.order_type,c.category,c.city,c.status,c.urgent,c.accept_deadline,c.create_time,c.update_time,c.handler_id,s.store_name from kg_mediation_case c join kg_store s on s.store_id=c.store_id where "+scope+" order by c.urgent desc,c.update_time desc,c.case_id desc",ps,page,30);
    }
    @Transactional(rollbackFor=Exception.class) public Long create(Long uid,Map<String,Object>b){
        String key=text(b,"requestKey",8,64),type=text(b,"orderType",1,24),category=text(b,"category",1,40),narrative=text(b,"narrative",50,10000),claims=text(b,"claims",2,2000),phone=text(b,"phone",7,24);
        require(phone.matches("[+0-9 -]{7,24}"),"请填写有效联系电话");require(CATEGORIES.contains(category),"请选择纠纷类型");
        must(db,"select user_id from kg_app_user where user_id=:u and status='0' and del_flag='0' for update",p("u",uid),"账号不可用",403);
        Map<String,Object> old=db.one("select case_id from kg_mediation_case where initiator_id=:u and request_key=:k for update",p("u",uid,"k",key));if(old!=null)return id(old.get("caseId"));
        orders.lock(type,id(b.get("orderId")));Map<String,Object> o=orders.order(type,id(b.get("orderId")));
        Long store=id(o.get("storeId")),user=id(o.get("userId"));String as=uid.equals(user)?"user":orders.merchant(uid,store)?"merchant":"";if(as.isEmpty())denied();
        // 这笔订单的调解已出结果（双方接受裁决 / 总部终局复核后关闭）：双方都不能再发起；撤回的、没出结果就关闭的仍可以（2026-11-09 主人拍板）
        require(!orders.settled(type,id(o.get("orderId"))),KgMediationOrders.SETTLED);
        require(orders.eligible(o),"该订单未进入可调解阶段，或已超过关闭后15天");
        require(!str(o.get("city")).isEmpty(),"门店尚未设置城市，请联系平台补充");
        require(db.count("select count(*) from kg_mediation_case where order_type=:t and order_id=:o and status<>'closed' for update",p("t",type,"o",o.get("orderId")))==0,"该订单已有进行中的调解，请在原工单补充材料");
        LocalDateTime incident=time(b.get("incidentAt"));require(!incident.isAfter(now().plusMinutes(5)),"发生时间不能晚于当前时间");long amount=num(b.get("amountFen"));require(amount>=0&&amount<=100000000,"涉及金额请填写 0～1000000 元");
        List<Long> files=ids(b.get("fileIds"));require(files.size()>=1&&files.size()<=9,"请上传1至9项证据");
        boolean urgent=Boolean.TRUE.equals(b.get("safety"))||"店家失联".equals(category);
        long cid=db.insert("kg_mediation_case",p("request_key",key,"order_type",type,"order_id",o.get("orderId"),"order_no",o.get("orderNo"),"user_id",user,"store_id",store,"city",o.get("city"),"initiator_id",uid,"initiator_role",as,"category",category,"narrative",narrative,"claims",claims,"incident_at",incident,"urgent",urgent?"1":"0","amount_fen",amount,"phone",phone,"accept_deadline",now().plusHours(urgent?2:24)));
        String cityCode=Integer.toUnsignedString(str(o.get("city")).hashCode(),36).toUpperCase(Locale.ROOT);
        db.update("update kg_mediation_case set case_no=:n where case_id=:c",p("c",cid,"n","JF-"+cityCode+"-"+LocalDate.now(ZONE).toString().replace("-","")+"-"+cid));
        attach(uid,cid,files,false);Map<String,Object> c=get(cid,true);log(c,"app:"+uid,as,"submit","pending","提交调解","parties");
        if(!uid.equals(user))notify(c,user,"user","submitted_party","门店已发起纠纷调解，请补充材料");
        for(Long merchant:orders.merchants(store))if(!merchant.equals(uid))notify(c,merchant,"staff","submitted_party","收到纠纷调解，请补充说明和材料","merchant");
        if("merchant".equals(as))notifySupport(c,"merchant_submit","店家已提交调解材料，可接单跟进");
        for(Long lead:cityScope.leadsOf(store))if(!lead.equals(user)&&!relatedToStore(lead,store))notify(c,lead,"staff","submitted",urgent?"紧急调解，请2小时内接单":"新调解工单，请24小时内接单");
        // Headquarters queue is always visible even when no city lead has been assigned.
        event(c,"hq:new","headquarters",urgent?"紧急调解，需要监督响应":"新工单待城市负责人接单","pending");
        if(urgent)event(c,"urgent:phone","sms_phone","紧急工单需短信/电话联络城市负责人和督导","manual_required");
        return cid;
    }
    private void attach(Long uid,Long cid,List<Long> ids,boolean redacted){
        for(Long fid:ids){Map<String,Object> f=must(db,"select * from kg_mediation_file where file_id=:f and owner_actor=:a and case_id is null for update",p("f",fid,"a","app:"+uid),"附件不存在、已使用或不属于当前账号",404);require(("1".equals(f.get("redacted")))==redacted,"附件类别不匹配");db.update("update kg_mediation_file set case_id=:c where file_id=:f",p("c",cid,"f",fid));}
    }
    public Map<String,Object> detail(Long uid,Long cid,boolean hq){
        Map<String,Object> c=get(cid,false);String as=role(uid,c,hq);boolean staff="city".equals(as)||hq;c.put("viewerRole",as);c.put("canContribute",!"support".equals(as));c.put("canHandle","city".equals(as)&&uid.equals(KgJdbc.asLong(c.get("handlerId"))));
        Map<String,Object> material=merchantMaterial(c),claim=supportClaim(c);Long owner=claim==null?null:id(claim.get("content"));
        boolean open=ASSISTANCE_STATES.contains(str(c.get("status"))),support="support".equals(as);
        c.put("merchantMaterialsReady",material!=null);c.put("merchantMaterialsAt",material==null?null:material.get("createTime"));
        c.put("canMerchantRespond","merchant".equals(as)&&open);c.put("canSupportAccept",support&&open&&material!=null&&owner==null);
        c.put("supportClaimed",owner!=null);c.put("supportMine",uid.equals(owner));c.put("supportAcceptedAt",claim==null?null:claim.get("createTime"));
        c.put("canAssist",support&&open&&(owner==null||uid.equals(owner)));
        if(support&&uid.equals(owner))c.put("merchantResponses",sharedMerchantResponses(c));
        // Parties see published decisions/progress, never the other party's phone, private statement or raw evidence.
        // requestKey 是发起人提交时的防重编号，只给发起人本人和总部看
        if(!hq&&!uid.equals(KgJdbc.asLong(c.get("initiatorId"))))c.remove("requestKey");
        if(!staff){c.remove("phone");c.remove("verification");c.remove("appeal");if(!uid.equals(id(c.get("initiatorId")))){c.put("narrative","对方已提交事件材料，城市负责人核实后会同步可公开的内容。");c.put("claims","以核实记录与裁决结果为准");}c.remove("userId");c.remove("initiatorId");c.remove("handlerId");}
        c.put("evidence",db.list("select file_id,original_name,mime_type,size_bytes,redacted,create_time from kg_mediation_file where case_id=:c"+(staff?"":" and (owner_actor=:a or redacted='1')")+" order by file_id",p("c",cid,"a","app:"+uid)));
        List<Map<String,Object>> logs=db.list("select log_id,"+(staff?"actor,role_code,":"")+"action,from_state,to_state,note,create_time from kg_mediation_log where case_id=:c"+(staff?"":" and (audience='parties' or (audience='private' and actor=:a))")+" order by log_id",p("c",cid,"a","app:"+uid));
        c.put("log",logs);
        Map<String,Object> round=db.one("select round_id,status,deadline,result_json,anonymous_narrative,anonymous_claims,core_priority from kg_mediation_round where case_id=:c",p("c",cid));if(round!=null&&!staff&&round.get("resultJson")!=null){Map<String,Object> safe=JSON.parseObject(str(round.get("resultJson")));safe.remove("reasons");round.put("resultJson",JSON.toJSONString(safe));}c.put("jury",round);
        if(staff){
            c.put("events",db.list("select * from kg_mediation_event where case_id=:c and audience<>'internal' order by event_id",p("c",cid)));
            if(round!=null){
                List<Map<String,Object>> votes=db.list("select vote_id,"+(hq?"app_user_id,":"")+"juror_kind,choice,reason,valid,quality,assessment_note,assessment_kind,city_adopted,adopted_at from kg_mediation_vote where round_id=:r",p("r",round.get("roundId")));
                if(!hq)anonymizeOpinions(round,votes,logs);
                c.put("votes",votes);
            }
        }
        return c;
    }
    /** Keep juror identities out of the city lead's opinion summary, including older rounds. */
    private void anonymizeOpinions(Map<String,Object> round,List<Map<String,Object>> votes,List<Map<String,Object>> logs){
        List<Map<String,Object>> profiles=db.list("select u.real_name,u.nickname,u.phone,s.store_name,s.contact_phone "
                +"from kg_mediation_vote v join kg_app_user u on u.user_id=v.app_user_id "
                +"left join kg_staff_grant g on g.app_user_id=u.user_id "
                +"left join kg_store s on s.store_id=g.store_id where v.round_id=:r",p("r",round.get("roundId")));
        Set<String> names=new TreeSet<>(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        for(Map<String,Object> profile:profiles)for(Object value:profile.values())if(str(value).length()>=2)names.add(str(value));
        List<Map<String,Object>> reasons=new ArrayList<>();
        for(Map<String,Object> vote:votes){
            for(String field:Arrays.asList("reason","assessmentNote")){
                vote.put(field,anonymousOpinion(str(vote.get(field)),names));
            }
            if("1".equals(vote.get("valid"))&&!str(vote.get("reason")).isEmpty())
                reasons.add(p("choice",vote.get("choice"),"reason",vote.get("reason")));
        }
        for(Map<String,Object> log:logs)if("jury_assessment".equals(log.get("action"))){
            log.put("note",anonymousOpinion(str(log.get("note")),names));
        }
        if(round.get("resultJson")!=null){
            Map<String,Object> result=JSON.parseObject(str(round.get("resultJson")));
            result.put("reasons",reasons);
            round.put("resultJson",JSON.toJSONString(result));
        }
    }
    private String anonymousOpinion(String text,Set<String> names){
        String value=com.ruoyi.kangaroo.util.MaskUtils.contacts(text)
                .replaceAll("(?i)[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}","[邮箱已隐藏]")
                .replaceAll("(?i)(?:https?://|www\\.)[^\\s\\u4e00-\\u9fff，。；！？、<>]+","[链接已隐藏]");
        for(String name:names)value=value.replace(name,"[身份信息已隐藏]");
        return value;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED) public void command(Long uid,Long cid,String action,Map<String,Object>b,boolean hq,String adminActor){
        Map<String,Object> c=get(cid,true);String as=role(uid,c,hq),state=str(c.get("status")),actor=hq?adminActor:"app:"+uid;
        if("merchantResponse".equals(action)){merchantResponse(uid,c,b,as);return;}
        if("supportAccept".equals(action)){
            if(!"support".equals(as))throw new ServiceException("仅负责本店报名的换工主理人可接单跟进，当事人不能接单",403);
            require(ASSISTANCE_STATES.contains(state),"当前工单已关闭或不可接单跟进");require(merchantMaterial(c)!=null,"请先等待店家补充材料");
            Map<String,Object> claim=supportClaim(c);if(claim!=null){require(uid.equals(id(claim.get("content"))),"已有其他换工主理人接单跟进");return;}
            db.update("insert into kg_mediation_event(case_id,event_key,audience,content,status) values(:c,:k,'internal',:u,'done') on duplicate key update content=:u,create_time=now(),status='done'",p("c",cid,"k",cid+":support_claim","u",String.valueOf(uid)));
            log(c,actor,as,"support_accept",state,"换工主理人已接单跟进，协助核实材料，独立裁决仍由指定负责人完成","parties");
            db.update("update kg_mediation_case set update_time=now() where case_id=:c",p("c",cid));
            notifyParties(c,"support_accepted:"+uid,"换工主理人已接单跟进调解");return;
        }
        if("assistance".equals(action)){
            if(!"support".equals(as))throw new ServiceException("仅负责本店报名的换工主理人可提交协助意见，当事人请使用补充陈述或材料",403);
            String requestKey=text(b,"requestKey",8,64),note=text(b,"note",10,5000);
            require(b.get("fileIds")==null||b.get("fileIds") instanceof List,"附件列表格式不正确");
            List<Long> files=ids(b.get("fileIds"));Collections.sort(files);
            String receiptKey=cid+":assistance_request:"+uid+":"+sha256(requestKey);
            String payloadHash=sha256(JSON.toJSONString(Arrays.asList(note,files)));
            Map<String,Object> receipt=db.one("select content from kg_mediation_event where case_id=:c and event_key=:k and audience='internal' and status='done'",p("c",cid,"k",receiptKey));
            if(receipt!=null&&receipt.get("content")!=null){
                require(payloadHash.equals(receipt.get("content")),"这次提交编号已用于其他内容，请重新提交新的协助意见");
                return;
            }
            require(ASSISTANCE_STATES.contains(state),"当前工单已关闭或不可补充协助意见");
            Map<String,Object> claim=supportClaim(c);require(claim==null||uid.equals(id(claim.get("content"))),"请由已接单的换工主理人补充协助意见");
            attachAssistance(uid,cid,files);
            long logId=db.insert("kg_mediation_log",p("case_id",cid,"actor",actor,"role_code",as,"action","assistance","from_state",state,"to_state",state,"note",note,"audience","private"));
            log(c,actor,as,"assistance_received",state,"换工主理人已提交协助意见"+(files.isEmpty()?"":"及 "+files.size()+" 项材料")+"，待负责人核实","parties");
            db.update("update kg_mediation_case set update_time=now() where case_id=:c",p("c",cid));
            notifyAssistance(c,logId);
            // 同一事务提交回执；只保存摘要，重试不重复附件、审计和通知，也不暴露 requestKey。
            db.insert("kg_mediation_event",p("case_id",cid,"event_key",receiptKey,"audience","internal","content",payloadHash,"status","done"));
            return;
        }
        if("support".equals(as))throw new ServiceException("请通过参与调解提交协助意见，裁决和处理操作由当事人或指定负责人完成",403);
        if("evidence".equals(action)){
            require(!"closed".equals(state),"已关闭工单不能补充材料");require(!hq,"总部不代当事人修改证据");List<Long> files=ids(b.get("fileIds"));require(!files.isEmpty(),"请选择附件");
            require(files.size()+db.count("select count(*) from kg_mediation_file where case_id=:c and owner_actor=:a and redacted='0'",p("c",cid,"a",actor))<=9,"每方最多9项证据");attach(uid,cid,files,false);log(c,actor,as,"evidence",state,"补充证据 "+files.size()+" 项","parties");if("merchant".equals(as))notifySupport(c,"merchant_evidence:"+Collections.max(files),"店家已补充调解材料，请接单或继续跟进");return;
        }
        if("statement".equals(action)){require(Arrays.asList("pending","accepted","verifying","ready","appealed").contains(state),"当前阶段不能补充陈述");log(c,actor,as,"statement",state,text(b,"note",10,5000),hq||"city".equals(as)?"staff":"private");if("merchant".equals(as))notifySupport(c,"merchant_statement:"+UUID.randomUUID(),"店家已补充调解说明，请接单或继续跟进");return;}
        if("accept".equals(action)){
            require("city".equals(as)&&"pending".equals(state),"只有本城负责人可以接待接单工单");
            // 总部已转交给别人（handler_id 已定）：其他负责人不能再接，否则会把转交覆盖掉
            Long handler=KgJdbc.asLong(c.get("handlerId"));if(handler!=null&&!uid.equals(handler))throw new ServiceException("该工单已转交给其他城市负责人",409);
            db.update("update kg_mediation_case set handler_id=:u,accepted_at=now() where case_id=:c",p("c",cid,"u",uid));resolveEvents(c,"hq:new","overdue","hq:assistance");transition(c,actor,as,action,"accepted","城市负责人已接单");notifyParties(c,"accepted","调解已接单，正在核实");return;
        }
        if("verify".equals(action)){
            assigned(uid,c,as);require(Arrays.asList("accepted","verifying","ready").contains(state),"当前状态不能核实");
            if("accepted".equals(state))transition(c,actor,as,"verifying","verifying","正在核实双方陈述与材料");String record=JSON.toJSONString(p("userStatement",text(b,"userStatement",10,5000),"merchantStatement",text(b,"merchantStatement",10,5000),"evidenceList",text(b,"evidenceList",2,5000),"finding",text(b,"finding",10,5000)));
            db.update("update kg_mediation_case set verification=:v where case_id=:c",p("c",cid,"v",record));log(c,actor,as,"verify",state,record,"staff");transition(c,actor,as,"verified","ready","已完成双方陈述与证据核实");return;
        }
        if("jury".equals(action)){assigned(uid,c,as);startJury(uid,c,b);return;}
        if("decision".equals(action)){
            assigned(uid,c,as);require("ready".equals(state),"请先完成核实或等待评审结束");
            String decision=text(b,"decision",10,5000),basis=text(b,"basis",10,5000),measures=text(b,"measures",5,5000),impact=text(b,"creditImpact",2,1000);LocalDateTime deadline=time(b.get("executeDeadline"));require(deadline.isAfter(now()),"执行时限应晚于当前时间");
            boolean hasJury=db.count("select count(*) from kg_mediation_round where case_id=:c",p("c",cid))>0;String jr=hasJury?text(b,"juryReason",5,2000):"";if(hasJury)require(b.get("juryAdopted") instanceof Boolean,"请说明是否采纳评审意见");
            if(b.containsKey("adoptedVoteIds")){require(hasJury,"直接裁决没有评审意见可登记");recordAdoption(c,b,actor,as,jr);}
            db.update("update kg_mediation_case set decision=:d,basis=:b,measures=:m,credit_impact=:i,execute_deadline=:e,confirm_deadline=date_add(now(),interval 7 day),jury_adopted=:a,jury_reason=:j,path=:p where case_id=:c",p("c",cid,"d",decision,"b",basis,"m",measures,"i",impact,"e",deadline,"a",Boolean.TRUE.equals(b.get("juryAdopted"))?"1":"0","j",jr,"p",hasJury?"jury":"direct"));
            transition(c,actor,as,action,"confirmation","裁决已发布，请双方7天内确认");notifyParties(c,"decision","调解已裁决，请查看并确认");return;
        }
        if("adoption".equals(action)){
            require(hq||("city".equals(as)&&uid.equals(KgJdbc.asLong(c.get("handlerId")))),"仅处理负责人或总部可核定评审意见采纳情况");
            require(Arrays.asList("confirmation","appealed","closed").contains(state),"裁决发布后可补录采纳情况");
            require(b.containsKey("adoptedVoteIds"),"请逐条核定本轮评审意见");
            recordAdoption(c,b,actor,as,text(b,"note",5,500));return;
        }
        if("confirm".equals(action)){
            require(Arrays.asList("user","merchant").contains(as)&&"confirmation".equals(state),"当前不能确认裁决");require(b.get("accept") instanceof Boolean,"请选择接受或申诉");boolean accept=Boolean.TRUE.equals(b.get("accept"));
            String field="user".equals(as)?"user_confirm":"merchant_confirm";require(c.get("user".equals(as)?"userConfirm":"merchantConfirm")==null,"你已提交确认");
            db.update("update kg_mediation_case set "+field+"=:v where case_id=:c",p("c",cid,"v",accept?"accepted":"rejected"));
            if(!accept){String appeal=text(b,"note",10,5000);db.update("update kg_mediation_case set appeal=:a where case_id=:c",p("a",appeal,"c",cid));resolveEvents(c,"confirmation_due");transition(c,actor,as,"appeal","appealed","当事人不接受裁决，已提交总部复核");notifyParties(c,"appealed","调解已进入总部复核");log(c,actor,as,"appeal_reason","appealed",appeal,"staff");event(c,"hq:appeal","headquarters","二次申诉待总部复核","pending");}
            else {Map<String,Object> updated=get(cid,true);if("accepted".equals(updated.get("userConfirm"))&&"accepted".equals(updated.get("merchantConfirm")))close(c,actor,as,"双方接受裁决，调解已结束");else log(c,actor,as,"confirm",state,"一方已接受，等待另一方确认","parties");}return;
        }
        if("reassign".equals(action)){
            require(hq&&!"closed".equals(state),"仅总部可转交未关闭的工单");
            if(num(b.get("handlerId"))<=0)throw new ServiceException("请填写要转交的城市负责人的小程序账号 ID",422);Long next=id(b.get("handlerId"));String note=text(b,"note",10,500);
            // role() 对无关账号抛「无权访问此记录」，转交时换成能看懂的原因
            String nextRole;try{nextRole=role(next,c,false);}catch(ServiceException e){nextRole="";}
            if(!"city".equals(nextRole))throw new ServiceException("只能转交给本城、和本单无利益关联的城市负责人",409);
            db.update("update kg_mediation_case set handler_id=:u where case_id=:c",p("u",next,"c",cid));
            log(c,actor,as,"reassign",state,"转交负责人 "+next+"："+note,"staff");notify(c,next,"staff","assigned:"+UUID.randomUUID(),"调解工单已转交，请及时处理");return;
        }
        if("contact".equals(action)){
            require(hq||("city".equals(as)&&uid.equals(KgJdbc.asLong(c.get("handlerId")))),"仅处理负责人或总部可登记联络");
            log(c,actor,as,"contact",state,text(b,"note",10,1000),"staff");resolveEvents(c,"urgent:phone");return;
        }
        if("hqReview".equals(action)){
            require(hq&&"appealed".equals(state),"总部仅处理二次申诉");String result=text(b,"result",20,10000);db.update("update kg_mediation_case set hq_result=:r where case_id=:c",p("c",cid,"r",result));close(c,actor,as,"总部复核已完成，请查看终局结果");event(c,"hq:reviewed","headquarters","总部复核已完成","done");return;
        }
        if("execution".equals(action)){require((hq||("city".equals(as)&&uid.equals(KgJdbc.asLong(c.get("handlerId")))))&&Arrays.asList("confirmation","appealed","closed").contains(state),"仅处理负责人可登记执行结果");log(c,actor,as,"execution",state,text(b,"note",10,5000),"parties");return;}
        throw new ServiceException("不支持的操作",422);
    }
    private void assigned(Long uid,Map<String,Object>c,String as){require("city".equals(as)&&uid.equals(KgJdbc.asLong(c.get("handlerId"))),"仅接单的城市负责人可以处理");}
    private void recordAdoption(Map<String,Object> c,Map<String,Object> body,String actor,String role,String note){
        require(body.get("adoptedVoteIds") instanceof List,"请逐条核定本轮评审意见");
        List<Long> selected=ids(body.get("adoptedVoteIds"));
        Map<String,Object> round=must(db,"select round_id,status from kg_mediation_round where case_id=:c for update",p("c",c.get("caseId")),"该工单没有大众评审",409);
        require("closed".equals(round.get("status")),"评审结束后才能核定采纳情况");
        List<Map<String,Object>> votes=db.list("select vote_id,app_user_id,choice,valid,city_adopted from kg_mediation_vote where round_id=:r order by vote_id for update",p("r",round.get("roundId")));
        Set<Long> valid=new HashSet<>();
        for(Map<String,Object> vote:votes)if(vote.get("choice")!=null&&"1".equals(vote.get("valid")))valid.add(id(vote.get("voteId")));
        require(valid.containsAll(selected),"仅可采纳本轮已经提交的有效评审意见");
        List<Long> changed=new ArrayList<>();
        for(Map<String,Object> vote:votes){
            Long voteId=id(vote.get("voteId"));
            if(!valid.contains(voteId))continue;
            String adopted=selected.contains(voteId)?"1":"0";
            if(adopted.equals(vote.get("cityAdopted")))continue;
            db.update("update kg_mediation_vote set city_adopted=:a,adopted_by=:by,adopted_at=now() where vote_id=:v",p("v",voteId,"a",adopted,"by",actor));
            Long juror=id(vote.get("appUserId"));if(!badges.goldJurorEligible(juror))revokeJurorBadges(juror,false);
            changed.add(voteId);
        }
        if(!changed.isEmpty())log(c,actor,role,"jury_adoption",str(c.get("status")),"本轮采纳意见编号 "+selected+"。核定依据 "+note,"staff");
    }
    private void transition(Map<String,Object>c,String actor,String as,String action,String to,String note){db.update("update kg_mediation_case set status=:s where case_id=:c",p("s",to,"c",c.get("caseId")));log(c,actor,as,action,to,note,"parties");c.put("status",to);}
    private void resolveEvents(Map<String,Object>c,String... keys){for(String k:keys)db.update("update kg_mediation_event set status='done' where event_key=:k",p("k",c.get("caseId")+":"+k));}
    private void close(Map<String,Object>c,String actor,String as,String note){resolveEvents(c,"hq:new","overdue","urgent:phone","hq:appeal","confirmation_due","hq:assistance");db.update("update kg_mediation_case set closed_at=now() where case_id=:c",p("c",c.get("caseId")));transition(c,actor,as,"close","closed",note);notifyParties(c,"closed","调解已结束，结果已同步");}
    private void log(Map<String,Object>c,String actor,String as,String action,String to,String note,String audience){db.insert("kg_mediation_log",p("case_id",c.get("caseId"),"actor",actor,"role_code",as,"action",action,"from_state",c.get("status"),"to_state",to,"note",note,"audience",audience));}
    private void event(Map<String,Object>c,String key,String audience,String content,String status){db.update("insert ignore into kg_mediation_event(case_id,event_key,audience,content,status) values(:c,:k,:a,:n,:s)",p("c",c.get("caseId"),"k",c.get("caseId")+":"+key,"a",audience,"n",content,"s",status));}
    private void notify(Map<String,Object> c,Long uid,String mode,String event,String title){notify(c,uid,mode,event,title,"staff".equals(mode)?"city":"user");}
    private void notify(Map<String,Object> c,Long uid,String mode,String event,String title,String view){String key=c.get("caseId")+":"+event+":"+uid;if(db.update("insert ignore into kg_mediation_event(case_id,event_key,audience,content,status) values(:c,:k,:a,:n,'in_app')",p("c",c.get("caseId"),"k",key,"a",mode+":"+uid,"n",title))==1)messages.send(uid,mode,"system",title,str(c.get("caseNo"))+"，请打开工单查看。","mediation",id(c.get("caseId")),"/pages/governance/mediation?id="+c.get("caseId")+("user".equals(view)?"":"&mode="+view));}
    /** A new opinion may cite this author's existing case files, but never another case's materials. */
    private void attachAssistance(Long uid,Long cid,List<Long> files){
        List<Long> unbound=new ArrayList<>();String actor="app:"+uid;
        for(Long fid:files){
            Map<String,Object> file=must(db,"select case_id,redacted from kg_mediation_file where file_id=:f and owner_actor=:a and (case_id is null or case_id=:c) for update",p("f",fid,"a",actor,"c",cid),"附件不存在、属于其他工单或不属于当前账号",404);
            require("0".equals(file.get("redacted")),"协助附件应为本人上传的原始材料");
            if(file.get("caseId")==null)unbound.add(fid);
        }
        require(unbound.size()+db.count("select count(*) from kg_mediation_file where case_id=:c and owner_actor=:a and redacted='0'",p("c",cid,"a",actor))<=9,"每人累计最多9项材料附件");
        for(Long fid:unbound)db.update("update kg_mediation_file set case_id=:c where file_id=:f",p("c",cid,"f",fid));
    }
    private static String sha256(String value){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format("%02x",b));return result.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** Only the current independent handler (or available city leads before assignment) is notified. */
    private void notifyAssistance(Map<String,Object> c,long logId){
        Long assigned=KgJdbc.asLong(c.get("handlerId"));
        List<Long> recipients=assigned==null?cityScope.leadsOf(id(c.get("storeId"))):Collections.singletonList(assigned);
        boolean sent=false;
        for(Long recipient:new LinkedHashSet<>(recipients)){
            try{if(!"city".equals(role(recipient,c,false)))continue;}catch(ServiceException ignored){continue;}
            notify(c,recipient,"staff","assistance:"+logId,"换工主理人已补充调解材料，请查看");sent=true;
        }
        // 没有有效处理人时交总部待办，不把材料正文写进通知或公开进度。
        if(!sent)db.update("insert into kg_mediation_event(case_id,event_key,audience,content,status) values(:c,:k,'headquarters','主理人已补充材料，请安排负责人核实','pending') "
                +"on duplicate key update status='pending',content=values(content)",p("c",c.get("caseId"),"k",c.get("caseId")+":hq:assistance"));
    }
    private void notifyParties(Map<String,Object> c,String event,String title){notify(c,id(c.get("userId")),"user",event,title);for(Long m:orders.merchants(id(c.get("storeId"))))if(!m.equals(id(c.get("userId"))))notify(c,m,"staff",event,title,"merchant");}

    private Map<String,Object> merchantMaterial(Map<String,Object> c){
        // Existing merchant-originated cases and historical supplements already contain a response.
        return db.one("select create_time from kg_mediation_log where case_id=:c and role_code='merchant' and action in ('submit','evidence','statement','merchant_response') order by log_id desc limit 1",p("c",c.get("caseId")));
    }
    private Map<String,Object> supportClaim(Map<String,Object> c){
        Map<String,Object> claim=db.one("select content,create_time from kg_mediation_event where case_id=:c and event_key=:k and audience='internal' and status='done'",p("c",c.get("caseId"),"k",c.get("caseId")+":support_claim"));
        if(claim==null)return null;
        try{Long owner=id(claim.get("content"));if(db.count("select count(*) from kg_app_user where user_id=:u and status='0' and del_flag='0'",p("u",owner))==0||!"support".equals(role(owner,c,false)))return null;}catch(RuntimeException revoked){return null;}
        return claim;
    }
    private void merchantResponse(Long uid,Map<String,Object> c,Map<String,Object> b,String as){
        if(!"merchant".equals(as))throw new ServiceException("仅本店商家可提交店家材料",403);
        Long cid=id(c.get("caseId"));String key=text(b,"requestKey",8,64),note=text(b,"note",0,5000);
        require(b.get("fileIds")==null||b.get("fileIds") instanceof List,"附件列表格式不正确");List<Long> files=ids(b.get("fileIds"));Collections.sort(files);
        String receipt=cid+":merchant_response:"+uid+":"+sha256(key),hash=sha256(JSON.toJSONString(Arrays.asList(note,files)));
        Map<String,Object> prior=db.one("select content from kg_mediation_event where case_id=:c and event_key=:k and audience='internal' and status='done'",p("c",cid,"k",receipt));
        if(prior!=null){require(hash.equals(prior.get("content")),"提交编号已用于其他材料，请重新提交");return;}
        require(ASSISTANCE_STATES.contains(str(c.get("status"))),"当前工单已关闭或不可补充材料");require(note.length()>=10||!files.isEmpty(),"请填写至少10字说明或上传至少1项材料");
        attachAssistance(uid,cid,files);
        long logId=db.insert("kg_mediation_log",p("case_id",cid,"actor","app:"+uid,"role_code","merchant","action","merchant_response","from_state",c.get("status"),"to_state",c.get("status"),"note",note.isEmpty()?"店家提交了附件材料":note,"audience","private"));
        db.insert("kg_mediation_event",p("case_id",cid,"event_key",cid+":merchant_materials:"+logId,"audience","internal","content",JSON.toJSONString(files),"status","done"));
        log(c,"app:"+uid,"merchant","merchant_response_received",str(c.get("status")),"店家已补充材料，换工主理人可接单跟进","parties");
        db.update("update kg_mediation_case set update_time=now() where case_id=:c",p("c",cid));
        notifySupport(c,"merchant_response:"+logId,"店家已补充调解材料，请接单或继续跟进");
        db.insert("kg_mediation_event",p("case_id",cid,"event_key",receipt,"audience","internal","content",hash,"status","done"));
    }
    private void notifySupport(Map<String,Object> c,String event,String title){
        if(!"application".equals(c.get("orderType")))return;
        Map<String,Object> claim=supportClaim(c);Long owner=claim==null?null:id(claim.get("content"));
        for(Map<String,Object> row:db.list("select distinct g.app_user_id from kg_staff_grant g join kg_app_user u on u.user_id=g.app_user_id join kg_store s on s.store_id=g.store_id where g.store_id=:s and g.role_code='manager' and g.status='0' and g.del_flag='0' and u.status='0' and u.del_flag='0' and s.status='0' and s.del_flag='0'",p("s",c.get("storeId")))){
            Long recipient=id(row.get("appUserId"));if(owner!=null&&!owner.equals(recipient))continue;
            try{if(!"support".equals(role(recipient,c,false)))continue;}catch(ServiceException denied){continue;}
            notify(c,recipient,"staff",event,title,"support");
        }
    }
    /** Only files explicitly supplied with this response are shared with the assigned support worker. */
    private List<Map<String,Object>> sharedMerchantResponses(Map<String,Object> c){
        List<Map<String,Object>> rows=db.list("select l.log_id,l.note,l.create_time,e.content from kg_mediation_log l join kg_mediation_event e on e.case_id=l.case_id and e.event_key=concat(l.case_id,':merchant_materials:',l.log_id) and e.audience='internal' where l.case_id=:c and l.role_code='merchant' and l.action='merchant_response' order by l.log_id",p("c",c.get("caseId")));
        for(Map<String,Object> row:rows){List<Long> files=JSON.parseArray(str(row.remove("content")),Long.class);row.put("evidence",files.isEmpty()?Collections.emptyList():db.list("select file_id,original_name,mime_type,size_bytes,redacted,create_time from kg_mediation_file where case_id=:c and file_id in(:ids) order by file_id",p("c",c.get("caseId"),"ids",files)));}
        return rows;
    }
    private boolean sharedMerchantFile(Long uid,Map<String,Object> c,Map<String,Object> file){
        Map<String,Object> claim=supportClaim(c);if(claim==null||!uid.equals(id(claim.get("content")))||file.get("fileId")==null)return false;
        for(Map<String,Object> row:sharedMerchantResponses(c))for(Map<String,Object> evidence:(List<Map<String,Object>>)row.get("evidence"))if(id(file.get("fileId")).equals(id(evidence.get("fileId"))))return true;
        return false;
    }

    // Jury qualification is evaluated again when drawing, rather than trusting an old opt-in.
    public Map<String,Object> juror(Long uid){
        Map<String,Object> r=db.one("select * from kg_mediation_juror where app_user_id=:u",p("u",uid));
        if(r==null)r=p("willing","0","kind","user","creditScore",100,"violations",0);
        r.put("creditScore",moderation.creditScore(uid));
        boolean eligible=eligibleJuror(uid,str(r.get("kind")),KgJdbc.asLong(r.get("storeId")),r);
        boolean core=eligible&&badges.goldJurorEligible(uid);
        r.put("eligible",eligible);r.put("coreEligible",core);r.put("coreMember",core&&"1".equals(r.get("willing")));
        return r;
    }
    private boolean eligibleJuror(Long uid,String kind,Long store,Map<String,Object> r){
        if("1".equals(r.get("disqualified"))||num(r.get("violations"))>0||moderation.creditScore(uid)<90)return false;
        if(r.get("suspendedUntil")!=null&&time(r.get("suspendedUntil")).isAfter(now()))return false;
        if(db.count("select count(*) from kg_member_case where app_user_id=:u and status='executed'",p("u",uid))>0)return false;
        if("merchant".equals(kind))return store!=null&&orders.merchant(uid,store)&&db.count("select count(*) from kg_store where store_id=:s and status='0' and del_flag='0' and rating>=4.5 and create_time<=date_sub(now(),interval 3 month) and not exists(select 1 from kg_mediation_case where store_id=:s and status<>'closed')",p("s",store))==1;
        return db.count("select (select count(*) from kg_application a join kg_job j on j.job_id=a.job_id where a.app_user_id=:u and a.volunteer_done_time is not null and a.status in ('onboarded','completed') and a.del_flag='0' and j.job_type in ('volunteer','parttime'))+(select count(*) from kg_stay_order where app_user_id=:u and status='completed' and del_flag='0')",p("u",uid))>=3;
    }
    @Transactional(rollbackFor=Exception.class) public void optIn(Long uid,Map<String,Object>b){require(db.count("select count(*) from kg_mediation_vote v join kg_mediation_round r on r.round_id=v.round_id where v.app_user_id=:u and r.status='open'",p("u",uid))==0,"你有进行中的评审，请完成后再修改身份或退出评审池");String kind="merchant".equals(b.get("kind"))?"merchant":"user";Long store="merchant".equals(kind)?id(b.get("storeId")):null;if(store!=null&&!orders.merchant(uid,store))denied();db.update("insert into kg_mediation_juror(app_user_id,kind,store_id,willing) values(:u,:k,:s,:w) on duplicate key update kind=:k,store_id=:s,willing=:w,update_time=now()",p("u",uid,"k",kind,"s",store,"w",Boolean.TRUE.equals(b.get("willing"))?"1":"0"));}
    private boolean conflict(Long uid,Map<String,Object> c,Map<String,Object> juror){
        Long user=id(c.get("userId")),store=id(c.get("storeId"));if(uid.equals(user)||relatedToStore(uid,store)||uid.equals(KgJdbc.asLong(c.get("handlerId"))))return true;
        List<Long> parties=new ArrayList<>(orders.merchants(store));parties.add(user);
        if(db.count("select count(*) from kg_user_follow where (follower_id=:u and followee_id in(:p)) or (followee_id=:u and follower_id in(:p))",p("u",uid,"p",parties))>0)return true;
        if(db.count("select count(*) from kg_app_user where (user_id=:u and invited_by in(:p)) or (user_id in(:p) and invited_by=:u)",p("u",uid,"p",parties))>0)return true;
        // Any participating merchant account with a local store is excluded, even if it opted in as a user.
        return db.count("select count(*) from kg_staff_grant g join kg_store s on s.store_id=g.store_id where g.app_user_id=:u and g.role_code='merchant' and g.status='0' and g.del_flag='0' and (s.city=:city or replace(s.city,'岛','')=replace(:city,'岛',''))",p("u",uid,"city",c.get("city")))>0;
    }
    private void startJury(Long uid,Map<String,Object> c,Map<String,Object>b){
        require("ready".equals(c.get("status"))&&JURY.contains(c.get("category"))&&!"1".equals(c.get("urgent")),"此纠纷应由城市负责人直接裁决");require(Boolean.TRUE.equals(b.get("redactionConfirmed")),"请确认文本和全部附件已脱敏");
        String story=text(b,"anonymousNarrative",50,10000),claims=text(b,"anonymousClaims",2,2000);require(!story.matches("(?s).*1[3-9]\\d{9}.*")&&!claims.matches("(?s).*1[3-9]\\d{9}.*"),"请移除手机号");
        Map<String,Object> people=db.one("select real_name,nickname,phone from kg_app_user where user_id=:u",p("u",c.get("userId")));Map<String,Object> store=db.one("select store_name,contact_phone from kg_store where store_id=:s",p("s",c.get("storeId")));List<Object> hidden=new ArrayList<>();if(people!=null)hidden.addAll(people.values());if(store!=null)hidden.addAll(store.values());hidden.add(c.get("phone"));for(Object v:hidden){String secret=str(v);if(secret.length()>=2)require(!story.contains(secret)&&!claims.contains(secret),"脱敏材料仍含当事人或门店信息");}
        List<Long> files=ids(b.get("fileIds"));require(!files.isEmpty()&&files.size()<=9,"请另行上传1至9份已经脱敏的材料");
        List<Map<String,Object>> pool=db.list("select j.* from kg_mediation_juror j join kg_app_user u on u.user_id=j.app_user_id where j.willing='1' and j.disqualified='0' and u.status='0' and u.del_flag='0'",p());Collections.shuffle(pool,new SecureRandom());List<Map<String,Object>> users=new ArrayList<>(),shops=new ArrayList<>();
        boolean corePriority=Boolean.TRUE.equals(b.get("corePriority"));
        for(Map<String,Object> r:pool){Long candidate=id(r.get("appUserId"));if(eligibleJuror(candidate,str(r.get("kind")),KgJdbc.asLong(r.get("storeId")),r)&&!conflict(candidate,c,r)){r.put("coreMember",corePriority&&badges.goldJurorEligible(candidate));("merchant".equals(r.get("kind"))?shops:users).add(r);}}
        if(corePriority){users.sort(Comparator.comparing(r->!Boolean.TRUE.equals(r.get("coreMember"))));shops.sort(Comparator.comparing(r->!Boolean.TRUE.equals(r.get("coreMember"))));}
        require(users.size()>=3&&shops.size()>=1&&users.size()+shops.size()>=5,"合格且无利益关联的评审员不足5人，请先完善评审池或选择直接裁决");
        List<Map<String,Object>> picked=new ArrayList<>(users.subList(0,3));picked.add(shops.get(0));List<Map<String,Object>> remaining=new ArrayList<>(users.subList(3,users.size()));remaining.addAll(shops.subList(1,shops.size()));Collections.shuffle(remaining,new SecureRandom());if(corePriority)remaining.sort(Comparator.comparing(r->!Boolean.TRUE.equals(r.get("coreMember"))));picked.addAll(remaining.subList(0,Math.min(3,remaining.size())));
        List<Long> selectedUsers=new ArrayList<>();for(Map<String,Object> candidate:picked)selectedUsers.add(id(candidate.get("appUserId")));Collections.sort(selectedUsers);
        for(Long candidate:selectedUsers)must(db,"select user_id from kg_app_user where user_id=:u and status='0' and del_flag='0' for update",p("u",candidate),"评审池已变化，请重新邀请",409);
        for(Map<String,Object> candidate:picked){
            Long juror=id(candidate.get("appUserId"));
            Map<String,Object> current=must(db,"select * from kg_mediation_juror where app_user_id=:u for update",p("u",juror),"评审池已变化，请重新邀请",409);
            require("1".equals(current.get("willing"))&&Objects.equals(candidate.get("kind"),current.get("kind"))&&eligibleJuror(juror,str(current.get("kind")),KgJdbc.asLong(current.get("storeId")),current)&&!conflict(juror,c,current),"评审资格或参与意愿已变化，请重新邀请");
        }
        long rid=db.insert("kg_mediation_round",p("case_id",c.get("caseId"),"anonymous_narrative",story,"anonymous_claims",claims,"deadline",now().plusHours(48),"core_priority",corePriority?"1":"0"));attach(uid,id(c.get("caseId")),files,true);
        for(Map<String,Object>r:picked){Long juror=id(r.get("appUserId"));db.insert("kg_mediation_vote",p("round_id",rid,"app_user_id",juror,"juror_kind",r.get("kind")));messages.send(juror,"user","system","有一件纠纷需要您评议","请在48小时内阅读脱敏材料并独立投票。","mediation_jury",rid,"/pages/governance/jury?id="+rid);}
        transition(c,"app:"+uid,"city","jury","jury",corePriority?"复杂纠纷优先抽取合格核心评审员，48小时内独立评议":"已邀请匿名评审员，48小时内独立评议");
    }
    public List<Map<String,Object>> juryList(Long uid){return db.list("select r.round_id,r.status,r.deadline,v.choice,v.valid,v.quality,c.category from kg_mediation_vote v join kg_mediation_round r on r.round_id=v.round_id join kg_mediation_case c on c.case_id=r.case_id where v.app_user_id=:u order by r.round_id desc limit 100",p("u",uid));}
    public Map<String,Object> juryDetail(Long uid,Long rid){require(!"1".equals(juror(uid).get("disqualified")),"评审资格已取消");Map<String,Object> r=must(db,"select r.round_id,r.case_id,r.status,r.deadline,r.anonymous_narrative,r.anonymous_claims,v.choice,v.reason,c.category from kg_mediation_round r join kg_mediation_vote v on v.round_id=r.round_id join kg_mediation_case c on c.case_id=r.case_id where r.round_id=:r and v.app_user_id=:u",p("r",rid,"u",uid),"无权查看此评审",403);r.put("evidence",db.list("select file_id,mime_type,size_bytes from kg_mediation_file where case_id=:c and redacted='1' order by file_id",p("c",r.remove("caseId"))));return r;}
    @Transactional(rollbackFor=Exception.class) public void vote(Long uid,Long rid,Map<String,Object>b){Map<String,Object> r=must(db,"select * from kg_mediation_round where round_id=:r for update",p("r",rid),"评审不存在",404);Map<String,Object> v=must(db,"select * from kg_mediation_vote where round_id=:r and app_user_id=:u for update",p("r",rid,"u",uid),"无权投票",403);require("open".equals(r.get("status"))&&time(r.get("deadline")).isAfter(now()),"评审已截止");require(v.get("choice")==null,"已投票，不能重复投票");String choice=text(b,"choice",1,24),reason=text(b,"reason",0,1000);require(CHOICES.contains(choice),"请选择有效选项");Map<String,Object> qualification=juror(uid);require(Boolean.TRUE.equals(qualification.get("eligible"))&&!conflict(uid,get(id(r.get("caseId")),false),qualification),"评审资格或利益关系发生变化，当前无法投票");db.update("update kg_mediation_vote set choice=:c,reason=:n,voted_at=now(),valid='1' where vote_id=:v",p("v",v.get("voteId"),"c",choice,"n",reason));ledger.award(uid,5,"完成有效大众评审","m_jury",id(v.get("voteId")));}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED) public void finishRound(Long rid){Map<String,Object> owner=must(db,"select case_id from kg_mediation_round where round_id=:r",p("r",rid),"评审不存在",404);Map<String,Object> c=get(id(owner.get("caseId")),true);Map<String,Object> r=must(db,"select * from kg_mediation_round where round_id=:r for update",p("r",rid),"评审不存在",404);if(!"open".equals(r.get("status"))||time(r.get("deadline")).isAfter(now()))return;Map<String,Object> result=roundResult(rid);db.update("update kg_mediation_round set status='closed',result_json=:j where round_id=:r",p("r",rid,"j",JSON.toJSONString(result)));
        for(Map<String,Object>v:db.list("select * from kg_mediation_vote where round_id=:r",p("r",rid)))updateMisses(id(v.get("appUserId")));
        transition(c,"system","system","jury_end","ready","大众评审已截止，等待城市负责人裁决");if(c.get("handlerId")!=null)notify(c,id(c.get("handlerId")),"staff","jury_end","大众评审已结束，请查看意见并裁决");
    }
    /** Published totals must use the same valid votes as membership rewards. */
    private Map<String,Object> roundResult(Long rid) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String choice:CHOICES)result.put(choice,db.count("select count(*) from kg_mediation_vote where round_id=:r and choice=:c and valid='1'",p("r",rid,"c",choice)));
        result.put("reasons",db.list("select choice,reason from kg_mediation_vote where round_id=:r and valid='1' and trim(coalesce(reason,''))<>'' order by vote_id",p("r",rid)));
        return result;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED) public void assess(Long voteId,Map<String,Object>b,String actor){
        Map<String,Object> owner=must(db,"select r.case_id,v.round_id from kg_mediation_vote v join kg_mediation_round r on r.round_id=v.round_id where v.vote_id=:v",p("v",voteId),"评审不存在",404);
        Map<String,Object> c=get(id(owner.get("caseId")),true);
        Long rid=id(owner.get("roundId"));
        Map<String,Object> round=must(db,"select status from kg_mediation_round where round_id=:r for update",p("r",rid),"评审不存在",404);
        Map<String,Object> v=must(db,"select * from kg_mediation_vote where vote_id=:v for update",p("v",voteId),"评审不存在",404);
        require("closed".equals(round.get("status"))&&v.get("choice")!=null&&"0".equals(v.get("assessed")),"评审未结束、未投票或已评定");
        String note=text(b,"note",10,500);
        boolean valid=Boolean.TRUE.equals(b.get("valid")),quality=valid&&Boolean.TRUE.equals(b.get("quality"));
        String kind=b.containsKey("assessmentKind")?str(b.get("assessmentKind")):(valid?"valid":"invalid");
        require(Arrays.asList("valid","perfunctory","invalid").contains(kind)&&valid=="valid".equals(kind),"请核对有效性与评审核验结论");
        require(!quality||!str(v.get("reason")).trim().isEmpty(),"优质评审须填写理由，并由总部核实其参考价值");
        Long uid=id(v.get("appUserId"));
        db.update("update kg_mediation_vote set assessed='1',valid=:v,quality=:q,assessment_note=:n,assessment_kind=:k where vote_id=:id",p("id",voteId,"v",valid?"1":"0","q",quality?"1":"0","n",note,"k",kind));
        if(quality)ledger.award(uid,10,"优质大众评审奖励","m_jury_quality",voteId);
        if(!valid)ledger.clawBack(uid,5,"评审无效，撤回奖励","m_jury_back",voteId);
        updatePerfunctory(uid,actor);
        if(!badges.goldJurorEligible(uid))revokeJurorBadges(uid,false);
        db.update("update kg_mediation_round set result_json=:j where round_id=:r",p("r",rid,"j",JSON.toJSONString(roundResult(rid))));
        log(c,actor,"hq","jury_assessment",str(c.get("status")),note,"staff");
    }
    private void updateMisses(Long uid){
        int misses=0;for(Map<String,Object>v:db.list("select v.choice from kg_mediation_vote v join kg_mediation_round r on r.round_id=v.round_id where v.app_user_id=:u and r.status='closed' order by r.deadline desc,r.round_id desc limit 3",p("u",uid))){if(v.get("choice")!=null)break;misses++;}
        db.update("update kg_mediation_juror set missed=:m where app_user_id=:u",p("m",misses,"u",uid));if(misses>=3)db.update("update kg_mediation_juror set suspended_until=date_add(now(),interval 30 day) where app_user_id=:u",p("u",uid));
        if(misses>0)revokeJurorBadges(uid,false);
    }
    private void updatePerfunctory(Long uid,String actor){
        must(db,"select app_user_id from kg_mediation_juror where app_user_id=:u for update",p("u",uid),"评审员不存在",404);
        List<Map<String,Object>> votes=db.list("select v.vote_id,v.choice,v.assessment_kind from kg_mediation_vote v join kg_mediation_round r on r.round_id=v.round_id where v.app_user_id=:u and r.status='closed' order by coalesce(v.voted_at,r.deadline),v.vote_id",p("u",uid));
        Set<Long> processedEnds=new HashSet<>(),processedVotes=new HashSet<>();
        for(Map<String,Object> previous:db.list("select related_vote_id from kg_badge_log where app_user_id=:u and action='juror_perfunctory_suspend'",p("u",uid)))processedEnds.add(id(previous.get("relatedVoteId")));
        for(int i=0;i<votes.size();i++){
            if(!processedEnds.contains(id(votes.get(i).get("voteId"))))continue;
            for(int j=Math.max(0,i-2);j<=i;j++)processedVotes.add(id(votes.get(j).get("voteId")));
        }
        int consecutive=0,newGroups=0;
        for(Map<String,Object> vote:votes){
            consecutive=!processedVotes.contains(id(vote.get("voteId")))&&vote.get("choice")!=null&&"perfunctory".equals(vote.get("assessmentKind"))?consecutive+1:0;
            if(consecutive<3)continue;
            Long lastVote=id(vote.get("voteId"));
            newGroups+=db.update("insert ignore into kg_badge_log(app_user_id,actor,action,related_vote_id,note) values(:u,:a,'juror_perfunctory_suspend',:v,:n)",p("u",uid,"a",actor,"v",lastVote,"n","连续三次评审经总部核实为敷衍，组末评审编号 "+lastVote));
            consecutive=0;
        }
        if(newGroups==0)return;
        db.update("update kg_mediation_juror set suspended_until=greatest(coalesce(suspended_until,now()),date_add(now(),interval 30 day)) where app_user_id=:u",p("u",uid));
        revokeJurorBadges(uid,false);
    }
    private void revokeJurorBadges(Long uid,boolean all){
        db.update("update kg_badge_award set status='inactive',worn='0' where app_user_id=:u and badge_code in("+(all?"'juror','juror_gold'":"'juror_gold'")+") and status='active'",p("u",uid));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED) public void sanctionJuror(Long uid,Map<String,Object>b,String actor){
        String note=text(b,"note",10,500),kind=b.containsKey("kind")?str(b.get("kind")):"leakage";
        require(Arrays.asList("leakage","bias").contains(kind),"请选择已核实的泄露或偏袒行为");
        long deduction=num(b.get("deduction"));require(deduction>=1&&deduction<=100,"信用扣分范围1至100");
        must(db,"select user_id from kg_app_user where user_id=:u for update",p("u",uid),"账号不存在",404);
        must(db,"select app_user_id from kg_mediation_juror where app_user_id=:u for update",p("u",uid),"评审员不存在",404);
        String action="juror_"+kind;
        if(db.count("select count(*) from kg_badge_log l where l.app_user_id=:u and l.action=:a and not exists(select 1 from kg_badge_log r where r.action='juror_restore' and r.related_log_id=l.log_id)",p("u",uid,"a",action))>0)return;
        int creditDelta=moderation.adjustCredit(uid,-Math.toIntExact(deduction),actor);
        db.update("update kg_mediation_juror set disqualified='1',willing='0',violations=violations+1 where app_user_id=:u",p("u",uid));
        db.insert("kg_badge_log",p("app_user_id",uid,"actor",actor,"action",action,"note",note,"credit_delta",creditDelta));
        revokeJurorBadges(uid,true);
    }
    public List<Map<String,Object>> jurorSanctions(Long uid){
        return db.list("select l.log_id,l.action,l.note,l.actor,l.create_time,l.credit_delta,not exists(select 1 from kg_badge_log r where r.action='juror_restore' and r.related_log_id=l.log_id) active from kg_badge_log l where l.app_user_id=:u and l.action in ('juror_leakage','juror_bias','juror_disqualified') order by l.log_id desc",p("u",uid));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED) public void restoreJuror(Long uid,Map<String,Object>b,String actor){
        String note=text(b,"note",10,500);Long logId=id(b.get("logId"));
        must(db,"select user_id from kg_app_user where user_id=:u for update",p("u",uid),"账号不存在",404);
        Map<String,Object> juror=must(db,"select * from kg_mediation_juror where app_user_id=:u for update",p("u",uid),"评审员不存在",404);
        Map<String,Object> original=must(db,"select action,credit_delta from kg_badge_log where log_id=:l and app_user_id=:u for update",p("l",logId,"u",uid),"原处理记录不存在",404);
        require(Arrays.asList("juror_leakage","juror_bias","juror_disqualified").contains(original.get("action")),"请选择对应的评审资格处理记录");
        if(db.count("select count(*) from kg_badge_log where action='juror_restore' and related_log_id=:l",p("l",logId))>0)return;
        Integer creditDelta=original.get("creditDelta")==null?null:moderation.adjustCredit(uid,Math.toIntExact(-num(original.get("creditDelta"))),actor);
        db.insert("kg_badge_log",p("app_user_id",uid,"actor",actor,"action","juror_restore","related_log_id",logId,"note",note,"credit_delta",creditDelta));
        long remaining=Math.max(0,num(juror.get("violations"))-1);
        remaining=Math.max(remaining,db.count("select count(*) from kg_badge_log l where l.app_user_id=:u and l.action in ('juror_leakage','juror_bias','juror_disqualified') and not exists(select 1 from kg_badge_log r where r.action='juror_restore' and r.related_log_id=l.log_id)",p("u",uid)));
        db.update("update kg_mediation_juror set violations=:n,disqualified=:d,update_time=now() where app_user_id=:u",p("u",uid,"n",remaining,"d",remaining>0?"1":"0"));
    }
    @Transactional(rollbackFor=Exception.class) public void deadlines(Long cid){Map<String,Object> c=get(cid,true);if("pending".equals(c.get("status"))&&time(c.get("acceptDeadline")).isBefore(now()))event(c,"overdue","headquarters","已超过接单时限，请督导安排城市负责人处理","pending");if("confirmation".equals(c.get("status"))&&time(c.get("confirmDeadline")).isBefore(now())){notifyParties(c,"confirmation_due","调解确认已到期，请查看结果或提交申诉");event(c,"confirmation_due","headquarters","当事人尚未确认，不自动视为接受","pending");}}
    public boolean fileAccess(Long uid,Map<String,Object>f,boolean hq){if(hq)return true;if(f.get("caseId")==null)return ("app:"+uid).equals(f.get("ownerActor"));Map<String,Object>c=get(id(f.get("caseId")),false);if("1".equals(f.get("redacted"))&&!"1".equals(juror(uid).get("disqualified"))&&db.count("select count(*) from kg_mediation_vote v join kg_mediation_round r on r.round_id=v.round_id where r.case_id=:c and v.app_user_id=:u",p("c",c.get("caseId"),"u",uid))>0)return true;try{String as=role(uid,c,false);return "city".equals(as)||("app:"+uid).equals(f.get("ownerActor"))||"1".equals(f.get("redacted"))||("support".equals(as)&&sharedMerchantFile(uid,c,f));}catch(ServiceException e){return false;}}
    public Map<String,Object> stats(){return p("totals",db.one("select count(*) cases,sum(status='closed') closed,sum(status='appealed') appeals,avg(timestampdiff(minute,create_time,accepted_at)) response_minutes,sum(path='jury') jury_cases,sum(path='jury' and jury_adopted='1') adopted from kg_mediation_case",p()),"types",db.list("select category,count(*) n from kg_mediation_case group by category",p()),"urgentQueue",db.list("select e.*,c.case_no from kg_mediation_event e join kg_mediation_case c on c.case_id=e.case_id where e.status in ('pending','manual_required') order by event_id desc limit 100",p()));}
}
