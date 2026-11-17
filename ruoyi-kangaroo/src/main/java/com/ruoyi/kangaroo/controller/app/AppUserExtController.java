package com.ruoyi.kangaroo.controller.app;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.ext.user.KgHomeService;
import com.ruoyi.kangaroo.ext.user.KgResumeService;
import com.ruoyi.kangaroo.ext.user.KgSupportService;
import com.ruoyi.kangaroo.ext.user.KgUserHubService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 用户端扩展：城市、首页与找机会、智能客服与工单、简历、订单聚合、评价、提醒设置、体验官、同行队伍、新机会订阅、岗位筛选、责任书与到店信息。
 */
@RestController
@RequestMapping("/app")
public class AppUserExtController
{
    @Autowired
    private KgCityService cities;

    @Autowired
    private KgHomeService home;

    @Autowired
    private KgSupportService support;

    @Autowired
    private KgResumeService resume;

    @Autowired
    private KgUserHubService hub;

    @Autowired
    private com.ruoyi.kangaroo.ext.user.KgSubscribeService subscribe;

    @Autowired
    private AppRateLimiter limiter;

    private static String s(Map<String, Object> b, String k)
    {
        return b == null ? null : KgJdbc.asStr(b.get(k));
    }

    private static Long me()
    {
        return AppUserContext.getUserId();
    }

    @Autowired private com.ruoyi.kangaroo.ext.user.KgCampService camp;

    @AppAuth(required = false)
    @GetMapping("/camp/groups")
    public AjaxResult campGroups(@RequestParam(defaultValue = "") String current,
            @RequestParam(defaultValue = "") String destination, @RequestParam(defaultValue = "0") long visitedAt) {
        return AjaxResult.success(camp.groups(AppUserContext.currentUserId(), current, destination, visitedAt));
    }

    // ---------------------------------------------------------------- 城市 / 首页

    @AppAuth(required = false)
    @GetMapping("/cities/coordinator")
    public AjaxResult coordinator(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(cities.coordinator(city));
    }

    @AppAuth(required = false)
    @GetMapping("/cities")
    public AjaxResult cities()
    {
        return AjaxResult.success(cities.cities());
    }

    @AppAuth(required = false)
    @GetMapping("/cities/locate")
    public AjaxResult locate(@RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double accuracy, @RequestParam(defaultValue = "wgs84") String coordinateType)
    {
        return AjaxResult.success(cities.locate(lat, lng, accuracy, coordinateType));
    }

    @AppAuth(required = false)
    @GetMapping("/cities/overview")
    public AjaxResult overview(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(cities.overview(city));
    }

    @AppAuth(required = false)
    @GetMapping("/home/feed")
    public AjaxResult feed(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(home.feed(AppUserContext.currentUserId(), city));
    }

    @AppAuth(required = false)
    @PostMapping("/home/match")
    public AjaxResult match(@RequestBody Map<String, Object> body, HttpServletRequest req)
    {
        limiter.check("home:match", AppUserContext.currentUserId() == null ? IpUtils.getIpAddr(req) : String.valueOf(AppUserContext.currentUserId()), 30, 600, "匹配太频繁了，稍后再试");
        return AjaxResult.success(home.match(AppUserContext.currentUserId(), body));
    }

    // ---------------------------------------------------------------- 客服

    @AppAuth(required = false)
    @PostMapping("/support/ask")
    public AjaxResult ask(@RequestBody Map<String, Object> body, HttpServletRequest req)
    {
        limiter.check("support:ask", AppUserContext.currentUserId() == null ? IpUtils.getIpAddr(req) : String.valueOf(AppUserContext.currentUserId()), 40, 600, "提问太频繁了，稍后再试");
        return AjaxResult.success(support.ask(AppUserContext.currentUserId(), s(body, "context"), s(body, "question"), s(body, "city"), s(body, "orderNo")));
    }

    // 主人（2026-11-03）：「历史聊天记录，永久保存，不能删除」。只有查看和追加，没有删除 / 清空接口
    @AppAuth
    @GetMapping("/support/history")
    public AjaxResult supportHistory(@RequestParam(required = false) Long before, @RequestParam(required = false) Integer size)
    {
        return AjaxResult.success(support.history(me(), before, size));
    }

    @AppAuth
    @PostMapping("/support/faq-read")
    public AjaxResult faqRead(@RequestBody Map<String, Object> body)
    {
        limiter.check("support:ask", String.valueOf(me()), 40, 600, "提问太频繁了，稍后再试");
        return AjaxResult.success(support.faqRead(me(), s(body, "context"), KgJdbc.asLong(body.get("faqId"))));
    }

    @AppAuth(required = false)
    @GetMapping("/support/faqs")
    public AjaxResult faqs() { return AjaxResult.success(support.faqs()); }

    @AppAuth(required = false)
    @GetMapping("/support/quick")
    public AjaxResult quick(@RequestParam(required = false) String context)
    {
        // 登录了就把按用户状态预测的问题排在最前（报名进度 / 入住和退款 / 查看客服回复 / 怎么提交成果）
        return AjaxResult.success(support.quickQuestions(AppUserContext.currentUserId(), context));
    }

    @AppAuth(required = false)
    @PostMapping("/support/feedback")
    public AjaxResult feedback(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(support.feedback(AppUserContext.currentUserId(), KgJdbc.asLong(body.get("logId")), Boolean.parseBoolean(s(body, "helpful"))));
    }

    @AppAuth
    @PostMapping("/support/tickets")
    public AjaxResult createTicket(@RequestBody Map<String, Object> body)
    {
        limiter.check("support:ticket", String.valueOf(me()), 10, 3600, "提交太频繁了，稍后再试");
        // 在员工身份下提交的，客服回复也发回员工身份；和消息中心一样，要真有员工授权才算，不能靠传参切过去
        AppLoginUser u = AppUserContext.require();
        return AjaxResult.success(support.createTicket(u.getUserId(), body, "staff".equals(s(body, "mode")) && u.hasStaffAccess()));
    }

    @AppAuth(required = false)
    @GetMapping("/support/contact")
    public AjaxResult supportContact()
    {
        return AjaxResult.success(support.contact());
    }

    @AppAuth
    @GetMapping("/support/tickets")
    public AjaxResult tickets()
    {
        return AjaxResult.success(support.myTickets(me()));
    }

    @AppAuth
    @PostMapping("/support/tickets/{id}/rate")
    public AjaxResult rate(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(support.rate(me(), id, (int) (long) KgJdbc.asLong(body.get("score"))));
    }

    // ---------------------------------------------------------------- 简历 / 找机会

    @AppAuth
    @GetMapping("/resume")
    public AjaxResult resume()
    {
        return AjaxResult.success(resume.get(me()));
    }

    @AppAuth
    @PutMapping("/resume/basic")
    public AjaxResult basic(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("已保存", resume.saveBasic(me(), body));
    }

    @AppAuth
    @PutMapping("/resume/skills")
    public AjaxResult skills(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("已保存", resume.saveSkills(me(), body));
    }

    @AppAuth
    @PostMapping("/resume/reuse")
    public AjaxResult reuse()
    {
        return AjaxResult.success("已从最近一次报名复用", resume.reuseLast(me()));
    }

    @AppAuth
    @PostMapping("/resume/exps")
    public AjaxResult addExp(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("已添加", resume.saveExp(me(), null, body));
    }

    @AppAuth
    @PutMapping("/resume/exps/{id}")
    public AjaxResult editExp(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("已保存", resume.saveExp(me(), id, body));
    }

    @AppAuth
    @DeleteMapping("/resume/exps/{id}")
    public AjaxResult removeExp(@PathVariable Long id)
    {
        return AjaxResult.success("已删除", resume.removeExp(me(), id));
    }

    @AppAuth
    @GetMapping("/resume/opportunities")
    public AjaxResult opportunities(@RequestParam(required = false) String city)
    {
        return AjaxResult.success(resume.opportunities(me(), city));
    }

    // ---------------------------------------------------------------- 订单 / 评价 / 提醒 / 体验官

    @AppAuth
    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(required = false) String tab)
    {
        return AjaxResult.success(hub.orders(me(), tab));
    }

    @AppAuth
    @GetMapping("/reviews/todo")
    public AjaxResult todo()
    {
        return AjaxResult.success(hub.reviewsTodo(me()));
    }

    @AppAuth
    @GetMapping("/reviews/mine")
    public AjaxResult mine(@RequestParam(defaultValue = "false") boolean featured)
    {
        return AjaxResult.success(hub.reviewsMine(me(), featured));
    }

    @AppAuth
    @PostMapping("/reviews")
    public AjaxResult review(@RequestBody Map<String, Object> body)
    {
        limiter.check("review:write", String.valueOf(me()), 20, 3600, "提交太频繁了，稍后再试");
        return AjaxResult.success(hub.writeReview(me(), body));
    }

    @AppAuth(required = false)
    @GetMapping("/stores/{storeId}/reviews")
    public AjaxResult storeReviews(@PathVariable Long storeId, @RequestParam(required = false) String filter, @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int days, @RequestParam(defaultValue = "1") int pageNum, @RequestParam(required = false) Long stayId)
    {
        return AjaxResult.success(hub.storeReviews(storeId, filter, sort, days, pageNum, stayId));
    }

    @AppAuth
    @GetMapping("/notify-prefs")
    public AjaxResult prefs()
    {
        return AjaxResult.success(hub.prefs(me()));
    }

    @AppAuth
    @PutMapping("/notify-prefs")
    public AjaxResult setPref(@RequestBody Map<String, Object> body)
    {
        Object e = body.get("enabled");
        return AjaxResult.success(hub.setPref(me(), s(body, "key"), e == null ? null : Boolean.parseBoolean(String.valueOf(e)), s(body, "freq")));
    }

    @AppAuth
    @PostMapping("/guide-apply")
    public AjaxResult guideApply(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(hub.guideApply(me(), body));
    }

    @AppAuth
    @GetMapping("/guide-apply")
    public AjaxResult guideStatus()
    {
        return AjaxResult.success(hub.guideStatus(me()));
    }

    @AppAuth
    @GetMapping("/route")
    public AjaxResult route(@RequestParam(required = false) Long storeId, @RequestParam(required = false) Long stayId)
    {
        return AjaxResult.success(hub.route(me(), storeId, stayId));
    }

    // ---------------------------------------------------------------- 同行队伍 / 订阅 / 筛选

    @AppAuth(required = false)
    @GetMapping("/jobs/{jobId}/team")
    public AjaxResult team(@PathVariable Long jobId)
    {
        return AjaxResult.success(hub.team(AppUserContext.currentUserId(), jobId));
    }

    @AppAuth
    @PostMapping("/jobs/{jobId}/team")
    public AjaxResult joinTeam(@PathVariable Long jobId, @RequestBody(required = false) Map<String, Object> body)
    {
        return AjaxResult.success(hub.joinTeam(me(), jobId, body == null || body.get("visible") == null || Boolean.parseBoolean(s(body, "visible"))));
    }

    @AppAuth
    @GetMapping("/job-subscriptions")
    public AjaxResult subs()
    {
        return AjaxResult.success(hub.subscriptions(me()));
    }

    @AppAuth
    @PostMapping("/job-subscriptions")
    public AjaxResult addSub(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(hub.saveSubscription(me(), null, body));
    }

    @AppAuth
    @PutMapping("/job-subscriptions/{id}")
    public AjaxResult editSub(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(hub.saveSubscription(me(), id, body));
    }

    @AppAuth
    @DeleteMapping("/job-subscriptions/{id}")
    public AjaxResult removeSub(@PathVariable Long id)
    {
        return AjaxResult.success(hub.removeSubscription(me(), id));
    }

    /** 微信订阅消息：当前授权状态与要申请的模板 */
    @AppAuth
    @GetMapping("/subscribe/status")
    public AjaxResult subscribeStatus()
    {
        return AjaxResult.success(subscribe.status(me()));
    }

    @PostMapping("/subscribe/prepare")
    public AjaxResult subscribePrepare() { return AjaxResult.success(subscribe.prepare(me())); }

    /** 记录 wx.requestSubscribeMessage 的授权结果 */
    @AppAuth
    @PostMapping("/subscribe/accept")
    public AjaxResult subscribeAccept(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(subscribe.accept(me(), body));
    }

    /** 关掉某一类微信提醒（把授权额度清零） */
    @AppAuth
    @PostMapping("/subscribe/revoke")
    public AjaxResult subscribeRevoke(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(subscribe.revoke(me(), s(body, "bizKey")));
    }

    /** 通知偏好设置（提醒页设置屏） */
    @AppAuth
    @GetMapping("/notify-settings")
    public AjaxResult notifySettings()
    {
        return AjaxResult.success(hub.notifySettings(me()));
    }

    @AppAuth
    @PostMapping("/notify-settings")
    public AjaxResult saveNotifySettings(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(hub.saveNotifySettings(me(), body));
    }

    /** 关注 / 取消关注门店（有新岗位时通知） */
    @AppAuth
    @PostMapping("/stores/{storeId}/follow")
    public AjaxResult followStore(@PathVariable Long storeId, @RequestParam(defaultValue = "true") boolean follow)
    {
        return AjaxResult.success(hub.followStore(me(), storeId, follow));
    }

    @AppAuth(required = false)
    @PostMapping("/jobs/search")
    public AjaxResult search(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(hub.search(AppUserContext.currentUserId(), body));
    }

    // ---------------------------------------------------------------- 责任书 / 到店信息

    @AppAuth
    @GetMapping("/application/{appId}/contract")
    public AjaxResult contract(@PathVariable Long appId)
    {
        return AjaxResult.success(hub.contract(me(), appId));
    }

    @AppAuth
    @GetMapping("/application/{appId}/region-group")
    public AjaxResult regionGroup(@PathVariable Long appId)
    {
        return AjaxResult.success(hub.regionGroup(me(), appId));
    }

    @AppAuth
    @PostMapping("/application/{appId}/contract/sign")
    public AjaxResult sign(@PathVariable Long appId, @RequestBody Map<String, Object> body, HttpServletRequest req)
    {
        AppLoginUser u = AppUserContext.require();
        return AjaxResult.success("责任书已签署", hub.sign(u.getUserId(), u.getNickname(), appId, body, IpUtils.getIpAddr(req)));
    }

    @AppAuth
    @PutMapping("/application/{appId}/arrival")
    public AjaxResult arrival(@PathVariable Long appId, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success("到店信息已保存并通知门店", hub.saveArrival(me(), appId, body));
    }
}
