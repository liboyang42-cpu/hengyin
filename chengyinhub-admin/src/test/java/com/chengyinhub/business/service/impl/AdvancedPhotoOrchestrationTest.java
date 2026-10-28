package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.support.AdvancedGameClock;
import com.chengyinhub.business.service.support.CurrentPlayTestFixtures;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit orchestration/transaction-marker tests. In-memory rollback here is NOT MySQL evidence. */
class AdvancedPhotoOrchestrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<GameRuntimeSession> row = new AtomicReference<>();
    private final Map<String, PlayAttemptLog> receipts = new HashMap<>();
    private AdvancedGameRuntimeServiceImpl service;
    private GameRuntimeSessionMapper sessions;
    private PlayAttemptLogMapper attempts;
    private PhotoCheckIntentService intents;
    private PhotoCheckIntentCommand original;
    private PhotoCheckIntentView intent;
    private PhotoCheckVisionResult providerResult;
    private AbstractPlatformTransactionManager transactions;
    private int providerCalls;
    private boolean casFails, logFails, markFails;
    private Runnable duringProvider;
    private String initialState;

    @BeforeEach void fixture() {
        service = new AdvancedGameRuntimeServiceImpl();
        sessions = mock(GameRuntimeSessionMapper.class); attempts = mock(PlayAttemptLogMapper.class);
        intents = mock(PhotoCheckIntentService.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessions);
        ReflectionTestUtils.setField(service, "attemptMapper", attempts);
        ReflectionTestUtils.setField(service, "photoCheckIntentService", intents);
        ReflectionTestUtils.setField(service, "clock", (AdvancedGameClock) () -> new Date(1_775_000_000_000L));
        CurrentPlayTestFixtures.installOn(service);
        GameRuntimeSession s = new GameRuntimeSession(); s.setId(51L); s.setActivityId(0L); s.setTopicId(19L); s.setNodeId(29L);
        s.setOwnerType(1); s.setOwnerId(7L); s.setPlayInstanceId("default"); s.setVersion(0); s.setStatus(0);
        s.setStartedAt(new Date(1_774_999_000_000L)); s.setScore(0); s.setCompletedUnits(0); s.setStateJson("{}");
        s.setConfigSnapshotJson("{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true,\"fallback\":\"pass\",\"maxTries\":3,\"xp\":5}}");
        initialState = "{}";
        row.set(s); receipts.clear(); original = null; intent = null; providerCalls = 0;
        casFails = logFails = markFails = false; duringProvider = () -> {};
        providerResult = PhotoCheckVisionResult.concluded(true, 99, "original result");
        transactions = new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Snapshot(copy(row.get()), new HashMap<>(receipts), intent); }
            protected void doBegin(Object tx, TransactionDefinition def) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) {
                Snapshot before = (Snapshot) status.getTransaction();
                row.set(before.row()); receipts.clear(); receipts.putAll(before.receipts()); intent = before.intent();
            }
        };
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        when(sessions.selectAccessibleById(51L, 7L)).thenAnswer(i -> copy(row.get()));
        when(sessions.selectByIdForUpdate(51L)).thenAnswer(i -> { assertTx(); return copy(row.get()); });
        when(sessions.selectAccessibleByIdForUpdate(51L, 7L)).thenAnswer(i -> { assertTx(); return copy(row.get()); });
        when(sessions.updateStateCas(any())).thenAnswer(i -> {
            assertTx(); if (casFails) return 0;
            GameRuntimeSession next = copy(i.getArgument(0)); next.setVersion(next.getVersion() + 1); row.set(next); return 1;
        });
        when(attempts.selectByIdempotencyKey(anyString())).thenAnswer(i -> receipts.get(i.getArgument(0)));
        when(attempts.selectByIdempotencyKeyForUpdate(anyString())).thenAnswer(i -> { assertTx(); return receipts.get(i.getArgument(0)); });
        when(attempts.insertPlayAttemptLog(any())).thenAnswer(i -> {
            assertTx(); if (logFails) throw new IllegalStateException("receipt store unavailable");
            PlayAttemptLog log = i.getArgument(0); log.setId(91L); receipts.put(log.getIdempotencyKey(), log); return 1;
        });
        when(intents.read(anyLong(), anyLong(), anyString(), anyString())).thenAnswer(i -> {
            assertTx(); if (intent == null) return null;
            if (!Objects.equals(i.getArgument(0), intent.memberId()) || !Objects.equals(i.getArgument(1), intent.sessionId())
                    || !Objects.equals(i.getArgument(2), intent.actionKey()) || !Objects.equals(i.getArgument(3), intent.requestFingerprint())) {
                throw new ServiceException("original intent mismatch", 409);
            }
            return intent;
        });
        when(intents.prepare(any())).thenAnswer(i -> {
            assertTx(); assertEquals(0, providerCalls); assertEquals(0, row.get().getVersion());
            assertEquals(initialState, row.get().getStateJson()); assertTrue(receipts.isEmpty());
            original = i.getArgument(0); intent = view(PhotoCheckIntentState.PREPARED, null, "NOT_SENT"); return intent;
        });
        when(intents.executeOnce(eq(7L), eq(81L), anyString())).thenAnswer(i -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            providerCalls++; duringProvider.run();
            intent = view(PhotoCheckIntentState.RESULT_READY, providerResult, providerResult.isDegraded() ? "UNKNOWN" : "RECEIVED");
            return intent;
        });
        doAnswer(i -> {
            assertTx(); assertEquals(Long.valueOf(91L), i.<Long>getArgument(2)); assertEquals(1, receipts.size());
            intent = view(PhotoCheckIntentState.APPLIED, intent.result(), intent.providerState());
            if (markFails) throw new IllegalStateException("intent write failed after APPLIED");
            return null;
        }).when(intents).markApplied(eq(81L), anyString(), eq(91L));
    }

    private record Snapshot(GameRuntimeSession row, Map<String, PlayAttemptLog> receipts, PhotoCheckIntentView intent) { }
    private void assertTx() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }
    private GameRuntimeSession copy(GameRuntimeSession s) { return json.convertValue(s, GameRuntimeSession.class); }
    private PhotoCheckIntentView view(PhotoCheckIntentState state, PhotoCheckVisionResult result, String providerState) {
        return new PhotoCheckIntentView(81L, 7L, 51L, state == PhotoCheckIntentState.APPLIED ? 91L : null,
                original.actionKey(), original.requestFingerprint(), state, result, "UNIT_RESULT", providerState);
    }
    private Map<String,Object> act() { return service.act(7L, 51L, 0, "photo-original", "SUBMIT_PHOTO_CHECK", Map.of("imageUrl", "media-asset:701")); }
    private void seedOriginal(PhotoCheckIntentState state, PhotoCheckVisionResult result, String providerState) {
        ReflectionTestUtils.invokeMethod(service, "admitPhoto", 7L, 51L, 0, "photo-original", Map.of("imageUrl", "media-asset:701"));
        intent = view(state, result, providerState);
    }
    private void seed(PhotoCheckIntentState state, PhotoCheckVisionResult result, String providerState) {
        new TransactionTemplate(transactions).execute(status -> { seedOriginal(state, result, providerState); return null; });
    }

    @Test void intentCommitsBeforeProviderAndReceiptCommitsWithApplied() {
        Map<String,Object> result = act();
        assertEquals(1, result.get("version")); assertEquals(1, providerCalls); assertEquals(5, row.get().getScore());
        assertEquals(PhotoCheckIntentState.APPLIED, intent.state()); assertEquals(1, receipts.size());
        org.mockito.InOrder order = inOrder(intents, attempts);
        order.verify(intents).prepare(any()); order.verify(intents).executeOnce(anyLong(), anyLong(), anyString());
        order.verify(attempts).insertPlayAttemptLog(any()); order.verify(intents).markApplied(anyLong(), anyString(), anyLong());
    }

    @Test void casFailureRetainsOriginalResultAndRetryDoesNotCallProviderAgain() {
        casFails = true; assertThrows(AiProviderOutcomeUnknownException.class, this::act);
        assertEquals(0, row.get().getVersion()); assertEquals("{}", row.get().getStateJson()); assertTrue(receipts.isEmpty());
        assertEquals(PhotoCheckIntentState.RESULT_READY, intent.state());
        casFails = false; act(); assertEquals(1, providerCalls); assertEquals(5, row.get().getScore());
    }

    @Test void receiptFailureRollsBackLocalEffectButKeepsFirstResult() {
        logFails = true; assertThrows(AiProviderOutcomeUnknownException.class, this::act);
        assertEquals(0, row.get().getVersion()); assertEquals(0, row.get().getScore()); assertEquals("{}", row.get().getStateJson());
        assertEquals(PhotoCheckIntentState.RESULT_READY, intent.state()); assertTrue(receipts.isEmpty());
        verify(intents, never()).markApplied(anyLong(), anyString(), anyLong());
        logFails = false; act(); assertEquals(1, providerCalls);
    }

    @Test void appliedWriteFailureRollsBackLocalReceiptAndGrantButRetainsFirstProviderResult() {
        markFails = true;
        assertThrowsExactly(AiProviderOutcomeUnknownException.class, this::act);
        assertEquals(0, row.get().getVersion()); assertEquals(0, row.get().getScore());
        assertEquals(initialState, row.get().getStateJson()); assertTrue(receipts.isEmpty());
        assertEquals(PhotoCheckIntentState.RESULT_READY, intent.state());
        assertEquals(providerResult, intent.result()); assertEquals("RECEIVED", intent.providerState());
        assertEquals(1, providerCalls);
        verify(intents).markApplied(81L, original.requestFingerprint(), 91L);
        markFails = false;
        act();
        assertEquals(1, providerCalls); assertEquals(1, row.get().getVersion());
        assertEquals(5, row.get().getScore()); assertEquals(1, receipts.size());
        assertEquals(PhotoCheckIntentState.APPLIED, intent.state());
    }

    @Test void inFlightAndUnknownNeverSynthesizeDegradedOrExecuteAgain() {
        seed(PhotoCheckIntentState.IN_FLIGHT, null, "UNKNOWN");
        assertThrows(AiProviderOutcomeUnknownException.class, this::act);
        intent = view(PhotoCheckIntentState.UNKNOWN, null, "UNKNOWN");
        assertThrows(AiProviderOutcomeUnknownException.class, this::act);
        assertEquals(0, providerCalls); assertEquals("{}", row.get().getStateJson()); assertTrue(receipts.isEmpty());
    }

    @Test void savedFirstDegradedPreservesConfiguredFallbackWithoutRewardOrSecondProviderCall() throws Exception {
        seed(PhotoCheckIntentState.RESULT_READY, PhotoCheckVisionResult.degraded("first local timeout"), "UNKNOWN");
        act(); assertEquals(0, providerCalls); assertEquals("UNKNOWN", intent.providerState());
        assertTrue(json.readTree(row.get().getStateJson()).path("photoCheck").path("passed").asBoolean());
        assertEquals(0, row.get().getScore()); assertEquals(PhotoCheckIntentState.APPLIED, intent.state());
    }

    @Test void originalResultIsReadBeforeCurrentVersionConflictAndRemainsRecoverable() {
        seed(PhotoCheckIntentState.RESULT_READY, providerResult, "RECEIVED"); row.get().setVersion(4);
        assertThrows(AiProviderOutcomeUnknownException.class, this::act);
        assertEquals(PhotoCheckIntentState.RESULT_READY, intent.state()); assertEquals(0, providerCalls); assertTrue(receipts.isEmpty());
        verify(intents, atLeastOnce()).read(7L, 51L, "photo-original", original.requestFingerprint());
    }

    @Test void lostPrepareReplyRecoversOriginalKeyWithoutAllocatingNewIntent() {
        seed(PhotoCheckIntentState.PREPARED, null, "NOT_SENT"); clearInvocations(intents);
        act(); verify(intents, never()).prepare(any()); assertEquals(1, providerCalls);
    }

    @Test void terminalReplayPrecedesCurrentVersionStatusChecksAndDoesNotExecute() throws Exception {
        Map<String,Object> first = act(); row.get().setVersion(9); row.get().setStatus(1);
        Map<String,Object> replay = act(); assertEquals(json.valueToTree(first), json.valueToTree(replay));
        assertEquals(1, providerCalls); assertEquals(1, receipts.size());
    }

    @Test void sameKeyDifferentExpectedOrImageConflictsWithoutNewExecution() {
        seed(PhotoCheckIntentState.UNKNOWN, null, "UNKNOWN");
        assertThrows(ServiceException.class, () -> service.act(7L,51L,1,"photo-original","SUBMIT_PHOTO_CHECK",Map.of("imageUrl","media-asset:701")));
        assertThrows(ServiceException.class, () -> service.act(7L,51L,0,"photo-original","SUBMIT_PHOTO_CHECK",Map.of("imageUrl","media-asset:702")));
        assertEquals(0, providerCalls); verify(intents, times(1)).prepare(any());
    }

    @Test void changedFrozenAuthorityOrConfigDoesNotRebindOriginalIntent() {
        seed(PhotoCheckIntentState.RESULT_READY, providerResult, "RECEIVED");
        row.get().setStateJson("{\"runtimeAuthorityEpoch\":999}");
        assertThrows(ServiceException.class, this::act); assertEquals(0, providerCalls); assertTrue(receipts.isEmpty());
        row.get().setStateJson(initialState);
        row.get().setConfigSnapshotJson(row.get().getConfigSnapshotJson().replace("\"xp\":5", "\"xp\":6"));
        assertThrows(ServiceException.class, this::act); assertEquals(0, providerCalls); assertTrue(receipts.isEmpty());
    }

    @Test void outerTransactionIsRejectedBeforeReadingIntentOrCallingProvider() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> act()));
        verifyNoInteractions(intents, sessions, attempts); assertEquals(0, providerCalls);
    }

    @Test void revokedReadAccessCannotObtainTerminalOrOriginalIntent() {
        act(); when(sessions.selectAccessibleById(51L, 7L)).thenReturn(null); clearInvocations(intents, attempts);
        assertThrows(AiProviderOutcomeUnknownException.class, this::act); verifyNoInteractions(intents, attempts); assertEquals(1, providerCalls);
    }

    @Test void firstPhaseMissingIdentityAndStaleExpectationHaveZeroLocalOrExternalEffects() {
        assertThrows(IllegalArgumentException.class, () -> service.act(7L,51L,null,"photo-original","SUBMIT_PHOTO_CHECK",Map.of()));
        assertThrows(ServiceException.class, () -> service.act(7L,51L,1,"photo-original","SUBMIT_PHOTO_CHECK",Map.of("imageUrl","media-asset:701")));
        verify(intents, never()).prepare(any()); assertEquals(0, providerCalls); assertEquals("{}", row.get().getStateJson());
    }
    @Test void r5EvidenceHeadIsOutsideTransactionAndSavedResultRetryNeverRepeatsIt() {
        row.get().setConfigSnapshotJson("{\"schemaVersion\":1,\"photoCheck\":{\"enabled\":true},\"outcomeRules\":{\"version\":1,\"pass\":{}}}");
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media=mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.processingUrl("media-asset:701",7L,"IMAGE")).thenReturn("fixture-processing-url");
        ReflectionTestUtils.setField(service,"mediaReferences",media);
        java.util.concurrent.atomic.AtomicInteger heads=new java.util.concurrent.atomic.AtomicInteger();
        ReflectionTestUtils.setField(service,"photoEvidenceVerifier",(java.util.function.BiConsumer<String,Long>)(url,actor)->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("fixture-processing-url",url);assertEquals(7L,actor);heads.incrementAndGet();
        });
        casFails=true;assertThrows(AiProviderOutcomeUnknownException.class,this::act);
        assertThrows(AiProviderOutcomeUnknownException.class,this::act);
        assertEquals(1,heads.get());assertEquals(1,providerCalls);assertEquals(PhotoCheckIntentState.RESULT_READY,intent.state());
    }

    @Test void externalAuthorityEpochChangeKeepsOriginalResultWithoutAnyLocalGrant() {
        initialState="{\"runtimeAuthorityEpoch\":4,\"originalGameSessionId\":70}";
        row.get().setStateJson(initialState);row.get().setOwnerType(2);row.get().setOwnerId(17L);row.get().setPlayInstanceId("original-shared-instance");
        com.chengyinhub.business.service.IGameSessionRuntimeService shared=mock(com.chengyinhub.business.service.IGameSessionRuntimeService.class);
        ReflectionTestUtils.setField(service,"gameSessionRuntimeService",shared);
        when(shared.sharedRouteSnapshot(7L,19L,0L,29L)).thenReturn(json.createObjectNode()
                .put("teamId",17).put("runtimeInstance","original-shared-instance").put("authorityEpoch",4));
        java.util.concurrent.atomic.AtomicInteger currentEpoch=new java.util.concurrent.atomic.AtomicInteger(4);
        doAnswer(i->{if(currentEpoch.get()!=4)throw new ServiceException("old authority epoch",409);return null;})
                .when(shared).assertSharedRouteWritable(7L,19L,0L,29L,4);
        duringProvider=()->currentEpoch.set(5);
        assertThrows(AiProviderOutcomeUnknownException.class,this::act);
        assertEquals(1,providerCalls);assertEquals(initialState,row.get().getStateJson());assertEquals(0,row.get().getScore());
        assertEquals(PhotoCheckIntentState.RESULT_READY,intent.state());assertTrue(receipts.isEmpty());
        assertThrows(AiProviderOutcomeUnknownException.class,this::act);assertEquals(1,providerCalls);
        verify(sessions,never()).updateStateCas(any());verify(intents,never()).markApplied(anyLong(),anyString(),anyLong());
    }

    @Test void newAttemptWithSameRejectedPhotoCountsAgainButOriginalReplayDoesNot() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reviews=new java.util.concurrent.atomic.AtomicInteger();
        // Re-stubbing invokes the previous strict locking-read answer. Keep that setup read in a real test transaction.
        new TransactionTemplate(transactions).execute(status -> {
            assertTx();
            return AdvancedActionTestFixtures.photos(service,()->{reviews.incrementAndGet();return PhotoCheckVisionResult.concluded(false,99,"retake");});
        });
        Map<String,Object> image=Map.of("imageUrl","media-asset:701");
        service.act(7L,51L,0,"photo-try-1","SUBMIT_PHOTO_CHECK",image);
        service.act(7L,51L,0,"photo-try-1","SUBMIT_PHOTO_CHECK",image);
        service.act(7L,51L,1,"photo-try-2","SUBMIT_PHOTO_CHECK",image);
        service.act(7L,51L,1,"photo-try-2","SUBMIT_PHOTO_CHECK",image);
        assertEquals(2,reviews.get());assertEquals(2,row.get().getVersion());assertEquals(0,row.get().getScore());
        assertEquals(2,json.readTree(row.get().getStateJson()).path("photoCheck").path("tries").asInt());
        verify(attempts,times(2)).insertPlayAttemptLog(any());
    }

}
