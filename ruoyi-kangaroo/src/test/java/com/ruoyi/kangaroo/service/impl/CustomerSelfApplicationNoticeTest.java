package com.ruoyi.kangaroo.service.impl;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.kangaroo.mapper.KgApplicationMapper;
import com.ruoyi.kangaroo.domain.KgApplication;
import com.ruoyi.kangaroo.service.*;

class CustomerSelfApplicationNoticeTest {
 @Test void applicantWhoIsAlsoStaffDoesNotReceiveOwnStaffNotice(){
  KgApplicationServiceImpl service=new KgApplicationServiceImpl();
  KgApplicationMapper mapper=mock(KgApplicationMapper.class);IKgStaffGrantService grants=mock(IKgStaffGrantService.class);IKgMessageService messages=mock(IKgMessageService.class);
  ReflectionTestUtils.setField(service,"kgApplicationMapper",mapper);ReflectionTestUtils.setField(service,"kgStaffGrantService",grants);ReflectionTestUtils.setField(service,"kgMessageService",messages);
  KgApplication app=new KgApplication();app.setAppUserId(7L);when(mapper.selectKgApplicationByAppId(12L)).thenReturn(app);
  when(grants.listActiveStaffUserIds(1L)).thenReturn(Arrays.asList(7L,9L));
  ReflectionTestUtils.invokeMethod(service,"notifyStaff",1L,"apply","报名进度","消息内容",12L,"/pages/staff/applications?appId=12");
  verify(messages,never()).send(eq(7L),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
  verify(messages).send(9L,"staff","apply","报名进度","消息内容","application",12L,"/pages/staff/applications?appId=12");
 }
}
