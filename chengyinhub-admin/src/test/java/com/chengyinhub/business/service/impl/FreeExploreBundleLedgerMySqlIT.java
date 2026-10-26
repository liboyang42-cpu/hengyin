package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.ExploreBundleRefundMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.ExploreBundleOrder;
import com.chengyinhub.business.domain.ExploreBundleReservation;
import com.chengyinhub.business.domain.ExploreBundleUnit;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.exception.FreeExploreBundleException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ExploreBundleMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
import com.chengyinhub.business.mapper.SettlementRuleMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.FreeExploreBundleSwitches;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC2 F3 Phase 1 真库验收:本批账本迁移 + 真 ExploreBundleMapper.xml + 真 SettlementClaimMapper.xml。
 *
 * <ul>
 *   <li>迁移可重跑;表形状被改动后重跑必须 guard 报错(负控)。</li>
 *   <li>Phase 1 不动资金表 settlement_claim:不加 unit_code,7 列经济键唯一闸原样(单位维度留待 Phase 2 资金审批)。</li>
 *   <li>A、B 买同一套票:A 核销只动 A 的单位,B 余额不变;同一 registration 只冻结一张单;
 *       改产品不改已售快照;同单位同时只许一条在约;月度豁免唯一。</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreeExploreBundleLedgerMySqlIT {
    private static final String LEDGER = "sql/migration_doc2_f3_bundle_ledger_20261022.sql";
    private static final long TOPIC = 70L;
    private static final long CH1 = 701L;
    private static final long CH2 = 702L;
    private static final long MERCHANT = 9001L;
    private static final long OFFER1 = 801L;
    private static final long OFFER2 = 802L;

    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private org.springframework.transaction.support.TransactionTemplate transactions;
    private String databaseUrl;
    private String user;
    private String password;
    private ExploreBundleMapper bundleMapper;
    private SettlementClaimMapper claimMapper;
    private ExploreBundleRefundMapper holdMapper;
    private FreeExploreBundleRefundServiceImpl refundService;
    private final RefundApplicationMapper refundMapper = mock(RefundApplicationMapper.class);
    private final Map<Long, RefundApplication> refundIntents = new HashMap<>();
    private FreeExploreBundleServiceImpl service;
    private final Date now = new Date(1792000000000L);   // 固定时钟
    private final List<CmsTopicChapter> chapters = new ArrayList<>();
    private final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
    private final Map<Long, CmsRegistration> registrations = new HashMap<>();

    @BeforeAll
    void createScratchSchema() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("f3_bundle");
        if (!config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")) {
            throw new IllegalStateException("loopback task MySQL only");
        }
        database = "chengyin_f3_bundle_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        transactions = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        databaseUrl = config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?");
        user = config.user();
        password = config.password();
        db.execute(createTable(read("../sql/base_schema_20260818.sql"), "settlement_claim"));
        apply(LEDGER);
        apply(LEDGER);
        apply("sql/migration_doc2_f3_refund_hold_20261026.sql");
        apply("sql/migration_doc2_f3_refund_hold_20261026.sql");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ExploreBundleMapper.xml"),
                new ClassPathResource("mapper/business/SettlementClaimMapper.xml"),
                new ClassPathResource("mapper/business/ExploreBundleRefundMapper.xml"));
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        bundleMapper = template.getMapper(ExploreBundleMapper.class);
        claimMapper = template.getMapper(SettlementClaimMapper.class);
        service = newService();
        holdMapper = template.getMapper(ExploreBundleRefundMapper.class);
        refundService = new FreeExploreBundleRefundServiceImpl(registrationMapper, refundMapper, bundleMapper, holdMapper);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(anyLong())).thenAnswer(inv -> registrations.get(inv.<Long>getArgument(0)));
        when(refundMapper.selectRefundApplicationById(anyLong())).thenAnswer(inv -> refundIntents.get(inv.<Long>getArgument(0)));
        when(refundMapper.selectRegistrationHistoryForUpdate(anyLong())).thenAnswer(inv -> refundIntents.values().stream()
                .filter(row -> inv.<Long>getArgument(0).equals(row.getSourceId())).collect(java.util.stream.Collectors.toList()));
    }

    @AfterAll
    void dropOnlyScratchDatabase() {
        if (database != null && admin != null) {
            admin.execute("DROP DATABASE " + database);
            assertEquals(0, admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        }
    }

    @Test
    void ledgerMigrationGuardRejectsChangedShape() throws Exception {
        db.execute("ALTER TABLE explore_bundle_waiver MODIFY waiver_month CHAR(8) NOT NULL");
        assertThrows(Exception.class, () -> apply(LEDGER), "形状被改的同名表不得当成已完成");
        db.execute("ALTER TABLE explore_bundle_waiver MODIFY waiver_month CHAR(7) NOT NULL COMMENT '自然月 yyyy-MM'");
        apply(LEDGER);
    }

    @Test
    void phase1LeavesSettlementClaimUntouched() {
        assertEquals(0, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='settlement_claim' AND column_name='unit_code'"), "Phase 1 不给资金表加 unit_code");
        assertEquals(7, count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                + " AND table_name='settlement_claim' AND index_name='uk_settlement_claim_key'"), "唯一键仍是存量 7 列");
        assertEquals(1, claimMapper.insertClaim(claim()));
        assertThrows(DataIntegrityViolationException.class, () -> claimMapper.insertClaim(claim()),
                "存量 7 列经济键唯一性不变");
    }

    @Test
    void twoHoldersOfSameBundleHaveIndependentBalancesAndSnapshotsAreFrozen() {
        CmsRegistration a = registration(1001L, 11L);
        CmsRegistration b = registration(1002L, 12L);
        ExploreBundleOrder orderA = service.freezeOnOrderCreated(a, topic(), activity());
        service.freezeOnOrderCreated(b, topic(), activity());
        // 同一张 registration(同一请求的重放)只会有一张套票单
        ExploreBundleOrder again = service.freezeOnOrderCreated(a, topic(), activity());
        assertEquals(orderA.getId(), again.getId());
        assertEquals(1, count("SELECT COUNT(*) FROM explore_bundle_order WHERE registration_id=1001"));
        assertEquals(2, count("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id=1001"));

        service.onChapterRedeemed(1001L, CH1, MERCHANT, 5001L);
        assertEquals("REDEEMED", unitStatus(1001L, CH1));
        assertEquals("AVAILABLE", unitStatus(1002L, CH1), "A 的核销不影响 B 的余额");
        assertEquals("AVAILABLE", unitStatus(1001L, CH2));
        FreeExploreBundleException again2 = assertThrows(FreeExploreBundleException.class,
                () -> service.onChapterRedeemed(1001L, CH1, MERCHANT, 5002L));
        assertEquals("BUNDLE_NO_BALANCE", again2.getBusinessCode());
        Map<String, Object> viewB = service.orderView(12L, 1002L);
        assertTrue(String.valueOf(viewB).contains("AVAILABLE=1, RESERVED=0, REDEEMED=0"), String.valueOf(viewB));
        assertTrue(!String.valueOf(viewB).contains("REDEEMED=1"), String.valueOf(viewB));
        assertThrows(FreeExploreBundleException.class, () -> service.orderView(11L, 1002L), "A 读不到 B 的订单");

        // 撤销核销:单位退回可用
        service.onRedemptionReversed(5001L);
        assertEquals("AVAILABLE", unitStatus(1001L, CH1));

        // 编辑产品(章节改名)后新售单的产品版本变化,已售快照逐字不变
        String before = db.queryForObject("SELECT snapshot_json FROM explore_bundle_order WHERE registration_id=1001", String.class);
        chapters.get(0).setName("改过的章节名");
        service.freezeOnOrderCreated(registration(1003L, 13L), topic(), activity());
        assertEquals(before, db.queryForObject("SELECT snapshot_json FROM explore_bundle_order WHERE registration_id=1001", String.class));
        assertNotEquals(
                db.queryForObject("SELECT product_version FROM explore_bundle_order WHERE registration_id=1001", String.class),
                db.queryForObject("SELECT product_version FROM explore_bundle_order WHERE registration_id=1003", String.class));
        chapters.get(0).setName("第一章");
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id=1001 AND status='REDEEMED'"));
    }

    @Test
    void reservationsAreUniquePerUnitAndPenaltiesConsumeOnlyThatUnit() {
        service.freezeOnOrderCreated(registration(2001L, 21L), topic(), activity());
        Long unitId = unitId(2001L, CH1);
        Date in48h = new Date(now.getTime() + 48L * 3600_000L);
        Date in2h = new Date(now.getTime() + 2L * 3600_000L);
        ExploreBundleReservation first = service.reserve(21L, 2001L, unitId, OFFER1, in48h, "rsv-1");
        assertEquals("BOOKED", first.getStatus());
        assertEquals(first.getId(), service.reserve(21L, 2001L, unitId, OFFER1, in48h, "rsv-1").getId(), "同键重放回原预约");
        assertEquals("BUNDLE_IDEMPOTENCY_CONFLICT", assertThrows(FreeExploreBundleException.class,
                () -> service.reserve(21L, 2001L, unitId, OFFER1, in2h, "rsv-1")).getBusinessCode());
        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO explore_bundle_reservation(unit_id,registration_id,holder_member_id,chapter_id,offer_id,"
                + "merchant_id,start_at,status,idempotency_key,request_fingerprint,create_time) VALUES(?,2001,21,?,?,?,"
                + "NOW(),'BOOKED','raw-dup',REPEAT('0',64),NOW())", unitId, CH1, OFFER1, MERCHANT),
                "同一单位同时只许一条在约");

        assertEquals("CANCELLED", service.cancelReservation(21L, first.getId(), false).getStatus(), ">=12h 免费取消");
        assertEquals("AVAILABLE", unitStatus(2001L, CH1));

        ExploreBundleReservation late = service.reserve(21L, 2001L, unitId, OFFER1, in2h, "rsv-2");
        assertEquals("WAIVED", service.cancelReservation(21L, late.getId(), true).getStatus(), "本月首次主动豁免");
        assertEquals("AVAILABLE", unitStatus(2001L, CH1));
        ExploreBundleReservation late2 = service.reserve(21L, 2001L, unitId, OFFER1, in2h, "rsv-3");
        assertEquals("BUNDLE_WAIVER_USED", assertThrows(FreeExploreBundleException.class,
                () -> service.cancelReservation(21L, late2.getId(), true)).getBusinessCode());
        assertEquals("LATE_CANCELLED", service.cancelReservation(21L, late2.getId(), false).getStatus());
        assertEquals("FORFEITED", unitStatus(2001L, CH1), "<12h 只消耗该单位");
        assertEquals("AVAILABLE", unitStatus(2001L, CH2), "其他单位不受影响");
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id=2001 AND redemption_id IS NOT NULL"),
                "罚没不是真实核销");
        Exception check = assertThrows(Exception.class, () -> db.update(
                "UPDATE explore_bundle_unit SET status='REDEEMED' WHERE id=?", unitId(2001L, CH2)));
        assertTrue(String.valueOf(check.getMessage()).contains("ck_bundle_unit_redeemed"),
                "REDEEMED 必须带真实 redemption_id:" + check.getMessage());
    }

    @Test
    void concurrentDuplicateCancellationReleasesUnitOnlyOnceInRealTransactions() throws Exception {
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(registration(3001L, 31L), topic(), activity()));
        Long unitId = unitId(3001L, CH1);
        ExploreBundleReservation reservation = transactions.execute(tx -> service.reserve(31L, 3001L, unitId,
                OFFER1, new Date(now.getTime() + 48L * 3600_000L), "concurrent-cancel"));
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> cancel = () -> {
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return transactions.execute(tx -> service.cancelReservation(31L, reservation.getId(), false).getStatus());
            };
            var first = pool.submit(cancel);
            var second = pool.submit(cancel);
            start.countDown();
            assertEquals("CANCELLED", first.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLED", second.get(15, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals("AVAILABLE", unitStatus(3001L, CH1));
        assertEquals(2, db.queryForObject("SELECT version FROM explore_bundle_unit WHERE id=?", Integer.class, unitId),
                "reserve increments once, concurrent cancels increment only once");
        assertEquals(1, db.queryForObject("SELECT version FROM explore_bundle_reservation WHERE id=?", Integer.class,
                reservation.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_waiver WHERE member_id=31"));
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id=3001 AND redemption_id IS NOT NULL"));
    }

    @Test
    void cancelRedeemAndNoShowRaceConservesSingleUnitAndReservationInRealTransactions() throws Exception {
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(registration(4001L, 41L), topic(), activity()));
        Long unitId = unitId(4001L, CH1);
        ExploreBundleReservation booking = transactions.execute(tx -> service.reserve(41L, 4001L, unitId,
                OFFER1, new Date(now.getTime() + 2L * 3600_000L), "three-way-race"));
        service.clock = () -> new Date(now.getTime() + 3L * 3600_000L);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            List<Runnable> actions = List.of(
                    () -> service.cancelReservation(41L, booking.getId(), false),
                    () -> service.onChapterRedeemed(4001L, CH1, MERCHANT, 7001L),
                    () -> service.markNoShow(MERCHANT, booking.getId()));
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (Runnable action : actions) {
                results.add(pool.submit(() -> {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    try {
                        transactions.executeWithoutResult(tx -> action.run());
                        return "DONE";
                    } catch (FreeExploreBundleException rejected) {
                        // These are committed terminal-state refusals, not SQL/deadlock exceptions.
                        assertTrue(List.of("BUNDLE_NO_BALANCE", "BUNDLE_RESERVATION_CLOSED").contains(rejected.getBusinessCode()),
                                rejected.getBusinessCode());
                        return rejected.getBusinessCode();
                    }
                }));
            }
            start.countDown();
            for (var result : results) result.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            service.clock = () -> now;
        }
        ExploreBundleReservation terminal = bundleMapper.selectReservationById(booking.getId());
        assertTrue(List.of("FULFILLED", "LATE_CANCELLED", "NO_SHOW").contains(terminal.getStatus()));
        assertEquals("FULFILLED".equals(terminal.getStatus()) ? "REDEEMED" : "FORFEITED", unitStatus(4001L, CH1));
        assertEquals(2, db.queryForObject("SELECT version FROM explore_bundle_unit WHERE id=?", Integer.class, unitId));
        assertEquals(1, terminal.getVersion());
        assertEquals("FULFILLED".equals(terminal.getStatus()) ? 1 : 0,
                count("SELECT COUNT(*) FROM explore_bundle_unit WHERE registration_id=4001 AND redemption_id IS NOT NULL"));
        assertEquals("AVAILABLE", unitStatus(4001L, CH2));
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_waiver WHERE member_id=41"));
    }

    @Test
    void concurrentSameReservationRequestReturnsOneReservationAndConsumesUnitOnce() throws Exception {
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(registration(5001L, 51L), topic(), activity()));
        Long unitId = unitId(5001L, CH1);
        Date startAt = new Date(now.getTime() + 48L * 3600_000L);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Long> reserve = () -> {
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return transactions.execute(tx -> service.reserve(51L, 5001L, unitId,
                        OFFER1, startAt, "concurrent-reserve").getId());
            };
            var first = pool.submit(reserve);
            var second = pool.submit(reserve);
            start.countDown();
            assertEquals(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM explore_bundle_reservation WHERE registration_id=5001"));
        assertEquals("RESERVED", unitStatus(5001L, CH1));
        assertEquals(1, db.queryForObject("SELECT version FROM explore_bundle_unit WHERE id=?", Integer.class, unitId));
        assertEquals("AVAILABLE", unitStatus(5001L, CH2));
    }

    @Test
    void refundFreezeApprovalUnknownFailureAndSuccessKeepOriginalIdentity() {
        CmsRegistration row = registration(6001L, 61L); row.setWechatPaymentAmount(new BigDecimal("99.00"));
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(row, topic(), activity()));
        RefundApplication app = refundIntent(6601L, row);
        transactions.executeWithoutResult(tx -> {
            var context = refundService.prepareFullRefund(6001L, new BigDecimal("99.00"));
            refundIntents.put(app.getId(), app);
            refundService.bindFullRefund(context, app.getId(), null);
        });
        assertEquals("REFUND_PENDING", unitStatus(6001L, CH1));
        app.setStatus(1);
        transactions.executeWithoutResult(tx -> refundService.onRegistrationRefundApproved(6001L));
        assertEquals("APPROVED", holdMapper.selectByRegistration(6001L).getState());
        assertEquals("REFUND_PENDING", unitStatus(6001L, CH1), "approval is not arrival");
        for (int payout : List.of(5, 6, 0, 2, 3, 4)) {
            app.setPayoutStatus(payout);
            transactions.executeWithoutResult(tx -> refundService.syncRefundOutcome(app.getId()));
            assertEquals("REFUND_PENDING", unitStatus(6001L, CH1));
            assertEquals(app.getId(), holdMapper.selectByRegistration(6001L).getRefundId());
        }
        app.setPayoutStatus(1);
        transactions.executeWithoutResult(tx -> refundService.syncRefundOutcome(app.getId()));
        assertEquals("SUCCEEDED", holdMapper.selectByRegistration(6001L).getState());
        assertEquals("REFUNDED", unitStatus(6001L, CH1));
        int version = holdMapper.selectByRegistration(6001L).getVersion();
        transactions.executeWithoutResult(tx -> refundService.syncRefundOutcome(app.getId()));
        assertEquals(version, holdMapper.selectByRegistration(6001L).getVersion());
        assertEquals(2, db.queryForObject("SELECT version FROM explore_bundle_unit WHERE id=?", Integer.class, unitId(6001L, CH1)));
    }

    @Test
    void unboundPreparationRollsBackAndPenaltyInZeroCashChildBlocksFullParent() {
        CmsRegistration payer = registration(6101L, 71L), child = registration(6102L, 72L);
        payer.setWechatPaymentAmount(new BigDecimal("99.00")); child.setWechatPaymentAmount(BigDecimal.ZERO);
        for (CmsRegistration row : List.of(payer, child)) {
            row.setParentOrderNo("f3-parent-61"); row.setOwnerType(2L); row.setOwnerId(60L);
            transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(row, topic(), activity()));
        }
        when(registrationMapper.selectByParentOrderNoForUpdate("f3-parent-61", 60L)).thenReturn(List.of(payer, child));
        assertEquals("BUNDLE_REFUND_CASH_SCOPE", assertThrows(FreeExploreBundleException.class,
                () -> transactions.executeWithoutResult(tx -> refundService.prepareFullRefund(6102L, BigDecimal.ZERO)))
                .getBusinessCode(), "zero-cash child cannot bypass the original cash payer");
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_refund_hold WHERE registration_id IN (6101,6102)"));
        assertThrows(FreeExploreBundleException.class, () -> transactions.executeWithoutResult(tx ->
                refundService.prepareFullRefund(6101L, new BigDecimal("99.00"))), "unbound positive cash must not commit");
        assertEquals("AVAILABLE", unitStatus(6101L, CH1));
        assertEquals(0, count("SELECT COUNT(*) FROM explore_bundle_refund_hold WHERE registration_id IN (6101,6102)"));
        transactions.executeWithoutResult(tx -> {
            var reservation = service.reserve(72L, 6102L, unitId(6102L, CH1), OFFER1,
                    new Date(now.getTime() + 2L * 3600_000L), "late-parent-child");
            service.cancelReservation(72L, reservation.getId(), false);
        });
        assertEquals("FORFEITED", unitStatus(6102L, CH1));
        assertEquals("BUNDLE_REFUND_CONSUMED", assertThrows(FreeExploreBundleException.class,
                () -> transactions.executeWithoutResult(tx -> refundService.prepareFullRefund(6101L, new BigDecimal("99.00"))))
                .getBusinessCode());
        assertEquals("AVAILABLE", unitStatus(6101L, CH1));
    }

    @Test
    void zeroCashCompletesWithoutFinancialIntentAndReplacementNeverReleasesUnits() {
        CmsRegistration zero = registration(6201L, 81L); zero.setWechatPaymentAmount(BigDecimal.ZERO); zero.setPayableAmount(BigDecimal.ZERO);
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(zero, topic(), activity()));
        transactions.executeWithoutResult(tx -> {
            var context = refundService.prepareFullRefund(6201L, BigDecimal.ZERO);
            zero.setRegistrationStatus(3);
            refundService.completeZeroCashRefund(context);
        });
        assertEquals("ZERO_CASH_COMPLETED", holdMapper.selectByRegistration(6201L).getState());
        assertEquals(null, holdMapper.selectByRegistration(6201L).getRefundId());
        assertEquals("REFUNDED", unitStatus(6201L, CH1));
        CmsRegistration row = registration(6202L, 82L); row.setWechatPaymentAmount(new BigDecimal("99.00"));
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(row, topic(), activity()));
        RefundApplication old = refundIntent(6621L, row), replacement = refundIntent(6622L, row);
        transactions.executeWithoutResult(tx -> {
            var context = refundService.prepareFullRefund(row.getId(), new BigDecimal("99.00"));
            refundIntents.put(old.getId(), old); refundService.bindFullRefund(context, old.getId(), null);
        });
        transactions.executeWithoutResult(tx -> {
            var context = refundService.prepareFullRefund(row.getId(), new BigDecimal("99.00"));
            old.setStatus(2); refundIntents.put(replacement.getId(), replacement);
            refundService.bindFullRefund(context, replacement.getId(), old.getId());
        });
        assertEquals(replacement.getId(), holdMapper.selectByRegistration(row.getId()).getRefundId());
        assertEquals("REFUND_PENDING", unitStatus(row.getId(), CH1));
        old.setStatus(1); old.setPayoutStatus(1);
        transactions.executeWithoutResult(tx -> refundService.syncRefundOutcome(old.getId()));
        assertEquals("RECONCILIATION_REQUIRED", holdMapper.selectByRegistration(row.getId()).getState());
        assertEquals("REFUND_PENDING", unitStatus(row.getId(), CH1), "late old success cannot complete replacement");
    }

    @Test
    void unusedMerchantExitCanBindOrdinaryFullRefundButBarePendingCannot() {
        CmsRegistration row = registration(6241L, 83L); row.setWechatPaymentAmount(new BigDecimal("99.00"));
        transactions.executeWithoutResult(tx -> {
            service.freezeOnOrderCreated(row, topic(), activity());
            service.reserve(83L, row.getId(), unitId(row.getId(), CH1), OFFER1,
                    new Date(now.getTime() + 24L * 3600_000L), "exit-before-refund");
        });
        MerchantChapterOfferMapper offers = (MerchantChapterOfferMapper) ReflectionTestUtils.getField(service, "offerMapper");
        MerchantChapterOffer exited = offers.selectById(OFFER1);
        exited.setStatus(0);
        try {
            transactions.executeWithoutResult(tx -> service.decideMerchantExit(83L, unitId(row.getId(), CH1),
                    "REFUND_UNFULFILLED", null, "unused-exit-refund"));
        } finally { exited.setStatus(1); }
        RefundApplication app = refundIntent(6641L, row);
        transactions.executeWithoutResult(tx -> {
            var context = refundService.prepareFullRefund(row.getId(), new BigDecimal("99.00"));
            refundIntents.put(app.getId(), app); refundService.bindFullRefund(context, app.getId(), null);
        });
        assertEquals("BOUND", holdMapper.selectByRegistration(row.getId()).getState());
        assertEquals("REFUND_PENDING", unitStatus(row.getId(), CH1));

        CmsRegistration bare = registration(6242L, 84L); bare.setWechatPaymentAmount(new BigDecimal("99.00"));
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(bare, topic(), activity()));
        db.update("UPDATE explore_bundle_unit SET status='REFUND_PENDING',status_reason='MERCHANT_EXIT' WHERE id=?", unitId(bare.getId(), CH1));
        assertEquals("BUNDLE_REFUND_CONSUMED", assertThrows(FreeExploreBundleException.class,
                () -> transactions.executeWithoutResult(tx -> refundService.prepareFullRefund(bare.getId(), new BigDecimal("99.00"))))
                .getBusinessCode(), "status alone cannot invent a holder's merchant-exit decision");
        assertEquals(null, holdMapper.selectByRegistration(bare.getId()));
    }

    @Test
    void realSuccessWithBrokenBookingOrLegacyPenaltyRequiresReconciliationWithoutRewritingUnits() {
        CmsRegistration row = registration(6251L, 85L); row.setWechatPaymentAmount(new BigDecimal("99.00"));
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(row, topic(), activity()));
        RefundApplication app = refundIntent(6651L, row);
        transactions.executeWithoutResult(tx -> {
            var context = refundService.prepareFullRefund(row.getId(), new BigDecimal("99.00"));
            refundIntents.put(app.getId(), app); refundService.bindFullRefund(context, app.getId(), null);
        });
        db.update("UPDATE explore_bundle_unit SET reservation_id=999999 WHERE id=?", unitId(row.getId(), CH1));
        app.setStatus(1); app.setPayoutStatus(1);
        transactions.executeWithoutResult(tx -> refundService.syncRefundOutcome(app.getId()));
        assertEquals("RECONCILIATION_REQUIRED", holdMapper.selectByRegistration(row.getId()).getState());
        assertEquals("REFUND_PENDING", unitStatus(row.getId(), CH1));
        assertEquals(1, app.getPayoutStatus(), "F3 must preserve the authoritative cash fact");

        CmsRegistration legacy = registration(6252L, 86L); legacy.setWechatPaymentAmount(new BigDecimal("99.00"));
        transactions.executeWithoutResult(tx -> {
            service.freezeOnOrderCreated(legacy, topic(), activity());
            var booking = service.reserve(86L, legacy.getId(), unitId(legacy.getId(), CH1), OFFER1,
                    new Date(now.getTime() + 2L * 3600_000L), "legacy-penalty");
            service.cancelReservation(86L, booking.getId(), false);
        });
        RefundApplication legacyApp = refundIntent(6652L, legacy); legacyApp.setStatus(1); legacyApp.setPayoutStatus(1);
        refundIntents.put(legacyApp.getId(), legacyApp);
        transactions.executeWithoutResult(tx -> refundService.syncRefundOutcome(legacyApp.getId()));
        assertEquals("RECONCILIATION_REQUIRED", holdMapper.selectByRegistration(legacy.getId()).getState());
        assertEquals("FORFEITED", unitStatus(legacy.getId(), CH1), "external success is not penalty waiver authority");
    }

    @Test
    void penaltyVersusRefundPreparationHasOneWinnerInRealUnitTransactions() throws Exception {
        CmsRegistration row = registration(6301L, 91L); row.setWechatPaymentAmount(new BigDecimal("99.00"));
        transactions.executeWithoutResult(tx -> service.freezeOnOrderCreated(row, topic(), activity()));
        var booking = transactions.execute(tx -> service.reserve(91L, 6301L, unitId(6301L, CH1), OFFER1,
                new Date(now.getTime() + 2L * 3600_000L), "refund-penalty-race"));
        RefundApplication app = refundIntent(6631L, row); refundIntents.put(app.getId(), app);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var refund = pool.submit(() -> {
                if (!gate.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                try {
                    transactions.executeWithoutResult(tx -> {
                        var context = refundService.prepareFullRefund(6301L, new BigDecimal("99.00"));
                        refundService.bindFullRefund(context, app.getId(), null);
                    });
                    return "FROZEN";
                } catch (FreeExploreBundleException e) { assertEquals("BUNDLE_REFUND_CONSUMED", e.getBusinessCode()); return "PENALTY"; }
            });
            var penalty = pool.submit(() -> {
                if (!gate.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                try { transactions.executeWithoutResult(tx -> service.cancelReservation(91L, booking.getId(), false)); }
                catch (FreeExploreBundleException e) { assertEquals("BUNDLE_REFUND_PENDING", e.getBusinessCode()); }
                return true;
            });
            gate.countDown();
            String winner = refund.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(penalty.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("FROZEN".equals(winner) ? "REFUND_PENDING" : "FORFEITED", unitStatus(6301L, CH1));
            assertEquals("FROZEN".equals(winner) ? 1 : 0,
                    count("SELECT COUNT(*) FROM explore_bundle_refund_hold WHERE registration_id=6301"));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)); }
    }

    private RefundApplication refundIntent(Long id, CmsRegistration source) {
        RefundApplication app = new RefundApplication(); app.setId(id); app.setRefundNo("RF" + source.getId() + "-" + id);
        app.setSourceType("registration"); app.setSourceId(source.getId()); app.setMemberId(source.getMemberId());
        app.setRefundAmount(new BigDecimal("99.00")); app.setPaidAmount(new BigDecimal("99.00"));
        app.setStatus(0); app.setPayoutStatus(5); app.setDelFlag(0); return app;
    }

    // ---------------------------------------------------------------- fixtures

    private FreeExploreBundleServiceImpl newService() {
        chapters.add(chapter(CH1, "第一章"));
        chapters.add(chapter(CH2, "第二章"));
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        when(chapterMapper.selectRequiredByTopic(TOPIC)).thenReturn(chapters);
        MerchantChapterOfferMapper offerMapper = mock(MerchantChapterOfferMapper.class);
        List<MerchantChapterOffer> offers = Arrays.asList(offer(OFFER1, CH1), offer(OFFER2, CH2));
        when(offerMapper.selectByTopic(TOPIC)).thenReturn(offers);
        when(offerMapper.selectById(OFFER1)).thenReturn(offers.get(0));
        when(offerMapper.selectById(OFFER2)).thenReturn(offers.get(1));
        SettlementRuleMapper rules = mock(SettlementRuleMapper.class);
        when(rules.selectActiveRule(eq(0L), any())).thenReturn(null);
        when(registrationMapper.selectCmsRegistrationById(anyLong()))
                .thenAnswer(inv -> registrations.get(inv.<Long>getArgument(0)));
        FreeExploreBundleServiceImpl impl = new FreeExploreBundleServiceImpl();
        ReflectionTestUtils.setField(impl, "bundleMapper", bundleMapper);
        ReflectionTestUtils.setField(impl, "bundleRefundMapper", mock(com.chengyinhub.business.mapper.ExploreBundleRefundMapper.class));
        ReflectionTestUtils.setField(impl, "redemptionMapper", mock(com.chengyinhub.business.mapper.RedemptionMapper.class));
        ReflectionTestUtils.setField(impl, "registrationMapper", registrationMapper);
        ReflectionTestUtils.setField(impl, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(impl, "chapterMapper", chapterMapper);
        ReflectionTestUtils.setField(impl, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(impl, "settlementRuleMapper", rules);
        ReflectionTestUtils.setField(impl, "switches", new FreeExploreBundleSwitches(true, true, true));
        impl.clock = () -> now;
        return impl;
    }

    private CmsRegistration registration(Long id, Long memberId) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(memberId);
        r.setPaymentStatus(2);
        r.setRegistrationStatus(2);
        r.setPayableAmount(new BigDecimal("99.00"));
        registrations.put(id, r);
        return r;
    }

    private CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("自由探索套票");
        t.setProductType(ProductType.FREE_EXPLORE);
        return t;
    }

    private CmsActivity activity() {
        CmsActivity a = new CmsActivity();
        a.setId(60L);
        a.setName("探索日");
        a.setEndDate(new Date(now.getTime() + 30L * 24 * 3600_000L));
        return a;
    }

    private static CmsTopicChapter chapter(Long id, String name) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id);
        c.setTopicId(TOPIC);
        c.setName(name);
        c.setRequired(1);
        c.setTermsMode("REVSHARE");
        return c;
    }

    private MerchantChapterOffer offer(Long id, Long chapterId) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(id);
        o.setTopicId(TOPIC);
        o.setChapterId(chapterId);
        o.setMerchantId(MERCHANT);
        o.setStatus(1);
        o.setTermsMode("REVSHARE");
        o.setPerHeadFee(new BigDecimal("15.00"));
        o.setQuotaTotal(0);
        return o;
    }

    private static SettlementClaim claim() {
        SettlementClaim c = new SettlementClaim();
        c.setCommercialContextType("ACTIVITY");
        c.setCommercialContextId(1L);
        c.setFulfillmentType("REDEMPTION");
        c.setFulfillmentId(9L);
        c.setBeneficiaryRole("MERCHANT");
        c.setBeneficiaryId(5L);
        c.setServiceUnit("CHAPTER_SUPPLY");
        c.setSettlementRoute("NONE");
        c.setSourceContractType("MERCHANT_CHAPTER_OFFER");
        c.setSourceContractId(3L);
        c.setStatus("ACTIVE");
        c.setCreateTime(new Date());
        return c;
    }

    private String unitStatus(Long registrationId, Long chapterId) {
        return db.queryForObject("SELECT status FROM explore_bundle_unit WHERE registration_id=? AND chapter_id=?",
                String.class, registrationId, chapterId);
    }

    private Long unitId(Long registrationId, Long chapterId) {
        for (ExploreBundleUnit unit : bundleMapper.selectUnitsByRegistration(registrationId)) {
            if (chapterId.equals(unit.getChapterId())) return unit.getId();
        }
        throw new IllegalStateException("unit missing");
    }

    private int count(String sql) {
        return db.queryForObject(sql, Integer.class);
    }

    /** 按仓库执行器同口径:去掉整行注释后按语句切分(单引号内的分号不切),逐条执行,末句是 scalar-true 断言。 */
    private void apply(String relative) throws Exception {
        List<String> statements = split(read(relative));
        // 迁移用临时表做 guard ⇒ 必须同一连接执行(与真执行器一致)。
        SingleConnectionDataSource one = new SingleConnectionDataSource(databaseUrl, user, password, true);
        try {
            JdbcTemplate session = new JdbcTemplate(one);
            for (int i = 0; i < statements.size() - 1; i++) session.execute(statements.get(i));
            assertEquals(Integer.valueOf(1), session.queryForObject(statements.get(statements.size() - 1), Integer.class),
                    "迁移末尾断言未通过:" + relative);
        } finally {
            one.destroy();
        }
    }

    private static List<String> split(String text) {
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!line.trim().startsWith("--")) body.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        String s = body.toString();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') quoted = !quoted;   // '' 转义 = 连续两次翻转,状态不变
            if (ch == ';' && !quoted) {
                if (!current.toString().trim().isEmpty()) out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (!current.toString().trim().isEmpty()) out.add(current.toString().trim());
        return out;
    }

    private static String createTable(String sql, String table) {
        Matcher m = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\) ENGINE=[^;]*;", Pattern.DOTALL).matcher(sql);
        if (!m.find()) throw new IllegalStateException("base schema 里找不到 " + table);
        return m.group().replaceAll(";$", "");
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(Path.of(relative)), StandardCharsets.UTF_8);
    }
}
