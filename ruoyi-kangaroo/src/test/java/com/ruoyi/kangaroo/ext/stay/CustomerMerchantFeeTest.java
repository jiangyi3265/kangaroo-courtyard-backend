package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

class CustomerMerchantFeeTest {
    @Test void grossPriceSplitsWithFeeRoundedOnceAndRemainderToMerchant() {
        long[][] examples = {{0,0,0},{1,1,0},{9,9,0},{10,9,1},{1700,1615,85},
            {11900,11305,595},{25500,24225,1275},{10710,10174,536},{5355,5087,268},{10000,9500,500}};
        for (long[] row : examples) {
            assertEquals(row[1], KgSettlementMath.merchantStayNet(row[0]));
            assertEquals(row[2], row[0] - KgSettlementMath.merchantStayNet(row[0]));
        }
        assertThrows(IllegalArgumentException.class, () -> KgSettlementMath.merchantStayNet(-1));
    }

    Map<String,Object> quote(long paid) {
        return p("stay",p("storeId",1L,"stayName","测试门店","typeName","单人间"),
            "settlementPolicy",KgSettlementMath.MERCHANT_FEE_POLICY,"payFen",paid,
            "merchantBaseFen",11900L,"platformMarkupFen",595L,"discountFen",1190L,
            "addonFen",0,"serviceFeeFen",0);
    }

    @Test @SuppressWarnings("unchecked") void allDiscountTypesUseFinalPayInsteadOfRawRoomBase() {
        KgJdbc db = mock(KgJdbc.class);
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service,"db",db);
        for (String mode : new String[]{"", "floor", "nights"}) {
            Map<String,Object> q = quote("nights".equals(mode) ? 0 : 10710);
            q.put("memberMode",mode);
            service.captureStay(7L,q);
        }
        ArgumentCaptor<Map<String,Object>> rows = ArgumentCaptor.forClass(Map.class);
        verify(db,times(3)).insert(eq("kg_business_bill"),rows.capture());
        for (int i=0;i<3;i++) {
            Map<String,Object> row=rows.getAllValues().get(i);
            assertEquals(i==2 ? 0L : 10174L,row.get("base_fen"));
            assertEquals(i==2 ? 0L : 536L,row.get("fee_fen"));
            assertEquals("merchant",row.get("bearer"));
            assertEquals(500,row.get("rate_bp"));
        }
    }

    @Test void invalidReceiptOrMixedExtraServiceNeverCreatesBill() {
        KgJdbc db=mock(KgJdbc.class);
        KgBusinessBillService service=new KgBusinessBillService();
        ReflectionTestUtils.setField(service,"db",db);
        for (Object bad : new Object[]{null,-1L,"17.5"}) {
            Map<String,Object> q=quote(1700);q.put("payFen",bad);
            assertThrows(ServiceException.class,()->service.captureStay(7L,q));
        }
        Map<String,Object> q=quote(1700);q.put("addonFen",100);
        assertThrows(ServiceException.class,()->service.captureStay(7L,q));
        verify(db,never()).insert(anyString(),anyMap());
    }

    @Test @SuppressWarnings("unchecked") void newDirectBillsKeepAllActualReceiptsForEveryDiscountMode() {
        KgJdbc db = mock(KgJdbc.class);
        KgBusinessBillService service = new KgBusinessBillService();
        ReflectionTestUtils.setField(service,"db",db);
        for (String mode : new String[]{"", "floor", "nights"}) {
            Map<String,Object> q = quote("nights".equals(mode) ? 0 : 10710);
            q.put("settlementPolicy",KgSettlementMath.DIRECT_NO_FEE_POLICY);
            q.put("memberMode",mode);
            service.captureStay(7L,q);
        }
        ArgumentCaptor<Map<String,Object>> rows = ArgumentCaptor.forClass(Map.class);
        verify(db,times(3)).insert(eq("kg_business_bill"),rows.capture());
        for (int i=0;i<3;i++) {
            Map<String,Object> row=rows.getAllValues().get(i);
            assertEquals(i==2 ? 0L : 10710L,row.get("base_fen"));
            assertEquals(0L,row.get("fee_fen"));
            assertEquals("merchant",row.get("bearer"));
            assertEquals(0,row.get("rate_bp"));
            assertTrue(row.get("basis").toString().contains("不收平台服务费"));
        }
    }

    @Test void invalidDirectReceiptOrMixedExtraServiceNeverCreatesBill() {
        KgJdbc db=mock(KgJdbc.class);
        KgBusinessBillService service=new KgBusinessBillService();
        ReflectionTestUtils.setField(service,"db",db);
        for (Object bad : new Object[]{null,-1L,"17.5"}) {
            Map<String,Object> q=quote(1700);q.put("settlementPolicy",KgSettlementMath.DIRECT_NO_FEE_POLICY);q.put("payFen",bad);
            assertThrows(ServiceException.class,()->service.captureStay(7L,q));
        }
        Map<String,Object> q=quote(1700);q.put("settlementPolicy",KgSettlementMath.DIRECT_NO_FEE_POLICY);q.put("addonFen",100);
        assertThrows(ServiceException.class,()->service.captureStay(7L,q));
        verify(db,never()).insert(anyString(),anyMap());
    }
}
