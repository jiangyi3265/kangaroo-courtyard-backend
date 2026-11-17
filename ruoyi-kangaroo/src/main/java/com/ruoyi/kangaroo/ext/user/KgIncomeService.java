package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 我的收入（线上任务的收支明细 / 到账记录）。
 *
 * 只读 kg_online_settlement（门店验收通过后生成的线下结算单）：报酬由门店按约定方式线下付给接单人，
 * 平台不经手资金、不做任何转账，这里也不改任何状态 —— 确认收款 / 申诉仍在任务交付页里做。
 * 合计口径：已到账 = confirmed；待到账 = pending_payout（待门店打款）+ payout_registered（门店已登记打款、待本人确认）；申诉中 = disputed。
 */
@Service
public class KgIncomeService
{
    public static final String NOTE = "报酬由门店按约定方式线下支付，平台不经手资金；收到后请在任务里点「确认收款」";

    @Autowired
    private KgJdbc db;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    static long num(Object v)
    {
        Long l = KgJdbc.asLong(v);
        return l == null ? 0L : l;
    }

    static String yuan(long fen)
    {
        return "¥" + (fen % 100 == 0 ? String.valueOf(fen / 100) : String.format("%.2f", fen / 100.0));
    }

    /** 筛选：all 全部 / pending 待到账 / confirmed 已到账 / disputed 申诉中；不认识的按全部 */
    static String filterSql(String status)
    {
        if ("pending".equals(status))
        {
            return " and s.status in ('pending_payout', 'payout_registered')";
        }
        if ("confirmed".equals(status))
        {
            return " and s.status = 'confirmed'";
        }
        if ("disputed".equals(status))
        {
            return " and s.status = 'disputed'";
        }
        return "";
    }

    /** 本人看到的状态名（短）：结算单状态 → 待打款 / 待确认到账 / 已到账 / 申诉中 */
    static String label(String status)
    {
        if ("pending_payout".equals(status))
        {
            return "待打款";
        }
        if ("payout_registered".equals(status))
        {
            return "待确认到账";
        }
        if ("confirmed".equals(status))
        {
            return "已到账";
        }
        return "disputed".equals(status) ? "申诉中" : "处理中";
    }

    /** 状态说明（一句话说清楚接下来要做什么） */
    static String hint(String status)
    {
        if ("pending_payout".equals(status))
        {
            return "验收已通过，等门店按约定方式打款";
        }
        if ("payout_registered".equals(status))
        {
            return "门店已登记打款，核对到账后请在任务里点「确认收款」";
        }
        if ("confirmed".equals(status))
        {
            return "你已确认收到这笔报酬";
        }
        return "disputed".equals(status) ? "你已申诉没收到款，平台会介入核实" : "";
    }

    static String tone(String status)
    {
        if ("confirmed".equals(status))
        {
            return "green";
        }
        if ("disputed".equals(status))
        {
            return "red";
        }
        return "payout_registered".equals(status) ? "blue" : "ochre";
    }

    /** 'yyyy-MM-dd HH:mm:ss' → 今年的显示 MM-dd，往年的显示 yyyy-MM-dd */
    static String day(Object v)
    {
        String s = str(v);
        if (s.length() < 10)
        {
            return "";
        }
        return s.substring(0, 4).equals(String.valueOf(LocalDate.now().getYear())) ? s.substring(5, 10) : s.substring(0, 10);
    }

    /** 这一单的关键日期：验收（结算单生成）、门店登记打款、本人确认到账；申诉中的再标上申诉日期 */
    static String dates(Map<String, Object> r)
    {
        List<String> parts = new ArrayList<String>();
        if (!day(r.get("createTime")).isEmpty())
        {
            parts.add("验收 " + day(r.get("createTime")));
        }
        if (!day(r.get("registeredTime")).isEmpty())
        {
            parts.add("登记打款 " + day(r.get("registeredTime")));
        }
        if (!day(r.get("confirmedTime")).isEmpty())
        {
            parts.add("到账 " + day(r.get("confirmedTime")));
        }
        if ("disputed".equals(str(r.get("status"))) && !day(r.get("updateTime")).isEmpty())
        {
            parts.add("申诉 " + day(r.get("updateTime")));
        }
        return String.join(" · ", parts);
    }

    /**
     * 合计 + 明细（最新的在前，分页）。
     *
     * @param status   all / pending / confirmed / disputed
     * @param pageNum  从 1 开始
     * @param pageSize 1–50，默认 20
     */
    public Map<String, Object> income(Long me, String status, Integer pageNum, Integer pageSize)
    {
        long confirmed = 0, pending = 0, disputed = 0;
        long confirmedCount = 0, pendingCount = 0, disputedCount = 0;
        for (Map<String, Object> r : db.list("select status, count(*) n, coalesce(sum(amount_fen), 0) fen from kg_online_settlement where app_user_id = :u group by status",
                p("u", me)))
        {
            String st = str(r.get("status"));
            if ("confirmed".equals(st))
            {
                confirmed += num(r.get("fen"));
                confirmedCount += num(r.get("n"));
            }
            else if ("pending_payout".equals(st) || "payout_registered".equals(st))
            {
                pending += num(r.get("fen"));
                pendingCount += num(r.get("n"));
            }
            else if ("disputed".equals(st))
            {
                disputed += num(r.get("fen"));
                disputedCount += num(r.get("n"));
            }
        }
        Map<String, Object> totals = new LinkedHashMap<String, Object>();
        totals.put("confirmedFen", confirmed);
        totals.put("confirmed", yuan(confirmed));
        totals.put("confirmedCount", confirmedCount);
        totals.put("pendingFen", pending);
        totals.put("pending", yuan(pending));
        totals.put("pendingCount", pendingCount);
        totals.put("disputedFen", disputed);
        totals.put("disputed", yuan(disputed));
        totals.put("disputedCount", disputedCount);

        String filter = filterSql(status);
        Map<String, Object> page = db.page("select s.settle_id, s.settle_no, s.app_id, s.job_id, s.amount_fen, s.status, s.payout_method, s.create_time, s.registered_time, "
                + "s.confirmed_time, s.update_time, j.title, st.store_name from kg_online_settlement s left join kg_job j on j.job_id = s.job_id "
                + "left join kg_store st on st.store_id = s.store_id where s.app_user_id = :u" + filter + " order by s.create_time desc, s.settle_id desc",
                p("u", me), pageNum == null ? 1 : pageNum, pageSize == null ? 20 : pageSize);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Object raw = page.get("rows");
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) o;
                String st = str(r.get("status"));
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("appId", r.get("appId"));
                m.put("jobId", r.get("jobId"));
                m.put("settleNo", r.get("settleNo"));
                m.put("title", str(r.get("title")).isEmpty() ? "（任务已删除）" : r.get("title"));
                m.put("storeName", str(r.get("storeName")).isEmpty() ? "门店" : r.get("storeName"));
                m.put("amountFen", num(r.get("amountFen")));
                m.put("amount", yuan(num(r.get("amountFen"))));
                m.put("status", st);
                m.put("statusLabel", label(st));
                m.put("hint", hint(st));
                m.put("tone", tone(st));
                m.put("method", r.get("payoutMethod"));
                m.put("dates", dates(r));
                rows.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("totals", totals);
        out.put("rows", rows);
        out.put("total", page.get("total"));
        out.put("hasMore", page.get("hasMore"));
        out.put("pageNum", page.get("pageNum"));
        out.put("pageSize", page.get("pageSize"));
        out.put("status", filter.isEmpty() ? "all" : status);
        out.put("note", NOTE);
        return out;
    }
}
