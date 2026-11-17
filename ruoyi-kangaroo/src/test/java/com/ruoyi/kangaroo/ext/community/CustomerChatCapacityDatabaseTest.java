package com.ruoyi.kangaroo.ext.community;
import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import org.junit.jupiter.api.Test;

class CustomerChatCapacityDatabaseTest {
 @Test void migrationStoresTwentyMegabytePayloadWithoutTruncation() throws Exception {
  String name=System.getenv("KG_MEMBER_TEST_DB");assertNotNull(name);assertTrue(name.matches("kg_member_exec_[a-f0-9]{32}"));
  try(Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/"+name,System.getenv("KG_MEMBER_TEST_USER"),System.getenv("KG_TEST_DB_PASSWORD"))) {
   c.setAutoCommit(false);
   try {
    try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select data_type from information_schema.columns where table_schema=database() and table_name='kg_chat_image' and column_name='image_bytes'")){assertTrue(r.next());assertEquals("longblob",r.getString(1));}
    byte[] data=new byte[20*1024*1024];data[0]=71;data[data.length-1]=72;
    try(PreparedStatement p=c.prepareStatement("insert into kg_chat_image(msg_id,input_sha,mime,width,height,image_bytes) values(987654321,?,'image/png',2,2,?)")){p.setString(1,String.join("",java.util.Collections.nCopies(64,"a")));p.setBytes(2,data);p.executeUpdate();}
    try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select octet_length(image_bytes),hex(left(image_bytes,1)),hex(right(image_bytes,1)) from kg_chat_image where msg_id=987654321")){assertTrue(r.next());assertEquals(data.length,r.getInt(1));assertEquals("47",r.getString(2));assertEquals("48",r.getString(3));}
   } finally {c.rollback();}
  }
 }
}
