package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component
public class ResumeProfileSchema implements KgSchemaContributor {
    private final KgJdbc db; private final boolean enabled;
    public ResumeProfileSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}") boolean enabled){this.db=db;this.enabled=enabled;}
    public Map<String,List<String>> requiredIndexes(){return Collections.emptyMap();}
    public String migration(){return "sql/kangaroo/V58__resume_profile_merchant.sql";}
    @PostConstruct public void check(){if(enabled && db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_resume' and column_name='profile_json' and data_type='text'",KgJdbc.p())!=1)throw new IllegalStateException("Run "+migration());}
}
