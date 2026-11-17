package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.KgJdbc;

class KgStayRefundDeletionTest
{
    @ParameterizedTest
    @ValueSource(strings = {"stay", "roomtype"})
    void refundInFlightKeepsTheAccommodationManageable(String key)
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.count(argThat(sql -> sql.contains("from kg_stay_order") && sql.contains("'refunding'")), anyMap())).thenReturn(1L);
        ExtEntity entity = new KgStayModule(db).entities().stream().filter(e -> key.equals(e.key)).findFirst().orElseThrow(AssertionError::new);

        ServiceException e = assertThrows(ServiceException.class, () -> entity.beforeDelete.accept(13L));

        assertEquals(409, e.getCode());
        assertTrue(e.getMessage().contains("退款完成后再删除"), e.getMessage());
        verify(db).count(anyString(), eq(p("id", 13L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stay", "roomtype"})
    void noUnfinishedOrderStillAllowsDeletion(String key)
    {
        KgJdbc db = mock(KgJdbc.class);
        ExtEntity entity = new KgStayModule(db).entities().stream().filter(e -> key.equals(e.key)).findFirst().orElseThrow(AssertionError::new);
        assertDoesNotThrow(() -> entity.beforeDelete.accept(13L));
    }
}
