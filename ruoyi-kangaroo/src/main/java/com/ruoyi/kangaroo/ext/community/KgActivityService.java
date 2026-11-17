package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.util.AccountState;
import com.ruoyi.kangaroo.ext.pay.KgPayListener;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.stay.StayCodes;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/**
 * 社区活动：列表 / 推荐 / 详情 / 报名（名额、候补、在线支付仅限小院活动）/ 取消与退款 / 我的活动 / 发起与管理 / 签到 / 员工端数据。
 * 名额用活动行锁 + CAS 计数；在线支付走共享支付单（biz_type = activity_signup），退款一律人工审批。
 */
@Service
public class KgActivityService implements KgPayListener
{
    private static final Logger log = LoggerFactory.getLogger(KgActivityService.class);
    static final String BIZ = "activity_signup";
    static final Map<String, String> SOURCE_LABEL = new LinkedHashMap<String, String>();
    static final Map<String, String> SOURCE_TONE = new LinkedHashMap<String, String>();
    static final Map<String, String> STATUS_LABEL = new LinkedHashMap<String, String>();
    static final Map<String, String> SIGNUP_LABEL = new LinkedHashMap<String, String>();
    /** 发给城市负责人的活动退款待审批（KgMessageMapper 对城市负责人放行这一类；老的 activity 类型他看不到） */
    static final String BIZ_REFUND_PENDING = "activity_refund";
    /** 迟到支付转退款时写的取消原因开头（onPaid）；这种退款不能驳回 */
    static final String LATE_PAID = "报名已关闭后才到账";
    /**
     * 重新报名复用旧报名行时，旧支付单已付款没退完的，单号记进 refund_note（不加列）：「旧支付单 KP…（¥30 已付未退）」。
     * 之后审批写 refund_note 时用 KEEP_OLD_PAY 把这段接在后面留着，不被「同意 / 驳回」说明盖掉
     */
    static final String OLD_PAY_TAG = "旧支付单";
    static final String KEEP_OLD_PAY = "left(concat(:n, if(instr(coalesce(refund_note, ''), '" + OLD_PAY_TAG + "') > 0, "
            + "concat('；', substring(refund_note, instr(refund_note, '" + OLD_PAY_TAG + "'))), '')), 255)";
    static final List<String> CATEGORIES = Arrays.asList("手作课程", "生活体验", "小型聚会", "户外活动", "讲座沙龙", "其他");
    /** 2026-11-03 主人：活动类型「只留这四个」。新建只能选这四个（别的记成「其他」）；老活动的手作课程 / 讲座沙龙照常显示、照常筛选，修改时原样保留 */
    static final List<String> PICKABLE = Arrays.asList("生活体验", "小型聚会", "户外活动", "其他");
    /**
     * 2026-11-03 主人：「价格，免费，aa制三种付费方式」。表单提交 feeKind：free 免费 / paid 收费 / aa AA制。
     * 存法：免费 fee_mode = free、fee_fen = 0；收费 fee_fen > 0，fee_mode 跟来源走（online 小院在线支付 / offline 到场支付）；
     * AA制 fee_mode = aa、fee_fen 恒为 0（报名永远不建支付单），预估人均（选填）存 aa_fen（V71）
     */
    static final List<String> FEE_KINDS = Arrays.asList("free", "paid", "aa");
    /** 活动介绍字数上限（kg_activity.intro varchar(1000)） */
    static final int INTRO_MAX = 1000;
    static final int USER_INTRO_MAX = 2000;
    static final List<String> CHANNELS = Arrays.asList("home", "group", "share", "community", "official", "other");
    static final Map<String, String> CHANNEL_LABEL = new LinkedHashMap<String, String>();
    static final List<String> ACTIVE = Arrays.asList("pending_payment", "confirmed", "waitlist", "refund_pending", "refunding");
    /** 还能设置集合提醒的报名：报名成功，或待支付 / 候补（转成报名成功后按设置提醒） */
    static final List<String> REMINDABLE = Arrays.asList("pending_payment", "confirmed", "waitlist");
    private static final SecureRandom RND = new SecureRandom();
    private static final String[] WEEK = { "周日", "周一", "周二", "周三", "周四", "周五", "周六" };
    static
    {
        SOURCE_LABEL.put("yard", "小院活动");
        SOURCE_LABEL.put("merchant", "商家活动");
        SOURCE_LABEL.put("user", "用户自发");
        SOURCE_TONE.put("yard", "yard");
        SOURCE_TONE.put("merchant", "business");
        SOURCE_TONE.put("user", "user");
        STATUS_LABEL.put("draft", "草稿");
        STATUS_LABEL.put("pending", "待审核");
        STATUS_LABEL.put("open", "报名中");
        STATUS_LABEL.put("paused", "暂停报名");
        STATUS_LABEL.put("closed", "报名截止");
        STATUS_LABEL.put("ended", "已结束");
        STATUS_LABEL.put("cancelled", "已取消");
        STATUS_LABEL.put("rejected", "已驳回");
        SIGNUP_LABEL.put("pending_payment", "待支付");
        SIGNUP_LABEL.put("confirmed", "已确认");
        SIGNUP_LABEL.put("waitlist", "候补中");
        SIGNUP_LABEL.put("cancelled", "已取消");
        SIGNUP_LABEL.put("refund_pending", "退款审核中");
        SIGNUP_LABEL.put("refunding", "退款中");
        SIGNUP_LABEL.put("refunded", "已退款");
        SIGNUP_LABEL.put("closed", "已关闭");
        CHANNEL_LABEL.put("home", "小院首页");
        CHANNEL_LABEL.put("group", "群聊");
        CHANNEL_LABEL.put("share", "好友分享");
        CHANNEL_LABEL.put("community", "社区");
        CHANNEL_LABEL.put("official", "公众号");
        CHANNEL_LABEL.put("other", "其他");
    }

    @Autowired(required=false) private com.ruoyi.kangaroo.ext.user.KgMemberWallet memberWallet;
    @Autowired
    private KgJdbc db;
    @Autowired private KgCommunityFeature communityFeature;

    @Autowired
    private com.ruoyi.kangaroo.security.WxMiniAppClient wxMiniApp;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgPayService pay;

    @Lazy
    @Autowired
    private KgChatService chats;

    @Lazy
    @Autowired
    private KgMerchantRequestService requests;

    @Autowired
    private KgCityService cities;

    /**
     * 自己的代理：tick() 是定时线程直接调用的，里面调本类 closeExpired 如果写成 this.closeExpired()，
     * 那个方法上的 @Transactional 不会生效（没有经过代理）—— 加锁读 for update 立刻释放、
     * 「关单 + 名额减一 + 候补递补」不在一个事务里，中途出错会留下脏数据。
     */
    @Lazy
    @Autowired
    private KgActivityService self;

    // ---------------------------------------------------------------- 小工具

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

    /** 活动的费用方式（给页面判断用）：aa AA制 / free 免费 / paid 收费（在线支付或到场支付看 feeMode） */
    public static String feeKind(Map<String, Object> a)
    {
        if ("aa".equals(str(a.get("feeMode"))))
        {
            return "aa";
        }
        return num(a.get("feeFen")) > 0 ? "paid" : "free";
    }

    /**
     * 报名要不要先在线付款：只有「小院活动 + 在线支付 + 金额 > 0」。免费、AA制（fee_mode = aa）、到场支付（商家 / 用户自发）一律不建支付单。
     * 报名和候补转正共用这一条，别处不要再自己拼判断
     */
    static boolean collectsOnline(Map<String, Object> a, long fee)
    {
        return fee > 0 && "online".equals(str(a.get("feeMode"))) && "yard".equals(str(a.get("source")));
    }

    /** 卡片、详情上的价格：免费 /「¥88」/ AA制（填了预估人均写「约¥80 AA」） */
    public static String priceText(Map<String, Object> a)
    {
        String kind = feeKind(a);
        if ("aa".equals(kind))
        {
            long aa = num(a.get("aaFen"));
            return aa > 0 ? "约¥" + yuan(aa) + " AA" : "AA制";
        }
        return "free".equals(kind) ? "免费" : "¥" + yuan(num(a.get("feeFen")));
    }

    /** 怎么付：免费 / 在线支付（只有小院活动）/ 到场支付（商家活动）/ 到场付给发起人（用户自发）/ 现场AA。平台只代收「在线支付」这一种 */
    public static String feeModeLabel(Map<String, Object> a)
    {
        String kind = feeKind(a);
        if (!"paid".equals(kind))
        {
            return "aa".equals(kind) ? "现场AA" : "免费";
        }
        if ("online".equals(str(a.get("feeMode"))))
        {
            return "在线支付";
        }
        return "user".equals(str(a.get("source"))) ? "到场付给发起人" : "到场支付";
    }

    /** 元 → 分（最多两位小数）；空 = null。原先 Double.parseDouble(...) * 100 会把 12.345 静默四舍五入、把 NaN 变成 0，非数字直接 500 */
    static Long fen(Object yuan, String label)
    {
        String s = str(yuan).trim();
        if (s.isEmpty())
        {
            return null;
        }
        long f;
        try
        {
            f = new java.math.BigDecimal(s).movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
        }
        catch (NumberFormatException | ArithmeticException e)
        {
            throw new ServiceException(label + "格式不对，请填写金额（最多两位小数）", 422);
        }
        if (f < 0 || f > 10000000)
        {
            throw new ServiceException(label + "不合法", 422);
        }
        return f;
    }

    /**
     * 费用三选一（免费 / 收费 / AA制），员工端、用户自发共用。stored = 修改前的活动（新建为 null），source = 活动来源。
     * 收费：金额必须大于 0；收费方式沿用活动原来的在线 / 到场，原来不收费的按来源定（小院 = 在线支付，其余 = 到场支付，平台不代收）。
     * 免费、AA制：fee_fen 记 0，报名不建支付单；AA制的预估人均（aaYuan，选填，≥ 0）记 aa_fen，没带这个键时保留原值。
     * 老版本小程序不带 feeKind：按金额定（0 = 免费、> 0 = 收费），原来是 AA制、金额还是 0 的保持 AA制（「换封面」那种整条保存不能把 AA 变成免费）
     */
    void applyFee(Map<String, Object> b, Map<String, Object> cols, Map<String, Object> stored, String source)
    {
        Long yuanFen = fen(b.containsKey("feeYuan") ? b.get("feeYuan") : b.get("fee") == null ? null : String.valueOf(b.get("fee")), "费用");
        long fee = yuanFen == null ? 0L : yuanFen;
        String kind = str(b.get("feeKind")).trim();
        if (!kind.isEmpty() && !FEE_KINDS.contains(kind))
        {
            throw new ServiceException("费用方式只能选免费、收费或 AA制", 422);
        }
        String keptMode = stored == null ? "" : str(stored.get("feeMode"));
        if (kind.isEmpty())
        {
            kind = fee > 0 ? "paid" : ("aa".equals(keptMode) ? "aa" : "free");
        }
        Long aa = null;
        if ("paid".equals(kind))
        {
            if (fee <= 0)
            {
                throw new ServiceException("收费活动请填写大于 0 的费用（元/人）；不收费请选「免费」或「AA制」", 422);
            }
        }
        else
        {
            fee = 0L;
            if ("aa".equals(kind))
            {
                aa = b.containsKey("aaYuan") ? fen(b.get("aaYuan"), "预估人均")
                        : (stored != null && "aa".equals(keptMode) ? KgJdbc.asLong(stored.get("aaFen")) : null);
            }
        }
        cols.put("fee_fen", fee);
        cols.put("fee_mode", "free".equals(kind) ? "free" : ("aa".equals(kind) ? "aa"
                : (Arrays.asList("online", "offline").contains(keptMode) ? keptMode : paidMode(source))));
        cols.put("aa_fen", aa);
    }

    /** 新建只能选那四个类型（别的记成「其他」）；修改时原来的老类型（手作课程 / 讲座沙龙）原样保留 */
    static void pickCategory(Map<String, Object> cols, Object storedCategory)
    {
        String cat = str(cols.get("category"));
        if (!PICKABLE.contains(cat) && !cat.equals(str(storedCategory)))
        {
            cols.put("category", "其他");
        }
    }

    /**
     * 用户选中的区县（思明区）→ 业务城市（厦门）：活动的 city 抄自门店，存的是城市名，按区县查会一条都查不到、订阅也永远匹配不上。
     * 判空只为手工 new 出来、没注入城市服务的测试；不认识的名字 businessCity 原样返回。
     */
    private String bizCity(String city)
    {
        return cities == null || city == null ? city : cities.businessCity(city);
    }

    /** 两个时间是否同一刻（入参可能是 Date / Timestamp / 字符串；都为空也算相同） */
    static boolean sameTime(Object x, Object y)
    {
        Date a = x == null ? null : dt(x);
        Date b = y == null ? null : dt(y);
        return a == null ? b == null : (b != null && a.getTime() / 60000 == b.getTime() / 60000);
    }

    static Date dt(Object v)
    {
        return StaffSupport.parse(v);
    }

    static String week(Date d)
    {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        return WEEK[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** 9月7日（周日）05:30–10:00；跨天时写全两端 */
    public static String rangeText(Object start, Object end)
    {
        Date s = dt(start);
        Date e = dt(end);
        if (s == null)
        {
            return "";
        }
        String head = StaffSupport.fmt(s, "M月d日") + "（" + week(s) + "）" + StaffSupport.fmt(s, "HH:mm");
        if (e == null)
        {
            return head;
        }
        return StaffSupport.fmt(s, "yyyyMMdd").equals(StaffSupport.fmt(e, "yyyyMMdd")) ? head + "–" + StaffSupport.fmt(e, "HH:mm")
                : head + " – " + StaffSupport.fmt(e, "M月d日 HH:mm");
    }

    static String dayTitle(Date d)
    {
        LocalDate day = LocalDate.parse(StaffSupport.fmt(d, "yyyy-MM-dd"));
        String base = StaffSupport.fmt(d, "M月d日") + " " + week(d);
        if (day.equals(LocalDate.now()))
        {
            return "今天  " + base;
        }
        if (day.equals(LocalDate.now().plusDays(1)))
        {
            return "明天  " + base;
        }
        return base;
    }

    static String yuan(long fen)
    {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.2f", fen / 100.0);
    }

    static String maskPhone(String phone)
    {
        return phone == null || phone.length() < 7 ? "" : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String sixDigits()
    {
        return String.valueOf(100000 + RND.nextInt(900000));
    }

    /**
     * 同一场活动内不重复的签到码。签到按「活动 + 码」查人，撞码会把人签错。
     * 调用方已对活动行加锁（报名在同一场活动上串行）；这里用加锁读，看到的是别人刚提交的报名，
     * 而不是本事务开头那份快照（可重复读隔离级别下普通 select 读快照，会漏掉并发刚写入的码）。
     */
    private String uniqueCheckinCode(Long activityId)
    {
        for (int i = 0; i < 20; i++)
        {
            String code = sixDigits();
            if (db.count("select count(*) from kg_activity_signup where activity_id = :a and checkin_code = :c for share", p("a", activityId, "c", code)) == 0)
            {
                return code;
            }
        }
        throw new ServiceException("签到码生成失败，请重试");
    }

    /** 展示态：报名中但已过截止 / 已开始 → 报名截止；开始到结束之间 → 进行中 */
    static String phase(Map<String, Object> a)
    {
        String st = str(a.get("status"));
        Date now = new Date();
        Date s = dt(a.get("startTime"));
        Date e = dt(a.get("endTime"));
        if (Arrays.asList("open", "paused", "closed").contains(st) && s != null && !now.before(s) && (e == null || now.before(e)))
        {
            return "ongoing";
        }
        Date dl = dt(a.get("deadline"));
        if ("open".equals(st) && ((dl != null && now.after(dl)) || (s != null && now.after(s))))
        {
            return "closed";
        }
        return st;
    }

    static String phaseLabel(String phase)
    {
        return "ongoing".equals(phase) ? "进行中" : STATUS_LABEL.getOrDefault(phase, phase);
    }

    private Map<String, Object> mustActivity(Long id)
    {
        Map<String, Object> a = db.one("select * from kg_activity where activity_id = :id and del_flag = '0'", p("id", id));
        if (a == null)
        {
            throw new ServiceException("活动不存在", 404);
        }
        return a;
    }

    public List<String> avatars(Long activityId, int n)
    {
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> r : db.list("select u.avatar from kg_activity_signup g join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.activity_id = :a and g.status = 'confirmed' and u.avatar is not null and u.avatar <> '' order by g.signup_id limit " + n, p("a", activityId)))
        {
            out.add(str(r.get("avatar")));
        }
        return out;
    }

    private void stat(Long activityId, String channel, int views, int signups)
    {
        String ch = CHANNELS.contains(channel) ? channel : "other";
        db.update("insert into kg_activity_stat (activity_id, stat_day, channel, views, signups) values (:a, curdate(), :c, :v, :s) "
                + "on duplicate key update views = views + :v, signups = signups + :s", p("a", activityId, "c", ch, "v", views, "s", signups));
    }

    private Map<String, Object> card(Map<String, Object> a)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Long id = KgJdbc.asLong(a.get("activityId"));
        String src = str(a.get("source"));
        Date s = dt(a.get("startTime"));
        Date e = dt(a.get("endTime"));
        long cap = num(a.get("capacity"));
        long signed = num(a.get("signed"));
        m.put("activityId", id);
        m.put("productKind", a.get("productKind"));
        m.put("title", a.get("title"));
        m.put("img", a.get("cover"));
        m.put("kind", SOURCE_LABEL.get(src));
        m.put("tone", SOURCE_TONE.get(src));
        m.put("source", src);
        m.put("city", a.get("city"));
        m.put("place", a.get("place"));
        m.put("time", s == null ? "" : StaffSupport.fmt(s, "HH:mm") + (e == null ? "" : "–" + StaffSupport.fmt(e, "HH:mm")));
        m.put("timeText", rangeText(a.get("startTime"), a.get("endTime")));
        m.put("dateText", s == null ? "" : StaffSupport.fmt(s, "M月d日") + "（" + week(s) + "）");
        m.put("count", signed);
        m.put("capacity", cap);
        m.put("few", cap - signed <= 3);
        m.put("full", signed >= cap);
        m.put("feeFen", num(a.get("feeFen")));
        m.put("memberFree", "1".equals(a.get("memberFree")));
        m.put("feeMode", a.get("feeMode"));
        // 费用三选一（2026-11-03）：feeKind = free / paid / aa；AA制的预估人均 aaFen（没填为 null）；priceText、feeModeLabel 页面直接显示
        m.put("feeKind", feeKind(a));
        m.put("aaFen", a.get("aaFen"));
        m.put("priceText", priceText(a));
        m.put("feeModeLabel", feeModeLabel(a));
        m.put("top", "1".equals(str(a.get("pinned"))));
        String ph = phase(a);
        m.put("phase", ph);
        m.put("phaseLabel", phaseLabel(ph));
        m.put("avatars", avatars(id, 5));
        // 报名截止（10-16 反馈：收藏活动就是为了回头看截止时间，卡片上也要有）；没设截止 = 开始前都可报名
        m.put("deadlineText", a.get("deadline") == null ? "开始前" : StaffSupport.fmt(dt(a.get("deadline")), "M月d日 HH:mm"));
        // 集合点地图坐标（V49，GCJ-02，和微信地图同一坐标）：发布时在地图上选过点才有，没有就是 null（详情里点集合地点改成复制地址）
        m.put("meetLat", a.get("meetLat"));
        m.put("meetLng", a.get("meetLng"));
        return m;
    }

    /** 中国境内的大致范围（含南海诸岛）：地图选点只会落在国内，超出的多半是传错了或经纬度写反了 */
    static final java.math.BigDecimal LAT_MIN = new java.math.BigDecimal("3.5"), LAT_MAX = new java.math.BigDecimal("54");
    static final java.math.BigDecimal LNG_MIN = new java.math.BigDecimal("73"), LNG_MAX = new java.math.BigDecimal("135.5");

    private static java.math.BigDecimal coord(Object raw)
    {
        String s = str(raw).trim();
        if (s.isEmpty())
        {
            return null;
        }
        try
        {
            return new java.math.BigDecimal(s).setScale(6, java.math.RoundingMode.HALF_UP);
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("集合点的地图坐标格式不对，请重新在地图上选点", 422);
        }
    }

    /**
     * 集合点坐标（kg_activity.meet_lat / meet_lng）：请求里 meetLat、meetLng 两个键都没带 = 不改（老版本小程序、只换封面的整条保存都不带）；
     * 带了就要成对：两个都空 = 清掉地图位置，两个都有 = 必须在国内范围内。只有一个有值直接 422，不存半个点。
     */
    static void meetPoint(Map<String, Object> b, Map<String, Object> cols)
    {
        if (b == null || (!b.containsKey("meetLat") && !b.containsKey("meetLng")))
        {
            return;
        }
        java.math.BigDecimal lat = coord(b.get("meetLat"));
        java.math.BigDecimal lng = coord(b.get("meetLng"));
        if ((lat == null) != (lng == null))
        {
            throw new ServiceException("集合点的纬度和经度要同时提供，请重新在地图上选点", 422);
        }
        if (lat != null && (lat.compareTo(LAT_MIN) < 0 || lat.compareTo(LAT_MAX) > 0 || lng.compareTo(LNG_MIN) < 0 || lng.compareTo(LNG_MAX) > 0))
        {
            throw new ServiceException("集合点不在国内范围，请重新在地图上选点", 422);
        }
        cols.put("meet_lat", lat);
        cols.put("meet_lng", lng);
    }

    /**
     * 已有人报名时集合点能不能这样改：没带坐标（不改）、原来没标（补标）、清掉地图位置都可以；
     * 原来标过、现在换到另一个点 = 改集合地点，和改地点文字一样不行。
     */
    static boolean meetPointKept(Map<String, Object> cols, Map<String, Object> stored)
    {
        if (!cols.containsKey("meet_lat") || cols.get("meet_lat") == null)
        {
            return true;
        }
        java.math.BigDecimal oldLat = coord(stored.get("meetLat"));
        java.math.BigDecimal oldLng = coord(stored.get("meetLng"));
        if (oldLat == null || oldLng == null)
        {
            return true;
        }
        return oldLat.compareTo((java.math.BigDecimal) cols.get("meet_lat")) == 0 && oldLng.compareTo((java.math.BigDecimal) cols.get("meet_lng")) == 0;
    }

    private static final String LISTABLE = " a.del_flag = '0' and a.status in ('open','paused','closed') and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) > now() and" + KgCommunityFeature.USER_ACTIVITY_VISIBLE_SQL;

    // ---------------------------------------------------------------- 用户端：热门 / 列表 / 推荐

    public List<Map<String, Object>> hots(String city)
    {
        city = bizCity(city);
        Map<String, Object> ps = p();
        String c = "";
        if (city != null && !city.isEmpty() && !"全部城市".equals(city))
        {
            c = " and a.city = :c";
            ps.put("c", city);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.* from kg_activity a where a.status = 'open' and" + LISTABLE + c
                + " and a.start_time > now() and a.start_time < date_add(now(), interval 14 day) order by a.pinned desc, a.signed desc, a.start_time limit 6", ps))
        {
            out.add(card(a));
        }
        return out;
    }

    /**
     * 首页「近期活动」不足两场时的补位：口径和 hots 一样（报名中、可展示、还没开始），只是不限「14 天内」；
     * 置顶的在前，其余按开始时间。city 为空或「全部城市」时不限城市。社区页仍用 hots，不受影响。
     */
    public List<Map<String, Object>> upcoming(String city, int limit)
    {
        city = bizCity(city);
        Map<String, Object> ps = p();
        String c = "";
        if (city != null && !city.isEmpty() && !"全部城市".equals(city))
        {
            c = " and a.city = :c";
            ps.put("c", city);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.* from kg_activity a where a.status = 'open' and" + LISTABLE + c
                + " and a.start_time > now() order by a.pinned desc, a.start_time, a.activity_id limit " + Math.max(1, Math.min(limit, 20)), ps))
        {
            out.add(card(a));
        }
        return out;
    }

    public List<Map<String,Object>> homePicks(String city) {
        List<Map<String,Object>> out=new ArrayList<>();
        String filter=com.ruoyi.kangaroo.ext.user.KgHomeService.pickWhere();
        for(Map<String,Object> a:db.list("select a.* from kg_activity a join (select target_id,min(sort) pos from kg_home_pick h where h.section='activity' and "+filter+" group by target_id) picks on picks.target_id=a.activity_id where"+KgCommunityFeature.USER_ACTIVITY_VISIBLE_SQL+" and a.del_flag='0' and a.status='open' and a.start_time>now() and a.start_time<date_add(now(),interval 14 day)"+(city.isEmpty()?"":" and a.city=:c")+" order by picks.pos,a.activity_id limit 3",p("c",city))) out.add(card(a));
        return out;
    }

    /**
     * 「全职旅行」列表顶上的培训活动横幅（2026-10-18 主人：放自己的「培训活动」，点进去看详情、报名）。
     * 后台「首页内容管理」里栏目选「全职培训横幅」（kg_home_pick.section = 'training'，不用加表），推荐内容是一场开放报名、还没开始的活动；
     * 展示城市留空 = 全国都显示，填了城市 = 用户在列表里选了这个城市才显示，选了的城市优先于全国的；同一档按显示顺序。
     * 没有符合的活动返回 null，页面就不出横幅（不拿别的活动凑）。cities 是列表里已选的城市（已归一成业务城市名，最多 5 个）
     */
    public Map<String,Object> trainingBanner(List<String> cities) {
        List<String> cs=new ArrayList<>();
        if(cities!=null) for(String c:cities) { String t=c==null?"":c.trim(); if(!t.isEmpty() && !cs.contains(t) && cs.size()<5) cs.add(t); }
        Map<String,Object> ps=p();
        StringBuilder in=new StringBuilder();
        for(int i=0;i<cs.size();i++) { in.append(i==0?"":",").append(":c").append(i); ps.put("c"+i,cs.get(i)); }
        String window="h.del_flag='0' and h.status='0' and (h.start_time is null or h.start_time<=now()) and (h.end_time is null or h.end_time>now())";
        String cityCond=cs.isEmpty()?"h.city=''":"(h.city='' or h.city in ("+in+"))";
        List<Map<String,Object>> rows=db.list("select a.* from kg_home_pick h join kg_activity a on a.activity_id=h.target_id where h.section='training' and "+window+" and "+cityCond
                +" and "+KgCommunityFeature.USER_ACTIVITY_VISIBLE_SQL+" and a.del_flag='0' and a.status='open' and a.start_time>now() order by case when h.city='' then 1 else 0 end, h.sort, h.pick_id limit 1",ps);
        if(rows==null || rows.isEmpty()) return null;
        return card(rows.get(0));
    }

    public Map<String, Object> list(Long me, String source, String city, String category)
    {
        // 筛选与下面的「已订阅」判断都按业务城市：subscribe() 存的也是归一后的城市名；
        // 归一之前存下的区县名订阅（raw）同样算已订阅，否则用户看到"未订阅"再点一次会多出一行
        String raw = city;
        city = bizCity(city);
        Map<String, Object> ps = p();
        StringBuilder where = new StringBuilder(" where" + LISTABLE);
        if (SOURCE_LABEL.containsKey(source))
        {
            where.append(" and a.source = :src");
            ps.put("src", source);
        }
        if (city != null && !city.isEmpty() && !"全部城市".equals(city))
        {
            where.append(" and a.city = :c");
            ps.put("c", city);
        }
        if (CATEGORIES.contains(category))
        {
            where.append(" and a.category = :cat");
            ps.put("cat", category);
        }
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        Map<String, Object> cur = null;
        List<Map<String, Object>> rows = db.list("select a.* from kg_activity a" + where + " order by a.start_time, a.activity_id limit 100", ps);
        // 卡片上的收藏心：登录了才查，这一页的活动一次查完（不逐张查）
        java.util.Set<Long> faved = favoritedIn(me, rows);
        for (Map<String, Object> a : rows)
        {
            String title = dayTitle(dt(a.get("startTime")));
            if (cur == null || !title.equals(cur.get("date")))
            {
                cur = new LinkedHashMap<String, Object>();
                cur.put("date", title);
                cur.put("items", new ArrayList<Map<String, Object>>());
                groups.add(cur);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) cur.get("items");
            Map<String, Object> m = card(a);
            m.put("favorited", faved.contains(KgJdbc.asLong(a.get("activityId"))));
            items.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("groups", groups);
        res.put("subscribed", me != null && city != null && db.count("select count(*) from kg_activity_subscribe where app_user_id = :u and (city = :c or city = :raw)",
                p("u", me, "c", city, "raw", raw)) > 0);
        return res;
    }

    public Map<String, Object> travel(String city, int page) {
        String selectedCity = bizCity(city);
        boolean filterCity = selectedCity != null && !selectedCity.isEmpty() && !"全国".equals(selectedCity);
        Map<String, Object> result = db.page("select a.* from kg_activity a where" + LISTABLE
                + " and a.product_kind='travel' and a.source in ('yard','merchant')"
                + (filterCity ? " and a.city=:city" : "") + " order by a.start_time,a.activity_id", p("city", selectedCity), page, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<String, Object> row : rows) cards.add(card(row));
        result.put("rows", cards);
        return result;
    }

    /**
     * 为你推荐（规则推荐，不是 AI）：同类目收藏 / 报名过的优先 → 与已有行程时间冲突的标出来 → 同城热门补齐。
     */
    public Map<String, Object> recommend(Long me, String city)
    {
        city = bizCity(city);
        List<String> likedCats = new ArrayList<String>();
        List<Date[]> busy = new ArrayList<Date[]>();
        if (me != null)
        {
            for (Map<String, Object> r : db.list("select distinct a.category from kg_activity a where a.activity_id in ("
                    + "select target_id from kg_favorite where target_type = 'activity' and app_user_id = :u union "
                    + "select activity_id from kg_activity_signup where app_user_id = :u) and a.category is not null", p("u", me)))
            {
                likedCats.add(str(r.get("category")));
            }
            for (Map<String, Object> r : db.list("select a.start_time s, coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) e from kg_activity_signup g "
                    + "join kg_activity a on a.activity_id = g.activity_id where g.app_user_id = :u and g.status in ('confirmed','pending_payment','waitlist') "
                    + "and a.start_time > now()", p("u", me)))
            {
                busy.add(new Date[] { dt(r.get("s")), dt(r.get("e")) });
            }
        }
        Map<String, Object> ps = p();
        String c = "";
        if (city != null && !city.isEmpty() && !"全部城市".equals(city))
        {
            c = " and a.city = :c";
            ps.put("c", city);
        }
        List<Map<String, Object>> all = db.list("select a.* from kg_activity a where a.status = 'open' and" + LISTABLE + c
                + " and a.start_time > now() order by a.start_time limit 60", ps);
        List<Map<String, Object>> rec = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> more = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : all)
        {
            if (me != null && db.count("select count(*) from kg_activity_signup where activity_id = :a and app_user_id = :u and status in ('confirmed','pending_payment','waitlist')",
                    p("a", a.get("activityId"), "u", me)) > 0)
            {
                continue;
            }
            Map<String, Object> m = card(a);
            Date s = dt(a.get("startTime"));
            Date e = a.get("endTime") == null ? new Date(s.getTime() + 4 * 3600_000L) : dt(a.get("endTime"));
            boolean conflict = false;
            for (Date[] b : busy)
            {
                conflict = conflict || (s.before(b[1]) && e.after(b[0]));
            }
            m.put("distance", str(a.get("city")));
            if (conflict)
            {
                m.put("reason", "可能冲突");
                m.put("reasonTone", "orange");
                m.put("desc", "和你已报名的活动时间重叠");
            }
            else if (likedCats.contains(str(a.get("category"))))
            {
                m.put("reason", "最匹配");
                m.put("reasonTone", "green");
                m.put("desc", "你收藏或报名过「" + a.get("category") + "」类活动");
            }
            else if (num(a.get("signed")) >= Math.max(3, num(a.get("capacity")) / 2))
            {
                m.put("reason", "同城热门");
                m.put("reasonTone", "blue");
                m.put("desc", "已有 " + a.get("signed") + " 人报名");
            }
            else
            {
                more.add(m);
                continue;
            }
            if (rec.size() < 4)
            {
                rec.add(m);
            }
            else
            {
                more.add(m);
            }
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("recommended", rec);
        res.put("more", more);
        return res;
    }

    /**
     * 住宿预订页的「精选活动」：同城、入住期间开始、报名中的免费活动。只给免费的——住宿订单不代收活动费用，
     * 付费活动请在活动页单独报名；勾选的免费活动在住宿支付成功后由前端逐个报名。
     */
    public List<Map<String, Object>> forStay(String city, String checkIn, String checkOut)
    {
        LocalDate f;
        LocalDate t;
        try
        {
            f = LocalDate.parse(checkIn);
            t = LocalDate.parse(checkOut);
        }
        catch (Exception e)
        {
            f = LocalDate.now();
            t = f.plusDays(7);
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.* from kg_activity a where a.city = :c and a.status = 'open' and a.del_flag = '0' and a.fee_fen = 0 and a.fee_mode <> 'aa' "
                + "and " + KgCommunityFeature.USER_ACTIVITY_VISIBLE_SQL + " and a.start_time >= :f and a.start_time < :t and a.start_time > now() and (a.deadline is null or a.deadline > now()) order by a.start_time limit 10",
                p("c", city, "f", f.toString(), "t", t.plusDays(1).toString())))
        {
            Date s = dt(a.get("startTime"));
            Date e = dt(a.get("endTime"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("activityId", a.get("activityId"));
            m.put("name", a.get("title"));
            m.put("image", a.get("cover"));
            m.put("safe", "yard".equals(str(a.get("source"))));
            m.put("date", StaffSupport.fmt(s, "M月d日") + " " + week(s));
            m.put("timeRange", StaffSupport.fmt(s, "HH:mm") + (e == null ? "" : "–" + StaffSupport.fmt(e, "HH:mm")));
            m.put("limit", num(a.get("capacity")));
            m.put("booked", num(a.get("signed")));
            m.put("priceFen", 0);
            m.put("note", SOURCE_LABEL.get(str(a.get("source"))) + " · 免费 · 住宿支付成功后自动为你报名");
            out.add(m);
        }
        return out;
    }

    /**
     * 房源详情里的「小院活动」（2026-11-03 主人：二销产品的价格、可以发限时活动、还能看往期，不需要关注门店）：
     * 按活动的主办门店（kg_activity.store_id，小院 / 商家活动发布时就带）和房源所属门店对上，游客也能看。
     * current = 还在展示期的（报名中 / 暂停 / 报名截止且还没结束），置顶在前、再按开始时间；
     * past = 已结束的（状态 ended，或时间已过的），最近结束的在前。草稿、待审核、驳回、取消的都不出现。各最多 20 场。
     * 每张卡在 card() 之外多给 deadlineAt（报名截止的毫秒时间戳，没设截止时为 null，前端算倒计时用）。
     */
    public Map<String, Object> forStore(Long storeId)
    {
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> current = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> past = new ArrayList<Map<String, Object>>();
        res.put("current", current);
        res.put("past", past);
        if (storeId == null || storeId <= 0)
        {
            return res;
        }
        String finish = "coalesce(a.end_time, date_add(a.start_time, interval 4 hour))";
        for (Map<String, Object> a : db.list("select a.* from kg_activity a where a.store_id = :s and" + LISTABLE
                + " order by a.pinned desc, a.start_time, a.activity_id limit 20", p("s", storeId)))
        {
            current.add(storeCard(a));
        }
        for (Map<String, Object> a : db.list("select a.* from kg_activity a where a.store_id = :s and " + KgCommunityFeature.USER_ACTIVITY_VISIBLE_SQL + " and a.del_flag = '0' and (a.status = 'ended' "
                + "or (a.status in ('open','paused','closed') and " + finish + " <= now())) order by " + finish + " desc, a.activity_id desc limit 20", p("s", storeId)))
        {
            Map<String, Object> m = storeCard(a);
            m.put("phase", "ended");
            m.put("phaseLabel", STATUS_LABEL.get("ended"));
            past.add(m);
        }
        return res;
    }

    private Map<String, Object> storeCard(Map<String, Object> a)
    {
        Map<String, Object> m = card(a);
        Date dl = dt(a.get("deadline"));
        m.put("deadlineAt", dl == null ? null : dl.getTime());
        m.put("slogan", a.get("slogan"));
        return m;
    }

    /** Public organizer history. Pending/rejected/deleted activities never leave this query. */
    public Map<String, Object> profileActivities(Long uid, Integer page)
    {
        if (!communityFeature.enabled()) return p("rows", Collections.emptyList(), "total", 0, "hasMore", false);
        int size = 20;
        int offset = (Math.min(Math.max(page == null ? 1 : page, 1), 100000) - 1) * size;
        String where = " where a.app_user_id = :u and a.source = 'user' and a.del_flag = '0' and a.status in ('open','paused','closed','ended')";
        Map<String, Object> params = p("u", uid, "size", size + 1, "off", offset);
        List<Map<String, Object>> found = db.list("select a.* from kg_activity a" + where + " order by a.activity_id desc limit :size offset :off", params);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> a : found.subList(0, Math.min(size, found.size()))) rows.add(card(a));
        return p("rows", rows, "total", db.count("select count(*) from kg_activity a" + where, p("u", uid)), "hasMore", found.size() > size);
    }

    // ---------------------------------------------------------------- 详情

    public Map<String, Object> detail(Long me, Long id, String channel, String checkinToken)
    {
        Map<String, Object> a = mustActivity(id);
        boolean owner = me != null && me.equals(KgJdbc.asLong(a.get("appUserId")));
        boolean privateUserActivity = "user".equals(str(a.get("source"))) && !communityFeature.enabled();
        if (privateUserActivity && !owner && (me == null || db.count("select count(*) from kg_activity_signup where activity_id=:a and app_user_id=:u", p("a", id, "u", me)) == 0))
            throw new ServiceException(KgCommunityFeature.MESSAGE, 423);
        if (!Arrays.asList("open", "paused", "closed", "ended", "cancelled").contains(str(a.get("status"))) && !owner)
        {
            throw new ServiceException("活动不存在或未通过审核", 404);
        }
        db.update("update kg_activity set views = views + 1 where activity_id = :id", p("id", id));
        stat(id, channel, 1, 0);
        Map<String, Object> m = card(a);
        m.put("publicSharingEnabled", !privateUserActivity);
        m.put("slogan", a.get("slogan"));
        m.put("category", a.get("category"));
        m.put("intensity", a.get("intensity"));
        m.put("intro", a.get("intro"));
        m.put("safetyNote", a.get("safetyNote"));
        m.put("feeIncludes", a.get("feeIncludes"));
        m.put("insured", "1".equals(str(a.get("insured"))));
        m.put("insuranceDesc", "1".equals(str(a.get("insured"))) ? a.get("insuranceDesc") : "本活动未由平台投保，户外活动请自行购买意外险");
        m.put("deadlineText", a.get("deadline") == null ? "开始前" : StaffSupport.fmt(dt(a.get("deadline")), "M月d日 HH:mm"));
        m.put("status", a.get("status"));
        m.put("isOwner", owner);
        Map<String, Object> host = new LinkedHashMap<String, Object>();
        host.put("name", a.get("organizerName"));
        Long storeId = KgJdbc.asLong(a.get("storeId"));
        if (storeId != null)
        {
            Map<String, Object> st = db.one("select store_name, cover logo from kg_store where store_id = :s", p("s", storeId));
            host.put("logo", st == null ? "" : st.get("logo"));
            host.put("storeId", st == null ? null : storeId);
            host.put("chips", Arrays.asList("yard".equals(str(a.get("source"))) ? "平台直营" : "入驻商家"));
            host.put("count", db.count("select count(*) from kg_activity where store_id = :s and status in ('open','closed','ended') and del_flag = '0'", p("s", storeId)));
        }
        else
        {
            Map<String, Object> u = db.one("select avatar from kg_app_user where user_id = :u", p("u", a.get("appUserId")));
            host.put("logo", u == null ? "" : u.get("avatar"));
            host.put("userId", u == null ? null : a.get("appUserId"));
            host.put("chips", Arrays.asList("用户发起"));
            host.put("count", db.count("select count(*) from kg_activity where app_user_id = :u and source = 'user' and status in ('open','closed','ended') and del_flag = '0'",
                    p("u", a.get("appUserId"))));
        }
        m.put("host", host);
        m.put("favorited", me != null && db.count("select count(*) from kg_favorite where target_type = 'activity' and target_id = :id and app_user_id = :u",
                p("id", id, "u", me)) > 0);
        Map<String, Object> mine = me == null ? null : db.one("select signup_id, status, waitlist_no, pay_no from kg_activity_signup where activity_id = :a and app_user_id = :u",
                p("a", id, "u", me));
        if (mine != null && ACTIVE.contains(str(mine.get("status"))))
        {
            m.put("mySignupId", mine.get("signupId"));
            m.put("myStatus", mine.get("status"));
            m.put("myStatusLabel", SIGNUP_LABEL.get(str(mine.get("status"))));
        }
        m.put("checkinWindowText", checkinWindowText(a));
        if (checkinToken != null && !checkinToken.isEmpty() && me != null)
        {
            m.put("checkin", self.checkinByToken(me, id, checkinToken));
        }
        m.put("convId", a.get("convId"));
        return m;
    }

    // ---------------------------------------------------------------- 报名 / 支付 / 取消

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> signup(Long me, Long id, String channel) { return signup(me,id,channel,null); }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> signup(Long me, Long id, String channel, Long memberWalletId)
    {
        AccountState.requireActive(db, me);
        guard.requireConsent(me, "activity");
        Map<String, Object> a = db.one("select * from kg_activity where activity_id = :id and del_flag = '0' for update", p("id", id));
        if (a == null)
        {
            throw new ServiceException("活动不存在", 404);
        }
        if ("user".equals(str(a.get("source")))) communityFeature.requireEnabled();
        String st = str(a.get("status"));
        if (!"open".equals(st))
        {
            throw new ServiceException("当前不可报名（" + STATUS_LABEL.getOrDefault(st, st) + "）", 409);
        }
        Date now = new Date();
        if (!now.before(dt(a.get("startTime"))))
        {
            throw new ServiceException("活动已开始，不能再报名", 409);
        }
        if (a.get("deadline") != null && now.after(dt(a.get("deadline"))))
        {
            throw new ServiceException("报名已截止", 409);
        }
        Long organizer = KgJdbc.asLong(a.get("appUserId"));
        if (me.equals(organizer) && "user".equals(str(a.get("source"))))
        {
            throw new ServiceException("你是发起人，已在活动中", 409);
        }
        if ("user".equals(str(a.get("source"))))
        {
            guard.requireNotBlocked(me, organizer);
        }
        // 加锁读：本事务在锁活动行之前已有普通查询（规则同意等），快照已定；取消后再报名的双击会两边都看到"已取消"、各自 +1
        Map<String, Object> old = db.one("select * from kg_activity_signup where activity_id = :a and app_user_id = :u for update", p("a", id, "u", me));
        if (old != null && ACTIVE.contains(str(old.get("status"))))
        {
            throw new ServiceException("你已报名（" + SIGNUP_LABEL.get(str(old.get("status"))) + "）", 409);
        }
        Map<String, Object> u = guard.user(me);
        boolean full = num(a.get("signed")) >= num(a.get("capacity"));
        long fee = num(a.get("feeFen"));
        if(memberWallet != null)memberWallet.reconcileActivities(me);
        if(memberWalletId != null){
            if(memberWallet == null || !memberWallet.enabled())throw new ServiceException("会员权益暂未开放",409);
            if(full)throw new ServiceException("活动名额已满，免费权益暂不用于候补",409);
            memberWallet.activityVoucher(me,id,memberWalletId);fee=0;
        }
        boolean paid = collectsOnline(a, fee);
        String status = full ? "waitlist" : (paid ? "pending_payment" : "confirmed");
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("signup_no", StayCodes.orderNo("A"));
        row.put("activity_id", id);
        row.put("app_user_id", me);
        row.put("name", StaffSupport.cut(str(u.get("displayName")), 64));
        row.put("phone", u.get("phoneVerifiedTime") == null ? null : u.get("phone"));
        row.put("status", status);
        row.put("hidden_by_user", "0");
        // 候补号取「当前仍在候补的最大号 + 1」：kg_activity.waitlist 计数在递补 / 取消时会减少，拿它 +1 会发出重复的号（2026-09-26 回归发现）
        row.put("waitlist_no", full ? db.count("select coalesce(max(waitlist_no), 0) from kg_activity_signup where activity_id = :a and status = 'waitlist' for share",
                p("a", id)) + 1 : null);
        row.put("fee_fen", paid ? fee : 0);
        if(memberWallet != null)row.put("member_wallet_id",memberWalletId);
        row.put("pay_no", null);
        row.put("paid_time", null);
        row.put("expire_time", paid && !full ? new Date(now.getTime() + 15 * 60_000L) : null);
        row.put("checkin_code", uniqueCheckinCode(id));
        row.put("checkin_time", null);
        row.put("channel", CHANNELS.contains(channel) ? channel : "other");
        row.put("cancel_reason", null);
        row.put("cancel_time", null);
        row.put("refund_fen", 0);
        row.put("refund_note", null);
        row.put("refund_time", null);
        row.put("reminded_time", null);
        row.put("create_time", now);
        row.put("update_time", now);
        long sid;
        if (old != null)
        {
            sid = num(old.get("signupId"));
            // 复用旧行会把 pay_no 换成新单：旧单已付款没退完的，单号记进 refund_note，不和这条报名彻底脱钩
            row.put("refund_note", oldPayNote(old));
            db.updateRow("kg_activity_signup", "signup_id", sid, row);
        }
        else
        {
            sid = db.insert("kg_activity_signup", row);
        }
        if (full)
        {
            db.update("update kg_activity set waitlist = waitlist + 1 where activity_id = :id", p("id", id));
        }
        else if (db.update("update kg_activity set signed = signed + 1 where activity_id = :id and signed < capacity", p("id", id)) == 0)
        {
            throw new ServiceException("名额刚刚被抢完，请重试", 409);
        }
        if(memberWalletId != null)memberWallet.reserveActivity(me,memberWalletId);
        stat(id, channel, 0, 1);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        if (paid && !full)
        {
            Map<String, Object> po = pay.createForSignupCycle(sid,me,KgJdbc.asLong(a.get("storeId")),fee,str(a.get("title"))+" 报名费");
            db.update("update kg_activity_signup set pay_no = :n where signup_id = :s", p("n", po.get("payNo"), "s", sid));
            res.put("payNo", po.get("payNo"));
        }
        if ("confirmed".equals(status))
        {
            afterConfirmed(a, sid, me, str(u.get("displayName")));
        }
        res.put("signupId", sid);
        res.put("status", status);
        res.put("message", "waitlist".equals(status) ? "名额已满，已为你排上候补第 " + row.get("waitlist_no") + " 位"
                : ("pending_payment".equals(status) ? "名额已为你保留 15 分钟，请尽快支付" : "报名成功"));
        return res;
    }

    private void afterConfirmed(Map<String, Object> a, long signupId, Long uid, String name)
    {
        Long id = KgJdbc.asLong(a.get("activityId"));
        chats.joinActivityGroup(id, uid);
        String title = str(a.get("title"));
        support.notifyUser(uid, "system", "活动报名成功", title + "，" + rangeText(a.get("startTime"), a.get("endTime")) + "，集合：" + a.get("place") + "。",
                "activity", id, "/pages/community/activity?signupId=" + signupId);
        Long storeId = KgJdbc.asLong(a.get("storeId"));
        if (storeId != null)
        {
            support.notifyStore(storeId, "system", "活动有新报名", name + " 报名了「" + title + "」", "activity", id, "/pages/staff/activities?s=4&id=" + id);
        }
        else if (a.get("appUserId") != null && !uid.equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            support.notifyUser(KgJdbc.asLong(a.get("appUserId")), "system", "你发起的活动有新报名", name + " 报名了「" + title + "」", "activity", id,
                    "/pages/community/activity-mine?id=" + id);
        }
    }

    /**
     * 重新报名复用旧行时新的 refund_note：留着之前记下的「旧支付单…」，再记上这一轮已付款没退完的支付单（开始前 24 小时内取消不退的那种）。
     * 支付单本身的 biz_id 仍是这条报名，后台按报名 ID 也查得到；这里是让看报名的人直接看到。都没有返回 null
     */
    private String oldPayNote(Map<String, Object> old)
    {
        String prev = str(old.get("refundNote"));
        int at = prev.indexOf(OLD_PAY_TAG);
        String keep = at >= 0 ? prev.substring(at) : "";
        String payNo = str(old.get("payNo"));
        if (!payNo.isEmpty() && !keep.contains(payNo))
        {
            Map<String, Object> po = db.one("select amount, refund_amount, status from kg_pay_order where pay_no = :n and biz_type = :t and del_flag = '0'",
                    p("n", payNo, "t", BIZ));
            long left = po == null ? 0 : num(po.get("amount")) - num(po.get("refundAmount"));
            if (po != null && Arrays.asList("paid", "part_refunded").contains(str(po.get("status"))) && left > 0)
            {
                String seg = OLD_PAY_TAG + " " + payNo + "（¥" + yuan(left) + " 已付未退）";
                keep = keep.isEmpty() ? seg : seg + "；" + keep;
                log.warn("活动重新报名，上一轮支付单已付未退 signup={} pay={} left={}", old.get("signupNo"), payNo, left);
            }
        }
        return keep.isEmpty() ? null : StaffSupport.cut(keep, 255);
    }

    /** 候补递补：有空位就按候补顺序转正（在线支付的转为待支付，30 分钟内付款） */
    private void promote(Long activityId)
    {
        Map<String, Object> a = db.one("select * from kg_activity where activity_id = :id for update", p("id", activityId));
        if (a == null || !Arrays.asList("open", "paused", "closed").contains(str(a.get("status"))) || !new Date().before(dt(a.get("startTime"))))
        {
            return;
        }
        while (num(a.get("signed")) < num(a.get("capacity")))
        {
            // 加锁读：退款 / 取消 / 超时关单可能并发触发递补，普通读会让两边选中同一个候补人（2026-09-28 复核发现：凭空多出名额、同一报名两张支付单）
            Map<String, Object> w = db.one("select * from kg_activity_signup where activity_id = :a and status = 'waitlist' order by waitlist_no, signup_id limit 1 for update",
                    p("a", activityId));
            if (w == null)
            {
                return;
            }
            long fee = num(w.get("feeFen")) > 0 ? num(w.get("feeFen")) : num(a.get("feeFen"));
            boolean paid = collectsOnline(a, fee);
            Long uid = KgJdbc.asLong(w.get("appUserId"));
            long sid = num(w.get("signupId"));
            if (paid)
            {
                if (db.update("update kg_activity_signup set status = 'pending_payment', fee_fen = :f, expire_time = date_add(now(), interval 30 minute), "
                        + "update_time = now() where signup_id = :s and status = 'waitlist'", p("f", fee, "s", sid)) == 0)
                {
                    continue;   // 这个候补人已经被别处处理：不要建支付单、发通知、占名额，换下一位
                }
                Map<String, Object> po = pay.createForSignupCycle(sid,uid,KgJdbc.asLong(a.get("storeId")),fee,str(a.get("title"))+" 报名费");
                db.update("update kg_activity_signup set pay_no = :n where signup_id = :s", p("n", po.get("payNo"), "s", sid));
                support.notifyUser(uid, "system", "候补转正，请 30 分钟内支付", "「" + a.get("title") + "」有人退出，名额已为你保留 30 分钟。", "activity", activityId,
                        "/pages/community/activity?signupId=" + sid);
            }
            else
            {
                if (db.update("update kg_activity_signup set status = 'confirmed', update_time = now() where signup_id = :s and status = 'waitlist'", p("s", sid)) == 0)
                {
                    continue;
                }
                afterConfirmed(a, sid, uid, str(w.get("name")));
            }
            db.update("update kg_activity set signed = signed + 1, waitlist = greatest(waitlist - 1, 0) where activity_id = :id", p("id", activityId));
            a = db.one("select * from kg_activity where activity_id = :id for update", p("id", activityId));
        }
    }

    private Map<String, Object> mySignup(Long me, Long signupId, boolean lock)
    {
        if (lock) {
            mySignup(me,signupId,false); // Authorize before taking business locks.
            lockActivityForSignup(signupId);
        }
        Map<String, Object> s = db.one("select * from kg_activity_signup where signup_id = :s and app_user_id = :u" + (lock ? " for update" : ""),
                p("s", signupId, "u", me));
        if (s == null)
        {
            throw new ServiceException("报名记录不存在", 404);
        }
        return s;
    }

    /** 只从本人的列表移除终态报名，交易、退款、签到和通知记录全部保留。 */
    @Transactional(rollbackFor = Exception.class)
    public String hideSignup(Long me, Long signupId)
    {
        AccountState.requireActive(db, me);
        Map<String, Object> s = mySignup(me, signupId, true);
        if (!Arrays.asList("cancelled", "refunded", "closed").contains(str(s.get("status"))))
            throw new ServiceException("只能移除已取消或已退款的记录，处理中的报名请先完成处理", 409);
        db.update("update kg_activity_signup set hidden_by_user = '1', update_time = now() where signup_id = :s and app_user_id = :u",
                p("s", signupId, "u", me));
        return "已从个人列表移除";
    }

    /** 待处理状态始终可见：后续退款重试或重新报名不能被历史的隐藏标记遮住。 */
    public static final String VISIBLE_SIGNUP_SQL = "(g.hidden_by_user = '0' or g.status not in ('cancelled','refunded','closed'))";

    public Map<String, Object> ticket(Long me, Long signupId)
    {
        Map<String, Object> s = mySignup(me, signupId, false);
        Map<String, Object> a = mustActivity(KgJdbc.asLong(s.get("activityId")));
        Map<String, Object> m = card(a);
        String st = str(s.get("status"));
        m.put("signupId", signupId);
        m.put("signupNo", s.get("signupNo"));
        m.put("status", st);
        m.put("statusLabel", SIGNUP_LABEL.get(st));
        m.put("waitlistNo", s.get("waitlistNo"));
        m.put("name", s.get("name"));
        m.put("phoneMasked", maskPhone(str(s.get("phone"))));
        m.put("createTimeText", StaffSupport.fmt(dt(s.get("createTime")), "yyyy-MM-dd HH:mm:ss"));
        m.put("feeFen", num(s.get("feeFen")));
        m.put("payNo", "pending_payment".equals(st) ? s.get("payNo") : null);
        m.put("expireTime", s.get("expireTime"));
        m.put("checkinCode", "confirmed".equals(st) ? s.get("checkinCode") : null);
        m.put("checkedIn", s.get("checkinTime") != null);
        m.put("checkinWindowText", checkinWindowText(a));
        m.put("remind", s.get("remind"));
        m.put("remindLabel", "off".equals(str(s.get("remind"))) ? "已关闭集合提醒" : ("2h".equals(str(s.get("remind"))) ? "活动前 2 小时提醒我" : "活动前1天 20:00 提醒我"));
        // 凭证页的「报名成功」章、集合提醒只给还有效的报名（取消 / 退款中 / 已退款的不再显示）
        m.put("canRemind", REMINDABLE.contains(st));
        m.put("refundFen", num(s.get("refundFen")));
        m.put("refundNote", s.get("refundNote"));
        m.put("convId", "confirmed".equals(st) ? a.get("convId") : null);
        m.put("canCancel", Arrays.asList("pending_payment", "confirmed", "waitlist").contains(st) && new Date().before(dt(a.get("startTime"))) && s.get("checkinTime") == null);
        m.put("refundable", "confirmed".equals(st) && s.get("paidTime") != null && dt(a.get("startTime")).getTime() - System.currentTimeMillis() >= 24 * 3600_000L);
        return m;
    }

    @Transactional(rollbackFor = Exception.class)
    public String cancel(Long me, Long signupId, String reason)
    {
        AccountState.requireActive(db, me);
        Map<String, Object> s = mySignup(me, signupId, true);
        Long activityId = KgJdbc.asLong(s.get("activityId"));
        Map<String, Object> a = db.one("select * from kg_activity where activity_id = :id for update", p("id", activityId));
        String st = str(s.get("status"));
        // 已经签到（人到现场了）就不能再取消：之前签到后照样能取消、能申请退款
        if (s.get("checkinTime") != null)
        {
            throw new ServiceException("你已签到，不能取消报名", 409);
        }
        String rs = StaffSupport.cut(reason == null || reason.trim().isEmpty() ? "用户取消" : reason.trim(), 255);
        String msg;
        if ("pending_payment".equals(st))
        {
            if (s.get("payNo") != null && !pay.close(str(s.get("payNo"))))
            {
                throw new ServiceException("订单已支付，请刷新后再操作", 409);
            }
            db.update("update kg_activity_signup set status = 'cancelled', cancel_reason = :r, cancel_time = now(), update_time = now() "
                    + "where signup_id = :s and status = 'pending_payment'", p("r", rs, "s", signupId));
            db.update("update kg_activity set signed = greatest(signed - 1, 0) where activity_id = :id", p("id", activityId));
            msg = "已取消报名";
        }
        else if ("waitlist".equals(st))
        {
            db.update("update kg_activity_signup set status = 'cancelled', cancel_reason = :r, cancel_time = now(), update_time = now() "
                    + "where signup_id = :s and status = 'waitlist'", p("r", rs, "s", signupId));
            db.update("update kg_activity set waitlist = greatest(waitlist - 1, 0) where activity_id = :id", p("id", activityId));
            return "已取消候补";
        }
        else if ("confirmed".equals(st))
        {
            if (!new Date().before(dt(a.get("startTime"))))
            {
                throw new ServiceException("活动已开始，不能取消", 409);
            }
            boolean paidOnline = s.get("paidTime") != null && num(s.get("feeFen")) > 0;
            boolean refundable = paidOnline && dt(a.get("startTime")).getTime() - System.currentTimeMillis() >= 24 * 3600_000L;
            String to = refundable ? "refund_pending" : "cancelled";
            db.update("update kg_activity_signup set status = :to, refund_fen = :rf, cancel_reason = :r, cancel_time = now(), update_time = now() "
                    + "where signup_id = :s and status = 'confirmed'", p("to", to, "rf", refundable ? num(s.get("feeFen")) : 0, "r", rs, "s", signupId));
            db.update("update kg_activity set signed = greatest(signed - 1, 0) where activity_id = :id", p("id", activityId));
            chats.leaveActivityGroup(activityId, me);
            boolean storeTold = false;
            if (refundable)
            {
                storeTold = notifyRefundApprovers(a, str(s.get("name")), num(s.get("feeFen")), me);
                msg = "已取消，退款 ¥" + yuan(num(s.get("feeFen"))) + " 待审核，通过后原路退回";
            }
            else
            {
                msg = paidOnline ? "已取消（开始前 24 小时内取消不退款）" : "已取消报名";
            }
            // 门店已经收到「活动退款待审批」（里面写了谁取消）的不再重复发
            if (!storeTold)
            {
                notifyOrganizerCancelled(a, me, str(s.get("name")));
            }
        }
        else
        {
            throw new ServiceException("当前状态不能取消（" + SIGNUP_LABEL.getOrDefault(st, st) + "）", 409);
        }
        if(memberWallet != null)memberWallet.reconcileActivities(me);
        promote(activityId);
        return msg;
    }

    /**
     * 参与者取消了已确认的报名：和「有新报名」同一个收件人——门店活动发给门店，用户发起的发给发起人（之前取消了谁都不知道）。
     * 待支付 / 候补的取消不发：报名成功之前本来就没通知过他们
     */
    private void notifyOrganizerCancelled(Map<String, Object> a, Long uid, String name)
    {
        Long id = KgJdbc.asLong(a.get("activityId"));
        String text = name + " 取消了「" + a.get("title") + "」的报名";
        Long storeId = KgJdbc.asLong(a.get("storeId"));
        if (storeId != null)
        {
            support.notifyStore(storeId, "system", "活动有人取消报名", text, "activity", id, "/pages/staff/activities?s=4&id=" + id);
        }
        else if (a.get("appUserId") != null && !uid.equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            support.notifyUser(KgJdbc.asLong(a.get("appUserId")), "system", "你发起的活动有人取消报名", text, "activity", id,
                    "/pages/community/activity-mine?id=" + id);
        }
    }

    private void notifyRefundApprovers(Map<String, Object> a, String name, long fen)
    {
        Long storeId = KgJdbc.asLong(a.get("storeId"));
        if (storeId != null)
        {
            support.notifyStore(storeId, "order", "活动退款待审批", name + " 取消了「" + a.get("title") + "」，申请退款 ¥" + yuan(fen) + "。", "activity",
                    KgJdbc.asLong(a.get("activityId")), "/pages/staff/activities?s=4&id=" + a.get("activityId"));
        }
    }

    /**
     * 切换后退款只归这家店有授权的城市负责人；发给门店员工他们也做不了，用户的钱会卡住。切换前走上面那个（一个字不变）。
     * 他不能审自己的报名：名单去掉报名人本人，去掉后没人了就和没有城市负责人一样，写明走平台后台。
     * 退款列表里姓名是打码的（KgCityFinanceService.refunds），消息也不能露全名。
     * 返回门店有没有收到这条（收到了就不用再发「有人取消报名」）。
     */
    private boolean notifyRefundApprovers(Map<String, Object> a, String name, long fen, Long applicant)
    {
        if (!com.ruoyi.kangaroo.ext.staff.RolePhase.isNew())
        {
            notifyRefundApprovers(a, name, fen);
            return a.get("storeId") != null;
        }
        Long storeId = KgJdbc.asLong(a.get("storeId"));
        if (storeId == null)
        {
            return false;
        }
        String text = com.ruoyi.kangaroo.util.MaskUtils.name(name) + " 取消了「" + a.get("title") + "」，申请退款 ¥" + yuan(fen) + "。";
        List<Long> leads = support.cityLeadsOfStore(storeId);
        boolean ownSignup = leads != null && leads.contains(applicant);
        if (ownSignup)
        {
            leads = new java.util.ArrayList<Long>(leads);
            leads.remove(applicant);
        }
        if (leads != null && !leads.isEmpty())
        {
            for (Long lead : leads)
            {
                support.notifyStaff(lead, "order", "活动退款待审批", text, BIZ_REFUND_PENDING, KgJdbc.asLong(a.get("activityId")), "/pages/staff/city-finance?storeId=" + storeId);
            }
            return false;
        }
        text += ownSignup ? "这笔是这家店城市负责人自己的报名，退款请联系平台后台处理。" : "这家店没有城市负责人，退款请联系平台后台处理。";
        support.notifyStore(storeId, "order", "活动退款待审批", text, "activity",
                KgJdbc.asLong(a.get("activityId")), "/pages/staff/activities?s=4&id=" + a.get("activityId"));
        return true;
    }

    /**
     * 整场取消转出来的「退款待审批」：之前一条都不通知，审批人不知道有钱要退。收件人和用户自己取消同一套规则。
     * 只有一笔时照用户取消那条发；多笔时每人只收一条合并的（写笔数和合计），不刷屏。
     * refunds 每项：name 报名人、feeFen 金额、appUserId 报名人账号。
     */
    private void notifyCancelRefunds(Map<String, Object> a, List<Map<String, Object>> refunds)
    {
        if (refunds.isEmpty())
        {
            return;
        }
        if (refunds.size() == 1)
        {
            Map<String, Object> r = refunds.get(0);
            notifyRefundApprovers(a, str(r.get("name")), num(r.get("feeFen")), KgJdbc.asLong(r.get("appUserId")));
            return;
        }
        Long storeId = KgJdbc.asLong(a.get("storeId"));
        if (storeId == null)
        {
            return;
        }
        Long id = KgJdbc.asLong(a.get("activityId"));
        String head = "「" + a.get("title") + "」已整场取消，";
        String storeRoute = "/pages/staff/activities?s=4&id=" + id;
        if (!com.ruoyi.kangaroo.ext.staff.RolePhase.isNew())
        {
            support.notifyStore(storeId, "order", "活动退款待审批", head + refundSummary(refunds, null) + "待审批。", "activity", id, storeRoute);
            return;
        }
        List<Long> leads = support.cityLeadsOfStore(storeId);
        List<Long> approvers = leads == null ? new ArrayList<Long>() : leads;
        // 城市负责人不能审自己的报名：他收到的合计里去掉他自己那笔；一个能审的人都没有的那几笔，提醒门店走平台后台
        List<Map<String, Object>> orphan = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : refunds)
        {
            Long applicant = KgJdbc.asLong(r.get("appUserId"));
            boolean someone = false;
            for (Long lead : approvers)
            {
                someone = someone || (lead != null && !lead.equals(applicant));
            }
            if (!someone)
            {
                orphan.add(r);
            }
        }
        for (Long lead : approvers)
        {
            if (lead != null && !refundSummary(refunds, lead).isEmpty())
            {
                support.notifyStaff(lead, "order", "活动退款待审批", head + refundSummary(refunds, lead) + "待审批。", BIZ_REFUND_PENDING, id,
                        "/pages/staff/city-finance?storeId=" + storeId);
            }
        }
        if (!orphan.isEmpty())
        {
            support.notifyStore(storeId, "order", "活动退款待审批", head + refundSummary(orphan, null) + "待审批。"
                    + (approvers.isEmpty() ? "这家店没有城市负责人，退款请联系平台后台处理。" : "其中有城市负责人自己的报名，这几笔退款请联系平台后台处理。"),
                    "activity", id, storeRoute);
        }
    }

    /** 「N 笔报名费共 ¥X 」：except 不为空时去掉这个人自己的那笔；一笔都不剩返回空串 */
    private static String refundSummary(List<Map<String, Object>> refunds, Long except)
    {
        int n = 0;
        long fen = 0;
        for (Map<String, Object> r : refunds)
        {
            if (except != null && except.equals(KgJdbc.asLong(r.get("appUserId"))))
            {
                continue;
            }
            n++;
            fen += num(r.get("feeFen"));
        }
        return n == 0 ? "" : n + " 笔报名费共 ¥" + yuan(fen) + " ";
    }

    @Transactional(rollbackFor = Exception.class)
    public String setRemind(Long me, Long signupId, String remind)
    {
        AccountState.requireActive(db, me);
        Map<String, Object> s = mySignup(me, signupId, true);
        // 集合提醒只发给报名成功的人（见 tick）：已取消 / 退款审核中 / 已退款的报名之前照样能改提醒，还回一句「将在活动前…提醒你」，其实不会发
        if (!REMINDABLE.contains(str(s.get("status"))))
        {
            throw new ServiceException("这次报名" + SIGNUP_LABEL.getOrDefault(str(s.get("status")), "已失效") + "，不会再发集合提醒", 409);
        }
        String r = Arrays.asList("1d20", "2h", "off").contains(remind) ? remind : "1d20";
        db.update("update kg_activity_signup set remind = :r, reminded_time = null, update_time = now() where signup_id = :s", p("r", r, "s", signupId));
        return "off".equals(r) ? "已关闭集合提醒" : ("2h".equals(r) ? "将在活动前 2 小时提醒你" : "将在活动前一天 20:00 提醒你");
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> favorite(Long me, Long id)
    {
        AccountState.requireActive(db, me);
        mustActivity(id);
        // 同一个人连点 / 两个人同时收藏：先 delete 不存在的行会在唯一索引上各拿一把间隙锁，随后 insert 互相等待 → 死锁（500）。
        // 先锁自己的账号行串行，再先插后删（同 KgPostService.favorite）
        // The active account lock above also serializes repeated toggles.
        boolean on;
        if (db.update("insert ignore into kg_favorite (target_type, target_id, app_user_id, create_time) values ('activity', :a, :u, now())", p("a", id, "u", me)) > 0)
        {
            on = true;
        }
        else
        {
            db.update("delete from kg_favorite where target_type = 'activity' and target_id = :a and app_user_id = :u", p("a", id, "u", me));
            on = false;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("favorited", on);
        return m;
    }

    /** 这一批活动里我收藏了哪些（游客 / 空列表不查库） */
    java.util.Set<Long> favoritedIn(Long me, List<Map<String, Object>> rows)
    {
        java.util.Set<Long> out = new java.util.HashSet<Long>();
        if (me == null || rows == null || rows.isEmpty())
        {
            return out;
        }
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> a : rows)
        {
            Long id = KgJdbc.asLong(a.get("activityId"));
            if (id != null)
            {
                ids.add(id);
            }
        }
        if (ids.isEmpty())
        {
            return out;
        }
        for (Map<String, Object> r : db.list("select target_id from kg_favorite where target_type = 'activity' and app_user_id = :u and target_id in (:ids)",
                p("u", me, "ids", ids)))
        {
            Long id = KgJdbc.asLong(r.get("targetId"));
            if (id != null)
            {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 我收藏的活动（「我的活动 → 收藏」页签）：还能报名的在最前，其次是没结束但报不了的（截止 / 暂停 / 进行中），各自按报名截止（没设截止按开始时间）由近到远；
     * 已结束 / 已取消的放后面、标灰（ended = true）。待审核 / 驳回 / 草稿（别人看不到的）和已删除的不列。
     */
    public List<Map<String, Object>> favorites(Long me)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String over = "(a.status in ('ended','cancelled') or coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) <= now())";
        for (Map<String, Object> a : db.list("select a.* from kg_favorite f join kg_activity a on a.activity_id = f.target_id "
                + "where f.target_type = 'activity' and f.app_user_id = :u and a.del_flag = '0' and a.status in ('open','paused','closed','ended','cancelled') "
                + "and " + KgCommunityFeature.USER_ACTIVITY_VISIBLE_SQL + " order by " + over + ", (a.status <> 'open' or coalesce(a.deadline, a.start_time) <= now()), "
                + "case when " + over + " then null else coalesce(a.deadline, a.start_time) end, a.start_time desc, a.activity_id desc limit 100",
                p("u", me)))
        {
            Map<String, Object> m = card(a);
            String ph = str(m.get("phase"));
            Date e = a.get("endTime") == null ? null : dt(a.get("endTime"));
            Date s = dt(a.get("startTime"));
            Date until = e != null ? e : (s == null ? null : new Date(s.getTime() + 4 * 3600_000L));
            boolean ended = Arrays.asList("ended", "cancelled").contains(ph) || (until != null && !until.after(new Date()));
            m.put("favorited", true);
            m.put("ended", ended);
            m.put("state", ended ? ("cancelled".equals(ph) ? "已取消" : "已结束") : phaseLabel(ph));
            m.put("stateTone", ended ? "blue" : ("open".equals(ph) ? "green" : "ochre"));
            // 卡片下方一行：还能报名的写截止时间，其余写当前阶段
            m.put("deadlineNote", ended ? (("cancelled".equals(ph)) ? "活动已取消" : "活动已结束")
                    : ("open".equals(ph) ? ("开始前".equals(m.get("deadlineText")) ? "开始前都可报名" : "报名截止 " + m.get("deadlineText")) : phaseLabel(ph)));
            out.add(m);
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> subscribe(Long me, String city, boolean on)
    {
        AccountState.requireActive(db, me);
        if (city == null || city.trim().isEmpty() || city.length() > 32)
        {
            throw new ServiceException("请先选择城市", 422);
        }
        // 订阅按业务城市存（思明区 → 厦门）：活动上线时 onOpened 拿活动的城市（抄自门店，是城市名）来匹配，存区县名永远收不到提醒。
        // 取消时连同归一之前存下的区县名那一行一起删
        String raw = city.trim();
        city = bizCity(raw);
        if (on)
        {
            db.update("insert ignore into kg_activity_subscribe (app_user_id, city, create_time) values (:u, :c, now())", p("u", me, "c", city));
        }
        else
        {
            db.update("delete from kg_activity_subscribe where app_user_id = :u and (city = :c or city = :raw)", p("u", me, "c", city, "raw", raw));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("subscribed", on);
        m.put("message", on ? city + "有新活动上线时会通知你" : "已关闭新活动提醒");
        return m;
    }

    // ---------------------------------------------------------------- 我的活动

    public List<Map<String, Object>> mine(Long me, String tab)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if ("mine".equals(tab))
        {
            for (Map<String, Object> a : db.list("select a.* from kg_activity a where a.app_user_id = :u and a.source = 'user' and a.del_flag = '0' "
                    + "order by a.activity_id desc limit 50", p("u", me)))
            {
                Map<String, Object> m = card(a);
                String ph = phase(a);
                m.put("state", phaseLabel(ph));
                m.put("stateTone", Arrays.asList("open", "ongoing").contains(ph) ? "green" : ("pending".equals(ph) ? "ochre" : "blue"));
                m.put("attend", num(a.get("signed")) + "人已报名");
                m.put("manage", true);
                m.put("convId", a.get("convId"));
                m.put("auditReason", "rejected".equals(str(a.get("status"))) ? a.get("auditReason") : null);
                out.add(m);
            }
            return out;
        }
        boolean done = "done".equals(tab);
        // 「已结束」页签：活动结束后仍在退款审核中 / 候补中 / 待支付的报名也要留在这里（原先两个页签都不显示，用户找不到自己的退款进度）
        String cond = done ? "(g.status in ('confirmed','pending_payment','waitlist','refund_pending','refunding') and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) <= now()) "
                + "or g.status in ('refunded','cancelled','closed')"
                : "g.status in ('pending_payment','confirmed','waitlist','refund_pending','refunding') and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) > now()";
        for (Map<String, Object> r : db.list("select a.*, g.signup_id, g.status g_status, g.waitlist_no from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id "
                + "where g.app_user_id = :u and a.del_flag = '0' and " + VISIBLE_SIGNUP_SQL + " and (" + cond + ") order by a.start_time" + (done ? " desc" : "") + " limit 50", p("u", me)))
        {
            Map<String, Object> m = card(r);
            String gs = str(r.get("gStatus"));
            m.put("signupId", r.get("signupId"));
            m.put("signupStatus", gs);
            m.put("canHide", Arrays.asList("cancelled", "refunded", "closed").contains(gs));
            m.put("state", "waitlist".equals(gs) ? "候补第" + r.get("waitlistNo") + "位" : (done && "confirmed".equals(gs) ? "已完成" : SIGNUP_LABEL.get(gs)));
            m.put("stateTone", "confirmed".equals(gs) ? "green" : ("pending_payment".equals(gs) || "waitlist".equals(gs) ? "ochre" : "blue"));
            m.put("attend", num(r.get("signed")) + "人已报名");
            m.put("cancel", done ? null : ("pending_payment".equals(gs) ? "去支付" : (Arrays.asList("confirmed", "waitlist").contains(gs) ? "取消报名" : null)));
            m.put("manage", false);
            m.put("convId", "confirmed".equals(gs) ? r.get("convId") : null);
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- 发起（用户自发 / 员工创建）

    private Date parseTime(Object v, String label, boolean required)
    {
        String s = str(v).trim();
        if (s.isEmpty())
        {
            if (required)
            {
                throw new ServiceException("请选择" + label, 422);
            }
            return null;
        }
        Date d = dt(s.replace('T', ' '));
        if (d == null)
        {
            throw new ServiceException(label + "格式应为 yyyy-MM-dd HH:mm", 422);
        }
        return d;
    }

    private Map<String, Object> fields(Map<String, Object> b, boolean staff)
    {
        return fields(b, staff, Collections.emptyMap());
    }

    private Map<String, Object> fields(Map<String, Object> b, boolean staff, Map<String, Object> original)
    {
        String title = str(b.get("title")).trim();
        if (title.isEmpty() || title.length() > 30)
        {
            throw new ServiceException("活动标题为 1–30 字", 422);
        }
        Date start = parseTime(b.get("startTime"), "活动开始时间", true);
        if (start.getTime() <= System.currentTimeMillis() || (!start.equals(dt(original.get("startTime"))) && start.getTime() < System.currentTimeMillis() + 3600_000L))
        {
            throw new ServiceException("开始时间至少在 1 小时之后", 422);
        }
        Date end = parseTime(b.get("endTime"), "结束时间", false);
        if (end == null && b.get("hours") != null)
        {
            long h = Math.max(1, Math.min(72, num(b.get("hours"))));
            end = new Date(start.getTime() + h * 3600_000L);
        }
        if (end != null && !end.after(start))
        {
            throw new ServiceException("结束时间要晚于开始时间", 422);
        }
        Date deadline = parseTime(b.get("deadline"), "报名截止时间", false);
        if (deadline != null && deadline.after(start))
        {
            throw new ServiceException("报名截止要早于活动开始", 422);
        }
        // 截止已过还收下的话，定时任务一分钟内就把活动关成「报名截止」：发起页默认的「前一天 20:00」在活动就在明天时常常已经过了
        if (deadline != null && !deadline.after(new Date()) && !deadline.equals(dt(original.get("deadline"))))
        {
            throw new ServiceException("报名截止时间已过，请改到现在之后，或不设截止（开始前都可报名）", 422);
        }
        String place = str(b.get("place")).trim();
        if (place.isEmpty() || place.length() > 128)
        {
            throw new ServiceException("请填写集合地点", 422);
        }
        long cap = num(b.get("capacity"));
        if (cap < (staff ? 1 : 2) || cap > 50)
        {
            throw new ServiceException("人数为 " + (staff ? 1 : 2) + "–50 人", 422);
        }
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        cols.put("title", title);
        if (staff && b.containsKey("productKind")) {
            String productKind = str(b.get("productKind"));
            if (!Arrays.asList("activity", "travel").contains(productKind)) throw new ServiceException("请选择活动或旅游产品", 422);
            cols.put("product_kind", productKind);
        }
        cols.put("start_time", start);
        cols.put("end_time", end);
        cols.put("deadline", deadline);
        cols.put("place", place);
        cols.put("capacity", cap);
        String coverPath = str(b.get("cover")).trim();
        if (!coverPath.isEmpty() && !KgJdbc.isPlatformImage(coverPath))
        {
            throw new ServiceException("封面只能使用本平台上传的图片", 422);
        }
        cols.put("cover", coverPath);
        String cat = str(b.get("category"));
        cols.put("category", CATEGORIES.contains(cat) ? cat : "其他");
        // 活动介绍（流程、亮点、集合方式、注意事项）：员工端「创建活动」也能填了，超过 1000 字直接说清楚，
        // 不再悄悄截掉后面的内容（原先 cut 到 1000 字，介绍末尾的注意事项会无声丢失）
        String intro = str(b.get("intro")).trim();
        int introMax = staff ? INTRO_MAX : USER_INTRO_MAX;
        if (intro.length() > introMax)
        {
            throw new ServiceException("活动介绍最多 " + introMax + " 字，现在 " + intro.length() + " 字，请删减后再保存", 422);
        }
        cols.put("intro", intro);
        cols.put("safety_note", StaffSupport.cut(str(b.get("safetyNote")), 200));
        if (b.containsKey("slogan"))
        {
            cols.put("slogan", StaffSupport.cut(str(b.get("slogan")), 128));
        }
        // 集合点地图坐标（选填）：员工建 / 改活动、用户发起 / 修改活动都走这里；没带这两个键的请求不动原来的坐标
        meetPoint(b, cols);
        return cols;
    }

    /** 门店活动的来源：直营门店 = 小院活动，其他门店 = 商家活动（后台新增与员工端新建共用） */
    static String sourceOf(Map<String, Object> store)
    {
        return "direct".equals(str(store.get("opType"))) ? "yard" : "merchant";
    }

    /** 收费活动允许的收费方式：小院活动平台在线代收，商家活动只能到场支付（报名时也只有 yard + online 会真的建支付单） */
    static String paidMode(String source)
    {
        return "yard".equals(source) ? "online" : "offline";
    }

    /** 后台新增先进入待审核，沿用活动审核和报名流程。 */
    public void prepareAdminCreate(Map<String, Object> cols, Boolean creating)
    {
        if (!creating) return;
        Long storeId = KgJdbc.asLong(cols.get("store_id"));
        Map<String, Object> store = db.must("select store_name, city, op_type from kg_store where store_id = :s and status = '0' and del_flag = '0'", p("s", storeId), "请选择有效门店");
        // 来源由门店类型决定，与员工端 staffSave 同一条规则（后台表单里「来源」已改为只读）。原先运营自选来源，
        // 非直营门店能建成「小院活动 + 在线支付」：平台替商家收钱，详情页还标「平台直营」
        String source = sourceOf(store);
        String picked = str(cols.get("source"));
        if (!picked.isEmpty() && !picked.equals(source))
        {
            throw new ServiceException("「" + store.get("storeName") + "」" + ("yard".equals(source) ? "是直营门店，只能创建小院活动" : "不是直营门店，只能创建商家活动"), 422);
        }
        cols.put("source", source);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : cols.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Date) v = StaffSupport.fmt((Date)v, "yyyy-MM-dd HH:mm:ss");
            if (v instanceof java.time.LocalDateTime) v = ((java.time.LocalDateTime)v).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            body.put(KgJdbc.toCamel(e.getKey()), v);
        }
        cols.putAll(fields(body, true));
        pickCategory(cols, null);
        long fee = num(cols.get("fee_fen"));
        String mode = str(cols.get("fee_mode"));
        if (!Arrays.asList("free", "online", "offline", "aa").contains(mode) || fee < 0 || fee > 100000000L
                || ("free".equals(mode) || "aa".equals(mode) ? fee != 0 : fee <= 0)) throw new ServiceException("免费、AA制活动费用填 0（AA 的预估人均填在「AA预估人均」），收费活动请填写正数费用", 422);
        // AA制（2026-11-03）：现场平摊、平台不收钱，预估人均选填；别的方式不留 AA 预估
        long aa = num(cols.get("aa_fen"));
        if (aa < 0 || aa > 10000000L) throw new ServiceException("AA预估人均不合法", 422);
        cols.put("aa_fen", "aa".equals(mode) && aa > 0 ? Long.valueOf(aa) : null);
        // 收费方式同样跟来源走（员工端：免费 / 小院 = 在线支付 / 商家 = 到场支付）。报名时只有「小院 + 在线支付」才真的收款，
        // 「小院 + 到场支付」会让平台直营活动绕开在线收款，「商家 + 在线支付」则详情写着在线支付、报名却不收钱
        if (fee > 0 && !paidMode(source).equals(mode))
        {
            throw new ServiceException("yard".equals(source) ? "「" + store.get("storeName") + "」是直营门店（小院活动），收费由平台在线收取，请选择在线支付"
                    : "「" + store.get("storeName") + "」不是直营门店（商家活动），平台不代收费用，请选择到场支付", 422);
        }
        cols.put("fee_fen", fee);
        if (str(cols.get("city")).trim().isEmpty()) cols.put("city", store.get("city"));
        if (str(cols.get("city")).trim().isEmpty()) throw new ServiceException("请先设置门店城市", 422);
        if (str(cols.get("organizer_name")).trim().isEmpty()) cols.put("organizer_name", store.get("storeName"));
        cols.put("status", "pending");
        cols.put("review_mode", "manual");
        cols.put("checkin_token", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    /**
     * 用户自发活动：需要同意《活动参与须知》并验证手机号；先审后发（联调模拟模式下本地检查通过直接上线）。
     * 费用（2026-11-03 主人：「免费，aa，输入价格」）：免费 / AA制 / 收费三选一。平台不给普通用户代收钱（没有商户号、没有分账和退款审批），
     * 所以用户活动的「收费」只是标价：fee_mode = offline，报名不建支付单，详情写「到场付给发起人」，钱由参加的人当面付给发起人。
     * 老版本小程序不带 feeKind，仍只能免费（带了金额就拦下，提示和原来一样）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createByUser(Long me, Map<String, Object> b, Long postId)
    {
        AccountState.requireActive(db, me);
        guard.requireConsent(me, "activity");
        guard.requirePhone(me);
        if (str(b.get("feeKind")).trim().isEmpty() && (num(b.get("feeFen")) > 0 || num(b.get("fee")) > 0))
        {
            throw new ServiceException("用户自发活动只能免费；需要收费请联系小院或入驻商家发起", 422);
        }
        if (db.count("select count(*) from kg_activity where app_user_id = :u and source = 'user' and status in ('pending','open','paused','closed') and del_flag = '0'",
                p("u", me)) >= 5)
        {
            throw new ServiceException("同时进行中的自发活动最多 5 个", 409);
        }
        Map<String, Object> cols = fields(b, false);
        String city = str(b.get("city")).trim();
        if (city.isEmpty())
        {
            Map<String, Object> u = guard.user(me);
            city = str(u.get("city"));
        }
        if (city.isEmpty())
        {
            throw new ServiceException("请选择城市", 422);
        }
        String level = guard.check(str(cols.get("title")), str(cols.get("intro")), str(cols.get("place")), str(cols.get("safety_note")));
        cols.put("source", "user");
        cols.put("app_user_id", me);
        cols.put("organizer_name", guard.user(me).get("displayName"));
        // 存业务城市（列表 / 订阅都按业务城市精确匹配）：表单里选到区县（思明区）时，存成区县就哪个城市的列表里都查不到
        cols.put("city", StaffSupport.cut(bizCity(city), 32));
        applyFee(b, cols, null, "user");
        pickCategory(cols, null);
        if (b.containsKey("feeIncludes"))
        {
            cols.put("fee_includes", StaffSupport.cut(str(b.get("feeIncludes")), 128));
        }
        cols.put("status", "online".equals(guard.initialStatus(level)) ? "open" : "pending");
        cols.put("review_mode", guard.mode());
        cols.put("post_id", postId);
        cols.put("checkin_token", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        cols.put("create_by", "app:" + me);
        cols.put("create_time", new Date());
        long id = db.insert("kg_activity", cols);
        if ("open".equals(cols.get("status")))
        {
            onOpened(id);
        }
        return id;
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String editByUser(Long me, Long id, Map<String, Object> b)
    {
        AccountState.requireActive(db, me);
        Map<String, Object> a = mustActivity(id);
        if (!me.equals(KgJdbc.asLong(a.get("appUserId"))) || !"user".equals(str(a.get("source"))))
        {
            throw new ServiceException("只能修改自己发起的活动", 403);
        }
        // 取消 / 结束的活动是终态，不能靠编辑重新开启（编辑会把状态改回 open / pending）；并加锁重读 signed，理由同员工端
        Map<String, Object> lockedUserAct = db.one("select * from kg_activity where activity_id = :id and del_flag = '0' for update", p("id", id));
        if (lockedUserAct == null) throw new ServiceException("活动不存在", 404);
        a = lockedUserAct;
        if (!me.equals(KgJdbc.asLong(a.get("appUserId"))) || !"user".equals(str(a.get("source"))))
            throw new ServiceException("只能修改自己发起的活动", 403);
        if (b.containsKey("editToken") && !KgPostService.editToken(a).equals(str(b.get("editToken"))))
            throw new ServiceException("活动内容或状态已变化，请重新打开编辑", 409);
        String st = str(a.get("status"));
        if (Arrays.asList("cancelled", "ended").contains(st))
        {
            throw new ServiceException("活动已取消或已结束，不能再修改", 409);
        }
        if (Boolean.TRUE.equals(b.get("contentOnly")) || Boolean.TRUE.equals(b.get("detailsOnly"))) return editUserContent(me, id, a, b);
        if (hasUserSignups(a))
        {
            throw new ServiceException("已有报名，请重新打开编辑活动；原费用和名额需保留", 409);
        }
        Date originalStart = dt(a.get("startTime"));
        if (originalStart == null || originalStart.getTime() <= System.currentTimeMillis()
                || db.count("select count(*) from kg_activity_signup where activity_id = :a and checkin_time is not null", p("a", id)) > 0)
            throw new ServiceException("活动已开始或已有成员签到，请重新打开编辑，仅修改活动内容", 409);
        Map<String, Object> cols = fields(b, false, a);
        pickCategory(cols, a.get("category"));
        // 费用：新版表单带 feeKind（免费 / 收费 = 到场付给发起人 / AA制），老版本不带就不动原来的费用。
        // 已有人报名（报名中且 signed > 0）的上面已经整条拦下，这里改费用不会影响已报名的人
        if (!str(b.get("feeKind")).trim().isEmpty())
        {
            applyFee(b, cols, a, "user");
            if (b.containsKey("feeIncludes"))
            {
                cols.put("fee_includes", StaffSupport.cut(str(b.get("feeIncludes")), 128));
            }
        }
        // 发起表单可以选城市了：修改时也允许改（之前城市只在创建时悄悄取首页城市，建错了城市发起人改不了）
        String city = str(b.get("city")).trim();
        if (!city.isEmpty())
        {
            cols.put("city", StaffSupport.cut(bizCity(city), 32));
        }
        String level = guard.check(str(cols.get("title")), str(cols.get("intro")), str(cols.get("place")), str(cols.get("safety_note")));
        String to = "online".equals(guard.initialStatus(level)) ? (Arrays.asList("paused", "closed").contains(st) ? st : "open") : "pending";
        cols.put("status", to);
        cols.put("audit_reason", null);
        cols.put("update_by", "app:" + me);
        cols.put("update_time", new Date());
        db.updateRow("kg_activity", "activity_id", id, cols);
        if ("open".equals(to) && !"open".equals(st))
        {
            // 已经在报名中的活动改个错别字再保存，不能再给订阅该城市的人重发一遍「有新活动」（员工端 staffSave 早有同样的判断）
            onOpened(id);
        }
        return "pending".equals(to) ? "已提交审核" : "修改已保存";
    }

    private String editUserContent(Long me, Long id, Map<String, Object> a, Map<String, Object> b)
    {
        if (!KgPostService.editToken(a).equals(str(b.get("editToken"))))
            throw new ServiceException("活动内容或状态已变化，请重新打开编辑", 409);
        boolean details = Boolean.TRUE.equals(b.get("detailsOnly"));
        List<String> allowed = new ArrayList<String>(Arrays.asList("contentOnly", "editToken", "title", "cover", "intro", "safetyNote"));
        if (details) allowed.addAll(Arrays.asList("detailsOnly", "startTime", "endTime", "deadline", "place", "city", "meetLat", "meetLng"));
        for (String key : b.keySet())
            if (!allowed.contains(key)) throw new ServiceException("本次编辑保留原报名、费用和名额，请勿修改其他字段", 422);
        String title = str(b.get("title")).trim(), intro = str(b.get("intro")).trim(), safety = str(b.get("safetyNote")).trim(), cover = str(b.get("cover"));
        if (title.isEmpty() || title.length() > 30 || intro.length() > USER_INTRO_MAX || safety.length() > 200)
            throw new ServiceException("请检查标题、介绍和安全说明的字数", 422);
        if (cover.isEmpty() || (!KgJdbc.isPlatformImage(cover) && !cover.equals(str(a.get("cover")))))
            throw new ServiceException("请上传有效的活动封面", 422);
        List<String> edited = new ArrayList<String>();
        for (String[] value : new String[][] { {title, str(a.get("title"))}, {intro, str(a.get("intro"))}, {safety, str(a.get("safetyNote"))} })
            if (!value[0].equals(value[1].trim())) edited.add(value[0]);
        Map<String, Object> arrangement = new LinkedHashMap<String, Object>();
        boolean arrangementChanged = details && userArrangement(id, a, b, arrangement);
        if (arrangementChanged) { edited.add(str(arrangement.get("place"))); edited.add(str(arrangement.get("city"))); }
        String level = guard.check(edited.toArray(new String[0]));
        String status = str(a.get("status"));
        boolean hasParticipants = hasUserSignups(a);
        if (hasParticipants)
        {
            if (!"pass".equals(level)) throw new ServiceException("修改内容需要人工审核，已有报名时请联系平台处理", 409);
        }
        else if (!"online".equals(guard.initialStatus(level))) status = "pending";
        Map<String, Object> cols = p("title", title, "cover", cover, "intro", intro, "safety_note", safety,
                "status", status, "update_by", "app:" + me, "update_time", new Date());
        if (arrangementChanged) cols.putAll(arrangement);
        db.updateRow("kg_activity", "activity_id", id, cols);
        if (arrangementChanged && hasParticipants) notifyUserArrangement(id, title, arrangement);
        return "pending".equals(status) ? "修改已提交审核" : (arrangementChanged && hasParticipants ? "修改已保存，已通知报名和候补成员" : "修改已保存，原报名信息保留");
    }

    private boolean hasUserSignups(Map<String, Object> a)
    {
        return num(a.get("signed")) > 0 || num(a.get("waitlist")) > 0 || db.count(
                "select count(*) from kg_activity_signup where activity_id=:a and status in (:ss)", p("a", a.get("activityId"), "ss", ACTIVE)) > 0;
    }

    /** Called with the activity row locked, shared by signup, schedule edits and check-in. */
    private boolean userArrangement(Long id, Map<String, Object> a, Map<String, Object> b, Map<String, Object> cols)
    {
        Date start = parseTime(b.get("startTime"), "集合时间", true);
        Date end = parseTime(b.get("endTime"), "结束时间", true);
        Date deadline = parseTime(b.get("deadline"), "报名截止", false);
        String place = str(b.get("place")).trim(), city = bizCity(str(b.get("city")).trim());
        if (place.isEmpty() || place.length() > 128 || city.isEmpty() || city.length() > 32)
            throw new ServiceException("请填写有效城市和集合地点", 422);
        cols.putAll(p("start_time", start, "end_time", end, "deadline", deadline, "place", place, "city", city));
        meetPoint(b, cols);
        boolean changed = !Objects.equals(start, dt(a.get("startTime"))) || !Objects.equals(end, dt(a.get("endTime")))
                || !Objects.equals(deadline, dt(a.get("deadline"))) || !place.equals(str(a.get("place"))) || !city.equals(str(a.get("city")));
        for (String key : Arrays.asList("meet_lat", "meet_lng"))
            if (cols.containsKey(key)) {
                java.math.BigDecimal old = coord(a.get(KgJdbc.toCamel(key))), current = (java.math.BigDecimal)cols.get(key);
                changed |= old == null ? current != null : current == null || old.compareTo(current) != 0;
            }
        if (!changed) return false;
        long now = System.currentTimeMillis();
        Date oldStart = dt(a.get("startTime"));
        if (oldStart == null || oldStart.getTime() <= now || db.count(
                "select count(*) from kg_activity_signup where activity_id=:a and checkin_time is not null", p("a", id)) > 0)
            throw new ServiceException("活动已开始或已有成员签到，时间地点不能再修改，请重新打开编辑", 409);
        if (start.getTime() <= now || (!start.equals(oldStart) && start.getTime() < now + 3600_000L))
            throw new ServiceException("新的集合时间至少在当前时间1小时后", 422);
        if (!end.after(start)) throw new ServiceException("结束时间必须晚于集合时间", 422);
        if (deadline != null && deadline.after(start)) throw new ServiceException("报名截止不能晚于集合时间", 422);
        if (!Objects.equals(deadline, dt(a.get("deadline"))) && deadline != null && deadline.getTime() <= now)
            throw new ServiceException("新的报名截止时间须晚于当前时间", 422);
        return true;
    }

    private void notifyUserArrangement(Long id, String title, Map<String, Object> cols)
    {
        String text = "活动安排已更新：" + rangeText(cols.get("start_time"), cols.get("end_time")) + "（北京时间），"
                + cols.get("city") + " · 集合：" + cols.get("place") + "。报名截止："
                + (cols.get("deadline") == null ? "活动开始前" : StaffSupport.fmt(dt(cols.get("deadline")), "M月d日 HH:mm"))
                + "。原报名保留，请查看活动详情确认最新安排。";
        for (Map<String, Object> signup : db.list("select distinct app_user_id from kg_activity_signup where activity_id=:a "
                + "and status in ('confirmed','pending_payment','waitlist')", p("a", id)))
            support.notifyUser(KgJdbc.asLong(signup.get("appUserId")), "system", "「" + title + "」安排变更", text, "activity", id, "/pages/community/activity?id=" + id);
        chats.systemMessage(id, text);
    }

    /** 活动进入报名中：建活动群、通知订阅了该城市的用户 */
    void onOpened(long id)
    {
        db.update("update kg_activity set first_published_time=coalesce(first_published_time,now()) where activity_id=:id", p("id",id));
        Map<String, Object> a = mustActivity(id);
        chats.ensureActivityGroup(id);
        // 按业务城市匹配订阅；归一之前存下的区县名订阅（思明区）也认领到所属城市（厦门），同一人订了两个名字只通知一次
        String city = bizCity(str(a.get("city")));
        List<String> names = new ArrayList<String>();
        names.add(city);
        for (Map<String, Object> r : db.list("select distinct city from kg_activity_subscribe where city <> :c", p("c", city)))
        {
            if (city.equals(bizCity(str(r.get("city")))))
            {
                names.add(str(r.get("city")));
            }
        }
        for (Map<String, Object> s : db.list("select distinct app_user_id from kg_activity_subscribe where city in (:cs) limit 500", p("cs", names)))
        {
            Long uid = KgJdbc.asLong(s.get("appUserId"));
            if (!uid.equals(KgJdbc.asLong(a.get("appUserId"))))
            {
                support.notifyUser(uid, "system", a.get("city") + "有新活动", a.get("title") + "，" + rangeText(a.get("startTime"), a.get("endTime")), "activity", id,
                        "/pages/community/activity?id=" + id);
            }
        }
    }

    // ---------------------------------------------------------------- 发起人 / 门店管理

    /** 发起人（用户自发）或门店员工看活动管理；phoneWindow = 活动前后 24 小时内可查看参与者电话 */
    public Map<String, Object> manageView(Map<String, Object> a)
    {
        Long id = KgJdbc.asLong(a.get("activityId"));
        Map<String, Object> m = card(a);
        m.put("status", a.get("status"));
        m.put("statusLabel", STATUS_LABEL.get(str(a.get("status"))));
        m.put("auditReason", a.get("auditReason"));
        m.put("views", num(a.get("views")));
        m.put("waitlist", num(a.get("waitlist")));
        m.put("convId", a.get("convId"));
        m.put("organizerName", a.get("organizerName"));
        m.put("intro", a.get("intro"));
        m.put("safetyNote", a.get("safetyNote"));
        m.put("editToken", KgPostService.editToken(a));
        m.put("startTime", a.get("startTime"));
        m.put("endTime", a.get("endTime"));
        m.put("deadline", a.get("deadline"));
        m.put("category", a.get("category"));
        // 费用说明（kg_activity.fee_includes）：员工端「AA制」填的分摊说明，修改时回填
        m.put("feeIncludes", a.get("feeIncludes"));
        long now = System.currentTimeMillis();
        Date s = dt(a.get("startTime"));
        Date e = a.get("endTime") == null ? new Date(s.getTime() + 4 * 3600_000L) : dt(a.get("endTime"));
        boolean window = now >= s.getTime() - 24 * 3600_000L && now <= e.getTime() + 24 * 3600_000L;
        m.put("phoneWindow", window);
        List<Map<String, Object>> confirmed = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> waiting = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> refunds = new ArrayList<Map<String, Object>>();
        long checked = 0;
        long cancelled = 0;
        for (Map<String, Object> r : db.list("select g.*, u.avatar from kg_activity_signup g left join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.activity_id = :a order by g.status = 'confirmed' desc, g.waitlist_no, g.signup_id", p("a", id)))
        {
            String st = str(r.get("status"));
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("signupId", r.get("signupId"));
            v.put("userId", r.get("appUserId"));
            v.put("name", r.get("name"));
            v.put("avatar", r.get("avatar"));
            v.put("phone", r.get("phone") == null ? "未验证手机号" : maskPhone(str(r.get("phone"))));
            v.put("hasPhone", r.get("phone") != null);
            v.put("checkedIn", r.get("checkinTime") != null);
            v.put("status", st);
            v.put("feeFen", num(r.get("feeFen")));
            v.put("refundFen", num(r.get("refundFen")));
            v.put("cancelReason", r.get("cancelReason"));
            if ("confirmed".equals(st) || "pending_payment".equals(st))
            {
                v.put("tag", r.get("checkinTime") != null ? "已签到" : ("pending_payment".equals(st) ? "待支付" : "已确认"));
                confirmed.add(v);
                checked += r.get("checkinTime") != null ? 1 : 0;
            }
            else if ("waitlist".equals(st))
            {
                v.put("tag", "候补" + r.get("waitlistNo"));
                waiting.add(v);
            }
            else if ("refund_pending".equals(st))
            {
                v.put("tag", "退款待审批");
                refunds.add(v);
                cancelled++;
            }
            else if (Arrays.asList("cancelled", "refunded", "refunding", "closed").contains(st))
            {
                cancelled++;
            }
        }
        m.put("confirmed", confirmed);
        m.put("waiting", waiting);
        m.put("refunds", refunds);
        m.put("checkedIn", checked);
        m.put("hasSignups", hasUserSignups(a));
        m.put("arrangementEditable", !Arrays.asList("cancelled", "ended").contains(str(a.get("status")))
                && s.getTime() > now && db.count("select count(*) from kg_activity_signup where activity_id=:a and checkin_time is not null", p("a", id)) == 0);
        m.put("cancelledCount", cancelled);
        m.put("trend", trend(id, 7));
        m.put("reviews", db.count("select count(*) from kg_review where target_type = 'activity' and target_id = :a", p("a", id)));
        return m;
    }

    private List<Map<String, Object>> trend(Long id, int days)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        LocalDate end = LocalDate.now();
        Map<String, long[]> by = new LinkedHashMap<String, long[]>();
        for (Map<String, Object> r : db.list("select stat_day, sum(views) v, sum(signups) s from kg_activity_stat where activity_id = :a and stat_day >= :f group by stat_day",
                p("a", id, "f", end.minusDays(days - 1).toString())))
        {
            by.put(str(r.get("statDay")).substring(0, 10), new long[] { num(r.get("v")), num(r.get("s")) });
        }
        long cum = db.count("select coalesce(sum(signups), 0) from kg_activity_stat where activity_id = :a and stat_day < :f",
                p("a", id, "f", end.minusDays(days - 1).toString()));
        for (int i = days - 1; i >= 0; i--)
        {
            LocalDate d = end.minusDays(i);
            long[] v = by.getOrDefault(d.toString(), new long[2]);
            cum += v[1];
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("day", d.getMonthValue() + "/" + d.getDayOfMonth());
            m.put("views", v[0]);
            m.put("signups", v[1]);
            m.put("cumulative", cum);
            out.add(m);
        }
        return out;
    }

    /** 发起人校验后返回活动原始行（给状态操作、签到、查看电话用） */
    public Map<String, Object> ownUserActivity(Long me, Long id)
    {
        Map<String, Object> a = mustActivity(id);
        if (!me.equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            throw new ServiceException("只有发起人可以管理活动", 403);
        }
        if (!"user".equals(str(a.get("source"))))
        {
            // 门店活动（source = yard / merchant）的 app_user_id 只是创建它的员工：用户侧只认发起人会绕过员工端的门店授权与角色矩阵——
            // 普通员工能取消整场收费活动、授权被撤销的前员工还能读参与者完整手机号（2026-09-28 复核发现）。门店活动一律走员工端。
            throw new ServiceException("门店活动请在员工工作台管理", 403);
        }
        return a;
    }

    /** User management writes share erasure's account -> activity lock order. */
    @Transactional(rollbackFor = Exception.class)
    public String actAsUser(Long me, Long id, String action, String text, String code, String reason)
    {
        AccountState.requireActive(db, me);
        ownUserActivity(me, id); // Authorize before locking the activity.
        Map<String, Object> a = db.one("select * from kg_activity where activity_id = :id and del_flag = '0' for update", p("id", id));
        if (a == null) throw new ServiceException("活动不存在", 404);
        // Locking read is current even if the preceding authorization snapshot waited.
        if (!me.equals(KgJdbc.asLong(a.get("appUserId"))) || !"user".equals(str(a.get("source"))))
            throw new ServiceException("只有发起人可以管理用户活动", 403);
        if ("broadcast".equals(action)) return broadcast(a, text, "发起人");
        if ("checkin".equals(action)) return checkinByCode(a, code);
        return transition(a, action, reason, "app:" + me);
    }

    public Map<String, Object> manageAsUser(Long me, Long id)
    {
        Map<String, Object> a = ownUserActivity(me, id);
        Map<String, Object> m = manageView(a);
        attachCheckinCode(m, a);
        return m;
    }

    private String ensureToken(Map<String, Object> a)
    {
        String t = str(a.get("checkinToken"));
        if (t.isEmpty())
        {
            t = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            db.update("update kg_activity set checkin_token = :t where activity_id = :a and (checkin_token is null or checkin_token = '')", p("t", t, "a", a.get("activityId")));
            t = str(db.one("select checkin_token from kg_activity where activity_id = :a", p("a", a.get("activityId"))).get("checkinToken"));
        }
        return t;
    }

    public String checkinQr(Map<String, Object> a)
    {
        return str(wxMiniApp.activityCode(KgJdbc.asLong(a.get("activityId")), ensureToken(a)).get("image"));
    }

    /** Caller must first establish the existing owner/store authorization. */
    public void attachCheckinCode(Map<String, Object> view, Map<String, Object> activity)
    {
        Map<String, Object> code = wxMiniApp.activityCode(KgJdbc.asLong(activity.get("activityId")), ensureToken(activity));
        view.put("checkinQr", code.get("image"));
        view.put("checkinCodeInfo", code);
    }

    @Transactional(rollbackFor = Exception.class)
    public String checkinByToken(Long me, Long id, String token)
    {
        Map<String, Object> current = db.one("select * from kg_activity where activity_id=:id and del_flag='0' for update", p("id", id));
        if (current == null) throw new ServiceException("活动不存在", 404);
        return selfCheckin(me, current, token);
    }

    /** 参与者扫签到码：活动开始前 2 小时到结束之间有效 */
    private String selfCheckin(Long me, Map<String, Object> a, String token)
    {
        if (!token.equals(str(a.get("checkinToken"))))
        {
            return "签到码无效";
        }
        if (!inCheckinWindow(a))
        {
            return checkinWindowTip(a);
        }
        int n =db.update("update kg_activity_signup set checkin_time = now(), update_time = now() where activity_id = :a and app_user_id = :u "
                + "and status = 'confirmed' and checkin_time is null", p("a", a.get("activityId"), "u", me));
        if (n > 0)
        {
            return "签到成功";
        }
        return db.count("select count(*) from kg_activity_signup where activity_id = :a and app_user_id = :u and checkin_time is not null",
                p("a", a.get("activityId"), "u", me)) > 0 ? "你已签到" : "你没有已确认的报名，无法签到";
    }

    static final String CHECKIN_WINDOW_TIP = "不在签到时间内（开始前 2 小时起可签到）";

    private static String checkinTimeText(Date date)
    {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(date);
    }

    static String checkinWindowText(Map<String, Object> a)
    {
        if ("ended".equals(str(a.get("status")))) return "活动已结束，不能签到";
        if ("cancelled".equals(str(a.get("status")))) return "活动已取消，无需签到";
        Date start = dt(a.get("startTime"));
        if (start == null) return "活动时间待确认，请联系主办方";
        Date end = dt(a.get("endTime"));
        if (end == null) end = new Date(start.getTime() + 4 * 3600_000L);
        return "签到时间：" + checkinTimeText(new Date(start.getTime() - 2 * 3600_000L)) + " 至 " + checkinTimeText(end) + "（北京时间）";
    }

    static String checkinWindowTip(Map<String, Object> a)
    {
        if ("ended".equals(str(a.get("status")))) return "活动已结束，不能签到";
        if ("cancelled".equals(str(a.get("status")))) return "活动已取消，无需签到";
        Date start = dt(a.get("startTime"));
        if (start == null) return "活动时间待确认，请联系主办方";
        Date end = dt(a.get("endTime"));
        if (end == null) end = new Date(start.getTime() + 4 * 3600_000L);
        if (System.currentTimeMillis() < start.getTime() - 2 * 3600_000L)
            return "签到尚未开放，将于 " + checkinTimeText(new Date(start.getTime() - 2 * 3600_000L)) + " 开放（北京时间，活动开始前2小时）。";
        if (System.currentTimeMillis() > end.getTime()) return "签到已于 " + checkinTimeText(end) + " 结束（北京时间）。";
        return CHECKIN_WINDOW_TIP;
    }

    /** 签到时间窗：活动开始前 2 小时到结束（没填结束按开始后 4 小时）。参与者扫码、领队输码两条路共用 */
    static boolean inCheckinWindow(Map<String, Object> a)
    {
        if (Arrays.asList("ended", "cancelled").contains(str(a.get("status")))) return false;
        Date s = dt(a.get("startTime"));
        if (s == null)
        {
            return false;
        }
        Date e = a.get("endTime") == null ? new Date(s.getTime() + 4 * 3600_000L) : dt(a.get("endTime"));
        long now = System.currentTimeMillis();
        return now >= s.getTime() - 2 * 3600_000L && (e == null || now <= e.getTime());
    }

    /** 领队 / 员工按参与者出示的签到码签到（和参与者自己扫码同一个时间窗，之前任何时候都能签） */
    public String checkinByCode(Map<String, Object> a, String code)
    {
        if (code == null || !code.trim().matches("\\d{6}"))
        {
            throw new ServiceException("签到码为 6 位数字", 422);
        }
        if (!inCheckinWindow(a))
        {
            throw new ServiceException(checkinWindowTip(a), 409);
        }
        Map<String, Object> s = db.one("select signup_id, name, status, checkin_time from kg_activity_signup where activity_id = :a and checkin_code = :c",
                p("a", a.get("activityId"), "c", code.trim()));
        if (s == null || !"confirmed".equals(str(s.get("status"))))
        {
            throw new ServiceException("签到码无效或报名未确认", 404);
        }
        if (s.get("checkinTime") != null)
        {
            return s.get("name") + " 已签到过";
        }
        db.update("update kg_activity_signup set checkin_time = now(), update_time = now() where signup_id = :s and checkin_time is null", p("s", s.get("signupId")));
        return s.get("name") + " 签到成功";
    }

    /** 查看参与者完整手机号：只在活动前后 24 小时窗口内，每次留痕 */
    public Map<String, Object> revealPhone(Long viewer, Map<String, Object> a, Long signupId)
    {
        Map<String, Object> view = manageView(a);
        if (!Boolean.TRUE.equals(view.get("phoneWindow")))
        {
            throw new ServiceException("只能在活动开始前 24 小时到结束后 24 小时内查看参与者电话", 403);
        }
        Map<String, Object> s = db.one("select g.app_user_id, g.name, g.phone, g.status from kg_activity_signup g join kg_app_user u on u.user_id = g.app_user_id "
                + "where g.signup_id = :s and g.activity_id = :a and u.status = '0' and u.del_flag = '0'",
                p("s", signupId, "a", a.get("activityId")));
        if (s == null || s.get("phone") == null || !Arrays.asList("confirmed", "waitlist").contains(str(s.get("status"))))
        {
            throw new ServiceException("该成员没有可联系的手机号", 404);
        }
        db.update("insert into kg_pii_access (viewer_id, target_id, biz_type, biz_id, field, create_time) values (:v, :t, 'activity', :a, 'phone', now())",
                p("v", viewer, "t", s.get("appUserId"), "a", a.get("activityId")));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", s.get("name"));
        m.put("phone", s.get("phone"));
        return m;
    }

    /** 状态操作：pause 暂停报名 / resume 恢复报名 / close 结束报名 / end 结束活动 / cancel 取消活动（已付款的逐条转退款待审批） */
    @Transactional(rollbackFor = Exception.class)
    public String transition(Map<String, Object> a, String action, String reason, String operator)
    {
        return transition(a, action, reason, operator, true);
    }

    /**
     * @param canCancelSigned 操作人是否有权取消「已有报名 / 收费」的活动（主理人、平台；管家只能取消空活动）。
     *        在加锁之后再判一次：控制器里那次判断与这里之间有人报名，管家就能取消一场已有报名的活动。
     */
    @Transactional(rollbackFor = Exception.class)
    public String transition(Map<String, Object> a, String action, String reason, String operator, boolean canCancelSigned)
    {
        Long id = KgJdbc.asLong(a.get("activityId"));
        String st = str(a.get("status"));
        String to;
        List<String> from;
        switch (action)
        {
            case "pause":
                to = "paused";
                from = Arrays.asList("open");
                break;
            case "resume":
                to = "open";
                from = Arrays.asList("paused", "closed");
                break;
            case "close":
                to = "closed";
                from = Arrays.asList("open", "paused");
                break;
            case "end":
                to = "ended";
                from = Arrays.asList("open", "paused", "closed");
                if (a.get("startTime") != null && new Date().before(dt(a.get("startTime"))))
                {
                    // 没开始的活动不能「结束」：结束不会退已付款的报名费、也不通知报名人，而且结束之后谁都没法再取消；
                    // 有只带编辑权限的员工借它绕过「取消活动」需要的审核权限（2026-09-28 复核发现）
                    throw new ServiceException("活动还没开始，不能直接结束；不办了请用「取消活动」（会通知报名人并处理退款）", 409);
                }
                break;
            case "cancel":
                to = "cancelled";
                from = Arrays.asList("pending", "open", "paused", "closed");
                break;
            default:
                throw new ServiceException("未知操作");
        }
        if (!from.contains(st))
        {
            throw new ServiceException("当前状态（" + STATUS_LABEL.getOrDefault(st, st) + "）不能" + ("cancel".equals(action) ? "取消" : "执行该操作"), 409);
        }
        if ("resume".equals(action) && !new Date().before(dt(a.get("startTime"))))
        {
            throw new ServiceException("活动已开始，不能再恢复报名", 409);
        }
        if ("resume".equals(action) && a.get("deadline") != null && dt(a.get("deadline")) != null && !new Date().before(dt(a.get("deadline"))))
        {
            // 截止已过时恢复，定时任务一分钟内又会关回去，看起来像没点成功
            throw new ServiceException("报名截止时间已过，请先编辑活动把报名截止改到现在之后，再恢复报名", 409);
        }
        if ("cancel".equals(action) && (reason == null || reason.trim().isEmpty()))
        {
            throw new ServiceException("请填写取消原因，会通知给所有报名的人", 422);
        }
        if (db.update("update kg_activity set status = :to, audit_reason = :r, update_by = :by, update_time = now() where activity_id = :id and status = :from",
                p("to", to, "r", "cancel".equals(action) ? StaffSupport.cut(reason, 255) : a.get("auditReason"), "by", operator, "id", id, "from", st)) == 0)
        {
            throw new ServiceException("状态已变化，请刷新", 409);
        }
        if ("cancel".equals(action) && !canCancelSigned)
        {
            Map<String, Object> cur = db.one("select signed, fee_fen from kg_activity where activity_id = :id for update", p("id", id));
            if (cur != null && (num(cur.get("signed")) > 0 || num(cur.get("feeFen")) > 0))
            {
                throw new ServiceException(com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() ? "取消已有报名或收费的活动，只有城市负责人或平台可以操作" : "取消已有报名或收费的活动，只有主理人可以操作", 403);
            }
        }
        if ("cancel".equals(action))
        {
            int refunds = 0;
            List<Map<String, Object>> refundRows = new ArrayList<Map<String, Object>>();
            // 已取消但钱没退的（开始前 24 小时内自己取消按规则不退、之前退款被驳回的）：活动没办成，钱应退，一并转退款待审批（2026-11-09 主人拍板）
            String noActiveRefund = "not exists (select 1 from kg_pay_refund r where r.pay_no = o.pay_no and " + KgPayService.ACTIVE_REFUND_SQL + ")";
            for (Map<String, Object> s : db.list("select g.signup_id, g.app_user_id, g.name, g.cancel_reason, o.amount - o.refund_amount pay_left from kg_activity_signup g "
                    + "join kg_pay_order o on o.pay_no = g.pay_no and o.biz_type = '" + BIZ + "' and o.biz_id = g.signup_id and o.del_flag = '0' "
                    + "where g.activity_id = :a and g.status in ('cancelled','closed') and o.status in ('paid','part_refunded') and o.amount > o.refund_amount and "
                    + noActiveRefund, p("a", id)))
            {
                long left = num(s.get("payLeft"));
                String was = str(s.get("cancelReason"));
                if (left <= 0 || db.update("update kg_activity_signup set status = 'refund_pending', refund_fen = :rf, cancel_reason = :r, cancel_time = coalesce(cancel_time, now()), "
                        + "update_time = now() where signup_id = :s and status in ('cancelled','closed')",
                        p("rf", left, "r", StaffSupport.cut("活动取消：" + StaffSupport.cut(reason, 150) + (was.isEmpty() ? "" : "（之前已取消：" + was + "）"), 255),
                                "s", s.get("signupId"))) == 0)
                {
                    continue;
                }
                refunds++;
                refundRows.add(p("name", s.get("name"), "feeFen", left, "appUserId", s.get("appUserId")));
                support.notifyUser(KgJdbc.asLong(s.get("appUserId")), "system", "活动已取消", "「" + a.get("title") + "」已取消：" + reason
                        + "。你之前取消报名时没退的报名费 ¥" + yuan(left) + " 也会在审核后原路退回。", "activity", id, "/pages/community/activity?id=" + id);
            }
            for (Map<String, Object> s : db.list("select * from kg_activity_signup where activity_id = :a and status in ('pending_payment','confirmed','waitlist')", p("a", id)))
            {
                String ss = str(s.get("status"));
                if ("pending_payment".equals(ss) && s.get("payNo") != null && !pay.close(str(s.get("payNo"))))
                {
                    ss = "confirmed"; // 刚好付款成功：按已付款处理，转退款
                }
                boolean paid = s.get("paidTime") != null || !"pending_payment".equals(ss) && num(s.get("feeFen")) > 0 && s.get("payNo") != null;
                boolean refund = paid && num(s.get("feeFen")) > 0;
                db.update("update kg_activity_signup set status = :to, refund_fen = :rf, cancel_reason = :r, cancel_time = now(), update_time = now() where signup_id = :s",
                        p("to", refund ? "refund_pending" : "cancelled", "rf", refund ? num(s.get("feeFen")) : 0, "r", "活动取消：" + StaffSupport.cut(reason, 200),
                                "s", s.get("signupId")));
                refunds += refund ? 1 : 0;
                if (refund)
                {
                    refundRows.add(p("name", s.get("name"), "feeFen", num(s.get("feeFen")), "appUserId", s.get("appUserId")));
                }
                support.notifyUser(KgJdbc.asLong(s.get("appUserId")), "system", "活动已取消", "「" + a.get("title") + "」已取消：" + reason
                        + (refund ? "。报名费会在审核后原路退回。" : "。"), "activity", id, "/pages/community/activity?id=" + id);
            }
            db.update("update kg_activity set signed = 0, waitlist = 0 where activity_id = :id", p("id", id));
            notifyCancelRefunds(a, refundRows);
            chats.systemMessage(id, "活动已取消：" + reason);
            String msg = refunds > 0 ? "活动已取消，" + refunds + " 笔报名费转为退款待审批" : "活动已取消，已通知所有报名的人";
            // 重新报名前的旧付款（报名行复用后 pay_no 换成了新单）走不了这条报名的审批：写明单号，请平台在后台「支付单」里退
            List<String> detached = new ArrayList<String>();
            for (Map<String, Object> o : db.list("select o.pay_no from kg_pay_order o join kg_activity_signup g on g.signup_id = o.biz_id "
                    + "where o.biz_type = '" + BIZ + "' and o.del_flag = '0' and g.activity_id = :a and o.status in ('paid','part_refunded') and o.amount > o.refund_amount "
                    + "and (g.pay_no is null or g.pay_no <> o.pay_no) and " + noActiveRefund, p("a", id)))
            {
                detached.add(str(o.get("payNo")));
            }
            if (!detached.isEmpty())
            {
                log.warn("活动整场取消，有重新报名前的旧付款未退 activity={} pays={}", id, detached);
                msg += "；另有 " + detached.size() + " 笔重新报名前的旧付款要平台在后台「支付单」里退款（" + String.join("、", detached) + "）";
            }
            return msg;
        }
        if ("resume".equals(action))
        {
            promote(id);
        }
        return "pause".equals(action) ? "已暂停报名" : ("resume".equals(action) ? "已恢复报名" : ("close".equals(action) ? "已结束报名" : "活动已结束"));
    }

    /** 现场通知：发到活动群 + 给每位已确认成员发站内信 */
    public String broadcast(Map<String, Object> a, String text, String from)
    {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 200)
        {
            throw new ServiceException("通知内容为 1–200 字", 422);
        }
        guard.check(t);
        Long id = KgJdbc.asLong(a.get("activityId"));
        int n = 0;
        for (Map<String, Object> s : db.list("select app_user_id from kg_activity_signup where activity_id = :a and status = 'confirmed'", p("a", id)))
        {
            support.notifyUser(KgJdbc.asLong(s.get("appUserId")), "system", "「" + a.get("title") + "」通知", t, "activity", id, "/pages/community/activity?id=" + id);
            n++;
        }
        chats.systemMessage(id, from + " 发布通知：" + t);
        return "已通知 " + n + " 位成员";
    }

    // ---------------------------------------------------------------- 退款审批（门店员工 / 后台）

    @Transactional(rollbackFor = Exception.class)
    public String approveRefund(Long signupId, String operator)
    {
        lockActivityForSignup(signupId);
        Map<String, Object> s = db.must("select * from kg_activity_signup where signup_id = :s for update", p("s", signupId), "报名记录不存在");
        if (!"refund_pending".equals(str(s.get("status"))))
        {
            throw new ServiceException("当前不是退款待审批状态", 409);
        }
        db.update("update kg_activity_signup set status = 'refunding', refund_note = " + KEEP_OLD_PAY + ", update_time = now() where signup_id = :s and status = 'refund_pending'",
                p("n", "同意（" + operator + "）", "s", signupId));
        pay.refund(str(s.get("payNo")), num(s.get("refundFen")), "活动报名退款：" + str(s.get("cancelReason")), operator);
        return "已同意退款 ¥" + yuan(num(s.get("refundFen"))) + "，退款处理中，到账时间以支付渠道为准";
    }

    /**
     * 驳回退款 = 恢复报名、保留名额（2026-11-09 主人拍板）：之前驳回后报名是「已取消」、钱也不退，付了钱却没了位置。
     * 现在恢复为「已确认」、名额加回、重新进活动群；活动此刻已满也照样恢复（付了钱、平台不退，就该有位置），这时会超员 1 人。
     * 不能驳回（只能同意退款）的三种：活动已整场取消（没办成，钱应退）、报名关闭后才到账的迟到付款（和旅居订单同一规则）、
     * 活动已开始（名额没法再还给他了，这笔本来就是开始前 24 小时以上取消的）。
     */
    @Transactional(rollbackFor = Exception.class)
    public String rejectRefund(Long signupId, String note, String operator)
    {
        if (note == null || note.trim().isEmpty())
        {
            throw new ServiceException("请填写驳回原因", 422);
        }
        String why = note.trim();
        lockActivityForSignup(signupId);
        Map<String, Object> s = db.must("select * from kg_activity_signup where signup_id = :s for update", p("s", signupId), "报名记录不存在");
        if (!"refund_pending".equals(str(s.get("status"))))
        {
            throw new ServiceException("当前不是退款待审批状态", 409);
        }
        Long activityId = KgJdbc.asLong(s.get("activityId"));
        Map<String, Object> a = db.one("select * from kg_activity where activity_id = :id", p("id", activityId));
        if (a == null)
        {
            throw new ServiceException("活动不存在", 404);
        }
        String ast = str(a.get("status"));
        if ("cancelled".equals(ast))
        {
            throw new ServiceException("这场活动已整场取消，报名费应退回，不能驳回；请点「同意退款」", 409);
        }
        if (str(s.get("cancelReason")).startsWith(LATE_PAID))
        {
            throw new ServiceException("这笔是报名关闭后才到账的付款，要继续退款，不能驳回；请点「同意退款」", 409);
        }
        if ("ended".equals(ast) || dt(a.get("startTime")) == null || !new Date().before(dt(a.get("startTime"))))
        {
            throw new ServiceException("活动已开始，报名没法再恢复，不能驳回；请点「同意退款」", 409);
        }
        if (s.get("payNo") != null && db.count("select count(*) from kg_pay_refund where pay_no = :n and " + KgPayService.ACTIVE_REFUND_SQL, p("n", s.get("payNo"))) > 0)
        {
            throw new ServiceException("这笔报名费已经在退款中，不能驳回", 409);
        }
        if (db.update("update kg_activity_signup set status = 'confirmed', refund_fen = 0, cancel_reason = null, cancel_time = null, expire_time = null, "
                + "refund_note = " + KEEP_OLD_PAY + ", update_time = now() where signup_id = :s and status = 'refund_pending'",
                p("n", StaffSupport.cut("驳回：" + why + "（" + operator + "）", 200), "s", signupId)) == 0)
        {
            throw new ServiceException("当前不是退款待审批状态", 409);
        }
        boolean full = num(a.get("signed")) >= num(a.get("capacity"));
        // 名额照样加回：满了也恢复（超员 1 人），不走 signed < capacity 的条件
        db.update("update kg_activity set signed = signed + 1 where activity_id = :id", p("id", activityId));
        Long uid = KgJdbc.asLong(s.get("appUserId"));
        chats.joinActivityGroup(activityId, uid);
        String title = str(a.get("title"));
        support.notifyUser(uid, "order", "退款未通过，报名已恢复", "「" + title + "」的退款未通过：" + why + "。你的报名已恢复、名额保留，"
                + rangeText(a.get("startTime"), a.get("endTime")) + "，集合：" + a.get("place") + "。", "activity", activityId,
                "/pages/community/activity?signupId=" + signupId);
        Long storeId = KgJdbc.asLong(a.get("storeId"));
        if (storeId != null)
        {
            support.notifyStore(storeId, "system", "活动报名已恢复", str(s.get("name")) + " 的退款未通过，「" + title + "」的报名已恢复"
                    + (full ? "（名额已满，这场会多出 1 人）" : "") + "。", "activity", activityId, "/pages/staff/activities?s=4&id=" + activityId);
        }
        return full ? "已驳回退款，报名已恢复（活动名额已满，这场会超出 1 人）" : "已驳回退款，报名已恢复";
    }

    // ---------------------------------------------------------------- 员工端

    public Map<String, Object> staffList(StaffSupport.Ctx c, String tab, String source, String keyword, String sort)
    {
        Map<String, Object> ps = p("st", c.storeId);
        StringBuilder where = new StringBuilder(" where a.store_id = :st and a.del_flag = '0'");
        String alive = "coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) > now()";
        if ("open".equals(tab))
        {
            where.append(" and a.status = 'open' and a.start_time > now()");
        }
        else if ("ongoing".equals(tab))
        {
            where.append(" and a.status in ('open','paused','closed') and a.start_time <= now() and " + alive);
        }
        else if ("ended".equals(tab))
        {
            where.append(" and (a.status in ('ended','cancelled') or not " + alive + ")");
        }
        if (SOURCE_LABEL.containsKey(source))
        {
            where.append(" and a.source = :src");
            ps.put("src", source);
        }
        if (keyword != null && !keyword.trim().isEmpty())
        {
            where.append(" and (a.title like :kw or a.place like :kw or a.organizer_name like :kw)");
            ps.put("kw", "%" + keyword.trim() + "%");
        }
        String order = "signed".equals(sort) ? "a.signed desc, a.start_time" : ("old".equals(sort) ? "a.start_time" : "a.start_time desc");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : db.list("select a.*, u.nickname creator from kg_activity a left join kg_app_user u on u.user_id = a.app_user_id" + where
                + " order by " + order + " limit 100", ps))
        {
            Map<String, Object> m = card(a);
            String ph = phase(a);
            m.put("status", phaseLabel(ph));
            m.put("tone", "open".equals(ph) ? "green" : ("ongoing".equals(ph) || "pending".equals(ph) || "paused".equals(ph) ? "ochre" : "gray"));
            m.put("sourceLabel", SOURCE_LABEL.get(str(a.get("source"))).replace("活动", ""));
            m.put("owner", str(a.get("creator")).isEmpty() ? a.get("organizerName") : a.get("creator"));
            m.put("countText", num(a.get("signed")) + "/" + num(a.get("capacity")));
            m.put("price", yuan(num(a.get("feeFen"))));
            String st = str(a.get("status"));
            m.put("audit", "pending".equals(st) ? "待审核" : ("rejected".equals(st) ? "已驳回" : "审核通过"));
            m.put("auditTone", "pending".equals(st) ? "ochre" : ("rejected".equals(st) ? "gray" : "green"));
            rows.add(m);
        }
        Map<String, Object> cnt = db.one("select count(*) total, sum(status = 'open' and start_time > now()) open, "
                + "sum(status in ('open','paused','closed') and start_time <= now() and coalesce(end_time, date_add(start_time, interval 4 hour)) > now()) ongoing, "
                + "sum(status in ('ended','cancelled') or coalesce(end_time, date_add(start_time, interval 4 hour)) <= now()) ended "
                + "from kg_activity where store_id = :st and del_flag = '0'", p("st", c.storeId));
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", rows);
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("all", num(cnt.get("total")));
        counts.put("open", num(cnt.get("open")));
        counts.put("ongoing", num(cnt.get("ongoing")));
        counts.put("ended", num(cnt.get("ended")));
        res.put("counts", counts);
        return res;
    }

    public Map<String, Object> ownStoreActivity(StaffSupport.Ctx c, Long id)
    {
        Map<String, Object> a = mustActivity(id);
        Long st = KgJdbc.asLong(a.get("storeId"));
        if (st == null || !c.me.hasStoreAccess(st))
        {
            throw new ServiceException("活动不存在或没有权限", 403);
        }
        return a;
    }

    /** 员工修改活动时「请求里没带就保持原值」的字段：请求键 → 列名 */
    private static final String[][] KEEP_IF_ABSENT = { { "intro", "intro" }, { "safetyNote", "safety_note" }, { "deadline", "deadline" }, { "slogan", "slogan" } };

    /**
     * 员工创建 / 修改门店活动：新建时直营门店 = 小院活动（可在线收费），其他门店 = 商家活动（收费只能到场支付）；
     * 修改沿用活动自己存的来源和收费方式，不按门店当前类型重算。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> staffSave(StaffSupport.Ctx c, Long id, Map<String, Object> b)
    {
        Map<String, Object> cols = fields(b, true);
        Map<String, Object> store = db.must("select store_name, city, op_type from kg_store where store_id = :s", p("s", c.storeId), "门店不存在");
        String source = sourceOf(store);
        // 费用按元填写、最多两位小数（fen() 用 BigDecimal 解析）；格式不对先拦下，费用方式、收费方式与「费用包含」按新建 / 修改分别在下面定
        fen(b.get("feeYuan"), "费用");
        String level = guard.check(str(cols.get("title")), str(cols.get("intro")), str(cols.get("place")), str(cols.get("safety_note")));
        String status = "online".equals(guard.initialStatus(level)) ? "open" : "pending";
        // 只在活动「刚进入报名中」时建群、通知订阅城市的用户；编辑一个本来就在报名中的活动不再重复推送
        boolean announce = true;
        boolean capacityRaised = false;
        // 这次保存前的状态（新建没有）、活动自己存的来源和城市：判断要不要提醒本城商家服务人审核
        String before = null;
        String savedSource = source;
        Object savedCity = store.get("city");
        if (id == null)
        {
            cols.put("source", source);
            applyFee(b, cols, null, source);
            pickCategory(cols, null);
            cols.put("fee_includes", StaffSupport.cut(str(b.get("feeIncludes")), 128));
            cols.put("store_id", c.storeId);
            cols.put("app_user_id", c.uid());
            cols.put("organizer_name", store.get("storeName"));
            cols.put("city", store.get("city"));
            cols.put("status", status);
            cols.put("review_mode", guard.mode());
            cols.put("checkin_token", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            cols.put("create_by", "staff:" + c.uid());
            cols.put("create_time", new Date());
            id = db.insert("kg_activity", cols);
            support.op(c, "activity_create", "创建活动", "general", str(cols.get("title")), true, null);
        }
        else
        {
            Map<String, Object> a = ownStoreActivity(c, id);
            // 加锁重读：「已有人报名不能改时间 / 地点 / 费用」要按锁之后的 signed 判断，报名一侧的 signed + 1 与这里互斥，
            // 否则校验时还是 0 人、保存时已有人报名，规则就被绕过了
            Map<String, Object> locked = db.one("select * from kg_activity where activity_id = :id for update", p("id", id));
            if (locked != null)
            {
                a = locked;
            }
            String st = str(a.get("status"));
            if (Arrays.asList("ended", "cancelled").contains(st))
            {
                throw new ServiceException("活动已结束或取消，不能修改", 409);
            }
            before = st;
            savedSource = str(a.get("source"));
            savedCity = a.get("city");
            // 来源（不在 cols 里）和收费方式都沿用活动自己存的值。原先每次保存都按门店类型重算 fee_mode：
            // 后台建的「到场支付」活动被员工改一句介绍就悄悄变成在线支付，之后报名的人要先付款、之前报名的人没付过（反之亦然）。
            // 现在按表单选的免费 / 收费 / AA制定（有人报名时下面会拦住）：原本收费的保持原方式；免费、AA 改收费按活动自己的来源定
            applyFee(b, cols, a, savedSource);
            pickCategory(cols, a.get("category"));
            // 员工端修改表单不带「费用包含」：没传就保持原值，不能每次保存都把它清空
            if (b.containsKey("feeIncludes"))
            {
                cols.put("fee_includes", StaffSupport.cut(str(b.get("feeIncludes")), 128));
            }
            // 介绍、安全说明、报名截止、口号同理：员工端表单不展示这几项，请求里没带就保持原值。
            // 原先 fields() 把没传的写成 '' / null 再整行更新，员工改一次标题就把后台填的介绍、安全说明、截止时间清掉了
            for (String[] k : KEEP_IF_ABSENT)
            {
                if (!b.containsKey(k[0]))
                {
                    cols.remove(k[1]);
                }
            }
            if (!cols.containsKey("deadline"))
            {
                // 沿用原截止时间也要守住「截止早于开始」：开始时间被提前到原截止之后，不能留下一个开始后才截止的活动
                Date kept = dt(a.get("deadline"));
                if (kept != null && kept.after((Date) cols.get("start_time")))
                {
                    throw new ServiceException("原报名截止时间晚于新的开始时间，请一并调整报名截止时间", 422);
                }
            }
            boolean hasSignups = num(a.get("signed")) > 0 && !Arrays.asList("pending", "rejected").contains(st);
            if (hasSignups)
            {
                // 已有人报名：时间、地点、费用（金额、收费方式、费用包含）、名额下限这几项是报名人的约定，不能改（变更请用「现场通知」告知成员）；
                // 标题、封面、介绍、安全说明照常可改（2026-09-26 回归发现：此前连换封面都被整体拒绝，而提示说介绍可改）
                boolean same = sameTime(cols.get("start_time"), a.get("startTime")) && sameTime(cols.get("end_time"), a.get("endTime"))
                        && str(cols.get("place")).equals(str(a.get("place"))) && num(cols.get("fee_fen")) == num(a.get("feeFen"))
                        && str(cols.get("fee_mode")).equals(str(a.get("feeMode"))) && num(cols.get("aa_fen")) == num(a.get("aaFen"))
                        && (!cols.containsKey("fee_includes") || str(cols.get("fee_includes")).equals(str(a.get("feeIncludes"))))
                        && num(cols.get("capacity")) >= num(a.get("signed"));
                if (!same)
                {
                    throw new ServiceException("已有人报名，时间、地点、费用（金额、免费 / 收费 / AA制、AA 预估人均、费用说明）不能再改，名额也不能少于已报名人数；变更请用「现场通知」告知成员", 409);
                }
                // 地图上的集合点算「地点」：原来没标的可以补标（已发出的活动补个点，报名的人就能点开地图），标过的不能挪到别处
                if (!meetPointKept(cols, a))
                {
                    throw new ServiceException("已有人报名，地图上的集合点不能再挪到别处（原来没标的可以补标）；变更请用「现场通知」告知成员", 409);
                }
                // 已有人报名的活动不能转入审核（报名人会被挡在外面）：按内容检查结果定，不按审核模式定。
                // 原先生产环境（人工审核）算出来一律是「待审核」，这里永远 409，连换封面都改不了（2026-10-13 复查）。
                // 命中拦截词在上面 guard.check 已经 422；地点不许改、不再重查，只看这次真正改了的标题 / 介绍 / 安全说明：
                // 命中需人工审核的词或联系方式才拒绝，并说清是哪一类。
                // 没改的原文不再查：员工端表单不展示介绍、安全说明，保存时按现值原样带回，后台录入时写了电话的活动不能因此连封面都换不了
                List<String> edited = new ArrayList<String>();
                for (String[] k : new String[][] { { "title", "title" }, { "intro", "intro" }, { "safety_note", "safetyNote" } })
                {
                    // 比较时去掉首尾空白：介绍现在保存前会去首尾空白，库里老介绍末尾多个换行不能算「改过」
                    if (cols.containsKey(k[0]) && !str(cols.get(k[0])).trim().equals(str(a.get(k[1])).trim()))
                    {
                        edited.add(str(cols.get(k[0])));
                    }
                }
                String[] texts = edited.toArray(new String[0]);
                if (!"pass".equals(guard.check(texts)))
                {
                    throw new ServiceException(guard.hasContact(texts)
                            ? "已有人报名的活动，标题、介绍里不能出现手机号、微信号等联系方式，请删掉后再保存"
                            : "修改后的内容含需要平台审核的词语，已有人报名的活动不能转入审核；请换个说法后再保存", 409);
                }
                // 保持原状态（报名中 / 暂停 / 截止），不因改介绍把暂停的活动重新打开
                status = st;
            }
            announce = !"open".equals(st);
            cols.put("status", status);
            cols.put("audit_reason", null);
            cols.put("update_by", "staff:" + c.uid());
            cols.put("update_time", new Date());
            db.updateRow("kg_activity", "activity_id", id, cols);
            capacityRaised = num(cols.get("capacity")) > num(a.get("capacity"));
            support.op(c, "activity_edit", "修改活动", "general", str(cols.get("title")), true, null);
        }
        if ("open".equals(status) && announce)
        {
            onOpened(id);
        }
        if (capacityRaised)
        {
            // 名额调大了：排着队的候补按顺序转正（此前只有退款 / 取消 / 恢复报名会触发递补，调大名额后候补的人会一直空等）
            promote(id);
        }
        // 商家活动刚进入待审核：告诉本城商家服务人（已经在待审核的再改一次不重复提醒）。判空只为手工 new 出来的测试
        if ("pending".equals(status) && !"pending".equals(before) && "merchant".equals(savedSource) && requests != null)
        {
            requests.activityPending(id, c.storeId, str(store.get("storeName")), str(cols.get("title")), savedCity, c.uid());
        }
        Map<String, Object> m = manageView(mustActivity(id));
        // 已有报名的活动保存后沿用原状态（可能是暂停 / 截止），不能说成「已提交平台审核」。
        // 商家的活动由本城商家服务人审（平台也能审，谁先点算谁），对商家只说待审核；别的角色文案不变
        m.put("message", "open".equals(status) ? "已发布，报名中" : ("pending".equals(status) ? (StaffRole.MERCHANT.equals(c.role) ? "已提交，待审核" : "已提交平台审核") : "已保存"));
        return m;
    }

    /** 报名数据：时间段内浏览 / 报名 / 转化 + 渠道来源 + 每日趋势 + 报名结构 */
    public Map<String, Object> data(Map<String, Object> a, String from, String to)
    {
        Long id = KgJdbc.asLong(a.get("activityId"));
        LocalDate t = to != null && KgJdbc.isDate(to) ? LocalDate.parse(to) : LocalDate.now();
        LocalDate f = from != null && KgJdbc.isDate(from) ? LocalDate.parse(from) : t.minusDays(14);
        if (f.isAfter(t) || f.plusDays(92).isBefore(t))
        {
            throw new ServiceException("时间段应在 92 天以内", 422);
        }
        Map<String, Object> ps = p("a", id, "f", f.toString(), "t", t.toString());
        Map<String, Object> tot = db.one("select coalesce(sum(views), 0) v, coalesce(sum(signups), 0) s from kg_activity_stat where activity_id = :a and stat_day between :f and :t", ps);
        LocalDate y = LocalDate.now().minusDays(1);
        Map<String, Object> yes = db.one("select coalesce(sum(views), 0) v, coalesce(sum(signups), 0) s from kg_activity_stat where activity_id = :a and stat_day = :d",
                p("a", id, "d", y.toString()));
        Map<String, Object> tod = db.one("select coalesce(sum(views), 0) v, coalesce(sum(signups), 0) s from kg_activity_stat where activity_id = :a and stat_day = curdate()",
                p("a", id));
        long v = num(tot.get("v"));
        long s = num(tot.get("s"));
        List<Map<String, Object>> stats = new ArrayList<Map<String, Object>>();
        stats.add(statCell("浏览量", String.valueOf(v), delta(num(tod.get("v")), num(yes.get("v")))));
        stats.add(statCell("报名数", String.valueOf(s), delta(num(tod.get("s")), num(yes.get("s")))));
        stats.add(statCell("转化率", v == 0 ? "—" : String.format("%.2f%%", s * 100.0 / v), ""));
        List<Map<String, Object>> channels = new ArrayList<Map<String, Object>>();
        String[] colors = { "#4E8FC4", "#5F6B6A", "#8B8F6A", "#4F7A66", "#8FB5D6", "#C6963B" };
        int i = 0;
        for (Map<String, Object> r : db.list("select channel, sum(views) v from kg_activity_stat where activity_id = :a and stat_day between :f and :t "
                + "group by channel order by v desc", ps))
        {
            long n = num(r.get("v"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", CHANNEL_LABEL.getOrDefault(str(r.get("channel")), "其他"));
            m.put("num", n);
            m.put("pct", v == 0 ? "0%" : Math.round(n * 100.0 / v) + "%");
            m.put("color", colors[i++ % colors.length]);
            channels.add(m);
        }
        Map<String, long[]> byDay = new LinkedHashMap<String, long[]>();
        for (Map<String, Object> r : db.list("select stat_day, sum(views) v, sum(signups) s from kg_activity_stat where activity_id = :a and stat_day between :f and :t "
                + "group by stat_day", ps))
        {
            byDay.put(str(r.get("statDay")).substring(0, 10), new long[] { num(r.get("v")), num(r.get("s")) });
        }
        List<String> dates = new ArrayList<String>();
        List<Long> views = new ArrayList<Long>();
        List<Long> signs = new ArrayList<Long>();
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1))
        {
            long[] x = byDay.getOrDefault(d.toString(), new long[2]);
            dates.add(String.format("%02d-%02d", d.getMonthValue(), d.getDayOfMonth()));
            views.add(x[0]);
            signs.add(x[1]);
        }
        Map<String, Object> cnt = db.one("select sum(status = 'confirmed') c, sum(status = 'waitlist') w, "
                + "sum(status in ('cancelled','refund_pending','refunding','refunded','closed')) x from kg_activity_signup where activity_id = :a", p("a", id));
        long cc = num(cnt.get("c"));
        long ww = num(cnt.get("w"));
        long xx = num(cnt.get("x"));
        long all = Math.max(1, cc + ww + xx);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("activityId", id);
        res.put("title", a.get("title"));
        res.put("range", String.format("%02d-%02d 至 %02d-%02d", f.getMonthValue(), f.getDayOfMonth(), t.getMonthValue(), t.getDayOfMonth()));
        res.put("from", f.toString());
        res.put("to", t.toString());
        res.put("stats", stats);
        res.put("channels", channels);
        res.put("dates", dates);
        res.put("views", views);
        res.put("signs", signs);
        res.put("confirmed", cc);
        res.put("waitlist", ww);
        res.put("cancelled", xx);
        res.put("confirmedPct", String.format("%.1f%%", cc * 100.0 / all));
        res.put("waitlistPct", String.format("%.1f%%", ww * 100.0 / all));
        res.put("cancelledPct", String.format("%.1f%%", xx * 100.0 / all));
        return res;
    }

    private static Map<String, Object> statCell(String label, String value, String delta)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", value);
        m.put("delta", delta);
        return m;
    }

    private static String delta(long today, long yesterday)
    {
        if (yesterday == 0)
        {
            return today == 0 ? "持平" : "+" + today;
        }
        long pct = Math.round((today - yesterday) * 100.0 / yesterday);
        return (pct >= 0 ? "+" : "") + pct + "%";
    }

    // ---------------------------------------------------------------- 后台审核

    @Transactional(rollbackFor = Exception.class)
    public String audit(Long id, boolean pass, String reason, String operator)
    {
        Map<String, Object> a = mustActivity(id);
        if (!"pending".equals(str(a.get("status"))))
        {
            throw new ServiceException("只有待审核的活动可以审核", 409);
        }
        if (!pass && (reason == null || reason.trim().isEmpty()))
        {
            throw new ServiceException("请填写驳回原因", 422);
        }
        if (pass && dt(a.get("startTime")) != null && !new Date().before(dt(a.get("startTime"))))
        {
            // 活动已经开始 / 结束了还通过审核，会给用户发「开始报名」和同城新活动推送，报名的人来了也赶不上。只能驳回
            throw new ServiceException("这个活动的开始时间已过，不能再通过审核，请驳回", 409);
        }
        if (db.update("update kg_activity set status = :to, audit_reason = :r, update_by = :by, update_time = now() where activity_id = :id and status = 'pending'",
                p("to", pass ? "open" : "rejected", "r", pass ? null : reason, "by", operator, "id", id)) == 0)
        {
            // 另一位管理员刚处理过，或定时任务已把过期的待审核活动自动驳回：不能再发「通过 / 驳回」通知和同城新活动推送
            throw new ServiceException("这个活动的状态刚刚变化了，请刷新后再看", 409);
        }
        String title = str(a.get("title"));
        String msgTitle = pass ? "活动已通过审核，开始报名" : "活动未通过审核";
        String content = pass ? title : title + "：" + reason;
        Long storeId = KgJdbc.asLong(a.get("storeId"));
        if (storeId != null)
        {
            support.notifyStore(storeId, "system", msgTitle, content, "activity", id, "/pages/staff/activities?s=4&id=" + id);
        }
        else
        {
            support.notifyUser(KgJdbc.asLong(a.get("appUserId")), "system", msgTitle, content, "activity", id, "/pages/community/activity-mine?id=" + id);
        }
        if (pass)
        {
            onOpened(id);
        }
        return pass ? "已通过" : "已驳回";
    }

    // ---------------------------------------------------------------- 支付回调

    @Override
    public String bizType()
    {
        return BIZ;
    }

    @Override
    public void onPaid(Map<String, Object> payRow)
    {
        Map<String, Object> s = paymentSignup(payRow);
        if (s == null)
        {
            throw new ServiceException("报名记录不存在");
        }
        if (!str(payRow.get("payNo")).equals(str(s.get("payNo"))))
            throw new ServiceException("该支付不属于当前报名，请核对原支付记录",409);
        int n = db.update("update kg_activity_signup set status = 'confirmed', paid_time = now(), expire_time = null, update_time = now() "
                + "where signup_id = :s and pay_no=:pn and status = 'pending_payment'", p("s", s.get("signupId"),"pn",payRow.get("payNo")));
        if (n == 0)
        {
            // 已取消 / 已关闭后才到账：转人工全额退款
            db.update("update kg_activity_signup set status = 'refund_pending', paid_time = now(), refund_fen = fee_fen, "
                    + "cancel_reason = '报名已关闭后才到账（迟到支付），待人工全额退款', update_time = now() where signup_id = :s and status in ('closed','cancelled')",
                    p("s", s.get("signupId")));
            log.warn("活动报名迟到支付 signup={}", s.get("signupNo"));
            return;
        }
        Map<String, Object> a = mustActivity(KgJdbc.asLong(s.get("activityId")));
        afterConfirmed(a, num(s.get("signupId")), KgJdbc.asLong(s.get("appUserId")), str(s.get("name")));
    }

    @Override
    public void onRefunded(Map<String, Object> payRow, long amount)
    {
        Map<String, Object> s = paymentSignup(payRow);
        if (s == null || !str(payRow.get("payNo")).equals(str(s.get("payNo"))))
        {
            // Historical payment keeps its own refund ledger; a reused signup belongs to a later cycle.
            log.info("历史活动支付退款不修改当前报名 pay={} signup={}",payRow.get("payNo"),payRow.get("bizId"));
            return;
        }
        db.update("update kg_activity_signup set status = 'refunded', refund_time = now(), update_time = now() where signup_id = :s and status in ('refunding','refund_pending')",
                p("s", s.get("signupId")));
        // 后台直接对支付单全额退款（补偿 / 线下协商）时报名还是「已确认」：一并转为已退款、让出名额、退群、候补递补，
        // 否则钱退了人还占着位子、还能签到（2026-09-26 回归发现）
        if ("refunded".equals(str(payRow.get("status"))) && "confirmed".equals(str(s.get("status"))))
        {
            Long activityId = KgJdbc.asLong(s.get("activityId"));
            if (db.update("update kg_activity_signup set status = 'refunded', refund_fen = :f, refund_time = now(), cancel_reason = coalesce(cancel_reason, '平台退款'), "
                    + "cancel_time = coalesce(cancel_time, now()), update_time = now() where signup_id = :s and status = 'confirmed'",
                    p("s", s.get("signupId"), "f", num(s.get("refundFen")) + amount)) > 0)
            {
                db.update("update kg_activity set signed = greatest(signed - 1, 0) where activity_id = :id", p("id", activityId));
                chats.leaveActivityGroup(activityId, KgJdbc.asLong(s.get("appUserId")));
                promote(activityId);
            }
        }
        support.notifyUser(KgJdbc.asLong(s.get("appUserId")), "order", "活动报名费已退款", "¥" + yuan(amount) + " 已原路退回，预计 1–5 个工作日到账。", "activity",
                KgJdbc.asLong(s.get("activityId")), "/pages/community/activity?signupId=" + s.get("signupId"));
    }

    // ---------------------------------------------------------------- 定时任务

    private Map<String,Object> paymentSignup(Map<String,Object> payRow)
    {
        try {
            lockActivityForSignup(num(payRow.get("bizId")));
            // Payment is already locked. Signup/cancel may own the reverse order; fail the whole transaction instead of waiting in a cycle.
            return db.one("select * from kg_activity_signup where signup_id=:s for update nowait",p("s",payRow.get("bizId")));
        } catch (org.springframework.dao.CannotAcquireLockException busy) {
            throw new ServiceException("报名正在处理，请刷新后重试",409);
        }
    }

    /** All activity mutations take activity before signup, matching signup/promote.
     * Callbacks may already own payment: NOWAIT releases the entire transaction on a conflicting activity operation.
     */
    private void lockActivityForSignup(long signupId)
    {
        Map<String,Object> identity=db.one("select activity_id from kg_activity_signup where signup_id=:s",p("s",signupId));
        if(identity==null)return;
        try {
            db.one("select activity_id from kg_activity where activity_id=:a for update nowait",p("a",identity.get("activityId")));
        } catch (org.springframework.dao.CannotAcquireLockException busy) {
            throw new ServiceException("活动正在处理，请刷新后重试",409);
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 50000)
    public void tick()
    {
        for (Map<String, Object> s : db.list("select signup_id from kg_activity_signup where status = 'pending_payment' and expire_time < now() limit 100", p()))
        {
            try
            {
                self.closeExpired(num(s.get("signupId")));
            }
            catch (Exception e)
            {
                log.warn("活动报名关单失败 {}：{}", s.get("signupId"), e.getMessage());
            }
        }
        db.update("update kg_activity set status = 'closed', update_time = now() where status = 'open' and deadline is not null and deadline < now()", p());
        db.update("update kg_activity set status = 'ended', update_time = now() where status in ('open','paused','closed') "
                + "and coalesce(end_time, date_add(start_time, interval 4 hour)) < now()", p());
        // 待审核的活动过了开始时间还没人审：自动驳回（原先永远挂在待审核里，后台还能把已经结束的活动审核通过）
        db.update("update kg_activity set status = 'rejected', audit_reason = '超过活动开始时间仍未审核，已自动驳回', update_time = now() "
                + "where status = 'pending' and start_time < now()", p());
        for (Map<String, Object> r : db.list("select g.signup_id, g.app_user_id, g.remind, a.activity_id, a.title, a.place, a.start_time, a.end_time from kg_activity_signup g "
                + "join kg_activity a on a.activity_id = g.activity_id where g.status = 'confirmed' and g.reminded_time is null and g.remind <> 'off' "
                + "and a.status in ('open','paused','closed') and a.start_time > now() and ((g.remind = '2h' and a.start_time <= date_add(now(), interval 2 hour)) "
                + "or (g.remind = '1d20' and now() >= timestamp(date_sub(date(a.start_time), interval 1 day), '20:00:00'))) limit 200", p()))
        {
            if (db.update("update kg_activity_signup set reminded_time = now() where signup_id = :s and reminded_time is null", p("s", r.get("signupId"))) > 0)
            {
                support.notifyUser(KgJdbc.asLong(r.get("appUserId")), "system", "集合提醒", r.get("title") + "，" + rangeText(r.get("startTime"), r.get("endTime"))
                        + "，集合：" + r.get("place") + "。", "activity", KgJdbc.asLong(r.get("activityId")), "/pages/community/activity?signupId=" + r.get("signupId"));
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void closeExpired(long signupId)
    {
        lockActivityForSignup(signupId);
        Map<String, Object> s = db.one("select * from kg_activity_signup where signup_id = :s and status = 'pending_payment' for update", p("s", signupId));
        if (s == null || s.get("expireTime") == null || !dt(s.get("expireTime")).before(new Date())
                || (s.get("payNo") != null && !pay.close(str(s.get("payNo")))))
        {
            return;
        }
        if (db.update("update kg_activity_signup set status = 'closed', update_time = now() where signup_id = :s and status = 'pending_payment'", p("s", signupId)) > 0)
        {
            db.update("update kg_activity set signed = greatest(signed - 1, 0) where activity_id = :a", p("a", s.get("activityId")));
            promote(KgJdbc.asLong(s.get("activityId")));
            // 之前悄悄关掉，报名人不知道名额已经没了
            Map<String, Object> a = db.one("select title from kg_activity where activity_id = :a", p("a", s.get("activityId")));
            support.notifyUser(KgJdbc.asLong(s.get("appUserId")), "order", "活动报名已关闭", "「" + (a == null ? "活动" : str(a.get("title")))
                    + "」的报名因超时未支付已关闭，保留的名额已释放；还想参加可以回到活动页再报一次。", "activity", KgJdbc.asLong(s.get("activityId")),
                    "/pages/community/activity?signupId=" + signupId);
        }
    }
}
