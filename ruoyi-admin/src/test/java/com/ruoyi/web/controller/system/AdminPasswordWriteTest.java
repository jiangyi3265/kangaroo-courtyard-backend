package com.ruoyi.web.controller.system;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.domain.model.RegisterBody;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.SysRegisterService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysDeptService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real entry-point code with mocked persistence; no HTTP, database or credentials. */
class AdminPasswordWriteTest
{
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final String OLD_PASSWORD = "old-test-only";
    private ISysUserService users;
    private TokenService tokens;
    private SysUserController admin;
    private SysProfileController profile;
    private LoginUser login;
    private String originalHash;

    @BeforeEach void setup()
    {
        users = mock(ISysUserService.class);
        tokens = mock(TokenService.class);
        admin = new SysUserController();
        profile = new SysProfileController();
        ReflectionTestUtils.setField(admin, "userService", users);
        ReflectionTestUtils.setField(admin, "deptService", mock(ISysDeptService.class));
        ReflectionTestUtils.setField(admin, "roleService", mock(ISysRoleService.class));
        ReflectionTestUtils.setField(profile, "userService", users);
        ReflectionTestUtils.setField(profile, "tokenService", tokens);
        SysUser operator = user();
        originalHash = SecurityUtils.encryptPassword(OLD_PASSWORD);
        operator.setPassword(originalHash);
        login = new LoginUser(31L, 11L, operator, Collections.emptySet());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
        when(users.checkUserNameUnique(any())).thenReturn(true);
        when(users.insertUser(any())).thenReturn(1);
        when(users.resetPwd(any())).thenReturn(1);
        when(users.updateUser(any())).thenReturn(1);
        when(users.selectUserById(31L)).thenReturn(operator);
        when(users.resetUserPwd(eq(31L), anyString())).thenReturn(1);
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }
    @AfterAll static void closeValidator() { VALIDATORS.close(); }

    private static SysUser user()
    {
        SysUser user = new SysUser(31L);
        user.setUserName("password_contract");
        user.setNickName("contract");
        user.setDeptId(11L);
        user.setRoleIds(new Long[] { 21L });
        return user;
    }

    private static void rejected(Runnable operation)
    {
        ServiceException error = assertThrows(ServiceException.class, operation::run);
        assertEquals(Integer.valueOf(400), error.getCode());
        assertEquals("密码长度必须在5到20个字符之间", error.getMessage());
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = { "1234", "123456789012345678901" })
    void addRejectsUnusablePasswordBeforePersistence(String password)
    {
        SysUser input = user(); input.setPassword(password);
        rejected(() -> admin.add(input));
        verify(users, never()).insertUser(any());
        assertEquals(password, input.getPassword());
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = { "1234", "123456789012345678901" })
    void administratorResetRejectsBeforePersistence(String password)
    {
        SysUser input = user(); input.setPassword(password);
        rejected(() -> admin.resetPwd(input));
        verify(users).checkUserAllowed(input);
        verify(users).checkUserDataScope(31L);
        verify(users, never()).resetPwd(any());
        assertEquals(originalHash, login.getPassword());
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = { "1234", "123456789012345678901" })
    void profileRejectsBeforePersistenceOrSessionMutation(String password)
    {
        Map<String, String> input = new HashMap<>();
        input.put("oldPassword", OLD_PASSWORD); input.put("newPassword", password);
        rejected(() -> profile.updatePwd(input));
        verify(users, never()).resetUserPwd(anyLong(), anyString());
        verifyNoInteractions(tokens);
        assertEquals(originalHash, login.getPassword());
        assertNull(login.getUser().getPwdUpdateDate());
    }

    @ParameterizedTest @ValueSource(strings = { "Ab!12", "Ab!12345678901234567" })
    void validAddWritesBcryptAtBothLoginBoundaries(String password)
    {
        SysUser input = user(); input.setPassword(password);
        assertEquals(200, admin.add(input).get(AjaxResult.CODE_TAG));
        ArgumentCaptor<SysUser> saved = ArgumentCaptor.forClass(SysUser.class);
        verify(users).insertUser(saved.capture());
        assertTrue(SecurityUtils.matchesPassword(password, saved.getValue().getPassword()));
        assertFalse(password.equals(saved.getValue().getPassword()));
    }

    @ParameterizedTest @ValueSource(strings = { "Ab!12", "Ab!12345678901234567" })
    void validResetWritesBcryptAndRetainsScopeChecks(String password)
    {
        SysUser input = user(); input.setPassword(password);
        assertEquals(200, admin.resetPwd(input).get(AjaxResult.CODE_TAG));
        verify(users).checkUserAllowed(input); verify(users).checkUserDataScope(31L);
        verify(users).resetPwd(input);
        assertTrue(SecurityUtils.matchesPassword(password, input.getPassword()));
    }

    @ParameterizedTest @ValueSource(strings = { "Ab!12", "Ab!12345678901234567" })
    void validProfileChangeWritesHashThenUpdatesSession(String password)
    {
        Map<String, String> input = new HashMap<>();
        input.put("oldPassword", OLD_PASSWORD); input.put("newPassword", password);
        assertEquals(200, profile.updatePwd(input).get(AjaxResult.CODE_TAG));
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(users).resetUserPwd(eq(31L), saved.capture());
        assertTrue(SecurityUtils.matchesPassword(password, saved.getValue()));
        assertEquals(saved.getValue(), login.getPassword());
        assertNotNull(login.getUser().getPwdUpdateDate()); verify(tokens).setLoginUser(login);
    }

    @Test void profileStillRejectsWrongOldPasswordWithoutWriting()
    {
        Map<String, String> input = new HashMap<>();
        input.put("oldPassword", "wrong-test-only"); input.put("newPassword", "valid-test-only");
        assertNotEquals(200, profile.updatePwd(input).get(AjaxResult.CODE_TAG));
        verify(users, never()).resetUserPwd(anyLong(), anyString()); verifyNoInteractions(tokens);
        assertEquals(originalHash, login.getPassword());
    }

    @Test void profileEditDoesNotBecomeAnAlternatePasswordReset()
    {
        SysUser input = user(); input.setPassword("123456789012345678901");
        assertEquals(200, admin.edit(input).get(AjaxResult.CODE_TAG));
        verify(users).updateUser(input); assertNull(input.getPassword());
        verify(users, never()).resetPwd(any());
    }

    private SysUserServiceImpl importing(SysUserMapper mapper, String password)
    {
        SysUserServiceImpl service = new SysUserServiceImpl();
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("sys.user.initPassword")).thenReturn(password);
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        ReflectionTestUtils.setField(service, "configService", config);
        ReflectionTestUtils.setField(service, "deptService", mock(ISysDeptService.class));
        ReflectionTestUtils.setField(service, "validator", VALIDATORS.getValidator());
        return service;
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = { "1234", "123456789012345678901" })
    void invalidImportDefaultCreatesNoUsers(String password)
    {
        SysUserMapper mapper = mock(SysUserMapper.class);
        SysUserServiceImpl service = importing(mapper, password);
        ServiceException error = assertThrows(ServiceException.class,
            () -> service.importUser(Arrays.asList(user(), user()), false, "contract_operator"));
        assertTrue(error.getMessage().contains("密码长度必须在5到20个字符之间"));
        verify(mapper, never()).insertUser(any()); verify(mapper, never()).updateUser(any());
    }

    @ParameterizedTest @ValueSource(strings = { "Ab!12", "Ab!12345678901234567" })
    void validImportStoresHashForConfiguredInitialPassword(String password)
    {
        SysUserMapper mapper = mock(SysUserMapper.class);
        SysUserServiceImpl service = importing(mapper, password);
        assertTrue(service.importUser(Collections.singletonList(user()), false, "contract_operator")
            .contains("已全部导入成功"));
        ArgumentCaptor<SysUser> saved = ArgumentCaptor.forClass(SysUser.class);
        verify(mapper).insertUser(saved.capture());
        assertTrue(SecurityUtils.matchesPassword(password, saved.getValue().getPassword()));
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = { "1234", "123456789012345678901" })
    void registrationAlreadyRejectsOutsideLoginContract(String password)
    {
        SysRegisterService registration = new SysRegisterService();
        ISysConfigService config = mock(ISysConfigService.class);
        ReflectionTestUtils.setField(registration, "configService", config);
        ReflectionTestUtils.setField(registration, "userService", users);
        RegisterBody body = new RegisterBody(); body.setUsername("contract_register"); body.setPassword(password);
        assertFalse(registration.register(body).isEmpty());
        verify(users, never()).registerUser(any());
    }
}
