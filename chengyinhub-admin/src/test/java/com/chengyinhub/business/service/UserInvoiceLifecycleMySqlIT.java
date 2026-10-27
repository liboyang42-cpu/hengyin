package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UserInvoiceTitle;
import com.chengyinhub.business.it.ItSchema;
import com.chengyinhub.business.mapper.UserInvoiceMapper;
import com.chengyinhub.business.mapper.UserInvoiceProcessingMapper;
import com.chengyinhub.business.service.impl.UserInvoiceProcessingService;
import com.chengyinhub.business.service.impl.UserInvoiceRefundReconciliationJob;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.impl.UserInvoiceServiceImpl;
import com.chengyinhub.business.service.invoice.InvoiceIssuer;
import com.chengyinhub.business.service.invoice.ManualInvoiceIssuer;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real production Mapper, encryption and Spring transactions. No external invoice issuance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserInvoiceLifecycleMySqlIT {
    private MysqlIntegrationTestConfig mysql;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private UserInvoiceMapper mapper;
    private PiiCryptoService crypto;
    private PiiCryptoService previousCrypto;
    private IUserInvoiceService service;
    private UserInvoiceProcessingService desk;
    private UserInvoiceProcessingMapper processingMapper;
    private UserInvoiceRefundReconciliationJob reconciliation;
    private IRefundService refunds;
    private boolean created;
    private long titleId;
    private static final long OWNER = 71L;
    private static final long ORDER = 9007199254740993L;

    @BeforeAll
    void connectOnlyToExplicitlyEnabledTestDatabase() throws Exception {
        // A specifically selected IT must fail, not silently skip, without its approved test environment.
        assertEquals("1", System.getenv("CHENGYIN_FUNDS_MYSQL_IT"), "approved MySQL IT environment required");
        mysql = MysqlIntegrationTestConfig.fromEnvironment("user_invoice_it");
        try (Connection c = source(mysql.adminUrl()).getConnection(); Statement s = c.createStatement()) {
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(c.getMetaData().getDatabaseProductVersion()));
            s.execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
            created = true;
        }
        dataSource = source(mysql.databaseUrl()); jdbc = new JdbcTemplate(dataSource);
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        previousCrypto = PiiCryptoService.getInstance();
        crypto = new PiiCryptoService(Base64.getEncoder().encodeToString(key), ""); crypto.register();
        Configuration config = new Configuration(new Environment("user-invoice-it", new SpringManagedTransactionFactory(), dataSource));
        config.addMapper(UserInvoiceMapper.class);
        String resource = "mapper/business/UserInvoiceMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        config.addMapper(RefundApplicationMapper.class);
        String refundResource = "mapper/business/RefundApplicationMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(refundResource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, config, refundResource, config.getSqlFragments()).parse();
        }
        config.addMapper(UserInvoiceProcessingMapper.class);
        String processingResource="mapper/business/UserInvoiceProcessingMapper.xml";
        try (InputStream input=getClass().getClassLoader().getResourceAsStream(processingResource)) {
            assertNotNull(input); new XMLMapperBuilder(input,config,processingResource,config.getSqlFragments()).parse();
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        mapper = session.getMapper(UserInvoiceMapper.class);
        RefundServiceImpl refundService = new RefundServiceImpl();
        ReflectionTestUtils.setField(refundService, "refundApplicationMapper", session.getMapper(RefundApplicationMapper.class));
        refunds = refundService;
        service = transactional(new ManualInvoiceIssuer());
        processingMapper=session.getMapper(UserInvoiceProcessingMapper.class);
        ProxyFactory deskProxy=new ProxyFactory(new UserInvoiceProcessingService(mapper,processingMapper,refunds,crypto));
        deskProxy.setProxyTargetClass(true);
        deskProxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),new AnnotationTransactionAttributeSource()));
        desk=(UserInvoiceProcessingService)deskProxy.getProxy();
        reconciliation=new UserInvoiceRefundReconciliationJob(processingMapper,mapper,desk);
    }

    private DriverManagerDataSource source(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver"); ds.setUrl(url);
        ds.setUsername(mysql.user()); ds.setPassword(mysql.password()); return ds;
    }

    private IUserInvoiceService transactional(InvoiceIssuer issuer) {
        ProxyFactory proxy = new ProxyFactory(new UserInvoiceServiceImpl(mapper, crypto, issuer, refunds));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (IUserInvoiceService) proxy.getProxy();
    }

    @BeforeEach
    void fixtures() throws Exception {
        for (String table : List.of("user_invoice_issued_reference", "user_invoice_processing_audit", "user_invoice_processing", "user_invoice_scope", "user_invoice_issuer_owner", "user_invoice_issuer", "sys_user", "user_invoice_title_receipt", "user_invoice_application_order", "user_invoice_application", "user_invoice_title", "refund_application", "cms_registration")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        Path migration = Path.of("chengyinhub-admin/sql/migration_20261022_f7_user_invoice.sql");
        if (!Files.exists(migration)) migration = Path.of("..").resolve(migration);
        try (Connection c = dataSource.getConnection()) {
            ItSchema.applyMigrations(c, migration.toString(), migration.resolveSibling("migration_20261026_user_invoice_title_receipt.sql").toString(), migration.resolveSibling("migration_20261026_user_invoice_processing.sql").toString());
        }
        jdbc.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, member_id BIGINT, payment_status INT, registration_status INT, "
                + "wechat_payment_amount DECIMAL(10,2), activity_title VARCHAR(100), ticket_name VARCHAR(100), payment_time DATETIME, "
                + "parent_order_no VARCHAR(64), owner_type BIGINT, owner_id BIGINT, payable_amount DECIMAL(10,2), KEY idx_parent(parent_order_no)) ENGINE=InnoDB");
        Path base = Path.of("sql/base_schema_20260818.sql");
        if (!Files.exists(base)) base = Path.of("..").resolve(base);
        var refundDdl = java.util.regex.Pattern.compile("(?s)CREATE TABLE `refund_application` \\(.*?\\) ENGINE=.*?;").matcher(Files.readString(base));
        assertTrue(refundDdl.find()); jdbc.execute(refundDdl.group());
        jdbc.execute("ALTER TABLE refund_application ADD COLUMN platform_takeover_at DATETIME NULL");
        for (long id : List.of(ORDER, ORDER + 1, ORDER + 2)) {
            jdbc.update("INSERT INTO cms_registration(id,member_id,payment_status,registration_status,wechat_payment_amount,activity_title,ticket_name,payment_time,owner_type,owner_id,payable_amount) VALUES(?,?,2,2,12.34,'Invoice IT','Ticket',NOW(),1,101,12.34)", id, OWNER);
        }
        jdbc.execute("CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY,status CHAR(1),del_flag CHAR(1)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO sys_user VALUES(901,'0','0'),(902,'0','0'),(903,'0','0')");
        jdbc.update("INSERT INTO user_invoice_issuer VALUES(301,'Isolated invoice fixture',902,1)");
        jdbc.update("INSERT INTO user_invoice_issuer_owner VALUES(1,101,301)");
        jdbc.update("INSERT INTO user_invoice_scope VALUES(301,901,1,0,1),(301,902,0,1,1)");
        UserInvoiceTitle title = new UserInvoiceTitle(); title.setTitleType("COMPANY");
        title.setTitleName("Invoice integration fixture"); title.setTaxNo("913100001234567890"); title.setIsDefault(1);
        titleId = Long.parseLong(String.valueOf(service.saveTitle(OWNER, title).get("id")));
    }

    @AfterAll
    void cleanupOnlyOwnedDatabase() throws Exception {
        if (crypto != null) ReflectionTestUtils.setField(PiiCryptoService.class, "instance", previousCrypto);
        if (created) try (Connection c = source(mysql.adminUrl()).getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE " + mysql.databaseName());
        }
    }

    private long id(Map<String, Object> result) { return Long.parseLong(String.valueOf(result.get("id"))); }
    private int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private String failure(Runnable action) {
        try { action.run(); return "OK"; }
        catch (UserInvoiceException e) { return e.getErrorCode(); }
    }
    private List<String> race(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<String>> results = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return failure(action);
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            return List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void overlappingMultiOrderRequestsHaveOneAtomicWinner() throws Exception {
        List<String> results = race(
                () -> service.apply(OWNER, "overlap-a", titleId, List.of(ORDER, ORDER + 1)),
                () -> service.apply(OWNER, "overlap-b", titleId, List.of(ORDER + 1, ORDER + 2)));
        assertEquals(1, results.stream().filter("OK"::equals).count());
        assertTrue(results.contains(UserInvoiceException.ORDER_ALREADY_INVOICED));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_application"));
        assertEquals(2, count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_application_order o LEFT JOIN user_invoice_application a ON a.id=o.application_id WHERE a.id IS NULL"));
    }

    @Test
    void sameRequestReplaysOneApplicationAndKeepsLongAndMask() throws Exception {
        List<String> results = race(
                () -> service.apply(OWNER, "same-request", titleId, List.of(ORDER)),
                () -> service.apply(OWNER, "same-request", titleId, List.of(ORDER)));
        assertTrue(results.contains("OK"));
        assertTrue(results.stream().allMatch(r -> List.of("OK", UserInvoiceException.REQUEST_IN_PROGRESS, UserInvoiceException.ORDER_ALREADY_INVOICED).contains(r)));
        Map<String, Object> replay = service.apply(OWNER, "same-request", titleId, List.of(ORDER));
        assertEquals(true, replay.get("replayed"));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_application"));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        assertEquals(Long.toString(ORDER), service.applicationDetail(OWNER, id(replay)).get("orders") instanceof List<?> rows
                ? ((Map<?, ?>) rows.get(0)).get("orderId") : "missing");
        assertEquals("91******7890", replay.get("taxNoMasked"));
        String encrypted = jdbc.queryForObject("SELECT tax_no FROM user_invoice_application", String.class);
        assertNotNull(encrypted); assertTrue(encrypted.startsWith("v1:")); assertFalse(encrypted.contains("913100001234567890"));
        assertEquals(UserInvoiceException.REQUEST_REUSED, failure(() -> service.apply(OWNER, "same-request", titleId, List.of(ORDER + 1))));
    }

    @Test
    void cancelAndRejectRaceReleasesOnceAndAnotherApplicationCanOccupy() throws Exception {
        long application = id(service.apply(OWNER, "cancel-reject", titleId, List.of(ORDER)));
        List<String> results = race(() -> service.cancel(OWNER, application), () -> {
            Map<String,Object> r=desk.command(901L,application,command("reject-race",0,"REJECT",null,null,"evidence:reject",null));
            if ("DECLINED".equals(r.get("status"))) throw new UserInvoiceException(UserInvoiceException.STATE_CONFLICT,"race lost");
        });
        assertEquals(1, results.stream().filter("OK"::equals).count());
        assertTrue(results.contains(UserInvoiceException.STATE_CONFLICT));
        assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        assertEquals(0, mapper.releaseOrderLines(application));
        service.apply(OWNER, "after-release", titleId, List.of(ORDER));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key IS NULL"));
    }

    @Test
    void internalIssuedStateKeepsOccupancyAndCrossOwnerCannotReadOrCancel() {
        long application = id(service.apply(OWNER, "internal-issued", titleId, List.of(ORDER)));
        assertEquals(UserInvoiceException.NOT_FOUND, failure(() -> service.applicationDetail(72L, application)));
        assertEquals(UserInvoiceException.NOT_FOUND, failure(() -> service.cancel(72L, application)));
        assertEquals(UserInvoiceException.TITLE_NOT_FOUND, failure(() -> service.apply(72L, "foreign-title", titleId, List.of(ORDER))));
        UserInvoiceTitle otherTitle = new UserInvoiceTitle(); otherTitle.setTitleType("PERSONAL");
        otherTitle.setTitleName("Other owner fixture"); otherTitle.setIsDefault(0);
        long otherTitleId = id(service.saveTitle(72L, otherTitle));
        assertEquals(UserInvoiceException.ORDER_NOT_ELIGIBLE, failure(() -> service.apply(72L, "foreign-order", otherTitleId, List.of(ORDER))));
        desk.command(901L,application,command("claim",0,"CLAIM",null,null,null,null));
        desk.command(901L,application,command("start",1,"START",null,null,null,null));
        desk.command(901L,application,command("issued",2,"RECORD_ISSUED",application+"_start","IT-INTERNAL-NOT-A-REAL-INVOICE","evidence:issued",null));
        assertEquals("ISSUED", service.applicationDetail(OWNER, application).get("status"));
        assertEquals(UserInvoiceException.STATE_CONFLICT, failure(() -> service.cancel(OWNER, application)));
        assertEquals(UserInvoiceException.STATE_CONFLICT, failure(() -> service.markRejected(application, "too late")));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
    }

    /** R625 major (a):W8 前的申请没有处理行;回填迁移只按唯一主体映射补行,映射缺失不推断,重跑幂等。 */
    @Test
    void preW8ApplicationsGetProcessingRowsFromIdempotentBackfill() throws Exception {
        long mapped = id(service.apply(OWNER, "pre-w8-mapped", titleId, List.of(ORDER)));
        long unmapped = id(service.apply(OWNER, "pre-w8-unmapped", titleId, List.of(ORDER + 1)));
        jdbc.update("UPDATE cms_registration SET owner_id=999 WHERE id=?", ORDER + 1);
        jdbc.update("DELETE FROM user_invoice_processing");
        jdbc.update("UPDATE user_invoice_application SET status='ISSUED',invoice_no='PRE-W8-NOT-REAL' WHERE id=?", mapped);
        Path backfill = Path.of("chengyinhub-admin/sql/migration_20261027_user_invoice_processing_backfill.sql");
        if (!Files.exists(backfill)) backfill = Path.of("..").resolve(backfill);
        for (int run = 0; run < 2; run++) {
            try (Connection c = dataSource.getConnection()) { ItSchema.applyMigrations(c, backfill.toString()); }
        }
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_processing"));
        assertEquals(301, count("SELECT issuer_id FROM user_invoice_processing WHERE application_id=" + mapped));
        assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_processing WHERE application_id=" + unmapped));
        assertEquals("ISSUED", desk.detail(901L, mapped).get("status"));
        assertEquals("PRE-W8-NOT-REAL", service.applicationDetail(OWNER, mapped).get("invoiceNo"));
    }

    @Test
    void originalRequestBeyondRecentTwoHundredIsStillOwnedAndRecoverable() {
        long original = id(service.apply(OWNER, "old-original", titleId, List.of(ORDER)));
        for (int i = 0; i < 201; i++) {
            jdbc.update("INSERT INTO user_invoice_application(application_no,member_id,request_id,title_id,title_type,title_name,tax_no,total_amount,order_count,status,provider,create_time) "
                    + "SELECT ?,member_id,?,title_id,title_type,title_name,tax_no,total_amount,order_count,status,provider,create_time FROM user_invoice_application WHERE id=?",
                    "IT-FILL-" + i, "fill-" + i, original);
        }
        List<Map<String, Object>> recent = service.listApplications(OWNER);
        assertEquals(200, recent.size());
        assertTrue(recent.stream().noneMatch(row -> "old-original".equals(row.get("requestId"))));
        Map<String, Object> recovered = service.applicationByRequest(OWNER, "old-original");
        assertEquals(original, id(recovered));
        assertEquals(1, ((List<?>) recovered.get("orders")).size());
        assertEquals("91******7890", recovered.get("taxNoMasked"));
        assertEquals(UserInvoiceException.NOT_FOUND, failure(() -> service.applicationByRequest(72L, "old-original")));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
    }

    private void parentOrder() {
        jdbc.update("UPDATE cms_registration SET parent_order_no='P-INVOICE-IT', payable_amount=CASE WHEN id=? THEN 30.00 ELSE 0 END, "
                + "wechat_payment_amount=CASE WHEN id=? THEN 30.00 ELSE 12.34 END WHERE id IN (?,?)", ORDER, ORDER, ORDER, ORDER + 1);
    }

    @Test
    void parentCashIsCountedOnceAndChildPricePlaceholderCannotBeApplied() {
        parentOrder();
        assertTrue(service.listInvoiceableOrders(OWNER).stream().noneMatch(row -> row.getOrderId().equals(ORDER + 1)));
        assertEquals(UserInvoiceException.ORDER_NOT_ELIGIBLE,
                failure(() -> service.apply(OWNER, "child-only", titleId, List.of(ORDER + 1))));
        assertEquals(UserInvoiceException.ORDER_NOT_ELIGIBLE,
                failure(() -> service.apply(OWNER, "parent-and-child", titleId, List.of(ORDER, ORDER + 1))));
        Map<String, Object> result = service.apply(OWNER, "parent-cash", titleId, List.of(ORDER));
        assertEquals(new java.math.BigDecimal("30.00"), result.get("totalAmount"));
        assertEquals(new java.math.BigDecimal("30.00"), jdbc.queryForObject("SELECT SUM(amount) FROM user_invoice_application_order", java.math.BigDecimal.class));
    }

    @Test
    void legacyActiveChildInvoiceBlocksWholeParentCashUntilExplicitRelease() {
        // Create a pre-upgrade child occupancy, then expose the parent relationship.
        long legacy = id(service.apply(OWNER, "legacy-child", titleId, List.of(ORDER + 1)));
        parentOrder();
        assertTrue(service.listInvoiceableOrders(OWNER).stream().noneMatch(row -> row.getOrderId().equals(ORDER)));
        assertEquals(UserInvoiceException.ORDER_ALREADY_INVOICED,
                failure(() -> service.apply(OWNER, "parent-after-legacy", titleId, List.of(ORDER))));
        service.cancel(OWNER, legacy);
        assertEquals(new java.math.BigDecimal("30.00"), service.apply(OWNER, "parent-after-legacy", titleId, List.of(ORDER)).get("totalAmount"));
    }

    @Test
    void approvedSiblingRefundRemainsBlockingDespiteFailedPayout() {
        parentOrder();
        jdbc.update("INSERT INTO refund_application(source_type,source_id,member_id,status,payout_status,refund_amount) VALUES('registration',?,?,1,2,5.00)", ORDER + 1, OWNER);
        assertTrue(service.listInvoiceableOrders(OWNER).stream().noneMatch(row -> row.getOrderId().equals(ORDER)));
        assertEquals(UserInvoiceException.ORDER_NOT_ELIGIBLE,
                failure(() -> service.apply(OWNER, "sibling-refunded", titleId, List.of(ORDER))));
        assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_application_order"));
    }

    @Test
    void waitingInvoiceCurrentReadsRefundCommittedAfterItsRepeatableReadSnapshot() throws Exception {
        parentOrder();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection refund = dataSource.getConnection()) {
            refund.setAutoCommit(false);
            try (var lock = refund.prepareStatement("SELECT id FROM cms_registration WHERE parent_order_no='P-INVOICE-IT' ORDER BY id FOR UPDATE")) {
                try (var rows = lock.executeQuery()) { while (rows.next()) { /* hold entire parent like the refund service */ } }
            }
            CountDownLatch snapshot = new CountDownLatch(1);
            Future<String> application = executor.submit(() -> {
                var transaction = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
                transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return transaction.execute(status -> {
                    // Freeze an older RR snapshot before the refund commits; the invoice service must use current reads.
                    jdbc.queryForObject("SELECT COUNT(*) FROM refund_application", Integer.class);
                    snapshot.countDown();
                    try { service.apply(OWNER, "wait-refund", titleId, List.of(ORDER)); return "OK"; }
                    catch (UserInvoiceException e) { status.setRollbackOnly(); return e.getErrorCode(); }
                });
            });
            assertTrue(snapshot.await(10, TimeUnit.SECONDS));
            try (var insert = refund.prepareStatement("INSERT INTO refund_application(source_type,source_id,member_id,status,refund_amount) VALUES('registration',?,?,0,5.00)")) {
                insert.setLong(1, ORDER + 1); insert.setLong(2, OWNER); insert.executeUpdate();
            }
            refund.commit();
            assertEquals(UserInvoiceException.ORDER_NOT_ELIGIBLE, application.get(30, TimeUnit.SECONDS));
            assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_application"));
            assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_application_order"));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private UserInvoiceTitle titleCommand(String name) {
        UserInvoiceTitle title = new UserInvoiceTitle(); title.setTitleType("COMPANY");
        title.setTitleName(name); title.setTaxNo("913100001234567890"); title.setIsDefault(1);
        return title;
    }

    @Test
    void concurrentSameTitleCommandCommitsOneTitleAndOneNonSensitiveReceipt() throws Exception {
        List<String> results = race(
                () -> service.saveTitleWithRequest(OWNER, "title-command", titleCommand("Same title")),
                () -> service.saveTitleWithRequest(OWNER, "title-command", titleCommand("Same title")));
        assertEquals(List.of("OK", "OK"), results);
        assertEquals(2, count("SELECT COUNT(*) FROM user_invoice_title"));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_title_receipt"));
        Map<String, Object> receipt = service.titleSaveReceipt(OWNER, "title-command");
        assertEquals(java.util.Set.of("requestId", "titleId", "status"), receipt.keySet());
        assertEquals("SAVED", receipt.get("status"));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_title WHERE is_default=1"));
        assertEquals(64, jdbc.queryForObject("SELECT LENGTH(payload_fingerprint) FROM user_invoice_title_receipt", Integer.class));
    }

    @Test
    void titleReplayDoesNotOverwriteLaterEditsOrResurrectDeletedTitleAndForeignOwnerCannotReadReceipt() {
        Map<String, Object> receipt = service.saveTitleWithRequest(OWNER, "title-stable", titleCommand("Original name"));
        long saved = Long.parseLong(receipt.get("titleId").toString());
        UserInvoiceTitle edit = titleCommand("Later name"); edit.setId(saved); service.saveTitle(OWNER, edit);
        assertEquals(receipt, service.saveTitleWithRequest(OWNER, "title-stable", titleCommand("Original name")));
        assertEquals("Later name", mapper.selectTitle(OWNER, saved).getTitleName());
        assertEquals(UserInvoiceException.REQUEST_REUSED,
                failure(() -> service.saveTitleWithRequest(OWNER, "title-stable", titleCommand("Different input"))));
        UserInvoiceTitle changedTax = titleCommand("Original name"); changedTax.setTaxNo("913100001234567891");
        UserInvoiceTitle changedDefault = titleCommand("Original name"); changedDefault.setIsDefault(0);
        UserInvoiceTitle changedTarget = titleCommand("Original name"); changedTarget.setId(titleId);
        for (UserInvoiceTitle changed : List.of(changedTax, changedDefault, changedTarget)) {
            assertEquals(UserInvoiceException.REQUEST_REUSED,
                    failure(() -> service.saveTitleWithRequest(OWNER, "title-stable", changed)));
        }
        assertEquals(UserInvoiceException.NOT_FOUND, failure(() -> service.titleSaveReceipt(72L, "title-stable")));
        service.deleteTitle(OWNER, saved);
        assertEquals(receipt, service.saveTitleWithRequest(OWNER, "title-stable", titleCommand("Original name")));
        assertEquals(receipt, service.titleSaveReceipt(OWNER, "title-stable"));
        assertNull(mapper.selectTitle(OWNER, saved));
    }

    @Test
    void concurrentDifferentTitlePayloadsConflictWithoutSecondWrite() throws Exception {
        List<String> results = race(
                () -> service.saveTitleWithRequest(OWNER, "title-conflict", titleCommand("First")),
                () -> service.saveTitleWithRequest(OWNER, "title-conflict", titleCommand("Second")));
        assertEquals(1, results.stream().filter("OK"::equals).count());
        assertTrue(results.contains(UserInvoiceException.REQUEST_REUSED));
        assertEquals(2, count("SELECT COUNT(*) FROM user_invoice_title"));
        assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_title_receipt"));
    }

    @Test
    void titleReceiptFailureRollsBackNewTitleAndDefaultChangeThenSameRequestCanRetry() {
        jdbc.execute("CREATE TRIGGER fail_title_receipt BEFORE UPDATE ON user_invoice_title_receipt FOR EACH ROW "
                + "BEGIN IF NEW.title_id IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected receipt failure'; END IF; END");
        try {
            assertThrows(RuntimeException.class, () -> service.saveTitleWithRequest(OWNER, "title-rollback", titleCommand("Rollback")));
            assertEquals(1, count("SELECT COUNT(*) FROM user_invoice_title"));
            assertEquals(1, mapper.selectTitle(OWNER, titleId).getIsDefault());
            assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_title_receipt"));
        } finally { jdbc.execute("DROP TRIGGER fail_title_receipt"); }
        assertEquals("SAVED", service.saveTitleWithRequest(OWNER, "title-rollback", titleCommand("Rollback")).get("status"));
    }

    @Test
    void failureAfterOrderInsertRollsBackApplicationAndAllOccupancy() {
        InvoiceIssuer failing = new InvoiceIssuer() {
            @Override public String name() { return "MANUAL"; }
            @Override public Receipt submit(Request request) { throw new IllegalStateException("injected before transaction commit"); }
        };
        assertThrows(IllegalStateException.class, () -> transactional(failing).apply(OWNER, "rollback", titleId, List.of(ORDER, ORDER + 1)));
        assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_application"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_invoice_application_order"));
        service.apply(OWNER, "rollback", titleId, List.of(ORDER, ORDER + 1));
        assertEquals(2, count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
    }

    private UserInvoiceProcessingService.Command command(String request,long version,String action,String operation,String number,String evidence,String outcome) {
        return new UserInvoiceProcessingService.Command(request,version,action,operation,number,evidence,outcome);
    }

    @Test
    void scopedTaxAuditAndUnknownReplayNeverReleaseOrLeakTax() {
        long app=id(service.apply(OWNER,"desk-unknown",titleId,List.of(ORDER)));
        assertEquals(UserInvoiceException.NOT_FOUND,failure(()->desk.detail(903L,app)));
        assertFalse(desk.list(901L,null).get(0).containsKey("taxNo"));
        assertEquals("913100001234567890",desk.revealTaxNumber(901L,app,"reveal").get("taxNo"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_processing_audit WHERE action='TAX_VIEW'"));
        assertEquals(UserInvoiceException.NOT_FOUND,failure(()->desk.revealTaxNumber(902L,app,"not-handler")));
        desk.command(901L,app,command("claim",0,"CLAIM",null,null,null,null));
        desk.command(901L,app,command("start",1,"START",null,null,null,null));
        var unknown=command("unknown",2,"UNKNOWN",app+"_start",null,null,null);
        assertEquals(desk.command(901L,app,unknown),desk.command(901L,app,unknown));
        assertEquals("ISSUE_UNKNOWN",desk.detail(901L,app).get("status"));
        assertEquals(app+"_start",desk.detail(901L,app).get("operationId"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        assertEquals(UserInvoiceException.REQUEST_REUSED,failure(()->desk.command(901L,app,command("unknown",2,"UNKNOWN","different",null,null,null))));
        assertEquals(UserInvoiceException.NOT_FOUND,failure(()->desk.receipt(901L,app,"absent")));
        jdbc.update("UPDATE user_invoice_scope SET enabled=0 WHERE user_id=901");
        assertEquals(UserInvoiceException.NOT_FOUND,failure(()->desk.revealTaxNumber(901L,app,"revoked")));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_processing_audit WHERE action='TAX_VIEW'"));
    }

    @Test
    void refundDuringExternalProcessingPausesBeforeReceiptAndManualNoIssueReleasesOnce() {
        long app=id(service.apply(OWNER,"desk-refund",titleId,List.of(ORDER)));
        desk.command(901L,app,command("claim",0,"CLAIM",null,null,null,null));
        desk.command(901L,app,command("start",1,"START",null,null,null,null));
        jdbc.update("INSERT INTO refund_application(source_type,source_id,member_id,status,refund_amount) VALUES('registration',?,?,0,5.00)",ORDER,OWNER);
        // Even before asynchronous ingress, an unissued advancement must persist PAUSED.
        assertEquals("PAUSED",desk.command(901L,app,command("unknown",2,"UNKNOWN",app+"_start",null,null,null)).get("status"));
        desk.acceptRegistrationRefundFact(ORDER); desk.acceptRegistrationRefundFact(ORDER);
        assertEquals(app+"_start",desk.detail(901L,app).get("operationId"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        assertEquals("DECLINED",desk.command(901L,app,command("resume",3,"START",null,null,null,null)).get("status"));
        var close=command("verify-none",3,"RECONCILE",app+"_start",null,"evidence:no-issue","NO_EXTERNAL_ISSUE");
        assertEquals("REJECTED",desk.command(902L,app,close).get("status"));
        assertEquals(desk.receipt(902L,app,"verify-none"),desk.command(902L,app,close));
        assertEquals(0,count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        assertEquals("REJECTED",service.applicationByRequest(OWNER,"desk-refund").get("status"));
        assertEquals("NO_ISSUE_CONFIRMED",service.applicationByRequest(OWNER,"desk-refund").get("reconciliationStatus"));
        assertEquals("NO_ISSUE_CONFIRMED",service.applicationDetail(OWNER,app).get("reconciliationStatus"));
        assertEquals("NO_ISSUE_CONFIRMED",service.listApplications(OWNER).get(0).get("reconciliationStatus"));
        assertThrows(UserInvoiceException.class,()->service.applicationDetail(OWNER + 1,app));
        assertEquals(1,count("SELECT COUNT(*) FROM refund_application WHERE status=0"));
    }

    @Test
    void missingConfigurationAndCrossIssuerFailClosedAndLegacyIsNotAssigned() {
        jdbc.update("UPDATE user_invoice_scope SET enabled=0 WHERE user_id=902");
        assertEquals(UserInvoiceException.ORDER_NOT_ELIGIBLE,failure(()->service.apply(OWNER,"missing-reviewer",titleId,List.of(ORDER))));
        jdbc.update("UPDATE user_invoice_scope SET enabled=1 WHERE user_id=902");
        jdbc.update("INSERT INTO user_invoice_issuer VALUES(302,'Other isolated issuer',902,1)");
        jdbc.update("INSERT INTO user_invoice_scope VALUES(302,901,1,0,1),(302,902,0,1,1)");
        jdbc.update("INSERT INTO user_invoice_issuer_owner VALUES(1,102,302)");
        jdbc.update("UPDATE cms_registration SET owner_id=102 WHERE id=?",ORDER+1);
        assertEquals(UserInvoiceException.ORDER_NOT_ELIGIBLE,failure(()->service.apply(OWNER,"cross-issuer",titleId,List.of(ORDER,ORDER+1))));
        long app=id(service.apply(OWNER,"legacy-missing-scope",titleId,List.of(ORDER)));
        jdbc.update("DELETE FROM user_invoice_processing WHERE application_id=?",app);
        assertEquals(UserInvoiceException.NOT_FOUND,failure(()->desk.command(901L,app,command("claim",0,"CLAIM",null,null,null,null))));
    }

    @Test
    void twoHandlersCompeteForOneClaimAndAuditFailureRollsBack() throws Exception {
        jdbc.update("INSERT INTO user_invoice_scope VALUES(301,903,1,0,1)");
        long app=id(service.apply(OWNER,"claim-race",titleId,List.of(ORDER)));
        race(()->desk.command(901L,app,command("claim-a",0,"CLAIM",null,null,null,null)),
             ()->desk.command(903L,app,command("claim-b",0,"CLAIM",null,null,null,null)));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_processing_audit WHERE result_status='CLAIMED'"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_processing_audit WHERE result_status='DECLINED'"));
        long handler=jdbc.queryForObject("SELECT handler_id FROM user_invoice_processing WHERE application_id=?",Long.class,app);
        jdbc.execute("CREATE TRIGGER reject_processing_audit BEFORE INSERT ON user_invoice_processing_audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated audit failure'");
        assertThrows(RuntimeException.class,()->desk.command(handler,app,command("start-fails",1,"START",null,null,null,null)));
        assertEquals("CLAIMED",desk.detail(handler,app).get("status"));
        assertNull(desk.detail(handler,app).get("operationId"));
        jdbc.execute("DROP TRIGGER reject_processing_audit");
    }

    @Test
    void duplicateExternalReferenceIsDeclinedWithoutLosingOriginalOperationOrOccupancy() {
        long first=id(service.apply(OWNER,"external-one",titleId,List.of(ORDER)));
        long second=id(service.apply(OWNER,"external-two",titleId,List.of(ORDER+1)));
        for(long app:List.of(first,second)) {
            desk.command(901L,app,command("claim",0,"CLAIM",null,null,null,null));
            desk.command(901L,app,command("start",1,"START",null,null,null,null));
        }
        var issued=command("issued",2,"RECORD_ISSUED",first+"_start","NOT-A-REAL-INVOICE","evidence:verified",null);
        assertEquals("ISSUED",desk.command(901L,first,issued).get("status"));
        assertEquals("DECLINED",desk.command(901L,second,command("issued",2,"RECORD_ISSUED",second+"_start","NOT-A-REAL-INVOICE","evidence:verified",null)).get("status"));
        assertEquals("PROCESSING",desk.detail(901L,second).get("status"));
        assertEquals(second+"_start",desk.detail(901L,second).get("operationId"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_issued_reference"));
        assertEquals(2,count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
    }

    @Test
    void closingUnreceiptedCommandFencesLateWriteWithoutCancellingInvoice() throws Exception {
        long app=id(service.apply(OWNER,"fence-command",titleId,List.of(ORDER)));
        assertEquals("FENCED",desk.closeUnconfirmed(901L,app,"late-claim","CLAIM").get("status"));
        assertEquals(UserInvoiceException.REQUEST_REUSED,failure(()->desk.command(901L,app,command("late-claim",0,"CLAIM",null,null,null,null))));
        assertEquals("SUBMITTED",service.applicationDetail(OWNER,app).get("status"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        race(()->desk.command(901L,app,command("racing-claim",0,"CLAIM",null,null,null,null)),
             ()->desk.closeUnconfirmed(901L,app,"racing-claim","CLAIM"));
        Map<String,Object> r=desk.receipt(901L,app,"racing-claim");
        assertTrue(List.of("FENCED","CLAIMED").contains(r.get("status")));
        assertEquals("FENCED".equals(r.get("status"))?"SUBMITTED":"CLAIMED",service.applicationDetail(OWNER,app).get("status"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_processing_audit WHERE request_id='racing-claim'"));
    }

    @Test
    void externalResultWaitingOnConcurrentRefundMustUseCurrentFactsNotEarlierSnapshot() throws Exception {
        long app=id(service.apply(OWNER,"concurrent-refund",titleId,List.of(ORDER)));
        desk.command(901L,app,command("claim",0,"CLAIM",null,null,null,null));
        desk.command(901L,app,command("start",1,"START",null,null,null,null));
        ExecutorService executor=Executors.newSingleThreadExecutor();
        CountDownLatch snapshot=new CountDownLatch(1);
        try(Connection refund=dataSource.getConnection()) {
            refund.setAutoCommit(false);
            try(var lock=refund.prepareStatement("SELECT id FROM cms_registration WHERE id=? FOR UPDATE")) {
                lock.setLong(1,ORDER); try(var rows=lock.executeQuery()) {assertTrue(rows.next());}
            }
            Future<Map<String,Object>> advance=executor.submit(()-> {
                var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
                tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return tx.execute(status->{
                    assertEquals(0,count("SELECT COUNT(*) FROM refund_application")); snapshot.countDown();
                    return desk.command(901L,app,command("late-issued",2,"RECORD_ISSUED",app+"_start","NOT-REAL","evidence:race",null));
                });
            });
            assertTrue(snapshot.await(10,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,()->advance.get(100,TimeUnit.MILLISECONDS));
            try(var insert=refund.prepareStatement("INSERT INTO refund_application(source_type,source_id,member_id,status,refund_amount) VALUES('registration',?,?,0,5.00)")) {
                insert.setLong(1,ORDER); insert.setLong(2,OWNER); insert.executeUpdate();
            }
            refund.commit();
            // R625 (b): the external invoice exists; it is recorded as ISSUED with its number, flagged from current refund facts.
            assertEquals("ISSUED",advance.get(30,TimeUnit.SECONDS).get("status"));
            assertEquals("TAX_REVIEW_REQUIRED",desk.detail(901L,app).get("reconciliationStatus"));
            assertEquals("NOT-REAL",service.applicationDetail(OWNER,app).get("invoiceNo"));
            assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_issued_reference"));
            assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        } finally {executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test
    void refundAfterRecordedIssuanceKeepsEvidenceAndOccupancyForTaxReview() {
        long app=id(service.apply(OWNER,"issued-then-refund",titleId,List.of(ORDER)));
        desk.command(901L,app,command("claim",0,"CLAIM",null,null,null,null));
        desk.command(901L,app,command("start",1,"START",null,null,null,null));
        desk.command(901L,app,command("issued",2,"RECORD_ISSUED",app+"_start","NOT-REAL","evidence:issued",null));
        jdbc.update("INSERT INTO refund_application(source_type,source_id,member_id,status,payout_status,refund_amount) VALUES('registration',?,?,1,2,5.00)",ORDER,OWNER);
        desk.acceptRegistrationRefundFact(ORDER); desk.acceptRegistrationRefundFact(ORDER);
        // R625 (b): a refund after issuance never demotes ISSUED or drops the invoice number.
        assertEquals("ISSUED",service.applicationDetail(OWNER,app).get("status"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_processing_audit WHERE action='REFUND_TAX_REVIEW' AND result_status='ISSUED'"));
        assertEquals("DECLINED",desk.command(902L,app,command("incorrect-none",4,"RECONCILE",app+"_start",null,"evidence:conflict","NO_EXTERNAL_ISSUE")).get("status"));
        assertEquals("TAX_REVIEW_REQUIRED",desk.detail(902L,app).get("reconciliationStatus"));
        var memberResult=service.applicationDetail(OWNER,app);
        assertEquals("TAX_REVIEW_REQUIRED",memberResult.get("reconciliationStatus"));
        assertFalse(memberResult.containsKey("evidenceRef"));
        assertFalse(memberResult.containsKey("handlerId"));
        assertEquals("NOT-REAL",service.applicationDetail(OWNER,app).get("invoiceNo"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_issued_reference"));
    }

    @Test
    void differentIssuerHandlersCannotReadTaxOrProcessEachOthersApplications() {
        jdbc.update("INSERT INTO user_invoice_issuer VALUES(302,'Other isolated issuer',902,1)");
        jdbc.update("INSERT INTO user_invoice_scope VALUES(302,903,1,0,1),(302,902,0,1,1)");
        jdbc.update("INSERT INTO user_invoice_issuer_owner VALUES(1,102,302)");
        jdbc.update("UPDATE cms_registration SET owner_id=102 WHERE id=?",ORDER+1);
        long first=id(service.apply(OWNER,"scope-one",titleId,List.of(ORDER)));
        long second=id(service.apply(OWNER,"scope-two",titleId,List.of(ORDER+1)));
        assertEquals(List.of(Long.toString(first)),desk.list(901L,null).stream().map(r->r.get("id")).toList());
        assertEquals(List.of(Long.toString(second)),desk.list(903L,null).stream().map(r->r.get("id")).toList());
        assertEquals(UserInvoiceException.NOT_FOUND,failure(()->desk.revealTaxNumber(901L,second,"foreign-tax")));
        assertEquals(UserInvoiceException.NOT_FOUND,failure(()->desk.command(901L,second,command("foreign-claim",0,"CLAIM",null,null,null,null))));
        assertEquals("SUBMITTED",service.applicationDetail(OWNER,second).get("status"));
        assertEquals(0,count("SELECT COUNT(*) FROM user_invoice_processing_audit"));
    }

    @Test
    void invoiceOwnedScannerFindsSiblingRefundAndRetriesAuditFailureWithoutRefundCallback() {
        parentOrder();
        long app=id(service.apply(OWNER,"scan-parent",titleId,List.of(ORDER)));
        desk.command(901L,app,command("claim",0,"CLAIM",null,null,null,null));
        desk.command(901L,app,command("start",1,"START",null,null,null,null));
        jdbc.update("INSERT INTO refund_application(source_type,source_id,member_id,status,refund_amount) VALUES('registration',?,?,0,5.00)",ORDER+1,OWNER);
        assertEquals(List.of(ORDER),processingMapper.refundPauseCandidates(0));
        jdbc.execute("CREATE TRIGGER reject_pause_audit BEFORE INSERT ON user_invoice_processing_audit FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated audit failure'");
        reconciliation.reconcilePendingRefundFacts();
        assertEquals("PROCESSING",service.applicationDetail(OWNER,app).get("status"));
        assertEquals(List.of(ORDER),processingMapper.refundPauseCandidates(0));
        jdbc.execute("DROP TRIGGER reject_pause_audit");
        new UserInvoiceRefundReconciliationJob(processingMapper,mapper,desk).reconcilePendingRefundFacts();
        reconciliation.reconcilePendingRefundFacts();
        assertEquals("PAUSED",service.applicationDetail(OWNER,app).get("status"));
        assertEquals(app+"_start",desk.detail(901L,app).get("operationId"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_processing_audit WHERE action='REFUND_PAUSE'"));
        assertEquals(1,count("SELECT COUNT(*) FROM refund_application WHERE status=0"));
        assertTrue(processingMapper.refundPauseCandidates(0).isEmpty());
    }

    @Test
    void scopedManualRefundRefreshWorksWithoutSchedulerAndDoesNotDiscardOriginalCommand() {
        long app=id(service.apply(OWNER,"manual-facts",titleId,List.of(ORDER)));
        desk.command(901L,app,command("claim",0,"CLAIM",null,null,null,null));
        desk.command(901L,app,command("start",1,"START",null,null,null,null));
        jdbc.update("INSERT INTO refund_application(source_type,source_id,member_id,status,refund_amount) VALUES('registration',?,?,1,5.00)",ORDER,OWNER);
        assertEquals(UserInvoiceException.NOT_FOUND,failure(()->reconciliation.refreshApplication(903L,app)));
        assertEquals("PROCESSING",service.applicationDetail(OWNER,app).get("status"));
        assertEquals("PAUSED",reconciliation.refreshApplication(901L,app).get("status"));
        assertEquals("PROCESSING",desk.receipt(901L,app,"start").get("status"));
        assertEquals(1,count("SELECT COUNT(*) FROM user_invoice_application_order WHERE active_key=1"));
    }
}
