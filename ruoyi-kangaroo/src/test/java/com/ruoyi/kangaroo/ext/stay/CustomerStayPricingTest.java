package com.ruoyi.kangaroo.ext.stay;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import java.util.*;import java.time.*;import static com.ruoyi.kangaroo.ext.KgJdbc.p;
class CustomerStayPricingTest {
 @Test void configuredPricesNeverMarkedUp(){for(long n:new long[]{0,1,17,1700,2000,5714,99999999})assertEquals(n,KgSettlementMath.sale(n));assertEquals(11900,7*KgSettlementMath.sale(1700));assertEquals(25500,15*KgSettlementMath.sale(1700));}
 Map<String,Object> order(){return p("checkIn","2026-11-14","checkOut","2026-11-17","nightly","[{\"date\":\"2026-11-14\",\"priceFen\":1000},{\"date\":\"2026-11-15\",\"priceFen\":2000},{\"date\":\"2026-11-16\",\"priceFen\":3000}]");}
 @Test void unusedNightsUseActualNightlyPricesAndDiscount(){Map<String,Object>r=StayUnusedNights.estimate(order(),5400,0,LocalDate.of(2026,11,15));assertEquals(2,r.get("unusedNights"));assertEquals(4500L,r.get("suggestedFen"));}
 @Test void historicalCheckoutAndPriorRefundTakePrecedence(){Map<String,Object>o=order();o.put("checkedOutTime","2026-11-15 12:00:00");Map<String,Object>r=StayUnusedNights.estimate(o,5400,1000,LocalDate.of(2026,11,20));assertEquals(3500L,r.get("suggestedFen"));}
 @Test void missingSnapshotNeverInventsAmount(){Map<String,Object>o=order();o.put("nightly","[]");assertEquals(false,StayUnusedNights.estimate(o,5400,0,LocalDate.of(2026,11,15)).get("estimateAvailable"));}
 @Test void afterStayAndFullRefundHaveNoSuggestion(){assertEquals(0L,StayUnusedNights.estimate(order(),5400,0,LocalDate.of(2026,11,18)).get("suggestedFen"));assertEquals(0L,StayUnusedNights.estimate(order(),5400,5400,LocalDate.of(2026,11,15)).get("suggestedFen"));}
}
