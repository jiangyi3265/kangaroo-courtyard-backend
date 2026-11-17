package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;

class KgVolunteerDailyServiceTest {
    @Test void inclusiveTermAndInvalidData() {
        assertEquals(LocalDate.of(2028,2,29),KgVolunteerDailyService.end("2028-02-01",29));
        assertEquals(LocalDate.of(2026,10,29),KgVolunteerDailyService.end("2026-10-29",1));
        assertNull(KgVolunteerDailyService.end(null,30));
        assertNull(KgVolunteerDailyService.end("2026-02-30",30));
        assertNull(KgVolunteerDailyService.end("2026-10-29",0));
    }
    @Test void sameContractGetsFiveAndThreeDayRemindersExactlyOncePerPerson() {
        KgJdbc db=mock(KgJdbc.class);StaffSupport support=mock(StaffSupport.class);KgCityService cities=mock(KgCityService.class);
        KgVolunteerDailyService service=new KgVolunteerDailyService();
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"support",support);ReflectionTestUtils.setField(service,"cities",cities);
        when(cities.jobCity(anyString())).thenAnswer(i->i.getArgument(0));
        Map<String,Object> app=p("arrivalDate","2026-10-01","stayDays",30,"city","平潭","realName","小张","shopName","合作民宿");
        when(db.one(contains("for update"),anyMap())).thenReturn(app);
        when(db.list(contains("kg_staff_grant"),anyMap())).thenReturn(Arrays.asList(p("appUserId",1L,"storeId",10L,"city","平潭"),p("appUserId",1L,"storeId",11L,"city","平潭"),p("appUserId",2L,"storeId",20L,"city","丽江")));
        Set<String> keys=new HashSet<String>();when(db.claimIdem(anyString(),eq("volunteer_expiry"))).thenAnswer(i->keys.add(i.getArgument(0)));
        for(int day:new int[]{24,25,25,26,27,27,28,29,30,31})service.remindOne(100L,LocalDate.of(2026,10,day));
        verify(support,times(2)).notifyStaff(eq(1L),eq("system"),anyString(),anyString(),eq("volunteer_expiry"),eq(100L),eq("/pages/staff/volunteers?storeId=10"));
        verify(support,never()).notifyStaff(eq(2L),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        assertEquals(new HashSet<String>(Arrays.asList("volunteer-expiry:100:2026-10-30:5:1","volunteer-expiry:100:2026-10-30:3:1")),keys);
        // 延期至11月29日：原窗口不再提醒，新5天窗口仍可以发出。
        app.put("stayDays",60);service.remindOne(100L,LocalDate.of(2026,10,29));
        verify(support,times(2)).notifyStaff(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        service.remindOne(100L,LocalDate.of(2026,11,24));
        verify(support,times(3)).notifyStaff(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
    }
    @Test void catchupDoesNotSendBothMissedStagesTogether() {
        KgJdbc db=mock(KgJdbc.class);StaffSupport support=mock(StaffSupport.class);KgCityService cities=mock(KgCityService.class);
        KgVolunteerDailyService service=new KgVolunteerDailyService();
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"support",support);ReflectionTestUtils.setField(service,"cities",cities);
        when(cities.jobCity(anyString())).thenAnswer(i->i.getArgument(0));
        when(db.one(anyString(),anyMap())).thenReturn(p("arrivalDate","2026-10-01","stayDays",30,"city","平潭"));
        when(db.list(anyString(),anyMap())).thenReturn(Collections.singletonList(p("appUserId",1L,"storeId",10L,"city","平潭")));
        when(db.claimIdem(anyString(),anyString())).thenReturn(true);
        service.remindOne(100L,LocalDate.of(2026,10,28));
        verify(db).claimIdem("volunteer-expiry:100:2026-10-30:3:1","volunteer_expiry");
        verify(support,times(1)).notifyStaff(anyLong(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
    }
}
