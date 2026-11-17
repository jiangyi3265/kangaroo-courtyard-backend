package com.ruoyi.kangaroo.ext.core;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayBrowseService;
import com.ruoyi.kangaroo.service.IKgStoreService;
import com.ruoyi.kangaroo.util.KgValidate;

/**
 * 门店资料（2026-10-18 岗位详情改版，主人效果图 41「门店页」；V54 加的 kg_store 列）。
 *
 * 主人拍板：岗位详情只有两种样子 ——「袋鼠实地探访 + 保险」和「袋鼠线上采访」，采访方式由后台「门店」里选（实地探访 / 线上采访 / 未采访）；
 * 店长介绍、采访视频（≤5分钟）、该店亮点、交通参考、营业执照、店里日常由袋鼠员工在后台填。没填的项用这家店小院（kg_stay）的资料兜底。
 *
 * 实地探访就是原来的安心商家认证（主人早先说过认证 = 去现场拍过）：
 *   · 选实地探访 = 认证（safe_cert = '0'，记认证时间、认证人），选另外两种 = 撤销认证（safe_cert = '1'，清掉认证时间、认证人）——
 *     直接调门店服务的 certify，和原来的认证接口一模一样，所有按 safe_cert 显示的角标、筛选照旧对；
 *   · 改成 / 取消实地探访要有认证权限（kangaroo:store:cert），和认证接口同一道门；只改资料、或在线上采访和未采访之间换不用；
 *   · 读的时候以 safe_cert 为准（{@link #mode}）：老版本程序的认证接口只改 safe_cert，采访方式可能暂时对不上。
 * 图片只收平台图片路径（/profile/upload/…），采访视频只收本平台视频上传给的路径、而且文件确实在。
 */
@Service
public class KgStoreProfiles
{
    public static final String ONSITE = "onsite";

    public static final String ONLINE = "online";

    /** 该店亮点代码 → 名称（顺序就是页面上的顺序；小程序按代码配图标） */
    public static final Map<String, String> FACILITIES;
    static
    {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("gym", "健身房");
        m.put("pool", "游泳池");
        m.put("ebike", "电动车可用");
        m.put("parking", "免费停车");
        m.put("cook", "可做饭");
        m.put("lounge", "公共休闲区");
        m.put("wifi", "无线网络");
        m.put("ac", "空调");
        m.put("washer", "洗衣机");
        m.put("study", "学习室");
        m.put("bath", "独立卫浴");
        FACILITIES = Collections.unmodifiableMap(m);
    }

    /** 小院设施（字典 kg_stay_facility）里和门店亮点同名的几项；小院「可做饭」是单独一列 cookable = '1' */
    static final Set<String> STAY_SAME = new LinkedHashSet<String>(Arrays.asList("gym", "parking", "wifi", "ac", "washer", "study", "bath"));

    /** 店里日常最多几张（daily_images 列宽 2000，9 个平台路径绰绰有余） */
    public static final int DAILY_MAX = 9;

    /** 没填店里日常时，用小院生活碎片里最近的几张照片 */
    static final int DIARY_FALLBACK = 6;

    /** 旧发布包兼容常量，不再用于采访时长校验。 */
    @Deprecated
    static final int SECONDS_MAX = KgVideoUploads.INTERVIEW_MAX_SECONDS + 1;

    static final String COLS = "store_id, store_name, safe_cert, rating, interview_mode, interview_video, interview_cover, interview_seconds, interview_note, "
            + "manager_name, manager_avatar, manager_years, manager_skills, manager_quote, facilities, travel_rail, travel_airport, travel_cost, "
            + "license_image, daily_images";

    /** 兜底小院：这家店在架的小院（和小院列表、详情同一口径），排序小的、编号小的那个 */
    static final String STAY_SQL = "select s.stay_id, s.stay_name, s.host_name, s.host_avatar, s.host_skills, s.host_intro, s.facilities, s.cookable, "
            + "s.travel_rail, s.travel_airport,s.cover,s.images from kg_stay s where s.store_id = :st and " + KgStayBrowseService.ONLINE + " order by s.sort, s.stay_id limit 1";

    /** 兜底日常照片：显示中的生活碎片里的照片（视频不要），排序同生活碎片页（KgStayMediaService.ORDER） */
    static final String DIARY_SQL = "select d.media_url from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0' and d.status = '0' "
            + "and d.media_type = 'image' order by d.sort, d.shot_date desc, d.diary_id desc limit " + DIARY_FALLBACK;

    /** 文字项：接口字段 → [列名, 列宽, 提示用的名字] */
    static final String[][] TEXT_FIELDS = {
        { "interviewNote", "interview_note", "100", "采访卡说明" },
        { "managerName", "manager_name", "32", "店长称呼" },
        { "managerYears", "manager_years", "32", "在这里工作多久" },
        { "managerSkills", "manager_skills", "100", "店长擅长" },
        { "managerQuote", "manager_quote", "200", "店长说" },
        { "travelRail", "travel_rail", "500", "交通参考（高铁 / 火车）" },
        { "travelAirport", "travel_airport", "500", "交通参考（机场）" },
        { "travelCost", "travel_cost", "32", "预估最低出行成本" },
    };

    /** 图片项：接口字段 → [列名, 提示用的名字] */
    static final String[][] IMAGE_FIELDS = {
        { "interviewCover", "interview_cover", "采访视频封面" },
        { "managerAvatar", "manager_avatar", "店长头像" },
        { "licenseImage", "license_image", "营业执照照片" },
    };

    @Autowired
    private KgJdbc db;

    @Autowired
    private IKgStoreService stores;

    @Autowired
    private KgVideoUploads videos;

    // ---------------------------------------------------------------- 纯函数（单测直接调）

    static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /**
     * 实际的采访方式：safe_cert = '0' 一律是实地探访；没认证却记着 onsite 的（老版本程序撤销了认证）算未采访；其余照记的（online / ''）
     */
    public static String mode(Object interviewMode, Object safeCert)
    {
        if ("0".equals(str(safeCert)))
        {
            return ONSITE;
        }
        return ONLINE.equals(str(interviewMode)) ? ONLINE : "";
    }

    /** 逗号拼接的代码 → 列表（去空、去重，保持原顺序） */
    static List<String> split(Object csv)
    {
        List<String> out = new ArrayList<String>();
        for (String s : str(csv).split(","))
        {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t))
            {
                out.add(t);
            }
        }
        return out;
    }

    /** daily_images 列（JSON 数组）→ 平台图片路径列表；坏数据当没填 */
    static List<String> parseImages(Object json)
    {
        List<String> out = new ArrayList<String>();
        String s = str(json);
        if (s.isEmpty())
        {
            return out;
        }
        try
        {
            for (Object o : JSON.parseArray(s))
            {
                String u = str(o);
                if (isImage(u) && !out.contains(u))
                {
                    out.add(u);
                }
            }
        }
        catch (RuntimeException e)
        {
            return new ArrayList<String>();
        }
        return out;
    }

    /** 平台上传的图片路径（/profile/upload/…，只含路径字符） */
    static boolean isImage(String u)
    {
        return u != null && u.startsWith("/profile/upload/") && KgJdbc.isPlatformImage(u);
    }

    /** 门店亮点代码 → [{code, label}]，按 FACILITIES 的顺序，不认识的丢掉 */
    static List<Map<String, Object>> facilityItems(Collection<String> codes)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, String> e : FACILITIES.entrySet())
        {
            if (codes.contains(e.getKey()))
            {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("code", e.getKey());
                m.put("label", e.getValue());
                out.add(m);
            }
        }
        return out;
    }

    /** 门店亮点代码（按 FACILITIES 顺序） */
    // 只有显式开启沿用才借用小院设施；未填写和全部取消都不展示未经选择的亮点。
    static boolean inheritsFacilities(Object csv)
    {
        return "@inherit".equals(str(csv));
    }

    static List<String> storeFacilities(Object csv)
    {
        List<String> got = split(csv);
        List<String> out = new ArrayList<String>();
        for (String code : FACILITIES.keySet())
        {
            if (got.contains(code))
            {
                out.add(code);
            }
        }
        return out;
    }

    /** 小院设施 → 门店亮点代码（同名的几项 + 可做饭），按 FACILITIES 顺序 */
    static List<String> stayFacilities(Map<String, Object> stay)
    {
        List<String> out = new ArrayList<String>();
        if (stay == null)
        {
            return out;
        }
        List<String> got = split(stay.get("facilities"));
        for (String code : FACILITIES.keySet())
        {
            if ("cook".equals(code) ? "1".equals(str(stay.get("cookable"))) : STAY_SAME.contains(code) && got.contains(code))
            {
                out.add(code);
            }
        }
        return out;
    }

    private static String or(String a, Object b)
    {
        return a.isEmpty() ? str(b) : a;
    }

    /**
     * 岗位详情的 storePage（store 是 COLS 那些列的一行，stay 是兜底小院、可以为空，diaryPhotos 是兜底小院的生活碎片照片）：
     *   interviewMode；interview {video, cover, seconds, note}（有采访方式、而且传了视频才有）；
     *   manager {name, avatar, years, skills, quote, rating}（门店填了店长称呼用门店的，不然用小院主理人，都没有为 null；rating = 门店评分）；
     *   facilities [{code, label}]（门店勾选；仅开启沿用时用小院的）；travel {rail, airport, cost}（每项门店的，没填用小院的出行参考）；
     *   licenseImage；dailyImages（门店的，没传用小院生活碎片的照片）
     */
    static Map<String, Object> page(Map<String, Object> store, Map<String, Object> stay, List<String> diaryPhotos)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String mode = mode(store.get("interviewMode"), store.get("safeCert"));
        out.put("interviewMode", mode);
        String video = str(store.get("interviewVideo"));
        Map<String, Object> interview = null;
        if (!mode.isEmpty() && KgVideoUploads.isPlatformVideo(video))
        {
            interview = new LinkedHashMap<String, Object>();
            interview.put("video", video);
            String cover = str(store.get("interviewCover"));
            interview.put("cover", isImage(cover) ? cover : "");
            interview.put("seconds", store.get("interviewSeconds"));
            interview.put("note", str(store.get("interviewNote")));
        }
        out.put("interview", interview);

        Object rating = store.get("rating");
        Map<String, Object> manager = null;
        if (!str(store.get("managerName")).isEmpty())
        {
            manager = new LinkedHashMap<String, Object>();
            manager.put("name", str(store.get("managerName")));
            String avatar = str(store.get("managerAvatar"));
            manager.put("avatar", isImage(avatar) ? avatar : "");
            manager.put("years", str(store.get("managerYears")));
            manager.put("skills", str(store.get("managerSkills")));
            manager.put("quote", str(store.get("managerQuote")));
        }
        else if (stay != null && !str(stay.get("hostName")).isEmpty())
        {
            // 店长整块用小院主理人的（不拼：门店的头像配小院主理人的名字就成了两个人）
            manager = new LinkedHashMap<String, Object>();
            manager.put("name", str(stay.get("hostName")));
            manager.put("avatar", str(stay.get("hostAvatar")));
            manager.put("years", "");
            manager.put("skills", str(stay.get("hostSkills")));
            manager.put("quote", str(stay.get("hostIntro")));
        }
        if (manager != null)
        {
            // 门店评分（没有评价时是 null，页面不显示「店长服务评分」）
            manager.put("rating", rating instanceof Number ? rating : null);
        }
        out.put("manager", manager);

        List<String> fac = storeFacilities(store.get("facilities"));
        out.put("facilities", facilityItems(inheritsFacilities(store.get("facilities")) ? stayFacilities(stay) : fac));

        Map<String, Object> travel = new LinkedHashMap<String, Object>();
        travel.put("rail", or(str(store.get("travelRail")), stay == null ? null : stay.get("travelRail")));
        travel.put("airport", or(str(store.get("travelAirport")), stay == null ? null : stay.get("travelAirport")));
        travel.put("cost", str(store.get("travelCost")));
        out.put("travel", travel);

        String license = str(store.get("licenseImage"));
        out.put("licenseImage", isImage(license) ? license : "");
        List<String> daily = parseImages(store.get("dailyImages"));
        out.put("dailyImages", daily.isEmpty() ? (diaryPhotos == null ? new ArrayList<String>() : new ArrayList<String>(diaryPhotos)) : daily);
        return out;
    }

    // ---------------------------------------------------------------- 查库

    Map<String, Object> storeRow(Long storeId)
    {
        return storeId == null ? null : db.one("select " + COLS + " from kg_store where store_id = :id and del_flag = '0'", p("id", storeId));
    }

    Map<String, Object> fallbackStay(Long storeId)
    {
        return db.one(STAY_SQL, p("st", storeId));
    }

    List<String> diaryPhotos(Map<String, Object> stay)
    {
        List<String> out = new ArrayList<String>();
        if (stay == null || stay.get("stayId") == null)
        {
            return out;
        }
        for (Map<String, Object> r : db.list(DIARY_SQL, p("s", stay.get("stayId"))))
        {
            String u = str(r.get("mediaUrl"));
            if (!u.isEmpty() && !out.contains(u))
            {
                out.add(u);
            }
        }
        return out;
    }

    /** 岗位详情（线下岗位）的 storePage；门店不在了返回 null */
    public Map<String, Object> storePage(Long storeId)
    {
        Map<String, Object> store = storeRow(storeId);
        if (store == null)
        {
            return null;
        }
        Map<String, Object> stay = fallbackStay(storeId);
        Map<String,Object> result=page(store,stay,diaryPhotos(stay));
        List<String> environment=new ArrayList<>();
        if(stay!=null){
            for(String image:str(stay.get("images")).split(","))if(com.ruoyi.kangaroo.domain.JobImages.isUpload(image)&&!environment.contains(image))environment.add(image);
            String cover=str(stay.get("cover"));if(environment.isEmpty()&&com.ruoyi.kangaroo.domain.JobImages.isUpload(cover))environment.add(cover);
        }
        result.put("environmentImages",environment);return result;
    }

    /** 后台「门店资料」：门店填的各项 + 选项 + 兜底小院的情况（页面提示「没填时用小院的 XX」） */
    public Map<String, Object> adminProfile(Long storeId)
    {
        Map<String, Object> s = storeRow(storeId);
        if (s == null)
        {
            throw new ServiceException("门店不存在或已删除");
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("storeId", s.get("storeId"));
        out.put("storeName", str(s.get("storeName")));
        out.put("interviewMode", mode(s.get("interviewMode"), s.get("safeCert")));
        out.put("interviewVideo", str(s.get("interviewVideo")));
        out.put("interviewCover", str(s.get("interviewCover")));
        out.put("interviewSeconds", s.get("interviewSeconds"));
        out.put("interviewNote", str(s.get("interviewNote")));
        out.put("managerName", str(s.get("managerName")));
        out.put("managerAvatar", str(s.get("managerAvatar")));
        out.put("managerYears", str(s.get("managerYears")));
        out.put("managerSkills", str(s.get("managerSkills")));
        out.put("managerQuote", str(s.get("managerQuote")));
        out.put("facilities", storeFacilities(s.get("facilities")));
        out.put("inheritFacilities", inheritsFacilities(s.get("facilities")));
        out.put("travelRail", str(s.get("travelRail")));
        out.put("travelAirport", str(s.get("travelAirport")));
        out.put("travelCost", str(s.get("travelCost")));
        out.put("licenseImage", str(s.get("licenseImage")));
        out.put("dailyImages", parseImages(s.get("dailyImages")));
        out.put("facilityOptions", facilityItems(FACILITIES.keySet()));
        Map<String, Object> stay = fallbackStay(storeId);
        Map<String, Object> fb = new LinkedHashMap<String, Object>();
        fb.put("hasStay", Boolean.valueOf(stay != null));
        fb.put("stayName", stay == null ? "" : str(stay.get("stayName")));
        fb.put("managerName", stay == null ? "" : str(stay.get("hostName")));
        fb.put("facilities", stayFacilities(stay));
        fb.put("travelRail", stay == null ? "" : str(stay.get("travelRail")));
        fb.put("travelAirport", stay == null ? "" : str(stay.get("travelAirport")));
        fb.put("dailyCount", diaryPhotos(stay).size());
        out.put("fallback", fb);
        return out;
    }

    // ---------------------------------------------------------------- 保存

    /** 传了这一项（键在就算传了；null 当成清空） */
    private static boolean has(Map<String, Object> body, String key)
    {
        return body.containsKey(key);
    }

    /** 列表项：数组或逗号拼接的字符串都认 */
    static List<String> listOf(Object v)
    {
        List<String> out = new ArrayList<String>();
        if (v instanceof Collection)
        {
            for (Object o : (Collection<?>) v)
            {
                String t = str(o);
                if (!t.isEmpty() && !out.contains(t))
                {
                    out.add(t);
                }
            }
            return out;
        }
        return split(v);
    }

    /**
     * 校验并换算成要写的列（列名 → 值；没传的项不在里面、不动）。采访方式也在里面（interview_mode），认证的同步由 {@link #save} 处理。
     * 单测直接调（不查库的部分）；视频在不在磁盘上由 {@link #videoExists} 判断
     */
    Map<String, Object> columns(Map<String, Object> body)
    {
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        if (has(body, "interviewMode"))
        {
            String m = str(body.get("interviewMode"));
            if (!ONSITE.equals(m) && !ONLINE.equals(m) && !m.isEmpty())
            {
                throw new ServiceException("采访方式只能是 实地探访 / 线上采访 / 未采访");
            }
            cols.put("interview_mode", m);
        }
        if (has(body, "interviewVideo"))
        {
            String v = str(body.get("interviewVideo"));
            if (!v.isEmpty() && !(KgVideoUploads.isPlatformVideo(v) && videoExists(v)))
            {
                throw new ServiceException("采访视频要用这里上传的视频（找不到这个视频文件，请重新上传）");
            }
            cols.put("interview_video", v);
        }
        if (has(body, "interviewSeconds"))
        {
            Object raw = body.get("interviewSeconds");
            Integer sec = null;
            if (raw != null && !str(raw).isEmpty())
            {
                double d = KgVideoUploads.checkInterviewDuration(raw);
                // 多短都能传；不足 1 秒也按 1 秒存，0 会被页面当成没有时长
                sec = Integer.valueOf(Math.max(1, (int) Math.round(d)));
            }
            cols.put("interview_seconds", sec);
        }
        for (String[] f : IMAGE_FIELDS)
        {
            if (has(body, f[0]))
            {
                String v = str(body.get(f[0]));
                if (!v.isEmpty() && !isImage(v))
                {
                    throw new ServiceException(f[2] + "要用这里上传的图片");
                }
                cols.put(f[1], v);
            }
        }
        for (String[] f : TEXT_FIELDS)
        {
            if (has(body, f[0]))
            {
                String v = str(body.get(f[0]));
                KgValidate.maxLength(v, Integer.parseInt(f[2]), f[3]);
                cols.put(f[1], v);
            }
        }
        if (has(body, "inheritFacilities") && !(body.get("inheritFacilities") instanceof Boolean))
        {
            throw new ServiceException("沿用小院设施必须为布尔值");
        }
        if (Boolean.TRUE.equals(body.get("inheritFacilities")))
        {
            cols.put("facilities", "@inherit");
        }
        else if (has(body, "facilities"))
        {
            List<String> got = listOf(body.get("facilities"));
            for (String c : got)
            {
                if (!FACILITIES.containsKey(c))
                {
                    throw new ServiceException("不认识的亮点：" + c);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String c : storeFacilities(String.join(",", got)))
            {
                sb.append(sb.length() == 0 ? "" : ",").append(c);
            }
            cols.put("facilities", sb.length() == 0 ? "[]" : sb.toString());
        }
        else if (Boolean.FALSE.equals(body.get("inheritFacilities")))
        {
            throw new ServiceException("关闭沿用时请同时提交勾选的亮点");
        }
        if (has(body, "dailyImages"))
        {
            List<String> imgs = listOf(body.get("dailyImages"));
            if (imgs.size() > DAILY_MAX)
            {
                throw new ServiceException("店里日常最多 " + DAILY_MAX + " 张");
            }
            for (String u : imgs)
            {
                if (!isImage(u))
                {
                    throw new ServiceException("店里日常要用这里上传的图片");
                }
            }
            String json = imgs.isEmpty() ? "" : JSON.toJSONString(imgs);
            KgValidate.maxLength(json, 2000, "店里日常（图片地址合计）");
            cols.put("daily_images", json);
        }
        return cols;
    }

    boolean videoExists(String url)
    {
        return videos != null && videos.exists(url);
    }

    /**
     * 有采访视频就要有有效时长（2026-10-31 拍板）。只拒这次改动造成的「有视频、没时长」：换了视频没给秒数（不带这一项会留着旧视频的秒数，更不对）、把现有视频的秒数清空。
     * 放行：去掉视频（空视频 + 空秒数）；老数据本来就没存时长、视频也没换（门店设置页保存别的项时会原样带上这两项，不能因此存不上）。
     */
    static void requireVideoSeconds(Map<String, Object> current, Map<String, Object> cols)
    {
        String stored = str(current.get("interviewVideo"));
        String video = cols.containsKey("interview_video") ? str(cols.get("interview_video")) : stored;
        if (video.isEmpty() || cols.get("interview_seconds") != null)
        {
            return;
        }
        boolean replaced = !video.equals(stored);
        boolean cleared = cols.containsKey("interview_seconds") && current.get("interviewSeconds") != null;
        if (replaced || cleared)
        {
            throw new ServiceException("请提供有效的采访视频时长（秒数）", 422);
        }
    }

    /**
     * 保存后台「门店资料」。canCert = 当前后台账号有没有 kangaroo:store:cert（控制器里查好传进来）：
     * 采访方式改成 / 改掉实地探访等于认证 / 撤销认证，没有认证权限的拒绝。认证状态走门店服务的 certify（记认证时间、认证人，撤销时清掉）。
     * 返回保存后的资料（同 {@link #adminProfile}）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Long storeId, Map<String, Object> body, boolean canCert)
    {
        Map<String, Object> current = storeRow(storeId);
        if (current == null)
        {
            throw new ServiceException("门店不存在或已删除");
        }
        Map<String, Object> cols = columns(body == null ? new LinkedHashMap<String, Object>() : body);
        if (cols.containsKey("license_image"))
        {
            String license = str(cols.get("license_image"));
            if (!license.isEmpty() && !license.equals(str(current.get("licenseImage")))) KgLicenseWatermark.requirePublic(license);
        }
        requireVideoSeconds(current, cols);
        String before = mode(current.get("interviewMode"), current.get("safeCert"));
        if (cols.containsKey("interview_mode"))
        {
            String after = (String) cols.get("interview_mode");
            boolean certChange = !after.equals(before) && (ONSITE.equals(before) || ONSITE.equals(after));
            if (certChange)
            {
                if (!canCert)
                {
                    throw new ServiceException("改成或取消「实地探访」等于安心商家认证 / 撤销认证，需要门店认证权限", 403);
                }
                // 和认证接口同一个方法：safe_cert、认证时间、认证人一起改，采访方式也在里面同步（这里随后再写一次选定的值）
                stores.certify(storeId, ONSITE.equals(after));
            }
        }
        if (!cols.isEmpty())
        {
            cols.put("update_by", SecurityUtils.getUsername());
            cols.put("update_time", new java.util.Date());
            db.updateRow("kg_store", "store_id", storeId, cols);
        }
        return adminProfile(storeId);
    }
}
