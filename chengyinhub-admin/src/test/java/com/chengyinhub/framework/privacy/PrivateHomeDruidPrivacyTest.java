package com.chengyinhub.framework.privacy;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.stat.JdbcSqlStat;
import com.alibaba.druid.spring.boot4.autoconfigure.stat.DruidFilterConfiguration;
import com.chengyinhub.business.home.PrivateHomeLogScope;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class PrivateHomeDruidPrivacyTest {
    DruidDataSource pool() throws Exception {
        PrivateHomeStatFilter filter=new PrivateHomeStatFilter();filter.setSlowSqlMillis(-1);filter.setLogSlowSql(true);filter.setMergeSql(true);
        DruidDataSource ds=new PrivateHomeDruidDataSource();ds.setUrl("jdbc:h2:mem:privacy"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        ds.setDriverClassName("org.h2.Driver");ds.setUsername("sa");ds.setPassword("");ds.setInitialSize(0);
        ds.setProxyFilters(Collections.singletonList(filter));ds.init();return ds;
    }
    @Test void starterHasOnePrivacyFilterAndKeepsExistingTimingConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(PrivateHomePrivacyConfiguration.class)
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,DruidFilterConfiguration.class))
            .withPropertyValues("spring.datasource.druid.filter.stat.enabled=true","spring.datasource.druid.filter.stat.slow-sql-millis=4321",
                "spring.datasource.druid.filter.stat.log-slow-sql=true","spring.datasource.druid.filter.stat.merge-sql=true")
            .run(context->{assertNull(context.getStartupFailure());assertEquals(1,context.getBeansOfType(StatFilter.class).size());
                StatFilter filter=context.getBean(StatFilter.class);assertTrue(filter instanceof PrivateHomeStatFilter);
                assertEquals(4321,filter.getSlowSqlMillis());assertTrue(filter.isLogSlowSql());assertTrue(filter.isMergeSql());});
    }
    private void createPrivateHomeFixture(Connection connection,String columns) throws SQLException {
        // Each caller owns a distinct UUID-backed pool; keep the shared table creation in one place.
        try(java.sql.Statement statement=connection.createStatement()) {
            statement.execute("CREATE TABLE player_private_home("+columns+")");
        }
    }
    @Test void actualBoundCoordinateExecutesButSlowStatsContainOnlyRedaction() throws Exception {
        try(DruidDataSource ds=pool();Connection c=ds.getConnection()) {
            createPrivateHomeFixture(c,"owner_id BIGINT PRIMARY KEY,latitude DECIMAL(9,6),label VARCHAR(100)");
            try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open();PreparedStatement statement=c.prepareStatement("INSERT INTO player_private_home VALUES(?,?,?)")) {
                statement.setLong(1,7);statement.setBigDecimal(2,new BigDecimal("30.123456"));statement.setString(3,"PRIVATE_LABEL");assertEquals(1,statement.executeUpdate());
            }
            try(ResultSet row=c.createStatement().executeQuery("SELECT latitude FROM player_private_home WHERE owner_id=7")){assertTrue(row.next());assertEquals(new BigDecimal("30.123456"),row.getBigDecimal(1));}
            JdbcSqlStat stat=ds.getSqlStatMap().values().stream().filter(s->s.getSql().startsWith("INSERT")).findFirst().orElseThrow(AssertionError::new);
            assertEquals("[\"private-home:redacted\"]",stat.getLastSlowParameters());assertEquals(1,stat.getExecuteSuccessCount());
            assertFalse(ds.getStatData().toString().contains("30.123456"));assertFalse(ds.getStatData().toString().contains("PRIVATE_LABEL"));
        }
    }
    @Test void retainedSqlErrorLosesPrivateMessageAndCauseButKeepsCount() throws Exception {
        try(DruidDataSource ds=pool();Connection c=ds.getConnection()) {
            createPrivateHomeFixture(c,"latitude INT");
            try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open();PreparedStatement statement=c.prepareStatement("INSERT INTO player_private_home VALUES(?)")) {
                statement.setString(1,"PRIVATE_LABEL_30.123456");assertThrows(SQLException.class,statement::executeUpdate);
            }
            JdbcSqlStat stat=ds.getSqlStatMap().values().stream().filter(s->s.getSql().startsWith("INSERT")).findFirst().orElseThrow(AssertionError::new);
            assertEquals(1,stat.getErrorCount());Throwable failure=stat.getExecuteErrorLast();
            assertEquals("PRIVATE_HOME_SQL_ERROR",failure.getMessage());assertNull(failure.getCause());assertEquals(0,failure.getStackTrace().length);
            assertFalse(ds.getStatData().toString().contains("PRIVATE_LABEL_30.123456"));
            assertEquals("PRIVATE_HOME_SQL_ERROR",ds.getLastError().getMessage());assertNull(ds.getLastError().getCause());assertEquals(1,ds.getErrorCount());
        }
    }
    @Test void unrelatedSqlStillHasOriginalParameterStatistics() throws Exception {
        try(DruidDataSource ds=pool();Connection c=ds.getConnection()) {
            c.createStatement().execute("CREATE TABLE ordinary_probe(note VARCHAR(100))");
            try(PreparedStatement statement=c.prepareStatement("INSERT INTO ordinary_probe VALUES(?)")){statement.setString(1,"ordinary-public-value");statement.executeUpdate();}
            JdbcSqlStat stat=ds.getSqlStatMap().values().stream().filter(s->s.getSql().startsWith("INSERT")).findFirst().orElseThrow(AssertionError::new);
            assertTrue(stat.getLastSlowParameters().contains("ordinary-public-value"));assertEquals(1,stat.getExecuteSuccessCount());
        }
    }
    @Test void stockDruidBuilderIsNotReplacedByThePrivacyPool() throws Exception {
        try (DruidDataSource stock = com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceBuilder.create().build()) {
            assertFalse(stock instanceof PrivateHomeDruidDataSource);
        }
    }
    @Test void poolRetainsOnlySafeErrorAndPreservesOriginalFatalClassificationAndListeners() throws Exception {
        try(DruidDataSource ds=pool();com.alibaba.druid.pool.DruidPooledConnection connection=(com.alibaba.druid.pool.DruidPooledConnection)ds.getConnection()) {
            SQLException original=new SQLException("PRIVATE_LABEL_30.123456", "42000", 99);
            java.util.concurrent.atomic.AtomicReference<SQLException> received=new java.util.concurrent.atomic.AtomicReference<>();
            connection.addConnectionEventListener(new javax.sql.ConnectionEventListener(){
                public void connectionClosed(javax.sql.ConnectionEvent event){}
                public void connectionErrorOccurred(javax.sql.ConnectionEvent event){received.set(event.getSQLException());}
            });
            ds.setExceptionSorter(new com.alibaba.druid.pool.ExceptionSorter(){
                public boolean isExceptionFatal(SQLException error){assertSame(original,error);return error.getMessage().contains("PRIVATE_LABEL");}
                public void configFromProperties(Properties p){}
            });
            try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {
                SQLException failure=assertThrows(SQLException.class,()->ds.handleConnectionException(connection,original,"UPDATE player_private_home SET latitude=30.123456"));
                assertEquals("PRIVATE_HOME_SQL_ERROR",failure.getMessage());assertEquals("42000",failure.getSQLState());assertEquals(99,failure.getErrorCode());
                assertSame(failure,received.get());assertSame(failure,ds.getLastError());
                assertSame(failure,org.springframework.test.util.ReflectionTestUtils.getField(ds,"lastFatalError"));
                assertEquals("PRIVATE_HOME_SQL",org.springframework.test.util.ReflectionTestUtils.getField(ds,"lastFatalErrorSql"));
                assertEquals(1,ds.getErrorCount());assertEquals(1,ds.getDiscardCount());assertNull(failure.getCause());
            }
        }
    }
    @Test void nonHomePoolFailureDelegatesUnchanged() throws Exception {
        try(DruidDataSource ds=pool();com.alibaba.druid.pool.DruidPooledConnection connection=(com.alibaba.druid.pool.DruidPooledConnection)ds.getConnection()) {
            SQLException original=new SQLException("ordinary failure", "42000", 99);
            assertSame(original,assertThrows(SQLException.class,()->ds.handleConnectionException(connection,original,"UPDATE ordinary_probe SET value=?")));
            assertSame(original,ds.getLastError());assertEquals(1,ds.getErrorCount());
        }
    }
    @Test void concurrentPrivateFailuresPreserveAtomicPoolErrorCount() throws Exception {
        try(DruidDataSource ds=pool()) {
            java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                List<java.util.concurrent.Future<?>> futures=new ArrayList<>();
                for(int i=0;i<16;i++) futures.add(workers.submit(()->{
                    try(com.alibaba.druid.pool.DruidPooledConnection connection=(com.alibaba.druid.pool.DruidPooledConnection)ds.getConnection();PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {
                        assertThrows(SQLException.class,()->ds.handleConnectionException(connection,new SQLException("PRIVATE_LABEL_30.123456"),null));
                    } catch(SQLException error){throw new AssertionError(error);}
                }));
                for(java.util.concurrent.Future<?> future:futures) future.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(16,ds.getErrorCount());assertEquals("PRIVATE_HOME_SQL_ERROR",ds.getLastError().getMessage());
            } finally {workers.shutdownNow();}
        }
    }
}
