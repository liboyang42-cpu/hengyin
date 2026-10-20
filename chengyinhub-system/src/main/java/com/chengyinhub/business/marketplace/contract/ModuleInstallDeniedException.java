package com.chengyinhub.business.marketplace.contract;

/** Internal stable rejection reason; a future boundary must use existing AjaxResult/ServiceException. */
public final class ModuleInstallDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Reason {
        BUYER_SCOPE_DENIED, LICENSE_NOT_VERIFIED, LICENSE_INACTIVE, LICENSE_SCOPE_MISMATCH,
        TERMS_MISMATCH, SOURCE_AUTHOR_MISMATCH, VERSION_NOT_INSTALLABLE, VERSION_MISMATCH, VERSION_NOT_GRANTED,
        DRAFT_SCOPE_DENIED, DRAFT_NOT_EDITABLE, DRAFT_REVISION_MISMATCH, REGION_NOT_GRANTED,
        COMMERCIAL_USE_DENIED, ADAPTATION_DENIED, TRANSLATION_DENIED, REDISTRIBUTION_DENIED,
        BINDING_MISMATCH, BINDING_NOT_APPROVED, USAGE_UNAVAILABLE, USE_LIMIT_EXCEEDED
    }
    private final Reason reason;
    public ModuleInstallDeniedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
    public Reason getReason() { return reason; }
}
