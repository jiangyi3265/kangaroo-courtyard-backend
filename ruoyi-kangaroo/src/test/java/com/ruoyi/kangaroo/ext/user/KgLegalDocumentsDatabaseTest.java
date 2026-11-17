package com.ruoyi.kangaroo.ext.user;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.controller.app.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
class KgLegalDocumentsDatabaseTest {
    KgJdbc db;KgLegalDocuments documents;KgAccountService accounts;
    String text="<h2>协议正文</h2><p>"+String.join("",Collections.nCopies(25,"按用户主动选择的功能处理必要信息。"))+"</p>";
    @BeforeEach void setup(){
        String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
        db=new KgJdbc(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD")));
        db.update("delete from sys_notice",p());db.update("delete from kg_consent",p());
        documents=new KgLegalDocuments(db);accounts=new KgAccountService();
        ReflectionTestUtils.setField(accounts,"db",db);ReflectionTestUtils.setField(accounts,"legalDocuments",documents);
        AppLoginUser user=new AppLoginUser();user.setUserId(976L);AppUserContext.set(user);
    }
    @AfterEach void clear(){AppUserContext.remove();}
    long publish(String title,String html,String status){return db.insert("sys_notice",p("notice_title",title,"notice_type","2","notice_content",html.getBytes(java.nio.charset.StandardCharsets.UTF_8),"status",status,"create_time",java.time.LocalDateTime.now()));}
    @Test void absentBlankOrDisabledDocumentsCannotAuthorizeOrCreateConsent(){
        assertEquals(false,documents.read("privacy").get("configured"));
        assertThrows(ServiceException.class,()->accounts.agreeLegal());
        publish("用户协议",text,"0");publish("隐私政策",text,"1");assertThrows(ServiceException.class,documents::requirePublished);
        publish("隐私政策","<p>&nbsp; &#160; <br/></p><script>"+text+"</script>","0");
        assertEquals(false,documents.read("privacy").get("configured"));
        assertThrows(ServiceException.class,()->accounts.agreeLegal());
        assertEquals(0,db.count("select count(*) from kg_consent",p()));
    }
    @Test void aliasesUtf8AndAnonymousResponseReturnCompleteText(){
        publish("《用户服务协议》",text,"0");publish("《隐私政策》",text+"<p>联系方式</p>","0");
        assertEquals(2,documents.requirePublished().size());assertEquals(text,documents.read("terms").get("html"));
        AppUserContext.remove();AppLegalController controller=new AppLegalController();ReflectionTestUtils.setField(controller,"documents",documents);
        MockHttpServletResponse response=new MockHttpServletResponse();assertEquals(200,controller.legal("privacy",response).get("code"));assertEquals("no-store",response.getHeader("Cache-Control"));
        assertThrows(ServiceException.class,()->documents.read("other"));
    }
    @Test void sameDayEditsHaveNewVersionsAndOnlyExactConsentIsRecorded(){
        publish("用户协议",text,"0");long id=publish("隐私政策",text,"0");
        Map<String,String> versions=new HashMap<>();versions.put("termsVersion",String.valueOf(documents.read("terms").get("version")));versions.put("privacyVersion",String.valueOf(documents.read("privacy").get("version")));
        accounts.agreeLegal(versions);accounts.agreeLegal(versions);assertEquals(2,db.count("select count(*) from kg_consent",p()));
        db.update("update sys_notice set notice_content=:html where notice_id=:id",p("html",text+"<p>本次补充</p>","id",id));
        assertNotEquals(versions.get("privacyVersion"),documents.read("privacy").get("version"));
        assertThrows(ServiceException.class,()->accounts.agreeLegal(versions));assertEquals(2,db.count("select count(*) from kg_consent",p()));
        versions.put("privacyVersion",String.valueOf(documents.read("privacy").get("version")));accounts.agreeLegal(versions);assertEquals(3,db.count("select count(*) from kg_consent",p()));
    }
    @Test void missingDocumentsBlockExternalLoginAndPhoneExchangeBeforeAnyOtherDependencies(){
        AppAuthController controller=new AppAuthController();ReflectionTestUtils.setField(controller,"legalDocuments",documents);
        ServiceException login=assertThrows(ServiceException.class,()->controller.wxLogin(Collections.singletonMap("code","unused"),new MockHttpServletRequest()));
        assertEquals(503,login.getCode());assertThrows(ServiceException.class,()->controller.phoneVerify(Collections.singletonMap("code","unused")));
    }
}
