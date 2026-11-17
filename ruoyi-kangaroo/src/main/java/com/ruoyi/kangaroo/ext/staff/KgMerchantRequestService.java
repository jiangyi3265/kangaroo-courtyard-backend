package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.staff.StaffSupport.str;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.JobStatus;
import com.ruoyi.kangaroo.enums.JobType;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgActivityService;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.manage.KgStats;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.util.MaskUtils;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 商家需求（V63，2026-10-30 主人拍板）。一条需求 = 商家要招的人（换工 / 兼职 / 全职）、线上任务、代运营或抖音卖房（内部编码 cloud_chain，2026-10-31 改名，V66 改了已存的标题和站内信）。
 *
 * 换工 / 兼职 / 全职：商家发布 → 本城换工主理人收到派单，系统把商家店授权给他 → 他在手机上完善并上架；本城没有人就等平台。
 * 线上任务 / 代运营 / 抖音卖房：商家提交 → 本城换工主理人核实 → 转商家服务人 → 接手、上架、标完成；中途没有人就等平台。
 * 后台随时能看能改（商家需求页、岗位管理），老草稿没有需求行，照旧走后台上架。
 *
 * 防串城：换工主理人、商家服务人、城市负责人看到和动到的只有 kg_merchant_request.city 等于自己当前门店城市的需求。
 * 城市一律用 KgCityService.jobCity 归一后比较，空城市、全国、不限都算「没有城市」。
 *
 * 兼任：小院主理人的授权上开了 also_service（后台员工授权页勾选），在这里就算商家服务人：serviceCtx 放行、本城派单算他、在那家店不算「另有角色」。
 * 普通小院主理人照旧 403。门店业务（小院工作台那边）还是按小院主理人走，权限矩阵不动；线上任务的付款登记只在「商家的任务」上按商家服务人算（KgOnlineTaskService.payoutRole）。
 * 关掉兼任、撤销或换角色后，系统在商家店补的授权一起收回（releaseServiceGrants），不然他还能带着商家店的 storeId 继续调这些接口。
 *
 * 商家服务人的审核（Luna 备忘录，2026-10-30）：线上任务的「审核通过」就是上架，没上架之前可以退回给商家改；
 * 商家活动（kg_activity.source = merchant）也由本城商家服务人审，后台的审核入口不动，两边谁先点算谁（KgActivityService.audit 有 CAS）。
 */
@Service
public class KgMerchantRequestService
{
    public static final String DISPATCHED = "dispatched";
    public static final String PUBLISHED = "published";
    public static final String CLOSED = "closed";
    public static final String UNASSIGNED = "unassigned";
    public static final String PENDING_VERIFY = "pending_verify";
    public static final String FORWARDED = "forwarded";
    public static final String ACCEPTED = "accepted";
    public static final String DONE = "done";
    public static final String RETURNED = "returned";

    public static final String ONLINE_TASK = "online_task";
    public static final String MANAGED = "managed";
    public static final String CLOUD_CHAIN = "cloud_chain";

    /** 系统自动补的授权都记这个来源：V63 回滚时靠它撤销 */
    public static final String GRANTED_BY = "system:merchant_request";

    /**
     * 系统补的授权因为这个人不再是本城商家服务人而收回时，revoked_by 记这个。和平台手工撤销不一样：手工撤销等于不让他碰这家店，
     * 这种只是身份没了，他以后重新开兼任，ensureGrant 要能复活、blockedAt 不能拦。
     */
    public static final String REVOKED_NOT_SERVICE = "system:also_service_off";

    /** 同上，换工主理人版：他在这个城市已经不是（平台开的）换工主理人了，系统补的授权收回时记这个；以后重新开通能复活 */
    public static final String REVOKED_NOT_MANAGER = "system:manager_off";

    /** 消息的 biz_type（KgMessageMapper 对商家服务人、城市负责人放行这一类） */
    public static final String BIZ_TYPE = "merchant_request";

    /**
     * 参数设置里的开关（V63 写入，值 off）：新版小程序覆盖之前，老版本没有核实、完善的页面，派给换工主理人也接不住，所以商家提交的一律进老板队列（unassigned），
     * 和老流程一样在后台岗位管理上架。新版发布后把它改成 on 才开始派单。这一项不存在或填别的都算 on（本地库、回滚后不影响新流程）。
     * 阶段锁：角色切换之前（RolePhase 不是 new）不管这一项填什么都不派，老主理人这时还是带资金权的角色，不能往他们头上补商家店授权。
     */
    public static final String ROUTING_KEY = "kangaroo.merchant.staffRouting";

    static final List<String> JOB_TYPES = Arrays.asList(JobType.VOLUNTEER, "parttime", "fulltime");
    static final List<String> INTAKE_TYPES = Arrays.asList(ONLINE_TASK, MANAGED, CLOUD_CHAIN);
    private static final List<String> ALL_STATUS = Arrays.asList(DISPATCHED, PUBLISHED, CLOSED, UNASSIGNED, PENDING_VERIFY, FORWARDED, ACCEPTED, DONE, RETURNED);
    /** 后台能「重新派单」的状态：还没结束、也不在商家手上 */
    private static final List<String> REDISPATCH_FROM = Arrays.asList(UNASSIGNED, DISPATCHED, PENDING_VERIFY, FORWARDED, ACCEPTED);
    /** 后台能「关闭」的状态 */
    private static final List<String> CLOSE_FROM = Arrays.asList(UNASSIGNED, DISPATCHED, PENDING_VERIFY, FORWARDED, ACCEPTED, RETURNED);
    /** 后台能「标记完成」的状态（代运营、抖音卖房线下办完的） */
    private static final List<String> DONE_FROM = Arrays.asList(UNASSIGNED, PENDING_VERIFY, FORWARDED, ACCEPTED, RETURNED);
    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");
    private static final int MAX_IMAGES = 9;
    private static final Logger log = LoggerFactory.getLogger(KgMerchantRequestService.class);

    /** 待审核活动消息点开的页面（商家服务人的审核页，staff 分包） */
    static final String ACTIVITY_AUDIT_PAGE = "/pages/staff/service-activities";

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgMerchantAgreements merchantAgreements;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgCityService cities;

    @Autowired
    private IKgStaffGrantService grants;

    @Autowired
    private AppTokenService tokens;

    @Autowired
    private IKgJobService jobs;

    @Lazy
    @Autowired
    private KgStaffJobService staffJobs;

    @Lazy
    @Autowired
    private KgOnlineTaskService onlineTasks;

    @Lazy
    @Autowired
    private KgActivityService activities;

    @Autowired(required = false)
    private PlatformTransactionManager transactions;

    @Autowired(required = false)
    private KgCommunityGuard guard;

    @Autowired(required = false)
    private ISysConfigService configs;

    /** 这时候能不能把需求派给换工主理人、商家服务人（见 ROUTING_KEY）；参数服务出错时按开着算，和没这一项一样 */
    boolean staffRouting()
    {
        if (!RolePhase.isNew())
        {
            return false;
        }
        String v;
        try
        {
            v = configs == null ? null : configs.selectConfigByKey(ROUTING_KEY);
        }
        catch (RuntimeException e)
        {
            v = null;
        }
        return v == null || !"off".equalsIgnoreCase(v.trim());
    }

    /**
     * 本城的换工主理人只有商家本人（一个账号可以同时是商家和换工主理人，2026-10-31 主人反馈里要写明的情况）：派单不派给自己（dispatchTo、intakeManagers 都排除了他），
     * 这条需求等平台。这时不能说「本城没有换工主理人」，他自己就是。
     */
    private boolean onlySelfManages(String town, Long merchant)
    {
        return merchant != null && people(town, StaffRole.MANAGER, null).containsKey(merchant);
    }

    /** 没派出去时给商家的说法 */
    private String waitingMessage(String town, Long merchant)
    {
        if (!staffRouting())
        {
            return "已提交，平台会尽快安排";
        }
        return onlySelfManages(town, merchant) ? "已提交，本城没有其他换工主理人，平台会尽快安排" : "已提交，本城暂时没有换工主理人，平台会尽快安排";
    }

    /** 没派出去时给城市负责人的说法 */
    private String platformTail(String town, Long merchant)
    {
        if (!staffRouting())
        {
            return "，已转平台处理";
        }
        return onlySelfManages(town, merchant) ? "，本城没有其他换工主理人，已转平台处理" : "，本城还没有换工主理人，已转平台处理";
    }

    /** 状态的中文（新状态老版本小程序也读这个，不能露英文） */
    public static String statusLabel(String s)
    {
        if (DISPATCHED.equals(s)) return "换工主理人完善中";
        if (PUBLISHED.equals(s)) return "已上架";
        if (CLOSED.equals(s)) return "已关闭";
        if (UNASSIGNED.equals(s)) return "等待平台安排";
        if (PENDING_VERIFY.equals(s)) return "待换工主理人核实";
        if (FORWARDED.equals(s)) return "已转商家服务人";
        if (ACCEPTED.equals(s)) return "商家服务人处理中";
        if (DONE.equals(s)) return "已完成";
        if (RETURNED.equals(s)) return "已退回，可修改后再提交";
        return "处理中";
    }

    /** 显示给人看的状态：招募上架后岗位又被暂停、关闭时，需求还记着「已上架」，文字按岗位现在的状态说 */
    public static String statusLabel(String status, Object jobStatus)
    {
        if (PUBLISHED.equals(status))
        {
            if (JobStatus.PAUSED.equals(str(jobStatus)))
            {
                return "已暂停";
            }
            if (JobStatus.CLOSED.equals(str(jobStatus)))
            {
                return "已关闭";
            }
        }
        return statusLabel(status);
    }

    public static String typeLabel(String t)
    {
        if (JobType.VOLUNTEER.equals(t)) return "招募换工";
        if ("parttime".equals(t)) return "招募兼职";
        if ("fulltime".equals(t)) return "招募全职";
        if (ONLINE_TASK.equals(t)) return "线上任务";
        if (MANAGED.equals(t)) return "代运营";
        if (CLOUD_CHAIN.equals(t)) return "抖音卖房";
        return "商家需求";
    }

    // ------------------------------------------------------------------ 身份与城市

    /**
     * 一个账号可能同时有好几种角色：门店 ID 传了就必须是这家店、这个角色；没传取第一家这个角色的门店。
     * 不能只看权限矩阵：换工主理人也有 intake·编辑，没有这一步他就能调商家服务人的接口。
     */
    private StaffSupport.Ctx pick(Long storeId, String role, String opAction, String opLabel)
    {
        Long target = storeId;
        if (target == null)
        {
            AppLoginUser me = AppUserContext.requireStaff();
            for (StaffGrantView g : me.getStaffGrants())
            {
                if (g.getStoreId() != null && (role.equals(g.getRoleCode()) || (StaffRole.MERCHANT_SERVICE.equals(role) && alsoService(g))))
                {
                    target = g.getStoreId();
                    break;
                }
            }
            if (target == null && canStandIn(role))
            {
                for (StaffGrantView g : me.getStaffGrants())
                {
                    if (g.getStoreId() != null && StaffRole.CITY_LEAD.equals(g.getRoleCode()))
                    {
                        target = g.getStoreId();
                        break;
                    }
                }
            }
            if (target == null)
            {
                throw new ServiceException("这项功能只有" + StaffRole.label(role) + "可以使用", HttpStatus.FORBIDDEN);
            }
        }
        StaffSupport.Ctx c = support.ctx(target);
        if (StaffRole.MERCHANT_SERVICE.equals(role) && alsoService(c))
        {
            return asService(c);
        }
        if (canStandIn(role) && StaffRole.CITY_LEAD.equals(c.role))
        {
            return standIn(c, role);
        }
        if (!role.equals(c.role))
        {
            support.op(c, opAction, opLabel, "general", null, false, "角色不符");
            throw new ServiceException("这项功能只有" + StaffRole.label(role) + "可以使用", HttpStatus.FORBIDDEN);
        }
        return c;
    }

    /** 这条授权是不是「小院主理人 + 兼任商家服务人」（kg_staff_grant.also_service，后台员工授权页勾的） */
    private static boolean alsoService(StaffGrantView g)
    {
        return g != null && StaffRole.KEEPER.equals(g.getRoleCode()) && Boolean.TRUE.equals(g.getAlsoService());
    }

    /** 当前门店上的身份是不是兼任。看的是登录态里这家店的授权（每个请求都从库里重读），不用再查库 */
    public static boolean alsoService(StaffSupport.Ctx c)
    {
        if (c == null || c.me == null || c.storeId == null || c.me.getStaffGrants() == null || !StaffRole.KEEPER.equals(c.role))
        {
            return false;
        }
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            if (c.storeId.equals(g.getStoreId()))
            {
                return alsoService(g);
            }
        }
        return false;
    }

    /**
     * 兼任的人在商家服务人接口里的身份：另开一个 Ctx，角色记商家服务人，权限判断和操作日志都走这个角色。
     * 不改传进来的对象：同一个请求里别的代码还要按小院主理人算（权限矩阵里 intake、付款这几行是按角色给的）。
     */
    private static StaffSupport.Ctx asService(StaffSupport.Ctx c)
    {
        StaffSupport.Ctx s = new StaffSupport.Ctx();
        s.me = c.me;
        s.storeId = c.storeId;
        s.storeName = c.storeName;
        s.role = StaffRole.MERCHANT_SERVICE;
        return s;
    }

    /** 切换后城市负责人能顶换工主理人和商家服务人。合作商家不能顶：商家专用接口（我发布的）只认商家本人 */
    private static boolean canStandIn(String role)
    {
        return RolePhase.isNew() && (StaffRole.MANAGER.equals(role) || StaffRole.MERCHANT_SERVICE.equals(role));
    }

    /** 城市负责人顶这个角色：业务和权限都按这个角色走（不多给这个角色没有的），操作日志还记城市负责人 */
    private static StaffSupport.Ctx standIn(StaffSupport.Ctx c, String role)
    {
        StaffSupport.Ctx s = new StaffSupport.Ctx();
        s.me = c.me;
        s.storeId = c.storeId;
        s.storeName = c.storeName;
        s.role = role;
        s.actingFrom = StaffRole.CITY_LEAD;
        return s;
    }

    /** 换工主理人（线上任务审核）：控制器在事务外调用，拒绝时的失败日志不随业务回滚 */
    public StaffSupport.Ctx managerCtx(Long storeId, String action)
    {
        StaffSupport.Ctx c = pick(storeId, StaffRole.MANAGER, "intake", "线上任务审核");
        support.need(c, "intake", action, "intake", "线上任务审核");
        return c;
    }

    public StaffSupport.Ctx serviceCtx(Long storeId, String action)
    {
        StaffSupport.Ctx c = pick(storeId, StaffRole.MERCHANT_SERVICE, "service_request", "商家服务");
        support.need(c, "intake", action, "service_request", "商家服务");
        return c;
    }

    public StaffSupport.Ctx cityCtx(Long storeId)
    {
        StaffSupport.Ctx c = pick(storeId, StaffRole.CITY_LEAD, "city_request", "本城需求");
        support.need(c, "intake", StaffPerms.VIEW, "city_request", "本城需求");
        return c;
    }

    public StaffSupport.Ctx merchantCtx(Long storeId)
    {
        return pick(storeId, StaffRole.MERCHANT, "merchant_request", "我发布的");
    }

    /** 派单岗位按岗位所属门店取身份：必须是这家店的换工主理人；一个人在别家店是换工主理人、这家店是别的角色时借不了 */
    public StaffSupport.Ctx jobManagerCtx(Long jobId, String action, String opAction, String opLabel)
    {
        Map<String, Object> j = db.one("select store_id from kg_job where job_id = :id and del_flag = '0'", p("id", jobId));
        if (j == null || j.get("storeId") == null)
        {
            throw new ServiceException("岗位不存在", 404);
        }
        StaffSupport.Ctx c = support.ctx(KgJdbc.asLong(j.get("storeId")));
        if (canStandIn(StaffRole.MANAGER) && StaffRole.CITY_LEAD.equals(c.role))
        {
            c = standIn(c, StaffRole.MANAGER);
        }
        if (!StaffRole.MANAGER.equals(c.role))
        {
            support.op(c, opAction, opLabel, "general", null, false, "角色不符");
            throw new ServiceException("只有这家店的换工主理人可以处理商家岗位", HttpStatus.FORBIDDEN);
        }
        support.need(c, "jobs", action, opAction, opLabel);
        return c;
    }

    public String normalCity(Object raw)
    {
        String town = cities.jobCity(str(raw).trim());
        town = town == null ? "" : town.trim();
        return town.isEmpty() || "全国".equals(town) || "不限".equals(town) ? "" : town;
    }

    /** 当前门店的城市；门店没设城市时不能按「空城市」查，否则会把所有没城市的需求都列出来 */
    public String townOf(StaffSupport.Ctx c)
    {
        Map<String, Object> s = db.one("select city from kg_store where store_id = :s and status = '0' and del_flag = '0'", p("s", c.storeId));
        if (s == null)
        {
            throw new ServiceException("当前授权门店已停用", HttpStatus.FORBIDDEN);
        }
        String town = normalCity(s.get("city"));
        if (town.isEmpty())
        {
            throw new ServiceException("请先在后台为当前门店设置所在城市", 422);
        }
        return town;
    }

    /**
     * 本城某个角色的在职人员：授权、门店、账号都正常，门店城市归一后相同。一人多店只算一次，记他在本城的第一家店（消息点开时切到这家）。
     * 城市是 Java 里归一的（kg_store.city 是自由文本），和 KgVolunteerDailyService.remindOne 同一个做法。
     * 商家服务人连兼任的小院主理人一起算；别的角色查询不变。
     * 城市负责人还算按城市授权的（2026-11 起，store_id 为空、只有 city_id，城市就是授权上的城市，口径同 CityLeadScope）：
     * 原先 join kg_store 把他们筛掉了，店长发日报提示「本城还没有城市负责人」。这种行没有门店，storeId 补成本城第一家正常门店（消息点开切到这家），本城没有门店就是空。
     */
    private Map<Long, Map<String, Object>> people(String town, String role, Long exclude)
    {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<Long, Map<String, Object>>();
        if (town == null || town.isEmpty())
        {
            return out;
        }
        String who = StaffRole.MERCHANT_SERVICE.equals(role) ? "(g.role_code = :r or (g.role_code = 'keeper' and g.also_service = '1'))" : "g.role_code = :r";
        boolean lead = StaffRole.CITY_LEAD.equals(role);
        String scope = lead ? "((g.city_id is null and s.status = '0' and s.del_flag = '0') or (g.store_id is null and c.status = '0')) "
                : "s.status = '0' and s.del_flag = '0' ";
        // 门店授权排前面：一人既有门店授权又有城市授权时，消息点开切到他自己的店
        for (Map<String, Object> g : db.list("select g.app_user_id, g.store_id, " + (lead ? "coalesce(c.name, s.city)" : "s.city") + " city, u.nickname from kg_staff_grant g "
                + (lead ? "left join kg_store s on s.store_id = g.store_id left join kg_city c on c.city_id = g.city_id " : "join kg_store s on s.store_id = g.store_id ")
                + "join kg_app_user u on u.user_id = g.app_user_id where " + who + " and g.status = '0' and g.del_flag = '0' "
                + "and " + scope + "and u.status = '0' and u.del_flag = '0' order by " + (lead ? "g.store_id is null, " : "") + "g.grant_id", p("r", role)))
        {
            Long uid = KgJdbc.asLong(g.get("appUserId"));
            if (uid == null || uid.equals(exclude) || out.containsKey(uid) || !town.equals(normalCity(g.get("city"))))
            {
                continue;
            }
            if (g.get("storeId") == null)
            {
                g = new LinkedHashMap<String, Object>(g);
                g.put("storeId", firstStoreIn(town));
            }
            out.put(uid, g);
        }
        return out;
    }

    /** 本城第一家正常门店（按编号）；没有就是 null */
    private Long firstStoreIn(String town)
    {
        for (Map<String, Object> s : db.list("select store_id, city from kg_store where status = '0' and del_flag = '0' order by store_id", p()))
        {
            if (town.equals(normalCity(s.get("city"))))
            {
                return KgJdbc.asLong(s.get("storeId"));
            }
        }
        return null;
    }

    /** 这家店所在城市的城市负责人账号（日报发送用）；门店没设城市时为空 */
    public List<Long> cityLeadIds(Long storeId)
    {
        Map<String, Object> s = db.one("select city from kg_store where store_id = :s and status = '0' and del_flag = '0'", p("s", storeId));
        return s == null ? new ArrayList<Long>() : new ArrayList<Long>(people(normalCity(s.get("city")), StaffRole.CITY_LEAD, null).keySet());
    }

    /**
     * 补一条授权：这个人在这家店已有任何记录就不动（含已撤销：不复活被撤销的）。
     * 返回这个人现在是不是真的能以这个角色进这家店；不能的不算派到了，否则派单发出去没人接得住。
     * 这家店上他已经是兼任商家服务人的小院主理人时算能进，不插新行（同一人同一店只能一条授权）。
     * 例外：关掉兼任时系统收回的那种（REVOKED_NOT_SERVICE）不是平台不让他碰这家店，这里复活。
     */
    private boolean ensureGrant(Long uid, Long storeId, String role)
    {
        Map<String, Object> e = db.one("select grant_id, status, del_flag, role_code, also_service, revoked_by from kg_staff_grant where app_user_id = :u and store_id = :s limit 1",
                p("u", uid, "s", storeId));
        if (e != null)
        {
            if (!revivable(e, role))
            {
                return activeAs(e, role);
            }
            // 只认还是这个标记的行：同一时间平台手工处理过的不动，再看一次实际状态
            db.update("update kg_staff_grant set status = '0', revoked_by = null, revoked_time = null, granted_time = now(), update_by = :by, update_time = now() "
                    + "where grant_id = :g and status = '1' and revoked_by = :off", p("by", GRANTED_BY, "g", e.get("grantId"), "off", str(e.get("revokedBy"))));
            Map<String, Object> now = db.one("select grant_id, status, del_flag, role_code, also_service, revoked_by from kg_staff_grant where app_user_id = :u and store_id = :s limit 1",
                    p("u", uid, "s", storeId));
            tokens.refreshGrants(uid, grants.loadActiveGrantViews(uid));
            return now != null && activeAs(now, role);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", uid);
        row.put("store_id", storeId);
        row.put("role_code", role);
        row.put("status", "0");
        row.put("granted_by", GRANTED_BY);
        row.put("granted_time", new Date());
        row.put("del_flag", "0");
        row.put("create_by", GRANTED_BY);
        row.put("create_time", new Date());
        try
        {
            db.insert("kg_staff_grant", row);
        }
        catch (DuplicateKeyException ex)
        {
            // 同一时间另一条需求已经补过，再看一次实际状态
            Map<String, Object> again = db.one("select status, del_flag, role_code, also_service from kg_staff_grant where app_user_id = :u and store_id = :s limit 1",
                    p("u", uid, "s", storeId));
            return again != null && activeAs(again, role);
        }
        tokens.refreshGrants(uid, grants.loadActiveGrantViews(uid));
        return true;
    }

    /** 这行授权（status, del_flag, role_code, also_service）现在算不算生效中的这个角色；商家服务人也认兼任的小院主理人 */
    private static boolean activeAs(Map<String, Object> g, String role)
    {
        if (!"0".equals(str(g.get("status"))) || "2".equals(str(g.get("delFlag"))))
        {
            return false;
        }
        String held = str(g.get("roleCode"));
        return role.equals(held) || (StaffRole.MERCHANT_SERVICE.equals(role) && StaffRole.KEEPER.equals(held) && "1".equals(str(g.get("alsoService"))));
    }

    /** 这行是不是系统收回的自动授权（关掉兼任、不再是本城换工主理人）：不算平台不让他碰这家店，他重新有了这个身份后能复活 */
    private static boolean revivable(Map<String, Object> g, String role)
    {
        String off = StaffRole.MERCHANT_SERVICE.equals(role) ? REVOKED_NOT_SERVICE : (StaffRole.MANAGER.equals(role) ? REVOKED_NOT_MANAGER : null);
        return off != null && "1".equals(str(g.get("status"))) && !"2".equals(str(g.get("delFlag")))
                && role.equals(str(g.get("roleCode"))) && off.equals(str(g.get("revokedBy")));
    }

    /**
     * 兼任关掉、小院主理人授权被撤销或换了角色之后调（KgStaffGrantServiceImpl.updateRole / revoke、KgStaffTeamService.revoke）：
     * 这个人在某个城市已经不是商家服务人了，系统在这个城市的商家店给他补的授权（granted_by = GRANTED_BY）一起收回。
     * 不收的话带 storeId 调商家服务人接口还能过，本城提醒也继续发给他，平台却以为权限已经收回了。
     * 不是系统补的（平台手工开的 merchant_service）不动，它们本身也算他还是这个城市的商家服务人。重复调没有副作用。
     * 调用方接着负责刷新会话。
     */
    @Transactional(rollbackFor = Exception.class)
    public int releaseServiceGrants(Long uid)
    {
        if (uid == null)
        {
            return 0;
        }
        List<Map<String, Object>> rows = db.list("select g.grant_id, g.role_code, g.granted_by, s.city, s.status store_status, s.del_flag store_del from kg_staff_grant g "
                + "left join kg_store s on s.store_id = g.store_id where g.app_user_id = :u and g.status = '0' and g.del_flag = '0' "
                + "and (g.role_code = 'merchant_service' or (g.role_code = 'keeper' and g.also_service = '1'))", p("u", uid));
        List<String> towns = new ArrayList<String>();
        for (Map<String, Object> r : rows)
        {
            // 门店停用的不算（同 people）
            if (!autoServiceRow(r) && "0".equals(str(r.get("storeStatus"))) && "0".equals(str(r.get("storeDel"))))
            {
                towns.add(normalCity(r.get("city")));
            }
        }
        int released = 0;
        for (Map<String, Object> r : rows)
        {
            if (autoServiceRow(r) && !towns.contains(normalCity(r.get("city"))))
            {
                released += db.update("update kg_staff_grant set status = '1', revoked_by = :off, revoked_time = now(), update_by = :off, update_time = now() "
                        + "where grant_id = :g and status = '0' and role_code = 'merchant_service' and granted_by = :auto",
                        p("off", REVOKED_NOT_SERVICE, "g", r.get("grantId"), "auto", GRANTED_BY));
            }
        }
        return released;
    }

    /**
     * 换工主理人版的 releaseServiceGrants：平台撤销他的授权或换了角色之后，他在这个城市已经不是（平台开的）换工主理人了，
     * 派单时系统在这个城市的商家店给他补的授权（granted_by = GRANTED_BY）一起收回。不收的话这些店的报名消息、线下会话还发给他。
     * 开派单只可能在 new 阶段，所以这之前一行都不动（legacy 行为不变）。必须在打开派单之前上线。调用方接着负责刷新会话。
     */
    @Transactional(rollbackFor = Exception.class)
    public int releaseManagerGrants(Long uid)
    {
        if (uid == null || !RolePhase.isNew())
        {
            return 0;
        }
        List<Map<String, Object>> rows = db.list("select g.grant_id, g.granted_by, s.city, s.status store_status, s.del_flag store_del from kg_staff_grant g "
                + "left join kg_store s on s.store_id = g.store_id where g.app_user_id = :u and g.status = '0' and g.del_flag = '0' and g.role_code = 'manager'", p("u", uid));
        List<String> towns = new ArrayList<String>();
        for (Map<String, Object> r : rows)
        {
            if (!GRANTED_BY.equals(str(r.get("grantedBy"))) && "0".equals(str(r.get("storeStatus"))) && "0".equals(str(r.get("storeDel"))))
            {
                towns.add(normalCity(r.get("city")));
            }
        }
        int released = 0;
        for (Map<String, Object> r : rows)
        {
            if (GRANTED_BY.equals(str(r.get("grantedBy"))) && !towns.contains(normalCity(r.get("city"))))
            {
                released += db.update("update kg_staff_grant set status = '1', revoked_by = :off, revoked_time = now(), update_by = :off, update_time = now() "
                        + "where grant_id = :g and status = '0' and role_code = 'manager' and granted_by = :auto",
                        p("off", REVOKED_NOT_MANAGER, "g", r.get("grantId"), "auto", GRANTED_BY));
            }
        }
        return released;
    }

    private static boolean autoServiceRow(Map<String, Object> r)
    {
        return StaffRole.MERCHANT_SERVICE.equals(str(r.get("roleCode"))) && GRANTED_BY.equals(str(r.get("grantedBy")));
    }

    /** 这个人在这家店有授权记录、但不是生效中的这个角色（被撤销过，或者是别的角色）：不能以这个角色处理这家店的需求 */
    private boolean blockedAt(Long uid, Long storeId, String role)
    {
        if (uid == null || storeId == null)
        {
            return false;
        }
        Map<String, Object> e = db.one("select status, del_flag, role_code, also_service, revoked_by from kg_staff_grant where app_user_id = :u and store_id = :s limit 1",
                p("u", uid, "s", storeId));
        return e != null && !activeAs(e, role) && !revivable(e, role);
    }

    /** 当前这个人能不能碰这家店的需求。城市负责人顶上时：看他的范围（登录态每个请求现算），在这家店是商家、被撤销过的不在范围里 */
    private boolean blockedFor(StaffSupport.Ctx c, Long storeId)
    {
        if (c.cityLeadActing())
        {
            return storeId != null && c.me.getStaffGrants().stream().noneMatch(g -> storeId.equals(g.getStoreId()) && StaffRole.CITY_LEAD.equals(g.getRoleCode()));
        }
        return blockedAt(c.uid(), storeId, StaffRole.MERCHANT_SERVICE);
    }

    /** 本城能接这家店需求的商家服务人：排除商家本人，也排除在这家店被撤销过授权或另有角色的 */
    private Map<Long, Map<String, Object>> eligibleService(String town, Long merchant, Long storeId)
    {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map.Entry<Long, Map<String, Object>> e : people(town, StaffRole.MERCHANT_SERVICE, merchant).entrySet())
        {
            if (!blockedAt(e.getKey(), storeId, StaffRole.MERCHANT_SERVICE))
            {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** 自己提交的需求自己不能核实、不能接手：一个账号可以同时是换工主理人（或商家服务人）和商家 */
    private static void requireNotOwn(StaffSupport.Ctx c, Map<String, Object> r)
    {
        if (c.uid().equals(KgJdbc.asLong(r.get("merchantUserId"))))
        {
            throw new ServiceException("不能处理自己提交的需求", HttpStatus.FORBIDDEN);
        }
    }

    /** 列表里也不出现自己提交的（r 是 kg_merchant_request 的别名） */
    private static String notOwnSql(StaffSupport.Ctx c, Map<String, Object> ps)
    {
        ps.put("me", c.uid());
        return " and (r.merchant_user_id is null or r.merchant_user_id <> :me)";
    }

    /** 商家服务人的列表：在那家店被撤销过授权或另有角色的不出现（同 blockedAt）；在那家店是兼任的小院主理人、关掉兼任时系统收回的都不算 */
    private static final String SERVICE_NOT_BLOCKED = notBlockedAt("r.store_id");

    /** 城市负责人顶上时不用这条（他在这家店的授权行是 city_lead，这条会把自己的店过滤掉）：同城的需求他都能看，自己提交的 notOwnSql 已经去掉；门店不在他范围里的按授权列表去掉 */
    private String serviceNotBlocked(StaffSupport.Ctx c)
    {
        if (!c.cityLeadActing())
        {
            return SERVICE_NOT_BLOCKED;
        }
        StringBuilder ids = new StringBuilder();
        for (StaffGrantView g : c.me.getStaffGrants())
        {
            if (g.getStoreId() != null && StaffRole.CITY_LEAD.equals(g.getRoleCode()))
            {
                ids.append(ids.length() == 0 ? "" : ",").append(g.getStoreId().longValue());
            }
        }
        return ids.length() == 0 ? " and 1 = 0" : " and (r.store_id is null or r.store_id in (" + ids + "))";
    }

    private static String notBlockedAt(String storeColumn)
    {
        return " and not exists (select 1 from kg_staff_grant bg where bg.app_user_id = :me and bg.store_id = " + storeColumn + " "
                + "and not (bg.status = '0' and bg.del_flag <> '2' and (bg.role_code = 'merchant_service' or (bg.role_code = 'keeper' and bg.also_service = '1'))) "
                + "and not (bg.status = '1' and bg.del_flag <> '2' and bg.role_code = 'merchant_service' and bg.revoked_by = '" + REVOKED_NOT_SERVICE + "'))";
    }

    // ------------------------------------------------------------------ 建需求

    private Map<String, Object> storeOf(Long storeId)
    {
        return db.must("select store_id, store_name, city from kg_store where store_id = :s and del_flag = '0'", p("s", storeId), "门店不存在");
    }

    /** 联系方式：入驻时填的优先；老商家没有申请记录时用账号的昵称和手机号 */
    private String[] contactOf(Long storeId, Long uid)
    {
        Map<String, Object> a = db.one("select contact_name, phone from kg_merchant_apply where store_id = :s and del_flag = '0' order by apply_id desc limit 1", p("s", storeId));
        if (a != null && !str(a.get("phone")).isEmpty())
        {
            return new String[] { str(a.get("contactName")), str(a.get("phone")) };
        }
        Map<String, Object> u = db.one("select nickname, phone from kg_app_user where user_id = :u", p("u", uid));
        return u == null ? new String[] { "", "" } : new String[] { StaffSupport.cut(str(u.get("nickname")), 32), StaffSupport.cut(str(u.get("phone")), 20) };
    }

    private long insert(String type, Long storeId, String storeName, Long merchant, String town, Long jobId, String title, String content,
            String contact, String phone, String images, String status, String source, String by)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("request_type", type);
        row.put("store_id", storeId);
        row.put("store_name", StaffSupport.cut(storeName, 64));
        row.put("merchant_user_id", merchant);
        row.put("city", StaffSupport.cut(town, 32));
        row.put("job_id", jobId);
        row.put("title", StaffSupport.cut(title, 64));
        row.put("content", StaffSupport.cut(content, 2000));
        row.put("contact_name", StaffSupport.cut(contact, 32));
        row.put("contact_phone", StaffSupport.cut(phone, 20));
        row.put("images", images);
        row.put("status", status);
        row.put("source", source);
        row.put("create_by", by);
        row.put("create_time", new Date());
        return db.insert("kg_merchant_request", row);
    }

    private static Map<String, Object> result(long id, String status, String message)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("requestId", id);
        m.put("requestStatus", status);
        m.put("statusLabel", statusLabel(status));
        m.put("message", message);
        return m;
    }

    /** 商家发布换工 / 兼职 / 全职（KgStaffJobService.create 同一事务调用）：有本城换工主理人就派单，没有就等平台 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispatchJob(StaffSupport.Ctx c, Long jobId, String jobType, String title, String content)
    {
        Map<String, Object> store = storeOf(c.storeId);
        String storeName = str(store.get("storeName"));
        String town = normalCity(store.get("city"));
        // 开关关着时不补授权、不发派单消息：老版本的换工主理人接不住，全部等平台
        List<Map.Entry<Long, Map<String, Object>>> managers = staffRouting() ? dispatchTo(town, c.storeId, c.uid()) : new ArrayList<Map.Entry<Long, Map<String, Object>>>();
        String status = managers.isEmpty() ? UNASSIGNED : DISPATCHED;
        String[] contact = contactOf(c.storeId, c.uid());
        long id = insert(jobType, c.storeId, storeName, c.uid(), town, jobId, title, content, contact[0], contact[1], null, status, "merchant", "staff:" + c.uid());
        afterDispatch(id, managers, c.storeId, storeName, jobType, title, town, c.uid());
        support.op(c, "merchant_job", "商家发布招募", "general", title + " → " + statusLabel(status), true, null);
        return result(id, status, managers.isEmpty() ? waitingMessage(town, c.uid()) : "已提交，本城换工主理人会尽快完善并上架");
    }

    private List<Map.Entry<Long, Map<String, Object>>> dispatchTo(String town, Long storeId, Long merchant)
    {
        List<Map.Entry<Long, Map<String, Object>>> out = new ArrayList<Map.Entry<Long, Map<String, Object>>>();
        for (Map.Entry<Long, Map<String, Object>> e : people(town, StaffRole.MANAGER, merchant).entrySet())
        {
            if (ensureGrant(e.getKey(), storeId, StaffRole.MANAGER))
            {
                out.add(e);
            }
        }
        return out;
    }

    private void afterDispatch(long id, List<Map.Entry<Long, Map<String, Object>>> managers, Long storeId, String storeName, String jobType, String title, String town, Long merchant)
    {
        for (Map.Entry<Long, Map<String, Object>> e : managers)
        {
            support.notifyStaff(e.getKey(), MessageCategory.SYSTEM, "有商家岗位派给你", "「" + storeName + "」发布了" + typeLabel(jobType) + "「" + title + "」，切到这家店完善信息并上架",
                    BIZ_TYPE, id, "/pages/staff/jobs?storeId=" + storeId);
        }
        notifyCityLeads(town, "本城有新的商家招募", "「" + storeName + "」发布了" + typeLabel(jobType) + "「" + title + "」"
                + (managers.isEmpty() ? platformTail(town, merchant) : "，已派给本城换工主理人"), id);
    }

    /** 商家发布线上任务（KgOnlineTaskService.create 同一事务调用） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> onlineTaskCreated(StaffSupport.Ctx c, Long jobId, String title, String content)
    {
        Map<String, Object> store = storeOf(c.storeId);
        String town = normalCity(store.get("city"));
        String[] contact = contactOf(c.storeId, c.uid());
        return toIntake(ONLINE_TASK, c.storeId, str(store.get("storeName")), c.uid(), town, jobId, title, content, contact[0], contact[1], null, "merchant", "staff:" + c.uid());
    }

    private Map<String, Object> toIntake(String type, Long storeId, String storeName, Long merchant, String town, Long jobId, String title, String content,
            String contact, String phone, String images, String source, String by)
    {
        Map<Long, Map<String, Object>> managers = intakeManagers(town, merchant);
        String status = managers.isEmpty() ? UNASSIGNED : PENDING_VERIFY;
        long id = insert(type, storeId, storeName, merchant, town, jobId, title, content, contact, phone, images, status, source, by);
        notifyIntake(id, managers, type, storeName, title, town, merchant);
        return result(id, status, managers.isEmpty() ? waitingMessage(town, merchant) : "已提交，本城换工主理人会和你联系核实");
    }

    /** 待核实发给谁：开关关着时谁也不发，等平台 */
    private Map<Long, Map<String, Object>> intakeManagers(String town, Long merchant)
    {
        return staffRouting() ? people(town, StaffRole.MANAGER, merchant) : new LinkedHashMap<Long, Map<String, Object>>();
    }

    private void notifyIntake(long id, Map<Long, Map<String, Object>> managers, String type, String storeName, String title, String town, Long merchant)
    {
        for (Map.Entry<Long, Map<String, Object>> e : managers.entrySet())
        {
            support.notifyStaff(e.getKey(), MessageCategory.SYSTEM, "有商家需求待核实", "「" + storeName + "」提交了" + typeLabel(type) + "「" + title + "」，请和商家沟通后确认转发",
                    BIZ_TYPE, id, "/pages/staff/intake?storeId=" + e.getValue().get("storeId") + "&requestId=" + id);
        }
        notifyCityLeads(town, "本城有新的商家需求", "「" + storeName + "」提交了" + typeLabel(type) + "「" + title + "」"
                + (managers.isEmpty() ? platformTail(town, merchant) : "，等待换工主理人核实"), id);
    }

    private void notifyCityLeads(String town, String title, String content, long id)
    {
        for (Map.Entry<Long, Map<String, Object>> e : people(town, StaffRole.CITY_LEAD, null).entrySet())
        {
            // 城市负责人看不到完整联系方式（列表、详情都遮了），需求标题是商家自己写的，可能带手机号，消息里一样遮
            // 按城市授权、本城还没有门店的城市负责人没有 storeId，不带参数
            Object store = e.getValue().get("storeId");
            support.notifyStaff(e.getKey(), MessageCategory.SYSTEM, title, String.valueOf(maskText(content)), BIZ_TYPE, id, "/pages/staff/index" + (store == null ? "" : "?storeId=" + store));
        }
    }

    private void notifyMerchant(Map<String, Object> r, String title, String content)
    {
        Long merchant = KgJdbc.asLong(r.get("merchantUserId"));
        if (merchant != null)
        {
            support.notifyStaff(merchant, MessageCategory.SYSTEM, title, content, BIZ_TYPE, KgJdbc.asLong(r.get("requestId")),
                    "/pages/staff/index" + (r.get("storeId") == null ? "" : "?storeId=" + r.get("storeId")));
        }
    }

    // ------------------------------------------------------------------ 状态流转

    /** 只有状态还是 from 才改（两个人同时点时后到的 409）。extras 的键是代码里的列名常量 */
    private void move(Long id, String from, String to, String by, Map<String, Object> extras)
    {
        StringBuilder set = new StringBuilder("status = :to, version = version + 1, handle_time = now(), update_by = :by, update_time = now()");
        Map<String, Object> ps = p("id", id, "from", from, "to", to, "by", by);
        if (extras != null)
        {
            for (Map.Entry<String, Object> e : extras.entrySet())
            {
                set.append(", ").append(e.getKey()).append(" = :x_").append(e.getKey());
                ps.put("x_" + e.getKey(), e.getValue());
            }
        }
        if (db.update("update kg_merchant_request set " + set + " where request_id = :id and status = :from and del_flag = '0'", ps) == 0)
        {
            throw new ServiceException("这条需求刚刚已被处理，请刷新后查看", HttpStatus.CONFLICT);
        }
    }

    private static Map<String, Object> cols(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> mustRequest(Long id)
    {
        Map<String, Object> r = db.one("select * from kg_merchant_request where request_id = :id and del_flag = '0'", p("id", id));
        if (r == null)
        {
            throw new ServiceException("需求不存在", 404);
        }
        return r;
    }

    /** 防串城的唯一入口：别的城市的需求一律 403，不告诉内容 */
    private Map<String, Object> inCity(Long id, String town)
    {
        Map<String, Object> r = mustRequest(id);
        if (town == null || town.isEmpty() || !town.equals(str(r.get("city"))))
        {
            throw new ServiceException("这条需求不属于本城", HttpStatus.FORBIDDEN);
        }
        return r;
    }

    private static void requireIntakeType(Map<String, Object> r)
    {
        if (!INTAKE_TYPES.contains(str(r.get("requestType"))))
        {
            throw new ServiceException("招募类需求请到这家店的岗位管理处理", 409);
        }
    }

    private static void requireStatus(Map<String, Object> r, String status, String tip)
    {
        if (!status.equals(str(r.get("status"))))
        {
            throw new ServiceException(tip, HttpStatus.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 视图

    private Map<String, Object> brief(Map<String, Object> r)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("requestId", r.get("requestId"));
        m.put("requestType", r.get("requestType"));
        m.put("typeLabel", typeLabel(str(r.get("requestType"))));
        m.put("title", r.get("title"));
        m.put("storeId", r.get("storeId"));
        m.put("storeName", r.get("storeName"));
        m.put("city", r.get("city"));
        m.put("jobId", r.get("jobId"));
        m.put("status", r.get("status"));
        m.put("statusLabel", statusLabel(str(r.get("status")), r.get("jobStatus")));
        m.put("jobStatus", r.get("jobStatus"));
        m.put("source", r.get("source"));
        m.put("createTime", r.get("createTime"));
        m.put("handleTime", r.get("handleTime"));
        return m;
    }

    private static List<String> imageList(Object raw)
    {
        String s = str(raw).trim();
        return s.isEmpty() ? new ArrayList<String>() : new ArrayList<String>(Arrays.asList(s.split(",")));
    }

    /** 自己写的文字里也可能有联系方式（「联系我 138…」）：看不到完整号码的人，这些文字一起隐去（同 AppStaffController 的 10-14 回归） */
    private static Object maskText(Object v)
    {
        return v == null ? null : MaskUtils.contacts(String.valueOf(v));
    }

    /** 详情：phoneVisible = false 时手机号和文字里的联系方式都隐去（城市负责人只读） */
    private Map<String, Object> detailView(Map<String, Object> r, boolean phoneVisible)
    {
        Map<String, Object> m = brief(r);
        m.put("content", phoneVisible ? r.get("content") : maskText(r.get("content")));
        m.put("contactName", r.get("contactName"));
        m.put("contactPhone", phoneVisible ? str(r.get("contactPhone")) : MaskUtils.phone(str(r.get("contactPhone"))));
        m.put("images", imageList(r.get("images")));
        m.put("verifyNote", phoneVisible ? r.get("verifyNote") : maskText(r.get("verifyNote")));
        m.put("returnReason", phoneVisible ? r.get("returnReason") : maskText(r.get("returnReason")));
        m.put("serviceUserId", r.get("serviceUserId"));
        if (!phoneVisible)
        {
            m.put("title", maskText(r.get("title")));
        }
        Long jobId = KgJdbc.asLong(r.get("jobId"));
        if (jobId != null)
        {
            Map<String, Object> job = jobOf(jobId);
            if (job != null)
            {
                m.put("jobStatus", job.get("jobStatus"));
                m.put("statusLabel", statusLabel(str(r.get("status")), job.get("jobStatus")));
                if (!phoneVisible)
                {
                    job = new LinkedHashMap<String, Object>(job);
                    for (String k : Arrays.asList("content", "requirement", "deliverables", "criteria"))
                    {
                        job.put(k, maskText(job.get(k)));
                    }
                }
            }
            m.put("job", job);
        }
        return m;
    }

    /** 需求关联的岗位：核实、接手时看详情；商家修改被退回的线上任务时，表里带上原来填的 */
    private Map<String, Object> jobOf(Long jobId)
    {
        return db.one("select j.status job_status, j.headcount, j.content, j.requirement, j.est_hours, t.task_type, t.deliverables, t.criteria, t.commission_fen,t.reward_mode,t.items_per_person,t.unit_reward_fen,t.platform_fee_unit_fen, t.deadline_time "
                + "from kg_job j left join kg_online_task t on t.job_id = j.job_id where j.job_id = :j and j.del_flag = '0'", p("j", jobId));
    }

    private Map<String, Object> pageOf(String where, Map<String, Object> ps, Integer pageNum, Integer pageSize)
    {
        return pageOf(where, ps, pageNum, pageSize, null);
    }

    /** me 不为空时每行多一个 mine（是不是他接手的）：主人 2026-11-03 要工作台直接进「线上任务管理」，列表上就得知道哪条能点「管理任务」 */
    private Map<String, Object> pageOf(String where, Map<String, Object> ps, Integer pageNum, Integer pageSize, Long me)
    {
        Map<String, Object> page = db.page("select r.*, j.status job_status from kg_merchant_request r left join kg_job j on j.job_id = r.job_id where r.del_flag = '0' " + where + " order by r.request_id desc", ps, pageNum, pageSize);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Object raw = page.get("rows");
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) o;
                Map<String, Object> m = brief(r);
                if (me != null)
                {
                    m.put("mine", me.equals(KgJdbc.asLong(r.get("serviceUserId"))));
                }
                rows.add(m);
            }
        }
        page.put("rows", rows);
        return page;
    }

    private static String typeFilter(String type, Map<String, Object> ps)
    {
        if (type != null && !type.trim().isEmpty())
        {
            String t = type.trim();
            if (!JOB_TYPES.contains(t) && !INTAKE_TYPES.contains(t))
            {
                throw new ServiceException("需求类型不正确", 422);
            }
            ps.put("t", t);
            return " and r.request_type = :t";
        }
        return "";
    }

    private static String statusFilter(String status, Map<String, Object> ps)
    {
        if (status != null && !status.trim().isEmpty())
        {
            String s = status.trim();
            if (!ALL_STATUS.contains(s))
            {
                throw new ServiceException("需求状态不正确", 422);
            }
            ps.put("st", s);
            return " and r.status = :st";
        }
        return "";
    }

    // ------------------------------------------------------------------ 换工主理人：线上任务审核（/app/staff/intake）

    /** 本城线上任务 / 代运营 / 抖音卖房。status 不传 = 待核实 */
    public Map<String, Object> intakeList(StaffSupport.Ctx c, String status, String type, Integer pageNum, Integer pageSize)
    {
        String town = townOf(c);
        Map<String, Object> ps = p("town", town, "types", INTAKE_TYPES);
        String where = " and r.city = :town and r.request_type in (:types)"
                + statusFilter(status == null || status.trim().isEmpty() ? PENDING_VERIFY : status, ps) + typeFilter(type, ps) + notOwnSql(c, ps);
        Map<String, Object> page = pageOf(where, ps, pageNum, pageSize);
        page.put("city", town);
        return page;
    }

    public Map<String, Object> intakeDetail(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = inCity(id, townOf(c));
        requireIntakeType(r);
        requireNotOwn(c, r);
        Map<String, Object> m = detailView(r, true);
        // 只填店名代提交的没有商家账号：退回去没有人能接着改
        m.put("canReturn", r.get("merchantUserId") != null);
        return m;
    }

    /** 转发时要选的本城商家服务人、代提交时要选的本城商家店 */
    public Map<String, Object> intakeOptions(StaffSupport.Ctx c)
    {
        String town = townOf(c);
        List<Map<String, Object>> service = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Long, Map<String, Object>> e : people(town, StaffRole.MERCHANT_SERVICE, null).entrySet())
        {
            service.add(person(e));
        }
        List<Map<String, Object>> stores = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> s : db.list("select distinct s.store_id, s.store_name, s.city from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.role_code = 'merchant' and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0' order by s.store_id", p()))
        {
            if (town.equals(normalCity(s.get("city"))))
            {
                stores.add(p("storeId", s.get("storeId"), "storeName", s.get("storeName")));
            }
        }
        return p("city", town, "serviceStaff", service, "merchantStores", stores);
    }

    private static Map<String, Object> person(Map.Entry<Long, Map<String, Object>> e)
    {
        String name = str(e.getValue().get("nickname"));
        return p("userId", e.getKey(), "name", name.isEmpty() ? "用户" + e.getKey() : name);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> intakeNote(StaffSupport.Ctx c, Long id, String note)
    {
        Map<String, Object> r = inCity(id, townOf(c));
        requireIntakeType(r);
        requireNotOwn(c, r);
        String n = note == null ? "" : note.trim();
        if (n.length() > 500)
        {
            throw new ServiceException("备注最多 500 字", 422);
        }
        db.update("update kg_merchant_request set verify_note = :n, update_by = :by, update_time = now() where request_id = :id and del_flag = '0'",
                p("n", n, "by", "staff:" + c.uid(), "id", id));
        return p("requestId", id, "verifyNote", n);
    }

    /**
     * 确认转发：选了人只给他补这家店的商家服务人授权，没选给本城全部商家服务人补（他们用现有线上任务页管这家店）；消息一律发给本城全部商家服务人，谁都能接手。
     * 本城没有商家服务人：转平台（unassigned），核实记录保留，平台重新派单时直接走转发。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> intakeForward(StaffSupport.Ctx c, Long id, Long serviceUserId, String note)
    {
        String town = townOf(c);
        Map<String, Object> r = inCity(id, town);
        requireIntakeType(r);
        requireNotOwn(c, r);
        requireStatus(r, PENDING_VERIFY, "这条需求已经不是待核实状态，请刷新后查看");
        String n = note == null ? "" : note.trim();
        if (n.length() > 500)
        {
            throw new ServiceException("备注最多 500 字", 422);
        }
        Long merchant = KgJdbc.asLong(r.get("merchantUserId"));
        Map<Long, Map<String, Object>> service = eligibleService(town, merchant, KgJdbc.asLong(r.get("storeId")));
        if (serviceUserId != null && !service.containsKey(serviceUserId))
        {
            // 平台在这家店撤销过他的授权时，消息和待办都不能再转给他
            if (people(town, StaffRole.MERCHANT_SERVICE, merchant).containsKey(serviceUserId))
            {
                throw new ServiceException("这位商家服务人不能处理这家店的需求，请换一位", HttpStatus.FORBIDDEN);
            }
            throw new ServiceException("只能转给本城的商家服务人", 422);
        }
        Map<String, Object> extras = cols("verifier_user_id", c.uid());
        if (!n.isEmpty())
        {
            extras.put("verify_note", n);
        }
        String by = "staff:" + c.uid();
        support.op(c, "intake_forward", "确认转发商家需求", "general", str(r.get("title")), true, null);
        if (service.isEmpty())
        {
            move(id, PENDING_VERIFY, UNASSIGNED, by, extras);
            notifyCityLeads(town, "商家需求等待平台安排", "「" + str(r.get("storeName")) + "」的「" + str(r.get("title")) + "」已核实，本城还没有商家服务人，已转平台处理", id);
            return result(id, UNASSIGNED, "已核实；本城还没有商家服务人，已转平台处理");
        }
        extras.put("service_user_id", serviceUserId);
        move(id, PENDING_VERIFY, FORWARDED, by, extras);
        forwardOut(r, id, town, service, serviceUserId, true);
        return result(id, FORWARDED, "已转给本城商家服务人");
    }

    /** 转发的共同部分：补授权、通知商家服务人、城市负责人；firstTime = 第一次转发时才告诉商家「已核实」，后台改派不再重复 */
    private void forwardOut(Map<String, Object> r, Long id, String town, Map<Long, Map<String, Object>> service, Long designated, boolean firstTime)
    {
        Long storeId = KgJdbc.asLong(r.get("storeId"));
        String type = str(r.get("requestType"));
        String title = str(r.get("title"));
        String storeName = str(r.get("storeName"));
        for (Map.Entry<Long, Map<String, Object>> e : service.entrySet())
        {
            if (storeId != null && (designated == null || designated.equals(e.getKey())))
            {
                ensureGrant(e.getKey(), storeId, StaffRole.MERCHANT_SERVICE);
            }
            support.notifyStaff(e.getKey(), MessageCategory.SYSTEM, "有商家需求转给你", "「" + storeName + "」的" + typeLabel(type) + "「" + title + "」已核实，请接手处理",
                    BIZ_TYPE, id, "/pages/staff/index?storeId=" + e.getValue().get("storeId"));
        }
        if (firstTime)
        {
            notifyMerchant(r, "需求已核实", "「" + title + "」已核实，转给商家服务人处理");
        }
        notifyCityLeads(town, "商家需求已转商家服务人", "「" + storeName + "」的「" + title + "」已转给本城商家服务人", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> intakeReturn(StaffSupport.Ctx c, Long id, String reason)
    {
        Map<String, Object> r = inCity(id, townOf(c));
        requireIntakeType(r);
        requireNotOwn(c, r);
        requireStatus(r, PENDING_VERIFY, "这条需求已经不是待核实状态，请刷新后查看");
        if (r.get("merchantUserId") == null)
        {
            throw new ServiceException("这条是代提交的，还没有商家账号，退回后没有人能接着修改。请核实后转发，或联系平台关闭", HttpStatus.CONFLICT);
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 500)
        {
            throw new ServiceException("请填写退回原因（500 字内），会发给商家", 422);
        }
        move(id, PENDING_VERIFY, RETURNED, "staff:" + c.uid(), cols("verifier_user_id", c.uid(), "return_reason", why));
        notifyMerchant(r, "需求被退回", "「" + str(r.get("title")) + "」被退回，原因：" + why + "。修改后可以再次提交");
        support.op(c, "intake_return", "退回商家需求", "general", str(r.get("title")), true, null);
        return result(id, RETURNED, "已退回商家");
    }

    /**
     * 换工主理人代商家提交代运营 / 抖音卖房：选本城商家店，或者只填店名（商家还没开通）。来源记 manager，进同一个待核实列表。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> intakeSubmit(StaffSupport.Ctx c, Map<String, Object> b)
    {
        String town = townOf(c);
        Map<String, Object> in = b == null ? Collections.<String, Object>emptyMap() : b;
        String type = requireExtraType(in.get("requestType"));
        Long merchantStore = KgJdbc.asLong(in.get("merchantStoreId"));
        Long merchant = null;
        String storeName;
        if (merchantStore != null)
        {
            Map<String, Object> s = storeOf(merchantStore);
            if (!town.equals(normalCity(s.get("city"))))
            {
                throw new ServiceException("只能替本城的商家提交", HttpStatus.FORBIDDEN);
            }
            Map<String, Object> g = db.one("select app_user_id from kg_staff_grant where store_id = :s and role_code = 'merchant' and status = '0' and del_flag = '0' order by grant_id limit 1",
                    p("s", merchantStore));
            if (g == null)
            {
                throw new ServiceException("这家店还不是合作商家，请直接填店名", 422);
            }
            merchant = KgJdbc.asLong(g.get("appUserId"));
            storeName = str(s.get("storeName"));
        }
        else
        {
            storeName = str(in.get("storeName")).trim();
            if (storeName.isEmpty() || storeName.length() > 64)
            {
                throw new ServiceException("请选择本城商家店，或填写店名（64 字内）", 422);
            }
        }
        Form f = Form.of(in, guard);
        Map<String, Object> out = toIntake(type, merchantStore, storeName, merchant, town, null, f.title(type, storeName), f.content, f.contact, f.phone, f.images, "manager", "staff:" + c.uid());
        support.op(c, "intake_submit", "代商家提交需求", "general", typeLabel(type) + " · " + storeName, true, null);
        return out;
    }

    private static String requireExtraType(Object raw)
    {
        String t = str(raw).trim();
        if (!MANAGED.equals(t) && !CLOUD_CHAIN.equals(t))
        {
            throw new ServiceException("请选择代运营或抖音卖房", 422);
        }
        return t;
    }

    /** 代运营 / 抖音卖房的简单表单：需求说明、联系人、电话、可传图 */
    private static final class Form
    {
        String titleText;
        String content;
        String contact;
        String phone;
        String images;

        static Form of(Map<String, Object> b, KgCommunityGuard guard)
        {
            Form f = new Form();
            f.titleText = str(b.get("title")).trim();
            if (f.titleText.length() > 64)
            {
                throw new ServiceException("标题最多 64 字", 422);
            }
            f.content = str(b.get("content")).replace("\r\n", "\n").trim();
            if (f.content.isEmpty() || f.content.length() > 2000)
            {
                throw new ServiceException("请填写需求说明（2000 字内）", 422);
            }
            f.contact = str(b.get("contactName")).trim();
            if (f.contact.isEmpty() || f.contact.length() > 32)
            {
                throw new ServiceException("请填写联系人（32 字内）", 422);
            }
            f.phone = str(b.get("contactPhone")).replaceAll("[\\s-]", "");
            if (!MOBILE.matcher(f.phone).matches())
            {
                throw new ServiceException("请填写 11 位手机号", 422);
            }
            f.images = images(b.get("images"));
            if (guard != null)
            {
                guard.check(f.titleText, f.content, f.contact);
            }
            return f;
        }

        String title(String type, String storeName)
        {
            return titleText.isEmpty() ? typeLabel(type) + " · " + storeName : titleText;
        }

        private static String images(Object raw)
        {
            if (raw == null || "".equals(raw))
            {
                return null;
            }
            if (!(raw instanceof List))
            {
                throw new ServiceException("图片格式不正确", 422);
            }
            List<String> out = new ArrayList<String>();
            for (Object o : (List<?>) raw)
            {
                String s = str(o).trim();
                if (s.isEmpty())
                {
                    continue;
                }
                if (!KgJdbc.isPlatformImage(s))
                {
                    throw new ServiceException("只能上传本平台的图片", 422);
                }
                if (!out.contains(s))
                {
                    out.add(s);
                }
            }
            if (out.size() > MAX_IMAGES)
            {
                throw new ServiceException("图片最多 " + MAX_IMAGES + " 张", 422);
            }
            return out.isEmpty() ? null : String.join(",", out);
        }
    }

    // ------------------------------------------------------------------ 换工主理人：派给他的商家岗位（/app/staff/merchant-jobs）

    /** 本城派单中的招募需求；点开切到那家店完善（他已被自动授权） */
    public Map<String, Object> dispatchedList(StaffSupport.Ctx c, String status, Integer pageNum, Integer pageSize)
    {
        String town = townOf(c);
        Map<String, Object> ps = p("town", town, "types", JOB_TYPES);
        String where = " and r.city = :town and r.request_type in (:types)"
                + statusFilter(status == null || status.trim().isEmpty() ? DISPATCHED : status, ps) + notOwnSql(c, ps);
        Map<String, Object> page = pageOf(where, ps, pageNum, pageSize);
        page.put("city", town);
        return page;
    }

    private Map<String, Object> dispatchedRequest(Long jobId)
    {
        Map<String, Object> r = db.one("select * from kg_merchant_request where job_id = :j and del_flag = '0'", p("j", jobId));
        if (r == null || !JOB_TYPES.contains(str(r.get("requestType"))))
        {
            throw new ServiceException("这个岗位不是派单的商家岗位，只能在后台上架", HttpStatus.FORBIDDEN);
        }
        requireStatus(r, DISPATCHED, "这个商家岗位已经处理过了，请刷新后查看");
        return r;
    }

    /** 完善页要回显的全部字段（和 POST /app/staff/jobs 的字段对应） */
    public Map<String, Object> dispatchedJob(StaffSupport.Ctx c, Long jobId)
    {
        Map<String, Object> r = db.one("select * from kg_merchant_request where job_id = :j and del_flag = '0'", p("j", jobId));
        if (r == null || !JOB_TYPES.contains(str(r.get("requestType"))))
        {
            throw new ServiceException("这个岗位不是派单的商家岗位，只能在后台上架", HttpStatus.FORBIDDEN);
        }
        Map<String, Object> job = db.must("select job_id, store_id, job_type, work_kind, title, cover, images, city, address, latitude, longitude, salary_type, salary_min, salary_unit, "
                + "bonus_desc, commission_desc, insurance_flag, month_redpacket, board_type, board_desc, start_date, shift_desc, daily_hours, shift_time, settle_desc, probation, train_desc, "
                + "gender_pref, gender_male_count, gender_female_count, rest_mode, room_type, min_days, headcount, tags, content, requirement, status, remark, "
                // 餐补、宠物、住宿照片和视频（V66）：给原值，图片解析成数组（同 images），老服务端没有这几个字段时页面按没填
                + "meal_allowance, meal_allowance_unit, pet_status, pet_images, lodging_images, lodging_video, lodging_video_cover from kg_job where job_id = :j and store_id = :s and del_flag = '0'",
                p("j", jobId, "s", c.storeId), "岗位不存在");
        job.put("images", com.ruoyi.kangaroo.domain.JobImages.read(str(job.get("images"))));
        Map<String, Object> extras = com.ruoyi.kangaroo.domain.JobExtras.stored(str(job.get("jobType")), str(job.get("boardType")), job.get("mealAllowance"), job.get("mealAllowanceUnit"),
                job.get("petStatus"), job.get("petImages"), job.get("lodgingImages"), job.get("lodgingVideo"), job.get("lodgingVideoCover"));
        extras.remove("jobType");
        extras.remove("boardType");
        job.putAll(extras);
        return p("job", job, "request", detailView(r, true), "canEdit", DISPATCHED.equals(str(r.get("status"))));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispatchedEdit(StaffSupport.Ctx c, Long jobId, Map<String, Object> body)
    {
        Map<String, Object> r = dispatchedRequest(jobId);
        Map<String, Object> out = staffJobs.updateMerchantDraft(c, jobId, body);
        // 标题跟着岗位走，后台列表和商家「我发布的」才对得上
        db.update("update kg_merchant_request set title = :t, content = coalesce(:body, content), update_by = :by, update_time = now() where request_id = :id",
                p("t", StaffSupport.cut(str(out.get("title")), 64), "body", out.get("content") == null ? null : StaffSupport.cut(str(out.get("content")), 2000),
                        "by", "staff:" + c.uid(), "id", r.get("requestId")));
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispatchedPublish(StaffSupport.Ctx c, Long jobId)
    {
        Map<String, Object> r = dispatchedRequest(jobId);
        Long id = KgJdbc.asLong(r.get("requestId"));
        String by = "staff:" + c.uid();
        jobs.publishMerchantDraft(jobId, by);
        move(id, DISPATCHED, PUBLISHED, by, cols("verifier_user_id", c.uid()));
        notifyMerchant(r, "岗位已上架", "「" + str(r.get("title")) + "」已由换工主理人完善并上架，有人报名会通知你");
        notifyCityLeads(str(r.get("city")), "商家岗位已上架", "「" + str(r.get("storeName")) + "」的「" + str(r.get("title")) + "」已上架", id);
        support.op(c, "merchant_job_publish", "上架商家岗位", "general", str(r.get("title")), true, null);
        return result(id, PUBLISHED, "已上架");
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispatchedClose(StaffSupport.Ctx c, Long jobId, String reason)
    {
        Map<String, Object> r = dispatchedRequest(jobId);
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 200)
        {
            throw new ServiceException("请填写不通过的原因（200 字内），会发给商家", 422);
        }
        Long id = KgJdbc.asLong(r.get("requestId"));
        String by = "staff:" + c.uid();
        jobs.closeMerchantDraft(jobId, by, why);
        move(id, DISPATCHED, CLOSED, by, cols("verifier_user_id", c.uid(), "return_reason", why));
        notifyMerchant(r, "岗位没有上架", "「" + str(r.get("title")) + "」没有通过，原因：" + why + "。可以按意见调整后重新发布");
        support.op(c, "merchant_job_close", "不通过商家岗位", "general", str(r.get("title")) + "：" + why, true, null);
        return result(id, CLOSED, "已关闭并通知商家");
    }

    // ------------------------------------------------------------------ 商家服务人（/app/staff/service）

    /** 本城已转过来的需求。status 不传 = 转过来的全部（已转 / 处理中 / 已完成） */
    public Map<String, Object> serviceList(StaffSupport.Ctx c, String status, String type, Integer pageNum, Integer pageSize)
    {
        String town = townOf(c);
        Map<String, Object> ps = p("town", town, "types", INTAKE_TYPES);
        String where = " and r.city = :town and r.request_type in (:types)" + typeFilter(type, ps) + notOwnSql(c, ps) + serviceNotBlocked(c);
        if (status == null || status.trim().isEmpty())
        {
            where += " and r.status in ('" + FORWARDED + "','" + ACCEPTED + "','" + DONE + "')";
        }
        else
        {
            String s = status.trim();
            if (!Arrays.asList(FORWARDED, ACCEPTED, DONE).contains(s))
            {
                throw new ServiceException("需求状态不正确", 422);
            }
            ps.put("st", s);
            where += " and r.status = :st";
        }
        Map<String, Object> page = pageOf(where, ps, pageNum, pageSize, c.uid());
        page.put("city", town);
        return page;
    }

    /** 还没转过来的（待核实、被退回、等平台）不给商家服务人看 */
    private Map<String, Object> forwardedInCity(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = inCity(id, townOf(c));
        requireIntakeType(r);
        requireNotOwn(c, r);
        if (!Arrays.asList(FORWARDED, ACCEPTED, DONE).contains(str(r.get("status"))))
        {
            throw new ServiceException("这条需求还没有转给商家服务人", HttpStatus.FORBIDDEN);
        }
        // 平台撤销了他在这家店的授权（或他在这家店是别的角色）：城市对得上也不能处理
        if (blockedFor(c, KgJdbc.asLong(r.get("storeId"))))
        {
            throw new ServiceException("你不能处理这家店的需求", HttpStatus.FORBIDDEN);
        }
        return r;
    }

    public Map<String, Object> serviceDetail(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = forwardedInCity(c, id);
        Map<String, Object> m = detailView(r, true);
        m.put("mine", c.uid().equals(KgJdbc.asLong(r.get("serviceUserId"))));
        m.put("canReturn", canServiceReturn(c, r, m.get("jobStatus")));
        return m;
    }

    /** 能不能退回给商家：转过来还没人接，或者自己接手中；岗位还没上架（代运营、抖音卖房没有岗位）；有商家账号能接着改 */
    private static boolean canServiceReturn(StaffSupport.Ctx c, Map<String, Object> r, Object jobStatus)
    {
        String st = str(r.get("status"));
        boolean holder = FORWARDED.equals(st) || (ACCEPTED.equals(st) && c.uid().equals(KgJdbc.asLong(r.get("serviceUserId"))));
        return holder && r.get("merchantUserId") != null && (r.get("jobId") == null || JobStatus.DRAFT.equals(str(jobStatus)));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> serviceAccept(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = forwardedInCity(c, id);
        requireStatus(r, FORWARDED, "这条需求已经有人接手了，请刷新后查看");
        Long storeId = KgJdbc.asLong(r.get("storeId"));
        // 城市负责人顶上时不补授权：范围是算出来的，补一行 merchant_service 反而把他在这家店变成商家服务人
        if (storeId != null && (c.cityLeadActing() ? blockedFor(c, storeId) : !ensureGrant(c.uid(), storeId, StaffRole.MERCHANT_SERVICE)))
        {
            throw new ServiceException("你不能处理这家店的需求", HttpStatus.FORBIDDEN);
        }
        move(id, FORWARDED, ACCEPTED, "staff:" + c.uid(), cols("service_user_id", c.uid()));
        notifyMerchant(r, "商家服务人已接手", "「" + str(r.get("title")) + "」已由商家服务人接手");
        support.op(c, "service_accept", "接手商家需求", "general", str(r.get("title")), true, null);
        return result(id, ACCEPTED, "已接手");
    }

    /** 接手之后只有接手的人能继续处理；要换人由平台在后台「重新派单」 */
    private Map<String, Object> mineAccepted(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = forwardedInCity(c, id);
        requireStatus(r, ACCEPTED, "请先接手这条需求");
        if (!c.uid().equals(KgJdbc.asLong(r.get("serviceUserId"))))
        {
            throw new ServiceException("这条需求已由其他商家服务人接手", HttpStatus.FORBIDDEN);
        }
        return r;
    }

    /** 线上任务上架：走和后台一样的校验（截止时间等）。上架后需求还是处理中，做完再标完成 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> servicePublish(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = mineAccepted(c, id);
        if (!ONLINE_TASK.equals(str(r.get("requestType"))) || r.get("jobId") == null)
        {
            throw new ServiceException("只有线上任务需要上架", 409);
        }
        jobs.publishMerchantDraft(KgJdbc.asLong(r.get("jobId")), "staff:" + c.uid());
        db.update("update kg_merchant_request set version = version + 1, handle_time = now(), update_by = :by, update_time = now() where request_id = :id",
                p("by", "staff:" + c.uid(), "id", id));
        notifyMerchant(r, "线上任务已上架", "「" + str(r.get("title")) + "」已上架，用户可以接单了");
        support.op(c, "service_publish", "上架线上任务", "general", str(r.get("title")), true, null);
        Map<String, Object> out = result(id, ACCEPTED, "已上架");
        out.put("jobStatus", JobStatus.PUBLISHED);
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> serviceDone(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = mineAccepted(c, id);
        if (ONLINE_TASK.equals(str(r.get("requestType"))) && r.get("jobId") != null)
        {
            Map<String, Object> j = db.one("select status from kg_job where job_id = :j and del_flag = '0'", p("j", r.get("jobId")));
            if (j != null && JobStatus.DRAFT.equals(str(j.get("status"))))
            {
                throw new ServiceException("线上任务还没上架，请先上架", HttpStatus.CONFLICT);
            }
        }
        move(id, ACCEPTED, DONE, "staff:" + c.uid(), null);
        notifyMerchant(r, "需求已完成", "「" + str(r.get("title")) + "」已由商家服务人处理完成");
        support.op(c, "service_done", "完成商家需求", "general", str(r.get("title")), true, null);
        return result(id, DONE, "已标记完成");
    }

    /**
     * 退回给商家修改（模板同 intakeReturn）：商家服务人的「审核不通过」。只能在岗位还没上架时退：上架后已经有人能接单，要下架找平台。
     * 接手人清掉：商家改完要重新核实、重新转发，到时候可能是另一位接。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> serviceReturn(StaffSupport.Ctx c, Long id, String reason)
    {
        Map<String, Object> r = forwardedInCity(c, id);
        String from = str(r.get("status"));
        if (ACCEPTED.equals(from))
        {
            if (!c.uid().equals(KgJdbc.asLong(r.get("serviceUserId"))))
            {
                throw new ServiceException("这条需求已由其他商家服务人接手", HttpStatus.FORBIDDEN);
            }
        }
        else if (!FORWARDED.equals(from))
        {
            throw new ServiceException("这条需求已经办完了，不能退回", HttpStatus.CONFLICT);
        }
        if (r.get("merchantUserId") == null)
        {
            throw new ServiceException("这条是代提交的，还没有商家账号，退回后没有人能接着修改。请联系平台关闭", HttpStatus.CONFLICT);
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 500)
        {
            throw new ServiceException("请填写退回原因（500 字内），会发给商家", 422);
        }
        if (r.get("jobId") != null)
        {
            // 加锁读：和上架（publishMerchantDraft 也锁这一行）互斥，不会一边退回、一边已经上架
            Map<String, Object> j = db.one("select status from kg_job where job_id = :j and del_flag = '0' for update", p("j", r.get("jobId")));
            if (j == null || !JobStatus.DRAFT.equals(str(j.get("status"))))
            {
                throw new ServiceException("这个任务已经上架，不能退回；要下架请联系平台", HttpStatus.CONFLICT);
            }
        }
        move(id, from, RETURNED, "staff:" + c.uid(), cols("return_reason", why, "service_user_id", null));
        notifyMerchant(r, "需求被退回", "「" + str(r.get("title")) + "」被退回，原因：" + why + "。修改后可以再次提交");
        support.op(c, "service_return", "退回商家需求", "general", str(r.get("title")) + "：" + why, true, null);
        return result(id, RETURNED, "已退回商家");
    }

    // ------------------------------------------------------------------ 商家服务人：审核商家活动（/app/staff/service/activities）

    /**
     * 本城待审核的商家活动：城市在 Java 里归一后比（kg_activity.city 是门店城市原文，同 people），自己发起的、最后是他保存的、这家店不让他碰的不算。
     * 角标（hubCounts）和列表都走这里，同一个口径。门店没设城市（town 为空）时一条都没有。
     */
    private List<Map<String, Object>> pendingActivities(StaffSupport.Ctx c, String town)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (town == null || town.isEmpty())
        {
            return out;
        }
        for (Map<String, Object> a : db.list("select a.*, s.store_name, u.nickname creator from kg_activity a join kg_store s on s.store_id = a.store_id "
                + "left join kg_app_user u on u.user_id = a.app_user_id "
                + "where a.del_flag = '0' and a.status = 'pending' and a.source = 'merchant' and s.status = '0' and s.del_flag = '0' "
                + "and (a.app_user_id is null or a.app_user_id <> :me) and (a.update_by is null or a.update_by <> :meBy)" + notBlockedAt("a.store_id")
                + " order by coalesce(a.update_time, a.create_time), a.activity_id", p("me", c.uid(), "meBy", "staff:" + c.uid())))
        {
            if (town.equals(normalCity(a.get("city"))))
            {
                out.add(a);
            }
        }
        return out;
    }

    /** 审核要看的字段。提交时间：被驳回改完再交的，算最后这次保存（update_time） */
    private static Map<String, Object> activityView(Map<String, Object> a)
    {
        Long fee = KgJdbc.asLong(a.get("feeFen"));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("activityId", a.get("activityId"));
        m.put("title", a.get("title"));
        m.put("cover", a.get("cover"));
        m.put("category", a.get("category"));
        m.put("startTime", a.get("startTime"));
        m.put("endTime", a.get("endTime"));
        m.put("deadline", a.get("deadline"));
        m.put("city", a.get("city"));
        m.put("place", a.get("place"));
        m.put("feeFen", fee == null ? 0L : fee);
        m.put("feeMode", a.get("feeMode"));
        m.put("feeIncludes", a.get("feeIncludes"));
        // 2026-11-03 费用三选一：AA制（fee_mode = aa）写「AA制 / 约¥80 AA」，审核时看得出不是免费
        m.put("priceText", "aa".equals(String.valueOf(a.get("feeMode"))) ? KgActivityService.priceText(a)
                : (fee == null || fee == 0L ? "免费" : KgStats.yuanText(fee)));
        m.put("capacity", a.get("capacity"));
        m.put("intro", a.get("intro"));
        m.put("safetyNote", a.get("safetyNote"));
        m.put("storeId", a.get("storeId"));
        m.put("storeName", a.get("storeName"));
        m.put("organizerName", a.get("organizerName"));
        m.put("creator", str(a.get("creator")).isEmpty() ? a.get("organizerName") : a.get("creator"));
        m.put("submitTime", a.get("updateTime") == null ? a.get("createTime") : a.get("updateTime"));
        m.put("status", a.get("status"));
        m.put("statusLabel", "待审核");
        return m;
    }

    /** 分页形状和 db.page 一样；城市要在 Java 里比，所以是查出来再切（本城待审核的活动不会多） */
    public Map<String, Object> serviceActivities(StaffSupport.Ctx c, String status, Integer pageNum, Integer pageSize)
    {
        String town = townOf(c);
        if (status != null && !status.trim().isEmpty() && !"pending".equals(status.trim()))
        {
            throw new ServiceException("活动状态不正确", 422);
        }
        List<Map<String, Object>> all = pendingActivities(c, town);
        int size = pageSize == null ? 10 : Math.max(1, Math.min(50, pageSize));
        int num = pageNum == null ? 1 : Math.max(1, Math.min(100000, pageNum));
        int from = Math.min((num - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : all.subList(from, to))
        {
            rows.add(activityView(a));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("total", (long) all.size());
        out.put("hasMore", to < all.size());
        out.put("pageNum", num);
        out.put("pageSize", size);
        out.put("city", town);
        return out;
    }

    /** 通过 / 驳回。KgActivityService.audit 不查门店和城市，这里先把住；通知商家、开放报名都在 audit 里 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> serviceActivityAudit(StaffSupport.Ctx c, Long id, Boolean pass, String reason)
    {
        String town = townOf(c);
        Map<String, Object> a = db.one("select activity_id, source, store_id, app_user_id, update_by, city, title, status from kg_activity where activity_id = :id and del_flag = '0'", p("id", id));
        if (a == null)
        {
            throw new ServiceException("活动不存在", 404);
        }
        Long store = KgJdbc.asLong(a.get("storeId"));
        if (store == null || !"merchant".equals(str(a.get("source"))))
        {
            throw new ServiceException("这个活动不归商家服务人审核", HttpStatus.FORBIDDEN);
        }
        if (!town.equals(normalCity(a.get("city"))))
        {
            throw new ServiceException("这个活动不属于本城", HttpStatus.FORBIDDEN);
        }
        if (c.uid().equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            throw new ServiceException("不能审核自己提交的活动", HttpStatus.FORBIDDEN);
        }
        // 先判已处理：驳回也会把 update_by 写成审核人，他在旧页面上再点一次要看到「已经处理过了」，不是下面的「最后是你修改的」
        if (!"pending".equals(str(a.get("status"))))
        {
            throw new ServiceException("这个活动已经处理过了，请刷新后查看", HttpStatus.CONFLICT);
        }
        // 只比发起人不够：兼任的小院主理人有这家店的活动编辑权，改同事的活动会回到待审核，这时最后保存的就是他（staffSave 写 update_by）
        if (("staff:" + c.uid()).equals(str(a.get("updateBy"))))
        {
            throw new ServiceException("这场活动最后是你修改的，不能自己审核，请交给本城其他商家服务人或平台", HttpStatus.FORBIDDEN);
        }
        if (blockedFor(c, store))
        {
            throw new ServiceException("你不能处理这家店的活动", HttpStatus.FORBIDDEN);
        }
        if (pass == null)
        {
            throw new ServiceException("请选择通过或驳回", 422);
        }
        String why = reason == null ? "" : reason.trim();
        // kg_activity.audit_reason 是 varchar(255)
        if (!pass && (why.isEmpty() || why.length() > 255))
        {
            throw new ServiceException("请填写驳回原因（255 字内），会发给商家", 422);
        }
        activities.audit(id, pass, pass ? null : why, "staff:" + c.uid());
        String title = str(a.get("title"));
        support.op(c, "service_activity_audit", pass ? "通过商家活动" : "驳回商家活动", "general", pass ? title : title + "：" + why, true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("activityId", id);
        out.put("status", pass ? "open" : "rejected");
        out.put("statusLabel", pass ? "报名中" : "已驳回");
        out.put("message", pass ? "已通过，开始报名" : "已驳回并通知商家");
        return out;
    }

    /**
     * 商家活动进入待审核（KgActivityService.staffSave 在事务里调）：告诉本城商家服务人（含兼任），点开去审核页。
     * 消息类型用 merchant_request：只有商家服务人角色的账号，消息中心只放行这一类（KgMessageMapper）；门店活动通知走 notifyStore 会把他们滤掉。
     *
     * 提交活动绝不能因为发消息失败而失败，所以事务提交后才发、每人一个新事务：messages.send 自己带 @Transactional，
     * 在外层事务里一抛错，外层就被标记成只能回滚，这里 catch 住也救不回来（提交时 UnexpectedRollbackException）。
     * 提交后的发送不能再借外层的连接（已提交，写了没有下一次提交），所以要 REQUIRES_NEW。
     */
    public void activityPending(Long activityId, Long storeId, String storeName, String title, Object rawCity, Long submitter)
    {
        try
        {
            if (TransactionSynchronizationManager.isSynchronizationActive())
            {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        sendActivityPending(activityId, storeId, storeName, title, rawCity, submitter);
                    }
                });
            }
            else
            {
                sendActivityPending(activityId, storeId, storeName, title, rawCity, submitter);
            }
        }
        catch (RuntimeException e)
        {
            log.warn("待审核活动提醒没有发出 activityId={}", activityId, e);
        }
    }

    private void sendActivityPending(Long activityId, Long storeId, String storeName, String title, Object rawCity, Long submitter)
    {
        try
        {
            // 和商家需求同一个开关（ROUTING_KEY）：新版小程序覆盖之前，老版本没有审核页，提醒点开是空的；这段时间活动只走后台审核
            if (!staffRouting())
            {
                return;
            }
            for (Map.Entry<Long, Map<String, Object>> e : eligibleService(normalCity(rawCity), submitter, storeId).entrySet())
            {
                Long uid = e.getKey();
                String route = ACTIVITY_AUDIT_PAGE + "?storeId=" + e.getValue().get("storeId") + "&id=" + activityId;
                try
                {
                    inNewTransaction(() -> support.notifyStaff(uid, MessageCategory.SYSTEM, "有商家活动待审核", "「" + storeName + "」提交了活动「" + title + "」，请审核", BIZ_TYPE, activityId, route));
                }
                catch (RuntimeException ex)
                {
                    // 一个人发失败不影响下一个
                    log.warn("待审核活动提醒发送失败 activityId={} uid={}", activityId, uid, ex);
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("待审核活动提醒没有发出 activityId={}", activityId, e);
        }
    }

    /** 手工 new 出来、没注入事务管理器的测试直接跑 */
    private void inNewTransaction(Runnable r)
    {
        if (transactions == null)
        {
            r.run();
            return;
        }
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.execute(status -> {
            r.run();
            return null;
        });
    }

    // ------------------------------------------------------------------ 城市负责人（/app/staff/city，只读）

    public Map<String, Object> cityList(StaffSupport.Ctx c, String status, String type, Integer pageNum, Integer pageSize)
    {
        String town = townOf(c);
        Map<String, Object> ps = p("town", town);
        Map<String, Object> page = pageOf(" and r.city = :town" + statusFilter(status, ps) + typeFilter(type, ps), ps, pageNum, pageSize);
        Object rows = page.get("rows");
        if (rows instanceof List)
        {
            for (Object o : (List<?>) rows)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                m.put("title", maskText(m.get("title")));
            }
        }
        page.put("city", town);
        return page;
    }

    public Map<String, Object> cityDetail(StaffSupport.Ctx c, Long id)
    {
        return detailView(inCity(id, townOf(c)), false);
    }

    // ------------------------------------------------------------------ 商家：我发布的、代运营 / 抖音卖房（/app/staff/merchant/requests）

    /** 只看这家店的；状态和退回原因能看，核实备注、接手人不给 */
    private Map<String, Object> merchantView(Map<String, Object> r)
    {
        Map<String, Object> m = brief(r);
        m.put("content", r.get("content"));
        m.put("contactName", r.get("contactName"));
        m.put("contactPhone", r.get("contactPhone"));
        m.put("images", imageList(r.get("images")));
        m.put("returnReason", r.get("returnReason"));
        m.put("canEdit", RETURNED.equals(str(r.get("status"))));
        return m;
    }

    public Map<String, Object> merchantList(StaffSupport.Ctx c, String status, Integer pageNum, Integer pageSize)
    {
        Map<String, Object> ps = p("s", c.storeId);
        Map<String, Object> page = db.page("select r.*, j.status job_status from kg_merchant_request r left join kg_job j on j.job_id = r.job_id "
                + "where r.del_flag = '0' and r.store_id = :s" + statusFilter(status, ps) + " order by r.request_id desc", ps, pageNum, pageSize);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Object raw = page.get("rows");
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) o;
                rows.add(merchantView(r));
            }
        }
        page.put("rows", rows);
        return page;
    }

    private Map<String, Object> ownStore(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = mustRequest(id);
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(r.get("storeId"))))
        {
            throw new ServiceException("这条需求不属于当前门店", HttpStatus.FORBIDDEN);
        }
        return r;
    }

    public Map<String, Object> merchantDetail(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> r = ownStore(c, id);
        Map<String, Object> m = merchantView(r);
        Long jobId = KgJdbc.asLong(r.get("jobId"));
        if (jobId != null)
        {
            Map<String, Object> job = jobOf(jobId);
            m.put("job", job);
            if (job != null)
            {
                m.put("jobStatus", job.get("jobStatus"));
                m.put("statusLabel", statusLabel(str(r.get("status")), job.get("jobStatus")));
            }
        }
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> merchantSubmit(StaffSupport.Ctx c, Map<String, Object> b)
    {
        Map<String, Object> in = b == null ? Collections.<String, Object>emptyMap() : b;
        String type = requireExtraType(in.get("requestType"));
        if (MANAGED.equals(type)) merchantAgreements.requireAccepted(c, type);
        Form f = Form.of(in, guard);
        Map<String, Object> store = storeOf(c.storeId);
        String storeName = str(store.get("storeName"));
        Map<String, Object> out = toIntake(type, c.storeId, storeName, c.uid(), normalCity(store.get("city")), null, f.title(type, storeName), f.content, f.contact, f.phone, f.images, "merchant", "staff:" + c.uid());
        support.op(c, "merchant_request", "提交" + typeLabel(type), "general", storeName, true, null);
        return out;
    }

    /**
     * 被退回后修改再交。代运营 / 抖音卖房：改需求说明、联系人、电话、图片；线上任务：和发布线上任务同一份表单。
     * 重新看一次本城有没有换工主理人：有就回到待核实，没有就等平台。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> merchantResubmit(StaffSupport.Ctx c, Long id, Map<String, Object> b)
    {
        Map<String, Object> r = ownStore(c, id);
        requireIntakeType(r);
        requireStatus(r, RETURNED, "只有被退回的需求才能修改再提交");
        Map<String, Object> in = b == null ? Collections.<String, Object>emptyMap() : b;
        String type = str(r.get("requestType"));
        String storeName = str(r.get("storeName"));
        String town = str(r.get("city"));
        if (MANAGED.equals(type)) merchantAgreements.requireAccepted(c, type);
        // 核实人清掉：改过的内容要重新核实，后台重新派单靠这一列判断「核实过没有」。核实备注留着，下一位核实时能看到
        Map<String, Object> extras = cols("return_reason", "", "verifier_user_id", null);
        String title;
        if (ONLINE_TASK.equals(type))
        {
            onlineTasks.updateDraftTask(c, KgJdbc.asLong(r.get("jobId")), in);
            title = str(in.get("title")).trim();
            String summary = str(in.get("description")).trim();
            extras.put("title", StaffSupport.cut(title, 64));
            extras.put("content", StaffSupport.cut(summary.isEmpty() ? str(in.get("deliverables")).trim() : summary, 2000));
        }
        else
        {
            Form f = Form.of(in, guard);
            title = f.title(type, storeName);
            extras.put("title", StaffSupport.cut(title, 64));
            extras.put("content", f.content);
            extras.put("contact_name", f.contact);
            extras.put("contact_phone", f.phone);
            extras.put("images", f.images);
        }
        Map<Long, Map<String, Object>> managers = intakeManagers(town, c.uid());
        String status = managers.isEmpty() ? UNASSIGNED : PENDING_VERIFY;
        move(id, RETURNED, status, "staff:" + c.uid(), extras);
        notifyIntake(id, managers, type, storeName, title, town, c.uid());
        support.op(c, "merchant_resubmit", "修改再提交需求", "general", title, true, null);
        return result(id, status, managers.isEmpty() ? waitingMessage(town, c.uid()) : "已重新提交，本城换工主理人会尽快核实");
    }

    // ------------------------------------------------------------------ 工作台

    /** hub 上的待办数；只给对应角色，别的角色不多查一次库 */
    public Map<String, Object> hubCounts(StaffSupport.Ctx c)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        boolean manager = StaffRole.MANAGER.equals(c.role);
        // 商家服务人本身，或这家店上兼任的小院主理人（普通小院主理人没有这些数）
        boolean serving = StaffRole.MERCHANT_SERVICE.equals(c.role) || alsoService(c);
        boolean lead = StaffRole.CITY_LEAD.equals(c.role);
        if (!manager && !serving && !lead)
        {
            return m;
        }
        Map<String, Object> s = db.one("select city from kg_store where store_id = :s and status = '0' and del_flag = '0'", p("s", c.storeId));
        String town = s == null ? "" : normalCity(s.get("city"));
        // 切换后城市负责人也顶换工主理人和商家服务人（pick）：这几个数他也要有，口径和他在那两个入口里看到的列表一样
        boolean full = lead && RolePhase.isNew();
        if (manager || full)
        {
            m.put("intakePending", count(c, town, PENDING_VERIFY, INTAKE_TYPES, ""));
            m.put("merchantJobsPending", count(c, town, DISPATCHED, JOB_TYPES, ""));
        }
        if (full)
        {
            StaffSupport.Ctx service = standIn(c, StaffRole.MERCHANT_SERVICE);
            m.put("servicePending", count(service, town, FORWARDED, INTAKE_TYPES, serviceNotBlocked(service)));
            m.put("serviceAuditPending", (long) pendingActivities(service, town).size());
        }
        else if (serving)
        {
            m.put("servicePending", count(c, town, FORWARDED, INTAKE_TYPES, SERVICE_NOT_BLOCKED));
            m.put("serviceAuditPending", (long) pendingActivities(c, town).size());
        }
        if (lead)
        {
            m.put("cityUnassigned", town.isEmpty() ? 0L : db.count("select count(*) from kg_merchant_request where city = :c and status = 'unassigned' and del_flag = '0'", p("c", town)));
            if (full)
            {
                // 工作台「活动退款」入口的角标：只算他能审的（同 KgCityFinanceService）：他范围内的门店，自己的报名他审不了，算进来就永远消不掉
                List<Long> mine = new ArrayList<Long>();
                for (StaffGrantView g : c.me.getStaffGrants())
                {
                    if (StaffRole.CITY_LEAD.equals(g.getRoleCode()))
                    {
                        mine.add(g.getStoreId());
                    }
                }
                m.put("cityRefundPending", mine.isEmpty() ? 0L : db.count("select count(*) from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id "
                        + "where g.status = 'refund_pending' and a.del_flag = '0' and g.app_user_id <> :u and a.store_id in (:ids)", p("u", c.uid(), "ids", mine)));
            }
        }
        return m;
    }

    /** 角标和列表同一口径：自己提交的、这家店不让他碰的不算 */
    private long count(StaffSupport.Ctx c, String town, String status, List<String> types, String extra)
    {
        if (town.isEmpty())
        {
            return 0L;
        }
        Map<String, Object> ps = p("c", town, "s", status, "t", types);
        return db.count("select count(*) from kg_merchant_request r where r.city = :c and r.status = :s and r.request_type in (:t) and r.del_flag = '0'" + notOwnSql(c, ps) + extra, ps);
    }

    // ------------------------------------------------------------------ 老板（后台）

    /** 等平台处理的条数：后台铃铛轮询 */
    public long pendingCount()
    {
        return db.count("select count(*) from kg_merchant_request where status = 'unassigned' and del_flag = '0'", p());
    }

    /** 最近 24 小时派给本地（换工主理人、商家服务人）的条数：老板不用处理，但要知道；铃铛上是一个小红点 */
    public long recentDispatchedCount()
    {
        return db.count("select count(*) from kg_merchant_request where status <> 'unassigned' and del_flag = '0' and create_time >= date_sub(now(), interval 1 day)", p());
    }

    /**
     * 重新派单 / 改派：本城现在有人了就走正常流程。招募类 → 派单；核实过的线上类 → 转商家服务人；没核实过的线上类 → 给换工主理人核实。
     * 接手人、转给谁都清掉（接手的人离职、被撤授权时靠这个换人）。门店城市在后台改过的，按门店现在的城市算。
     * 没人时：本来就在等平台的 409、状态不变；已经派出去的退回「等待平台安排」，铃铛能看到。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> redispatch(Long id, String operator)
    {
        if (!staffRouting())
        {
            throw new ServiceException("派单流程还没打开：等新版小程序发布、角色切换完成后，到后台「角色切换」页点「打开派单」。现在可以到岗位管理直接上架，或关闭、标记完成", HttpStatus.CONFLICT);
        }
        Map<String, Object> r = mustRequest(id);
        String from = str(r.get("status"));
        if (!REDISPATCH_FROM.contains(from))
        {
            throw new ServiceException("这条需求已经结束或退回商家了，不能重新派单", HttpStatus.CONFLICT);
        }
        Long storeId = KgJdbc.asLong(r.get("storeId"));
        Long merchant = KgJdbc.asLong(r.get("merchantUserId"));
        String town = str(r.get("city"));
        if (storeId != null)
        {
            Map<String, Object> s = db.one("select city from kg_store where store_id = :s and del_flag = '0'", p("s", storeId));
            String now = s == null ? "" : normalCity(s.get("city"));
            if (!now.isEmpty())
            {
                town = now;
            }
        }
        Map<String, Object> extras = cols("city", town, "service_user_id", null);
        String type = str(r.get("requestType"));
        String storeName = str(r.get("storeName"));
        String title = str(r.get("title"));
        if (JOB_TYPES.contains(type))
        {
            List<Map.Entry<Long, Map<String, Object>>> managers = dispatchTo(town, storeId, merchant);
            if (managers.isEmpty())
            {
                return backToPlatform(id, from, town, managerWord(town, merchant), operator, extras);
            }
            move(id, from, DISPATCHED, operator, extras);
            afterDispatch(id, managers, storeId, storeName, type, title, town, merchant);
            if (UNASSIGNED.equals(from))
            {
                notifyMerchant(r, "招募已派单", "「" + title + "」已派给本城换工主理人");
            }
            return result(id, DISPATCHED, "已派给本城换工主理人");
        }
        if (r.get("verifierUserId") != null)
        {
            Map<Long, Map<String, Object>> service = eligibleService(town, merchant, storeId);
            if (service.isEmpty())
            {
                return backToPlatform(id, from, town, "商家服务人", operator, extras);
            }
            move(id, from, FORWARDED, operator, extras);
            forwardOut(r, id, town, service, null, UNASSIGNED.equals(from));
            return result(id, FORWARDED, "已转给本城商家服务人");
        }
        Map<Long, Map<String, Object>> managers = people(town, StaffRole.MANAGER, merchant);
        if (managers.isEmpty())
        {
            return backToPlatform(id, from, town, managerWord(town, merchant), operator, extras);
        }
        move(id, from, PENDING_VERIFY, operator, extras);
        notifyIntake(id, managers, type, storeName, title, town, merchant);
        return result(id, PENDING_VERIFY, "已转给本城换工主理人核实");
    }

    /** 后台重新派单没人可派时的说法：本城唯一的换工主理人就是商家本人时，要说清楚不是没开通，是不能派给自己 */
    private String managerWord(String town, Long merchant)
    {
        return onlySelfManages(town, merchant) ? "其他换工主理人（商家本人不能派给自己）" : "换工主理人";
    }

    /** 重新派单时本城没有人：等平台的照旧提示去开通；已派出去的退回等平台 */
    private Map<String, Object> backToPlatform(Long id, String from, String town, String who, String operator, Map<String, Object> extras)
    {
        String place = town.isEmpty() ? "未设置城市" : town;
        if (UNASSIGNED.equals(from))
        {
            throw new ServiceException("本城（" + place + "）还没有" + who + "，请先在员工授权里开通", HttpStatus.CONFLICT);
        }
        move(id, from, UNASSIGNED, operator, extras);
        return result(id, UNASSIGNED, "本城（" + place + "）现在没有" + who + "，已退回等待平台安排");
    }

    /**
     * 后台关闭（填原因，会发给商家）：岗位还是草稿的一并关闭；已上架的线上任务要先到岗位管理关闭，免得需求关了任务还在接单。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminClose(Long id, String reason, String operator)
    {
        Map<String, Object> r = mustRequest(id);
        String from = str(r.get("status"));
        if (!CLOSE_FROM.contains(from))
        {
            throw new ServiceException("这条需求已经结束了，不用再关闭", HttpStatus.CONFLICT);
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 200)
        {
            throw new ServiceException("请填写关闭原因（200 字内），会发给商家", 422);
        }
        Long jobId = KgJdbc.asLong(r.get("jobId"));
        Map<String, Object> job = jobId == null ? null : db.one("select status from kg_job where job_id = :j and del_flag = '0'", p("j", jobId));
        if (job != null)
        {
            String st = str(job.get("status"));
            if (JobStatus.PUBLISHED.equals(st) || JobStatus.PAUSED.equals(st))
            {
                throw new ServiceException("岗位还在上架中，请先到岗位管理关闭岗位，再关闭这条需求", HttpStatus.CONFLICT);
            }
            if (JobStatus.DRAFT.equals(st))
            {
                jobs.closeMerchantDraft(jobId, operator, why);
            }
        }
        move(id, from, CLOSED, operator, cols("return_reason", why));
        notifyMerchant(r, "需求已关闭", "「" + str(r.get("title")) + "」已由平台关闭，原因：" + why);
        return result(id, CLOSED, "已关闭并通知商家");
    }

    /** 后台标记完成：代运营、抖音卖房线下办完的（线上任务要走上架和交付，不走这里） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminDone(Long id, String operator)
    {
        Map<String, Object> r = mustRequest(id);
        String type = str(r.get("requestType"));
        if (!MANAGED.equals(type) && !CLOUD_CHAIN.equals(type))
        {
            throw new ServiceException("只有代运营、抖音卖房能在这里标记完成", HttpStatus.CONFLICT);
        }
        String from = str(r.get("status"));
        if (!DONE_FROM.contains(from))
        {
            throw new ServiceException("这条需求已经结束了", HttpStatus.CONFLICT);
        }
        move(id, from, DONE, operator, null);
        notifyMerchant(r, "需求已完成", "「" + str(r.get("title")) + "」已由平台处理完成");
        return result(id, DONE, "已标记完成");
    }
}
