package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.JobImages;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.core.KgReportHandler;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.util.AccountState;

/**
 * 小院评论（2026-10-31 主人拍板，V65）：小伙伴在「小院日记」页写评论，文字 + 图片或视频，一律先审后显示。
 *
 * 和「住过的家人评价」（kg_review）完全分开：不打星，不进综合分、AI 总结、积分。
 * 谁能写：登录并验证手机号，同意《社区公约》。身份不挡人，提交时由服务端算好标在评论上（已核实住客 / 已核实换工 / 未核实），审核的人和读者都看得到。
 * 审核：一律 pending，不看 review-mode，不走机审。命中拦截词 422；命中联系方式、复核词仍进待审，后台标出来。由平台审（KgStayAdminController），待审内容不发给门店。
 * 不依赖 RolePhase：legacy 和 new 下行为相同。
 *
 * 媒体的已知取舍：
 *  · 上传接口不记录上传者，校验不了「文件是不是这个人传的」。能低成本收紧的都收紧了：文件确实存在、图片确实是图片、视频不超过 200MB、
 *    路径里要有 7 天内的日期（正常上传出来的路径都带日期，没有日期的一律不收）、不能用别人评论里用过的文件；
 *    剩下的（比如引用别处的图，别人发在生活碎片里的也算）靠审核的人看图把关，媒体都直接显示在审核列表里。
 *  · 视频时长服务端量不了，只有上传登记时客户端报的数（60 秒，KgVideoUploads）。不带时长也能登记（住宿评价等入口共用这条路），kind=intro 能报到 3 分钟，普通账号也能用；
 *    这两种都只剩 200MB 这一道能挡，所以审核确认文案提醒看时长。
 *  · 待审和驳回的文件在 /profile/ 下其实可以直接访问（路径不可猜），「不展示」管的是列表和接口，不是文件本身。
 *  · 同一微信注销后再登录是新账号，限额从 0 算，但要重新验证手机号。限额按账号算，多手机号多账号刷量靠审核把关。
 *
 * 站内信的取舍：待审内容不发给任何人。评论下架、作者删除时 withdrawNotices 把之前的通知收回，作者注销时 KgAccountService 同样抹掉小院主理人那条，所以不再公开的原文只留在评论表里。
 * 驳回、下架通知只带小院名和理由，不引用评论原文：带 kangaroo:message:list 的后台账号（没有审核菜单也行）读得到消息正文，理由他们读得到，这是已知情况，给运营授权时要知道。
 */
@Service
public class KgStayCommentService implements KgReportHandler
{
    /** 举报对象类型、站内信 biz_type */
    public static final String TYPE = "stay_comment";

    static final int TEXT_MAX = 500;
    static final int IMAGES_MAX = 9;
    /** kg_stay_comment.images 列宽 */
    static final int IMAGES_LEN = 1000;
    /** 每人每院最多几条待审 */
    static final int PENDING_MAX = 3;
    /** 每人每小时最多几条（本人删掉的也算，不能删了重发来绕） */
    static final int HOUR_MAX = 10;
    static final int PAGE_SIZE = 20;
    /** 列表最上面最多放几条本人还没公开的 */
    static final int MINE_MAX = 20;
    /** 图片、视频路径里的日期要在这么多天以内 */
    static final int FRESH_DAYS = 7;

    static final String GUEST = "guest";
    static final String VOLUNTEER = "volunteer";
    static final String UNVERIFIED = "unverified";

    private static final Map<String, String> IDENTITY_TEXT = new LinkedHashMap<String, String>();
    private static final Map<String, String> STATUS_TEXT = new LinkedHashMap<String, String>();
    static
    {
        IDENTITY_TEXT.put(GUEST, "已核实住客");
        IDENTITY_TEXT.put(VOLUNTEER, "已核实换工");
        IDENTITY_TEXT.put(UNVERIFIED, "未核实");
        STATUS_TEXT.put("online", "已通过");
        STATUS_TEXT.put("pending", "审核中");
        STATUS_TEXT.put("rejected", "未通过");
        STATUS_TEXT.put("offline", "已下架");
    }

    private static final Pattern PATH_DATE = Pattern.compile("/(\\d{4})/(\\d{2})/(\\d{2})/");
    private static final String WITHDRAWN_DELETED = "这条评论已删除";
    private static final String WITHDRAWN_OFFLINE = "这条评论已下架";

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private KgVideoUploads videos;

    private static String str(Object o)
    {
        return o == null ? "" : String.valueOf(o);
    }

    /** 通知一律跳回这一页，不跳新页面：老版本小程序也打得开，只是不认 tab */
    static String route(Object stayId)
    {
        return "/pages/stays/diary?stayId=" + stayId + "&tab=comment";
    }

    /** 小院要在架（与详情页同一口径），否则 404 */
    private Map<String, Object> requireStay(Long stayId)
    {
        Map<String, Object> s = stayId == null ? null : db.one("select s.stay_id, s.stay_name, s.store_id from kg_stay s where s.stay_id = :id and " + KgStayBrowseService.ONLINE, p("id", stayId));
        if (s == null)
        {
            throw new ServiceException("房源不存在或已下架", 404);
        }
        return s;
    }

    // ---------------------------------------------------------------- 身份

    /** 现在提交会算成什么：{source_type, source_id}。住客优先于换工 */
    Object[] identity(Long stayId, Long storeId, Long me)
    {
        Map<String, Object> u = db.one("select phone from kg_app_user where user_id = :u and phone_verified_time is not null and phone is not null", p("u", me));
        String phone = u == null ? "" : str(u.get("phone"));
        Map<String, Object> order = db.one("select o.order_id from kg_stay_order o where o.stay_id = :s and o.del_flag = '0' and o.status in ('checked_in','completed') "
                + "and (o.app_user_id = :u or (:ph <> '' and o.guest_phone = :ph)) order by o.order_id desc limit 1", p("s", stayId, "u", me, "ph", phone));
        if (order != null)
        {
            return new Object[] { GUEST, KgJdbc.asLong(order.get("orderId")) };
        }
        Map<String, Object> app = db.one("select a.app_id from kg_application a join kg_job j on j.job_id = a.job_id "
                + "where a.app_user_id = :u and a.del_flag = '0' and a.status in ('onboarded','completed') and j.job_type <> 'online' "
                + "and coalesce(j.recruit_store_id, j.store_id) = :st order by a.app_id desc limit 1", p("u", me, "st", storeId));
        if (app != null)
        {
            return new Object[] { VOLUNTEER, KgJdbc.asLong(app.get("appId")) };
        }
        return new Object[] { UNVERIFIED, null };
    }

    static String identityText(Object type)
    {
        String t = IDENTITY_TEXT.get(str(type));
        return t == null ? IDENTITY_TEXT.get(UNVERIFIED) : t;
    }

    /** 别人看到的昵称：首字 + **（按字符算，表情不能切成两半） */
    static String maskName(String nick)
    {
        String n = nick == null ? "" : nick.trim();
        if (n.isEmpty())
        {
            return "小伙伴";
        }
        return new String(Character.toChars(n.codePointAt(0))) + "**";
    }

    // ---------------------------------------------------------------- 媒体校验

    /** 图片：认数组，也认逗号分隔；空的跳过，重复的只留一张；别处的网址、视频路径一律 422，不悄悄丢掉 */
    static List<String> parseImages(Object raw)
    {
        if (raw == null)
        {
            return Collections.emptyList();
        }
        List<Object> items = new ArrayList<Object>();
        if (raw instanceof List)
        {
            items.addAll((List<?>) raw);
        }
        else if (raw instanceof String)
        {
            items.addAll(Arrays.asList(((String) raw).split(",")));
        }
        else
        {
            throw new ServiceException("图片格式不对，请重新上传", 422);
        }
        List<String> out = new ArrayList<String>();
        for (Object o : items)
        {
            String u = str(o).trim();
            if (u.isEmpty())
            {
                continue;
            }
            if (!JobImages.isUpload(u) || KgVideoUploads.isPlatformVideo(u))
            {
                throw new ServiceException("图片只能用本平台上传的图片", 422);
            }
            if (!out.contains(u))
            {
                out.add(u);
            }
        }
        if (out.size() > IMAGES_MAX)
        {
            throw new ServiceException("最多附 " + IMAGES_MAX + " 张图片", 422);
        }
        if (String.join(",", out).length() > IMAGES_LEN)
        {
            throw new ServiceException("图片太多了，请删掉几张再提交", 422);
        }
        return out;
    }

    /** 视频：body.videos = [{url, cover}] 或 [url]，和住宿评价一样的写法，最多 1 段。返回 {url, cover}；没带返回 null */
    String[] parseVideo(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        List<Object> items = new ArrayList<Object>();
        if (raw instanceof List)
        {
            items.addAll((List<?>) raw);
        }
        else if (raw instanceof String)
        {
            items.addAll(Arrays.asList(((String) raw).split(",")));
        }
        else
        {
            throw new ServiceException("视频格式不对，请重新上传", 422);
        }
        String[] video = null;
        for (Object o : items)
        {
            String url = o instanceof Map ? str(((Map<?, ?>) o).get("url")).trim() : str(o).trim();
            String cover = o instanceof Map ? str(((Map<?, ?>) o).get("cover")).trim() : "";
            if (url.isEmpty())
            {
                continue;
            }
            if (video != null && !video[0].equals(url))
            {
                throw new ServiceException("最多附 1 段视频", 422);
            }
            if (!KgVideoUploads.isPlatformVideo(url) || !videos.exists(url))
            {
                throw new ServiceException("视频只能用本平台上传的视频，请重新上传", 422);
            }
            if (videos.sizeOf(url) > KgVideoUploads.MAX_BYTES)
            {
                throw new ServiceException(KgVideoUploads.TOO_BIG, 422);
            }
            if (!cover.isEmpty() && (!JobImages.isUpload(cover) || KgVideoUploads.isPlatformVideo(cover)))
            {
                throw new ServiceException("视频封面只能用本平台上传的图片", 422);
            }
            video = new String[] { url, cover };
        }
        return video;
    }

    /** 路径里的日期（/profile/upload/yyyy/MM/dd/…）要在 FRESH_DAYS 天以内。正常上传出来的路径都带日期，没有日期或日期不成立的一律不收 */
    static boolean fresh(String path, LocalDate today)
    {
        Matcher m = PATH_DATE.matcher(path);
        if (!m.find())
        {
            return false;
        }
        try
        {
            return !LocalDate.parse(m.group(1) + "-" + m.group(2) + "-" + m.group(3)).isBefore(today.minusDays(FRESH_DAYS));
        }
        catch (DateTimeParseException e)
        {
            return false;
        }
    }

    /** 图片文件要确实存在、确实是图片（JobImages 的提示写的是「岗位照片」，这里换成评论的说法） */
    private static void requireImageFiles(List<String> images)
    {
        if (images.isEmpty())
        {
            return;
        }
        try
        {
            JobImages.require(images);
        }
        catch (ServiceException e)
        {
            throw new ServiceException("图片找不到或已损坏，请重新上传", 422);
        }
    }

    /** 不能用别人评论里用过的文件（包括别人删掉的）。上传接口不记录上传者，这是校验归属能做到的最低成本的一步 */
    private void requireNotOthers(Long me, List<String> paths)
    {
        if (paths.isEmpty())
        {
            return;
        }
        StringBuilder or = new StringBuilder();
        Map<String, Object> ps = p("u", me);
        for (int i = 0; i < paths.size(); i++)
        {
            or.append(i == 0 ? "" : " or ").append("find_in_set(:m").append(i).append(", images) > 0 or video_url = :m").append(i).append(" or video_cover = :m").append(i);
            ps.put("m" + i, paths.get(i));
        }
        if (db.count("select count(*) from kg_stay_comment where app_user_id <> :u and (" + or + ")", ps) > 0)
        {
            throw new ServiceException("图片或视频已被别人使用，请换一个重新上传", 422);
        }
    }

    // ---------------------------------------------------------------- 文字

    /**
     * 有没有看得见的字。String.trim() 只去掉 U+0020 及以下，全角空格、零宽字符、不换行空格、单独的表情变体符都会被当成有内容。
     * 只用来判空，落库内容不动。不把「未分配」算成看不见：JDK 的 Unicode 表比手机旧，新表情在这里就是未分配，不能拒。
     */
    static boolean hasVisibleText(String s)
    {
        return s != null && s.codePoints().anyMatch(KgStayCommentService::visible);
    }

    private static boolean visible(int c)
    {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c))
        {
            return false;
        }
        switch (Character.getType(c))
        {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.SURROGATE:
            // 变体符（U+FE0F、U+E0100…）属于这两类。单独的组合符号没有字可以附着，也不算内容
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
                return false;
            default:
                // 归在字母、符号类里其实是空白的：盲文空格、韩文填充符
                return c != 0x2800 && c != 0x3164 && c != 0xFFA0 && c != 0x115F && c != 0x1160;
        }
    }

    // ---------------------------------------------------------------- 小程序

    /** 写评论前的提示。identity 只用来提示，真正落库以提交时为准 */
    public Map<String, Object> me(Long stayId, Long me)
    {
        Map<String, Object> s = requireStay(stayId);
        Object[] who = identity(stayId, KgJdbc.asLong(s.get("storeId")), me);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("phoneVerified", guard.phoneVerified(me));
        m.put("consented", guard.consented(me, "community"));
        m.put("identity", who[0]);
        m.put("identityText", identityText(who[0]));
        m.put("pending", pendingCount(stayId, me));
        m.put("pendingMax", PENDING_MAX);
        return m;
    }

    private long pendingCount(Long stayId, Long me)
    {
        return db.count("select count(*) from kg_stay_comment where app_user_id = :u and stay_id = :s and status = 'pending' and del_flag = '0'", p("u", me, "s", stayId));
    }

    /** 提交：一律 pending。锁住账号行再查限额，同一个人并发提交也超不了 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Long stayId, Long me, Map<String, Object> body)
    {
        AccountState.requireActive(db, me);
        Map<String, Object> s = requireStay(stayId);
        guard.requirePhone(me);
        guard.requireConsent(me, "community");
        Map<String, Object> b = body == null ? Collections.<String, Object>emptyMap() : body;
        String text = str(b.get("content")).trim();
        if (!hasVisibleText(text) || text.length() > TEXT_MAX)
        {
            throw new ServiceException("评论为 1–" + TEXT_MAX + " 字", 422);
        }
        // 命中拦截词这里直接 422。不看 review-mode：initialStatus、autoApprove 都不用，mock 和 auto 下也是待审
        String level = guard.check(text);
        String risk = "pass".equals(level) ? "none" : (guard.hasContact(text) ? "contact" : "word");
        List<String> images = parseImages(b.get("images"));
        String[] video = parseVideo(b.get("videos"));
        if (!images.isEmpty() && video != null)
        {
            throw new ServiceException("图片和视频只能选一种", 422);
        }
        List<String> media = new ArrayList<String>(images);
        if (video != null)
        {
            media.add(video[0]);
            if (!video[1].isEmpty())
            {
                media.add(video[1]);
            }
        }
        boolean confirmed = "true".equalsIgnoreCase(str(b.get("portraitConfirmed")));
        if (!media.isEmpty() && !confirmed)
        {
            throw new ServiceException("带图片或视频时，请先确认没有侵犯他人肖像", 422);
        }
        LocalDate today = LocalDate.now();
        for (String path : media)
        {
            if (!fresh(path, today))
            {
                throw new ServiceException("图片或视频上传太久了，请重新上传", 422);
            }
        }
        requireImageFiles(images);
        if (video != null && !video[1].isEmpty())
        {
            requireImageFiles(Collections.singletonList(video[1]));
        }
        requireNotOthers(me, media);
        if (pendingCount(stayId, me) >= PENDING_MAX)
        {
            throw new ServiceException("你在这家小院已有 " + PENDING_MAX + " 条评论在审核中，等审核完再写吧", 429);
        }
        Date now = new Date();
        if (db.count("select count(*) from kg_stay_comment where app_user_id = :u and create_time > :since", p("u", me, "since", new Date(now.getTime() - 3600000L))) >= HOUR_MAX)
        {
            throw new ServiceException("写得太频繁了，一小时最多 " + HOUR_MAX + " 条，请稍后再试", 429);
        }
        Long storeId = KgJdbc.asLong(s.get("storeId"));
        Object[] who = identity(stayId, storeId, me);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("stay_id", stayId);
        row.put("stay_name", str(s.get("stayName")));
        row.put("store_id", storeId);
        row.put("app_user_id", me);
        row.put("source_type", who[0]);
        row.put("source_id", who[1]);
        row.put("content", text);
        row.put("images", String.join(",", images));
        row.put("video_url", video == null ? "" : video[0]);
        row.put("video_cover", video == null ? "" : video[1]);
        row.put("portrait_confirmed", media.isEmpty() ? "0" : "1");
        row.put("risk_flag", risk);
        row.put("status", "pending");
        row.put("create_by", "app:" + me);
        row.put("create_time", now);
        long id = db.insert("kg_stay_comment", row);
        return card(db.one(SELECT + " where c.comment_id = :id", p("id", id)), me);
    }

    private static final String SELECT = "select c.*, u.nickname, u.avatar from kg_stay_comment c left join kg_app_user u on u.user_id = c.app_user_id";

    /** 列表：rows 只有已通过的（游客也能看）；mine 是本人还没公开的，只在第一页给 */
    public Map<String, Object> list(Long stayId, int pageNum, Long me)
    {
        requireStay(stayId);
        if (pageNum < 1 || pageNum > 1000)
        {
            throw new ServiceException("页码不对", 422);
        }
        String blocks = me == null ? "" : " and not exists (select 1 from kg_user_block b where (b.user_id = :me and b.blocked_id = c.app_user_id) or (b.user_id = c.app_user_id and b.blocked_id = :me))";
        Map<String, Object> page = db.page(SELECT + " where c.stay_id = :s and c.status = 'online' and c.del_flag = '0'" + blocks + " order by c.comment_id desc",
                p("s", stayId, "me", me), pageNum, PAGE_SIZE);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = (List<Map<String, Object>>) page.get("rows");
        for (Map<String, Object> r : raw)
        {
            rows.add(card(r, me));
        }
        List<Map<String, Object>> mine = new ArrayList<Map<String, Object>>();
        if (me != null && pageNum == 1)
        {
            for (Map<String, Object> r : db.list(SELECT + " where c.stay_id = :s and c.app_user_id = :me and c.status <> 'online' and c.del_flag = '0' order by c.comment_id desc limit " + MINE_MAX,
                    p("s", stayId, "me", me)))
            {
                mine.add(card(r, me));
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("stayId", stayId);
        out.put("rows", rows);
        out.put("mine", mine);
        out.put("total", page.get("total"));
        out.put("hasMore", page.get("hasMore"));
        out.put("pageNum", pageNum);
        return out;
    }

    /** 给小程序的卡片。理由、未脱敏的昵称只给本人；举报次数、风险标记、身份依据只给后台 */
    Map<String, Object> card(Map<String, Object> r, Long me)
    {
        boolean isMe = me != null && me.equals(KgJdbc.asLong(r.get("appUserId")));
        boolean anon = "1".equals(str(r.get("anonymous")));
        String status = str(r.get("status"));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("commentId", KgJdbc.asLong(r.get("commentId")));
        m.put("name", anon ? "已注销用户" : (isMe ? (str(r.get("nickname")).trim().isEmpty() ? "我" : str(r.get("nickname"))) : maskName(str(r.get("nickname")))));
        m.put("avatar", anon ? "" : str(r.get("avatar")));
        m.put("identity", str(r.get("sourceType")));
        m.put("identityText", identityText(r.get("sourceType")));
        m.put("content", str(r.get("content")));
        String images = str(r.get("images"));
        m.put("images", images.isEmpty() ? Collections.emptyList() : Arrays.asList(images.split(",")));
        String video = str(r.get("videoUrl"));
        if (video.isEmpty())
        {
            m.put("video", null);
        }
        else
        {
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("url", video);
            v.put("cover", str(r.get("videoCover")));
            m.put("video", v);
        }
        m.put("dateText", KgStayReviewService.ymdDot(r.get("createTime")));
        m.put("status", status);
        m.put("statusText", STATUS_TEXT.containsKey(status) ? STATUS_TEXT.get(status) : "");
        m.put("auditReason", isMe && !"online".equals(status) ? str(r.get("auditReason")) : "");
        m.put("isMe", isMe);
        m.put("canReport", me != null && !isMe && "online".equals(status));
        return m;
    }

    /** 本人删除，任何状态都能删（没通过的删掉重写）。删了就不再展示 */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long commentId, Long me)
    {
        AccountState.requireActive(db, me);
        if (db.update("update kg_stay_comment set del_flag = '2', update_by = :by, update_time = now() where comment_id = :id and app_user_id = :u and del_flag = '0'",
                p("id", commentId, "u", me, "by", "app:" + me)) == 0)
        {
            throw new ServiceException("评论不存在或已删除", 404);
        }
        withdrawNotices(commentId, WITHDRAWN_DELETED);
    }

    /** 举报：只能举报别人已公开的；同一人对同一条只记一次（kg_report 的唯一索引） */
    @Transactional(rollbackFor = Exception.class)
    public String report(Long commentId, Long me, String reason, String detail)
    {
        AccountState.requireActive(db, me);
        Map<String, Object> c = db.one("select app_user_id from kg_stay_comment where comment_id = :id and status = 'online' and del_flag = '0'", p("id", commentId));
        if (c == null)
        {
            throw new ServiceException("评论不存在或已下架", 404);
        }
        if (me.equals(KgJdbc.asLong(c.get("appUserId"))))
        {
            throw new ServiceException("不能举报自己的评论", 422);
        }
        String rs = StaffSupport.cut(reason == null || reason.trim().isEmpty() ? "不当评论" : reason.trim(), 64);
        int n = db.update("insert ignore into kg_report (target_type, target_id, reporter_id, reason, detail, status, create_by, create_time) "
                + "values (:t, :id, :u, :r, :d, 'pending', :by, now())",
                p("t", TYPE, "id", commentId, "u", me, "r", rs, "d", StaffSupport.cut(detail, 500), "by", "app:" + me));
        if (n > 0)
        {
            db.update("update kg_stay_comment set reported = reported + 1 where comment_id = :id", p("id", commentId));
            return "举报已提交，平台会在 24 小时内处理";
        }
        return "你已经举报过了，平台正在处理";
    }

    // ---------------------------------------------------------------- 后台审核

    private Map<String, Object> locked(Long id)
    {
        return db.must("select * from kg_stay_comment where comment_id = :id and del_flag = '0' for update", p("id", id), "评论不存在或已被作者删除");
    }

    private void move(Long id, String from, String to, String reason, String operator)
    {
        if (db.update("update kg_stay_comment set status = :to, audit_reason = :r, audit_by = :op, audit_time = now(), update_by = :op, update_time = now() "
                + "where comment_id = :id and status = :from and del_flag = '0'",
                p("id", id, "to", to, "from", from, "r", reason == null ? null : StaffSupport.cut(reason, 255), "op", operator)) == 0)
        {
            throw new ServiceException("评论状态已变化，请刷新后重试", 409);
        }
    }

    private static String place(Map<String, Object> c)
    {
        return "「" + str(c.get("stayName")) + "」";
    }

    /** 引用原文只用在评论公开的时候（通过）。公开之后下架、删除、注销，withdrawNotices 和注销语句会把它收回 */
    private static String quote(Map<String, Object> c)
    {
        return place(c) + StaffSupport.cut(str(c.get("content")), 40);
    }

    /**
     * 评论不再公开时，把之前发出去的站内信收回，原文不能留在别人读得到的地方。
     * 小院主理人那条：抹掉再软删（写法和注销抹别人收到的通知一样），消息列表、红点、数据导出、后台消息管理都不再有。
     * 作者自己收到的：留着记录，只把正文换掉（「已通过」那条引用了原文）。
     */
    private void withdrawNotices(Long id, String text)
    {
        db.update("update kg_message set title = '小院评论已撤回', content = :c, route = null, del_flag = '2' where biz_type = :t and biz_id = :id and mode = 'staff'",
                p("c", text, "t", TYPE, "id", id));
        db.update("update kg_message set content = :c where biz_type = :t and biz_id = :id and mode = 'user'", p("c", text, "t", TYPE, "id", id));
    }

    private static String need(String reason, String what)
    {
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty())
        {
            throw new ServiceException("请填写" + what, 422);
        }
        return r;
    }

    /** 通过：公开显示，通知本人和这家小院的小院主理人（只发 keeper，绝不用 notifyStore 群发） */
    @Transactional(rollbackFor = Exception.class)
    public String approve(Long id, String operator)
    {
        Map<String, Object> c = locked(id);
        if (!"pending".equals(str(c.get("status"))))
        {
            throw new ServiceException("只有待审核的评论可以通过", 409);
        }
        move(id, "pending", "online", null, operator);
        Long author = KgJdbc.asLong(c.get("appUserId"));
        String route = route(c.get("stayId"));
        support.notifyUser(author, MessageCategory.SYSTEM, "你的小院评论已通过审核", quote(c), TYPE, id, route);
        support.notifyKeepers(KgJdbc.asLong(c.get("storeId")), author, MessageCategory.SYSTEM, "小院有新评论",
                identityText(c.get("sourceType")) + "：" + quote(c), TYPE, id, route);
        // 合作商家店（可能没有小院主理人）：本店合作商家也要收到，同一句话；本人同时是小院主理人的上面已经发过，不重复
        for (Map<String, Object> m : db.list("select distinct g.app_user_id from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.store_id = :s and g.role_code = 'merchant' and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0' "
                + "and not exists (select 1 from kg_staff_grant k where k.store_id = g.store_id and k.app_user_id = g.app_user_id and k.role_code = 'keeper' "
                + "and k.status = '0' and k.del_flag = '0')", p("s", c.get("storeId"))))
        {
            Long uid = KgJdbc.asLong(m.get("appUserId"));
            if (uid != null && !uid.equals(author))
            {
                support.notifyStaff(uid, MessageCategory.SYSTEM, "小院有新评论", identityText(c.get("sourceType")) + "：" + quote(c), TYPE, id, route);
            }
        }
        return "已通过";
    }

    /** 驳回：理由必填，只通知本人。不引用评论原文（见类注释） */
    @Transactional(rollbackFor = Exception.class)
    public String reject(Long id, String reason, String operator)
    {
        String why = need(reason, "驳回理由");
        Map<String, Object> c = locked(id);
        if (!"pending".equals(str(c.get("status"))))
        {
            throw new ServiceException("只有待审核的评论可以驳回", 409);
        }
        move(id, "pending", "rejected", why, operator);
        support.notifyUser(KgJdbc.asLong(c.get("appUserId")), MessageCategory.SYSTEM, "你的小院评论未通过审核", place(c) + "：" + why, TYPE, id, route(c.get("stayId")));
        return "已驳回";
    }

    /** 下架：理由必填，只通知本人，并收回通过时发的通知 */
    @Transactional(rollbackFor = Exception.class)
    public String offline(Long id, String reason, String operator)
    {
        String why = need(reason, "下架理由");
        Map<String, Object> c = locked(id);
        if (!"online".equals(str(c.get("status"))))
        {
            throw new ServiceException("只有已通过的评论可以下架", 409);
        }
        move(id, "online", "offline", why, operator);
        withdrawNotices(id, WITHDRAWN_OFFLINE);
        support.notifyUser(KgJdbc.asLong(c.get("appUserId")), MessageCategory.SYSTEM, "你的小院评论已被下架", place(c) + "：" + why, TYPE, id, route(c.get("stayId")));
        return "已下架";
    }

    // ---------------------------------------------------------------- 举报成立

    @Override
    public String targetType()
    {
        return TYPE;
    }

    @Override
    public String uphold(long targetId, String operator)
    {
        Map<String, Object> c = db.one("select * from kg_stay_comment where comment_id = :id and del_flag = '0' for update", p("id", targetId));
        if (c == null)
        {
            return "评论不存在或已被作者删除";
        }
        if (!"online".equals(str(c.get("status"))))
        {
            return "评论已经不在展示了";
        }
        offline(targetId, "举报成立，平台下架", operator);
        return "评论已下架";
    }
}
