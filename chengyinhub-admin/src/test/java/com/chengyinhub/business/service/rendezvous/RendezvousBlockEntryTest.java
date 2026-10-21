package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.test.util.ReflectionTestUtils;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real {@link ImServiceImpl#block} entry. The consent service uses the charging constructor. */
class RendezvousBlockEntryTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    SqlSessionTemplate session;
    RendezvousConsentService consent;
    RecordingAdmission charges;
    ImServiceImpl im;
    CountingBlocks blocks;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16entry_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(dataSource);
        Path root = RendezvousConsentPersistenceTest.sourceRoot();
        try (Connection connection = dataSource.getConnection()) {
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
        }
        jdbc.execute("CREATE TABLE im_block(id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, blocked_member_id BIGINT NOT NULL, create_time TIMESTAMP, UNIQUE(member_id, blocked_member_id))");
        Configuration config = new Configuration(new Environment("w16-entry", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImBlockMapper.xml", true);
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        manager = new DataSourceTransactionManager(dataSource);
        MyBatisRendezvousConsentStore store = new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        charges = new RecordingAdmission();
        consent = new RendezvousConsentService(store, new BlockSafety(session.getMapper(ImBlockMapper.class)), new MutableClock(), charges);
        blocks = new CountingBlocks(session.getMapper(ImBlockMapper.class));
        im = new ImServiceImpl();
        ReflectionTestUtils.setField(im, "blockMapper", blocks);
    }

    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }

    @Test void missingOrDisabledEntryKeepsTheOldBlockAndDoesNotStopTheMatch() {
        String[] pair = mutual(1, 2);
        String matchId = consent.read(1, pair[0]).matchId;
        im.block(1L, 2L);
        assertEquals(1, blocks.inserts);
        assertEquals("MUTUAL_ACCEPTED", state(matchId));
        assertBlock(1, 2);
        im.unblock(1L, 2L);
        assertEquals(0, count("im_block"));

        ReflectionTestUtils.setField(im, "rendezvousBlock", RendezvousBlockEntry.disabled());
        String[] again = mutual(3, 4);
        String other = consent.read(3, again[0]).matchId;
        int inserts = blocks.inserts;
        im.block(3L, 4L);
        assertEquals(inserts + 1, blocks.inserts);
        assertEquals("MUTUAL_ACCEPTED", state(other));
        assertFalse(RendezvousBlockEntry.open(MyBatisRendezvousConsentStore.Mode.DISABLED, session, manager, consent).connected());
        assertFalse(RendezvousBlockEntry.open(ISOLATED_PERSISTENCE_EVALUATION, session, manager, null).connected());
    }

    @Test void connectedEntryStopsReadsBackAndUnblockDoesNotRevive() {
        String[] pair = mutual(1, 2);
        String[] other = mutual(3, 4);
        String matchId = consent.read(1, pair[0]).matchId;
        String otherId = consent.read(3, other[0]).matchId;
        RendezvousBlockEntry entry = RendezvousBlockEntry.open(ISOLATED_PERSISTENCE_EVALUATION, session, manager, consent);
        assertTrue(entry.connected());
        ReflectionTestUtils.setField(im, "rendezvousBlock", entry);
        im.block(1L, 2L);
        assertEquals(0, blocks.inserts);
        assertEquals("SAFETY_STOPPED", state(matchId));
        assertEquals("MUTUAL_ACCEPTED", state(otherId));
        assertBlock(1, 2);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM w16_match_reservation r JOIN w16_match_ticket t ON t.id=r.ticket_id WHERE t.actor_id IN (1,2)",
                Integer.class));
        im.block(1L, 2L);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM im_block WHERE member_id=1 AND blocked_member_id=2", Integer.class));
        im.block(2L, 1L);
        assertEquals(2, count("im_block"));
        assertEquals("SAFETY_STOPPED", state(matchId));
        assertEquals("MUTUAL_ACCEPTED", state(otherId));
        assertEquals(MatchState.SAFETY_STOPPED, consent.accept(1, pair[0], 99).matchState);
        im.unblock(1L, 2L);
        im.unblock(2L, 1L);
        assertEquals(0, count("im_block"));
        assertEquals("SAFETY_STOPPED", state(matchId));
        assertEquals(MatchState.SAFETY_STOPPED, consent.read(2, pair[1]).matchState);
        assertNull(consent.read(1, pair[0]).publicPlace);
        assertEquals(0, blocks.inserts);
    }

    @Test void connectedFailureRollsBackTheBlockRow() {
        String[] pair = mutual(1, 2);
        String matchId = consent.read(1, pair[0]).matchId;
        ReflectionTestUtils.setField(im, "rendezvousBlock",
                RendezvousBlockEntry.open(ISOLATED_PERSISTENCE_EVALUATION, session, manager, consent));
        jdbc.update("UPDATE w16_rendezvous_match SET first_ticket=? WHERE id=?", "corrupt-ticket", matchId);
        Rejection corrupt = assertThrows(Rejection.class, () -> im.block(1L, 2L));
        assertEquals("CORRUPT_RESERVATION", corrupt.getCode());
        assertEquals(0, count("im_block"));
        assertEquals(0, blocks.inserts);
        assertEquals("MUTUAL_ACCEPTED", state(matchId));
        assertEquals(2, count("w16_match_reservation"));
    }

    private void assertBlock(long actor, long other) {
        ImBlock row = session.getMapper(ImBlockMapper.class).selectOne(actor, other);
        assertNotNull(row);
        assertEquals(Long.valueOf(actor), row.getMemberId());
        assertEquals(Long.valueOf(other), row.getBlockedMemberId());
        assertTrue(session.getMapper(ImBlockMapper.class).existsBetween(actor, other) > 0);
    }

    private String[] mutual(long first, long second) {
        View a = consent.join(first, "pool", "v1", 100_000);
        View b = consent.join(second, "pool", "v1", 100_000);
        int before = charges.charges;
        consent.propose(a.ticketId, b.ticketId, "public-place");
        assertEquals(before + 1, charges.charges);
        consent.accept(first, a.ticketId, 1);
        consent.accept(second, b.ticketId, 2);
        return new String[]{a.ticketId, b.ticketId};
    }

    private String state(String matchId) {
        return jdbc.queryForObject("SELECT state FROM w16_rendezvous_match WHERE id=?", String.class, matchId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void load(Configuration config, Path root, String path, boolean clock) throws Exception {
        byte[] xml = Files.readAllBytes(root.resolve(path));
        if (clock) xml = new String(xml, StandardCharsets.UTF_8).replace("sysdate()", "CURRENT_TIMESTAMP").getBytes(StandardCharsets.UTF_8);
        try (InputStream input = new ByteArrayInputStream(xml)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
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
