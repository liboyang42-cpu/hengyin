package com.chengyinhub.business.service.fulfillment;

import java.util.concurrent.ConcurrentHashMap;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.service.invoice.ManualInvoiceIssuer;

/**
 * C07 exclusive adapter: invoice file / history reread.
 *
 * <p>{@link com.chengyinhub.business.service.impl.UserInvoiceServiceImpl} stores
 * application rows keyed by {@code (member_id, request_id)} and
 * {@link ManualInvoiceIssuer} never produces a file ({@code accepted=false},
 * {@code providerRef=null}). There is no invoice file locator on this remote.
 * Missing file is {@link FileStatus#UNKNOWN} and must not start a new
 * {@code apply()} request.
 */
public final class InvoiceHistoryUnknown {

    public enum FileStatus {
        PRESENT,
        UNKNOWN
    }

    public record ApplicationFact(
            String originalRequestId,
            String status,
            String invoiceNo,
            String fileLocator,
            String provider) {

        public ApplicationFact {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            status = status == null ? "" : status;
            invoiceNo = invoiceNo == null ? "" : invoiceNo;
            fileLocator = fileLocator == null ? "" : fileLocator;
            provider = provider == null ? "" : provider;
        }
    }

    public record View(
            String originalRequestId,
            FileStatus fileStatus,
            boolean startedNewApply,
            String applicationStatus,
            boolean historyIsNotAFile) {

        public View {
            originalRequestId = FulfillmentEvidence.blankToNull(originalRequestId);
            fileStatus = fileStatus == null ? FileStatus.UNKNOWN : fileStatus;
            applicationStatus = applicationStatus == null ? "" : applicationStatus;
        }
    }

    private final ConcurrentHashMap<String, View> originalByRequestId = new ConcurrentHashMap<String, View>();

    /**
     * Reread the original invoice request. Application history (SUBMITTED /
     * ISSUED / invoiceNo) is not a file. Manual provider never yields PRESENT.
     * PRESENT original is unique; UNKNOWN observation does not occupy.
     */
    public View reread(ApplicationFact fact) {
        View view = compute(fact);
        if (view.originalRequestId() != null && view.fileStatus() == FileStatus.PRESENT) {
            originalByRequestId.putIfAbsent(view.originalRequestId(), view);
        }
        return view;
    }

    public View requery(ApplicationFact fact) {
        return reread(fact);
    }

    public View recoverOriginal(String originalRequestId) {
        String id = FulfillmentEvidence.blankToNull(originalRequestId);
        if (id == null) {
            return unknown(null, "");
        }
        View original = originalByRequestId.get(id);
        return original == null ? unknown(id, "") : original;
    }

    private static View compute(ApplicationFact fact) {
        if (fact == null) {
            return unknown(null, "");
        }
        String requestId = fact.originalRequestId();
        if (requestId == null) {
            return unknown(null, fact.status());
        }
        if (ManualInvoiceIssuer.NAME.equals(fact.provider()) || fact.fileLocator().isBlank()) {
            return new View(requestId, FileStatus.UNKNOWN, false, fact.status(), true);
        }
        if (UserInvoiceApplication.STATUS_ISSUED.equals(fact.status()) && !fact.fileLocator().isBlank()) {
            return new View(requestId, FileStatus.PRESENT, false, fact.status(), true);
        }
        return new View(requestId, FileStatus.UNKNOWN, false, fact.status(), true);
    }

    private static View unknown(String requestId, String status) {
        return new View(requestId, FileStatus.UNKNOWN, false, status, true);
    }
}
