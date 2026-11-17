package com.ruoyi.kangaroo.controller.app;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.JobCompensation;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.enums.JobStatus;
import com.ruoyi.kangaroo.enums.JobType;
import com.ruoyi.kangaroo.mapper.KgJobFavoriteMapper;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgJobService;
import com.ruoyi.kangaroo.service.IKgStoreService;

/**
 * 小程序端岗位接口。
 *
 * 浏览类接口允许未登录访问（对应登录页的"先逛逛"），
 * 收藏等与账号绑定的动作必须登录。
 */
@RestController
@RequestMapping("/app/job")
public class AppJobController
{
    @Autowired(required = false)
    private javax.servlet.http.HttpServletRequest request;
    @Autowired
    private IKgJobService kgJobService;

    @Autowired
    private IKgStoreService kgStoreService;

    @Autowired
    private KgJobFavoriteMapper kgJobFavoriteMapper;

    @Autowired
    private com.ruoyi.kangaroo.ext.user.KgStoreFollowService storeFollows;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgVolunteerKinds volunteerKinds;

    /** 发布岗位的年龄段、性格选项（V67）；不连库的单测里可以没有 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgJobRequirementOptions requirementOptions;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService onlineTasks;

    /** 城市归一（区县 → 所属城市、别名 → 字典名，如 平潭岛 → 平潭）；没有时按原样 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.user.KgCityService cityService;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.KgJdbc db;

    /** 岗位详情「写评价」按钮的报名编号（myReviewableAppId） */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.user.KgReviewEntryService reviewEntry;

    /** 岗位详情改版（2026-10-18「门店页」）：采访方式、采访视频、店长、亮点、交通参考、执照、店里日常；不连库的单测里可以没有 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgStoreProfiles storeProfiles;

    @Autowired
    private com.ruoyi.kangaroo.ext.stay.KgStayMediaService stayMedia;

    /** 「全职旅行」列表的培训活动横幅（/training-banner）；不连库的单测里可以没有 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.community.KgActivityService activities;

    /**
     * 换工类型（后台「字典管理」里的 kg_volunteer_kind，只列启用的）：[{value, label, desc}]。
     * 「换工换宿」列表的分类条、筛选页的「类型」、员工端发布岗位的「换工类型」都用它。
     */
    @AppAuth(required = false)
    @GetMapping("/kinds")
    public AjaxResult kinds()
    {
        return AjaxResult.success(volunteerKinds == null ? new java.util.ArrayList<Object>() : volunteerKinds.enabled());
    }

    /**
     * 发布岗位时「年龄」「性格」能选的选项（V67，2026-11-03 主人：「年龄选择年龄段，在后台管理配置」「加一个性格」）：
     * { ages: ["不限", "18-25岁", …], personalities: ["活泼开朗", …], personalityMax: 3 }，只列启用的、按排序。
     * 运营在后台「袋鼠小院 → 岗位要求选项」改；选中的文字写进岗位「要求」的行首，见 KgJobRequirementOptions。不用登录也能读（没有敏感内容）。
     */
    @AppAuth(required = false)
    @GetMapping("/requirement-options")
    public AjaxResult requirementOptions()
    {
        return AjaxResult.success(requirementOptions == null ? new java.util.LinkedHashMap<String, Object>() : requirementOptions.options());
    }

    /**
     * 岗位列表。
     *
     * @param type     岗位分类，对应 /pages/jobs/list?type=volunteer|parttime|fulltime|online
     * @param city     城市
     * @param keyword  标题关键词
     * @param workKind 换工类型（字典 kg_volunteer_kind 的值，如 photo）；传了只看换工岗位
     * @param salaryMin 期望日薪下限（元/天，时薪 / 周薪 / 月薪按国家口径折算），列表页「薪资」胶囊
     * @param salaryMax 期望日薪上限（元/天）
     * @param board    食宿：room 包住 / meal 包吃 / all 包吃包住 / self 不包食宿（2026-10-17），none 或不传 = 不限
     * @param skill    技能（技能词典的标签，如「摄影」）或线上任务类型（如「内容写作」，type=online 时按任务类型精确匹配），列表页「技能 / 任务类型」胶囊
     * @param minStay  能待的天数：只看最短停留不超过它的岗位，列表页「时间」胶囊
     * @param train    1 = 只看提供培训的岗位，列表页「培训」胶囊
     * @param monthBonus 1 = 只看「做满一个月有红包」的岗位（V44 kg_job.month_redpacket = '0'），列表页「满月红包」
     * @param cities   多个城市（逗号分隔，去重后最多 5 个），列表页城市面板；和 city 合在一起，命中其一（2026-10-16 第二轮改版，以下同）
     * @param gender   性别偏好 F 女 / M 男：列出写了这个性别或没写（男女不限）的岗位；只有换工换宿会写性别，不传 = 不筛
     * @param restModes 休息排班（逗号分隔，如 5-2,6-1，见 JobFilterFields.REST_MODES），命中其一；没填排班的岗位不算
     * @param roomTypes 住宿条件（逗号分隔，single / double / triple / multi），命中其一；没填房型的岗位不算
     * @param skills   技能需求（逗号分隔，如 做饭,拍摄），每个按技能词典展开同义词，命中其一；和 skill 合在一起
     * @param assured  1 = 只看「经袋鼠实地拍摄并含保险」：门店通过安心商家认证且岗位含保险，线上任务不算
     * @param payDay   兼职「日薪」档（2026-10-17，元/天）：起薪（salary_min，时 / 周 / 月薪按国家口径折成日薪）不低于它，见 JobTabFilters
     * @param payMonthMin 全职「月薪」档下限（元/月，含）：起薪折成月薪后不低于它
     * @param payMonthMax 全职「月薪」档上限（元/月，不含）：起薪折成月薪后低于它
     * @param categories 全职「类型」（逗号分隔：homestay 民宿 / hotel 酒店 / food 咖啡餐饮 / bar 酒吧 / other 其他），按门店名 + 岗位标题归类，命中其一
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数，最大 50
     */
    @AppAuth(required = false)
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String type,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String workKind,
            @RequestParam(required = false) Long salaryMin,
            @RequestParam(required = false) Long salaryMax,
            @RequestParam(required = false) String board,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) Integer minStay,
            @RequestParam(required = false) String train,
            @RequestParam(required = false) String monthBonus,
            @RequestParam(required = false) String cities,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String restModes,
            @RequestParam(required = false) String roomTypes,
            @RequestParam(required = false) String skills,
            @RequestParam(required = false) String assured,
            @RequestParam(required = false) Long payDay,
            @RequestParam(required = false) Long payMonthMin,
            @RequestParam(required = false) Long payMonthMax,
            @RequestParam(required = false) String categories,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize)
    {
        Map<String, Object> tab = new HashMap<String, Object>();
        String bathroom = request == null ? null : request.getParameter("bathroom");
        if (bathroom != null && !bathroom.isEmpty())
        {
            if (!java.util.Arrays.asList("private", "shared").contains(bathroom)) throw new ServiceException("卫浴筛选条件无效", 422);
            tab.put("bathroom", bathroom);
        }
        // 每个页签自己的条件（2026-10-17）：先校验，不认识的值 422，再和其余条件一起交给 KgJobMapper.selectAppJobList
        com.ruoyi.kangaroo.domain.JobTabFilters.putParams(tab, com.ruoyi.kangaroo.domain.JobTabFilters.pay(payDay),
                com.ruoyi.kangaroo.domain.JobTabFilters.pay(payMonthMin), com.ruoyi.kangaroo.domain.JobTabFilters.pay(payMonthMax),
                com.ruoyi.kangaroo.domain.JobTabFilters.categories(categories));
        return list(type, city, keyword, workKind, salaryMin, salaryMax, board, skill, minStay, train, monthBonus,
                cities, gender, restModes, roomTypes, skills, assured, tab, pageNum, pageSize);
    }

    /** 不带页签条件的列表（2026-10-17 之前的参数，不对外映射） */
    public AjaxResult list(String type, String city, String keyword, String workKind, Long salaryMin, Long salaryMax, String board,
            String skill, Integer minStay, String train, String monthBonus, String cities, String gender, String restModes,
            String roomTypes, String skills, String assured, int pageNum, int pageSize)
    {
        return list(type, city, keyword, workKind, salaryMin, salaryMax, board, skill, minStay, train, monthBonus,
                cities, gender, restModes, roomTypes, skills, assured, new HashMap<String, Object>(), pageNum, pageSize);
    }

    private AjaxResult list(String type, String city, String keyword, String workKind, Long salaryMin, Long salaryMax, String board,
            String skill, Integer minStay, String train, String monthBonus, String cities, String gender, String restModes,
            String roomTypes, String skills, String assured, Map<String, Object> tabParams, int pageNum, int pageSize)
    {
        if (StringUtils.isNotEmpty(type) && !JobType.exists(type))
        {
            throw new ServiceException("岗位分类不合法");
        }
        if (pageNum < 1)
        {
            pageNum = 1;
        }
        if (pageSize < 1 || pageSize > 50)
        {
            pageSize = 10;
        }

        KgJob query = new KgJob();
        query.setJobType(StringUtils.isEmpty(type) ? null : type);
        // 城市先归一成业务城市名，再按「等于或以城市 / 别名结尾」匹配（KgJobCity，KgJobMapper.selectAppJobList 的 cityLikes / cityExacts）：
        // 选「平潭」带出写成「平潭岛」「福建平潭」的岗位，选「大连」不再带出「五大连池」
        query.setCity(appCity(city));
        Map<String, Object> params = new HashMap<String, Object>();
        // 城市面板多选（cities）和老的单个 city 合在一起：每个城市各自归一、展开写法，合成一组「命中其一」
        java.util.List<String> terms = new java.util.ArrayList<String>();
        for (String c : appCities(query.getCity(), cities))
        {
            for (String t : cityTerms(c))
            {
                if (!terms.contains(t))
                {
                    terms.add(t);
                }
            }
        }
        if (!terms.isEmpty())
        {
            com.ruoyi.kangaroo.ext.user.KgJobCity.putMapperParams(params, terms);
        }
        // 关键词：规则拆词 + 「海边 / 一二线 / 小众安静」词表（KgJobKeyword），写法里的 % _ \ 按字面找（之前搜「%」会列出全部岗位）
        java.util.List<Map<String, Object>> keywordTerms = com.ruoyi.kangaroo.ext.user.KgJobKeyword.terms(keyword, cityService);
        if (!keywordTerms.isEmpty())
        {
            params.put("keywordTerms", keywordTerms);
        }
        // 换工类型：按 work_kind 精确匹配；选「其他」时，还没选类型的换工岗位也列进来（KgJobMapper.selectAppJobList）。
        // 之前没选类型的老岗位按类型名称 / 说明里的词模糊匹配标题，会把不相干的岗位归进某一类，「其他」反而是空的
        String kind = workKind == null ? "" : workKind.trim();
        if (!kind.isEmpty())
        {
            if (!com.ruoyi.kangaroo.ext.core.KgVolunteerKinds.validValue(kind))
            {
                throw new ServiceException("换工类型不合法", 422);
            }
            params.put("workKind", kind);
            params.put("workKindUntyped", Boolean.valueOf(com.ruoyi.kangaroo.ext.core.KgVolunteerKinds.includesUntyped(java.util.Collections.singletonList(kind))));
        }
        putChipFilters(params, salaryMin, salaryMax, board, skill, minStay, train, JobType.ONLINE.equals(type));
        if ("1".equals(monthBonus) || "true".equals(monthBonus))
        {
            params.put("monthBonus", Boolean.TRUE);
        }
        putPanelFilters(params, gender, restModes, roomTypes, skills, assured);
        params.putAll(tabParams);
        query.setParams(params);

        PageHelper.startPage(pageNum, pageSize);
        List<KgJob> list = kgJobService.selectAppJobList(query);
        PageInfo<KgJob> page = new PageInfo<KgJob>(list);

        Set<Long> favIds = currentFavoriteIds();

        AjaxResult ajax = AjaxResult.success();
        ajax.put("rows", toListVO(list, favIds));
        ajax.put("total", Long.valueOf(page.getTotal()));
        ajax.put("hasMore", Boolean.valueOf(page.isHasNextPage()));
        return ajax;
    }

    /** 不带筛选胶囊条件的列表（老调用方式，不对外映射） */
    public AjaxResult list(String type, String city, String keyword, String workKind, int pageNum, int pageSize)
    {
        return list(type, city, keyword, workKind, null, null, null, null, null, null, null, pageNum, pageSize);
    }

    /** 不带筛选面板条件的列表（2026-10-16 之前的参数，不对外映射） */
    public AjaxResult list(String type, String city, String keyword, String workKind, Long salaryMin, Long salaryMax, String board,
            String skill, Integer minStay, String train, String monthBonus, int pageNum, int pageSize)
    {
        return list(type, city, keyword, workKind, salaryMin, salaryMax, board, skill, minStay, train, monthBonus,
                null, null, null, null, null, null, pageNum, pageSize);
    }

    /** 城市面板最多选几个城市（和小程序城市面板的上限一致） */
    static final int MAX_CITIES = 5;

    /**
     * 列表的城市条件：老的单个 city（已归一）+ 城市面板的 cities（逗号分隔），逐个按 appCity 归一（思明区 → 厦门、平潭岛 → 平潭），
     * 去重；「全部城市」/ 空的跳过。cities 去重后超过 5 个直接提示，不悄悄丢掉
     */
    java.util.List<String> appCities(String city, String cities)
    {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (city != null)
        {
            out.add(city);
        }
        if (cities == null || cities.trim().isEmpty())
        {
            return out;
        }
        java.util.List<String> picked = new java.util.ArrayList<String>();
        for (String part : cities.split("[,，]"))
        {
            String c = appCity(part);
            if (c != null && !picked.contains(c))
            {
                picked.add(c);
            }
        }
        if (picked.size() > MAX_CITIES)
        {
            throw new ServiceException("城市最多选 " + MAX_CITIES + " 个", 422);
        }
        for (String c : picked)
        {
            if (!out.contains(c))
            {
                out.add(c);
            }
        }
        return out;
    }

    /** 技能需求最多选几个（筛选面板 4 个选项，留些余量） */
    static final int MAX_SKILLS = 8;

    /**
     * 岗位列表筛选面板的条件（2026-10-16 第二轮改版，取值白名单见 JobFilterFields）：性别偏好、休息排班、住宿条件、技能多选、安心保障。
     * 不认识的值直接提示（422），不悄悄忽略（忽略了用户会以为筛过了）。
     * 线上任务没有排班、住宿、保险：线上任务列表选了这几项就一个都不会出来，照样按条件筛，不放开
     */
    static void putPanelFilters(Map<String, Object> params, String gender, String restModes, String roomTypes, String skills, String assured)
    {
        String g = com.ruoyi.kangaroo.domain.JobFilterFields.gender(gender);
        if (g != null && !g.isEmpty())
        {
            params.put("gender", g);
        }
        java.util.List<String> rest = com.ruoyi.kangaroo.domain.JobFilterFields.codes(com.ruoyi.kangaroo.domain.JobFilterFields.REST_MODES, restModes, "休息排班");
        if (!rest.isEmpty())
        {
            params.put("restModes", rest);
        }
        java.util.List<String> rooms = com.ruoyi.kangaroo.domain.JobFilterFields.codes(com.ruoyi.kangaroo.domain.JobFilterFields.ROOM_TYPES, roomTypes, "住宿条件");
        if (!rooms.isEmpty())
        {
            params.put("roomTypes", rooms);
        }
        java.util.List<String> picked = new java.util.ArrayList<String>();
        for (String part : (skills == null ? "" : skills).split("[,，]"))
        {
            String sk = part.trim();
            if (sk.isEmpty() || picked.contains(sk))
            {
                continue;
            }
            if (!sk.matches("[\u4e00-\u9fa5A-Za-z0-9/ ]{1,32}"))
            {
                throw new ServiceException("技能条件不合法", 422);
            }
            picked.add(sk);
        }
        if (picked.size() > MAX_SKILLS)
        {
            throw new ServiceException("技能最多选 " + MAX_SKILLS + " 个", 422);
        }
        if (!picked.isEmpty())
        {
            // 和单选的 skill 合在一起，命中任一个词就算（KgJobMapper.selectAppJobList 的 skillWords）
            @SuppressWarnings("unchecked")
            java.util.List<String> words = params.get("skillWords") instanceof java.util.List
                    ? new java.util.ArrayList<String>((java.util.List<String>) params.get("skillWords")) : new java.util.ArrayList<String>();
            for (String w : com.ruoyi.kangaroo.ext.user.KgSkills.wordsOfAll(picked))
            {
                if (!words.contains(w))
                {
                    words.add(w);
                }
            }
            if (!words.isEmpty())
            {
                params.put("skillWords", words);
            }
        }
        if ("1".equals(assured) || "true".equals(assured))
        {
            params.put("assured", Boolean.TRUE);
        }
    }

    /**
     * 列表页的城市条件：去掉首尾空白和 like 通配符，归一成业务城市名（思明区 → 厦门、平潭岛 → 平潭）。
     * 空串 / 「全部城市」= 不限
     */
    String appCity(String city)
    {
        String c = city == null ? "" : city.replace("%", "").replace("_", "").trim();
        if (c.isEmpty() || "全部城市".equals(c))
        {
            return null;
        }
        if (c.length() > 32)
        {
            throw new ServiceException("城市名称不合法", 422);
        }
        String n = cityService == null ? c : cityService.businessCity(c);
        return n == null || n.trim().isEmpty() ? c : n.trim();
    }

    /** 选中城市（已归一）的全部写法，列表和下拉数量共用（KgJobCity） */
    java.util.List<String> cityTerms(String city)
    {
        return com.ruoyi.kangaroo.ext.user.KgJobCity.terms(cityService, city);
    }

    /**
     * 岗位上的城市写法 → 下拉里显示的名字：先按 KgCityService.jobCity 归到字典城市（云南大理 → 大理、平潭岛 → 平潭），
     * 再走一遍 appCity，保证这个名字传回列表接口时查的就是它自己。太长的写法（超过 32 个字）不进下拉
     */
    String jobCityName(String raw)
    {
        String c = raw == null ? "" : raw.trim();
        if (c.isEmpty() || c.length() > 32)
        {
            return null;
        }
        String n = cityService == null ? null : cityService.jobCity(c);
        return appCity(n == null || n.trim().isEmpty() ? c : n);
    }

    /**
     * 有岗位在招的城市（2026-10-16 列表页城市下拉的「有岗位的城市」一组）：[{name, count}]，按岗位数从多到少。
     * 口径和列表一致：已上架 / 暂停、门店启用；岗位上的城市写法归一成业务城市名后合并（平潭岛、平潭 都算「平潭」，云南大理 算「大理」），
     * 每个城市的数量 = 选中它时列表查出来的岗位数。
     * 线上任务全国可接，不算进任何城市。type 不传 = 所有到店岗位
     */
    @AppAuth(required = false)
    @GetMapping("/cities")
    public AjaxResult cities(@RequestParam(required = false) String type)
    {
        if (StringUtils.isNotEmpty(type) && !JobType.exists(type))
        {
            throw new ServiceException("岗位分类不合法");
        }
        List<Map<String, Object>> out = new java.util.ArrayList<Map<String, Object>>();
        if (db == null || JobType.ONLINE.equals(type))
        {
            return AjaxResult.success(out);
        }
        Map<String, Object> ps = com.ruoyi.kangaroo.ext.KgJdbc.p();
        String typeCond = "";
        if (StringUtils.isNotEmpty(type))
        {
            typeCond = " and j.job_type = :t";
            ps.put("t", type);
        }
        // 岗位上原样的城市写法 → 岗位数
        java.util.LinkedHashMap<String, Long> raws = new java.util.LinkedHashMap<String, Long>();
        for (Map<String, Object> r : db.list("select j.city, count(*) cnt from kg_job j where j.del_flag = '0' and j.status in ('published', 'paused') "
                + "and j.job_type <> 'online'" + typeCond + " and exists (select 1 from kg_store st where st.store_id = j.store_id and st.status = '0' and st.del_flag = '0') "
                + "group by j.city", ps))
        {
            String raw = com.ruoyi.kangaroo.ext.KgJdbc.asStr(r.get("city"));
            Long n = com.ruoyi.kangaroo.ext.KgJdbc.asLong(r.get("cnt"));
            raws.merge(raw == null ? "" : raw.trim(), n == null ? 0L : n, Long::sum);
        }
        // 下拉里的城市名：归一成业务城市名，「云南大理」这种带省份的写法归到它结尾的字典城市「大理」（KgCityService.jobCity）
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        for (String raw : raws.keySet())
        {
            String name = jobCityName(raw);
            if (name != null && !"不限".equals(name))
            {
                names.add(name);
            }
        }
        // 数量按列表同一口径数（KgJobCity.matches，和 KgJobMapper.selectAppJobList 是同一组写法、同一条规则）：
        // 选中这个城市时列表查「等于或以城市 / 别名结尾」，所以 大理 = 大理 + 云南大理，平潭 = 平潭 + 平潭岛 + 福建平潭。
        // 之前按「包含」数，「大连」会把「五大连池」也算进去，和点进去的列表对不上
        for (String name : names)
        {
            java.util.List<String> terms = cityTerms(name);
            long count = 0;
            for (Map.Entry<String, Long> e : raws.entrySet())
            {
                count += com.ruoyi.kangaroo.ext.user.KgJobCity.matches(e.getKey(), terms) ? e.getValue() : 0L;
            }
            if (count == 0)
            {
                continue;  // 只写了区县的岗位（思明区 → 厦门）列表查不出来，下拉里不给一个点进去为空的城市
            }
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("name", name);
            m.put("count", count);
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
        return AjaxResult.success(out);
    }

    /**
     * 列表页筛选胶囊的条件（2026-10-11）。之前列表只认城市 / 关键词 / 换工类型，点「薪资 / 技能 / 包住」都跳去大筛选页；
     * 现在每个胶囊在列表页直接选、直接生效，条件放进 params 交给 KgJobMapper.selectAppJobList。
     * 不认识的值直接提示，不悄悄忽略（忽略了用户会以为筛过了）
     */
    static void putChipFilters(Map<String, Object> params, Long salaryMin, Long salaryMax, String board, String skill, Integer minStay, String train)
    {
        putChipFilters(params, salaryMin, salaryMax, board, skill, minStay, train, false);
    }

    /**
     * online = 线上任务列表：skill 是「任务类型」胶囊（内容写作 / 社群运营…），按发布时选的任务类型精确匹配，
     * 和「全部筛选」页同一口径（KgUserHubService.appendJobTypes）。之前也走技能词典展开同义词，
     * 选「社群运营」会带出标题里有「公众号」「小红书」的图片设计、内容写作任务，和全部筛选页对不上
     */
    static void putChipFilters(Map<String, Object> params, Long salaryMin, Long salaryMax, String board, String skill, Integer minStay, String train, boolean online)
    {
        if (salaryMin != null && salaryMin > 0)
        {
            params.put("salaryMin", Long.valueOf(Math.min(salaryMin, 100000L)));
        }
        if (salaryMax != null && salaryMax > 0)
        {
            params.put("salaryMax", Long.valueOf(Math.min(salaryMax, 100000L)));
        }
        // 食宿：none / 空 = 不限；2026-10-17 多了 self「不包食宿」（兼职、全职页签），取值和文案见 JobTabFilters.BOARDS
        String b = com.ruoyi.kangaroo.domain.JobTabFilters.board(board);
        if (b != null)
        {
            params.put("board", b);
        }
        String sk = skill == null ? "" : skill.trim();
        if (!sk.isEmpty())
        {
            if (!sk.matches("[\u4e00-\u9fa5A-Za-z0-9/ ]{1,32}"))
            {
                throw new ServiceException("技能条件不合法", 422);
            }
            if (online)
            {
                // 选「其他」时，没有任务类型的老线上岗位（后台岗位管理直接建的）也算进来，和全部筛选页一致
                params.put("taskType", sk);
                params.put("taskTypeUntyped", Boolean.valueOf("其他".equals(sk)));
            }
            else
            {
                List<String> words = com.ruoyi.kangaroo.ext.user.KgSkills.wordsOf(sk);
                if (!words.isEmpty())
                {
                    params.put("skillWords", words);
                }
            }
        }
        if (minStay != null && minStay > 0)
        {
            params.put("minStay", Integer.valueOf(Math.min(minStay, 3650)));
        }
        if ("1".equals(train) || "true".equals(train))
        {
            params.put("train", Boolean.TRUE);
        }
    }

    /** 店里日常：按岗位真实所属门店取数据，游客可看，关闭 / 停用后与详情页一样不可访问。 */
    @AppAuth(required = false)
    @GetMapping("/{jobId}/diary")
    public AjaxResult diary(@PathVariable Long jobId, @RequestParam(defaultValue = "1") int page)
    {
        KgJob job = kgJobService.requireJob(jobId);
        KgStore publisher = kgStoreService.selectKgStoreByStoreId(job.getStoreId());
        KgStore store = job.getRecruitStoreId() == null ? publisher : kgStoreService.selectKgStoreByStoreId(job.getRecruitStoreId());
        if (!JobStatus.visibleToApp(job.getStatus()) || publisher == null || !"0".equals(publisher.getStatus()) || store == null || !"0".equals(store.getStatus()))
            throw new ServiceException("岗位不存在或已下架");
        List<String> photos = new java.util.ArrayList<String>(com.ruoyi.kangaroo.domain.JobImages.read(job.getImages()));
        if (photos.isEmpty() && com.ruoyi.kangaroo.domain.JobImages.isUpload(job.getCover())) photos.add(job.getCover());
        return AjaxResult.success(stayMedia.storeDiary(job.getDisplayStoreId(), photos, page));
    }

    /** 岗位详情 */
    @AppAuth(required = false)
    @GetMapping("/{jobId}")
    public AjaxResult detail(@PathVariable Long jobId)
    {
        KgJob job = kgJobService.requireJob(jobId);
        KgStore publisher = kgStoreService.selectKgStoreByStoreId(job.getStoreId());
        KgStore store = job.getRecruitStoreId() == null ? publisher : kgStoreService.selectKgStoreByStoreId(job.getRecruitStoreId());
        // 门店停用/删除后，其岗位对小程序一律不可见；提示语与"岗位已下架"保持一致，
        // 避免通过错误信息区分出"岗位存在但门店被停用"这种内部状态
        boolean favored = currentFavoriteIds().contains(job.getJobId());
        boolean ended = false;
        if (!JobStatus.visibleToApp(job.getStatus()) || publisher == null || !"0".equals(publisher.getStatus()) || store == null || !"0".equals(store.getStatus()))
        {
            // 2026-11-09 验收：岗位关了（或门店停了），报过名或收藏过的人还能只读打开，显示「已结束招聘」、不能报名；草稿和无关的人照旧不可见
            if (JobStatus.DRAFT.equals(job.getStatus()) || !(favored || appliedBefore(job.getJobId())))
            {
                throw new ServiceException("岗位不存在或已下架");
            }
            ended = true;
        }

        Map<String, Object> data = jobToMap(job, favored);
        attachMyApplications(java.util.Collections.singletonList(data));
        // 线上任务过了截止时间、或进行中的接单人数已满（线上任务的 hiredCount 就是这个数，见 KgJobMapper.selectKgJobVo）就不能再报名，
        // 和提交报名、门店通过接单同一口径。之前只看岗位状态，详情页「报名申请」照样能点，填完表到最后一步才报错。
        // applyClosedReason：不能报名时给用户看的原因（能报名时为 null）
        String closedReason = null;
        if (JobType.ONLINE.equals(job.getJobType()))
        {
            if (onlineTasks != null && onlineTasks.deadlinePassed(job.getJobId()))
            {
                closedReason = "这个任务已过截止时间，不再接单";
            }
        }
        if (closedReason == null && job.getRemainingCount() != null && job.getRemainingCount() == 0)
        {
            closedReason = JobType.ONLINE.equals(job.getJobType())
                    ? "这个任务的名额已满，暂不接受报名" : "该岗位名额已满，暂不接受报名";
        }
        if (ended)
        {
            closedReason = "该岗位已结束招聘，不再接受报名";
        }
        boolean accepts = !ended && JobStatus.acceptsApplication(job.getStatus()) && closedReason == null;
        // ended：只读查看（岗位已关或门店已停），页面写「已结束招聘」
        data.put("ended", Boolean.valueOf(ended));
        if (ended) com.ruoyi.kangaroo.ext.user.KgJobDisplayOrder.decorate(data, 0, "closed", null);
        data.put("acceptsApplication", Boolean.valueOf(accepts));
        data.put("applyClosedReason", accepts ? null : (closedReason != null ? closedReason
                : (JobStatus.PAUSED.equals(job.getStatus()) ? "该岗位暂停招聘中，暂不接受报名" : "该岗位当前不接受报名")));
        data.put("content", job.getContent());
        data.put("requirement", job.getRequirement());
        data.put("settleDesc", job.getSettleDesc());
        data.put("shiftDesc", job.getShiftDesc());
        data.put("boardDesc", job.getBoardDesc());
        // 餐补、宠物、住宿照片和视频（V66）：只有详情带，列表不变大；读的时候按岗位类型和食宿收口（老版本把食宿改成包吃后，库里的餐补留着但不显示）
        data.putAll(com.ruoyi.kangaroo.domain.JobExtras.view(job.getJobType(), job.getBoardType(), job.getMealAllowance(), job.getMealAllowanceUnit(),
                job.getPetStatus(), job.getPetImages(), job.getLodgingImages(), job.getLodgingVideo(), job.getLodgingVideoCover()));
        if (store != null)
        {
            Map<String, Object> s = new HashMap<String, Object>();
            s.put("storeId", store.getStoreId());
            s.put("storeName", store.getStoreName());
            s.put("city", store.getCity());
            s.put("address", store.getAddress());
            s.put("intro", store.getIntro());
            s.put("cover", store.getCover());
            s.put("safeCert", store.getSafeCert());
            s.put("rating", store.getRating());
            s.put("reviewCount", store.getReviewCount());
            // 是否已关注这家门店（游客为 false）：详情页门店卡片上的「关注 / 已关注」按它显示，关注后这家店上新会通知你
            s.put("following", Boolean.valueOf(storeFollows.following(AppUserContext.currentUserId(), store.getStoreId())));
            // 门店电话属于签约后才解锁的信息，详情页不下发
            data.put("store", s);
        }
        // 2026-10-16 内测反馈 2.0：「评价」页签顶部「我在这家店做过，写评价」按钮——当前用户在这家店已入职、还没评价的报名编号，没有为 null
        if(JobType.ONLINE.equals(job.getJobType()) && onlineTasks!=null) {
            Map<String,Object> reward=onlineTasks.rewardTerms(jobId);data.putAll(reward);
            if(reward.get("rewardText")!=null)data.put("compensationText",reward.get("rewardText"));
        }
        data.put("myReviewableAppId", reviewEntry == null ? null : reviewEntry.myReviewableAppId(AppUserContext.currentUserId(), job.getDisplayStoreId()));
        // 2026-10-18 岗位详情改版：线下岗位多给一份门店页资料（storePage，门店没填的项用这家店小院的资料兜底，见 KgStoreProfiles.page）；线上任务照旧的样子，给 null
        data.put("storePage", storeProfiles == null || JobType.ONLINE.equals(job.getJobType()) ? null : storeProfiles.storePage(job.getDisplayStoreId()));
        return AjaxResult.success(data);
    }

    /**
     * 「全职旅行」列表顶上的培训活动横幅（2026-10-18）：后台「首页内容管理」栏目选「全职培训横幅」的那场活动，
     * cities 是列表里已选的城市（逗号分隔，最多 5 个，区县 / 别名先归一成业务城市）。没有就给 null，页面不出横幅。
     * 字段和活动卡片一样（activityId、title、img、dateText、timeText、city、place、feeFen…），点了去活动详情报名
     */
    @AppAuth(required = false)
    @GetMapping("/training-banner")
    public AjaxResult trainingBanner(@RequestParam(required = false) String cities)
    {
        if (activities == null)
        {
            return AjaxResult.success((Object) null);
        }
        List<String> list = new java.util.ArrayList<String>();
        for (String raw : StringUtils.isEmpty(cities) ? new String[0] : cities.split(","))
        {
            String c = raw.trim();
            if (c.isEmpty() || c.length() > 32 || list.size() >= 5)
            {
                continue;
            }
            list.add(cityService == null ? c : cityService.businessCity(c));
        }
        return AjaxResult.success(activities.trainingBanner(list));
    }

    /** 我的收藏岗位 */
    @AppAuth
    @GetMapping("/favorites")
    public AjaxResult favorites()
    {
        Long userId = AppUserContext.getUserId();
        List<Long> jobIds = kgJobFavoriteMapper.selectJobIdsByUser(userId);
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        if (jobIds != null)
        {
            for (Long jobId : jobIds)
            {
                KgJob job = kgJobService.selectKgJobByJobId(jobId);
                // 查询已排除删除的岗位
                if (job == null || JobStatus.DRAFT.equals(job.getStatus()))
                {
                    continue;
                }
                // 2026-11-09 验收：岗位关了也留在收藏里（之前直接消失，想取消收藏都找不到），写「已结束招聘」，点进去只读
                Map<String, Object> row = jobToMap(job, true);
                boolean ended = !JobStatus.visibleToApp(job.getStatus()) || !storesActive(job);
                row.put("ended", Boolean.valueOf(ended));
                if (ended)
                {
                    com.ruoyi.kangaroo.ext.user.KgJobDisplayOrder.decorate(row, 0, "closed", null);
                    row.put("statusText", "已结束招聘");
                }
                else if (JobStatus.PAUSED.equals(job.getStatus()))
                {
                    row.put("statusText", "已暂停招聘");
                }
                rows.add(row);
            }
        }
        return AjaxResult.success(rows);
    }

    /** 收藏 / 取消收藏（幂等：重复收藏不报错，重复取消也不报错） */
    @AppAuth
    @PostMapping("/favorite/{jobId}")
    public AjaxResult favorite(@PathVariable Long jobId, @RequestParam boolean favorite)
    {
        Long userId = AppUserContext.getUserId();
        if (!favorite)
        {
            // 取消收藏一律成功：岗位关了、门店停了、甚至删了，都要能从收藏里拿掉
            kgJobFavoriteMapper.deleteByUserAndJob(userId, jobId);
            Map<String, Object> data = new HashMap<String, Object>();
            data.put("favorite", Boolean.FALSE);
            return AjaxResult.success(data);
        }
        KgJob job = kgJobService.requireJob(jobId);
        if (!JobStatus.visibleToApp(job.getStatus()) || !recruitStoreActive(job))
        {
            throw new ServiceException("岗位不存在或已下架");
        }
        if (favorite)
        {
            com.ruoyi.kangaroo.domain.KgJobFavorite fav = new com.ruoyi.kangaroo.domain.KgJobFavorite();
            fav.setAppUserId(userId);
            fav.setJobId(jobId);
            kgJobFavoriteMapper.insertIgnore(fav);
        }
        else
        {
            kgJobFavoriteMapper.deleteByUserAndJob(userId, jobId);
        }
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("favorite", Boolean.valueOf(favorite));
        return AjaxResult.success(data);
    }

    // ---------- 内部 ----------

    /** 当前登录用户报过这个岗位（任何状态，含已撤回 / 已结束）；游客为 false */
    private boolean appliedBefore(Long jobId)
    {
        Long userId = AppUserContext.currentUserId();
        if (userId == null || db == null)
        {
            return false;
        }
        return db.count("select count(1) from kg_application where app_user_id = :u and job_id = :j and del_flag = '0'",
                com.ruoyi.kangaroo.ext.KgJdbc.p("u", userId, "j", jobId)) > 0;
    }

    private Set<Long> currentFavoriteIds()
    {
        Set<Long> ids = new HashSet<Long>();
        AppLoginUser loginUser = AppUserContext.get();
        if (loginUser == null)
        {
            return ids;
        }
        List<Long> jobIds = kgJobFavoriteMapper.selectJobIdsByUser(loginUser.getUserId());
        if (jobIds != null)
        {
            ids.addAll(jobIds);
        }
        return ids;
    }

    private List<Map<String, Object>> toListVO(List<KgJob> list, Set<Long> favIds)
    {
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        if (list == null)
        {
            return rows;
        }
        for (KgJob job : list)
        {
            rows.add(jobToMap(job, favIds.contains(job.getJobId())));
        }
        attachMyApplications(rows);
        return rows;
    }

    /**
     * 统一岗位出参。
     *
     * salaryText 由服务端拼好：列表页对日薪显示"120 元/天"、月薪显示"6K-8K/月"、
     * 计件显示"80-150 元/篇"，口径集中在后端，避免每个页面各拼各的。
     */
    /** 发布门店和招人门店都在营业（和详情页同一口径） */
    private boolean storesActive(KgJob job)
    {
        KgStore publisher = kgStoreService.selectKgStoreByStoreId(job.getStoreId());
        return publisher != null && "0".equals(publisher.getStatus()) && recruitStoreActive(job);
    }

    private boolean recruitStoreActive(KgJob job)
    {
        if (job.getRecruitStoreId() == null) return true;
        KgStore store = kgStoreService.selectKgStoreByStoreId(job.getRecruitStoreId());
        return store != null && "0".equals(store.getStatus());
    }

    /** One batch query, scoped to the current account; never expose another applicant. */
    private void attachMyApplications(List<Map<String, Object>> rows)
    {
        Long uid = AppUserContext.currentUserId();
        if (uid == null || db == null || rows.isEmpty()) return;
        List<Object> ids = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) if ("online".equals(row.get("jobType"))) ids.add(row.get("jobId"));
        if (ids.isEmpty()) return;
        List<Map<String, Object>> applications = db.list("select a.app_id, a.job_id, a.status, "
                + "(select s.status from kg_online_settlement s where s.app_id=a.app_id limit 1) settlement, "
                + "(select x.status from kg_online_submission x where x.app_id=a.app_id order by x.round desc limit 1) submission "
                + "from kg_application a where a.app_user_id=:u and a.job_id in (:jobs) and a.del_flag='0' "
                + "and a.status not in ('withdrawn','review_rejected','terminated') order by a.app_id desc",
                com.ruoyi.kangaroo.ext.KgJdbc.p("u", uid, "jobs", ids));
        Map<String, Map<String, Object>> latest = new HashMap<>();
        for (Map<String, Object> app : applications) latest.putIfAbsent(String.valueOf(app.get("jobId")), app);
        for (Map<String, Object> row : rows)
        {
            Map<String, Object> app = latest.get(String.valueOf(row.get("jobId")));
            if (app == null) continue;
            boolean pending = "submitted".equals(app.get("status"));
            Map<String, Object> own = new java.util.LinkedHashMap<>();
            own.put("appId", app.get("appId")); own.put("status", app.get("status"));
            own.put("label", pending ? "审核中 · 查看进度" : app.get("settlement") != null ? "查看结算" : "submitted".equals(app.get("submission")) ? "待验收 · 查看成果" : "提交成果");
            own.put("path", (pending ? "/pages/jobs/progress?appId=" : "/pages/jobs/deliver?appId=") + app.get("appId"));
            row.put("myApplication", own);
        }
    }

    private Map<String, Object> jobToMap(KgJob job, boolean favorite)
    {
        Map<String, Object> m = new HashMap<String, Object>();
        com.ruoyi.kangaroo.ext.user.KgJobDisplayOrder.decorate(m, job.getUrgentLevel(), job.getStatus(), job.getUrgentDeadline());
        m.put("jobId", job.getJobId());
        m.put("storeId", job.getStoreId());
        m.put("recruitStoreId", job.getRecruitStoreId());
        m.put("storeName", job.getRecruitStoreId() == null ? job.getStoreName() : job.getRecruitStoreName());
        m.put("jobType", job.getJobType());
        // 换工类型：列表卡片和详情页显示成小标签；没选类型（老岗位、非换工岗位）时为空串
        String kind = job.getWorkKind() == null ? "" : job.getWorkKind();
        m.put("workKind", kind);
        m.put("workKindLabel", volunteerKinds == null || kind.isEmpty() ? "" : volunteerKinds.label(kind));
        m.put("title", job.getTitle());
        // 岗位照片（V49，最多 9 张，可能是空数组）：详情页顶部的图集；列表卡片、详情头图的封面 cover = 第一张照片，
        // 没有照片时用岗位原来的封面（发布时抄的门店封面），再没有用门店封面
        List<String> images = com.ruoyi.kangaroo.domain.JobImages.read(job.getImages());
        m.put("images", images);
        m.put("cover", com.ruoyi.kangaroo.domain.JobImages.cover(images, job.getCover(), job.getRecruitStoreId() == null ? job.getStoreCover() : job.getRecruitStoreCover()));
        m.put("city", job.getCity());
        m.put("address", job.getAddress());
        // 工作地点的地图坐标（V42，GCJ-02）：详情页有坐标时点「工作地点」打开地图；没选过点为 null，页面照旧只显示文字
        m.put("latitude", job.getLatitude());
        m.put("longitude", job.getLongitude());
        m.put("salaryType", job.getSalaryType());
        m.put("salaryMin", job.getSalaryMin());
        m.put("salaryMax", job.getSalaryMax());
        m.put("salaryUnit", job.getSalaryUnit());
        m.put("salaryText", salaryText(job));
        m.put("bonusDesc", job.getBonusDesc());
        m.put("commissionDesc", job.getCommissionDesc());
        m.put("compensationText", JobCompensation.summary(salaryText(job), job.getBonusDesc(), job.getCommissionDesc()));
        m.put("startDate", job.getStartDate());
        m.put("minDays", job.getMinDays());
        m.put("dailyHours", job.getDailyHours());
        m.put("shiftDesc", job.getShiftDesc());
        m.put("shiftTime", job.getShiftTime());
        m.put("trainDesc", job.getTrainDesc());
        m.put("probation", job.getProbation());
        m.put("benefits", splitTags(job.getBenefits()));
        m.put("deliverDesc", job.getDeliverDesc());
        m.put("estHours", job.getEstHours());
        m.put("remoteFlag", job.getRemoteFlag());
        // 发单方：员工在小程序里发布的岗位 / 任务，publisher_name 记的是发布员工自己的微信昵称（内部留痕），
        // 不能当发单方公开给所有人看，改用门店名（和详情页、全部筛选结果一致）；后台「发单方」手填的照旧
        m.put("publisherName", "staff".equals(job.getSource()) || StringUtils.isEmpty(job.getPublisherName()) ? job.getStoreName() : job.getPublisherName());
        m.put("boardType", job.getBoardType());
        m.put("boardText", boardText(job.getBoardType()));
        m.put("minDaysText", minDaysText(job.getMinDays()));
        // 岗位筛选条件（V48，门店选填，'' = 没填）：性别偏好只对换工换宿（F 女 / M 男 / '' 男女不限）；
        // 休息排班、住宿条件带上文案（做五休二、单人间），列表卡片和详情页直接显示，不用各页面各写一套映射
        String rest = job.getRestMode() == null ? "" : job.getRestMode();
        String room = job.getRoomType() == null ? "" : job.getRoomType();
        m.put("genderPref", com.ruoyi.kangaroo.domain.JobFilterFields.genderFor(job.getJobType(), job.getGenderPref()));
        m.put("restMode", rest);
        m.put("restModeLabel", com.ruoyi.kangaroo.domain.JobFilterFields.restModeLabel(rest));
        m.put("roomType", room);
        m.put("roomTypeLabel", com.ruoyi.kangaroo.domain.JobFilterFields.roomTypeLabel(room));
        // 安心商家认证（门店 safe_cert：0 已认证）和含保险（insurance_flag：0 含）每类岗位都下发：
        // 两个都是 0 的线下岗位，列表卡片和详情页挂「经袋鼠实地拍摄并含保险」（线上任务不挂，后端也不允许线上任务含保险）
        m.put("insuranceFlag", job.getInsuranceFlag());
        // 做满一个月有红包（V44，month_redpacket 0 有 1 没有）：列表卡片和详情页显示「满月红包」小标签
        m.put("monthRedpacket", Boolean.valueOf("0".equals(job.getMonthRedpacket())));
        String displayCert = job.getRecruitStoreId() == null ? job.getSafeCert() : job.getRecruitSafeCert();
        m.put("safeCert", displayCert);
        // 和列表的 assured=1 同一口径：门店已认证、岗位含保险、不是线上任务
        m.put("assured", Boolean.valueOf("0".equals(displayCert) && "0".equals(job.getInsuranceFlag()) && !JobType.ONLINE.equals(job.getJobType())));
        m.put("headcount", job.getHeadcount());
        m.put("genderMaleCount", job.getGenderMaleCount());
        m.put("genderFemaleCount", job.getGenderFemaleCount());
        m.put("hiredCount", job.getHiredCount());
        m.put("reservedCount", job.getReservedCount());
        m.put("remainingCount", job.getRemainingCount());
        m.put("appliedCount", job.getAppliedCount());
        m.put("tags", splitTags(job.getTags()));
        m.put("status", job.getStatus());
        m.put("favorite", Boolean.valueOf(favorite));
        return m;
    }

    private static String salaryText(KgJob job)
    {
        if (job.getSalaryMin() == null || "none".equals(job.getSalaryType()))
        {
            return "";
        }
        String unit = StringUtils.isEmpty(job.getSalaryUnit()) ? "" : job.getSalaryUnit();
        String min = trimZero(job.getSalaryMin());
        if (job.getSalaryMax() == null || job.getSalaryMax().compareTo(job.getSalaryMin()) == 0)
        {
            return min + " " + unit;
        }
        return min + "-" + trimZero(job.getSalaryMax()) + " " + unit;
    }

    private static String trimZero(java.math.BigDecimal v)
    {
        return v.stripTrailingZeros().toPlainString();
    }

    /** 食宿枚举 -> 列表页展示文案，口径集中在后端，避免各页面各写一套映射 */
    private static String boardText(String boardType)
    {
        if ("all".equals(boardType))
        {
            return "包吃包住";
        }
        if ("room".equals(boardType))
        {
            return "包住宿";
        }
        if ("meal".equals(boardType))
        {
            return "包三餐";
        }
        if ("part".equals(boardType))
        {
            return "包部分";
        }
        return "不包食宿";
    }

    /** 最短停留天数 -> "最短2周" / "最短10天" */
    private static String minDaysText(Integer minDays)
    {
        if (minDays == null || minDays.intValue() <= 0)
        {
            return "";
        }
        int d = minDays.intValue();
        if (d % 7 == 0)
        {
            return "最短" + (d / 7) + "周";
        }
        return "最短" + d + "天";
    }

    private static List<String> splitTags(String tags)
    {
        List<String> list = new java.util.ArrayList<String>();
        if (StringUtils.isEmpty(tags))
        {
            return list;
        }
        String[] arr = tags.split(",");
        for (String a : arr)
        {
            String t = a.trim();
            if (t.length() > 0)
            {
                list.add(t);
            }
        }
        return list;
    }
}
