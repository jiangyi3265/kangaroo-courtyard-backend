package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component public class MerchantSettlementSchema implements KgSchemaContributor {
    private final KgJdbc db;private final boolean enabled;
    public MerchantSettlementSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}")boolean enabled){this.db=db;this.enabled=enabled;}
    public String migration(){return "sql/kangaroo/V81__merchant_settlement_delivery.sql";}
    public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<>();m.put("kg_merchant_settlement",Arrays.asList("PRIMARY","uk_merchant_fee_month"));m.put("kg_governance_sms",Arrays.asList("PRIMARY","uk_governance_sms_msg","idx_governance_sms_status"));m.put("kg_merchant_case_review",Arrays.asList("PRIMARY"));return m;}
    @PostConstruct public void check(){if(enabled&&db.count("select count(*) from kg_schema_version where version=81 and name='merchant_settlement_delivery'",KgJdbc.p())!=1)throw new IllegalStateException("Run "+migration()+" before deploying settlement and delivery");}
}
