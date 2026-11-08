package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.MarketingOrderAdjustment;
import com.chengyinhub.business.domain.MarketingPromotionRedemption;
import com.chengyinhub.business.domain.MarketingPromotionRefundFact;
import com.chengyinhub.business.mapper.MarketingOrderAdjustmentMapper;
import com.chengyinhub.business.mapper.MarketingPromotionRedemptionMapper;
import com.chengyinhub.business.mapper.MarketingPromotionRefundFactMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 促销退款累计在真实 InnoDB 上的口径。
 *
 * <p>为什么必须是真库：单测里 mock 的 mapper 永远不会撞唯一键，也不会让同一
 * idempotency_key 的重投真的收敛成一行；而线上决定"这单到底退完没有"的，
 * 正是这两条约束加一次累计求和。
 *
 * <p>覆盖的负例：
 * <ul>
 *   <li>被退的是同 parent_order_no 的子单时，归并到写促销快照的主单，且不跨会员/跨 parent；</li>
 *   <li>两笔各 50% 的退款累计到 100% 才撤销，撤销只置状态不删行；</li>
 *   <li>同一退款事实并发重投只落一行、不双计；</li>
 *   <li>不同退款申请、不同订单的相同来源 id 互不碰撞；同一 (order, refund_application) 重复插入被唯一键拒绝；</li>
 *   <li>同一订单两个 total_fen 会被 distinctTotalFen 同时读出（调用方必须拒绝累计）；</li>
 *   <li>一单一份成交快照；同一促销事实只能领一次，但不同促销可以各自作用同一订单。</li>
 * </ul>
 *
 * <p>一类一库，跑完 DROP DATABASE 并回读不存在。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketingPromotionRefundLedgerMySqlIT {

    private final String suite = "m4refund_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static final int REGISTRATION = 1;
    private static final long MEMBER = 7001L;
    private static final long OTHER_MEMBER = 7002L;
    private static final long PROMO_ORDER = 5001L;
    private static final long SIBLING_ORDER = 5002L;

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private MarketingPromotionRefundFactMapper refundFactMapper;
    private MarketingOrderAdjustmentMapper adjustmentMapper;
    private MarketingPromotionRedemptionMapper redemptionMapper;

    private long promotionId;

    @BeforeAll
    void schemaAndRealMappers() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()),
                "只许连一次性 scratch MySQL");
        source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(suite),
                db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("SELECT VERSION()", String.class)), "需要 Oracle MySQL >= 8");

        P0AcceptanceSchema.migrations(source, "migration_marketing_promotion_20261108.sql");

        // 既有报名单只读事实的最小形状：只用于 parent_order_no / member_id 归并，不复制任何业务列。
        db.execute("CREATE TABLE IF NOT EXISTS cms_registration ("
                + "id BIGINT NOT NULL, member_id BIGINT NOT NULL, parent_order_no VARCHAR(40) NULL,"
                + "PRIMARY KEY (id), KEY idx_it_reg_parent (parent_order_no))");

        var session = P0AcceptanceSchema.sql(source, "MarketingPromotionRefundFactMapper",
                "MarketingOrderAdjustmentMapper", "MarketingPromotionRedemptionMapper");
        refundFactMapper = session.getMapper(MarketingPromotionRefundFactMapper.class);
        adjustmentMapper = session.getMapper(MarketingOrderAdjustmentMapper.class);
        redemptionMapper = session.getMapper(MarketingPromotionRedemptionMapper.class);
    }

    @AfterAll
    void cleanup() {
        if (db != null) {
            assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_m4refund_[a-z0-9_]+"));
            P0AcceptanceSchema.drop(db, suite);
        }
    }

    @BeforeEach
    void reseedPromotionAndRegistrations() {
        for (String table : List.of("marketing_promotion_refund_fact", "marketing_order_adjustment",
                "marketing_promotion_redemption", "cms_registration", "marketing_promotion")) {
            db.update("DELETE FROM " + table);
        }
        // 报名单事实：主单无 parent；子单同 parent；另一个会员同 parent；另一 parent 不得归并。
        db.update("INSERT INTO cms_registration (id,member_id,parent_order_no) VALUES (?,?,NULL)",
                PROMO_ORDER, MEMBER);
        db.update("INSERT INTO cms_registration (id,member_id,parent_order_no) VALUES (?,?,'PO-1')",
                SIBLING_ORDER, MEMBER);
        db.update("INSERT INTO cms_registration (id,member_id,parent_order_no) VALUES (?,?,'PO-1')",
                5003L, OTHER_MEMBER);
        db.update("INSERT INTO cms_registration (id,member_id,parent_order_no) VALUES (?,?,'PO-2')",
                5004L, MEMBER);
        promotionId = insertPromotion("PROMO-" + UUID.randomUUID().toString().substring(0, 8));
    }

    // ------------------------------------------------------------------ 归并

    @Test
    void siblingRegistrationResolvesToTheParentPromotionOrder() {
        insertAdjustment(PROMO_ORDER, MEMBER, promotionId);

        assertEquals(List.of(PROMO_ORDER),
                refundFactMapper.resolvePromoOrderIds(REGISTRATION, PROMO_ORDER),
                "被退主单必须直接解析到自己的促销快照");
        assertEquals(List.of(PROMO_ORDER),
                refundFactMapper.resolvePromoOrderIds(REGISTRATION, SIBLING_ORDER),
                "同 parent_order_no 的子单退款必须归并到主单，否则永远不会撤销");
        assertTrue(refundFactMapper.resolvePromoOrderIds(REGISTRATION, 5003L).isEmpty(),
                "同 parent 但不同会员的订单不得合并");
        assertTrue(refundFactMapper.resolvePromoOrderIds(REGISTRATION, 5004L).isEmpty(),
                "不同 parent_order_no 不得合并");
    }

    // ------------------------------------------------------------------ 累计到全额

    @Test
    void partialRefundsAccumulateToFullBeforeRevokeAndRevokeKeepsTheRow() {
        insertAdjustment(PROMO_ORDER, MEMBER, promotionId);
        long redemptionId = insertRedemption(promotionId, PROMO_ORDER, 9001L, "red-" + UUID.randomUUID());

        refundFactMapper.upsertRefundFact(fact(PROMO_ORDER, PROMO_ORDER, 7101L, 5000L, 10000L,
                "refund-settled:5001:7101"));
        assertEquals(5000L, refundFactMapper.sumRefundedFen(REGISTRATION, PROMO_ORDER));
        assertEquals(MarketingPromotionRedemption.STATUS_USED,
                redemptionMapper.selectByOrder(REGISTRATION, PROMO_ORDER).get(0).getStatus(),
                "只退 50% 时促销资格保持已使用");

        // 第二笔来自同 parent 的子单：累计 100% 才允许撤销。
        refundFactMapper.upsertRefundFact(fact(PROMO_ORDER, SIBLING_ORDER, 7102L, 5000L, 10000L,
                "refund-settled:5002:7102"));
        assertEquals(10000L, refundFactMapper.sumRefundedFen(REGISTRATION, PROMO_ORDER),
                "子单退款必须累计到主单");
        assertEquals(List.of(10000L), refundFactMapper.distinctTotalFen(REGISTRATION, PROMO_ORDER));

        assertEquals(1, redemptionMapper.revokeByOrder(REGISTRATION, PROMO_ORDER,
                MarketingPromotionRedemption.STATUS_REVOKED_BY_REFUND, new Date()));
        MarketingPromotionRedemption after = redemptionMapper.selectByOrder(REGISTRATION, PROMO_ORDER).get(0);
        assertEquals(redemptionId, after.getId().longValue(), "撤销只改状态，不得删行");
        assertEquals(MarketingPromotionRedemption.STATUS_REVOKED_BY_REFUND, after.getStatus());
        assertEquals(0, redemptionMapper.revokeByOrder(REGISTRATION, PROMO_ORDER,
                MarketingPromotionRedemption.STATUS_REVOKED_BY_REFUND, new Date()),
                "重复撤销是幂等的 0 行");
    }

    // ------------------------------------------------------------------ 重投幂等

    @Test
    void concurrentReplayOfTheSameRefundFactKeepsOneLedgerRow() throws Exception {
        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    refundFactMapper.upsertRefundFact(fact(PROMO_ORDER, PROMO_ORDER, 7201L,
                            3000L, 10000L, "refund-settled:5001:7201"));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_promotion_refund_fact", Integer.class),
                "同一 idempotency_key 的并发重投只能落一行");
        assertEquals(3000L, refundFactMapper.sumRefundedFen(REGISTRATION, PROMO_ORDER),
                "重投不得把累计金额翻倍");
    }

    // ------------------------------------------------------------------ 来源 id 不碰撞

    @Test
    void differentRefundApplicationsAndOrdersDoNotCollide() {
        insertAdjustment(PROMO_ORDER, MEMBER, promotionId);

        refundFactMapper.upsertRefundFact(fact(PROMO_ORDER, PROMO_ORDER, 7301L, 1000L, 10000L,
                "refund-settled:5001:7301"));
        refundFactMapper.upsertRefundFact(fact(PROMO_ORDER, SIBLING_ORDER, 7302L, 2000L, 10000L,
                "refund-settled:5002:7302"));
        assertEquals(2, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_promotion_refund_fact WHERE order_id=?",
                Integer.class, PROMO_ORDER), "不同退款申请必须各自落账");

        // 不同订单复用同一 refund_application_id：唯一键含 order_id，不应互相碰撞。
        insertAdjustment(6001L, MEMBER, promotionId);
        refundFactMapper.upsertRefundFact(fact(6001L, 6001L, 7301L, 4000L, 9000L,
                "refund-settled:6001:7301"));
        assertEquals(4000L, refundFactMapper.sumRefundedFen(REGISTRATION, 6001L),
                "独立订单的累计必须独立");

        // 同 (order_type, order_id, refund_application_id) 的第二条即使换幂等键也只落到同一行：
        // upsert 用 on duplicate key，不抛异常，但绝不能多记一笔或改写金额。
        refundFactMapper.upsertRefundFact(fact(PROMO_ORDER, PROMO_ORDER, 7301L,
                1000L, 10000L, "different-key-same-app"));
        assertEquals(2, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_promotion_refund_fact WHERE order_id=?",
                Integer.class, PROMO_ORDER), "同一退款申请的第二行必须被 uk_promo_refund_app 收敛");
        assertEquals(3000L, refundFactMapper.sumRefundedFen(REGISTRATION, PROMO_ORDER),
                "重投不得改变累计金额，实际应仍为 1000+2000");
    }

    @Test
    void twoDifferentTotalFenOnOneOrderAreBothVisibleToTheGuard() {
        insertAdjustment(PROMO_ORDER, MEMBER, promotionId);
        refundFactMapper.upsertRefundFact(fact(PROMO_ORDER, PROMO_ORDER, 7401L, 1000L, 10000L,
                "refund-settled:5001:7401"));
        refundFactMapper.upsertRefundFact(fact(PROMO_ORDER, PROMO_ORDER, 7402L, 1000L, 11000L,
                "refund-settled:5001:7402"));

        assertEquals(2, refundFactMapper.distinctTotalFen(REGISTRATION, PROMO_ORDER).size(),
                "同一订单报了不同原支付总额时，调用方必须能看到两个值并拒绝合并");
    }

    // ------------------------------------------------------------------ 一单一快照 / 一次一事实

    @Test
    void adjustmentIsOnePerOrderAndRedemptionIsOnePerPromotionFact() {
        insertAdjustment(PROMO_ORDER, MEMBER, promotionId);
        assertThrows(DataIntegrityViolationException.class,
                () -> insertAdjustment(PROMO_ORDER, MEMBER, promotionId),
                "uk_adjustment_order 必须挡住同一订单的第二份成交快照");

        insertRedemption(promotionId, PROMO_ORDER, 9001L, "red-" + UUID.randomUUID());
        assertThrows(DataIntegrityViolationException.class,
                () -> insertRedemption(promotionId, PROMO_ORDER, 9001L, "red-" + UUID.randomUUID()),
                "同一促销的同一通关事实只能领取一次");

        long secondPromotion = insertPromotion("PROMO-2-" + UUID.randomUUID().toString().substring(0, 8));
        insertRedemption(secondPromotion, PROMO_ORDER, 9001L, "red2-" + UUID.randomUUID());
        assertEquals(2, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_promotion_redemption WHERE order_id=?",
                Integer.class, PROMO_ORDER),
                "不同促销可以各自作用于同一订单，唯一键只在同一 promotion 内生效");
    }

    // ------------------------------------------------------------------ fixtures

    private long insertPromotion(String code) {
        db.update("INSERT INTO marketing_promotion"
                        + " (promo_code, trigger_kind, name, scope_type, discount_type, discount_value,"
                        + " window_hours, status, rule_version, max_uses_per_member, create_time)"
                        + " VALUES (?,1,?,0,1,5.00,24,1,1,1,NOW())",
                code, "IT promo " + code);
        return db.queryForObject("SELECT id FROM marketing_promotion WHERE promo_code=?", Long.class, code);
    }

    private void insertAdjustment(long orderId, long memberId, long promoId) {
        MarketingOrderAdjustment row = new MarketingOrderAdjustment();
        row.setOrderType(MarketingOrderAdjustment.ORDER_REGISTRATION);
        row.setOrderId(orderId);
        row.setMemberId(memberId);
        row.setOwnerType(1);
        row.setOwnerId(42L);
        row.setListAmount(new BigDecimal("100.00"));
        row.setTierDiscountAmount(BigDecimal.ZERO);
        row.setOfferDiscountAmount(BigDecimal.ZERO);
        row.setPromotionDiscountAmount(new BigDecimal("5.00"));
        row.setPayableAmount(new BigDecimal("95.00"));
        row.setAppliedJson("{\"it\":true}");
        row.setRuleVersion("it-rule-v1");
        row.setQuoteFingerprint("a".repeat(64));
        row.setCreateTime(new Date());
        adjustmentMapper.insertAdjustment(row);
    }

    private long insertRedemption(long promoId, long orderId, long sourceFactId, String idempotencyKey) {
        MarketingPromotionRedemption row = new MarketingPromotionRedemption();
        row.setPromotionId(promoId);
        row.setPromoCode("promo-" + promoId);
        row.setMemberId(MEMBER);
        row.setSourceFactType(MarketingPromotionRedemption.SOURCE_FACT_BADGE);
        row.setSourceFactId(sourceFactId);
        row.setOrderType(REGISTRATION);
        row.setOrderId(orderId);
        row.setDiscountAmount(new BigDecimal("5.00"));
        row.setRuleVersion(1);
        row.setStatus(MarketingPromotionRedemption.STATUS_USED);
        row.setIdempotencyKey(idempotencyKey);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        redemptionMapper.insertRedemption(row);
        return row.getId();
    }

    private MarketingPromotionRefundFact fact(long promoOrderId, long refundedRegistrationId,
                                              long refundApplicationId, long refundedFen,
                                              long totalFen, String idempotencyKey) {
        MarketingPromotionRefundFact row = new MarketingPromotionRefundFact();
        row.setOrderType(MarketingPromotionRefundFact.ORDER_REGISTRATION);
        row.setOrderId(promoOrderId);
        row.setRefundRegistrationId(refundedRegistrationId);
        row.setRefundApplicationId(refundApplicationId);
        row.setOutRefundNo("RF" + refundApplicationId);
        row.setRefundedFen(refundedFen);
        row.setTotalFen(totalFen);
        row.setIdempotencyKey(idempotencyKey);
        row.setCreateTime(new Date());
        return row;
    }
}
