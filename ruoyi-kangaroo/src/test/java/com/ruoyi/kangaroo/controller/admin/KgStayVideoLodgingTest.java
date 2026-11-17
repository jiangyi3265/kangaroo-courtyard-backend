package com.ruoyi.kangaroo.controller.admin;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;

/** 后台传岗位的住宿视频（V66，kind=lodging）：只有岗位权限的账号也能传，规则和小院视频一样（3 分钟、100MB）；没有岗位权限的传不了。 */
class KgStayVideoLodgingTest
{
    final KgStayVideoController controller = new KgStayVideoController();
    final KgVideoUploads uploads = mock(KgVideoUploads.class);

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(controller, "videos", uploads);
    }

    @AfterEach
    void cleanup()
    {
        SecurityContextHolder.clearContext();
    }

    void login(String permission)
    {
        LoginUser user = new LoginUser();
        user.setUserId(45L);
        user.setPermissions(Collections.singleton(permission));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
    }

    @Test
    void jobEditorUploadsWithTheThreeMinuteRule()
    {
        login("kangaroo:job:edit");
        controller.begin(p("kind", "lodging", "name", "room.mp4", "size", 100L, "duration", 150));
        verify(uploads).partBegin(-45L, "room.mp4", 100L, 150, true);
        login("kangaroo:job:add");
        controller.begin(p("kind", "lodging", "name", "room2.mp4", "size", 100L, "duration", 150));
        verify(uploads).partBegin(-45L, "room2.mp4", 100L, 150, true);
    }

    @Test
    void stayEditorWithoutJobPermissionCannot()
    {
        login("kangaroo:stay:edit");
        ServiceException e = assertThrows(ServiceException.class, () -> controller.begin(p("kind", "lodging", "name", "room.mp4")));
        assertEquals(403, e.getCode());
        verifyNoInteractions(uploads);
    }

    @Test
    void jobEditorCannotUseTheOtherKinds()
    {
        login("kangaroo:job:edit");
        for (String kind : new String[] { "intro", "room", "diary" })
        {
            assertEquals(403, assertThrows(ServiceException.class, () -> controller.begin(p("kind", kind))).getCode(), kind);
        }
        assertEquals(422, assertThrows(ServiceException.class, () -> controller.begin(p("kind", "unknown"))).getCode());
        verifyNoInteractions(uploads);
    }
}
