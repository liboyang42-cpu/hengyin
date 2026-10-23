package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.function.Executable;
import org.springframework.mock.web.MockHttpServletRequest;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.ai.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit-only transaction markers and explicit stored photo responses; no database/rollback claim. */
public final class AdvancedActionTestFixtures {
    private AdvancedActionTestFixtures() { }

    private static void assertWritablePhotoTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    /** Check the exact fresh-refusal contract through the production exception response mapper. */
    public static void assertPhotoNotExecuted(Executable command, String message) {
        ServiceException refusal = assertThrowsExactly(ServiceException.class, command);
        assertEquals(Integer.valueOf(400), refusal.getCode());
        assertEquals(message, refusal.getMessage());
        assertEquals(Map.of("intentNotExecuted", true), refusal.getData());
        AjaxResult response = new GlobalExceptionHandler().handleServiceException(refusal,
                new MockHttpServletRequest("POST", "/api/play/advanced/action"));
        assertEquals(400, response.get("code"));
        assertEquals(message, response.get("msg"));
        assertEquals(Map.of("intentNotExecuted", true), response.get("data"));
    }

    public static void transactions(AdvancedGameRuntimeServiceImpl service) {
        ReflectionTestUtils.setField(service, "transactionManager", new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        });
    }

    /** Keeps old local business assertions using a persisted-result double, never a production fallback. */
    public static PhotoCheckIntentService photos(AdvancedGameRuntimeServiceImpl service, Supplier<PhotoCheckVisionResult> result) {
        return photos(service, command -> result.get());
    }

    public static PhotoCheckIntentService photos(AdvancedGameRuntimeServiceImpl service,
            Function<PhotoCheckIntentCommand, PhotoCheckVisionResult> result) {
        GameRuntimeSessionMapper sessions = (GameRuntimeSessionMapper) ReflectionTestUtils.getField(service, "sessionMapper");
        PlayAttemptLogMapper attempts = (PlayAttemptLogMapper) ReflectionTestUtils.getField(service, "attemptMapper");
        doAnswer(i -> {
            assertWritablePhotoTransaction();
            return sessions.selectAccessibleById(i.getArgument(0), i.getArgument(1));
        }).when(sessions).selectAccessibleByIdForUpdate(anyLong(), anyLong());
        doAnswer(i -> {
            assertWritablePhotoTransaction();
            Long id = i.getArgument(0), actor = null;
            for (org.mockito.invocation.Invocation read : mockingDetails(sessions).getInvocations()) {
                if ("selectAccessibleById".equals(read.getMethod().getName()) && id.equals(read.getArgument(0))) actor = read.getArgument(1);
            }
            if (actor == null) throw new AssertionError("Photo fixture must declare original read authorization");
            return sessions.selectAccessibleById(id, actor);
        }).when(sessions).selectByIdForUpdate(anyLong());
        Map<String, PlayAttemptLog> receipts = new LinkedHashMap<>();
        doAnswer(i -> receipts.get(i.getArgument(0))).when(attempts).selectByIdempotencyKey(anyString());
        doAnswer(i -> {
            assertWritablePhotoTransaction();
            return receipts.get(i.getArgument(0));
        }).when(attempts).selectByIdempotencyKeyForUpdate(anyString());
        doAnswer(i -> {
            assertWritablePhotoTransaction();
            PlayAttemptLog row = i.getArgument(0); row.setId(100L + receipts.size());
            receipts.put(row.getIdempotencyKey(), row); return 1;
        }).when(attempts).insertPlayAttemptLog(any());
        com.chengyinhub.business.service.support.AppMediaReferenceGuard media = mock(com.chengyinhub.business.service.support.AppMediaReferenceGuard.class);
        when(media.processingUrl(anyString(), anyLong(), eq("IMAGE"))).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(service, "mediaReferences", media);
        PhotoCheckIntentService intents = mock(PhotoCheckIntentService.class);
        Map<Long, PhotoCheckIntentView> stored = new LinkedHashMap<>();
        Map<Long, PhotoCheckIntentCommand> commands = new LinkedHashMap<>();
        when(intents.read(anyLong(), anyLong(), anyString(), anyString())).thenAnswer(i -> {
            for (PhotoCheckIntentView view : stored.values()) {
                if (!java.util.Objects.equals(i.getArgument(0), view.memberId())
                        || !java.util.Objects.equals(i.getArgument(1), view.sessionId())
                        || !java.util.Objects.equals(i.getArgument(2), view.actionKey())) continue;
                if (!java.util.Objects.equals(i.getArgument(3), view.requestFingerprint())) {
                    throw new com.chengyinhub.common.exception.ServiceException("unit original input conflict", 409);
                }
                return view;
            }
            return null;
        });
        when(intents.prepare(any())).thenAnswer(i -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("prepare outside transaction");
            PhotoCheckIntentCommand c = i.getArgument(0); Long id = 1L + stored.size(); commands.put(id, c);
            PhotoCheckIntentView v = new PhotoCheckIntentView(id, c.memberId(), c.sessionId(), null, c.actionKey(),
                    c.requestFingerprint(), PhotoCheckIntentState.PREPARED, null, null, "NOT_SENT");
            stored.put(id, v); return v;
        });
        when(intents.executeOnce(anyLong(), anyLong(), anyString())).thenAnswer(i -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("provider under transaction");
            PhotoCheckIntentView v = stored.get(i.getArgument(1));
            if (v.state() != PhotoCheckIntentState.PREPARED) return v;
            PhotoCheckIntentView ready = new PhotoCheckIntentView(v.intentId(), v.memberId(), v.sessionId(), null,
                    v.actionKey(), v.requestFingerprint(), PhotoCheckIntentState.RESULT_READY, result.apply(commands.get(v.intentId())), "UNIT_RESULT", "RECEIVED");
            stored.put(v.intentId(), ready); return ready;
        });
        doAnswer(i -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("markApplied outside transaction");
            PhotoCheckIntentView v = stored.get(i.getArgument(0));
            stored.put(v.intentId(), new PhotoCheckIntentView(v.intentId(), v.memberId(), v.sessionId(), i.getArgument(2),
                    v.actionKey(), v.requestFingerprint(), PhotoCheckIntentState.APPLIED, v.result(), v.reasonCode(), v.providerState()));
            return null;
        }).when(intents).markApplied(anyLong(), anyString(), anyLong());
        ReflectionTestUtils.setField(service, "photoCheckIntentService", intents);
        return intents;
    }
}
