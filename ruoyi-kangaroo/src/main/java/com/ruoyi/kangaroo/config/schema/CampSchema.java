package com.ruoyi.kangaroo.config.schema;

import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class CampSchema {
    private final KgJdbc db;
    private final boolean enabled;
    public CampSchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled) { this.db=db; this.enabled=enabled; }
    @PostConstruct public void check() {
        if (enabled && db.count("select count(*) from information_schema.columns where table_schema=database() and "
            + "((table_name='kg_stay' and column_name='house_type') or (table_name='kg_city' and column_name in ('camp_local_qr','camp_destination_qr')))", KgJdbc.p()) != 3)
            throw new IllegalStateException("请先执行 sql/kangaroo/V119__camp_and_booking_channel.sql");
    }
}
