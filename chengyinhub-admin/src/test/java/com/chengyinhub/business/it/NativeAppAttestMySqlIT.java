package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.NativeAppAttestService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.impl.AdvancedGameRuntimeServiceImpl;
import com.chengyinhub.business.service.impl.TopicRouteRuntimeServiceImpl;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.business.service.support.appattest.AppAttestTestKit;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real MySQL + real Spring transaction proxies + synthetic ephemeral cryptographic material only. */
class NativeAppAttestMySqlIT {
    private static final Instant NOW=Instant.parse("2026-10-02T10:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private AppAttestTestKit kit;
    private NativeAppAttestService service;
    private NativeAppAttestMapper mapper;
    private GameRuntimeSessionMapper games;
    private PlayAttemptLogMapper receipts;
    private TopicRouteSessionMapper routeSessions;
    private NativeAppAttestConfiguration config;
    private DataSourceTransactionManager manager;
    private JdbcTemplate jdbc;
    @BeforeEach void setup() throws Exception {
        String url=MybatisItDatabase.url("native_app_attest");
        DriverManagerDataSource ds=new DriverManagerDataSource(url,MybatisItDatabase.user(),MybatisItDatabase.password());
        try(Connection c=ds.getConnection();Statement st=c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS cms_member_template");st.execute("DROP TABLE IF EXISTS cms_template_library");
            st.execute("CREATE TABLE cms_member_template(id BIGINT PRIMARY KEY,preference_json JSON) ENGINE=InnoDB");
            st.execute("CREATE TABLE cms_template_library(id BIGINT PRIMARY KEY,preference_json JSON) ENGINE=InnoDB");
            ItSchema.applyMigrations(c,"sql/migration_20261002_native_app_attest.sql","sql/migration_20260822_advanced_game_runtime.sql");
            // Match the theme mapper's required shape without applying an unrelated production DDL bundle.
            st.execute("ALTER TABLE game_runtime_session ADD COLUMN play_instance_id VARCHAR(80) NOT NULL DEFAULT 'default'");
            st.execute("ALTER TABLE game_runtime_session DROP INDEX uk_runtime_owner, ADD UNIQUE KEY uk_runtime_owner(activity_id,topic_id,node_id,owner_type,owner_id,play_instance_id)");
            st.execute("DROP TABLE IF EXISTS topic_route_session");
            st.execute("CREATE TABLE topic_route_session(id BIGINT AUTO_INCREMENT PRIMARY KEY,activity_id BIGINT NOT NULL,topic_id BIGINT NOT NULL,member_id BIGINT NOT NULL,play_instance_id VARCHAR(80) NOT NULL,config_version INT,config_snapshot_json JSON,state_json JSON,status INT,current_node_id BIGINT,recommended_node_id BIGINT,seed BIGINT,version INT,completed_at DATETIME(3),create_time DATETIME(3),update_time DATETIME(3),UNIQUE KEY uk_native_route_instance(activity_id,topic_id,member_id,play_instance_id)) ENGINE=InnoDB");
            st.execute("DROP TABLE IF EXISTS play_team_member");st.execute("CREATE TABLE play_team_member(team_id BIGINT,member_id BIGINT,status INT) ENGINE=InnoDB");
            st.execute("DROP TABLE IF EXISTS play_attempt_log");
            st.execute("CREATE TABLE play_attempt_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,topic_id BIGINT,node_id BIGINT,activity_id BIGINT,attempt_type INT,result INT,wrong_answer VARCHAR(200),duration_s INT,result_json JSON,idempotency_key VARCHAR(64) UNIQUE,created_at DATETIME(3)) ENGINE=InnoDB");
        }
        org.apache.ibatis.session.Configuration cfg=new org.apache.ibatis.session.Configuration();
        cfg.getTypeAliasRegistry().registerAlias("PlayAttemptLog",PlayAttemptLog.class);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);
        factory.setMapperLocations(new ClassPathResource("mapper/business/NativeAppAttestMapper.xml"),
                new ClassPathResource("mapper/business/GameRuntimeSessionMapper.xml"),new ClassPathResource("mapper/business/PlayAttemptLogMapper.xml"),
                new ClassPathResource("mapper/business/TopicRouteSessionMapper.xml"));
        SqlSessionFactory sql=factory.getObject();SqlSessionTemplate template=new SqlSessionTemplate(sql);
        mapper=template.getMapper(NativeAppAttestMapper.class);games=template.getMapper(GameRuntimeSessionMapper.class);receipts=template.getMapper(PlayAttemptLogMapper.class);
        routeSessions=template.getMapper(TopicRouteSessionMapper.class);
        manager=new DataSourceTransactionManager(ds);jdbc=new JdbcTemplate(ds);kit=new AppAttestTestKit(NOW);
        config=mock(NativeAppAttestConfiguration.class);when(config.isReady()).thenReturn(true);when(config.appId()).thenReturn(AppAttestTestKit.APP_ID);when(config.verifier()).thenReturn(kit.verifier());
        NativeAppAttestService target=new NativeAppAttestService();ReflectionTestUtils.setField(target,"mapper",mapper);ReflectionTestUtils.setField(target,"configuration",config);
        ReflectionTestUtils.setField(target,"clock",Clock.fixed(NOW,ZoneOffset.UTC));service=proxy(target);
    }
    @SuppressWarnings("unchecked") private <T>T proxy(T target) {
        ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (T)proxy.getProxy();
    }
    private Map<String,Object> base() {Map<String,Object> p=new LinkedHashMap<>();p.put("provider",NativeStepProtocol.PROVIDER);p.put("deviceKeyId",kit.keyId());return p;}
    private Map<String,Object> enrollment() throws Exception {
        Map<String,Object> challenge=service.challenge(7L,base());Map<String,Object> p=base();p.put("challengeId",challenge.get("challengeId"));
        p.put("attestationObject",Base64.getEncoder().encodeToString(kit.enrollment(Base64.getDecoder().decode((String)challenge.get("challengeBase64")),NOW)));return p;
    }
    private void enroll() throws Exception {assertEquals("ACTIVE",service.enroll(7L,enrollment()).get("status"));}
    private String canonical(long sessionId) {
        ObjectNode c=json.createObjectNode();c.put("provider",NativeStepProtocol.PROVIDER);c.put("accountId",7);c.put("sessionId",sessionId);
        c.put("attemptStartedAt",NOW.minusSeconds(60).toEpochMilli());c.put("deviceKeyId",kit.keyId());c.put("challengeId",String.join("",Collections.nCopies(64,"a")));
        c.put("issuedAt",NOW.toEpochMilli());c.put("expiresAt",NOW.plusSeconds(120).toEpochMilli());c.put("timeZone","Asia/Shanghai");c.put("dayKey","2026-10-02");
        c.put("dayStartAt",Instant.parse("2026-10-01T16:00:00Z").toEpochMilli());c.put("dayEndAt",Instant.parse("2026-10-02T16:00:00Z").toEpochMilli());
        return NativeStepProtocol.canonical(c,c.path("dayStartAt").asLong(),NOW.toEpochMilli(),100,1,"counter-test");
    }
    private long consume(long sessionId,long counter) throws Exception {
        String data=canonical(sessionId);String proof=Base64.getEncoder().encodeToString(kit.assertion(data.getBytes(StandardCharsets.UTF_8),counter));
        return service.verifyAndConsume(new NativeStepAssertionVerifier.Request(7,sessionId,kit.keyId(),data),proof);
    }
    private long counter() {return mapper.selectKey(7L,kit.keyId()).getAssertionCounter();}
    @Test void realEnrollmentReplaysExactReceiptAndNeverReassignsAKey() throws Exception {
        Map<String,Object> proof=enrollment();assertEquals("ACTIVE",service.enroll(7L,proof).get("status"));
        assertEquals("ACTIVE",service.enroll(7L,proof).get("status"));assertEquals(1,mapper.countActiveKeys(7L));assertEquals(0,counter());
        assertArrayEquals(kit.publicKeySpki(),mapper.selectKey(7L,kit.keyId()).getPublicKeySpki());
        assertThrows(ServiceException.class,()->service.enroll(8L,proof));assertNull(mapper.selectKey(8L,kit.keyId()));
        Map<String,Object> otherChallenge=service.challenge(8L,base());Map<String,Object> other=base();other.put("challengeId",otherChallenge.get("challengeId"));
        other.put("attestationObject",Base64.getEncoder().encodeToString(kit.enrollment(Base64.getDecoder().decode((String)otherChallenge.get("challengeBase64")),NOW)));
        assertThrows(ServiceException.class,()->service.enroll(8L,other));assertNull(mapper.selectKey(8L,kit.keyId()));
        assertNull(mapper.lockChallenge(8L,(String)otherChallenge.get("challengeId")).getConsumedAt());
    }
    @Test void globalCounterJoinsSpringTransactionAndRevocationTakesEffect() throws Exception {
        enroll();TransactionTemplate tx=new TransactionTemplate(manager);
        assertThrows(RuntimeException.class,()->consume(51,1));assertEquals(0,counter());
        assertThrows(IllegalStateException.class,()->tx.execute(status->{try{consume(51,1);}catch(Exception e){throw new RuntimeException(e);}throw new IllegalStateException("receipt failed");}));
        assertEquals(0,counter());long accepted=tx.execute(status->{try{return consume(51,1);}catch(Exception e){throw new RuntimeException(e);}});assertEquals(1L,accepted);
        assertThrows(RuntimeException.class,()->tx.execute(status->{try{return consume(52,1);}catch(Exception e){throw new RuntimeException(e);}}));assertEquals(1,counter());
        assertEquals("REVOKED",service.revoke(7L,base()).get("status"));
        assertThrows(RuntimeException.class,()->tx.execute(status->{try{return consume(52,2);}catch(Exception e){throw new RuntimeException(e);}}));assertEquals(1,counter());
    }
    @Test void parallelSameCounterCommitsExactlyOnceUnderBothIsolationLevels() throws Exception {
        enroll();
        for(int isolation:new int[]{TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ}) {
            jdbc.update("UPDATE native_app_attest_key SET assertion_counter=0 WHERE member_id=? AND key_id=?",7L,kit.keyId());
            CountDownLatch start=new CountDownLatch(1);AtomicInteger committed=new AtomicInteger(),refused=new AtomicInteger();
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> work=new ArrayList<>();
                for(int i=0;i<2;i++){final long session=51+i;work.add(pool.submit(()->{try{start.await();TransactionTemplate tx=new TransactionTemplate(manager);tx.setIsolationLevel(isolation);
                    tx.execute(status->{try{return consume(session,1);}catch(Exception e){throw new RuntimeException(e);}});committed.incrementAndGet();
                }catch(Exception expected){refused.incrementAndGet();}}));}
                start.countDown();for(Future<?> f:work)f.get(20,TimeUnit.SECONDS);
            } finally {pool.shutdownNow();}
            assertEquals(1,committed.get());assertEquals(1,refused.get());assertEquals(1,counter());
        }
    }
    @Test void advancedReceiptCollisionRollsBackBothGameAndRealRegistryCounter() throws Exception {
        enroll();GameRuntimeSession row=new GameRuntimeSession();row.setActivityId(0L);row.setTopicId(19L);row.setNodeId(29L);row.setTemplateId(39L);
        row.setOwnerType(1);row.setOwnerId(7L);row.setConfigSnapshotJson("{\"steps\":{\"enabled\":true,\"goal\":1000,\"xp\":30}}");row.setStateJson("{}");
        row.setStatus(0);row.setScore(0);row.setCompletedUnits(0);row.setVersion(0);row.setStartedAt(Date.from(NOW.minusSeconds(60)));row.setCreateTime(Date.from(NOW));row.setUpdateTime(Date.from(NOW));assertEquals(1,games.insert(row));
        PlayAttemptLogMapper raced=mock(PlayAttemptLogMapper.class,org.mockito.AdditionalAnswers.delegatesTo(receipts));
        doReturn(null).when(raced).selectByIdempotencyKey("lost-race");
        NativeStepProtocol protocol=new NativeStepProtocol();ReflectionTestUtils.setField(protocol,"enabled",true);ReflectionTestUtils.setField(protocol,"verifier",service);
        AdvancedGameRuntimeServiceImpl target=new AdvancedGameRuntimeServiceImpl();ReflectionTestUtils.setField(target,"sessionMapper",games);ReflectionTestUtils.setField(target,"attemptMapper",raced);
        ReflectionTestUtils.setField(target,"nodeMapper",mock(CmsTopicNodeMapper.class));ReflectionTestUtils.setField(target,"templateMapper",mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(target,"nativeStepProtocol",protocol);ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock)()->Date.from(NOW));
        AdvancedGameRuntimeServiceImpl game=proxy(target);
        game.act(7L,row.getId(),0,"issue",NativeStepProtocol.ISSUE,base());
        JsonNode c=json.readTree(games.selectByOwner(0L,19L,29L,1,7L).getStateJson()).path("nativeSteps").path("challenge");
        Map<String,Object> p=base();p.put("challengeId",c.path("challengeId").asText());p.put("dayKey",c.path("dayKey").asText());p.put("sampleStartAt",c.path("dayStartAt").asLong());p.put("sampleEndAt",NOW.toEpochMilli());p.put("cumulativeSteps",4000);
        String canonical=NativeStepProtocol.canonical(c,c.path("dayStartAt").asLong(),NOW.toEpochMilli(),4000,1,"lost-race");
        p.put("assertion",Base64.getEncoder().encodeToString(kit.assertion(canonical.getBytes(StandardCharsets.UTF_8),1)));
        jdbc.update("INSERT INTO play_attempt_log(member_id,attempt_type,result_json,idempotency_key,created_at) VALUES(7,8,?, 'lost-race',NOW())","{\"sessionId\":"+row.getId()+",\"version\":1}");
        ServiceException failure=assertThrows(ServiceException.class,()->game.act(7L,row.getId(),1,"lost-race",NativeStepProtocol.SUBMIT,p));
        assertEquals("NATIVE_STEPS_IDEMPOTENCY_CONFLICT",((Map<?,?>)failure.getData()).get("reasonCode"));
        GameRuntimeSession stored=games.selectByOwner(0L,19L,29L,1,7L);assertEquals(1,stored.getVersion());assertEquals(0,counter());
        assertFalse(json.readTree(stored.getStateJson()).path("nativeSteps").has("baselineSteps"));assertEquals(0,stored.getScore());
    }
    @Test void ownerGuardSerializesEnrollmentChallengeQuota() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);AtomicInteger success=new AtomicInteger();
        try {List<Future<?>> work=new ArrayList<>();for(int i=0;i<2;i++)work.add(pool.submit(()->{try{start.await();service.challenge(7L,base());success.incrementAndGet();}catch(Exception denied){}}));
            start.countDown();for(Future<?> f:work)f.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        assertEquals(1,success.get());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM native_app_attest_challenge WHERE member_id=7",Integer.class));
        assertEquals(1,mapper.lockGuard(7L).getIssuedCount());
    }
    @Test void databaseRejectsCounterOverflowAndOwnerUniquenessViolations() throws Exception {
        enroll();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE native_app_attest_key SET assertion_counter=4294967296 WHERE member_id=? AND key_id=?",7L,kit.keyId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE native_app_attest_key SET assertion_counter=-1 WHERE member_id=? AND key_id=?",7L,kit.keyId()));
        assertEquals(0,mapper.advanceCounter(8L,kit.keyId(),0L,1L,NOW.toEpochMilli()));assertEquals(0,counter());
        String migration=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("sql/migration_20261002_native_app_attest.sql")),StandardCharsets.UTF_8);
        String readback=migration.substring(migration.lastIndexOf("SELECT ("));
        assertEquals(Boolean.TRUE,jdbc.queryForObject(readback,Boolean.class));
        jdbc.execute("ALTER TABLE native_app_attest_key DROP PRIMARY KEY, ADD PRIMARY KEY(key_id,member_id)");
        assertEquals(Boolean.FALSE,jdbc.queryForObject(readback,Boolean.class),"Composite owner/key identity must fail global-key uniqueness preflight");
    }
    @Test void themeNativeRawSignaturesAndReceiptNamespacesRemainIndependentAcrossReplays() throws Exception {
        enroll();
        ITopicRouteRuntimeService route = currentThemeRoute();
        AdvancedGameRuntimeServiceImpl game = themeGameRuntime(route, receipts);
        GameRuntimeSession first = null;
        long expectedCounter = 0;
        for (String instance : new String[] {"default", "replay:80"}) {
            GameRuntimeSession row = newThemeGame(instance);
            if (first == null) first = row;
            Map<String, Object> issue = game.act(7L, row.getId(), 0, "shared-issue", NativeStepProtocol.ISSUE, base());
            assertEquals(1, ((Number) issue.get("version")).intValue());
            JsonNode challenge = json.readTree(games.selectAccessibleById(row.getId(), 7L).getStateJson())
                    .path("nativeSteps").path("challenge");
            String rawKey = "shared.raw-submit:1";
            Map<String, Object> proof = signedSample(challenge, rawKey, ++expectedCounter);
            Map<String, Object> result = game.act(7L, row.getId(), 1, rawKey, NativeStepProtocol.SUBMIT, proof);
            assertEquals(expectedCounter, counter(), "Real App Attest accepts bytes ending in the original client key");
            assertEquals(NativeStepProtocol.requestDigest(NativeStepProtocol.SUBMIT, proof, 1, rawKey),
                    result.get("nativeRequestDigest"));
            assertNull(receipts.selectByIdempotencyKey(rawKey));
            PlayAttemptLog storedReceipt = receipts.selectByIdempotencyKey(themeReceiptKey(row.getId(), rawKey));
            assertNotNull(storedReceipt);
            assertEquals(row.getId().longValue(), json.readTree(storedReceipt.getResultJson()).path("sessionId").asLong());
            // MySQL JSON may reorder object keys; compare every value without Map iteration/type artifacts.
            assertEquals(json.readTree(json.writeValueAsString(result)),
                    json.readTree(json.writeValueAsString(game.act(7L, row.getId(), 1, rawKey, NativeStepProtocol.SUBMIT, proof))));
            Map<String, Object> changed = new LinkedHashMap<>(proof);
            changed.put("cumulativeSteps", 4001);
            nativeFailure("NATIVE_STEPS_IDEMPOTENCY_MISMATCH", () -> game.act(7L, row.getId(), 1, rawKey, NativeStepProtocol.SUBMIT, changed));
            nativeFailure("NATIVE_STEPS_IDEMPOTENCY_MISMATCH", () -> game.act(7L, row.getId(), 2, rawKey, NativeStepProtocol.SUBMIT, proof));
            nativeFailure("NATIVE_STEPS_IDEMPOTENCY_MISMATCH", () -> game.act(7L, row.getId(), 1, rawKey, NativeStepProtocol.ISSUE, base()));
            nativeFailure("NATIVE_STEPS_IDEMPOTENCY_MISMATCH", () -> game.act(7L, row.getId(), 1, rawKey, "DRAW", Collections.emptyMap()));
            assertEquals(expectedCounter, counter());
            GameRuntimeSession stored = games.selectAccessibleById(row.getId(), 7L);
            assertEquals(2, stored.getVersion());
            assertEquals(0, stored.getScore());
            assertEquals(0, stored.getCompletedUnits());
            assertEquals(GameRuntimeSession.STATUS_RUNNING, stored.getStatus());
            JsonNode state = json.readTree(stored.getStateJson());
            assertFalse(state.has("steps"));
            assertEquals(0, state.path("nativeSteps").path("acceptedDelta").asLong());
            JsonNode publicNative = json.valueToTree(result).path("playKit").path("steps").path("nativeSteps");
            assertEquals("AUDIT_ONLY", publicNative.path("status").asText());
            assertEquals(false, publicNative.path("rewardEnabled").asBoolean(true));
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM play_attempt_log WHERE idempotency_key LIKE ?",
                Integer.class, "theme:%:" + themeReceiptKey(1L, "shared.raw-submit:1").substring("theme:1:".length())));
        final Long historicalId = first.getId();
        assertThrows(ServiceException.class, () -> game.act(7L, historicalId, 0, "shared-issue", NativeStepProtocol.ISSUE, base()));
        assertEquals(2, counter());
        verifyNoNativeRewards(route);
    }

    @Test void themeNativeLateReceiptCollisionRollsBackRealCounterUnderBothIsolationLevels() throws Exception {
        enroll();
        for (int isolation : new int[] {TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionDefinition.ISOLATION_REPEATABLE_READ}) {
            ITopicRouteRuntimeService route = currentThemeRoute();
            GameRuntimeSession row = newThemeGame(isolation == TransactionDefinition.ISOLATION_READ_COMMITTED ? "default" : "replay:80");
            String rawKey = "theme-lost-race";
            String scopedKey = themeReceiptKey(row.getId(), rawKey);
            PlayAttemptLogMapper raced = mock(PlayAttemptLogMapper.class, org.mockito.AdditionalAnswers.delegatesTo(receipts));
            doReturn(null).when(raced).selectByIdempotencyKey(scopedKey);
            AdvancedGameRuntimeServiceImpl game = themeGameRuntime(route, raced);
            game.act(7L, row.getId(), 0, "issue-late-" + isolation, NativeStepProtocol.ISSUE, base());
            GameRuntimeSession before = games.selectAccessibleById(row.getId(), 7L);
            JsonNode challenge = json.readTree(before.getStateJson()).path("nativeSteps").path("challenge");
            Map<String, Object> proof = signedSample(challenge, rawKey, 1L);
            jdbc.update("INSERT INTO play_attempt_log(member_id,attempt_type,result_json,idempotency_key,created_at) VALUES(7,8,?,?,NOW())",
                    "{\"sessionId\":" + row.getId() + ",\"version\":1}", scopedKey);
            TransactionTemplate tx = new TransactionTemplate(manager);
            tx.setIsolationLevel(isolation);
            nativeFailure("NATIVE_STEPS_IDEMPOTENCY_CONFLICT", () -> tx.execute(status ->
                    game.act(7L, row.getId(), 1, rawKey, NativeStepProtocol.SUBMIT, proof)));
            GameRuntimeSession stored = games.selectAccessibleById(row.getId(), 7L);
            assertEquals(1, stored.getVersion());
            assertEquals(json.readTree(before.getStateJson()), json.readTree(stored.getStateJson()));
            assertEquals(0, counter(), "The genuine global App Attest key counter must join the losing transaction");
            assertFalse(json.readTree(stored.getStateJson()).path("nativeSteps").has("baselineSteps"));
            assertEquals(0, stored.getScore());
            assertEquals(0, stored.getCompletedUnits());
            verifyNoNativeRewards(route);
        }
    }


    @Test void repeatableReadNativeSubmitRejectsCommittedSuccessorThroughRealRouteGuard() throws Exception {
        enroll();
        ITopicRouteRuntimeService route = currentThemeRoute();
        GameRuntimeSession old = newThemeGame("default");
        AdvancedGameRuntimeServiceImpl game = themeGameRuntime(route, receipts);
        game.act(7L, old.getId(), 0, "issue-before-rr", NativeStepProtocol.ISSUE, base());
        GameRuntimeSession before = games.selectAccessibleById(old.getId(), 7L);
        JsonNode challenge = json.readTree(before.getStateJson()).path("nativeSteps").path("challenge");
        String rawKey = "rr-stale-native";
        Map<String,Object> proof = signedSample(challenge, rawKey, 1L);
        String scopedKey = themeReceiptKey(old.getId(), rawKey);
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            tx.execute(status -> {
                // These ordinary reads deliberately pin the pre-replay RR snapshot.
                TopicRouteSession observed = routeSessions.selectCurrentByOwner(0L,19L,7L);
                assertEquals("default", observed.getPlayInstanceId());
                assertEquals(1, games.selectAccessibleById(old.getId(),7L).getVersion());
                try {
                    GameRuntimeSession successor = pool.submit(() -> new TransactionTemplate(manager)
                            .execute(child -> newThemeGame("replay:" + observed.getId()))).get(10,TimeUnit.SECONDS);
                    assertNotEquals(old.getId(), successor.getId());
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                ServiceException denied = assertThrows(ServiceException.class,
                        () -> game.act(7L,old.getId(),1,rawKey,NativeStepProtocol.SUBMIT,proof));
                assertEquals("本局已更新，请刷新", denied.getMessage());
                status.setRollbackOnly();
                return null;
            });
        } finally { pool.shutdownNow(); }
        GameRuntimeSession unchanged = games.selectAccessibleById(old.getId(),7L);
        assertEquals(1, unchanged.getVersion());
        assertEquals(json.readTree(before.getStateJson()),json.readTree(unchanged.getStateJson()));
        assertEquals(0, unchanged.getScore());
        assertEquals(0, unchanged.getCompletedUnits());
        assertEquals(0, counter());
        assertNull(receipts.selectByIdempotencyKey(scopedKey));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM game_runtime_session",Integer.class));
        assertTrue(routeSessions.selectCurrentByOwner(0L,19L,7L).getPlayInstanceId().startsWith("replay:"));
        verifyNoNativeRewards(route);
    }

    private GameRuntimeSession newThemeGame(String instance) {
        TopicRouteSession route = new TopicRouteSession();
        route.setMemberId(7L); route.setTopicId(19L); route.setActivityId(0L); route.setPlayInstanceId(instance);
        route.setConfigVersion(1L); route.setConfigSnapshotJson("{\"themeConfig\":{}}"); route.setStateJson("{}");
        route.setStatus(TopicRouteSession.STATUS_RUNNING); route.setCurrentNodeId(29L); route.setRecommendedNodeId(29L);
        route.setSeed(1L); route.setVersion(0); route.setCreateTime(Date.from(NOW)); route.setUpdateTime(Date.from(NOW));
        assertEquals(1, routeSessions.insertSession(route));
        GameRuntimeSession row = new GameRuntimeSession();
        row.setPlayInstanceId(instance); row.setActivityId(0L); row.setTopicId(19L); row.setNodeId(29L); row.setTemplateId(39L);
        row.setOwnerType(GameRuntimeSession.OWNER_PLAYER); row.setOwnerId(7L);
        row.setConfigSnapshotJson("{\"steps\":{\"enabled\":true,\"goal\":1000,\"xp\":30}}"); row.setStateJson("{}");
        row.setStatus(GameRuntimeSession.STATUS_RUNNING); row.setScore(0); row.setCompletedUnits(0); row.setVersion(0);
        row.setStartedAt(Date.from(NOW.minusSeconds(60))); row.setCreateTime(Date.from(NOW)); row.setUpdateTime(Date.from(NOW));
        assertEquals(1, games.insert(row));
        return row;
    }

    /** Only unrelated route projection/access hooks are mocked; the current-instance guard is real. */
    private ITopicRouteRuntimeService currentThemeRoute() {
        ThemeRuntimeFeaturePolicy policy = new ThemeRuntimeFeaturePolicy();
        ReflectionTestUtils.setField(policy, "enabled", true);
        TopicRouteRuntimeServiceImpl target = new TopicRouteRuntimeServiceImpl(routeSessions,
                mock(CmsTopicMapper.class), mock(CmsTopicNodeMapper.class), mock(PlayerNodeProgressMapper.class),
                mock(CmsActivityMapper.class), mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(target, "themeRuntimePolicy", policy);
        TopicRouteRuntimeServiceImpl authoritative = proxy(target);
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        when(route.canPlay(7L,19L,0L,29L)).thenReturn(true);
        when(route.assertCurrentPlayInstance(eq(7L),eq(19L),eq(0L),anyString())).thenAnswer(i -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return authoritative.assertCurrentPlayInstance(i.getArgument(0), i.getArgument(1),
                    i.getArgument(2), i.getArgument(3));
        });
        return route;
    }

    private AdvancedGameRuntimeServiceImpl themeGameRuntime(ITopicRouteRuntimeService route, PlayAttemptLogMapper receiptMapper) {
        NativeStepProtocol protocol = new NativeStepProtocol();
        ReflectionTestUtils.setField(protocol,"enabled",true); ReflectionTestUtils.setField(protocol,"verifier",service);
        AdvancedGameRuntimeServiceImpl target = new AdvancedGameRuntimeServiceImpl();
        ReflectionTestUtils.setField(target,"sessionMapper",games); ReflectionTestUtils.setField(target,"attemptMapper",receiptMapper);
        ReflectionTestUtils.setField(target,"nodeMapper",mock(CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(target,"templateMapper",mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(target,"topicRouteRuntimeService",route);
        ReflectionTestUtils.setField(target,"nativeStepProtocol",protocol);
        ReflectionTestUtils.setField(target,"clock",(AdvancedGameClock)()->Date.from(NOW));
        return proxy(target);
    }

    private Map<String,Object> signedSample(JsonNode c, String rawKey, long assertionCounter) throws Exception {
        Map<String,Object> p = base();
        p.put("challengeId",c.path("challengeId").asText()); p.put("dayKey",c.path("dayKey").asText());
        p.put("sampleStartAt",c.path("dayStartAt").asLong()); p.put("sampleEndAt",NOW.toEpochMilli()); p.put("cumulativeSteps",4000);
        // Independent byte oracle: the final line is RAW, never the theme receipt key.
        String canonical = String.join("\n", "CHENGYIN_NATIVE_STEPS_V1", NativeStepProtocol.PROVIDER, "7",
                c.path("sessionId").asText(), Long.toString(NOW.minusSeconds(60).toEpochMilli()), kit.keyId(),
                c.path("challengeId").asText(), Long.toString(NOW.toEpochMilli()), Long.toString(NOW.plusSeconds(120).toEpochMilli()),
                "Asia/Shanghai", "2026-10-02", "1790870400000", "1790956800000", "1790870400000",
                Long.toString(NOW.toEpochMilli()), "4000", "1", rawKey);
        assertEquals(canonical, NativeStepProtocol.canonical(c,c.path("dayStartAt").asLong(),NOW.toEpochMilli(),4000,1,rawKey));
        p.put("assertion",Base64.getEncoder().encodeToString(kit.assertion(canonical.getBytes(StandardCharsets.UTF_8),assertionCounter)));
        return p;
    }

    private String themeReceiptKey(long sessionId, String rawKey) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder("theme:").append(sessionId).append(':');
        for (int i=0;i<16;i++) key.append(String.format(Locale.ROOT,"%02x",digest[i]&255));
        return key.toString();
    }

    private void nativeFailure(String expected, Runnable action) {
        ServiceException failure = assertThrows(ServiceException.class, action::run);
        assertEquals(expected, ((Map<?,?>)failure.getData()).get("reasonCode"));
    }

    private void verifyNoNativeRewards(ITopicRouteRuntimeService route) {
        verify(route,never()).recordNodeResult(any(),any(),any(),any(),any(),any(),any(),anyBoolean(),anyInt(),any());
        verify(route,never()).recordPassed(any(),any(),any(),any(),any(),anyBoolean());
        verify(route,never()).recordMistake(any(),any(),any(),any());
        verify(route,never()).recordOutcome(any(),any(),any(),any(),any(),anyInt());
        verify(route,never()).recordChoice(any(),any(),any(),any(),any(),any());
    }

}
