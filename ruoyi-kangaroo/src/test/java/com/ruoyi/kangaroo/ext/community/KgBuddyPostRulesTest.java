package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;

/** Real KgPostService / KgCommunityGuard bodies, Mock JDBC (no real SQL).
 * 找搭子：六类分类与关键词推断、同行性别的存储 / 列表筛选 / 返回字段、《找搭子安全须知》同意。 */
class KgBuddyPostRulesTest {
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgCityService cities = mock(KgCityService.class);
    final KgPostService service = new KgPostService();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "db", db);
        ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "cities", cities);
        ReflectionTestUtils.setField(service, "activities", mock(KgActivityService.class));
        when(db.one(startsWith("select status, del_flag from kg_app_user"), anyMap())).thenReturn(p("status", "0", "delFlag", "0"));
        when(guard.check(any(String[].class))).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("pending");
        when(guard.mode()).thenReturn("manual");
        when(db.insert(eq("kg_post"), anyMap())).thenReturn(42L);
        when(cities.businessCity(anyString())).thenAnswer(i -> i.getArgument(0));
    }

    Map<String, Object> buddy(Object... extra) {
        Map<String, Object> b = p("kind", "buddy", "title", "周六古城拼饭", "content", "人均五十，吃完去洱海边散步", "city", "大理");
        b.putAll(p(extra));
        return b;
    }

    Map<String, Object> row(Object... kv) {
        Map<String, Object> r = p("postId", 5L, "kind", "buddy", "appUserId", 9L, "city", "大理", "title", "晨跑搭子", "status", "online");
        r.putAll(p(kv));
        return r;
    }

    // ---------------------------------------------------------------- 分类

    @Test void sixCategoriesInListOrderWithFoodRenamedNotRecoded() {
        assertEquals(Arrays.asList("hike", "photo", "meal", "food", "ride", "run", "other"), new ArrayList<>(KgPostService.CATEGORY_LABEL.keySet()));
        assertEquals(Arrays.asList("徒步登山", "摄影", "拼饭", "美食分享", "骑行", "跑步", "其他"), new ArrayList<>(KgPostService.CATEGORY_LABEL.values()));
    }

    @Test void inferenceCoversMealRunAndFood() {
        assertEquals("meal", KgPostService.inferCategory("周六晚上古城拼饭，有人吗"));
        assertEquals("meal", KgPostService.inferCategory("找个饭搭子"));
        assertEquals("meal", KgPostService.inferCategory("下班一起吃饭"));
        assertEquals("meal", KgPostService.inferCategory("约饭顺便探店喝咖啡"), "拼饭排在美食分享前");
        assertEquals("run", KgPostService.inferCategory("洱海边晨跑 5 公里"));
        assertEquals("run", KgPostService.inferCategory("马拉松赛前拉练"));
        assertEquals("run", KgPostService.inferCategory("环湖夜跑"), "「环湖」只是骑行的弱关键词，跑步优先");
        assertEquals("ride", KgPostService.inferCategory("环湖骑行一圈"));
        assertEquals("food", KgPostService.inferCategory("探店新开的咖啡馆"));
        assertEquals("food", KgPostService.inferCategory("周末烘焙课"));
        assertEquals("hike", KgPostService.inferCategory("苍山徒步"));
        assertEquals("other", KgPostService.inferCategory("周末去看展"));
        assertEquals("other", KgPostService.inferCategory(null));
    }

    // ---------------------------------------------------------------- 发布：同行性别 / 分类 / 安全须知

    @Test void buddyPublishStoresGenderLimitAndExplicitCategory() {
        assertEquals(42L, service.publish(7L, buddy("genderLimit", "female", "category", "run")).get("postId"));
        verify(db).insert(eq("kg_post"), argThat(r -> "buddy".equals(r.get("kind")) && "female".equals(r.get("gender_limit")) && "run".equals(r.get("category"))));
    }

    @Test void buddyWithoutChoicesIsUnlimitedAndAutoCategorized() {
        service.publish(7L, buddy());
        verify(db).insert(eq("kg_post"), argThat(r -> "".equals(r.get("gender_limit")) && "meal".equals(r.get("category"))));
    }

    @Test void maleOnlyIsStoredAsIs() {
        service.publish(7L, buddy("genderLimit", "male"));
        verify(db).insert(eq("kg_post"), argThat(r -> "male".equals(r.get("gender_limit"))));
    }

    @Test void unknownGenderLimitRejectedBeforeInsert() {
        for (String bad : new String[] { "girls", "FEMALE", "2", "不限", "仅限女生" }) {
            ServiceException e = assertThrows(ServiceException.class, () -> service.publish(7L, buddy("genderLimit", bad)), bad);
            assertEquals(422, e.getCode());
        }
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void nonBuddyPostsNeverCarryGenderLimitOrNeedBuddyConsent() {
        service.publish(7L, p("kind", "daily", "title", "洱海日落", "content", "", "city", "大理", "genderLimit", "female"));
        verify(db).insert(eq("kg_post"), argThat(r -> !r.containsKey("gender_limit") && !r.containsKey("category")));
        verify(guard).requireConsent(7L, "community");
        verify(guard, never()).requireConsent(anyLong(), eq("buddy"));
    }

    @Test void buddyPublishRequiresSafetyConsentAfterCommunityRules() {
        service.publish(7L, buddy("genderLimit", "male"));
        InOrder o = inOrder(guard, db);
        o.verify(guard).requireConsent(7L, "community");
        o.verify(guard).requireConsent(7L, "buddy");
        o.verify(guard).requirePhone(7L);
        o.verify(db).insert(eq("kg_post"), anyMap());
    }

    @Test void missingSafetyConsentStopsBuddyPublish() {
        doThrow(new ServiceException("CONSENT:buddy:请先阅读并同意《找搭子安全须知》", 423)).when(guard).requireConsent(7L, "buddy");
        ServiceException e = assertThrows(ServiceException.class, () -> service.publish(7L, buddy("genderLimit", "female")));
        assertEquals(423, e.getCode());
        assertTrue(e.getMessage().startsWith("CONSENT:buddy:"));
        verify(guard, never()).requirePhone(anyLong());
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test void safetyDocIsServedWithoutBumpingRulesVersion() {
        assertEquals("2026-09", KgCommunityGuard.RULES_VERSION);
        Map<String, Object> doc = KgCommunityGuard.doc("buddy");
        assertEquals("《找搭子安全须知》", doc.get("title"));
        assertEquals("2026-09", doc.get("version"));
        String text = String.valueOf(doc.get("content"));
        for (String must : new String[] { "自愿结伴", "各自对自身安全负责", "不组织线下活动", "平台未为找搭子投保", "意外险", "公共场所", "告诉亲友",
                "女生尽量结伴", "未开放景区", "无人区", "危险水域", "天气", "110", "120", "119", "举报", "自行协商", "公安机关" }) {
            assertTrue(text.contains(must), must);
        }
    }

    @Test void realGuardDemandsBuddyConsentUntilAgreed() {
        KgCommunityGuard real = new KgCommunityGuard();
        ReflectionTestUtils.setField(real, "db", db);
        when(db.count(startsWith("select count(*) from kg_consent"), anyMap())).thenReturn(0L);
        ServiceException e = assertThrows(ServiceException.class, () -> real.requireConsent(7L, "buddy"));
        assertEquals(423, e.getCode());
        assertTrue(e.getMessage().startsWith("CONSENT:buddy:"));
        assertTrue(e.getMessage().contains("《找搭子安全须知》"));
        real.consent(7L, "buddy");
        verify(db).update(startsWith("insert ignore into kg_consent"), eq(p("u", 7L, "d", "buddy", "v", "2026-09")));
        when(db.count(startsWith("select count(*) from kg_consent"), anyMap())).thenReturn(1L);
        assertDoesNotThrow(() -> real.requireConsent(7L, "buddy"));
    }

    // ---------------------------------------------------------------- 列表筛选 / 返回字段

    @SuppressWarnings("unchecked")
    @Test void buddiesGenderFilterKeepsUnlimitedPlusThatGender() {
        // 帖子列表那一句（一页的点赞、评论数另各查一次，见 KgPostCommentCountTest）
        when(db.list(startsWith("select p.*"), anyMap())).thenReturn(Arrays.asList(row("genderLimit", "female", "category", "meal"), row("postId", 6L, "genderLimit", "", "category", "run")));
        Map<String, Object> res = service.buddies(7L, "全部城市", null, null, null, false, "female");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> ps = ArgumentCaptor.forClass(Map.class);
        verify(db).list(argThat((String q) -> q.startsWith("select p.*")), ps.capture());
        verify(db).list(sql.capture(), argThat(m -> "female".equals(m.get("g"))));
        assertTrue(sql.getValue().contains(" and p.gender_limit in ('', :g)"));
        assertEquals("female", ps.getValue().get("g"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.get("rows");
        assertEquals("female", rows.get(0).get("genderLimit"));
        assertEquals("仅限女生", rows.get(0).get("genderLimitLabel"));
        assertEquals("拼饭", rows.get(0).get("categoryLabel"));
        assertEquals("", rows.get(1).get("genderLimit"));
        assertEquals("不限", rows.get(1).get("genderLimitLabel"));
        assertEquals("跑步", rows.get(1).get("categoryLabel"));
        assertEquals(KgPostService.CATEGORY_LABEL, res.get("categories"));
        service.buddies(7L, "全部城市", null, null, null, false, "male");
        verify(db).list(anyString(), argThat(m -> "male".equals(m.get("g"))));
    }

    @Test void buddiesWithoutOrWithUnknownGenderDoNotFilter() {
        when(db.list(anyString(), anyMap())).thenReturn(new ArrayList<>());
        for (String g : new String[] { null, "", "x", "FEMALE", "2" }) {
            service.buddies(7L, "大理", null, null, null, false, g);
        }
        verify(db, times(5)).list(argThat(sql -> !sql.contains("gender_limit")), argThat(m -> !m.containsKey("g")));
    }

    @SuppressWarnings("unchecked")
    @Test void feedAndDetailRowsCarryGenderLimitLabel() {
        when(db.list(startsWith("select p.* from kg_post p"), anyMap())).thenReturn(Arrays.asList(row("genderLimit", "male"),
                row("postId", 6L, "kind", "daily", "title", "日常")));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.feed(7L, null, "全部城市", "new", 1, null).get("rows");
        assertEquals("male", rows.get(0).get("genderLimit"));
        assertEquals("仅限男生", rows.get(0).get("genderLimitLabel"));
        assertEquals("", rows.get(1).get("genderLimit"), "旧数据 / 非找搭子没有这一列的值时按不限");
        when(db.one(startsWith("select p.* from kg_post p where p.post_id"), anyMap())).thenReturn(row("genderLimit", "female"));
        Map<String, Object> detail = service.detail(5L, 7L);
        assertEquals("female", detail.get("genderLimit"));
        assertEquals("仅限女生", detail.get("genderLimitLabel"));
        when(db.one(startsWith("select p.* from kg_post p where p.post_id"), anyMap())).thenReturn(row("genderLimit", "nonsense"));
        assertEquals("", service.detail(5L, 7L).get("genderLimit"), "库里出现不认识的值也不往外传");
    }
}
