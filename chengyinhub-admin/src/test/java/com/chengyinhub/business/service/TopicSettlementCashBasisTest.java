package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.TopicSettlementCashBasis;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopicSettlementCashBasisTest {
    @Test void cashAndAuditUseTheirOriginalStateFiltersFromOneCurrentSet() {
        CmsRegistration unverified = row(1, 7L, 2, 2, 0, "60.00");
        CmsRegistration first = row(2, 8L, 2, 2, 1, "20.00");
        CmsRegistration sameMember = row(3, 8L, 2, 2, 1, "10.00");
        CmsRegistration refunded = row(4, 9L, 4, 3, 1, "40.00");
        CmsRegistration unpaid = row(5, 10L, 0, 2, 1, "99.00");
        CmsRegistration legacyVerified = row(6, null, 2, 3, 1, "5.00");
        TopicSettlementCashBasis basis = TopicSettlementCashBasis.fromLockedRows(11L,
                List.of(legacyVerified, sameMember, unverified, refunded, unpaid, first));
        assertEquals(new BigDecimal("90.00"), basis.getTotalSales());
        assertEquals(new BigDecimal("35.00"), basis.getVerifiedSales());
        assertEquals(1, basis.getVerifiedHeads(), "保持 COUNT(DISTINCT member_id)，不是票数");
        assertEquals(List.of(2L, 3L, 6L), basis.getVerifiedRegistrationIds());
        assertThrows(UnsupportedOperationException.class, () -> basis.getVerifiedRegistrationIds().add(99L));
    }
    @Test void missingDuplicateOrForeignEvidenceCannotSilentlyBecomeZero() {
        assertThrows(IllegalStateException.class, () -> TopicSettlementCashBasis.fromLockedRows(11L, null));
        CmsRegistration paid = row(1, 7L, 2, 2, 1, "10.00");
        assertThrows(IllegalStateException.class, () -> TopicSettlementCashBasis.fromLockedRows(11L, List.of(paid, paid)));
        paid.setOwnerType(2L);
        assertThrows(IllegalStateException.class, () -> TopicSettlementCashBasis.fromLockedRows(11L, List.of(paid)));
        paid.setOwnerType(1L); paid.setOwnerId(99L);
        assertThrows(IllegalStateException.class, () -> TopicSettlementCashBasis.fromLockedRows(11L, List.of(paid)));
    }
    @Test void paidUnverifiedActivityAndUnknownPaymentsCannotCloseAsZero() {
        CmsRegistration activity = row(7, 7L, 2, 2, 0, "90.00");
        activity.setOwnerType(2L); activity.setOwnerId(201L); activity.setTopicId(11L);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TopicSettlementCashBasis.fromLockedRows(11L, List.of(activity)));
        assertTrue(failure.getMessage().contains("ACTIVITY_CASH_BASIS_UNSUPPORTED"));
        activity.setPaymentStatus(1);
        failure = assertThrows(IllegalStateException.class,
                () -> TopicSettlementCashBasis.fromLockedRows(11L, List.of(activity)));
        assertTrue(failure.getMessage().contains("PAYMENT_CONFIRMATION_UNRESOLVED"));
    }
    @Test void invalidPaidAmountsRejectWhileActualEmptySetIsZero() {
        assertEquals(0, TopicSettlementCashBasis.fromLockedRows(11L, List.of()).getTotalSales().signum());
        CmsRegistration paid = row(1, 7L, 2, 2, 0, "0.00"); paid.setPayableAmount(null);
        assertThrows(IllegalStateException.class, () -> TopicSettlementCashBasis.fromLockedRows(11L, List.of(paid)));
        paid.setPayableAmount(new BigDecimal("-0.01"));
        assertThrows(IllegalStateException.class, () -> TopicSettlementCashBasis.fromLockedRows(11L, List.of(paid)));
    }
    @Test void paidUnverifiedHalfStateCannotCloseAsZeroAndRepairRestoresOriginalTenYuan() {
        CmsRegistration half=row(71,7L,2,1,0,"10.00");
        IllegalStateException error=assertThrows(IllegalStateException.class,()->TopicSettlementCashBasis.fromLockedRows(11L,List.of(half)));
        assertEquals("MANUAL_REPAIR:PAID_REGISTRATION_HALF_STATE_UNRESOLVED:registrationId=71",error.getMessage());
        assertEquals(2,half.getPaymentStatus());assertEquals(1,half.getRegistrationStatus());assertEquals(0,half.getVerificationStatus());
        half.setRegistrationStatus(2); // Original-order repair is A's command, not an automatic action of this reader.
        TopicSettlementCashBasis repaired=TopicSettlementCashBasis.fromLockedRows(11L,List.of(half));
        assertEquals(new BigDecimal("10.00"),repaired.getTotalSales());assertEquals(0,repaired.getVerifiedHeads());
    }
    @Test void paidHalfStateIsCheckedBeforeVerificationAndAlongsideOtherPaidCash() {
        CmsRegistration normal=row(72,8L,2,2,1,"90.00"),half=row(73,9L,2,1,0,"10.00");
        assertTrue(assertThrows(IllegalStateException.class,()->TopicSettlementCashBasis.fromLockedRows(11L,List.of(normal,half)))
                .getMessage().contains("PAID_REGISTRATION_HALF_STATE_UNRESOLVED"));
        half.setVerificationStatus(1);
        assertTrue(assertThrows(IllegalStateException.class,()->TopicSettlementCashBasis.fromLockedRows(11L,List.of(half)))
                .getMessage().contains("PAID_REGISTRATION_HALF_STATE_UNRESOLVED"));
        half.setPaymentStatus(1);
        assertTrue(assertThrows(IllegalStateException.class,()->TopicSettlementCashBasis.fromLockedRows(11L,List.of(half)))
                .getMessage().contains("PAYMENT_CONFIRMATION_UNRESOLVED"));
    }
    private CmsRegistration row(long id, Long member, int payment, int registration, int verified, String amount) {
        CmsRegistration row = new CmsRegistration(); row.setId(id); row.setOwnerType(1L); row.setOwnerId(11L);
        row.setMemberId(member); row.setPaymentStatus(payment); row.setRegistrationStatus(registration);
        row.setVerificationStatus(verified); row.setPayableAmount(new BigDecimal(amount)); return row;
    }
}
