package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.controller.app.AppStayController;

class CustomerStorePricingDatabaseTest {
    static KgJdbc db;static TransactionTemplate tx;
    KgStayBrowseService browse;KgBusinessBillService bills;
    LocalDate in=LocalDate.now().plusDays(2);
    static final String[] TYPES={"direct","partner","franchise","managed"};
    @BeforeAll static void init() {
        String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @BeforeEach void setup() {
        for(String t:new String[]{"kg_business_withdrawal","kg_business_bill_log","kg_business_bill","kg_pay_order","kg_stay_order","kg_favorite","kg_stay_promo","kg_stay_calendar","kg_stay_room_type","kg_stay","kg_store"})db.update("delete from "+t,p());
        db.update("delete from sys_config where config_key='kangaroo.stay_booking.enabled'",p());
        db.insert("sys_config",p("config_name","隔离测试","config_key","kangaroo.stay_booking.enabled","config_value","true"));
        for(int i=0;i<TYPES.length;i++) {
            long id=i+1;
            db.insert("kg_store",p("store_id",id,"store_name",TYPES[i]+"测试店","op_type",TYPES[i],"city","平潭"));
            db.insert("kg_stay",p("stay_id",id,"store_id",id,"stay_name",TYPES[i]+"测试房源","city","平潭","status","online","sort",id));
            db.insert("kg_stay_room_type",p("room_type_id",id,"stay_id",id,"type_name","单人房","base_price_fen",1700,"stock",10,"capacity",2));
            db.insert("kg_stay_promo",p("promo_id",id,"stay_id",id,"room_type_id",id,"title","早鸟测试","price_fen",1000,"quota",50,
                "stay_start",in.toString(),"stay_end",in.plusDays(30).toString(),"sale_end",in.plusDays(10)+" 23:59:59"));
        }
        browse=new KgStayBrowseService();ReflectionTestUtils.setField(browse,"db",db);
        KgCityService cities=mock(KgCityService.class);when(cities.businessCity(any())).thenAnswer(i->i.getArgument(0));ReflectionTestUtils.setField(browse,"cities",cities);
        bills=new KgBusinessBillService();ReflectionTestUtils.setField(bills,"db",db);
    }
    @AfterEach void clear(){AppUserContext.remove();}
    long n(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> r,String k){return (List<Map<String,Object>>)r.get(k);}
    Map<String,Object> quote(long id,int nights,Long promo){return browse.quote(id,in.toString(),in.plusDays(nights).toString(),1,promo);}

    @Test void weeklyDisplaySumsDifferentWeekdaysAndDatedTotalsUseCalendarAndGuests() {
        db.update("update kg_stay_room_type set week_price_fen='1700,1800,1900,2000,2100,2200,2300',unit_label='床位'",p());
        for(long id=1;id<=2;id++) {
            long week=id==1?14000:14700;
            assertEquals(week,n(browse.detail(id,null),"displayPriceFen"));
            assertEquals(week,n(browse.roomTypes(id,null,null,1,null).get(0),"displayPriceFen"));
            db.insert("kg_stay_calendar",p("stay_id",id,"room_type_id",id,"stay_date",in.toString(),"price_fen",110,"stock",10));
            for(int nights:new int[]{7,15}) {
                Map<String,Object> q=browse.quote(id,in.toString(),in.plusDays(nights).toString(),2,null);
                Map<String,Object> shown=browse.roomTypes(id,in.toString(),in.plusDays(nights).toString(),2,null).get(0);
                assertEquals(n(q,"roomFen"),n(shown,"displayPriceFen"));assertEquals(nights+"晚房费",shown.get("displayPriceLabel"));
                Map<String,String> filter=new HashMap<>();filter.put("checkIn",in.toString());filter.put("checkOut",in.plusDays(nights).toString());filter.put("guests","2");
                final long stay=id;
                Map<String,Object> card=rows(browse.list(filter,null),"rows").stream().filter(r->n(r,"stayId")==stay).findFirst().get();
                assertEquals(n(q,"roomFen"),n(card,"displayPriceFen"));assertEquals(nights+"晚房费起",card.get("displayPriceLabel"));
            }
        }
    }

    @Test void listSortAndPriceFiltersUseSameRetailRule() {
        Map<String,String> q=new HashMap<>();q.put("sort","price_asc");
        List<Map<String,Object>> all=rows(browse.list(q,null),"rows");assertEquals(4,all.size());assertEquals(1700,n(all.get(0),"priceFromFen"));
        for(int i=1;i<4;i++)assertEquals(1785,n(all.get(i),"priceFromFen"));
        q.put("priceMaxFen","1700");List<Map<String,Object>> cheap=rows(browse.list(q,null),"rows");assertEquals(1,cheap.size());assertEquals(1,n(cheap.get(0),"stayId"));
        q.remove("priceMaxFen");q.put("priceMinFen","1785");assertEquals(3,rows(browse.list(q,null),"rows").size());
        q.clear();q.put("checkIn",in.toString());q.put("checkOut",in.plusDays(7).toString());
        for(Map<String,Object> r:rows(browse.list(q,null),"rows"))assertEquals(n(r,"stayId")==1?1700:1785,n(r,"priceFromFen"));
    }

    @Test void detailsCalendarQuotesAndPromosAgreeForAllStoreTypes() {
        for(long id=1;id<=4;id++) {
            long nightly=id==1?1700:1785,promo=id==1?1000:1050;
            assertEquals(nightly,n(browse.detail(id,null),"priceFromFen"));
            assertEquals(nightly,n(browse.roomTypes(id,null,null,1,null).get(0),"priceFromFen"));
            assertEquals(nightly,n(browse.roomTypes(id,in.toString(),in.plusDays(7).toString(),1,null).get(0),"priceFromFen"));
            Map<String,Object> calendar=browse.calendar(id,in.toString().substring(0,7),null);
            Map<String,Object> day=rows(calendar,"days").stream().filter(r->in.toString().equals(r.get("date"))).findFirst().get();
            assertEquals(nightly,n(day,"priceFen"));assertEquals(nightly,n(calendar,"basePriceFen"));
            assertEquals(nightly*7,n(quote(id,7,null),"payFen"));assertEquals(nightly*15,n(quote(id,15,null),"payFen"));
            assertEquals(promo*7,n(quote(id,7,id),"payFen"));
        }
        for(Map<String,Object> r:rows(browse.promos(null),"rows")){
            assertEquals(n(r,"stayId")==1?1000:1050,n(r,"priceFen"));
            assertEquals(n(r,"stayId")==1?1700:1785,n(r,"strikeFen"));
        }
    }

    @Test void overridesAndBedUnitsRoundEachNightOnce() {
        for(long id=1;id<=2;id++) {
            db.update("update kg_stay_room_type set unit_label='床位',week_price_fen='1700,1700,1700,1700,1700,1700,1700' where room_type_id=:r",p("r",id));
            db.insert("kg_stay_calendar",p("stay_id",id,"room_type_id",id,"stay_date",in.toString(),"price_fen",110,"stock",10));
            Map<String,Object> q=browse.quote(id,in.toString(),in.plusDays(7).toString(),2,null);
            assertEquals(id==1?(110+1700*6)*2:(116+1785*6)*2,n(q,"roomFen"));
            assertEquals((110+1700*6)*2,n(q,"merchantBaseFen"));
            assertEquals(id==1?110:116,n(rows(q,"nightly").get(0),"priceFen"));
        }
    }

    @Test void discountAndSettlementStaySeparateForDirectAndPartner() {
        db.update("update kg_stay set long_stay_nights=7,long_stay_rate=90",p());
        for(long id=1;id<=2;id++) {
            Map<String,Object> q=quote(id,7,null);
            assertEquals(id==1?10710:11246,n(q,"payFen")); // Existing whole-order discount rounds down.
            bills.captureStay(id,q);
            Map<String,Object> row=db.one("select * from kg_business_bill where source_id=:id",p("id",id));
            assertEquals(id==1?"merchant":"guest",row.get("bearer"));
            assertEquals(10710,n(row,"baseFen"));
            assertEquals(id==1?0:536,n(row,"feeFen"));
            assertEquals(id==1?0:500,n(row,"rateBp"));
            assertEquals(id==1?KgSettlementMath.DIRECT_NO_FEE_POLICY:"guest_markup_5pct_v1",q.get("settlementPolicy"));
            assertEquals(n(q,"payFen"),n(row,"baseFen")+n(row,"feeFen"));
        }
    }

    @Test void changingStoreTypeChangesOnlyFutureQuoteNotSavedBill() {
        Map<String,Object> before=quote(2,7,null);bills.captureStay(2,before);
        assertEquals(12495,n(before,"payFen"));
        db.update("update kg_store set op_type='direct' where store_id=2",p());
        assertEquals(11900,n(quote(2,7,null),"payFen"));
        Map<String,Object> bill=db.one("select * from kg_business_bill where source_id=2",p());
        assertEquals(11900,n(bill,"baseFen"));assertEquals(595,n(bill,"feeFen"));assertEquals("guest",bill.get("bearer"));
        assertEquals(12495,n(before,"payFen"));
    }

    @Test void directBillRemainsFreeAfterStoreBecomesPartner() {
        Map<String,Object> before=quote(1,7,null);bills.captureStay(1,before);
        assertEquals(KgSettlementMath.DIRECT_NO_FEE_POLICY,before.get("settlementPolicy"));
        db.update("update kg_store set op_type='partner' where store_id=1",p());
        assertEquals(12495,n(quote(1,7,null),"payFen"));
        Map<String,Object> bill=db.one("select * from kg_business_bill where source_id=1",p());
        assertEquals(11900,n(bill,"baseFen"));assertEquals(0,n(bill,"feeFen"));
        assertEquals(0,n(bill,"rateBp"));assertEquals("merchant",bill.get("bearer"));
        assertEquals(11900,n(before,"payFen"));
    }

    @Test void favoritesAndSharedSqlMatchPublicPrice() {
        for(long id=1;id<=4;id++)db.insert("kg_favorite",p("app_user_id",99L,"target_type","stay","target_id",id));
        AppLoginUser user=new AppLoginUser();user.setUserId(99L);AppUserContext.set(user);
        AppStayController controller=new AppStayController();ReflectionTestUtils.setField(controller,"db",db);
        @SuppressWarnings("unchecked") Map<String,Object> data=(Map<String,Object>)controller.favorites(1,10).get("data");
        for(Map<String,Object> r:rows(data,"rows"))assertEquals(n(r,"stayId")==1?1700:1785,n(r,"priceFromFen"));
        for(long price:new long[]{0,1,9,10,110,1700,9999}) for(String type:TYPES) {
            String op="'"+type+"'";
            long sql=db.count("select "+KgSettlementMath.saleSql(Long.toString(price),op),p());
            assertEquals(KgSettlementMath.sale(price,type),sql);
        }
    }
}
