package com.ruoyi.kangaroo.validation;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import javax.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import static org.junit.jupiter.api.Assertions.*;

/** Bean validation used by admin create/edit/import; no Spring or database. */
class SysUserNameValidationTest
{
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll static void closeValidator() { FACTORY.close(); }

    @Test void usernameBoundsFollowTheLoginContract() throws Exception
    {
        Size size = SysUser.class.getMethod("getUserName").getAnnotation(Size.class);
        assertEquals(UserConstants.USERNAME_MIN_LENGTH, size.min());
        assertEquals(UserConstants.USERNAME_MAX_LENGTH, size.max());
    }

    @Test void adminAcceptsBothLoginLengthBoundaries()
    {
        assertTrue(VALIDATOR.validateValue(SysUser.class, "userName", "ab").isEmpty());
        assertTrue(VALIDATOR.validateValue(SysUser.class, "userName", "abcdefghijklmnopqrst").isEmpty());
    }

    @Test void adminRejectsNamesWhichLoginCannotAccept()
    {
        assertFalse(VALIDATOR.validateValue(SysUser.class, "userName", "a").isEmpty());
        assertFalse(VALIDATOR.validateValue(SysUser.class, "userName", "abcdefghijklmnopqrstu").isEmpty());
        assertFalse(VALIDATOR.validateValue(SysUser.class, "userName", "").isEmpty());
        assertFalse(VALIDATOR.validateValue(SysUser.class, "userName", "  ").isEmpty());
    }

    @Test void nicknameKeepsItsSeparateThirtyCharacterLimit()
    {
        assertTrue(VALIDATOR.validateValue(SysUser.class, "nickName", "abcdefghijklmnopqrstuvwxyz1234").isEmpty());
        assertFalse(VALIDATOR.validateValue(SysUser.class, "nickName", "abcdefghijklmnopqrstuvwxyz12345").isEmpty());
    }
}
