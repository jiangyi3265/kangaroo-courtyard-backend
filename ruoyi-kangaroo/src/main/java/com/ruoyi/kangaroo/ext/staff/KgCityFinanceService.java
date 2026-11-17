package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.staff.StaffSupport.str;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 城市负责人的财务和全城数字（角色切换后才开放）：
 *   活动退款审批、取消收费活动：限他能管的门店（本城全部，CityLeadScope；登录态已经展开）；别的城市走平台后台。
 *   全城数字：本城门店数、今日报名、在招岗位、在住人数，只读，不露个人信息。
 * 旅居和服务订单退款继续只走平台后台；线上任务付款继续合作商家、商家服务人登记。
 */
@Service
public class KgCityFinanceService
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgActivityService activities;

    @Autowired
    private KgMerchantRequestService requests;

    private static void requireSwitched()
    {
        if (!RolePhase.isNew())
        {
            throw new ServiceException("这项功能要等角色切换后才开放", HttpStatus.FORBIDDEN);
        }
    }

    /** 这个账号担任城市负责人的门店（登录态每个请求都从库里重读）；传了 storeId 就必须是其中一家 */
    private List<Long> leadStores(Long storeId)
    {
        requireSwitched();
        AppLoginUser me = AppUserContext.requireStaff();
        List<Long> ids = new ArrayList<Long>();
        for (StaffGrantView g : me.getStaffGrants())
        {
            if (g.getStoreId() != null && StaffRole.CITY_LEAD.equals(g.getRoleCode()) && (storeId == null || storeId.equals(g.getStoreId())))
            {
                ids.add(g.getStoreId());
            }
        }
        if (ids.isEmpty())
        {
            throw new ServiceException("这项功能只有这家店的城市负责人可以使用", HttpStatus.FORBIDDEN);
        }
        return ids;
    }

    /** 活动所在门店上必须是城市负责人；别家门店的角色借不了。控制器在事务外调用，拒绝时的失败日志不随业务回滚 */
    public StaffSupport.Ctx ctxOfActivity(Long activityId, String opAction, String opLabel)
    {
        requireSwitched();
        Map<String, Object> a = db.one("select store_id from kg_activity where activity_id = :a and del_flag = '0'", p("a", activityId));
        if (a == null || a.get("storeId") == null)
        {
            throw new ServiceException("活动不存在或不是门店活动", 404);
        }
        StaffSupport.Ctx c = support.ctx(KgJdbc.asLong(a.get("storeId")));
        if (!StaffRole.CITY_LEAD.equals(c.role))
        {
            support.op(c, opAction, opLabel, "fund", String.valueOf(activityId), false, "不是这家店的城市负责人");
            throw new ServiceException("只有这家店的城市负责人可以做这项操作（当前角色：" + StaffRole.label(c.role) + "）", HttpStatus.FORBIDDEN);
        }
        return c;
    }

    /** 报名记录要属于这场活动所在门店；自己的报名不能自己审 */
    public StaffSupport.Ctx ctxOfSignup(Long signupId, String opAction, String opLabel)
    {
        requireSwitched();
        Map<String, Object> s = db.one("select activity_id, app_user_id from kg_activity_signup where signup_id = :s", p("s", signupId));
        if (s == null)
        {
            throw new ServiceException("报名记录不存在", 404);
        }
        StaffSupport.Ctx c = ctxOfActivity(KgJdbc.asLong(s.get("activityId")), opAction, opLabel);
        if (c.uid().equals(KgJdbc.asLong(s.get("appUserId"))))
        {
            support.op(c, opAction, opLabel, "fund", String.valueOf(signupId), false, "不能审批自己的报名");
            throw new ServiceException("这是你自己的报名，请让平台处理", HttpStatus.FORBIDDEN);
        }
        return c;
    }

    private static String yuan(Object fen)
    {
        return BigDecimal.valueOf(StaffSupport.toInt(fen, 0), 2).stripTrailingZeros().toPlainString();
    }

    /** 待审批的活动退款（他有授权的门店，或指定其中一家） */
    public Map<String, Object> refunds(Long storeId, Integer pageNum, Integer pageSize)
    {
        List<Long> stores = leadStores(storeId);
        Map<String, Object> page = db.page("select g.signup_id, g.activity_id, g.name, g.phone, g.fee_fen, g.refund_fen, g.cancel_reason, g.cancel_time, g.app_user_id, "
                + "a.title activity_title, a.start_time, a.store_id, s.store_name, s.city from kg_activity_signup g "
                + "join kg_activity a on a.activity_id = g.activity_id join kg_store s on s.store_id = a.store_id "
                + "where g.status = 'refund_pending' and a.del_flag = '0' and a.store_id in (:ids) order by g.cancel_time, g.signup_id", p("ids", stores), pageNum, pageSize);
        AppLoginUser me = AppUserContext.requireStaff();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        for (Map<String, Object> r : rows)
        {
            r.put("name", MaskUtils.name(str(r.get("name"))));
            r.put("phone", MaskUtils.phone(str(r.get("phone"))));
            r.put("refundYuan", yuan(r.get("refundFen")));
            r.put("mine", me.getUserId().equals(KgJdbc.asLong(r.get("appUserId"))));
            r.remove("appUserId");
        }
        page.put("storeIds", stores);
        return page;
    }

    /** 可以由他取消的活动：还没开始、没结束，收费或已有报名（没人报名的免费活动换工主理人自己就能取消） */
    public Map<String, Object> cancellable(Long storeId, Integer pageNum, Integer pageSize)
    {
        List<Long> stores = leadStores(storeId);
        Map<String, Object> page = db.page("select a.activity_id, a.title, a.start_time, a.status, a.fee_fen, a.signed, a.store_id, s.store_name, s.city from kg_activity a "
                + "join kg_store s on s.store_id = a.store_id where a.del_flag = '0' and a.store_id in (:ids) and a.status in ('open','paused','closed') "
                + "and a.start_time > now() and (a.fee_fen > 0 or a.signed > 0) order by a.start_time, a.activity_id", p("ids", stores), pageNum, pageSize);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        for (Map<String, Object> r : rows)
        {
            r.put("feeYuan", yuan(r.get("feeFen")));
        }
        page.put("storeIds", stores);
        return page;
    }

    public String approve(StaffSupport.Ctx c, Long signupId)
    {
        String msg = activities.approveRefund(signupId, "staff:" + c.uid());
        support.op(c, "activity_refund", "活动退款审批", "fund", signupId + "：" + msg, true, null);
        return msg;
    }

    public String reject(StaffSupport.Ctx c, Long signupId, String reason)
    {
        String msg = activities.rejectRefund(signupId, reason, "staff:" + c.uid());
        support.op(c, "activity_refund", "活动退款审批", "fund", signupId + "：" + msg, true, null);
        return msg;
    }

    public String cancel(StaffSupport.Ctx c, Long activityId, String reason)
    {
        // 门店归属 ctxOfActivity 已经查过，这里再对一次
        Map<String, Object> a = db.must("select * from kg_activity where activity_id = :a and del_flag = '0'", p("a", activityId), "活动不存在");
        if (!c.storeId.equals(KgJdbc.asLong(a.get("storeId"))))
        {
            throw new ServiceException("活动不存在或没有权限", HttpStatus.FORBIDDEN);
        }
        if (StaffSupport.toInt(a.get("feeFen"), 0) <= 0 && StaffSupport.toInt(a.get("signed"), 0) <= 0)
        {
            throw new ServiceException("这场活动不收费也没人报名，请换工主理人或小院主理人取消", 409);
        }
        if (reason == null || reason.trim().isEmpty())
        {
            throw new ServiceException("请填写取消原因，会发给报名人", 422);
        }
        String msg = activities.transition(a, "cancel", reason.trim(), "staff:" + c.uid(), true);
        support.op(c, "activity_cancel", "取消已有报名的活动", "fund", activityId + "：" + msg, true, null);
        return msg;
    }

    /** 本城数字（门店城市归一后相同，和派单同一口径）。只有数字 */
    public Map<String, Object> summary(Long storeId)
    {
        requireSwitched();
        StaffSupport.Ctx c = requests.cityCtx(storeId);
        String town = requests.townOf(c);
        List<Long> stores = new ArrayList<Long>();
        for (Map<String, Object> s : db.list("select store_id, city from kg_store where status = '0' and del_flag = '0'", p()))
        {
            if (town.equals(requests.normalCity(s.get("city"))))
            {
                stores.add(KgJdbc.asLong(s.get("storeId")));
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("city", town);
        out.put("storeId", c.storeId);
        out.put("storeCount", stores.size());
        Map<String, Object> ps = p("ids", stores);
        boolean none = stores.isEmpty();
        out.put("todayApplications", none ? 0L : db.count("select count(*) from kg_application where store_id in (:ids) and del_flag = '0' and submit_time >= curdate()", ps));
        out.put("openJobs", none ? 0L : db.count("select count(*) from kg_job where store_id in (:ids) and del_flag = '0' and status = 'published'", ps));
        out.put("inHouseGuests", none ? 0L : db.count("select coalesce(sum(guests), 0) from kg_stay_order where store_id in (:ids) and del_flag = '0' and status = 'checked_in'", ps));
        return out;
    }
}
