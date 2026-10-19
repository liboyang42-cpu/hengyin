package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production mapper pagination, with no payout or provider invocation. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_REFUND_PAGING_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundReconcilePagingMySqlIT {
    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private RefundApplicationMapper mapper;
    private boolean created;

    @BeforeAll
    void setup() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "refund_paging");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        mysql = MysqlIntegrationTestConfig.from(env, "it");
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        admin.execute("CREATE DATABASE " + mysql.databaseName());
        created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        Path schema = Paths.get("../sql/base_schema_20260818.sql");
        if (!Files.exists(schema)) schema = Paths.get("sql/base_schema_20260818.sql");
        String base = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);
        int start = base.indexOf("CREATE TABLE `refund_application` (");
        db.execute(base.substring(start, base.indexOf("\n/*!40101 SET", start)));
        db.execute("ALTER TABLE refund_application ADD COLUMN platform_takeover_at DATETIME NULL");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/RefundApplicationMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(RefundApplicationMapper.class);
    }

    @AfterAll
    void cleanup() {
        if (created) admin.execute("DROP DATABASE " + mysql.databaseName());
    }

    @Test
    void stableBoundedScanReachesHealthyTailWithoutEligibilityDriftOrNewArrivalLeap() {
        for (long id = 1; id <= 201; id++) {
            db.update("insert into refund_application(id,source_type,source_id,status,payout_status,del_flag,create_time,update_time)"
                    + " values(?,'registration',?,1,0,0,date_sub(now(),interval 1 hour),date_sub(now(),interval 1 hour))", id, id);
        }
        Long bound = mapper.selectPayoutScanUpperId();
        assertEquals(Long.valueOf(201), bound);
        List<RefundApplication> first = mapper.selectStalePayoutProcessingPage(30, 0, bound);
        assertEquals(200, first.size());
        assertEquals(Long.valueOf(1), first.get(0).getId());
        assertEquals(Long.valueOf(200), first.get(199).getId());
        db.update("insert into refund_application(id,source_type,source_id,status,payout_status,del_flag,create_time,update_time)"
                + " values(202,'registration',202,1,0,0,date_sub(now(),interval 1 hour),date_sub(now(),interval 1 hour))");
        db.update("delete from refund_application where id=200");
        List<RefundApplication> tail = mapper.selectStalePayoutProcessingPage(30, 200, bound);
        assertEquals(1, tail.size());
        assertEquals(Long.valueOf(201), tail.get(0).getId());
        assertEquals(Long.valueOf(202), mapper.selectPayoutScanUpperId());
        db.update("update refund_application set payout_status=5,status=0 where id=201");
        assertTrue(mapper.selectStalePayoutProcessingPage(30, 200, bound).isEmpty());
        db.update("update refund_application set status=1,update_time=now() where id=201");
        assertTrue(mapper.selectStalePayoutProcessingPage(30, 200, bound).isEmpty());
        db.update("update refund_application set update_time=date_sub(now(),interval 1 hour) where id=201");
        assertEquals(1, mapper.selectStalePayoutProcessingPage(30, 200, bound).size());
        db.update("update refund_application set del_flag=1 where id=201");
        assertTrue(mapper.selectStalePayoutProcessingPage(30, 200, bound).isEmpty());
    }
}
