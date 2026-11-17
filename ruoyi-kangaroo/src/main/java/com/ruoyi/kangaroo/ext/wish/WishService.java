package com.ruoyi.kangaroo.ext.wish;

import java.time.*;
import java.util.*;
import com.ruoyi.kangaroo.ext.community.KgCommunityFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import com.ruoyi.kangaroo.ext.core.KgImageMagic;
import com.ruoyi.kangaroo.ext.pay.*;
import com.ruoyi.kangaroo.service.IKgMessageService;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** A payment is always locked before its wish. Row locks serialize acceptance and settlement. */
@Service
public class WishService implements KgPayListener {
    @Autowired private KgJdbc db;
    @Autowired @Lazy private KgPayService payments;
    @Autowired private KgPrivateFiles files;
    @Autowired private IKgMessageService notices;
    @Autowired @Lazy private WishService self;
    private static final Logger LOG = LoggerFactory.getLogger(WishService.class);
    private static final Set<String> CATEGORIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("craft","souvenir","postcard","errand","food","licensed")));
    private static final String JOIN = " from kg_wish w left join kg_app_user u on u.user_id=w.publisher_id left join kg_app_user h on h.user_id=w.helper_id";
    private static final String SELECT = "select w.*,u.nickname publisher_name,u.avatar publisher_avatar,h.nickname helper_name,h.avatar helper_avatar";
    private static final String PUBLIC = "select w.wish_id,w.title,w.category,w.origin_city,w.target_city,w.delivery,w.deadline,w.images,w.mode,w.amount_fen,w.status,w.create_time,u.nickname publisher_name,u.avatar publisher_avatar";
    public static String str(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    private static long num(Object v) { return v == null ? 0 : ((Number)v).longValue(); }
    private static String field(Map<String,Object> b,String name,int min,int max) {
        String s=str(b.get(name));
        if(s.length()<min || s.length()>max) {
            String label=str(p("title","心愿标题","description","心愿描述","originCity","所在城市","targetCity","帮忙城市","mode","互助方式","category","心愿分类","delivery","交付方式","note","说明","content","消息内容","receipt","打款流水号").getOrDefault(name,"提交信息"));
            throw new ServiceException("请检查"+label+"是否完整，长度不能超过"+max+"个字符",422);
        }
        return s;
    }
    private static void state(Map<String,Object> w,String... allowed) {
        if(!Arrays.asList(allowed).contains(w.get("status"))) throw new ServiceException("心愿状态已变化，请刷新后操作",409);
    }
    private static boolean owner(Map<String,Object> w,Long u) { return u!=null && u==num(w.get("publisherId")); }
    private static boolean helper(Map<String,Object> w,Long u) { return u!=null && u==num(w.get("helperId")); }
    private static void member(Map<String,Object> w,long u) { if(!owner(w,u)&&!helper(w,u)) throw new ServiceException("仅订单双方可查看",403); }
    private Map<String,Object> row(long id,boolean lock) { return db.must("select * from kg_wish where wish_id=:id"+(lock?" for update":""),p("id",id),"心愿不存在"); }
    private void audit(long id,String actor,String action,String note) { db.insert("kg_wish_audit",p("wish_id",id,"actor",actor,"action",action,"note",note)); }
    private void notify(Map<String,Object> w,String title,String content) {
        for(String key:Arrays.asList("publisherId","helperId")) if(w.get(key)!=null)
            notices.send(num(w.get(key)),"user","system",title,content,"wish",num(w.get("wishId")),"/pages/wish/order?id="+w.get("wishId"));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> decorate(Map<String,Object> w,Long me,boolean detail) {
        if(detail){
            w.put("informationOnly",WishEligibility.informationOnly(w));
            try{w.put("requiredTypes",WishEligibility.labels(requirements(w,false)));}
            catch(ServiceException error){w.put("eligibilityNotice",error.getMessage());}
        }
        w.put("images",split(w.get("images"))); w.put("isOwner",owner(w,me)); w.put("isHelper",helper(w,me));
        if(detail) {
            w.put("proof",split(w.get("proof")));
            if(!owner(w,me)&&!helper(w,me)) for(String k:Arrays.asList("payNo","requestKey","proof","deliveryNote","courier","trackingNo","dispute","payoutReceipt","auditNote")) w.remove(k);
        }
        return w;
    }
    private static List<String> split(Object v) { String s=str(v);return s.isEmpty()?Collections.emptyList():Arrays.asList(s.split(",")); }
    private static String images(Object value) {
        if(!(value instanceof List)) return "";
        List<?> list=(List<?>)value;if(list.size()>6)throw new ServiceException("最多上传6张图片",422);
        List<String> out=new ArrayList<>();for(Object v:list){String s=str(v);if(!KgJdbc.isPlatformImage(s))throw new ServiceException("请上传平台图片",422);out.add(s);} return String.join(",",out);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> list(Long me,String role,String status,String city,String category,String mode,String keyword,String min,String max,String sort,int page) {
        boolean mine=Arrays.asList("publisher","helper").contains(str(role));
        if(!mine) KgCommunityFeature.requireWish(db);
        Map<String,Object> args=p("me",me); StringBuilder where=new StringBuilder(" where 1=1");
        if(mine) { if(me==null)throw new ServiceException("请先登录",401);where.append(" and w.").append("helper".equals(role)?"helper_id":"publisher_id").append("=:me"); }
        else where.append(" and w.status='open' and w.deadline>=curdate()");
        if(!str(status).isEmpty()&&mine){where.append(" and w.status=:st");args.put("st",status);}
        if(!str(city).isEmpty()){where.append(" and w.target_city like :city");args.put("city","%"+city+"%");}
        if(CATEGORIES.contains(str(category))){where.append(" and w.category=:cat");args.put("cat",category);}
        if(Arrays.asList("paid","free").contains(str(mode))){where.append(" and w.mode=:mode");args.put("mode",mode);}
        if(!str(keyword).isEmpty()){where.append(" and w.title like :q");args.put("q","%"+keyword+"%");}
        if(!str(min).isEmpty()){where.append(" and w.amount_fen>=:min");args.put("min",filterPrice(min));}
        if(!str(max).isEmpty()){where.append(" and w.amount_fen<=:max");args.put("max",filterPrice(max));}
        String order="deadline".equals(sort)?"w.deadline asc,w.wish_id desc":"price".equals(sort)?"w.amount_fen asc,w.wish_id desc":"w.wish_id desc";
        Map<String,Object> result=db.page((mine?SELECT:PUBLIC)+JOIN+where+" order by "+order,args,page,20);
        for(Map<String,Object> w:(List<Map<String,Object>>)result.get("rows"))decorate(w,me,mine);
        return result;
    }
    private static long filterPrice(String value) {
        if(!value.matches("[0-9]{1,6}(\\.[0-9]{1,2})?"))throw new ServiceException("价格筛选无效",422);
        return new java.math.BigDecimal(value).movePointRight(2).longValueExact();
    }
    public Map<String,Object> detail(long id,Long me) {
        Map<String,Object> w=db.must(SELECT+JOIN+" where w.wish_id=:id",p("id",id),"心愿不存在");
        if(!owner(w,me)&&!helper(w,me)) KgCommunityFeature.requireWish(db);
        if(!owner(w,me)&&!helper(w,me)&&!"open".equals(w.get("status")))throw new ServiceException("该心愿暂不可查看",404);
        return decorate(w,me,true);
    }
    // A retry waiting on the idempotency lock must see the first request's committed order.
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> publish(long user,Map<String,Object> b) {
        KgCommunityFeature.requireWish(db);
        String key=field(b,"requestKey",12,64);
        db.claimIdem("wish:"+user+":"+key,"wish");
        Map<String,Object> old=db.one("select wish_id from kg_wish where publisher_id=:u and request_key=:k for update",p("u",user,"k",key));
        if(old!=null)return detail(num(old.get("wishId")),user);
        if(!Boolean.TRUE.equals(b.get("agreed")))throw new ServiceException("请阅读并同意心愿规则",422);
        String mode=field(b,"mode",4,4),cat=field(b,"category",1,24);
        if(!Arrays.asList("paid","free").contains(mode)||!CATEGORIES.contains(cat))throw new ServiceException("该品类未开放",422);
        String title=field(b,"title",3,80),description=field(b,"description",10,2000);
        WishEligibility.inspectWish(p("title",title,"description",description,"category",cat));
        LocalDate deadline;try{deadline=LocalDate.parse(str(b.get("deadline")));}catch(Exception e){throw new ServiceException("请选择期望完成日期",422);}
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(deadline.isBefore(today)||deadline.isAfter(today.plusDays(90)))throw new ServiceException("期望完成日期须在未来90天内",422);
        String delivery=field(b,"delivery",1,20);if(!Arrays.asList("express","digital","meet").contains(delivery))throw new ServiceException("请选择交付方式",422);
        Map<String,Object> q=WishPricing.quote(b.get("cost"),"free".equals(mode));
        long id=db.insert("kg_wish",p("publisher_id",user,"request_key",key,"title",title,"description",description,"category",cat,
            "origin_city",field(b,"originCity",2,80),"target_city",field(b,"targetCity",2,80),"delivery",delivery,"deadline",deadline.toString(),
            "images",images(b.get("images")),"mode",mode,"cost_fen",q.get("costFen"),"markup_fen",q.get("markupFen"),"amount_fen",q.get("amountFen"),
            "fee_fen",q.get("feeFen"),"net_fen",q.get("netFen"),"status","free".equals(mode)?"reviewing":"pending_payment"));
        if("paid".equals(mode)){
            Map<String,Object> pay=payments.createForNewBusiness(bizType(),id,user,null,num(q.get("amountFen")),"心愿互助："+title);
            db.update("update kg_wish set pay_no=:n,expire_time=date_add(now(),interval 30 minute) where wish_id=:id",p("n",pay.get("payNo"),"id",id));
        }
        audit(id,"app:"+user,"publish","");return detail(id,user);
    }
    private int requirements(Map<String,Object> wish, boolean requireReview) {
        int stored = (int) num(wish.get("reviewedRequirements"));
        int reviewed = stored & WishEligibility.SCOPES;
        boolean matched = WishEligibility.reviewMatches(wish);
        int text = WishEligibility.inspectWish(wish);
        // 后台确认过「关键词误判」且正文、图片之后没改：正文关键词不再叠加，以人工核实的品类为准；所选类目、交付说明照常算
        if (matched && (stored & WishEligibility.MANUAL) != 0) text = WishEligibility.categoryFloor(wish, reviewed);
        if (WishEligibility.informationOnly(wish)) text &= ~WishEligibility.FOOD;
        int detected = text | WishEligibility.deliveryRequirements(wish,wish.get("deliveryNote"));
        boolean legacySafeText = wish.get("reviewContentHash") == null && detected == 0 && str(wish.get("images")).isEmpty() && WishEligibility.clearlyOrdinary(wish);
        if (requireReview && !matched && !legacySafeText)
            throw new ServiceException("内容或图片需要平台复核品类，请申请平台介入；取消和退款仍可办理", 409);
        if (requireReview && matched && (detected & ~reviewed) != 0)
            throw new ServiceException("心愿新增了受限内容，需要平台重新核实品类", 409);
        return detected | reviewed;
    }
    private List<Map<String,Object>> qualifiedCredentials(long user, int requirements, String city, LocalDate date) {
        if (requirements == 0) return Collections.emptyList();
        List<Map<String,Object>> candidates = db.list("select credential_id,type,city,valid_from,valid_until,permit_scope from kg_wish_credential where user_id=:u and status='approved' and valid_from<=:d and valid_until>=:d order by credential_id for update",p("u",user,"d",date.toString()));
        List<Map<String,Object>> used = new ArrayList<>();
        int covered = 0;
        for (Map<String,Object> credential : candidates) {
            int scope = 0;
            if (Arrays.asList("health","food").contains(credential.get("type"))) scope = WishEligibility.FOOD;
            if ("permit".equals(credential.get("type")) && WishEligibility.normalized(city).equals(WishEligibility.normalized(credential.get("city"))))
                scope = (int) num(credential.get("permitScope")) & (WishEligibility.TOBACCO | WishEligibility.ALCOHOL);
            if ((scope & requirements) != 0) { covered |= scope; used.add(credential); }
        }
        if ((covered & requirements) != requirements)
            throw new ServiceException("此心愿按实物服务核验资质；普通用户也可提交有效证照。如仅咨询信息，请联系平台复核心愿分类", 422);
        return used;
    }
    private void saveReview(Map<String,Object> wish, Map<String,Object> body) {
        if (!Boolean.TRUE.equals(body.get("contentChecked")))
            throw new ServiceException("请人工核对正文、图片及实际商品，确认未涉及禁品", 422);
        int detected = requirements(wish, false);
        int declared = WishEligibility.declared(body.get("complianceTypes"));
        // 关键词误判改判：正文关键词检测出的、此前复核的范围可以去掉；所选类目和交付说明涉及的受限范围仍要涵盖
        boolean misjudged = Boolean.TRUE.equals(body.get("keywordMisjudged"));
        boolean information = Boolean.TRUE.equals(body.get("informationOnly"));
        if (information && (!"digital".equals(wish.get("delivery")) || "licensed".equals(wish.get("category")) || declared != 0))
            throw new ServiceException("信息咨询仅限线上交付的普通品类，不能包含实物采购、制作、寄送或烟酒服务",422);
        if (information && WishEligibility.requestsChangedGoods(str(wish.get("deliveryNote"))))
            throw new ServiceException("交付说明包含实物采购或寄送要求，请按实际商品核实资质",422);
        if (misjudged || information) detected = WishEligibility.categoryFloor(wish, declared) | WishEligibility.inspect(wish.get("deliveryNote"));
        if (information) detected &= ~WishEligibility.FOOD;
        if ((declared & detected) != detected)
            throw new ServiceException(misjudged ? "改判后仍须涵盖所选类目及交付说明涉及的受限范围" : "人工分类必须涵盖所选类目、已检测内容及此前复核的全部受限范围", 422);
        // 之前改判过、正文图片也没改：这次复核没勾也保留改判标记，不然正文关键词又会叠加回来
        boolean kept = (num(wish.get("reviewedRequirements")) & WishEligibility.MANUAL) != 0
                && WishEligibility.reviewMatches(wish);
        boolean keepInformation = WishEligibility.informationOnly(wish) && declared == 0;
        int stored = declared | (misjudged || information || kept ? WishEligibility.MANUAL : 0)
                | (information || keepInformation ? WishEligibility.INFORMATION : 0);
        String reviewHash = WishEligibility.reviewHash(wish, (stored & WishEligibility.INFORMATION) != 0);
        db.updateRow("kg_wish","wish_id",wish.get("wishId"),p("reviewed_requirements",stored,"review_content_hash",reviewHash));
        wish.put("reviewedRequirements",stored);
        wish.put("reviewContentHash",reviewHash);
    }
    private String qualificationSnapshot(long helper, int required, String city, LocalDate date) {
        List<Map<String,Object>> credentials = qualifiedCredentials(helper, required, city, date);
        try { return new ObjectMapper().writeValueAsString(p("checkedOn",date.toString(),"requirements",required,"credentials",credentials)); }
        catch (JsonProcessingException error) { throw new IllegalStateException("资质记录保存失败",error); }
    }
    private void checkCompletion(Map<String,Object> wish) {
        int required = requirements(wish, true) | WishEligibility.deliveryRequirements(wish,wish.get("deliveryNote"));
        if (wish.get("fulfillmentRequirements") != null && !str(wish.get("fulfillmentQualification")).isEmpty()
                && (required & ~(int) num(wish.get("fulfillmentRequirements"))) == 0) return;
        throw new ServiceException("此订单缺少交付时的资质核验记录，请申请平台介入，由平台核实当时证照或退款", 409);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> accept(long id,long user) {
        KgCommunityFeature.requireWish(db);
        Map<String,Object>w=row(id,true);
        int required=requirements(w,true);
        qualifiedCredentials(user,required,str(w.get("targetCity")),LocalDate.now(ZoneId.of("Asia/Shanghai")));
        if(helper(w,user))return detail(id,user);state(w,"open");
        if(owner(w,user))throw new ServiceException("不能承接自己的心愿",422);
        if(LocalDate.parse(str(w.get("deadline")).substring(0,10)).isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw new ServiceException("心愿已超过期望日期",409);
        db.updateRow("kg_wish","wish_id",id,p("helper_id",user,"status","accepted"));
        audit(id,"app:"+user,"accept","");
        Map<String,Object> accepted=row(id,false);
        notices.send(num(accepted.get("publisherId")),"user","system","你的心愿已被接单","帮忙的朋友已接单，可以在订单中沟通交付细节。","wish",id,"/pages/wish/order?id="+id);
        notices.send(user,"user","system","你已成功接下心愿","你正在帮助对方，完成约定后请提交完成凭证。","wish",id,"/pages/wish/order?id="+id);
        return detail(id,user);
    }
    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> action(long id,long user,String action,Map<String,Object>b) {
        Map<String,Object>w=row(id,false);member(w,user);
        if("cancel".equals(action)&&w.get("payNo")!=null) payments.lockPayment(str(w.get("payNo")));
        w=row(id,true);member(w,user);
        switch(action){
        case "tracking":
            if(!helper(w,user))throw new ServiceException("仅接单者可填写物流信息",403);
            state(w,"accepted","submitted");
            if(!"express".equals(w.get("delivery")))throw new ServiceException("此心愿不是快递交付",422);
            String courier=field(b,"courier",1,60),trackingNo=str(b.get("trackingNo")).replaceAll("\\s+","");
            if(!trackingNo.matches("[A-Za-z0-9-]{6,80}"))throw new ServiceException("请填写6至80位快递单号，可包含字母、数字和短横线",422);
            db.updateRow("kg_wish","wish_id",id,p("courier",courier,"tracking_no",trackingNo));break;
        case "deliver":
            if(!helper(w,user))throw new ServiceException("仅服务者可提交交付",403);state(w,"accepted");
            String proof=images(b.get("images"));if(proof.isEmpty())throw new ServiceException("请上传至少一张交付凭证",422);
            String deliveryNote=field(b,"note",5,500);
            int required=requirements(w,true);
            if ((WishEligibility.deliveryRequirements(w,deliveryNote) & ~required) != 0) throw new ServiceException("交付内容超出已审核品类，请申请平台介入重新核实",409);
            String snapshot=qualificationSnapshot(user,required,str(w.get("targetCity")),LocalDate.now(ZoneId.of("Asia/Shanghai")));
            db.updateRow("kg_wish","wish_id",id,p("proof",proof,"delivery_note",deliveryNote,"status","submitted","fulfillment_requirements",required,"fulfillment_qualification",snapshot));break;
        case "complete":
            if(!owner(w,user))throw new ServiceException("仅发布者可确认完成",403);if("completed".equals(w.get("status")))return detail(id,user);state(w,"submitted");complete(w);break;
        case "dispute":
            state(w,"accepted","submitted");db.updateRow("kg_wish","wish_id",id,p("status","disputed","dispute",field(b,"note",5,500)));break;
        case "review":
            if(!owner(w,user))throw new ServiceException("仅发布者可评价",403);state(w,"completed");
            if(w.get("rating")!=null)throw new ServiceException("此心愿已经评价",409);
            int rating;try{rating=Integer.parseInt(str(b.get("rating")));}catch(Exception e){throw new ServiceException("请选择星级",422);}
            if(rating<1||rating>5)throw new ServiceException("请选择1至5星",422);
            db.updateRow("kg_wish","wish_id",id,p("rating",rating,"review",field(b,"note",0,500)));break;
        case "cancel":
            if(!owner(w,user))throw new ServiceException("仅发布者可取消",403);state(w,"pending_payment","reviewing","open","refund_failed");
            refund(w,"用户取消","app:"+user);break;
        default:throw new ServiceException("不支持的操作",422);
        }
        audit(id,"app:"+user,action,str(b.get("note")));notify(row(id,false),"心愿进度已更新","打开订单查看最新进度。");return detail(id,user);
    }
    private void complete(Map<String,Object>w) {
        checkCompletion(w);
        db.update("update kg_wish set status='completed',completed_time=now(),payout_status=:s where wish_id=:id",p("id",w.get("wishId"),"s","free".equals(w.get("mode"))?"none":"pending"));
    }
    private void refund(Map<String,Object>w,String note,String operator) {
        long id=num(w.get("wishId"));String no=str(w.get("payNo"));
        if(no.isEmpty()){db.updateRow("kg_wish","wish_id",id,p("status","cancelled"));return;}
        if("pending_payment".equals(w.get("status"))&&payments.close(no)){db.updateRow("kg_wish","wish_id",id,p("status","cancelled"));return;}
        db.updateRow("kg_wish","wish_id",id,p("status","refunding"));payments.refund(no,num(w.get("amountFen")),note,operator);
    }
    // 有偿心愿发布后 30 分钟没付款：定时关掉支付单和心愿，告诉发布者（写法同服务订单 closeExpired；支付单已关的也收尾）
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void closeExpired(){
        for(Map<String,Object>w:db.list("select w.wish_id from kg_wish w join kg_pay_order p on p.pay_no=w.pay_no where w.status='pending_payment' and w.expire_time<now() and p.status in ('pending','closed') order by w.wish_id limit 100",p()))
            try{self.expireOne(num(w.get("wishId")));}catch(RuntimeException e){LOG.warn("心愿超时关单失败 {}：{}",w.get("wishId"),e.getMessage());}
    }
    /** 先锁支付单再锁心愿（与取消同序）；关单时发现已付款（close 返回 false）就不动，由支付回调转「审核中」 */
    @Transactional(rollbackFor=Exception.class)
    public boolean expireOne(long id){
        Map<String,Object>w=row(id,false);String no=str(w.get("payNo"));
        if(no.isEmpty()||!"pending_payment".equals(w.get("status")))return false;
        payments.lockPayment(no);w=row(id,true);
        if(!"pending_payment".equals(w.get("status"))||db.count("select count(*) from kg_wish where wish_id=:id and expire_time<now()",p("id",id))!=1)return false;
        if(!payments.close(no))return false;
        db.updateRow("kg_wish","wish_id",id,p("status","cancelled"));
        audit(id,"system","expire","超过 30 分钟未支付，自动关闭");
        notices.send(num(w.get("publisherId")),"user","system","心愿已自动关闭","你发布的心愿「"+str(w.get("title"))+"」30 分钟内没有完成支付，已自动关闭，没有扣款。仍需要帮忙的话请重新发布。","wish",id,"/pages/wish/order?id="+id);
        return true;
    }
    public String bizType(){return "wish_order";}
    @Override public void beforeRefund(Map<String,Object>pay,long amount) {
        Map<String,Object>w=row(num(pay.get("bizId")),true);
        if(!Objects.equals(w.get("payNo"),pay.get("payNo"))||!Arrays.asList("refunding","refund_failed").contains(w.get("status"))
            ||amount!=num(w.get("amountFen"))||num(pay.get("refundAmount"))!=0)
            throw new ServiceException("请在心愿审核台按订单状态发起全额退款，已结算订单不能直接退款",409);
    }
    public void onPaid(Map<String,Object>pay){
        Map<String,Object>w=row(num(pay.get("bizId")),true);
        if(!Objects.equals(w.get("payNo"),pay.get("payNo"))||num(w.get("amountFen"))!=num(pay.get("amount"))||num(w.get("publisherId"))!=num(pay.get("appUserId")))throw new ServiceException("心愿支付数据不一致",409);
        if("pending_payment".equals(w.get("status"))){db.updateRow("kg_wish","wish_id",w.get("wishId"),p("status","reviewing"));audit(num(w.get("wishId")),"payment","paid","");}
    }
    public void onRefunded(Map<String,Object>pay,long amount){
        if(num(pay.get("refundAmount"))==num(pay.get("amount")))db.update("update kg_wish set status='cancelled' where wish_id=:id and pay_no=:n and status in ('refunding','refund_failed')",p("id",pay.get("bizId"),"n",pay.get("payNo")));
    }
    public void onRefundClosed(Map<String,Object>pay,long amount){db.update("update kg_wish set status='refund_failed' where wish_id=:id and pay_no=:n and status='refunding'",p("id",pay.get("bizId"),"n",pay.get("payNo")));}
    public List<Map<String,Object>> messages(long id,long user,long before){member(row(id,false),user);return messageViews(id,db.list("select m.message_id,m.content,m.image_ids,m.create_time,(m.user_id=:u) mine,u.nickname from kg_wish_message m join kg_app_user u on u.user_id=m.user_id where m.wish_id=:id and (:b=0 or m.message_id<:b) order by m.message_id desc limit 50",p("id",id,"u",user,"b",before)));}
    private List<Map<String,Object>> messageViews(long id,List<Map<String,Object>> rows) {
        for(Map<String,Object> message:rows){
            List<String> urls=new ArrayList<>();
            for(String imageId:split(message.remove("imageIds"))){
                Map<String,Object> image=db.one("select file_key from kg_wish_message_image where image_id=:image and wish_id=:wish",p("image",imageId,"wish",id));
                if(image!=null)urls.add(files.sign(str(image.get("fileKey")),600));
            }
            message.put("images",urls);
        }
        return rows;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> messageImage(long id,long user,MultipartFile file){
        Map<String,Object>w=row(id,true);member(w,user);state(w,"accepted","submitted","disputed","completed");
        if(file==null||file.isEmpty())throw new ServiceException("请选择图片",422);
        KgImageMagic.require(file,"jpg","png");
        if(db.count("select count(*) from kg_wish_message_image where wish_id=:id and user_id=:u and create_time>date_sub(now(),interval 1 day)",p("id",id,"u",user))>=100)
            throw new ServiceException("本订单今日上传图片已达上限，请明天再试",429);
        Map<String,Object>saved=files.save(file,"wishchat");String key=str(saved.get("key"));
        long imageId=db.insert("kg_wish_message_image",p("wish_id",id,"user_id",user,"file_key",key));
        return p("imageId",imageId,"url",files.sign(key,600));
    }
    private String messageImageIds(long id,long user,Object value){
        if(value==null)return "";
        if(!(value instanceof List)||((List<?>)value).size()>3)throw new ServiceException("每条消息最多3张图片",422);
        Set<String> ids=new LinkedHashSet<>();
        for(Object item:(List<?>)value){
            String imageId=str(item);
            if(!imageId.matches("[1-9][0-9]{0,18}")||!ids.add(imageId))throw new ServiceException("图片信息无效，请重新选择",422);
            if(db.count("select count(*) from kg_wish_message_image where image_id=:image and wish_id=:wish and user_id=:u",p("image",imageId,"wish",id,"u",user))!=1)
                throw new ServiceException("只能发送自己在本订单上传的图片",403);
        }
        return String.join(",",ids);
    }
    @Transactional(rollbackFor=Exception.class)
    public void message(long id,long user,Map<String,Object>b){
        Map<String,Object>w=row(id,true);member(w,user);state(w,"accepted","submitted","disputed","completed");
        String key=field(b,"requestKey",12,64),content=field(b,"content",0,1000),imageIds=messageImageIds(id,user,b.get("imageIds"));
        if(content.isEmpty()&&imageIds.isEmpty())throw new ServiceException("请填写消息或添加图片",422);
        Map<String,Object>old=db.one("select wish_id,content,image_ids from kg_wish_message where user_id=:u and request_key=:k",p("u",user,"k",key));
        if(old!=null){if(num(old.get("wishId"))!=id||!content.equals(str(old.get("content")))||!imageIds.equals(str(old.get("imageIds"))))throw new ServiceException("请求编号已使用",409);return;}
        if (WishEligibility.requestsChangedGoods(content) && (WishEligibility.inspect(content) & ~(int)num(w.get("reviewedRequirements"))) != 0)
            throw new ServiceException("补充内容涉及新的受限品类，请申请平台介入核实后再变更约定",409);
        db.insert("kg_wish_message",p("wish_id",id,"user_id",user,"request_key",key,"content",content,"image_ids",imageIds));
        Long recipient=owner(w,user)?KgJdbc.asLong(w.get("helperId")):KgJdbc.asLong(w.get("publisherId"));
        notices.send(recipient,"user","system","心愿有新消息","对方发来了消息，点击查看。","wish",id,"/pages/wish/order?id="+id);
    }
    public List<Map<String,Object>> credentials(Long user,boolean admin){
        List<Map<String,Object>> rows=db.list("select c.*,u.nickname from kg_wish_credential c join kg_app_user u on u.user_id=c.user_id"+(admin&&user==null?"":" where c.user_id=:u")+" order by c.credential_id desc limit 100",p("u",user));
        for(Map<String,Object>c:rows){c.put("url",files.sign(str(c.remove("fileKey")),600));}return rows;
    }
    @Transactional(rollbackFor=Exception.class)
    public void credential(long user,String type,String city,MultipartFile file){
        credential(user,type,city,"","","",file);
    }
    @Transactional(rollbackFor=Exception.class)
    public void credential(long user,String type,String city,String validFrom,String validUntil,String permitType,MultipartFile file){
        if(!Arrays.asList("health","food","permit","payout").contains(type))throw new ServiceException("请选择资质类型",422);
        if(city==null||city.length()>80||("permit".equals(type)&&city.trim().length()<2))throw new ServiceException("特殊许可请填写适用城市",422);
        if(db.count("select count(*) from kg_wish_credential where user_id=:u and create_time>date_sub(now(),interval 1 day)",p("u",user))>=10)throw new ServiceException("今日提交次数已达上限",429);
        Map<String,Object> values=credentialDetails(type,city,validFrom,validUntil,permitType);
        Map<String,Object>saved=files.save(file,"wish");values.putAll(p("user_id",user,"type",type,"file_key",saved.get("key")));
        db.insert("kg_wish_credential",values);
    }
    private Map<String,Object> credentialDetails(String type,String city,Object from,Object until,Object permitType) {
        if ("payout".equals(type)) return p("city","","valid_from",null,"valid_until",null,"permit_scope",0);
        LocalDate start,end;
        try { start=LocalDate.parse(str(from));end=LocalDate.parse(str(until)); }
        catch (RuntimeException error) { throw new ServiceException("请填写证件上的生效日期和有效期止日期",422); }
        if (end.isBefore(start)) throw new ServiceException("证件有效期止日期不能早于生效日期",422);
        int scope=0;
        if ("permit".equals(type)) {
            if (WishEligibility.normalized(city).length()<2||city.length()>80) throw new ServiceException("请填写许可实际适用城市",422);
            if ("tobacco".equals(permitType)) scope=WishEligibility.TOBACCO;
            else if ("alcohol".equals(permitType)) scope=WishEligibility.ALCOHOL;
            else if ("both".equals(permitType)) scope=WishEligibility.TOBACCO|WishEligibility.ALCOHOL;
            else throw new ServiceException("请明确许可证涵盖烟草、酒类或两者",422);
        }
        return p("city","permit".equals(type)?city.trim():"","valid_from",start.toString(),"valid_until",end.toString(),"permit_scope",scope);
    }
    public Map<String,Object> adminList(String status,int page){return db.page(SELECT+JOIN+(str(status).isEmpty()?"":" where w.status=:s")+" order by w.wish_id desc",p("s",status),page,20);}
    public Map<String,Object> adminDetail(long id){Map<String,Object>w=db.must(SELECT+JOIN+" where w.wish_id=:id",p("id",id),"心愿不存在");w.put("history",db.list("select * from kg_wish_audit where wish_id=:id order by audit_id",p("id",id)));w.put("messages",messageViews(id,db.list("select m.*,u.nickname from kg_wish_message m join kg_app_user u on u.user_id=m.user_id where m.wish_id=:id order by m.message_id desc limit 100",p("id",id))));
        w.put("informationOnly",WishEligibility.informationOnly(w));
        try {w.put("detectedTypes",WishEligibility.labels(requirements(w,false)));}
        catch(ServiceException blocked){w.put("eligibilityError",blocked.getMessage());}
        return w;}
    @Transactional(rollbackFor=Exception.class)
    public void reviewCredential(long id,boolean approved,String note,String operator){
        reviewCredential(id,approved,p("note",note),operator);
    }
    @Transactional(rollbackFor=Exception.class)
    public void reviewCredential(long id,boolean approved,Map<String,Object> body,String operator){
        Map<String,Object> credential=db.must("select * from kg_wish_credential where credential_id=:id for update",p("id",id),"资质不存在");
        String note=field(body,"note",1,500);
        Map<String,Object> values=p("status",approved?"approved":"rejected","note",note,"reviewer",operator);
        if(approved){
            if(!Boolean.TRUE.equals(body.get("documentChecked")))throw new ServiceException("请核实证件原件、有效期、适用城市与许可范围后确认",422);
            values.putAll(credentialDetails(str(credential.get("type")),str(body.get("city")),body.get("validFrom"),body.get("validUntil"),body.get("permitType")));
        }
        db.updateRow("kg_wish_credential","credential_id",id,values);
        String evidence=approved?" [有效期 "+values.get("valid_from")+" 至 "+values.get("valid_until")+"，城市 "+values.get("city")+"，许可范围 "+values.get("permit_scope")+"]":"";
        db.insert("kg_wish_audit",p("credential_id",id,"actor",operator,"action",approved?"credential_approved":"credential_rejected","note",truncate(evidence+" "+note,500)));
        // 审核结果告诉提交人：没过资质接不了单、收款资料没过平台登记不了打款，之前只能自己回页面看
        if(credential.get("userId")!=null){
            String what="payout".equals(str(credential.get("type")))?"收款资料":"资质";
            notices.send(num(credential.get("userId")),"user","system",what+(approved?"审核已通过":"审核未通过"),
                    approved?"你提交的"+what+"已审核通过。":"你提交的"+what+"未通过："+note+"。可修改后重新提交。","wish_credential",id,"/pages/wish/credential");
        }
    }
    private static String truncate(String value,int max){return value.length()>max?value.substring(0,max):value;}
    private void reviewFulfillment(Map<String,Object> wish,Map<String,Object> body){
        if(!Boolean.TRUE.equals(body.get("fulfillmentChecked"))||str(wish.get("proof")).isEmpty())
            throw new ServiceException("请核实实际交付凭证、交付日期及服务者当时有效的证照后确认",422);
        LocalDate date;
        try {date=LocalDate.parse(str(body.get("serviceDate")));}
        catch(RuntimeException error){throw new ServiceException("请填写实际交付日期",422);}
        LocalDate created=LocalDate.parse(str(wish.get("createTime")).substring(0,10));
        if(date.isBefore(created)||date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw new ServiceException("实际交付日期须在发布日至今天之间",422);
        Map<String,Object> delivered=db.one("select create_time from kg_wish_audit where wish_id=:id and action='deliver' order by audit_id desc limit 1",p("id",wish.get("wishId")));
        if(delivered!=null&&!date.equals(LocalDate.parse(str(delivered.get("createTime")).substring(0,10))))
            throw new ServiceException("交付日期与已记录的提交时间不一致，请核对原始交付凭证",422);
        int required=requirements(wish,true)|WishEligibility.deliveryRequirements(wish,wish.get("deliveryNote"));
        if((required & ~(int)num(wish.get("reviewedRequirements")))!=0)throw new ServiceException("请先复核交付内容对应的受限品类",409);
        String snapshot=qualificationSnapshot(num(wish.get("helperId")),required,str(wish.get("targetCity")),date);
        db.updateRow("kg_wish","wish_id",wish.get("wishId"),p("fulfillment_requirements",required,"fulfillment_qualification",snapshot));
    }
    @Transactional(rollbackFor=Exception.class)
    public void adminAction(long id,String action,Map<String,Object>b,String operator){
        Map<String,Object>w=row(id,false);if(w.get("payNo")!=null)payments.lockPayment(str(w.get("payNo")));w=row(id,true);
        String note=field(b,"note",1,500);
        switch(action){
        case "approve":state(w,"reviewing");saveReview(w,b);db.updateRow("kg_wish","wish_id",id,p("status","open","audit_note",note));break;
        case "classify":state(w,"open","accepted","submitted","disputed");saveReview(w,b);break;
        case "verifyFulfillment":state(w,"submitted","disputed");reviewFulfillment(w,b);break;
        case "reject":state(w,"reviewing","open","refund_failed");db.updateRow("kg_wish","wish_id",id,p("audit_note",note));refund(w,note,operator);break;
        case "refund":state(w,"disputed","refund_failed");refund(w,note,operator);break;
        case "resume":state(w,"disputed");qualifiedCredentials(num(w.get("helperId")),requirements(w,true),str(w.get("targetCity")),LocalDate.now(ZoneId.of("Asia/Shanghai")));db.updateRow("kg_wish","wish_id",id,p("status","accepted","audit_note",note));break;
        case "complete":state(w,"disputed");complete(w);break;
        case "payout":
            state(w,"completed");if(!"pending".equals(w.get("payoutStatus")))throw new ServiceException("无待打款金额或已登记打款",409);
            Map<String,Object> payment=payments.byNo(str(w.get("payNo")));
            if(!"paid".equals(payment.get("status"))||num(payment.get("refundAmount"))!=0||num(payment.get("amount"))!=num(w.get("amountFen")))
                throw new ServiceException("支付状态或实收金额异常，不能登记打款",409);
            String receipt=field(b,"receipt",6,100);
            if(!str(w.get("netFen")).equals(str(b.get("amountFen"))))throw new ServiceException("打款金额必须等于净结算金额",422);
            Map<String,Object>c=db.must("select credential_id from kg_wish_credential where user_id=:u and type='payout' and status='approved' order by credential_id desc limit 1 for update",p("u",w.get("helperId")),"服务者收款资料尚未审核通过");
            try {
                db.insert("kg_wish_payout",p("wish_id",id,"helper_id",w.get("helperId"),"amount_fen",w.get("netFen"),"receipt",receipt,"credential_id",c.get("credentialId"),"operator",operator));
            } catch (DuplicateKeyException error) {
                throw new ServiceException("该订单或流水号已登记打款，请刷新后核对", 409);
            }
            db.update("update kg_wish set payout_status='paid',payout_receipt=:r,paid_out_time=now() where wish_id=:id",p("r",receipt,"id",id));break;
        default:throw new ServiceException("不支持的审核操作",422);
        }
        // 核实范围、内容摘要（64 位哈希）只进审计记录；发给用户的消息用审核员写的原话，之前整段带着内部标记发出去
        String userNote=str(note).trim().isEmpty()?"平台已处理你的心愿，请打开查看最新状态":note;
        if(Arrays.asList("approve","classify").contains(action))note=truncate((Boolean.TRUE.equals(b.get("keywordMisjudged"))?"[关键词误判改判] ":"")+(Boolean.TRUE.equals(b.get("informationOnly"))?"[已核实为线上信息咨询，无实物交付] ":"")+"[人工核实范围 "+WishEligibility.labels((int)num(w.get("reviewedRequirements")))+"，内容摘要 "+w.get("reviewContentHash")+"] "+note,500);
        if("verifyFulfillment".equals(action))note=truncate("[人工核实交付日期 "+str(b.get("serviceDate"))+"] "+note,500);
        audit(id,operator,action,note);notify(row(id,false),"心愿处理结果已更新",userNote);
    }
}
