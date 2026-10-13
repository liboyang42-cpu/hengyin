package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRegistrationCreationTransaction;
import com.chengyinhub.business.service.IRegistrationOrderCreateGate;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationOrderServiceBoundaryTest {

    @Test
    void concurrentRequestsWithoutBusinessKeyAreRejectedBeforeCreation() throws Exception {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IRegistrationCreationTransaction creation = mock(IRegistrationCreationTransaction.class);
        IRegistrationOrderCreateGate gate = mock(IRegistrationOrderCreateGate.class);
        when(creation.create(any(), any(), any(), any())).thenReturn(Collections.emptyList());
        RegistrationOrderServiceImpl service = service(registrations, creation, gate);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> requestWithoutKey = () -> {
                startTogether.await(5, TimeUnit.SECONDS);
                try {
                    service.createOrReplay(7L, request(null));
                    return "CREATED";
                } catch (IllegalArgumentException rejected) {
                    return rejected.getMessage();
                }
            };
            Future<String> first = pool.submit(requestWithoutKey);
            Future<String> second = pool.submit(requestWithoutKey);
            List<String> outcomes = Arrays.asList(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            org.junit.jupiter.api.Assertions.assertEquals(
                    Arrays.asList("报名幂等键不能为空", "报名幂等键不能为空"), outcomes,
                    "两个并发无 key 请求都必须被 requestId 闸拦截，不能进入建单");
        } finally {
            pool.shutdownNow();
        }
        verify(creation, never()).create(any(), any(), any(), any());
    }

    @Test
    void createBoundaryCannotBypassQuoteAndReadinessGate() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IRegistrationCreationTransaction creation = mock(IRegistrationCreationTransaction.class);
        IRegistrationOrderCreateGate gate = mock(IRegistrationOrderCreateGate.class);
        RegistrationOrderServiceImpl service = service(registrations, creation, gate);
        RegistrationRequest request = request("gate-boundary-request");
        RuntimeException blocked = new RuntimeException("gate blocked");
        org.mockito.Mockito.doThrow(blocked).when(gate).validate(7L, request);

        assertSame(blocked, assertThrows(RuntimeException.class,
                () -> service.createOrReplay(7L, request)));
        verify(creation, never()).create(any(), any(), any(), any());
    }

    @Test
    void persistedReplayReturnsBeforeLiveGate() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IRegistrationCreationTransaction creation = mock(IRegistrationCreationTransaction.class);
        IRegistrationOrderCreateGate gate = mock(IRegistrationOrderCreateGate.class);
        RegistrationOrderServiceImpl service = service(registrations, creation, gate);
        RegistrationRequest request = request("stable-request");
        String fingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", 7L, request);
        CmsRegistration persisted = new CmsRegistration();
        persisted.setId(11L);
        persisted.setMemberId(7L);
        persisted.setRequestId("stable-request");
        persisted.setRequestFingerprint(fingerprint);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(persisted));

        RegistrationOrderResult replay = service.createOrReplay(7L, request);

        org.junit.jupiter.api.Assertions.assertTrue(replay.isReplayed());
        verify(gate, never()).validate(any(), any());
        verify(creation, never()).create(any(), any(), any(), any());
    }

    @Test
    void waitlistOfferAndTokenArePartOfIdempotencyFingerprint() {
        RegistrationOrderServiceImpl service = service(mock(ICmsRegistrationService.class),
                mock(IRegistrationCreationTransaction.class), mock(IRegistrationOrderCreateGate.class));
        RegistrationRequest first = request("waitlist-order");
        first.setWaitlistOfferId(61L);
        first.setWaitlistOfferToken("token-a");
        RegistrationRequest changed = request("waitlist-order");
        changed.setWaitlistOfferId(61L);
        changed.setWaitlistOfferToken("token-b");

        String firstFingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", 7L, first);
        String changedFingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", 7L, changed);

        assertNotEquals(firstFingerprint, changedFingerprint,
                "不同候补凭证不得以相同 requestId 伪装成幂等重放");
    }

    @Test
    void cancelledOrderIsNotReplayedAsSuccess() {
        assertTerminalOrderRejected(3, "该幂等键对应的报名已取消，请重新发起报名");
    }

    @Test
    void expiredOrderIsNotReplayedAsSuccess() {
        assertTerminalOrderRejected(4, "该幂等键对应的报名已过期，请重新发起报名");
    }

    /** 负控:非终态(待支付1)且指纹一致时仍是正常重放,新的终态闸不得误伤。 */
    @Test
    void pendingOrderStillReplaysWithSameFingerprint() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IRegistrationCreationTransaction creation = mock(IRegistrationCreationTransaction.class);
        IRegistrationOrderCreateGate gate = mock(IRegistrationOrderCreateGate.class);
        RegistrationOrderServiceImpl service = service(registrations, creation, gate);
        RegistrationRequest request = request("pending-replay");
        String fingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", 7L, request);
        CmsRegistration persisted = order(1, fingerprint);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(persisted));

        RegistrationOrderResult replay = service.createOrReplay(7L, request);

        org.junit.jupiter.api.Assertions.assertTrue(replay.isReplayed());
        verify(creation, never()).create(any(), any(), any(), any());
    }

    private void assertTerminalOrderRejected(int registrationStatus, String expectedMessage) {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IRegistrationCreationTransaction creation = mock(IRegistrationCreationTransaction.class);
        IRegistrationOrderCreateGate gate = mock(IRegistrationOrderCreateGate.class);
        RegistrationOrderServiceImpl service = service(registrations, creation, gate);
        RegistrationRequest request = request("terminal-" + registrationStatus);
        String fingerprint = ReflectionTestUtils.invokeMethod(service, "fingerprint", 7L, request);
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(order(registrationStatus, fingerprint)));

        RegistrationRequestConflictException conflict = assertThrows(
                RegistrationRequestConflictException.class,
                () -> service.createOrReplay(7L, request),
                "终态单必须回明确冲突,不得伪装成下单成功");

        org.junit.jupiter.api.Assertions.assertEquals(expectedMessage, conflict.getMessage());
        verify(creation, never()).create(any(), any(), any(), any());
        verify(gate, never()).validate(any(), any());
    }

    private CmsRegistration order(int registrationStatus, String fingerprint) {
        CmsRegistration persisted = new CmsRegistration();
        persisted.setId(11L);
        persisted.setMemberId(7L);
        persisted.setRequestId("terminal");
        persisted.setRequestFingerprint(fingerprint);
        persisted.setRegistrationStatus(registrationStatus);
        return persisted;
    }

    private RegistrationOrderServiceImpl service(ICmsRegistrationService registrations,
                                                  IRegistrationCreationTransaction creation,
                                                  IRegistrationOrderCreateGate gate) {
        RegistrationOrderServiceImpl service = new RegistrationOrderServiceImpl();
        ReflectionTestUtils.setField(service, "registrationService", registrations);
        ReflectionTestUtils.setField(service, "registrationCreationTransaction", creation);
        ReflectionTestUtils.setField(service, "registrationOrderCreateGate", gate);
        return service;
    }

    private RegistrationRequest request(String requestId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(55L);
        request.setTicketId(9L);
        request.setRequestId(requestId);
        return request;
    }
}
