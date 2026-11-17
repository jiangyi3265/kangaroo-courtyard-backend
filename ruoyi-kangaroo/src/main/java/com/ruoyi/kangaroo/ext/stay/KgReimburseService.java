package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.JobImages;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.util.AccountState;

/**
 * 采购报销（2026-10-31 主人拍板，V66）：自营院子里的东西采购报销，没提交进表的不报销。系统只记账不打款。
 *
 * 谁能提交：这家店的小院主理人（keeper，只看 kg_staff_grant.role_code：legacy 和 new 下含义相同，切换不动这一行，不用看 RolePhase），
 * 且门店是直营（kg_store.op_type = 'direct'，列 not null 默认 direct，商家入驻建的店写 franchise，kg_stay 上没有运营类型）。
 * 看表格、驳回后再交、撤回都只给 keeper：同店别的 keeper 能看，只有提交人本人能改、能撤。门店改成非直营后老记录还能看、待审的还能撤，不能再交。
 *
 * 状态：pending 待审核 → approved 已通过 → paid 已打款；pending → rejected 已驳回 → （本人修改再交）pending。撤回 = 软删（del_flag = 2），只有 pending 能撤。
 * 已通过、已打款的内容不能再改，也没有撤销，点错了要改库，主人定了之后再加。
 *
 * 防并发：改状态都是先 for update 锁这一行再看状态，update 带 where status = 原状态，两个人同时审只有一个成功，别的 409。
 * 提交先锁账号行（AccountState.requireActive，必须是事务第一条），每小时条数、重复提交在锁里查：同一个人并发也超不了、连点两下只进一笔。
 * 每小时条数按 submit_time 数（撤回的也算，不能撤了重发来绕），不用 Redis 限流：只数真正交成功的，填错不占名额，Redis 挂了也不失效。
 *
 * 钱按 BigDecimal 算，对外一律是两位小数的字符串（"128.50"）：JSON 数字到前端是浮点，合计会有误差。
 *
 * 不能审自己的：后台账号和小程序账号是两套（AppAuth 的注释），唯一能对应的是手机号，sys_user.phonenumber 和提交人当前手机号相同就算本人，空的不算。
 * 没写手机号的后台账号对应不上，这是已知缺口。两张表不 join，比较放在 Java 里，不依赖两张表的排序规则一样。
 *
 * 站内信只发提交人本人（员工身份，biz_type = reimburse，KgMessageMapper 放行）。微信订阅消息只给用户身份，这里没有，要打开小程序才看得到。
 * 带 kangaroo:message:list 的后台账号（没有报销菜单也行）读得到消息正文（品名、金额、驳回理由），和小院评论同一个已知情况，给运营授权时要知道。
 * 注销：见 KgAccountService（待审、待打款挡住注销；财务记录保留，提交人转匿名）。
 */
@Service
public class KgReimburseService
{
    /** 站内信 biz_type */
    public static final String TYPE = "reimburse";

    static final String PENDING = "pending";
    static final String APPROVED = "approved";
    static final String REJECTED = "rejected";
    static final String PAID = "paid";
    private static final List<String> STATUSES = Collections.unmodifiableList(Arrays.asList(PENDING, APPROVED, REJECTED, PAID));
    private static final Map<String, String> STATUS_TEXT = new LinkedHashMap<String, String>();
    static
    {
        STATUS_TEXT.put(PENDING, "待审核");
        STATUS_TEXT.put(APPROVED, "已通过");
        STATUS_TEXT.put(REJECTED, "已驳回");
        STATUS_TEXT.put(PAID, "已打款");
    }

    static final int ITEM_MAX = 60;
    static final int QUANTITY_MAX = 999999;
    static final BigDecimal AMOUNT_MAX = new BigDecimal("100000");
    static final int RECEIPTS_MAX = 9;
    /** kg_reimbursement.receipts 列宽 */
    static final int RECEIPTS_COLUMN_MAX = 2000;
    static final int REMARK_MAX = 200;
    static final int REASON_MAX = 255;
    /** 每人每小时最多几条 */
    static final int HOUR_MAX = 20;
    static final int PAGE_SIZE = 20;
    /** 导出上限：多一行就报错，财务表格不能悄悄截断 */
    static final int EXPORT_MAX = 10000;
    /** 比这还早的日期只可能是填错了（也挡住 MySQL date 不支持的年份） */
    private static final LocalDate DATE_MIN = LocalDate.of(2000, 1, 1);
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    /** 后台表格的顺序：待审排前，已通过（等打款）其次 */
    private static final String ADMIN_ORDER = " order by field(status, 'pending', 'approved', 'rejected', 'paid'), reimburse_id desc";

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    private static String str(Object o)
    {
        return o == null ? "" : String.valueOf(o);
    }

    /** 通知点开回到报销页；带门店是因为工作台有「当前门店」，一个人可能管几家 */
    static String route(Object id, Object storeId)
    {
        return "/pages/staff/reimburse?id=" + id + "&storeId=" + storeId;
    }

    static String statusText(Object status)
    {
        String t = STATUS_TEXT.get(str(status));
        return t == null ? "" : t;
    }

    /** 两位小数的字符串，读库和合计都走这里 */
    static String money(Object v)
    {
        return new BigDecimal(str(v).isEmpty() ? "0" : str(v)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static int length(String s)
    {
        return s.codePointCount(0, s.length());
    }

    // ---------------------------------------------------------------- 谁能用

    /**
     * 这家店的小院主理人身份。storeId 为空时取第一家有 keeper 授权的门店（不能用 StaffSupport.ctx(null)：第一条授权可能是别的角色）。
     * 授权每个请求都从库里重读（AppIdentityLoader），停用的门店、撤销的授权这里已经没有了。
     */
    private StaffSupport.Ctx keeper(Long storeId)
    {
        Long target = storeId == null ? firstKeeperStore() : storeId;
        if (target == null)
        {
            throw new ServiceException("只有小院主理人可以使用采购报销", HttpStatus.FORBIDDEN);
        }
        StaffSupport.Ctx c = support.ctxForStore(target);
        if (!usable(c.role))
        {
            throw new ServiceException("只有这家店的小院主理人可以使用采购报销（当前角色：" + StaffRole.label(c.role) + "）", HttpStatus.FORBIDDEN);
        }
        return c;
    }

    /** 切换后城市负责人能替小院主理人提交；审批只在平台后台（approve），小程序没有审批接口，自己交的自己批不了 */
    private static boolean usable(String role)
    {
        return StaffRole.KEEPER.equals(role) || (StaffRole.CITY_LEAD.equals(role) && com.ruoyi.kangaroo.ext.staff.RolePhase.isNew());
    }

    private static List<StaffGrantView> keeperGrants()
    {
        AppLoginUser me = AppUserContext.requireStaff();
        List<StaffGrantView> out = new ArrayList<StaffGrantView>();
        for (StaffGrantView g : me.getStaffGrants())
        {
            if (g.getStoreId() != null && usable(g.getRoleCode()))
            {
                out.add(g);
            }
        }
        return out;
    }

    private static Long firstKeeperStore()
    {
        List<StaffGrantView> grants = keeperGrants();
        return grants.isEmpty() ? null : grants.get(0).getStoreId();
    }

    /** 新交一笔要落在哪家店：只有一家时可以不传，管几家时必须说清楚，钱不能记错店 */
    private StaffSupport.Ctx keeperForSubmit(Long storeId)
    {
        if (storeId == null && keeperGrants().size() > 1)
        {
            throw new ServiceException("你管着几家小院，请先选门店", 422);
        }
        return keeper(storeId);
    }

    /** 直营的真实存法：kg_store.op_type = 'direct'（列 not null 默认 direct；可选值 direct / franchise / managed / partner）。停用、删除的门店不算 */
    boolean isDirect(Long storeId)
    {
        Map<String, Object> s = db.one("select op_type from kg_store where store_id = :s and status = '0' and del_flag = '0'", p("s", storeId));
        return s != null && "direct".equals(str(s.get("opType")));
    }

    private void requireDirect(Long storeId)
    {
        if (!isDirect(storeId))
        {
            throw new ServiceException("只有直营小院可以提交采购报销", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 工作台决定要不要放入口用：不报错。visible = 是这家店的 keeper，且门店直营或者有老记录（门店改成非直营后还要能看）；
     * canSubmit = 现在能不能新交。storeId 不传看第一家 keeper 门店；不是 keeper 或者没有这家店的授权一律两个 false。
     */
    public Map<String, Object> access(Long storeId)
    {
        StaffGrantView hit = null;
        for (StaffGrantView g : keeperGrants())
        {
            if (storeId == null || storeId.equals(g.getStoreId()))
            {
                hit = g;
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        boolean direct = hit != null && isDirect(hit.getStoreId());
        out.put("storeId", hit == null ? storeId : hit.getStoreId());
        out.put("storeName", hit == null ? "" : str(hit.getStoreName()));
        out.put("canSubmit", direct);
        out.put("visible", direct || (hit != null && db.count("select count(*) from kg_reimbursement where store_id = :s and del_flag = '0'", p("s", hit.getStoreId())) > 0));
        return out;
    }

    // ---------------------------------------------------------------- 表单校验

    /** 校验过的表单 */
    static final class Form
    {
        String item;
        int quantity;
        BigDecimal amount;
        LocalDate purchaseDate;
        List<String> receipts;
        String remark;
    }

    /** 只收字符串和数字：对象、数组、布尔转成字符串会混过后面的校验或者进库 */
    private static String plain(Object raw, String wrong)
    {
        if (raw == null)
        {
            return "";
        }
        if (raw instanceof String || raw instanceof Number)
        {
            return String.valueOf(raw).trim();
        }
        throw new ServiceException(wrong, 422);
    }

    static String itemName(Object raw)
    {
        String s = plain(raw, "请填写品名（1–" + ITEM_MAX + " 字）");
        if (!KgStayCommentService.hasVisibleText(s) || length(s) > ITEM_MAX)
        {
            throw new ServiceException("请填写品名（1–" + ITEM_MAX + " 字）", 422);
        }
        return s;
    }

    static int quantity(Object raw)
    {
        String s = plain(raw, "数量要是正整数");
        if (!s.matches("[0-9]{1,9}") || Integer.parseInt(s) < 1)
        {
            throw new ServiceException("数量要是正整数", 422);
        }
        int n = Integer.parseInt(s);
        if (n > QUANTITY_MAX)
        {
            throw new ServiceException("数量不能超过 " + QUANTITY_MAX, 422);
        }
        return n;
    }

    /** 大于 0，不超过 100000，最多两位小数；字符串和数字都收，科学计数法、全角数字不收 */
    static BigDecimal amount(Object raw)
    {
        String s = plain(raw, "金额要是数字，最多两位小数");
        if (s.length() > 20 || !s.matches("[0-9]+(\\.[0-9]+)?"))
        {
            throw new ServiceException("金额要是数字，最多两位小数", 422);
        }
        BigDecimal v = new BigDecimal(s);
        if (v.scale() > 2)
        {
            throw new ServiceException("金额最多两位小数", 422);
        }
        if (v.signum() <= 0)
        {
            throw new ServiceException("金额要大于 0", 422);
        }
        if (v.compareTo(AMOUNT_MAX) > 0)
        {
            throw new ServiceException("金额不能超过 100000 元", 422);
        }
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    static LocalDate purchaseDate(Object raw, LocalDate today)
    {
        String s = plain(raw, "请选择购买日期");
        if (s.isEmpty())
        {
            throw new ServiceException("请选择购买日期", 422);
        }
        if (!KgJdbc.isDate(s))
        {
            throw new ServiceException("购买日期不对，请重新选择", 422);
        }
        LocalDate d = LocalDate.parse(s);
        if (d.isAfter(today))
        {
            throw new ServiceException("购买日期不能晚于今天", 422);
        }
        if (d.isBefore(DATE_MIN))
        {
            throw new ServiceException("购买日期不对，请重新选择", 422);
        }
        return d;
    }

    /** 路径校验和岗位照片同一套（JobImages：只收本平台上传的图片路径，文件要在、要真是图片），张数和列宽是报销自己的 */
    static List<String> receipts(Object raw)
    {
        List<String> images = JobImages.parse(raw, RECEIPTS_MAX, RECEIPTS_COLUMN_MAX, "凭证照片");
        if (images == null || images.isEmpty())
        {
            throw new ServiceException("请至少上传 1 张凭证照片", 422);
        }
        JobImages.requireExisting(images, "凭证照片");
        return images;
    }

    static String remark(Object raw)
    {
        String s = plain(raw, "备注格式不对");
        if (length(s) > REMARK_MAX)
        {
            throw new ServiceException("备注最多 " + REMARK_MAX + " 字", 422);
        }
        return s;
    }

    /** 文件核对放最后：前面的都过了才碰磁盘 */
    static Form form(Map<String, Object> body, LocalDate today)
    {
        Map<String, Object> b = body == null ? Collections.<String, Object>emptyMap() : body;
        Form f = new Form();
        f.item = itemName(b.get("itemName"));
        f.quantity = quantity(b.get("quantity"));
        f.amount = amount(b.get("amount"));
        f.purchaseDate = purchaseDate(b.get("purchaseDate"), today);
        f.remark = remark(b.get("remark"));
        f.receipts = receipts(b.get("receipts"));
        return f;
    }

    private static LocalDate today()
    {
        return LocalDate.now(CHINA);
    }

    /** 每小时条数：在账号锁里数，撤回的也算（del_flag 不过滤） */
    private void requireHourly(Long me, Date now)
    {
        if (db.count("select count(*) from kg_reimbursement where app_user_id = :u and submit_time > :since", p("u", me, "since", new Date(now.getTime() - 3600000L))) >= HOUR_MAX)
        {
            throw new ServiceException("提交太频繁了，一小时最多 " + HOUR_MAX + " 条，请稍后再试", 429);
        }
    }

    /**
     * 连点两下、网络超时重试：内容一模一样（凭证是同一批上传的文件）又还在走流程的，不再进一笔。在账号锁里查，并发也只进一笔。
     * 重新选一次照片是新文件，不算重复：一张小票拆几行报销、同一天买两次同样的东西都不受影响。
     */
    private void requireNotDuplicate(Long me, Long storeId, Form f, Long self)
    {
        if (db.count("select count(*) from kg_reimbursement where app_user_id = :u and store_id = :s and item_name = :i and quantity = :q and amount = :a and purchase_date = :d "
                + "and receipts = :r and del_flag = '0' and status <> 'rejected' and reimburse_id <> :self",
                p("u", me, "s", storeId, "i", f.item, "q", f.quantity, "a", f.amount, "d", f.purchaseDate.toString(), "r", JobImages.toJson(f.receipts), "self", self == null ? 0L : self)) > 0)
        {
            throw new ServiceException("这一笔已经提交过了，不用再交", 409);
        }
    }

    private void logOp(StaffSupport.Ctx c, String action, String label, Long id, String item, Object amount)
    {
        support.op(c, action, label, "fund", "报销#" + id + " " + StaffSupport.cut(item, 60) + " ¥" + money(amount), true, null);
    }

    // ---------------------------------------------------------------- 小程序：提交、再交、撤回

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Long storeId, Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        AccountState.requireActive(db, me);
        StaffSupport.Ctx c = keeperForSubmit(storeId);
        requireDirect(c.storeId);
        Form f = form(body, today());
        requireNotDuplicate(me, c.storeId, f, null);
        Date now = new Date();
        requireHourly(me, now);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("store_id", c.storeId);
        row.put("store_name", StaffSupport.cut(str(c.storeName), 64));
        row.put("app_user_id", me);
        row.put("submitter_name", StaffSupport.cut(c.name(), 64));
        row.put("item_name", f.item);
        row.put("quantity", f.quantity);
        row.put("amount", f.amount);
        row.put("purchase_date", f.purchaseDate.toString());
        row.put("receipts", JobImages.toJson(f.receipts));
        row.put("remark", f.remark);
        row.put("status", PENDING);
        row.put("submit_time", now);
        row.put("create_by", "app:" + me);
        row.put("create_time", now);
        long id = db.insert("kg_reimbursement", row);
        logOp(c, "reimburse_submit", "提交采购报销", id, f.item, f.amount);
        return view(found(id), me, true);
    }

    /** 驳回后修改再交：只有提交人本人，还得是这家直营店的 keeper。第几次提交加 1，后台拿旧页面审不到新内容 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> resubmit(Long id, Map<String, Object> body)
    {
        Long me = AppUserContext.getUserId();
        AccountState.requireActive(db, me);
        Map<String, Object> r = locked(id);
        Long storeId = KgJdbc.asLong(r.get("storeId"));
        StaffSupport.Ctx c = keeper(storeId);
        requireMine(r, me, "只有提交人本人可以修改");
        requireDirect(storeId);
        if (!REJECTED.equals(str(r.get("status"))))
        {
            throw new ServiceException("只有被驳回的报销可以修改后再交", 409);
        }
        Form f = form(body, today());
        requireNotDuplicate(me, storeId, f, id);
        Date now = new Date();
        requireHourly(me, now);
        Map<String, Object> ps = p("id", id, "u", me, "i", f.item, "q", f.quantity, "a", f.amount, "d", f.purchaseDate.toString(), "r", JobImages.toJson(f.receipts), "m", f.remark, "now", now, "by", "app:" + me);
        if (db.update("update kg_reimbursement set item_name = :i, quantity = :q, amount = :a, purchase_date = :d, receipts = :r, remark = :m, status = 'pending', revision = revision + 1, "
                + "reject_reason = null, audit_by = null, audit_time = null, submit_time = :now, update_by = :by, update_time = :now "
                + "where reimburse_id = :id and app_user_id = :u and status = 'rejected' and del_flag = '0'", ps) == 0)
        {
            throw new ServiceException("报销状态已变化，请刷新后重试", 409);
        }
        logOp(c, "reimburse_resubmit", "修改后再交采购报销", id, f.item, f.amount);
        return view(found(id), me, true);
    }

    /** 撤回待审的：软删，只有提交人本人。门店改成非直营也能撤（人还是 keeper） */
    @Transactional(rollbackFor = Exception.class)
    public void withdraw(Long id)
    {
        Long me = AppUserContext.getUserId();
        AccountState.requireActive(db, me);
        Map<String, Object> r = locked(id);
        StaffSupport.Ctx c = keeper(KgJdbc.asLong(r.get("storeId")));
        requireMine(r, me, "只有提交人本人可以撤回");
        if (!PENDING.equals(str(r.get("status"))))
        {
            throw new ServiceException("只有待审核的报销可以撤回", 409);
        }
        if (db.update("update kg_reimbursement set del_flag = '2', update_by = :by, update_time = now() where reimburse_id = :id and status = 'pending' and del_flag = '0'",
                p("id", id, "by", "app:" + me)) == 0)
        {
            throw new ServiceException("报销状态已变化，请刷新后重试", 409);
        }
        logOp(c, "reimburse_withdraw", "撤回采购报销", id, str(r.get("itemName")), r.get("amount"));
    }

    private static void requireMine(Map<String, Object> r, Long me, String message)
    {
        if (!me.equals(KgJdbc.asLong(r.get("appUserId"))))
        {
            throw new ServiceException(message, HttpStatus.FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------- 小程序：看

    /** 这家店的报销表（同店别的 keeper 提交的也在里面）。{store, rows, total, hasMore, pageNum, counts} */
    public Map<String, Object> list(Long storeId, String status, int pageNum)
    {
        Long me = AppUserContext.getUserId();
        StaffSupport.Ctx c = keeper(storeId);
        boolean direct = isDirect(c.storeId);
        Map<String, Object> ps = p("s", c.storeId);
        StringBuilder where = new StringBuilder(" where store_id = :s and del_flag = '0'");
        String st = status == null ? "" : status.trim();
        if (!st.isEmpty())
        {
            where.append(" and status = :st");
            ps.put("st", requireStatus(st));
        }
        Map<String, Object> page = db.page("select * from kg_reimbursement" + where + " order by reimburse_id desc", ps, pageNum, PAGE_SIZE);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = (List<Map<String, Object>>) page.get("rows");
        for (Map<String, Object> r : raw)
        {
            rows.add(view(r, me, direct));
        }
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        for (String s : STATUSES)
        {
            counts.put(s, 0L);
        }
        for (Map<String, Object> r : db.list("select status, count(*) n from kg_reimbursement where store_id = :s and del_flag = '0' group by status", p("s", c.storeId)))
        {
            counts.put(str(r.get("status")), KgJdbc.asLong(r.get("n")));
        }
        Map<String, Object> store = new LinkedHashMap<String, Object>();
        store.put("storeId", c.storeId);
        store.put("storeName", c.storeName);
        store.put("canSubmit", direct);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("store", store);
        out.put("rows", rows);
        out.put("total", page.get("total"));
        out.put("hasMore", page.get("hasMore"));
        out.put("pageNum", page.get("pageNum"));
        out.put("counts", counts);
        return out;
    }

    public Map<String, Object> detail(Long id)
    {
        Long me = AppUserContext.getUserId();
        Map<String, Object> r = found(id);
        StaffSupport.Ctx c = keeper(KgJdbc.asLong(r.get("storeId")));
        return view(r, me, isDirect(c.storeId));
    }

    /** 给小院主理人看的一行：不带审核人、打款人的后台账号名 */
    static Map<String, Object> view(Map<String, Object> r, Long me, boolean direct)
    {
        Map<String, Object> m = common(r);
        boolean mine = me != null && me.equals(KgJdbc.asLong(r.get("appUserId")));
        String status = str(r.get("status"));
        m.put("isMine", mine);
        m.put("canEdit", mine && direct && REJECTED.equals(status));
        m.put("canWithdraw", mine && PENDING.equals(status));
        return m;
    }

    private static Map<String, Object> common(Map<String, Object> r)
    {
        String status = str(r.get("status"));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("reimburseId", KgJdbc.asLong(r.get("reimburseId")));
        m.put("storeId", KgJdbc.asLong(r.get("storeId")));
        m.put("storeName", str(r.get("storeName")));
        m.put("submitterName", str(r.get("submitterName")));
        m.put("itemName", str(r.get("itemName")));
        m.put("quantity", KgJdbc.asLong(r.get("quantity")));
        m.put("amount", money(r.get("amount")));
        m.put("purchaseDate", str(r.get("purchaseDate")));
        m.put("receipts", JobImages.read(str(r.get("receipts"))));
        m.put("remark", str(r.get("remark")));
        m.put("status", status);
        m.put("statusText", statusText(status));
        m.put("rejectReason", REJECTED.equals(status) ? str(r.get("rejectReason")) : "");
        m.put("revision", KgJdbc.asLong(r.get("revision")));
        m.put("submitTime", str(r.get("submitTime")));
        m.put("auditTime", str(r.get("auditTime")));
        m.put("paidTime", str(r.get("paidTime")));
        return m;
    }

    // ---------------------------------------------------------------- 行

    /** 没锁地读一行；不存在、已撤回都是 404 */
    private Map<String, Object> found(Long id)
    {
        Map<String, Object> r = db.one("select * from kg_reimbursement where reimburse_id = :id and del_flag = '0'", p("id", id));
        if (r == null)
        {
            throw new ServiceException("报销不存在或已撤回", 404);
        }
        return r;
    }

    private Map<String, Object> locked(Long id)
    {
        Map<String, Object> r = db.one("select * from kg_reimbursement where reimburse_id = :id and del_flag = '0' for update", p("id", id));
        if (r == null)
        {
            throw new ServiceException("报销不存在或已撤回", 404);
        }
        return r;
    }

    private static String requireStatus(String st)
    {
        if (!STATUSES.contains(st))
        {
            throw new ServiceException("状态只能是待审核、已通过、已驳回或已打款", 422);
        }
        return st;
    }

    // ---------------------------------------------------------------- 后台：列表、合计、详情、导出

    private static String like(String raw)
    {
        return "%" + raw.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String param(Map<String, String> q, String key)
    {
        String v = q == null ? null : q.get(key);
        return v == null ? "" : v.trim();
    }

    private static Long longParam(Map<String, String> q, String key, String label)
    {
        String v = param(q, key);
        if (v.isEmpty())
        {
            return null;
        }
        if (!v.matches("[0-9]{1,18}"))
        {
            throw new ServiceException(label + "要是数字", 422);
        }
        return Long.valueOf(v);
    }

    private static int intParam(Map<String, String> q, String key, int dft)
    {
        Long v = longParam(q, key, key);
        return v == null ? dft : (int) Math.min(v.longValue(), 100000L);
    }

    private static String dateParam(Map<String, String> q, String key)
    {
        String v = param(q, key);
        if (v.isEmpty())
        {
            return null;
        }
        if (!KgJdbc.isDate(v))
        {
            throw new ServiceException("日期格式应为 yyyy-MM-dd", 422);
        }
        return v;
    }

    /**
     * 筛选：storeId 门店、storeName 门店名（模糊）、status 状态、appUserId 提交人 ID、submitter 提交人昵称（模糊）、
     * purchaseBegin / purchaseEnd 购买日期、submitBegin / submitEnd 提交日期（都含当天）。撤回的不进表格。
     */
    static String adminWhere(Map<String, String> q, Map<String, Object> ps)
    {
        StringBuilder w = new StringBuilder(" where del_flag = '0'");
        Long storeId = longParam(q, "storeId", "门店");
        if (storeId != null)
        {
            w.append(" and store_id = :storeId");
            ps.put("storeId", storeId);
        }
        if (!param(q, "storeName").isEmpty())
        {
            w.append(" and store_name like :storeName escape '!'");
            ps.put("storeName", like(param(q, "storeName")));
        }
        if (!param(q, "status").isEmpty())
        {
            w.append(" and status = :status");
            ps.put("status", requireStatus(param(q, "status")));
        }
        Long appUserId = longParam(q, "appUserId", "提交人");
        if (appUserId != null)
        {
            w.append(" and app_user_id = :appUserId");
            ps.put("appUserId", appUserId);
        }
        if (!param(q, "submitter").isEmpty())
        {
            w.append(" and submitter_name like :submitter escape '!'");
            ps.put("submitter", like(param(q, "submitter")));
        }
        String purchaseBegin = dateParam(q, "purchaseBegin");
        if (purchaseBegin != null)
        {
            w.append(" and purchase_date >= :purchaseBegin");
            ps.put("purchaseBegin", purchaseBegin);
        }
        String purchaseEnd = dateParam(q, "purchaseEnd");
        if (purchaseEnd != null)
        {
            w.append(" and purchase_date <= :purchaseEnd");
            ps.put("purchaseEnd", purchaseEnd);
        }
        String submitBegin = dateParam(q, "submitBegin");
        if (submitBegin != null)
        {
            w.append(" and submit_time >= :submitBegin");
            ps.put("submitBegin", submitBegin + " 00:00:00");
        }
        String submitEnd = dateParam(q, "submitEnd");
        if (submitEnd != null)
        {
            w.append(" and submit_time <= :submitEnd");
            ps.put("submitEnd", submitEnd + " 23:59:59");
        }
        return w.toString();
    }

    /** {rows, total, hasMore, pageNum, pageSize, summary}。待审排前，已通过其次 */
    public Map<String, Object> adminList(Map<String, String> q)
    {
        Map<String, Object> ps = new HashMap<String, Object>();
        String where = adminWhere(q, ps);
        Map<String, Object> page = db.page("select * from kg_reimbursement" + where + ADMIN_ORDER, ps, intParam(q, "pageNum", 1), intParam(q, "pageSize", 10));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = (List<Map<String, Object>>) page.get("rows");
        for (Map<String, Object> r : raw)
        {
            rows.add(adminView(r));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>(page);
        out.put("rows", rows);
        out.put("summary", summary(where, ps));
        return out;
    }

    /**
     * 合计（同一组筛选条件，不分页）：sumAmount 是表格里全部行的金额，validAmount 去掉驳回（驳回的不用付钱，表头该显示这个），
     * byStatus 分状态的条数和金额（待审多少、待打款多少、已打款多少）。
     */
    Map<String, Object> summary(String where, Map<String, Object> ps)
    {
        Map<String, Object> byStatus = new LinkedHashMap<String, Object>();
        for (String s : STATUSES)
        {
            byStatus.put(s, statusSum(0L, BigDecimal.ZERO));
        }
        long count = 0;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal valid = BigDecimal.ZERO;
        for (Map<String, Object> r : db.list("select status, count(*) n, coalesce(sum(amount), 0) total from kg_reimbursement" + where + " group by status", ps))
        {
            String status = str(r.get("status"));
            long n = KgJdbc.asLong(r.get("n"));
            BigDecimal total = new BigDecimal(money(r.get("total")));
            byStatus.put(status, statusSum(n, total));
            count += n;
            sum = sum.add(total);
            if (!REJECTED.equals(status))
            {
                valid = valid.add(total);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("count", count);
        out.put("sumAmount", money(sum));
        out.put("validAmount", money(valid));
        out.put("byStatus", byStatus);
        return out;
    }

    private static Map<String, Object> statusSum(long count, BigDecimal amount)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("count", count);
        m.put("amount", money(amount));
        return m;
    }

    static Map<String, Object> adminView(Map<String, Object> r)
    {
        Map<String, Object> m = common(r);
        m.put("appUserId", KgJdbc.asLong(r.get("appUserId")));
        m.put("anonymous", "1".equals(str(r.get("anonymous"))));
        m.put("auditBy", str(r.get("auditBy")));
        m.put("paidBy", str(r.get("paidBy")));
        m.put("createTime", str(r.get("createTime")));
        return m;
    }

    public Map<String, Object> adminDetail(Long id)
    {
        return adminView(found(id));
    }

    /** 门店筛选的下拉：表格里出现过的门店，不用另外要门店管理的权限。同一家店改过名只出一行（取最新的名字） */
    public List<Map<String, Object>> adminStores()
    {
        return db.list("select r.store_id, r.store_name from kg_reimbursement r join (select store_id, max(reimburse_id) last_id from kg_reimbursement where del_flag = '0' group by store_id) x "
                + "on x.last_id = r.reimburse_id order by r.store_id", p());
    }

    /** 导出：和列表同一组筛选、同一个顺序，不分页；多取一行来发现超限，财务表格不能悄悄截断 */
    public void adminExport(Map<String, String> q, HttpServletResponse response) throws IOException
    {
        Map<String, Object> ps = new HashMap<String, Object>();
        String where = adminWhere(q, ps);
        List<Map<String, Object>> rows = db.list("select * from kg_reimbursement" + where + ADMIN_ORDER + " limit " + (EXPORT_MAX + 1), ps);
        if (rows.size() > EXPORT_MAX)
        {
            throw new ServiceException("导出超过 " + EXPORT_MAX + " 条，请缩小日期或状态范围后重试", 422);
        }
        KgReimburseExport.write(rows, response);
    }

    // ---------------------------------------------------------------- 后台：审核

    /** 不能审自己提交的：后台账号的手机号和提交人当前手机号相同就算本人（见类注释） */
    private void requireNotSelf(Map<String, Object> r, Long adminId)
    {
        Map<String, Object> admin = db.one("select phonenumber from sys_user where user_id = :a", p("a", adminId));
        String phone = admin == null ? "" : str(admin.get("phonenumber")).trim();
        if (phone.isEmpty())
        {
            return;
        }
        Map<String, Object> u = db.one("select phone from kg_app_user where user_id = :u", p("u", KgJdbc.asLong(r.get("appUserId"))));
        if (u != null && phone.equals(str(u.get("phone")).trim()))
        {
            throw new ServiceException("这一笔是你自己提交的，不能自己审，请换一个后台账号", HttpStatus.FORBIDDEN);
        }
    }

    /** 页面带上它看到的 revision：驳回后对方又交了，旧页面不能把新内容审过去。没带不查（接口文档要求页面带） */
    private static void requireRevision(Map<String, Object> r, Object revision)
    {
        String v = revision == null ? "" : String.valueOf(revision).trim();
        if (v.isEmpty())
        {
            return;
        }
        if (!v.matches("[0-9]{1,9}"))
        {
            throw new ServiceException("revision 要是数字", 422);
        }
        if (Long.parseLong(v) != KgJdbc.asLong(r.get("revision")).longValue())
        {
            throw new ServiceException("这一笔已经被修改过了，请刷新后再审", 409);
        }
    }

    private void move(Long id, String from, String to, String set, Map<String, Object> ps)
    {
        ps.put("id", id);
        ps.put("from", from);
        ps.put("to", to);
        if (db.update("update kg_reimbursement set status = :to, " + set + ", update_by = :op, update_time = now() where reimburse_id = :id and status = :from and del_flag = '0'", ps) == 0)
        {
            throw new ServiceException("报销状态已变化，请刷新后重试", 409);
        }
    }

    private static String line(Map<String, Object> r)
    {
        return "「" + str(r.get("itemName")) + "」¥" + money(r.get("amount"));
    }

    private void tell(Map<String, Object> r, String title, String content)
    {
        support.notifyStaff(KgJdbc.asLong(r.get("appUserId")), MessageCategory.SYSTEM, title, content, TYPE, KgJdbc.asLong(r.get("reimburseId")), route(r.get("reimburseId"), r.get("storeId")));
    }

    /** 通过：等主人打款 */
    @Transactional(rollbackFor = Exception.class)
    public String approve(Long id, Object revision, Long adminId, String operator)
    {
        Map<String, Object> r = locked(id);
        requireNotSelf(r, adminId);
        if (!PENDING.equals(str(r.get("status"))))
        {
            throw new ServiceException("只有待审核的报销可以通过", 409);
        }
        requireRevision(r, revision);
        move(id, PENDING, APPROVED, "reject_reason = null, audit_by = :op, audit_time = now()", p("op", operator));
        tell(r, "采购报销已通过", line(r) + " 已通过，等平台打款");
        return "已通过";
    }

    /** 驳回：理由必填，提交人看得到，可以修改后再交 */
    @Transactional(rollbackFor = Exception.class)
    public String reject(Long id, String reason, Object revision, Long adminId, String operator)
    {
        String why = reason == null ? "" : reason.trim();
        if (!KgStayCommentService.hasVisibleText(why))
        {
            throw new ServiceException("请填写驳回理由", 422);
        }
        if (length(why) > REASON_MAX)
        {
            throw new ServiceException("驳回理由最多 " + REASON_MAX + " 字", 422);
        }
        Map<String, Object> r = locked(id);
        requireNotSelf(r, adminId);
        if (!PENDING.equals(str(r.get("status"))))
        {
            throw new ServiceException("只有待审核的报销可以驳回", 409);
        }
        requireRevision(r, revision);
        move(id, PENDING, REJECTED, "reject_reason = :why, audit_by = :op, audit_time = now()", p("op", operator, "why", why));
        tell(r, "采购报销被驳回", line(r) + " 被驳回：" + why + "。可以修改后再交");
        return "已驳回";
    }

    /** 标记已打款：只有已通过的能标。系统不打款，这里只记账 */
    @Transactional(rollbackFor = Exception.class)
    public String markPaid(Long id, Long adminId, String operator)
    {
        Map<String, Object> r = locked(id);
        requireNotSelf(r, adminId);
        if (!APPROVED.equals(str(r.get("status"))))
        {
            throw new ServiceException("只有已通过的报销可以标记已打款", 409);
        }
        move(id, APPROVED, PAID, "paid_by = :op, paid_time = now()", p("op", operator));
        tell(r, "采购报销已打款", line(r) + " 平台已标记打款，请留意收款");
        return "已标记打款";
    }
}
