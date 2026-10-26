package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Fixed test-child failure evidence. Never serializes exception messages, SQL, stack frames or environment. */
public final class RestoreChildDiagnostics {
    public enum Stage { ADMISSION, SOURCE_IDENTITY, SOURCE_SCHEMA, SOURCE_RESET, SOURCE_ASSEMBLY,
        HISTORICAL_PAYMENT, SOURCE_TIMELINE, BACKUP_BOUNDARY, AFTER_BACKUP, FAULT_BOUNDARY, OWNER_OBSERVATION }
    private static volatile Stage stage = Stage.ADMISSION;
    private static final Set<String> ORIGINS = Set.of(
        "com.chengyinhub.business.service.RestoreBusinessReconciliationFixture",
        "com.chengyinhub.business.service.RegistrationFundsMybatisIntegrationTest",
        "com.chengyinhub.business.service.impl.RestoreWithdrawalReconciliationFixture",
        "com.chengyinhub.business.service.impl.RestoreRefundReconciliationFixture",
        "com.chengyinhub.business.service.impl.RefundRetryAndRemainderMySqlIT",
        "com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl",
        "com.chengyinhub.business.service.impl.SettlementEntryServiceImpl",
        "com.chengyinhub.business.service.impl.SettlementClaimServiceImpl",
        "com.chengyinhub.business.service.impl.RegistrationTopicFence",
        "com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService",
        "com.chengyinhub.business.service.PaymentCallbackInboxStubs",
        "com.chengyinhub.business.service.impl.FundsRecoveryProcessProbe$Store",
        "com.chengyinhub.business.service.ai.RestoreVoiceReconciliationFixture");
    private static final Set<String> SQL_STATES = Set.of("08001", "08003", "08004", "08006", "08S01", "28000", "42000",
        "42S01", "42S02", "42S21", "42S22", "23000", "22001", "22003", "HY000", "HY001", "HYT00", "HYT01", "40001");
    private static final Set<String> TYPES = Set.of("java.lang.IllegalArgumentException", "java.lang.IllegalStateException",
        "java.lang.NullPointerException", "java.lang.AssertionError", "java.lang.ClassNotFoundException",
        "java.lang.NoClassDefFoundError", "java.lang.ExceptionInInitializerError", "java.lang.reflect.InvocationTargetException",
        "java.io.IOException", "java.nio.file.NoSuchFileException", "java.sql.SQLException",
        "java.sql.SQLSyntaxErrorException", "java.sql.SQLIntegrityConstraintViolationException",
        "java.sql.SQLNonTransientConnectionException", "java.sql.SQLTransientConnectionException",
        "org.opentest4j.AssertionFailedError", "org.springframework.jdbc.BadSqlGrammarException",
        "org.springframework.jdbc.CannotGetJdbcConnectionException", "org.springframework.jdbc.datasource.init.ScriptStatementFailedException",
        "org.springframework.dao.DataIntegrityViolationException", "org.springframework.beans.factory.BeanCreationException",
        "com.chengyinhub.common.exception.ServiceException");
    private RestoreChildDiagnostics() { }
    public static void stage(Stage next) { stage = java.util.Objects.requireNonNull(next); }
    public static void report(Throwable failure) {
        System.err.println("RESTORE_CHILD_FAILURE_V1:" + JSON.toJSONString(describe(failure, stage, System.getenv()),
                com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        System.err.flush();
    }
    static Map<String, Object> describe(Throwable failure, Stage at, Map<String, String> env) {
        var result = new LinkedHashMap<String, Object>();
        result.put("protocol", "RESTORE_CHILD_FAILURE_V1"); result.put("stage", at.name());
        result.put("sourceSha", checked(env.get("GITHUB_SHA"), "[a-f0-9]{40}"));
        result.put("scope", checked(env.get("CHENGYIN_RESTORE_SCOPE"), "[a-f0-9]{32}"));
        result.put("boot", checked(env.get("CHENGYIN_RESTORE_BOOT"), "[a-f0-9]{32}"));
        String cohort = env.get("CHENGYIN_RESTORE_COHORT");
        result.put("cohort", Set.of("payment-gap", "withdrawal-complete", "withdrawal-gap", "refund-complete",
                "refund-gap", "voice-complete", "voice-gap").contains(cohort == null ? "" : cohort) ? cohort : "UNKNOWN");
        if (failure instanceof RestoreBusinessReconciliationFixture.CallbackRejected callback) {
            result.put("callback", Map.of("result", callback.outcome, "reason", callback.reason.name()));
        }
        if (failure instanceof com.chengyinhub.business.service.ai.RestoreVoiceReconciliationFixture.VoiceFixtureFailure voice) {
            result.put("voice", voice.observation());
        }
        var types = new ArrayList<String>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        String state = null; Integer code = null; Map<String, Object> origin = null;
        for (Throwable current = failure; current != null && types.size() < 8 && seen.add(current); current = current.getCause()) {
            String type = current instanceof RestoreBusinessReconciliationFixture.CallbackRejected
                    || current instanceof com.chengyinhub.business.service.ai.RestoreVoiceReconciliationFixture.VoiceFixtureFailure
                    ? "java.lang.IllegalStateException" : current.getClass().getName();
            types.add(TYPES.contains(type) ? type : "UNKNOWN");
            for (StackTraceElement frame : current.getStackTrace()) {
                if (ORIGINS.contains(frame.getClassName()) && !"require".equals(frame.getMethodName())
                        && frame.getLineNumber() > 0 && frame.getLineNumber() <= 100000) {
                    origin = Map.of("class", frame.getClassName(), "line", frame.getLineNumber()); break;
                }
            }
            if (current instanceof SQLException sql && state == null) {
                state = SQL_STATES.contains(sql.getSQLState() == null ? "" : sql.getSQLState()) ? sql.getSQLState() : null;
                if (state != null && sql.getErrorCode() >= 0 && sql.getErrorCode() <= 65535) code = sql.getErrorCode();
            }
        }
        result.put("causeTypes", types); result.put("sqlState", state); result.put("sqlErrorCode", code); result.put("origin", origin);
        return result;
    }
    private static String checked(String value, String pattern) {
        return value != null && value.matches(pattern) ? value : null;
    }
}
