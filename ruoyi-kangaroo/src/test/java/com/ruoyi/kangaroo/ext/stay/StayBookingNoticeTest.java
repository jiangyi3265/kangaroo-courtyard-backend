package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.*;
import com.ruoyi.kangaroo.ext.pay.KgPayService;

class StayBookingNoticeTest {
    Map<String,Object> config(String title,String content) {return p("version",1,"title",title,"content",content);}
    @Test void defaultsComeFromTheSameBackendTextAndVersionIsContentBased() {
        Map<String,Object> notice=StayBookingNotice.snapshot(null);
        assertEquals(KgStayBrowseService.AGREEMENT_TITLE,notice.get("title"));
        assertEquals(KgStayBrowseService.AGREEMENT_SUMMARY,notice.get("summary"));
        assertTrue(notice.get("version").toString().matches("notice-[0-9a-f]{64}"));
        assertEquals(notice,StayBookingNotice.snapshot(StayBookingNotice.defaultConfig()));
        assertEquals(notice,StayBookingNotice.snapshot(""));
    }
    @Test void titleAndContentEditsBothChangeVersionAndNormalizeLineEndings() {
        Map<String,Object> a=StayBookingNotice.snapshot(config("须知","第一条\n第二条"));
        assertEquals(a,StayBookingNotice.snapshot(config(" 须知 "," 第一条\r\n第二条 ")));
        assertNotEquals(a.get("version"),StayBookingNotice.snapshot(config("新须知","第一条\n第二条")).get("version"));
        assertNotEquals(a.get("version"),StayBookingNotice.snapshot(config("须知","第三条")).get("version"));
    }
    @Test void validatesTypeLengthAndBlankFieldsButPreservesPlainText() {
        for(Object raw:Arrays.asList("null","[]","{}","bad",config("","正文"),config("标题"," \n "),
                config("x".repeat(81),"正文"),config("标题","x".repeat(6001)),config("多\n行","正文"),
                config("标题","bad\u0000text"),p("version",2,"title","标题","content","正文"),p("version",1,"title",1,"content","正文")))
            assertThrows(ServiceException.class,()->StayBookingNotice.parse(raw));
        assertDoesNotThrow(()->StayBookingNotice.parse(config("x".repeat(80),"文".repeat(6000))));
        assertEquals("<script>文本</script>",StayBookingNotice.snapshot(config("须知","<script>文本</script>")).get("summary"));
    }
    @Test void onlyExactDisplayedVersionCanBeAcceptedIncludingAfterResetToDefault() {
        Map<String,Object> custom=StayBookingNotice.snapshot(config("新标题","新的正文"));
        assertDoesNotThrow(()->StayBookingNotice.validateConsent(custom,custom.get("version")));
        for(Object version:Arrays.asList("","2026-09","2026-10","notice-old",1))
            assertEquals(409,assertThrows(ServiceException.class,()->StayBookingNotice.validateConsent(custom,version)).getCode());
        assertThrows(ServiceException.class,()->StayBookingNotice.validateConsent(custom,null));
        assertThrows(ServiceException.class,()->StayBookingNotice.validateConsent(null,"null"));
        assertThrows(ServiceException.class,()->StayBookingNotice.validateConsent(StayBookingNotice.snapshot(null),custom.get("version")));
    }
    @Test void adminMetadataProvidesDefaultTextAndValidatesOnlyTheUpdatedConfig() {
        ExtEntity e=new KgStayModule(mock(KgJdbc.class)).entities().stream().filter(x->x.key.equals("stay")).findFirst().get();
        ExtField f=e.fields.stream().filter(x->x.name.equals("bookingNoticeConfig")).findFirst().get();
        assertTrue(f.editable);assertEquals(StayBookingNotice.defaultConfig(),f.meta().get("editorDefault"));
        Map<String,Object> cols=p("booking_notice_config",JSON.toJSONString(config(" 须知 ","第一条\r\n第二条")));
        e.validator.accept(cols,false);
        assertEquals(config("须知","第一条\n第二条"),StayBookingNotice.parse(cols.get("booking_notice_config")));
        assertThrows(ServiceException.class,()->e.validator.accept(p("booking_notice_config","{}"),false));
        Map<String,Object> off=p("booking_notice_config","");e.validator.accept(off,false);assertNull(off.get("booking_notice_config"));
    }
    class Fixture {
        final KgJdbc db=mock(KgJdbc.class);
        final KgPayService pay=mock(KgPayService.class);
        final KgStayBrowseService browse=spy(new KgStayBrowseService());
        final KgStayOrderService service=new KgStayOrderService();
        final Map<String,Object> property=p("stayId",7L,"storeId",9L,"checkinTime","15:00","stayName","测试小院","bookingNoticeConfig",JSON.toJSONString(config("测试小院预订须知","1. 请带好身份证。\n2. 晚到请提前联系。")));
        final LocalDate in=LocalDate.now().plusDays(5);
        Fixture() {
            ReflectionTestUtils.setField(browse,"db",db);
            when(db.one(contains("from sys_config"),anyMap())).thenReturn(p("configValue","true"));
            doReturn(p("stayId",7L,"roomTypeId",31L,"typeName","双人间")).when(browse).roomType(31L);
            doReturn(property).when(browse).onlineStay(7L);
            doReturn(null).when(browse).activePromo(any(),any());
            doReturn(p("bookable",true,"units",1,"roomFen",70000L,"nightly",Arrays.asList(p("priceFen",10000L))))
                    .when(browse).availability(anyMap(),any(),any(),anyInt(),any());
            ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"browse",browse);ReflectionTestUtils.setField(service,"pay",pay);
            ReflectionTestUtils.setField(service,"activitySelections",mock(KgStayActivitySelection.class));
            when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
            when(db.update(startsWith("update kg_stay_calendar c join"),anyMap())).thenReturn(7);
            when(db.insert(eq("kg_stay_order"),anyMap())).thenReturn(101L);
            when(pay.createForNewBusiness(anyString(),anyLong(),anyLong(),anyLong(),anyLong(),anyString())).thenReturn(p("payNo","P101"));
            when(db.one(startsWith("select order_no, status, pay_fen"),anyMap())).thenReturn(p("orderNo","S101","status","pending_payment","payNo","P101"));
        }
        Map<String,Object> notice() {return StayBookingNotice.snapshot(property.get("bookingNoticeConfig"));}
        Map<String,Object> body() {return p("roomTypeId",31L,"checkIn",in.toString(),"checkOut",in.plusDays(7).toString(),"guestName","测试用户","guestPhone","13800138000","agreementVersion",notice().get("version"));}
    }
    @Test void realQuoteUsesEditableTitleFullContentAndVersion() {
        Fixture f=new Fixture();
        assertEquals(f.notice(),f.browse.quote(31L,f.in.toString(),f.in.plusDays(7).toString(),1,null).get("agreement"));
        f.property.put("bookingNoticeConfig",null);
        assertEquals(StayBookingNotice.snapshot(null),f.browse.quote(31L,f.in.toString(),f.in.plusDays(7).toString(),1,null).get("agreement"));
    }
    @Test void contentChangedAfterQuoteStopsCreationBeforeInventoryOrPayment() {
        Fixture f=new Fixture();Map<String,Object> body=f.body();
        f.property.put("bookingNoticeConfig",JSON.toJSONString(config("新的标题","新的正文")));
        ServiceException e=assertThrows(ServiceException.class,()->f.service.create(1L,body));
        assertEquals(409,e.getCode());assertTrue(e.getMessage().contains("须知已更新"));
        verify(f.db,never()).update(anyString(),anyMap());verify(f.db,never()).insert(anyString(),anyMap());verifyNoInteractions(f.pay);
    }
    @Test void successSnapshotsAcceptedTextAndHistoricalDetailDoesNotReadCurrentNotice() {
        Fixture f=new Fixture();Map<String,Object> original=f.notice();
        f.service.create(1L,f.body());
        ArgumentCaptor<Map> saved=ArgumentCaptor.forClass(Map.class);verify(f.db).insert(eq("kg_stay_order"),saved.capture());
        String snapshot=(String)saved.getValue().get("agreement_snapshot");
        assertEquals(original,JSON.parseObject(snapshot));assertEquals(original.get("version"),saved.getValue().get("agreement_version"));
        f.property.put("bookingNoticeConfig",JSON.toJSONString(config("后来的标题","后来的正文")));
        Map<String,Object> old=p("orderId",101L,"status","pending_payment","agreementVersion",original.get("version"),"agreementSnapshot",snapshot);
        assertEquals(original,f.service.view(old,true).get("agreement"));
        assertEquals(Collections.emptyMap(),f.service.view(p("orderId",1L,"status","closed","agreementVersion","2026-09"),true).get("agreement"));
    }
    @Test void idempotentRetryRecoversOriginalOrderEvenAfterNoticeChanges() {
        Fixture f=new Fixture();Map<String,Object> body=f.body();body.put("idemKey","same-booking");
        f.property.put("bookingNoticeConfig",null);
        when(f.db.one(startsWith("select order_no, status, pay_fen, expire_time, pay_no from kg_stay_order where app_user_id"),anyMap()))
                .thenReturn(p("orderNo","Soriginal","status","pending_payment","payFen",70000));
        assertEquals("Soriginal",f.service.create(1L,body).get("orderNo"));
        verify(f.browse,never()).quote(any(),anyString(),anyString(),anyInt(),any());
        verify(f.db,never()).insert(anyString(),anyMap());verifyNoInteractions(f.pay);
    }
}
