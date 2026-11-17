package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.LocalDate;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class StayCustomPolicyTest {
    Map<String,Object> rules(int days, String mode, int percent) {
        return p("version",1,"freeDays",days,"lateMode",mode,"latePercent",percent);
    }
    Map<String,Object> snap(int days, String mode, int percent) {
        return StayCustomPolicy.snapshot("moderate",rules(days,mode,percent),false,"15:00");
    }
    @Test void validatesStructuredRulesAndCanonicalizesUnusedPercent() {
        assertNull(StayCustomPolicy.parse(null)); assertNull(StayCustomPolicy.parse(""));
        assertEquals(0,StayCustomPolicy.parse(rules(7,"first_night",99)).get("latePercent"));
        assertEquals(rules(3,"percent",25),StayCustomPolicy.parse(JSON.toJSONString(rules(3,"percent",25))));
        for (Object raw : Arrays.asList("bad", "null", "[]", "{}", rules(-1,"first_night",0), rules(181,"first_night",0),
                rules(7,"unknown",0),rules(7,"percent",-1),rules(7,"percent",101),
                p("version",1,"freeDays",1.5,"lateMode","percent","latePercent",50),
                p("version",2,"freeDays",7,"lateMode","percent","latePercent",50),
                p("version",1,"freeDays","7","lateMode","percent","latePercent",50)))
            assertThrows(ServiceException.class,()->StayCustomPolicy.parse(raw),String.valueOf(raw));
    }
    @Test void sevenDayBoundaryAndArrivalCutoffMatchTheDisplayedPolicy() {
        Map<String,Object> s=snap(7,"first_night",0);
        assertEquals("入住前7天可免费取消，之后扣首晚房费",s.get("title"));
        assertTrue(s.get("summary").toString().contains("不少于7天"));
        assertEquals(70000L,StayCustomPolicy.compute(s,168,70000,10000,70000).get("refundFen"));
        assertEquals(60000L,StayCustomPolicy.compute(s,167.99,70000,10000,70000).get("refundFen"));
        assertEquals(60000L,StayCustomPolicy.compute(s,0.1,70000,10000,70000).get("refundFen"));
        for (double h : new double[]{0,-1}) {
            Map<String,Object> result=StayCustomPolicy.compute(s,h,70000,10000,70000);
            assertEquals(0L,result.get("refundFen")); assertEquals(false,result.get("allowed"));
        }
    }
    @Test void zeroDayAllowsAllPreArrivalCancellation() {
        assertEquals("入住前可免费取消",snap(0,"nonrefundable",0).get("title"));
        assertEquals(70000L,StayCustomPolicy.compute(snap(0,"nonrefundable",0),0.1,70000,10000,70000).get("refundFen"));
    }
    @Test void percentageRoundsToFenAndCapsAtActualPayment() {
        Map<String,Object> s=snap(3,"percent",25);
        assertEquals(1750L,StayCustomPolicy.compute(s,24,7003,1000,6000).get("deductFen"));
        assertEquals(4250L,StayCustomPolicy.compute(s,24,7003,1000,6000).get("refundFen"));
        assertEquals(1000L,StayCustomPolicy.compute(s,24,7003,1000,1000).get("deductFen"));
        assertEquals(6000L,StayCustomPolicy.compute(snap(3,"percent",0),24,7003,1000,6000).get("refundFen"));
        assertEquals(0L,StayCustomPolicy.compute(snap(3,"percent",100),24,7003,1000,6000).get("refundFen"));
        assertEquals(0L,StayCustomPolicy.compute(snap(3,"first_night",0),24,7003,2000,1000).get("refundFen"));
    }
    @Test void lateNonrefundableAndPromoOverrideRemainStrict() {
        assertEquals(0L,StayCustomPolicy.compute(snap(3,"nonrefundable",0),24,7000,1000,7000).get("refundFen"));
        Map<String,Object> s=StayCustomPolicy.snapshot("flexible",rules(0,"percent",0),true,"15:00");
        assertEquals("nonrefundable",s.get("code")); assertFalse(s.containsKey("rules"));
        assertEquals(false,s.get("refundable"));
        assertEquals(0L,StayCustomPolicy.compute(s,500,7000,1000,7000).get("refundFen"));
    }
    @Test void everyLegacySnapshotKeepsItsExactRefundBehavior() {
        for (String code : Arrays.asList("flexible","moderate","strict","nonrefundable"))
            for (double h : new double[]{-1,0,1,24,167,168,200})
                assertEquals(StayPolicy.compute(code,h,7000,1000,6500),StayCustomPolicy.compute(p("code",code),h,7000,1000,6500));
        assertThrows(ServiceException.class,()->StayCustomPolicy.compute(p("code","custom"),48,7000,1000,7000));
    }
    @Test void adminMetadataPersistsCanonicalConfigAndRejectsInvalidFields() {
        ExtEntity e=new KgStayModule(mock(KgJdbc.class)).entities().stream().filter(x->x.key.equals("stay")).findFirst().get();
        assertTrue(e.fields.stream().anyMatch(f->f.name.equals("cancelPolicyConfig") && f.editable));
        Map<String,Object> cols=p("cancel_policy_config",JSON.toJSONString(rules(3,"percent",25)));
        e.validator.accept(cols,false); assertEquals(rules(3,"percent",25),StayCustomPolicy.parse(cols.get("cancel_policy_config")));
        assertThrows(ServiceException.class,()->e.validator.accept(p("cancel_policy_config","{}"),false));
        e.validator.accept(p("cancel_policy_config",null),false);
    }
    KgStayBrowseService browse(Map<String,Object> property) {
        KgStayBrowseService b=spy(new KgStayBrowseService());
        doReturn(p("stayId",7L,"roomTypeId",31L,"typeName","双人间")).when(b).roomType(31L);
        doReturn(property).when(b).onlineStay(7L);
        doReturn(null).when(b).activePromo(any(),any());
        doReturn(p("bookable",true,"units",1,"roomFen",70000L,"nightly",Arrays.asList(p("priceFen",10000L))))
                .when(b).availability(anyMap(),any(),any(),anyInt(),any());
        return b;
    }
    @Test void realQuoteContainsCurrentPolicyAndStableConsentKey() {
        Map<String,Object> property=p("stayId",7L,"checkinTime","15:00","cancelPolicy","moderate","cancelPolicyConfig",JSON.toJSONString(rules(3,"percent",25)));
        KgStayBrowseService b=browse(property); LocalDate in=LocalDate.now().plusDays(5);
        Map<?,?> policy=(Map<?,?>)b.quote(31L,in.toString(),in.plusDays(7).toString(),1,null).get("policy");
        assertEquals("入住前3天可免费取消，之后扣25%房费",policy.get("title"));
        assertTrue(policy.get("copy").toString().contains("15:00 为入住时刻"));
        Object key=policy.get("key"); property.put("cancelPolicyConfig",JSON.toJSONString(rules(5,"first_night",0)));
        Map<?,?> updated=(Map<?,?>)b.quote(31L,in.toString(),in.plusDays(7).toString(),1,null).get("policy");
        assertNotEquals(key,updated.get("key")); assertEquals(rules(3,"percent",25),policy.get("rules"));
    }
    @Test void createBlocksStaleConsentBeforeInventoryAndSnapshotsAcceptedRules() {
        KgJdbc db=mock(KgJdbc.class); KgPayService pay=mock(KgPayService.class); KgStayOrderService service=new KgStayOrderService();
        Map<String,Object> property=p("stayId",7L,"storeId",9L,"checkinTime","15:00","cancelPolicy","moderate","cancelPolicyConfig",JSON.toJSONString(rules(3,"percent",25)));
        KgStayBrowseService b=browse(property);
        ReflectionTestUtils.setField(service,"db",db); ReflectionTestUtils.setField(service,"browse",b); ReflectionTestUtils.setField(service,"pay",pay);
        ReflectionTestUtils.setField(service,"activitySelections",mock(KgStayActivitySelection.class));
        when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
        LocalDate in=LocalDate.now().plusDays(5);
        Map<String,Object> body=p("roomTypeId",31L,"checkIn",in.toString(),"checkOut",in.plusDays(7).toString(),"guestName","测试","guestPhone","13800138000","agreementVersion",StayBookingNotice.snapshot(null).get("version"));
        assertEquals(409,assertThrows(ServiceException.class,()->service.create(1L,body)).getCode());
        body.put("expectedPolicyKey","v1|custom|7|first_night|0|15:00");
        assertEquals(409,assertThrows(ServiceException.class,()->service.create(1L,body)).getCode());
        verify(db,never()).update(anyString(),anyMap()); verify(db,never()).insert(anyString(),anyMap()); verifyNoInteractions(pay);
        body.put("expectedPolicyKey",snap(3,"percent",25).get("key"));
        when(db.update(startsWith("update kg_stay_calendar c join"),anyMap())).thenReturn(7);
        when(db.insert(eq("kg_stay_order"),anyMap())).thenReturn(101L);
        when(pay.createForNewBusiness(anyString(),anyLong(),anyLong(),anyLong(),anyLong(),anyString())).thenReturn(p("payNo","P101"));
        when(db.one(startsWith("select order_no, status, pay_fen"),anyMap())).thenReturn(p("orderNo","S101","status","pending_payment","payNo","P101"));
        service.create(1L,body);
        ArgumentCaptor<Map> saved=ArgumentCaptor.forClass(Map.class); verify(db).insert(eq("kg_stay_order"),saved.capture());
        Map<?,?> snapshot=JSON.parseObject(saved.getValue().get("policy_snapshot").toString());
        assertEquals(rules(3,"percent",25),snapshot.get("rules"));
    }
    @Test void refundUsesHistoricalSnapshotEvenAfterPropertyChanges() {
        KgStayOrderService service=spy(new KgStayOrderService());
        doReturn(new long[]{0,0}).when(service).refundedBefore(anyMap());
        Map<String,Object> original=snap(180,"percent",25);
        Map<String,Object> o=p("policySnapshot",JSON.toJSONString(original),"staySnapshot","{\"checkinTime\":\"15:00\"}",
                "checkIn",LocalDate.now().plusDays(2).toString(),"status","confirmed","roomFen",70000L,"payFen",70000L,"nightly","[{\"priceFen\":10000}]","units",1);
        snap(180,"nonrefundable",0); // later property rule cannot influence an existing order
        assertEquals(52500L,service.preview(o).get("refundFen"));
        assertEquals(true,service.preview(o).get("allowed"));
    }
}
