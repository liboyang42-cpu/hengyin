package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.WxPaymentCallbackClaim;
import com.chengyinhub.business.domain.WxPaymentCallbackInbox;
import com.chengyinhub.business.mapper.WxPaymentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;

class WxPaymentCallbackInboxServiceImplTest
{
    @Test
    void registerPersistsHashInsteadOfRawNotifyId()
    {
        WxPaymentCallbackInboxMapper mapper = mock(WxPaymentCallbackInboxMapper.class);
        when(mapper.insertIgnore(any(WxPaymentCallbackInbox.class))).thenReturn(1);
        WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertTrue(service.register("wx-notify-secret", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT,
                42L, "TRANSACTION.SUCCESS"));

        verify(mapper).insertIgnore(org.mockito.ArgumentMatchers.argThat(row ->
                !"wx-notify-secret".equals(row.getNotifyHash())
                        && WxPaymentCallbackInboxServiceImpl.sha256Hex("wx-notify-secret")
                                .equals(row.getNotifyHash())
                        && Integer.valueOf(WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT).equals(row.getBizType())
                        && Long.valueOf(42L).equals(row.getBizId())
                        && "TRANSACTION.SUCCESS".equals(row.getEventType())
                        && Integer.valueOf(WxPaymentCallbackInbox.STATE_WAITING).equals(row.getState())));
    }

    @Test
    void duplicateNotifyIdIsAnsweredByIdempotentInsertNotASecondRow()
    {
        WxPaymentCallbackInboxMapper mapper = mock(WxPaymentCallbackInboxMapper.class);
        when(mapper.insertIgnore(any(WxPaymentCallbackInbox.class))).thenReturn(1, 0);
        WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertTrue(service.register("wx-notify-1", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 7L, "SUCCESS"));
        assertFalse(service.register("wx-notify-1", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 7L, "SUCCESS"));

        // 唯一键兜底「只落一行」由 MySQL IT 证明(见 WxPaymentCallbackFencingMySqlIT);
        // 这里证明重复投递仍走幂等 insert,不会走第二条写入路径。
        verify(mapper, times(2)).insertIgnore(any(WxPaymentCallbackInbox.class));
        verify(mapper, never()).selectForUpdate(anyString());
    }

    @Test
    void invalidRegisterArgumentsNeverTouchDatabase()
    {
        WxPaymentCallbackInboxMapper mapper = mock(WxPaymentCallbackInboxMapper.class);
        WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertFalse(service.register("", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 7L, "SUCCESS"));
        assertFalse(service.register(null, WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 7L, "SUCCESS"));
        assertFalse(service.register("wx-notify-1", 0, 7L, "SUCCESS"));
        assertFalse(service.register("wx-notify-1", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, null, "SUCCESS"));
        assertFalse(service.register("wx-notify-1", WxPaymentCallbackInbox.BIZ_TYPE_PAYMENT, 0L, "SUCCESS"));

        verifyNoInteractions(mapper);
    }

    @Test
    void isRegisteredHashesNotifyIdBeforeLookup()
    {
        WxPaymentCallbackInboxMapper mapper = mock(WxPaymentCallbackInboxMapper.class);
        when(mapper.selectByNotifyHash(WxPaymentCallbackInboxServiceImpl.sha256Hex("wx-notify-1")))
                .thenReturn(new WxPaymentCallbackInbox());
        WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertTrue(service.isRegistered("wx-notify-1"));
        assertFalse(service.isRegistered(""));
        assertFalse(service.isRegistered(null));
        verify(mapper).selectByNotifyHash(anyString());
    }

    @Test
    void failedMutationReleasesOnlyTheClaimedOwnerAndFence()
    {
        WxPaymentCallbackClaimService claimService = mock(WxPaymentCallbackClaimService.class);
        WxPaymentCallbackMutationService mutationService = mock(WxPaymentCallbackMutationService.class);
        WxPaymentCallbackClaim claim = WxPaymentCallbackClaim.acquired("hash", "owner-a", 7L);
        when(claimService.claim(anyString())).thenReturn(claim);
        when(mutationService.apply(any(WxPaymentCallbackClaim.class),
                any(IWxPaymentCallbackInboxService.Mutation.class))).thenReturn(false);
        WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "claimService", claimService);
        ReflectionTestUtils.setField(service, "mutationService", mutationService);

        boolean handled = service.process("notify", mock(IWxPaymentCallbackInboxService.Mutation.class));

        assertFalse(handled);
        verify(claimService).release(WxPaymentCallbackInboxServiceImpl.sha256Hex("notify"), "owner-a", 7L);
    }

    @Test
    void duplicateDoneDoesNotRunBusinessMutationOrReleaseAnotherOwner()
    {
        WxPaymentCallbackClaimService claimService = mock(WxPaymentCallbackClaimService.class);
        WxPaymentCallbackMutationService mutationService = mock(WxPaymentCallbackMutationService.class);
        when(claimService.claim(anyString())).thenReturn(WxPaymentCallbackClaim.done("hash"));
        WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "claimService", claimService);
        ReflectionTestUtils.setField(service, "mutationService", mutationService);

        assertTrue(service.process("notify", mock(IWxPaymentCallbackInboxService.Mutation.class)));

        verify(mutationService, never()).apply(any(), any());
        verify(claimService, never()).release(anyString(), anyString(), anyLong());
    }

    @Test
    void staleEventIsAckedWithoutRunningMutation()
    {
        WxPaymentCallbackClaimService claimService = mock(WxPaymentCallbackClaimService.class);
        WxPaymentCallbackMutationService mutationService = mock(WxPaymentCallbackMutationService.class);
        when(claimService.claim(anyString())).thenReturn(WxPaymentCallbackClaim.stale("hash"));
        WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "claimService", claimService);
        ReflectionTestUtils.setField(service, "mutationService", mutationService);

        assertTrue(service.process("notify", mock(IWxPaymentCallbackInboxService.Mutation.class)));

        verify(mutationService, never()).apply(any(), any());
        verify(claimService, never()).release(anyString(), anyString(), anyLong());
    }

    @Test
    void busyClaimIsNotRetriedInsideTheSameDelivery()
    {
        WxPaymentCallbackClaimService claimService = mock(WxPaymentCallbackClaimService.class);
        WxPaymentCallbackMutationService mutationService = mock(WxPaymentCallbackMutationService.class);
        when(claimService.claim(anyString()))
                .thenReturn(WxPaymentCallbackClaim.of(WxPaymentCallbackClaim.State.BUSY, "hash"));
        WxPaymentCallbackInboxServiceImpl service = new WxPaymentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "claimService", claimService);
        ReflectionTestUtils.setField(service, "mutationService", mutationService);

        assertFalse(service.process("notify", mock(IWxPaymentCallbackInboxService.Mutation.class)));

        verify(mutationService, never()).apply(any(), any());
        verify(claimService, never()).release(anyString(), anyString(), anyLong());
    }
}
