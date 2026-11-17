package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.admin.KgStayVideoController;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/** 「云连锁」改名「抖音卖房」（2026-10-31 主人拍板）：用户看得到的都改，内部编码 cloud_chain 不改；以后谁在后端写回老名字，这里就红。 */
class KgDouyinRenameTest
{
    private static final String OLD_NAME = new String(new char[] { '云', '连', '锁' });

    @Test
    void labelsSayTheNewNameAndTheCodeStays()
    {
        assertEquals("抖音卖房", KgMerchantRequestService.typeLabel("cloud_chain"));
        assertEquals("cloud_chain", KgMerchantRequestService.CLOUD_CHAIN);
        assertEquals("代运营", KgMerchantRequestService.typeLabel("managed"));
        assertTrue(KgMerchantRequestModule.TYPE_OPTIONS.contains("cloud_chain:抖音卖房"));
        assertFalse(KgMerchantRequestModule.TYPE_OPTIONS.contains(OLD_NAME));
    }

    @Test
    void noBackendSourceMentionsTheOldName() throws IOException
    {
        List<String> hits = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main")))
        {
            for (Path f : files.filter(Files::isRegularFile).filter(x -> x.toString().endsWith(".java") || x.toString().endsWith(".xml")).collect(Collectors.toList()))
            {
                if (new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains(OLD_NAME))
                {
                    hits.add(f.toString());
                }
            }
        }
        assertEquals(Collections.emptyList(), hits);
    }

    private static KgMerchantRequestService service(KgJdbc db)
    {
        KgMerchantRequestService s = new KgMerchantRequestService();
        ReflectionTestUtils.setField(s, "db", db);
        ReflectionTestUtils.setField(s, "support", mock(StaffSupport.class));
        KgCityService cities = mock(KgCityService.class);
        when(cities.jobCity(anyString())).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(s, "cities", cities);
        return s;
    }

    private static StaffSupport.Ctx ctx()
    {
        StaffSupport.Ctx c = new StaffSupport.Ctx();
        c.me = new AppLoginUser();
        c.me.setUserId(7L);
        c.me.setStaffGrants(Collections.singletonList(new StaffGrantView()));
        c.storeId = 30L;
        c.storeName = "南舍庄园";
        c.role = StaffRole.MERCHANT;
        return c;
    }

    @Test
    void defaultTitleOfANewRequestUsesTheNewNameAndTheOldCode()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.must(contains("select store_id, store_name, city from kg_store"), anyMap(), anyString())).thenReturn(p("storeId", 30L, "storeName", "南舍庄园", "city", "平潭"));
        when(db.insert(eq("kg_merchant_request"), anyMap())).thenReturn(500L);
        service(db).merchantSubmit(ctx(), p("requestType", "cloud_chain", "content", "想在抖音上卖房", "contactName", "小杨", "contactPhone", "13800000000"));
        verify(db).insert(eq("kg_merchant_request"), argThat(row -> "cloud_chain".equals(row.get("request_type")) && "抖音卖房 · 南舍庄园".equals(row.get("title"))));
    }

    @Test
    void refusalsSayTheNewName()
    {
        KgJdbc db = mock(KgJdbc.class);
        ServiceException e = assertThrows(ServiceException.class, () -> service(db).merchantSubmit(ctx(), p("requestType", "online_task")));
        assertEquals("请选择代运营或抖音卖房", e.getMessage());
        when(db.one(contains("from kg_merchant_request"), anyMap())).thenReturn(p("requestId", 500L, "requestType", "online_task", "status", "unassigned"));
        e = assertThrows(ServiceException.class, () -> service(db).adminDone(500L, "admin"));
        assertEquals("只有代运营、抖音卖房能在这里标记完成", e.getMessage());
    }

    @Test
    void lodgingVideoPermissionIsRegisteredForJobEditors() throws Exception
    {
        Object permissions = ReflectionTestUtils.getField(KgStayVideoController.class, "PERMISSIONS");
        List<String> list = Arrays.asList(String.valueOf(permissions).split(","));
        assertTrue(list.contains("kangaroo:job:add") && list.contains("kangaroo:job:edit"), list.toString());
    }

    @Test
    void migrationTouchesOnlyTheDefaultTitleOfThisType() throws IOException
    {
        String up = new String(Files.readAllBytes(Paths.get("../sql/kangaroo/V66__forms.sql")), StandardCharsets.UTF_8);
        assertTrue(up.contains("where dict_type = 'kg_staff_role' and dict_value = 'merchant_service'"));
        assertTrue(up.contains("where perms = 'kangaroo:merchantrequest:list'"));
        assertTrue(up.contains("where request_type = 'cloud_chain'"));
        assertTrue(up.contains("where biz_type = 'merchant_request'"));
    }
}
