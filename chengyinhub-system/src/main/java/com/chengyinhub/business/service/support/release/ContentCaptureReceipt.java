package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.ContentCaptureSnapshot;
import com.fasterxml.jackson.databind.JsonNode;

/** Immutable persisted candidate receipt. snapshotId is never an assigned releaseId. */
public final class ContentCaptureReceipt {
    private final long snapshotId;
    private final ContentCaptureCandidate candidate;

    private ContentCaptureReceipt(long snapshotId, ContentCaptureCandidate candidate) {
        this.snapshotId=snapshotId; this.candidate=candidate;
    }
    public long snapshotId() { return snapshotId; }
    public long topicId() { return candidate.topicId(); }
    public long draftConfigVersion() { return candidate.draftConfigVersion(); }
    public String contentHash() { return candidate.contentHash(); }
    public String serverOnlyJson() { return candidate.serverOnlyJson(); }
    public boolean isCompleteRelease() { return false; }
    public void requireCompleteRelease() { candidate.requireCompleteRelease(); }

    static ContentCaptureReceipt verify(ContentCaptureSnapshot row, long topicId, long memberId,
                                        String requestId, long version, String hash) {
        if (row == null || row.getId() == null || row.getId() <= 0 || !Long.valueOf(topicId).equals(row.getTopicId())
                || !Long.valueOf(memberId).equals(row.getCapturedByMemberId()) || !requestId.equals(row.getRequestId())
                || !Long.valueOf(version).equals(row.getSourceConfigVersion()) || !hash.equals(row.getContentHash())
                || !"FROZEN_INCOMPLETE".equals(row.getState())
                || !"questify.content-capture-candidate.v1".equals(row.getContractVersion())
                || !"questify.canonical-content-json.v1".equals(row.getCanonicalizationVersion())) throw conflict();
        JsonNode root=CanonicalContentJson.object(row.getCanonicalJson(),"stored candidate");
        if (!"questify.content-capture-candidate.v1".equals(root.path("contract").asText())
                || !exactLong(root.path("schemaVersion"),1) || !exactLong(root.path("topicId"),topicId)
                || !exactLong(root.path("draftConfigVersion"),version)
                || !root.path("completeRelease").isBoolean() || root.path("completeRelease").booleanValue()
                || !row.getCanonicalJson().equals(CanonicalContentJson.write(root))
                || !hash.equals(CanonicalContentJson.sha256(row.getCanonicalJson()))) throw conflict();
        return new ContentCaptureReceipt(row.getId(),new ContentCaptureCandidate(topicId,version,row.getCanonicalJson()));
    }
    private static boolean exactLong(JsonNode node,long expected) { return node.isIntegralNumber() && node.canConvertToLong() && node.longValue()==expected; }
    // Distinguish stored validation failures from unrelated IllegalStateException (e.g. hashing infrastructure).
    static final class InvalidReceiptException extends IllegalStateException {
        private InvalidReceiptException() { super("Stored candidate request or immutable content mismatch"); }
    }
    private static InvalidReceiptException conflict() { return new InvalidReceiptException(); }
}
