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
        String sql = Files.readString(root.resolve("sql/migration_reward_pledge_candidate.sql"));
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
        service.withdraw(50L, versionId);
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
        Map<String, Object> expiredAttempt = service.expire(awardId, CLOCK.millis());
        assertEquals(Boolean.FALSE, expiredAttempt.get("expiredNow"));
        assertEquals(1, stock(live).redeemed);
        assertEquals("FORBIDDEN", assertThrows(PledgeRejection.class, () -> service.fulfil(8L, 60L, "MERCHANT_OWNER", awardId)).code);
        assertEquals("POLICY_CLOSED", assertThrows(PledgeRejection.class, () -> service.compensate(50L, request(live, 13L, "comp"), awardId, "k1")).code);
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
            pool.submit(() -> run(start, () -> service.expire(awardId, 5_000L), failure));
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
        service.revise(50L, pledgeId, edited);
        assertEquals("in-store non-cash", service.detail(11L, awardId).get("terms"));
        assertEquals(1, stock(original).awardedUnredeemed);
        service.submit(50L, pledgeId);
        service.confirm(50L, pledgeId);
        service.freeze(80L, pledgeId, "rule-approved");
        String revised = jdbc.queryForObject(
                "SELECT version_id FROM w17_reward_pledge_version WHERE pledge_id=? AND state='FROZEN' ORDER BY version_no DESC LIMIT 1",
                String.class, pledgeId);
        assertEquals(2, stock(revised).quantity);
        assertEquals(1, stock(original).sum());
        jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?", new Date(1_000L), awarded.get("historyId"));
        service.expire(awardId, redeemStart + 1);
        assertEquals("POLICY_CLOSED", assertThrows(PledgeRejection.class, () -> service.returnToPool(50L, awardId, redeemStart + 1)).code);
        assertEquals(1, stock(original).expired);
        policy("RETURN_TO_POOL", "", "OPEN", null);
        Map<String, Object> returned = service.returnToPool(50L, awardId, redeemStart + 1);
        assertEquals("RETURNED", returned.get("state"));
        assertEquals(Boolean.TRUE, returned.get("historyStillPresent"));
        assertEquals(1, stock(original).available);
        assertEquals(0, stock(original).expired);
        assertEquals(1, count("sms_coupon_history"));
        assertEquals("CONFLICT", assertThrows(PledgeRejection.class, () -> service.returnToPool(50L, awardId, redeemStart + 1)).code);
        String other = frozen(1);
        complete(12L, 100L);
        Map<String, Object> second = claim(other, 12L, "window");
        jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?", new Date(1_000L), second.get("historyId"));
        service.expire((String) second.get("awardId"), redeemStart + 1);
        assertEquals("WINDOW_CLOSED", assertThrows(PledgeRejection.class,
                () -> service.returnToPool(50L, (String) second.get("awardId"), redeemEnd)).code);
        assertEquals(1, stock(other).expired);

        String held = frozen(2);
        complete(14L, 100L);
        Map<String, Object> heldAward = claim(held, 14L, "held");
        jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?", new Date(1_000L), heldAward.get("historyId"));
        service.expire((String) heldAward.get("awardId"), redeemStart + 1);
        policy("STORE_HOLD", "", "OPEN", null);
        service.hold(50L, held);
        assertEquals("CONFLICT", assertThrows(PledgeRejection.class, () -> service.returnToPool(50L, (String) heldAward.get("awardId"), redeemStart + 1)).code);
        assertEquals(1, stock(held).expired);
        assertEquals(1, stock(held).frozenUnits);

        policy("COMPENSATION", "", "OPEN", null);
        assertEquals("NOT_NEW_SUPPLY", assertThrows(PledgeRejection.class,
                () -> service.compensate(50L, request(original, 11L, "same-supply"), awardId, "k1")).code);
        RewardPledgeService.Claim compensation = request(revised, 99L, "comp-1");
        Map<String, Object> replaced = service.compensate(50L, compensation, awardId, "k1");
        assertEquals("AWARDED", replaced.get("state"));
        assertEquals(11L, jdbc.queryForObject("SELECT beneficiary_id FROM w17_reward_award WHERE award_id=?", Long.class, replaced.get("awardId")));
        Map<String, Object> replay = service.compensate(50L, request(revised, 99L, "comp-2"), awardId, "k1");
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
        assertThrows(ServiceException.class, () -> controller.redeem(redeemBody(awardId, true)));
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
        service.submit(50L, pledgeId);
        service.confirm(50L, pledgeId);
        service.freeze(80L, pledgeId, "rule-approved");
        return jdbc.queryForObject(
                "SELECT version_id FROM w17_reward_pledge_version WHERE pledge_id=? AND state='FROZEN' ORDER BY version_no DESC LIMIT 1",
                String.class, pledgeId);
    }

    private RewardPledgeService.Draft baseDraft(int quantity) {
        RewardPledgeService.Draft draft = new RewardPledgeService.Draft();
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
        if (Files.isRegularFile(here.resolve("sql/migration_reward_pledge_candidate.sql"))) return here;
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
    }
}
