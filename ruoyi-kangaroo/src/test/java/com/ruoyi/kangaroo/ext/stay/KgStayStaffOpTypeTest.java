package com.ruoyi.kangaroo.ext.stay;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 员工端房源列表带上门店运营类型（kg_store.op_type），页面不再写死「直营」（不连库） */
class KgStayStaffOpTypeTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgStayStaffService service = new KgStayStaffService();
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(service, "db", db);
        ctx.storeId = 102L; ctx.storeName = "测试门店"; ctx.role = "manager";
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L);
        when(db.one(contains("count(*) total"), anyMap())).thenReturn(new HashMap<String, Object>());
    }

    private void store(String opType)
    {
        Map<String, Object> s = new HashMap<String, Object>();
        s.put("opType", opType);
        when(db.one(contains("from kg_store"), anyMap())).thenReturn(s);
    }

    @Test
    void listCarriesStoreOperateType()
    {
        store("managed");
        Map<String, Object> out = service.stays(ctx, null, null);
        assertEquals("managed", out.get("opType"));
        assertEquals("代运营", out.get("operateModeLabel"));
        verify(db).one(contains("from kg_store"), argThat(m -> Long.valueOf(102L).equals(m.get("st"))));

        store("direct");
        assertEquals("直营", service.stays(ctx, null, null).get("operateModeLabel"));
        store("franchise");
        assertEquals("加盟", service.stays(ctx, null, null).get("operateModeLabel"));
        // 2026-11-03 主人：来源类型加「合作」（V70）
        store("partner");
        assertEquals("合作", service.stays(ctx, null, null).get("operateModeLabel"));
    }
}
