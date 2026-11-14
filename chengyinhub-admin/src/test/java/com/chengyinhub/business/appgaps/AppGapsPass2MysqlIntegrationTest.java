package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.FreeExploreBundleSwitches;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gaps A pass 2 player routes on real MySQL 8 with baseline DDL and the real pass 2 migrations: personal location
 * share (privacy and retention rules), bundle extras (reads, statements, refused money writes), the author-only
 * cover pipeline and the deregistration result token.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_GAPS_A_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppGapsPass2MysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("gaps_pass2_it");
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private final AtomicLong clock = new AtomicLong();
    private Path coverDir;

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        jdbc = new JdbcTemplate(dataSource);
        tx = new DataSourceTransactionManager(dataSource);
        coverDir = Files.createTempDirectory("cover-it");
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES")) s.execute("DROP TABLE `" + t.values().iterator().next() + "`");
        }
        String baseline = Files.readString(Paths.get("../sql/base_schema_20260818.sql"));
        for (String name : new String[]{"ums_member", "ums_member_follow", "cms_topic", "cms_topic_chapter", "cms_registration", "mms_merchant", "redemption"}) {
            execute(table(baseline, name));
        }
        execute("ALTER TABLE cms_topic ADD COLUMN config_version BIGINT NOT NULL DEFAULT 1");
        migrate("sql/migration_doc2_f3_bundle_ledger_20261022.sql");
        migrate("sql/migration_doc2_f3_bundle_extras_20261114.sql");
        migrate("sql/migration_20261114_gaps_a_player.sql");
        clock.set(java.sql.Timestamp.valueOf("2026-11-14 10:00:00").getTime());
    }

    // ---- location share ----

    @Test
    void locationShareNeedsMutualFollowStoresOnlyTheLatestSnappedFixAndDeletesItOnRevoke() {
        LocationShareService share = new LocationShareService(jdbc, tx, clock::get);
        execute0("INSERT INTO ums_member(id, del_flag) VALUES (1,0),(2,0),(3,0)");
        execute0("INSERT INTO ums_member_follow(member_id, follow_member_id) VALUES (1,2),(2,1),(1,3)");
        assertEquals("POLICY_NOT_PUBLISHED", share.context(1L, "2").get("unavailableReason"));
        execute0("INSERT INTO location_share_policy(policy_id, version, approval_ref, mode_choices, duration_choices, refresh_after_ms, stale_after_ms,"
                + " display_lease_ms, owner_lease_ms, status) VALUES ('pls',1,'review-1','[{\"id\":\"exact\",\"mode\":\"EXACT\",\"datum\":\"WGS84\"},"
                + "{\"id\":\"coarse\",\"mode\":\"COARSE\",\"datum\":\"WGS84\",\"latitudeStep\":0.01,\"longitudeStep\":0.01}]',"
                + "'[{\"id\":\"h1\",\"seconds\":3600}]',5000,30000,60000,60000,'PUBLISHED')");
        assertEquals("NOT_MUTUAL_FOLLOW", share.context(1L, "3").get("unavailableReason"));
        Map<String, Object> context = share.context(1L, "2");
        assertEquals(true, context.get("eligible"));
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) context.get("policy");
        String key = UUID.randomUUID().toString();
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("requestKey", key); create.put("recipientId", 2L); create.put("policyId", "pls"); create.put("policyVersion", 1L);
        create.put("policyDigest", policy.get("digest")); create.put("modeChoiceId", "coarse"); create.put("durationChoiceId", "h1");
        create.put("foregroundOnly", true);
        create.put("acknowledgedDisclosures", List.of("audience", "precision", "duration", "foregroundOnly", "stopAnytime"));
        assertEquals("INCOMPLETE_CONSENT", assertThrows(AppGapException.class, () -> share.create(1L, create)).getErrorCode());
        create.put("acknowledgedDisclosures", List.of("audience", "precision", "duration", "foregroundOnly", "stopAnytime", "alreadySeenCannotBeRecalled"));
        Map<String, Object> receipt = share.create(1L, create);
        assertEquals("ACTIVE", receipt.get("state"));
        assertEquals(receipt.get("grant"), share.create(1L, create).get("grant"), "same request key replays");
        @SuppressWarnings("unchecked")
        String grantId = (String) ((Map<String, Object>) receipt.get("grant")).get("grantId");
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("sequence", 1L); fix.put("observedAtMs", clock.get()); fix.put("datum", "WGS84"); fix.put("latitude", 31.23456); fix.put("longitude", 121.47891);
        fix.put("foreground", true);
        share.publish(1L, grantId, fix);
        assertEquals(0, new java.math.BigDecimal("31.23").compareTo(jdbc.queryForObject("SELECT latitude FROM location_share_observation", java.math.BigDecimal.class)),
                "coarse grants keep only the grid cell");
        assertEquals("SEQUENCE_STALE", assertThrows(AppGapException.class, () -> share.publish(1L, grantId, fix)).getErrorCode());
        assertEquals(404, assertThrows(AppGapException.class, () -> share.publish(2L, grantId, fix)).getStatus());
        Map<String, Object> received = share.received(2L);
        assertEquals(1, ((List<?>) received.get("entries")).size());
        assertTrue(((List<?>) share.received(1L).get("entries")).isEmpty(), "the owner is not a recipient");
        share.revoke(1L, key);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM location_share_observation", Long.class), "ending a grant deletes the stored location");
        Map<String, Object> events = share.events(2L, "0");
        assertEquals(3, ((List<?>) events.get("events")).size());
        assertEquals(true, share.events(2L, "999").get("resetRequired"));
    }

    // ---- bundle extras ----

    @Test
    void bundleReadsAreHolderKeyedStatementsReplayAndMoneyWritesRefuse() {
        BundleExtrasService bundles = new BundleExtrasService(jdbc, new FreeExploreBundleSwitches(true, false, false), clock::get);
        execute0("INSERT INTO cms_topic(id, name, product_type, lifecycle, del_flag, img_url, config_version) VALUES (101,'外滩自由探索',2,3,0,'c.jpg',1),(102,'下架',2,6,0,NULL,1)");
        execute0("INSERT INTO cms_topic_chapter(id, topic_id, name, required) VALUES (11,101,'第一章',1),(12,101,'第二章',1)");
        execute0("INSERT INTO cms_registration(id, registration_no, owner_id, ticket_id, ticket_price, member_id, original_amount, total_amount, payable_amount,"
                + " pay_expire_time, payment_status, registration_status, verification_code) VALUES"
                + " (9001,'R9001',55,1,199,1,199,199,199,'2026-11-01',2,2,'VC-9001'),(9002,'R9002',55,1,199,2,199,199,199,'2026-11-01',2,2,'VC-9002')");
        execute0("INSERT INTO explore_bundle_order(id, registration_id, holder_member_id, topic_id, product_version, quota_version, policy_version, contract_version,"
                + " settlement_rule_version, snapshot_json, paid_amount, valid_from, valid_to, idempotency_key, request_fingerprint, create_time)"
                + " VALUES (1,9001,1,101,'" + "p".repeat(64) + "','" + "q".repeat(64) + "','pv1','" + "c".repeat(64) + "','sr1','{}',199.00,"
                + " '2026-11-01','2026-12-31','bundle-order:9001','" + "f".repeat(64) + "','2026-11-01')");
        execute0("INSERT INTO explore_bundle_grant(id, bundle_order_id, registration_id, holder_member_id, topic_id, chapter_id, grant_no, grant_type, units_total,"
                + " valid_from, valid_to, create_time) VALUES (1,1,9001,1,101,11,1,'PURCHASE',1,'2026-11-01','2026-12-31','2026-11-01'),"
                + " (2,1,9001,1,101,12,1,'PURCHASE',1,'2026-11-01','2026-12-31','2026-11-01')");
        execute0("INSERT INTO explore_bundle_unit(id, grant_id, registration_id, holder_member_id, chapter_id, unit_seq, unit_code, unit_value, status, create_time)"
                + " VALUES (21,1,9001,1,11,1,'FXAAA',99.50,'AVAILABLE','2026-11-01'),(22,2,9001,1,12,1,'FXBBB',99.50,'AVAILABLE','2026-11-01')");

        Map<String, Object> shelf = bundles.shelf(null, null);
        assertEquals(1, ((List<?>) shelf.get("items")).size(), "only on-sale free-explore topics");
        Map<String, Object> wallet = bundles.wallet(1L, null, null);
        assertEquals(1, ((List<?>) wallet.get("orders")).size());
        assertTrue(((List<?>) bundles.wallet(2L, null, null).get("orders")).isEmpty());
        Map<String, Object> voucher = bundles.voucher(1L, "9001");
        assertEquals("VC-9001", voucher.get("mainCode"));
        assertEquals(2, ((List<?>) voucher.get("subs")).size());
        assertEquals("BUNDLE_NOT_FOUND", assertThrows(FreeExploreBundleException.class, () -> bundles.voucher(2L, "9001")).getBusinessCode());
        String v1 = (String) bundles.voucherVersion(1L, "9001").get("voucherVersion");
        execute0("UPDATE explore_bundle_unit SET status = 'RESERVED', version = version + 1 WHERE id = 21");
        assertNotEquals(v1, bundles.voucherVersion(1L, "9001").get("voucherVersion"), "a unit transition changes the version");
        assertEquals("NONE", bundles.redemptionResult(1L, "9001", "21").get("outcome"));

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("registrationId", "9001"); review.put("rating", 5); review.put("content", "很好"); review.put("idempotencyKey", "rv-1");
        assertEquals("BUNDLE_REVIEW_NOT_ELIGIBLE", assertThrows(FreeExploreBundleException.class, () -> bundles.submitReview(1L, review)).getBusinessCode());
        execute0("INSERT INTO redemption(id, registration_id, chapter_id, topic_id, merchant_id, operator_id, status, idempotency_key, create_time) VALUES (77,9001,12,101,500,500,'ACTIVE','redeem:9001:12:500','2026-11-10')");
        execute0("UPDATE explore_bundle_unit SET status = 'REDEEMED', redemption_id = 77 WHERE id = 22");
        assertEquals("SUCCESS", bundles.redemptionResult(1L, "9001", "22").get("outcome"));
        Map<String, Object> saved = bundles.submitReview(1L, review);
        assertEquals("PENDING", saved.get("status"));
        assertEquals(saved, bundles.submitReview(1L, review), "replay");
        Map<String, Object> changed = new LinkedHashMap<>(review);
        changed.put("rating", 1);
        assertEquals("BUNDLE_IDEMPOTENCY_CONFLICT", assertThrows(FreeExploreBundleException.class, () -> bundles.submitReview(1L, changed)).getBusinessCode());
        assertTrue(((List<?>) bundles.reviewList("101", null, null).get("items")).isEmpty(), "pending reviews are not listed");

        Map<String, Object> renewal = new LinkedHashMap<>();
        renewal.put("registrationId", "9001"); renewal.put("intent", "MAYBE"); renewal.put("note", "看看"); renewal.put("idempotencyKey", "rn-1");
        bundles.renewalIntent(1L, renewal);
        Map<String, Object> second = new LinkedHashMap<>(renewal);
        second.put("intent", "WILL_RENEW"); second.put("idempotencyKey", "rn-2");
        Map<String, Object> latest = bundles.renewalIntent(1L, second);
        assertNotNull(latest.get("supersedesId"));
        assertEquals("WILL_RENEW", bundles.latestRenewal(1L, "9001").get("intent"));

        Map<String, Object> appeal = new LinkedHashMap<>();
        appeal.put("registrationId", "9001"); appeal.put("unitId", "21"); appeal.put("reasonCode", "MERCHANT_FORGOT_SCAN"); appeal.put("note", "店员忘扫");
        appeal.put("evidenceRefs", List.of("m-1")); appeal.put("idempotencyKey", "ap-1");
        assertEquals("OPEN", bundles.appeal(1L, appeal).get("status"));
        Map<String, Object> dup = new LinkedHashMap<>(appeal);
        dup.put("idempotencyKey", "ap-2");
        assertEquals("BUNDLE_APPEAL_DUPLICATE", assertThrows(FreeExploreBundleException.class, () -> bundles.appeal(1L, dup)).getBusinessCode());
        assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM explore_bundle_unit WHERE id = 21", String.class), "an appeal never consumes the unit");
        assertEquals("FOUND", bundles.appealReceipt(1L, "ap-1").get("receipt"));

        assertEquals("NONE", bundles.refundProgress(1L, "9001").get("state"));
        assertEquals(false, bundles.transferPreview(1L, "9001").get("eligible"));
        assertEquals("BUNDLE_MONEY_WRITE_DISABLED", BundleExtrasService.moneyWriteDisabled().getBusinessCode());
    }

    // ---- topic cover ----

    @Test
    void coverIsAuthorOnlySanitisedAndSelectedByCompareAndSet() throws Exception {
        TopicCoverService covers = new TopicCoverService(jdbc, tx, coverDir.toString(), topicId -> false);
        execute0("INSERT INTO cms_topic(id, name, member_id, del_flag, config_version) VALUES (301,'我的主题',1,0,4)");
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        Map<String, Object> asset = covers.upload(1L, png.toByteArray());
        String hash = (String) asset.get("contentHash");
        byte[] bytes = covers.content(1L, (String) asset.get("assetId"), (String) asset.get("sourceVersion"), hash);
        assertEquals((byte) 0xFF, bytes[0], "re-encoded as JPEG");
        assertEquals(404, assertThrows(AppGapException.class, () -> covers.content(2L, (String) asset.get("assetId"), (String) asset.get("sourceVersion"), hash)).getStatus());
        assertEquals(400, assertThrows(AppGapException.class, () -> covers.upload(1L, "not an image".getBytes())).getStatus());
        Map<String, Object> current = covers.current(1L, "301");
        assertEquals("NONE", current.get("selectionState"));
        assertEquals(403, assertThrows(AppGapException.class, () -> covers.current(2L, "301")).getStatus());
        Map<String, Object> select = new LinkedHashMap<>();
        select.put("topicId", 301); select.put("expectedConfigVersion", 4); select.put("expectedSelectionVersion", 0);
        select.put("expectedContentSlotId", current.get("contentSlotId")); select.put("requestId", UUID.randomUUID().toString().toUpperCase());
        select.put("assetId", asset.get("assetId")); select.put("sourceVersion", asset.get("sourceVersion")); select.put("contentHash", hash);
        Map<String, Object> receipt = covers.select(1L, select);
        assertEquals(5L, ((Number) receipt.get("topicConfigVersion")).longValue());
        assertEquals(receipt, covers.select(1L, select), "replay, not a second advance");
        assertEquals(5L, jdbc.queryForObject("SELECT config_version FROM cms_topic WHERE id = 301", Long.class));
        assertEquals(receipt, covers.status(1L, select));
        Map<String, Object> stale = new LinkedHashMap<>(select);
        stale.put("requestId", UUID.randomUUID().toString().toUpperCase());
        assertEquals("COVER_VERSION_CONFLICT", assertThrows(AppGapException.class, () -> covers.select(1L, stale)).getErrorCode());
        Map<String, Object> after = covers.current(1L, "301");
        assertEquals("CURRENT", after.get("selectionState"));
        assertEquals("AVAILABLE", after.get("assetAvailability"));
    }

    // ---- deregistration result ----

    @Test
    void deregistrationResultTokenReadsOnlyItsOwnMember() {
        IAccountDeregistrationService status = (IAccountDeregistrationService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{IAccountDeregistrationService.class}, (proxy, method, args) -> {
                    DeregistrationStatusVO vo = new DeregistrationStatusVO();
                    vo.setStatus(Long.valueOf(1L).equals(args[0]) ? "PENDING" : "NORMAL");
                    return vo;
                });
        DeregistrationResultService results = new DeregistrationResultService(jdbc, status, clock::get);
        String token = results.issue(1L, "req-1");
        assertEquals("PENDING", results.result(null, token).get("status"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM account_deregistration_result_token WHERE token_hash = ?", Long.class, AppGapSupport.sha256(token)));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM account_deregistration_result_token WHERE token_hash = ?", Long.class, token), "plaintext never stored");
        assertEquals(404, assertThrows(AppGapException.class, () -> results.result(2L, token)).getStatus());
        clock.addAndGet(DeregistrationResultService.TOKEN_TTL_MS + 1);
        assertEquals(404, assertThrows(AppGapException.class, () -> results.result(null, token)).getStatus());
    }

    private static String table(String baseline, String name) {
        Matcher m = Pattern.compile("CREATE TABLE `" + name + "` \\([\\s\\S]*?\\) ENGINE=InnoDB[^\\n]*").matcher(baseline);
        assertTrue(m.find(), "baseline has " + name);
        return m.group();
    }

    private void migrate(String path) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : Files.readString(Paths.get(path)).split(";\\s*\\n")) {
                String sql = statement.replaceAll("(?m)^--.*$", "").trim();
                if (!sql.isEmpty()) s.execute(sql);
            }
        }
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) s.execute(statement);
        }
    }

    private void execute0(String sql) { jdbc.execute(sql); }

    private DriverManagerDataSource ds(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
