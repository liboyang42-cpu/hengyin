package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.TeamEventCommand;
import com.chengyinhub.business.mapper.TeamEventCommandMapper;
import com.chengyinhub.business.service.impl.TeamEventCommandService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.io.InputStream;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL receipt locks/unique key/commit/rollback. Domain stand-ins write SQL in the actual transaction. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamEventCommandMySqlIT {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("team_event_command_it");
    private JdbcTemplate jdbc;
    private TeamEventCommandService service;
    private IPlayTeamService teams;
    private IOfficialEventService signups;
    private TeamEventCommandMapper mapper;
    private DataSourceTransactionManager manager;

    @BeforeAll void setup() throws Exception {
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("CREATE DATABASE " + mysql.databaseName());
        DriverManagerDataSource source = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        try (Connection c = source.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource("sql/migration_20261026_team_event_command_receipt.sql"));
        }
        jdbc.execute("CREATE TABLE command_effect (id BIGINT PRIMARY KEY, effects INT NOT NULL) ENGINE=InnoDB");
        Configuration config = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), source));
        config.addMapper(TeamEventCommandMapper.class);
        String resource = "mapper/business/TeamEventCommandMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(xml);
            new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(TeamEventCommandMapper.class);
    }

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM team_event_command_receipt");
        jdbc.update("DELETE FROM command_effect");
        jdbc.update("INSERT INTO command_effect VALUES (9,0)");
        teams = mock(IPlayTeamService.class);
        doAnswer(call -> { jdbc.update("UPDATE command_effect SET effects=effects+1 WHERE id=9"); return null; })
                .when(teams).quit(9L, 71L);
        signups = mock(IOfficialEventService.class);
        service = new TeamEventCommandService(mapper, teams, mock(IOfficialEventV2Service.class), signups, manager);
    }

    @AfterAll void cleanup() {
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("DROP DATABASE " + mysql.databaseName());
    }

    private TeamEventCommand input() {
        TeamEventCommand c = new TeamEventCommand();
        c.setRequestId("1234567890abcdef1234567890abcdef");
        c.setAction("quit"); c.setTargetId(9L); return c;
    }
    private int effects() { return jdbc.queryForObject("SELECT effects FROM command_effect WHERE id=9", Integer.class); }

    @Test void originalReceiptSurvivesServiceRestartAndDoesNotReexecute() {
        assertEquals("APPLIED", service.execute(71L, "TEAM", input()).get("state"));
        TeamEventCommandService restarted = new TeamEventCommandService(mapper, teams, mock(IOfficialEventV2Service.class), mock(IOfficialEventService.class), manager);
        assertEquals("APPLIED", restarted.execute(71L, "TEAM", input()).get("state"));
        assertEquals("APPLIED", restarted.query(71L, "TEAM", input().getRequestId()).get("state"));
        assertEquals(1, effects()); verify(teams, times(1)).quit(9L, 71L);
    }

    @Test void changedPayloadCannotReuseOriginalKeyAndOtherActorCannotReadReceipt() {
        service.execute(71L, "TEAM", input());
        TeamEventCommand changed = input(); changed.setTargetId(10L);
        assertThrows(RuntimeException.class, () -> service.execute(71L, "TEAM", changed));
        assertEquals("UNKNOWN", service.query(72L, "TEAM", input().getRequestId()).get("state"));
        assertEquals(1, effects());
    }

    @Test void businessRollbackDoesNotLeaveEffectsAndRejectedReceiptIsDurable() {
        doAnswer(call -> {
            jdbc.update("UPDATE command_effect SET effects=effects+1 WHERE id=9");
            throw new IllegalStateException("domain rejection");
        }).when(teams).quit(9L, 71L);
        assertEquals("REJECTED", service.execute(71L, "TEAM", input()).get("state"));
        assertEquals(0, effects());
        assertEquals("REJECTED", service.execute(71L, "TEAM", input()).get("state"));
        verify(teams, times(1)).quit(9L, 71L);
    }

    @Test void cancelledBeforeLateSubmissionCannotExecute() {
        assertEquals("UNKNOWN", service.query(71L, "TEAM", input().getRequestId()).get("state"));
        assertEquals("CANCELLED", service.cancel(71L, "TEAM", input()).get("state"));
        assertEquals("CANCELLED", service.execute(71L, "TEAM", input()).get("state"));
        assertEquals(0, effects()); verifyNoInteractions(teams);
    }

    @Test void invitationReceiptDoesNotRejoinAfterMembershipChangesAndRejectsChangedCode() {
        com.chengyinhub.business.domain.PlayTeam team = new com.chengyinhub.business.domain.PlayTeam();
        team.setId(9L);
        when(teams.join("invite-a", 71L)).thenAnswer(call -> {
            jdbc.update("UPDATE command_effect SET effects=effects+1 WHERE id=9"); return team;
        });
        TeamEventCommand c = input(); c.setAction("join"); c.setInviteCode("invite-a");
        assertEquals("APPLIED", service.execute(71L, "TEAM", c).get("state"));
        team.setId(10L); // Later state cannot change the original successful receipt.
        assertEquals("APPLIED", service.execute(71L, "TEAM", c).get("state"));
        c.setInviteCode("invite-b");
        assertThrows(RuntimeException.class, () -> service.execute(71L, "TEAM", c));
        assertEquals(1, effects()); verify(teams, times(1)).join("invite-a", 71L);
    }

    @Test void invitationTargetMismatchRollsBackActualSqlEffect() {
        com.chengyinhub.business.domain.PlayTeam wrong = new com.chengyinhub.business.domain.PlayTeam();
        wrong.setId(10L);
        when(teams.join("wrong", 71L)).thenAnswer(call -> {
            jdbc.update("UPDATE command_effect SET effects=effects+1 WHERE id=9"); return wrong;
        });
        TeamEventCommand c = input(); c.setAction("join"); c.setInviteCode("wrong");
        assertEquals("REJECTED", service.execute(71L, "TEAM", c).get("state"));
        assertEquals(0, effects());
    }

    @Test void applicationMessageIsFrozenAndDoesNotReapplyAfterTerminalChanges() {
        when(teams.apply(9L, 71L, "original")).thenAnswer(call -> {
            jdbc.update("UPDATE command_effect SET effects=effects+1 WHERE id=9"); return new java.util.Date();
        });
        TeamEventCommand c = input(); c.setAction("apply"); c.setMessage("original");
        assertEquals("APPLIED", service.execute(71L, "TEAM", c).get("state"));
        assertEquals("APPLIED", service.execute(71L, "TEAM", c).get("state"));
        c.setMessage("edited");
        assertThrows(RuntimeException.class, () -> service.execute(71L, "TEAM", c));
        assertEquals(1, effects()); verify(teams, times(1)).apply(9L, 71L, "original");
    }

    @Test void ticketRejectionRemainsExplainableAndDoesNotRetryBusiness() {
        when(teams.apply(9L, 71L, null)).thenThrow(new IPlayTeamService.TeamRejection("TICKET_REQUIRED", "持有这一场的票才能申请加入"));
        TeamEventCommand c = input(); c.setAction("apply");
        Map<String, Object> result = service.execute(71L, "TEAM", c);
        assertEquals("REJECTED", result.get("state"));
        assertEquals("TICKET_REQUIRED", result.get("errorCode"));
        assertEquals(result, service.query(71L, "TEAM", c.getRequestId()));
        service.execute(71L, "TEAM", c);
        verify(teams, times(1)).apply(9L, 71L, null);
    }

    @Test void signupUsesExistingVersionRouterAndCancellationBlocksLateRegistration() {
        when(signups.signup(9L, 71L)).thenAnswer(call -> {
            jdbc.update("UPDATE command_effect SET effects=effects+1 WHERE id=9"); return null;
        });
        TeamEventCommand c = input(); c.setAction("signup");
        assertEquals("APPLIED", service.execute(71L, "SIGNUP", c).get("state"));
        assertEquals("APPLIED", service.execute(71L, "SIGNUP", c).get("state"));
        assertEquals("UNKNOWN", service.query(71L, "EVENT", c.getRequestId()).get("state"));
        assertThrows(RuntimeException.class, () -> service.execute(71L, "EVENT", c));
        assertThrows(RuntimeException.class, () -> service.execute(0L, "SIGNUP", c));
        c.setRequestId("abcdef1234567890abcdef1234567890");
        assertEquals("CANCELLED", service.cancel(71L, "SIGNUP", c).get("state"));
        assertEquals("CANCELLED", service.execute(71L, "SIGNUP", c).get("state"));
        assertEquals(1, effects()); verify(signups, times(1)).signup(9L, 71L);
    }

    @Test void concurrentDuplicatesAndCancellationWaitForSameReceiptLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            jdbc.update("UPDATE command_effect SET effects=effects+1 WHERE id=9");
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return null;
        }).when(teams).quit(9L, 71L);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<Map<String, Object>> first = pool.submit(() -> service.execute(71L, "TEAM", input()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals("UNKNOWN", service.query(71L, "TEAM", input().getRequestId()).get("state"));
            Future<Map<String, Object>> duplicate = pool.submit(() -> service.execute(71L, "TEAM", input()));
            Future<Map<String, Object>> cancel = pool.submit(() -> service.cancel(71L, "TEAM", input()));
            assertThrows(TimeoutException.class, () -> cancel.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals("APPLIED", first.get(10, TimeUnit.SECONDS).get("state"));
            assertEquals("APPLIED", duplicate.get(10, TimeUnit.SECONDS).get("state"));
            assertEquals("APPLIED", cancel.get(10, TimeUnit.SECONDS).get("state"));
            assertEquals(1, effects());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
