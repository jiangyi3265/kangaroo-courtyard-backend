package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppGovernanceController;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.service.IKgMessageService;

class KgMediationAssistanceTest {
    final KgMediationSupportAccessTest f=new KgMediationSupportAccessTest();
    final String note="现场已核对入住安排，建议负责人联系双方确认实际房间。";
    final IKgMessageService messages=mock(IKgMessageService.class);
    KgMediationAssistanceTest(){ReflectionTestUtils.setField(f.service,"messages",messages);}
    void send(Map<String,Object> body){if(!body.containsKey("requestKey"))body.put("requestKey","assistance-request-test");f.service.command(9L,5L,"assistance",body,false,"");}
    void record(Map<String,Object> record){when(f.db.one(contains("from kg_mediation_case"),anyMap())).thenAnswer(x->new HashMap<>(record));}
    void noWrites(){verify(f.db,never()).insert(anyString(),anyMap());verify(f.db,never()).update(anyString(),anyMap());}
    @Test void authorizedManagerCanSubmitPrivateAssistanceWithoutChangingDecisionState(){
        send(p("note",note));
        verify(f.db).insert(eq("kg_mediation_log"),argThat(m->"assistance".equals(m.get("action"))&&"private".equals(m.get("audience"))&&"support".equals(m.get("role_code"))&&"app:9".equals(m.get("actor"))&&note.equals(m.get("note"))&&m.get("from_state").equals(m.get("to_state"))));
        verify(f.db).insert(eq("kg_mediation_log"),argThat(m->"assistance_received".equals(m.get("action"))&&"parties".equals(m.get("audience"))&&!note.equals(m.get("note"))));
        verify(f.db,never()).update(contains("set status="),anyMap());
        verify(f.db,never()).update(contains("set decision="),anyMap());
    }
    @Test void closedAndUnknownStatesAreNotWritableOrAdvertised(){
        for(String state:Arrays.asList("closed","unexpected")){
            Map<String,Object> row=f.record();row.put("status",state);record(row);
            assertEquals(false,f.service.detail(9L,5L,false).get("canAssist"));
            assertEquals(409,assertThrows(ServiceException.class,()->send(p("note",note))).getCode());
        }
        noWrites();
    }
    @Test void everyExistingOpenStateAcceptsAssistanceWithoutChangingStage(){
        for(String state:Arrays.asList("pending","accepted","verifying","ready","jury","confirmation","appealed")){
            Map<String,Object> row=f.record();row.put("status",state);record(row);
            assertEquals(true,f.service.detail(9L,5L,false).get("canAssist"));send(p("note",note));
        }
        verify(f.db,never()).update(contains("set status="),anyMap());
    }
    @Test void revokedManagerCannotUseTheNewAction(){
        when(f.grants.loadActiveGrantViews(9L)).thenReturn(Collections.emptyList());
        assertEquals(403,assertThrows(ServiceException.class,()->send(p("note",note))).getCode());noWrites();
    }
    @Test void differentStoreOrBusinessTypeDoesNotGainWriteAccess(){
        Map<String,Object> row=f.record();row.put("storeId",3L);record(row);
        assertEquals(403,assertThrows(ServiceException.class,()->send(p("note",note))).getCode());
        row.put("storeId",2L);row.put("orderType","stay");record(row);
        assertEquals(403,assertThrows(ServiceException.class,()->send(p("note",note))).getCode());noWrites();
    }
    @Test void ownCaseAndMerchantCannotMasqueradeAsAnIndependentManager(){
        Map<String,Object> row=f.record();row.put("userId",9L);record(row);
        assertEquals(403,assertThrows(ServiceException.class,()->send(p("note",note))).getCode());
        record(f.record());when(f.orders.merchant(9L,2L)).thenReturn(true);
        assertEquals(403,assertThrows(ServiceException.class,()->send(p("note",note))).getCode());noWrites();
    }
    @Test void assistanceCannotBypassAttachmentOwnershipAndRedactionGuards(){
        when(f.db.one(contains("from kg_mediation_file"),anyMap())).thenReturn(null);
        assertEquals(404,assertThrows(ServiceException.class,()->send(p("note",note,"fileIds",Arrays.asList(30L)))).getCode());
        verify(f.db).one(contains("owner_actor=:a and (case_id is null or case_id=:c) for update"),eq(p("f",30L,"a","app:9","c",5L)));
        when(f.db.one(contains("from kg_mediation_file"),anyMap())).thenReturn(p("redacted","1"));
        assertEquals(409,assertThrows(ServiceException.class,()->send(p("note",note,"fileIds",Arrays.asList(30L)))).getCode());noWrites();
    }
    @Test void explanationAndCumulativeFileLimitsAreEnforcedBeforeWriting(){
        for(String invalid:Arrays.asList("短",new String(new char[5001]).replace('\0','字')))
            assertEquals(422,assertThrows(ServiceException.class,()->send(p("note",invalid))).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->send(p("note",note,"fileIds","not-a-list"))).getCode());
        when(f.db.count(contains("from kg_mediation_file"),anyMap())).thenReturn(9L);
        when(f.db.one(contains("from kg_mediation_file"),anyMap())).thenReturn(p("redacted","0"));
        assertEquals(409,assertThrows(ServiceException.class,()->send(p("note",note,"fileIds",Arrays.asList(30L)))).getCode());noWrites();
    }
    @Test void assignedIndependentHandlerReceivesGenericNotificationOnly(){
        Map<String,Object> row=f.record();row.put("handlerId",3L);record(row);when(f.city.covers(3L,2L)).thenReturn(true);
        when(f.db.insert(eq("kg_mediation_log"),anyMap())).thenReturn(81L);
        when(f.db.update(startsWith("insert ignore into kg_mediation_event"),anyMap())).thenReturn(1);
        send(p("note",note));
        verify(messages).send(eq(3L),eq("staff"),eq("system"),eq("换工主理人已补充调解材料，请查看"),argThat(v->!v.contains(note)),eq("mediation"),eq(5L),eq("/pages/governance/mediation?id=5&mode=city"));
        verify(f.db,never()).update(startsWith("insert into kg_mediation_event"),anyMap());
    }
    @Test void noEligibleHandlerCreatesAHeadquartersTodoWithoutLeakingNote(){
        send(p("note",note));
        verify(f.db).update(contains("on duplicate key update status='pending'"),eq(p("c",5L,"k","5:hq:assistance")));
        verifyNoInteractions(messages);
    }
    @Test void existingAuthenticatedCommandApiAcceptsAssistanceAndNeverTrustsBodyUserId(){
        AppGovernanceController controller=new AppGovernanceController();KgMediationService service=mock(KgMediationService.class);
        ReflectionTestUtils.setField(controller,"mediation",service);AppLoginUser login=new AppLoginUser();login.setUserId(9L);AppUserContext.set(login);
        Map<String,Object> body=p("note",note,"fileIds",Collections.emptyList(),"userId",100L);
        try{assertEquals(200,controller.command(5L,"assistance",body).get("code"));verify(service).command(9L,5L,"assistance",body,false,"");}finally{AppUserContext.remove();}
    }
    @Test void contextPublishesExistingCreateLimitsWithoutExpandingEligibility(){
        KgMediationService checked=spy(f.service);doReturn(p()).when(checked).juror(9L);
        Map<String,Object> rules=(Map<String,Object>)checked.context(9L).get("formRules");
        assertEquals(p("narrativeMin",50,"narrativeMax",10000,"claimsMin",2,"claimsMax",2000,"amountMaxFen",100000000),rules);
    }
    @Test void assistanceRequiresAStableRequestKeyOfEightToSixtyFourCharacters(){
        for(String key:Arrays.asList("", "short",new String(new char[65]).replace('\0','x')))
            assertEquals(422,assertThrows(ServiceException.class,()->send(p("requestKey",key,"note",note))).getCode());
        noWrites();
    }
}
