package com.ruoyi.kangaroo.config.schema;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;import java.sql.*;import java.util.*;
class ChatMediaSchemaTest {
 static class Db {
  DataSource ds=mock(DataSource.class);Connection c=mock(Connection.class);String failColumn;boolean failQuery,ledger=true,wrongPk,compositePk,wrongEngine,wrongCollation,wrongChecksum,wrongType,wrongNullable,wrongLength,wrongSequence,nonUnique;int calls;
  Db()throws Exception{when(ds.getConnection()).thenReturn(c);when(c.prepareStatement(anyString())).thenAnswer(i->{String sql=i.getArgument(0);PreparedStatement p=mock(PreparedStatement.class);Map<Integer,String> v=new HashMap<>();doAnswer(a->{v.put(a.getArgument(0),a.getArgument(1));return null;}).when(p).setString(anyInt(),anyString());when(p.executeQuery()).thenAnswer(a->{if(failQuery)throw new SQLException("synthetic unavailable");ResultSet r=mock(ResultSet.class);if(sql.contains("kg_schema_version")){when(r.next()).thenReturn(ledger,false);when(r.getString(1)).thenReturn("chat_private_media");when(r.getString(2)).thenReturn(wrongChecksum?"bad":ChatMediaSchema.CHECKSUM);return r;}
if(sql.contains("information_schema.tables t")){when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn(wrongEngine?"MyISAM":"InnoDB");when(r.getString(2)).thenReturn(wrongCollation?"utf8mb4_bin":"utf8mb4_unicode_ci");when(r.getString(3)).thenReturn("utf8mb4_unicode_ci");return r;}
if(sql.contains("information_schema.statistics")){when(r.next()).thenReturn(true,compositePk,false);when(r.getString(1)).thenReturn(wrongPk?"other":v.get(1).equals("kg_chat_image")?"msg_id":"store_id");when(r.getInt(2)).thenReturn(wrongSequence?2:1);when(r.getInt(3)).thenReturn(nonUnique?1:0);return r;}calls++;String col=v.get(2);when(r.next()).thenReturn(!col.equals(failColumn),false);String type="bigint";if(col.equals("update_time"))type="datetime";if(col.equals("input_sha"))type="char";if(col.equals("mime"))type="varchar";if(col.equals("width")||col.equals("height"))type="int";if(col.equals("image_bytes"))type="longblob";when(r.getString(1)).thenReturn(wrongType?"varchar":type);when(r.getString(2)).thenReturn(wrongNullable?"MAYBE":col.equals("first_id")||col.equals("second_id")?"YES":"NO");when(r.getLong(3)).thenReturn(wrongLength?1L:col.equals("input_sha")?64L:10L);return r;});return p;});}
 }
 @Test void completeTwelveColumnsAndLedgerChecked()throws Exception{Db d=new Db();new ChatMediaSchema(d.ds,true).checkColumns();assertEquals(12,d.calls);verify(d.c).close();}
 @Test void everyRequiredColumnIndividuallyFailsClosed()throws Exception{for(String col:Arrays.asList("store_id","first_id","second_id","revision","updated_by","update_time","msg_id","input_sha","mime","width","height","image_bytes")){Db d=new Db();d.failColumn=col;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}}
 @Test void connectionFailureFailsClosed()throws Exception{Db d=new Db();when(d.ds.getConnection()).thenThrow(new SQLException("fixture"));assertTrue(assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns()).getCause() instanceof SQLException);}
 @Test void partialQueryFailureFailsClosed()throws Exception{Db d=new Db();d.failQuery=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void missingLedgerFailsClosed()throws Exception{Db d=new Db();d.ledger=false;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void wrongChecksumFailsClosed()throws Exception{Db d=new Db();d.wrongChecksum=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void wrongPrimaryColumnFailsClosed()throws Exception{Db d=new Db();d.wrongPk=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void compositePrimaryFailsClosed()throws Exception{Db d=new Db();d.compositePk=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void nonTransactionalEngineFailsClosed()throws Exception{Db d=new Db();d.wrongEngine=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void differentCollationFailsClosed()throws Exception{Db d=new Db();d.wrongCollation=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void wrongTypeFailsClosed()throws Exception{Db d=new Db();d.wrongType=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void wrongNullabilityFailsClosed()throws Exception{Db d=new Db();d.wrongNullable=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void wrongFixedLengthFailsClosed()throws Exception{Db d=new Db();d.wrongLength=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void primarySequenceFailsClosed()throws Exception{Db d=new Db();d.wrongSequence=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void primaryNonUniqueFailsClosed()throws Exception{Db d=new Db();d.nonUnique=true;assertThrows(IllegalStateException.class,()->new ChatMediaSchema(d.ds,true).checkColumns());}
 @Test void registersBothUniquePrimaryKeys()throws Exception{ChatMediaSchema s=new ChatMediaSchema(new Db().ds,true);assertEquals(new HashSet<>(Arrays.asList("kg_chat_image","kg_chat_reception")),s.requiredIndexes().keySet());s.requiredIndexes().values().forEach(v->assertEquals(Arrays.asList("PRIMARY"),v));assertTrue(s.migration().contains("V103__"));}
}
