package com.ruoyi.kangaroo.ext.stay;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.core.KgVideoUploads;
class KgAdminStayDiaryTest {
    final KgJdbc db = mock(KgJdbc.class);
    final KgVideoUploads videos = mock(KgVideoUploads.class);
    final KgStayMediaService service = new KgStayMediaService();
    final String photo = "/profile/upload/daily.jpg";
    final String video = "/profile/upload/videos/2026/10/26/0123456789abcdef0123456789abcdef.mp4";
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "db", db); ReflectionTestUtils.setField(service, "videos", videos);
        when(videos.exists(video)).thenReturn(true);
        when(db.must(contains("from kg_stay where"), anyMap(), anyString())).thenReturn(p("stayId", 7L, "storeId", 104L));
        when(db.must(contains("from kg_stay_diary where"), anyMap(), anyString())).thenReturn(p("stayId", 7L, "mediaType", "image", "mediaUrl", photo, "caption", "旧日常"));
    }
    @Test void newPhotoDerivesStoreFromSelectedStayAndNormalizesTextAndDate() {
        Map<String,Object> row = p("stay_id", 7L, "store_id", 999L, "media_type", "image", "media_url", photo, "caption", " 早餐\n 时光 ", "shot_date", LocalDate.of(2026,10,20));
        service.validateAdminDiary(row, true);
        assertEquals(104L,row.get("store_id")); assertEquals("早餐 时光",row.get("caption")); assertEquals("2026-10-20",row.get("shot_date")); assertEquals("0",row.get("status"));
    }
    @Test void refusesEmptyExternalAndMismatchedMedia() {
        for (String url : Arrays.asList("", "https://example.com/fake.jpg", video))
            assertThrows(ServiceException.class, () -> service.validateAdminDiary(p("stay_id",7L,"media_type","image","media_url",url),true));
    }
    @Test void videoMustExistAndPhotoCannotMasqueradeAsVideo() {
        Map<String,Object> row = p("stay_id",7L,"media_type","video","media_url",video,"cover_url",photo);
        service.validateAdminDiary(row,true); assertEquals(photo,row.get("cover_url"));
        when(videos.exists(video)).thenReturn(false);
        assertThrows(ServiceException.class, () -> service.validateAdminDiary(row,true));
        assertThrows(ServiceException.class, () -> service.validateAdminDiary(p("stay_id",7L,"media_type","video","media_url",photo),true));
    }
    @Test void updateKeepsOriginalStayAndSupportsHideWithoutReplacingContent() {
        Map<String,Object> row=p("_pk",11L,"stay_id",999L,"status","1"); service.validateAdminDiary(row,false);
        assertEquals(7L,row.get("stay_id")); assertEquals(photo,row.get("media_url")); assertEquals("1",row.get("status")); assertFalse(row.containsKey("caption"));
    }
    @Test void capsDiaryCountAndRejectsFutureDate() {
        when(db.count(anyString(),anyMap())).thenReturn(200L);
        assertThrows(ServiceException.class, () -> service.validateAdminDiary(p("stay_id",7L,"media_type","image","media_url",photo),true));
        when(db.count(anyString(),anyMap())).thenReturn(0L);
        assertThrows(ServiceException.class, () -> service.validateAdminDiary(p("stay_id",7L,"media_type","image","media_url",photo,"shot_date",LocalDate.now().plusDays(5)),true));
    }
    @Test void metadataEnablesAddAndPreservesDerivedStoreAndStayOwnership() {
        KgStayModule module=new KgStayModule(db); ReflectionTestUtils.setField(module,"media",service);
        ExtEntity diary=module.entities().stream().filter(e -> e.key.equals("staydiary")).findFirst().get();
        assertTrue(diary.allowAdd); assertNotNull(diary.validator);
        assertTrue(diary.fields.stream().filter(f -> f.name.equals("stayId")).findFirst().get().createOnly);
        assertFalse(diary.fields.stream().filter(f -> f.name.equals("storeId")).findFirst().get().editable);
    }
}
