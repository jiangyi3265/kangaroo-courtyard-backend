package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.JobImages;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 小院视频、房型视频、生活碎片（2026-10-17 旅居改版 R3，V53；主人效果图 7「3分钟看小院」「近期生活碎片」和「可选空余房型」的短视频）。
 *
 * 员工端（房源管理 → 视频与生活碎片）：
 *   · 小院视频（kg_stay.intro_video + 封面）、每个房型的短视频（kg_stay_room_type.video_url + 封面）：上传 / 换 / 删；
 *   · 生活碎片顶部：背景图、标题、两三行文案；生活碎片一条一行（kg_stay_diary：照片或视频 + 一句话 + 拍摄日期），增、改字、删、排序。
 *   这些都是补充展示内容，主人拍板上线中的房源也能直接改，不用下架重审（和群码一样）；每次改动记一条员工操作日志。
 *   视频只收本平台视频上传接口（KgVideoUploads）给的路径、而且文件确实在；封面 / 背景图 / 照片只收平台图片路径（/profile/upload/…）。
 * 游客端：详情页的小院视频、生活碎片摘要（条数 + 最近几张缩略图），生活碎片页（只给显示中的、房源在架的）。
 * 后台审核：后台「生活碎片」可以把某条隐藏（status = '1'）或删除；员工端看得到「已被平台隐藏」，只能删，不能自己放出来。
 */
@Service
public class KgStayMediaService
{
    /** 一个小院最多多少条生活碎片（没删的，含被隐藏的） */
    static final int DIARY_MAX = 200;

    /** 一句话最多几个字（kg_stay_diary.caption 列宽） */
    static final int CAPTION_MAX = 32;

    /** 生活碎片页标题最多几个字（kg_stay.diary_title 列宽） */
    static final int TITLE_MAX = 32;

    static final int SUBTITLE_MAX = 80;

    /** 文案最多几行、一共多少字（kg_stay.diary_intro 列宽） */
    static final int INTRO_LINES = 3;
    static final int INTRO_MAX = 255;

    /** 生活碎片页每页几条 */
    static final int PAGE_SIZE = 20;

    /** 详情页入口卡上放几张缩略图 */
    static final int THUMBS = 3;

    static final String DEFAULT_TITLE = "小院日记";
    static final String SUBTITLE = "把日子过成一段可以翻看的记忆";

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgVideoUploads videos;

    static String str(Object o)
    {
        return o == null ? "" : String.valueOf(o);
    }

    // ---------------------------------------------------------------- 校验（纯函数，单测直接调）

    /** 封面 / 背景图 / 照片：'' 表示不要；否则必须是平台图片路径，而且不是视频 */
    static String image(Object raw, String what)
    {
        String v = str(raw).trim();
        if (v.isEmpty())
        {
            return "";
        }
        if (!JobImages.isUpload(v) || KgVideoUploads.isPlatformVideo(v))
        {
            throw new ServiceException(what + "只能用本平台上传的图片", 422);
        }
        return v;
    }

    /** 视频：'' 表示删掉；否则必须是平台视频上传接口给的路径，而且文件确实在 */
    String video(Object raw)
    {
        String v = str(raw).trim();
        if (v.isEmpty())
        {
            return "";
        }
        if (!KgVideoUploads.isPlatformVideo(v) || (videos != null && !videos.exists(v)))
        {
            throw new ServiceException("视频地址不对，请重新上传视频", 422);
        }
        return v;
    }

    /** 单行文字：换行、连续空白并成一个空格，超长报 422 */
    static String oneLine(Object raw, int max, String what)
    {
        String v = str(raw).trim().replaceAll("\\s+", " ");
        if (v.length() > max)
        {
            throw new ServiceException(what + "最多 " + max + " 个字", 422);
        }
        return v;
    }

    /** 文案：按换行拆，空行去掉，每行压成一行，最多 3 行、一共 255 字（换行也算），用 \n 连起来存 */
    static String introLines(Object raw)
    {
        List<String> lines = new ArrayList<String>();
        for (String s : str(raw).split("\\r?\\n|\\r"))
        {
            String t = s.trim().replaceAll("\\s+", " ");
            if (!t.isEmpty())
            {
                lines.add(t);
            }
        }
        if (lines.size() > INTRO_LINES)
        {
            throw new ServiceException("文案最多 " + INTRO_LINES + " 行", 422);
        }
        String joined = String.join("\n", lines);
        if (joined.length() > INTRO_MAX)
        {
            throw new ServiceException("文案最多 " + INTRO_MAX + " 个字", 422);
        }
        return joined;
    }

    /** 拍摄日期：没填用今天；要是真实的日期，不能晚于明天（时区差一天的余量），不能早于 2000 年 */
    static String shotDate(Object raw)
    {
        String v = str(raw).trim();
        if (v.isEmpty())
        {
            return LocalDate.now().toString();
        }
        if (!KgJdbc.isDate(v))
        {
            throw new ServiceException("拍摄日期格式不对", 422);
        }
        LocalDate d = LocalDate.parse(v);
        if (d.isAfter(LocalDate.now().plusDays(1)) || d.getYear() < 2000)
        {
            throw new ServiceException("拍摄日期不对，请选今天或以前的日期", 422);
        }
        return v;
    }

    /** "2026-10-12" → "10.12"（卡片左上角的日期角标） */
    static String mmdd(Object date)
    {
        String s = str(date);
        return s.length() >= 10 && s.charAt(4) == '-' ? s.substring(5, 7) + "." + s.substring(8, 10) : "";
    }

    /** 存的文案 → 页面的几行；没填时用一句默认的 */
    static List<String> lines(Object intro, String stayName)
    {
        List<String> out = new ArrayList<String>();
        for (String s : str(intro).split("\n"))
        {
            if (!s.trim().isEmpty())
            {
                out.add(s.trim());
            }
        }
        if (out.isEmpty())
        {
            out.add("这里是" + (str(stayName).trim().isEmpty() ? "小院" : str(stayName).trim()) + "的真实日常");
        }
        return out;
    }

    // ---------------------------------------------------------------- 员工端

    private Map<String, Object> ownStay(StaffSupport.Ctx c, Long stayId)
    {
        Map<String, Object> s = stayId == null ? null : db.one("select * from kg_stay where stay_id = :id and del_flag = '0'", p("id", stayId));
        if (s == null || !c.me.hasStoreAccess(KgJdbc.asLong(s.get("storeId"))))
        {
            throw new ServiceException("房源不存在或没有权限", HttpStatus.FORBIDDEN);
        }
        return s;
    }

    /** 一条生活碎片给页面的样子（员工端多带 status / hidden） */
    static Map<String, Object> entry(Map<String, Object> r, boolean staff)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("diaryId", KgJdbc.asLong(r.get("diaryId")));
        String type = "video".equals(str(r.get("mediaType"))) ? "video" : "image";
        m.put("type", type);
        m.put("url", str(r.get("mediaUrl")));
        m.put("cover", "video".equals(type) ? str(r.get("coverUrl")) : str(r.get("mediaUrl")));
        m.put("caption", str(r.get("caption")));
        String d = str(r.get("shotDate"));
        m.put("shotDate", d.length() >= 10 ? d.substring(0, 10) : d);
        m.put("date", mmdd(d));
        if (staff)
        {
            m.put("hidden", "1".equals(str(r.get("status"))));
            m.put("sort", KgJdbc.asLong(r.get("sort")));
        }
        return m;
    }

    static final String ORDER = " order by d.sort, d.shot_date desc, d.diary_id desc";

    /** 员工端「视频与生活碎片」一屏的全部数据 */
    public Map<String, Object> media(StaffSupport.Ctx c, Long stayId)
    {
        Map<String, Object> s = ownStay(c, stayId);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("stayId", stayId);
        out.put("stayName", s.get("stayName"));
        out.put("status", s.get("status"));
        out.put("cover", str(s.get("cover")));
        Map<String, Object> intro = new LinkedHashMap<String, Object>();
        intro.put("url", str(s.get("introVideo")));
        intro.put("cover", str(s.get("introVideoCover")));
        out.put("introVideo", intro);
        Map<String, Object> diary = new LinkedHashMap<String, Object>();
        diary.put("bg", str(s.get("diaryBg")));
        diary.put("title", str(s.get("diaryTitle")));
        diary.put("intro", str(s.get("diaryIntro")));
        diary.put("defaultTitle", DEFAULT_TITLE);
        diary.put("subtitle", str(s.get("diarySubtitle")));
        diary.put("defaultSubtitle", SUBTITLE);
        out.put("diary", diary);
        List<Map<String, Object>> rooms = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> rt : db.list("select room_type_id, type_name, cover, video_url, video_cover, status from kg_stay_room_type "
                + "where stay_id = :s and del_flag = '0' order by sort, room_type_id", p("s", stayId)))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("roomTypeId", KgJdbc.asLong(rt.get("roomTypeId")));
            m.put("typeName", rt.get("typeName"));
            m.put("cover", str(rt.get("cover")));
            m.put("videoUrl", str(rt.get("videoUrl")));
            m.put("videoCover", str(rt.get("videoCover")));
            m.put("status", rt.get("status"));
            rooms.add(m);
        }
        out.put("roomTypes", rooms);
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : db.list("select d.* from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0'" + ORDER + " limit " + DIARY_MAX,
                p("s", stayId)))
        {
            entries.add(entry(r, true));
        }
        out.put("entries", entries);
        Map<String, Object> limits = new LinkedHashMap<String, Object>();
        limits.put("videoMb", KgVideoUploads.MAX_BYTES / 1024 / 1024);
        limits.put("videoSeconds", KgVideoUploads.MAX_SECONDS);
        limits.put("captionMax", CAPTION_MAX);
        limits.put("titleMax", TITLE_MAX);
        limits.put("introLines", INTRO_LINES);
        limits.put("diaryMax", DIARY_MAX);
        out.put("limits", limits);
        return out;
    }

    /** 小院视频（「3分钟看小院」）：url = '' 删掉（封面一起清）；换视频没给封面时清掉旧封面（旧封面是上一个视频的画面） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setIntroVideo(StaffSupport.Ctx c, Long stayId, Map<String, Object> body)
    {
        Map<String, Object> s = ownStay(c, stayId);
        String url = video(body.get("url"));
        String cover = url.isEmpty() ? "" : image(body.get("cover"), "视频封面");
        db.update("update kg_stay set intro_video = :v, intro_video_cover = :c, update_by = :u, update_time = now() where stay_id = :s",
                p("s", stayId, "v", url, "c", cover, "u", "app:" + c.uid()));
        support.op(c, "stay_media", url.isEmpty() ? "删除小院视频" : "更新小院视频", "general", str(s.get("stayName")), true, null);
        return media(c, stayId);
    }

    /** 房型短视频：url = '' 删掉 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setRoomVideo(StaffSupport.Ctx c, Long roomTypeId, Map<String, Object> body)
    {
        Map<String, Object> rt = db.one("select room_type_id, stay_id, type_name from kg_stay_room_type where room_type_id = :r and del_flag = '0'", p("r", roomTypeId));
        if (rt == null)
        {
            throw new ServiceException("房型不存在", 404);
        }
        Long stayId = KgJdbc.asLong(rt.get("stayId"));
        Map<String, Object> s = ownStay(c, stayId);
        String url = video(body.get("url"));
        String cover = url.isEmpty() ? "" : image(body.get("cover"), "视频封面");
        db.update("update kg_stay_room_type set video_url = :v, video_cover = :c, update_by = :u, update_time = now() where room_type_id = :r",
                p("r", roomTypeId, "v", url, "c", cover, "u", "app:" + c.uid()));
        support.op(c, "stay_media", url.isEmpty() ? "删除房型视频" : "更新房型视频", "general", str(s.get("stayName")) + " · " + str(rt.get("typeName")), true, null);
        return media(c, stayId);
    }

    /** 生活碎片顶部：背景图、标题、文案（只改带了的） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setDiaryHeader(StaffSupport.Ctx c, Long stayId, Map<String, Object> body)
    {
        Map<String, Object> s = ownStay(c, stayId);
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        if (body.containsKey("bg"))
        {
            cols.put("diary_bg", image(body.get("bg"), "背景图"));
        }
        if (body.containsKey("title"))
        {
            cols.put("diary_title", oneLine(body.get("title"), TITLE_MAX, "标题"));
        }
        if (body.containsKey("subtitle"))
        {
            cols.put("diary_subtitle", oneLine(body.get("subtitle"), SUBTITLE_MAX, "副标题"));
        }
        if (body.containsKey("intro"))
        {
            cols.put("diary_intro", introLines(body.get("intro")));
        }
        if (!cols.isEmpty())
        {
            cols.put("update_by", "app:" + c.uid());
            cols.put("update_time", new Date());
            db.updateRow("kg_stay", "stay_id", stayId, cols);
            support.op(c, "stay_diary", "更新生活碎片封面与文案", "general", str(s.get("stayName")), true, null);
        }
        return media(c, stayId);
    }

    /** 加一条生活碎片：{mediaType: image | video, url, cover（视频封面，选填）, caption 一句话, shotDate 拍摄日期（选填，默认今天）} */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> addDiary(StaffSupport.Ctx c, Long stayId, Map<String, Object> body)
    {
        Map<String, Object> s = db.must("select * from kg_stay where stay_id = :id and del_flag = '0' for update", p("id", stayId), "房源不存在");
        ownStay(c, stayId);
        String type = str(body.get("mediaType")).trim();
        if (!Arrays.asList("image", "video").contains(type))
        {
            throw new ServiceException("请选择照片或视频", 422);
        }
        String url = "video".equals(type) ? video(body.get("url")) : image(body.get("url"), "照片");
        if (url.isEmpty())
        {
            throw new ServiceException("video".equals(type) ? "请先上传视频" : "请先上传照片", 422);
        }
        String cover = "video".equals(type) ? image(body.get("cover"), "视频封面") : "";
        String caption = oneLine(body.get("caption"), CAPTION_MAX, "一句话");
        String date = shotDate(body.get("shotDate"));
        if (db.count("select count(*) from kg_stay_diary where stay_id = :s and del_flag = '0'", p("s", stayId)) >= DIARY_MAX)
        {
            throw new ServiceException("一个小院最多 " + DIARY_MAX + " 条生活碎片，请先删掉一些旧的", 422);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("stay_id", stayId);
        row.put("store_id", s.get("storeId"));
        row.put("media_type", type);
        row.put("media_url", url);
        row.put("cover_url", cover);
        row.put("caption", caption);
        row.put("shot_date", date);
        row.put("sort", 0);
        row.put("status", "0");
        row.put("create_by", "app:" + c.uid());
        row.put("create_time", new Date());
        row.put("del_flag", "0");
        db.insert("kg_stay_diary", row);
        support.op(c, "stay_diary", "添加生活碎片", "general", str(s.get("stayName")) + (caption.isEmpty() ? "" : " · " + caption), true, null);
        return media(c, stayId);
    }

    /** 在通用后台增改事务内校验、锁住房源并推导门店归属。 */
    public void validateAdminDiary(Map<String, Object> cols, boolean creating)
    {
        Map<String, Object> old = creating ? new LinkedHashMap<String, Object>()
                : db.must("select * from kg_stay_diary where diary_id = :id and del_flag = '0' for update", p("id", cols.get("_pk")), "生活碎片不存在");
        Long stayId = KgJdbc.asLong(creating ? cols.get("stay_id") : old.get("stayId"));
        Map<String, Object> stay = db.must("select * from kg_stay where stay_id = :id and del_flag = '0' for update", p("id", stayId), "请选择有效房源");
        if (creating && db.count("select count(*) from kg_stay_diary where stay_id = :s and del_flag = '0'", p("s", stayId)) >= DIARY_MAX)
            throw new ServiceException("一个小院最多 " + DIARY_MAX + " 条生活碎片，请先删除旧内容", 422);
        String type = str(cols.containsKey("media_type") ? cols.get("media_type") : old.get("mediaType")).trim();
        if (!Arrays.asList("image", "video").contains(type)) throw new ServiceException("请选择照片或视频", 422);
        Object rawUrl = cols.containsKey("media_url") ? cols.get("media_url") : old.get("mediaUrl");
        String url = "video".equals(type) ? video(rawUrl) : image(rawUrl, "照片");
        if (url.isEmpty()) throw new ServiceException("请先上传照片或视频", 422);
        Object rawCover = cols.containsKey("cover_url") ? cols.get("cover_url") : old.get("coverUrl");
        cols.put("stay_id", stayId);
        cols.put("store_id", stay.get("storeId"));
        cols.put("media_type", type);
        cols.put("media_url", url);
        cols.put("cover_url", "video".equals(type) ? image(rawCover, "视频封面") : "");
        if (creating || cols.containsKey("caption")) cols.put("caption", oneLine(cols.get("caption"), CAPTION_MAX, "一句话"));
        if (creating || cols.containsKey("shot_date")) cols.put("shot_date", shotDate(cols.get("shot_date")));
        if (creating) { cols.put("sort", 0); if (!cols.containsKey("status")) cols.put("status", "0"); }
    }

    private Map<String, Object> ownDiary(StaffSupport.Ctx c, Long diaryId)
    {
        Map<String, Object> d = diaryId == null ? null : db.one("select * from kg_stay_diary where diary_id = :id and del_flag = '0'", p("id", diaryId));
        if (d == null)
        {
            throw new ServiceException("这条生活碎片不存在或已删除", 404);
        }
        ownStay(c, KgJdbc.asLong(d.get("stayId")));
        return d;
    }

    /** 改一条的文字和日期（照片 / 视频本身不能换，换就删掉重加） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> editDiary(StaffSupport.Ctx c, Long diaryId, Map<String, Object> body)
    {
        Map<String, Object> d = ownDiary(c, diaryId);
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        if (body.containsKey("caption"))
        {
            cols.put("caption", oneLine(body.get("caption"), CAPTION_MAX, "一句话"));
        }
        if (body.containsKey("shotDate"))
        {
            cols.put("shot_date", shotDate(body.get("shotDate")));
        }
        if ("video".equals(str(d.get("mediaType"))) && body.containsKey("cover"))
        {
            cols.put("cover_url", image(body.get("cover"), "视频封面"));
        }
        Long stayId = KgJdbc.asLong(d.get("stayId"));
        if (!cols.isEmpty())
        {
            cols.put("update_by", "app:" + c.uid());
            cols.put("update_time", new Date());
            db.updateRow("kg_stay_diary", "diary_id", diaryId, cols);
            support.op(c, "stay_diary", "修改生活碎片", "general", String.valueOf(diaryId), true, null);
        }
        return media(c, stayId);
    }

    /** 删一条（软删；文件留着，后台还能查到是谁传的） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteDiary(StaffSupport.Ctx c, Long diaryId)
    {
        Map<String, Object> d = ownDiary(c, diaryId);
        db.update("update kg_stay_diary set del_flag = '2', update_by = :u, update_time = now() where diary_id = :id and del_flag = '0'",
                p("id", diaryId, "u", "app:" + c.uid()));
        support.op(c, "stay_diary", "删除生活碎片", "general", String.valueOf(diaryId) + (str(d.get("caption")).isEmpty() ? "" : " · " + d.get("caption")), true, null);
        return media(c, KgJdbc.asLong(d.get("stayId")));
    }

    /** 排序：ids 是这个小院生活碎片的新顺序（可以只给一部分，没给的排在后面、原顺序不变） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> orderDiary(StaffSupport.Ctx c, Long stayId, Object rawIds)
    {
        Map<String, Object> s = ownStay(c, stayId);
        if (!(rawIds instanceof List) || ((List<?>) rawIds).isEmpty() || ((List<?>) rawIds).size() > DIARY_MAX)
        {
            throw new ServiceException("排序参数不对", 422);
        }
        List<Long> want = new ArrayList<Long>();
        for (Object o : (List<?>) rawIds)
        {
            Long id = KgJdbc.asLong(o);
            if (id == null || want.contains(id))
            {
                throw new ServiceException("排序参数不对", 422);
            }
            want.add(id);
        }
        List<Long> have = new ArrayList<Long>();
        for (Map<String, Object> r : db.list("select d.diary_id from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0'" + ORDER + " for update", p("s", stayId)))
        {
            have.add(KgJdbc.asLong(r.get("diaryId")));
        }
        if (!have.containsAll(want))
        {
            throw new ServiceException("有的生活碎片已经被删了，请刷新后再排", 409);
        }
        List<Long> order = new ArrayList<Long>(want);
        for (Long id : have)
        {
            if (!order.contains(id))
            {
                order.add(id);
            }
        }
        for (int i = 0; i < order.size(); i++)
        {
            db.update("update kg_stay_diary set sort = :n where diary_id = :id and stay_id = :s", p("n", i + 1, "id", order.get(i), "s", stayId));
        }
        support.op(c, "stay_diary", "调整生活碎片顺序", "general", str(s.get("stayName")), true, null);
        return media(c, stayId);
    }

    // ---------------------------------------------------------------- 游客端

    /** 详情页的小院视频：{url, cover}；没传视频返回 null（页面不出这张卡）。封面没传时用房源封面 */
    static Map<String, Object> introVideo(Map<String, Object> stay)
    {
        String url = str(stay.get("introVideo")).trim();
        if (!KgVideoUploads.isPlatformVideo(url))
        {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("url", url);
        String cover = str(stay.get("introVideoCover")).trim();
        m.put("cover", cover.isEmpty() ? str(stay.get("cover")) : cover);
        m.put("hasCover", !cover.isEmpty());
        return m;
    }

    /** 房型卡上的短视频：{videoUrl, videoCover}，没传时两项都是 ''（页面不出播放按钮） */
    static void roomVideo(Map<String, Object> rt, Map<String, Object> out)
    {
        String url = str(rt.get("videoUrl")).trim();
        boolean ok = KgVideoUploads.isPlatformVideo(url);
        out.put("videoUrl", ok ? url : "");
        out.put("videoCover", ok ? str(rt.get("videoCover")).trim() : "");
    }

    /** 详情页「近期生活碎片」入口卡：{count, thumbs: [缩略图，最多 3 张，视频用封面]}；没有显示中的返回 count 0 */
    public Map<String, Object> diarySummary(Long stayId)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        long n = db.count("select count(*) from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0' and d.status = '0'", p("s", stayId));
        m.put("count", n);
        List<String> thumbs = new ArrayList<String>();
        if (n > 0)
        {
            for (Map<String, Object> r : db.list("select d.media_type, d.media_url, d.cover_url from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0' "
                    + "and d.status = '0'" + ORDER + " limit 6", p("s", stayId)))
            {
                String t = "video".equals(str(r.get("mediaType"))) ? str(r.get("coverUrl")) : str(r.get("mediaUrl"));
                if (!t.isEmpty() && thumbs.size() < THUMBS)
                {
                    thumbs.add(t);
                }
            }
        }
        m.put("thumbs", thumbs);
        return m;
    }

    /**
     * 生活碎片页：顶部（背景图、标题、副标题、文案）+ 显示中的生活碎片（排序小的在前，同排序按拍摄日期新的在前），每页 20 条。
     * 房源不在架（与详情页同一口径）时 404
     */
    public Map<String, Object> diary(Long stayId, int pageNum)
    {
        Map<String, Object> s = stayId == null ? null : db.one("select s.stay_id, s.stay_name, s.cover, s.diary_subtitle, s.diary_bg, s.diary_title, s.diary_intro from kg_stay s "
                + "where s.stay_id = :id and " + KgStayBrowseService.ONLINE, p("id", stayId));
        if (s == null)
        {
            throw new ServiceException("房源不存在或已下架", 404);
        }
        if (pageNum < 1 || pageNum > 1000)
        {
            throw new ServiceException("页码不对", 422);
        }
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        String bg = str(s.get("diaryBg")).trim();
        header.put("bg", bg.isEmpty() ? str(s.get("cover")) : bg);
        String title = str(s.get("diaryTitle")).trim();
        header.put("title", title.isEmpty() ? DEFAULT_TITLE : title);
        String subtitle = str(s.get("diarySubtitle")).trim();
        header.put("subtitle", subtitle.isEmpty() ? SUBTITLE : subtitle);
        header.put("lines", lines(s.get("diaryIntro"), str(s.get("stayName"))));
        header.put("stayName", s.get("stayName"));
        Map<String, Object> page = db.page("select d.* from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0' and d.status = '0'" + ORDER,
                p("s", stayId), pageNum, PAGE_SIZE);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = (List<Map<String, Object>>) page.get("rows");
        for (Map<String, Object> r : raw)
        {
            rows.add(entry(r, false));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("stayId", stayId);
        out.put("header", header);
        out.put("rows", rows);
        out.put("total", page.get("total"));
        out.put("hasMore", page.get("hasMore"));
        out.put("pageNum", pageNum);
        return out;
    }

    /** 岗位的店里日常：门店上传照片 + 本店在架小院的公开生活碎片，共用原来的播放和分页格式。 */
    public Map<String, Object> storeDiary(Long storeId, List<String> jobPhotos, int pageNum)
    {
        if (pageNum < 1 || pageNum > 1000) throw new ServiceException("页码不对", 422);
        Map<String, Object> store = db.one("select store_id, store_name, cover, daily_images from kg_store "
                + "where store_id = :id and del_flag = '0' and status = '0'", p("id", storeId));
        if (store == null) throw new ServiceException("门店不存在或已停用", 404);
        Map<String, Object> stay = db.one("select s.stay_id, s.stay_name, s.cover, s.diary_bg, s.diary_title, s.diary_subtitle, s.diary_intro "
                + "from kg_stay s where s.store_id = :id and " + KgStayBrowseService.ONLINE + " order by s.sort, s.stay_id limit 1", p("id", storeId));
        Map<String, Map<String, Object>> published = new LinkedHashMap<String, Map<String, Object>>();
        if (stay != null)
        {
            for (Map<String, Object> r : db.list("select d.* from kg_stay_diary d where d.stay_id = :s and d.del_flag = '0' and d.status = '0'"
                    + ORDER + " limit " + DIARY_MAX, p("s", stay.get("stayId"))))
            {
                Map<String, Object> item = entry(r, false);
                String url = str(item.get("url"));
                if (!url.isEmpty()) published.putIfAbsent(url, item);
            }
        }
        // 门店资料里手动选的照片排在前面；与生活碎片相同的照片保留原日期和说明，且只出现一次。
        Map<String, Map<String, Object>> merged = new LinkedHashMap<String, Map<String, Object>>();
        List<String> photos = JobImages.read(str(store.get("dailyImages")));
        if (photos.isEmpty() && published.isEmpty() && jobPhotos != null) photos = jobPhotos;
        long photoId = -1;
        for (String url : photos)
        {
            if (!JobImages.isUpload(url)) continue;
            Map<String, Object> item = published.get(url);
            if (item == null)
            {
                item = p("diaryId", photoId--, "type", "image", "url", url, "cover", url,
                        "caption", "门店照片", "date", "", "shotDate", "");
            }
            merged.putIfAbsent(url, item);
        }
        for (Map.Entry<String, Map<String, Object>> item : published.entrySet()) merged.putIfAbsent(item.getKey(), item.getValue());

        String name = str(store.get("storeName"));
        Map<String, Object> h = stay == null ? new LinkedHashMap<String, Object>() : stay;
        String bg = str(h.get("diaryBg"));
        if (bg.isEmpty()) bg = str(h.get("cover"));
        if (bg.isEmpty()) bg = str(store.get("cover"));
        if (bg.isEmpty() && !merged.isEmpty()) bg = str(merged.values().iterator().next().get("cover"));
        Map<String, Object> header = p("bg", bg, "title", str(h.get("diaryTitle")).isEmpty() ? DEFAULT_TITLE : str(h.get("diaryTitle")),
                "subtitle", str(h.get("diarySubtitle")).isEmpty() ? SUBTITLE : str(h.get("diarySubtitle")),
                "lines", lines(h.get("diaryIntro"), name), "storeName", name);
        List<Map<String, Object>> all = new ArrayList<Map<String, Object>>(merged.values());
        int start = Math.min((pageNum - 1) * PAGE_SIZE, all.size());
        int end = Math.min(start + PAGE_SIZE, all.size());
        return p("storeId", storeId, "header", header, "rows", new ArrayList<Map<String, Object>>(all.subList(start, end)),
                "total", all.size(), "pageNum", pageNum, "hasMore", end < all.size());
    }
}
