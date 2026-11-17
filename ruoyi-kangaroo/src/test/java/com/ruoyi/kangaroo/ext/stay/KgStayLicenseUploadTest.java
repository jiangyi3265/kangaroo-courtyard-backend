package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;

class KgStayLicenseUploadTest
{
    final KgJdbc db = mock(KgJdbc.class);
    final KgPrivateFiles files = mock(KgPrivateFiles.class);
    final StaffSupport support = mock(StaffSupport.class);
    final KgStayStaffService service = new KgStayStaffService();
    final StaffSupport.Ctx ctx = new StaffSupport.Ctx();
    final MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", new byte[] { 1 });

    @BeforeEach void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "files", files);
        ReflectionTestUtils.setField(service, "support", support);
        ctx.me = mock(AppLoginUser.class); ctx.storeId = 102L;
        when(ctx.me.getUserId()).thenReturn(1L);
        when(ctx.me.hasStoreAccess(102L)).thenReturn(true);
        when(db.one(contains("select * from kg_stay"), anyMap())).thenReturn(p("storeId", 102L, "stayName", "Test stay"));
        when(files.saveLicense(file)).thenReturn(p("key", "qual/marked.jpg", "name", "license.jpg", "size", 123L));
        when(files.save(file, "qual")).thenReturn(p("key", "qual/other.png", "name", "other.png", "size", 1L));
    }

    @Test void licenseIsWatermarkedBeforeItsTransformedFileIsRecorded()
    {
        service.uploadQual(ctx, 8L, "license", "", file);
        verify(files).saveLicense(file); verify(files, never()).save(any(), anyString());
        verify(db).insert(eq("kg_stay_qualification"), argThat(row -> "qual/marked.jpg".equals(row.get("file_key")) && Long.valueOf(123).equals(row.get("file_size"))));
    }

    @Test void failedWatermarkDoesNotInvalidatePreviousQualification()
    {
        when(files.saveLicense(file)).thenThrow(new ServiceException("水印失败"));
        assertThrows(ServiceException.class, () -> service.uploadQual(ctx, 8L, "license", "", file));
        verify(db, never()).update(anyString(), anyMap()); verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void otherQualificationsKeepTheirExistingUploadHandling()
    {
        service.uploadQual(ctx, 8L, "idcard", "", file);
        verify(files).save(file, "qual"); verify(files, never()).saveLicense(any());
    }

    @Test void storeAuthorizationIsCheckedBeforeAnyFileIsProcessed()
    {
        when(ctx.me.hasStoreAccess(102L)).thenReturn(false);
        assertThrows(ServiceException.class, () -> service.uploadQual(ctx, 8L, "license", "", file));
        verifyNoInteractions(files);
    }
}
