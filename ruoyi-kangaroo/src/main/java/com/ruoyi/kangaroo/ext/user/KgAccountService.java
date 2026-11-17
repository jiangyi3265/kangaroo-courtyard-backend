package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 个人中心的账号能力：资料编辑、授权管理（同意记录、简历可见范围）、登录设备、实名核验（可插拔，未开通时如实拒绝）、
 * 积分与等级、个人数据导出、账号注销（先查阻塞项，再清个人资料并保留依法要留存的交易记录）。
 */
@Service
public class KgAccountService
{
    /** 六级等级的积分门槛，与个人中心的等级阶梯一一对应 */
    static final long[] LEVEL_POINTS = { 0, 100, 300, 800, 2000, 5000 };

    static final String[] LEVEL_NAMES = { "幼崽鼠", "小袋鼠", "袋鼠", "大袋鼠", "巨袋鼠", "袋鼠王" };

    static final Map<String, String> GENDERS = new LinkedHashMap<String, String>();
    static
    {
        GENDERS.put("0", "未填写");
        GENDERS.put("1", "男");
        GENDERS.put("2", "女");
    }

    /** 注销前必须先处理完的业务 */
    static final List<String> APP_BUSY = Arrays.asList("submitted", "review_passed", "interview_booked", "interview_done", "store_confirmed", "user_confirmed",
            "contract_signed", "onboarded");

    @Autowired
    private KgJdbc db;

    @Autowired private KgLegalDocuments legalDocuments;

    @Autowired
    private KgMemberService membership;

    @Autowired
    private KgPointsService pointsService;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private KangarooProperties properties;

    @Autowired
    private AppTokenService tokens;

    @Autowired
    private com.ruoyi.kangaroo.ext.core.KgArrivalTickets arrivalTickets;

    @Autowired
    private com.ruoyi.kangaroo.service.IKgMessageService messages;

    @Value("${kangaroo.file.sign-secret:${token.secret}}")
    private String signSecret;

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

    static String maskPhone(String phone)
    {
        return phone == null || phone.length() < 7 ? "" : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    static String maskName(String name)
    {
        if (name == null || name.isEmpty())
        {
            return "";
        }
        return name.length() == 1 ? name : name.charAt(0) + "**".substring(0, Math.min(2, name.length() - 1));
    }

    private Long me()
    {
        return AppUserContext.require().getUserId();
    }

    private Map<String, Object> user(Long uid)
    {
        return db.must("select * from kg_app_user where user_id = :u and del_flag = '0'", p("u", uid), "账号不存在");
    }

    // ---------------------------------------------------------------- 资料

    public Map<String, Object> profile()
    {
        Long uid = me();
        Map<String, Object> u = user(uid);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("memberNo", uid);
        m.put("nickname", str(u.get("nickname")));
        m.put("avatar", u.get("avatar"));
        m.put("gender", str(u.get("gender")).isEmpty() ? "0" : str(u.get("gender")));
        m.put("genderLabel", GENDERS.getOrDefault(str(u.get("gender")), "未填写"));
        String bd = str(u.get("birthday"));
        m.put("birthday", bd);
        m.put("birthdayLabel", bd.matches("\\d{2}-\\d{2}") ? Integer.parseInt(bd.substring(0, 2)) + "月" + Integer.parseInt(bd.substring(3)) + "日" : "");
        m.put("city", str(u.get("city")));
        m.put("intro", str(u.get("intro")));
        m.put("phone", maskPhone(str(u.get("phone"))));
        m.put("phoneBound", !str(u.get("phone")).isEmpty());
        m.put("phoneVerified", u.get("phoneVerifiedTime") != null);
        boolean realOk = u.get("realNameVerifiedTime") != null;
        m.put("realNameVerified", realOk);
        m.put("realNameLabel", realOk ? maskName(str(u.get("realName"))) + "（已实名）" : ("none".equals(properties.getRealNameMode()) ? "未开通" : "未认证"));
        m.put("idLast4", u.get("idLast4"));
        m.put("realNameEnabled", !"none".equals(properties.getRealNameMode()));
        m.put("smsEnabled", !"none".equals(properties.getSmsMode()));
        m.put("registerDays", u.get("registerTime") == null ? null
                : ChronoUnit.DAYS.between(LocalDate.parse(str(u.get("registerTime")).substring(0, 10)), LocalDate.now()) + 1);
        m.putAll(points(1));
        return m;
    }

    /**
     * 昵称保留词（2026-10-16 内测反馈 2.0，主人定的词表）：防止有人起名冒充官方、客服。
     * 不做全站昵称唯一（默认昵称本来就重名，大多数社交产品也允许同名）。
     * 不放进 kg_sensitive_word：那张表社区发帖共用，加了「客服」「官方」后普通帖子提到这些字都会被转人工审核。
     */
    static final String[] RESERVED_NICKNAME_WORDS = { "官方", "客服", "管理员", "系统", "平台", "袋鼠小院", "袋鼠旅游", "袋鼠青年", "袋鼠全球青年", "小助手" };

    /** 先统一全角半角、去掉空格和标点符号（「官 方」「客-服」「袋鼠·小院」）、不分大小写，再看是否含保留词 */
    static boolean reservedNickname(String nickname)
    {
        String n = Normalizer.normalize(nickname == null ? "" : nickname, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}\\p{P}\\p{S}\\p{Cc}\\p{Cf}]+", "").toLowerCase(Locale.ROOT);
        for (String w : RESERVED_NICKNAME_WORDS)
        {
            if (n.contains(w))
            {
                return true;
            }
        }
        return false;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveProfile(Map<String, Object> b)
    {
        Long uid = me();
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        if (b.containsKey("nickname"))
        {
            String v = str(b.get("nickname")).trim();
            if (v.isEmpty() || v.length() > 16)
            {
                throw new ServiceException("昵称 1–16 个字", 422);
            }
            if (reservedNickname(v))
            {
                throw new ServiceException("昵称不能使用官方或平台相关字样", 422);
            }
            if ("review".equals(guard.check(v)))
            {
                throw new ServiceException("昵称里不要写联系方式或敏感内容", 422);
            }
            cols.put("nickname", v);
        }
        if (b.containsKey("gender"))
        {
            String v = str(b.get("gender"));
            if (!GENDERS.containsKey(v))
            {
                throw new ServiceException("性别不合法", 422);
            }
            cols.put("gender", v);
        }
        if (b.containsKey("birthday"))
        {
            String v = str(b.get("birthday")).trim();
            if (!v.isEmpty())
            {
                if (!v.matches("\\d{2}-\\d{2}"))
                {
                    throw new ServiceException("生日格式为 MM-dd，例如 08-29", 422);
                }
                try
                {
                    LocalDate.parse("2024-" + v);
                }
                catch (Exception e)
                {
                    throw new ServiceException("没有这个日期", 422);
                }
            }
            cols.put("birthday", v.isEmpty() ? null : v);
        }
        if (b.containsKey("city"))
        {
            cols.put("city", StaffSupport.cut(str(b.get("city")).trim(), 32));
        }
        if (b.containsKey("intro"))
        {
            String v = str(b.get("intro")).trim();
            if (v.length() > 60)
            {
                throw new ServiceException("自我介绍 60 字以内", 422);
            }
            if (!v.isEmpty() && "review".equals(guard.check(v)))
            {
                throw new ServiceException("自我介绍里不要写联系方式或敏感内容", 422);
            }
            cols.put("intro", v.isEmpty() ? null : v);
        }
        if (b.containsKey("avatar"))
        {
            String v = str(b.get("avatar")).trim();
            if (!v.isEmpty() && !KgJdbc.isPlatformImage(v))
            {
                throw new ServiceException("头像只能使用本平台上传的图片", 422);
            }
            cols.put("avatar", v.isEmpty() ? null : v);
        }
        if (cols.isEmpty())
        {
            throw new ServiceException("没有要修改的内容", 422);
        }
        cols.put("update_time", new Date());
        db.updateRow("kg_app_user", "user_id", uid, cols);
        return profile();
    }

    // ---------------------------------------------------------------- 授权管理

    /**
     * 登录页「我已阅读并同意」的两份文档。全文由运营在后台「通知公告」发布（AppLegalController 按标题取最新一条），
     * 同意记录使用正文内容版本；没有可读的已发布正文时不能登记同意。
     */
    static final Map<String, String> LEGAL_DOCS = new LinkedHashMap<String, String>();
    static
    {
        LEGAL_DOCS.put("terms", "用户协议");
        LEGAL_DOCS.put("privacy", "隐私政策");
    }

    /** 同意记录里的文档标题；文档已下线时原样显示 key，不报错 */
    static String docTitle(String key)
    {
        if (LEGAL_DOCS.containsKey(key))
        {
            return "《" + LEGAL_DOCS.get(key) + "》";
        }
        try
        {
            return str(KgCommunityGuard.doc(key).get("title"));
        }
        catch (Exception e)
        {
            return key;
        }
    }

    public Map<String, Object> authorizations()
    {
        Long uid = me();
        Map<String, Object> u = user(uid);
        List<Map<String, Object>> consents = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> c : db.list("select doc, version, agree_time from kg_consent where app_user_id = :u order by agree_time desc", p("u", uid)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("doc", c.get("doc"));
            m.put("title", docTitle(str(c.get("doc"))));
            m.put("version", c.get("version"));
            m.put("time", StaffSupport.fmt(StaffSupport.parse(c.get("agreeTime")), "yyyy-MM-dd"));
            consents.add(m);
        }
        List<String> stores = new ArrayList<String>();
        for (Map<String, Object> s : db.list("select distinct s.store_name from kg_application a join kg_store s on s.store_id = a.store_id "
                + "where a.app_user_id = :u and a.del_flag = '0'", p("u", uid)))
        {
            stores.add(str(s.get("storeName")));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("consents", consents);
        m.put("resumeStores", stores);
        m.put("phone", maskPhone(str(u.get("phone"))));
        m.put("phoneVerified", u.get("phoneVerifiedTime") != null);
        m.put("notifyOn", db.count("select count(*) from kg_notify_pref where app_user_id = :u and enabled = '1'", p("u", uid)));
        m.put("note", "报名时你的简历（含联系方式）会提供给所报名的门店，门店侧手机号脱敏展示；撤回同意后，再次使用对应功能时会重新征求同意。");
        return m;
    }

    /**
     * 登录时勾选的《用户协议》《隐私政策》：之前这个勾选只留在页面里，「授权管理」显示「还没有同意记录」。
     * 同一版本重复同意只留第一次的时间（kg_consent 按 用户 + 文档 + 版本 唯一）。
     */
    @Transactional(rollbackFor = Exception.class)
    public String agreeLegal()
    {
        return agreeLegal(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public String agreeLegal(Map<String, String> versions)
    {
        Long uid = me();
        List<Map<String, Object>> docs = legalDocuments.requirePublished();
        if (versions != null && !versions.isEmpty())
            for (Map<String, Object> d : docs)
                if (!d.get("version").equals(versions.get(d.get("key") + "Version")))
                    throw new ServiceException("协议已更新，请重新阅读并同意后继续", 409);
        for (Map<String, Object> d : docs)
        {
            db.update("insert ignore into kg_consent (app_user_id, doc, version, agree_time) values (:u, :d, :v, now())",
                    p("u", uid, "d", d.get("key"), "v", d.get("version")));
        }
        return "已记录";
    }

    @Transactional(rollbackFor = Exception.class)
    public String withdrawConsent(String doc)
    {
        Long uid = me();
        if (LEGAL_DOCS.containsKey(doc))
        {
            // 这两份是使用小程序的前提，单独撤回之后也不会有地方再征求同意；不想再用的路是注销账号
            throw new ServiceException("用户协议和隐私政策不能单独撤回，不再使用可以注销账号");
        }
        int n = db.update("delete from kg_consent where app_user_id = :u and doc = :d", p("u", uid, "d", doc));
        return n > 0 ? "已撤回同意，再次使用相关功能时会重新征求你的同意" : "没有找到这条同意记录";
    }

    // ---------------------------------------------------------------- 黑名单

    public Map<String, Object> blocks()
    {
        Long uid = me();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select b.blocked_id, u.nickname, u.avatar, b.create_time from kg_user_block b "
                + "left join kg_app_user u on u.user_id = b.blocked_id where b.user_id = :u order by b.id desc", p("u", uid)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("userId", r.get("blockedId"));
            m.put("name", str(r.get("nickname")).isEmpty() ? "袋鼠用户" : str(r.get("nickname")));
            m.put("avatar", r.get("avatar"));
            m.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "yyyy-MM-dd"));
            rows.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rows", rows);
        m.put("note", "拉黑后，对方的帖子、评论、找搭子不会再出现在你的列表里，你们也无法互相关注。");
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public String unblock(Long target)
    {
        Long uid = me();
        int n = db.update("delete from kg_user_block where user_id = :u and blocked_id = :t", p("u", uid, "t", target));
        return n > 0 ? "已移出黑名单" : "对方不在黑名单里";
    }

    // ---------------------------------------------------------------- 登录设备

    public Map<String, Object> sessions()
    {
        AppLoginUser me = AppUserContext.require();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rows", tokens.listSessions(me.getUserId(), me.getToken()));
        Map<String, Object> u = user(me.getUserId());
        m.put("lastLogin", u.get("lastLoginTime") == null ? null : StaffSupport.fmt(StaffSupport.parse(u.get("lastLoginTime")), "yyyy-MM-dd HH:mm"));
        m.put("lastIp", u.get("lastLoginIp"));
        return m;
    }

    public String revokeOtherSessions()
    {
        AppLoginUser me = AppUserContext.require();
        int n = tokens.revokeOthers(me.getUserId(), me.getToken());
        return n == 0 ? "没有其他登录设备" : "已退出其他 " + n + " 台设备";
    }

    public String revokeSession(String id)
    {
        AppLoginUser me = AppUserContext.require();
        if (id == null || id.isEmpty())
        {
            throw new ServiceException("请选择要退出的设备", 422);
        }
        if (id.equals(me.getToken()))
        {
            throw new ServiceException("这是当前设备，请用「退出登录」", 422);
        }
        return tokens.revokeOne(me.getUserId(), id) ? "已退出该设备" : "该设备已经退出登录了";
    }

    /** 账号上绑定的完整手机号（只在服务端内部用：发验证码、校验验证码，不下发给页面） */
    public String boundPhone(Long uid)
    {
        Map<String, Object> r = db.one("select phone from kg_app_user where user_id = :u and del_flag = '0'", p("u", uid));
        String phone = r == null ? "" : str(r.get("phone"));
        if (phone.isEmpty())
        {
            throw new ServiceException("还没有绑定手机号，请先在「我的 · 账号与安全」里绑定", 422);
        }
        return phone;
    }

    // ---------------------------------------------------------------- 实名核验

    private String hashId(String idNo)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(idNo.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : out)
            {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            throw new ServiceException("核验失败，请稍后再试");
        }
    }

    /** 18 位身份证校验位（GB 11643），只做格式与校验位判断，不代表任何官方核验 */
    static boolean idChecksumOk(String id)
    {
        if (!id.matches("\\d{17}[0-9Xx]"))
        {
            return false;
        }
        int[] w = { 7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2 };
        char[] check = { '1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2' };
        int sum = 0;
        for (int i = 0; i < 17; i++)
        {
            sum += (id.charAt(i) - '0') * w[i];
        }
        return check[sum % 11] == Character.toUpperCase(id.charAt(17));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> realNameVerify(String name, String idNo)
    {
        Long uid = me();
        if ("none".equals(properties.getRealNameMode()))
        {
            throw new ServiceException("实名核验服务尚未开通，开通后才能在这里完成认证", 503);
        }
        String n = name == null ? "" : name.trim();
        String id = idNo == null ? "" : idNo.trim().toUpperCase();
        if (!n.matches("[\\u4e00-\\u9fa5·]{2,16}"))
        {
            throw new ServiceException("请填写身份证上的中文姓名", 422);
        }
        if (!idChecksumOk(id))
        {
            throw new ServiceException("身份证号格式或校验位不正确", 422);
        }
        String hash = hashId(id);
        Map<String, Object> mine = db.one("select id_hash, real_name_verified_time from kg_app_user where user_id = :u and status='0' and del_flag='0' for update", p("u", uid));
        if (mine == null) throw new ServiceException("账号当前不可用", 403);
        if (mine != null && mine.get("realNameVerifiedTime") != null && !hash.equals(str(mine.get("idHash"))))
        {
            // 已实名的账号不能自己换一张身份证覆盖身份（合同、保险都挂在实名上）；确需更换走客服人工处理（2026-09-26 回归发现）
            throw new ServiceException("账号已完成实名，不能更换为其他证件；如需更正请联系客服", 409);
        }
        Map<String, Object> other =db.one("select user_id from kg_app_user where id_hash = :h and user_id <> :u and del_flag = '0'", p("h", hash, "u", uid));
        if (other != null)
        {
            throw new ServiceException("该证件已在其他账号完成实名，请先注销那个账号", 409);
        }
        try
        {
            db.update("update kg_app_user set real_name = :n, id_last4 = :l, id_hash = :h, real_name_verified_time = now(), update_time = now() where user_id = :u",
                    p("n", n, "l", id.substring(id.length() - 4), "h", hash, "u", uid));
        }
        catch (org.springframework.dao.DuplicateKeyException e)
        {
            throw new ServiceException("该证件已在其他账号完成实名，请先核实账号归属", 409);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message", "mock".equals(properties.getRealNameMode()) ? "已通过（本地模拟核验：只校验了证件号格式与校验位）" : "实名认证已通过");
        m.put("name", maskName(n));
        m.put("idLast4", id.substring(id.length() - 4));
        m.put("simulated", "mock".equals(properties.getRealNameMode()));
        return m;
    }

    // ---------------------------------------------------------------- 积分与等级

    /**
     * 之前：积分 = 全部流水之和，等级也按它算，积分只能看不能用。
     * 现在积分能花（加急投递），交给 KgPointsService：返回可用 balance（points 字段同值）、累计获得 earned、已使用 spent，
     * 等级按累计获得算（花积分不降级），明细带来源类别，规则文案里的数值读后台参数设置。
     */
    public Map<String, Object> points(int page)
    {
        return pointsService.summary(me(), page);
    }

    // ---------------------------------------------------------------- 账号注销

    public Map<String, Object> deactivationCheck()
    {
        Long uid = me();
        List<String> blockers = new ArrayList<String>();
        if (db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0'", p("u", uid)) > 0)
        {
            // 合作商家能自己退出；换工主理人、商家服务人、城市负责人只有平台能撤（本店主理人撤不了，之前一律叫他找主理人，找了也没用）
            long merchant = db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0' and role_code = 'merchant'", p("u", uid));
            long platform = db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0' and role_code in ('manager', 'merchant_service', 'city_lead')", p("u", uid));
            long all = db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0'", p("u", uid));
            if (merchant > 0)
            {
                blockers.add("你还是合作商家，请先在商家端「我的」里退出合作商家");
            }
            if (platform > 0)
            {
                blockers.add("你的员工身份（换工主理人、商家服务人或城市负责人）只有平台能撤销，请联系客服");
            }
            if (all > merchant + platform)
            {
                blockers.add(com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() ? "你还是门店员工，员工授权由平台撤销，请联系客服" : "你还是门店员工，请先请主理人撤销员工授权");
            }
        }
        // 线上任务的报名走不到入职 / 终止这类终态（验收、付款、确认收款都在结算单上），结算已确认收款的不能再算「进行中」，
        // 否则做完任务的人永远注销不了账号
        long apps = db.count("select count(*) from kg_application a join kg_job j on j.job_id = a.job_id where a.app_user_id = :u and a.del_flag = '0' and a.status in ('"
                + String.join("','", APP_BUSY) + "') and not (j.job_type = 'online' and exists (select 1 from kg_online_settlement s where s.app_id = a.app_id and s.status = 'confirmed'))",
                p("u", uid));
        if (apps > 0)
        {
            blockers.add("有 " + apps + " 条进行中的报名，请先完成或撤回");
        }
        // 报名撤回不代表已交付成果已处理；只看最新一轮，避免旧退回记录永久阻止已结清账号注销。
        long unfinishedWork = db.count("select count(*) from kg_application a where a.app_user_id = :u "
                + "and exists (select 1 from kg_online_submission x where x.app_id = a.app_id and x.status in ('submitted','returned','accepted') "
                + "and x.round = (select max(x2.round) from kg_online_submission x2 where x2.app_id = a.app_id)) "
                + "and not exists (select 1 from kg_online_settlement s where s.app_id = a.app_id and s.status = 'confirmed')", p("u", uid));
        if (unfinishedWork > 0)
        {
            blockers.add("有 " + unfinishedWork + " 份线上任务成果尚未处理完成，请联系门店或平台处理后再注销");
        }
        // 退款中（refunding：已发起退款、钱还在路上，退款失败的也停在这里等平台重退）和退款审核中一样要挡
        long stays = db.count("select count(*) from kg_stay_order where app_user_id = :u and del_flag = '0' and status in ('pending_payment','confirmed','checked_in','refund_pending','refunding')",
                p("u", uid));
        if (stays > 0)
        {
            blockers.add("有 " + stays + " 个住宿订单未完成或退款处理中");
        }
        long afterRefunds = db.count("select count(*) from kg_stay_after_refund a left join kg_stay_staff_refund r on r.refund_id=a.staff_refund_id "
                + "where a.app_user_id=:u and (a.status='pending' or (a.status='approved' and (r.status is null or r.status='processing')))",p("u",uid));
        if(afterRefunds>0)blockers.add("有退房后退款申请待审核或退款处理中，请处理完后再注销");
        long shops = db.count("select count(*) from kg_shop_order where app_user_id = :u and del_flag = '0' and status in ('pending_payment','unused','refund_pending','refunding')", p("u", uid));
        if (shops > 0)
        {
            blockers.add("有 " + shops + " 个二消订单未完成或退款处理中");
        }
        long acts = db.count("select count(*) from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id where g.app_user_id = :u "
                + "and (g.status = 'pending_payment' or g.status = 'refund_pending' or g.status = 'refunding' or (g.status in ('confirmed','waitlist') and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) > now()))",
                p("u", uid));
        if (acts > 0)
        {
            blockers.add("有 " + acts + " 个活动报名未结束或退款处理中");
        }
        // 自己发起、还没结束的活动（待审核 / 报名中 / 暂停 / 截止）：之前注销后活动照样「报名中」，别人还能报，已报名的人也收不到消息。
        // 没开始的先取消（取消会通知报名的人、处理退款），已经开始的等它结束
        for (Map<String, Object> r : db.list("select title, start_time from kg_activity where app_user_id = :u and source = 'user' and del_flag = '0' "
                + "and status in ('pending','open','paused','closed') and coalesce(end_time, date_add(start_time, interval 4 hour)) > now() "
                + "order by start_time, activity_id limit 5", p("u", uid)))
        {
            Date start = StaffSupport.parse(r.get("startTime"));
            boolean started = start != null && !new Date().before(start);
            blockers.add("你发起的活动「" + str(r.get("title")) + "」还没结束，" + (started ? "请等活动结束后再注销" : "请先在「我的活动 › 我发起的」里取消，再注销"));
        }
        long wallet =db.count("select coalesce(sum(amount_fen), 0) from kg_wallet_entry where app_user_id = :u", p("u", uid))
                - db.count("select coalesce(sum(amount_fen), 0) from kg_wallet_withdraw where app_user_id = :u and status in ('pending','approved','paid')", p("u", uid));
        if (wallet > 0)
        {
            blockers.add("钱包还有 ¥" + java.math.BigDecimal.valueOf(wallet, 2).toPlainString() + " 未结算，请先申请结算并确认收款");
        }
        else if (wallet < 0)
        {
            blockers.add("钱包有 ¥" + java.math.BigDecimal.valueOf(wallet, 2).abs().toPlainString() + " 欠款待核对，请联系平台处理后再注销");
        }
        if (db.count("select count(*) from kg_wallet_withdraw where app_user_id = :u and status in ('pending','approved')", p("u", uid)) > 0)
        {
            blockers.add("有结算申请正在处理中");
        }
        if (db.count("select count(*) from kg_online_settlement where app_user_id = :u and status in ('pending_payout','payout_registered','disputed')", p("u", uid)) > 0)
        {
            blockers.add("有线上任务的结算还没确认收款");
        }
        if (db.count("select count(*) from kg_gift_claim where app_user_id = :u and status = 'pending'", p("u", uid)) > 0)
        {
            blockers.add("生日礼物还没发货");
        }
        // 采购报销（V66）：待审核、已通过待打款的，人走了平台不知道钱打给谁，要先办完。驳回的不挡（人不交了就不交了）
        long reimburse = db.count("select count(*) from kg_reimbursement where app_user_id = :u and del_flag = '0' and status in ('pending','approved')", p("u", uid));
        if (reimburse > 0)
        {
            blockers.add("有 " + reimburse + " 笔采购报销还没处理完（待审核或待打款），请联系平台处理后再注销");
        }
        // 心愿互助（V87，状态见 WishService）：自己发布还没结束的（待付款 / 审核中 / 待接单 / 已接单 / 已交付 / 平台介入 / 退款中 / 退款失败）、
        // 自己接下还没完成的、做完了平台还没打酬劳的——人走了对方和平台都找不到人，钱也不知道打给谁
        long wishes = db.count("select count(*) from kg_wish where publisher_id = :u "
                + "and status in ('pending_payment','reviewing','open','accepted','submitted','disputed','refunding','refund_failed')", p("u", uid));
        if (wishes > 0)
        {
            blockers.add("有 " + wishes + " 个你发布的心愿还没结束或退款处理中，请先在「我的心愿 › 我发布的」里处理完再注销");
        }
        long helping = db.count("select count(*) from kg_wish where helper_id = :u and status in ('accepted','submitted','disputed')", p("u", uid));
        if (helping > 0)
        {
            blockers.add("有 " + helping + " 个你接下的心愿还没完成，请先在「我的心愿 › 我帮助的」里处理完再注销");
        }
        long wishPayout = db.count("select count(*) from kg_wish where helper_id = :u and status = 'completed' and payout_status = 'pending'", p("u", uid));
        if (wishPayout > 0)
        {
            blockers.add("有 " + wishPayout + " 笔心愿酬劳平台还没打款，请联系平台处理后再注销");
        }
        // 积分商城兑换（V84，状态见 KgPointMallService）：待发货 / 待领取、已发货待确认收货的还没完
        long redeems = db.count("select count(*) from kg_point_goods_order where app_user_id = :u and status in ('pending','shipped')", p("u", uid));
        if (redeems > 0)
        {
            blockers.add("有 " + redeems + " 个积分商城兑换还没完成（待发货、待领取或已发货），请先在「积分商城 › 兑换记录」里取消兑换或确认收货，再注销");
        }
        // 纠纷调解（V79，状态见 KgMediationService）：本人是订单当事人或发起人、还没结案（closed 才算结案）的工单
        long cases = db.count("select count(*) from kg_mediation_case where (user_id = :u or initiator_id = :u) and status <> 'closed'", p("u", uid));
        if (cases > 0)
        {
            blockers.add("有 " + cases + " 件纠纷调解还没结案，请在「我的」页「纠纷调解 › 我的调解」里跟进到结案后再注销");
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("blockers", blockers);
        m.put("canDeactivate", blockers.isEmpty());
        m.put("willDelete", Arrays.asList("昵称、头像、性别、生日、城市、自我介绍、手机号与实名信息", "简历与经历、技能证书、提醒设置、同意记录、订阅与收藏",
                "社区帖子与评论、关注与拉黑、行程计划、人才库里的标记"));
        m.put("willKeep", Arrays.asList("已完成的订单、支付与退款记录（依法保留，用于对账与售后）", "已发布的评价和小院评论（会转为匿名展示）", "门店侧的报名流转记录（不含你的联系方式）", "采购报销记录（财务凭据，依法保留，提交人转为匿名）"));
        m.put("confirmWord", "确认注销");
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deactivate(String confirm, String reason)
    {
        Long uid = me();
        // 线上提交/验收也先锁同一账号；检查阻塞与匿名化之间不能插入新的未结成果或结算。
        Map<String, Object> locked = db.one("select status, del_flag from kg_app_user where user_id = :u for update", p("u", uid));
        if (locked == null || !"0".equals(str(locked.get("status"))) || !"0".equals(str(locked.get("delFlag"))))
        {
            throw new ServiceException("账号已注销或不可用", 409);
        }
        Map<String, Object> check = deactivationCheck();
        if (!Boolean.TRUE.equals(check.get("canDeactivate")))
        {
            throw new ServiceException("还有未完成的业务，暂时不能注销", 409);
        }
        if (!"确认注销".equals(confirm == null ? "" : confirm.trim()))
        {
            throw new ServiceException("请输入「确认注销」以确认", 422);
        }
        Map<String, Object> u = user(uid);
        Map<String, Object> log = new LinkedHashMap<String, Object>();
        log.put("app_user_id", uid);
        log.put("nickname", StaffSupport.cut(str(u.get("nickname")), 64));
        log.put("reason", StaffSupport.cut(reason == null ? "" : reason.trim(), 200));
        log.put("detail", "已清除个人资料、简历、社区内容与授权记录；交易与资金记录依法保留并与匿名账号关联");
        log.put("create_time", new Date());
        db.insert("kg_account_deletion", log);

        // Account -> application -> arrival is the common erasure/write lock order.
        db.list("select app_id from kg_application where app_user_id = :u order by app_id for update", p("u", uid));
        for (Map<String, Object> arrival : db.list("select app_id from kg_app_arrival where app_user_id = :u "
                + "or app_id in (select app_id from kg_application where app_user_id = :u) order by app_id for update", p("u", uid)))
        {
            arrivalTickets.protectBeforeRemoval(KgJdbc.asLong(arrival.get("appId")));
        }
        // Keep business IDs/statuses/amounts, erase personal snapshots and invalidated shares.
        // These exact business links also cover internal forwarded messages to other recipients.
        db.update("update kg_message m join kg_application a on m.biz_type = 'application' and m.biz_id = a.app_id "
                + "set m.title = '关联账号已注销', m.content = '该报名人的个人资料已清除', m.route = null, m.del_flag = '2' "
                + "where a.app_user_id = :u", p("u", uid));
        db.update("update kg_message m join kg_resume_forward f on m.biz_type = 'resume_forward' and m.biz_id = f.forward_id "
                + "join kg_application a on a.app_id = f.app_id set m.title = '简历转发已失效', m.content = '关联账号已注销', "
                + "m.route = null, m.del_flag = '2' where a.app_user_id = :u", p("u", uid));
        db.update("delete f from kg_resume_forward f join kg_application a on a.app_id = f.app_id where a.app_user_id = :u", p("u", uid));
        db.update("delete from kg_app_arrival where app_user_id = :u or app_id in (select app_id from kg_application where app_user_id = :u)", p("u", uid));
        db.update("update kg_application set real_name = '已注销用户', phone = '', age = 0, skills = null, note = null, update_time = now() "
                + "where app_user_id = :u", p("u", uid));

        // Keep support workflow/audit identities, but erase all user-provided snapshots.
        // createTicket/reply hold the same account lock before touching these rows.
        db.list("select ticket_id from kg_support_ticket where app_user_id = :u order by ticket_id for update", p("u", uid));
        db.update("update kg_message m join kg_support_ticket t on m.biz_type = 'support_ticket' and m.biz_id = t.ticket_id "
                + "set m.title = '关联账号已注销', m.content = '工单个人内容已清除', m.route = null, m.del_flag = '2' where t.app_user_id = :u", p("u", uid));
        db.update("update kg_support_ticket set question = '关联账号已注销，原问题已清除', summary = null, related = null, reply = null, update_time = now() "
                + "where app_user_id = :u", p("u", uid));

        // The active account is already locked. Every actor write now follows
        // account -> activity -> signup, matching existing activity mutation locks.
        db.list("select activity_id from kg_activity where activity_id in (select activity_id from kg_activity_signup where app_user_id = :u) "
                + "or (source = 'user' and app_user_id = :u) order by activity_id for update", p("u", uid));
        db.list("select signup_id from kg_activity_signup where app_user_id = :u order by activity_id, signup_id for update", p("u", uid));
        // Legacy notices only identify the activity, not the attendee. Conservatively
        // remove attendee names from these exact generated templates for affected
        // activities; keep every notice ID, route, status, timestamp and recipient.
        db.update("update kg_message set content = '活动报名信息已更新，请进入活动查看当前参与者' "
                + "where biz_type = 'activity' and category = 'system' and title in ('活动有新报名','你发起的活动有新报名') "
                + "and biz_id in (select activity_id from kg_activity_signup where app_user_id = :u)", p("u", uid));
        // 参与者取消报名时发给门店 / 发起人的通知（正文带取消人姓名），同样抹掉
        db.update("update kg_message set content = '活动报名信息已更新，请进入活动查看当前参与者' "
                + "where biz_type = 'activity' and category = 'system' and title in ('活动有人取消报名','你发起的活动有人取消报名') "
                + "and biz_id in (select activity_id from kg_activity_signup where app_user_id = :u)", p("u", uid));
        db.update("update kg_message set content = '活动退款申请已处理，请进入活动查看记录' "
                + "where biz_type = 'activity' and category = 'order' and title = '活动退款待审批' "
                + "and biz_id in (select activity_id from kg_activity_signup where app_user_id = :u)", p("u", uid));
        db.update("update kg_activity_signup set name = '已注销用户', phone = null, update_time = now() where app_user_id = :u", p("u", uid));
        db.update("update kg_activity set organizer_name = '已注销用户', update_time = now() where source = 'user' and app_user_id = :u", p("u", uid));
        db.update("delete from kg_activity_subscribe where app_user_id = :u", p("u", uid));

        // 注销会批量软删评论、批量删收藏、删点赞：先记下受影响的帖子，删完按表重算评论数 / 收藏数 / 点赞数（原先这些计数就此漂移）
        // 排好序：重算时按帖子ID从小到大加锁，和点赞「先锁计数、再写通知」的顺序一致
        java.util.TreeSet<Long> touchedPosts = new java.util.TreeSet<Long>();
        // 本人的评论：普通 select 先读出主键（走 V45 的 idx_pc_user，不加锁），下面按主键从小到大软删——
        // 按 app_user_id 直接 update 会在评论表上锁一大片（以前没有这列的索引，等于锁全表），和点赞的处理方式一致
        java.util.TreeSet<Long> commentIds = new java.util.TreeSet<Long>();
        for (Map<String, Object> r : db.list("select comment_id, post_id, del_flag from kg_post_comment where app_user_id = :u", p("u", uid)))
        {
            if ("2".equals(str(r.get("delFlag"))))
            {
                continue;
            }
            addId(commentIds, r.get("commentId"));
            if ("0".equals(str(r.get("delFlag"))))
            {
                addId(touchedPosts, r.get("postId"));
            }
        }
        for (Map<String, Object> r : db.list("select distinct target_id pid from kg_favorite where app_user_id = :u and target_type = 'post'", p("u", uid)))
        {
            addId(touchedPosts, r.get("pid"));
        }
        // 本人点过的赞：普通 select 先读出主键（不加锁），再按主键删——点赞表没有 app_user_id 索引，按它删会锁一大片
        java.util.TreeSet<Long> postLikeIds = new java.util.TreeSet<Long>();
        for (Map<String, Object> r : db.list("select like_id, post_id from kg_post_like where app_user_id = :u", p("u", uid)))
        {
            addId(postLikeIds, r.get("likeId"));
            addId(touchedPosts, r.get("postId"));
        }
        java.util.TreeSet<Long> commentLikeIds = new java.util.TreeSet<Long>();
        java.util.TreeSet<Long> touchedComments = new java.util.TreeSet<Long>();
        for (Map<String, Object> r : db.list("select id, comment_id from kg_post_comment_like where app_user_id = :u", p("u", uid)))
        {
            addId(commentLikeIds, r.get("id"));
            addId(touchedComments, r.get("commentId"));
        }

        for (String sql : Arrays.asList(
                "delete from kg_resume_exp where app_user_id = :u",
                "delete from kg_resume where app_user_id = :u",
                "delete from kg_cert where app_user_id = :u",
                "delete from kg_notify_pref where app_user_id = :u",
                "delete from kg_consent where app_user_id = :u",
                "delete from kg_job_subscribe where app_user_id = :u",
                "delete from kg_app_notice_seen where user_id = :u",
                "delete from kg_notice_reply where thread_id in (select thread_id from kg_notice_thread where app_user_id = :u)",
                "delete from kg_notice_thread where app_user_id = :u",
                "delete from kg_job_favorite where app_user_id = :u",
                "delete from kg_favorite where app_user_id = :u",
                "delete from kg_user_follow where follower_id = :u",
                "delete from kg_user_follow where followee_id = :u",
                "delete from kg_user_block where user_id = :u",
                "delete from kg_team_member where app_user_id = :u",
                "delete from kg_trip_plan where app_user_id = :u",
                "delete from kg_talent_mark where app_user_id = :u",
                "delete from kg_talent_invite where app_user_id = :u",
                "delete from kg_support_log where app_user_id = :u",
                // AI袋鼠聊天记录（V72）：用户自己删不了，注销账号时才随账号一起清掉（个人信息保护法要求注销后删除）
                "delete from kg_support_chat where app_user_id = :u",
                "delete from kg_chat_member where app_user_id = :u",
                "update kg_post set del_flag = '2', update_time = now() where app_user_id = :u",
                "update kg_review set anonymous = '1', update_time = now() where app_user_id = :u",
                // 小院评论（V65）：已公开的转匿名，和评价一样；还没公开的（待审、驳回、下架）人都走了就不留了
                "update kg_stay_comment set anonymous = '1', update_time = now() where app_user_id = :u",
                "update kg_stay_comment set del_flag = '2', update_time = now() where app_user_id = :u and status <> 'online'",
                // 采购报销（V66）是财务记录：品名、数量、金额、日期、凭证、备注都留着，只把提交人转匿名（app_user_id 还在，指向已抹掉的账号行，和订单、钱包同一个做法）。
                // 待审、待打款走不到这里（deactivationCheck 挡住）。提交人自己撤回的不是财务记录，不留。审核结果的站内信只发给本人，下面按 recipient_id 一起下架
                "update kg_reimbursement set submitter_name = '已注销用户', anonymous = '1', update_time = now() where app_user_id = :u",
                "delete from kg_reimbursement where app_user_id = :u and del_flag <> '0'"))
        {
            db.update(sql, p("u", uid));
        }
        if (!commentIds.isEmpty())
        {
            db.update("update kg_post_comment set del_flag = '2', update_time = now() where comment_id in (:ids)",
                    p("ids", new ArrayList<Long>(commentIds)));
        }
        if (!postLikeIds.isEmpty())
        {
            db.update("delete from kg_post_like where like_id in (:ids)", p("ids", new ArrayList<Long>(postLikeIds)));
        }
        if (!commentLikeIds.isEmpty())
        {
            db.update("delete from kg_post_comment_like where id in (:ids)", p("ids", new ArrayList<Long>(commentLikeIds)));
        }
        // 计数重算要在下面改 kg_message 之前：点赞是「先锁计数行、再写通知」，这里同一顺序，不会互相等待成死锁
        if (!touchedPosts.isEmpty())
        {
            db.update("update kg_post p set p.comments = (select count(*) from kg_post_comment c where c.post_id = p.post_id and c.status = 'visible' and c.del_flag = '0'), "
                    + "p.favorites = (select count(*) from kg_favorite f where f.target_type = 'post' and f.target_id = p.post_id), "
                    + "p.likes = (select count(*) from kg_post_like l where l.post_id = p.post_id) where p.post_id in (:ids)",
                    p("ids", new ArrayList<Long>(touchedPosts)));
        }
        if (!touchedComments.isEmpty())
        {
            db.update("update kg_post_comment c set c.likes = (select count(*) from kg_post_comment_like l where l.comment_id = c.comment_id) "
                    + "where c.comment_id in (:ids)", p("ids", new ArrayList<Long>(touchedComments)));
        }
        // 别人收到的「晨光 等 3 人赞了你的动态」：本人是最近点赞的人时，改指剩下的人（一个不剩才删），不能整行跟着本人一起下架
        // 线上一定注入；只给不关心点赞的老单测（没注入）留个口子，跳过时这些行照样被下面按 actor_id 抹掉，不会留下本人信息
        if (messages != null)
        {
            messages.relinkLikesOf(uid);
        }
        for (String sql : Arrays.asList(
                "update kg_message set del_flag = '2' where recipient_id = :u",
                // 本人小院评论通过时发给小院主理人的通知（V65，正文引用了原文，没有 actor_id）：和别的注销抹法一样按业务关联抹掉
                "update kg_message m join kg_stay_comment c on m.biz_type = 'stay_comment' and m.biz_id = c.comment_id "
                        + "set m.title = '关联账号已注销', m.content = '对方的个人资料已清除', m.route = null, m.del_flag = '2' where c.app_user_id = :u and m.mode = 'staff'",
                // 别人收到的、由本人触发的评论 / 点赞通知：标题里有本人昵称、正文里有评论原文，一并抹掉并下架（只按本行 actor_id，不自查本表）
                "update kg_message set title = '关联账号已注销', content = '对方的个人资料已清除', route = null, actor_id = null, del_flag = '2' where actor_id = :u",
                // 2026-11-03 出行证件（V69，订票、买保险用的身份证号）：注销时整行删掉，不留密文
                "delete from kg_user_travel_id where user_id = :u"))
        {
            db.update(sql, p("u", uid));
        }
        db.update("update kg_app_user set nickname = '已注销用户', avatar = null, phone = null, phone_verified_time = null, real_name = null, id_last4 = null, id_hash = null, "
                + "real_name_verified_time = null, gender = '0', city = null, intro = null, birthday = null, unionid = null, openid = concat('deleted:', user_id, ':', unix_timestamp()), "
                + "status = '1', del_flag = '2', deactivated_time = now(), update_time = now(), remark = '账号已注销' where user_id = :u", p("u", uid));
        tokens.kickOut(uid);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("message", "账号已注销，感谢你曾经来过");
        return m;
    }

    private static void addId(java.util.Set<Long> ids, Object v)
    {
        Long id = KgJdbc.asLong(v);
        if (id != null)
        {
            ids.add(id);
        }
    }

    // ---------------------------------------------------------------- 数据导出

    private static List<String> row(Object... cells)
    {
        List<String> r = new ArrayList<String>();
        for (Object c : cells)
        {
            r.add(c == null ? "" : String.valueOf(c));
        }
        return r;
    }

    /** 个人数据副本：一个工作表一类数据，控制器负责写成 xlsx */
    public Map<String, List<List<String>>> exportSheets()
    {
        Long uid = me();
        Map<String, Object> u = user(uid);
        Map<String, List<List<String>>> out = new LinkedHashMap<String, List<List<String>>>();
        List<List<String>> acc = new ArrayList<List<String>>();
        acc.add(row("字段", "内容"));
        acc.add(row("账号ID", uid));
        acc.add(row("昵称", u.get("nickname")));
        acc.add(row("手机号", maskPhone(str(u.get("phone")))));
        acc.add(row("性别", GENDERS.getOrDefault(str(u.get("gender")), "未填写")));
        acc.add(row("生日", u.get("birthday")));
        acc.add(row("常驻城市", u.get("city")));
        acc.add(row("自我介绍", u.get("intro")));
        acc.add(row("实名状态", u.get("realNameVerifiedTime") == null ? "未实名" : "已实名（" + maskName(str(u.get("realName"))) + "，证件后四位 " + str(u.get("idLast4")) + "）"));
        // 2026-11-03 出行证件（V69）：本人数据副本里也写一行，证件号只给后四位（导出件会离开设备）
        Map<String, Object> travelId = db.one("select id_name, id_last4, coalesce(update_time, create_time) t from kg_user_travel_id where user_id = :u", p("u", uid));
        acc.add(row("出行证件（订票、买保险用）", travelId == null ? "未提交" : "已提交（" + maskName(str(travelId.get("idName"))) + "，证件后四位 " + str(travelId.get("idLast4")) + "，" + str(travelId.get("t")) + "）"));
        acc.add(row("注册时间", u.get("registerTime")));
        acc.add(row("最近登录", u.get("lastLoginTime")));
        out.put("账号信息", acc);

        List<List<String>> resume = sheet("select name, gender, age, phone, city, intro, skill_text, extra_text, update_time from kg_resume where app_user_id = :u",
                uid, "姓名", "性别", "年龄", "手机号", "城市", "自我介绍", "技能描述", "补充", "更新时间");
        // 简历里的手机号和「账号信息」同一规则打码（导出件会离开设备，页面也写着手机号是脱敏的）；第一行是表头
        for (int i = 1; i < resume.size(); i++)
        {
            List<String> line = resume.get(i);
            if (line.size() > 3)
            {
                line.set(3, maskPhone(line.get(3)));
            }
        }
        out.put("简历", resume);
        out.put("经历", sheet("select kind, title, place, start_date, end_date, content, source from kg_resume_exp where app_user_id = :u order by exp_id",
                uid, "类型", "名称", "单位/地点", "开始", "结束", "描述", "来源"));
        out.put("报名", sheet("select a.app_id, j.title, s.store_name, a.status, a.arrival_date, a.stay_days, a.submit_time from kg_application a "
                + "join kg_job j on j.job_id = a.job_id left join kg_store s on s.store_id = a.store_id where a.app_user_id = :u order by a.app_id",
                uid, "报名ID", "岗位", "门店", "状态", "到岗日期", "天数", "提交时间"));
        out.put("住宿订单", sheet("select o.order_no, s.stay_name, o.check_in, o.check_out, o.nights, o.pay_fen / 100 金额元, o.refund_fen / 100 退款元, o.status, o.create_time "
                + "from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id where o.app_user_id = :u order by o.order_id",
                uid, "订单号", "房源", "入住", "离店", "晚数", "金额(元)", "退款(元)", "状态", "下单时间"));
        out.put("二消订单", sheet("select order_no, use_date, time_slot, qty, pay_fen / 100 金额元, status, create_time from kg_shop_order where app_user_id = :u order by order_id",
                uid, "订单号", "使用日期", "时段", "数量", "金额(元)", "状态", "下单时间"));
        out.put("活动报名", sheet("select g.signup_no, a.title, a.start_time, g.fee_fen / 100 费用元, g.status, g.create_time from kg_activity_signup g "
                + "join kg_activity a on a.activity_id = g.activity_id where g.app_user_id = :u order by g.signup_id",
                uid, "报名号", "活动", "开始时间", "费用(元)", "状态", "报名时间"));
        out.put("我的评价", sheet("select biz_type, rating, content, reply, create_time from kg_review where app_user_id = :u and del_flag = '0' order by review_id",
                uid, "类型", "评分", "内容", "商家回复", "时间"));
        out.put("小院评论", sheet("select stay_name, content, images, video_url, source_type, status, audit_reason, create_time from kg_stay_comment where app_user_id = :u and del_flag = '0' order by comment_id",
                uid, "小院", "内容", "图片", "视频", "身份", "状态", "驳回/下架理由", "时间"));
        out.put("采购报销", sheet("select store_name, item_name, quantity, amount, purchase_date, receipts, remark, status, reject_reason, submit_time, paid_time from kg_reimbursement where app_user_id = :u and del_flag = '0' order by reimburse_id",
                uid, "门店", "品名", "数量", "金额(元)", "购买日期", "凭证", "备注", "状态", "驳回理由", "提交时间", "打款时间"));
        out.put("社区内容", sheet("select post_id, kind, title, content, status, create_time from kg_post where app_user_id = :u and del_flag = '0' order by post_id",
                uid, "帖子ID", "类型", "标题", "正文", "状态", "发布时间"));
        out.put("积分", sheet("select delta, reason, create_time from kg_point_entry where app_user_id = :u order by id", uid, "积分", "原因", "时间"));
        out.put("钱包流水", sheet("select biz_no, entry_type, source_type, amount_fen / 100 金额元, available_date, create_time from kg_wallet_entry where app_user_id = :u order by entry_id",
                uid, "单号", "类型", "来源", "金额(元)", "可结算日期", "时间"));
        out.put("授权与同意", sheet("select doc, version, agree_time from kg_consent where app_user_id = :u order by id", uid, "同意的文档", "版本", "同意时间"));
        out.put("消息", sheet("select title, content, category, create_time from kg_message where recipient_id = :u and del_flag = '0' order by msg_id desc limit 300",
                uid, "标题", "内容", "分类", "时间"));
        return out;
    }

    private List<List<String>> sheet(String sql, Long uid, String... headers)
    {
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(row((Object[]) headers));
        for (Map<String, Object> r : db.list(sql, p("u", uid)))
        {
            List<String> line = new ArrayList<String>();
            for (Object v : r.values())
            {
                line.add(v == null ? "" : String.valueOf(v));
            }
            rows.add(line);
        }
        return rows;
    }

    /** 导出留痕：谁在什么时候导出了自己的数据 */
    public void logExport(Long uid)
    {
        Map<String, Object> log = new LinkedHashMap<String, Object>();
        log.put("viewer_id", uid);
        log.put("target_id", uid);
        log.put("biz_type", "self_export");
        log.put("biz_id", uid);
        log.put("field", "personal_data");
        log.put("create_time", new Date());
        db.insert("kg_pii_access", log);
    }

}
