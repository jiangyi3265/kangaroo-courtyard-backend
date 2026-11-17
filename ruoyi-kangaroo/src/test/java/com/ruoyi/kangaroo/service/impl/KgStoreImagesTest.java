package com.ruoyi.kangaroo.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgStore;
import com.ruoyi.kangaroo.mapper.KgStoreMapper;

class KgStoreImagesTest
{
    private final KgStoreMapper mapper = mock(KgStoreMapper.class);
    private final KgStoreServiceImpl service = new KgStoreServiceImpl();
    private static final String A = "/profile/upload/2026/10/28/a.jpg", B = "/profile/upload/2026/10/28/b.png";
    private KgStore current;
    private String json(String... paths) { return JSON.toJSONString(paths); }
    private KgStore change(String images) { KgStore s = new KgStore(); s.setStoreId(77L); s.setImages(images); return s; }

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "kgStoreMapper", mapper);
        current = change(json(A, B)); current.setCover(A); current.setStoreName("图片测试门店"); current.setCity("大理"); current.setStatus("0");
        when(mapper.selectKgStoreByStoreId(77L)).thenReturn(current);
        SysUser user = new SysUser(); user.setUserId(1L); user.setUserName("store-images-test");
        LoginUser login = new LoginUser(); login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void createStoresAllImagesAndOverridesClientCover() {
        KgStore s = change(json(B, A)); s.setStoreName("新门店"); s.setCity("大理"); s.setCover(A);
        service.insertKgStore(s);
        verify(mapper).insertKgStore(s); assertEquals(B, s.getCover()); assertEquals(json(B, A), s.getImages());
    }
    @Test void reorderChangesCoverAndDeletingFirstPromotesNext() {
        KgStore reorder = change(json(B, A)); reorder.setCover(A); service.updateKgStore(reorder);
        assertEquals(B, reorder.getCover()); assertEquals(json(B, A), reorder.getImages());
        KgStore remove = change(json(B)); service.updateKgStore(remove); assertEquals(B, remove.getCover());
        KgStore clear = change("[]"); service.updateKgStore(clear); assertEquals("", clear.getCover()); assertEquals("[]", clear.getImages());
    }
    @Test void partialAndOldClientsPreserveGallery() {
        KgStore partial = change(null); partial.setRemark("仅备注"); service.updateKgStore(partial);
        assertNull(partial.getImages()); assertNull(partial.getCover());
        KgStore unchanged = change(null); unchanged.setCover(A); service.updateKgStore(unchanged); assertNull(unchanged.getImages());
        KgStore replacement = change(null); replacement.setCover("https://example.com/legacy,new.jpg"); service.updateKgStore(replacement);
        assertEquals(json("https://example.com/legacy,new.jpg", B), replacement.getImages());
    }
    @Test void legacyUrlAndPackagedCoverCanBecomeFirstPhoto() {
        for (String legacy : new String[] {"https://example.com/old,cover.jpg", "/static/media/store.jpg"}) {
            current.setImages(""); current.setCover(legacy);
            KgStore s = change(json(legacy, B)); service.updateKgStore(s);
            assertEquals(legacy, s.getCover()); assertEquals(json(legacy, B), s.getImages());
        }
    }
    @Test void rejectsMalformedTooManyAndUnsafePathsWithoutWriting() {
        String ten = JSON.toJSONString(IntStream.range(0, 10).mapToObj(i -> "/profile/upload/" + i + ".jpg").collect(Collectors.toList()));
        for (String invalid : new String[] {"not json", "null", "[1]", ten, json("javascript:alert(1)"), json("C:/image.jpg"), json("/profile/upload/../secret.jpg")})
            assertThrows(ServiceException.class, () -> service.updateKgStore(change(invalid)), invalid);
        verify(mapper, never()).updateKgStore(any());
    }
    @Test void duplicateImagesDoNotChangeFirstImage() {
        KgStore s = change(json(B, A, B)); service.updateKgStore(s); assertEquals(json(B, A), s.getImages()); assertEquals(B, s.getCover());
    }
}
