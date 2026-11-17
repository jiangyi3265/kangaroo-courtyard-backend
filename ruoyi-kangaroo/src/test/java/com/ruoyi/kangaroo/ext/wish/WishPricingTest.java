package com.ruoyi.kangaroo.ext.wish;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;
class WishPricingTest {
    @Test void hundredYuanExample(){Map<String,Object> q=WishPricing.quote("100",false);assertEquals(10000L,q.get("costFen"));assertEquals(100L,q.get("markupFen"));assertEquals(10100L,q.get("amountFen"));assertEquals(505L,q.get("feeFen"));assertEquals(9595L,q.get("netFen"));}
    @Test void freeNeverChargesEvenWithStaleCost(){for(Object amount:new Object[]{null,"100","bad"})for(Object value:WishPricing.quote(amount,true).values())assertEquals(0L,value);}
    @Test void roundingAndConservation(){Map<String,Object> half=WishPricing.quote("0.50",false);assertEquals(1L,half.get("markupFen"));assertEquals(3L,half.get("feeFen"));for(String s:new String[]{"0.01","0.49","0.50","9.99","999.99","100000"}){Map<String,Object> q=WishPricing.quote(s,false);assertEquals((Long)q.get("costFen")+(Long)q.get("markupFen"),q.get("amountFen"));assertEquals((Long)q.get("netFen")+(Long)q.get("feeFen"),q.get("amountFen"));}}
    @Test void rejectsInvalidAndExtremeNumbers(){for(Object input:new Object[]{null,"","0","-1","1.001","1e9999999","NaN","100000.01","9999999999999999999999","1,000"})assertThrows(ServiceException.class,()->WishPricing.quote(input,false),String.valueOf(input));}
}
