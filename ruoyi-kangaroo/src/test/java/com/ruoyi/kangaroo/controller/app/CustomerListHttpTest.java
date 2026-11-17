package com.ruoyi.kangaroo.controller.app;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.kangaroo.ext.werewolf.WerewolfService;
import com.ruoyi.kangaroo.ext.staff.KgOnlineRecipient;
import com.ruoyi.kangaroo.security.*;

/** HTTP routing and authorization tests complement engine/DB tests; no external writes. */
class CustomerListHttpTest {
 MockMvc mvc;WerewolfService games;KgOnlineRecipient recipients;AppLoginUser user;
 @BeforeEach void setup(){
  user=new AppLoginUser();user.setUserId(1L);user.setNickname("测试玩家");
  games=mock(WerewolfService.class);recipients=mock(KgOnlineRecipient.class);
  AppRateLimiter limiter=mock(AppRateLimiter.class);
  AppWerewolfController wolf=new AppWerewolfController();
  ReflectionTestUtils.setField(wolf,"games",games);ReflectionTestUtils.setField(wolf,"limiter",limiter);
  HttpServletRequest request=mock(HttpServletRequest.class);when(request.getParameter("rulesVersion")).thenReturn("2");ReflectionTestUtils.setField(wolf,"request",request);
  when(games.get("123456",1L)).thenReturn(p("playerCount",12,"playMode","online"));
  when(games.command(eq("123456"),same(user),eq("aim"),anyMap())).thenReturn(p("phase","wolves","version",2));
  AppOnlineController online=new AppOnlineController();ReflectionTestUtils.setField(online,"recipients",recipients);ReflectionTestUtils.setField(online,"limiter",limiter);
  when(recipients.save(eq(71L),anyMap())).thenReturn(p("submitted",true));when(recipients.saveQr(eq(71L),eq("测试接单人"),any())).thenReturn(p("submitted",true,"method","qr"));
  AppTokenService tokens=mock(AppTokenService.class);when(tokens.getLoginUser(any())).thenAnswer(i->"fixture".equals(((HttpServletRequest)i.getArgument(0)).getHeader("X-App-Token"))?user:null);
  AppAuthInterceptor auth=new AppAuthInterceptor();ReflectionTestUtils.setField(auth,"appTokenService",tokens);ReflectionTestUtils.setField(auth,"identities",mock(AppIdentityLoader.class));
  mvc=MockMvcBuilders.standaloneSetup(wolf,online).addInterceptors(auth).setControllerAdvice(new GlobalExceptionHandler()).build();
 }
 @AfterEach void clear(){AppUserContext.remove();}
 @Test void authenticatedAimReachesGameServiceWithPhaseAndTarget() throws Exception {
  mvc.perform(post("/app/werewolf/rooms/123456/aim").header("X-App-Token","fixture").param("rulesVersion","2").contentType(MediaType.APPLICATION_JSON).content("{\"target\":3,\"phaseToken\":\"night-1\",\"requestId\":\"aim-1\"}"))
   .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.version").value(2));
  verify(games).command(eq("123456"),same(user),eq("aim"),argThat(b->Integer.valueOf(3).equals(b.get("target"))&&"night-1".equals(b.get("phaseToken"))));
 }
 @Test void anonymousAimIsRejectedBeforeGameReadOrMutation() throws Exception {
  mvc.perform(post("/app/werewolf/rooms/123456/aim").contentType(MediaType.APPLICATION_JSON).content("{}"))
   .andExpect(jsonPath("$.code").value(401));verifyNoInteractions(games);
 }
 @Test void unknownActionRemainsRejected() throws Exception {
  mvc.perform(post("/app/werewolf/rooms/123456/invalid").header("X-App-Token","fixture").contentType(MediaType.APPLICATION_JSON).content("{}"))
   .andExpect(jsonPath("$.code").value(404));verifyNoInteractions(games);
 }
 @Test void recipientJsonAndMultipartReachAuthorizedServices() throws Exception {
  mvc.perform(post("/app/online/assignments/71/recipient").header("X-App-Token","fixture").contentType(MediaType.APPLICATION_JSON).content("{\"realName\":\"测试接单人\",\"method\":\"alipay\",\"account\":\"worker@example.com\"}"))
   .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.submitted").value(true));
  MockMultipartFile f=new MockMultipartFile("file","receipt.png","image/png",new byte[]{1,2,3});
  mvc.perform(multipart("/app/online/assignments/71/recipient-qr").file(f).param("realName","测试接单人").header("X-App-Token","fixture"))
   .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.method").value("qr"));
  verify(recipients).save(eq(71L),argThat(b->"worker@example.com".equals(b.get("account"))));verify(recipients).saveQr(eq(71L),eq("测试接单人"),any());
 }
 @Test void anonymousRecipientWriteIsRejected() throws Exception {
  mvc.perform(post("/app/online/assignments/71/recipient").contentType(MediaType.APPLICATION_JSON).content("{}"))
   .andExpect(jsonPath("$.code").value(401));verifyNoInteractions(recipients);
 }
}
