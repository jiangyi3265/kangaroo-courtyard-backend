package com.ruoyi.kangaroo.ext.party;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.security.AppLoginUser;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated Redis coverage for shared-room reads and re-entry, without production writes. */
class PartyShareRedisTest {
 static PartyService service;
 static LettuceConnectionFactory factory;
 static StringRedisTemplate redis;
 @BeforeAll static void init() {
  int port=Integer.parseInt(System.getenv("KG_PARTY_TEST_PORT"));
  assertTrue(port>30000&&port<50000);
  factory=new LettuceConnectionFactory("127.0.0.1",port);factory.afterPropertiesSet();
  redis=new StringRedisTemplate(factory);service=new PartyService();ReflectionTestUtils.setField(service,"redis",redis);
 }
 @AfterAll static void close(){if(factory!=null)factory.destroy();}
 AppLoginUser user(long id){AppLoginUser u=new AppLoginUser();u.setUserId(id);u.setNickname("分享测试"+id);return u;}
 Map<String,Object> action(String code,long uid,String action,String token,String req){
  return service.command(code,user(uid),action,p("phaseToken",token,"requestId",req));
 }
 String room(long uid,String mode){return (String)service.create(user(uid),mode,"share-create-"+uid).get("code");}
 @Test void anonymousSharedRoomLoadsWithoutJoiningAndDoesNotExposeAccountIds(){
  String code=room(10001,"monopoly");String before=redis.opsForValue().get("kg:party:room:"+code);
  Map<String,Object> r=service.get(code,null);
  assertEquals("waiting",r.get("phase"));assertEquals("monopoly",r.get("mode"));assertNull(r.get("me"));
  assertEquals(1,((List<?>)r.get("players")).size());assertEquals(20,((List<?>)r.get("board")).size());
  assertFalse((Boolean)r.get("canRoll"));assertFalse((Boolean)r.get("canBuy"));
  assertEquals(Collections.emptyList(),r.get("events"));
  assertFalse(((Map<?,?>)((List<?>)r.get("players")).get(0)).containsKey("id"));
  assertEquals(before,redis.opsForValue().get("kg:party:room:"+code));
 }
 @Test void signedInFriendCanJoinSharedRoomAndRepeatedEntryNeverDuplicatesSeat(){
  String code=room(10002,"monopoly");
  assertNull(service.get(code,10003L).get("me"));
  Map<String,Object> joined=action(code,10003,"join","","shared-join-10003");
  assertEquals(2,((Map<?,?>)joined.get("me")).get("seat"));
  assertEquals(false,((Map<?,?>)joined.get("me")).get("host"));
  action(code,10003,"join","","shared-join-10003");
  action(code,10003,"join","","shared-reenter-10003");
  assertEquals(2,((List<?>)service.get(code,10002L).get("players")).size());
  assertEquals(code,((Map<?,?>)service.mine(10003).get("room")).get("code"));
 }
 @Test void startedGameAllowsMemberReconnectButRejectsNewSeatAndOutsideCommands(){
  String code=room(10004,"monopoly");Map<String,Object> r=action(code,10005,"join","","join-10005-test");
  r=action(code,10004,"start",(String)r.get("phaseToken"),"start-10004-test");
  final String token=(String)r.get("phaseToken");
  Map<String,Object> rejoined=action(code,10005,"join","","rejoin-10005-test");
  assertEquals("playing",rejoined.get("phase"));assertNotNull(rejoined.get("me"));
  ServiceException noSeat=assertThrows(ServiceException.class,()->action(code,10006,"join","","join-late-10006"));
  assertEquals(409,noSeat.getCode());
  ServiceException forbidden=assertThrows(ServiceException.class,()->action(code,10006,"roll",token,"outside-roll-test"));
  assertEquals(403,forbidden.getCode());
  Map<String,Object> rolled=action(code,10004,"roll",token,"roll-10004-test");
  Map<String,Object> other=service.get(code,10005L);
  assertArrayEquals((int[])rolled.get("dice"),(int[])other.get("dice"));
  assertEquals(rolled.get("version"),other.get("version"));
 }
 @Test void invalidExpiredAndClosedInvitationHaveExplicitOutcomes(){
  assertEquals(422,assertThrows(ServiceException.class,()->service.get("abc",null)).getCode());
  String code=room(10007,"monopoly");
  action(code,10007,"leave","","leave-10007-test");
  assertEquals("closed",service.get(code,null).get("phase"));
  assertEquals(409,assertThrows(ServiceException.class,()->action(code,10008,"join","","join-closed-10008")).getCode());
  redis.delete("kg:party:room:"+code);
  assertEquals(404,assertThrows(ServiceException.class,()->service.get(code,null)).getCode());
  assertNull(service.mine(10007).get("room"));
 }
 @Test void ownedPropertiesAndPlayersSurviveRedisJsonRoundTrip()throws Exception{
  String code=room(10009,"monopoly");
  action(code,10010,"join","","join-10010-test");
  ObjectMapper json=new ObjectMapper();String key="kg:party:room:"+code;
  PartyRoom stored=json.readValue(redis.opsForValue().get(key),PartyRoom.class);
  stored.owners.put(1,10009L);stored.players.get(0).money=1380;
  redis.opsForValue().set(key,json.writeValueAsString(stored));
  Map<String,Object> view=service.get(code,10010L);
  assertEquals(1,((Map<?,?>)((List<?>)view.get("board")).get(1)).get("ownerSeat"));
  assertEquals(1500L,((Map<?,?>)((List<?>)view.get("players")).get(0)).get("assets"));
  assertEquals(2,((Map<?,?>)view.get("me")).get("seat"));
 }
 @Test void diceSharesUseTheSameWorkingReadAndJoinProtocol(){
  String code=room(10011,"dice");
  Map<String,Object> publicRoom=service.get(code,null);assertEquals("dice",publicRoom.get("mode"));
  Map<String,Object> joined=action(code,10012,"join","","join-dice-10012");
  assertEquals(2,((List<?>)joined.get("players")).size());assertNotNull(joined.get("me"));
 }
}
