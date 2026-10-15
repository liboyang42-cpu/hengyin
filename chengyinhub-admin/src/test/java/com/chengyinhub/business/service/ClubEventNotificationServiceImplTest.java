package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.domain.dto.ClubEventCampaignRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.service.impl.ClubEventNotificationServiceImpl;
import com.chengyinhub.business.service.impl.ClubEventNotificationTransactionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubEventNotificationServiceImplTest {
    private static final Long ACTOR = 41L;
    private static final Long CLUB = 21L;
    private static final Long ACTIVITY = 31L;
    private IClubAccessService access;
    private ClubEventOpsMapper mapper;
    private ClubMapper clubs;
    private IImService im;
    private ClubEventNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IClubAccessService.class);
        mapper = mock(ClubEventOpsMapper.class);
        clubs = mock(ClubMapper.class);
        im = mock(IImService.class);
        service = new ClubEventNotificationServiceImpl(access, mapper,
                new ClubEventNotificationTransactionService(mapper, im, access, clubs));
        ReflectionTestUtils.setField(service, "wxContentSecurityService", mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(service, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        when(mapper.isEligibleNotificationRecipient(any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.claimNotificationOutbox(any(), any())).thenReturn(1);
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE))
                .thenReturn(eventAccess());
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(activeClub());
    }

    @Test
    void audienceCountsIncludesSixGroupsAndLeavesUnscopedEventCountsUnknown() {
        when(access.require(ACTOR, CLUB, null, ClubPermission.NOTIFY_SEND))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, 99L, null,
                        Collections.singleton(ClubRoleCode.CLUB_OPERATOR)));
        when(mapper.selectAllMemberRecipients(CLUB)).thenReturn(Arrays.asList(51L, 52L));
        when(mapper.selectAdminRecipients(CLUB)).thenReturn(Arrays.asList(51L, 51L));
        when(mapper.selectInactiveRecipients(CLUB)).thenReturn(Collections.emptyList());

        Map<?, ?> counts = (Map<?, ?>) service.audienceCounts(ACTOR, CLUB, null).get("counts");
        assertEquals(6, counts.size());
        assertEquals(2, counts.get("ALL_MEMBERS"));
        assertEquals(1, counts.get("ADMINS"));
        assertEquals(0, counts.get("INACTIVE"));
        for (String group : Arrays.asList("REGISTERED", "WAITLIST", "NO_SHOW")) {
            assertEquals(true, counts.containsKey(group));
            assertEquals(null, counts.get(group));
        }
        verify(mapper, never()).selectRegisteredRecipients(any(), any());
    }

    @Test
    void scopedAudienceCountsKeepsActivityPermissionAndSameRecipientLimits() {
        when(mapper.selectAdminRecipients(CLUB)).thenReturn(Collections.emptyList());
        when(mapper.selectInactiveRecipients(CLUB)).thenReturn(Arrays.asList(51L, 52L));
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(51L));
        Map<?, ?> counts = (Map<?, ?>) service.audienceCounts(ACTOR, CLUB, ACTIVITY).get("counts");
        assertEquals(6, counts.size());
        assertEquals(0, counts.get("ADMINS"));
        assertEquals(2, counts.get("INACTIVE"));
        assertEquals(1, counts.get("REGISTERED"));
        assertEquals(0, counts.get("WAITLIST"));
        assertEquals(0, counts.get("NO_SHOW"));
        verify(access).require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE);

        when(mapper.selectInactiveRecipients(CLUB)).thenReturn(
                java.util.stream.LongStream.rangeClosed(1, 501).boxed().collect(java.util.stream.Collectors.toList()));
        assertThrows(ServiceException.class, () -> service.audienceCounts(ACTOR, CLUB, ACTIVITY));
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE))
                .thenThrow(new ServiceException("无权限", HttpStatus.FORBIDDEN));
        assertThrows(ServiceException.class, () -> service.audienceCounts(ACTOR, CLUB, ACTIVITY));
    }

    @Test
    void previewReturnsCountAndExplicitWechatUnavailabilityWithoutPii() {
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Arrays.asList(51L, 52L));

        Map<String, Object> preview = service.preview(ACTOR, request("preview-1"));

        assertEquals(2, preview.get("recipientCount"));
        assertEquals("AVAILABLE", preview.get("inApp"));
        assertEquals("UNAVAILABLE", preview.get("wechatSubscription"));
        assertFalse(preview.containsKey("phones"));
        verify(access).require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE);
    }

    @Test
    void clubWideAudienceUsesDedicatedNotificationPermissionInsteadOfActivityManage() {
        ClubEventCampaignRequest request = request("all-members");
        request.setAudienceType("ALL_MEMBERS");
        when(access.require(ACTOR, CLUB, null, ClubPermission.NOTIFY_SEND))
                .thenReturn(new ClubAccessContext(ACTOR, CLUB, 99L, null,
                        Collections.singleton(ClubRoleCode.CLUB_OPERATOR)));
        when(mapper.selectAllMemberRecipients(CLUB)).thenReturn(Collections.singletonList(51L));

        Map<String, Object> preview = service.preview(ACTOR, request);

        assertEquals(1, preview.get("recipientCount"));
        verify(access).require(ACTOR, CLUB, null, ClubPermission.NOTIFY_SEND);
        verify(access, never()).require(ACTOR, CLUB, null, ClubPermission.EVENT_OPERATE);
    }

    @Test
    void sendCannotCreateCampaignWhenEventRoleIsRevokedWhileWaitingForClubLock() {
        AtomicBoolean clubLockAcquired = new AtomicBoolean(false);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenAnswer(call -> {
            clubLockAcquired.set(true);
            return activeClub();
        });
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE)).thenAnswer(call -> {
            if (!clubLockAcquired.get()) return eventAccess();
            throw new ServiceException("活动岗位已撤销", HttpStatus.FORBIDDEN);
        });
        when(mapper.insertCampaign(any())).thenAnswer(call -> {
            call.getArgument(0, ClubEventCampaign.class).setId(61L);
            return 1;
        });

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.send(ACTOR, request("send-revoked-after-lock")));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(mapper, never()).insertCampaign(any());
        verify(mapper, never()).insertDelivery(any());
        verify(mapper, never()).insertOutbox(any(), any());
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    @Test
    void manualRetryCannotClaimDeliveryWhenEventRoleIsRevokedWhileWaitingForClubLock() {
        AtomicBoolean clubLockAcquired = new AtomicBoolean(false);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenAnswer(call -> {
            clubLockAcquired.set(true);
            return activeClub();
        });
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE)).thenAnswer(call -> {
            if (!clubLockAcquired.get()) return eventAccess();
            throw new ServiceException("活动岗位已撤销", HttpStatus.FORBIDDEN);
        });
        when(mapper.selectCampaignById(61L)).thenReturn(campaign(61L));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.retryFailed(ACTOR, 61L));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(mapper, never()).selectDispatchableByCampaign(any(), any(Integer.class));
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    @Test
    void dispatchRejectsMissingCountRefreshReceiptInsteadOfSynthesizingSuccess() {
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Collections.emptyList());
        when(mapper.insertCampaign(any())).thenAnswer(call -> {
            call.getArgument(0, ClubEventCampaign.class).setId(61L);
            return 1;
        });
        when(mapper.selectCampaignById(61L)).thenReturn(campaign(
                61L, "missing-count-refresh", 0, 0, 0, ClubEventCampaign.PROCESSING));
        when(mapper.refreshCampaignCounts(61L)).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.send(ACTOR, request("missing-count-refresh")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
    }

    @Test
    void dispatchRejectsMissingAuthoritativeReadbackInsteadOfSynthesizingSuccess() {
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Collections.emptyList());
        when(mapper.insertCampaign(any())).thenAnswer(call -> {
            call.getArgument(0, ClubEventCampaign.class).setId(61L);
            return 1;
        });
        when(mapper.refreshCampaignCounts(61L)).thenReturn(1);
        when(mapper.selectCampaignById(61L)).thenReturn(
                campaign(61L, "missing-campaign-readback", 0, 0, 0, ClubEventCampaign.PROCESSING),
                null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.send(ACTOR, request("missing-campaign-readback")));

        assertEquals(HttpStatus.CONFLICT, error.getCode());
    }

    @Test
    void dispatchRejectsWrongScopeOrImpossibleCountsFromAuthoritativeReadback() {
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Collections.emptyList());
        when(mapper.insertCampaign(any())).thenAnswer(call -> {
            call.getArgument(0, ClubEventCampaign.class).setId(61L);
            return 1;
        });
        when(mapper.refreshCampaignCounts(61L)).thenReturn(1);
        ClubEventCampaign currentWrongScopeRequest = campaign(
                61L, "wrong-scope-readback", 0, 0, 0, ClubEventCampaign.PROCESSING);
        ClubEventCampaign wrongScope = campaign(
                61L, "wrong-scope-readback", 0, 0, 0, ClubEventCampaign.COMPLETED);
        wrongScope.setClubId(999L);
        ClubEventCampaign currentImpossibleCountRequest = campaign(
                61L, "impossible-count-readback", 0, 0, 0, ClubEventCampaign.PROCESSING);
        ClubEventCampaign impossibleCounts = campaign(
                61L, "impossible-count-readback", 0, 2, 0, ClubEventCampaign.COMPLETED);
        when(mapper.selectCampaignById(61L)).thenReturn(
                currentWrongScopeRequest, wrongScope,
                currentImpossibleCountRequest, impossibleCounts);

        ServiceException wrongScopeError = assertThrows(ServiceException.class,
                () -> service.send(ACTOR, request("wrong-scope-readback")));
        ServiceException impossibleCountError = assertThrows(ServiceException.class,
                () -> service.send(ACTOR, request("impossible-count-readback")));

        assertEquals(HttpStatus.CONFLICT, wrongScopeError.getCode());
        assertEquals(HttpStatus.CONFLICT, impossibleCountError.getCode());
    }

    @Test
    void uncheckedNotificationCannotCreateCampaignOrContactRecipients() {
        com.chengyinhub.business.service.IWxContentSecurityService safety =
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        when(safety.checkText(anyString(), any())).thenReturn("安全检测不可用");
        ReflectionTestUtils.setField(service, "wxContentSecurityService", safety);
        assertThrows(ServiceException.class, () -> service.send(ACTOR, request("unsafe-notification")));
        verify(mapper, never()).insertCampaign(any());
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    @Test
    void sendPersistsPerRecipientDeliveryAndKeepsPartialFailureRetryable() {
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Arrays.asList(51L, 52L));
        when(mapper.insertCampaign(any())).thenAnswer(invocation -> {
            ClubEventCampaign row = invocation.getArgument(0);
            row.setId(61L);
            return 1;
        });
        when(mapper.insertDelivery(any())).thenAnswer(invocation -> {
            ClubEventDelivery row = invocation.getArgument(0);
            row.setId(70L + row.getRecipientMemberId());
            return 1;
        });
        when(mapper.insertOutbox(any(), any())).thenReturn(1);
        ClubEventDelivery first = delivery(121L, 51L);
        ClubEventDelivery second = delivery(122L, 52L);
        when(mapper.selectDispatchableByCampaign(61L, 500)).thenReturn(Arrays.asList(first, second));
        when(mapper.selectDeliveryByIdForUpdate(121L)).thenReturn(first);
        when(mapper.selectDeliveryByIdForUpdate(122L)).thenReturn(second);
        when(im.sendSystem(eq(51L), eq(1), any(), any())).thenReturn(501L);
        when(im.sendSystem(eq(52L), eq(1), any(), any())).thenThrow(new RuntimeException("IM unavailable"));
        when(mapper.markDeliverySentCas(eq(121L), eq(0), eq(501L), any())).thenReturn(1);
        when(mapper.markDeliveryFailedCas(eq(122L), eq(0), any(), any())).thenReturn(1);
        when(mapper.completeOutbox(121L)).thenReturn(1);
        when(mapper.rescheduleOutbox(eq(122L), any())).thenReturn(1);
        when(mapper.refreshCampaignCounts(61L)).thenReturn(1);
        when(mapper.selectCampaignById(61L)).thenReturn(
                campaign(61L, "send-1", 2, 0, 0, ClubEventCampaign.PROCESSING),
                campaign(61L, "send-1", 2, 1, 1, ClubEventCampaign.PARTIAL_FAILED));

        ClubEventCampaign campaign = service.send(ACTOR, request("send-1"));

        assertEquals(Long.valueOf(61L), campaign.getId());
        assertEquals(Integer.valueOf(1), campaign.getSuccessCount());
        assertEquals(Integer.valueOf(1), campaign.getFailedCount());
        assertEquals(ClubEventCampaign.PARTIAL_FAILED, campaign.getStatus());
        verify(im).sendSystem(eq(51L), eq(1), any(), any());
        verify(im).sendSystem(eq(52L), eq(1), any(), any());
    }

    @Test
    void recipientWhoBecameIneligibleIsCompletedWithoutSending() {
        when(mapper.selectRegisteredRecipients(CLUB, ACTIVITY)).thenReturn(Collections.singletonList(51L));
        when(mapper.insertCampaign(any())).thenAnswer(invocation -> {
            ClubEventCampaign row = invocation.getArgument(0);
            row.setId(61L);
            return 1;
        });
        when(mapper.insertDelivery(any())).thenAnswer(invocation -> {
            ClubEventDelivery row = invocation.getArgument(0);
            row.setId(121L);
            return 1;
        });
        when(mapper.insertOutbox(any(), any())).thenReturn(1);
        ClubEventDelivery delivery = delivery(121L, 51L);
        when(mapper.selectDispatchableByCampaign(61L, 500)).thenReturn(Collections.singletonList(delivery));
        when(mapper.selectDeliveryByIdForUpdate(121L)).thenReturn(delivery);
        when(mapper.isEligibleNotificationRecipient(61L, CLUB, ACTIVITY, "REGISTERED", 51L)).thenReturn(0);
        when(mapper.markDeliverySkippedIneligibleCas(121L, 0)).thenReturn(1);
        when(mapper.completeOutbox(121L)).thenReturn(1);
        when(mapper.refreshCampaignCounts(61L)).thenReturn(1);
        when(mapper.selectCampaignById(61L)).thenReturn(
                campaign(61L, "membership-revoked", 1, 0, 0, ClubEventCampaign.PROCESSING),
                campaign(61L, "membership-revoked", 1, 0, 0, ClubEventCampaign.COMPLETED));
        when(im.sendSystem(eq(51L), eq(1), any(), any())).thenReturn(501L);
        when(mapper.markDeliverySentCas(eq(121L), eq(0), eq(501L), any())).thenReturn(1);

        ClubEventCampaign campaign = service.send(ACTOR, request("membership-revoked"));

        assertEquals(ClubEventCampaign.COMPLETED, campaign.getStatus());
        assertEquals(Integer.valueOf(0), campaign.getSuccessCount());
        assertEquals(Integer.valueOf(0), campaign.getFailedCount());
        verify(mapper).markDeliverySkippedIneligibleCas(121L, 0);
        verify(mapper).completeOutbox(121L);
        verify(im, never()).sendSystem(any(), any(), any(), any());
        verify(mapper, never()).markDeliverySentCas(any(), any(), any(), any());
    }

    @Test
    void manualRetryDurablyClaimsDueOutboxBeforeExternalDelivery() {
        ClubEventCampaign pending = campaign(
                61L, "manual-retry", 1, 0, 1, ClubEventCampaign.PARTIAL_FAILED);
        ClubEventCampaign refreshed = campaign(
                61L, "manual-retry", 1, 1, 0, ClubEventCampaign.COMPLETED);
        ClubEventDelivery delivery = delivery(121L, 51L);
        delivery.setStatus(ClubEventDelivery.FAILED);
        delivery.setAttemptCount(1);
        delivery.setVersion(1);
        when(mapper.selectCampaignById(61L)).thenReturn(pending, pending, refreshed);
        when(mapper.selectDispatchableByCampaign(61L, 500)).thenReturn(Collections.singletonList(delivery));
        when(mapper.selectDeliveryByIdForUpdate(121L)).thenReturn(delivery);
        when(im.sendSystem(eq(51L), eq(1), any(), any())).thenReturn(501L);
        when(mapper.markDeliverySentCas(eq(121L), eq(1), eq(501L), any())).thenReturn(1);
        when(mapper.completeOutbox(121L)).thenReturn(1);
        when(mapper.refreshCampaignCounts(61L)).thenReturn(1);

        ClubEventCampaign result = service.retryFailed(ACTOR, 61L);

        assertEquals(ClubEventCampaign.COMPLETED, result.getStatus());
        InOrder durableBeforeExternal = inOrder(mapper, im);
        durableBeforeExternal.verify(mapper).claimNotificationOutbox(eq(121L), any());
        durableBeforeExternal.verify(im).sendSystem(eq(51L), eq(1), any(), any());
    }

    @Test
    void failedFailureReceiptDoesNotBlockLaterDeliveryInSameCampaign() {
        IClubEventNotificationTransactionService tx = mock(IClubEventNotificationTransactionService.class);
        ClubEventNotificationServiceImpl isolated = new ClubEventNotificationServiceImpl(access, mapper, tx);
        ClubEventCampaign campaign = campaign(
                61L, "retry-partial", 2, 0, 1, ClubEventCampaign.PARTIAL_FAILED);
        ClubEventCampaign refreshed = campaign(
                61L, "retry-partial", 2, 1, 0, ClubEventCampaign.PROCESSING);
        ClubEventDelivery poison = delivery(121L, 51L);
        ClubEventDelivery healthy = delivery(122L, 52L);
        when(mapper.selectCampaignById(61L)).thenReturn(campaign, refreshed);
        when(tx.claimAuthorized(eq(ACTOR), eq(campaign), eq(500), any()))
                .thenReturn(Arrays.asList(poison, healthy));
        when(tx.deliver(campaign, 121L)).thenThrow(new IllegalStateException("IM unavailable"));
        when(tx.recordFailure(eq(61L), eq(121L), any(), any()))
                .thenThrow(new IllegalStateException("failure receipt unavailable"));
        when(tx.deliver(campaign, 122L)).thenReturn(true);
        when(mapper.refreshCampaignCounts(61L)).thenReturn(1);

        isolated.retryFailed(ACTOR, 61L);

        verify(tx).deliver(campaign, 122L);
        verify(mapper).refreshCampaignCounts(61L);
    }

    @Test
    void poisonedCampaignDoesNotBlockNextDueCampaign() {
        IClubEventNotificationTransactionService tx = mock(IClubEventNotificationTransactionService.class);
        ClubEventNotificationServiceImpl isolated = new ClubEventNotificationServiceImpl(access, mapper, tx);
        ClubEventCampaign poisonCampaign = campaign(
                61L, "due-poison", 1, 0, 0, ClubEventCampaign.PROCESSING);
        ClubEventCampaign healthyCampaign = campaign(
                62L, "due-healthy", 1, 0, 0, ClubEventCampaign.PROCESSING);
        ClubEventCampaign healthyRefreshed = campaign(
                62L, "due-healthy", 1, 1, 0, ClubEventCampaign.COMPLETED);
        ClubEventDelivery wrongScope = delivery(121L, 51L);
        wrongScope.setCampaignId(999L);
        ClubEventDelivery healthy = delivery(222L, 52L);
        healthy.setCampaignId(62L);
        when(mapper.selectDueCampaignIds(any(), eq(10))).thenReturn(Arrays.asList(61L, 62L));
        when(mapper.selectCampaignById(61L)).thenReturn(poisonCampaign);
        when(mapper.selectCampaignById(62L)).thenReturn(healthyCampaign, healthyRefreshed);
        when(tx.claim(eq(poisonCampaign), eq(50), any())).thenReturn(Collections.singletonList(wrongScope));
        when(tx.claim(eq(healthyCampaign), eq(50), any())).thenReturn(Collections.singletonList(healthy));
        when(tx.deliver(healthyCampaign, 222L)).thenReturn(true);
        when(mapper.refreshCampaignCounts(62L)).thenReturn(1);

        int processed = isolated.retryDue();

        assertEquals(1, processed);
        verify(tx).deliver(healthyCampaign, 222L);
    }

    @Test
    void replayedCampaignDoesNotSendAgain() {
        ClubEventCampaign existing = new ClubEventCampaign();
        existing.setId(61L);
        existing.setClubId(CLUB);
        existing.setActivityId(ACTIVITY);
        existing.setAudienceType("REGISTERED");
        existing.setChannel("IN_APP");
        existing.setTitle("集合提醒");
        existing.setContent("请提前十分钟到场");
        existing.setActorMemberId(ACTOR);
        existing.setRequestId("same-request");
        when(mapper.selectCampaignByRequest(CLUB, ACTOR, "same-request")).thenReturn(existing);

        assertEquals(existing, service.send(ACTOR, request("same-request")));
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    @Test
    void orchestrationHasNoSharedTransactionAndReceiptsUseRequiresNew() throws Exception {
        assertEquals(null, ClubEventNotificationServiceImpl.class
                .getMethod("send", Long.class, ClubEventCampaignRequest.class)
                .getAnnotation(Transactional.class));
        assertEquals(null, ClubEventNotificationServiceImpl.class
                .getMethod("retryFailed", Long.class, Long.class)
                .getAnnotation(Transactional.class));
        assertEquals(null, ClubEventNotificationServiceImpl.class
                .getMethod("retryDue")
                .getAnnotation(Transactional.class));

        assertRequiresNew("create", ClubEventCampaign.class, java.util.List.class, java.util.Date.class);
        assertRequiresNew("claim", ClubEventCampaign.class, int.class, java.util.Date.class);
        assertRequiresNew("claimAuthorized", Long.class, ClubEventCampaign.class,
                int.class, java.util.Date.class);
        assertRequiresNew("deliver", ClubEventCampaign.class, Long.class);
        assertRequiresNew("recordFailure", Long.class, Long.class, String.class, java.util.Date.class);
    }

    @Test
    void unsupportedWechatChannelIsExplicitlyUnavailableBeforeRecipientQuery() {
        ClubEventCampaignRequest request = request("wechat-1");
        request.setChannel("WECHAT_SUBSCRIPTION");

        com.chengyinhub.common.exception.ServiceException error = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.send(ACTOR, request));

        assertEquals(Integer.valueOf(501), error.getCode());
        verify(mapper, never()).selectRegisteredRecipients(any(), any());
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    @Test
    void publicPreviewAndSendRejectReservedCancellationSnapshotPrefixes() {
        ClubEventCampaignRequest preview = request("cancel-w-forged");
        preview.setAudienceType("WAITLIST");
        com.chengyinhub.common.exception.ServiceException previewError = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.preview(ACTOR, preview));

        ClubEventCampaignRequest send = request("CANCEL-R-forged");
        com.chengyinhub.common.exception.ServiceException sendError = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.send(ACTOR, send));

        assertEquals(Integer.valueOf(400), previewError.getCode());
        assertEquals(Integer.valueOf(400), sendError.getCode());
        verify(mapper, never()).selectWaitlistRecipients(any(), any());
        verify(mapper, never()).selectRegisteredRecipients(any(), any());
        verify(mapper, never()).insertCampaign(any());
    }

    /**
     * CU-C-135(2026-09-25 走查):投递卡在「总 4 / 送达 0 / 失败 0」再也不动 —— 页面只有
     * send 那一次响应,没有任何回读入口。这里钉住新增的只读回读:拿到的是库里推进后的数,
     * 且**读**这个动作本身不许推进投递。
     *
     * <p>⚠️ 负控:把 {@code campaign} 实现成走 {@code retryFailed}(或任何 dispatch 路径)⇒
     * claimNotificationOutbox / sendSystem / refreshCampaignCounts 三条 never() 一起红。
     */
    @Test
    void campaignReadReturnsAdvancedCountsWithoutDispatchingAnything() {
        when(mapper.selectCampaignById(61L))
                .thenReturn(campaign(61L, "campaign-61", 4, 3, 0, ClubEventCampaign.PROCESSING));

        ClubEventCampaign result = service.campaign(ACTOR, 61L);

        assertEquals(Integer.valueOf(4), result.getTotalCount());
        assertEquals(Integer.valueOf(3), result.getSuccessCount(),
                "刷新要读到投递推进后的数,不能把页面里那份旧快照再吐回去");
        verify(access).require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE);
        verify(mapper, never()).claimNotificationOutbox(any(), any());
        verify(mapper, never()).selectDispatchableByCampaign(any(), any(Integer.class));
        verify(mapper, never()).refreshCampaignCounts(any());
        verify(im, never()).sendSystem(any(), any(), any(), any());
    }

    /** 读与改共用同一道闸:不能「能改的才能改,但谁都能读」——那等于凭 campaignId 探别人的投递人数。 */
    @Test
    void campaignReadRefusesACampaignTheActorCannotNotify() {
        when(mapper.selectCampaignById(61L)).thenReturn(campaign(61L));
        when(access.require(ACTOR, CLUB, ACTIVITY, ClubPermission.EVENT_OPERATE))
                .thenThrow(new ServiceException("没有该活动的通知权限", HttpStatus.FORBIDDEN));

        ServiceException error = assertThrows(ServiceException.class, () -> service.campaign(ACTOR, 61L));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(mapper, never()).refreshCampaignCounts(any());
    }

    @Test
    void campaignReadRejectsIllegalIdBeforeTouchingTheDatabase() {
        assertThrows(ServiceException.class, () -> service.campaign(ACTOR, 0L));
        assertThrows(ServiceException.class, () -> service.campaign(ACTOR, null));

        verify(mapper, never()).selectCampaignById(any());
    }

    private ClubEventCampaignRequest request(String requestId) {
        ClubEventCampaignRequest request = new ClubEventCampaignRequest();
        request.setClubId(CLUB);
        request.setActivityId(ACTIVITY);
        request.setAudienceType("REGISTERED");
        request.setChannel("IN_APP");
        request.setTitle("集合提醒");
        request.setContent("请提前十分钟到场");
        request.setRequestId(requestId);
        return request;
    }

    private ClubAccessContext eventAccess() {
        return new ClubAccessContext(ACTOR, CLUB, 99L, ACTIVITY,
                Collections.singleton(ClubRoleCode.EVENT_LEAD));
    }

    private Club activeClub() {
        Club row = new Club();
        row.setId(CLUB);
        row.setMemberId(99L);
        row.setStatus(1L);
        row.setDelFlag(0);
        return row;
    }

    private ClubEventDelivery delivery(Long id, Long memberId) {
        ClubEventDelivery row = new ClubEventDelivery();
        row.setId(id);
        row.setCampaignId(61L);
        row.setRecipientMemberId(memberId);
        row.setChannel("IN_APP");
        row.setStatus(ClubEventDelivery.PENDING);
        row.setAttemptCount(0);
        row.setVersion(0);
        return row;
    }

    private ClubEventCampaign campaign(Long id) {
        return campaign(id, "campaign-" + id, 1, 0, 0, ClubEventCampaign.PROCESSING);
    }

    private ClubEventCampaign campaign(Long id, String requestId, int total, int success,
                                       int failed, String status) {
        ClubEventCampaign row = new ClubEventCampaign();
        row.setId(id);
        row.setClubId(CLUB);
        row.setActivityId(ACTIVITY);
        row.setAudienceType("REGISTERED");
        row.setChannel("IN_APP");
        row.setTitle("集合提醒");
        row.setContent("请提前十分钟到场");
        row.setActorMemberId(ACTOR);
        row.setRequestId(requestId);
        row.setStatus(status);
        row.setTotalCount(total);
        row.setSuccessCount(success);
        row.setFailedCount(failed);
        return row;
    }

    private void assertRequiresNew(String method, Class<?>... arguments) throws Exception {
        Transactional annotation = ClubEventNotificationTransactionService.class
                .getMethod(method, arguments).getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation());
    }
}
