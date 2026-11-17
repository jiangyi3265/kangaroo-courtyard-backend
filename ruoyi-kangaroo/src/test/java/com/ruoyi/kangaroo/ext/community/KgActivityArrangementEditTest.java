package com.ruoyi.kangaroo.ext.community;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KgActivityArrangementEditTest {
    KgJdbc db; KgActivityService service; KgCommunityGuard guard; StaffSupport support; KgChatService chats;
    Map<String,Object> activity;
    String time(long offset) {return StaffSupport.fmt(new Date(System.currentTimeMillis()+offset),"yyyy-MM-dd HH:mm:ss");}
    @BeforeEach void setup() {
        db=mock(KgJdbc.class);guard=mock(KgCommunityGuard.class);support=mock(StaffSupport.class);chats=mock(KgChatService.class);service=new KgActivityService();
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"guard",guard);
        ReflectionTestUtils.setField(service,"support",support);ReflectionTestUtils.setField(service,"chats",chats);
        when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
        when(guard.check(any())).thenReturn("pass");when(guard.initialStatus(any())).thenReturn("online");
        activity=p("activityId",6L,"appUserId",7L,"source","user","status","paused","signed",5L,"title","原活动","intro","介绍","safetyNote","注意","cover","/profile/a.jpg",
                "startTime",time(86400000),"endTime",time(108000000),"deadline",time(-3600000),"place","原地点","city","平潭","feeFen",5000L,"capacity",10L);
        when(db.one(contains("from kg_activity where"),anyMap())).thenAnswer(i->activity);
        when(db.list(contains("select distinct app_user_id"),anyMap())).thenReturn(Arrays.asList(p("appUserId",8L),p("appUserId",9L)));
    }
    Map<String,Object> body(){return p("detailsOnly",true,"editToken",KgPostService.editToken(activity),"title","原活动","intro","介绍","safetyNote","注意","cover","/profile/a.jpg",
            "startTime",activity.get("startTime"),"endTime",activity.get("endTime"),"deadline",activity.get("deadline"),"place","新的集合地点","city","平潭");}
    @SuppressWarnings("unchecked") Map<String,Object> written(){ArgumentCaptor<Map<String,Object>> c=ArgumentCaptor.forClass(Map.class);verify(db).updateRow(eq("kg_activity"),eq("activity_id"),eq(6L),c.capture());return c.getValue();}
    ServiceException reject(Map<String,Object>b){ServiceException e=assertThrows(ServiceException.class,()->service.editByUser(7L,6L,b));verify(db,never()).updateRow(anyString(),anyString(),any(),anyMap());verifyNoInteractions(support,chats);return e;}
    @Test void signedArrangementChangesKeepStatusFeesAndSignupsAndNotifyAllActiveMembers(){
        Map<String,Object>b=body();b.put("startTime",time(172800000));b.put("endTime",time(180000000));
        service.editByUser(7L,6L,b);Map<String,Object>cols=written();
        assertEquals("paused",cols.get("status"));assertEquals("新的集合地点",cols.get("place"));
        assertEquals(StaffSupport.parse(activity.get("deadline")),cols.get("deadline"));
        for(String k:Arrays.asList("signed","capacity","fee_fen","fee_mode","aa_fen","post_id","app_user_id","conv_id"))assertFalse(cols.containsKey(k),k);
        verify(support).notifyUser(eq(8L),eq("system"),anyString(),contains("新的集合地点"),eq("activity"),eq(6L),eq("/pages/community/activity?id=6"));
        verify(support).notifyUser(eq(9L),eq("system"),anyString(),contains("原报名保留"),eq("activity"),eq(6L),anyString());
        verify(db).list(contains("'confirmed','pending_payment','waitlist'"),anyMap());verify(chats).systemMessage(eq(6L),contains("北京时间"));
        verify(db,never()).update(contains("kg_activity_signup"),anyMap());
    }
    @Test void unchangedArrangementDoesNotNotifyOrRewriteSchedule(){Map<String,Object>b=body();b.put("place",activity.get("place"));b.put("title","改标题");service.editByUser(7L,6L,b);assertFalse(written().containsKey("start_time"));verifyNoInteractions(support,chats);}
    @Test void checkedInMemberPreventsReschedule(){when(db.count(contains("checkin_time is not null"),anyMap())).thenReturn(1L);assertEquals(409,reject(body()).getCode());}
    @Test void alreadyStartedPreventsReschedule(){activity.put("startTime",time(-1000));assertEquals(409,reject(body()).getCode());}
    @Test void unsafeContentDoesNotChangeExistingActivity(){when(guard.check(any())).thenReturn("review");assertEquals(409,reject(body()).getCode());}
    @Test void cannotInjectFeeCapacityOwnershipOrState(){for(String key:Arrays.asList("feeYuan","capacity","status","appUserId","source")){Map<String,Object>b=body();b.put(key,1);assertEquals(422,reject(b).getCode());}}
    @Test void staleScheduleOrMissingTokenIsRejected(){Map<String,Object>b=body();activity.put("place","另一设备的新地点");assertEquals(409,reject(b).getCode());b=body();b.remove("editToken");assertEquals(409,reject(b).getCode());}
    @Test void invalidDatesAndNewPastDeadlineRejected(){for(String[] change:new String[][]{{"endTime",time(1000)},{"startTime",time(1000)},{"deadline",time(-2000)},{"deadline",time(180000000)},{"startTime","2026-02-30 12:00"}}){Map<String,Object>b=body();b.put(change[0],change[1]);assertEquals(422,reject(b).getCode());}}
    @Test void nearStartAndPastUnchangedDeadlineMayBeKept(){activity.put("startTime",time(1800000));service.editByUser(7L,6L,body());assertEquals(StaffSupport.parse(activity.get("startTime")),written().get("start_time"));}
    @Test void mapOnlyChangeNotifiesAndKeepsCoordinates(){Map<String,Object>b=body();b.put("place",activity.get("place"));b.put("meetLat",25.5);b.put("meetLng",119.8);service.editByUser(7L,6L,b);assertNotNull(written().get("meet_lat"));verify(chats).systemMessage(eq(6L),anyString());}
    @Test void waitlistProtectsExistingStatusAndFees(){activity.put("signed",0L);activity.put("waitlist",2L);when(guard.initialStatus(any())).thenReturn("pending");service.editByUser(7L,6L,body());assertEquals("paused",written().get("status"));}
    @Test void cancelledOrOtherUsersCannotEdit(){activity.put("appUserId",8L);assertEquals(403,reject(body()).getCode());activity.put("appUserId",7L);activity.put("status","cancelled");assertEquals(409,reject(body()).getCode());}
    @Test void legacyFullEditCannotBypassSignedGuardsEvenPending(){activity.put("status","pending");Map<String,Object>b=body();b.remove("detailsOnly");assertEquals(409,reject(b).getCode());}
    @Test void qrCheckinLocksAndUsesCurrentSchedule(){activity.put("checkinToken","valid");String result=service.checkinByToken(8L,6L,"valid");assertTrue(result.contains("开放"));verify(db).one(contains("for update"),anyMap());verify(db,never()).update(contains("set checkin_time"),anyMap());}
    Map<String,Object> fullBody(){Map<String,Object>b=body();b.remove("detailsOnly");b.put("capacity",10);b.put("feeKind","free");b.put("feeYuan","0");return b;}
    @Test void fullUserEditSupportsSameTwoThousandCharacterIntroAsContentEdit(){
        activity.put("signed",0L);Map<String,Object>b=fullBody();String intro=String.join("",Collections.nCopies(2000,"介"));b.put("intro",intro);
        service.editByUser(7L,6L,b);assertEquals(intro,written().get("intro"));
    }
    @Test void fullEditCannotRescheduleStartedActivityWithoutRemainingSignups(){
        activity.put("signed",0L);activity.put("startTime",time(-60000));Map<String,Object>b=fullBody();b.put("startTime",time(86400000));assertEquals(409,reject(b).getCode());
    }
    @Test void fullEditCannotBypassHistoricalCheckinAfterSignupCancellation(){
        activity.put("signed",0L);when(db.count(contains("checkin_time is not null"),anyMap())).thenReturn(1L);assertEquals(409,reject(fullBody()).getCode());
    }
}
