package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U08-03 creator maintenance on real MySQL 8 with the real play_run_feedback and creator_maintenance migrations.
 * Topic/node/run/revision tables are reduced to the columns the service reads.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_R2_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreatorMaintenanceMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("creator_maintenance_it");
    private static final long AUTHOR = 1, OTHER = 2;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private CreatorMaintenanceService service;
    private final AtomicLong clock = new AtomicLong(java.sql.Timestamp.valueOf("2026-11-15 10:00:00").getTime());

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = ds(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = ds(MYSQL.databaseUrl());
        jdbc = new JdbcTemplate(dataSource);
        service = new CreatorMaintenanceService(jdbc, new DataSourceTransactionManager(dataSource), clock::get);
    }

    @BeforeEach
    void reset() throws Exception {
        for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES")) jdbc.execute("DROP TABLE `" + t.values().iterator().next() + "`");
        jdbc.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, name VARCHAR(64), member_id BIGINT, del_flag CHAR(1) DEFAULT '0',"
                + " config_version BIGINT NOT NULL DEFAULT 1, published_config_version BIGINT NULL)");
        jdbc.execute("CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY, topic_id BIGINT, name VARCHAR(64))");
        jdbc.execute("CREATE TABLE topic_route_session (id BIGINT AUTO_INCREMENT PRIMARY KEY, topic_id BIGINT, member_id BIGINT,"
                + " config_version BIGINT NOT NULL, completed_at DATETIME(3) NULL)");
        jdbc.execute("CREATE TABLE cms_topic_content_revision (id BIGINT PRIMARY KEY, topic_id BIGINT, revision_no BIGINT)");
        migrate("sql/migration_20261109_play_run_feedback.sql");
        migrate("sql/migration_20261115_creator_maintenance.sql");
        migrate("sql/migration_20261115_creator_maintenance.sql"); // rerunnable
        jdbc.execute("INSERT INTO cms_topic(id, name, member_id, del_flag, config_version, published_config_version) VALUES"
                + " (10,'老城三章',1,'0',2,2),(11,'别人的',2,'0',1,1),(12,'草稿',1,'0',1,NULL),(13,'已删',1,'2',1,1)");
        jdbc.execute("INSERT INTO cms_topic_node(id, topic_id, name) VALUES (101,10,'骑楼'),(102,10,'码头')");
        jdbc.execute("INSERT INTO cms_topic_content_revision(id, topic_id, revision_no) VALUES (501,10,1),(502,10,2)");
        feedback(1, 7, 10, 101L, "PLACE", "入口找不到");
        feedback(2, 8, 10, 102L, "QUESTION", "答案有歧义");
        feedback(3, 8, 10, null, "OTHER", "整体太长");
        feedback(4, 9, 11, null, "OTHER", "别人的主题");
        feedback(5, 9, 13, null, "OTHER", "已删主题");
    }

    @Test
    void feedbackIsAuthorOnlyPagedAndNeverNamesTheReporter() {
        Map<String, Object> first = service.feedback(AUTHOR, null, null, "2");
        List<?> items = (List<?>) first.get("items");
        assertEquals(2, items.size());
        assertEquals(true, first.get("hasMore"));
        Map<?, ?> top = (Map<?, ?>) items.get(0);
        assertEquals(3L, top.get("feedbackId"));
        assertFalse(top.containsKey("memberId"));
        assertFalse(String.valueOf(first).contains("runId"));
        assertEquals("码头", ((Map<?, ?>) items.get(1)).get("nodeName"));
        Map<String, Object> second = service.feedback(AUTHOR, null, (String) first.get("nextCursor"), "2");
        assertEquals(1, ((List<?>) second.get("items")).size(), "deleted topics and other authors' topics are excluded");
        assertEquals(false, second.get("hasMore"));
        assertEquals(1, ((List<?>) service.feedback(OTHER, null, null, null).get("items")).size());
        assertEquals("TOPIC_NOT_FOUND", assertThrows(AppGapException.class, () -> service.feedback(AUTHOR, "11", null, null)).getErrorCode());
        assertEquals(401, assertThrows(AppGapException.class, () -> service.feedback(null, null, null, null)).getStatus());
    }

    @Test
    void ticketFollowsProblemDraftPublishRetestAndClosesOnlyWithFixEvidence() {
        Map<String, Object> opened = service.open(AUTHOR, open("req-open-0001", 10, "入口和题面", List.of(2L, 1L)));
        long ticket = (Long) opened.get("ticketId");
        assertEquals("REPORTED", opened.get("stage"));
        assertEquals(2L, ((Map<?, ?>) opened.get("baseline")).get("configVersion"));
        assertEquals(502L, ((Map<?, ?>) opened.get("baseline")).get("contentRevisionId"));
        assertEquals(2, ((List<?>) opened.get("feedback")).size());
        assertEquals(ticket, service.open(AUTHOR, open("req-open-0001", 10, "入口和题面", List.of(1L, 2L))).get("ticketId"), "same request replays");
        assertEquals("REQUEST_ID_CONFLICT", assertThrows(AppGapException.class,
                () -> service.open(AUTHOR, open("req-open-0001", 10, "改了标题", List.of(1L, 2L)))).getErrorCode());
        assertEquals("FEEDBACK_ALREADY_LINKED", assertThrows(AppGapException.class,
                () -> service.open(AUTHOR, open("req-open-0002", 10, "重复", List.of(2L)))).getErrorCode());
        assertEquals("APPLIED", service.openRequest(AUTHOR, "req-open-0001").get("state"));
        assertEquals("NOT_FOUND", service.openRequest(AUTHOR, "req-open-9999").get("state"));
        assertEquals(ticket, ((Map<?, ?>) ((List<?>) service.feedback(AUTHOR, "10", null, null).get("items")).get(1)).get("ticketId"));

        assertEquals("FIX_NOT_PUBLISHED", assertThrows(AppGapException.class,
                () -> service.close(AUTHOR, String.valueOf(ticket), close("req-close-0001", 1, "FIXED", null))).getErrorCode());
        jdbc.update("UPDATE cms_topic SET config_version = 3 WHERE id = 10");
        assertEquals("FIX_DRAFTED", service.ticket(AUTHOR, String.valueOf(ticket)).get("stage"));
        jdbc.update("UPDATE cms_topic SET published_config_version = 3 WHERE id = 10");
        Map<String, Object> published = service.ticket(AUTHOR, String.valueOf(ticket));
        assertEquals("FIX_PUBLISHED", published.get("stage"));
        assertEquals(true, published.get("canCloseFixed"));
        jdbc.update("INSERT INTO topic_route_session(topic_id, member_id, config_version, completed_at) VALUES (10, 7, 3, NOW(3)), (10, 1, 2, NOW(3))");
        assertEquals("FIX_PUBLISHED", service.ticket(AUTHOR, String.valueOf(ticket)).get("stage"), "a player's run or an old-version run is not the author's retest");
        jdbc.update("INSERT INTO topic_route_session(topic_id, member_id, config_version, completed_at) VALUES (10, 1, 3, NOW(3))");
        assertEquals("RETESTED", service.ticket(AUTHOR, String.valueOf(ticket)).get("stage"));

        assertEquals("TICKET_VERSION_CONFLICT", assertThrows(AppGapException.class,
                () -> service.close(AUTHOR, String.valueOf(ticket), close("req-close-0001", 7, "FIXED", null))).getErrorCode());
        assertEquals("TICKET_NOT_FOUND", assertThrows(AppGapException.class,
                () -> service.close(OTHER, String.valueOf(ticket), close("req-close-0001", 1, "FIXED", null))).getErrorCode());
        Map<String, Object> closed = service.close(AUTHOR, String.valueOf(ticket), close("req-close-0001", 1, "FIXED", null));
        assertEquals("CLOSED", closed.get("status"));
        assertEquals("FIXED", closed.get("resolution"));
        assertEquals(3L, closed.get("fixedConfigVersion"));
        assertEquals(2L, closed.get("version"));
        assertNotNull(closed.get("closedAt"));
        assertEquals(closed, service.close(AUTHOR, String.valueOf(ticket), close("req-close-0001", 1, "FIXED", null)), "same close replays");
        assertEquals("TICKET_CLOSED", assertThrows(AppGapException.class,
                () -> service.close(AUTHOR, String.valueOf(ticket), close("req-close-0002", 2, "NO_CHANGE", "再关一次"))).getErrorCode());
    }

    @Test
    void closingWithoutAFixNeedsAReasonAndTicketsStayPrivateToTheirAuthor() {
        long ticket = (Long) service.open(AUTHOR, open("req-open-0003", 10, "整体节奏", List.of(3L))).get("ticketId");
        assertEquals("CLOSE_REASON_REQUIRED", assertThrows(AppGapException.class,
                () -> service.close(AUTHOR, String.valueOf(ticket), close("req-close-0003", 1, "NO_CHANGE", " "))).getErrorCode());
        Map<String, Object> closed = service.close(AUTHOR, String.valueOf(ticket), close("req-close-0003", 1, "NO_CHANGE", "设计如此，章节长度是主题卖点"));
        assertEquals("NO_CHANGE", closed.get("resolution"));
        assertNull(closed.get("fixedConfigVersion"));
        assertEquals("TICKET_NOT_FOUND", assertThrows(AppGapException.class, () -> service.ticket(OTHER, String.valueOf(ticket))).getErrorCode());
        assertEquals(1, ((List<?>) service.tickets(AUTHOR, "10", "CLOSED", null, null).get("items")).size());
        assertEquals(0, ((List<?>) service.tickets(AUTHOR, null, "OPEN", null, null).get("items")).size());
        assertEquals(0, ((List<?>) service.tickets(OTHER, null, null, null, null).get("items")).size());
        assertEquals("TOPIC_NOT_PUBLISHED", assertThrows(AppGapException.class,
                () -> service.open(AUTHOR, open("req-open-0004", 12, "草稿", List.of(3L)))).getErrorCode());
        assertEquals("TOPIC_NOT_FOUND", assertThrows(AppGapException.class,
                () -> service.open(OTHER, open("req-open-0005", 10, "别人的", List.of(1L)))).getErrorCode());
        assertEquals("FEEDBACK_TOPIC_MISMATCH", assertThrows(AppGapException.class,
                () -> service.open(OTHER, open("req-open-0006", 11, "混入", List.of(4L, 1L)))).getErrorCode());
        assertEquals(400, assertThrows(AppGapException.class,
                () -> service.open(AUTHOR, open("req-open-0007", 10, "", List.of(1L)))).getStatus());
        Map<String, Object> extra = open("req-open-0008", 10, "多余字段", List.of(1L));
        extra.put("authorMemberId", 2L);
        assertEquals(400, assertThrows(AppGapException.class, () -> service.open(AUTHOR, extra)).getStatus());
    }

    private void feedback(long id, long member, long topic, Long node, String category, String content) {
        jdbc.update("INSERT INTO play_run_feedback(id, member_id, route_session_id, topic_id, activity_id, config_version, node_id, category, content,"
                + " request_id, request_hash, create_time) VALUES (?,?,?,?,0,2,?,?,?,?,?,NOW(3))",
                id, member, 900 + id, topic, node, category, content, "fb-" + id + "-request", "0".repeat(64));
    }

    private static Map<String, Object> open(String requestId, long topic, String title, List<Long> feedbackIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId); m.put("topicId", topic); m.put("title", title); m.put("feedbackIds", feedbackIds);
        return m;
    }

    private static Map<String, Object> close(String requestId, long expectedVersion, String resolution, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", requestId); m.put("expectedVersion", expectedVersion); m.put("resolution", resolution);
        if (reason != null) m.put("reason", reason);
        return m;
    }

    private void migrate(String path) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : Files.readString(Paths.get(path)).split(";\\s*\\n")) {
                String sql = statement.replaceAll("(?m)^--.*$", "").trim();
                if (!sql.isEmpty()) s.execute(sql);
            }
        }
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
