package com.ruoyi.kangaroo.ext.core;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.controller.app.AppUploadController;
import com.ruoyi.kangaroo.security.*;
import com.ruoyi.kangaroo.ext.community.KgChatImage;
import com.ruoyi.kangaroo.ext.user.KgResumeMedia;

class CustomerUploadCapacityTest {
 Path root;String old;
 @BeforeEach void setup() throws Exception {
  old=RuoYiConfig.getProfile();root=Files.createTempDirectory("kg-capacity-");new RuoYiConfig().setProfile(root.resolve("profile").toString());
  AppLoginUser user=new AppLoginUser();user.setUserId(71L);AppUserContext.set(user);
 }
 @AfterEach void cleanup() throws Exception {
  AppUserContext.remove();new RuoYiConfig().setProfile(old);
  try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}
 }
 static byte[] photo(int size) throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
  return Arrays.copyOf(out.toByteArray(),size);
 }
 static MockMultipartFile file(String name,byte[] bytes){return new MockMultipartFile("file",name,name.endsWith("png")?"image/png":"video/mp4",bytes);}
 @Test void effectiveMultipartConfigAllowsLargePrivateVideoEvenWithLegacyDefaults() {
  org.springframework.boot.autoconfigure.web.servlet.MultipartProperties p=new org.springframework.boot.autoconfigure.web.servlet.MultipartProperties();
  javax.servlet.MultipartConfigElement c=new com.ruoyi.kangaroo.config.GovernanceUploadConfig().multipartConfigElement(p);
  assertEquals(200L*1024*1024,c.getMaxFileSize());assertEquals(220L*1024*1024,c.getMaxRequestSize());assertEquals(0,c.getFileSizeThreshold());
 }
 @Test void licensePhotoAtTwentyMegabytesStillReceivesWatermark() throws Exception {
  String url=KgLicenseWatermark.savePublic(file("license.png",photo(20*1024*1024)));
  Path p=Paths.get(RuoYiConfig.getProfile()).resolve(url.substring("/profile/".length()));
  assertTrue(Files.size(p)>0);assertNotNull(ImageIO.read(p.toFile()));
 }
 @Test void publicPhotoExactLimitPersistedOversizeAndDisguisedRejected() throws Exception {
  AppUploadController c=new AppUploadController();ReflectionTestUtils.setField(c,"limiter",mock(AppRateLimiter.class));
  byte[] data=photo(20*1024*1024);
  Map<?,?> result=(Map<?,?>)c.image(file("photo.png",data),"2").get("data");
  Path saved=Paths.get(RuoYiConfig.getProfile()).resolve(result.get("url").toString().substring("/profile/".length()));assertEquals(data.length,Files.size(saved));
  assertThrows(ServiceException.class,()->c.image(file("big.png",Arrays.copyOf(data,data.length+1)),"2"));
  assertThrows(ServiceException.class,()->c.image(file("fake.png","<script>bad</script>".getBytes()),"2"));
 }
 @Test void chatPhotoAtTwentyMegabytesDecodedAndMetadataDiscarded() throws Exception {
  byte[] bytes=photo(20*1024*1024);KgChatImage result=KgChatImage.decode(file("photo.png",bytes));
  assertEquals(2,result.width);assertTrue(result.bytes.length<1024);
  assertThrows(ServiceException.class,()->KgChatImage.decode(file("big.png",Arrays.copyOf(bytes,bytes.length+1))));
 }
 @Test void privateResumeVideoAtTwoHundredMegabytesRemainsPrivate() throws Exception {
  KgResumeMedia media=new KgResumeMedia(new KgPrivateFiles("capacity-test-signing-secret-not-production"));
  byte[] bytes=new byte[200*1024*1024];System.arraycopy(new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m'},0,bytes,0,12);
  Map<String,Object> result=media.save(71L,file("video.mp4",bytes),"video");
  String key=result.get("key").toString();assertTrue(media.owned(71L,key));assertFalse(media.owned(72L,key));assertEquals(bytes.length,media.resolve(key).length());
  MockMultipartFile tooLarge=new MockMultipartFile("file","big.mp4","video/mp4",new byte[]{1}){@Override public long getSize(){return 200L*1024*1024+1;}};
  assertThrows(ServiceException.class,()->media.save(71L,tooLarge,"video"));
 }
 @Test void privateCredentialPhotoTwentyMegabytesPdfStillLimited() throws Exception {
  KgPrivateFiles files=new KgPrivateFiles("capacity-test-signing-secret-not-production");
  assertNotNull(files.save(file("photo.png",photo(20*1024*1024)),"qual").get("key"));
  MockMultipartFile largePdf=new MockMultipartFile("file","certificate.pdf","application/pdf",new byte[]{1}){@Override public long getSize(){return 10L*1024*1024+1;}};
  assertThrows(ServiceException.class,()->files.save(largePdf,"qual"));
 }
}
