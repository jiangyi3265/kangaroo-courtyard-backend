package com.ruoyi.kangaroo.service.impl;

import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.kangaroo.domain.KgJob;
import com.ruoyi.kangaroo.ext.staff.KgPublicationFeeService;
import com.ruoyi.kangaroo.mapper.KgApplicationMapper;
import com.ruoyi.kangaroo.mapper.KgJobMapper;

class KgPublicationFeeHooksTest {
    KgJobServiceImpl service;
    KgPublicationFeeService fees;
    KgJobMapper jobs;

    @BeforeEach void setup() {
        SysUser user=new SysUser();user.setUserName("test");
        LoginUser login=new LoginUser();login.setUser(user);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null));
        service=new KgJobServiceImpl();jobs=mock(KgJobMapper.class);fees=mock(KgPublicationFeeService.class);
        ReflectionTestUtils.setField(service,"kgJobMapper",jobs);
        ReflectionTestUtils.setField(service,"kgApplicationMapper",mock(KgApplicationMapper.class));
        ReflectionTestUtils.setField(service,"publicationFees",fees);
        KgJob job=new KgJob();job.setJobId(1L);job.setTitle("测试任务");job.setJobType("online");job.setStatus("draft");job.setSource("staff");
        when(jobs.selectKgJobByJobId(1L)).thenReturn(job);
        when(jobs.updateKgJob(any())).thenReturn(1);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void adminApprovalActivatesCapturedFee() {
        service.changeStatus(1L,"published","");verify(fees).published(1L);
    }
    @Test void merchantReviewApprovalUsesSameFeeHook() {
        service.publishMerchantDraft(1L,"test");verify(fees).published(1L);
    }
    @Test void rejectionAndDraftDeletionVoidUnpublishedFees() {
        service.closeMerchantDraft(1L,"test","资料有误");verify(fees).closed(1L);
        clearInvocations(fees);
        service.deleteKgJobByJobIds(new Long[]{1L});verify(fees).closed(1L);
    }
}
