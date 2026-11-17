package com.ruoyi.kangaroo.ext.stay;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amounts are integer fen. Round each room-night before multiplying by units. */
public final class KgSettlementMath {
    private KgSettlementMath() { }

    public static long fee(long baseFen, int basisPoints) {
        if (baseFen < 0 || basisPoints < 0) throw new IllegalArgumentException("金额或比例无效");
        return BigDecimal.valueOf(baseFen).multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    public static final String MERCHANT_FEE_POLICY = "merchant_fee_5pct_v1";
    public static final String DIRECT_NO_FEE_POLICY = "direct_no_fee_v1";

    /** Historical bill snapshots retain the original 5% deduction, including after refunds. */
    public static long merchantStayNet(long receiptsFen) {
        return Math.subtractExact(receiptsFen, fee(receiptsFen, 500));
    }

    public static boolean isDirect(Object opType) {
        return opType == null || "direct".equals(opType);
    }

    /** Configured prices are gross for direct stores, merchant base for other stores. */
    public static long sale(long baseFen, Object opType) {
        long price = sale(baseFen);
        return isDirect(opType) ? price : Math.addExact(price, fee(price, 500));
    }

    /** Only trusted column expressions from source code may be passed here. */
    public static String saleSql(String priceColumn, String opColumn) {
        return "(case when coalesce(" + opColumn + ", 'direct') = 'direct' then " + priceColumn
                + " else " + priceColumn + " + floor((" + priceColumn + " + 10) / 20) end)";
    }

    public static long sale(long baseFen) {
        if (baseFen < 0) throw new IllegalArgumentException("金额无效");
        return baseFen; // Public nightly price is exactly the configured price. Historical orders use snapshots.
    }

    /** 折扣按比例分摊（2026-11-09 主人拍板）：结算价' = round(结算价 × 实付 ÷ (结算价 + 服务费))，服务费' = 实付 − 结算价' */
    public static long shareBase(long baseFen, long feeFen, long paidFen) {
        if (baseFen < 0 || feeFen < 0 || paidFen < 0) throw new IllegalArgumentException("金额无效");
        long total = Math.addExact(baseFen, feeFen);
        if (total == 0) return 0;
        return BigDecimal.valueOf(baseFen).multiply(BigDecimal.valueOf(paidFen))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
