package com.ruoyi.kangaroo.ext.governance;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.alibaba.fastjson2.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Operational notifications only; separate from OTP configuration. No PII in logs. */
@Component public class TencentGovernanceSms {
    @Value("${kangaroo.governance.sms.enabled:false}") private boolean enabled;
    @Value("${kangaroo.governance.sms.secret-id:}") private String secretId;
    @Value("${kangaroo.governance.sms.secret-key:}") private String secretKey;
    @Value("${kangaroo.governance.sms.sdk-app-id:}") private String appId;
    @Value("${kangaroo.governance.sms.sign-name:}") private String sign;
    @Value("${kangaroo.governance.sms.template-id:}") private String template;
    @Value("${kangaroo.governance.sms.region:ap-guangzhou}") private String region;
    public boolean ready(){return enabled&&present(secretId)&&present(secretKey)&&present(appId)&&present(sign)&&present(template);}
    private boolean present(String s){return s!=null&&!s.trim().isEmpty();}
    public String appId(){return appId;}
    public String template(){return template;}
    public Map<String,Object> readiness(){return p("enabled",enabled,"credentials",present(secretId)&&present(secretKey),"appId",present(appId),"sign",present(sign),"template",present(template),"ready",ready());}
    public JSONObject send(String phone,String kind,String reference)throws Exception{
        return call("SendSms",p("PhoneNumberSet",Arrays.asList(phone),"SmsSdkAppId",appId,"SignName",sign,"TemplateId",template,"TemplateParamSet",Arrays.asList(kind,reference),"SessionContext",reference));
    }
    public JSONObject receipt(String phone,String savedApp,long start)throws Exception{
        return call("PullSmsSendStatusByPhoneNumber",p("SmsSdkAppId",savedApp,"PhoneNumber",phone,"BeginTime",Math.max(start-60,Instant.now().getEpochSecond()-604700),"EndTime",Instant.now().getEpochSecond(),"Offset",0,"Limit",100));
    }
    static byte[] hmac(byte[] key,String data)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(data.getBytes(StandardCharsets.UTF_8));}
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
    static String sha(String s)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
    static String authorization(String id,String secret,String action,long timestamp,String body)throws Exception{
        String date=Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toString();
        String headers="content-type:application/json; charset=utf-8\nhost:sms.tencentcloudapi.com\nx-tc-action:"+action.toLowerCase(Locale.ROOT)+"\n";
        String signed="content-type;host;x-tc-action",scope=date+"/sms/tc3_request";
        String canonical="POST\n/\n\n"+headers+"\n"+signed+"\n"+sha(body);
        String toSign="TC3-HMAC-SHA256\n"+timestamp+"\n"+scope+"\n"+sha(canonical);
        byte[] key=hmac(hmac(hmac(("TC3"+secret).getBytes(StandardCharsets.UTF_8),date),"sms"),"tc3_request");
        return "TC3-HMAC-SHA256 Credential="+id+"/"+scope+", SignedHeaders="+signed+", Signature="+hex(hmac(key,toSign));
    }
    protected JSONObject call(String action,Map<String,Object> payload)throws Exception{
        if(!ready())throw new IllegalStateException("SMS channel is not configured");
        String body=JSON.toJSONString(payload);long ts=Instant.now().getEpochSecond();
        HttpURLConnection c=(HttpURLConnection)new URL("https://sms.tencentcloudapi.com/").openConnection();
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("X-TC-Action",action);c.setRequestProperty("X-TC-Version","2021-01-11");c.setRequestProperty("X-TC-Region",region);c.setRequestProperty("X-TC-Timestamp",String.valueOf(ts));
            c.setRequestProperty("Authorization",authorization(secretId,secretKey,action,ts,body));
            byte[] data=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);try(OutputStream o=c.getOutputStream()){o.write(data);}
            if(c.getResponseCode()!=200)throw new IOException("Provider HTTP response requires verification");
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>1048576)throw new IOException("Response limit exceeded");out.write(buf,0,n);}}
            JSONObject r=JSON.parseObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getJSONObject("Response");if(r==null)throw new IOException("Missing provider response");return r;
        }finally{c.disconnect();}
    }
}
