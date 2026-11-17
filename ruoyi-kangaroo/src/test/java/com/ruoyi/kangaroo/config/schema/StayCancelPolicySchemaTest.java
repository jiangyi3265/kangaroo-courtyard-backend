package com.ruoyi.kangaroo.config.schema;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.kangaroo.ext.KgJdbc;
class StayCancelPolicySchemaTest {
    @Test void startupRequiresCompatibleMigration() {
        KgJdbc db=mock(KgJdbc.class); StayCancelPolicySchema schema=new StayCancelPolicySchema(db,true);
        assertTrue(assertThrows(IllegalStateException.class,schema::check).getMessage().contains("V91__stay_cancel_policy.sql"));
        when(db.count(eq(StayCancelPolicySchema.SQL),anyMap())).thenReturn(1L);
        assertDoesNotThrow(schema::check); assertTrue(schema.requiredIndexes().isEmpty());
    }
    @Test void disabledCheckDoesNotQuery() {
        KgJdbc db=mock(KgJdbc.class); new StayCancelPolicySchema(db,false).check(); verifyNoInteractions(db);
    }
}
