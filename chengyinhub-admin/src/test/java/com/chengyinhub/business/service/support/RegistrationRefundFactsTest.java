package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationRefundFactsTest {
    private static final Long REGISTRATION = 9007199254740993L;

    @Test
    void existingHistoryServiceFeedsExactImmutableFactsWithoutTaxOrRecipientFields() {
        RefundApplication row = row(0, RefundApplication.PAYOUT_DISPATCH_PENDING);
        List<RefundApplication> history = new ArrayList<>(List.of(row));
        RefundApplicationMapper mapper = mock(RefundApplicationMapper.class);
        when(mapper.selectRegistrationHistoryForUpdate(REGISTRATION)).thenReturn(history);
        RefundServiceImpl service = new RefundServiceImpl();
        ReflectionTestUtils.setField(service, "refundApplicationMapper", mapper);
        RegistrationRefundFacts facts = RegistrationRefundFacts.fromHistory(REGISTRATION,
                service.selectRegistrationHistoryForUpdate(REGISTRATION));
        row.setStatus(2);
        row.setRefundAmount(BigDecimal.ZERO);
        history.clear();
        assertEquals(REGISTRATION, facts.registrationId());
        assertTrue(facts.hasPendingOrApproved());
        assertFalse(facts.unknownHistory());
        assertEquals(new BigDecimal("12.50"), facts.refunds().get(0).refundAmount());
        assertThrows(UnsupportedOperationException.class, () -> facts.refunds().clear());
        verify(mapper).selectRegistrationHistoryForUpdate(REGISTRATION);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void approvedRefundRemainsApprovedForEveryPayoutStateIncludingFailureAndUnknown() {
        for (Integer payout : Arrays.asList(null, 0, 1, 2, 3, 4, 5, 6, 99)) {
            RegistrationRefundFacts facts = RegistrationRefundFacts.fromHistory(REGISTRATION, List.of(row(1, payout)));
            assertTrue(facts.hasPendingOrApproved());
            assertFalse(facts.unknownHistory());
            assertEquals(payout, facts.refunds().get(0).payoutStatus());
        }
    }

    @Test
    void rejectionDoesNotHideAnotherPendingOrApprovedRefund() {
        assertTrue(RegistrationRefundFacts.fromHistory(REGISTRATION,
                List.of(row(2, 5), row(0, 5))).hasPendingOrApproved());
        assertTrue(RegistrationRefundFacts.fromHistory(REGISTRATION,
                List.of(row(2, 5), row(1, 2))).hasPendingOrApproved());
        RegistrationRefundFacts rejected = RegistrationRefundFacts.fromHistory(REGISTRATION, List.of(row(2, 5)));
        assertFalse(rejected.hasPendingOrApproved());
        assertEquals(2, rejected.refunds().get(0).reviewStatus());
        // Facts expose no resume/issue/refund command. Invoice policy cannot be inferred from false.
    }

    @Test
    void missingOrMalformedHistoryRemainsExplicitlyUnknown() {
        assertTrue(RegistrationRefundFacts.fromHistory(REGISTRATION, null).unknownHistory());
        assertTrue(RegistrationRefundFacts.fromHistory(REGISTRATION, Arrays.asList((RefundApplication) null)).unknownHistory());
        for (Integer status : Arrays.asList(null, -1, 3)) {
            assertTrue(RegistrationRefundFacts.fromHistory(REGISTRATION, List.of(row(status, 0))).unknownHistory());
        }
        RefundApplication invalid = row(1, 0);
        invalid.setRefundAmount(null);
        assertTrue(RegistrationRefundFacts.fromHistory(REGISTRATION, List.of(invalid)).unknownHistory());
        invalid.setRefundAmount(new BigDecimal("-1"));
        assertTrue(RegistrationRefundFacts.fromHistory(REGISTRATION, List.of(invalid)).unknownHistory());
    }

    @Test
    void crossOrderOrWrongSourceHistoryIsNeverSilentlyAccepted() {
        RefundApplication other = row(1, 0);
        other.setSourceId(99L);
        assertThrows(IllegalStateException.class,
                () -> RegistrationRefundFacts.fromHistory(REGISTRATION, List.of(other)));
        other.setSourceId(REGISTRATION);
        other.setSourceType("other");
        assertThrows(IllegalStateException.class,
                () -> RegistrationRefundFacts.fromHistory(REGISTRATION, List.of(other)));
    }

    @Test
    void emptyLockedHistoryIsAnEmptyFactSetAndNeverAnExternalFailureReceipt() {
        RegistrationRefundFacts facts = RegistrationRefundFacts.fromHistory(REGISTRATION, List.of());
        assertFalse(facts.hasPendingOrApproved());
        assertFalse(facts.unknownHistory());
        assertTrue(facts.refunds().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RegistrationRefundFacts.fromHistory(0L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRefundFacts.fromHistory(null, List.of()));
    }

    private RefundApplication row(Integer review, Integer payout) {
        RefundApplication row = new RefundApplication();
        row.setId(9223372036854775806L);
        row.setSourceType("registration");
        row.setSourceId(REGISTRATION);
        row.setDelFlag(0);
        row.setStatus(review);
        row.setPayoutStatus(payout);
        row.setRefundAmount(new BigDecimal("12.50"));
        return row;
    }
}
