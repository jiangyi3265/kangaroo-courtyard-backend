package com.ruoyi.kangaroo.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ruoyi.kangaroo.config.schema.KgSchemaContributor;

/** Real check() method with scripted metadata JDBC. No database or Spring boot. */
class SchemaAssertionFailureClosedTest {
    static final class Metadata {
        final DataSource ds=mock(DataSource.class);
        final Connection connection=mock(Connection.class);
        final List<PreparedStatement> statements=new ArrayList<>();
        final List<ResultSet> results=new ArrayList<>();
        final List<String> sqls=new ArrayList<>();
        final Set<String> missing=new HashSet<>(), nonUnique=new HashSet<>();
        int queries, failAt;
        SQLException queryFailure, resultCloseFailure;
        Metadata() throws Exception {
            when(ds.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenAnswer(invocation->{
                String sql=invocation.getArgument(0);sqls.add(sql);
                PreparedStatement statement=mock(PreparedStatement.class);statements.add(statement);
                Map<Integer,String> args=new HashMap<>();
                doAnswer(call->{args.put(call.getArgument(0),call.getArgument(1));return null;}).when(statement).setString(anyInt(),anyString());
                when(statement.executeQuery()).thenAnswer(call->{
                    if(++queries==failAt)throw queryFailure;
                    String key=args.get(1)+"."+args.get(2);
                    int count=missing.contains(key)||(sql.contains("non_unique = 0")&&nonUnique.contains(key))?0:1;
                    ResultSet result=mock(ResultSet.class);results.add(result);
                    when(result.next()).thenReturn(true,false);when(result.getInt(1)).thenReturn(count);
                    if(resultCloseFailure!=null)doThrow(resultCloseFailure).when(result).close();
                    return result;
                });
                return statement;
            });
        }
        void closed() throws Exception {
            verify(connection).close();
            for(PreparedStatement statement:statements)verify(statement).close();
            for(ResultSet result:results)verify(result).close();
        }
    }
    static KgSchemaContributor contributor(String... names){
        return new KgSchemaContributor(){
            public Map<String,List<String>> requiredIndexes(){return Collections.singletonMap("owned_table",Arrays.asList(names));}
            public String migration(){return "V_fixture_required.sql";}
        };
    }
    static SchemaAssertion assertion(Metadata m,String... indexes){return new SchemaAssertion(m.ds,Collections.singletonList(contributor(indexes)),true);}
    static List<ILoggingEvent> logs(Executable body) throws Throwable {
        Logger logger=(Logger)LoggerFactory.getLogger(SchemaAssertion.class);Level previous=logger.getLevel();
        ListAppender<ILoggingEvent> appender=new ListAppender<>();appender.start();logger.addAppender(appender);logger.setLevel(Level.INFO);
        try{body.execute();}finally{logger.detachAppender(appender);logger.setLevel(previous);appender.stop();}
        return appender.list;
    }
    static boolean passed(List<ILoggingEvent> events){return events.stream().anyMatch(e->e.getLevel()==Level.INFO&&e.getFormattedMessage().contains("启动断言通过"));}

    @Test void connectionFailureRefusesWithExactCauseAndNoSuccessLog() throws Throwable {
        Metadata m=new Metadata();SQLException cause=new SQLException("synthetic connection unavailable","08001",2003);
        when(m.ds.getConnection()).thenThrow(cause);
        List<ILoggingEvent> events=logs(()->{
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->assertion(m,"uk_first").check());
            assertSame(cause,failure.getCause());assertTrue(failure.getMessage().contains("拒绝启动"));
        });
        assertFalse(passed(events));assertTrue(m.sqls.isEmpty());verifyNoInteractions(m.connection);
    }
    @Test void laterQueryFailureDiscardsPartialSuccessAndClosesEveryResource() throws Throwable {
        Metadata m=new Metadata();SQLException cause=new SQLException("synthetic second metadata query failure","HY000",1105);
        m.failAt=2;m.queryFailure=cause;
        List<ILoggingEvent> events=logs(()->assertSame(cause,assertThrows(IllegalStateException.class,()->assertion(m,"uk_first","uk_second","uk_third").check()).getCause()));
        assertFalse(passed(events));assertEquals(2,m.queries);assertEquals(1,m.results.size());m.closed();
    }
    @Test void queryFailureRemainsCauseWhenConnectionCloseAlsoFails() throws Throwable {
        Metadata m=new Metadata();SQLException cause=new SQLException("synthetic metadata failure");SQLException close=new SQLException("synthetic close failure");
        m.failAt=1;m.queryFailure=cause;doThrow(close).when(m.connection).close();
        List<ILoggingEvent> events=logs(()->{
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->assertion(m,"PRIMARY").check());
            assertSame(cause,failure.getCause());assertArrayEquals(new Throwable[]{close},cause.getSuppressed());
        });
        assertFalse(passed(events));m.closed();
    }
    @Test void resultCloseFailureBeforeCollectionCompletesRefusesStartup() throws Throwable {
        Metadata m=new Metadata();m.resultCloseFailure=new SQLException("synthetic result close failure");
        List<ILoggingEvent> events=logs(()->assertSame(m.resultCloseFailure,assertThrows(IllegalStateException.class,()->assertion(m,"uk_first","uk_second").check()).getCause()));
        assertFalse(passed(events));assertEquals(1,m.queries);m.closed();
    }
    @Test void completedPositiveResultStillPassesWhenOnlyConnectionCloseFails() throws Throwable {
        Metadata m=new Metadata();doThrow(new SQLException("synthetic pool return failure")).when(m.connection).close();
        List<ILoggingEvent> events=logs(()->assertDoesNotThrow(()->assertion(m,"uk_first","PRIMARY").check()));
        assertTrue(passed(events));assertEquals(2,m.queries);m.closed();
    }
    @Test void sameNameNonUniqueUkCannotSatisfyUniqueRegistration() throws Throwable {
        Metadata m=new Metadata();m.nonUnique.add("owned_table.uk_first");
        List<ILoggingEvent> events=logs(()->{
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->assertion(m,"uk_first").check());
            assertTrue(failure.getMessage().contains("owned_table.uk_first"));assertTrue(failure.getMessage().contains("V_fixture_required.sql"));
        });
        assertFalse(passed(events));m.closed();
    }
    @Test void primaryRegistrationRequiresUniqueMetadata() throws Throwable {
        Metadata m=new Metadata();m.nonUnique.add("owned_table.PRIMARY");
        List<ILoggingEvent> events=logs(()->assertTrue(assertThrows(IllegalStateException.class,()->assertion(m,"PRIMARY").check()).getMessage().contains("owned_table.PRIMARY")));
        assertFalse(passed(events));m.closed();
    }
    @Test void legitimateRetryAndPayoutSearchIndexesMayRemainNonUnique() throws Throwable {
        Metadata m=new Metadata();m.nonUnique.add("owned_table.idx_outbox_retry");m.nonUnique.add("owned_table.idx_online_correction_app");
        List<ILoggingEvent> events=logs(()->assertDoesNotThrow(()->assertion(m,"idx_outbox_retry","idx_online_correction_app","PRIMARY","uk_first").check()));
        assertTrue(passed(events));assertEquals(4,m.queries);m.closed();
        assertFalse(m.sqls.get(0).contains("non_unique = 0"));assertFalse(m.sqls.get(1).contains("non_unique = 0"));
        assertTrue(m.sqls.get(2).contains("non_unique = 0"));assertTrue(m.sqls.get(3).contains("non_unique = 0"));
    }
}
