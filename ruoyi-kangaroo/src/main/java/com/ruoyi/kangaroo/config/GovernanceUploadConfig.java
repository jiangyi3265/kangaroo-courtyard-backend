package com.ruoyi.kangaroo.config;
import javax.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
/** Transport accepts private resume videos up to 200 MiB; each endpoint still enforces its own image/document limits. */
@Configuration public class GovernanceUploadConfig {
 @Bean public MultipartConfigElement multipartConfigElement(MultipartProperties properties){
  if(properties.getMaxFileSize().toBytes()<200L*1024*1024)properties.setMaxFileSize(DataSize.ofMegabytes(200));
  if(properties.getMaxRequestSize().toBytes()<220L*1024*1024)properties.setMaxRequestSize(DataSize.ofMegabytes(220));
  properties.setFileSizeThreshold(DataSize.ofBytes(0));
  return properties.createMultipartConfig();
 }
}
