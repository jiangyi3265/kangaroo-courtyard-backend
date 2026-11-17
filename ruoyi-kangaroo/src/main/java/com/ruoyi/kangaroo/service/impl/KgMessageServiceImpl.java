package com.ruoyi.kangaroo.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.mapper.KgMessageMapper;
import com.ruoyi.kangaroo.service.IKgMessageService;

@Service
public class KgMessageServiceImpl implements IKgMessageService
{
    /** 面向 C 端用户的消息身份 */
    private static final String MODE_USER = "user";

    /** 面向门店员工的消息身份 */
    private static final String MODE_STAFF = "staff";

    /** 与 kg_message 列宽对齐，超长在入库前截断，避免严格模式下直接抛 SQL 异常 */
    private static final int TITLE_MAX = 128;

    private static final int CONTENT_MAX = 500;

    private static final int ROUTE_MAX = 128;

    @Autowired
    private KgMessageMapper kgMessageMapper;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.user.KgSubscribeService subscribe;

    @Autowired(required = false)
    private KgJdbc jdbc;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.governance.KgGovernanceDelivery governanceDelivery;

    /**
     * 用户在「我的提醒」里暂停的类别不再写入消息中心；对不上类别的系统通知（审核结果、客服回复等）照常送达。
     * job_new：订阅命中的推送（bizType = job_subscribe）也按这个开关暂停；免打扰 / 每日汇总由订阅推送自己判断。
     */
    static String prefKeyOf(String category, String bizType)
    {
        String b = bizType == null ? "" : bizType;
        if ("member_case".equals(b) || "member_case_result".equals(b) || "merchant_case".equals(b)) return "governance_case";
        if ("mediation".equals(b) || "mediation_jury".equals(b)) return b;
        if ("job_subscribe".equals(b) || "job_urgent".equals(b))
        {
            // 订阅命中的「新机会提醒」：映射到 job_new，才能被「我的提醒」里的开关暂停，也才会触发微信订阅消息
            //（原先没有这条映射，job_new 的订阅推送永远发不出去）
            return "job_new";
        }
        if ("order".equals(category))
        {
            return "order";
        }
        if ("apply".equals(category) || "interview".equals(category) || "application".equals(b))
        {
            return "apply_progress";
        }
        if (b.startsWith("activity"))
        {
            return "activity";
        }
        if ("review".equals(b))
        {
            return "review";
        }
        // 评论、点赞（2026-10-15 起单独分类，comment_like = 赞了评论）也归社区互动开关
        if ("chat".equals(category) || "comment".equals(category) || "like".equals(category)
                || b.startsWith("chat") || b.startsWith("post") || b.startsWith("comment") || "follow".equals(b) || b.startsWith("trip"))
        {
            return "community";
        }
        return null;
    }

    /**
     * 审核结果（动态 / 评论 / 公开行程 / 活动 / 报名 的通过、驳回、隐藏）：和你正在办的事直接相关，任何开关都关不掉。
     * 之前 prefKeyOf 只看 bizType 前缀，动态、行程的审核结果跟点赞评论一起归进「社区互动」开关，关掉这个开关就连审核结果一起不写库了
     * （2026-10-16 内测反馈 2.0 的 bug）。没有单独的列标「这是审核结果」，按发送方写死的标题认，标题改了要同步改这里（有测试钉住）。
     * 微信订阅消息照旧按 prefKeyOf 选模板。
     */
    static boolean reviewResult(String bizType, String title)
    {
        String b = bizType == null ? "" : bizType;
        if ("member_case".equals(b) || "member_case_result".equals(b) || "merchant_case".equals(b)) return true;
        if ("mediation".equals(b) || "mediation_jury".equals(b) || "insurance_finance".equals(b) || "insurance_policy".equals(b)) return true;
        String t = title == null ? "" : title;
        if ("post".equals(b) || "trip_plan".equals(b) || b.startsWith("activity"))
        {
            // 你的动态已通过审核 / 未通过审核 / 已被隐藏，你的评论已通过 / 未通过审核，你的公开行程…，活动已通过审核，开始报名 / 活动未通过审核
            return t.contains("通过审核") || "你的动态已被隐藏".equals(t) || "你的评论已被隐藏".equals(t);
        }
        if ("application".equals(b))
        {
            return "报名审核通过".equals(t) || "报名未通过".equals(t) || "接单申请已通过".equals(t) || "接单申请未通过".equals(t);
        }
        return false;
    }

    /** 「我的提醒」里能暂停这条消息的开关；审核结果返回 null（关不掉），其余同 prefKeyOf */
    static String prefKeyOf(String category, String bizType, String title)
    {
        return reviewResult(bizType, title) ? null : prefKeyOf(category, bizType);
    }

    private boolean paused(Long userId, String mode, String category, String bizType)
    {
        return paused(userId, mode, category, bizType, null);
    }

    private boolean paused(Long userId, String mode, String category, String bizType, String title)
    {
        String key = prefKeyOf(category, bizType, title);
        if (key == null || jdbc == null)
        {
            return false;
        }
        // 员工身份的开关单独存一份（staff: 前缀），同一个人可以"员工通知要、个人通知不要"，反过来也行
        if (MODE_STAFF.equals(mode))
        {
            key = "staff:" + key;
        }
        Map<String, Object> r = jdbc.one("select enabled from kg_notify_pref where app_user_id = :u and pref_key = :k", KgJdbc.p("u", userId, "k", key));
        return r != null && "0".equals(String.valueOf(r.get("enabled")));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Long send(Long recipientId, String mode, String category, String title, String content,
            String bizType, Long bizId, String route)
    {
        // 自调用不经过代理：两个入口都标事务，保持原来「消息和发送待办同一个事务」
        return send(recipientId, mode, category, title, content, bizType, bizId, route, null);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Long send(Long recipientId, String mode, String category, String title, String content,
            String bizType, Long bizId, String route, Long actorId)
    {
        if (recipientId == null)
        {
            // 发消息是报名、面试等主流程的附属动作，收件人缺失时静默跳过，不能连累主业务回滚
            return null;
        }
        if (StringUtils.isEmpty(title))
        {
            throw new ServiceException("消息标题不能为空");
        }
        if (paused(recipientId, normalizeMode(mode), category, bizType, title))
        {
            return null;
        }

        KgMessage message = new KgMessage();
        message.setRecipientId(recipientId);
        message.setMode(normalizeMode(mode));
        message.setCategory(StringUtils.isEmpty(category) ? MessageCategory.SYSTEM : category);
        message.setTitle(truncate(title, TITLE_MAX));
        // 正文常由业务数据拼接而成，长度不可控，截断比报错更符合“消息不打断主流程”的定位
        message.setContent(content == null ? "" : truncate(content, CONTENT_MAX));
        message.setBizType(bizType);
        message.setBizId(bizId);
        message.setActorId(actorId);
        message.setRoute(truncate(route, ROUTE_MAX));
        // 显式写死而不是依赖库表默认值：小程序的未读数、列表都以这两列为过滤条件
        message.setReadFlag("0");
        message.setDelFlag("0");
        kgMessageMapper.insertKgMessage(message);
        if (governanceDelivery != null) governanceDelivery.enqueue(recipientId, message.getMsgId(), bizType);
        // 同事务记录发送待办；只有提交后才调用微信，业务回滚时不会外发。
        if (subscribe != null)
        {
            String prefKey = com.ruoyi.kangaroo.ext.user.KgSubscribeScenes.of(category,bizType,title,route);
            if (prefKey != null)
            {
                subscribe.push(recipientId, prefKey, message.getTitle(), message.getContent(), message.getRoute(), message.getMsgId());
            }
        }
        return message.getMsgId();
    }

    @Override
    public List<KgMessage> selectAppMessages(Long recipientId, String mode, String category)
    {
        if (recipientId == null)
        {
            return new ArrayList<KgMessage>();
        }
        return kgMessageMapper.selectAppMessages(recipientId, normalizeMode(mode), category);
    }

    @Override
    public List<KgMessage> selectAppMessagePage(Long recipientId, String mode, String category, String bucket, int offset, int limit)
    {
        if (recipientId == null)
        {
            return new ArrayList<KgMessage>();
        }
        return kgMessageMapper.selectAppMessagePage(recipientId, normalizeMode(mode), category, bucket, Math.max(0, offset), Math.max(1, limit));
    }

    @Override
    public int countUnread(Long recipientId, String mode)
    {
        if (recipientId == null)
        {
            return 0;
        }
        return kgMessageMapper.countUnread(recipientId, normalizeMode(mode));
    }

    @Override
    public int markRead(Long msgId, Long recipientId)
    {
        if (msgId == null || recipientId == null)
        {
            return 0;
        }
        // 归属校验放在 update 的 where 里：一次原子操作，且标记他人消息只会返回 0，
        // 不抛错也不区分“不存在”和“不是你的”，防止靠消息ID探测他人数据
        return kgMessageMapper.markRead(msgId, recipientId);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public int deleteOwnMessage(Long msgId, Long recipientId, String mode)
    {
        if (msgId == null || recipientId == null) return 0;
        String realMode = normalizeMode(mode);
        KgMessage message = kgMessageMapper.selectOwnMessageForDelete(msgId, recipientId, realMode);
        if (message == null) return 0;
        Long chatId = "chat_conv".equals(message.getBizType()) ? message.getBizId() : null;
        return kgMessageMapper.deleteOwnMessage(msgId, recipientId, realMode, chatId);
    }

    @Override
    public int markAllRead(Long recipientId, String mode)
    {
        return markAllRead(recipientId, mode, null);
    }

    @Override
    public int markAllRead(Long recipientId, String mode, String bucket)
    {
        return markAllRead(recipientId, mode, bucket, null);
    }

    @Override
    public int markAllRead(Long recipientId, String mode, String bucket, Long maxId)
    {
        if (recipientId == null)
        {
            return 0;
        }
        return kgMessageMapper.markAllRead(recipientId, normalizeMode(mode), StringUtils.isEmpty(bucket) ? null : bucket, maxId);
    }

    @Override
    public List<Long> selectUnreadIds(Long recipientId, String mode, String bucket, Long maxId, int limit)
    {
        if (recipientId == null || limit <= 0)
        {
            return new ArrayList<Long>();
        }
        return kgMessageMapper.selectUnreadIds(recipientId, normalizeMode(mode), StringUtils.isEmpty(bucket) ? null : bucket, maxId, limit);
    }

    @Override
    public List<Map<String, Object>> countByCategory(Long recipientId, String mode)
    {
        if (recipientId == null)
        {
            return new ArrayList<Map<String, Object>>();
        }
        return kgMessageMapper.countByCategory(recipientId, normalizeMode(mode));
    }

    @Override
    public List<Map<String, Object>> countByBucket(Long recipientId, String mode)
    {
        if (recipientId == null)
        {
            return new ArrayList<Map<String, Object>>();
        }
        return kgMessageMapper.countByBucket(recipientId, normalizeMode(mode));
    }

    // ---------------------------------------------------------------- 点赞通知（2026-10-15）

    /** 点赞通知的对象：biz_type → {点赞表, 对象列, 主键列, 标题里的叫法} */
    static String[] likeTarget(String bizType)
    {
        if ("post_like".equals(bizType))
        {
            return new String[] { "kg_post_like", "post_id", "like_id", "动态" };
        }
        if ("comment_like".equals(bizType))
        {
            return new String[] { "kg_post_comment_like", "comment_id", "id", "评论" };
        }
        return null;
    }

    /** 「晨光 赞了你的动态」/「晨光 等 3 人赞了你的动态」 */
    static String likeTitle(String name, long count, String noun)
    {
        return name + (count > 1 ? " 等 " + count + " 人" : " ") + "赞了你的" + noun;
    }

    /** 和收件人有拉黑关系（任一方向）的人点的赞不算：拉黑之前点的赞还留在点赞表里 */
    private static final String LIKER_NOT_BLOCKED = " and not exists (select 1 from kg_user_block k "
            + "where (k.user_id = :r and k.blocked_id = l.app_user_id) or (k.user_id = l.app_user_id and k.blocked_id = :r))";

    /**
     * 已注销 / 停用账号点的赞不算（2026-10-15）：注销会删掉本人的点赞，这里再兜一层，
     * 防止「晨光 等 3 人」把已注销的人算进去、或者把通知的触发人指回已注销的人。
     * 用 exists 而不是 join：前面的 SQL 前缀（from 点赞表 l where ...）保持不变。
     */
    private static final String LIKER_ACTIVE = " and exists (select 1 from kg_app_user au "
            + "where au.user_id = l.app_user_id and au.status = '0' and au.del_flag = '0')";

    /** 这一轮的点赞人：since（上次读这条通知的时间）之后点的，不含收件人自己；include 是刚点赞的人，同一秒内读过也要算上 */
    private static String likersWhere(String[] t, Long include)
    {
        return " from " + t[0] + " l where l." + t[1] + " = :b and l.app_user_id <> :r and (:since is null or l.create_time > :since"
                + (include == null ? "" : " or l.app_user_id = :a") + ")" + LIKER_NOT_BLOCKED + LIKER_ACTIVE;
    }

    private String nickname(Long userId)
    {
        Map<String, Object> u = jdbc.one("select nickname from kg_app_user where user_id = :u", KgJdbc.p("u", userId));
        String name = u == null ? null : KgJdbc.asStr(u.get("nickname"));
        // 和社区作者一样：没填昵称写「用户{id}」
        return StringUtils.isEmpty(name) ? "用户" + userId : name;
    }

    /** 收件人在这个对象上的那一行（最新一行；upsert 时连删掉的也算：取消到 0 人后再有人赞就把它恢复，read_time 这个起点不丢） */
    private Map<String, Object> likeRow(Long recipientId, String bizType, Long bizId, boolean aliveOnly)
    {
        return jdbc.one("select msg_id, read_flag, read_time from kg_message where recipient_id = :r and mode = 'user' "
                + "and biz_type = :t and biz_id = :b" + (aliveOnly ? " and del_flag = '0'" : "") + " order by msg_id desc limit 1",
                KgJdbc.p("r", recipientId, "t", bizType, "b", bizId));
    }

    @Override
    public void upsertLike(Long recipientId, String bizType, Long bizId, Long actorId, String content, String route)
    {
        String[] t = likeTarget(bizType);
        if (t == null || jdbc == null || recipientId == null || bizId == null || actorId == null || recipientId.equals(actorId))
        {
            return;
        }
        if (paused(recipientId, MODE_USER, MessageCategory.LIKE, bizType))
        {
            return;
        }
        Map<String, Object> row = likeRow(recipientId, bizType, bizId, false);
        Object since = row == null ? null : row.get("readTime");
        long count = jdbc.count("select count(*)" + likersWhere(t, actorId), KgJdbc.p("b", bizId, "r", recipientId, "since", since, "a", actorId));
        Map<String, Object> v = KgJdbc.p("r", recipientId, "t", bizType, "b", bizId, "a", actorId,
                "title", truncate(likeTitle(nickname(actorId), Math.max(1L, count), t[3]), TITLE_MAX),
                "c", content == null ? "" : truncate(content, CONTENT_MAX), "route", truncate(route, ROUTE_MAX));
        if (row == null)
        {
            // 不走 send()：点赞永远不发微信订阅消息
            jdbc.update("insert into kg_message (recipient_id, mode, category, title, content, biz_type, biz_id, actor_id, route, read_flag, del_flag, create_time) "
                    + "values (:r, 'user', 'like', :title, :c, :t, :b, :a, :route, '0', '0', now())", v);
        }
        else
        {
            v.put("id", row.get("msgId"));
            jdbc.update("update kg_message set category = 'like', title = :title, content = :c, actor_id = :a, route = :route, "
                    + "read_flag = '0', del_flag = '0', create_time = now() where msg_id = :id", v);
        }
    }

    @Override
    public void retractLike(Long recipientId, String bizType, Long bizId, Long actorId)
    {
        String[] t = likeTarget(bizType);
        if (t == null || jdbc == null || recipientId == null || bizId == null || recipientId.equals(actorId))
        {
            return;
        }
        if (paused(recipientId, MODE_USER, MessageCategory.LIKE, bizType))
        {
            return;
        }
        Map<String, Object> row = likeRow(recipientId, bizType, bizId, true);
        // 读过的不改：「晨光 等 3 人」读过以后有人取消，也不会变成 2 人
        if (row == null || !"0".equals(KgJdbc.asStr(row.get("readFlag"))))
        {
            return;
        }
        relinkLike(row.get("msgId"), recipientId, bizId, t, row.get("readTime"));
    }

    /**
     * 按这一轮剩下的点赞人重写一条未读的点赞通知：一个都不剩就删掉（del_flag = 2），
     * 否则标题改成剩下的人数、触发人指向剩下的人里最近点赞的那个；时间不动。
     */
    private void relinkLike(Object msgId, Long recipientId, Long bizId, String[] t, Object readTime)
    {
        Map<String, Object> ps = KgJdbc.p("b", bizId, "r", recipientId, "since", readTime, "id", msgId);
        long count = jdbc.count("select count(*)" + likersWhere(t, null), ps);
        if (count == 0)
        {
            jdbc.update("update kg_message set del_flag = '2' where msg_id = :id", ps);
            return;
        }
        Map<String, Object> last = jdbc.one("select l.app_user_id" + likersWhere(t, null) + " order by l.create_time desc, l." + t[2] + " desc limit 1", ps);
        Long lastId = last == null ? null : KgJdbc.asLong(last.get("appUserId"));
        if (lastId == null)
        {
            return;
        }
        ps.put("a", lastId);
        ps.put("title", truncate(likeTitle(nickname(lastId), count, t[3]), TITLE_MAX));
        // 只改标题和最近点赞的人，时间不动
        jdbc.update("update kg_message set title = :title, actor_id = :a where msg_id = :id", ps);
    }

    @Override
    public void relinkLikesOf(Long userId)
    {
        if (jdbc == null || userId == null)
        {
            return;
        }
        // 只动没读过的合并行（读过的不改，和取消点赞一样）；调用方要先删掉这个人的点赞，剩下的人才是「这一轮剩下的」
        relinkRows(jdbc.list("select msg_id, recipient_id, biz_type, biz_id, read_time from kg_message "
                + "where actor_id = :u and biz_type in ('post_like', 'comment_like') and read_flag = '0' and del_flag = '0' order by msg_id",
                KgJdbc.p("u", userId)));
    }

    @Override
    public void relinkLikesBetween(Long recipientId, Long actorId)
    {
        if (jdbc == null || recipientId == null || actorId == null || recipientId.equals(actorId))
        {
            return;
        }
        // 调用方先写好拉黑记录：likersWhere 不算和收件人有拉黑关系的人，剩下的才是「还能显示的点赞人」
        relinkRows(jdbc.list("select msg_id, recipient_id, biz_type, biz_id, read_time from kg_message "
                + "where recipient_id = :r and actor_id = :a and biz_type in ('post_like', 'comment_like') and read_flag = '0' and del_flag = '0' order by msg_id",
                KgJdbc.p("r", recipientId, "a", actorId)));
    }

    /** 逐行按这一轮剩下的点赞人重写（行来自上面两个查询：未读、没删的点赞合并行） */
    private void relinkRows(List<Map<String, Object>> rows)
    {
        for (Map<String, Object> row : rows)
        {
            String[] t = likeTarget(KgJdbc.asStr(row.get("bizType")));
            Long recipientId = KgJdbc.asLong(row.get("recipientId"));
            Long bizId = KgJdbc.asLong(row.get("bizId"));
            if (t == null || recipientId == null || bizId == null)
            {
                continue;
            }
            relinkLike(row.get("msgId"), recipientId, bizId, t, row.get("readTime"));
        }
    }

    @Override
    public List<KgMessage> selectKgMessageList(KgMessage kgMessage)
    {
        return kgMessageMapper.selectKgMessageList(kgMessage);
    }

    @Override
    public KgMessage selectKgMessageByMsgId(Long msgId)
    {
        return kgMessageMapper.selectKgMessageByMsgId(msgId);
    }

    /**
     * mode 只有 user / staff 两个取值。写错了消息会被小程序的 visibleMessages 永久过滤掉，
     * 是查不出来的“静默丢消息”，所以这里宁可抛错让问题在联调期暴露。
     */
    private String normalizeMode(String mode)
    {
        if (StringUtils.isEmpty(mode))
        {
            return MODE_USER;
        }
        if (!MODE_USER.equals(mode) && !MODE_STAFF.equals(mode))
        {
            throw new ServiceException("消息身份非法：" + mode);
        }
        return mode;
    }

    private String truncate(String value, int maxLength)
    {
        if (value == null || value.length() <= maxLength)
        {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
