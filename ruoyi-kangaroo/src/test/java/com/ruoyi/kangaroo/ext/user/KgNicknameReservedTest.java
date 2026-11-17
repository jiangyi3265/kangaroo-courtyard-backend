package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;

/** 昵称保留词（内测反馈 2.0 d2）：去掉空格符号后含官方 / 平台字样的昵称一律 422，且不走社区敏感词表。 */
class KgNicknameReservedTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    private final KgAccountService service = new KgAccountService();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        AppLoginUser user = new AppLoginUser();
        user.setUserId(5L);
        AppUserContext.set(user);
        when(guard.check(anyString())).thenReturn("pass");
    }

    @AfterEach
    void clear()
    {
        AppUserContext.remove();
    }

    @ParameterizedTest
    @ValueSource(strings = { "官方", "袋鼠客服", "管理员小王", "系统通知", "平台", "袋鼠小院", "袋鼠旅游官方号", "袋鼠青年", "袋鼠全球青年", "袋鼠·全球·青年", "小助手",
            "官 方", "客-服", "袋鼠·小院", "袋　鼠　旅　游", "小_助_手", "管理员!", "【系统】", "客​服" })
    void reservedWordsAreBlockedAfterNormalising(String nickname)
    {
        assertTrue(KgAccountService.reservedNickname(nickname), nickname);
        ServiceException e = assertThrows(ServiceException.class, () -> service.saveProfile(p("nickname", nickname)));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertEquals("昵称不能使用官方或平台相关字样", e.getMessage());
        verify(db, never()).update(anyString(), anyMap());
        // 保留词自己拦，不靠（也不改）社区共用的敏感词表
        verify(guard, never()).check(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = { "小院同学", "大理的风", "袋鼠", "Kangaroo", "阿官", "客人", "平安", "系上鞋带" })
    void ordinaryNicknamesPass(String nickname)
    {
        assertFalse(KgAccountService.reservedNickname(nickname), nickname);
    }

    @Test
    void ordinaryNicknameStillGoesThroughGuardAndSaves()
    {
        when(db.one(anyString(), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        try
        {
            service.saveProfile(p("nickname", "大理的风"));
        }
        catch (RuntimeException ignored)
        {
            // 保存之后的资料回读依赖更多桩，这里只关心保留词没拦它、敏感词照常检查
        }
        verify(guard).check(eq("大理的风"));
    }
}
