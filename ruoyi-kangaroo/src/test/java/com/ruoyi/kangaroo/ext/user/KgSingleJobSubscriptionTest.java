package com.ruoyi.kangaroo.ext.user;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
class KgSingleJobSubscriptionTest {
 @Test void changedConditionsReuseCurrentSubscriptionUnderAccountLock() {
  KgJdbc db=mock(KgJdbc.class); KgUserHubService s=new KgUserHubService();ReflectionTestUtils.setField(s,"db",db);
  when(db.one(contains("order by (status = '0')"),anyMap())).thenReturn(p("subId",17L));
  assertEquals(17L,s.saveSubscription(8L,null,p("cities",Arrays.asList("平潭"),"jobTypes",Arrays.asList("parttime"),"freq","daily")).get("subId"));
  verify(db,never()).insert(anyString(),anyMap());
  org.mockito.InOrder order=inOrder(db);
  order.verify(db).one(contains("for update"),eq(p("u",8L)));
  order.verify(db).one(contains("order by (status = '0')"),eq(p("u",8L)));
  verify(db).updateRow(eq("kg_job_subscribe"),eq("sub_id"),eq(17L),argThat(x->"parttime".equals(x.get("job_types"))));
  verify(db).update(contains("sub_id <> :s"),eq(p("u",8L,"s",17L)));
 }
 @Test void firstSubscriptionCreatesOneRow() {
  KgJdbc db=mock(KgJdbc.class);KgUserHubService s=new KgUserHubService();ReflectionTestUtils.setField(s,"db",db);
  when(db.insert(eq("kg_job_subscribe"),anyMap())).thenReturn(19L);
  assertEquals(19L,s.saveSubscription(8L,null,p("jobTypes",Arrays.asList("volunteer"))).get("subId"));
  verify(db,times(1)).insert(eq("kg_job_subscribe"),anyMap());
 }
 @Test void storeFollowDoesNotReplaceJobSubscription() {
  KgJdbc db=mock(KgJdbc.class);KgUserHubService s=new KgUserHubService();ReflectionTestUtils.setField(s,"db",db);
  when(db.one(contains("from kg_store"),anyMap())).thenReturn(p("storeId",100L));
  when(db.insert(eq("kg_job_subscribe"),anyMap())).thenReturn(20L);
  s.saveSubscription(8L,null,p("storeId",100L));
  verify(db,never()).one(contains("order by (status = '0')"),anyMap());
  verify(db,never()).update(contains("sub_id <> :s"),anyMap());
 }
}
