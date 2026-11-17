package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.admin.KgExtAdminController;
import com.ruoyi.kangaroo.ext.*;

/** Real admin/staff reads and pricing against a randomly named isolated local database. */
class CustomerWeeklyTotalDatabaseTest extends CustomerStorePricingDatabaseTest {
    KgExtAdminController admin;
    ExtEntities entities;

    @BeforeEach void weeklySetup() {
        entities=new ExtEntities();
        ReflectionTestUtils.setField(entities,"db",db);
        ReflectionTestUtils.setField(entities,"contributors",Collections.singletonList(new KgStayModule(db)));
        entities.init();
        admin=new KgExtAdminController();
        ReflectionTestUtils.setField(admin,"db",db);
        ReflectionTestUtils.setField(admin,"entities",entities);
        SysUser user=new SysUser();user.setUserId(1L);user.setUserName("weekly-total-test");
        LoginUser login=new LoginUser();login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,Collections.emptyList()));
    }

    @AfterEach void weeklyCleanup(){SecurityContextHolder.clearContext();}

    @SuppressWarnings("unchecked") Map<String,Object> detail(long id){return (Map<String,Object>)admin.get("roomtype",id).get("data");}

    @Test void metadataShowsOnlyWeeklyTotalButReadQueriesStillUseRealColumns() {
        ExtEntity e=entities.require("roomtype");
        assertFalse(e.selectCols.contains("week_total_fen"));
        assertTrue(e.selectCols.contains("base_price_fen"));
        assertTrue(e.selectCols.contains("week_price_fen"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> fields=(List<Map<String,Object>>)e.meta().get("fields");
        assertFalse(fields.stream().anyMatch(f->Arrays.asList("basePriceFen","weekPriceFen").contains(f.get("name"))));
        Map<String,Object> total=fields.stream().filter(f->"weekTotalFen".equals(f.get("name"))).findFirst().get();
        assertEquals("money",total.get("type"));assertEquals(700L,total.get("min"));assertEquals(70000000L,total.get("max"));
        assertEquals(11900,n(detail(1),"weekTotalFen"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> list=(List<Map<String,Object>>)admin.list("roomtype",new HashMap<>()).getRows();
        assertEquals(4,list.size());for(Map<String,Object> r:list)assertEquals(11900,n(r,"weekTotalFen"));
        assertThrows(ServiceException.class,()->admin.export("roomtype",new HashMap<>(),null),"Only existing supported export entities may export");
    }

    @Test void adminCreatesAndUpdatesTotalWithoutAddingAnyColumnOrChangingHistoricalBill() {
        bills.captureStay(1,quote(1,7,null));
        Map<String,Object> before=db.must("select * from kg_business_bill where source_id=1",p(),"bill");
        tx.execute(s->{admin.edit("roomtype",p("roomTypeId",1L,"weekTotalFen",10001));return null;});
        Map<String,Object> row=detail(1);
        assertEquals(10001,n(row,"weekTotalFen"));
        assertEquals("1429,1429,1429,1429,1429,1428,1428",row.get("weekPriceFen"));
        assertEquals(1428,n(row,"basePriceFen"));
        assertEquals(before,db.must("select * from kg_business_bill where source_id=1",p(),"bill"));
        Map<String,Object> input=p("stayId",1L,"typeName","新周价房","roomKind","private","capacity",2,"unitLabel","间","stock",1,"status","0","weekTotalFen",12345);
        long id=((Number)tx.execute(s->admin.add("roomtype",input).get("data"))).longValue();
        assertEquals(12345,n(detail(id),"weekTotalFen"));
        assertEquals(0,db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_stay_room_type' and column_name='week_total_fen'",p()));
    }

    @Test void allSevenStartingDaysQuoteExactTotalAndPartnerKeepsEachNightMarkup() {
        for(long id=1;id<=2;id++) {
            final long room=id;
            tx.execute(s->{admin.edit("roomtype",p("roomTypeId",room,"weekTotalFen",10001));return null;});
            for(int offset=0;offset<7;offset++) {
                LocalDate start=in.plusDays(offset);
                Map<String,Object> q=browse.quote(id,start.toString(),start.plusDays(7).toString(),1,null);
                assertEquals(10001,n(q,"merchantBaseFen"));
                long expected=0;
                for(Map<String,Object> night:rows(q,"nightly")) expected+=n(night,"priceFen");
                assertEquals(expected,n(q,"payFen"));
                assertEquals(id==1?10001:10498,n(q,"payFen"));
                assertEquals(id==1?0:497,n(q,"platformMarkupFen"));
                assertEquals(0,n(q,"serviceFeeFen"));
            }
        }
    }

    @Test void calendarOverridesAndLongStayDiscountStillApplyToActualReservedDates() {
        tx.execute(s->{admin.edit("roomtype",p("roomTypeId",1L,"weekTotalFen",10001));return null;});
        Map<String,Object> room=detail(1);
        db.insert("kg_stay_calendar",p("stay_id",1,"room_type_id",1,"stay_date",in.toString(),"price_fen",3000,"stock",10));
        for(int nights:new int[]{7,15,30}) {
            long expected=3000;
            for(int i=1;i<nights;i++)expected+=KgRoomWeekPrice.nightly(room,in.plusDays(i));
            assertEquals(expected,n(quote(1,nights,null),"payFen"));
        }
        db.update("update kg_stay set long_stay_nights=15,long_stay_rate=90 where stay_id=1",p());
        long before=3000;for(int i=1;i<15;i++)before+=KgRoomWeekPrice.nightly(room,in.plusDays(i));
        assertEquals(before-before*10/100,n(quote(1,15,null),"payFen"));
        assertEquals(0,n(quote(1,15,null),"serviceFeeFen"));
    }

    @Test void sameTotalAndNonPriceEditsKeepOriginalWeekdayPatternOrLegacyHighBase() {
        db.update("update kg_stay_room_type set base_price_fen=100,week_price_fen='100,200,300,400,500,600,700' where room_type_id=1",p());
        tx.execute(s->{admin.edit("roomtype",p("roomTypeId",1L,"weekTotalFen",2800,"typeName","改名"));return null;});
        assertEquals("100,200,300,400,500,600,700",detail(1).get("weekPriceFen"));
        db.update("update kg_stay_room_type set base_price_fen=100000000,week_price_fen=null where room_type_id=1",p());
        assertEquals(700000000,n(detail(1),"weekTotalFen"));
        tx.execute(s->{admin.edit("roomtype",p("roomTypeId",1L,"typeName","只改资料"));return null;});
        assertEquals(100000000,n(detail(1),"basePriceFen"));assertNull(detail(1).get("weekPriceFen"));
    }

    @Test void legacyClientsRemainCompatibleAndInvalidMixedOrMissingTotalsWriteNothing() {
        tx.execute(s->{admin.edit("roomtype",p("roomTypeId",1L,"basePriceFen",1800));return null;});
        assertEquals(12600,n(detail(1),"weekTotalFen"));
        tx.execute(s->{admin.edit("roomtype",p("roomTypeId",1L,"weekPriceFen","100,200,300,400,500,600,700"));return null;});
        assertEquals(2800,n(detail(1),"weekTotalFen"));
        long count=db.count("select count(*) from kg_stay_room_type",p());
        for(Object invalid:new Object[]{null,"",699,70000001,"1000.1","bad"})
            assertThrows(ServiceException.class,()->tx.execute(s->admin.edit("roomtype",p("roomTypeId",1L,"weekTotalFen",invalid))));
        for(String old:new String[]{"basePriceFen","weekPriceFen"})
            assertThrows(ServiceException.class,()->tx.execute(s->admin.edit("roomtype",p("roomTypeId",1L,"weekTotalFen",10001,old,old.equals("basePriceFen")?1800:"100,200,300,400,500,600,700"))));
        Map<String,Object> create=p("stayId",1L,"typeName","无价格","roomKind","private","capacity",2,"unitLabel","间","stock",1,"status","0");
        assertThrows(ServiceException.class,()->tx.execute(s->admin.add("roomtype",create)));
        assertEquals(count,db.count("select count(*) from kg_stay_room_type",p()));
        assertEquals(2800,n(detail(1),"weekTotalFen"));
        create.put("basePriceFen",1700);
        long id=((Number)tx.execute(s->admin.add("roomtype",create).get("data"))).longValue();
        assertEquals(11900,n(detail(id),"weekTotalFen"));
    }
}
