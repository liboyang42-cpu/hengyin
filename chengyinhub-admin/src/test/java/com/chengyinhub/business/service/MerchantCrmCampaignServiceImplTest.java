package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.domain.MerchantCrmCampaignCandidate;
import com.chengyinhub.business.domain.MerchantCrmCampaignRecipient;
import com.chengyinhub.business.domain.MerchantCrmSegment;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantCrmCampaignPreviewRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCampaignRequest;
import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import com.chengyinhub.business.domain.vo.MerchantCrmCampaignVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmAppMapper;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.mapper.MerchantCrmSegmentMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.MerchantCrmCampaignServiceImpl;
import com.chengyinhub.business.service.impl.MerchantCrmCampaignClaimTransactionService;
import com.chengyinhub.business.service.impl.MerchantCrmCouponGrantAdapter;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class MerchantCrmCampaignServiceImplTest {
    private static final long OWNER = 900L;
    private static final long MERCHANT = 77L;
    private static final long PRINCIPAL = OWNER;
    private static final long OPERATOR = 901L;
    private static final long MARKETING = 902L;
    private static final long CHECKIN = 903L;
    private static final long FINANCE = 904L;
    private static final long OTHER_MERCHANT = 88L;
    private IMerchantAccessService access;
    private MerchantCrmSegmentMapper segmentMapper;
    private MerchantCrmAppMapper appMapper;
    private MerchantCrmCampaignMapper campaignMapper;
    private MerchantCrmMarketingConsentMapper consentMapper;
    private MmsMerchantMapper merchantMapper;
    private BusinessAuditEventMapper auditMapper;
    private IMerchantCrmRecipientDeliveryService recipientDeliveryService;
    private MerchantCrmCouponGrantAdapter couponGrantAdapter;
    private MerchantCrmCampaignServiceImpl service;

    @BeforeEach
    void setUp() {
        access = mock(IMerchantAccessService.class);
        segmentMapper = mock(MerchantCrmSegmentMapper.class);
        appMapper = mock(MerchantCrmAppMapper.class);
        campaignMapper = mock(MerchantCrmCampaignMapper.class);
        consentMapper = mock(MerchantCrmMarketingConsentMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        service = new MerchantCrmCampaignServiceImpl();
        ReflectionTestUtils.setField(service, "accessService", access);
        ReflectionTestUtils.setField(service, "segmentMapper", segmentMapper);
        ReflectionTestUtils.setField(service, "appMapper", appMapper);
        ReflectionTestUtils.setField(service, "campaignMapper", campaignMapper);
        ReflectionTestUtils.setField(service, "consentMapper", consentMapper);
        couponGrantAdapter = mock(MerchantCrmCouponGrantAdapter.class);
        ReflectionTestUtils.setField(service, "couponGrantAdapter", couponGrantAdapter);
        recipientDeliveryService = mock(IMerchantCrmRecipientDeliveryService.class);
        ReflectionTestUtils.setField(service, "recipientDeliveryService", recipientDeliveryService);
        ReflectionTestUtils.setField(service, "merchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "auditMapper", auditMapper);
        MerchantCrmCampaignClaimTransactionService claimTransactions =
                new MerchantCrmCampaignClaimTransactionService();
        ReflectionTestUtils.setField(claimTransactions, "campaignMapper", campaignMapper);
        ReflectionTestUtils.setField(claimTransactions, "auditMapper", auditMapper);
        ReflectionTestUtils.setField(service, "claimTransactionService", claimTransactions);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(access.require(PRINCIPAL, MerchantPermission.MARKETING_WRITE)).thenReturn(context());
        when(access.require(PRINCIPAL, MerchantPermission.MARKETING_READ)).thenReturn(context());
        when(access.require(PRINCIPAL, MerchantPermission.COUPON_MANAGE)).thenReturn(context());
    }

    @Test
    void couponOptionsAndCouponCampaignValidationUseExactMerchantRow() {
        service.coupons(PRINCIPAL);
        verify(couponGrantAdapter).list(OWNER, MERCHANT);

        MerchantCrmCampaignRequest couponRequest = request();
        couponRequest.setChannel("COUPON");
        couponRequest.setCouponId(5L);
        when(segmentMapper.selectOwnedSegmentById(MERCHANT, 5L)).thenReturn(segment(MERCHANT));
        when(appMapper.selectOwnedCampaignRecipientCandidates(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(merchant());
        when(campaignMapper.insertCampaign(any())).thenAnswer(invocation -> {
            ((MerchantCrmCampaign) invocation.getArgument(0)).setId(8L);
            return 1;
        });

        service.create(PRINCIPAL, couponRequest);

        verify(couponGrantAdapter).requireOwnedActive(OWNER, MERCHANT, 5L);
    }

    @Test
    void couponOptionsRemainAvailableToAuthorizedOperator() {
        when(access.require(OPERATOR, MerchantPermission.COUPON_MANAGE)).thenReturn(operatorContext());

        service.coupons(OPERATOR);

        verify(couponGrantAdapter).list(OWNER, MERCHANT);
    }

    @Test
    void marketingOperatorCanPreviewAndReadCampaignsWithinResolvedMerchantScope() {
        when(access.require(MARKETING, MerchantPermission.MARKETING_WRITE)).thenReturn(marketingContext());
        when(access.require(MARKETING, MerchantPermission.MARKETING_READ)).thenReturn(marketingContext());
        when(segmentMapper.selectOwnedSegmentById(MERCHANT, 5L)).thenReturn(segment(MERCHANT));
        when(appMapper.selectOwnedCustomerCount(any(), any())).thenReturn(0L);
        when(appMapper.selectOwnedCampaignRecipientCandidates(any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(merchant());
        when(campaignMapper.insertCampaign(any())).thenAnswer(invocation -> {
            ((MerchantCrmCampaign) invocation.getArgument(0)).setId(8L);
            return 1;
        });
        MerchantCrmCampaign row = campaign(MerchantCrmCampaign.READY);
        when(campaignMapper.selectOwnedCampaign(MERCHANT, 8L)).thenReturn(row);
        when(campaignMapper.selectOwnedRecipients(MERCHANT, 8L, 200)).thenReturn(Collections.emptyList());
        when(campaignMapper.selectOwnedCampaigns(MERCHANT, 20)).thenReturn(Collections.singletonList(row));
        MerchantCrmCampaignPreviewRequest preview = new MerchantCrmCampaignPreviewRequest();
        preview.setSegmentId(5L);
        preview.setChannel("IN_APP");

        assertEquals(0, service.preview(MARKETING, preview).getDeliverableCount());
        assertEquals(8L, service.create(MARKETING, request()).getId());
        assertEquals(8L, service.detail(MARKETING, 8L).getId());
        assertEquals(1, service.list(MARKETING).size());

        verify(access, org.mockito.Mockito.times(2))
                .require(MARKETING, MerchantPermission.MARKETING_WRITE);
        verify(access, org.mockito.Mockito.times(2))
                .require(MARKETING, MerchantPermission.MARKETING_READ);
        ArgumentCaptor<MerchantCrmCustomerQuery> scoped = ArgumentCaptor.forClass(MerchantCrmCustomerQuery.class);
        verify(appMapper, org.mockito.Mockito.times(2))
                .selectOwnedCampaignRecipientCandidates(any(), scoped.capture(), anyInt());
        for (MerchantCrmCustomerQuery query : scoped.getAllValues()) {
            assertEquals(MERCHANT, query.getMerchantId());
        }
    }

    @Test
    void checkinAndFinanceRolesAreDeniedByCampaignPermissionsBeforeDataAccess() {
        MerchantAccessContext checkin = new MerchantAccessContext(
                CHECKIN, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_CHECKIN);
        MerchantAccessContext finance = new MerchantAccessContext(
                FINANCE, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_FINANCE);
        assertFalse(checkin.hasPermission(MerchantPermission.MARKETING_READ));
        assertFalse(finance.hasPermission(MerchantPermission.MARKETING_WRITE));
        when(access.require(CHECKIN, MerchantPermission.MARKETING_READ))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        when(access.require(FINANCE, MerchantPermission.MARKETING_WRITE))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));

        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.list(CHECKIN)).getCode());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.create(FINANCE, request())).getCode());

        verify(campaignMapper, never()).selectOwnedCampaigns(any(), anyInt());
        verify(segmentMapper, never()).selectOwnedSegmentById(any(), any());
    }

    @Test
    void campaignListQueriesOwnerAndExactMerchantRowTogether() {
        when(campaignMapper.selectOwnedCampaigns(MERCHANT, 20))
                .thenReturn(Collections.emptyList());

        assertEquals(0, service.list(PRINCIPAL).size());

        verify(campaignMapper).selectOwnedCampaigns(MERCHANT, 20);
    }

    @Test
    void managerOperatorCreatesFromOwnedSavedSegmentAndAuditsPrincipalAndRole() {
        when(access.require(OPERATOR, MerchantPermission.MARKETING_WRITE)).thenReturn(operatorContext());
        when(segmentMapper.selectOwnedSegmentById(MERCHANT, 5L)).thenReturn(segment(MERCHANT));
        when(appMapper.selectOwnedCampaignRecipientCandidates(any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(candidate(41L)));
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(merchant());
        when(campaignMapper.insertCampaign(any())).thenAnswer(invocation -> {
            ((MerchantCrmCampaign) invocation.getArgument(0)).setId(8L);
            return 1;
        });
        when(campaignMapper.insertRecipient(any())).thenReturn(1);

        MerchantCrmCampaignVO out = service.create(OPERATOR, request());

        assertEquals(8L, out.getId());
        assertEquals(MerchantCrmCampaign.READY, out.getStatus());
        verify(segmentMapper).selectOwnedSegmentById(MERCHANT, 5L);
        verify(appMapper).selectOwnedCampaignRecipientCandidates(any(), any(), anyInt());
        ArgumentCaptor<MerchantCrmCampaign> campaign = ArgumentCaptor.forClass(MerchantCrmCampaign.class);
        verify(campaignMapper).insertCampaign(campaign.capture());
        assertEquals(OWNER, campaign.getValue().getOwnerMemberId());
        assertEquals(MERCHANT, campaign.getValue().getMerchantRowId());
        assertEquals(OPERATOR, campaign.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, campaign.getValue().getActorRoleCode());
        ArgumentCaptor<com.chengyinhub.business.domain.MerchantCrmCampaignRecipient> recipient =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.MerchantCrmCampaignRecipient.class);
        verify(campaignMapper).insertRecipient(recipient.capture());
        assertEquals(41L, recipient.getValue().getMemberId());
        assertNull(recipient.getValue().getConsentEventIdSnapshot());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals(OPERATOR, audit.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, audit.getValue().getActorRoleCode());
    }

    @Test
    void createFailsClosedWhenActorAuditReceiptCannotPersist() {
        when(segmentMapper.selectOwnedSegmentById(MERCHANT, 5L)).thenReturn(segment(MERCHANT));
        when(appMapper.selectOwnedCampaignRecipientCandidates(any(), any(), anyInt()))
                .thenReturn(Collections.singletonList(candidate(41L)));
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(merchant());
        when(campaignMapper.insertCampaign(any())).thenAnswer(invocation -> {
            ((MerchantCrmCampaign) invocation.getArgument(0)).setId(8L);
            return 1;
        });
        when(campaignMapper.insertRecipient(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.create(PRINCIPAL, request()));

        assertEquals(409, error.getCode());
    }

    @Test
    void otherMerchantsSegmentFailsClosedBeforeCandidateOrCampaignWrites() {
        when(segmentMapper.selectOwnedSegmentById(MERCHANT, 5L)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class, () -> service.create(PRINCIPAL, request()));

        assertEquals(404, error.getCode());
        verify(segmentMapper, never()).selectOwnedSegmentById(OTHER_MERCHANT, 5L);
        verify(appMapper, never()).selectOwnedCampaignRecipientCandidates(any(), any(), anyInt());
        verify(campaignMapper, never()).insertCampaign(any());
    }

    @Test
    void dtoHasNoClientRecipientIdsField() {
        assertThrows(NoSuchFieldException.class,
                () -> MerchantCrmCampaignRequest.class.getDeclaredField("memberIds"));
        assertThrows(NoSuchFieldException.class,
                () -> MerchantCrmCampaignRequest.class.getDeclaredField("merchantId"));
    }

    @Test
    void managerDispatchAuditUsesOperatorPrincipalAndRoleInsteadOfCampaignCreator() {
        when(access.require(OPERATOR, MerchantPermission.MARKETING_WRITE)).thenReturn(operatorContext());
        MerchantCrmCampaign row = new MerchantCrmCampaign();
        row.setId(8L);
        row.setOwnerMemberId(OWNER);
        row.setMerchantRowId(MERCHANT);
        row.setSegmentId(5L);
        row.setActorMemberId(700L);
        row.setActorRoleCode(MerchantRoleCode.MERCHANT_OWNER);
        row.setChannel("IN_APP");
        row.setStatus(MerchantCrmCampaign.READY);
        row.setRecipientCount(0);
        when(campaignMapper.selectOwnedCampaign(MERCHANT, 8L)).thenReturn(row);
        when(campaignMapper.markCampaignProcessing(eq(MERCHANT), eq(8L),
                eq(MerchantCrmCampaign.READY), anyString(), eq(300))).thenReturn(1);
        when(campaignMapper.selectPendingRecipientIds(MERCHANT, 8L, 200)).thenReturn(Collections.emptyList());
        when(campaignMapper.finalizeCampaign(eq(MERCHANT), eq(8L), anyString())).thenReturn(1);
        when(campaignMapper.selectOwnedRecipients(MERCHANT, 8L, 200)).thenReturn(Collections.emptyList());

        service.dispatch(OPERATOR, 8L);

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals(OPERATOR, audit.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, audit.getValue().getActorRoleCode());
    }

    @Test
    void managerOperatorCanRetryOwnedCampaignWithCurrentPrincipalAndRoleAudit() {
        when(access.require(OPERATOR, MerchantPermission.MARKETING_WRITE)).thenReturn(operatorContext());
        MerchantCrmCampaign row = campaign(MerchantCrmCampaign.PARTIAL_FAILED);
        when(campaignMapper.selectOwnedCampaign(MERCHANT, 8L)).thenReturn(row);
        when(campaignMapper.markCampaignProcessing(eq(MERCHANT), eq(8L),
                eq(MerchantCrmCampaign.PARTIAL_FAILED), anyString(), eq(300))).thenReturn(1);
        when(campaignMapper.resetRetryableRecipients(MERCHANT, 8L)).thenReturn(0);
        when(campaignMapper.finalizeCampaign(eq(MERCHANT), eq(8L), anyString())).thenReturn(1);
        when(campaignMapper.selectOwnedRecipients(MERCHANT, 8L, 200)).thenReturn(Collections.emptyList());

        assertEquals(8L, service.retry(OPERATOR, 8L).getId());

        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("CRM_CAMPAIGN_RETRIED", audit.getValue().getAction());
        assertEquals(OPERATOR, audit.getValue().getActorMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, audit.getValue().getActorRoleCode());
    }

    @Test
    void merchantAOperatorCannotDispatchMerchantBCampaignUnderSameOwner() {
        when(access.require(OPERATOR, MerchantPermission.MARKETING_WRITE)).thenReturn(operatorContext());
        MerchantCrmCampaign row = new MerchantCrmCampaign();
        row.setId(8L);
        row.setOwnerMemberId(OWNER);
        row.setMerchantRowId(88L);
        row.setChannel("COUPON");
        row.setCouponId(6L);
        row.setStatus(MerchantCrmCampaign.READY);
        when(campaignMapper.selectOwnedCampaign(MERCHANT, 8L)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.dispatch(OPERATOR, 8L));

        assertEquals(404, error.getCode());
        verify(campaignMapper).selectOwnedCampaign(MERCHANT, 8L);
        verify(campaignMapper, never()).selectOwnedCampaign(OTHER_MERCHANT, 8L);
        verify(campaignMapper, never()).markCampaignProcessing(any(), any(), any(), anyString(), anyInt());
        verify(recipientDeliveryService, never()).deliver(any(), any(), any());
    }

    @Test
    void expiredProcessingCampaignIsRecoveredWithVersionCasAndSkipsDeliveredRecipients() {
        MerchantCrmCampaign stale = staleCampaign();
        when(campaignMapper.selectStaleProcessingCampaigns(20)).thenReturn(Collections.singletonList(stale));
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        when(campaignMapper.reclaimStaleCampaign(eq(MERCHANT), eq(8L), eq(OWNER), eq(7L),
                anyString(), eq(300)))
                .thenReturn(1);
        when(campaignMapper.selectPendingRecipientIds(MERCHANT, 8L, 200)).thenReturn(Collections.singletonList(9L));
        when(campaignMapper.renewCampaignLease(eq(MERCHANT), eq(8L), anyString(), eq(300))).thenReturn(1);
        when(campaignMapper.finalizeCampaign(eq(MERCHANT), eq(8L), anyString())).thenReturn(1);

        assertEquals(1, service.recoverStaleProcessing());

        verify(recipientDeliveryService).deliver(MERCHANT, 8L, 9L);
        verify(campaignMapper).reclaimStaleCampaign(eq(MERCHANT), eq(8L), eq(OWNER), eq(7L),
                anyString(), eq(300));
        verify(campaignMapper).finalizeCampaign(eq(MERCHANT), eq(8L), anyString());
    }

    @Test
    void merchantDisabledAfterStaleReclaimStopsBeforeNextRecipientAndTerminatesCampaign() {
        MerchantCrmCampaign stale = staleCampaign();
        AtomicBoolean active = new AtomicBoolean(true);
        when(campaignMapper.selectStaleProcessingCampaigns(20)).thenReturn(Collections.singletonList(stale));
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        when(campaignMapper.reclaimStaleCampaign(eq(MERCHANT), eq(8L), eq(OWNER), eq(7L),
                anyString(), eq(300))).thenReturn(1);
        when(campaignMapper.selectPendingRecipientIds(MERCHANT, 8L, 200))
                .thenReturn(java.util.Arrays.asList(9L, 10L));
        when(campaignMapper.renewCampaignLease(eq(MERCHANT), eq(8L), anyString(), eq(300)))
                .thenAnswer(call -> active.get() ? 1 : 0);
        when(campaignMapper.invalidateClaimedCampaign(eq(MERCHANT), eq(8L), eq(OWNER), anyString()))
                .thenReturn(1);
        doAnswer(call -> {
            active.set(false);
            return null;
        }).when(recipientDeliveryService).deliver(MERCHANT, 8L, 9L);

        assertEquals(0, service.recoverStaleProcessing());

        verify(recipientDeliveryService).deliver(MERCHANT, 8L, 9L);
        verify(recipientDeliveryService, never()).deliver(MERCHANT, 8L, 10L);
        verify(campaignMapper, never()).finalizeCampaign(eq(MERCHANT), eq(8L), anyString());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("CRM_CAMPAIGN_INVALIDATED", audit.getValue().getAction());
    }

    @Test
    void disabledMerchantStaleCampaignCannotBeReclaimedOrDeliverRecipients() {
        MmsMerchant disabled = activeMerchant();
        disabled.setAccountStatus(2L);
        assertInvalidMerchantCannotRecoverStaleCampaign(disabled);
    }

    @Test
    void ownerMismatchStaleCampaignCannotBeReclaimedOrDeliverRecipients() {
        MmsMerchant transferred = activeMerchant();
        transferred.setMemberId(OWNER + 1L);
        assertInvalidMerchantCannotRecoverStaleCampaign(transferred);
    }

    @Test
    void missingMerchantStaleCampaignCannotBeReclaimedOrDeliverRecipients() {
        assertInvalidMerchantCannotRecoverStaleCampaign(null);
    }

    @Test
    void reclaimVersionCasLossFailsClosedBeforeRecipientSelection() {
        MerchantCrmCampaign stale = staleCampaign();
        when(campaignMapper.selectStaleProcessingCampaigns(20)).thenReturn(Collections.singletonList(stale));
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(activeMerchant());
        when(campaignMapper.reclaimStaleCampaign(eq(MERCHANT), eq(8L), eq(OWNER), eq(7L),
                anyString(), eq(300))).thenReturn(0);
        when(campaignMapper.invalidateStaleCampaign(MERCHANT, 8L, OWNER, 7L)).thenReturn(0);

        assertEquals(0, service.recoverStaleProcessing());

        verify(campaignMapper).invalidateStaleCampaign(MERCHANT, 8L, OWNER, 7L);
        verify(campaignMapper, never()).selectPendingRecipientIds(any(), any(), anyInt());
        verify(recipientDeliveryService, never()).deliver(any(), any(), any());
    }

    @Test
    void nonRetryableUnknownAndAttemptLimitFailuresDoNotAdvertiseRetryButton() {
        MerchantCrmCampaign row = new MerchantCrmCampaign();
        row.setId(8L);
        row.setOwnerMemberId(OWNER);
        row.setMerchantRowId(MERCHANT);
        row.setStatus(MerchantCrmCampaign.PARTIAL_FAILED);
        row.setNoConsentCount(0);
        row.setFrequencySkippedCount(0);
        row.setFailedCount(2);
        MerchantCrmCampaignRecipient unknown = new MerchantCrmCampaignRecipient();
        unknown.setStatus(MerchantCrmCampaignRecipient.FAILED);
        unknown.setFailureCode("DELIVERY_OUTCOME_UNKNOWN");
        unknown.setAttemptCount(1);
        MerchantCrmCampaignRecipient exhausted = new MerchantCrmCampaignRecipient();
        exhausted.setStatus(MerchantCrmCampaignRecipient.FAILED);
        exhausted.setFailureCode("CHANNEL_FAILED");
        exhausted.setAttemptCount(20);
        when(campaignMapper.selectOwnedCampaign(MERCHANT, 8L)).thenReturn(row);
        when(campaignMapper.selectOwnedRecipients(MERCHANT, 8L, 200))
                .thenReturn(java.util.Arrays.asList(unknown, exhausted));

        MerchantCrmCampaignVO out = service.detail(PRINCIPAL, 8L);

        assertEquals(0, out.getRetryableCount());
    }

    private MerchantCrmCampaignRequest request() {
        MerchantCrmCampaignRequest request = new MerchantCrmCampaignRequest();
        request.setSegmentId(5L);
        request.setChannel("IN_APP");
        request.setTitle("旧城咖啡活动提醒");
        request.setContent("本周六欢迎到店参加活动");
        request.setRequestId("campaign-request-1");
        return request;
    }

    private MerchantCrmSegment segment(Long owner) {
        MerchantCrmSegment segment = new MerchantCrmSegment();
        segment.setId(5L);
        segment.setMerchantId(owner);
        segment.setFilterJson("{}");
        return segment;
    }

    private MerchantCrmCampaignCandidate candidate(Long memberId) {
        MerchantCrmCampaignCandidate out = new MerchantCrmCampaignCandidate();
        out.setMemberId(memberId);
        out.setName("林青");
        return out;
    }

    private MmsMerchant merchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT);
        merchant.setMemberId(OWNER);
        merchant.setName("旧城咖啡");
        return merchant;
    }

    private MerchantCrmCampaign campaign(String status) {
        MerchantCrmCampaign row = new MerchantCrmCampaign();
        row.setId(8L);
        row.setOwnerMemberId(OWNER);
        row.setMerchantRowId(MERCHANT);
        row.setSegmentId(5L);
        row.setChannel("IN_APP");
        row.setStatus(status);
        row.setRecipientCount(0);
        return row;
    }

    private MerchantCrmCampaign staleCampaign() {
        MerchantCrmCampaign row = campaign(MerchantCrmCampaign.PROCESSING);
        row.setVersion(7L);
        return row;
    }

    private MmsMerchant activeMerchant() {
        MmsMerchant row = merchant();
        row.setStatus(1L);
        row.setAccountStatus(1L);
        row.setDelFlag(0);
        return row;
    }

    private void assertInvalidMerchantCannotRecoverStaleCampaign(MmsMerchant merchant) {
        MerchantCrmCampaign stale = staleCampaign();
        when(campaignMapper.selectStaleProcessingCampaigns(20)).thenReturn(Collections.singletonList(stale));
        when(merchantMapper.selectMmsMerchantById(MERCHANT)).thenReturn(merchant);
        when(campaignMapper.invalidateStaleCampaign(MERCHANT, 8L, OWNER, 7L)).thenReturn(1);
        when(campaignMapper.selectPendingRecipientIds(MERCHANT, 8L, 200))
                .thenReturn(Collections.singletonList(9L));
        when(campaignMapper.renewCampaignLease(eq(MERCHANT), eq(8L), anyString(), eq(300)))
                .thenReturn(1);
        when(campaignMapper.finalizeCampaign(eq(MERCHANT), eq(8L), anyString())).thenReturn(1);

        assertEquals(0, service.recoverStaleProcessing());

        verify(merchantMapper).selectMmsMerchantById(MERCHANT);
        verify(campaignMapper).invalidateStaleCampaign(MERCHANT, 8L, OWNER, 7L);
        verify(campaignMapper, never()).reclaimStaleCampaign(
                any(), any(), any(), any(), anyString(), anyInt());
        verify(campaignMapper, never()).selectPendingRecipientIds(any(), any(), anyInt());
        verify(recipientDeliveryService, never()).deliver(any(), any(), any());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("CRM_CAMPAIGN_INVALIDATED", audit.getValue().getAction());
        assertEquals("SYSTEM_RECOVERY", audit.getValue().getActorRoleCode());
        assertEquals("{\"status\":\"NO_ELIGIBLE\","
                + "\"error\":\"MERCHANT_INACTIVE_OR_SCOPE_CHANGED\"}",
                audit.getValue().getAfterJson());
    }

    private MerchantAccessContext context() {
        return new MerchantAccessContext(PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_OWNER);
    }

    private MerchantAccessContext operatorContext() {
        return new MerchantAccessContext(OPERATOR, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_MANAGER);
    }

    private MerchantAccessContext marketingContext() {
        return new MerchantAccessContext(MARKETING, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_MARKETING);
    }
}
