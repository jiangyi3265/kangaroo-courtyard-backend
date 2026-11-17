package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/**
 * 旅居浏览与报价（游客可用）。价格与可订判断只在服务端算：列表起价、房型余量、房态日历、低价日推荐、下单报价
 * 全部走同一套"逐晚价 = 早鸟价 / 当晚设定价 / 房型基础价"的规则，下单时再算一遍，不接受客户端传价。
 */
@Service
public class KgStayBrowseService
{
    static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final int MIN_NIGHTS = 7;
    static final int MAX_NIGHTS = 30;
    static final int BOOK_AHEAD_DAYS = 180;
    /** 公开房源统一可见性条件；房源表须使用别名 s，推荐和计数也必须遵守。 */
    public static final String ONLINE = "s.status = 'online' and s.del_flag = '0' and exists (select 1 from kg_store st where st.store_id = s.store_id and st.status = '0' and st.del_flag = '0') and exists (select 1 from kg_stay_room_type rt where rt.stay_id=s.stay_id and rt.status='0' and rt.del_flag='0')";

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgCityService cities;

    /** 小院视频、生活碎片（2026-10-17 R3）；不连库的单测里可以没有 */
    @Autowired(required = false)
    private KgStayMediaService media;

    @Autowired(required = false)
    private KgReviewAiService reviewAi;

    // ---------- 通用 ----------

    static LocalDate date(String s)
    {
        try
        {
            return s == null || s.isEmpty() ? null : LocalDate.parse(s.trim(), D);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static String str(Object o)
    {
        return o == null ? "" : String.valueOf(o);
    }

    static long fen(Object o)
    {
        Long v = KgJdbc.asLong(o);
        return v == null ? 0L : v;
    }

    static List<String> split(Object o)
    {
        List<String> out = new ArrayList<String>();
        for (String s : str(o).split("[,，]"))
        {
            if (!s.trim().isEmpty())
            {
                out.add(s.trim());
            }
        }
        return out;
    }

    static String weekday(LocalDate d)
    {
        return new String[] { "周一", "周二", "周三", "周四", "周五", "周六", "周日" }[d.getDayOfWeek().getValue() - 1];
    }

    static String md(LocalDate d)
    {
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    static String yuan(long fen)
    {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.2f", fen / 100.0).replaceAll("0$", "");
    }

    static String operateLabel(String opType)
    {
        // 2026-11-03 主人：「来源类型，分成直营，加盟，代运营，合作」：新增 partner 合作（V70 字典）；和代运营、加盟一样算非直营（列表「袋鼠合作」那一类、活动不在线代收）
        if ("partner".equals(opType))
        {
            return "合作";
        }
        return "managed".equals(opType) ? "代运营" : ("franchise".equals(opType) ? "加盟" : "直营");
    }

    /** 新预订一周起订：checkIn ≥ 今天、≤ 180 天，7–30 晚。历史订单不走此校验。 */
    static void checkDates(LocalDate in, LocalDate out)
    {
        if (in == null || out == null)
        {
            throw new ServiceException("请选择入住和离店日期");
        }
        LocalDate today = LocalDate.now();
        if (in.isBefore(today))
        {
            throw new ServiceException("入住日期不能早于今天");
        }
        if (in.isAfter(today.plusDays(BOOK_AHEAD_DAYS)))
        {
            throw new ServiceException("最多提前 " + BOOK_AHEAD_DAYS + " 天预订");
        }
        long n = ChronoUnit.DAYS.between(in, out);
        if (n < MIN_NIGHTS)
        {
            throw new ServiceException("一周起订，请至少选择 " + MIN_NIGHTS + " 晚");
        }
        if (n > MAX_NIGHTS)
        {
            throw new ServiceException("最多连住 " + MAX_NIGHTS + " 晚");
        }
    }

    // ---------- 城市、商圈 ----------

    public List<Map<String, Object>> cities()
    {
        return db.list("select s.city, count(*) count from kg_stay s where " + ONLINE + " group by s.city order by count desc, s.city", p());
    }

    public List<Map<String, Object>> areas(String city)
    {
        city = cities.businessCity(city);
        List<Map<String, Object>> rows = db.list("select s.area, count(*) count, "
                + "(select s2.cover from kg_stay s2 where s2.city = s.city and s2.area = s.area and " + ONLINE.replace("s.", "s2.")
                + " order by s2.rating desc, s2.sort limit 1) cover "
                + "from kg_stay s where " + ONLINE + " and s.city = :c and s.area <> '' group by s.city, s.area order by count desc limit 12",
                p("c", city));
        return rows;
    }

    /** 城市写法 → 房源上存的城市名（思明区 → 厦门、平潭岛 → 平潭），员工端表单选项用 */
    public String businessCity(String city)
    {
        return cities.businessCity(city);
    }

    /**
     * 这座城市的区县名（2026-10-17 R2，列表「位置」下拉、员工端「所在区县」选择用）：kg_city 里 level = 'district'、
     * 上级是这座城市全称的行（哈尔滨 → 哈尔滨市 → 道里区、道外区…），按字典顺序。大理、平潭这类目的地本身就是县级，字典里没有下级，返回空。
     * 2026-10-17 R3：返回全称（kg_city.full_name）。V25 补的县名 name 有的去掉了「县」字（宾县 → 宾、玉龙纳西族自治县 → 玉龙纳西族自治），
     * 同名的区还带了「·城市」后缀（朝阳区·北京），页面上显示得很怪；full_name 才是正经的全称，没填时退回 name
     */
    public List<String> districtNames(String city)
    {
        return new ArrayList<String>(new java.util.LinkedHashSet<String>(districtMap(city).values()));
    }

    /**
     * 区县字典（2026-10-17 R3）：这座城市每个区县的 简称（kg_city.name）和 全称（full_name）→ 全称，按字典顺序。
     * R2 上线后员工端存进 kg_stay.district 的是简称（宾），R3 起存全称（宾县）：显示、计数、筛选都先过一遍这张表，两种写法当成同一个区县
     */
    public Map<String, String> districtMap(String city)
    {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String c = cities.businessCity(city);
        if (c == null || c.isEmpty())
        {
            return out;
        }
        for (Map<String, Object> r : db.list("select d.name, d.full_name from kg_city d where d.level = 'district' and d.status = '0' and d.parent_name in "
                + "(select x.full_name from kg_city x where x.name = :c and x.level <> 'district') order by d.city_id", p("c", c)))
        {
            String n = str(r.get("name")).trim();
            String f = str(r.get("fullName")).trim();
            if (f.isEmpty())
            {
                f = n;
            }
            if (f.isEmpty())
            {
                continue;
            }
            if (!n.isEmpty() && !out.containsKey(n))
            {
                out.put(n, f);
            }
            if (!out.containsKey(f))
            {
                out.put(f, f);
            }
        }
        return out;
    }

    /** 存的区县 → 显示用的全称（字典里没有的手填区县原样） */
    public static String fullDistrict(Map<String, String> map, Object stored)
    {
        String v = str(stored).trim();
        return map == null || !map.containsKey(v) ? v : map.get(v);
    }

    /** 筛选用：和这个区县是同一个的全部写法（全称 + 简称 + 原样），去重 */
    static List<String> districtAliases(Map<String, String> map, String value)
    {
        String v = value == null ? "" : value.trim();
        String full = fullDistrict(map, v);
        List<String> out = new ArrayList<String>();
        out.add(v);
        if (!out.contains(full))
        {
            out.add(full);
        }
        for (Map.Entry<String, String> e : map.entrySet())
        {
            if (e.getValue().equals(full) && !out.contains(e.getKey()))
            {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * 列表「位置」下拉的区县（2026-10-17 R2，效果图 6「哈尔滨 → 道外区…」）：只返回有上线房源的区县和各自的家数，再带一个城市全部家数 all，
     * 免得把一座城市十几个空区县都发下去。排序：家数多的在前，一样多按区县字典顺序；房源上手填的、字典里没有的区县排在字典里的后面。
     * 返回 {city, all, districts: [{name, count}]}
     */
    public Map<String, Object> districts(String city)
    {
        String c = cities.businessCity(city);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("city", c == null ? "" : c);
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        if (c == null || c.isEmpty())
        {
            out.put("all", 0L);
            out.put("districts", list);
            return out;
        }
        out.put("all", db.count("select count(*) from kg_stay s where " + ONLINE + " and s.city = :c", p("c", c)));
        Map<String, String> map = districtMap(c);
        final List<String> names = new ArrayList<String>(new java.util.LinkedHashSet<String>(map.values()));
        // 2026-10-17 R3：存简称（宾）和存全称（宾县）的房源算同一个区县，名字一律给全称；列表按这个名字筛时两种写法都认（districtAliases）
        Map<String, Map<String, Object>> merged = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : db.list("select s.district, count(*) count from kg_stay s where " + ONLINE
                + " and s.city = :c and s.district <> '' group by s.district", p("c", c)))
        {
            String name = fullDistrict(map, r.get("district"));
            Map<String, Object> d = merged.get(name);
            if (d == null)
            {
                d = new LinkedHashMap<String, Object>();
                d.put("name", name);
                d.put("count", 0L);
                merged.put(name, d);
                list.add(d);
            }
            d.put("count", fen(d.get("count")) + fen(r.get("count")));
        }
        list.sort((a, b) -> {
            int byCount = Long.compare(fen(b.get("count")), fen(a.get("count")));
            if (byCount != 0)
            {
                return byCount;
            }
            int ia = names.indexOf(str(a.get("name"))), ib = names.indexOf(str(b.get("name")));
            ia = ia < 0 ? Integer.MAX_VALUE : ia;
            ib = ib < 0 ? Integer.MAX_VALUE : ib;
            return ia != ib ? Integer.compare(ia, ib) : str(a.get("name")).compareTo(str(b.get("name")));
        });
        out.put("districts", list);
        return out;
    }

    // ---------- 列表 ----------

    /** 列表：筛选 + 分页 + 来源计数；日期内可订与起价只对当前页的房源算 */
    public Map<String, Object> list(Map<String, String> q, Long userId)
    {
        StringBuilder where = new StringBuilder(" where " + ONLINE);
        Map<String, Object> ps = new HashMap<String, Object>();
        // 选中的是区县（思明区）时按所属城市（厦门）查：帖子、房源、活动存的都是城市名
        String city = cities.businessCity(q.get("city"));
        if (city != null && !city.isEmpty())
        {
            where.append(" and s.city = :city");
            ps.put("city", city);
        }
        String kw = q.get("keyword");
        if (kw != null && !kw.trim().isEmpty())
        {
            // 区县也能搜（2026-10-17 R2：搜「道外区」带出填了这个区县的房源）
            where.append(" and (s.stay_name like :kw escape '!' or s.area like :kw escape '!' or s.district like :kw escape '!' or s.tags like :kw escape '!' or s.area_desc like :kw escape '!')");
            ps.put("kw", "%" + kw.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (q.get("area") != null && !q.get("area").isEmpty())
        {
            where.append(" and s.area = :area");
            ps.put("area", q.get("area"));
        }
        // 所在区县（2026-10-17 R2，列表「位置」下拉）：和 /app/stay/districts 返回的名字精确匹配。
        // R3 起接口给全称（宾县），R2 时存进房源的是简称（宾）：同一个区县的几种写法都认
        if (q.get("district") != null && !q.get("district").trim().isEmpty())
        {
            where.append(" and s.district in (:districts)");
            ps.put("districts", districtAliases(city == null || city.isEmpty() ? new LinkedHashMap<String, String>() : districtMap(city), q.get("district")));
        }
        where.append(roomTypeClause(q, ps));
        for (String f : split(q.get("facilities")))
        {
            if (f.matches("[a-z]{2,16}"))
            {
                where.append(" and find_in_set('").append(f).append("', s.facilities)");
            }
        }
        if ("1".equals(q.get("cookable")))
        {
            where.append(" and s.cookable = '1'");
        }
        if ("1".equals(q.get("kitchen")))
        {
            where.append(" and s.kitchen = '1'");
        }
        if ("1".equals(q.get("longStay")))
        {
            where.append(" and s.long_stay_nights > 0 and s.long_stay_rate < 100");
        }
        Long scoreMin10 = KgJdbc.asLong(q.get("scoreMin10"));
        if (scoreMin10 != null && scoreMin10 > 0)
        {
            where.append(" and s.rating >= :sm");
            ps.put("sm", scoreMin10 / 10.0);
        }
        Long distMax = KgJdbc.asLong(q.get("distMax"));
        if (distMax != null && distMax > 0)
        {
            where.append(" and s.scenic_distance_m is not null and s.scenic_distance_m <= :dm");
            ps.put("dm", distMax);
        }
        Long pMin = KgJdbc.asLong(q.get("priceMinFen"));
        Long pMax = KgJdbc.asLong(q.get("priceMaxFen"));
        if (pMin != null || pMax != null)
        {
            where.append(" and exists (select 1 from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0'");
            if (pMin != null)
            {
                where.append(" and (" + com.ruoyi.kangaroo.ext.stay.KgSettlementMath.saleSql("t.base_price_fen", "st.op_type") + ") >= :pmin");
                ps.put("pmin", pMin);
            }
            if (pMax != null)
            {
                where.append(" and (" + com.ruoyi.kangaroo.ext.stay.KgSettlementMath.saleSql("t.base_price_fen", "st.op_type") + ") <= :pmax");
                ps.put("pmax", pMax);
            }
            where.append(")");
        }
        String base = " from kg_stay s left join kg_store st on st.store_id = s.store_id" + where;
        Map<String, Object> counts = db.one("select count(*) all_cnt, sum(coalesce(st.op_type, 'direct') = 'direct') direct_cnt, "
                + "sum(coalesce(st.op_type, 'direct') <> 'direct') managed_cnt" + base, ps);
        String mode = q.get("operateMode");
        StringBuilder where2 = new StringBuilder(base);
        if ("direct".equals(mode))
        {
            where2.append(" and coalesce(st.op_type, 'direct') = 'direct'");
        }
        else if ("managed".equals(mode))
        {
            where2.append(" and coalesce(st.op_type, 'direct') <> 'direct'");
        }
        String sort = q.get("sort");
        String order = "price_asc".equals(sort) ? "min_price asc" : ("price_desc".equals(sort) ? "min_price desc"
                : ("rating".equals(sort) ? "s.rating desc, s.review_count desc" : "s.sort, s.stay_id"));
        int pageNum = Math.max(1, KgJdbc.asLong(q.get("pageNum")) == null ? 1 : KgJdbc.asLong(q.get("pageNum")).intValue());
        int pageSize = Math.min(50, Math.max(1, KgJdbc.asLong(q.get("pageSize")) == null ? 10 : KgJdbc.asLong(q.get("pageSize")).intValue()));
        // s.city：旅居首页「全国」推荐卡片要显示城市名（list.vue 读 h.city）
        Map<String, Object> page = db.page("select s.stay_id, s.stay_name, s.cover, s.tags, s.district, s.area_desc, s.city, s.rating, s.review_count, s.store_id, s.long_stay_nights, s.long_stay_rate, "
                + "coalesce(st.op_type, 'direct') op_type, st.safe_cert, "
                + "(select min(" + com.ruoyi.kangaroo.ext.stay.KgSettlementMath.saleSql("t.base_price_fen", "st.op_type") + ") from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0') min_price"
                + where2 + " order by " + order, ps, pageNum, pageSize);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        LocalDate in = date(q.get("checkIn"));
        LocalDate out = date(q.get("checkOut"));
        int guests = Math.max(1, KgJdbc.asLong(q.get("guests")) == null ? 1 : KgJdbc.asLong(q.get("guests")).intValue());
        boolean dated = in != null && out != null && out.isAfter(in) && ChronoUnit.DAYS.between(in, out) <= MAX_NIGHTS;
        List<Long> favs = favorites(userId, rows);
        for (Map<String, Object> r : rows)
        {
            Long stayId = KgJdbc.asLong(r.get("stayId"));
            decorate(r);
            long from = fen(r.get("minPrice"));
            long display = Long.MAX_VALUE;
            long availableDisplay = Long.MAX_VALUE;
            boolean available = true;
            // 都订不了时按原因写：之前一律写「满房」，选 3 人时一间最多住 2 人的房源明明有空房也显示满房
            boolean overCapacity = false;
            boolean shortage = false;
            if (dated)
            {
                long best = Long.MAX_VALUE;
                available = false;
                for (Map<String, Object> rt : roomTypeRows(stayId))
                {
                    Map<String, Object> av = availability(rt, in, out, guests, null);
                    display = Math.min(display,fen(av.get("roomFen")));
                    if(Boolean.TRUE.equals(av.get("bookable"))) availableDisplay = Math.min(availableDisplay,fen(av.get("roomFen")));
                    if (Boolean.TRUE.equals(av.get("bookable")))
                    {
                        available = true;
                    }
                    overCapacity = overCapacity || "capacity".equals(av.get("reasonCode"));
                    shortage = shortage || "remain".equals(av.get("reasonCode"));
                    best = Math.min(best, fen(av.get("minNightFen")));
                }
                if (best != Long.MAX_VALUE)
                {
                    from = best;
                }
            }
            if(!dated) for(Map<String,Object> rt:roomTypeRows(stayId)) display=Math.min(display,KgRoomWeekPrice.saleWeek(rt));
            if(availableDisplay!=Long.MAX_VALUE) display=availableDisplay;
            r.put("periodPrices", periodPrices(roomTypeRows(stayId), in == null ? LocalDate.now() : in, guests, null, r));
            r.put("displayPriceFen",display==Long.MAX_VALUE?0:display);
            r.put("displayPriceLabel",dated?ChronoUnit.DAYS.between(in,out)+"晚房费起":"7晚参考价起");
            r.put("priceFromFen", from);
            r.put("available", available);
            r.put("availText", available ? "可订" : (overCapacity ? "超出可住人数" : (shortage ? "余量不足" : "满房")));
            r.put("dateText", dated ? md(in) + "–" + md(out) : "");
            r.put("favorite", favs.contains(stayId));
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", rows);
        res.put("total", page.get("total"));
        res.put("hasMore", page.get("hasMore"));
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("all", KgJdbc.asLong(counts.get("allCnt")));
        c.put("direct", fen(counts.get("directCnt")));
        c.put("managed", fen(counts.get("managedCnt")));
        res.put("counts", c);
        return res;
    }

    /** 独立卫浴的写法（房型亮点 / 名称 / 一句话描述里任一出现即算） */
    static final List<String> BATH_PRIVATE_WORDS = Arrays.asList("独立卫浴", "独卫", "独立卫生间", "独立洗手间");
    /** 公共卫浴的写法 */
    static final List<String> BATH_SHARED_WORDS = Arrays.asList("公共卫浴", "公卫", "公共卫生间", "公共洗手间", "共用卫浴");
    static final List<String> FEMALE_WORDS = Arrays.asList("女生", "女性", "女士", "女宿");
    static final List<String> MALE_WORDS = Arrays.asList("男生", "男性", "男士", "男宿");

    /**
     * 房型条件（2026-10-11）：房型类别 roomKind（whole 整院 / private 独立房间 / shared 多人间）、
     * 房间大小 roomSize（single 单人间 / double 双人间 = 独立房间且可住 1 / 2 人；dorm_female / dorm_male 女生 / 男生多人间）、
     * 卫浴 bath（private 独立卫浴 / shared 公共卫浴）。
     * 之前只能按「整院 / 独立房间 / 多人间」筛，独卫、公卫只有房源级的「独立卫浴」设施，一家店有独卫大床房也有公卫床位时分不出来；
     * 现在几个条件落在同一个房型上（选「单人间 + 独立卫浴」= 这家店至少有一个独卫的单人间）。
     * 卫浴、男女没有单独的字段，按房型亮点 / 名称 / 描述里的写法认（「独立卫浴」「公卫」「女生四人间」）；
     * 整院、房源设施勾了「独立卫浴」的独立房间也算独立卫浴。
     * 不认识的值不加条件（和原来 roomKind 的处理一致）
     */
    static String roomTypeClause(Map<String, String> q, Map<String, Object> ps)
    {
        StringBuilder c = new StringBuilder();
        String kind = q.get("roomKind");
        if ("whole".equals(kind) || "private".equals(kind) || "shared".equals(kind))
        {
            c.append(" and t.room_kind = :rk");
            ps.put("rk", kind);
        }
        String size = q.get("roomSize");
        if ("single".equals(size) || "double".equals(size))
        {
            c.append(" and t.room_kind = 'private' and t.capacity = ").append("single".equals(size) ? 1 : 2);
        }
        else if ("dorm_female".equals(size) || "dorm_male".equals(size))
        {
            c.append(" and t.room_kind = 'shared' and ").append(wordsLike("dorm_female".equals(size) ? FEMALE_WORDS : MALE_WORDS, "g", ps))
                    .append(" and concat_ws(',', t.type_name, t.summary, t.features) not like '%混住%'");
        }
        String bath = q.get("bath");
        if ("private".equals(bath))
        {
            // 整院、以及房源设施勾了「独立卫浴」的独立房间也算（老房源只在房源级勾过设施，房型亮点里没写）
            c.append(" and (t.room_kind = 'whole' or (t.room_kind = 'private' and find_in_set('bath', s.facilities)) or ")
                    .append(wordsLike(BATH_PRIVATE_WORDS, "b", ps)).append(")");
        }
        else if ("shared".equals(bath))
        {
            c.append(" and ").append(wordsLike(BATH_SHARED_WORDS, "b", ps));
        }
        if (c.length() == 0)
        {
            return "";
        }
        return " and exists (select 1 from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0'" + c + ")";
    }

    /** (名称 / 描述 / 亮点 like 词1 or … )，词走参数绑定 */
    private static String wordsLike(List<String> words, String prefix, Map<String, Object> ps)
    {
        StringBuilder w = new StringBuilder("(");
        for (int i = 0; i < words.size(); i++)
        {
            String name = prefix + "w" + i;
            w.append(i == 0 ? "" : " or ").append("concat_ws(',', t.type_name, t.summary, t.features) like :").append(name);
            ps.put(name, "%" + words.get(i) + "%");
        }
        return w.append(")").toString();
    }

    private List<Long> favorites(Long userId, List<Map<String, Object>> rows)
    {
        List<Long> out = new ArrayList<Long>();
        if (userId == null || rows.isEmpty())
        {
            return out;
        }
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> r : rows)
        {
            ids.add(KgJdbc.asLong(r.get("stayId")));
        }
        for (Map<String, Object> f : db.list("select target_id from kg_favorite where target_type = 'stay' and app_user_id = :u and target_id in (:ids)",
                p("u", userId, "ids", ids)))
        {
            out.add(KgJdbc.asLong(f.get("targetId")));
        }
        return out;
    }

    /** 列表 / 详情共用的展示字段 */
    static void decorate(Map<String, Object> r)
    {
        r.put("operateMode", str(r.get("opType")).isEmpty() ? "direct" : r.get("opType"));
        r.put("operateModeLabel", operateLabel(str(r.get("opType"))));
        r.put("safeCert", "0".equals(str(r.get("safeCert"))));
        r.put("tags", split(r.get("tags")));
        Object rating = r.get("rating");
        int reviews = (int) fen(r.get("reviewCount"));
        r.put("ratingText", rating == null || reviews == 0 ? "暂无评价" : rating + "分 · " + reviews + "条评价");
    }

    List<Map<String, Object>> roomTypeRows(Long stayId)
    {
        return db.list("select t.*, coalesce(st.op_type, 'direct') op_type from kg_stay_room_type t join kg_stay s on s.stay_id=t.stay_id left join kg_store st on st.store_id=s.store_id where t.stay_id = :s and t.status = '0' and t.del_flag = '0' order by t.sort, t.room_type_id",
                p("s", stayId));
    }

    /** 早鸟：启用、在售期内、未售罄、房型匹配、入住日期在适用范围内 */
    Map<String, Object> activePromo(Long promoId, Long roomTypeId)
    {
        if (promoId == null)
        {
            return null;
        }
        return db.one("select * from kg_stay_promo where promo_id = :p and room_type_id = :r and status = '0' and del_flag = '0' "
                + "and sale_end > now() and sold < quota", p("p", promoId, "r", roomTypeId));
    }

    /**
     * 某房型在 [in, out) 每晚的价格与余量。返回 {bookable, reason, remain, nightly:[{date, priceFen, promo}], roomFen, minNightFen}
     * promo 不为空且覆盖全部入住日期时整单按早鸟价。
     */
    Map<String, Object> availability(Map<String, Object> rt, LocalDate in, LocalDate out, int guests, Map<String, Object> promo)
    {
        Long rtId = KgJdbc.asLong(rt.get("roomTypeId"));
        long base = fen(rt.get("basePriceFen"));
        int stock = (int) fen(rt.get("stock"));
        Map<String, Map<String, Object>> cal = new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> c : db.list("select stay_date, price_fen, stock, occupied, closed from kg_stay_calendar "
                + "where room_type_id = :r and stay_date >= :in and stay_date < :out", p("r", rtId, "in", in.format(D), "out", out.format(D))))
        {
            cal.put(str(c.get("stayDate")), c);
        }
        boolean promoOk = promo != null;
        if (promoOk)
        {
            LocalDate ps = date(str(promo.get("stayStart")));
            LocalDate pe = date(str(promo.get("stayEnd")));
            promoOk = ps != null && pe != null && !in.isBefore(ps) && !out.minusDays(1).isAfter(pe);
        }
        int stayNights = (int) ChronoUnit.DAYS.between(in, out);
        Long periodTotal = promoOk ? null : KgRoomPeriodPrice.total(rt, stayNights);
        List<Map<String, Object>> nightly = new ArrayList<Map<String, Object>>();
        int remain = Integer.MAX_VALUE;
        long room = 0;
        long merchantBase = 0;
        long minNight = Long.MAX_VALUE;
        String reason = null;
        for (LocalDate d = in; d.isBefore(out); d = d.plusDays(1))
        {
            Map<String, Object> c = cal.get(d.format(D));
            int dayStock = c == null || c.get("stock") == null ? stock : (int) fen(c.get("stock"));
            int left = dayStock - (c == null ? 0 : (int) fen(c.get("occupied")));
            boolean closed = c != null && "1".equals(str(c.get("closed")));
            if (closed)
            {
                left = 0;
                if (reason == null)
                {
                    reason = md(d) + " 不可订";
                }
            }
            else if (left <= 0 && reason == null)
            {
                reason = md(d) + " 已满房";
            }
            remain = Math.min(remain, Math.max(0, left));
            long defaultPrice = periodTotal == null ? KgRoomWeekPrice.nightly(rt, d)
                    : KgRoomPeriodPrice.nightly(periodTotal, stayNights, nightly.size());
            long price = promoOk ? fen(promo.get("priceFen")) : (c == null || c.get("priceFen") == null ? defaultPrice : fen(c.get("priceFen")));
            merchantBase += price;
            price = KgSettlementMath.sale(price, rt.get("opType"));
            room += price;
            minNight = Math.min(minNight, price);
            Map<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("date", d.format(D));
            n.put("priceFen", price);
            n.put("promo", promoOk);
            nightly.add(n);
        }
        int capacity = (int) fen(rt.get("capacity"));
        String unit = str(rt.get("unitLabel"));
        // 床位按人头占：几个人就要几个床位；房间按间，一间可住 capacity 人
        int units = "床位".equals(unit) ? guests : 1;
        // 不可订的原因分类（列表页据此区分「满房」和「超出可住人数」）：date 日期满房 / 关房，capacity 人数超过一间可住人数，remain 余量不够这么多人
        String reasonCode = reason == null ? null : "date";
        if (!"床位".equals(unit) && guests > capacity && reason == null)
        {
            reason = "该房型最多住 " + capacity + " 人";
            reasonCode = "capacity";
        }
        if (remain < units && reason == null)
        {
            reason = "余量不足";
            reasonCode = "remain";
        }
        Map<String, Object> outMap = new LinkedHashMap<String, Object>();
        outMap.put("bookable", reason == null);
        outMap.put("reason", reason);
        outMap.put("reasonCode", reasonCode);
        outMap.put("remain", remain == Integer.MAX_VALUE ? 0 : remain);
        outMap.put("units", units);
        outMap.put("nightly", nightly);
        outMap.put("roomFen", room * units);
        outMap.put("merchantBaseFen", merchantBase * units);
        outMap.put("minNightFen", minNight == Long.MAX_VALUE ? base : minNight);
        outMap.put("promoApplied", promoOk);
        outMap.put("periodPriceApplied", periodTotal != null);
        return outMap;
    }

    // ---------- 详情 ----------

    /** 一对坐标是否可用：两个都有、在范围内、不是 0,0（和前端 stay-location.js 的 coordinates() 同一口径） */
    static boolean usablePoint(Object lat, Object lng)
    {
        try
        {
            if (lat == null || lng == null || String.valueOf(lat).trim().isEmpty() || String.valueOf(lng).trim().isEmpty()) return false;
            double a = Double.parseDouble(String.valueOf(lat).trim()), b = Double.parseDouble(String.valueOf(lng).trim());
            return !Double.isNaN(a) && !Double.isNaN(b) && Math.abs(a) <= 90 && Math.abs(b) <= 180 && !(a == 0 && b == 0);
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    /**
     * 房源详情的地图位置：房源自己没选点时用门店登记的坐标兜底（和「到店路线」KgUserHubService 同一规则，但按一对整体取，
     * 不拼半个房源点半个门店点）。mapFrom = stay / store / ''，前端据此写「到店路线」还是「复制地址」。
     * 10-16 反馈：本地库房源坐标几乎全空，按钮一律变成「复制地址」，有门店坐标也打不开地图。
     */
    static void mapPoint(Map<String, Object> s)
    {
        Object storeLat = s.remove("storeLat");
        Object storeLng = s.remove("storeLng");
        if (usablePoint(s.get("latitude"), s.get("longitude")))
        {
            s.put("mapFrom", "stay");
            return;
        }
        if (usablePoint(storeLat, storeLng))
        {
            s.put("latitude", storeLat);
            s.put("longitude", storeLng);
            s.put("mapFrom", "store");
            return;
        }
        s.put("latitude", null);
        s.put("longitude", null);
        s.put("mapFrom", "");
    }

    public Map<String, Object> detail(Long stayId, Long userId)
    {
        Map<String, Object> s = db.one("select s.*, coalesce(st.op_type, 'direct') op_type, st.safe_cert, st.store_name, st.latitude store_lat, st.longitude store_lng from kg_stay s "
                + "left join kg_store st on st.store_id = s.store_id where s.stay_id = :id and " + ONLINE, p("id", stayId));
        if (s == null)
        {
            throw new ServiceException("房源不存在或已下架");
        }
        mapPoint(s);
        decorate(s);
        s.put("images", split(s.get("images")));
        s.put("features", split(s.get("features")));
        s.put("facilities", split(s.get("facilities")));
        Map<String, Object> host = new LinkedHashMap<String, Object>();
        host.put("name", s.remove("hostName"));
        host.put("avatar", s.remove("hostAvatar"));
        host.put("intro", s.remove("hostIntro"));
        host.put("story", s.remove("hostStory"));
        // 2026-10-17 R2（效果图 7「小院主理人」）：年龄 / 籍贯 / 擅长是员工端、后台手填的文字，没填是 ''，页面只显示填了的
        host.put("age", str(s.remove("hostAge")).trim());
        host.put("hometown", str(s.remove("hostHometown")).trim());
        host.put("skills", str(s.remove("hostSkills")).trim());
        // 主理人评分 = 这间房源评价里「服务」一项的平均分（住宿评价 5 项依次是 卫生 / 设施相符 / 服务 / 安静舒适 / 位置周边，
        // 见 pages/profile/review.vue 的 stay_order）；带分项的评价不足 3 条时没有参考价值，给 null，页面不显示
        List<Object> dims = new ArrayList<Object>();
        for (Map<String, Object> r : db.list("select r.dims from kg_review r where r.target_type = 'room' and r.target_id = :s and r.status = '0' "
                + "and r.del_flag = '0' and r.dims is not null and r.dims <> '' order by r.review_id desc limit 500", p("s", stayId)))
        {
            dims.add(r.get("dims"));
        }
        Object[] score = hostScore(dims);
        host.put("score", score[0]);
        host.put("scoreCount", score[1]);
        host.put("storeId", s.get("storeId"));
        // 关注房东 = 关注这家门店的新岗位上新，落在 kg_job_subscribe 上（见 KgUserHubService.followStore）。
        // 之前带这家门店的任何订阅都算已关注，和「取消关注」删的不是同一种行；现在只认关注行，与岗位详情、「我的关注」同一口径
        host.put("following", Boolean.valueOf(userId != null && db.count("select count(*) from kg_job_subscribe where app_user_id = :u "
                + "and store_id = :s and status = '0' and " + com.ruoyi.kangaroo.ext.user.KgStoreFollowService.followRow(""), p("u", userId, "s", s.get("storeId"))) > 0));
        s.put("host", host);
        // 出行参考（2026-10-17 R2）：员工端 / 后台手填的四段文字（主人拍板不接大模型），页面只显示填了的几行，都没填时整张卡不出现
        Map<String, Object> travel = new LinkedHashMap<String, Object>();
        travel.put("pros", str(s.remove("travelPros")).trim());
        travel.put("cons", str(s.remove("travelCons")).trim());
        travel.put("airport", str(s.remove("travelAirport")).trim());
        travel.put("rail", str(s.remove("travelRail")).trim());
        s.put("travel", travel);
        // 2026-10-17 R3：区县显示全称（R2 时存的是 kg_city 的简称，「宾」显示成「宾县」）
        String district = str(s.get("district")).trim();
        s.put("district", district.isEmpty() ? "" : fullDistrict(districtMap(str(s.get("city"))), district));
        // 2026-10-17 R3（效果图 7「3分钟看小院」「近期生活碎片」）：小院视频 {url, cover}（没传是 null，页面不出这张卡）、
        // 生活碎片摘要 {count, thumbs}（count 0 时页面不出入口）；原始列不往外带
        s.put("introVideo", KgStayMediaService.introVideo(s));
        for (String k : new String[] { "introVideoCover", "diaryBg", "diaryTitle", "diarySubtitle", "diaryIntro" })
        {
            s.remove(k);
        }
        Map<String, Object> diary = media == null ? null : media.diarySummary(stayId);
        if (diary == null)
        {
            diary = new LinkedHashMap<String, Object>();
            diary.put("count", 0L);
            diary.put("thumbs", new ArrayList<String>());
        }
        s.put("diary", diary);
        List<Map<String, Object>> types = new ArrayList<Map<String, Object>>();
        long from = Long.MAX_VALUE;
        long weekFrom = Long.MAX_VALUE;
        Map<Long, long[]> scores = roomTypeScores(stayId);
        for (Map<String, Object> rt : roomTypeRows(stayId))
        {
            Map<String, Object> t = new LinkedHashMap<String, Object>();
            t.put("roomTypeId", rt.get("roomTypeId"));
            t.put("typeName", rt.get("typeName"));
            t.put("summary", rt.get("summary"));
            t.put("cover", rt.get("cover"));
            t.put("priceFromFen", KgSettlementMath.sale(fen(rt.get("basePriceFen")), rt.get("opType")));
            t.put("displayPriceFen",KgRoomWeekPrice.saleWeek(rt));
            t.put("displayPriceLabel","7晚参考价");
            t.put("capacity", rt.get("capacity"));
            t.put("unitLabel", rt.get("unitLabel"));
            KgStayMediaService.roomVideo(rt, t);
            roomScore(t, scores.get(KgJdbc.asLong(rt.get("roomTypeId"))));
            types.add(t);
            weekFrom=Math.min(weekFrom,KgRoomWeekPrice.saleWeek(rt));
            from = Math.min(from, KgSettlementMath.sale(fen(rt.get("basePriceFen")), rt.get("opType")));
        }
        Map<String, Object> ai = reviewAi == null ? null : reviewAi.snapshot(stayId);
        s.put("ai", KgReviewAiService.overall(ai));
        for (Map<String, Object> room : types)
            room.put("ai", KgReviewAiService.room(ai, room.get("roomTypeId"), fen(room.get("reviewCount"))));
        s.put("periodPrices", periodPrices(roomTypeRows(stayId), LocalDate.now(), 1, null, s));
        s.put("houseType", StayBookingChannel.type(s));
        s.put("roomTypes", types);
        s.put("priceFromFen", from == Long.MAX_VALUE ? 0 : from);
        s.put("displayPriceFen",weekFrom==Long.MAX_VALUE?0:weekFrom);
        s.put("displayPriceLabel","7晚参考价起");
        // 在售早鸟（房型还在架的）：promo 是最便宜的一条（老版本只认它），promos 是全部，带上房型和价格。
        // 详情页之前只在日常起价旁边放一个「早鸟价」，真正的早鸟价哪儿都看不到；现在按 promoId / 房型标出早鸟价
        List<Map<String, Object>> promos = db.list("select p.promo_id, p.room_type_id, p.title, p.price_fen from kg_stay_promo p where p.stay_id = :s and p.status = '0' "
                + "and p.del_flag = '0' and p.sale_end > now() and p.sold < p.quota and exists (select 1 from kg_stay_room_type t where t.room_type_id = p.room_type_id "
                + "and t.status = '0' and t.del_flag = '0') order by p.price_fen, p.promo_id limit 10", p("s", stayId));
        for (Map<String, Object> offer : promos) offer.put("priceFen", KgSettlementMath.sale(fen(offer.get("priceFen")), s.get("opType")));
        s.put("promo", promos.isEmpty() ? null : promos.get(0));
        s.put("promos", promos);
        s.put("favorite", userId != null && db.count("select count(*) from kg_favorite where target_type = 'stay' and target_id = :s and app_user_id = :u",
                p("s", stayId, "u", userId)) > 0);
        // 住宿订单评价落库时 target_type 是 'room'（target_id = stay_id），这里原先查 'stay'，永远查不到
        Map<String, Object> agg = db.one("select count(*) n, coalesce(sum(r.rating >= 4), 0) good from kg_review r where r.target_type = 'room' and r.target_id = :s "
                + "and r.status = '0' and r.del_flag = '0'", p("s", stayId));
        long reviewN = agg == null ? 0 : fen(agg.get("n"));
        // 评价太少时好评率没有参考价值（1 条好评就是 100%），不足 5 条不展示
        s.put("goodRate", reviewN >= 5 ? (Object) Math.round(fen(agg.get("good")) * 100.0 / reviewN) : null);
        s.put("reviewLatest", db.one("select r.rating, r.content, r.create_time, "
                + "case when r.anonymous = '0' then u.nickname else null end nickname, case when r.anonymous = '0' then u.avatar else null end avatar from kg_review r "
                + "left join kg_app_user u on u.user_id = r.app_user_id where r.target_type = 'room' and r.target_id = :s "
                + "and r.status = '0' and r.del_flag = '0' order by r.featured desc, r.review_id desc limit 1", p("s", stayId)));
        // 住客群码、门店电话只在订单详情（已支付）里下发
        s.remove("groupQr");
        s.remove("groupQrExpire");
        // 内部字段不给用户端：谁建的 / 谁改的（员工账号 ID）、提交与审核时间、驳回原因、后台备注（详情页都没用到）
        for (String k : new String[] { "createBy", "createTime", "updateBy", "updateTime", "submitTime", "auditTime", "auditReason", "remark", "delFlag" })
        {
            s.remove(k);
        }
        s.put("serverTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return s;
    }

    /**
     * 每个房型自己的评分（2026-10-18 主人：房型卡上「每个房型显示它自己的评分与条数」）。
     * 住宿评价落库时只记到小院（target_type = 'room'、target_id = stay_id），房型取评价对应的住宿订单 kg_stay_order.room_type_id，
     * 和「住过的家人评价」页按房型筛选是同一口径（KgStayReviewService.rows）；只算显示中、未删除的评价。
     * 返回 {房型ID: [评分合计, 条数]}，没有评价的房型不在里面
     */
    Map<Long, long[]> roomTypeScores(Long stayId)
    {
        Map<Long, long[]> out = new HashMap<Long, long[]>();
        for (Map<String, Object> r : db.list("select o.room_type_id, count(*) n, coalesce(sum(v.rating), 0) total from kg_review v "
                + "join kg_stay_order o on v.biz_type = 'stay_order' and o.order_id = v.biz_id and o.stay_id = v.target_id "
                + "where v.target_type = 'room' and v.target_id = :s and v.status = '0' and v.del_flag = '0' group by o.room_type_id", p("s", stayId)))
        {
            Long id = KgJdbc.asLong(r.get("roomTypeId"));
            if (id != null)
            {
                out.put(id, new long[] { fen(r.get("total")), fen(r.get("n")) });
            }
        }
        return out;
    }

    /**
     * 房型卡上的评分：rating 一位小数（和家人评价页综合分同一算法 KgStayReviewService.avg1），reviewCount 条数；
     * 这个房型还没有评价时 rating = null、reviewCount = 0，页面不显示分数（不拿小院总分顶替）
     */
    static void roomScore(Map<String, Object> t, long[] agg)
    {
        long n = agg == null ? 0 : agg[1];
        t.put("rating", n <= 0 ? null : KgStayReviewService.avg1(agg[0], n));
        t.put("reviewCount", Math.max(0, n));
    }

    /** 住宿评价分项里「服务」是第 3 项（下标 2） */
    static final int SERVICE_DIM = 2;
    /** 带分项的评价至少几条才给主理人评分 */
    static final int HOST_SCORE_MIN = 3;

    /**
     * 主理人评分（2026-10-17 R2）：每条评价的 dims 形如 "[5, 4, 5, 4, 5]"，取第 3 项「服务」求平均，保留一位小数。
     * 写坏的、不到 3 项的、不是 1–5 分的跳过；剩下的不足 HOST_SCORE_MIN 条返回 [null, 条数]。返回 [Double 或 null, Long 条数]
     */
    static Object[] hostScore(List<Object> dims)
    {
        long n = 0;
        long sum = 0;
        for (Object o : dims)
        {
            String[] parts = str(o).replaceAll("[\\[\\]\\s\"]", "").split(",");
            if (parts.length <= SERVICE_DIM)
            {
                continue;
            }
            try
            {
                int v = new java.math.BigDecimal(parts[SERVICE_DIM]).intValueExact();
                if (v >= 1 && v <= 5)
                {
                    n++;
                    sum += v;
                }
            }
            catch (ArithmeticException | NumberFormatException e)
            {
                // 写坏的分项不算
            }
        }
        if (n < HOST_SCORE_MIN)
        {
            return new Object[] { null, n };
        }
        return new Object[] { Math.round(sum * 10.0 / n) / 10.0, n };
    }

    Map<String, Object> onlineStay(Long stayId)
    {
        Map<String, Object> s = db.one("select s.*, coalesce(st.op_type, 'direct') op_type, st.safe_cert, st.store_name, st.contact_phone store_phone "
                + "from kg_stay s left join kg_store st on st.store_id = s.store_id where s.stay_id = :id and " + ONLINE, p("id", stayId));
        if (s == null)
        {
            throw new ServiceException("房源不存在或已下架");
        }
        return s;
    }

    Map<String, Object> roomType(Long roomTypeId)
    {
        Map<String, Object> rt = db.one("select t.*, coalesce(st.op_type, 'direct') op_type from kg_stay_room_type t join kg_stay s on s.stay_id=t.stay_id left join kg_store st on st.store_id=s.store_id where t.room_type_id = :r and t.status = '0' and t.del_flag = '0'", p("r", roomTypeId));
        if (rt == null)
        {
            throw new ServiceException("房型不存在或已下架");
        }
        return rt;
    }

    // ---------- 房型、日历、推荐 ----------

    public List<Map<String, Object>> roomTypes(Long stayId, String checkIn, String checkOut, int guests, Long promoId)
    {
        Map<String,Object> stay = onlineStay(stayId);
        LocalDate in = date(checkIn);
        LocalDate out = date(checkOut);
        boolean dated = in != null && out != null && out.isAfter(in) && ChronoUnit.DAYS.between(in, out) <= MAX_NIGHTS;
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        Map<Long, long[]> scores = roomTypeScores(stayId);
        for (Map<String, Object> rt : roomTypeRows(stayId))
        {
            Map<String, Object> t = new LinkedHashMap<String, Object>();
            t.put("roomTypeId", rt.get("roomTypeId"));
            t.put("typeName", rt.get("typeName"));
            t.put("roomKind", rt.get("roomKind"));
            t.put("summary", rt.get("summary"));
            t.put("bedDesc", rt.get("bedDesc"));
            t.put("areaSqm", rt.get("areaSqm"));
            t.put("capacity", rt.get("capacity"));
            t.put("unitLabel", rt.get("unitLabel"));
            t.put("cover", rt.get("cover"));
            // 2026-10-17 R3：房型短视频（≤1分钟）和封面，没传时都是 ''，房型卡不出播放按钮
            KgStayMediaService.roomVideo(rt, t);
            t.put("features", split(rt.get("features")));
            roomScore(t, scores.get(KgJdbc.asLong(rt.get("roomTypeId"))));
            // 早鸟只对它自己的房型有效：标出来，预订页只在选中这个房型时带 promoId（之前选别的房型也带，报价一律失败）
            Map<String, Object> promo = activePromo(promoId, KgJdbc.asLong(rt.get("roomTypeId")));
            t.put("periodPrices", periodPrices(Arrays.asList(rt), in == null ? LocalDate.now() : in, Math.max(1,guests), promo, stay));
            t.put("promo", promo != null);
            if (dated)
            {
                Map<String, Object> av = availability(rt, in, out, Math.max(1, guests), promo);
                t.put("priceFromFen", av.get("minNightFen"));
                t.put("displayPriceFen",av.get("roomFen"));
                long roomFen = fen(av.get("roomFen"));
                t.put("displayPayFen", roomFen - stayDiscount(roomFen, ChronoUnit.DAYS.between(in,out), stay, promo, av));
                t.put("displayPriceLabel",ChronoUnit.DAYS.between(in,out)+"晚房费");
                t.put("remain", av.get("remain"));
                t.put("bookable", av.get("bookable"));
                t.put("reason", av.get("reason"));
                int remain = (int) fen(av.get("remain"));
                t.put("remainText", remain <= 0 ? "满房" : "剩余 " + remain + " " + rt.get("unitLabel"));
            }
            else
            {
                t.put("priceFromFen", KgSettlementMath.sale(fen(rt.get("basePriceFen")), rt.get("opType")));
            t.put("displayPriceFen",KgRoomWeekPrice.saleWeek(rt));
            t.put("displayPriceLabel","7晚参考价");
                t.put("remain", rt.get("stock"));
                t.put("bookable", true);
                t.put("reason", null);
                t.put("remainText", "每晚 " + rt.get("stock") + " " + rt.get("unitLabel"));
            }
            list.add(t);
        }
        Map<String, Object> ai = reviewAi == null ? null : reviewAi.snapshot(stayId);
        for (Map<String, Object> room : list)
            room.put("ai", KgReviewAiService.room(ai, room.get("roomTypeId"), fen(room.get("reviewCount"))));
        return list;
    }

    /** 房态日历：month = yyyy-MM */
    public Map<String, Object> calendar(Long roomTypeId, String month, Long promoId)
    {
        Map<String, Object> rt = roomType(roomTypeId);
        onlineStay(KgJdbc.asLong(rt.get("stayId")));
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
        LocalDate today = LocalDate.now();
        LocalDate last = today.plusDays(BOOK_AHEAD_DAYS);
        Map<String, Object> promo = activePromo(promoId, roomTypeId);
        Map<String, Map<String, Object>> cal = new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> c : db.list("select stay_date, price_fen, stock, occupied, closed from kg_stay_calendar "
                + "where room_type_id = :r and stay_date >= :a and stay_date < :b", p("r", roomTypeId, "a", first.format(D), "b", end.format(D))))
        {
            cal.put(str(c.get("stayDate")), c);
        }
        long base = fen(rt.get("basePriceFen"));
        int stock = (int) fen(rt.get("stock"));
        List<Map<String, Object>> days = new ArrayList<Map<String, Object>>();
        for (LocalDate d = first; d.isBefore(end); d = d.plusDays(1))
        {
            Map<String, Object> c = cal.get(d.format(D));
            long price = c == null || c.get("priceFen") == null ? KgRoomWeekPrice.nightly(rt, d) : fen(c.get("priceFen"));
            boolean promoDay = false;
            if (promo != null)
            {
                LocalDate ps = date(str(promo.get("stayStart")));
                LocalDate pe = date(str(promo.get("stayEnd")));
                if (ps != null && pe != null && !d.isBefore(ps) && !d.isAfter(pe))
                {
                    price = fen(promo.get("priceFen"));
                    promoDay = true;
                }
            }
            int remain = (c == null || c.get("stock") == null ? stock : (int) fen(c.get("stock"))) - (c == null ? 0 : (int) fen(c.get("occupied")));
            String status;
            if (d.isBefore(today))
            {
                status = "past";
            }
            else if (d.isAfter(last))
            {
                status = "beyond";
            }
            else if (c != null && "1".equals(str(c.get("closed"))))
            {
                status = "closed";
            }
            else if (remain <= 0)
            {
                status = "full";
            }
            else
            {
                status = "available";
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("date", d.format(D));
            m.put("day", d.getDayOfMonth());
            m.put("priceFen", KgSettlementMath.sale(price, rt.get("opType")));
            m.put("status", status);
            m.put("low", "available".equals(status) && price < base);
            m.put("promo", promoDay);
            m.put("remain", Math.max(0, remain));
            days.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("month", first.getYear() + "年 " + first.getMonthValue() + "月");
        out.put("ym", first.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        out.put("lead", first.getDayOfWeek().getValue() - 1);
        out.put("days", days);
        out.put("basePriceFen", KgSettlementMath.sale(base, rt.get("opType")));
        return out;
    }

    /** 低价日推荐：前后平移 1–3 天、晚数不变、每晚可订、不早于今天，取总价最低；不比当前便宜就返回 recommended = null */
    public Map<String, Object> recommend(Long roomTypeId, String checkIn, String checkOut, int guests)
    {
        Map<String, Object> rt = roomType(roomTypeId);
        LocalDate in = date(checkIn);
        LocalDate out = date(checkOut);
        checkDates(in, out);
        Map<String, Object> cur = availability(rt, in, out, Math.max(1, guests), null);
        long curFen = fen(cur.get("roomFen"));
        Map<String, Object> best = null;
        int bestShift = 0;
        for (int shift : new int[] { 1, -1, 2, -2, 3, -3 })
        {
            LocalDate ni = in.plusDays(shift);
            if (ni.isBefore(LocalDate.now()))
            {
                continue;
            }
            Map<String, Object> av = availability(rt, ni, out.plusDays(shift), Math.max(1, guests), null);
            if (Boolean.TRUE.equals(av.get("bookable")) && fen(av.get("roomFen")) < curFen
                    && (best == null || fen(av.get("roomFen")) < fen(best.get("roomFen"))))
            {
                best = av;
                bestShift = shift;
            }
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("current", pack(in, out, cur));
        if (best == null)
        {
            res.put("recommended", null);
            return res;
        }
        res.put("recommended", pack(in.plusDays(bestShift), out.plusDays(bestShift), best));
        res.put("shiftDays", bestShift);
        res.put("shiftText", (bestShift > 0 ? "晚" : "早") + Math.abs(bestShift) + "天入住");
        res.put("savingFen", curFen - fen(best.get("roomFen")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> a = (List<Map<String, Object>>) cur.get("nightly");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> b = (List<Map<String, Object>>) best.get("nightly");
        List<Map<String, Object>> compare = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < a.size() && i < b.size(); i++)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("leftDate", a.get(i).get("date"));
            row.put("leftFen", a.get(i).get("priceFen"));
            row.put("rightDate", b.get(i).get("date"));
            row.put("rightFen", b.get(i).get("priceFen"));
            compare.add(row);
        }
        res.put("compare", compare);
        return res;
    }

    private static Map<String, Object> pack(LocalDate in, LocalDate out, Map<String, Object> av)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("checkIn", in.format(D));
        m.put("checkOut", out.format(D));
        m.put("nights", ChronoUnit.DAYS.between(in, out));
        m.put("totalFen", av.get("roomFen"));
        m.put("nightly", av.get("nightly"));
        return m;
    }

    // ---------- 早鸟 ----------

    public Map<String, Object> promos(String city)
    {
        city = cities.businessCity(city);
        List<Map<String, Object>> rows = db.list("select p.promo_id, p.stay_id, p.room_type_id, p.title, p.price_fen, p.quota, p.sold, p.stay_start, "
                + "p.stay_end, p.sale_end, p.refundable, s.stay_name, s.cover, s.area_desc, t.type_name, t.base_price_fen strike_fen, "
                + "coalesce(st.op_type, 'direct') op_type, st.safe_cert from kg_stay_promo p join kg_stay s on s.stay_id = p.stay_id "
                + "join kg_stay_room_type t on t.room_type_id = p.room_type_id left join kg_store st on st.store_id = s.store_id "
                + "where p.status = '0' and p.del_flag = '0' and p.sale_end > now() and p.sold < p.quota and t.status = '0' and " + ONLINE
                + (city == null || city.isEmpty() ? "" : " and s.city = :c") + " order by p.sale_end, p.promo_id limit 50", p("c", city));
        String nearest = null;
        for (Map<String, Object> r : rows)
        {
            r.put("operateModeLabel", operateLabel(str(r.get("opType"))));
            r.put("safeCert", "0".equals(str(r.get("safeCert"))));
            r.put("priceFen", KgSettlementMath.sale(fen(r.get("priceFen")), r.get("opType")));
            r.put("strikeFen", KgSettlementMath.sale(fen(r.get("strikeFen")), r.get("opType")));
            r.put("remain", fen(r.get("quota")) - fen(r.get("sold")));
            r.put("refundable", "1".equals(str(r.get("refundable"))));
            if (nearest == null)
            {
                nearest = str(r.get("saleEnd"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("nearestSaleEnd", nearest);
        out.put("serverTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return out;
    }

    // ---------- 报价（服务端定价的唯一入口） ----------

    /** 历史默认文案版本；新报价与订单的须知版本由 StayBookingNotice 按标题、全文生成。 */
    public static final String AGREEMENT_VERSION = "2026-10";
    public static final String AGREEMENT_TITLE = "袋鼠全球青年旅居预订须知";
    public static final String AGREEMENT_SUMMARY = "1. 入住人姓名与联系电话仅用于本次预订与入住服务，只提供给接待门店；\n"
            + "2. 请带好入住人本人身份证，到店后由小院前台按当地规定现场实名登记；\n"
            + "3. 取消与退款按订单确认页展示的取消政策执行；\n"
            + "4. 退款需门店审核，通过后原路退回，到账时间以支付渠道为准；\n"
            + "5. 入住期间请遵守门店住宿规定。";
    /** 角色切换后的默认须知：第 4 条改成平台审核（退款只由平台在后台审批），其余逐字不变 */
    public static final String AGREEMENT_SUMMARY_PLATFORM = AGREEMENT_SUMMARY.replace("4. 退款需门店审核，", "4. 退款需平台审核，");

    /**
     * 现在用的默认须知正文（房源没在后台自定义须知时用）。版本号是标题 + 正文的哈希（StayBookingNotice.snapshot），
     * 切换前后正文不同、版本也不同：报价后、下单前正好切换的，下单会被要求重新阅读并同意一次；已下的单存的是当时的快照，不受影响
     */
    public static String agreementSummary()
    {
        return KgStayOrderService.refundByPlatform() ? AGREEMENT_SUMMARY_PLATFORM : AGREEMENT_SUMMARY;
    }

    /** Same date-specific nightly price, availability and long-stay discount as checkout. */
    List<Map<String,Object>> periodPrices(List<Map<String,Object>> rooms, LocalDate in, int guests, Map<String,Object> promo, Map<String,Object> stay) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (int nights : new int[]{7,15,30}) {
            long best = Long.MAX_VALUE, available = Long.MAX_VALUE;
            for (Map<String,Object> rt : rooms) {
                Map<String,Object> av = availability(rt, in, in.plusDays(nights), guests, promo);
                long room = fen(av.get("roomFen"));
                long price = room - stayDiscount(room, nights, stay, promo, av);
                best = Math.min(best, price);
                if (Boolean.TRUE.equals(av.get("bookable")) && (promo == null || Boolean.TRUE.equals(av.get("promoApplied")))) available = Math.min(available, price);
            }
            result.add(p("nights",nights,"checkIn",in.toString(),"checkOut",in.plusDays(nights).toString(),
                "payFen",best == Long.MAX_VALUE ? null : (available == Long.MAX_VALUE ? best : available),"bookable",available != Long.MAX_VALUE));
        }
        return result;
    }

    static long longStayDiscount(long roomFen, long nights, Map<String,Object> stay, Map<String,Object> promo) {
        long threshold=fen(stay.get("longStayNights")), rate=fen(stay.get("longStayRate"));
        return promo==null && threshold>0 && rate>0 && rate<100 && nights>=threshold ? roomFen*(100-rate)/100 : 0;
    }

    // Explicit duration totals are already a package price; do not discount them a second time.
    static long stayDiscount(long roomFen, long nights, Map<String,Object> stay, Map<String,Object> promo, Map<String,Object> availability) {
        return Boolean.TRUE.equals(availability.get("periodPriceApplied")) ? 0 : longStayDiscount(roomFen, nights, stay, promo);
    }

    public Map<String, Object> quote(Long roomTypeId, String checkIn, String checkOut, int guests, Long promoId)
    {
        com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireStayBooking(db);
        Map<String, Object> rt = roomType(roomTypeId);
        Long stayId = KgJdbc.asLong(rt.get("stayId"));
        Map<String, Object> s = onlineStay(stayId);
        StayBookingChannel.requireOnline(s);
        rt.put("opType", s.get("opType"));
        LocalDate in = date(checkIn);
        LocalDate out = date(checkOut);
        checkDates(in, out);
        if (guests < 1)
        {
            throw new ServiceException("入住人数至少 1 人");
        }
        Map<String, Object> promo = activePromo(promoId, roomTypeId);
        if (promoId != null && promo == null)
        {
            throw new ServiceException("早鸟特价已售罄或已结束，请按日常价预订");
        }
        Map<String, Object> av = availability(rt, in, out, guests, promo);
        if (promo != null && !Boolean.TRUE.equals(av.get("promoApplied")))
        {
            throw new ServiceException("所选日期不在早鸟特价适用范围内");
        }
        long nights = ChronoUnit.DAYS.between(in, out);
        long roomFen = fen(av.get("roomFen"));
        long discount = 0;
        String discountLabel = "暂无可用优惠";
        int lsNights = (int) fen(s.get("longStayNights"));
        int lsRate = (int) fen(s.get("longStayRate"));
        if (!Boolean.TRUE.equals(av.get("periodPriceApplied")) && promo == null && lsNights > 0 && lsRate > 0 && lsRate < 100 && nights >= lsNights)
        {
            discount = longStayDiscount(roomFen, nights, s, promo);
            discountLabel = "长住优惠（满" + lsNights + "晚" + (lsRate % 10 == 0 ? lsRate / 10 + "" : lsRate / 10.0 + "") + "折）";
        }
        long serviceFee = 0;
        long pay = roomFen - discount + serviceFee;
        boolean nonrefundablePromo = promo != null && !"1".equals(str(promo.get("refundable")));
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        Map<String, Object> stay = new LinkedHashMap<String, Object>();
        stay.put("stayId", stayId);
        stay.put("stayName", s.get("stayName"));
        stay.put("cover", rt.get("cover") == null || str(rt.get("cover")).isEmpty() ? s.get("cover") : rt.get("cover"));
        stay.put("typeName", rt.get("typeName"));
        stay.put("summary", rt.get("summary"));
        stay.put("capacity", rt.get("capacity"));
        stay.put("unitLabel", rt.get("unitLabel"));
        stay.put("safeCert", "0".equals(str(s.get("safeCert"))));
        stay.put("checkinTime", s.get("checkinTime"));
        stay.put("checkoutTime", s.get("checkoutTime"));
        stay.put("storeId", s.get("storeId"));
        stay.put("city", s.get("city"));
        stay.put("storeName", s.get("storeName"));
        res.put("stay", stay);
        res.put("checkIn", in.format(D));
        res.put("checkOut", out.format(D));
        res.put("checkInText", md(in) + " " + weekday(in));
        res.put("checkOutText", md(out) + " " + weekday(out));
        res.put("nights", nights);
        res.put("guests", guests);
        res.put("units", av.get("units"));
        res.put("bookable", av.get("bookable"));
        res.put("reason", av.get("reason"));
        res.put("nightly", av.get("nightly"));
        res.put("periodPriceApplied", av.get("periodPriceApplied"));
        res.put("roomFen", roomFen);
        res.put("merchantBaseFen", av.get("merchantBaseFen"));
        res.put("platformMarkupFen", roomFen - fen(av.get("merchantBaseFen")));
        boolean direct = KgSettlementMath.isDirect(s.get("opType"));
        res.put("markupRateBp", direct ? 0 : 500);
        res.put("settlementPolicy", direct ? KgSettlementMath.DIRECT_NO_FEE_POLICY : "guest_markup_5pct_v1");
        res.put("addons", new ArrayList<Object>());
        res.put("addonFen", 0);
        res.put("discountFen", discount);
        res.put("discountLabel", discountLabel);
        res.put("serviceFeeFen", serviceFee);
        res.put("payFen", pay);
        res.put("promoId", promo == null ? null : promo.get("promoId"));
        Map<String, Object> pol = StayCustomPolicy.snapshot(str(s.get("cancelPolicy")), s.get("cancelPolicyConfig"), nonrefundablePromo, str(s.get("checkinTime")));
        pol.put("copy", md(in) + " " + s.get("checkinTime") + " 为入住时刻。" + pol.get("summary"));
        res.put("policy", pol);
        res.put("agreement", StayBookingNotice.snapshot(s.get("bookingNoticeConfig")));
        return res;
    }

    /** 房源设施的代码（字典 kg_stay_facility；2026-10-17 V51 补了 study 学习室、gym 健身房，前端回归测试按这里核对列表筛选用的代码） */
    // 2026-11-03 主人：「增加一个地暖」：V70 字典补 floorheat 地暖
    public static final List<String> FACILITY_CODES = Arrays.asList("bath", "ac", "wifi", "washer", "parking", "balcony", "hotwater", "study", "gym", "floorheat");
}
