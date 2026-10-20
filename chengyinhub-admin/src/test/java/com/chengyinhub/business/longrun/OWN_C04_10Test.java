package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ai.NpcChatReq;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Provider completion and receipt-storage failure classification, not a live DB test. */
class OWN_C04_10Test {
    private static final String ID = "00000000-0000-4000-8000-000000000010";
    private NpcChatService service;
    private NpcChatWriteService writes;
    private AiGatewayService gateway;
    private NpcChatRequest originalProcessing;

    @BeforeEach void setup() {
        writes = mock(NpcChatWriteService.class); gateway = mock(AiGatewayService.class);
        NpcChatRequestMapper requests = mock(NpcChatRequestMapper.class);
        when(requests.renewLease(anyLong(), anyString(), anyString(), any(Date.class))).thenReturn(1);
        when(writes.read(1001L, ID)).thenReturn(null).thenThrow(new IllegalStateException("receipt read unavailable"));
        when(writes.admit(any(NpcChatRequest.class), anyString())).thenAnswer(inv -> {
            NpcChatRequest admitted = inv.getArgument(0);
            admitted.setLeaseUntil(new Date(System.currentTimeMillis() + 20000));
            originalProcessing = new NpcChatRequest();
            org.springframework.beans.BeanUtils.copyProperties(admitted, originalProcessing);
            return admitted;
        });
        when(writes.finish(any(NpcChatRequest.class), any(NpcChatLog.class)))
                .thenThrow(new IllegalStateException("finish unavailable"));
        when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("fixture", "provider answer", "fixture", 0));
        NpcProfileMapper profiles = mock(NpcProfileMapper.class);
        NpcProfile profile = new NpcProfile(); profile.setId(1L); profile.setPersona("fixture persona");
        when(profiles.selectGlobalNpc()).thenReturn(profile);
        RoamMapper roam = mock(RoamMapper.class);
        RoamSession session = new RoamSession(); session.setId(99L); session.setMemberId(1001L); session.setStatus(0);
        when(roam.selectSessionById(99L)).thenReturn(session);
        NpcMemoryBuilder memory = mock(NpcMemoryBuilder.class);
        when(memory.buildRoam(any(RoamSession.class))).thenReturn("fixture progress");
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        service = new NpcChatService();
        ReflectionTestUtils.setField(service, "npcChatWriteService", writes);
        ReflectionTestUtils.setField(service, "npcChatRequestMapper", requests);
        ReflectionTestUtils.setField(service, "aiGatewayService", gateway);
        ReflectionTestUtils.setField(service, "npcProfileMapper", profiles);
        ReflectionTestUtils.setField(service, "roamMapper", roam);
        ReflectionTestUtils.setField(service, "memoryBuilder", memory);
        ReflectionTestUtils.setField(service, "scheduledExecutorService", scheduler);
    }
    private NpcChatResp call() {
        NpcChatReq query = new NpcChatReq(); query.setRequestId(ID); query.setBizId(99L); query.setMessage("question");
        return service.chat(query, 1001L);
    }
    private void assertUnknown(NpcChatResp result) {
        assertEquals(ID, result.getRequestId());
        assertEquals("PROCESSING", result.getOutcomeStatus());
        assertEquals("UNKNOWN", result.getSafetyDecision());
        assertEquals("REQUEST_OUTCOME_UNKNOWN", result.getErrorCode());
        assertFalse(result.isRetryable());
        assertNotEquals("provider answer", result.getSafeText());
        verify(gateway, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
    @Test void generatedAnswerWithFinishAndReadFailureIsUnknown() { assertUnknown(call()); }
    @Test void generatedAnswerWithMissingReceiptIsUnknown() {
        when(writes.read(1001L, ID)).thenReturn(null);
        doReturn(null).when(writes).finish(any(NpcChatRequest.class), any(NpcChatLog.class));
        assertUnknown(call());
    }
    @Test void rolledBackFinishDoesNotReportOriginalLiveRowAsNotRun() {
        AtomicInteger reads = new AtomicInteger();
        when(writes.read(1001L, ID)).thenAnswer(inv -> reads.getAndIncrement() == 0 ? null : originalProcessing);
        assertUnknown(call());
    }
    @Test void finalReadFailureAfterPersistedSuccessDoesNotClaimNoProviderRan() {
        NpcChatRequest complete = new NpcChatRequest();
        complete.setUserId(1001L); complete.setRequestId(ID); complete.setSessionId(99L);
        complete.setStatus("SUCCEEDED"); complete.setSafetyDecision("PASS"); complete.setSafeText("provider answer");
        doReturn(complete).when(writes).finish(any(NpcChatRequest.class), any(NpcChatLog.class));
        assertUnknown(call());
    }
    @Test void providerTimeoutAndStorageFailureKeepsTypedUnknown() {
        when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new AiProviderOutcomeUnknownException("timeout", new RuntimeException("fixture")));
        assertUnknown(call());
    }
    @Test void knownPreProviderUnavailableKeepsExistingClassification() {
        when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, "local config missing"));
        NpcChatResp result = call();
        assertEquals("FAILED", result.getOutcomeStatus());
        assertEquals("NOT_RUN", result.getSafetyDecision());
        assertEquals("REQUEST_STATE_UNAVAILABLE", result.getErrorCode());
    }
}
