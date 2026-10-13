package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentCallbackInboxService;

class WxContentCallbackInboxServiceImplTest
{
    @Test
    void registerPersistsHashesInsteadOfRawTraceOrMediaUrl()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        when(mapper.insertIgnore(any(WxContentCallbackInbox.class))).thenReturn(1);
        WxContentCallbackInboxServiceImpl service = new WxContentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertTrue(service.register("trace-secret", 9, 42L, "https://media.example/secret.jpg"));

        verify(mapper).insertIgnore(org.mockito.ArgumentMatchers.argThat(row ->
                !"trace-secret".equals(row.getTraceHash())
                        && !"https://media.example/secret.jpg".equals(row.getMediaHash())
                        && row.getTraceHash().length() == 64
                        && row.getMediaHash().length() == 64));
    }

    @Test
    void failedMutationReleasesOnlyTheClaimedOwnerAndFence()
    {
        WxContentCallbackClaimService claimService = mock(WxContentCallbackClaimService.class);
        WxContentCallbackMutationService mutationService = mock(WxContentCallbackMutationService.class);
        WxContentCallbackClaim claim = WxContentCallbackClaim.acquired("hash", "owner-a", 7L);
        when(claimService.claim(anyString(), org.mockito.ArgumentMatchers.eq(true))).thenReturn(claim);
        when(mutationService.apply(any(WxContentCallbackClaim.class), org.mockito.ArgumentMatchers.eq(true),
                any(IWxContentCallbackInboxService.Mutation.class))).thenReturn(false);
        WxContentCallbackInboxServiceImpl service = new WxContentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "claimService", claimService);
        ReflectionTestUtils.setField(service, "mutationService", mutationService);

        boolean handled = service.process("trace", true, mock(IWxContentCallbackInboxService.Mutation.class));

        assertFalse(handled);
        verify(claimService).release(WxContentCallbackInboxServiceImpl.sha256Hex("trace"), "owner-a", 7L);
    }

    @Test
    void duplicateDoneDoesNotRunBusinessMutationOrReleaseAnotherOwner()
    {
        WxContentCallbackClaimService claimService = mock(WxContentCallbackClaimService.class);
        WxContentCallbackMutationService mutationService = mock(WxContentCallbackMutationService.class);
        when(claimService.claim(anyString(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(WxContentCallbackClaim.done("hash"));
        WxContentCallbackInboxServiceImpl service = new WxContentCallbackInboxServiceImpl();
        ReflectionTestUtils.setField(service, "claimService", claimService);
        ReflectionTestUtils.setField(service, "mutationService", mutationService);

        assertTrue(service.process("trace", false, mock(IWxContentCallbackInboxService.Mutation.class)));

        verify(mutationService, never()).apply(any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(claimService, never()).release(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }
}
