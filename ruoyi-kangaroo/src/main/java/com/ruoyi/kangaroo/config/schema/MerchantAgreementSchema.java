package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component
public class MerchantAgreementSchema implements KgSchemaContributor {
 private final KgJdbc db;private final boolean enabled;
 public MerchantAgreementSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}") boolean enabled){this.db=db;this.enabled=enabled;}
 public String migration(){return "sql/kangaroo/V99__merchant_agreements.sql";}
 public Map<String,List<String>> requiredIndexes(){return Collections.singletonMap("kg_merchant_agreement_consent",Arrays.asList("PRIMARY","uk_merchant_consent","idx_merchant_consent_store"));}
 @PostConstruct public void check(){if(enabled&&db.count("select count(*) from kg_schema_version where version=99 and name='merchant_agreements'",KgJdbc.p())!=1)throw new IllegalStateException("Run "+migration());}
}
