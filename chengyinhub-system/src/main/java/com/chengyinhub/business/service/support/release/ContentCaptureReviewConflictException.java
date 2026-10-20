package com.chengyinhub.business.service.support.release;

/** A validated candidate binding/version mismatch; never denotes infrastructure unavailability. */
public final class ContentCaptureReviewConflictException extends IllegalStateException {
    public ContentCaptureReviewConflictException() {
        super("Candidate review source, ownership, request or version mismatch");
    }
}
