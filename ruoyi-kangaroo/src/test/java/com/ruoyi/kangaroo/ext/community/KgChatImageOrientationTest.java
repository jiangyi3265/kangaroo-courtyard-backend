package com.ruoyi.kangaroo.ext.community;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

class KgChatImageOrientationTest {
    // Independent literal source-position oracle for a,b,c / d,e,f.
    static final int[][] ORDER={{0,1,2,3,4,5},{2,1,0,5,4,3},{5,4,3,2,1,0},{3,4,5,0,1,2},
        {0,3,1,4,2,5},{3,0,4,1,5,2},{5,2,4,1,3,0},{2,5,1,4,0,3}};
    static byte[] jpeg(BufferedImage image)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"jpg",out));return out.toByteArray();}
    static byte[] base()throws Exception{BufferedImage image=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);int[] colors={0xff2020,0x20ff20,0x2020ff,0xffff20,0x20ffff,0xff20ff};for(int n=0;n<6;n++)image.setRGB(n%3,n/3,colors[n]);return jpeg(image);}
    static void put16(byte[] a,int p,int value,boolean little){a[p]=(byte)(little?value:value>>8);a[p+1]=(byte)(little?value>>8:value);}
    static void put32(byte[] a,int p,long value,boolean little){for(int n=0;n<4;n++)a[p+n]=(byte)(value>>(little?n*8:(3-n)*8));}
    static byte[] exif(int orientation,boolean little){byte[] p=new byte[32];System.arraycopy(new byte[]{'E','x','i','f',0,0},0,p,0,6);p[6]=p[7]=(byte)(little?'I':'M');put16(p,8,42,little);put32(p,10,8,little);put16(p,14,1,little);put16(p,16,0x112,little);put16(p,18,3,little);put32(p,20,1,little);put16(p,24,orientation,little);return p;}
    static byte[] app1(byte[] image,byte[] payload)throws Exception{assertTrue(payload.length+2<=65535);ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(image,0,2);out.write(255);out.write(225);out.write((payload.length+2)>>8);out.write((payload.length+2)&255);out.write(payload);out.write(image,2,image.length-2);return out.toByteArray();}
    static KgChatImage decode(byte[] input){return KgChatImage.decode(new MockMultipartFile("file","synthetic.jpg","image/jpeg",input));}
    static byte[] expected(byte[] raw,int orientation)throws Exception{BufferedImage input=ImageIO.read(new ByteArrayInputStream(raw));int w=orientation<5?3:2,h=orientation<5?2:3;BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);for(int n=0;n<6;n++){int source=ORDER[orientation-1][n];image.setRGB(n%w,n/w,input.getRGB(source%3,source/3));}return jpeg(image);}
    static void verify(byte[] input,byte[] raw,int orientation)throws Exception{
        KgChatImage actual=decode(input);assertEquals(orientation<5?3:2,actual.width);assertEquals(orientation<5?2:3,actual.height);
        BufferedImage result=ImageIO.read(new ByteArrayInputStream(actual.bytes)),want=ImageIO.read(new ByteArrayInputStream(expected(raw,orientation)));
        assertEquals(want.getWidth(),result.getWidth());assertEquals(want.getHeight(),result.getHeight());
        for(int y=0;y<want.getHeight();y++)for(int x=0;x<want.getWidth();x++)assertEquals(want.getRGB(x,y),result.getRGB(x,y),"normalized pixel "+x+","+y);
        assertEquals(KgChatImage.sha(input),actual.inputSha);assertEquals("image/jpeg",actual.mime);
        assertFalse(new String(actual.bytes,"ISO-8859-1").contains("Exif\0\0"),"metadata must be discarded");
    }
    @TestFactory Stream<DynamicTest> allEightDirectionsBothTiffByteOrders(){List<DynamicTest> tests=new ArrayList<>();for(boolean little:new boolean[]{true,false})for(int orientation=1;orientation<=8;orientation++){final int o=orientation;tests.add(DynamicTest.dynamicTest((little?"II":"MM")+" orientation "+o,()->{byte[] raw=base();verify(app1(raw,exif(o,little)),raw,o);}));}return tests.stream();}
    @TestFactory Stream<DynamicTest> malformedOrUnknownMetadataDefaultsToOne(){List<DynamicTest> tests=new ArrayList<>();for(boolean little:new boolean[]{true,false})for(String name:Arrays.asList("byte-order","magic","ifd-zero","ifd-overflow","ifd-outside","entry-count","wrong-type","value-count","count-overflow","zero-value","unknown-value","high-value","wrong-tag","truncated-ifd","duplicate-tag","no-tags")){
        tests.add(DynamicTest.dynamicTest((little?"II ":"MM ")+name,()->{byte[] p=exif(6,little);
            switch(name){case "byte-order":p[6]='X';break;case "magic":put16(p,8,43,little);break;case "ifd-zero":put32(p,10,0,little);break;case "ifd-overflow":put32(p,10,0xfffffff0L,little);break;case "ifd-outside":put32(p,10,32,little);break;case "entry-count":put16(p,14,65535,little);break;case "wrong-type":put16(p,18,4,little);break;case "value-count":put32(p,20,2,little);break;case "count-overflow":put32(p,20,0xffffffffL,little);break;case "zero-value":put16(p,24,0,little);break;case "unknown-value":put16(p,24,9,little);break;case "high-value":put16(p,24,65535,little);break;case "wrong-tag":put16(p,16,0x113,little);break;case "truncated-ifd":p=Arrays.copyOf(p,28);break;case "duplicate-tag":p=Arrays.copyOf(p,44);System.arraycopy(p,16,p,28,12);put16(p,14,2,little);break;case "no-tags":put16(p,14,0,little);break;default:throw new AssertionError(name);}
            byte[] raw=base();verify(app1(raw,p),raw,1);
        }));}return tests.stream();}
    @Test void shortExifHeaderDefaultsToOne()throws Exception{byte[] raw=base();verify(app1(raw,new byte[]{'E','x','i','f',0,0,'I','I'}),raw,1);}
    @Test void nonExifApp1BeforeValidTagDoesNotHideOrientation()throws Exception{byte[] raw=base();verify(app1(app1(raw,exif(6,true)),"http://ns.adobe.com/xap/1.0/\0synthetic".getBytes("UTF-8")),raw,6);}
    @Test void noExifPreservesExistingJpegNormalization()throws Exception{byte[] raw=base();verify(raw,raw,1);}
    @Test void pngPathDoesNotInterpretExifLookingBytes()throws Exception{BufferedImage source=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);source.setRGB(2,1,0xff0080);ByteArrayOutputStream out=new ByteArrayOutputStream();assertTrue(ImageIO.write(source,"png",out));byte[] png=out.toByteArray();out.write(exif(6,true));KgChatImage actual=decode(out.toByteArray());assertEquals("image/png",actual.mime);assertEquals(3,actual.width);assertEquals(2,actual.height);assertEquals(source.getRGB(2,1),ImageIO.read(new ByteArrayInputStream(actual.bytes)).getRGB(2,1));assertArrayEquals(decode(png).bytes,actual.bytes);}
}
