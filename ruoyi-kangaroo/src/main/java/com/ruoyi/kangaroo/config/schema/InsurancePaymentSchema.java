package com.ruoyi.kangaroo.config.schema;
import java.util.*;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.kangaroo.ext.KgJdbc;
@Component public class InsurancePaymentSchema implements KgSchemaContributor {
 private final KgJdbc db;
 private final boolean enabled;
 public InsurancePaymentSchema(KgJdbc db,@Value("${kangaroo.schema.assert-indexes:true}") boolean enabled){this.db=db;this.enabled=enabled;}
 public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<>();m.put("kg_insurance_payment",Arrays.asList("PRIMARY","uk_ins_payment_request","uk_ins_payment_pay"));m.put("kg_insurance_account",Arrays.asList("PRIMARY"));m.put("kg_insurance_entry",Arrays.asList("uk_ins_entry_business"));m.put("kg_insurance_refund",Arrays.asList("uk_ins_refund_request","uk_ins_refund_active","uk_ins_refund_channel"));m.put("kg_insurance_request",Arrays.asList("PRIMARY","uk_ins_request_apply","uk_ins_request_refund"));return m;}
 public String migration(){return "sql/kangaroo/V111__insurance_payment.sql";}
 @PostConstruct public void check(){
  if(!enabled)return;
  if(db.count("select count(*) from kg_schema_version where version=111 and name='insurance_payment' and checksum='4f4edc8d3570fdc184c6a9309fbff9fd4c64002a0b596d24a2a967fa65529d7f'",KgJdbc.p())!=1)fail();
  requireColumns("kg_insurance_request","payer_user_id,request_kind,request_key,request_hash,apply_id,refund_id,created_at");
  requireColumns("kg_insurance_payment","apply_id,payer_user_id,store_id,request_key,request_hash,pay_no,amount,method,status,refund_amount,refund_required,expire_time,paid_at,created_at");
  requireColumns("kg_insurance_account","user_id,balance,updated_at");
  requireColumns("kg_insurance_entry","entry_id,user_id,business_key,apply_id,refund_id,type,amount,balance_after,created_at");
  requireColumns("kg_insurance_refund","refund_id,apply_id,store_id,payer_user_id,request_key,request_hash,amount,approved_amount,destination,status,prior_status,reason,handle_note,handled_by,channel_refund_no,created_at,handled_at,completed_at,active_apply_id");
  for(String[] item:new String[][]{{"kg_insurance_payment","amount,refund_amount"},{"kg_insurance_account","balance"},{"kg_insurance_entry","amount,balance_after"},{"kg_insurance_refund","amount"}}){
   List<String>columns=Arrays.asList(item[1].split(","));
   if(db.count("select count(*) from information_schema.columns where table_schema=database() and table_name=:t and column_name in (:c) and data_type='bigint' and is_nullable='NO'",KgJdbc.p("t",item[0],"c",columns))!=columns.size())fail();
  }
  if(db.count("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_insurance_refund' and column_name='active_apply_id' and extra like '%STORED GENERATED%' and generation_expression like '%pending%' and generation_expression like '%processing%'",KgJdbc.p())!=1)fail();
  if(db.count("select count(*) from information_schema.table_constraints where constraint_schema=database() and constraint_name in ('ck_ins_payment_amount','ck_ins_account_balance','ck_ins_refund_amount') and constraint_type='CHECK' and enforced='YES'",KgJdbc.p())!=3)fail();
 }
 private void requireColumns(String table,String names){List<String>columns=Arrays.asList(names.split(","));if(db.count("select count(*) from information_schema.columns where table_schema=database() and table_name=:t and column_name in (:c)",KgJdbc.p("t",table,"c",columns))!=columns.size())fail();}
 private void fail(){throw new IllegalStateException("Insurance payment schema incomplete; run "+migration());}
}
