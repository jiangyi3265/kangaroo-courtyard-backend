package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.governance.Governance.*;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Private evidence is outside /profile. Signed reads re-check current participant/assignment access. */
@Service public class KgMediationFiles {
    @Autowired private KgJdbc db;
    @Autowired private KgMediationService mediation;
    private final byte[] secret;
    public KgMediationFiles(@Value("${kangaroo.file.sign-secret:}")String configured){secret=com.ruoyi.kangaroo.ext.core.KgSignSecret.resolve(configured);}
    private Path base(){return new File(RuoYiConfig.getProfile()).getAbsoluteFile().toPath().getParent().resolve("kg-mediation-private");}
    public Map<String,Object> upload(Long uid,MultipartFile file,boolean redacted)throws IOException{
        require(file!=null&&!file.isEmpty()&&file.getSize()<=20L*1024*1024,"单个证据文件需在20MB以内");
        String name=str(file.getOriginalFilename()).replaceAll("[\\r\\n\\x00]","");if(name.length()>120)name=name.substring(name.length()-120);
        String ext=name.substring(Math.max(0,name.lastIndexOf('.')+1)).toLowerCase(Locale.ROOT);
        Map<String,String> types=new HashMap<>();types.put("jpg","image/jpeg");types.put("jpeg","image/jpeg");types.put("png","image/png");types.put("webp","image/webp");types.put("mp4","video/mp4");types.put("mov","video/quicktime");types.put("mp3","audio/mpeg");types.put("m4a","audio/mp4");types.put("wav","audio/wav");types.put("pdf","application/pdf");types.put("docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document");types.put("txt","text/plain");
        require(types.containsKey(ext),"支持图片、MP4/MOV、音频、PDF、DOCX和TXT");
        if(Arrays.asList("jpg","jpeg","png","webp").contains(ext))com.ruoyi.kangaroo.ext.core.KgImageMagic.require(file,"jpg","png","webp");
        String key=UUID.randomUUID().toString().replace("-","")+"."+ext;Path target=base().resolve(key);Files.createDirectories(base());
        try(InputStream in=file.getInputStream();OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){byte[] buf=new byte[8192];int n;long total=0;while((n=in.read(buf))!=-1){total+=n;require(total<=20L*1024*1024,"文件超过20MB");out.write(buf,0,n);}}
        catch(Exception e){Files.deleteIfExists(target);throw e;}
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target));StringBuilder hash=new StringBuilder();for(byte n:digest)hash.append(String.format("%02x",n));long id=db.insert("kg_mediation_file",p("owner_actor","app:"+uid,"file_key",key,"original_name",name,"mime_type",types.get(ext),"size_bytes",Files.size(target),"sha256",hash.toString(),"redacted",redacted?"1":"0"));return p("fileId",id,"name",name,"size",Files.size(target));}
        catch(Exception e){Files.deleteIfExists(target);throw new ServiceException("证据保存失败，请重试");}
    }
    public Map<String,Object> link(Long uid,Long fid,boolean hq){Map<String,Object> f=must(db,"select * from kg_mediation_file where file_id=:f",p("f",fid),"附件不存在",404);if(!mediation.fileAccess(uid,f,hq))denied();long exp=System.currentTimeMillis()/1000+300;String actor=hq?"hq":"app";return p("url","/kangaroo/file/mediation?f="+fid+"&u="+uid+"&a="+actor+"&e="+exp+"&s="+mac(fid+"|"+uid+"|"+actor+"|"+exp),"mime",f.get("mimeType"),"name",f.get("redacted").equals("1")?"评审材料":f.get("originalName"));}
    public void serve(Long fid,Long uid,String actor,long exp,String sig,HttpServletResponse response)throws IOException{
        serve(fid,uid,actor,exp,sig,null,response);
    }
    public void serve(Long fid,Long uid,String actor,long exp,String sig,String range,HttpServletResponse response)throws IOException{
        if(sig==null||exp<System.currentTimeMillis()/1000||exp>System.currentTimeMillis()/1000+330||!Arrays.asList("hq","app").contains(actor)||!MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),mac(fid+"|"+uid+"|"+actor+"|"+exp).getBytes(StandardCharsets.UTF_8))){response.sendError(403);return;}
        Map<String,Object> f=db.one("select * from kg_mediation_file where file_id=:f",p("f",fid));if(f==null||!mediation.fileAccess(uid,f,"hq".equals(actor))){response.sendError(403);return;}
        if("app".equals(actor)&&db.count("select count(*) from kg_app_user where user_id=:u and status='0' and del_flag='0'",p("u",uid))!=1){response.sendError(403);return;}
        String key=str(f.get("fileKey"));if(!key.matches("[a-f0-9]{32}\\.(jpg|jpeg|png|webp|mp4|mov|mp3|m4a|wav|pdf|docx|txt)")){response.sendError(404);return;}Path path=base().resolve(key);if(!Files.isRegularFile(path)){response.sendError(404);return;}
        response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Security-Policy","sandbox");response.setHeader("Content-Disposition",(str(f.get("mimeType")).startsWith("image/")||str(f.get("mimeType")).startsWith("video/")||str(f.get("mimeType")).startsWith("audio/")?"inline":"attachment")+"; filename=\""+key+"\"");response.setContentType(str(f.get("mimeType")));long size=Files.size(path),start=0,end=size-1;response.setHeader("Accept-Ranges","bytes");
        if(range!=null){
            try{require(range.matches("bytes=[0-9]*-[0-9]*"),"无效范围");String[] parts=range.substring(6).split("-",-1);
                if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,size-suffix);}
                else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}
                if(start<0||start>=size||end<start)throw new IllegalArgumentException();
            }catch(Exception invalid){response.setHeader("Content-Range","bytes */"+size);response.setStatus(416);return;}
            response.setStatus(206);response.setHeader("Content-Range","bytes "+start+"-"+end+"/"+size);
        }
        long remaining=end-start+1;response.setContentLengthLong(remaining);
        try(RandomAccessFile input=new RandomAccessFile(path.toFile(),"r")){input.seek(start);byte[] buf=new byte[8192];while(remaining>0){int n=input.read(buf,0,(int)Math.min(buf.length,remaining));if(n<0)break;response.getOutputStream().write(buf,0,n);remaining-=n;}}
    }
    private String mac(String value){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret,"HmacSHA256"));StringBuilder s=new StringBuilder();for(byte b:m.doFinal(value.getBytes(StandardCharsets.UTF_8)))s.append(String.format("%02x",b));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
