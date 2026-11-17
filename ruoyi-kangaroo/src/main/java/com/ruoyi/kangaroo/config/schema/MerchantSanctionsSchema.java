package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component public class MerchantSanctionsSchema implements KgSchemaContributor {
    private final KgJdbc db;private final boolean enabled;
    public MerchantSanctionsSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}")boolean enabled){this.db=db;this.enabled=enabled;}
    public String migration(){return "sql/kangaroo/V80__merchant_sanctions.sql";}
    public Map<String,List<String>> requiredIndexes(){return Collections.singletonMap("kg_merchant_case",Arrays.asList("PRIMARY","uk_merchant_case_request","idx_merchant_case_due"));}
    @PostConstruct public void check(){if(enabled&&db.count("select count(*) from kg_schema_version where version=80 and name='merchant_sanctions'",KgJdbc.p())!=1)throw new IllegalStateException("Run "+migration()+" before deploying merchant sanctions");}
}
