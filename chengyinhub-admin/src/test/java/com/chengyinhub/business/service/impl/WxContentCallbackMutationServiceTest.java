package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class WxContentCallbackMutationServiceTest
{
    @Test
    void expiredOldOwnerCannotMutateOrCompleteAfterTakeover()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        IWxContentCallbackInboxService.Mutation mutation = mock(IWxContentCallbackInboxService.Mutation.class);
        WxContentCallbackClaim oldClaim = WxContentCallbackClaim.acquired("hash", "owner-old", 3L);
        WxContentCallbackInbox current = new WxContentCallbackInbox();
        current.setState(WxContentCallbackInbox.STATE_PROCESSING);
        current.setOwnerToken("owner-new");
        current.setFenceToken(4L);
        when(mapper.selectOwnedForUpdate("hash", "owner-old", 3L)).thenReturn(null);
        WxContentCallbackMutationService service = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertFalse(service.apply(oldClaim, true, mutation));

        verify(mapper, never()).lockBusinessTarget(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(mutation, never()).apply(any(WxContentCallbackInbox.class), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mapper, never()).markDone(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void zeroRowDoneIsFailureEvenAfterBusinessMutationReportsSuccess()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        IWxContentCallbackInboxService.Mutation mutation = mock(IWxContentCallbackInboxService.Mutation.class);
        WxContentCallbackClaim claim = WxContentCallbackClaim.acquired("hash", "owner", 8L);
        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setTraceHash("hash");
        row.setBizType(9);
        row.setBizId(42L);
        row.setMediaHash("media-hash");
        row.setState(WxContentCallbackInbox.STATE_PROCESSING);
        row.setOwnerToken("owner");
        row.setFenceToken(8L);
        when(mapper.selectOwnedForUpdate("hash", "owner", 8L)).thenReturn(row);
        when(mapper.lockBusinessTarget(9, 42L)).thenReturn(42L);
        when(mutation.isCurrent(row)).thenReturn(true);
        when(mutation.apply(row, true)).thenReturn(true);
        when(mapper.markDone("hash", "owner", 8L, 1)).thenReturn(0);
        WxContentCallbackMutationService service = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertThrows(WxContentCallbackMutationService.CallbackTransactionException.class,
                () -> service.apply(claim, true, mutation));

        verify(mutation).apply(row, true);
        verify(mapper).markDone("hash", "owner", 8L, 1);
    }

    @Test
    void staleSubmittedMediaIsIgnoredWithoutApplyingBusinessMutation()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        IWxContentCallbackInboxService.Mutation mutation = mock(IWxContentCallbackInboxService.Mutation.class);
        WxContentCallbackClaim claim = WxContentCallbackClaim.acquired("hash", "owner", 8L);
        WxContentCallbackInbox row = processingRow(9, 42L, "media-hash");
        when(mapper.selectOwnedForUpdate("hash", "owner", 8L)).thenReturn(row);
        when(mapper.lockBusinessTarget(9, 42L)).thenReturn(42L);
        when(mutation.isCurrent(row)).thenReturn(false);
        when(mapper.markIgnoredStale("hash", "owner", 8L, 1)).thenReturn(1);
        WxContentCallbackMutationService service = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertTrue(service.apply(claim, true, mutation));

        verify(mapper).markIgnoredStale("hash", "owner", 8L, 1);
        verify(mutation, never()).apply(any(WxContentCallbackInbox.class), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mapper, never()).markDone(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void legacyMissingMediaHashIsManuallyReviewedBeforeTargetLockOrMutation()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        IWxContentCallbackInboxService.Mutation mutation = mock(IWxContentCallbackInboxService.Mutation.class);
        WxContentCallbackClaim claim = WxContentCallbackClaim.acquired("hash", "owner", 8L);
        WxContentCallbackInbox row = processingRow(9, 42L, null);
        when(mapper.selectOwnedForUpdate("hash", "owner", 8L)).thenReturn(row);
        when(mapper.markManualReview("hash", "owner", 8L, 1, "LEGACY_MEDIA_HASH_MISSING")).thenReturn(1);
        WxContentCallbackMutationService service = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertTrue(service.apply(claim, true, mutation));

        verify(mapper).markManualReview("hash", "owner", 8L, 1, "LEGACY_MEDIA_HASH_MISSING");
        verify(mapper, never()).lockBusinessTarget(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(mutation, never()).isCurrent(any(WxContentCallbackInbox.class));
        verify(mutation, never()).apply(any(WxContentCallbackInbox.class), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void nodeTemplateIsManuallyReviewedBeforeTargetLockOrMutation()
    {
        assertUnsupportedBusinessTypeIsManuallyReviewed(7);
    }

    /** AUDIT-G1-4:官方活动已有下线分支,不再整类转人工 —— 照常锁行后交给 mutation。 */
    @Test
    void officialEventLocksTargetAndMutatesInsteadOfManualReview()
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        IWxContentCallbackInboxService.Mutation mutation = mock(IWxContentCallbackInboxService.Mutation.class);
        WxContentCallbackClaim claim = WxContentCallbackClaim.acquired("hash", "owner", 8L);
        WxContentCallbackInbox row = processingRow(8, 42L, "media-hash");
        when(mapper.selectOwnedForUpdate("hash", "owner", 8L)).thenReturn(row);
        when(mapper.lockBusinessTarget(8, 42L)).thenReturn(42L);
        when(mutation.isCurrent(row)).thenReturn(true);
        when(mutation.apply(row, true)).thenReturn(true);
        when(mapper.markDone("hash", "owner", 8L, 1)).thenReturn(1);
        WxContentCallbackMutationService service = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertTrue(service.apply(claim, true, mutation));

        verify(mapper).lockBusinessTarget(8, 42L);
        verify(mutation).apply(row, true);
        verify(mapper, never()).markManualReview(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    private void assertUnsupportedBusinessTypeIsManuallyReviewed(int bizType)
    {
        WxContentCallbackInboxMapper mapper = mock(WxContentCallbackInboxMapper.class);
        IWxContentCallbackInboxService.Mutation mutation = mock(IWxContentCallbackInboxService.Mutation.class);
        WxContentCallbackClaim claim = WxContentCallbackClaim.acquired("hash", "owner", 8L);
        WxContentCallbackInbox row = processingRow(bizType, 42L, "media-hash");
        when(mapper.selectOwnedForUpdate("hash", "owner", 8L)).thenReturn(row);
        when(mapper.markManualReview("hash", "owner", 8L, 1, "UNSUPPORTED_AUTO_MUTATION")).thenReturn(1);
        WxContentCallbackMutationService service = new WxContentCallbackMutationService();
        ReflectionTestUtils.setField(service, "mapper", mapper);

        assertTrue(service.apply(claim, true, mutation));

        verify(mapper).markManualReview("hash", "owner", 8L, 1, "UNSUPPORTED_AUTO_MUTATION");
        verify(mapper, never()).lockBusinessTarget(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(mutation, never()).isCurrent(any(WxContentCallbackInbox.class));
        verify(mutation, never()).apply(any(WxContentCallbackInbox.class), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private WxContentCallbackInbox processingRow(int bizType, Long bizId, String mediaHash)
    {
        WxContentCallbackInbox row = new WxContentCallbackInbox();
        row.setTraceHash("hash");
        row.setBizType(bizType);
        row.setBizId(bizId);
        row.setMediaHash(mediaHash);
        row.setState(WxContentCallbackInbox.STATE_PROCESSING);
        row.setOwnerToken("owner");
        row.setFenceToken(8L);
        return row;
    }
}
