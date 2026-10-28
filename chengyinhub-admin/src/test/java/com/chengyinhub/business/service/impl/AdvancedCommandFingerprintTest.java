package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.PhotoCheckIntentService;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H-01 服务端负控；仅单元合同，不能替代 MySQL 事务/并发验证。 */
class AdvancedCommandFingerprintTest {
    private AdvancedGameRuntimeServiceImpl service;
    private GameRuntimeSessionMapper sessions;
    private PlayAttemptLogMapper attempts;
    private PhotoCheckIntentService photos;
    private GameRuntimeSession session;
    private final AtomicReference<PlayAttemptLog> receipt = new AtomicReference<>();

    @BeforeEach void fixture() {
        receipt.set(null);
        service = new AdvancedGameRuntimeServiceImpl();
        com.chengyinhub.business.service.impl.AdvancedActionTestFixtures.transactions(service);
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
        sessions = mock(GameRuntimeSessionMapper.class); attempts = mock(PlayAttemptLogMapper.class);
        photos = mock(PhotoCheckIntentService.class);
        ReflectionTestUtils.setField(service, "photoCheckIntentService", photos);
        ReflectionTestUtils.setField(service, "sessionMapper", sessions);
        ReflectionTestUtils.setField(service, "attemptMapper", attempts);
        when(sessions.selectByIdForUpdate(51L)).thenAnswer(i -> session);
        when(sessions.selectAccessibleByIdForUpdate(51L, 7L)).thenAnswer(i -> session);
        when(attempts.selectByIdempotencyKeyForUpdate(anyString())).thenAnswer(i -> receipt.get());
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(1_775_000_000_000L));
        session = new GameRuntimeSession();
        session.setId(51L); session.setActivityId(0L); session.setTopicId(19L); session.setNodeId(29L);
        session.setOwnerType(GameRuntimeSession.OWNER_PLAYER); session.setOwnerId(7L);
        session.setPlayInstanceId("default"); session.setVersion(0); session.setStatus(GameRuntimeSession.STATUS_RUNNING);
        session.setStartedAt(new Date(1_774_999_000_000L)); session.setScore(0); session.setCompletedUnits(0);
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"note\":{\"enabled\":true,\"maxLength\":40}}");
        session.setStateJson("{}");
        when(sessions.selectAccessibleById(51L, 7L)).thenReturn(session);
        when(attempts.selectByIdempotencyKey(anyString())).thenAnswer(i -> receipt.get());
    }

    private Map<String,Object> originalPayload() {
        Map<String,Object> input = new LinkedHashMap<>();
        input.put("text", "original"); input.put("nested", Map.of("b", List.of(1, "2"), "a", true));
        return input;
    }

    private void bindReceipt(String action, Map<String,Object> input, Integer expected) {
        String hash = ReflectionTestUtils.invokeMethod(service, "commandFingerprint", session, 7L,
                expected, "original-key", action, input);
        PlayAttemptLog row = new PlayAttemptLog(); row.setId(91L); row.setMemberId(7L); row.setAttemptType(8);
        row.setIdempotencyKey("original-key"); row.setResultJson("{\"sessionId\":51,\"version\":1,\"status\":\"RUNNING\"}");
        // Reflection allows this negative suite to compile against the old domain before the new column is added.
        ReflectionTestUtils.invokeMethod(row, "setRequestFingerprint", hash);
        receipt.set(row);
    }

    @Test void sameKeyChangedActionCannotReplayOldSuccessOrCallProvider() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0);
        assertThrows(ServiceException.class, () -> service.act(7L, 51L, 0, "original-key", "SUBMIT_PHOTO_CHECK", originalPayload()));
        verifyNoInteractions(photos); verify(sessions, never()).updateStateCas(any());
    }

    @Test void sameKeyChangedNestedPayloadCannotReplayOldSuccess() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0);
        Map<String,Object> changed = originalPayload(); changed.put("nested", Map.of("b", List.of("1", "2"), "a", true));
        assertThrows(ServiceException.class, () -> service.act(7L, 51L, 0, "original-key", "SUBMIT_NOTE", changed));
        verify(sessions, never()).updateStateCas(any());
    }

    @Test void sameKeyChangedExpectedCannotReplayOldSuccess() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0); session.setVersion(3);
        assertThrows(ServiceException.class, () -> service.act(7L, 51L, 3, "original-key", "SUBMIT_NOTE", originalPayload()));
    }

    @Test void executedOriginalReplaysBeforeCurrentVersionStatusAndEpochChecks() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0);
        session.setVersion(9); session.setStatus(GameRuntimeSession.STATUS_COMPLETED);
        session.setStateJson("{\"runtimeAuthorityEpoch\":999}");
        assertEquals(1, service.act(7L, 51L, 0, "original-key", " submit_note ", originalPayload()).get("version"));
        verify(sessions, never()).updateStateCas(any()); verifyNoInteractions(photos);
    }

    @Test void recursiveObjectOrderDoesNotChangeFingerprintButArrayOrderAndNullDo() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0);
        Map<String,Object> reordered = new LinkedHashMap<>();
        reordered.put("nested", Map.of("a", true, "b", List.of(1, "2"))); reordered.put("text", "original");
        assertEquals(1, service.act(7L, 51L, 0, "original-key", "SUBMIT_NOTE", reordered).get("version"));
        reordered.put("nested", Map.of("a", true, "b", List.of("2", 1)));
        assertThrows(ServiceException.class, () -> service.act(7L, 51L, 0, "original-key", "SUBMIT_NOTE", reordered));
        Map<String,Object> withNull = originalPayload(); withNull.put("extra", null);
        assertThrows(ServiceException.class, () -> service.act(7L, 51L, 0, "original-key", "SUBMIT_NOTE", withNull));
    }

    @Test void historicalNullAndMalformedHashKeepKeyReserved() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0);
        for (String hash : Arrays.asList(null, "", "not-a-sha256")) {
            ReflectionTestUtils.invokeMethod(receipt.get(), "setRequestFingerprint", hash);
            assertThrows(ServiceException.class, () -> service.act(7L, 51L, 0, "original-key", "SUBMIT_NOTE", originalPayload()));
        }
        verify(attempts, never()).insertPlayAttemptLog(any());
    }

    @Test void missingExpectedIsRejectedEvenWhenExecutedReceiptExists() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0);
        assertThrows(IllegalArgumentException.class, () -> service.act(7L, 51L, null, "original-key", "SUBMIT_NOTE", originalPayload()));
        verify(attempts, never()).selectByIdempotencyKey(anyString());
    }

    @Test void unauthorizedReaderCannotObtainOriginalResult() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0);
        assertThrows(IllegalStateException.class, () -> service.act(8L, 51L, 0, "original-key", "SUBMIT_NOTE", originalPayload()));
        verify(attempts, never()).selectByIdempotencyKey(anyString());
    }

    @Test void fingerprintBindsActorSessionOriginalScopeAndFullClientKey() {
        String original = fingerprint(7L, 0, "original-key", originalPayload());
        assertEquals(original, fingerprint(7L, 0, "original-key", originalPayload()));
        assertNotEquals(original, fingerprint(8L, 0, "original-key", originalPayload()));
        assertNotEquals(original, fingerprint(7L, 1, "original-key", originalPayload()));
        assertNotEquals(original, fingerprint(7L, 0, "new-attempt", originalPayload()));
        session.setPlayInstanceId("other-instance");
        assertNotEquals(original, fingerprint(7L, 0, "original-key", originalPayload()));
    }

    private String fingerprint(Long actor, Integer expected, String key, Map<String,Object> payload) {
        return ReflectionTestUtils.invokeMethod(service, "commandFingerprint", session, actor, expected, key, "SUBMIT_NOTE", payload);
    }

    @Test void duplicateReceiptInsertionNeverCommitsAnotherLocalEffect() {
        bindReceipt("SUBMIT_NOTE", originalPayload(), 0);
        String hash = fingerprint(7L, 0, "original-key", originalPayload());
        when(attempts.insertPlayAttemptLog(any())).thenThrow(new DuplicateKeyException("fixture"));
        // logAction is called after local writes. Even a semantically identical duplicate must force rollback;
        // callers may obtain the winner only after the losing transaction has ended.
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(service, "logAction", session,
                7L, "original-key", hash, Map.of("sessionId", 51L, "version", 1)));
    }

    @Test void newAttemptWithSameWrongAnswerCountsOncePerIntentAndRewardDoesNotDrift() throws Exception {
        com.chengyinhub.business.service.support.CurrentPlayTestFixtures.installOn(service);
        session.setConfigSnapshotJson("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\","
                + "\"answerText\":\"right\",\"maxTries\":4,\"xp\":5}}");
        when(sessions.updateStateCas(any())).thenReturn(1);
        Map<String,PlayAttemptLog> stored = new HashMap<>();
        when(attempts.selectByIdempotencyKey(anyString())).thenAnswer(i -> stored.get(i.getArgument(0)));
        when(attempts.insertPlayAttemptLog(any())).thenAnswer(i -> {
            PlayAttemptLog log = i.getArgument(0); stored.put(log.getIdempotencyKey(), log); return 1;
        });
        service.act(7L, 51L, 0, "try-one", "SUBMIT_QA", Map.of("input", "wrong"));
        assertEquals(1, service.act(7L, 51L, 0, "try-one", "SUBMIT_QA", Map.of("input", "wrong")).get("version"));
        service.act(7L, 51L, 1, "try-two", "SUBMIT_QA", Map.of("input", "wrong"));
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(2, json.readTree(session.getStateJson()).path("qa").path("attempts").asInt());
        assertEquals(0, session.getScore()); assertEquals(2, stored.size());
        assertNotEquals(stored.get("try-one").getRequestFingerprint(), stored.get("try-two").getRequestFingerprint());
        service.act(7L, 51L, 2, "try-right", "SUBMIT_QA", Map.of("input", "right"));
        service.act(7L, 51L, 2, "try-right", "SUBMIT_QA", Map.of("input", "right"));
        assertEquals(3, json.readTree(session.getStateJson()).path("qa").path("attempts").asInt());
        assertEquals(5, session.getScore()); assertEquals(3, stored.size());
        verify(sessions, times(3)).updateStateCas(any()); verify(attempts, times(3)).insertPlayAttemptLog(any());
    }
}
