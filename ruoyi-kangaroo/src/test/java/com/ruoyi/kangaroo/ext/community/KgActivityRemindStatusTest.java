package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 取消 / 退款审核中 / 已退款的报名不能再改集合提醒（2026-10-14 回归 R3-12：之前照样回「将在活动前…提醒你」，其实只给报名成功的发） */
class KgActivityRemindStatusTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KgActivityService service = new KgActivityService();

    void signup(String status)
    {
        ReflectionTestUtils.setField(service, "db", db);
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(db.one(startsWith("select activity_id from kg_activity_signup"), anyMap())).thenReturn(p("activityId", 633L));
        when(db.one(startsWith("select * from kg_activity_signup"), anyMap())).thenReturn(p("signupId", 664L, "activityId", 633L, "status", status, "remind", "1d20"));
        when(db.update(anyString(), anyMap())).thenReturn(1);
    }

    @Test
    void endedSignupsCannotChangeReminder()
    {
        for (String status : new String[] { "refund_pending", "refunding", "refunded", "cancelled", "closed" })
        {
            reset(db);
            signup(status);
            ServiceException e = assertThrows(ServiceException.class, () -> service.setRemind(7L, 664L, "2h"), status);
            assertEquals(409, e.getCode());
            assertTrue(e.getMessage().contains("不会再发集合提醒"), e.getMessage());
            verify(db, never()).update(startsWith("update kg_activity_signup set remind"), anyMap());
        }
    }

    @Test
    void liveSignupsStillChangeReminder()
    {
        for (String status : new String[] { "confirmed", "waitlist", "pending_payment" })
        {
            reset(db);
            signup(status);
            assertEquals("将在活动前 2 小时提醒你", service.setRemind(7L, 664L, "2h"), status);
            verify(db).update(startsWith("update kg_activity_signup set remind"), eq(p("r", "2h", "s", 664L)));
        }
    }
}
