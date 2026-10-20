package com.chengyinhub.business.discovery;

/** Internal persistence records. Never API response DTOs or award qualifications. */
public final class EggDiscoveryRows {
    private EggDiscoveryRows() { }
    public static final class ScopeRow {
        public String evaluationKey, scopeKind, ownerId, releaseId, occurrenceId, definitionId;
        public String definitionFingerprint, status, evidenceLevel;
        public long memberId, definitionVersion, revision;
    }
    public static final class ReceiptRow {
        public String evaluationKey, receiptId, definitionFingerprint, discoveredAt, historyHashes, evidenceLevel;
        public long memberId;
    }
    public static final class OutboxRow {
        public String eventId, evaluationKey, eventType, evidenceLevel, recordedAt;
        public long revision;
    }
}
