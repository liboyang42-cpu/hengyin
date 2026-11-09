package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.GiftEntitlementMapper;
import com.chengyinhub.business.mapper.MarketingProductOrderMapper;
import com.chengyinhub.business.mapper.MarketingRevenueAllocationMapper;
import com.chengyinhub.business.mapper.MarketingRevenuePostingTaskMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.impl.GiftEntitlementServiceImpl;
import com.chengyinhub.business.service.impl.MarketingProductPurchaseServiceImpl;
import com.chengyinhub.business.service.impl.MarketingProductRefundConsumer;
import com.chengyinhub.business.service.impl.MarketingRevenuePostingServiceImpl;
import com.chengyinhub.business.service.impl.MarketingRevenueRefundSettledConsumer;
import com.chengyinhub.business.service.support.MarketingCashChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 合成到账事实 + 原 Mapper/消费方/商品撤销/礼品/收入队列的 InnoDB 存储回归。
 * 不调用 provider，不证明非首项具备原路付款绑定，也不冒充真实退款到账验收。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketingBundleRefundMySqlIT {
    private final String suite = "bundle_refund_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private CmsRegistrationMapper registrations;
    private RefundApplicationMapper refunds;
    private MarketingProductRefundConsumer productConsumer;
    private MarketingRevenueRefundSettledConsumer revenueConsumer;
    private MarketingRevenuePostingServiceImpl posting;

    @BeforeAll
    void schemaAndServices() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()),
                "仅使用集中测试的一次性 scratch MySQL");
        DriverManagerDataSource source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(suite), db.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer(
                db.queryForObject("SELECT VERSION()", String.class)));
        P0AcceptanceSchema.baseTables(db, "cms_registration", "refund_application", "player_node_progress");
        P0AcceptanceSchema.migrations(source, "migration_20260930_registration_first_successful_scan.sql",
                "migration_merchant_aftercare_timeout_20260917.sql",
                "migration_marketing_product_20261108.sql", "migration_marketing_revenue_allocation_20261108.sql");
        // 夹具补齐原 Mapper 的运行时列；不改既有迁移，不执行全游戏域迁移。
        db.execute("ALTER TABLE player_node_progress ADD COLUMN play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',"
                + " ADD COLUMN puzzle_score SMALLINT NULL, ADD COLUMN completion_mode VARCHAR(16) NULL");
        var sql = P0AcceptanceSchema.sql(source, "CmsRegistrationMapper", "RefundApplicationMapper",
                "MarketingProductOrderMapper", "GiftEntitlementMapper", "PlayerNodeProgressMapper",
                "MarketingRevenueAllocationMapper", "MarketingRevenuePostingTaskMapper");
        registrations = sql.getMapper(CmsRegistrationMapper.class);
        refunds = sql.getMapper(RefundApplicationMapper.class);
        var orders = sql.getMapper(MarketingProductOrderMapper.class);
        var gifts = sql.getMapper(GiftEntitlementMapper.class);
        var progress = sql.getMapper(PlayerNodeProgressMapper.class);
        var giftService = new GiftEntitlementServiceImpl(gifts, null, registrations, progress,
                "bundle-refund-test-only");
        var purchases = new MarketingProductPurchaseServiceImpl(null, orders, gifts, null, null, null,
                null, null, new MarketingCashChannel(false), giftService, null, null, null, null);
        productConsumer = new MarketingProductRefundConsumer(orders, purchases, registrations, refunds, gifts, progress);
        var manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        posting = new MarketingRevenuePostingServiceImpl(null,
                sql.getMapper(MarketingRevenuePostingTaskMapper.class), refunds, manager);
        revenueConsumer = new MarketingRevenueRefundSettledConsumer(posting,
                sql.getMapper(MarketingRevenueAllocationMapper.class));
    }

    @BeforeEach
    void seedPaidTwoItemGift() {
        for (String table : List.of("marketing_revenue_posting_task", "gift_entitlement",
                "marketing_product_order_line", "marketing_product_order", "marketing_product_item",
                "marketing_product", "refund_application", "cms_registration", "player_node_progress")) {
            db.update("DELETE FROM " + table);
        }
        db.update("INSERT INTO marketing_product (id,product_code,product_name,product_kind,purchase_kind,"
                + "cash_price,validity_kind,seat_count,gift_allowed,status,terms_version,revision,create_time,update_time)"
                + " VALUES (1,'bundle-fixture','两项有限包',2,2,100,0,1,1,1,'fixture-v1',1,NOW(),NOW())");
        db.update("INSERT INTO marketing_product_order (id,order_no,member_id,product_id,product_revision,"
                + "fulfillment_kind,pay_mode,quantity,unit_cash_price,total_cash_amount,currency,product_snapshot_json,"
                + "quote_sign,quote_expires_at,status,payment_fact_type,payment_fact_id,primary_registration_id,"
                + "primary_registration_no,registration_ids_json,fulfillment_state,paid_at,request_id,"
                + "request_fingerprint,create_time,update_time)"
                + " VALUES (10,'bundle-10',7,1,1,2,1,1,100,100,'CNY','{}',?,DATE_ADD(NOW(), INTERVAL 1 DAY),"
                + "1,1,11,101,'REG101','[101,102]',1,NOW(),'fixture-order',?,NOW(),NOW())",
                "a".repeat(64), "b".repeat(64));
        for (long id : List.of(101L, 102L)) {
            long topic = id == 101 ? 41 : 42;
            int amount = id == 101 ? 40 : 60;
            db.update("INSERT INTO cms_registration (id,registration_no,parent_order_no,owner_type,owner_id,"
                    + "topic_id,ticket_id,ticket_price,member_id,original_amount,total_amount,payable_amount,"
                    + "payment_status,registration_status,pay_expire_time)"
                    + " VALUES (?,?,'bundle-10',1,?,?,0,0,7,?,?,?,2,2,NOW())",
                    id, "REG" + id, topic, topic, amount, amount, amount);
            db.update("INSERT INTO marketing_product_order_line (order_id,topic_id,list_amount,payable_amount,"
                    + "validity_kind,valid_days,sort_no,create_time) VALUES (10,?,?,?,0,0,?,NOW())",
                    topic, amount, amount, id);
        }
        db.update("INSERT INTO gift_entitlement (id,public_id,order_id,product_id,source_registration_id,"
                + "buyer_member_id,claim_token_hash,status,claimed_member_id,claimed_registration_id,claimed_at,"
                + "create_time,update_time) VALUES (20,'gift-fixture',10,1,101,7,?,1,9,101,NOW(),NOW(),NOW())",
                "c".repeat(64));
        tx.executeWithoutResult(s -> {
            posting.enqueueAccrual(101L);
            posting.enqueueAccrual(102L);
        });
    }

    @AfterAll
    void dropOwnSchema() {
        if (db != null) P0AcceptanceSchema.drop(db, suite);
    }

    @Test
    void twoItemGiftRefundKeepsRemainingAccessAndReplaysWithoutExtraTasks() {
        RefundSettledFact first = settle(101, 4000);
        replay(first);
        assertEquals(1, integer("SELECT status FROM marketing_product_order WHERE id=10"));
        assertEquals(2, integer("SELECT payment_status FROM cms_registration WHERE id=102"));
        assertEquals(2, integer("SELECT registration_status FROM cms_registration WHERE id=102"));
        assertEquals(1, integer("SELECT status FROM gift_entitlement WHERE id=20"));
        assertEquals(9L, db.queryForObject("SELECT claimed_member_id FROM gift_entitlement WHERE id=20", Long.class));
        assertEquals(1, integer("SELECT COUNT(*) FROM refund_application"));
        assertEquals(1, integer("SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE task_kind=2"));
        assertEquals(101L, db.queryForObject("SELECT order_id FROM marketing_revenue_posting_task"
                + " WHERE task_kind=2 AND ref_id=?", Long.class, first.refundApplicationId()));

        // 售后只认冻结的 40/60；修改实时商品标价不改变旧订单。
        db.update("UPDATE marketing_product SET cash_price=999,revision=2 WHERE id=1");
        RefundSettledFact second = settle(102, 6000);
        replay(second);
        replay(first);
        assertEquals(3, integer("SELECT status FROM marketing_product_order WHERE id=10"));
        assertEquals(3, integer("SELECT status FROM gift_entitlement WHERE id=20"));
        assertEquals(9L, db.queryForObject("SELECT claimed_member_id FROM gift_entitlement WHERE id=20", Long.class));
        assertEquals(2, integer("SELECT COUNT(*) FROM refund_application"));
        assertEquals(2, integer("SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE task_kind=2"));
        assertEquals(102L, db.queryForObject("SELECT order_id FROM marketing_revenue_posting_task"
                + " WHERE task_kind=2 AND ref_id=?", Long.class, second.refundApplicationId()));
        assertEquals(0, new BigDecimal("100.00").compareTo(db.queryForObject(
                "SELECT SUM(payable_amount) FROM marketing_product_order_line WHERE order_id=10", BigDecimal.class)));
    }

    @Test
    void ambiguousAmountRollsBackInsteadOfChoosingAnItem() {
        assertThrows(IllegalStateException.class, () -> settle(101, 2000));
        assertEquals(2, integer("SELECT payment_status FROM cms_registration WHERE id=101"));
        assertEquals(2, integer("SELECT payment_status FROM cms_registration WHERE id=102"));
        assertEquals(0, integer("SELECT COUNT(*) FROM refund_application"));
        assertEquals(0, integer("SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE task_kind=2"));
        assertEquals(1, integer("SELECT status FROM gift_entitlement WHERE id=20"));
    }

    @Test
    void changedReplayPayloadCannotConsumeTheOriginalRefundTwice() {
        RefundSettledFact first = settle(101, 4000);
        assertThrows(IllegalStateException.class, () -> replay(fact(101, first.refundApplicationId(), 3900)));
        assertEquals(1, integer("SELECT COUNT(*) FROM refund_application"));
        assertEquals(1, integer("SELECT COUNT(*) FROM marketing_revenue_posting_task WHERE task_kind=2"));
        assertEquals(2, integer("SELECT payment_status FROM cms_registration WHERE id=102"));
    }

    @Test
    void recipientProgressFromAnOlderRunIsNotMistakenForAnUnusedGift() {
        db.update("INSERT INTO player_node_progress(member_id,activity_id,topic_id,node_id,play_instance_id)"
                + " VALUES (9,0,41,701,'old-run')");
        assertTrue(assertThrows(IllegalStateException.class, () -> settle(101, 4000))
                .getMessage().contains("USED_CONTENT_NOT_REVOCABLE"));
        assertEquals(0, integer("SELECT COUNT(*) FROM refund_application"));
        assertEquals(1, integer("SELECT status FROM gift_entitlement WHERE id=20"));
        assertEquals(2, integer("SELECT payment_status FROM cms_registration WHERE id=102"));
    }

    private RefundSettledFact settle(long registrationId, long amountFen) {
        return tx.execute(s -> {
            assertEquals(1, registrations.claimRegistrationRefunded(registrationId, "refund-review", new Date()));
            RefundApplication refund = new RefundApplication();
            refund.setSourceType("registration");
            refund.setSourceId(registrationId);
            refund.setRefundNo("RF" + registrationId);
            refund.setOwnerType(1L);
            refund.setOwnerId(registrationId == 101 ? 41L : 42L);
            refund.setMemberId(7L);
            refund.setPaidAmount(new BigDecimal(registrationId == 101 ? "40.00" : "60.00"));
            refund.setRefundAmount(BigDecimal.valueOf(amountFen, 2));
            refund.setStatus(1);
            refund.setDelFlag(0);
            refund.setIdempotencyKey("registration:" + registrationId);
            refund.setPayoutStatus(RefundApplication.PAYOUT_PROCESSING);
            assertEquals(1, refunds.insertRefundApplication(refund));
            assertEquals(1, refunds.updatePayoutResult(refund.getId(), RefundApplication.PAYOUT_SUCCESS,
                    "synthetic verified refund fixture"));
            RefundSettledFact fact = fact(registrationId, refund.getId(), amountFen);
            productConsumer.onRefundSettled(fact);
            revenueConsumer.onRefundSettled(fact);
            return fact;
        });
    }

    private void replay(RefundSettledFact fact) {
        tx.executeWithoutResult(s -> {
            productConsumer.onRefundSettled(fact);
            revenueConsumer.onRefundSettled(fact);
        });
    }

    private RefundSettledFact fact(long registrationId, long refundId, long amount) {
        return new RefundSettledFact(registrationId, refundId, "RF" + registrationId, "synthetic-" + refundId,
                amount, 10000, "CNY", refundId + 1000, "synthetic-verified-" + refundId);
    }

    private int integer(String sql) {
        return db.queryForObject(sql, Integer.class);
    }
}
