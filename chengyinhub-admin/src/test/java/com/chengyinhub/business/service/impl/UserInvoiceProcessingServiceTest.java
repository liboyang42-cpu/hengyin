package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Behavior only. Lock/CAS/rollback proofs live in UserInvoiceLifecycleMySqlIT. */
class UserInvoiceProcessingServiceTest {
    UserInvoiceMapper invoices; UserInvoiceProcessingMapper processing; IRefundService refunds;
    PiiCryptoService crypto; UserInvoiceProcessingService service;
    UserInvoiceApplication application; Map<String,Object> state;
    @BeforeEach void setup() {
        invoices=mock(UserInvoiceMapper.class); processing=mock(UserInvoiceProcessingMapper.class);
        refunds=mock(IRefundService.class); crypto=mock(PiiCryptoService.class);
        when(crypto.isEnabled()).thenReturn(true); when(crypto.blindIndex(anyString())).thenAnswer(c->c.getArgument(0));
        application=new UserInvoiceApplication(); application.setId(Long.MAX_VALUE); application.setStatus("PROCESSING");
        application.setTaxNo("913100001234567890"); application.setOrders(List.of());
        state=new HashMap<>(Map.of("version",2L,"issuer_id",301L,"handler_id",901L,"operation_id","original",
            "can_handle",1,"can_reconcile",0,"reconciliation_user_id",902L,"reconciliation_status","NONE"));
        when(invoices.selectApplicationById(Long.MAX_VALUE)).thenReturn(application);
        when(processing.lockApplication(Long.MAX_VALUE)).thenReturn(application);
        when(processing.lockProcessing(Long.MAX_VALUE)).thenReturn(state);
        when(processing.scope(Long.MAX_VALUE,901L)).thenReturn(state);
        when(processing.scopeRead(Long.MAX_VALUE,901L)).thenReturn(state);
        // MyBatis resultType=map yields null for an absent audit row; Mockito's default would be an empty map.
        when(processing.receipt(anyLong(),anyLong(),anyString())).thenReturn(null);
        UserInvoiceOrderLine line=new UserInvoiceOrderLine(); line.setOrderId(1L);
        when(invoices.selectOrderLines(Long.MAX_VALUE)).thenReturn(List.of(line));
        CmsRegistration row=new CmsRegistration(); row.setId(1L); row.setPaymentStatus(2); row.setRegistrationStatus(2);
        when(invoices.selectInvoiceRegistrationById(1L)).thenReturn(row);
        when(invoices.lockInvoiceRegistration(1L)).thenReturn(row);
        when(refunds.selectRegistrationHistoryForUpdate(1L)).thenReturn(List.of());
        when(processing.transition(anyLong(),anyString(),anyString(),any(),any())).thenReturn(1);
        when(processing.advance(anyLong(),anyLong(),any(),any(),any(),any())).thenReturn(1);
        service=new UserInvoiceProcessingService(invoices,processing,refunds,crypto);
    }
    private UserInvoiceProcessingService.Command command(String action,String operation) {
        return new UserInvoiceProcessingService.Command("command",2L,action,operation,"NO-REAL-INVOICE","evidence:1",null);
    }
    @Test void maskedDetailsAndUnauthorizedTaxNeverLeak() {
        Map<String,Object> view=service.detail(901L,Long.MAX_VALUE);
        assertEquals("91******7890",view.get("taxNoMasked")); assertFalse(view.containsKey("taxNo"));
        assertEquals(Long.toString(Long.MAX_VALUE),view.get("id"));
        assertThrows(UserInvoiceException.class,()->service.revealTaxNumber(903L,Long.MAX_VALUE,"tax-read"));
        verify(processing,never()).audit(anyLong(),anyLong(),any(),any(),any(),any(),anyLong(),any());
    }
    @Test void authorizedTaxViewAuditsMetadataAndAuditFailureReturnsNoTax() {
        assertEquals(application.getTaxNo(),service.revealTaxNumber(901L,Long.MAX_VALUE,"tax-read").get("taxNo"));
        verify(processing).audit(eq(Long.MAX_VALUE),eq(901L),eq("tax-read"),anyString(),eq("TAX_VIEW"),eq("PROCESSING"),eq(2L),isNull());
        when(processing.audit(anyLong(),anyLong(),any(),any(),any(),any(),anyLong(),any())).thenThrow(new IllegalStateException("audit unavailable"));
        assertThrows(IllegalStateException.class,()->service.revealTaxNumber(901L,Long.MAX_VALUE,"tax-again"));
    }
    @Test void unknownRetainsOperationAndOccupancyAndUsesCurrentRefundReadBeforeApplicationLock() {
        assertEquals("ISSUE_UNKNOWN",service.command(901L,Long.MAX_VALUE,command("UNKNOWN","original")).get("status"));
        verify(processing).advance(Long.MAX_VALUE,2L,901L,"original","NONE",null);
        verify(invoices,never()).releaseOrderLines(anyLong());
        var order=inOrder(invoices,refunds,processing);
        order.verify(invoices).lockInvoiceRegistration(1L);
        order.verify(refunds).selectRegistrationHistoryForUpdate(1L);
        order.verify(processing).lockApplication(Long.MAX_VALUE);
    }
    @Test void approvedRefundEvenFailedPayoutPausesUnissuedProcessing() {
        RefundApplication refund=new RefundApplication(); refund.setStatus(1); refund.setPayoutStatus(2);
        when(refunds.selectRegistrationHistoryForUpdate(1L)).thenReturn(List.of(refund));
        assertEquals("PAUSED",service.command(901L,Long.MAX_VALUE,command("UNKNOWN","original")).get("status"));
        verify(processing).transition(Long.MAX_VALUE,"PROCESSING","PAUSED",null,null);
        verify(processing).advance(Long.MAX_VALUE,2L,901L,"original","REQUIRED",null);
        verify(invoices,never()).releaseOrderLines(anyLong());
    }
    /** R625 major (b):外部已开具的发票是事实,退款在途也必须记 ISSUED 并保留票号,只标税务复核。 */
    @Test void recordIssuedDuringRefundKeepsIssuedStatusAndInvoiceNumberForTaxReview() {
        RefundApplication refund=new RefundApplication(); refund.setStatus(1); refund.setPayoutStatus(2);
        when(refunds.selectRegistrationHistoryForUpdate(1L)).thenReturn(List.of(refund));
        when(processing.reserveIssuedReference(Long.MAX_VALUE,301L,"NO-REAL-INVOICE")).thenReturn(1);
        assertEquals("ISSUED",service.command(901L,Long.MAX_VALUE,command("RECORD_ISSUED","original")).get("status"));
        verify(processing).transition(Long.MAX_VALUE,"PROCESSING","ISSUED","NO-REAL-INVOICE",null);
        verify(processing).advance(Long.MAX_VALUE,2L,901L,"original","TAX_REVIEW_REQUIRED","evidence:1");
        verify(processing,never()).transition(anyLong(),anyString(),eq("PAUSED"),any(),any());
        verify(invoices,never()).releaseOrderLines(anyLong());
    }
    @Test void refundFactAfterIssueNeverDemotesIssuedOrDropsInvoiceNumber() {
        application.setStatus("ISSUED"); application.setInvoiceNo("NO-REAL-INVOICE");
        RefundApplication refund=new RefundApplication(); refund.setStatus(0);
        when(refunds.selectRegistrationHistoryForUpdate(1L)).thenReturn(List.of(refund));
        when(processing.affectedApplications(List.of(1L))).thenReturn(List.of(Long.MAX_VALUE));
        service.acceptRegistrationRefundFact(1L);
        verify(processing,never()).transition(anyLong(),any(),any(),any(),any());
        verify(processing).advance(Long.MAX_VALUE,2L,901L,"original","TAX_REVIEW_REQUIRED",null);
        verify(processing).audit(eq(Long.MAX_VALUE),eq(0L),eq("refund_review_3"),anyString(),eq("REFUND_TAX_REVIEW"),eq("ISSUED"),eq(3L),isNull());
        assertEquals("NO-REAL-INVOICE",application.getInvoiceNo());
        state.put("reconciliation_status","TAX_REVIEW_REQUIRED"); clearInvocations(processing);
        service.acceptRegistrationRefundFact(1L);
        verify(processing,never()).advance(anyLong(),anyLong(),any(),any(),any(),any());
        verify(processing,never()).audit(anyLong(),anyLong(),any(),any(),any(),any(),anyLong(),any());
    }
    @Test void pausedAndEmptyRefundHistoryDoesNotResumeAndCommitsDefiniteDeclineReceipt() {
        application.setStatus("PAUSED");
        assertEquals("DECLINED",service.command(901L,Long.MAX_VALUE,command("START",null)).get("status"));
        verify(processing,never()).transition(anyLong(),any(),any(),any(),any());
        verify(processing).audit(eq(Long.MAX_VALUE),eq(901L),eq("command"),anyString(),eq("START"),eq("DECLINED"),eq(2L),isNull());
    }
    @Test void originalReceiptIsExactAndMissingReceiptNeverMutates() {
        assertThrows(UserInvoiceException.class,()->service.receipt(901L,Long.MAX_VALUE,"absent"));
        when(processing.receipt(Long.MAX_VALUE,901L,"original")).thenReturn(Map.of("action","START","result_status","PROCESSING","result_version",2L,"fingerprint","private"));
        Map<String,Object> receipt=service.receipt(901L,Long.MAX_VALUE,"original");
        assertFalse(receipt.containsKey("fingerprint")); assertEquals("original",receipt.get("requestId"));
        verify(processing,never()).advance(anyLong(),anyLong(),any(),any(),any(),any());
    }
    @Test void differentPayloadOnSameRequestIsRejectedWithoutMutation() {
        when(processing.receipt(Long.MAX_VALUE,901L,"command")).thenReturn(Map.of("fingerprint","other-payload"));
        assertThrows(UserInvoiceException.class,()->service.command(901L,Long.MAX_VALUE,command("UNKNOWN","original")));
        verify(processing,never()).advance(anyLong(),anyLong(),any(),any(),any(),any());
    }

    @Test void taxCannotBeSmuggledIntoPublicInvoiceNumberOrEvidenceReference() {
        var c=new UserInvoiceProcessingService.Command("no-tax-in-evidence",2L,"RECORD_ISSUED","original","NOT-REAL",application.getTaxNo(),null);
        assertThrows(UserInvoiceException.class,()->service.command(901L,Long.MAX_VALUE,c));
        verify(processing,never()).audit(anyLong(),anyLong(),any(),any(),any(),any(),anyLong(),any());
        verify(processing,never()).transition(anyLong(),any(),any(),any(),any());
    }
}
