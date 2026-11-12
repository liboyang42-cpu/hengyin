package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ChannelRefundVerification;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MarketingProduct;
import com.chengyinhub.business.domain.MarketingProductItem;
import com.chengyinhub.business.domain.dto.ChannelGrantCommand;
import com.chengyinhub.business.domain.dto.ChannelGrantResult;
import com.chengyinhub.business.domain.dto.ChannelGrantRevocation;
import com.chengyinhub.business.domain.dto.ChannelRedeemRequest;
import com.chengyinhub.business.domain.dto.ChannelRedeemResult;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelRefundVerificationMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.mapper.MarketingProductMapper;
import com.chengyinhub.business.service.ChannelRedemptionGrantPort;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.impl.ChannelRedemptionServiceImpl;
import com.chengyinhub.business.service.impl.ChannelVoucherServiceImpl;
import com.chengyinhub.business.service.support.ChannelRedemptionGrantPortResolver;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M8 渠道码在真实 InnoDB 上的兑换/退款消费边界。
 *
 * <p>这里用的是**真实 ChannelRedemptionServiceImpl + 真实 mapper**，只把权威 grant 端口
 * 换成一个可计数的桩（端口本身就是外部合同，不是本域数据）。单测证明不了的是：
 * 两个会员同时兑同一码只有一个成功、请求键重放不二次授权、grant 失败时码不落已兑换、
 * 退款消费 CAS 只有一个执行者。
 *
 * <p>覆盖的负例：
 * <ul>
 *   <li>同一码并发兑换：恰好一份回执，另一个 ALREADY_REDEEMED_BY_OTHER；</li>
 *   <li>同 (member, requestId) 重放：返回原回执，不再次调用 grant；</li>
 *   <li>grant 端口未接线：不写回执、码保持 ACTIVE（不假装已兑换）；</li>
 *   <li>过期码即使 status 仍是 ACTIVE 也不能兑换；</li>
 *   <li>uk_channel_redemption_voucher 挡住同一码的第二份回执；</li>
 *   <li>同一退款事实的消费 CAS 只有一个执行者。</li>
 * </ul>
 *
 * <p>一类一库，跑完 DROP DATABASE 并回读不存在。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketingChannelVoucherMySqlIT {

    private final String suite = "m8channel_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    private static final long TOPIC_ID = 4141L;
    private static final long MEMBER_A = 9901L;
    private static final long MEMBER_B = 9902L;
    private static final String CHANNEL_KEY = "ota-it";
    private static final String PROVIDER_KEY = "it-provider";

    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private MarketingProductMapper productMapper;
    private ChannelVoucherMapper voucherMapper;
    private ChannelRedemptionMapper redemptionMapper;
    private ChannelOrderVerificationMapper verificationMapper;
    private ChannelVoucherAccountMapper accountMapper;
    private ChannelRefundVerificationMapper refundMapper;
    private ChannelRedemptionServiceImpl service;
    private CountingGrantPort grantPort;
    private ChannelVoucherServiceImpl issuance;
    private ChannelRedemptionServiceImpl unwiredService;

    private long productId;
    private long accountId;
    private long verificationId;
    private long voucherId;
    private String code;

    @BeforeAll
    void schemaAndRealService() throws Exception {
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
        // 渠道迁移的 preflight 要求既有 oms_ticket 存在；本域不读它，只建最小形状。
        db.execute("CREATE TABLE IF NOT EXISTS oms_ticket (id BIGINT NOT NULL, PRIMARY KEY (id))");
        P0AcceptanceSchema.migrations(source, "migration_marketing_z_channel_voucher_20261108.sql",
                "migration_z_channel_voucher_snapshot_20261109.sql");

        var session = P0AcceptanceSchema.sql(source,
                "MarketingProductMapper", "ChannelVoucherMapper", "ChannelRedemptionMapper",
                "ChannelOrderVerificationMapper", "ChannelVoucherAccountMapper",
                "ChannelRefundVerificationMapper");
        productMapper = session.getMapper(MarketingProductMapper.class);
        voucherMapper = session.getMapper(ChannelVoucherMapper.class);
        redemptionMapper = session.getMapper(ChannelRedemptionMapper.class);
        verificationMapper = session.getMapper(ChannelOrderVerificationMapper.class);
        accountMapper = session.getMapper(ChannelVoucherAccountMapper.class);
        refundMapper = session.getMapper(ChannelRefundVerificationMapper.class);

        IProductRoleService roleService = mock(IProductRoleService.class);
        when(roleService.isPlatformOwned(TOPIC_ID)).thenReturn(true);
        ICmsTopicService topicService = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setSelfPlayPrice(new BigDecimal("10.00"));
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        grantPort = new CountingGrantPort();
        issuance = new ChannelVoucherServiceImpl(accountMapper, verificationMapper, voucherMapper, redemptionMapper,
                productMapper, mock(com.chengyinhub.business.mapper.OmsTicketMapper.class), roleService, topicService,
                new ChannelRedemptionGrantPortResolver(List.of(grantPort)));
        service = new ChannelRedemptionServiceImpl(voucherMapper, redemptionMapper, verificationMapper,
                accountMapper, productMapper, roleService, topicService,
                new ChannelRedemptionGrantPortResolver(List.of(grantPort)));
        unwiredService = new ChannelRedemptionServiceImpl(voucherMapper, redemptionMapper, verificationMapper,
                accountMapper, productMapper, roleService, topicService,
                new ChannelRedemptionGrantPortResolver(List.of()));
    }

    @AfterAll
    void cleanup() {
        if (db != null) {
            assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_m8channel_[a-z0-9_]+"));
            P0AcceptanceSchema.drop(db, suite);
        }
    }

    @BeforeEach
    void reseedIssuedVoucher() {
        for (String table : List.of("marketing_channel_refund_verification", "marketing_channel_redemption",
                "marketing_channel_voucher", "marketing_channel_order_verification",
                "marketing_channel_voucher_account", "marketing_product_item", "marketing_product")) {
            db.update("DELETE FROM " + table);
        }
        grantPort.calls.set(0);
        productId = insertProduct();
        insertItem(productId);
        accountId = insertAccount();
        verificationId = insertVerifiedOrder();
        com.chengyinhub.business.domain.dto.ChannelVoucherIssueRequest issue = new com.chengyinhub.business.domain.dto.ChannelVoucherIssueRequest();
        issue.setVerificationId(verificationId);
        issue.setRequestId("issue-" + hex16());
        var issued = tx.execute(status -> issuance.issue(1L, issue));
        code = issued.getCode();
        voucherId = issued.getId();
    }

    // ------------------------------------------------------------------ 同码并发兑换

    @Test
    void concurrentRedeemOfTheSameCodeYieldsExactlyOneReceipt() throws Exception {
        List<String> outcomes = runConcurrently(2, index -> tx.execute(status -> {
            try {
                service.redeem(MEMBER_A + index, request("req-" + index));
                return "OK";
            } catch (ChannelVoucherException rejected) {
                return rejected.getReasonCode();
            }
        }));

        assertEquals(1L, outcomes.stream().filter("OK"::equals).count(),
                "同一兑换码并发只能成功一次，实际=" + outcomes);
        assertEquals(1L, outcomes.stream().filter("ALREADY_REDEEMED_BY_OTHER"::equals).count(),
                "输家必须得到明确拒绝而不是第二个回执，实际=" + outcomes);
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_channel_redemption WHERE voucher_id=?", Integer.class, voucherId));
        assertEquals(ChannelVoucher.STATUS_REDEEMED,
                voucherMapper.selectById(voucherId).getStatus().intValue());
        assertEquals(1, grantPort.calls.get(), "权威 grant 只能被调用一次");
    }

    @Test
    void replayOfTheSameRequestReturnsTheOriginalReceiptWithoutSecondGrant() {
        ChannelRedeemResult first = tx.execute(status -> service.redeem(MEMBER_A, request("replay-1")));
        assertNotNull(first);
        ChannelRedeemResult replay = tx.execute(status -> service.redeem(MEMBER_A, request("replay-1")));

        assertNotNull(replay, "同一请求键必须返回原回执而不是 null");
        assertTrue(replay.getReplayed(), "同一请求键必须返回原回执");
        assertEquals(first.getReceiptNo(), replay.getReceiptNo());
        assertEquals(1, grantPort.calls.get(), "重放不得再走一次权威授权");
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_channel_redemption WHERE member_id=?", Integer.class, MEMBER_A));
    }

    // ------------------------------------------------------------------ 默认关闭 / 过期

    @Test
    void unwiredGrantPortLeavesVoucherActiveAndUnredeemed() {
        ChannelVoucherException rejected = assertThrows(ChannelVoucherException.class,
                () -> tx.execute(status -> unwiredService.redeem(MEMBER_A, request("unwired-1"))));
        assertEquals("GRANT_PORT_NOT_WIRED", rejected.getReasonCode());
        assertEquals(ChannelVoucher.STATUS_ACTIVE,
                voucherMapper.selectById(voucherId).getStatus().intValue(),
                "授权未完成时码必须保持 ACTIVE，不得先标已兑换");
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_channel_redemption", Integer.class));
    }

    @Test
    void expiredVoucherCannotBeRedeemedEvenIfStatusIsStillActive() {
        // 过期夹具同时回拨发行时间，保留 ck_channel_voucher_window 的合法窗口。
        db.update("UPDATE marketing_channel_voucher SET issued_at = DATE_SUB(NOW(), INTERVAL 2 MINUTE),"
                + " expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=?", voucherId);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM marketing_channel_voucher"
                + " WHERE id=? AND status=? AND issued_at < expires_at AND expires_at < NOW()",
                Integer.class, voucherId, ChannelVoucher.STATUS_ACTIVE));
        ChannelVoucherException rejected = assertThrows(ChannelVoucherException.class,
                () -> tx.execute(status -> service.redeem(MEMBER_A, request("expired-1"))));
        assertEquals("CODE_EXPIRED", rejected.getReasonCode());
        assertEquals(ChannelVoucher.STATUS_ACTIVE, voucherMapper.selectById(voucherId).getStatus());
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_channel_redemption", Integer.class));
        assertEquals(0, grantPort.calls.get(), "过期码不得触发权威授权");
    }

    // ------------------------------------------------------------------ 一码一回执 / 退款消费

    @Test
    void oneVoucherCanOnlyHaveOneRedemptionReceipt() {
        tx.execute(status -> service.redeem(MEMBER_A, request("first-1")));

        assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "INSERT INTO marketing_channel_redemption"
                        + " (voucher_id,member_id,channel_order_no,receipt_no,request_id,request_fingerprint,"
                        + " status,redeemed_at,create_time,update_time)"
                        + " VALUES(?,?,'IT-ORDER-1','CR-SECOND','second-req',?,0,NOW(),NOW(),NOW())",
                voucherId, MEMBER_B, "f".repeat(64)),
                "uk_channel_redemption_voucher 必须挡住同一码的第二份回执");
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM marketing_channel_redemption WHERE voucher_id=?", Integer.class, voucherId));
    }

    @Test
    void concurrentRefundFactConsumptionHasOneExecutor() throws Exception {
        long refundId = insertVerifiedRefund();

        List<String> results = runConcurrently(2, index -> tx.execute(status ->
                String.valueOf(refundMapper.markConsumed(refundId, new Date(), new Date()))));

        assertEquals(1L, results.stream().filter("1"::equals).count(),
                "同一退款事实只能被消费一次，实际=" + results);
        ChannelRefundVerification after = refundMapper.selectById(refundId);
        assertEquals(1, after.getConsumed().intValue());
        assertNotNull(after.getConsumedAt());
    }

    @Test
    void refundBeforeRedemptionPermanentlyClosesIssuedCode() {
        long refundId = insertVerifiedRefund();
        var fulfillment = mock(com.chengyinhub.business.service.IMarketingProductFulfillmentService.class);
        var refund = new com.chengyinhub.business.service.impl.ChannelRefundServiceImpl(
                accountMapper, verificationMapper, refundMapper,
                new com.chengyinhub.business.service.impl.ChannelRefundFactStore(refundMapper),
                voucherMapper, redemptionMapper, mock(com.chengyinhub.business.mapper.OmsTicketMapper.class), fulfillment);
        assertEquals(Integer.valueOf(1), tx.execute(status -> refund.retryPendingConsumptions(5)));
        ChannelVoucherException rejected = org.junit.jupiter.api.Assertions.assertThrows(ChannelVoucherException.class,
                () -> tx.execute(status -> service.redeem(MEMBER_A, request("after-refund"))));
        assertEquals("CODE_REVOKED", rejected.getReasonCode());
        assertEquals(0, grantPort.calls.get());
        assertEquals(Integer.valueOf(1), db.queryForObject(
                "SELECT consumed FROM marketing_channel_refund_verification WHERE id=?", Integer.class, refundId));
        org.mockito.Mockito.verifyNoInteractions(fulfillment);
    }

    @Test
    void concurrentSameRequestReturnsOneReceiptAndOneGrant() throws Exception {
        List<String> receipts = runConcurrently(2, index -> tx.execute(status ->
                service.redeem(MEMBER_A, request("same-concurrent")).getReceiptNo()));
        assertEquals(receipts.get(0), receipts.get(1));
        assertEquals(1, grantPort.calls.get());
        assertEquals(Integer.valueOf(1), db.queryForObject(
                "SELECT COUNT(*) FROM marketing_channel_redemption WHERE voucher_id=?", Integer.class, voucherId));
    }

    // ------------------------------------------------------------------ fixtures

    private long insertProduct() {
        MarketingProduct product = new MarketingProduct();
        product.setProductCode("ota-it-" + hex16());
        product.setProductName("渠道自营单关");
        product.setProductKind(1);
        product.setPurchaseKind(2);
        product.setExploreCapacity(0);
        product.setFulfillmentRoute(1);
        product.setCashPrice(new BigDecimal("10.00"));
        product.setValidityKind(0);
        product.setSeatCount(1);
        product.setGiftAllowed(0);
        product.setStatus(1);
        product.setTermsVersion("terms-v1");
        product.setRevision(1L);
        product.setCreateTime(new Date());
        product.setUpdateTime(new Date());
        productMapper.insertProduct(product);
        return product.getId();
    }

    private void insertItem(long productId) {
        MarketingProductItem item = new MarketingProductItem();
        item.setProductId(productId);
        item.setTopicId(TOPIC_ID);
        item.setTopicTitle("IT topic");
        item.setContentRevision(1L);
        item.setSortNo(0);
        item.setCreateTime(new Date());
        productMapper.insertItem(item);
    }

    private long insertAccount() {
        db.update("INSERT INTO marketing_channel_voucher_account"
                        + " (channel_key,display_name,contract_ref,settlement_mode,status,effective_from,"
                        + " create_by,create_time,update_time)"
                        + " VALUES(?,?,?,0,1,DATE_SUB(NOW(), INTERVAL 1 DAY),1,NOW(),NOW())",
                CHANNEL_KEY, "IT OTA", "CONTRACT-IT-1");
        return db.queryForObject("SELECT id FROM marketing_channel_voucher_account WHERE channel_key=?",
                Long.class, CHANNEL_KEY);
    }

    private long insertVerifiedOrder() {
        String orderNo = "OTA-ORDER-" + hex16();
        db.update("INSERT INTO marketing_channel_order_verification"
                        + " (account_id,channel_key,provider_key,channel_order_no,event_id,amount,currency,"
                        + " product_id,quantity,status,verified_at,raw_hash,request_id,request_fingerprint,"
                        + " create_time,update_time)"
                        + " VALUES(?,?,?,?,?,10.00,'CNY',?,1,1,NOW(),?,?,?,NOW(),NOW())",
                accountId, CHANNEL_KEY, PROVIDER_KEY, orderNo, "EV-" + hex16(), productId,
                "a".repeat(64), "vo-" + hex16(), "b".repeat(64));
        return db.queryForObject(
                "SELECT id FROM marketing_channel_order_verification WHERE channel_order_no=?",
                Long.class, orderNo);
    }


    private long insertVerifiedRefund() {
        String orderNo = db.queryForObject(
                "SELECT channel_order_no FROM marketing_channel_order_verification WHERE id=?",
                String.class, verificationId);
        ChannelRefundVerification row = new ChannelRefundVerification();
        row.setAccountId(accountId);
        row.setPurchaseVerificationId(verificationId);
        row.setChannelKey(CHANNEL_KEY);
        row.setProviderKey(PROVIDER_KEY);
        row.setChannelOrderNo(orderNo);
        row.setChannelRefundNo("OTA-REFUND-" + hex16());
        row.setEventId("EV-REFUND-" + hex16());
        row.setAmount(new BigDecimal("10.00"));
        row.setCurrency("CNY");
        row.setFullRefund(1);
        row.setStatus(ChannelRefundVerification.STATUS_VERIFIED);
        row.setConsumed(0);
        row.setRawHash("d".repeat(64));
        row.setRequestId("rf-" + hex16());
        row.setRequestFingerprint("e".repeat(64));
        row.setVerifiedAt(new Date());
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        refundMapper.insert(row);
        return row.getId();
    }

    private ChannelRedeemRequest request(String requestId) {
        ChannelRedeemRequest request = new ChannelRedeemRequest();
        request.setCode(code);
        request.setRequestId(requestId);
        return request;
    }

    /**
     * 【界外 read/grant port 计数桩】{@code ChannelRedemptionGrantPort} 由 M2-F 在
     * 原注册/权益服务里实现（外部合同，不是本域数据）。本 IT 只关心「调用了几次 / 是否被调用」，
     * 因此用计数桩替代它；回执里的 registration id 是虚构值，不会写成任何真实准入事实。
     * 真实 grant 行为由 M2-F 的合同测试负责。
     */
    private final class CountingGrantPort implements ChannelRedemptionGrantPort {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChannelGrantResult grant(ChannelGrantCommand command) {
            calls.incrementAndGet();
            ChannelGrantResult result = new ChannelGrantResult();
            result.setPrimaryRegistrationId(70001L + calls.get());
            result.setPrimaryRegistrationNo("R-IT-" + calls.get());
            result.setRegistrationIds(List.of(70001L + calls.get()));
            result.setEntitlementIds(List.of());
            result.setGrantRef("grant-ref-" + calls.get());
            result.setPendingActivation(false);
            return result;
        }

        @Override
        public ChannelGrantResult revoke(ChannelGrantRevocation command) {
            return null;
        }
    }

    private interface ConcurrentCall {
        String run(int index) throws Exception;
    }

    private List<String> runConcurrently(int threads, ConcurrentCall call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    assertTrue(start.await(20, TimeUnit.SECONDS));
                    return call.run(index);
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        }
    }


    private static String hex16() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

}
