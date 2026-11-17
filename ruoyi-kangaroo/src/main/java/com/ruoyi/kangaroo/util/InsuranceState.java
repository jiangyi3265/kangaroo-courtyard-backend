package com.ruoyi.kangaroo.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Issuance and date-bound coverage are distinct; use the same labels in both clients. */
public final class InsuranceState
{
    private InsuranceState() { }
    public static void decorate(Map<String,Object> row)
    {
        decorate(row, LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }
    static void decorate(Map<String,Object> row, LocalDate today)
    {
        if (row == null) return;
        String status = KgJdbc.asStr(row.get("status"));
        String no = KgJdbc.asStr(row.get("policyNo"));
        boolean issued = "effective".equals(status) && no != null && !no.trim().isEmpty();
        String coverage = "pending";
        String label = "待登记保单"; // 主理人可在小程序登记保单号，平台也可在后台登记，不再只写"待平台处理"
        if ("pending_payment".equals(status)) { coverage="unpaid";label="待缴纳保费"; }
        else if ("refunding".equals(status)) { coverage="refund_pending";label="退保退款处理中"; }
        else if ("refunded".equals(status)) { coverage="terminated";label="已退保退款"; }
        else if ("closed".equals(status)) { coverage="closed";label="申请已关闭"; }
        else if ("rejected".equals(status)) { coverage = "rejected"; label = "已驳回"; }
        else if ("effective".equals(status)) {
            coverage = "invalid"; label = "保单信息待核实";
            try {
                LocalDate start = LocalDate.parse(row.get("startDate").toString().substring(0, 10));
                LocalDate end = LocalDate.parse(row.get("endDate").toString().substring(0, 10));
                if (issued && !end.isBefore(start)) {
                    if (end.isBefore(today)) { coverage = "expired"; label = "已失效"; }
                    else if (start.isAfter(today)) { coverage = "upcoming"; label = "已出单·待生效"; }
                    else { coverage = "active"; label = "生效中"; }
                }
            } catch (Exception ignored) { /* Invalid historical data must never claim coverage. */ }
        }
        row.put("policyIssued", issued);
        row.put("coverageStatus", coverage);
        row.put("statusLabel", label);
    }
}
