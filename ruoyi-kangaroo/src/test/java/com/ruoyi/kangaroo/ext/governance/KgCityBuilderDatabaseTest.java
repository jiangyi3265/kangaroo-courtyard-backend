package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;

class KgCityBuilderDatabaseTest {
    static KgJdbc db;
    static TransactionTemplate tx;
    KgCityService cities;
    KgBadgeService badges;
    static final String CITY = "共建甲";
    static final String OTHER = "共建乙";
    static final long FIRST_USER = 710001L;

    @BeforeAll static void connect() {
        String name = System.getenv("KG_MEMBER_TEST_DB");
        assumeTrue(name != null && name.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + name
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sessionVariables=time_zone=%27%2B08:00%27",
                System.getenv("KG_MEMBER_TEST_USER"), System.getenv("KG_TEST_DB_PASSWORD"));
        db = new KgJdbc(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @BeforeEach void setup() {
        cleanup();
        db.insert("kg_city", p("name", CITY, "full_name", CITY + "市", "level", "city", "create_by", "city_builder_test"));
        db.insert("kg_city", p("name", OTHER, "full_name", OTHER + "市", "level", "city", "create_by", "city_builder_test"));
        db.insert("kg_city", p("name", "共建甲区", "full_name", "共建甲区", "parent_name", CITY + "市", "level", "district", "create_by", "V25"));
        for (long user = FIRST_USER; user < FIRST_USER + 10; user++)
            db.insert("kg_app_user", p("user_id", user, "openid", "builder-test-" + user, "nickname", "测试共建人" + user));
        cities = new KgCityService();
        ReflectionTestUtils.setField(cities, "db", db);
        badges = new KgBadgeService();
        ReflectionTestUtils.setField(badges, "db", db);
        ReflectionTestUtils.setField(badges, "cities", cities);
    }

    @AfterEach void cleanup() {
        db.update("delete from kg_badge_award where app_user_id between 710001 and 710010", p());
        db.update("delete from kg_badge_log where app_user_id between 710001 and 710010", p());
        db.update("delete from kg_app_user where user_id between 710001 and 710010", p());
        db.update("delete from kg_city where name in ('共建甲','共建乙','共建甲区')", p());
        db.update("update kg_badge_def set enabled='1' where code='city_builder'", p());
    }

    long grant(long user, String city, String key) {
        return tx.execute(status -> badges.grant(p("userId", user, "code", "city_builder", "city", city,
                "sourceKey", key, "note", "已核验城市共建任命记录"), "admin:test"));
    }

    long legacy(long user, String city) {
        return db.insert("kg_badge_award", p("app_user_id", user, "badge_code", "city_builder", "city", city,
                "source_key", "legacy-city-builder", "source_note", "历史城市共建任命", "awarded_by", "admin:test"));
    }

    @SuppressWarnings("unchecked") List<Map<String, Object>> builders(String city) {
        return (List<Map<String, Object>>) cities.overview(city).get("builders");
    }

    @Test void cityPageShowsOnlyPublicCurrentAwardsAndUsableAccounts() {
        grant(FIRST_USER, CITY, "city-builder-one");
        grant(FIRST_USER, CITY, "city-builder-two");
        grant(FIRST_USER + 1, OTHER, "city-builder-other");
        legacy(FIRST_USER + 2, CITY + "市");
        long hidden = legacy(FIRST_USER + 3, CITY);
        db.update("update kg_badge_award set visible='0' where award_id=:a", p("a", hidden));
        long revoked = legacy(FIRST_USER + 4, CITY);
        db.update("update kg_badge_award set status='revoked' where award_id=:a", p("a", revoked));
        long expired = legacy(FIRST_USER + 5, CITY);
        db.update("update kg_badge_award set expires_at=date_sub(now(),interval 1 minute) where award_id=:a", p("a", expired));
        legacy(FIRST_USER + 6, CITY);
        db.update("update kg_app_user set status='1' where user_id=:u", p("u", FIRST_USER + 6));
        legacy(FIRST_USER + 7, CITY);
        db.update("update kg_app_user set del_flag='2' where user_id=:u", p("u", FIRST_USER + 7));
        legacy(FIRST_USER + 8, null);
        List<Map<String, Object>> rows = builders("共建甲区");
        assertEquals(Arrays.asList(FIRST_USER, FIRST_USER + 2), rows.stream()
                .map(row -> KgJdbc.asLong(row.get("userId"))).collect(Collectors.toList()));
        for (Map<String, Object> row : rows) assertEquals(new HashSet<>(Arrays.asList("userId", "name")), row.keySet());
        db.update("update kg_badge_award set visible='0' where app_user_id=:u", p("u", FIRST_USER));
        assertEquals(1, builders(CITY).size());
        db.update("update kg_badge_def set enabled='0' where code='city_builder'", p());
        assertTrue(builders(CITY).isEmpty());
    }

    @Test void allCitiesDoesNotExposeACrossCityHonorList() {
        grant(FIRST_USER, CITY, "city-builder-one");
        assertTrue(builders("全部城市").isEmpty());
        assertTrue(builders(null).isEmpty());
        assertTrue(builders(OTHER).isEmpty());
    }

    @Test void grantRequiresARealCityAndStoresTheNormalizedCity() {
        for (String invalid : Arrays.asList("", " ", "全部城市", "不存在的共建测试城"))
            assertThrows(ServiceException.class, () -> grant(FIRST_USER, invalid, "city-builder-bad"));
        long award = grant(FIRST_USER, "共建甲区", "city-builder-valid");
        assertEquals(CITY, db.one("select city from kg_badge_award where award_id=:a", p("a", award)).get("city"));
        assertEquals(award, grant(FIRST_USER, CITY, "city-builder-valid"));
        assertEquals(1, builders(CITY).size());
    }

    @Test void legacyAwardCanReceiveACityWithAnAuditableIdempotentUpdate() {
        long award = legacy(FIRST_USER, null);
        assertTrue(builders(CITY).isEmpty());
        Map<String, Object> body = p("city", CITY + "市", "note", "依据原始任命记录补录城市");
        for (int i = 0; i < 2; i++) tx.execute(status -> { badges.updateBuilderCity(award, body, "admin:test"); return null; });
        assertEquals(1, builders(CITY).size());
        assertEquals(1, db.count("select count(*) from kg_badge_award where app_user_id=:u", p("u", FIRST_USER)));
        assertEquals(1, db.count("select count(*) from kg_badge_log where award_id=:a and action='city_update'", p("a", award)));
        db.update("update kg_badge_award set status='revoked' where award_id=:a", p("a", award));
        assertTrue(builders(CITY).isEmpty());
        assertThrows(ServiceException.class, () -> tx.execute(status -> { badges.updateBuilderCity(award, body, "admin:test"); return null; }));
    }

    @Test void cityCorrectionNotesFitTheAuditColumnWithoutTruncation() {
        long award = legacy(FIRST_USER, OTHER);
        String note = String.join("", Collections.nCopies(360, "据"));
        Map<String, Object> tooLong = p("city", CITY, "note", note + "长");
        assertThrows(ServiceException.class, () -> tx.execute(status -> {
            badges.updateBuilderCity(award, tooLong, "admin:test"); return null;
        }));
        assertEquals(OTHER, db.one("select city from kg_badge_award where award_id=:a", p("a", award)).get("city"));
        tx.execute(status -> { badges.updateBuilderCity(award, p("city", CITY, "note", note), "admin:test"); return null; });
        assertEquals(OTHER + " → " + CITY + "；" + note,
                db.one("select note from kg_badge_log where award_id=:a and action='city_update'", p("a", award)).get("note"));
    }
}
