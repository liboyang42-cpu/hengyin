package com.chengyinhub.business.workshop;

import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.OpReceipt;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Version;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.WorkshopService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The two new migrations on real MySQL 8: 20261117_reward_pledge (w17_* tables, op receipts) and
 * 20261117_workshop_professional (operations + review events), each applied twice (rerunnable). Exercises the keys the
 * services rely on, the reward mapper statements MySQL-only syntax touches, and the workshop review flow end to end.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_R2_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RewardsWorkshopMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("rewards_workshop_it");
    private static final long OWNER = 9004L;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private RewardPledgeMapper rewards;

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("it", new SpringManagedTransactionFactory(), dataSource));
        Path mapper = root().getParent().resolve("chengyinhub-system/src/main/resources/mapper/business/rewardpledge/RewardPledgeMapper.xml");
        try (ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(mapper))) {
            new XMLMapperBuilder(in, config, mapper.toString(), config.getSqlFragments()).parse();
        }
        rewards = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(RewardPledgeMapper.class);
    }

    @BeforeEach
    void reset() throws Exception {
        for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES")) jdbc.execute("DROP TABLE `" + t.values().iterator().next() + "`");
        for (int i = 0; i < 2; i++) {
            migrate("sql/migration_20261117_reward_pledge.sql");
            migrate("sql/migration_20261117_workshop_professional.sql");
        }
        jdbc.execute("CREATE TABLE merchant_chapter_offer (id BIGINT AUTO_INCREMENT PRIMARY KEY, topic_id BIGINT NOT NULL, chapter_id BIGINT NOT NULL,"
                + " merchant_id BIGINT NOT NULL, status TINYINT NOT NULL DEFAULT 1, valid_end DATETIME NULL)");
        jdbc.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, name VARCHAR(128), product_type INT, config_version BIGINT, member_id BIGINT, del_flag INT)");
        jdbc.execute("CREATE TABLE workshop_order (id BIGINT PRIMARY KEY, buyer_id BIGINT, paid_at DATETIME, state VARCHAR(16), license_status VARCHAR(16))");
    }

    @Test
    void rewardTablesKeepOneReceiptPerStoreOpKeyAndOneAwardPerBusinessKey() {
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'w17\\_%'", Integer.class));
        OpReceipt receipt = new OpReceipt();
        receipt.storeId = 7;
        receipt.op = "draft";
        receipt.opKey = "pledge-1";
        receipt.payloadHash = "a".repeat(64);
        receipt.state = "APPLIED";
        receipt.pledgeId = "pledge-1";
        receipt.actorMemberId = 50;
        receipt.createdAt = 1L;
        assertEquals(1, rewards.insertOpReceipt(receipt));
        assertThrows(DuplicateKeyException.class, () -> rewards.insertOpReceipt(receipt));
        receipt.storeId = 8;
        assertEquals(1, rewards.insertOpReceipt(receipt), "the same key in another store is its own receipt");
        assertEquals("APPLIED", rewards.findOpReceipt(7, "draft", "pledge-1").state);
        assertEquals(0, rewards.deleteRejectedOpReceipt(7, "draft", "pledge-1"), "an APPLIED receipt is never replaced");
        jdbc.update("INSERT INTO w17_reward_award(award_id, version_id, rule_version, beneficiary_id, reward_scope, fact_ref, state, request_id, created_at)"
                + " VALUES ('a1', 'v1', 'r', 11, 'PLEDGE:v1', 'THEME:100:session:1', 'AWARDED', 'q1', 1)");
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO w17_reward_award(award_id, version_id, rule_version, beneficiary_id,"
                + " reward_scope, fact_ref, state, request_id, created_at) VALUES ('a2', 'v1', 'r', 11, 'PLEDGE:v1', 'THEME:100:session:1', 'AWARDED', 'q2', 1)"));
        jdbc.update("UPDATE w17_reward_award SET history_id = 5 WHERE award_id = 'a1'");
        assertEquals(1, rewards.pageAwards(11, null, 10).size());
        assertEquals(0, rewards.pageAwards(11, "a1", 10).size());
        assertEquals("a1", rewards.findAwardByHistory(5).awardId);
    }

    @Test
    void subjectVersionsNeedAnActiveChapterOfferOnTheTopic() {
        jdbc.update("INSERT INTO w17_reward_pledge(pledge_id, store_id, merchant_member_id, coupon_id, supply_ref, quantity, service_start, service_end,"
                + " redeem_start, redeem_end, terms, minor_restricted, state, created_at) VALUES ('p1', 7, 50, 1, 's', 2, 1, 2, 1, 2, 't', 0, 'FROZEN', 1)");
        jdbc.update("INSERT INTO w17_reward_pledge_version(version_id, pledge_id, version_no, payload_hash, quantity, service_start, service_end,"
                + " redeem_start, redeem_end, terms, minor_restricted, rule_version, state, frozen_at) VALUES ('v1', 'p1', 1, 'h', 2, 1, 2, 1, 2, 't', 0, 'r', 'FROZEN', 5)");
        assertTrue(rewards.subjectVersions(100).isEmpty());
        jdbc.update("INSERT INTO merchant_chapter_offer(topic_id, chapter_id, merchant_id, status, valid_end) VALUES (100, 1, 50, 1, '2000-01-01 00:00:00')");
        assertTrue(rewards.subjectVersions(100).isEmpty(), "an expired offer does not count");
        jdbc.update("INSERT INTO merchant_chapter_offer(topic_id, chapter_id, merchant_id, status) VALUES (100, 2, 50, 1)");
        List<Version> versions = rewards.subjectVersions(100);
        assertEquals(1, versions.size());
        assertEquals("v1", versions.get(0).versionId);
        assertEquals(1, rewards.subjectOffered(50, 100));
        assertEquals(0, rewards.subjectOffered(51, 100));
        assertEquals(1, rewards.listStorePledges(7).size());
        assertEquals(1, rewards.listVersions("p1").size());
    }

    @Test
    void workshopReviewRunsOnMysqlAndTheTablesGuardStatusAndRequestId() throws Exception {
        jdbc.update("INSERT INTO cms_topic VALUES (990, 'Old town', 1, 3, ?, 0)", OWNER);
        jdbc.update("INSERT INTO workshop_order VALUES (5, ?, NOW(), 'PAID', 'ACTIVE')", OWNER);
        WorkshopService workshop = mock(WorkshopService.class);
        CmsTemplatePublishRequest snapshot = new CmsTemplatePublishRequest();
        snapshot.setTitle("Licensed text");
        when(workshop.professionalSource(eq(OWNER), eq(5L), anyString())).thenAnswer(i -> new WorkshopService.ProfessionalSource("WKO_x",
                WorkshopProfessionalServiceTest.VERSION, 301L, WorkshopProfessionalServiceTest.CONTENT, WorkshopProfessionalServiceTest.TERMS,
                77L, WorkshopProfessionalServiceTest.REVISION, WorkshopProfessionalServiceTest.PAYLOAD, snapshot));
        when(workshop.createGuardedTemplate(any(), eq(OWNER), any())).thenReturn(4242L);
        WorkshopProfessionalService manual = new WorkshopProfessionalService(jdbc, new DataSourceTransactionManager(dataSource), workshop,
                System::currentTimeMillis, false);
        String fingerprint = (String) manual.target(OWNER, "{\"licenseId\":\"w18-paid-5\",\"topicId\":990}").get("ownerModeFingerprint");
        String raw = command(UUID.randomUUID().toString(), fingerprint);
        assertEquals("PREPARED", manual.submit(OWNER, null, raw).get("state"));
        long id = jdbc.queryForObject("SELECT id FROM workshop_professional_operation", Long.class);
        Map<String, Object> approved = manual.approve(1L, id, "ok");
        assertEquals("CREATED", approved.get("state"));
        assertEquals(4242L, ((Number) ((Map<?, ?>) approved.get("creation")).get("templateId")).longValue());
        assertTrue(((String) ((Map<?, ?>) approved.get("creation")).get("createdAt")).endsWith("Z"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM workshop_professional_event WHERE operation_id = ?", Integer.class, id));
        assertEquals(1, ((List<?>) manual.history(OWNER, "{\"licenseId\":\"w18-paid-5\"}").get("items")).size());
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE workshop_professional_operation SET review_status = 'DONE' WHERE id = ?", id));
        assertThrows(DuplicateKeyException.class, () -> jdbc.update("INSERT INTO workshop_professional_operation(request_id, operation_key, owner_member_id,"
                + " license_id, command_json, command_hash, review_status, target_topic_id, owned_draft_id, confirmed_mode, purchased_version_id, create_time,"
                + " update_time) SELECT request_id, REPEAT('f', 64), owner_member_id, license_id, command_json, command_hash, 'PENDING', target_topic_id,"
                + " owned_draft_id, confirmed_mode, purchased_version_id, NOW(3), NOW(3) FROM workshop_professional_operation WHERE id = " + id));
        WorkshopProfessionalService auto = new WorkshopProfessionalService(jdbc, new DataSourceTransactionManager(dataSource), workshop,
                System::currentTimeMillis, true);
        assertEquals("CREATED", auto.submit(OWNER, null, command(UUID.randomUUID().toString(), fingerprint)).get("state"));
    }

    private static String command(String requestId, String fingerprint) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", WorkshopProfessionalService.COMMAND_SCHEMA);
        m.put("requestId", requestId);
        m.put("installationRequestId", WorkshopProfessionalServiceTest.INSTALL);
        m.put("licenseId", "w18-paid-5");
        m.put("moduleId", "301");
        m.put("purchasedVersionId", WorkshopProfessionalServiceTest.VERSION);
        m.put("contentHash", WorkshopProfessionalServiceTest.CONTENT);
        m.put("termsHash", WorkshopProfessionalServiceTest.TERMS);
        m.put("componentHash", WorkshopProfessionalServiceTest.COMPONENT);
        m.put("ownedDraftId", 77L);
        m.put("currentDraftRevision", WorkshopProfessionalServiceTest.REVISION);
        m.put("currentDraftPayloadHash", WorkshopProfessionalServiceTest.PAYLOAD);
        m.put("targetTopicId", 990L);
        m.put("targetOwnerModeFingerprint", fingerprint);
        m.put("confirmedMode", "CITY_ORIENTATION");
        return new ObjectMapper().writeValueAsString(m);
    }

    private void migrate(String path) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : Files.readString(root().resolve(path)).split(";\\s*\\n")) {
                String sql = statement.replaceAll("(?m)^--.*$", "").trim();
                if (!sql.isEmpty()) s.execute(sql);
            }
        }
    }

    private static Path root() {
        Path here = Paths.get("").toAbsolutePath();
        return Files.isDirectory(here.resolve("sql")) ? here : here.resolve("chengyinhub-admin");
    }

    private DriverManagerDataSource ds(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
