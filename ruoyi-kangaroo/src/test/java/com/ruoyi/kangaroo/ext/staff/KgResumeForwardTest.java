package com.ruoyi.kangaroo.ext.staff;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Delivery stays within the application store; store target includes keeper/staff, not only managers. */
class KgResumeForwardTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final KgWorkbenchService service = new KgWorkbenchService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        ctx.storeId = 102L; ctx.storeName = "平潭测试门店"; ctx.role = "manager";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setNickname("发送者");
        when(support.ctx(102L)).thenReturn(ctx);
        when(db.must(anyString(), anyMap(), eq("报名不存在"))).thenReturn(p("appNo", "QA201", "realName", "测试", "title", "接待换工"));
        when(db.must(eq("select app_user_id from kg_application where app_id=:id and store_id=:s and del_flag='0'"), eq(p("id",201L,"s",102L)), eq("报名不存在")))
                .thenReturn(p("appUserId",7L));
        when(db.one(eq("select status, del_flag from kg_app_user where user_id=:u for update"),eq(p("u",7L))))
                .thenReturn(p("status","0","delFlag","0"));
        when(db.one(eq("select app_id from kg_application where app_id=:id for update"),eq(p("id",201L))))
                .thenReturn(p("appId",201L));
        grants(Arrays.asList(p("appUserId", 1L, "roleCode", "manager"), p("appUserId", 2L, "roleCode", "manager"),
                p("appUserId", 3L, "roleCode", "keeper"), p("appUserId", 4L, "roleCode", "staff")));
        when(support.notifyStaffWithId(anyLong(), eq("staff"), anyString(), anyString(), eq("application"), eq(201L), anyString()))
                .thenAnswer(inv -> ((Long) inv.getArgument(0)) + 100L);
    }

    private void grants(List<Map<String, Object>> grants)
    {
        when(db.list(contains("from kg_staff_grant"), eq(p("s", 102L)))).thenReturn(grants);
    }

    private void delivered(Long uid)
    {
        verify(support).notifyStaffWithId(eq(uid), eq("staff"), anyString(), contains("接待换工"), eq("application"), eq(201L),
                eq("/pages/staff/applications?s=2&appId=201"));
    }

    @Test
    void storeTargetDeliversToManagerKeeperAndStaffButNotSender()
    {
        Map<String, Object> result = service.forward(102L, 201L, "store", "请协助查看", true);
        assertEquals(3, result.get("count"));
        delivered(2L); delivered(3L); delivered(4L);
        verify(support, never()).notifyStaffWithId(eq(1L), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(db).list(contains("status = '0' and del_flag = '0'"), eq(p("s", 102L)));
        verify(db).insert(eq("kg_resume_forward"), argThat(row -> "store".equals(row.get("target")) && "102,103,104".equals(row.get("msg_ids"))));
    }

    @Test
    void managerTargetExcludesKeeperAndStaff()
    {
        assertEquals(1, service.forward(102L, 201L, "manager", "", false).get("count"));
        delivered(2L);
        verify(support, times(1)).notifyStaffWithId(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(db).insert(eq("kg_resume_forward"), argThat(row -> "0".equals(row.get("track"))));
    }

    @Test
    void noOtherStaffDoesNotRecordASuccess()
    {
        grants(Collections.singletonList(p("appUserId", 1L, "roleCode", "manager")));
        assertEquals("本店暂时没有其他在职员工", assertThrows(ServiceException.class,
                () -> service.forward(102L, 201L, "store", "", true)).getMessage());
        verify(db, never()).insert(anyString(), anyMap());
        verify(support, never()).notifyStaffWithId(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void disabledNotificationsAreNotCountedAsDelivered()
    {
        when(support.notifyStaffWithId(eq(3L), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString())).thenReturn(null);
        Map<String, Object> result = service.forward(102L, 201L, "store", "", true);
        assertEquals(2, result.get("count")); assertEquals(3, result.get("targets"));
        assertTrue(result.get("message").toString().contains("另有 1 位"));
        verify(db).insert(eq("kg_resume_forward"), argThat(row -> "102,104".equals(row.get("msg_ids"))));
    }

    @Test
    void applicationFromAnotherStoreIsRejectedBeforeDelivery()
    {
        when(db.must(contains("a.store_id = :s"), eq(p("id", 201L, "s", 102L)), eq("报名不存在")))
                .thenThrow(new ServiceException("报名不存在"));
        assertThrows(ServiceException.class, () -> service.forward(102L, 201L, "store", "", true));
        verify(support, never()).notifyStaffWithId(anyLong(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void unauthorizedStoreIsRejectedBeforeLoadingApplication()
    {
        when(support.ctx(999L)).thenThrow(new ServiceException("没有该门店的操作权限"));
        assertThrows(ServiceException.class, () -> service.forward(999L, 201L, "store", "", true));
        verifyNoInteractions(db);
    }
}
