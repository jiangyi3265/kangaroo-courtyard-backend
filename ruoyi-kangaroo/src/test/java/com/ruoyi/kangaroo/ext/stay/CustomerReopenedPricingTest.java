package com.ruoyi.kangaroo.ext.stay;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CustomerReopenedPricingTest extends CustomerStorePricingDatabaseTest {
 @Test void allThreePublicPricesEqualCheckoutIncludingDiscountOverridesAndStoreMarkup() {
  db.update("update kg_stay set long_stay_nights=15,long_stay_rate=85",p());
  db.update("update kg_stay_room_type set week_price_fen='1700,1800,1900,2000,2100,2200,2300',unit_label='床位'",p());
  Map<String,String> f=new HashMap<>();f.put("checkIn",in.toString());f.put("checkOut",in.plusDays(7).toString());f.put("guests","2");
  for(long id=1;id<=4;id++) {
   db.insert("kg_stay_calendar",p("stay_id",id,"room_type_id",id,"stay_date",in.toString(),"price_fen",110,"stock",10));
   List<Map<String,Object>> periods=rows(browse.roomTypes(id,in.toString(),in.plusDays(7).toString(),2,null).get(0),"periodPrices");
   final long sid=id;
   Map<String,Object> list=rows(browse.list(f,null),"rows").stream().filter(x->n(x,"stayId")==sid).findFirst().get();
   for(int index=0;index<3;index++) {
    int nights=new int[]{7,15,30}[index];Map<String,Object> q=browse.quote(id,in.toString(),in.plusDays(nights).toString(),2,null);
    assertEquals(n(q,"payFen"),n(periods.get(index),"payFen"));
    assertEquals(n(q,"payFen"),n(rows(list,"periodPrices").get(index),"payFen"));
    assertEquals(n(q,"payFen"),n(browse.roomTypes(id,in.toString(),in.plusDays(nights).toString(),2,null).get(0),"displayPayFen"));
    assertEquals(in.plusDays(nights).toString(),periods.get(index).get("checkOut"));
    assertEquals(true,periods.get(index).get("bookable"));
   }
  }
 }
 @Test void longerStaySoldOutAndPromoOutsideDatesAreNotAdvertisedAsBookable() {
  db.insert("kg_stay_calendar",p("stay_id",1,"room_type_id",1,"stay_date",in.plusDays(9).toString(),"price_fen",1700,"stock",0));
  List<Map<String,Object>> prices=rows(browse.roomTypes(1L,in.toString(),in.plusDays(7).toString(),1,null).get(0),"periodPrices");
  assertEquals(true,prices.get(0).get("bookable"));assertEquals(false,prices.get(1).get("bookable"));assertEquals(false,prices.get(2).get("bookable"));
  db.update("update kg_stay_promo set stay_end=:e where promo_id=2",p("e",in.plusDays(8).toString()));
  prices=rows(browse.roomTypes(2L,in.toString(),in.plusDays(7).toString(),1,2L).get(0),"periodPrices");
  assertEquals(true,prices.get(0).get("bookable"));assertEquals(false,prices.get(1).get("bookable"));
 }
}
