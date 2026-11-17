package com.ruoyi.kangaroo.ext.user;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import com.ruoyi.kangaroo.ext.core.KgImageMagic;
/** 简历照片和作品存在公开 profile 目录之外，短时签名访问。 */
@Service
public class KgResumeMedia {
    private final KgPrivateFiles signer;
    public KgResumeMedia(KgPrivateFiles signer){this.signer=signer;}
    private Path root(){return new File(RuoYiConfig.getProfile()).getAbsoluteFile().toPath().getParent().resolve("kg-resume-private");}
    public boolean owned(Long uid,String key){return uid!=null && key!=null && key.matches("resume/"+uid+"/[0-9a-f]{32}\\.(jpg|png|mp4)");}
    public File resolve(String key){
        if(key==null || !key.matches("resume/[1-9][0-9]*/[0-9a-f]{32}\\.(jpg|png|mp4)"))return null;
        File f=root().resolve(key).toFile(); return f.isFile()?f:null;
    }
    public void requireOwned(Long uid,String key,boolean video){
        if(!owned(uid,key) || resolve(key)==null || (!video && key.endsWith(".mp4"))) throw new ServiceException("请重新上传本人简历的图片或作品",422);
    }
    public String url(String key){return signer.sign(key,900).replace("/kangaroo/file/private?","/kangaroo/file/resume?");}
    public boolean verify(String key,long exp,String sig){return signer.verify(key,exp,sig);}
    public Map<String,Object> save(Long uid,MultipartFile file,String kind) throws IOException {
        if(!Arrays.asList("portrait","image","video").contains(kind))throw new ServiceException("文件类别不正确",422);
        boolean video=kind.equals("video");
        // 文件仍为私有存储；入口、代理和客户端按同一容量配置。
        if(file==null || file.isEmpty() || file.getSize()>(video?200:20)*1024L*1024)throw new ServiceException(video?"视频不能超过 200MB":"图片不能超过 20MB",422);
        String ext;
        if(video){
            try(InputStream in=file.getInputStream()){
                byte[] h=new byte[12];
                int offset=0, count;
                while(offset<h.length && (count=in.read(h,offset,h.length-offset))!=-1) offset+=count;
                if(offset<12 || h[4]!='f'||h[5]!='t'||h[6]!='y'||h[7]!='p')throw new ServiceException("请上传 MP4 视频",422);
            }
            ext="mp4";
        }else{
            KgImageMagic.require(file,"jpg","png");
            try(InputStream in=file.getInputStream()){ext=in.read()==0x89?"png":"jpg";}
        }
        String key="resume/"+uid+"/"+UUID.randomUUID().toString().replace("-","")+"."+ext;
        Path path=root().resolve(key);Files.createDirectories(path.getParent());
        try(InputStream in=file.getInputStream()){Files.copy(in,path);}
        String name=Objects.toString(file.getOriginalFilename(),"作品");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("key",key);result.put("url",url(key));
        result.put("name",name.substring(0,Math.min(120,name.length())));result.put("kind",video?"video":"image");
        return result;
    }
}
