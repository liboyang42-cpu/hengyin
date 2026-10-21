package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImBlock;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamHandoffRequest;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.mapper.PlayTeamHandoffMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import com.chengyinhub.business.service.impl.PlayTeamServiceImpl;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.controller.api.ApiRendezvousMeetingController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
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
import org.springframework.aop.framework.Advised;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.interceptor.TransactionInterceptor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** H2 only. Not a MySQL run and not a production dynamic-datasource proof. */
class PlayTeamHandoffTest {
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    SqlSessionTemplate session;
    IPlayTeamService service;
    PlayTeamServiceImpl target;
    PlayTeamHandoffDelegate.Wiring wired;
    Gate gate;
    final ObjectMapper json = new ObjectMapper();
    RendezvousConsentService consent;
    RendezvousMeetingService meetings;
    Safety safety;
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
        jdbc.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, member_id BIGINT, name VARCHAR(200),"
                + " description VARCHAR(500), img_url VARCHAR(255), img_arr VARCHAR(500), category_ids VARCHAR(255),"
                + " address_name VARCHAR(200), longitude VARCHAR(64), latitude VARCHAR(64), address VARCHAR(255),"
                + " status INT, publish_status INT, cancel_time TIMESTAMP, cancel_reason VARCHAR(255), reason VARCHAR(255),"
                + " del_flag INT, start_date TIMESTAMP, end_date TIMESTAMP, template_id BIGINT, topic_id BIGINT, club_id BIGINT,"
                + " check_status INT, is_settlement INT, create_by VARCHAR(64), create_time TIMESTAMP, update_by VARCHAR(64),"
                + " update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, member_id BIGINT, club_id BIGINT, topic_type INT,"
                + " product_type INT, circle_theme_code VARCHAR(64), source_template_topic_id BIGINT, complete_rule_json VARCHAR(500),"
                + " route_mode VARCHAR(64), route_graph_json VARCHAR(500), config_version INT, published_route_graph_json VARCHAR(500),"
                + " published_config_version INT, game_module_config_json VARCHAR(500), journey_rules_json VARCHAR(500),"
                + " journey_story_json VARCHAR(500), theme_runtime_config_json VARCHAR(500), published_theme_runtime_config_json VARCHAR(500),"
                + " fee_platform_rate DECIMAL(10,4), fee_guarantee_base_rate DECIMAL(10,4), fee_guarantee_step_rate DECIMAL(10,4),"
                + " fee_guarantee_max_merchant DECIMAL(10,4), topic_id BIGINT, name VARCHAR(200), subtitle VARCHAR(200),"
                + " description VARCHAR(500), address_name VARCHAR(200), total_time INT, total_mileage INT, location_count INT,"
                + " template_count INT, start_date TIMESTAMP, end_date TIMESTAMP, img_url VARCHAR(255), img_arr VARCHAR(500),"
                + " category_ids VARCHAR(255), status INT, reason VARCHAR(255), user_status INT, merchant_status INT,"
                + " open_club_pool INT, merchant_sign_up_start_date TIMESTAMP, merchant_sign_up_end_date TIMESTAMP, del_flag INT,"
                + " is_sync INT, is_recommend INT, check_status INT, is_settlement INT, publish_mode INT, beta_flag INT,"
                + " audio_url VARCHAR(255), audio_duration INT, self_play INT, self_play_price DECIMAL(10,2), self_play_quota INT,"
                + " team_mode INT, team_max_members INT, xp_budget INT, finish_medal_name VARCHAR(100), finish_medal_img VARCHAR(255),"
                + " complete_reward_coupon_id BIGINT, recruit_deadline TIMESTAMP, recruit_extend_count INT, club_coop_open INT,"
                + " club_pinned INT, club_member_only INT, lifecycle VARCHAR(64), sale_end_at TIMESTAMP, selling_started_at TIMESTAMP,"
                + " source_topic_id BIGINT, create_by VARCHAR(64), create_time TIMESTAMP, update_by VARCHAR(64), update_time TIMESTAMP)");
        jdbc.update("INSERT INTO cms_topic(id, team_mode, team_max_members, del_flag) VALUES (20, 1, 4, 0)");
        jdbc.update("INSERT INTO cms_activity(id, topic_id, del_flag, start_date) VALUES (99, 20, 0, DATEADD('DAY', 1, CURRENT_TIMESTAMP))");
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
        config.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        config.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousConsentMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousProtocolMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousHandoffMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/RendezvousMeetingEvidenceMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/PlayTeamMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/PlayTeamHandoffMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImBlockMapper.xml", true);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImConversationMapper.xml", true);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/ImConversationMemberMapper.xml", true);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/CmsActivityMapper.xml", false);
        load(config, root, "chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml", false);
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        wired = PlayTeamHandoffDelegate.bind(session);
        manager = wired.manager();
        target = wired.service();
        service = wired.teams();
        IImService delegated = (IImService) ReflectionTestUtils.getField(target, "imService");
        ReflectionTestUtils.setField(target, "imService", (IImService) Proxy.newProxyInstance(IImService.class.getClassLoader(),
                new Class<?>[]{IImService.class}, (proxy, method, args) -> {
                    if ("getOrCreateTeamGroup".equals(method.getName()) && method.getParameterCount() == 2) {
                        Long id = delegated.getOrCreateTeamGroup((Long) args[0], (Long) args[1]);
                        if (failIm[0]) throw new IllegalStateException("IM_FAIL");
                        return id;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "handoff-im";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                    }
                    throw new IllegalStateException("W16_BLOCK_NOT_OWNED");
                }));
        gate = new Gate(wired.gateway());
        MyBatisRendezvousConsentStore consentStore = wired.consent(ISOLATED_PERSISTENCE_EVALUATION);
        MyBatisRendezvousMeetingStore meetingStore = wired.meetings(
                MyBatisRendezvousMeetingStore.Mode.ISOLATED_PERSISTENCE_EVALUATION);
        safety = new Safety() {
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

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.execute("SHUTDOWN");
    }

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

    @Test void delegateRefusesCallerManagerAndRequiresTheDerivedGuard() throws Exception {
        Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        assertSame(environment.getDataSource(), manager.getDataSource());
        assertSame(session, ReflectionTestUtils.getField(Proxy.getInvocationHandler(wired.teamMapper()), "sqlSession"));
        assertSame(wired.teamMapper(), ReflectionTestUtils.getField(target, "teamMapper"));
        assertSame(session, ReflectionTestUtils.getField(Proxy.getInvocationHandler(
                ReflectionTestUtils.getField(target, "cmsActivityMapper")), "sqlSession"));
        assertSame(session, ReflectionTestUtils.getField(Proxy.getInvocationHandler(
                ReflectionTestUtils.getField(target, "cmsTopicMapper")), "sqlSession"));
        TransactionInterceptor interceptor = (TransactionInterceptor) ((Advised) service).getAdvisors()[0].getAdvice();
        assertSame(manager, interceptor.getTransactionManager());
        IllegalStateException owned = assertThrows(IllegalStateException.class,
                () -> ((IImService) ReflectionTestUtils.getField(target, "imService")).block(1L, 2L));
        assertEquals("W16_BLOCK_NOT_OWNED", owned.getMessage());
        assertEquals(0, count("im_block"));

        IllegalStateException unmarked = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(manager).execute(status -> gate.create(null)));
        assertEquals("W16_GUARD_MISSING", unmarked.getMessage());
        DataSourceTransactionManager otherManager = new DataSourceTransactionManager(dataSource);
        IllegalStateException otherManagerCall = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(otherManager).execute(status -> gate.create(null)));
        assertEquals("W16_GUARD_MISSING", otherManagerCall.getMessage());
        JdbcDataSource otherSource = new JdbcDataSource();
        otherSource.setURL("jdbc:h2:mem:w16-other-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        DataSourceTransactionManager otherSourceManager = new DataSourceTransactionManager(otherSource);
        IllegalStateException runtimeOtherSource = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(otherSourceManager).execute(status -> gate.create(null)));
        assertEquals("W16_TRANSACTION_RESOURCE_MISMATCH", runtimeOtherSource.getMessage());
        Configuration bare = new Configuration(new Environment("bad", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), dataSource));
        SqlSessionTemplate bad = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(bare));
        IllegalArgumentException wrongFactory = assertThrows(IllegalArgumentException.class, () -> PlayTeamHandoffDelegate.bind(bad));
        assertEquals("W16_TRANSACTION_RESOURCE_MISMATCH", wrongFactory.getMessage());
        IllegalArgumentException caller = assertThrows(IllegalArgumentException.class,
                () -> PlayTeamHandoffAssembly.open(session, manager, wired.gateway()));
        assertEquals("W16_CALLER_SUPPLIED_MANAGER_REFUSED", caller.getMessage());
        assertEquals(0, count("play_team"));
        assertEquals(0, count("play_team_member"));
        assertEquals(0, count("w16_play_team_handoff"));
        assertEquals(0, count("im_conversation"));
    }

    @Test void realActivityAndTopicRulesRefuseClosedModeAndUnpaidLeader() {
        jdbc.update("UPDATE cms_topic SET team_mode=0 WHERE id=20");
        IllegalStateException closed = assertThrows(IllegalStateException.class,
                () -> inGuard(() -> service.createForHandoff(request("closed-mode"))));
        assertEquals("该主题未开放组队", closed.getMessage());
        jdbc.update("UPDATE cms_topic SET team_mode=1 WHERE id=20");
        PlayTeamHandoffRequest unpaid = request("unpaid");
        unpaid.setLeaderMemberId(70L);
        unpaid.setPeerMemberId(71L);
        IllegalStateException denied = assertThrows(IllegalStateException.class, () -> inGuard(() -> service.createForHandoff(unpaid)));
        assertEquals("报名成功后才能建队", denied.getMessage());
        assertEquals(0, count("play_team"));
        assertEquals(0, count("w16_play_team_handoff"));
    }

    @Test void challengeConfirmHandoffReadBackAndRestartKeepOneTeam() throws Exception {
        String[] ids = paired(1, 2);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiRendezvousMeetingController(meetings)).build();
        authenticate(1);
        String challenge = response(mvc, post("/api/rendezvous/tickets/" + ids[0] + "/challenge")).get("challengeId").asText();
        response(mvc, post("/api/rendezvous/tickets/" + ids[0] + "/challenge/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{\"challengeId\":\"" + challenge + "\"}"));
        authenticate(2);
        response(mvc, post("/api/rendezvous/tickets/" + ids[1] + "/challenge/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{\"challengeId\":\"" + challenge + "\"}"));
        authenticate(1);
        JsonNode done = response(mvc, post("/api/rendezvous/tickets/" + ids[0] + "/handoff"));
        assertEquals("COMPLETED", done.get("state").asText());
        long teamId = done.get("teamId").asLong();
        assertEquals(4, jdbc.queryForObject("SELECT max_members FROM play_team WHERE id=?", Integer.class, teamId));
        assertEquals(1, jdbc.queryForObject("SELECT join_mode FROM play_team WHERE id=?", Integer.class, teamId));
        JsonNode read = response(mvc, get("/api/rendezvous/tickets/" + ids[0] + "/handoff"));
        assertEquals(teamId, read.get("teamId").asLong());
        assertEquals("COMPLETED", read.get("handoffPhase").asText());
        authenticate(2);
        assertEquals(teamId, response(mvc, get("/api/rendezvous/tickets/" + ids[1] + "/handoff")).get("teamId").asLong());
        authenticate(9);
        var missing = mvc.perform(get("/api/rendezvous/tickets/" + ids[0] + "/handoff")).andReturn();
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("NOT_FOUND", json.readTree(missing.getResponse().getContentAsString()).get("errorCode").asText());

        PlayTeamHandoffDelegate.Wiring restarted = PlayTeamHandoffDelegate.bind(session);
        RendezvousMeetingService again = new RendezvousMeetingService(RendezvousMeetingService.Mode.ISOLATED_CONTRACT_EVALUATION,
                restarted.meetings(MyBatisRendezvousMeetingStore.Mode.ISOLATED_PERSISTENCE_EVALUATION),
                safety, (m, t, a, p, n) -> safetyOpen.get(), new RendezvousHandoffLegacyTeamPort(), clock, 60_000,
                restarted.gateway());
        assertEquals(teamId, again.handoff(1, ids[0]).teamId);
        assertEquals(teamId, again.result(2, ids[1]).teamId);
        String requestId = jdbc.queryForObject("SELECT request_id FROM w16_handoff_request WHERE first_actor=1", String.class);
        assertEquals(teamId, restarted.teams().findHandoff(PlayTeamHandoffGateway.NAMESPACE, requestId).teamId.longValue());
        assertEquals(1, count("play_team"));
        assertEquals(2, count("play_team_member"));
        assertEquals(1, count("im_conversation"));
    }

    @Test void expiryBeforeDispatchCreatesNothingAndAfterDispatchIsNotRevoked() {
        String[] ids = ready();
        clock.now = 80_000;
        RendezvousMeetingService.View expired = meetings.handoff(1, ids[0]);
        assertEquals(RendezvousMeetingService.State.EXPIRED, expired.state);
        assertNull(expired.teamId);
        assertNull(expired.blocker);
        assertEquals(0, count("play_team"));
        assertEquals(0, count("w16_play_team_handoff"));

        clock.now = 90_000;
        String[] later = ready(7, 8);
        paid(7L); paid(8L);
        gate.afterCreate = () -> clock.now = 160_000;
        RendezvousMeetingService.View kept = meetings.handoff(7, later[0]);
        assertEquals(RendezvousMeetingService.State.RECOVERY_REQUIRED, kept.state);
        assertEquals("EXPIRED_NOT_ROLLED_BACK", kept.blocker);
        assertNull(kept.teamId);
        assertEquals(1, count("play_team"));
        assertNotEquals("REVOKED", kept.blocker);
    }

    private String[] ready() { return ready(1, 2); }

    private String[] ready(long first, long second) {
        String[] ids = paired(first, second);
        String challenge = meetings.issue(first, ids[0]).challengeId;
        meetings.confirm(first, ids[0], challenge);
        meetings.confirm(second, ids[1], challenge);
        return ids;
    }

    private String[] paired(long first, long second) {
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

    private void authenticate(long actor) {
        AppUser app = new AppUser();
        app.setUserId(actor);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginAppUser(999L, app), null, Collections.emptyList()));
    }

    private JsonNode response(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        return json.readTree(result.getResponse().getContentAsString()).get("data");
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
