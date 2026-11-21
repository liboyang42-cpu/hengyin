package com.chengyinhub.business.externalgame;

import com.chengyinhub.business.appgaps.AppGapException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** G3: takedown / restore, report resolution, operator node binding and availability on H2 (MySQL mode). */
class ExternalGameOpsServiceTest {
    private JdbcTemplate jdbc;
    private ExternalGameOpsService ops;
    private long now = 1_763_200_000_000L;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:egops" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE external_game (game_id VARCHAR(64) PRIMARY KEY, owner_member_id BIGINT NOT NULL,"
                + " approved_submission_id BIGINT NULL, approved_time TIMESTAMP NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE external_game_submission (id BIGINT PRIMARY KEY, game_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,"
                + " name VARCHAR(80), version VARCHAR(32))");
        jdbc.execute("CREATE TABLE external_game_report (id BIGINT AUTO_INCREMENT PRIMARY KEY, game_id VARCHAR(64) NOT NULL,"
                + " reporter_member_id BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'OPEN')");
        jdbc.execute("CREATE TABLE external_game_play_session (session_id VARCHAR(40) PRIMARY KEY, game_id VARCHAR(64) NOT NULL,"
                + " status VARCHAR(16) NOT NULL)");
        jdbc.execute("CREATE TABLE external_game_node_binding (node_id BIGINT PRIMARY KEY, topic_id BIGINT NOT NULL, game_id VARCHAR(64) NOT NULL,"
                + " owner_member_id BIGINT NOT NULL, min_score INT NOT NULL DEFAULT 0, require_pass TINYINT NOT NULL DEFAULT 1,"
                + " enabled TINYINT NOT NULL DEFAULT 1, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY, topic_id BIGINT, name VARCHAR(80))");
        // Same shape as migration_20261115_external_games_ops.sql (charset clauses dropped for H2).
        jdbc.execute("CREATE TABLE external_game_takedown (game_id VARCHAR(64) PRIMARY KEY, status VARCHAR(16) NOT NULL,"
                + " submission_id BIGINT NULL, reason_code VARCHAR(24) NOT NULL, reason VARCHAR(500) NOT NULL, taken_down_by BIGINT NOT NULL,"
                + " taken_down_time TIMESTAMP NOT NULL, lifted_by BIGINT NULL, lifted_time TIMESTAMP NULL, lift_reason VARCHAR(500) NULL,"
                + " update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE external_game_report_resolution (report_id BIGINT PRIMARY KEY, resolution VARCHAR(16) NOT NULL,"
                + " note VARCHAR(500) NULL, resolver_id BIGINT NOT NULL, resolved_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE external_game_moderation_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, game_id VARCHAR(64) NOT NULL,"
                + " action VARCHAR(24) NOT NULL, target_type VARCHAR(16) NOT NULL, target_id VARCHAR(64) NOT NULL, actor_id BIGINT NOT NULL,"
                + " reason VARCHAR(500) NULL, detail_json VARCHAR(1000) NULL, create_time TIMESTAMP NOT NULL)");

        jdbc.update("INSERT INTO external_game VALUES ('maze.one', 7, 11, NOW(), NOW(), NOW())");
        jdbc.update("INSERT INTO external_game_submission VALUES (11, 'maze.one', 'APPROVED', 'Maze', '1.0.0')");
        jdbc.update("INSERT INTO external_game VALUES ('draft.one', 8, NULL, NULL, NOW(), NOW())");
        jdbc.update("INSERT INTO external_game_report(game_id, reporter_member_id, status) VALUES ('maze.one', 100, 'OPEN')");
        jdbc.update("INSERT INTO external_game_report(game_id, reporter_member_id, status) VALUES ('maze.one', 101, 'OPEN')");
        jdbc.update("INSERT INTO external_game_play_session VALUES ('EGS_a', 'maze.one', 'STARTED')");
        jdbc.update("INSERT INTO external_game_play_session VALUES ('EGS_b', 'maze.one', 'COMPLETED')");
        jdbc.update("INSERT INTO cms_topic_node VALUES (501, 40, 'Old gate')");
        ops = new ExternalGameOpsService(jdbc, new DataSourceTransactionManager(ds), () -> now);
    }

    @Test
    void takedownDelistsExpiresSessionsResolvesReportsAndAudits() {
        Map<String, Object> out = ops.takedown(1L, "maze.one", Map.of("reasonCode", "MALICIOUS_CODE", "reason", "phones home"));
        assertEquals("ACTIVE", out.get("status"));
        assertEquals(11L, out.get("submissionId"));
        assertEquals(1, out.get("expiredSessions"));
        assertEquals(2, out.get("resolvedReports"));
        assertNull(jdbc.queryForObject("SELECT approved_submission_id FROM external_game WHERE game_id='maze.one'", Long.class));
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM external_game_play_session WHERE session_id='EGS_a'", String.class));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM external_game_play_session WHERE session_id='EGS_b'", String.class));
        assertEquals(0, count("SELECT COUNT(*) FROM external_game_report WHERE status='OPEN'"));
        assertEquals(2, count("SELECT COUNT(*) FROM external_game_report_resolution WHERE resolution='ACTIONED' AND resolver_id=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM external_game_moderation_event WHERE action='TAKEDOWN' AND actor_id=1"));
        assertEquals(2, count("SELECT COUNT(*) FROM external_game_moderation_event WHERE action='REPORT_ACTIONED'"));

        Map<String, Object> availability = ops.availability("maze.one");
        assertEquals("TAKEN_DOWN", availability.get("state"));
        assertEquals(false, availability.get("available"));
        assertFalse(availability.containsKey("reason"), "the operator's internal reason never reaches players");

        AppGapException again = assertThrows(AppGapException.class,
                () -> ops.takedown(1L, "maze.one", Map.of("reasonCode", "OTHER", "reason", "x")));
        assertEquals("ALREADY_TAKEN_DOWN", again.getErrorCode());
    }

    @Test
    void restorePutsBackNewestApprovedVersionAndAudits() {
        ops.takedown(1L, "maze.one", Map.of("reasonCode", "BROKEN", "reason", "blank screen", "resolveOpenReports", false));
        assertEquals(2, count("SELECT COUNT(*) FROM external_game_report WHERE status='OPEN'"));
        jdbc.update("INSERT INTO external_game_submission VALUES (12, 'maze.one', 'APPROVED', 'Maze', '1.0.1')");
        Map<String, Object> out = ops.restore(2L, "maze.one", Map.of("reason", "fixed in 1.0.1"));
        assertEquals("LIFTED", out.get("status"));
        assertEquals(12L, jdbc.queryForObject("SELECT approved_submission_id FROM external_game WHERE game_id='maze.one'", Long.class));
        assertEquals("LISTED", ops.availability("maze.one").get("state"));
        assertEquals(1, count("SELECT COUNT(*) FROM external_game_moderation_event WHERE action='RESTORE' AND actor_id=2"));
        assertEquals("NOT_TAKEN_DOWN", assertThrows(AppGapException.class,
                () -> ops.restore(2L, "maze.one", Map.of("reason", "again"))).getErrorCode());
    }

    @Test
    void restoreRefusesWhenNoApprovedVersionRemains() {
        ops.takedown(1L, "maze.one", Map.of("reasonCode", "COPYRIGHT", "reason", "claim"));
        jdbc.update("UPDATE external_game_submission SET status='CANCELLED' WHERE id=11");
        assertEquals("NOTHING_TO_RESTORE", assertThrows(AppGapException.class,
                () -> ops.restore(1L, "maze.one", Map.of("reason", "x"))).getErrorCode());
        assertNull(jdbc.queryForObject("SELECT approved_submission_id FROM external_game WHERE game_id='maze.one'", Long.class));
    }

    @Test
    void takedownValidatesInput() {
        assertThrows(AppGapException.class, () -> ops.takedown(1L, "maze.one", Map.of("reasonCode", "OTHER")));
        assertThrows(AppGapException.class, () -> ops.takedown(1L, "maze.one", Map.of("reasonCode", "NOPE", "reason", "x")));
        assertThrows(AppGapException.class, () -> ops.takedown(null, "maze.one", Map.of("reasonCode", "OTHER", "reason", "x")));
        assertEquals("GAME_NOT_FOUND", assertThrows(AppGapException.class,
                () -> ops.takedown(1L, "nope.game", Map.of("reasonCode", "OTHER", "reason", "x"))).getErrorCode());
        assertEquals(0, count("SELECT COUNT(*) FROM external_game_moderation_event"));
    }

    @Test
    void resolveReportDismissNeedsNoteAndIsOnce() {
        long id = jdbc.queryForObject("SELECT MIN(id) FROM external_game_report", Long.class);
        assertThrows(AppGapException.class, () -> ops.resolveReport(3L, id, Map.of("resolution", "DISMISSED")));
        Map<String, Object> out = ops.resolveReport(3L, id, Map.of("resolution", "DISMISSED", "note", "not reproducible"));
        assertEquals("RESOLVED", out.get("status"));
        assertEquals("DISMISSED", out.get("resolution"));
        assertEquals(1, count("SELECT COUNT(*) FROM external_game_moderation_event WHERE action='REPORT_DISMISSED' AND target_id='" + id + "'"));
        assertEquals("REPORT_ALREADY_RESOLVED", assertThrows(AppGapException.class,
                () -> ops.resolveReport(3L, id, Map.of("resolution", "ACTIONED"))).getErrorCode());

        Map<String, Object> page = Map.of("rows", List.of(new java.util.LinkedHashMap<>(Map.of("reportId", id, "gameId", "maze.one"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) ops.enrichReports(page).get("rows")).get(0);
        assertEquals("DISMISSED", row.get("resolution"));
        assertEquals(false, row.get("takenDown"));
    }

    @Test
    void operatorBindsListedGameToNodeAndUnbinds() {
        Map<String, Object> bound = ops.adminBind(4L, 501L, Map.of("gameId", "maze.one", "minScore", 60, "reason", "pilot"));
        assertEquals(40L, bound.get("topicId"));
        assertEquals(7L, bound.get("ownerMemberId"), "binding owner stays the game's creator");
        assertEquals(60, bound.get("minScore"));
        assertEquals(true, bound.get("listed"));
        assertEquals(1L, ops.listBindings("maze.one", null, 1, 20).get("total"));
        assertEquals(1, count("SELECT COUNT(*) FROM external_game_moderation_event WHERE action='NODE_BIND' AND actor_id=4"));

        assertEquals("GAME_NOT_APPROVED", assertThrows(AppGapException.class,
                () -> ops.adminBind(4L, 501L, Map.of("gameId", "draft.one"))).getErrorCode());
        assertEquals("NODE_NOT_FOUND", assertThrows(AppGapException.class,
                () -> ops.adminBind(4L, 999L, Map.of("gameId", "maze.one"))).getErrorCode());

        ops.adminUnbind(4L, 501L, Map.of("reason", "pilot over"));
        assertEquals(0L, ops.listBindings("maze.one", null, 1, 20).get("total"));
        assertEquals("NODE_NOT_BOUND", assertThrows(AppGapException.class,
                () -> ops.adminUnbind(4L, 501L, Map.of())).getErrorCode());

        ops.takedown(1L, "maze.one", Map.of("reasonCode", "OTHER", "reason", "x"));
        assertEquals("GAME_TAKEN_DOWN", assertThrows(AppGapException.class,
                () -> ops.adminBind(4L, 501L, Map.of("gameId", "maze.one"))).getErrorCode());
        assertEquals(5L, ops.moderationEvents("maze.one", 1, 50).get("total"));
    }

    @Test
    void availabilityStates() {
        assertEquals("LISTED", ops.availability("maze.one").get("state"));
        assertEquals("NOT_LISTED", ops.availability("draft.one").get("state"));
        assertEquals("UNKNOWN_GAME", ops.availability("ghost.game").get("state"));
        assertThrows(AppGapException.class, () -> ops.availability("../etc"));
    }

    @Test
    void activeTakedownFailsOpenOnlyWhenTableMissing() {
        jdbc.execute("DROP TABLE external_game_takedown");
        assertFalse(ops.activeTakedown("maze.one"));
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
