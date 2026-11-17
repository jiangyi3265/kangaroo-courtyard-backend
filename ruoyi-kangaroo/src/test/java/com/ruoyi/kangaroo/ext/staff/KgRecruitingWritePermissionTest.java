package com.ruoyi.kangaroo.ext.staff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Roles without the relevant job/online edit permission cannot forward or review work. */
class KgRecruitingWritePermissionTest {
    private final KgJdbc db = mock(KgJdbc.class);
    private final StaffSupport support = mock(StaffSupport.class);
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach void setup() {
        ctx.storeId = 10L;
        ctx.role = "online_staff";
        when(support.ctx(10L)).thenReturn(ctx);
        doCallRealMethod().when(support).need(any(), anyString(), anyString(), anyString(), anyString());
    }

    private void denied(Runnable action) {
        ServiceException error = assertThrows(ServiceException.class, action::run);
        assertEquals(Integer.valueOf(403), error.getCode());
        verifyNoInteractions(db);
    }

    @Test void sameStoreForwardCannotGrantAccessAsOnlineOnlyStaff() {
        KgWorkbenchService service = new KgWorkbenchService();
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        denied(() -> service.forward(10L, 1L, "manager", "", true));
    }

    @Test void crossStoreForwardCannotGrantAccessAsOnlineOnlyStaff() {
        KgResumeShareService service = new KgResumeShareService(db, support, mock(KgWorkbenchService.class));
        denied(() -> service.send(10L, 1L, 20L, "", true));
    }

    @Test void onlineReviewDeniesBothAcceptAndReturnBeforeAnyDataAccess() {
        ctx.role = "manager"; // 换工主理人只核实需求，不发布/验收线上任务。
        KgOnlineTaskService service = new KgOnlineTaskService();
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "support", support);
        denied(() -> service.review(ctx, 1L, true, "验收通过"));
        denied(() -> service.review(ctx, 1L, false, "请补齐成果"));
    }
}
