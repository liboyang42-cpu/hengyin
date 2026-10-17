package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.VerificationReview;
import com.chengyinhub.business.mapper.VerificationReviewMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual queue DDL and mapper: only pending reviews count, duplicate detections stay one row. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerificationRiskQueueMySqlIT {
    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private VerificationReviewMapper mapper;

    @BeforeAll
    void createScratchQueue() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("risk_queue");
        if (!config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:"))
            throw new IllegalStateException("loopback task MySQL only");
        database = "chengyin_risk_queue_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        String migration = Files.readString(Path.of("sql/migration_commerce_order_fields_and_verification_review_20260805.sql"));
        int start = migration.indexOf("CREATE TABLE verification_review (");
        assertTrue(start >= 0);
        db.execute(migration.substring(start, migration.indexOf(';', start) + 1));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/VerificationReviewMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(VerificationReviewMapper.class);
    }

    @AfterAll
    void dropOnlyScratchDatabase() {
        if (database != null && admin != null) {
            admin.execute("DROP DATABASE " + database);
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        }
    }

    @Test
    void pendingAgeAndCountUseActualQueueAndIgnoreResolvedRows() {
        Map<String, Object> empty = mapper.selectPendingQueueStats();
        assertEquals(0, ((Number) empty.get("pendingCount")).longValue());
        assertEquals(0, ((Number) empty.get("oldestAgeSeconds")).longValue());
        VerificationReview first = review("pending-first");
        assertEquals(1, mapper.insertIgnore(first));
        assertNotNull(first.getId());
        assertEquals(0, mapper.insertIgnore(review("pending-first")));
        assertEquals(1, mapper.insertIgnore(review("pending-second")));
        VerificationReview resolved = review("resolved-old");
        resolved.setStatus(1);
        assertEquals(1, mapper.insertIgnore(resolved));
        db.update("UPDATE verification_review SET create_time=NOW()-INTERVAL 2 DAY WHERE id=?", first.getId());
        db.update("UPDATE verification_review SET create_time=NOW()-INTERVAL 30 DAY WHERE id=?", resolved.getId());
        Map<String, Object> pending = mapper.selectPendingQueueStats();
        assertEquals(2, ((Number) pending.get("pendingCount")).longValue());
        long age = ((Number) pending.get("oldestAgeSeconds")).longValue();
        assertTrue(age >= 172800 && age < 172810);
        db.update("UPDATE verification_review SET status=2 WHERE status=0");
        Map<String, Object> closed = mapper.selectPendingQueueStats();
        assertEquals(0, ((Number) closed.get("pendingCount")).longValue());
        assertEquals(0, ((Number) closed.get("oldestAgeSeconds")).longValue());
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM verification_review", Integer.class));
    }

    private VerificationReview review(String key) {
        VerificationReview review = new VerificationReview();
        review.setVerifierMemberId(11L);
        review.setTopicId(21L);
        review.setRiskType("BURST");
        review.setVerificationRate(BigDecimal.ONE);
        review.setPaidCount(3);
        review.setVerifiedCount(3);
        review.setOneMinuteCount(3);
        review.setStatus(0);
        review.setIdempotencyKey(key);
        return review;
    }
}
