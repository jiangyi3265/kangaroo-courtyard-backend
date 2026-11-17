package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.manage.KgStats;

/**
 * 小院主理人审核本店活动（2026-11-03 主人：「小院主理人，审核活动」）。
 *
 * 原来门店活动提交后只有平台后台能审（合作商家的活动另有本城商家服务人审，KgMerchantRequestService.serviceActivityAudit）；
 * 现在本店的小院主理人也能审，平台和商家服务人照旧能审，谁先点算谁（最后都走 KgActivityService.audit：只认 pending，晚到的 409）。
 * 城市负责人有全部二级角色的权限（StaffPerms NEW 表），他在能以城市负责人身份操作的门店上也能审。
 *
 * 边界（服务端把住，页面只是不给按钮）：
 *   只看、只审自己当前门店的活动（不是本店的 403）；自己发起的、最后是自己保存的不能审（自己不能审自己）；
 *   通过前再过一遍内容检查：含联系方式或需要人工把关的词，只能驳回或交给平台，小院主理人不能放行（驳回不受限）；
 *   开始时间已过的不能通过（audit 里挡）。
 */
@Service
public class KgKeeperActivityReview
{
    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgActivityService activities;

    @Autowired
    private KgCommunityGuard guard;

    /** 能用这个功能的角色：本店的小院主理人，或顶上的城市负责人；还要有「活动管理·审核」 */
    public static boolean canReview(String role)
    {
        return (StaffRole.KEEPER.equals(role) || StaffRole.CITY_LEAD.equals(role)) && StaffPerms.allows(role, "activities", StaffPerms.AUDIT);
    }

    /** 控制器在事务外调用，拒绝时的失败日志不随业务回滚 */
    public StaffSupport.Ctx ctx(Long storeId)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        if (!canReview(c.role))
        {
            support.op(c, "keeper_activity_audit", "审核本店活动", "general", null, false, "角色不符");
            throw new ServiceException("审核本店活动只有小院主理人可以做（当前角色：" + StaffRole.label(c.role) + "）", HttpStatus.FORBIDDEN);
        }
        return c;
    }

    private static String mine(StaffSupport.Ctx c)
    {
        return "staff:" + c.uid();
    }

    /** 本店待审核的活动（不含自己发起、自己最后保存的），按提交先后 */
    public Map<String, Object> pending(StaffSupport.Ctx c, Integer pageNum, Integer pageSize)
    {
        Map<String, Object> page = db.page("select a.activity_id, a.title, a.cover, a.category, a.start_time, a.end_time, a.deadline, a.city, a.place, a.fee_fen, a.fee_mode, "
                + "a.fee_includes, a.capacity, a.intro, a.safety_note, a.store_id, a.source, a.status, a.create_time, a.update_time, s.store_name, a.organizer_name, u.nickname creator "
                + "from kg_activity a join kg_store s on s.store_id = a.store_id left join kg_app_user u on u.user_id = a.app_user_id "
                + "where a.store_id = :s and a.del_flag = '0' and a.status = 'pending' and (a.app_user_id is null or a.app_user_id <> :me) "
                + "and (a.update_by is null or a.update_by <> :meBy) order by coalesce(a.update_time, a.create_time), a.activity_id",
                p("s", c.storeId, "me", c.uid(), "meBy", mine(c)), pageNum, pageSize);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) page.get("rows"))
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) raw;
            rows.add(view(a));
        }
        page.put("rows", rows);
        page.put("storeName", c.storeName);
        return page;
    }

    private Map<String, Object> view(Map<String, Object> a)
    {
        Long fee = KgJdbc.asLong(a.get("feeFen"));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (String k : new String[] { "activityId", "title", "cover", "category", "startTime", "endTime", "deadline", "city", "place", "feeMode", "feeIncludes",
                "capacity", "intro", "safetyNote", "storeId", "storeName", "organizerName", "status" })
        {
            m.put(k, a.get(k));
        }
        m.put("feeFen", fee == null ? 0L : fee);
        m.put("priceText", fee == null || fee == 0L ? "免费" : KgStats.yuanText(fee));
        m.put("creator", KgJdbc.asStr(a.get("creator")) == null || KgJdbc.asStr(a.get("creator")).isEmpty() ? a.get("organizerName") : a.get("creator"));
        m.put("submitTime", a.get("updateTime") == null ? a.get("createTime") : a.get("updateTime"));
        m.put("statusLabel", "待审核");
        // 内容检查要人工把关的，小院主理人只能驳回：页面据此不给「通过」
        m.put("needsPlatform", !"pass".equals(level(a)));
        return m;
    }

    private String level(Map<String, Object> a)
    {
        try
        {
            return guard.check(KgJdbc.asStr(a.get("title")), KgJdbc.asStr(a.get("intro")), KgJdbc.asStr(a.get("place")), KgJdbc.asStr(a.get("safetyNote")));
        }
        catch (ServiceException e)
        {
            return "block";
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> audit(StaffSupport.Ctx c, Long id, Boolean pass, String reason)
    {
        Map<String, Object> a = db.one("select activity_id, store_id, app_user_id, update_by, title, intro, place, safety_note, status from kg_activity "
                + "where activity_id = :id and del_flag = '0' for update", p("id", id));
        if (a == null)
        {
            throw new ServiceException("活动不存在", 404);
        }
        if (!c.storeId.equals(KgJdbc.asLong(a.get("storeId"))))
        {
            throw new ServiceException("这个活动不是本店的，不能审核", HttpStatus.FORBIDDEN);
        }
        if (!"pending".equals(KgJdbc.asStr(a.get("status"))))
        {
            throw new ServiceException("这个活动已经处理过了，请刷新后查看", HttpStatus.CONFLICT);
        }
        if (c.uid().equals(KgJdbc.asLong(a.get("appUserId"))) || mine(c).equals(KgJdbc.asStr(a.get("updateBy"))))
        {
            throw new ServiceException("自己发起或最后修改的活动不能自己审核，请交给平台", HttpStatus.FORBIDDEN);
        }
        if (pass == null)
        {
            throw new ServiceException("请选择通过或驳回", 422);
        }
        String why = reason == null ? "" : reason.trim();
        if (!pass && (why.isEmpty() || why.length() > 255))
        {
            throw new ServiceException("请填写驳回原因（255 字内），发起人会看到", 422);
        }
        if (pass && !"pass".equals(level(a)))
        {
            throw new ServiceException("这场活动的内容含联系方式或需要平台把关的词语，小院主理人不能直接通过；可以驳回让对方修改，或等平台审核", HttpStatus.CONFLICT);
        }
        activities.audit(id, pass, pass ? null : why, mine(c));
        String title = KgJdbc.asStr(a.get("title"));
        support.op(c, "keeper_activity_audit", pass ? "通过本店活动" : "驳回本店活动", "general", pass ? title : title + "：" + why, true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("activityId", id);
        out.put("status", pass ? "open" : "rejected");
        out.put("statusLabel", pass ? "报名中" : "已驳回");
        out.put("message", pass ? "已通过，开始报名" : "已驳回并通知发起人");
        return out;
    }
}
