package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * be-gap-club {@code /api/club/events/proxy-enroll} on real MySQL 8 with the real club_proxy_enroll migration (applied twice).
 * club / club_member / cms_activity / cms_registration are reduced to the columns the service reads.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_R2_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubProxyEnrollMysqlIntegrationTest {
    private static final MysqlIntegrationTestConfig MYSQL = MysqlIntegrationTestConfig.fromEnvironment("club_proxy_enroll_it");
    private static final long CLUB = 7001, OWNER = 9003, ADMIN = 9005, MEMBER = 9004, PENDING = 9006, OUTSIDER = 9007;
    private static final long OPEN = 992031, ENDED = 992032, CANCELLED = 992033, OTHER_CLUB = 992034;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ClubProxyEnrollService service;
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
        service = new ClubProxyEnrollService(jdbc, clock::get);
    }

    @BeforeEach
    void reset() throws Exception {
        for (Map<String, Object> t : jdbc.queryForList("SHOW TABLES")) jdbc.execute("DROP TABLE `" + t.values().iterator().next() + "`");
        jdbc.execute("CREATE TABLE club (id BIGINT PRIMARY KEY, member_id BIGINT, status BIGINT, del_flag INT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE club_member (id BIGINT AUTO_INCREMENT PRIMARY KEY, club_id BIGINT, member_id BIGINT, role TINYINT DEFAULT 0,"
                + " status TINYINT DEFAULT 1, del_flag INT NOT NULL DEFAULT 0, UNIQUE KEY uk(club_id, member_id))");
        jdbc.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, club_id BIGINT, del_flag TINYINT DEFAULT 0, status BIGINT,"
                + " publish_status BIGINT, cancel_time DATETIME NULL, end_date DATETIME NULL)");
        jdbc.execute("CREATE TABLE cms_registration (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, owner_type INT, owner_id BIGINT,"
                + " registration_status INT)");
        jdbc.execute("CREATE TABLE oms_ticket (id BIGINT PRIMARY KEY, owner_type INT, owner_id BIGINT, remaining_inventory BIGINT, del_flag TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE club_event_waitlist (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, activity_id BIGINT, ticket_id BIGINT, state VARCHAR(16))");
        migrate("sql/migration_20261118_club_proxy_enroll.sql");
        migrate("sql/migration_20261118_club_proxy_enroll.sql"); // rerunnable
        jdbc.execute("INSERT INTO club(id, member_id, status, del_flag) VALUES (7001, 9003, 1, 0), (7002, 9008, 1, 0), (7003, 9003, 1, 2)");
        jdbc.execute("INSERT INTO club_member(club_id, member_id, role, status) VALUES (7001, 9005, 1, 1), (7001, 9004, 0, 1), (7001, 9006, 0, 0)");
        jdbc.execute("INSERT INTO cms_activity(id, club_id, status, publish_status, cancel_time, end_date) VALUES"
                + " (992031, 7001, 1, 1, NULL, '2027-01-12 22:00:00'), (992032, 7001, 1, 1, NULL, '2026-11-01 10:00:00'),"
                + " (992033, 7001, 1, 1, '2026-11-10 10:00:00', NULL), (992034, 7002, 1, 1, NULL, NULL)");
    }

    @Test
    void migrationShapeIsOneRowPerActivityAndMember() {
        List<Map<String, Object>> keys = jdbc.queryForList("SELECT index_name AS k, column_name AS c, non_unique AS nu FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'club_proxy_enroll' AND index_name = 'uk_club_proxy_enroll' ORDER BY seq_in_index");
        assertEquals(2, keys.size());
        assertEquals("activity_id", keys.get(0).get("c"));
        assertEquals("member_id", keys.get(1).get("c"));
        assertEquals(0L, ((Number) keys.get(0).get("nu")).longValue());
    }

    @Test
    void invitationIsIdempotentAndBecomesRegisteredOnlyWhenTheMemberSignsUp() {
        Map<String, Object> first = service.propose(OWNER, CLUB, OPEN, MEMBER);
        assertEquals("INVITED", first.get("state"));
        assertEquals(false, first.get("replayed"));
        assertEquals(true, first.get("registrationRequired"));
        assertEquals(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.Instant.ofEpochMilli(clock.get())), first.get("createdAt"), "the same instant that was written, in +08:00");
        Map<String, Object> again = service.propose(ADMIN, CLUB, OPEN, MEMBER);
        assertEquals(first.get("id"), again.get("id"));
        assertEquals(true, again.get("replayed"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM club_proxy_enroll", Integer.class));
        assertEquals(OWNER, jdbc.queryForObject("SELECT proposer_member_id FROM club_proxy_enroll", Long.class), "the first proposer is kept");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cms_registration", Integer.class), "no registration is created on the member's behalf");

        Map<String, Object> mine = service.mine(MEMBER);
        List<?> items = (List<?>) mine.get("items");
        assertEquals(1, items.size());
        assertEquals(OWNER, ((Map<?, ?>) items.get(0)).get("proposerMemberId"));
        assertEquals("INVITED", ((Map<?, ?>) items.get(0)).get("state"));
        assertTrue(((List<?>) service.mine(ADMIN).get("items")).isEmpty());

        jdbc.update("INSERT INTO cms_registration(member_id, owner_type, owner_id, registration_status) VALUES (?, 2, ?, 2)", MEMBER, OPEN);
        assertEquals("REGISTERED", ((Map<?, ?>) ((List<?>) service.mine(MEMBER).get("items")).get(0)).get("state"));
    }

    @Test
    void refusesWhatTheMemberOrSessionCannotTake() {
        assertEquals("PROXY_ENROLL_NOT_MEMBER", assertThrows(AppGapException.class, () -> service.propose(OWNER, CLUB, OPEN, OUTSIDER)).getErrorCode());
        assertEquals("PROXY_ENROLL_NOT_MEMBER", assertThrows(AppGapException.class, () -> service.propose(OWNER, CLUB, OPEN, PENDING)).getErrorCode(),
                "a join request that is still pending is not a member");
        assertEquals("ACTIVITY_NOT_OPEN", assertThrows(AppGapException.class, () -> service.propose(OWNER, CLUB, ENDED, MEMBER)).getErrorCode());
        assertEquals("ACTIVITY_NOT_OPEN", assertThrows(AppGapException.class, () -> service.propose(OWNER, CLUB, CANCELLED, MEMBER)).getErrorCode());
        assertEquals("ACTIVITY_NOT_FOUND", assertThrows(AppGapException.class, () -> service.propose(OWNER, CLUB, OTHER_CLUB, MEMBER)).getErrorCode());
        assertEquals("CLUB_NOT_FOUND", assertThrows(AppGapException.class, () -> service.propose(OWNER, 7003L, OPEN, MEMBER)).getErrorCode());
        assertEquals(400, assertThrows(AppGapException.class, () -> service.propose(OWNER, CLUB, OPEN, null)).getStatus());
        assertEquals(401, assertThrows(AppGapException.class, () -> service.propose(null, CLUB, OPEN, MEMBER)).getStatus());
        jdbc.update("INSERT INTO cms_registration(member_id, owner_type, owner_id, registration_status) VALUES (?, 2, ?, 2)", MEMBER, OPEN);
        assertEquals("PROXY_ENROLL_ALREADY_REGISTERED", assertThrows(AppGapException.class, () -> service.propose(OWNER, CLUB, OPEN, MEMBER)).getErrorCode());
        assertEquals("INVITED", service.propose(OWNER, CLUB, OPEN, OWNER).get("state"), "the owner counts as a member of their own club");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM club_proxy_enroll", Integer.class));
    }

    /** App activity/join|cancel send only {activityId}: the waitlist ticket is resolved only when it is unambiguous. */
    @Test
    void waitlistTicketIsResolvedOnlyWhenUnambiguous() {
        ClubWaitlistTickets tickets = new ClubWaitlistTickets(jdbc);
        jdbc.execute("INSERT INTO oms_ticket(id, owner_type, owner_id, remaining_inventory, del_flag) VALUES"
                + " (1, 2, 992031, 5, 0), (2, 2, 992032, 0, 0), (3, 2, 992032, 4, 0), (4, 2, 992033, 0, 0), (5, 2, 992033, 0, 0),"
                + " (6, 2, 992034, 0, 1), (7, 1, 992031, 0, 0)");
        assertEquals(1L, tickets.forJoin(OPEN), "the only ticket of the session, even with seats left (the waitlist service then says so)");
        assertEquals(2L, tickets.forJoin(ENDED), "the only sold-out ticket");
        AppGapException two = assertThrows(AppGapException.class, () -> tickets.forJoin(CANCELLED));
        assertEquals("TICKET_REQUIRED", two.getErrorCode());
        assertEquals(List.of(4L, 5L), ((Map<?, ?>) two.getData()).get("ticketIds"));
        assertEquals("TICKET_NOT_FOUND", assertThrows(AppGapException.class, () -> tickets.forJoin(OTHER_CLUB)).getErrorCode(), "deleted tickets do not count");
        jdbc.execute("INSERT INTO club_event_waitlist(member_id, activity_id, ticket_id, state) VALUES"
                + " (9004, 992033, 5, 'WAITING'), (9004, 992033, 4, 'CANCELLED'), (9005, 992033, 4, 'OFFERED'), (9005, 992033, 5, 'REQUESTED')");
        assertEquals(5L, tickets.forCancel(MEMBER, CANCELLED), "the caller's own active entry, not the cancelled one");
        assertEquals("TICKET_REQUIRED", assertThrows(AppGapException.class, () -> tickets.forCancel(ADMIN, CANCELLED)).getErrorCode());
        assertEquals(1L, tickets.forCancel(MEMBER, OPEN), "no entry: the session's ticket (the waitlist service then answers nothing to cancel)");
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
