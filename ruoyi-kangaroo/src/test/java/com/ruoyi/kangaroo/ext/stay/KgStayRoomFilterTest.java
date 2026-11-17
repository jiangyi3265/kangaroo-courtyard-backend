package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 旅居搜索的房型条件：单人间 / 双人间 / 男女多人间 / 独卫公卫落在同一个房型上，词走参数绑定（不连库） */
class KgStayRoomFilterTest
{
    private static Map<String, String> q(String... kv)
    {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void nothingSelectedAddsNothing()
    {
        Map<String, Object> ps = new HashMap<String, Object>();
        assertEquals("", KgStayBrowseService.roomTypeClause(q(), ps));
        assertEquals("", KgStayBrowseService.roomTypeClause(q("roomKind", "x", "roomSize", "huge", "bath", "maybe"), ps));
        assertTrue(ps.isEmpty());
    }

    @Test
    void roomKindStillWorksAsBefore()
    {
        Map<String, Object> ps = new HashMap<String, Object>();
        String c = KgStayBrowseService.roomTypeClause(q("roomKind", "shared"), ps);
        assertTrue(c.startsWith(" and exists (select 1 from kg_stay_room_type t where t.stay_id = s.stay_id and t.status = '0' and t.del_flag = '0'"), c);
        assertTrue(c.contains("t.room_kind = :rk"), c);
        assertEquals("shared", ps.get("rk"));
    }

    @Test
    void singleRoomWithPrivateBathIsOneRoomType()
    {
        Map<String, Object> ps = new HashMap<String, Object>();
        String c = KgStayBrowseService.roomTypeClause(q("roomSize", "single", "bath", "private"), ps);
        // 只有一个 exists：两个条件必须落在同一个房型上
        assertEquals(1, c.split("exists", -1).length - 1, c);
        assertTrue(c.contains("t.room_kind = 'private' and t.capacity = 1"), c);
        assertTrue(c.contains("t.room_kind = 'whole' or (t.room_kind = 'private' and find_in_set('bath', s.facilities)) or (concat_ws(',', t.type_name, t.summary, t.features) like :bw0"), c);
        assertEquals("%独立卫浴%", ps.get("bw0"));
        assertTrue(ps.containsValue("%独卫%"));
        assertFalse(c.contains("独"), "词要走参数绑定，不能拼进 SQL");
    }

    @Test
    void doubleRoomAndSharedBath()
    {
        Map<String, Object> ps = new HashMap<String, Object>();
        String c = KgStayBrowseService.roomTypeClause(q("roomSize", "double", "bath", "shared"), ps);
        assertTrue(c.contains("t.capacity = 2"), c);
        assertTrue(ps.containsValue("%公卫%") && ps.containsValue("%公共卫浴%"));
        assertFalse(c.contains("'whole' or"), "公卫不把整院算进来：" + c);
    }

    @Test
    void femaleAndMaleDorms()
    {
        Map<String, Object> ps = new HashMap<String, Object>();
        String c = KgStayBrowseService.roomTypeClause(q("roomSize", "dorm_female"), ps);
        assertTrue(c.contains("t.room_kind = 'shared' and (concat_ws"), c);
        assertTrue(c.contains("not like '%混住%'"), c);
        assertTrue(ps.containsValue("%女生%") && !ps.containsValue("%男生%"));
        ps.clear();
        KgStayBrowseService.roomTypeClause(q("roomSize", "dorm_male", "bath", "shared"), ps);
        assertTrue(ps.containsValue("%男生%") && ps.containsValue("%公卫%"));
    }
}
