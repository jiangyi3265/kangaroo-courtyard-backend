package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.str;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KgStayActivitySelection
{
    @Autowired private KgJdbc db;
    @Autowired @Lazy private KgActivityService activities;

    /** 与住宿订单在同一事务持久化选择；不预留活动名额，也不替用户同意活动须知。 */
    public void record(Long orderId, Long uid, String city, String checkIn, String checkOut, Object value)
    {
        if (value == null) return;
        if (!(value instanceof List) || ((List<?>)value).size()>10) throw new ServiceException("附选活动最多10个",422);
        Set<Long> ids=new LinkedHashSet<>();
        for(Object raw:(List<?>)value)
        {
            Long id;
            try { id=new java.math.BigDecimal(String.valueOf(raw)).longValueExact(); }
            catch(RuntimeException e) { throw new ServiceException("附选活动参数不合法",422); }
            if(id==null || id<=0) throw new ServiceException("附选活动参数不合法",422);
            ids.add(id);
        }
        for(Long id:ids)
        {
            Map<String,Object> a=db.one("select title from kg_activity where activity_id=:id and city=:c and del_flag='0' and status='open' and fee_fen=0 and fee_mode<>'aa' "
                    + "and date(start_time)>=:a and date(start_time)<=:b and start_time>now() and (deadline is null or deadline>now())",p("id",id,"c",city,"a",checkIn,"b",checkOut));
            if(a==null) throw new ServiceException("附选活动已变更，请重新选择入住期间的同城免费活动",409);
            db.insert("kg_stay_activity_selection",p("order_id",orderId,"activity_id",id,"app_user_id",uid,"title",a.get("title"),
                    "status","pending","create_time",new Date(),"update_time",new Date()));
        }
    }

    public List<Map<String,Object>> list(Object orderId)
    {
        return db.list("select s.activity_id,s.title,s.status,s.signup_id,g.status signup_status from kg_stay_activity_selection s "
                + "left join kg_activity_signup g on g.signup_id=s.signup_id where s.order_id=:id order by s.activity_id",p("id",orderId));
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> enroll(Long uid, String orderNo, Long activityId)
    {
        Map<String,Object> order=db.one("select order_id,stay_id,check_in,check_out,status from kg_stay_order where order_no=:n and app_user_id=:u and del_flag='0' for share",p("n",orderNo,"u",uid));
        if(order==null) throw new ServiceException("订单不存在",403);
        Map<String,Object> selection=db.one("select * from kg_stay_activity_selection where order_id=:o and activity_id=:a for update",p("o",order.get("orderId"),"a",activityId));
        if(selection==null) throw new ServiceException("这项活动不在订单的附选记录中",404);
        if("completed".equals(selection.get("status"))) return p("message","这项活动已处理，可到我的活动查看最新状态","selections",list(order.get("orderId")));
        if(!Arrays.asList("confirmed","checked_in").contains(str(order.get("status")))) throw new ServiceException("请在住宿付款成功且订单有效时继续报名",409);
        // AA制（2026-11-03）也不算免费：现场要平摊费用，不能随住宿订单悄悄替人报上
        Map<String,Object> a=db.one("select fee_fen, fee_mode from kg_activity where activity_id=:id and del_flag='0' "
                + "and city=(select city from kg_stay where stay_id=:s) and date(start_time)>=:a and date(start_time)<=:b for update",
                p("id",activityId,"s",order.get("stayId"),"a",order.get("checkIn"),"b",order.get("checkOut")));
        if(a==null || ((Number)a.get("feeFen")).longValue()!=0 || "aa".equals(a.get("feeMode")))
            throw new ServiceException("活动已变更，附选报名不会代付额外费用，请打开活动详情核对",409);
        Map<String,Object> old=db.one("select signup_id,status from kg_activity_signup where activity_id=:a and app_user_id=:u for update",p("a",activityId,"u",uid));
        Map<String,Object> result;
        if(old!=null && Arrays.asList("confirmed","waitlist").contains(str(old.get("status")))) result=p("message","你已报名该活动","signupId",old.get("signupId"));
        else result=activities.signup(uid,activityId,"home");
        Map<String,Object> signup=db.must("select signup_id,status from kg_activity_signup where activity_id=:a and app_user_id=:u for update",p("a",activityId,"u",uid),"活动报名未保存");
        db.update("update kg_stay_activity_selection set status='completed',signup_id=:s,update_time=now() where order_id=:o and activity_id=:a",
                p("s",signup.get("signupId"),"o",order.get("orderId"),"a",activityId));
        return p("message",result.get("message"),"selections",list(order.get("orderId")));
    }
}
