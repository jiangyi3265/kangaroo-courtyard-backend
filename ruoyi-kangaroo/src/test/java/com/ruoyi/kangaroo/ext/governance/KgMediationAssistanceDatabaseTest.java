package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.KgGovernanceExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.security.StaffGrantView;
import com.ruoyi.kangaroo.service.IKgStaffGrantService;

/** Production service/SQL, synthetic rows in a disposable MySQL schema. */
class KgMediationAssistanceDatabaseTest {
    final KgGovernanceExecutionTest fixture=new KgGovernanceExecutionTest();
    final String note="主理人现场核对后确认房间安排有差异，建议负责人组织双方沟通。";
    @BeforeAll static void initialize(){setup();}
    @BeforeEach void prepare(){
        fixture.reset();fixture.grant(9L,2L,"manager");fixture.grant(8L,2L,"manager");
        IKgStaffGrantService grants=mock(IKgStaffGrantService.class);
        when(grants.loadActiveGrantViews(anyLong())).thenAnswer(call->{
            List<StaffGrantView> views=new ArrayList<>();
            for(Map<String,Object> row:db.list("select store_id from kg_staff_grant where app_user_id=:u and role_code='manager' and status='0' and del_flag='0'",p("u",call.getArgument(0)))){
                StaffGrantView grant=new StaffGrantView();grant.setStoreId(com.ruoyi.kangaroo.ext.KgJdbc.asLong(row.get("storeId")));grant.setRoleCode("manager");grant.setActive(true);views.add(grant);
            }
            return views;
        });
        field(service,"grants",grants);
    }
    @AfterEach void cleanContext(){field(service,"grants",null);}
    void send(long cid,Long... files){run(()->service.command(9L,cid,"assistance",p("requestKey",UUID.randomUUID().toString(),"note",note,"fileIds",Arrays.asList(files)),false,""));}
    void submit(long actor,long cid,Map<String,Object> body){run(()->service.command(actor,cid,"assistance",body,false,""));}
    boolean containsNote(Map<String,Object> detail){return ((List<Map<String,Object>>)detail.get("log")).stream().anyMatch(row->note.equals(row.get("note")));}
    boolean containsFile(Map<String,Object> detail,long fid){return ((List<Map<String,Object>>)detail.get("evidence")).stream().anyMatch(row->Long.valueOf(fid).equals(com.ruoyi.kangaroo.ext.KgJdbc.asLong(row.get("fileId"))));}
    @Test void privateOpinionAndRawFilesAreOnlyVisibleToAuthorAndIndependentHandlers(){
        long cid=fixture.create(),fid=fixture.file(9L,false);send(cid,fid);
        assertEquals("pending",fixture.state(cid));
        for(long uid:Arrays.asList(9L,3L)){
            Map<String,Object> detail=service.detail(uid,cid,false);assertTrue(containsNote(detail));assertTrue(containsFile(detail,fid));
        }
        assertTrue(containsNote(service.detail(99L,cid,true)));
        for(long uid:Arrays.asList(1L,2L,8L)){
            Map<String,Object> detail=service.detail(uid,cid,false);assertFalse(containsNote(detail));assertFalse(containsFile(detail,fid));
            assertTrue(((List<Map<String,Object>>)detail.get("log")).stream().anyMatch(row->"assistance_received".equals(row.get("action"))));
            assertFalse(service.fileAccess(uid,p("caseId",cid,"ownerActor","app:9","redacted","0"),false));
        }
        assertTrue(service.fileAccess(9L,p("caseId",cid,"ownerActor","app:9","redacted","0"),false));
        assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and actor='app:9' and role_code='support' and action='assistance' and audience='private' and from_state=to_state",p("c",cid)));
    }
    @Test void closedCaseDoesNotAttachFilesOrCreateAuditOrNotifications(){
        long cid=fixture.create(),fid=fixture.file(9L,false);db.update("update kg_mediation_case set status='closed' where case_id=:c",p("c",cid));
        long logs=db.count("select count(*) from kg_mediation_log where case_id=:c",p("c",cid));reset(messages);
        assertEquals(false,service.detail(9L,cid,false).get("canAssist"));
        assertEquals(409,assertThrows(ServiceException.class,()->send(cid,fid)).getCode());
        assertNull(db.one("select case_id from kg_mediation_file where file_id=:f",p("f",fid)).get("caseId"));
        assertEquals(logs,db.count("select count(*) from kg_mediation_log where case_id=:c",p("c",cid)));verifyNoInteractions(messages);
    }
    @Test void aForeignSecondAttachmentRollsBackTheFirstAttachmentAndAudit(){
        long cid=fixture.create(),mine=fixture.file(9L,false),other=fixture.file(1L,false);reset(messages);
        assertEquals(404,assertThrows(ServiceException.class,()->send(cid,mine,other)).getCode());
        assertNull(db.one("select case_id from kg_mediation_file where file_id=:f",p("f",mine)).get("caseId"));
        assertEquals(0,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));verifyNoInteractions(messages);
    }
    @Test void revocationAfterOpeningDetailBlocksSubmissionAndOwnPrivateFileAccess(){
        long cid=fixture.create(),fid=fixture.file(9L,false);send(cid,fid);assertEquals(true,service.detail(9L,cid,false).get("canAssist"));
        db.update("update kg_staff_grant set status='1' where app_user_id=9 and store_id=2",p());
        assertEquals(403,assertThrows(ServiceException.class,()->send(cid)).getCode());
        assertFalse(service.fileAccess(9L,p("caseId",cid,"ownerActor","app:9","redacted","0"),false));
        assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));
    }
    @Test void currentAssignedHandlerGetsNotificationAndStageRemainsUnchanged(){
        long cid=fixture.create();fixture.ready(cid);reset(messages);send(cid);
        assertEquals("ready",fixture.state(cid));
        verify(messages).send(eq(3L),eq("staff"),eq("system"),eq("换工主理人已补充调解材料，请查看"),argThat(text->!text.contains(note)),eq("mediation"),eq(cid),eq("/pages/governance/mediation?id="+cid+"&mode=city"));
        assertEquals(1,db.count("select count(*) from kg_mediation_event where case_id=:c and audience='staff:3' and content='换工主理人已补充调解材料，请查看'",p("c",cid)));
    }
    @Test void createLimitsRemainTheSameAndAmountErrorExplainsYuan(){
        long app=fixture.app(1L);Map<String,Object> body=fixture.body(1L,app);body.put("amountFen",100000001L);
        assertEquals("涉及金额请填写 0～1000000 元",assertThrows(ServiceException.class,()->in(()->service.create(1L,body))).getMessage());
        body.put("amountFen",0);body.put("narrative","事情经过太短");
        assertTrue(assertThrows(ServiceException.class,()->in(()->service.create(1L,body))).getMessage().startsWith("事情经过请填写 50–10000 字"));
    }
    @Test void committedSubmissionCanRetryAfterLostResponseIncludingAlreadyBoundFiles(){
        long cid=fixture.create(),first=fixture.file(9L,false),second=fixture.file(9L,false);
        Map<String,Object> body=p("requestKey","lost-response-001","note",note,"fileIds",Arrays.asList(first,second));reset(messages);
        submit(9,cid,body);
        // The client got no response, then retries with the same attachment IDs in a different order.
        body.put("fileIds",Arrays.asList(second,first,first));submit(9,cid,body);
        assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));
        assertEquals(2,db.count("select count(*) from kg_mediation_file where case_id=:c and owner_actor='app:9'",p("c",cid)));
        verify(messages,times(1)).send(eq(3L),eq("staff"),eq("system"),eq("换工主理人已补充调解材料，请查看"),anyString(),eq("mediation"),eq(cid),anyString());
        Map<String,Object> receipt=db.one("select event_key,content from kg_mediation_event where case_id=:c and audience='internal'",p("c",cid));
        assertTrue(String.valueOf(receipt.get("content")).matches("[a-f0-9]{64}"));assertFalse(String.valueOf(receipt.get("eventKey")).contains("lost-response-001"));
        for(boolean hq:Arrays.asList(false,true))assertTrue(((List<Map<String,Object>>)service.detail(hq?99L:3L,cid,hq).get("events")).stream().noneMatch(e->"internal".equals(e.get("audience"))));
    }
    @Test void sameKeyWithDifferentNoteOrAttachmentsIsRejectedWithoutWrites(){
        long cid=fixture.create(),fid=fixture.file(9L,false);Map<String,Object> body=p("requestKey","stable-request-01","note",note,"fileIds",Arrays.asList(fid));submit(9,cid,body);reset(messages);
        body.put("note",note+"追加不同内容");ServiceException different=assertThrows(ServiceException.class,()->submit(9,cid,body));
        assertEquals(409,different.getCode());assertTrue(different.getMessage().contains("已用于其他内容"));
        body.put("note",note);body.put("fileIds",Collections.emptyList());assertEquals(409,assertThrows(ServiceException.class,()->submit(9,cid,body)).getCode());
        assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));verifyNoInteractions(messages);
    }
    @Test void differentKeysCanIntentionallyResubmitSameOpinionAndCiteOwnCaseFiles(){
        long cid=fixture.create();List<Long> files=new ArrayList<>();for(int i=0;i<9;i++)files.add(fixture.file(9L,false));
        Map<String,Object> body=p("requestKey","intentional-first","note",note,"fileIds",files);
        submit(9,cid,body);body.put("requestKey","intentional-second");submit(9,cid,body);
        assertEquals(2,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));
        assertEquals(2,db.count("select count(*) from kg_mediation_event where case_id=:c and audience='internal'",p("c",cid)));
        assertEquals(9,db.count("select count(*) from kg_mediation_file where case_id=:c and owner_actor='app:9'",p("c",cid)));
        body.put("requestKey","intentional-third");body.put("fileIds",Arrays.asList(fixture.file(9L,false)));
        assertEquals(409,assertThrows(ServiceException.class,()->submit(9,cid,body)).getCode());
        assertEquals(2,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));
    }
    @Test void receiptIsScopedToActorAndCaseAndRemainsSafeAfterCaseCloses(){
        long cid=fixture.create();Map<String,Object> body=p("requestKey","same-key-per-actor","note",note);
        submit(9,cid,body);submit(8,cid,body);
        assertEquals(2,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));
        long other=fixture.create();submit(9,other,body);
        db.update("update kg_mediation_case set status='closed' where case_id=:c",p("c",cid));submit(9,cid,body);
        body.put("requestKey","closed-new-request");assertEquals(409,assertThrows(ServiceException.class,()->submit(9,cid,body)).getCode());
        assertEquals(2,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));
    }
    @Test void failedAttachmentValidationDoesNotConsumeRequestKey(){
        long cid=fixture.create(),mine=fixture.file(9L,false),foreign=fixture.file(1L,false);
        Map<String,Object> body=p("requestKey","failure-then-retry","note",note,"fileIds",Arrays.asList(mine,foreign));
        assertThrows(ServiceException.class,()->submit(9,cid,body));assertEquals(0,db.count("select count(*) from kg_mediation_event where case_id=:c and audience='internal'",p("c",cid)));
        body.put("fileIds",Arrays.asList(mine));submit(9,cid,body);
        assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));
    }
    @Test void concurrentDuplicateRequestsSerializeToOneReceiptAuditAndNotification()throws Exception{
        long cid=fixture.create(),fid=fixture.file(9L,false);Map<String,Object> body=p("requestKey","concurrent-retry-001","note",note,"fileIds",Arrays.asList(fid));reset(messages);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try{
            List<java.util.concurrent.Future<?>> requests=new ArrayList<>();
            for(int i=0;i<2;i++)requests.add(pool.submit(()->{try{start.await();submit(9,cid,body);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));
            start.countDown();for(java.util.concurrent.Future<?> request:requests)request.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertEquals(1,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",cid)));
        assertEquals(1,db.count("select count(*) from kg_mediation_event where case_id=:c and audience='internal'",p("c",cid)));
        verify(messages,times(1)).send(eq(3L),eq("staff"),eq("system"),eq("换工主理人已补充调解材料，请查看"),anyString(),eq("mediation"),eq(cid),anyString());
    }
    @Test void anAuthorCannotMoveOrCiteTheirOwnRawAttachmentFromAnotherCase(){
        long first=fixture.create(),fid=fixture.file(9L,false);send(first,fid);long second=fixture.create();
        assertEquals(404,assertThrows(ServiceException.class,()->send(second,fid)).getCode());
        assertEquals(first,com.ruoyi.kangaroo.ext.KgJdbc.asLong(db.one("select case_id from kg_mediation_file where file_id=:f",p("f",fid)).get("caseId")));
        assertEquals(0,db.count("select count(*) from kg_mediation_log where case_id=:c and action='assistance'",p("c",second)));
    }
}
