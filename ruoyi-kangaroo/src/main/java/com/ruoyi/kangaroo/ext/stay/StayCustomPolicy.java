package com.ruoyi.kangaroo.ext.stay;

import java.math.BigDecimal;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;

/** 自定义规则存房源，确认文案、订单快照和退款计算共用同一份结构化规则。 */
public final class StayCustomPolicy {
    private StayCustomPolicy() {}
    public static final String CUSTOM = "custom";

    public static Map<String, Object> parse(Object raw) {
        if (raw == null || String.valueOf(raw).trim().isEmpty()) return null;
        try {
            Map<?, ?> source = raw instanceof Map ? (Map<?, ?>) raw : JSON.parseObject(String.valueOf(raw));
            if (source == null || !source.keySet().equals(new HashSet<String>(Arrays.asList("version", "freeDays", "lateMode", "latePercent")))) throw new IllegalArgumentException();
            int version = integer(source.get("version"), 1, 1);
            int days = integer(source.get("freeDays"), 0, 180);
            String mode = String.valueOf(source.get("lateMode"));
            if (!Arrays.asList("first_night", "percent", "nonrefundable").contains(mode)) throw new IllegalArgumentException();
            int pct = integer(source.get("latePercent"), 0, 100);
            Map<String, Object> rules = new LinkedHashMap<>();
            rules.put("version", version); rules.put("freeDays", days); rules.put("lateMode", mode);
            rules.put("latePercent", "percent".equals(mode) ? pct : 0);
            return rules;
        } catch (Exception e) { throw new ServiceException("取消退款规则无效：提前天数需为0–180的整数，扣费比例需为0–100的整数"); }
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Number)) throw new IllegalArgumentException();
        int n = new BigDecimal(value.toString()).intValueExact();
        if (n < min || n > max) throw new IllegalArgumentException();
        return n;
    }

    public static void validateColumns(Map<String, Object> cols) {
        if (!cols.containsKey("cancel_policy_config")) return;
        Map<String, Object> rules = parse(cols.get("cancel_policy_config"));
        cols.put("cancel_policy_config", rules == null ? null : JSON.toJSONString(rules));
    }

    public static Map<String, Object> snapshot(String preset, Object config, boolean forceNonrefundable, String checkinTime) {
        Map<String, Object> rules = forceNonrefundable ? null : parse(config);
        String code = forceNonrefundable ? StayPolicy.NONREFUNDABLE : rules == null ? StayPolicy.normalize(preset) : CUSTOM;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        if (rules == null) {
            out.put("title", StayPolicy.title(code)); out.put("summary", StayPolicy.summary(code));
            out.put("rows", StayPolicy.rows(code)); out.put("refundable", StayPolicy.refundable(code));
            out.put("key", "v1|" + code + "|" + checkinTime);
            return out;
        }
        int days = (Integer) rules.get("freeDays");
        String deduction = deductionText(rules);
        String title = days == 0 ? "入住前可免费取消" : "入住前" + days + "天可免费取消，之后" + deduction;
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(days == 0 ? "入住前" : "距入住时刻不少于" + days + "天", "全额退款"));
        if (days > 0) rows.add(row("入住前" + days + "天内", deduction));
        rows.add(row("入住时刻及之后", "不可退款"));
        StringJoiner summary = new StringJoiner("；", "", "。");
        for (Map<String, Object> r : rows) summary.add(r.get("label") + "：" + r.get("value"));
        out.put("rules", rules); out.put("title", title); out.put("summary", summary.toString());
        out.put("rows", rows); out.put("refundable", true);
        out.put("key", "v1|custom|" + days + "|" + rules.get("lateMode") + "|" + rules.get("latePercent") + "|" + checkinTime);
        return out;
    }

    private static Map<String, Object> row(String label, String value) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("label", label); out.put("value", value); return out;
    }

    private static String deductionText(Map<String, Object> rules) {
        String mode = (String) rules.get("lateMode");
        if ("first_night".equals(mode)) return "扣首晚房费";
        if ("nonrefundable".equals(mode)) return "不可退款";
        int percent = (Integer) rules.get("latePercent");
        return percent == 0 ? "全额退款" : "扣" + percent + "%房费";
    }

    public static Map<String, Object> compute(Map<String, Object> snapshot, double hoursLeft, long roomFen, long firstNightFen, long paidFen) {
        String code = String.valueOf(snapshot.get("code"));
        if (!CUSTOM.equals(code)) return StayPolicy.compute(code, hoursLeft, roomFen, firstNightFen, paidFen);
        Map<String, Object> rules = parse(snapshot.get("rules"));
        if (rules == null) throw new ServiceException("订单取消退款规则缺失，请联系平台核对");
        long paid = Math.max(0, paidFen), deduct;
        int days = (Integer) rules.get("freeDays");
        String label;
        if (hoursLeft <= 0) { deduct = paid; label = "已到入住时间，不可退款"; }
        else if (hoursLeft >= days * 24.0) { deduct = 0; label = "符合免费取消期限，全额退款"; }
        else {
            String mode = (String) rules.get("lateMode");
            if ("first_night".equals(mode)) deduct = Math.min(paid, Math.max(0, firstNightFen));
            else if ("nonrefundable".equals(mode)) deduct = paid;
            else deduct = Math.min(paid, BigDecimal.valueOf(Math.max(0, roomFen)).multiply(BigDecimal.valueOf((Integer) rules.get("latePercent"))).divideToIntegralValue(BigDecimal.valueOf(100)).longValue());
            label = "入住前" + days + "天内，" + deductionText(rules);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", paid > deduct); out.put("ruleLabel", label);
        out.put("ratePct", paid == 0 ? 0 : Math.round(deduct * 100.0 / paid));
        out.put("deductFen", deduct); out.put("refundFen", paid - deduct);
        return out;
    }
}
