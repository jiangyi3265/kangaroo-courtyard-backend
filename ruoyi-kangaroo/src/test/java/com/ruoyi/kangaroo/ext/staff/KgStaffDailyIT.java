package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 员工端「每日概况」真实 SQL（2026-10-17）：用 e2e/test_staff_daily_database.py 跑——它从验收库只复制表结构建一个一次性库
 * kg_daily_test_xxxxxxxxxxxx，跑完删掉；这里写的订单 / 报名都是假数据，不碰任何业务库。
 * 没有 KG_DAILY_TEST_DB 环境变量时直接跳过（普通 mvn test 不需要数据库）。
 */
class KgStaffDailyIT
{
    static final LocalDate D = LocalDate.of(2026, 10, 17);

    private static StaffGrantView grant(long store, String role)
    {
        StaffGrantView g = new StaffGrantView();
        g.setStoreId(store);
        g.setStoreName("店" + store);
        g.setRoleCode(role);
        g.setActive(true);
        return g;
    }

    private static long order(KgJdbc db, long id, long stay, long store, long type, String status, LocalDate in, LocalDate out, int units,
            String paid, String checkedOut)
    {
        Map<String, Object> o = p("order_id", id, "order_no", "DT" + id, "stay_id", stay, "store_id", store, "room_type_id", type, "app_user_id", 9001L,
                "check_in", in.toString(), "check_out", out.toString(), "nights", (int) (out.toEpochDay() - in.toEpochDay()), "guests", units + 1, "units", units,
                "guest_name", "测试住客", "guest_phone", "13800000000", "room_fen", 1000L, "pay_fen", 1000L, "status", status, "del_flag", "0",
                "create_time", (paid == null ? D.minusDays(5) + " 10:00:00" : paid));
        if (paid != null)
        {
            o.put("paid_time", paid);
        }
        if (checkedOut != null)
        {
            o.put("checked_out_time", checkedOut);
        }
        return db.insert("kg_stay_order", o);
    }

    private static void app(KgJdbc db, long id, long store, long job, String status, String submit, String storeConfirm, String arrival, String onboard)
    {
        Map<String, Object> a = p("app_id", id, "app_no", "AP" + id, "job_id", job, "store_id", store, "app_user_id", 8000L + id, "real_name", "报名人" + id, "phone", "13900000000",
                "status", status, "submit_time", submit, "del_flag", "0");
        if (storeConfirm != null) a.put("store_confirm_time", storeConfirm);
        if (arrival != null) a.put("arrival_date", arrival);
        if (onboard != null) a.put("onboard_time", onboard);
        db.insert("kg_application", a);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsFromRealSql()
    {
        String schema = System.getenv("KG_DAILY_TEST_DB");
        org.junit.jupiter.api.Assumptions.assumeTrue(schema != null, "只在 e2e/test_staff_daily_database.py 里跑");
        assertTrue(schema.matches("kg_daily_test_[a-f0-9]{12}"), schema);
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai", "root", System.getenv("MYSQL_PWD"));
        KgJdbc db = new KgJdbc(ds);
        // 两家授权门店使用当前拥有旅居权限的小院主理人；104 没授权，数字不能混进来。
        db.insert("kg_store", p("store_id", 102L, "store_name", "平潭测试店", "city", "平潭", "address", "测试路", "status", "0", "del_flag", "0"));
        db.insert("kg_store", p("store_id", 103L, "store_name", "大理测试店", "city", "大理", "address", "测试路", "status", "0", "del_flag", "0"));
        db.insert("kg_store", p("store_id", 104L, "store_name", "无授权店", "city", "平潭", "address", "测试路", "status", "0", "del_flag", "0"));
        db.insert("kg_stay", p("stay_id", 6L, "store_id", 102L, "stay_name", "海边小院", "city", "平潭", "status", "online", "del_flag", "0", "sort", 1));
        db.insert("kg_stay", p("stay_id", 7L, "store_id", 103L, "stay_name", "古城小院", "city", "大理", "status", "online", "del_flag", "0", "sort", 1));
        db.insert("kg_stay", p("stay_id", 8L, "store_id", 104L, "stay_name", "别人家小院", "city", "平潭", "status", "online", "del_flag", "0", "sort", 1));
        // 房型：海边小院 6 间 + 4 床位（床位房型当天单独设库存 2）+ 一个下架房型（不算库存）；古城小院 5 间
        db.insert("kg_stay_room_type", p("room_type_id", 31L, "stay_id", 6L, "type_name", "大床房", "capacity", 2, "unit_label", "间", "stock", 6, "base_price_fen", 10000L, "status", "0", "del_flag", "0"));
        db.insert("kg_stay_room_type", p("room_type_id", 32L, "stay_id", 6L, "type_name", "多人间", "capacity", 1, "unit_label", "床位", "stock", 4, "base_price_fen", 5000L, "status", "0", "del_flag", "0"));
        db.insert("kg_stay_room_type", p("room_type_id", 33L, "stay_id", 6L, "type_name", "下架房", "capacity", 1, "unit_label", "间", "stock", 9, "base_price_fen", 5000L, "status", "1", "del_flag", "0"));
        db.insert("kg_stay_room_type", p("room_type_id", 41L, "stay_id", 7L, "type_name", "庭院房", "capacity", 2, "unit_label", "间", "stock", 5, "base_price_fen", 10000L, "status", "0", "del_flag", "0"));
        db.insert("kg_stay_room_type", p("room_type_id", 51L, "stay_id", 8L, "type_name", "别人家", "capacity", 2, "unit_label", "间", "stock", 5, "base_price_fen", 10000L, "status", "0", "del_flag", "0"));
        db.insert("kg_stay_calendar", p("room_type_id", 32L, "stay_id", 6L, "stay_date", D.toString(), "price_fen", 5000L, "stock", 2, "occupied", 1, "closed", "0"));
        db.insert("kg_stay_calendar", p("room_type_id", 31L, "stay_id", 6L, "stay_date", D.plusDays(1).toString(), "price_fen", 5000L, "stock", 1, "occupied", 0, "closed", "0"));

        String paidToday = D + " 08:30:00";
        order(db, 1, 6, 102, 31, "confirmed", D, D.plusDays(2), 2, paidToday, null);           // 今日入住（待到店）+ 在住 2 间 + 新订单
        order(db, 2, 6, 102, 31, "checked_in", D.minusDays(1), D.plusDays(1), 1, D.minusDays(3) + " 10:00:00", null); // 在住 1 间
        order(db, 3, 6, 102, 32, "checked_in", D.minusDays(2), D, 1, D.minusDays(4) + " 10:00:00", null);  // 今日离店（待退房），不在住
        order(db, 4, 6, 102, 31, "completed", D.minusDays(3), D.plusDays(2), 1, D.minusDays(5) + " 10:00:00", D + " 09:00:00"); // 今天提前退房
        order(db, 5, 6, 102, 32, "checked_in", D.minusDays(4), D.minusDays(1), 1, D.minusDays(6) + " 10:00:00", null); // 过了离店日没退房：今日离店
        order(db, 6, 6, 102, 32, "checked_in", D, D.plusDays(1), 1, D.minusDays(1) + " 10:00:00", null);  // 今日入住已到店 + 在住 1 床位
        order(db, 7, 6, 102, 31, "refunding", D, D.plusDays(2), 3, D.minusDays(2) + " 10:00:00", null);   // 退款中：不占房
        order(db, 8, 6, 102, 31, "refund_pending", D.plusDays(5), D.plusDays(6), 1, D.minusDays(2) + " 10:00:00", null); // 待审退款
        order(db, 9, 6, 102, 31, "pending_payment", D, D.plusDays(1), 1, null, null);           // 待支付：不算
        order(db, 10, 6, 102, 31, "confirmed", D.plusDays(1), D.plusDays(3), 1, D + " 23:59:59", null); // 明天入住，今天支付：新订单
        order(db, 11, 7, 103, 41, "checked_in", D.minusDays(1), D.plusDays(3), 2, D.minusDays(2) + " 10:00:00", null); // 大理在住 2 间
        order(db, 12, 8, 104, 51, "checked_in", D, D.plusDays(3), 5, paidToday, null);          // 没授权的门店
        db.insert("kg_stay_order", p("order_id", 13L, "order_no", "DT13", "stay_id", 6L, "store_id", 102L, "room_type_id", 31L, "app_user_id", 9001L,
                "check_in", D.toString(), "check_out", D.plusDays(1).toString(), "nights", 1, "guests", 1, "units", 1, "guest_name", "删", "guest_phone", "1",
                "room_fen", 1L, "pay_fen", 1L, "status", "confirmed", "del_flag", "1", "create_time", paidToday)); // 已删除

        db.insert("kg_job", p("job_id", 501L, "store_id", 102L, "job_type", "volunteer", "title", "前台换工", "city", "平潭", "status", "online", "del_flag", "0"));
        db.insert("kg_job", p("job_id", 502L, "store_id", 103L, "job_type", "parttime", "title", "大理兼职", "city", "", "status", "online", "del_flag", "0"));
        db.insert("kg_job", p("job_id", 503L, "store_id", 104L, "job_type", "volunteer", "title", "别人家", "city", "平潭", "status", "online", "del_flag", "0"));
        app(db, 1, 102, 501, "submitted", D + " 08:00:00", null, null, null);                    // 新报名 + 待审核
        app(db, 2, 102, 501, "submitted", D.minusDays(2) + " 08:00:00", null, null, null);       // 待审核
        app(db, 3, 102, 501, "user_confirmed", D.minusDays(3) + " 08:00:00", null, null, null);  // 待处理（待录用）
        app(db, 4, 102, 501, "store_confirmed", D.minusDays(4) + " 08:00:00", D + " 11:00:00", D.toString(), null); // 今天录用 + 今天到岗
        app(db, 5, 102, 501, "onboarded", D.minusDays(9) + " 08:00:00", D.minusDays(5) + " 11:00:00", D.toString(), D + " 15:00:00"); // 到岗 + 入职
        app(db, 6, 102, 501, "withdrawn", D + " 00:00:00", null, null, null);                    // 今天报名又撤回：算新报名，不算待处理
        app(db, 7, 102, 501, "submitted", D.plusDays(1) + " 00:00:00", null, null, null);        // 明天 0 点：今天不算新报名，算待处理
        app(db, 8, 103, 502, "contract_signed", D.minusDays(6) + " 08:00:00", D.minusDays(2) + " 11:00:00", D.plusDays(1).toString(), null); // 待处理（待入职）
        app(db, 9, 104, 503, "submitted", D + " 09:00:00", null, null, null);                    // 没授权

        KgStaffDailyService service = new KgStaffDailyService()
        {
            @Override
            LocalDate today()
            {
                return D;
            }

            @Override
            LocalDateTime now()
            {
                return D.atTime(9, 30);
            }
        };
        ReflectionTestUtils.setField(service, "db", db);
        AppLoginUser me = new AppLoginUser();
        me.setUserId(1L);
        me.setStaffGrants(Arrays.asList(grant(102L, "keeper"), grant(103L, "keeper")));

        Map<String, Object> out = service.daily(me, D.toString(), null);
        List<Map<String, Object>> cities = (List<Map<String, Object>>) out.get("cities");
        assertEquals(new java.util.HashSet<String>(Arrays.asList("平潭", "大理")), new java.util.HashSet<String>((List<String>) out.get("cityOptions")));
        Map<String, Object> ptCity = cities.stream().filter(city -> "平潭".equals(city.get("city"))).findFirst().orElseThrow(AssertionError::new);
        Map<String, Object> dlCity = cities.stream().filter(city -> "大理".equals(city.get("city"))).findFirst().orElseThrow(AssertionError::new);
        Map<String, Object> pt = ((List<Map<String, Object>>) ptCity.get("stays")).get(0);
        assertEquals(6L, pt.get("stayId"));
        assertEquals(2L, pt.get("arrivals"), "订单 1、6");
        assertEquals(1L, pt.get("arrived"), "订单 6 已到店");
        assertEquals(3L, pt.get("inHouse"), "订单 1、2、6");
        assertEquals(4L, pt.get("units"), "2 + 1 + 1");
        assertEquals(8L, pt.get("stock"), "大床房 6 + 多人间当天库存 2；下架房型不算");
        assertEquals("50%", pt.get("occupancyText"));
        assertEquals(3L, pt.get("departures"), "订单 3（待退房）、4（提前退房）、5（逾期未退）");
        assertEquals(1L, pt.get("departed"), "订单 4");
        assertEquals(2L, pt.get("newOrders"), "订单 1、10（23:59:59 也算今天）");
        assertEquals(1L, pt.get("refundPending"), "订单 8");
        Map<String, Object> dl = ((List<Map<String, Object>>) dlCity.get("stays")).get(0);
        assertEquals(7L, dl.get("stayId"));
        assertEquals(1L, dl.get("inHouse"));
        assertEquals("40%", dl.get("occupancyText"));

        Map<String, Object> ptApps = ((List<Map<String, Object>>) ptCity.get("apps")).get(0);
        assertEquals(102L, ptApps.get("storeId"));
        assertEquals(2L, ptApps.get("newApps"), "报名 1、6（0 点整也算今天）；7 是明天 0 点，不算");
        assertEquals(4L, ptApps.get("pending"), "报名 1、2、3、7");
        assertEquals(3L, ptApps.get("toReview"), "报名 1、2、7");
        assertEquals(1L, ptApps.get("hired"), "报名 4");
        assertEquals(2L, ptApps.get("arriving"), "报名 4、5");
        assertEquals(1L, ptApps.get("onboarded"), "报名 5");
        Map<String, Object> dlApps = ((List<Map<String, Object>>) dlCity.get("apps")).get(0);
        assertEquals(103L, dlApps.get("storeId"), "岗位没填城市时用门店城市（大理）");
        assertEquals(1L, dlApps.get("pending"));

        Map<String, Object> total = (Map<String, Object>) out.get("total");
        assertEquals(4L, total.get("inHouse"), "没授权的门店 104 不混进来");
        assertEquals(13L, total.get("stock"));
        assertEquals(6L, total.get("units"));
        assertEquals("46.2%", total.get("occupancyText"));

        // 第二天：订单 1、2（离店日）不再在住；1 不再是今日入住；看的不是今天，逾期未退的订单 5 不算离店
        Map<String, Object> next = service.daily(me, D.plusDays(1).toString(), "平潭");
        Map<String, Object> pt2 = ((List<Map<String, Object>>) ((List<Map<String, Object>>) next.get("cities")).get(0).get("stays")).get(0);
        assertEquals(1L, pt2.get("arrivals"), "订单 10");
        assertEquals(2L, pt2.get("inHouse"), "订单 1、10");
        assertEquals(3L, pt2.get("units"));
        assertEquals(2L, pt2.get("departures"), "订单 2、6 离店日是明天；看的不是今天，逾期未退的 3、5 不算");
        assertEquals(5L, pt2.get("stock"), "大床房当天单独设了 1 间 + 多人间默认 4");

        // 换工主理人与员工保留招聘统计，但已取消旅居权限，不得夹带房源与订单信息。
        me.setStaffGrants(Arrays.asList(grant(102L, "manager"), grant(103L, "staff")));
        Map<String, Object> recruitmentOnly = service.daily(me, D.toString(), null);
        for (Map<String, Object> city : (List<Map<String, Object>>) recruitmentOnly.get("cities"))
        {
            assertTrue(((List<?>) city.get("stays")).isEmpty());
            assertFalse(((List<?>) city.get("apps")).isEmpty());
        }
        assertEquals(0L, ((Map<String, Object>) recruitmentOnly.get("total")).get("inHouse"));
        assertEquals(5L, ((Map<String, Object>) recruitmentOnly.get("total")).get("pending"));
    }
}
