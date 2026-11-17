package com.ruoyi.kangaroo.config.schema;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component
public class OnlineTaskAgreementSchema {
 private final KgJdbc db;private final boolean enabled;
 public OnlineTaskAgreementSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}") boolean enabled){this.db=db;this.enabled=enabled;}
 @PostConstruct public void check(){if(enabled&&db.count("select count(*) from kg_schema_version where version=100 and name='online_task_agreement'",KgJdbc.p())!=1)throw new IllegalStateException("Run sql/kangaroo/V100__online_task_agreement.sql");}
}
