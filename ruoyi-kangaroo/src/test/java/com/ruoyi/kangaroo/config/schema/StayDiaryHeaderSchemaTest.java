package com.ruoyi.kangaroo.config.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.kangaroo.ext.KgJdbc;

class StayDiaryHeaderSchemaTest
{
    @Test
    void missingOrIncompatibleColumnFailsBeforeServingRequests()
    {
        KgJdbc db = mock(KgJdbc.class);
        StayDiaryHeaderSchema schema = new StayDiaryHeaderSchema(db, true);
        assertTrue(assertThrows(IllegalStateException.class, schema::check).getMessage().contains("V57__stay_diary_subtitle.sql"));
        when(db.count(eq(StayDiaryHeaderSchema.SQL), anyMap())).thenReturn(1L);
        assertDoesNotThrow(schema::check);
        assertTrue(StayDiaryHeaderSchema.SQL.contains("character_maximum_length >= 80"));
        assertTrue(StayDiaryHeaderSchema.SQL.contains("is_nullable = 'NO'"));
        assertTrue(schema.requiredIndexes().isEmpty());
    }

    @Test
    void explicitDisabledCheckDoesNotReadSchema()
    {
        KgJdbc db = mock(KgJdbc.class);
        new StayDiaryHeaderSchema(db, false).check();
        verifyNoInteractions(db);
    }
}
