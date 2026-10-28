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
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
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
    String serverUuid, serverVersion;
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
    boolean ownsRedisFactory;
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
    volatile Map<String, Object> subscriptionRead = Map.of();
    volatile Map<String, Object> photoPrepareFailure = Map.of("phase", "NOT_OBSERVED");
    // mysql-8.0.45 trx0i_s.cc: can_cache_be_updated requires STRICTLY >100ms since end_read.
    static final long TRX_CACHE_MIN_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private long lastTrxCacheReadEndedNanos = System.nanoTime();
    private final Set<Long> freshnessWitnessIds = new HashSet<>();
    private volatile Map<String, Object> lastTransactionSample = Map.of("phase", "NOT_SAMPLED");

    void open() throws Exception {
        open(null); // Standalone storage witness keeps its original explicitly owned Redis client.
    }

    void open(LettuceConnectionFactory applicationRedis) throws Exception {
        if (applicationRedis != null) requireSharedRedisFactory(applicationRedis,
                Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_PORT")),
                Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_DATABASE")),
                System.getenv("CHENGYIN_AI_REDIS_IT_PASSWORD"));
        assertEquals("github-hosted", required("RUNNER_ENVIRONMENT"));
        assertEquals("1", required("B_PAYMENT_FACT_DDL_APPROVED"));
        assertTrue(List.of("127.0.0.1", "localhost").contains(required("CHENGYIN_MYBATIS_IT_HOST")));
        int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT")); assertTrue(port > 0 && port < 65536);
        user = required("CHENGYIN_MYBATIS_IT_USER"); password = required("CHENGYIN_MYBATIS_IT_PASSWORD");
        String prefix = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(prefix + "mysql" + options, user, password));
        serverVersion = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(serverVersion));
        serverUuid = admin.queryForObject("SELECT @@server_uuid", String.class);
        admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4"); created = true;
        url = prefix + schema + options;
        DriverManagerDataSource ddl = new DriverManagerDataSource(url, user, password);
        JdbcTemplate setup = new JdbcTemplate(ddl);
        // Same canonical source sequence as ClmCommercePaymentProofMySqlIT; no guessed historical proof.
        P0AcceptanceSchema.baseTables(setup, "sys_config", "ums_member", "club", "club_member", "oms_order", "merchant_subscription", "oms_ticket", "cms_registration");
        P0AcceptanceSchema.migrations(ddl, "migration_app_open_id.sql", "migration_club_open_settings_20260909.sql", "migration_club_role_membership_20260823.sql",
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
        installPayments(); installRoute(); installNotifications(); installRedis(applicationRedis);
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
        db.update("INSERT INTO cms_topic(id,member_id,name,product_type,del_flag,published_config_version,route_mode) VALUES(80,?,'cashless witness',2,0,1,'BRANCH_GRAPH')", MEMBER);
        // A real same-owner FREE_EXPLORE ticket backs this zero-cash access fixture. It is
        // unrelated to the original 19.90 commerce payment and is not a nullable/zero ticket ID.
        db.update("INSERT INTO oms_ticket(id,member_id,owner_type,owner_id,name,price,mode,total_inventory,remaining_inventory,del_flag) "
                + "VALUES(81,?,1,80,'cashless access',0.00,2,1,0,0)", MEMBER);
        db.update("INSERT INTO cms_registration(registration_no,owner_type,owner_id,topic_id,member_id,ticket_id,ticket_name,ticket_price,order_num,"
                + "original_amount,discount_amount,total_amount,payable_amount,point_used,point_deduct_amount,point_payment_amount,wechat_payment_amount,"
                + "payment_type,payment_status,registration_status,payment_time,pay_expire_time,purchase_kind,expires_at) "
                + "VALUES('T19-ACCESS',1,80,80,?,81,'cashless access',0.00,1,0.00,0.00,0.00,0.00,0,0.00,0.00,0.00,"
                + "0,2,2,NOW(),DATE_ADD(NOW(),INTERVAL 30 MINUTE),3,DATE_ADD(NOW(),INTERVAL 1 DAY))", MEMBER);
        assertRouteAccessFixture();
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

    void assertRouteAccessFixture() {
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM cms_registration r "
                + "JOIN oms_ticket t ON t.id=r.ticket_id AND t.owner_type=r.owner_type AND t.owner_id=r.owner_id "
                + "JOIN cms_topic p ON p.id=r.topic_id AND p.id=r.owner_id "
                + "WHERE r.registration_no='T19-ACCESS' AND r.ticket_id=81 AND r.owner_type=1 AND r.owner_id=80 "
                + "AND r.member_id=? AND t.member_id=? AND p.member_id=? AND p.product_type=2 AND t.mode=2 "
                + "AND p.del_flag=0 AND t.del_flag=0 AND r.purchase_kind=3 AND r.order_num=1 "
                + "AND r.payment_status=2 AND r.registration_status=2 AND r.payment_type=0 "
                + "AND r.ticket_name=t.name AND t.price=0.00 AND r.ticket_price=t.price AND r.original_amount=0.00 AND r.discount_amount=0.00 "
                + "AND r.total_amount=0.00 AND r.payable_amount=0.00 AND r.point_used=0 "
                + "AND r.point_deduct_amount=0.00 AND r.point_payment_amount=0.00 AND r.wechat_payment_amount=0.00 "
                + "AND r.payment_time IS NOT NULL AND r.pay_expire_time>r.payment_time AND r.expires_at>NOW() "
                + "AND t.total_inventory=1 AND t.remaining_inventory=0", Integer.class, MEMBER, MEMBER, MEMBER));
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
        // The real notifier reads the complete current member projection before post(). Base schema
        // alone lacks app_open_id; do not hide that mapper prerequisite behind the poison latch.
        UmsMember recipient = memberService.selectUmsMemberById(MEMBER);
        assertNotNull(recipient, "notification fixture must resolve its actual canonical member");
        assertEquals(Long.valueOf(MEMBER), recipient.getId());
        assertEquals("fixture-openid", recipient.getWxOpenId());
        assertNull(recipient.getAppOpenId(), "no guessed App identity or migration backfill");
        // Observe the REAL lookup on its calling thread without changing its propagation or result.
        // NOT_SUPPORTED can still have synchronization-bound, auto-commit MyBatis resources.
        ProxyFactory memberLookup = new ProxyFactory(memberService); memberLookup.setProxyTargetClass(true);
        memberLookup.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if ("selectUmsMemberById".equals(invocation.getMethod().getName())) observeSubscriptionRead();
            return result;
        });
        set(provider, "umsMemberService", memberLookup.getProxy());
        IWxContentSecurityService token = mock(IWxContentSecurityService.class); when(token.getAccessToken()).thenReturn("fixture-access");
        set(provider, "wxSec", token); set(provider, "tplOfficialEvent", "fixture-official-template");
        set(provider, "defaultPage", "pages/index/index");
        set(notifications, "wxSubscribeMsgService", provider);
    }

    private void observeSubscriptionRead() throws Exception {
        Map<String, Object> witness = new LinkedHashMap<>();
        witness.put("role", "notification-member-read");
        witness.put("springTransactionActive", TransactionSynchronizationManager.isActualTransactionActive());
        witness.put("springSynchronizationActive", TransactionSynchronizationManager.isSynchronizationActive());
        Object bound = TransactionSynchronizationManager.getResource(pool);
        witness.put("parentPoolResourceBound", bound instanceof ConnectionHolder);
        if (bound instanceof ConnectionHolder holder) {
            Connection connection = holder.getConnection();
            witness.put("jdbcAutoCommit", connection.getAutoCommit());
            witness.put("jdbcIsolation", connection.getTransactionIsolation());
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (var row = statement.executeQuery("SELECT CONNECTION_ID(),DATABASE(),@@server_uuid,@@session.autocommit")) {
                    assertTrue(row.next()); assertEquals(schema, row.getString(2)); assertEquals(serverUuid, row.getString(3));
                    witness.put("mysqlConnectionId", row.getLong(1));
                    witness.put("mysqlAutoCommit", row.getInt(4) == 1);
                }
            }
        }
        subscriptionRead = Map.copyOf(witness);
    }

    record ParentSqlObservation(long observerConnectionId, int activePoolConnections,
                                List<Map<String, Object>> transactions, Map<String, Object> subscriptionRead,
                                Map<String, Object> freshness) {
        int openTransactions() { return transactions.size(); }
        Map<String, Object> evidence() {
            return Map.of("stage", "parent-domain-sql-transactions", "observerConnectionId", observerConnectionId,
                    "activePoolConnections", activePoolConnections, "transactions", transactions,
                    "notificationRead", subscriptionRead, "scope", "all-sessions-in-exact-owned-schema-no-excluded-ids",
                    "freshness", freshness);
        }
    }

    ParentSqlObservation observeParentTransactions() throws Exception {
        return observeParentTransactions(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
    }

    private synchronized ParentSqlObservation observeParentTransactions(long deadline) throws Exception {
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("phase", "SAMPLING_STARTED"); lastTransactionSample = Map.copyOf(diagnostic);
        requireSampleBudget(System.nanoTime(), deadline);
        int active = pool.getActiveCount();
        // Both connections use the ORIGINAL unpooled admin datasource, never the business pool.
        // The reader is auto-commit. A separate mysql-default-db witness locks only our existing
        // wp14_probe row. Its NEW physical ID must appear in this cache generation; time alone
        // cannot prove freshness when another reader may have renewed the global last_read.
        try (Connection witness = Objects.requireNonNull(admin.getDataSource()).getConnection();
             Connection observer = admin.getDataSource().getConnection()) {
            long witnessId = independentAdminId(witness), observerId = independentAdminId(observer);
            assertNotEquals(witnessId, observerId, "freshness witness and cache reader must be independent");
            assertNotEquals(subscriptionRead.get("mysqlConnectionId"), Long.valueOf(observerId), "independent oracle identity");
            assertNotEquals(subscriptionRead.get("mysqlConnectionId"), Long.valueOf(witnessId), "independent freshness witness identity");
            assertTrue(freshnessWitnessIds.add(witnessId), "TRANSACTION_SAMPLE_UNKNOWN: freshness witness ID was reused");
            diagnostic.put("observerConnectionId", observerId); diagnostic.put("witnessConnectionId", witnessId);
            diagnostic.put("serverUuid", serverUuid); diagnostic.put("mysqlVersion", serverVersion);
            witness.setAutoCommit(false);
            Throwable primary = null;
            try {
                try (var statement = witness.prepareStatement("SELECT original_id FROM `" + schema + "`.wp14_probe WHERE original_id=? FOR UPDATE")) {
                    statement.setQueryTimeout(3); statement.setString(1, original);
                    try (var row = statement.executeQuery()) { assertTrue(row.next()); assertEquals(original, row.getString(1)); }
                }
                long witnessReadyNanos = System.nanoTime();
                int attempts = 0;
                try (var statement = observer.prepareStatement("SELECT t.trx_mysql_thread_id,t.trx_state,t.trx_rows_locked,t.trx_rows_modified,p.DB "
                        + "FROM information_schema.innodb_trx t JOIN information_schema.processlist p ON p.ID=t.trx_mysql_thread_id "
                        + "WHERE p.DB=? OR t.trx_mysql_thread_id=? ORDER BY t.trx_mysql_thread_id")) {
                    statement.setQueryTimeout(3); statement.setString(1, schema); statement.setLong(2, witnessId);
                    while (true) {
                        long quietSince = Math.max(lastTrxCacheReadEndedNanos, witnessReadyNanos);
                        diagnostic.put("phase", "AWAIT_CACHE_IDLE");
                        diagnostic.put("remainingBudgetNanos", Math.max(0, deadline - System.nanoTime()));
                        lastTransactionSample = Map.copyOf(diagnostic);
                        awaitCacheIdle(quietSince, deadline);
                        long readStarted = System.nanoTime(); requireSampleBudget(readStarted, deadline);
                        List<Map<String, Object>> rows = new ArrayList<>();
                        try (var result = statement.executeQuery()) {
                            while (result.next()) rows.add(Map.of("mysqlConnectionId", result.getLong(1),
                                    "state", result.getString(2), "rowsLocked", result.getLong(3),
                                    "rowsModified", result.getLong(4), "database", result.getString(5)));
                        } finally {
                            // Even a stale/failed read renews the server's idle clock. Never poll at 20ms.
                            lastTrxCacheReadEndedNanos = System.nanoTime();
                        }
                        attempts++;
                        boolean currentWitnessSeen = rows.stream().anyMatch(row -> Long.valueOf(witnessId).equals(row.get("mysqlConnectionId")));
                        diagnostic.put("phase", "CACHE_READ_COMPLETED");
                        diagnostic.put("lastRead", Map.of("attempt", attempts, "quietNanos", readStarted - quietSince,
                                "minimumIdleNanosExclusive", TRX_CACHE_MIN_IDLE_NANOS,
                                "readNanos", lastTrxCacheReadEndedNanos - readStarted,
                                "currentWitnessSeen", currentWitnessSeen, "rows", List.copyOf(rows)));
                        lastTransactionSample = Map.copyOf(diagnostic);
                        requireSampleBudget(lastTrxCacheReadEndedNanos, deadline);
                        if (!currentWitnessSeen) continue;
                        List<Map<String, Object>> transactions = freshDomainTransactions(rows, schema, witnessId, observerId);
                        return new ParentSqlObservation(observerId, active, transactions, subscriptionRead,
                                Map.of("result", "CURRENT_PHYSICAL_WITNESS_IN_CACHE", "witnessConnectionId", witnessId,
                                        "witnessDatabase", "mysql", "readAttempts", attempts,
                                        "quietNanos", readStarted - quietSince, "minimumIdleNanosExclusive", TRX_CACHE_MIN_IDLE_NANOS,
                                        "witnessReadyToReadNanos", readStarted - witnessReadyNanos,
                                        "readNanos", lastTrxCacheReadEndedNanos - readStarted,
                                        "mysqlVersion", serverVersion,
                                        "source", "mysql-8.0.45/trx0i_s.cc:can_cache_be_updated+trx_i_s_cache_end_read"));
                    }
                }
            } catch (Exception | Error failure) { primary = failure; throw failure; }
            finally {
                try { witness.rollback(); }
                catch (Exception | Error cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
            }
        }
    }

    Map<String, Object> transactionSamplingDiagnostic() {
        return Map.of("stage", "transaction-cache-freshness", "sample", lastTransactionSample,
                "source", "mysql-8.0.45/trx0i_s.cc:can_cache_be_updated+trx_i_s_cache_end_read");
    }

    private long independentAdminId(Connection connection) throws Exception {
        assertTrue(connection.getAutoCommit(), "transaction oracle must use auto-commit before witness admission");
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (var row = statement.executeQuery("SELECT CONNECTION_ID(),DATABASE(),@@server_uuid,@@session.autocommit")) {
                assertTrue(row.next()); assertEquals("mysql", row.getString(2)); assertEquals(serverUuid, row.getString(3));
                assertEquals(1, row.getInt(4), "real oracle session must remain auto-commit"); return row.getLong(1);
            }
        }
    }

    static long cacheIdleRemainingNanos(long now, long quietSince) {
        assertTrue(now - quietSince >= 0, "TRANSACTION_SAMPLE_UNKNOWN: invalid monotonic sample order");
        return Math.max(0, TRX_CACHE_MIN_IDLE_NANOS + 1 - (now - quietSince));
    }

    static void requireSampleBudget(long now, long deadline) {
        assertTrue(deadline - now > 0, "TRANSACTION_SAMPLE_UNKNOWN: original bounded sampling deadline exhausted");
    }

    private static void awaitCacheIdle(long quietSince, long deadline) throws InterruptedException {
        while (true) {
            long now = System.nanoTime(); requireSampleBudget(now, deadline);
            long wait = cacheIdleRemainingNanos(now, quietSince);
            if (wait == 0) return;
            assertTrue(wait < deadline - now, "TRANSACTION_SAMPLE_UNKNOWN: no cache-idle window inside original deadline");
            TimeUnit.NANOSECONDS.sleep(wait);
        }
    }

    static List<Map<String, Object>> freshDomainTransactions(List<Map<String, Object>> rows, String schema,
                                                            long witnessId, long observerId) {
        assertNotEquals(witnessId, observerId, "freshness witness cannot be the reader");
        List<Map<String, Object>> witnesses = rows.stream()
                .filter(row -> Long.valueOf(witnessId).equals(row.get("mysqlConnectionId"))).toList();
        assertEquals(1, witnesses.size(), "TRANSACTION_SAMPLE_UNKNOWN: current physical witness absent/duplicated");
        Map<String, Object> witness = witnesses.getFirst();
        assertEquals("mysql", witness.get("database"), "freshness witness must not be a domain-pool transaction");
        assertEquals("RUNNING", witness.get("state"));
        assertEquals(Long.valueOf(0), witness.get("rowsModified"), "freshness witness must not modify rows");
        assertTrue(((Number) witness.get("rowsLocked")).longValue() > 0, "freshness witness must own its actual fixture row lock");
        List<Map<String, Object>> domain = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (Long.valueOf(witnessId).equals(row.get("mysqlConnectionId"))) continue;
            assertEquals(schema, row.get("database"), "unexpected transaction scope; never normalize unknown rows away");
            domain.add(row); // Includes calibration/notification/readonly/unknown-role transactions without exception.
        }
        return List.copyOf(domain);
    }

    Map<String, Object> assertTransactionOracleDetectsParentTransaction() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1); // Inside the unchanged 150-second case budget.
        long heldId;
        ParentSqlObservation held;
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            Throwable primary = null;
            try {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(3);
                    try (var row = statement.executeQuery("SELECT CONNECTION_ID()")) { assertTrue(row.next()); heldId = row.getLong(1); }
                }
                try (var statement = connection.prepareStatement("SELECT id FROM ums_member WHERE id=? FOR UPDATE")) {
                    statement.setQueryTimeout(3); statement.setLong(1, MEMBER);
                    try (var row = statement.executeQuery()) { assertTrue(row.next()); assertEquals(MEMBER, row.getLong(1)); }
                }
                held = observeParentTransactions(deadline);
                assertTrue(containsTransaction(held, heldId), "real parent transaction must be visible: " + held.evidence());
                assertNotEquals(heldId, held.observerConnectionId(), "calibration holder and observer must be different physical connections");
            } catch (Exception | Error failure) { primary = failure; throw failure; }
            finally {
                try { connection.rollback(); connection.setAutoCommit(true); }
                catch (Exception | Error cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
            }
        }
        ParentSqlObservation released = observeParentTransactions(deadline);
        requireSampleBudget(System.nanoTime(), deadline);
        assertEquals(0, released.openTransactions(), "rolled-back calibration must leave no domain transaction: " + released.evidence());
        long originalConnection = heldId;
        Object rowsModified = held.transactions().stream()
                .filter(row -> Long.valueOf(originalConnection).equals(row.get("mysqlConnectionId")))
                .findFirst().orElseThrow().get("rowsModified");
        assertEquals(Long.valueOf(0), rowsModified, "calibration must not modify business rows");
        requireSampleBudget(System.nanoTime(), deadline);
        return Map.of("stage", "transaction-oracle-calibration", "heldConnectionId", heldId,
                "held", held.evidence(), "released", released.evidence(), "observedHeldRowsModified", rowsModified);
    }

    private static boolean containsTransaction(ParentSqlObservation observation, long connectionId) {
        return observation.transactions().stream().anyMatch(row -> Long.valueOf(connectionId).equals(row.get("mysqlConnectionId")));
    }

    String notificationProgressDiagnostic(java.util.concurrent.Future<?> dispatch) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("providerPostEntries", subscriptionCalls.get());
        facts.put("poisonLatchRemaining", poisonEntered.getCount());
        facts.put("serverFailureTypes", subscriptionFailures.stream().map(t -> t.getClass().getSimpleName()).toList());
        facts.put("dispatchDone", dispatch.isDone());
        if (dispatch.isDone()) {
            try { dispatch.get(0, TimeUnit.MILLISECONDS); facts.put("dispatchOutcome", "RETURNED"); }
            catch (java.util.concurrent.ExecutionException failed) {
                facts.put("dispatchFailureType", failed.getCause().getClass().getSimpleName());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); facts.put("dispatchOutcome", "OBSERVER_INTERRUPTED");
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.CancellationException unavailable) {
                facts.put("dispatchOutcome", unavailable.getClass().getSimpleName());
            }
        }
        try {
            facts.put("originalPoisonReceiptStates", db.queryForList(
                    "SELECT delivery_state FROM official_broadcast_log WHERE broadcast_id=? AND channel='subscribe' ORDER BY id",
                    String.class, poisonId));
        } catch (RuntimeException unavailable) {
            facts.put("receiptReadFailureType", unavailable.getClass().getSimpleName());
        }
        // Only fixed fixture observations/types; never exception messages, SQL or provider bodies.
        return "real subscribe poison entry not observed: " + facts;
    }
    long broadcast(String channels) {
        OfficialBroadcast row = new OfficialBroadcast(); row.setTitle("controlled broadcast"); row.setAudience("player"); row.setChannels(channels);
        row.setCopyMode(1); row.setContentJson("{\"title\":\"controlled\",\"sub\":\"no external delivery\"}"); row.setStatus(1); row.setCreateTime(new Date());
        assertEquals(1, sql.getMapper(OfficialBroadcastMapper.class).insertOfficialBroadcast(row)); return row.getId();
    }

    static LettuceConnectionFactory requireSharedRedisFactory(LettuceConnectionFactory applicationRedis,
                                                              int port, int database, String password) {
        assertNotNull(applicationRedis);
        assertTrue(port > 0 && port < 65536); assertEquals(15, database, "Combined HTTP owner uses explicit DB15");
        assertTrue(applicationRedis.isRunning(), "Borrow only the already-running application Redis owner");
        assertFalse(applicationRedis.isClusterAware()); assertFalse(applicationRedis.isRedisSentinelAware());
        assertNull(applicationRedis.getSocketConfiguration());
        assertEquals("127.0.0.1", applicationRedis.getHostName());
        assertEquals(port, applicationRedis.getPort()); assertEquals(database, applicationRedis.getDatabase());
        String actualPassword = applicationRedis.getPassword();
        assertTrue(Objects.equals(password == null ? "" : password, actualPassword == null ? "" : actualPassword),
                "Borrowed Redis authentication must match the original fixture");
        String username = applicationRedis.getStandaloneConfiguration().getUsername();
        assertTrue(username == null || username.isEmpty(), "Original fixture uses the default Redis authentication identity");
        return applicationRedis;
    }

    private void installRedis(LettuceConnectionFactory applicationRedis) {
        int port = Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_PORT")); int database = Integer.parseInt(required("CHENGYIN_AI_REDIS_IT_DATABASE"));
        assertTrue(port > 0 && port < 65536 && database >= 0 && database <= 15);
        if (applicationRedis == null) {
            var configuration = new RedisStandaloneConfiguration("127.0.0.1", port); configuration.setDatabase(database);
            String secret = System.getenv("CHENGYIN_AI_REDIS_IT_PASSWORD"); if (secret != null && !secret.isEmpty()) configuration.setPassword(secret);
            redisFactory = new LettuceConnectionFactory(configuration); ownsRedisFactory = true;
            redisFactory.afterPropertiesSet();
        } else {
            redisFactory = applicationRedis; ownsRedisFactory = false;
            // Validated before any fixture resource was created; never reinitialize it.
        }
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
    PhotoCheckIntentView preparePhoto(PhotoCheckIntentService service, PhotoCheckIntentCommand command) {
        String[] phase = {"TX_BEGIN"};
        try {
            return tx.execute(status -> {
                phase[0] = "PHOTO_PREPARE";
                return service.prepare(command);
            });
        } catch (RuntimeException failure) {
            photoPrepareFailure = photoPrepareFailure(phase[0], failure);
            throw failure; // Preserve the original exception, cause, rollback and non-success outcome.
        }
    }
    /** Fixed diagnostic vocabulary only; never exception text, SQL, identity or configuration values. */
    static Map<String,Object> photoPrepareFailure(String phase, Throwable failure) {
        Set<String> reviewedTypes = Set.of("java.lang.IllegalStateException", "java.lang.IllegalArgumentException",
                "java.sql.SQLException", "java.sql.SQLTimeoutException", "java.sql.SQLTransientConnectionException",
                "java.sql.SQLNonTransientConnectionException", "java.sql.SQLIntegrityConstraintViolationException",
                "java.sql.SQLSyntaxErrorException", "java.sql.SQLTransactionRollbackException",
                "org.springframework.transaction.CannotCreateTransactionException",
                "org.springframework.jdbc.CannotGetJdbcConnectionException",
                "org.springframework.dao.DataAccessResourceFailureException",
                "org.springframework.dao.TransientDataAccessResourceException",
                "org.springframework.dao.CannotAcquireLockException", "org.springframework.dao.QueryTimeoutException",
                "org.mybatis.spring.MyBatisSystemException", "com.chengyinhub.common.exception.ServiceException");
        Set<String> reviewedStates = Set.of("08001", "08003", "08006", "08S01", "23000", "40001", "42000", "42S02", "HY000", "HYT00", "HYT01");
        Set<Integer> reviewedCodes = Set.of(0, 1040, 1045, 1049, 1054, 1062, 1146, 1205, 1213, 1317, 2003, 2006, 2013);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> types = new TreeSet<>();
        List<Map<String,Object>> sql = new ArrayList<>();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8 && seen.add(current); depth++, current = current.getCause()) {
            String type = current.getClass().getName();
            types.add(reviewedTypes.contains(type) ? type : "UNKNOWN_TYPE");
            if (current instanceof java.sql.SQLException database) {
                for (int chained = 0; database != null && chained < 4; chained++, database = database.getNextException()) {
                    String state = database.getSQLState(); int code = database.getErrorCode();
                    sql.add(Map.of("sqlState", state != null && reviewedStates.contains(state) ? state : "UNKNOWN",
                            "vendorCode", reviewedCodes.contains(code) ? Integer.toString(code) : "UNKNOWN"));
                }
            }
        }
        return Map.of("phase", Set.of("TX_BEGIN", "PHOTO_PREPARE").contains(phase) ? phase : "UNKNOWN",
                "exceptionTypes", List.copyOf(types), "sql", List.copyOf(sql));
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
        try { if (ownsRedisFactory && redisFactory != null) redisFactory.destroy(); } catch (Throwable failure) { failures.add(failure); }
        try { if (pool != null) { assertEquals(0, pool.getActiveCount(), "parent business connections must exit before pool close"); pool.close(); } }
        catch (Throwable failure) { failures.add(failure); if (pool != null) pool.close(); }
        try { if (created) { assertTrue(schema.matches("cy_wp14_[a-f0-9]{32}")); admin.execute("DROP DATABASE `" + schema + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema)); created = false; } }
        catch (Throwable failure) { failures.add(failure); }
        if (!failures.isEmpty()) { Exception result = new Exception("owned cross-domain cleanup failed"); failures.forEach(result::addSuppressed); throw result; }
    }
}
