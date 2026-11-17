package com.ruoyi.kangaroo.ext.staff;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.stay.KgStayStaffService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Run only against a temporary database containing V110. */
class KgStayAgreementDatabaseTest {
    static KgJdbc db;
    static TransactionTemplate tx;
    static KgMerchantAgreements agreements;
    static Map<String,Object> published;
    @BeforeAll static void init() {
        String name=System.getenv("KG_MEMBER_TEST_DB");
        assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"));
        db=new KgJdbc(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));agreements=new KgMerchantAgreements(db);
        published=db.one("select notice_id,notice_content from sys_notice where notice_title=:t and status='0' order by notice_id desc limit 1",p("t",KgMerchantAgreements.title("stay")));
        assertNotNull(published);
        for(long s:Arrays.asList(8601L,8602L))db.update("insert ignore into kg_store(store_id,store_name,city,status,del_flag) values(:s,:n,'平潭','0','0')",p("s",s,"n","协议测试店"+s));
    }
    @BeforeEach void reset(){
        db.update("delete from kg_merchant_agreement_consent where store_id in (8601,8602)",p());
        db.update("update sys_notice set notice_content=:h,status='0' where notice_id=:i",p("h",published.get("noticeContent"),"i",published.get("noticeId")));
    }
    @AfterAll static void restore(){db.update("update sys_notice set notice_content=:h,status='0' where notice_id=:i",p("h",published.get("noticeContent"),"i",published.get("noticeId")));}
    StaffSupport.Ctx ctx(long store,long user){
        StaffSupport.Ctx c=new StaffSupport.Ctx();c.storeId=store;c.storeName="协议测试店"+store;c.role="merchant";
        c.me=new AppLoginUser();c.me.setUserId(user);c.me.setNickname("确认人"+user);return c;
    }
    Map<String,Object> accept(StaffSupport.Ctx c){return tx.execute(s->agreements.accept(c,"stay",p("agreed",true,"version",agreements.read(c,"stay").get("version"))));}
    void code(int code,Runnable action){assertEquals(Integer.valueOf(code),assertThrows(ServiceException.class,action::run).getCode());}

    @Test void v110SelectsFeeFreeRevisionAndKeepsPriorDocuments(){
        Map<String,Object>d=agreements.read(ctx(8601,9601),"stay");String html=d.get("html").toString();
        assertEquals(false,d.get("draft"));assertEquals(false,d.get("accepted"));
        for(String text:Arrays.asList("V2026.11.15.1","深圳市袋鼠星辰投资有限公司","第180天","最多连续30晚","自营房源不加价，不收取平台服务费","服务费0元，商家应收100元","自营房源按剩余实收全额结算，平台服务费仍为0","17.85元","平台在门店完成退房后审核退款申请"))assertTrue(html.contains(text),text);
        assertFalse(html.contains("内扣5%"));assertFalse(html.contains("自营房源按优惠后实收内扣服务费"));
        assertFalse(html.contains("___%"));assertFalse(html.contains("【草稿，未生效】"));
        assertEquals(1,db.count("select count(*) from sys_notice where notice_title=:t and status='1'",p("t",d.get("title"))));
        assertEquals(1,db.count("select count(*) from sys_notice where notice_title=:t and create_by='stay_agreement_v104' and notice_content like '%内扣5%服务费%'",p("t",d.get("title"))));
    }
    @Test void confirmationRequiresExplicitAgreementAndCurrentVersion(){
        StaffSupport.Ctx c=ctx(8601,9601);
        code(422,()->tx.execute(s->agreements.accept(c,"stay",p("agreed",false))));
        code(409,()->tx.execute(s->agreements.accept(c,"stay",p("agreed",true,"version","old-draft"))));
        code(428,()->tx.execute(s->{agreements.requireAccepted(c,"stay");return null;}));
        Map<String,Object>d=accept(c);assertEquals(true,d.get("accepted"));accept(c);
        tx.execute(s->{agreements.requireAccepted(c,"stay");return null;});
        assertEquals(1,db.count("select count(*) from kg_merchant_agreement_consent where store_id=8601",p()));
    }
    @Test void consentAndSnapshotAreScopedToStoreAndOperator(){
        StaffSupport.Ctx c=ctx(8601,9601);Map<String,Object>d=accept(c);
        long id=KgJdbc.asLong(((Map<?,?>)d.get("record")).get("id"));
        assertEquals(d.get("html"),agreements.record(c,id).get("contentHtml"));
        for(StaffSupport.Ctx other:Arrays.asList(ctx(8602,9601),ctx(8601,9602))){
            code(428,()->tx.execute(s->{agreements.requireAccepted(other,"stay");return null;}));
            code(404,()->agreements.record(other,id));
        }
    }
    @Test void laterRevisionRequiresNewConsentWithoutChangingTheOldRecord(){
        StaffSupport.Ctx c=ctx(8601,9601);Map<String,Object>first=accept(c);
        long id=KgJdbc.asLong(((Map<?,?>)first.get("record")).get("id"));
        db.update("update sys_notice set notice_content=:h where notice_id=:i",p("h",first.get("html")+"<p>测试修订内容</p>","i",published.get("noticeId")));
        assertEquals(false,agreements.read(c,"stay").get("accepted"));
        code(409,()->tx.execute(s->agreements.accept(c,"stay",p("agreed",true,"version",first.get("version")))));
        assertEquals(first.get("html"),agreements.record(c,id).get("contentHtml"));accept(c);
        assertEquals(2,((List<?>)agreements.read(c,"stay").get("records")).size());
    }
    @Test void roomWriteAndListingSubmissionCannotBypassPublishedAgreement(){
        StaffSupport.Ctx c=ctx(8601,9601);AppLoginUser user=spy(c.me);doReturn(true).when(user).hasStoreAccess(8601L);c.me=user;
        KgJdbc business=mock(KgJdbc.class);
        when(business.one(anyString(),anyMap())).thenReturn(p("storeId",8601L,"stayId",8701L,"status","draft"));
        KgStayStaffService staff=new KgStayStaffService();ReflectionTestUtils.setField(staff,"db",business);ReflectionTestUtils.setField(staff,"merchantAgreements",agreements);
        code(428,()->tx.execute(s->staff.saveRoomType(c,8701L,null,p("typeName","测试房型"))));
        code(428,()->tx.execute(s->staff.submit(c,8701L)));
        verify(business,never()).insert(anyString(),anyMap());verify(business,never()).update(anyString(),anyMap());
    }
}
