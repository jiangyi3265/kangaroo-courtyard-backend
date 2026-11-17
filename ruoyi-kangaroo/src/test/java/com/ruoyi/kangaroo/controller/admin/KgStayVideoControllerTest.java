package com.ruoyi.kangaroo.controller.admin;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;

class KgStayVideoControllerTest {
    final KgStayVideoController controller = new KgStayVideoController();
    final KgVideoUploads uploads = mock(KgVideoUploads.class);
    @BeforeEach void setup() { ReflectionTestUtils.setField(controller, "videos", uploads); login("kangaroo:stay:edit"); }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    void login(String permission) {
        LoginUser user = new LoginUser(); user.setUserId(45L); user.setPermissions(Collections.singleton(permission));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
    }
    @Test void introUsesAdminOwnerAndThreeMinuteLimits() {
        controller.begin(p("kind", "intro", "name", "court.mp4", "size", 100L, "duration", 120));
        verify(uploads).partBegin(-45L, "court.mp4", 100L, 120, true);
    }
    @Test void unrelatedEditPermissionCannotStartUpload() {
        assertThrows(ServiceException.class, () -> controller.begin(p("kind", "diary")));
        verifyNoInteractions(uploads);
    }
    @Test void shortVideoUsesShortLimitsAndRejectsUnknownKinds() {
        login("kangaroo:staydiary:add"); controller.begin(p("kind", "diary", "name", "daily.mp4", "size", 20L, "duration", 8));
        verify(uploads).partBegin(-45L, "daily.mp4", 20L, 8, false);
        assertThrows(ServiceException.class, () -> controller.begin(p("kind", "unknown")));
    }
    @Test void finishingAndCancelingUseCurrentAdminIdentity() {
        controller.finish("owned"); controller.abort("owned");
        verify(uploads).partFinish("owned", -45L); verify(uploads).partAbort("owned", -45L);
    }
    @Test void everyUploadEndpointRequiresBackendPermissions() throws Exception {
        for (java.lang.reflect.Method method : KgStayVideoController.class.getDeclaredMethods())
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) assertNotNull(method.getAnnotation(PreAuthorize.class), method.getName());
    }
    @Test void diaryEditorsCanLoadStayOptions() throws Exception {
        String permission = KgStayAdminController.class.getMethod("stayOptions", String.class).getAnnotation(PreAuthorize.class).value();
        assertTrue(permission.contains("kangaroo:staydiary:add")); assertTrue(permission.contains("kangaroo:staydiary:edit"));
    }
}
