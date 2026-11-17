package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.util.AccountState;

/** Durable entitlements. All spending/claiming serializes on the member account. */
@Service
public class KgMemberWallet {
    @Autowired private KgJdbc db;
    @Autowired private com.ruoyi.kangaroo.service.IKgMessageService messages;
    @Autowired private KgPointLedger ledger;
    @Autowired private KgMemberService members;
    @Autowired private com.ruoyi.kangaroo.ext.stay.KgStayBrowseService stays;
    public static long num(Object o){Long n=KgJdbc.asLong(o);return n==null?0:n;}
    public static String str(Object o){return o==null?"":String.valueOf(o);}
    public boolean enabled(){return db.count("select count(*) from kg_member_execution where id=1 and enabled='1'",p())>0;}
    public void active(Long uid){AccountState.requireActive(db,uid);if(!enabled())throw new ServiceException("会员权益暂未开放",409);if(db.count("select count(*) from kg_member_restriction where app_user_id=:u and frozen='1'",p("u",uid))>0)throw new ServiceException("会员权益已冻结，请查看违规通知或联系平台申诉",403);}
    public int level(Long uid){return (int)num(members.summaryFor(uid,ledger.lockedTotals(uid).earned).get("level"));}
    public static String key(Object value){String k=str(value);if(!k.matches("[A-Za-z0-9_-]{8,64}"))throw new ServiceException("请求编号无效，请重新提交",422);return k;}
    private String expiry(){return (KgMembershipPolicy.year()+1)+"-01-01 00:00:00";}
    private long issue(Long uid,String code,String source,String sourceKey,int cost,long amount){
        Map<String,Object> old=db.one("select wallet_id from kg_member_wallet where source_key=:k",p("k",sourceKey));if(old!=null)return num(old.get("walletId"));
        return db.insert("kg_member_wallet",p("app_user_id",uid,"issuer_user_id",uid,"entitlement_year",KgMembershipPolicy.year(),"benefit_code",code,"source",source,"source_key",sourceKey,"points_cost",cost,"amount_fen",amount,"status","available","expires_at",expiry()));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> wallet(Long uid){
        active(uid);
        db.update("update kg_member_wallet set status='expired' where app_user_id=:u and status='available' and expires_at<=:now",p("u",uid,"now",LocalDateTime.now(KgMembershipPolicy.ZONE)));
        List<Map<String,Object>> rows=db.list("select * from kg_member_wallet where app_user_id=:u order by wallet_id desc limit 300",p("u",uid));
        for(Map<String,Object> r:rows)r.put("label",KgMembershipPolicy.label(str(r.get("benefitCode"))));
        return p("rows",rows,"level",level(uid),"balance",ledger.lockedTotals(uid).balance(),"year",KgMembershipPolicy.year(),"allowances",KgMembershipPolicy.allowances(level(uid)),"usedNights",annualNights(uid),"requests",db.list("select request_id,wallet_id,request_type,status,content,review_note,create_time,reviewed_time from kg_member_request where app_user_id=:u order by request_id desc limit 100",p("u",uid)));
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> claim(Long uid){
        active(uid);int y=KgMembershipPolicy.year();
        for(Map.Entry<String,Integer> e:KgMembershipPolicy.allowances(level(uid)).entrySet()){
            String code=e.getKey();int count=e.getValue();
            if("subsidy".equals(code)){
                long granted=db.count("select coalesce(sum(amount_fen),0) from kg_member_wallet where issuer_user_id=:u and entitlement_year=:y and source='level' and benefit_code='subsidy'",p("u",uid,"y",y));
                if(granted<count)issue(uid,code,"level","level:"+uid+":"+y+":"+code+":"+count,0,count-granted);
            }else for(int i=1;i<=count;i++)issue(uid,code,"level","level:"+uid+":"+y+":"+code+":"+i,0,0);
        }
        return wallet(uid);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> redeem(Long uid,Map<String,Object> b){
        active(uid);String k=key(b.get("requestId")),code=str(b.get("roomCode"));int cost=KgMembershipPolicy.nightCost(code);int n=(int)num(b.get("nights"));
        if(n<1||n>20)throw new ServiceException("每次兑换1至20晚",422);
        String prefix="points:"+uid+":"+k+":";
        if(db.count("select count(*) from kg_member_wallet where source_key=:k",p("k",prefix+1))>0)return wallet(uid);
        if(ledger.lockedTotals(uid).balance()<(long)cost*n)throw new ServiceException("可用积分不足",409);
        for(int i=1;i<=n;i++){long id=issue(uid,code,"points",prefix+i,cost,0);ledger.spend(uid,cost,"兑换"+KgMembershipPolicy.label(code),"member_redeem",id);}
        return wallet(uid);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> undoRedeem(Long uid,Long id){
        active(uid);Map<String,Object> w=owned(uid,id);
        if("cancelled".equals(w.get("status")))return wallet(uid);
        available(w);
        if(!"points".equals(w.get("source")))throw new ServiceException("只有积分兑换且未使用的间夜可撤销",409);
        db.update("update kg_member_wallet set status='cancelled' where wallet_id=:id",p("id",id));
        ledger.award(uid,(int)num(w.get("pointsCost")),"撤销未使用间夜，退还积分","member_redeem_refund",id);
        return wallet(uid);
    }
    private Map<String,Object> owned(Long uid,Long id){Map<String,Object> r=db.one("select * from kg_member_wallet where wallet_id=:id and app_user_id=:u for update",p("id",id,"u",uid));if(r==null)throw new ServiceException("权益不存在",404);return r;}
    private void available(Map<String,Object> w){if(!"available".equals(w.get("status"))||str(w.get("expiresAt")).compareTo(LocalDateTime.now(KgMembershipPolicy.ZONE).toString().replace('T',' '))<=0)throw new ServiceException("权益已使用、已占用或已到期",409);}
    private long annualNights(Long uid){return db.list("select wallet_id from kg_member_wallet where app_user_id=:u and entitlement_year=:y and benefit_code in ('night4','night2','night1') and status in ('reserved','redeemed') for update",p("u",uid,"y",KgMembershipPolicy.year())).size();}
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> gift(Long uid,Map<String,Object> b){
        Long to=KgJdbc.asLong(b.get("recipientId"));List<Long> ids=ids(b.get("walletIds"));
        if(to==null||to.equals(uid)||ids.isEmpty()||ids.size()>3)throw new ServiceException("请选择一位亲友及1至3晚未使用间夜",422);
        // Always lock accounts in numeric order when transferring ownership.
        active(Math.min(uid,to));active(Math.max(uid,to));
        if(level(uid)<5)throw new ServiceException("最高等级可使用年度转赠权益",403);
        int y=KgMembershipPolicy.year();if(db.count("select count(*) from kg_member_gift where app_user_id=:u and entitlement_year=:y",p("u",uid,"y",y))>0)throw new ServiceException("本年度已经使用过转赠权益",409);
        for(Long id:ids){Map<String,Object> w=owned(uid,id);available(w);if(!str(w.get("benefitCode")).startsWith("night")||!uid.equals(KgJdbc.asLong(w.get("issuerUserId"))))throw new ServiceException("仅可转赠自己领取或兑换的免费间夜",422);}
        db.insert("kg_member_gift",p("app_user_id",uid,"recipient_id",to,"entitlement_year",y,"nights",ids.size()));
        for(Long id:ids)db.update("update kg_member_wallet set app_user_id=:to,source='gift' where wallet_id=:id",p("to",to,"id",id));
        return wallet(uid);
    }
    public static List<Long> ids(Object o){List<Long> out=new ArrayList<>();if(o==null)return out;if(!(o instanceof List)||((List<?>)o).size()>20)throw new ServiceException("权益选择无效",422);for(Object x:(List<?>)o){Long id=KgJdbc.asLong(x);if(id==null||id<=0||out.contains(id))throw new ServiceException("权益选择重复或无效",422);out.add(id);}return out;}
    /** Called both for quote and, inside the order transaction, immediately before reserving. */
    public void applyStay(Long uid,Long roomId,Map<String,Object> q,List<Long> ids,boolean floor){
        if(ids.isEmpty()&&!floor)return;active(uid);
        Map<String,Object> room=db.must("select member_room_code,member_price_fen,room_kind,unit_label,type_name,capacity from kg_stay_room_type where room_type_id=:r",p("r",roomId),"房型不存在");
        if(q.get("promoId")!=null)throw new ServiceException("会员权益不可与早鸟优惠叠加",422);
        long roomFen=num(q.get("roomFen")),discount=0;String mode="floor";
        if(floor){if(!ids.isEmpty())throw new ServiceException("会员底价与免费间夜不可同时使用",422);if(level(uid)<2||num(room.get("memberPriceFen"))<=0)throw new ServiceException("该房型暂不适用会员底价",403);long total=num(room.get("memberPriceFen"))*num(q.get("units"))*num(q.get("nights"));if(total>roomFen-num(q.get("discountFen")))throw new ServiceException("当前日常价更低，请使用日常价",409);discount=roomFen-total;}
        else{
            mode="nights";
            if(num(q.get("guests"))!=1||num(q.get("units"))!=1)throw new ServiceException("免费间夜仅限会员本人入住，请选择1位入住人",422);
            LocalDate in=LocalDate.parse(str(q.get("checkIn"))),out=LocalDate.parse(str(q.get("checkOut")));
            if(in.getYear()!=KgMembershipPolicy.year()||out.isAfter(LocalDate.of(in.getYear()+1,1,1)))throw new ServiceException("免费间夜只能用于本年度入住",422);
            if(db.count("select count(*) from kg_member_calendar_year where calendar_year=:y and confirmed='1'",p("y",in.getYear()))==0)throw new ServiceException("本年度节假日尚未配置，请联系平台后使用免费间夜",409);
            if(db.count("select count(*) from kg_member_holiday where holiday_date>=:a and holiday_date<:b",p("a",in,"b",out))>0)throw new ServiceException("法定节假日不可使用免费间夜，请分开预订",422);
            if(ids.size()>num(q.get("nights"))||annualNights(uid)+ids.size()>20)throw new ServiceException("超出订单晚数或年度20晚免费入住上限",422);
            for(Long id:ids){Map<String,Object> w=owned(uid,id);available(w);if(!Objects.equals(w.get("benefitCode"),KgMembershipPolicy.roomCode(room)))throw new ServiceException("免费间夜与所选房型不匹配",422);}
            List<?> nightly=(List<?>)q.get("nightly");for(int i=0;i<ids.size();i++)discount+=num(((Map<?,?>)nightly.get(i)).get("priceFen"));
        }
        q.put("discountFen",discount);q.put("discountLabel",floor?"会员底价":"会员免费间夜（"+ids.size()+"晚）");q.put("memberMode",mode);q.put("memberDiscountFen",discount);q.put("payFen",Math.max(0,roomFen-discount)+num(q.get("addonFen"))+num(q.get("serviceFeeFen")));
    }
    public void reserveStay(Long uid,Long orderId,List<Long> ids){for(Long id:ids)if(db.update("update kg_member_wallet set status='reserved',order_id=:o where wallet_id=:w and app_user_id=:u and status='available'",p("o",orderId,"w",id,"u",uid))!=1)throw new ServiceException("权益已被使用，请重新选择",409);}
    public void releaseStay(Long orderId){db.update("update kg_member_wallet set status=if(expires_at>now(),'available','expired'),order_id=null where order_id=:o and status='reserved'",p("o",orderId));}
    public void consumeStay(Long orderId){db.update("update kg_member_wallet set status='redeemed',used_by='stay-checkin',used_time=now() where order_id=:o and status='reserved'",p("o",orderId));}
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> quote(Long uid,Map<String,Object> b){
        Long room=KgJdbc.asLong(b.get("roomTypeId"));
        Map<String,Object> q=stays.quote(room,str(b.get("checkIn")),str(b.get("checkOut")),Math.max(1,(int)num(b.get("guests"))),KgJdbc.asLong(b.get("promoId")));
        List<Long> selected=ids(b.get("memberWalletIds"));boolean floor=Boolean.TRUE.equals(b.get("memberFloor"));
        if(!enabled()){if(!selected.isEmpty()||floor)throw new ServiceException("会员权益暂未开放",409);return q;}
        if(uid!=null){
            if(db.count("select count(*) from kg_member_restriction where app_user_id=:u and frozen='1'",p("u",uid))>0 && selected.isEmpty()&&!floor)return q;
            active(uid);applyStay(uid,room,q,selected,floor);
            Map<String,Object> rt=db.must("select member_room_code,member_price_fen,room_kind,unit_label,type_name,capacity from kg_stay_room_type where room_type_id=:r",p("r",room),"房型不存在");
            String roomCode=KgMembershipPolicy.roomCode(rt);
            q.put("memberRoomCode",roomCode);q.put("memberFloorAvailable",level(uid)>=2&&num(rt.get("memberPriceFen"))>0);
            q.put("memberVouchers",db.list("select wallet_id,benefit_code,expires_at from kg_member_wallet where app_user_id=:u and benefit_code=:c and status='available' and expires_at>now() order by wallet_id",p("u",uid,"c",roomCode)));
        }else if(!selected.isEmpty()||floor)throw new ServiceException("请登录后使用会员权益",401);
        return q;
    }
    public long activityVoucher(Long uid,Long activityId,Long walletId){
        if(walletId==null)return 0;active(uid);
        Map<String,Object> a=db.must("select member_free from kg_activity where activity_id=:a",p("a",activityId),"活动不存在");
        if(!"1".equals(a.get("memberFree")))throw new ServiceException("此活动未开放会员免费权益",422);
        Map<String,Object> w=owned(uid,walletId);available(w);if(!"activity".equals(w.get("benefitCode")))throw new ServiceException("请选择免费活动权益",422);
        return walletId;
    }
    public void reserveActivity(Long uid,Long id){if(id!=null&&id>0&&db.update("update kg_member_wallet set status='reserved' where wallet_id=:id and app_user_id=:u and status='available'",p("id",id,"u",uid))!=1)throw new ServiceException("活动权益已被占用",409);}
    /** Both user cancellation and organizer cancellation are reconciled; attended events are final. */
    @Transactional(rollbackFor=Exception.class)
    public void reconcileActivities(){
        reconcileActivities(null);
    }
    @Transactional(rollbackFor=Exception.class)
    public void reconcileActivities(Long uid){
        String scope=uid==null?"":" and w.app_user_id=:u";
        db.update("update kg_member_wallet w join kg_activity_signup s on s.member_wallet_id=w.wallet_id and s.app_user_id=w.app_user_id set w.status='redeemed',w.used_by='activity-checkin',w.used_time=s.checkin_time where w.benefit_code='activity' and w.status='reserved' and s.checkin_time is not null"+scope,p("u",uid));
        // A cancelled signup remains in history after its voucher is used for another event.
        // Release only when NO live signup still owns the reservation; never let old history
        // turn a currently booked entitlement back into an available one.
        db.update("update kg_member_wallet w join kg_activity_signup s on s.member_wallet_id=w.wallet_id and s.app_user_id=w.app_user_id "
                +"left join kg_activity_signup live on live.member_wallet_id=w.wallet_id and live.app_user_id=w.app_user_id and (live.checkin_time is not null or live.status not in ('cancelled','refunded','closed')) "
                +"set w.status=if(w.expires_at>now(),'available','expired') where w.benefit_code='activity' and w.status='reserved' and s.checkin_time is null and s.status in ('cancelled','refunded','closed') and live.signup_id is null"+scope,p("u",uid));
    }
    @Transactional(rollbackFor=Exception.class)
    public void cancelRequest(Long uid,Long id){active(uid);Map<String,Object> r=db.must("select * from kg_member_request where request_id=:r and app_user_id=:u for update",p("r",id,"u",uid),"申请不存在");if(!"pending".equals(r.get("status")))throw new ServiceException("已处理的申请不能撤回",409);db.update("update kg_member_request set status='cancelled' where request_id=:id",p("id",id));db.update("update kg_member_wallet set status=if(expires_at>now(),'available','expired') where wallet_id=:w and status='reserved'",p("w",r.get("walletId")));}
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> request(Long uid,Map<String,Object> b){
        active(uid);String k=key(b.get("requestId"));Map<String,Object> prior=db.one("select request_id from kg_member_request where app_user_id=:u and request_key=:k",p("u",uid,"k",k));if(prior!=null)return prior;
        Long id=KgJdbc.asLong(b.get("walletId"));String content=str(b.get("content")).trim();if(content.length()<2||content.length()>1000)throw new ServiceException("请填写2至1000字使用说明",422);
        String type="support";
        if(id!=null){Map<String,Object> w=owned(uid,id);available(w);type=str(w.get("benefitCode"));if(type.startsWith("night")||"activity".equals(type))throw new ServiceException("免费间夜和活动权益请在对应订单中使用",422);db.update("update kg_member_wallet set status='reserved' where wallet_id=:w",p("w",id));}
        else {
            Long benefitId=KgJdbc.asLong(b.get("benefitId"));
            if(benefitId!=null){
                Map<String,Object> benefit=members.benefit(uid,benefitId);
                if(!Boolean.TRUE.equals(benefit.get("eligible")))throw new ServiceException("当前等级暂不适用该店铺权益",403);
                type="merchant";
                content="合作店权益 #"+benefitId+" · "+str(benefit.get("merchantName"))+"\n"+str(benefit.get("title"))+" · "+str(benefit.get("discountText"))+"\n"+content;
            }else{
                type=str(b.get("serviceType"));if(type.isEmpty())type="support";
                int min="recommend".equals(type)?1:"booking".equals(type)?4:"support".equals(type)?5:99;
                if(level(uid)<min)throw new ServiceException("当前等级尚未解锁此项服务",403);
            }
        }
        return p("requestId",db.insert("kg_member_request",p("app_user_id",uid,"wallet_id",id,"request_key",k,"request_type",type,"content",content,"status","pending")));
    }
    @Transactional(rollbackFor=Exception.class)
    public void fulfill(Long requestId,boolean approve,String note,String proof,String operator){
        Map<String,Object> r=db.must("select * from kg_member_request where request_id=:id for update",p("id",requestId),"申请不存在");
        if(!"pending".equals(r.get("status")))throw new ServiceException("申请已处理",409);
        if(note==null||note.trim().length()<2||note.length()>500)throw new ServiceException("请填写处理说明",422);
        if(approve&&(proof==null||proof.trim().isEmpty()||proof.length()>1000))throw new ServiceException("请填写发货单号、服务记录或补贴支付凭证后再核销",422);
        Long w=KgJdbc.asLong(r.get("walletId"));if(w!=null){int n=db.update("update kg_member_wallet set status=if(:approved=1,'redeemed',if(expires_at>now(),'available','expired')),used_by=:by,used_time=:t where wallet_id=:w and status='reserved'",p("approved",approve?1:0,"by",approve?operator:null,"t",approve?LocalDateTime.now(KgMembershipPolicy.ZONE):null,"w",w));if(n!=1)throw new ServiceException("权益状态已变化，不能重复核销",409);}
        db.update("update kg_member_request set status=:s,review_note=:n,proof=:p,reviewed_by=:by,reviewed_time=now() where request_id=:id",p("s",approve?"completed":"rejected","n",note,"p",proof,"by",operator,"id",requestId));
        messages.send(KgJdbc.asLong(r.get("appUserId")),"user","system",approve?"会员权益已履约":"会员权益申请需补充",note,"member_request",requestId,"/pages/profile/member-wallet");
    }
}
