package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.manage.KgStats.delta;
import static com.ruoyi.kangaroo.ext.manage.KgStats.deltaBp;
import static com.ruoyi.kangaroo.ext.manage.KgStats.num;
import static com.ruoyi.kangaroo.ext.manage.KgStats.pct;
import static com.ruoyi.kangaroo.ext.manage.KgStats.str;
import static com.ruoyi.kangaroo.ext.manage.KgStats.yuanText;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgJobReviewDimensions;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.ext.staff.RolePhase;
import com.ruoyi.kangaroo.ext.staff.KgVolunteerDailyService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.admin.AdminCityContext;

/**
 * 经营：代运营周报、每日经营报表、数据看板。对换工主理人、小院主理人和员工开放，门店范围每次回库读当前授权，
 * 不只信令牌里的授权快照。所有数字来自 {@link KgStats}，没有数据时给 null（页面显示「—」）。
 */
@Service
public class KgManageService
{
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityLeadScope cityLeads;

    /** 按城市授权的换工主理人、小院主理人（门店开通后在本城按所选角色算）；单测没注入时不展开 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityGrantScope cityGrants;

    static final List<String> ROLES = Arrays.asList("manager", "keeper", "staff");

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgStats stats;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgCityService cities;

    @Autowired
    private KgVolunteerDailyService volunteers;

    @Lazy
    @Autowired
    private KgMerchantRequestService merchantRequests;

    // ---------------------------------------------------------------- 门店范围

    /**
     * 当前账号作为 manager / keeper 生效授权的门店（回库读）。
     * 口径必须与登录态 KgStaffGrantMapper.selectActiveGrantsWithStore 一致：授权有效且门店启用、未删除。
     * 原先漏了 s.status，门店停用后主理人仍能读写该店的日报、周报和看板。
     */
    public List<Map<String, Object>> myStores(Long uid)
    {
        // 切换后 staff 退役：残留的 staff 行不再算经营门店
        return withCityLead(uid, withCityGrants(uid, db.list("select g.store_id, g.role_code, s.store_name, s.cover, s.city, s.address from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.app_user_id = :u and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0' and g.role_code in " + (RolePhase.isNew() ? "('manager','keeper') " : "('manager','keeper','staff') ")
                + "order by g.grant_id",
                p("u", uid)), RolePhase.isNew() ? Arrays.asList("manager", "keeper") : ROLES));
    }

    /**
     * 按城市授权的换工主理人、小院主理人（store_id 为空、只有城市）：本城门店按所选角色算经营门店（CityGrantScope，和登录态同一套规则）。
     * 这家店已有门店授权或撤销记录的以门店授权为准；行和上面 SQL 同样的列，只影响这次请求，库里不动
     */
    private List<Map<String, Object>> withCityGrants(Long uid, List<Map<String, Object>> own, List<String> roles)
    {
        Map<Long, com.ruoyi.kangaroo.security.StaffGrantView> covered = cityGrants == null ? null : cityGrants.stores(uid);
        if (covered == null || covered.isEmpty())
        {
            return own;
        }
        Set<Long> seen = new LinkedHashSet<Long>(ids(own));
        List<Long> extra = new ArrayList<Long>();
        for (Map.Entry<Long, com.ruoyi.kangaroo.security.StaffGrantView> e : covered.entrySet())
        {
            if (roles.contains(e.getValue().getRoleCode()) && seen.add(e.getKey()))
            {
                extra.add(e.getKey());
            }
        }
        if (extra.isEmpty())
        {
            return own;
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(own);
        for (Map<String, Object> s : db.list("select store_id, store_name, cover, city, address from kg_store where store_id in (:ids) and status = '0' and del_flag = '0' order by store_id",
                p("ids", extra)))
        {
            Long id = KgJdbc.asLong(s.get("storeId"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("storeId", id);
            row.put("roleCode", covered.get(id).getRoleCode());
            row.put("storeName", s.get("storeName"));
            row.put("cover", s.get("cover"));
            row.put("city", s.get("city"));
            row.put("address", s.get("address"));
            out.add(row);
        }
        return out;
    }

    /**
     * 看经营数字（营业额、好评率）的门店。切换后不算派单补的授权：那种授权只管岗位和报名，不碰钱（StaffSupport.autoManager），
     * 补给本城每一位换工主理人，算进来每个人都能看到每家商家店的钱。日报、周报、招募账还要用自动授权的门店，继续走 myStores。
     * 城市负责人算他能管的全部门店。
     */
    public List<Map<String, Object>> myBusinessStores(Long uid)
    {
        return RolePhase.isNew() ? withCityLead(uid, withCityGrants(uid, myOwnBusinessStores(uid), Arrays.asList("manager", "keeper"))) : myStores(uid);
    }

    /** 同上，但不按城市展开，只算自己的授权：荣誉进度、月营收里程碑用这个，当了城市负责人不等于全城的营收都算他的 */
    public List<Map<String, Object>> myOwnBusinessStores(Long uid)
    {
        if (!RolePhase.isNew())
        {
            return myStores(uid);
        }
        return db.list("select g.store_id, g.role_code, s.store_name, s.cover, s.city, s.address from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.app_user_id = :u and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0' and g.role_code in ('manager','keeper') "
                + "and not (g.role_code = 'manager' and ifnull(g.granted_by, '') = :auto) order by g.grant_id",
                p("u", uid, "auto", KgMerchantRequestService.GRANTED_BY));
    }

    /**
     * 城市负责人能管的门店也算经营门店（切换后）。这个文件里日报、周报整套都按行上的 role_code 分经营账（小院主理人）和招募账（换工主理人），
     * 他两边都能做：这一行按这次请求顶谁来写，不用改下面每一处判断；只影响这次请求，库里不动。这家店同时有真实授权的，以城市负责人这一行为准。
     */
    private List<Map<String, Object>> withCityLead(Long uid, List<Map<String, Object>> own)
    {
        Map<Long, Map<String, Object>> covered = cityLeads == null ? new LinkedHashMap<Long, Map<String, Object>>() : cityLeads.stores(uid);
        if (covered.isEmpty())
        {
            return own;
        }
        String acting = cityLeadRecruit() ? StaffRole.MANAGER : StaffRole.KEEPER;
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> s : own)
        {
            if (!covered.containsKey(KgJdbc.asLong(s.get("storeId"))))
            {
                out.add(s);
            }
        }
        for (Map<String, Object> s : covered.values())
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>(s);
            row.put("roleCode", acting);
            out.add(row);
        }
        return out;
    }

    /** 小程序招募日报、招募周报入口带的请求头；打开一张招募周报时 reportStore 也会设上（消息里点进来的） */
    private static final String REPORT_AS_HEADER = "X-Report-As";
    private static final String REPORT_AS_ATTR = "kangaroo.cityLeadRecruit";

    private static boolean cityLeadRecruit()
    {
        org.springframework.web.context.request.RequestAttributes attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes))
        {
            return false;
        }
        javax.servlet.http.HttpServletRequest request = ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
        return Boolean.TRUE.equals(request.getAttribute(REPORT_AS_ATTR)) || "recruit".equals(request.getHeader(REPORT_AS_HEADER));
    }

    AppLoginUser me()
    {
        return AppUserContext.requireStaff();
    }

    /** 取门店：传了就必须是自己已授权的经营门店；没传取第一家 */
    Map<String, Object> store(Long uid, Long storeId)
    {
        List<Map<String, Object>> list = myStores(uid);
        if (list.isEmpty())
        {
            throw new ServiceException("当前账号没有该门店的经营数据权限", 403);
        }
        for (Map<String, Object> s : list)
        {
            if (storeId == null || storeId.equals(KgJdbc.asLong(s.get("storeId"))))
            {
                return s;
            }
        }
        throw new ServiceException("没有该门店的经营权限", 403);
    }

    static List<Long> ids(List<Map<String, Object>> stores)
    {
        List<Long> out = new ArrayList<Long>();
        for (Map<String, Object> s : stores)
        {
            out.add(KgJdbc.asLong(s.get("storeId")));
        }
        return out;
    }

    static String md(LocalDate d)
    {
        return d.getMonthValue() + "." + d.getDayOfMonth();
    }

    static LocalDate monday(LocalDate d)
    {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static String weekLabel(LocalDate mon)
    {
        return mon.getYear() + "年 第" + mon.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + "周（" + md(mon) + "-" + md(mon.plusDays(6)) + "）";
    }

    static Map<String, Object> cell(String label, String value, String delta, Boolean up)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", value);
        m.put("delta", delta);
        m.put("up", up);
        return m;
    }

    // ---------------------------------------------------------------- 周报

    static final Map<String, String> WEEKLY_STATUS = new LinkedHashMap<String, String>();
    static
    {
        WEEKLY_STATUS.put("auto", "待确认");
        WEEKLY_STATUS.put("draft", "草稿");
        WEEKLY_STATUS.put("returned", "已退回");
        WEEKLY_STATUS.put("submitted", "已提交");
    }

    /** 周报系统汇总（s2 / s4 展示；提交时冻结进 snapshot） */
    public Map<String, Object> weeklySnapshot(Long storeId, LocalDate mon)
    {
        List<Long> s = Collections.singletonList(storeId);
        LocalDate to = mon.plusDays(7);
        LocalDate lastMon = mon.minusDays(7);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("range", md(mon) + "-" + md(mon.plusDays(6)));
        Map<String, Long> w = stats.works(storeId, mon, to);
        List<Map<String, Object>> works = new ArrayList<Map<String, Object>>();
        works.add(work("house", "房源管理", w.get("rooms") + " 项"));
        works.add(work("briefcase-business", "岗位管理", w.get("jobs") + " 项"));
        works.add(work("flower", "活动管理", w.get("activities") + " 场"));
        works.add(work("file-badge", "内容发布", w.get("content") + " 条"));
        m.put("works", works);

        Map<String, Long> rev = stats.revenue(s, mon, to);
        Map<String, Long> revLast = stats.revenue(s, lastMon, mon);
        Integer occ = stats.occupancy(s, mon, to);
        Integer occLast = stats.occupancy(s, lastMon, mon);
        Map<String, Long> good = stats.goodRate(s, mon, to);
        Map<String, Long> goodLast = stats.goodRate(s, lastMon, mon);
        Map<String, Long> apps = stats.applications(s, mon, to);
        Map<String, Long> appsLast = stats.applications(s, lastMon, mon);
        List<Map<String, Object>> biz = new ArrayList<Map<String, Object>>();
        biz.add(cell("总营收(元)", yuanText(rev.get("total")), delta(rev.get("total"), revLast.get("total")), rev.get("total") >= revLast.get("total")));
        biz.add(cell("订单(笔)", String.valueOf(rev.get("orders")), delta(rev.get("orders"), revLast.get("orders")), rev.get("orders") >= revLast.get("orders")));
        if (occ != null)
        {
            biz.add(cell("入住率", pct(occ), deltaBp(occ.longValue(), occLast == null ? null : occLast.longValue()), occLast == null || occ >= occLast));
        }
        else
        {
            biz.add(cell("报名(人)", String.valueOf(apps.get("n")), delta(apps.get("n"), appsLast.get("n")), apps.get("n") >= appsLast.get("n")));
        }
        biz.add(cell("好评率", pct(good.get("bp")), deltaBp(good.get("bp"), goodLast.get("bp")), good.get("bp") == null || goodLast.get("bp") == null
                || good.get("bp") >= goodLast.get("bp")));
        m.put("bizCols", biz);

        List<String> days = new ArrayList<String>();
        for (int i = 0; i < 7; i++)
        {
            days.add(md(mon.plusDays(i)));
        }
        m.put("days", days);
        m.put("thisWeek", KgStats.list(stats.dailyRevenue(s, mon, to)));
        m.put("lastWeek", KgStats.list(stats.dailyRevenue(s, lastMon, mon)));

        long paid = rev.get("total");
        long orders = rev.get("orders");
        long paidLast = revLast.get("total");
        long ordersLast = revLast.get("orders");
        List<Map<String, Object>> oc = new ArrayList<Map<String, Object>>();
        oc.add(cell("报名人数", String.valueOf(apps.get("n")), delta(apps.get("n"), appsLast.get("n")), apps.get("n") >= appsLast.get("n")));
        oc.add(cell("订单数", String.valueOf(orders), delta(orders, ordersLast), orders >= ordersLast));
        oc.add(cell("支付金额(元)", yuanText(paid), delta(paid, paidLast), paid >= paidLast));
        long avg = orders == 0 ? 0 : paid / orders;
        long avgLast = ordersLast == 0 ? 0 : paidLast / ordersLast;
        oc.add(cell("客单价(元)", orders == 0 ? "—" : yuanText(avg), orders == 0 || ordersLast == 0 ? "—" : delta(avg, avgLast), avg >= avgLast));
        m.put("orderCols", oc);
        m.put("hasRooms", occ != null);
        m.put("note", "营收按支付成功日计入、按退款日扣减；入住率 = 占用间夜 ÷（上架房型库存 × 天数）；好评率 = 4 星及以上评价占比。");
        return m;
    }

    static Map<String, Object> work(String icon, String label, String value)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("icon", icon);
        m.put("label", label);
        m.put("value", value);
        return m;
    }

    /** 周报列表：按周 = 选中那周我的每家门店一张卡；按门店 = 选中门店最近 6 周 */
    public Map<String, Object> weeklyList(Long storeId, String weekStart, String mode)
    {
        AppLoginUser me = me();
        List<Map<String, Object>> stores = myStores(me.getUserId());
        if (stores.isEmpty())
        {
            throw new ServiceException("仅门店主理人或管家可以查看周报", 403);
        }
        LocalDate thisMon = monday(LocalDate.now());
        LocalDate mon = weekStart == null || weekStart.isEmpty() ? thisMon : monday(LocalDate.parse(weekStart));
        if (mon.isAfter(thisMon))
        {
            mon = thisMon;
        }
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> anomalies = new ArrayList<Map<String, Object>>();
        boolean shopMode = false;
        if ("store".equals(mode))
        {
            Map<String, Object> st = store(me.getUserId(), storeId);
            if (isManager(st))
            {
                // 换工主理人按门店看 = 本城每家换工店家这一周一张卡（不是某家店最近 6 周）
                shopMode = true;
                cards.addAll(recruitShopCards(recruitStores(stores, st).get(0), mon, stores));
            }
            else
            {
                for (int i = 0; i < 6; i++)
                {
                    cards.add(card(st, thisMon.minusWeeks(i)));
                }
            }
        }
        else
        {
            // 换工主理人同城多家授权门店（商家店会自动授权给他）只出一张卡，否则同样的全城数据要写好多份周报
            Set<Long> seen = new HashSet<Long>();
            for (Map<String, Object> st : stores)
            {
                if (storeId != null && !storeId.equals(KgJdbc.asLong(st.get("storeId"))))
                {
                    continue;
                }
                if (isManager(st))
                {
                    Map<String, Object> anchor = recruitStores(stores, st).get(0);
                    if (seen.add(KgJdbc.asLong(anchor.get("storeId"))))
                    {
                        cards.add(recruitCard(anchor, mon, stores));
                    }
                    continue;
                }
                Map<String, Object> c = card(st, mon);
                cards.add(c);
                anomalies.addAll(anomaly(st, mon));
            }
        }
        List<Map<String, Object>> weeks = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 6; i++)
        {
            Map<String, Object> w = new LinkedHashMap<String, Object>();
            w.put("start", thisMon.minusWeeks(i).toString());
            w.put("label", weekLabel(thisMon.minusWeeks(i)));
            weeks.add(w);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("weekStart", mon.toString());
        m.put("weekLabel", weekLabel(mon));
        m.put("weeks", weeks);
        List<Map<String, Object>> listStores = listStores(stores);
        m.put("stores", listStores);
        m.put("cards", cards);
        m.put("anomalies", anomalies);
        m.put("title", listTitle(cards, listStores));
        m.put("shopMode", shopMode);
        return m;
    }

    /** 门店筛选项：换工主理人同城只留一项（名字是「城市换工招募」），其余门店原样 */
    private List<Map<String, Object>> listStores(List<Map<String, Object>> stores)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Set<Long> seen = new HashSet<Long>();
        for (Map<String, Object> st : stores)
        {
            if (!isManager(st))
            {
                Map<String, Object> x = new LinkedHashMap<String, Object>(st);
                x.put("reportType", TYPE_OPS);
                out.add(x);
                continue;
            }
            Map<String, Object> anchor = recruitStores(stores, st).get(0);
            if (seen.add(KgJdbc.asLong(anchor.get("storeId"))))
            {
                Map<String, Object> x = new LinkedHashMap<String, Object>(anchor);
                x.put("storeName", recruitName(anchor));
                x.put("reportType", TYPE_RECRUIT);
                out.add(x);
            }
        }
        return out;
    }

    private static String listTitle(List<Map<String, Object>> cards, List<Map<String, Object>> stores)
    {
        Set<String> types = new LinkedHashSet<String>();
        for (Map<String, Object> c : cards.isEmpty() ? stores : cards)
        {
            types.add(str(c.get("reportType")));
        }
        return types.size() == 1 ? titleOf(types.iterator().next()) : "周报";
    }

    // ---------------------------------------------------------------- 换工招募情况周报（换工主理人）

    static final String TYPE_OPS = "ops";
    static final String TYPE_RECRUIT = "recruit";
    static final String TITLE_OPS = "代运营周报";
    static final String TITLE_RECRUIT = "换工招募情况周报";

    static String titleOf(String type)
    {
        return TYPE_RECRUIT.equals(type) ? TITLE_RECRUIT : TITLE_OPS;
    }

    static boolean isManager(Map<String, Object> st)
    {
        return StaffRole.MANAGER.equals(str(st.get("roleCode")));
    }

    static String typeOf(Map<String, Object> report)
    {
        return TYPE_RECRUIT.equals(str(report.get("reportType"))) ? TYPE_RECRUIT : TYPE_OPS;
    }

    /** 门店城市归一；空城市、全国、不限都算「没有城市」（和 KgMerchantRequestService 同一口径） */
    private String town(Object raw)
    {
        String t = cities.jobCity(str(raw).trim());
        t = t == null ? "" : t.trim();
        return t.isEmpty() || "全国".equals(t) || "不限".equals(t) ? "" : t;
    }

    /**
     * 这个账号在 st 同城有 manager 授权的门店（按授权先后）。第一家就是他这座城周报挂靠的门店。
     * 门店没设城市时不能和别的「没城市」的门店混到一起，只算它自己。
     */
    private List<Map<String, Object>> recruitStores(List<Map<String, Object>> stores, Map<String, Object> st)
    {
        String t = town(st.get("city"));
        Long id = KgJdbc.asLong(st.get("storeId"));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> s : stores)
        {
            if (isManager(s) && (t.isEmpty() ? id.equals(KgJdbc.asLong(s.get("storeId"))) : t.equals(town(s.get("city")))))
            {
                out.add(s);
            }
        }
        return out;
    }

    private String recruitName(Map<String, Object> anchor)
    {
        String t = town(anchor.get("city"));
        return t.isEmpty() ? str(anchor.get("storeName")) : t + "换工招募";
    }

    private static List<Long> managerIds(List<Map<String, Object>> stores)
    {
        List<Long> out = new ArrayList<Long>();
        for (Map<String, Object> s : stores)
        {
            if (isManager(s))
            {
                out.add(KgJdbc.asLong(s.get("storeId")));
            }
        }
        return out;
    }

    /**
     * 招募周报的系统汇总。人数是全城换工店家的（coalesce(recruit_store_id, store_id)，和当地换工招募概况同一个算法）；
     * 钱只算这个账号有换工主理人授权的同城门店（招募账），没有权限的店不露钱。
     * 老版本小程序读 works / bizCols / orderCols / thisWeek / lastWeek，这几个键一个都不能少。
     */
    Map<String, Object> recruitSnapshot(Map<String, Object> anchor, LocalDate mon, List<Map<String, Object>> stores)
    {
        String t = town(anchor.get("city"));
        LocalDate to = mon.plusDays(7);
        LocalDate lastMon = mon.minusDays(7);
        List<Map<String, Object>> mine = recruitStores(stores, anchor);
        List<Long> mineIds = ids(mine);
        Map<Long, long[]> money = new LinkedHashMap<Long, long[]>();
        long[] thisWeek = new long[7];
        long[] lastWeek = new long[7];
        if (!mineIds.isEmpty())
        {
            for (Map<String, Object> e : db.list("select store_id, biz_date, direction, sum(amount_fen) v from kg_daily_entry where store_id in (:s) and ledger = 'recruit' and status = '0' "
                    + "and biz_date >= :f and biz_date < :t group by store_id, biz_date, direction", p("s", mineIds, "f", lastMon.toString(), "t", to.toString())))
            {
                LocalDate day = LocalDate.parse(str(e.get("bizDate")).substring(0, 10));
                boolean current = !day.isBefore(mon);
                boolean income = "income".equals(str(e.get("direction")));
                long[] x = money.computeIfAbsent(KgJdbc.asLong(e.get("storeId")), k -> new long[4]);
                x[(current ? 0 : 2) + (income ? 0 : 1)] += num(e.get("v"));
                if (income)
                {
                    int i = (int) ChronoUnit.DAYS.between(current ? mon : lastMon, day);
                    (current ? thisWeek : lastWeek)[i] += num(e.get("v"));
                }
            }
        }
        List<Map<String, Object>> shops = t.isEmpty() ? new ArrayList<Map<String, Object>>() : volunteers.weekShops(t, mon);
        Set<Long> placed = new HashSet<Long>();
        for (Map<String, Object> shop : shops)
        {
            Long id = KgJdbc.asLong(shop.get("storeId"));
            placed.add(id);
            if (mineIds.contains(id))
            {
                putMoney(shop, money.get(id));
            }
            else
            {
                shop.put("canSeeMoney", false);
            }
        }
        // 有账但本城没发过换工岗位的门店也要有一行，否则各店加起来对不上总数
        for (Map<String, Object> s : mine)
        {
            Long id = KgJdbc.asLong(s.get("storeId"));
            if (!placed.contains(id) && money.containsKey(id))
            {
                Map<String, Object> shop = new LinkedHashMap<String, Object>();
                shop.put("storeId", id);
                shop.put("name", s.get("storeName"));
                shop.put("cover", s.get("cover"));
                putMoney(shop, money.get(id));
                shops.add(shop);
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("reportType", TYPE_RECRUIT);
        m.put("title", TITLE_RECRUIT);
        m.put("city", t);
        m.put("range", md(mon) + "-" + md(mon.plusDays(6)));
        m.put("shops", shops);
        List<String> days = new ArrayList<String>();
        for (int i = 0; i < 7; i++)
        {
            days.add(md(mon.plusDays(i)));
        }
        m.put("days", days);
        m.put("thisWeek", KgStats.list(thisWeek));
        m.put("lastWeek", KgStats.list(lastWeek));
        m.put("hasRooms", false);
        m.put("note", "招募换工按商家确认录用的时间统计，到岗按入职时间统计；缺口是招聘中岗位的剩余名额；一周内离开是合约在这一周结束的人数。本周收入、支出来自换工招募日报的手工明细，只算你有换工主理人授权的门店，没有权限的门店不显示金额。");
        recruitRollup(m);
        return m;
    }

    private static void putMoney(Map<String, Object> shop, long[] x)
    {
        long[] v = x == null ? new long[4] : x;
        shop.put("canSeeMoney", true);
        shop.put("incomeFen", v[0]);
        shop.put("expenseFen", v[1]);
        shop.put("lastIncomeFen", v[2]);
        shop.put("lastExpenseFen", v[3]);
        shop.put("income", yuanText(v[0]));
        shop.put("expense", yuanText(v[1]));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shopsOf(Map<String, Object> snap)
    {
        Object v = snap.get("shops");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<Map<String, Object>>();
    }

    /** 由各店的行加总出全周数字和老版本要读的几组格子。第一次生成和按查看人遮挡金额后都走这里，两处算法不会不一致 */
    private static void recruitRollup(Map<String, Object> m)
    {
        long recruited = 0, onboarded = 0, recruitedLast = 0, onboardedLast = 0, applied = 0, appliedLast = 0, shortfall = 0, leaving = 0;
        long income = 0, expense = 0, incomeLast = 0, expenseLast = 0;
        for (Map<String, Object> s : shopsOf(m))
        {
            recruited += num(s.get("recruited"));
            onboarded += num(s.get("onboarded"));
            recruitedLast += num(s.get("recruitedLast"));
            onboardedLast += num(s.get("onboardedLast"));
            applied += num(s.get("applied"));
            appliedLast += num(s.get("appliedLast"));
            shortfall += num(s.get("shortfall"));
            leaving += num(s.get("leaving"));
            income += num(s.get("incomeFen"));
            expense += num(s.get("expenseFen"));
            incomeLast += num(s.get("lastIncomeFen"));
            expenseLast += num(s.get("lastExpenseFen"));
        }
        List<Map<String, Object>> works = new ArrayList<Map<String, Object>>();
        works.add(work("user-plus", "招募换工", recruited + " 人"));
        works.add(work("user-check", "到岗", onboarded + " 人"));
        works.add(work("users", "招募缺口", shortfall + " 个"));
        works.add(work("log-out", "一周内离开", leaving + " 人"));
        m.put("works", works);
        List<Map<String, Object>> biz = new ArrayList<Map<String, Object>>();
        biz.add(cell("招募换工(人)", String.valueOf(recruited), delta(recruited, recruitedLast), recruited >= recruitedLast));
        biz.add(cell("到岗(人)", String.valueOf(onboarded), delta(onboarded, onboardedLast), onboarded >= onboardedLast));
        biz.add(cell("本周收入(元)", yuanText(income), delta(income, incomeLast), income >= incomeLast));
        biz.add(cell("本周支出(元)", yuanText(expense), delta(expense, expenseLast), expense >= expenseLast));
        m.put("bizCols", biz);
        long net = income - expense;
        long netLast = incomeLast - expenseLast;
        List<Map<String, Object>> oc = new ArrayList<Map<String, Object>>();
        oc.add(cell("报名人数", String.valueOf(applied), delta(applied, appliedLast), applied >= appliedLast));
        oc.add(cell("招募换工", String.valueOf(recruited), delta(recruited, recruitedLast), recruited >= recruitedLast));
        oc.add(cell("收支结余(元)", yuanText(net), delta(net, netLast), net >= netLast));
        oc.add(cell("到岗人数", String.valueOf(onboarded), delta(onboarded, onboardedLast), onboarded >= onboardedLast));
        m.put("orderCols", oc);
        Map<String, Object> totals = new LinkedHashMap<String, Object>();
        totals.put("recruited", recruited);
        totals.put("onboarded", onboarded);
        totals.put("shortfall", shortfall);
        totals.put("leaving", leaving);
        totals.put("income", yuanText(income));
        totals.put("expense", yuanText(expense));
        m.put("totals", totals);
    }

    /**
     * 已提交的招募周报冻结的是提交人当时看到的金额。同一家挂靠门店的另一位换工主理人打开时，
     * 他没有授权的门店不能露钱：去掉那几家的金额并重算总数；按日收入分不出是哪家店的，有遮挡时就不给趋势。
     */
    static Map<String, Object> maskRecruit(Map<String, Object> snap, List<Long> visible)
    {
        boolean hidden = false;
        for (Map<String, Object> s : shopsOf(snap))
        {
            if (Boolean.TRUE.equals(s.get("canSeeMoney")) && !visible.contains(KgJdbc.asLong(s.get("storeId"))))
            {
                s.put("canSeeMoney", false);
                for (String k : Arrays.asList("incomeFen", "expenseFen", "lastIncomeFen", "lastExpenseFen", "income", "expense"))
                {
                    s.remove(k);
                }
                hidden = true;
            }
        }
        if (hidden)
        {
            snap.put("thisWeek", new ArrayList<Long>());
            snap.put("lastWeek", new ArrayList<Long>());
            recruitRollup(snap);
        }
        return snap;
    }

    private Map<String, Object> recruitView(Map<String, Object> r, Map<String, Object> anchor, LocalDate mon, List<Map<String, Object>> stores)
    {
        if (r != null && "submitted".equals(str(r.get("status"))) && r.get("snapshot") != null)
        {
            return maskRecruit(JSON.parseObject(str(r.get("snapshot"))), managerIds(stores));
        }
        return recruitSnapshot(anchor, mon, stores);
    }

    private Map<String, Object> recruitCard(Map<String, Object> anchor, LocalDate mon, List<Map<String, Object>> stores)
    {
        Long sid = KgJdbc.asLong(anchor.get("storeId"));
        Map<String, Object> r = reportRow(sid, mon, TYPE_RECRUIT);
        return cardOf(r, recruitView(r, anchor, mon, stores), sid, recruitName(anchor), anchor.get("cover"), mon, TYPE_RECRUIT);
    }

    /**
     * 按门店：本城每家换工店家一张卡。形状和普通卡片一样（老版本能显示）：storeId 是这家店（列表要靠它区分每一行），
     * reportId 是他这一周的那张周报，点开进同一张周报；还没建时老版本会拿 storeId 查汇总，见 {@link #weeklySummary}。
     */
    private List<Map<String, Object>> recruitShopCards(Map<String, Object> anchor, LocalDate mon, List<Map<String, Object>> stores)
    {
        Map<String, Object> r = reportRow(KgJdbc.asLong(anchor.get("storeId")), mon, TYPE_RECRUIT);
        Map<String, Object> snap = recruitView(r, anchor, mon, stores);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> s : shopsOf(snap))
        {
            Map<String, Object> c = cardOf(r, snap, KgJdbc.asLong(s.get("storeId")), s.get("name"), s.get("cover"), mon, TYPE_RECRUIT);
            List<Map<String, Object>> cols = new ArrayList<Map<String, Object>>();
            cols.add(cell("招募(人)", String.valueOf(num(s.get("recruited"))), null, null));
            cols.add(cell("到岗(人)", String.valueOf(num(s.get("onboarded"))), null, null));
            cols.add(cell("缺口", String.valueOf(num(s.get("shortfall"))), null, null));
            cols.add(cell("一周内离开", String.valueOf(num(s.get("leaving"))), null, null));
            c.put("cols", cols);
            c.put("shop", true);
            if (Boolean.TRUE.equals(s.get("canSeeMoney")))
            {
                Map<String, Object> money = new LinkedHashMap<String, Object>();
                money.put("income", s.get("income"));
                money.put("expense", s.get("expense"));
                c.put("money", money);
            }
            else
            {
                c.put("money", null);
            }
            out.add(c);
        }
        return out;
    }

    /** GET /weekly/summary：还没建周报时的系统汇总。换工主理人从按门店的卡片点进来时 storeId 可能是他没授权的换工店家，看的是他这座城的那张周报 */
    public Map<String, Object> weeklySummary(Long storeId, String weekStart)
    {
        AppLoginUser me = me();
        List<Map<String, Object>> stores = myStores(me.getUserId());
        Map<String, Object> st = null;
        for (Map<String, Object> x : stores)
        {
            if (storeId == null || storeId.equals(KgJdbc.asLong(x.get("storeId"))))
            {
                st = x;
                break;
            }
        }
        if (st == null && storeId != null)
        {
            Map<String, Object> shop = db.one("select city from kg_store where store_id = :s and status = '0' and del_flag = '0'", p("s", storeId));
            String t = shop == null ? "" : town(shop.get("city"));
            for (Map<String, Object> x : stores)
            {
                if (!t.isEmpty() && isManager(x) && t.equals(town(x.get("city"))))
                {
                    st = x;
                    break;
                }
            }
        }
        if (st == null)
        {
            throw new ServiceException("没有该门店的经营权限", 403);
        }
        LocalDate mon = monday(weekStart == null || weekStart.isEmpty() ? LocalDate.now() : LocalDate.parse(weekStart));
        Map<String, Object> snap;
        if (isManager(st))
        {
            Map<String, Object> anchor = recruitStores(stores, st).get(0);
            snap = recruitSnapshot(anchor, mon, stores);
            snap.put("storeId", anchor.get("storeId"));
            snap.put("storeName", recruitName(anchor));
        }
        else
        {
            snap = weeklySnapshot(KgJdbc.asLong(st.get("storeId")), mon);
            snap.put("reportType", TYPE_OPS);
            snap.put("title", TITLE_OPS);
            snap.put("storeId", st.get("storeId"));
            snap.put("storeName", st.get("storeName"));
        }
        snap.put("weekStart", mon.toString());
        return snap;
    }

    /** 周报所在门店；招募周报里有换工招募的账，只有这家店的换工主理人能打开（小院主理人、员工不行） */
    private Map<String, Object> reportStore(AppLoginUser me, Map<String, Object> r)
    {
        // 城市负责人从消息里点开招募周报时没带请求头：这张是招募周报，这次请求就按招募账算
        if (TYPE_RECRUIT.equals(typeOf(r)) && cityLeads != null && cityLeads.covers(me.getUserId(), KgJdbc.asLong(r.get("storeId")))
                && org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null)
        {
            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes().setAttribute(REPORT_AS_ATTR, Boolean.TRUE, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        }
        Map<String, Object> st = store(me.getUserId(), KgJdbc.asLong(r.get("storeId")));
        if (TYPE_RECRUIT.equals(typeOf(r)) && !isManager(st))
        {
            throw new ServiceException("换工招募情况周报只有换工主理人可以查看", 403);
        }
        return st;
    }

    private Map<String, Object> card(Map<String, Object> st, LocalDate mon)
    {
        Long sid = KgJdbc.asLong(st.get("storeId"));
        Map<String, Object> r = reportRow(sid, mon, TYPE_OPS);
        Map<String, Object> snap = r != null && "submitted".equals(str(r.get("status"))) && r.get("snapshot") != null
                ? JSON.parseObject(str(r.get("snapshot"))) : weeklySnapshot(sid, mon);
        return cardOf(r, snap, sid, st.get("storeName"), st.get("cover"), mon, TYPE_OPS);
    }

    private Map<String, Object> reportRow(Long storeId, LocalDate mon, String type)
    {
        return db.one("select report_id, status, update_time, submitted_time, snapshot from kg_weekly_report where store_id = :s and week_start = :w and report_type = :rt",
                p("s", storeId, "w", mon.toString(), "rt", type));
    }

    private static Map<String, Object> cardOf(Map<String, Object> r, Map<String, Object> snap, Long sid, Object name, Object cover, LocalDate mon, String type)
    {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("reportId", r == null ? null : r.get("reportId"));
        c.put("reportType", type);
        c.put("storeId", sid);
        c.put("name", name);
        c.put("cover", cover);
        c.put("weekStart", mon.toString());
        c.put("weekText", "第" + mon.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + "周　" + md(mon) + "-" + md(mon.plusDays(6)));
        String status = r == null ? "auto" : str(r.get("status"));
        c.put("status", status);
        c.put("statusText", WEEKLY_STATUS.get(status));
        c.put("tone", "submitted".equals(status) ? "done" : ("auto".equals(status) ? "wait" : "draft"));
        Object t = r == null ? null : (r.get("submittedTime") != null ? r.get("submittedTime") : r.get("updateTime"));
        c.put("time", t == null ? "系统汇总 " : StaffSupport.fmt(StaffSupport.parse(t), "yyyy-MM-dd HH:mm"));
        List<Map<String, Object>> cols = new ArrayList<Map<String, Object>>();
        for (Object o : (List<?>) snap.get("bizCols"))
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> x = (Map<String, Object>) o;
            cols.add(cell(str(x.get("label")), str(x.get("value")), null, null));
        }
        c.put("cols", cols);
        return c;
    }

    /** 本周 vs 上周：营收降 ≥ 20%、入住率或好评率降 ≥ 5 个百分点 */
    private List<Map<String, Object>> anomaly(Map<String, Object> st, LocalDate mon)
    {
        Long sid = KgJdbc.asLong(st.get("storeId"));
        List<Long> s = Collections.singletonList(sid);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        long cur = stats.revenue(s, mon, mon.plusDays(7)).get("total");
        long last = stats.revenue(s, mon.minusDays(7), mon).get("total");
        if (last > 0 && cur < last * 0.8)
        {
            out.add(alert(st, "营收较上周下降 " + Math.round((last - cur) * 100.0 / last) + "%"));
        }
        Integer o1 = stats.occupancy(s, mon, mon.plusDays(7));
        Integer o0 = stats.occupancy(s, mon.minusDays(7), mon);
        if (o1 != null && o0 != null && o0 - o1 >= 500)
        {
            out.add(alert(st, "入住率较上周下降 " + pct(o0 - o1)));
        }
        Long g1 = stats.goodRate(s, mon, mon.plusDays(7)).get("bp");
        Long g0 = stats.goodRate(s, mon.minusDays(7), mon).get("bp");
        if (g1 != null && g0 != null && g0 - g1 >= 500)
        {
            out.add(alert(st, "好评率较上周下降 " + pct(g0 - g1)));
        }
        return out;
    }

    private static Map<String, Object> alert(Map<String, Object> st, String desc)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("storeId", st.get("storeId"));
        m.put("name", st.get("storeName"));
        m.put("desc", desc);
        return m;
    }

    /**
     * 打开（没有就建）某店某周的周报草稿。
     * 故意不开事务：只有一条 insert，其余是读；开了事务后并发打开同一周时，后到的请求撞唯一键后
     * 读的是本事务开头的快照，看不到先到者刚建的那张周报（2026-09-26 回归发现返回「周报不存在」）。
     */
    public Map<String, Object> weeklyOpen(Long storeId, String weekStart)
    {
        AppLoginUser me = me();
        Map<String, Object> st = store(me.getUserId(), storeId);
        // 换工主理人写的是招募周报，挂在他这座城最早那家授权门店上（小院主理人同一周的代运营周报是另一行，互不影响）
        String type = isManager(st) ? TYPE_RECRUIT : TYPE_OPS;
        Long sid = KgJdbc.asLong((isManager(st) ? recruitStores(myStores(me.getUserId()), st).get(0) : st).get("storeId"));
        LocalDate thisMon = monday(LocalDate.now());
        LocalDate mon = weekStart == null || weekStart.isEmpty() ? thisMon : monday(LocalDate.parse(weekStart));
        if (mon.isAfter(thisMon))
        {
            throw new ServiceException("还没到这一周，不能写周报");
        }
        return weeklyDetail(ensureWeeklyDraft(me.getUserId(), sid, type, mon));
    }

    /** Admin reminders use the same effective grant order and recruitment anchor as weeklyOpen.
     * No app identity is impersonated and no city-lead-only grant becomes an obligation to write. */
    public List<Map<String,Object>> weeklyReminderTargets(long uid, List<Long> cityStores)
    {
        List<Map<String,Object>> stores = myStores(uid), out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for(Map<String,Object> st:stores) {
            long sid=KgJdbc.asLong(st.get("storeId"));
            if(!seen.add(sid) || !cityStores.contains(sid)) continue;
            String role=str(st.get("roleCode"));
            if(!Arrays.asList("keeper","manager").contains(role)) continue;
            Map<String,Object> anchor=isManager(st)?recruitStores(stores,st).get(0):st;
            long target=KgJdbc.asLong(anchor.get("storeId"));
            if(!cityStores.contains(target)) continue;
            if(targets.add(role+":"+target)) out.add(p("storeId",target,"roleCode",role,"reportType",isManager(st)?TYPE_RECRUIT:TYPE_OPS,"targetName",isManager(st)?recruitName(st):st.get("storeName")));
        }
        return out;
    }

    public long openWeeklyReminder(long uid,long sid,String role,List<Long> cityStores,String week)
    {
        for(Map<String,Object> target:weeklyReminderTargets(uid,cityStores)) {
            if(KgJdbc.asLong(target.get("storeId"))==sid && role.equals(target.get("roleCode")))
                return ensureWeeklyDraft(uid,sid,str(target.get("reportType")),monday(LocalDate.parse(week)));
        }
        throw new ServiceException("周报填报权限已变更，请重新预览",403);
    }

    private long ensureWeeklyDraft(long uid,long sid,String type,LocalDate mon)
    {
        Map<String, Object> r = db.one("select report_id from kg_weekly_report where store_id = :s and week_start = :w and report_type = :rt",
                p("s", sid, "w", mon.toString(), "rt", type));
        if (r == null)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("store_id", sid);
            row.put("week_start", mon.toString());
            row.put("report_type", type);
            row.put("status", "draft");
            row.put("create_by", "app:" + uid);
            row.put("create_time", new Date());
            row.put("update_time", new Date());
            try
            {
                db.insert("kg_weekly_report", row);
            }
            catch (org.springframework.dao.DuplicateKeyException e)
            {
                // 并发打开同一周：以先建的为准
            }
            // 加锁读：并发打开同一周时另一个请求刚提交的那行，本事务的快照里看不到（2026-09-26 回归发现 3 个并发里 2 个 NPE）
            r = db.one("select report_id from kg_weekly_report where store_id = :s and week_start = :w and report_type = :rt for share",
                    p("s", sid, "w", mon.toString(), "rt", type));
        }
        return KgJdbc.asLong(r.get("reportId"));
    }

    public Map<String, Object> weeklyDetail(Long reportId)
    {
        AppLoginUser me = me();
        Map<String, Object> r = db.must("select * from kg_weekly_report where report_id = :id", p("id", reportId), "周报不存在");
        Map<String, Object> st = reportStore(me, r);
        LocalDate mon = LocalDate.parse(str(r.get("weekStart")).substring(0, 10));
        boolean submitted = "submitted".equals(str(r.get("status")));
        String type = typeOf(r);
        boolean recruit = TYPE_RECRUIT.equals(type);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("reportId", reportId);
        m.put("reportType", type);
        m.put("title", titleOf(type));
        m.put("storeId", st.get("storeId"));
        m.put("storeName", recruit ? recruitName(st) : st.get("storeName"));
        m.put("weekStart", mon.toString());
        m.put("weekText", "第" + mon.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + "周（" + md(mon) + "-" + md(mon.plusDays(6)) + "）");
        m.put("status", r.get("status"));
        m.put("statusText", WEEKLY_STATUS.get(str(r.get("status"))));
        m.put("version", r.get("version"));
        m.put("note", str(r.get("note")));
        m.put("risk", str(r.get("risk")));
        m.put("returnReason", r.get("returnReason"));
        m.put("proofs", str(r.get("proofs")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(r.get("proofs")).split(",")));
        m.put("updated", r.get("updateTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(r.get("updateTime")), "HH:mm"));
        m.put("submittedBy", r.get("submittedName"));
        m.put("submittedTime", r.get("submittedTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(r.get("submittedTime")), "yyyy-MM-dd HH:mm"));
        m.put("snapshot", recruit ? recruitView(r, st, mon, myStores(me.getUserId()))
                : submitted && r.get("snapshot") != null ? JSON.parseObject(str(r.get("snapshot"))) : weeklySnapshot(KgJdbc.asLong(st.get("storeId")), mon));
        List<Map<String, Object>> fees = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> coops = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> it : db.list("select * from kg_weekly_item where report_id = :id order by sort, item_id", p("id", reportId)))
        {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            if ("fee".equals(str(it.get("itemType"))))
            {
                x.put("name", it.get("name"));
                x.put("amountFen", num(it.get("amountFen")));
                x.put("remark", it.get("remark"));
                fees.add(x);
            }
            else
            {
                x.put("text", it.get("name"));
                x.put("dept", it.get("dept"));
                x.put("done", "1".equals(str(it.get("doneFlag"))));
                coops.add(x);
            }
        }
        m.put("fees", fees);
        m.put("coops", coops);
        return m;
    }

    /** 保存草稿（整单覆盖，带版本号防止两台手机互相覆盖） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> weeklySave(Long reportId, Map<String, Object> b)
    {
        AppLoginUser me = me();
        Map<String, Object> r = db.must("select * from kg_weekly_report where report_id = :id", p("id", reportId), "周报不存在");
        reportStore(me, r);
        if ("submitted".equals(str(r.get("status"))))
        {
            throw new ServiceException("周报已提交，不能再修改", 409);
        }
        long ver = num(b.get("version"));
        String note = str(b.get("note")).trim();
        String risk = str(b.get("risk")).trim();
        if (note.length() > 300 || risk.length() > 200)
        {
            throw new ServiceException("补充说明不超过 300 字，风险与问题不超过 200 字", 422);
        }
        List<String> proofs = new ArrayList<String>();
        if (b.get("proofs") instanceof List)
        {
            for (Object o : (List<?>) b.get("proofs"))
            {
                if (!str(o).isEmpty())
                {
                    proofs.add(str(o));
                }
            }
        }
        if (proofs.size() > 9)
        {
            throw new ServiceException("凭证图片最多 9 张", 422);
        }
        int n = db.update("update kg_weekly_report set note = :n, risk = :r, proofs = :p, version = version + 1, status = case when status = 'returned' then 'returned' else 'draft' end, "
                + "update_by = :by, update_time = now() where report_id = :id and version = :v and status <> 'submitted'",
                p("n", note, "r", risk, "p", String.join(",", proofs), "by", "app:" + me.getUserId(), "id", reportId, "v", ver));
        if (n == 0)
        {
            throw new ServiceException("周报已在别处修改，请刷新后再保存", 409);
        }
        db.update("delete from kg_weekly_item where report_id = :id", p("id", reportId));
        int sort = 0;
        if (b.get("fees") instanceof List)
        {
            for (Object o : (List<?>) b.get("fees"))
            {
                Map<?, ?> f = (Map<?, ?>) o;
                long fen = num(f.get("amountFen"));
                if (fen <= 0 || fen > 100000000L)
                {
                    throw new ServiceException("费用金额需在 0.01–1,000,000 元之间", 422);
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("report_id", reportId);
                row.put("item_type", "fee");
                row.put("name", StaffSupport.cut(str(f.get("name")), 64));
                row.put("amount_fen", fen);
                row.put("remark", StaffSupport.cut(str(f.get("remark")), 120));
                row.put("sort", sort++);
                db.insert("kg_weekly_item", row);
            }
        }
        if (b.get("coops") instanceof List)
        {
            for (Object o : (List<?>) b.get("coops"))
            {
                Map<?, ?> c = (Map<?, ?>) o;
                if (str(c.get("text")).trim().isEmpty())
                {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("report_id", reportId);
                row.put("item_type", "coop");
                row.put("name", StaffSupport.cut(str(c.get("text")).trim(), 64));
                row.put("dept", StaffSupport.cut(str(c.get("dept")), 32));
                row.put("done_flag", Boolean.TRUE.equals(c.get("done")) || "true".equals(str(c.get("done"))) ? "1" : "0");
                row.put("sort", sort++);
                db.insert("kg_weekly_item", row);
            }
        }
        return weeklyDetail(reportId);
    }

    /** 提交：冻结系统汇总，之后不可修改；通知本店其他主理人 / 管家 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> weeklySubmit(Long reportId, Map<String, Object> b)
    {
        AppLoginUser me = me();
        Map<String, Object> r = db.must("select * from kg_weekly_report where report_id = :id", p("id", reportId), "周报不存在");
        Map<String, Object> st = reportStore(me, r);
        boolean recruit = TYPE_RECRUIT.equals(typeOf(r));
        if (!Boolean.TRUE.equals(b.get("confirmed")) && !"true".equals(str(b.get("confirmed"))))
        {
            throw new ServiceException("请先确认周报内容真实准确", 422);
        }
        LocalDate mon = LocalDate.parse(str(r.get("weekStart")).substring(0, 10));
        String snap = JSON.toJSONString(recruit ? recruitSnapshot(st, mon, myStores(me.getUserId())) : weeklySnapshot(KgJdbc.asLong(st.get("storeId")), mon));
        String nick = me.getNickname() == null || me.getNickname().isEmpty() ? "员工" + me.getUserId() : me.getNickname();
        int n = db.update("update kg_weekly_report set status = 'submitted', snapshot = :snap, submitted_by = :u, submitted_name = :nm, submitted_time = now(), "
                + "version = version + 1, update_time = now() where report_id = :id and version = :v and status <> 'submitted'",
                p("snap", snap, "u", me.getUserId(), "nm", StaffSupport.cut(nick, 64), "id", reportId, "v", num(b.get("version"))));
        if (n == 0)
        {
            throw new ServiceException("周报已提交或已在别处修改，请刷新", 409);
        }
        // 招募周报只有换工主理人能打开，不发给小院主理人（发了也点不开）
        List<Long> to = new ArrayList<Long>();
        for (Map<String, Object> g : db.list("select app_user_id from kg_staff_grant where store_id = :s and status = '0' and del_flag = '0' "
                + "and role_code in (" + (recruit ? "'manager'" : opsWeeklyRoles()) + ") and app_user_id <> :u", p("s", st.get("storeId"), "u", me.getUserId())))
        {
            to.add(KgJdbc.asLong(g.get("appUserId")));
        }
        for (Long uid : withCityHolders(to, st.get("storeId"), recruit, me.getUserId()))
        {
            support.notifyStaff(uid, "system", "周报已提交",
                    (recruit ? recruitName(st) : st.get("storeName")) + " " + weekLabel(mon) + " 的周报已由 " + nick + " 提交",
                    "weekly_report", reportId, "/pages/manage/weekly?reportId=" + reportId);
        }
        return weeklyDetail(reportId);
    }

    /** 经营周报的通知发给谁：切换后只有小院主理人（新换工主理人写的是招募周报，打不开经营周报） */
    private static String opsWeeklyRoles()
    {
        return com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() ? "'keeper'" : "'manager','keeper'";
    }

    /** 周报通知名单补上本城按城市授权的同角色（和 opsWeeklyRoles 同一口径）；except 不发 */
    private List<Long> withCityHolders(List<Long> own, Object storeId, boolean recruit, Long except)
    {
        Set<Long> ids = new LinkedHashSet<Long>(own);
        if (cityGrants != null)
        {
            List<String> roles = recruit ? Arrays.asList("manager") : RolePhase.isNew() ? Arrays.asList("keeper") : Arrays.asList("manager", "keeper");
            ids.addAll(cityGrants.holderIds(KgJdbc.asLong(storeId), roles));
        }
        ids.remove(null);
        ids.remove(except);
        return new ArrayList<Long>(ids);
    }

    /** 后台退回：退回后门店可以继续修改再提交 */
    @Transactional(rollbackFor = Exception.class)
    public String weeklyReturn(Long reportId, String reason, String operator)
    {
        if (reason == null || reason.trim().isEmpty())
        {
            throw new ServiceException("请填写退回原因");
        }
        Map<String, Object> r = db.must("select store_id, week_start, status, submitted_by, report_type, version from kg_weekly_report where report_id = :id for update", p("id", reportId), "周报不存在");
        if (!"submitted".equals(str(r.get("status"))))
        {
            throw new ServiceException("只有已提交的周报可以退回");
        }
        db.updateRow("kg_weekly_report", "report_id", reportId, p("status", "returned", "return_reason", StaffSupport.cut(reason.trim(), 200),
                "version", num(r.get("version")) + 1, "update_by", operator, "update_time", new Date()));
        LocalDate mon = LocalDate.parse(str(r.get("weekStart")).substring(0, 10));
        // 只发给本店主理人 / 管家（与提交时的通知同一口径）：之前发给全店员工，普通员工点开只看到「仅门店主理人或管家可以查看」
        List<Long> to = new ArrayList<Long>();
        for (Map<String, Object> g : db.list("select app_user_id from kg_staff_grant where store_id = :s and status = '0' and del_flag = '0' "
                + "and role_code in (" + (TYPE_RECRUIT.equals(typeOf(r)) ? "'manager'" : opsWeeklyRoles()) + ")", p("s", r.get("storeId"))))
        {
            to.add(KgJdbc.asLong(g.get("appUserId")));
        }
        for (Long uid : withCityHolders(to, r.get("storeId"), TYPE_RECRUIT.equals(typeOf(r)), null))
        {
            // The report and eligible recipients were city-filtered; allow only its addressed notice.
            List<Long> saved = AdminCityContext.cities();
            try {
                AdminCityContext.clear();
                support.notifyStaff(uid, "system", "周报被退回", weekLabel(mon) + "：" + reason.trim(), "weekly_report", reportId,
                        "/pages/manage/weekly?reportId=" + reportId + (TYPE_RECRUIT.equals(typeOf(r)) ? "&as=recruit" : ""));
            } finally { if (saved == null) AdminCityContext.clear(); else AdminCityContext.set(saved); }
        }
        return "已退回";
    }

    /**
     * 后台审周报用：费用支出、待协同事项（kg_weekly_item）连同补充说明、风险与凭证一起给出，
     * 以 reviewContext 返回，后台列表「费用与协同」按钮弹窗显示。之前这些只有门店自己能看，平台审核时看不到。
     */
    public Map<String, Object> weeklyReviewContext(Long reportId)
    {
        Map<String, Object> r = db.must("select r.*, s.store_name from kg_weekly_report r left join kg_store s on s.store_id = r.store_id where r.report_id = :id",
                p("id", reportId), "周报不存在");
        LocalDate mon = LocalDate.parse(str(r.get("weekStart")).substring(0, 10));
        StringBuilder text = new StringBuilder();
        text.append("门店：").append(str(r.get("storeName")).isEmpty() ? "门店" + r.get("storeId") : r.get("storeName")).append("（门店ID ").append(r.get("storeId")).append("）\n");
        text.append("类型：").append(titleOf(typeOf(r))).append("\n");
        text.append("周次：").append(weekLabel(mon)).append("\n");
        text.append("状态：").append(WEEKLY_STATUS.getOrDefault(str(r.get("status")), str(r.get("status"))));
        if (r.get("submittedTime") != null)
        {
            text.append(" · ").append(str(r.get("submittedName")).isEmpty() ? "门店" : r.get("submittedName")).append(" 于 ")
                    .append(StaffSupport.fmt(StaffSupport.parse(r.get("submittedTime")), "yyyy-MM-dd HH:mm")).append(" 提交");
        }
        if (TYPE_RECRUIT.equals(typeOf(r)))
        {
            appendRecruitSummary(text, r);
        }
        text.append("\n\n【员工补充说明】\n").append(str(r.get("note")).isEmpty() ? "（未填写）" : r.get("note")).append("\n");
        StringBuilder fees = new StringBuilder();
        StringBuilder coops = new StringBuilder();
        long feeTotal = 0;
        for (Map<String, Object> it : db.list("select * from kg_weekly_item where report_id = :id order by sort, item_id", p("id", reportId)))
        {
            if ("fee".equals(str(it.get("itemType"))))
            {
                feeTotal += num(it.get("amountFen"));
                fees.append("· ").append(it.get("name")).append("  ").append(yuanText(num(it.get("amountFen"))))
                        .append(str(it.get("remark")).isEmpty() ? "" : "（" + it.get("remark") + "）").append("\n");
            }
            else
            {
                coops.append("· [").append(str(it.get("dept")).isEmpty() ? "未指定部门" : it.get("dept")).append("] ").append(it.get("name"))
                        .append("1".equals(str(it.get("doneFlag"))) ? "（门店标记已完成）" : "（待协同）").append("\n");
            }
        }
        text.append("\n【费用支出】").append(fees.length() == 0 ? "\n（没有填写费用）\n" : "合计 " + yuanText(feeTotal) + "\n" + fees);
        text.append("\n【待协同事项】\n").append(coops.length() == 0 ? "（没有待协同事项）\n" : coops.toString());
        text.append("\n【风险与问题】\n").append(str(r.get("risk")).isEmpty() ? "（未填写）" : r.get("risk")).append("\n");
        if (!str(r.get("returnReason")).isEmpty())
        {
            text.append("\n【上次退回原因】\n").append(r.get("returnReason")).append("\n");
        }
        // 后台通用弹窗（ExtCrud 的 image-preview）只给第一张出缩略图，其余要点开大图后左右翻：张数多于 1 时写明，免得审核人以为后面几张没传上来
        int proofCount = str(r.get("proofs")).isEmpty() ? 0 : str(r.get("proofs")).split(",").length;
        text.append("\n凭证图片：").append(proofCount == 0 ? "无" : proofCount == 1 ? "1 张（见下方）"
                : proofCount + " 张（见下方；点开图片后左右翻页可看全部 " + proofCount + " 张）");
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("title", TYPE_RECRUIT.equals(typeOf(r)) ? "换工招募情况周报内容" : "周报内容（费用支出 / 待协同事项）");
        context.put("text", text.toString());
        if (!str(r.get("proofs")).isEmpty())
        {
            context.put("images", r.get("proofs"));
        }
        return context;
    }

    /** 后台看的是提交人冻结的那份：招募、到岗、收支和各店情况 */
    private static void appendRecruitSummary(StringBuilder text, Map<String, Object> r)
    {
        text.append("\n\n【招募情况】\n");
        if (r.get("snapshot") == null)
        {
            text.append("（还没提交，提交时才冻结系统汇总）");
            return;
        }
        Map<String, Object> snap = JSON.parseObject(str(r.get("snapshot")));
        Object o = snap.get("totals");
        if (o instanceof Map)
        {
            Map<?, ?> t = (Map<?, ?>) o;
            text.append("招募换工 ").append(t.get("recruited")).append(" 人，到岗 ").append(t.get("onboarded")).append(" 人，缺口 ").append(t.get("shortfall"))
                    .append(" 个，一周内离开 ").append(t.get("leaving")).append(" 人；本周收入 ").append(t.get("income")).append("，本周支出 ").append(t.get("expense")).append("\n");
        }
        for (Map<String, Object> s : shopsOf(snap))
        {
            text.append("· ").append(s.get("name")).append("：招募 ").append(num(s.get("recruited"))).append("，到岗 ").append(num(s.get("onboarded")))
                    .append("，缺口 ").append(num(s.get("shortfall"))).append("，一周内离开 ").append(num(s.get("leaving")));
            if (Boolean.TRUE.equals(s.get("canSeeMoney")))
            {
                text.append("，收入 ").append(s.get("income")).append("，支出 ").append(s.get("expense"));
            }
            text.append("\n");
        }
    }

    // ---------------------------------------------------------------- 日报

    static final List<String> INCOME_CATS = Arrays.asList("线下房费", "线下二消", "其他收入");
    static final List<String> EXPENSE_CATS = Arrays.asList("采购支出", "人工支出", "水电网费", "维修保养", "其他支出");
    static final List<String> PAY_METHODS = Arrays.asList("现金", "微信", "支付宝", "银行卡", "其他");

    /**
     * 两本账：换工主理人（当前门店角色 manager）记招募账 recruit，小院主理人 / 员工记旅居账 stay，互相看不到。
     * 招募账的二级科目就存「一级/二级」这个串（category 是 varchar(32)）；老版本小程序只有一层选择，也能照样选、照样存。
     * 保险收入、保险支出都是手工录：app 里没有真实的保险收付款，不编数字。
     */
    static final String LEDGER_STAY = "stay";
    static final String LEDGER_RECRUIT = "recruit";
    static final String TITLE_RECRUIT_DAILY = "换工招募日报";
    static final List<String> RECRUIT_INCOME_CATS = Arrays.asList("保险收入", "其他收入/兼职", "其他收入/全职", "其他收入/其他收入");
    static final List<String> RECRUIT_EXPENSE_CATS = Arrays.asList("保险支出", "其他支出/办公费", "其他支出/交通费", "其他支出/差旅费", "其他支出/租赁费", "其他支出/业务招待费", "其他支出/其他支出");
    /** 城市负责人的消息可见范围放行这一类（KgMessageMapper）；他进不了日报页，所以这类消息没有落地页，数字全写在正文里 */
    static final String BIZ_RECRUIT_DAILY = "recruit_daily";

    static String ledgerOf(Map<String, Object> st)
    {
        return isManager(st) ? LEDGER_RECRUIT : LEDGER_STAY;
    }

    /** 老版本点「其他收入」瓦片时分类预填的是没有二级的「其他收入」：归到「其他收入/其他收入」 */
    static String recruitCategory(String cat)
    {
        if ("其他收入".equals(cat))
        {
            return "其他收入/其他收入";
        }
        return "其他支出".equals(cat) ? "其他支出/其他支出" : cat;
    }

    static String cn(LocalDate d)
    {
        String[] w = { "一", "二", "三", "四", "五", "六", "日" };
        return d.getYear() + "年" + d.getMonthValue() + "月" + d.getDayOfMonth() + "日（周" + w[d.getDayOfWeek().getValue() - 1] + "）";
    }

    private long[] manual(Long storeId, LocalDate d)
    {
        // [其他收入, 采购支出, 其他支出, 收入笔数, 采购笔数, 其他支出笔数]
        long[] v = new long[6];
        for (Map<String, Object> e : db.list("select direction, category, amount_fen from kg_daily_entry where store_id = :s and biz_date = :d and status = '0' and ledger = 'stay'",
                p("s", storeId, "d", d.toString())))
        {
            long fen = num(e.get("amountFen"));
            if ("income".equals(str(e.get("direction"))))
            {
                v[0] += fen;
                v[3]++;
            }
            else if ("采购支出".equals(str(e.get("category"))))
            {
                v[1] += fen;
                v[4]++;
            }
            else
            {
                v[2] += fen;
                v[5]++;
            }
        }
        return v;
    }

    /** 招募账 [保险收入, 保险支出, 其他收入, 其他支出, 以及四项的笔数] */
    private long[] recruitManual(Long storeId, LocalDate d)
    {
        long[] v = new long[8];
        for (Map<String, Object> e : db.list("select direction, category, amount_fen from kg_daily_entry where store_id = :s and biz_date = :d and status = '0' and ledger = 'recruit'",
                p("s", storeId, "d", d.toString())))
        {
            String cat = str(e.get("category"));
            int i = "保险收入".equals(cat) ? 0 : "保险支出".equals(cat) ? 1 : "income".equals(str(e.get("direction"))) ? 2 : 3;
            v[i] += num(e.get("amountFen"));
            v[i + 4]++;
        }
        return v;
    }

    /** 新版小程序点瓦片按这两个字段开表单；老版本还是看 manual + key，见 {@link #recruitDaily} */
    private static Map<String, Object> entryTile(Map<String, Object> tile, String direction, String category)
    {
        tile.put("direction", direction);
        tile.put("category", category);
        return tile;
    }

    /**
     * 招募账版日报：没有订房、二消（不算进结余）。
     * 老版本 daily.vue 的 onMetric：manual=true 时 key='income' 进收入表单并预填分类「其他收入」，其余 key 一律进支出表单。所以：
     * - 其他收入：key=income，预填的「其他收入」dailyAdd 会归到「其他收入/其他收入」；
     * - 保险支出、其他支出：key=expense，进支出表单自己选分类；
     * - 保险收入：manual=false，老版本点了只是打开收入明细。如果给 manual=true + key=income，表单会预填「其他收入」，
     *   不留神就把保险收入记成其他收入；给别的 key 又会进支出表单，方向错。
     */
    private Map<String, Object> recruitDaily(AppLoginUser me, Map<String, Object> st, LocalDate d)
    {
        Long sid = KgJdbc.asLong(st.get("storeId"));
        long[] mv = recruitManual(sid, d);
        long[] my = recruitManual(sid, d.minusDays(1));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(entryTile(metric("保险收入", "手动", mv[4] + " 笔明细", mv[0], my[0], "green", "shield-check", false, "income"), "income", "保险收入"));
        items.add(entryTile(metric("保险支出", "手动", mv[5] + " 笔明细", mv[1], my[1], "blue", "shield", true, "expense"), "expense", "保险支出"));
        items.add(entryTile(metric("其他收入", "手动", mv[6] + " 笔明细", mv[2], my[2], "green-dark", "wallet-cards", true, "income"), "income", null));
        items.add(entryTile(metric("其他支出", "手动", mv[7] + " 笔明细", mv[3], my[3], "blue", "file-clock", true, "expense"), "expense", null));
        long income = mv[0] + mv[2];
        long incomeY = my[0] + my[2];
        long expense = mv[1] + mv[3];
        long expenseY = my[1] + my[3];
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("storeId", sid);
        m.put("storeName", st.get("storeName"));
        m.put("date", d.toString());
        m.put("dateText", cn(d));
        m.put("synced", "已更新 " + StaffSupport.fmt(new Date(), "HH:mm"));
        m.put("items", items);
        m.put("profit", yuanText(income - expense));
        m.put("profitDelta", delta(income - expense, incomeY - expenseY));
        m.put("profitUp", income - expense >= incomeY - expenseY);
        List<Map<String, Object>> sums = new ArrayList<Map<String, Object>>();
        sums.add(summary("总收入", income, incomeY, false));
        sums.add(summary("总支出", expense, expenseY, false));
        sums.add(summary("收支结余", income - expense, incomeY - expenseY, true));
        m.put("summaries", sums);
        m.put("warning", null);
        m.put("rows", entryRows(sid, d, LEDGER_RECRUIT, new ArrayList<Map<String, Object>>()));
        m.put("incomeCats", RECRUIT_INCOME_CATS);
        m.put("expenseCats", RECRUIT_EXPENSE_CATS);
        m.put("payMethods", PAY_METHODS);
        m.put("stores", myStores(me.getUserId()));
        putRecruitRecipients(m, sid, me.getUserId());
        m.put("totals", new long[] { income, expense, income - expense });
        m.put("buckets", new long[] { mv[0], mv[1], mv[2], mv[3] });
        m.put("ledger", LEDGER_RECRUIT);
        m.put("title", TITLE_RECRUIT_DAILY);
        m.put("sectionTitle", "今日收支（手工录入）");
        m.put("sourceText", "手动录入");
        m.put("dataNote", "数据说明：这本账全部手工录入，只有本店的换工主理人能看到");
        return m;
    }

    /** 明细行。stay / recruit 两本账只读自己的 */
    private List<Map<String, Object>> entryRows(Long sid, LocalDate d, String ledger, List<Map<String, Object>> rows)
    {
        for (Map<String, Object> e : db.list("select * from kg_daily_entry where store_id = :s and biz_date = :d and status = '0' and ledger = :l order by entry_id",
                p("s", sid, "d", d.toString(), "l", ledger)))
        {
            Map<String, Object> x = row(KgJdbc.asLong(e.get("entryId")), str(e.get("category")), num(e.get("amountFen")), false, str(e.get("direction")),
                    str(e.get("payMethod")) + (str(e.get("remark")).isEmpty() ? "" : " · " + e.get("remark")));
            x.put("proofs", str(e.get("proofs")).isEmpty() ? new ArrayList<String>() : Arrays.asList(str(e.get("proofs")).split(",")));
            x.put("creator", e.get("creatorName"));
            rows.add(x);
        }
        return rows;
    }

    /**
     * 招募日报发给谁：本城城市负责人（换工主理人发给自己没意义）；本城没有就给本店其他换工主理人。
     * 仍放在 managers 这个键上：老版本的导出页就是读它显示收件人、判断能不能点发送。
     */
    private void putRecruitRecipients(Map<String, Object> m, Long sid, Long self)
    {
        Set<Long> ids = new LinkedHashSet<Long>(merchantRequests.cityLeadIds(sid));
        ids.remove(self);
        String kind = "city_lead";
        if (ids.isEmpty())
        {
            kind = "manager";
            for (Map<String, Object> g : db.list("select g.app_user_id from kg_staff_grant g join kg_app_user u on u.user_id = g.app_user_id where g.store_id = :s and g.status = '0' and g.del_flag = '0' "
                    + "and g.role_code = 'manager' and u.status = '0' and u.del_flag = '0' and g.app_user_id <> :u order by g.grant_id", p("s", sid, "u", self)))
            {
                ids.add(KgJdbc.asLong(g.get("appUserId")));
            }
            // 本城按城市授权的换工主理人也是本店的换工主理人
            ids.addAll(withCityHolders(new ArrayList<Long>(), sid, true, self));
        }
        List<Map<String, Object>> people = ids.isEmpty() ? new ArrayList<Map<String, Object>>()
                : db.list("select user_id, nickname, avatar from kg_app_user where user_id in (:ids)", p("ids", new ArrayList<Long>(ids)));
        boolean lead = "city_lead".equals(kind);
        m.put("managers", people);
        m.put("recipientKind", ids.isEmpty() ? "none" : kind);
        m.put("recipientTitle", lead ? "发送给城市负责人" : "发送给本店换工主理人");
        m.put("recipientNote", ids.isEmpty() ? "本城还没有城市负责人，本店也没有其他换工主理人"
                : "站内消息 · " + (lead ? "本城城市负责人 " : "本店其他换工主理人 ") + people.size() + " 人");
        m.put("recipientEmpty", "本城还没有城市负责人，本店也没有其他换工主理人，暂时没有人可以发送");
    }

    public Map<String, Object> daily(Long storeId, String date)
    {
        AppLoginUser me = me();
        Map<String, Object> st = store(me.getUserId(), storeId);
        Long sid = KgJdbc.asLong(st.get("storeId"));
        LocalDate today = LocalDate.now();
        LocalDate d = date == null || date.isEmpty() ? today : LocalDate.parse(date);
        if (d.isAfter(today))
        {
            throw new ServiceException("不能查看未来的日期");
        }
        if (LEDGER_RECRUIT.equals(ledgerOf(st)))
        {
            return recruitDaily(me, st, d);
        }
        List<Long> s = Collections.singletonList(sid);
        Map<String, Long> rev = stats.revenue(s, d, d.plusDays(1));
        Map<String, Long> revY = stats.revenue(s, d.minusDays(1), d);
        long stayOrders = db.count("select count(*) from kg_stay_order where store_id = :s and del_flag = '0' and paid_time >= :f and paid_time < :t",
                p("s", sid, "f", d.toString(), "t", d.plusDays(1).toString()));
        long shopOrders = db.count("select count(*) from kg_shop_order where store_id = :s and del_flag = '0' and paid_time >= :f and paid_time < :t",
                p("s", sid, "f", d.toString(), "t", d.plusDays(1).toString()))
                + db.count("select count(*) from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id where a.store_id = :s and g.fee_fen > 0 "
                        + "and g.paid_time >= :f and g.paid_time < :t", p("s", sid, "f", d.toString(), "t", d.plusDays(1).toString()));
        long[] mv = manual(sid, d);
        long[] my = manual(sid, d.minusDays(1));
        long shop = rev.get("shop") + rev.get("activity");
        long shopY = revY.get("shop") + revY.get("activity");
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(metric("订房收入", "系统", "订单 " + stayOrders + " 笔", rev.get("stay"), revY.get("stay"), "green", "bed-double", false, "stay"));
        items.add(metric("二消收入", "系统", "订单 " + shopOrders + " 笔", shop, shopY, "ochre", "coffee", false, "shop"));
        items.add(metric("其他收入", "手动", mv[3] + " 笔明细", mv[0], my[0], "green-dark", "wallet-cards", true, "income"));
        items.add(metric("采购支出", "手动", mv[4] + " 笔明细", mv[1], my[1], "blue", "shopping-cart", true, "采购支出"));
        items.add(metric("其他支出", "手动", mv[5] + " 笔明细", mv[2], my[2], "blue", "file-clock", true, "expense"));
        long income = rev.get("stay") + shop + mv[0];
        long incomeY = revY.get("stay") + shopY + my[0];
        long expense = mv[1] + mv[2];
        long expenseY = my[1] + my[2];
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("storeId", sid);
        m.put("storeName", st.get("storeName"));
        m.put("date", d.toString());
        m.put("dateText", cn(d));
        m.put("synced", "系统已同步 " + StaffSupport.fmt(new Date(), "HH:mm"));
        m.put("items", items);
        m.put("profit", yuanText(income - expense));
        m.put("profitDelta", delta(income - expense, incomeY - expenseY));
        m.put("profitUp", income - expense >= incomeY - expenseY);
        List<Map<String, Object>> sums = new ArrayList<Map<String, Object>>();
        sums.add(summary("总营收", income, incomeY, false));
        sums.add(summary("总支出", expense, expenseY, false));
        sums.add(summary("收支结余", income - expense, incomeY - expenseY, true));
        m.put("summaries", sums);
        String warn = null;
        if (revY.get("stay") > 0 && rev.get("stay") < revY.get("stay") * 0.8)
        {
            warn = "订房收入较昨日下降超过 20%";
        }
        else if (shopY > 0 && shop < shopY * 0.8)
        {
            warn = "二消收入较昨日下降超过 20%";
        }
        m.put("warning", warn);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row(null, "订房收入", rev.get("stay"), true, "income", "订单 " + stayOrders + " 笔"));
        rows.add(row(null, "二消收入", shop, true, "income", "订单 " + shopOrders + " 笔"));
        m.put("rows", entryRows(sid, d, LEDGER_STAY, rows));
        m.put("incomeCats", INCOME_CATS);
        m.put("expenseCats", EXPENSE_CATS);
        m.put("payMethods", PAY_METHODS);
        m.put("stores", myStores(me.getUserId()));
        List<Map<String, Object>> managers;
        if (com.ruoyi.kangaroo.ext.staff.RolePhase.isNew())
        {
            // 切换后每日经营报表发本城城市负责人（新换工主理人看的是招募账）；本城没有也不算错，日报照样留在系统里
            Set<Long> ids = new LinkedHashSet<Long>(merchantRequests.cityLeadIds(sid));
            ids.remove(me.getUserId());
            managers = ids.isEmpty() ? new ArrayList<Map<String, Object>>()
                    : db.list("select user_id, nickname, avatar from kg_app_user where user_id in (:ids)", p("ids", new ArrayList<Long>(ids)));
            m.put("recipientKind", ids.isEmpty() ? "none" : "city_lead");
            m.put("recipientTitle", "发送给城市负责人");
            m.put("recipientNote", ids.isEmpty() ? "本城还没有城市负责人，日报留在系统里，平台可以在后台查看" : "站内消息 · 本城城市负责人 " + managers.size() + " 人");
        }
        else
        {
            managers = db.list("select u.user_id, u.nickname, u.avatar from kg_staff_grant g join kg_app_user u on u.user_id = g.app_user_id "
                    + "where g.store_id = :s and g.status = '0' and g.del_flag = '0' and g.role_code = 'manager'", p("s", sid));
        }
        m.put("managers", managers);
        m.put("totals", new long[] { income, expense, income - expense });
        m.put("ledger", LEDGER_STAY);
        m.put("title", "每日经营报表");
        return m;
    }

    private static Map<String, Object> metric(String name, String source, String detail, long cur, long prev, String tone, String icon, boolean manual, String key)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("source", source);
        m.put("detail", detail);
        m.put("amount", yuanText(cur));
        m.put("delta", delta(cur, prev));
        m.put("up", cur >= prev);
        m.put("tone", tone);
        m.put("icon", icon);
        m.put("manual", manual);
        m.put("key", key);
        return m;
    }

    private static Map<String, Object> summary(String label, long cur, long prev, boolean green)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("amount", yuanText(cur));
        m.put("delta", "较昨日 " + delta(cur, prev) + (cur >= prev ? " ↑" : " ↓"));
        m.put("green", green);
        return m;
    }

    private static Map<String, Object> row(Long id, String name, long fen, boolean system, String direction, String note)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("entryId", id);
        m.put("name", name);
        m.put("amount", ("expense".equals(direction) ? "-" : "") + yuanText(fen));
        m.put("amountFen", fen);
        m.put("system", system);
        m.put("direction", direction);
        m.put("note", note);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dailyAdd(Map<String, Object> b)
    {
        AppLoginUser me = me();
        Map<String, Object> st = store(me.getUserId(), KgJdbc.asLong(b.get("storeId")));
        LocalDate d = LocalDate.parse(str(b.get("date")).isEmpty() ? LocalDate.now().toString() : str(b.get("date")));
        if (d.isAfter(LocalDate.now()) || d.isBefore(LocalDate.now().minusDays(90)))
        {
            throw new ServiceException("只能录入近 90 天内的明细", 422);
        }
        String dir = str(b.get("direction"));
        String ledger = ledgerOf(st);
        boolean recruit = LEDGER_RECRUIT.equals(ledger);
        String cat = recruit ? recruitCategory(str(b.get("category"))) : str(b.get("category"));
        List<String> incomes = recruit ? RECRUIT_INCOME_CATS : INCOME_CATS;
        List<String> expenses = recruit ? RECRUIT_EXPENSE_CATS : EXPENSE_CATS;
        if (!("income".equals(dir) && incomes.contains(cat)) && !("expense".equals(dir) && expenses.contains(cat)))
        {
            throw new ServiceException("请选择分类", 422);
        }
        long fen = num(b.get("amountFen"));
        if (fen <= 0 || fen > 100000000L)
        {
            throw new ServiceException("金额需在 0.01–1,000,000 元之间", 422);
        }
        String pay = str(b.get("payMethod"));
        if (!PAY_METHODS.contains(pay))
        {
            throw new ServiceException("请选择收付方式", 422);
        }
        List<String> proofs = new ArrayList<String>();
        if (b.get("proofs") instanceof List)
        {
            for (Object o : (List<?>) b.get("proofs"))
            {
                proofs.add(str(o));
            }
        }
        if (proofs.size() > 5)
        {
            throw new ServiceException("凭证最多 5 张", 422);
        }
        String idem = str(b.get("idemKey"));
        String key = idem.isEmpty() ? null : "daily:" + me.getUserId() + ":" + StaffSupport.cut(idem, 64);
        if (key != null)
        {
            Map<String, Object> old = db.one("select biz_id from kg_idempotency where idem_key = :k", p("k", key));
            if (old != null)
            {
                // 同一次提交重放（网络重试）：返回第一次的结果
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("entryId", old.get("bizId"));
                m.put("message", "已保存");
                return m;
            }
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", st.get("storeId"));
        row.put("ledger", ledger);
        row.put("biz_date", d.toString());
        row.put("direction", dir);
        row.put("category", cat);
        row.put("amount_fen", fen);
        row.put("pay_method", pay);
        row.put("remark", StaffSupport.cut(str(b.get("remark")).trim(), 120));
        row.put("proofs", String.join(",", proofs));
        row.put("creator_id", me.getUserId());
        row.put("creator_name", StaffSupport.cut(me.getNickname(), 64));
        row.put("create_time", new Date());
        long id = db.insert("kg_daily_entry", row);
        if (key != null)
        {
            try
            {
                db.update("insert into kg_idempotency (idem_key, biz_type, biz_id, create_time) values (:k, 'daily_entry', :id, now())", p("k", key, "id", id));
            }
            catch (org.springframework.dao.DuplicateKeyException e)
            {
                // 并发的同一次提交：本次回滚，以先到的为准
                throw new ServiceException("这条明细已经保存过了", 409);
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("entryId", id);
        m.put("message", "已保存");
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public String dailyVoid(Long entryId)
    {
        AppLoginUser me = me();
        Map<String, Object> e = db.must("select store_id, status, ledger from kg_daily_entry where entry_id = :id", p("id", entryId), "明细不存在");
        Map<String, Object> st = store(me.getUserId(), KgJdbc.asLong(e.get("storeId")));
        // 两本账互相看不到，也不能隔着账本作废别人的明细
        if (!ledgerOf(st).equals(str(e.get("ledger")).isEmpty() ? LEDGER_STAY : str(e.get("ledger"))))
        {
            throw new ServiceException("没有这条明细的权限", 403);
        }
        if (!"0".equals(str(e.get("status"))))
        {
            throw new ServiceException("这条明细已作废");
        }
        db.update("update kg_daily_entry set status = '1', void_by = :by, void_time = now() where entry_id = :id", p("by", "app:" + me.getUserId(), "id", entryId));
        return "已作废";
    }

    /** 发送给本店主理人（站内消息，路由到这一天的报表） */
    public Map<String, Object> dailySend(Long storeId, String date)
    {
        AppLoginUser me = me();
        Map<String, Object> d = daily(storeId, date);
        if (LEDGER_RECRUIT.equals(d.get("ledger")))
        {
            return recruitSend(me, d);
        }
        List<?> managers = (List<?>) d.get("managers");
        boolean switched = com.ruoyi.kangaroo.ext.staff.RolePhase.isNew();
        if (managers.isEmpty())
        {
            if (switched)
            {
                // 本城还没有城市负责人：小院主理人照常写日报，这里如实说没发出去，不报错拦他
                Map<String, Object> none = new LinkedHashMap<String, Object>();
                none.put("sent", 0);
                none.put("message", "本城还没有城市负责人，日报已留在系统里，平台可以在后台查看");
                return none;
            }
            throw new ServiceException("本店还没有主理人账号", 422);
        }
        long[] t = (long[]) d.get("totals");
        int n = 0;
        for (Object o : managers)
        {
            Long uid = KgJdbc.asLong(((Map<?, ?>) o).get("userId"));
            // 收件人是换工主理人，他打开日报页看到的是招募账，不是这份旅居账：数字都在正文里，不再给落地页，免得点进去对不上
            support.notifyStaff(uid, "system", "每日经营报表 " + d.get("dateText"), d.get("storeName") + "：总营收 " + yuanText(t[0]) + "，总支出 " + yuanText(t[1])
                    + "，收支结余 " + yuanText(t[2]) + "（" + (me.getNickname() == null ? "员工" : me.getNickname()) + " 发送）", "daily_report", KgJdbc.asLong(d.get("storeId")), null);
            n++;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sent", n);
        m.put("message", "已发送给 " + n + (switched ? " 位城市负责人" : " 位主理人"));
        return m;
    }

    private Map<String, Object> recruitSend(AppLoginUser me, Map<String, Object> d)
    {
        List<?> people = (List<?>) d.get("managers");
        if (people.isEmpty())
        {
            throw new ServiceException(str(d.get("recipientEmpty")), 422);
        }
        boolean lead = "city_lead".equals(d.get("recipientKind"));
        long[] b = (long[]) d.get("buckets");
        long[] t = (long[]) d.get("totals");
        String content = d.get("storeName") + "：保险收入 " + yuanText(b[0]) + "，保险支出 " + yuanText(b[1]) + "，其他收入 " + yuanText(b[2]) + "，其他支出 " + yuanText(b[3])
                + "，收支结余 " + yuanText(t[2]) + "（" + (me.getNickname() == null ? "换工主理人" : me.getNickname()) + " 发送）";
        Long sid = KgJdbc.asLong(d.get("storeId"));
        for (Object o : people)
        {
            support.notifyStaff(KgJdbc.asLong(((Map<?, ?>) o).get("userId")), "system", TITLE_RECRUIT_DAILY + " " + d.get("dateText"), content,
                    lead ? BIZ_RECRUIT_DAILY : "daily_report", sid, lead ? null : "/pages/manage/daily?storeId=" + sid + "&date=" + d.get("date"));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sent", people.size());
        m.put("message", "已发送给 " + people.size() + (lead ? " 位城市负责人" : " 位换工主理人"));
        return m;
    }

    // ---------------------------------------------------------------- 看板

    static int daysOf(String period)
    {
        if ("30".equals(period) || "30天".equals(period))
        {
            return 30;
        }
        if ("90".equals(period) || "季度".equals(period))
        {
            return 90;
        }
        return 7;
    }

    private static Map<String, Object> series(String label, String value, String delta, boolean up, List<Long> vals)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", value);
        m.put("delta", delta);
        m.put("up", up);
        m.put("series", vals);
        return m;
    }

    public Map<String, Object> overview(String period)
    {
        AppLoginUser me = me();
        List<Map<String, Object>> stores = myBusinessStores(me.getUserId());
        if (stores.isEmpty())
        {
            throw new ServiceException("仅门店主理人或管家可以查看数据看板", 403);
        }
        List<Long> s = ids(stores);
        int days = daysOf(period);
        LocalDate to = LocalDate.now().plusDays(1);
        LocalDate from = to.minusDays(days);
        LocalDate pFrom = from.minusDays(days);
        Map<String, Long> rev = stats.revenue(s, from, to);
        Map<String, Long> revP = stats.revenue(s, pFrom, from);
        Integer occ = stats.occupancy(s, from, to);
        Integer occP = stats.occupancy(s, pFrom, from);
        Map<String, Long> apps = stats.applications(s, from, to);
        Map<String, Long> appsP = stats.applications(s, pFrom, from);
        Long conv = KgStats.rateBp(apps.get("confirmed"), apps.get("n"));
        Long convP = KgStats.rateBp(appsP.get("confirmed"), appsP.get("n"));
        Map<String, Long> good = stats.goodRate(s, from, to);
        Map<String, Long> goodP = stats.goodRate(s, pFrom, from);
        long[] daily = stats.dailyRevenue(s, from, to);
        long[] nights = stats.dailyRoomNights(s, from, to);
        long[] dApps = stats.dailyApplications(s, from, to);
        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        metrics.add(series("营业额（元）", yuanText(rev.get("total")).replace("¥", ""), delta(rev.get("total"), revP.get("total")), rev.get("total") >= revP.get("total"),
                KgStats.list(daily)));
        metrics.add(series("入住率", pct(occ), occ == null ? "—" : deltaBp(occ.longValue(), occP == null ? null : occP.longValue()), occ == null || occP == null || occ >= occP,
                KgStats.list(nights)));
        metrics.add(series("报名转化率", pct(conv), deltaBp(conv, convP), conv == null || convP == null || conv >= convP, KgStats.list(dApps)));
        metrics.add(series("二消收入（元）", yuanText(rev.get("shop") + rev.get("activity")).replace("¥", ""),
                delta(rev.get("shop") + rev.get("activity"), revP.get("shop") + revP.get("activity")), rev.get("shop") + rev.get("activity") >= revP.get("shop") + revP.get("activity"),
                KgStats.list(stats.dailyOrders(s, from, to))));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("range", from.getMonthValue() + "月" + from.getDayOfMonth() + "日 - " + to.minusDays(1).getMonthValue() + "月" + to.minusDays(1).getDayOfMonth() + "日");
        m.put("compare", "与" + pFrom.getMonthValue() + "月" + pFrom.getDayOfMonth() + "日 - " + from.minusDays(1).getMonthValue() + "月" + from.minusDays(1).getDayOfMonth() + "日对比");
        m.put("metrics", metrics);
        List<Long> goodSeries = new ArrayList<Long>();
        int buckets = Math.min(days, 12);
        for (int i = 0; i < buckets; i++)
        {
            LocalDate a = from.plusDays((long) days * i / buckets);
            LocalDate z = from.plusDays((long) days * (i + 1) / buckets);
            Long bp = stats.goodRate(s, a, z).get("bp");
            goodSeries.add(bp == null ? 0L : bp);
        }
        m.put("good", series("好评率", pct(good.get("bp")), deltaBp(good.get("bp"), goodP.get("bp")), good.get("bp") == null || goodP.get("bp") == null
                || good.get("bp") >= goodP.get("bp"), goodSeries));
        m.put("note", "指标说明：* 以上数据为你有权限的 " + stores.size() + " 家门店汇总；营业额按支付成功日计入、退款日扣减");
        return m;
    }

    public Map<String, Object> trend(Long storeId, String city, String period, String end)
    {
        AppLoginUser me = me();
        List<Map<String, Object>> stores = myBusinessStores(me.getUserId());
        if (stores.isEmpty())
        {
            throw new ServiceException("仅门店主理人或管家可以查看数据看板", 403);
        }
        List<Map<String, Object>> scope = new ArrayList<Map<String, Object>>();
        List<String> cities = new ArrayList<String>();
        for (Map<String, Object> st : stores)
        {
            if (!cities.contains(str(st.get("city"))))
            {
                cities.add(str(st.get("city")));
            }
            if ((storeId == null || storeId.equals(KgJdbc.asLong(st.get("storeId")))) && (city == null || city.isEmpty() || city.equals(str(st.get("city")))))
            {
                scope.add(st);
            }
        }
        List<Long> s = ids(scope);
        int days = daysOf(period);
        LocalDate last = end == null || end.isEmpty() ? LocalDate.now() : LocalDate.parse(end);
        if (last.isAfter(LocalDate.now()))
        {
            last = LocalDate.now();
        }
        LocalDate to = last.plusDays(1);
        LocalDate from = to.minusDays(days);
        LocalDate pFrom = from.minusDays(days);
        long[] orders = stats.dailyOrders(s, from, to);
        long[] nights = stats.dailyRoomNights(s, from, to);
        long[] apps = stats.dailyApplications(s, from, to);
        long[] people = stats.dailyActivityPeople(s, from, to);
        long[][] prev = { stats.dailyOrders(s, pFrom, from), stats.dailyRoomNights(s, pFrom, from), stats.dailyApplications(s, pFrom, from),
                stats.dailyActivityPeople(s, pFrom, from) };
        long[][] cur = { orders, nights, apps, people };
        String[] labels = { "订单数（单）", "入住数（间夜）", "报名数（人）", "活动参与（人次）" };
        List<Map<String, Object>> totals = new ArrayList<Map<String, Object>>();
        long[] sums = new long[4];
        long[] psums = new long[4];
        for (int k = 0; k < 4; k++)
        {
            for (long v : cur[k])
            {
                sums[k] += v;
            }
            for (long v : prev[k])
            {
                psums[k] += v;
            }
            totals.add(series(labels[k], String.format("%,d", sums[k]), delta(sums[k], psums[k]), sums[k] >= psums[k], null));
        }
        List<String> xl = new ArrayList<String>();
        for (int i = 0; i < 5; i++)
        {
            LocalDate d = from.plusDays((long) (days - 1) * i / 4);
            xl.add(d.getMonthValue() + "/" + d.getDayOfMonth());
        }
        List<String> insights = new ArrayList<String>();
        int peak = 0;
        for (int i = 1; i < orders.length; i++)
        {
            if (orders[i] > orders[peak])
            {
                peak = i;
            }
        }
        if (orders.length > 0 && orders[peak] > 0)
        {
            LocalDate pd = from.plusDays(peak);
            insights.add(pd.getMonthValue() + "月" + pd.getDayOfMonth() + "日订单数最多，共 " + orders[peak] + " 单");
        }
        int best = -1;
        double bestGrowth = 0;
        for (int k = 0; k < 4; k++)
        {
            if (psums[k] > 0)
            {
                double g = (sums[k] - psums[k]) * 100.0 / psums[k];
                if (g > bestGrowth)
                {
                    bestGrowth = g;
                    best = k;
                }
            }
        }
        if (best >= 0)
        {
            insights.add(labels[best].replaceAll("（.*）", "") + "增长最快，较上一周期 +" + String.format("%.1f", bestGrowth) + "%");
        }
        long weekend = 0;
        long weekday = 0;
        int nWeekend = 0;
        int nWeekday = 0;
        for (int i = 0; i < apps.length; i++)
        {
            DayOfWeek dw = from.plusDays(i).getDayOfWeek();
            if (dw == DayOfWeek.SATURDAY || dw == DayOfWeek.SUNDAY)
            {
                weekend += apps[i];
                nWeekend++;
            }
            else
            {
                weekday += apps[i];
                nWeekday++;
            }
        }
        if (nWeekend > 0 && nWeekday > 0 && weekday > 0 && (weekend / (double) nWeekend) >= 1.2 * (weekday / (double) nWeekday))
        {
            insights.add("报名数在周末表现更佳，可以把活动和岗位推广放在周末");
        }
        if (insights.isEmpty())
        {
            insights.add("这段时间数据较少，暂时没有明显趋势");
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("stores", stores);
        m.put("cities", cities);
        m.put("storeName", storeId == null ? "全部门店" : str(scope.isEmpty() ? "—" : scope.get(0).get("storeName")));
        m.put("city", city == null || city.isEmpty() ? "全部城市" : city);
        m.put("days", days);
        m.put("periodText", "近" + days + "天");
        m.put("compare", "同期对比：" + pFrom.getMonthValue() + "月" + pFrom.getDayOfMonth() + "日 - " + from.minusDays(1).getMonthValue() + "月" + from.minusDays(1).getDayOfMonth() + "日");
        m.put("orders", KgStats.list(orders));
        m.put("nights", KgStats.list(nights));
        m.put("apps", KgStats.list(apps));
        m.put("people", KgStats.list(people));
        m.put("xLabels", xl);
        m.put("totals", totals);
        m.put("insights", insights);
        return m;
    }

    /** 门店排名：只在本人有授权的门店之间排；名次变化对比上一等长周期 */
    public Map<String, Object> rank(String metric, String period)
    {
        AppLoginUser me = me();
        List<Map<String, Object>> stores = myBusinessStores(me.getUserId());
        if (stores.isEmpty())
        {
            throw new ServiceException("仅门店主理人或管家可以查看数据看板", 403);
        }
        int days = daysOf(period);
        LocalDate to = LocalDate.now().plusDays(1);
        LocalDate from = to.minusDays(days);
        LocalDate pFrom = from.minusDays(days);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> st : stores)
        {
            List<Long> s = Collections.singletonList(KgJdbc.asLong(st.get("storeId")));
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("storeId", st.get("storeId"));
            r.put("name", st.get("storeName"));
            r.put("area", st.get("city") + (str(st.get("address")).isEmpty() ? "" : " · " + str(st.get("address")).split("[·\\s]")[0]));
            r.put("cover", st.get("cover"));
            Long v;
            Long pv;
            String display;
            if ("occupancy".equals(metric))
            {
                Integer o = stats.occupancy(s, from, to);
                Integer po = stats.occupancy(s, pFrom, from);
                v = o == null ? null : o.longValue();
                pv = po == null ? null : po.longValue();
                display = pct(v);
            }
            else if ("good".equals(metric))
            {
                v = stats.goodRate(s, from, to).get("bp");
                pv = stats.goodRate(s, pFrom, from).get("bp");
                display = pct(v);
            }
            else if ("fulfill".equals(metric))
            {
                Map<String, Long> a = stats.applications(s, from, to);
                Map<String, Long> pa = stats.applications(s, pFrom, from);
                v = KgStats.rateBp(a.get("onboarded"), a.get("confirmed"));
                pv = KgStats.rateBp(pa.get("onboarded"), pa.get("confirmed"));
                display = pct(v);
            }
            else
            {
                v = stats.revenue(s, from, to).get("total");
                pv = stats.revenue(s, pFrom, from).get("total");
                display = yuanText(v);
            }
            r.put("value", v);
            r.put("prev", pv);
            r.put("display", display);
            rows.add(r);
        }
        List<Map<String, Object>> prevOrder = new ArrayList<Map<String, Object>>(rows);
        prevOrder.sort((a, b) -> Long.compare(b.get("prev") == null ? -1 : (Long) b.get("prev"), a.get("prev") == null ? -1 : (Long) a.get("prev")));
        rows.sort((a, b) -> Long.compare(b.get("value") == null ? -1 : (Long) b.get("value"), a.get("value") == null ? -1 : (Long) a.get("value")));
        for (int i = 0; i < rows.size(); i++)
        {
            int before = prevOrder.indexOf(rows.get(i));
            int change = before - i;
            rows.get(i).put("change", String.valueOf(Math.abs(change)));
            rows.get(i).put("down", change < 0);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("range", "近" + days + "天（" + md(from) + "-" + md(to.minusDays(1)) + "）");
        m.put("note", "* 名次变化对比上一周期（" + md(pFrom) + "-" + md(from.minusDays(1)) + "），只在你有权限的 " + stores.size() + " 家门店之间排名");
        m.put("rows", rows);
        return m;
    }

    private static Map<String, Object> businessStore(List<Map<String, Object>> stores, Long storeId)
    {
        for (Map<String, Object> s : stores)
        {
            if (storeId.equals(KgJdbc.asLong(s.get("storeId"))))
            {
                return s;
            }
        }
        throw new ServiceException("没有该门店的经营权限", 403);
    }

    static final Map<String, String> ADVICE = new LinkedHashMap<String, String>();
    static
    {
        ADVICE.put("位置", "补充到店交通与周边指引");
        ADVICE.put("氛围", "增加团队沟通与迎新安排");
        ADVICE.put("工作", "合理排班，明确工作安排");
        ADVICE.put("吃住", "改善员工餐食与住宿条件");
        ADVICE.put("住宿条件", "改善员工住宿条件（床品、清洁、热水）");
        ADVICE.put("工作强度", "合理排班，避免连续高强度工作");
        ADVICE.put("团队氛围", "增加团队沟通与迎新安排");
        ADVICE.put("成长学习", "增加带教与技能分享");
        ADVICE.put("位置周边", "补充到店交通与周边指引");
        ADVICE.put("卫生", "加强客房清洁与布草更换");
        ADVICE.put("设施相符", "核对房源描述与实拍图，更新老旧设施");
        ADVICE.put("服务", "优化入住接待与响应速度");
        ADVICE.put("安静舒适", "优化房间隔音措施");
        ADVICE.put("活动内容", "丰富活动内容与流程设计");
        ADVICE.put("组织安排", "提前发布集合与时间安排");
        ADVICE.put("主办方", "加强活动现场引导");
        ADVICE.put("安全秩序", "完善活动安全预案");
        ADVICE.put("集合地点", "在活动详情里补充集合地点指引");
    }

    static final Map<String, String[]> DIM_LABELS = new LinkedHashMap<String, String[]>();
    static
    {
        DIM_LABELS.put("application", KgJobReviewDimensions.LABELS);
        DIM_LABELS.put("stay_order", new String[] { "卫生", "设施相符", "服务", "安静舒适", "位置周边" });
        DIM_LABELS.put("activity_signup", new String[] { "活动内容", "组织安排", "主办方", "安全秩序", "集合地点" });
    }

    /** 评价统计：分布、分项得分高低（按评价的分项平均，不做关键词 NLP）、好评率 / 回复率 / 精选数、规则建议 */
    public Map<String, Object> reviews(Long storeId, String period)
    {
        AppLoginUser me = me();
        List<Map<String, Object>> stores = myBusinessStores(me.getUserId());
        if (stores.isEmpty())
        {
            throw new ServiceException("仅门店主理人或管家可以查看数据看板", 403);
        }
        Map<String, Object> picked = storeId == null ? null : businessStore(stores, storeId);
        List<Long> s = storeId == null ? ids(stores) : Collections.singletonList(KgJdbc.asLong(picked.get("storeId")));
        int days = daysOf(period);
        LocalDate to = LocalDate.now().plusDays(1);
        LocalDate from = to.minusDays(days);
        LocalDate pFrom = from.minusDays(days);
        Map<String, Object> ps = p("s", s, "f", from.toString(), "t", to.toString());
        Map<String, Object> agg = db.one("select count(*) n, round(avg(rating), 1) avg, coalesce(sum(rating = 5), 0) s5, coalesce(sum(rating = 4), 0) s4, "
                + "coalesce(sum(rating = 3), 0) s3, coalesce(sum(rating = 2), 0) s2, coalesce(sum(rating = 1), 0) s1, coalesce(sum(reply is not null and reply <> ''), 0) replied, "
                + "coalesce(sum(featured = '1'), 0) featured from kg_review where store_id in (:s) and status = '0' and del_flag = '0' and create_time >= :f and create_time < :t", ps);
        Map<String, Object> aggP = db.one("select count(*) n, coalesce(sum(rating >= 4), 0) g, coalesce(sum(reply is not null and reply <> ''), 0) replied, "
                + "coalesce(sum(featured = '1'), 0) featured from kg_review where store_id in (:s) and status = '0' and del_flag = '0' and create_time >= :f and create_time < :t",
                p("s", s, "f", pFrom.toString(), "t", from.toString()));
        long n = num(agg.get("n"));
        String[] colors = { "#4F7A66", "#D9A441", "#E39A2E", "#A94734", "#A94734" };
        List<Map<String, Object>> dist = new ArrayList<Map<String, Object>>();
        for (int star = 5; star >= 1; star--)
        {
            long c = num(agg.get("s" + star));
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("star", star);
            d.put("pct", n == 0 ? 0 : Math.round(c * 100.0 / n));
            d.put("num", String.format("%,d", c) + " (" + (n == 0 ? 0 : Math.round(c * 100.0 / n)) + "%)");
            d.put("color", colors[5 - star]);
            dist.add(d);
        }
        Map<String, double[]> dimSum = new LinkedHashMap<String, double[]>();
        for (Map<String, Object> r : db.list("select biz_type, dims from kg_review where store_id in (:s) and status = '0' and del_flag = '0' and create_time >= :f "
                + "and create_time < :t and dims is not null and dims <> ''", ps))
        {
            String[] labels = DIM_LABELS.get(str(r.get("bizType")));
            if (labels == null)
            {
                continue;
            }
            String[] parts = str(r.get("dims")).replaceAll("[\\[\\] ]", "").split(",");
            if ("application".equals(str(r.get("bizType"))))
            {
                labels = KgJobReviewDimensions.LABELS;
                parts = java.util.Arrays.stream(KgJobReviewDimensions.scores(r.get("dims"))).mapToObj(String::valueOf).toArray(String[]::new);
            }
            for (int i = 0; i < Math.min(labels.length, parts.length); i++)
            {
                try
                {
                    double v = Integer.parseInt(parts[i]);
                    if (v < 1 || v > 5) continue;
                    double[] acc = dimSum.computeIfAbsent(labels[i], k -> new double[2]);
                    acc[0] += v;
                    acc[1]++;
                }
                catch (NumberFormatException e)
                {
                    // 跳过历史数据
                }
            }
        }
        List<Object[]> dims = new ArrayList<Object[]>();
        for (Map.Entry<String, double[]> e : dimSum.entrySet())
        {
            dims.add(new Object[] { e.getKey(), e.getValue()[0] / e.getValue()[1], (long) e.getValue()[1] });
        }
        dims.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
        List<List<Object>> high = new ArrayList<List<Object>>();
        List<List<Object>> low = new ArrayList<List<Object>>();
        for (int i = 0; i < dims.size() && high.size() < 5; i++)
        {
            high.add(Arrays.asList(dims.get(i)[0], String.format("%.1f 分", (Double) dims.get(i)[1])));
        }
        List<String> advice = new ArrayList<String>();
        for (int i = dims.size() - 1; i >= 0 && low.size() < 5; i--)
        {
            if ((Double) dims.get(i)[1] < 4.5)
            {
                low.add(Arrays.asList(dims.get(i)[0], String.format("%.1f 分", (Double) dims.get(i)[1])));
                if (advice.size() < 3 && ADVICE.containsKey(str(dims.get(i)[0])))
                {
                    advice.add(ADVICE.get(str(dims.get(i)[0])));
                }
            }
        }
        long nP = num(aggP.get("n"));
        Long goodBp = KgStats.rateBp(num(agg.get("s5")) + num(agg.get("s4")), n);
        Long goodBpP = KgStats.rateBp(num(aggP.get("g")), nP);
        Long replyBp = KgStats.rateBp(num(agg.get("replied")), n);
        Long replyBpP = KgStats.rateBp(num(aggP.get("replied")), nP);
        List<Map<String, Object>> kpis = new ArrayList<Map<String, Object>>();
        kpis.add(series("好评率", pct(goodBp), deltaBp(goodBp, goodBpP), goodBp == null || goodBpP == null || goodBp >= goodBpP, null));
        kpis.add(series("回复率", pct(replyBp), deltaBp(replyBp, replyBpP), replyBp == null || replyBpP == null || replyBp >= replyBpP, null));
        long feat = num(agg.get("featured"));
        long featP = num(aggP.get("featured"));
        kpis.add(series("精选数量", String.valueOf(feat), (feat - featP >= 0 ? "+" : "") + (feat - featP), feat >= featP, null));
        String summary;
        if (n == 0)
        {
            summary = "这段时间还没有评价。";
        }
        else
        {
            summary = "共 " + n + " 条评价，平均 " + agg.get("avg") + " 分" + (high.isEmpty() ? "" : "；得分较高：" + high.get(0).get(0))
                    + (low.isEmpty() ? "" : "；需要改进：" + low.get(0).get(0)) + "。（按评价分项规则整理，不是 AI 生成）";
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("stores", stores);
        m.put("storeName", storeId == null ? "全部门店" : str(picked.get("storeName")));
        m.put("range", "近" + days + "天（" + md(from) + "-" + md(to.minusDays(1)) + "）");
        m.put("avg", agg.get("avg") == null ? "—" : String.valueOf(agg.get("avg")));
        m.put("avgValue", agg.get("avg") == null ? 0 : Double.parseDouble(String.valueOf(agg.get("avg"))));
        m.put("count", "共 " + String.format("%,d", n) + " 条评价");
        m.put("dist", dist);
        m.put("high", high);
        m.put("low", low);
        m.put("kpis", kpis);
        m.put("summary", summary);
        m.put("advice", advice.isEmpty() ? Collections.singletonList(n == 0 ? "暂无评价，先邀请完成的客人和伙伴写评价" : "各项得分都不错，继续保持") : advice);
        m.put("adviceReal", !advice.isEmpty());
        return m;
    }
}
