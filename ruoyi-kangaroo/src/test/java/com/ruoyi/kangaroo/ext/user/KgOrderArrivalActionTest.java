package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgOrderArrivalActionTest
{
    private List<Map<String, Object>> actions(String status, String jobType)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenAnswer(i -> i.getArgument(0, String.class).contains("from kg_application a join kg_job j")
                ? Collections.singletonList(p("appId", 7L, "status", status, "jobType", jobType, "title", "岗位", "reviewed", 1L))
                : Collections.emptyList());
        KgUserHubService service = new KgUserHubService();
        ReflectionTestUtils.setField(service, "db", db);
        return (List<Map<String, Object>>) service.orders(2L, null).get(0).get("btns");
    }

    @Test void unsignedApplicantMustStillReadAndSign()
    {
        String buttons = actions("store_confirmed", "volunteer").toString();
        assertTrue(buttons.contains("签署责任书"));
        assertTrue(buttons.contains("appId=7&view=terms"));
        assertFalse(buttons.contains("到店确认"));
    }

    @Test void signedAndArrivedApplicantsReachArrivalConfirmation()
    {
        for (String status : new String[] { "contract_signed", "onboarded", "completed" })
        {
            String buttons = actions(status, "volunteer").toString();
            assertTrue(buttons.contains("到店确认"), status);
            assertTrue(buttons.contains("appId=7&s=5"), status);
            assertFalse(buttons.contains("签约到店"), status);
        }
    }

    @Test void remoteWorkHasNoArrivalAction()
    {
        String buttons = actions("contract_signed", "online").toString();
        assertFalse(buttons.contains("到店确认"));
        assertFalse(buttons.contains("/pages/jobs/contract"));
    }
}
