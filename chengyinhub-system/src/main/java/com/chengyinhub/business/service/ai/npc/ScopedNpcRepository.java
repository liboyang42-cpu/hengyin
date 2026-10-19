package com.chengyinhub.business.service.ai.npc;

import java.time.Instant;
import java.util.Collections;
import java.util.TreeSet;
import java.util.List;
import java.util.Set;
import com.chengyinhub.business.service.ai.scope.*;

/**
 * Internal authoritative registry + durable request ledger adapter. No default implementation exists:
 * legacy profile approval and W02 provenance alone cannot prove public publication or run eligibility.
 * Implementations MUST read current server records, never deserialize client authority claims.
 */
public interface ScopedNpcRepository
{
    /** Join live session/principal/consent, merchant owner approval, run membership and frozen release. */
    Access resolveCurrent(long authenticatedMemberId, ScopedNpcRequest locator, Instant now);

    /**
     * Hard filter BEFORE retrieval/ranking: exact scope, approved source set/revision, allowed IDs,
     * kind, publication, visibility and expiry. No global search or raw NpcProfile.knowledge fallback.
     * Return complete selected rows (at most Query.limit); no model interprets this predicate.
     */
    List<KnowledgeRow> retrieve(Query query, Instant now);

    /**
     * Durable atomic CAS, unique (principal, requestId), comparing current authority/source versions.
     * Same request with changed digest/key is CONFLICT, processing is BUSY. Terminal replay is allowed
     * only with the exact key and original unexpired deadline. No TTL extension on a hit.
     * A stale/lost claim MUST NOT be reassigned to another model call under the same requestId.
     */
    Claim claim(long principalId, String requestId, String requestDigest, String cacheKey,
                Access access, Instant expiresAt, Instant now);

    /** Must atomically compare ALL authority/source/consent/run versions to the passed snapshot. */
    boolean isCurrent(Access access, Instant now);

    /** Commit only with the owned fencing token AND current evidence, original deadline, active consent.
     * Persist outcome and redacted audit atomically; return false on lost ownership/stale authority. */
    boolean finish(String token, Access access, String cacheKey, ScopedNpcReply reply,
                   List<String> retrievedSourceIds, String promptHash, Instant now);

    /** Redacted denial/fallback metadata only, no prompt, retrieved text, output or personal fields. */
    void recordDecision(String traceId, String scopeHash, String reason);

    enum State { ACTIVE, REVOKED }

    /** Server-owned records; public fields are not HTTP DTO fields or evidence by themselves. */
    final class Access
    {
        public final long principalId, actorId, merchantId, ownerMemberId, profileId, dataEpoch, conversationGeneration;
        public final State state;
        public final String decisionId, snapshotVersion, policyVersion, consentVersion, publicSetVersion,
                modelPolicyVersion, conversationId, publicPublicationReference;
        public final Instant validUntil;
        public final AiRetentionPolicy retention;
        public final Theme theme;
        public final Set<String> publicSourceIds;

        public Access(long principalId, long actorId, long merchantId, long ownerMemberId, long profileId,
                      long dataEpoch, long conversationGeneration, State state, String decisionId,
                      String snapshotVersion, String policyVersion, String consentVersion, String publicSetVersion,
                      String modelPolicyVersion, String conversationId, String publicPublicationReference, Instant validUntil,
                      AiRetentionPolicy retention, Set<String> publicSourceIds, Theme theme)
        {
            this.principalId = principalId; this.actorId = actorId; this.merchantId = merchantId;
            this.ownerMemberId = ownerMemberId; this.profileId = profileId; this.dataEpoch = dataEpoch;
            this.conversationGeneration = conversationGeneration; this.state = state; this.decisionId = decisionId;
            this.snapshotVersion = snapshotVersion; this.policyVersion = policyVersion; this.consentVersion = consentVersion;
            this.publicSetVersion = publicSetVersion; this.modelPolicyVersion = modelPolicyVersion;
            this.conversationId = conversationId; this.publicPublicationReference = publicPublicationReference; this.validUntil = validUntil; this.retention = retention;
            this.publicSourceIds = immutableIds(publicSourceIds); this.theme = theme;
        }
    }

    final class Theme
    {
        public final long themeId, releaseId, runId, actorId, bindingId, bindingMerchantId, bindingProfileId,
                bindingReleaseId, runThemeId, runReleaseId, bindingThemeId, themeOwnerMemberId;
        public final int releaseSchemaVersion;
        public final String releaseHash, publicationReference, reviewReference, eligibilityReference,
                packId, packHash, releasedPackHash, sourceSetVersion, runStateVersion;
        public final State state;
        public final AiReleaseReference.Readiness readiness;
        public final Set<String> visibleSourceIds;
        public Theme(long themeId, long releaseId, long runId, long actorId, long bindingId,
                     long bindingMerchantId, long bindingProfileId, long bindingReleaseId,
                     long runThemeId, long runReleaseId, long bindingThemeId, long themeOwnerMemberId,
                     String releaseHash, String publicationReference, String reviewReference,
                     String eligibilityReference, String packId, String packHash, String releasedPackHash,
                     String sourceSetVersion, String runStateVersion, State state, AiReleaseReference.Readiness readiness, int releaseSchemaVersion,
                     Set<String> visibleSourceIds)
        {
            this.themeId = themeId; this.releaseId = releaseId; this.runId = runId; this.actorId = actorId;
            this.bindingId = bindingId; this.bindingMerchantId = bindingMerchantId;
            this.bindingProfileId = bindingProfileId; this.bindingReleaseId = bindingReleaseId;
            this.runThemeId = runThemeId; this.runReleaseId = runReleaseId; this.bindingThemeId = bindingThemeId;
            this.themeOwnerMemberId = themeOwnerMemberId; this.releaseSchemaVersion = releaseSchemaVersion;
            this.releaseHash = releaseHash; this.publicationReference = publicationReference;
            this.reviewReference = reviewReference; this.eligibilityReference = eligibilityReference;
            this.packId = packId; this.packHash = packHash; this.releasedPackHash = releasedPackHash;
            this.sourceSetVersion = sourceSetVersion; this.runStateVersion = runStateVersion;
            this.state = state; this.readiness = readiness; this.visibleSourceIds = immutableIds(visibleSourceIds);
        }
    }

    final class Query
    {
        public final AiScope scope;
        public final AiKnowledge.SourceKind kind;
        public final String sourceSetVersion, publicationReference, packId, packHash;
        public final long approvingOwnerMemberId;
        public final Set<String> allowedSourceIds;
        public final int limit = 8;
        Query(AiScope scope, AiKnowledge.SourceKind kind, String version, String publicationReference,
              String packId, String packHash, long owner, Set<String> sourceIds)
        {
            this.scope = scope; this.kind = kind; this.sourceSetVersion = version;
            this.publicationReference = publicationReference; this.packId = packId; this.packHash = packHash;
            this.approvingOwnerMemberId = owner; this.allowedSourceIds = sourceIds;
        }
    }

    final class KnowledgeRow
    {
        public final AiKnowledge knowledge;
        public final String sourceSetVersion, publicationReference, packId, packHash;
        public final long approvingOwnerMemberId;
        public KnowledgeRow(AiKnowledge knowledge, String sourceSetVersion, String publicationReference,
                            String packId, String packHash, long approvingOwnerMemberId)
        {
            this.knowledge = knowledge; this.sourceSetVersion = sourceSetVersion;
            this.publicationReference = publicationReference; this.packId = packId; this.packHash = packHash;
            this.approvingOwnerMemberId = approvingOwnerMemberId;
        }
    }

    final class Claim
    {
        public enum Status { OWNED, REPLAY, BUSY, CONFLICT, STALE }
        public final Status status;
        public final String token, cacheKey;
        public final Instant expiresAt;
        public final ScopedNpcReply reply;
        public Claim(Status status, String token, String cacheKey, Instant expiresAt, ScopedNpcReply reply)
        {
            this.status = status; this.token = token; this.cacheKey = cacheKey;
            this.expiresAt = expiresAt; this.reply = reply;
        }
    }

    static Set<String> immutableIds(Set<String> source)
    {
        if (source == null || source.size() > 64) throw new IllegalArgumentException("bounded source set required");
        // Sets are authority membership, not order: stabilize both public and theme snapshot hashes.
        return Collections.unmodifiableSet(new TreeSet<>(source));
    }
}
