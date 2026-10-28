package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT_RUN: user deferred execution. Real service entry with mocked persistence boundary, not DB/controller proof. */
class OWN_C02_27Test {
    private RefundServiceImpl service(RefundApplicationMapper refunds) {
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CmsRegistration registration = new CmsRegistration();
        registration.setId(99L);
        registration.setPaymentStatus(2);
        registration.setPayableAmount(new BigDecimal("10.00"));
        when(registrations.selectCmsRegistrationById(99L)).thenReturn(registration);
        // master 退款入口按报名行加锁读（lockRefundRegistration）。
        when(registrations.selectCmsRegistrationByIdForUpdate(99L)).thenReturn(registration);
        RefundServiceImpl service = new RefundServiceImpl();
        ReflectionTestUtils.setField(service, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        when(refunds.insertRefundApplication(any(RefundApplication.class))).thenAnswer(invocation -> {
            RefundApplication inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        });
        return service;
    }

    private RefundApplication request(String amount) {
        RefundApplication refund = new RefundApplication();
        refund.setSourceType("registration");
        refund.setSourceId(99L);
        refund.setRefundAmount(new BigDecimal(amount));
        refund.setMemberId(42L);
        refund.setMerchantId(7L);
        return refund;
    }

    @Test
    void fractionalCentsNeverReachInsert() {
        for (String amount : new String[]{"0.001", "1.001"}) {
            RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
            RefundServiceImpl service = service(refunds);
            RefundApplication refund = request(amount);
            assertThrows(IllegalArgumentException.class, () -> service.saveRefund(refund), amount);
            verify(refunds, never()).insertRefundApplication(any(RefundApplication.class));
            assertEquals(new BigDecimal(amount), refund.getRefundAmount());
        }
    }

    @Test
    void exactCentsIncludingTrailingZeroesKeepAmountAndExistingStateContract() {
        for (String amount : new String[]{"1.230", "1.23", "0.010", "10.00"}) {
            RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
            RefundServiceImpl service = service(refunds);
            RefundApplication refund = request(amount);
            refund.setStatus(1); // Existing new-request guard must still force review.
            assertEquals(1, service.saveRefund(refund));
            verify(refunds).insertRefundApplication(refund);
            assertEquals(new BigDecimal(amount), refund.getRefundAmount());
            assertEquals(Integer.valueOf(0), refund.getStatus());
            assertEquals(Integer.valueOf(RefundApplication.PAYOUT_DISPATCH_PENDING), refund.getPayoutStatus());
            assertEquals("registration:99", refund.getIdempotencyKey());
            assertEquals("RF99", refund.getRefundNo());
        }
    }

    @Test
    void existingPositiveAndPaidAmountLimitsStillRejectBeforeInsert() {
        for (String amount : new String[]{"0", "-1.00", "10.01"}) {
            RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
            RefundServiceImpl service = service(refunds);
            assertThrows(IllegalArgumentException.class, () -> service.saveRefund(request(amount)), amount);
            verify(refunds, never()).insertRefundApplication(any(RefundApplication.class));
        }
    }
}
