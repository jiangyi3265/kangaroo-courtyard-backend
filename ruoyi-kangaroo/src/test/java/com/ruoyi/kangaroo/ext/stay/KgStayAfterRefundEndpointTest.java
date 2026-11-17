package com.ruoyi.kangaroo.ext.stay;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppStayAfterRefundController;
import com.ruoyi.kangaroo.controller.admin.KgStayAfterRefundController;
import com.ruoyi.kangaroo.security.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
class KgStayAfterRefundEndpointTest {
  KgStayAfterRefundService service=mock(KgStayAfterRefundService.class);
  AppStayAfterRefundController controller=new AppStayAfterRefundController(service,mock(AppRateLimiter.class));
  @BeforeEach void login(){AppLoginUser u=new AppLoginUser();u.setUserId(10L);AppUserContext.set(u);}
  @AfterEach void clean(){AppUserContext.remove();}
  @Test void appRequiresAuthentication(){assertNotNull(AppStayAfterRefundController.class.getAnnotation(AppAuth.class));AppUserContext.remove();assertEquals(401,assertThrows(ServiceException.class,()->controller.overview("QA1")).getCode());verifyNoInteractions(service);}
  @Test void ignoresForgedApplicantAndUsesAuthenticatedUser(){controller.apply("QA1",p("userId",11,"amountFen",29,"reason","提前退房","requestKey","request-001"));verify(service).apply(10L,"QA1",29,"提前退房","request-001");}
  @Test void rejectsMalformedCentAmountsBeforeService(){for(Object n:Arrays.asList(0,-1,0.5,"1e2","1.00","99999999999","")){assertEquals(422,assertThrows(ServiceException.class,()->controller.apply("QA1",p("amountFen",n))).getCode());}verifyNoInteractions(service);}
  @Test void allAdminOperationsRequireRefundPermission(){for(java.lang.reflect.Method m:KgStayAfterRefundController.class.getDeclaredMethods()){PreAuthorize a=m.getAnnotation(PreAuthorize.class);assertNotNull(a);assertEquals("@ss.hasPermi('kangaroo:stayorder:refund')",a.value());}}
  @Test void mutationsAreTransactional()throws Exception{for(String method:Arrays.asList("apply","review")){java.lang.reflect.Method m=Arrays.stream(KgStayAfterRefundService.class.getDeclaredMethods()).filter(x->x.getName().equals(method)).findFirst().get();assertNotNull(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class));}}
}
