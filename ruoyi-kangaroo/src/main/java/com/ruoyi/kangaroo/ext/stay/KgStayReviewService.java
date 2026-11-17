package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 小院详情「住过的家人评价」页（2026-10-17 改版 R2，主人效果图）：一个小院自己的评价汇总 + 列表。
 *
 * 真实统计口径（avg 等字段保持原口径；ai 独立由模型分析、标注来源，不替换这些值）：
 *  · 只算显示中、未删除的评价（status = '0' and del_flag = '0'，与门店评价汇总、kg_stay.rating 回写同一口径）；
 *    住宿评价落库时 target_type = 'room'、target_id = stay_id、biz_id = 住宿订单（见 KgUserHubService.writeReview）；
 *  · 综合评分 = 评分平均（保留一位，四舍五入，和 kg_stay.rating 的 round(avg, 1) 一致）；
 *  · 推荐率 = 4 星及以上的占比；不足 5 条时不给（1 条好评就是 100%，没有参考价值，与详情页好评率同一门槛）；
 *  · 分项 = 写评价页住宿的 5 题（卫生 / 设施相符 / 服务 / 安静舒适 / 位置周边，kg_review.dims 形如 "[5, 4, 5, 4, 5]"），
 *    页面写短名 卫生 / 设施 / 服务 / 安静 / 位置；带完整分项的评价不足 3 条时整行不给；
 *  · 「大家提到」= 拿评价正文对一张人工整理的关键词表数次数（MENTIONS），每条评价每个标签最多算一次，
 *    至少 2 条评价提到才出现，最多 6 个；正文里写评价页拼进去的【卫生】【安静舒适】这类小标题先去掉，否则每条都「提到」卫生、安静；
 *    关键词前面紧挨着「不 / 没有 / 不太 …」、后面紧跟着「不方便 / 一般 / 很差 …」的不算（「不太安静」「交通不方便」不能算成好话）；
 *  · 房型、入住时间按评价对应的住宿订单（kg_stay_order.room_type_id / check_in）算，房型名取 kg_stay_room_type（已下架的房型名也照样显示）；
 *  · 匿名评价不露昵称和头像（写评价页默认匿名提交）。
 *
 * 一个小院的评价最多取最近 MAX_ROWS 条在内存里算汇总和筛选：最热的小院也就几百条；
 * 「大家提到」要按同一套去小标题、去否定的规则筛，用 SQL like 做不到和计数一致。
 * 2026-10-17 R3：评价可以附 1 段视频（V53 kg_review.videos / video_covers），列表每条带 videos: [{url, cover}]，「有图·视频」页签也算有视频的。
 */
@Service
public class KgStayReviewService
{
    /** 一个小院最多取多少条评价来算 */
    static final int MAX_ROWS = 2000;

    /** 推荐率至少要几条评价 */
    static final int RECOMMEND_MIN = 5;

    /** 分项至少要几条带完整分项的评价 */
    static final int DIMS_MIN = 3;

    /** 「大家提到」：至少几条评价提到、最多显示几个 */
    static final int MENTION_MIN = 2;
    static final int MENTION_TOP = 6;

    /** 差评：2 星及以下 */
    static final int BAD_MAX = 2;

    /** 分项短名，顺序与写评价页住宿 5 题一致（pages/profile/review.vue 的 QUESTIONS.stay_order） */
    static final String[] DIM_LABELS = { "卫生", "设施", "服务", "安静", "位置" };

    /**
     * 「大家提到」关键词表：{ 标签, 关键词… }。顺序就是次数相同时的先后。
     * 只收正面说法；关键词要具体（「方便」「氛围」这种宽一点的词放在最贴近的标签下），改词表不用改库。
     */
    static final String[][] MENTIONS = {
        { "小院安静", "安静", "清静", "宁静", "静谧", "不吵" },
        { "房间干净", "干净", "整洁", "一尘不染", "卫生好", "卫生很好", "很卫生" },
        { "绿植很多", "绿植", "花草", "植物", "鲜花", "花园", "绿化" },
        { "公共区域舒服", "公共区域", "公区", "客厅", "露台", "天台", "院子" },
        { "设施齐全", "齐全", "设施很全", "设施完善", "设备很全" },
        { "氛围治愈", "治愈", "氛围", "温馨", "放松", "惬意", "舒心" },
        { "主理人热情", "热情", "贴心", "周到", "好客", "亲切", "老板好", "老板人好", "老板很好", "老板人很好", "主理人好", "管家好", "主人好" },
        { "早餐好吃", "早餐", "早饭" },
        { "交通方便", "交通", "方便", "便利", "好找", "位置好", "位置很好", "位置不错" },
        { "床很舒服", "床很舒服", "床舒服", "睡得好", "睡得很好", "好睡", "床品" },
        { "景色很美", "风景", "景色", "视野", "海景", "山景", "日落", "日出", "星空" },
        { "性价比高", "性价比", "划算", "实惠", "超值", "物超所值" },
        { "可以做饭", "做饭", "厨房" } };

    /** 关键词前面紧挨着这些字就当否定，不算提到（「不太安静」「没有早餐」） */
    static final String[] NEGATIONS = { "不", "没", "没有", "不太", "不够", "不算", "不很", "不怎么", "不是很", "不是太", "不大", "不那么", "不特别", "未", "无" };

    /** 关键词后面紧跟着这些说法也不算（「交通不方便」「早餐一般」）；「不错」「不吵」「不贵」是好话，不在里面 */
    static final String[] NEGATIONS_AFTER = { "不方便", "不便", "不好", "不太", "不行", "不够", "不怎么", "不干净", "不舒服", "不齐全", "不全", "一般", "很一般",
        "比较一般", "有点差", "很差", "太差", "较差", "差", "没有", "没什么", "堪忧", "欠佳", "有待" };

    /** 写评价页拼正文用的小标题：【卫生】【安静舒适】… */
    private static final Pattern SECTION = Pattern.compile("【[^】]{0,12}】");

    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    @Autowired
    private KgJdbc db;

    @Autowired(required = false)
    private KgReviewAiService reviewAi;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    static int rating(Map<String, Object> r)
    {
        Long l = KgJdbc.asLong(r.get("rating"));
        return l == null ? 0 : l.intValue();
    }

    /** 一位小数，四舍五入（与 MySQL round(avg, 1) 一致，不走浮点） */
    static Double avg1(long sum, long n)
    {
        return n == 0 ? null : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(n), 1, RoundingMode.HALF_UP).doubleValue();
    }

    /** "[5, 4, 5, 4, 5]" → 5 个 1–5 的分；不是完整的 5 项（历史评价、别的题型）返回 null */
    static int[] dims(Object raw)
    {
        String d = str(raw).replaceAll("[\\[\\]\\s]", "");
        if (d.isEmpty())
        {
            return null;
        }
        String[] parts = d.split(",");
        if (parts.length != DIM_LABELS.length)
        {
            return null;
        }
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++)
        {
            try
            {
                out[i] = Integer.parseInt(parts[i]);
            }
            catch (NumberFormatException e)
            {
                return null;
            }
            if (out[i] < 1 || out[i] > 5)
            {
                return null;
            }
        }
        return out;
    }

    /** 拿来数关键词的正文：去掉【小标题】 */
    static String plain(Object content)
    {
        return SECTION.matcher(str(content)).replaceAll(" ");
    }

    /** text 里从 idx 开始、长 len 的关键词前面紧挨着否定词，或者后面紧跟着差评说法 */
    static boolean negated(String text, int idx, int len)
    {
        String before = text.substring(Math.max(0, idx - 4), idx);
        for (String n : NEGATIONS)
        {
            if (before.endsWith(n))
            {
                return true;
            }
        }
        String after = text.substring(idx + len, Math.min(text.length(), idx + len + 4));
        for (String n : NEGATIONS_AFTER)
        {
            if (after.startsWith(n))
            {
                return true;
            }
        }
        return false;
    }

    /** 这段正文有没有（不带否定地）说到这些关键词里的任意一个 */
    static boolean says(String text, String[] row)
    {
        for (int k = 1; k < row.length; k++)
        {
            String kw = row[k];
            for (int idx = text.indexOf(kw); idx >= 0; idx = text.indexOf(kw, idx + 1))
            {
                if (!negated(text, idx, kw.length()))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** 一条评价提到了哪些标签（按词表顺序，不重复） */
    static List<String> mentionsOf(Object content)
    {
        String text = plain(content);
        List<String> out = new ArrayList<String>();
        for (String[] row : MENTIONS)
        {
            if (says(text, row))
            {
                out.add(row[0]);
            }
        }
        return out;
    }

    static String[] mentionRow(String label)
    {
        for (String[] row : MENTIONS)
        {
            if (row[0].equals(label))
            {
                return row;
            }
        }
        return null;
    }

    static boolean hasMedia(Map<String, Object> r)
    {
        return !images(r.get("images")).isEmpty() || !videos(r.get("videos"), r.get("videoCovers")).isEmpty();
    }

    /**
     * 逗号分隔的视频列 + 对应的封面列 → [{url, cover}]（2026-10-17 R3）；只放本平台视频上传接口给的路径，封面不是平台图片的给 ''。
     * 封面和视频按位置一一对应，封面列短了、空着的位置给 ''（页面画中性的占位底色）
     */
    public static List<Map<String, Object>> videos(Object raw, Object rawCovers)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String[] urls = str(raw).split(",", -1);
        String[] covers = str(rawCovers).split(",", -1);
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < urls.length; i++)
        {
            String u = urls[i].trim();
            if (u.isEmpty() || !com.ruoyi.kangaroo.ext.core.KgVideoUploads.isPlatformVideo(u) || seen.contains(u))
            {
                continue;
            }
            seen.add(u);
            String c = i < covers.length ? covers[i].trim() : "";
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("url", u);
            m.put("cover", !c.isEmpty() && KgJdbc.isPlatformImage(c) && !com.ruoyi.kangaroo.ext.core.KgVideoUploads.isPlatformVideo(c) ? c : "");
            out.add(m);
        }
        return out;
    }

    /** 逗号分隔的图片列 → 列表；只放本平台的图片路径（手改库塞进来的外链不往页面上放） */
    static List<String> images(Object raw)
    {
        List<String> out = new ArrayList<String>();
        for (String s : str(raw).split(","))
        {
            String u = s.trim();
            if (!u.isEmpty() && KgJdbc.isPlatformImage(u) && !com.ruoyi.kangaroo.ext.core.KgVideoUploads.isPlatformVideo(u) && !out.contains(u))
            {
                out.add(u);
            }
        }
        return out;
    }

    static List<String> tags(Object raw)
    {
        List<String> out = new ArrayList<String>();
        for (String s : str(raw).split("[,，]"))
        {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t))
            {
                out.add(t);
            }
        }
        return out;
    }

    /** 入住月份 yyyy-MM（订单入住日 yyyy-MM-dd 的前 7 位）；没有对应订单时为 "" */
    static String month(Map<String, Object> r)
    {
        String d = str(r.get("checkIn"));
        return d.length() >= 7 && MONTH.matcher(d.substring(0, 7)).matches() ? d.substring(0, 7) : "";
    }

    /** "2026-09" → "2026年9月" */
    static String monthLabel(String ym)
    {
        return ym.length() == 7 ? ym.substring(0, 4) + "年" + Integer.parseInt(ym.substring(5, 7)) + "月" : ym;
    }

    /** "2026-10-17 08:00:00" → "2026.10" */
    static String ymDot(Object dt)
    {
        String s = str(dt);
        return s.length() >= 7 && s.charAt(4) == '-' ? s.substring(0, 4) + "." + s.substring(5, 7) : "";
    }

    /** "2026-10-17 08:00:00" → "2026.10.17" */
    static String ymdDot(Object dt)
    {
        String s = str(dt);
        return s.length() >= 10 && s.charAt(4) == '-' ? s.substring(0, 4) + "." + s.substring(5, 7) + "." + s.substring(8, 10) : "";
    }

    // ---------------------------------------------------------------- 取数

    /** 小院要在架（与详情页同一口径），否则 404 */
    private String requireStay(Long stayId)
    {
        Map<String, Object> s = stayId == null ? null : db.one("select s.stay_id, s.stay_name from kg_stay s where s.stay_id = :id and " + KgStayBrowseService.ONLINE,
                p("id", stayId));
        if (s == null)
        {
            throw new ServiceException("房源不存在或已下架", 404);
        }
        return str(s.get("stayName"));
    }

    /** 这家小院显示中的评价（新的在前，精选置顶），带评价人、对应订单的房型和入住日 */
    List<Map<String, Object>> rows(Long stayId)
    {
        return db.list("select v.review_id, v.rating, v.content, v.images, v.videos, v.video_covers, v.tags, v.dims, v.anonymous, v.featured, v.reply, v.reply_time, v.create_time, "
                + "u.nickname, u.avatar, o.room_type_id, o.check_in, t.type_name room_type_name from kg_review v "
                + "left join kg_app_user u on u.user_id = v.app_user_id "
                + "left join kg_stay_order o on v.biz_type = 'stay_order' and o.order_id = v.biz_id and o.stay_id = v.target_id "
                + "left join kg_stay_room_type t on t.room_type_id = o.room_type_id "
                + "where v.target_type = 'room' and v.target_id = :s and v.status = '0' and v.del_flag = '0' "
                + "order by v.featured desc, v.review_id desc limit " + MAX_ROWS, p("s", stayId));
    }

    // ---------------------------------------------------------------- 汇总

    public Map<String, Object> summary(Long stayId)
    {
        String name = requireStay(stayId);
        Map<String, Object> m = summarize(rows(stayId));
        m.put("stayId", stayId);
        m.put("stayName", name);
        Map<String, Object> ai = reviewAi == null ? null : reviewAi.snapshot(stayId);
        m.put("ai", KgReviewAiService.overall(ai));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roomTypes = (List<Map<String, Object>>) m.get("roomTypes");
        for (Map<String, Object> room : roomTypes)
            room.put("ai", KgReviewAiService.room(ai, room.get("roomTypeId"), (Integer) room.get("count")));
        return m;
    }

    static Map<String, Object> summarize(List<Map<String, Object>> rows)
    {
        long n = rows.size();
        long sum = 0;
        long good = 0;
        long media = 0;
        long bad = 0;
        long[] dimSum = new long[DIM_LABELS.length];
        int dimRows = 0;
        Map<String, Integer> mention = new LinkedHashMap<String, Integer>();
        for (String[] row : MENTIONS)
        {
            mention.put(row[0], 0);
        }
        Map<Long, Map<String, Object>> rooms = new LinkedHashMap<Long, Map<String, Object>>();
        Map<String, Integer> months = new java.util.TreeMap<String, Integer>(java.util.Collections.reverseOrder());
        for (Map<String, Object> r : rows)
        {
            int rt = rating(r);
            sum += rt;
            good += rt >= 4 ? 1 : 0;
            bad += rt <= BAD_MAX ? 1 : 0;
            media += hasMedia(r) ? 1 : 0;
            int[] d = dims(r.get("dims"));
            if (d != null)
            {
                dimRows++;
                for (int i = 0; i < d.length; i++)
                {
                    dimSum[i] += d[i];
                }
            }
            for (String label : mentionsOf(r.get("content")))
            {
                mention.put(label, mention.get(label) + 1);
            }
            Long roomTypeId = KgJdbc.asLong(r.get("roomTypeId"));
            if (roomTypeId != null)
            {
                Map<String, Object> room = rooms.get(roomTypeId);
                if (room == null)
                {
                    room = new LinkedHashMap<String, Object>();
                    room.put("roomTypeId", roomTypeId);
                    room.put("name", str(r.get("roomTypeName")).isEmpty() ? "房型" : str(r.get("roomTypeName")));
                    room.put("count", 0);
                    rooms.put(roomTypeId, room);
                }
                room.put("count", (Integer) room.get("count") + 1);
            }
            String ym = month(r);
            if (!ym.isEmpty())
            {
                months.put(ym, months.containsKey(ym) ? months.get(ym) + 1 : 1);
            }
        }
        List<Map<String, Object>> dims = new ArrayList<Map<String, Object>>();
        if (dimRows >= DIMS_MIN)
        {
            for (int i = 0; i < DIM_LABELS.length; i++)
            {
                Map<String, Object> d = new LinkedHashMap<String, Object>();
                d.put("label", DIM_LABELS[i]);
                d.put("avg", avg1(dimSum[i], dimRows));
                dims.add(d);
            }
        }
        List<Map<String, Object>> chips = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Integer> e : mention.entrySet())
        {
            if (e.getValue() >= MENTION_MIN)
            {
                Map<String, Object> c = new LinkedHashMap<String, Object>();
                c.put("label", e.getKey());
                c.put("count", e.getValue());
                chips.add(c);
            }
        }
        // 次数多的在前；次数一样按词表顺序（List.sort 是稳定排序）
        chips.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));
        List<Map<String, Object>> roomList = new ArrayList<Map<String, Object>>(rooms.values());
        roomList.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));
        List<Map<String, Object>> monthList = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Integer> e : months.entrySet())
        {
            Map<String, Object> mo = new LinkedHashMap<String, Object>();
            mo.put("month", e.getKey());
            mo.put("label", monthLabel(e.getKey()));
            mo.put("count", e.getValue());
            monthList.add(mo);
        }
        Map<String, Object> tabs = new LinkedHashMap<String, Object>();
        tabs.put("all", n);
        tabs.put("media", media);
        tabs.put("bad", bad);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("count", n);
        m.put("avg", avg1(sum, n));
        m.put("recommendRate", n >= RECOMMEND_MIN ? (Object) Math.round(good * 100.0 / n) : null);
        m.put("recommendMin", RECOMMEND_MIN);
        m.put("dims", dims);
        m.put("dimsCount", dimRows);
        m.put("mentions", chips.size() > MENTION_TOP ? new ArrayList<Map<String, Object>>(chips.subList(0, MENTION_TOP)) : chips);
        m.put("tabs", tabs);
        m.put("roomTypes", roomList);
        m.put("months", monthList);
        return m;
    }

    // ---------------------------------------------------------------- 列表

    /**
     * tab = all（默认）/ media 有图·视频 / bad 差评（2 星及以下）；roomTypeId 房型；month = yyyy-MM 入住月份；
     * mention = 「大家提到」里的一个标签。参数不认识的回 422，不悄悄当成「全部」。
     */
    public Map<String, Object> list(Long stayId, String tab, Long roomTypeId, String month, String mention, int pageNum, int pageSize)
    {
        checkParams(tab, month, mention, pageNum, pageSize);
        requireStay(stayId);
        return page(filter(rows(stayId), tab, roomTypeId, month, mention), pageNum, pageSize);
    }

    static void checkParams(String tab, String month, String mention, int pageNum, int pageSize)
    {
        String t = tab == null ? "" : tab.trim();
        if (!(t.isEmpty() || "all".equals(t) || "media".equals(t) || "bad".equals(t)) || pageNum < 1 || pageNum > 10000 || pageSize < 1 || pageSize > 20)
        {
            throw new ServiceException("评价筛选参数不合法", 422);
        }
        if (month != null && !month.trim().isEmpty() && !MONTH.matcher(month.trim()).matches())
        {
            throw new ServiceException("入住时间格式不对", 422);
        }
        if (mention != null && !mention.trim().isEmpty() && mentionRow(mention.trim()) == null)
        {
            throw new ServiceException("没有这个评价标签", 422);
        }
    }

    static List<Map<String, Object>> filter(List<Map<String, Object>> rows, String tab, Long roomTypeId, String month, String mention)
    {
        String t = tab == null ? "" : tab.trim();
        String ym = month == null ? "" : month.trim();
        String[] row = mention == null || mention.trim().isEmpty() ? null : mentionRow(mention.trim());
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : rows)
        {
            if ("media".equals(t) && !hasMedia(r))
            {
                continue;
            }
            if ("bad".equals(t) && rating(r) > BAD_MAX)
            {
                continue;
            }
            if (roomTypeId != null && !roomTypeId.equals(KgJdbc.asLong(r.get("roomTypeId"))))
            {
                continue;
            }
            if (!ym.isEmpty() && !ym.equals(month(r)))
            {
                continue;
            }
            if (row != null && !says(plain(r.get("content")), row))
            {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    static Map<String, Object> page(List<Map<String, Object>> rows, int pageNum, int pageSize)
    {
        int from = Math.min(rows.size(), (pageNum - 1) * pageSize);
        int to = Math.min(rows.size(), from + pageSize);
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : rows.subList(from, to))
        {
            cards.add(card(r));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rows", cards);
        m.put("total", rows.size());
        m.put("hasMore", to < rows.size());
        m.put("pageNum", pageNum);
        m.put("pageSize", pageSize);
        return m;
    }

    /** 一条评价卡：匿名的不给昵称和头像；房型、入住月份来自对应订单，没有就空着 */
    static Map<String, Object> card(Map<String, Object> r)
    {
        boolean anon = !"0".equals(str(r.get("anonymous")));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("reviewId", r.get("reviewId"));
        m.put("anonymous", anon);
        m.put("name", anon ? "匿名住客" : (str(r.get("nickname")).isEmpty() ? "住客" : str(r.get("nickname"))));
        m.put("avatar", anon ? "" : str(r.get("avatar")));
        m.put("rating", rating(r));
        m.put("content", str(r.get("content")));
        m.put("images", images(r.get("images")));
        // 评价视频（2026-10-17 R3）：[{url, cover}]，页面在照片上面放满宽 16:9 的视频，点了横屏全屏播放
        m.put("videos", videos(r.get("videos"), r.get("videoCovers")));
        m.put("tags", tags(r.get("tags")));
        m.put("mentions", mentionsOf(r.get("content")));
        m.put("roomType", str(r.get("roomTypeName")));
        String ym = month(r);
        m.put("stayMonth", ym.isEmpty() ? "" : monthLabel(ym) + "入住");
        m.put("date", ymDot(r.get("createTime")));
        m.put("reply", KgJdbc.asStr(r.get("reply")));
        m.put("replyDate", r.get("replyTime") == null ? null : ymdDot(r.get("replyTime")));
        m.put("featured", "1".equals(str(r.get("featured"))));
        return m;
    }
}
