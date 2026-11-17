package com.ruoyi.kangaroo.ext.community;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppStaffActivityController;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.WxMiniAppClient;

class KgActivityCodeAccessTest {
    private Map<String,Object> activity(String source) { return KgJdbc.p("activityId",12L,"appUserId",7L,"source",source,"checkinToken","0123456789ab"); }
    @Test void onlyUserActivityOwnerCanRequestManagementCode() {
        KgJdbc db=mock(KgJdbc.class);WxMiniAppClient wx=mock(WxMiniAppClient.class);KgActivityService s=new KgActivityService();
        ReflectionTestUtils.setField(s,"db",db);ReflectionTestUtils.setField(s,"wxMiniApp",wx);
        when(db.must(anyString(),anyMap(),anyString())).thenReturn(activity("user"));
        assertEquals(403,assertThrows(ServiceException.class,()->s.manageAsUser(8L,12L)).getCode());
        when(db.must(anyString(),anyMap(),anyString())).thenReturn(activity("merchant"));
        assertEquals(403,assertThrows(ServiceException.class,()->s.manageAsUser(7L,12L)).getCode());
        verifyNoInteractions(wx);
    }
    @Test void authorizedUserGetsStructuredMockStateWithNoInventedImage() {
        KgActivityService s=spy(new KgActivityService());WxMiniAppClient wx=mock(WxMiniAppClient.class);
        ReflectionTestUtils.setField(s,"wxMiniApp",wx);doReturn(activity("user")).when(s).ownUserActivity(7L,12L);
        doReturn(new LinkedHashMap<String,Object>()).when(s).manageView(anyMap());
        when(wx.activityCode(12L,"0123456789ab")).thenReturn(KgJdbc.p("state","mock","image","","message","本地联调"));
        Map<String,Object> result=s.manageAsUser(7L,12L);assertEquals("",result.get("checkinQr"));
        assertEquals("mock",((Map<?,?>)result.get("checkinCodeInfo")).get("state"));
        verify(wx).activityCode(12L,"0123456789ab");
    }
    @Test void crossStoreControllerAuthorizationRunsBeforeCodeAttachment() {
        AppStaffActivityController c=new AppStaffActivityController();KgJdbc db=mock(KgJdbc.class);StaffSupport support=mock(StaffSupport.class);KgActivityService activities=mock(KgActivityService.class);
        ReflectionTestUtils.setField(c,"db",db);ReflectionTestUtils.setField(c,"support",support);ReflectionTestUtils.setField(c,"activities",activities);
        when(db.one(anyString(),anyMap())).thenReturn(KgJdbc.p("storeId",99L));when(support.ctx(99L)).thenThrow(new ServiceException("无权",403));
        assertEquals(403,assertThrows(ServiceException.class,()->c.detail(12L)).getCode());verifyNoInteractions(activities);
    }
    @Test void nonmemberGroupCannotTriggerOfficialCodeRequest() {
        KgChatService s=new KgChatService();KgJdbc db=mock(KgJdbc.class);WxMiniAppClient wx=mock(WxMiniAppClient.class);
        ReflectionTestUtils.setField(s,"db",db);ReflectionTestUtils.setField(s,"wxMiniApp",wx);
        when(db.one(anyString(),anyMap())).thenReturn(null);
        assertEquals(403,assertThrows(ServiceException.class,()->s.groupDetail(8L,99L)).getCode());verifyNoInteractions(wx);
    }

    private void missingTokenIsRepaired(String initial) {
        KgActivityService service=new KgActivityService();KgJdbc db=mock(KgJdbc.class);WxMiniAppClient wx=mock(WxMiniAppClient.class);
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"wxMiniApp",wx);
        java.util.concurrent.atomic.AtomicReference<String> stored=new java.util.concurrent.atomic.AtomicReference<>(initial);
        when(db.update(anyString(),anyMap())).thenAnswer(call->{
            String sql=call.getArgument(0);Map<String,Object> params=call.getArgument(1);
            boolean eligible=stored.get()==null || (stored.get().isEmpty() && sql.contains("checkin_token = ''"));
            if(!eligible)return 0;
            stored.set((String)params.get("t"));return 1;
        });
        when(db.one(anyString(),anyMap())).thenAnswer(call->KgJdbc.p("checkinToken",stored.get()));
        when(wx.activityCode(eq(12L),anyString())).thenAnswer(call->{
            String token=call.getArgument(1);assertTrue(token.matches("[0-9a-f]{16}"),"Persisted token must be full UUID-derived16 hex");
            return KgJdbc.p("image","","state","mock");
        });
        Map<String,Object> row=activity("user");row.put("checkinToken",initial);
        service.attachCheckinCode(new LinkedHashMap<>(),row);
        String persisted=stored.get();assertNotNull(persisted);assertTrue(persisted.matches("[0-9a-f]{16}"));
        row.put("checkinToken",persisted);service.attachCheckinCode(new LinkedHashMap<>(),row);
        assertEquals(persisted,stored.get());verify(db,times(1)).update(anyString(),anyMap());
        verify(wx,times(2)).activityCode(12L,persisted);
    }
    @Test void nullLegacyTokenIsRepairedAsStableSixteenHex() { missingTokenIsRepaired(null); }
    @Test void blankLegacyTokenIsRepairedAsStableSixteenHex() { missingTokenIsRepaired(""); }
    @Test void lostCompareAndSetUsesPersistedConcurrentWinner() {
        KgActivityService service=new KgActivityService();KgJdbc db=mock(KgJdbc.class);WxMiniAppClient wx=mock(WxMiniAppClient.class);
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"wxMiniApp",wx);
        String winner="fedcba9876543210";when(db.update(anyString(),anyMap())).thenReturn(0);
        when(db.one(anyString(),anyMap())).thenReturn(KgJdbc.p("checkinToken",winner));
        when(wx.activityCode(12L,winner)).thenReturn(KgJdbc.p("image","","state","mock"));
        Map<String,Object> row=activity("user");row.put("checkinToken","");service.attachCheckinCode(new LinkedHashMap<>(),row);
        verify(wx).activityCode(12L,winner);
    }
    @Test void existingTwelveHexTokenIsKeptWithoutAnyWrite() {
        KgActivityService service=new KgActivityService();KgJdbc db=mock(KgJdbc.class);WxMiniAppClient wx=mock(WxMiniAppClient.class);
        ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"wxMiniApp",wx);
        when(wx.activityCode(12L,"0123456789ab")).thenReturn(KgJdbc.p("image","","state","mock"));
        service.attachCheckinCode(new LinkedHashMap<>(),activity("user"));verifyNoInteractions(db);
        verify(wx).activityCode(12L,"0123456789ab");
    }
}
