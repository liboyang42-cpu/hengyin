package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CouponPublisDTO;
import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.UserSafeMessage;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Oracle MySQL 8.0.45 acceptance only, using the existing loopback scratch fixture.
 * Coupon/receipt/member-lock XML, coupon service, quota service and Spring transactions are real.
 * Current merchant authority, role permissions and Redis are fixture ports, not RBAC/Redis proof.
 * No server, live merchant request, remote database or H2 fallback is started by this class.
 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
@Timeout(45)
public class CouponCommandServiceMySqlIT {
    static final String MIGRATION_SHA256 = "2696b2b9111738e9fdca15312777ead0e0a9ea5da19ed1da07a510473c37d11c";
    static final String FIXTURE_SHA256 = "9945a408d21efec3fc8c4393049bf715636d987ef01d12f14b497c39bcfc1c75";
    static final String DEDUP_SHA256 = "42eeb52ce81d342d7538be70dd2a25596c52eb655a1cb8af04dd7265a6864ca3";
    static final String MEMBER_MAPPER_SHA256 = "c53e6db3f346582cc2eb3bf5132e72d47f31f2ed800048b094c589f3502b7bb3";
    static final String KEY = "11111111-1111-4111-8111-111111111111";
    static final long ACTOR = 7002L, MERCHANT = 11L, OWNER = 9001L;
    static final String[] SOURCE_PATHS = {
        "chengyinhub-admin/sql/migration_coupon_command_receipt.sql",
        "chengyinhub-admin/sql/migration_coupon_dedup.sql",
        "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCouponController.java",
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/it/support/IsolatedMySqlSchema.java",
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/MysqlIntegrationTestConfig.java",
        "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/CouponCommandServiceMySqlIT.java",
        "chengyinhub-admin/src/test/resources/fixtures/coupon-command-existing-schema.sql",
        "chengyinhub-framework/src/main/java/com/chengyinhub/framework/web/exception/GlobalExceptionHandler.java",
        "chengyinhub-system/src/main/java/com/chengyinhub/business/domain/CouponCommandReceipt.java",
        "chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CouponCommandReceiptMapper.java",
        "chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/SmsCouponMapper.java",
        "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CouponCommandService.java",
        "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/QuotaServiceImpl.java",
        "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/SmsCouponServiceImpl.java",
        "chengyinhub-system/src/main/resources/mapper/business/CouponCommandReceiptMapper.xml",
        "chengyinhub-system/src/main/resources/mapper/business/SmsCouponMapper.xml",
        "chengyinhub-system/src/main/resources/mapper/business/UmsMemberMapper.xml"
    };
    IsolatedMySqlSchema schema;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    CouponCommandReceiptMapper receipts;
    SmsCouponMapper coupons;
    UmsMemberMapper memberMapper;
    SmsCouponServiceImpl couponService;
    IQuotaService quota;
    IUmsMemberService members;
    IMerchantAccessService access;
    RedisCache redis;
    UserPermission permissions;
    CouponCommandService service;
    String proofSchema, proofCommit, proofRun, proofSource;
    boolean migrationApplied;
    final Set<Long> transactionConnections = ConcurrentHashMap.newKeySet();

    static Path root() {
        Path path = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if ("chengyinhub-admin".equals(path.getFileName().toString())) path = path.getParent();
        assertTrue(Files.isRegularFile(path.resolve("ci/sql_splitter.py")), "Run in the actual repository fixture checkout");
        return path;
    }
    static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    static String sourceFingerprint(Path root) throws Exception {
        StringBuilder rows = new StringBuilder();
        for (String path : SOURCE_PATHS) rows.append(path).append('\0').append(sha256(Files.readAllBytes(root.resolve(path)))).append('\n');
        return sha256(rows.toString().getBytes(StandardCharsets.UTF_8));
    }
    @BeforeEach void setup() throws Exception {
        // Validate invocation binding before opening any database connection.
        proofCommit = System.getProperty("coupon.command.test.commit", "");
        proofRun = System.getProperty("coupon.command.test.run", "");
        assertTrue(proofCommit.matches("[0-9a-f]{40}"), "Exact tested commit required");
        assertTrue(proofRun.matches("[0-9a-f]{32}"), "Fresh run nonce required");
        Path repository = root();
        proofSource = sourceFingerprint(repository);
        assertEquals(System.getProperty("coupon.command.test.sourceSha256"), proofSource, "Frozen source fingerprint required");
        assertEquals(MIGRATION_SHA256, sha256(Files.readAllBytes(repository.resolve(SOURCE_PATHS[0]))));
        assertEquals(DEDUP_SHA256, sha256(Files.readAllBytes(repository.resolve(SOURCE_PATHS[1]))));
        Path mapper = repository.resolve("chengyinhub-system/src/main/resources/mapper/business");
        assertEquals(MEMBER_MAPPER_SHA256, sha256(Files.readAllBytes(mapper.resolve("UmsMemberMapper.xml"))));
        byte[] fixture = Files.readAllBytes(repository.resolve("chengyinhub-admin/src/test/resources/fixtures/coupon-command-existing-schema.sql"));
        assertEquals(FIXTURE_SHA256, sha256(fixture));
        schema = new IsolatedMySqlSchema();
        source = schema.open("coupon_cmd");
        jdbc = new JdbcTemplate(source);
        proofSchema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertEquals("REPEATABLE-READ", jdbc.queryForObject("SELECT @@transaction_isolation", String.class));
        for (String sql : new String(fixture, StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "").split(";"))
            if (!sql.trim().isEmpty()) jdbc.execute(sql);
        schema.apply("coupon_command_receipt"); // Repository splitter, original bytes; never replace MySQL syntax.
        migrationApplied = true;
        jdbc.update("INSERT INTO ums_member(id) VALUES (9001),(9002)");
        manager = new DataSourceTransactionManager(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
        cfg.getTypeAliasRegistry().registerAlias("SmsCoupon", SmsCoupon.class);
        cfg.getTypeAliasRegistry().registerAlias("UmsMember", UmsMember.class);
        factory.setConfiguration(cfg);
        factory.setMapperLocations(new FileSystemResource(mapper.resolve("SmsCouponMapper.xml")),
                new FileSystemResource(mapper.resolve("CouponCommandReceiptMapper.xml")),
                new FileSystemResource(mapper.resolve("UmsMemberMapper.xml")));
        SqlSessionFactory sqlFactory = factory.getObject();
        SqlSessionTemplate session = new SqlSessionTemplate(sqlFactory);
        coupons = session.getMapper(SmsCouponMapper.class);
        receipts = session.getMapper(CouponCommandReceiptMapper.class);
        memberMapper = session.getMapper(UmsMemberMapper.class);
        members = mock(IUmsMemberService.class);
        when(members.lockMemberById(anyLong())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            transactionConnections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            return memberMapper.lockMemberById(call.getArgument(0));
        });
        when(members.selectUmsMemberById(anyLong())).thenReturn(new UmsMember());
        IRoleService roles = mock(IRoleService.class);
        permissions = new UserPermission(); permissions.setCanPublishCoupon(true); permissions.setMaxCoupons(100);
        when(roles.getPermission(any())).thenReturn(permissions);
        couponService = spy(new SmsCouponServiceImpl());
        ReflectionTestUtils.setField(couponService, "smsCouponMapper", coupons);
        QuotaServiceImpl quotaTarget = new QuotaServiceImpl();
        ReflectionTestUtils.setField(quotaTarget, "umsMemberService", members);
        ReflectionTestUtils.setField(quotaTarget, "roleService", roles);
        ReflectionTestUtils.setField(quotaTarget, "smsCouponService", couponService);
        ProxyFactory proxy = new ProxyFactory(quotaTarget);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        quota = (IQuotaService) proxy.getProxy();
        access = mock(IMerchantAccessService.class);
        allow(ACTOR, MERCHANT, OWNER);
        redis = mock(RedisCache.class);
        when(redis.setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS))).thenReturn(true);
        service = freshService();
    }
    @AfterEach void cleanup(TestInfo test) throws Exception {
        if (schema != null) schema.close();
        if (proofSchema != null && migrationApplied) System.out.println("COUPON_COMMAND_MYSQL_EVIDENCE METHOD="
                + test.getTestMethod().get().getName() + " VERSION=8.0.45 SCHEMA=" + proofSchema
                + " MIGRATION_SHA256=" + MIGRATION_SHA256 + " FIXTURE_SHA256=" + FIXTURE_SHA256
                + " SOURCE_SHA256=" + proofSource + " COMMIT=" + proofCommit + " RUN=" + proofRun + " SCRATCH_DROPPED=true");
    }
    CouponCommandService freshService() {
        CouponCommandService result = new CouponCommandService();
        ReflectionTestUtils.setField(result, "enabled", true);
        ReflectionTestUtils.setField(result, "merchantAccessService", access);
        ReflectionTestUtils.setField(result, "umsMemberService", members);
        ReflectionTestUtils.setField(result, "quotaService", quota);
        ReflectionTestUtils.setField(result, "smsCouponService", couponService);
        ReflectionTestUtils.setField(result, "smsCouponMapper", coupons);
        ReflectionTestUtils.setField(result, "receiptMapper", receipts);
        ReflectionTestUtils.setField(result, "redisCache", redis);
        ReflectionTestUtils.setField(result, "transactionManager", manager);
        return result;
    }
    void allow(long actor, long merchant, long owner) {
        when(access.require(actor, MerchantPermission.COUPON_MANAGE)).thenReturn(
                new MerchantAccessContext(actor, merchant, owner, MerchantRoleCode.MERCHANT_MARKETING));
    }
    CouponPublisDTO request() {
        CouponPublisDTO value = new CouponPublisDTO(); value.setScope("MERCHANT"); value.setName("测试券");
        value.setDescription(null); value.setCouponType(0); value.setPublishCount(10L);
        value.setStartTime(new Date(1791129600123L)); value.setEndTime(new Date(4070908799999L));
        return value;
    }
    AjaxResult publish() { return service.publish(ACTOR, KEY, MERCHANT, request()); }
    AjaxResult stop(String key, long coupon) { return service.stop(ACTOR, key, MERCHANT, coupon, "MERCHANT"); }
    AjaxResult read(String key) { return service.read(ACTOR, key, MERCHANT, "MERCHANT"); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @SuppressWarnings("unchecked") Map<String,Object> receipt(AjaxResult result) {
        return (Map<String,Object>) ((Map<?,?>) result.get("data")).get("commandReceipt");
    }
    long couponId(AjaxResult result) { return ((Number) receipt(result).get("couponId")).longValue(); }
    void assertEmpty() { assertEquals(0, count("sms_coupon")); assertEquals(0, count("coupon_command_receipt")); }
    void failReceipt(boolean after) {
        CouponCommandReceiptMapper broken = mock(CouponCommandReceiptMapper.class);
        when(broken.selectCurrent(anyLong(), anyLong(), anyString())).thenAnswer(call -> receipts.selectCurrent(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(broken.insertReceipt(any())).thenAnswer(call -> {
            if (after) receipts.insertReceipt(call.getArgument(0));
            throw new IllegalStateException("java.lang.IllegalStateException: synthetic response failure");
        });
        ReflectionTestUtils.setField(service, "receiptMapper", broken);
    }
    List<AjaxResult> race(Callable<AjaxResult> first, Callable<AjaxResult> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<AjaxResult>> pending = new ArrayList<>();
            for (Callable<AjaxResult> work : Arrays.asList(first, second)) pending.add(pool.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return work.call();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            List<AjaxResult> values = new ArrayList<>();
            for (Future<AjaxResult> future : pending) values.add(future.get(20, TimeUnit.SECONDS));
            assertEquals(2, transactionConnections.size(), "Concurrent requests must use separate physical transactions");
            return values;
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(12, TimeUnit.SECONDS)); }
    }

    @Test void originalReceiptMigrationHasNativeInnoDbUniqueAndPrecision() {
        assertEquals("InnoDB", jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coupon_command_receipt'", String.class));
        assertEquals("ascii_bin", jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coupon_command_receipt' AND column_name='request_id'", String.class));
        assertEquals(3, jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coupon_command_receipt' AND column_name='completed_at'", Integer.class));
        assertEquals("actor_member_id,scope_type,merchant_id,request_id", jdbc.queryForObject("SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='coupon_command_receipt' AND index_name='uk_coupon_command' AND non_unique=0", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coupon_command_receipt' AND column_name IN ('actor_member_id','scope_type','merchant_id','request_id') AND is_nullable='YES'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name='coupon_command_receipt'", Integer.class));
    }
    @Test void concurrentIdenticalCommandsCommitOneCouponAndOneReceipt() throws Exception {
        List<AjaxResult> values = race(this::publish, this::publish);
        assertEquals(200, values.get(0).get("code")); assertEquals(values.get(0), values.get(1));
        assertEquals(1, count("sms_coupon")); assertEquals(1, count("coupon_command_receipt"));
        assertEquals(couponId(values.get(0)), couponId(values.get(1)));
        verify(redis, times(1)).setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS));
    }
    @Test void actualMemberForUpdateBlocksCommandUntilOwnerTransactionCommits() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); attempting.countDown();
            return memberMapper.lockMemberById(OWNER);
        }).when(members).lockMemberById(OWNER);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                assertEquals(OWNER, memberMapper.lockMemberById(OWNER)); held.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new IllegalStateException(error); }
                return null;
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS)); Future<AjaxResult> waiting = pool.submit(this::publish);
            assertTrue(attempting.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(250, TimeUnit.MILLISECONDS));
            assertEmpty(); release.countDown(); holder.get(15, TimeUnit.SECONDS);
            assertEquals(200, waiting.get(15, TimeUnit.SECONDS).get("code"));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(12, TimeUnit.SECONDS)); }
    }
    @Test void concurrentDifferentPayloadsCannotOverwriteTheWinningReceipt() throws Exception {
        CouponPublisDTO changed = request(); changed.setDescription("");
        List<AjaxResult> values = race(this::publish, () -> service.publish(ACTOR, KEY, MERCHANT, changed));
        assertEquals(new HashSet<>(Arrays.asList(200, 409)), new HashSet<>(Arrays.asList(values.get(0).get("code"), values.get(1).get("code"))));
        assertEquals(1, count("sms_coupon")); assertEquals(1, count("coupon_command_receipt"));
        AjaxResult winner = values.get(0).get("code").equals(200) ? values.get(0) : values.get(1);
        assertEquals(winner, read(KEY));
        assertEquals(409, stop(KEY, couponId(winner)).get("code"));
    }
    @Test void distinctKeysStillSerializeThroughActualQuotaGuard() throws Exception {
        permissions.setMaxCoupons(1);
        List<AjaxResult> values = race(this::publish, () -> service.publish(ACTOR, UUID.randomUUID().toString(), MERCHANT, request()));
        assertEquals(new HashSet<>(Arrays.asList(200, 500)), new HashSet<>(Arrays.asList(values.get(0).get("code"), values.get(1).get("code"))));
        assertEquals(1, count("sms_coupon")); assertEquals(2, count("coupon_command_receipt"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM coupon_command_receipt WHERE outcome='REJECTED' AND reason_code='QUOTA_REJECTED'", Integer.class));
        verify(redis, times(1)).setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS));
    }
    @Test void originalMinuteMigrationStillRejectsDifferentKeysWithoutFalseReceipt() throws Exception {
        // Deliberately execute the actual legacy DDL; unsupported DDL is a failing acceptance result.
        schema.apply("coupon_dedup");
        AjaxResult first = publish();
        Date fixedCreateTime = jdbc.queryForObject("SELECT create_time FROM sms_coupon", Date.class);
        doAnswer(call -> {
            SmsCoupon value = call.getArgument(0); value.setCreateTime(fixedCreateTime);
            return coupons.insertSmsCoupon(value);
        }).when(couponService).insertSmsCoupon(any());
        String second = UUID.randomUUID().toString();
        assertThrows(DuplicateKeyException.class, () -> service.publish(ACTOR, second, MERCHANT, request()));
        assertEquals(1, count("sms_coupon")); assertEquals(1, count("coupon_command_receipt"));
        assertEquals(404, read(second).get("code")); assertEquals(first, publish());
    }
    @Test void replaySurvivesQuotaFullRateLimitStopAndCouponDeletion() {
        AjaxResult first = publish(); long id = couponId(first);
        assertEquals(200, stop(UUID.randomUUID().toString(), id).get("code"));
        permissions.setMaxCoupons(0);
        when(redis.setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS))).thenReturn(false);
        jdbc.update("UPDATE sms_coupon SET create_time=DATE_SUB(create_time, INTERVAL 2 MINUTE) WHERE id=?", id);
        assertEquals(first, publish());
        jdbc.update("DELETE FROM sms_coupon WHERE id=?", id);
        assertEquals(first, publish()); assertEquals(first, read(KEY));
        assertEquals(0, count("sms_coupon")); assertEquals(2, count("coupon_command_receipt"));
        verify(redis, times(1)).setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS));
    }
    @Test void receiptUniqueKeyIncludesActorMerchantAndExcludesOperation() {
        publish();
        CouponCommandReceipt original = receipts.selectCurrent(ACTOR, MERCHANT, KEY);
        original.setId(null); original.setOperation("STOP");
        assertThrows(DuplicateKeyException.class, () -> receipts.insertReceipt(original));
        allow(7003L, MERCHANT, OWNER);
        assertEquals(404, service.read(7003L, KEY, MERCHANT, "MERCHANT").get("code"));
        assertEquals(200, service.publish(7003L, KEY, MERCHANT, request()).get("code"));
        allow(ACTOR, 12L, 9002L);
        assertEquals(404, service.read(ACTOR, KEY, 12L, "MERCHANT").get("code"));
        assertEquals(200, service.publish(ACTOR, KEY, 12L, request()).get("code"));
        assertEquals(3, count("sms_coupon")); assertEquals(3, count("coupon_command_receipt"));
    }
    @Test void changedOwnerAndRevokedActorCannotRecoverForeignBinding() {
        publish(); allow(ACTOR, MERCHANT, 9002L);
        assertEquals(409, publish().get("code")); assertEquals(404, read(KEY).get("code"));
        when(access.require(ACTOR, MerchantPermission.COUPON_MANAGE)).thenThrow(new ServiceException("revoked", 403));
        assertThrows(ServiceException.class, this::publish); assertThrows(ServiceException.class, () -> read(KEY));
        assertEquals(1, count("sms_coupon")); assertEquals(1, count("coupon_command_receipt"));
    }
    @Test void receiptInsertFailureRollsBackCouponAndAllowsSameKeyRetry() {
        failReceipt(false); assertThrows(IllegalStateException.class, this::publish); assertEmpty();
        service = freshService(); assertEquals(404, read(KEY).get("code"));
        assertEquals(200, publish().get("code")); assertEquals(1, count("sms_coupon")); assertEquals(1, count("coupon_command_receipt"));
    }
    @Test void exceptionAfterReceiptInsertRollsBackBothDatabaseWrites() {
        failReceipt(true); assertThrows(IllegalStateException.class, this::publish); assertEmpty();
    }
    @Test void exceptionAfterCouponInsertRollsBackBothDatabaseWrites() {
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("after coupon insert"); }).when(couponService).insertSmsCoupon(any());
        assertThrows(IllegalStateException.class, this::publish); assertEmpty();
    }
    @Test void missingGeneratedKeyCannotLeaveACommittedCoupon() {
        doAnswer(call -> { int rows = (Integer) call.callRealMethod(); ((SmsCoupon) call.getArgument(0)).setId(null); return rows; }).when(couponService).insertSmsCoupon(any());
        assertThrows(IllegalStateException.class, this::publish); assertEmpty();
    }
    @Test void stopRollbackRestoresStatusAndNeverChangesClaimedHistory() {
        long id = couponId(publish());
        jdbc.update("INSERT INTO sms_coupon_history VALUES (1,?,8001,0,'fixture-claimed')", id);
        List<Map<String,Object>> history = jdbc.queryForList("SELECT * FROM sms_coupon_history");
        String stopKey = UUID.randomUUID().toString(); failReceipt(true);
        assertThrows(IllegalStateException.class, () -> stop(stopKey, id));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM sms_coupon WHERE id=?", Integer.class, id));
        assertEquals(history, jdbc.queryForList("SELECT * FROM sms_coupon_history"));
        assertEquals(1, count("coupon_command_receipt"));
        service = freshService(); assertEquals(404, read(stopKey).get("code"));
    }
    @Test void concurrentIdenticalStopsReplaySuccessAndPreserveClaimedHistory() throws Exception {
        long id = couponId(publish()); jdbc.update("INSERT INTO sms_coupon_history VALUES (1,?,8001,0,'fixture-claimed')", id);
        List<Map<String,Object>> history = jdbc.queryForList("SELECT * FROM sms_coupon_history");
        String stopKey = UUID.randomUUID().toString(); transactionConnections.clear();
        List<AjaxResult> results = race(() -> stop(stopKey, id), () -> stop(stopKey, id));
        assertEquals(200, results.get(0).get("code")); assertEquals(results.get(0), results.get(1));
        assertEquals(4, jdbc.queryForObject("SELECT status FROM sms_coupon WHERE id=?", Integer.class, id));
        assertEquals(history, jdbc.queryForList("SELECT * FROM sms_coupon_history"));
        assertEquals("COUPON_ALREADY_STOPPED", receipt(stop(UUID.randomUUID().toString(), id)).get("reasonCode"));
        assertEquals(3, count("coupon_command_receipt"));
    }
    @Test void merchantStopSqlIsOwnerScopeMerchantAndStatusFenced() {
        long id = couponId(publish());
        assertEquals(0, coupons.stopIssuingMerchantCommand(id, 9002L, MERCHANT));
        assertEquals(0, coupons.stopIssuingMerchantCommand(id, OWNER, 12L));
        jdbc.update("UPDATE sms_coupon SET scope_type=2 WHERE id=?", id);
        assertEquals(0, coupons.stopIssuingMerchantCommand(id, OWNER, MERCHANT));
        jdbc.update("UPDATE sms_coupon SET scope_type=1,del_flag=1 WHERE id=?", id);
        assertEquals(0, coupons.stopIssuingMerchantCommand(id, OWNER, MERCHANT));
        jdbc.update("UPDATE sms_coupon SET del_flag=0 WHERE id=?", id);
        for (int status : new int[] {2,3,4}) {
            jdbc.update("UPDATE sms_coupon SET status=? WHERE id=?", status, id);
            assertEquals(0, coupons.stopIssuingMerchantCommand(id, OWNER, MERCHANT));
            assertEquals(500, stop(UUID.randomUUID().toString(), id).get("code"));
            assertEquals(status, jdbc.queryForObject("SELECT status FROM sms_coupon WHERE id=?", Integer.class, id));
        }
    }
    @Test void commitThenResponseFailureReturnsGenericEnvelopeButReadbackRecovers() throws Exception {
        ResponseFailureHarness harness = new ResponseFailureHarness(this::publish, true);
        String envelope = failureEnvelope(harness);
        assertEquals(500, new ObjectMapper().readTree(envelope).get("code").asInt());
        assertNotNull(harness.committed); assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        service = freshService();
        assertEquals(harness.committed, read(KEY)); assertEquals(harness.committed, publish());
        assertEquals(1, count("sms_coupon")); assertEquals(1, count("coupon_command_receipt"));
    }
    @Test void rollbackThenResponseFailureHasSameGenericEnvelopeAndNoReceipt() throws Exception {
        failReceipt(true); ResponseFailureHarness harness = new ResponseFailureHarness(this::publish, false);
        String envelope = failureEnvelope(harness);
        assertEquals(500, new ObjectMapper().readTree(envelope).get("code").asInt());
        assertNull(harness.committed); assertEmpty();
        service = freshService(); assertEquals(404, read(KEY).get("code"));
    }
    @Test void rollbackCanConsumeExistingRedisMinuteGuardWithoutFabricatedSuccess() {
        AtomicBoolean acquired = new AtomicBoolean();
        when(redis.setIfAbsent(anyString(), eq("1"), eq(60), eq(TimeUnit.SECONDS))).thenAnswer(call -> acquired.compareAndSet(false, true));
        failReceipt(true); assertThrows(IllegalStateException.class, this::publish); assertEmpty();
        service = freshService(); AjaxResult retry = publish();
        assertEquals("REJECTED", receipt(retry).get("outcome")); assertEquals("PUBLISH_RATE_LIMITED", receipt(retry).get("reasonCode"));
        assertEquals(0, count("sms_coupon")); assertEquals(1, count("coupon_command_receipt"));
        verify(redis, never()).deleteObject(anyString());
    }
    @Test void disabledOrMissingReceiptTableNeverFallsBackToMutation() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals(503, publish().get("code")); assertEmpty(); verifyNoInteractions(redis);
        ReflectionTestUtils.setField(service, "enabled", true);
        jdbc.execute("DROP TABLE coupon_command_receipt");
        assertThrows(RuntimeException.class, this::publish); assertEquals(0, count("sms_coupon")); verifyNoInteractions(redis);
    }
    String failureEnvelope(ResponseFailureHarness harness) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(harness).setControllerAdvice(new GlobalExceptionHandler()).build();
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(post("/fixture/coupon-response-failure")).andReturn().getResponse();
        assertEquals(200, response.getStatus(), "Existing failure advice uses an HTTP-200 AjaxResult error envelope");
        String body = new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(UserSafeMessage.GENERIC, new ObjectMapper().readTree(body).get("msg").asText());
        assertFalse(new ObjectMapper().readTree(body).has("data"));
        return body;
    }
    @RestController
    public static class ResponseFailureHarness {
        private final Supplier<AjaxResult> action;
        private final boolean throwAfterCommit;
        AjaxResult committed;
        ResponseFailureHarness(Supplier<AjaxResult> action, boolean throwAfterCommit) { this.action = action; this.throwAfterCommit = throwAfterCommit; }
        @PostMapping("/fixture/coupon-response-failure") public AjaxResult invoke() {
            committed = action.get();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Service must complete its transaction before response construction");
            if (throwAfterCommit) throw new IllegalStateException("java.lang.IllegalStateException: synthetic response failure");
            return committed;
        }
    }
}
