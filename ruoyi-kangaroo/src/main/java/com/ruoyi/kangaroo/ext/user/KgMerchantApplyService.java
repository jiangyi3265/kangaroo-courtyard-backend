package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.ext.staff.KgMerchantRequestService;
import com.ruoyi.kangaroo.security.AppTokenService;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;
import com.ruoyi.kangaroo.util.MaskUtils;

/** 商家入驻申请先审核；仅后台通过后建店并授予商家身份，既有授权不变。 */
@Service
public class KgMerchantApplyService
{
    /** 店铺类型：值 → 中文（和后台下拉、前端胶囊同一套） */
    public static final Map<String, String> STORE_TYPES = ordered("homestay", "民宿", "hotel", "酒店", "cafe", "咖啡餐饮", "bar", "酒吧", "other", "其他");
    /** 想招的人：值 → 中文 */
    public static final Map<String, String> WANT_TYPES = ordered("volunteer", "换工", "parttime", "兼职", "fulltime", "全职", "online", "线上任务");
    /** 状态：值 → 用户看到的说法 */
    public static final Map<String, String> STATUS_TEXT = ordered("new", "申请审核中，通过后开通商家身份", "contacted", "袋鼠全球青年已和你联系，正在沟通合作细节",
            "settled", "已入驻，欢迎加入袋鼠全球青年", "rejected", "这次暂时没能合作，有新情况可以再提交");
    /** 还没处理完的状态：手上有这两种时不让再交 */
    static final List<String> OPEN = Collections.unmodifiableList(Arrays.asList("new", "contacted"));

    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");

    private final KgJdbc db;

    @Autowired(required = false)
    private KgCommunityGuard guard;

    @Autowired(required = false)
    private KgCityService cities;

    @Autowired(required = false)
    private IKgStaffGrantService grants;

    @Autowired(required = false)
    private AppTokenService tokens;

    @Autowired(required = false)
    private IKgMessageService messages;

    public KgMerchantApplyService(KgJdbc db)
    {
        this.db = db;
    }

    /** 测试里换内容审核用 */
    void setGuard(KgCommunityGuard guard)
    {
        this.guard = guard;
    }

    private static Map<String, String> ordered(String... kv)
    {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    private static String text(Object v)
    {
        String s = KgJdbc.asStr(v);
        // 连续空白（含换行、全角空格）并成一个空格：后台列表一行显示
        return s == null ? "" : s.replaceAll("[\\s\\u3000]+", " ").trim();
    }

    private static String need(Object v, String label, int max)
    {
        String s = text(v);
        if (s.isEmpty())
        {
            throw new ServiceException("请填写" + label, 422);
        }
        if (s.length() > max)
        {
            throw new ServiceException(label + "最多 " + max + " 个字", 422);
        }
        return s;
    }

    /** 提交申请：校验每一项，通过后落库，返回给页面的提示和这份申请的进度 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(Long me, Map<String, Object> b)
    {
        return apply(me, b, null);
    }

    /**
     * 同上；beforeSave 在全部校验通过、真正落库（落「待联系」或建店开通）之前调一次：接口的每日次数在这里计，
     * 被校验挡回的（没验证手机号、城市对不上、漏填）不算次数（AppMerchantController）。抛异常整笔回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(Long me, Map<String, Object> b, Runnable beforeSave)
    {
        if (me == null)
        {
            throw new ServiceException("请先登录", 401);
        }
        Map<String, Object> body = b == null ? Collections.<String, Object>emptyMap() : b;
        String contact = need(body.get("contactName"), "联系人", 32);
        String phone = text(body.get("phone")).replaceAll("[\\s-]", "");
        if (!MOBILE.matcher(phone).matches())
        {
            throw new ServiceException("请填写 11 位手机号", 422);
        }
        String city = need(body.get("city"), "城市", 32);
        String storeType = text(body.get("storeType"));
        if (!STORE_TYPES.containsKey(storeType))
        {
            throw new ServiceException("请选择店铺类型", 422);
        }
        String storeName = need(body.get("storeName"), "店铺名称", 64);
        // 介绍是选填（多行文字保留换行），最多 500 字
        String intro = KgJdbc.asStr(body.get("intro"));
        intro = intro == null ? "" : intro.replace("\r\n", "\n").trim();
        if (intro.length() > 500)
        {
            throw new ServiceException("简单介绍最多 500 个字", 422);
        }
        List<String> wants = new ArrayList<String>();
        Object raw = body.get("wantTypes");
        List<?> list = raw instanceof List ? (List<?>) raw : raw == null ? Collections.emptyList() : Arrays.asList(String.valueOf(raw).split(","));
        for (Object o : list)
        {
            String w = text(o);
            if (!WANT_TYPES.containsKey(w))
            {
                throw new ServiceException("想招的人只能选换工、兼职、全职、线上任务", 422);
            }
            if (!wants.contains(w))
            {
                wants.add(w);
            }
        }
        // 违禁词直接拒（不回显命中的词）；有联系方式没关系，这张表本来就是给后台联系用的
        if (guard != null)
        {
            guard.check(contact, storeName, intro, city);
        }
        // 同一账号同时点两次：锁住账号行排队，后到的那次会看到已经开通，不会建第二家店
        Map<String, Object> account = db.one("select user_id, phone, phone_verified_time, status, del_flag from kg_app_user where user_id = :u for update", p("u", me));
        // 请求鉴权之后仍可能发生停用/注销；持有账号锁后复查，避免旧请求继续自动建店并授予商家身份。
        if (account == null || !"0".equals(KgJdbc.asStr(account.get("status"))) || !"0".equals(KgJdbc.asStr(account.get("delFlag"))))
        {
            throw new ServiceException("账号已注销或不可用", 409);
        }
        Map<String, Object> active = db.one("select g.grant_id, g.store_id, s.store_name, s.status store_status, s.del_flag store_del from kg_staff_grant g "
                + "join kg_store s on s.store_id = g.store_id where g.app_user_id = :u and g.role_code = 'merchant' and g.status = '0' and g.del_flag = '0' "
                + "order by g.grant_id limit 1", p("u", me));
        if (active != null && active.get("grantId") != null && "0".equals(KgJdbc.asStr(active.get("storeStatus"))) && "0".equals(KgJdbc.asStr(active.get("storeDel"))))
        {
            // 老版本的页面在「已入驻」时还会露出申请表，再交一次不能再建一家店
            return approved(me, KgJdbc.asLong(active.get("storeId")), KgJdbc.asStr(active.get("storeName")), KgJdbc.asLong(active.get("grantId")), "你已经是合作商家了");
        }
        Map<String, Object> open = db.one("select apply_id from kg_merchant_apply where app_user_id = :u and del_flag = '0' and status in ('new', 'contacted') order by apply_id desc limit 1", p("u", me));
        Long openId = open == null ? null : KgJdbc.asLong(open.get("applyId"));
        if (openId != null) throw new ServiceException("申请正在审核，请勿重复提交", 409);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_user_id", me);
        row.put("contact_name", contact);
        row.put("phone", phone);
        row.put("city", city);
        row.put("store_type", storeType);
        row.put("store_name", storeName);
        row.put("intro", intro);
        // 存中文（「换工、兼职」）：这一列只给后台的人看，存代码的话列表里是一串英文
        List<String> wantLabels = new ArrayList<String>();
        for (String w : wants)
        {
            wantLabels.add(WANT_TYPES.get(w));
        }
        row.put("want_types", String.join("、", wantLabels));
        String town = cities == null ? city : cities.dictionaryCity(city);
        if (town == null || town.trim().isEmpty())
        {
            throw new ServiceException("没有找到这个城市，请从列表里选择", 422);
        }
        String verified = account == null || account.get("phoneVerifiedTime") == null ? "" : text(account.get("phone"));
        if (verified.isEmpty())
        {
            throw new ServiceException("请先到「我的」验证手机号，再提交申请", 422);
        }
        if (!verified.equals(phone))
        {
            throw new ServiceException("请填写你验证过的手机号（" + MaskUtils.phone(verified) + "）", 422);
        }
        row.put("city", town);
        row.put("status", "new");
        row.put("follow_note", "");
        row.put("create_by", "app:" + me);
        row.put("create_time", new Date());
        if (beforeSave != null) beforeSave.run();
        db.insert("kg_merchant_apply", row);
        Map<String, Object> result = latest(me);
        result.put("approved", false);
        result.put("message", "申请已提交，平台审核通过后开通商家身份");
        return result;
    }

    /** 自动建的门店：加盟类型、状态正常，别的资料（封面、地址、采访）之后再补。同城同名的门店有唯一键，撞上如实提示 */
    private Long createStore(String by, String storeName, String town, String phone, String intro, String remark)
    {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("store_name", storeName);
        s.put("city", town.trim());
        s.put("contact_phone", phone);
        s.put("intro", intro);
        s.put("op_type", "franchise");
        s.put("status", "0");
        s.put("del_flag", "0");
        s.put("create_by", by);
        s.put("create_time", new Date());
        s.put("remark", remark);
        try
        {
            return db.insert("kg_store", s);
        }
        catch (DuplicateKeyException e)
        {
            throw new ServiceException("这个城市已经有同名的门店了，请换一个店铺名称，或联系袋鼠全球青年客服", 409);
        }
    }

    private Long createGrant(Long me, Long storeId)
    {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("app_user_id", me);
        g.put("store_id", storeId);
        g.put("role_code", StaffRole.MERCHANT);
        g.put("status", "0");
        g.put("granted_by", "system:merchant_apply");
        g.put("granted_time", new Date());
        g.put("del_flag", "0");
        g.put("create_by", "system:merchant_apply");
        g.put("create_time", new Date());
        return db.insert("kg_staff_grant", g);
    }

    /** 账号现在生效的合作商家授权（门店也正常）：grant_id, store_id, store_name；没有为 null */
    private Map<String, Object> activeMerchant(Long uid)
    {
        return uid == null ? null : db.one("select g.grant_id, g.store_id, s.store_name from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.app_user_id = :u and g.role_code = 'merchant' and g.status = '0' and g.del_flag = '0' and s.status = '0' and s.del_flag = '0' "
                + "order by g.grant_id limit 1", p("u", uid));
    }

    /**
     * 后台「商家入驻申请」页改跟进状态（KgMerchantModule 的保存前钩子；和这次保存同一个事务，保存失败一起回滚）。cols 是这次要写的列（列名 → 值）。
     * 状态变了就给用户发站内消息：已联系 / 暂不合作（这次在跟进备注里新写的内容当原因发出去，以前的备注不发）/ 已入驻。
     * 改成「已入驻」：账号已有生效的合作商家授权就用它；从没开过就走和自动开通同一套建店 + 开授权（createStore / createGrant）；
     * 申请回填 store_id、grant_id（直接加进这次保存的列）。以前开过、被撤销或门店停用的不自动恢复（那是平台或本人的决定），拒绝并说清先做什么。
     * 已入驻而且商家身份还在的，不能直接改成别的状态：页面会说「暂不合作」，人却还是商家。
     */
    public void adminFollow(Long applyId, Map<String, Object> cols, String operator)
    {
        if (applyId == null || cols == null || !cols.containsKey("status"))
        {
            return;
        }
        Map<String, Object> old = db.one("select apply_id, app_user_id, status, store_name, city, phone, intro, follow_note from kg_merchant_apply "
                + "where apply_id = :id and del_flag = '0' for update", p("id", applyId));
        if (old == null)
        {
            return;
        }
        String from = KgJdbc.asStr(old.get("status"));
        String to = text(cols.get("status"));
        if (to.isEmpty() || to.equals(from))
        {
            return;
        }
        Long uid = KgJdbc.asLong(old.get("appUserId"));
        String storeName = text(old.get("storeName"));
        if ("settled".equals(from) && activeMerchant(uid) != null)
        {
            throw new ServiceException("这个账号的合作商家身份还在。要结束合作，请先在「员工授权」里撤销他的合作商家授权，再改跟进状态", 409);
        }
        if ("settled".equals(to))
        {
            settleByAdmin(applyId, uid, old, cols, operator);
            return;
        }
        if ("contacted".equals(to))
        {
            notifyApplicant(uid, applyId, "商家入驻申请有新进展", "「" + storeName + "」：" + STATUS_TEXT.get("contacted") + "。");
        }
        else if ("rejected".equals(to))
        {
            String reason = newNote(old.get("followNote"), cols.containsKey("follow_note") ? cols.get("follow_note") : old.get("followNote"));
            notifyApplicant(uid, applyId, "商家入驻申请未通过", "「" + storeName + "」这次暂时没能合作" + (reason.isEmpty() ? "" : "，原因：" + reason) + "。有新情况可以再提交申请。");
        }
    }

    /** 这次保存在跟进备注里新写的内容：在原备注后面接着写的只取新的那段，整段改掉的取新的整段，没动就是空 */
    private static String newNote(Object before, Object after)
    {
        String a = before == null ? "" : String.valueOf(before).trim();
        String b = after == null ? "" : String.valueOf(after).trim();
        if (b.equals(a))
        {
            return "";
        }
        return (b.startsWith(a) ? b.substring(a.length()) : b).trim();
    }

    private void settleByAdmin(Long applyId, Long uid, Map<String, Object> old, Map<String, Object> cols, String operator)
    {
        Map<String, Object> account = uid == null ? null : db.one("select user_id, status, del_flag from kg_app_user where user_id = :u for update", p("u", uid));
        if (account == null || !"0".equals(KgJdbc.asStr(account.get("status"))) || !"0".equals(KgJdbc.asStr(account.get("delFlag"))))
        {
            throw new ServiceException("提交申请的账号已注销或停用，不能改成已入驻", 409);
        }
        Map<String, Object> active = activeMerchant(uid);
        Long storeId;
        Long grantId;
        String storeName;
        if (active != null)
        {
            // 已经是合作商家（后台先建了店、开了授权）：不重复建，记上这家店
            storeId = KgJdbc.asLong(active.get("storeId"));
            grantId = KgJdbc.asLong(active.get("grantId"));
            storeName = KgJdbc.asStr(active.get("storeName"));
        }
        else
        {
            Map<String, Object> past = db.one("select g.grant_id, s.store_name from kg_staff_grant g left join kg_store s on s.store_id = g.store_id "
                    + "where g.app_user_id = :u and g.role_code = 'merchant' order by g.grant_id desc limit 1", p("u", uid));
            if (past != null)
            {
                String name = text(past.get("storeName"));
                throw new ServiceException("这个账号以前开过合作商家" + (name.isEmpty() ? "" : "（门店「" + name + "」）") + "，授权已撤销或门店已停用，系统不自动恢复。"
                        + "请先在「员工授权」恢复合作商家授权、在门店管理里启用门店，再改成已入驻", 409);
            }
            String city = text(old.get("city"));
            String town = cities == null ? city : cities.dictionaryCity(city);
            String phone = text(old.get("phone"));
            storeName = text(old.get("storeName"));
            if (town == null || town.trim().isEmpty() || storeName.isEmpty() || phone.isEmpty())
            {
                throw new ServiceException("申请里的城市「" + city + "」在城市列表里对不上（或缺店名、手机号），不能自动建店。请先在门店管理建店、在「员工授权」开合作商家授权，再改成已入驻", 422);
            }
            String intro = KgJdbc.asStr(old.get("intro"));
            try
            {
                storeId = createStore("admin:" + operator, storeName, town, phone, intro == null ? "" : intro, "后台把入驻申请改成「已入驻」时开通（申请 " + applyId + "）");
            }
            catch (ServiceException e)
            {
                throw new ServiceException("「" + town.trim() + "」已经有同名门店「" + storeName + "」，不能自动建店。请先在门店管理确认或建店、在「员工授权」开合作商家授权，再改成已入驻", 409);
            }
            grantId = createGrant(uid, storeId);
        }
        cols.put("store_id", storeId);
        cols.put("grant_id", grantId);
        if (active != null)
        {
            notifyApplicant(uid, applyId, "商家入驻申请已通过", "「" + storeName + "」" + STATUS_TEXT.get("settled") + "。在「我的 → 成为商家」点「进入商家端」就能开始发布招募。");
            return;
        }
        // 新授权要进登录态：刷新会话，小程序不用重新登录就能进商家端；消息和自动开通同一条（用户身份一定收得到）
        if (tokens != null && grants != null)
        {
            tokens.refreshGrants(uid, grants.loadActiveGrantViews(uid));
        }
        if (grants != null)
        {
            grants.notifyGrantChange(uid, storeId, "你已成为合作商家", "「" + storeName + "」入驻申请已通过，已开通。切换到商家身份就可以发布招募和线上任务。", grantId);
        }
        else
        {
            notifyApplicant(uid, applyId, "商家入驻申请已通过", "「" + storeName + "」" + STATUS_TEXT.get("settled") + "。");
        }
    }

    /** 用户身份的站内消息，点开回「成为商家」页看进度 */
    private void notifyApplicant(Long uid, Long applyId, String title, String content)
    {
        if (messages != null && uid != null)
        {
            messages.send(uid, "user", "system", title, content, "merchant_apply", applyId, "/pages/profile/merchant");
        }
    }

    /** 开通后的返回：刷新登录态，把最新授权带回去，页面不用重新登录就能进商家端 */
    private Map<String, Object> approved(Long me, Long storeId, String storeName, Long grantId, String message)
    {
        List<StaffGrantView> views = grants == null ? Collections.<StaffGrantView>emptyList() : grants.loadActiveGrantViews(me);
        if (tokens != null)
        {
            tokens.refreshGrants(me, views);
        }
        Map<String, Object> m = latest(me);
        m.put("approved", true);
        m.put("status", "settled");
        m.put("storeId", storeId);
        m.put("storeName", storeName);
        m.put("grantId", grantId);
        m.put("roleCode", StaffRole.MERCHANT);
        m.put("staffGrants", views);
        m.put("message", message);
        return m;
    }

    /**
     * 自助退出合作商家：所有店都没有在招岗位、进行中的报名、订单和需求时才能退，有就如实说还差什么。
     * 退出：撤销自己的授权（revoked_by = app:账号，后台看得出是自己退的），被退回的需求和自己发的草稿关闭。
     * 这家店是「成为商家」自动开通的，而且没有平台开的其他员工：门店一并停用，系统派单补的授权一并撤销。
     * 再想开通要走人工（照旧落「待联系」），不能退了又开、一直新建门店。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> exit(Long me)
    {
        if (me == null)
        {
            throw new ServiceException("请先登录", 401);
        }
        db.one("select user_id from kg_app_user where user_id = :u for update", p("u", me));
        List<Map<String, Object>> mine = db.list("select g.grant_id, g.store_id, s.store_name from kg_staff_grant g join kg_store s on s.store_id = g.store_id "
                + "where g.app_user_id = :u and g.role_code = 'merchant' and g.status = '0' and g.del_flag = '0' order by g.grant_id", p("u", me));
        if (mine.isEmpty())
        {
            throw new ServiceException("你现在不是合作商家", 409);
        }
        List<String> blockers = new ArrayList<String>();
        for (Map<String, Object> g : mine)
        {
            blockers.addAll(exitBlockers(KgJdbc.asLong(g.get("storeId")), KgJdbc.asStr(g.get("storeName"))));
        }
        if (!blockers.isEmpty())
        {
            throw new ServiceException("暂时不能退出：" + String.join("；", blockers), 409);
        }
        String by = "app:" + me;
        List<Long> otherUsers = new ArrayList<Long>();
        for (Map<String, Object> g : mine)
        {
            Long grantId = KgJdbc.asLong(g.get("grantId"));
            Long storeId = KgJdbc.asLong(g.get("storeId"));
            String storeName = KgJdbc.asStr(g.get("storeName"));
            db.update("update kg_merchant_request set status = 'closed', return_reason = '商家已退出合作', version = version + 1, handle_time = now(), update_by = :by, update_time = now() "
                    + "where store_id = :s and status = 'returned' and del_flag = '0'", p("s", storeId, "by", by));
            db.update("update kg_job set status = 'closed', close_time = now(), update_by = :by, update_time = now() where store_id = :s and status = 'draft' and create_by = :mine and del_flag = '0'",
                    p("s", storeId, "by", by, "mine", "staff:" + me));
            db.update("update kg_staff_grant set status = '1', revoked_by = :by, revoked_time = now(), update_by = :by, update_time = now() where grant_id = :g and status = '0'",
                    p("g", grantId, "by", by));
            // 自动开通的店：申请行记着这条授权。申请改成「暂不合作」并留一句备注，后台看得到；页面上也不再说「已入驻」
            int selfOpened = db.update("update kg_merchant_apply set status = 'rejected', follow_note = concat(coalesce(follow_note, ''), :note), update_by = :by, update_time = now() "
                    + "where grant_id = :g and status = 'settled' and del_flag = '0'", p("g", grantId, "note", "（用户自己退出了合作商家）", "by", by));
            if (selfOpened > 0 && db.count("select count(*) from kg_staff_grant where store_id = :s and status = '0' and del_flag = '0' and coalesce(granted_by, '') <> :auto",
                    p("s", storeId, "auto", KgMerchantRequestService.GRANTED_BY)) == 0)
            {
                db.update("update kg_store set status = '1', update_by = :by, update_time = now() where store_id = :s and status = '0'", p("s", storeId, "by", by));
                for (Map<String, Object> other : db.list("select grant_id, app_user_id from kg_staff_grant where store_id = :s and status = '0' and del_flag = '0'", p("s", storeId)))
                {
                    db.update("update kg_staff_grant set status = '1', revoked_by = :by, revoked_time = now(), update_by = :by, update_time = now() where grant_id = :g and status = '0'",
                            p("g", other.get("grantId"), "by", by));
                    Long uid = KgJdbc.asLong(other.get("appUserId"));
                    if (grants != null)
                    {
                        grants.notifyGrantChange(uid, storeId, "门店授权已结束", "「" + storeName + "」已退出合作，这家店的派单授权已结束。", KgJdbc.asLong(other.get("grantId")));
                    }
                    otherUsers.add(uid);
                }
            }
            if (grants != null)
            {
                grants.notifyGrantChange(me, storeId, "已退出合作商家", "「" + storeName + "」的合作商家身份已结束。想重新合作可以再提交申请，袋鼠全球青年会和你联系。", grantId);
            }
        }
        List<StaffGrantView> views = grants == null ? Collections.<StaffGrantView>emptyList() : grants.loadActiveGrantViews(me);
        if (tokens != null)
        {
            tokens.refreshGrants(me, views);
            for (Long uid : otherUsers)
            {
                tokens.refreshGrants(uid, grants == null ? Collections.<StaffGrantView>emptyList() : grants.loadActiveGrantViews(uid));
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("exited", true);
        m.put("staffGrants", views);
        m.put("message", "已退出合作商家");
        return m;
    }

    /** 这家店还有什么没处理完（和注销账号同一套「进行中」口径） */
    private List<String> exitBlockers(Long storeId, String storeName)
    {
        List<String> out = new ArrayList<String>();
        Map<String, Object> s = p("s", storeId);
        long jobs = db.count("select count(*) from kg_job where store_id = :s and status in ('published', 'paused') and del_flag = '0'", s);
        if (jobs > 0)
        {
            out.add("「" + storeName + "」还有 " + jobs + " 个上架或暂停中的岗位，请联系客服关闭");
        }
        long apps = db.count("select count(*) from kg_application a join kg_job j on j.job_id = a.job_id where a.store_id = :s and a.del_flag = '0' and a.status in ('"
                + String.join("','", KgAccountService.APP_BUSY) + "') and not (j.job_type = 'online' and exists (select 1 from kg_online_settlement x where x.app_id = a.app_id and x.status = 'confirmed'))", s);
        if (apps > 0)
        {
            out.add("「" + storeName + "」还有 " + apps + " 条进行中的报名");
        }
        long pay = db.count("select count(*) from kg_online_settlement x join kg_application a on a.app_id = x.app_id where a.store_id = :s and x.status in ('pending_payout','payout_registered','disputed')", s);
        if (pay > 0)
        {
            out.add("「" + storeName + "」还有 " + pay + " 笔线上任务结算没完成");
        }
        long requests = db.count("select count(*) from kg_merchant_request where store_id = :s and del_flag = '0' and status in ('unassigned','dispatched','pending_verify','forwarded','accepted')", s);
        if (requests > 0)
        {
            out.add("「" + storeName + "」还有 " + requests + " 条需求在处理中，请联系客服关闭");
        }
        // 已同意但渠道尚未退回的钱仍是未完成订单，不能在退款处理中撤销商家授权并停店。
        long stays = db.count("select count(*) from kg_stay_order where store_id = :s and del_flag = '0' and status in ('pending_payment','confirmed','checked_in','refund_pending','refunding')", s);
        long shops = db.count("select count(*) from kg_shop_order where store_id = :s and del_flag = '0' and status in ('pending_payment','unused','refund_pending','refunding')", s);
        if (stays + shops > 0)
        {
            out.add("「" + storeName + "」还有 " + (stays + shops) + " 个订单没完成");
        }
        long acts = db.count("select count(*) from kg_activity_signup g join kg_activity a on a.activity_id = g.activity_id where a.store_id = :s "
                + "and (g.status = 'pending_payment' or g.status = 'refund_pending' or (g.status in ('confirmed','waitlist') and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) > now()))", s);
        if (acts > 0)
        {
            out.add("「" + storeName + "」还有 " + acts + " 个活动报名没结束");
        }
        return out;
    }

    /** 我最近一份申请的进度（没有时 latest 为 null）；页面据此显示「已提交」还是申请表 */
    public Map<String, Object> latest(Long me)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Map<String, Object> r = me == null ? null : db.one("select apply_id, contact_name, city, store_type, store_name, want_types, status, store_id, create_time "
                + "from kg_merchant_apply where app_user_id = :u and del_flag = '0' order by apply_id desc limit 1", p("u", me));
        // KgJdbc 查出来的列名已经转成驼峰（store_name → storeName）
        if (r == null)
        {
            m.put("latest", null);
        }
        else
        {
            String status = KgJdbc.asStr(r.get("status"));
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("applyId", r.get("applyId"));
            x.put("storeName", r.get("storeName"));
            x.put("storeId", r.get("storeId"));
            x.put("city", r.get("city"));
            x.put("storeTypeLabel", STORE_TYPES.get(KgJdbc.asStr(r.get("storeType"))));
            List<String> wants = new ArrayList<String>();
            for (String w : String.valueOf(r.get("wantTypes") == null ? "" : r.get("wantTypes")).split("、"))
            {
                if (WANT_TYPES.containsValue(w))
                {
                    wants.add(w);
                }
            }
            x.put("wantLabels", wants);
            // 「已入驻」但账号现在没有生效的合作商家身份（以前后台直接改状态没开通、或授权后来被撤销）：不说「欢迎加入」，也让他能再交
            boolean settledWithout = "settled".equals(status) && activeMerchant(me) == null;
            x.put("status", status);
            x.put("statusText", settledWithout ? "申请已标记入驻，但商家身份还没开通或已结束，请联系袋鼠全球青年客服，或重新提交申请"
                    : STATUS_TEXT.containsKey(status) ? STATUS_TEXT.get(status) : "已提交");
            // 处理完了（已入驻 / 暂不合作）可以再交一份，暂不合作的再交不自动开通（落「待联系」）。open 老版本页面在用（true 时藏表单）；新页面看 canResubmit：老申请也能再交，交了就转正
            x.put("open", OPEN.contains(status));
            x.put("canResubmit", !OPEN.contains(status) && (!"settled".equals(status) || settledWithout));
            x.put("merchantActive", "settled".equals(status) && !settledWithout);
            x.put("createTime", r.get("createTime"));
            m.put("latest", x);
        }
        m.put("storeTypes", options(STORE_TYPES));
        m.put("wantTypes", options(WANT_TYPES));
        return m;
    }

    private static List<Map<String, String>> options(Map<String, String> src)
    {
        List<Map<String, String>> out = new ArrayList<Map<String, String>>();
        for (Map.Entry<String, String> e : src.entrySet())
        {
            Map<String, String> o = new LinkedHashMap<String, String>();
            o.put("value", e.getKey());
            o.put("label", e.getValue());
            out.add(o);
        }
        return out;
    }
}
