package com.ruoyi.kangaroo.config.schema;

import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class StayBookingNoticeSchema implements KgSchemaContributor {
    static final String MIGRATION = "sql/kangaroo/V92__stay_booking_notice.sql";
    static final String SQL = "select count(*) from information_schema.columns where table_schema=database() and is_nullable='YES' and ("
            + "(table_name='kg_stay' and column_name='booking_notice_config' and data_type in ('text','mediumtext','longtext')) or "
            + "(table_name='kg_stay_order' and column_name='agreement_snapshot' and data_type in ('text','mediumtext','longtext')) or "
            + "(table_name='kg_stay_order' and column_name='agreement_version' and data_type='varchar' and character_maximum_length>=80))";
    private final KgJdbc db;
    private final boolean enabled;
    public StayBookingNoticeSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) {this.db=db;this.enabled=enabled;}
    @Override public Map<String,List<String>> requiredIndexes() {return Collections.emptyMap();}
    @Override public String migration() {return MIGRATION;}
    @PostConstruct public void check() {
        if (enabled && db.count(SQL,KgJdbc.p())!=3) throw new IllegalStateException("stay booking notice columns missing or incompatible; run " + MIGRATION);
    }
}
