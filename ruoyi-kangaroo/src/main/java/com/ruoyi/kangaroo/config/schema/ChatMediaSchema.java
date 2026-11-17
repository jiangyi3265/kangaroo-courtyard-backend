package com.ruoyi.kangaroo.config.schema;
import java.sql.*;
import java.util.*;
import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Required PKs are checked by SchemaAssertion. This same contributor also
 * checks exact V33 column contracts and ledger before the app can serve. */
@Component
public class ChatMediaSchema implements KgSchemaContributor {
 public static final String CHECKSUM="239f4d1514abdae31ba8cc09d1eafcb86f5274ae78c4a4623c008b3a0ccd2c97";
 private final DataSource dataSource; private final boolean enabled;
 @Autowired public ChatMediaSchema(DataSource ds,@Value("${kangaroo.schema.assert-indexes:true}") boolean enabled){this.dataSource=ds;this.enabled=enabled;}
 public Map<String,List<String>> requiredIndexes(){Map<String,List<String>> m=new LinkedHashMap<String,List<String>>();m.put("kg_chat_reception",Arrays.asList("PRIMARY"));m.put("kg_chat_image",Arrays.asList("PRIMARY"));return m;}
 public String migration(){return "sql/kangaroo/V103__chat_image_capacity.sql";}
 @PostConstruct public void checkColumns(){
  if(!enabled)return; // The existing production provider guard rejects this test-only switch.
  try(Connection c=dataSource.getConnection()){
   table(c,"kg_chat_reception","store_id");table(c,"kg_chat_image","msg_id");
   column(c,"kg_chat_reception","store_id","bigint","NO",null);
   column(c,"kg_chat_reception","first_id","bigint","YES",null);column(c,"kg_chat_reception","second_id","bigint","YES",null);
   column(c,"kg_chat_reception","revision","bigint","NO",null);column(c,"kg_chat_reception","updated_by","bigint","NO",null);column(c,"kg_chat_reception","update_time","datetime","NO",null);
   column(c,"kg_chat_image","msg_id","bigint","NO",null);column(c,"kg_chat_image","input_sha","char","NO",64L);
   column(c,"kg_chat_image","mime","varchar","NO",10L);column(c,"kg_chat_image","width","int","NO",null);column(c,"kg_chat_image","height","int","NO",null);column(c,"kg_chat_image","image_bytes","longblob","NO",null);
   try(PreparedStatement p=c.prepareStatement("select name,checksum from kg_schema_version where version=33" );ResultSet r=p.executeQuery()){
    if(!r.next()||!"chat_private_media".equals(r.getString(1))||!CHECKSUM.equals(r.getString(2))||r.next())throw new IllegalStateException("V33 private chat schema ledger missing or changed; refuse startup");
   }
  }catch(SQLException e){throw new IllegalStateException("V33 private chat schema check incomplete; refuse startup",e);}
 }
 private static void table(Connection c,String table,String primary)throws SQLException{
  try(PreparedStatement p=c.prepareStatement("select t.ENGINE,t.TABLE_COLLATION,(select TABLE_COLLATION from information_schema.tables where table_schema=database() and table_name='kg_application') from information_schema.tables t where t.table_schema=database() and t.table_name=?")){
   p.setString(1,table);try(ResultSet r=p.executeQuery()){
    if(!r.next()||!"InnoDB".equalsIgnoreCase(r.getString(1))||r.getString(2)==null||!r.getString(2).equals(r.getString(3))||r.next())throw new IllegalStateException("V33 table engine/collation mismatch: "+table);
   }
  }
  try(PreparedStatement p=c.prepareStatement("select COLUMN_NAME,SEQ_IN_INDEX,NON_UNIQUE from information_schema.statistics where table_schema=database() and table_name=? and index_name='PRIMARY' order by SEQ_IN_INDEX")){
   p.setString(1,table);try(ResultSet r=p.executeQuery()){
    if(!r.next()||!primary.equals(r.getString(1))||r.getInt(2)!=1||r.getInt(3)!=0||r.next())throw new IllegalStateException("V33 primary key mismatch: "+table);
   }
  }
 }
 private static void column(Connection c,String table,String name,String type,String nullable,Long length)throws SQLException{
  try(PreparedStatement p=c.prepareStatement("select DATA_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH from information_schema.columns where table_schema=database() and table_name=? and column_name=?")){
   p.setString(1,table);p.setString(2,name);
   try(ResultSet r=p.executeQuery()){
    if(!r.next()||!type.equals(r.getString(1))||!nullable.equals(r.getString(2))||(length!=null&&r.getLong(3)!=length.longValue()))throw new IllegalStateException("V33 private chat column mismatch: "+table+"."+name);
    if(r.next())throw new IllegalStateException("V33 duplicate schema metadata");
   }
  }
 }
}
