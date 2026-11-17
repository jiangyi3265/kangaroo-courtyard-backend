package com.ruoyi.kangaroo.ext.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** 微信 time_expire 必须带秒：截止时间正好整分时，之前会写成 14:14+08:00，被微信判参数错误。 */
class KgPayWxTimeTest {
    @Test void wholeMinuteKeepsSeconds() {
        assertEquals("2026-10-11T14:14:00+08:00", KgPayService.wxTime(LocalDateTime.of(2026, 10, 11, 14, 14, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
    }

    @Test void secondsAndMillisAreFormattedPlainly() {
        assertEquals("2026-10-11T14:13:58+08:00", KgPayService.wxTime(LocalDateTime.of(2026, 10, 11, 14, 13, 58, 123000000).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
    }
}
