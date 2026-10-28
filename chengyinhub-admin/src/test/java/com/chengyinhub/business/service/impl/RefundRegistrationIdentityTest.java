package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Registration is the authority for refund recipients and commercial scope, including admin creation. */
class RefundRegistrationIdentityTest {
    private final RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
    private final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    private final CmsActivityMapper activities = mock(CmsActivityMapper.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final RefundServiceImpl service = new RefundServiceImpl();

    private CmsRegistration setup(long ownerType) {
        ReflectionTestUtils.setField(service, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        CmsRegistration row = new CmsRegistration();
        row.setId(9007199254740993L);
        row.setMemberId(71L);
        row.setOwnerType(ownerType);
        row.setOwnerId(9223372036854775806L);
        row.setPayableAmount(new BigDecimal("25.00"));
        row.setPaymentStatus(2);
        when(registrations.selectCmsRegistrationById(row.getId())).thenReturn(row);
        when(registrations.selectCmsRegistrationByIdForUpdate(row.getId())).thenReturn(row);
        when(refunds.selectRegistrationHistoryForUpdate(row.getId())).thenReturn(Collections.emptyList());
        when(refunds.insertRefundApplication(any())).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });
        CmsActivity activity = new CmsActivity();
        activity.setMemberId(81L);
        when(activities.selectCmsActivityById(row.getOwnerId())).thenReturn(activity);
        CmsTopic topic = new CmsTopic();
        topic.setMemberId(82L);
        when(topics.selectCmsTopicById(row.getOwnerId())).thenReturn(topic);
        return row;
    }

    @Test
    void adminCannotRedirectActivityRefundToAnotherBuyerOrCommercialScope() {
        CmsRegistration row = setup(2L);
        RefundApplication request = request(row);
        assertEquals(1, service.saveBackOfficeRefund(request));
        assertIdentity(request, row, 81L);
        verify(refunds).insertRefundApplication(request);
    }

    @Test
    void topicRefundAlsoUsesServerIdentityInsteadOfClientMerchant() {
        CmsRegistration row = setup(1L);
        RefundApplication request = request(row);
        assertEquals(1, service.saveRefund(request));
        assertIdentity(request, row, 82L);
    }

    @Test
    void missingMerchantFactCannotBeReplacedByClientMerchant() {
        CmsRegistration row = setup(2L);
        when(activities.selectCmsActivityById(row.getOwnerId())).thenReturn(null);
        RefundApplication request = request(row);
        service.saveRefund(request);
        assertIdentity(request, row, null);
    }

    @Test
    void missingLegacyIdentityNeverAdoptsCallerSuppliedBuyerOrOwner() {
        CmsRegistration row = setup(2L);
        row.setMemberId(null);
        row.setOwnerType(null);
        row.setOwnerId(null);
        RefundApplication request = request(row);
        service.saveRefund(request);
        assertIdentity(request, row, null);
    }

    @Test
    void alreadyCorrectIdentityRemainsEquivalentAndExactCashStillComesFromRegistration() {
        CmsRegistration row = setup(2L);
        RefundApplication request = request(row);
        request.setMemberId(row.getMemberId());
        request.setOwnerType(row.getOwnerType());
        request.setOwnerId(row.getOwnerId());
        request.setMerchantId(81L);
        request.setPaidAmount(new BigDecimal("999"));
        service.saveRefund(request);
        assertIdentity(request, row, 81L);
        assertEquals(new BigDecimal("25.00"), request.getPaidAmount());
        assertEquals("RF9007199254740993", request.getRefundNo());
    }

    private RefundApplication request(CmsRegistration row) {
        RefundApplication request = new RefundApplication();
        request.setSourceType("registration");
        request.setSourceId(row.getId());
        request.setRefundAmount(BigDecimal.TEN);
        request.setMemberId(72L);
        request.setOwnerType(3L);
        request.setOwnerId(99L);
        request.setMerchantId(999L);
        return request;
    }

    private void assertIdentity(RefundApplication refund, CmsRegistration row, Long merchant) {
        assertEquals(row.getMemberId(), refund.getMemberId());
        assertEquals(row.getOwnerType(), refund.getOwnerType());
        assertEquals(row.getOwnerId(), refund.getOwnerId());
        assertEquals(merchant, refund.getMerchantId());
    }
}
