package com.ruoyi.kangaroo.ext.stay;

import java.util.Map;
import com.ruoyi.common.exception.ServiceException;

/** 房源类型控制订房入口，门店运营类型继续控制原有结算规则。 */
public final class StayBookingChannel {
    private StayBookingChannel() {}
    public static String type(Map<String, Object> stay) {
        Object type = stay.get("houseType");
        if ("direct".equals(type) || "managed".equals(type)) return type.toString();
        return "direct".equals(stay.get("opType")) ? "direct" : "managed";
    }
    public static void requireOnline(Map<String, Object> stay) {
        if ("direct".equals(type(stay)))
            throw new ServiceException("自营房源请联系订房助手确认房型、日期和价格", 409);
    }
}
