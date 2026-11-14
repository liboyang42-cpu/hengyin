package com.chengyinhub.business.merchantgap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IMerchantOfferService;

/**
 * Real MySQL for migration_20261119_merchant_gap.sql: applied twice to a fresh schema, every new table and unique key
 * present, and the services that write the new tables run on real rows (LAST_INSERT_ID on a pooled connection
 * inside one transaction, duplicate-key replay, the receipt row). Runs only with CHENGYIN_MERCHANT_GAP_MYSQL_IT=1;
 * port from CHENGYIN_MYBATIS_IT_PORT (be-int-mysql is 3330), schema cy_merchant_gap_it (dropped and recreated).
 */
class MerchantGapMySqlIT {
    static final String DB = "cy_merchant_gap_it";
    static final long OWNER = 9002L, STORE = 8001L;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager tx;
    static IMerchantAccessService access;

    static String url(String db) {
        return "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port() + "/" + db
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowMultiQueries=true";
    }

    @BeforeAll
    static void schema() throws Exception {
        assumeTrue("1".equals(System.getenv("CHENGYIN_MERCHANT_GAP_MYSQL_IT")), "set CHENGYIN_MERCHANT_GAP_MYSQL_IT=1");
        try (Connection c = DriverManager.getConnection(url(""), MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB);
            s.execute("CREATE DATABASE " + DB + " CHARACTER SET utf8mb4");
        }
        DriverManagerDataSource source = new DriverManagerDataSource(url(DB), MybatisItDatabase.user(), MybatisItDatabase.password());
        jdbc = new JdbcTemplate(source);
        tx = new DataSourceTransactionManager(source);
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            // The CITY tables the R3 report reads (and the visit column 20261120 adds), then both merchant-gap migrations twice.
            for (String base : List.of("migration_20261113_gaps_a_city_game.sql", "migration_20261114_gaps_a_city_r491.sql",
                    "migration_business_audit_event_20260823.sql")) {
                s.execute(Files.readString(Path.of("sql", base)));
            }
            for (int run = 0; run < 2; run++) {
                s.execute(Files.readString(Path.of("sql/migration_20261119_merchant_gap.sql")));
                s.execute(Files.readString(Path.of("sql/migration_20261120_merchant_authoring_candidate.sql")));
                s.execute(Files.readString(Path.of("sql/migration_20261120_city_value_visit_feedback_at.sql")));
            }
        }
        access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), anyString())).thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
    }

    @Test
    void everyTableAndUniqueKeyExistsAfterTwoRuns() {
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name", String.class, DB);
        assertTrue(tables.stream().map(String::toLowerCase).toList().containsAll(List.of("merchant_authoring_candidate", "merchant_authoring_source",
                "merchant_city_value_fact", "merchant_city_value_report", "merchant_city_value_snapshot",
                "merchant_npc_avatar_job", "merchant_npc_knowledge_review", "merchant_offer_withdrawal_request",
                "w05_authoring_approval", "w05_authoring_draft", "w05_authoring_job", "w05_authoring_release", "w05_authoring_revision",
                "w05_environment_snapshot")), tables.toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'city_value_visit' AND column_name = 'feedback_at'",
                Integer.class, DB));
        Set<String> unique = Set.copyOf(jdbc.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema = ? AND non_unique = 0 AND index_name <> 'PRIMARY'", String.class, DB));
        assertTrue(unique.containsAll(Set.of("uk_w05_job_request", "uk_w05_revision", "uk_w05_approval", "uk_w05_release_request",
                "uk_authoring_source_candidate", "uk_authoring_source_template", "uk_npc_knowledge_request",
                "uk_offer_withdrawal_request", "uk_city_value_report_request")), unique.toString());
    }

    @Test
    void knowledgeReviewAndAvatarJobOnRealRows() {
        IAuditTaskService audit = mock(IAuditTaskService.class);
        when(audit.submit(anyString(), any(), any(), anyString(), anyString())).thenReturn(501L);
        NpcProfileMapper profiles = mock(NpcProfileMapper.class);
        NpcProfile npc = new NpcProfile();
        npc.setId(1L);
        npc.setScopeId(STORE);
        when(profiles.selectMerchantNpcList(STORE, null)).thenReturn(List.of(npc));
        MerchantNpcGapService service = new MerchantNpcGapService(access, profiles, audit, jdbc, tx, new E2eTestMerchantAvatarProvider());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "11111111-2222-4333-8444-555555555555");
        body.put("name", "阿猫");
        body.put("greeting", "欢迎");
        body.put("persona", "热情 😀");
        body.put("knowledge", "营业到九点");
        Map<String, Object> first = service.submitKnowledge(OWNER, body);
        assertEquals(501L, first.get("auditTaskId"));
        assertEquals("热情 😀", service.knowledgeRequest(OWNER, "11111111-2222-4333-8444-555555555555").get("persona"));
        assertEquals(first, service.submitKnowledge(OWNER, body));
        body.put("persona", "冷淡");
        assertEquals(409, assertThrows(MerchantGapException.class, () -> service.submitKnowledge(OWNER, body)).getStatus());

        Map<String, Object> job = service.avatarGenerate(OWNER, Map.of("imageUrl", "https://img.example.test/a.png", "style", "CARTOON"));
        assertEquals("SUCCEEDED", job.get("status"));
        assertEquals(job.get("jobId"), ((Map<?, ?>) service.avatarStatus(OWNER).get("job")).get("jobId"));
    }

    @Test
    void offerWithdrawalReceiptOnRealRows() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS merchant_chapter_offer (id BIGINT PRIMARY KEY, topic_id BIGINT, chapter_id BIGINT, merchant_id BIGINT, perk_id BIGINT, terms_mode VARCHAR(16), per_head_fee DECIMAL(10,2), quota_total INT, quota_used INT, valid_end DATETIME, status INT, withdraw_reason VARCHAR(500), withdraw_required TINYINT(1), update_time DATETIME)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS cms_topic (id BIGINT PRIMARY KEY, lifecycle INT, user_status INT, product_type INT, circle_theme_code VARCHAR(32), del_flag INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS cms_topic_chapter (id BIGINT PRIMARY KEY, topic_id BIGINT, required INT)");
        jdbc.update("REPLACE INTO merchant_chapter_offer VALUES (12, 30, 31, 9002, NULL, 'PERK', 12.50, 10, 2, '2026-12-31 00:00:00', 1, NULL, NULL, '2026-11-01 10:00:00')");
        jdbc.update("REPLACE INTO cms_topic VALUES (30, 3, 1, 2, NULL, 0)");
        jdbc.update("REPLACE INTO cms_topic_chapter VALUES (31, 30, 0)");
        IMerchantOfferService offers = mock(IMerchantOfferService.class);
        when(offers.withdrawOwnChapterOffer(eq(12L), eq(OWNER), anyString())).thenAnswer(inv -> {
            jdbc.update("UPDATE merchant_chapter_offer SET status = 0, withdraw_reason = ?, withdraw_required = 0, update_time = NOW() WHERE id = 12", (Object) inv.getArgument(2));
            return false;
        });
        MerchantOfferWithdrawalRequestService service = new MerchantOfferWithdrawalRequestService(access, offers, jdbc, tx);
        Map<String, Object> current = service.current(OWNER, STORE, 12L);
        assertEquals(false, current.get("withdrawRequired") == null ? false : current.get("withdrawRequired"));
        String reason = "装修/暂停";
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("offerId", 12L);
        intent.put("reason", reason);
        intent.put("reasonSHA256", MerchantGapSupport.sha256(reason));
        intent.put("expectedSHA256", current.get("snapshotSHA256"));
        Map<String, Object> body = new LinkedHashMap<>(intent);
        body.put("merchantId", STORE);
        body.put("requestId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        body.put("intentSHA256", MerchantGapSupport.sha256(MerchantGapSupport.appleSortedJson(intent)));
        Map<String, Object> receipt = service.withdraw(OWNER, body);
        assertEquals("WITHDRAWN", receipt.get("outcome"));
        assertEquals(0, receipt.get("requiredAtWrite"));
        assertEquals(receipt, service.result(OWNER, STORE, 12L, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));
        assertEquals(false, service.current(OWNER, STORE, 12L).get("canWithdraw"));
    }

    /** The candidate/source INSERTs of MerchantAuthoringRecordMapper.xml, verbatim, on the real tables (4-byte UTF-8 kept). */
    @Test
    void authoringCandidateAndSourceInsertOnRealRows() throws Exception {
        String xml = Files.readString(Path.of("../chengyinhub-system/src/main/resources/mapper/business/MerchantAuthoringRecordMapper.xml"));
        java.util.function.Function<String, String> insert = id -> {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<insert id=\"" + id + "\"[^>]*>(.*?)</insert>", java.util.regex.Pattern.DOTALL).matcher(xml);
            assertTrue(m.find(), id);
            return m.group(1).trim().replaceAll("#\\{[a-zA-Z]+\\}", "?");
        };
        String hash = "a".repeat(64);
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        jdbc.update(insert.apply("insertCandidate"), OWNER, STORE, OWNER, "{\"shopName\":\"店😀\"}", hash, "{}", hash, "trace-1", "{}", now);
        long candidate = jdbc.queryForObject("SELECT MAX(id) FROM merchant_authoring_candidate", Long.class);
        jdbc.update(insert.apply("insertSource"), candidate, OWNER, STORE, 6101L, "{}", hash, hash, hash, "{}", now);
        assertEquals("{\"shopName\":\"店😀\"}", jdbc.queryForObject("SELECT facts_json FROM merchant_authoring_candidate WHERE id = ?", String.class, candidate));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update(insert.apply("insertSource"), candidate, OWNER, STORE, 6102L, "{}", hash, hash, hash, "{}", now));
    }

    /** The R3 CITY report SQL (goals, visit cost, first choice, redemption link) on the real CITY tables. */
    @Test
    @SuppressWarnings("unchecked")
    void cityValueReportReadsTheRealCityTables() {
        jdbc.update("DELETE FROM city_game_point WHERE board_id = 'itb'");
        jdbc.update("INSERT INTO city_game_point(board_id, point_id, title, latitude, longitude, domain, collected_at, merchant_id, place_id) VALUES ('itb','itp','店',31,121,'MERCHANT',NOW(3),?,71)", STORE);
        jdbc.update("INSERT INTO city_game_property(board_id, property_id, point_id, track_index, role) VALUES ('itb','itg','itp',0,'NEUTRAL'), ('itb','ito','itp',1,'OPERABLE')");
        jdbc.update("INSERT INTO city_game_participation(participation_id, board_id, team_id, member_id, confirmed, create_time) VALUES (UUID(),'itb','team-a',1,1,'2026-11-13 08:00:00'), (UUID(),'itb','team-a',2,1,'2026-11-13 08:00:00')");
        jdbc.update("INSERT INTO city_game_task_attempt(board_id, property_id, team_id, member_id, request_id, create_time) VALUES ('itb','itg','team-a',1,'t1','2026-11-13 09:00:00')");
        jdbc.update("INSERT INTO city_game_service(board_id, service_id, property_id, title, member_points_fee) VALUES ('itb','svc','ito','咖啡',30)");
        jdbc.update("INSERT INTO city_game_request(member_id, domain, request_id, board_id, action, request_hash, http_status, decision, current_version, points_charged, create_time) VALUES (1,'game','r1','itb','ACCEPT_SERVICE_VISIT',?,200,'APPLIED',1,30,'2026-11-13 10:00:00')", "b".repeat(64));
        jdbc.update("INSERT INTO city_game_quote(quote_id, quote_version, board_id, action, target_id, payer_member_id, rule_version, member_points_cost, action_cost, expires_at, consumed_request_id) VALUES (UUID(),1,'itb','ACCEPT_SERVICE_VISIT','svc',1,'v1',30,2,'2026-11-14 00:00:00','r1')");
        jdbc.update("INSERT INTO city_value_visit(visit_id, member_id, request_id, request_hash, board_id, point_id, poi_id, entered_at, feedback_at, closed, feedback_kind, rating) VALUES (UUID(),9004,'v1',?,'itb','itp',71,'2026-11-13 11:00:00','2026-11-13 11:00:45',1,'SERVICE_RATING',90)", "c".repeat(64));
        jdbc.update("INSERT INTO business_audit_event(domain_type, domain_id, owner_member_id, actor_member_id, actor_role_code, action, target_type, target_id, relation_id, after_json, request_id, create_time) VALUES ('CITY_NODE',71,?,?,'MERCHANT_OWNER','CITY_NODE_REDEEM','ROAM_CHECKIN',71,9004,?,'citynode-redeem-71-9004','2026-11-13 11:05:00')",
                OWNER, OWNER, "{\"status\":1,\"receiptVersion\":1,\"data\":{\"memberId\":9004,\"poiId\":71,\"couponId\":88,\"couponGranted\":true}}");
        com.chengyinhub.business.mapper.CityGameMapper mapper = mock(com.chengyinhub.business.mapper.CityGameMapper.class);
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("regionId", "itr"); board.put("boardId", "itb"); board.put("seasonId", "its"); board.put("rulesReleaseId", "itrel");
        board.put("lifecycle", "OPEN"); board.put("taskCost", 1);
        when(mapper.selectCurrentBoard("itr")).thenReturn(board);
        com.chengyinhub.business.citygame.MerchantCityValueReportService service = new com.chengyinhub.business.citygame.MerchantCityValueReportService(
                new com.chengyinhub.business.citygame.CityR491Store(mapper, jdbc, tx), access);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("regionId", "itr"); q.put("boardId", "itb"); q.put("seasonId", "its"); q.put("rulesReleaseId", "itrel");
        q.put("from", "2026-11-13T00:00:00Z"); q.put("to", "2026-11-14T00:00:00Z");
        Map<String, String> report = new LinkedHashMap<>(q);
        report.put("requestId", "it-report-1");
        Map<String, Object> out = service.report(OWNER, report);
        Map<String, Map<String, Object>> funnel = (Map<String, Map<String, Object>>) out.get("funnel");
        assertEquals(1, funnel.get("publicGoal").get("count"));
        assertEquals(1, funnel.get("cooperativeGoal").get("count"));
        Map<String, Map<String, Object>> cost = (Map<String, Map<String, Object>>) out.get("visitCost");
        assertEquals(30L, ((Number) cost.get("netPoints").get("sum")).longValue());
        assertEquals(3L, ((Number) cost.get("budget").get("sum")).longValue());
        assertEquals(45_000L, ((Number) ((Map<String, Object>) out.get("timeToFirstEffectiveChoice")).get("medianMillis")).longValue());
        Map<String, String> events = new LinkedHashMap<>(q);
        events.put("cursor", "");
        Map<String, Object> page = service.events(OWNER, events);
        Map<String, Object> success = ((List<Map<String, Object>>) page.get("events")).stream().filter(e -> "SERVICE_SUCCESS".equals(e.get("kind"))).findFirst().orElseThrow();
        Map<String, String> benefit = new LinkedHashMap<>(q);
        benefit.put("snapshotId", String.valueOf(page.get("snapshotId")));
        Map<String, Object> record = service.benefit(OWNER, (Long) success.get("id"), benefit);
        assertEquals("VERIFIED", record.get("evidenceStatus"));
        assertEquals(88L, record.get("couponId"));
    }
}
