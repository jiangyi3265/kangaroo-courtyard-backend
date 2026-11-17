package com.ruoyi.kangaroo.security;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import com.ruoyi.common.exception.ServiceException;

/**
 * AppAuthInterceptor 会不会把登录身份留在 Tomcat 线程上。
 *
 * 为什么要专门测它：这个拦截器的校验失败走的是**抛异常**而不是 return false。
 * Spring 的 HandlerExecutionChain 只在 preHandle 正常返回 true 之后才记 interceptorIndex，
 * preHandle 抛出时 triggerAfterCompletion 从 -1 开始循环 —— afterCompletion 一次都不会调。
 * 原先的写法是"先 AppUserContext.set(loginUser)，再做员工授权校验"，
 * 于是一个没有员工授权的账号访问员工接口拿到 403 之后，它的身份就留在了那个线程上；
 * 下一个落到同一线程的匿名请求（/app/job/** 都是 required=false）如果读上下文，
 * 读到的是上一个人。
 *
 * 线程池复用在真实压测下才会稳定重现，端到端测试里做不出确定性的用例，
 * 所以这里直接对拦截器本身断言：任何一条抛出路径走完，ThreadLocal 都必须是空的。
 */
class AppAuthInterceptorTest
{
    private AppAuthInterceptor interceptor;

    private AppTokenService tokenService;

    /** 用来取 HandlerMethod 的桩控制器 */
    static class StubController
    {
        @AppAuth
        public void needLogin()
        {
        }

        @AppAuth(staff = true)
        public void needStaff()
        {
        }

        @AppAuth(required = false)
        public void anonymous()
        {
        }
    }

    private HandlerMethod handler(String method) throws Exception
    {
        Method m = StubController.class.getMethod(method);
        return new HandlerMethod(new StubController(), m);
    }

    private AppLoginUser user(boolean staff)
    {
        AppLoginUser u = new AppLoginUser();
        u.setUserId(42L);
        if (staff)
        {
            u.setStaffGrants(Collections.singletonList(new StaffGrantView()));
        }
        return u;
    }

    @BeforeEach
    void setUp() throws Exception
    {
        interceptor = new AppAuthInterceptor();
        tokenService = mock(AppTokenService.class);
        Field f = AppAuthInterceptor.class.getDeclaredField("appTokenService");
        f.setAccessible(true);
        f.set(interceptor, tokenService);
        org.springframework.test.util.ReflectionTestUtils.setField(interceptor, "identities", mock(AppIdentityLoader.class));
        AppUserContext.remove();
    }

    @AfterEach
    void tearDown()
    {
        AppUserContext.remove();
    }

    private HttpServletRequest req()
    {
        return new MockHttpServletRequest("GET", "/app/whatever");
    }

    private HttpServletResponse resp()
    {
        return new MockHttpServletResponse();
    }

    @Test
    @DisplayName("没有员工授权的账号访问员工接口：抛 403，且身份不能留在线程上")
    void staffRejectionLeavesNoResidue() throws Exception
    {
        when(tokenService.getLoginUser(any(HttpServletRequest.class))).thenReturn(user(false));

        assertThrows(ServiceException.class,
                () -> interceptor.preHandle(req(), resp(), handler("needStaff")));

        assertNull(AppUserContext.get(),
                "preHandle 抛出后 afterCompletion 不会被调用，身份必须在抛出前就清掉");
    }

    @Test
    @DisplayName("未登录访问需要登录的接口：抛 401，线程上也不能有身份")
    void unauthorizedLeavesNoResidue() throws Exception
    {
        when(tokenService.getLoginUser(any(HttpServletRequest.class))).thenReturn(null);

        assertThrows(ServiceException.class,
                () -> interceptor.preHandle(req(), resp(), handler("needLogin")));

        assertNull(AppUserContext.get());
    }

    @Test
    @DisplayName("令牌校验本身抛出时同样不留残留")
    void verifyFailureLeavesNoResidue() throws Exception
    {
        when(tokenService.getLoginUser(any(HttpServletRequest.class))).thenReturn(user(true));
        org.mockito.Mockito.doThrow(new ServiceException("令牌已过期"))
                .when(tokenService).verifyToken(any(AppLoginUser.class));

        assertThrows(ServiceException.class,
                () -> interceptor.preHandle(req(), resp(), handler("needLogin")));

        assertNull(AppUserContext.get());
    }

    @Test
    @DisplayName("上一个请求留下的身份，会在进入拦截器时就被清掉")
    void residueFromPreviousRequestIsCleared() throws Exception
    {
        // 模拟"同一个线程上一次请求留下的身份"
        AppUserContext.set(user(true));
        when(tokenService.getLoginUser(any(HttpServletRequest.class))).thenReturn(null);

        interceptor.preHandle(req(), resp(), handler("anonymous"));

        assertNull(AppUserContext.get(),
                "匿名请求不该看见上一个人的身份");
    }

    @Test
    @DisplayName("正常放行时身份写入上下文，afterCompletion 之后清空")
    void happyPathSetsAndClears() throws Exception
    {
        AppLoginUser u = user(true);
        when(tokenService.getLoginUser(any(HttpServletRequest.class))).thenReturn(u);

        interceptor.preHandle(req(), resp(), handler("needStaff"));
        assertSame(u, AppUserContext.get());

        interceptor.afterCompletion(req(), resp(), handler("needStaff"), null);
        assertNull(AppUserContext.get());
    }
}
