package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialBroadcastLog;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IWxSubscribeMsgService;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Explicit opt-in execution via -Dtest=OfficialBroadcastDeliveryMySqlIT on approved VPS/CI only.
 * Missing environment fails setup, never skips. Real MySQL + real IM mappers; WX is a fault-injection stub.
 * Creates and removes only this class's random, isolated test schema. Never point it at production.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class OfficialBroadcastDeliveryMySqlIT {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String schema;
    private boolean created;
    private DataSourceTransactionManager transactions;
    private OfficialBroadcastLogMapper logs;
    private OfficialBroadcastMapper broadcasts;
    private IImService im;
    private IWxSubscribeMsgService wx;
    private OfficialAudienceMapper audience;

    private String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "approved isolated MySQL setup required: " + name);
        return value;
    }

    @BeforeAll
    void setup() throws Exception {
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port > 0 && port <= 65535);
        String user = required("CHENGYIN_MYBATIS_IT_USER");
        assertFalse(user.isBlank());
        String password = required("CHENGYIN_MYBATIS_IT_PASSWORD");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("select version()", String.class)));
        schema = "cy_broadcast_d_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + schema);
        created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(base + schema + options, user, password);
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("it-schema/official-broadcast-delivery-it-schema.sql"))
                .execute(source);
        // Seed historical evidence BEFORE migration to verify conservative backfill.
        db.update("insert into official_broadcast(id,title,audience,channels,status) values(900,'legacy','player','inapp,subscribe',2)");
        db.update("insert into official_broadcast_log(broadcast_id,member_id,channel,reached) values(900,1,'inapp',1),(900,1,'subscribe',1),(900,2,'inapp',0)");
        Path root = Paths.get("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("chengyinhub-admin"))) root = root.getParent();
        Path migration = root.resolve("docs/delivery/architecture-day/D/sql/migration_20261023_d_official_broadcast_delivery.sql");
        new ResourceDatabasePopulator(new FileSystemResource(migration.toFile())).execute(source);
        assertEquals(1, db.queryForObject("select count(*) from official_broadcast_log where delivery_state='SUCCESS'", Integer.class));
        assertEquals(2, db.queryForObject("select count(*) from official_broadcast_log where delivery_state='UNKNOWN'", Integer.class));
        assertEquals(2, db.queryForObject("select sum(reached) from official_broadcast_log", Integer.class),
                "raw legacy flags must remain as evidence even when not counted as success");
        assertEquals(1, db.queryForObject("select status from official_broadcast where id=900", Integer.class));
        assertEquals(1, db.queryForObject("select reached_count from official_broadcast where id=900", Integer.class));
        Path sql = root.resolve("chengyinhub-admin/sql");
        new ResourceDatabasePopulator(new FileSystemResource(sql.resolve("migration_im.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_im_send_idempotency_20260913.sql").toFile()),
                new FileSystemResource(sql.resolve("migration_20261012_im_media_visibility.sql").toFile())).execute(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/OfficialBroadcastMapper.xml"),
                new ClassPathResource("mapper/business/OfficialBroadcastLogMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"));
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory.getObject());
        logs = sessions.getMapper(OfficialBroadcastLogMapper.class);
        broadcasts = sessions.getMapper(OfficialBroadcastMapper.class);
        transactions = new DataSourceTransactionManager(source);
        ImServiceImpl target = new ImServiceImpl();
        ReflectionTestUtils.setField(target, "conversationMapper", sessions.getMapper(ImConversationMapper.class));
        ReflectionTestUtils.setField(target, "memberMapper", sessions.getMapper(ImConversationMemberMapper.class));
        ReflectionTestUtils.setField(target, "messageMapper", sessions.getMapper(ImMessageMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        im = (IImService) proxy.getProxy();
    }

    @BeforeEach
    void fixtures() {
        // Only owned fixtures inside the newly created schema; no shared DB resources.
        db.update("delete from official_broadcast_log");
        db.update("delete from official_broadcast");
        db.update("delete from im_message");
        db.update("delete from im_conversation_member");
        db.update("delete from im_conversation");
        audience = mock(OfficialAudienceMapper.class);
        when(audience.selectPlayerIds(any())).thenReturn(Collections.singletonList(10L));
        wx = mock(IWxSubscribeMsgService.class);
        when(wx.notifyOfficialBroadcast(anyLong(), anyString(), anyString())).thenReturn(true);
    }

    @AfterAll
    void cleanup() {
        if (created && schema != null && schema.matches("cy_broadcast_d_[a-f0-9]{32}"))
            admin.execute("drop database " + schema);
    }

    private OfficialBroadcast seed(String channels) {
        OfficialBroadcast bc = OfficialBroadcastDeliveryTest.broadcast(channels);
        bc.setId(null);
        bc.setStatus(OfficialBroadcast.ST_PENDING);
        assertEquals(1, broadcasts.insertOfficialBroadcast(bc));
        return bc;
    }

    private OfficialBroadcastServiceImpl service(OfficialBroadcastLogMapper mapper) {
        OfficialBroadcastServiceImpl result = new OfficialBroadcastServiceImpl();
        ReflectionTestUtils.setField(result, "broadcastMapper", broadcasts);
        ReflectionTestUtils.setField(result, "logMapper", mapper);
        ReflectionTestUtils.setField(result, "audienceMapper", audience);
        ReflectionTestUtils.setField(result, "imService", im);
        ReflectionTestUtils.setField(result, "wxSubscribeMsgService", wx);
        ReflectionTestUtils.setField(result, "transactionManager", transactions);
        ReflectionTestUtils.setField(result, "officialTemplate", "configured");
        return result;
    }

    private int messages() { return db.queryForObject("select count(*) from im_message", Integer.class); }
    private String state(Long id, String channel) {
        return db.queryForObject("select delivery_state from official_broadcast_log where broadcast_id=? and channel=?", String.class, id, channel);
    }

    @Test
    void receiptFailureRollsBackActualMessageAndRecoveryCommitsExactlyOne() {
        OfficialBroadcast bc = seed("inapp,subscribe");
        OfficialBroadcastLogMapper fault = mock(OfficialBroadcastLogMapper.class, delegatesTo(logs));
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            if (failOnce.compareAndSet(true, false)) throw new IllegalStateException("receipt failed");
            return logs.transition(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    call.getArgument(3), call.getArgument(4));
        }).when(fault).transition(anyLong(), anyString(), eq(OfficialBroadcastLog.SUCCESS), isNull(), anyLong());
        assertEquals(0, service(fault).dispatch(bc));
        assertEquals(0, messages(), "message insertion must roll back with failed terminal receipt");
        assertEquals(0, db.queryForObject("select count(*) from im_conversation", Integer.class));
        assertEquals(OfficialBroadcastLog.FAILED, state(bc.getId(), "inapp"));
        assertEquals(OfficialBroadcastLog.SUCCESS, state(bc.getId(), "subscribe"));
        assertEquals(1, service(logs).dispatch(bc)); // new service instance, same durable identities
        assertEquals(1, messages());
        assertEquals(1, logs.countInappReached(bc.getId()));
        assertEquals(2, logs.countReached(bc.getId()));
        assertEquals(OfficialBroadcast.ST_SENT, broadcasts.selectOfficialBroadcastById(bc.getId()).getStatus());
        verify(wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void responseLostAfterRemoteAcceptanceRemainsUnknownAcrossServiceRestart() {
        OfficialBroadcast bc = seed("inapp,subscribe");
        AtomicInteger accepted = new AtomicInteger();
        when(wx.notifyOfficialBroadcast(anyLong(), anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(OfficialBroadcastLog.UNKNOWN, state(bc.getId(), "subscribe"));
            accepted.incrementAndGet(); // simulated provider accepted, response was lost
            return false; // current public WX helper folds this transport failure into false
        });
        service(logs).dispatch(bc);
        Long originalId = db.queryForObject("select id from official_broadcast_log where broadcast_id=? and channel='subscribe'", Long.class, bc.getId());
        service(logs).runDueScheduled();
        assertEquals(1, accepted.get());
        assertEquals(1, messages());
        assertEquals(OfficialBroadcastLog.UNKNOWN, state(bc.getId(), "subscribe"));
        assertEquals(originalId, db.queryForObject("select id from official_broadcast_log where broadcast_id=? and channel='subscribe'", Long.class, bc.getId()));
        assertEquals(OfficialBroadcast.ST_PENDING, broadcasts.selectOfficialBroadcastById(bc.getId()).getStatus());
    }

    @Test
    void concurrentWorkersDoNotRepeatConfirmedOrUnknownChannel() throws Exception {
        OfficialBroadcast bc = seed("inapp,subscribe");
        CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
        when(wx.notifyOfficialBroadcast(anyLong(), anyString(), anyString())).thenAnswer(call -> {
            sending.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return true;
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> service(logs).dispatch(bc));
            assertTrue(sending.await(10, TimeUnit.SECONDS));
            OfficialBroadcast copy = broadcasts.selectOfficialBroadcastById(bc.getId());
            Future<Integer> duplicate = workers.submit(() -> service(logs).dispatch(copy));
            assertEquals(1, duplicate.get(10, TimeUnit.SECONDS));
            assertEquals(OfficialBroadcastLog.UNKNOWN, state(bc.getId(), "subscribe"));
            release.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS));
            assertEquals(1, messages());
            assertEquals(2, db.queryForObject("select count(*) from official_broadcast_log", Integer.class));
            verify(wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void unknownClaimSurvivesLongPauseAndNoLeaseMakesItRetryable() {
        OfficialBroadcast bc = seed("subscribe");
        when(wx.notifyOfficialBroadcast(anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("crash boundary"));
        service(logs).dispatch(bc);
        db.update("update official_broadcast_log set create_time=date_sub(now(), interval 30 day) where broadcast_id=?", bc.getId());
        service(logs).runDueScheduled();
        assertEquals(OfficialBroadcastLog.UNKNOWN, state(bc.getId(), "subscribe"));
        assertEquals(0, logs.countReached(bc.getId()));
        verify(wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void missingTemplateRecoversOnlySubscriptionAndClickRequiresSuccess() {
        OfficialBroadcast bc = seed("inapp,subscribe");
        OfficialBroadcastServiceImpl noTemplate = service(logs);
        ReflectionTestUtils.setField(noTemplate, "officialTemplate", "");
        noTemplate.dispatch(bc);
        assertEquals(OfficialBroadcastLog.FAILED, state(bc.getId(), "subscribe"));
        assertEquals(0, logs.markClicked(bc.getId(), 10L, "subscribe"));
        assertEquals(1, logs.markClicked(bc.getId(), 10L, "inapp"));
        service(logs).dispatch(bc);
        assertEquals(1, messages());
        assertEquals(2, logs.countReached(bc.getId()));
        assertEquals(1, logs.countClicked(bc.getId()));
        verify(wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

    @Test
    void successfulReceiptsSurviveCrashBeforeAggregateRefresh() {
        OfficialBroadcast bc = seed("inapp");
        OfficialBroadcastLogMapper fault = mock(OfficialBroadcastLogMapper.class, delegatesTo(logs));
        doThrow(new IllegalStateException("summary interrupted")).when(fault).countInappReached(bc.getId());
        assertThrows(IllegalStateException.class, () -> service(fault).dispatch(bc));
        assertEquals(1, messages());
        assertEquals(OfficialBroadcast.ST_PENDING, broadcasts.selectOfficialBroadcastById(bc.getId()).getStatus());
        service(logs).runDueScheduled();
        assertEquals(1, messages());
        assertEquals(OfficialBroadcast.ST_SENT, broadcasts.selectOfficialBroadcastById(bc.getId()).getStatus());
    }

    @Test
    void enclosingRollbackHasNoDeliveryAndCommitWakesOnlyAfterDurablePublish() {
        org.springframework.transaction.support.TransactionTemplate outer =
                new org.springframework.transaction.support.TransactionTemplate(transactions);
        OfficialBroadcastServiceImpl dispatcher = service(logs);
        OfficialBroadcast rejected = OfficialBroadcastDeliveryTest.broadcast("inapp,subscribe");
        rejected.setId(null);
        assertThrows(IllegalStateException.class, () -> outer.executeWithoutResult(status -> {
            dispatcher.publish(rejected, null);
            assertEquals(0, messages(), "no delivery before business commit");
            verifyNoInteractions(wx);
            throw new IllegalStateException("caller rolled back");
        }));
        assertEquals(0, db.queryForObject("select count(*) from official_broadcast", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from official_broadcast_log", Integer.class));
        OfficialBroadcast committed = OfficialBroadcastDeliveryTest.broadcast("inapp,subscribe");
        committed.setId(null);
        outer.executeWithoutResult(status -> {
            dispatcher.publish(committed, null);
            assertEquals(0, messages());
            verifyNoInteractions(wx);
        });
        assertEquals(1, messages());
        assertEquals(2, logs.countReached(committed.getId()));
        verify(wx, times(1)).notifyOfficialBroadcast(anyLong(), anyString(), anyString());
    }

}
