package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantPayable;
import com.chengyinhub.business.domain.MerchantPayableBatch;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.MerchantPayableBatchMapper;
import com.chengyinhub.business.mapper.MerchantPayableMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.mapper.SettlementClaimMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC2 FXE(O-07 + F3 Phase 2 设计)真库钉桩:**只钉 master 现状,不改任何产品代码**。
 *
 * <p>用真 DDL(base_schema + 两条既有迁移)、真 MerchantPayableMapper / MerchantPayableBatchMapper /
 * SettlementClaimMapper 与真 MerchantPayableServiceImpl,把 FXE.md §2 列出的资金链断点在 MySQL 上复现,
 * 并逐条执行 {@code docs/delivery/doc2-1023/FXE-reconciliation.sql}:每条必须是只读 SELECT、必须在真表上跑通、
 * 关键条目必须命中这里造出的现状不一致。
 *
 * <p>⚠️ 这些断言描述的是「今天的行为」。Phase 2 修复落地时,对应用例应当**按设计翻转**(而不是删除),
 * 让修复本身有回归证据。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FundsChainO07PinMySqlIT {
    private static final String RECON = "../docs/delivery/doc2-1023/FXE-reconciliation.sql";
    private static final String CLUB_DIRECT = "sql/migration_club_direct_merchant_receivable_20261004.sql";
    private static final String BUNDLE = "sql/migration_doc2_f3_bundle_ledger_20261022.sql";
    private static final String[] BASE_TABLES = {
        "settlement_claim", "merchant_payable", "merchant_payable_batch", "redemption", "cms_registration",
        "refund_application", "merchant_clawback_application", "merchant_chapter_offer", "oms_order",
        "ums_member_points_detail",
    };
    private static final BigDecimal FEE = new BigDecimal("15.00");

    private String database;
    private String databaseUrl;
    private String user;
    private String password;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private MerchantPayableMapper payableMapper;
    private MerchantPayableBatchMapper batchMapper;
    private SettlementClaimMapper claimMapper;
    private final RedemptionMapper redemptionMapper = mock(RedemptionMapper.class);
    private final MerchantChapterOfferMapper offerMapper = mock(MerchantChapterOfferMapper.class);
    private final IProductRoleService productRoleService = mock(IProductRoleService.class);
    private MerchantPayableServiceImpl service;
    private Map<String, String> queries;

    @BeforeAll
    void createScratchSchema() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("fxe_o07");
        if (!config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")) {
            throw new IllegalStateException("loopback task MySQL only");
        }
        database = "chengyin_fxe_o07_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
        databaseUrl = config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?");
        user = config.user();
        password = config.password();
        DriverManagerDataSource source = new DriverManagerDataSource(databaseUrl, user, password);
        db = new JdbcTemplate(source);
        String base = read("../sql/base_schema_20260818.sql");
        for (String table : BASE_TABLES) db.execute(createTable(base, table));
        // club_direct 迁移第一句给 merchant_chapter_offer 加 payer_club_id,第二句建直付应收表。
        for (String statement : split(read(CLUB_DIRECT))) db.execute(statement);
        apply(BUNDLE);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/MerchantPayableMapper.xml"),
                new ClassPathResource("mapper/business/MerchantPayableBatchMapper.xml"),
                new ClassPathResource("mapper/business/SettlementClaimMapper.xml"));
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        payableMapper = template.getMapper(MerchantPayableMapper.class);
        batchMapper = template.getMapper(MerchantPayableBatchMapper.class);
        claimMapper = template.getMapper(SettlementClaimMapper.class);

        service = new MerchantPayableServiceImpl();
        ReflectionTestUtils.setField(service, "payableMapper", payableMapper);
        ReflectionTestUtils.setField(service, "batchMapper", batchMapper);
        ReflectionTestUtils.setField(service, "redemptionMapper", redemptionMapper);
        ReflectionTestUtils.setField(service, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        ReflectionTestUtils.setField(service, "settlementClaimService", new SettlementClaimServiceImpl(claimMapper));
        ReflectionTestUtils.setField(service, "clubDirectReceivableService", mock(ClubDirectMerchantReceivableService.class));
        when(productRoleService.isPlatformOwned(anyLong())).thenReturn(true);

        queries = parseQueries(read(RECON));
    }

    @AfterAll
    void dropOnlyScratchDatabase() {
        if (database != null && admin != null) {
            admin.execute("DROP DATABASE " + database);
            assertEquals(0, admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        }
    }

    /** 对账文件:每条都只读,且在真表上跑得通(列名写错、表名写错会在这里红)。 */
    @Test
    void everyReconciliationQueryIsReadOnlyAndRunsOnRealSchema() {
        assertEquals(18, queries.size(), "FXE-reconciliation.sql 条目数与 FXE.md §3 不一致");
        Pattern forbidden = Pattern.compile(
                "\\b(INSERT|UPDATE|DELETE|REPLACE|MERGE|ALTER|DROP|CREATE|TRUNCATE|GRANT|LOCK|CALL|SET)\\b|FOR\\s+UPDATE"
                        + "|FOR\\s+SHARE|INTO\\s+OUTFILE",
                Pattern.CASE_INSENSITIVE);
        for (Map.Entry<String, String> q : queries.entrySet()) {
            String sql = q.getValue();
            String head = sql.trim().toUpperCase(Locale.ROOT);
            assertTrue(head.startsWith("SELECT") || head.startsWith("WITH"), q.getKey() + " 必须是 SELECT/WITH");
            assertFalse(forbidden.matcher(sql).find(), q.getKey() + " 含写/锁关键字");
            assertFalse(sql.contains(";"), q.getKey() + " 只能是单条语句");
            db.queryForList(sql);   // 能在真 DDL 上执行
        }
    }

    /**
     * O-07 断点 ①:claim 在「主题须自营」闸之前创建 ⇒ 非自营主题的计酬核销留下 CHAPTER_OFFER claim 却没有应付。
     * R02(有 claim 无应付)与 R06(生效计酬核销无应付)都必须命中。
     */
    @Test
    void o07ClaimIsCreatedBeforeSelfOwnedGateSoNonSelfOwnedRevshareHasClaimButNoPayable() {
        long redemptionId = 1101L;
        long merchant = 9101L;
        long offer = 8101L;
        long topic = 7101L;
        seedOffer(offer, topic, 71011L, merchant, "REVSHARE", FEE);
        seedRedemption(redemptionId, 61101L, topic, 71011L, merchant, offer, "ACTIVE");
        when(productRoleService.isPlatformOwned(topic)).thenReturn(false);

        service.accrueOnRedemption(redemptionId);

        Long claimId = claimIdFor(redemptionId);
        assertEquals("CHAPTER_OFFER", db.queryForObject(
                "SELECT settlement_route FROM settlement_claim WHERE id=?", String.class, claimId));
        assertEquals(0, count("SELECT COUNT(*) FROM merchant_payable WHERE redemption_id=" + redemptionId));
        assertTrue(hits("R02", "claim_id", claimId), "R02 必须报出有 claim 无应付");
        assertTrue(hits("R06", "redemption_id", redemptionId), "R06 必须报出生效计酬核销无应付");
    }

    /**
     * O-07 断点 ②:merchant_payable 只能表达「某次核销的计提/冲正」——redemption_id NOT NULL、
     * entry_type 只许 ACCRUAL/REVERSAL、amount ≥ 0。退款调整、套票罚没/换店、倒扣、结转等非核销事件都写不进来。
     */
    @Test
    void o07PayableShapeCannotRepresentNonRedemptionEvents() {
        Long claimId = insertClaim(1201L, 9201L, "CHAPTER_OFFER");
        assertThrows(DataAccessException.class, () -> db.update(
                "INSERT INTO merchant_payable (claim_id, event_key, redemption_id, topic_id, chapter_id,"
                        + " merchant_member_id, per_head_fee_snapshot, amount, entry_type, idempotency_key, create_time)"
                        + " VALUES (?, 'REFUND:1', NULL, 1, 1, 9201, 15, 15, 'REVERSAL', 'refund:1', NOW())", claimId),
                "redemption_id NOT NULL:没有核销就没有应付行");
        assertThrows(DataAccessException.class, () -> db.update(
                "INSERT INTO merchant_payable (claim_id, event_key, redemption_id, topic_id, chapter_id,"
                        + " merchant_member_id, per_head_fee_snapshot, amount, entry_type, idempotency_key, create_time)"
                        + " VALUES (?, 'ADJ:1', 1201, 1, 1, 9201, 15, 5, 'ADJUSTMENT', 'adj:1', NOW())", claimId),
                "ck_payable_entry_type 只许 ACCRUAL/REVERSAL");
        assertThrows(DataAccessException.class, () -> db.update(
                "INSERT INTO merchant_payable (claim_id, event_key, redemption_id, topic_id, chapter_id,"
                        + " merchant_member_id, per_head_fee_snapshot, amount, entry_type, idempotency_key, create_time)"
                        + " VALUES (?, 'NEG:1', 1201, 1, 1, 9201, 15, -5, 'ACCRUAL', 'neg:1', NOW())", claimId),
                "ck_payable_amount:金额只能是非负绝对值");
        // settlement_claim 的 7 列经济键里没有单位/事件维度:同一核销同一商家只能有一条 claim。
        assertThrows(DataAccessException.class, () -> insertClaim(1201L, 9201L, "NONE"),
                "uk_settlement_claim_key 不含 unit_code / 事件维度");
    }

    /**
     * 断点 ③:撤销核销只追加 REVERSAL,claim 永远停在 ACTIVE(R04);存量 claim_id=NULL 的计提被冲正时,
     * 冲正行也是 NULL(R01)。
     */
    @Test
    void reversalLeavesClaimActiveAndCopiesLegacyNullClaimId() {
        long redemptionId = 1301L;
        long merchant = 9301L;
        long offer = 8301L;
        seedOffer(offer, 7301L, 73011L, merchant, "REVSHARE", FEE);
        seedRedemption(redemptionId, 61301L, 7301L, 73011L, merchant, offer, "ACTIVE");
        service.accrueOnRedemption(redemptionId);
        db.update("UPDATE redemption SET status='REVERSED', reversed_at=NOW() WHERE id=?", redemptionId);
        service.reverseOnRedemption(redemptionId);

        Long claimId = claimIdFor(redemptionId);
        assertEquals("ACTIVE", db.queryForObject("SELECT status FROM settlement_claim WHERE id=?", String.class, claimId),
                "现状:全仓没有把 claim 改离 ACTIVE 的写路径");
        assertEquals(BigDecimal.ZERO.setScale(2), net("redemption_id=" + redemptionId));
        assertTrue(hits("R04", "claim_id", claimId), "R04 必须报出已撤销核销的 ACTIVE claim");
        assertFalse(hits("R05", "redemption_id", redemptionId), "计提/冲正成对,R05 不应误报");

        long legacy = 1302L;
        seedRedemption(legacy, 61302L, 7301L, 73011L, merchant, offer, "REVERSED");
        MerchantPayable old = payable(null, legacy, merchant, MerchantPayable.ENTRY_ACCRUAL, "accrual:" + legacy, new Date());
        old.setEventKey(null);
        payableMapper.insertPayable(old);
        service.reverseOnRedemption(legacy);
        MerchantPayable rev = payableMapper.selectByIdempotencyKey("reversal:" + legacy);
        assertNull(rev.getClaimId(), "现状:冲正复制被冲那笔的 claim_id,存量 NULL 被原样带下去");
        assertTrue(hits("R01", "id", old.getId()) && hits("R01", "id", rev.getId()), "R01 必须报出两条无 claim 行");
    }

    /** 断点 ④:已退款报名仍挂着正净额应付(退款审批不触达 merchant_payable)。R07 必须命中。 */
    @Test
    void refundedRegistrationStillCarriesPositiveMerchantPayable() {
        long registrationId = 61401L;
        long redemptionId = 1401L;
        long merchant = 9401L;
        long offer = 8401L;
        seedRegistration(registrationId, 4);
        seedOffer(offer, 7401L, 74011L, merchant, "REVSHARE", FEE);
        seedRedemption(redemptionId, registrationId, 7401L, 74011L, merchant, offer, "ACTIVE");
        service.accrueOnRedemption(redemptionId);
        db.update("INSERT INTO refund_application (refund_no, source_type, source_id, member_id, paid_amount,"
                + " refund_amount, status, reviewed_at) VALUES ('RFXE1401', 'registration', ?, 1, 30, 30, 1, NOW())",
                registrationId);

        assertTrue(hits("R07", "registration_id", registrationId), "R07 必须报出退款后仍欠商家的报名");
    }

    /**
     * 断点 ⑤:负额批次卡死且不结转。2026-01 计提并打款 → 2026-02 只有冲正(-15)→ 2026-03 计提照常打款;
     * 2026-02 那笔负额永远停在 PENDING,没有任何机制把它抵进 2026-03。R10 必须命中。
     */
    @Test
    void negativeBatchIsStuckAndNeverCarriedForward() throws Exception {
        long merchant = 9501L;
        Long claimA = insertClaim(1501L, merchant, "CHAPTER_OFFER");
        payableMapper.insertPayable(payable(claimA, 1501L, merchant, MerchantPayable.ENTRY_ACCRUAL, "accrual:1501", at("2026-01-15")));
        payBatch(service.generateMonthlyBatches("2026-01"), merchant);

        MerchantPayable rev = payable(claimA, 1501L, merchant, MerchantPayable.ENTRY_REVERSAL, "reversal:1501", at("2026-02-10"));
        rev.setEventKey("REDEMPTION_REVERSED:1501");
        payableMapper.insertPayable(rev);
        MerchantPayableBatch feb = only(service.generateMonthlyBatches("2026-02"), merchant);
        assertEquals(0, new BigDecimal("-15.00").compareTo(feb.getAmountTotal()));
        assertThrows(IllegalStateException.class, () -> service.confirmBatch(feb.getId(), "ops-a"),
                "现状:非正额批次不能确认,也没有结转路径");

        Long claimC = insertClaim(1502L, merchant, "CHAPTER_OFFER");
        payableMapper.insertPayable(payable(claimC, 1502L, merchant, MerchantPayable.ENTRY_ACCRUAL, "accrual:1502", at("2026-03-05")));
        MerchantPayableBatch mar = payBatch(service.generateMonthlyBatches("2026-03"), merchant);
        assertEquals(0, FEE.compareTo(mar.getAmountTotal()), "现状:3 月全额打款,2 月的 -15 没被抵扣");

        assertTrue(hits("R10", "batch_id", feb.getId()), "R10 必须报出卡死的负额批次");
        assertEquals(1L, ((Number) row("R10", "batch_id", feb.getId()).get("later_paid_batches")).longValue());
    }

    /**
     * 断点 ⑥:OFFSET_BATCH 倒扣直接改 amount_total ⇒ 冻结额 ≠ 台账净额(四账对账维度三会把它报成差异);
     * R08 先扣掉已执行倒扣再比,合法倒扣不误报;批次生成后再落进该月的行才是真漂移(R08)/孤儿(R09)。
     */
    @Test
    void clawbackOffsetAndLateRowsAreSeparatedByReconciliation() throws Exception {
        long merchant = 9601L;
        Long claim = insertClaim(1601L, merchant, "CHAPTER_OFFER");
        payableMapper.insertPayable(payable(claim, 1601L, merchant, MerchantPayable.ENTRY_ACCRUAL, "accrual:1601", at("2025-11-20")));
        MerchantPayableBatch nov = only(service.generateMonthlyBatches("2025-11"), merchant);
        assertEquals(1, batchMapper.deductForClawback(nov.getId(), new BigDecimal("5.00")));
        db.update("INSERT INTO merchant_clawback_application (merchant_member_id, reason, amount, settle_path,"
                + " target_batch_id, status, initiated_by, confirmed_by, create_time)"
                + " VALUES (?, 'fxe pin', 5.00, 'OFFSET_BATCH', ?, 'EXECUTED', 'ops-a', 'ops-b', NOW())", merchant, nov.getId());
        BigDecimal frozen = batchMapper.selectById(nov.getId()).getAmountTotal();
        assertNotEquals(0, frozen.compareTo(service.netPayable(merchant, "2025-11")),
                "现状:倒扣后冻结额与台账净额不再相等(FourLedgerReconcile 维度三会报差异)");
        assertFalse(hits("R08", "batch_id", nov.getId()), "R08 计入已执行倒扣后不应误报");

        Long late = insertClaim(1602L, merchant, "CHAPTER_OFFER");
        payableMapper.insertPayable(payable(late, 1602L, merchant, MerchantPayable.ENTRY_ACCRUAL, "accrual:1602", at("2025-11-30")));
        assertTrue(hits("R08", "batch_id", nov.getId()), "批次生成后再落进该月的行 = 真漂移");

        long orphanMerchant = 9602L;
        Long orphanClaim = insertClaim(1603L, orphanMerchant, "CHAPTER_OFFER");
        payableMapper.insertPayable(payable(orphanClaim, 1603L, orphanMerchant, MerchantPayable.ENTRY_ACCRUAL,
                "accrual:1603", at("2025-11-30")));
        assertTrue(hits("R09", "merchant_member_id", orphanMerchant), "该月已开批而本商家无批次 = 孤儿应付");
    }

    // ------------------------------------------------------------------ helpers

    private MerchantPayableBatch payBatch(List<MerchantPayableBatch> created, long merchant) {
        MerchantPayableBatch b = only(created, merchant);
        assertTrue(service.confirmBatch(b.getId(), "ops-a"));
        assertTrue(service.markBatchPaid(b.getId(), "ops-b", "VOUCHER-" + b.getId()));
        return b;
    }

    private static MerchantPayableBatch only(List<MerchantPayableBatch> created, long merchant) {
        for (MerchantPayableBatch b : created) {
            if (Long.valueOf(merchant).equals(b.getMerchantMemberId())) return b;
        }
        throw new IllegalStateException("批次未生成 merchant=" + merchant);
    }

    private MerchantPayable payable(Long claimId, long redemptionId, long merchant, String entryType, String key, Date at) {
        MerchantPayable p = new MerchantPayable();
        p.setClaimId(claimId);
        p.setEventKey("ORIGIN");
        p.setRedemptionId(redemptionId);
        p.setTopicId(1L);
        p.setChapterId(1L);
        p.setMerchantMemberId(merchant);
        p.setPerHeadFeeSnapshot(FEE);
        p.setAmount(FEE);
        p.setEntryType(entryType);
        p.setIdempotencyKey(key);
        p.setCreateTime(at);
        return p;
    }

    private Long insertClaim(long redemptionId, long merchant, String route) {
        db.update("INSERT INTO settlement_claim (commercial_context_type, commercial_context_id, fulfillment_type,"
                + " fulfillment_id, beneficiary_role, beneficiary_id, service_unit, settlement_route,"
                + " source_contract_type, source_contract_id, status, create_time)"
                + " VALUES ('TOPIC_EDITION', 1, 'REDEMPTION', ?, 'MERCHANT', ?, 'CHAPTER_SUPPLY', ?,"
                + " 'MERCHANT_CHAPTER_OFFER', 1, 'ACTIVE', NOW())", redemptionId, merchant, route);
        return claimIdFor(redemptionId);
    }

    private Long claimIdFor(long redemptionId) {
        return db.queryForObject("SELECT id FROM settlement_claim WHERE fulfillment_type='REDEMPTION'"
                + " AND fulfillment_id=?", Long.class, redemptionId);
    }

    private void seedOffer(long id, long topic, long chapter, long merchant, String terms, BigDecimal fee) {
        db.update("INSERT INTO merchant_chapter_offer (id, topic_id, chapter_id, merchant_id, terms_mode, per_head_fee,"
                + " quota_total, quota_used, status, create_time) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 1, NOW())",
                id, topic, chapter, merchant, terms, fee);
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(id);
        o.setTopicId(topic);
        o.setChapterId(chapter);
        o.setMerchantId(merchant);
        o.setTermsMode(terms);
        o.setPerHeadFee(fee);
        when(offerMapper.selectById(id)).thenReturn(o);
    }

    private void seedRedemption(long id, long registration, long topic, long chapter, long merchant, long offer,
                                String status) {
        db.update("INSERT INTO redemption (id, registration_id, chapter_id, topic_id, merchant_id, offer_id,"
                + " operator_id, status, idempotency_key, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                id, registration, chapter, topic, merchant, offer, merchant, status, "redeem:fxe:" + id);
        Redemption r = new Redemption();
        r.setId(id);
        r.setRegistrationId(registration);
        r.setChapterId(chapter);
        r.setTopicId(topic);
        r.setMerchantId(merchant);
        r.setOfferId(offer);
        r.setStatus(status);
        when(redemptionMapper.selectById(id)).thenReturn(r);
    }

    private void seedRegistration(long id, int paymentStatus) {
        db.update("INSERT INTO cms_registration (id, registration_no, owner_type, owner_id, ticket_id, ticket_price,"
                + " member_id, original_amount, total_amount, payable_amount, wechat_payment_amount, payment_status,"
                + " registration_status, verification_status, purchase_kind, pay_expire_time, create_time)"
                + " VALUES (?, ?, 2, 1, 1, 30, 1, 30, 30, 30, 30, ?, 3, 1, 3, NOW(), NOW())",
                id, "RFXE" + id, paymentStatus);
    }

    private BigDecimal net(String where) {
        return db.queryForObject("SELECT COALESCE(SUM(CASE entry_type WHEN 'ACCRUAL' THEN amount ELSE -amount END), 0)"
                + " FROM merchant_payable WHERE " + where, BigDecimal.class);
    }

    private boolean hits(String query, String column, Object value) {
        return row(query, column, value) != null;
    }

    private Map<String, Object> row(String query, String column, Object value) {
        String sql = queries.get(query);
        if (sql == null) throw new IllegalStateException("对账文件缺少 " + query);
        for (Map<String, Object> row : db.queryForList(sql)) {
            Object v = row.get(column);
            if (v instanceof Number && value instanceof Number
                    && ((Number) v).longValue() == ((Number) value).longValue()) return row;
            if (v != null && v.equals(value)) return row;
        }
        return null;
    }

    private int count(String sql) {
        return db.queryForObject(sql, Integer.class);
    }

    private static Date at(String day) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(day + " 12:00:00");
    }

    /** `-- @query Rxx` 分段;段内去掉整行注释与结尾分号,剩下的就是要执行的那一条 SQL。 */
    static Map<String, String> parseQueries(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            Matcher m = Pattern.compile("^--\\s*@query\\s+(R\\d+)\\s*$").matcher(line.trim());
            if (m.matches()) {
                if (current != null) out.put(current, finish(body));
                current = m.group(1);
                body.setLength(0);
            } else if (current != null && !line.trim().startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        if (current != null) out.put(current, finish(body));
        return out;
    }

    private static String finish(StringBuilder body) {
        String sql = body.toString().trim();
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1).trim() : sql;
    }

    private void apply(String relative) throws Exception {
        List<String> statements = split(read(relative));
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
            if (ch == '\'') quoted = !quoted;
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
        // 表注释里可能带分号(merchant_payable 就有),所以截到 ENGINE 那一整行行尾,而不是第一个分号。
        Matcher m = Pattern.compile("CREATE TABLE `" + table + "` \\(.*?\\n\\) ENGINE=[^\\n]*", Pattern.DOTALL).matcher(sql);
        if (!m.find()) throw new IllegalStateException("base schema 里找不到 " + table);
        return m.group().trim().replaceAll(";$", "");
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(Path.of(relative)), StandardCharsets.UTF_8);
    }
}
