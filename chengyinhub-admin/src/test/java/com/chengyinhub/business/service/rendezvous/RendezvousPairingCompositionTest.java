package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;
import com.chengyinhub.business.service.rendezvous.RendezvousConsentService.TicketState;
import com.chengyinhub.business.service.rendezvous.RendezvousPairingComposition.FormalMode;
import com.chengyinhub.business.service.rendezvous.RendezvousPairingComposition.Pairing;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import static com.chengyinhub.business.service.rendezvous.RendezvousPairingComposition.ENABLED_PROPERTY;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated H2. Not MySQL acceptance, and not a started scheduler. */
class RendezvousPairingCompositionTest {
    @Test void defaultOffRegistersNothingAndDoesNotSchedule() throws Exception {
        assertFalse(RendezvousPairingComposition.DEFAULT_ENABLED);
        assertEquals(FormalMode.UNCONTRACTED, RendezvousPairingComposition.FORMAL_MODE);
        assertEquals("UNCONTRACTED", RendezvousPairingComposition.FACT_PROVIDER);
        Method factory = RendezvousPairingComposition.class.getDeclaredMethod("rendezvousPairing",
                DataSource.class, SqlSessionTemplate.class, DataSourceTransactionManager.class);
        assertNotNull(factory.getAnnotation(Bean.class));
        ConditionalOnProperty condition = factory.getAnnotation(ConditionalOnProperty.class);
        assertEquals(ENABLED_PROPERTY, condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
        assertEquals(1, java.util.Arrays.stream(RendezvousPairingComposition.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class)).count());
        assertEquals(0, java.util.Arrays.stream(RendezvousPairingComposition.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class)).count());
        String source = Files.readString(sourceRoot().resolve(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/rendezvous/RendezvousPairingComposition.java"));
        assertFalse(source.contains("UmsMember") || source.contains("birthday") || source.contains("areaId") || source.contains("area_id"));
        assertFalse(source.contains("pairOnce"));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(RendezvousPairingComposition.class)) {
            assertTrue(context.getBeansOfType(Pairing.class).isEmpty());
            assertTrue(context.getBeansOfType(RendezvousPoolScheduler.class).isEmpty());
        }
    }

    @Test void missingDependencyStaysClosed() {
        try (AnnotationConfigApplicationContext context = enabled()) {
            context.register(RendezvousPairingComposition.class);
            Exception failure = assertThrows(Exception.class, context::refresh);
            assertTrue(causedBy(failure, NoSuchBeanDefinitionException.class));
            assertFalse(context.isActive());
        }
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> RendezvousPairingComposition.formal(null, null, null));
        assertEquals("W16_PAIRING_DEPENDENCY_MISSING", missing.getMessage());
    }

    @Test void differentDatabaseAndMissingMapperAreRejected() throws Exception {
        try (Fixture left = new Fixture(); Fixture right = new Fixture()) {
            IllegalStateException foreign = assertThrows(IllegalStateException.class, () -> RendezvousPairingComposition.evaluation(
                    left.dataSource, left.session, right.manager, left.clock));
            assertEquals("W16_PAIRING_RESOURCE_MISMATCH", foreign.getMessage());
            Configuration jdbcTx = new Configuration(new Environment("jdbc-tx", new JdbcTransactionFactory(), left.dataSource));
            SqlSessionTemplate unmanaged = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(jdbcTx));
            IllegalStateException factory = assertThrows(IllegalStateException.class, () -> RendezvousPairingComposition.formal(
                    left.dataSource, unmanaged, left.manager));
            assertEquals("W16_PAIRING_RESOURCE_MISMATCH", factory.getMessage());
            Configuration bareConfig = new Configuration(new Environment("no-mapper", new SpringManagedTransactionFactory(), left.dataSource));
            SqlSessionTemplate bare = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(bareConfig));
            IllegalStateException absent = assertThrows(IllegalStateException.class, () -> RendezvousPairingComposition.formal(
                    left.dataSource, bare, left.manager));
            assertEquals("W16_PAIRING_MAPPER_MISSING", absent.getMessage());
            assertEquals(0, left.count("w16_rendezvous_match"));
            assertEquals(0, left.count("w16_contact_event"));
        }
    }

    @Test void formalModeDoesNotStartTheScheduler() throws Exception {
        try (Fixture fx = new Fixture(); AnnotationConfigApplicationContext context = enabled()) {
            IllegalStateException direct = assertThrows(IllegalStateException.class, () -> RendezvousPairingComposition.formal(
                    fx.dataSource, fx.session, fx.manager));
            assertEquals("W16_PAIRING_FORMAL_MODE_UNCONTRACTED", direct.getMessage());
            context.registerBean(DataSource.class, () -> fx.dataSource);
            context.registerBean(SqlSessionTemplate.class, () -> fx.session);
            context.registerBean(DataSourceTransactionManager.class, () -> fx.manager);
            context.register(RendezvousPairingComposition.class);
            Exception failure = assertThrows(Exception.class, context::refresh);
            assertTrue(messageChain(failure, "W16_PAIRING_FORMAL_MODE_UNCONTRACTED"));
            assertEquals(0, fx.count("w16_rendezvous_match"));
            assertEquals(0, fx.count("w16_policy_version"));
            assertEquals(0, fx.count("w16_eligibility_fact"));
            assertEquals(0, fx.count("w16_contact_quota"));
        }
    }

    @Test void missingRulesFactsAndQuotaRefuseWithoutAPair() throws Exception {
        try (Fixture fx = new Fixture()) {
            Pairing pairing = RendezvousPairingComposition.evaluation(fx.dataSource, fx.session, fx.manager, fx.clock);
            assertEquals(0, fx.count("w16_rendezvous_match"));
            assertEquals("NOT_ELIGIBLE", assertThrows(Rejection.class, () -> pairing.consent.join(1, "pool", "v1", 100_000)).getCode());
            fx.rules();
            assertEquals("NOT_ELIGIBLE", assertThrows(Rejection.class, () -> pairing.consent.join(1, "pool", "v1", 100_000)).getCode());
            fx.fact(1, 20); fx.fact(2, 21);
            pairing.consent.join(1, "pool", "v1", 100_000);
            pairing.consent.join(2, "pool", "v1", 100_000);
            assertEquals("POLICY_CLOSED", assertThrows(Rejection.class, () -> pairing.scheduler.pairOnce("pool")).getCode());
            assertEquals(0, fx.count("w16_rendezvous_match"));
            assertEquals(0, fx.count("w16_match_reservation"));
            assertEquals(0, fx.count("w16_contact_event"));
            assertEquals(TicketState.WAITING, pairing.consent.current(1).ticketState);
            assertEquals(TicketState.WAITING, pairing.consent.current(2).ticketState);
        }
    }

    @Test void evaluationChargesBothAndRollsBackTheSecondFailure() throws Exception {
        try (Fixture fx = new Fixture()) {
            Pairing pairing = RendezvousPairingComposition.evaluation(fx.dataSource, fx.session, fx.manager, fx.clock);
            fx.rules(); fx.fact(1, 20); fx.fact(2, 21); fx.quota(2);
            pairing.consent.join(1, "pool", "v1", 100_000);
            pairing.consent.join(2, "pool", "v1", 100_000);
            String match = pairing.scheduler.pairOnce("pool");
            assertEquals(1, fx.count("w16_rendezvous_match"));
            assertEquals(2, fx.count("w16_match_reservation"));
            assertEquals(2, fx.jdbc.queryForObject("SELECT COUNT(*) FROM w16_contact_event WHERE match_id=?", Integer.class, match));
            assertEquals(fx.clock.now, fx.jdbc.queryForObject(
                    "SELECT charged_at FROM w16_contact_event WHERE match_id=? AND actor_id=1", Long.class, match));
            assertEquals(fx.clock.now, fx.jdbc.queryForObject(
                    "SELECT charged_at FROM w16_contact_event WHERE match_id=? AND actor_id=2", Long.class, match));

            fx.jdbc.update("DELETE FROM w16_contact_event");
            fx.jdbc.update("DELETE FROM w16_match_reservation");
            fx.jdbc.update("DELETE FROM w16_rendezvous_match");
            fx.jdbc.update("UPDATE w16_match_ticket SET state='CANCELLED', match_id=NULL");
            fx.jdbc.update("UPDATE w16_contact_quota SET max_contacts=1 WHERE pool='pool'");
            fx.jdbc.update("INSERT INTO w16_contact_event(match_id, actor_id, pool, scope, charged_at) VALUES ('seed',1,'pool','POOL',?)", fx.clock.now);
            pairing.consent.join(1, "pool", "v1", 100_000);
            pairing.consent.join(2, "pool", "v1", 100_000);
            assertEquals("PAIR_UNAVAILABLE", assertThrows(Rejection.class, () -> pairing.scheduler.pairOnce("pool")).getCode());
            assertEquals(0, fx.count("w16_rendezvous_match"));
            assertEquals(0, fx.count("w16_match_reservation"));
            assertEquals(1, fx.count("w16_contact_event"));
            assertEquals(TicketState.WAITING, pairing.consent.current(1).ticketState);
            assertEquals(TicketState.WAITING, pairing.consent.current(2).ticketState);
        }
    }

    private static AnnotationConfigApplicationContext enabled() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("w16-pairing",
                java.util.Collections.singletonMap(ENABLED_PROPERTY, "true")));
        return context;
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) if (type.isInstance(cursor)) return true;
        return false;
    }

    private static boolean messageChain(Throwable error, String token) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor.getMessage() != null && cursor.getMessage().contains(token)) return true;
        }
        return false;
    }

    private static Path sourceRoot() {
        for (Path root = Paths.get("").toAbsolutePath(); root != null; root = root.getParent()) {
            if (Files.isRegularFile(root.resolve("chengyinhub-admin/sql/migration_w16_eligibility_contact_candidate.sql"))) return root;
        }
        throw new IllegalStateException("W16_SOURCE_FIXTURE_ROOT_MISSING");
    }

    static final class Fixture implements AutoCloseable {
        final JdbcDataSource dataSource = new JdbcDataSource();
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager manager;
        final SqlSessionTemplate session;
        final RendezvousProtocolTest.MutableClock clock = new RendezvousProtocolTest.MutableClock();

        Fixture() throws Exception {
            dataSource.setURL("jdbc:h2:mem:w16pair_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            jdbc = new JdbcTemplate(dataSource);
            Path root = sourceRoot();
            try (Connection connection = dataSource.getConnection()) {
                RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                        root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
                RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                        root.resolve("chengyinhub-admin/sql/migration_w16_rendezvous_protocol_candidate.sql"))));
                RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                        root.resolve("chengyinhub-admin/sql/migration_w16_eligibility_contact_candidate.sql"))));
            }
            jdbc.execute("CREATE TABLE im_block(id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, blocked_member_id BIGINT NOT NULL, create_time TIMESTAMP, UNIQUE(member_id, blocked_member_id))");
            Configuration config = new Configuration(new Environment("w16-pairing", new SpringManagedTransactionFactory(), dataSource));
            config.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
            for (String path : new String[]{
                    "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml",
                    "chengyinhub-system/src/main/resources/mapper/business/RendezvousProtocolMapper.xml",
                    "chengyinhub-system/src/main/resources/mapper/business/RendezvousEligibilityMapper.xml",
                    "chengyinhub-system/src/main/resources/mapper/business/RendezvousContactMapper.xml",
                    "chengyinhub-system/src/main/resources/mapper/business/ImBlockMapper.xml"}) {
                try (InputStream input = Files.newInputStream(root.resolve(path))) {
                    new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
                }
            }
            session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            manager = new DataSourceTransactionManager(dataSource);
        }

        void rules() {
            for (String dimension : new String[]{"eligibility-source", "age", "region", "time-window"}) {
                jdbc.update("INSERT INTO w16_policy_version(dimension, version_id, decision) VALUES (?, 'v1', 'OPEN')", dimension);
            }
            jdbc.update("INSERT INTO w16_policy_version(dimension, version_id, decision, ticket_ttl_millis) VALUES ('ticket-ttl','v1','OPEN',100000)");
            jdbc.update("INSERT INTO w16_policy_version(dimension, version_id, decision, max_contacts) VALUES ('frequency','pool','OPEN',2)");
            jdbc.update("INSERT INTO w16_policy_version(dimension, version_id, decision, public_place) VALUES ('meeting-safety','pool','OPEN','public-place')");
            jdbc.update("INSERT INTO w16_eligibility_current(id, version_id) VALUES (1, 'v1')");
            jdbc.update("INSERT INTO w16_eligibility_rule(version_id, decision, min_age, max_age, region_code, window_start, window_end) VALUES ('v1','OPEN',18,NULL,'CN-SH',0,100000)");
        }

        void fact(long actor, int age) {
            jdbc.update("INSERT INTO w16_eligibility_fact(actor_id, rule_version, age_years, region_code, reviewed_at, expires_at) VALUES (?,?,?,?,?,?)",
                    actor, "v1", age, "CN-SH", 1, 80_000);
        }

        void quota(int max) {
            jdbc.update("INSERT INTO w16_contact_quota(pool, scope, window_millis, max_contacts, decision) VALUES ('pool','POOL',100000,?,'OPEN')", max);
        }

        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

        public void close() { jdbc.execute("SHUTDOWN"); }
    }
}
