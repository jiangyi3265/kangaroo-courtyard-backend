package com.ruoyi.kangaroo.ext.stay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 取消政策：退改规则只有这一个来源。下单时把政策代码快照进订单，四处页面文案（确认订单、预订须知、退款页、加购说明）
 * 全部由这里生成，页面不写死任何规则。
 *
 * 退款按"距入住时刻（入住日 + 入住时间）"的小时数计算，扣款只扣房费部分，加购与服务费按同一政策全额退或不退。
 */
public final class StayPolicy
{
    public static final String FLEXIBLE = "flexible";
    public static final String MODERATE = "moderate";
    public static final String STRICT = "strict";
    public static final String NONREFUNDABLE = "nonrefundable";

    private StayPolicy()
    {
    }

    public static String normalize(String code)
    {
        if (FLEXIBLE.equals(code) || STRICT.equals(code) || NONREFUNDABLE.equals(code))
        {
            return code;
        }
        return MODERATE;
    }

    public static String title(String code)
    {
        String c = normalize(code);
        if (FLEXIBLE.equals(c))
        {
            return "入住前1天可免费取消";
        }
        if (STRICT.equals(c))
        {
            return "入住前7天可免费取消，之后扣50%房费";
        }
        if (NONREFUNDABLE.equals(c))
        {
            return "该订单不可取消、不可退款";
        }
        return "入住前7天可免费取消，之后扣首晚房费";
    }

    /** 表格：[{label, value}] */
    public static List<Map<String, Object>> rows(String code)
    {
        String c = normalize(code);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (NONREFUNDABLE.equals(c))
        {
            rows.add(row("任何时间", "不可退款"));
            return rows;
        }
        if (FLEXIBLE.equals(c))
        {
            rows.add(row("入住前 24 小时以上", "全额退款"));
            rows.add(row("入住前 24 小时内", "扣除首晚房费"));
        }
        else
        {
            rows.add(row("入住前 7 天以上", "全额退款"));
            rows.add(row("入住前 7 天内", STRICT.equals(c) ? "扣除 50% 房费" : "扣除首晚房费"));
        }
        rows.add(row("过了入住时间", "不可退款"));
        return rows;
    }

    private static Map<String, Object> row(String label, String value)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("value", value);
        return m;
    }

    public static String summary(String code)
    {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : rows(code))
        {
            sb.append(sb.length() == 0 ? "" : "；").append(r.get("label")).append(r.get("value"));
        }
        // 10-19：订房流程已去掉「活动加购」这一步，取消政策里不再提加购（老订单的加购记录照旧按订单规则处理）
        return sb.append("。").toString();
    }

    public static boolean refundable(String code)
    {
        return !NONREFUNDABLE.equals(normalize(code));
    }

    /**
     * 按政策算扣款。hoursLeft = 距入住时刻的小时数（可以为负），roomFen = 房费，firstNightFen = 首晚房价，paidFen = 实付。
     * 返回 {allowed, ruleLabel, ratePct, deductFen, refundFen}
     */
    public static Map<String, Object> compute(String code, double hoursLeft, long roomFen, long firstNightFen, long paidFen)
    {
        String c = normalize(code);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        long deduct;
        String label;
        if (NONREFUNDABLE.equals(c) || hoursLeft <= 0)
        {
            deduct = paidFen;
            label = NONREFUNDABLE.equals(c) ? "该订单不可退款" : "已过入住时间，不可退款";
        }
        else if (FLEXIBLE.equals(c))
        {
            deduct = hoursLeft >= 24 ? 0 : Math.min(firstNightFen, paidFen);
            label = hoursLeft >= 24 ? "入住前24小时以上，全额退款" : "入住前24小时内，扣除首晚房费";
        }
        else if (hoursLeft >= 7 * 24)
        {
            deduct = 0;
            label = "入住前7天以上，全额退款";
        }
        else if (STRICT.equals(c))
        {
            deduct = Math.min(roomFen / 2, paidFen);
            label = "入住前7天内，扣除50%房费";
        }
        else
        {
            deduct = Math.min(firstNightFen, paidFen);
            label = "入住前7天内，扣除首晚房费";
        }
        long refund = Math.max(0, paidFen - deduct);
        out.put("allowed", refund > 0);
        out.put("ruleLabel", label);
        out.put("ratePct", paidFen == 0 ? 0 : Math.round(deduct * 100.0 / paidFen));
        out.put("deductFen", deduct);
        out.put("refundFen", refund);
        return out;
    }
}
