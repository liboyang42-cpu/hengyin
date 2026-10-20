package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** NOT_RUN. Real production mapped SQL; time is pinned only on this test connection. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OWN_C06_30Test {
    private final MysqlIntegrationTestConfig mysql=MysqlIntegrationTestConfig.fromEnvironment("c06_week");
    private DriverManagerDataSource source;
    private SqlSessionFactory factory;
    private boolean created;
    private JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(),mysql.user(),mysql.password()));
    }
    @BeforeAll void setup() throws Exception {
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_HOST"));
        String suffix=System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX");assertNotNull(suffix);assertFalse(suffix.trim().isEmpty());
        admin().execute("CREATE DATABASE "+mysql.databaseName());created=true;
        source=new DriverManagerDataSource(mysql.databaseUrl(),mysql.user(),mysql.password());
        JdbcTemplate jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE ums_member_balance_detail(id BIGINT PRIMARY KEY,member_id BIGINT,change_balance DECIMAL(18,2),change_type INT,del_flag INT,create_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO ums_member_balance_detail VALUES (1,7,99,1,0,'2026-10-11 23:59:59'),(2,7,10,1,0,'2026-10-12 00:00:00'),(3,7,20,1,0,'2026-10-18 23:59:58'),(4,7,1000,1,0,'2026-10-19 00:00:00'),(5,7,500,2,0,'2026-10-14 12:00:00'),(6,7,600,1,2,'2026-10-14 12:00:00'),(7,8,700,1,0,'2026-10-14 12:00:00')");
        jdbc.execute("CREATE TABLE ums_member_points_detail(id BIGINT PRIMARY KEY,member_id BIGINT,change_points BIGINT,change_type INT,del_flag INT,create_time DATETIME) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO ums_member_points_detail VALUES (1,7,99,1,0,'2026-10-11 23:59:59'),(2,7,4,1,0,'2026-10-12 00:00:00'),(3,7,6,1,0,'2026-10-18 23:59:58'),(4,7,500,1,0,'2026-10-19 00:00:00'),(5,7,700,2,0,'2026-10-14 12:00:00'),(6,7,800,1,2,'2026-10-14 12:00:00'),(7,8,900,1,0,'2026-10-14 12:00:00')");
        Configuration configuration=new Configuration(new Environment("c06-week",new JdbcTransactionFactory(),source));
        configuration.getTypeAliasRegistry().registerAlias("UmsMemberBalanceDetail",UmsMemberBalanceDetail.class);
        configuration.getTypeAliasRegistry().registerAlias("UmsMemberPointsDetail",UmsMemberPointsDetail.class);
        for(String resource:new String[]{"mapper/business/UmsMemberBalanceDetailMapper.xml","mapper/business/UmsMemberPointsDetailMapper.xml"}) {
            try(InputStream input=Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
            }
        }
        factory=new SqlSessionFactoryBuilder().build(configuration);
    }
    @AfterAll void cleanup() {if(created)admin().execute("DROP DATABASE "+mysql.databaseName());}
    private void pin(Connection connection,String date) throws SQLException {
        // Task-owned connection only: no global timestamp, system clock or production target.
        try(Statement statement=connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
            statement.execute("SET timestamp = UNIX_TIMESTAMP('"+date+"')");
        }
    }
    @Test void sundayUsesPreviousMondayAndRetainsMemberDirectionAndDeletionFilters() throws Exception {
        try(Connection connection=source.getConnection();SqlSession session=factory.openSession(connection)) {
            pin(connection,"2026-10-18 23:59:59");
            BigDecimal amount=session.getMapper(UmsMemberBalanceDetailMapper.class).selectWeekBalanceIncome(7L);
            assertEquals(0,new BigDecimal("30.00").compareTo(amount));
        }
    }
    @Test void nextMondayStartsNewWeekAtMidnight() throws Exception {
        try(Connection connection=source.getConnection();SqlSession session=factory.openSession(connection)) {
            pin(connection,"2026-10-19 00:00:00");
            assertEquals(0,new BigDecimal("1000.00").compareTo(session.getMapper(UmsMemberBalanceDetailMapper.class).selectWeekBalanceIncome(7L)));
        }
    }
    @Test void sundayPointsUseSameMondayBoundaryAndOriginalFilters() throws Exception {
        try(Connection connection=source.getConnection();SqlSession session=factory.openSession(connection)) {
            pin(connection,"2026-10-18 23:59:59");
            assertEquals(0,new BigDecimal("10").compareTo(session.getMapper(UmsMemberPointsDetailMapper.class).selectWeekPointsIncome(7L)));
        }
    }
    @Test void nextMondayPointsStartNewWeekAtMidnight() throws Exception {
        try(Connection connection=source.getConnection();SqlSession session=factory.openSession(connection)) {
            pin(connection,"2026-10-19 00:00:00");
            assertEquals(0,new BigDecimal("500").compareTo(session.getMapper(UmsMemberPointsDetailMapper.class).selectWeekPointsIncome(7L)));
        }
    }
    @Test void negativeControlOldSundayExpressionSelectsFutureWeekFixture() throws Exception {
        try(Connection connection=source.getConnection()) {
            pin(connection,"2026-10-18 23:59:59");
            // Exact pre-fix predicate, not production mapper execution; future row is an explicit boundary probe.
            String old="SELECT COALESCE(SUM(change_balance),0) FROM ums_member_balance_detail WHERE member_id=7 AND change_type=1 AND del_flag=0 AND create_time >= DATE_SUB(CURDATE(),INTERVAL(DAYOFWEEK(CURDATE())-2)DAY) AND create_time < DATE_ADD(DATE_SUB(CURDATE(),INTERVAL(DAYOFWEEK(CURDATE())-2)DAY),INTERVAL 7 DAY)";
            try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery(old)) {
                assertTrue(result.next());assertEquals(0,new BigDecimal("1000.00").compareTo(result.getBigDecimal(1)));
            }
        }
    }
}
