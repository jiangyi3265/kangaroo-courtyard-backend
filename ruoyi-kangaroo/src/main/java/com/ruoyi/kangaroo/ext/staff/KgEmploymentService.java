package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** Actual departure is independent of the recruitment state machine and financial settlement. */
@Service
public class KgEmploymentService {
    @Autowired private KgJdbc db;
    @Autowired private IKgStaffGrantService grants;
    @Autowired(required=false) private CityLeadScope cityLeads;
    @Autowired(required=false) private CityGrantScope cityGrants;

    private Map<String,Object> authorized(AppLoginUser actor, Long applicationId, String permission, boolean lock) {
        Map<String,Object> application = record(applicationId,lock);
        if (!Arrays.asList("volunteer","parttime","fulltime").contains(application.get("jobType")))
            throw new ServiceException("此报名不适用在岗登记",422);
        Long store = KgJdbc.asLong(application.get("storeId"));
        boolean permitted = lock ? allowedNow(actor.getUserId(),store,permission) : allowed(actor.getUserId(),store,permission);
        if (StaffPerms.VIEW.equals(permission))
            permitted |= allowed(actor.getUserId(),KgJdbc.asLong(application.get("managementStoreId")),permission);
        if (!permitted)
            throw new ServiceException("没有该门店的在岗登记权限",403);
        return application;
    }

    private boolean allowed(Long actor, Long store, String permission) {
        List<StaffGrantView> current = grants.loadActiveGrantViews(actor);
        if (current == null) return false;
        for (StaffGrantView grant : current) {
            if (Objects.equals(store,grant.getStoreId()) && Boolean.TRUE.equals(grant.getActive())
                    && StaffPerms.allows(grant.getRoleCode(),"jobs",permission)) return true;
        }
        return false;
    }

    /** Lock waits must not reuse a MyBatis session's earlier grant query result. */
    private boolean allowedNow(Long actor, Long store, String permission) {
        Map<String,Object> params = p("u",actor,"s",store);
        if (db.count("select count(*) from kg_app_user u join kg_store s on s.store_id=:s "
                + "where u.user_id=:u and u.status='0' and u.del_flag='0' and s.status='0' and s.del_flag='0'",params) == 0) return false;
        for (Map<String,Object> row : db.list("select role_code from kg_staff_grant where app_user_id=:u "
                + "and store_id=:s and status='0' and del_flag='0'",params)) {
            String role = str(row.get("roleCode"));
            if (RolePhase.usable(role) && StaffPerms.allows(role,"jobs",permission)) return true;
        }
        if (cityLeads != null && cityLeads.covers(actor,store) && StaffPerms.allows("city_lead","jobs",permission)) return true;
        // 按城市授权（本城门店按所选角色算；这家店有门店授权或撤销记录时 roleAt 为空，以门店授权为准）
        String cityRole = cityGrants == null ? null : cityGrants.roleAt(actor,store);
        return cityRole != null && StaffPerms.allows(cityRole,"jobs",permission);
    }

    private Map<String,Object> record(Long applicationId, boolean lock) {
        return db.must("select a.app_id,a.app_user_id,a.status,a.onboard_time,a.volunteer_done_time,a.offboard_time,a.offboard_by,a.offboard_note,"
                + "j.job_type,coalesce(j.recruit_store_id,a.store_id) store_id,a.store_id management_store_id "
                + "from kg_application a join kg_job j on j.job_id=a.job_id where a.app_id=:a and a.del_flag='0'"
                + (lock?" for update":""),p("a",applicationId),"报名记录不存在");
    }

    private boolean unfinished(Map<String,Object> row) {
        return "onboarded".equals(row.get("status")) && row.get("onboardTime") != null && row.get("volunteerDoneTime") == null;
    }

    private Map<String,Object> view(Map<String,Object> row, boolean canEdit, Long actor) {
        boolean completed = row.get("volunteerDoneTime") != null || "completed".equals(row.get("status"));
        String state = completed ? "completed" : row.get("offboardTime") != null ? "offboarded" : unfinished(row) ? "active" : "unconfirmed";
        return p("appId",row.get("appId"),"state",state,"onboardTime",row.get("onboardTime"),
                "offboardTime",row.get("offboardTime"),"offboardNote",row.get("offboardNote"),
                "canManage",canEdit && unfinished(row) && !Objects.equals(actor,KgJdbc.asLong(row.get("appUserId"))));
    }

    public Map<String,Object> detail(AppLoginUser actor, Long applicationId) {
        Map<String,Object> application = authorized(actor,applicationId,StaffPerms.VIEW,false);
        return view(application,allowed(actor.getUserId(),KgJdbc.asLong(application.get("storeId")),StaffPerms.EDIT),actor.getUserId());
    }

    public Map<String,Object> list(AppLoginUser actor, Long storeId, int page) {
        if (storeId == null || !allowed(actor.getUserId(),storeId,StaffPerms.VIEW))
            throw new ServiceException("没有该门店的在岗名单查看权限",403);
        return db.page("select a.app_id,a.real_name name,j.title job_title,j.job_type,a.onboard_time,a.offboard_time,"
                + "case when a.volunteer_done_time is not null or a.status='completed' then 'completed' "
                + "when a.offboard_time is not null then 'offboarded' else 'active' end state "
                + "from kg_application a join kg_job j on j.job_id=a.job_id "
                + "where coalesce(j.recruit_store_id,a.store_id)=:s and a.del_flag='0' "
                + "and a.status in ('onboarded','completed') and a.onboard_time is not null "
                + "and j.job_type in ('volunteer','parttime','fulltime') "
                + "order by a.onboard_time desc,a.app_id desc",p("s",storeId),page,10);
    }

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> save(AppLoginUser actor, Long applicationId, Map<String,Object> body) {
        authorized(actor,applicationId,StaffPerms.EDIT,false);
        String note = text(body,"note",5,180);
        String requestKey = text(body,"requestKey",8,64);
        require(body.containsKey("expectedOffboardTime"),"请刷新在岗状态后重试");
        String requested = str(body.get("offboardTime"));
        LocalDateTime departure = requested.isEmpty() ? null : time(requested).withNano(0);
        boolean fresh = db.claimIdem("employment:"+actor.getUserId()+":"+applicationId+":"+requestKey,"employment");
        Map<String,Object> row = authorized(actor,applicationId,StaffPerms.EDIT,true);
        if (Objects.equals(actor.getUserId(),KgJdbc.asLong(row.get("appUserId"))))
            throw new ServiceException("请由本店其他有权限的负责人登记本人在岗情况",403);
        if (!fresh) return view(row,true,actor.getUserId());
        require(unfinished(row),"仅可登记已确认入职且尚未完成的经历");
        String expected = str(body.get("expectedOffboardTime"));
        String actual = str(row.get("offboardTime"));
        require(expected.equals(actual),"在岗记录已更新，请刷新后核对再保存");
        if (departure != null) {
            require(!departure.isAfter(now()),"离岗时间不能晚于当前时间");
            require(!departure.isBefore(time(row.get("onboardTime"))),"离岗时间不能早于已确认入职时间");
        }
        String normalized = departure == null ? "" : departure.toString().replace('T',' ');
        if (normalized.length() == 16) normalized += ":00";
        if (actual.equals(normalized)) return view(row,true,actor.getUserId());
        db.update("update kg_application set offboard_time=:t,offboard_by=:by,offboard_note=:n where app_id=:a",
                p("t",departure,"by","staff:"+actor.getUserId(),"n",note,"a",applicationId));
        String action = departure == null ? "纠正离岗登记，确认仍在岗。" : "确认离岗 "+normalized+"。";
        db.insert("kg_application_log",p("app_id",applicationId,"from_status",row.get("status"),"to_status",row.get("status"),
                "operator_type","staff","operator_id",String.valueOf(actor.getUserId()),"operator_name",StaffSupport.cut(actor.getNickname(),64),
                "reason",action+note,"create_time",now()));
        return view(record(applicationId,false),true,actor.getUserId());
    }
}
