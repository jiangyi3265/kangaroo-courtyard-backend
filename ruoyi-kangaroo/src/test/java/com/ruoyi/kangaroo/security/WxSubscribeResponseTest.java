package com.ruoyi.kangaroo.security;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WxSubscribeResponseTest
{
    @Test
    void onlyExplicitNumericSuccessIsAcknowledged()
    {
        assertTrue(WxMiniAppClient.subscribeSucceeded(JSON.parseObject("{\"errcode\":0,\"errmsg\":\"ok\"}")));
        for (String response : new String[] { "null", "{}", "{\"errmsg\":\"ok\"}", "{\"errcode\":43101}",
                "{\"errcode\":null}", "{\"errcode\":false}", "{\"errcode\":\"bad\"}", "{\"errcode\":0.5}" })
        {
            assertFalse(WxMiniAppClient.subscribeSucceeded(JSON.parseObject(response)), response);
        }
    }
}
