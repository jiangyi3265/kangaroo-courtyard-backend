package com.ruoyi.kangaroo.ext.staff;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 新建线上任务的截止时间上限：最多 180 天内（不连库，只走到截止时间校验） */
class KgOnlineTaskDeadlineTest
{
    private final KgOnlineTaskService service = new KgOnlineTaskService();

    private ServiceException create(LocalDateTime deadline)
    {
        ReflectionTestUtils.setField(service, "db", mock(KgJdbc.class));
        Map<String, Object> b = KgJdbc.p("title", "探店笔记", "taskType", "内容写作", "deliverables", "一篇笔记", "criteria", "原创",
                "description", "", "commissionFen", 5000L, "quota", 2L, "deadline", deadline.format(KgOnlineTaskService.DT));
        try
        {
            ReflectionTestUtils.invokeMethod(service, "createTask", 3L, "平潭袋鼠小院", "staff:1", "staff", b);
        }
        catch (ServiceException e)
        {
            return e;
        }
        catch (RuntimeException e)
        {
            return null;  // 截止时间校验过了，后面要连库的步骤在打桩下失败，不关心
        }
        return null;
    }

    @Test
    void deadlineMustBeWithin180Days()
    {
        ServiceException far = create(LocalDateTime.now().plusDays(181));
        assertNotNull(far);
        assertEquals(Integer.valueOf(422), far.getCode());
        assertEquals("截止时间最多 180 天内", far.getMessage());
        ServiceException ok = create(LocalDateTime.now().plusDays(179));
        assertTrue(ok == null || !ok.getMessage().contains("截止时间"), ok == null ? "" : ok.getMessage());
        ServiceException soon = create(LocalDateTime.now().plusMinutes(30));
        assertNotNull(soon);
        assertEquals("截止时间至少晚于现在 2 小时", soon.getMessage());
    }
}
