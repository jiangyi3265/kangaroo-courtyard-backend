package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgReportHandler;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.WxMiniAppClient;
import com.ruoyi.kangaroo.util.AccountState;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/**
 * 私信与群聊：用户之间的私信（同行匹配 / 帖子 / 找搭子进入）、报名人联系门店、活动群。
 * 轮询取消息（前端 3 秒一次，页面隐藏即停）；拉黑后互不可发。
 * 用户之间的私信（2026-10-11 内测建议）：之前对方回复前最多发 3 条，谁都能私信任何人；现在互相关注的两人不限条数，
 * 其他情况对方回复前只能先发 1 条；对方在「谁可以私信我」里只允许关注的人 / 互相关注的人时，不满足条件的发不起私信（403）。
 * 联系门店、活动群不受这些限制。
 * 站内信「私信 / 群聊」分类就是会话列表（2026-10-15 起）：每人每个身份下每个会话只占一行，发消息的人自己也有一行（已读的「我：…」），
 * 有新消息时更新这一行、改回未读、排到最前。之前只给对方写提醒、读掉后再来消息另插一行：门店员工「联系接单人」发出后自己那边什么都没有，
 * 同一会话在列表里越积越多。
 * 私信里的手机号 / 微信号 / QQ 号：双方都点「交换联系方式」之前，对方看到的是「已隐藏」。
 * 2026-10-16（主人真机反馈，V46）：每条文字最多 2000 字（之前 500 字；私信、联系门店、活动群一样）；图片最多 10MB（之前 5MB）；
 * 门店和报名人 / 接单人的会话里可以发文件（zip / rar / 7z、PDF、Word、Excel、PPT，单个 10MB，一条消息一个，见 KgChatFiles），
 * 用户之间的私信、活动群不能发文件。专员发布的线上任务，发布的专员一定是门店这一侧的席位（见 KgChatReception.publishers）。
 */
@Service
public class KgChatService implements KgReportHandler
{
    // 之前是 3；现在对方回复前只能先发 1 条（互相关注的不受限）
    static final int GREET_LIMIT = 1;
    /** 每条文字最多几个字（2026-10-16 起 2000；之前 500）。群公告仍是 500 字（announce） */
    static final int TEXT_MAX = 2000;
    static final Map<String, String> SOURCE_LABEL = new LinkedHashMap<String, String>();
    static
    {
        SOURCE_LABEL.put("match", "同行匹配");
        SOURCE_LABEL.put("post", "社区帖子");
        SOURCE_LABEL.put("buddy", "找搭子");
        SOURCE_LABEL.put("progress", "报名进度");
        SOURCE_LABEL.put("online_task", "线上任务");
        SOURCE_LABEL.put("activity", "活动");
        SOURCE_LABEL.put("profile", "个人主页");
    }

    // 私信里的联系方式（2026-10-11 起）：之前手机号、微信号可以直接发给陌生人，容易招来骚扰和诈骗；现在双方都点了
    // 「交换联系方式」之前，对方发来的手机号 / 微信号 / QQ 号显示成「已隐藏」。只在读的时候遮，库里原文不动，
    // 双方同意后历史消息也一起显示出来。只管用户之间的私信，联系门店、活动群不受影响。识别规则见 ContactMask。
    static String maskContacts(String text)
    {
        return ContactMask.mask(text);
    }

    @Autowired
    private KgJdbc db;

    @Autowired
    private WxMiniAppClient wxMiniApp;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired
    private IKgStaffGrantService grants;

    @Autowired private KgChatReception reception;

    @Autowired
    private KgChatFiles files;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    /** 会话预览（消息中心那一行、会话「最近一条」）只有一行：换行和连续空白并成一个空格（消息本身存原文、照原样分行显示） */
    static String oneLine(String s)
    {
        return s == null ? "" : s.replaceAll("[\\s\\u3000]+", " ").trim();
    }

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    // ---------------------------------------------------------------- 建会话

    private long upsertMember(long convId, Long uid, String role)
    {
        return db.update("insert into kg_chat_member (conv_id, app_user_id, role, join_time) values (:c, :u, :r, now()) "
                + "on duplicate key update left_time = null, role = if(role = 'owner', role, :r)", p("c", convId, "u", uid, "r", role));
    }

    @Transactional(rollbackFor = Exception.class)
    public long ensureActivityGroup(long activityId)
    {
        if(!KgCommunityFeature.isEnabled(db,KgCommunityFeature.GROUP_KEY)) return 0L;
        Map<String, Object> a = db.one("select activity_id, title, conv_id, app_user_id, city from kg_activity where activity_id = :a for update", p("a", activityId));
        if (a == null)
        {
            throw new ServiceException("活动不存在", 404);
        }
        if (a.get("convId") != null)
        {
            return num(a.get("convId"));
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("conv_type", "group");
        row.put("activity_id", activityId);
        row.put("source", "activity");
        row.put("source_id", activityId);
        row.put("title", StaffSupport.cut(a.get("title") + " 活动群", 64));
        row.put("announcement", "欢迎来到「" + a.get("title") + "」活动群！尊重包容、安全第一，文明交流。集合信息以活动页为准，请勿在群里发布或索要私人联系方式。");
        row.put("status", "active");
        row.put("create_time", new Date());
        long convId = db.insert("kg_chat_conv", row);
        db.update("update kg_activity set conv_id = :c where activity_id = :a", p("c", convId, "a", activityId));
        if (a.get("appUserId") != null)
        {
            upsertMember(convId, KgJdbc.asLong(a.get("appUserId")), "owner");
        }
        for (Map<String, Object> s : db.list("select app_user_id from kg_activity_signup where activity_id = :a and status = 'confirmed'", p("a", activityId)))
        {
            upsertMember(convId, KgJdbc.asLong(s.get("appUserId")), "member");
        }
        return convId;
    }

    public void joinActivityGroup(long activityId, Long uid)
    {
        long convId = ensureActivityGroup(activityId);
        if(convId>0) upsertMember(convId, uid, "member");
    }

    public void leaveActivityGroup(long activityId, Long uid)
    {
        db.update("update kg_chat_member m join kg_activity a on a.conv_id = m.conv_id set m.left_time = now() "
                + "where a.activity_id = :a and m.app_user_id = :u and m.role <> 'owner'", p("a", activityId, "u", uid));
    }

    public void systemMessage(long activityId, String text)
    {
        if(!KgCommunityFeature.isEnabled(db,KgCommunityFeature.GROUP_KEY)) return;
        Map<String, Object> a = db.one("select conv_id from kg_activity where activity_id = :a", p("a", activityId));
        if (a != null && a.get("convId") != null)
        {
            insertMsg(num(a.get("convId")), 0L, "system", StaffSupport.cut(text, 1000));
        }
    }

    // Pair creation takes account locks in the same stable order in both directions.
    // Never acquire another participant's account after a conversation/member lock.
    private void lockActiveParticipants(Long first, Long second, boolean privateTarget)
    {
        if (first == null || second == null) throw new ServiceException("账号已注销或不可用", 409);
        Long[] users = first.equals(second) ? new Long[] { first }
                : new Long[] { Math.min(first, second), Math.max(first, second) };
        for (Long userId : users)
        {
            try { AccountState.requireActive(db, userId); }
            catch (ServiceException e)
            {
                // Preserve the private recipient's existing non-disclosing 404;
                // the authenticated caller's inactive-account contract stays 409.
                if (privateTarget && !first.equals(userId) && Integer.valueOf(409).equals(e.getCode()))
                    throw new ServiceException("对方账号不存在", 404);
                throw e;
            }
        }
    }

    /**
     * 找搭子帖「发起认识一下」的同行性别：之前谁都能点；现在发帖人限定了仅限女生 / 仅限男生时，
     * 发起人资料里的性别（kg_app_user.gender：1 男 / 2 女）要对得上，不符 403；没填性别 422，提示先去个人资料填写。
     * 发帖人自己不受限；没限定（''）或不是找搭子帖不检查。只读不加锁，不影响下面的加锁顺序。
     */
    void requireBuddyGender(Long me, String source, Long postId)
    {
        if (!"buddy".equals(source) || postId == null)
        {
            return;
        }
        Map<String, Object> post = db.one("select app_user_id, kind, gender_limit from kg_post where post_id = :p", p("p", postId));
        String limit = post == null || !"buddy".equals(str(post.get("kind"))) ? "" : str(post.get("genderLimit"));
        if (!"female".equals(limit) && !"male".equals(limit))
        {
            return;
        }
        if (me.equals(KgJdbc.asLong(post.get("appUserId"))))
        {
            return;
        }
        Map<String, Object> u = db.one("select gender from kg_app_user where user_id = :u", p("u", me));
        String gender = u == null ? "" : str(u.get("gender"));
        String who = "female".equals(limit) ? "女生" : "男生";
        if (!"1".equals(gender) && !"2".equals(gender))
        {
            throw new ServiceException("这条找搭子仅限" + who + "参加，请先在「我的 → 个人资料」里填写性别", 422);
        }
        if (!gender.equals("female".equals(limit) ? "2" : "1"))
        {
            throw new ServiceException("这条找搭子仅限" + who + "参加", 403);
        }
    }

    /** 私信：同一对用户只有一个会话 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> openPrivate(Long me, Long other, String source, Long sourceId)
    {
        KgCommunityFeature.requirePrivateChat(db);
        if (other == null || other.equals(me))
        {
            throw new ServiceException("不能给自己发私信", 422);
        }
        lockActiveParticipants(me, other, true);
        // 先看性别要求再弹规则：对不上的人不用先同意一遍《社区公约》才被告知参加不了
        requireBuddyGender(me, source, sourceId);
        guard.requireConsent(me, "community");
        guard.requireNotBlocked(me, other);
        String key = Math.min(me, other) + "-" + Math.max(me, other);
        Map<String, Object> c = db.one("select conv_id from kg_chat_conv where pair_key = :k", p("k", key));
        // 对方的「谁可以私信我」：对方已经在这个会话里给我发过消息的（聊过天）不再拦，免得改了设置后老朋友的会话打不开
        boolean talked = c != null && db.count("select count(*) from kg_chat_msg where conv_id = :c and sender_id = :o and sender_role = 'user'",
                p("c", c.get("convId"), "o", other)) > 0;
        String refusal = talked ? null : guard.dmRefusal(me, other);
        if (refusal != null)
        {
            throw new ServiceException(refusal, 403);
        }
        long convId;
        if (c == null)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("conv_type", "private");
            row.put("pair_key", key);
            row.put("source", SOURCE_LABEL.containsKey(source) ? source : "profile");
            row.put("source_id", sourceId);
            row.put("title", "私信");
            row.put("status", "active");
            row.put("create_time", new Date());
            convId = db.insert("kg_chat_conv", row);
            upsertMember(convId, me, "member");
            upsertMember(convId, other, "member");
        }
        else
        {
            convId = num(c.get("convId"));
            if (SOURCE_LABEL.containsKey(source))
            {
                db.update("update kg_chat_conv set source = :s, source_id = :i where conv_id = :c", p("s", source, "i", sourceId, "c", convId));
            }
            upsertMember(convId, me, "member");
        }
        return conversation(me, convId);
    }

    /** 报名人联系门店：会话成员 = 报名人 + 门店所有在职员工 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> openStore(Long me, Long appId)
    {
        Map<String, Object> app = db.one("select a.app_id, a.app_user_id, a.store_id, j.title, j.job_type, s.store_name from kg_application a join kg_job j on j.job_id = a.job_id "
                + "join kg_store s on s.store_id = a.store_id where a.app_id = :a and a.del_flag = '0'", p("a", appId));
        if (app == null)
        {
            throw new ServiceException("报名记录不存在", 404);
        }
        // This first read resolves only whose account must be locked. It grants
        // no authority and cannot authorize a stale application after a lock wait.
        Long resolvedApplicant = KgJdbc.asLong(app.get("appUserId"));
        Long resolvedStore = KgJdbc.asLong(app.get("storeId"));
        lockActiveParticipants(me, resolvedApplicant, false);
        app = db.one("select a.app_id, a.app_user_id, a.store_id, j.title, j.job_type, s.store_name from kg_application a join kg_job j on j.job_id = a.job_id "
                + "join kg_store s on s.store_id = a.store_id where a.app_id = :a and a.del_flag = '0'", p("a", appId));
        if (app == null) throw new ServiceException("报名记录不存在", 404);
        if (!resolvedApplicant.equals(KgJdbc.asLong(app.get("appUserId")))
                || !java.util.Objects.equals(resolvedStore, KgJdbc.asLong(app.get("storeId"))))
            throw new ServiceException("报名归属已变化，请重新进入", 409);
        Long storeId = KgJdbc.asLong(app.get("storeId"));
        Long applicant = KgJdbc.asLong(app.get("appUserId"));
        reception.lockStore(storeId);
        boolean online = "online".equals(str(app.get("jobType")));
        // 门店这一侧：接待名单 + 发布过这位接单人所接线上任务的专员（2026-10-16：专员即使不在接待名单里，也能从「联系接单人」进来）
        List<Long> staff = seats(storeId, online, applicant);
        if (!me.equals(applicant) && (staff == null || !staff.contains(me)) && reception.steppingIn(me, storeId))
        {
            staff = staff == null ? new ArrayList<Long>() : new ArrayList<Long>(staff);
            staff.add(me);
        }
        if (!me.equals(applicant) && (staff == null || !staff.contains(me))) throw new ServiceException("无权联系这份报名", 403);
        String key = online ? "o" + storeId + "-u" + applicant : "offline:" + storeId + ":u" + applicant;
        Map<String, Object> c = db.one("select conv_id from kg_chat_conv where pair_key = :k", p("k", key));
        long convId;
        if (c == null)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("conv_type", "store");
            row.put("pair_key", key);
            row.put("store_id", storeId);
            row.put("source", online ? "online_task" : "progress");
            row.put("source_id", appId);
            row.put("title", StaffSupport.cut(str(app.get("storeName")), 64));
            row.put("status", "active");
            row.put("create_time", new Date());
            convId = db.insert("kg_chat_conv", row);
        }
        else
        {
            convId = num(c.get("convId"));
            db.update("update kg_chat_conv set source_id = :i where conv_id = :c", p("i", appId, "c", convId));
        }
        upsertMember(convId, applicant, "member");
        if (staff != null)
        {
            for (Long s : staff)
            {
                if (!s.equals(applicant))
                {
                    upsertMember(convId, s, "store");
                }
            }
        }
        return conversation(me, convId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> openActivity(Long me, Long activityId)
    {
        KgCommunityFeature.requireGroupChat(db);
        AccountState.requireActive(db, me);
        // 本方法自己开事务：内部调用 ensureActivityGroup 不经过代理，它身上的 @Transactional 在这里不生效，
        // 两个人同时第一次点进同一场活动的群会各建一个群（for update 在自动提交下没有锁的效果）
        long convId = ensureActivityGroup(activityId);
        // 自己退群后还能从活动页重新进（退群提示就是这么说的）：仍持有这场活动「已确认」报名的，恢复成员身份。
        // 取消报名时 leaveActivityGroup 会把人移出，此时报名已不是 confirmed，不会被这里拉回来（2026-09-26 回归发现退群后进不去）
        if (db.count("select count(*) from kg_activity_signup where activity_id = :a and app_user_id = :u and status = 'confirmed'",
                p("a", activityId, "u", me)) > 0)
        {
            upsertMember(convId, me, "member");
        }
        requireMember(me, convId);
        return conversation(me, convId);
    }

    /**
     * 门店会话里门店这一侧可以在席的人：接待名单（KgChatReception.staff）+ 线上会话里发布过这位接单人所接任务的专员
     * （KgChatReception.publishers，2026-10-16 主人拍板：发布的专员一定在会话里，即使门店设的接待账号里没有 TA）。
     * 两份名单分开取，各自以当前授权为准；同事里有接待资格的照旧在席。
     */
    private List<Long> seats(Long storeId, boolean online, Long applicant)
    {
        List<Long> base = reception.staff(storeId, online);
        List<Long> ids = base == null ? new ArrayList<Long>() : new ArrayList<Long>(base);
        if (online && applicant != null)
        {
            for (Long uid : reception.publishers(storeId, applicant))
            {
                if (uid != null && !ids.contains(uid))
                {
                    ids.add(uid);
                }
            }
        }
        return ids;
    }

    /** 一段门店会话的席位（见上）；历史混合会话（s 开头的老 pair）照旧只认接待名单 */
    private List<Long> seats(Map<String, Object> c)
    {
        boolean online = reception.isOnline(c);
        Long applicant = null;
        if (online && !KgChatReception.legacy(c))
        {
            try
            {
                applicant = KgChatReception.applicant(c);
            }
            catch (ServiceException e)
            {
                applicant = null;
            }
        }
        return seats(KgJdbc.asLong(c.get("storeId")), online, applicant);
    }

    private void lockReception(long convId)
    {
        Map<String,Object> c=db.one("select conv_type,store_id from kg_chat_conv where conv_id=:c",p("c",convId));
        if(c!=null && "store".equals(c.get("convType")))reception.lockStore(KgJdbc.asLong(c.get("storeId")));
    }

    // ---------------------------------------------------------------- 会话 / 消息

    private Map<String, Object> requireMember(Long me, long convId)
    {
        return requireMember(me, convId, true);
    }

    private Map<String, Object> requireMember(Long me, long convId, boolean persist)
    {
        Map<String,Object> member = requireMemberSeat(me, convId, persist);
        String mode = com.ruoyi.kangaroo.security.AppUserContext.chatMode();
        boolean staff = "store".equals(member.get("role")) || "admin".equals(member.get("role"));
        if (mode != null && staff != "staff".equals(mode))
            throw new ServiceException(staff ? "请切换到门店身份查看接待消息" : "这是你的个人消息，请返回用户首页查看", 403);
        return member;
    }

    private Map<String, Object> requireMemberSeat(Long me, long convId, boolean persist)
    {
        Map<String,Object> scope = db.one("select conv_id,conv_type,pair_key,source,store_id from kg_chat_conv where conv_id=:c",p("c",convId));
        if(scope!=null && "group".equals(scope.get("convType"))) KgCommunityFeature.requireGroupChat(db);
        if (scope != null && "private".equals(scope.get("convType"))) KgCommunityFeature.requirePrivateChat(db);
        if(scope!=null && "store".equals(scope.get("convType")))reception.requireStoreIdentity(me,scope);
        Map<String, Object> m = db.one("select * from kg_chat_member where conv_id = :c and app_user_id = :u and left_time is null", p("c", convId, "u", me));
        if (m != null && staffSeatRevoked(convId, me, m))
        {
            // 门店会话里的员工席位以「当前仍在职」为准（权限始终以 kg_staff_grant 为准）：授权被撤销 / 门店停用后，
            // 原来写进 kg_chat_member 的员工行不能继续读写（2026-09-28 复核发现：前员工仍能读报名人消息、以门店身份回复）
            throw new ServiceException("你已不是该门店的在职员工", 403);
        }
        if (m == null)
        {
            // 门店员工后加入的：门店会话、门店主办的活动群，当前是该门店在职员工就可以进
            Map<String, Object> c = db.one("select c.conv_type, c.store_id, a.store_id act_store, c.conv_id, c.pair_key, c.source, c.source_id from kg_chat_conv c left join kg_activity a on a.activity_id = c.activity_id "
                    + "where c.conv_id = :c", p("c", convId));
            Long storeId = c == null ? null : ("store".equals(str(c.get("convType"))) ? KgJdbc.asLong(c.get("storeId"))
                    : ("group".equals(str(c.get("convType"))) ? KgJdbc.asLong(c.get("actStore")) : null));
            if (storeId != null)
            {
                List<Long> staff = "store".equals(str(c.get("convType"))) ? seats(c) : reception.staff(storeId, false);
                if ((staff != null && staff.contains(me)) || reception.steppingIn(me, storeId))
                {
                    String role = "store".equals(str(c.get("convType"))) ? "store" : "admin";
                    // Transactional readers/senders authorize before locking the
                    // conversation, then materialize the seat in conv -> member order.
                    if (!persist) return p("role", role);
                    upsertMember(convId, me, role);
                    return db.one("select * from kg_chat_member where conv_id = :c and app_user_id = :u", p("c", convId, "u", me));
                }
            }
            throw new ServiceException("你不在这个会话中", 403);
        }
        return m;
    }

    /** 这一行成员是员工席位（role = store / admin）、且这个人已不在该门店的在职员工里 */
    private boolean staffSeatRevoked(long convId, Long uid, Map<String, Object> member)
    {
        String role = str(member.get("role"));
        if (!"store".equals(role) && !"admin".equals(role))
        {
            return false;
        }
        Map<String, Object> c = db.one("select c.conv_type, c.store_id, a.store_id act_store, c.conv_id, c.pair_key, c.source, c.source_id from kg_chat_conv c left join kg_activity a on a.activity_id = c.activity_id "
                + "where c.conv_id = :c", p("c", convId));
        if (c == null)
        {
            return false;
        }
        Long storeId = "store".equals(str(c.get("convType"))) ? KgJdbc.asLong(c.get("storeId"))
                : ("group".equals(str(c.get("convType"))) ? KgJdbc.asLong(c.get("actStore")) : null);
        if (storeId == null)
        {
            return false;
        }
        List<Long> staff = "store".equals(str(c.get("convType"))) ? seats(c) : reception.staff(storeId, false);
        return (staff == null || !staff.contains(uid)) && !reception.steppingIn(uid, storeId);
    }

    private Map<String, Object> userBrief(Long uid)
    {
        Map<String, Object> u = db.one("select user_id, nickname, avatar, phone_verified_time from kg_app_user where user_id = :u", p("u", uid));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("userId", uid);
        m.put("name", u == null || str(u.get("nickname")).isEmpty() ? "用户" + uid : u.get("nickname"));
        m.put("avatar", u == null ? "" : str(u.get("avatar")));
        m.put("phoneVerified", u != null && u.get("phoneVerifiedTime") != null);
        return m;
    }

    static final String PEER_GONE = "对方已注销，消息发不出去";
    /** 私信对方注销后显示的名字（会话详情、消息列表一致） */
    static final String GONE_NAME = "已注销用户";

    /** 私信的另一方：先看成员表；对方注销时成员行已删掉，就按 pair_key（小号-大号）找回来。c 要带 convId、pairKey */
    private Long privatePeer(Map<String, Object> c, Long me)
    {
        Map<String, Object> other = db.one("select app_user_id from kg_chat_member where conv_id = :c and app_user_id <> :u limit 1", p("c", c.get("convId"), "u", me));
        if (other != null)
        {
            return KgJdbc.asLong(other.get("appUserId"));
        }
        return pairPeer(c.get("pairKey"), me);
    }

    /** 私信 pair_key（小号-大号）里不是我的那个账号；不是私信的 pair_key 返回 null。会话详情和消息列表共用 */
    static Long pairPeer(Object pairKey, Long me)
    {
        String[] pair = str(pairKey).split("-");
        if (pair.length == 2)
        {
            for (String x : pair)
            {
                try
                {
                    Long id = Long.valueOf(x.trim());
                    if (!id.equals(me))
                    {
                        return id;
                    }
                }
                catch (NumberFormatException ignore)
                {
                    // 不是私信的 pair_key
                }
            }
        }
        return null;
    }

    /** 私信对方是不是已经注销 / 删除了（del_flag 不是 0；成员表和 pair_key 都找不到对方也算）；只是停用的不算 */
    private boolean peerGone(Long uid)
    {
        if (uid == null)
        {
            return true;
        }
        Map<String, Object> u = db.one("select del_flag from kg_app_user where user_id = :u", p("u", uid));
        String flag = u == null ? "" : str(u.get("delFlag"));
        return !flag.isEmpty() && !"0".equals(flag);
    }

    private static Map<String, Object> goneBrief(Long uid)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("userId", uid);
        m.put("name", GONE_NAME);
        m.put("avatar", "");
        m.put("phoneVerified", false);
        return m;
    }

    /**
     * 门店会话顶部的说明（2026-10-15 主人反馈）：门店这一侧是本店接待同事共用的，同事的消息也在里面，员工以为是「接单人都在的群聊」。
     * 之前两边都只写「线上任务接待 / 线下报名沟通；下方消息显示实际发送者昵称。」，现在写清楚是和谁的单独沟通、还有谁能看到：
     * 员工这一侧「这是和 某某 的单独沟通，其他接单人看不到；本店接待 同事A、同事B 也能看到并回复。」——同事是当前接待名单去掉自己和报名人，
     * 超过 2 位只写人数，没有就不写后半句；报名人这一侧「门店接待会在这里回复你，其他接单人看不到。」。线下报名把「接单人」写成「报名人」。
     * 归档提示和「暂无可用接待账号」由调用方先判断，不走这里。
     */
    private String receptionNotice(Long me, Map<String, Object> c, Map<String, Object> mem, List<Long> staff, boolean online, Long specialist)
    {
        String who = online ? "接单人" : "报名人";
        if (!"store".equals(str(mem.get("role"))))
        {
            // 专员发布的线上任务（2026-10-16）：发布的专员一定在这段会话里
            return specialist != null ? "发布任务的专员和门店接待会在这里回复你，其他" + who + "看不到。" : "门店接待会在这里回复你，其他" + who + "看不到。";
        }
        Long applicant;
        try
        {
            applicant = KgChatReception.applicant(c);
        }
        catch (ServiceException e)
        {
            applicant = null;
        }
        List<Long> mates = new ArrayList<Long>();
        for (Long uid : staff)
        {
            if (!uid.equals(me) && !uid.equals(applicant) && !mates.contains(uid))
            {
                mates.add(uid);
            }
        }
        String head = "这是和 " + KgChatReception.applicantName(db, c) + " 的单独沟通，其他" + who + "看不到";
        if (mates.isEmpty())
        {
            return head + "。";
        }
        if (mates.size() > 2)
        {
            return head + "；本店 " + mates.size() + " 位同事也能看到并回复。";
        }
        List<String> names = new ArrayList<String>();
        for (Long uid : mates)
        {
            names.add(str(userBrief(uid).get("name")));
        }
        return head + "；本店接待 " + String.join("、", names) + " 也能看到并回复。";
    }

    public Map<String, Object> conversation(Long me, long convId)
    {
        Map<String, Object> mem = requireMember(me, convId);
        Map<String, Object> c = db.must("select * from kg_chat_conv where conv_id = :c", p("c", convId), "会话不存在");
        String type = str(c.get("convType"));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("convId", convId);
        m.put("type", type);
        m.put("status", KgChatReception.legacy(c) ? "archived" : c.get("status"));
        m.put("archived", KgChatReception.legacy(c));
        m.put("sourceLabel", SOURCE_LABEL.getOrDefault(str(c.get("source")), ""));
        m.put("muted", "1".equals(str(mem.get("muted"))));
        m.put("pinned", "1".equals(str(mem.get("pinned"))));
        m.put("myRole", mem.get("role"));
        // 接单人这一侧：会话当前这份报名的任务是本店在职专员发布的，顶栏写「专员 · 昵称」（2026-10-16 主人拍板）
        Long specialist = null;
        if("store".equals(type)) {
            boolean online=reception.isOnline(c);
            List<Long> staff=seats(c);
            if(online){staff=new ArrayList<Long>(staff);staff.remove(KgChatReception.applicant(c));}
            if(online&&!KgChatReception.legacy(c)&&!"store".equals(str(mem.get("role")))){
                specialist=reception.specialistOf(KgJdbc.asLong(c.get("storeId")),c.get("sourceId"));
                if(specialist!=null&&(specialist<=0||!staff.contains(specialist)))specialist=null;
            }
            m.put("receptionNotice",KgChatReception.legacy(c)?"历史沟通已归档；请从相应报名或线上任务重新联系。":
                    online&&staff.isEmpty()?"线上任务接待：当前暂无可用接待账号，请联系门店调整。":receptionNotice(me,c,mem,staff,online,specialist));
            if(KgChatReception.legacy(c)) m.put("recontactApps",db.list("select a.app_id,j.title,j.job_type from kg_application a join kg_job j on j.job_id=a.job_id "
                    +"join kg_chat_member cm on cm.conv_id=:c and cm.role='member' and cm.app_user_id=a.app_user_id "
                    +"where a.store_id=:s and a.del_flag='0' and j.del_flag='0' order by a.app_id desc limit 20",p("c",convId,"s",c.get("storeId"))));
        }
        if ("private".equals(type))
        {
            Long oid = privatePeer(c, me);
            // 对方已注销：注销时成员行删掉了，之前标题是 null、other 是空的，还能接着发。现在写「已注销用户」，并用 dmRefusal 让页面直接说明、不让输入
            boolean gone = peerGone(oid);
            Map<String, Object> ob = gone ? goneBrief(oid) : userBrief(oid);
            m.put("title", ob.get("name"));
            m.put("other", ob);
            m.put("otherDeactivated", gone);
            m.put("blocked", oid != null && guard.blocked(me, oid));
            // blocked 是双向的（谁拉黑谁都算）；菜单「拉黑 / 取消拉黑」要看是不是我拉黑了对方：被对方拉黑时之前显示「取消拉黑」，点了反而把对方拉黑
            m.put("iBlocked", oid != null && db.count("select count(*) from kg_user_block where user_id = :u and blocked_id = :o", p("u", me, "o", oid)) > 0);
            // 同行信息：双方公开行程的共同城市与重叠天数（没有公开行程就不显示）
            if (oid != null && !gone)
            {
                Map<String, Object> ov = db.one("select a.city, greatest(a.start_date, b.start_date) f, least(a.end_date, b.end_date) t from kg_trip_plan a "
                        + "join kg_trip_plan b on b.city = a.city and b.app_user_id = :o and b.status = 'online' and b.del_flag = '0' "
                        + "where a.app_user_id = :me and a.status in ('online','pending') and a.del_flag = '0' and least(a.end_date, b.end_date) >= greatest(a.start_date, b.start_date) "
                        + "order by a.plan_id desc limit 1", p("o", oid, "me", me));
                if (ov != null)
                {
                    java.time.LocalDate f = java.time.LocalDate.parse(str(ov.get("f")).substring(0, 10));
                    java.time.LocalDate t = java.time.LocalDate.parse(str(ov.get("t")).substring(0, 10));
                    long days = java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1;
                    m.put("overlapCity", ov.get("city"));
                    m.put("overlapText", days + "天（" + f.getMonthValue() + "月" + f.getDayOfMonth() + "日—" + t.getMonthValue() + "月" + t.getDayOfMonth() + "日）");
                }
                Map<String, Object> plan = db.one("select city, activities from kg_trip_plan where app_user_id = :o and status = 'online' and del_flag = '0' "
                        + "order by plan_id desc limit 1", p("o", oid));
                m.put("otherMeta", plan == null ? "" : "公开行程 · " + plan.get("city") + (str(plan.get("activities")).isEmpty() ? "" : " · " + str(plan.get("activities")).replace(",", " / ")));
            }
            long mine = db.count("select count(*) from kg_chat_msg where conv_id = :c and sender_id = :u", p("c", convId, "u", me));
            long theirs = db.count("select count(*) from kg_chat_msg where conv_id = :c and sender_id <> :u and sender_role = 'user'", p("c", convId, "u", me));
            // 互相关注不限条数（-1）；否则对方回复前还能发几条
            boolean mutual = oid != null && guard.mutualFollow(me, oid);
            m.put("greetLeft", theirs > 0 || mutual ? -1 : Math.max(0, GREET_LIMIT - mine));
            m.put("mutualFollow", mutual);
            // 对方还没回过话、而 TA 的「谁可以私信我」不允许我：页面直接说明原因、不让输入（服务端发送时也会拦）
            m.put("dmRefusal", gone ? PEER_GONE : (oid == null || theirs > 0 ? null : guard.dmRefusal(me, oid)));
            // 对方注销了就不再显示「交换联系方式」
            if (!gone)
            {
                m.put("contact", contactState(convId, me));
            }
        }
        else if ("store".equals(type))
        {
            Map<String, Object> app = db.one("select j.title, j.city from kg_application a join kg_job j on j.job_id = a.job_id where a.app_id = :a", p("a", c.get("sourceId")));
            // 城市是「不限」（线上任务）或没填就不写：之前写成「… · 不限」，页面上和下面的接待说明连成「不限线上任务接待」
            String city = app == null ? "" : str(app.get("city"));
            String jobMeta = app == null ? "" : app.get("title") + (city.isEmpty() || "不限".equals(city) ? "" : " · " + city);
            // 员工这一侧（2026-10-15）：顶栏写报名人昵称、下面写「线上任务 / 线下报名 · 岗位 · 城市」，看得出在和谁聊；
            // 之前员工打开也是门店名，自己店的名字没有意义。报名人那一侧照旧是门店名
            if ("store".equals(str(mem.get("role"))))
            {
                m.put("title", KgChatReception.applicantName(db, c));
                String kind = KgChatReception.legacy(c) ? "历史沟通" : "online_task".equals(str(c.get("source"))) ? "线上任务" : "线下报名";
                m.put("otherMeta", jobMeta.isEmpty() ? kind : kind + " · " + jobMeta);
            }
            else if (specialist != null)
            {
                // 专员发布的线上任务：顶栏写「专员 · 昵称」，接单人知道在和谁沟通；门店名挪到下面一行（老版本小程序也照这两个字段显示）
                m.put("title", "专员 · " + str(userBrief(specialist).get("name")));
                m.put("otherMeta", jobMeta.isEmpty() ? str(c.get("title")) : c.get("title") + " · " + jobMeta);
                m.put("contactRole", "specialist");
            }
            else
            {
                m.put("title", c.get("title"));
                m.put("otherMeta", jobMeta);
            }
            m.put("storeId", c.get("storeId"));
            m.put("greetLeft", -1);
        }
        else
        {
            Map<String, Object> a = db.one("select activity_id, title, source, start_time, end_time, place, signed, cover, city, app_user_id, store_id from kg_activity where activity_id = :a",
                    p("a", c.get("activityId")));
            long members = db.count("select count(*) from kg_chat_member where conv_id = :c and left_time is null", p("c", convId));
            m.put("title", c.get("title"));
            m.put("memberCount", members);
            m.put("announcement", c.get("announcement"));
            if (a != null)
            {
                Map<String, Object> av = new LinkedHashMap<String, Object>();
                av.put("activityId", a.get("activityId"));
                av.put("title", a.get("title"));
                av.put("kind", KgActivityService.SOURCE_LABEL.get(str(a.get("source"))));
                av.put("time", KgActivityService.rangeText(a.get("startTime"), a.get("endTime")));
                av.put("place", a.get("place"));
                av.put("signed", num(a.get("signed")));
                av.put("cover", a.get("cover"));
                m.put("activity", av);
            }
            m.put("greetLeft", -1);
        }
        return m;
    }

    /**
     * 我最近的私信会话（帖子「分享到私信」选人用）：只列用户之间、还在进行中的私信，按最近消息倒序，最多 30 个；
     * 存在拉黑关系、对方账号已停用的不列。最近一条用会话里存的预览文字（没交换联系方式时已经是遮挡后的）。
     */
    public List<Map<String, Object>> recentPrivate(Long me)
    {
        KgCommunityFeature.requirePrivateChat(db);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select c.conv_id, c.last_msg, c.last_time, o.app_user_id other_id from kg_chat_member m "
                + "join kg_chat_conv c on c.conv_id = m.conv_id and c.conv_type = 'private' and c.status = 'active' "
                + "join kg_chat_member o on o.conv_id = c.conv_id and o.app_user_id <> m.app_user_id "
                + "join kg_app_user u on u.user_id = o.app_user_id and u.status = '0' and u.del_flag = '0' "
                + "where m.app_user_id = :u and m.left_time is null "
                + "and not exists (select 1 from kg_user_block b where (b.user_id = :u and b.blocked_id = o.app_user_id) or (b.user_id = o.app_user_id and b.blocked_id = :u)) "
                + "order by c.last_time is null, c.last_time desc, c.conv_id desc limit 30", p("u", me)))
        {
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("convId", r.get("convId"));
            v.put("other", userBrief(KgJdbc.asLong(r.get("otherId"))));
            v.put("lastMsg", oneLine(str(r.get("lastMsg"))));
            Date t = StaffSupport.parse(r.get("lastTime"));
            v.put("timeText", t == null ? "" : StaffSupport.fmt(t, "MM-dd HH:mm"));
            out.add(v);
        }
        return out;
    }

    /** 私信双方是否同意交换联系方式：mine 我已同意 / theirs 对方已同意 / open 双方都同意（这时联系方式不再遮挡） */
    Map<String, Object> contactState(long convId, Long me)
    {
        boolean mine = false;
        boolean theirs = false;
        for (Map<String, Object> r : db.list("select app_user_id, contact_ok_time from kg_chat_member where conv_id = :c", p("c", convId)))
        {
            boolean ok = r.get("contactOkTime") != null;
            if (me.equals(KgJdbc.asLong(r.get("appUserId"))))
            {
                mine = ok;
            }
            else
            {
                theirs = theirs || ok;
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("mine", mine);
        m.put("theirs", theirs);
        m.put("open", mine && theirs);
        return m;
    }

    private Map<String, Object> msgView(Map<String, Object> r, Long me, boolean hideContacts, List<Long> specialists)
    {
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        Long sender = KgJdbc.asLong(r.get("senderId"));
        v.put("msgId", r.get("msgId"));
        v.put("senderId", sender);
        v.put("role", r.get("senderRole"));
        v.put("mine", me.equals(sender));
        v.put("name", r.get("nickname") == null || str(r.get("nickname")).isEmpty() ? ("system".equals(str(r.get("senderRole"))) ? "系统" : "用户" + sender) : r.get("nickname"));
        v.put("avatar", r.get("avatar"));
        Object content = "recalled".equals(str(r.get("status"))) ? "（消息已撤回）" : r.get("content");
        if (hideContacts && content != null && !"recalled".equals(str(r.get("status"))))
        {
            String masked = maskContacts(str(content));
            if (!masked.equals(str(content)))
            {
                // 自己发的照常显示原文，只提示「对方暂时看不到」；对方发的显示遮挡后的
                if (me.equals(sender))
                {
                    v.put("hiddenForOther", true);
                }
                else
                {
                    content = masked;
                }
            }
        }
        boolean image = r.get("imageMime") != null && !"recalled".equals(str(r.get("status")));
        boolean file = !image && r.get("fileName") != null && !"recalled".equals(str(r.get("status")));
        // 文件消息（V46）：新版本按 type = file 显示文件卡片；旧版本小程序只认文字，content 写成「[文件] 名称，请升级小程序查看」
        v.put("content", file ? KgChatFiles.fallback(str(r.get("fileName"))) : content);
        v.put("type", image ? "image" : file ? "file" : "text");
        if (image) { v.put("imageWidth", r.get("imageWidth")); v.put("imageHeight", r.get("imageHeight")); }
        if (file) v.put("file", KgChatFiles.view(r.get("fileName"), r.get("fileExt"), r.get("fileSize")));
        // 门店这一侧发的：发布任务的专员写「专员 · 昵称」，其他同事照旧「接待 · 昵称」（页面按 seatLabel 显示，老版本不认就还是「接待」）
        if ("store".equals(str(r.get("senderRole"))) && specialists != null && specialists.contains(sender)) v.put("seatLabel", "专员");
        v.put("time", StaffSupport.fmt(StaffSupport.parse(r.get("createTime")), "MM-dd HH:mm"));
        return v;
    }

    /** 拉消息：afterId = 0 取最近 30 条，否则取之后的新消息；同时把已读推进到最新 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> messages(Long me, long convId, Long afterId)
    {
        lockReception(convId);
        requireMember(me, convId, false);
        Map<String, Object> conv = db.must("select conv_id, conv_type from kg_chat_conv where conv_id = :c for update", p("c", convId), "会话不存在");
        requireMember(me, convId);
        Map<String, Object> contact = "private".equals(str(conv.get("convType"))) ? contactState(convId, me) : null;
        boolean hideContacts = contact != null && !Boolean.TRUE.equals(contact.get("open"));
        // The conversation lock serializes read acknowledgements with send's
        // notification write. A committed, non-locking message snapshot avoids
        // waiting on uncommitted inserts from existing system-message writers.
        long after = afterId == null ? 0 : afterId;
        String base = "select m.*, u.nickname, u.avatar, i.mime image_mime, i.width image_width, i.height image_height, f.file_name, f.file_ext, f.file_size "
                + "from kg_chat_msg m left join kg_app_user u on u.user_id = m.sender_id left join kg_chat_image i on i.msg_id=m.msg_id and u.status='0' and u.del_flag='0' "
                + "left join kg_chat_file f on f.msg_id=m.msg_id and u.status='0' and u.del_flag='0' where m.conv_id = :c "
                + "and (m.status in ('visible','recalled') or m.sender_id = :me)";
        List<Map<String, Object>> rows = after > 0
                ? db.list(base + " and m.msg_id > :a order by m.msg_id limit 50", p("c", convId, "me", me, "a", after))
                : db.list("select * from (" + base + " order by m.msg_id desc limit 30) x order by msg_id", p("c", convId, "me", me));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        long last = after;
        List<Long> specialists = null;
        for (Map<String, Object> r : rows)
        {
            if (guard.blocked(me, KgJdbc.asLong(r.get("senderId"))) && !me.equals(KgJdbc.asLong(r.get("senderId"))))
            {
                continue;
            }
            if (specialists == null && "store".equals(str(r.get("senderRole"))))
            {
                specialists = specialistSeats(convId);
            }
            out.add(msgView(r, me, hideContacts, specialists));
            last = Math.max(last, num(r.get("msgId")));
        }
        if (last > 0)
        {
            db.update("update kg_chat_member set last_read_id = greatest(last_read_id, :l) where conv_id = :c and app_user_id = :u", p("l", last, "c", convId, "u", me));
            Map<String, Object> member = db.must("select last_read_id from kg_chat_member where conv_id = :c and app_user_id = :u",
                    p("c", convId, "u", me), "会话成员不存在");
            long read = num(member.get("lastReadId"));
            // A page is limited to 50 rows. Clearing the merged reminder is only
            // safe when no visible other-user message remains beyond the actual
            // (monotonic) member watermark, including visible recalled messages.
            if (db.count("select count(*) from kg_chat_msg m where m.conv_id = :c and m.msg_id > :l and m.sender_id <> :u "
                    + "and m.status in ('visible','recalled') and not exists (select 1 from kg_user_block b "
                    + "where (b.user_id = :u and b.blocked_id = m.sender_id) or (b.user_id = m.sender_id and b.blocked_id = :u))",
                    p("c", convId, "u", me, "l", read)) == 0)
            {
                db.update("update kg_message set read_flag = '1', read_time = now() where recipient_id = :u and biz_type = 'chat_conv' and biz_id = :c and read_flag = '0'",
                        p("u", me, "c", convId));
            }
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("rows", out);
        res.put("lastId", last);
        // 私信对方已注销（注销时成员行删掉了）：不再带交换状态，页面上「交换联系方式」那条不会被轮询又顶出来
        if (contact != null && db.count("select count(*) from kg_chat_member where conv_id = :c and app_user_id <> :u", p("c", convId, "u", me)) > 0)
        {
            // 轮询时顺带带回交换状态：对方点了同意，前端不用重进会话就能看到
            res.put("contact", contact);
        }
        return res;
    }

    /** 线上门店会话里的发布专员（消息上方写「专员 · 昵称」）；其他会话、历史混合会话是空的 */
    private List<Long> specialistSeats(long convId)
    {
        Map<String, Object> c = db.one("select conv_id, conv_type, store_id, pair_key, source from kg_chat_conv where conv_id = :c", p("c", convId));
        if (c == null || !"store".equals(str(c.get("convType"))) || !"online_task".equals(str(c.get("source"))) || KgChatReception.legacy(c))
        {
            return new ArrayList<Long>();
        }
        try
        {
            return reception.publishers(KgJdbc.asLong(c.get("storeId")), KgChatReception.applicant(c));
        }
        catch (ServiceException e)
        {
            return new ArrayList<Long>();
        }
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> send(Long me, long convId, String content)
    {
        return send(me, convId, content, null);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> send(Long me, long convId, String content, Object clientMessageId)
    {
        return sendPayload(me, convId, content, clientMessageId, null, null);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> sendImage(Long me, long convId, Object clientMessageId, org.springframework.web.multipart.MultipartFile file)
    {
        if (clientMessageId == null) throw new ServiceException("图片消息必须提供消息标识", 422);
        // Decode only after authorization; no public upload or persistent bytes before transaction.
        AccountState.requireActive(db, me);
        lockReception(convId);
        requireMember(me, convId, false);
        return sendPayload(me, convId, "[图片]", clientMessageId, KgChatImage.decode(file), null);
    }

    /**
     * 发文件（V46）：只有门店和报名人 / 接单人的会话能发（用户之间的私信、活动群不行），一条消息一个文件。
     * 先核对身份、会话资格，再读文件；格式、大小、文件头不对 422。消息文字是「[文件] 名称」（会话列表、站内信也显示这一句）。
     * 同一个消息标识重发时：文件内容一样返回原来那条，不一样 409。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> sendFile(Long me, long convId, Object clientMessageId, org.springframework.web.multipart.MultipartFile file, String name)
    {
        if (clientMessageId == null) throw new ServiceException("文件消息必须提供消息标识", 422);
        AccountState.requireActive(db, me);
        lockReception(convId);
        requireMember(me, convId, false);
        Map<String, Object> c = db.one("select conv_type from kg_chat_conv where conv_id = :c", p("c", convId));
        if (c == null || !"store".equals(str(c.get("convType"))))
        {
            throw new ServiceException("只有和门店的沟通里可以发文件", 422);
        }
        KgChatFiles.Upload upload = files.check(file, name);
        return sendPayload(me, convId, KgChatFiles.preview(upload.name), clientMessageId, null, upload);
    }

    private Map<String, Object> sendPayload(Long me, long convId, String content, Object clientMessageId, KgChatImage image, KgChatFiles.Upload file)
    {
        AccountState.requireActive(db, me);
        lockReception(convId);
        requireMember(me, convId, false);
        Map<String, Object> c = db.must("select * from kg_chat_conv where conv_id = :c for update", p("c", convId), "会话不存在");
        Map<String, Object> mem = requireMember(me, convId);
        if (KgChatReception.legacy(c)) throw new ServiceException("历史门店沟通已归档，请从对应报名或线上任务重新进入", 409);
        if (!"active".equals(str(c.get("status"))))
        {
            throw new ServiceException("会话已关闭", 409);
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() || text.length() > TEXT_MAX)
        {
            throw new ServiceException("消息为 1–" + TEXT_MAX + " 字", 422);
        }
        String type = str(c.get("convType"));
        if (file != null && !"store".equals(type))
        {
            throw new ServiceException("只有和门店的沟通里可以发文件", 422);
        }
        // Membership and blocking must be checked even for a replay: an old key
        // never grants access after revocation. Do not charge replay rate limits.
        Long privateOther = null;
        if ("private".equals(type))
        {
            privateOther = privatePeer(c, me);
            // 对方已注销：之前照样发成功，消息发进一个没人的会话
            if (peerGone(privateOther))
            {
                throw new ServiceException(PEER_GONE, 409);
            }
            guard.requireNotBlocked(me, privateOther);
        }
        String key = messageKey(me, convId, clientMessageId);
        if (key != null && !db.claimIdem(key, "chat_message"))
        {
            // claimIdem waits for the winning transaction. A locking current
            // read is necessary under MySQL REPEATABLE READ.
            Map<String, Object> idem = db.one("select biz_type, biz_id from kg_idempotency where idem_key = :k for share", p("k", key));
            if (idem == null || !"chat_message".equals(str(idem.get("bizType"))) || num(idem.get("bizId")) <= 0)
                throw new ServiceException("消息提交结果待确认，请稍后重试", 409);
            Map<String, Object> original = db.one("select msg_id, conv_id, sender_id, content, (select i.input_sha from kg_chat_image i where i.msg_id=kg_chat_msg.msg_id) input_sha, "
                    + "(select f.input_sha from kg_chat_file f where f.msg_id=kg_chat_msg.msg_id) file_sha from kg_chat_msg where msg_id = :m for share", p("m", idem.get("bizId")));
            if (original == null || num(original.get("convId")) != convId || !me.equals(KgJdbc.asLong(original.get("senderId")))
                    || !text.equals(str(original.get("content")))
                    || (image == null ? original.get("inputSha") != null : !image.inputSha.equals(original.get("inputSha")))
                    || (file == null ? original.get("fileSha") != null : !file.sha.equals(original.get("fileSha"))))
                throw new ServiceException("同一消息标识不能用于不同消息", 409);
            return p("msgId", original.get("msgId"));
        }
        if ("private".equals(type))
        {
            Map<String, Object> other = db.one("select app_user_id from kg_chat_member where conv_id = :c and app_user_id <> :u limit 1", p("c", convId, "u", me));
            Long oid = other == null ? null : KgJdbc.asLong(other.get("appUserId"));
            long theirs = db.count("select count(*) from kg_chat_msg where conv_id = :c and sender_id <> :u and sender_role = 'user'", p("c", convId, "u", me));
            if (theirs == 0)
            {
                // 对方还没回过话：先看 TA 的「谁可以私信我」（会话建好后 TA 才改的设置也算数），再看条数
                String refusal = oid == null ? null : guard.dmRefusal(me, oid);
                if (refusal != null)
                {
                    throw new ServiceException(refusal, 403);
                }
                long mine = db.count("select count(*) from kg_chat_msg where conv_id = :c and sender_id = :u", p("c", convId, "u", me));
                if (mine >= GREET_LIMIT && (oid == null || !guard.mutualFollow(me, oid)))
                {
                    throw new ServiceException("对方回复前只能先发 " + GREET_LIMIT + " 条消息，等对方回复后就能继续聊了（互相关注的人不受限制）", 409);
                }
            }
        }
        if (db.count("select count(*) from kg_chat_msg where sender_id = :u and create_time > date_sub(now(), interval 1 minute)", p("u", me)) >= 20)
        {
            throw new ServiceException("发送太频繁了，稍后再试", 429);
        }
        // 文件：每人每天最多 200MB（防止把服务器磁盘塞满）
        if (file != null && db.count("select coalesce(sum(file_size), 0) from kg_chat_file where sender_id = :u and create_time >= curdate()", p("u", me)) + file.size
                > KgChatFiles.MAX_BYTES_PER_SENDER_DAY)
        {
            throw new ServiceException("今天发的文件已经很多了，请明天再发，或把网盘链接发在消息里", 429);
        }
        if (image == null) guard.check(text);
        // A newly selected receiver may never have opened this existing online
        // conversation. The store and conversation locks are already held;
        // add only current eligible seats and preserve their mute preference.
        // 席位含发布这位接单人所接任务的专员（2026-10-16）：专员一定在会话里、收得到提醒
        if ("store".equals(type) && reception.isOnline(c))
        {
            Long applicant=KgChatReception.applicant(c);
            for (Long receiver : seats(c))
            {
                if (!receiver.equals(me) && !receiver.equals(applicant)) upsertMember(convId, receiver, "store");
            }
        }
        String role = "store".equals(str(mem.get("role"))) ? "store" : "user";
        // 私信还没交换联系方式时，站内信提醒和会话最近一条也用遮挡后的文字
        String preview = "private".equals(type) && !Boolean.TRUE.equals(contactState(convId, me).get("open")) ? maskContacts(text) : text;
        long msgId = insertMsg(convId, me, role, text, preview);
        if (image != null && db.update("insert into kg_chat_image(msg_id,input_sha,mime,width,height,image_bytes) values(:m,:h,:t,:w,:v,:b)",
                p("m",msgId,"h",image.inputSha,"t",image.mime,"w",image.width,"v",image.height,"b",image.bytes)) != 1)
            throw new ServiceException("图片保存失败，请重试",503);
        if (file != null) saveFile(convId, msgId, me, file);
        // 站内信：每人每个身份下这个会话只有一行，对方那一行更新并改回未读；免打扰的不提醒
        String senderName = str(userBrief(me).get("name"));
        String staffTitle = "store".equals(type) ? KgChatReception.staffSideTitle(db, c) : null;
        for (Map<String, Object> r : db.list("select app_user_id, role from kg_chat_member where conv_id = :c and app_user_id <> :u and left_time is null and muted = '0'",
                p("c", convId, "u", me)))
        {
            // 已经不在职的前员工不再收到会话内容；门店会话里按收件人自己的身份决定进员工还是用户消息中心
            // （此前员工回复后，其他员工收到的是 mode=user 的提醒，出现在个人消息中心）
            if (staffSeatRevoked(convId, KgJdbc.asLong(r.get("appUserId")), r))
            {
                continue;
            }
            Long uid = KgJdbc.asLong(r.get("appUserId"));
            String seat = str(r.get("role"));
            boolean staffSide = "store".equals(type) && "store".equals(seat);
            // 标题按收件人这一侧：员工看到「报名人昵称 · 岗位」，报名人看到门店名，私信是对方昵称，群聊是群名
            String title = staffSide ? staffTitle : "private".equals(type) ? senderName : str(c.get("title"));
            // 正文：群聊写发言人；员工这一侧看到同事发的写「同事昵称（接待）：」，报名人发的直接是正文。
            // 不再在前面写「（N 条新消息）」：红点 / 角标已经说明有新消息；那个数只在进会话后才归零，
            // 「全部已读」以后再来消息会把已经清掉的也算进去，而且读掉后列表不刷新还一直显示着
            String who = "group".equals(type) ? senderName + "：" : staffSide && "store".equals(role) ? senderName + "（接待）：" : "";
            upsertConvNotice(uid, c, seat, title, who + StaffSupport.cut(oneLine(preview), 80), true);
        }
        // 发消息的人自己也留一行（已读）：门店员工「联系接单人 / 报名人」发出后，员工身份「消息 › 私信」里马上有这段会话，点开能接着聊
        String mySeat = str(mem.get("role"));
        String myTitle = "store".equals(type) ? ("store".equals(mySeat) ? staffTitle : str(c.get("title")))
                : "private".equals(type) ? (privateOther == null ? "私信" : str(userBrief(privateOther).get("name"))) : str(c.get("title"));
        upsertConvNotice(me, c, mySeat, myTitle, "我：" + StaffSupport.cut(oneLine(preview), 80), false);
        if (key != null && db.update("update kg_idempotency set biz_id = :m where idem_key = :k and biz_type = 'chat_message' and biz_id is null",
                p("m", msgId, "k", key)) != 1)
            throw new ServiceException("消息提交结果未能登记，请重试", 409);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("msgId", msgId);
        if (!preview.equals(text))
        {
            res.put("hiddenForOther", true);
        }
        return res;
    }

    /**
     * 交换联系方式（只用于用户之间的私信）：agree=true 我同意，对方也同意后双方都能看到对方发的手机号 / 微信号；
     * agree=false 撤回我的请求（双方都同意之后不能撤回）。我同意时给对方发站内信提醒（对方设了免打扰就不发）。
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> exchangeContact(Long me, long convId, boolean agree)
    {
        AccountState.requireActive(db, me);
        lockReception(convId);
        requireMember(me, convId, false);
        Map<String, Object> c = db.must("select * from kg_chat_conv where conv_id = :c for update", p("c", convId), "会话不存在");
        requireMember(me, convId);
        if (!"private".equals(str(c.get("convType"))))
        {
            throw new ServiceException("只有私信可以交换联系方式", 422);
        }
        if (KgChatReception.legacy(c) || !"active".equals(str(c.get("status"))))
        {
            throw new ServiceException("会话已关闭", 409);
        }
        Map<String, Object> other = db.one("select app_user_id, muted, left_time from kg_chat_member where conv_id = :c and app_user_id <> :u limit 1", p("c", convId, "u", me));
        Long oid = other == null ? null : KgJdbc.asLong(other.get("appUserId"));
        if (oid == null)
        {
            throw new ServiceException("对方不在这个会话里", 409);
        }
        guard.requireNotBlocked(me, oid);
        Map<String, Object> before = contactState(convId, me);
        if (Boolean.TRUE.equals(before.get("open")))
        {
            if (!agree)
            {
                throw new ServiceException("已经交换过联系方式，不能撤回", 409);
            }
            return before;
        }
        if (agree == Boolean.TRUE.equals(before.get("mine")))
        {
            return before;
        }
        db.update("update kg_chat_member set contact_ok_time = " + (agree ? "now()" : "null") + " where conv_id = :c and app_user_id = :u", p("c", convId, "u", me));
        Map<String, Object> after = contactState(convId, me);
        if (agree && other.get("leftTime") == null && !"1".equals(str(other.get("muted"))))
        {
            boolean open = Boolean.TRUE.equals(after.get("open"));
            upsertConvNotice(oid, c, "member", str(userBrief(me).get("name")),
                    open ? "同意了交换联系方式，现在你们都能看到对方发的手机号 / 微信号" : "想和你交换联系方式，打开会话点「同意」后双方都能看到对方发的手机号 / 微信号", true);
        }
        return after;
    }

    /** Every new binary read re-authorizes the active caller, current reception and sender. */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> image(Long me,long convId,long msgId)
    {
        AccountState.requireActive(db,me);
        lockReception(convId);
        requireMember(me,convId,false);
        db.must("select conv_id from kg_chat_conv where conv_id=:c for update",p("c",convId),"会话不存在");
        requireMember(me,convId);
        Map<String,Object> image=db.one("select m.sender_id,i.mime,i.image_bytes from kg_chat_msg m join kg_chat_image i on i.msg_id=m.msg_id "
                +"join kg_app_user u on u.user_id=m.sender_id and u.status='0' and u.del_flag='0' "
                +"where m.msg_id=:m and m.conv_id=:c and m.status='visible'",p("m",msgId,"c",convId));
        if(image==null)throw new ServiceException("图片不存在或已不可用",404);
        guard.requireNotBlocked(me,KgJdbc.asLong(image.get("senderId")));
        return image;
    }

    /** 文件写到私有目录并登记 kg_chat_file（和消息同一事务）：事务回滚时删掉磁盘上的文件；登记失败当场删 */
    private void saveFile(long convId, long msgId, Long me, KgChatFiles.Upload file)
    {
        final String key = files.write(convId, file.file, file.ext);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
        {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization()
                    {
                        @Override
                        public void afterCompletion(int status)
                        {
                            if (status == STATUS_ROLLED_BACK)
                            {
                                files.delete(key);
                            }
                        }
                    });
        }
        int n;
        try
        {
            n = db.update("insert into kg_chat_file(msg_id,conv_id,sender_id,file_key,file_name,file_ext,file_size,input_sha,create_time) "
                    + "values(:m,:c,:u,:k,:n,:e,:s,:h,now())",
                    p("m", msgId, "c", convId, "u", me, "k", key, "n", file.name, "e", file.ext, "s", file.size, "h", file.sha));
        }
        catch (RuntimeException e)
        {
            files.delete(key);
            throw e;
        }
        if (n != 1)
        {
            files.delete(key);
            throw new ServiceException("文件保存失败，请重试", 503);
        }
    }

    /** 下载文件：每次都按当前身份重新核对（在职、接待资格、拉黑、发送人账号），和图片一样 */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> file(Long me, long convId, long msgId)
    {
        AccountState.requireActive(db, me);
        lockReception(convId);
        requireMember(me, convId, false);
        db.must("select conv_id from kg_chat_conv where conv_id = :c for update", p("c", convId), "会话不存在");
        requireMember(me, convId);
        Map<String, Object> f = db.one("select m.sender_id, f.file_key, f.file_name, f.file_ext, f.file_size from kg_chat_msg m join kg_chat_file f on f.msg_id = m.msg_id "
                + "join kg_app_user u on u.user_id = m.sender_id and u.status = '0' and u.del_flag = '0' "
                + "where m.msg_id = :m and m.conv_id = :c and m.status = 'visible'", p("m", msgId, "c", convId));
        if (f == null)
        {
            throw new ServiceException("文件不存在或已不可用", 404);
        }
        guard.requireNotBlocked(me, KgJdbc.asLong(f.get("senderId")));
        return f;
    }

    /**
     * 复制下载链接：3 天内有效，链接认人（签名里带着复制的人）——用浏览器打开时按这个人重新核对还在不在会话里，
     * 接待资格撤销、被拉黑、账号停用后链接也就失效。{url（相对后端根）, expireAt, hours}
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> fileLink(Long me, long convId, long msgId)
    {
        file(me, convId, msgId);
        long exp = System.currentTimeMillis() / 1000 + KgChatFiles.LINK_TTL_SECONDS;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("url", files.sign(msgId, me, exp));
        m.put("expireAt", StaffSupport.fmt(new Date(exp * 1000), "yyyy-MM-dd HH:mm"));
        m.put("hours", KgChatFiles.LINK_TTL_SECONDS / 3600);
        return m;
    }

    /** 匿名签名链接：签名对、没过期，再按链接里的人重新核对（不在会话里了、文件没了都抛错）；签名不对返回 null */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> signedFile(long msgId, long uid, long exp, String sig)
    {
        if (!files.verify(msgId, uid, exp, sig))
        {
            return null;
        }
        Map<String, Object> msg = db.one("select conv_id from kg_chat_msg where msg_id = :m", p("m", msgId));
        if (msg == null)
        {
            throw new ServiceException("文件不存在或已不可用", 404);
        }
        return file(uid, num(msg.get("convId")), msgId);
    }

    static String messageKey(Long me, long convId, Object raw)
    {
        if (raw == null) return null; // Existing clients retain their original contract.
        // Lowercase avoids collation-dependent aliases in the existing unique key.
        if (!(raw instanceof String) || !((String) raw).matches("[a-z0-9_-]{16,64}"))
            throw new ServiceException("消息标识格式不正确", 422);
        String key = "chat:" + me + ":" + convId + ":" + raw;
        if (key.length() > 128) throw new ServiceException("消息标识过长", 422);
        return key;
    }

    private long insertMsg(long convId, Long sender, String role, String text)
    {
        return insertMsg(convId, sender, role, text, text);
    }

    /** preview：会话「最近一条」里显示的文字（私信没交换联系方式时是遮挡后的），消息本身存原文 */
    private long insertMsg(long convId, Long sender, String role, String text, String preview)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("conv_id", convId);
        row.put("sender_id", sender);
        row.put("sender_role", role);
        row.put("content", text);
        row.put("status", "visible");
        row.put("create_time", new Date());
        long id = db.insert("kg_chat_msg", row);
        db.update("update kg_chat_conv set last_msg = :m, last_time = now(), update_time = now() where conv_id = :c", p("m", StaffSupport.cut(oneLine(preview), 255), "c", convId));
        return id;
    }

    /**
     * 站内信里这个会话的那一行（2026-10-15）：每人每个身份下每个会话只有一行。有就更新最新那一行（标题、正文、时间，
     * unread 决定未读还是已读），没有才新插。之前只更新「未读」的那行、读掉后再来消息另插一行，同一会话越积越多；
     * 历史上多出来的旧行在消息列表里隐藏（KgMessageMapper 的 latestChatRow），不删数据。
     * 调用方都已持有这个会话的行锁（for update），同一会话串行，不会并发插出两行。
     * seat 是收件人在会话里的席位：门店会话里员工席位（store）进员工身份消息中心，其余进用户身份。
     */
    @Autowired(required=false) private com.ruoyi.kangaroo.ext.user.KgSubscribeOutbox subscribeOutbox;

    private void upsertConvNotice(Long uid, Map<String, Object> c, String seat, String title, String body, boolean unread)
    {
        String type = str(c.get("convType"));
        long convId = num(c.get("convId"));
        String category = "group".equals(type) ? com.ruoyi.kangaroo.enums.MessageCategory.GROUP : com.ruoyi.kangaroo.enums.MessageCategory.CHAT;
        String mode = "store".equals(seat) || "admin".equals(seat) ? "staff" : "user";
        String readTime = unread ? "null" : "now()";
        Map<String, Object> v = p("t", StaffSupport.cut(title, 64), "b", StaffSupport.cut(body, 500), "u", uid, "m", mode, "c", convId,
                "f", unread ? "0" : "1", "cat", category, "r", "/pages/community/chat?convId=" + convId);
        if (db.update("update kg_message set title = :t, content = :b, create_time = now(), read_flag = :f, read_time = " + readTime
                + " where recipient_id = :u and mode = :m and biz_type = 'chat_conv' and biz_id = :c and del_flag = '0' order by msg_id desc limit 1", v) == 0)
        {
            db.update("insert into kg_message (recipient_id, mode, category, title, content, biz_type, biz_id, route, read_flag, read_time, del_flag, create_time) "
                    + "values (:u, :m, :cat, :t, :b, 'chat_conv', :c, :r, :f, " + readTime + ", '0', now())", v);
        }
        if(unread&&subscribeOutbox!=null) {
            String pref="staff".equals(mode)?"staff:community":"community";
            if(db.count("select count(*) from kg_notify_pref where app_user_id=:u and pref_key=:k and enabled='0'",p("u",uid,"k",pref))==0) {
                Map<String,Object> notice=db.one("select msg_id from kg_message where recipient_id=:u and mode=:m and biz_type='chat_conv' and biz_id=:c and del_flag='0' order by msg_id desc limit 1",v);
                long latest=db.count("select coalesce(max(msg_id),0) from kg_chat_msg where conv_id=:c",p("c",convId));
                if(notice!=null&&latest>0)subscribeOutbox.enqueueEvent(uid,"community",title,"有新的"+("group".equals(type)?"群聊":"会话")+"消息，请打开查看",str(v.get("r")),num(notice.get("msgId")),"chat:"+convId+":"+latest+":"+uid+":"+mode);
            }
        }
    }

    // ---------------------------------------------------------------- 消息页每行的对方和来源（2026-10-31 主人反馈）

    /**
     * 消息页「最近消息」每段会话要显示的三样（会话 ID → {peerName, peerAvatar, sourceLabel}）：名字、头像、名字下面的来源小标签。
     * 读的时候现算，不用迁移，岗位改名也跟着变；kg_message 的 title、content 一个字不动，老版本小程序照旧读那两个。
     * 不按 title 的分隔符拆：岗位名、昵称都可能带「 · 」，title 还截过 64 字，报名人那一侧 title 根本没有来源。
     * 可见范围已由消息列表（currentChatReception）先过滤，这里不再判权。一页最多 50 行，两条 in 查询，不一行一查。
     */
    public Map<Long, Map<String, Object>> inboxFacts(Long me, String mode, Collection<Long> convIds)
    {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<Long, Map<String, Object>>();
        if (convIds == null || convIds.isEmpty())
        {
            return out;
        }
        List<Long> ids = new ArrayList<Long>(convIds);
        List<Map<String, Object>> convs = db.list("select c.conv_id, c.conv_type, c.pair_key, c.store_id, c.source, c.source_id, c.title, s.cover store_cover, j.title job_title "
                + "from kg_chat_conv c left join kg_store s on s.store_id = c.store_id "
                + "left join kg_application a on c.conv_type = 'store' and a.app_id = c.source_id "
                + "left join kg_job j on j.job_id = a.job_id where c.conv_id in (:ids)", p("ids", ids));
        // 群聊的名字和来源都不看成员（一个群可能很多人）
        Map<Long, List<Map<String, Object>>> members = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (Map<String, Object> m : db.list("select m.conv_id, m.app_user_id, u.nickname, u.avatar, u.del_flag from kg_chat_member m join kg_chat_conv c on c.conv_id = m.conv_id "
                + "left join kg_app_user u on u.user_id = m.app_user_id where m.conv_id in (:ids) and c.conv_type in ('store', 'private') and m.role = 'member'", p("ids", ids)))
        {
            members.computeIfAbsent(KgJdbc.asLong(m.get("convId")), k -> new ArrayList<Map<String, Object>>()).add(m);
        }
        // 私信对方注销时成员行已删掉：和会话详情（privatePeer）一样按 pair_key 找回对方，一次 in 查询补上，列表里写「已注销用户」。
        // pair_key 也找不到对方的（真的没有对方）照旧显示会话标题「私信」
        Map<Long, Long> lost = new LinkedHashMap<Long, Long>();
        for (Map<String, Object> c : convs)
        {
            Long convId = KgJdbc.asLong(c.get("convId"));
            if ("private".equals(str(c.get("convType"))) && otherMember(me, members.get(convId)) == null)
            {
                Long peer = pairPeer(c.get("pairKey"), me);
                if (peer != null)
                {
                    lost.put(convId, peer);
                }
            }
        }
        if (!lost.isEmpty())
        {
            Map<Long, Map<String, Object>> users = new java.util.HashMap<Long, Map<String, Object>>();
            for (Map<String, Object> u : db.list("select user_id, nickname, avatar, del_flag from kg_app_user where user_id in (:ids)",
                    p("ids", new ArrayList<Long>(new java.util.LinkedHashSet<Long>(lost.values())))))
            {
                users.put(KgJdbc.asLong(u.get("userId")), u);
            }
            for (Map.Entry<Long, Long> e : lost.entrySet())
            {
                Map<String, Object> u = users.get(e.getValue());
                members.computeIfAbsent(e.getKey(), k -> new ArrayList<Map<String, Object>>()).add(p("convId", e.getKey(), "appUserId", e.getValue(),
                        "nickname", u == null ? null : u.get("nickname"), "avatar", u == null ? null : u.get("avatar"), "delFlag", u == null ? null : u.get("delFlag")));
            }
        }
        for (Map<String, Object> c : convs)
        {
            Long convId = KgJdbc.asLong(c.get("convId"));
            out.put(convId, inboxFact(me, mode, c, members.get(convId)));
        }
        return out;
    }

    /** 群名结尾的这几个字（ensureActivityGroup 写的），标签已经写「活动群」，名字里不重复 */
    private static final String GROUP_SUFFIX = " 活动群";

    /**
     * 一行的规则（纯函数）：
     *  · 门店会话：员工身份看到报名人的昵称（没填写「用户{id}」）和头像，报名人身份看到门店名和门店封面；来源是这段会话当前那份报名的岗位名，
     *    取不到时线上写「线上任务」、线下写「线下报名」；历史混合会话（s 开头的老 pair）写「历史沟通」，当前挂的报名说明不了历史；
     *  · 私信：对方的昵称和头像（对方已注销写「已注销用户」、无头像；完全找不到对方用会话标题）；来源是从哪里发起的（社区帖子、找搭子…），从个人主页或说不清的写「社区私信」；
     *  · 群聊：群名去掉结尾的「活动群」，来源写「活动群」，头像空（小程序显示首字）。
     */
    static Map<String, Object> inboxFact(Long me, String mode, Map<String, Object> c, List<Map<String, Object>> members)
    {
        String type = str(c.get("convType"));
        String name;
        String avatar;
        String source;
        if ("store".equals(type))
        {
            String job = str(c.get("jobTitle")).trim();
            source = KgChatReception.legacy(c) ? "历史沟通" : !job.isEmpty() ? job : "online_task".equals(str(c.get("source"))) ? "线上任务" : "线下报名";
            if ("staff".equals(mode))
            {
                Long applicant = null;
                try
                {
                    applicant = KgChatReception.applicant(c);
                }
                catch (ServiceException e)
                {
                    applicant = null;
                }
                Map<String, Object> peer = memberOf(members, applicant);
                name = applicant == null ? str(c.get("title")) : peerName(peer, applicant);
                avatar = peer == null ? "" : str(peer.get("avatar"));
            }
            else
            {
                name = str(c.get("title"));
                avatar = str(c.get("storeCover"));
            }
        }
        else if ("private".equals(type))
        {
            Map<String, Object> peer = otherMember(me, members);
            // 对方已注销（del_flag 不是 0，和会话详情 peerGone 同一判断）：写「已注销用户」、不带头像；真的没有对方才用会话标题「私信」
            boolean gone = peer != null && !str(peer.get("delFlag")).isEmpty() && !"0".equals(str(peer.get("delFlag")));
            name = peer == null ? str(c.get("title")) : gone ? GONE_NAME : peerName(peer, KgJdbc.asLong(peer.get("appUserId")));
            avatar = peer == null || gone ? "" : str(peer.get("avatar"));
            String from = str(c.get("source"));
            source = "profile".equals(from) || !SOURCE_LABEL.containsKey(from) ? "社区私信" : SOURCE_LABEL.get(from);
        }
        else
        {
            String title = str(c.get("title"));
            name = title.endsWith(GROUP_SUFFIX) ? title.substring(0, title.length() - GROUP_SUFFIX.length()) : title;
            avatar = "";
            source = "活动群";
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("conversationType", type);
        m.put("peerName", name);
        m.put("peerAvatar", avatar);
        m.put("sourceLabel", source);
        return m;
    }

    /** 私信成员里不是我的那一位（没有返回 null） */
    private static Map<String, Object> otherMember(Long me, List<Map<String, Object>> members)
    {
        Map<String, Object> peer = null;
        if (members != null)
        {
            for (Map<String, Object> m : members)
            {
                if (!java.util.Objects.equals(me, KgJdbc.asLong(m.get("appUserId"))))
                {
                    peer = m;
                }
            }
        }
        return peer;
    }

    private static Map<String, Object> memberOf(List<Map<String, Object>> members, Long uid)
    {
        if (members != null && uid != null)
        {
            for (Map<String, Object> m : members)
            {
                if (uid.equals(KgJdbc.asLong(m.get("appUserId"))))
                {
                    return m;
                }
            }
        }
        return null;
    }

    /** 和会话页、消息里发送人的名字同一规则：昵称，没填写「用户{id}」 */
    private static String peerName(Map<String, Object> peer, Long uid)
    {
        String n = peer == null ? "" : str(peer.get("nickname"));
        return n.isEmpty() ? "用户" + uid : n;
    }

    // 门店会话的报名人昵称、员工这一侧的标题（「报名人昵称 · 岗位 / 任务名」）挪到了 KgChatReception.applicantName / staffSideTitle：
    // 「线上接待安排」提醒也要用同一条规则（2026-10-15）

    // ---------------------------------------------------------------- 群详情 / 设置

    public Map<String, Object> groupDetail(Long me, long convId)
    {
        Map<String, Object> base = conversation(me, convId);
        if (!"group".equals(base.get("type")))
        {
            throw new ServiceException("只有群聊有群详情", 409);
        }
        Map<String, Object> c = db.one("select create_time, activity_id from kg_chat_conv where conv_id = :c", p("c", convId));
        base.put("groupNo", String.format("%08d", convId * 7919 % 100000000));
        base.put("createdText", StaffSupport.fmt(StaffSupport.parse(c.get("createTime")), "yyyy-MM-dd"));
        List<Map<String, Object>> members = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select m.app_user_id, m.role, u.nickname, u.avatar from kg_chat_member m left join kg_app_user u on u.user_id = m.app_user_id "
                + "where m.conv_id = :c and m.left_time is null order by m.role = 'owner' desc, m.id limit 60", p("c", convId)))
        {
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("userId", r.get("appUserId"));
            v.put("name", str(r.get("nickname")).isEmpty() ? "用户" + r.get("appUserId") : r.get("nickname"));
            v.put("avatar", r.get("avatar"));
            v.put("admin", "owner".equals(str(r.get("role"))) || "admin".equals(str(r.get("role"))));
            members.add(v);
        }
        base.put("members", members);
        Map<String, Object> code = wxMiniApp.activityCode(KgJdbc.asLong(c.get("activityId")), null);
        base.put("qr", code.get("image"));
        base.put("qrInfo", code);
        Map<String, Object> a = db.one("select store_id, app_user_id from kg_activity where activity_id = :a", p("a", c.get("activityId")));
        long common = a == null ? 0 : db.count("select count(*) from kg_activity_signup g join kg_activity t on t.activity_id = g.activity_id "
                + "where g.app_user_id = :u and g.status = 'confirmed' and (t.store_id = :s or t.app_user_id = :o)",
                p("u", me, "s", a.get("storeId"), "o", a.get("appUserId")));
        base.put("commonCount", common);
        return base;
    }

    public Map<String, Object> setFlag(Long me, long convId, String flag, boolean on)
    {
        requireMember(me, convId);
        String col = "muted".equals(flag) ? "muted" : "pinned";
        db.update("update kg_chat_member set " + col + " = :v where conv_id = :c and app_user_id = :u", p("v", on ? "1" : "0", "c", convId, "u", me));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(col, on);
        return m;
    }

    public String announce(Long me, long convId, String text)
    {
        Map<String, Object> mem = requireMember(me, convId);
        if (!Arrays.asList("owner", "admin").contains(str(mem.get("role"))))
        {
            throw new ServiceException("只有群主可以修改群公告", 403);
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 500)
        {
            throw new ServiceException("群公告为 1–500 字", 422);
        }
        guard.check(t);
        db.update("update kg_chat_conv set announcement = :t, update_time = now() where conv_id = :c", p("t", t, "c", convId));
        insertMsg(convId, 0L, "system", "群主更新了群公告");
        return "群公告已更新";
    }

    public String leave(Long me, long convId)
    {
        Map<String, Object> mem = requireMember(me, convId);
        Map<String, Object> c = db.one("select conv_type from kg_chat_conv where conv_id = :c", p("c", convId));
        if (!"group".equals(str(c.get("convType"))))
        {
            throw new ServiceException("私信不能退出，可以拉黑对方", 409);
        }
        if ("owner".equals(str(mem.get("role"))))
        {
            throw new ServiceException("群主不能退出活动群", 409);
        }
        db.update("update kg_chat_member set left_time = now() where conv_id = :c and app_user_id = :u", p("c", convId, "u", me));
        return "已退出群聊（活动报名不受影响，可在活动页重新进群）";
    }

    // ---------------------------------------------------------------- 举报处置：关闭会话

    @Override
    public String targetType()
    {
        return "chat";
    }

    @Override
    public String uphold(long targetId, String operator)
    {
        db.update("update kg_chat_conv set status = 'closed', update_time = now() where conv_id = :c", p("c", targetId));
        insertMsg(targetId, 0L, "system", "该会话因违反社区公约已被平台关闭");
        return "会话已关闭";
    }
}
