package com.ruoyi.kangaroo.ext.core;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Collections;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.ArrivalTicketResourceInterceptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class KgArrivalTicketsTest {
    @Test void originalUnicodeAndSpacedLegacyNamesAreProtected() {
        for (String name : new String[]{"测试票据.jpg", "票据 (1).jpg", "ticket 20%.JPG", "车票#1.jpg", "票据?1.jpg", "abc.jpg"})
            assertTrue(KgArrivalTickets.legacy("/profile/upload/2026/10/06/"+name),name);
    }
    @Test void legacyNameNeverAllowsTraversalSeparatorsOrCsvInjection() {
        for (String name : new String[]{"../x.jpg", "a/b.jpg", "a\\b.jpg", "a,b.jpg", "a\nb.jpg", "a.svg", "a.jpg?x=1", "a.jpg#fragment"})
            assertFalse(KgArrivalTickets.legacy("/profile/upload/2026/10/06/"+name),name);
    }
    @Test void nullHistoricalTicketListCanBeSaved() {
        KgJdbc db=mock(KgJdbc.class); KgArrivalTickets svc=new KgArrivalTickets();
        ReflectionTestUtils.setField(svc,"db",db);
        when(db.one(startsWith("select a.app_user_id,a.store_id,a.status,j.job_type from kg_application"),eq(p("a",2L)))).thenReturn(p("appUserId",1L,"storeId",4L,"status","store_confirmed","jobType","volunteer"));
        when(db.one(eq("select tickets from kg_app_arrival where app_id=:a"),eq(p("a",2L)))).thenReturn(p("tickets",null));
        assertDoesNotThrow(()->svc.validateForSave(1L,2L,Collections.emptyList()));
    }
    @Test void aPublicImageCannotBeBoundAsNewTicket() {
        KgJdbc db=mock(KgJdbc.class); KgArrivalTickets svc=new KgArrivalTickets();
        ReflectionTestUtils.setField(svc,"db",db);
        when(db.one(startsWith("select a.app_user_id,a.store_id,a.status,j.job_type from kg_application"),eq(p("a",2L)))).thenReturn(p("appUserId",1L,"storeId",4L,"status","store_confirmed","jobType","volunteer"));
        assertThrows(ServiceException.class,()->svc.validateForSave(1L,2L,Collections.singletonList("/profile/upload/abc.jpg")));
    }
    @Test void rejectsCrossApplicationOrTraversalRefsBeforeFilesystemAccess() {
        KgArrivalTickets svc=new KgArrivalTickets();
        assertThrows(ServiceException.class,()->svc.privateFile(1L,"/private/arrival/2/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg"));
        assertThrows(ServiceException.class,()->svc.privateFile(1L,"/private/arrival/1/../aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg"));
    }
    @Test void privacyDoesNotDependOnReadableNamesAndSurvivesRemovingLastBinding(@TempDir Path folder) {
        String original=RuoYiConfig.getProfile();
        try {
            new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
            KgJdbc db=mock(KgJdbc.class); KgArrivalTickets svc=new KgArrivalTickets();
            ReflectionTestUtils.setField(svc,"db",db);
            when(db.count(anyString(),anyMap())).thenReturn(1L);
            for (String name : new String[]{"车票#1.jpg", "票据?1.jpg", "票据,1.jpg", "old.txt"}) {
                String ref="/profile/upload/2026/10/06/"+name;
                assertTrue(svc.isLegacyPrivate(ref),name);
                when(db.count(anyString(),anyMap())).thenReturn(0L);
                assertTrue(svc.isLegacyPrivate(ref),"Permanent marker survives deletion: "+name);
                when(db.count(anyString(),anyMap())).thenReturn(1L);
            }
            verify(db,atLeastOnce()).count(contains("locate(concat(',',:f,','),concat(',',tickets,','))"),anyMap());
            assertFalse(svc.isLegacyPrivate("/profile/avatar/x.jpg"));
        } finally { new RuoYiConfig().setProfile(original); }
    }
    @Test void removingUnreadCommaNamesProtectsWholeReferenceBeforeSave(@TempDir Path folder) {
        String original=RuoYiConfig.getProfile();
        try {
            new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
            KgJdbc db=mock(KgJdbc.class); KgArrivalTickets svc=new KgArrivalTickets();
            ReflectionTestUtils.setField(svc,"db",db);
            when(db.one(startsWith("select a.app_user_id,a.store_id,a.status,j.job_type from kg_application"),eq(p("a",2L)))).thenReturn(p("appUserId",1L,"storeId",4L,"status","store_confirmed","jobType","volunteer"));
            String comma="/profile/upload/a,1.jpg", hash="/profile/upload/b#1.jpg";
            when(db.one(eq("select tickets from kg_app_arrival where app_id=:a"),eq(p("a",2L)))).thenReturn(p("tickets",comma+","+hash));
            svc.validateForSave(1L,2L,Collections.emptyList());
            when(db.count(anyString(),anyMap())).thenReturn(0L);
            assertTrue(svc.isLegacyPrivate(comma)); assertTrue(svc.isLegacyPrivate(hash));
            assertFalse(svc.isLegacyPrivate("/profile/upload/a.jpg"));
            assertFalse(KgArrivalTickets.legacy(comma));
        } finally { new RuoYiConfig().setProfile(original); }
    }
    @Test void markerDirectoryCollisionRejectsSaveRatherThanSilentlyRemovingPrivacy(@TempDir Path folder) throws Exception {
        String original=RuoYiConfig.getProfile();
        try {
            new RuoYiConfig().setProfile(folder.resolve("uploads").toString());
            String ref="/profile/upload/车票#1.jpg";
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format("%02x",b));
            java.nio.file.Files.createDirectories(folder.resolve("kg-private/arrival-legacy-block/"+hex));
            KgJdbc db=mock(KgJdbc.class);KgArrivalTickets svc=new KgArrivalTickets();ReflectionTestUtils.setField(svc,"db",db);
            when(db.one(startsWith("select a.app_user_id,a.store_id,a.status,j.job_type from kg_application"),eq(p("a",2L)))).thenReturn(p("appUserId",1L,"storeId",4L,"status","store_confirmed","jobType","volunteer"));
            when(db.one(eq("select tickets from kg_app_arrival where app_id=:a"),eq(p("a",2L)))).thenReturn(p("tickets",ref));
            ServiceException error=assertThrows(ServiceException.class,()->svc.validateForSave(1L,2L,Collections.emptyList()));
            assertEquals(Integer.valueOf(503),error.getCode());
        } finally { new RuoYiConfig().setProfile(original); }
    }

    private static Path rawMarker(Path folder,String ref) throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format("%02x",b));
        return folder.resolve("kg-private/arrival-legacy-block/"+hex);
    }

    @Test void actualFilesystemIdentityPreservesOldRawMarkersAndInterceptsBothRoutes(@TempDir Path folder) throws Exception {
        String original=RuoYiConfig.getProfile();
        try {
            Path profile=folder.resolve("uploads"),file=profile.resolve("upload/2026/10/06/CaseTicket.jpg");
            Files.createDirectories(file.getParent());Files.write(file,new byte[]{1,2,3});
            new RuoYiConfig().setProfile(profile.toString());
            String ref="/profile/upload/2026/10/06/CaseTicket.jpg";
            Path marker=rawMarker(folder,ref);Files.createDirectories(marker.getParent());Files.createFile(marker);
            KgJdbc db=mock(KgJdbc.class);KgArrivalTickets svc=new KgArrivalTickets();ReflectionTestUtils.setField(svc,"db",db);
            when(db.count(anyString(),anyMap())).thenReturn(0L);
            ArrivalTicketResourceInterceptor interceptor=new ArrivalTicketResourceInterceptor();ReflectionTestUtils.setField(interceptor,"files",svc);
            int existingAliases=0;
            for(String alias:new String[]{"/profile/UPLOAD/2026/10/06/CaseTicket.jpg","/profile/upload/2026/10/06/caseticket.JPG",
                    "/profile/upload/2026/10/06/CaseTicket.jpg.","/profile/upload/2026/10/06/CaseTicket.jpg ","/profile/upload/2026/10/06/CASETI~1.JPG"}) {
                File candidate=new File(profile.toFile(),alias.substring("/profile/".length()));
                // Filesystem-dependent aliases: Windows resolves these to one file;
                // Linux may have different files. Do not lowercase either system.
                if(!candidate.isFile()) continue;
                assertTrue(Files.isSameFile(file,candidate.getCanonicalFile().toPath()));existingAliases++;
                assertTrue(svc.isLegacyPrivate(alias),alias);
                for(boolean download:new boolean[]{false,true}) {
                    MockHttpServletRequest req=new MockHttpServletRequest();MockHttpServletResponse res=new MockHttpServletResponse();
                    req.setRequestURI(download?"/common/download/resource":alias.replace(" ","%20"));
                    if(download)req.setParameter("resource","https://example.invalid"+alias);
                    assertFalse(interceptor.preHandle(req,res,new Object()),alias);
                    assertEquals(404,res.getStatus());assertEquals("no-store",res.getHeader("Cache-Control"));
                }
            }
            if(File.separatorChar=='\\')assertTrue(existingAliases>=4,"Windows case/dot/space aliases must actually be exercised");
            assertTrue(svc.isLegacyPrivate(ref));
            Path different=profile.resolve("upload/2026/10/06/caseticket.jpg");
            if(!Files.exists(different)) {
                Files.write(different,new byte[]{9});assertFalse(Files.isSameFile(file,different));
                assertFalse(svc.isLegacyPrivate("/profile/upload/2026/10/06/caseticket.jpg"),"Distinct case-sensitive files cannot share marker");
            }
            assertFalse(svc.isLegacyPrivate("/profile/upload/2026/10/06/OtherTicket.jpg"));
        } finally {new RuoYiConfig().setProfile(original);}
    }

    @Test void bindingAndRemovalThroughAnActualAliasWriteCanonicalAndRawProtection(@TempDir Path folder) throws Exception {
        String original=RuoYiConfig.getProfile();
        try {
            Path profile=folder.resolve("uploads"),file=profile.resolve("upload/2026/10/06/CaseTicket.jpg");
            Files.createDirectories(file.getParent());Files.write(file,new byte[]{1,2,3});new RuoYiConfig().setProfile(profile.toString());
            String ref="/profile/upload/2026/10/06/CaseTicket.jpg";
            String alias=new File(profile.toFile(),"UPLOAD/2026/10/06/caseticket.JPG").isFile()
                    ?"/profile/UPLOAD/2026/10/06/caseticket.JPG":ref;
            KgJdbc db=mock(KgJdbc.class);KgArrivalTickets svc=new KgArrivalTickets();ReflectionTestUtils.setField(svc,"db",db);
            when(db.count(anyString(),anyMap())).thenAnswer(invocation->ref.equals(((java.util.Map<?,?>)invocation.getArgument(1)).get("f"))?1L:0L);
            assertTrue(svc.isLegacyPrivate(alias),"Lookup must use the canonical bound reference too");
            assertTrue(Files.isRegularFile(rawMarker(folder,ref)));assertTrue(Files.isRegularFile(rawMarker(folder,alias)));
            Files.delete(rawMarker(folder,ref));if(!alias.equals(ref))Files.delete(rawMarker(folder,alias));
            when(db.one(startsWith("select a.app_user_id,a.store_id,a.status,j.job_type from kg_application"),eq(p("a",2L)))).thenReturn(p("appUserId",1L,"storeId",4L,"status","store_confirmed","jobType","volunteer"));
            when(db.one(eq("select tickets from kg_app_arrival where app_id=:a"),eq(p("a",2L)))).thenReturn(p("tickets",alias));
            svc.validateForSave(1L,2L,Collections.emptyList());
            when(db.count(anyString(),anyMap())).thenReturn(0L);
            assertTrue(svc.isLegacyPrivate(ref));assertTrue(svc.isLegacyPrivate(alias));
            assertTrue(Files.isRegularFile(rawMarker(folder,ref)));assertTrue(Files.isRegularFile(rawMarker(folder,alias)));
        } finally {new RuoYiConfig().setProfile(original);}
    }
}
