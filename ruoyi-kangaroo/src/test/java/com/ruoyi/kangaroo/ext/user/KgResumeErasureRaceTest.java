package com.ruoyi.kangaroo.ext.user;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** An already-authenticated request must not recreate private data after erasure. */
class KgResumeErasureRaceTest {
    final KgJdbc db=mock(KgJdbc.class);
    final KgResumeService service=new KgResumeService();
    @BeforeEach void setup(){
        ReflectionTestUtils.setField(service,"db",db);
        when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","1","delFlag","2"));
    }
    void rejects(Runnable action){
        assertEquals(409,assertThrows(ServiceException.class,action::run).getCode());
        verify(db,never()).insert(anyString(),anyMap());verify(db,never()).update(anyString(),anyMap());
        verify(db,never()).updateRow(anyString(),anyString(),any(),anyMap());
    }
    @Test void generatedPlatformExperienceCannotReappear(){rejects(()->service.get(7L));}
    @Test void basicResumeCannotReappear(){rejects(()->service.saveBasic(7L,p("name","姓名","phone","13800138000")));}
    @Test void skillsCannotReappear(){rejects(()->service.saveSkills(7L,p("skill","内容")));}
    @Test void priorApplicationCannotRefillResume(){rejects(()->service.reuseLast(7L));}
    @Test void manualExperienceCannotReappear(){rejects(()->service.saveExp(7L,null,p("kind","volunteer","title","经历")));}
    @Test void removalCannotGenerateNewPlatformExperience(){rejects(()->service.removeExp(7L,12L));}
}
