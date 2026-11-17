package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.staff.StaffSupport.str;

import java.security.SecureRandom;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.JobCompensation;
import com.ruoyi.kangaroo.domain.JobExtras;
import com.ruoyi.kangaroo.domain.JobFilterFields;
import com.ruoyi.kangaroo.domain.JobImages;
import com.ruoyi.kangaroo.enums.JobStatus;
import com.ruoyi.kangaroo.enums.JobType;
import com.ruoyi.kangaroo.enums.SalaryType;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.StayLocation;

/**
 * 员工端岗位：本店岗位列表与指标、发布岗位（落草稿，平台审核后上架）、描述生成、批量暂停/恢复、分享追踪。
 *
 * 描述生成是规则模板：用填写的结构化信息 + 门店资料拼句子，不调用任何外部 AI 服务，
 * "查看生成依据"原样列出用到的输入，页面不会把模板文案说成模型生成。
 */
@Service
public class KgStaffJobService
{
    /** 审核通过及之后的状态，用于"通过数/匹配率" */
    static final String PASSED = "'review_passed','interview_booked','interview_done','user_confirmed','store_confirmed','contract_signed','onboarded'";

    private static final List<String> CHANNELS = Arrays.asList("friend", "group", "moments", "link", "poster");

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgMerchantAgreements merchantAgreements;

    @Autowired
    private StaffSupport support;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgVolunteerKinds volunteerKinds;

    /** 地图选的工作地点落在哪个城市（离线行政区边界，不调外部接口）；没有这个 bean 时一律用门店城市 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.user.KgCityService cities;

    /** 分享链接的站点前缀（H5 部署地址，形如 https://m.example.com/#）；不配时只给小程序页面路径 */
    @Value("${kangaroo.share.base-url:}")
    private String shareBaseUrl;

    /** 合作商家发布的岗位走派单（V63）；单测没注入时照旧只落草稿 */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private KgMerchantRequestService merchantRequests;

    /** 合作商家现在是自助开通的：标题、工作内容、要求要过内容审核，它们会原样进派单消息；单测没注入时不查 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.community.KgCommunityGuard guard;

    /** 住宿视频写入时核对文件确实在（V66）；单测没注入时不查 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.core.KgVideoUploads videos;

    // ---------- 列表 ----------

    /** 名额已满：换宿按提交占位、线上任务按进行中接单、其他按录用人数（和 KgJob.getRemainingCount 同一口径）；没设名额的不算满 */
    static boolean isFull(Map<String, Object> r)
    {
        if (r.get("headcount") == null) return false;
        String jt = str(r.get("jobType"));
        Object occupied = JobType.VOLUNTEER.equals(jt) ? r.get("reserved") : JobType.ONLINE.equals(jt) ? r.get("passed") : r.get("hiredCount");
        return StaffSupport.toInt(occupied, 0) >= StaffSupport.toInt(r.get("headcount"), 0);
    }

    public Map<String, Object> list(StaffSupport.Ctx c, String type, String keyword, String status)
    {
        StringBuilder where = new StringBuilder(" where j.store_id = :s and j.del_flag = '0'");
        Map<String, Object> ps = p("s", c.storeId);
        boolean onlineAllowed = StaffPerms.allows(c.role, "online", StaffPerms.VIEW);
        if (!onlineAllowed) where.append(" and j.job_type <> 'online'");
        if (type != null && JobType.exists(type))
        {
            where.append(" and j.job_type = :t");
            ps.put("t", type);
        }
        if (keyword != null && !keyword.trim().isEmpty())
        {
            String kw = keyword.trim();
            where.append(" and (j.title like :kw or j.city like :kw or j.address like :kw or j.tags like :kw or cast(j.job_id as char) = :kwRaw)");
            // % _ \ 按字面找（KgLike）：之前搜「%」「_」会列出本店全部岗位
            ps.put("kw", com.ruoyi.kangaroo.ext.KgLike.contains(kw));
            ps.put("kwRaw", kw);
        }
        String shortCond = "(j.urgent_level > 0 or (j.start_date <= date_add(curdate(), interval 7 day) and j.hired_count < j.headcount))";
        if ("recruiting".equals(status))
        {
            where.append(" and j.status = 'published' and not ").append(shortCond);
        }
        else if ("short".equals(status))
        {
            where.append(" and j.status = 'published' and ").append(shortCond);
        }
        else if (status != null && JobStatus.exists(status))
        {
            where.append(" and j.status = :st");
            ps.put("st", status);
        }
        // latitude / longitude：「修改工作地点」打开地图时以原来的点为中心（V42）
        List<Map<String, Object>> rows = db.list("select j.job_id, j.title, j.cover, j.city, j.address, j.latitude, j.longitude, j.job_type, j.status, j.headcount, "
                + "j.hired_count, j.applied_count, j.share_visits, j.urgent_level, j.source, " + shortCond + " short_flag, "
                // 筛选条件（V48）：列表上提示哪些岗位还没填，「补填筛选条件」打开时带出当前值
                + "j.board_type, j.gender_pref, j.rest_mode, j.room_type, j.min_days, j.gender_male_count, j.gender_female_count, "
                // 岗位照片（V49）：卡片菜单「换岗位照片」打开时带出当前的几张
                + "j.images, "
                // 商家需求（V63）：有这一行的草稿走派单，状态文字和「能不能在小程序上架」跟着它；没有就是老流程
                + "(select mr.request_id from kg_merchant_request mr where mr.job_id = j.job_id and mr.del_flag = '0') request_id, "
                + "(select mr.status from kg_merchant_request mr where mr.job_id = j.job_id and mr.del_flag = '0') request_status, "
                + "(select count(*) from kg_application a where a.job_id = j.job_id and a.del_flag = '0') apps, "
                + "(select count(*) from kg_application a where a.job_id = j.job_id and a.del_flag = '0' and a.status in (" + PASSED + ")) passed, "
                // 换宿提交即占位（和小程序「名额已满」同一口径，见 KgJob.recruitmentOccupiedCount）
                + "(select count(*) from kg_application a where a.job_id = j.job_id and a.del_flag = '0' and a.status not in ('review_rejected','withdrawn','terminated')) reserved "
                + "from kg_job j" + where + " order by field(j.status, 'published', 'paused', 'draft', 'closed'), j.job_id desc limit 100", ps);
        int i = 0;
        for (Map<String, Object> r : rows)
        {
            String st = str(r.get("status"));
            boolean isShort = StaffSupport.toInt(r.get("shortFlag"), 0) > 0;
            String label;
            String tone;
            if (JobStatus.PUBLISHED.equals(st) && isFull(r))
            {
                // 2026-11-09 验收：满员的岗位小程序已写「名额已满」、不收报名，这里不能还写「招聘中」
                label = "名额已满";
                tone = "gray";
            }
            else if (JobStatus.PUBLISHED.equals(st))
            {
                label = isShort ? "缺人中" : "招聘中";
                tone = isShort ? "amber" : "green";
            }
            else if (JobStatus.PAUSED.equals(st))
            {
                label = "已暂停";
                tone = "gray";
            }
            else if (JobStatus.DRAFT.equals(st))
            {
                label = "staff".equals(str(r.get("source"))) ? "待平台审核" : "草稿";
                tone = "gray";
            }
            else
            {
                label = "已关闭";
                tone = "gray";
            }
            int apps = StaffSupport.toInt(r.get("apps"), 0);
            int passed = StaffSupport.toInt(r.get("passed"), 0);
            String cover = str(r.get("cover"));
            r.put("img", cover.isEmpty() ? "/static/media/rooms-cover-" + (i % 4 + 1) + ".jpg" : cover);
            r.put("images", JobImages.read(str(r.get("images"))));
            String requestStatus = str(r.get("requestStatus"));
            if (JobStatus.DRAFT.equals(st) && !requestStatus.isEmpty())
            {
                label = KgMerchantRequestService.statusLabel(requestStatus);
            }
            r.put("statusLabel", label);
            r.put("tone", tone);
            // 换工主理人切到这家商家店后：这条草稿能在小程序里完善、上架（普通草稿仍只能后台上架）
            r.put("canPublish", JobStatus.DRAFT.equals(st) && KgMerchantRequestService.DISPATCHED.equals(requestStatus)
                    && (StaffRole.MANAGER.equals(c.role) || StaffRole.CITY_LEAD.equals(c.role)));
            r.put("area", areaOf(str(r.get("address"))));
            r.put("type", StaffSupport.jobTypeLabel(r.get("jobType")));
            r.put("people", r.get("headcount"));
            r.put("apply", apps);
            r.put("match", apps == 0 ? 0 : Math.round(passed * 100f / apps));
            r.put("visits", StaffSupport.toInt(r.get("shareVisits"), 0));
            r.putAll(conditionView(str(r.get("jobType")), str(r.get("boardType")), st, r.get("genderPref"), r.get("restMode"), r.get("roomType"), r.get("minDays")));
            i++;
        }
        List<Map<String, Object>> metrics = new ArrayList<Map<String, Object>>();
        // 「招聘中」和下面「招聘中」筛选同一口径：已上架、不缺人（缺人的单独算「缺人中」）。之前数的是全部已上架，点进去条数对不上
        Map<String, Object> cnt = db.one("select count(*) total, sum(status = 'published' and not " + shortCond.replace("j.", "") + ") published, "
                + "sum(status = 'published' and " + shortCond.replace("j.", "") + ") short_cnt, sum(status = 'paused') paused "
                + "from kg_job where store_id = :s and del_flag = '0'" + (onlineAllowed ? "" : " and job_type <> 'online'"), p("s", c.storeId));
        metrics.add(metric("全部岗位", cnt.get("total"), ""));
        metrics.add(metric("招聘中", cnt.get("published"), ""));
        metrics.add(metric("缺人中", cnt.get("shortCnt"), "amber-text"));
        metrics.add(metric("已暂停", cnt.get("paused"), ""));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("metrics", metrics);
        out.put("rows", rows);
        out.put("storeName", c.storeName);
        out.put("canEdit", StaffPerms.allows(c.role, "jobs", StaffPerms.EDIT));
        // 已上架的线下岗位里，按排班或住宿筛时会被漏掉的有几个（没填休息排班，或包住却没填住宿条件；不受上面类型 / 关键词 / 状态筛选影响），和最新的一个：
        // 列表顶上提示「N 个岗位还没填排班 / 住宿等条件」，点「去补填」直接打开这一个（它不一定在当前筛出来的列表里）
        Map<String, Object> miss = db.one("select j.job_id, j.title, j.job_type, j.board_type, j.status, "
                + "(select count(*) from kg_job m where m.store_id = :s and m.del_flag = '0' and m.status = 'published' and " + conditionsEmpty("m.") + ") missing_cnt "
                + "from kg_job j where j.store_id = :s and j.del_flag = '0' and j.status = 'published' and " + conditionsEmpty("j.")
                + " order by j.job_id desc limit 1", p("s", c.storeId));
        Long missJob = miss == null ? null : KgJdbc.asLong(miss.get("jobId"));
        out.put("conditionsMissingCount", missJob == null ? 0 : StaffSupport.toInt(miss.get("missingCnt"), 0));
        if (missJob != null)
        {
            Map<String, Object> first = new LinkedHashMap<String, Object>();
            first.put("jobId", missJob);
            first.put("title", str(miss.get("title")));
            first.put("jobType", str(miss.get("jobType")));
            first.put("status", str(miss.get("status")));
            first.putAll(conditionView(str(miss.get("jobType")), str(miss.get("boardType")), str(miss.get("status")), "", "", "", null));
            out.put("conditionsMissingJob", first);
        }
        else
        {
            out.put("conditionsMissingJob", null);
        }
        Map<String, Object> store = db.one("select safe_cert, address, latitude, longitude from kg_store where store_id = :s", p("s", c.storeId));
        // kg_store.safe_cert：0 已认证 / 1 未认证
        out.put("storeCert", store != null && "0".equals(str(store.get("safeCert"))));
        // 发布页「工作地点」不选时写「默认：门店地址 …」；门店登记过坐标的，不选点也会带上门店的点
        out.put("storeAddress", store == null ? "" : str(store.get("address")));
        out.put("storeLatitude", store == null ? null : store.get("latitude"));
        out.put("storeLongitude", store == null ? null : store.get("longitude"));
        return out;
    }

    private static Map<String, Object> metric(String label, Object v, String tone)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", StaffSupport.toInt(v, 0));
        m.put("tone", tone);
        return m;
    }

    private static String areaOf(String address)
    {
        if (address.isEmpty())
        {
            return "";
        }
        String a = address.split("[·\\s,，]")[0];
        return a.length() > 8 ? a.substring(0, 8) : a;
    }

    /** 批量暂停 / 恢复：只在 published ↔ paused 之间切换，草稿上架仍由平台审核 */
    @Transactional(rollbackFor = Exception.class)
    public int batch(StaffSupport.Ctx c, List<Long> jobIds, String action)
    {
        if (jobIds == null || jobIds.isEmpty())
        {
            throw new ServiceException("没有选中岗位");
        }
        if (!StaffPerms.allows(c.role, "online", StaffPerms.EDIT) && db.count(
                "select count(*) from kg_job where job_id in (:ids) and store_id = :s and job_type = 'online' and del_flag = '0'",
                p("ids", jobIds, "s", c.storeId)) > 0)
            throw new ServiceException("当前角色没有线上任务管理权限", HttpStatus.FORBIDDEN);
        String from;
        String to;
        if ("pause".equals(action))
        {
            from = JobStatus.PUBLISHED;
            to = JobStatus.PAUSED;
        }
        else if ("resume".equals(action))
        {
            from = JobStatus.PAUSED;
            to = JobStatus.PUBLISHED;
        }
        else
        {
            throw new ServiceException("不支持的批量操作");
        }
        if (JobStatus.PUBLISHED.equals(to))
        {
            List<Map<String, Object>> selected = db.list("select job_id, job_type, images, status from kg_job "
                    + "where job_id in (:ids) and store_id = :s and del_flag = '0' order by job_id for update",
                    p("ids", jobIds, "s", c.storeId));
            for (Map<String, Object> job : selected)
            {
                if (from.equals(str(job.get("status"))) && !JobType.ONLINE.equals(str(job.get("jobType"))))
                {
                    JobImages.require(job.get("images"));
                }
            }
        }
        int n = db.update("update kg_job set status = :to, update_by = :u, update_time = now() "
                + "where job_id in (:ids) and store_id = :s and status = :from and del_flag = '0'",
                p("to", to, "from", from, "ids", jobIds, "s", c.storeId, "u", "staff:" + c.uid()));
        support.op(c, "job_" + action, "pause".equals(action) ? "批量暂停岗位" : "批量恢复岗位", "general", n + " 个岗位", true, null);
        return n;
    }

    // ---------- 发布 ----------

    /** 描述生成：variant 用于"重新生成"换一套句式 */
    public Map<String, Object> describe(StaffSupport.Ctx c, Map<String, Object> in, int variant)
    {
        Draft d = Draft.of(in);
        Map<String, Object> store = db.must("select store_name, city, address, intro, latitude, longitude from kg_store where store_id = :s", p("s", c.storeId), "门店不存在");
        String storeName = str(store.get("storeName"));
        // 工作地点：发布页选了地图点 / 手填了地址就用它（标题里的城市跟着选的点走），没选用门店的（V42）。
        // 这里就按发布时同一套规则校验，坐标不对在「下一步：生成描述」就提示，不用等到最后提交
        Place place = place(in, store);
        String city = str(place.city);
        String address = str(place.address);
        String intro = str(store.get("intro")).trim().replaceAll("[。.！!；;，,\\s]+$", "");
        String typeLabel = StaffSupport.jobTypeLabel(d.type);
        // 换工岗位选了类型（如「摄影换工」）：之前生成的标题和介绍一律笼统写「换工」，现在用类型名；「其他」仍写「换工」
        String kind = "volunteer".equals(d.type) ? str(in.get("workKind")).trim() : "";
        String kindLabel = kind.isEmpty() || volunteerKinds == null ? "" : volunteerKinds.label(kind);
        if (!kindLabel.isEmpty() && !"other".equals(kind))
        {
            typeLabel = kindLabel.endsWith("换工") ? kindLabel : kindLabel + "换工";
        }
        String baseBenefit = d.boardText();
        if (d.salaryEnabled)
        {
            baseBenefit += (baseBenefit.isEmpty() ? "" : " + ")
                    + d.salary.stripTrailingZeros().toPlainString() + SalaryType.unit(d.salaryType);
        }
        if (baseBenefit.isEmpty()) baseBenefit = "无食宿及底薪";
        String benefit = JobCompensation.summary(baseBenefit, d.bonusDesc, d.commissionDesc);
        int v = Math.abs(variant) % 3;

        String title = jobTitle(v, city.trim(), storeName.trim(), typeLabel.trim(), d.headcount,
                d.keywords.isEmpty() ? "" : d.keywords.get(0), benefit);

        String kwText = d.keywords.isEmpty() ? "生活与交流" : String.join("、", d.keywords);
        // 选了别处的工作地点（比如门店替合作商户招人）：不能再写「我们是位于〈那个地址〉的〈本店〉」，改说工作地点在哪。
        // 城市只在地图选点算出来、地址里又没写时才补在前面：手填的地址算不出城市（存的是门店城市），
        // 补上会拼出「平潭闽侯县南舍庄园」这种错地名；选点给的地址多半已带「福州市」，再补就重复
        String placeText = place.lat == null || city.isEmpty() || address.contains(city) ? address : city + address;
        String introHead = intro.isEmpty()
                ? (place.custom ? "工作地点在" + placeText + "，由" + storeName + "发布招募。"
                        : "我们是位于" + city + (address.isEmpty() ? "" : address) + "的" + storeName + "。")
                : storeName + "：" + StaffSupport.cut(intro, 80) + (intro.length() > 80 ? "…" : "") + "。"
                        + (place.custom ? "工作地点在" + placeText + "。" : "");
        String[] introTail = {
            "现招募 " + d.headcount + " 名" + typeLabel + "伙伴，" + benefit + "，期待热爱" + kwText + "的你加入。",
            "这次想找 " + d.headcount + " 位喜欢" + kwText + "的" + typeLabel + "伙伴，" + benefit + "，一起把小店经营得更好。",
            "如果你对" + kwText + "感兴趣，欢迎成为我们的" + typeLabel + "伙伴（共 " + d.headcount + " 人，" + benefit + "）。"
        };
        String extraRules = d.bonusDesc == null && d.commissionDesc == null ? ""
                : "\n薪资待遇：" + JobCompensation.details(baseBenefit, d.bonusDesc, d.commissionDesc);
        if ("0".equals(d.insuranceFlag))
        {
            extraRules += "\n为录用人员安排保险，保障以实际保单为准。";
        }
        // 为待遇规则预留空间，防止长门店简介把奖金/提成条件截掉。
        String introText = StaffSupport.cut(introHead + introTail[v], 300 - extraRules.length()) + extraRules;

        List<String> duties = new ArrayList<String>();
        for (String k : d.keywords)
        {
            String duty = DUTY.get(k);
            if (duty != null && !duties.contains(duty))
            {
                duties.add(duty);
            }
        }
        if (duties.isEmpty())
        {
            duties.add("online".equals(d.type) ? "按要求按时完成线上任务并提交成果" : "协助门店日常运营与接待工作");
        }
        duties.add("online".equals(d.type) ? "与门店保持沟通，按反馈修改完善" : "协助整理公共区域，保持环境整洁");
        String workTime = d.workTimeText();
        if (!workTime.isEmpty())
        {
            duties.add("工作时间：" + workTime);
        }
        if (!d.restMode.isEmpty())
        {
            duties.add("休息安排：" + JobFilterFields.restModeLabel(d.restMode));
        }
        StringBuilder work = new StringBuilder();
        for (int i = 0; i < duties.size() && i < 6; i++)
        {
            work.append(i == 0 ? "" : "\n").append(i + 1).append(". ").append(duties.get(i)).append(i == duties.size() - 1 || i == 5 ? "。" : "；");
        }

        List<String> hopes = new ArrayList<String>();
        hopes.add(v == 1 ? "热情开朗，乐于与人打交道" : "性格开朗，喜欢与人交流");
        hopes.add("有责任心，能遵守约定" + (d.startDate.isEmpty() ? "" : "，" + d.startDate + " 前后可以到岗")
                + (d.minDays == null ? "" : "，能在店至少 " + d.minDays + " 天"));
        hopes.add("volunteer".equals(d.type) ? "有相关经验者优先，无经验可培训" : (v == 2 ? "有" + kwText + "相关经验优先" : "有相关经验者优先"));
        StringBuilder hope = new StringBuilder();
        for (int i = 0; i < hopes.size(); i++)
        {
            hope.append(i == 0 ? "" : "\n").append("• ").append(hopes.get(i)).append(i == hopes.size() - 1 ? "。" : "；");
        }

        List<String> basis = new ArrayList<String>();
        basis.add("岗位类型：" + typeLabel);
        if ("volunteer".equals(d.type))
        {
            basis.add("换工类型：" + (kindLabel.isEmpty() ? "未选" : kindLabel));
        }
        basis.add("到岗日期：" + (d.startDate.isEmpty() ? "未填" : d.startDate));
        basis.add("工作时长：" + (workTime.isEmpty() ? "未填" : workTime));
        // 筛选条件（V48）：用户在岗位列表按这几项筛，没填的岗位在筛这一项时不会出现（性别没填 = 男女不限）
        if ("volunteer".equals(d.type))
        {
            basis.add("性别偏好：" + (d.genderPref.isEmpty() ? "男女不限" : JobFilterFields.GENDERS.get(d.genderPref)));
        }
        basis.add("休息排班：" + (d.restMode.isEmpty() ? "未选" : JobFilterFields.restModeLabel(d.restMode)));
        if (JobFilterFields.housing(d.type, d.boardType))
        {
            basis.add("住宿条件：" + (d.roomType.isEmpty() ? "未选" : JobFilterFields.roomTypeLabel(d.roomType)));
        }
        // 商家端发布表单（E1）的几项：填了才列出来
        if (d.boardTyped)
        {
            basis.add("食宿安排：" + Draft.BOARD_LABELS.get(d.boardType) + (d.boardDesc.isEmpty() ? "" : "（" + d.boardDesc + "）"));
        }
        if (!d.settleDesc.isEmpty())
        {
            basis.add("结算方式：" + d.settleDesc);
        }
        if (!d.categoryLabel().isEmpty())
        {
            basis.add("门店类型：" + d.categoryLabel());
        }
        if (!d.probation.isEmpty())
        {
            basis.add("试用期：" + d.probation);
        }
        if (!d.trainDesc.isEmpty())
        {
            basis.add("培训：" + d.trainDesc);
        }
        basis.add("最短在店：" + (d.minDays == null ? "不限" : d.minDays + " 天"));
        basis.add("岗位待遇：" + JobCompensation.details(baseBenefit, d.bonusDesc, d.commissionDesc));
        basis.add("岗位保险：" + ("0".equals(d.insuranceFlag) ? "为录用人员安排保险，保障以实际保单为准" : "未承诺提供保险"));
        if ("0".equals(d.monthRedpacket))
        {
            basis.add("满月红包：做满一个月有红包（列表和详情显示「满月红包」标签）");
        }
        basis.add("工作地点：" + (place.custom ? city + " · " + address + (place.lat != null ? "（地图选点）" : "（手填）")
                : (address.isEmpty() ? "门店地址（门店未填写）" : "门店地址（" + address + "）")));
        basis.add("招募人数：" + d.headcount + " 人");
        if (d.genderMaleCount != null) basis.add("招募计划：男生 " + d.genderMaleCount + " 人，女生 " + d.genderFemaleCount + " 人");
        basis.add("关键词：" + (d.keywords.isEmpty() ? "未选" : kwText));
        basis.add("门店资料：" + storeName + (intro.isEmpty() ? "（未填写门店简介）" : "（含门店简介）"));
        basis.add("生成方式：规则模板（第 " + (v + 1) + " 套句式），未调用外部 AI 服务");

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("title", title);
        out.put("intro", introText);
        out.put("work", work.toString());
        out.put("hope", hope.toString());
        out.put("basis", basis);
        out.put("variant", v);
        return out;
    }

    /** 岗位标题上限：与 create() 的校验、发布页「x/30」计数一致 */
    private static final int TITLE_MAX = 30;

    /** 三套标题句式；抬头写法从完整到精简排列，参数是门店名 */
    static String jobTitle(int v, String city, String store, String type, int headcount, String keyword, String benefit)
    {
        if (v == 0)
        {
            return fitTitle(store, benefit, Arrays.<Function<String, String>>asList(
                    s -> (city.isEmpty() ? "" : city + " · ") + s + type,
                    s -> (city.isEmpty() ? "" : city + "·") + s + type,
                    s -> s + type));
        }
        if (v == 1)
        {
            return fitTitle(store, benefit, Arrays.<Function<String, String>>asList(
                    s -> s + " · 招" + type + headcount + "人",
                    s -> s + "招" + type + headcount + "人",
                    s -> s + "招" + type));
        }
        return fitTitle(store, "", Arrays.<Function<String, String>>asList(
                s -> keyword + type + " · " + s,
                s -> keyword + type + "·" + s));
    }

    /**
     * 超长时按重要性逐级精简，不再硬截断（以前会截出半个「（」、留拖尾空格、把「+ 奖金」「+ 提成」截掉）：
     * 待遇去空格 → 「提供食宿」写成「包食宿」→ 换更短的抬头（去城市/人数）→ 门店名缩短加「…」→ 实在放不下才不带待遇
     */
    private static String fitTitle(String store, String benefit, List<Function<String, String>> heads)
    {
        String compact = benefit.replace(" + ", "+");
        String perk = compact.replace("提供食宿", "包食宿");
        for (String p : new String[] { benefit, compact, perk })
        {
            String t = withPerk(heads.get(0).apply(store), p);
            if (t.length() <= TITLE_MAX) return t;
        }
        for (Function<String, String> h : heads)
        {
            String t = withPerk(h.apply(store), perk);
            if (t.length() <= TITLE_MAX) return t;
        }
        Function<String, String> last = heads.get(heads.size() - 1);
        // 走到这里门店名一定比 keep 长；至少留 2 个字，否则认不出是哪家店，宁可不带待遇
        int keep = TITLE_MAX - 1 - withPerk(last.apply(""), perk).length();
        if (keep >= 2)
        {
            if (Character.isHighSurrogate(store.charAt(keep - 1))) keep--;
            // 店名里常带空格或「·」（如「苍山 · 慢时光」），截断处别留「苍山 ·…」
            return withPerk(last.apply(store.substring(0, keep).replaceAll("[\\s·]+$", "") + "…"), perk);
        }
        return perk.isEmpty() ? StaffSupport.cut(last.apply(store), TITLE_MAX).trim() : fitTitle(store, "", heads);
    }

    private static String withPerk(String head, String perk)
    {
        return perk.isEmpty() ? head : head + "（" + perk + "）";
    }

    private static final Map<String, String> DUTY = new LinkedHashMap<String, String>();

    static
    {
        DUTY.put("咖啡", "协助咖啡出品与吧台整理");
        DUTY.put("接待", "协助前台接待，办理入住与退房");
        DUTY.put("服务", "为客人介绍周边景点与店内活动");
        DUTY.put("手作", "协助手作体验活动的准备与引导");
        DUTY.put("摄影", "为店内活动与客人拍摄照片");
        DUTY.put("文案", "撰写店铺宣传文案与社交平台内容");
        DUTY.put("清洁", "负责客房与公共区域的清洁整理");
        DUTY.put("花艺", "协助花材整理与花艺作品制作");
        DUTY.put("运营", "参与店铺日常运营与数据记录");
        DUTY.put("活动", "协助策划与执行店内主题活动");
        // 2026-10-16 第二轮改版：岗位列表筛选面板的「技能需求」按这几个关键词筛（技能词典 KgSkills 里有同名标签和同义词）
        DUTY.put("做饭", "协助准备员工餐和客人早餐，保持厨房整洁");
        DUTY.put("拍摄", "拍摄店内日常与活动的照片、短视频");
        DUTY.put("开车", "持驾照协助接送客人、外出采购");
        DUTY.put("玩游戏", "陪客人玩桌游、剧本杀等游戏，活跃店内气氛");
    }

    public static List<String> keywordOptions()
    {
        return new ArrayList<String>(DUTY.keySet());
    }

    /** 表单里的结构化信息 */
    static class Draft
    {
        String type;
        String startDate;
        String hours;
        String pay;
        boolean boardEnabled;
        boolean salaryEnabled;
        String salaryType = "none";
        BigDecimal salary = BigDecimal.ZERO;
        String bonusDesc;
        String commissionDesc;
        String insuranceFlag;
        /** 做满一个月有红包（V44）：0 有 / 1 没有 */
        String monthRedpacket = "1";
        int headcount;
        Integer genderMaleCount;
        Integer genderFemaleCount;
        List<String> keywords = new ArrayList<String>();
        /**
         * 岗位筛选条件（V48，选填，取值见 JobFilterFields）：性别偏好只对换工换宿、休息排班只对线下岗位、
         * 住宿房型只在包住时（换工换宿或勾了提供食宿）有意义，超出范围的一律存 ''；老客户端不带 = ''。
         * 最短在店天数：不选 = 不限（不写 min_days），选了 1–365 天
         */
        String genderPref = "";
        String restMode = "";
        String roomType = "";
        Integer minDays;
        /**
         * 商家端发布表单（2026-10-18，E1）：用户端岗位详情要显示、之前发布页存不进去的几项，都落在 kg_job 现有的列上（不加迁移）。
         * 食宿细分 boardType：all 包吃包住 / room 包住 / meal 包吃 / none 不包（和用户端「食宿」筛选同一组代码）；
         * 老客户端不带 boardType，按 boardEnabled 存 all / none，boardTyped = false（描述里照旧写「提供食宿」）
         */
        String boardType = "none";
        boolean boardTyped;
        /** 食宿说明（选填，如「独立单间，三餐和店里一起吃」），没写按食宿类型的文字 */
        String boardDesc = "";
        /** 每日工时（daily_hours，如「每天 6 小时」）、上班时段（shift_time，如「9:00-18:00」）：用户端「工作时间」那一块 */
        String dailyHours = "";
        String shiftTime = "";
        /** 结算方式（settle_desc，如「月结 · 次月 5 日前发放」）：用户端福利卡「结算：…」 */
        String settleDesc = "";
        /** 全职才有：试用期（probation）、培训（train_desc，用户端「提供培训」筛选按它有没有填）、门店类型（民宿 / 酒店 …，存成第一个标签） */
        String probation = "";
        String trainDesc = "";
        String category = "";

        @SuppressWarnings("unchecked")
        static Draft of(Map<String, Object> in)
        {
            Draft d = new Draft();
            d.type = str(in.get("jobType"));
            if (!JobType.exists(d.type))
            {
                throw new ServiceException("请选择岗位类型");
            }
            // 缺字段兼容旧客户端；显式传入必须是既有保险标记枚举。
            Object insurance = in.get("insuranceFlag");
            d.insuranceFlag = in.containsKey("insuranceFlag") ? str(insurance) : "1";
            if ((!(insurance instanceof String) && in.containsKey("insuranceFlag"))
                    || !Arrays.asList("0", "1").contains(d.insuranceFlag))
            {
                throw new ServiceException("请选择有效的岗位保险选项", 422);
            }
            if ("online".equals(d.type) && "0".equals(d.insuranceFlag))
            {
                throw new ServiceException("线上任务不适用线下岗位保险", 422);
            }
            d.startDate = str(in.get("startDate")).trim();
            if (!d.startDate.isEmpty() && StaffSupport.parse(d.startDate) == null)
            {
                throw new ServiceException("到岗日期格式应为 yyyy-MM-dd");
            }
            d.hours = StaffSupport.cut(str(in.get("hours")).trim(), 64);
            d.pay = str(in.get("pay"));
            if (!Arrays.asList("board", "salary", "none").contains(d.pay))
            {
                d.pay = "board";
            }
            d.boardEnabled = benefitOption(in, "boardEnabled", "board".equals(d.pay));
            d.salaryEnabled = benefitOption(in, "salaryEnabled", "salary".equals(d.pay));
            if (d.salaryEnabled)
            {
                // 旧版客户端没有 salaryType，继续按原来的日薪处理。
                d.salaryType = in.get("salaryType") == null ? "daily" : str(in.get("salaryType"));
                if (!SalaryType.isTimeBased(d.salaryType))
                {
                    throw new ServiceException("请选择时薪、日薪、周薪或月薪");
                }
                String amount = str(in.get("salary")).trim();
                if (amount.length() > 16 || !amount.matches("[0-9]+(?:\\.[0-9]{1,2})?"))
                {
                    throw new ServiceException("薪资金额需大于 0、不超过 100000，最多两位小数");
                }
                d.salary = new BigDecimal(amount);
                if (d.salary.signum() <= 0 || d.salary.compareTo(new BigDecimal("100000")) > 0)
                {
                    throw new ServiceException("薪资金额需大于 0、不超过 100000，最多两位小数");
                }
            }
            d.bonusDesc = JobCompensation.selectedRule(in.get("bonusEnabled"), in.get("bonusDesc"), "奖金");
            // 「做满一个月有红包」（V44）：老客户端不带这个字段，按没有；线上任务不适用
            Object redpacket = in.get("monthRedpacket");
            if (redpacket != null && !(redpacket instanceof Boolean))
            {
                throw new ServiceException("满月红包选项必须为勾选状态", 422);
            }
            d.monthRedpacket = Boolean.TRUE.equals(redpacket) && !"online".equals(d.type) ? "0" : "1";
            d.commissionDesc = JobCompensation.selectedRule(in.get("commissionEnabled"), in.get("commissionDesc"), "提成");
            d.headcount = StaffSupport.toInt(in.get("headcount"), 1);
            if (d.headcount < 1 || d.headcount > 99)
            {
                throw new ServiceException("招募人数为 1–99 人");
            }
            Object kws = in.get("keywords");
            if (kws instanceof List)
            {
                for (Object k : (List<Object>) kws)
                {
                    String s = str(k).trim();
                    if (!s.isEmpty() && s.length() <= 8 && !d.keywords.contains(s) && d.keywords.size() < 4)
                    {
                        d.keywords.add(s);
                    }
                }
            }
            // 筛选条件（V48）：先按白名单校验（不认识的值 422），再按岗位类型、食宿收口（兼职填了性别、线上填了房型都存 ''）
            String gender = JobFilterFields.gender(in.get("genderPref"));
            String rest = JobFilterFields.restMode(in.get("restMode"));
            String room = JobFilterFields.roomType(in.get("roomType"));
            d.genderPref = JobFilterFields.genderFor(d.type, gender);
            Object split = in.get("genderCountsEnabled");
            if (split != null && !(split instanceof Boolean))
                throw new ServiceException("请选择有效的招募人数设置方式", 422);
            com.ruoyi.kangaroo.domain.KgJob plan = new com.ruoyi.kangaroo.domain.KgJob();
            plan.setJobType(d.type);
            plan.setHeadcount(d.headcount);
            plan.setGenderPref(d.genderPref);
            plan.setGenderCountsEnabled((Boolean) split);
            plan.setGenderMaleCount(com.ruoyi.kangaroo.domain.JobGenderCounts.count(in.get("genderMaleCount")));
            plan.setGenderFemaleCount(com.ruoyi.kangaroo.domain.JobGenderCounts.count(in.get("genderFemaleCount")));
            com.ruoyi.kangaroo.domain.JobGenderCounts.apply(plan, null);
            d.genderMaleCount = plan.getGenderMaleCount();
            d.genderFemaleCount = plan.getGenderFemaleCount();
            d.genderPref = plan.getGenderPref();
            d.restMode = JobFilterFields.restModeFor(d.type, rest);
            d.minDays = "online".equals(d.type) ? null : JobFilterFields.minDays(in.get("minDays"));
            d.readPublishFields(in);
            d.roomType = JobFilterFields.roomTypeFor(d.type, d.boardType, room);
            return d;
        }

        /** 食宿代码 → 文字（board_desc 没写时存这个；和用户端列表 / 详情的 boardText 同一套说法） */
        static final Map<String, String> BOARD_LABELS = new LinkedHashMap<String, String>();

        static
        {
            BOARD_LABELS.put("all", "包吃包住");
            BOARD_LABELS.put("room", "包住宿");
            BOARD_LABELS.put("meal", "包三餐");
            BOARD_LABELS.put("none", "不包食宿");
        }

        /**
         * 商家端发布表单的新字段（E1）：都是选填，老客户端一个都不带时结果和以前一样。
         * 不认识的代码一律 422（不悄悄存成别的）；文字项按列宽截断；线上任务不到店，这些都不存；试用期、培训、门店类型只给全职
         */
        private void readPublishFields(Map<String, Object> in)
        {
            boolean online = "online".equals(type);
            String bt = str(in.get("boardType")).trim();
            if (!bt.isEmpty() && !online)
            {
                if (!BOARD_LABELS.containsKey(bt))
                {
                    throw new ServiceException("请选择有效的食宿安排", 422);
                }
                // 换宿换的就是住宿（JobFilterFields.housing 对换工一律算包住），只能选包吃包住或只包住
                if ("volunteer".equals(type) && !("all".equals(bt) || "room".equals(bt)))
                {
                    throw new ServiceException("换宿岗位要提供住宿：请选包吃包住或只包住", 422);
                }
                boardType = bt;
                boardTyped = true;
                boardEnabled = !"none".equals(bt);
            }
            else
            {
                boardType = boardEnabled && !online ? "all" : "none";
            }
            if (online)
            {
                return;
            }
            boardDesc = boardEnabled ? StaffSupport.cut(str(in.get("boardDesc")).trim(), 60) : "";
            dailyHours = StaffSupport.cut(str(in.get("dailyHours")).trim(), 32);
            shiftTime = StaffSupport.cut(str(in.get("shiftTime")).trim(), 32);
            settleDesc = StaffSupport.cut(str(in.get("settleDesc")).trim(), 64);
            if (!"fulltime".equals(type))
            {
                return;
            }
            probation = StaffSupport.cut(str(in.get("probation")).trim(), 32);
            trainDesc = StaffSupport.cut(str(in.get("trainDesc")).trim(), 64);
            String cat = str(in.get("category")).trim();
            if (!cat.isEmpty())
            {
                String code = null;
                for (Map.Entry<String, String> e : com.ruoyi.kangaroo.domain.JobTabFilters.CATEGORIES.entrySet())
                {
                    if (e.getKey().equals(cat) || e.getValue().equals(cat))
                    {
                        code = e.getKey();
                    }
                }
                if (code == null)
                {
                    throw new ServiceException("请选择有效的门店类型", 422);
                }
                category = code;
            }
        }

        /** 描述、标题里的食宿说法：新客户端按选的类型（包吃包住 / 包住宿 / 包三餐），老客户端照旧「提供食宿」 */
        String boardText()
        {
            if (!boardEnabled)
            {
                return "";
            }
            return boardTyped ? BOARD_LABELS.get(boardType) : "提供食宿";
        }

        /** 门店类型的文字（民宿 / 酒店 / 咖啡餐饮 / 酒吧）：「其他」和没选都不进标签 */
        String categoryLabel()
        {
            return category.isEmpty() || "other".equals(category) ? "" : com.ruoyi.kangaroo.domain.JobTabFilters.CATEGORIES.get(category);
        }

        /** 存进 tags 的标签：门店类型（全职选了才有）放第一个，后面是关键词 / 技能（用户端标签行、技能筛选都读 tags） */
        List<String> tagList()
        {
            List<String> tags = new ArrayList<String>();
            String c = categoryLabel();
            if (!c.isEmpty())
            {
                tags.add(c);
            }
            for (String k : keywords)
            {
                if (!tags.contains(k))
                {
                    tags.add(k);
                }
            }
            return tags;
        }

        /** 工作时间的说法：老客户端写在 hours（如「每周 2 天，每天 4 小时」），新表单分成每日工时 + 上班时段 */
        String workTimeText()
        {
            String h = hours.isEmpty() ? dailyHours : hours;
            return shiftTime.isEmpty() ? h : (h.isEmpty() ? shiftTime : h + "（" + shiftTime + "）");
        }

        private static boolean benefitOption(Map<String, Object> in, String key, boolean legacy)
        {
            if (!in.containsKey(key)) return legacy;
            if (!(in.get(key) instanceof Boolean))
            {
                throw new ServiceException("食宿与底薪选项必须为勾选状态", 422);
            }
            return (Boolean) in.get(key);
        }
    }

    /** 员工发布岗位：落草稿（来源 staff），平台在后台审核上架；上架前小程序不可见 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(StaffSupport.Ctx c, Map<String, Object> in)
    {
        Map<String, Object> row = draftRow(c, in);
        if (StaffRole.MERCHANT.equals(c.role)) merchantAgreements.requireAccepted(c, str(row.get("job_type")));
        String title = str(row.get("title"));
        if (StaffRole.MERCHANT.equals(c.role) && guard != null)
        {
            guard.check(title, str(in.get("intro")), str(in.get("work")), str(in.get("hope")), str(in.get("address")));
        }
        row.put("store_id", c.storeId);
        row.put("publisher_name", StaffSupport.cut(c.name(), 32));
        row.put("status", JobStatus.DRAFT);
        row.put("source", "staff");
        row.put("create_by", "staff:" + c.uid());
        row.put("create_time", new Date());
        long jobId = db.insert("kg_job", row);
        support.op(c, "job_create", "发布岗位", "general", title, true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jobId", jobId);
        out.put("status", JobStatus.DRAFT);
        out.put("statusLabel", "待平台审核");
        if (StaffRole.MERCHANT.equals(c.role) && merchantRequests != null)
        {
            // 同一事务：派单失败就不留这条草稿，商家重新提交一次即可
            out.putAll(merchantRequests.dispatchJob(c, jobId, str(row.get("job_type")), title, str(row.get("content"))));
        }
        return out;
    }

    /**
     * 换工主理人完善派给他的商家草稿（V63）：和发布同一套校验（Draft.of、JobImages、工作地点），整组覆盖；岗位类型不能改。
     * 调用方（KgMerchantRequestService）已核对这是派单中的商家草稿、当前身份是这家店的换工主理人。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateMerchantDraft(StaffSupport.Ctx c, Long jobId, Map<String, Object> in)
    {
        Map<String, Object> j = db.must("select job_id, store_id, job_type, status, gender_male_count, gender_female_count from kg_job where job_id = :id and del_flag = '0' for update",
                p("id", jobId), "岗位不存在");
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        if (!JobStatus.DRAFT.equals(str(j.get("status"))))
        {
            throw new ServiceException("岗位已经不是草稿了，请刷新后查看", HttpStatus.CONFLICT);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>(in == null ? new LinkedHashMap<String, Object>() : in);
        body.put("jobType", j.get("jobType"));
        if (!body.containsKey("genderCountsEnabled") && !body.containsKey("genderMaleCount") && !body.containsKey("genderFemaleCount"))
        {
            body.put("genderMaleCount", j.get("genderMaleCount"));
            body.put("genderFemaleCount", j.get("genderFemaleCount"));
        }
        Map<String, Object> cols = draftRow(c, body);
        // 没选的项要清掉：发布时是「不写这一列」，改的时候得写空
        for (String clear : Arrays.asList("latitude", "longitude", "min_days"))
        {
            if (!cols.containsKey(clear))
            {
                cols.put(clear, null);
            }
        }
        cols.put("update_by", "staff:" + c.uid());
        cols.put("update_time", new Date());
        db.updateRow("kg_job", "job_id", jobId, cols);
        support.op(c, "job_draft_edit", "完善商家岗位", "general", str(cols.get("title")), true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jobId", jobId);
        out.put("title", cols.get("title"));
        out.put("content", cols.get("content"));
        return out;
    }

    /** 发布和完善共用：校验表单，得到 kg_job 的列（不含门店、状态、来源这些只在新建时写的列） */
    private Map<String, Object> draftRow(StaffSupport.Ctx c, Map<String, Object> in)
    {
        Draft d = Draft.of(in);
        // 线上任务的佣金、截止时间、验收标准都在 kg_online_task，从这里建只会有一条 kg_job：
        // 结算回落到岗位薪资、没有截止时间。只能走线上任务发布（KgOnlineTaskService）
        if ("online".equals(d.type))
        {
            throw new ServiceException("线上任务请在「线上任务」发布页填写交付物、截止时间和佣金后发布", 422);
        }
        String title = str(in.get("title")).trim();
        String intro = str(in.get("intro")).trim();
        String work = str(in.get("work")).trim();
        String hope = str(in.get("hope")).trim();
        if (title.isEmpty() || title.length() > 30)
        {
            throw new ServiceException("岗位标题为 1–30 字");
        }
        if (intro.length() > 300 || work.length() > 300 || hope.length() > 300)
        {
            throw new ServiceException("介绍、工作内容、要求各不超过 300 字");
        }
        if (d.startDate.isEmpty())
        {
            throw new ServiceException("请填写到岗日期");
        }
        Map<String, Object> store = db.must("select store_name, city, address, cover, latitude, longitude from kg_store where store_id = :s and del_flag = '0'",
                p("s", c.storeId), "门店不存在");
        // 至少一张岗位照片，第一张作为封面。
        List<String> images = JobImages.require(in.get("images"));
        // 工作地点（V42）：之前无条件写门店地址，门店替合作商户（南舍庄园、李喵喵民宿）发的岗位也显示门店地址
        Place place = place(in, store);
        // 自动开通的商家店没有地址：不选工作地点时等于空，用户端看到的工作地点就是空的
        if (!"online".equals(d.type) && str(place.address).trim().isEmpty())
        {
            throw new ServiceException("这家店还没有登记地址，请填写工作地点", 422);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("job_type", d.type);
        // 换工类型（字典 kg_volunteer_kind）：只有换工岗位才存，选了就必须是启用中的类型；不选也能发布
        String kind = str(in.get("workKind")).trim();
        row.put("work_kind", volunteerKinds == null ? "" : volunteerKinds.normalize(d.type, kind, null));
        row.put("title", title);
        row.put("cover", images.get(0));
        row.put("images", JobImages.toJson(images));
        row.put("city", "online".equals(d.type) ? "不限" : str(place.city));
        row.put("address", "online".equals(d.type) ? "远程" : place.address);
        if (!"online".equals(d.type) && place.lat != null)
        {
            row.put("latitude", place.lat);
            row.put("longitude", place.lng);
        }
        if (d.salaryEnabled)
        {
            row.put("salary_type", d.salaryType);
            row.put("salary_min", d.salary);
            row.put("salary_max", d.salary);
            row.put("salary_unit", SalaryType.unit(d.salaryType));
        }
        else
        {
            row.put("salary_type", "none");
        }
        row.put("bonus_desc", d.bonusDesc);
        row.put("commission_desc", d.commissionDesc);
        // 岗位待遇承诺与个人投保单分开：这里只保存标记，不申请或生成保单。
        row.put("insurance_flag", d.insuranceFlag);
        row.put("month_redpacket", d.monthRedpacket);
        row.put("board_type", d.boardType);
        // 食宿说明：写了用写的，没写按选的类型（老客户端照旧「提供食宿」）；不包食宿不写
        row.put("board_desc", !d.boardEnabled ? null : d.boardDesc.isEmpty() ? d.boardText() : d.boardDesc);
        row.put("start_date", d.startDate);
        row.put("shift_desc", d.hours.isEmpty() ? null : d.hours);
        // 商家端发布表单（E1）：每日工时、上班时段、结算方式、试用期、培训，都是 kg_job 现有的列；没填存 null（和后台岗位管理一样，详情里不显示）
        row.put("daily_hours", d.dailyHours.isEmpty() ? null : d.dailyHours);
        row.put("shift_time", d.shiftTime.isEmpty() ? null : d.shiftTime);
        row.put("settle_desc", d.settleDesc.isEmpty() ? null : d.settleDesc);
        row.put("probation", d.probation.isEmpty() ? null : d.probation);
        row.put("train_desc", d.trainDesc.isEmpty() ? null : d.trainDesc);
        // 筛选条件（V48）：Draft.of 已校验、按岗位类型收口；不选的存 ''，最短在店不选不写（null = 不限）
        row.put("gender_pref", d.genderPref);
        row.put("rest_mode", d.restMode);
        row.put("room_type", d.roomType);
        if (d.minDays != null)
        {
            row.put("min_days", d.minDays);
        }
        row.put("headcount", d.headcount);
        row.put("gender_male_count", d.genderMaleCount);
        row.put("gender_female_count", d.genderFemaleCount);
        List<String> tags = d.tagList();
        row.put("tags", tags.isEmpty() ? null : String.join(",", tags));
        row.put("content", (intro.isEmpty() ? "" : intro + "\n\n") + work);
        row.put("requirement", hope);
        row.put("remote_flag", "online".equals(d.type) ? "0" : "1");
        // 餐补、宠物、住宿照片和视频（V66）：只有带了的键才进来。老版本小程序不带，发布走列默认值，完善（updateMerchantDraft）不动库里的值
        row.putAll(JobExtras.columns(in, d.type, d.boardType, videos == null ? null : videos::exists));
        return row;
    }

    // ---------- 工作地点（V42） ----------

    /** 岗位的工作地点：门店地址（默认），或发布 / 修改时在地图上选的点、手填的地址 */
    static final class Place
    {
        Object address;
        String city;
        BigDecimal lat;
        BigDecimal lng;
        /** 不是门店地址（地图选点或手填了别的地址） */
        boolean custom;
    }

    /**
     * 按请求里的 address / latitude / longitude 定工作地点（发布、生成描述、修改工作地点共用）：
     *  · 带了坐标（地图选点）：坐标要成对、在范围内、不能是 0,0（和房源同一套校验），地址必填、不超过 255 字；
     *    城市按这个点算（离线行政区边界），算不出来用门店城市；
     *  · 没带坐标、地址空着或和门店地址一样：照旧用门店的地址和城市，门店登记过坐标的一并带上；
     *  · 没带坐标、手填了别的地址（网页版只能手填，小程序里手改了选点后的地址也会清掉坐标）：用这个地址、门店城市，不存坐标。
     */
    private Place place(Map<String, Object> in, Map<String, Object> store)
    {
        String address = str(in.get("address")).trim();
        if (address.length() > 255)
        {
            throw new ServiceException("工作地点不超过 255 字", 422);
        }
        Map<String, Object> point = new LinkedHashMap<String, Object>();
        for (String k : Arrays.asList("latitude", "longitude"))
        {
            if (in.containsKey(k))
            {
                point.put(k, in.get(k));
            }
        }
        StayLocation.normalize(point);
        Place p = new Place();
        String storeCity = str(store.get("city"));
        String storeAddress = str(store.get("address")).trim();
        if (point.get("latitude") != null)
        {
            if (address.isEmpty())
            {
                throw new ServiceException("请填写工作地点", 422);
            }
            p.address = address;
            p.lat = (BigDecimal) point.get("latitude");
            p.lng = (BigDecimal) point.get("longitude");
            p.city = cityOf(p.lat, p.lng, storeCity);
            p.custom = !address.equals(storeAddress);
            return p;
        }
        if (address.isEmpty() || address.equals(storeAddress))
        {
            p.address = store.get("address");
            p.city = storeCity;
            BigDecimal[] sp = storePoint(store);
            if (sp != null)
            {
                p.lat = sp[0];
                p.lng = sp[1];
            }
            return p;
        }
        p.address = address;
        p.city = storeCity;
        p.custom = true;
        return p;
    }

    /** 门店登记的坐标（后台门店管理里填的）；没填或填得不对就当没有，不拦发布 */
    private static BigDecimal[] storePoint(Map<String, Object> store)
    {
        if (store.get("latitude") == null || store.get("longitude") == null)
        {
            return null;
        }
        Map<String, Object> pt = new LinkedHashMap<String, Object>();
        pt.put("latitude", store.get("latitude"));
        pt.put("longitude", store.get("longitude"));
        try
        {
            StayLocation.normalize(pt);
        }
        catch (ServiceException e)
        {
            return null;
        }
        return pt.get("latitude") == null ? null : new BigDecimal[] { (BigDecimal) pt.get("latitude"), (BigDecimal) pt.get("longitude") };
    }

    /** 选的点落在哪个城市（业务城市名，如「思明区」记成「厦门」）；算不出来（边界附近、偏远地点）用门店城市 */
    private String cityOf(BigDecimal lat, BigDecimal lng, String fallback)
    {
        if (cities != null)
        {
            try
            {
                Map<String, Object> hit = cities.locate(lat.doubleValue(), lng.doubleValue(), 0d, "gcj02");
                String name = hit == null ? "" : str(hit.get("name")).trim();
                if (hit != null && Boolean.TRUE.equals(hit.get("found")) && !name.isEmpty() && name.length() <= 32)
                {
                    return name;
                }
            }
            catch (RuntimeException e)
            {
                // 定城市是附带的，算不出来不能挡住发布
            }
        }
        return fallback;
    }

    /**
     * 改已发岗位的工作地点（店长、管家）：地图选点，或手填地址（手填会清掉旧坐标，免得导航到原来的点）。
     * 控制器已按岗位所属门店取身份并校验「岗位管理·编辑」，这里再核对一次门店，别家门店的身份带不进来。
     * 工作地点不是平台审核的内容，改了不用重新审核、立即生效；记一条员工操作日志备查。线上任务是远程交付，没有工作地点。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> location(StaffSupport.Ctx c, Long jobId, Map<String, Object> in)
    {
        Map<String, Object> j = db.must("select job_id, store_id, title, job_type, city, status from kg_job where job_id = :id and del_flag = '0' for update",
                p("id", jobId), "岗位不存在");
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        if (JobType.ONLINE.equals(str(j.get("jobType"))))
        {
            throw new ServiceException("线上任务是远程交付，没有工作地点", HttpStatus.CONFLICT);
        }
        // 已关闭的岗位不再对外展示，也不能再改（之前照样能改地点，还会记一条操作日志）
        if (JobStatus.CLOSED.equals(str(j.get("status"))))
        {
            throw new ServiceException("岗位已关闭，不能再修改工作地点", HttpStatus.CONFLICT);
        }
        Map<String, Object> body = in == null ? new LinkedHashMap<String, Object>() : in;
        if (str(body.get("address")).trim().isEmpty())
        {
            throw new ServiceException("请填写工作地点", 422);
        }
        Map<String, Object> store = db.must("select city, address, latitude, longitude from kg_store where store_id = :s", p("s", c.storeId), "门店不存在");
        Place place = place(body, store);
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        cols.put("address", place.address);
        cols.put("latitude", place.lat);
        cols.put("longitude", place.lng);
        // 手填的别处地址没有坐标，算不出城市，城市保持原样；选了点、或改回门店地址时城市跟着走
        String city = place.lat != null || !place.custom ? place.city : str(j.get("city"));
        cols.put("city", city);
        cols.put("update_by", "staff:" + c.uid());
        cols.put("update_time", new Date());
        db.updateRow("kg_job", "job_id", jobId, cols);
        support.op(c, "job_location", "修改工作地点", "general", str(j.get("title")) + " → " + str(place.address), true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jobId", jobId);
        out.put("address", place.address);
        out.put("city", city);
        out.put("latitude", place.lat);
        out.put("longitude", place.lng);
        return out;
    }

    // ---------- 补填筛选条件（10-16 跟进） ----------

    /**
     * 线下岗位缺了会让它在筛选里被漏掉的条件：没填休息排班，或包住（换工换宿，或食宿是包吃包住 / 包住宿）却没填住宿条件。
     * 性别偏好没填 = 男女不限、最短在店没填 = 不限，按任何性别 / 在店天数筛都看得到，不算缺（10-16 复查：之前要四项全空才算，
     * 填过最短在店的老岗位排班空着也不提示）。列表横幅数的是这种已上架岗位；和 conditionView 的 conditionsMissing 同一口径
     */
    static String conditionsEmpty(String a)
    {
        return a + "job_type <> 'online' and (" + a + "rest_mode = '' or ((" + a + "job_type = 'volunteer' or " + a + "board_type in ('all', 'room')) and "
                + a + "room_type = ''))";
    }

    /**
     * 岗位当前的筛选条件 + 文案，员工端列表每行、补填接口的返回共用：
     * genderPref / genderPrefLabel（只有换工换宿有，'' = 男女不限）、restMode / restModeLabel、roomType / roomTypeLabel、
     * minDays（null = 不限）、housing（包不包住，决定要不要填住宿条件）、
     * conditionsMissing（线下、没关闭，并且没填休息排班、或包住却没填住宿条件：这两项空着时用户按它筛就看不到这个岗位）
     */
    static Map<String, Object> conditionView(String jobType, String boardType, String status, Object gender, Object rest, Object room, Object days)
    {
        String g = str(gender);
        String r = str(rest);
        String t = str(room);
        Long n = KgJdbc.asLong(days);
        Integer d = n == null || n <= 0 ? null : Integer.valueOf(n.intValue());
        boolean online = JobType.ONLINE.equals(jobType);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("genderPref", g);
        String gl = JobFilterFields.GENDERS.get(g);
        m.put("genderPrefLabel", !JobType.VOLUNTEER.equals(jobType) ? "" : gl == null ? "男女不限" : gl);
        m.put("restMode", r);
        m.put("restModeLabel", JobFilterFields.restModeLabel(r));
        m.put("roomType", t);
        m.put("roomTypeLabel", JobFilterFields.roomTypeLabel(t));
        m.put("minDays", d);
        boolean housing = JobFilterFields.housing(jobType, boardType);
        m.put("housing", housing);
        m.put("conditionsMissing", !online && !JobStatus.CLOSED.equals(status) && (r.isEmpty() || (housing && t.isEmpty())));
        return m;
    }

    /**
     * 给已发岗位补填筛选条件（店长、管家）。老岗位发布时还没有这几项，用户按 性别 / 排班 / 住宿 / 在店时长 筛时看不到它们。
     * 和发布同一套规则（JobFilterFields）：先按白名单校验，不认识的值 422；再按岗位类型、食宿收口——
     * 性别偏好只对换工换宿，住宿条件只在包住时，最短在店 1–365 天或不限。四项整体覆盖（没传的按没填），同样的请求重复提交结果一样。
     * 控制器已按岗位所属门店取身份并校验「岗位管理·编辑」，这里再核对一次门店。只改这四列和更新时间，不动状态（不用重新审核）；
     * 线上任务没有这些条件（422）；已关闭的岗位不能再改（409，和改工作地点一致）。记一条员工操作日志
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> conditions(StaffSupport.Ctx c, Long jobId, Map<String, Object> in)
    {
        Map<String, Object> j = db.must("select job_id, store_id, title, job_type, board_type, status, gender_male_count, gender_female_count from kg_job where job_id = :id and del_flag = '0' for update",
                p("id", jobId), "岗位不存在");
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        String type = str(j.get("jobType"));
        if (JobType.ONLINE.equals(type))
        {
            throw new ServiceException("线上任务没有这些条件", 422);
        }
        if (JobStatus.CLOSED.equals(str(j.get("status"))))
        {
            throw new ServiceException("岗位已关闭，不能再修改筛选条件", HttpStatus.CONFLICT);
        }
        if (in == null)
        {
            throw new ServiceException("请填写筛选条件", 422);
        }
        String board = str(j.get("boardType"));
        // 四项都先校验，再收口：兼职填了性别、不包住填了房型都存 ''（和发布一样，不报错）
        String gender = JobFilterFields.gender(in.get("genderPref"));
        String rest = JobFilterFields.restMode(in.get("restMode"));
        String room = JobFilterFields.roomType(in.get("roomType"));
        Integer minDays = JobFilterFields.minDays(in.get("minDays"));
        gender = JobFilterFields.genderFor(type, gender);
        if ("volunteer".equals(type) && j.get("genderMaleCount") != null && j.get("genderFemaleCount") != null)
        {
            String planned = com.ruoyi.kangaroo.domain.JobGenderCounts.preference(
                    com.ruoyi.kangaroo.domain.JobGenderCounts.count(j.get("genderMaleCount")),
                    com.ruoyi.kangaroo.domain.JobGenderCounts.count(j.get("genderFemaleCount")));
            if (!planned.equals(gender)) throw new ServiceException("该岗位已分别设置男女人数，请在岗位管理中一起修改", 422);
        }
        rest = JobFilterFields.restModeFor(type, rest);
        room = JobFilterFields.roomTypeFor(type, board, room);
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        cols.put("gender_pref", gender);
        cols.put("rest_mode", rest);
        cols.put("room_type", room);
        cols.put("min_days", minDays);
        cols.put("update_by", "staff:" + c.uid());
        cols.put("update_time", new Date());
        db.updateRow("kg_job", "job_id", jobId, cols);
        Map<String, Object> view = conditionView(type, board, str(j.get("status")), gender, rest, room, minDays);
        List<String> said = new ArrayList<String>();
        if (JobType.VOLUNTEER.equals(type))
        {
            said.add(str(view.get("genderPrefLabel")));
        }
        said.add(rest.isEmpty() ? "排班未填" : str(view.get("restModeLabel")));
        if (JobFilterFields.housing(type, board))
        {
            said.add(room.isEmpty() ? "房型未填" : str(view.get("roomTypeLabel")));
        }
        said.add(minDays == null ? "在店不限" : "最短 " + minDays + " 天");
        support.op(c, "job_conditions", "补填筛选条件", "general", str(j.get("title")) + " → " + String.join("、", said), true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jobId", jobId);
        out.putAll(view);
        return out;
    }

    // ---------- 岗位照片（V49） ----------

    /**
     * 换已发岗位的照片（店长、管家）：body = {images: [平台上传的图片路径，最多 9 张]}，整组覆盖，顺序就是展示顺序，第一张做封面；
     * 至少保留一张；只收本平台上传接口给的 /profile/upload/… 路径，不合格 422。
     * 控制器已按岗位所属门店取身份并校验「岗位管理·编辑」，这里再核对一次门店。照片和工作地点、筛选条件一样不是平台审核的内容，
     * 改了立即生效、不用重新审核（后台岗位管理可以替换或删掉）；线上任务没有岗位照片（422），已关闭的岗位不能再改（409）。记一条员工操作日志
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> images(StaffSupport.Ctx c, Long jobId, Map<String, Object> in)
    {
        Map<String, Object> j = db.must("select job_id, store_id, title, job_type, status from kg_job where job_id = :id and del_flag = '0' for update",
                p("id", jobId), "岗位不存在");
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        if (JobType.ONLINE.equals(str(j.get("jobType"))))
        {
            throw new ServiceException("线上任务没有岗位照片", 422);
        }
        if (JobStatus.CLOSED.equals(str(j.get("status"))))
        {
            throw new ServiceException("岗位已关闭，不能再换照片", HttpStatus.CONFLICT);
        }
        // 合作商家是自助开通的，换工主理人只把关上架那一刻：上架后换图不经过任何人，所以上架后只能由换工主理人换
        if (StaffRole.MERCHANT.equals(c.role) && !JobStatus.DRAFT.equals(str(j.get("status"))))
        {
            throw new ServiceException("岗位上架后不能自己换照片，请联系本城换工主理人更换", HttpStatus.FORBIDDEN);
        }
        List<String> images = JobImages.require(in == null ? null : in.get("images"));
        String cover = images.get(0);
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        cols.put("images", JobImages.toJson(images));
        cols.put("cover", cover);
        cols.put("update_by", "staff:" + c.uid());
        cols.put("update_time", new Date());
        db.updateRow("kg_job", "job_id", jobId, cols);
        support.op(c, "job_images", "更换岗位照片", "general",
                str(j.get("title")) + " → " + images.size() + " 张照片", true, null);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("jobId", jobId);
        out.put("images", images);
        out.put("cover", cover == null ? "" : cover);
        return out;
    }

    // ---------- 餐补、宠物、住宿照片和视频（V66） ----------

    private static final String EXTRA_COLUMNS = "meal_allowance, meal_allowance_unit, pet_status, pet_images, lodging_images, lodging_video, lodging_video_cover";

    private static Map<String, Object> storedExtras(Map<String, Object> j)
    {
        return JobExtras.stored(str(j.get("jobType")), str(j.get("boardType")), j.get("mealAllowance"), j.get("mealAllowanceUnit"), j.get("petStatus"),
                j.get("petImages"), j.get("lodgingImages"), j.get("lodgingVideo"), j.get("lodgingVideoCover"));
    }

    /** 已发岗位补资料（店长、管家、换工主理人，商家只在草稿时）：打开弹层时带出原值。线上任务没有这些（422） */
    public Map<String, Object> extras(StaffSupport.Ctx c, Long jobId)
    {
        Map<String, Object> j = db.must("select job_id, store_id, job_type, board_type, status, " + EXTRA_COLUMNS + " from kg_job where job_id = :id and del_flag = '0'",
                p("id", jobId), "岗位不存在");
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        if (JobType.ONLINE.equals(str(j.get("jobType"))))
        {
            throw new ServiceException("线上任务没有住宿和宠物资料", 422);
        }
        Map<String, Object> out = storedExtras(j);
        out.put("jobId", jobId);
        return out;
    }

    /**
     * 给已发岗位补餐补、宠物、住宿照片和视频（主人问的「换工的住宿视频在哪里上传」：线上的老岗位没有别的入口）。规则照换岗位照片（images）：
     * 控制器已按岗位所属门店取身份并校验「岗位管理·编辑」，这里再核对一次门店；线上 422、已关闭 409、合作商家上架后 403；不用重新审核；记一条员工操作日志。
     * 和换照片不同：只改带了的键（见 JobExtras），一个键都没带就什么都不改
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveExtras(StaffSupport.Ctx c, Long jobId, Map<String, Object> in)
    {
        Map<String, Object> j = db.must("select job_id, store_id, title, job_type, board_type, status, " + EXTRA_COLUMNS + " from kg_job where job_id = :id and del_flag = '0' for update",
                p("id", jobId), "岗位不存在");
        if (c.storeId == null || !c.storeId.equals(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        if (JobType.ONLINE.equals(str(j.get("jobType"))))
        {
            throw new ServiceException("线上任务没有住宿和宠物资料", 422);
        }
        if (JobStatus.CLOSED.equals(str(j.get("status"))))
        {
            throw new ServiceException("岗位已关闭，不能再修改", HttpStatus.CONFLICT);
        }
        if (StaffRole.MERCHANT.equals(c.role) && !JobStatus.DRAFT.equals(str(j.get("status"))))
        {
            throw new ServiceException("岗位上架后不能自己改，请联系本城换工主理人", HttpStatus.FORBIDDEN);
        }
        Map<String, Object> cols = JobExtras.columns(in, str(j.get("jobType")), str(j.get("boardType")), videos == null ? null : videos::exists);
        if (cols.isEmpty())
        {
            Map<String, Object> same = storedExtras(j);
            same.put("jobId", jobId);
            return same;
        }
        Map<String, Object> merged = new LinkedHashMap<String, Object>(j);
        merged.put("mealAllowance", cols.containsKey("meal_allowance") ? cols.get("meal_allowance") : j.get("mealAllowance"));
        merged.put("mealAllowanceUnit", cols.containsKey("meal_allowance_unit") ? cols.get("meal_allowance_unit") : j.get("mealAllowanceUnit"));
        merged.put("petStatus", cols.containsKey("pet_status") ? cols.get("pet_status") : j.get("petStatus"));
        merged.put("petImages", cols.containsKey("pet_images") ? cols.get("pet_images") : j.get("petImages"));
        merged.put("lodgingImages", cols.containsKey("lodging_images") ? cols.get("lodging_images") : j.get("lodgingImages"));
        merged.put("lodgingVideo", cols.containsKey("lodging_video") ? cols.get("lodging_video") : j.get("lodgingVideo"));
        merged.put("lodgingVideoCover", cols.containsKey("lodging_video_cover") ? cols.get("lodging_video_cover") : j.get("lodgingVideoCover"));
        cols.put("update_by", "staff:" + c.uid());
        cols.put("update_time", new Date());
        db.updateRow("kg_job", "job_id", jobId, cols);
        support.op(c, "job_extras", "补填住宿和宠物资料", "general", str(j.get("title")), true, null);
        Map<String, Object> out = storedExtras(merged);
        out.put("jobId", jobId);
        return out;
    }

    // ---------- 分享追踪 ----------

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RND = new SecureRandom();

    private static String newCode()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++)
        {
            sb.append(CODE_CHARS.charAt(RND.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private Map<String, Object> ownJob(StaffSupport.Ctx c, Long jobId)
    {
        Map<String, Object> j = db.must("select job_id, store_id, title, cover, city, address, job_type, status, board_type, shift_desc, "
                + "salary_type, salary_min, salary_unit from kg_job where job_id = :id and del_flag = '0'", p("id", jobId), "岗位不存在");
        if (!c.me.hasStoreAccess(KgJdbc.asLong(j.get("storeId"))))
        {
            throw new ServiceException("没有该门店的操作权限", HttpStatus.FORBIDDEN);
        }
        if (JobType.ONLINE.equals(str(j.get("jobType"))))
            StaffPerms.require(support.ctxForStore(KgJdbc.asLong(j.get("storeId"))).role, "online", StaffPerms.VIEW);
        return j;
    }

    /** 生成（或复用）本人在该岗位该渠道的分享码；count=true 时记一次分享（复制链接 / 保存海报 / 转发时才算） */
    public Map<String, Object> share(StaffSupport.Ctx c, Long jobId, String channel, boolean count)
    {
        Map<String, Object> j = ownJob(c, jobId);
        String ch = CHANNELS.contains(channel) ? channel : "link";
        Map<String, Object> s = db.one("select share_code from kg_job_share where job_id = :j and staff_user_id = :u and channel = :ch",
                p("j", jobId, "u", c.uid(), "ch", ch));
        String code;
        if (s == null)
        {
            code = null;
            for (int i = 0; i < 5 && code == null; i++)
            {
                String candidate = newCode();
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("share_code", candidate);
                row.put("job_id", jobId);
                row.put("store_id", j.get("storeId"));
                row.put("staff_user_id", c.uid());
                row.put("channel", ch);
                row.put("shares", 0);
                row.put("create_time", new Date());
                try
                {
                    db.insert("kg_job_share", row);
                    code = candidate;
                }
                catch (DuplicateKeyException e)
                {
                    // 同一人同渠道并发生成时对方已写入，直接复用；分享码撞车则换一个重试
                    Map<String, Object> again = db.one("select share_code from kg_job_share where job_id = :j and staff_user_id = :u and channel = :ch",
                            p("j", jobId, "u", c.uid(), "ch", ch));
                    if (again != null)
                    {
                        code = str(again.get("shareCode"));
                    }
                }
            }
            if (code == null)
            {
                throw new ServiceException("分享码生成失败，请重试");
            }
        }
        else
        {
            code = str(s.get("shareCode"));
        }
        if (count)
        {
            db.update("update kg_job_share set shares = shares + 1 where share_code = :c", p("c", code));
        }
        String path = "/pages/jobs/detail?jobId=" + jobId + "&sc=" + code;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("shareCode", code);
        out.put("channel", ch);
        out.put("path", path);
        out.put("link", shareBaseUrl == null || shareBaseUrl.trim().isEmpty() ? path : shareBaseUrl.trim() + path);
        out.put("title", j.get("title"));
        out.put("cover", j.get("cover"));
        out.put("published", JobStatus.PUBLISHED.equals(str(j.get("status"))));
        String board = "all".equals(str(j.get("boardType"))) ? "提供食宿" : ("room".equals(str(j.get("boardType"))) ? "提供住宿" : "");
        List<String> parts = new ArrayList<String>();
        if (!board.isEmpty())
        {
            parts.add(board);
        }
        if (!str(j.get("shiftDesc")).isEmpty())
        {
            parts.add(str(j.get("shiftDesc")));
        }
        parts.add(areaOf(str(j.get("address"))).isEmpty() ? str(j.get("city")) : areaOf(str(j.get("address"))));
        out.put("summary", String.join("｜", parts));
        return out;
    }

    private static String channelLabel(String ch)
    {
        if ("friend".equals(ch))
        {
            return "微信好友";
        }
        if ("group".equals(ch))
        {
            return "微信群";
        }
        if ("moments".equals(ch))
        {
            return "朋友圈";
        }
        return "其他渠道";
    }

    /** dim：channel 按渠道 / staff 按员工 / manager 按主理人；近 30 天 */
    public Map<String, Object> shareStats(StaffSupport.Ctx c, Long jobId, String dim)
    {
        if (jobId != null)
        {
            ownJob(c, jobId);
        }
        String jobCond = jobId == null ? "" : " and s.job_id = :j";
        Map<String, Object> ps = p("st", c.storeId, "j", jobId);
        List<Map<String, Object>> shares = db.list("select s.share_code, s.channel, s.staff_user_id, s.shares, u.nickname, "
                + "(select g.role_code from kg_staff_grant g where g.app_user_id = s.staff_user_id and g.store_id = s.store_id "
                + " and g.del_flag = '0' and g.status = '0' limit 1) role_code, "
                + "(select count(*) from kg_job_share_visit v where v.share_code = s.share_code and v.visit_date >= date_sub(curdate(), interval 29 day)) visit30, "
                + "(select count(*) from kg_application a where a.share_code = s.share_code and a.del_flag = '0' "
                + " and a.submit_time >= date_sub(now(), interval 30 day)) apply30, "
                + "(select count(*) from kg_application a where a.share_code = s.share_code and a.del_flag = '0' "
                + " and a.submit_time >= date_sub(now(), interval 30 day) and a.status in (" + PASSED + ")) pass30 "
                + "from kg_job_share s left join kg_app_user u on u.user_id = s.staff_user_id where s.store_id = :st" + jobCond, ps);

        String d = "staff".equals(dim) || "manager".equals(dim) ? dim : "channel";
        // 员工维度按 staffUserId 分组，不能按昵称：新账号默认昵称都是「小院同学」，按昵称分组会把几个人合并成一行，分享榜记错人
        Map<String, String> staffNames = new LinkedHashMap<String, String>();
        Map<String, long[]> groups = new LinkedHashMap<String, long[]>();
        if ("channel".equals(d))
        {
            for (String ch : Arrays.asList("微信好友", "微信群", "朋友圈", "其他渠道"))
            {
                groups.put(ch, new long[4]);
            }
        }
        for (Map<String, Object> s : shares)
        {
            String key;
            if ("channel".equals(d))
            {
                key = channelLabel(str(s.get("channel")));
            }
            else
            {
                if ("manager".equals(d) && !"manager".equals(str(s.get("roleCode"))))
                {
                    continue;
                }
                key = String.valueOf(s.get("staffUserId"));
                staffNames.put(key, displayName(s));
            }
            long[] g = groups.get(key);
            if (g == null)
            {
                g = new long[4];
                groups.put(key, g);
            }
            g[0] += StaffSupport.toInt(s.get("visit30"), 0);
            g[1] += StaffSupport.toInt(s.get("apply30"), 0);
            g[2] += StaffSupport.toInt(s.get("pass30"), 0);
            g[3] += StaffSupport.toInt(s.get("shares"), 0);
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        long[] total = new long[4];
        for (Map.Entry<String, long[]> e : groups.entrySet())
        {
            long[] g = e.getValue();
            rows.add(statRow("channel".equals(d) ? e.getKey() : uniqueName(staffNames, e.getKey()), g));
            for (int i = 0; i < 4; i++)
            {
                total[i] += g[i];
            }
        }
        // 员工分享榜：按分享次数排，只统计本店
        Map<String, long[]> byStaff = new LinkedHashMap<String, long[]>();
        Map<String, Object> ids = new LinkedHashMap<String, Object>();
        Map<String, String> rankNames = new LinkedHashMap<String, String>();
        for (Map<String, Object> s : shares)
        {
            String key = String.valueOf(s.get("staffUserId"));
            rankNames.put(key, displayName(s));
            long[] g = byStaff.get(key);
            if (g == null)
            {
                g = new long[4];
                byStaff.put(key, g);
                ids.put(key, s.get("staffUserId"));
            }
            g[0] += StaffSupport.toInt(s.get("shares"), 0);
            g[1] += StaffSupport.toInt(s.get("apply30"), 0);
            g[2] += StaffSupport.toInt(s.get("pass30"), 0);
        }
        List<Map<String, Object>> ranks = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, long[]> e : byStaff.entrySet())
        {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("name", uniqueName(rankNames, e.getKey()));
            r.put("id", ids.get(e.getKey()));
            r.put("share", e.getValue()[0]);
            r.put("apply", e.getValue()[1]);
            r.put("pass", e.getValue()[2]);
            ranks.add(r);
        }
        ranks.sort((a, b) -> Long.compare(((Number) b.get("share")).longValue(), ((Number) a.get("share")).longValue()));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("dim", d);
        out.put("rows", rows);
        out.put("total", statRow("合计", total));
        out.put("ranks", ranks.size() > 5 ? ranks.subList(0, 5) : ranks);
        out.put("rankTotal", ranks.size());
        out.put("allRanks", ranks);
        out.put("updatedAt", StaffSupport.fmt(new Date(), "yyyy-MM-dd HH:mm"));
        return out;
    }

    /** 展示名；同名的员工在名字后面加上编号区分，分组本身是按员工 id 分的 */
    private static String uniqueName(Map<String, String> names, String key)
    {
        String n = names.get(key);
        if (n == null)
        {
            return key;
        }
        int same = 0;
        for (String other : names.values())
        {
            if (n.equals(other))
            {
                same++;
            }
        }
        return same > 1 ? n + " #" + key : n;
    }

    private static String displayName(Map<String, Object> s)
    {
        String n = str(s.get("nickname"));
        return n.isEmpty() ? "员工" + s.get("staffUserId") : n;
    }

    private static Map<String, Object> statRow(String name, long[] g)
    {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("name", name);
        r.put("visit", g[0]);
        r.put("apply", g[1]);
        r.put("pass", g[2]);
        r.put("rate", g[0] == 0 ? "—" : String.format("%.2f%%", g[2] * 100.0 / g[0]));
        return r;
    }

    // ---------- 访问与报名归因（用户侧调用） ----------

    /** 记一次分享访问；同一访客同一分享码一天只记一次。visitorKey 由控制器按登录态或 IP 生成 */
    public boolean visit(String code, String visitorKey)
    {
        if (code == null || !code.matches("[A-Z0-9]{8}") || visitorKey == null)
        {
            return false;
        }
        Map<String, Object> s = db.one("select job_id from kg_job_share where share_code = :c", p("c", code));
        if (s == null)
        {
            return false;
        }
        int n = db.update("insert ignore into kg_job_share_visit (share_code, visitor_key, visit_date, create_time) "
                + "values (:c, :k, curdate(), now())", p("c", code, "k", StaffSupport.cut(visitorKey, 64)));
        if (n > 0)
        {
            db.update("update kg_job_share set visits = visits + 1 where share_code = :c", p("c", code));
            db.update("update kg_job set share_visits = share_visits + 1 where job_id = :j", p("j", s.get("jobId")));
        }
        return n > 0;
    }

    /** 报名来源归因：只认本人的报名、只写一次、分享码必须属于同一个岗位 */
    public boolean bind(Long appId, Long userId, String code)
    {
        if (code == null || !code.matches("[A-Z0-9]{8}"))
        {
            return false;
        }
        return db.update("update kg_application a join kg_job_share s on s.share_code = :c and s.job_id = a.job_id "
                + "set a.share_code = :c where a.app_id = :a and a.app_user_id = :u and a.share_code is null",
                p("c", code, "a", appId, "u", userId)) > 0;
    }
}
