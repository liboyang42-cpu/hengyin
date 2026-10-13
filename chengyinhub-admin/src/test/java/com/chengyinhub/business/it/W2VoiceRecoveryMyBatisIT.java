package com.chengyinhub.business.it;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.web.controller.api.ApiMerchantController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.InputStream;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real isolated MySQL + real MyBatis, supplier mocked: never consumes a paid voice request. */
class W2VoiceRecoveryMyBatisIT {
    private static JdbcTemplate jdbc;
    private static NpcProfileMapper mapper;
    private static DataSourceTransactionManager tm;
    private VoiceCloneClient provider;
    private ApiMerchantController controller;
    private static final String SAMPLE = "https://files.example.com/original.wav";

    @BeforeAll static void database() throws Exception {
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_HOST"), "Explicit isolated test target required");
        MysqlIntegrationTestConfig target = MysqlIntegrationTestConfig.fromEnvironment("w2_voice_recovery");
        try (Connection c = DriverManager.getConnection(target.adminUrl(), target.user(), target.password()); Statement s = c.createStatement()) {
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(c.getMetaData().getDatabaseProductVersion()));
            s.execute("DROP DATABASE IF EXISTS `" + target.databaseName() + "`");
            s.execute("CREATE DATABASE `" + target.databaseName() + "` CHARACTER SET utf8mb4");
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(target.databaseUrl(), target.user(), target.password());
        jdbc = new JdbcTemplate(ds); tm = new DataSourceTransactionManager(ds);
        try (Connection c = ds.getConnection()) {
            ItSchema.applyMigrations(c, "sql/migration_npc_profile.sql", "sql/migration_merchant_npc_voice_20260909.sql",
                    "sql/migration_npc_profile_merchant_fields_20260905.sql", "../sql/migration_npc_voice_request_guard_20261013.sql");
        }
        jdbc.execute("create table mms_merchant(id bigint primary key, name varchar(32))");
        Configuration config = new Configuration(new Environment("test", new SpringManagedTransactionFactory(), ds));
        String resource = "mapper/business/NpcProfileMapper.xml";
        try (InputStream input = W2VoiceRecoveryMyBatisIT.class.getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(NpcProfileMapper.class);
    }
    @BeforeEach void setup() {
        jdbc.update("delete from npc_voice_request"); jdbc.update("delete from npc_profile");
        jdbc.update("insert into npc_profile(id,scope_type,scope_id,name,persona,voice_status) values(42,2,11,'store','',0),(43,2,12,'other','',0),(44,4,11,'node','',0)");
        provider = mock(VoiceCloneClient.class); when(provider.usable()).thenReturn(true);
        controller = controller(201L, 11L, provider);
    }
    private static ApiMerchantController controller(Long member, Long merchant, VoiceCloneClient client) {
        ApiMerchantController c = new ApiMerchantController() { @Override public Long getAppUserId() { return member; } };
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(member, MerchantPermission.PROFILE_WRITE)).thenReturn(new MerchantAccessContext(member, merchant, member, MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(c, "merchantAccessService", access); ReflectionTestUtils.setField(c, "npcProfileMapper", mapper);
        ReflectionTestUtils.setField(c, "voiceTransactionManager", tm); ReflectionTestUtils.setField(c, "voiceCloneClient", client); return c;
    }
    private static NpcProfile request(String id) { NpcProfile n = new NpcProfile(); n.setVoiceRequestId(id); n.setVoiceSample(SAMPLE); n.setVoiceConsent(true); return n; }
    private static NpcProfile current() { return mapper.selectMerchantNpcById(42L); }
    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for controlled race"); }

    @Test void concurrentClaimConsumesProviderOnlyOnceAndCommitsBeforeNetwork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(provider.enroll(SAMPLE)).thenAnswer(c -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(4, current().getVoiceStatus()); assertNotNull(mapper.selectMerchantVoiceRequest(11L, "same"));
            calls.incrementAndGet(); entered.countDown(); await(release); return "original-vendor";
        });
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<AjaxResult> first = pool.submit(() -> controller.npcVoiceEnroll(request("same"))); await(entered);
            Future<AjaxResult> duplicate = pool.submit(() -> controller.npcVoiceEnroll(request("same")));
            Future<AjaxResult> different = pool.submit(() -> controller.npcVoiceEnroll(request("other-request")));
            assertEquals(4, duplicate.get(10, TimeUnit.SECONDS).get("voiceStatus"));
            assertEquals(500, different.get(10, TimeUnit.SECONDS).get("code"));
            release.countDown(); assertEquals(1, first.get(10, TimeUnit.SECONDS).get("voiceStatus"));
            assertEquals(1, calls.get()); assertEquals(1, jdbc.queryForObject("select count(*) from npc_voice_request", Integer.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void simultaneousClaimantsShareOnePersistedOriginalRequest() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        when(provider.enroll(SAMPLE)).thenAnswer(c -> { Thread.sleep(100); return "simultaneous-vendor"; });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            java.util.List<Future<AjaxResult>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) calls.add(pool.submit(() -> { await(start); return controller.npcVoiceEnroll(request("simultaneous")); }));
            start.countDown();
            for (Future<AjaxResult> call : calls) assertEquals(200, call.get(10, TimeUnit.SECONDS).get("code"));
            verify(provider, times(1)).enroll(SAMPLE);
            assertEquals(1, current().getVoiceGeneration());
            assertEquals(1, jdbc.queryForObject("select count(*) from npc_voice_request", Integer.class));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    @Test void resetAndNewEnrollCannotBeOverwrittenByOldCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(provider.enroll(SAMPLE)).thenAnswer(c -> { entered.countDown(); await(release); return "old-vendor"; });
        when(provider.isReady("old-vendor")).thenReturn(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AjaxResult> old = pool.submit(() -> controller.npcVoiceEnroll(request("old"))); await(entered);
            assertEquals(200, controller.npcVoiceReset().get("code"));
            VoiceCloneClient next = mock(VoiceCloneClient.class); when(next.usable()).thenReturn(true);
            when(next.enroll(SAMPLE)).thenReturn("new-vendor"); when(next.isReady("new-vendor")).thenReturn(true);
            assertEquals(2, controller(201L,11L,next).npcVoiceEnroll(request("new")).get("voiceStatus"));
            release.countDown(); assertEquals(false, old.get(10, TimeUnit.SECONDS).get("voiceCurrent"));
            assertEquals("new-vendor", current().getVoiceId()); assertEquals("new", current().getVoiceRequestId());
            assertEquals("old-vendor", mapper.selectMerchantVoiceRequest(11L,"old").getVoiceId());
            assertEquals(2, controller.npcVoiceStatus(request("old")).get("voiceStatus"));
            assertEquals("new-vendor", current().getVoiceId());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void lateStatusAfterResetCannotResurrectOriginalVoice() throws Exception {
        when(provider.enroll(SAMPLE)).thenReturn("original-vendor");
        controller.npcVoiceEnroll(request("original"));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(provider.isReady("original-vendor")).thenAnswer(c -> { entered.countDown(); await(release); return true; });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AjaxResult> status = pool.submit(() -> controller.npcVoiceStatus(request("original"))); await(entered);
            controller.npcVoiceReset(); release.countDown();
            assertEquals(false, status.get(10, TimeUnit.SECONDS).get("voiceCurrent"));
            assertEquals(0, current().getVoiceStatus()); assertNull(current().getVoiceId());
            assertEquals(2, mapper.selectMerchantVoiceRequest(11L,"original").getVoiceStatus());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void recreatedControllerRecoversSamePersistedVendorWithoutReenroll() throws Exception {
        when(provider.enroll(SAMPLE)).thenReturn("original-vendor");
        controller.npcVoiceEnroll(request("original"));
        VoiceCloneClient afterRestart = mock(VoiceCloneClient.class); when(afterRestart.usable()).thenReturn(true);
        when(afterRestart.isReady("original-vendor")).thenReturn(true);
        ApiMerchantController recreated = controller(201L,11L,afterRestart);
        assertEquals(2, recreated.npcVoiceStatus(request("original")).get("voiceStatus"));
        assertEquals(2, recreated.npcVoiceEnroll(request("original")).get("voiceStatus"));
        verify(afterRestart).isReady("original-vendor"); verify(afterRestart, never()).enroll(anyString());
    }
    @Test void lostVendorResponseSurvivesRestartResetAndSameIdRetry() throws Exception {
        when(provider.enroll(SAMPLE)).thenThrow(new AiServiceException("response lost"));
        assertEquals(4, controller.npcVoiceEnroll(request("lost")).get("voiceStatus"));
        ApiMerchantController restarted = controller(201L,11L,provider);
        assertEquals(4, restarted.npcVoiceStatus(request("lost")).get("voiceStatus"));
        restarted.npcVoiceReset();
        assertEquals(4, restarted.npcVoiceEnroll(request("lost")).get("voiceStatus"));
        assertEquals(0, current().getVoiceStatus()); assertEquals(SAMPLE, mapper.selectMerchantVoiceRequest(11L,"lost").getVoiceSample());
        verify(provider, times(1)).enroll(SAMPLE); verify(provider, never()).isReady(anyString());
    }
    @Test void sameIdChangedPayloadAndIdentityAreRejectedWithoutDataLeak() throws Exception {
        when(provider.enroll(SAMPLE)).thenThrow(new AiServiceException("timeout")); controller.npcVoiceEnroll(request("id"));
        NpcProfile changed = request("id"); changed.setVoiceSample(SAMPLE+"?different");
        assertEquals(500, controller.npcVoiceEnroll(changed).get("code"));
        ApiMerchantController actor = controller(202L,11L,provider);
        AjaxResult denied = actor.npcVoiceStatus(request("id")); assertEquals(500,denied.get("code")); assertFalse(denied.containsKey("voiceSample"));
        assertEquals(200,actor.npcVoiceStatus().get("code")); assertEquals(500,actor.npcVoiceEnroll(request("id")).get("code"));
        AjaxResult otherMerchant = controller(201L,12L,provider).npcVoiceStatus(request("id"));
        assertEquals(500,otherMerchant.get("code")); assertFalse(otherMerchant.containsKey("voiceSample"));
        verify(provider,times(1)).enroll(SAMPLE);
    }
    @Test void mapperRequiresGenerationRequestOwnerAndMerchantScope() {
        NpcProfile bad = new NpcProfile(); bad.setId(42L); bad.setScopeId(12L); bad.setVoiceGeneration(0L); bad.setVoiceStatus(2);
        assertEquals(0,mapper.updateMerchantNpcVoice(bad)); assertEquals(0,mapper.resetMerchantNpcVoice(bad));
        bad.setScopeId(11L); bad.setId(44L); assertEquals(0,mapper.resetMerchantNpcVoice(bad));
        bad.setId(42L); bad.setVoiceGeneration(9L); assertEquals(0,mapper.resetMerchantNpcVoice(bad));
        bad.setVoiceGeneration(null); assertEquals(0,mapper.updateMerchantNpcVoice(bad));
        assertEquals(0,current().getVoiceStatus());
    }
    @Test void legacyClientsReplayUnknownUntilExplicitResetStartsNewEpoch() throws Exception {
        when(provider.enroll(SAMPLE)).thenThrow(new AiServiceException("lost"));
        NpcProfile old = request(null);
        AjaxResult initial = controller.npcVoiceEnroll(old); assertEquals(4,initial.get("voiceStatus"));
        assertEquals(initial.get("voiceRequestId"),controller.npcVoiceEnroll(old).get("voiceRequestId"));
        verify(provider,times(1)).enroll(SAMPLE);
        controller.npcVoiceReset();
        assertNotEquals(initial.get("voiceRequestId"),controller.npcVoiceEnroll(old).get("voiceRequestId"));
        verify(provider,times(2)).enroll(SAMPLE);
    }
    @Test void delayedResetCommandRejectsNewEnrollmentAndOriginalClearIsIdempotent() throws Exception {
        when(provider.enroll(SAMPLE)).thenReturn("v-original"); when(provider.isReady("v-original")).thenReturn(true);
        controller.npcVoiceEnroll(request("original"));
        NpcProfile expected = request("original"); expected.setId(42L); expected.setVoiceGeneration(current().getVoiceGeneration());
        assertEquals(200,controller.npcVoiceReset(expected).get("code"));
        long resetGeneration = current().getVoiceGeneration();
        assertEquals(200,controller.npcVoiceReset(expected).get("code"));
        assertEquals(resetGeneration,current().getVoiceGeneration());
        when(provider.enroll(SAMPLE)).thenReturn("v-new"); controller.npcVoiceEnroll(request("new"));
        assertEquals(500,controller.npcVoiceReset(expected).get("code"));
        assertEquals("v-new",current().getVoiceId());
        expected.setId(999L); expected.setVoiceGeneration(current().getVoiceGeneration());
        assertEquals(500,controller.npcVoiceReset(expected).get("code"));
    }
}
