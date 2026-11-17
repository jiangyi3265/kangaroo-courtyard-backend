package com.ruoyi.kangaroo.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;
import com.ruoyi.common.exception.ServiceException;

class KgRecruitStoreTest {
    private final KgStoreMapper stores = mock(KgStoreMapper.class);
    private final KgJobServiceImpl service = new KgJobServiceImpl();
    KgRecruitStoreTest() { ReflectionTestUtils.setField(service, "kgStoreMapper", stores); }
    private KgJob job() { KgJob j=new KgJob();j.setStoreId(1L);j.setJobType("volunteer");return j; }
    private void active(long id) { KgStore s=new KgStore();s.setStoreId(id);s.setStatus("0");when(stores.selectKgStoreByStoreId(id)).thenReturn(s); }
    private void apply(KgJob j, KgJob old) { ReflectionTestUtils.invokeMethod(service,"applyRecruitStore",j,old); }
    @Test void publisherOwnershipStaysSeparateFromRecruitingVenue() {
        active(2L);KgJob j=job();j.setRecruitStoreId(2L);apply(j,null);
        assertEquals(1L,j.getStoreId());assertEquals(2L,j.getDisplayStoreId());
    }
    @Test void olderClientsPreserveAssociationButExplicitNullClearsIt() throws Exception {
        active(2L);KgJob old=job();old.setRecruitStoreId(2L);
        ObjectMapper json=new ObjectMapper();KgJob missing=json.readValue("{\"storeId\":1,\"jobType\":\"volunteer\"}",KgJob.class);
        assertFalse(missing.isRecruitStoreProvided());apply(missing,old);assertEquals(2L,missing.getRecruitStoreId());
        KgJob cleared=json.readValue("{\"storeId\":1,\"jobType\":\"volunteer\",\"recruitStoreId\":null}",KgJob.class);
        assertTrue(cleared.isRecruitStoreProvided());apply(cleared,old);assertNull(cleared.getRecruitStoreId());assertEquals(1L,cleared.getDisplayStoreId());
    }
    @Test void deletedOrInactiveRecruitersCannotBeSelected() {
        KgJob j=job();j.setRecruitStoreId(8L);assertThrows(ServiceException.class,()->apply(j,null));
        KgStore s=new KgStore();s.setStatus("1");when(stores.selectKgStoreByStoreId(8L)).thenReturn(s);
        assertThrows(ServiceException.class,()->apply(j,null));
    }
    @Test void ownRecruitmentDoesNotCreateRedundantAssociation() { active(1L);KgJob j=job();j.setRecruitStoreId(1L);apply(j,null);assertNull(j.getRecruitStoreId()); }
    @Test void remoteTasksCannotBorrowPhysicalVenueCertification() { KgJob j=job();j.setJobType("online");j.setRecruitStoreId(2L);assertThrows(ServiceException.class,()->apply(j,null)); }
}
