package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 后台「用户服务 → 小程序公告」：小程序公告的增删改查，走通用实体（与常见问题同一套页面）。
 *
 * 保存时额外做三件事：
 *  · 改了标题或正文，版本号自动 +1 —— 已经点过「我知道了」的人下次进小程序会再看到一次新内容；只改排序、状态等不加；
 *  · 固定标识（notice_key）只能用小写字母 / 数字 / 短横线，不能和别的公告重复；页面里写死引用的标识（online-guide）不能改也不能清空；
 *  · 有固定标识的公告不能删除（任务页面会直接打开它），不想展示就改成下线。
 */
@Component
public class KgNoticeModule implements ExtEntityContributor
{
    /** 小程序页面里写死引用的固定标识：改掉或清空，页面上的「线上兼职须知」就打不开了 */
    static final List<String> PAGE_KEYS = Collections.singletonList("online-guide");

    @Autowired
    private KgJdbc db;

    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity notice = new ExtEntity("appnotice", "小程序公告", "kg_app_notice", "notice_id", "kangaroo:appnotice")
                .field(ExtField.of("title", "标题", "text").required().max(64).search("like"))
                .field(ExtField.of("content", "正文", "textarea").required().max(5000).hideInList())
                .field(ExtField.of("audience", "给谁看", "select").required().options("all:所有人,online:关注线上任务的人").search("eq"))
                .field(ExtField.of("popup", "进小程序时", "select").required().options("1:弹窗提醒一次,0:不弹窗").search("eq"))
                .field(ExtField.of("noticeKey", "固定标识", "text").max(32))
                .field(ExtField.of("version", "版本", "int").readOnly())
                .field(ExtField.of("status", "状态", "select").required().options("0:上线,1:下线").search("eq"))
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L))
                .field(ExtField.of("startTime", "开始展示", "datetime").hideInList())
                .field(ExtField.of("endTime", "结束展示", "datetime").hideInList())
                .field(ExtField.of("updateTime", "最后修改", "datetime").readOnly())
                .order("sort, notice_id desc")
                .validate(this::check)
                .beforeDelete(this::checkDelete);
        return Arrays.asList(notice);
    }

    /** 通用保存的实体校验钩子：cols 是「列名 → 已转换的值」，修改时 _pk 是主键 */
    void check(Map<String, Object> cols, Boolean creating)
    {
        Object start = cols.get("start_time");
        Object end = cols.get("end_time");
        if (start instanceof LocalDateTime && end instanceof LocalDateTime && !((LocalDateTime) end).isAfter((LocalDateTime) start))
        {
            throw new ServiceException("结束展示时间要晚于开始展示时间", 422);
        }
        Long id = Boolean.TRUE.equals(creating) ? null : KgJdbc.asLong(cols.get("_pk"));
        Map<String, Object> old = id == null ? null
                : db.one("select title, content, notice_key, version from kg_app_notice where notice_id = :id and del_flag = '0'", p("id", id));
        if (!Boolean.TRUE.equals(creating) && old == null)
        {
            throw new ServiceException("公告不存在或已删除", 404);
        }
        if (cols.containsKey("notice_key"))
        {
            Object raw = cols.get("notice_key");
            String key = raw == null ? "" : String.valueOf(raw).trim();
            String oldKey = old == null || old.get("noticeKey") == null ? "" : String.valueOf(old.get("noticeKey"));
            if (PAGE_KEYS.contains(oldKey) && !oldKey.equals(key))
            {
                throw new ServiceException("这条公告的固定标识「" + oldKey + "」被小程序页面直接引用（如任务页的「线上兼职须知」），不能修改或清空", 422);
            }
            if (!key.isEmpty())
            {
                if (!key.matches(KgAppNoticeService.KEY_PATTERN) || "popup".equals(key))
                {
                    throw new ServiceException("固定标识只能用小写字母、数字和短横线，以字母开头，2–32 位（如 online-guide）", 422);
                }
                if (db.count("select count(*) from kg_app_notice where notice_key = :k and notice_id <> :id", p("k", key, "id", id == null ? -1L : id)) > 0)
                {
                    throw new ServiceException("固定标识「" + key + "」已被别的公告使用，请换一个", 409);
                }
                cols.put("notice_key", key);
            }
            else
            {
                // 留空存 NULL：唯一索引允许多条 NULL，空串只能有一条
                cols.put("notice_key", null);
            }
        }
        if (Boolean.TRUE.equals(creating))
        {
            cols.put("version", 1L);
            return;
        }
        boolean changed = cols.containsKey("title") && !same(cols.get("title"), old.get("title"))
                || cols.containsKey("content") && !same(cols.get("content"), old.get("content"));
        if (changed)
        {
            Long v = KgJdbc.asLong(old.get("version"));
            cols.put("version", (v == null ? 1L : v) + 1);
        }
    }

    void checkDelete(Long id)
    {
        Map<String, Object> r = db.one("select notice_key from kg_app_notice where notice_id = :id and del_flag = '0'", p("id", id));
        String key = r == null || r.get("noticeKey") == null ? "" : String.valueOf(r.get("noticeKey")).trim();
        if (!key.isEmpty())
        {
            throw new ServiceException("这条公告有固定标识「" + key + "」，小程序页面会直接打开它，不能删除；不想展示请把状态改成「下线」", 409);
        }
    }

    private static boolean same(Object a, Object b)
    {
        String x = a == null ? "" : String.valueOf(a).trim();
        String y = b == null ? "" : String.valueOf(b).trim();
        return x.equals(y);
    }
}
