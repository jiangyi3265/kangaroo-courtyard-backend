package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayBrowseService;

/**
 * 全国城市与区县字典、离线行政区定位与城市主页统计。
 * 岗位的城市字段是"云南大理"这种写法，统一按「等于或以城市 / 别名结尾」匹配（KgJobCity）。
 */
@Service
public class KgCityService
{
    @Autowired
    private KgJdbc db;

    /** 公开展示资料，独立读取以便运营保存后下一次打开即可看到。不会授予员工权限。 */
    public Map<String, Object> coordinator(String rawCity)
    {
        String raw = rawCity == null ? "" : rawCity.trim();
        if (raw.isEmpty() || raw.length() > 64) return new LinkedHashMap<String, Object>();
        String city = jobCity(raw);
        Map<String, Object> row = db.one("select name as city, coordinator_name as name, coordinator_avatar as avatar, "
                + "coordinator_intro as intro from kg_city where name = :city and status = '0' order by city_id limit 1", p("city", city));
        return row == null ? new LinkedHashMap<String, Object>() : row;
    }

    // ---------------------------------------------------------------- 业务城市归一
    // 门店、房源、活动、岗位里存的是城市或常用目的地名（"厦门""大理""平潭"）。V25 补进来的区县行（create_by='V25'）
    // 只是为了能搜到、能定位：定位或选中"思明区"时要按所属城市"厦门"查数据，否则岗位、房源、活动全部为空。
    // level=city/destination 的行，以及运营原有的目的地行（V11 种子、后台新增，哪怕 V25 把它的 level 补成了 district）保持自身名字。
    private static final long BUSINESS_TTL_MS = 10 * 60 * 1000L;
    private volatile Map<String, String> businessNames;
    /** 字典里的城市 / 目的地名（level 不是 district 的行），和 businessNames 一起加载 */
    private volatile java.util.Set<String> cityNames;
    /** 字典名 → 只属于它的全称 / 搜索别名（含中文、两个字以上），岗位城市按这组写法匹配（KgJobCity） */
    private volatile Map<String, List<String>> aliasesByName;
    private volatile long businessLoadedAt;

    private Map<String, String> businessNames()
    {
        Map<String, String> cached = businessNames;
        if (cached != null && System.currentTimeMillis() - businessLoadedAt < BUSINESS_TTL_MS)
        {
            return cached;
        }
        // 不按启停过滤：运营在字典里停用了"厦门"，门店和房源仍记在"厦门"名下，选中"思明区"照样要按"厦门"查
        List<Map<String, Object>> rows = db.list("select name, full_name, parent_name, level, create_by, search_aliases from kg_city order by city_id", p());
        Map<String, String> parents = new HashMap<String, String>();
        for (Map<String, Object> r : rows)
        {
            if (!"district".equals(str(r.get("level"))))
            {
                parents.putIfAbsent(str(r.get("fullName")), str(r.get("name")));
            }
        }
        Map<String, String> out = new HashMap<String, String>();
        for (Map<String, Object> r : rows)
        {
            if ("district".equals(str(r.get("level"))) && "V25".equals(str(r.get("createBy"))))
            {
                String parent = parents.get(str(r.get("parentName")));
                if (parent != null && !parent.isEmpty() && !parent.equals(str(r.get("name"))))
                {
                    out.put(str(r.get("name")), parent);
                }
            }
        }
        // 全称与搜索别名 → 字典名（平潭岛 → 平潭、厦门市 → 厦门）。后台的门店 / 房源城市是自由填写的文本，
        // 生产上门店写成了"平潭岛"，用户选"平潭"时按 city = '平潭' 查不到它的房源和活动。
        // 只收"恰好属于一个城市/目的地、本身又不是字典名"的写法；被两个地方共用的别名不猜
        java.util.Set<String> names = new java.util.HashSet<String>();
        java.util.Set<String> places = new java.util.HashSet<String>();
        for (Map<String, Object> r : rows)
        {
            names.add(str(r.get("name")));
            if (!"district".equals(str(r.get("level"))))
            {
                places.add(str(r.get("name")));
            }
        }
        cityNames = places;
        Map<String, String> aliases = new HashMap<String, String>();
        java.util.Set<String> ambiguous = new java.util.HashSet<String>();
        for (Map<String, Object> r : rows)
        {
            if ("district".equals(str(r.get("level"))))
            {
                continue;
            }
            String name = str(r.get("name"));
            List<String> tokens = new ArrayList<String>();
            tokens.add(str(r.get("fullName")));
            for (String a : str(r.get("searchAliases")).split(","))
            {
                tokens.add(a);
            }
            for (String t : tokens)
            {
                String a = t.trim();
                if (a.isEmpty() || names.contains(a))
                {
                    continue;
                }
                String prev = aliases.putIfAbsent(a, name);
                if (prev != null && !prev.equals(name))
                {
                    ambiguous.add(a);
                }
            }
        }
        Map<String, List<String>> byName = new HashMap<String, List<String>>();
        for (Map.Entry<String, String> e : aliases.entrySet())
        {
            if (!ambiguous.contains(e.getKey()))
            {
                out.putIfAbsent(e.getKey(), e.getValue());
                // 拼音缩写（pt、dl）不拿来匹配岗位城市；一个字的别名太容易误中
                if (e.getKey().length() >= 2 && hasCjk(e.getKey()))
                {
                    byName.computeIfAbsent(e.getValue(), k -> new ArrayList<String>()).add(e.getKey());
                }
            }
        }
        for (List<String> l : byName.values())
        {
            java.util.Collections.sort(l);
        }
        aliasesByName = byName;
        businessNames = out;
        businessLoadedAt = System.currentTimeMillis();
        return out;
    }

    /**
     * 城市写法 → 业务数据使用的城市名：区县归到所属城市（思明区 → 厦门），全称 / 搜索别名归到字典名（平潭岛 → 平潭）。
     * 查询时归一用户选的值，保存门店、房源时归一后台填的值；不认识的名字原样返回（去掉首尾空白）。
     */
    public String businessCity(String name)
    {
        if (name == null)
        {
            return null;
        }
        String n = name.trim();
        String mapped = n.isEmpty() ? null : businessNames().get(n);
        return mapped == null ? n : mapped;
    }

    /**
     * 岗位上的城市写法 → 「有岗位的城市」下拉里显示的城市名（2026-10-16）。
     * 先按 businessCity 归一；归一后还不是字典里的城市 / 目的地名时（岗位写的是「云南大理」「福建平潭岛」），
     * 取它结尾的最长的城市 / 目的地名或别名（「大理」「平潭岛」），再归一（平潭岛 → 平潭）。
     * 和列表同一口径（KgJobCity：等于或以城市 / 别名结尾）：选「大理」本来就带出「云南大理」，下拉不能再单列一行；
     * 之前按包含取，「五大连池」会归到「大连」，下拉里的「大连」点进去却查不到它。
     * 一个字的名字不拿来匹配（太容易误中）；什么都对不上就原样返回
     */
    public String jobCity(String name)
    {
        String n = businessCity(name);
        if (n == null || n.isEmpty())
        {
            return n;
        }
        Map<String, String> mapped = businessNames();
        java.util.Set<String> places = cityNames;
        Map<String, List<String>> byName = aliasesByName;
        if (places == null || places.contains(n))
        {
            return n;
        }
        java.util.Set<String> candidates = new java.util.HashSet<String>(places);
        if (byName != null)
        {
            for (List<String> l : byName.values())
            {
                candidates.addAll(l);
            }
        }
        String hit = null;
        for (String c : candidates)
        {
            if (c.length() >= 2 && n.endsWith(c)
                    && (hit == null || c.length() > hit.length() || (c.length() == hit.length() && c.compareTo(hit) < 0)))
            {
                hit = c;
            }
        }
        if (hit == null)
        {
            return n;
        }
        // 命中的是字典名就用它自己（不能再按区县归到别的城市）；命中的是别名就归到它的字典名
        return places.contains(hit) || !mapped.containsKey(hit) ? hit : mapped.get(hit);
    }

    /**
     * 能在字典里对上的城市名（先按 jobCity 归一）：对不上、或者是「全国」「不限」时返回 null。
     * 自动建商家门店用：kg_store.city 会公开展示，也是派单找本城人的依据，不能是随手写的文字。
     */
    public String dictionaryCity(String name)
    {
        String n = jobCity(name);
        if (n == null || n.isEmpty() || "全国".equals(n) || "不限".equals(n))
        {
            return null;
        }
        businessNames();
        java.util.Set<String> places = cityNames;
        return places != null && places.contains(n) ? n : null;
    }

    /**
     * 选中某个城市时岗位城市还可以写成的样子（不含它自己）：字典里只属于它的全称和搜索别名，
     * 如 平潭 → [平潭县, 平潭岛, 平潭综合实验区]。区县不算（「鼓楼区」各地都有；只写区县的岗位以前也查不出来，口径不变）。
     * 匹配规则见 KgJobCity
     */
    public List<String> jobCityTerms(String name)
    {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty())
        {
            return new ArrayList<String>();
        }
        businessNames();
        Map<String, List<String>> byName = aliasesByName;
        List<String> l = byName == null ? null : byName.get(n);
        return l == null ? new ArrayList<String>() : new ArrayList<String>(l);
    }

    /** 含中文（或其他非 ASCII 字符）：拼音缩写不拿来匹配岗位城市 */
    static boolean hasCjk(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            if (s.charAt(i) > 0x7f)
            {
                return true;
            }
        }
        return false;
    }

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    public Map<String, Object> cities()
    {
        List<Map<String, Object>> all = db.list("select name, full_name, province, region, pinyin, initial, hot, adcode, parent_name, level, search_aliases from kg_city where status = '0' order by initial, pinyin, name", p());
        List<String> hot = new ArrayList<String>();
        for (Map<String, Object> c : db.list("select name from kg_city where status = '0' and hot = '1' order by sort, city_id", p()))
        {
            hot.add(str(c.get("name")));
        }
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        Map<String, Object> cur = null;
        for (Map<String, Object> c : all)
        {
            // 区县通过搜索进入，默认字母列表保留城市与常用目的地，避免一次渲染三千余行。
            if ("district".equals(str(c.get("level")))) continue;
            String letter = str(c.get("initial"));
            if (cur == null || !letter.equals(cur.get("letter")))
            {
                cur = new LinkedHashMap<String, Object>();
                cur.put("letter", letter);
                cur.put("items", new ArrayList<String>());
                groups.add(cur);
            }
            @SuppressWarnings("unchecked")
            List<String> items = (List<String>) cur.get("items");
            items.add(str(c.get("name")));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("hot", hot);
        m.put("groups", groups);
        // 区县行带上业务城市名：选择器选中"思明区"时按"厦门"保存和查询（与 businessCity 同一口径）
        for (Map<String, Object> c : all)
        {
            c.put("cityName", businessCity(str(c.get("name"))));
        }
        m.put("all", all);
        return m;
    }

    /** Older clients sent WGS84 without an explicit coordinate type. */
    public Map<String, Object> locate(Double lat, Double lng)
    {
        return locate(lat, lng, null, "wgs84");
    }

    public Map<String, Object> locate(Double lat, Double lng, Double accuracy, String coordinateType)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("found", false);
        m.put("message", "暂时无法确认所在城市，请手动选择");
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < -90 || lat > 90 || lng < -180 || lng > 180) return m;
        if (accuracy != null && (!Double.isFinite(accuracy) || accuracy < 0 || accuracy > 5000)) return m;
        if (!"wgs84".equals(coordinateType) && !"gcj02".equals(coordinateType)) return m;
        double[] point = "gcj02".equals(coordinateType) ? new double[]{lat,lng} : CityBoundaryIndex.gcj(lat,lng);
        String code = CityBoundaryIndex.instance().locate(point[0],point[1],accuracy == null ? 200 : accuracy);
        if (code == null) return m;
        Map<String,Object> city = db.one("select name,full_name,province,adcode from kg_city where adcode=:code and status='0' order by city_id limit 1",p("code",code));
        if (city == null) return m;
        m.put("found", true);
        // 定位到区县时交给前端的是业务城市名（思明区 → 厦门），否则选中后岗位、房源、活动全部查不到；文案仍写清楚落在哪个区县
        String business = businessCity(str(city.get("name")));
        m.put("name",business);
        m.put("district",business.equals(str(city.get("name"))) ? null : city.get("name"));
        m.put("label",city.get("fullName")+" · "+(business.equals(str(city.get("name"))) ? city.get("province") : business));
        m.put("adcode",code);
        m.put("source","administrative_boundary");
        m.remove("message");
        return m;
    }

    static double distanceKm(double lat1, double lng1, double lat2, double lng2)
    {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    /** 城市主页：在招机会 / 本周上新 / 可报名活动、三块入口的真实数量、城市群、安心商家占比 */
    public Map<String, Object> overview(String city)
    {
        // 没选过城市（首页显示「全部城市」）的按全部城市统计；之前一律当成大理，首页写着全部城市，点进城市页却是大理的数据
        boolean all = city == null || city.trim().isEmpty() || "全部城市".equals(city.trim());
        // 之前已经存下区县名（今天 V25 上线后选过区县的用户）也按所属城市统计
        String c = all ? "全部城市" : businessCity(city);
        Map<String, Object> ps = p("c", c, "like", com.ruoyi.kangaroo.ext.KgLike.contains(c));
        String byLike = all ? "" : " and city like :like";
        // 岗位数和岗位列表同一口径（KgJobCity：等于或以城市 / 别名结尾），之前按 like %城市% 数，「大连」会把「五大连池」算进去
        String byJobCity = all ? "" : " and " + KgJobCity.sql("kg_job.city", "jc", KgJobCity.terms(this, c), ps);
        String byCity = all ? "" : " and city = :c";
        // 只数门店还在营业的岗位（与岗位列表 / feed 口径一致）
        String storeOk = " and exists (select 1 from kg_store st where st.store_id = kg_job.store_id and st.status = '0' and st.del_flag = '0')";
        long jobs = db.count("select count(*) from kg_job where del_flag = '0' and status = 'published' and job_type <> 'online'" + byJobCity + storeOk, ps);
        long newJobs = db.count("select count(*) from kg_job where del_flag = '0' and status = 'published' and job_type <> 'online'" + byJobCity
                + " and coalesce(publish_time, create_time) >= date_sub(now(), interval 7 day)" + storeOk, ps);
        long acts = db.count("select count(*) from kg_activity where del_flag = '0' and status = 'open'" + byCity + " and start_time > now()", ps);
        long weekActs = db.count("select count(*) from kg_activity where del_flag = '0' and status in ('open','closed')" + byCity + " and start_time > now() "
                + "and start_time < date_add(curdate(), interval 7 day)", ps);
        long stays = com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.STAY_KEY) ? db.count("select count(*) from kg_stay s where " + (all ? "" : "s.city = :c and ") + KgStayBrowseService.ONLINE, ps) : 0;
        long stores = db.count("select count(*) from kg_store where del_flag = '0' and status = '0'" + byLike, ps);
        long safe = db.count("select count(*) from kg_store where del_flag = '0' and status = '0'" + byLike + " and safe_cert = '0'", ps);
        Map<String, Object> info = all ? null : db.one("select name, full_name, province, region from kg_city where name = :c", ps);
        List<String> siblings = new ArrayList<String>();
        if (info != null && !str(info.get("region")).trim().isEmpty())
        {
            for (Map<String, Object> r : db.list("select name from kg_city where region = :r and status = '0' order by sort limit 6", p("r", info.get("region"))))
            {
                siblings.add(str(r.get("name")));
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("city", c);
        m.put("label", info == null ? c : info.get("fullName") + " · " + info.get("province"));
        m.put("region", info == null ? "" : info.get("region"));
        m.put("siblings", siblings);
        m.put("jobs", com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.RECRUITMENT_KEY) ? jobs : 0);
        m.put("newJobs", com.ruoyi.kangaroo.ext.community.KgCommunityFeature.isEnabled(db,com.ruoyi.kangaroo.ext.community.KgCommunityFeature.RECRUITMENT_KEY) ? newJobs : 0);
        m.put("activities", acts);
        m.put("weekActivities", weekActs);
        m.put("stays", stays);
        m.put("stores", stores);
        m.put("safeRatio", stores == 0 ? null : Math.round(safe * 100.0 / stores));
        m.put("builders", all ? new ArrayList<Map<String, Object>>() : builders(c));
        return m;
    }

    private List<Map<String, Object>> builders(String city)
    {
        // Older awards may use a district or a full city name. Reuse the city dictionary's exact aliases.
        List<String> names = new ArrayList<String>();
        names.add(city);
        for (Map.Entry<String, String> entry : businessNames().entrySet())
        {
            if (city.equals(entry.getValue()) && !city.equals(entry.getKey())) names.add(entry.getKey());
        }
        return db.list("select distinct u.user_id, coalesce(nullif(trim(u.nickname),''),'城市共建人') name "
                + "from kg_badge_award a join kg_badge_def d on d.code=a.badge_code "
                + "join kg_app_user u on u.user_id=a.app_user_id "
                + "where a.badge_code='city_builder' and trim(a.city) in (:cities) "
                + "and a.status='active' and a.visible='1' and d.enabled='1' "
                + "and (a.expires_at is null or a.expires_at>now()) and u.status='0' and u.del_flag='0' "
                + "order by u.user_id", p("cities", names));
    }
}
