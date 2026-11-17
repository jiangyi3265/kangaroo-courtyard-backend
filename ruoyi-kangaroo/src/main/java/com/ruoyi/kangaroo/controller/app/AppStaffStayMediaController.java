package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.stay.KgStayMediaService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 员工端「视频与生活碎片」（2026-10-17 旅居改版 R3，V53）：小院视频、房型短视频、生活碎片顶部和每一条。
 * 和「编辑房源」同一档权限（房源·编辑）；上线中的房源也能直接改（主人拍板：补充展示内容不用下架重审）。
 * 视频先走 /app/upload/video 分段上传拿到平台路径，再在这里挂上去；图片走 /app/upload/image。
 *  GET    /app/staff/stay/stays/{stayId}/media                  一屏的全部数据
 *  PUT    /app/staff/stay/stays/{stayId}/intro-video            {url, cover}（url = '' 删掉）
 *  PUT    /app/staff/stay/room-types/{roomTypeId}/video         {url, cover}
 *  PUT    /app/staff/stay/stays/{stayId}/diary-header           {bg, title, subtitle, intro}
 *  POST   /app/staff/stay/stays/{stayId}/diary                  {mediaType, url, cover, caption, shotDate}
 *  PUT    /app/staff/stay/diary/{diaryId}                       {caption, shotDate, cover}
 *  DELETE /app/staff/stay/diary/{diaryId}
 *  PUT    /app/staff/stay/stays/{stayId}/diary-order            {ids: [...]}
 * 每个改动都返回最新的一屏数据。
 */
@RestController
@RequestMapping("/app/staff/stay")
// 合作商家能管自己门店的房源、房型和 Vlog（AppStaffStayController 各方法都标了 merchant = true），房源「更多 → 视频与生活碎片」也要放行，
// 之前只标 staff，商家点进去一律 403；能不能改仍按 rooms 编辑权限判（edit() 里的 support.need）
@AppAuth(staff = true, merchant = true)
public class AppStaffStayMediaController
{
    @Autowired
    private KgStayMediaService media;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgJdbc db;

    @Autowired
    private AppRateLimiter limiter;

    private StaffSupport.Ctx ctxOfStay(Long stayId)
    {
        Map<String, Object> r = db.one("select store_id from kg_stay where stay_id = :id and del_flag = '0'", p("id", stayId));
        if (r == null)
        {
            throw new ServiceException("房源不存在", 404);
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private StaffSupport.Ctx ctxOfRoomType(Long roomTypeId)
    {
        Map<String, Object> r = db.one("select s.store_id from kg_stay_room_type t join kg_stay s on s.stay_id = t.stay_id where t.room_type_id = :id",
                p("id", roomTypeId));
        if (r == null)
        {
            throw new ServiceException("房型不存在", 404);
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private StaffSupport.Ctx ctxOfDiary(Long diaryId)
    {
        Map<String, Object> r = db.one("select store_id from kg_stay_diary where diary_id = :id and del_flag = '0'", p("id", diaryId));
        if (r == null)
        {
            throw new ServiceException("这条生活碎片不存在或已删除", 404);
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private static Map<String, Object> body(Map<String, Object> b)
    {
        return b == null ? new LinkedHashMap<String, Object>() : b;
    }

    /** 改动类的统一把关：房源·编辑权限 + 每人每小时 300 次 */
    private StaffSupport.Ctx edit(StaffSupport.Ctx c, String action, String label)
    {
        support.need(c, "rooms", StaffPerms.EDIT, action, label);
        limiter.check("staff:stay-media", String.valueOf(c.uid()), 300, 3600, "操作太频繁了，请稍后再试");
        return c;
    }

    @GetMapping("/stays/{stayId}/media")
    public AjaxResult get(@PathVariable Long stayId)
    {
        return AjaxResult.success(media.media(ctxOfStay(stayId), stayId));
    }

    @PutMapping("/stays/{stayId}/intro-video")
    public AjaxResult introVideo(@PathVariable Long stayId, @RequestBody(required = false) Map<String, Object> b)
    {
        StaffSupport.Ctx c = edit(ctxOfStay(stayId), "stay_media", "更新小院视频");
        return AjaxResult.success("已保存", media.setIntroVideo(c, stayId, body(b)));
    }

    @PutMapping("/room-types/{roomTypeId}/video")
    public AjaxResult roomVideo(@PathVariable Long roomTypeId, @RequestBody(required = false) Map<String, Object> b)
    {
        StaffSupport.Ctx c = edit(ctxOfRoomType(roomTypeId), "stay_media", "更新房型视频");
        return AjaxResult.success("已保存", media.setRoomVideo(c, roomTypeId, body(b)));
    }

    @PutMapping("/stays/{stayId}/diary-header")
    public AjaxResult diaryHeader(@PathVariable Long stayId, @RequestBody(required = false) Map<String, Object> b)
    {
        StaffSupport.Ctx c = edit(ctxOfStay(stayId), "stay_diary", "更新生活碎片封面与文案");
        return AjaxResult.success("已保存", media.setDiaryHeader(c, stayId, body(b)));
    }

    @PostMapping("/stays/{stayId}/diary")
    public AjaxResult addDiary(@PathVariable Long stayId, @RequestBody(required = false) Map<String, Object> b)
    {
        StaffSupport.Ctx c = edit(ctxOfStay(stayId), "stay_diary", "添加生活碎片");
        return AjaxResult.success("已添加", media.addDiary(c, stayId, body(b)));
    }

    @PutMapping("/diary/{diaryId}")
    public AjaxResult editDiary(@PathVariable Long diaryId, @RequestBody(required = false) Map<String, Object> b)
    {
        StaffSupport.Ctx c = edit(ctxOfDiary(diaryId), "stay_diary", "修改生活碎片");
        return AjaxResult.success("已保存", media.editDiary(c, diaryId, body(b)));
    }

    @DeleteMapping("/diary/{diaryId}")
    public AjaxResult deleteDiary(@PathVariable Long diaryId)
    {
        StaffSupport.Ctx c = edit(ctxOfDiary(diaryId), "stay_diary", "删除生活碎片");
        return AjaxResult.success("已删除", media.deleteDiary(c, diaryId));
    }

    @PutMapping("/stays/{stayId}/diary-order")
    public AjaxResult orderDiary(@PathVariable Long stayId, @RequestBody(required = false) Map<String, Object> b)
    {
        StaffSupport.Ctx c = edit(ctxOfStay(stayId), "stay_diary", "调整生活碎片顺序");
        return AjaxResult.success("已调整", media.orderDiary(c, stayId, body(b).get("ids")));
    }
}
