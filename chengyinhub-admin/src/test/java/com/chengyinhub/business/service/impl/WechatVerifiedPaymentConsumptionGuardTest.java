package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit unit boundaries; actual row locks and rollback require the separate MySQL suite. */
class WechatVerifiedPaymentConsumptionGuardTest {
    private final WechatVerifiedPaymentFactService service = new WechatVerifiedPaymentFactService();
    private final WechatVerifiedFactTestSupport.MemoryMapper facts = new WechatVerifiedFactTestSupport.MemoryMapper();
    private final Map<String,Object> current = new HashMap<>();
    private List<Map<String,Object>> attempts;

    @BeforeEach void fixture() {
        ReflectionTestUtils.setField(service, "mapper", facts);
        WechatVerifiedFactTestSupport.wireUnitReads(service);
        current.putAll(Map.of("mchid", "merchant", "business_out_trade_no", "REG1", "out_trade_no", "REG1",
                "appid", "wx-original", "trade_type", "APP", "currency", "CNY", "total_fen", 1000L,
                "request_hash", "a".repeat(64), "biz_type", "REGISTRATION", "biz_id", 1L));
        attempts = List.of(current);
        ReflectionTestUtils.setField(service, "factIntentJdbc", new JdbcTemplate() {
            @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                assertTrue(sql.contains("wx_pay_order_intent"));
                if (sql.startsWith("SELECT *")) {
                    assertTrue(sql.endsWith("ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE"));
                    assertArrayEquals(new Object[]{"REGISTRATION", 1L}, args);
                }
                return attempts;
            }
        });
    }

    @Test void rejectsOutsideTheOriginalTransactionBeforeReadingFacts() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertThrows(IllegalStateException.class, () -> service.requireConsumablePayment(new Reference(1, "a".repeat(64))));
        assertTrue(facts.rows.isEmpty());
    }

    @Test void originalOpenAttemptAndExactReplayKeepTheSameStoredReference() {
        Reference original = service.recordVerified(payment("REG1", "a".repeat(64)));
        withinTransaction(() -> {
            assertEquals(original, service.requireConsumablePayment(original).reference());
            assertEquals(original, service.requireConsumablePayment(original).reference());
        });
        assertEquals(1, facts.rows.size());
        assertTrue(facts.recoveries.isEmpty());
    }

    @Test void latestClosedOrConflictingIdentityNeverConsumesTheStoredSuccess() {
        Reference original = service.recordVerified(payment("REG1", "a".repeat(64)));
        Map<String,Object> replacements = Map.of("closed_confirmed_at", Instant.EPOCH,
                "out_trade_no", "ATTEMPT2", "mchid", "other-merchant", "business_out_trade_no", "REG2",
                "appid", "other-app", "trade_type", "JSAPI", "currency", "USD", "total_fen", 1001L,
                "request_hash", "b".repeat(64));
        replacements.forEach((field, value) -> {
            Object before = current.put(field, value);
            try {
                withinTransaction(() -> assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,
                        () -> service.requireConsumablePayment(original), field));
            } finally {
                if (before == null) current.remove(field); else current.put(field, before);
            }
        });
        attempts = List.of();
        withinTransaction(() -> assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,
                () -> service.requireConsumablePayment(original), "frozen intent must not become historical"));
        assertEquals(original, service.requireVerified(original).reference());
        assertEquals(1, facts.rows.size());
        assertTrue(facts.recoveries.isEmpty());
        assertTrue(facts.rejections.isEmpty());
    }

    @Test void historicalAdmissionRequiresTheActualOriginalProviderNumber() {
        attempts = List.of();
        Reference historical = service.recordVerified(payment("REG1", null));
        Reference otherProviderNumber = service.recordVerified(payment("OLD-WX", null));
        withinTransaction(() -> {
            assertEquals(historical, service.requireConsumablePayment(historical).reference());
            assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,
                    () -> service.requireConsumablePayment(otherProviderNumber));
        });
        assertEquals(2, facts.rows.size());
    }

    private WechatVerifiedPaymentFact payment(String providerNo, String hash) {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, "merchant", "wx-original",
                "REG1", providerNo, providerNo, "TX-" + providerNo, "TX-" + providerNo,
                "APP", "CNY", 1000, 1000, Instant.parse("2026-10-24T00:00:00Z"), hash, "SUCCESS");
    }

    private void withinTransaction(Runnable check) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { check.run(); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
