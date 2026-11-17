package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgMembershipOperations;

/** Explicitly selected evidence, processed within the headquarters transaction. */
@Service public class KgMerchantCaseEffects {
    @Autowired private KgJdbc db;
    @Autowired private KgMembershipOperations members;
    public List<Map<String,Object>> reviews(Long store){return db.list("select review_id,app_user_id,content,rating,status from kg_review where store_id=:s and del_flag='0' order by review_id desc limit 200",p("s",store));}
    public List<Map<String,Object>> linked(Long cid){return db.list("select l.review_id,l.member_case_id,r.content,r.app_user_id,m.status member_status,m.appeal,m.appeal_deadline from kg_merchant_case_review l join kg_review r on r.review_id=l.review_id left join kg_member_case m on m.case_id=l.member_case_id where l.case_id=:c order by l.review_id",p("c",cid));}
    public void attach(Long cid,Long store,String violation,Map<String,Object>b,String actor){
        List<Long> ids=ids(b.get("reviewIds"));require(!ids.isEmpty()&&ids.size()<=50,"请选择1至50条已核实的异常评价");
        Map<Long,Long> memberCases=new HashMap<>();
        for(Long rid:ids){
            Map<String,Object> r=must(db,"select review_id,app_user_id,store_id from kg_review where review_id=:r and del_flag='0' for update",p("r",rid),"关联评价不存在",404);
            require("competitor_attack".equals(violation)||store.equals(KgJdbc.asLong(r.get("storeId"))),"异常评价必须属于实际合作门店");
            Long uid=id(r.get("appUserId")),mid=null;
            if("collusion".equals(violation)){
                mid=memberCases.get(uid);
                if(mid==null){String facts="商家复核 #"+cid+" 涉及串通刷评。事实："+str(b.get("facts"))+"；依据："+str(b.get("basis"));
                    if(facts.length()>490)facts=facts.substring(0,490);
                    mid=members.notifyCase(p("userId",uid,"reviewId",rid,"severity",4,"note",facts),actor);memberCases.put(uid,mid);
                }
            }
            db.insert("kg_merchant_case_review",p("case_id",cid,"review_id",rid,"member_case_id",mid));
        }
    }
    public void apply(Long cid,boolean execute,Map<String,Object>b,String actor){
        List<Map<String,Object>> linked=linked(cid);Set<Long> done=new HashSet<>();
        for(Map<String,Object> r:linked){
            if(r.get("memberCaseId")!=null&&done.add(id(r.get("memberCaseId")))){
                require(execute||!"executed".equals(r.get("memberStatus")),"关联用户处罚已执行，须先由总部复核处置，不能直接撤销商家案件");
                if(execute){
                    require(Boolean.TRUE.equals(b.get("relatedReviewed")),"请核对关联用户事实、申诉与处理结果后确认联动执行");
                    require(!"cancelled".equals(r.get("memberStatus")),"关联用户复核已撤销，请重新核查串通事实");
                }
                if(!Arrays.asList("executed","cancelled").contains(r.get("memberStatus")))members.decideCase(id(r.get("memberCaseId")),p("execute",execute,"note","关联商家复核 #"+cid+"："+str(b.get("note")).substring(0,Math.min(450,str(b.get("note")).length()))),actor);
            }
            if(execute){
                db.update("update kg_review set status='1',featured='0',update_by=:a,update_time=now() where review_id=:r",p("r",r.get("reviewId"),"a",actor));
                // Quality badges must not keep counting a headquarters-revoked review.
                db.update("insert into kg_member_review_check(review_id,reason,revoked) values(:r,:n,'1') on duplicate key update revoked='1',quality='0',reason=:n",p("r",r.get("reviewId"),"n","商家复核 #"+cid+" 已撤销异常评价"));
            }
        }
    }
}
