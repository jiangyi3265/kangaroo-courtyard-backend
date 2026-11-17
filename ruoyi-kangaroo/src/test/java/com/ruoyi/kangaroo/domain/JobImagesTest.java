package com.ruoyi.kangaroo.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

import com.ruoyi.common.exception.ServiceException;

/** 岗位照片（V49）：只收本平台上传的路径、最多 9 张、去重、存 JSON 数组；读库宽松；封面取第一张 → 岗位封面 → 门店封面 */
class JobImagesTest
{
    private static final String A = "/profile/upload/2026/10/16/a1b2c3.jpg";
    private static final String B = "/profile/upload/2026/10/16/d4e5f6.png";
    private UploadedImageFixture images;
    @BeforeEach void uploadedFiles() { images = new UploadedImageFixture(A, B); }
    @AfterEach void cleanUploads() { images.close(); }

    private static int code(Object raw)
    {
        ServiceException e = assertThrows(ServiceException.class, () -> JobImages.parse(raw));
        return e.getCode() == null ? 500 : e.getCode();
    }

    @Test
    void requiredPhotosRejectMissingBlankAndPlaceholderValues()
    {
        for (Object raw : Arrays.asList(null, "", "[]", "[\" \" ]", Collections.emptyList(), "/static/media/cover-empty.png")) {
            assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> JobImages.require(raw)).getCode());
        }
        assertEquals(Arrays.asList(A), JobImages.require(Arrays.asList(A)));
    }

    @Test
    void acceptsListJsonAndCommaSeparatedAndKeepsOrderWithoutDuplicates()
    {
        assertEquals(Arrays.asList(A, B), JobImages.parse(Arrays.asList(A, B, A, " ")));
        assertEquals(Arrays.asList(B, A), JobImages.parse("[\"" + B + "\",\"" + A + "\"]"));
        // 后台图片上传组件给的是逗号分隔
        assertEquals(Arrays.asList(A, B), JobImages.parse(A + "," + B));
        assertEquals(Collections.emptyList(), JobImages.parse(""));
        assertEquals(Collections.emptyList(), JobImages.parse(new ArrayList<Object>()));
        assertNull(JobImages.parse(null), "没带这个字段");
    }

    @Test
    void onlyPlatformUploadPathsAreAccepted()
    {
        assertEquals(422, code(Arrays.asList("https://evil.example.com/x.jpg")));
        assertEquals(422, code(Arrays.asList("/static/media/cover-empty.png")), "包内图片不是上传的照片");
        assertEquals(422, code(Arrays.asList("/profile/avatar/2026/a.jpg")), "只认上传目录");
        assertEquals(422, code(Arrays.asList("/profile/upload/../../etc/passwd")));
        assertEquals(422, code(Arrays.asList("/profile/upload/2026/微信图片.jpg")));
        assertEquals(422, code(Arrays.asList("/profile/upload/a b.jpg")));
        assertEquals(422, code(Arrays.asList(1, 2)), "不是字符串");
        assertEquals(422, code(Collections.singletonMap("a", A)));
        assertEquals(422, code("[not json"));
        assertTrue(JobImages.isUpload(A));
        assertFalse(JobImages.isUpload("/profile/upload/" + new String(new char[260]).replace('\0', 'a') + ".jpg"));
    }

    @Test
    void atMostNinePhotos()
    {
        List<String> nine = new ArrayList<String>();
        for (int i = 0; i < 9; i++)
        {
            nine.add("/profile/upload/2026/10/16/p" + i + ".jpg");
        }
        assertEquals(9, JobImages.parse(nine).size());
        List<String> ten = new ArrayList<String>(nine);
        ten.add("/profile/upload/2026/10/16/p9.jpg");
        ServiceException e = assertThrows(ServiceException.class, () -> JobImages.parse(ten));
        assertEquals(Integer.valueOf(422), e.getCode());
        assertEquals("岗位照片最多 9 张", e.getMessage());
        // 9 张长路径拼起来超过列宽（2000）也拦
        List<String> longOnes = new ArrayList<String>();
        for (int i = 0; i < 9; i++)
        {
            longOnes.add("/profile/upload/" + new String(new char[220]).replace('\0', (char) ('a' + i)) + ".jpg");
        }
        assertEquals(422, code(longOnes));
    }

    @Test
    void storedAsJsonArrayAndEmptyAsBlank()
    {
        assertEquals("[\"" + A + "\",\"" + B + "\"]", JobImages.toJson(Arrays.asList(A, B)));
        assertEquals("", JobImages.toJson(Collections.<String>emptyList()));
        assertEquals("", JobImages.toJson(null));
    }

    @Test
    void readingIsLenient()
    {
        assertEquals(Arrays.asList(A, B), JobImages.read("[\"" + A + "\",\"" + B + "\"]"));
        assertEquals(Arrays.asList(A), JobImages.read(A), "逗号分隔的老写法也认");
        assertEquals(Collections.emptyList(), JobImages.read(null));
        assertEquals(Collections.emptyList(), JobImages.read(""));
        assertEquals(Collections.emptyList(), JobImages.read("[broken"));
        // 手改库塞进去的不合格路径跳过，不报错
        assertEquals(Arrays.asList(A), JobImages.read("[\"http://x.com/a.jpg\",\"" + A + "\",3]"));
    }

    @Test
    void coverIsFirstPhotoThenJobCoverThenStoreCover()
    {
        assertEquals(A, JobImages.cover(Arrays.asList(A, B), "/profile/upload/old.jpg", "/profile/upload/store.jpg"));
        assertEquals("/profile/upload/old.jpg", JobImages.cover(Collections.<String>emptyList(), "/profile/upload/old.jpg", "/profile/upload/store.jpg"));
        assertEquals("/profile/upload/store.jpg", JobImages.cover(null, " ", "/profile/upload/store.jpg"));
        assertEquals("", JobImages.cover(null, null, null));
    }
}
