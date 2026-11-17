package com.ruoyi.kangaroo.ext;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import com.ruoyi.kangaroo.ext.user.KgAccountService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 业务边界回归，DB 是替身；真实 HTTP/落库另由验收脚本验证。 */
class ReleaseBoundaryTest
{
    @Test
    void unavailablePaymentCannotAdvertiseOrCreatePayableOrders()
    {
        KangarooProperties properties = mock(KangarooProperties.class);
        KgJdbc db = mock(KgJdbc.class);
        KgPayService service = new KgPayService();
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "wechat", mock(com.ruoyi.kangaroo.ext.pay.WxPayGateway.class));
        for (String mode : new String[] { "off", "wechat" })
        {
            when(properties.getPayMode()).thenReturn(mode);
            assertEquals(false, service.config().get("enabled"), mode);
            assertThrows(ServiceException.class, () -> service.create("stay", 1, 1, 1L, 100, "test"));
            verifyNoInteractions(db);
        }
        when(properties.getPayMode()).thenReturn("mock");
        assertEquals(true, service.config().get("enabled"));
        assertEquals(true, service.config().get("mock"));
    }

    @Test
    void walletDebtAndCreditBothBlockDeletionOnlySettledBalanceAllowsIt()
    {
        KgJdbc db = mock(KgJdbc.class);
        AtomicLong net = new AtomicLong();
        when(db.count(anyString(), anyMap())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.startsWith("select coalesce(sum(amount_fen)") && sql.contains("from kg_wallet_entry")) return net.get();
            return 0L;
        });
        KgAccountService service = new KgAccountService();
        ReflectionTestUtils.setField(service, "db", db);
        AppLoginUser user = new AppLoginUser(); user.setUserId(987654L);
        AppUserContext.set(user);
        try
        {
            for (long outstanding : new long[] { -1, -5000, 1, 5000 })
            {
                net.set(outstanding);
                assertEquals(false, service.deactivationCheck().get("canDeactivate"), "outstanding=" + outstanding);
                assertThrows(ServiceException.class, () -> service.deactivate("确认注销", "test"));
                verify(db, never()).update(anyString(), anyMap());
            }
            net.set(0);
            assertEquals(true, service.deactivationCheck().get("canDeactivate"));
        }
        finally { AppUserContext.remove(); }
    }
}
