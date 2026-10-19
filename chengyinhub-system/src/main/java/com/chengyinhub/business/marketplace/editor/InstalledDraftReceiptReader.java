package com.chengyinhub.business.marketplace.editor;

/** Read-only seam for the existing content-draft restore path. Not a content-use authority. */
public interface InstalledDraftReceiptReader {
    InstalledDraftReceipts read(long authenticatedMemberId, long draftId, long expectedVersion, String expectedPayloadHash);
}
