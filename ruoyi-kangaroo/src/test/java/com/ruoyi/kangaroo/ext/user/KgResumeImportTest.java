package com.ruoyi.kangaroo.ext.user;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import com.ruoyi.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
class KgResumeImportTest {
    @Test void parseOnlyLabelledPlausibleFields(){
        Map<String,String> fields=KgResumeImport.parse("称呼：小夏\n性别：女\n年龄：24岁\n电话：138 1234 5678\n所在城市：大理\n兴趣爱好：摄影、徒步\n技能：手冲咖啡\n价格：3000\n");
        assertEquals("小夏",fields.get("name"));assertEquals("24",fields.get("age"));assertEquals("13812345678",fields.get("phone"));
        assertEquals("摄影、徒步",fields.get("hobbies"));assertFalse(fields.containsKey("价格"));
        assertFalse(KgResumeImport.parse("年龄：11\n电话：hello").containsKey("age"));
    }
    @Test void txtAndDocxReadWithoutSaving()throws Exception{
        MockMultipartFile text=new MockMultipartFile("file","resume.txt","text/plain","昵称：小夏\n技能：拍照".getBytes(StandardCharsets.UTF_8));
        assertEquals("小夏",((Map<?,?>)KgResumeImport.read(text).get("fields")).get("name"));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write("<w:document xmlns:w='test'><w:p><w:r><w:t>称呼：阿木</w:t></w:r></w:p></w:document>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        MockMultipartFile doc=new MockMultipartFile("file","resume.docx","application/zip",bytes.toByteArray());
        assertEquals("阿木",((Map<?,?>)KgResumeImport.read(doc).get("fields")).get("name"));
        assertThrows(ServiceException.class,()->KgResumeImport.read(new MockMultipartFile("file",new byte[6*1024*1024])));
    }
    @Test void profilePreservesOmittedFieldsAndRejectsForeignMedia(){
        KgResumeMedia media=mock(KgResumeMedia.class);KgResumeProfile profile=new KgResumeProfile(media);
        String json=profile.merge(1L,"{\"hobbies\":\"徒步\"}",Map.of("background","咖啡店经历"));
        assertEquals("徒步",JSON.parseObject(json).getString("hobbies"));
        assertEquals("咖啡店经历",JSON.parseObject(json).getString("background"));
        doThrow(new ServiceException("foreign")).when(media).requireOwned(1L,"resume/2/file.jpg",false);
        assertThrows(ServiceException.class,()->profile.merge(1L,null,Map.of("lifePhoto","resume/2/file.jpg")));
        assertThrows(ServiceException.class,()->profile.merge(1L,null,Map.of("media",Collections.nCopies(7,Map.of("key","x")))));
        KgResumeMedia real=new KgResumeMedia(null);
        assertFalse(real.owned(1L,"resume/2/"+"a".repeat(32)+".jpg"));assertFalse(real.owned(1L,"../1/test.jpg"));
        assertTrue(real.owned(1L,"resume/1/"+"a".repeat(32)+".jpg"));
    }
}
