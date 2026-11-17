package com.ruoyi.kangaroo.config.schema;

import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Component
public class NoticeReplySchema implements KgSchemaContributor
{
    private final KgJdbc db;
    private final boolean enabled;
    public NoticeReplySchema(KgJdbc db, @Value("${kangaroo.schema.assert-indexes:true}") boolean enabled)
    { this.db=db; this.enabled=enabled; }
    public String migration() { return "sql/kangaroo/V75__notice_reply.sql"; }
    public Map<String,List<String>> requiredIndexes()
    {
        Map<String,List<String>> m=new HashMap<>();
        m.put("kg_notice_thread", Arrays.asList("PRIMARY", "uk_notice_user", "idx_notice_pending"));
        m.put("kg_notice_reply", Arrays.asList("PRIMARY", "uk_notice_request", "idx_notice_history"));
        return m;
    }
    @PostConstruct public void check()
    {
        if (enabled && (db.count("select count(*) from kg_schema_version where version=75 and name='notice_reply'", KgJdbc.p()) != 1
                || db.count("select count(*) from information_schema.columns where table_schema=database() and "
                        + "((table_name='kg_notice_thread' and column_name in ('thread_id','notice_id','app_user_id','notice_title','reply_mode','pending','last_content','create_time','update_time')) "
                        + "or (table_name='kg_notice_reply' and column_name in ('reply_id','thread_id','sender','sender_id','request_id','content','create_time')))", KgJdbc.p()) != 16))
            throw new IllegalStateException("Notice replies not migrated; run " + migration());
    }
}
