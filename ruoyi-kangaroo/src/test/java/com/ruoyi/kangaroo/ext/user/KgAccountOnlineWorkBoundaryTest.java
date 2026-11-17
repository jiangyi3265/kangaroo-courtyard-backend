package com.ruoyi.kangaroo.ext.user;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 未结成果阻止注销，且检查前必须锁账号；SQL 实际边界由真实 HTTP/数据库回归验证。 */
class KgAccountOnlineWorkBoundaryTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgAccountService service = new KgAccountService();
    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        AppLoginUser user = new AppLoginUser(); user.setUserId(2L); AppUserContext.set(user);
        when(db.one(anyString(), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
    }
    @AfterEach void clear() { AppUserContext.remove(); }

    @Test void unfinishedLatestWorkBlocksDeletionAfterApplicationHasEnded()
    {
        when(db.count(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0, String.class).contains("x.round = (select max(x2.round)") ? 1L : 0L);
        Map<String, Object> check = service.deactivationCheck();
        assertEquals(false, check.get("canDeactivate"));
        assertTrue(check.get("blockers").toString().contains("成果尚未处理完成"));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.deactivate("确认注销", "local")).getCode());
        verify(db, never()).update(anyString(), anyMap()); verify(db, never()).insert(anyString(), anyMap());
        InOrder order = inOrder(db);
        order.verify(db).one(contains("kg_app_user where user_id = :u for update"), anyMap());
        order.verify(db).count(contains("kg_staff_grant"), anyMap());
    }

    private void grantCounts(long all, long merchant, long platform)
    {
        when(db.count(anyString(), anyMap())).thenAnswer(i -> {
            String sql = i.getArgument(0, String.class);
            if (!sql.contains("from kg_staff_grant")) return 0L;
            if (sql.contains("role_code = 'merchant'")) return merchant;
            if (sql.contains("role_code in ('manager', 'merchant_service', 'city_lead')")) return platform;
            return all;
        });
    }

    @Test void deactivationTellsEachRoleWhatItCanActuallyDo()
    {
        grantCounts(1, 1, 0);
        String merchant = service.deactivationCheck().get("blockers").toString();
        assertTrue(merchant.contains("退出合作商家"), merchant);
        assertFalse(merchant.contains("主理人撤销"), "本店主理人撤不了合作商家：" + merchant);

        grantCounts(1, 0, 1);
        String platform = service.deactivationCheck().get("blockers").toString();
        assertTrue(platform.contains("联系客服"), platform);
        assertFalse(platform.contains("主理人撤销"), platform);

        grantCounts(1, 0, 0);
        assertTrue(service.deactivationCheck().get("blockers").toString().contains("请先请主理人撤销员工授权"), "普通员工的说法不变");

        grantCounts(3, 1, 1);
        String all = service.deactivationCheck().get("blockers").toString();
        assertTrue(all.contains("退出合作商家") && all.contains("联系客服") && all.contains("主理人撤销"), all);
    }

    @Test void deletedAccountCannotRepeatDeletion()
    {
        when(db.one(anyString(), anyMap())).thenReturn(p("status", "1", "delFlag", "2"));
        assertEquals(Integer.valueOf(409), assertThrows(ServiceException.class, () -> service.deactivate("确认注销", "local")).getCode());
        verify(db, never()).count(anyString(), anyMap()); verify(db, never()).update(anyString(), anyMap());
    }

    @Test void noOutstandingWorkOrDebtAllowsCheck()
    {
        assertEquals(true, service.deactivationCheck().get("canDeactivate"));
    }
}
