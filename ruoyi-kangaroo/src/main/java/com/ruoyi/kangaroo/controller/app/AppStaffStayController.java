package com.ruoyi.kangaroo.controller.app;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayPmsService;
import com.ruoyi.kangaroo.ext.stay.KgStayStaffService;
import com.ruoyi.kangaroo.ext.stay.KgStayVlogService;
import com.ruoyi.kangaroo.ext.staff.StaffPerms;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 员工端旅居：房源、房态、订单、到店核销、退款审批。门店以记录所属门店的授权与角色为准。
 */
@RestController
@RequestMapping("/app/staff/stay")
@AppAuth(staff = true)
public class AppStaffStayController
{
    @Autowired private com.ruoyi.kangaroo.ext.stay.KgStayAfterRefundService afterRefunds;
    @AppAuth(staff = true, merchant = true)
    @GetMapping("/pms/orders/{orderNo}/after-refund")
    public AjaxResult afterRefundInfo(@PathVariable String orderNo) {
        return AjaxResult.success(afterRefunds.staffOverview(ctxOfStayOrder(orderNo),orderNo));
    }
    @AppAuth(staff = true, merchant = true)
    @PostMapping("/pms/orders/{orderNo}/after-refund")
    public AjaxResult afterRefundApply(@PathVariable String orderNo,@RequestBody Map<String,Object> b) {
        StaffSupport.Ctx c=ctxOfStayOrder(orderNo);
        limiter.check("staff-after-refund",String.valueOf(c.uid()),20,3600,"申请过于频繁，请稍后再试");
        String raw=String.valueOf(b.get("amountFen"));
        if(!raw.matches("[1-9][0-9]{0,9}"))throw new com.ruoyi.common.exception.ServiceException("退款金额格式不正确",422);
        return AjaxResult.success(afterRefunds.staffApply(c,orderNo,Long.parseLong(raw),
            b.get("reason")==null?"":b.get("reason").toString(),b.get("requestKey")==null?"":b.get("requestKey").toString()));
    }

    @Autowired
    private KgStayStaffService staff;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgJdbc db;

    @Autowired
    private AppRateLimiter limiter;

    @Autowired
    private KgStayVlogService vlogs;

    @Autowired
    private KgStayPmsService pms;

    private StaffSupport.Ctx ctxOfStay(Long stayId)
    {
        Map<String, Object> r = db.one("select store_id from kg_stay where stay_id = :id", p("id", stayId));
        if (r == null)
        {
            throw new ServiceException("房源不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private StaffSupport.Ctx ctxOfRoomType(Long roomTypeId)
    {
        Map<String, Object> r = db.one("select s.store_id from kg_stay_room_type t join kg_stay s on s.stay_id = t.stay_id where t.room_type_id = :id",
                p("id", roomTypeId));
        if (r == null)
        {
            throw new ServiceException("房型不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private static String s(Map<String, Object> body, String k)
    {
        return body == null ? null : KgJdbc.asStr(body.get(k));
    }

    // ---------- 房源 ----------

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/stays")
    public AjaxResult stays(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(staff.stays(support.ctx(storeId), status, keyword));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/stays/{stayId}")
    public AjaxResult stay(@PathVariable Long stayId)
    {
        return AjaxResult.success(staff.stay(ctxOfStay(stayId), stayId));
    }

    /** 编辑表单的选项（2026-10-17 R2）：所在区县（这座城市的区县）、配套设施（字典 kg_stay_facility）。编辑带 stayId，新建带 storeId */
    @AppAuth(staff = true, merchant = true)
    @GetMapping("/form-options")
    public AjaxResult formOptions(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Long stayId)
    {
        return AjaxResult.success(staff.formOptions(stayId != null ? ctxOfStay(stayId) : support.ctx(storeId), stayId));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/stays")
    public AjaxResult create(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_create", "新建房源");
        return AjaxResult.success("已保存为草稿", staff.save(c, null, body));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/stays/{stayId}")
    public AjaxResult edit(@PathVariable Long stayId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfStay(stayId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_edit", "编辑房源");
        return AjaxResult.success("已保存", staff.save(c, stayId, body));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/stays/{stayId}/location")
    public AjaxResult location(@PathVariable Long stayId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfStay(stayId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_location", "设置房源地图位置");
        return AjaxResult.success("地图位置已保存", staff.location(c, stayId, body));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/stays/{stayId}/submit")
    public AjaxResult submit(@PathVariable Long stayId)
    {
        StaffSupport.Ctx c = ctxOfStay(stayId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_submit", "房源提交审核");
        return AjaxResult.success(staff.submit(c, stayId));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/stays/{stayId}/offline")
    public AjaxResult offline(@PathVariable Long stayId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfStay(stayId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_offline", "房源下架");
        return AjaxResult.success(staff.offline(c, stayId, s(body, "reason")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/stays/{stayId}/room-types")
    public AjaxResult createRoomType(@PathVariable Long stayId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfStay(stayId);
        support.need(c, "rooms", StaffPerms.EDIT, "roomtype_create", "新增房型");
        return AjaxResult.success("已保存", staff.saveRoomType(c, stayId, null, body));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/room-types/{roomTypeId}")
    public AjaxResult editRoomType(@PathVariable Long roomTypeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfRoomType(roomTypeId);
        support.need(c, "rooms", StaffPerms.EDIT, "roomtype_edit", "修改房型");
        return AjaxResult.success("已保存", staff.saveRoomType(c, null, roomTypeId, body));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/stays/{stayId}/group")
    public AjaxResult group(@PathVariable Long stayId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfStay(stayId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_group", "更新住客群码");
        return AjaxResult.success(staff.group(c, stayId, s(body, "name"), s(body, "qr"), s(body, "expire")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/stays/{stayId}/qualifications")
    public AjaxResult uploadQual(@PathVariable Long stayId, @RequestParam("type") String type,
            @RequestParam(value = "expire", required = false) String expire, @RequestParam("file") org.springframework.web.multipart.MultipartFile file)
    {
        StaffSupport.Ctx c = ctxOfStay(stayId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_qual", "上传资质");
        return AjaxResult.success("已上传", staff.uploadQual(c, stayId, type, expire, file));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/stays/{stayId}/qualifications/{type}/expire")
    public AjaxResult qualExpire(@PathVariable Long stayId, @PathVariable String type, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfStay(stayId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_qual", "资质有效期");
        return AjaxResult.success("已更新", staff.setQualExpire(c, stayId, type, s(body, "expire")));
    }

    // ---------- 房态 ----------

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/room-types/{roomTypeId}/calendar")
    public AjaxResult calendar(@PathVariable Long roomTypeId, @RequestParam(required = false) String month)
    {
        return AjaxResult.success(staff.calendar(ctxOfRoomType(roomTypeId), roomTypeId, month));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/room-types/{roomTypeId}/calendar")
    public AjaxResult setCalendar(@PathVariable Long roomTypeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfRoomType(roomTypeId);
        support.need(c, "rooms", StaffPerms.EDIT, "stay_calendar", "房态价格设置");
        return AjaxResult.success(staff.setCalendar(c, roomTypeId, body));
    }

    // ---------- 订单 ----------

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String tab,
            @RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(staff.stayOrders(support.ctx(storeId), tab, keyword));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/shop-orders")
    public AjaxResult shopOrders(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String tab,
            @RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(staff.shopOrders(support.ctx(storeId), tab, keyword));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/resale-orders")
    public AjaxResult resaleOrders(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String tab,
            @RequestParam(required = false) String biz, @RequestParam(required = false) Integer days, @RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(staff.resaleOrders(support.ctx(storeId), tab, biz, days, keyword));
    }

    // ---------- 旅居订单台：订单分组、房态图、排房、办理入住 / 退房、房间床位设置 ----------

    private StaffSupport.Ctx ctxOfStayOrder(String orderNo)
    {
        Map<String, Object> r = db.one("select store_id from kg_stay_order where order_no = :n and del_flag = '0'", p("n", orderNo));
        if (r == null)
        {
            throw new ServiceException("订单不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private StaffSupport.Ctx ctxOfUnit(Long unitId)
    {
        Map<String, Object> r = db.one("select store_id from kg_stay_unit where unit_id = :u and del_flag = '0'", p("u", unitId));
        if (r == null)
        {
            throw new ServiceException("房间 / 床位不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private static List<Long> ids(Object v)
    {
        List<Long> out = new ArrayList<Long>();
        if (v instanceof List)
        {
            for (Object o : (List<?>) v)
            {
                Long id = KgJdbc.asLong(o);
                if (id != null)
                {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static List<String> names(Object v)
    {
        List<String> out = new ArrayList<String>();
        if (v instanceof List)
        {
            for (Object o : (List<?>) v)
            {
                out.add(o == null ? "" : String.valueOf(o));
            }
        }
        return out;
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/pms/overview")
    public AjaxResult pmsOverview(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Long stayId)
    {
        StaffSupport.Ctx c = stayId != null ? ctxOfStay(stayId) : support.ctx(storeId);
        return AjaxResult.success(pms.overview(c, stayId));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/pms/orders")
    public AjaxResult pmsOrders(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Long stayId,
            @RequestParam(required = false) String tab, @RequestParam(required = false) String keyword)
    {
        StaffSupport.Ctx c = stayId != null ? ctxOfStay(stayId) : support.ctx(storeId);
        return AjaxResult.success(pms.orders(c, tab, stayId, keyword));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/pms/orders/{orderNo}")
    public AjaxResult pmsOrder(@PathVariable String orderNo)
    {
        return AjaxResult.success(pms.detail(ctxOfStayOrder(orderNo), orderNo));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/pms/orders/{orderNo}/units")
    public AjaxResult pmsUnitOptions(@PathVariable String orderNo)
    {
        return AjaxResult.success(pms.unitOptions(ctxOfStayOrder(orderNo), orderNo));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/pms/orders/{orderNo}/assign")
    public AjaxResult pmsAssign(@PathVariable String orderNo, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("排房成功", pms.assign(ctxOfStayOrder(orderNo), orderNo, ids(body == null ? null : body.get("unitIds"))));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/pms/orders/{orderNo}/unassign")
    public AjaxResult pmsUnassign(@PathVariable String orderNo)
    {
        return AjaxResult.success("已取消排房", pms.unassign(ctxOfStayOrder(orderNo), orderNo));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/pms/orders/{orderNo}/check-in")
    public AjaxResult pmsCheckIn(@PathVariable String orderNo)
    {
        StaffSupport.Ctx c = ctxOfStayOrder(orderNo);
        limiter.check("stay-checkin", String.valueOf(c.uid()), 30, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success("已办理入住", pms.checkIn(c, orderNo));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/pms/orders/{orderNo}/check-out")
    public AjaxResult pmsCheckOut(@PathVariable String orderNo)
    {
        StaffSupport.Ctx c = ctxOfStayOrder(orderNo);
        limiter.check("stay-checkout", String.valueOf(c.uid()), 30, 60, "操作太频繁，请稍后再试");
        Map<String, Object> r = pms.checkOut(c, orderNo);
        return AjaxResult.success(String.valueOf(r.get("message")), r);
    }

    /**
     * 门店主动退款（2026-10-11 新增）：只有本店主理人；金额（分）自己填、必须写原因；idemKey 是这次提交的编号，连点 / 重试只退一次。
     * 主理人校验放在控制器：拒绝时记的失败日志不随业务事务回滚（和同意退款一样）
     */
    @PostMapping("/pms/orders/{orderNo}/refund")
    public AjaxResult pmsRefund(@PathVariable String orderNo, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfStayOrder(orderNo);
        support.needManager(c, "stay_staff_refund", "门店退款");
        limiter.check("stay-staff-refund", String.valueOf(c.uid()), 10, 60, "操作太频繁，请稍后再试");
        Map<String, Object> r = pms.refund(c, orderNo, body == null ? null : body.get("amountFen"), s(body, "reason"), s(body, "idemKey"));
        return AjaxResult.success(String.valueOf(r.get("message")), r);
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/pms/orders/{orderNo}/note")
    public AjaxResult pmsNote(@PathVariable String orderNo, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("备注已保存", pms.note(ctxOfStayOrder(orderNo), orderNo, s(body, "note")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/pms/auto-assign")
    public AjaxResult pmsAutoAssign(@RequestBody Map<String, Object> body)
    {
        Long stayId = KgJdbc.asLong(body == null ? null : body.get("stayId"));
        if (stayId == null)
        {
            throw new ServiceException("缺少房源");
        }
        Long days = KgJdbc.asLong(body.get("days"));
        Map<String, Object> r = pms.autoAssign(ctxOfStay(stayId), stayId, days == null ? 30 : days.intValue());
        return AjaxResult.success(String.valueOf(r.get("message")), r);
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/pms/board")
    public AjaxResult pmsBoard(@RequestParam Long stayId, @RequestParam(required = false) String start, @RequestParam(required = false) Integer days)
    {
        return AjaxResult.success(pms.board(ctxOfStay(stayId), stayId, start, days == null ? 7 : days));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/pms/units")
    public AjaxResult pmsUnits(@RequestParam Long stayId)
    {
        return AjaxResult.success(pms.units(ctxOfStay(stayId), stayId));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/pms/units")
    public AjaxResult pmsAddUnits(@RequestBody Map<String, Object> body)
    {
        Long roomTypeId = KgJdbc.asLong(body == null ? null : body.get("roomTypeId"));
        if (roomTypeId == null)
        {
            throw new ServiceException("缺少房型");
        }
        return AjaxResult.success("已添加", pms.addUnits(ctxOfRoomType(roomTypeId), roomTypeId, names(body.get("names"))));
    }

    @AppAuth(staff = true, merchant = true)
    @PutMapping("/pms/units/{unitId}")
    public AjaxResult pmsUpdateUnit(@PathVariable Long unitId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("已保存", pms.updateUnit(ctxOfUnit(unitId), unitId, s(body, "name"), s(body, "status")));
    }

    @AppAuth(staff = true, merchant = true)
    @DeleteMapping("/pms/units/{unitId}")
    public AjaxResult pmsRemoveUnit(@PathVariable Long unitId)
    {
        return AjaxResult.success("已删除", pms.removeUnit(ctxOfUnit(unitId), unitId));
    }

    // ---------- 核销 ----------

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/verify/resolve")
    public AjaxResult resolve(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        limiter.check("verify-resolve", String.valueOf(c.uid()), 60, 60, "核对太频繁，请稍后再试");
        return AjaxResult.success(staff.resolve(c, s(body, "code")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/verify/confirm")
    public AjaxResult confirm(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        limiter.check("verify-confirm", String.valueOf(c.uid()), 30, 60, "核销太频繁，请稍后再试");
        return AjaxResult.success("核销成功", staff.confirm(c, s(body, "code")));
    }

    // ---------- Vlog ----------

    private StaffSupport.Ctx ctxOfVlog(Long vlogId)
    {
        Map<String, Object> r = db.one("select store_id from kg_stay_vlog where vlog_id = :id", p("id", vlogId));
        if (r == null)
        {
            throw new ServiceException("视频不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    private StaffSupport.Ctx ctxOfComment(Long commentId)
    {
        Map<String, Object> r = db.one("select store_id from kg_stay_vlog_comment where comment_id = :id", p("id", commentId));
        if (r == null)
        {
            throw new ServiceException("评论不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/vlogs")
    public AjaxResult vlogLibrary(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Long stayId,
            @RequestParam(required = false) String platform, @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort)
    {
        return AjaxResult.success(vlogs.library(support.ctx(storeId), stayId, platform, status, sort));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/vlogs/data")
    public AjaxResult vlogData(@RequestParam(required = false) Long storeId, @RequestParam(defaultValue = "7") int days)
    {
        return AjaxResult.success(vlogs.data(support.ctx(storeId), days));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/vlogs/comments")
    public AjaxResult vlogComments(@RequestParam(required = false) Long storeId, @RequestParam(required = false) String tab)
    {
        return AjaxResult.success(vlogs.staffComments(support.ctx(storeId), tab));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/vlogs/handle-logs")
    public AjaxResult vlogHandleLogs(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(vlogs.handleLogs(support.ctx(storeId)));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/vlogs/{vlogId}")
    public AjaxResult vlogDetail(@PathVariable Long vlogId)
    {
        return AjaxResult.success(vlogs.detail(ctxOfVlog(vlogId), vlogId));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/vlogs")
    public AjaxResult vlogCreate(@RequestParam(required = false) Long storeId, @RequestBody Map<String, Object> body)
    {
        StaffSupport.Ctx c = support.ctx(storeId);
        support.need(c, "rooms", StaffPerms.EDIT, "vlog_create", "同步 Vlog");
        return AjaxResult.success("已提交，待审核", vlogs.create(c, body));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/vlogs/{vlogId}/approve")
    public AjaxResult vlogApprove(@PathVariable Long vlogId)
    {
        StaffSupport.Ctx c = ctxOfVlog(vlogId);
        support.need(c, "rooms", StaffPerms.AUDIT, "vlog_approve", "Vlog 审核");
        return AjaxResult.success(vlogs.approve(c, vlogId));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/vlogs/{vlogId}/reject")
    public AjaxResult vlogReject(@PathVariable Long vlogId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfVlog(vlogId);
        support.need(c, "rooms", StaffPerms.AUDIT, "vlog_reject", "Vlog 驳回");
        return AjaxResult.success(vlogs.reject(c, vlogId, s(body, "reason")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/vlogs/{vlogId}/offline")
    public AjaxResult vlogOffline(@PathVariable Long vlogId, @RequestBody(required = false) Map<String, Object> body)
    {
        StaffSupport.Ctx c = ctxOfVlog(vlogId);
        support.need(c, "rooms", StaffPerms.AUDIT, "vlog_offline", "Vlog 下架");
        return AjaxResult.success(vlogs.offline(c, vlogId, s(body, "reason")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/vlogs/comments/{commentId}/reply")
    public AjaxResult vlogReply(@PathVariable Long commentId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(vlogs.reply(ctxOfComment(commentId), commentId, s(body, "reply")));
    }

    @AppAuth(staff = true, merchant = true)
    @GetMapping("/vlogs/comments/{commentId}/draft")
    public AjaxResult vlogDraft(@PathVariable Long commentId)
    {
        return AjaxResult.success("ok", vlogs.draft(ctxOfComment(commentId), commentId));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/vlogs/comments/{commentId}/hide")
    public AjaxResult vlogHide(@PathVariable Long commentId, @RequestBody(required = false) Map<String, Object> body)
    {
        boolean hidden = body == null || body.get("hidden") == null || Boolean.parseBoolean(String.valueOf(body.get("hidden")));
        // 隐藏用户评论属于内容处置，与 Vlog 审核 / 下架同一道权限（房源管理·审核）；普通员工只能回复（2026-09-24 复核发现此前没把关）
        StaffSupport.Ctx c = ctxOfComment(commentId);
        support.need(c, "rooms", StaffPerms.AUDIT, hidden ? "vlog_comment_hide" : "vlog_comment_show", hidden ? "隐藏评论" : "恢复评论");
        return AjaxResult.success(vlogs.setHidden(c, commentId, hidden, s(body, "note")));
    }

    @AppAuth(staff = true, merchant = true)
    @PostMapping("/vlogs/comments/{commentId}/dismiss")
    public AjaxResult vlogDismiss(@PathVariable Long commentId)
    {
        StaffSupport.Ctx c = ctxOfComment(commentId);
        support.need(c, "rooms", StaffPerms.AUDIT, "vlog_report_dismiss", "驳回举报");
        return AjaxResult.success(vlogs.dismissReport(c, commentId));
    }

    // ---------- 退款审批 ----------

    @GetMapping("/refunds")
    public AjaxResult refunds(@RequestParam(required = false) Long storeId)
    {
        return AjaxResult.success(staff.refunds(support.ctx(storeId)));
    }

    private StaffSupport.Ctx ctxOfOrder(String bizType, String orderNo)
    {
        Map<String, Object> r = db.one("select store_id from " + ("shop".equals(bizType) ? "kg_shop_order" : "kg_stay_order") + " where order_no = :n",
                p("n", orderNo));
        if (r == null)
        {
            throw new ServiceException("订单不存在");
        }
        return support.ctx(KgJdbc.asLong(r.get("storeId")));
    }

    /**
     * 角色切换后旅居、服务订单的退款只由平台在后台审：门店谁来都是 403（权限和之前一样），只是统一说一句话。
     * 之前各角色的提示互相指（「已交给城市负责人」「由平台在后台处理」）。失败照样记一条资金操作日志
     */
    private void platformOnly(StaffSupport.Ctx c, String opAction, String opLabel)
    {
        if (com.ruoyi.kangaroo.ext.stay.KgStayOrderService.refundByPlatform())
        {
            support.op(c, opAction, opLabel, "fund", null, false, "旅居和服务订单退款由平台审核");
            throw new ServiceException("旅居和服务订单的退款由平台在后台审核，门店无需处理", com.ruoyi.common.constant.HttpStatus.FORBIDDEN);
        }
    }

    @PostMapping("/refunds/approve")
    public AjaxResult approve(@RequestBody Map<String, Object> body)
    {
        String biz = s(body, "bizType");
        String no = s(body, "orderNo");
        StaffSupport.Ctx c = ctxOfOrder(biz, no);
        platformOnly(c, "refund_approve", "同意退款");
        support.needManager(c, "refund_approve", "同意退款");
        return AjaxResult.success(staff.approve(c, biz, no));
    }

    @PostMapping("/refunds/reject")
    public AjaxResult reject(@RequestBody Map<String, Object> body)
    {
        String biz = s(body, "bizType");
        String no = s(body, "orderNo");
        StaffSupport.Ctx c = ctxOfOrder(biz, no);
        platformOnly(c, "refund_reject", "驳回退款");
        support.needManager(c, "refund_reject", "驳回退款");
        return AjaxResult.success(staff.reject(c, biz, no, s(body, "reason")));
    }
}
