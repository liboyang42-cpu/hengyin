package com.chengyinhub.business.workshop;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.service.impl.WorkshopService;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2 in MySQL mode for the operation tables; the license / install / draft facts come from a mocked WorkshopService. */
class WorkshopProfessionalServiceTest {
    static final long OWNER = 9004L, OTHER = 9005L, LINE = 5L, TOPIC = 990L, DRAFT = 77L, REVISION = 1_700_000_000_000L;
    static final String INSTALL = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", VERSION = "WKV_0123456789abcdef0123456789abcdef";
    static final String CONTENT = "a".repeat(64), TERMS = "b".repeat(64), COMPONENT = "c".repeat(64), PAYLOAD = "d".repeat(64);
    final ObjectMapper json = new ObjectMapper();
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    WorkshopService workshop;
    AtomicLong clock = new AtomicLong(1_763_000_000_000L);

    @BeforeEach void setup() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w18_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE workshop_professional_operation (id BIGINT AUTO_INCREMENT PRIMARY KEY, request_id CHAR(36) NOT NULL UNIQUE,"
                + " operation_key CHAR(64) NOT NULL UNIQUE, owner_member_id BIGINT NOT NULL, license_id VARCHAR(40) NOT NULL, command_json TEXT NOT NULL,"
                + " command_hash CHAR(64) NOT NULL, review_status VARCHAR(16) NOT NULL, rejection_code VARCHAR(80), target_topic_id BIGINT NOT NULL,"
                + " owned_draft_id BIGINT NOT NULL, confirmed_mode VARCHAR(24) NOT NULL, purchased_version_id VARCHAR(128) NOT NULL, template_id BIGINT,"
                + " template_content_hash CHAR(64), missing_inputs_json VARCHAR(255), committed_time TIMESTAMP(3), row_version INT NOT NULL DEFAULT 0,"
                + " create_time TIMESTAMP(3) NOT NULL, update_time TIMESTAMP(3) NOT NULL)");
        jdbc.execute("CREATE TABLE workshop_professional_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, operation_id BIGINT NOT NULL, from_status VARCHAR(16),"
                + " to_status VARCHAR(16) NOT NULL, actor_type VARCHAR(8) NOT NULL, actor_id BIGINT, reason VARCHAR(500), create_time TIMESTAMP(3) NOT NULL)");
        jdbc.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, name VARCHAR(128), product_type INT, config_version BIGINT, member_id BIGINT, del_flag INT)");
        jdbc.execute("CREATE TABLE workshop_order (id BIGINT PRIMARY KEY, buyer_id BIGINT, paid_at TIMESTAMP, state VARCHAR(16), license_status VARCHAR(16))");
        jdbc.update("INSERT INTO cms_topic VALUES (990, 'Old town', 1, 3, ?, 0), (991, 'Free walk', 2, NULL, ?, 0), (992, 'Unset', NULL, NULL, ?, 0),"
                + " (993, 'Someone else', 1, 1, ?, 0), (994, 'Deleted', 1, 1, ?, 2)", OWNER, OWNER, OWNER, OTHER, OWNER);
        jdbc.update("INSERT INTO workshop_order VALUES (5, ?, CURRENT_TIMESTAMP, 'PAID', 'ACTIVE'), (6, ?, CURRENT_TIMESTAMP, 'PAID', 'REVOKED')", OWNER, OWNER);
        workshop = mock(WorkshopService.class);
        CmsTemplatePublishRequest snapshot = new CmsTemplatePublishRequest();
        snapshot.setTitle("Licensed text");
        snapshot.setDescription("A described route");
        snapshot.setPlayers("2-4");
        snapshot.setDuration(90);
        when(workshop.professionalSource(eq(OWNER), eq(LINE), eq(INSTALL))).thenAnswer(i -> new WorkshopService.ProfessionalSource(
                "WKO_x", VERSION, 301L, CONTENT, TERMS, DRAFT, REVISION, PAYLOAD, copy(snapshot)));
        when(workshop.createGuardedTemplate(any(), eq(OWNER), any())).thenReturn(4242L);
    }

    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }

    WorkshopProfessionalService service(boolean auto) {
        return new WorkshopProfessionalService(jdbc, new DataSourceTransactionManager(dataSource), workshop, clock::get, auto);
    }

    @Test void commandHashMatchesTheAppAlgorithm() throws Exception {
        WorkshopProfessionalService.Command c = WorkshopProfessionalService.command(command("11111111-2222-4333-8444-555555555555",
                "e".repeat(64), "CITY_ORIENTATION", CONTENT));
        assertEquals("977039d518d818c768f6c244dbde40a04276fb67f9463866fd71b144ccafae36", c.hash);
    }

    @Test void targetsListOnlyTheOwnersTopicsWithModeAndFingerprint() throws Exception {
        WorkshopProfessionalService s = service(true);
        Map<String, Object> page = s.targets(OWNER, "{\"licenseId\":\"w18-paid-5\"}");
        assertEquals("w18-owned-professional-topic-targets-v1", page.get("schema"));
        assertEquals("INDIVIDUAL_CONTENT_OWNER_AND_PUBLISHER_METADATA_ONLY", page.get("scope"));
        assertTrue(page.containsKey("nextBeforeTopicId"));
        assertNull(page.get("nextBeforeTopicId"));
        assertEquals(Boolean.FALSE, page.get("hasMore"));
        assertTrue(((String) page.get("checkedAt")).endsWith("Z"));
        List<?> items = (List<?>) page.get("items");
        assertEquals(3, items.size());
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals(992L, first.get("topicId"));
        assertEquals("UNRESOLVED", first.get("mode"));
        assertTrue(first.containsKey("productType") && first.get("productType") == null);
        Map<?, ?> city = (Map<?, ?>) items.get(2);
        assertEquals("CITY_ORIENTATION", city.get("mode"));
        assertEquals(3L, city.get("routeConfigVersion"));
        assertEquals("W18_CONTENT_OWNER_MODE_V1", city.get("fingerprintProfile"));
        assertEquals("PROTECTED_TEMPLATE_ADAPTER_REQUIRED", city.get("materializationStatus"));
        assertEquals(8, city.size());
        assertTrue(((String) city.get("ownerModeFingerprint")).matches("[0-9a-f]{64}"));
        assertEquals(1, ((List<?>) s.targets(OWNER, "{\"licenseId\":\"w18-paid-5\",\"beforeTopicId\":991}").get("items")).size());
        assertEquals(city, s.target(OWNER, "{\"licenseId\":\"w18-paid-5\",\"topicId\":990}"));
        assertEquals(404, assertThrows(AppGapException.class, () -> s.target(OWNER, "{\"licenseId\":\"w18-paid-5\",\"topicId\":993}")).getStatus());
        assertEquals(404, assertThrows(AppGapException.class, () -> s.targets(OTHER, "{\"licenseId\":\"w18-paid-5\"}")).getStatus());
        assertEquals(403, assertThrows(AppGapException.class, () -> s.targets(OWNER, "{\"licenseId\":\"w18-paid-6\"}")).getStatus());
        assertEquals(400, assertThrows(AppGapException.class, () -> s.targets(OWNER, "{\"licenseId\":\"WKO_5\"}")).getStatus());
        assertEquals(400, assertThrows(AppGapException.class, () -> s.targets(OWNER, "{\"licenseId\":\"w18-paid-5\",\"x\":1}")).getStatus());
    }

    @Test void autoReviewCreatesTheGuardedTemplateAndReplays() throws Exception {
        WorkshopProfessionalService s = service(true);
        String fingerprint = fingerprint(s, 990);
        String request = UUID.randomUUID().toString();
        String raw = command(request, fingerprint, "CITY_ORIENTATION", CONTENT);
        Map<String, Object> created = s.submit(OWNER, "player", raw);
        assertEquals("CREATED", created.get("state"));
        assertEquals(Boolean.FALSE, created.get("safeToReplace"));
        assertNull(created.get("rejectionCode"));
        assertEquals(10, created.size());
        assertEquals(WorkshopProfessionalService.command(raw).hash, created.get("commandHash"));
        Map<?, ?> creation = (Map<?, ?>) created.get("creation");
        assertEquals("CREATION_COMMITTED", creation.get("state"));
        assertEquals(4242L, creation.get("templateId"));
        assertEquals(TOPIC, creation.get("targetTopicId"));
        assertEquals(DRAFT, creation.get("ownedDraftId"));
        assertEquals("w18-paid-5", creation.get("licenseId"));
        assertEquals("NOT_CHECKED", creation.get("currentTemplateState"));
        assertEquals("NOT_GRANTED_BY_HISTORY", creation.get("currentUseAuthority"));
        assertEquals(14, creation.size());
        verify(workshop).createGuardedTemplate(argThat(t -> t.getPlayers() == null && t.getDuration() == null && t.getCategoryId() == null),
                eq(OWNER), eq("player"));
        assertEquals("[\"players\",\"duration\",\"category\",\"questionName\",\"questionAnswer\"]",
                jdbc.queryForObject("SELECT missing_inputs_json FROM workshop_professional_operation", String.class));
        assertEquals(created, s.submit(OWNER, "player", raw));
        verify(workshop, times(1)).createGuardedTemplate(any(), anyLong(), any());
        assertEquals(created, s.status(OWNER, "{\"requestId\":\"" + request + "\"}"));
        assertEquals("NOT_FOUND", s.status(OTHER, "{\"requestId\":\"" + request + "\"}").get("state"));
        assertEquals(5, s.status(OWNER, "{\"requestId\":\"" + UUID.randomUUID() + "\"}").size());
        assertEquals(409, assertThrows(AppGapException.class,
                () -> s.submit(OWNER, "player", command(request, fingerprint, "CITY_ORIENTATION", "f".repeat(64)))).getStatus());
        assertEquals(409, assertThrows(AppGapException.class, () -> s.submit(OTHER, "x", raw)).getStatus());
        assertEquals(List.of("AUTHOR:PENDING", "SYSTEM:APPROVED"), events());
        assertEquals("CREATED", s.cancel(OWNER, raw).get("state"));
    }

    @Test void aFailedServerCheckRejectsWithTheAppsCode() throws Exception {
        WorkshopProfessionalService s = service(true);
        String fingerprint = fingerprint(s, 990);
        Map<String, Object> wrongHash = s.submit(OWNER, null, command(UUID.randomUUID().toString(), fingerprint, "CITY_ORIENTATION", "9".repeat(64)));
        assertEquals("REJECTED", wrongHash.get("state"));
        assertEquals(WorkshopProfessionalService.UNAVAILABLE, wrongHash.get("rejectionCode"));
        assertEquals(Boolean.TRUE, wrongHash.get("safeToReplace"));
        assertNull(wrongHash.get("creation"));
        assertEquals("REJECTED", s.submit(OWNER, null, command(UUID.randomUUID().toString(), fingerprint, "FREE_EXPLORATION", CONTENT)).get("state"));
        assertEquals("REJECTED", s.submit(OWNER, null, command(UUID.randomUUID().toString(), "0".repeat(64), "CITY_ORIENTATION", CONTENT)).get("state"));
        when(workshop.professionalSource(anyLong(), anyLong(), anyString())).thenThrow(new ServiceException("LICENSE_DISABLED", 403));
        assertEquals("REJECTED", s.submit(OWNER, null, command(UUID.randomUUID().toString(), fingerprint, "CITY_ORIENTATION", CONTENT)).get("state"));
        verify(workshop, never()).createGuardedTemplate(any(), anyLong(), any());
        assertEquals(400, assertThrows(AppGapException.class, () -> s.submit(OWNER, null, "{\"schema\":\"x\"}")).getStatus());
        assertEquals(400, assertThrows(AppGapException.class, () -> s.submit(OWNER, null, command("NOT-A-UUID", fingerprint, "CITY_ORIENTATION", CONTENT))).getStatus());
        assertEquals(400, assertThrows(AppGapException.class, () -> s.submit(OWNER, null, command(UUID.randomUUID().toString(), fingerprint, "UNRESOLVED", CONTENT))).getStatus());
    }

    @Test void manualReviewWaitsAsPreparedAndUsesTheSharedStateMachine() throws Exception {
        WorkshopProfessionalService s = service(false);
        String fingerprint = fingerprint(s, 990);
        String cancelMe = command(UUID.randomUUID().toString(), fingerprint, "CITY_ORIENTATION", CONTENT);
        assertEquals("PREPARED", s.submit(OWNER, null, cancelMe).get("state"));
        Map<String, Object> cancelled = s.cancel(OWNER, cancelMe);
        assertEquals("CANCELLED", cancelled.get("state"));
        assertEquals(Boolean.TRUE, cancelled.get("safeToReplace"));
        String approveMe = command(UUID.randomUUID().toString(), fingerprint, "CITY_ORIENTATION", CONTENT);
        s.submit(OWNER, null, approveMe);
        Map<String, Object> queue = s.adminQueue();
        assertEquals(1, ((List<?>) queue.get("items")).size());
        long id = ((Number) ((Map<?, ?>) ((List<?>) queue.get("items")).get(0)).get("id")).longValue();
        assertEquals("ESCALATED", s.escalate(1L, id, "needs a second look").get("reviewStatus"));
        assertEquals("PREPARED", s.status(OWNER, "{\"requestId\":\"" + WorkshopProfessionalService.command(approveMe).requestId + "\"}").get("state"));
        assertEquals("CREATED", s.approve(1L, id, null).get("state"));
        assertEquals(409, assertThrows(AppGapException.class, () -> s.reject(1L, id, "late")).getStatus());
        String rejectMe = command(UUID.randomUUID().toString(), fingerprint, "CITY_ORIENTATION", CONTENT);
        s.submit(OWNER, null, rejectMe);
        long second = jdbc.queryForObject("SELECT MAX(id) FROM workshop_professional_operation", Long.class);
        assertEquals(400, assertThrows(AppGapException.class, () -> s.reject(1L, second, " ")).getStatus());
        Map<String, Object> rejected = s.reject(1L, second, "not a fit");
        assertEquals("REJECTED", rejected.get("state"));
        assertEquals(WorkshopProfessionalService.UNAVAILABLE, rejected.get("rejectionCode"));
        assertTrue(events().containsAll(List.of("AUTHOR:CANCELLED", "ADMIN:ESCALATED", "ADMIN:APPROVED", "ADMIN:REJECTED")));
    }

    @Test void cancelBeforeSubmitBlocksALateSubmitAndHistoryPagesByKey() throws Exception {
        WorkshopProfessionalService s = service(true);
        String fingerprint = fingerprint(s, 990);
        String raw = command(UUID.randomUUID().toString(), fingerprint, "CITY_ORIENTATION", CONTENT);
        assertEquals("CANCELLED", s.cancel(OWNER, raw).get("state"));
        assertEquals("CANCELLED", s.submit(OWNER, null, raw).get("state"));
        verify(workshop, never()).createGuardedTemplate(any(), anyLong(), any());
        for (int i = 0; i < 52; i++) s.cancel(OWNER, command(UUID.randomUUID().toString(), fingerprint, "CITY_ORIENTATION", CONTENT));
        Map<String, Object> page = s.history(OWNER, "{\"licenseId\":\"w18-paid-5\"}");
        List<?> items = (List<?>) page.get("items");
        assertEquals(50, items.size());
        assertEquals(50, page.get("scannedCount"));
        assertEquals(Boolean.TRUE, page.get("hasMore"));
        Map<?, ?> top = (Map<?, ?>) items.get(0);
        assertEquals(4, top.size());
        assertEquals(15, ((Map<?, ?>) top.get("command")).size());
        String next = (String) page.get("nextBeforeOperationKey");
        assertEquals(((Map<?, ?>) items.get(49)).get("operationKey"), next);
        assertTrue(((String) top.get("operationKey")).compareTo(next) > 0);
        Map<String, Object> rest = s.history(OWNER, "{\"licenseId\":\"w18-paid-5\",\"beforeOperationKey\":\"" + next + "\"}");
        assertEquals(3, ((List<?>) rest.get("items")).size());
        assertNull(rest.get("nextBeforeOperationKey"));
        assertTrue(rest.containsKey("nextBeforeOperationKey"));
        assertEquals(0, ((List<?>) s.history(OTHER, "{\"licenseId\":\"w18-paid-5\"}").get("items")).size());
        String hash = (String) top.get("commandHash");
        String rebuilt = json.writeValueAsString(top.get("command"));
        assertEquals(WorkshopProfessionalService.command(rebuilt).hash, hash);
    }

    private List<String> events() {
        return jdbc.queryForList("SELECT CONCAT(actor_type, ':', to_status) FROM workshop_professional_event ORDER BY id", String.class);
    }

    private static String fingerprint(WorkshopProfessionalService s, long topic) {
        return (String) s.target(OWNER, "{\"licenseId\":\"w18-paid-5\",\"topicId\":" + topic + "}").get("ownerModeFingerprint");
    }

    private String command(String requestId, String fingerprint, String mode, String contentHash) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", WorkshopProfessionalService.COMMAND_SCHEMA);
        m.put("requestId", requestId);
        m.put("installationRequestId", INSTALL);
        m.put("licenseId", "w18-paid-5");
        m.put("moduleId", "301");
        m.put("purchasedVersionId", VERSION);
        m.put("contentHash", contentHash);
        m.put("termsHash", TERMS);
        m.put("componentHash", COMPONENT);
        m.put("ownedDraftId", DRAFT);
        m.put("currentDraftRevision", REVISION);
        m.put("currentDraftPayloadHash", PAYLOAD);
        m.put("targetTopicId", TOPIC);
        m.put("targetOwnerModeFingerprint", fingerprint);
        m.put("confirmedMode", mode);
        return json.writeValueAsString(m);
    }

    private static CmsTemplatePublishRequest copy(CmsTemplatePublishRequest from) {
        CmsTemplatePublishRequest to = new CmsTemplatePublishRequest();
        org.springframework.beans.BeanUtils.copyProperties(from, to);
        return to;
    }
}
