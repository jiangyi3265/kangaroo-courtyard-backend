package com.ruoyi.kangaroo.security.admin;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.AntPathMatcher;
import java.util.List;
class AdminCityDownloadRoutingTest {
 @Test void signedDownloadsReachTheirOwnAuthorizationWhileAdminBusinessStaysGuarded() {
  InterceptorRegistry r=new InterceptorRegistry();new AdminCityInterceptor(mock(AdminCityScopeService.class)).addInterceptors(r);
  List<Object> items=ReflectionTestUtils.invokeMethod(r,"getInterceptors");assertEquals(1,items.size());
  MappedInterceptor i=(MappedInterceptor)items.get(0);AntPathMatcher matcher=new AntPathMatcher();
  for(String name:new String[]{"private","material","chat","resume","mediation"})assertFalse(i.matches("/kangaroo/file/"+name,matcher));
  for(String path:new String[]{"/kangaroo/store/list","/kangaroo/admin-city-scope/2","/system/user/list","/common/download/resource","/kangaroo/file/admin-export"})assertTrue(i.matches(path,matcher));
 }
}
