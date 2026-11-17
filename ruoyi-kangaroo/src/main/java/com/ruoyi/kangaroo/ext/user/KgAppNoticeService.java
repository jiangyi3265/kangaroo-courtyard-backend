package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 小程序公告（kg_app_notice）：运营在后台「用户服务 → 小程序公告」编辑，小程序里三处用到：
 *  · 进小程序时弹窗一次（popup = '1'），点「我知道了」记下看过的版本；运营改了标题或正文，版本号 +1，会再弹一次；
 *  · 消息页顶部的「公告」卡片列出全部上线中的公告；
 *  · 线上任务页面的「线上兼职须知」按固定标识 online-guide 直接打开那一条。
 *
 * 给谁看（audience）：all 所有人；online 关注线上任务的人 —— 订阅过线上任务提醒，或者报名过任意线上任务。
 * 游客只看得到 all。这只决定「弹不弹、列不列」，不是保密：任何人按标识打开详情都能看（须知要让还没报名的人也看得到）。
 *
 * 游客没有账号，看过哪条由小程序记在手机本地，取弹窗时把本地记录带上来（seen 参数），这里一并排除。
 */
@Service
public class KgAppNoticeService
{
    /** 上线中、在展示时间内、没删除 */
    static final String VISIBLE = "n.del_flag = '0' and n.status = '0' and (n.start_time is null or n.start_time <= now()) "
            + "and (n.end_time is null or n.end_time > now())";

    /** 固定标识的写法：小写字母开头，小写字母 / 数字 / 短横线，2–32 位（与后台校验同一条规则） */
    static final String KEY_PATTERN = "[a-z][a-z0-9-]{1,31}";

    @Autowired
    private KgJdbc db;

    private static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v);
    }

    private static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    /** 这个人能看到哪些人群的公告：游客只有 all；订阅过线上任务或报名过线上任务的人再加 online */
    List<String> audiences(Long uid)
    {
        List<String> out = new ArrayList<String>();
        out.add("all");
        if (uid != null && onlineInterested(uid))
        {
            out.add("online");
        }
        return out;
    }

    /** 关注线上任务：订阅里勾过「线上任务」（job_types 存的是岗位大类代码），或者报名过线上任务（含已撤回的） */
    boolean onlineInterested(Long uid)
    {
        return db.count("select (select count(*) from kg_job_subscribe s where s.app_user_id = :u and find_in_set('online', s.job_types) > 0) "
                + "+ (select count(*) from kg_application a join kg_job j on j.job_id = a.job_id "
                + "where a.app_user_id = :u and a.del_flag = '0' and j.job_type = 'online')", p("u", uid)) > 0;
    }

    /** 消息页「公告」：上线中、面向这个人的公告，按排序；read = 看过当前版本 */
    public List<Map<String, Object>> list(Long uid)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select n.notice_id, n.title, n.content, n.notice_key, n.version, n.popup, "
                + "coalesce(n.update_time, n.create_time) shown_time, s.version seen_version from kg_app_notice n "
                + "left join kg_app_notice_seen s on s.notice_id = n.notice_id and s.user_id = :u "
                + "where " + VISIBLE + " and n.audience in (:aud) order by n.sort, n.notice_id desc limit 50",
                p("u", uid == null ? -1L : uid, "aud", audiences(uid))))
        {
            Map<String, Object> m = view(r, false);
            m.put("read", uid != null && r.get("seenVersion") != null && num(r.get("seenVersion")) >= num(r.get("version")));
            out.add(m);
        }
        return out;
    }

    /** 详情：纯数字按编号，否则按固定标识（如 online-guide）。下线 / 不在展示时间内 / 删除了，一律说已下线 */
    public Map<String, Object> detail(Long uid, String idOrKey)
    {
        String k = idOrKey == null ? "" : idOrKey.trim();
        Map<String, Object> r;
        String cols = "select n.notice_id, n.title, n.content, n.notice_key, n.version, n.popup, coalesce(n.update_time, n.create_time) shown_time "
                + "from kg_app_notice n where " + VISIBLE;
        if (k.matches("[0-9]{1,18}"))
        {
            r = db.one(cols + " and n.notice_id = :id", p("id", Long.valueOf(k)));
        }
        else if (k.matches(KEY_PATTERN))
        {
            r = db.one(cols + " and n.notice_key = :k", p("k", k));
        }
        else
        {
            r = null;
        }
        if (r == null)
        {
            throw new ServiceException("这条公告已下线或不存在", 404);
        }
        Map<String, Object> m = view(r, true);
        if (uid != null)
        {
            Map<String, Object> s = db.one("select version from kg_app_notice_seen where user_id = :u and notice_id = :n",
                    p("u", uid, "n", r.get("noticeId")));
            m.put("read", s != null && num(s.get("version")) >= num(r.get("version")));
        }
        else
        {
            m.put("read", false);
        }
        return m;
    }

    /**
     * 进小程序时要弹的那一条：上线中、要弹窗、面向这个人，并且这个人还没看过当前版本（登录的人查库，游客看本地带上来的 seen）。
     * 一次只给一条（排序最前的），看完下次进来再弹下一条；没有要弹的返回 null。
     *
     * @param localSeen 小程序本地记下的「编号:版本」，逗号分隔，如 "3:2,5:1"（游客靠它；登录的人也带，补上还没同步到服务器的那次点击）
     */
    public Map<String, Object> popup(Long uid, String localSeen)
    {
        Map<Long, Long> seen = parseSeen(localSeen);
        boolean login = uid != null;
        for (Map<String, Object> r : db.list("select n.notice_id, n.title, n.content, n.notice_key, n.version, n.popup, "
                + "coalesce(n.update_time, n.create_time) shown_time, " + (login ? "s.version" : "null") + " seen_version from kg_app_notice n "
                + (login ? "left join kg_app_notice_seen s on s.notice_id = n.notice_id and s.user_id = :u " : "")
                + "where " + VISIBLE + " and n.popup = '1' and n.audience in (:aud) order by n.sort, n.notice_id desc limit 20",
                p("u", login ? uid : -1L, "aud", audiences(uid))))
        {
            long id = num(r.get("noticeId"));
            long version = num(r.get("version"));
            Long dbSeen = r.get("seenVersion") == null ? null : num(r.get("seenVersion"));
            Long local = seen.get(id);
            if ((dbSeen == null || dbSeen < version) && (local == null || local < version))
            {
                return view(r, true);
            }
        }
        return null;
    }

    /**
     * 「我知道了」：记下这个人看过这条公告的哪个版本（只增不减）。
     * 前端带的是弹窗时拿到的版本：运营在用户看的这段时间里改了内容，记的仍是旧版本，下次会把新内容再弹一次。
     */
    public Map<String, Object> seen(Long uid, Long noticeId, Object version)
    {
        if (noticeId == null)
        {
            throw new ServiceException("缺少公告编号", 422);
        }
        Map<String, Object> n = db.one("select notice_id, version from kg_app_notice where notice_id = :id and del_flag = '0'", p("id", noticeId));
        if (n == null)
        {
            throw new ServiceException("这条公告已下线或不存在", 404);
        }
        long current = num(n.get("version"));
        long v;
        try
        {
            v = version == null || "".equals(version) ? current : Long.parseLong(String.valueOf(version).trim());
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("版本号不正确", 422);
        }
        if (v < 1)
        {
            throw new ServiceException("版本号不正确", 422);
        }
        // 前端不能替还没发布的版本记「看过」
        v = Math.min(v, current);
        db.update("insert into kg_app_notice_seen (user_id, notice_id, version, seen_time) values (:u, :n, :v, now()) "
                + "on duplicate key update version = greatest(version, :v), seen_time = now()", p("u", uid, "n", noticeId, "v", v));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("noticeId", noticeId);
        out.put("version", v);
        return out;
    }

    /** "3:2,5:1" → {3=2, 5=1}；写错的片段直接忽略，最多认 50 条 */
    static Map<Long, Long> parseSeen(String raw)
    {
        Map<Long, Long> out = new HashMap<Long, Long>();
        if (raw == null || raw.trim().isEmpty())
        {
            return out;
        }
        for (String part : raw.split(","))
        {
            String t = part.trim();
            if (out.size() >= 50 || !t.matches("[0-9]{1,18}:[0-9]{1,9}"))
            {
                continue;
            }
            int i = t.indexOf(':');
            Long id = Long.valueOf(t.substring(0, i));
            Long v = Long.valueOf(t.substring(i + 1));
            Long old = out.get(id);
            out.put(id, old == null ? v : Math.max(old, v));
        }
        return out;
    }

    /** 下发给小程序的字段；列表只给摘要（前 60 个字，换行压成空格），详情和弹窗给全文（保留换行） */
    private static Map<String, Object> view(Map<String, Object> r, boolean full)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String content = str(r.get("content"));
        m.put("noticeId", r.get("noticeId"));
        m.put("title", str(r.get("title")));
        if (full)
        {
            m.put("content", content);
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        m.put("summary", flat.length() > 60 ? flat.substring(0, 60) + "…" : flat);
        m.put("noticeKey", r.get("noticeKey") == null ? "" : str(r.get("noticeKey")));
        m.put("version", num(r.get("version")));
        m.put("popup", "1".equals(str(r.get("popup"))));
        String t = str(r.get("shownTime"));
        m.put("date", t.length() >= 10 ? t.substring(0, 10) : t);
        return m;
    }
}
