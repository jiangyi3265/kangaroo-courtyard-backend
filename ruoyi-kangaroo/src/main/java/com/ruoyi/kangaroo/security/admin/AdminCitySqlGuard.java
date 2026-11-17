package com.ruoyi.kangaroo.security.admin;

import java.util.*;
import java.util.function.Function;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** Applies the same boundary before both JDBC and MyBatis execution. Unsupported global data fails closed. */
@Component
public class AdminCitySqlGuard {
    private final JdbcTemplate raw;
    private final Map<String,Set<String>> columns=new HashMap<>();
    private static final Set<String> REFERENCES=new HashSet<>(Arrays.asList("kg_insurance_plan","kg_ops_form","kg_ops_form_change","kg_city","sys_dict_data","sys_dict_type","sys_config","kg_legal_doc"));
    private static final Set<String> OWNED_TABLES=new HashSet<>(Arrays.asList("kg_ops_daily","kg_ops_target","kg_ops_monthly","kg_ops_notice","kg_ops_result","kg_activity","kg_activity_subscribe","kg_app_arrival","kg_app_user","kg_application","kg_application_date_plan","kg_application_log","kg_badge_award","kg_business_bill","kg_business_bill_log","kg_business_withdrawal","kg_chat_conv","kg_chat_reception","kg_city","kg_commission_rule","kg_contract_sign","kg_daily_entry","kg_dispatch_log","kg_growth_milestone","kg_home_pick","kg_honor_award","kg_insurance_apply","kg_interview","kg_interview_slot","kg_job","kg_job_favorite","kg_job_share","kg_job_subscribe","kg_mediation_case","kg_mediation_juror","kg_member_benefit","kg_merchant_agreement_consent","kg_merchant_apply","kg_merchant_case","kg_merchant_request","kg_merchant_settlement","kg_online_material","kg_online_material_file","kg_online_payout_correction","kg_online_settlement","kg_online_submission","kg_online_task","kg_pay_order","kg_post","kg_reimbursement","kg_resume","kg_resume_exp","kg_resume_forward","kg_review","kg_shop_order","kg_shop_service","kg_staff_grant","kg_staff_op_log","kg_staff_task","kg_staff_task_batch","kg_stay","kg_stay_after_refund","kg_stay_assign","kg_stay_calendar","kg_stay_comment","kg_stay_diary","kg_stay_order","kg_stay_promo","kg_stay_qualification","kg_stay_room_type","kg_stay_staff_refund","kg_stay_unit","kg_stay_vlog","kg_stay_vlog_comment","kg_stay_vlog_event","kg_store","kg_talent_invite","kg_talent_mark","kg_task","kg_team_member","kg_todo_meta","kg_trip_plan","kg_wallet_entry","kg_weekly_report","kg_wish"));
    private static final LinkedHashMap<String,String[]> PARENTS=new LinkedHashMap<>();
    static {
        OWNED_TABLES.addAll(Arrays.asList("kg_insurance_payment","kg_insurance_refund","kg_ops_daily_change","kg_ops_reminder_batch","kg_ops_reminder_item"));
        PARENTS.put("store_id",new String[]{"kg_store","store_id"});
        PARENTS.put("stay_id",new String[]{"kg_stay","stay_id"});
        PARENTS.put("room_type_id",new String[]{"kg_stay_room_type","room_type_id"});
        PARENTS.put("job_id",new String[]{"kg_job","job_id"});
        PARENTS.put("app_id",new String[]{"kg_application","app_id"});
        PARENTS.put("bill_id",new String[]{"kg_business_bill","bill_id"});
    }
    public AdminCitySqlGuard(DataSource ds) {raw=new JdbcTemplate(ds);}
    @PostConstruct public void init() {
        columns.clear();
        for(Map<String,Object> r:raw.queryForList("select table_name, column_name from information_schema.columns where table_schema=database() and table_name like 'kg\\_%'"))
            columns.computeIfAbsent(String.valueOf(r.get("TABLE_NAME")), k->new HashSet<>()).add(String.valueOf(r.get("COLUMN_NAME")));
    }
    private ServiceException denied() {return new ServiceException("无权访问该城市数据；全局业务需由全部城市账号处理",403);}
    private String clean(String s) {return s.replace("`","").toLowerCase(Locale.ROOT);}
    private String city(String value) {return "exists (select 1 from kg_city kg_scope_city where kg_scope_city.city_id in ("+AdminCityContext.ids()+") and kg_scope_city.status='0' and (kg_scope_city.name="+value+" or kg_scope_city.full_name="+value+"))";}
    private String owner(String table) {
        // Weekly items inherit the report's store. Other report_id columns are polymorphic.
        if("kg_weekly_item".equals(table))return "report_id";
        if(!OWNED_TABLES.contains(table))return null;
        Set<String> c=columns.getOrDefault(table,Collections.emptySet());
        for(Map.Entry<String,String[]> p:PARENTS.entrySet()) if(!p.getValue()[0].equals(table) && c.contains(p.getKey()))return p.getKey();
        if(c.contains("city_id"))return "city_id";
        if(c.contains("city"))return "city";
        return null;
    }
    private String relation(String column,String value,int depth) {
        if("city".equals(column))return city(value);
        if("city_id".equals(column))return value+" in ("+AdminCityContext.ids()+")";
        if("report_id".equals(column))return "exists (select 1 from kg_weekly_report kg_scope_week where kg_scope_week.report_id="+value+" and "+relation("store_id","kg_scope_week.store_id",depth+1)+")";
        String[] p=PARENTS.get(column);
        if(p==null || depth>8)throw denied();
        String alias="kg_scope_p"+depth;
        return "exists (select 1 from "+p[0]+" "+alias+" where "+alias+"."+p[1]+"="+value+" and "+predicate(p[0],alias,depth+1)+")";
    }
    private String predicate(String table,String alias,int depth) {
        if(REFERENCES.contains(table))return "1=1";
        if("kg_pay_refund".equals(table))return "exists (select 1 from kg_pay_order kg_scope_pay where kg_scope_pay.pay_no="+alias+".pay_no and "+relation("store_id","kg_scope_pay.store_id",depth+1)+")";
        if("kg_staff_grant".equals(table))return "(("+alias+".store_id is not null and "+relation("store_id",alias+".store_id",depth+1)+") or ("+alias+".store_id is null and "+relation("city_id",alias+".city_id",depth+1)+"))";
        if("kg_app_user".equals(table)) {
            // A city may view profiles of its applicants/guests/staff; never the entire user directory.
            String s="select s.store_id from kg_store s where "+city("s.city");
            return "("+alias+".user_id in (select app_user_id from kg_application where store_id in ("+s+")) or "+alias+".user_id in (select app_user_id from kg_stay_order where store_id in ("+s+")) or "+alias+".user_id in (select app_user_id from kg_staff_grant where del_flag='0' and status='0' and (store_id in ("+s+") or (store_id is null and city_id in ("+AdminCityContext.ids()+")))))";
        }
        String key=owner(table);
        if(key==null)throw denied();
        return relation(key,alias+"."+key,depth);
    }
    private void validate(String table,Map<String,Expression> values,boolean creating,Function<Expression,Object> resolver) {
        if(REFERENCES.contains(table) || "kg_app_user".equals(table))throw denied();
        String own=owner(table);
        if(own==null || (creating && !values.containsKey(own)))throw denied();
        for(Map.Entry<String,Expression> e:values.entrySet()) {
            String key=e.getKey();
            if(!key.equals(own) && !PARENTS.containsKey(key) && !"city".equals(key) && !"city_id".equals(key))continue;
            if(PARENTS.containsKey(key) && PARENTS.get(key)[0].equals(table)) { if(!creating)throw denied(); else continue; }
            Object value=value(e.getValue(),resolver);
            if(value==null) {if(key.equals(own))throw denied();else continue;}
            // Validation uses placeholders, not caller-controlled SQL or client city hints.
            String sql="select count(*) from (select ? kg_value) kg_scope_value where "+relation(key,"kg_scope_value.kg_value",0);
            if(raw.queryForObject(sql,Long.class,value)!=1L)throw denied();
        }
    }
    private Object value(Expression e,Function<Expression,Object> resolver) {
        if(e instanceof StringValue)return ((StringValue)e).getValue();
        if(e instanceof LongValue)return ((LongValue)e).getValue();
        if(e instanceof NullValue)return null;
        if(e instanceof JdbcParameter || e instanceof JdbcNamedParameter)return resolver.apply(e);
        throw denied();
    }
    public String sql(String sql,Function<Expression,Object> resolver) {
        if(!AdminCityContext.limited())return sql;
        try {
            Statement stmt=CCJSqlParserUtil.parse(sql);
            Table target=null;
            if(stmt instanceof Select) {
                if(((Select)stmt).getWithItemsList()!=null && !((Select)stmt).getWithItemsList().isEmpty())throw denied();
            } else if(stmt instanceof Update) {
                Update u=(Update)stmt;target=u.getTable();
                if((u.getJoins()!=null && !u.getJoins().isEmpty()) || u.getFromItem()!=null || (u.getStartJoins()!=null && !u.getStartJoins().isEmpty()))throw denied();
                Map<String,Expression> values=new LinkedHashMap<>();
                u.getUpdateSets().forEach(set->{for(int i=0;i<set.getColumns().size();i++)values.put(clean(set.getColumns().get(i).getColumnName()),set.getExpressions().get(i));});
                validate(clean(target.getName()),values,false,resolver);
            } else if(stmt instanceof Delete) {
                Delete d=(Delete)stmt;target=d.getTable();
                if((d.getJoins()!=null && !d.getJoins().isEmpty()) || (d.getTables()!=null && !d.getTables().isEmpty()))throw denied();
                if(owner(clean(target.getName()))==null || REFERENCES.contains(clean(target.getName())))throw denied();
            } else if(stmt instanceof Insert) {
                Insert i=(Insert)stmt;target=i.getTable();
                if(i.isUseDuplicate() || i.isUseSet() || !(i.getItemsList() instanceof ExpressionList))throw denied();
                List<Expression> expressions=((ExpressionList)i.getItemsList()).getExpressions();
                if(i.getColumns()==null || expressions.size()!=i.getColumns().size())throw denied();
                Map<String,Expression> values=new LinkedHashMap<>();
                for(int n=0;n<i.getColumns().size();n++)values.put(clean(i.getColumns().get(n).getColumnName()),expressions.get(n));
                validate(clean(target.getName()),values,true,resolver);
            } else throw denied();
            final Table writeTarget=target;
            // An outer FOR UPDATE does not lock rows read inside a derived table in MySQL.
            final boolean lockingRead=stmt instanceof Select && sql.toLowerCase(Locale.ROOT).matches("(?s).*\\bfor\\s+update\\b.*");
            // Visit only FROM/JOIN tables; Column's qualifier is not a data source.
            new TablesNamesFinder() {
                @Override public void visit(Column col) {}
                @Override public void visit(Table t) {
                    if(t==writeTarget)return;
                    if(t.getSchemaName()!=null)throw denied();
                    String name=clean(t.getName());
                    if(REFERENCES.contains(name))return;
                    String pred=predicate(name,"kg_scope_row",0);
                    if(t.getAlias()==null)t.setAlias(new Alias(name,false));
                    t.setName("(select * from "+name+" kg_scope_row where "+pred+(lockingRead?" for update":"")+")");
                }
            }.getTableList(stmt);
            if(target!=null && !(stmt instanceof Insert)) {
                if(target.getSchemaName()!=null)throw denied();
                String qualifier=target.getAlias()==null?target.getName():target.getAlias().getName();
                Expression old=stmt instanceof Update?((Update)stmt).getWhere():((Delete)stmt).getWhere();
                Expression restricted=CCJSqlParserUtil.parseCondExpression((old==null?"1=1":"("+old+")")+" and ("+predicate(clean(target.getName()),qualifier,0)+")");
                if(stmt instanceof Update)((Update)stmt).setWhere(restricted);else ((Delete)stmt).setWhere(restricted);
            }
            return stmt.toString();
        } catch(ServiceException e) {throw e;} catch(Exception e) {throw new ServiceException("该操作暂不支持城市范围访问，请由平台管理员处理",403);}
    }
}
