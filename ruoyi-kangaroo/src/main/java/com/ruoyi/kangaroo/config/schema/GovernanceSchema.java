package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component public class GovernanceSchema implements KgSchemaContributor {
    private final KgJdbc db;private final boolean enabled;
    public GovernanceSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}")boolean enabled){this.db=db;this.enabled=enabled;}
    public String migration(){return "sql/kangaroo/V79__governance.sql";}
    public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new HashMap<>();m.put("kg_mediation_case",Arrays.asList("PRIMARY","uk_mediation_request","idx_mediation_party","idx_mediation_deadline"));m.put("kg_mediation_vote",Arrays.asList("uk_mediation_vote"));m.put("kg_badge_award",Arrays.asList("uk_badge_source","idx_badge_user"));return m;}
    @PostConstruct public void check(){if(enabled&&db.count("select count(*) from kg_schema_version where version=79 and name='governance'",KgJdbc.p())!=1)throw new IllegalStateException("Run "+migration()+" before deploying governance APIs");}
}
