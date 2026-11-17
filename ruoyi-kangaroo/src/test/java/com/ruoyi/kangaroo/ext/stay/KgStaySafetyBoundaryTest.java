package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.pay.KgPayService;

/** Real service source; JDBC and resources Mock. No real lock/SQL/payment proof. */
class KgStaySafetyBoundaryTest {
 final KgJdbc db=mock(KgJdbc.class);
 final KgStayBrowseService browse=mock(KgStayBrowseService.class);
 final KgPayService pay=mock(KgPayService.class);
 final StaffSupport support=mock(StaffSupport.class);
 final KgStayOrderService service=new KgStayOrderService();
 final AtomicInteger occupied=new AtomicInteger(1);
 Map<String,Object> order;
 @BeforeEach void setup(){
  ReflectionTestUtils.setField(service,"db",db);ReflectionTestUtils.setField(service,"browse",browse);
  ReflectionTestUtils.setField(service,"pay",pay);ReflectionTestUtils.setField(service,"support",support);
  when(db.one(contains("from sys_config where config_key"),anyMap())).thenReturn(p("configValue","true"));
  when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
  // KgJdbc.one returns null when no row exists; Mockito's default empty Map is not that contract.
  when(db.one(startsWith("select order_no"),anyMap())).thenReturn(null);
  when(db.claimIdem(anyString(),eq("stay_order"))).thenReturn(true);
  order=p("orderId",11L,"orderNo","Sfixture","appUserId",7L,"storeId",9L,"status","refund_pending","payFen",5815L,"refundFen",5815L,
      "roomTypeId",13L,"units",1L,"checkIn","2030-02-01","checkOut","2030-02-03","payNo","Pfixture");
  when(db.one(startsWith("select * from kg_stay_order where order_id"),anyMap())).thenReturn(order);
  when(db.must(startsWith("select * from kg_stay_order where order_no"),anyMap(),anyString())).thenReturn(order);
  when(db.update(startsWith("update kg_stay_order set status = 'cancelled'"),anyMap())).thenAnswer(i->{if(!Arrays.asList("confirmed","refund_pending").contains(order.get("status")))return 0;order.put("status","cancelled");order.put("refundFen",((Map<?,?>)i.getArgument(1)).get("t"));return 1;});
  when(db.update(startsWith("update kg_stay_calendar set occupied"),anyMap())).thenAnswer(i->{occupied.updateAndGet(x->Math.max(0,x-1));return 2;});
  when(db.update(startsWith("update kg_stay_order set status = 'confirmed'"),anyMap())).thenAnswer(i->{order.put("status","confirmed");order.put("refundFen",0L);return 1;});
 }
 Map<String,Object> body(){return p("roomTypeId",13L,"checkIn","2030-02-01","checkOut","2030-02-03","guestName","测试用户","guestPhone","13800138000","agreementVersion",KgStayBrowseService.AGREEMENT_VERSION,"idemKey","candidate-owned-key");}
 Map<String,Object> refund(){return p("bizId",11L,"status","refunded","refundAmount",5815L);}
 void noWrites(){verify(db,never()).update(anyString(),anyMap());verify(db,never()).insert(anyString(),anyMap());verify(db,never()).claimIdem(anyString(),anyString());verifyNoInteractions(pay,support);}
 void assertInactive(Map<String,Object> row){when(db.one(contains("from kg_app_user"),anyMap())).thenReturn(row);ServiceException e=assertThrows(ServiceException.class,()->service.create(7L,body()));assertEquals(409,e.getCode());assertEquals("账号已注销或不可用",e.getMessage());noWrites();verifyNoInteractions(browse);}
 @Test void missingAccountCannotCreatePersonalOrder(){assertInactive(null);}
 @Test void deletedAccountCannotCreatePersonalOrder(){assertInactive(p("status","0","delFlag","2"));}
 @Test void disabledAccountCannotCreatePersonalOrder(){assertInactive(p("status","1","delFlag","0"));}
 @Test void activeAccountLockPrecedesIdempotentReplay(){
  when(db.one(startsWith("select order_no"),anyMap())).thenReturn(p("orderNo","Srepeat","status","pending_payment","payFen",5815L));
  assertEquals("Srepeat",service.create(7L,body()).get("orderNo"));InOrder seq=inOrder(db);
  seq.verify(db).one(contains("from kg_app_user where user_id=:u for update"),eq(p("u",7L)));
  seq.verify(db).one(startsWith("select order_no"),anyMap());verify(db,never()).count(anyString(),anyMap());noWrites();
 }
 @Test void quotaReadIsAfterAccountAndSameUserIdemLock(){
  when(db.count(contains("pending_payment"),anyMap())).thenReturn(3L);
  assertEquals("你有 3 张待支付的订单，请先支付或取消",assertThrows(ServiceException.class,()->service.create(7L,body())).getMessage());
  InOrder seq=inOrder(db);seq.verify(db).one(contains("from kg_app_user"),eq(p("u",7L)));
  seq.verify(db).claimIdem(eq("stay:7:candidate-owned-key"),eq("stay_order"));seq.verify(db).count(contains("pending_payment"),eq(p("u",7L)));
  verifyNoInteractions(browse,pay);verify(db,never()).insert(anyString(),anyMap());
 }
 @Test void accountLockPrecedesRoomInventoryLock(){
  when(browse.quote(any(),anyString(),anyString(),anyInt(),any())).thenReturn(p("bookable",false,"reason","fixture"));
  assertThrows(ServiceException.class,()->service.create(7L,body()));
  InOrder seq=inOrder(db);seq.verify(db).one(contains("from kg_app_user"),anyMap());
  seq.verify(db).must(contains("from kg_stay_room_type"),anyMap(),anyString());
 }
 @Test void closedLatePaidFullRefundPreservesOtherBookingInventory(){
  order.put("closeTime","2029-01-01 00:00:00");service.onRefunded(refund(),5815L);
  assertEquals("cancelled",order.get("status"));assertEquals(5815L,order.get("refundFen"));assertEquals(1,occupied.get());
  verify(db,never()).update(startsWith("update kg_stay_calendar set occupied"),anyMap());
  verify(db,never()).update(startsWith("update kg_stay_promo"),anyMap());
 }
 @Test void ordinaryFullRefundReleasesOnce(){
  service.onRefunded(refund(),5815L);assertEquals("cancelled",order.get("status"));assertEquals(0,occupied.get());
  service.onRefunded(refund(),5815L);assertEquals(0,occupied.get());verify(db,times(1)).update(startsWith("update kg_stay_calendar set occupied"),anyMap());
 }
 @Test void approvedRefundCallbackDoesNotReleaseTwice(){
  order.put("status","refunding");service.onRefunded(refund(),5815L);assertEquals(1,occupied.get());
  verify(db,never()).update(startsWith("update kg_stay_calendar set occupied"),anyMap());
 }
 @Test void partialRefundDoesNotReleaseInventory(){
  order.put("status","confirmed");service.onRefunded(p("bizId",11L,"status","paid","refundAmount",1234L),1234L);
  assertEquals("confirmed",order.get("status"));assertEquals(1,occupied.get());verify(db,never()).update(startsWith("update kg_stay_calendar set occupied"),anyMap());
 }
 @Test void closedLatePaidRefundCannotBeRejectedIntoUnreservedStay(){
  order.put("closeTime","2029-01-01 00:00:00");Map<String,Object> before=new LinkedHashMap<>(order);
  ServiceException e=assertThrows(ServiceException.class,()->service.rejectRefund("Sfixture","继续退款","manager"));
  assertEquals(409,e.getCode());assertEquals(before,order);assertEquals(1,occupied.get());noWrites();
 }
 @Test void normalRefundRejectionRetainsOccupiedBooking(){
  assertEquals("已驳回",service.rejectRefund("Sfixture","保留入住","manager"));assertEquals("confirmed",order.get("status"));assertEquals(0L,order.get("refundFen"));assertEquals(1,occupied.get());
  verify(db,never()).update(startsWith("update kg_stay_calendar"),anyMap());
 }
 @Test void refundRejectionChecksFreshLockedStateBeforeWrite(){
  service.rejectRefund("Sfixture","保留入住","manager");InOrder seq=inOrder(db);
  seq.verify(db).must(endsWith("for update"),eq(p("n","Sfixture")),eq("订单不存在"));
  seq.verify(db).update(startsWith("update kg_stay_order set status = 'confirmed'"),anyMap());
 }
 @Test void createAndRejectKeepOneBusinessTransaction()throws Exception{
  for(String method:new String[]{"create","rejectRefund"}){
   Transactional t=("create".equals(method)?KgStayOrderService.class.getMethod(method,Long.class,Map.class):KgStayOrderService.class.getMethod(method,String.class,String.class,String.class)).getAnnotation(Transactional.class);
   assertNotNull(t);assertTrue(Arrays.asList(t.rollbackFor()).contains(Exception.class));
  }
 }
}
