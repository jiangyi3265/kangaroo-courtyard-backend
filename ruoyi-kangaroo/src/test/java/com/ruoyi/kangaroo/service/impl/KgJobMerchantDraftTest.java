package com.ruoyi.kangaroo.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.KgOnlineTaskService;
import com.ruoyi.kangaroo.mapper.KgJobMapper;
import com.ruoyi.kangaroo.testsupport.UploadedImageFixture;

/** 小程序上架 / 关闭商家草稿（V63）和后台直接处理时同步需求状态。不连库。 */
class KgJobMerchantDraftTest
{
    private static final String PHOTO = "/profile/upload/2026/10/30/a.jpg";

    private final KgJobServiceImpl service = new KgJobServiceImpl();
    private final KgJobMapper jobs = mock(KgJobMapper.class);
    private final KgJdbc jdbc = mock(KgJdbc.class);
    private final KgOnlineTaskService online = mock(KgOnlineTaskService.class);
    private UploadedImageFixture files;

    @BeforeEach
    void setup()
    {
        files = new UploadedImageFixture(PHOTO);
        ReflectionTestUtils.setField(service, "kgJobMapper", jobs);
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        ReflectionTestUtils.setField(service, "onlineTasks", online);
        when(jobs.updateKgJob(any())).thenReturn(1);
        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName("boss");
        LoginUser login = new LoginUser();
        login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, Collections.emptyList()));
    }

    @AfterEach
    void clear()
    {
        SecurityContextHolder.clearContext();
        files.close();
    }

    @Test
    void deletingADraftClosesItsRequestAndTellsTheMerchant()
    {
        job("volunteer", "draft", null);
        com.ruoyi.kangaroo.mapper.KgApplicationMapper apps = mock(com.ruoyi.kangaroo.mapper.KgApplicationMapper.class);
        com.ruoyi.kangaroo.ext.staff.StaffSupport support = mock(com.ruoyi.kangaroo.ext.staff.StaffSupport.class);
        ReflectionTestUtils.setField(service, "kgApplicationMapper", apps);
        ReflectionTestUtils.setField(service, "staffSupport", support);
        when(jobs.deleteKgJobByJobIds(any(), anyString())).thenReturn(1);
        when(jdbc.list(contains("from kg_merchant_request"), anyMap())).thenReturn(java.util.Arrays.<java.util.Map<String, Object>>asList(
                new java.util.HashMap<String, Object>(KgJdbc.p("requestId", 500L, "merchantUserId", 1000L, "storeId", 102L, "title", "招换工"))));

        assertEquals(1, service.deleteKgJobByJobIds(new Long[] { 7L }));

        verify(jdbc).update(contains("set status = 'closed', return_reason = '平台已删除'"), argThat(m -> java.util.Arrays.asList(7L).equals(m.get("ids"))));
        verify(support).notifyStaff(eq(1000L), anyString(), eq("需求已关闭"), contains("招换工"), eq("merchant_request"), eq(500L), anyString());
    }

    @Test
    void deletingAnOldDraftWithoutARequestChangesNothingElse()
    {
        job("volunteer", "draft", null);
        ReflectionTestUtils.setField(service, "kgApplicationMapper", mock(com.ruoyi.kangaroo.mapper.KgApplicationMapper.class));
        when(jdbc.list(contains("from kg_merchant_request"), anyMap())).thenReturn(Collections.<java.util.Map<String, Object>>emptyList());
        service.deleteKgJobByJobIds(new Long[] { 7L });
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    void savingAJobInTheAdminCarriesTitleAndContentBackToTheRequest()
    {
        KgJob j = job("volunteer", "draft", null);
        j.setContent("改过的内容");
        ReflectionTestUtils.invokeMethod(service, "syncMerchantRequestText", j);
        verify(jdbc).update(contains("update kg_merchant_request set title = :t"), argThat(m -> "招换工".equals(m.get("t")) && "改过的内容".equals(m.get("c")) && Long.valueOf(7L).equals(m.get("j")) && "boss".equals(m.get("by"))));

        clearInvocations(jdbc);
        KgJob noContent = job("volunteer", "draft", null);
        ReflectionTestUtils.invokeMethod(service, "syncMerchantRequestText", noContent);
        verify(jdbc).update(contains("coalesce(:c, content)"), argThat(m -> m.containsKey("c") && m.get("c") == null));
    }

    @Test
    void deleteRunsInOneTransaction() throws Exception
    {
        assertNotNull(KgJobServiceImpl.class.getMethod("deleteKgJobByJobIds", Long[].class).getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test
    void publishRefusesAnEmptyWorkplace()
    {
        KgJob j = job("volunteer", "draft", "[\"" + PHOTO + "\"]");
        j.setAddress("  ");
        assertEquals(422, assertThrows(ServiceException.class, () -> service.publishMerchantDraft(7L, "staff:1")).getCode());
        verify(jobs, never()).updateKgJob(any());
    }

    private KgJob job(String type, String status, String images)
    {
        KgJob j = new KgJob();
        j.setJobId(7L);
        j.setStoreId(102L);
        j.setTitle("招换工");
        j.setJobType(type);
        j.setStatus(status);
        j.setImages(images);
        j.setSource("staff");
        j.setAddress("喜洲古镇");
        when(jobs.selectKgJobByJobId(7L)).thenReturn(j);
        return j;
    }

    private KgJob updated()
    {
        ArgumentCaptor<KgJob> c = ArgumentCaptor.forClass(KgJob.class);
        verify(jobs).updateKgJob(c.capture());
        return c.getValue();
    }

    @Test
    void publishRunsTheSameChecksAsTheBackOffice()
    {
        job("volunteer", "draft", "[\"" + PHOTO + "\"]");
        service.publishMerchantDraft(7L, "staff:9");
        KgJob u = updated();
        assertEquals("published", u.getStatus());
        assertNotNull(u.getPublishTime());
        assertEquals("staff:9", u.getUpdateBy());
        verify(jobs).lockRecruitment(7L);
    }

    @Test
    void offlineDraftWithoutPhotoCannotBePublished()
    {
        job("parttime", "draft", null);
        assertThrows(ServiceException.class, () -> service.publishMerchantDraft(7L, "staff:9"));
        verify(jobs, never()).updateKgJob(any());
    }

    @Test
    void onlineTaskGoesThroughTheDeadlineCheck()
    {
        job("online", "draft", null);
        doThrow(new ServiceException("过期", 409)).when(online).checkPublishable(7L, "draft");
        assertEquals(409, assertThrows(ServiceException.class, () -> service.publishMerchantDraft(7L, "staff:9")).getCode());
        verify(jobs, never()).updateKgJob(any());
    }

    @Test
    void onlyDraftsCanBePublishedOrClosed()
    {
        job("volunteer", "published", "[\"" + PHOTO + "\"]");
        assertEquals(409, assertThrows(ServiceException.class, () -> service.publishMerchantDraft(7L, "staff:9")).getCode());
        assertEquals(409, assertThrows(ServiceException.class, () -> service.closeMerchantDraft(7L, "staff:9", "不行")).getCode());
        verify(jobs, never()).updateKgJob(any());
    }

    @Test
    void closeRecordsTheReason()
    {
        job("volunteer", "draft", null);
        service.closeMerchantDraft(7L, "staff:9", "待遇没写清");
        KgJob u = updated();
        assertEquals("closed", u.getStatus());
        assertNotNull(u.getCloseTime());
        assertTrue(u.getRemark().contains("待遇没写清"));
    }

    @Test
    void backOfficePublishMarksTheRequest()
    {
        job("online", "draft", null);
        service.changeStatus(7L, "published", null);
        verify(jdbc).update(contains("update kg_merchant_request"), argThat(p -> "done".equals(p.get("st")) && Long.valueOf(7L).equals(p.get("j"))));
        org.mockito.Mockito.clearInvocations(jdbc);

        job("volunteer", "draft", "[\"" + PHOTO + "\"]");
        service.changeStatus(7L, "published", null);
        verify(jdbc).update(contains("update kg_merchant_request"), argThat(p -> "published".equals(p.get("st"))));
    }

    @Test
    void backOfficeRejectClosesTheRequestWithTheReason()
    {
        job("volunteer", "draft", null);
        service.changeStatus(7L, "closed", "资料不全");
        verify(jdbc).update(contains("update kg_merchant_request"), argThat(p -> "closed".equals(p.get("st")) && "资料不全".equals(p.get("why"))));
    }

    @Test
    void laterStatusChangesDoNotTouchTheRequest()
    {
        job("volunteer", "published", "[\"" + PHOTO + "\"]");
        service.changeStatus(7L, "paused", null);
        verify(jdbc, never()).update(contains("update kg_merchant_request"), anyMap());
    }
}
