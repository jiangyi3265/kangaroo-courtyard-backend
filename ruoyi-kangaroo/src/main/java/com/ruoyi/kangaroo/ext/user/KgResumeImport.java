package com.ruoyi.kangaroo.ext.user;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.*;
import com.ruoyi.common.exception.ServiceException;

/** 提取 PDF / DOCX / TXT 的文字字段到待确认草稿，不自动保存、不发送至第三方。 */
public final class KgResumeImport {
    private KgResumeImport() {}
    public static Map<String,Object> read(MultipartFile file) throws IOException {
        if(file==null || file.isEmpty() || file.getSize()>5*1024*1024)throw new ServiceException("请上传 5MB 内的 PDF、DOCX 或 TXT 简历",422);
        byte[] bytes=file.getBytes();
        String text;
        try {
            if(bytes.length>4 && bytes[0]=='%' && bytes[1]=='P' && bytes[2]=='D' && bytes[3]=='F') {
                try(PDDocument pdf=Loader.loadPDF(bytes)) {
                    if(pdf.isEncrypted() || pdf.getNumberOfPages()>10)throw new ServiceException("请使用未加密、10 页以内的简历",422);
                    text=new PDFTextStripper().getText(pdf);
                }
            } else if(bytes.length>2 && bytes[0]=='P' && bytes[1]=='K') text=docx(bytes);
            else {
                text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if(text.chars().anyMatch(c->c<32 && c!='\n' && c!='\r' && c!='\t'))throw new IOException("not text");
            }
        } catch(ServiceException e){throw e;}
        catch(Exception e){throw new ServiceException("无法读取此文件，请使用文字版 PDF、DOCX 或 UTF-8 TXT，也可以手动填写",422);}
        Map<String,String> fields=parse(text);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("fields",fields);
        result.put("message",fields.isEmpty()?"没有识别出可填写字段，扫描图片请手动填写":"已提取 "+fields.size()+" 项资料，请核对后保存");
        return result;
    }
    static String docx(byte[] bytes) throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; int entries=0, total=0;
            while((entry=zip.getNextEntry())!=null) {
                if(++entries>200)throw new IOException("too many entries");
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                byte[] chunk=new byte[8192];int count;
                while(buffer.size()<=1024*1024 && (count=zip.read(chunk,0,Math.min(chunk.length,1024*1024+1-buffer.size())))!=-1) buffer.write(chunk,0,count);
                byte[] data=buffer.toByteArray();total+=data.length;
                if(data.length>1024*1024 || total>8*1024*1024)throw new IOException("oversized document");
                if(!entry.getName().equals("word/document.xml"))continue;
                DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                f.setFeature("http://xml.org/sax/features/external-general-entities",false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                Document doc=f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
                NodeList paragraphs=doc.getElementsByTagNameNS("*","p");StringBuilder out=new StringBuilder();
                for(int i=0;i<paragraphs.getLength();i++)out.append(paragraphs.item(i).getTextContent()).append('\n');
                return out.toString();
            }
        }
        throw new IOException("not docx");
    }
    public static Map<String,String> parse(String text) {
        if(text.length()>100000)throw new ServiceException("简历内容过长，请精简后导入",422);
        Map<String,String> out=new LinkedHashMap<>();
        String[][] labels={{"name","姓名|昵称|称呼"},{"gender","性别"},{"age","年龄"},{"phone","联系电话|手机号码|手机号|电话|手机"},{"city","所在城市|现居城市|现居地"},{"hobbies","兴趣爱好|爱好"},{"personality","性格"},{"intro","自我介绍|个人简介"},{"skill","本人特长|技能特长|专业技能|技能"},{"background","相关经历|工作经历|专业背景"}};
        for(String[] label:labels) {
            Matcher m=Pattern.compile("(?m)(?:^|[\\t　])\\s*(?:"+label[1]+")\\s*[:：]\\s*([^\\r\\n]+)").matcher(text);
            if(m.find()) {
                String value=m.group(1).trim();int limit=Arrays.asList("skill","background").contains(label[0])?1000:200;
                if(label[0].equals("name") || label[0].equals("city"))limit=32;
                if(label[0].equals("gender") && !Arrays.asList("男","女","其他").contains(value))continue;
                if(label[0].equals("age")) {value=value.replace("岁","").trim();if(!value.matches("[1-8][0-9]") || Integer.parseInt(value)<16 || Integer.parseInt(value)>80)continue;}
                if(label[0].equals("phone")){value=value.replaceAll("\\s","");if(!value.matches("1[3-9][0-9]{9}"))continue;}
                out.put(label[0],value.substring(0,Math.min(limit,value.length())));
            }
        }
        return out;
    }
}
