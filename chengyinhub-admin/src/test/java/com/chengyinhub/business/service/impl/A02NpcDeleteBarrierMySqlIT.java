package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit dedicated-MySQL gate. Real production mappers/transactions and independent SQL reads.
 * Only network providers/profile lookup are doubles; the deletion barrier is never mocked.
 * No production migration, real provider, Redis, or deployment is required.
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_NPC_DELETE_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(40)
class A02NpcDeleteBarrierMySqlIT
{
    private DriverManagerDataSource source;
    private String ownedSchema;
    private NpcChatRequestMapper requests;
    private NpcChatLogMapper logs;
    private NpcChatWriteService writes;
    private DataSourceTransactionManager transactions;
    private ScheduledExecutorService heartbeat;
    private ExecutorService workers;
    private final AtomicReference<Runnable> afterFinish = new AtomicReference<>();
    private static final Long USER = 1001L;
    private static final Long OTHER = 1002L;

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    private static class FinishPause implements Interceptor {
        private final AtomicReference<Runnable> hook;
        FinishPause(AtomicReference<Runnable> hook) { this.hook = hook; }
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().endsWith(".finishIfLeaseOwned")) {
                Runnable action = hook.get();
                if (action != null) action.run();
            }
            return result;
        }
        public Object plugin(Object target) { return Plugin.wrap(target, this); }
        public void setProperties(Properties properties) { }
    }

    @BeforeAll void connect() throws Exception {
        String suitePrefix = "a02_npc_delete_";
        // The shared helper appends DB_SUFFIX verbatim; budget it before any CREATE/DROP call.
        int nonceLength = Math.min(16, 64 - MybatisItDatabase.databaseName(suitePrefix).length());
        if (nonceLength < 12)
            throw new IllegalArgumentException("CHENGYIN_MYBATIS_IT_DB_SUFFIX is too long for A02 scratch database: "
                    + "MySQL allows 64 characters; reserve at least 12 characters for the random identity");
        String suite = suitePrefix + UUID.randomUUID().toString().replace("-", "").substring(0, nonceLength);
        ownedSchema = MybatisItDatabase.databaseName(suite);
        if (ownedSchema.length() > 64 || !ownedSchema.matches("[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("A02 scratch database name must contain only letters, digits, underscores "
                    + "and fit MySQL's 64-character limit; check CHENGYIN_MYBATIS_IT_DB_SUFFIX");
        source = new DriverManagerDataSource(MybatisItDatabase.url(suite),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Path base = Paths.get("sql/base_schema_20260818.sql");
        if (!Files.exists(base)) base = Paths.get("../sql/base_schema_20260818.sql");
        String schema = new String(Files.readAllBytes(base), StandardCharsets.UTF_8);
        // Reuse complete repository DDL; do not truncate SQL at semicolons inside quoted COMMENTs.
        sql(table(schema, "ums_member"));
        sql(table(schema, "npc_chat_log"));
        Path migration = Paths.get("chengyinhub-admin/sql/migration_npc_chat_request_20260722.sql");
        if (!Files.exists(migration)) migration = Paths.get("sql/migration_npc_chat_request_20260722.sql");
        String requestSchema = new String(Files.readAllBytes(migration), StandardCharsets.UTF_8);
        sql(requestSchema.substring(requestSchema.indexOf("CREATE TABLE IF NOT EXISTS npc_chat_request"),
                requestSchema.indexOf(';', requestSchema.indexOf("CREATE TABLE IF NOT EXISTS npc_chat_request"))));
        // master 的 NpcChatRequestMapper 读写 audio_asset_id：按仓库迁移原样补列（与 NpcGeneratedAudioMySqlIT 同口径）。
        Path mediaMigration = Paths.get("chengyinhub-admin/sql/migration_20261012_npc_chat_media_asset.sql");
        if (!Files.exists(mediaMigration)) mediaMigration = Paths.get("sql/migration_20261012_npc_chat_media_asset.sql");
        try (java.sql.Connection connection = source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource(mediaMigration.toFile()));
        }
        Path asrMigration=Paths.get("chengyinhub-admin/sql/migration_20261024_d_npc_asr_intent.sql");
        if(!Files.exists(asrMigration))asrMigration=Paths.get("sql/migration_20261024_d_npc_asr_intent.sql");
        try(java.sql.Connection connection=source.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource(asrMigration.toFile()));
            java.nio.file.Path asrQuotaMigration=java.nio.file.Paths.get("chengyinhub-admin/sql/migration_20261024_d_npc_asr_shared_quota.sql");
            if(!java.nio.file.Files.exists(asrQuotaMigration))asrQuotaMigration=java.nio.file.Paths.get("sql/migration_20261024_d_npc_asr_shared_quota.sql");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource(asrQuotaMigration.toFile()));
        }
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("a02-delete", new SpringManagedTransactionFactory(), source));
        cfg.addInterceptor(new FinishPause(afterFinish));
        for (String path : Arrays.asList("mapper/business/NpcChatRequestMapper.xml", "mapper/business/NpcChatLogMapper.xml", "mapper/business/NpcAsrIntentMapper.xml")) {
            try (InputStream xml = Resources.getResourceAsStream(path)) {
                new XMLMapperBuilder(xml, cfg, path, cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(cfg));
        requests = template.getMapper(NpcChatRequestMapper.class);
        logs = template.getMapper(NpcChatLogMapper.class);
        transactions = new DataSourceTransactionManager(source);
        writes = new NpcChatWriteService(requests, logs, transactions);
        ReflectionTestUtils.setField(writes,"asrIntents",template.getMapper(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        sql("insert into ums_member(id) values (1001),(1002)");
    }

    private static String table(String schema, String name) {
        int begin = schema.indexOf("CREATE TABLE `" + name + "`");
        assertTrue(begin >= 0, "repository fixture table missing: " + name);
        boolean quoted = false;
        for (int i = begin; i < schema.length(); i++) {
            char ch = schema.charAt(i);
            if (ch == '\\') { i++; continue; }
            if (ch == '\'') {
                if (quoted && i + 1 < schema.length() && schema.charAt(i + 1) == '\'') { i++; continue; }
                quoted = !quoted;
            }
            if (ch == ';' && !quoted) return schema.substring(begin, i);
        }
        throw new AssertionError("unterminated table DDL: " + name);
    }

    @BeforeEach void reset() throws Exception {
        afterFinish.set(null);
        sql("delete from npc_chat_log");
        sql("delete from npc_chat_request");
        sql("delete from npc_asr_intent");
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        workers = Executors.newFixedThreadPool(3);
    }
    @AfterEach void stop() throws Exception {
        afterFinish.set(null);
        workers.shutdownNow(); heartbeat.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(heartbeat.awaitTermination(5, TimeUnit.SECONDS));
    }

    @AfterAll void dropOwnedScratchDatabase() throws Exception {
        if (source != null && ownedSchema != null && ownedSchema.matches("chengyin_mybatis_a02_npc_delete_[a-zA-Z0-9_]+"))
            sql("drop database `" + ownedSchema + "`");
    }

    private void sql(String statement) throws Exception {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) { s.execute(statement); }
    }
    private long count(String expression) throws Exception {
        try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(expression)) {
            assertTrue(r.next()); return r.getLong(1);
        }
    }
    private String text(String requestId) throws Exception {
        try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
                "select safe_text from npc_chat_request where user_id=? and request_id=?")) {
            s.setLong(1, USER); s.setString(2, requestId);
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); return r.getString(1); }
        }
    }
    private NpcChatRequest pending(Long user, long session) {
        NpcChatRequest r = new NpcChatRequest();
        r.setUserId(user); r.setSessionId(session); r.setRequestId(UUID.randomUUID().toString());
        r.setStatus("PROCESSING"); r.setSafetyDecision("NOT_RUN"); r.setRetryable(false); r.setAi(true);
        r.setLeaseToken(UUID.randomUUID().toString()); r.setLeaseUntil(new java.util.Date(System.currentTimeMillis() + 30000));
        r.setActiveSlot(1); return r;
    }
    private NpcChatRequest success(NpcChatRequest r) {
        NpcChatRequest result = new NpcChatRequest();
        org.springframework.beans.BeanUtils.copyProperties(r, result);
        result.setStatus("SUCCEEDED"); result.setSafetyDecision("PASS"); result.setSafeText("private answer");
        result.setActiveSlot(null); return result;
    }
    private NpcChatLog entry(NpcChatRequest r) {
        NpcChatLog e = new NpcChatLog(); e.setUserId(r.getUserId()); e.setActivityId(r.getSessionId());
        e.setProfileId(1L); e.setUserMsg("private question"); e.setNpcReply("private answer"); return e;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(8, TimeUnit.SECONDS), "controlled interleave timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private NpcChatService service(AiGatewayService gateway) {
        NpcChatService service = new NpcChatService();
        NpcProfileMapper profiles = mock(NpcProfileMapper.class);
        NpcProfile profile = new NpcProfile(); profile.setId(1L); profile.setPersona("fixture persona");
        when(profiles.selectGlobalNpc()).thenReturn(profile);
        RoamMapper roam = mock(RoamMapper.class);
        RoamSession session = new RoamSession(); session.setId(99L); session.setMemberId(USER); session.setStatus(0);
        when(roam.selectSessionById(99L)).thenReturn(session);
        NpcMemoryBuilder memory = mock(NpcMemoryBuilder.class);
        when(memory.buildRoam(any())).thenReturn("fixture progress");
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "npcChatRequestMapper", requests);
        ReflectionTestUtils.setField(service, "npcProfileMapper", profiles);
        ReflectionTestUtils.setField(service, "roamMapper", roam);
        ReflectionTestUtils.setField(service, "memoryBuilder", memory);
        ReflectionTestUtils.setField(service, "scheduledExecutorService", heartbeat);
        return service;
    }
    private NpcChatReq query(String id) {
        NpcChatReq q = new NpcChatReq(); q.setRequestId(id); q.setBizId(99L); q.setMessage("private question"); return q;
    }

    @Test void inFlightProviderCannotRecreateDeletedDataOrRepeatPaidRequest() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AiGatewayService gateway = mock(AiGatewayService.class);
        when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            started.countDown(); await(release); return new AiGatewayResult("fixture", "private answer", "fixture", 0);
        });
        NpcChatService service = service(gateway);
        String id = UUID.randomUUID().toString();
        Future<NpcChatResp> call = workers.submit(() -> service.chat(query(id), USER));
        await(started);
        try {
            // Must complete while provider is still blocked: no lock held over network time.
            assertEquals(0, workers.submit(() -> writes.deleteForUser(USER)).get(5, TimeUnit.SECONDS).intValue());
        } finally { release.countDown(); }
        NpcChatResp late = call.get(8, TimeUnit.SECONDS);
        assertEquals("REQUEST_OUTCOME_UNKNOWN", late.getErrorCode());
        assertFalse(late.isRetryable()); assertNotEquals("private answer", late.getSafeText());
        assertNull(text(id)); assertEquals(0, count("select count(*) from npc_chat_log where user_id=1001"));
        assertEquals("REQUEST_OUTCOME_UNKNOWN", service.chat(query(id), USER).getErrorCode());
        verify(gateway, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void lateGatewayAndUnexpectedFailuresCannotRecreateUserMessageLogs() throws Exception {
        for (RuntimeException failure : Arrays.asList(
                new AiServiceException(AiServiceException.Reason.INPUT_BLOCKED, "fixture blocked"),
                new IllegalStateException("fixture unexpected failure"))) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            AiGatewayService gateway = mock(AiGatewayService.class);
            when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
                started.countDown(); await(release); throw failure;
            });
            NpcChatService service = service(gateway);
            String id = UUID.randomUUID().toString();
            Future<NpcChatResp> call = workers.submit(() -> service.chat(query(id), USER));
            await(started);
            try { writes.deleteForUser(USER); }
            finally { release.countDown(); }
            NpcChatResp late = call.get(8, TimeUnit.SECONDS);
            assertEquals("REQUEST_OUTCOME_UNKNOWN", late.getErrorCode());
            assertFalse(late.isRetryable()); assertNull(text(id));
            assertEquals(0, count("select count(*) from npc_chat_log where user_id=1001"));
            assertEquals("REQUEST_OUTCOME_UNKNOWN", service.chat(query(id), USER).getErrorCode());
            verify(gateway, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
        }
    }

    @Test void completedReceiptAndLogAreErasedWithoutRemovingIdentity() throws Exception {
        NpcChatRequest r = pending(USER, 99); writes.admit(r, "timeout"); writes.finish(success(r), entry(r));
        assertEquals(1, writes.deleteForUser(USER));
        assertNull(text(r.getRequestId()));
        assertEquals(1, count("select count(*) from npc_chat_request where user_id=1001"));
        assertEquals(0, count("select count(*) from npc_chat_log where user_id=1001"));
    }

    @Test void deleteCannotSplitLeaseFinishAndLogCommit() throws Exception {
        NpcChatRequest r = pending(USER, 99); writes.admit(r, "timeout");
        CountDownLatch updated = new CountDownLatch(1), release = new CountDownLatch(1), deleting = new CountDownLatch(1);
        afterFinish.set(() -> { updated.countDown(); await(release); });
        Future<NpcChatRequest> finish = workers.submit(() -> writes.finish(success(r), entry(r)));
        await(updated);
        Future<Integer> deletion = workers.submit(() -> { deleting.countDown(); return writes.deleteForUser(USER); });
        await(deleting);
        try {
            assertThrows(TimeoutException.class, () -> deletion.get(250, TimeUnit.MILLISECONDS),
                    "delete must wait for finish+log transaction, not run between the writes");
        } finally { release.countDown(); }
        finish.get(8, TimeUnit.SECONDS); assertEquals(1, deletion.get(8, TimeUnit.SECONDS).intValue());
        assertNull(text(r.getRequestId())); assertEquals(0, count("select count(*) from npc_chat_log"));
    }

    @Test void failedLogInsertRollsBackLeaseFinishAndDeleteFailureRollsBackRedaction() throws Exception {
        NpcChatRequest r = pending(USER, 99); writes.admit(r, "timeout");
        NpcChatLog invalid = entry(r); invalid.setUserMsg(null); // Real NOT NULL violation, not a mapper mock.
        assertThrows(RuntimeException.class, () -> writes.finish(success(r), invalid));
        NpcChatRequest stillPending = writes.read(USER, r.getRequestId());
        assertEquals("PROCESSING", stillPending.getStatus()); assertEquals(r.getLeaseToken(), stillPending.getLeaseToken());
        writes.finish(success(r), entry(r));
        // Scratch-only table rename forces failure after redaction; transaction must restore the answer.
        sql("rename table npc_chat_log to npc_chat_log_unavailable");
        try { assertThrows(RuntimeException.class, () -> writes.deleteForUser(USER)); }
        finally { sql("rename table npc_chat_log_unavailable to npc_chat_log"); }
        assertEquals("private answer", text(r.getRequestId()));
        assertEquals(1, count("select count(*) from npc_chat_log"));
    }

    @Test void allOwnNpcScopesAreDeletedButOtherUserIsUntouched() throws Exception {
        for (long session : new long[] {99, -21, -4611686018427387905L}) {
            NpcChatRequest r = pending(USER, session); writes.admit(r, "timeout"); writes.finish(success(r), entry(r));
        }
        NpcChatRequest other = pending(OTHER, 99); writes.admit(other, "timeout"); writes.finish(success(other), entry(other));
        assertEquals(3, writes.deleteForUser(USER));
        assertEquals(0, count("select count(*) from npc_chat_request where user_id=1001 and safe_text is not null"));
        assertEquals(3, count("select count(*) from npc_chat_request where user_id=1001"));
        assertEquals(1, count("select count(*) from npc_chat_log where user_id=1002"));
        assertEquals("private answer", writes.read(OTHER, other.getRequestId()).getSafeText());
    }

    @Test void repeatedDeletePreservesTombstoneAndNewIdentityStillWorks() throws Exception {
        NpcChatRequest old = pending(USER, 99); writes.admit(old, "timeout");
        assertEquals(0, writes.deleteForUser(USER)); assertEquals(0, writes.deleteForUser(USER));
        NpcChatRequest duplicate = pending(USER, 99); duplicate.setRequestId(old.getRequestId());
        assertNull(writes.admit(duplicate, "timeout").getLeaseToken());
        NpcChatRequest fresh = pending(USER, 99); assertEquals(fresh.getLeaseToken(), writes.admit(fresh, "timeout").getLeaseToken());
        writes.finish(success(fresh), entry(fresh));
        assertEquals("private answer", text(fresh.getRequestId()));
        assertEquals(2, count("select count(*) from npc_chat_request where user_id=1001"));
        assertEquals(1, count("select count(*) from npc_chat_log where user_id=1001"));
    }

    @Test void admissionWaitsForUserBarrierThenObservesDeleteBoundary() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), admitting = new CountDownLatch(1);
        Future<?> deletion = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
            assertEquals(USER, requests.lockChatUser(USER));
            requests.redactChatRequestsByUserId(USER); logs.deleteNpcChatLogsByUserId(USER);
            locked.countDown(); await(release); return null;
        }));
        await(locked);
        NpcChatRequest fresh = pending(USER, 99);
        Future<NpcChatRequest> admission = workers.submit(() -> { admitting.countDown(); return writes.admit(fresh, "timeout"); });
        await(admitting);
        try { assertThrows(TimeoutException.class, () -> admission.get(250, TimeUnit.MILLISECONDS)); }
        finally { release.countDown(); }
        deletion.get(8, TimeUnit.SECONDS);
        assertEquals(fresh.getLeaseToken(), admission.get(8, TimeUnit.SECONDS).getLeaseToken());
        assertEquals(1, count("select count(*) from npc_chat_request where user_id=1001"));
    }
}
