package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.domain.PhotoCheckIntentRow;
import com.chengyinhub.business.mapper.AiQuotaReservationMapper;
import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.framework.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Gateway/Vision/Intent/recovery SQL and Redis Lua; typed provider is a no-network double. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(45)
class AiNotSentQuotaRecoveryMySqlRedisIT
{
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }

    private String adminUrl, schema, user, password;
    private boolean created;
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private AiQuotaReservationMapper quotas;
    private PhotoCheckIntentMapper intents;
    private DataSourceTransactionManager transactions;
    private LettuceConnectionFactory redisFactory;
    private RedisTemplate<Object, Object> production;
    private StringRedisTemplate raw;
    private long member;
    private AiNotSentGatewayFixture fixture;
    private AiQuotaRecoveryService recovery;
    private final AtomicBoolean redisUnavailable = new AtomicBoolean();
    private final Set<String> ownedKeys = new HashSet<>();
    private AnnotationConfigApplicationContext context;

    @BeforeAll void connect() throws Exception
    {
        int mysqlPort = Integer.parseInt(required("CHENGYIN_AI_MYSQL_IT_PORT", false));
        assertTrue(mysqlPort > 0 && mysqlPort <= 65535);
        user = required("CHENGYIN_AI_MYSQL_IT_USER", false);
        password = required("CHENGYIN_AI_MYSQL_IT_PASSWORD", true);
        adminUrl = "jdbc:mysql://127.0.0.1:" + mysqlPort + "/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
        schema = "cy_ai_not_sent_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement statement = admin.createStatement())
        {
            assertEquals("MySQL", admin.getMetaData().getDatabaseProductName());
            assertTrue(admin.getMetaData().getDatabaseMajorVersion() >= 8);
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4"); created = true;
        }
        source = new DriverManagerDataSource(adminUrl.replace("/?", "/" + schema + "?"), user, password);
        db = new JdbcTemplate(source);
        Path sql = Paths.get("chengyinhub-admin/sql"); if (!Files.isDirectory(sql)) sql = Paths.get("sql");
        // Only existing canonical table DDL, as in the existing recovery fixture; no Quartz job registration.
        for (String migration : List.of("migration_ai_quota_reservation_recovery_20261013.sql", "migration_photo_check_intent_20261023.sql"))
            db.execute(Files.readString(sql.resolve(migration)).split(";", 2)[0]);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setEnvironment(new Environment("ai-not-sent", new SpringManagedTransactionFactory(), source));
        for (String resource : List.of("mapper/business/AiQuotaReservationMapper.xml", "mapper/business/PhotoCheckIntentMapper.xml"))
            try (InputStream xml = Resources.getResourceAsStream(resource))
            { new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse(); }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        quotas = session.getMapper(AiQuotaReservationMapper.class); intents = session.getMapper(PhotoCheckIntentMapper.class);
        transactions = new DataSourceTransactionManager(source);
        int redisPort = Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_PORT", false));
        int database = Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_DATABASE", false));
        assertTrue(redisPort > 0 && redisPort <= 65535 && database >= 0 && database <= 15);
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration("127.0.0.1", redisPort); redis.setDatabase(database);
        String redisPassword = System.getenv("CHENGYIN_AI_REDIS_IT_PASSWORD");
        if (redisPassword != null && !redisPassword.isEmpty()) redis.setPassword(redisPassword);
        redisFactory = new LettuceConnectionFactory(redis); redisFactory.afterPropertiesSet();
        production = new RedisConfig().redisTemplate(redisFactory); raw = new StringRedisTemplate(redisFactory);
    }

    @BeforeEach void originalIdentity()
    {
        member = 1000000000000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 8000000000000L);
        redisUnavailable.set(false);
        fixture = new AiNotSentGatewayFixture(member);
        RedisTemplate<Object, Object> faultBoundary = new RedisTemplate<>() {
            @Override public <T> T execute(RedisScript<T> script, RedisSerializer<?> argsSerializer,
                    RedisSerializer<T> resultSerializer, List<Object> keys, Object... args)
            {
                if (redisUnavailable.get()) throw new IllegalStateException("controlled Redis outage");
                return production.execute(script, argsSerializer, resultSerializer, keys, args);
            }
        };
        recovery = new AiQuotaRecoveryService(quotas, faultBoundary, transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        ReflectionTestUtils.setField(fixture.gateway, "redisTemplate", production);
        ReflectionTestUtils.setField(fixture.gateway, "quotaRecovery", recovery);
    }

    @AfterEach void cleanupOwnedKeys()
    {
        if (context != null) { context.close(); context = null; }
        if (db != null && raw != null && member > 0)
        {
            for (var row : db.queryForList("SELECT counter_key,receipt_key FROM ai_quota_reservation WHERE member_id=?", member))
            {
                String counter = String.valueOf(row.get("counter_key")), receipt = String.valueOf(row.get("receipt_key"));
                assertTrue(counter.matches("ai:gen:limit:(gen|npc|photo):" + member + ":[0-9]{8}"));
                assertTrue(receipt.startsWith("ai:gen:reservation:" + counter + ":"));
                ownedKeys.add(counter); ownedKeys.add(counter + ":generation"); ownedKeys.add(receipt);
            }
            for (String key : ownedKeys) raw.delete(key);
            for (String key : ownedKeys) assertFalse(Boolean.TRUE.equals(raw.hasKey(key)), "task-owned Redis key must be cleaned");
            ownedKeys.clear();
        }
    }

    @AfterAll void cleanup() throws Exception
    {
        if (context != null) context.close();
        if (redisFactory != null) redisFactory.destroy();
        if (created)
        {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement statement = admin.createStatement())
            { statement.execute("DROP DATABASE `" + schema + "`"); }
            assertEquals(0, new JdbcTemplate(new DriverManagerDataSource(adminUrl, user, password)).queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
            created = false;
        }
    }

    @Test void typedNotSentDecisionSurvivesRedisOutageAndRestartRefundsOnlyOriginalReceiptOnce()
    {
        ownCounter(currentCounter("merchant_plan"));
        when(fixture.model.chat(anyString(), anyString())).thenAnswer(call -> {
            redisUnavailable.set(true); throw new AiProviderNotSentException();
        });
        AiServiceException failure = assertThrows(AiServiceException.class,
                () -> fixture.gateway.generate(member, "club", "merchant_plan", "system", "user"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, failure.getReason());
        String receipt = receipt(); String token = token(receipt); String counter = counter(receipt);
        assertEquals("REFUND_DUE", state(receipt)); assertEquals("1", raw.opsForValue().get(counter));
        assertOriginal(receipt, "merchant_plan", "gen", counter);
        forceDue(receipt);
        AiQuotaRecoveryService restarted = new AiQuotaRecoveryService(quotas, production, transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class));
        restarted.runDue(); restarted.runDue();
        assertEquals("REFUNDED", state(receipt)); assertEquals("0", raw.opsForValue().get(counter));
        assertEquals("0", raw.opsForValue().get(receipt));
        fixture.gateway.refundQuota(member, "merchant_plan", token);
        assertEquals("0", raw.opsForValue().get(counter));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ai_quota_reservation WHERE member_id=?", Integer.class, member));
        verify(fixture.model, times(1)).chat(anyString(), anyString());
    }

    @Test void genericProviderFailureRetainsOriginalReservationAcrossRecoveryInsteadOfRefunding()
    {
        ownCounter(currentCounter("merchant_plan"));
        when(fixture.model.chat(anyString(), anyString())).thenThrow(new IllegalStateException("controlled timeout"));
        assertThrows(AiProviderOutcomeUnknownException.class,
                () -> fixture.gateway.generate(member, "club", "merchant_plan", "system", "user"));
        String receipt = receipt(); String counter = counter(receipt); assertEquals("RESERVED", state(receipt));
        String originalRedisReceipt = raw.opsForValue().get(receipt); assertNotNull(originalRedisReceipt);
        assertEquals("MODEL_STARTED", db.queryForObject("SELECT stage FROM ai_quota_reservation WHERE receipt_key=?", String.class, receipt));
        forceDue(receipt); new AiQuotaRecoveryService(quotas, production, transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class)).runDue();
        assertEquals("UNCERTAIN", state(receipt)); assertEquals("1", raw.opsForValue().get(counter));
        assertEquals(originalRedisReceipt, raw.opsForValue().get(receipt));
        assertOriginal(receipt, "merchant_plan", "gen", counter);
        verify(fixture.model, times(1)).chat(anyString(), anyString());
    }

    @Test void expiredCompensationDecisionCasCannotBypassFenceOrDirectlyRefundRedis()
    {
        ownCounter(currentCounter("merchant_plan"));
        when(fixture.model.chat(anyString(), anyString())).thenAnswer(call -> {
            db.update("UPDATE ai_quota_reservation SET request_lease_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE member_id=?", member);
            throw new AiProviderNotSentException();
        });
        AiServiceException failure = assertThrows(AiServiceException.class,
                () -> fixture.gateway.generate(member, "club", "merchant_plan", "system", "user"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, failure.getReason());
        String receipt = receipt(); String counter = counter(receipt); assertEquals("RESERVED", state(receipt));
        assertEquals("1", raw.opsForValue().get(counter));
        forceDue(receipt); new AiQuotaRecoveryService(quotas, production, transactions,org.mockito.Mockito.mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class)).runDue();
        assertEquals("UNCERTAIN", state(receipt)); assertEquals("1", raw.opsForValue().get(counter));
        verify(fixture.model, times(1)).chat(anyString(), anyString());
    }

    @Test void originalPhotoIntentStoresNotSentAndReplaysOldDayNonceWithoutAnotherProviderAttempt() throws Exception
    {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String priorDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String counter = "ai:gen:limit:photo:" + member + ":" + priorDay;
        String receipt = "ai:gen:reservation:" + counter + ":" + nonce;
        ownCounter(counter); own(receipt);
        String today = currentCounter("photo_check"); ownCounter(today);
        raw.opsForValue().set(today, "9"); raw.opsForValue().set(today + ":generation", "unrelated-today-generation");
        String fingerprint = "a".repeat(64);
        PhotoCheckIntentCommand command = new PhotoCheckIntentCommand(member, 8L, 9L, 10L, 11L, 12L, 13L, 14L,
                1, 2, 3, "original-instance", "original-photo-key", fingerprint, "media-asset:42", "a red door", "b".repeat(64));
        // A persisted PREPARED intent from a prior Beijing day, not a new plan/nonce at replay time.
        PhotoCheckIntentRow original = new PhotoCheckIntentRow();
        original.setMemberId(member); original.setSessionId(8L); original.setActionKey(command.actionKey());
        original.setRequestFingerprint(fingerprint); original.setContextJson(new ObjectMapper().writeValueAsString(command));
        original.setImageReference(command.imageReference()); original.setNonce(nonce);
        original.setOriginalDay(priorDay); original.setReservation(counter + "|" + nonce);
        new TransactionTemplate(transactions).execute(status -> intents.insertPrepared(original));
        PhotoCheckIntentService service = photoService();
        when(fixture.model.chatVision(anyString(), anyString(), anyString())).thenThrow(new AiProviderNotSentException());
        PhotoCheckIntentView result = service.executeOnce(member, original.getId(), fingerprint);
        assertEquals(PhotoCheckIntentState.RESULT_READY, result.state()); assertEquals("NOT_SENT", result.providerState());
        assertEquals("PROVIDER_NOT_SENT", result.reasonCode()); assertTrue(result.result().isDegraded());
        assertEquals("REFUNDED", state(receipt)); assertEquals("0", raw.opsForValue().get(counter));
        assertEquals("9", raw.opsForValue().get(today)); assertOriginal(receipt, "photo_check", "photo", counter);
        var stored = db.queryForMap("SELECT * FROM photo_check_intent WHERE id=?", original.getId());
        var replay = service.executeOnce(member, original.getId(), fingerprint);
        var read = service.read(member, 8L, command.actionKey(), fingerprint);
        sameResult(result, replay); sameResult(result, read);
        assertEquals(stored, db.queryForMap("SELECT * FROM photo_check_intent WHERE id=?", original.getId()));
        assertEquals(nonce, db.queryForObject("SELECT nonce FROM photo_check_intent WHERE id=?", String.class, original.getId()));
        assertEquals(original.getReservation(), db.queryForObject("SELECT reservation FROM photo_check_intent WHERE id=?", String.class, original.getId()));
        assertNotNull(db.queryForObject("SELECT encoded_input_sha256 FROM photo_check_intent WHERE id=?", String.class, original.getId()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ai_quota_reservation WHERE member_id=?", Integer.class, member));
        verify(fixture.model, times(1)).chatVision(anyString(), anyString(), anyString());
        verify(fixture.model, never()).chat(anyString(), anyString());
    }

    private PhotoCheckIntentService photoService()
    {
        ConsentGuard consent = mock(ConsentGuard.class); AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
        when(media.processingUrl("media-asset:42", member, "IMAGE")).thenReturn("owned-approved-image");
        VisionImageEncoder encoder = mock(VisionImageEncoder.class);
        when(encoder.encode("owned-approved-image")).thenReturn(VisionImageEncoder.Encoded.ok("data:image/jpeg;base64,AA=="));
        PhotoCheckVisionService vision = new PhotoCheckVisionService();
        ReflectionTestUtils.setField(vision, "consentGuard", consent); ReflectionTestUtils.setField(vision, "mediaReferences", media);
        ReflectionTestUtils.setField(vision, "imageEncoder", encoder); ReflectionTestUtils.setField(vision, "visionClient", fixture.model);
        ReflectionTestUtils.setField(vision, "aiGatewayService", fixture.gateway);
        context = new AnnotationConfigApplicationContext(); context.register(Transactions.class);
        // Already-built collaborators: do not let Spring populate their fields or recurse into Mockito mocks.
        context.getBeanFactory().registerSingleton("transactionManager", transactions);
        context.getBeanFactory().registerSingleton(PhotoCheckIntentMapper.class.getName(), intents);
        context.getBeanFactory().registerSingleton(AiGatewayService.class.getName(), fixture.gateway);
        context.getBeanFactory().registerSingleton(PhotoCheckVisionService.class.getName(), vision);
        context.getBeanFactory().registerSingleton(ConsentGuard.class.getName(), consent);
        context.getBeanFactory().registerSingleton(AppMediaReferenceGuard.class.getName(), media);
        context.registerBean(PhotoCheckIntentService.class); context.refresh();
        return context.getBean(PhotoCheckIntentService.class);
    }

    private String currentCounter(String biz)
    { return fixture.gateway.planQuotaReservation(member, biz, "0".repeat(32)).split("\\|", 2)[0]; }
    private String receipt()
    { return db.queryForObject("SELECT receipt_key FROM ai_quota_reservation WHERE member_id=?", String.class, member); }
    private String token(String receipt)
    {
        String counter = counter(receipt);
        String nonce = db.queryForObject("SELECT nonce FROM ai_quota_reservation WHERE receipt_key=?", String.class, receipt);
        return counter + "|" + nonce;
    }
    private String counter(String receipt)
    { return db.queryForObject("SELECT counter_key FROM ai_quota_reservation WHERE receipt_key=?", String.class, receipt); }
    private String state(String receipt)
    { return db.queryForObject("SELECT state FROM ai_quota_reservation WHERE receipt_key=?", String.class, receipt); }
    private void sameResult(PhotoCheckIntentView expected, PhotoCheckIntentView actual)
    {
        assertNotNull(actual);
        assertEquals(expected.intentId(), actual.intentId()); assertEquals(expected.memberId(), actual.memberId());
        assertEquals(expected.sessionId(), actual.sessionId()); assertEquals(expected.attemptLogId(), actual.attemptLogId());
        assertEquals(expected.actionKey(), actual.actionKey()); assertEquals(expected.requestFingerprint(), actual.requestFingerprint());
        assertEquals(expected.state(), actual.state()); assertEquals(expected.providerState(), actual.providerState());
        assertEquals(expected.reasonCode(), actual.reasonCode()); assertNotNull(actual.result());
        // PhotoCheckVisionResult deliberately has no equals/Jackson creator; compare its persisted values.
        assertEquals(expected.result().isPass(), actual.result().isPass());
        assertEquals(expected.result().getConfidence(), actual.result().getConfidence());
        assertEquals(expected.result().getReason(), actual.result().getReason());
        assertEquals(expected.result().isDegraded(), actual.result().isDegraded());
    }
    private void forceDue(String receipt)
    { db.update("UPDATE ai_quota_reservation SET request_lease_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND),next_attempt_at=NOW(3) WHERE receipt_key=?", receipt); }
    private void assertOriginal(String receipt, String biz, String pool, String counter)
    {
        var row = db.queryForMap("SELECT member_id,biz_type,pool,original_day,counter_key,nonce FROM ai_quota_reservation WHERE receipt_key=?", receipt);
        assertEquals(member, ((Number) row.get("member_id")).longValue()); assertEquals(biz, row.get("biz_type"));
        assertEquals(pool, row.get("pool")); assertEquals(counter, row.get("counter_key"));
        assertEquals(counter.substring(counter.length() - 8), row.get("original_day"));
        assertEquals("ai:gen:reservation:" + counter + ":" + row.get("nonce"), receipt);
    }
    private void ownCounter(String counter) { own(counter); own(counter + ":generation"); }
    private void own(String key)
    { assertFalse(Boolean.TRUE.equals(raw.hasKey(key)), "refuse preexisting Redis task key"); ownedKeys.add(key); }
    private static String required(String name, boolean emptyAllowed)
    {
        String value = System.getenv(name); assertNotNull(value, "explicit task configuration required: " + name);
        if (!emptyAllowed) assertFalse(value.isBlank(), "blank task configuration: " + name);
        return value;
    }
}
