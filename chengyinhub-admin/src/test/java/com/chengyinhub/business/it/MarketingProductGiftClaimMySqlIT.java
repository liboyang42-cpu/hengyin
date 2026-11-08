package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.GiftEntitlement;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductOrder;
import com.chengyinhub.business.domain.MarketingProductOrderLine;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 商品/礼品在真实 InnoDB 上的领取与成交边界。
 *
 * <p>单测能证明"服务里写了 CAS"，但证明不了并发时**只有一个**赢家、唯一键真的挡住第二行、
 * CHECK 真的拒绝半截状态。这里把真正的迁移建在独立库里，用真 mapper 打真表。
 *
 * <p>覆盖的负例：
 * <ul>
 *   <li>同一礼品两个领取者并发抢最后一次 → 只有一方 claimed，另一方 0 行；</li>
 *   <li>过期的领取码即使 status 仍是 0 也不能领，过期后不能再被领取；</li>
 *   <li>同一会员同一 requestId 的两笔下单请求 → 唯一键收敛成一行；</li>
 *   <li>一份已付订单只能对应一份礼品；领取后绑定原 registration 只发生一次；</li>
 *   <li>成交冻结分摊不允许同一订单出现两条同 topic 行，Σpayable 保持实付；</li>
 *   <li>status=1 却没有 claimed_member_id/claimed_at 的行被 CHECK 拒绝。</li>
 * </ul>
 *
 * <p>一类一库，跑完 DROP DATABASE 并由 {@link P0AcceptanceSchema#drop} 回读确实不存在。
 * 本机不执行；由真库门禁（registry 登记）在 GitHub-hosted runner 上跑。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketingProductGiftClaimMySqlIT {

    private final String suite = "m3gift_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static final long BUYER = 8101L;
    private static final long RECIPIENT = 8202L;
    private static final long OTHER = 8303L;
    private static final String TOKEN = "gift-claim-token-fixture";

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private MarketingProductMapper productMapper;
    private MarketingProductOrderMapper orderMapper;
    private GiftEntitlementMapper giftMapper;

    private long productId;

    @BeforeAll
    void schemaAndRealMappers() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()),
                "只许连一次性 scratch MySQL");
        source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertEquals(MybatisItDatabase.databaseName(suite),
                db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("SELECT VERSION()", String.class)), "需要 Oracle MySQL >= 8");

        P0AcceptanceSchema.migrations(source, "migration_marketing_product_20261108.sql");

        var session = P0AcceptanceSchema.sql(source,
                "MarketingProductMapper", "MarketingProductOrderMapper", "GiftEntitlementMapper");
        productMapper = session.getMapper(MarketingProductMapper.class);
        orderMapper = session.getMapper(MarketingProductOrderMapper.class);
        giftMapper = session.getMapper(GiftEntitlementMapper.class);
    }

    @AfterAll
    void cleanup() {
        if (db != null) {
            assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_m3gift_[a-z0-9_]+"));
            P0AcceptanceSchema.drop(db, suite);
        }
    }

    @BeforeEach
    void seedOneGiftProduct() {
        for (String table : List.of("gift_entitlement", "marketing_product_order_line",
                "marketing_product_order", "marketing_product_item", "marketing_product")) {
            db.update("DELETE FROM " + table);
        }
        productId = insertGiftProduct("gift-pass-30d");
    }

    // ------------------------------------------------------------------ 领取竞争

    @Test
    void concurrentClaimOfTheSameGiftHasExactlyOneWinner() throws Exception {
        long orderId = insertPaidGiftOrder("G-ONE-WINNER-" + UUID.randomUUID(), BUYER, "req-gift-1");
        long giftId = insertPendingGift(orderId, futureDate(30), TOKEN);

        List<Long> winners = runConcurrently(4, index -> tx.execute(status -> {
            GiftEntitlement locked = giftMapper.selectByTokenHashForUpdate(sha256(TOKEN));
            assertNotNull(locked, "礼品行必须存在");
            int updated = giftMapper.claimCas(locked.getId(), index % 2 == 0 ? RECIPIENT : OTHER,
                    "claim-" + index, new Date());
            return (long) updated;
        }));

        assertEquals(1L, winners.stream().filter(value -> value == 1L).count(),
                "同一份礼品并发领取只能有 1 个 CAS 成功，实际=" + winners);

        GiftEntitlement after = giftMapper.selectById(giftId);
        assertEquals(1, after.getStatus(), "领取后 status 必须为 1(已领取)");
        assertNotNull(after.getClaimedMemberId());
        assertNotNull(after.getClaimedAt());
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM gift_entitlement WHERE claimed_member_id IS NOT NULL", Integer.class),
                "只能有一个领取人落在库里");
    }

    @Test
    void expiredClaimCodeIsRejectedAndExpiryIsTerminal() {
        long orderId = insertPaidGiftOrder("G-EXPIRED-" + UUID.randomUUID(), BUYER, "req-gift-2");
        long giftId = insertPendingGift(orderId, pastDate(1), "token-expired");

        assertEquals(0, giftMapper.claimCas(giftId, RECIPIENT, "late-claim", new Date()),
                "过期码即使 status 仍为 0 也必须拒绝");
        assertEquals(1, giftMapper.expire(giftId, new Date()), "过期标记仍可把 0 置 2");
        assertEquals(2, giftMapper.selectById(giftId).getStatus());
        assertEquals(0, giftMapper.claimCas(giftId, RECIPIENT, "after-expiry", new Date()),
                "过期是终态，不能再被领取");
        assertEquals(0, giftMapper.expire(giftId, new Date()), "重复过期不得改写已终态行");
    }

    // ------------------------------------------------------------------ 下单幂等

    @Test
    void concurrentOrdersWithTheSameRequestIdCollapseToOneRow() throws Exception {
        String requestId = "same-request-" + UUID.randomUUID();
        List<Long> results = runConcurrently(4, index -> tx.execute(status -> {
            try {
                orderMapper.insert(order(BUYER, requestId, "O-" + index + "-" + UUID.randomUUID()));
                return 1L;
            } catch (DataIntegrityViolationException duplicate) {
                return 0L;
            }
        }));

        assertEquals(1L, results.stream().filter(value -> value == 1L).count(),
                "uk_marketing_order_request(member_id, request_id) 必须只放行一行");
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_product_order WHERE member_id=? AND request_id=?",
                Integer.class, BUYER, requestId));
        assertNotNull(orderMapper.selectByMemberRequest(BUYER, requestId),
                "重复请求必须能读回那唯一一行，而不是丢单");
    }

    // ------------------------------------------------------------------ 一份支付一份礼品

    @Test
    void oneGiftPerOrderAndRegistrationBindingHappensOnce() {
        long orderId = insertPaidGiftOrder("G-SINGLE-" + UUID.randomUUID(), BUYER, "req-gift-3");
        long giftId = insertPendingGift(orderId, futureDate(10), "token-single");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertPendingGift(orderId, futureDate(10), "token-second"),
                "uk_gift_entitlement_order 必须挡住同一订单的第二份礼品");

        assertEquals(1, giftMapper.bindSameRegistration(giftId, 99001L, new Date()));
        assertEquals(0, giftMapper.bindSameRegistration(giftId, 99002L, new Date()),
                "领取后绑定原 registration 只能发生一次");
        assertEquals(99001L, giftMapper.selectById(giftId).getClaimedRegistrationId().longValue());
    }

    // ------------------------------------------------------------------ 冻结分摊

    @Test
    void frozenAllocationRejectsDuplicateTopicAndKeepsPayableSum() {
        long orderId = insertPaidGiftOrder("G-LINES-" + UUID.randomUUID(), BUYER, "req-gift-4");
        MarketingProductOrderLine first = line(orderId, 41L, "6.00", "4.00", 0);
        MarketingProductOrderLine second = line(orderId, 42L, "6.00", "6.00", 1);
        assertEquals(1, orderMapper.insertFrozenLine(first));
        assertEquals(1, orderMapper.insertFrozenLine(second));

        assertThrows(DataIntegrityViolationException.class,
                () -> orderMapper.insertFrozenLine(line(orderId, 41L, "1.00", "1.00", 2)),
                "uk_marketing_order_line(order_id, topic_id) 必须拒绝同一订单的重复作品行");

        List<MarketingProductOrderLine> frozen = orderMapper.selectFrozenLines(
                orderMapper.selectById(orderId).getOrderNo());
        assertEquals(2, frozen.size());
        BigDecimal sum = frozen.stream().map(MarketingProductOrderLine::getPayableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("10.00").compareTo(sum),
                "冻结分摊 Σpayable 必须等于成交现金额，实际=" + sum);
        assertEquals(0, new BigDecimal("10.00").compareTo(
                orderMapper.selectById(orderId).getTotalCashAmount()));
    }

    // ------------------------------------------------------------------ CHECK 约束

    @Test
    void claimedStatusWithoutClaimantIsRejectedByCheck() {
        long orderId = insertPaidGiftOrder("G-CHECK-" + UUID.randomUUID(), BUYER, "req-gift-5");
        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO gift_entitlement (public_id, order_id, product_id, buyer_member_id,"
                        + " claim_token_hash, status, claimed_member_id, claimed_at, create_time, update_time)"
                        + " VALUES ('half-claimed',?,?,?,?,1,NULL,NULL,NOW(),NOW())",
                orderId, productId, BUYER, sha256("half-claimed-token")),
                "status=1 却没有 claimed_member_id/claimed_at 必须被 ck_gift_entitlement_claim 拒绝");
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM gift_entitlement WHERE status=1", Integer.class));
    }

    // ------------------------------------------------------------------ fixtures

    private long insertGiftProduct(String code) {
        MarketingProduct product = new MarketingProduct();
        product.setProductCode(code + "-" + UUID.randomUUID().toString().substring(0, 8));
        product.setProductName("礼品通行证");
        product.setProductKind(7);
        product.setPurchaseKind(2);
        product.setExploreCapacity(0);
        product.setFulfillmentRoute(1);
        product.setCashPrice(new BigDecimal("10.00"));
        product.setValidityKind(0);
        product.setSeatCount(1);
        product.setGiftAllowed(1);
        product.setGiftClaimDays(30);
        product.setStatus(1);
        product.setTermsVersion("terms-v1");
        product.setRevision(1L);
        product.setCreateTime(new Date());
        product.setUpdateTime(new Date());
        assertEquals(1, productMapper.insertProduct(product));
        return product.getId();
    }

    private long insertPaidGiftOrder(String orderNo, long memberId, String requestId) {
        MarketingProductOrder order = order(memberId, requestId, orderNo);
        order.setFulfillmentKind(2);
        order.setGiftRecipientMemberId(RECIPIENT);
        order.setStatus(1);
        order.setPaidAt(new Date());
        assertEquals(1, orderMapper.insert(order));
        return order.getId();
    }

    private MarketingProductOrder order(long memberId, String requestId, String orderNo) {
        MarketingProductOrder order = new MarketingProductOrder();
        order.setOrderNo(orderNo);
        order.setMemberId(memberId);
        order.setProductId(productId);
        order.setProductRevision(1L);
        order.setFulfillmentKind(1);
        order.setPayMode(1);
        order.setQuantity(1);
        order.setUnitCashPrice(new BigDecimal("10.00"));
        order.setTotalCashAmount(new BigDecimal("10.00"));
        order.setCurrency("CNY");
        order.setProductSnapshotJson("{}");
        order.setQuoteSign("a".repeat(64));
        order.setQuoteExpiresAt(futureDate(1));
        order.setStatus(0);
        order.setFulfillmentState(0);
        order.setRequestId(requestId);
        order.setRequestFingerprint("f".repeat(64));
        order.setCreateTime(new Date());
        order.setUpdateTime(new Date());
        return order;
    }

    private long insertPendingGift(long orderId, Date claimExpiresAt, String claimToken) {
        GiftEntitlement gift = new GiftEntitlement();
        gift.setPublicId("pub-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        gift.setOrderId(orderId);
        gift.setProductId(productId);
        gift.setBuyerMemberId(BUYER);
        gift.setRecipientMemberId(RECIPIENT);
        gift.setClaimTokenHash(sha256(claimToken));
        gift.setClaimExpiresAt(claimExpiresAt);
        gift.setStatus(0);
        gift.setCreateTime(new Date());
        gift.setUpdateTime(new Date());
        assertEquals(1, giftMapper.insert(gift));
        return gift.getId();
    }

    private MarketingProductOrderLine line(long orderId, long topicId, String list, String payable, int sort) {
        MarketingProductOrderLine line = new MarketingProductOrderLine();
        line.setOrderId(orderId);
        line.setTopicId(topicId);
        line.setListAmount(new BigDecimal(list));
        line.setPayableAmount(new BigDecimal(payable));
        line.setValidityKind(0);
        line.setValidDays(0);
        line.setExploreCapacity(0);
        line.setSortNo(sort);
        line.setCreateTime(new Date());
        return line;
    }

    private interface ConcurrentCall {
        long run(int index) throws Exception;
    }

    private List<Long> runConcurrently(int threads, ConcurrentCall call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    return call.run(index);
                }));
            }
            start.countDown();
            List<Long> results = new ArrayList<>();
            for (Future<Long> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static Date futureDate(int days) {
        return new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days));
    }

    private static Date pastDate(int days) {
        return new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
