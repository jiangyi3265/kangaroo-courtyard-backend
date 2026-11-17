package com.ruoyi.kangaroo.config.schema;

import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class MembershipExecutionSchema implements KgSchemaContributor {
    private final KgJdbc db;
    private final boolean enabled;
    public MembershipExecutionSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}") boolean enabled){this.db=db;this.enabled=enabled;}
    public String migration(){return "sql/kangaroo/V77__membership_execution.sql";}
    public Map<String,List<String>> requiredIndexes(){
        Map<String,List<String>> m=new HashMap<>();
        m.put("kg_member_wallet",Arrays.asList("PRIMARY","uk_member_wallet_source","idx_member_wallet_user","idx_member_wallet_order"));
        m.put("kg_member_request",Arrays.asList("PRIMARY","uk_member_request","idx_member_request_status"));
        m.put("kg_member_gift",Arrays.asList("PRIMARY","uk_member_gift"));
        m.put("kg_member_pin",Arrays.asList("PRIMARY","uk_member_pin","idx_member_pin_week","idx_member_pin_post"));
        m.put("kg_member_hot",Arrays.asList("PRIMARY","uk_member_hot"));
        return m;
    }
    @PostConstruct public void check(){
        if(enabled && db.count("select count(*) from kg_schema_version where version=77 and name='membership_execution'",KgJdbc.p())!=1)
            throw new IllegalStateException("Membership execution not migrated; run "+migration());
    }
}
