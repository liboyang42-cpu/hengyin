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
        String sql = Files.readString(Path.of("sql/migration_20261119_merchant_gap.sql"));
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
            s.execute(sql);
        }
        access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), anyString())).thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
    }

    @Test
    void everyTableAndUniqueKeyExistsAfterTwoRuns() {
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name", String.class, DB);
        assertEquals(List.of("merchant_authoring_source", "merchant_city_value_fact", "merchant_city_value_report", "merchant_city_value_snapshot",
                "merchant_npc_avatar_job", "merchant_npc_knowledge_review", "merchant_offer_withdrawal_request",
                "w05_authoring_approval", "w05_authoring_draft", "w05_authoring_job", "w05_authoring_release", "w05_authoring_revision",
                "w05_environment_snapshot"), tables.stream().map(String::toLowerCase).toList());
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
}
