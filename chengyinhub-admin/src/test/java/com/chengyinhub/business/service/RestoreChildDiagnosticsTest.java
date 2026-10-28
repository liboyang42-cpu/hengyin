package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestoreChildDiagnosticsTest {
    private Map<String, String> identity() {
        return Map.of("GITHUB_SHA", "a".repeat(40), "CHENGYIN_RESTORE_SCOPE", "b".repeat(32),
                "CHENGYIN_RESTORE_BOOT", "c".repeat(32), "CHENGYIN_RESTORE_COHORT", "payment-gap");
    }

    @Test
    void sqlCauseIsTypedWithoutMessagesQueriesCredentialsOrPrivatePaths() {
        String secret = "PRIVATE_SECRET_CANARY password/defaults/SQL/path";
        var sql = new SQLException(secret, "42S22", 1054);
        String source = "com.chengyinhub.business.service.RegistrationFundsMybatisIntegrationTest";
        sql.setStackTrace(new StackTraceElement[] {new StackTraceElement("PRIVATE_SECRET_CANARY", "secret", "private", 1),
                new StackTraceElement(source, "createDatabaseAndService", "PRIVATE_SECRET_CANARY", 132)});
        var failure = new IllegalStateException(secret, sql);
        var evidence = RestoreChildDiagnostics.describe(failure, RestoreChildDiagnostics.Stage.SOURCE_SCHEMA, identity());
        assertEquals("RESTORE_CHILD_FAILURE_V1", evidence.get("protocol"));
        assertEquals("SOURCE_SCHEMA", evidence.get("stage"));
        assertEquals(List.of("java.lang.IllegalStateException", "java.sql.SQLException"), evidence.get("causeTypes"));
        assertEquals("42S22", evidence.get("sqlState")); assertEquals(Integer.valueOf(1054), evidence.get("sqlErrorCode"));
        assertEquals("a".repeat(40), evidence.get("sourceSha")); assertEquals("c".repeat(32), evidence.get("boot"));
        assertFalse(JSON.toJSONString(evidence).contains("PRIVATE"));
        assertEquals(Map.of("class", source, "line", 132), evidence.get("origin"));
        assertEquals(10, evidence.size());
    }

    @Test
    void unreviewedTypesStatesAndInvalidIdentityAreNotEchoed() {
        var failure = new SQLException("PRIVATE_SECRET_CANARY", "SECRT", 1054) { };
        var evidence = RestoreChildDiagnostics.describe(failure, RestoreChildDiagnostics.Stage.ADMISSION,
                Map.of("GITHUB_SHA", "PRIVATE_SECRET_CANARY", "CHENGYIN_RESTORE_COHORT", "PRIVATE_SECRET_CANARY"));
        assertEquals(List.of("UNKNOWN"), evidence.get("causeTypes"));
        assertNull(evidence.get("sqlState")); assertNull(evidence.get("sqlErrorCode"));
        assertNull(evidence.get("sourceSha")); assertNull(evidence.get("scope")); assertNull(evidence.get("boot"));
        assertEquals("UNKNOWN", evidence.get("cohort"));
        assertFalse(JSON.toJSONString(evidence).contains("PRIVATE"));
    }

    @Test
    void fixedStageAndCauseBudgetNeverSerializeArbitraryThrowablePayload() {
        Throwable failure = new IllegalStateException("PRIVATE_SECRET_CANARY");
        for (int i = 0; i < 20; i++) failure = new IllegalStateException("PRIVATE_SECRET_CANARY", failure);
        for (var stage : RestoreChildDiagnostics.Stage.values()) {
            var evidence = RestoreChildDiagnostics.describe(failure, stage, identity());
            assertEquals(stage.name(), evidence.get("stage"));
            assertEquals(8, ((List<?>) evidence.get("causeTypes")).size());
            assertFalse(JSON.toJSONString(evidence).contains("PRIVATE"));
        }
    }
}
