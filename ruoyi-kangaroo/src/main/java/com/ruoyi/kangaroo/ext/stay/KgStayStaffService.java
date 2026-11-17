package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.D;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.date;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.fen;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.str;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 员工端旅居：本店房源（统计、草稿、提交审核、下架、群码）、房态价格、订单、到店核销、退款审批。
 *
 * 房源上线只能由平台后台审核（kangaroo:stay:audit），员工端最多把草稿 / 驳回 / 下架的房源提交到"待审核"。
 * 核销码统一解析：旅居订单与增值服务订单共用一个 10 位码空间，跨门店的码一律回"核销码无效"，不区分是不存在还是别家的。
 */
@Service
public class KgStayStaffService
{
    @Autowired
    private KgJdbc db;

    @Autowired private com.ruoyi.kangaroo.ext.staff.KgMerchantAgreements merchantAgreements;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgStayOrderService stayOrders;

    @Autowired
    private KgShopService shop;

    @Autowired
    private com.ruoyi.kangaroo.ext.core.KgPrivateFiles files;

    /**
     * 资质类型：编码 → [名称, 是否必填]。
     * 2026-11-03 主人：「改成可选不可选，加上食品安全许可证」：消防安全检查合格证改为选填；新增选填的食品安全许可证（food），
     * 排在消防后面。qual_type 是 varchar(16)，加一种不用改表；提交审核、后台「小程序展示检查」都按这里的必填标记判断
     */
    static final String[][] QUALS = { { "license", "营业执照", "1" }, { "fire", "消防安全检查合格证", "0" },
            { "food", "食品安全许可证", "0" }, { "special", "特种行业许可证", "0" }, { "idcard", "负责人身份证", "1" } };

    public static String stayStatusLabel(String st)
    {
        switch (st == null ? "" : st)
        {
            case "draft": return "草稿";
            case "pending": return "待审核";
            case "online": return "已上线";
            case "offline": return "已下架";
            case "rejected": return "已驳回";
            default: return st;
        }
    }

    private Map<String, Object> ownStay(StaffSupport.Ctx c, Long stayId)
    {
        Map<String, Object> s = db.one("select * from kg_stay where stay_id = :id and del_flag = '0'", p("id", stayId));
        if (s == null || !c.me.hasStoreAccess(KgJdbc.asLong(s.get("storeId"))))
        {
            throw new ServiceException("房源不存在或没有权限", HttpStatus.FORBIDDEN);
        }
        return s;
    }

    // ---------- 房源 ----------

    /** 本店房源 + 统计：房源数、近 30 天入住率（已售间夜 / 可售间夜）、今日订单；status 页签 */
    public Map<String, Object> stays(StaffSupport.Ctx c, String status, String keyword)
    {
        StringBuilder where = new StringBuilder(" where s.store_id = :st and s.del_flag = '0'");
        Map<String, Object> ps = p("st", c.storeId);
        if (status != null && Arrays.asList("draft", "pending", "online", "offline", "rejected").contains(status))
        {
            where.append(" and s.status = :status");
            ps.put("status", status);
        }
        if (keyword != null && !keyword.trim().isEmpty())
        {
            where.append(" and (s.stay_name like :kw or s.area like :kw)");
            ps.put("kw", "%" + keyword.trim() + "%");
        }
        List<Map<String, Object>> rows = db.list("select s.stay_id, s.stay_name, s.cover, s.area, s.area_desc, s.address, s.status, s.audit_reason, s.rating, s.review_count, "
                + "(select count(*) from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0') room_types, "
                + "(select coalesce(sum(t.stock), 0) from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0') units, "
                + "(select min(t.base_price_fen) from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0') price_from, "
                + "(select count(*) from kg_stay_order o where o.stay_id = s.stay_id and o.status = 'confirmed') upcoming, "
                + "(select count(*) from kg_stay_order o where o.stay_id = s.stay_id and date(o.create_time) = curdate() "
                + "and o.status not in ('pending_payment','closed')) today_orders "
                + "from kg_stay s" + where + " order by field(s.status, 'online', 'pending', 'rejected', 'draft', 'offline'), s.sort, s.stay_id", ps);
        for (Map<String, Object> r : rows)
        {
            r.put("statusLabel", stayStatusLabel(str(r.get("status"))));
            r.put("occupancy", occupancy(KgJdbc.asLong(r.get("stayId"))));
        }
        Map<String, Object> counts = db.one("select count(*) total, sum(status = 'online') online, sum(status = 'pending') pending, "
                + "sum(status in ('draft','rejected')) draft, sum(status = 'offline') offline from kg_stay where store_id = :st and del_flag = '0'",
                p("st", c.storeId));
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        stats.put("stays", fen(counts.get("total")));
        stats.put("occupancy", storeOccupancy(c.storeId));
        stats.put("todayOrders", db.count("select count(*) from kg_stay_order where store_id = :st and date(create_time) = curdate() "
                + "and status not in ('pending_payment','closed')", p("st", c.storeId)));
        stats.put("todayCheckins", db.count("select count(*) from kg_stay_order where store_id = :st and check_in = curdate() "
                + "and status in ('confirmed','checked_in')", p("st", c.storeId)));
        Map<String, Object> tabs = new LinkedHashMap<String, Object>();
        tabs.put("all", fen(counts.get("total")));
        tabs.put("online", fen(counts.get("online")));
        tabs.put("pending", fen(counts.get("pending")));
        tabs.put("draft", fen(counts.get("draft")));
        tabs.put("offline", fen(counts.get("offline")));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("stats", stats);
        out.put("tabs", tabs);
        out.put("storeName", c.storeName);
        // 运营类型（直营 / 代运营 / 加盟）按 kg_store.op_type 返回；原先列表不带，页面一律写死「直营」，代运营门店也显示成直营
        Map<String, Object> store = db.one("select op_type from kg_store where store_id = :st", p("st", c.storeId));
        String opType = store == null ? "" : str(store.get("opType"));
        out.put("opType", opType);
        out.put("operateModeLabel", KgStayBrowseService.operateLabel(opType));
        return out;
    }

    /** 近 30 天入住率（百分比整数）：已确认及之后的间夜 / 可售间夜 */
    private long occupancy(Long stayId)
    {
        Map<String, Object> r = db.one("select (select coalesce(sum(t.stock), 0) from kg_stay_room_type t where t.stay_id = :s and t.status = '0' and t.del_flag = '0') units, "
                + "(select coalesce(sum(o.units * datediff(least(o.check_out, curdate()), greatest(o.check_in, date_sub(curdate(), interval 30 day)))), 0) "
                + " from kg_stay_order o where o.stay_id = :s and o.status in ('confirmed','checked_in','completed','no_show') "
                + " and o.check_out > date_sub(curdate(), interval 30 day) and o.check_in < curdate()) sold", p("s", stayId));
        long units = fen(r.get("units"));
        return units == 0 ? 0 : Math.min(100, Math.round(fen(r.get("sold")) * 100.0 / (units * 30)));
    }

    private long storeOccupancy(Long storeId)
    {
        long units = 0;
        long sold = 0;
        for (Map<String, Object> s : db.list("select stay_id from kg_stay where store_id = :st and status = 'online' and del_flag = '0'", p("st", storeId)))
        {
            Map<String, Object> r = db.one("select (select coalesce(sum(t.stock), 0) from kg_stay_room_type t where t.stay_id = :s and t.status = '0' and t.del_flag = '0') units, "
                    + "(select coalesce(sum(o.units * datediff(least(o.check_out, curdate()), greatest(o.check_in, date_sub(curdate(), interval 30 day)))), 0) "
                    + " from kg_stay_order o where o.stay_id = :s and o.status in ('confirmed','checked_in','completed','no_show') "
                    + " and o.check_out > date_sub(curdate(), interval 30 day) and o.check_in < curdate()) sold", p("s", s.get("stayId")));
            units += fen(r.get("units"));
            sold += fen(r.get("sold"));
        }
        return units == 0 ? 0 : Math.min(100, Math.round(sold * 100.0 / (units * 30)));
    }

    public Map<String, Object> stay(StaffSupport.Ctx c, Long stayId)
    {
        Map<String, Object> s = ownStay(c, stayId);
        s.put("statusLabel", stayStatusLabel(str(s.get("status"))));
        s.put("quals", quals(stayId));
        Map<String, Object> store = db.one("select op_type, store_name from kg_store where store_id = :st", p("st", s.get("storeId")));
        s.put("opType", store == null ? "" : str(store.get("opType")));
        s.put("operateModeLabel", KgStayBrowseService.operateLabel(store == null ? "" : str(store.get("opType"))));
        // week_price_fen（V70）：周一到周日 7 天每晚价，房型管理页按它回填；老房型是空的，页面按基础价把 7 天都填上
        List<Map<String,Object>> roomTypes = db.list("select room_type_id, type_name, room_kind, capacity, unit_label, stock, base_price_fen, week_price_fen, half_month_total_fen, month_total_fen, status, cover, video_url, video_cover from kg_stay_room_type "
                + "where stay_id = :s and del_flag = '0' order by sort, room_type_id", p("s", stayId));
        for (Map<String,Object> room : roomTypes) room.put("weekTotalFen", KgRoomWeekPrice.total(room));
        s.put("roomTypes", roomTypes);
        // 2026-10-17 R3：R2 时存进来的区县是 kg_city 的简称（宾），编辑表单里显示全称（宾县），再保存就存成全称
        String district = str(s.get("district")).trim();
        if (!district.isEmpty() && browse != null)
        {
            s.put("district", KgStayBrowseService.fullDistrict(browse.districtMap(str(s.get("city"))), district));
        }
        // 上线中的房源也能直接改的那几项（不用下架重审），页面据此提示
        s.put("directEditable", DIRECT_LABELS);
        return s;
    }

    /**
     * 上线中（或审核中）的房源也能直接改的补充信息（2026-10-17 R3 主人拍板）：所在区县、主理人年龄 / 籍贯 / 擅长、出行参考四段、配套设施、可做饭。
     * 这些是帮住客了解小院的补充说明，改了不影响平台审核过的名称、地址、简介、标签、封面、图集、价格、取消政策；
     * 其余字段照旧要先下架、改完重新提交审核。小院视频、房型视频、生活碎片走 KgStayMediaService，任何状态都能改。
     */
    // 2026-10-18 主人真机：填主理人信息时顺手改了「主理人称呼」，被要求先下架 —— 称呼也算主理人信息，上线中可以直接改
    // 地图位置（经纬度）本来就能在「更多 → 设置地图位置」里随时改，编辑页「地图选点」也一样直接存（地址文字照旧算核心内容）
    static final List<String> DIRECT_EDIT = Arrays.asList("district", "host_name", "host_age", "host_hometown", "host_skills",
            "travel_pros", "travel_cons", "travel_airport", "travel_rail", "facilities", "cookable", "latitude", "longitude");

    /** 要先下架才能改的那些列的中文名（被拦下时告诉员工到底是哪一项） */
    static final Map<String, String> CORE_LABELS = new LinkedHashMap<String, String>();
    static
    {
        String[][] pairs = { { "stay_name", "房源名称" }, { "address", "房源地址" },
                { "host_story", "房源简介" }, { "host_intro", "主理人简介" }, { "tags", "特色标签" }, { "features", "卖点" }, { "area", "商圈" },
                { "area_desc", "位置描述" }, { "cover", "封面" }, { "images", "图片" }, { "slogan", "标语" }, { "slogan_sub", "副标语" },
                { "checkin_time", "入住时间" }, { "checkout_time", "退房时间" }, { "cancel_policy", "取消政策" }, { "room_kind", "房型类别" },
                { "kitchen", "厨房" } };
        for (String[] kv : pairs)
        {
            CORE_LABELS.put(kv[0], kv[1]);
        }
    }

    /** 被拦下的那几项的中文名（去重，按上面的顺序） */
    static String coreChanged(Map<String, Object> diff)
    {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        for (Map.Entry<String, String> e : CORE_LABELS.entrySet())
        {
            if (diff.containsKey(e.getKey()))
            {
                names.add(e.getValue());
            }
        }
        for (String k : diff.keySet())
        {
            if (!DIRECT_EDIT.contains(k) && !CORE_LABELS.containsKey(k))
            {
                names.add(k);
            }
        }
        return String.join("、", names);
    }

    static final String DIRECT_LABELS = "所在区县、地图位置、主理人称呼 / 年龄 / 籍贯 / 擅长、出行参考、配套设施、视频与生活碎片";

    /** 两个值在表单里算不算一样：去掉首尾空白比；标签 / 特色这类逗号列表按拆开后的列表比；坐标按数值比 */
    static boolean same(String col, Object a, Object b)
    {
        String x = str(a).trim();
        String y = str(b).trim();
        if (Arrays.asList("tags", "features", "images").contains(col))
        {
            return KgStayBrowseService.split(x).equals(KgStayBrowseService.split(y));
        }
        if (Arrays.asList("latitude", "longitude").contains(col))
        {
            if (x.isEmpty() || y.isEmpty())
            {
                return x.isEmpty() && y.isEmpty();
            }
            try
            {
                return new java.math.BigDecimal(x).compareTo(new java.math.BigDecimal(y)) == 0;
            }
            catch (NumberFormatException e)
            {
                return false;
            }
        }
        return x.equals(y);
    }

    /** 和库里比真正改了的列（cols 是 snake_case，stay 行是 camelCase） */
    static Map<String, Object> changed(Map<String, Object> cols, Map<String, Object> stay)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : cols.entrySet())
        {
            if (!same(e.getKey(), e.getValue(), stay.get(KgJdbc.toCamel(e.getKey()))))
            {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static final List<String> EDITABLE = Arrays.asList("stay_name", "area", "district", "area_desc", "address", "cover", "images", "tags", "features", "facilities",
            "slogan", "slogan_sub", "host_name", "host_intro", "host_story", "host_age", "host_hometown", "host_skills",
            "travel_pros", "travel_cons", "travel_airport", "travel_rail", "checkin_time", "checkout_time", "cancel_policy", "room_kind", "cookable", "kitchen");

    /** 字段长度上限（和 kg_stay 的列宽一致），没列出的 64 */
    static final Map<String, Integer> MAX_LEN = new LinkedHashMap<String, Integer>();
    /** 2026-10-17 R2 加的单行文字：所在区县、主理人年龄 / 籍贯 / 擅长、出行参考四段。换行和连续空白并成一个空格（页面按一行显示），超长时说清是哪一项 */
    static final Map<String, String> ONE_LINE = new LinkedHashMap<String, String>();
    static
    {
        for (String k : Arrays.asList("host_story", "images"))
        {
            MAX_LEN.put(k, 1000);
        }
        for (String k : Arrays.asList("address", "slogan_sub", "host_skills"))
        {
            MAX_LEN.put(k, 128);
        }
        // 位置描述 kg_stay.area_desc 是 varchar(64)（后台表单也限 64），超过 64 存库会报错
        MAX_LEN.put("area_desc", 64);
        for (String k : Arrays.asList("travel_pros", "travel_cons", "travel_airport", "travel_rail"))
        {
            MAX_LEN.put(k, 255);
        }
        MAX_LEN.put("district", 32);
        MAX_LEN.put("host_hometown", 32);
        MAX_LEN.put("host_age", 16);
        ONE_LINE.put("district", "所在区县");
        ONE_LINE.put("host_age", "主理人年龄");
        ONE_LINE.put("host_hometown", "主理人籍贯");
        ONE_LINE.put("host_skills", "主理人擅长");
        ONE_LINE.put("travel_pros", "出行参考·优点");
        ONE_LINE.put("travel_cons", "出行参考·缺点");
        ONE_LINE.put("travel_airport", "出行参考·机场怎么到");
        ONE_LINE.put("travel_rail", "出行参考·高铁怎么到");
    }

    /**
     * 设施代码（2026-10-17 R2：员工端也能勾设施，选项读字典 kg_stay_facility）：只收小写字母代码（和列表筛选 find_in_set 的口径一致），
     * 去重、按勾选顺序用逗号连起来；写坏的代码丢掉，不让它进库后在列表筛选里永远对不上
     */
    static String facilityCodes(String raw)
    {
        List<String> out = new ArrayList<String>();
        for (String f : raw.split("[,，\\s]+"))
        {
            String v = f.trim();
            if (v.matches("[a-z]{2,16}") && !out.contains(v))
            {
                out.add(v);
            }
        }
        return String.join(",", out);
    }

    /** 员工端编辑表单的选项（2026-10-17 R2）：这座城市的区县（所在区县选择）、设施字典（配套设施勾选）。城市取房源的，新建时取门店的 */
    public Map<String, Object> formOptions(StaffSupport.Ctx c, Long stayId)
    {
        String city;
        if (stayId != null)
        {
            city = str(ownStay(c, stayId).get("city"));
        }
        else
        {
            Map<String, Object> store = db.one("select city from kg_store where store_id = :st", p("st", c.storeId));
            city = store == null ? "" : str(store.get("city"));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("city", city);
        out.put("storeDefaults",db.one("select store_name, address, latitude, longitude, cover from kg_store where store_id=:s and del_flag='0'",p("s",c.storeId)));
        // 2026-10-17 R3：选项给全称（宾县，不是 kg_city 里去了「县」字的「宾」）
        Map<String, String> map = browse == null ? new LinkedHashMap<String, String>() : browse.districtMap(city);
        List<String> districts = new ArrayList<String>(new java.util.LinkedHashSet<String>(map.values()));
        // 本城市房源上已经填过、字典里没有的区县（手填的）也放进选项，免得员工再编辑时选不回原来的值；存的是简称的折成全称，不重复
        for (Map<String, Object> r : db.list("select distinct district from kg_stay where city = :c and district <> '' and del_flag = '0' order by district",
                p("c", browse == null ? city : browse.businessCity(city))))
        {
            String d = KgStayBrowseService.fullDistrict(map, r.get("district"));
            if (!districts.contains(d))
            {
                districts.add(d);
            }
        }
        out.put("districts", districts);
        out.put("facilities", db.list("select dict_value value, dict_label label from sys_dict_data where dict_type = 'kg_stay_facility' and status = '0' "
                + "order by dict_sort, dict_code", p()));
        return out;
    }

    @Autowired(required = false)
    private KgStayBrowseService browse;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.user.KgMemberWallet memberWallet;

    /** 2026-10-17 R3：选的 / 手填的区县是 kg_city 里的简称（宾）时存成全称（宾县）；字典里没有的手填区县原样 */
    private void normalizeDistrict(Map<String, Object> cols, String city)
    {
        String d = str(cols.get("district")).trim();
        if (cols.containsKey("district") && !d.isEmpty() && browse != null)
        {
            cols.put("district", KgStayBrowseService.fullDistrict(browse.districtMap(city), d));
        }
    }

    /** 新建草稿 / 编辑（只允许草稿、驳回、下架状态改内容；上线中的要先下架） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(StaffSupport.Ctx c, Long stayId, Map<String, Object> body)
    {
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        for (String col : EDITABLE)
        {
            String key = KgJdbc.toCamel(col);
            if (body.containsKey(key))
            {
                String v = str(body.get(key)).trim();
                if (ONE_LINE.containsKey(col))
                {
                    v = v.replaceAll("\\s+", " ");
                }
                if ("facilities".equals(col))
                {
                    v = facilityCodes(v);
                }
                int max = MAX_LEN.containsKey(col) ? MAX_LEN.get(col) : 64;
                if (v.length() > max)
                {
                    throw new ServiceException(ONE_LINE.containsKey(col) ? ONE_LINE.get(col) + "最多 " + max + " 个字" : "字段过长：" + key);
                }
                cols.put(col, v);
            }
        }
        for (String key : Arrays.asList("latitude", "longitude"))
            if (body.containsKey(key)) cols.put(key, body.get(key));
        StayLocation.normalize(cols);
        KgStayModule.checkTimes(cols);
        if (cols.containsKey("cancel_policy"))
        {
            cols.put("cancel_policy", StayPolicy.normalize(str(cols.get("cancel_policy"))));
        }
        if (stayId == null)
        {
            String name = str(cols.get("stay_name"));
            if (name.isEmpty())
            {
                throw new ServiceException("请填写房源名称");
            }
            Map<String, Object> storeCity = db.one("select city from kg_store where store_id = :st", p("st", c.storeId));
            normalizeDistrict(cols, storeCity == null ? "" : str(storeCity.get("city")));
            if (db.count("select count(*) from kg_stay where store_id = :st and stay_name = :n and del_flag = '0'", p("st", c.storeId, "n", name)) > 0)
            {
                throw new ServiceException("本店已有同名房源");
            }
            Map<String, Object> store = db.one("select city from kg_store where store_id = :st", p("st", c.storeId));
            cols.put("store_id", c.storeId);
            cols.put("city", store == null ? "" : store.get("city"));
            cols.put("status", "draft");
            cols.put("create_by", "app:" + c.uid());
            cols.put("create_time", new Date());
            long id = db.insert("kg_stay", cols);
            support.op(c, "stay_create", "新建房源", "general", name, true, null);
            return stay(c, id);
        }
        Map<String, Object> s = ownStay(c, stayId);
        normalizeDistrict(cols, str(s.get("city")));
        // 2026-10-17 R3（主人拍板）：只改了区县、主理人信息、出行参考、配套设施这类补充信息时，任何状态都直接保存，不下架、不退回草稿；
        // 表单会把没改的字段原样带回来，所以按「和库里比真的变了的列」判断。改到名称、地址、简介等其他内容，照旧要先下架
        Map<String, Object> diff = changed(cols, s);
        if (diff.isEmpty())
        {
            return stay(c, stayId);
        }
        if (DIRECT_EDIT.containsAll(diff.keySet()))
        {
            diff.put("update_by", "app:" + c.uid());
            diff.put("update_time", new Date());
            db.updateRow("kg_stay", "stay_id", stayId, diff);
            support.op(c, "stay_edit_extra", "修改房源补充信息", "general", str(s.get("stayName")), true, null);
            return stay(c, stayId);
        }
        if (!Arrays.asList("draft", "rejected", "offline").contains(str(s.get("status"))))
        {
            throw new ServiceException("房源" + stayStatusLabel(str(s.get("status"))) + "，这次改到了「" + coreChanged(diff) + "」，要先下架、改完重新提交审核；"
                    + DIRECT_LABELS + "可以直接改");
        }
        // Text edits must not retain the point of a different address.
        if (cols.containsKey("address") && !str(cols.get("address")).equals(str(s.get("address"))) && !cols.containsKey("latitude")) {
            cols.put("latitude", null);
            cols.put("longitude", null);
        }
        if (!cols.isEmpty())
        {
            cols.put("update_by", "app:" + c.uid());
            cols.put("update_time", new Date());
            db.updateRow("kg_stay", "stay_id", stayId, cols);
            reopenIfOffline(stayId, str(s.get("status")));
            support.op(c, "stay_edit", "编辑房源", "general", str(s.get("stayName")), true, null);
        }
        return stay(c, stayId);
    }

    /** An authorized employee may correct navigation without changing audited listing content. */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> location(StaffSupport.Ctx c, Long stayId, Map<String, Object> body)
    {
        Map<String, Object> locked = db.must("select * from kg_stay where stay_id = :id and del_flag = '0' for update", p("id", stayId), "房源不存在");
        ownStay(c, stayId);
        String address = str(locked.get("address")).trim();
        if (address.isEmpty() || !address.equals(str(body.get("expectedAddress")).trim()))
            throw new ServiceException("房源地址已变更，请刷新后重新选点", 409);
        for (String key : body.keySet())
            if (!Arrays.asList("expectedAddress", "latitude", "longitude").contains(key))
                throw new ServiceException("此入口只能设置地图位置", 422);
        Map<String, Object> values = p("latitude", body.get("latitude"), "longitude", body.get("longitude"));
        StayLocation.normalize(values);
        if (values.get("latitude") == null) throw new ServiceException("请先选择地图位置", 422);
        values.put("update_by", "app:" + c.uid());
        values.put("update_time", new Date());
        db.updateRow("kg_stay", "stay_id", stayId, values);
        support.op(c, "stay_location", "设置房源地图位置", "general", str(locked.get("stayName")), true, null);
        return stay(c, stayId);
    }

    /**
     * 下架状态的房源被改了内容 / 房型：退回草稿，必须重新提交平台审核才能上线。
     * 原先改完仍是 offline，后台「恢复上线」（offline → online，本意是内容没动时直接恢复）就把没审核过的新内容放上线了。
     */
    private void reopenIfOffline(Long stayId, String status)
    {
        if ("offline".equals(status))
        {
            db.update("update kg_stay set status = 'draft', audit_reason = '内容已修改，需重新提交平台审核', update_time = now() where stay_id = :s and status = 'offline'",
                    p("s", stayId));
        }
    }

    private static final List<String> ROOM_KINDS = Arrays.asList("whole", "private", "shared");

    private static Integer intIn(Object raw, int min, int max, String label)
    {
        String v = str(raw).trim();
        if (v.isEmpty())
        {
            return null;
        }
        try
        {
            int n = new java.math.BigDecimal(v).intValueExact();
            if (n >= min && n <= max)
            {
                return n;
            }
        }
        catch (ArithmeticException | NumberFormatException e)
        {
            // 落到下面统一报错
        }
        throw new ServiceException(label + "应为 " + min + "–" + max + " 的整数");
    }

    /**
     * 员工端新增 / 修改房型。房源须是草稿、驳回或下架状态（上线中的先下架），改完随房源一起送平台审核；
     * 上线期间的临时调价、关房走「房态」日历，不动这里的基础价。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveRoomType(StaffSupport.Ctx c, Long stayId, Long roomTypeId, Map<String, Object> body)
    {
        Map<String, Object> rt = null;
        if (roomTypeId != null)
        {
            rt = db.one("select * from kg_stay_room_type where room_type_id = :r and del_flag = '0' for update", p("r", roomTypeId));
            if (rt == null)
            {
                throw new ServiceException("房型不存在");
            }
            stayId = KgJdbc.asLong(rt.get("stayId"));
        }
        Map<String, Object> s = ownStay(c, stayId);
        if ("merchant".equals(c.role)) merchantAgreements.requireAccepted(c,"stay");
        if (!Arrays.asList("draft", "rejected", "offline").contains(str(s.get("status"))))
        {
            throw new ServiceException("房源" + stayStatusLabel(str(s.get("status"))) + "，请先下架再修改房型；临时调价、关房请用「房态」");
        }
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        if (body.containsKey("typeName"))
        {
            String v = str(body.get("typeName")).trim();
            if (v.isEmpty() || v.length() > 64)
            {
                throw new ServiceException("房型名称为 1–64 字");
            }
            cols.put("type_name", v);
        }
        if (body.containsKey("roomKind"))
        {
            String v = str(body.get("roomKind"));
            if (!ROOM_KINDS.contains(v))
            {
                throw new ServiceException("房型类别不合法");
            }
            cols.put("room_kind", v);
            cols.put("unit_label", "shared".equals(v) ? "床位" : "间");
        }
        Integer capacity = intIn(body.get("capacity"), 1, 20, "可住人数");
        Integer stock = intIn(body.get("stock"), 1, 999, "每晚库存");
        Integer price = intIn(body.get("basePriceFen"), 100, 10000000, "基础价（分）");
        Integer area = intIn(body.get("areaSqm"), 1, 10000, "面积");
        if (capacity != null)
        {
            cols.put("capacity", capacity);
        }
        if (stock != null)
        {
            cols.put("stock", stock);
        }
        // 2026-11-03 主人：「必须7天，填写7天的价格」：传了 weekPriceFen（周一到周日 7 个，分）就存 7 天价，基础价跟着存成其中最低的（「¥X 起」用）；
        // 老版本小程序只传 basePriceFen：表示每天一个价，把 7 天价清掉，免得新填的基础价被旧的 7 天价盖住
        if (body.containsKey("weekTotalFen"))
        {
            if (body.containsKey("weekPriceFen") || body.containsKey("basePriceFen"))
                throw new ServiceException("7天总价与旧版每晚价格不能同时提交，请刷新页面后重试", 422);
            KgRoomWeekPrice.applyTotal(cols, body.get("weekTotalFen"), rt);
            price = cols.containsKey("base_price_fen") ? ((Number) cols.get("base_price_fen")).intValue() : null;
        }
        else if (body.containsKey("weekPriceFen"))
        {
            String week = KgRoomWeekPrice.normalize(body.get("weekPriceFen"));
            cols.put("week_price_fen", week);
            price = (int) KgRoomWeekPrice.min(week);
        }
        else if (price != null)
        {
            cols.put("week_price_fen", null);
        }
        if (price != null)
        {
            cols.put("base_price_fen", price);
        }
        if (body.containsKey("halfMonthTotalFen"))
            cols.put("half_month_total_fen", KgRoomPeriodPrice.checkedTotal(body.get("halfMonthTotalFen"), 15));
        if (body.containsKey("monthTotalFen"))
            cols.put("month_total_fen", KgRoomPeriodPrice.checkedTotal(body.get("monthTotalFen"), 30));
        if (area != null)
        {
            cols.put("area_sqm", area);
        }
        for (String[] f : new String[][] { { "bedDesc", "bed_desc", "64" }, { "summary", "summary", "128" } })
        {
            if (body.containsKey(f[0]))
            {
                String v = str(body.get(f[0])).trim();
                if (v.length() > Integer.parseInt(f[2]))
                {
                    throw new ServiceException("字段过长：" + f[0]);
                }
                cols.put(f[1], v);
            }
        }
        if (body.containsKey("status"))
        {
            cols.put("status", "1".equals(str(body.get("status"))) ? "1" : "0");
        }
        String name = str(cols.containsKey("type_name") ? cols.get("type_name") : (rt == null ? "" : rt.get("typeName")));
        if (rt == null)
        {
            if (!cols.containsKey("type_name") || price == null)
            {
                throw new ServiceException("请填写房型名称与7天总价");
            }
            if (db.count("select count(*) from kg_stay_room_type where stay_id = :s and del_flag = '0'", p("s", stayId)) >= 20)
            {
                throw new ServiceException("一个房源最多 20 个房型");
            }
            cols.put("stay_id", stayId);
            cols.put("create_by", "app:" + c.uid());
            cols.put("create_time", new Date());
            db.insert("kg_stay_room_type", cols);
        }
        else if (!cols.isEmpty())
        {
            if (cols.containsKey("stock")) KgRoomInventory.checkDefault(db, roomTypeId, cols.get("stock"));
            cols.put("update_by", "app:" + c.uid());
            cols.put("update_time", new Date());
            db.updateRow("kg_stay_room_type", "room_type_id", roomTypeId, cols);
        }
        reopenIfOffline(stayId, str(s.get("status")));
        support.op(c, rt == null ? "roomtype_create" : "roomtype_edit", rt == null ? "新增房型" : "修改房型", "general",
                str(s.get("stayName")) + " · " + name, true, null);
        return stay(c, stayId);
    }

    @Transactional(rollbackFor = Exception.class)
    public String submit(StaffSupport.Ctx c, Long stayId)
    {
        Map<String, Object> s = ownStay(c, stayId);
        if ("merchant".equals(c.role)) merchantAgreements.requireAccepted(c,"stay");
        if (!Arrays.asList("draft", "rejected", "offline").contains(str(s.get("status"))))
        {
            throw new ServiceException("当前状态（" + stayStatusLabel(str(s.get("status"))) + "）不能提交审核");
        }
        if (db.count("select count(*) from kg_stay_room_type where stay_id = :s and status = '0' and del_flag = '0'", p("s", stayId)) == 0)
        {
            throw new ServiceException("至少需要一个上架的房型（点房源卡片上的「房型管理」添加）");
        }
        if (str(s.get("cover")).isEmpty() || str(s.get("address")).isEmpty())
        {
            throw new ServiceException("请先补全封面与详细地址");
        }
        for (String[] q : QUALS)
        {
            if (!"1".equals(q[2]))
            {
                continue;
            }
            Map<String, Object> doc = db.one("select expire_date from kg_stay_qualification where stay_id = :s and qual_type = :t and status = '0' "
                    + "order by qual_id desc limit 1", p("s", stayId, "t", q[0]));
            if (doc == null)
            {
                throw new ServiceException("请先上传「" + q[1] + "」");
            }
            LocalDate exp = date(str(doc.get("expireDate")));
            if (exp != null && exp.isBefore(LocalDate.now()))
            {
                throw new ServiceException("「" + q[1] + "」已过有效期，请上传新的");
            }
        }
        db.update("update kg_stay set status = 'pending', audit_reason = null, submit_time = now(), update_by = :u, update_time = now() where stay_id = :s "
                + "and status in ('draft','rejected','offline')", p("s", stayId, "u", "app:" + c.uid()));
        support.op(c, "stay_submit", "房源提交审核", "general", str(s.get("stayName")), true, null);
        return "已提交平台审核";
    }

    @Transactional(rollbackFor = Exception.class)
    public String offline(StaffSupport.Ctx c, Long stayId, String reason)
    {
        Map<String, Object> s = ownStay(c, stayId);
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty())
        {
            throw new ServiceException("请填写下架原因");
        }
        if (db.update("update kg_stay set status = 'offline', audit_reason = :r, update_by = :u, update_time = now() where stay_id = :s and status = 'online'",
                p("s", stayId, "r", StaffSupport.cut(why, 255), "u", "app:" + c.uid())) == 0)
        {
            throw new ServiceException("只有已上线的房源可以下架");
        }
        support.op(c, "stay_offline", "房源下架", "general", s.get("stayName") + "：" + why, true, null);
        return "已下架，已有订单照常履约";
    }

    // ---------- 资质（私有存储，签名链接查看） ----------

    /** 每类取最新一份有效文件；查看链接 5 分钟有效 */
    public List<Map<String, Object>> quals(Long stayId)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String[] q : QUALS)
        {
            Map<String, Object> doc = db.one("select qual_id, file_key, file_name, file_size, expire_date, create_time from kg_stay_qualification "
                    + "where stay_id = :s and qual_type = :t and status = '0' order by qual_id desc limit 1", p("s", stayId, "t", q[0]));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("type", q[0]);
            m.put("title", q[1]);
            m.put("required", "1".equals(q[2]));
            m.put("uploaded", doc != null);
            if (doc != null)
            {
                String key = str(doc.get("fileKey"));
                m.put("qualId", doc.get("qualId"));
                m.put("file", doc.get("fileName"));
                long size = fen(doc.get("fileSize"));
                m.put("size", size >= 1024 * 1024 ? String.format("%.1fMB", size / 1048576.0) : Math.max(1, size / 1024) + "KB");
                m.put("expire", doc.get("expireDate"));
                m.put("isPdf", key.endsWith(".pdf"));
                m.put("url", files.sign(key, 300));
                LocalDate exp = date(str(doc.get("expireDate")));
                m.put("expired", exp != null && exp.isBefore(LocalDate.now()));
            }
            out.add(m);
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> uploadQual(StaffSupport.Ctx c, Long stayId, String type, String expire,
            org.springframework.web.multipart.MultipartFile file)
    {
        Map<String, Object> s = ownStay(c, stayId);
        boolean known = false;
        for (String[] q : QUALS)
        {
            known = known || q[0].equals(type);
        }
        if (!known)
        {
            throw new ServiceException("资质类型不合法");
        }
        LocalDate exp = date(expire);
        if (expire != null && !expire.isEmpty() && exp == null)
        {
            throw new ServiceException("有效期格式应为 yyyy-MM-dd");
        }
        Map<String, Object> saved = "license".equals(type) ? files.saveLicense(file) : files.save(file, "qual");
        db.update("update kg_stay_qualification set status = '1', update_by = :u, update_time = now() where stay_id = :s and qual_type = :t and status = '0'",
                p("s", stayId, "t", type, "u", "app:" + c.uid()));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("stay_id", stayId);
        row.put("store_id", s.get("storeId"));
        row.put("qual_type", type);
        row.put("file_key", saved.get("key"));
        row.put("file_name", saved.get("name"));
        row.put("file_size", saved.get("size"));
        row.put("expire_date", exp == null ? null : exp.format(D));
        row.put("status", "0");
        row.put("create_by", "app:" + c.uid());
        row.put("create_time", new Date());
        db.insert("kg_stay_qualification", row);
        support.op(c, "stay_qual", "上传资质", "general", s.get("stayName") + " · " + type, true, null);
        return quals(stayId);
    }

    /** 只改有效期 */
    public List<Map<String, Object>> setQualExpire(StaffSupport.Ctx c, Long stayId, String type, String expire)
    {
        ownStay(c, stayId);
        LocalDate exp = date(expire);
        if (exp == null)
        {
            throw new ServiceException("有效期格式应为 yyyy-MM-dd");
        }
        if (db.update("update kg_stay_qualification set expire_date = :e, update_by = :u, update_time = now() where stay_id = :s and qual_type = :t and status = '0'",
                p("s", stayId, "t", type, "e", exp.format(D), "u", "app:" + c.uid())) == 0)
        {
            throw new ServiceException("请先上传文件");
        }
        return quals(stayId);
    }

    /** 住客群码（上线中也可改：运营字段，不影响审核内容） */
    public String group(StaffSupport.Ctx c, Long stayId, String name, String qr, String expire)
    {
        ownStay(c, stayId);
        LocalDate exp = date(expire);
        if (expire != null && !expire.isEmpty() && exp == null)
        {
            throw new ServiceException("有效期格式应为 yyyy-MM-dd");
        }
        // 传了新群码却没选有效期（员工端日历点了取消）：微信群码 7 天有效，按今天往后 7 天记，到期前一天才能提醒换码（KgStayGroupQrReminder）
        if (exp == null && qr != null && !qr.trim().isEmpty())
        {
            exp = LocalDate.now().plusDays(7);
        }
        db.update("update kg_stay set group_name = :n, group_qr = :q, group_qr_expire = :e, update_by = :u, update_time = now() where stay_id = :s",
                p("s", stayId, "n", StaffSupport.cut(name == null ? "" : name.trim(), 64), "q", StaffSupport.cut(qr == null ? "" : qr.trim(), 255),
                        "e", exp == null ? null : exp.format(D), "u", "app:" + c.uid()));
        support.op(c, "stay_group", "更新住客群码", "general", String.valueOf(stayId), true, null);
        return "住客群信息已更新";
    }

    // ---------- 房态价格 ----------

    private Map<String, Object> ownRoomType(StaffSupport.Ctx c, Long roomTypeId)
    {
        Map<String, Object> rt = db.one("select t.*, s.store_id from kg_stay_room_type t join kg_stay s on s.stay_id = t.stay_id "
                + "where t.room_type_id = :r and t.del_flag = '0'", p("r", roomTypeId));
        if (rt == null || !c.me.hasStoreAccess(KgJdbc.asLong(rt.get("storeId"))))
        {
            throw new ServiceException("房型不存在或没有权限", HttpStatus.FORBIDDEN);
        }
        return rt;
    }

    public Map<String, Object> calendar(StaffSupport.Ctx c, Long roomTypeId, String month)
    {
        Map<String, Object> rt = ownRoomType(c, roomTypeId);
        LocalDate first;
        try
        {
            first = month == null || month.isEmpty() ? LocalDate.now().withDayOfMonth(1) : LocalDate.parse(month + "-01", D);
        }
        catch (Exception e)
        {
            throw new ServiceException("月份格式应为 yyyy-MM");
        }
        LocalDate end = first.plusMonths(1);
        Map<String, Map<String, Object>> cal = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : db.list("select stay_date, price_fen, stock, occupied, closed from kg_stay_calendar where room_type_id = :r "
                + "and stay_date >= :a and stay_date < :b", p("r", roomTypeId, "a", first.format(D), "b", end.format(D))))
        {
            cal.put(str(r.get("stayDate")), r);
        }
        List<Map<String, Object>> days = new ArrayList<Map<String, Object>>();
        long base = fen(rt.get("basePriceFen"));
        int stock = (int) fen(rt.get("stock"));
        for (LocalDate d = first; d.isBefore(end); d = d.plusDays(1))
        {
            Map<String, Object> r = cal.get(d.format(D));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("date", d.format(D));
            m.put("day", d.getDayOfMonth());
            m.put("priceFen", r == null || r.get("priceFen") == null ? KgRoomWeekPrice.nightly(rt, d) : fen(r.get("priceFen")));
            m.put("stock", r == null || r.get("stock") == null ? stock : fen(r.get("stock")));
            m.put("occupied", r == null ? 0 : fen(r.get("occupied")));
            m.put("closed", r != null && "1".equals(str(r.get("closed"))));
            m.put("past", d.isBefore(LocalDate.now()));
            days.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("roomType", rt);
        out.put("month", first.getYear() + "年" + first.getMonthValue() + "月");
        out.put("ym", first.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        out.put("lead", first.getDayOfWeek().getValue() - 1);
        out.put("days", days);
        return out;
    }

    /** 批量设价 / 关房 / 改库存：{from, to, weekdays[1..7], priceFen?, stock?, closed?}；库存不能低于当晚已占用 */
    @Transactional(rollbackFor = Exception.class)
    public String setCalendar(StaffSupport.Ctx c, Long roomTypeId, Map<String, Object> body)
    {
        Map<String, Object> rt = ownRoomType(c, roomTypeId);
        StaffPerms.require(c.role, "rooms", StaffPerms.EDIT);
        KgRoomInventory.lockRoom(db, roomTypeId);
        LocalDate from = date(str(body.get("from")));
        LocalDate to = date(str(body.get("to")));
        if (from == null || to == null || to.isBefore(from))
        {
            throw new ServiceException("请填写正确的日期范围");
        }
        if (from.isBefore(LocalDate.now()))
        {
            from = LocalDate.now();
        }
        if (ChronoUnit.DAYS.between(from, to) > 180)
        {
            throw new ServiceException("一次最多设置 180 天");
        }
        List<Integer> weekdays = new ArrayList<Integer>();
        Object w = body.get("weekdays");
        if (w instanceof List)
        {
            for (Object o : (List<?>) w)
            {
                weekdays.add((int) fen(o));
            }
        }
        Long price = KgJdbc.asLong(body.get("priceFen"));
        Long stock = KgJdbc.asLong(body.get("stock"));
        Object closedRaw = body.get("closed");
        if (price != null && (price < 100 || price > 100000000L))
        {
            throw new ServiceException("价格需在 1 元以上");
        }
        if (stock != null && (stock < 0 || stock > 999))
        {
            throw new ServiceException("库存为 0–999");
        }
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
        {
            if (!weekdays.isEmpty() && !weekdays.contains(d.getDayOfWeek().getValue()))
            {
                continue;
            }
            db.update("insert into kg_stay_calendar (room_type_id, stay_id, stay_date, occupied, update_time) values (:r, :s, :d, 0, now()) "
                    + "on duplicate key update cal_id = cal_id", p("r", roomTypeId, "s", rt.get("stayId"), "d", d.format(D)));
            if (stock != null && db.count("select count(*) from kg_stay_calendar where room_type_id = :r and stay_date = :d and occupied > :k for share",
                    p("r", roomTypeId, "d", d.format(D), "k", stock)) > 0)
            {
                throw new ServiceException(KgStayBrowseService.md(d) + " 已占用的房量超过新库存，不能改小到 " + stock);
            }
            StringBuilder set = new StringBuilder("update_time = now()");
            Map<String, Object> ps = p("r", roomTypeId, "d", d.format(D));
            if (price != null)
            {
                set.append(", price_fen = :pr");
                ps.put("pr", price);
            }
            if (stock != null)
            {
                set.append(", stock = :k");
                ps.put("k", stock);
            }
            if (closedRaw != null)
            {
                set.append(", closed = :cl");
                ps.put("cl", Boolean.parseBoolean(str(closedRaw)) || "1".equals(str(closedRaw)) ? "1" : "0");
            }
            n += db.update("update kg_stay_calendar set " + set + " where room_type_id = :r and stay_date = :d", ps);
        }
        support.op(c, "stay_calendar", "房态价格设置", "general", rt.get("typeName") + " " + from + "~" + to + " 共 " + n + " 晚", true, null);
        return "已更新 " + n + " 晚的房态";
    }

    // ---------- 订单（旅居 + 增值服务） ----------

    /** 本店旅居订单：tab = all / today（今日入住）/ upcoming（待入住）/ refund（退款审核中）/ done */
    public Map<String, Object> stayOrders(StaffSupport.Ctx c, String tab, String keyword)
    {
        StringBuilder where = new StringBuilder(" where o.store_id = :st and o.del_flag = '0' and o.status <> 'pending_payment'");
        Map<String, Object> ps = p("st", c.storeId);
        if ("today".equals(tab))
        {
            where.append(" and o.check_in = curdate() and o.status in ('confirmed','checked_in')");
        }
        else if ("upcoming".equals(tab))
        {
            where.append(" and o.status = 'confirmed'");
        }
        else if ("refund".equals(tab))
        {
            where.append(" and o.status in ('refund_pending','refunding')");
        }
        else if ("done".equals(tab))
        {
            where.append(" and o.status in ('checked_in','completed','no_show','cancelled','closed')");
        }
        if (keyword != null && !keyword.trim().isEmpty())
        {
            where.append(" and (o.order_no like :kw or o.guest_name like :kw)");
            ps.put("kw", "%" + keyword.trim() + "%");
        }
        List<Map<String, Object>> rows = db.list("select o.* from kg_stay_order o" + where + " order by o.check_in, o.order_id desc limit 100", ps);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> o : rows)
        {
            Map<String, Object> v = stayOrders.view(o, false);
            v.put("guestName", MaskUtils.name(str(o.get("guestName"))));
            v.remove("verify");
            out.add(v);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rows", out);
        m.put("counts", db.one("select sum(status = 'confirmed') upcoming, sum(check_in = curdate() and status in ('confirmed','checked_in')) today, "
                + "sum(status in ('refund_pending','refunding')) refund from kg_stay_order where store_id = :st and del_flag = '0'", p("st", c.storeId)));
        return m;
    }

    /** 本店服务订单：tab = unused / used / refunded */
    public Map<String, Object> shopOrders(StaffSupport.Ctx c, String tab, String keyword)
    {
        String st = "used".equals(tab) ? "'used'" : ("refunded".equals(tab) ? "'refunded','refund_pending','refunding'" : "'unused'");
        StringBuilder where = new StringBuilder(" where o.store_id = :st and o.del_flag = '0' and o.status in (" + st + ")");
        Map<String, Object> ps = p("st", c.storeId);
        if (keyword != null && !keyword.trim().isEmpty())
        {
            where.append(" and (o.order_no like :kw or o.user_name like :kw or o.service_snapshot like :kw)");
            ps.put("kw", "%" + keyword.trim() + "%");
        }
        List<Map<String, Object>> rows = db.list("select o.* from kg_shop_order o" + where + " order by o.order_id desc limit 100", ps);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> o : rows)
        {
            Map<String, Object> v = shop.view(o, false);
            v.put("userName", MaskUtils.name(str(o.get("userName"))));
            v.put("verifiedBy", o.get("verifiedBy"));
            out.add(v);
        }
        Map<String, Object> counts = db.one("select sum(status = 'unused') unused, sum(status = 'used') used, sum(status in ('refunded','refund_pending','refunding')) refunded "
                + "from kg_shop_order where store_id = :st and del_flag = '0'", p("st", c.storeId));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rows", out);
        Map<String, Object> cnt = new LinkedHashMap<String, Object>();
        cnt.put("unused", fen(counts.get("unused")));
        cnt.put("used", fen(counts.get("used")));
        cnt.put("refunded", fen(counts.get("refunded")));
        m.put("counts", cnt);
        return m;
    }

    private static String md(Object ymd)
    {
        String v = str(ymd);
        return v.length() >= 10 ? v.substring(5, 10) : v;
    }

    private static String mdhm(Object t)
    {
        Date d = StaffSupport.parse(t);
        return d == null ? "—" : StaffSupport.fmt(d, "MM-dd HH:mm");
    }

    private static String refundText(Map<String, Object> o, String status)
    {
        return "refund_pending".equals(status) ? "申请退款：" + mdhm(o.get("refundApplyTime")) : "退款：" + mdhm(o.get("refundTime"));
    }

    /**
     * 员工端「二消订单」：住宿订单和服务订单合成一张列表。tab = unused 待使用 / used 已核销 / refunded 已退款（含待审批、退款中）；
     * biz = stay / shop 只看一类；days = 1 / 7 / 30 按下单日期筛。
     */
    public Map<String, Object> resaleOrders(StaffSupport.Ctx c, String tab, String biz, Integer days, String keyword)
    {
        String t = Arrays.asList("unused", "used", "refunded").contains(tab) ? tab : "unused";
        Map<String, Object> ps = p("st", c.storeId);
        String since = "";
        if (days != null && days > 0 && days <= 366)
        {
            ps.put("since", LocalDate.now().minusDays(days - 1).toString());
            since = " and o.create_time >= :since";
        }
        boolean kw = keyword != null && !keyword.trim().isEmpty();
        if (kw)
        {
            ps.put("kw", "%" + keyword.trim() + "%");
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!"shop".equals(biz))
        {
            String cond = "used".equals(t) ? "o.status in ('checked_in','completed')"
                    : ("refunded".equals(t) ? "(o.status in ('refund_pending','refunding') or (o.status = 'cancelled' and o.refund_fen > 0))" : "o.status = 'confirmed'");
            for (Map<String, Object> o : db.list("select o.*, st.store_name from kg_stay_order o left join kg_store st on st.store_id = o.store_id "
                    + "where o.store_id = :st and o.del_flag = '0' and " + cond + since
                    + (kw ? " and (o.order_no like :kw or o.guest_name like :kw or o.stay_snapshot like :kw)" : "") + " order by o.order_id desc limit 100", ps))
            {
                Map<String, Object> snap = KgStayOrderService.json(o.get("staySnapshot"));
                String st = str(o.get("status"));
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("bizType", "stay");
                m.put("orderNo", o.get("orderNo"));
                m.put("status", st);
                m.put("statusLabel", KgStayOrderService.statusLabel(st, o.get("refundFen"), o.get("payFen")));
                m.put("title", snap.get("stayName") + " · " + snap.get("typeName"));
                m.put("cover", snap.get("cover"));
                m.put("user", MaskUtils.name(str(o.get("guestName"))));
                m.put("dateText", "unused".equals(t) ? "入住：" + md(o.get("checkIn")) + " ～ " + md(o.get("checkOut")) + "（" + o.get("nights") + "晚）"
                        : ("used".equals(t) ? "核销：" + mdhm(o.get("verifyTime")) : refundText(o, st)));
                m.put("storeName", o.get("storeName"));
                m.put("verifiedBy", o.get("verifiedBy"));
                m.put("payFen", o.get("payFen"));
                m.put("refundFen", o.get("refundFen"));
                m.put("refundReason", o.get("refundReason"));
                m.put("sortKey", str(o.get("createTime")));
                out.add(m);
            }
        }
        if (!"stay".equals(biz))
        {
            String cond = "used".equals(t) ? "o.status = 'used'" : ("refunded".equals(t) ? "o.status in ('refund_pending','refunding','refunded')" : "o.status = 'unused'");
            for (Map<String, Object> o : db.list("select o.*, st.store_name from kg_shop_order o left join kg_store st on st.store_id = o.store_id "
                    + "where o.store_id = :st and o.del_flag = '0' and " + cond + since
                    + (kw ? " and (o.order_no like :kw or o.user_name like :kw or o.service_snapshot like :kw)" : "") + " order by o.order_id desc limit 100", ps))
            {
                Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
                String st = str(o.get("status"));
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("bizType", "shop");
                m.put("orderNo", o.get("orderNo"));
                m.put("status", st);
                m.put("statusLabel", KgShopService.statusLabel(st));
                m.put("title", snap.get("serviceName") + " × " + o.get("qty"));
                m.put("cover", snap.get("cover"));
                m.put("user", MaskUtils.name(str(o.get("userName"))));
                m.put("dateText", "unused".equals(t) ? "预约：" + md(o.get("useDate")) + " " + str(o.get("timeSlot"))
                        : ("used".equals(t) ? "核销：" + mdhm(o.get("verifyTime")) : refundText(o, st)));
                m.put("storeName", o.get("storeName"));
                m.put("verifiedBy", o.get("verifiedBy"));
                m.put("payFen", o.get("payFen"));
                m.put("refundFen", o.get("refundFen"));
                m.put("refundReason", o.get("refundReason"));
                m.put("sortKey", str(o.get("createTime")));
                out.add(m);
            }
        }
        out.sort((a, b) -> str(b.get("sortKey")).compareTo(str(a.get("sortKey"))));
        Map<String, Object> cnt = new LinkedHashMap<String, Object>();
        long[] n = new long[3];
        if (!"shop".equals(biz))
        {
            Map<String, Object> r = db.one("select sum(status = 'confirmed') a, sum(status in ('checked_in','completed')) b, "
                    + "sum(status in ('refund_pending','refunding') or (status = 'cancelled' and refund_fen > 0)) c from kg_stay_order "
                    + "where store_id = :st and del_flag = '0'", p("st", c.storeId));
            n[0] += fen(r.get("a"));
            n[1] += fen(r.get("b"));
            n[2] += fen(r.get("c"));
        }
        if (!"stay".equals(biz))
        {
            Map<String, Object> r = db.one("select sum(status = 'unused') a, sum(status = 'used') b, sum(status in ('refund_pending','refunding','refunded')) c "
                    + "from kg_shop_order where store_id = :st and del_flag = '0'", p("st", c.storeId));
            n[0] += fen(r.get("a"));
            n[1] += fen(r.get("b"));
            n[2] += fen(r.get("c"));
        }
        cnt.put("unused", n[0]);
        cnt.put("used", n[1]);
        cnt.put("refunded", n[2]);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", out.size() > 100 ? out.subList(0, 100) : out);
        res.put("counts", cnt);
        res.put("storeName", c.storeName);
        // 退款审核中的单：切换后由平台审，按钮不再写「去审批」
        res.put("platformReview", KgStayOrderService.refundByPlatform());
        return res;
    }

    // ---------- 核销 ----------

    /** 核对：返回订单摘要；码无效 / 不在有效期 / 不是本人有授权的门店，统一回"核销码无效" */
    public Map<String, Object> resolve(StaffSupport.Ctx c, String code)
    {
        String cd = code == null ? "" : code.trim();
        if (!cd.matches("\\d{10}"))
        {
            throw new ServiceException("核销码无效");
        }
        Map<String, Object> o = db.one("select * from kg_stay_order where verify_code = :c and del_flag = '0'", p("c", cd));
        if (o != null && c.me.hasStoreAccess(KgJdbc.asLong(o.get("storeId"))))
        {
            Map<String, Object> snap = KgStayOrderService.json(o.get("staySnapshot"));
            LocalDate in = date(str(o.get("checkIn")));
            LocalDate out = date(str(o.get("checkOut")));
            LocalDate today = LocalDate.now();
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("bizType", "stay");
            m.put("orderNo", o.get("orderNo"));
            m.put("title", snap.get("stayName") + " · " + snap.get("typeName"));
            m.put("cover", snap.get("cover"));
            m.put("userMasked", MaskUtils.name(str(o.get("guestName"))));
            m.put("period", o.get("checkIn") + " ～ " + o.get("checkOut") + "（" + o.get("nights") + "晚）");
            m.put("amountFen", o.get("payFen"));
            m.put("status", o.get("status"));
            m.put("statusLabel", KgStayOrderService.statusLabel(str(o.get("status"))));
            String checkout = str(snap.get("checkoutTime"));
            if (!checkout.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) checkout = "12:00";
            boolean withinStay = in != null && out != null && !today.isBefore(in)
                    && java.time.LocalDateTime.now().isBefore(out.atTime(java.time.LocalTime.parse(checkout)));
            boolean ok = "confirmed".equals(str(o.get("status"))) && withinStay;
            m.put("canVerify", ok);
            m.put("reason", ok ? null : ("confirmed".equals(str(o.get("status"))) ? "不在入住有效期内（" + o.get("checkIn") + " 至 " + o.get("checkOut") + " " + checkout + "）" : "订单" + m.get("statusLabel")));
            return m;
        }
        Map<String, Object> so = db.one("select * from kg_shop_order where verify_code = :c and del_flag = '0'", p("c", cd));
        if (so != null && c.me.hasStoreAccess(KgJdbc.asLong(so.get("storeId"))))
        {
            Map<String, Object> snap = KgStayOrderService.json(so.get("serviceSnapshot"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("bizType", "shop");
            m.put("orderNo", so.get("orderNo"));
            m.put("title", snap.get("serviceName") + " × " + so.get("qty"));
            m.put("cover", snap.get("cover"));
            m.put("userMasked", MaskUtils.name(str(so.get("userName"))));
            m.put("period", so.get("useDate") + " " + so.get("timeSlot"));
            m.put("amountFen", so.get("payFen"));
            m.put("status", so.get("status"));
            m.put("statusLabel", KgShopService.statusLabel(str(so.get("status"))));
            boolean parentActive = db.count("select count(*) from kg_stay_order where order_no=:n and status in ('confirmed','checked_in') and del_flag='0'", p("n", so.get("stayOrderNo"))) == 1;
            boolean ok = "unused".equals(str(so.get("status"))) && parentActive;
            m.put("canVerify", ok);
            m.put("reason", ok ? null : (!parentActive ? "关联入住订单已失效，请处理退款" : "订单" + m.get("statusLabel")));
            return m;
        }
        throw new ServiceException("核销码无效");
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(StaffSupport.Ctx c, String code)
    {
        Map<String, Object> child = db.one("select stay_order_no from kg_shop_order where verify_code=:c and del_flag='0'", p("c", code == null ? "" : code.trim()));
        if (child != null)
        {
            Map<String, Object> parent = db.one("select status,del_flag from kg_stay_order where order_no=:n for share", p("n", child.get("stayOrderNo")));
            if (parent == null || !"0".equals(str(parent.get("delFlag"))) || !Arrays.asList("confirmed","checked_in").contains(str(parent.get("status"))))
                throw new ServiceException("关联入住订单已失效，不能核销服务", 409);
        }
        Map<String, Object> m = resolve(c, code);
        if (!Boolean.TRUE.equals(m.get("canVerify")))
        {
            throw new ServiceException("不能核销：" + m.get("reason"));
        }
        String by = c.name() + "（" + c.uid() + "）";
        int n;
        if ("stay".equals(m.get("bizType")))
        {
            n = db.update("update kg_stay_order set status = 'checked_in', verify_time = now(), verified_by = :b, update_time = now() "
                    + "where verify_code = :c and status = 'confirmed'", p("c", code.trim(), "b", StaffSupport.cut(by, 64)));
        }
        else
        {
            n = db.update("update kg_shop_order set status = 'used', verify_time = now(), verified_by = :b, update_time = now() "
                    + "where verify_code = :c and status = 'unused'", p("c", code.trim(), "b", StaffSupport.cut(by, 64)));
        }
        if (n == 0)
        {
            throw new ServiceException("订单状态刚刚变化，请重新核对");
        }
        // 扫核销码入住和前台「办理入住」效果要一样：会员免费间夜券从「已占用」记成「已核销」（KgStayPmsService.checkIn 同一处理）
        if ("stay".equals(m.get("bizType")) && memberWallet != null)
        {
            Map<String, Object> so = db.one("select order_id from kg_stay_order where verify_code = :c", p("c", code.trim()));
            if (so != null)
            {
                memberWallet.consumeStay(KgJdbc.asLong(so.get("orderId")));
            }
        }
        // 同样告诉客人已办理入住（前台办理那条路也发）
        if ("stay".equals(m.get("bizType")))
        {
            Map<String, Object> so = db.one("select * from kg_stay_order where verify_code = :c and status = 'checked_in'", p("c", code.trim()));
            if (so != null)
            {
                stayOrders.notifyCheckedIn(so);
            }
        }
        support.op(c, "verify", "到店核销", "general", m.get("title") + " " + m.get("orderNo"), true, null);
        m.put("status", "stay".equals(m.get("bizType")) ? "checked_in" : "used");
        m.put("statusLabel", "stay".equals(m.get("bizType")) ? "已入住" : "已核销");
        m.put("canVerify", false);
        m.put("verifiedBy", by);
        return m;
    }

    // ---------- 退款审批 ----------

    public Map<String, Object> refunds(StaffSupport.Ctx c)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> o : db.list("select * from kg_stay_order where store_id = :st and status = 'refund_pending' and del_flag = '0' "
                + "order by refund_apply_time", p("st", c.storeId)))
        {
            Map<String, Object> snap = KgStayOrderService.json(o.get("staySnapshot"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("bizType", "stay");
            m.put("orderNo", o.get("orderNo"));
            m.put("title", snap.get("stayName") + " · " + snap.get("typeName"));
            m.put("user", MaskUtils.name(str(o.get("guestName"))));
            m.put("paidFen", o.get("payFen"));
            m.put("refundFen", o.get("refundFen"));
            m.put("reason", o.get("refundReason"));
            m.put("applyTime", o.get("refundApplyTime"));
            rows.add(m);
        }
        for (Map<String, Object> o : db.list("select * from kg_shop_order where store_id = :st and status = 'refund_pending' and del_flag = '0' "
                + "order by refund_apply_time", p("st", c.storeId)))
        {
            Map<String, Object> snap = KgStayOrderService.json(o.get("serviceSnapshot"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("bizType", "shop");
            m.put("orderNo", o.get("orderNo"));
            m.put("title", snap.get("serviceName") + " × " + o.get("qty"));
            m.put("user", MaskUtils.name(str(o.get("userName"))));
            m.put("paidFen", o.get("payFen"));
            m.put("refundFen", o.get("refundFen"));
            m.put("reason", o.get("refundReason"));
            m.put("applyTime", o.get("refundApplyTime"));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        // 角色切换后这些退款由平台在后台审：页面只显示「平台审核中」，不给同意 / 拒绝
        out.put("platformReview", KgStayOrderService.refundByPlatform());
        return out;
    }

    private void ownOrder(StaffSupport.Ctx c, String bizType, String orderNo)
    {
        Map<String, Object> o = db.one("select store_id from " + ("shop".equals(bizType) ? "kg_shop_order" : "kg_stay_order") + " where order_no = :n",
                p("n", orderNo));
        if (o == null || !c.me.hasStoreAccess(KgJdbc.asLong(o.get("storeId"))))
        {
            throw new ServiceException("订单不存在或没有权限", HttpStatus.FORBIDDEN);
        }
    }

    public String approve(StaffSupport.Ctx c, String bizType, String orderNo)
    {
        ownOrder(c, bizType, orderNo);
        String by = "staff:" + c.uid();
        String msg = "shop".equals(bizType) ? shop.approveRefund(orderNo, by, c.uid()) : stayOrders.approveRefund(orderNo, by, c.uid());
        support.op(c, "refund_approve", "同意退款", "fund", orderNo, true, null);
        return msg;
    }

    public String reject(StaffSupport.Ctx c, String bizType, String orderNo, String note)
    {
        ownOrder(c, bizType, orderNo);
        String by = "staff:" + c.uid();
        String msg = "shop".equals(bizType) ? shop.rejectRefund(orderNo, note, by) : stayOrders.rejectRefund(orderNo, note, by);
        support.op(c, "refund_reject", "驳回退款", "fund", orderNo + "：" + note, true, null);
        return msg;
    }
}
