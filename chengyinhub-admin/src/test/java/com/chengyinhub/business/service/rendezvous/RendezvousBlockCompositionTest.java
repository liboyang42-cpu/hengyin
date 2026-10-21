package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static com.chengyinhub.business.service.rendezvous.RendezvousBlockComposition.ENABLED_PROPERTY;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Container assembly for the default-off block composition. Consent uses the charging constructor. */
class RendezvousBlockCompositionTest {
    @Test void defaultOffRegistersNoEntryAndKeepsTheOldBlock() throws Exception {
        assertFalse(RendezvousBlockComposition.DEFAULT_ENABLED);
        assertEquals(RendezvousBlockComposition.FormalMode.UNCONTRACTED, RendezvousBlockComposition.FORMAL_MODE);
        Method factory = RendezvousBlockComposition.class.getDeclaredMethod("rendezvousBlockEntry", DataSource.class,
                SqlSessionTemplate.class, DataSourceTransactionManager.class, ImBlockMapper.class, RendezvousConsentService.class);
        assertNotNull(factory.getAnnotation(Bean.class));
        assertEquals(1, java.util.Arrays.stream(RendezvousBlockComposition.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class)).count());
        var condition = factory.getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertEquals(ENABLED_PROPERTY, condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
        Autowired injection = ImServiceImpl.class.getMethod("setRendezvousBlock", RendezvousBlockEntry.class).getAnnotation(Autowired.class);
        assertNotNull(injection);
        assertFalse(injection.required());

        try (Fixture fx = new Fixture();
                AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(RendezvousBlockComposition.class)) {
            assertTrue(context.getBeansOfType(RendezvousBlockEntry.class).isEmpty());
            String[] pair = fx.mutual(1, 2);
            String matchId = fx.consent.read(1, pair[0]).matchId;
            CountingBlocks counting = new CountingBlocks(fx.blocks);
            ImServiceImpl im = new ImServiceImpl();
            ReflectionTestUtils.setField(im, "blockMapper", counting);
            im.block(1L, 2L);
            assertEquals(1, counting.inserts);
            assertEquals("MUTUAL_ACCEPTED", fx.state(matchId));
            im.setRendezvousBlock(RendezvousBlockEntry.disabled());
            im.block(1L, 2L);
            assertEquals(2, counting.inserts);
            assertEquals("MUTUAL_ACCEPTED", fx.state(matchId));
        }
    }

    @Test void oneEntryIsInjectableAndASecondIsRejected() {
        try (AnnotationConfigApplicationContext single = new AnnotationConfigApplicationContext()) {
            single.register(RendezvousBlockComposition.class);
            single.registerBean(RendezvousBlockEntry.class, RendezvousBlockEntry::disabled);
            single.register(OneEntry.class);
            single.refresh();
            assertEquals(1, single.getBeansOfType(RendezvousBlockEntry.class).size());
            assertFalse(single.getBean(OneEntry.class).entry.connected());
        }
        try (AnnotationConfigApplicationContext doubled = new AnnotationConfigApplicationContext()) {
            doubled.registerBean("first", RendezvousBlockEntry.class, RendezvousBlockEntry::disabled);
            doubled.registerBean("second", RendezvousBlockEntry.class, RendezvousBlockEntry::disabled);
            doubled.register(OneEntry.class);
            Exception failure = assertThrows(Exception.class, doubled::refresh);
            assertTrue(causedBy(failure, NoUniqueBeanDefinitionException.class));
        }
    }

    @Test void missingDependencyDoesNotPublishAConnectedEntry() throws Exception {
        try (AnnotationConfigApplicationContext context = enabled()) {
            context.register(RendezvousBlockComposition.class);
            Exception failure = assertThrows(Exception.class, context::refresh);
            assertTrue(causedBy(failure, NoSuchBeanDefinitionException.class));
            assertFalse(context.isActive());
        }
        try (Fixture fx = new Fixture()) {
            IllegalStateException missing = assertThrows(IllegalStateException.class, () -> RendezvousBlockComposition.formal(
                    fx.authority, fx.session, fx.manager, fx.blocks, null));
            assertEquals("W16_BLOCK_DEPENDENCY_MISSING", missing.getMessage());
        }
    }

    @Test void differentDatabaseIsRejected() throws Exception {
        try (Fixture left = new Fixture(); Fixture right = new Fixture(); AnnotationConfigApplicationContext context = enabled()) {
            context.registerBean(DataSource.class, () -> left.authority);
            context.registerBean(SqlSessionTemplate.class, () -> left.session);
            context.registerBean(DataSourceTransactionManager.class, () -> right.manager);
            context.registerBean(ImBlockMapper.class, () -> left.blocks);
            context.registerBean(RendezvousConsentService.class, () -> left.consent);
            context.register(RendezvousBlockComposition.class);
            Exception failure = assertThrows(Exception.class, context::refresh);
            assertTrue(messageChain(failure, "W16_BLOCK_RESOURCE_MISMATCH"));
            IllegalStateException foreign = assertThrows(IllegalStateException.class, () -> RendezvousBlockComposition.evaluation(
                    left.authority, left.session, left.manager, right.blocks, left.consent));
            assertEquals("W16_BLOCK_MAPPER_MISMATCH", foreign.getMessage());
            IllegalStateException fake = assertThrows(IllegalStateException.class, () -> RendezvousBlockComposition.formal(
                    left.authority, left.session, left.manager, new FakeBlocks(), left.consent));
            assertEquals("W16_BLOCK_MAPPER_MISMATCH", fake.getMessage());
            SqlSessionTemplate bare = session(left.authority, false);
            IllegalStateException absent = assertThrows(IllegalStateException.class, () -> RendezvousBlockComposition.formal(
                    left.authority, bare, left.manager, left.blocks, left.consent));
            assertEquals("W16_BLOCK_MAPPER_MISSING", absent.getMessage());
            assertEquals(0, left.count("im_block"));
        }
    }

    @Test void formalModeDoesNotRegisterAnIsolatedEntry() throws Exception {
        try (Fixture fx = new Fixture()) {
            IllegalStateException direct = assertThrows(IllegalStateException.class, () -> RendezvousBlockComposition.formal(
                    fx.authority, fx.session, fx.manager, fx.blocks, fx.consent));
            assertEquals("W16_BLOCK_FORMAL_MODE_UNCONTRACTED", direct.getMessage());
            try (AnnotationConfigApplicationContext context = enabled()) {
                registerConsistent(context, fx);
                context.register(RendezvousBlockComposition.class);
                Exception failure = assertThrows(Exception.class, context::refresh);
                assertTrue(messageChain(failure, "W16_BLOCK_FORMAL_MODE_UNCONTRACTED"));
                assertFalse(context.isActive());
            }
        }
    }

    @Test void evaluationContainerWritesOnceAndRollsBack() throws Exception {
        try (Fixture fx = new Fixture(); AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RendezvousBlockComposition.class);
            context.registerBean(RendezvousBlockEntry.class, () -> RendezvousBlockComposition.evaluation(
                    fx.authority, fx.session, fx.manager, fx.blocks, fx.consent));
            context.refresh();
            assertEquals(1, context.getBeansOfType(RendezvousBlockEntry.class).size());
            RendezvousBlockEntry entry = context.getBean(RendezvousBlockEntry.class);
            assertTrue(entry.connected());
            String[] pair = fx.mutual(1, 2);
            String matchId = fx.consent.read(1, pair[0]).matchId;
            CountingBlocks counting = new CountingBlocks(fx.blocks);
            ImServiceImpl im = new ImServiceImpl();
            im.setRendezvousBlock(entry);
            ReflectionTestUtils.setField(im, "blockMapper", counting);
            im.block(1L, 2L);
            assertEquals(0, counting.inserts);
            assertEquals(1, fx.count("im_block"));
            assertEquals("SAFETY_STOPPED", fx.state(matchId));
            assertNotNull(fx.blocks.selectOne(1L, 2L));
            String[] other = fx.mutual(3, 4);
            String otherId = fx.consent.read(3, other[0]).matchId;
            fx.jdbc.update("UPDATE w16_rendezvous_match SET first_ticket=? WHERE id=?", "corrupt-ticket", otherId);
            Rejection corrupt = assertThrows(Rejection.class, () -> im.block(3L, 4L));
            assertEquals("CORRUPT_RESERVATION", corrupt.getCode());
            assertEquals(0, counting.inserts);
            assertEquals(0, fx.jdbc.queryForObject("SELECT COUNT(*) FROM im_block WHERE member_id=3 AND blocked_member_id=4", Integer.class));
            assertEquals("MUTUAL_ACCEPTED", fx.state(otherId));
            assertEquals(1, fx.count("im_block"));
        }
    }

    private static void registerConsistent(AnnotationConfigApplicationContext context, Fixture fx) {
        context.registerBean(DataSource.class, () -> fx.authority);
        context.registerBean(SqlSessionTemplate.class, () -> fx.session);
        context.registerBean(DataSourceTransactionManager.class, () -> fx.manager);
        context.registerBean(ImBlockMapper.class, () -> fx.blocks);
        context.registerBean(RendezvousConsentService.class, () -> fx.consent);
    }

    private static AnnotationConfigApplicationContext enabled() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("w16-block", Map.of(ENABLED_PROPERTY, "true")));
        return context;
    }

    private static boolean causedBy(Throwable error, Class<?> type) {
        while (error != null) {
            if (type.isInstance(error)) return true;
            error = error.getCause();
        }
        return false;
    }

    private static boolean messageChain(Throwable error, String code) {
        while (error != null) {
            if (code.equals(error.getMessage())) return true;
            error = error.getCause();
        }
        return false;
    }

    private static SqlSessionTemplate session(DataSource dataSource, boolean blockMapper) throws Exception {
        Configuration config = new Configuration(new Environment("w16-bare", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
        Path root = RendezvousConsentPersistenceTest.sourceRoot();
        if (blockMapper) load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImBlockMapper.xml", true);
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }

    private static void load(Configuration config, Path root, String path, boolean clock) throws Exception {
        byte[] xml = Files.readAllBytes(root.resolve(path));
        if (clock) xml = new String(xml, StandardCharsets.UTF_8).replace("sysdate()", "CURRENT_TIMESTAMP").getBytes(StandardCharsets.UTF_8);
        try (InputStream input = new ByteArrayInputStream(xml)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
    }

    static final class OneEntry {
        final RendezvousBlockEntry entry;
        @Autowired OneEntry(RendezvousBlockEntry entry) { this.entry = entry; }
    }

    static final class Fixture implements AutoCloseable {
        final JdbcDataSource authority;
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager manager;
        final SqlSessionTemplate session;
        final ImBlockMapper blocks;
        final RendezvousConsentService consent;
        final RecordingAdmission charges = new RecordingAdmission();
        Fixture() throws Exception {
            authority = new JdbcDataSource();
            authority.setURL("jdbc:h2:mem:w16compose_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            jdbc = new JdbcTemplate(authority);
            Path root = RendezvousConsentPersistenceTest.sourceRoot();
            try (Connection connection = authority.getConnection()) {
                RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                        root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
            }
            jdbc.execute("CREATE TABLE im_block(id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, blocked_member_id BIGINT NOT NULL, create_time TIMESTAMP, UNIQUE(member_id, blocked_member_id))");
            Configuration config = new Configuration(new Environment("w16-compose", new SpringManagedTransactionFactory(), authority));
            config.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
            load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml", false);
            load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImBlockMapper.xml", true);
            session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            manager = new DataSourceTransactionManager(authority);
            blocks = session.getMapper(ImBlockMapper.class);
            consent = new RendezvousConsentService(new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager),
                    new BlockSafety(blocks), new MutableClock(), charges);
        }
        String[] mutual(long first, long second) {
            View a = consent.join(first, "pool", "v1", 100_000);
            View b = consent.join(second, "pool", "v1", 100_000);
            int before = charges.charges;
            consent.propose(a.ticketId, b.ticketId, "public-place");
            assertEquals(before + 1, charges.charges);
            consent.accept(first, a.ticketId, 1);
            consent.accept(second, b.ticketId, 2);
            return new String[]{a.ticketId, b.ticketId};
        }
        String state(String matchId) {
            return jdbc.queryForObject("SELECT state FROM w16_rendezvous_match WHERE id=?", String.class, matchId);
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        public void close() { jdbc.execute("SHUTDOWN"); }
    }

    static final class RecordingAdmission implements RendezvousConsentService.Admission {
        int charges;
        public void charge(String matchId, long first, long second, String pool, long now) {
            if (matchId == null || matchId.trim().isEmpty() || pool == null || pool.trim().isEmpty()
                    || first <= 0 || second <= 0 || first == second || now < 0) throw new Rejection("PAIR_UNAVAILABLE");
            charges++;
        }
    }

    static final class CountingBlocks implements ImBlockMapper {
        final ImBlockMapper real;
        int inserts;
        CountingBlocks(ImBlockMapper real) { this.real = real; }
        public int insertImBlock(ImBlock block) { inserts++; return real.insertImBlock(block); }
        public int deleteImBlock(Long memberId, Long blockedMemberId) { return real.deleteImBlock(memberId, blockedMemberId); }
        public ImBlock selectOne(Long memberId, Long blockedMemberId) { return real.selectOne(memberId, blockedMemberId); }
        public int existsBetween(Long a, Long b) { return real.existsBetween(a, b); }
    }

    static final class FakeBlocks implements ImBlockMapper {
        public int insertImBlock(ImBlock block) { throw new IllegalStateException("fake"); }
        public int deleteImBlock(Long memberId, Long blockedMemberId) { throw new IllegalStateException("fake"); }
        public ImBlock selectOne(Long memberId, Long blockedMemberId) { throw new IllegalStateException("fake"); }
        public int existsBetween(Long a, Long b) { throw new IllegalStateException("fake"); }
    }

    static final class BlockSafety implements Safety {
        final ImBlockMapper blocks;
        BlockSafety(ImBlockMapper blocks) { this.blocks = blocks; }
        public boolean eligible(long actor, String pool, String version, long now) { return true; }
        public boolean blocked(long a, long b) { return blocks.existsBetween(a, b) > 0; }
        public boolean compatible(long a, long b, String pool, long now) { return true; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return "public-place".equals(place); }
    }

    static final class MutableClock extends Clock {
        long now = 10_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
