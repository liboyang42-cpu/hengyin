package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.domain.MerchantCrmCampaignRecipient;
import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantCrmCouponGrantAdapter;
import com.chengyinhub.business.service.impl.MerchantCrmDeliveryGuardService;
import com.chengyinhub.business.service.impl.MerchantCrmDeliveryOutcomeUnknownException;
import com.chengyinhub.business.service.impl.MerchantCrmNotificationAdapter;
import com.chengyinhub.business.service.impl.MerchantCrmRecipientDeliveryServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantCrmRecipientDeliveryServiceImplTest {
    private static final long OWNER = 900L;
    private static final long MERCHANT = 77L;
    private static final long CAMPAIGN = 8L;
    private static final long RECIPIENT = 9L;
    private static final long MEMBER = 41L;
    private MerchantCrmCampaignMapper campaignMapper;
    private MerchantCrmMarketingConsentMapper consentMapper;
    private MmsMerchantMapper merchantMapper;
    private MerchantCrmNotificationAdapter notification;
    private MerchantCrmCouponGrantAdapter coupon;
    private MerchantCrmDeliveryGuardService guardService;
    private BusinessAuditEventMapper auditMapper;
    private MerchantCrmRecipientDeliveryServiceImpl service;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(MerchantCrmCampaignMapper.class);
        consentMapper = mock(MerchantCrmMarketingConsentMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        notification = mock(MerchantCrmNotificationAdapter.class);
        coupon = mock(MerchantCrmCouponGrantAdapter.class);
        guardService = mock(MerchantCrmDeliveryGuardService.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        service = new MerchantCrmRecipientDeliveryServiceImpl();
        ReflectionTestUtils.setField(service, "campaignMapper", campaignMapper);
        ReflectionTestUtils.setField(service, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "consentMapper", consentMapper);
        ReflectionTestUtils.setField(service, "notificationAdapter", notification);
        ReflectionTestUtils.setField(service, "couponGrantAdapter", coupon);
        ReflectionTestUtils.setField(service, "deliveryGuardService", guardService);
        ReflectionTestUtils.setField(service, "auditMapper", auditMapper);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(merchantMapper.selectMmsMerchantByIdForUpdate(MERCHANT))
                .thenReturn(merchant(OWNER, 1L, 1L, 0));
        when(campaignMapper.selectOwnedCampaign(MERCHANT, CAMPAIGN)).thenReturn(campaign("IN_APP"));
    }

    @Test
    void ownerChangedAfterCampaignRenewalIsRejectedBeforeGuardOrExternalDelivery() {
        when(merchantMapper.selectMmsMerchantByIdForUpdate(MERCHANT))
                .thenReturn(merchant(OWNER + 1L, 1L, 1L, 0));
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP"))
                .thenReturn(optIn("IN_APP"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        when(notification.send(any(), any())).thenReturn(66L);
        when(campaignMapper.markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, 66L, null)).thenReturn(1);

        ServiceException failure = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));

        org.junit.jupiter.api.Assertions.assertEquals(409, failure.getCode());
        verify(merchantMapper).selectMmsMerchantByIdForUpdate(MERCHANT);
        verify(guardService, never()).claim(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(notification, never()).send(any(), any());
        verify(coupon, never()).grant(any(), any(), any(), any());
    }

    @Test
    void disabledMerchantIsRejectedBeforeRecipientGuardOrExternalDelivery() {
        when(merchantMapper.selectMmsMerchantByIdForUpdate(MERCHANT))
                .thenReturn(merchant(OWNER, 1L, 2L, 0));

        ServiceException failure = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));

        org.junit.jupiter.api.Assertions.assertEquals(409, failure.getCode());
        verify(campaignMapper, never()).selectRecipientForUpdate(anyLong(), anyLong(), anyLong());
        verify(guardService, never()).claim(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(notification, never()).send(any(), any());
        verify(coupon, never()).grant(any(), any(), any(), any());
    }

    @Test
    void forgedRecipientLineageIsRejectedBeforeGuardOrExternalDelivery() {
        MerchantCrmCampaignRecipient forged = recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING);
        forged.setCampaignId(CAMPAIGN + 1L);
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT)).thenReturn(forged);

        ServiceException failure = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));

        org.junit.jupiter.api.Assertions.assertEquals(409, failure.getCode());
        verify(guardService, never()).claim(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(notification, never()).send(any(), any());
        verify(coupon, never()).grant(any(), any(), any(), any());
    }

    @Test
    void optOutAfterSnapshotTakesEffectImmediatelyAndSkipsDelivery() {
        MerchantCrmCampaignRecipient row = recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING);
        row.setConsentEventIdSnapshot(55L);
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT)).thenReturn(row);
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(null);
        when(campaignMapper.markRecipientSkipped(MERCHANT, CAMPAIGN, RECIPIENT,
                MerchantCrmCampaignRecipient.SKIPPED_NO_CONSENT, "NO_CONSENT", "客户未同意或已退订")).thenReturn(1);

        service.deliver(MERCHANT, CAMPAIGN, RECIPIENT);

        verify(notification, never()).send(any(), any());
        verify(coupon, never()).grant(any(), any(), any(), any());
    }

    @Test
    void deliveredRecipientIsIdempotentAndNeverSendsAgain() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.DELIVERED));

        service.deliver(MERCHANT, CAMPAIGN, RECIPIENT);

        verify(consentMapper, never()).selectLatestOptIn(any(), any(), any(), any());
        verify(notification, never()).send(any(), any());
        verify(campaignMapper, never()).markRecipientDelivered(any(), any(), any(), any(), any());
    }

    @Test
    void inAppDeliveryUsesOwnerScopedLockAndPersistsRealMessageReceipt() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(optIn("IN_APP"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        when(notification.send(any(), any())).thenReturn(66L);
        when(campaignMapper.markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, 66L, null)).thenReturn(1);

        service.deliver(MERCHANT, CAMPAIGN, RECIPIENT);

        org.mockito.InOrder scopeOrder = inOrder(merchantMapper, campaignMapper);
        scopeOrder.verify(merchantMapper).selectMmsMerchantByIdForUpdate(MERCHANT);
        scopeOrder.verify(campaignMapper).selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT);
        scopeOrder.verify(campaignMapper).selectOwnedCampaign(MERCHANT, CAMPAIGN);
        org.mockito.InOrder order = inOrder(guardService, notification);
        order.verify(guardService).claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP");
        order.verify(guardService).beginDispatch(reserved);
        order.verify(notification).send(any(), any());
        order.verify(guardService).markDelivered(dispatching, 66L, null);
        verify(campaignMapper, never()).countDeliveredToday(anyLong(), anyLong(), anyString(), anyLong());
        verify(campaignMapper).markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, 66L, null);
    }

    @Test
    void couponDeliveryPersistsRealCouponHistoryReceipt() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("COUPON", MerchantCrmCampaignRecipient.PENDING));
        when(campaignMapper.selectOwnedCampaign(MERCHANT, CAMPAIGN)).thenReturn(campaign("COUPON"));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "COUPON")).thenReturn(optIn("COUPON"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "COUPON")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        when(coupon.grant(OWNER, MERCHANT, MEMBER, 5L)).thenReturn(77L);
        when(campaignMapper.markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, null, 77L)).thenReturn(1);

        service.deliver(MERCHANT, CAMPAIGN, RECIPIENT);

        verify(coupon).grant(OWNER, MERCHANT, MEMBER, 5L);
        verify(guardService).markDelivered(dispatching, null, 77L);
        verify(campaignMapper).markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, null, 77L);
    }

    @Test
    void dailyFrequencyCapSkipsSecondDeliveryForMerchantAndChannel() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(optIn("IN_APP"));
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP"))
                .thenReturn(MerchantCrmDeliveryGuardService.Claim.dailyCap());
        when(campaignMapper.markRecipientSkipped(MERCHANT, CAMPAIGN, RECIPIENT,
                MerchantCrmCampaignRecipient.SKIPPED_FREQUENCY, "DAILY_CAP",
                "客户今日已收到或已占用该商家同渠道触达额度")).thenReturn(1);

        service.deliver(MERCHANT, CAMPAIGN, RECIPIENT);

        verify(notification, never()).send(any(), any());
        verify(coupon, never()).grant(any(), any(), any(), any());
        verify(campaignMapper).markRecipientSkipped(MERCHANT, CAMPAIGN, RECIPIENT,
                MerchantCrmCampaignRecipient.SKIPPED_FREQUENCY, "DAILY_CAP",
                "客户今日已收到或已占用该商家同渠道触达额度");
    }

    @Test
    void twoCampaignsForSameOwnerMemberChannelOnlyCallExternalAdapterOnce() {
        long secondCampaign = 18L;
        long secondRecipient = 19L;
        MerchantCrmCampaignRecipient first = recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING);
        MerchantCrmCampaignRecipient second = recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING);
        second.setCampaignId(secondCampaign);
        second.setId(secondRecipient);
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT)).thenReturn(first);
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, secondCampaign, secondRecipient)).thenReturn(second);
        when(campaignMapper.selectOwnedCampaign(MERCHANT, secondCampaign)).thenReturn(campaign(secondCampaign, "IN_APP"));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(optIn("IN_APP"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        when(guardService.claim(MERCHANT, secondCampaign, secondRecipient, MEMBER, "IN_APP"))
                .thenReturn(MerchantCrmDeliveryGuardService.Claim.dailyCap());
        when(notification.send(any(), any())).thenReturn(66L);
        when(campaignMapper.markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, 66L, null)).thenReturn(1);
        when(campaignMapper.markRecipientSkipped(MERCHANT, secondCampaign, secondRecipient,
                MerchantCrmCampaignRecipient.SKIPPED_FREQUENCY, "DAILY_CAP",
                "客户今日已收到或已占用该商家同渠道触达额度")).thenReturn(1);

        service.deliver(MERCHANT, CAMPAIGN, RECIPIENT);
        service.deliver(MERCHANT, secondCampaign, secondRecipient);

        verify(notification).send(any(), any());
        verify(campaignMapper).markRecipientSkipped(MERCHANT, secondCampaign, secondRecipient,
                MerchantCrmCampaignRecipient.SKIPPED_FREQUENCY, "DAILY_CAP",
                "客户今日已收到或已占用该商家同渠道触达额度");
    }

    @Test
    void couponNotIssuedReleasesGuardAndPersistsRetryableCode() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("COUPON", MerchantCrmCampaignRecipient.PENDING));
        when(campaignMapper.selectOwnedCampaign(MERCHANT, CAMPAIGN)).thenReturn(campaign("COUPON"));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "COUPON")).thenReturn(optIn("COUPON"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "COUPON")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        MerchantCrmCouponGrantAdapter.GrantRejectedException exhausted =
                new MerchantCrmCouponGrantAdapter.GrantRejectedException(
                        "COUPON_NOT_ISSUED", "优惠券未发出，可能已停发、失效或库存不足", null);
        when(coupon.grant(OWNER, MERCHANT, MEMBER, 5L)).thenThrow(exhausted);
        when(campaignMapper.markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "COUPON_NOT_ISSUED", "优惠券未发出，恢复可发条件后可重试")).thenReturn(1);

        MerchantCrmCouponGrantAdapter.GrantRejectedException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        MerchantCrmCouponGrantAdapter.GrantRejectedException.class,
                        () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));
        service.recordFailure(MERCHANT, CAMPAIGN, RECIPIENT, failure);

        verify(guardService).releaseForRetry(dispatching);
        verify(guardService, never()).markOutcomeUnknown(any(), anyString());
        verify(campaignMapper).markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "COUPON_NOT_ISSUED", "优惠券未发出，恢复可发条件后可重试");
    }

    @Test
    void manualRetryAfterNotIssuedCanAcquireAgainAndDeliverOnce() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("COUPON", MerchantCrmCampaignRecipient.PENDING),
                        recipient("COUPON", MerchantCrmCampaignRecipient.PENDING),
                        recipient("COUPON", MerchantCrmCampaignRecipient.PENDING));
        when(campaignMapper.selectOwnedCampaign(MERCHANT, CAMPAIGN)).thenReturn(campaign("COUPON"));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "COUPON")).thenReturn(optIn("COUPON"));
        MerchantCrmDeliveryGuardService.Claim firstReserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim firstDispatching = dispatchingClaim();
        MerchantCrmDeliveryGuardService.Claim retryReserved =
                MerchantCrmDeliveryGuardService.Claim.acquired(101L, "retry-lease", 2L);
        MerchantCrmDeliveryGuardService.Claim retryDispatching =
                MerchantCrmDeliveryGuardService.Claim.acquired(101L, "retry-lease", 3L);
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "COUPON"))
                .thenReturn(firstReserved, retryReserved);
        when(guardService.beginDispatch(firstReserved)).thenReturn(firstDispatching);
        when(guardService.beginDispatch(retryReserved)).thenReturn(retryDispatching);
        when(coupon.grant(OWNER, MERCHANT, MEMBER, 5L))
                .thenThrow(new MerchantCrmCouponGrantAdapter.GrantRejectedException(
                        "COUPON_NOT_ISSUED", "优惠券未发出，可能已停发、失效或库存不足", null))
                .thenReturn(77L);
        when(campaignMapper.markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "COUPON_NOT_ISSUED", "优惠券未发出，恢复可发条件后可重试")).thenReturn(1);
        when(campaignMapper.markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, null, 77L)).thenReturn(1);

        MerchantCrmCouponGrantAdapter.GrantRejectedException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        MerchantCrmCouponGrantAdapter.GrantRejectedException.class,
                        () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));
        service.recordFailure(MERCHANT, CAMPAIGN, RECIPIENT, failure);
        service.deliver(MERCHANT, CAMPAIGN, RECIPIENT);

        verify(coupon, org.mockito.Mockito.times(2)).grant(OWNER, MERCHANT, MEMBER, 5L);
        verify(guardService).releaseForRetry(firstDispatching);
        verify(guardService).markDelivered(retryDispatching, null, 77L);
        verify(guardService, never()).markOutcomeUnknown(any(), anyString());
    }

    @Test
    void releaseCasFailureKeepsUnknownProtection() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("COUPON", MerchantCrmCampaignRecipient.PENDING));
        when(campaignMapper.selectOwnedCampaign(MERCHANT, CAMPAIGN)).thenReturn(campaign("COUPON"));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "COUPON")).thenReturn(optIn("COUPON"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "COUPON")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        when(coupon.grant(OWNER, MERCHANT, MEMBER, 5L)).thenThrow(
                new MerchantCrmCouponGrantAdapter.GrantRejectedException(
                        "COUPON_NOT_ISSUED", "优惠券未发出，可能已停发、失效或库存不足", null));
        org.mockito.Mockito.doThrow(new ServiceException("CAS lost", 409))
                .when(guardService).releaseForRetry(dispatching);

        org.junit.jupiter.api.Assertions.assertThrows(MerchantCrmDeliveryOutcomeUnknownException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));

        verify(guardService).releaseForRetry(dispatching);
        verify(guardService).markOutcomeUnknown(dispatching, "DELIVERY_OUTCOME_UNKNOWN");
    }

    @Test
    void markDeliveredFailureAfterCouponReceiptStaysUnknownAndNeverReleasesGuard() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("COUPON", MerchantCrmCampaignRecipient.PENDING));
        when(campaignMapper.selectOwnedCampaign(MERCHANT, CAMPAIGN)).thenReturn(campaign("COUPON"));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "COUPON")).thenReturn(optIn("COUPON"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "COUPON")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        when(coupon.grant(OWNER, MERCHANT, MEMBER, 5L)).thenReturn(77L);
        org.mockito.Mockito.doThrow(new ServiceException("回执 CAS lost", 409))
                .when(guardService).markDelivered(dispatching, null, 77L);

        org.junit.jupiter.api.Assertions.assertThrows(MerchantCrmDeliveryOutcomeUnknownException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));

        verify(guardService, never()).releaseForRetry(any());
        verify(guardService).markOutcomeUnknown(dispatching, "DELIVERY_OUTCOME_UNKNOWN");
        verify(campaignMapper, never()).markRecipientDelivered(any(), any(), any(), any(), any());
    }

    @Test
    void adapterFailureAfterDispatchFenceBecomesUnknownAndIsNeverAutomaticallyResent() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(optIn("IN_APP"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        when(notification.send(any(), any())).thenThrow(new IllegalStateException("timeout"));

        org.junit.jupiter.api.Assertions.assertThrows(MerchantCrmDeliveryOutcomeUnknownException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));

        verify(guardService).markOutcomeUnknown(dispatching, "DELIVERY_OUTCOME_UNKNOWN");
        verify(campaignMapper, never()).markRecipientDelivered(any(), any(), any(), any(), any());
    }

    @Test
    void deliveredGuardRepairsRecipientReceiptWithoutCallingExternalAdapterAgain() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(optIn("IN_APP"));
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP"))
                .thenReturn(MerchantCrmDeliveryGuardService.Claim.delivered(88L, null));
        when(campaignMapper.markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, 88L, null)).thenReturn(1);

        service.deliver(MERCHANT, CAMPAIGN, RECIPIENT);

        verify(notification, never()).send(any(), any());
        verify(coupon, never()).grant(any(), any(), any(), any());
        verify(campaignMapper).markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, 88L, null);
    }

    @Test
    void busyGuardNeverBecomesDeliveryFailureOrCallsExternalAdapter() {
        MerchantCrmCampaignRecipient row = recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING);
        row.setAttemptCount(19);
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT)).thenReturn(row);
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(optIn("IN_APP"));
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP"))
                .thenReturn(MerchantCrmDeliveryGuardService.Claim.busy());
        when(campaignMapper.markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "DELIVERY_FAILED", "投递失败，可重试")).thenReturn(1);
        when(campaignMapper.markRecipientRetryableBusy(MERCHANT, CAMPAIGN, RECIPIENT,
                "投递正在由其他任务处理，请稍后重试")).thenReturn(1);

        RuntimeException busy = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));
        service.recordFailure(MERCHANT, CAMPAIGN, RECIPIENT, busy);

        verify(notification, never()).send(any(), any());
        verify(coupon, never()).grant(any(), any(), any(), any());
        verify(campaignMapper, never()).markRecipientFailed(any(), any(), any(), any(), any());
        verify(campaignMapper).markRecipientRetryableBusy(MERCHANT, CAMPAIGN, RECIPIENT,
                "投递正在由其他任务处理，请稍后重试");
    }

    @Test
    void preDispatchNotFoundRetainsAssetInvalidFallback() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("COUPON", MerchantCrmCampaignRecipient.PENDING));
        when(campaignMapper.markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "ASSET_INVALID", "投递失败，可重试")).thenReturn(1);

        service.recordFailure(MERCHANT, CAMPAIGN, RECIPIENT, new ServiceException("not found", 404));

        verify(campaignMapper).markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "ASSET_INVALID", "投递失败，可重试");
    }

    @Test
    void unknownExternalOutcomeIsPersistedAsNonRetryableRecipientFailure() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(campaignMapper.markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "DELIVERY_OUTCOME_UNKNOWN", "投递结果未知，已阻止自动重发")).thenReturn(1);

        service.recordFailure(MERCHANT, CAMPAIGN, RECIPIENT,
                new MerchantCrmDeliveryOutcomeUnknownException());

        verify(campaignMapper).markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "DELIVERY_OUTCOME_UNKNOWN", "投递结果未知，已阻止自动重发");
    }

    @Test
    void deliveredAuditReceiptZeroFailsTerminalTransaction() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(optIn("IN_APP"));
        MerchantCrmDeliveryGuardService.Claim reserved = reservedClaim();
        MerchantCrmDeliveryGuardService.Claim dispatching = dispatchingClaim();
        when(guardService.claim(MERCHANT, CAMPAIGN, RECIPIENT, MEMBER, "IN_APP")).thenReturn(reserved);
        when(guardService.beginDispatch(reserved)).thenReturn(dispatching);
        when(notification.send(any(), any())).thenReturn(66L);
        when(campaignMapper.markRecipientDelivered(MERCHANT, CAMPAIGN, RECIPIENT, 66L, null)).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));
    }

    @Test
    void skippedAuditReceiptZeroFailsTerminalTransaction() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(consentMapper.selectLatestOptIn(MEMBER, OWNER, MERCHANT, "IN_APP")).thenReturn(null);
        when(campaignMapper.markRecipientSkipped(MERCHANT, CAMPAIGN, RECIPIENT,
                MerchantCrmCampaignRecipient.SKIPPED_NO_CONSENT, "NO_CONSENT", "客户未同意或已退订")).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.deliver(MERCHANT, CAMPAIGN, RECIPIENT));
    }

    @Test
    void failedAuditReceiptZeroFailsTerminalTransaction() {
        when(campaignMapper.selectRecipientForUpdate(MERCHANT, CAMPAIGN, RECIPIENT))
                .thenReturn(recipient("IN_APP", MerchantCrmCampaignRecipient.PENDING));
        when(campaignMapper.markRecipientFailed(MERCHANT, CAMPAIGN, RECIPIENT,
                "DELIVERY_OUTCOME_UNKNOWN", "投递结果未知，已阻止自动重发")).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.recordFailure(MERCHANT, CAMPAIGN, RECIPIENT,
                        new MerchantCrmDeliveryOutcomeUnknownException()));
    }

    private MerchantCrmCampaign campaign(String channel) {
        return campaign(CAMPAIGN, channel);
    }

    private MerchantCrmCampaign campaign(Long id, String channel) {
        MerchantCrmCampaign row = new MerchantCrmCampaign();
        row.setId(id);
        row.setOwnerMemberId(OWNER);
        row.setMerchantRowId(MERCHANT);
        row.setActorMemberId(901L);
        row.setActorRoleCode("MERCHANT_MANAGER");
        row.setChannel(channel);
        row.setCouponId(5L);
        return row;
    }

    private MerchantCrmDeliveryGuardService.Claim reservedClaim() {
        return MerchantCrmDeliveryGuardService.Claim.acquired(101L, "lease-token", 0L);
    }

    private MerchantCrmDeliveryGuardService.Claim dispatchingClaim() {
        return MerchantCrmDeliveryGuardService.Claim.acquired(101L, "lease-token", 1L);
    }

    private MerchantCrmCampaignRecipient recipient(String channel, String status) {
        MerchantCrmCampaignRecipient row = new MerchantCrmCampaignRecipient();
        row.setId(RECIPIENT);
        row.setCampaignId(CAMPAIGN);
        row.setMerchantId(MERCHANT);
        row.setMemberId(MEMBER);
        row.setChannel(channel);
        row.setStatus(status);
        return row;
    }

    private MerchantCrmMarketingConsent optIn(String channel) {
        MerchantCrmMarketingConsent row = new MerchantCrmMarketingConsent();
        row.setId(55L);
        row.setChannel(channel);
        row.setEventType(MerchantCrmMarketingConsent.OPT_IN);
        return row;
    }

    private MmsMerchant merchant(Long ownerMemberId, Long status, Long accountStatus, Integer delFlag) {
        MmsMerchant row = new MmsMerchant();
        row.setId(MERCHANT);
        row.setMemberId(ownerMemberId);
        row.setStatus(status);
        row.setAccountStatus(accountStatus);
        row.setDelFlag(delFlag);
        return row;
    }
}
