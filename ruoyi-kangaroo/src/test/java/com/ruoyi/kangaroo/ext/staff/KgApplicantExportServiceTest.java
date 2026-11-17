package com.ruoyi.kangaroo.ext.staff;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgTravelIdService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 2026-11-03 到店名单导出：只导「岗位管理·导出」的门店，证件号没交写「未提交」，预览脱敏，导出按门店记日志 */
class KgApplicantExportServiceTest
{
    static final String ID = "11010519491231002X";

    @AfterEach
    void reset()
    {
        RolePhase.set(RolePhase.LEGACY);
    }

    private static StaffGrantView grant(long store, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setStoreName("店" + store);
        g.setRoleCode(role);
        g.setActive(true);
        return g;
    }

    private static AppLoginUser me(StaffGrantView... grants)
    {
        AppLoginUser me = new AppLoginUser();
        me.setUserId(1L);
        me.setStaffGrants(new ArrayList<StaffGrantView>(Arrays.asList(grants)));
        return me;
    }

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static KgApplicantExportService service(KgJdbc db, StaffSupport support, KgTravelIdService travel)
    {
        KgApplicantExportService s = new KgApplicantExportService();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "support", support);
        ReflectionTestUtils.setField(s, "travelIds", travel);
        return s;
    }

    @Test
    void onlyStoresWithJobsExportCount()
    {
        RolePhase.set(RolePhase.NEW);
        AppLoginUser me = me(grant(1, StaffRole.MANAGER), grant(2, StaffRole.KEEPER), grant(3, StaffRole.CITY_LEAD), grant(4, StaffRole.MERCHANT), grant(5, StaffRole.ONLINE_STAFF));
        List<Long> ids = new ArrayList<Long>();
        for (StaffGrantView g : KgApplicantExportService.exportable(me, null))
        {
            ids.add(g.getStoreId());
        }
        assertEquals(Arrays.asList(1L, 3L), ids, "换工主理人、城市负责人能导；小院主理人、商家、专员不能");
        assertEquals(1, KgApplicantExportService.exportable(me, 3L).size());
        assertTrue(KgApplicantExportService.exportable(me, 2L).isEmpty(), "点了没导出权的门店：一家都没有");
        assertTrue(KgApplicantExportService.exportable(me, 99L).isEmpty(), "不在授权里的门店");
    }

    @Test
    void noExportableStoreIsForbiddenAndLogged()
    {
        StaffSupport support = mock(StaffSupport.class);
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.storeId = 2L;
        c.role = StaffRole.KEEPER;
        when(support.ctx(any())).thenReturn(c);
        KgApplicantExportService s = service(mock(KgJdbc.class), support, mock(KgTravelIdService.class));
        assertThrows(ServiceException.class, () -> s.query(me(grant(2, StaffRole.KEEPER)), null, "2026-11-01", "2026-11-30", null));
        verify(support).need(c, "jobs", StaffPerms.EXPORT, "applicant_export", "导出到店名单");
    }

    @Test
    void datesAreValidatedAndCappedAtOneYear()
    {
        KgApplicantExportService s = service(mock(KgJdbc.class), mock(StaffSupport.class), mock(KgTravelIdService.class));
        AppLoginUser me = me(grant(1, StaffRole.MANAGER));
        assertEquals(422, assertThrows(ServiceException.class, () -> s.query(me, null, null, "2026-11-30", null)).getCode());
        assertEquals(422, assertThrows(ServiceException.class, () -> s.query(me, null, "2026/11/01", "2026-11-30", null)).getCode());
        assertEquals(422, assertThrows(ServiceException.class, () -> s.query(me, null, "2026-11-30", "2026-11-01", null)).getCode());
        assertEquals(422, assertThrows(ServiceException.class, () -> s.query(me, null, "2026-01-01", "2027-01-02", null)).getCode());
        assertEquals(422, assertThrows(ServiceException.class, () -> s.query(me, null, "2026-11-01", "2026-11-30", "all")).getCode());
        KgApplicantExportService.Query q = s.query(me, null, "2026-11-01", "2026-11-30", "hired");
        assertTrue(q.hiredOnly);
        assertEquals(Arrays.asList(1L), q.stores);
    }

    @Test
    void queryScopesToStoresAndFiltersOnStartDate()
    {
        KgJdbc db = mock(KgJdbc.class);
        KgApplicantExportService s = service(db, mock(StaffSupport.class), mock(KgTravelIdService.class));
        KgApplicantExportService.Query q = s.query(me(grant(1, StaffRole.MANAGER)), null, "2026-11-01", "2026-11-30", null);
        s.rows(q);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> p = ArgumentCaptor.forClass(Map.class);
        verify(db).list(sql.capture(), p.capture());
        assertTrue(sql.getValue().contains("a.store_id in (:ids)"));
        assertTrue(sql.getValue().contains("<> 'online'"));
        assertTrue(sql.getValue().contains("coalesce(ar.arrive_date, a.arrival_date) >= :f and coalesce(ar.arrive_date, a.arrival_date) < :t"));
        assertEquals(Arrays.asList(1L), p.getValue().get("ids"));
        assertEquals("2026-11-01", p.getValue().get("f"));
        assertEquals("2026-12-01", p.getValue().get("t"), "结束日当天也算");
        assertEquals(KgApplicantExportService.ACTIVE, p.getValue().get("st"));
        assertFalse(KgApplicantExportService.ACTIVE.contains("withdrawn"));
        assertFalse(KgApplicantExportService.ACTIVE.contains("terminated"));
        assertFalse(KgApplicantExportService.ACTIVE.contains("review_rejected"));
    }

    @Test
    void endDayIsStartPlusStayDaysMinusOne()
    {
        assertEquals("2026-11-30", KgApplicantExportService.endDay("2026-11-01", 30));
        assertEquals("2026-11-01", KgApplicantExportService.endDay("2026-11-01", 1));
        assertEquals("", KgApplicantExportService.endDay("2026-11-01", 0));
        assertEquals("", KgApplicantExportService.endDay("", 5));
    }

    private static List<Map<String, Object>> sample()
    {
        return Arrays.asList(
                row("appId", 11L, "storeId", 1L, "status", "contract_signed", "realName", "张三", "phone", "13800001111", "stayDays", 30, "jobTitle", "前台换工",
                        "storeName", "平潭袋鼠小院", "startDay", "2026-11-05", "idName", "张三", "idNoEnc", "v1:ok"),
                row("appId", 12L, "storeId", 1L, "status", "submitted", "realName", "李四", "phone", "", "userPhone", "13900002222", "stayDays", 15, "jobTitle", "咖啡换工",
                        "storeName", "平潭袋鼠小院", "startDay", "2026-11-08"),
                row("appId", 13L, "storeId", 1L, "status", "store_confirmed", "realName", "王五", "phone", "13700003333", "stayDays", 10, "jobTitle", "前台换工",
                        "storeName", "平潭袋鼠小院", "startDay", "2026-11-09", "idName", "王五", "idNoEnc", "v1:stale"));
    }

    private static KgTravelIdService travel()
    {
        KgTravelIdService t = mock(KgTravelIdService.class);
        when(t.reveal("v1:ok")).thenReturn(ID);
        when(t.reveal("v1:stale")).thenReturn(null);
        return t;
    }

    @Test
    void previewMasksIdAndPhoneAndCountsMissing()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenReturn(sample());
        KgApplicantExportService s = service(db, mock(StaffSupport.class), travel());
        Map<String, Object> out = s.preview(s.query(me(grant(1, StaffRole.MANAGER)), null, "2026-11-01", "2026-11-30", null));
        assertEquals(3, out.get("count"));
        assertEquals(2, out.get("missingId"), "一人没交、一人需重新提交");
        assertFalse(out.toString().contains(ID), "预览里没有完整证件号");
        assertFalse(out.toString().contains("13800001111"), "预览里手机号脱敏");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals("110***********002X", rows.get(0).get("idNo"));
        assertEquals("未提交", rows.get(1).get("idNo"));
        assertEquals("需重新提交", rows.get(2).get("idNo"));
        assertEquals("2026-12-04", rows.get(0).get("end"));
    }

    @Test
    void excelHasFullIdAsTextAndLogsPerStore() throws Exception
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.list(anyString(), anyMap())).thenReturn(sample());
        StaffSupport support = mock(StaffSupport.class);
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.storeId = 1L;
        c.role = StaffRole.MANAGER;
        when(support.ctx(1L)).thenReturn(c);
        KgApplicantExportService s = service(db, support, travel());
        AppLoginUser me = me(grant(1, StaffRole.MANAGER));
        byte[] bytes = s.export(me, s.query(me, null, "2026-11-01", "2026-11-30", null));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes)))
        {
            Sheet sh = wb.getSheetAt(0);
            assertEquals("到店名单", sh.getSheetName());
            String[] head = new String[10];
            for (int i = 0; i < 10; i++)
            {
                head[i] = sh.getRow(2).getCell(i).getStringCellValue();
            }
            assertArrayEquals(new String[] { "序号", "姓名", "身份证号", "手机号", "门店", "岗位", "开始时间", "结束时间", "天数", "报名状态" }, head);
            assertEquals(CellType.STRING, sh.getRow(3).getCell(2).getCellType());
            assertEquals(ID, sh.getRow(3).getCell(2).getStringCellValue());
            assertEquals("13800001111", sh.getRow(3).getCell(3).getStringCellValue());
            assertEquals("平潭袋鼠小院", sh.getRow(3).getCell(4).getStringCellValue());
            assertEquals("前台换工", sh.getRow(3).getCell(5).getStringCellValue());
            assertEquals("2026-11-05", sh.getRow(3).getCell(6).getStringCellValue());
            assertEquals("2026-12-04", sh.getRow(3).getCell(7).getStringCellValue());
            assertEquals("未提交", sh.getRow(4).getCell(2).getStringCellValue());
            assertEquals("13900002222", sh.getRow(4).getCell(3).getStringCellValue(), "报名没留手机号时用账号绑定的");
            assertEquals("需重新提交", sh.getRow(5).getCell(2).getStringCellValue());
        }
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(support).op(eq(c), eq("applicant_export"), eq("导出到店名单"), eq("export"), detail.capture(), eq(true), isNull());
        assertTrue(detail.getValue().contains("3 人"), detail.getValue());
        assertTrue(detail.getValue().contains("2 人未提交"), detail.getValue());
        assertFalse(detail.getValue().contains(ID), "日志里不写证件号");
    }

    @Test
    void tooManyRowsRefusesExport()
    {
        KgJdbc db = mock(KgJdbc.class);
        List<Map<String, Object>> many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i <= KgApplicantExportService.MAX_ROWS; i++)
        {
            many.add(row("appId", (long) i, "storeId", 1L, "startDay", "2026-11-05"));
        }
        when(db.list(anyString(), anyMap())).thenReturn(many);
        StaffSupport support = mock(StaffSupport.class);
        KgApplicantExportService s = service(db, support, travel());
        AppLoginUser me = me(grant(1, StaffRole.MANAGER));
        assertEquals(422, assertThrows(ServiceException.class, () -> s.export(me, s.query(me, null, "2026-11-01", "2026-11-30", null))).getCode());
        verify(support, never()).op(any(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any());
    }
}
