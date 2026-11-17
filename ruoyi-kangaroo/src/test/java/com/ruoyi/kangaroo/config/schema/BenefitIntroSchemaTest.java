package com.ruoyi.kangaroo.config.schema;

import com.ruoyi.kangaroo.ext.KgJdbc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** V40：库里没有 kg_member_benefit.intro 时拒绝启动；关掉断言的开发库不查库。 */
class BenefitIntroSchemaTest {
    @Test void startsOnlyWhenIntroColumnExists() {
        KgJdbc db=mock(KgJdbc.class);
        when(db.count(contains("column_name = 'intro'"),anyMap())).thenReturn(1L);
        assertDoesNotThrow(()->new BenefitIntroSchema(db,true).check());
        verify(db).count(contains("table_name = 'kg_member_benefit'"),anyMap());
    }
    @Test void missingColumnRefusesStartupAndNamesMigration() {
        KgJdbc db=mock(KgJdbc.class);
        when(db.count(anyString(),anyMap())).thenReturn(0L);
        IllegalStateException e=assertThrows(IllegalStateException.class,()->new BenefitIntroSchema(db,true).check());
        assertTrue(e.getMessage().contains("V40__benefit_intro.sql"),e.getMessage());
    }
    @Test void disabledAssertionSkipsDatabase() {
        KgJdbc db=mock(KgJdbc.class);
        assertDoesNotThrow(()->new BenefitIntroSchema(db,false).check());
        verifyNoInteractions(db);
    }
    @Test void registersNoIndexesOnlyTheMigration() {
        BenefitIntroSchema s=new BenefitIntroSchema(mock(KgJdbc.class),true);
        assertTrue(s.requiredIndexes().isEmpty());
        assertEquals("sql/kangaroo/V40__benefit_intro.sql",s.migration());
    }
}
