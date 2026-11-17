package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.schema.RoomPeriodPriceSchema;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;

/** Exercises the real admin endpoint and all public quote consumers in isolated MySQL. */
class CustomerRoomPeriodDatabaseTest extends CustomerWeeklyTotalDatabaseTest {
    @Test void staffRoundTripPreservesAndClearsPricesAndEnforcesStoreOwnership() {
        KgStayStaffService staff=new KgStayStaffService();
        ReflectionTestUtils.setField(staff,"db",db);
        ReflectionTestUtils.setField(staff,"support",mock(StaffSupport.class));
        ReflectionTestUtils.setField(staff,"files",mock(com.ruoyi.kangaroo.ext.core.KgPrivateFiles.class));
        StaffSupport.Ctx ctx=new StaffSupport.Ctx();ctx.storeId=1L;ctx.role="manager";
        StaffGrantView grant=new StaffGrantView();grant.setStoreId(1L);grant.setRoleCode("manager");grant.setActive(true);
        ctx.me=new AppLoginUser();ctx.me.setUserId(1L);ctx.me.setStaffGrants(Collections.singletonList(grant));
        db.update("update kg_stay set status='draft' where stay_id=1",p());
        Map<String,Object> saved=tx.execute(s->staff.saveRoomType(ctx,null,1L,p("halfMonthTotalFen",60001,"monthTotalFen",100003)));
        assertEquals(60001,n(rows(saved,"roomTypes").get(0),"halfMonthTotalFen"));
        assertEquals(100003,n(rows(saved,"roomTypes").get(0),"monthTotalFen"));
        saved=tx.execute(s->staff.saveRoomType(ctx,null,1L,p("typeName","改房名")));
        assertEquals(60001,n(rows(saved,"roomTypes").get(0),"halfMonthTotalFen"));
        assertThrows(ServiceException.class,()->tx.execute(s->staff.saveRoomType(ctx,null,2L,p("halfMonthTotalFen",60001))));
        assertThrows(ServiceException.class,()->tx.execute(s->staff.saveRoomType(ctx,null,1L,p("monthTotalFen","3000.1"))));
        saved=tx.execute(s->staff.saveRoomType(ctx,null,1L,p("halfMonthTotalFen",null,"monthTotalFen",null)));
        assertNull(rows(saved,"roomTypes").get(0).get("halfMonthTotalFen"));
        assertNull(rows(saved,"roomTypes").get(0).get("monthTotalFen"));
    }

    void setTotals(long id, Object half, Object month) {
        tx.execute(s -> admin.edit("roomtype", p("roomTypeId",id,"halfMonthTotalFen",half,"monthTotalFen",month)));
    }

    @Test void adminTotalsReachRoomCardsListAndCheckoutWithoutDoubleDiscount() {
        setTotals(1, 60001L, 100003L);
        db.update("update kg_stay set long_stay_nights=15,long_stay_rate=85 where stay_id=1",p());
        assertEquals(60001,n(detail(1),"halfMonthTotalFen"));
        assertEquals(100003,n(detail(1),"monthTotalFen"));
        assertEquals(11900,n(quote(1,7,null),"payFen"));
        for(int offset=0;offset<7;offset++) for(int nights:new int[]{15,30}) {
            LocalDate arrival=in.plusDays(offset);
            long expected=nights==15?60001:100003;
            Map<String,Object> q=browse.quote(1L,arrival.toString(),arrival.plusDays(nights).toString(),1,null);
            assertEquals(expected,n(q,"payFen"));assertEquals(0,n(q,"discountFen"));
            assertEquals(true,q.get("periodPriceApplied"));
            assertEquals(expected,rows(q,"nightly").stream().mapToLong(r->n(r,"priceFen")).sum());
            Map<String,Object> card=browse.roomTypes(1L,arrival.toString(),arrival.plusDays(nights).toString(),1,null).get(0);
            assertEquals(expected,n(card,"displayPayFen"));
            assertEquals(expected,n(rows(card,"periodPrices").stream().filter(r->n(r,"nights")==nights).findFirst().get(),"payFen"));
            Map<String,String> f=new HashMap<>();f.put("checkIn",arrival.toString());f.put("checkOut",arrival.plusDays(nights).toString());
            Map<String,Object> listing=rows(browse.list(f,null),"rows").stream().filter(r->n(r,"stayId")==1).findFirst().get();
            assertEquals(expected,n(rows(listing,"periodPrices").stream().filter(r->n(r,"nights")==nights).findFirst().get(),"payFen"));
        }
        long ordinary=1700L*16;
        assertEquals(ordinary-ordinary*15/100,n(quote(1,16,null),"payFen"));
    }

    @Test void clearIsNullAndMetadataOnlyEditsPreservePricesAndHistoricalBill() {
        setTotals(1,60001,100003);
        Map<String,Object> q=quote(1,15,null);bills.captureStay(1L,q);
        Map<String,Object> before=db.must("select * from kg_business_bill where source_id=1",p(),"bill");
        tx.execute(s->admin.edit("roomtype",p("roomTypeId",1L,"typeName","改名")));
        assertEquals(60001,n(detail(1),"halfMonthTotalFen"));
        assertEquals(100003,n(detail(1),"monthTotalFen"));
        setTotals(1,null,null);
        assertNull(detail(1).get("halfMonthTotalFen"));assertNull(detail(1).get("monthTotalFen"));
        assertEquals(25500,n(quote(1,15,null),"payFen"));
        assertEquals(51000,n(quote(1,30,null),"payFen"));
        assertEquals(before,db.must("select * from kg_business_bill where source_id=1",p(),"bill"));
    }

    @Test void calendarOverrideBedUnitsAndSoldOutRemainEffective() {
        setTotals(1,60001,100003);
        db.update("update kg_stay_room_type set unit_label='床位' where room_type_id=1",p());
        db.insert("kg_stay_calendar",p("stay_id",1,"room_type_id",1,"stay_date",in.toString(),"price_fen",9999,"stock",10));
        Map<String,Object> q=browse.quote(1L,in.toString(),in.plusDays(15).toString(),2,null);
        assertEquals((60001-4001+9999)*2,n(q,"payFen"));
        assertEquals(9999,n(rows(q,"nightly").get(0),"priceFen"));
        db.insert("kg_stay_calendar",p("stay_id",1,"room_type_id",1,"stay_date",in.plusDays(10).toString(),"closed","1"));
        assertEquals(false,quote(1,15,null).get("bookable"));
        List<Map<String,Object>> prices=rows(browse.roomTypes(1L,in.toString(),in.plusDays(7).toString(),1,null).get(0),"periodPrices");
        assertEquals(true,prices.get(0).get("bookable"));assertEquals(false,prices.get(1).get("bookable"));
    }

    @Test void existingPartnerMarkupAndEarlyBirdArePreserved() {
        for(long id=1;id<=4;id++) {
            setTotals(id,60001,100003);
            Map<String,Object> q=quote(id,15,null);
            assertEquals(60001,n(q,"merchantBaseFen"));
            assertEquals(id==1?60001:63001,n(q,"payFen"));
            assertEquals(id==1?15000:15750,n(quote(id,15,id),"payFen"));
            assertEquals(false,quote(id,15,id).get("periodPriceApplied"));
        }
    }

    @Test void invalidTotalsDoNotWriteAndNewRoomsCanSetBothDurations() {
        setTotals(1,60001,100003);
        for(String field:new String[]{"halfMonthTotalFen","monthTotalFen"}) {
            for(Object invalid:new Object[]{-1,0,1499,300000001L,"60001.1","invalid",true})
                assertThrows(ServiceException.class,()->tx.execute(s->admin.edit("roomtype",p("roomTypeId",1L,field,invalid))));
        }
        assertEquals(60001,n(detail(1),"halfMonthTotalFen"));
        Map<String,Object> data=p("stayId",1L,"typeName","长住房","roomKind","private","capacity",2,"unitLabel","间","stock",1,"status","0",
                "weekTotalFen",40000,"halfMonthTotalFen",70000,"monthTotalFen",120000);
        long id=((Number)tx.execute(s->admin.add("roomtype",data).get("data"))).longValue();
        assertEquals(70000,n(detail(id),"halfMonthTotalFen"));assertEquals(120000,n(detail(id),"monthTotalFen"));
        new RoomPeriodPriceSchema(db,true).check();
    }
}
