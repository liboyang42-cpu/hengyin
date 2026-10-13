package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicReference;

import com.chengyinhub.business.domain.WxContentCallbackClaim;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;

class WxContentCallbackClaimServiceTest
{
    @Test
    void claimReturnsIncrementedFenceAndAcceptsMysqlUppercaseOwnerHex()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        WxContentCallbackInbox waiting = row(WxContentCallbackInbox.STATE_WAITING, null, 4L, null);
        AtomicReference<String> owner = new AtomicReference<String>();
        when(mapper.selectForUpdate("hash")).thenReturn(waiting).thenAnswer(invocation ->
                row(WxContentCallbackInbox.STATE_PROCESSING, owner.get().toUpperCase(), 5L, 1));
        when(mapper.claim(org.mockito.ArgumentMatchers.eq("hash"), anyString(),
                org.mockito.ArgumentMatchers.eq(1))).thenAnswer(invocation -> {
                    owner.set(invocation.getArgument(1, String.class));
                    return 1;
                });
        WxContentCallbackClaimService service = new WxContentCallbackClaimService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        WxContentCallbackClaim claim = service.claim("hash", true);

        assertEquals(WxContentCallbackClaim.State.ACQUIRED, claim.getState());
        assertEquals(5L, claim.getFenceToken());
    }

    @Test
    void conflictingVerdictIsRejectedWithoutTakingOverLease()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        when(mapper.selectForUpdate("hash")).thenReturn(
                row(WxContentCallbackInbox.STATE_RETRYABLE, null, 2L, 1));
        WxContentCallbackClaimService service = new WxContentCallbackClaimService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        WxContentCallbackClaim claim = service.claim("hash", false);

        assertEquals(WxContentCallbackClaim.State.REJECTED, claim.getState());
        verify(mapper, never()).claim(org.mockito.ArgumentMatchers.anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void manualReviewIsTerminalForSameVerdictAndRejectsConflictingVerdict()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        when(mapper.selectForUpdate("same")).thenReturn(
                row(WxContentCallbackInbox.STATE_MANUAL_REVIEW, null, 2L, 1));
        when(mapper.selectForUpdate("conflict")).thenReturn(
                row(WxContentCallbackInbox.STATE_MANUAL_REVIEW, null, 2L, 1));
        WxContentCallbackClaimService service = new WxContentCallbackClaimService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertEquals(WxContentCallbackClaim.State.DONE, service.claim("same", true).getState());
        assertEquals(WxContentCallbackClaim.State.REJECTED, service.claim("conflict", false).getState());

        verify(mapper, never()).claim(org.mockito.ArgumentMatchers.anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    private WxContentCallbackInbox row(int state, String owner, long fence, Integer verdict)
    {
        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setState(state);
        row.setOwnerToken(owner);
        row.setFenceToken(fence);
        row.setVerdict(verdict);
        return row;
    }
}
