package com.ruoyi.kangaroo.config.schema;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class RoomPeriodPriceSchema implements KgSchemaContributor {
    private final KgJdbc db;
    private final boolean enabled;
    public RoomPeriodPriceSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) {
        this.db = db;
        this.enabled = enabled;
    }
    @Override public Map<String, List<String>> requiredIndexes() { return Collections.emptyMap(); }
    @Override public String migration() { return "sql/kangaroo/V117__room_period_prices.sql"; }
    @PostConstruct public void check() {
        if (!enabled) return;
        if (db.count("select count(*) from information_schema.columns where table_schema=database() "
                + "and table_name='kg_stay_room_type' and column_name in ('half_month_total_fen','month_total_fen') "
                + "and data_type='bigint' and is_nullable='YES'", KgJdbc.p()) != 2
                || db.count("select count(*) from kg_schema_version where version=117 and name='room_period_prices'", KgJdbc.p()) != 1)
            throw new IllegalStateException("Room duration price columns missing or incompatible; run " + migration());
    }
}
