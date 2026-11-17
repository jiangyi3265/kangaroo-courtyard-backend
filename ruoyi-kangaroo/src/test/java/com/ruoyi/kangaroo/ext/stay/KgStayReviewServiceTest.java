package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 小院「住过的家人评价」（2026-10-17 改版 R2）：综合分 / 推荐率 / 分项的算法和门槛、「大家提到」关键词统计（去小标题、去否定）、
 * 页签和筛选（差评、有图、房型、入住月份、提到）、匿名、参数校验、只取显示中的评价。
 */
class KgStayReviewServiceTest
{
    static Map<String, Object> rv(long id, int rating, String content, String dims, String images, Long roomTypeId, String roomName, String checkIn)
    {
        Map<String, Object> r = p("reviewId", id, "rating", rating, "content", content, "dims", dims, "images", images, "roomTypeId", roomTypeId,
                "roomTypeName", roomName, "checkIn", checkIn, "anonymous", "1", "featured", "0", "createTime", "2026-10-12 09:30:00");
        return r;
    }

    static List<Map<String, Object>> sample()
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(rv(1, 5, "【卫生】房间很干净\n【安静舒适】晚上特别安静，院子里绿植很多", "[5, 5, 5, 5, 5]", "/profile/upload/2026/10/12/a.jpg", 11L, "庭院大床房", "2026-09-20"));
        rows.add(rv(2, 5, "【卫生】干净整洁\n【服务】老板很热情", "[5, 4, 5, 4, 5]", null, 11L, "庭院大床房", "2026-09-02"));
        rows.add(rv(3, 4, "【安静舒适】很安静，早餐好吃", "[4, 4, 4, 4, 4]", "", 12L, "山景双床房", "2026-08-15"));
        rows.add(rv(4, 3, "【安静舒适】不太安静，没有早餐", "[3, 3, 3, 3, 3]", null, 12L, "山景双床房", "2026-10-01"));
        rows.add(rv(5, 1, "【卫生】不干净", null, "/profile/upload/2026/10/12/b.jpg,https://evil.example.com/x.jpg", null, null, null));
        return rows;
    }

    @Test
    void summaryMathAverageRecommendRateAndDims()
    {
        Map<String, Object> s = KgStayReviewService.summarize(sample());
        assertEquals(5L, s.get("count"));
        // (5 + 5 + 4 + 3 + 1) / 5 = 3.6
        assertEquals(3.6, (Double) s.get("avg"), 1e-9);
        // 4 星及以上 3 条 / 5 条
        assertEquals(60L, s.get("recommendRate"));
        assertEquals(4, s.get("dimsCount"), "第 5 条没有分项，不算");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dims = (List<Map<String, Object>>) s.get("dims");
        assertEquals(Arrays.asList("卫生", "设施", "服务", "安静", "位置"), Arrays.asList(dims.stream().map(d -> d.get("label")).toArray()));
        // 卫生 (5+5+4+3)/4 = 4.25 → 4.3（四舍五入，与 MySQL round 一致）；设施 (5+4+4+3)/4 = 4.0
        assertEquals(4.3, (Double) dims.get(0).get("avg"), 1e-9);
        assertEquals(4.0, (Double) dims.get(1).get("avg"), 1e-9);
        @SuppressWarnings("unchecked")
        Map<String, Object> tabs = (Map<String, Object>) s.get("tabs");
        assertEquals(5L, tabs.get("all"));
        assertEquals(2L, tabs.get("media"));
        assertEquals(1L, tabs.get("bad"));
    }

    @Test
    void thresholdsHideRecommendRateAndDimsWhenTooFewReviews()
    {
        List<Map<String, Object>> rows = sample().subList(0, 2);
        Map<String, Object> s = KgStayReviewService.summarize(rows);
        assertNull(s.get("recommendRate"), "不足 5 条不给推荐率");
        assertEquals(5, s.get("recommendMin"));
        assertEquals(Collections.emptyList(), s.get("dims"), "带分项的不足 3 条不给分项");
        Map<String, Object> empty = KgStayReviewService.summarize(new ArrayList<Map<String, Object>>());
        assertEquals(0L, empty.get("count"));
        assertNull(empty.get("avg"));
        assertNull(empty.get("recommendRate"));
        assertEquals(Collections.emptyList(), empty.get("mentions"));
    }

    @Test
    void avgRoundsHalfUpWithoutFloatingDrift()
    {
        assertEquals(4.3, KgStayReviewService.avg1(17, 4), 1e-9);
        assertEquals(4.5, KgStayReviewService.avg1(89, 20), 1e-9);
        assertEquals(4.8, KgStayReviewService.avg1(24, 5), 1e-9);
        assertNull(KgStayReviewService.avg1(0, 0));
    }

    @Test
    void dimsParseOnlyCompleteFiveItemLists()
    {
        assertArrayEquals(new int[] { 5, 4, 5, 4, 5 }, KgStayReviewService.dims("[5, 4, 5, 4, 5]"));
        assertNull(KgStayReviewService.dims(null));
        assertNull(KgStayReviewService.dims("[5, 4]"));
        assertNull(KgStayReviewService.dims("[5, 4, 5, 4, 9]"));
        assertNull(KgStayReviewService.dims("[a, 4, 5, 4, 5]"));
    }

    @Test
    void mentionsIgnoreSectionTitlesAndNegations()
    {
        // 小标题【卫生】【安静舒适】【设施相符】本身不算提到
        assertEquals(Collections.emptyList(), KgStayReviewService.mentionsOf("【卫生】还行\n【设施相符】一般\n【安静舒适】一般"));
        assertEquals(Arrays.asList("小院安静", "房间干净"), KgStayReviewService.mentionsOf("【卫生】房间很干净\n【安静舒适】晚上很安静"));
        // 否定的不算
        assertEquals(Collections.emptyList(), KgStayReviewService.mentionsOf("不太安静，没有早餐，不干净，交通不方便"));
        // 后面跟着差评说法的也不算；「不错」「不吵」是好话
        assertEquals(Collections.emptyList(), KgStayReviewService.mentionsOf("早餐一般，氛围很差，设施不齐全"));
        assertEquals(Arrays.asList("小院安静", "早餐好吃"), KgStayReviewService.mentionsOf("早餐不错，晚上不吵"));
        // 同一条里先否定后肯定：只要有一处不带否定就算
        assertEquals(Arrays.asList("小院安静"), KgStayReviewService.mentionsOf("白天不安静，晚上很安静"));
        assertEquals(Arrays.asList("主理人热情", "早餐好吃"), KgStayReviewService.mentionsOf("老板很热情，早餐好吃"));
        assertEquals(Arrays.asList("绿植很多", "氛围治愈"), KgStayReviewService.mentionsOf("满院花草，氛围特别治愈"));
        assertEquals(Collections.emptyList(), KgStayReviewService.mentionsOf(null));
    }

    @Test
    void mentionChipsNeedTwoReviewsSortByCountAndCapAtSix()
    {
        Map<String, Object> s = KgStayReviewService.summarize(sample());
        // 小院安静：1、3（4 是「不太安静」）；房间干净：1、2（5 是「不干净」）；早餐好吃只有 3 一条、主理人热情只有 2 一条 → 不出现
        assertEquals("[{label=小院安静, count=2}, {label=房间干净, count=2}]", s.get("mentions").toString());

        List<Map<String, Object>> many = new ArrayList<Map<String, Object>>();
        String all = "安静 干净 绿植 客厅 齐全 治愈 热情 早餐 交通 床很舒服 风景 划算 做饭";
        for (int i = 0; i < 3; i++)
        {
            many.add(rv(100 + i, 5, all, null, null, null, null, null));
        }
        many.add(rv(200, 5, "早餐 交通", null, null, null, null, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chips = (List<Map<String, Object>>) KgStayReviewService.summarize(many).get("mentions");
        assertEquals(6, chips.size());
        // 次数多的在前（早餐好吃、交通方便各 4 次），其余按词表顺序
        assertEquals("早餐好吃", chips.get(0).get("label"));
        assertEquals(4, chips.get(0).get("count"));
        assertEquals("交通方便", chips.get(1).get("label"));
        assertEquals("小院安静", chips.get(2).get("label"));
        assertEquals("公共区域舒服", chips.get(5).get("label"));
    }

    @Test
    void roomTypeAndMonthOptionsComeFromReviewOrders()
    {
        Map<String, Object> s = KgStayReviewService.summarize(sample());
        assertEquals("[{roomTypeId=11, name=庭院大床房, count=2}, {roomTypeId=12, name=山景双床房, count=2}]", s.get("roomTypes").toString());
        // 新的月份在前，没有订单的评价不出月份
        assertEquals("[{month=2026-10, label=2026年10月, count=1}, {month=2026-09, label=2026年9月, count=2}, {month=2026-08, label=2026年8月, count=1}]",
                s.get("months").toString());
    }

    private static List<Object> ids(List<Map<String, Object>> rows)
    {
        List<Object> out = new ArrayList<Object>();
        for (Map<String, Object> r : rows)
        {
            out.add(r.get("reviewId"));
        }
        return out;
    }

    @Test
    void filtersBadMediaRoomTypeMonthAndMention()
    {
        List<Map<String, Object>> rows = sample();
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L), ids(KgStayReviewService.filter(rows, null, null, null, null)));
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L), ids(KgStayReviewService.filter(rows, "all", null, "", " ")));
        assertEquals(Arrays.asList(5L), ids(KgStayReviewService.filter(rows, "bad", null, null, null)));
        // 有图：空串 / null 不算；外链被过滤掉但同条还有平台图，仍算有图
        assertEquals(Arrays.asList(1L, 5L), ids(KgStayReviewService.filter(rows, "media", null, null, null)));
        assertEquals(Arrays.asList(3L, 4L), ids(KgStayReviewService.filter(rows, null, 12L, null, null)));
        assertEquals(Arrays.asList(1L, 2L), ids(KgStayReviewService.filter(rows, null, null, "2026-09", null)));
        // 「小院安静」和汇总里的次数一致：「不太安静」那条不出
        assertEquals(Arrays.asList(1L, 3L), ids(KgStayReviewService.filter(rows, null, null, null, "小院安静")));
        assertEquals(Arrays.asList(1L), ids(KgStayReviewService.filter(rows, "media", 11L, "2026-09", "房间干净")));
        assertEquals(Collections.emptyList(), ids(KgStayReviewService.filter(rows, "bad", 11L, null, null)));
    }

    @Test
    void invalidParamsAre422()
    {
        KgStayReviewService.checkParams(null, null, null, 1, 10);
        KgStayReviewService.checkParams("media", "2026-09", "小院安静", 2, 20);
        for (Runnable bad : Arrays.<Runnable>asList(
                () -> KgStayReviewService.checkParams("good", null, null, 1, 10),
                () -> KgStayReviewService.checkParams(null, "2026-13", null, 1, 10),
                () -> KgStayReviewService.checkParams(null, "2026-9", null, 1, 10),
                () -> KgStayReviewService.checkParams(null, null, "随便写的", 1, 10),
                () -> KgStayReviewService.checkParams(null, null, null, 0, 10),
                () -> KgStayReviewService.checkParams(null, null, null, 1, 21)))
        {
            ServiceException e = assertThrows(ServiceException.class, bad::run);
            assertEquals(Integer.valueOf(422), e.getCode());
        }
    }

    @Test
    void cardHidesAnonymousIdentityAndShapesFields()
    {
        Map<String, Object> r = rv(9, 4, "【服务】老板很热情", null, "/profile/upload/2026/10/12/a.jpg,https://evil.example.com/x.jpg,,/profile/upload/2026/10/12/a.jpg",
                11L, "庭院大床房", "2026-09-20");
        r.put("nickname", "小鹿");
        r.put("avatar", "/profile/avatar/1.jpg");
        r.put("tags", "回头客，安静,");
        r.put("reply", "谢谢家人");
        r.put("replyTime", "2026-10-13 10:00:00");
        Map<String, Object> anon = KgStayReviewService.card(r);
        assertEquals("匿名住客", anon.get("name"));
        assertEquals("", anon.get("avatar"));
        assertEquals(Boolean.TRUE, anon.get("anonymous"));
        assertEquals(Arrays.asList("/profile/upload/2026/10/12/a.jpg"), anon.get("images"), "外链不放，重复的只留一张");
        assertEquals(Collections.emptyList(), anon.get("videos"));
        assertEquals(Arrays.asList("回头客", "安静"), anon.get("tags"));
        assertEquals(Arrays.asList("主理人热情"), anon.get("mentions"));
        assertEquals("庭院大床房", anon.get("roomType"));
        assertEquals("2026年9月入住", anon.get("stayMonth"));
        assertEquals("2026.10", anon.get("date"));
        assertEquals("谢谢家人", anon.get("reply"));
        assertEquals("2026.10.13", anon.get("replyDate"));
        assertFalse(anon.toString().contains("小鹿"));

        r.put("anonymous", "0");
        Map<String, Object> named = KgStayReviewService.card(r);
        assertEquals("小鹿", named.get("name"));
        assertEquals("/profile/avatar/1.jpg", named.get("avatar"));
        r.put("nickname", "");
        assertEquals("住客", KgStayReviewService.card(r).get("name"));

        Map<String, Object> noOrder = KgStayReviewService.card(rv(10, 5, "好", null, null, null, null, null));
        assertEquals("", noOrder.get("roomType"));
        assertEquals("", noOrder.get("stayMonth"));
        assertNull(noOrder.get("replyDate"));
    }

    @Test
    void pageSlicesAndReportsHasMore()
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 23; i++)
        {
            rows.add(rv(i, 5, "好", null, null, null, null, null));
        }
        Map<String, Object> p1 = KgStayReviewService.page(rows, 1, 10);
        assertEquals(10, ((List<?>) p1.get("rows")).size());
        assertEquals(23, p1.get("total"));
        assertEquals(Boolean.TRUE, p1.get("hasMore"));
        Map<String, Object> p3 = KgStayReviewService.page(rows, 3, 10);
        assertEquals(3, ((List<?>) p3.get("rows")).size());
        assertEquals(Boolean.FALSE, p3.get("hasMore"));
        assertEquals(0, ((List<?>) KgStayReviewService.page(rows, 9, 10).get("rows")).size());
    }

    private KgStayReviewService service(KgJdbc db)
    {
        KgStayReviewService s = new KgStayReviewService();
        ReflectionTestUtils.setField(s, "db", db);
        return s;
    }

    @Test
    void onlyVisibleReviewsOfThisOnlineStayAreRead()
    {
        KgJdbc db = mock(KgJdbc.class);
        when(db.one(anyString(), anyMap())).thenReturn(p("stayId", 7L, "stayName", "平潭袋鼠小院"));
        when(db.list(anyString(), anyMap())).thenReturn(sample());
        KgStayReviewService svc = service(db);
        Map<String, Object> s = svc.summary(7L);
        assertEquals("平潭袋鼠小院", s.get("stayName"));
        assertEquals(5L, s.get("count"));

        ArgumentCaptor<String> stay = ArgumentCaptor.forClass(String.class);
        verify(db).one(stay.capture(), eq(p("id", 7L)));
        assertTrue(stay.getValue().contains(KgStayBrowseService.ONLINE), "下架 / 删除的小院不给看，和详情页同一口径");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).list(sql.capture(), eq(p("s", 7L)));
        String q = sql.getValue();
        assertTrue(q.contains("v.target_type = 'room' and v.target_id = :s and v.status = '0' and v.del_flag = '0'"), q);
        assertTrue(q.contains("left join kg_stay_order o on v.biz_type = 'stay_order' and o.order_id = v.biz_id and o.stay_id = v.target_id"), q);
        assertTrue(q.contains("left join kg_stay_room_type t on t.room_type_id = o.room_type_id"), q);
        assertTrue(q.contains("order by v.featured desc, v.review_id desc limit " + KgStayReviewService.MAX_ROWS), q);

        Map<String, Object> page = svc.list(7L, "bad", null, null, null, 1, 10);
        assertEquals(1, page.get("total"));
        assertEquals("匿名住客", ((Map<?, ?>) ((List<?>) page.get("rows")).get(0)).get("name"));
    }

    @Test
    void offlineOrMissingStayIs404AndBadParamsSkipTheDatabase()
    {
        KgJdbc db = mock(KgJdbc.class);
        // Mockito 对返回 Map 的方法默认给空 Map，这里明确「查不到在架的小院」
        when(db.one(anyString(), anyMap())).thenReturn(null);
        KgStayReviewService svc = service(db);
        ServiceException e = assertThrows(ServiceException.class, () -> svc.summary(7L));
        assertEquals(Integer.valueOf(404), e.getCode());
        assertEquals(Integer.valueOf(404), assertThrows(ServiceException.class, () -> svc.list(7L, null, null, null, null, 1, 10)).getCode());
        assertEquals(Integer.valueOf(422), assertThrows(ServiceException.class, () -> svc.list(7L, "x", null, null, null, 1, 10)).getCode());
        verify(db, never()).list(anyString(), anyMap());
    }
}
