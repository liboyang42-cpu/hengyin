package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestorePaymentIntentIdentityTest {
    private CmsRegistration order() {
        var order = new CmsRegistration(); order.setId(12L); order.setRegistrationNo("ORIGINAL_ORDER");
        order.setWechatPaymentAmount(new BigDecimal("100.00")); return order;
    }
    private Map<String, Object> frozen() {
        var row = new LinkedHashMap<String, Object>();
        row.put("mchid", "mch-funds-it"); row.put("appid", "wx-funds-it"); row.put("out_trade_no", "ORIGINAL_ALIAS");
        row.put("business_out_trade_no", "ORIGINAL_ORDER"); row.put("biz_type", "REGISTRATION"); row.put("biz_id", 12L);
        row.put("attempt_no", 1); row.put("request_hash", "a".repeat(64)); row.put("trade_type", "JSAPI");
        row.put("currency", "CNY"); row.put("total_fen", 10000L); row.put("closed_confirmed_at", null); return row;
    }
    @Test
    void canonicalCompositeRowNeedsNoSurrogateIdOrBusinessIdSubstitute() {
        var row = frozen(); assertFalse(row.containsKey("id"));
        assertEquals(1, RestoreBusinessReconciliationFixture.frozenAttempt(row, order(), "ORIGINAL_ALIAS"));
        assertEquals(Long.valueOf(12), row.get("biz_id")); assertEquals(Integer.valueOf(1), row.get("attempt_no"));
    }
    @Test
    void anyMismatchOfOriginalCompositeKeyOwnerHashChannelOrMoneyRejects() {
        var changed = Map.<String, Object>ofEntries(Map.entry("mchid", "other"), Map.entry("appid", "other"),
            Map.entry("out_trade_no", "OTHER_ALIAS"), Map.entry("business_out_trade_no", "OTHER_ORDER"),
            Map.entry("biz_type", "COMMERCE"), Map.entry("biz_id", 13L), Map.entry("request_hash", "not-a-hash"),
            Map.entry("trade_type", "APP"), Map.entry("currency", "USD"), Map.entry("total_fen", 9999L),
            Map.entry("closed_confirmed_at", "closed"));
        for (var entry : changed.entrySet()) {
            var row = frozen(); row.put(entry.getKey(), entry.getValue());
            assertThrows(IllegalStateException.class,
                () -> RestoreBusinessReconciliationFixture.frozenAttempt(row, order(), "ORIGINAL_ALIAS"), entry.getKey());
        }
    }
    @Test
    void missingNonpositiveFractionalOrOverflowAttemptCannotBeInventedOrTruncated() {
        var missing = frozen(); missing.remove("attempt_no");
        assertThrows(IllegalStateException.class,
            () -> RestoreBusinessReconciliationFixture.frozenAttempt(missing, order(), "ORIGINAL_ALIAS"));
        for (int value : new int[] {0, -1}) {
            var row = frozen(); row.put("attempt_no", value);
            assertThrows(IllegalStateException.class,
                () -> RestoreBusinessReconciliationFixture.frozenAttempt(row, order(), "ORIGINAL_ALIAS"));
        }
        for (var value : new BigDecimal[] {new BigDecimal("1.5"), new BigDecimal("2147483648")}) {
            var row = frozen(); row.put("attempt_no", value);
            assertThrows(ArithmeticException.class,
                () -> RestoreBusinessReconciliationFixture.frozenAttempt(row, order(), "ORIGINAL_ALIAS"));
        }
    }

    @Test
    void callbackReasonMustBeFromTheExactOriginalAlarmEnvelope() {
        var alarm = org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.AlarmService.class);
        alarm.alarmThrottled("payment.callback.registration", "支付回调报名处理失败", "order=ORIGINAL reason=amount_mismatch result=FAIL");
        assertEquals(RestoreBusinessReconciliationFixture.CallbackReason.AMOUNT_MISMATCH,
                RestoreBusinessReconciliationFixture.observedCallbackReason(alarm, "ORIGINAL"));
        assertEquals(RestoreBusinessReconciliationFixture.CallbackReason.UNKNOWN,
                RestoreBusinessReconciliationFixture.observedCallbackReason(alarm, "OTHER"));
        org.mockito.Mockito.clearInvocations(alarm);
        alarm.alarmThrottled("payment.callback.registration", "支付回调报名处理失败", "order=ORIGINAL reason=PRIVATE_SECRET_CANARY result=FAIL");
        alarm.alarmThrottled("foreign", "支付回调报名处理失败", "order=ORIGINAL reason=amount_mismatch result=FAIL");
        assertEquals(RestoreBusinessReconciliationFixture.CallbackReason.UNKNOWN,
                RestoreBusinessReconciliationFixture.observedCallbackReason(alarm, "ORIGINAL"));
    }

    @Test
    void callbackDiagnosticPreservesMutationCauseWithoutPublishingPayloadOrArbitraryReturnValue() {
        var failure = new RestoreBusinessReconciliationFixture.CallbackRejected("PRIVATE_SECRET_CANARY",
            RestoreBusinessReconciliationFixture.CallbackReason.MUTATION_EXCEPTION, new NullPointerException("PRIVATE_SECRET_CANARY"));
        var observed = RestoreChildDiagnostics.describe(failure, RestoreChildDiagnostics.Stage.AFTER_BACKUP,
            Map.of("GITHUB_SHA", "a".repeat(40), "CHENGYIN_RESTORE_SCOPE", "b".repeat(32),
                   "CHENGYIN_RESTORE_BOOT", "c".repeat(32), "CHENGYIN_RESTORE_COHORT", "payment-gap"));
        assertEquals(Map.of("result", "OTHER", "reason", "MUTATION_EXCEPTION"), observed.get("callback"));
        assertEquals(java.util.List.of("java.lang.IllegalStateException", "java.lang.NullPointerException"), observed.get("causeTypes"));
        assertFalse(com.alibaba.fastjson2.JSON.toJSONString(observed).contains("PRIVATE"));
    }

    @Test
    void activityUsesOnlyExactExistingCanonicalTablesWithoutFabricatingTheirShape() throws Exception {
        String base = java.nio.file.Files.readString(java.nio.file.Path.of("../sql/base_schema_20260818.sql"));
        for (String table : java.util.List.of("settlement_claim", "settlement_detail", "settlement_rule")) {
            String ddl = RestoreBusinessReconciliationFixture.canonicalActivityTable(base, table);
            assertTrue(ddl.startsWith("CREATE TABLE `" + table + "` ("));
            assertTrue(base.contains(ddl)); assertTrue(ddl.endsWith(";"));
        }
        assertThrows(IllegalStateException.class,
            () -> RestoreBusinessReconciliationFixture.canonicalActivityTable(base, "ums_member"));
        assertThrows(IllegalStateException.class,
            () -> RestoreBusinessReconciliationFixture.canonicalActivityTable("truncated", "settlement_claim"));
    }
}
