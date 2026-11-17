package com.ruoyi.kangaroo.ext.manage;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.manage.KgStats.num;
import static com.ruoyi.kangaroo.ext.manage.KgStats.pct;
import static com.ruoyi.kangaroo.ext.manage.KgStats.str;
import static com.ruoyi.kangaroo.ext.manage.KgStats.yuan2;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 主理人钱包：按后台配置的分佣规则，对门店已完成的住宿订单、已核销的服务订单、已结束的付费活动计佣（入账后到结算日才可结算），
 * 退款后自动冲回。结算 = 用户提交申请 → 后台审核 → 财务线下打款并登记凭证；平台不做任何自动转账，也不收手续费。
 */
@Service
public class KgWalletService
{
    private static final Logger log = LoggerFactory.getLogger(KgWalletService.class);

    static final Map<String, String> SOURCE = new LinkedHashMap<String, String>();
    static
    {
        SOURCE.put("stay", "营业收入");
        SOURCE.put("shop", "二消收入");
        SOURCE.put("activity", "活动收入");
        SOURCE.put("adjust", "人工调整");
    }

    static final Map<String, String> RULE_TITLE = new LinkedHashMap<String, String>();
    static
    {
        RULE_TITLE.put("stay", "营业额提成");
        RULE_TITLE.put("shop", "二消收入提成");
        RULE_TITLE.put("activity", "活动报名提成");
    }

    /** 单笔结算下限 / 上限（分）。设计约定（m6 3.7）¥10–¥50,000；首页接口一并下发，页面的提示与校验以这里为准，不再各写一份 */
    static final long WITHDRAW_MIN_FEN = 1000L;
    static final long WITHDRAW_MAX_FEN = 5000000L;

    @Autowired
    private KgJdbc db;

    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.user.KgSmsService sms;

    @Autowired
    private StaffSupport support;

    /**
     * 钱包结果通知（结算申请提交 / 通过 / 驳回 / 打款、收益冲回）。钱包在员工端「主理人专区」，有员工授权的账号登录后默认停在员工身份，
     * 之前只发用户身份的消息，员工身份的消息页和红点都看不到；现在有员工授权时同时发一条员工身份消息（同申报处理的做法）。
     */
    private void notifyPayee(Long uid, String title, String content, String bizType, Long bizId, String route)
    {
        // 切换后城市负责人是老主理人，钱包跟人走，他也要在员工身份收到（KgMessageMapper 放行了 wallet、wallet_withdraw）
        String quiet = com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() ? "('online_staff','merchant_service')" : "('online_staff','merchant_service','city_lead')";
        if (db.count("select count(*) from kg_staff_grant where app_user_id = :u and status = '0' and del_flag = '0' and role_code not in " + quiet, p("u", uid)) > 0)
        {
            support.notifyStaff(uid, "system", title, content, bizType, bizId, route);
        }
        support.notifyUser(uid, "system", title, content, bizType, bizId, route);
    }

    /** 结算日：次月 5 日，遇周末顺延到周一 */
    static LocalDate settleDate(LocalDate done)
    {
        LocalDate d = done.withDayOfMonth(1).plusMonths(1).withDayOfMonth(5);
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY)
        {
            return d.plusDays(2);
        }
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY)
        {
            return d.plusDays(1);
        }
        return d;
    }

    /**
     * 保存分佣规则时校验阶梯并归一化（全角冒号分号换成半角）：格式 上限元:基点;上限元:基点;…，
     * 上限严格递增，只有最后一档可以是 0（= 以上），每档基点 0–3000（与固定费率同一上限）。
     * 写错一条规则不能再拖垮全平台的分佣入账（2026-09-28 复核发现：parseLong 抛异常会回滚整批入账、后台重新计算返回 500）。
     */
    public static String normalizeTiers(String raw)
    {
        String t = raw == null ? "" : raw.trim().replace('：', ':').replace('；', ';').replace(" ", "");
        if (t.isEmpty())
        {
            return "";
        }
        if (!t.matches("\\d{1,9}:\\d{1,4}(;\\d{1,9}:\\d{1,4})*"))
        {
            throw new ServiceException("阶梯格式不对：应为「上限元:基点;上限元:基点;0:基点」，例如 10000:100;30000:200;0:300", 422);
        }
        String[] segs = t.split(";");
        long prev = 0;
        for (int i = 0; i < segs.length; i++)
        {
            String[] x = segs[i].split(":");
            long upper = Long.parseLong(x[0]);
            long bp = Long.parseLong(x[1]);
            if (bp > 3000)
            {
                throw new ServiceException("阶梯第 " + (i + 1) + " 档基点不能超过 3000（30%）", 422);
            }
            boolean last = i == segs.length - 1;
            if (last ? upper != 0 : (upper == 0 || upper <= prev))
            {
                throw new ServiceException(last ? "最后一档的上限必须写 0（表示以上）" : "阶梯上限必须逐档变大，且只有最后一档可以写 0", 422);
            }
            prev = upper;
        }
        return t;
    }

    /** 阶梯："10000:100;30000:200;0:300" = 当月累计 ≤1 万元 1%、≤3 万元 2%、以上 3%；没有阶梯用固定费率。历史坏数据不抛异常，退回固定费率 */
    static int rateFor(Map<String, Object> rule, long monthBaseFen)
    {
        String tiers = str(rule.get("tiers")).trim();
        int fixed = (int) Math.min(3000, num(rule.get("rateBp")));
        if (tiers.isEmpty())
        {
            return fixed;
        }
        int last = fixed;
        try
        {
            for (String t : normalizeTiers(tiers).split(";"))
            {
                String[] x = t.split(":");
                long upper = Long.parseLong(x[0]);
                last = Integer.parseInt(x[1]);
                if (upper == 0 || monthBaseFen <= upper * 100)
                {
                    return last;
                }
            }
        }
        catch (RuntimeException e)
        {
            return fixed;
        }
        return last;
    }

    @Scheduled(fixedDelay = 600000, initialDelay = 90000)
    public void accrueJob()
    {
        try
        {
            accrue();
        }
        catch (Exception e)
        {
            log.warn("分佣入账失败，下次重试：{}", e.getMessage());
        }
    }

    /**
     * 计佣的排除条件：下单人就是收款人、或下单人是这家店的在职员工，不计佣（自己给自己的店下单刷佣金）。
     * 入账按「订单 + 来源」去重而不是按收款人去重：改了规则的收款人、或同一门店同一来源建了第二条规则，
     * 历史订单不会再给另一个收款人重复计一遍（2026-09-28 复核发现）。
     */
    private static final String NOT_SELF_O = "and o.app_user_id <> :u and not exists (select 1 from kg_staff_grant sg where sg.app_user_id = o.app_user_id "
            + "and sg.store_id = o.store_id and sg.status = '0' and sg.del_flag = '0')";
    private static final String NOT_SELF_G = "and g.app_user_id <> :u and not exists (select 1 from kg_staff_grant sg where sg.app_user_id = g.app_user_id "
            + "and sg.store_id = a.store_id and sg.status = '0' and sg.del_flag = '0')";

    /** 入账 + 冲回；幂等（按订单 + 来源去重，唯一键 app_user_id + biz_no + entry_type 兜底），可以反复跑 */
    @Transactional(rollbackFor = Exception.class)
    public int accrue()
    {
        int n = 0;
        for (Map<String, Object> rule : db.list("select * from kg_commission_rule where status = '0' and del_flag = '0'", p()))
        {
            String src = str(rule.get("sourceType"));
            Long uid = KgJdbc.asLong(rule.get("payeeUserId"));
            Map<String, Object> ps = p("s", rule.get("storeId"), "f", str(rule.get("effectiveFrom")).substring(0, 10), "u", uid);
            List<Map<String, Object>> done;
            if ("stay".equals(src))
            {
                done = db.list("select o.order_no biz_no, s.stay_name title, o.pay_fen - o.refund_fen base, o.check_out done_day from kg_stay_order o join kg_stay s on s.stay_id = o.stay_id "
                        + "where o.store_id = :s and o.del_flag = '0' and o.status = 'completed' and o.check_out >= :f and not exists (select 1 from kg_wallet_entry e "
                        + "where e.biz_no = o.order_no and e.source_type = 'stay' and e.entry_type = 'credit' and e.rule_id is not null) " + NOT_SELF_O + " order by o.order_id limit 500", ps);
            }
            else if ("shop".equals(src))
            {
                done = db.list("select o.order_no biz_no, coalesce(v.service_name, '服务') title, o.pay_fen - o.refund_fen base, date(o.verify_time) done_day "
                        + "from kg_shop_order o left join kg_shop_service v on v.service_id = o.service_id where o.store_id = :s and o.del_flag = '0' and o.status = 'used' "
                        + "and o.verify_time >= :f and not exists (select 1 from kg_wallet_entry e where e.biz_no = o.order_no and e.source_type = 'shop' "
                        + "and e.entry_type = 'credit' and e.rule_id is not null) " + NOT_SELF_O + " order by o.order_id limit 500", ps);
            }
            else if ("activity".equals(src))
            {
                done = db.list("select g.signup_no biz_no, a.title, g.fee_fen - g.refund_fen base, date(coalesce(a.end_time, a.start_time)) done_day from kg_activity_signup g "
                        + "join kg_activity a on a.activity_id = g.activity_id where a.store_id = :s and g.status = 'confirmed' and g.fee_fen > 0 "
                        + "and coalesce(a.end_time, date_add(a.start_time, interval 4 hour)) <= now() and a.start_time >= :f and not exists (select 1 from kg_wallet_entry e "
                        + "where e.biz_no = g.signup_no and e.source_type = 'activity' and e.entry_type = 'credit' and e.rule_id is not null) " + NOT_SELF_G + " order by g.signup_id limit 500", ps);
            }
            else
            {
                continue;
            }
            for (Map<String, Object> o : done)
            {
                long base = num(o.get("base"));
                if (base <= 0)
                {
                    continue;
                }
                LocalDate doneDay = LocalDate.parse(str(o.get("doneDay")).substring(0, 10));
                long monthBase = db.count("select coalesce(sum(base_fen), 0) from kg_wallet_entry where rule_id = :r and entry_type = 'credit' and create_time >= :m",
                        p("r", rule.get("ruleId"), "m", LocalDate.now().withDayOfMonth(1).toString()));
                int rate = rateFor(rule, monthBase + base);
                long amount = base * rate / 10000;
                if (amount <= 0)
                {
                    continue;
                }
                n += db.update("insert ignore into kg_wallet_entry (app_user_id, store_id, rule_id, entry_type, source_type, biz_no, biz_title, base_fen, rate_bp, amount_fen, "
                        + "available_date, remark, create_by, create_time) values (:u, :s, :r, 'credit', :src, :no, :title, :base, :rate, :amt, :ad, :rm, 'system', now())",
                        p("u", uid, "s", rule.get("storeId"), "r", rule.get("ruleId"), "src", src, "no", o.get("bizNo"), "title", StaffSupport.cut(str(o.get("title")), 64),
                                "base", base, "rate", rate, "amt", amount, "ad", settleDate(LocalDate.now().isAfter(doneDay) ? LocalDate.now() : doneDay).toString(),
                                "rm", "按规则 #" + rule.get("ruleId") + " 计佣"));
            }
        }
        // 冲回：入账后订单又发生退款（计佣基数变小）。
        // 同一单可能先后退多次（后台对支付单的补偿退款）：每次都把「应冲回总额」与「已冲回总额」对账，差多少补一条。
        // 第一条冲回沿用订单号，之后依次是 订单号#R2、#R3…，唯一键 (app_user_id, biz_no, entry_type) 仍然挡住并发重复入账。
        // 早先只允许一条冲回，第二次退款对应的收益永远不冲（2026-09-26 回归用例 M1 证实）。
        for (Map<String, Object> e : db.list("select e.*, coalesce(so.pay_fen - so.refund_fen, sh.pay_fen - sh.refund_fen, g.fee_fen - g.refund_fen) cur_base, "
                + "(select coalesce(sum(r.amount_fen), 0) from kg_wallet_entry r where r.app_user_id = e.app_user_id and r.entry_type = 'reverse' "
                + " and (r.biz_no = e.biz_no or r.biz_no like concat(e.biz_no, '#R%'))) rev_amt, "
                + "(select coalesce(sum(r.base_fen), 0) from kg_wallet_entry r where r.app_user_id = e.app_user_id and r.entry_type = 'reverse' "
                + " and (r.biz_no = e.biz_no or r.biz_no like concat(e.biz_no, '#R%'))) rev_base, "
                + "(select count(*) from kg_wallet_entry r where r.app_user_id = e.app_user_id and r.entry_type = 'reverse' "
                + " and (r.biz_no = e.biz_no or r.biz_no like concat(e.biz_no, '#R%'))) rev_cnt "
                + "from kg_wallet_entry e left join kg_stay_order so on e.source_type = 'stay' and so.order_no = e.biz_no "
                + "left join kg_shop_order sh on e.source_type = 'shop' and sh.order_no = e.biz_no "
                + "left join kg_activity_signup g on e.source_type = 'activity' and g.signup_no = e.biz_no "
                + "where e.entry_type = 'credit' having cur_base is not null and cur_base < e.base_fen limit 2000", p()))
        {
            long cur = Math.max(0, num(e.get("curBase")));
            long base = num(e.get("baseFen"));
            long keep = cur * num(e.get("rateBp")) / 10000;
            // 应冲回总额 = 入账额 - 按当前基数应得；已冲回的 rev_amt 是负数
            long back = num(e.get("amountFen")) - keep + num(e.get("revAmt"));
            if (back <= 0)
            {
                continue;
            }
            long cnt = num(e.get("revCnt"));
            String no = cnt == 0 ? str(e.get("bizNo")) : e.get("bizNo") + "#R" + (cnt + 1);
            int added = db.update("insert ignore into kg_wallet_entry (app_user_id, store_id, rule_id, entry_type, source_type, biz_no, biz_title, base_fen, rate_bp, amount_fen, "
                    + "available_date, remark, create_by, create_time) values (:u, :s, :r, 'reverse', :src, :no, :title, :base, :rate, :amt, :ad, '订单退款，冲回对应收益', 'system', now())",
                    p("u", e.get("appUserId"), "s", e.get("storeId"), "r", e.get("ruleId"), "src", e.get("sourceType"), "no", no, "title", e.get("bizTitle"),
                            "base", Math.max(0, base - cur - num(e.get("revBase"))), "rate", e.get("rateBp"), "amt", -back, "ad", str(e.get("availableDate")).substring(0, 10)));
            n += added;
            if (added == 0)
            {
                // 定时任务与后台「重新计算」同时跑时，另一边已经冲回过：唯一键挡住了重复入账，通知也不能再发一次
                continue;
            }
            notifyPayee(KgJdbc.asLong(e.get("appUserId")), "收益冲回", "订单 " + e.get("bizNo") + " 发生退款，冲回收益 ¥" + yuan2(back), "wallet", null,
                    "/pages/manage/wallet?view=entries");
        }
        return n;
    }

    // ---------------------------------------------------------------- 余额

    Map<String, Long> balance(Long uid)
    {
        Map<String, Object> r = db.one("select coalesce(sum(case when available_date <= curdate() then amount_fen else 0 end), 0) avail, "
                + "coalesce(sum(case when available_date > curdate() then amount_fen else 0 end), 0) pend from kg_wallet_entry where app_user_id = :u", p("u", uid));
        long used = db.count("select coalesce(sum(amount_fen), 0) from kg_wallet_withdraw where app_user_id = :u and status in ('pending','approved','paid')", p("u", uid));
        Map<String, Long> m = new LinkedHashMap<String, Long>();
        long real = (r == null ? 0 : num(r.get("avail"))) - used;
        m.put("available", Math.max(0, real));
        m.put("pending", r == null ? 0 : num(r.get("pend")));
        // 按账本的真实余额，不截到 0：申请结算后订单又退款冲回会是负数（后台登记打款前要看它）
        m.put("real", real);
        return m;
    }

    /** 某人当前真实可结算余额（可能为负）：后台结算申请列表标「余额不足」、登记打款前核对 */
    public long realBalance(Long uid)
    {
        return balance(uid).get("real");
    }

    static String shortfallText(long real)
    {
        return "余额不足：当前 " + yuan2(real);
    }

    /**
     * 后台结算申请列表 / 详情：「已通过待打款」且申请人真实余额为负的，状态旁标「余额不足：当前 -97.00」（extNote），
     * 「登记打款」先弹确认框写清差额，确认后带 confirmShortfall=true（extConfirm，ExtCrud 认这个键）。
     */
    public void markShortfall(List<Map<String, Object>> rows)
    {
        Map<Long, Long> seen = new java.util.HashMap<Long, Long>();
        for (Map<String, Object> r : rows)
        {
            Long uid = KgJdbc.asLong(r.get("appUserId"));
            if (uid == null || !"approved".equals(str(r.get("status"))))
            {
                continue;
            }
            Long real = seen.get(uid);
            if (real == null)
            {
                real = realBalance(uid);
                seen.put(uid, real);
            }
            if (real < 0)
            {
                r.put("extNote", shortfallText(real));
                Map<String, Object> paid = new LinkedHashMap<String, Object>();
                paid.put("text", "申请人当前余额 " + yuan2(real) + "（结算申请通过后有订单退款冲回），这笔 ¥" + yuan2(num(r.get("amountFen")))
                        + " 打款后差额 ¥" + yuan2(-real) + " 需要另外追回。确认仍要登记打款？还没打款也可以先「退回」这笔申请。");
                paid.put("param", "confirmShortfall");
                Map<String, Object> confirm = new LinkedHashMap<String, Object>();
                confirm.put("paid", paid);
                r.put("extConfirm", confirm);
            }
        }
    }

    private String payeeText(Long uid)
    {
        Map<String, Object> pe = db.one("select payee_name, bank_name, account_masked from kg_wallet_payee where app_user_id = :u", p("u", uid));
        return pe == null ? null : pe.get("bankName") + " " + pe.get("accountMasked") + "（" + pe.get("payeeName") + "）";
    }

    public Map<String, Object> overview()
    {
        AppLoginUser me = AppUserContext.require();
        Long uid = me.getUserId();
        Map<String, Long> bal = balance(uid);
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        long month = db.count("select coalesce(sum(amount_fen), 0) from kg_wallet_entry where app_user_id = :u and entry_type in ('credit','reverse') and create_time >= :f",
                p("u", uid, "f", first.toString()));
        long lastMonth = db.count("select coalesce(sum(amount_fen), 0) from kg_wallet_entry where app_user_id = :u and entry_type in ('credit','reverse') and create_time >= :f "
                + "and create_time < :t", p("u", uid, "f", first.minusMonths(1).toString(), "t", first.toString()));
        Map<String, Object> next = db.one("select min(available_date) d from kg_wallet_entry where app_user_id = :u and available_date > curdate()", p("u", uid));
        Map<String, Object> user = db.one("select nickname, avatar, register_time from kg_app_user where user_id = :u", p("u", uid));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("nickname", user == null ? "" : user.get("nickname"));
        m.put("avatar", user == null ? null : user.get("avatar"));
        m.put("days", user == null || user.get("registerTime") == null ? null
                : ChronoUnit.DAYS.between(LocalDate.parse(str(user.get("registerTime")).substring(0, 10)), LocalDate.now()) + 1);
        m.put("isManager", db.count("select count(*) from kg_staff_grant where app_user_id = :u and role_code in " + (com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() ? "('manager','city_lead')" : "('manager')") + " and status = '0' and del_flag = '0'", p("u", uid)) > 0);
        m.put("availableFen", bal.get("available"));
        // 余额为负（结算后订单退款冲回）：如实给出负数和说明，页面不再只显示 ¥0
        long real = bal.get("real");
        m.put("realAvailableFen", real);
        m.put("balanceNote", real < 0 ? "有订单退款冲回，暂不可结算；之后的收益会先抵扣这部分" : null);
        m.put("pendingFen", bal.get("pending"));
        m.put("withdrawMinFen", WITHDRAW_MIN_FEN);
        m.put("withdrawMaxFen", WITHDRAW_MAX_FEN);
        // 结算是否需要短信验证码，以及验证码会发到哪个号（脱敏）——页面据此决定那张卡片显不显示
        Map<String, Object> phoneRow = db.one("select phone from kg_app_user where user_id = :u", p("u", uid));
        String phone = phoneRow == null ? "" : str(phoneRow.get("phone"));
        boolean needCode = sms != null && sms.enabled() && !phone.isEmpty();
        m.put("smsEnabled", Boolean.valueOf(needCode));
        m.put("smsPhone", needCode && phone.length() >= 7 ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : "");
        m.put("monthFen", month);
        m.put("monthRange", first.getMonthValue() + "月1日-" + LocalDate.now().getMonthValue() + "月" + LocalDate.now().getDayOfMonth() + "日");
        m.put("monthDelta", KgStats.delta(month, lastMonth));
        m.put("monthUp", month >= lastMonth);
        m.put("nextSettle", next == null || next.get("d") == null ? settleDate(LocalDate.now()).toString() : str(next.get("d")).substring(0, 10));
        m.put("payee", payeeText(uid));
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        long total = 0;
        List<Map<String, Object>> rows = db.list("select source_type, sum(amount_fen) amt from kg_wallet_entry where app_user_id = :u and entry_type in ('credit','reverse') "
                + "and create_time >= :f group by source_type order by amt desc", p("u", uid, "f", first.toString()));
        for (Map<String, Object> r : rows)
        {
            total += Math.max(0, num(r.get("amt")));
        }
        for (Map<String, Object> r : rows)
        {
            if (sources.size() >= 3)
            {
                break;
            }
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("type", r.get("sourceType"));
            s.put("label", SOURCE.getOrDefault(str(r.get("sourceType")), "其他"));
            s.put("amount", "¥ " + yuan2(num(r.get("amt"))));
            s.put("pct", total <= 0 ? "—" : Math.round(Math.max(0, num(r.get("amt"))) * 100.0 / total) + "%");
            sources.add(s);
        }
        m.put("sources", sources);
        Map<String, Object> last = db.one("select e.*, s.store_name from kg_wallet_entry e left join kg_store s on s.store_id = e.store_id where e.app_user_id = :u "
                + "and e.entry_type = 'credit' order by e.entry_id desc limit 1", p("u", uid));
        if (last != null)
        {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("name", SOURCE.getOrDefault(str(last.get("sourceType")), "收益") + " - " + last.get("bizTitle"));
            r.put("place", last.get("storeName"));
            r.put("amount", "¥ " + yuan2(num(last.get("amountFen"))));
            r.put("status", StaffSupport.fmt(StaffSupport.parse(last.get("createTime")), "MM-dd") + "　"
                    + (LocalDate.parse(str(last.get("availableDate")).substring(0, 10)).isAfter(LocalDate.now()) ? "待结算" : "可结算"));
            r.put("type", last.get("sourceType"));
            m.put("recent", r);
        }
        m.put("hasRule", db.count("select count(*) from kg_commission_rule where payee_user_id = :u and status = '0' and del_flag = '0'", p("u", uid)) > 0);
        return m;
    }

    public Map<String, Object> entries(String type, String from, String to, String keyword)
    {
        AppLoginUser me = AppUserContext.require();
        StringBuilder w = new StringBuilder(" where e.app_user_id = :u");
        Map<String, Object> ps = p("u", me.getUserId());
        if (type != null && SOURCE.containsKey(type))
        {
            w.append(" and e.source_type = :ty");
            ps.put("ty", type);
        }
        LocalDate f = from == null || from.isEmpty() ? LocalDate.now().withDayOfMonth(1) : LocalDate.parse(from);
        LocalDate t = to == null || to.isEmpty() ? LocalDate.now() : LocalDate.parse(to);
        if (ChronoUnit.DAYS.between(f, t) > 366 || t.isBefore(f))
        {
            throw new ServiceException("日期范围不超过一年", 422);
        }
        w.append(" and e.create_time >= :f and e.create_time < :t");
        ps.put("f", f.toString());
        ps.put("t", t.plusDays(1).toString());
        if (keyword != null && !keyword.trim().isEmpty())
        {
            w.append(" and e.biz_no like :k");
            ps.put("k", "%" + keyword.trim() + "%");
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> e : db.list("select e.*, s.store_name from kg_wallet_entry e left join kg_store s on s.store_id = e.store_id" + w
                + " order by e.entry_id desc limit 200", ps))
        {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            String src = str(e.get("sourceType"));
            r.put("id", e.get("bizNo"));
            r.put("prefix", "activity".equals(src) ? "报名号" : ("adjust".equals(src) ? "调整单" : "订单号"));
            r.put("time", StaffSupport.fmt(StaffSupport.parse(e.get("createTime")), "MM-dd HH:mm"));
            r.put("place", str(e.get("storeName")) + (str(e.get("bizTitle")).isEmpty() ? "" : " · " + e.get("bizTitle")));
            r.put("type", "reverse".equals(str(e.get("entryType"))) ? "退款冲回" : SOURCE.getOrDefault(src, "其他"));
            r.put("tone", "reverse".equals(str(e.get("entryType"))) ? "k-red" : ("stay".equals(src) ? "k-indigo" : ("shop".equals(src) ? "k-green" : "k-ochre")));
            r.put("order", "¥ " + yuan2(num(e.get("baseFen"))));
            r.put("rate", pct(num(e.get("rateBp"))));
            r.put("received", (num(e.get("amountFen")) < 0 ? "-¥ " : "¥ ") + yuan2(Math.abs(num(e.get("amountFen")))));
            r.put("status", "reverse".equals(str(e.get("entryType"))) ? "已冲回"
                    : (LocalDate.parse(str(e.get("availableDate")).substring(0, 10)).isAfter(LocalDate.now()) ? "待结算" : "已入账"));
            rows.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("from", f.toString());
        m.put("to", t.toString());
        m.put("rows", rows);
        m.put("types", SOURCE);
        return m;
    }

    public Map<String, Object> rules()
    {
        AppLoginUser me = AppUserContext.require();
        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
        List<List<String>> tiers = new ArrayList<List<String>>();
        List<String> example = new ArrayList<String>();
        long exampleTotal = 0;
        for (Map<String, Object> r : db.list("select c.*, s.store_name from kg_commission_rule c left join kg_store s on s.store_id = c.store_id "
                + "where c.payee_user_id = :u and c.status = '0' and c.del_flag = '0' order by c.rule_id", p("u", me.getUserId())))
        {
            String src = str(r.get("sourceType"));
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("title", RULE_TITLE.getOrDefault(src, "提成"));
            x.put("desc", r.get("storeName") + " · " + ("stay".equals(src) ? "已完成的房源订单实付金额" : ("shop".equals(src) ? "已核销的二消服务订单实付金额" : "已结束活动的报名费"))
                    + "（" + str(r.get("effectiveFrom")).substring(0, 10) + " 起）");
            String tierStr = str(r.get("tiers")).trim();
            if (tierStr.isEmpty())
            {
                x.put("rate", pct(num(r.get("rateBp"))));
            }
            else
            {
                int lo = Integer.MAX_VALUE;
                int hi = 0;
                long lower = 0;
                String safeTiers;
                try
                {
                    safeTiers = normalizeTiers(tierStr);
                }
                catch (RuntimeException ex)
                {
                    safeTiers = "";   // 历史坏数据：展示固定费率，不让钱包规则页 500
                }
                if (safeTiers.isEmpty())
                {
                    x.put("rate", pct(num(r.get("rateBp"))));
                }
                for (String t : safeTiers.isEmpty() ? new String[0] : safeTiers.split(";"))
                {
                    String[] p2 = t.split(":");
                    long upper = Long.parseLong(p2[0]);
                    int bp = Integer.parseInt(p2[1]);
                    lo = Math.min(lo, bp);
                    hi = Math.max(hi, bp);
                    if (tiers.isEmpty() || tiers.size() < 6)
                    {
                        tiers.add(java.util.Arrays.asList(upper == 0 ? String.format("%,d 及以上", lower + 1) : String.format("%,d - %,d", lower == 0 ? 0 : lower + 1, upper),
                                pct(bp)));
                    }
                    lower = upper;
                }
                if (!safeTiers.isEmpty())
                {
                    x.put("rate", lo == hi ? pct(lo) : pct(lo) + "-" + pct(hi));
                }
            }
            x.put("icon", "stay".equals(src) ? "house" : ("shop".equals(src) ? "coffee" : "users-round"));
            x.put("color", "stay".equals(src) ? "indigo" : ("shop".equals(src) ? "green" : "ochre"));
            rules.add(x);
            long sample = "stay".equals(src) ? 1000000L : 100000L;
            long amt = sample * rateFor(r, sample) / 10000;
            exampleTotal += amt;
            example.add("• " + x.get("title") + "：¥ " + yuan2(sample) + " × " + pct(rateFor(r, sample)) + " ＝ ¥ " + yuan2(amt));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rules", rules);
        m.put("tiers", tiers);
        m.put("example", example);
        m.put("exampleIntro", rules.isEmpty() ? "" : "举例（按你当前生效的费率）：营业额 ¥ 10,000.00，其余每类 ¥ 1,000.00");
        m.put("exampleTotal", rules.isEmpty() ? "" : "合计预计收益：¥ " + yuan2(exampleTotal));
        Map<String, Object> contact = db.one("select config_value from sys_config where config_key = 'kangaroo.support.contact'", p());
        List<Map<String, Object>> tips = new ArrayList<Map<String, Object>>();
        tips.add(tip("结算周期", "每月 5 日起可结算上月收益，遇周末顺延", "receipt-text"));
        tips.add(tip("退款反冲", "退订 / 退款会自动冲回对应收益", "refresh-cw"));
        tips.add(tip("如有疑问", contact == null || str(contact.get("configValue")).isEmpty() ? "在「荣誉与关怀 › 申报」提交问题，3 个工作日内反馈"
                : "联系 " + contact.get("configValue"), "headphones"));
        m.put("tips", tips);
        return m;
    }

    private static Map<String, Object> tip(String title, String desc, String icon)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("title", title);
        m.put("desc", desc);
        m.put("icon", icon);
        return m;
    }

    /** 提交结算申请（按用户加锁，防止并发超额） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> withdraw(Map<String, Object> b)
    {
        AppLoginUser me = AppUserContext.require();
        Long uid = me.getUserId();
        Map<String, Object> payee = db.one("select * from kg_wallet_payee where app_user_id = :u for update", p("u", uid));
        if (payee == null)
        {
            throw new ServiceException("收款账户还没有登记，请联系平台财务按结算协议登记", 422);
        }
        String idem = str(b.get("idemKey"));
        if (idem.isEmpty() || idem.length() > 64)
        {
            throw new ServiceException("缺少幂等键", 422);
        }
        Map<String, Object> old = db.one("select withdraw_no, status from kg_wallet_withdraw where app_user_id = :u and idem_key = :k", p("u", uid, "k", idem));
        if (old != null)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("withdrawNo", old.get("withdrawNo"));
            m.put("message", "结算申请已提交");
            return m;
        }
        // 结算是资金动作：短信通道开着、且本人绑过手机号时，必须先过一次验证码。
        // 通道没开（kangaroo.sms.mode=none）就维持原流程，不假装验过。
        if (sms != null && sms.enabled())
        {
            Map<String, Object> u = db.one("select phone from kg_app_user where user_id = :u and del_flag = '0'", p("u", uid));
            String phone = u == null ? "" : str(u.get("phone"));
            if (!phone.isEmpty())
            {
                sms.verify(phone, "withdraw", str(b.get("code")));
            }
        }
        long amount = num(b.get("amountFen"));
        long avail = balance(uid).get("available");
        // 设计约定（m6 3.7）：单笔 ¥10–¥50,000；同一账号同时只能有 1 笔在途（上面已锁了收款账户行，并发申请在这里串行）
        if (amount < WITHDRAW_MIN_FEN || amount > WITHDRAW_MAX_FEN)
        {
            throw new ServiceException("单次结算金额需在 ¥" + yuan2(WITHDRAW_MIN_FEN) + " – ¥" + yuan2(WITHDRAW_MAX_FEN) + " 之间", 422);
        }
        if (db.count("select count(*) from kg_wallet_withdraw where app_user_id = :u and status in ('pending','approved') for update", p("u", uid)) > 0)
        {
            throw new ServiceException("你还有一笔结算申请在处理中，请等它完成后再申请", 409);
        }
        if (amount > avail)
        {
            throw new ServiceException("超过可结算余额 ¥" + yuan2(avail), 422);
        }
        String no = "W" + StaffSupport.fmt(new Date(), "yyyyMMddHHmmss") + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("withdraw_no", no);
        row.put("app_user_id", uid);
        row.put("amount_fen", amount);
        row.put("payee_snapshot", StaffSupport.cut(payeeText(uid), 128));
        row.put("status", "pending");
        row.put("idem_key", idem);
        row.put("create_time", new Date());
        db.insert("kg_wallet_withdraw", row);
        notifyPayee(uid, "结算申请已提交", "金额 ¥" + yuan2(amount) + "，平台审核通过后 3 个工作日内线下打款", "wallet_withdraw", null,
                "/pages/manage/wallet");
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("withdrawNo", no);
        m.put("message", "结算申请已提交，审核通过后 3 个工作日内线下打款");
        return m;
    }

    public List<Map<String, Object>> withdraws()
    {
        AppLoginUser me = AppUserContext.require();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> w : db.list("select * from kg_wallet_withdraw where app_user_id = :u order by withdraw_id desc limit 50", p("u", me.getUserId())))
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("withdrawNo", w.get("withdrawNo"));
            m.put("amount", "¥ " + yuan2(num(w.get("amountFen"))));
            m.put("status", w.get("status"));
            m.put("statusText", "pending".equals(str(w.get("status"))) ? "待审核" : ("approved".equals(str(w.get("status"))) ? "待打款"
                    : ("paid".equals(str(w.get("status"))) ? "已打款" : (str(w.get("auditNote")).startsWith(REVOKE_PREFIX) ? "已退回" : "已驳回"))));
            m.put("note", w.get("auditNote"));
            m.put("voucher", w.get("paidVoucher"));
            m.put("time", StaffSupport.fmt(StaffSupport.parse(w.get("createTime")), "yyyy-MM-dd HH:mm"));
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- 后台

    @org.springframework.beans.factory.annotation.Value("${kangaroo.wallet.four-eyes:true}")
    private boolean fourEyes;

    /** 「已通过」后退回的申请仍记 rejected（不加状态、不加迁移），审核说明以这几个字开头，用来和待审核时的驳回区分 */
    static final String REVOKE_PREFIX = "通过后退回：";

    @Transactional(rollbackFor = Exception.class)
    public String audit(Long id, String action, String note, String operator)
    {
        return audit(id, action, note, operator, false);
    }

    /**
     * confirmShortfall：登记打款时申请人真实余额为负（结算后订单退款冲回），默认 409 拒绝并说明差额；后台在确认框里确认后带 true 才放行。
     * revoke：已通过、还没打款的申请退回（2026-11-09 主人拍板），钱回到余额并通知本人。
     */
    @Transactional(rollbackFor = Exception.class)
    public String audit(Long id, String action, String note, String operator, boolean confirmShortfall)
    {
        Map<String, Object> w = db.must("select * from kg_wallet_withdraw where withdraw_id = :id for update", p("id", id), "申请不存在");
        String st = str(w.get("status"));
        Long uid = KgJdbc.asLong(w.get("appUserId"));
        String amt = "¥" + yuan2(num(w.get("amountFen")));
        if ("approve".equals(action))
        {
            if (!"pending".equals(st))
            {
                throw new ServiceException("只有待审核的申请可以通过");
            }
            db.update("update kg_wallet_withdraw set status = 'approved', audit_note = :n, audited_by = :by, audited_time = now(), update_by = :by, update_time = now() "
                    + "where withdraw_id = :id", p("n", note, "by", operator, "id", id));
            notifyPayee(uid, "结算申请已通过", amt + " 将在 3 个工作日内线下打款到登记的收款账户", "wallet_withdraw", id, "/pages/manage/wallet");
            return "已通过，待线下打款";
        }
        if ("reject".equals(action))
        {
            if (!"pending".equals(st))
            {
                // 已通过的申请财务可能已经线下付过款，驳回会把金额退回余额让人再提一次（2026-09-28 复核发现）；确认没打款的走「退回」
                throw new ServiceException("只有待审核的申请可以驳回；已通过的申请确认还没线下打款的，请用「退回」");
            }
            if (note == null || note.trim().isEmpty())
            {
                throw new ServiceException("请填写驳回原因");
            }
            db.update("update kg_wallet_withdraw set status = 'rejected', audit_note = :n, audited_by = :by, audited_time = now(), update_by = :by, update_time = now() "
                    + "where withdraw_id = :id", p("n", note.trim(), "by", operator, "id", id));
            notifyPayee(uid, "结算申请未通过", amt + "：" + note.trim() + "（金额已退回可结算余额）", "wallet_withdraw", id, "/pages/manage/wallet");
            return "已驳回，金额退回可结算余额";
        }
        if ("paid".equals(action))
        {
            if (!"approved".equals(st))
            {
                throw new ServiceException("只有审核通过的申请可以登记打款");
            }
            if (note == null || note.trim().isEmpty())
            {
                throw new ServiceException("请填写打款凭证（银行流水号等）");
            }
            // 四眼原则：登记打款的人不能就是审批的人（kangaroo.wallet.four-eyes=false 可关，仅限本地联调）。
            // 用 409 而不是 403：后台把 403 统一显示成「当前操作没有权限」，管理员看不到真正的原因
            if (fourEyes && operator != null && operator.equals(str(w.get("auditedBy"))))
            {
                throw new ServiceException("审批人不能登记打款：这笔申请是你审批通过的，请由另一位有结算审核权限的后台账号登记打款", 409);
            }
            // 余额不足（通过后订单退款冲回）：默认拒绝并写清差额，后台确认后才放行；确认过的在审核说明里留一笔
            long real = realBalance(uid);
            String auditNote = str(w.get("auditNote"));
            if (real < 0)
            {
                if (!confirmShortfall)
                {
                    throw new ServiceException(shortfallText(real) + "，打款后差额 ¥" + yuan2(-real) + " 需要另外追回。仍要打款请在确认框里确认；还没打款也可以「退回」这笔申请", 409);
                }
                auditNote = StaffSupport.cut((auditNote.isEmpty() ? "" : auditNote + "；") + "登记打款时余额 ¥" + yuan2(real) + "，已确认仍打款", 200);
            }
            db.update("update kg_wallet_withdraw set status = 'paid', paid_voucher = :v, paid_by = :by, paid_time = now(), audit_note = :n, update_by = :by, update_time = now() "
                    + "where withdraw_id = :id", p("v", StaffSupport.cut(note.trim(), 255), "by", operator, "n", auditNote.isEmpty() ? w.get("auditNote") : auditNote, "id", id));
            notifyPayee(uid, "结算已打款", amt + " 已线下打款，凭证：" + StaffSupport.cut(note.trim(), 60), "wallet_withdraw", id, "/pages/manage/wallet");
            return "已登记打款";
        }
        if ("revoke".equals(action))
        {
            if (!"approved".equals(st))
            {
                throw new ServiceException("只有已通过、还没登记打款的申请可以退回", 409);
            }
            if (note == null || note.trim().isEmpty())
            {
                throw new ServiceException("请填写退回原因");
            }
            String by = str(w.get("auditedBy"));
            db.update("update kg_wallet_withdraw set status = 'rejected', audit_note = :n, audited_by = :by, audited_time = now(), update_by = :by, update_time = now() "
                    + "where withdraw_id = :id", p("n", StaffSupport.cut(REVOKE_PREFIX + note.trim() + (by.isEmpty() ? "" : "（原审批 " + by + "）"), 200), "by", operator, "id", id));
            notifyPayee(uid, "结算申请已退回", amt + "：" + note.trim() + "（还没有打款，金额已退回可结算余额）", "wallet_withdraw", id, "/pages/manage/wallet");
            return "已退回，金额回到申请人的可结算余额";
        }
        throw new ServiceException("未知操作");
    }
}
