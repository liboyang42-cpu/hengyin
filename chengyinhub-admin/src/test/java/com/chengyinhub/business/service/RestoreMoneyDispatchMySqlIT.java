package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.common.config.RestoreDispatchGuard;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Source only. A fixed restore-mode child uses the original canonical MyBatis/fact fixture.
 * It owns a distinct schema, never an existing restored/production database. No provider is invoked. */
class RestoreMoneyDispatchMySqlIT {
    @Test void actualRestoreStartupPreservesFinancialRowsAndNumberHighWaterWhileRetainingAnOriginalSuccess() throws Exception {
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_BASE"), "host must allocate the original isolated base");
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX"), "host must allocate the original isolated suffix");
        ProcessBuilder launch = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx512m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName());
        launch.environment().put("CHENGYIN_RESTORE_MODE", "true");
        launch.environment().put("CHENGYIN_TEST_MYSQL_DATABASE_BASE",
                System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_BASE") + "_rdispatch");
        launch.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process owned = launch.start();
        try {
            assertTrue(owned.waitFor(120, TimeUnit.SECONDS), "fixed restore admission SQL child timed out");
            assertEquals(0, owned.exitValue(), "fixed restore admission SQL contract/cleanup failed; "
                    + Probe.describeExit(owned.exitValue()));
        } finally {
            if (owned.isAlive()) {
                owned.destroyForcibly();
                assertTrue(owned.waitFor(10, TimeUnit.SECONDS), "owned SQL child did not exit; outer exact-schema cleanup required");
            }
        }
    }

    public static final class Probe {
        private enum Phase { ADMISSION, SCHEMA_CREATE, SCHEMA_RESET, SNAPSHOT_BEFORE,
            REFUSE_NEW_MONEY, SNAPSHOT_AFTER, RECORD_ORIGINAL, READBACK, CLEANUP }
        private static Phase phase = Phase.ADMISSION;
        private static final String[] FAILURE_TYPES = {"ASSERTION", "NULL_VALUE", "STATE", "ARGUMENT", "SQL", "OTHER"};
        private static int failureCode(Throwable failure) {
            int type = failure instanceof AssertionError ? 0 : failure instanceof NullPointerException ? 1
                    : failure instanceof IllegalStateException ? 2 : failure instanceof IllegalArgumentException ? 3
                    : failure instanceof java.sql.SQLException || failure instanceof org.springframework.dao.DataAccessException ? 4 : 5;
            return 20 + phase.ordinal() * 8 + type;
        }
        private static String describeExit(int exit) {
            if (exit == 0) return "childExit=0,phase=COMPLETE,type=NONE";
            int code = exit - 20;
            if (code < 0 || code / 8 >= Phase.values().length || code % 8 >= FAILURE_TYPES.length)
                return "childExit=" + exit + ",phase=UNKNOWN,type=UNKNOWN";
            return "childExit=" + exit + ",phase=" + Phase.values()[code / 8].name()
                    + ",type=" + FAILURE_TYPES[code % 8];
        }
        private static final List<String> MONEY_TABLES = List.of("cms_registration", "ums_member", "oms_ticket",
                "ums_member_points_detail", "ums_member_balance_detail", "refund_application",
                "wx_pay_order_intent", "cms_registration_payment_fact");
        public static void main(String[] args) {
            boolean created = false;
            boolean initializationStarted = false;
            int failedExit = 0;
            RegistrationFundsMybatisIntegrationTest original = null;
            MysqlIntegrationTestConfig config = null;
            JdbcTemplate admin = null;
            try {
                original = new RegistrationFundsMybatisIntegrationTest();
                config = MysqlIntegrationTestConfig.fromEnvironment("funds_core_it");
                admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        config.adminUrl(), config.user(), config.password()));
                assertEquals(0, args.length);
                assertTrue(RestoreDispatchGuard.isRestoreMode());
                assertEquals("1", System.getenv("B_PAYMENT_FACT_DDL_APPROVED"));
                assertEquals("1", System.getenv("A_REGISTRATION_PAYMENT_FACT_DDL_APPROVED"));
                assertTrue(config.databaseName().contains("_rdispatch_funds_core_it"));
                // The old factory has DROP/CREATE semantics. Prove this exact new name absent first.
                assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                        Integer.class, config.databaseName()));
                initializationStarted = true;
                phase = Phase.SCHEMA_CREATE;
                original.createDatabaseAndService(); created = true;
                phase = Phase.SCHEMA_RESET;
                original.resetSchema();
                DataSource source = (DataSource) ReflectionTestUtils.getField(original, "dataSource");
                JdbcTemplate db = new JdbcTemplate(source);
                ICmsRegistrationService registrations = (ICmsRegistrationService) ReflectionTestUtils.getField(original, "registrationService");
                IRegistrationOrderService orders = (IRegistrationOrderService) ReflectionTestUtils.getField(original, "registrationOrderService");
                RefundServiceImpl refunds = (RefundServiceImpl) ReflectionTestUtils.getField(original, "refundService");
                phase = Phase.SNAPSHOT_BEFORE;
                var before = financialRows(db);
                var highWater = highWater(db, config.databaseName());
                RegistrationRequest request = new RegistrationRequest();
                request.setOwnerType(2); request.setOwnerId(55L); request.setTicketId(9L); request.setIsUsePoint(0);
                request.setRequestId("restore-must-not-allocate"); request.setRealName("隔离恢复夹具"); request.setPhone("13800000000");
                phase = Phase.REFUSE_NEW_MONEY;
                assertThrows(RestoreDispatchGuard.Blocked.class, () -> orders.createOrReplay(7L, request));
                assertThrows(RestoreDispatchGuard.Blocked.class, () -> registrations.beginPaymentAttempt(11L, 7L));
                assertThrows(RestoreDispatchGuard.Blocked.class, () -> registrations.completeZeroAmountPayment("T20260724PAY"));
                assertThrows(RestoreDispatchGuard.Blocked.class, () -> registrations.cancelRegistration(11L, 7L, "actor"));
                RefundApplication fresh = new RefundApplication(); fresh.setSourceType("registration"); fresh.setSourceId(11L);
                fresh.setRefundAmount(java.math.BigDecimal.ONE);
                assertThrows(RestoreDispatchGuard.Blocked.class, () -> refunds.saveRefund(fresh));
                phase = Phase.SNAPSHOT_AFTER;
                assertEquals(before, financialRows(db));
                assertEquals(highWater, highWater(db, config.databaseName()));

                CmsRegistration row = registrations.selectCmsRegistrationById(11L);
                assertEquals("T20260724PAY", row.getRegistrationNo());
                // This exact original is the factory's explicit historical, hashless snapshot.
                // The typed fact is a controlled fixture observation, not proof of live SDK verification.
                long cents = row.getWechatPaymentAmount().movePointRight(2).longValueExact();
                var facts = (WechatVerifiedPaymentFactService) ReflectionTestUtils.getField(original, "verifiedFacts");
                var observed = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, "mch-funds-it", "wx-funds-it",
                        row.getRegistrationNo(), row.getRegistrationNo(), row.getRegistrationNo(), "RESTORE-ORIGINAL-TX",
                        "RESTORE-ORIGINAL-TX", "JSAPI", "CNY", cents, cents, Instant.parse("2026-10-24T00:00:00Z"), null, "SUCCESS");
                phase = Phase.RECORD_ORIGINAL;
                var reference = facts.recordVerified(observed);
                Map<String, String> callback = new LinkedHashMap<>();
                callback.put("out_trade_no", row.getRegistrationNo()); callback.put("transaction_id", observed.transactionId());
                callback.put("trade_state", "SUCCESS"); callback.put("total", Long.toString(cents));
                callback.put("verified_fact_id", Long.toString(reference.id())); callback.put("verified_fact_hash", reference.hash());
                callback.put("wx_out_trade_no", observed.wxOutTradeNo()); callback.put("mchid", observed.mchid());
                callback.put("appid", observed.appid()); callback.put("trade_type", "JSAPI"); callback.put("currency", "CNY");
                phase = Phase.READBACK;
                assertEquals("FAIL", registrations.handleWechatPayCallback(callback));
                assertEquals("FAIL", registrations.handleWechatPayCallback(callback));
                assertEquals(observed, facts.requireVerified(reference).facts());
                assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact WHERE id=? AND fact_hash=?",
                        Integer.class, reference.id(), reference.hash()));
                assertEquals(before, financialRows(db));
                // The original fixture's inbox/notification/G ports are unit boundaries. This test proves
                // real SQL facts and financial zero-effects, not SQL inbox DONE/SDK/auth or G atomicity.
            } catch (Throwable failure) {
                System.err.println("RESTORE_MONEY_SQL_CONTRACT_FAILED");
                failedExit = failureCode(failure);
            } finally {
                phase = Phase.CLEANUP;
                if (initializationStarted && !created) {
                    // The old factory may have created this exact schema before throwing. Never
                    // claim CLEAN or broaden cleanup to another name; the host owns fallback cleanup.
                    System.err.println("RESTORE_MONEY_SQL_CLEANUP_UNKNOWN");
                    if (failedExit == 0) failedExit = failureCode(new IllegalStateException());
                }
                if (created) {
                    try {
                        original.dropDatabase();
                        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                                Integer.class, config.databaseName()));
                    } catch (Throwable cleanupFailure) {
                        System.err.println("RESTORE_MONEY_SQL_CLEANUP_UNKNOWN");
                        if (failedExit == 0) failedExit = failureCode(cleanupFailure);
                    }
                }
            }
            if (failedExit != 0) System.exit(failedExit);
        }
        private static Map<String, List<Map<String, Object>>> financialRows(JdbcTemplate db) {
            Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
            for (String fixed : MONEY_TABLES) {
                String key = switch (fixed) {
                    case "cms_registration_payment_fact" -> "registration_id";
                    case "wx_pay_order_intent" -> "mchid,out_trade_no";
                    default -> "id";
                };
                result.put(fixed, db.queryForList("SELECT * FROM " + fixed + " ORDER BY " + key));
            }
            return result;
        }
        private static List<Map<String, Object>> highWater(JdbcTemplate db, String schema) {
            return db.queryForList("SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=? ORDER BY TABLE_NAME", schema);
        }
    }
}
