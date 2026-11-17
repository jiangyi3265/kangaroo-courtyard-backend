package com.ruoyi.kangaroo.ext.user;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.mapper.KgMessageMapper;
import com.ruoyi.kangaroo.domain.KgMessage;

class KgSubscribeVisibilityTest {
 @AfterEach void cleanup(){AppUserContext.remove();}
 @Test void backgroundDispatchChecksEachFreshRoleAndRestoresTheCallingContext(){
  AppIdentityLoader identities=mock(AppIdentityLoader.class);KgMessageMapper messages=mock(KgMessageMapper.class);
  StaffGrantView manager=new StaffGrantView();manager.setActive(true);manager.setStoreId(8L);manager.setRoleCode("manager");
  StaffGrantView keeper=new StaffGrantView();keeper.setActive(true);keeper.setStoreId(9L);keeper.setRoleCode("keeper");
  doAnswer(x->{((AppLoginUser)x.getArgument(0)).setStaffGrants(Arrays.asList(manager,keeper));return null;}).when(identities).reload(any());
  when(messages.selectVisibleMessage(77L,1L,"staff")).thenAnswer(x->{
   assertEquals(1L,AppUserContext.getUserId());
   return "keeper".equals(AppUserContext.messageRole())&&AppUserContext.messageStores().equals(Collections.singletonList(9L))?new KgMessage():null;
  });
  AppLoginUser caller=new AppLoginUser();caller.setUserId(99L);AppUserContext.set(caller);AppUserContext.messageScope(Collections.singletonList(100L),"merchant");
  assertEquals("store:9:keeper",new KgSubscribeVisibility(identities,messages).authorizedScope(1L,77L,"staff"));
  assertSame(caller,AppUserContext.get());assertEquals("merchant",AppUserContext.messageRole());assertEquals(Collections.singletonList(100L),AppUserContext.messageStores());
 }
 @Test void RevokedStaffCannotReceiveAnOldStaffNotification(){
  AppIdentityLoader identities=mock(AppIdentityLoader.class);KgMessageMapper messages=mock(KgMessageMapper.class);
  doAnswer(x->{((AppLoginUser)x.getArgument(0)).setStaffGrants(Collections.emptyList());return null;}).when(identities).reload(any());
  assertNull(new KgSubscribeVisibility(identities,messages).authorizedScope(1L,77L,"staff"));verifyNoInteractions(messages);assertNull(AppUserContext.get());
 }
 @Test void personalModeCannotBorrowStaffScope(){
  AppIdentityLoader identities=mock(AppIdentityLoader.class);KgMessageMapper messages=mock(KgMessageMapper.class);
  when(messages.selectVisibleMessage(77L,1L,"user")).thenAnswer(x->{assertEquals("user",AppUserContext.messageRole());assertNull(AppUserContext.messageStores());return new KgMessage();});
  assertEquals("user",new KgSubscribeVisibility(identities,messages).authorizedScope(1L,77L,"user"));assertNull(AppUserContext.get());
 }
 @Test void serviceSubscriptionReturnsTheSameCityIdentityAsTheInbox(){
  AppIdentityLoader identities=mock(AppIdentityLoader.class);KgMessageMapper messages=mock(KgMessageMapper.class);
  StaffGrantView first=new StaffGrantView();first.setActive(true);first.setStoreId(8L);first.setRoleCode("merchant_service");first.setCity("平潭");
  StaffGrantView second=new StaffGrantView();second.setActive(true);second.setStoreId(9L);second.setRoleCode("merchant_service");second.setCity("平潭");
  StaffGrantView other=new StaffGrantView();other.setActive(true);other.setStoreId(10L);other.setRoleCode("merchant_service");other.setCity("丽江");
  doAnswer(x->{((AppLoginUser)x.getArgument(0)).setStaffGrants(Arrays.asList(first,second,other));return null;}).when(identities).reload(any());
  when(messages.selectVisibleMessage(77L,1L,"staff")).thenAnswer(x->{
   assertEquals("merchant_service",AppUserContext.messageRole());
   assertEquals(Arrays.asList(8L,9L),AppUserContext.messageStores());return new KgMessage();
  });
  assertEquals("service-city:%E5%B9%B3%E6%BD%AD",new KgSubscribeVisibility(identities,messages).authorizedScope(1L,77L,"staff"));
  assertNull(AppUserContext.get());assertNull(AppUserContext.messageStores());
 }
}
