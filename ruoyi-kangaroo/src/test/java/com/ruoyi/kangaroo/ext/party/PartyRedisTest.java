package com.ruoyi.kangaroo.ext.party;
import java.util.*;import java.util.concurrent.*;import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;import org.springframework.data.redis.core.StringRedisTemplate;
import com.ruoyi.kangaroo.security.AppLoginUser;import com.ruoyi.common.exception.ServiceException;import static com.ruoyi.kangaroo.ext.KgJdbc.p;
class PartyRedisTest {
 static PartyService service;static LettuceConnectionFactory factory;static StringRedisTemplate redis;
 @BeforeAll static void init(){int port=Integer.parseInt(System.getenv("KG_PARTY_TEST_PORT"));assertTrue(port>30000&&port<50000);factory=new LettuceConnectionFactory("127.0.0.1",port);factory.afterPropertiesSet();redis=new StringRedisTemplate(factory);service=new PartyService();ReflectionTestUtils.setField(service,"redis",redis);}
 @AfterAll static void close(){if(factory!=null)factory.destroy();}
 AppLoginUser u(long id){AppLoginUser u=new AppLoginUser();u.setUserId(id);u.setNickname("测试"+id);return u;}
 Map<String,Object> action(String code,long uid,String action,String token,String request){return service.command(code,u(uid),action,p("phaseToken",token,"requestId",request));}
 @Test void concurrentRollRetryIsOneServerOutcome()throws Exception{
  Map<String,Object>r=service.create(u(101),"dice","create-101-test");String code=(String)r.get("code");assertEquals(code,service.create(u(101),"dice","create-101-test").get("code"));
  assertThrows(ServiceException.class,()->service.create(u(101),"dice","create-101-again"));
  r=action(code,102,"join","","join-102-test");r=action(code,101,"start",(String)r.get("phaseToken"),"start-101-test");final String token=(String)r.get("phaseToken");
  ExecutorService pool=Executors.newFixedThreadPool(8);try{List<Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<12;i++)futures.add(pool.submit(()->action(code,101,"roll",token,"same-roll-test")));for(Future<Map<String,Object>>f:futures)assertTrue((Boolean)f.get().get("rolled"));}finally{pool.shutdownNow();}
  Map<String,Object>finalRoom=service.get(code,102L);assertEquals(2,((List<?>)finalRoom.get("events")).size());assertEquals(1,((int[])finalRoom.get("dice")).length);
  assertThrows(ServiceException.class,()->action(code,101,"roll",token,"different-roll-test"));
  assertNull(service.get(code,null).get("me"));
  action(code,101,"leave",token,"leave-101-test");assertNull(service.mine(101).get("room"));assertNotNull(service.create(u(101),"monopoly","new-room-after-leave"));
 }
 @Test void onePlayerCannotJoinTwoRooms(){Map<String,Object>a=service.create(u(201),"dice","create-201-test"),b=service.create(u(202),"dice","create-202-test");assertThrows(ServiceException.class,()->action((String)a.get("code"),202,"join","","join-other-room"));assertEquals(b.get("code"),((Map<?,?>)service.mine(202).get("room")).get("code"));}
 @Test void expiredRoomPointerDoesNotBlockJoiningFriends(){
  Map<String,Object>a=service.create(u(301),"dice","create-301-test"),b=service.create(u(302),"dice","create-302-test");
  redis.delete("kg:party:room:"+a.get("code"));assertNull(service.mine(301).get("room"));
  Map<String,Object>joined=action((String)b.get("code"),301,"join","","join-after-expiry");
  assertEquals(2,((List)joined.get("players")).size());
  assertEquals(b.get("code"),((Map)service.mine(301).get("room")).get("code"));
 }
}
