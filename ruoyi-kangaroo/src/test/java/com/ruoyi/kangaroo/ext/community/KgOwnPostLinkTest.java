package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgCityService;

class KgOwnPostLinkTest {
    final KgJdbc db = mock(KgJdbc.class);
    final KgCommunityGuard guard = mock(KgCommunityGuard.class);
    final KgPostService service = new KgPostService();
    final Map<String,Object> target = p("id",3L,"title","自己的另一篇帖子","cover","/profile/a.jpg","city","平潭","postKind","buddy");
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service,"db",db);
        ReflectionTestUtils.setField(service,"guard",guard);
        KgCityService cities=mock(KgCityService.class);
        ReflectionTestUtils.setField(service,"cities",cities);
        ReflectionTestUtils.setField(service,"activities",mock(KgActivityService.class));
        when(cities.businessCity(any())).thenAnswer(i->i.getArgument(0));
        when(db.one(startsWith("select status, del_flag from kg_app_user"),anyMap())).thenReturn(p("status","0","delFlag","0"));
        when(guard.check(any(String[].class))).thenReturn("pass");
        when(guard.initialStatus(any())).thenReturn("online");
        when(guard.mode()).thenReturn("mock");
        when(db.insert(eq("kg_post"),anyMap())).thenReturn(42L);
    }
    Map<String,Object> payload() { return p("kind","daily","title","一次分享","content","真实的旅行记录","city","平潭","linkType","post","linkId",3L); }
    @Test void minePickerRequiresLogin() {
        assertEquals(401,assertThrows(ServiceException.class,()->service.linkables("post",null,null,1,null)).getCode());
        verify(db,never()).list(anyString(),anyMap());
    }
    @Test void pickerLimitsToCurrentAuthorAndPublicPostsWithPagination() {
        when(db.list(anyString(),anyMap())).thenReturn(Collections.singletonList(target));
        Map<String,Object> result=service.linkables("post","100%_","平潭",2,7L);
        assertEquals(false,result.get("hasMore"));
        verify(db).list(argThat(sql->sql.contains("lp.app_user_id = :u") && sql.contains("lp.status = 'online'") && sql.contains("lp.del_flag = '0'") && sql.contains("lp.title like :kw")),argThat(args->Long.valueOf(7).equals(args.get("u")) && Integer.valueOf(20).equals(args.get("off")) && "%100\\%\\_%".equals(args.get("kw"))));
        assertEquals("post",((List<Map<String,Object>>)result.get("rows")).get(0).get("type"));
    }
    @Test void publishStoresOwnPostAndResolvesClickableCard() {
        when(db.one(startsWith(KgPostService.LINK_POST_SQL),argThat(args->Long.valueOf(7).equals(args.get("u"))))).thenReturn(target);
        when(db.list(startsWith(KgPostService.LINK_POST_SQL),anyMap())).thenReturn(Collections.singletonList(target));
        Map<String,Object> result=service.publish(7L,payload());
        verify(db).insert(eq("kg_post"),argThat(row->"post".equals(row.get("link_type")) && Long.valueOf(3).equals(row.get("link_id"))));
        assertEquals("post",((Map<?,?>)result.get("link")).get("type"));
        assertEquals("自己的另一篇帖子",((Map<?,?>)result.get("link")).get("title"));
    }
    @Test void forgedForeignHiddenOrDeletedTargetCannotBePublished() {
        assertEquals(422,assertThrows(ServiceException.class,()->service.publish(7L,payload())).getCode());
        verify(db).one(contains("lp.post_id = :id and lp.app_user_id = :u and " + KgPostService.POST_SHOWN),eq(p("id",3L,"u",7L)));
        verify(db,never()).insert(anyString(),anyMap());
    }
    @Test void hiddenTargetIsRemovedFromExistingPublicCards() {
        assertTrue(service.links(Collections.singletonList(p("postId",42L,"kind","daily","linkType","post","linkId",3L))).isEmpty());
        verify(db).list(contains("lp.post_id in (:ids) and " + KgPostService.POST_SHOWN),anyMap());
    }
    @Test void legacyPlaceLinksStillValidateWithoutOwnershipRestriction() {
        when(db.one(startsWith(KgPostService.LINK_STAY_SQL),anyMap())).thenReturn(p("id",6L,"title","小院"));
        assertEquals(6L,service.pickableLink("stay",6L,7L).get("id"));
        assertNull(service.pickableLink("post",3L,null));
    }
}
