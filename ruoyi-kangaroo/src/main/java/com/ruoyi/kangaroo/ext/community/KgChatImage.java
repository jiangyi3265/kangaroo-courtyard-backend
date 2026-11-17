package com.ruoyi.kangaroo.ext.community;

import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.exception.ServiceException;

/** Decode and re-encode; reject invalid images and discard metadata/trailing data.
 * Bytes are committed in kg_chat_image in the SAME transaction as the message.
 * No profile URL, temporary public upload, filesystem rollback or bearer URL. */
public final class KgChatImage
{
    /** 聊天图片上限：2026-10-16 起 20MB（之前 5MB；与 Spring multipart 的 max-file-size 20MB 一致，kg_chat_image.image_bytes 是 longblob，放得下） */
    public static final int MAX=20*1024*1024;
    public final byte[] bytes;
    public final String mime,inputSha;
    public final int width,height;
    private KgChatImage(byte[] bytes,String mime,String sha,int width,int height)
    {this.bytes=bytes;this.mime=mime;this.inputSha=sha;this.width=width;this.height=height;}
    public static KgChatImage decode(MultipartFile file)
    {
        if(file==null||file.isEmpty()||file.getSize()>MAX)throw invalid("请选择不超过20MB的JPG或PNG图片");
        try(InputStream input=file.getInputStream())
        {
            ByteArrayOutputStream raw=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int read;
            while((read=input.read(chunk))!=-1){if(raw.size()+read>MAX)throw invalid("图片不能超过20MB");raw.write(chunk,0,read);}
            byte[] data=raw.toByteArray();
            boolean png=data.length>8 && Arrays.equals(Arrays.copyOf(data,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
            boolean jpg=data.length>3 && data[0]==(byte)255 && data[1]==(byte)216 && data[2]==(byte)255;
            if(!png&&!jpg)throw invalid("只支持真实JPG或PNG图片");
            try(MemoryCacheImageInputStream imageInput=new MemoryCacheImageInputStream(new ByteArrayInputStream(data)))
            {
                Iterator<ImageReader> readers=ImageIO.getImageReaders(imageInput);
                if(!readers.hasNext())throw invalid("图片内容无法读取");
                ImageReader reader=readers.next();
                try
                {
                    reader.setInput(imageInput,true,true);
                    String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if(!(png&&"png".equals(format)) && !(jpg&&("jpeg".equals(format)||"jpg".equals(format))))throw invalid("图片格式不正确");
                    int w=reader.getWidth(0),h=reader.getHeight(0);
                    if(w<=0||h<=0||w>8192||h>8192||(long)w*h>16000000)throw invalid("图片尺寸过大，请压缩后发送");
                    BufferedImage image=reader.read(0);
                    if(image==null)throw invalid("图片内容无法读取");
                    if(jpg)image=orientJpeg(image,data);
                    ByteArrayOutputStream clean=new ByteArrayOutputStream();
                    if(!ImageIO.write(image,png?"png":"jpg",clean)||clean.size()>MAX)throw invalid("图片处理后超过20MB，请压缩后发送");
                    return new KgChatImage(clean.toByteArray(),png?"image/png":"image/jpeg",sha(data),image.getWidth(),image.getHeight());
                }
                finally {reader.dispose();}
            }
        }
        catch(ServiceException e){throw e;}
        catch(Exception e){throw invalid("图片内容无法读取，请重新选择");}
    }
    /** Shared with certificate watermarking so camera photos keep their upright orientation. */
    public static BufferedImage orientJpeg(BufferedImage image, byte[] data)
    { return orient(image, jpegOrientation(data)); }

    /** Inspect only bounded JPEG metadata segments before the compressed scan. */
    private static int jpegOrientation(byte[] data)
    {
        int at=2;
        while(at<data.length)
        {
            if((data[at++]&255)!=255)return 1;
            while(at<data.length&&(data[at]&255)==255)at++;
            if(at>=data.length)return 1;
            int marker=data[at++]&255;
            if(marker==0xda||marker==0xd9)return 1;
            if(marker==0x01||(marker>=0xd0&&marker<=0xd8))continue;
            if(marker==0||at>data.length-2)return 1;
            int size=((data[at]&255)<<8)|(data[at+1]&255);
            if(size<2||size>data.length-at)return 1;
            int payload=at+2,end=at+size;
            if(marker==0xe1&&end-payload>=6&&data[payload]=='E'&&data[payload+1]=='x'
                &&data[payload+2]=='i'&&data[payload+3]=='f'&&data[payload+4]==0&&data[payload+5]==0)
                return tiffOrientation(data,payload+6,end);
            at=end;
        }
        return 1;
    }
    private static int tiffOrientation(byte[] data,int base,int end)
    {
        if(end-base<8)return 1;
        boolean little=data[base]=='I'&&data[base+1]=='I';
        if(!little&&!(data[base]=='M'&&data[base+1]=='M'))return 1;
        if(u16(data,base+2,little)!=42)return 1;
        long offset=u32(data,base+4,little);
        if(offset<8||offset>(long)end-base-2)return 1;
        int directory=base+(int)offset,count=u16(data,directory,little);
        if((long)count*12+6>end-directory)return 1;
        int orientation=1;boolean found=false;
        for(int n=0;n<count;n++)
        {
            int entry=directory+2+n*12;
            if(u16(data,entry,little)!=0x112)continue;
            if(found||u16(data,entry+2,little)!=3||u32(data,entry+4,little)!=1)return 1;
            orientation=u16(data,entry+8,little);found=true;
            if(orientation<1||orientation>8)return 1;
        }
        return orientation;
    }
    private static int u16(byte[] data,int at,boolean little)
    {return little?(data[at]&255)|((data[at+1]&255)<<8):((data[at]&255)<<8)|(data[at+1]&255);}
    private static long u32(byte[] data,int at,boolean little)
    {return little?((long)u16(data,at+2,true)<<16)|u16(data,at,true):((long)u16(data,at,false)<<16)|u16(data,at+2,false);}
    private static BufferedImage orient(BufferedImage source,int orientation)
    {
        if(orientation==1)return source;
        int w=source.getWidth(),h=source.getHeight();boolean swap=orientation>=5;
        BufferedImage result=new BufferedImage(swap?h:w,swap?w:h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)
        {
            int dx=x,dy=y;
            switch(orientation)
            {
                case 2:dx=w-1-x;break;
                case 3:dx=w-1-x;dy=h-1-y;break;
                case 4:dy=h-1-y;break;
                case 5:dx=y;dy=x;break;
                case 6:dx=h-1-y;dy=x;break;
                case 7:dx=h-1-y;dy=w-1-x;break;
                case 8:dx=y;dy=w-1-x;break;
                default:throw new IllegalArgumentException("Invalid image orientation");
            }
            result.setRGB(dx,dy,source.getRGB(x,y));
        }
        return result;
    }
    static String sha(byte[] data)throws Exception
    {StringBuilder out=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",x&255));return out.toString();}
    private static ServiceException invalid(String text){return new ServiceException(text,422);}
}
