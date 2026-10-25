package com.chengyinhub.business.it;

import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.framework.config.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One owned schema and ONE bounded parent pool for real domain SQL. No provider/HTTP authentication claim. */
final class CrossDomainStorageFixture implements AutoCloseable {
    static final long MEMBER = 7_100_000_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000_000L);
    static final String ORDER = "TPLTM_T19_ORIGINAL", REQUEST_HASH = "b".repeat(64);
    final String schema = "cy_wp14_" + UUID.randomUUID().toString().replace("-", "");
    final String original = UUID.randomUUID().toString().replace("-", "");
    String url, user, password;
    JdbcTemplate admin, db;
    DruidDataSource pool;
    DataSourceTransactionManager transactions;
    TransactionTemplate tx;
    SqlSessionTemplate sql;
    WechatVerifiedPaymentFactService facts;
    MerchantCommerceService commerce;
    WechatPaymentCallbackRouter callbacks;
    ITopicRouteRuntimeService route;
    OfficialBroadcastServiceImpl notifications;
    LettuceConnectionFactory redisFactory;
    StringRedisTemplate redis;
    RedisTemplate<Object,Object> productionRedis;
    AiQuotaRecoveryService recovery;
    AiNotSentGatewayFixture gateway;
    final Set<String> ownedRedis = new LinkedHashSet<>();
    boolean created;
    final CountDownLatch poisonEntered = new CountDownLatch(1), releasePoison = new CountDownLatch(1);
    final AtomicInteger subscriptionCalls = new AtomicInteger();
    final List<Throwable> subscriptionFailures = new java.util.concurrent.CopyOnWriteArrayList<>();
    HttpServer subscriptionServer;
    ExecutorService subscriptionWorkers;
    long poisonId, healthyId;
    Reference payment;

    void open() throws Exception {
        assertEquals("github-hosted", required("RUNNER_ENVIRONMENT"));
        assertEquals("1", required("B_PAYMENT_FACT_DDL_APPROVED"));
        assertTrue(List.of("127.0.0.1", "localhost").contains(required("CHENGYIN_MYBATIS_IT_HOST")));
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT")); assertTrue(port > 0 && port < 65536);
        user = required("CHENGYIN_MYBATIS_IT_USER"); password = required("CHENGYIN_MYBATIS_IT_PASSWORD");
        String prefix = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(prefix + "mysql" + options, user, password));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4"); created = true;
        url = prefix + schema + options;
        DriverManagerDataSource ddl = new DriverManagerDataSource(url, user, password);
        JdbcTemplate setup = new JdbcTemplate(ddl);
        // Same canonical source sequence as ClmCommercePaymentProofMySqlIT; no guessed historical proof.
        P0AcceptanceSchema.baseTables(setup, "sys_config", "ums_member", "club", "club_member", "oms_order", "merchant_subscription", "cms_registration");
        P0AcceptanceSchema.migrations(ddl, "migration_club_open_settings_20260909.sql", "migration_club_role_membership_20260823.sql",
                "migration_club_role_membership_resume_fence_20260826.sql", "migration_20260821_commerce_order_idempotency.sql",
                "migration_business_audit_event_20260823.sql", "migration_20261023_wx_pay_order_intent.sql",
                "migration_wx_payment_callback_inbox_outbox_20260725.sql", "migration_20261024_wx_verified_payment_fact.sql",
                "migration_20261024_clm_commerce_payment_proof.sql", "migration_20260930_registration_first_successful_scan.sql");
        try (var input = new ClassPathResource("it-schema/cms-topic-it-schema.sql").getInputStream()) {
            // Existing resource contains the one current topic CREATE, not a fixture rewrite.
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            setup.execute(text.substring(text.indexOf("CREATE TABLE"), text.lastIndexOf(';')));
        }
        P0AcceptanceSchema.createTableFromMigration(setup, "migration_20260823_topic_route_graph.sql");
        String routeDdl = Files.readString(P0AcceptanceSchema.repoRoot().resolve("chengyinhub-admin/sql/migration_20260823_topic_route_graph.sql"));
        int actionStart = routeDdl.indexOf("CREATE TABLE IF NOT EXISTS topic_route_action (");
        assertTrue(actionStart >= 0); setup.execute(routeDdl.substring(actionStart, routeDdl.indexOf(';', actionStart)));
        P0AcceptanceSchema.createTableFromMigration(setup, "migration_ai_quota_reservation_recovery_20261013.sql");
        P0AcceptanceSchema.createTableFromMigration(setup, "migration_photo_check_intent_20261023.sql");
        try (var input = new ClassPathResource("it-schema/official-broadcast-delivery-it-schema.sql").getInputStream()) {
            // Extract original two CREATE statements; ums_member comes from the canonical base above.
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String name : List.of("official_broadcast", "official_broadcast_log")) {
                String start = "CREATE TABLE `" + name + "` ("; int from = text.indexOf(start), to = text.indexOf(';', from);
                assertTrue(from >= 0 && to > from); setup.execute(text.substring(from, to));
            }
        }
        P0AcceptanceSchema.migrations(ddl, "migration_20261023_d_official_broadcast_delivery.sql", "migration_im.sql",
                "migration_im_send_idempotency_20260913.sql", "migration_20261012_im_media_visibility.sql");
        setup.execute("CREATE TABLE wp14_probe(original_id VARCHAR(32) PRIMARY KEY,attempts INT NOT NULL DEFAULT 0,effects INT NOT NULL DEFAULT 0) ENGINE=InnoDB");
        setup.execute("CREATE TABLE wp14_probe_receipt(original_id VARCHAR(32) PRIMARY KEY) ENGINE=InnoDB");
        setup.update("INSERT INTO wp14_probe(original_id) VALUES(?)", original);
        pool = new DruidDataSource(); pool.setUrl(url); pool.setUsername(user); pool.setPassword(password);
        pool.setInitialSize(0); pool.setMinIdle(0); pool.setMaxActive(12); pool.setMaxWait(2000); pool.init();
        db = new JdbcTemplate(pool); db.setQueryTimeout(3);
        assertEquals(schema, db.queryForObject("SELECT DATABASE()", String.class));
        transactions = new DataSourceTransactionManager(pool); tx = new TransactionTemplate(transactions);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(pool);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(Arrays.stream(new String[]{"OmsOrderMapper", "MerchantSubscriptionMapper", "OfficialBroadcastMapper", "OfficialBroadcastLogMapper",
                "UmsMemberMapper", "ImConversationMapper", "ImConversationMemberMapper", "ImMessageMapper", "TopicRouteSessionMapper",
                "CmsTopicMapper", "CmsRegistrationMapper", "AiQuotaReservationMapper", "PhotoCheckIntentMapper"})
                .map(name -> new ClassPathResource("mapper/business/" + name + ".xml")).toArray(ClassPathResource[]::new));
        sql = new SqlSessionTemplate(factory.getObject());
        db.update("INSERT INTO ums_member(id,wx_open_id) VALUES(?,'fixture-openid')", MEMBER);
        installPayments(); installRoute(); installNotifications(); installRedis();
    }

    private void installPayments() {
        facts = WechatVerifiedFactTestSupport.realFacts(pool);
        commerce = new MerchantCommerceService(false);
        set(commerce, "orderMapper", sql.getMapper(OmsOrderMapper.class)); set(commerce, "subscriptionMapper", sql.getMapper(MerchantSubscriptionMapper.class));
        set(commerce, "paymentTransactions", transactions); set(commerce, "verifiedPaymentFacts", facts);
        set(commerce, "wechatPayService", WechatVerifiedFactTestSupport.realPaymentGuards(pool, "merchant", facts));
        callbacks = new WechatPaymentCallbackRouter(null, commerce, null, null, null);
        db.update("INSERT INTO oms_order(id,order_sn,member_id,mms_merchant_id,biz_type,pay_amount,status,pay_type,entitlement_days,entitlement_max_usage) VALUES(8,?,?,34,'premium_template',19.90,0,0,30,3)", ORDER, MEMBER);
        db.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,attempt_no,appid,trade_type,currency,total_fen,request_hash,payer_openid,request_description,notify_url) VALUES('merchant',?,?,'COMMERCE',8,1,'wx-original','JSAPI','CNY',1990,?,'','','https://example.test/notify')", ORDER, ORDER, REQUEST_HASH);
        // Test-only trusted provider observation; real fact validation/persistence, not WeChat signature/HTTP acceptance.
        payment = facts.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, "merchant", "wx-original", ORDER, ORDER, ORDER,
                "T19-TX", "T19-TX", "JSAPI", "CNY", 1990, 1990, Instant.parse("2026-10-25T00:00:00Z"), REQUEST_HASH, "SUCCESS"));
    }

    Map<String,String> paymentPayload() {
        return Map.of("out_trade_no", ORDER, "trade_state", "SUCCESS", "total", "1990", "currency", "CNY", "transaction_id", "T19-TX",
                "verified_fact_id", Long.toString(payment.id()), "verified_fact_hash", payment.hash());
    }
    void assertPaymentReadback() {
        assertEquals(1, db.queryForObject("SELECT status FROM oms_order WHERE id=8", Integer.class));
        assertEquals(MEMBER, db.queryForObject("SELECT member_id FROM oms_order WHERE id=8", Long.class));
        assertEquals(payment.id(), db.queryForObject("SELECT verified_payment_fact_id FROM oms_order WHERE id=8", Long.class));
        assertEquals(payment.hash(), db.queryForObject("SELECT CONVERT(verified_payment_fact_hash USING ascii) FROM oms_order WHERE id=8", String.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM merchant_subscription WHERE merchant_id=?", Integer.class, MEMBER));
        var original = facts.requireVerified(payment); assertEquals(payment, original.reference());
        assertEquals("T19-TX", original.facts().transactionId()); assertEquals("CNY", original.facts().currency());
        assertEquals(1990L, original.facts().totalFen()); assertEquals(REQUEST_HASH, original.facts().intentRequestHash());
    }

    private void installRoute() throws Exception {
        db.update("INSERT INTO cms_topic(id,member_id,name,del_flag,published_config_version,route_mode) VALUES(80,?,'cashless witness',0,1,'BRANCH_GRAPH')", MEMBER);
        db.update("INSERT INTO cms_registration(registration_no,owner_type,owner_id,topic_id,member_id,payment_status,registration_status) VALUES('T19-ACCESS',1,80,80,?,2,2)", MEMBER);
        var state = new TopicRouteEngine.State(); state.setStatus("ACTIVE"); state.setCurrentNodeId(11L);
        state.getNodeStates().put(11L, TopicRouteState.PLAYABLE);
        TopicRouteSession row = new TopicRouteSession(); row.setActivityId(0L); row.setTopicId(80L); row.setMemberId(MEMBER);
        row.setPlayInstanceId("default"); row.setConfigVersion(1L); row.setConfigSnapshotJson("{\"schemaVersion\":1,\"startNodeId\":11,\"terminalNodeIds\":[12],\"edges\":[]}");
        row.setStateJson(new ObjectMapper().writeValueAsString(state)); row.setStatus(0); row.setSeed(123L); row.setVersion(7);
        row.setCreateTime(new Date()); row.setUpdateTime(new Date()); assertEquals(1, sql.getMapper(TopicRouteSessionMapper.class).insertSession(row));
        TopicRouteRuntimeServiceImpl target = new TopicRouteRuntimeServiceImpl(sql.getMapper(TopicRouteSessionMapper.class), sql.getMapper(CmsTopicMapper.class),
                null, null, null, null, sql.getMapper(CmsRegistrationMapper.class), null, () -> 123L, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        route = proxy(target);
    }

    private void installNotifications() throws Exception {
        ImServiceImpl im = new ImServiceImpl(); set(im, "conversationMapper", sql.getMapper(ImConversationMapper.class));
        set(im, "memberMapper", sql.getMapper(ImConversationMemberMapper.class)); set(im, "messageMapper", sql.getMapper(ImMessageMapper.class));
        notifications = new OfficialBroadcastServiceImpl(); set(notifications, "broadcastMapper", sql.getMapper(OfficialBroadcastMapper.class));
        set(notifications, "logMapper", sql.getMapper(OfficialBroadcastLogMapper.class)); set(notifications, "memberMapper", sql.getMapper(UmsMemberMapper.class));
        set(notifications, "imService", (IImService) proxy(im)); set(notifications, "transactionManager", transactions); set(notifications, "officialTemplate", "configured");
        OfficialAudienceMapper audience = mock(OfficialAudienceMapper.class); when(audience.selectPlayerIds(any())).thenReturn(List.of(MEMBER));
        set(notifications, "audienceMapper", audience); // fixed test recipient; real audience/JWT admission is outside this witness.
        subscriptionWorkers = Executors.newSingleThreadExecutor();
        subscriptionServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        subscriptionServer.setExecutor(subscriptionWorkers);
        subscriptionServer.createContext("/subscribe", exchange -> {
            boolean held = releasePoison.getCount() > 0;
            try {
                assertEquals("POST", exchange.getRequestMethod());
                assertTrue(exchange.getRequestHeaders().getFirst("Content-Type").contains("application/json"));
                exchange.getRequestBody().readAllBytes();
                if (held) { poisonEntered.countDown(); assertTrue(releasePoison.await(60, TimeUnit.SECONDS)); }
                byte[] reply = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, reply.length); exchange.getResponseBody().write(reply);
            } catch (java.io.IOException cancelled) { if (!held) subscriptionFailures.add(cancelled); }
            catch (Throwable failure) { subscriptionFailures.add(failure); }
            finally { exchange.close(); }
        });
        subscriptionServer.start();
        var provider = new CrossDomainSubscriptionProvider("http://127.0.0.1:" + subscriptionServer.getAddress().getPort() + "/subscribe", subscriptionCalls);
        var memberService = new UmsMemberServiceImpl(); set(memberService, "umsMemberMapper", sql.getMapper(UmsMemberMapper.class));
        set(provider, "umsMemberService", memberService);
        IWxContentSecurityService token = mock(IWxContentSecurityService.class); when(token.getAccessToken()).thenReturn("fixture-access");
        set(provider, "wxSec", token); set(provider, "tplOfficialEvent", "fixture-official-template");
        set(provider, "defaultPage", "pages/index/index");
        set(notifications, "wxSubscribeMsgService", provider);
    }
    long broadcast(String channels) {
        OfficialBroadcast row = new OfficialBroadcast(); row.setTitle("controlled broadcast"); row.setAudience("player"); row.setChannels(channels);
        row.setCopyMode(1); row.setContentJson("{\"title\":\"controlled\",\"sub\":\"no external delivery\"}"); row.setStatus(1); row.setCreateTime(new Date());
        assertEquals(1, sql.getMapper(OfficialBroadcastMapper.class).insertOfficialBroadcast(row)); return row.getId();
    }

    private void installRedis() {
        int port = Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_PORT")); int database = Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_DATABASE"));
        assertTrue(port > 0 && port < 65536 && database >= 0 && database <= 15);
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", port); configuration.setDatabase(database);
        String secret = System.getenv("CHENGYIN_AI_REDIS_IT_PASSWORD"); if (secret != null && !secret.isEmpty()) configuration.setPassword(secret);
        redisFactory = new LettuceConnectionFactory(configuration); redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory); productionRedis = new RedisConfig().redisTemplate(redisFactory);
        recovery = new AiQuotaRecoveryService(sql.getMapper(AiQuotaReservationMapper.class), productionRedis, transactions, mock(NpcAsrIntentMapper.class));
        gateway = new AiNotSentGatewayFixture(MEMBER); set(gateway.gateway, "redisTemplate", productionRedis); set(gateway.gateway, "quotaRecovery", recovery);
        String originalCounter = gateway.gateway.planQuotaReservation(MEMBER, "photo_check", "0".repeat(32)).split("\\|", 2)[0];
        assertFalse(Boolean.TRUE.equals(redis.hasKey(originalCounter)), "random fixture actor must not reuse another actor's quota");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(originalCounter + ":generation")));
    }
    PhotoCheckIntentService photo(LlmClient client, java.util.function.Supplier<String> image) {
        ConsentGuard consent = mock(ConsentGuard.class); AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
        when(media.requireAttachment(eq(MEMBER), anyString(), eq(true))).thenReturn("media-asset:42");
        when(media.processingUrl("media-asset:42", MEMBER, "IMAGE")).thenReturn("controlled-owned-media");
        VisionImageEncoder encoder = mock(VisionImageEncoder.class); when(encoder.encode("controlled-owned-media")).thenAnswer(call -> VisionImageEncoder.Encoded.ok(image.get()));
        PhotoCheckVisionService vision = new PhotoCheckVisionService(); set(vision, "consentGuard", consent); set(vision, "mediaReferences", media);
        set(vision, "imageEncoder", encoder); set(vision, "visionClient", client); set(vision, "aiGatewayService", gateway.gateway);
        return proxy(new PhotoCheckIntentService(sql.getMapper(PhotoCheckIntentMapper.class), gateway.gateway, vision, consent, media, transactions));
    }
    PhotoCheckIntentCommand command(int number) {
        return new PhotoCheckIntentCommand(MEMBER, 100L + number, 0L, 80L, 11L, 80L, null, null, 1, 7, 1,
                "default", "t19-action-" + number, String.format("%064x", number), "media-asset:42", "controlled requirement", "e".repeat(64));
    }
    String field(long intent, String column) {
        assertTrue(Set.of("nonce", "reservation", "state", "provider_state").contains(column));
        return db.queryForObject("SELECT " + column + " FROM photo_check_intent WHERE id=?", String.class, intent);
    }
    void rememberQuotaKeys() {
        if (db == null || redis == null) return;
        for (var row : db.queryForList("SELECT counter_key,receipt_key FROM ai_quota_reservation WHERE member_id=?", MEMBER)) {
            String counter = (String)row.get("counter_key"), receipt = (String)row.get("receipt_key");
            assertTrue(counter.matches("ai:gen:limit:photo:" + MEMBER + ":[0-9]{8}"));
            assertTrue(receipt.startsWith("ai:gen:reservation:" + counter + ":")); ownedRedis.add(counter); ownedRedis.add(counter + ":generation"); ownedRedis.add(receipt);
        }
    }
    <T> T proxy(T target) {
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T result = (T)proxy.getProxy(); return result;
    }
    static void set(Object target, String name, Object value) { ReflectionTestUtils.setField(target, name, value); }
    static String required(String name) {
        String value = System.getenv(name); assertNotNull(value, "explicit controlled selector required: " + name);
        if (!name.endsWith("_PASSWORD")) assertFalse(value.isBlank(), "blank selector: " + name); return value;
    }
    @Override public void close() throws Exception {
        releasePoison.countDown();
        List<Throwable> failures = new ArrayList<>();
        try { if (subscriptionServer != null) subscriptionServer.stop(0); if (subscriptionWorkers != null) {
            subscriptionWorkers.shutdownNow(); assertTrue(subscriptionWorkers.awaitTermination(10, TimeUnit.SECONDS)); } }
        catch (Throwable failure) { failures.add(failure); }
        failures.addAll(subscriptionFailures);
        try { rememberQuotaKeys(); if (redis != null) for (String key : ownedRedis) { redis.delete(key); assertFalse(Boolean.TRUE.equals(redis.hasKey(key))); } }
        catch (Throwable failure) { failures.add(failure); }
        try { if (redisFactory != null) redisFactory.destroy(); } catch (Throwable failure) { failures.add(failure); }
        try { if (pool != null) { assertEquals(0, pool.getActiveCount(), "parent business connections must exit before pool close"); pool.close(); } }
        catch (Throwable failure) { failures.add(failure); if (pool != null) pool.close(); }
        try { if (created) { assertTrue(schema.matches("cy_wp14_[a-f0-9]{32}")); admin.execute("DROP DATABASE `" + schema + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)); created = false; } }
        catch (Throwable failure) { failures.add(failure); }
        if (!failures.isEmpty()) { Exception result = new Exception("owned cross-domain cleanup failed"); failures.forEach(result::addSuppressed); throw result; }
    }
}
