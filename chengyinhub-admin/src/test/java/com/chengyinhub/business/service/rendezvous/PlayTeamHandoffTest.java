package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamHandoffRequest;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.PlayTeamHandoffMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import com.chengyinhub.business.service.impl.PlayTeamServiceImpl;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Safety;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.View;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H2 only. Not a MySQL run and not a production dynamic-datasource proof. */
class PlayTeamHandoffTest {
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    SqlSessionTemplate session;
    IPlayTeamService service;
    PlayTeamServiceImpl target;
    PlayTeamBoundHandoff bound;
    Gate gate;
    RendezvousConsentService consent;
    RendezvousMeetingService meetings;
    final MutableClock clock = new MutableClock();
    final boolean[] failIm = new boolean[1];
    final AtomicBoolean safetyOpen = new AtomicBoolean(true);

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w16play_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(dataSource);
        Path root = RendezvousConsentPersistenceTest.sourceRoot();
        try (Connection connection = dataSource.getConnection()) {
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.consent(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_consent_persistence_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_rendezvous_protocol_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_handoff_recovery_candidate.sql"))));
            RendezvousH2Schema.apply(connection, RendezvousH2Schema.protocol(RendezvousH2Schema.read(
                    root.resolve("chengyinhub-admin/sql/migration_w16_play_team_handoff_candidate.sql"))));
        }
        jdbc.execute("CREATE TABLE play_team (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_type INT, owner_id BIGINT,"
                + " leader_member_id BIGINT, invite_code VARCHAR(64), title VARCHAR(60), join_mode INT, max_members INT,"
                + " joined_count INT, status INT, expire_time TIMESTAMP, create_time TIMESTAMP, update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE play_team_member (id BIGINT AUTO_INCREMENT PRIMARY KEY, team_id BIGINT, member_id BIGINT,"
                + " role INT, status INT, apply_message VARCHAR(200), apply_expire_time TIMESTAMP, create_time TIMESTAMP,"
                + " update_time TIMESTAMP, UNIQUE(team_id, member_id))");
        jdbc.execute("CREATE TABLE cms_registration (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, owner_type INT,"
                + " owner_id BIGINT, payment_status INT, registration_status INT)");
        jdbc.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, del_flag INT, end_date TIMESTAMP, cancel_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, nickname VARCHAR(40), avatar VARCHAR(255))");
        jdbc.execute("CREATE TABLE im_block (id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT, blocked_member_id BIGINT, create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE im_conversation (id BIGINT AUTO_INCREMENT PRIMARY KEY, type INT, biz_key VARCHAR(64),"
                + " last_msg_type INT, last_msg_text VARCHAR(500), last_msg_at TIMESTAMP, status INT, create_by VARCHAR(64),"
                + " create_time TIMESTAMP, update_by VARCHAR(64), update_time TIMESTAMP, remark VARCHAR(255))");
        jdbc.execute("CREATE TABLE im_conversation_member (id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT,"
                + " member_id BIGINT, role INT, unread_count INT, last_read_msg_id BIGINT, muted INT, deleted INT, create_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE im_message (id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT, status INT)");
        paid(1L); paid(2L);
        Configuration config = new Configuration(new Environment("w16-play", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAlias("ImBlock", ImBlock.class);
        config.getTypeAliasRegistry().registerAlias("ImConversation", ImConversation.class);
        config.getTypeAliasRegistry().registerAlias("ImConversationMember", ImConversationMember.class);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousProtocolMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousHandoffMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousMeetingEvidenceMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/PlayTeamMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/PlayTeamHandoffMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImBlockMapper.xml", true);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImConversationMapper.xml", true);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImConversationMemberMapper.xml", true);
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        manager = new DataSourceTransactionManager(dataSource);
        ImServiceImpl imTarget = new ImServiceImpl();
        ReflectionTestUtils.setField(imTarget, "conversationMapper", session.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(imTarget, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(imTarget, "playTeamMapper", session.getMapper(PlayTeamMapper.class));
        IImService realIm = proxy(imTarget);
        IImService im = mock(IImService.class);
        when(im.getOrCreateTeamGroup(any(), any())).thenAnswer(invocation -> {
            Long id = realIm.getOrCreateTeamGroup(invocation.getArgument(0), invocation.getArgument(1));
            if (failIm[0]) throw new IllegalStateException("IM_FAIL");
            return id;
        });
        target = new PlayTeamServiceImpl();
        ReflectionTestUtils.setField(target, "teamMapper", session.getMapper(PlayTeamMapper.class));
        ReflectionTestUtils.setField(target, "handoffMapper", session.getMapper(PlayTeamHandoffMapper.class));
        ReflectionTestUtils.setField(target, "blockMapper", session.getMapper(ImBlockMapper.class));
        ReflectionTestUtils.setField(target, "imService", im);
        CmsActivity activity = new CmsActivity();
        activity.setId(99L); activity.setTopicId(20L); activity.setStartDate(new Date(System.currentTimeMillis() + 86400000L));
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        when(activities.selectCmsActivityById(99L)).thenReturn(activity);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        CmsTopic topic = new CmsTopic(); topic.setTeamMode(1); topic.setTeamMaxMembers(4);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        service = proxy(target);
        bound = new PlayTeamBoundHandoff(service);
        gate = new Gate(PlayTeamHandoffAssembly.open(session, manager, bound));
        MyBatisRendezvousConsentStore consentStore = new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        MyBatisRendezvousMeetingStore meetingStore = new MyBatisRendezvousMeetingStore(
                MyBatisRendezvousMeetingStore.Mode.ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        Safety safety = new Safety() {
            public boolean eligible(long actor, String pool, String version, long now) { return true; }
            public boolean blocked(long a, long b) {
                Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM im_block WHERE (member_id=? AND blocked_member_id=?) OR (member_id=? AND blocked_member_id=?)",
                        Integer.class, a, b, b, a);
                return n != null && n > 0;
            }
            public boolean compatible(long a, long b, String pool, long now) { return true; }
            public boolean publicMeetingAllowed(String pool, String place, long now) { return "public-place".equals(place); }
        };
        consent = new RendezvousConsentService(consentStore, safety, clock, RendezvousConsentServiceTest.permitsPairing);
        meetings = new RendezvousMeetingService(RendezvousMeetingService.Mode.ISOLATED_CONTRACT_EVALUATION, meetingStore, safety,
                (m, t, a, p, n) -> safetyOpen.get(), new RendezvousHandoffLegacyTeamPort(), clock, 60_000, gate);
        failIm[0] = false;
        safetyOpen.set(true);
    }

    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }

    @Test void sameManagerAndMandatoryPropagation() throws Exception {
        Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        assertSame(dataSource, manager.getDataSource());
        assertSame(dataSource, environment.getDataSource());
        assertInstanceOf(SpringManagedTransactionFactory.class, environment.getTransactionFactory());
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        TransactionAttribute create = source.getTransactionAttribute(
                PlayTeamServiceImpl.class.getMethod("createForHandoff", PlayTeamHandoffRequest.class), PlayTeamServiceImpl.class);
        TransactionAttribute join = source.getTransactionAttribute(
                PlayTeamServiceImpl.class.getMethod("joinForHandoff", PlayTeamHandoffRequest.class), PlayTeamServiceImpl.class);
        TransactionAttribute im = source.getTransactionAttribute(
                ImServiceImpl.class.getMethod("getOrCreateTeamGroup", Long.class, Long.class), ImServiceImpl.class);
        assertEquals(TransactionDefinition.PROPAGATION_MANDATORY, create.getPropagationBehavior());
        assertEquals(TransactionDefinition.PROPAGATION_MANDATORY, join.getPropagationBehavior());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, im.getPropagationBehavior());
        assertNotEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, create.getPropagationBehavior());
        assertNotEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, im.getPropagationBehavior());
        assertInstanceOf(org.springframework.aop.SpringProxy.class, service);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class, () -> service.createForHandoff(request("bare")));
        assertEquals(0, count("play_team"));
    }

    @Test void concurrentSameRequestKeepsOneTeamAndDifferentContextConflicts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Long> first = pool.submit(() -> { start.await(); return inGuard(() -> service.createForHandoff(request("same"))).teamId; });
            Future<Long> second = pool.submit(() -> { start.await(); return inGuard(() -> service.createForHandoff(request("same"))).teamId; });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1, count("play_team"));
        assertEquals(1, count("w16_play_team_handoff"));
        assertEquals(1, count("im_conversation"));
        assertEquals(1, count("play_team_member"));
        PlayTeamHandoffRequest other = request("same");
        other.setPeerMemberId(3L);
        IPlayTeamService.TeamRejection conflict = assertThrows(IPlayTeamService.TeamRejection.class,
                () -> inGuard(() -> service.createForHandoff(other)));
        assertEquals("HANDOFF_CONTEXT_CONFLICT", conflict.getErrorCode());
        assertEquals(1, count("play_team"));
    }

    @Test void lostCreateAndJoinResponsesDoNotReserveAgain() {
        PlayTeamHandoffRequest.Fact created = inGuard(() -> service.createForHandoff(request("lost")));
        PlayTeamHandoffRequest.Fact again = inGuard(() -> service.createForHandoff(request("lost")));
        assertEquals(created.teamId, again.teamId);
        assertEquals(1, count("play_team_member"));
        PlayTeamHandoffRequest.Fact joined = inGuard(() -> service.joinForHandoff(request("lost")));
        int slots = jdbc.queryForObject("SELECT joined_count FROM play_team", Integer.class);
        PlayTeamHandoffRequest.Fact replay = inGuard(() -> service.joinForHandoff(request("lost")));
        assertEquals(PlayTeamHandoffRequest.Fact.Kind.JOINED, joined.kind);
        assertEquals(PlayTeamHandoffRequest.Fact.Kind.JOINED, replay.kind);
        assertEquals(slots, jdbc.queryForObject("SELECT joined_count FROM play_team", Integer.class));
        assertEquals(2, count("play_team_member"));
        for (Field field : PlayTeamHandoffRequest.Fact.class.getDeclaredFields()) {
            assertFalse(field.getName().toLowerCase().contains("invite"));
        }
    }

    @Test void imFailureRollsBackTeamMemberAndIndex() {
        failIm[0] = true;
        assertThrows(IllegalStateException.class, () -> inGuard(() -> service.createForHandoff(request("boom"))));
        assertEquals(0, count("play_team"));
        assertEquals(0, count("play_team_member"));
        assertEquals(0, count("w16_play_team_handoff"));
        assertEquals(0, count("im_conversation"));
    }

    @Test void lookupFailureDoesNotCreateAndEndedOrLeftDoesNotRejoin() {
        PlayTeamHandoffMapper broken = mock(PlayTeamHandoffMapper.class);
        when(broken.find(any(), any())).thenThrow(new RuntimeException("sql down"));
        ReflectionTestUtils.setField(target, "handoffMapper", broken);
        assertThrows(RuntimeException.class, () -> service.findHandoff(PlayTeamHandoffGateway.NAMESPACE, "missing"));
        assertEquals(0, count("play_team"));
        ReflectionTestUtils.setField(target, "handoffMapper", session.getMapper(PlayTeamHandoffMapper.class));

        assertEquals(PlayTeamHandoffRequest.Fact.Kind.ABSENT, service.findHandoff(PlayTeamHandoffGateway.NAMESPACE, "none").kind);
        PlayTeamHandoffRequest.Fact created = inGuard(() -> service.createForHandoff(request("life")));
        jdbc.update("INSERT INTO w16_play_team_handoff(namespace, request_id, match_id, leader_member_id, peer_member_id, activity_id, team_id, phase)"
                + " VALUES ('w16.meeting','ghost','m-ghost',1,2,99,424242,'TEAM_CREATED')");
        assertEquals(PlayTeamHandoffRequest.Fact.Kind.UNKNOWN, service.findHandoff(PlayTeamHandoffGateway.NAMESPACE, "ghost").kind);
        assertThrows(IPlayTeamService.TeamRejection.class, () -> inGuard(() -> service.createForHandoff(ghost())));
        assertEquals(1, count("play_team"));

        jdbc.update("UPDATE play_team SET status=? WHERE id=?", PlayTeam.STATUS_ENDED, created.teamId);
        assertEquals(PlayTeamHandoffRequest.Fact.Kind.ENDED, service.findHandoff(PlayTeamHandoffGateway.NAMESPACE, "life").kind);
        assertEquals(PlayTeamHandoffRequest.Fact.Kind.ENDED, inGuard(() -> service.joinForHandoff(request("life"))).kind);
        assertEquals(1, count("play_team_member"));

        jdbc.update("UPDATE play_team SET status=? WHERE id=?", PlayTeam.STATUS_RECRUITING, created.teamId);
        assertEquals(PlayTeamHandoffRequest.Fact.Kind.JOINED, inGuard(() -> service.joinForHandoff(request("life"))).kind);
        jdbc.update("UPDATE play_team_member SET status=? WHERE member_id=2", PlayTeamMember.STATUS_QUIT);
        PlayTeamHandoffRequest.Fact left = inGuard(() -> service.joinForHandoff(request("life")));
        assertEquals(PlayTeamHandoffRequest.Fact.Kind.LEFT, left.kind);
        assertFalse(left.peerActive);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM play_team_member WHERE member_id=2 AND status=1", Integer.class));
    }

    @Test void cancelBeforeCreateStoresNothingAndCancelAfterCreateBlocksJoin() {
        String[] ids = ready();
        gate.beforeLookup = () -> meetings.cancel(1, ids[0]);
        RendezvousMeetingService.View prevented = meetings.handoff(1, ids[0]);
        assertEquals(RendezvousMeetingService.State.CANCELLED, prevented.state);
        assertEquals("CANCELLED_PREVENTED", prevented.blocker);
        assertEquals(0, count("play_team"));
        assertEquals(0, count("im_conversation"));

        gate.beforeLookup = () -> {};
        String[] later = ready(3, 4);
        paid(3L); paid(4L);
        gate.afterCreate = () -> meetings.cancel(3, later[0]);
        RendezvousMeetingService.View kept = meetings.handoff(3, later[0]);
        assertEquals(RendezvousMeetingService.State.RECOVERY_REQUIRED, kept.state);
        assertEquals("CANCELLED_NOT_ROLLED_BACK", kept.blocker);
        assertNull(kept.teamId);
        assertEquals(1, count("play_team"));
        assertEquals(1, count("play_team_member"));
        assertEquals(1, count("im_conversation"));
    }

    @Test void revokeOrBlockBetweenCreateAndJoinDoesNotAddThePeer() {
        String[] ids = ready();
        gate.afterCreate = () -> safetyOpen.set(false);
        RendezvousMeetingService.View revoked = meetings.handoff(1, ids[0]);
        assertEquals(RendezvousMeetingService.State.RECOVERY_REQUIRED, revoked.state);
        assertEquals("SAFETY_REVOKED_NOT_ROLLED_BACK", revoked.blocker);
        assertEquals(1, count("play_team"));
        assertEquals(1, count("play_team_member"));

        safetyOpen.set(true);
        String[] blocked = ready(5, 6);
        paid(5L); paid(6L);
        gate.afterCreate = () -> jdbc.update("INSERT INTO im_block(member_id, blocked_member_id) VALUES (5,6)");
        RendezvousMeetingService.View stopped = meetings.handoff(5, blocked[0]);
        assertEquals(RendezvousMeetingService.State.RECOVERY_REQUIRED, stopped.state);
        assertEquals("SAFETY_REVOKED_NOT_ROLLED_BACK", stopped.blocker);
        assertEquals(2, count("play_team"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM play_team_member WHERE member_id=6 AND status=1", Integer.class));
    }

    @Test void handoffReplayUsesTheSameTeam() {
        String[] ids = ready();
        RendezvousMeetingService.View done = meetings.handoff(1, ids[0]);
        assertEquals(RendezvousMeetingService.State.COMPLETED, done.state);
        Long teamId = done.teamId;
        assertEquals(teamId, meetings.handoff(1, ids[0]).teamId);
        assertEquals(1, count("play_team"));
        assertEquals(2, count("play_team_member"));
        assertEquals(1, count("im_conversation"));
        assertEquals(1, count("w16_play_team_handoff"));
    }

    @Test void mismatchedBindingRefusesAdoptJoinAndPublishWithNoTeamWrite() {
        gate.replacement = RendezvousHandoffTeamPort.Lookup.joined(424242L);
        String[] forgedIds = ready();
        RendezvousMeetingService.View forged = meetings.handoff(1, forgedIds[0]);
        assertNotEquals(RendezvousMeetingService.State.COMPLETED, forged.state);
        assertNull(forged.teamId);
        assertEquals(0, count("play_team"));
        assertEquals(0, count("play_team_member"));
        assertEquals(0, count("im_conversation"));
        assertEquals(0, count("w16_play_team_handoff"));
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT phase FROM w16_handoff_request", String.class));
        assertNull(jdbc.queryForObject("SELECT team_id FROM w16_handoff_request", Long.class));

        gate.replacement = null;
        String[] ids = ready(3, 4);
        paid(3L); paid(4L);
        gate.beforeLookup = () -> {
            jdbc.update("INSERT INTO play_team (leader_member_id, invite_code, join_mode, max_members, joined_count, status) VALUES (8,'foreign',1,4,1,1)");
            Long foreign = jdbc.queryForObject("SELECT id FROM play_team", Long.class);
            jdbc.update("INSERT INTO play_team_member (team_id, member_id, role, status) VALUES (?,?,1,1)", foreign, 8L);
            jdbc.update("INSERT INTO play_team_member (team_id, member_id, role, status) VALUES (?,?,0,1)", foreign, 9L);
            String requestId = jdbc.queryForObject("SELECT request_id FROM w16_handoff_request WHERE first_actor=3", String.class);
            jdbc.update("INSERT INTO w16_play_team_handoff(namespace, request_id, match_id, leader_member_id, peer_member_id, activity_id, team_id, phase)"
                    + " VALUES (?,?,?,?,?,?,?,?)", PlayTeamHandoffGateway.NAMESPACE, requestId, "other-match", 8, 9, 99, foreign, "JOINED");
        };
        RendezvousMeetingService.View mismatched = meetings.handoff(3, ids[0]);
        assertNotEquals(RendezvousMeetingService.State.COMPLETED, mismatched.state);
        assertNull(mismatched.teamId);
        assertEquals(1, count("play_team"));
        assertEquals(2, count("play_team_member"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM play_team_member WHERE member_id IN (3,4)", Integer.class));
        assertEquals(0, count("im_conversation"));
        assertEquals(1, count("w16_play_team_handoff"));
        assertEquals("JOINED", jdbc.queryForObject("SELECT phase FROM w16_play_team_handoff", String.class));
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT phase FROM w16_handoff_request WHERE first_actor=3", String.class));
        assertNull(jdbc.queryForObject("SELECT team_id FROM w16_handoff_request WHERE first_actor=3", Long.class));
    }

    @Test void assemblyRefusesWrongManagerDatasourceAndMissingGuard() {
        IllegalStateException unmarked = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(manager).execute(status -> gate.create(null)));
        assertEquals("W16_GUARD_MISSING", unmarked.getMessage());
        DataSourceTransactionManager otherManager = new DataSourceTransactionManager(dataSource);
        IllegalStateException otherManagerCall = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(otherManager).execute(status -> gate.create(null)));
        assertEquals("W16_GUARD_MISSING", otherManagerCall.getMessage());
        JdbcDataSource otherSource = new JdbcDataSource();
        otherSource.setURL("jdbc:h2:mem:w16-other;MODE=MySQL;DB_CLOSE_DELAY=-1");
        DataSourceTransactionManager otherSourceManager = new DataSourceTransactionManager(otherSource);
        IllegalArgumentException wrongSource = assertThrows(IllegalArgumentException.class,
                () -> PlayTeamHandoffAssembly.open(session, otherSourceManager, bound));
        assertEquals("W16_TRANSACTION_RESOURCE_MISMATCH", wrongSource.getMessage());
        IllegalStateException runtimeOtherSource = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(otherSourceManager).execute(status -> gate.create(null)));
        assertEquals("W16_TRANSACTION_RESOURCE_MISMATCH", runtimeOtherSource.getMessage());
        Configuration bare = new Configuration(new Environment("bad", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        SqlSessionTemplate bad = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(bare));
        IllegalArgumentException wrongFactory = assertThrows(IllegalArgumentException.class,
                () -> PlayTeamHandoffAssembly.open(bad, manager, bound));
        assertEquals("W16_TRANSACTION_RESOURCE_MISMATCH", wrongFactory.getMessage());
        assertEquals(0, count("play_team"));
        assertEquals(0, count("play_team_member"));
        assertEquals(0, count("w16_play_team_handoff"));
        assertEquals(0, count("im_conversation"));
    }

    private String[] ready() { return ready(1, 2); }

    private String[] ready(long first, long second) {
        View a = consent.join(first, "pool", "v1", 100_000);
        View b = consent.join(second, "pool", "v1", 100_000);
        consent.propose(a.ticketId, b.ticketId, "public-place");
        consent.accept(first, a.ticketId, 1);
        consent.accept(second, b.ticketId, 2);
        jdbc.update("INSERT INTO w16_meeting_evidence(match_id, activity_id, place_id, first_actor, second_actor,"
                + " risk_clear, time_distance_plausible, merchant_required, merchant_verified, review_required,"
                + " review_approved, fact_source, rule_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                consent.read(first, a.ticketId).matchId, 99L, "public-place", first, second, 1, 1, 0, 0, 0, 0,
                "AUTHORITY_FACT", "evidence-v1");
        String challenge = meetings.issue(first, a.ticketId).challengeId;
        meetings.confirm(first, a.ticketId, challenge);
        meetings.confirm(second, b.ticketId, challenge);
        return new String[]{a.ticketId, b.ticketId};
    }

    private PlayTeamHandoffRequest request(String id) {
        PlayTeamHandoffRequest row = new PlayTeamHandoffRequest();
        row.setNamespace(PlayTeamHandoffGateway.NAMESPACE);
        row.setRequestId(id);
        row.setMatchId("match-" + id);
        row.setLeaderMemberId(1L);
        row.setPeerMemberId(2L);
        row.setActivityId(99L);
        return row;
    }

    private PlayTeamHandoffRequest ghost() {
        PlayTeamHandoffRequest row = request("ghost");
        row.setMatchId("m-ghost");
        return row;
    }

    private void paid(long member) {
        jdbc.update("INSERT INTO cms_registration(member_id, owner_type, owner_id, payment_status, registration_status) VALUES (?,?,?,?,?)",
                member, 2, 99, 2, 2);
    }

    private <T> T inGuard(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(manager).execute(status -> {
            Integer locked = session.getMapper(RendezvousConsentMapper.class).lockGuard();
            if (!Integer.valueOf(1).equals(locked)) throw new IllegalStateException("W16_GUARD_MISSING");
            return work.get();
        });
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private <T> T proxy(T targetObject) {
        ProxyFactory factory = new ProxyFactory(targetObject);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static void load(Configuration config, Path root, String path, boolean clock) throws Exception {
        String text = Files.readString(root.resolve(path));
        if (clock) text = text.replace("sysdate()", "CURRENT_TIMESTAMP");
        // H2 2.4 rejects MySQL IF() in this statement. The mapper file itself is unchanged.
        if (path.endsWith("PlayTeamMapper.xml")) {
            text = text.replace("if(joined_count >= max_members, 1, status)",
                            "CASE WHEN joined_count >= max_members THEN 1 ELSE status END")
                    .replace("if(status = 1, 0, status)",
                            "CASE WHEN status = 1 THEN 0 ELSE status END");
        }
        byte[] xml = text.getBytes(StandardCharsets.UTF_8);
        try (InputStream input = new ByteArrayInputStream(xml)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
    }

    static final class Gate implements PlayTeamHandoffGateway {
        final PlayTeamHandoffGateway inner;
        Runnable beforeLookup = () -> {};
        Runnable afterCreate = () -> {};
        RendezvousHandoffTeamPort.Lookup replacement;
        Gate(PlayTeamHandoffGateway inner) { this.inner = inner; }
        public boolean enabled() { return true; }
        public RendezvousHandoffTeamPort.Lookup lookup(String requestId) {
            beforeLookup.run();
            return replacement != null ? replacement : inner.lookup(requestId);
        }
        public long create(RendezvousHandoffRequest request) { return inner.create(request); }
        public boolean join(RendezvousHandoffRequest request) { return inner.join(request); }
        public void betweenCreateAndJoin() { afterCreate.run(); }
    }

    static final class MutableClock extends Clock {
        long now = 10_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
