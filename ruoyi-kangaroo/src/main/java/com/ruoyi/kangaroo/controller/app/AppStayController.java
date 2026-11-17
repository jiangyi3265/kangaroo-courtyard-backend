package com.ruoyi.kangaroo.controller.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayBrowseService;
import com.ruoyi.kangaroo.ext.stay.KgStayOrderService;
import com.ruoyi.kangaroo.ext.stay.KgStayVlogService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 旅居（用户侧）。浏览类游客可用（带了令牌仍解析账号，用于收藏标记）；下单、订单、退款需要登录。
 * 金额一律整数分（字段名 …Fen），页面自己换算成元。
 */
@RestController
@RequestMapping("/app/stay")
public class AppStayController
{
    @Autowired private com.ruoyi.kangaroo.ext.user.KgMemberWallet memberWallet;
    @Autowired
    private KgStayBrowseService browse;

    @Autowired
    private KgStayOrderService orders;

    @Autowired
    private com.ruoyi.kangaroo.ext.stay.KgStayActivitySelection selections;

    @Autowired
    private KgJdbc db;

    @Autowired
    private KgStayVlogService vlogs;

    @Autowired
    private AppRateLimiter limiter;

    private static Long uid()
    {
        AppLoginUser me = AppUserContext.get();
        return me == null ? null : me.getUserId();
    }

    private static int intOf(Object v, int dft)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? dft : l.intValue();
    }

    // ---------- 浏览 ----------

    @AppAuth(required = false)
    @GetMapping("/cities")
    public AjaxResult cities()
    {
        return AjaxResult.success(browse.cities());
    }

    @AppAuth(required = false)
    @GetMapping("/areas")
    public AjaxResult areas(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(browse.areas(city));
    }

    /** 列表「位置」下拉的区县（2026-10-17 R2）：{city, all, districts: [{name, count}]}，只带有上线房源的区县 */
    @AppAuth(required = false)
    @GetMapping("/districts")
    public AjaxResult districts(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(browse.districts(city));
    }

    @AppAuth(required = false)
    @GetMapping("/list")
    public AjaxResult list(@RequestParam Map<String, String> q)
    {
        limiter.checkByIp("stay-list", 300, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success(browse.list(q, uid()));
    }

    @AppAuth(required = false)
    @GetMapping("/promos")
    public AjaxResult promos(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(browse.promos(city));
    }

    @AppAuth(required = false)
    @GetMapping("/{stayId}")
    public AjaxResult detail(@PathVariable Long stayId)
    {
        return AjaxResult.success(browse.detail(stayId, uid()));
    }

    @AppAuth(required = false)
    @GetMapping("/{stayId}/room-types")
    public AjaxResult roomTypes(@PathVariable Long stayId, @RequestParam(required = false) String checkIn,
            @RequestParam(required = false) String checkOut, @RequestParam(defaultValue = "1") int guests,
            @RequestParam(required = false) Long promoId)
    {
        return AjaxResult.success(browse.roomTypes(stayId, checkIn, checkOut, guests, promoId));
    }

    /** 精选活动：同城、入住期间开始的免费社区活动（住宿订单不代收活动费，勾选的在住宿支付成功后逐个报名） */
    @AppAuth(required = false)
    @GetMapping("/{stayId}/addons")
    public AjaxResult addons(@PathVariable Long stayId, @RequestParam(required = false) String checkIn,
            @RequestParam(required = false) String checkOut)
    {
        Map<String, Object> s = db.one("select city from kg_stay where stay_id = :s and del_flag = '0'", KgJdbc.p("s", stayId));
        return AjaxResult.success(s == null ? new ArrayList<Object>() : activities.forStay(KgJdbc.asStr(s.get("city")), checkIn, checkOut));
    }

    @Autowired
    private com.ruoyi.kangaroo.ext.community.KgActivityService activities;

    @AppAuth(required = false)
    @GetMapping("/room-types/{roomTypeId}/calendar")
    public AjaxResult calendar(@PathVariable Long roomTypeId, @RequestParam(required = false) String month,
            @RequestParam(required = false) Long promoId)
    {
        return AjaxResult.success(browse.calendar(roomTypeId, month, promoId));
    }

    @AppAuth(required = false)
    @GetMapping("/room-types/{roomTypeId}/recommend")
    public AjaxResult recommend(@PathVariable Long roomTypeId, @RequestParam String checkIn, @RequestParam String checkOut,
            @RequestParam(defaultValue = "1") int guests)
    {
        return AjaxResult.success(browse.recommend(roomTypeId, checkIn, checkOut, guests));
    }

    // ---------- Vlog ----------

    @AppAuth(required = false)
    @GetMapping("/{stayId}/vlogs")
    public AjaxResult stayVlogs(@PathVariable Long stayId)
    {
        return AjaxResult.success(vlogs.forStay(stayId));
    }

    @AppAuth(required = false)
    @PostMapping("/vlogs/{vlogId}/open")
    public AjaxResult openVlog(@PathVariable Long vlogId)
    {
        limiter.checkByIp("vlog-open", 120, 60, "操作太频繁，请稍后再试");
        return AjaxResult.success(vlogs.open(vlogId, uid()));
    }

    /** 从 Vlog 进房源详情：event = detail */
    @AppAuth(required = false)
    @PostMapping("/vlogs/{vlogId}/track")
    public AjaxResult trackVlog(@PathVariable Long vlogId, @RequestParam String event)
    {
        limiter.checkByIp("vlog-track", 120, 60, "操作太频繁，请稍后再试");
        vlogs.track(vlogId, event);
        return AjaxResult.success();
    }

    @AppAuth
    @PostMapping("/vlogs/{vlogId}/like")
    public AjaxResult likeVlog(@PathVariable Long vlogId)
    {
        return AjaxResult.success(vlogs.like(vlogId, AppUserContext.getUserId()));
    }

    @AppAuth(required = false)
    @GetMapping("/vlogs/{vlogId}/comments")
    public AjaxResult vlogComments(@PathVariable Long vlogId)
    {
        return AjaxResult.success(vlogs.comments(vlogId));
    }

    @AppAuth
    @PostMapping("/vlogs/{vlogId}/comments")
    public AjaxResult commentVlog(@PathVariable Long vlogId, @RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("vlog-comment", String.valueOf(me), 10, 600, "评论太频繁，请稍后再试");
        return AjaxResult.success("评论已发布", vlogs.comment(vlogId, me, KgJdbc.asStr(body.get("content"))));
    }

    @AppAuth
    @PostMapping("/vlogs/comments/{commentId}/report")
    public AjaxResult reportComment(@PathVariable Long commentId)
    {
        vlogs.report(commentId, AppUserContext.getUserId());
        return AjaxResult.success("已举报，平台会尽快处理");
    }

    // ---------- 收藏 ----------

    @AppAuth
    @PostMapping("/{stayId}/favorite")
    public AjaxResult favorite(@PathVariable Long stayId, @RequestParam(defaultValue = "true") boolean favorite)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("stay-fav", String.valueOf(me), 60, 60, "操作太频繁，请稍后再试");
        browse.detail(stayId, me);
        if (favorite)
        {
            db.update("insert ignore into kg_favorite (target_type, target_id, app_user_id, create_time) values ('stay', :s, :u, now())",
                    KgJdbc.p("s", stayId, "u", me));
        }
        else
        {
            db.update("delete from kg_favorite where target_type = 'stay' and target_id = :s and app_user_id = :u", KgJdbc.p("s", stayId, "u", me));
        }
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("favorite", favorite);
        return AjaxResult.success(favorite ? "已收藏" : "已取消收藏", r);
    }

    @AppAuth
    @GetMapping("/favorites")
    public AjaxResult favorites(@RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "10") int pageSize)
    {
        return AjaxResult.success(db.page("select s.stay_id, s.stay_name, s.cover, s.area_desc, s.rating, s.review_count, "
                + "(select min(" + com.ruoyi.kangaroo.ext.stay.KgSettlementMath.saleSql("t.base_price_fen", "st.op_type") + ") from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0') price_from_fen "
                + "from kg_favorite f join kg_stay s on s.stay_id = f.target_id left join kg_store st on st.store_id=s.store_id where f.target_type = 'stay' and f.app_user_id = :u "
                + "and s.del_flag = '0' order by f.create_time desc", KgJdbc.p("u", AppUserContext.getUserId()), pageNum, pageSize));
    }

    // ---------- 报价与下单 ----------

    /** 报价游客也能看（预订页房态日历要实时算总价）；登录按账号限流，游客按 IP 限流 */
    @AppAuth(required = false)
    @PostMapping("/orders/quote")
    public AjaxResult quote(@RequestBody Map<String, Object> body)
    {
        Long me = uid();
        if (me == null)
        {
            limiter.checkByIp("stay-quote", 60, 60, "操作太频繁，请稍后再试");
        }
        else
        {
            limiter.check("stay-quote", String.valueOf(me), 60, 60, "操作太频繁，请稍后再试");
        }
        return AjaxResult.success(memberWallet.quote(me,body));
    }

    @AppAuth
    @PostMapping("/orders")
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("stay-order", String.valueOf(me), 20, 3600, "下单太频繁，请稍后再试");
        Map<String, Object> r = orders.create(me, body);
        // 从 Vlog 进来的预订记一次转化（只用于门店看 Vlog 数据，不影响订单）
        vlogs.track(KgJdbc.asLong(body.get("vlogId")), "order");
        return AjaxResult.success(r);
    }

    @AppAuth
    @GetMapping("/orders/mine")
    public AjaxResult mine(@RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize)
    {
        return AjaxResult.success(orders.mine(AppUserContext.getUserId(), status, pageNum, pageSize));
    }

    @AppAuth
    @GetMapping("/orders/current")
    public AjaxResult current()
    {
        return AjaxResult.success(orders.current(AppUserContext.getUserId()));
    }

    @AppAuth
    @GetMapping("/orders/{orderNo}")
    public AjaxResult order(@PathVariable String orderNo)
    {
        return AjaxResult.success(orders.detail(AppUserContext.getUserId(), orderNo));
    }

    @AppAuth
    @PostMapping("/orders/{orderNo}/cancel")
    public AjaxResult cancel(@PathVariable String orderNo)
    {
        orders.cancel(AppUserContext.getUserId(), orderNo);
        return AjaxResult.success("订单已取消");
    }

    @AppAuth
    @PostMapping("/orders/{orderNo}/activities/{activityId}")
    public AjaxResult enrollActivity(@PathVariable String orderNo, @PathVariable Long activityId)
    {
        return AjaxResult.success(selections.enroll(AppUserContext.getUserId(), orderNo, activityId));
    }

    @AppAuth
    @GetMapping("/orders/{orderNo}/refund-preview")
    public AjaxResult refundPreview(@PathVariable String orderNo)
    {
        return AjaxResult.success(orders.refundPreview(AppUserContext.getUserId(), orderNo));
    }

    @AppAuth
    @PostMapping("/orders/{orderNo}/refund")
    public AjaxResult refund(@PathVariable String orderNo, @RequestBody(required = false) Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("stay-refund", String.valueOf(me), 10, 86400, "今天的退款申请次数已用完");
        // 角色切换后由平台在后台审，之前门店审
        return AjaxResult.success("退款申请已提交，等待" + com.ruoyi.kangaroo.ext.stay.KgStayOrderService.refundReviewer() + "审核", orders.applyRefund(me, orderNo,
                body == null ? null : KgJdbc.asStr(body.get("reason")), body == null ? null : KgJdbc.asLong(body.get("expectedRefundFen"))));
    }

    @AppAuth
    @PostMapping("/orders/{orderNo}/verify-code/refresh")
    public AjaxResult refreshCode(@PathVariable String orderNo)
    {
        Long me = AppUserContext.getUserId();
        limiter.check("stay-code", me + ":" + orderNo, 10, 86400, "今天刷新次数已用完");
        return AjaxResult.success("核销码已刷新，旧码作废", orders.refreshCode(me, orderNo));
    }
}
