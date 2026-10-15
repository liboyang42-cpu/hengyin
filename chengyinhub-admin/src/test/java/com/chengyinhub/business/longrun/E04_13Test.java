package com.chengyinhub.business.longrun;

import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.RedemptionRewardClawbackService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NOT_RUN. Real MySQL RR read view plus independent committed balance changes; no mocked reads. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E04_13Test {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("e04_13_it");
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private TransactionTemplate rr;
    private UmsMemberMapper members;
    private RedemptionRewardClawbackService clawback;
    private boolean databaseCreated;
    private String base;

    @BeforeAll
    void setUp() throws Exception {
        for (String key:new String[]{"CHENGYIN_TEST_MYSQL_HOST","CHENGYIN_TEST_MYSQL_PORT",
                "CHENGYIN_TEST_MYSQL_DATABASE_BASE","CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX","CHENGYIN_TEST_MYSQL_USER"}) {
            String value=System.getenv(key);
            assertTrue(value!=null && !value.trim().isEmpty(),key+" must be explicit");
        }
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_PASSWORD"));
        assertEquals("e04_scratch",System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_BASE"));
        JdbcTemplate admin=new JdbcTemplate(dataSource(MYSQL.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()",String.class)));
        // Refuse existing schemas; never drop a pre-existing database.
        admin.execute("CREATE DATABASE "+MYSQL.databaseName()+" CHARACTER SET utf8mb4");
        databaseCreated=true;
        ds=dataSource(MYSQL.databaseUrl());
        jdbc=new JdbcTemplate(ds);
        Path root=Paths.get("").toAbsolutePath();
        while (root!=null && !Files.exists(root.resolve("sql/base_schema_20260818.sql"))) root=root.getParent();
        assertNotNull(root,"Repository schema source must exist");
        base=new String(Files.readAllBytes(root.resolve("sql/base_schema_20260818.sql")),StandardCharsets.UTF_8);

        Configuration config=new Configuration(new Environment("e04-13",new SpringManagedTransactionFactory(),ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (Class<?> type:new Class<?>[]{UmsMemberMapper.class,UmsMemberPointsDetailMapper.class}) {
            config.addMapper(type);
            String resource="mapper/business/"+type.getSimpleName()+".xml";
            try (InputStream input=getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input,resource);
                new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate template=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        members=template.getMapper(UmsMemberMapper.class);
        clawback=new RedemptionRewardClawbackService();
        ReflectionTestUtils.setField(clawback,"umsMemberMapper",members);
        ReflectionTestUtils.setField(clawback,"pointsDetailMapper",template.getMapper(UmsMemberPointsDetailMapper.class));
        rr=new TransactionTemplate(new DataSourceTransactionManager(ds));
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TABLE IF EXISTS ums_member_points_detail");
        jdbc.execute("DROP TABLE IF EXISTS ums_member");
        createBaseTable("ums_member");
        createBaseTable("ums_member_points_detail");
        // Existing full member reader consumes this post-base column, so even the old query is valid.
        jdbc.execute("ALTER TABLE ums_member ADD COLUMN app_open_id VARCHAR(128) NULL");
        jdbc.update("INSERT INTO ums_member(id,point,del_flag) VALUES (501,100,0)");
        jdbc.update("INSERT INTO ums_member_points_detail(id,member_id,current_points,after_points,change_points,event_type,event_id,change_type,del_flag)"
                + " VALUES (10,501,90,100,10,11,9001,1,0)");
    }

    @AfterAll
    void cleanUp() {
        if (databaseCreated) new JdbcTemplate(dataSource(MYSQL.adminUrl())).execute("DROP DATABASE "+MYSQL.databaseName());
    }

    @Test
    void committedBalanceIncreaseAfterRrSnapshotUsesCurrent105To95Ledger() {
        Map<String,Object> result=clawAfterSnapshotAndIndependentCommit(105L);
        assertEquals(10L,((Number)result.get("deducted")).longValue());
        assertEquals(0L,((Number)result.get("shortfall")).longValue());
        assertLedgerAndBalance(105L,95L,10L);
    }

    @Test
    void committedBalanceDecreaseAfterRrSnapshotDeductsOnlyCurrentSixAndRecordsShortfall() {
        Map<String,Object> result=clawAfterSnapshotAndIndependentCommit(6L);
        assertEquals(6L,((Number)result.get("deducted")).longValue());
        assertEquals(4L,((Number)result.get("shortfall")).longValue());
        assertLedgerAndBalance(6L,0L,6L);
    }

    private Map<String,Object> clawAfterSnapshotAndIndependentCommit(long committedBalance) {
        return rr.execute(status -> {
            // These are actual nonlocking SQL reads on a bound RR transaction, not mapper/mock snapshots.
            String isolation=jdbc.queryForObject("SELECT @@transaction_isolation",String.class);
            assertEquals("REPEATABLE-READ",isolation);
            assertEquals(100L,jdbc.queryForObject("SELECT point FROM ums_member WHERE id=501",Long.class));
            try (Connection independent=ds.getConnection();PreparedStatement update=independent.prepareStatement(
                    "UPDATE ums_member SET point=? WHERE id=501")) {
                assertTrue(independent.getAutoCommit());
                update.setLong(1,committedBalance);
                assertEquals(1,update.executeUpdate());
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
            // Establish that the genuine read view is still old before taking the member lock.
            assertEquals(100L,jdbc.queryForObject("SELECT point FROM ums_member WHERE id=501",Long.class));
            assertEquals(Long.valueOf(501),members.lockMemberById(501L));
            // Same documented caller precondition as clawback(): member lock before grant/ledger locks.
            Map<String,Object> result=clawback.clawPoints(501L,11L,9001L,"E04 RR clawback","redeem");
            assertNotNull(result);
            assertEquals(10L,((Number)result.get("requested")).longValue());
            return result;
        });
    }

    private void assertLedgerAndBalance(long before,long after,long deducted) {
        // Fresh, independent connections after the RR transaction commits.
        try (Connection c=ds.getConnection();PreparedStatement query=c.prepareStatement(
                "SELECT current_points,after_points,change_points FROM ums_member_points_detail WHERE member_id=501 AND change_type=2")) {
            try (ResultSet rows=query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(before,rows.getLong(1));
                assertEquals(after,rows.getLong(2));
                assertEquals(deducted,rows.getLong(3));
                org.junit.jupiter.api.Assertions.assertFalse(rows.next(),"One original grant creates only one negative entry");
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
        assertEquals(after,jdbc.queryForObject("SELECT point FROM ums_member WHERE id=501",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail WHERE id=10 AND event_id IS NULL AND reward_business_key='redeem-clawed:10'",Integer.class));
    }

    private void createBaseTable(String table) {
        int start=base.indexOf("CREATE TABLE `"+table+"` (");
        assertTrue(start>=0,"Missing schema table "+table);
        // Same safe line-ending delimiter as the existing B03 asset fixture; no first-semicolon truncation.
        java.util.regex.Matcher end=java.util.regex.Pattern.compile(";\\s*\\n").matcher(base);
        assertTrue(end.find(start),"Missing table terminator "+table);
        jdbc.execute(base.substring(start,end.start()));
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource result=new DriverManagerDataSource();
        result.setDriverClassName("com.mysql.cj.jdbc.Driver");
        result.setUrl(url);
        result.setUsername(MYSQL.user());
        result.setPassword(MYSQL.password());
        return result;
    }
}
