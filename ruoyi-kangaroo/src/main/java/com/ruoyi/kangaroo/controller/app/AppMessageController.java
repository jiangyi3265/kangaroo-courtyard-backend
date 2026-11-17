package com.ruoyi.kangaroo.controller.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.kangaroo.domain.KgMessage;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgMessageService;

/**
 * 小程序消息中心接口。
 *
 * mode 参数决定看的是用户端消息还是员工端消息，与 experience.mode 对应。
 * 请求 staff 模式的消息时必须确实持有员工授权，否则按 user 处理 ——
 * 不能靠传参就切换到员工视角。
 *
 * 2026-10-15 消息页改版：/list 可选 bucket（分组）、page、size，分页时多回 hasMore；/summary 多回各组的 buckets；
 * /read-all 可选 bucket。不带这些新参数时 /list、/read-all 的返回和以前逐字节一样，老版本小程序照常用。
 *
 * 2026-10-31 消息来源：分页的 /list 里，会话那一行（biz_type = chat_conv）多回 peerName、peerAvatar、sourceLabel，title、content 不变。
 */
@RestController
@RequestMapping("/app/message")
@AppAuth
public class AppMessageController
{
    @Autowired private com.ruoyi.kangaroo.ext.staff.CityLeadScope messageCities;
    @ModelAttribute
    public void selectMessageScope(@RequestHeader(value="X-Message-Scope",required=false) String scope) {
        AppUserContext.messageScope(null,null);
        if("user".equals(scope)){AppUserContext.messageScope(null,"user");return;}
        if(scope==null||scope.isEmpty())return; // Legacy clients use least-privilege lodging ACL.
        AppLoginUser me=AppUserContext.require();
        if(scope.startsWith(com.ruoyi.kangaroo.security.ServiceCityMessageScope.PREFIX)) {
            AppUserContext.messageScope(com.ruoyi.kangaroo.security.ServiceCityMessageScope.stores(me,scope),"merchant_service");
            return;
        }
        com.ruoyi.kangaroo.security.StaffGrantView selected=null;
        for(com.ruoyi.kangaroo.security.StaffGrantView g:me.getStaffGrants()) {
            String key=(g.getStoreId()!=null?"store:"+g.getStoreId():"city:"+g.getCityId())+":"+g.getRoleCode();
            if(Boolean.TRUE.equals(g.getActive())&&key.equals(scope)){selected=g;break;}
        }
        if(selected==null)throw new ServiceException("当前身份授权已变化，请重新选择身份",403);
        List<Long> ids=new ArrayList<>();
        if(selected.getStoreId()!=null)ids.add(selected.getStoreId());
        else if("city_lead".equals(selected.getRoleCode())) {
            for(Map.Entry<Long,Map<String,Object>> e:messageCities.stores(me.getUserId()).entrySet())
                if(messageCities.town(selected.getCity()).equals(messageCities.town(e.getValue().get("city"))))ids.add(e.getKey());
        }
        if(ids.isEmpty())ids.add(-1L);
        AppUserContext.messageScope(ids,selected.getRoleCode());
    }
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AppMessageController.class);

    @Autowired
    private IKgMessageService kgMessageService;

    /** 会话行的对方和来源（2026-10-31）；没注入（单测）或查失败时不带这三个字段，小程序退回 title */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.community.KgChatService chats;

    /** 消息页的分组：用户身份上方「系统通知 / 评论 / 赞」三个入口 + 下面的列表 main；员工身份是「系统通知 / 报名 / 订单」+ 列表 */
    static final List<String> USER_BUCKETS = Arrays.asList("main", "system", "comment", "like");
    static final List<String> STAFF_BUCKETS = Arrays.asList("main", "system", "apply", "order");

    /** 一页默认 20 条，最多 50 条 */
    static final int PAGE_SIZE = 20;
    static final int PAGE_SIZE_MAX = 50;
    /** read-all 带 maxId 时最多回多少个未读ID：一页 20 条，够往下翻 25 页 */
    static final int UNREAD_IDS_MAX = 500;

    @Autowired private com.ruoyi.kangaroo.mapper.KgMessageMapper messageMapper;

    @GetMapping("/subscription/{id}")
    public AjaxResult subscriptionMessage(@PathVariable Long id,@RequestParam(defaultValue="user") String mode) {
        AppLoginUser me=AppUserContext.require();
        String actual=resolveMode(me,mode);
        if(!actual.equals(mode))throw new ServiceException("当前身份无权查看此提醒",403);
        KgMessage message=messageMapper.selectVisibleMessage(id,me.getUserId(),actual);
        if(message==null)throw new ServiceException("该提醒不存在或不在当前身份权限内",404);
        Map<String,Object> data=new LinkedHashMap<>();data.put("id",message.getMsgId());data.put("title",message.getTitle());data.put("content",message.getContent());data.put("route",message.getRoute());data.put("mode",actual);
        return AjaxResult.success(data);
    }

    @AppAuth(staff=true,merchant=true,onlineStaff=true,merchantService=true,cityLead=true)
    @GetMapping("/application-threads")
    public AjaxResult applicationThreads(@RequestParam(required=false) Long personId,@RequestParam(defaultValue="1") int page) {
        Long me=AppUserContext.requireStaff().getUserId();
        int offset=(Math.min(10000,Math.max(1,page))-1)*20;
        List<Map<String,Object>> rows=new ArrayList<>();
        if(personId==null) {
            rows=messageMapper.selectStaffApplicationThreads(me,offset,21);
            for(Map<String,Object> row:rows) {
                row.put("read",!(row.get("unreadCount") instanceof Number)||((Number)row.get("unreadCount")).longValue()==0);
                row.put("category","apply");
            }
        } else {
            if(personId<=0)throw new ServiceException("报名人编号无效",422);
            for(KgMessage m:messageMapper.selectStaffApplicationThreadMessages(me,personId,offset,21)) {
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("id",m.getMsgId());row.put("title",m.getTitle());row.put("content",m.getContent());
                row.put("route",m.getRoute());row.put("createTime",m.getCreateTime());row.put("category",m.getCategory());
                row.put("read","1".equals(m.getReadFlag()));rows.add(row);
            }
        }
        boolean more=rows.size()>20;
        AjaxResult out=AjaxResult.success();out.put("rows",more?rows.subList(0,20):rows);out.put("hasMore",more);return out;
    }

    /**
     * 消息列表。
     * 不带 bucket / page / size：和以前一样一次返回当前身份（可按 category 过滤）的全部消息。
     * 带任意一个：按 bucket 分组（可不带）分页，每页 size 条（默认 20，最多 50），多回 hasMore；
     * 评论 / 赞两类的行另带 actorId、actorName、actorAvatar、postId、cover、postTitle、quote。
     */
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String mode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size)
    {
        AppLoginUser me = AppUserContext.require();
        String realMode = resolveMode(me, mode);
        if (revokedStaffBucket(mode, realMode, bucket))
        {
            // 员工授权刚被撤销、小程序还停在员工身份：回一页空的并带上 mode=user，不报「分组不对」让页面显示加载失败
            AjaxResult empty = AjaxResult.success();
            empty.put("rows", new ArrayList<Map<String, Object>>());
            empty.put("unread", Integer.valueOf(kgMessageService.countUnread(me.getUserId(), realMode)));
            empty.put("mode", realMode);
            empty.put("hasMore", Boolean.FALSE);
            return empty;
        }
        String cat = StringUtils.isEmpty(category) ? null : category;
        boolean paged = StringUtils.isNotEmpty(bucket) || page != null || size != null;
        String group = checkBucket(realMode, bucket);
        int pageSize = size == null ? PAGE_SIZE : Math.max(1, Math.min(PAGE_SIZE_MAX, size.intValue()));
        // 页码设上限：(页码 - 1) * 每页条数 不能在 int 里溢出成负数
        int pageNum = page == null ? 1 : Math.max(1, Math.min(10000, page.intValue()));
        List<KgMessage> list = paged
                ? kgMessageService.selectAppMessagePage(me.getUserId(), realMode, cat, group, (pageNum - 1) * pageSize, pageSize + 1)
                : kgMessageService.selectAppMessages(me.getUserId(), realMode, cat);
        // 多取一条：取到了就说明还有下一页
        boolean hasMore = paged && list != null && list.size() > pageSize;
        if (hasMore)
        {
            list = list.subList(0, pageSize);
        }

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (list != null)
        {
            for (KgMessage m : list)
            {
                Map<String, Object> row = new HashMap<String, Object>();
                row.put("id", m.getMsgId());
                // recipientId 下发成字符串，与 experience.account.id 的类型保持一致，
                // 这样页面里的 m.recipientId === account.id 严格相等判断才成立
                row.put("recipientId", String.valueOf(m.getRecipientId()));
                row.put("mode", m.getMode());
                row.put("category", m.getCategory());
                row.put("title", m.getTitle());
                row.put("content", listContent(m));
                row.put("route", m.getRoute());
                row.put("bizType", m.getBizType());
                row.put("bizId", m.getBizId());
                row.put("read", Boolean.valueOf("1".equals(m.getReadFlag())));
                // Read messages remain available in paginated history.
                row.put("createTime", m.getCreateTime());
                if (paged && isInteraction(m))
                {
                    putInteraction(row, m);
                }
                rows.add(row);
            }
        }
        if (paged)
        {
            putInboxFacts(rows, me.getUserId(), realMode);
        }
        AjaxResult ajax = AjaxResult.success();
        ajax.put("rows", rows);
        ajax.put("unread", Integer.valueOf(kgMessageService.countUnread(me.getUserId(), realMode)));
        ajax.put("mode", realMode);
        if (paged)
        {
            ajax.put("hasMore", Boolean.valueOf(hasMore));
        }
        return ajax;
    }

    /** 会话行的对方名字、头像、来源：这不是主流程，查失败只记日志，不能让消息列表失败 */
    private void putInboxFacts(List<Map<String, Object>> rows, Long me, String mode)
    {
        if (chats == null)
        {
            return;
        }
        List<Long> convIds = new ArrayList<Long>();
        for (Map<String, Object> row : rows)
        {
            if ("chat_conv".equals(row.get("bizType")) && row.get("bizId") != null && !convIds.contains(row.get("bizId")))
            {
                convIds.add((Long) row.get("bizId"));
            }
        }
        if (convIds.isEmpty())
        {
            return;
        }
        Map<Long, Map<String, Object>> facts;
        try
        {
            facts = chats.inboxFacts(me, mode, convIds);
        }
        catch (RuntimeException e)
        {
            log.warn("消息列表的会话来源没有查到 user={} mode={}", me, mode, e);
            return;
        }
        for (Map<String, Object> row : rows)
        {
            Map<String, Object> fact = "chat_conv".equals(row.get("bizType")) ? facts.get(row.get("bizId")) : null;
            if (fact != null)
            {
                row.putAll(fact);
            }
        }
    }

    /**
     * 各分类的总数与未读数（categories，老字段原样保留），
     * 加上消息页各组的总数与未读数 buckets：{分组: {total, unread}}，当前身份的每个分组都有，没有消息时是 0。
     */
    @GetMapping("/summary")
    public AjaxResult summary(@RequestParam(required = false) String mode)
    {
        AppLoginUser me = AppUserContext.require();
        String realMode = resolveMode(me, mode);
        AjaxResult ajax = AjaxResult.success();
        ajax.put("categories", kgMessageService.countByCategory(me.getUserId(), realMode));
        ajax.put("unread", Integer.valueOf(kgMessageService.countUnread(me.getUserId(), realMode)));
        ajax.put("buckets", buckets(realMode, kgMessageService.countByBucket(me.getUserId(), realMode)));
        return ajax;
    }

    /** 未读数（tabbar 红点用） */
    @GetMapping("/unread")
    public AjaxResult unread(@RequestParam(required = false) String mode)
    {
        AppLoginUser me = AppUserContext.require();
        String realMode = resolveMode(me, mode);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("unread", Integer.valueOf(kgMessageService.countUnread(me.getUserId(), realMode)));
        return AjaxResult.success(data);
    }

    /** 标记单条已读。只能标记自己的消息，标记他人消息返回影响行数 0。 */
    @PostMapping("/{msgId}/read")
    public AjaxResult read(@PathVariable Long msgId)
    {
        Long userId = AppUserContext.getUserId();
        int rows = kgMessageService.markRead(msgId, userId);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("updated", Integer.valueOf(rows));
        return AjaxResult.success(data);
    }

    /** 删除本人当前身份的消息；不回显消息内容，不区分他人消息和不存在的消息。 */
    @PostMapping("/{msgId}/delete")
    public AjaxResult delete(@PathVariable Long msgId, @RequestParam(required = false) String mode)
    {
        AppLoginUser me = AppUserContext.require();
        String realMode = resolveMode(me, mode);
        boolean revoked = "staff".equals(mode) && !"staff".equals(realMode);
        int updated = revoked ? 0 : kgMessageService.deleteOwnMessage(msgId, me.getUserId(), realMode);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("updated", updated);
        data.put("unread", kgMessageService.countUnread(me.getUserId(), realMode));
        return AjaxResult.success(data);
    }

    /**
     * 全部标记已读；带 bucket 时只读掉这一组（消息页点开「评论」「赞」「系统通知」时清掉那个入口的角标）。
     * 带 maxId（2026-10-16，消息子页带第一页最大的消息ID）：只读掉这个ID及以前的，并多回 unreadIds = 读掉之前还没读的那些ID
     * （新的在前，最多 {@link #UNREAD_IDS_MAX} 个），子页往下翻时靠它给「进来那一刻没读」的行留小红点。不带 maxId 时返回和以前一样。
     */
    @PostMapping("/read-all")
    public AjaxResult readAll(@RequestParam(required = false) String mode,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) Long maxId)
    {
        AppLoginUser me = AppUserContext.require();
        String realMode = resolveMode(me, mode);
        Map<String, Object> data = new HashMap<String, Object>();
        if (revokedStaffBucket(mode, realMode, bucket))
        {
            // 同上：员工授权已撤销，员工才有的分组什么也不读，照常回成功
            data.put("updated", Integer.valueOf(0));
            if (maxId != null)
            {
                data.put("unreadIds", new ArrayList<Long>());
            }
            return AjaxResult.success(data);
        }
        String group = checkBucket(realMode, bucket);
        if (maxId != null)
        {
            // 先读出要读掉的那些ID，再按同样条件标已读；中间别处读掉几条，只是多留几个小红点，不会少
            data.put("unreadIds", kgMessageService.selectUnreadIds(me.getUserId(), realMode, group, maxId, UNREAD_IDS_MAX));
            data.put("updated", Integer.valueOf(kgMessageService.markAllRead(me.getUserId(), realMode, group, maxId)));
            return AjaxResult.success(data);
        }
        data.put("updated", Integer.valueOf(group == null
                ? kgMessageService.markAllRead(me.getUserId(), realMode)
                : kgMessageService.markAllRead(me.getUserId(), realMode, group)));
        return AjaxResult.success(data);
    }

    /** 空 = 不分组；其余必须是当前身份的分组之一（员工身份没有评论 / 赞，用户身份没有报名 / 订单入口） */
    static String checkBucket(String realMode, String bucket)
    {
        if (StringUtils.isEmpty(bucket))
        {
            return null;
        }
        if (!("staff".equals(realMode) ? STAFF_BUCKETS : USER_BUCKETS).contains(bucket))
        {
            throw new ServiceException("消息分组不对，请刷新后重试", 400);
        }
        return bucket;
    }

    /**
     * 请求的是员工身份、实际被降成了用户身份，分组又是员工身份才有的（报名 / 订单）：
     * 说明员工授权刚被撤销、小程序还没重新核对身份，不算传错分组。完全不认识的分组仍由 checkBucket 返回 400。
     */
    static boolean revokedStaffBucket(String mode, String realMode, String bucket)
    {
        return "staff".equals(mode) && !"staff".equals(realMode)
                && STAFF_BUCKETS.contains(bucket) && !USER_BUCKETS.contains(bucket);
    }

    /** buckets：当前身份的每个分组都给出 {total, unread}，查不到的记 0，顺序固定 */
    static Map<String, Object> buckets(String realMode, List<Map<String, Object>> counted)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String b : "staff".equals(realMode) ? STAFF_BUCKETS : USER_BUCKETS)
        {
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("total", Long.valueOf(0L));
            v.put("unread", Long.valueOf(0L));
            out.put(b, v);
        }
        if (counted != null)
        {
            for (Map<String, Object> r : counted)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> v = (Map<String, Object>) out.get(String.valueOf(r.get("bucket")));
                if (v != null)
                {
                    v.put("total", Long.valueOf(number(r.get("total"))));
                    v.put("unread", Long.valueOf(number(r.get("unread"))));
                }
            }
        }
        return out;
    }

    private static long number(Object v)
    {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    static boolean isInteraction(KgMessage m)
    {
        return "comment".equals(m.getCategory()) || "like".equals(m.getCategory());
    }

    /** 评论 / 赞的行：触发人、动态首图和标题、被回复 / 被赞的那条评论片段（没有的为 null） */
    static void putInteraction(Map<String, Object> row, KgMessage m)
    {
        row.put("actorId", m.getActorId());
        row.put("actorName", m.getActorName());
        row.put("actorAvatar", m.getActorAvatar());
        row.put("postId", m.getPostId());
        row.put("cover", m.getCover());
        row.put("postTitle", m.getPostTitle());
        row.put("quote", m.getQuote());
    }

    /**
     * 解析身份：请求 staff 视角时必须真的有员工授权，否则降级为 user。
     * 这样即使前端被篡改传 mode=staff，也拿不到员工消息。
     */
    private static String resolveMode(AppLoginUser me, String mode)
    {
        if ("staff".equals(mode) && me.hasStaffAccess())
        {
            return "staff";
        }
        return "user";
    }

    /** 私信 / 群聊那一行正文开头的「（N 条新消息）」：只有 10-15 验收版写过，现在 KgChatService 已不再写 */
    private static final Pattern NEW_COUNT_PREFIX = Pattern.compile("^（[0-9]+ 条新消息）");

    /**
     * 列表里显示的正文：私信 / 群聊那一行开头如果还留着旧版写的「（N 条新消息）」，不管已读未读都去掉。
     * 这个数不准（「全部已读」后会把清掉的也算进去），而且读掉后列表不刷新还一直显示；红点 / 角标已经说明有新消息。
     * 库里存的正文不动，下一条新消息会整行重写。
     * 会话那一行只是预览：消息里的换行、多个空格并成一个空格（2026-10-16 主人反馈：多行消息在列表里一行显示不下，字冒到上下两行）。
     */
    static String listContent(KgMessage m)
    {
        String content = m.getContent();
        if (content != null && "chat_conv".equals(m.getBizType()))
        {
            return oneLine(NEW_COUNT_PREFIX.matcher(content).replaceFirst(""));
        }
        return content;
    }

    /** 换行、制表、连续空白（含全角空格）并成一个空格，去掉首尾空白 */
    static String oneLine(String s)
    {
        return s == null ? null : s.replaceAll("[\\s\\u3000]+", " ").trim();
    }
}
