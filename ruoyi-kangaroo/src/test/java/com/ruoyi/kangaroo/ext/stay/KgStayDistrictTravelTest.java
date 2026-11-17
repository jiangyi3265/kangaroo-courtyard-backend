package com.ruoyi.kangaroo.ext.stay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.ext.user.KgCityService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 2026-10-17 旅居改版 R2（V51）：区县接口与列表区县筛选、详情的主理人年龄 / 籍贯 / 擅长与主理人评分、出行参考、
 * 员工端保存这几项的校验、员工端表单选项（不连库）
 */
class KgStayDistrictTravelTest
{
    private final KgJdbc db = mock(KgJdbc.class);
    private final KgCityService cities = mock(KgCityService.class);
    private final KgStayBrowseService browse = new KgStayBrowseService();
    private final KgStayStaffService staff = new KgStayStaffService();
    private final StaffSupport support = mock(StaffSupport.class);
    private final StaffSupport.Ctx ctx = new StaffSupport.Ctx();

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @BeforeEach
    void setup()
    {
        ReflectionTestUtils.setField(browse, "db", db);
        ReflectionTestUtils.setField(browse, "cities", cities);
        ReflectionTestUtils.setField(staff, "db", db);
        ReflectionTestUtils.setField(staff, "support", support);
        ReflectionTestUtils.setField(staff, "browse", browse);
        ReflectionTestUtils.setField(staff, "files", mock(com.ruoyi.kangaroo.ext.core.KgPrivateFiles.class));
        when(cities.businessCity(anyString())).thenAnswer(i -> {
            String c = i.getArgument(0);
            return "哈尔滨市".equals(c) ? "哈尔滨" : c.trim();
        });
        ctx.storeId = 102L; ctx.storeName = "测试门店"; ctx.role = "manager";
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(102L); g.setRoleCode("manager"); g.setActive(true);
        ctx.me = new AppLoginUser(); ctx.me.setUserId(1L); ctx.me.setStaffGrants(Collections.singletonList(g));
    }

    // ---------------------------------------------------------------- 区县

    @Test
    void districtNamesComeFromKgCityUnderTheCityFullName()
    {
        when(db.list(contains("from kg_city d where d.level = 'district'"), anyMap()))
                .thenReturn(Arrays.asList(row("name", "道里区"), row("name", "道外区"), row("name", "宾"), row("name", "道外区")));
        assertEquals(Arrays.asList("道里区", "道外区", "宾"), browse.districtNames("哈尔滨市"), "名字原样（去了「县」字的也不改）、去重");
        verify(db).list(argThat((String s) -> s.contains("d.status = '0' and d.parent_name in (select x.full_name from kg_city x where x.name = :c and x.level <> 'district')")
                && s.contains("order by d.city_id")), argThat(m -> "哈尔滨".equals(m.get("c"))));
        assertTrue(browse.districtNames(null).isEmpty());
    }

    @Test
    void districtsReturnOnlyDistrictsWithStaysSortedByCountThenDictionaryOrder()
    {
        when(db.count(contains("select count(*) from kg_stay s where"), anyMap())).thenReturn(6L);
        when(db.list(contains("from kg_city d"), anyMap())).thenReturn(Arrays.asList(row("name", "道里区"), row("name", "南岗区"), row("name", "道外区")));
        when(db.list(contains("group by s.district"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(
                row("district", "道外区", "count", 2L), row("district", "手填的区", "count", 2L), row("district", "南岗区", "count", 2L), row("district", "道里区", "count", 1L))));
        Map<String, Object> out = browse.districts("哈尔滨");
        assertEquals("哈尔滨", out.get("city"));
        assertEquals(6L, out.get("all"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ds = (List<Map<String, Object>>) out.get("districts");
        List<String> names = new ArrayList<String>();
        for (Map<String, Object> d : ds)
        {
            names.add(String.valueOf(d.get("name")));
            assertEquals(Arrays.asList("name", "count"), new ArrayList<String>(d.keySet()), "只带名字和家数，payload 小");
        }
        assertEquals(Arrays.asList("南岗区", "道外区", "手填的区", "道里区"), names, "家数多的在前；一样多按字典顺序，字典里没有的排后面");
        verify(db).list(argThat((String s) -> s.contains(KgStayBrowseService.ONLINE) && s.contains("s.city = :c and s.district <> ''")), anyMap());
        // 没有城市：不查库
        Map<String, Object> none = browse.districts("");
        assertEquals(0L, none.get("all"));
        assertTrue(((List<?>) none.get("districts")).isEmpty());
    }

    @Test
    void listFiltersByExactDistrictAndCarriesItOnCards()
    {
        when(db.one(contains("all_cnt"), anyMap())).thenReturn(row("allCnt", 0L, "directCnt", 0L, "managedCnt", 0L));
        Map<String, Object> page = row("rows", new ArrayList<Map<String, Object>>(), "total", 0L, "hasMore", false);
        when(db.page(anyString(), anyMap(), anyInt(), anyInt())).thenReturn(page);
        Map<String, String> q = new HashMap<String, String>();
        q.put("city", "哈尔滨");
        q.put("district", " 道外区 ");
        browse.list(q, null);
        // 2026-10-17 R3：同一个区县的几种写法都认（字典里没有时就是原样这一个）
        verify(db).page(argThat((String s) -> s.contains(" and s.district in (:districts)") && s.contains("s.tags, s.district, s.area_desc")),
                argThat(m -> Arrays.asList("道外区").equals(m.get("districts"))), anyInt(), anyInt());
        // 不传区县不加条件；关键词也能搜到区县
        Map<String, String> q2 = new HashMap<String, String>();
        q2.put("keyword", "道外");
        browse.list(q2, null);
        verify(db).page(argThat((String s) -> !s.contains(":district") && s.contains("s.district like :kw escape '!'")), anyMap(), anyInt(), anyInt());
    }

    // ---------------------------------------------------------------- 主理人评分

    @Test
    void hostScoreAveragesTheServiceDimensionWithAtLeastThreeReviews()
    {
        assertArrayEquals(new Object[] { 4.7, 3L }, KgStayBrowseService.hostScore(Arrays.<Object>asList("[5, 4, 5, 4, 5]", "[3, 3, 4, 5, 5]", "[5,5,5,5,5]")));
        // 不足 3 条：null（页面不显示），条数照给
        assertArrayEquals(new Object[] { null, 2L }, KgStayBrowseService.hostScore(Arrays.<Object>asList("[5, 4, 5, 4, 5]", "[2, 2, 2, 2, 2]")));
        // 写坏的、不到 3 项的、超出 1–5 的不算
        assertArrayEquals(new Object[] { null, 2L }, KgStayBrowseService.hostScore(Arrays.<Object>asList("[5, 4]", "abc", "[5, 4, 9, 4, 5]", "[5, 4, x, 4, 5]", "[1, 1, 1]", "[\"4\", \"4\", \"4\"]")));
        assertArrayEquals(new Object[] { 2.0, 3L }, KgStayBrowseService.hostScore(Arrays.<Object>asList("[2, 2, 2, 2, 2]", "[2, 2, 2, 2, 2]", "[2, 2, 2, 2, 2]")));
        assertArrayEquals(new Object[] { null, 0L }, KgStayBrowseService.hostScore(new ArrayList<Object>()));
    }

    // ---------------------------------------------------------------- 详情

    @Test
    void detailMovesHostFactsAndTravelNotesIntoTheirOwnObjects()
    {
        Map<String, Object> s = row("stayId", 9L, "storeId", 102L, "stayName", "测试小院", "city", "哈尔滨", "district", " 道外区 ",
                "hostName", "阿东", "hostAvatar", "", "hostIntro", "你好", "hostStory", "", "hostAge", " 90后 ", "hostHometown", "四川成都", "hostSkills", "",
                "travelPros", "步行 5 分钟到中央大街", "travelCons", "", "travelAirport", "机场大巴 1 小时", "travelRail", null, "rating", "4.8", "reviewCount", 3L);
        when(db.one(contains("select s.*, coalesce(st.op_type, 'direct') op_type"), anyMap())).thenReturn(s);
        when(db.list(contains("select r.dims from kg_review r"), anyMap())).thenReturn(Arrays.asList(row("dims", "[5, 4, 5, 4, 5]"), row("dims", "[5, 4, 4, 4, 5]"),
                row("dims", "[5, 4, 4, 4, 5]")));
        Map<String, Object> d = browse.detail(9L, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> host = (Map<String, Object>) d.get("host");
        assertEquals("阿东", host.get("name"));
        assertEquals("90后", host.get("age"));
        assertEquals("四川成都", host.get("hometown"));
        assertEquals("", host.get("skills"));
        assertEquals(4.3, host.get("score"), "服务分 5 / 4 / 4 平均 4.3");
        assertEquals(3L, host.get("scoreCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> travel = (Map<String, Object>) d.get("travel");
        assertEquals(Arrays.asList("pros", "cons", "airport", "rail"), new ArrayList<String>(travel.keySet()));
        assertEquals("步行 5 分钟到中央大街", travel.get("pros"));
        assertEquals("", travel.get("cons"));
        assertEquals("机场大巴 1 小时", travel.get("airport"));
        assertEquals("", travel.get("rail"), "null 也给成空串");
        assertEquals("道外区", d.get("district"));
        for (String k : new String[] { "hostAge", "hostHometown", "hostSkills", "travelPros", "travelCons", "travelAirport", "travelRail" })
        {
            assertFalse(d.containsKey(k), k + " 只在 host / travel 里出现一次");
        }
        verify(db).list(argThat((String q) -> q.contains("r.target_type = 'room' and r.target_id = :s and r.status = '0'") && q.contains("r.dims <> ''")),
                argThat(m -> Long.valueOf(9L).equals(m.get("s"))));
    }

    @Test
    void detailWithoutEnoughReviewsHasNoHostScore()
    {
        when(db.one(contains("select s.*, coalesce(st.op_type, 'direct') op_type"), anyMap())).thenReturn(row("stayId", 9L, "storeId", 102L, "stayName", "测试小院"));
        Map<String, Object> d = browse.detail(9L, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> host = (Map<String, Object>) d.get("host");
        assertNull(host.get("score"));
        assertEquals("", host.get("age"), "老库（V51 前的数据）没有这几列的值：空串");
        @SuppressWarnings("unchecked")
        Map<String, Object> travel = (Map<String, Object>) d.get("travel");
        assertEquals("", travel.get("pros"));
    }

    // ---------------------------------------------------------------- 员工端保存

    private Map<String, Object> savedDraft(Map<String, Object> body)
    {
        when(db.count(contains("stay_name = :n"), anyMap())).thenReturn(0L);
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(row("city", "哈尔滨"));
        when(db.insert(eq("kg_stay"), anyMap())).thenReturn(9L);
        when(db.one(contains("select * from kg_stay where stay_id = :id"), anyMap())).thenReturn(row("stayId", 9L, "storeId", 102L, "status", "draft"));
        staff.save(ctx, null, body);
        @SuppressWarnings({ "unchecked", "rawtypes" })
        org.mockito.ArgumentCaptor<Map<String, Object>> cols = (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(db).insert(eq("kg_stay"), cols.capture());
        return cols.getValue();
    }

    @Test
    void staffSaveTrimsOneLineFieldsAndNormalizesFacilities()
    {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("stayName", "测试小院");
        body.put("district", "  道外区 ");
        body.put("hostAge", " 90后 ");
        body.put("hostHometown", "四川\n成都");
        body.put("hostSkills", "做饭、\t徒步");
        body.put("travelPros", "步行 5 分钟\r\n到中央大街");
        body.put("travelCons", "");
        body.put("travelAirport", "机场大巴   1 小时");
        body.put("travelRail", "哈西站打车 20 分钟");
        body.put("facilities", "parking, study，gym,GYM,study, bad-code ,a");
        Map<String, Object> cols = savedDraft(body);
        assertEquals("道外区", cols.get("district"));
        assertEquals("90后", cols.get("host_age"));
        assertEquals("四川 成都", cols.get("host_hometown"), "换行并成一个空格");
        assertEquals("做饭、 徒步", cols.get("host_skills"));
        assertEquals("步行 5 分钟 到中央大街", cols.get("travel_pros"));
        assertEquals("", cols.get("travel_cons"));
        assertEquals("机场大巴 1 小时", cols.get("travel_airport"));
        assertEquals("哈西站打车 20 分钟", cols.get("travel_rail"));
        assertEquals("parking,study,gym", cols.get("facilities"), "只收小写代码、去重");
        assertEquals("哈尔滨", cols.get("city"));
    }

    @Test
    void staffSaveRejectsTooLongFieldsWithTheirChineseName()
    {
        Object[][] cases = { { "district", 33, "所在区县最多 32 个字" }, { "hostAge", 17, "主理人年龄最多 16 个字" }, { "hostHometown", 33, "主理人籍贯最多 32 个字" },
                { "hostSkills", 129, "主理人擅长最多 128 个字" }, { "travelPros", 256, "出行参考·优点最多 255 个字" }, { "travelRail", 256, "出行参考·高铁怎么到最多 255 个字" } };
        for (Object[] c : cases)
        {
            Map<String, Object> body = new HashMap<String, Object>();
            body.put("stayName", "测试小院");
            body.put(String.valueOf(c[0]), new String(new char[(Integer) c[1]]).replace('\0', '字'));
            ServiceException e = assertThrows(ServiceException.class, () -> staff.save(ctx, null, body));
            assertEquals(c[2], e.getMessage());
        }
        // 正好到上限可以存
        Map<String, Object> ok = new HashMap<String, Object>();
        ok.put("stayName", "测试小院");
        ok.put("travelAirport", new String(new char[255]).replace('\0', '字'));
        assertEquals(255, String.valueOf(savedDraft(ok).get("travel_airport")).length());
    }

    /** 2026-10-17 R3（主人拍板）：补充信息上线中也能直接改；名称、地址、简介等其他内容照旧要先下架 */
    @Test
    @SuppressWarnings("unchecked")
    void onlineStaysTakeSupplementaryEditsDirectlyButCoreEditsStillNeedDelisting()
    {
        Map<String, Object> online = row("stayId", 9L, "storeId", 102L, "status", "online", "stayName", "测试小院", "city", "哈尔滨", "address", "中央大街 1 号",
                "hostStory", "故事", "tags", "安静,近古城", "hostName", "阿东", "latitude", new java.math.BigDecimal("45.770000"), "longitude", new java.math.BigDecimal("126.610000"),
                "district", "", "travelPros", "", "facilities", "parking", "cookable", "0");
        when(db.one(contains("select * from kg_stay where stay_id = :id"), anyMap())).thenReturn(online);
        // 表单把没改的核心字段原样带回来（标签多了空格、坐标少了几位小数也算没改），只有出行参考和设施变了：直接存，不下架、不退回草稿
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("stayName", "测试小院");
        body.put("address", " 中央大街 1 号 ");
        body.put("hostStory", "故事");
        body.put("tags", "安静, 近古城");
        body.put("hostName", "阿东");
        body.put("latitude", "45.77");
        body.put("longitude", 126.61);
        body.put("travelPros", "近中央大街");
        body.put("facilities", "parking,gym");
        body.put("cookable", "0");
        staff.save(ctx, 9L, body);
        ArgumentCaptor<Map<String, Object>> cols = ArgumentCaptor.forClass(Map.class);
        verify(db).updateRow(eq("kg_stay"), eq("stay_id"), eq(9L), cols.capture());
        assertEquals(new java.util.HashSet<String>(Arrays.asList("travel_pros", "facilities", "update_by", "update_time")), cols.getValue().keySet(), "只写真的变了的列");
        assertEquals("近中央大街", cols.getValue().get("travel_pros"));
        verify(db, never()).update(contains("status = 'draft'"), anyMap());
        verify(support).op(eq(ctx), eq("stay_edit_extra"), eq("修改房源补充信息"), eq("general"), eq("测试小院"), eq(true), isNull());
        // 改了名称：照旧要先下架
        Map<String, Object> core = new HashMap<String, Object>(body);
        core.put("stayName", "新名字");
        ServiceException e = assertThrows(ServiceException.class, () -> staff.save(ctx, 9L, core));
        // 2026-10-18：提示里写清是哪一项要先下架（「房源名称」），也说明哪些能直接改
        assertTrue(e.getMessage().contains("「房源名称」") && e.getMessage().contains("要先下架") && e.getMessage().contains("出行参考"), e.getMessage());
        verify(db, times(1)).updateRow(anyString(), anyString(), any(), anyMap());
        // 改了简介和标签：两项都点名
        Map<String, Object> core2 = new HashMap<String, Object>(body);
        core2.put("hostStory", "新的简介");
        core2.put("tags", "安静,海边");
        ServiceException e2 = assertThrows(ServiceException.class, () -> staff.save(ctx, 9L, core2));
        assertTrue(e2.getMessage().contains("「房源简介、特色标签」"), e2.getMessage());
        // 2026-10-18 主人真机：改主理人称呼也算主理人信息，上线中直接存
        Map<String, Object> host = new HashMap<String, Object>(body);
        host.put("hostName", "小院阿木");
        staff.save(ctx, 9L, host);
        verify(db, times(2)).updateRow(eq("kg_stay"), eq("stay_id"), eq(9L), cols.capture());
        assertEquals("小院阿木", cols.getValue().get("host_name"));
        // 什么都没变：不写库
        Map<String, Object> same = new HashMap<String, Object>();
        same.put("stayName", "测试小院");
        staff.save(ctx, 9L, same);
        verify(db, times(2)).updateRow(anyString(), anyString(), any(), anyMap());
    }

    @Test
    void offlineStaysStayOfflineWhenOnlySupplementaryFieldsChange()
    {
        when(db.one(contains("select * from kg_stay where stay_id = :id"), anyMap())).thenReturn(row("stayId", 9L, "storeId", 102L, "status", "offline", "stayName", "测试小院",
                "city", "哈尔滨", "hostAge", ""));
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("hostAge", "90后");
        staff.save(ctx, 9L, body);
        verify(db).updateRow(eq("kg_stay"), eq("stay_id"), eq(9L), argThat(m -> "90后".equals(m.get("host_age"))));
        verify(db, never()).update(contains("set status = 'draft'"), anyMap());
        // 下架状态改核心内容：照旧退回草稿
        Map<String, Object> core = new HashMap<String, Object>();
        core.put("stayName", "新名字");
        staff.save(ctx, 9L, core);
        verify(db).update(contains("set status = 'draft'"), anyMap());
    }

    // ---------------------------------------------------------------- 区县全称（2026-10-17 R3）

    /** 哈尔滨的区县：道里区（简称全称一样）、宾（全称宾县） */
    private void harbinDistricts()
    {
        when(db.list(contains("from kg_city d where d.level = 'district'"), anyMap()))
                .thenReturn(Arrays.asList(row("name", "道里区", "fullName", "道里区"), row("name", "宾", "fullName", "宾县"), row("name", "朝阳区·北京", "fullName", "")));
    }

    @Test
    void districtNamesAreFullNamesAndShortNamesMapToThem()
    {
        harbinDistricts();
        assertEquals(Arrays.asList("道里区", "宾县", "朝阳区·北京"), browse.districtNames("哈尔滨"), "全称；full_name 空着时退回 name");
        Map<String, String> m = browse.districtMap("哈尔滨");
        assertEquals("宾县", KgStayBrowseService.fullDistrict(m, "宾"));
        assertEquals("宾县", KgStayBrowseService.fullDistrict(m, " 宾县 "));
        assertEquals("手填的区", KgStayBrowseService.fullDistrict(m, "手填的区"), "字典里没有的原样");
        assertEquals(Arrays.asList("宾县", "宾"), KgStayBrowseService.districtAliases(m, "宾县"));
        assertEquals(Arrays.asList("宾", "宾县"), KgStayBrowseService.districtAliases(m, "宾"));
        verify(db, atLeastOnce()).list(argThat((String s) -> s.contains("select d.name, d.full_name from kg_city d")), anyMap());
    }

    @Test
    void districtCountsMergeShortAndFullNamesAndListMatchesEither()
    {
        harbinDistricts();
        when(db.count(contains("select count(*) from kg_stay s where"), anyMap())).thenReturn(5L);
        when(db.list(contains("group by s.district"), anyMap())).thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(
                row("district", "宾", "count", 2L), row("district", "宾县", "count", 1L), row("district", "道里区", "count", 2L))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ds = (List<Map<String, Object>>) browse.districts("哈尔滨").get("districts");
        assertEquals(2, ds.size(), ds.toString());
        assertEquals("宾县", ds.get(0).get("name"));
        assertEquals(3L, ds.get(0).get("count"), "存简称的 2 家 + 存全称的 1 家");
        assertEquals("道里区", ds.get(1).get("name"));
        // 按全称筛：简称、全称两种写法的房源都出来
        when(db.one(contains("all_cnt"), anyMap())).thenReturn(row("allCnt", 0L, "directCnt", 0L, "managedCnt", 0L));
        when(db.page(anyString(), anyMap(), anyInt(), anyInt())).thenReturn(row("rows", new ArrayList<Map<String, Object>>(), "total", 0L, "hasMore", false));
        Map<String, String> q = new HashMap<String, String>();
        q.put("city", "哈尔滨");
        q.put("district", "宾县");
        browse.list(q, null);
        verify(db).page(contains(" and s.district in (:districts)"), argThat(m -> Arrays.asList("宾县", "宾").equals(m.get("districts"))), anyInt(), anyInt());
    }

    @Test
    void staffFormAndSaveUseTheFullName()
    {
        harbinDistricts();
        // 每次查都是新的一行（stay() 会往返回的行里写全称，真库每次查出来都是新的）
        when(db.one(contains("select * from kg_stay where stay_id = :id"), anyMap())).thenAnswer(i -> row("stayId", 9L, "storeId", 102L, "city", "哈尔滨", "status", "draft",
                "stayName", "测试小院", "district", "宾"));
        when(db.list(contains("select distinct district from kg_stay"), anyMap())).thenReturn(Arrays.asList(row("district", "宾"), row("district", "老城")));
        assertEquals(Arrays.asList("道里区", "宾县", "朝阳区·北京", "老城"), staff.formOptions(ctx, 9L).get("districts"), "存的简称折成全称，不重复");
        assertEquals("宾县", staff.stay(ctx, 9L).get("district"), "编辑表单显示全称");
        // 选了简称（老版本页面）也存成全称
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("district", "宾");
        body.put("stayName", "测试小院");
        staff.save(ctx, 9L, body);
        verify(db).updateRow(eq("kg_stay"), eq("stay_id"), eq(9L), argThat(m -> "宾县".equals(m.get("district")) && !m.containsKey("stay_name")));
    }

    @Test
    void detailShowsTheFullDistrictName()
    {
        harbinDistricts();
        when(db.one(contains("select s.*, coalesce(st.op_type, 'direct') op_type"), anyMap())).thenReturn(row("stayId", 9L, "storeId", 102L, "stayName", "测试小院",
                "city", "哈尔滨", "district", "宾", "introVideo", "/profile/upload/videos/2026/10/17/0123456789abcdef0123456789abcdef.mp4", "introVideoCover", "",
                "cover", "/profile/upload/2026/10/17/c.jpg", "diaryBg", "x", "diaryTitle", "", "diaryIntro", ""));
        Map<String, Object> d = browse.detail(9L, null);
        assertEquals("宾县", d.get("district"));
        @SuppressWarnings("unchecked")
        Map<String, Object> iv = (Map<String, Object>) d.get("introVideo");
        assertEquals("/profile/upload/2026/10/17/c.jpg", iv.get("cover"));
        for (String k : new String[] { "introVideoCover", "diaryBg", "diaryTitle", "diaryIntro" })
        {
            assertFalse(d.containsKey(k), k);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> diary = (Map<String, Object>) d.get("diary");
        assertEquals(0L, diary.get("count"), "单测里没有 KgStayMediaService：摘要按没有算");
    }

    @Test
    void formOptionsListTheCityDistrictsPlusHandTypedOnesAndTheFacilityDictionary()
    {
        when(db.one(contains("select * from kg_stay where stay_id = :id"), anyMap())).thenReturn(row("stayId", 9L, "storeId", 102L, "city", "哈尔滨"));
        when(db.list(contains("from kg_city d"), anyMap())).thenReturn(Arrays.asList(row("name", "道里区"), row("name", "道外区")));
        when(db.list(contains("select distinct district from kg_stay"), anyMap())).thenReturn(Arrays.asList(row("district", "道外区"), row("district", "老城")));
        when(db.list(contains("from sys_dict_data where dict_type = 'kg_stay_facility' and status = '0'"), anyMap()))
                .thenReturn(Arrays.asList(row("value", "parking", "label", "停车"), row("value", "study", "label", "学习室"), row("value", "gym", "label", "健身房")));
        Map<String, Object> out = staff.formOptions(ctx, 9L);
        assertEquals("哈尔滨", out.get("city"));
        assertEquals(Arrays.asList("道里区", "道外区", "老城"), out.get("districts"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facs = (List<Map<String, Object>>) out.get("facilities");
        assertEquals("学习室", facs.get(1).get("label"));
        // 新建房源：按门店城市
        when(db.one(contains("select city from kg_store"), anyMap())).thenReturn(row("city", "哈尔滨市"));
        assertEquals("哈尔滨市", staff.formOptions(ctx, null).get("city"));
        verify(db, atLeastOnce()).list(contains("from kg_city d"), argThat(m -> "哈尔滨".equals(m.get("c"))));
    }

    @Test
    void adminOneLineUsesTheSameColumns()
    {
        Map<String, Object> cols = new HashMap<String, Object>();
        cols.put("travel_cons", "  巷子窄\n车开不进来 ");
        cols.put("district", " 道外区 ");
        cols.put("host_story", "第一段\n第二段");
        KgStayModule.oneLine(cols);
        assertEquals("巷子窄 车开不进来", cols.get("travel_cons"));
        assertEquals("道外区", cols.get("district"));
        assertEquals("第一段\n第二段", cols.get("host_story"), "小院故事是多段文字，不并行");
        assertEquals(8, KgStayStaffService.ONE_LINE.size());
    }
}
