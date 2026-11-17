package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.util.AccountState;

/** 公告回复是一对一会话。所有用户查询均绑定登录账号，不接收客户端指定的用户 ID。 */
@Service
public class KgNoticeReplyService
{
    @Autowired private KgJdbc db;
    @Autowired private StaffSupport support;

    public Map<String, Object> mine(Long userId, Long noticeId, Long before)
    {
        Map<String, Object> thread = own(userId, noticeId);
        if (thread == null)
        {
            visible(noticeId);
            return p("thread", null, "rows", Collections.emptyList(), "hasMore", false);
        }
        return history(thread, before);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> send(Long userId, Long noticeId, Map<String, Object> body, boolean staff)
    {
        String content = text(body), requestId = requestId(body);
        AccountState.requireActive(db, userId);
        Map<String, Object> thread = own(userId, noticeId);
        if (thread == null)
        {
            Map<String, Object> n = visible(noticeId);
            long id = db.insert("kg_notice_thread", p("notice_id", noticeId, "app_user_id", userId,
                    "notice_title", n.get("title"), "reply_mode", staff ? "staff" : "user", "pending", "1",
                    "last_content", content, "create_time", new Date(), "update_time", new Date()));
            thread = p("threadId", id);
        }
        Long id = KgJdbc.asLong(thread.get("threadId"));
        Map<String, Object> duplicate = duplicate(id, "user", userId, requestId, content);
        if (duplicate != null) return duplicate;
        // 同一账号持有行锁，连点和并发重试都不会创建重复对话 / 消息。
        if (db.count("select count(*) from kg_notice_reply r join kg_notice_thread t on t.thread_id=r.thread_id "
                + "where t.app_user_id=:u and r.sender='user' and r.create_time > date_sub(now(), interval 1 minute)", p("u", userId)) >= 10)
            throw new ServiceException("发送较频繁，请稍后再试", 429);
        Map<String, Object> message = append(id, "user", userId, requestId, content);
        db.update("update kg_notice_thread set pending='1', last_content=:c, reply_mode=:m, update_time=now() where thread_id=:t",
                p("c", content, "m", staff ? "staff" : "user", "t", id));
        return message;
    }

    public Map<String, Object> list(String pending, String keyword, Integer pageNum, Integer pageSize)
    {
        String where = " where u.status='0' and u.del_flag='0'";
        Map<String, Object> args = p();
        if ("1".equals(pending) || "0".equals(pending)) { where += " and t.pending=:p"; args.put("p", pending); }
        if (keyword != null && !keyword.trim().isEmpty())
        {
            where += " and (t.notice_title like :q or u.nickname like :q or cast(t.app_user_id as char)=:uid)";
            args.put("q", "%" + StaffSupport.cut(keyword.trim(), 64) + "%"); args.put("uid", keyword.trim());
        }
        return db.page("select t.*, u.nickname from kg_notice_thread t join kg_app_user u on u.user_id=t.app_user_id"
                + where + " order by t.pending desc, t.update_time desc, t.thread_id desc", args, pageNum, pageSize);
    }

    public Map<String, Object> detail(Long threadId, Long before)
    {
        return history(thread(threadId), before);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reply(Long threadId, Long operator, Map<String, Object> body)
    {
        String content = text(body), requestId = requestId(body);
        Map<String, Object> initial = thread(threadId);
        Long userId = KgJdbc.asLong(initial.get("appUserId"));
        AccountState.requireActive(db, userId);
        // 锁顺序与账号注销、用户发送一致：账号 -> 对话。
        Map<String, Object> t = db.one("select * from kg_notice_thread where thread_id=:t for update", p("t", threadId));
        if (t == null) throw new ServiceException("对话不存在", 404);
        Map<String, Object> duplicate = duplicate(threadId, "admin", operator, requestId, content);
        if (duplicate != null) return duplicate;
        Map<String, Object> message = append(threadId, "admin", operator, requestId, content);
        db.update("update kg_notice_thread set pending='0', last_content=:c, update_time=now() where thread_id=:t", p("c", content, "t", threadId));
        String route = "/pages/notice/index?id=" + t.get("noticeId");
        Long replyId = KgJdbc.asLong(message.get("replyId"));
        // 还有员工身份：和登录下发的授权同一口径，按城市授权（本城还没门店）也算
        boolean staff = "staff".equals(t.get("replyMode")) && db.count(com.ruoyi.kangaroo.ext.staff.CityGrantScope.COUNT_ACTIVE_IDENTITY, p("u", userId)) > 0;
        if (staff) support.notifyStaff(userId, "system", "管理员回复了你的公告留言", StaffSupport.cut(content, 80), "notice_reply", replyId, route);
        else support.notifyUser(userId, "system", "管理员回复了你的公告留言", StaffSupport.cut(content, 80), "notice_reply", replyId, route);
        return message;
    }

    /** 只清除已实际加载到的回复，避免并发的新回复被一并标成已读。 */
    public void read(Long userId, Long noticeId, Long through)
    {
        Map<String, Object> t = own(userId, noticeId);
        if (t == null || through == null || through <= 0) return;
        db.update("update kg_message set read_flag='1', read_time=now() where recipient_id=:u and biz_type='notice_reply' and read_flag='0' "
                + "and biz_id in (select reply_id from kg_notice_reply where thread_id=:t and sender='admin' and reply_id<=:r)",
                p("u", userId, "t", t.get("threadId"), "r", through));
    }

    private Map<String, Object> own(Long userId, Long noticeId)
    {
        return db.one("select * from kg_notice_thread where notice_id=:n and app_user_id=:u", p("n", noticeId, "u", userId));
    }

    private Map<String, Object> visible(Long noticeId)
    {
        Map<String, Object> n = db.one("select n.notice_id, n.title from kg_app_notice n where n.notice_id=:n and " + KgAppNoticeService.VISIBLE, p("n", noticeId));
        if (n == null) throw new ServiceException("公告已下线，不能发起新的对话", 404);
        return n;
    }

    private Map<String, Object> thread(Long id)
    {
        Map<String, Object> t = db.one("select t.*, u.nickname from kg_notice_thread t join kg_app_user u on u.user_id=t.app_user_id "
                + "where t.thread_id=:t and u.status='0' and u.del_flag='0'", p("t", id));
        if (t == null) throw new ServiceException("对话不存在或账号已停用", 404);
        return t;
    }

    private Map<String, Object> history(Map<String, Object> thread, Long before)
    {
        Long id = KgJdbc.asLong(thread.get("threadId"));
        List<Map<String, Object>> rows = db.list("select reply_id, sender, content, create_time from kg_notice_reply where thread_id=:t"
                + (before != null && before > 0 ? " and reply_id<:b" : "") + " order by reply_id desc limit 51", p("t", id, "b", before));
        boolean more = rows.size() > 50;
        if (more) rows = new ArrayList<>(rows.subList(0, 50));
        Collections.reverse(rows);
        // 不把管理员账号或内部去重标识下发给小程序。
        return p("thread", p("threadId", id, "noticeId", thread.get("noticeId"), "noticeTitle", thread.get("noticeTitle"),
                "nickname", thread.get("nickname"), "appUserId", thread.get("appUserId"), "pending", thread.get("pending")), "rows", rows, "hasMore", more);
    }

    private Map<String, Object> duplicate(Long id, String sender, Long senderId, String key, String content)
    {
        Map<String, Object> old = db.one("select reply_id, sender, content, create_time from kg_notice_reply "
                + "where thread_id=:t and sender=:s and sender_id=:u and request_id=:k for update", p("t", id, "s", sender, "u", senderId, "k", key));
        if (old != null && !content.equals(old.get("content"))) throw new ServiceException("发送标识已使用，请重新发送", 409);
        return old;
    }

    private Map<String, Object> append(Long id, String sender, Long senderId, String key, String content)
    {
        long replyId = db.insert("kg_notice_reply", p("thread_id", id, "sender", sender, "sender_id", senderId,
                "request_id", key, "content", content, "create_time", new Date()));
        return db.one("select reply_id, sender, content, create_time from kg_notice_reply where reply_id=:r", p("r", replyId));
    }

    private String text(Map<String, Object> body)
    {
        String s = body == null || body.get("content") == null ? "" : String.valueOf(body.get("content")).trim();
        if (s.isEmpty() || s.length() > 1000) throw new ServiceException("请填写 1–1000 字的回复内容", 422);
        return s;
    }

    private String requestId(Map<String, Object> body)
    {
        String s = body == null || body.get("requestId") == null ? "" : String.valueOf(body.get("requestId"));
        if (!s.matches("[A-Za-z0-9_-]{8,64}")) throw new ServiceException("发送标识无效，请重新发送", 422);
        return s;
    }
}
