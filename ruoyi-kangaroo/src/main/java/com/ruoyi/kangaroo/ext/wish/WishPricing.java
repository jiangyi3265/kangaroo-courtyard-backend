package com.ruoyi.kangaroo.ext.wish;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Amounts are integer cents. Both fees round half up once at the order boundary. */
public final class WishPricing {
    private WishPricing() { }
    public static Map<String,Object> quote(Object value, boolean free) {
        if (free) return p("costFen",0L,"markupFen",0L,"amountFen",0L,"feeFen",0L,"netFen",0L);
        long cost;
        try {
            String input = String.valueOf(value);
            if (!input.matches("[0-9]{1,6}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            cost = new BigDecimal(input).movePointRight(2).longValueExact();
        } catch (RuntimeException e) { throw new ServiceException("成本价请填写最多两位小数的金额",422); }
        if (cost < 1 || cost > 10000000) throw new ServiceException("成本价须为0.01至100000元",422);
        long markup = BigDecimal.valueOf(cost).multiply(new BigDecimal("0.01")).setScale(0,RoundingMode.HALF_UP).longValueExact();
        long amount = cost + markup;
        long fee = BigDecimal.valueOf(amount).multiply(new BigDecimal("0.05")).setScale(0,RoundingMode.HALF_UP).longValueExact();
        return p("costFen",cost,"markupFen",markup,"amountFen",amount,"feeFen",fee,"netFen",amount-fee);
    }
}
