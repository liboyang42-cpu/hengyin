package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IRefundService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real projection behavior; no funds write, provider call or transaction is simulated as accepted. */
class RegistrationCancellationCashReadTest {
    private final ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
    private final IRefundService refunds = mock(IRefundService.class);
    private final RegistrationCancellationReadService service =
            new RegistrationCancellationReadService(registrations, refunds);

    @Test
    void legacyCashSnapshotReadsOriginalRefundInsteadOfClaimingNoRefundNeeded() {
        CmsRegistration row = registration(11L, "25.00", null);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(row);
        when(refunds.selectRefundList(any())).thenAnswer(call -> {
            RefundApplication query = call.getArgument(0);
            assertEquals("registration", query.getSourceType());
            assertEquals(11L, query.getSourceId());
            assertEquals(71L, query.getMemberId());
            return Collections.singletonList(refund(RefundApplication.PAYOUT_PROCESSING));
        });

        RegistrationCancellationReadService.Result result = service.read(11L);

        assertEquals("PROCESSING", result.getData().get("cashRefundStatus"));
        assertEquals(new BigDecimal("25.00"), result.getData().get("cashRefundAmount"));
        assertFalse(result.getMessage().contains("无现金退款"));
        assertTrue(result.getMessage().contains("尚未确认到账"));
    }

    @Test
    void unknownOrInvalidCashIsNotZeroAndDoesNotDiscardCancellationAndPointFacts() {
        for (String[] amounts : new String[][] {{null, null}, {"-1", null}, {"25", "-1"}}) {
            CmsRegistration row = registration(11L, amounts[0], amounts[1]);
            row.setPointUsed(50L);
            row.setPointsReturned(1);
            when(registrations.selectCmsRegistrationById(11L)).thenReturn(row);

            Map<String, Object> data = service.read(11L).getData();

            assertEquals("CANCELLED", data.get("cancellationStatus"));
            assertEquals("UNCONFIRMED", data.get("cashRefundStatus"));
            assertNull(data.get("cashRefundAmount"));
            assertEquals(50L, data.get("pointsReturned"));
            assertEquals("RETURNED", data.get("pointsRefundStatus"));
        }
        verifyNoInteractions(refunds);
    }

    @Test
    void explicitZeroCashRemainsAuthoritativeAndDoesNotBecomePayableCash() {
        when(registrations.selectCmsRegistrationById(11L))
                .thenReturn(registration(11L, "25", "0"));
        assertEquals("NOT_NEEDED", service.read(11L).getData().get("cashRefundStatus"));
        verifyNoInteractions(refunds);
    }

    @Test
    void parentUsesPayerPayableSnapshotAndNeverTreatsChildPlaceholdersAsCash() {
        CmsRegistration child = registration(11L, "0", "999");
        CmsRegistration payer = registration(12L, "25", null);
        child.setParentOrderNo("ORDER-ONE");
        payer.setParentOrderNo("ORDER-ONE");
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(child);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Arrays.asList(child, payer));
        when(refunds.selectRefundList(any())).thenAnswer(call -> {
            RefundApplication query = call.getArgument(0);
            assertEquals(12L, query.getSourceId());
            assertEquals(71L, query.getMemberId());
            return Collections.singletonList(refund(RefundApplication.PAYOUT_DISPATCH_PENDING));
        });

        assertEquals("DISPATCH_PENDING", service.read(11L).getData().get("cashRefundStatus"));
        clearInvocations(refunds);
        payer.setPayableAmount(BigDecimal.ZERO);
        assertEquals("NOT_NEEDED", service.read(11L).getData().get("cashRefundStatus"));
        verifyNoInteractions(refunds);
    }

    @Test
    void parentWithoutProvableCashSnapshotIsUnknownEvenWhenChildHasPlaceholder() {
        CmsRegistration child = registration(11L, "0", "999");
        CmsRegistration unknown = registration(12L, null, "999");
        child.setParentOrderNo("ORDER-ONE");
        unknown.setParentOrderNo("ORDER-ONE");
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(child);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Arrays.asList(child, unknown));

        assertEquals("UNCONFIRMED", service.read(11L).getData().get("cashRefundStatus"));
        verifyNoInteractions(refunds);
    }

    @Test
    void refundAbsenceOrReadFailureDoesNotProveThatCashRefundIsUnnecessary() {
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(registration(11L, "25", null));
        when(refunds.selectRefundList(any())).thenReturn(Collections.emptyList());
        assertEquals("UNCONFIRMED", service.read(11L).getData().get("cashRefundStatus"));
        when(refunds.selectRefundList(any())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals("UNCONFIRMED", service.read(11L).getData().get("cashRefundStatus"));
    }

    @Test
    void exactLongAndBuyerScopeArePreservedWhenReadingRefundProgress() {
        long id = 9007199254740993L;
        CmsRegistration row = registration(id, "25", null);
        row.setMemberId(Long.MAX_VALUE);
        when(registrations.selectCmsRegistrationById(id)).thenReturn(row);
        when(refunds.selectRefundList(any())).thenAnswer(call -> {
            RefundApplication query = call.getArgument(0);
            assertEquals(id, query.getSourceId());
            assertEquals(Long.MAX_VALUE, query.getMemberId());
            return Collections.singletonList(refund(RefundApplication.PAYOUT_PROCESSING));
        });
        RegistrationCancellationReadService.Result result = service.read(id);
        assertEquals(id, result.getData().get("registrationId"));
        assertEquals("PROCESSING", result.getData().get("cashRefundStatus"));
    }

    @Test
    void unapprovedApplicationCannotClaimArrivalEvenWithASuccessPayoutField() {
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(registration(11L, "25", null));
        RefundApplication application = refund(RefundApplication.PAYOUT_SUCCESS);
        application.setStatus(0);
        when(refunds.selectRefundList(any())).thenReturn(Collections.singletonList(application));
        RegistrationCancellationReadService.Result result = service.read(11L);
        assertEquals("UNCONFIRMED", result.getData().get("cashRefundStatus"));
        assertNull(result.getData().get("cashRefundAmount"));
        assertFalse(result.getMessage().contains("已确认到账"));
    }

    @Test
    void invalidLegacyPointsCannotBeReportedAsAConfirmedNegativeReturn() {
        CmsRegistration row = registration(11L, "0", "0");
        row.setPointUsed(-1L);
        row.setPointsReturned(1);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(row);
        RegistrationCancellationReadService.Result result = service.read(11L);
        assertEquals("CANCELLED", result.getData().get("cancellationStatus"));
        assertEquals("NOT_NEEDED", result.getData().get("cashRefundStatus"));
        assertEquals("UNCONFIRMED", result.getData().get("pointsRefundStatus"));
        assertNull(result.getData().get("pointsUsed"));
        assertNull(result.getData().get("pointsReturned"));
        assertFalse(result.getMessage().contains("返还-1"));
    }

    @Test
    void parentPointSumOverflowKeepsCashAndCancellationFactsWithoutWrapping() {
        CmsRegistration first = registration(11L, "0", "999");
        CmsRegistration second = registration(12L, "0", "999");
        first.setParentOrderNo("ORDER-POINTS");
        second.setParentOrderNo("ORDER-POINTS");
        first.setPointUsed(Long.MAX_VALUE);
        second.setPointUsed(1L);
        first.setPointsReturned(1);
        second.setPointsReturned(1);
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(first);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Arrays.asList(first, second));
        Map<String, Object> data = service.read(11L).getData();
        assertEquals("CANCELLED", data.get("cancellationStatus"));
        assertEquals("NOT_NEEDED", data.get("cashRefundStatus"));
        assertEquals("UNCONFIRMED", data.get("pointsRefundStatus"));
        assertNull(data.get("pointsUsed"));
        assertNull(data.get("pointsReturned"));
        verifyNoInteractions(refunds);
    }

    @Test
    void onlySuccessfulPayoutSaysArrivedAndDuplicateReadsNeverWrite() {
        when(registrations.selectCmsRegistrationById(11L)).thenReturn(registration(11L, "25", null));
        int[] statuses = {RefundApplication.PAYOUT_DISPATCH_PENDING, RefundApplication.PAYOUT_DISPATCHING,
                RefundApplication.PAYOUT_PROCESSING, RefundApplication.PAYOUT_PENDING_MANUAL,
                RefundApplication.PAYOUT_MANUAL_HANDLED, RefundApplication.PAYOUT_MANUAL_VERIFIED,
                RefundApplication.PAYOUT_SUCCESS};
        String[] expected = {"DISPATCH_PENDING", "DISPATCHING", "PROCESSING", "PENDING_MANUAL",
                "MANUAL_HANDLED", "MANUAL_VERIFIED", "SUCCESS"};
        for (int i = 0; i < statuses.length; i++) {
            when(refunds.selectRefundList(any())).thenReturn(Collections.singletonList(refund(statuses[i])));
            for (int repeat = 0; repeat < 2; repeat++) {
                RegistrationCancellationReadService.Result result = service.read(11L);
                assertEquals(expected[i], result.getData().get("cashRefundStatus"));
                assertEquals(statuses[i] == RefundApplication.PAYOUT_SUCCESS,
                        result.getMessage().contains("已确认到账"));
            }
        }
        verify(registrations, times(statuses.length * 2)).selectCmsRegistrationById(11L);
        verify(refunds, times(statuses.length * 2)).selectRefundList(any());
        verifyNoMoreInteractions(registrations, refunds);
    }

    private CmsRegistration registration(Long id, String payable, String cash) {
        CmsRegistration row = new CmsRegistration();
        row.setId(id);
        row.setMemberId(71L);
        row.setRegistrationStatus(3);
        row.setPayableAmount(payable == null ? null : new BigDecimal(payable));
        row.setWechatPaymentAmount(cash == null ? null : new BigDecimal(cash));
        return row;
    }

    private RefundApplication refund(int payout) {
        RefundApplication row = new RefundApplication();
        row.setStatus(1);
        row.setPayoutStatus(payout);
        row.setRefundAmount(new BigDecimal("25.00"));
        return row;
    }
}
