package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import com.ruoyi.kangaroo.ext.KgJdbc;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
@Component
public class WechatPaySchema implements KgSchemaContributor {
 private final KgJdbc db; private final boolean enabled;
 public WechatPaySchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}") boolean enabled){this.db=db;this.enabled=enabled;}
 public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<>();m.put("kg_wx_payment",Arrays.asList("PRIMARY","uk_wx_transaction"));m.put("kg_pay_refund",Arrays.asList("PRIMARY","uk_wx_refund","uk_refund_seq"));return m;}
 public String migration(){return "sql/kangaroo/V34__wechat_pay.sql";}
 @PostConstruct public void check(){if(!enabled)return;
  if(db.count("select count(*) from kg_schema_version where version=34 and name='wechat_pay' and checksum=:c",KgJdbc.p("c","eb5bd2d6f7f0b59c5e5cfb1cc612a75c3ed2f52b016959c81a3cc1a9d69711a1"))!=1)throw new IllegalStateException("V34 WeChat payment schema missing or changed");
  db.list("select pay_no,wx_mchid,wx_appid,wx_openid,wx_requested,wx_expire_time,wx_last_query_time,transaction_id from kg_wx_payment where 1=0",KgJdbc.p());
  db.list("select refund_no,pay_no,amount,reason,operator,status,channel_refund_id,attempts,next_attempt_time,last_error,success_time from kg_pay_refund where 1=0",KgJdbc.p());
 }
}
