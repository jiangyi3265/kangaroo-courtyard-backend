package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component
public class AdminCitySchema implements KgSchemaContributor {
    private final KgJdbc db;private final boolean enabled;
    public AdminCitySchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}")boolean enabled){this.db=db;this.enabled=enabled;}
    public String migration(){return "sql/kangaroo/V102__admin_city_scope.sql";}
    public Map<String,List<String>> requiredIndexes(){return Collections.emptyMap();}
    @PostConstruct public void check(){if(enabled && db.count("select count(*) from information_schema.tables where table_schema=database() and table_name in ('kg_admin_city_scope','kg_admin_city_grant')",KgJdbc.p())!=2)throw new IllegalStateException("Run "+migration());}
}
