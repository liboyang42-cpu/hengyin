package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.HandoffState;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.LaunchReceipt;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.OngoingVerdict;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.Phase;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitPorts.SoloResult;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.Mode;
import com.chengyinhub.business.service.rendezvous.RendezvousSafeExitService.View;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** H2/MyBatis exit ledger. Passing H2 is not MySQL acceptance, and the candidate SQL is not executed outside this test. */
class RendezvousSafeExitPersistenceTest {
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    RendezvousSafeExitServiceTest.Ports ports;
    RendezvousSafeExitService service;

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16exit_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        initializeSchema();
        manager = new DataSourceTransactionManager(dataSource);
        ports = new RendezvousSafeExitServiceTest.Ports();
        service = service();
    }

    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }

    @Test void disabledStoreDoesNotOpenADatabaseCall() {
        assertThrows(IllegalStateException.class, () -> new RendezvousSafeExitStore().transaction(tx -> fail("must not run")));
    }

    @Test void exitCommitsAndRestartReadsTheSameFact() throws Exception {
        View exited = service.choose(1, "ticket-1", "EXIT");
        RendezvousSafeExitService restarted = new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION, newStore(),
                ports, ports, ports, ports, null, null, ports.clock);
        ports.failVerification = true;
        View read = restarted.options(1, "ticket-1");
        assertEquals(exited.ticketId, read.ticketId);
        assertEquals("EXIT", read.choice);
        assertEquals("WAITING", read.phase);
        assertEquals("NOT_STARTED", read.handoff);
        assertFalse(read.completed);
        assertEquals(1, ports.releases);
        assertEquals(0, ports.launches);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM w16_safe_exit", Integer.class));
    }

    @Test void releaseFailureRollsTheExitBack() {
        ports.failRelease = true;
        assertThrows(IllegalStateException.class, () -> service.choose(1, "ticket-1", "EXIT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_safe_exit", Integer.class));
    }

    @Test void unknownHandoffDoesNotInsertOrStartSolo() {
        ports.verdict = RendezvousSafeExitServiceTest.verdict(Phase.MUTUAL, "match-1");
        ports.answer = HandoffState.UNKNOWN;
        ports.goal = "published-solo-1";
        assertEquals("HANDOFF_UNKNOWN", assertThrows(com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection.class,
                () -> service.choose(1, "ticket-1", "SOLO")).getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM w16_safe_exit", Integer.class));
        assertEquals(0, ports.launches);
        assertEquals("match-1", ports.queriedMatchId);
        assertEquals(0, ports.mintedRequestIds);
    }

    @Test void soloReceiptSurvivesRestartAndStaysIncompleteUntilTheResultSaysSo() throws Exception {
        ports.verdict = RendezvousSafeExitServiceTest.verdict(Phase.ONE_SIDED, "match-1");
        ports.answer = HandoffState.NOT_STARTED;
        ports.goal = "published-solo-1";
        ports.receipt = new LaunchReceipt("rcpt-1", true);
        View started = service.choose(1, "ticket-1", "SOLO");
        assertEquals("STARTED", started.soloState);
        assertTrue(started.resumed);
        assertFalse(started.completed);
        assertEquals("rcpt-1", jdbc.queryForObject("SELECT launch_receipt FROM w16_safe_exit WHERE ticket_id='ticket-1'", String.class));
        RendezvousSafeExitService restarted = new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION, newStore(),
                ports, ports, ports, ports, ports, ports, ports.clock);
        assertFalse(restarted.options(1, "ticket-1").completed);
        ports.status = SoloResult.Status.COMPLETED;
        View done = restarted.options(1, "ticket-1");
        assertTrue(done.completed);
        assertEquals("COMPLETED", done.soloState);
        assertEquals(1, ports.launches);
    }

    @Test void twoExitsMayBothHaveNoLaunchReceipt() {
        service.choose(1, "ticket-1", "EXIT");
        ports.verdict = RendezvousSafeExitServiceTest.verdict(Phase.WAITING, null);
        service.choose(2, "ticket-2", "EXIT");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM w16_safe_exit WHERE launch_receipt IS NULL", Integer.class));
    }

    @Test void committedExitSurvivesFileShutdown() throws Exception {
        jdbc.execute("SHUTDOWN");
        String url = "jdbc:h2:file:" + java.nio.file.Files.createTempDirectory("w16-exit").resolve("exit").toAbsolutePath() + ";MODE=MySQL;LOCK_TIMEOUT=5000";
        dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        jdbc = new JdbcTemplate(dataSource);
        initializeSchema();
        manager = new DataSourceTransactionManager(dataSource);
        service = service();
        service.choose(1, "ticket-1", "EXIT");
        jdbc.execute("SHUTDOWN");
        dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        ports.failVerification = true;
        RendezvousSafeExitService restarted = new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION, newStore(),
                ports, ports, ports, ports, null, null, ports.clock);
        View read = restarted.options(1, "ticket-1");
        assertEquals("EXIT", read.choice);
        assertFalse(read.completed);
    }

    private RendezvousSafeExitService service() throws Exception {
        return new RendezvousSafeExitService(Mode.ISOLATED_CONTRACT_EVALUATION, newStore(), ports, ports, ports, ports, ports, ports, ports.clock);
    }

    private void initializeSchema() throws Exception {
        String schema = RendezvousH2Schema.protocol(Files.readString(sourceRoot().resolve(
                "chengyinhub-admin/sql/migration_w16_safe_exit_solo_candidate.sql")));
        try (java.sql.Connection connection = dataSource.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private RendezvousSafeExitStore newStore() throws Exception {
        return new RendezvousSafeExitStore(RendezvousSafeExitStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session(dataSource), manager);
    }

    private SqlSessionTemplate session(JdbcDataSource source) throws Exception {
        Configuration config = new Configuration(new Environment("isolated-w16-exit", new SpringManagedTransactionFactory(), source));
        String path = "chengyinhub-system/src/main/resources/mapper/business/RendezvousSafeExitMapper.xml";
        try (InputStream input = Files.newInputStream(sourceRoot().resolve(path))) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }

    private static Path sourceRoot() {
        for (Path root = Paths.get("").toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_w16_safe_exit_solo_candidate.sql"))
                    && Files.isRegularFile(root.resolve("chengyinhub-system/src/main/resources/mapper/business/RendezvousSafeExitMapper.xml"))) return root;
        }
        throw new IllegalStateException("W16_EXIT_SOURCE_MISSING");
    }
}
