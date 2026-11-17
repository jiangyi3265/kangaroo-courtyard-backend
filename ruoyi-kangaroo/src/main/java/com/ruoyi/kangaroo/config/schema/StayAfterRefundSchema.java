package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component
public class StayAfterRefundSchema implements KgSchemaContributor {
    private final KgJdbc db; private final boolean enabled;
    public StayAfterRefundSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}") boolean enabled){this.db=db;this.enabled=enabled;}
    public String migration(){return "sql/kangaroo/V97__stay_after_refund.sql";}
    public Map<String,List<String>> requiredIndexes(){return Collections.singletonMap("kg_stay_after_refund",Arrays.asList("uk_sar_request","idx_sar_pending"));}
    @PostConstruct public void check(){
        if(enabled && (db.count("select count(*) from kg_schema_version where version=97 and name='stay_after_refund'",KgJdbc.p())!=1
            || db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_stay_after_refund' and column_name in ('request_id','request_key','staff_refund_id','review_note','reviewed_by','reviewed_time')",KgJdbc.p())!=6))
            throw new IllegalStateException("Run "+migration());
    }
}
