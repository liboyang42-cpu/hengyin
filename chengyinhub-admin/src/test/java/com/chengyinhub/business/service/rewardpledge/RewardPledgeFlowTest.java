package com.chengyinhub.business.service.rewardpledge;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Stock;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeMapper;
import com.chengyinhub.business.service.rewardpledge.PledgeCouponPort.CouponView;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiNonCashRewardController;
import com.chengyinhub.web.controller.api.ApiRewardPledgeController;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated H2. No live coupon issuance and no payment. */
class RewardPledgeFlowTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    JdbcDataSource dataSource;
    JdbcTemplate jdbc;
    SqlSessionTemplate session;
    RewardPledgeService service;
    MapperCouponPort coupons;
    FaultGate gate;
    long couponId;
    long redeemStart;
    long redeemEnd;

    @BeforeEach void setup() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:w17_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=8000");
        jdbc = new JdbcTemplate(dataSource);
        Path root = moduleRoot();
        String sql = Files.readString(root.resolve("sql/migration_20261117_reward_pledge.sql"));
        StringBuilder ddl = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) ddl.append(line).append('\n');
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String part : ddl.toString().replace(" ENGINE=InnoDB", "").replace(" DEFAULT CHARSET=utf8mb4", "").split(";")) {
                if (!part.isBlank()) statement.execute(part);
            }
            statement.execute("CREATE TABLE sms_coupon(id BIGINT PRIMARY KEY, name VARCHAR(64), coupon_type INT, platform INT, amount INT, min_point INT, start_time TIMESTAMP, end_time TIMESTAMP, use_type INT, publish_count INT, use_count INT, receive_count INT, enable_time TIMESTAMP, code VARCHAR(64), scope_type INT, member_id BIGINT, merchant_id BIGINT, status INT, del_flag INT, description VARCHAR(128), create_by VARCHAR(64), create_time TIMESTAMP, update_by VARCHAR(64), update_time TIMESTAMP)");
            statement.execute("CREATE TABLE sms_coupon_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, coupon_id BIGINT, member_id BIGINT, coupon_code VARCHAR(64), qrcode_url VARCHAR(128), get_type INT, get_time TIMESTAMP, use_status INT, use_time TIMESTAMP, order_id BIGINT, order_sn VARCHAR(64), start_time TIMESTAMP, end_time TIMESTAMP, note VARCHAR(128), del_flag INT, create_by VARCHAR(64), create_time TIMESTAMP, update_by VARCHAR(64), update_time TIMESTAMP)");
            statement.execute("CREATE TABLE topic_route_session(id BIGINT PRIMARY KEY, member_id BIGINT, topic_id BIGINT, status INT, completed_at TIMESTAMP)");
            statement.execute("CREATE TABLE pms_sku(id BIGINT PRIMARY KEY, stock INT)");
            statement.execute("CREATE TABLE merchant_chapter_offer(id BIGINT AUTO_INCREMENT PRIMARY KEY, topic_id BIGINT, chapter_id BIGINT, merchant_id BIGINT, status INT, valid_end TIMESTAMP)");
        }
        Configuration config = new Configuration(new Environment("w17", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        load(config, root.getParent().resolve("chengyinhub-system/src/main/resources/mapper/business/rewardpledge/RewardPledgeMapper.xml"));
        load(config, root.getParent().resolve("chengyinhub-system/src/main/resources/mapper/business/SmsCouponMapper.xml"));
        load(config, root.getParent().resolve("chengyinhub-system/src/main/resources/mapper/business/SmsCouponHistoryMapper.xml"));
        session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        coupons = new MapperCouponPort(session.getMapper(SmsCouponMapper.class), session.getMapper(SmsCouponHistoryMapper.class));
        gate = new FaultGate();
        service = new RewardPledgeService(new DataSourceTransactionManager(dataSource), session.getMapper(RewardPledgeMapper.class), coupons, CLOCK, gate);
        jdbc.update("INSERT INTO sms_coupon(id, member_id, merchant_id, scope_type, status, publish_count, receive_count, use_count, start_time, end_time, name) VALUES (1, 50, 7, 1, 1, NULL, 0, 0, TIMESTAMP '2026-06-01 00:00:00', TIMESTAMP '2026-12-01 00:00:00', 'pledge')");
        jdbc.update("INSERT INTO pms_sku(id, stock) VALUES (90, 5)");
        CouponView coupon = coupons.load(1);
        couponId = coupon.id;
        redeemStart = coupon.startTime.getTime();
        redeemEnd = coupon.endTime.getTime();
        policy("REVIEWER", "", "OPEN", 80L);
        policy("RULE", "rule-approved", "OPEN", null);
        policy("CLAIM", "rule-approved", "OPEN", null);
        offer(50L, 100L);
        offer(50L, 200L);
    }

    @AfterEach void cleanup() { jdbc.execute("SHUTDOWN"); }

    @Test void twoPlayersRaceForTheLastUnit() throws Exception {
        String versionId = frozen(1);
        complete(11L, 100L);
        complete(12L, 100L);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> first = pool.submit(() -> claimAfter(start, versionId, 11L, "race-a"));
            Future<Map<String, Object>> second = pool.submit(() -> claimAfter(start, versionId, 12L, "race-b"));
            start.countDown();
            Map<String, Object> left = first.get();
            Map<String, Object> right = second.get();
            int awarded = ("AWARDED".equals(left.get("state")) ? 1 : 0) + ("AWARDED".equals(right.get("state")) ? 1 : 0);
            int refused = ("REFUSED".equals(left.get("state")) ? 1 : 0) + ("REFUSED".equals(right.get("state")) ? 1 : 0);
            assertEquals(1, awarded);
            assertEquals(1, refused);
            assertEquals("STOCK_EXHAUSTED", "REFUSED".equals(left.get("state")) ? left.get("errorCode") : right.get("errorCode"));
        } finally {
            pool.shutdownNow();
        }
        Stock stock = stock(versionId);
        assertEquals(0, stock.available);
        assertEquals(0, stock.reserved);
        assertEquals(1, stock.awardedUnredeemed);
        assertEquals(stock.quantity, stock.sum());
        assertEquals(1, count("w17_reward_award"));
        assertEquals(1, count("sms_coupon_history"));
        assertEquals(1, jdbc.queryForObject("SELECT receive_count FROM sms_coupon WHERE id=1", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT stock FROM pms_sku WHERE id=90", Integer.class));
    }

    @Test void aNewRequestIdDoesNotGrantAgain() {
        String versionId = frozen(2);
        complete(11L, 100L);
        Map<String, Object> first = claim(versionId, 11L, "req-1");
        Map<String, Object> replay = claim(versionId, 11L, "req-1");
        Map<String, Object> renamed = claim(versionId, 11L, "req-2");
        assertEquals(first.get("awardId"), replay.get("awardId"));
        assertEquals(first.get("awardId"), renamed.get("awardId"));
        assertEquals(1, count("w17_reward_award"));
        assertEquals(1, count("sms_coupon_history"));
        assertEquals(1, stock(versionId).awardedUnredeemed);
        assertEquals(1, stock(versionId).available);
        assertEquals(Boolean.FALSE, first.get("guaranteed"));
    }

    @Test void lostResponseRecoversTheSameRequest() {
        String versionId = frozen(1);
        RewardPledgeService.Claim claim = request(versionId, 11L, "same");
        assertEquals("NO_AUTHORITATIVE_FACT", assertThrows(PledgeRejection.class, () -> service.claim(claim)).code);
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT state FROM w17_reward_intent", String.class));
        assertEquals(0, count("w17_reward_award"));
        complete(11L, 100L);
        Map<String, Object> recovered = service.recover(11L, "same");
        assertEquals("AWARDED", recovered.get("state"));
        assertEquals(recovered.get("awardId"), service.recover(11L, "same").get("awardId"));
        assertEquals(1, service.dispatchOutbox((String) recovered.get("awardId")).get("attempt"));
        assertEquals(1, service.dispatchOutbox((String) recovered.get("awardId")).get("attempt"));
        assertEquals(1, count("sms_coupon_history"));
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class, () -> service.recover(11L, "other")).code);
    }

    @Test void everyWritePointRollsBackThenRecoverGrantsOnce() {
        for (String point : new String[]{"intent", "reserve", "award", "coupon", "receipt", "outbox", "settle", "intent-update"}) {
            jdbc.update("DELETE FROM w17_reward_case");
            jdbc.update("DELETE FROM w17_reward_outbox");
            jdbc.update("DELETE FROM w17_reward_receipt");
            jdbc.update("DELETE FROM w17_reward_award");
            jdbc.update("DELETE FROM w17_reward_intent");
            jdbc.update("DELETE FROM w17_reward_stock");
            jdbc.update("DELETE FROM w17_reward_pledge_version");
            jdbc.update("DELETE FROM w17_reward_pledge");
            jdbc.update("DELETE FROM sms_coupon_history");
            jdbc.update("DELETE FROM topic_route_session");
            jdbc.update("UPDATE sms_coupon SET receive_count=0, use_count=0");
            String versionId = frozen(1);
            complete(11L, 100L);
            gate.point = point;
            RewardPledgeService.Claim claim = request(versionId, 11L, "fault-" + point);
            assertThrows(IllegalStateException.class, () -> service.claim(claim), point);
            Stock afterFault = stock(versionId);
            assertEquals(1, afterFault.available, point);
            assertEquals(0, afterFault.reserved, point);
            assertEquals(0, count("w17_reward_award"), point);
            assertEquals(0, count("sms_coupon_history"), point);
            gate.point = null;
            if ("intent".equals(point)) {
                assertEquals(0, count("w17_reward_intent"));
                Map<String, Object> granted = service.claim(claim);
                assertEquals("AWARDED", granted.get("state"));
            } else {
                assertEquals("UNKNOWN", jdbc.queryForObject("SELECT state FROM w17_reward_intent WHERE request_id=?", String.class, "fault-" + point));
                Map<String, Object> granted = service.recover(11L, "fault-" + point);
                assertEquals("AWARDED", granted.get("state"));
                assertEquals("fault-" + point, granted.get("requestId"));
            }
            assertEquals(1, count("w17_reward_award"), point);
            assertEquals(1, count("sms_coupon_history"), point);
            assertEquals(0, stock(versionId).available, point);
            assertEquals(1, stock(versionId).sum(), point);
        }
    }

    @Test void withdrawKeepsTheAwardAndRedeemBeatsExpire() {
        String versionId = frozen(2);
        complete(11L, 100L);
        complete(12L, 100L);
        Map<String, Object> awarded = claim(versionId, 11L, "keep");
        service.withdraw(7L, 50L, versionId);
        Stock withdrawn = stock(versionId);
        assertEquals(0, withdrawn.available);
        assertEquals(1, withdrawn.withdrawn);
        assertEquals(1, withdrawn.awardedUnredeemed);
        assertEquals(withdrawn.quantity, withdrawn.sum());
        assertEquals(1, count("sms_coupon_history"));
        Map<String, Object> blocked = claim(versionId, 12L, "late");
        assertEquals("REFUSED", blocked.get("state"));
        assertEquals("PLEDGE_NOT_FROZEN", blocked.get("errorCode"));
        String live = frozen(1);
        complete(13L, 200L);
        Map<String, Object> second = claim(live, 13L, "redeem-me");
        String awardId = (String) second.get("awardId");
        Map<String, Object> redeemed = service.fulfil(7L, 60L, "MERCHANT_OWNER", awardId);
        Map<String, Object> again = service.fulfil(7L, 60L, "MERCHANT_OWNER", awardId);
        assertEquals("REDEEMED", redeemed.get("redemption"));
        assertEquals("ALREADY_USED", again.get("redemption"));
        assertEquals(1, stock(live).redeemed);
        assertEquals(0, stock(live).expired);
        Map<String, Object> expiredAttempt = service.expire(7L, 50L, awardId, CLOCK.millis());
        assertEquals(Boolean.FALSE, expiredAttempt.get("expiredNow"));
        assertEquals(1, stock(live).redeemed);
        assertEquals("FORBIDDEN", assertThrows(PledgeRejection.class, () -> service.fulfil(8L, 60L, "MERCHANT_OWNER", awardId)).code);
        assertEquals("POLICY_CLOSED", assertThrows(PledgeRejection.class, () -> service.compensate(7L, 50L, request(live, 13L, "comp"), awardId, "k1")).code);
        assertEquals(5, jdbc.queryForObject("SELECT stock FROM pms_sku WHERE id=90", Integer.class));
    }

    @Test void expiryTakesTheUnusedCouponOnce() throws Exception {
        String versionId = frozen(1);
        complete(11L, 100L);
        Map<String, Object> awarded = claim(versionId, 11L, "old");
        String awardId = (String) awarded.get("awardId");
        jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?", new Date(1_000L), awarded.get("historyId"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        try {
            pool.submit(() -> run(start, () -> service.expire(7L, 50L, awardId, 5_000L), failure));
            pool.submit(() -> run(start, () -> service.fulfil(7L, 60L, "MERCHANT_OWNER", awardId), failure));
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        Stock stock = stock(versionId);
        assertEquals(1, stock.expired);
        assertEquals(0, stock.redeemed);
        assertEquals(0, stock.awardedUnredeemed);
        assertEquals(stock.quantity, stock.sum());
        assertEquals(2, coupons.useStatus(((Number) awarded.get("historyId")).longValue()));
    }

    @Test void cityBranchAndClosedPolicyDoNotGrant() {
        String versionId = frozen(1);
        complete(11L, 100L);
        RewardPledgeService.Claim city = request(versionId, 11L, "city");
        city.branch = "CITY";
        Map<String, Object> refused = service.claim(city);
        assertEquals("NO_AUTHORITATIVE_FACT", refused.get("errorCode"));
        jdbc.update("DELETE FROM w17_reward_policy WHERE policy_code='CLAIM'");
        Map<String, Object> closed = claim(versionId, 11L, "no-policy");
        assertEquals("POLICY_CLOSED", closed.get("errorCode"));
        assertEquals(0, count("w17_reward_award"));
        RewardPledgeService.Draft missingWindow = baseDraft(1);
        missingWindow.redeemEnd = 0;
        assertEquals("INVALID_INPUT", assertThrows(PledgeRejection.class, () -> service.draft(missingWindow)).code);
    }

    @Test void revisionReturnAndCompensationKeepOneLedger() {
        String original = frozen(1);
        complete(11L, 100L);
        Map<String, Object> awarded = claim(original, 11L, "keep-terms");
        String awardId = (String) awarded.get("awardId");
        assertEquals("in-store non-cash", awarded.get("terms"));
        String pledgeId = jdbc.queryForObject("SELECT pledge_id FROM w17_reward_pledge_version WHERE version_id=?", String.class, original);
        RewardPledgeService.Draft edited = baseDraft(2);
        edited.terms = "revised terms";
        service.revise(7L, pledgeId, edited);
        assertEquals("in-store non-cash", service.detail(11L, awardId).get("terms"));
        assertEquals(1, stock(original).awardedUnredeemed);
        service.submit(7L, 50L, pledgeId);
        service.confirm(7L, 50L, pledgeId);
        service.freeze(80L, pledgeId, "rule-approved");
        String revised = jdbc.queryForObject(
                "SELECT version_id FROM w17_reward_pledge_version WHERE pledge_id=? AND state='FROZEN' ORDER BY version_no DESC LIMIT 1",
                String.class, pledgeId);
        assertEquals(2, stock(revised).quantity);
        assertEquals(1, stock(original).sum());
        jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?", new Date(1_000L), awarded.get("historyId"));
        service.expire(7L, 50L, awardId, redeemStart + 1);
        assertEquals("POLICY_CLOSED", assertThrows(PledgeRejection.class, () -> service.returnToPool(7L, 50L, awardId, redeemStart + 1)).code);
        assertEquals(1, stock(original).expired);
        policy("RETURN_TO_POOL", "", "OPEN", null);
        Map<String, Object> returned = service.returnToPool(7L, 50L, awardId, redeemStart + 1);
        assertEquals("RETURNED", returned.get("state"));
        assertEquals(Boolean.TRUE, returned.get("historyStillPresent"));
        assertEquals(1, stock(original).available);
        assertEquals(0, stock(original).expired);
        assertEquals(1, count("sms_coupon_history"));
        Map<String, Object> returnReplay = service.returnToPool(7L, 50L, awardId, redeemStart + 1);
        assertEquals("APPLIED", receipt(returnReplay).get("state"));
        assertEquals(1, stock(original).available);
        String other = frozen(1);
        complete(12L, 100L);
        Map<String, Object> second = claim(other, 12L, "window");
        jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?", new Date(1_000L), second.get("historyId"));
        service.expire(7L, 50L, (String) second.get("awardId"), redeemStart + 1);
        assertEquals("WINDOW_CLOSED", assertThrows(PledgeRejection.class,
                () -> service.returnToPool(7L, 50L, (String) second.get("awardId"), redeemEnd)).code);
        assertEquals(1, stock(other).expired);

        String held = frozen(2);
        complete(14L, 100L);
        Map<String, Object> heldAward = claim(held, 14L, "held");
        jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?", new Date(1_000L), heldAward.get("historyId"));
        service.expire(7L, 50L, (String) heldAward.get("awardId"), redeemStart + 1);
        policy("STORE_HOLD", "", "OPEN", null);
        service.hold(7L, 50L, held);
        assertEquals("CONFLICT", assertThrows(PledgeRejection.class, () -> service.returnToPool(7L, 50L, (String) heldAward.get("awardId"), redeemStart + 1)).code);
        assertEquals(1, stock(held).expired);
        assertEquals(1, stock(held).frozenUnits);

        policy("COMPENSATION", "", "OPEN", null);
        assertEquals("NOT_NEW_SUPPLY", assertThrows(PledgeRejection.class,
                () -> service.compensate(7L, 50L, request(original, 11L, "same-supply"), awardId, "k1")).code);
        RewardPledgeService.Claim compensation = request(revised, 99L, "comp-1");
        Map<String, Object> replaced = service.compensate(7L, 50L, compensation, awardId, "k1");
        assertEquals("AWARDED", replaced.get("state"));
        assertEquals(11L, jdbc.queryForObject("SELECT beneficiary_id FROM w17_reward_award WHERE award_id=?", Long.class, replaced.get("awardId")));
        Map<String, Object> replay = service.compensate(7L, 50L, request(revised, 99L, "comp-2"), awardId, "k1");
        assertEquals(replaced.get("awardId"), replay.get("awardId"));
        assertEquals(1, stock(revised).awardedUnredeemed);
        assertEquals(1, stock(revised).available);

        service.fulfil(7L, 60L, "MERCHANT_OWNER", (String) replaced.get("awardId"));
        assertEquals("POLICY_CLOSED", assertThrows(PledgeRejection.class, () -> service.reverse(70L, (String) replaced.get("awardId"), "wrong scan")).code);
        policy("REVERSE", "", "OPEN", null);
        Map<String, Object> reversed = service.reverse(70L, (String) replaced.get("awardId"), "wrong scan");
        assertEquals("REVERSED", reversed.get("state"));
        assertEquals(Boolean.TRUE, reversed.get("awardStillPresent"));
        assertEquals(1, stock(revised).redeemed);
        assertEquals(0, coupons.useStatus(((Number) replaced.get("historyId")).longValue()));
        assertEquals("CASH_CLOSED", assertThrows(PledgeRejection.class, () -> service.openCase(11L, revised, (String) replaced.get("awardId"), "CASH_REFUND")).code);
        assertEquals(5, jdbc.queryForObject("SELECT stock FROM pms_sku WHERE id=90", Integer.class));
    }

    @Test void revokedStaffCannotRedeem() {
        String versionId = frozen(1);
        complete(11L, 100L);
        String awardId = (String) claim(versionId, 11L, "staff").get("awardId");
        AccessGate access = new AccessGate();
        ApiRewardPledgeController controller = new ApiRewardPledgeController(service, access) {
            @Override public Long getAppUserId() { return 60L; }
        };
        access.revoked = true;
        assertEquals(403, controller.redeem(redeemBody(awardId, true)).get(AjaxResult.CODE_TAG));
        assertEquals(1, stock(versionId).awardedUnredeemed);
        access.revoked = false;
        assertEquals(200, controller.preview(redeemBody(awardId, false)).get(AjaxResult.CODE_TAG));
        assertEquals(1, stock(versionId).awardedUnredeemed);
        assertEquals(400, controller.redeem(redeemBody(awardId, false)).get(AjaxResult.CODE_TAG));
        assertEquals(200, controller.redeem(redeemBody(awardId, true)).get(AjaxResult.CODE_TAG));
        AjaxResult replay = controller.redeem(redeemBody(awardId, true));
        assertEquals("ALREADY_USED", map(replay).get("redemption"));
        assertEquals(1, stock(versionId).redeemed);
        assertEquals("FORBIDDEN", assertThrows(PledgeRejection.class, () -> service.fulfil(8L, 60L, "MERCHANT_OWNER", awardId)).code);
        assertEquals(1, stock(versionId).redeemed);
        assertEquals("FORBIDDEN", assertThrows(PledgeRejection.class, () -> service.readPledge(8L, pledgeOf(versionId))).code);
    }

    // ---- App contract (Core/RewardPledge.swift, Core/NonCashRewardService.swift) ----

    @Test void draftReceiptReplaysAndReadsBackWithTheAppDigest() {
        RewardPledgeService.Draft draft = baseDraft(3);
        Map<String, Object> created = service.draft(draft);
        String key = draft.requestId;
        String appDigest = RewardPledgeService.sha(couponId + "|3|" + redeemStart + "|" + redeemEnd + "|" + redeemStart + "|" + redeemEnd
                + "|in-store non-cash|0|coupon:1");
        assertEquals("APPLIED", receipt(created).get("state"));
        assertEquals("draft", receipt(created).get("op"));
        assertEquals(key, receipt(created).get("key"));
        assertEquals(key, receipt(created).get("pledgeId"));
        assertEquals(appDigest, receipt(created).get("payloadHash"));
        Map<String, Object> replay = service.draft(draft);
        assertEquals(receipt(created).get("payloadHash"), receipt(replay).get("payloadHash"));
        assertEquals(1, count("w17_reward_pledge"));
        Map<String, Object> read = service.readOp(7L, "draft", key);
        assertEquals(appDigest, receipt(read).get("payloadHash"));
        assertEquals(key, ((com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Pledge) read.get("pledge")).pledgeId);
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class, () -> service.readOp(8L, "draft", key)).code);
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class, () -> service.readOp(7L, "delete", key)).code);
        draft.quantity = 4;
        assertEquals("CONFLICT", assertThrows(PledgeRejection.class, () -> service.draft(draft)).code);
        assertEquals(1, count("w17_reward_pledge"));
        RewardPledgeService.Draft cash = baseDraft(1);
        cash.rewardKind = "CASH";
        assertEquals("CASH_CLOSED", assertThrows(PledgeRejection.class, () -> service.draft(cash)).code);
    }

    @Test void submitAndConfirmReceiptsNameTheVersionTheMerchantSaw() {
        RewardPledgeService.Draft draft = baseDraft(1);
        Map<String, Object> created = service.draft(draft);
        String pledgeId = draft.requestId;
        String draftVersion = (String) receipt(created).get("versionId");
        Map<String, Object> submitted = service.submit(7L, 50L, pledgeId);
        assertEquals(draftVersion, receipt(submitted).get("key"));
        assertEquals(draftVersion, receipt(submitted).get("versionId"));
        assertEquals(pledgeId, receipt(submitted).get("pledgeId"));
        assertEquals(RewardPledgeService.sha("submit|" + pledgeId + "|" + draftVersion), receipt(submitted).get("payloadHash"));
        String submittedVersion = ((com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Version) submitted.get("version")).versionId;
        assertNotEquals(draftVersion, submittedVersion);
        Map<String, Object> confirmed = service.confirm(7L, 51L, pledgeId);
        assertEquals(submittedVersion, receipt(confirmed).get("key"));
        assertEquals(RewardPledgeService.sha("confirm|" + pledgeId + "|" + submittedVersion), receipt(confirmed).get("payloadHash"));
        assertEquals("APPLIED", receipt(service.readOp(7L, "confirm", submittedVersion)).get("state"));
        assertEquals("FORBIDDEN", assertThrows(PledgeRejection.class, () -> service.submit(8L, 50L, pledgeId)).code);
        Map<String, Object> queue = service.reviewQueue(80L);
        assertEquals("PLATFORM_REVIEW", queue.get("store"));
        assertEquals(1, ((java.util.List<?>) queue.get("items")).size());
        assertEquals("POLICY_CLOSED", assertThrows(PledgeRejection.class, () -> service.reviewQueue(81L)).code);
        service.freeze(80L, pledgeId, "rule-approved");
        Map<String, Object> reviewed = service.reviewVersion(80L, submittedVersion);
        assertEquals("FROZEN", reviewed.get("state"));
        assertEquals(submittedVersion, reviewed.get("versionId"));
        Map<String, Object> owned = service.ownedPledges(7L);
        assertEquals(7L, owned.get("store"));
        assertEquals(1, ((java.util.List<?>) owned.get("pledges")).size());
        assertEquals(0, ((java.util.List<?>) service.ownedPledges(8L).get("pledges")).size());
    }

    @Test void aRejectedWriteLeavesARejectedReceiptAndARetryReplacesIt() {
        String versionId = frozen(1);
        service.withdraw(7L, 50L, versionId);
        String draftOnly = (String) receipt(service.draft(baseDraft(1))).get("versionId");
        assertEquals("CONFLICT", assertThrows(PledgeRejection.class, () -> service.withdraw(7L, 50L, draftOnly)).code);
        Map<String, Object> stored = receipt(service.readOp(7L, "withdraw", draftOnly));
        assertEquals("REJECTED", stored.get("state"));
        assertEquals("CONFLICT", stored.get("errorCode"));
        assertEquals(RewardPledgeService.sha("withdraw|" + draftOnly), stored.get("payloadHash"));
        assertEquals("APPLIED", receipt(service.readOp(7L, "withdraw", versionId)).get("state"));
        assertEquals("APPLIED", receipt(service.withdraw(7L, 50L, versionId)).get("state"));
        assertEquals(1, stock(versionId).withdrawn);
    }

    @Test void playerListPageAndDetailMatchBothAppDecoders() {
        String versionId = frozen(3);
        complete(11L, 100L);
        complete(11L, 200L);
        String first = (String) claim(versionId, 11L, "p-1").get("awardId");
        RewardPledgeService.Claim other = request(versionId, 11L, "p-2");
        other.subjectId = "200";
        String second = (String) service.claim(other).get("awardId");
        assertTrue(NonCashWire.isAwardId(first));
        java.util.List<Map<String, Object>> rows = service.listMine(11L);
        assertEquals(2, rows.size());
        for (Map<String, Object> row : rows) {
            for (String key : new String[]{"awardId", "versionId", "ruleVersion", "rewardScope", "factRef", "state", "requestId"}) {
                assertTrue(row.get(key) instanceof String && !((String) row.get(key)).isEmpty(), key);
            }
        }
        assertTrue(service.listMine(12L).isEmpty());
        Map<String, Object> page = service.pageMine(11L, 1, null);
        java.util.List<?> items = (java.util.List<?>) page.get("items");
        assertEquals(1, items.size());
        String lower = first.compareTo(second) < 0 ? first : second;
        String upper = first.compareTo(second) < 0 ? second : first;
        assertEquals(lower, page.get("nextCursor"));
        @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) items.get(0);
        assertEquals(lower, item.get("awardId"));
        assertEquals("PHYSICAL", item.get("rewardKind"));
        assertEquals(1, item.get("quantity"));
        assertEquals("AWARDED", item.get("state"));
        assertEquals("UPCOMING", item.get("validityStatus"), "the fixed clock (2023) is before the coupon window (2026)");
        assertEquals("UNVERIFIED", item.get("fulfillmentStatus"));
        assertEquals("50", item.get("merchantId"));
        assertEquals("7", item.get("storeId"));
        assertEquals(page.get("asOf"), item.get("asOf"));
        Map<String, Object> last = service.pageMine(11L, 1, lower);
        assertNull(last.get("nextCursor"));
        assertTrue(last.containsKey("nextCursor"));
        assertEquals(upper, ((Map<?, ?>) ((java.util.List<?>) last.get("items")).get(0)).get("awardId"));
        assertEquals("INVALID_INPUT", assertThrows(PledgeRejection.class, () -> service.pageMine(11L, 51, null)).code);
        Map<String, Object> detail = service.nonCashDetail(11L, lower, "THEME", (String) item.get("contextId"),
                versionId, (String) item.get("instanceId"));
        assertEquals(item.get("awardedAt"), detail.get("awardedAt"));
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class,
                () -> service.nonCashDetail(11L, lower, "MAP", (String) item.get("contextId"), versionId, (String) item.get("instanceId"))).code);
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class,
                () -> service.nonCashDetail(12L, lower, "THEME", (String) item.get("contextId"), versionId, (String) item.get("instanceId"))).code);
        assertEquals("AWARDED", service.detail(11L, first).get("state"));
    }

    @Test void catalogAndOfferNameTheSubjectAndRefuseAsData() {
        String versionId = frozen(2);
        Map<String, Object> catalog = service.catalog(11L, "100");
        assertEquals("100", catalog.get("subject"));
        assertEquals(versionId, catalog.get("versionId"));
        assertEquals("rule-approved", catalog.get("ruleVersion"));
        assertEquals(7L, catalog.get("store"));
        assertEquals(couponId, ((Number) catalog.get("coupon")).longValue());
        assertEquals("W06_RESERVATION_ABSENT", catalog.get("blocked"));
        assertEquals(Boolean.FALSE, catalog.get("guaranteed"));
        assertNull(catalog.get("errorCode"));
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class, () -> service.catalog(11L, "300")).code);
        assertEquals("NO_AUTHORITATIVE_FACT", service.offer(11L, versionId, "THEME", "100").get("errorCode"));
        complete(11L, 100L);
        Map<String, Object> offer = service.offer(11L, versionId, "THEME", "100");
        assertNull(offer.get("errorCode"));
        assertEquals("THEME:100:session:210", offer.get("factRef"));
        assertEquals("PLEDGE:" + versionId, offer.get("rewardScope"));
        assertEquals(2, offer.get("available"));
        complete(11L, 300L);
        assertEquals("SUBJECT_NOT_OFFERED", service.offer(11L, versionId, "THEME", "300").get("errorCode"));
        RewardPledgeService.Claim unrelated = request(versionId, 11L, "unrelated");
        unrelated.subjectId = "300";
        assertEquals("SUBJECT_NOT_OFFERED", service.claim(unrelated).get("errorCode"));
        jdbc.update("DELETE FROM w17_reward_policy WHERE policy_code='CLAIM'");
        assertEquals("POLICY_CLOSED", service.offer(11L, versionId, "THEME", "100").get("errorCode"));
        assertEquals("POLICY_CLOSED", service.catalog(11L, "100").get("errorCode"));
        assertEquals("INVALID_INPUT", assertThrows(PledgeRejection.class, () -> service.offer(11L, versionId, "CITY", "100")).code);
    }

    @Test void storeAwardsResolveAndCasesStayInTheirScope() {
        String versionId = frozen(1);
        complete(11L, 100L);
        Map<String, Object> awarded = claim(versionId, 11L, "case-me");
        String awardId = (String) awarded.get("awardId");
        assertEquals(1, service.storeAwards(7L).size());
        assertTrue(service.storeAwards(8L).isEmpty());
        Map<String, Object> resolved = service.resolve(7L, "hid:" + awarded.get("historyId"));
        assertEquals(awardId, resolved.get("awardId"));
        assertEquals(Boolean.TRUE, resolved.get("previewOnly"));
        assertEquals("FORBIDDEN", assertThrows(PledgeRejection.class, () -> service.resolve(8L, "hid:" + awarded.get("historyId"))).code);
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class, () -> service.resolve(7L, "garbage")).code);
        Map<String, Object> preview = service.preview(7L, awardId);
        assertEquals("AWARDED", preview.get("state"));
        assertEquals(0, preview.get("useStatus"));
        assertEquals(redeemEnd, preview.get("serviceEnd"));
        Map<String, Object> opened = service.openCase(11L, versionId, awardId, "STORE_REFUSED");
        String caseId = (String) opened.get("caseId");
        Map<String, Object> read = service.readCase(11L, caseId);
        assertEquals(awardId, read.get("awardId"));
        assertEquals("OPEN", read.get("state"));
        assertEquals("STORE_REFUSED", read.get("reason"));
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class, () -> service.readCase(12L, caseId)).code);
        assertEquals("NOT_FOUND", assertThrows(PledgeRejection.class, () -> service.openCase(12L, versionId, awardId, "STORE_REFUSED")).code);
        assertEquals("CASH_CLOSED", assertThrows(PledgeRejection.class, () -> service.openCase(11L, versionId, awardId, "CASH_REFUND")).code);
        RewardPledgeService cashOpen = new RewardPledgeService(new DataSourceTransactionManager(dataSource),
                session.getMapper(RewardPledgeMapper.class), coupons, CLOCK, null, new CashPayoutSwitch(true, true));
        assertEquals("CASH_REFUND", cashOpen.openCase(11L, versionId, awardId, "CASH_REFUND").get("reason"));
        assertEquals("CASH_CLOSED", assertThrows(PledgeRejection.class, () -> new RewardPledgeService(new DataSourceTransactionManager(dataSource),
                session.getMapper(RewardPledgeMapper.class), coupons, CLOCK, null, new CashPayoutSwitch(true, false))
                .openCase(11L, versionId, awardId, "CASH_REFUND")).code);
        Map<String, Object> expired = service.expire(7L, 50L, awardId, CLOCK.millis());
        assertEquals("expire", receipt(expired).get("op"));
        assertEquals(awardId, receipt(expired).get("awardId"));
        assertEquals("FORBIDDEN", assertThrows(PledgeRejection.class, () -> service.expire(8L, 50L, awardId, CLOCK.millis())).code);
    }

    @Test void controllersAnswer401SignedOutAnd403FeatureClosedForCash() {
        ApiNonCashRewardController signedOut = new ApiNonCashRewardController(service) {
            @Override public Long getAppUserId() { throw new ServiceException("login", 401); }
        };
        assertEquals(401, signedOut.list(null, null).get(AjaxResult.CODE_TAG));
        assertEquals(401, signedOut.catalog("100").get(AjaxResult.CODE_TAG));
        ApiNonCashRewardController player = new ApiNonCashRewardController(service) {
            @Override public Long getAppUserId() { return 11L; }
        };
        String versionId = frozen(1);
        complete(11L, 100L);
        String awardId = (String) claim(versionId, 11L, "ctl").get("awardId");
        Map<String, String> cash = new LinkedHashMap<String, String>();
        cash.put("awardId", awardId);
        cash.put("versionId", versionId);
        cash.put("reason", "CASH_REFUND");
        AjaxResult closed = player.openCase(cash);
        assertEquals(403, closed.get(AjaxResult.CODE_TAG));
        assertEquals("CASH_CLOSED", closed.get(AjaxResult.MSG_TAG));
        assertEquals(CashPayoutSwitch.FEATURE_CLOSED, closed.get("errorCode"));
        assertTrue(player.list(null, null).get(AjaxResult.DATA_TAG) instanceof java.util.List);
        assertTrue(player.list(10, null).get(AjaxResult.DATA_TAG) instanceof Map);
        assertEquals(404, player.detail("nope", null, null, null, null).get(AjaxResult.CODE_TAG));
        AccessGate access = new AccessGate();
        ApiRewardPledgeController merchant = new ApiRewardPledgeController(service, access) {
            @Override public Long getAppUserId() { return 50L; }
        };
        assertEquals(200, merchant.storeAwards().get(AjaxResult.CODE_TAG));
        assertEquals(200, merchant.owned().get(AjaxResult.CODE_TAG));
        assertEquals(404, merchant.readOp("hold", "missing").get(AjaxResult.CODE_TAG));
        access.revoked = true;
        assertEquals(403, merchant.owned().get(AjaxResult.CODE_TAG));
    }

    private static Map<String, String> redeemBody(String awardId, boolean confirm) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("awardId", awardId);
        if (confirm) body.put("confirm", "true");
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(AjaxResult result) {
        return (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
    }

    private String pledgeOf(String versionId) {
        return jdbc.queryForObject("SELECT pledge_id FROM w17_reward_pledge_version WHERE version_id=?", String.class, versionId);
    }

    private Map<String, Object> claimAfter(CountDownLatch start, String versionId, long owner, String requestId) throws Exception {
        start.await();
        return claim(versionId, owner, requestId);
    }

    private static void run(CountDownLatch start, Runnable work, AtomicReference<Throwable> failure) {
        try {
            start.await();
            work.run();
        } catch (Throwable failed) {
            failure.compareAndSet(null, failed);
        }
    }

    private Map<String, Object> claim(String versionId, long owner, String requestId) {
        return service.claim(request(versionId, owner, requestId));
    }

    private RewardPledgeService.Claim request(String versionId, long owner, String requestId) {
        RewardPledgeService.Claim claim = new RewardPledgeService.Claim();
        claim.ownerId = owner;
        claim.requestId = requestId;
        claim.versionId = versionId;
        claim.branch = "THEME";
        claim.subjectId = "100";
        if (owner == 13L) claim.subjectId = "200";
        return claim;
    }

    private String frozen(int quantity) {
        RewardPledgeService.Draft draft = baseDraft(quantity);
        Map<String, Object> created = service.draft(draft);
        String pledgeId = ((com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Pledge) created.get("pledge")).pledgeId;
        service.submit(7L, 50L, pledgeId);
        service.confirm(7L, 50L, pledgeId);
        service.freeze(80L, pledgeId, "rule-approved");
        return jdbc.queryForObject(
                "SELECT version_id FROM w17_reward_pledge_version WHERE pledge_id=? AND state='FROZEN' ORDER BY version_no DESC LIMIT 1",
                String.class, pledgeId);
    }

    private RewardPledgeService.Draft baseDraft(int quantity) {
        RewardPledgeService.Draft draft = new RewardPledgeService.Draft();
        draft.requestId = "pledge-" + UUID.randomUUID();
        draft.storeId = 7L;
        draft.merchantMemberId = 50L;
        draft.couponId = couponId;
        draft.supplyRef = "coupon:1";
        draft.quantity = quantity;
        draft.serviceStart = redeemStart;
        draft.serviceEnd = redeemEnd;
        draft.redeemStart = redeemStart;
        draft.redeemEnd = redeemEnd;
        draft.terms = "in-store non-cash";
        draft.minorRestricted = false;
        draft.rewardKind = "NON_CASH";
        return draft;
    }

    private void complete(long memberId, long topicId) {
        jdbc.update("INSERT INTO topic_route_session(id, member_id, topic_id, status, completed_at) VALUES (?,?,?,1,?)",
                memberId * 10 + topicId, memberId, topicId, new Date(CLOCK.millis()));
    }

    private void offer(long merchantMemberId, long topicId) {
        jdbc.update("INSERT INTO merchant_chapter_offer(topic_id, chapter_id, merchant_id, status) VALUES (?,?,?,1)",
                topicId, topicId * 10, merchantMemberId);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> receipt(Map<String, Object> body) {
        return (Map<String, Object>) body.get("receipt");
    }

    private void policy(String code, String version, String decision, Long actor) {
        jdbc.update("INSERT INTO w17_reward_policy(policy_code, rule_version, decision, actor_member_id) VALUES (?,?,?,?)",
                code, version, decision, actor);
    }

    private Stock stock(String versionId) {
        return jdbc.queryForObject("SELECT quantity, available, reserved, awarded_unredeemed, redeemed, expired, frozen_units, withdrawn, version_no FROM w17_reward_stock WHERE version_id=?",
                (rs, n) -> {
                    Stock stock = new Stock();
                    stock.quantity = rs.getInt(1);
                    stock.available = rs.getInt(2);
                    stock.reserved = rs.getInt(3);
                    stock.awardedUnredeemed = rs.getInt(4);
                    stock.redeemed = rs.getInt(5);
                    stock.expired = rs.getInt(6);
                    stock.frozenUnits = rs.getInt(7);
                    stock.withdrawn = rs.getInt(8);
                    stock.versionNo = rs.getLong(9);
                    return stock;
                }, versionId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Path moduleRoot() {
        Path here = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(here.resolve("sql/migration_20261117_reward_pledge.sql"))) return here;
        return here.resolve("chengyinhub-admin");
    }

    private static void load(Configuration config, Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            new XMLMapperBuilder(input, config, path.toString(), config.getSqlFragments()).parse();
        }
    }

    static final class AccessGate implements IMerchantAccessService {
        boolean revoked;
        public MerchantAccessContext require(Long principalMemberId, String permission) {
            if (revoked || principalMemberId == null) throw new ServiceException("forbidden", 403);
            return new MerchantAccessContext(principalMemberId, 7L, 50L, "MERCHANT_OWNER");
        }
        public MerchantAccessContext resolve(Long principalMemberId) {
            return require(principalMemberId, MerchantPermission.VERIFY);
        }
    }

    static final class FaultGate implements WriteGate {
        String point;
        public void before(String name) {
            if (point != null && point.equals(name)) throw new IllegalStateException("FAULT:" + name);
        }
    }

    /** Runs the same coupon mapper statements the production history service uses. No settlement and no payment. */
    static final class MapperCouponPort implements PledgeCouponPort {
        private final SmsCouponMapper coupons;
        private final SmsCouponHistoryMapper histories;
        MapperCouponPort(SmsCouponMapper coupons, SmsCouponHistoryMapper histories) {
            this.coupons = coupons;
            this.histories = histories;
        }
        public CouponView load(long couponId) {
            com.chengyinhub.business.domain.SmsCoupon coupon = coupons.selectSmsCouponById(couponId);
            if (coupon == null) return null;
            return new CouponView(coupon.getId(), coupon.getMemberId(), coupon.getMerchantId(), coupon.getStartTime(), coupon.getEndTime());
        }
        public Long grantOnce(long memberId, long couponId) {
            if (coupons.tryIncrementIssuableReceiveCount(couponId) != 1) return null;
            com.chengyinhub.business.domain.SmsCoupon coupon = coupons.selectSmsCouponById(couponId);
            SmsCouponHistory history = new SmsCouponHistory();
            history.setMemberId(memberId);
            history.setCouponId(couponId);
            history.setCouponCode("COUTEST");
            history.setGetType(0);
            history.setGetTime(new Date());
            history.setUseStatus(0);
            history.setCreateTime(new Date());
            history.setStartTime(coupon.getStartTime());
            history.setEndTime(coupon.getEndTime());
            history.setDelFlag(0);
            if (histories.insertSmsCouponHistory(history) != 1 || history.getId() == null) {
                throw new IllegalStateException("history");
            }
            return history.getId();
        }
        public String redeem(long historyId, long couponOwnerMemberId, long merchantId, long actorMemberId, String actorRole) {
            SmsCouponHistory history = histories.selectSmsCouponHistoryByIdForUpdate(historyId);
            if (history == null) return "NOT_FOUND";
            Date now = new Date();
            if (history.getEndTime() != null && history.getEndTime().before(now)) {
                histories.expireUnusedCoupon(historyId, now);
                return "EXPIRED";
            }
            if (Integer.valueOf(1).equals(history.getUseStatus())) return "ALREADY_USED";
            if (histories.claimUnusedCoupon(historyId, now) != 1) {
                SmsCouponHistory current = histories.selectSmsCouponHistoryByIdForUpdate(historyId);
                if (current != null && Integer.valueOf(1).equals(current.getUseStatus())) return "ALREADY_USED";
                if (current != null && Integer.valueOf(2).equals(current.getUseStatus())) return "EXPIRED";
                return "CONFLICT";
            }
            if (coupons.incrementUseCount(history.getCouponId()) != 1) throw new IllegalStateException("use-count");
            return "REDEEMED";
        }
        public boolean expireUnused(long historyId, Date now) {
            return histories.expireUnusedCoupon(historyId, now) == 1;
        }
        public boolean reverse(long historyId, String reason, long operatorUserId) {
            return histories.reverseUsedCoupon(historyId, new Date()) == 1;
        }
        public int useStatus(long historyId) {
            SmsCouponHistory history = histories.selectSmsCouponHistoryById(historyId);
            return history == null || history.getUseStatus() == null ? -1 : history.getUseStatus();
        }
        /** Test credential: "hid:{historyId}" stands in for a /api/coupon/qr-token token. */
        public Long resolveCode(String code) {
            return code.startsWith("hid:") ? Long.valueOf(code.substring(4)) : null;
        }
    }
}
