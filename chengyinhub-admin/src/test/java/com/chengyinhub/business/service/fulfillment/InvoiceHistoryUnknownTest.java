package com.chengyinhub.business.service.fulfillment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.service.fulfillment.InvoiceHistoryUnknown.ApplicationFact;
import com.chengyinhub.business.service.fulfillment.InvoiceHistoryUnknown.FileStatus;
import com.chengyinhub.business.service.fulfillment.InvoiceHistoryUnknown.View;
import com.chengyinhub.business.service.invoice.ManualInvoiceIssuer;

class InvoiceHistoryUnknownTest {

    private final InvoiceHistoryUnknown history = new InvoiceHistoryUnknown();

    @Test
    void submittedManualApplicationHasUnknownFileAndDoesNotStartNewApply() {
        ApplicationFact fact = new ApplicationFact("c07-inv-1",
                UserInvoiceApplication.STATUS_SUBMITTED, "", "", ManualInvoiceIssuer.NAME);
        View view = history.reread(fact);
        assertEquals(FileStatus.UNKNOWN, view.fileStatus());
        assertFalse(view.startedNewApply());
        assertTrue(view.historyIsNotAFile());
        assertEquals("c07-inv-1", view.originalRequestId());
        assertEquals(UserInvoiceApplication.STATUS_SUBMITTED, view.applicationStatus());
        assertEquals(view.fileStatus(), history.requery(fact).fileStatus());
    }

    @Test
    void issuedInvoiceNoWithoutFileIsStillUnknown() {
        ApplicationFact fact = new ApplicationFact("c07-inv-2",
                UserInvoiceApplication.STATUS_ISSUED, "INV-9", "", ManualInvoiceIssuer.NAME);
        View view = history.reread(fact);
        assertEquals(FileStatus.UNKNOWN, view.fileStatus());
        assertFalse(view.startedNewApply());
        assertEquals("c07-inv-2", view.originalRequestId());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniquePresentApplies() {
        InvoiceHistoryUnknown invoices = new InvoiceHistoryUnknown();
        assertEquals(FileStatus.UNKNOWN, invoices.recoverOriginal("c07-inv-never-seen").fileStatus());
        assertEquals("c07-inv-never-seen", invoices.recoverOriginal("c07-inv-never-seen").originalRequestId());
        invoices.reread(new ApplicationFact("c07-inv-never-seen", "SUBMITTED", "", "", "MANUAL"));
        assertEquals(FileStatus.UNKNOWN, invoices.recoverOriginal("c07-inv-never-seen").fileStatus());
        invoices.reread(new ApplicationFact("c07-inv-never-seen",
                UserInvoiceApplication.STATUS_ISSUED, "INV-9", "file://x", "OTHER"));
        assertEquals(FileStatus.PRESENT, invoices.recoverOriginal("c07-inv-never-seen").fileStatus());
    }
}
